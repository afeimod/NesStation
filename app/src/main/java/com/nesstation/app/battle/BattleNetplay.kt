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
 *   -> {"type":"hello","token":"...","roomId":"..."}          握手（第一行）
 *   <- {"type":"hello","msg":"host"|"guest","pad":延迟帧,
 *       "slot":1..4,"players":[1,2..],"playing":bool}          角色+槽位+成员+是否已开跑
 *   -> {"type":"ping","ts":纳秒}                                RTT 测量
 *   <- {"type":"pong","ts":回显}                                服务器回显时间戳
 *   -> {"type":"ready","pad":建议延迟帧}                        引擎加载完毕，等待 start
 *   <- {"type":"peer_ready","msg":...}                          对方也就绪了
 *   <- {"type":"start","pad":最终延迟帧,"players":[..]}          开跑（全员就绪 / 房主单开）
 *   -> {"type":"input","frame":N,"pad":0x..,"slot":1..4}        本端输入（★ v4.3 带槽位）
 *   <- {"type":"input","frame":N,"pad":0x..,"slot":1..4}        其他玩家输入
 *   <- {"type":"peer_joined"/"peer_left","msg":...,"slot":N}
 *   ★★ v4.3 后加入（1P 先单机开玩，2P/3P/4P 中途接入）：
 *   <- {"type":"pause"}                                         全体暂停在各自下一帧
 *   <- {"type":"state_req"}                                     仅房主：暂停后发存档
 *   -> {"type":"state","frame":F,"data":"b64","hist":{...}}     房主存档+近16帧输入
 *   <- {"type":"state","frame":F,"data":...,"hist":...}         转发给其他成员
 *   -> {"type":"state_ok"}                                      新玩家已装载存档
 *   <- {"type":"resume","frame":F,"pad":最终延迟,"players":[..],
 *       "data":...,"hist":...}                                  全员装载后同帧恢复
 *   <- {"type":"error","msg":"..."}
 *
 * ★ 同步启动：客户端连接后先把引擎加载完，发 ready；服务器在
 *   - 房主单人 → 立即 start（1P 直接单机开玩，不等后来者）；
 *   - 多人等待 → 全员 ready 后同时广播 start；
 *   - 对局中有人 ready → pause + 存档同步 + resume（后加入接入）。
 */
