package main

import (
        "encoding/json"
        "net/http"
        "time"
)

// --- 房间 HTTP API ---

type roomResponse struct {
        ID        string `json:"id"`
        GameID    string `json:"gameId"`
        GameTitle string `json:"gameTitle"`
        Host      string `json:"host"`
        Guest     string `json:"guest"` // 空 = 等待加入
        Status    string `json:"status"`
        CreatedAt int64  `json:"createdAt"`
        // ★ v4.3：当前人数与槽位（1P 可先单机开玩，房间保持可加入）
        Players int   `json:"players"`
        Slots   []int `json:"slots"`
        // ★★ v4.4：观战人数（满员房间仍可观战 —— 客户端据此显示“满员·仅观战”）
        Spectators int `json:"spectators"`
}

func roomToResponse(cfg *Config, r *Room) roomResponse {
        resp := roomResponse{
                ID:        r.ID,
                GameID:    r.GameID,
                CreatedAt: r.CreatedAt.Unix(),
        }
        // 找到游戏标题
        for _, g := range cfg.Games {
                if g.ID == r.GameID {
                        resp.GameTitle = g.Title
                        break
                }
        }
        // ★ 房主 = 房间创建者（Room.OwnerID），不再按连接顺序 ——
        //   与中继的角色分配保持一致，避免 HTTP 列表显示的主容与
        //   实际 pad1/pad2 分配对不上。
        if r.OwnerID != "" {
                if c, ok := r.conns[r.OwnerID]; ok && c.user != nil {
                        resp.Host = c.user.Username
                }
                for id, c := range r.conns {
                        if id != r.OwnerID && c.user != nil {
                                resp.Guest = c.user.Username
                        }
                }
        } else {
                first := true
                for _, c := range r.conns {
                        if first {
                                resp.Host = c.user.Username
                                first = false
                        } else {
                                resp.Guest = c.user.Username
                        }
                }
        }
        switch r.Status {
        case roomWaiting:
                resp.Status = "waiting"
        case roomReady:
                resp.Status = "ready"
        case roomPlaying:
                // ★ v4.3：对局中仍可加入（后加入经存档同步接入）——
                //   客户端显示为"对战中·可加入"
                resp.Status = "playing"
        case roomSyncing:
                resp.Status = "syncing"
        }
        resp.Players = r.playerCount()
        resp.Slots = activeSlots(r)
        resp.Spectators = r.spectatorCount()
        return resp
}

// handleListRooms 列出所有等待中的房间
func (s *apiServer) handleListRooms(w http.ResponseWriter, r *http.Request) {
        s.hub.mu.Lock()
        rooms := make([]roomResponse, 0, len(s.hub.rooms))
        // ★ v4.3：对局中的房间也列出（可加入）—— 1P 可先单机开玩，
        //   后来者进入房间后经存档同步"快速同步画面后接入"。
        for _, rm := range s.hub.rooms {
                rooms = append(rooms, roomToResponse(s.cfg.get(), rm))
        }
        s.hub.mu.Unlock()
        writeJSON(w, http.StatusOK, map[string]any{"rooms": rooms})
}

// handleCreateRoom 创建房间
func (s *apiServer) handleCreateRoom(w http.ResponseWriter, r *http.Request) {
        user, err := s.requireAuth(r)
        if err != nil {
                writeErr(w, http.StatusUnauthorized, err.Error())
                return
        }

        var req struct {
                GameID string `json:"gameId"`
        }
        if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
                writeErr(w, http.StatusBadRequest, "请求格式错误")
                return
        }
        // 校验游戏存在（热重载后即时按最新列表校验）
        cur := s.cfg.get()
        gameExists := false
        for _, g := range cur.Games {
                if g.ID == req.GameID {
                        gameExists = true
                        break
                }
        }
        if !gameExists {
                writeErr(w, http.StatusBadRequest, "游戏不存在")
                return
        }

        s.hub.mu.Lock()
        if len(s.hub.rooms) >= cur.MaxRooms {
                s.hub.mu.Unlock()
                writeErr(w, http.StatusTooManyRequests, "房间数量已达上限")
                return
        }
        rm := &Room{
                ID:        randomID()[:8],
                GameID:    req.GameID,
                Status:    roomWaiting,
                CreatedAt: time.Now().UTC(),
                // ★ 记录房主：中继握手时按它分配 host 角色（创建者 = 1P），
                //   不再受"谁先连上 TCP"的连接顺序影响。
                OwnerID:   user.ID,
                conns:     map[string]*relayConn{},
                specs:     map[string]*relayConn{}, // ★★ v4.4 观战者连接表
        }
        s.hub.rooms[rm.ID] = rm
        s.hub.mu.Unlock()

        writeJSON(w, http.StatusOK, map[string]any{
                "room":    roomToResponse(s.cfg.get(), rm),
                "tcpAddr": s.cfg.get().PublicTCP(),
        })
}

