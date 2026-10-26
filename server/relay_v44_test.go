package main

// ★★ v4.4 端到端测试：观战系统 + 指定座位(want) + inputDelay=0 自动模式 +
// 输入/存档广播覆盖观战者 + 满员引导观战 + 观战者离开/房间清理。
// 运行：go test -run TestV44 -v .（交付包不含本文件）

import (
        "bufio"
        "encoding/json"
        "fmt"
        "net"
        "net/http"
        "net/http/httptest"
        "strings"
        "testing"
        "time"
)

// ---- 测试用 TCP 客户端 ----

type testClient struct {
        t     *testing.T
        conn  net.Conn
        rd    *bufio.Reader
        msgs  chan relayMsg
        name  string
        closed chan struct{}
}

func dialRoom(t *testing.T, addr, token, roomID string, spectate bool, want int) *testClient {
        t.Helper()
        conn, err := net.DialTimeout("tcp", addr, 3*time.Second)
        if err != nil {
                t.Fatalf("dial %s: %v", addr, err)
        }
        if tcp, ok := conn.(*net.TCPConn); ok {
                _ = tcp.SetNoDelay(true)
        }
        hello := map[string]any{"type": "hello", "token": token, "roomId": roomID}
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

func (c *testClient) send(m map[string]any) {
        c.t.Helper()
        b, _ := json.Marshal(m)
        fmt.Fprintf(c.conn, "%s\n", b)
}

// wait 等待一条满足 predicate 的消息（跳过不匹配的，3s 超时）
func (c *testClient) wait(desc string, pred func(relayMsg) bool) relayMsg {
        c.t.Helper()
        deadline := time.After(3 * time.Second)
        for {
                select {
                case m := <-c.msgs:
                        if pred(m) {
                                return m
                        }
                case <-c.closed:
                        c.t.Fatalf("[%s] 连接提前关闭", c.name)
                case <-deadline:
                        c.t.Fatalf("[%s] 3s 内未等到消息: %s", c.name, desc)
                }
        }
}

// expectNone 确认一段时间内没有匹配的消息
func (c *testClient) expectNone(desc string, pred func(relayMsg) bool) {
        c.t.Helper()
        for {
                select {
                case m := <-c.msgs:
                        if pred(m) {
                                c.t.Fatalf("[%s] 不应收到: %s (%+v)", c.name, desc, m)
                        }
                case <-time.After(300 * time.Millisecond):
                        return
                }
        }
}

func inputMsg(frame int64, pad, slot int) map[string]any {
        return map[string]any{"type": "input", "frame": frame, "pad": pad, "slot": slot}
}

// ---- 测试环境 ----

type v44Env struct {
        srv *httptest.Server
        hub *relayHub
        api *apiServer
        cfg *configStore
        tcp net.Listener
}

func newEnv(t *testing.T, inputDelay int) *v44Env {
        t.Helper()
        dir := t.TempDir()
        cfg := &Config{
                HTTPAddr:   "127.0.0.1:0",
                TCPAddr:    "127.0.0.1:0",
                JWTSecret:  "test-secret",
                DataDir:    dir,
                InputDelay: inputDelay,
                MaxRooms:   10,
                Games:      []Game{{ID: "kof97", Title: "拳皇97", Platform: "arcade", ROMURL: "http://x/k.zip"}},
        }
        if err := cfg.validate(); err != nil {
                t.Fatal(err)
        }
        store, err := newUserStore(dir)
        if err != nil {
                t.Fatal(err)
        }
        t.Cleanup(func() { _ = store.Close() })
        jwter := newJWT(cfg.JWTSecret, time.Hour)
        cs := newConfigStore(cfg)
        hub := newRelayHub(cs, jwter, store)
        api := &apiServer{cfg: cs, users: store, hub: hub, jwt: jwter, romCache: newRomCache(1 << 20)}
        srv := httptest.NewServer(api.routes())
        t.Cleanup(srv.Close)

        tcpLn, err := net.Listen("tcp", "127.0.0.1:0")
        if err != nil {
                t.Fatal(err)
        }
        t.Cleanup(func() { _ = tcpLn.Close() })
        go func() {
                for {
                        conn, err := tcpLn.Accept()
                        if err != nil {
                                return
                        }
                        go hub.handleConn(conn)
                }
        }()
        return &v44Env{srv: srv, hub: hub, api: api, cfg: cs, tcp: tcpLn}
}

func (e *v44Env) register(t *testing.T, name string) (token, userID string) {
        t.Helper()
        body := fmt.Sprintf(`{"username":%q,"password":"pass1234"}`, name)
        resp, err := http.Post(e.srv.URL+"/api/auth/register", "application/json", strings.NewReader(body))
        if err != nil {
                t.Fatal(err)
        }
        defer resp.Body.Close()
        var out struct {
                Token string `json:"token"`
                User  struct {
                        ID string `json:"id"`
                } `json:"user"`
        }
        if err := json.NewDecoder(resp.Body).Decode(&out); err != nil {
                t.Fatal(err)
        }
        return out.Token, out.User.ID
}

func (e *v44Env) createRoom(t *testing.T, token string) string {
        t.Helper()
        resp, err := http.Post(e.srv.URL+"/api/rooms", "application/json",
                strings.NewReader(`{"gameId":"kof97"}`))
        if err != nil {
                t.Fatal(err)
        }
        defer resp.Body.Close()
        bearer := "Bearer " + token
        req, _ := http.NewRequest("POST", e.srv.URL+"/api/rooms", strings.NewReader(`{"gameId":"kof97"}`))
        req.Header.Set("Authorization", bearer)
        req.Header.Set("Content-Type", "application/json")
        resp2, err := http.DefaultClient.Do(req)
        if err != nil {
                t.Fatal(err)
        }
        defer resp2.Body.Close()
        if resp2.StatusCode != 200 {
                t.Fatalf("createRoom status %d", resp2.StatusCode)
        }
        var out struct {
                Room struct {
                        ID string `json:"id"`
                } `json:"room"`
        }
        _ = json.NewDecoder(resp2.Body).Decode(&out)
        if out.Room.ID == "" {
                t.Fatal("room id empty")
        }
        return out.Room.ID
}

// ---- 主测试 ----

func TestV44SpectatorAndSeatPick(t *testing.T) {
        env := newEnv(t, 0) // ★ inputDelay=0 自动模式
        tokH, _ := env.register(t, "host")
        tok2, _ := env.register(t, "p2")
        tok3, _ := env.register(t, "p3")
        tok4, _ := env.register(t, "p4")
        tok5, _ := env.register(t, "p5")
        tokSpec, _ := env.register(t, "spec1")
        tokSpec2, _ := env.register(t, "spec2")
        addr := env.tcp.Addr().String()

        roomID := env.createRoom(t, tokH)

        // 1. 房主连接 → host / slot 1
        host := dialRoom(t, addr, tokH, roomID, false, 0)
        host.name = "host"
        hello := host.wait("hello host", func(m relayMsg) bool { return m.Type == "hello" })
        if hello.Msg != "host" || hello.Slot != 1 {
                t.Fatalf("host hello = %+v", hello)
        }

        // 2. ★ 指定座位：p2 要 3P → slot 3；p3 要 2P → slot 2；p4 自动 → 剩余 4
        p2 := dialRoom(t, addr, tok2, roomID, false, 3)
        p2.name = "p2"
        if m := p2.wait("hello want=3", func(m relayMsg) bool { return m.Type == "hello" }); m.Slot != 3 {
                t.Fatalf("p2 want 3P but got slot=%d", m.Slot)
        }
        p3 := dialRoom(t, addr, tok3, roomID, false, 2)
        p3.name = "p3"
        if m := p3.wait("hello want=2", func(m relayMsg) bool { return m.Type == "hello" }); m.Slot != 2 {
                t.Fatalf("p3 want 2P but got slot=%d", m.Slot)
        }
        p4 := dialRoom(t, addr, tok4, roomID, false, 0)
        p4.name = "p4"
        if m := p4.wait("hello auto", func(m relayMsg) bool { return m.Type == "hello" }); m.Slot != 4 {
                t.Fatalf("p4 auto should get 4P but slot=%d", m.Slot)
        }

        // 3. ★ 观战者加入满员桌（4 玩家已满）→ spectator / slot 0 / players=[1 2 3 4]
        spec := dialRoom(t, addr, tokSpec, roomID, true, 0)
        spec.name = "spec"
        sm := spec.wait("hello spectator", func(m relayMsg) bool { return m.Type == "hello" })
        if sm.Msg != "spectator" || sm.Slot != 0 {
                t.Fatalf("spectator hello = %+v", sm)
        }
        if len(sm.Players) != 4 {
                t.Fatalf("spectator players = %v", sm.Players)
        }
        // 其他成员收到 spec=true 的 peer_joined
        pm := host.wait("spec joined", func(m relayMsg) bool { return m.Type == "peer_joined" && m.Spec })
        if pm.Msg != "spec1" {
                t.Fatalf("spec joined msg = %+v", pm)
        }

        // 4. 第 5 个玩家想入座 → 房间已满
        p5reject := dialRoom(t, addr, tok5, roomID, false, 0)
        p5reject.name = "p5reject"
        em := p5reject.wait("full error", func(m relayMsg) bool { return m.Type == "error" })
        if !strings.Contains(em.Msg, "满") {
                t.Fatalf("expected full error, got %+v", em)
        }

        // 5. HTTP join（玩家模式）→ 409 + spectateOnly
        req, _ := http.NewRequest("POST", env.srv.URL+"/api/rooms/"+roomID+"/join", nil)
        req.Header.Set("Authorization", "Bearer "+tok5)
        resp, err := http.DefaultClient.Do(req)
        if err != nil {
                t.Fatal(err)
        }
        defer resp.Body.Close()
        if resp.StatusCode != http.StatusConflict {
                t.Fatalf("join full room = %d", resp.StatusCode)
        }
        var conflict map[string]any
        _ = json.NewDecoder(resp.Body).Decode(&conflict)
        if conflict["spectateOnly"] != true {
                t.Fatalf("conflict body = %v", conflict)
        }
        // HTTP join（观战模式）→ 200
        req2, _ := http.NewRequest("POST", env.srv.URL+"/api/rooms/"+roomID+"/join", strings.NewReader(`{"spectate":true}`))
        req2.Header.Set("Authorization", "Bearer "+tokSpec2)
        req2.Header.Set("Content-Type", "application/json")
        resp2, err := http.DefaultClient.Do(req2)
        if err != nil {
                t.Fatal(err)
        }
        defer resp2.Body.Close()
        if resp2.StatusCode != 200 {
                t.Fatalf("spectate join = %d", resp2.StatusCode)
        }
        // 房间列表带观战人数 ≥1
        lr, err := http.Get(env.srv.URL + "/api/rooms")
        if err != nil {
                t.Fatal(err)
        }
        defer lr.Body.Close()
        var list struct {
                Rooms []struct {
                        ID         string `json:"id"`
                        Players    int    `json:"players"`
                        Spectators int    `json:"spectators"`
                        Slots      []int  `json:"slots"`
                } `json:"rooms"`
        }
        _ = json.NewDecoder(lr.Body).Decode(&list)
        found := false
        for _, r := range list.Rooms {
                if r.ID == roomID {
                        found = true
                        if r.Players != 4 || r.Spectators < 1 {
                                t.Fatalf("room players=%d spectators=%d", r.Players, r.Spectators)
                        }
                        if len(r.Slots) != 4 {
                                t.Fatalf("slots = %v", r.Slots)
                        }
                }
        }
        if !found {
                t.Fatal("room not listed")
        }

        // 6. ★ 全员就绪 → 同时广播 start（自动模式 inputDelay=0，建议 2 → pad=2）
        //    覆盖成员含观战者。房主单开（B 路径）在 TestV44HostSoloStart 单测。
        for _, c := range []*testClient{host, p2, p3, p4} {
                c.send(map[string]any{"type": "ready", "pad": 2})
        }
        for _, c := range []*testClient{host, p2, p3, p4, spec} {
                st := c.wait("start broadcast", func(m relayMsg) bool { return m.Type == "start" })
                if st.Pad != 2 {
                        t.Fatalf("[%s] start pad=%d (want 2, auto mode)", c.name, st.Pad)
                }
                if len(st.Players) != 4 {
                        t.Fatalf("[%s] start players=%v", c.name, st.Players)
                }
        }

        // 7. 输入广播覆盖观战者；观战者不发输入（发了也被忽略）
        host.send(inputMsg(100, 0x0F, 1))
        for _, c := range []*testClient{p2, p3, p4, spec} {
                im := c.wait("input frame100 slot1", func(m relayMsg) bool {
                        return m.Type == "input" && m.Frame == 100 && m.Slot == 1
                })
                if im.Pad != 0x0F {
                        t.Fatalf("[%s] input pad=%d", c.name, im.Pad)
                }
        }
        spec.send(inputMsg(101, 0xFF, 2)) // 观战者发 input：服务器应忽略
        for _, c := range []*testClient{host, p2, p3} {
                c.expectNone("spectator input must be ignored", func(m relayMsg) bool {
                        return m.Type == "input" && m.Frame == 101
                })
        }

        // 8. ★ 观战者 ready（对局中）→ 触发存档同步编排（pause 全员+观战者 → state_req 房主）
        spec2 := dialRoom(t, addr, tokSpec2, roomID, true, 0)
        spec2.name = "spec2"
        spec2.wait("spec2 hello", func(m relayMsg) bool { return m.Type == "hello" })
        spec2.send(map[string]any{"type": "ready", "pad": 4})
        // pause 广播给全员 + 观战者
        for _, c := range []*testClient{host, p2, p3, p4, spec, spec2} {
                c.wait("pause broadcast", func(m relayMsg) bool { return m.Type == "pause" })
        }
        // state_req 只给房主
        sreq := host.wait("state_req", func(m relayMsg) bool { return m.Type == "state_req" })
        _ = sreq
        for _, c := range []*testClient{p2, p3, p4, spec, spec2} {
                c.expectNone("state_req only to host", func(m relayMsg) bool { return m.Type == "state_req" })
        }
        // 房主发存档 → 其他成员 + 观战者都收到
        host.send(map[string]any{
                "type": "state", "frame": 500, "data": "aGVsbG8=",
                "hist": map[string][]int{"1": {1, 2}, "2": {3, 4}}, "histEnd": 500,
        })
        for _, c := range []*testClient{p2, p3, p4, spec, spec2} {
                st := c.wait("state broadcast", func(m relayMsg) bool { return m.Type == "state" && m.Frame == 500 })
                if st.Data != "aGVsbG8=" || st.HistEnd != 500 {
                        t.Fatalf("[%s] state = %+v", c.name, st)
                }
        }
        // 新观战者 state_ok → 全员 resume（带存档）
        spec2.send(map[string]any{"type": "state_ok"})
        for _, c := range []*testClient{host, p2, p3, p4, spec, spec2} {
                rm := c.wait("resume broadcast", func(m relayMsg) bool { return m.Type == "resume" })
                if rm.Frame != 500 || rm.Data != "aGVsbG8=" {
                        t.Fatalf("[%s] resume = %+v", c.name, rm)
                }
        }

        // 9. ★ 观战者离开 → spec=true 的 peer_left；玩家离开 → 普通 peer_left
        _ = spec2.conn.Close()
        lm := spec.wait("spec2 left", func(m relayMsg) bool { return m.Type == "peer_left" && m.Spec && m.Msg == "spec2" })
        _ = lm
        host.wait("host sees spec2 left", func(m relayMsg) bool { return m.Type == "peer_left" && m.Spec && m.Msg == "spec2" })

        _ = p4.conn.Close()
        host.wait("p4 left", func(m relayMsg) bool {
                return m.Type == "peer_left" && !m.Spec && m.Slot == 4
        })
        // p4 离开后座位空出：p5 可入座（want=4）
        p5 := dialRoom(t, addr, tok5, roomID, false, 4)
        p5.name = "p5"
        if m := p5.wait("p5 takes freed slot 4", func(m relayMsg) bool { return m.Type == "hello" }); m.Slot != 4 {
                t.Fatalf("p5 want 4P but slot=%d", m.Slot)
        }

        // 10. 玩家全走 → 房间删除 + 观战者收到"对战已结束"
        for _, c := range []*testClient{host, p2, p3, p5} {
                _ = c.conn.Close()
        }
        endMsg := spec.wait("players all left → error to spec", func(m relayMsg) bool {
                return m.Type == "error" && strings.Contains(m.Msg, "结束")
        })
        _ = endMsg
        time.Sleep(200 * time.Millisecond)
        env.hub.mu.Lock()
        _, exists := env.hub.rooms[roomID]
        env.hub.mu.Unlock()
        if exists {
                t.Fatal("room should be deleted after all players left")
        }
}

// TestV44HostSoloStart 房主单开（B 路径）：房内只有房主（+观战者）时，
// 房主 ready → 立即 start 广播给全员含观战者；★观战者的 ready 不得误触发。
func TestV44HostSoloStart(t *testing.T) {
        env := newEnv(t, 0)
        tokH, _ := env.register(t, "soloHost")
        tokSpec, _ := env.register(t, "soloSpec")
        addr := env.tcp.Addr().String()
        roomID := env.createRoom(t, tokH)

        host := dialRoom(t, addr, tokH, roomID, false, 0)
        host.name = "soloHost"
        host.wait("hello", func(m relayMsg) bool { return m.Type == "hello" })
        spec := dialRoom(t, addr, tokSpec, roomID, true, 0)
        spec.name = "soloSpec"
        if m := spec.wait("spec hello", func(m relayMsg) bool { return m.Type == "hello" }); m.Msg != "spectator" {
                t.Fatalf("spec hello = %+v", m)
        }

        // ★ 观战者先 ready —— 绝不能触发 start（v4.3 的 count==1 会被误触发，v4.4 已修）
        spec.send(map[string]any{"type": "ready", "pad": 4})
        host.expectNone("spectator ready must NOT trigger start", func(m relayMsg) bool { return m.Type == "start" })
        spec.expectNone("spectator ready must NOT trigger start (self)", func(m relayMsg) bool { return m.Type == "start" })

        // 房主 ready → count==1 && isOwner → 立即 start（广播含观战者）
        host.send(map[string]any{"type": "ready", "pad": 2})
        for _, c := range []*testClient{host, spec} {
                st := c.wait("host solo start", func(m relayMsg) bool { return m.Type == "start" })
                if st.Pad != 2 {
                        t.Fatalf("[%s] start pad=%d (want 2)", c.name, st.Pad)
                }
                if len(st.Players) != 1 {
                        t.Fatalf("[%s] start players=%v (want [1])", c.name, st.Players)
                }
        }
}

func TestV44DelayModes(t *testing.T) {
        // inputDelay 语义：<0/缺省 → 4；0 → 自动（客户端建议最大值）；>=1 → 下限
        env := newEnv(t, 0)
        tokH, _ := env.register(t, "dh")
        tok2, _ := env.register(t, "dp")
        addr := env.tcp.Addr().String()
        roomID := env.createRoom(t, tokH)
        h := dialRoom(t, addr, tokH, roomID, false, 0)
        h.name = "dh"
        h.wait("hello", func(m relayMsg) bool { return m.Type == "hello" })
        p := dialRoom(t, addr, tok2, roomID, false, 0)
        p.name = "dp"
        p.wait("hello", func(m relayMsg) bool { return m.Type == "hello" })

        // 自动模式：建议 2+2 → start pad=2
        h.send(map[string]any{"type": "ready", "pad": 2})
        p.send(map[string]any{"type": "ready", "pad": 2})
        st := h.wait("start", func(m relayMsg) bool { return m.Type == "start" })
        if st.Pad != 2 {
                t.Fatalf("auto mode pad=%d, want 2", st.Pad)
        }

        // 下限模式：inputDelay=5（热替换）→ 新对局 >= 5
        env.cfg.store(&Config{
                HTTPAddr: "127.0.0.1:0", TCPAddr: "127.0.0.1:0", JWTSecret: "test-secret",
                DataDir: env.cfg.get().DataDir, InputDelay: 5, MaxRooms: 10,
                Games: []Game{{ID: "kof97", Title: "拳皇97", Platform: "arcade", ROMURL: "http://x/k.zip"}},
        })
        if d := env.hub.finalDelay(2); d != 5 {
                t.Fatalf("floor mode finalDelay(2)=%d, want 5", d)
        }
        if d := env.hub.finalDelay(7); d != 7 {
                t.Fatalf("floor mode finalDelay(7)=%d, want 7", d)
        }
        // 自动模式回切
        env.cfg.store(&Config{
                HTTPAddr: "127.0.0.1:0", TCPAddr: "127.0.0.1:0", JWTSecret: "test-secret",
                DataDir: env.cfg.get().DataDir, InputDelay: 0, MaxRooms: 10,
                Games: []Game{{ID: "kof97", Title: "拳皇97", Platform: "arcade", ROMURL: "http://x/k.zip"}},
        })
        if d := env.hub.finalDelay(2); d != 2 {
                t.Fatalf("auto mode finalDelay(2)=%d, want 2", d)
        }
        if d := env.hub.finalDelay(); d != 4 {
                t.Fatalf("auto mode no-suggestion finalDelay()=%d, want 4", d)
        }
        // 传统模式（负数）
        env.cfg.store(&Config{
                HTTPAddr: "127.0.0.1:0", TCPAddr: "127.0.0.1:0", JWTSecret: "test-secret",
                DataDir: env.cfg.get().DataDir, InputDelay: -1, MaxRooms: 10,
                Games: []Game{{ID: "kof97", Title: "拳皇97", Platform: "arcade", ROMURL: "http://x/k.zip"}},
        })
        if d := env.hub.finalDelay(); d != 4 {
                t.Fatalf("legacy mode finalDelay()=%d, want 4", d)
        }
}

// ===========================================================================
// ★★ v4.5 测试：桌号固定 + 服务器侧存档同步（看门狗 / 缓存接入 / state_fail）
// ===========================================================================

// createRoomAtTable 带桌号创建房间，返回 (状态码, 房间 ID, 桌号)。
func (e *v44Env) createRoomAtTable(t *testing.T, token, gameId string, table int) (int, string, int) {
        t.Helper()
        body := fmt.Sprintf(`{"gameId":%q,"table":%d}`, gameId, table)
        req, _ := http.NewRequest("POST", e.srv.URL+"/api/rooms", strings.NewReader(body))
        req.Header.Set("Authorization", "Bearer "+token)
        req.Header.Set("Content-Type", "application/json")
        resp, err := http.DefaultClient.Do(req)
        if err != nil {
                t.Fatal(err)
        }
        defer resp.Body.Close()
        var out struct {
                Room struct {
                        ID    string `json:"id"`
                        Table int    `json:"table"`
                } `json:"room"`
        }
        _ = json.NewDecoder(resp.Body).Decode(&out)
        return resp.StatusCode, out.Room.ID, out.Room.Table
}

// TestV45TableIdentity 桌号固定：开哪桌就是哪桌；同桌唯一；跨游戏不冲突；僵尸桌让位。
func TestV45TableIdentity(t *testing.T) {
        env := newEnv(t, 0)
        tokA, _ := env.register(t, "tableA")
        tokB, _ := env.register(t, "tableB")
        // 第二个游戏（跨游戏同桌号不冲突）
        env.cfg.store(&Config{
                HTTPAddr: "127.0.0.1:0", TCPAddr: "127.0.0.1:0", JWTSecret: "test-secret",
                DataDir: env.cfg.get().DataDir, InputDelay: 0, MaxRooms: 10,
                Games: []Game{
                        {ID: "kof97", Title: "拳皇97", Platform: "arcade", ROMURL: "http://x/k.zip"},
                        {ID: "sfa3", Title: "街霸Zero3", Platform: "arcade", ROMURL: "http://x/s.zip"},
                },
        })

        // 1. 开 2 桌 → 房间带桌号 2
        code, room2, table := env.createRoomAtTable(t, tokA, "kof97", 2)
        if code != 200 || room2 == "" || table != 2 {
                t.Fatalf("create table2: code=%d room=%s table=%d", code, room2, table)
        }
        // 2. 再开 3 桌 → 桌号 3
        code, room3, table := env.createRoomAtTable(t, tokA, "kof97", 3)
        if code != 200 || room3 == "" || table != 3 {
                t.Fatalf("create table3: code=%d room=%s table=%d", code, room3, table)
        }
        // 3. 同游戏再开 2 桌 → 409（该桌已开局）
        code, _, _ = env.createRoomAtTable(t, tokB, "kof97", 2)
        if code != http.StatusConflict {
                t.Fatalf("same game same table should 409, got %d", code)
        }
        // 4. 不同游戏开 2 桌 → OK（桌号按游戏隔离）
        code, roomS2, table := env.createRoomAtTable(t, tokB, "sfa3", 2)
        if code != 200 || table != 2 {
                t.Fatalf("other game same table should be ok, got %d", code)
        }
        // 5. 房间列表带桌号
        lr, err := http.Get(env.srv.URL + "/api/rooms")
        if err != nil {
                t.Fatal(err)
        }
        defer lr.Body.Close()
        var list struct {
                Rooms []struct {
                        ID    string `json:"id"`
                        Table int    `json:"table"`
                } `json:"rooms"`
        }
        _ = json.NewDecoder(lr.Body).Decode(&list)
        found2, found3 := false, false
        for _, r := range list.Rooms {
                if r.ID == room2 && r.Table == 2 {
                        found2 = true
                }
                if r.ID == room3 && r.Table == 3 {
                        found3 = true
                }
        }
        if !found2 || !found3 {
                t.Fatalf("room list tables: room2@2=%v room3@3=%v", found2, found3)
        }
        _ = roomS2

        // 6. 僵尸桌让位：把 3 桌房间改成 2 分钟前创建（一直无人连）→ 同桌重开成功
        env.hub.mu.Lock()
        if rm := env.hub.rooms[room3]; rm != nil {
                rm.CreatedAt = time.Now().Add(-3 * time.Minute)
        }
        env.hub.mu.Unlock()
        code, room3b, table := env.createRoomAtTable(t, tokB, "kof97", 3)
        if code != 200 || room3b == "" || table != 3 || room3b == room3 {
                t.Fatalf("stale table takeover failed: code=%d room=%s table=%d", code, room3b, table)
        }
}

// playSolo 房主单开到 playing（B 路径）。
func playSolo(t *testing.T, addr, token, roomID string) *testClient {
        t.Helper()
        host := dialRoom(t, addr, token, roomID, false, 0)
        host.name = "host"
        host.wait("hello", func(m relayMsg) bool { return m.Type == "hello" })
        host.send(map[string]any{"type": "ready", "pad": 2})
        host.wait("start", func(m relayMsg) bool { return m.Type == "start" })
        return host
}

// TestV45SyncWatchdog 看门狗：房主死活不出档 → 超时后接入者被劝退、原玩家裸恢复，
// 绝不卡在"同步中"。
func TestV45SyncWatchdog(t *testing.T) {
        env := newEnv(t, 0)
        env.hub.watchdogMs = 400 // 测试注入短看门狗
        tokH, _ := env.register(t, "wdHost")
        tokJ, _ := env.register(t, "wdJoiner")
        addr := env.tcp.Addr().String()
        roomID := env.createRoom(t, tokH)

        host := playSolo(t, addr, tokH, roomID)

        // 接入者 ready → 触发同步（pause + state_req），但房主故意不应答
        joiner := dialRoom(t, addr, tokJ, roomID, false, 0)
        joiner.name = "wdJoiner"
        joiner.wait("hello", func(m relayMsg) bool { return m.Type == "hello" })
        joiner.send(map[string]any{"type": "ready", "pad": 2})
        host.wait("pause", func(m relayMsg) bool { return m.Type == "pause" })
        host.wait("state_req", func(m relayMsg) bool { return m.Type == "state_req" })

        // 看门狗到期：接入者收 error（连接被请出），原玩家收无档 resume
        em := joiner.wait("watchdog abort error", func(m relayMsg) bool { return m.Type == "error" })
        if !strings.Contains(em.Msg, "同步失败") {
                t.Fatalf("joiner error = %+v", em)
        }
        // 接入者被请出（房主先收 peer_left，再收 bare resume）
        host.wait("joiner left", func(m relayMsg) bool { return m.Type == "peer_left" && m.Msg == "wdJoiner" })
        rm := host.wait("bare resume", func(m relayMsg) bool { return m.Type == "resume" })
        if rm.Data != "" {
                t.Fatalf("resume should have no data (no cache), got %+v", rm)
        }
        // 房间回到 playing，可继续接受输入
        host.send(inputMsg(900, 0x11, 1))
}

// TestV45StateFailWithCache state_fail + 服务器缓存档：房主出档失败但服务器有
// 近期上传的快照 → 全员（含接入者）按缓存档恢复 —— 联机平台式服务器同步。
func TestV45StateFailWithCache(t *testing.T) {
        env := newEnv(t, 0)
        tokH, _ := env.register(t, "cfHost")
        tokJ, _ := env.register(t, "cfJoiner")
        addr := env.tcp.Addr().String()
        roomID := env.createRoom(t, tokH)

        host := playSolo(t, addr, tokH, roomID)

        // ★ 房主周期快照上传（state_upload）→ 服务器缓存
        host.send(map[string]any{
                "type": "state_upload", "frame": 1200, "data": "Y2FjaGVkMTIz",
                "hist": map[string][]int{"1": {1, 1}}, "histEnd": 1200,
        })
        time.Sleep(100 * time.Millisecond) // 等服务器落地缓存

        // 接入者 ready → 同步开始；房主回 state_fail（出档失败）
        joiner := dialRoom(t, addr, tokJ, roomID, false, 0)
        joiner.name = "cfJoiner"
        joiner.wait("hello", func(m relayMsg) bool { return m.Type == "hello" })
        joiner.send(map[string]any{"type": "ready", "pad": 2})
        host.wait("state_req", func(m relayMsg) bool { return m.Type == "state_req" })
        host.send(map[string]any{"type": "state_fail"})

        // 全员按缓存档恢复（含接入者 —— 随 resume 档一起接入）
        for _, c := range []*testClient{host, joiner} {
                rm := c.wait("cached resume", func(m relayMsg) bool { return m.Type == "resume" })
                if rm.Frame != 1200 || rm.Data != "Y2FjaGVkMTIz" {
                        t.Fatalf("[%s] resume = %+v (want cached frame 1200)", c.name, rm)
                }
        }
}

// TestV45OwnerlessCachedJoin 房主掉线后接入：服务器缓存档直接同步
//（2P/3P/4P 加入时从服务器拿档 —— 不依赖房主在场上出档）。
func TestV45OwnerlessCachedJoin(t *testing.T) {
        env := newEnv(t, 0)
        tokH, _ := env.register(t, "olHost")
        tokP2, _ := env.register(t, "olP2")
        tokJ, _ := env.register(t, "olJoiner")
        addr := env.tcp.Addr().String()
        roomID := env.createRoom(t, tokH)

        // 房主 + 2P 同场开局（2 人全员 ready → start）
        host := dialRoom(t, addr, tokH, roomID, false, 0)
        host.name = "olHost"
        host.wait("hello", func(m relayMsg) bool { return m.Type == "hello" })
        p2 := dialRoom(t, addr, tokP2, roomID, false, 0)
        p2.name = "olP2"
        p2.wait("hello", func(m relayMsg) bool { return m.Type == "hello" })
        host.send(map[string]any{"type": "ready", "pad": 2})
        p2.send(map[string]any{"type": "ready", "pad": 2})
        for _, c := range []*testClient{host, p2} {
                c.wait("start", func(m relayMsg) bool { return m.Type == "start" })
        }

        // 房主上传快照后掉线（2P 还在 —— 房间继续）
        host.send(map[string]any{
                "type": "state_upload", "frame": 800, "data": "b3duZXJsZXNz",
                "hist": map[string][]int{"1": {2, 2}}, "histEnd": 800,
        })
        time.Sleep(100 * time.Millisecond)
        _ = host.conn.Close()
        p2.wait("host left", func(m relayMsg) bool { return m.Type == "peer_left" && m.Msg == "olHost" })

        // 接入者不会被拒（服务器有新鲜缓存档）
        joiner := dialRoom(t, addr, tokJ, roomID, false, 0)
        joiner.name = "olJoiner"
        joiner.wait("hello", func(m relayMsg) bool { return m.Type == "hello" })
        joiner.send(map[string]any{"type": "ready", "pad": 2})

        // 服务器直接用缓存档编排：pause → state(缓存) → state_ok → resume(带档)
        for _, c := range []*testClient{p2, joiner} {
                c.wait("pause", func(m relayMsg) bool { return m.Type == "pause" })
        }
        sm := joiner.wait("cached state", func(m relayMsg) bool { return m.Type == "state" })
        if sm.Frame != 800 || sm.Data != "b3duZXJsZXNz" {
                t.Fatalf("state = %+v", sm)
        }
        joiner.send(map[string]any{"type": "state_ok"})
        for _, c := range []*testClient{p2, joiner} {
                rm := c.wait("resume", func(m relayMsg) bool { return m.Type == "resume" })
                if rm.Frame != 800 || rm.Data != "b3duZXJsZXNz" {
                        t.Fatalf("[%s] resume = %+v", c.name, rm)
                }
        }
}

// TestV45OwnerlessNoCacheReject 房主掉线且无缓存档：明确拒绝接入（不再发无档 resume
// 让接入者掉进"各玩各的"鬼影对局）。
func TestV45OwnerlessNoCacheReject(t *testing.T) {
        env := newEnv(t, 0)
        tokH, _ := env.register(t, "ncHost")
        tokP2, _ := env.register(t, "ncP2")
        tokJ, _ := env.register(t, "ncJoiner")
        addr := env.tcp.Addr().String()
        roomID := env.createRoom(t, tokH)

        host := dialRoom(t, addr, tokH, roomID, false, 0)
        host.name = "ncHost"
        host.wait("hello", func(m relayMsg) bool { return m.Type == "hello" })
        p2 := dialRoom(t, addr, tokP2, roomID, false, 0)
        p2.name = "ncP2"
        p2.wait("hello", func(m relayMsg) bool { return m.Type == "hello" })
        host.send(map[string]any{"type": "ready", "pad": 2})
        p2.send(map[string]any{"type": "ready", "pad": 2})
        for _, c := range []*testClient{host, p2} {
                c.wait("start", func(m relayMsg) bool { return m.Type == "start" })
        }

        _ = host.conn.Close()
        p2.wait("host left", func(m relayMsg) bool { return m.Type == "peer_left" && m.Msg == "ncHost" })

        joiner := dialRoom(t, addr, tokJ, roomID, false, 0)
        joiner.name = "ncJoiner"
        em := joiner.wait("reject at hello", func(m relayMsg) bool { return m.Type == "error" })
        if !strings.Contains(em.Msg, "房主已离开") {
                t.Fatalf("expected ownerless reject, got %+v", em)
        }
        // 2P 不受影响（没有 pause/resume 打扰）
        p2.expectNone("no pause for surviving player", func(m relayMsg) bool { return m.Type == "pause" })
}