class BattleNetplay(
    private val host: String,
    private val port: Int,
    private val token: String,
    private val roomId: String,
    private val listener: Listener
) {

    interface Listener {
        /** 收到其他玩家的一帧输入（★ v4.3：带发送者槽位 1-4） */
        fun onRemoteInput(frame: Long, pad: Int, slot: Int)
        /**
         * 服务器 hello 回执已收到 —— TCP 握手 + JWT 验证 + 房间加入全部完成。
         * @param role "host" 或 "guest"（房主 = host = 1P）
         * @param inputDelay 服务器配置的输入延迟帧数（参考值，最终以 onStart/onResume 为准）
         * @param slot 本端玩家槽位（1P=1 … 4P=4）
         * @param players 当前房间成员槽位列表
         * @param playing 房间是否已在跑（true = 后加入：收到存档前不许开跑）
         */
        fun onReady(role: String, inputDelay: Int, slot: Int, players: List<Int>, playing: Boolean)
        /**
         * ★ 同步开始信号（新房全员就绪 / 房主单开）。
         * @param inputDelay 最终输入延迟帧数（各端一致，由服务器裁决）
         * @param players 本局活跃槽位（各端一致）
         */
        fun onStart(inputDelay: Int, players: List<Int>)
        /**
         * ★★ v4.3 后加入：全员暂停（有新玩家正在接入，画面短暂定格）。
         * 运行中的引擎线程应在下一帧前挂起；房主稍后会收到 [onStateRequest]。
         */
        fun onPause()
        /**
         * ★★ v4.3 后加入：仅房主收到 —— 本端已暂停在帧 F 后，生成即时存档并
         * [sendState] 上报（data + 近 16 帧各槽位输入）。此时引擎线程必须保持
         * 暂停，直到 [onResume]。
         */
        fun onStateRequest()
        /**
         * ★★ v4.3 后加入：收到存档（服务器转发的房主 state）。
         * 引擎装载存档 → 帧计数对齐到 frame → sendStateOk。
         */
        fun onStateData(frame: Long, dataBase64: String, hist: Map<Int, List<Int>>?, histEnd: Long)
        /**
         * ★★ v4.3 后加入：全员恢复信号 —— 各端装载同一份存档、以 frame 对齐、
         * 按统一 inputDelay 继续。data 为空 = 直接恢复（无存档，如同步中断线兜底）。
         */
        fun onResume(frame: Long, inputDelay: Int, players: List<Int>,
                     dataBase64: String?, hist: Map<Int, List<Int>>?, histEnd: Long)
        /** 对方已 ready（引擎已加载完，正在等 start）。 */
        fun onPeerReady(username: String)
        /** 对方加入（slot = 其槽位） */
        fun onPeerJoined(username: String, slot: Int)
        /** 对方断开（slot = 其槽位） */
        fun onPeerLeft(username: String, slot: Int)
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

    /** ★ v4.3：本端玩家槽位（1P=1 … 4P=4），服务器在 hello 回执里下发。 */
    @Volatile var mySlot: Int = 1
        private set

    /** ★ v4.3：房间是否已在跑（后加入：等存档同步，不许自行开跑）。 */
    @Volatile var roomPlaying: Boolean = false
        private set

    /** 输入延迟帧数（由服务器在 hello/start/resume 中下发）。 */
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
                reader = BufferedReader(InputStreamReader(s.getInputStream()), 1 shl 20)
                writer = BufferedWriter(OutputStreamWriter(s.getOutputStream()), 1 shl 20)

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

    private fun parsePlayers(msg: JSONObject): List<Int> {
        val arr = msg.optJSONArray("players") ?: return emptyList()
        val out = ArrayList<Int>(arr.length())
        for (i in 0 until arr.length()) {
            val v = arr.optInt(i, 0)
            if (v in 1..4) out.add(v)
        }
        return out
    }

    /** 解析 hist 字段：{"槽位":[pad,...]}（按帧序，止于 histEnd 前一帧） */
    private fun parseHist(msg: JSONObject): Map<Int, List<Int>>? {
        val h = msg.optJSONObject("hist") ?: return null
        val out = HashMap<Int, List<Int>>()
        for (key in h.keys()) {
            val slot = key.toIntOrNull() ?: continue
            val arr = h.optJSONArray(key) ?: continue
            val pads = ArrayList<Int>(arr.length())
            for (i in 0 until arr.length()) pads.add(arr.optInt(i, 0))
            out[slot] = pads
        }
        return out
    }

    private fun handleMessage(msg: JSONObject) {
        when (msg.optString("type")) {
            "hello" -> {
                role = msg.optString("msg", "host")
                inputDelay = msg.optInt("pad", 4).coerceIn(0, 16)
                mySlot = msg.optInt("slot", 1).coerceIn(1, 4)
                roomPlaying = msg.optBoolean("playing", false)
                listener.onReady(role, inputDelay, mySlot, parsePlayers(msg), roomPlaying)
            }
            "start" -> {
                // ★ 同步开始：全员就绪或房主单开（最终 inputDelay 各端一致）
                val finalDelay = msg.optInt("pad", 4).coerceIn(0, 16)
                inputDelay = finalDelay
                listener.onStart(finalDelay, parsePlayers(msg))
            }
            "pause" -> listener.onPause()
            "state_req" -> listener.onStateRequest()
            "state" -> {
                listener.onStateData(
                    msg.optLong("frame", 0),
                    msg.optString("data", ""),
                    parseHist(msg),
                    msg.optLong("histEnd", msg.optLong("frame", 0))
                )
            }
            "state_ok" -> {
                // 服务器内部状态流转；客户端不需处理（resume 才是恢复信号）
            }
            "resume" -> {
                val finalDelay = msg.optInt("pad", 4).coerceIn(0, 16)
                inputDelay = finalDelay
                val data = msg.optString("data", "")
                listener.onResume(
                    msg.optLong("frame", 0),
                    finalDelay,
                    parsePlayers(msg),
                    if (data.isNullOrEmpty()) null else data,
                    parseHist(msg),
                    msg.optLong("histEnd", msg.optLong("frame", 0))
                )
            }
            "peer_ready" -> listener.onPeerReady(msg.optString("msg", ""))
            "input" -> {
                listener.onRemoteInput(
                    msg.optLong("frame", 0),
                    msg.optInt("pad", 0),
                    msg.optInt("slot", 0).coerceIn(0, 4)
                )
            }
            "peer_joined" -> listener.onPeerJoined(msg.optString("msg", ""), msg.optInt("slot", 0))
            "peer_left" -> listener.onPeerLeft(msg.optString("msg", ""), msg.optInt("slot", 0))
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

    /** ★ v4.3：发送本端输入（带槽位，服务器按槽位广播给其他成员）。 */
    fun sendInput(frame: Long, pad: Int, slot: Int) {
        val line = JSONObject()
            .put("type", "input")
            .put("frame", frame)
            .put("pad", pad)
            .put("slot", slot)
            .toString()
        sendLine(line)
    }

    /**
     * ★★ v4.3 后加入：房主上报即时存档。
     * @param frame 暂停帧号（存档代表 [0, frame) 已完成；全员从 frame 恢复）
     * @param dataBase64 即时存档字节（FBNeo 存档一般几十 KB，base64 后一行发出）
     * @param hist 槽位 -> 近 16 帧 pad 序列（按帧序，止于 frame 前一帧）——
     *        恢复后 inputDelay 窗口内的帧要用到这些历史输入
     * @param histEnd 等于 frame（服务器原样转发）
     */
    fun sendState(frame: Long, dataBase64: String, hist: Map<Int, List<Int>>) {
        val histJson = JSONObject()
        for ((slot, pads) in hist) {
            histJson.put(slot.toString(), pads)
        }
        val line = JSONObject()
            .put("type", "state")
            .put("frame", frame)
            .put("data", dataBase64)
            .put("hist", histJson)
            .put("histEnd", frame)
            .toString()
        sendLine(line)
    }

    /** ★★ v4.3 后加入：新玩家已装载存档，等服务器广播 resume。 */
    fun sendStateOk() {
        sendLine(JSONObject().put("type", "state_ok").toString())
    }

    /** 通知服务器：我已就绪（引擎已加载完、帧循环就位，等 start/resume）。带建议 inputDelay（帧）。 */
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
