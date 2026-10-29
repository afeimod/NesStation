package main

// ★★ v53 回归测试：追赶机制完善（静默拉档 + 旧档可用 + 聊天能力协商）。
//
// 用户 v52 实测反馈（截图复盘）：
//   1) "对战平台追赶后并没有实现同步"（图二 #5325/#5340 vs 图三 #1485）——
//      根因链：房主快照上传间隔大档 15s + 出档线程繁忙 → 缓存档距最新帧
//      60s+（3600+ 帧）> 历史缓冲 3600 帧 → v52 追赶判定失败 → 静默降级
//      暂停同步/全员重开（用户看到的是"拉回旧画面"，不是追赶）。
//   2) "聊天对方也收不到"——v52 服务端 chat 中继本身正确（TestV51ChatRelay
//      全绿）；典型根因是线上服务器二进制未随客户端同步更新（<v51 无 chat
//      case）。v53 hello 回执带 srv 字段，客户端据此显示能力提示，不再让
//      用户误以为是客户端 bug。
//
// 本文件锁定 v53 服务端行为：
//   1) 无缓存档 + 全员 v53 → 静默拉档（state_pull 只发房主，全员零 pause）
//      → 房主 state_upload → 接入者收追赶包（catchup=1 + 完整历史）；
//   2) 历史缺口（档太旧）+ 全员 v53 → 同样走静默拉档（不降级暂停同步）；
//   3) 拉档期间接入者发聊天照常转发（chat 不受 catchingUp 门控）；
//   4) 接入者 state_ok → slot_active 广播（座位延迟激活）；
//   5) 混合 v52 客户端房间 → 不走拉档（v52 不认识 state_pull），
//      维持 v52 降级链（fresh/restart，与旧行为完全一致）；
//   6) 旧档（超新鲜窗口）+ 历史完整 → 直接追赶（v52 会因"不新鲜"降级）；
//   7) hello 回执带 srv=53（客户端能力协商）；
//   8) 拉档等待中接入者掉线 → 名单移除、不误发追赶包。
// 运行：go test -run TestV53 -v .

import (
        "bufio"
        "encoding/json"
        "fmt"
        "net"
        "strings"
        "testing"
        "time"
)

// dialRoomV 带 hello 协议版本 v 的 dialRoom（v53 = 理解 state_pull/srv）。
// 与 v52 测试的 dialRoom52 同一模式：hello 首行带 v，其余与 dialRoom 一致。
func dialRoomV(t *testing.T, addr, token, roomID string, spectate bool, want, v int) *testClient {
        t.Helper()
        conn, err := net.DialTimeout("tcp", addr, 3*time.Second)
        if err != nil {
                t.Fatalf("dial %s: %v", addr, err)
        }
        if tcp, ok := conn.(*net.TCPConn); ok {
                _ = tcp.SetNoDelay(true)
        }
        hello := map[string]any{"type": "hello", "token": token, "roomId": roomID, "v": v}
        if spectate {
                hello["spectate"] = true
        }
        if want > 0 {
                hello["want"] = want
        }
        b, _ := json.Marshal(hello)
        fmt.Fprintf(conn, "%s\n", b)
        c := &testClient{t: t, conn: conn, rd: bufio.NewReaderSize(conn, 1<<20), msgs: make(chan relayMsg, 256), closed: make(chan struct{})}
        go func() {
                defer close(c.closed)
                for {
                        line, err := c.rd.ReadString('\n')
                        if err != nil {
                                return
                        }
                        line = strings.TrimSpace(line)
                        if line == "" {
                                continue
                        }
                        var m relayMsg
                        if json.Unmarshal([]byte(line), &m) == nil {
                                c.msgs <- m
                        }
                }
        }()
        return c
}

// startHost53 开一个 v53 房主并单机开跑（playing）。
func startHost53(t *testing.T, addr, token, roomID string) *testClient {
        t.Helper()
        h := dialRoomV(t, addr, token, roomID, false, 0, 53)
        h.name = "host"
        if m := h.wait("hello host", func(m relayMsg) bool { return m.Type == "hello" }); m.Slot != 1 {
                t.Fatalf("host slot=%d, want 1", m.Slot)
        }
        h.send(map[string]any{"type": "ready", "pad": 2, "stateCap": 1, "catchup": true})
        h.wait("start solo", func(m relayMsg) bool { return m.Type == "start" })
        return h
}

