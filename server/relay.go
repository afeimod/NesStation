package main

import (
        "bufio"
        "encoding/json"
        "net"
        "strings"
        "sync"
        "time"
)

// ---------------------------------------------------------------------------
// 房间模型
// ---------------------------------------------------------------------------

// 房间状态常量
const (
        roomWaiting string = "waiting"
        roomReady   string = "ready"
        roomPlaying string = "playing"
)

type Room struct {
        ID        string
        GameID    string
        CreatedAt time.Time
        Status    string // 房间状态：waiting / ready / playing
        // ★ 房主（创建者）用户 ID —— 由 HTTP 创建房间时记录，
        //   中继握手时按它分配 host/guest 角色（旧版按连接顺序分配，
        //   加入者先连上时角色反转，两人都以为自己是 1P）。
        OwnerID string
        // 玩家连接（socket 已建立）。key 为 user id。
        conns map[string]*relayConn
}

func (r *Room) playerCount() int { return len(r.conns) }

// ---------------------------------------------------------------------------
// 中继连接
// ---------------------------------------------------------------------------

// relayMsg 帧同步消息。输入用 JSON 编码（小巧且便于调试）。
// 协议：客户端 -> 服务器 -> 对端。
//   {"type":"input","frame":123,"pad":0x00FF}
//   {"type":"hello",...}  握手
//   {"type":"ready","pad":建议inputDelay}  客户端就绪（引擎已加载完，等 start）
//   {"type":"start","pad":最终inputDelay}  双方就绪后服务器同时广播 → 同步开跑
//   {"type":"ping","ts":纳秒时间戳} / {"type":"pong","ts":回显}  RTT 测量
//   {"type":"bye",...}    结束
type relayMsg struct {
        Type  string `json:"type"`
        Frame int64  `json:"frame"`
        Pad   int    `json:"pad"`
        // ping/pong 时间戳回显（客户端本地时钟，原样返回）
        Ts int64 `json:"ts,omitempty"`
        // 握手字段
        Token  string `json:"token,omitempty"`
        RoomID string `json:"roomId,omitempty"`
        UserID string `json:"userId,omitempty"`
        // 通知字段
        Msg string `json:"msg,omitempty"`
}

type relayConn struct {
        conn net.Conn
        enc  *json.Encoder
        user *User
        room *Room
        // 对端引用（快速访问）
        peer *relayConn
        // sendMu 保护 enc 的并发写入。json.Encoder 不是线程安全的，
        // relayLoop（转发对端的 input）和 handleConn（发 hello / peer_joined）
        // 可能在不同 goroutine 里并发调 send，必须加锁。
        sendMu sync.Mutex
        // ★ 同步启动：该端是否已发过 ready，以及它建议的 inputDelay（帧）。
        ready    bool
        readyPad int
}

func (rc *relayConn) send(m relayMsg) error {
        rc.sendMu.Lock()
        defer rc.sendMu.Unlock()
        rc.conn.SetWriteDeadline(time.Now().Add(10 * time.Second))
        return rc.enc.Encode(m)
}

func (rc *relayConn) close() {
        _ = rc.conn.Close()
}

// ---------------------------------------------------------------------------
// 中继中心
// ---------------------------------------------------------------------------

type relayHub struct {
        cfg   *Config
        jwt   *jwt
        users *userStore

        mu    sync.Mutex
        rooms map[string]*Room // 等待/进行中的房间
}

func newRelayHub(cfg *Config, jwt *jwt, users *userStore) *relayHub {
        return &relayHub{cfg: cfg, jwt: jwt, users: users, rooms: map[string]*Room{}}
}

// roomByID 获取房间
func (h *relayHub) roomByID(id string) *Room {
        h.mu.Lock()
        defer h.mu.Unlock()
        return h.rooms[id]
}

