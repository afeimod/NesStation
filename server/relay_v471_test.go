package main

// v4.7.1 服务器健壮性回归测试：
// · TestV471ChaosSurvival —— 混沌轰击（v4.7 客户端真实流量模式：非街机核心
//   stateCap=-1 → restart 全员重开流程 + 随机并发加入/掉线/ready 风暴/
//   restart_req 风暴/坏 JSON/半行断开/假存档/512KB 大消息）。任何 goroutine
//   panic 都会杀死测试进程 —— 测试通过 = 服务器在极限混乱下存活
//   （panic 装甲 + 全部锁纪律的回归保障）。
// · TestV471TemplateConfigBootstrap —— 首次运行（缺 config.json）自动生成
//   模板配置且模板可直接解析运行（Windows 双击不再闪退的引导链路）。
// · TestV471NoDoubleStart —— 对局中掉线不得触发二次 start（竞态护栏回归）。

import (
        "fmt"
        "math/rand"
        "net"
        "net/http"
        "os"
        "path/filepath"
        "strings"
        "sync"
        "testing"
        "time"
)

// chaosStep 随机执行一种客户端行为
func chaosStep(t *testing.T, tokens []string, roomID, addr string, rnd *rand.Rand) {
        tok := tokens[rnd.Intn(len(tokens))]
        switch rnd.Intn(12) {
        case 0, 1: // 正常加入（v47 hello）
                c := dialRoomV47(t, addr, tok, roomID, false, 0)
                time.Sleep(time.Duration(rnd.Intn(30)) * time.Millisecond)
                c.send(map[string]any{"type": "ready", "pad": 2, "stateCap": -1})
                time.Sleep(time.Duration(rnd.Intn(50)) * time.Millisecond)
                _ = c.conn.Close()
        case 2: // 观战加入后立刻退出
                c := dialRoomV47(t, addr, tok, roomID, true, 0)
                time.Sleep(time.Duration(rnd.Intn(20)) * time.Millisecond)
                _ = c.conn.Close()
        case 3: // 坏 JSON 行
                c := dialRoomV47(t, addr, tok, roomID, false, 0)
                fmt.Fprintf(c.conn, "this is not json at all\n")
                fmt.Fprintf(c.conn, "{\"type\":\"input\",\"frame\":\"不是数字\"}\n")
                fmt.Fprintf(c.conn, "{\"type\":12345}\n")
                fmt.Fprintf(c.conn, "\n\n\n")
                _ = c.conn.Close()
        case 4: // ready 风暴 + restart_req 风暴
                c := dialRoomV47(t, addr, tok, roomID, false, 0)
                for i := 0; i < 10; i++ {
                        c.send(map[string]any{"type": "ready", "pad": i % 5, "stateCap": -1})
                        c.send(map[string]any{"type": "restart_req"})
                }
                _ = c.conn.Close()
        case 5: // 发完 hello 不等回执立刻断
                c := dialRoomV47(t, addr, tok, roomID, false, rnd.Intn(5))
                _ = c.conn.Close()
        case 6: // 假存档/伪造消息（非房主 state / state_upload / state_fail）
                c := dialRoomV47(t, addr, tok, roomID, false, 0)
                c.send(map[string]any{"type": "state", "frame": 9999, "data": "ZmFrZQ==", "hist": map[string]any{"1": []int{1, 2}}})
                c.send(map[string]any{"type": "state_upload", "frame": 1, "data": "ZmFrZQ=="})
                c.send(map[string]any{"type": "state_fail"})
                c.send(map[string]any{"type": "state_ok"})
                c.send(map[string]any{"type": "unknown_type", "frame": -5, "pad": -99})
                _ = c.conn.Close()
        case 7: // 大消息（512KB base64 假档）
                c := dialRoomV47(t, addr, tok, roomID, false, 0)
                big := strings.Repeat("A", 512*1024)
                c.send(map[string]any{"type": "state", "frame": 100, "data": big})
                c.send(map[string]any{"type": "input", "frame": 1, "pad": 0xFF, "slot": 9})
                time.Sleep(time.Duration(rnd.Intn(20)) * time.Millisecond)
                _ = c.conn.Close()
        case 8: // 半行断开（截断 JSON）
                conn := dialRaw(t, addr, tok, roomID)
                if conn != nil {
                        fmt.Fprintf(conn, "{\"type\":\"ready\",\"pad\":2,\"stateCap\"")
                        _ = conn.Close()
                }
        case 9: // ping/bye 快闪
                c := dialRoomV47(t, addr, tok, roomID, false, 0)
                c.send(map[string]any{"type": "ping", "ts": 1})
                c.send(map[string]any{"type": "bye"})
                c.send(map[string]any{"type": "ping", "ts": 2}) // bye 后继续发
                _ = c.conn.Close()
        case 10: // 开局流程后 input 轰炸 + 中途断
                c := dialRoomV47(t, addr, tok, roomID, false, 0)
                c.send(map[string]any{"type": "ready", "pad": 2, "stateCap": -1})
                inner := rand.New(rand.NewSource(time.Now().UnixNano())) // 独立随机源（防与本层竞态）
                go func() {
                        for i := 0; i < 200; i++ {
                                c.send(map[string]any{"type": "input", "frame": int64(i), "pad": inner.Intn(16), "slot": 1 + inner.Intn(4)})
                                if i%50 == 0 {
                                        time.Sleep(time.Millisecond)
                                }
                        }
                        _ = c.conn.Close()
                }()
        default: // 睡眠（模拟真实节奏空隙）
                time.Sleep(time.Duration(rnd.Intn(15)) * time.Millisecond)
        }
}

