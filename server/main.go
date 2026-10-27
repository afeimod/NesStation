// GameBox 对战平台服务器
//
// 零第三方依赖（仅 Go 标准库），构建：
//   cd server && go build -o gamebox-server .
// 运行：
//   ./gamebox-server -config config.json
//
// 功能：
//   - 虚拟账号注册/登录（JWT 鉴权）
//   - 游戏列表 + ROM 统一分发（服务器中转下载，可在配置里指向 GitHub 直链）
//   - 房间系统（创建 / 列表 / 加入 / 离开）
//   - 帧同步中继（TCP）：对战双方输入互相转发，锁步 + 输入延迟
//   - ★ 配置热重载：config.json 变动后 1 秒内自动生效
//       · games / inputDelay / maxRooms / romCacheMax / publicXxxAddr
//         → 就地热替换，不断连接、不打断进行中的对局（加游戏的常用场景）
//       · httpAddr / tcpAddr / jwtSecret / dataDir
//         → 自动优雅重启进程（这三类字段必须重建监听器/JWT/用户存储）
//       · 新配置无效（写一半/语法错）→ 仅告警，继续用旧配置运行
//       · kill -HUP 也可手动触发一次立即重载
//
// ★★ v4.7.1 启动健壮性（Windows 双击运行不再"闪退"）：
//   - 所有日志同步写入 exe/config 同目录 server.log（控制台闪退后仍可查）；
//   - config.json 缺失 → 自动生成模板配置并继续运行（不再秒退）；
//   - 任何启动失败（配置无效/端口被占/存储初始化失败）→ 原因落盘 +
//     Windows 交互式控制台暂停等回车（看得见原因再退出）。
package main

import (
        "context"
        "errors"
        "flag"
        "fmt"
        "io"
        "io/fs"
        "log"
        "net"
        "net/http"
        "os"
        "os/signal"
        "path/filepath"
        "syscall"
        "time"
)

func main() {
        configPath := flag.String("config", "config.json", "配置文件路径")
        flag.Parse()

        // ★★ v4.7.1 启动日志落盘：exe/config 同目录 server.log —— 控制台窗口
        //   一闪而过（或远程断开）后，启动失败与运行期异常仍可事后查证。
        //   进程生命周期内只打开一次（热重载自动重启不重复打开）。
        if lf, err := os.OpenFile(serverLogPath(*configPath), os.O_CREATE|os.O_WRONLY|os.O_APPEND, 0o644); err == nil {
                log.SetOutput(io.MultiWriter(os.Stderr, lf))
        }

        // 服务生命周期循环：runServer 返回 true = 配置发生结构性变更，
        // 需要用新配置自动重启（重新走一遍完整启动流程）；false = 正常退出。
        for {
                restart := runServer(*configPath)
                if !restart {
                        break
                }
                log.Println("=== 配置结构性变更已应用，服务器自动重启完成 ===")
                // 给操作系统一点端口释放缓冲（SO_REUSEADDR 之外的正常等待）
                time.Sleep(300 * time.Millisecond)
        }
}