// handleConn 处理一条 TCP 连接：先握手，再进入房间中继
func (h *relayHub) handleConn(conn net.Conn) {
        defer conn.Close()
        conn.SetReadDeadline(time.Now().Add(15 * time.Second))

        // 关闭 Nagle 算法 —— 帧同步 input 每帧一个很小的 JSON 包，
        // Nagle 会把多个小包合并延迟发送，导致对端读不到输入（desync）。
        // 这是导致"2P 收不到 1P 输入"的关键 bug：服务器 TCP 默认开 Nagle，
        // 1P 发的 60fps 小 input 包被 TCP 层合并/延迟，2P readLine 一直阻塞。
        if tcp, ok := conn.(*net.TCPConn); ok {
                _ = tcp.SetNoDelay(true)
        }

        reader := bufio.NewReader(conn)
        // 读第一行：握手消息
        line, err := reader.ReadString('\n')
        if err != nil {
                return
        }
        var hello relayMsg
        if err := json.Unmarshal([]byte(line), &hello); err != nil || hello.Type != "hello" {
                _ = json.NewEncoder(conn).Encode(relayMsg{Type: "error", Msg: "握手消息格式错误"})
                return
        }

        // 校验 token（JWT）
        claims, err := h.jwt.verify(hello.Token)
        if err != nil {
                _ = json.NewEncoder(conn).Encode(relayMsg{Type: "error", Msg: "token 无效: " + err.Error()})
                return
        }

        h.mu.Lock()
        room, ok := h.rooms[hello.RoomID]
        if !ok {
                h.mu.Unlock()
                _ = json.NewEncoder(conn).Encode(relayMsg{Type: "error", Msg: "房间不存在"})
                return
        }
        if room.playerCount() >= 2 {
                h.mu.Unlock()
                _ = json.NewEncoder(conn).Encode(relayMsg{Type: "error", Msg: "房间已满"})
                return
        }
        if _, exists := room.conns[claims.Sub]; exists {
                h.mu.Unlock()
                _ = json.NewEncoder(conn).Encode(relayMsg{Type: "error", Msg: "已在房间中"})
                return
        }

        rc := &relayConn{
                conn: conn,
                enc:  json.NewEncoder(conn),
                user: h.users.byID(claims.Sub),
                room: room,
        }
        room.conns[claims.Sub] = rc

        // === 关键修复：在锁内设置 peer 引用 ===
        // 之前 other.peer = rc 和 rc.peer = other 在 peerOf 的锁外执行，
        // 导致 1P 的 relayLoop 可能读不到 rc_1p.peer 的更新（Go 内存模型
        // 跨 goroutine 需要 sync）。现在在 h.mu 锁内设置 peer，保证
        // relayLoop 下次读 rc.peer 时一定能看到。
        var other *relayConn
        for id, c := range room.conns {
                if id != claims.Sub {
                        other = c
                        break
                }
        }
        if other != nil {
                other.peer = rc
                rc.peer = other
        }
        h.mu.Unlock()

        conn.SetReadDeadline(time.Time{}) // 清除超时

        // 向本端发送 hello 回执（附带输入延迟帧数，客户端据此配置同步参数）
        // ★ 角色按房主身份分配（Room.OwnerID 由 HTTP 创建房间时记录），
        //   不再按连接顺序 —— 否则加入者网络快先连上时会被当成 host，
        //   双方 pad1/pad2 映射颠倒，各自控制对方角色。
        //   拿不到 OwnerID（旧客户端建的房间）时退回连接顺序，兼容旧数据。
        var other2 *relayConn
        for id, c := range room.conns {
                if id != claims.Sub {
                        other2 = c
                        break
                }
        }
        role := "guest"
        if room.OwnerID != "" {
                if claims.Sub == room.OwnerID {
                        role = "host"
                }
        } else if other2 == nil {
                // 无房主记录：第一个连接的当 host（旧行为兜底）
                role = "host"
        }
        delay := h.cfg.InputDelay
        if delay <= 0 {
                delay = 4
        }
        _ = rc.send(relayMsg{
                Type:  "hello",
                Msg:   role,
                Pad:   delay, // 复用 Pad 字段传递 inputDelay
        })

        // 通知对方（peer_joined）—— 告诉 1P 有新玩家加入。
        if other != nil {
                _ = other.send(relayMsg{Type: "peer_joined", Msg: rc.user.Username})
                // ★ 补发通知：若对方在我加入前就已发过 ready（它加载快、先就绪），
                //   它的 peer_ready 通知当时无处可投 —— 给新加入的这端补一条，
                //   让 UI 能正确显示"对手已就绪，等同步开跑"。
                h.mu.Lock()
                otherReady := other.ready
                h.mu.Unlock()
                if otherReady {
                        _ = rc.send(relayMsg{Type: "peer_ready", Msg: other.user.Username})
                }
        }

        // 中继循环：读本端输入，转发给对端
        h.relayLoop(rc, reader)
}

