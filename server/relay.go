package main

import (
        "bufio"
        "encoding/json"
        "log"
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
        // ★★ v4.3 后加入（late join）：对局进行中允许新玩家加入。
        roomSyncing string = "syncing" // 正在为新加入玩家做画面(存档)同步
)

// maxRoomPlayers 最大玩家数（1P-4P）。FBNeo/街机支持 4 手柄；
// 其他 2 手柄核心上 3/4P 的输入被客户端忽略（仍可观战同步画面）。
const maxRoomPlayers = 4

// ★★ v4.4 观战系统：观战者不占玩家槽位、只收不发（收全员输入/存档/恢复），
// 满员房间仍可观战。上限防滥用。
const maxSpectators = 16

type Room struct {
        ID        string
        GameID    string
        CreatedAt time.Time
        Status    string // 房间状态：waiting / ready / playing / syncing
        // ★ 房主（创建者）用户 ID —— 由 HTTP 创建房间时记录，
        //   中继握手时按它分配 host 角色（创建者 = 1P = 槽 1）。
        OwnerID string
        // 玩家连接（socket 已建立）。key 为 user id。
        conns map[string]*relayConn
        // ★★ v4.4 观战者连接（不占 1P-4P 槽位）。key 为 user id。
        specs map[string]*relayConn
        // ★ v4.3 后加入：房主最近一次发来的存档（frame/data/hist）。
        // state_ok 时随 resume 一并广播给全员 —— 所有成员装载同一份存档、
        // 以同一帧号恢复，保证 1P/2P/3P/4P 画面与状态完全一致。
        lastState   *relayMsg
        lastStateMu sync.Mutex
}

func (r *Room) playerCount() int { return len(r.conns) }

// spectatorCount 观战者人数（房间列表/满员判定用）。
func (r *Room) spectatorCount() int { return len(r.specs) }

// nextFreeSlot 返回下一个空闲玩家槽位（1-4）。房主占用槽 1，
// 加入者按顺序取 2/3/4；有人离开后槽位可被后来者复用。
// ★★ v4.4：hello 可带 want（期望槽位 2/3/4）—— 空闲则优先满足。
func (r *Room) nextFreeSlot(want int) int {
        used := [maxRoomPlayers + 1]bool{}
        for _, c := range r.conns {
                if c.slot >= 1 && c.slot <= maxRoomPlayers {
                        used[c.slot] = true
                }
        }
        // ★ 指定座位：用户点桌子时选了 2P/3P/4P —— 想坐哪桌坐哪桌
        if want >= 1 && want <= maxRoomPlayers && !used[want] {
                return want
        }
        for s := 1; s <= maxRoomPlayers; s++ {
                if !used[s] {
                        return s
                }
        }
        return 0
}

// ---------------------------------------------------------------------------
// 中继连接
// ---------------------------------------------------------------------------