// runServer 按当前 config.json 启动整套服务并阻塞运行，
// 直到收到退出信号（Ctrl+C / SIGTERM）或热重载要求重启。
// 返回 true = 需要自动重启；false = 正常退出。
func runServer(configPath string) bool {
        cfg, err := loadConfig(configPath)
        if err != nil {
                // ★★ v4.7.1 缺配置 → 自动生成模板并继续运行（首次运行引导）：
                //   双击 exe 秒退的"闪退"大多是 cwd 没有 config.json。
                if errors.Is(err, fs.ErrNotExist) {
                        if werr := writeTemplateConfig(configPath); werr != nil {
                                startupFatal("未找到配置文件 %s，且自动生成模板失败：%v", configPath, werr)
                        }
                        log.Printf("[配置] 未找到 %s —— 已自动生成模板配置（示例游戏占位）", configPath)
                        log.Printf("[配置] 请把它改成你的游戏列表与密钥后重启；改完会热重载，无需停服")
                        cfg, err = loadConfig(configPath)
                }
                if err != nil {
                        startupFatal("加载配置失败: %v", err)
                }
        }

        store, err := newUserStore(cfg.DataDir)
        if err != nil {
                startupFatal("初始化用户存储失败: %v", err)
        }

        jwter := newJWT(cfg.JWTSecret, 7*24*time.Hour)

        // ★ 热重载核心：所有组件经 configStore 读"当前配置"，
        //   文件变动时整指针原子替换，正在处理的请求不受影响。
        cs := newConfigStore(cfg)
        hub := newRelayHub(cs, jwter, store)

        api := &apiServer{
                cfg:      cs,
                users:    store,
                hub:      hub,
                jwt:      jwter,
                romCache: newRomCache(cfg.RomCacheMax),
        }

        // TCP 中继服务器（帧同步）
        tcpLn, err := net.Listen("tcp", cfg.AddressTCP())
        if err != nil {
                startupFatal("监听 TCP %s 失败: %v（端口被占用？旧服务器还在跑？）", cfg.AddressTCP(), err)
        }
        go func() {
                log.Printf("帧同步中继监听: tcp://%s", cfg.AddressTCP())
                for {
                        conn, err := tcpLn.Accept()
                        if err != nil {
                                // 监听器已关闭（优雅退出）——直接退出，避免死循环刷日志
                                if errors.Is(err, net.ErrClosed) {
                                        return
                                }
                                log.Printf("TCP accept 错误: %v", err)
                                time.Sleep(100 * time.Millisecond)
                                continue
                        }
                        go hub.handleConn(conn)
                }
        }()

        // ★★ v4.5 空房回收：创建了但从未有人连上的房间会永久占住桌号，
        //   周期清理（详见 relay.go reapEmptyRooms）。
        go hub.reapEmptyRooms()

        // HTTP API 服务器
        srv := &http.Server{
                Addr:              cfg.AddressHTTP(),
                Handler:           api.routes(),
                ReadHeaderTimeout: 10 * time.Second,
                WriteTimeout:      30 * time.Minute, // ROM 大文件下载需要足够时间
        }
        go func() {
                log.Printf("HTTP API 监听: http://%s", cfg.AddressHTTP())
                if err := srv.ListenAndServe(); err != nil && err != http.ErrServerClosed {
                        startupFatal("HTTP 服务器错误: %v（端口被占用？旧服务器还在跑？）", err)
                }
        }()

        // ===== 配置热重载与信号 =====
        reloadCh := make(chan *Config, 1)
        watcher := newConfigWatcher(configPath, reloadCh)
        go watcher.run()

        stop := make(chan os.Signal, 1)
        signal.Notify(stop, os.Interrupt, syscall.SIGTERM)
        hup := make(chan os.Signal, 1)
        signal.Notify(hup, syscall.SIGHUP)

        log.Printf("已启动：%d 个游戏 · 中继 %s", len(cfg.Games), cfg.PublicTCP())
        log.Printf("配置热重载已启用：监视 %s（每秒检测）—— "+
                "games/inputDelay/maxRooms/romCacheMax/对外地址 变动就地生效；"+
                "httpAddr/tcpAddr/jwtSecret/dataDir 变动自动重启；SIGHUP 立即重载", configPath)

        for {
                select {
                case <-stop:
                        log.Println("正在关闭服务器...")
                        watcher.stop()
                        signal.Stop(stop)
                        signal.Stop(hup)
                        shutdownServer(srv, tcpLn, store)
                        log.Println("已退出")
                        return false

                case <-hup:
                        log.Println("收到 SIGHUP，立即检查配置变动…")
                        watcher.reloadNow()

                case newCfg := <-reloadCh:
                        old := cs.get()

                        // 结构性变更（监听地址 / 签名密钥 / 数据目录）→ 自动优雅重启
                        if needsRestart(old, newCfg) {
                                hub.mu.Lock()
                                roomCount := len(hub.rooms)
                                hub.mu.Unlock()
                                log.Printf("[热重载] 变更涉及 httpAddr/tcpAddr/jwtSecret/dataDir"+
                                        "（当前 %d 个房间会被断开）—— 自动重启服务器…", roomCount)
                                watcher.stop()
                                signal.Stop(stop)
                                signal.Stop(hup)
                                shutdownServer(srv, tcpLn, store)
                                return true
                        }

                        // 常规变更（games 等）→ 就地热替换，零打断
                        cs.store(newCfg)
                        api.romCache.setMaxBytes(newCfg.RomCacheMax)
                        log.Printf("[热重载] 已生效：%s", describeChange(old, newCfg))
                }
        }
}

// shutdownServer 优雅关闭三件套（HTTP API / TCP 中继 / 用户存储）
func shutdownServer(srv *http.Server, tcpLn net.Listener, store *userStore) {
        ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
        defer cancel()
        _ = srv.Shutdown(ctx)
        _ = tcpLn.Close()
        _ = store.Close()
}

// serverLogPath 启动/运行日志落盘路径：config.json 同目录 server.log
//（双击 exe 时通常就是 exe 所在目录，最好找）。
func serverLogPath(configPath string) string {
        return filepath.Join(filepath.Dir(configPath), "server.log")
}

// startupFatal 启动失败收尾：原因写日志（含 server.log 落盘）+ 控制台加粗
// 提示 + Windows 交互式控制台暂停等回车 —— 取代 log.Fatalf 的"秒退闪退"
// （双击运行的窗口直接关闭，用户完全看不到失败原因）。
func startupFatal(format string, args ...any) {
        msg := fmt.Sprintf(format, args...)
        log.Printf("[启动失败] %s", msg)
        fmt.Fprintf(os.Stderr, "\n*** 启动失败：%s ***\n", msg)
        pauseIfInteractive()
        os.Exit(1)
}
