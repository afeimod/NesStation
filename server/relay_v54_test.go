package main

// ★★ v54 回归测试：黑屏极速追赶配套的服务器行为。
//
// 用户 v53 实测反馈：
//   "1-加入对战的一人或者多人应该黑屏快速直接追赶而不是启动游戏慢慢追赶，
//    追赶成功在同步画面"—— 根因一（客户端）：v52/v53 的"每可见帧快放 15 帧"
//    受引擎 60fps 渲染节奏限制（净速 ~15 倍）+ 屏幕上是从旧档帧开始的可见
//    快进 —— 观感"启动游戏慢慢追赶"（v54 客户端改为紧循环离屏快放 + 黑屏
//    进度浮层，见 NetplayController.kt）；根因二（服务器）：v53 直接把
//    【旧档】发给接入者追赶（档龄 × 60 帧的追赶距离）。v54 服务器：缓存档
//    距现在 > catchupStalePull(4s) 且房主能秒级响应拉档 → 优先静默拉【新】档。
//
// 本文件锁定 v54 服务端行为：
//   1) 旧档（> 4s）+ 房主能响应 → 拉新档（TestV53StaleCacheStillCatches 已更新）；
//   2) 拉档无响应（房主老客户端/出档失败）+ 服务器有旧档 → 看门狗降级发
//      旧档追赶包（不再直接劝退 —— "拉档失败仍能用旧档追赶"的保证）；
//   3) 降级追赶包 → 接入者 state_ok → 座位激活（房间全程 playing、零 pause）；
//   4) 降级后房主迟到快照不二次发追赶包（名单已移除）；
//   5) 已发追赶包但接入者迟迟不 state_ok → 看门狗续期（catchupMaxExtends 轮）
//      而不是一超时就劝退。
// 运行：go test -run TestV54 -v .

import (
        "testing"
        "time"
)

// TestV54WatchdogFallsBackToStaleCache ★ 核心：拉档无响应（房主没回
// state_upload）+ 服务器有可用旧档（历史完整）→ 追赶看门狗到期后降级
// 发【旧档追赶包】（v54 客户端黑屏极速追赶数秒追平），接入者不会被劝退，
// 原玩家全程无感（零 pause / 零 restart）。
func TestV54WatchdogFallsBackToStaleCache(t *testing.T) {
        env := newEnv(t, 0)
        env.hub.catchupWatchdogMs = 250 // 追赶看门狗 250ms（测试注入）
        tokH, _ := env.register(t, "host")
        tok2, _ := env.register(t, "p2")
        addr := env.tcp.Addr().String()
        roomID := env.createRoom(t, tokH)

        host := startHost53(t, addr, tokH, roomID)
        // 房主跑 30 帧 + 上传一份档（帧 20，历史完整覆盖 [4,20) 热身窗）
        for f := int64(0); f < 30; f++ {
                host.send(inputMsg(f, 0x11, 1))
        }
        host.send(map[string]any{"type": "state_upload", "frame": 20, "data": "QUJD", "histEnd": 20})
        time.Sleep(120 * time.Millisecond)

        // 白盒：把缓存时间拨回 60s 前（旧档 → v54 优先拉新档，才能走到
        // "拉档无响应 → 看门狗降级"的被测路径）
        env.hub.mu.Lock()
        for _, r := range env.hub.rooms {
                if r.ID == roomID {
                        r.lastStateMu.Lock()
                        r.lastStateAt = time.Now().Add(-60 * time.Second)
                        r.lastStateMu.Unlock()
                }
        }
        env.hub.mu.Unlock()

        p2 := dialRoomV(t, addr, tok2, roomID, false, 2, 53)
        p2.name = "p2"
        p2.wait("hello", func(m relayMsg) bool { return m.Type == "hello" })
        p2.send(map[string]any{"type": "ready", "pad": 2, "stateCap": 1, "catchup": true})

        // 1) 旧档 → v54 优先拉新档（state_pull 只发房主）
        host.wait("state_pull", func(m relayMsg) bool { return m.Type == "state_pull" })

        // 2) 房主【不】响应拉档（模拟出档失败/老客户端）→ 看门狗到期降级：
        //    接入者收到旧档追赶包（catchup=1 frame=20），而不是 error 劝退。
        st := p2.wait("fallback catchup state", func(m relayMsg) bool {
                return m.Type == "state" || m.Type == "error"
        })
        if st.Type == "error" {
                t.Fatalf("拉档无响应时不应劝退（v54 看门狗降级旧档追赶）：error=%s", st.Msg)
        }
        if !st.Catchup || st.Frame != 20 {
                t.Fatalf("state = %+v, want catchup=1 frame=20（降级旧档追赶包）", st)
        }
        // 历史完整（[4, 30) 热身窗+重放窗）
        if len(st.Hist[1]) != 30-4 {
                t.Fatalf("hist[1] len=%d, want %d（[4,30) 完整历史）", len(st.Hist[1]), 30-4)
        }

        // 3) 全员零 pause / 零 restart（原玩家完全无感）
        host.expectNone("no pause", func(m relayMsg) bool { return m.Type == "pause" })
        host.expectNone("no restart", func(m relayMsg) bool { return m.Type == "restart" })

        // 4) 接入者追平（state_ok）→ 广播座位激活（房间健康）
        p2.send(map[string]any{"type": "state_ok"})
        sa := host.wait("slot_active", func(m relayMsg) bool { return m.Type == "slot_active" })
        if sa.Slot != 2 {
                t.Fatalf("slot_active.slot=%d, want 2", sa.Slot)
        }

        // 5) 房主迟到的快照到达：不二次发追赶包（降级时已从 pendingCatchup 移除）
        host.send(map[string]any{"type": "state_upload", "frame": 40, "data": "QUJD", "histEnd": 40})
        p2.expectNone("no double catchup state", func(m relayMsg) bool {
                return m.Type == "state" && m.Catchup
        })
        // 房间仍健康：输入照常中继
        host.send(inputMsg(50, 0x33, 1))
        ri := p2.wait("input relay", func(m relayMsg) bool {
                return m.Type == "input" && m.Slot == 1 && m.Frame == 50
        })
        if ri.Pad != 0x33 {
                t.Fatalf("relay pad=%x, want 0x33", ri.Pad)
        }
}