// TestV53PullSnapshotNoCache ★ 核心场景一：服务器完全没有缓存档（房主从未
// 上传——PGM 出档探测失败/服务器重启丢缓存），全员 v53 + 接入者能追赶 →
// 不降级！静默拉档：接入者零 pause，房主收到 state_pull（仅房主），
// 房主上传新快照后接入者收到追赶包（catchup=1 + 历史完整）。
func TestV53PullSnapshotNoCache(t *testing.T) {
        env := newEnv(t, 0)
        tokH, _ := env.register(t, "host")
        tok2, _ := env.register(t, "p2")
        addr := env.tcp.Addr().String()
        roomID := env.createRoom(t, tokH)

        host := startHost53(t, addr, tokH, roomID)
        // 房主跑 30 帧（历史缓冲有货），但从未上传快照（服务器无缓存档）
        for f := int64(0); f < 30; f++ {
                host.send(inputMsg(f, 0x11, 1))
        }
        time.Sleep(120 * time.Millisecond)

        p2 := dialRoomV(t, addr, tok2, roomID, false, 2, 53)
        p2.name = "p2"
        if m := p2.wait("hello", func(m relayMsg) bool { return m.Type == "hello" }); m.Slot != 2 {
                t.Fatalf("p2 slot=%d", m.Slot)
        }
        p2.send(map[string]any{"type": "ready", "pad": 2, "stateCap": 1, "catchup": true})

        // 1) 房主（仅房主）收到 state_pull；全员绝不能收到 pause
        pl := host.wait("host state_pull", func(m relayMsg) bool { return m.Type == "state_pull" })
        _ = pl
        host.expectNone("no pause", func(m relayMsg) bool { return m.Type == "pause" })
        p2.expectNone("p2 no pause", func(m relayMsg) bool { return m.Type == "pause" })

        // 2) 房主出档上传（引擎线程下一帧边界的行为，测试直接模拟 state_upload）
        host.send(map[string]any{"type": "state_upload", "frame": 31, "data": "QUJD", "histEnd": 31})

        // 3) 接入者收到追赶包：catchup=1 + 档帧 31 + 完整历史（热身窗 15..31 + 重放窗 31..32）
        st := p2.wait("catchup state", func(m relayMsg) bool { return m.Type == "state" })
        if !st.Catchup {
                t.Fatalf("state.catchup=%v, want true（静默拉档后必须走追赶）", st.Catchup)
        }
        if st.Frame != 31 {
                t.Fatalf("state.frame=%d, want 31（拉档后的新快照帧）", st.Frame)
        }
        if st.HistEnd != 31 {
                t.Fatalf("histEnd=%d, want 31（maxFrame 30 + 1）", st.HistEnd)
        }
        // 新档场景：重放窗 [档帧31, 最新31) = 0 帧，历史 = 16 帧热身窗 [15,31)
        if len(st.Hist[1]) != 16 {
                t.Fatalf("hist[1] len=%d, want 16（[15,31) 热身窗 —— 新档即最新帧，零重放）", len(st.Hist[1]))
        }

        // 4) 接入者 state_ok → 广播 slot_active（激活帧 = 最新帧 + 45）
        p2.send(map[string]any{"type": "state_ok"})
        sa := host.wait("slot_active", func(m relayMsg) bool { return m.Type == "slot_active" })
        if sa.Slot != 2 {
                t.Fatalf("slot_active.slot=%d, want 2", sa.Slot)
        }
        if sa.Frame < 31 {
                t.Fatalf("slot_active.frame=%d, want >= 31（最新帧+45 裕量）", sa.Frame)
        }

        // 5) 追赶后输入正常中继
        host.send(inputMsg(50, 0x22, 1))
        ri := p2.wait("input relay", func(m relayMsg) bool {
                return m.Type == "input" && m.Slot == 1 && m.Frame == 50
        })
        if ri.Pad != 0x22 {
                t.Fatalf("relay pad=%x, want 0x22", ri.Pad)
        }
}