// relayMsg 帧同步消息。输入用 JSON 编码（小巧且便于调试）。
// 协议：客户端 -> 服务器 -> 其他成员。
//   {"type":"hello","token":"...","roomId":"...",
//    "spectate":true,"want":3}                     握手（第一行）
//     ★★ v4.4：spectate=true 观战加入（不占槽位、只收不发）；
//     want=2/3/4 指定期望座位（空闲则满足，否则取下一空闲位）
//   <- {"type":"hello","msg":"host"|"guest"|"spectator","pad":延迟帧,
//       "slot":1..4,"players":[1,2..],"playing":bool}   角色分配 + 槽位 + 成员
//   -> {"type":"ping","ts":纳秒} / <- {"type":"pong","ts":回显}
//   -> {"type":"ready","pad":建议延迟帧}                    引擎加载完毕
//   <- {"type":"start","pad":最终延迟帧,"players":[..]}      开跑（全员就绪 / 房主单开）
//   -> {"type":"input","frame":N,"pad":0x..,"slot":1..4}    本端输入（带槽位）
//   <- {"type":"input","frame":N,"pad":0x..,"slot":1..4}    其他玩家输入
//   <- {"type":"peer_joined"/"peer_left","msg":"名字","slot":N,
//       "spec":true}                                        ★ v4.4：spec=true 为观战者加入/离开
//   ★★ v4.3 后加入同步（服务器编排，房主出存档）：
//   <- {"type":"pause"}                                     全体暂停在各自下一帧
//   <- {"type":"state_req"}                                 仅房主：暂停后发存档
//   -> {"type":"state","frame":F,"data":"base64","hist":{...}}  房主存档+近16帧输入
//   <- {"type":"state","frame":F,"data":...,"hist":...}     服务器转发给其他成员
//   -> {"type":"state_ok"}                                  新玩家已装载存档
//   <- {"type":"resume","frame":F,"pad":最终延迟,"players":[..],
//       "data":...,"hist":...}                              全员装载后同帧恢复
//   <- {"type":"error","msg":"..."}
type relayMsg struct {
        Type  string `json:"type"`
        Frame int64  `json:"frame"`
        Pad   int    `json:"pad"`
        // ★ v4.3：玩家槽位（1P=1 … 4P=4）。input/peer_joined/peer_left 消息必带。
        Slot int `json:"slot,omitempty"`
        // ping/pong 时间戳回显（客户端本地时钟，原样返回）
        Ts int64 `json:"ts,omitempty"`
        // 握手字段
        Token  string `json:"token,omitempty"`
        RoomID string `json:"roomId,omitempty"`
        UserID string `json:"userId,omitempty"`
        // 通知字段
        Msg string `json:"msg,omitempty"`
        // ★ v4.3 后加入：存档同步
        Data    string       `json:"data,omitempty"`    // base64 即时存档
        Hist    map[int][]int `json:"hist,omitempty"`   // 槽位 -> 近 16 帧 pad（按帧序，含终点帧号）
        HistEnd int64        `json:"histEnd,omitempty"` // hist 覆盖的最后一帧（不含）
        // 成员槽位列表（hello/start/resume 携带）
        Players []int `json:"players,omitempty"`
        // hello 回执：房间是否已在跑（后加入客户端据此选择加载路径）
        Playing bool `json:"playing,omitempty"`
        // ★★ v4.4：hello 请求观战加入（不占 1P-4P 槽位、只收不发）
        Spectate bool `json:"spectate,omitempty"`
        // ★★ v4.4：hello 期望座位（2/3/4；0 = 自动取下一空闲位）
        Want int `json:"want,omitempty"`
        // ★★ v4.4：peer_joined/peer_left 附带 —— true = 观战者（客户端显示“xx 正在观战”）
        Spec bool `json:"spec,omitempty"`
}

type relayConn struct {
        conn net.Conn
        enc  *json.Encoder
        user *User
        room *Room
        // sendMu 保护 enc 的并发写入（多 goroutine 转发）。
        sendMu sync.Mutex
        // 该玩家的槽位（1-4）。房主 = 1。★ v4.4：观战者 = 0。
        slot int
        // ★★ v4.4：观战者连接（不占槽位、不发输入、收全员广播）。
        spectator bool
        // 同步启动：该端是否已发过 ready，以及它建议的 inputDelay（帧）。
        ready    bool
        readyPad int
}

func (rc *relayConn) send(m relayMsg) error {
        rc.sendMu.Lock()
        defer rc.sendMu.Unlock()
        rc.conn.SetWriteDeadline(time.Now().Add(30 * time.Second))
        return rc.enc.Encode(m)
}

func (rc *relayConn) close() {
        _ = rc.conn.Close()
}

// ---------------------------------------------------------------------------
// 中继中心
// ---------------------------------------------------------------------------

type relayHub struct {
        // cfg 当前配置（热重载落点）：经 get() 取最新快照。
        // inputDelay 等参数变更对后续 start/resume 计算生效，
        // 进行中房间的既有状态不受影响。
        cfg   *configStore
        jwt   *jwt
        users *userStore

        mu    sync.Mutex
        rooms map[string]*Room
}

