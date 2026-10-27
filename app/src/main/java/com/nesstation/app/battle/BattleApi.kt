package com.nesstation.app.battle

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * 对战平台 HTTP API 客户端。
 * 使用 HttpURLConnection（与项目其他模块一致），无第三方依赖。
 *
 * 所有请求默认超时 10s；ROM 下载单独设置长超时。
 */
class BattleApi(private val ctx: Context) {

    data class ApiException(val code: Int, override val message: String) : Exception(message)

    /** 服务器返回的游戏条目 */
    data class Game(
        val id: String,
        val title: String,
        val platform: String,
        val fileName: String,
        val size: Long,
        val needsBios: List<String>,
        val iconUrl: String = "",
        /** ★★ v4.6 本游戏房间人数上限（服务器 config.json maxPlayers；旧服务器缺省 = 4）。
         *  拳皇等 2 人游戏 = 2：开桌只开 2 座，满员后只能观战。 */
        val maxPlayers: Int = 4
    )

    /** 房间信息
     *  ★ v4.3：players/slots —— 1P 可先单机开玩（房间保持可加入），
     *  2P/3P/4P 中途接入（存档同步）；满员 = 4 人。
     *  ★★ v4.4：spectators —— 观战人数（不占座位；满员房间仍可观战）。
     *  ★★ v4.5：table —— 桌号（开哪桌就是哪桌；0 = 旧服务器未绑定桌号）。
     *  ★★ v4.6：maxPlayers —— 本游戏人数上限（拳皇 2 人房只显示 1P/2P；
     *  旧服务器缺省 = 4，客户端按人数上限摆座位）。 */
    data class Room(
        val id: String,
        val gameId: String,
        val gameTitle: String,
        val host: String,
        val guest: String,
        val status: String,
        val createdAt: Long,
        val players: Int = 0,
        val slots: List<Int> = emptyList(),
        val spectators: Int = 0,
        val table: Int = 0,
        val maxPlayers: Int = 4
    )

    /** 登录/注册结果 */
    data class AuthResult(val token: String, val username: String, val nickname: String)

    // --- 基础请求 ---

    private fun open(method: String, path: String, body: JSONObject? = null, token: String? = null): HttpURLConnection {
        val base = BattleSession.httpBase(ctx)
        val conn = URL(base + path).openConnection() as HttpURLConnection
        conn.requestMethod = method
        conn.connectTimeout = 10_000
        conn.readTimeout = 10_000
        conn.setRequestProperty("Accept", "application/json")
        if (token != null) {
            conn.setRequestProperty("Authorization", "Bearer $token")
        }
        if (body != null) {
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.outputStream.use { it.write(body.toString().toByteArray()) }
        }
        return conn
    }

    private fun readJSON(conn: HttpURLConnection): JSONObject {
        val code = conn.responseCode
        val stream: InputStream = if (code in 200..299) conn.inputStream else conn.errorStream
        val text = stream.bufferedReader().use { it.readText() }
        if (code !in 200..299) {
            val msg = try {
                JSONObject(text).optString("error")
            } catch (_: Exception) { "" }
            throw ApiException(code, if (msg.isNotBlank()) msg else "服务器错误 ($code)")
        }
        return try {
            JSONObject(text)
        } catch (_: Exception) {
            JSONObject()
        }
    }

    /** 健康检查，返回 tcp 中继地址（也用于验证服务器地址可连） */
    fun health(): Pair<String, Int> {
        val conn = open("GET", "/api/health")
        try {
            val json = readJSON(conn)
            return json.optString("tcpAddr", "") to json.optInt("gameCount", 0)
        } finally {
            conn.disconnect()
        }
    }

    // --- 认证 ---

    fun register(username: String, password: String): AuthResult {
        val body = JSONObject().put("username", username).put("password", password)
        val conn = open("POST", "/api/auth/register", body)
        try {
            val json = readJSON(conn)
            return AuthResult(
                token = json.getString("token"),
                username = json.getJSONObject("user").getString("username"),
                nickname = json.getJSONObject("user").getString("nickname")
            )
        } finally {
            conn.disconnect()
        }
    }

    fun login(username: String, password: String): AuthResult {
        val body = JSONObject().put("username", username).put("password", password)
        val conn = open("POST", "/api/auth/login", body)
        try {
            val json = readJSON(conn)
            return AuthResult(
                token = json.getString("token"),
                username = json.getJSONObject("user").getString("username"),
                nickname = json.getJSONObject("user").getString("nickname")
            )
        } finally {
            conn.disconnect()
        }
    }

    // --- 游戏 ---

    fun games(): List<Game> {
        val conn = open("GET", "/api/games")
        try {
            val json = readJSON(conn)
            val arr = json.optJSONArray("games") ?: JSONArray()
            return (0 until arr.length()).map { i ->
                val g = arr.getJSONObject(i)
                val bios = g.optJSONArray("needsBios")?.let { b ->
                    (0 until b.length()).map { b.getString(it) }
                } ?: emptyList()
                Game(
                    id = g.getString("id"),
                    title = g.getString("title"),
                    platform = g.optString("platform", "arcade"),
                    fileName = g.optString("fileName", "${g.getString("id")}.zip"),
                    size = g.optLong("size", 0),
                    needsBios = bios,
                    iconUrl = g.optString("iconUrl", ""),
                    maxPlayers = g.optInt("maxPlayers", 4).coerceIn(1, 4) // ★★ v4.6
                )
            }
        } finally {
            conn.disconnect()
        }
    }

