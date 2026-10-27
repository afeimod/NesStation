package main

// v48 服务器修复回归测试：
// · TestV48PeerLeftCarriesLastFrame —— peer_left 消息携带离队槽位最后一次
//   中继的输入帧号（frame 字段）。客户端据此做“离队槽位确定性归零”，
//   根治 3/4 人房“一个人不同步”（各端对离队玩家在途输入的取舍不一致）。
// · TestV48SlowClientIsolation —— 一个“完全不读”的观战客户端（接收缓冲
//   塞满 → 服务器写它阻塞）不能拖住服务器向正常玩家转发输入
//   （旧版同步串行 send 的队头阻塞：慢客户端卡住中继 goroutine →
//   同一循环里排在后面的其他玩家输入全部延迟 → 全员卡死；
//   2 人房 targets 只有 1 个，从不排队 → 双人没问题、3/4 人必现）。

import (
        "encoding/json"
        "fmt"
        "net"
        "testing"
        "time"
)

func TestV48PeerLeftCarriesLastFrame(t *testing.T) {
        env := newEnv(t, 0)
        tok1, _ := env.register(t, "v48-host")
        tok2, _ := env.register(t, "v48-guest")
        roomID := env.createRoom(t, tok1)
        addr := env.tcp.Addr().String()

        host := dialRoom(t, addr, tok1, roomID, false, 0)
        host.name = "v48-host"
        if m := host.wait("host hello", func(m relayMsg) bool { return m.Type == "hello" }); m.Slot != 1 {
                t.Fatalf("host slot = %d", m.Slot)
        }
        p2 := dialRoom(t, addr, tok2, roomID, false, 2)
        p2.name = "v48-p2"
        p2.wait("p2 hello", func(m relayMsg) bool { return m.Type == "hello" })

        // 全员就绪开局（2 人房：房主 + 2P）
        host.send(map[string]any{"type": "ready", "pad": 2, "stateCap": -1})
        p2.send(map[string]any{"type": "ready", "pad": 2, "stateCap": -1})
        host.wait("start", func(m relayMsg) bool { return m.Type == "start" })
        p2.wait("start", func(m relayMsg) bool { return m.Type == "start" })

        // p2 发了 0..29 帧（槽 2），服务器应已全部中继给 host
        for f := 0; f < 30; f++ {
                p2.send(inputMsg(int64(f), 0x11, 2))
        }
        host.wait("input f29", func(m relayMsg) bool { return m.Type == "input" && m.Frame == 29 && m.Slot == 2 })

        // p2 掉线 → host 收到的 peer_left 必须带 frame=29
        _ = p2.conn.Close()
        lm := host.wait("peer_left with lastFrame", func(m relayMsg) bool {
                return m.Type == "peer_left" && m.Slot == 2
        })
        if lm.Frame != 29 {
                t.Fatalf("peer_left frame = %d, want 29（离队槽位最后中继帧）", lm.Frame)
        }
}

func TestV48SlowClientIsolation(t *testing.T) {
        env := newEnv(t, 0)
        tok1, _ := env.register(t, "iso-host")
        tok2, _ := env.register(t, "iso-p2")
        tok3, _ := env.register(t, "iso-slow")
        roomID := env.createRoom(t, tok1)
        addr := env.tcp.Addr().String()

        host := dialRoom(t, addr, tok1, roomID, false, 0)
        host.name = "iso-host"
        host.wait("host hello", func(m relayMsg) bool { return m.Type == "hello" })
        p2 := dialRoom(t, addr, tok2, roomID, false, 2)
        p2.name = "iso-p2"
        p2.wait("p2 hello", func(m relayMsg) bool { return m.Type == "hello" })

        // 开局
        host.send(map[string]any{"type": "ready", "pad": 2, "stateCap": -1})
        p2.send(map[string]any{"type": "ready", "pad": 2, "stateCap": -1})
        host.wait("start", func(m relayMsg) bool { return m.Type == "start" })
        p2.wait("start", func(m relayMsg) bool { return m.Type == "start" })

        // slow：观战加入后【完全不读】socket 的客户端（接收缓冲塞满 → 服务器写它阻塞）
        slowConn, err := net.DialTimeout("tcp", addr, 3*time.Second)
        if err != nil {
                t.Fatalf("dial slow: %v", err)
        }
        hello := map[string]any{"type": "hello", "token": tok3, "roomId": roomID, "v": 47, "spectate": true}
        b, _ := json.Marshal(hello)
        fmt.Fprintf(slowConn, "%s\n", b)
        time.Sleep(150 * time.Millisecond)

        // 排水协程：持续消费 p2.msgs（★必须在洪泛之前启动 —— 否则 256 容量的
        // msgs 通道塞满 → 测试客户端读协程停摆 → p2 被服务器按慢客户端断开，
        // 测试失效）。记录来自 host 的最大输入帧号。
        done := make(chan int64, 1)
        go func() {
                got := int64(-1)
                deadline := time.After(8 * time.Second)
                for got < 2999 {
                        select {
                        case m := <-p2.msgs:
                                if m.Type == "input" && m.Slot == 1 && m.Frame > got {
                                        got = m.Frame
                                }
                        case <-p2.closed:
                                done <- -1
                                return
                        case <-deadline:
                                done <- got
                                return
                        }
                }
                done <- got
        }()

        // 灌入大量输入：观战者不读 → 它的 outbox 持续增长（直至被断开），
        // 但 host → p2 的转发必须不受影响（旧版卡在同一串行循环 → 全员卡死）。
        // ★ 每 10 帧小睡 2ms 模拟真实 60fps 节奏（也让 p2 的测试读协程跟上）。
        for f := 0; f < 3000; f++ {
                host.send(inputMsg(int64(f), 0x0F, 1))
                p2.send(inputMsg(int64(f), 0x33, 2))
                if f%10 == 9 {
                        time.Sleep(2 * time.Millisecond)
                }
        }

        // p2 必须在合理时间内收到第 3000 帧的输入（异步队列正常转发）
        select {
        case got := <-done:
                if got == -1 {
                        t.Fatalf("p2 连接被断开（慢观战者拖累了正常玩家？）")
                }
                if got < 2999 {
                        t.Fatalf("输入转发被阻塞：最后收到 host 帧 %d（应达 2999）— 慢客户端隔离失效", got)
                }
        case <-time.After(9 * time.Second):
                t.Fatalf("等待超时")
        }
}
