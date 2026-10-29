package main

// ★★ v55 回归测试：立即追赶（用户 v54 实测反馈的根治）。
//
// 用户 v54 实测反馈：
//   "1-加入对战时这个等待同步开始太久了，应该直接进行同步追赶啊，
//    并快进游戏进行同步，否则追赶很慢"
//   —— 根因：v54 服务器"旧档(>4s)优先拉新档"让接入者在启动门上等
//      拉档往返（state_pull → 房主出档 → 上传 → 组装追赶包）；房主出档
//      被周期上传占用（uploadInFlight 跳过）/ 低端机出档慢时，接入者
//      从数秒一直等到 45s 看门狗兑底才降级旧档追赶 —— 观感"等待同步
//      开始太久"（截图 FPS 0 + 等待提示常驻）。
//
// v55 语义（本文件锁定）：
//   1) 历史覆盖到的缓存档（无论多旧）→ 接入者 ready 后【立即】收追赶包
//      （TestV53StaleCacheStillCatches 已更新为 v55 语义锁定）；
//   2) 拉档仅保留给无档/历史缺口场景；拉档无响应（房主出不了档）→
//      看门狗到期劝退接入者（绝不无限等待），房间其他成员全程无感。
// 运行：go test -run TestV55 -v .

import (
        "testing"
        "time"
)

// TestV55JoinGetsCatchupImmediatelyWithinWatchdog 核心承诺：接入者 ready 后
// 追赶包【立即】到达 —— 在远小于看门狗（注入 800ms）的时间内收到追赶包
// （v54 翻车场景：等房主拉档响应直到看门狗兑底）。锁定"立即追赶"不回退。
func TestV55JoinGetsCatchupImmediatelyWithinWatchdog(t *testing.T) {
        env := newEnv(t, 0)
        env.hub.catchupWatchdogMs = 800 // 若走拉档且房主不响应，800ms 后才可能兑底
        tokH, _ := env.register(t, "host")
        tok2, _ := env.register(t, "p2")
        addr := env.tcp.Addr().String()
        roomID := env.createRoom(t, tokH)

        host := startHost53(t, addr, tokH, roomID)
        for f := int64(0); f < 30; f++ {
                host.send(inputMsg(f, 0x11, 1))
        }
        // 房主上传一份档（历史完整覆盖 → v55 直接追赶路径）
        host.send(map[string]any{"type": "state_upload", "frame": 25, "data": "QUJD", "histEnd": 25})
        time.Sleep(80 * time.Millisecond)

        p2 := dialRoomV(t, addr, tok2, roomID, false, 2, 53)
        p2.name = "p2"
        p2.wait("hello", func(m relayMsg) bool { return m.Type == "hello" })
        p2.send(map[string]any{"type": "ready", "pad": 2, "stateCap": 1, "catchup": true})

        // 立即追赶：500ms 内必须收到追赶包（远小于 800ms 看门狗 —— v54
        // 翻车时接入者要等看门狗兑底才有包）
        st := p2.waitDeadline("immediate catchup state", func(m relayMsg) bool {
                return m.Type == "state" && m.Catchup
        }, 500*time.Millisecond)
        if st.Frame != 25 {
                t.Fatalf("state.frame=%d, want 25（缓存档立即追赶）", st.Frame)
        }
        host.expectNone("no state_pull", func(m relayMsg) bool { return m.Type == "state_pull" })
        host.expectNone("no pause", func(m relayMsg) bool { return m.Type == "pause" })
}

// TestV55PullGapHostSilentReject 拉档仅剩场景：历史缺口（档太旧覆盖不到）
// + 房主对 state_pull 无响应（老客户端/出档失败/引擎暂停）→ 看门狗到期
// 劝退接入者（error + 断开），绝不无限等待；房主全程无感（零 pause/零 restart），
// 房间保持健康（后续接入者在房主恢复出档后仍可追赶）。
func TestV55PullGapHostSilentReject(t *testing.T) {
        env := newEnv(t, 0)
        env.hub.catchupWatchdogMs = 250 // 拉档看门狗 250ms（测试注入）
        tokH, _ := env.register(t, "host")
        tok2, _ := env.register(t, "p2")
        addr := env.tcp.Addr().String()
        roomID := env.createRoom(t, tokH)

        host := startHost53(t, addr, tokH, roomID)
        for f := int64(0); f < 100; f++ {
                host.send(inputMsg(f, 0x11, 1))
        }
        host.send(map[string]any{"type": "state_upload", "frame": 5, "data": "QUJD", "histEnd": 5})
        time.Sleep(120 * time.Millisecond)

        // 白盒：历史起点抬到 40（档帧 5-16 覆盖不到 → 拉档路径）
        env.hub.mu.Lock()
        for _, r := range env.hub.rooms {
                if r.ID == roomID {
                        r.inpHistStart = 40
                }
        }
        env.hub.mu.Unlock()

        p2 := dialRoomV(t, addr, tok2, roomID, false, 2, 53)
        p2.name = "p2"
        p2.wait("hello", func(m relayMsg) bool { return m.Type == "hello" })
        p2.send(map[string]any{"type": "ready", "pad": 2, "stateCap": 1, "catchup": true})

        // 1) 走拉档（state_pull 只发房主；全员零 pause）
        host.wait("state_pull", func(m relayMsg) bool { return m.Type == "state_pull" })
        host.expectNone("no pause", func(m relayMsg) bool { return m.Type == "pause" })

        // 2) 房主不响应（模拟出档失败/引擎暂停）→ 看门狗到期劝退接入者。
        //    劝退 = error 消息（可能先于连接关闭到达）或直接断开 ——
        //    两者都算劝退成功；期间绝不能收到追赶包（state.catchup）。
        rejected := false
        deadline := time.Now().Add(1500 * time.Millisecond)
        for !rejected && time.Now().Before(deadline) {
                select {
                case m := <-p2.msgs:
                        if m.Type == "state" && m.Catchup {
                                t.Fatalf("拉档无响应 + 无可用档：不应收到追赶包，实际 %+v", m)
                        }
                        if m.Type == "error" {
                                rejected = true // 劝退 error 到达（连接随后关闭）
                        }
                case <-p2.closed:
                        rejected = true // 看门狗到期被断开（error 可能尚在读缓冲区）
                case <-time.After(50 * time.Millisecond):
                }
        }
        if !rejected {
                t.Fatal("拉档无响应 + 无可用档：看门狗到期应劝退接入者（error 或断开），实际仍在等待")
        }

        // 3) 房主全程无感（零 pause / 零 restart），房间保持健康：
        //    房主继续跑帧 + 上传，输入照常记账
        host.expectNone("no restart", func(m relayMsg) bool { return m.Type == "restart" })
        host.send(inputMsg(120, 0x44, 1))
        time.Sleep(100 * time.Millisecond)
        host.expectNone("still no pause", func(m relayMsg) bool { return m.Type == "pause" })
}

// waitDeadline 同 wait，但带自定义截止时间（用于"立即追赶"的时间上限断言）。
func (c *testClient) waitDeadline(desc string, pred func(relayMsg) bool, d time.Duration) relayMsg {
        c.t.Helper()
        deadline := time.After(d)
        for {
                select {
                case m := <-c.msgs:
                        if pred(m) {
                                return m
                        }
                case <-c.closed:
                        c.t.Fatalf("[%s] %s: 连接已关闭（未等到期望消息）", c.name, desc)
                case <-deadline:
                        c.t.Fatalf("[%s] %s: %.0fms 内未到达（v55 立即追赶承诺被破坏）",
                                c.name, desc, float64(d)/float64(time.Millisecond))
                }
        }
}
