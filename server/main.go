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
package main

import (
        "context"
        "errors"
        "flag"
        "log"
        "net"
        "net/http"
        "os"
        "os/signal"
        "syscall"
        "time"
)

func main() {
        configPath := flag.String("config", "config.json", "配置文件路径")
        flag.Parse()

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
                log.Fatalf("加载配置失败: %v", err)
        }

        store, err := newUserStore(cfg.DataDir)
        if err != nil {
                log.Fatalf("初始化用户存储失败: %v", err)
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
                log.Fatalf("监听 TCP %s 失败: %v", cfg.AddressTCP(), err)
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
                        log.Fatalf("HTTP 服务器错误: %v", err)
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
