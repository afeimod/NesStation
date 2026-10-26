package main

import (
        "encoding/json"
        "fmt"
        "io"
        "net/http"
        "net/url"
        "strconv"
        "strings"
        "sync"
        "time"
)

// apiServer HTTP API 处理器
type apiServer struct {
        // cfg 当前配置（热重载落点）：一律通过 cfg.get() 取最新快照，
        // 文件变动时 configStore 整指针原子替换，请求处理中途不受影响。
        cfg      *configStore
        users    *userStore
        hub      *relayHub
        jwt      *jwt
        romCache *romCache
}

type apiError struct {
        Error string `json:"error"`
}

func writeJSON(w http.ResponseWriter, status int, v any) {
        w.Header().Set("Content-Type", "application/json; charset=utf-8")
        w.WriteHeader(status)
        _ = json.NewEncoder(w).Encode(v)
}

func writeErr(w http.ResponseWriter, status int, msg string) {
        writeJSON(w, status, apiError{Error: msg})
}

// splitHostPort 简单拆分 host:port
func splitHostPort(addr string) (string, string) {
        if i := strings.LastIndex(addr, ":"); i >= 0 {
                return addr[:i], addr[i+1:]
        }
        return addr, ""
}

func (s *apiServer) routes() http.Handler {
        mux := http.NewServeMux()

        mux.HandleFunc("GET /api/health", s.handleHealth)
        mux.HandleFunc("POST /api/auth/register", s.handleRegister)
        mux.HandleFunc("POST /api/auth/login", s.handleLogin)
        mux.HandleFunc("GET /api/games", s.handleGames)
        mux.HandleFunc("GET /api/games/{id}/rom", s.handleRomDownload)
        mux.HandleFunc("GET /api/rooms", s.handleListRooms)
        mux.HandleFunc("POST /api/rooms", s.handleCreateRoom)
        mux.HandleFunc("POST /api/rooms/{id}/join", s.handleJoinRoom)
        mux.HandleFunc("POST /api/rooms/{id}/leave", s.handleLeaveRoom)

        return logMiddleware(mux)
}

func logMiddleware(next http.Handler) http.Handler {
        return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
                start := time.Now()
                next.ServeHTTP(w, r)
                fmt.Printf("[%s] %s %s (%s)\n", time.Now().Format("15:04:05"),
                        r.Method, r.URL.Path, time.Since(start).Round(time.Millisecond))
        })
}

func (s *apiServer) handleHealth(w http.ResponseWriter, r *http.Request) {
        cur := s.cfg.get()
        writeJSON(w, http.StatusOK, map[string]any{
                "status":    "ok",
                "server":    "gamebox-battle-server",
                "time":      time.Now().Unix(),
                "tcpAddr":   cur.PublicTCP(),
                "gameCount": len(cur.Games),
        })
}

// --- 认证 ---

type authRequest struct {
        Username string `json:"username"`
        Password string `json:"password"`
}

type authResponse struct {
        Token string `json:"token"`
        User  struct {
                ID       string `json:"id"`
                Username string `json:"username"`
                Nickname string `json:"nickname"`
        } `json:"user"`
}

func (s *apiServer) handleRegister(w http.ResponseWriter, r *http.Request) {
        var req authRequest
        if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
                writeErr(w, http.StatusBadRequest, "请求格式错误")
                return
        }
        req.Username = strings.TrimSpace(req.Username)
        u, err := s.users.register(req.Username, req.Password)
        if err != nil {
                writeErr(w, http.StatusConflict, err.Error())
                return
        }
        s.respondAuth(w, u)
}

func (s *apiServer) handleLogin(w http.ResponseWriter, r *http.Request) {
        var req authRequest
        if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
                writeErr(w, http.StatusBadRequest, "请求格式错误")
                return
        }
        u, err := s.users.verify(strings.TrimSpace(req.Username), req.Password)
        if err != nil {
                writeErr(w, http.StatusUnauthorized, err.Error())
                return
        }
        s.respondAuth(w, u)
}

