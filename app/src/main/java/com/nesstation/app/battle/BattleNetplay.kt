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
 *
 * ★★ v4.5 服务器侧存档同步（联机平台模式）：
 *   - 房主周期 sendStateUpload 快照 → 服务器缓存（引擎就绪即首份，之后 6-15s 一份）；
 *   - 加入者优先从服务器缓存档接入（房主在 → state_req 新鲜档零回退）；
 *   - 同步全程看门狗：房主 sendStateFail / 超时 / 掉线 → 服务器按缓存档收尾，
 *     无档可用时全员重开（v≥45）或接入者收 error 劝退 —— 绝不卡在“同步中”。
 *
 * ★★ v4.5.1：
 *   - hello 带 v=45（协议版本 —— 服务器据此判断能否广播 restart 全员重开）；
 *   - ready 带 stateCap（出档能力探测结果：1 能 / -1 不能 —— 核心不支持快照的
 *     游戏服务器直接走全员重开，不再浪费出档尝试）；
 *   - 新消息 restart：全员重开（回开机状态、重新就绪、从帧 0 同步开跑）。
 *
 * ★★ v4.6：
 *   - 协议版本 v=46；新消息 restart_req：成员同步画面装载失败 / 核心复位失败时
 *     的自救通道 —— 不再断开退出，而是请求服务器编排全员重开（全员 v≥45 才生效；
 *     混合旧客户端房间服务器回 error 说明）；
 *   - 游戏人数上限（config.json maxPlayers）随 /api/games、房间列表下发 ——
 *     拳皇等 2 人游戏只开 2 座（服务器强制 + 客户端按人数摆座位 UI）。
 *
 * ★★ v4.4：
 *   - hello 可带 spectate=true（观战加入：不占 1P-4P 槽位、只收不发）；
 *   - hello 可带 want=2/3/4（用户在大厅选的座位，空闲则满足）；
 *   - peer_joined/peer_left 新增 spec 字段（true = 观战者加入/离开）。
 *
 * ★★ v51 游戏内聊天：
 *   -> {"type":"chat","msg":"文本"}                    发聊天（玩家/观战者均可）
 *   <- {"type":"chat","msg":"文本","from":"名字",
 *       "slot":N,"spec":bool}                            服务器盖戳后转发给其他成员
 */
