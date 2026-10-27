package main

import (
        "encoding/json"
        "fmt"
        "os"
        "path/filepath"
        "sort"
        "strings"
)

// Game 对战平台游戏条目。ROM 由服务器统一分发（中转下载）。
type Game struct {
        ID       string `json:"id"`       // 游戏 ID（如 kof97）
        Title    string `json:"title"`    // 显示名（如 拳皇97）
        Platform string `json:"platform"` // 平台标识（arcade / gba / ...）
        // ROM 源地址：可以是 GitHub 直链、GitHub Release 资产或任意 HTTP 地址。
        // 服务器作为代理转发给客户端，实现统一分发。
        ROMURL   string `json:"romUrl"`
        FileName string `json:"fileName"` // 客户端保存的文件名（如 kof97.zip）
        Size     int64  `json:"size"`     // ROM 大小（字节），未知为 0
        NeedsBIOS []string `json:"needsBios,omitempty"` // 需要的 BIOS 文件名列表（提示用）
        // 游戏封面图标地址（网络 URL，客户端用 Coil 加载展示在游戏库宫格 / 街机桌面）。
        IconURL string `json:"iconUrl,omitempty"`
        // ★★ v4.6 本游戏房间人数上限（1-4；0/缺省 = 4）。
        //   拳皇等 2 人游戏配 "maxPlayers": 2 —— 房间只开 2 个座位，
        //   第 3 人点进来只能观战；三国战纪等 4 人机默认 4。
        //   服务器读配置直接执行（HTTP 满员判定 + TCP 槽位分配都按它），
        //   并随 games 列表下发给客户端按人数摆座位 UI。
        MaxPlayers int `json:"maxPlayers,omitempty"`
}

// maxPlayersOf 返回游戏的有效人数上限（规范化到 1-4；0/缺省 = 4）。
// 房间创建时快照到 Room.MaxPlayers（后续改配置不影响已开局房间）。
func (g *Game) maxPlayersOf() int {
        if g.MaxPlayers <= 0 {
                return maxRoomPlayers
        }
        if g.MaxPlayers > maxRoomPlayers {
                return maxRoomPlayers
        }
        return g.MaxPlayers
}

// Config 服务器配置
type Config struct {
        // HTTP 服务监听地址，如 ":8080" 或 "0.0.0.0:8080"
        HTTPAddr string `json:"httpAddr"`
        // TCP 帧同步中继监听地址，如 ":9090"
        TCPAddr string `json:"tcpAddr"`
        // 对外公布的地址（客户端连接用）。例如 "game.example.com:8080"。
        // 为空时客户端使用请求中的 Host。
        PublicHTTPAddr string `json:"publicHttpAddr"`
        // 对外公布的 TCP 中继地址，例如 "game.example.com:9090"。
        // 为空时使用 PublicHTTPAddr 的主机名 + TCPAddr 的端口。
        PublicTCPAddr string `json:"publicTcpAddr"`

        JWTSecret string `json:"jwtSecret"` // 签密钥，生产环境务必修改
        DataDir   string `json:"dataDir"`   // 用户数据存放目录

        MaxRooms    int `json:"maxRooms"`    // 最大房间数
        InputDelay  int `json:"inputDelay"`  // 帧同步输入延迟（帧）。★★ v4.4：0=自动（按客户端建议，配合回滚客户端≈2 帧）；>=1=服务器下限；缺省/负数=传统 4 帧
        RomCacheMax int `json:"romCacheMax"` // ROM 内存缓存上限（字节）

        Games []Game `json:"games"` // 对战平台游戏列表
}

// AddressHTTP 返回 HTTP 监听地址
func (c *Config) AddressHTTP() string {
        if c.HTTPAddr == "" {
                return ":8080"
        }
        return c.HTTPAddr
}

// AddressTCP 返回 TCP 监听地址
func (c *Config) AddressTCP() string {
        if c.TCPAddr == "" {
                return ":9090"
        }
        return c.TCPAddr
}

// PublicHTTP 返回对外公布的 HTTP 地址（不含 scheme）
func (c *Config) PublicHTTP() string {
        if c.PublicHTTPAddr != "" {
                return c.PublicHTTPAddr
        }
        return c.AddressHTTP()
}

// PublicTCP 返回对外公布的 TCP 中继地址
func (c *Config) PublicTCP() string {
        if c.PublicTCPAddr != "" {
                return c.PublicTCPAddr
        }
        // 从 HTTP 地址推断主机名
        host := c.PublicHTTPAddr
        // 去掉端口（如果 PublicHTTPAddr 带端口则保留主机部分）
        hostname := host
        if h, _ := splitHostPort(host); h != "" {
                hostname = h
        }
        _, tcpPort := splitHostPort(c.AddressTCP())
        return fmt.Sprintf("%s:%s", hostname, tcpPort)
}

