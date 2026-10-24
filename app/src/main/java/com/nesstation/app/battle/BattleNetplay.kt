package com.nesstation.app.battle

import org.json.JSONObject
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetSocketAddress
import java.net.Socket
import kotlin.concurrent.thread

/**
 * 帧同步中继 TCP 客户端。
 *
 * 协议（JSON 行，\n 分隔）：
 *   -> {"type":"hello","token":"...","roomId":"..."}   握手（第一行）
 *   <- {"type":"hello","msg":"host"|"guest","pad":4}   角色分配 + 输入延迟帧数
 *   -> {"type":"ping","ts":纳秒}                          RTT 测量
 *   <- {"type":"pong","ts":回显}                          服务器回显时间戳
 *   -> {"type":"ready","pad":建议延迟帧}                   引擎加载完毕，等待 start
 *   <- {"type":"peer_ready","msg":...}                     对方也就绪了
 *   <- {"type":"start","pad":最终延迟帧}                    双方就绪，同步开跑
 *   -> {"type":"input","frame":N,"pad":0x...}              本端输入
 *   <- {"type":"input","frame":N,"pad":0x...}              对方输入
 *   <- {"type":"peer_joined"/"peer_left", "msg":...}
 *   <- {"type":"error","msg":"..."}
 *
 * ★ 同步启动：客户端连接后先把引擎加载完，发 ready；服务器等双方 ready
 *   同时广播 start → 两端从帧 0 同步开跑（修复双方启动时差导致的水久 desync）。
 * inputDelay 最终值以 start 消息为准（= max(双方建议, 服务器配置)，两端一致）。
 */
