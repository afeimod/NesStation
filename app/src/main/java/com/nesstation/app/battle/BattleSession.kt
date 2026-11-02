package com.nesstation.app.battle

import android.content.Context
import android.content.SharedPreferences
import com.nesstation.app.BuildConfig

/**
 * 对战平台会话 + 服务器配置存储。
 *
 * 服务器地址在打包时通过 BuildConfig 内置（见 app/build.gradle.kts），
 * 用户无需手动配置。设置页/对战平台首页仍保留手动覆盖作为高级选项。
 */
object BattleSession {

    private const val PREFS = "battle_session"

    // 默认服务器地址（打包时内置，可通过 gradle 参数覆盖）。
    val defaultServerHost: String = BuildConfig.BATTLE_SERVER_HOST
    val defaultServerHttpPort: String = BuildConfig.BATTLE_SERVER_HTTP_PORT
    val defaultServerTcpPort: String = BuildConfig.BATTLE_SERVER_TCP_PORT

    private fun prefs(ctx: Context): SharedPreferences =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // --- 服务器地址 ---
    fun getServerHost(ctx: Context): String =
        prefs(ctx).getString("server_host", defaultServerHost) ?: defaultServerHost

    fun getServerHttpPort(ctx: Context): String =
        prefs(ctx).getString("server_http_port", defaultServerHttpPort) ?: defaultServerHttpPort

    fun getServerTcpPort(ctx: Context): String =
        prefs(ctx).getString("server_tcp_port", defaultServerTcpPort) ?: defaultServerTcpPort

    fun saveServer(ctx: Context, host: String, httpPort: String, tcpPort: String) {
        prefs(ctx).edit()
            .putString("server_host", host.trim())
            .putString("server_http_port", httpPort.trim())
            .putString("server_tcp_port", tcpPort.trim())
            .apply()
    }

    /** HTTP 基础地址，如 http://192.168.1.100:8080 */
    fun httpBase(ctx: Context): String {
        val host = getServerHost(ctx)
        val port = getServerHttpPort(ctx)
        if (host.contains("://")) {
            return host.trimEnd('/')
        }
        return "http://$host:$port"
    }

    /** TCP 中继地址，如 192.168.1.100:9090 */
    fun tcpAddr(ctx: Context): String {
        val host = getServerHost(ctx)
        val port = getServerTcpPort(ctx)
        return "$host:$port"
    }

    // --- 账号会话 ---
    fun saveAuth(ctx: Context, token: String, username: String) {
        prefs(ctx).edit()
            .putString("token", token)
            .putString("username", username)
            .apply()
    }

    fun getToken(ctx: Context): String? =
        prefs(ctx).getString("token", null)

    fun getUsername(ctx: Context): String? =
        prefs(ctx).getString("username", null)

    fun isLoggedIn(ctx: Context): Boolean =
        !getToken(ctx).isNullOrBlank()

    fun logout(ctx: Context) {
        prefs(ctx).edit().remove("token").remove("username").apply()
    }

    // --- ★ v64 token 有效期本地预检 ---
    //
    // 服务端 JWT 有效期有限（现 30 天），过去客户端把 token 永久存着、
    // 从不检测过期 —— 过期后"进游戏才报 token 已过期"，用户看不懂。
    // 现在：启动/进游戏前先本地解码 exp，过期即清登录态并引导重新登录。

    /**
     * 解析 JWT payload 里的 exp（过期时间，unix 秒 × 1000 = 毫秒）。
     * 返回 null = 本地没有 token；0 = 格式错/解析失败（服务器也会拒，按过期处理）。
     */
    fun tokenExpMs(ctx: Context): Long? {
        val token = getToken(ctx) ?: return null
        val parts = token.split(".")
        if (parts.size != 3) return 0L
        return try {
            val payload = String(
                android.util.Base64.decode(
                    parts[1],
                    android.util.Base64.URL_SAFE or android.util.Base64.NO_PADDING or android.util.Base64.NO_WRAP
                ),
                Charsets.UTF_8
            )
            org.json.JSONObject(payload).optLong("exp", 0L) * 1000L
        } catch (_: Exception) {
            0L
        }
    }

    /** token 是否已过期（无 token / 格式错 / exp 已过 → true）。 */
    fun isTokenExpired(ctx: Context): Boolean {
        val expMs = tokenExpMs(ctx) ?: return true
        return expMs <= System.currentTimeMillis()
    }

    /**
     * ★ v64 登录态自检：token 存在但已过期 → 清除本地登录态并返回 true
     * （调用方据此回到登录界面）。未登录或 token 仍有效 → false。
     */
    fun clearAuthIfExpired(ctx: Context): Boolean {
        if (getToken(ctx) == null) return false
        if (isTokenExpired(ctx)) {
            logout(ctx)
            return true
        }
        return false
    }
}