func newRelayHub(cfg *configStore, jwt *jwt, users *userStore) *relayHub {
        return &relayHub{cfg: cfg, jwt: jwt, users: users, rooms: map[string]*Room{}}
}

// roomByID 获取房间
func (h *relayHub) roomByID(id string) *Room {
        h.mu.Lock()
        defer h.mu.Unlock()
        return h.rooms[id]
}

// finalDelay 计算最终输入延迟。
// ★★ v4.4 语义升级（配合客户端回滚网络码）：
//   inputDelay < 0 / 未配置 → 传统行为：兜底 4 帧；
//   inputDelay == 0        → 自动模式：完全采纳客户端建议中的最大值
//                             （回滚客户端建议 2，旧客户端建议 RTT 推算值）；
//   inputDelay >= 1        → 服务器下限（管理员保证），仍与建议取最大值。
// 混合房间安全：建议取 max —— 旧延迟模式客户端永远拿到 >= 它自己需要的值；
// 回滚模式客户端不依赖服务器值（本地延迟是客户端自己的策略）。
// 热重载说明：inputDelay 就地热替换，此处在每次计算时读最新值。
func (h *relayHub) finalDelay(suggestions ...int) int {
        d := h.cfg.get().InputDelay
        if d < 0 {
                d = 4
        }
        best := 0
        for _, s := range suggestions {
                if s > best {
                        best = s
                }
        }
        if best > d {
                d = best
        }
        if d <= 0 {
                d = 4 // 自动模式下无人给建议时兜底
        }
        if d > 16 {
                d = 16
        }
        return d
}

// activeSlots 返回房间当前活跃槽位列表（升序，保证各端一致）。
func activeSlots(room *Room) []int {
        slots := make([]int, 0, maxRoomPlayers)
        for _, c := range room.conns {
                slots = append(slots, c.slot)
        }
        for i := 0; i < len(slots); i++ {
                for j := i + 1; j < len(slots); j++ {
                        if slots[j] < slots[i] {
                                slots[i], slots[j] = slots[j], slots[i]
                        }
                }
        }
        return slots
}

// ownerConn 返回房主的连接（可能为 nil）。
func ownerConn(room *Room) *relayConn {
        if room.OwnerID == "" {
                return nil
        }
        return room.conns[room.OwnerID]
}

