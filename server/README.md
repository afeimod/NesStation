# GameBox 对战平台服务器

零第三方依赖（仅 Go 标准库），负责：虚拟账号、游戏/ROM 统一分发、房间管理、帧同步中继。

## 快速开始

```bash
cd server
go build -o gamebox-server .          # 或直接: go build .
./gamebox-server -config config.json
```

默认监听：
- HTTP API: `:8080`
- 帧同步 TCP 中继: `:9090`

## 配置文件 config.json

```jsonc
{
  "httpAddr": ":8080",                // HTTP 监听地址
  "tcpAddr": ":9090",                 // TCP 帧同步中继监听地址
  "publicHttpAddr": "your-server-ip-or-domain:8080", // 对外公布的 HTTP 地址（客户端用）
  "publicTcpAddr": "your-server-ip-or-domain:9090",  // 对外公布的 TCP 地址（客户端用）
  "jwtSecret": "change-me-to-a-long-random-string",  // 生产环境务必修改！
  "dataDir": "data",                  // 用户数据（users.json）目录
  "maxRooms": 200,                    // 最大房间数
  "inputDelay": 4,                    // 帧同步输入延迟（帧），数值越大越抗网络抖动
  "romCacheMax": 67108864,            // ROM 内存缓存上限（字节），默认 64MB
  "games": [                          // 对战平台游戏列表（统一 ROM 分发）
    {
      "id": "kof97",
      "title": "拳皇97",
      "platform": "arcade",
      "romUrl": "https://github.com/YOUR_USER/GameBox-Roms/releases/download/v1.0/kof97.zip",
      "fileName": "kof97.zip",
      "size": 0,                       // 可选，字节数
      "needsBios": ["neogeo.zip"]      // 可选，提示信息
    }
  ]
}
```

### ROM 分发说明

- ROM 由你统一托管（建议 GitHub Release 或任意直链），**服务器只做中转代理**，客户端不会直接访问 GitHub。
- 客户端调用 `GET /api/games/{id}/rom` 下载，服务器从 `romUrl` 拉取并流式转发，支持 Range 断点续传。
- 新增游戏只需在 `games` 数组里加一项，客户端会自动显示。

### 服务器 IP/域名

`publicHttpAddr` 和 `publicTcpAddr` 填你的服务器公网 IP 或域名。
如果服务器有防火墙，记得放行 **8080（HTTP）** 和 **9090（TCP）** 端口。

## API 一览

| 方法 | 路径 | 说明 |
|---|---|---|
| GET  | `/api/health` | 健康检查 |
| POST | `/api/auth/register` | 注册 `{username, password}` |
| POST | `/api/auth/login` | 登录 `{username, password}` → `{token, user}` |
| GET  | `/api/games` | 对战平台游戏列表 |
| GET  | `/api/games/{id}/rom` | 下载 ROM（服务器中转） |
| GET  | `/api/rooms` | 房间列表 |
| POST | `/api/rooms` | 创建房间 `{gameId}` → `{room, tcpAddr}` |
| POST | `/api/rooms/{id}/join` | 加入房间 |
| POST | `/api/rooms/{id}/leave` | 离开房间 |

鉴权：除 `health`、`register`、`login`、`games`、`games/{id}/rom`、`rooms`(GET) 外，
均需请求头 `Authorization: Bearer <token>`。

## 帧同步中继协议（TCP，JSON 行协议）

客户端连上 TCP 端口后，每一行是一个 JSON 消息（`\n` 分隔）：

**客户端 → 服务器**

```json
{"type":"hello","token":"<jwt>","roomId":"<房间ID>"}   // 握手（第一行必须）
{"type":"input","frame":123,"pad":0x00FF}               // 本端输入（每帧发送）
{"type":"ready"}                                        // ROM 加载完成
{"type":"ping"}
{"type":"bye"}
```

**服务器 → 客户端**

```json
{"type":"hello","msg":"host"|"guest"}    // 角色分配
{"type":"peer_joined","msg":"<对方用户名>"}
{"type":"start","msg":"<输入延迟帧数>"}   // 双方到齐，开始对战
{"type":"peer_ready"}                    // 对方已就绪
{"type":"input","frame":123,"pad":0x00FF} // 对方输入
{"type":"peer_left","msg":"<用户名>"}     // 对方断开
{"type":"pong"}
{"type":"error","msg":"..."}
```

### 帧同步策略（客户端负责）

两台设备各自本地运行模拟器（同一 ROM、同一核心 → 确定性），服务器只转发输入。
- 客户端以固定 60fps 推进帧计数 `frame`。
- 发送本端输入时带上 `frame` 号；收到对方输入后按帧号缓冲。
- 输入延迟（默认 4 帧）：双方都晚 4 帧应用输入，容忍网络抖动。
- 画面渲染在本地，无需服务器参与。

## 数据存储

- 用户账号：`data/users.json`（加盐 HMAC-SHA256 迭代哈希，非明文）。
- 无数据库依赖，重启不丢数据。