class BattleNetplay(
    private val host: String,
    private val port: Int,
    private val token: String,
    private val roomId: String,
    private val listener: Listener
) {

    interface Listener {
        /** 收到对方的一帧输入 */
        fun onRemoteInput(frame: Long, pad: Int)
        /**
         * 服务器 hello 回执已收到 —— TCP 握手 + JWT 验证 + 房间加入全部完成。
         * @param role "host" 或 "guest"（房主 = host = 1P）
         * @param inputDelay 服务器配置的输入延迟帧数（参考值，最终以 onStart 为准）
         */
        fun onReady(role: String, inputDelay: Int)
        /**
         * ★ 同步开始信号：双方都已 ready，服务器同时广播了 start。
         * 收到本回调后引擎才允许从帧 0 开始模拟 —— 这是消除双方
         * 启动时差（ROM 加载耗时不同 → 帧计数错位 → 水久 desync）的关键。
         * @param inputDelay 最终输入延迟帧数（两端一致，由服务器裁决）
         */
        fun onStart(inputDelay: Int)
        /**
         * 对方已 ready（引擎已加载完，正在等 start）。
         */
        fun onPeerReady(username: String)
        /** 对方加入 */
        fun onPeerJoined(username: String)
        /** 对方断开 */
        fun onPeerLeft(username: String)
        /** 连接被拒绝 / 协议错误 */
        fun onError(message: String)
        /** 连接关闭（非主动） */
        fun onDisconnected()
    }

    @Volatile private var socket: Socket? = null
    @Volatile private var reader: BufferedReader? = null
    @Volatile private var writer: BufferedWriter? = null
    @Volatile private var running = false
    private var thread: Thread? = null

    @Volatile var role: String = "host"
        private set

    /** 输入延迟帧数（由服务器在 hello 响应中通过 pad 字段传递）。 */
    @Volatile var inputDelay: Int = 4
        private set

    val isConnected: Boolean get() = running

    fun connect() {
        if (running) return
        running = true
        thread = thread(name = "battle-netplay", isDaemon = true) {
            try {
                val s = Socket()
                s.connect(InetSocketAddress(host, port), 15_000)
                s.tcpNoDelay = true
                socket = s
                reader = BufferedReader(InputStreamReader(s.getInputStream()))
                writer = BufferedWriter(OutputStreamWriter(s.getOutputStream()))

                // 握手
                val hello = JSONObject()
                    .put("type", "hello")
                    .put("token", token)
                    .put("roomId", roomId)
                sendLine(hello.toString())

                readLoop()
            } catch (e: Exception) {
                if (running) {
                    listener.onError("无法连接对战服务器: ${e.message}")
                }
            } finally {
                running = false
                closeQuietly()
                if (thread === Thread.currentThread()) {
                    listener.onDisconnected()
                }
            }
        }
    }

    private fun readLoop() {
        val r = reader ?: return
        while (running) {
            val line = try {
                r.readLine()
            } catch (_: Exception) {
                break
            }
            if (line == null) break
            val trimmed = line.trim()
            if (trimmed.isEmpty()) continue
            try {
                handleMessage(JSONObject(trimmed))
            } catch (_: Exception) {
            }
        }
    }

    private fun handleMessage(msg: JSONObject) {
        when (msg.optString("type")) {
            "hello" -> {
                role = msg.optString("msg", "host")
                // 服务器通过 pad 字段传递 inputDelay
                inputDelay = msg.optInt("pad", 4).coerceIn(0, 16)
                // 通知 listener：握手完成，可以开始对战了
                listener.onReady(role, inputDelay)
            }
            "start" -> {
                // ★ 同步开始：双方就绪，服务器同时广播（最终 inputDelay 两端一致）
                val finalDelay = msg.optInt("pad", 4).coerceIn(0, 16)
                inputDelay = finalDelay
                listener.onStart(finalDelay)
            }
            "peer_ready" -> listener.onPeerReady(msg.optString("msg", ""))
            "input" -> {
                listener.onRemoteInput(msg.optLong("frame", 0), msg.optInt("pad", 0))
            }
            "peer_joined" -> listener.onPeerJoined(msg.optString("msg", ""))
            "peer_left" -> listener.onPeerLeft(msg.optString("msg", ""))
            "error" -> listener.onError(msg.optString("msg", "服务器错误"))
            "pong" -> {
                // RTT 测量回显：交给等待中的测量请求
                val ts = msg.optLong("ts", 0L)
                val cb = pongWaiter.getAndSet(null)
                cb?.invoke(ts)
            }
        }
    }

    /** pong 等待器（单次 RTT 测量；原子引用避免并发乱序） */
    private val pongWaiter = java.util.concurrent.atomic.AtomicReference<((Long) -> Unit)?>()

    /**
     * 单次 RTT 测量：发 ping（带本地纳秒时间戳），服务器回显后回调往返耗时(ms)。
     * [timeoutMs] 内未收到 pong 回调 null。同一时刻只支持一个在途测量。
     */
    fun measureRtt(timeoutMs: Long = 1200, onDone: (rttMs: Long?) -> Unit) {
        val sentAt = System.nanoTime()
        pongWaiter.set { echoedTs -> onDone((System.nanoTime() - echoedTs) / 1_000_000L) }
        sendLine(JSONObject().put("type", "ping").put("ts", sentAt).toString())
        // 超时看门狗：未回显则回调 null（复用共享调度线程，避免每次新建）
        rttWatchdog.schedule({
            val cb = pongWaiter.getAndSet(null)
            if (cb != null) onDone(null)
        }, timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
    }

    /** RTT 超时看门狗调度器（守护线程，懒加载） */
    private val rttWatchdog by lazy {
        java.util.concurrent.Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "battle-rtt-watchdog").apply { isDaemon = true }
        }
    }

    fun sendInput(frame: Long, pad: Int) {
        val line = JSONObject()
            .put("type", "input")
            .put("frame", frame)
            .put("pad", pad)
            .toString()
        sendLine(line)
    }

    /** 通知服务器：我已就绪（引擎已加载完、帧循环就位，等 start）。带建议 inputDelay（帧）。 */
    fun sendReady(suggestedDelay: Int = 0) {
        sendLine(JSONObject().put("type", "ready").put("pad", suggestedDelay.coerceIn(0, 16)).toString())
    }

    fun sendPing() {
        sendLine(JSONObject().put("type", "ping").toString())
    }

    fun sendBye() {
        sendLine(JSONObject().put("type", "bye").toString())
    }

    @Synchronized
    private fun sendLine(line: String) {
        val w = writer ?: run {
            android.util.Log.w("BattleNetplay", "sendLine: writer is null, line dropped: $line")
            return
        }
        try {
            w.write(line)
            w.write("\n")
            w.flush()
        } catch (e: Exception) {
            android.util.Log.w("BattleNetplay", "sendLine failed: ${e.message}")
        }
    }

    private fun closeQuietly() {
        try { writer?.close() } catch (_: Exception) {}
        try { reader?.close() } catch (_: Exception) {}
        try { socket?.close() } catch (_: Exception) {}
        writer = null
        reader = null
        socket = null
    }

    fun close() {
        running = false
        sendBye()
        closeQuietly()
        thread?.interrupt()
    }
}