// TestV53PullSnapshotHistGap ★ 核心场景二：有缓存档但历史覆盖不到（档太旧，
// v52 实测翻车场景：上传间隔 15s × 大档 + 网络抖动 → 档距最新 60s+，超过
// v52 的 3600 帧历史缓冲）→ v53 不降级，静默拉档后追赶。
func TestV53PullSnapshotHistGap(t *testing.T) {
        env := newEnv(t, 0)
        tokH, _ := env.register(t, "host")
        tok2, _ := env.register(t, "p2")
        addr := env.tcp.Addr().String()
        roomID := env.createRoom(t, tokH)

        host := startHost53(t, addr, tokH, roomID)
        // 房主跑 0..99 帧，帧 5 上传快照（距最新 95 帧 —— 制造"档旧于历史
        // 起点"的缺口需要 > 历史缓冲，单测里不真跑 7200 帧：直接构造
        // inpHistStart=50 的等价场景 —— 服务器丢弃 0..49 历史后档帧 5-16<0
        // 的覆盖不足。简化：上传帧 5 的档，再把历史起点抬到 40（等价于
        // 环形缓冲已淘汰 [0,40)）—— 通过真实 input 流量做不到（缓冲 7200），
        // 直接改房间内部状态（白盒）。
        for f := int64(0); f < 100; f++ {
                host.send(inputMsg(f, 0x11, 1))
        }
        host.send(map[string]any{"type": "state_upload", "frame": 5, "data": "QUJD", "histEnd": 5})
        time.Sleep(120 * time.Millisecond)

        // 白盒：把历史起点抬到 40（模拟环形缓冲已淘汰 [0,40) → 档帧 5-16 覆盖不到）
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

        // v52 行为：catchupOk=false（histStart 40 > 5-16）→ 降级暂停同步（pause 广播）。
        // v53 行为：静默拉档（无 pause，state_pull → 新档 → 追赶包）。
        host.wait("host state_pull", func(m relayMsg) bool { return m.Type == "state_pull" })
        host.expectNone("no pause", func(m relayMsg) bool { return m.Type == "pause" })

        host.send(map[string]any{"type": "state_upload", "frame": 101, "data": "QUJD", "histEnd": 101})
        st := p2.wait("catchup state", func(m relayMsg) bool { return m.Type == "state" })
        if !st.Catchup || st.Frame != 101 {
                t.Fatalf("state = %+v, want catchup=1 frame=101（拉档新快照）", st)
        }
        // 历史覆盖 [85, 101)（新档 101-16=85 >= 历史起点 40 ✓）
        if len(st.Hist[1]) != 101-85 {
                t.Fatalf("hist[1] len=%d, want 16（[85,101) 热身窗+重放窗）", len(st.Hist[1]))
        }
}

// TestV53ChatDuringPull 拉档等待期间聊天照常转发（chat 不受拉档/追赶门控 ——
// 用户 v52 反馈"聊天对方也收不到"的回归锁定：等待追赶包的接入者也能聊天）。
func TestV53ChatDuringPull(t *testing.T) {
        env := newEnv(t, 0)
        tokH, _ := env.register(t, "host")
        tok2, _ := env.register(t, "p2")
        addr := env.tcp.Addr().String()
        roomID := env.createRoom(t, tokH)

        host := startHost53(t, addr, tokH, roomID)
        for f := int64(0); f < 30; f++ {
                host.send(inputMsg(f, 0x11, 1))
        }
        time.Sleep(120 * time.Millisecond)

        p2 := dialRoomV(t, addr, tok2, roomID, false, 2, 53)
        p2.name = "p2"
        p2.wait("hello", func(m relayMsg) bool { return m.Type == "hello" })
        p2.send(map[string]any{"type": "ready", "pad": 2, "stateCap": 1, "catchup": true})
        host.wait("state_pull", func(m relayMsg) bool { return m.Type == "state_pull" })

        // 拉档等待中（尚未收追赶包）：双向聊天都必须通
        host.send(map[string]any{"type": "chat", "msg": "等我出个档"})
        cm := p2.wait("p2 recv chat", func(m relayMsg) bool { return m.Type == "chat" })
        if cm.Msg != "等我出个档" || cm.From != "host" || cm.Slot != 1 {
                t.Fatalf("p2 chat = %+v", cm)
        }
        p2.send(map[string]any{"type": "chat", "msg": "好的不急"})
        cm2 := host.wait("host recv chat", func(m relayMsg) bool { return m.Type == "chat" })
        if cm2.Msg != "好的不急" || cm2.From != "p2" || cm2.Slot != 2 {
                t.Fatalf("host chat = %+v", cm2)
        }

        // 拉档照常收尾
        host.send(map[string]any{"type": "state_upload", "frame": 31, "data": "QUJD", "histEnd": 31})
        p2.wait("catchup state", func(m relayMsg) bool { return m.Type == "state" && m.Catchup })
}