    /** 下载 ROM 到目标文件。onProgress 回调 (已下载, 总长)，总长未知时为 -1。 */
    fun downloadRom(game: Game, dest: File, onProgress: ((Long, Long) -> Unit)? = null) {
        val base = BattleSession.httpBase(ctx)
        val conn = URL("$base/api/games/${game.id}/rom").openConnection() as HttpURLConnection
        conn.requestMethod = "GET"
        conn.connectTimeout = 15_000
        conn.readTimeout = 30_000
        try {
            val code = conn.responseCode
            if (code !in 200..299) {
                val msg = conn.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
                throw ApiException(code, if (msg.isNotBlank()) msg else "下载失败 ($code)")
            }
            val total = conn.contentLengthLong
            dest.parentFile?.mkdirs()
            val tmp = File(dest.parentFile, dest.name + ".part")
            conn.inputStream.use { input ->
                tmp.outputStream().use { output ->
                    val buf = ByteArray(64 * 1024)
                    var done = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        output.write(buf, 0, n)
                        done += n
                        onProgress?.invoke(done, total)
                    }
                }
            }
            // 下载完成，原子替换
            if (tmp.exists() && tmp.length() > 0) {
                if (dest.exists()) dest.delete()
                if (!tmp.renameTo(dest)) {
                    tmp.copyTo(dest, overwrite = true)
                    tmp.delete()
                }
            }
        } finally {
            conn.disconnect()
        }
    }

    // --- 房间 ---

    fun rooms(): List<Room> {
        val conn = open("GET", "/api/rooms")
        try {
            val json = readJSON(conn)
            val arr = json.optJSONArray("rooms") ?: JSONArray()
            return (0 until arr.length()).map { i ->
                val r = arr.getJSONObject(i)
                Room(
                    id = r.getString("id"),
                    gameId = r.optString("gameId", ""),
                    gameTitle = r.optString("gameTitle", ""),
                    host = r.optString("host", ""),
                    guest = r.optString("guest", ""),
                    status = r.optString("status", ""),
                    createdAt = r.optLong("createdAt", 0),
                    players = r.optInt("players", 0),
                    slots = parseSlots(r),
                    spectators = r.optInt("spectators", 0),
                    table = r.optInt("table", 0)
                )
            }
        } finally {
            conn.disconnect()
        }
    }

    /** 创建房间，返回 (房间, 服务器公布的 TCP 中继地址)
     *  ★★ v4.5：table = 点开的桌号（开哪桌就是哪桌；0 = 不指定）。 */
    fun createRoom(gameId: String, token: String, table: Int = 0): Pair<Room, String> {
        val body = JSONObject().put("gameId", gameId).put("table", table)
        val conn = open("POST", "/api/rooms", body, token)
        try {
            val json = readJSON(conn)
            val room = parseRoom(json.getJSONObject("room"))
            return room to json.optString("tcpAddr", "")
        } finally {
            conn.disconnect()
        }
    }

    /** ★★ v4.4：加入房间（spectate=true 观战模式 —— 不受 4 人上限约束，满员仅观战）。 */
    fun joinRoom(roomId: String, token: String, spectate: Boolean = false): Pair<Room, String> {
        val body = if (spectate) JSONObject().put("spectate", true) else null
        val conn = open("POST", "/api/rooms/$roomId/join", body, token)
        try {
            val json = readJSON(conn)
            val room = parseRoom(json.getJSONObject("room"))
            return room to json.optString("tcpAddr", "")
        } finally {
            conn.disconnect()
        }
    }

    fun leaveRoom(roomId: String, token: String) {
        val conn = open("POST", "/api/rooms/$roomId/leave", token = token)
        try {
            readJSON(conn)
        } catch (_: Exception) {
        } finally {
            conn.disconnect()
        }
    }

    private fun parseRoom(o: JSONObject): Room = Room(
        id = o.getString("id"),
        gameId = o.optString("gameId", ""),
        gameTitle = o.optString("gameTitle", ""),
        host = o.optString("host", ""),
        guest = o.optString("guest", ""),
        status = o.optString("status", ""),
        createdAt = o.optLong("createdAt", 0),
        players = o.optInt("players", 0),
        slots = parseSlots(o),
        spectators = o.optInt("spectators", 0),
        table = o.optInt("table", 0),
        maxPlayers = o.optInt("maxPlayers", 4).coerceIn(1, 4) // ★★ v4.6 人数上限（旧服务器 = 4）
    )

    /** ★ v4.3：解析成员槽位列表 [1,2,4]（1P=1 … 4P=4）。 */
    private fun parseSlots(o: JSONObject): List<Int> {
        val arr = o.optJSONArray("slots") ?: return emptyList()
        val out = ArrayList<Int>(arr.length())
        for (i in 0 until arr.length()) {
            val v = arr.optInt(i, 0)
            if (v in 1..4) out.add(v)
        }
        return out
    }
}
