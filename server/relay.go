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
        // ★★ v4.5：后加入同步（late join）
        roomSyncing string = "syncing" // 正在为新加入玩家做画面(存档)同步
)

// ★★ v4.5.1：客户端协议版本（hello.v）。45 = 支持 restart 全员重开
//（核心不支持快照的游戏：NeoGeo 改版/PGM 等 —— 存档同步不可用时的回退）。
const clientProtoRestart = 45

// maxRoomPlayers 最大玩家数（1P-4P）。FBNeo/街机支持 4 手柄；
// 其他 2 手柄核心上 3/4P 的输入被客户端忽略（仍可观战同步画面）。
const maxRoomPlayers = 4

// ★★ v4.4 观战系统：观战者不占玩家槽位、只收不发（收全员输入/存档/恢复），
// 满员房间仍可观战。上限防滥用。
const maxSpectators = 16

const (
        // ★★ v4.5 同步看门狗：进入 syncing 后这么久还没走完（房主出档慢/失败/
        // 加入者装载卡住）→ 服务器主动收尾，绝不让任何人卡在“同步中”。
        defaultSyncWatchdog = 10 * time.Second
        // ★★ v4.5 服务器缓存存档的“新鲜”窗口：超过它的缓存不再用于同步恢复
        //（房主周期上传间隔 15s + 同步暂停窗口，正常缓存都在窗口内）。
        stateFreshWindow = 30 * time.Second
        // ★★ v4.5 桌号上限（客户端街机厅 10 桌，服务端留裕量）。
        maxTableNo = 64
        // ★★ v4.5 空房回收：创建后一直无人连上的房间占住桌号，定期清理。
        reapInterval     = 30 * time.Second
        staleRoomReapTTL = 2 * time.Minute
)

type Room struct {
        ID        string
        GameID    string
        CreatedAt time.Time
        Status    string // 房间状态：waiting / ready / playing / syncing
        // ★ 房主（创建者）用户 ID —— 由 HTTP 创建房间时记录，
        //   中继握手时按它分配 host 角色（创建者 = 1P = 槽 1）。
        OwnerID string
        // ★★ v4.5 桌号固定：创建时由客户端指定（点哪桌开哪桌）。
        //   房间列表按桌号展示 —— 开 2 桌就是 2 桌，不再随排序漂移。
        TableNo int
        // 玩家连接（socket 已建立）。key 为 user id。
        conns map[string]*relayConn
        // ★★ v4.4 观战者连接（不占 1P-4P 槽位）。key 为 user id。
        specs map[string]*relayConn
        // ★ v4.3 后加入：房主最近一次发来的存档（frame/data/hist）。
        // state_ok 时随 resume 一并广播给全员 —— 所有成员装载同一份存档、
        // 以同一帧号恢复，保证 1P/2P/3P/4P 画面与状态完全一致。
        lastState   *relayMsg
        lastStateMu sync.Mutex
        // ★★ v4.5 服务器侧存档同步：lastState 的落地时间（新鲜度判定）。
        //   房主周期 state_upload + 同步时 state 都会刷新它 —— 后加入/观战
        //   从“服务器”拿档，不再依赖加入瞬间房主即时出档。
        lastStateAt time.Time
        // ★★ v4.5 本轮同步（syncing）待接入的成员（玩家+观战者）。
        //   同步收尾时据此决定谁能随档恢复、谁需要劝退。
        syncJoiners map[string]*relayConn
        // ★★ v4.5 同步看门狗（超时 abort）与同步轮次（防旧定时器误杀新同步）。
        syncTimer *time.Timer
        syncGen   uint64
}

func (r *Room) playerCount() int { return len(r.conns) }

// spectatorCount 观战者人数（房间列表/满员判定用）。
func (r *Room) spectatorCount() int { return len(r.specs) }