// TestV53MixedV52NoPull 混合 v52 客户端的房间不走拉档（v52 不认识
// state_pull，拉了也白拉）—— 维持 v52 降级链（fresh 房主出档暂停同步），
// 与旧行为完全一致（向后兼容锁定）。
func TestV53MixedV52NoPull(t *testing.T) {
        env := newEnv(t, 0)
        tokH, _ := env.register(t, "host")
        tok2, _ := env.register(t, "p2")
        addr := env.tcp.Addr().String()
        roomID := env.createRoom(t, tokH)

        host := dialRoomV(t, addr, tokH, roomID, false, 0, 53)
        host.name = "host"
        host.wait("hello", func(m relayMsg) bool { return m.Type == "hello" })
        host.send(map[string]any{"type": "ready", "pad": 2, "stateCap": 1, "catchup": true})
        host.wait("start", func(m relayMsg) bool { return m.Type == "start" })

        for f := int64(0); f < 30; f++ {
                host.send(inputMsg(f, 0x11, 1))
        }
        time.Sleep(120 * time.Millisecond)

        // v52 接入者（不认识 state_pull）
        p2 := dialRoomV(t, addr, tok2, roomID, false, 2, 52)
        p2.name = "p2"
        p2.wait("hello", func(m relayMsg) bool { return m.Type == "hello" })
        p2.send(map[string]any{"type": "ready", "pad": 2, "stateCap": 1, "catchup": true})

        // v52 行为：无缓存档 → fresh（state_req 仅房主 + 全员 pause 暂停同步）。
        // 消息顺序：先广播 pause，再发 state_req（wait 会消费掉不匹配的消息 ——
        // 必须先等 pause 再等 state_req，顺序反了会把 pause 丢掉）。
        host.wait("pause broadcast", func(m relayMsg) bool { return m.Type == "pause" })
        host.wait("state_req", func(m relayMsg) bool { return m.Type == "state_req" })
        host.expectNone("no state_pull to v52 room", func(m relayMsg) bool { return m.Type == "state_pull" })
}

// TestV53StaleCacheStillCatches 旧档 + 历史完整 + 全员 v53：
// ★★ v55 语义（立即追赶）：历史覆盖到的缓存档【无论多旧】直接立即发追赶包
//   （v54 曾改为“>4s 旧档优先拉新档”，实测接入者在启动门上等拉档往返
//   数秒到 45s —— 用户实测“等待同步开始太久，应该直接进行同步追赶”）。
//   v55：不 state_pull、不 pause，接入者立即收旧档追赶包（v54+ 客户端
//   黑屏紧循环快放数秒追平），后续链路（state_ok → 座位激活、迟到快照
//   不二次发包、输入照常中继）全部锁定。
func TestV53StaleCacheStillCatches(t *testing.T) {
        env := newEnv(t, 0)
        tokH, _ := env.register(t, "host")
        tok2, _ := env.register(t, "p2")
        addr := env.tcp.Addr().String()
        roomID := env.createRoom(t, tokH)

        host := startHost53(t, addr, tokH, roomID)
        for f := int64(0); f < 30; f++ {
                host.send(inputMsg(f, 0x11, 1))
        }
        host.send(map[string]any{"type": "state_upload", "frame": 20, "data": "QUJD", "histEnd": 20})
        time.Sleep(120 * time.Millisecond)

        // 白盒：把缓存时间拨回 60s 前（等价“不新鲜”，但历史缓冲 7200 仍完整覆盖）
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

        // ★ v55：旧档 + 历史完整 → 立即追赶包（绝不 state_pull、绝不 pause）
        st := p2.wait("immediate catchup state", func(m relayMsg) bool { return m.Type == "state" })
        if !st.Catchup || st.Frame != 20 {
                t.Fatalf("state = %+v, want catchup=1 frame=20（旧档立即追赶）", st)
        }
        if len(st.Hist[1]) != 30-4 {
                t.Fatalf("hist[1] len=%d, want %d（[4,30) 完整历史）", len(st.Hist[1]), 30-4)
        }
        host.expectNone("no state_pull (v55 立即追赶)", func(m relayMsg) bool { return m.Type == "state_pull" })
        host.expectNone("no pause", func(m relayMsg) bool { return m.Type == "pause" })

        // 追平 state_ok → 座位激活；迟到快照不二次发追赶包；输入照常中继
        p2.send(map[string]any{"type": "state_ok"})
        sa := host.wait("slot_active", func(m relayMsg) bool { return m.Type == "slot_active" })
        if sa.Slot != 2 {
                t.Fatalf("slot_active.slot=%d, want 2", sa.Slot)
        }
        host.send(map[string]any{"type": "state_upload", "frame": 40, "data": "QUJD", "histEnd": 40})
        p2.expectNone("no double catchup state", func(m relayMsg) bool {
                return m.Type == "state" && m.Catchup
        })
        host.send(inputMsg(50, 0x33, 1))
        ri := p2.wait("input relay", func(m relayMsg) bool {
                return m.Type == "input" && m.Slot == 1 && m.Frame == 50
        })
        if ri.Pad != 0x33 {
                t.Fatalf("relay pad=%x, want 0x33", ri.Pad)
        }
}