class BattleNetplay(
    private val host: String,
    private val port: Int,
    private val token: String,
    private val roomId: String,
    private val listener: Listener,
    /** ★★ v4.4：观战模式（hello 带 spectate=true；服务器回执角色 = spectator、槽位 0） */
    private val spectate: Boolean = false,
    /** ★★ v4.4：期望座位 2/3/4（0 = 自动取下一空闲位；服务器分配结果以 hello 回执 slot 为准） */
    private val wantSlot: Int = 0
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
         * ★★ v52：catchup=true = 追赶模式存档（不暂停流 —— 仅接入者收到）：
         * 装档后不等 resume，直接离屏快放追赶，追平 histEnd 后自行 sendStateOk
         * （服务器此时广播座位激活）。hist 覆盖 [frame-16, histEnd)。
         */
        fun onStateData(frame: Long, dataBase64: String, hist: Map<Int, List<Int>>?, histEnd: Long,
                        catchup: Boolean = false)

        /**
         * ★★ v52 座位延迟激活：追赶接入者已追平（或观战者转座）—— [slot]
         * 座位从 [frame] 之后开始被全员等待其输入。players = 最新活跃座位表。
         */
        fun onSlotActive(frame: Long, slot: Int, players: List<Int>) {}

        /**
         * ★★ v52 观战转座确认（仅转座者本人收到）：服务器已把本端从
         * 观战切到 [slot] 座 —— 解锁输入采样，从下一帧开始正常采样发送。
         */
        fun onSeatGranted(frame: Long, slot: Int, players: List<Int>) {}
        /**
         * ★★ v4.3 后加入：全员恢复信号 —— 各端装载同一份存档、以 frame 对齐、
         * 按统一 inputDelay 继续。data 为空 = 直接恢复（无存档，如同步中断线兜底）。
         */
        fun onResume(frame: Long, inputDelay: Int, players: List<Int>,
                     dataBase64: String?, hist: Map<Int, List<Int>>?, histEnd: Long)
        /**
         * ★★ v4.5.1 全员重开（本作核心不支持画面快照 —— 服务器编排）：
         * 复位引擎到开机态 → 回启动门 → 重新 ready；服务器等全员重新就绪后
         * 广播 start，全员从帧 0 同步开跑（街机语义：新人加入 = 新的一局）。
         */
        fun onRestart(inputDelay: Int, players: List<Int>)
        /** 对方已 ready（引擎已加载完，正在等 start）。 */
        fun onPeerReady(username: String)
        /** 对方加入（slot = 其槽位；★ v4.4 spec=true 为观战者，不占槽位） */
        fun onPeerJoined(username: String, slot: Int, spec: Boolean = false)
        /** 对方断开（slot = 其槽位；★ v4.4 spec=true 为观战者离开）
         *  ★★ v48：lastFrame = 服务器 peer_left 携带的该槽位最后一次中继输入帧号
         *  （-1 = 旧服务器未携带）。≤ lastFrame 的输入仍在途（TCP 有序必达，等到
         *  再用），> lastFrame 的帧确定性归零 —— 各端对离队槽位的输入序列严格一致。 */
        fun onPeerLeft(username: String, slot: Int, spec: Boolean = false, lastFrame: Long = -1L)
        /** ★★ v51 游戏内聊天收到（from = 发送者名字（服务器盖戳）；slot = 其座位；
         *  spec = 观战者）。*/
        fun onChat(from: String, text: String, slot: Int, spec: Boolean = false) {}
        /** 连接被拒绝 / 协议错误 */
        fun onError(message: String)
        /** 连接关闭（非主动） */
        fun onDisconnected()
    }

    companion object {
        /** ★★ v48 客户端协议版本（hello.v）。
         *  48 = 3/4 人房不同步根治：peer_left 携带离队槽位最后一次中继的输入
         *  帧号（frame 字段）—— 客户端对 ≤ frame 的在途输入等到再用、
         *  > frame 的确定性归零，各端输入序列严格一致（旧版各端自行清零 →
         *  单端永久发散）；配合服务器异步发送队列（慢客户端不再阻塞其他人）。
         *  47 = 新一代 restart 全员重开：网络帧号改由控制器内部计数器发号
         *  （旧版重开后各端引擎帧号错位 → “1P 等 2P、2P 等 1P” 互等死锁）；
         *  非街机核心（NES/SFC/GBA/MD/PCE）快照同步降级 —— 一律上报
         *  stateCap=-1，中途接入走“全员同时重开”（4.2 语义）。
         *  服务器对 v<47 的房间不再广播 restart（旧客户端重开语义不兼容）。 */
        // ★★ v52 小马模拟器式“中途加入不暂停追赶”：
        //   state.catchup=true 追赶模式存档（仅接入者收到：装档 + 输入历史
        //   离屏快放追赶，追平 state_ok 后座位激活 —— 原玩家全程不暂停）；
        //   slot_active 座位延迟激活；seat_granted/take_seat 观战者热转座。
        //   服务器对房间内全员 v≥52 才启用追赶；混合旧客户端自动退回 v4.5 暂停同步。
        const val PROTO_VERSION = 52
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

    /** ★★ v4.4：本端是否观战者（服务器 hello 回执角色 = spectator）。 */
    val isSpectator: Boolean get() = role == "spectator"

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
                // ★★ v48 网络调优（联通/移动等运营商链路）：
                //   - keepAlive：运营商 CGNAT 会回收"空闲"TCP 映射（暂停/存档同步
                //     等间歇期无输入流量时），keepalive 探测包维持映射不中断；
                //   - 收发缓冲加大：跨网链路拥塞时降低丢包重传概率。
                try {
                    s.keepAlive = true
                    s.receiveBufferSize = 512 * 1024
                    s.sendBufferSize = 512 * 1024
                } catch (_: Throwable) { /* 部分系统不支持，忽略 */ }
                socket = s
                reader = BufferedReader(InputStreamReader(s.getInputStream()), 1 shl 20)
                writer = BufferedWriter(OutputStreamWriter(s.getOutputStream()), 1 shl 20)

                // 握手（★★ v4.4：spectate=true 观战加入；want=2/3/4 指定座位；
                // ★★ v4.5.1：v=45 协议版本 —— restart 全员重开支持判定）
                val hello = JSONObject()
                    .put("type", "hello")
                    .put("token", token)
                    .put("roomId", roomId)
                    .put("v", PROTO_VERSION)
                if (spectate) hello.put("spectate", true)
                if (wantSlot in 2..4) hello.put("want", wantSlot)
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
                    msg.optLong("histEnd", msg.optLong("frame", 0)),
                    // ★★ v52：catchup=true = 追赶模式存档（装档 + 离屏快放追赶）
                    msg.optBoolean("catchup", false)
                )
            }
            // ★★ v52 座位延迟激活（追赶接入者追平 / 观战者转座）：
            //   [slot] 座位从 frame 之后开始被等待输入
            "slot_active" -> listener.onSlotActive(
                msg.optLong("frame", 0), msg.optInt("slot", 0), parsePlayers(msg))
            // ★★ v52 观战转座确认（仅转座者本人收到）
            "seat_granted" -> listener.onSeatGranted(
                msg.optLong("frame", 0), msg.optInt("slot", 0), parsePlayers(msg))
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
            "restart" -> {
                // ★★ v4.5.1 全员重开（本作核心不支持画面快照）
                listener.onRestart(msg.optInt("pad", 4).coerceIn(0, 16), parsePlayers(msg))
            }
            "peer_ready" -> listener.onPeerReady(msg.optString("msg", ""))
            "input" -> {
                listener.onRemoteInput(
                    msg.optLong("frame", 0),
                    msg.optInt("pad", 0),
                    msg.optInt("slot", 0).coerceIn(0, 4)
                )
            }
            "peer_joined" -> listener.onPeerJoined(
                msg.optString("msg", ""), msg.optInt("slot", 0), msg.optBoolean("spec", false))
            "peer_left" -> listener.onPeerLeft(
                msg.optString("msg", ""), msg.optInt("slot", 0), msg.optBoolean("spec", false),
                msg.optLong("frame", -1L))
            // ★★ v51 游戏内聊天：服务器盖戳 from/slot/spec 后转发
            "chat" -> listener.onChat(
                msg.optString("from", ""), msg.optString("msg", ""),
                msg.optInt("slot", 0), msg.optBoolean("spec", false))
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

    /** ★★ v4.5 服务器侧存档：房主周期上报快照（服务器缓存，供 2P/3P/4P/观战
     *  加入时直接从服务器拿档同步 —— 联机平台模式）。字段语义与 sendState 一致。 */
    fun sendStateUpload(frame: Long, dataBase64: String, hist: Map<Int, List<Int>>) {
        val histJson = JSONObject()
        for ((slot, pads) in hist) {
            histJson.put(slot.toString(), pads)
        }
        sendLine(JSONObject()
            .put("type", "state_upload")
            .put("frame", frame)
            .put("data", dataBase64)
            .put("hist", histJson)
            .put("histEnd", frame)
            .toString())
    }

    /** ★★ v4.5：房主出档失败时主动上报 —— 服务器立即收尾同步（缓存档恢复全员 /
     *  接入者劝退），不等看门狗，绝不让房间卡在“同步中”。 */
    fun sendStateFail() {
        sendLine(JSONObject().put("type", "state_fail").toString())
    }

    /** ★★ v4.6 成员自救：同步画面装载失败 / 核心复位失败等无法继续对齐的场景，
     *  请求服务器编排全员重开（回开机态、从帧 0 同步重跑）—— 不再断开退出。
     *  服务器只在全员 v≥45 时广播 restart；混合旧客户端房间回 error 说明。 */
    fun sendRestartReq() {
        sendLine(JSONObject().put("type", "restart_req").toString())
    }

    /** 通知服务器：我已就绪（引擎已加载完、帧循环就位，等 start/resume）。
     * 带建议 inputDelay（帧）+ ★★ v4.5.1 出档能力（1 能 / -1 不能 / 0 未知 ——
     * 服务器据此决定快照同步还是全员重开）。
     * ★★ v52 追赶能力（catchup=true = 引擎支持装档 + runFrameDirect
     * 离屏快放 —— 服务器据此决定该接入者走“不暂停追赶”还是传统暂停同步；
     * 观战者也可追赶 —— 它是同一条输入流的只读消费者）。 */
    fun sendReady(suggestedDelay: Int = 0, stateCap: Int = 0, catchup: Boolean = false) {
        sendLine(JSONObject().put("type", "ready")
            .put("pad", suggestedDelay.coerceIn(0, 16))
            .put("stateCap", stateCap.coerceIn(-1, 1))
            .put("catchup", catchup)
            .toString())
    }

    /** ★★ v52 观战者热转座：请求入座（服务器分配空位 → seat_granted
     * + 全员 slot_active —— 不暂停不重开，转座者画面本就是当前局）。
     * want=0 自动取下一空闲座；满座/旧版本房间服务器回 error。 */
    fun sendTakeSeat(want: Int = 0) {
        sendLine(JSONObject().put("type", "take_seat")
            .put("slot", want.coerceIn(0, 4))
            .toString())
    }

    /** ★★ v51 游戏内聊天：发送文本（服务器按连接盖戳 from/slot/spec 转发；
     *  空白丢弃、限长 200 字符 —— 与服务器同一规则，两端一致）。 */
    fun sendChat(text: String) {
        val t = text.trim()
        if (t.isEmpty()) return
        sendLine(JSONObject().put("type", "chat")
            .put("msg", if (t.length > 200) t.take(200) else t)
            .toString())
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