// cacheState 记录服务器侧存档（state / state_upload 的落点）。
func (r *Room) cacheState(st *relayMsg) {
        r.lastStateMu.Lock()
        r.lastState = st
        r.lastStateAt = time.Now()
        r.lastStateMu.Unlock()
}

// cachedState 最新缓存档（不管新旧）。
func (r *Room) cachedState() *relayMsg {
        r.lastStateMu.Lock()
        defer r.lastStateMu.Unlock()
        return r.lastState
}

// freshCachedState 新鲜缓存档（stateFreshWindow 内）—— 可用于同步恢复；
// 过旧的档（如房主离开后停更）恢复会让全员倒退太多，不采用。
// ★ 锁序约定：持有 hub.mu 时调用（hub.mu → lastStateMu），不得反向。
func (r *Room) freshCachedState() *relayMsg {
        r.lastStateMu.Lock()
        defer r.lastStateMu.Unlock()
        if r.lastState != nil && time.Since(r.lastStateAt) <= stateFreshWindow {
                return r.lastState
        }
        return nil
}

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
//   ★★ v4.3 后加入同步（服务器编排，★ v4.5 以服务器缓存档为准）：
//   <- {"type":"pause"}                                     全体暂停在各自下一帧
//   <- {"type":"state_req"}                                 仅房主：暂停后发存档
//   -> {"type":"state","frame":F,"data":"base64","hist":{...}}  房主存档+近16帧输入
//   <- {"type":"state","frame":F,"data":...,"hist":...}     服务器转发给其他成员
//   -> {"type":"state_ok"}                                  新玩家已确认收到存档
//   <- {"type":"resume","frame":F,"pad":最终延迟,"players":[..],
//       "data":...,"hist":...}                              全员装载后同帧恢复
//   ★★ v4.5 服务器侧存档同步（联机平台模式 —— 存档同步在服务器）：
//   -> {"type":"state_upload","frame":F,"data":...,"hist":...}  房主周期快照上传
//                                                            （服务器缓存，供后加入/观战同步）
//   -> {"type":"state_fail"}                                房主出档失败 → 服务器立即收尾
//   （同步全程有看门狗：超时/失败 → 服务器按缓存档恢复全员；
//     无档可用 → 原玩家继续、接入者收 error 劝退 —— 绝不卡在“同步中”）
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
        // ★★ v4.5.1：hello 客户端协议版本（45 = 支持 restart 全员重开）
        V int `json:"v,omitempty"`
        // ★★ v4.5.1：ready 附带 —— 该端能否出存档快照（1 能 / -1 不能 / 0 未知=旧客户端）。
        //   部分核心/驱动不支持快照（FBNeo 的 PGM/改版等，且 JNI 永远返回 true，
        //   客户端只能靠“存档文件非空”探测）—— 服务器据此决定：快照同步 or 全员重开。
        StateCap int `json:"stateCap,omitempty"`
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
        // ★★ v4.5：drop 幂等标记（多路径重复 drop 防重复 peer_left）。
        dropped bool
        // ★★ v4.5.1：客户端协议版本（hello.v；0 = 旧客户端）
        v int
        // ★★ v4.5.1：能否出存档快照（ready.stateCap；0 未知 / 1 能 / -1 不能）。
        stateCap int
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

        // ★★ v4.5 同步看门狗时长（测试可注入短值；0 = 默认 defaultSyncWatchdog）。
        watchdogMs int
}

func newRelayHub(cfg *configStore, jwt *jwt, users *userStore) *relayHub {
        return &relayHub{cfg: cfg, jwt: jwt, users: users, rooms: map[string]*Room{}}
}