// TestV53HelloSrvField hello 回执必须带 srv=53（客户端能力协商依据 ——
// 老服务器不回 srv，客户端据此在聊天面板显示"服务器版本较旧"提示）。
func TestV53HelloSrvField(t *testing.T) {
        env := newEnv(t, 0)
        tokH, _ := env.register(t, "host")
        addr := env.tcp.Addr().String()
        roomID := env.createRoom(t, tokH)

        host := dialRoomV(t, addr, tokH, roomID, false, 0, 53)
        host.name = "host"
        hm := host.wait("hello", func(m relayMsg) bool { return m.Type == "hello" })
        if hm.Srv != serverProto {
                t.Fatalf("hello.srv=%d, want %d（v53 能力协商字段）", hm.Srv, serverProto)
        }

        // 观战者回执同样带 srv
        tokS, _ := env.register(t, "spec1")
        spec := dialRoomV(t, addr, tokS, roomID, true, 0, 53)
        spec.name = "spec"
        sm := spec.wait("spec hello", func(m relayMsg) bool { return m.Type == "hello" })
        if sm.Srv != serverProto {
                t.Fatalf("spec hello.srv=%d, want %d", sm.Srv, serverProto)
        }
}

// TestV53PullDropWhileWaiting 拉档等待中接入者掉线 → 名单移除（房主补档
// 到达时不给已掉线的连接发追赶包），房间其他人完全无感。
func TestV53PullDropWhileWaiting(t *testing.T) {
        env := newEnv(t, 0)
        tokH, _ := env.register(t, "host")
        tok2, _ := env.register(t, "p2")
        addr := env.tcp.Addr().String()
        roomID := env.createRoom(t, tokH)

        host := startHost53(t, addr, tokH, roomID)
        for f := int64(0); f < 30; f++ {
                host.send(inputMsg(f, 0x11, 1))
        }
        time.Sleep(120 * time.Millisecond)

        p2 := dialRoomV(t, addr, tok2, roomID, false, 2, 53)
        p2.name = "p2"
        p2.wait("hello", func(m relayMsg) bool { return m.Type == "hello" })
        p2.send(map[string]any{"type": "ready", "pad": 2, "stateCap": 1, "catchup": true})
        host.wait("state_pull", func(m relayMsg) bool { return m.Type == "state_pull" })

        // 接入者掉线（拉档等待中）
        p2.conn.Close()
        host.wait("peer_left", func(m relayMsg) bool { return m.Type == "peer_left" })
        time.Sleep(50 * time.Millisecond)

        // 房主补档：不给已掉线的接入者发（也不 panic/不卡死）
        host.send(map[string]any{"type": "state_upload", "frame": 31, "data": "QUJD", "histEnd": 31})
        time.Sleep(150 * time.Millisecond)

        // 房间状态仍健康：房主输入照常记账，没有任何 pause/restart 广播
        host.expectNone("no pause", func(m relayMsg) bool { return m.Type == "pause" })
        host.expectNone("no restart", func(m relayMsg) bool { return m.Type == "restart" })

        // 下一个接入者仍能正常拉档追赶
        tok3, _ := env.register(t, "p3")
        p3 := dialRoomV(t, addr, tok3, roomID, false, 2, 53)
        p3.name = "p3"
        p3.wait("hello", func(m relayMsg) bool { return m.Type == "hello" })
        p3.send(map[string]any{"type": "ready", "pad": 2, "stateCap": 1, "catchup": true})
        st := p3.wait("catchup state", func(m relayMsg) bool { return m.Type == "state" })
        if !st.Catchup {
                t.Fatalf("state.catchup=%v, want true（拉档链路恢复健康）", st.Catchup)
        }
}
