package main

// ★★ v51 回归测试：对局中加入选座。
// 用户 bug 现象："对战平台加入时选择 3P，他还是落在 2P 位置"。
// 根因在客户端导航路由（NesApp Routes.BATTLE_MATCH 丢 spectate/wantSlot
// 参数，hello 从不带 want → 服务器只能按"下一空闲位"发座）；服务端
// nextFreeSlot(want) 逻辑本身经 TestV44 验证正确。本测试锁定服务端在
// 【对局进行中】（用户实际点桌加入的场景）同样尊重 want，防止将来回归：
//   1) 房主单机开跑（playing）→ 2P 自动入座（want=0）→ slot 2；
//   2) 3P 对局中加入选 3P（want=3）→ 必须 slot 3（★ 用户场景）；
//   3) want 座位被抢先占用 → 顺延下一空闲座（文档行为，客户端 v51 起会显式提示）；
//   4) 房间列表带座位占用者名单 slotNames（座位对话框显示每个座位上是谁）。
// 运行：go test -run TestV51 -v .

import (
        "encoding/json"
        "net/http"
        "strings"
        "testing"
        "time"
)

func TestV51WantSeatMidGameJoin(t *testing.T) {
        env := newEnv(t, 0)
        tokH, _ := env.register(t, "host")
        tok2, _ := env.register(t, "p2")
        tok3, _ := env.register(t, "p3")
        tok4, _ := env.register(t, "p4")
        addr := env.tcp.Addr().String()
        roomID := env.createRoom(t, tokH)

        // 1. 房主连接 → slot 1 → ready → 单机开跑（房间转 playing，保持可加入）
        host := dialRoom(t, addr, tokH, roomID, false, 0)
        host.name = "host"
        if m := host.wait("hello host", func(m relayMsg) bool { return m.Type == "hello" }); m.Slot != 1 {
                t.Fatalf("host slot=%d, want 1", m.Slot)
        }
        host.send(map[string]any{"type": "ready", "pad": 0})
        host.wait("start solo", func(m relayMsg) bool { return m.Type == "start" })

        // 2. 对局中 2P 自动入座（want=0）→ slot 2
        p2 := dialRoom(t, addr, tok2, roomID, false, 0)
        p2.name = "p2"
        if m := p2.wait("hello auto", func(m relayMsg) bool { return m.Type == "hello" }); m.Slot != 2 {
                t.Fatalf("p2 auto slot=%d, want 2", m.Slot)
        }

        // 3. ★ 用户场景：对局中加入，点选 3P（want=3）→ 必须 slot 3
        p3 := dialRoom(t, addr, tok3, roomID, false, 3)
        p3.name = "p3"
        hm := p3.wait("hello want=3", func(m relayMsg) bool { return m.Type == "hello" })
        if hm.Slot != 3 {
                t.Fatalf("p3 want 3P but got slot=%d（选 3P 落 %dP）", hm.Slot, hm.Slot)
        }
        if len(hm.Players) != 3 {
                t.Fatalf("p3 hello players=%v, want 3 席 [1 2 3]", hm.Players)
        }

        // 4. want 座位被抢先占用（3P 已被 p3 占）→ 顺延到下一空闲座 4P
        //    （客户端 v51 起 onReadyDetail 会显式提示"所选 3P 刚被占用，本次为 4P"）
        p4 := dialRoom(t, addr, tok4, roomID, false, 3)
        p4.name = "p4"
        if m := p4.wait("hello want=3 taken", func(m relayMsg) bool { return m.Type == "hello" }); m.Slot != 4 {
                t.Fatalf("p4 want taken 3P should fall to 4P, got slot=%d", m.Slot)
        }

        // 5. 房间列表带座位占用者名单 slotNames（1P=host 2P=p2 3P=p3 4P=p4）
        lr, err := http.Get(env.srv.URL + "/api/rooms")
        if err != nil {
                t.Fatal(err)
        }
        defer lr.Body.Close()
        var list struct {
                Rooms []struct {
                        ID        string            `json:"id"`
                        SlotNames map[string]string `json:"slotNames"`
                } `json:"rooms"`
        }
        if err := json.NewDecoder(lr.Body).Decode(&list); err != nil {
                t.Fatal(err)
        }
        for _, r := range list.Rooms {
                if r.ID != roomID {
                        continue
                }
                want := map[string]string{"1": "host", "2": "p2", "3": "p3", "4": "p4"}
                for k, v := range want {
                        if r.SlotNames[k] != v {
                                t.Fatalf("slotNames[%s]=%q, want %q（全表 %v）", k, r.SlotNames[k], v, r.SlotNames)
                        }
                }
                return
        }
        t.Fatal("room not found in list")
}