// handleConn 处理一条 TCP 连接：先握手，再进入房间中继
func (h *relayHub) handleConn(conn net.Conn) {
        defer conn.Close()
        conn.SetReadDeadline(time.Now().Add(15 * time.Second))

        // 关闭 Nagle 算法 —— 帧同步 input 每帧一个很小的 JSON 包，
        // Nagle 会把多个小包合并延迟发送，导致对端读不到输入（desync）。
        if tcp, ok := conn.(*net.TCPConn); ok {
                _ = tcp.SetNoDelay(true)
        }

        // ★ v4.3：缓冲调大 —— 后加入要经此收发 base64 即时存档（可达数百 KB 一行）。
        reader := bufio.NewReaderSize(conn, 1<<20)
        line, err := reader.ReadString('\n')
        if err != nil {
                return
        }
        var hello relayMsg
        if err := json.Unmarshal([]byte(line), &hello); err != nil || hello.Type != "hello" {
                _ = json.NewEncoder(conn).Encode(relayMsg{Type: "error", Msg: "握手消息格式错误"})
                return
        }

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

        // ★★ v4.4 观战系统：spectate=true → 观战者连接。不占 1P-4P 槽位、
        //   只收不发（收全员 input/pause/state/resume），满员房间仍可加入。
        if hello.Spectate {
                if _, exists := room.conns[claims.Sub]; exists {
                        h.mu.Unlock()
                        _ = json.NewEncoder(conn).Encode(relayMsg{Type: "error", Msg: "你已在对战中，不能同时观战"})
                        return
                }
                if _, exists := room.specs[claims.Sub]; exists {
                        h.mu.Unlock()
                        _ = json.NewEncoder(conn).Encode(relayMsg{Type: "error", Msg: "已在观战中"})
                        return
                }
                if len(room.specs) >= maxSpectators {
                        h.mu.Unlock()
                        _ = json.NewEncoder(conn).Encode(relayMsg{Type: "error", Msg: "观战人数已满"})
                        return
                }
                if room.specs == nil {
                        room.specs = map[string]*relayConn{}
                }
                rc := &relayConn{
                        conn: conn,
                        enc:  json.NewEncoder(conn),
                        user: h.users.byID(claims.Sub),
                        room: room,
                        slot: 0, spectator: true,
                }
                room.specs[claims.Sub] = rc

                playing := room.Status == roomPlaying || room.Status == roomSyncing
                players := activeSlots(room)
                specCount := len(room.specs)
                h.mu.Unlock()

                conn.SetReadDeadline(time.Time{}) // 清除超时

                // hello 回执：角色 = spectator，槽位 0，成员槽位，是否已在跑
                _ = rc.send(relayMsg{
                        Type:    "hello",
                        Msg:     "spectator",
                        Pad:     h.finalDelay(),
                        Slot:    0,
                        Players: players,
                        Playing: playing,
                })

                // 通知其他成员：有观战者加入（spec=true，不占槽位）
                h.broadcastExcept(room, rc, relayMsg{
                        Type: "peer_joined", Msg: rc.user.Username, Slot: 0, Spec: true,
                })
                log.Printf("[观战] %s 进入房间 %s 观战（第 %d 位观战者，玩家 %d 人）",
                        rc.user.Username, room.ID, specCount, len(players))

                h.relayLoop(rc, reader)
                return
        }

        // ---- 玩家加入 ----
        // ★ v4.3：上限 4 人；对局进行中（playing/syncing）同样允许加入 ——
        //   新玩家经存档同步流程"快速同步画面后接入"（见 relayLoop ready 分支）。
        if room.playerCount() >= maxRoomPlayers {
                h.mu.Unlock()
                _ = json.NewEncoder(conn).Encode(relayMsg{Type: "error", Msg: "房间已满（可改用观战加入）"})
                return
        }
        if _, exists := room.conns[claims.Sub]; exists {
                h.mu.Unlock()
                _ = json.NewEncoder(conn).Encode(relayMsg{Type: "error", Msg: "已在房间中"})
                return
        }

        // ★ 槽位分配：房主（OwnerID）= 1P（槽 1）；加入者取下一个空闲槽（2/3/4）。
        // ★★ v4.4：hello.want 指定座位优先（用户在大厅点了 2P/3P/4P 座位）。
        // 拿不到 OwnerID（旧房间）时第一个连接者当 1P。
        isHost := false
        if room.OwnerID != "" {
                isHost = claims.Sub == room.OwnerID
        } else if room.playerCount() == 0 {
                isHost = true
        }
        slot := 0
        if isHost {
                slot = 1
                for _, c2 := range room.conns {
                        if c2.slot == 1 {
                                slot = 0 // 1P 槽被占（房主重连场景之外的理论态）：改取空闲槽
                                break
                        }
                }
                if slot == 0 {
                        slot = room.nextFreeSlot(hello.Want)
                }
        } else {
                slot = room.nextFreeSlot(hello.Want)
        }
        if slot == 0 {
                h.mu.Unlock()
                _ = json.NewEncoder(conn).Encode(relayMsg{Type: "error", Msg: "房间已满（可改用观战加入）"})
                return
        }

        rc := &relayConn{
                conn: conn,
                enc:  json.NewEncoder(conn),
                user: h.users.byID(claims.Sub),
                room: room,
                slot: slot,
        }
        room.conns[claims.Sub] = rc

        playing := room.Status == roomPlaying || room.Status == roomSyncing
        players := activeSlots(room)
        h.mu.Unlock()

        conn.SetReadDeadline(time.Time{}) // 清除超时

        // hello 回执：角色（host=1P）、参考延迟、本端槽位、成员槽位、是否已在跑
        role := "guest"
        if isHost {
                role = "host"
        }
        _ = rc.send(relayMsg{
                Type:    "hello",
                Msg:     role,
                Pad:     h.finalDelay(),
                Slot:    rc.slot,
                Players: players,
                Playing: playing,
        })

        // 通知其他成员：有新玩家加入（带槽位）—— 1P 端据此显示"2P/3P/4P 正在接入"
        h.broadcastExcept(room, rc, relayMsg{Type: "peer_joined", Msg: rc.user.Username, Slot: rc.slot})

        h.relayLoop(rc, reader)
}