func (c *Config) validate() error {
        if c.JWTSecret == "" {
                return fmt.Errorf("jwtSecret 不能为空")
        }
        if c.InputDelay < 0 {
                return fmt.Errorf("inputDelay 不能为负数")
        }
        if len(c.Games) == 0 {
                return fmt.Errorf("games 列表为空，请配置至少一个游戏")
        }
        seen := map[string]bool{}
        for _, g := range c.Games {
                if g.ID == "" || g.ROMURL == "" {
                        return fmt.Errorf("游戏条目缺少 id 或 romUrl")
                }
                if seen[g.ID] {
                        return fmt.Errorf("游戏 id 重复: %s", g.ID)
                }
                seen[g.ID] = true
        }
        return nil
}

// loadConfig 读取 JSON 配置文件
func loadConfig(path string) (*Config, error) {
        data, err := os.ReadFile(path)
        if err != nil {
                return nil, fmt.Errorf("读取配置文件失败: %w", err)
        }
        return parseConfig(data, path)
}

// templateConfig 首次运行（exe 旁边没有 config.json）自动生成的配置模板。
// ★★ v4.7.1：Windows 下双击 exe 时若 cwd 没有 config.json，旧逻辑
//   log.Fatalf 秒退 —— 窗口一闪而过、什么都看不到（"exe 无法运行/闪退"
//   的第一根源）。现在改为自动生成模板并继续运行，日志里给出醒目指引。
const templateConfig = `{
  "httpAddr": ":8080",
  "tcpAddr": ":9090",
  "jwtSecret": "gamebox-请修改成你自己的密钥",
  "dataDir": "",
  "maxRooms": 200,
  "inputDelay": 0,
  "romCacheMax": 67108864,
  "games": [
    {
      "id": "demo",
      "title": "示例游戏（请换成你的游戏）",
      "platform": "arcade",
      "romUrl": "https://example.com/demo.zip",
      "fileName": "demo.zip",
      "maxPlayers": 4
    }
  ]
}`

// writeTemplateConfig 生成模板配置文件（首次运行引导）。
func writeTemplateConfig(path string) error {
        return os.WriteFile(path, []byte(templateConfig), 0o644)
}

// parseConfig 解析 + 补默认值 + 校验。
// 首次启动（loadConfig）与热重载（configWatcher.check）共用同一套逻辑，
// 保证"重启生效的配置"和"热生效的配置"行为完全一致。
func parseConfig(data []byte, path string) (*Config, error) {
        var cfg Config
        if err := json.Unmarshal(data, &cfg); err != nil {
                return nil, fmt.Errorf("解析配置文件失败: %w", err)
        }
        if cfg.DataDir == "" {
                cfg.DataDir = filepath.Join(filepath.Dir(path), "data")
        }
        if cfg.MaxRooms <= 0 {
                cfg.MaxRooms = 200
        }
        if cfg.RomCacheMax <= 0 {
                cfg.RomCacheMax = 64 * 1024 * 1024
        }
        // ★★ v4.6 人数上限规范化（0/负 = 默认 4；>4 收到 4）。
        //   规范化后 hotreload 的 describeChange / api 下发 / 房间创建都用同一份值。
        for i := range cfg.Games {
                cfg.Games[i].MaxPlayers = cfg.Games[i].maxPlayersOf()
        }
        if err := cfg.validate(); err != nil {
                return nil, err
        }
        return &cfg, nil
}

// ---------------------------------------------------------------------------
// 热重载：新旧配置对比
// ---------------------------------------------------------------------------

// needsRestart 判断新旧配置的差异是否必须重启进程才能生效。
// 只有三类结构性字段需要重启：
//   - httpAddr / tcpAddr：监听器需要重新 bind
//   - jwtSecret：变更即所有已签发 token 作废，需重建 JWT 实例
//   - dataDir：用户存储（users.json）路径变化
//
// 其余字段（games / inputDelay / maxRooms / romCacheMax / publicXxxAddr）
// 全部支持就地热替换，不断开任何连接、不打断进行中的对局。
func needsRestart(old, cur *Config) bool {
        return old.HTTPAddr != cur.HTTPAddr ||
                old.TCPAddr != cur.TCPAddr ||
                old.JWTSecret != cur.JWTSecret ||
                old.DataDir != cur.DataDir
}

