package main

// ============================================================================
// ★ 本地验证桩 —— 仅用于本沙箱编译/测试 relay.go + relay_v53_test.go。
// ★ 用户的完整仓库里有这些类型/入口的真实实现（main.go / config.go / jwt.go /
//   users.go / 早期版本测试文件的 testEnv/testClient/inputMsg）。
// ★ 此文件【绝不】进入 v54 交付包！
// ============================================================================

import (
        "bufio"
        "encoding/json"
        "fmt"
        "net"
        "strings"
        "testing"
        "time"
)

type configData struct {
        InputDelay int
}
type configStore struct{ d configData }

func (c *configStore) get() configData { return c.d }

type jwtClaims struct{ Sub string }
type jwt struct{ sub string }

func (j *jwt) verify(token string) (jwtClaims, error) { return jwtClaims{Sub: token}, nil }

type User struct {
        ID       string
        Username string
}
type userStore struct{ m map[string]*User }

func (u *userStore) byID(id string) *User {
        if u.m == nil {
                u.m = map[string]*User{}
        }
        if x, ok := u.m[id]; ok {
                return x
        }
        x := &User{ID: id, Username: id}
        u.m[id] = x
        return x
}

// ---------- 测试环境（仿用户仓库早期测试文件的 testEnv） ----------

type testEnv struct {
        t   *testing.T
        tcp net.Listener
        hub *relayHub
}

func newEnv(t *testing.T, watchdogMs int) *testEnv {
        hub := newRelayHub(&configStore{d: configData{InputDelay: 0}}, &jwt{}, &userStore{})
        hub.watchdogMs = watchdogMs
        ln, err := net.Listen("tcp", "127.0.0.1:0")
        if err != nil {
                t.Fatalf("listen: %v", err)
        }
        env := &testEnv{t: t, tcp: ln, hub: hub}
        go func() {
                for {
                        c, err := ln.Accept()
                        if err != nil {
                                return
                        }
                        go hub.handleConn(c)
                }
        }()
        t.Cleanup(func() { ln.Close() })
        return env
}

func (e *testEnv) register(t *testing.T, name string) (string, string) {
        return name, "pw-" + name
}

func (e *testEnv) createRoom(t *testing.T, token string) string {
        id := "room-" + fmt.Sprint(time.Now().UnixNano())
        e.hub.mu.Lock()
        e.hub.rooms[id] = &Room{
                ID: id, OwnerID: token, Status: roomWaiting, MaxPlayers: 4,
                conns: map[string]*relayConn{}, specs: map[string]*relayConn{},
        }
        e.hub.mu.Unlock()
        return id
}

// ---------- 测试客户端（仿用户仓库 testClient） ----------

type testClient struct {
        t      *testing.T
        conn   net.Conn
        rd     *bufio.Reader
        msgs   chan relayMsg
        closed chan struct{}
        name   string
}

func (c *testClient) send(m map[string]any) {
        b, _ := json.Marshal(m)
        _, _ = c.conn.Write(append(b, '\n'))
}

// wait 等待第一条满足 pred 的消息（消费掉不匹配的）。
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
                        c.t.Fatalf("[%s] %s: 连接已关闭（未等到期望消息）", c.name, desc)
                case <-deadline:
                        c.t.Fatalf("[%s] %s: 等待超时", c.name, desc)
                }
        }
}

// expectNone 断言 300ms 内不出现满足 pred 的消息。
func (c *testClient) expectNone(desc string, pred func(relayMsg) bool) {
        c.t.Helper()
        deadline := time.After(300 * time.Millisecond)
        for {
                select {
                case m := <-c.msgs:
                        if pred(m) {
                                c.t.Fatalf("[%s] %s: 不应出现的消息到达: %+v", c.name, desc, m)
                        }
                case <-deadline:
                        return
                }
        }
}

func inputMsg(frame int64, pad int, slot int) map[string]any {
        return map[string]any{"type": "input", "frame": frame, "pad": pad, "slot": slot}
}

// ---------- main（真实服务器入口在用户仓库 main.go） ----------

func main() {
        ln, err := net.Listen("tcp", ":18090")
        if err != nil {
                panic(err)
        }
        hub := newRelayHub(&configStore{}, &jwt{}, &userStore{})
        for {
                c, err := ln.Accept()
                if err != nil {
                        return
                }
                go hub.handleConn(c)
        }
        _ = strings.TrimSpace("")
}