// watchdogDuration 同步看门狗时长。
func (h *relayHub) watchdogDuration() time.Duration {
        if h.watchdogMs > 0 {
                return time.Duration(h.watchdogMs) * time.Millisecond
        }
        return defaultSyncWatchdog
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
        // ★★ v4.5：对局中的房间要有同步画面源（房主在 / 服务器有新鲜缓存档）
        //   才能接入 —— 两者皆无时明确拒绝，不再让接入者掉进“各玩各的”鬼影对局。
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
                if (room.Status == roomPlaying || room.Status == roomSyncing) &&
                        ownerConn(room) == nil && room.freshCachedState() == nil &&
                        !h.roomSupportsRestart(room) {
                        h.mu.Unlock()
                        _ = json.NewEncoder(conn).Encode(relayMsg{Type: "error", Msg: "房主已离开且服务器暂无同步画面，无法接入；请稍后再试或另开一桌"})
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
                        v:    hello.V, // ★★ v4.5.1 客户端协议版本（restart 支持判定）
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

        // ★★ v4.5 对局中接入的先决条件：房主在（可即时出档）或服务器有新鲜缓存档，
        //   或全员支持重开（v≥45 —— 无档也能全员重开接入）。三者皆无才明确拒绝
        //   （旧行为：房主不在时发一份无档 resume，接入者从帧 0 独自开跑 ——
        //   与原玩家各玩各的“卡同步/画面错乱”根源之一）。
        if (room.Status == roomPlaying || room.Status == roomSyncing) &&
                ownerConn(room) == nil && room.freshCachedState() == nil &&
                !h.roomSupportsRestart(room) {
                h.mu.Unlock()
                _ = json.NewEncoder(conn).Encode(relayMsg{Type: "error", Msg: "房主已离开且服务器暂无同步画面，无法接入；请稍后再试或另开一桌"})
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
                v:    hello.V, // ★★ v4.5.1 客户端协议版本（restart 支持判定）
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
                        //   C. 对局进行中（playing/syncing）新玩家 ready → 触发存档同步
                        //      （★★ v4.5 服务器主导 + 看门狗，详见 beginSync 注释）。
                        h.mu.Lock()
                        rc.ready = true
                        if msg.Pad > rc.readyPad {
                                rc.readyPad = msg.Pad
                        }
                        // ★★ v4.5.1：出档能力上报（1 能 / -1 不能；0/缺省 = 旧客户端未知）
                        if msg.StateCap != 0 {
                                rc.stateCap = msg.StateCap
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
                                // C. 后加入同步（服务器主导 + 看门狗，详见 beginSync 注释）。
                                //    syncing 期间又有新成员 ready → 并入本轮（beginSync 内
                                //    会在锁内复核状态，杜绝竞态脏数据）。
                                h.beginSync(room, rc)
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
                        // 房主的同步存档（应 state_req）：服务器缓存 + syncing 期间转发给
                        // 其他成员（含观战者）。v4.5 客户端统一在 resume 时装载，
                        // 这里转发主要为触发接入者 state_ok（并兼容旧客户端装载路径）。
                        // ★ 仅认房主的档（防伪造）。
                        h.mu.Lock()
                        room := rc.room
                        isOwner := isRoomOwner(room, rc)
                        syncing := room.Status == roomSyncing
                        h.mu.Unlock()
                        if !isOwner {
                                continue
                        }
                        st := msg
                        room.cacheState(&st)
                        if syncing {
                                h.broadcastState(room, rc, &st)
                        }

                case "state_upload":
                        // ★★ v4.5 服务器侧存档：房主周期上报快照 → 服务器缓存。
                        //   2P/3P/4P/观战加入时从服务器拿档（联机平台模式），
                        //   不再依赖加入瞬间房主即时出档。syncing 期间只刷缓存不转发
                        //   （本轮同步的档走 state_req / 缓存路径）。
                        h.mu.Lock()
                        room := rc.room
                        isOwner := isRoomOwner(room, rc)
                        h.mu.Unlock()
                        if !isOwner {
                                continue
                        }
                        st := msg
                        room.cacheState(&st)
                        log.Printf("[存档] 房间 %s：房主快照已缓存（帧 %d，约 %dKB）",
                                room.ID, st.Frame, len(st.Data)*3/4/1024)

                case "state_fail":
                        // ★★ v4.5 房主出档失败（客户端主动上报）→ 服务器立即收尾，不等看门狗。
                        h.mu.Lock()
                        room := rc.room
                        isOwner := isRoomOwner(room, rc)
                        syncing := room.Status == roomSyncing
                        h.mu.Unlock()
                        if isOwner && syncing {
                                h.abortSync(room, "房主出档失败")
                        }

                case "state_ok":
                        // 接入者已确认收到存档 → 全员广播 resume（带存档与最终延迟）。
                        // 各端在 resume 时统一装载同一份档、以 frame 对齐恢复 —— 画面完全一致。
                        h.completeSync(rc)

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

// ---------------------------------------------------------------------------
// ★★ v4.5 服务器侧存档同步（后加入/观战接入编排）
// ---------------------------------------------------------------------------

// broadcastState 把存档（房主新档或服务器缓存档）发给除 exclude 外的成员（含观战者）。
func (h *relayHub) broadcastState(room *Room, exclude *relayConn, st *relayMsg) {
        h.broadcastExcept(room, exclude, relayMsg{
                Type: "state", Frame: st.Frame, Data: st.Data,
                Hist: st.Hist, HistEnd: st.HistEnd,
        })
}

// beginSync 开始一轮后加入同步（★ 在未持锁时调用）。接入者 rc 已 ready、房间在 playing。
//
// 服务器主导（存档同步在服务器，联机平台模式；★★ v4.5.1 决策树）：
//  1. 首选【服务器缓存档】（房主周期 state_upload 上来、引擎线程安全窗口出的档）：
//     广播 pause → 直接发缓存档 → 接入者 state_ok → 全员 resume 带档同帧恢复
//    （画面统一回到缓存帧，典型 3-10 秒内 —— 一致性优先）。
//  2. 无缓存且房主能出档（或旧客户端能力未知）→ state_req 新鲜档（零回退）。
//  3. 拿不到任何档（核心不支持快照 —— FBNeo 的 PGM/改版驱动等；或房主不在）
//     → 全员重开 restartSync（v≥45 房间）：全员回开机状态从帧 0 重新同步开跑。
//  4. 混合旧客户端房间（不能发 restart）→ 接入者劝退，绝不卡死。
//
// 全程看门狗（startSyncWatchdog）：超时/房主 state_fail/房主掉线 → abortSync 收尾
//（优先缓存档恢复，其次全员重开，最后旧收尾）。
func (h *relayHub) beginSync(room *Room, rc *relayConn) {
        h.mu.Lock()
        if room.Status == roomSyncing {
                // 并发竞态：另一接入者刚开启同步 —— 并入本轮（收同一份 state/resume）
                if room.syncJoiners == nil {
                        room.syncJoiners = map[string]*relayConn{}
                }
                room.syncJoiners[rc.user.ID] = rc
                h.mu.Unlock()
                return
        }
        if room.Status != roomPlaying {
                h.mu.Unlock()
                return
        }
        owner := ownerConn(room)
        cached := room.freshCachedState() // hub.mu → lastStateMu 锁序 ✓
        // ★★ v4.5.1 接入方式决策（存档同步在服务器 —— 能拿档绝不重开）：
        //   1. 服务器有新鲜缓存档（房主周期上传）→ 缓存档接入（首选，稳定）；
        //   2. 房主在且能出档（或旧客户端能力未知）→ state_req 新鲜档（零回退）；
        //   3. 都不行（核心不支持快照 / 房主不在）→ 全员重开（v≥45 房间）；
        //   4. 混合旧客户端房间 → 维持旧收尾（接入者劝退，绝不卡死）。
        mode := ""
        switch {
        case cached != nil:
                mode = "cache"
        case owner != nil && owner.stateCap >= 0:
                mode = "fresh"
        case h.roomSupportsRestart(room):
                mode = "restart"
        }
        if mode == "" {
                // 无档可用且不能重开：不广播 pause，接入者劝退
                h.mu.Unlock()
                log.Printf("[同步] 房间 %s（桌 %d）：%s 接入失败（无可用同步画面且不可重开）",
                        room.ID, room.TableNo, rc.user.Username)
                _ = rc.send(relayMsg{Type: "error", Msg: "暂时无法同步画面（服务器无近期存档），请稍后再试"})
                h.drop(rc)
                return
        }
        if mode == "restart" {
                h.mu.Unlock()
                h.restartSync(room, rc, "无可用同步画面")
                return
        }
        room.Status = roomSyncing
        room.syncGen++
        if room.syncJoiners == nil {
                room.syncJoiners = map[string]*relayConn{}
        }
        room.syncJoiners[rc.user.ID] = rc
        gen := room.syncGen
        h.mu.Unlock()

        log.Printf("[同步] 房间 %s（桌 %d）：%s 接入，开始画面同步（%s）",
                room.ID, room.TableNo, rc.user.Username,
                map[string]string{"cache": "服务器缓存档", "fresh": "房主即时出档"}[mode])
        // 1) 全员暂停在各自下一帧（引擎线程在 beforeFrame 里挂起；观战者同样暂停）
        h.broadcastExcept(room, nil, relayMsg{Type: "pause"})
        if mode == "fresh" {
                // 2) 房主即时出档（零回退）
                _ = owner.send(relayMsg{Type: "state_req"})
        } else {
                // 2) 服务器缓存档直接接入：全员同一份档、同帧恢复
                h.broadcastState(room, nil, cached)
        }
        h.startSyncWatchdog(room, gen)
}

// startSyncWatchdog 同步看门狗：超过 watchdogDuration 同步未走完 → abortSync 收尾
//（房主出档慢/失败、接入者装载卡住都兜得住 —— 绝不让房间卡在“同步中”）。
// ★ 在未持锁时调用。
func (h *relayHub) startSyncWatchdog(room *Room, gen uint64) {
        d := h.watchdogDuration()
        h.mu.Lock()
        room.syncTimer = time.AfterFunc(d, func() {
                h.mu.Lock()
                if room.Status != roomSyncing || room.syncGen != gen {
                        h.mu.Unlock() // 本轮已收尾 / 已被新一轮同步取代
                        return
                }
                h.mu.Unlock()
                log.Printf("[同步] 房间 %s：同步超时（%s），服务器收尾", room.ID, d)
                h.abortSync(room, "同步超时")
        })
        h.mu.Unlock()
}

// stopSyncTimerLocked 停掉看门狗定时器（★ 已持 hub.mu 时调用；不动 syncJoiners ——
// 中止路径要先快照接入者名单再清，见 abortSync）。
func (h *relayHub) stopSyncTimerLocked(room *Room) {
        if room.syncTimer != nil {
                room.syncTimer.Stop()
                room.syncTimer = nil
        }
}

// completeSync 同步完成（任一接入者 state_ok）：全员带档恢复（★ 未持锁时调用）。
func (h *relayHub) completeSync(rc *relayConn) {
        room := rc.room
        if room == nil {
                return
        }
        h.mu.Lock()
        if room.Status != roomSyncing {
                // 重复 state_ok（多个接入者各发一条）：只在 syncing → playing 的
                // 第一次广播 resume，其余忽略（他们已随广播装载）。
                h.mu.Unlock()
                return
        }
        room.Status = roomPlaying
        h.stopSyncTimerLocked(room)
        room.syncJoiners = map[string]*relayConn{}
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
        log.Printf("[同步] 房间 %s：画面同步完成，全员带档恢复（帧 %d）", room.ID, resume.Frame)
}

// abortSync 同步中止（看门狗超时 / 房主 state_fail / 房主掉线）：
//   - 服务器有新鲜缓存档 → 全员带档恢复（画面统一回到缓存帧 —— 一致性优先；
//     接入者随 resume 档一起接入成功）；
//   - 无新鲜档 → 原玩家从各自暂停帧裸恢复（暂停前他们本就同帧一致），
//     接入者收 error 并被请出 —— 没有可匹配的画面，硬等只会全房卡死。
// ★ 在未持锁时调用。
func (h *relayHub) abortSync(room *Room, reason string) {
        h.mu.Lock()
        if room.Status != roomSyncing {
                h.mu.Unlock()
                return
        }
        room.Status = roomPlaying
        h.stopSyncTimerLocked(room)
        // ★ 先快照接入者名单，再清空（顺序不能反 —— 否则名单永远为空）
        joiners := make([]*relayConn, 0, len(room.syncJoiners))
        for _, c := range room.syncJoiners {
                joiners = append(joiners, c)
        }
        room.syncJoiners = map[string]*relayConn{}
        d := h.finalDelay()
        for _, c := range room.conns {
                if c.readyPad > d {
                        d = c.readyPad
                }
        }
        if d > 16 {
                d = 16
        }
        cached := room.freshCachedState() // hub.mu → lastStateMu 锁序 ✓
        canRestart := h.roomSupportsRestart(room) // ★ 持锁内判定（只读 conns/specs）
        players := activeSlots(room)
        h.mu.Unlock()

        log.Printf("[同步] 房间 %s：同步中止（%s，接入者 %d 名）—— %s",
                room.ID, reason, len(joiners),
                map[bool]string{true: "按服务器缓存档恢复", false: "全员重开/原玩家继续"}[cached != nil])

        if cached != nil {
                // 缓存档恢复全员（含接入者 —— 随 resume 档一起接入）
                h.broadcastExcept(room, nil, relayMsg{
                        Type: "resume", Pad: d, Players: players,
                        Frame: cached.Frame, Data: cached.Data, Hist: cached.Hist, HistEnd: cached.HistEnd,
                })
                return
        }
        if canRestart {
                // ★★ v4.5.1：拿不到档（核心不支持快照等）→ 全员重开（接入者留下一起重开）
                h.restartSync(room, joiners, reason)
                return
        }
        // 混合旧客户端房间：接入者劝退，原玩家裸恢复
        for _, j := range joiners {
                _ = j.send(relayMsg{Type: "error", Msg: "同步失败：暂时拿不到房主画面，请稍后重试"})
        }
        for _, j := range joiners {
                h.drop(j) // 先 drop（peer_left 先到），再广播 resume —— 避免把已离开
                // 接入者的槽位放进 players 让原玩家死等其输入
        }
        h.mu.Lock()
        players = activeSlots(room) // ★ drop 后重算（不含已请出的接入者）
        h.mu.Unlock()
        h.broadcastExcept(room, nil, relayMsg{Type: "resume", Pad: d, Players: players})
}

// roomSupportsRestart 全员（玩家+观战者）客户端协议 v≥45（★ 持 hub.mu 时调用）。
// 旧客户端不认识 restart 消息会一直挂在暂停门 → 混合房间不能发 restart。
func (h *relayHub) roomSupportsRestart(room *Room) bool {
        for _, c := range room.conns {
                if c.v < clientProtoRestart {
                        return false
                }
        }
        for _, c := range room.specs {
                if c.v < clientProtoRestart {
                        return false
                }
        }
        return true
}

// restartSync 全员重开（★★ v4.5.1）：拿不到任何可用档（核心不支持快照，如
// FBNeo 的 PGM/改版驱动；或房主不在且无缓存）时 —— 与其劝退接入者，不如
// 全员回到开机状态、从帧 0 同步重新开跑（街机语义：新人加入 = 新的一局）。
// 广播 restart 消息：各端引擎复位 + 重新就绪；服务器置回 waiting 等全员
// re-ready → 走 A 路径（全员就绪 → 同时广播 start）从帧 0 同步开跑。
// ★ 在未持锁时调用。trigger 可为单个接入者或多接入者列表（仅用于日志）。
func (h *relayHub) restartSync(room *Room, trigger any, reason string) {
        h.mu.Lock()
        room.Status = roomWaiting // 重新走“全员 ready → start”开局流程
        h.stopSyncTimerLocked(room)
        room.syncJoiners = map[string]*relayConn{}
        for _, c := range room.conns {
                c.ready = false // 强制全员重新就绪（等各自的引擎复位完成）
        }
        players := activeSlots(room) // 含接入者（他们已入座）
        d := h.finalDelay()
        for _, c := range room.conns {
                if c.readyPad > d {
                        d = c.readyPad
                }
        }
        if d > 16 {
                d = 16
        }
        h.mu.Unlock()

        who := ""
        switch t := trigger.(type) {
        case *relayConn:
                who = t.user.Username
        case []*relayConn:
                for i, c := range t {
                        if i > 0 {
                                who += ","
                        }
                        who += c.user.Username
                }
        }
        log.Printf("[重开] 房间 %s（桌 %d）：%s —— %s 触发全员重开（本作不支持画面快照同步）",
                room.ID, room.TableNo, reason, who)
        h.broadcastExcept(room, nil, relayMsg{Type: "restart", Pad: d, Players: players})
}

// drop 断开连接：通知其他成员、清理房间（★★ v4.5 幂等：重复 drop 只生效一次）
func (h *relayHub) drop(rc *relayConn) {
        h.mu.Lock()
        if rc.dropped {
                h.mu.Unlock()
                return
        }
        rc.dropped = true
        room := rc.room
        var abortAfterDrop bool  // 同步中房主掉线 → abortSync 收尾
        var bareResumeAfterDrop bool // 同步中接入者都走了 → 原玩家裸恢复
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
                        // ★★ v4.5 同步中掉线：
                        //   - 掉的是房主 → 立即收尾（abortSync：缓存档或劝退接入者）；
                        //   - 掉的是本轮接入者 → 移出名单；名单空了 → 原玩家从各自
                        //     暂停帧裸恢复（暂停前本就同帧一致，不必回退）；
                        //   - 其余 → 看门狗兜底。
                        if room.syncJoiners != nil {
                                delete(room.syncJoiners, rc.user.ID)
                        }
                        if isRoomOwner(room, rc) {
                                abortAfterDrop = true
                        } else if len(room.syncJoiners) == 0 {
                                room.Status = roomPlaying
                                h.stopSyncTimerLocked(room)
                                room.syncJoiners = map[string]*relayConn{}
                                bareResumeAfterDrop = true
                        }
                }
        }
        h.mu.Unlock()
        rc.close()

        if abortAfterDrop {
                h.abortSync(room, "房主掉线")
        } else if bareResumeAfterDrop {
                h.mu.Lock()
                players := activeSlots(room)
                h.mu.Unlock()
                h.broadcastExcept(room, nil, relayMsg{Type: "resume", Players: players})
        }
}

// reapEmptyRooms 空房回收器：创建了但从未有人连上（客户端中途退出/崩溃）的
// 房间会永久占住桌号 —— 周期清理（无人无观战且超过 staleRoomReapTTL）。
func (h *relayHub) reapEmptyRooms() {
        t := time.NewTicker(reapInterval)
        defer t.Stop()
        for range t.C {
                h.mu.Lock()
                for id, r := range h.rooms {
                        if r.playerCount() == 0 && r.spectatorCount() == 0 &&
                                time.Since(r.CreatedAt) > staleRoomReapTTL {
                                delete(h.rooms, id)
                                log.Printf("[回收] 房间 %s（桌 %d，%s）创建后一直无人，已清理",
                                        r.ID, r.TableNo, r.GameID)
                        }
                }
                h.mu.Unlock()
        }
}