// describeChange 生成热重载生效日志的变更摘要（中文，直接可读）
func describeChange(old, cur *Config) string {
        var parts []string

        // 游戏列表 diff（按 id 对比；识别新增/移除/改名/顺序调整）
        oldTitles := gameTitles(old.Games)
        newTitles := gameTitles(cur.Games)
        var added, removed, renamed []string
        for id, t := range newTitles {
                if ot, ok := oldTitles[id]; !ok {
                        added = append(added, titleOrID(t, id))
                } else if ot != t {
                        renamed = append(renamed, fmt.Sprintf("%s→%s", titleOrID(ot, id), titleOrID(t, id)))
                }
        }
        for id, t := range oldTitles {
                if _, ok := newTitles[id]; !ok {
                        removed = append(removed, titleOrID(t, id))
                }
        }
        switch {
        case len(added) > 0 || len(removed) > 0:
                sort.Strings(added)
                sort.Strings(removed)
                parts = append(parts, fmt.Sprintf("游戏 %d → %d", len(old.Games), len(cur.Games)))
                if len(added) > 0 {
                        parts = append(parts, "新增 "+strings.Join(added, "、"))
                }
                if len(removed) > 0 {
                        parts = append(parts, "移除 "+strings.Join(removed, "、"))
                }
        case len(renamed) > 0:
                sort.Strings(renamed)
                parts = append(parts, "游戏改名 "+strings.Join(renamed, "、"))
        case sameGameSet(old.Games, cur.Games) && !sameGameOrder(old.Games, cur.Games):
                parts = append(parts, "游戏顺序调整（客户端显示顺序已更新）")
        }
        // ★★ v4.6 人数上限变化（已开局房间沿用创建时快照，新开房间立即生效）
        oldMax := map[string]int{}
        for _, g := range old.Games {
                oldMax[g.ID] = g.maxPlayersOf()
        }
        var mpChanged []string
        for _, g := range cur.Games {
                if om, ok := oldMax[g.ID]; ok && om != g.maxPlayersOf() {
                        mpChanged = append(mpChanged, fmt.Sprintf("%s %d→%d 人", titleOrID(g.Title, g.ID), om, g.maxPlayersOf()))
                }
        }
        if len(mpChanged) > 0 {
                sort.Strings(mpChanged)
                parts = append(parts, "游戏人数上限 "+strings.Join(mpChanged, "、")+"（新开桌生效）")
        }
        if old.InputDelay != cur.InputDelay {
                parts = append(parts, fmt.Sprintf("inputDelay %d → %d（新对局/重新同步生效）", old.InputDelay, cur.InputDelay))
        }
        if old.MaxRooms != cur.MaxRooms {
                parts = append(parts, fmt.Sprintf("maxRooms %d → %d", old.MaxRooms, cur.MaxRooms))
        }
        if old.RomCacheMax != cur.RomCacheMax {
                parts = append(parts, fmt.Sprintf("romCacheMax %dMB → %dMB", old.RomCacheMax/1024/1024, cur.RomCacheMax/1024/1024))
        }
        if old.PublicHTTPAddr != cur.PublicHTTPAddr {
                parts = append(parts, fmt.Sprintf("publicHttpAddr %q → %q", old.PublicHTTPAddr, cur.PublicHTTPAddr))
        }
        if old.PublicTCPAddr != cur.PublicTCPAddr {
                parts = append(parts, fmt.Sprintf("publicTcpAddr %q → %q", old.PublicTCPAddr, cur.PublicTCPAddr))
        }
        if len(parts) == 0 {
                return "文件内容更新（相关字段无变化）"
        }
        return strings.Join(parts, "；")
}

// gameTitles id → 显示名 映射
func gameTitles(games []Game) map[string]string {
        m := make(map[string]string, len(games))
        for _, g := range games {
                m[g.ID] = g.Title
        }
        return m
}

// sameGameSet 两个游戏列表是否为同一集合（id 不重复时等价于集合相等）
func sameGameSet(a, b []Game) bool {
        if len(a) != len(b) {
                return false
        }
        seen := map[string]bool{}
        for _, g := range a {
                seen[g.ID] = true
        }
        for _, g := range b {
                if !seen[g.ID] {
                        return false
                }
        }
        return true
}

// sameGameOrder 两个游戏列表顺序是否完全一致
func sameGameOrder(a, b []Game) bool {
        if len(a) != len(b) {
                return false
        }
        for i := range a {
                if a[i].ID != b[i].ID {
                        return false
                }
        }
        return true
}

func titleOrID(title, id string) string {
        if title != "" {
                return title
        }
        return id
}