// relayLoop 读取一条输入消息，原样转发给对端
func (h *relayHub) relayLoop(rc *relayConn, reader *bufio.Reader) {
        logf := func(format string, args ...interface{}) {
                // 简单日志 —— 帮助调试转发问题
                // fmt.Fprintf(os.Stderr, "[relay] "+format+"\n", args...)
        }
        _ = logf

        for {
                line, err := reader.ReadString('\n')
                if err != nil {
                        break
                }
                line = strings.TrimSpace(line)
                if line == "" {
                        continue
                }
                var msg relayMsg
                if err := json.Unmarshal([]byte(line), &msg); err != nil {
                        continue
                }

                switch msg.Type {
                case "input":
                        // 转发给对端
                        // 加锁读 peer —— 防止读到 nil 的瞬态（虽然 handleConn
                        // 已经在锁内设了 peer，但保险起见这里也加锁）。
                        h.mu.Lock()
                        peer := rc.peer
                        h.mu.Unlock()
                        if peer != nil && peer.conn != nil {
                                if err := peer.send(relayMsg{Type: "input", Frame: msg.Frame, Pad: msg.Pad}); err != nil {
                                        peer.close()
                                        h.drop(rc)
                                        return
                                }
                        }
                case "ready":
                        // ★★ 同步启动门（修复"对战不同步、各玩各的"的根因）：
                        //   旧协议 2P 收到 hello 就发 ready 给 1P，1P 收到就各自
                        //   启动引擎 —— 双方引擎启动时刻差 = RTT + 双端 ROM 加载
                        //   时差（慢设备可达数秒），帧计数错开几十上百帧，
                        //   inputDelay（4-6 帧 ≈ 100ms）根本吸收不了 → 一方永远
                        //   等不到对方早期帧的输入 → 用"上一帧输入兜底"补帧 →
                        //   两边状态永久发散 = 各玩各的。
                        //   现在：双方各自在【引擎完全加载好、帧循环就位】后才发
                        //   ready；服务器等【两端都 ready】后向双方【同时】广播
                        //   start（最终 inputDelay = max(双方建议, 服务器配置)，
                        //   两端用同一个值 —— 不同值会让两边的执行帧错位）。
                        //   客户端收到 start 才放行帧 0 → 双方帧计数起点差缩到
                        //   一次消息传播差（毫秒级），被 inputDelay 轻松吸收。
                        h.mu.Lock()
                        rc.ready = true
                        if msg.Pad > rc.readyPad {
                                rc.readyPad = msg.Pad
                        }
                        peer := rc.peer
                        bothReady := peer != nil && peer.ready && rc.ready
                        finalDelay := h.cfg.InputDelay
                        if finalDelay <= 0 {
                                finalDelay = 4
                        }
                        if bothReady {
                                if rc.readyPad > finalDelay {
                                        finalDelay = rc.readyPad
                                }
                                if peer.readyPad > finalDelay {
                                        finalDelay = peer.readyPad
                                }
                                if finalDelay > 16 {
                                        finalDelay = 16
                                }
                                if rc.room != nil {
                                        rc.room.Status = roomPlaying
                                }
                        }
                        h.mu.Unlock()

                        // 转发 peer_ready 给对端（UI 状态显示用）
                        if peer != nil && peer.conn != nil {
                                _ = peer.send(relayMsg{Type: "peer_ready", Msg: rc.user.Username})
                        }
                        // 双方就绪 → 同时广播 start（带最终 inputDelay）
                        if bothReady {
                                _ = rc.send(relayMsg{Type: "start", Pad: finalDelay})
                                if peer.send(relayMsg{Type: "start", Pad: finalDelay}) != nil {
                                        // 对端发送失败（已断）→ 收回本端状态
                                        h.drop(rc)
                                        return
                                }
                        }
                case "ping":
                        // RTT 测量：原样回显客户端时间戳
                        _ = rc.send(relayMsg{Type: "pong", Ts: msg.Ts})
                case "bye":
                        // 主动退出
                        h.drop(rc)
                        return
                }
        }
        // 连接断开
        h.drop(rc)
}

// drop 断开连接：通知对端、清理房间
func (h *relayHub) drop(rc *relayConn) {
        h.mu.Lock()
        room := rc.room
        if room != nil {
                if rc.peer != nil {
                        _ = rc.peer.send(relayMsg{Type: "peer_left", Msg: rc.user.Username})
                        rc.peer.peer = nil
                }
                delete(room.conns, rc.user.ID)
                if room.playerCount() == 0 {
                        delete(h.rooms, room.ID)
                }
        }
        h.mu.Unlock()
        rc.close()
}

// peerOf 返回 room 中与 userID 对战的另一端
func (h *relayHub) peerOf(room *Room, userID string) *relayConn {
        h.mu.Lock()
        defer h.mu.Unlock()
        for id, c := range room.conns {
                if id != userID {
                        return c
                }
        }
        return nil
}

// jwtToken / users 由 newRelayHub 注入