// broadcastExcept 向房间内除 exclude 外的所有成员广播（exclude 为 nil = 全员）。
// ★★ v4.4：广播覆盖观战者（specs）—— 观战者收全员 input/pause/state/resume/
// peer_*，与玩家看到同一画面。
func (h *relayHub) broadcastExcept(room *Room, exclude *relayConn, msg relayMsg) {
        h.mu.Lock()
        targets := make([]*relayConn, 0, maxRoomPlayers+maxSpectators)
        for _, c := range room.conns {
                if c != exclude {
                        targets = append(targets, c)
                }
        }
        for _, c := range room.specs {
                if c != exclude {
                        targets = append(targets, c)
                }
        }
        h.mu.Unlock()
        for _, c := range targets {
                _ = c.send(msg)
        }
}

// relayLoop 读取消息并路由。
func (h *relayHub) relayLoop(rc *relayConn, reader *bufio.Reader) {
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
                        // ★★ v4.4：观战者不产生输入（忽略，防协议滥用）；
                        //   玩家输入带发送者槽位广播给房间内所有其他成员（含观战者 ——
                        //   观战者与玩家同帧同画运行）。
                        if rc.spectator {
                                continue
                        }
                        h.mu.Lock()
                        room := rc.room
                        targets := make([]*relayConn, 0, maxRoomPlayers+maxSpectators)
                        for _, c := range room.conns {
                                if c != rc {
                                        targets = append(targets, c)
                                }
                        }
                        for _, c := range room.specs {
                                if c != rc {
                                        targets = append(targets, c)
                                }
                        }
                        h.mu.Unlock()
                        out := relayMsg{Type: "input", Frame: msg.Frame, Pad: msg.Pad, Slot: rc.slot}
                        for _, c := range targets {
                                if err := c.send(out); err != nil {
                                        c.close()
                                        h.drop(c)
                                }
                        }

                case "ready":
                        // ★★ v4.3 三种开局（用户需求："1P 进房直接单机开玩；2P/3P/4P
                        //   进入时连接 1P、快速同步画面后接入"）：
                        //   A. 房间未开跑（waiting/ready）：全员 ready → 同时广播 start
                        //      （旧双端流程，兼容 2/3/4 人同场首次开局）。
                        //   B. 房主单开：房主 ready 且房内只有自己 → 立即回 start，房间转
                        //      playing 并保持可加入 —— 1P 直接单机开始，不等任何人。
                        //   C. 对局进行中（playing/syncing）新玩家 ready → 触发存档同步：
                        //      广播 pause（全员暂停）+ state_req（房主暂停后发存档）→
                        //      房主 state → 服务器缓存并转发 → 新玩家 state_ok →
                        //      全员广播 resume（各端装载同一存档、同帧恢复）。
                        h.mu.Lock()
                        rc.ready = true
                        if msg.Pad > rc.readyPad {
                                rc.readyPad = msg.Pad
                        }
                        room := rc.room
                        count := room.playerCount()
                        allReady := true
                        suggestions := []int{}
                        for _, c := range room.conns {
                                if !c.ready {
                                        allReady = false
                                }
                                suggestions = append(suggestions, c.readyPad)
                        }
                        status := room.Status
                        syncing := status == roomSyncing
                        h.mu.Unlock()

                        switch {
                        case status == roomPlaying || syncing:
                                // C. 后加入同步（syncing 期间又有新玩家/观战者 ready：并入同一轮同步，
                                //    不重复发起 —— 他们会收到同一份 state 广播）
                                if !syncing {
                                        h.mu.Lock()
                                        room.Status = roomSyncing
                                        h.mu.Unlock()
                                        // 1) 全员暂停在各自下一帧（引擎线程在 beforeFrame 里挂起）。
                                        //    ★ v4.4：观战者同样暂停（broadcastExcept 已覆盖 specs）。
                                        h.broadcastExcept(room, nil, relayMsg{Type: "pause"})
                                        // 2) 房主：暂停后生成并发送存档
                                        h.mu.Lock()
                                        owner := ownerConn(room)
                                        h.mu.Unlock()
                                        if owner != nil {
                                                _ = owner.send(relayMsg{Type: "state_req"})
                                        } else {
                                                // 无房主连接（理论态）：放弃同步，恢复运行
                                                h.mu.Lock()
                                                room.Status = roomPlaying
                                                h.mu.Unlock()
                                                h.broadcastExcept(room, nil, relayMsg{Type: "resume", Players: activeSlots(room)})
                                        }
                                }
                        case count == 1 && isRoomOwner(room, rc):
                                // B. 房主单开：1P 直接单机开始（房间保持可加入）。
                                // ★★ v4.4：必须由房主触发（旧条件 count==1 会被观战者的 ready 误触发）；
                                //    start 广播给全员含观战者（观战者也同步开跑）。
                                d := h.finalDelay(suggestions...)
                                h.mu.Lock()
                                room.Status = roomPlaying
                                players := activeSlots(room)
                                h.mu.Unlock()
                                h.broadcastExcept(room, nil, relayMsg{Type: "start", Pad: d, Players: players})
                        case allReady:
                                // A. 全员就绪同时开跑（★ v4.4：观战者随广播同步开跑）
                                d := h.finalDelay(suggestions...)
                                h.mu.Lock()
                                room.Status = roomPlaying
                                players := activeSlots(room)
                                h.mu.Unlock()
                                h.broadcastExcept(room, nil, relayMsg{Type: "start", Pad: d, Players: players})
                        default:
                                // 还有人在等：转发 peer_ready（UI 状态显示用；观战者 ready 不阻塞开局）
                                h.broadcastExcept(room, rc, relayMsg{Type: "peer_ready", Msg: rc.user.Username})
                        }

                case "state":
                        // 房主的存档（后加入同步）。缓存到房间 + 转发给除房主外的所有成员
                        // （★★ v4.4 含观战者 —— 观战者装载同一存档同帧起步）：
                        // 新玩家要装载；其他在线成员也在 resume 时装载同一份（统一回滚对齐）。
                        h.mu.Lock()
                        room := rc.room
                        st := msg
                        room.lastStateMu.Lock()
                        room.lastState = &st
                        room.lastStateMu.Unlock()
                        targets := make([]*relayConn, 0, maxRoomPlayers+maxSpectators)
                        for _, c := range room.conns {
                                if c != rc {
                                        targets = append(targets, c)
                                }
                        }
                        for _, c := range room.specs {
                                if c != rc {
                                        targets = append(targets, c)
                                }
                        }
                        h.mu.Unlock()
                        for _, c := range targets {
                                _ = c.send(relayMsg{
                                        Type: "state", Frame: msg.Frame, Data: msg.Data,
                                        Hist: msg.Hist, HistEnd: msg.HistEnd,
                                })
                        }

                case "state_ok":
                        // 新玩家已装载存档 → 全员广播 resume（带存档与最终延迟）。
                        // 各端装载同一份存档、以 frame 对齐恢复 —— 画面完全一致。
                        h.mu.Lock()
                        room := rc.room
                        if room.Status != roomSyncing {
                                // 重复 state_ok（多个新玩家各发一条）：只在 syncing → playing
                                // 的第一次广播 resume，其余忽略（他们已随广播装载）。
                                h.mu.Unlock()
                                continue
                        }
                        room.Status = roomPlaying
                        d := h.finalDelay()
                        for _, c := range room.conns {
                                if c.readyPad > d {
                                        d = c.readyPad
                                }
                        }
                        if d > 16 {
                                d = 16
                        }
                        room.lastStateMu.Lock()
                        ls := room.lastState
                        room.lastStateMu.Unlock()
                        players := activeSlots(room)
                        h.mu.Unlock()

                        resume := relayMsg{Type: "resume", Pad: d, Players: players}
                        if ls != nil {
                                resume.Frame = ls.Frame
                                resume.Data = ls.Data
                                resume.Hist = ls.Hist
                                resume.HistEnd = ls.HistEnd
                        }
                        h.broadcastExcept(room, nil, resume)

                case "ping":
                        _ = rc.send(relayMsg{Type: "pong", Ts: msg.Ts})
                case "bye":
                        h.drop(rc)
                        return
                }
        }
        // 连接断开
        h.drop(rc)
}

