package main

// ★★ v59 聊天压力回归：复现用户实测场景 ——
//   "2p发了几十个信息最多显示一个给1p，1p发几十个信息一个都没显示给2p"
//   两个玩家在房间内、60Hz 输入流照常跑，双方各快速连发 40 条聊天，
//   断言：对方必须【一条不少】全部收到（顺序不作要求之外的完整性）。
//   如果服务器在输入流压力下丢 chat（比如某条 case 互斥/队列/竞态），
//   本测试立刻红。
//
// 同时验证：
//   - 聊天与输入流交错（同一条 TCP 连接上 input/chat 混发）不互相干扰；
//   - 聊天不进 padsHistory 语义由客户端负责（服务器只管转发）。

import (
	"fmt"
	"testing"
	"time"
)

// TestV59ChatStressUnderInputFlow：60Hz 输入流 + 双向各 40 条聊天，零丢失。
func TestV59ChatStressUnderInputFlow(t *testing.T) {
	env := newEnv(t, 0)
	tokH, _ := env.register(t, "host")
	tok2, _ := env.register(t, "p2")
	addr := env.tcp.Addr().String()
	roomID := env.createRoom(t, tokH)

	host := dialRoom(t, addr, tokH, roomID, false, 0)
	host.name = "host"
	host.wait("hello host", func(m relayMsg) bool { return m.Type == "hello" })
	p2 := dialRoom(t, addr, tok2, roomID, false, 2)
	p2.name = "p2"
	p2.wait("hello want2", func(m relayMsg) bool { return m.Type == "hello" })

	// 双方 ready → start
	host.send(map[string]any{"type": "ready", "pad": 2, "stateCap": 1, "catchup": true})
	p2.send(map[string]any{"type": "ready", "pad": 2, "stateCap": 1, "catchup": true})
	host.wait("start", func(m relayMsg) bool { return m.Type == "start" })
	p2.wait("start", func(m relayMsg) bool { return m.Type == "start" })

	// 模拟双方 60Hz 输入流（跑 200 帧），同时每方连发 40 条聊天
	const frames = 200
	const chats = 40
	stop := make(chan struct{})
	go func() {
		f := int64(0)
		for {
			select {
			case <-stop:
				return
			default:
			}
			host.send(map[string]any{"type": "input", "frame": f, "pad": 0x11, "slot": 1})
			p2.send(map[string]any{"type": "input", "frame": f, "pad": 0x22, "slot": 2})
			f++
			if f > frames {
				return
			}
			time.Sleep(16 * time.Millisecond)
		}
	}()
	// 立刻双向连发 40 条聊天（与输入流同线交错）
	for i := 0; i < chats; i++ {
		p2.send(map[string]any{"type": "chat", "msg": fmt.Sprintf("p2消息%02d", i)})
		host.send(map[string]any{"type": "chat", "msg": fmt.Sprintf("1p消息%02d", i)})
	}
	time.Sleep(50 * time.Millisecond)
	close(stop)

	// host 必须收齐 p2 的 40 条
	gotP2 := map[string]bool{}
	deadline := time.Now().Add(5 * time.Second)
	for len(gotP2) < chats && time.Now().Before(deadline) {
		m := host.wait("host recv", func(m relayMsg) bool { return m.Type == "chat" })
		if m.From == "p2" {
			gotP2[m.Msg] = true
		}
	}
	missing := 0
	for i := 0; i < chats; i++ {
		if !gotP2[fmt.Sprintf("p2消息%02d", i)] {
			missing++
		}
	}
	if missing > 0 {
		t.Fatalf("host 只收到 %d/%d 条 p2 聊天（丢失 %d 条）—— 服务器在输入流压力下丢 chat", len(gotP2), chats, missing)
	}

	// p2 必须收齐 host 的 40 条
	gotHost := map[string]bool{}
	deadline = time.Now().Add(5 * time.Second)
	for len(gotHost) < chats && time.Now().Before(deadline) {
		m := p2.wait("p2 recv", func(m relayMsg) bool { return m.Type == "chat" })
		if m.From == "host" {
			gotHost[m.Msg] = true
		}
	}
	missing = 0
	for i := 0; i < chats; i++ {
		if !gotHost[fmt.Sprintf("1p消息%02d", i)] {
			missing++
		}
	}
	if missing > 0 {
		t.Fatalf("p2 只收到 %d/%d 条 host 聊天（丢失 %d 条）—— 服务器在输入流压力下丢 chat", len(gotHost), chats, missing)
	}
}