// TestV51ChatRelay 游戏内聊天中继：
//   1) 玩家发 chat → 其他玩家/观战者收到（from/slot/spec 服务器盖戳）；
//   2) 观战者也能发（spec=true、slot=0）；
//   3) 发送者自己收不到回显（客户端本地补录展示）；
//   4) 超长文本（>200 字符）服务器截断到 200。
func TestV51ChatRelay(t *testing.T) {
        env := newEnv(t, 0)
        tokH, _ := env.register(t, "host")
        tok2, _ := env.register(t, "p2")
        tokS, _ := env.register(t, "spec1")
        addr := env.tcp.Addr().String()
        roomID := env.createRoom(t, tokH)

        host := dialRoom(t, addr, tokH, roomID, false, 0)
        host.name = "host"
        host.wait("hello host", func(m relayMsg) bool { return m.Type == "hello" })
        p2 := dialRoom(t, addr, tok2, roomID, false, 2)
        p2.name = "p2"
        if m := p2.wait("hello want=2", func(m relayMsg) bool { return m.Type == "hello" }); m.Slot != 2 {
                t.Fatalf("p2 slot=%d", m.Slot)
        }
        spec := dialRoom(t, addr, tokS, roomID, true, 0)
        spec.name = "spec"
        spec.wait("hello spectator", func(m relayMsg) bool { return m.Type == "hello" })

        // 1. 玩家 p2 发聊天 → host 和观战者都收到（盖戳 from=p2 slot=2 spec=false）
        p2.send(map[string]any{"type": "chat", "msg": "开始了没？"})
        cm := host.wait("host recv chat", func(m relayMsg) bool { return m.Type == "chat" })
        if cm.Msg != "开始了没？" || cm.From != "p2" || cm.Slot != 2 || cm.Spec {
                t.Fatalf("host chat = %+v", cm)
        }
        sm := spec.wait("spec recv chat", func(m relayMsg) bool { return m.Type == "chat" })
        if sm.From != "p2" || sm.Slot != 2 {
                t.Fatalf("spec chat = %+v", sm)
        }

        // 2. 观战者发聊天 → 玩家收到（from=spec1 spec=true slot=0）
        spec.send(map[string]any{"type": "chat", "msg": "观战看看"})
        cm2 := host.wait("host recv spec chat", func(m relayMsg) bool { return m.Type == "chat" })
        if cm2.Msg != "观战看看" || cm2.From != "spec1" || !cm2.Spec || cm2.Slot != 0 {
                t.Fatalf("spec chat relay = %+v", cm2)
        }

        // 3. 发送者自己收不到回显（其他人 —— 如观战者 —— 的消息照常收到）
        p2.expectNone("sender echo", func(m relayMsg) bool { return m.Type == "chat" && m.From == "p2" })

        // 4. 超长截断到 200 字符
        long := strings.Repeat("哈", 300)
        p2.send(map[string]any{"type": "chat", "msg": long})
        cm3 := host.wait("clamped chat", func(m relayMsg) bool { return m.Type == "chat" })
        if got := []rune(cm3.Msg); len(got) != 200 {
                t.Fatalf("chat clamp = %d runes, want 200", len(got))
        }

        // 空白消息被丢弃（不转发）
        p2.send(map[string]any{"type": "chat", "msg": "   "})
        host.expectNone("blank chat", func(m relayMsg) bool { return m.Type == "chat" })

        // 慢连接不打断：聊天消息能及时到达（异步 outbox 队列）
        time.Sleep(100 * time.Millisecond)
}