func (s *apiServer) respondAuth(w http.ResponseWriter, u *User) {
        token := s.jwt.sign(jwtClaims{Sub: u.ID, Username: u.Username})
        var resp authResponse
        resp.Token = token
        resp.User.ID = u.ID
        resp.User.Username = u.Username
        resp.User.Nickname = u.Nickname
        writeJSON(w, http.StatusOK, resp)
}

// requireAuth 从 Authorization: Bearer <token> 解析用户
func (s *apiServer) requireAuth(r *http.Request) (*User, error) {
        h := r.Header.Get("Authorization")
        if !strings.HasPrefix(h, "Bearer ") {
                return nil, fmt.Errorf("缺少 Authorization 头")
        }
        claims, err := s.jwt.verify(strings.TrimPrefix(h, "Bearer "))
        if err != nil {
                return nil, err
        }
        u := s.users.byID(claims.Sub)
        if u == nil {
                return nil, fmt.Errorf("用户不存在")
        }
        return u, nil
}

// --- 游戏 ---

func (s *apiServer) handleGames(w http.ResponseWriter, r *http.Request) {
        type gameResp struct {
                ID        string   `json:"id"`
                Title     string   `json:"title"`
                Platform  string   `json:"platform"`
                FileName  string   `json:"fileName"`
                Size      int64    `json:"size"`
                NeedsBIOS []string `json:"needsBios,omitempty"`
                IconURL   string   `json:"iconUrl,omitempty"`
        }
        cur := s.cfg.get()
        games := make([]gameResp, 0, len(cur.Games))
        for _, g := range cur.Games {
                games = append(games, gameResp{
                        ID:        g.ID,
                        Title:     g.Title,
                        Platform:  g.Platform,
                        FileName:  g.FileName,
                        Size:      g.Size,
                        NeedsBIOS: g.NeedsBIOS,
                        IconURL:   g.IconURL,
                })
        }
        writeJSON(w, http.StatusOK, map[string]any{"games": games})
}

// handleRomDownload ROM 统一分发：服务器中转下载
// 支持 Range 断点续传（若源支持）。客户端用 GET /api/games/{id}/rom 下载。
func (s *apiServer) handleRomDownload(w http.ResponseWriter, r *http.Request) {
        id := r.PathValue("id")
        cur := s.cfg.get()
        var game *Game
        for i := range cur.Games {
                if cur.Games[i].ID == id {
                        game = &cur.Games[i]
                        break
                }
        }
        if game == nil {
                writeErr(w, http.StatusNotFound, "游戏不存在")
                return
        }

        srcURL, err := url.Parse(game.ROMURL)
        if err != nil {
                writeErr(w, http.StatusInternalServerError, "ROM 源地址配置错误")
                return
        }

        // 从缓存读取（内存缓存，避免每次回源）
        if s.romCache != nil {
                if data, ok := s.romCache.get(id); ok {
                        serveBytes(w, r, data, game.FileName)
                        return
                }
        }

        // 构建上游请求，透传 Range
        upstream := &http.Request{
                Method: r.Method,
                URL:    srcURL,
                Header: make(http.Header),
        }
        if rng := r.Header.Get("Range"); rng != "" {
                upstream.Header.Set("Range", rng)
        }
        upstream.Header.Set("User-Agent", "GameBox-Battle-Server/1.0")

        client := &http.Client{Timeout: 30 * time.Minute}
        resp, err := client.Do(upstream)
        if err != nil {
                writeErr(w, http.StatusBadGateway, "无法连接 ROM 源服务器")
                return
        }
        defer resp.Body.Close()

        if resp.StatusCode >= 400 {
                writeErr(w, http.StatusBadGateway, fmt.Sprintf("ROM 源返回 %d", resp.StatusCode))
                return
        }

        // 透传响应头
        if resp.StatusCode == http.StatusPartialContent {
                w.WriteHeader(http.StatusPartialContent)
                w.Header().Set("Content-Range", resp.Header.Get("Content-Range"))
                w.Header().Set("Content-Length", resp.Header.Get("Content-Length"))
        } else {
                w.Header().Set("Content-Disposition", fmt.Sprintf("attachment; filename=\"%s\"", game.FileName))
                if resp.ContentLength > 0 {
                        w.Header().Set("Content-Length", strconv.FormatInt(resp.ContentLength, 10))
                }
        }

        w.Header().Set("Accept-Ranges", "bytes")

        // 流式转发 + 写缓存
        cache := s.romCache != nil
        var buf []byte
        if cache {
                buf = make([]byte, 0, resp.ContentLength)
        }
        _, err = io.Copy(w, io.TeeReader(resp.Body, &cacheWriter{cache: s.romCache, id: id, buf: &buf}))
        if err != nil {
                fmt.Printf("ROM 下载中断: %v\n", err)
        }
}