func TestV471ChaosHuntCrash(t *testing.T) {
        env := newEnv(t, 0)
        addr := env.tcp.Addr().String()

        // 注册 6 个用户
        var tokens []string
        for i := 0; i < 6; i++ {
                tok, _ := env.register(t, fmt.Sprintf("chaos%d", i))
                tokens = append(tokens, tok)
        }

        // 4 个房间（不同桌），全部施加混沌
        roomIDs := make([]string, 4)
        for i := 0; i < 4; i++ {
                roomIDs[i] = env.createRoom(t, tokens[0])
        }

        // 主场景：每房间先跑一轮完整 restart 重开流程（v4.7 非街机核心语义）
        for _, roomID := range roomIDs {
                a := dialRoomV47(t, addr, tokens[0], roomID, false, 0)
                a.wait("hello", func(m relayMsg) bool { return m.Type == "hello" })
                a.send(map[string]any{"type": "ready", "pad": 2, "stateCap": -1})
                a.wait("start", func(m relayMsg) bool { return m.Type == "start" }) // 房主单开
                go func(c *testClient) {
                        time.Sleep(300 * time.Millisecond)
                        _ = c.conn.Close()
                }(a)
        }

        // 混沌：8 goroutine × 30 步随机行为
        var wg sync.WaitGroup
        for g := 0; g < 8; g++ {
                wg.Add(1)
                go func(seed int64) {
                        defer wg.Done()
                        rnd := rand.New(rand.NewSource(seed))
                        for step := 0; step < 30; step++ {
                                roomID := roomIDs[rnd.Intn(len(roomIDs))]
                                chaosStep(t, tokens, roomID, addr, rnd)
                        }
                }(time.Now().UnixNano() + int64(g))
        }
        wg.Wait()

        // 服务器必须还活着：新建房间 + 完整跑一局 restart 流程
        roomID := env.createRoom(t, tokens[1])
        h := dialRoomV47(t, addr, tokens[1], roomID, false, 0)
        h.name = "finalHost"
        h.wait("hello", func(m relayMsg) bool { return m.Type == "hello" })
        h.send(map[string]any{"type": "ready", "pad": 2, "stateCap": -1})
        h.wait("start", func(m relayMsg) bool { return m.Type == "start" })

        j := dialRoomV47(t, addr, tokens[2], roomID, false, 0)
        j.name = "finalJoiner"
        j.wait("hello", func(m relayMsg) bool { return m.Type == "hello" })
        j.send(map[string]any{"type": "ready", "pad": 2, "stateCap": -1})
        // 非街机核心：无缓存无快照 → 全员重开
        h.wait("restart", func(m relayMsg) bool { return m.Type == "restart" })
        j.wait("restart", func(m relayMsg) bool { return m.Type == "restart" })
        h.send(map[string]any{"type": "ready", "pad": 2, "stateCap": -1})
        j.send(map[string]any{"type": "ready", "pad": 2, "stateCap": -1})
        h.wait("start", func(m relayMsg) bool { return m.Type == "start" })
        j.wait("start", func(m relayMsg) bool { return m.Type == "start" })

        // HTTP API 也必须活着
        resp, err := http.Get(env.srv.URL + "/api/games")
        if err != nil || resp.StatusCode != 200 {
                t.Fatalf("混沌后 HTTP API 无响应: %v %v", err, resp)
        }
        resp.Body.Close()
}

