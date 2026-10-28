package main

// ★★ v52 回归测试：小马模拟器式"中途加入不暂停追赶" + 观战者热转座。
//
// 用户需求："加入小马模拟器那种中途加入不暂停追赶，并完善一下对战平台"。
// 本文件锁定服务端不暂停追赶全链路：
//   1) 对局中 v52 接入者（引擎支持快放）→ 收 state.catchup=1 + 完整输入历史，
//      全员【零 pause 广播】（原玩家完全无感知 —— 本机制的核心价值）；
//   2) 追平 state_ok → 广播 slot_active（座位延迟激活帧 = 最新帧 + 45 裕量）；
//   3) 接入者追赶期间发的历史输入被忽略（不属于真实时间线）；
//   4) 观战者追赶接入：无座位可激活，不广播任何消息；
//   5) 观战者热转座 take_seat：seat_granted + slot_active，转座后输入正常中继；
//   6) 混合旧客户端房间自动退回暂停同步（v<52 不认识 slot_active —— 门控防发散）；
//   7) 无追赶能力的接入者（引擎不支持快放）退回暂停同步；
//   8) 非本轮接入名单成员的 stray state_ok 不触发 resume（completeSync 名单校验）。
// 运行：go test -run TestV52 -v .

import (
	"bufio"
	"encoding/json"
	"fmt"
	"net"
	"strings"
	"testing"
	"time"
)

