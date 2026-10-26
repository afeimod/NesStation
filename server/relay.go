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
	// ★★ v4.3 后加入（late join）：对局进行中允许新玩家加入。
	roomSyncing string = "syncing" // 正在为新加入玩家做画面(存档)同步
)

// maxRoomPlayers 最大玩家数（1P-4P）。FBNeo/街机支持 4 手柄；
// 其他 2 手柄核心上 3/4P 的输入被客户端忽略（仍可观战同步画面）。
const maxRoomPlayers = 4

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
	// ★ v4.3 后加入：房主最近一次发来的存档（frame/data/hist）。
	// state_ok 时随 resume 一并广播给全员 —— 所有成员装载同一份存档、
	// 以同一帧号恢复，保证 1P/2P/3P/4P 画面与状态完全一致。
	lastState   *relayMsg
	lastStateMu sync.Mutex
}

func (r *Room) playerCount() int { return len(r.conns) }

// nextFreeSlot 返回下一个空闲玩家槽位（1-4）。房主占用槽 1，
// 加入者按顺序取 2/3/4；有人离开后槽位可被后来者复用。
func (r *Room) nextFreeSlot() int {
	used := [maxRoomPlayers + 1]bool{}
	for _, c := range r.conns {
		if c.slot >= 1 && c.slot <= maxRoomPlayers {
			used[c.slot] = true
		}
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
//   {"type":"hello","token":"...","roomId":"..."}         握手（第一行）
//   <- {"type":"hello","msg":"host"|"guest","pad":延迟帧,
//       "slot":1..4,"players":[1,2..],"playing":bool}      角色分配 + 槽位 + 成员
//   -> {"type":"ping","ts":纳秒} / <- {"type":"pong","ts":回显}
//   -> {"type":"ready","pad":建议延迟帧}                    引擎加载完毕
//   <- {"type":"start","pad":最终延迟帧,"players":[..]}      开跑（全员就绪 / 房主单开）
//   -> {"type":"input","frame":N,"pad":0x..,"slot":1..4}    本端输入（带槽位）
//   <- {"type":"input","frame":N,"pad":0x..,"slot":1..4}    其他玩家输入
//   <- {"type":"peer_joined"/"peer_left","msg":"名字","slot":N}
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
}

type relayConn struct {
	conn net.Conn
	enc  *json.Encoder
	user *User
	room *Room
	// sendMu 保护 enc 的并发写入（多 goroutine 转发）。
	sendMu sync.Mutex
	// 该玩家的槽位（1-4）。房主 = 1。
	slot int
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
	cfg   *Config
	jwt   *jwt
	users *userStore

	mu    sync.Mutex
	rooms map[string]*Room
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

// finalDelay 计算最终输入延迟（服务器配置与各端建议的最大值，钳制 16）。
func (h *relayHub) finalDelay(suggestions ...int) int {
	d := h.cfg.InputDelay
	if d <= 0 {
		d = 4
	}
	for _, s := range suggestions {
		if s > d {
			d = s
		}
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
	// ★ v4.3：上限 4 人；对局进行中（playing/syncing）同样允许加入 ——
	//   新玩家经存档同步流程"快速同步画面后接入"（见 relayLoop ready 分支）。
	if room.playerCount() >= maxRoomPlayers {
		h.mu.Unlock()
		_ = json.NewEncoder(conn).Encode(relayMsg{Type: "error", Msg: "房间已满"})
		return
	}
	if _, exists := room.conns[claims.Sub]; exists {
		h.mu.Unlock()
		_ = json.NewEncoder(conn).Encode(relayMsg{Type: "error", Msg: "已在房间中"})
		return
	}

	// ★ 槽位分配：房主（OwnerID）= 1P（槽 1）；加入者取下一个空闲槽（2/3/4）。
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
			slot = room.nextFreeSlot()
		}
	} else {
		slot = room.nextFreeSlot()
	}
	if slot == 0 {
		h.mu.Unlock()
		_ = json.NewEncoder(conn).Encode(relayMsg{Type: "error", Msg: "房间已满"})
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
func (h *relayHub) broadcastExcept(room *Room, exclude *relayConn, msg relayMsg) {
	h.mu.Lock()
	targets := make([]*relayConn, 0, maxRoomPlayers)
	for _, c := range room.conns {
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
			// ★ v4.3：带发送者槽位广播给房间内所有其他成员
			h.mu.Lock()
			room := rc.room
			targets := make([]*relayConn, 0, maxRoomPlayers)
			for _, c := range room.conns {
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
				// C. 后加入同步（syncing 期间又有新玩家 ready：并入同一轮同步，
				//    不重复发起 —— 它会收到同一份 state 广播）
				if !syncing {
					h.mu.Lock()
					room.Status = roomSyncing
					h.mu.Unlock()
					// 1) 全员暂停在各自下一帧（引擎线程在 beforeFrame 里挂起）
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
			case count == 1:
				// B. 房主单开：1P 直接单机开始（房间保持可加入）
				d := h.finalDelay(suggestions...)
				h.mu.Lock()
				room.Status = roomPlaying
				players := activeSlots(room)
				h.mu.Unlock()
				_ = rc.send(relayMsg{Type: "start", Pad: d, Players: players})
			case allReady:
				// A. 全员就绪同时开跑
				d := h.finalDelay(suggestions...)
				h.mu.Lock()
				room.Status = roomPlaying
				players := activeSlots(room)
				h.mu.Unlock()
				h.broadcastExcept(room, nil, relayMsg{Type: "start", Pad: d, Players: players})
			default:
				// 还有人在等：转发 peer_ready（UI 状态显示用）
				h.broadcastExcept(room, rc, relayMsg{Type: "peer_ready", Msg: rc.user.Username})
			}

		case "state":
			// 房主的存档（后加入同步）。缓存到房间 + 转发给除房主外的所有成员：
			// 新玩家要装载；其他在线成员也在 resume 时装载同一份（统一回滚对齐）。
			h.mu.Lock()
			room := rc.room
			st := msg
			room.lastStateMu.Lock()
			room.lastState = &st
			room.lastStateMu.Unlock()
			targets := make([]*relayConn, 0, maxRoomPlayers)
			for _, c := range room.conns {
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

// drop 断开连接：通知其他成员、清理房间
func (h *relayHub) drop(rc *relayConn) {
	h.mu.Lock()
	room := rc.room
	if room != nil {
		for _, c := range room.conns {
			if c != rc {
				_ = c.send(relayMsg{Type: "peer_left", Msg: rc.user.Username, Slot: rc.slot})
			}
		}
		delete(room.conns, rc.user.ID)
		if room.playerCount() == 0 {
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