// isRoomOwner 判断连接是否房主（★ v4.4：B 路径“房主单开”只认房主本人的 ready，
// 观战者的 ready 不得误触发 start）。
func isRoomOwner(room *Room, rc *relayConn) bool {
        if room.OwnerID != "" {
                return rc.user != nil && rc.user.ID == room.OwnerID
        }
        // 旧房间无 OwnerID：第一个连接者（1P）即房主
        return rc.slot == 1
}

// drop 断开连接：通知其他成员、清理房间
func (h *relayHub) drop(rc *relayConn) {
        h.mu.Lock()
        room := rc.room
        if room != nil {
                // ★★ v4.4 观战者：从 specs 移除 + 通知（spec=true，不占槽位）
                if rc.spectator {
                        delete(room.specs, rc.user.ID)
                        log.Printf("[观战] %s 离开房间 %s 观战（剩余观战 %d 人）",
                                rc.user.Username, room.ID, len(room.specs))
                        h.mu.Unlock()
                        h.broadcastExcept(room, rc, relayMsg{
                                Type: "peer_left", Msg: rc.user.Username, Slot: 0, Spec: true,
                        })
                        rc.close()
                        return
                }
                for _, c := range room.conns {
                        if c != rc {
                                _ = c.send(relayMsg{Type: "peer_left", Msg: rc.user.Username, Slot: rc.slot})
                        }
                }
                delete(room.conns, rc.user.ID)
                if room.playerCount() == 0 {
                        // 玩家全走：房间删除，观战者连接一并关闭（客户端收 onDisconnected 引导退出）
                        for _, sc := range room.specs {
                                _ = sc.send(relayMsg{Type: "error", Msg: "对战已结束（玩家已全部离开）"})
                                sc.close()
                        }
                        room.specs = map[string]*relayConn{}
                        delete(h.rooms, room.ID)
                } else if room.Status == roomSyncing {
                        // 同步中有人掉线：别让其余人卡在暂停态 ——
                        // 已有存档：按存档恢复；没有：直接继续（无 data 的 resume）。
                        room.Status = roomPlaying
                        players := activeSlots(room)
                        room.lastStateMu.Lock()
                        ls := room.lastState
                        room.lastStateMu.Unlock()
                        resume := relayMsg{Type: "resume", Players: players}
                        if ls != nil {
                                resume.Frame = ls.Frame
                                resume.Data = ls.Data
                                resume.Hist = ls.Hist
                                resume.HistEnd = ls.HistEnd
                        }
                        for _, c := range room.conns {
                                _ = c.send(resume)
                        }
                }
        }
        h.mu.Unlock()
        rc.close()
}