// dialRoom52 带 hello 协议版本 v=52 的 dialRoom（理解追赶/转座消息的客户端）。
func dialRoom52(t *testing.T, addr, token, roomID string, spectate bool, want int) *testClient {
	t.Helper()
	conn, err := net.DialTimeout("tcp", addr, 3*time.Second)
	if err != nil {
		t.Fatalf("dial %s: %v", addr, err)
	}
	if tcp, ok := conn.(*net.TCPConn); ok {
		_ = tcp.SetNoDelay(true)
	}
	hello := map[string]any{"type": "hello", "token": token, "roomId": roomID, "v": 52}
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

// startHostV52 开一个 v52 房主并单机开跑（playing）。
func startHostV52(t *testing.T, addr, token, roomID string) *testClient {
	t.Helper()
	h := dialRoom52(t, addr, token, roomID, false, 0)
	h.name = "host"
	if m := h.wait("hello host", func(m relayMsg) bool { return m.Type == "hello" }); m.Slot != 1 {
		t.Fatalf("host slot=%d, want 1", m.Slot)
	}
	h.send(map[string]any{"type": "ready", "pad": 2, "stateCap": 1, "catchup": true})
	h.wait("start solo", func(m relayMsg) bool { return m.Type == "start" })
	return h
}

// TestV52CatchupJoinNoPause ★ 核心场景：对局进行中 v52 接入者追赶加入 ——
// 全员不暂停：无 pause 广播、无 state_req；接入者收 catchup=1 存档 + 完整
// 输入历史（16 帧热身窗 + [档帧, 最新帧) 重放窗）；state_ok 后座位延迟激活。
func TestV52CatchupJoinNoPause(t *testing.T) {
	env := newEnv(t, 0)
	tokH, _ := env.register(t, "host")
	tok2, _ := env.register(t, "p2")
	addr := env.tcp.Addr().String()
	roomID := env.createRoom(t, tokH)

	host := startHostV52(t, addr, tokH, roomID)

	// 房主跑帧 0..19（输入历史缓冲记录 20 帧），帧 10 上传一份快照（服务器缓存档）
	for f := int64(0); f < 20; f++ {
		host.send(inputMsg(f, 0x11, 1))
	}
	host.send(map[string]any{"type": "state_upload", "frame": 10, "data": "QUJD", "histEnd": 10})
	time.Sleep(150 * time.Millisecond) // 等服务器收齐（TCP 有序）

	// ★ 2P 追赶接入：v52 + 引擎支持快放
	p2 := dialRoom52(t, addr, tok2, roomID, false, 2)
	p2.name = "p2"
	hm := p2.wait("hello", func(m relayMsg) bool { return m.Type == "hello" })
	if hm.Slot != 2 {
		t.Fatalf("p2 slot=%d, want 2", hm.Slot)
	}
	p2.send(map[string]any{"type": "ready", "pad": 2, "stateCap": 1, "catchup": true})

	// 1) 接入者收到追赶存档：catchup=1 + 完整输入历史
	st := p2.wait("catchup state", func(m relayMsg) bool { return m.Type == "state" })
	if !st.Catchup {
		t.Fatalf("state.catchup=%v, want true（应走不暂停追赶）", st.Catchup)
	}
	if st.Frame != 10 {
		t.Fatalf("state.frame=%d, want 10（服务器缓存档帧）", st.Frame)
	}
	// 历史窗 [max(0,10-16)=0, maxFrame+1=20) → 每槽位 20 帧
	if st.HistEnd != 20 {
		t.Fatalf("histEnd=%d, want 20", st.HistEnd)
	}
	h1 := st.Hist[1]
	if len(h1) != 20 {
		t.Fatalf("hist[1] len=%d, want 20", len(h1))
	}
	if h1[15] != 0x11 || h1[19] != 0x11 {
		t.Fatalf("hist[1] 输入值错位：h1[15]=%x h1[19]=%x", h1[15], h1[19])
	}
	if h2 := st.Hist[2]; len(h2) != 20 {
		t.Fatalf("hist[2] len=%d, want 20", len(h2))
	}

	// 2) ★ 全员不暂停：房主绝不能收到 pause / state_req（本机制核心）
	host.expectNone("no pause", func(m relayMsg) bool { return m.Type == "pause" })
	host.expectNone("no state_req", func(m relayMsg) bool { return m.Type == "state_req" })

	// 3) 追赶中的接入者发的历史输入被忽略（不属于真实时间线）
	p2.send(inputMsg(3, 0xFF, 2))
	host.expectNone("catching input ignored", func(m relayMsg) bool {
		return m.Type == "input" && m.Slot == 2
	})

	// 4) 追平 state_ok → 座位延迟激活广播（激活帧 = 最新帧 + 45 裕量）
	p2.send(map[string]any{"type": "state_ok"})
	sa := host.wait("slot_active", func(m relayMsg) bool { return m.Type == "slot_active" })
	if sa.Slot != 2 {
		t.Fatalf("slot_active.slot=%d, want 2", sa.Slot)
	}
	if sa.Frame <= 20 {
		t.Fatalf("slot_active.frame=%d, want > 20（激活帧 = 最新帧+45 裕量）", sa.Frame)
	}
	if len(sa.Players) != 2 || sa.Players[0] != 1 || sa.Players[1] != 2 {
		t.Fatalf("slot_active.players=%v, want [1 2]", sa.Players)
	}
	// 接入者自己也收到 slot_active（座位激活帧记账）
	sa2 := p2.wait("self slot_active", func(m relayMsg) bool { return m.Type == "slot_active" })
	if sa2.Slot != 2 || sa2.Frame != sa.Frame {
		t.Fatalf("p2 slot_active = %+v, want slot=2 frame=%d", sa2, sa.Frame)
	}

	// 5) 激活后接入者的输入正常中继给房主
	p2.send(inputMsg(25, 0x22, 2))
	im := host.wait("p2 input relay", func(m relayMsg) bool { return m.Type == "input" && m.Slot == 2 })
	if im.Frame != 25 || im.Pad != 0x22 {
		t.Fatalf("p2 input relay = %+v", im)
	}
}

// TestV52SpectatorCatchup 观战者追赶接入：同样不暂停；追平后无座位可激活，
// 服务器不广播 slot_active（观战者直接进入观战）。
func TestV52SpectatorCatchup(t *testing.T) {
	env := newEnv(t, 0)
	tokH, _ := env.register(t, "host")
	tokS, _ := env.register(t, "spec1")
	addr := env.tcp.Addr().String()
	roomID := env.createRoom(t, tokH)

	host := startHostV52(t, addr, tokH, roomID)
	for f := int64(0); f < 12; f++ {
		host.send(inputMsg(f, 3, 1))
	}
	host.send(map[string]any{"type": "state_upload", "frame": 6, "data": "QUJD", "histEnd": 6})
	time.Sleep(150 * time.Millisecond)

	spec := dialRoom52(t, addr, tokS, roomID, true, 0)
	spec.name = "spec"
	spec.wait("hello spectator", func(m relayMsg) bool { return m.Type == "hello" && m.Msg == "spectator" })
	// 观战者引擎也能快放（catchup=true）→ 追赶接入
	spec.send(map[string]any{"type": "ready", "pad": 4, "catchup": true})

	st := spec.wait("spec catchup state", func(m relayMsg) bool { return m.Type == "state" })
	if !st.Catchup {
		t.Fatalf("观战者应走追赶接入（catchup=1）")
	}
	host.expectNone("host no pause", func(m relayMsg) bool { return m.Type == "pause" })

	// 追平 → 观战者无座位：不广播（房主不应收到 slot_active / resume）
	spec.send(map[string]any{"type": "state_ok"})
	host.expectNone("no slot_active for spec", func(m relayMsg) bool {
		return m.Type == "slot_active" || m.Type == "resume"
	})

	// 观战者继续收房主实时输入（同一条锁步输入流的只读消费者）
	host.send(inputMsg(12, 7, 1))
	spec.wait("spec live input", func(m relayMsg) bool { return m.Type == "input" && m.Frame == 12 })
}

// TestV52TakeSeat 观战者热转座：观战中点"入座" → seat_granted + slot_active；
// 转座后输入正常中继（观战者原本发 input 被忽略）；已是玩家再点入座被忽略。
func TestV52TakeSeat(t *testing.T) {
	env := newEnv(t, 0)
	tokH, _ := env.register(t, "host")
	tok2, _ := env.register(t, "p2")
	tokS, _ := env.register(t, "spec1")
	addr := env.tcp.Addr().String()
	roomID := env.createRoom(t, tokH)

	host := startHostV52(t, addr, tokH, roomID)
	// 先备好缓存档 + 输入历史（避免 ready 与 upload 跨连接竞态）
	host.send(inputMsg(0, 1, 1))
	host.send(map[string]any{"type": "state_upload", "frame": 1, "data": "QUJD", "histEnd": 1})
	time.Sleep(150 * time.Millisecond)
	p2 := dialRoom52(t, addr, tok2, roomID, false, 0)
	p2.name = "p2"
	p2.wait("hello p2", func(m relayMsg) bool { return m.Type == "hello" })
	p2.send(map[string]any{"type": "ready", "pad": 2, "stateCap": 1, "catchup": true})
	st := p2.wait("catchup state", func(m relayMsg) bool { return m.Type == "state" })
	if !st.Catchup {
		t.Fatalf("p2 应走追赶接入")
	}
	p2.send(map[string]any{"type": "state_ok"})
	host.wait("p2 slot_active", func(m relayMsg) bool { return m.Type == "slot_active" && m.Slot == 2 })

	// 观战者接入（v52）→ 转座 3P
	spec := dialRoom52(t, addr, tokS, roomID, true, 0)
	spec.name = "spec"
	spec.wait("hello spectator", func(m relayMsg) bool { return m.Type == "hello" })
	spec.send(map[string]any{"type": "take_seat", "slot": 3})

	sg := spec.wait("seat_granted", func(m relayMsg) bool { return m.Type == "seat_granted" })
	if sg.Slot != 3 {
		t.Fatalf("seat_granted.slot=%d, want 3", sg.Slot)
	}
	if sg.Frame <= 0 {
		t.Fatalf("seat_granted.frame=%d, want >0（激活帧）", sg.Frame)
	}
	// 其他成员收到座位激活
	sa := host.wait("host slot_active 3", func(m relayMsg) bool { return m.Type == "slot_active" && m.Slot == 3 })
	if sa.Frame != sg.Frame {
		t.Fatalf("slot_active.frame=%d != seat_granted.frame=%d", sa.Frame, sg.Frame)
	}

	// 转座者的输入正常中继（观战者发 input 原本被忽略）
	spec.send(inputMsg(30, 0x33, 3))
	if im := host.wait("spec input after seat", func(m relayMsg) bool {
		return m.Type == "input" && m.Slot == 3
	}); im.Pad != 0x33 {
		t.Fatalf("转座后输入中继错：%+v", im)
	}

	// 已是玩家再 take_seat → 忽略（无 seat_granted / slot_active）
	spec.send(map[string]any{"type": "take_seat", "slot": 4})
	host.expectNone("player take_seat ignored", func(m relayMsg) bool { return m.Type == "slot_active" && m.Slot == 4 })
}

// TestV52MixedOldClientFallsBack 混合旧客户端门控：房里只要有一个人 v<52
// （不认识 slot_active），追赶关闭 → 退回 v4.5 暂停同步（pause 广播照旧）。
func TestV52MixedOldClientFallsBack(t *testing.T) {
	env := newEnv(t, 0)
	tokH, _ := env.register(t, "host")
	tokS, _ := env.register(t, "oldspec")
	tok2, _ := env.register(t, "p2")
	addr := env.tcp.Addr().String()
	roomID := env.createRoom(t, tokH)

	host := startHostV52(t, addr, tokH, roomID)
	for f := int64(0); f < 8; f++ {
		host.send(inputMsg(f, 1, 1))
	}
	host.send(map[string]any{"type": "state_upload", "frame": 4, "data": "QUJD", "histEnd": 4})
	time.Sleep(150 * time.Millisecond)

	// 旧版本观战者（hello 无 v → v=0）
	old := dialRoom(t, addr, tokS, roomID, true, 0)
	old.name = "oldspec"
	old.wait("hello old spec", func(m relayMsg) bool { return m.Type == "hello" })

	// v52 接入者（带追赶能力）→ 因房内有旧客户端，退回暂停同步
	p2 := dialRoom52(t, addr, tok2, roomID, false, 0)
	p2.name = "p2"
	p2.wait("hello p2", func(m relayMsg) bool { return m.Type == "hello" })
	p2.send(map[string]any{"type": "ready", "pad": 2, "stateCap": 1, "catchup": true})

	host.wait("pause broadcast (fallback)", func(m relayMsg) bool { return m.Type == "pause" })
	// 走老流程：state 转发（不带 catchup）→ state_ok → resume
	st := p2.wait("old-flow state", func(m relayMsg) bool { return m.Type == "state" })
	if st.Catchup {
		t.Fatalf("混合旧客户端房间不应走追赶（catchup 应为 false）")
	}
	p2.send(map[string]any{"type": "state_ok"})
	host.wait("resume broadcast", func(m relayMsg) bool { return m.Type == "resume" })
}

// TestV52NoCatchupCapFallsBack 接入者引擎不支持快放（catchup=false，
// 如 PGM/改版驱动、非 FBNeo 核心）→ 退回暂停同步。
func TestV52NoCatchupCapFallsBack(t *testing.T) {
	env := newEnv(t, 0)
	tokH, _ := env.register(t, "host")
	tok2, _ := env.register(t, "p2")
	addr := env.tcp.Addr().String()
	roomID := env.createRoom(t, tokH)

	host := startHostV52(t, addr, tokH, roomID)
	for f := int64(0); f < 8; f++ {
		host.send(inputMsg(f, 1, 1))
	}
	host.send(map[string]any{"type": "state_upload", "frame": 4, "data": "QUJD", "histEnd": 4})
	time.Sleep(150 * time.Millisecond)

	p2 := dialRoom52(t, addr, tok2, roomID, false, 0)
	p2.name = "p2"
	p2.wait("hello p2", func(m relayMsg) bool { return m.Type == "hello" })
	// v52 但无追赶能力（引擎不支持 runFrameDirect）
	p2.send(map[string]any{"type": "ready", "pad": 2, "stateCap": 1, "catchup": false})

	host.wait("pause (no cap fallback)", func(m relayMsg) bool { return m.Type == "pause" })
	st := p2.wait("state", func(m relayMsg) bool { return m.Type == "state" })
	if st.Catchup {
		t.Fatalf("无追赶能力的接入者不应收到 catchup=1")
	}
}

// TestV52StrayStateOkGuard 非本轮接入名单成员的 stray state_ok 不得触发
// resume（防"提前广播 resume 把未装载的接入者晾在启动门上"）。
func TestV52StrayStateOkGuard(t *testing.T) {
	env := newEnv(t, 0)
	tokH, _ := env.register(t, "host")
	tok2, _ := env.register(t, "p2")
	addr := env.tcp.Addr().String()
	roomID := env.createRoom(t, tokH)

	host := startHostV52(t, addr, tokH, roomID)
	host.send(map[string]any{"type": "state_upload", "frame": 2, "data": "QUJD", "histEnd": 2})
	time.Sleep(150 * time.Millisecond)

	// 无追赶能力的接入者 → 暂停同步（进入 syncing、p2 入 syncJoiners）
	p2 := dialRoom52(t, addr, tok2, roomID, false, 0)
	p2.name = "p2"
	p2.wait("hello p2", func(m relayMsg) bool { return m.Type == "hello" })
	p2.send(map[string]any{"type": "ready", "pad": 2, "stateCap": 1, "catchup": false})
	host.wait("pause", func(m relayMsg) bool { return m.Type == "pause" })

	// 先取走本轮下发的 state（expectNone 会丢弃不匹配消息，顺序不能反）
	p2.wait("state for p2", func(m relayMsg) bool { return m.Type == "state" })

	// 房主（运行成员，不在接入名单）stray 一条 state_ok → 必须被忽略
	host.send(map[string]any{"type": "state_ok"})
	host.expectNone("stray state_ok must not resume", func(m relayMsg) bool { return m.Type == "resume" })
	p2.expectNone("stray state_ok must not resume", func(m relayMsg) bool { return m.Type == "resume" })

	// 真正的接入者 state_ok → 正常 resume
	p2.send(map[string]any{"type": "state_ok"})
	host.wait("resume after real state_ok", func(m relayMsg) bool { return m.Type == "resume" })
}