// handleJoinRoom 加入房间
// ★★ v4.4：body 可带 {"spectate":true} 观战模式 —— 不受 4 人上限约束
// （满员房间只提供观战）；实际观战人数上限由 TCP 中继（maxSpectators）执行。
func (s *apiServer) handleJoinRoom(w http.ResponseWriter, r *http.Request) {
        user, err := s.requireAuth(r)
        if err != nil {
                writeErr(w, http.StatusUnauthorized, err.Error())
                return
        }
        id := r.PathValue("id")

        // ★★ v4.4：可选 body {"spectate":bool}（兼容旧客户端空 body）
        var req struct {
                Spectate bool `json:"spectate"`
        }
        _ = json.NewDecoder(r.Body).Decode(&req)

        s.hub.mu.Lock()
        rm, ok := s.hub.rooms[id]
        if !ok {
                s.hub.mu.Unlock()
                writeErr(w, http.StatusNotFound, "房间不存在")
                return
        }
        // ★ v4.3：对局中允许加入（后加入 = 连接 1P 画面，存档同步后接入）。
        //   实际人数上限由 TCP 中继（4 人）执行，HTTP 侧同步校验。
        if !req.Spectate && rm.playerCount() >= maxRoomPlayers {
                s.hub.mu.Unlock()
                // ★★ v4.4：满员改为引导观战（响应带 spectateOnly，客户端据此只给观战选项）
                writeJSON(w, http.StatusConflict, map[string]any{
                        "error":       "房间已满（4 个座位都有人）",
                        "spectateOnly": true,
                })
                return
        }
        if req.Spectate && rm.spectatorCount() >= maxSpectators {
                s.hub.mu.Unlock()
                writeErr(w, http.StatusConflict, "观战人数已满")
                return
        }
        for _, c := range rm.conns {
                if c.user.ID == user.ID {
                        s.hub.mu.Unlock()
                        writeErr(w, http.StatusConflict, "你已在房间中")
                        return
                }
        }
        s.hub.mu.Unlock()

        writeJSON(w, http.StatusOK, map[string]any{
                "room":     roomToResponse(s.cfg.get(), rm),
                "tcpAddr":  s.cfg.get().PublicTCP(),
                "spectate": req.Spectate, // 回显模式（客户端据此走观战握手）
        })
}

// handleLeaveRoom 离开房间
func (s *apiServer) handleLeaveRoom(w http.ResponseWriter, r *http.Request) {
        user, err := s.requireAuth(r)
        if err != nil {
                writeErr(w, http.StatusUnauthorized, err.Error())
                return
        }
        id := r.PathValue("id")

        s.hub.mu.Lock()
        rm, ok := s.hub.rooms[id]
        if ok {
                // 如果存在已连接的 relayConn 则关闭，否则直接删房间
                for uid, c := range rm.conns {
                        if uid == user.ID {
                                s.hub.mu.Unlock()
                                s.hub.drop(c)
                                writeJSON(w, http.StatusOK, map[string]any{"ok": true})
                                return
                        }
                }
                // 房间没有该用户的连接，若无玩家则删除
                if rm.playerCount() == 0 {
                        delete(s.hub.rooms, id)
                }
        }
        s.hub.mu.Unlock()
        writeJSON(w, http.StatusOK, map[string]any{"ok": true})
}