// cacheWriter 边转发边写入内存缓存
type cacheWriter struct {
        cache *romCache
        id    string
        buf   *[]byte
}

func (cw *cacheWriter) Write(p []byte) (int, error) {
        if cw.cache != nil && cw.buf != nil {
                *cw.buf = append(*cw.buf, p...)
                if len(*cw.buf) >= cw.cache.maxBytes {
                        cw.cache.set(cw.id, *cw.buf)
                        cw.buf = nil
                }
        }
        return len(p), nil
}

func serveBytes(w http.ResponseWriter, r *http.Request, data []byte, fileName string) {
        w.Header().Set("Content-Disposition", fmt.Sprintf("attachment; filename=\"%s\"", fileName))
        w.Header().Set("Content-Length", strconv.Itoa(len(data)))
        w.Header().Set("Accept-Ranges", "bytes")
        // 支持 Range（缓存场景）
        if rng := r.Header.Get("Range"); rng != "" {
                var start, end int64
                n, _ := fmt.Sscanf(rng, "bytes=%d-%d", &start, &end)
                if n == 1 {
                        end = int64(len(data) - 1)
                }
                if start >= 0 && end >= start && end < int64(len(data)) {
                        w.WriteHeader(http.StatusPartialContent)
                        w.Header().Set("Content-Range", fmt.Sprintf("bytes %d-%d/%d", start, end, len(data)))
                        _, _ = w.Write(data[start : end+1])
                        return
                }
        }
        w.WriteHeader(http.StatusOK)
        _, _ = w.Write(data)
}

// romCache 简单的进程内 ROM 缓存
type romCache struct {
        mu       sync.Mutex
        maxBytes int
        entries  map[string][]byte
        order    []string
}

func newRomCache(maxBytes int) *romCache {
        return &romCache{maxBytes: maxBytes, entries: map[string][]byte{}}
}

// setMaxBytes 热重载：调整缓存上限（已有缓存条目保留，下次写入起按新上限淘汰）
func (c *romCache) setMaxBytes(n int) {
        if n <= 0 {
                n = 64 * 1024 * 1024
        }
        c.mu.Lock()
        c.maxBytes = n
        c.mu.Unlock()
}

func (c *romCache) get(id string) ([]byte, bool) {
        if c == nil {
                return nil, false
        }
        c.mu.Lock()
        defer c.mu.Unlock()
        d, ok := c.entries[id]
        return d, ok
}

func (c *romCache) set(id string, data []byte) {
        if c == nil || data == nil {
                return
        }
        c.mu.Lock()
        defer c.mu.Unlock()
        if _, exists := c.entries[id]; !exists {
                c.order = append(c.order, id)
        }
        c.entries[id] = data
        // 简单 LRU：超出上限时清掉最旧的
        var total int
        for _, k := range c.order {
                total += len(c.entries[k])
        }
        for total > c.maxBytes && len(c.order) > 1 {
                oldest := c.order[0]
                total -= len(c.entries[oldest])
                delete(c.entries, oldest)
                c.order = c.order[1:]
        }
}