// TestV54CatchupWatchdogExtends 已发追赶包但接入者迟迟不 state_ok
// （超大差距/低端机追赶中）→ 看门狗续期（catchupMaxExtends 轮），
// 不会在第一个窗口就把还在追赶中的接入者劝退。
func TestV54CatchupWatchdogExtends(t *testing.T) {
        env := newEnv(t, 0)
        env.hub.catchupWatchdogMs = 200 // 每轮 200ms；续期 catchupMaxExtends=3 轮 → 至少 ~800ms
        tokH, _ := env.register(t, "host")
        tok2, _ := env.register(t, "p2")
        addr := env.tcp.Addr().String()
        roomID := env.createRoom(t, tokH)

        host := startHost53(t, addr, tokH, roomID)
        for f := int64(0); f < 30; f++ {
                host.send(inputMsg(f, 0x11, 1))
        }
        // 新档（新鲜）+ 历史完整 → 直接追赶（catchup 路径，无需拉档）
        host.send(map[string]any{"type": "state_upload", "frame": 20, "data": "QUJD", "histEnd": 20})
        time.Sleep(120 * time.Millisecond)

        p2 := dialRoomV(t, addr, tok2, roomID, false, 2, 53)
        p2.name = "p2"
        p2.wait("hello", func(m relayMsg) bool { return m.Type == "hello" })
        p2.send(map[string]any{"type": "ready", "pad": 2, "stateCap": 1, "catchup": true})

        // 接入者收到追赶包（旧档新鲜 → 直接追赶）
        st := p2.wait("catchup state", func(m relayMsg) bool { return m.Type == "state" && m.Catchup })
        if st.Frame != 20 {
                t.Fatalf("state.frame=%d, want 20", st.Frame)
        }

        // 接入者【不】发 state_ok（模拟仍在黑屏追赶中）：
        // 第一个看门狗窗口（200ms）内绝不能收到 error（劝退）——
        // v53 行为是一超时就劝退，v54 必须续期等待。
        p2.expectNone("no early kick", func(m relayMsg) bool { return m.Type == "error" })

        // 看门狗续期满 catchupMaxExtends 轮后最终劝退（~800ms）；
        // 用宽松上界（2.5s）等待 error，验证"续期而非立即劝退"。
        deadline := time.Now().Add(2500 * time.Millisecond)
        kicked := false
        for !kicked && time.Now().Before(deadline) {
                select {
                case m := <-p2.msgs:
                        if m.Type == "error" {
                                kicked = true
                        }
                case <-p2.closed:
                        t.Fatal("接入者在续期窗口内被断开（应只收 error 后由服务器断开）")
                case <-time.After(50 * time.Millisecond):
                }
        }
        if !kicked {
                t.Fatal("续期满 catchupMaxExtends 轮后应最终劝退（未收到 error）")
        }
        // 房间健康：房主照常
        host.expectNone("no pause", func(m relayMsg) bool { return m.Type == "pause" })
        host.expectNone("no restart", func(m relayMsg) bool { return m.Type == "restart" })
}

// TestV54FreshCacheStillDirectCatch 新鲜档（≤ catchupStalePull 4s）+ 历史完整
// → 直接追赶（不拉档 —— 新鲜档追赶距离本就小，拉档纯属浪费一次往返）。
func TestV54FreshCacheStillDirectCatch(t *testing.T) {
        env := newEnv(t, 0)
        tokH, _ := env.register(t, "host")
        tok2, _ := env.register(t, "p2")
        addr := env.tcp.Addr().String()
        roomID := env.createRoom(t, tokH)

        host := startHost53(t, addr, tokH, roomID)
        for f := int64(0); f < 30; f++ {
                host.send(inputMsg(f, 0x11, 1))
        }
        host.send(map[string]any{"type": "state_upload", "frame": 25, "data": "QUJD", "histEnd": 25})
        time.Sleep(80 * time.Millisecond) // 档龄 < 4s（新鲜）

        p2 := dialRoomV(t, addr, tok2, roomID, false, 2, 53)
        p2.name = "p2"
        p2.wait("hello", func(m relayMsg) bool { return m.Type == "hello" })
        p2.send(map[string]any{"type": "ready", "pad": 2, "stateCap": 1, "catchup": true})

        // 直接收追赶包（新鲜档不拉档）
        st := p2.wait("catchup state", func(m relayMsg) bool { return m.Type == "state" })
        if !st.Catchup || st.Frame != 25 {
                t.Fatalf("state = %+v, want catchup=1 frame=25（新鲜档直接追赶）", st)
        }
        host.expectNone("no state_pull", func(m relayMsg) bool { return m.Type == "state_pull" })
        host.expectNone("no pause", func(m relayMsg) bool { return m.Type == "pause" })
}