// TestV471TemplateConfigBootstrap 首次运行引导：exe 目录没有 config.json 时
// 自动生成模板 —— 模板必须可直接解析并通过校验（否则首跑照样闪退）。
func TestV471TemplateConfigBootstrap(t *testing.T) {
        dir := t.TempDir()
        path := filepath.Join(dir, "config.json")

        // 确认未生成前 loadConfig 报"文件不存在"（v4.7.1 据此引导生成）
        if _, err := loadConfig(path); err == nil {
                t.Fatal("空目录应报配置不存在")
        }

        // 生成模板 → 必须直接可运行
        if err := writeTemplateConfig(path); err != nil {
                t.Fatalf("生成模板失败: %v", err)
        }
        cfg, err := loadConfig(path)
        if err != nil {
                t.Fatalf("模板配置必须可直接解析运行（否则首跑仍闪退）: %v", err)
        }
        if len(cfg.Games) == 0 || cfg.Games[0].ID != "demo" {
                t.Fatalf("模板游戏条目异常: %+v", cfg.Games)
        }
        if cfg.Games[0].MaxPlayers != 4 {
                t.Fatalf("模板 maxPlayers 规范化异常: %d", cfg.Games[0].MaxPlayers)
        }
        if cfg.JWTSecret == "" {
                t.Fatal("模板必须带 jwtSecret（校验要求非空）")
        }
        // 落盘内容与模板一致（下次重启读到同一份，不再重复生成）
        data, _ := os.ReadFile(path)
        if string(data) != templateConfig {
                t.Fatal("落盘内容与模板不一致")
        }
}

// TestV471NoDoubleStart 对局中掉线不得触发二次 start：A(owner)+B 双 ready
// 正常开局 → B 掉线 → A 只应收到 peer_left，不应再收到 start
//（v4.7.1 竞态护栏：drop 开局路径只在 waiting 阶段广播）。
func TestV471NoDoubleStart(t *testing.T) {
        env := newEnv(t, 0)
        addr := env.tcp.Addr().String()
        tokA, _ := env.register(t, "dsA")
        tokB, _ := env.register(t, "dsB")
        roomID := env.createRoom(t, tokA)

        a := dialRoomV47(t, addr, tokA, roomID, false, 0)
        a.name = "A"
        a.wait("hello", func(m relayMsg) bool { return m.Type == "hello" })
        b := dialRoomV47(t, addr, tokB, roomID, false, 0)
        b.name = "B"
        b.wait("hello", func(m relayMsg) bool { return m.Type == "hello" })

        a.send(map[string]any{"type": "ready", "pad": 2, "stateCap": -1})
        b.send(map[string]any{"type": "ready", "pad": 2, "stateCap": -1})
        a.wait("start", func(m relayMsg) bool { return m.Type == "start" })
        b.wait("start", func(m relayMsg) bool { return m.Type == "start" })

        // B 掉线：playing 阶段 → 只应有 peer_left，不得再广播 start
        _ = b.conn.Close()
        a.wait("peer_left", func(m relayMsg) bool { return m.Type == "peer_left" })
        a.expectNone("二次 start", func(m relayMsg) bool { return m.Type == "start" })
}

// dialRaw 只发 hello 行（用于半行断开等原始 socket 场景）
func dialRaw(t *testing.T, addr, token, roomID string) net.Conn {
        conn, err := net.DialTimeout("tcp", addr, 3*time.Second)
        if err != nil {
                return nil
        }
        fmt.Fprintf(conn, "{\"type\":\"hello\",\"token\":\"%s\",\"roomId\":\"%s\",\"v\":47}\n", token, roomID)
        return conn
}
