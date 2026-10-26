package com.nesstation.app.battle

import android.os.Handler
import android.os.Looper
import com.nesstation.app.core.engine.NetplayHook
import com.nesstation.app.core.model.GamePlatform
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.max

/**
 * 帧同步联机控制器：通过 [NetplayHook] 接入到既有 [com.nesstation.app.core.engine.EmulatorEngine]
 * 的模拟循环中，让本地 / 远端玩家在同一 ROM 上同帧同画执行。
 *
 * ★★ v4.3 后加入（late join）架构升级：
 *  - **1P 进房直接单机开玩**：房主（槽 1）在房间只有自己时 ready，服务器立即
 *    回 start —— 1P 立刻开玩（单机手感），房间保持可加入。
 *  - **2P/3P/4P 中途接入**：新玩家进房 → 装载同一 ROM → ready → 服务器广播
 *    pause（全员暂停在各自下一帧）+ state_req → 房主暂停后即时存档 + 近 16 帧
 *    各槽位输入 → 服务器缓存转发 → 新玩家装载存档、帧计数对齐 → state_ok →
 *    服务器广播 resume（全员按同一存档、同一帧号、同一 inputDelay 恢复）——
 *    “连接 1P 的画面，快速同步画面后接入”。
 *  - **多槽位输入**：input 消息带槽位（1P=1…4P=4），padsHistory[1..4] 按槽位
 *    存输入；beforeFramePads 返回 4 手柄（FBNeo 等核心 setPad1..4 全用，
 *    2 手柄核心只用前两个 —— 3P/4P 仍可观战同画面）。
 *  - **帧计数对齐**：非房主端维护 frameOffset（本地帧 + offset = 网络帧号），
 *    恢复时 offset = 存档帧 - 本地暂停帧，保证全员网络帧号一致。
 *
 * ## 设计要点（★ 同步启动门 + 阻塞锁步 + RTT 自适应延迟）
 *
 *  - **同步启动**：引擎加载完 → 帧循环就位 → [beforeFrame] 阻塞在启动门上；
 *    本端测完 RTT 后发 ready → 服务器按开局类型（房主单开 / 全员就绪 / 后加入）
 *    分别回 start / 广播 start / 走存档同步后广播 resume。
 *  - **绝不兜底**：锁步联机中只要有一帧用替代输入执行，各端状态就永久发散。
 *    [beforeFrameImpl] 对每个活跃槽位的 execFrame 帧输入【阻塞等待直到到达】。
 *  - inputDelay：ready 前测 RTT 建议；服务器裁决最终值随 start/resume 下发。
 *
 * ## 线程模型
 *
 *  - 模拟器线程：[beforeFrame] / [afterFrame]。启动门 / 同步暂停门 / 远端输入
 *    等待都阻塞在这里（有意为之）。
 *  - 网络线程：[BattleNetplay] 的 TCP 读线程 → onRemoteInput 写 padsHistory、
 *    onPause/onStateRequest/onStateData/onResume 驱动同步状态机。
 *  - UI 线程：[UiListener]（main Handler 自动 post）。
 */
class NetplayController(
    val platform: GamePlatform = GamePlatform.ARCADE
) : NetplayHook {

    /**
     * ★★ v4.3 后加入：即时存档 I/O 桥（由 EmulatorScreen 注入 —— 它持有引擎）。
     * 引擎线程已暂停时在【网络线程】被调用；实现必须线程安全（FBNeo 等核心的
     * saveState/loadState 在模拟线程挂起时可安全调用）。
     */
    interface StateIo {
        /** 房主：把当前状态写入 [file]。返回是否成功。 */
        fun saveStateTo(file: File): Boolean
        /** 装载 [file]（引擎已暂停或尚未开跑）。返回是否成功。 */
        fun loadStateFrom(file: File): Boolean
        /** 存档暂存目录。 */
        fun stateDir(): File
    }

    /**
     * UI 回调接口（所有方法保证在主线程被调用）。
     * ★ v4.3 新增的回调全部带默认空实现 —— 旧实现（EmulatorScreen /
     * BattleMatchScreen）无需改动即可编译；需要展示新状态的界面按需覆写。
     */
    interface UiListener {
        fun onReady(role: String, inputDelay: Int)
        fun onStart(inputDelay: Int)
        fun onPeerReady(username: String)
        fun onFrameInfo(frame: Long, inputDelay: Int, desyncCount: Int)
        fun onPeerJoined(username: String)
        fun onPeerLeft(username: String)
        fun onError(message: String)
        fun onDisconnected()
        fun onNetplayWait(reason: String)
        fun onNetplayLost(reason: String)

        /** ★ v4.3：握手详情（槽位 / 成员 / 房间是否已在跑）。 */
        fun onReadyDetail(role: String, inputDelay: Int, slot: Int, players: List<Int>, playing: Boolean) {}
        /** ★ v4.3：开局详情（活跃槽位）。 */
        fun onStartDetail(inputDelay: Int, players: List<Int>) {}
        /** ★ v4.3：对端加入（带槽位）。 */
        fun onPeerJoinedSlot(username: String, slot: Int) {}
        /** ★ v4.3：对端离开（带槽位）。 */
        fun onPeerLeftSlot(username: String, slot: Int) {}
        /** ★ v4.3：后加入同步进度提示（“新玩家接入：同步画面中…”等）。 */
        fun onSyncStatus(text: String) {}
    }

    @Volatile private var primaryListener: UiListener? = null
    private val uiListeners = java.util.concurrent.CopyOnWriteArrayList<UiListener>()

    fun setUiListener(l: UiListener?) { primaryListener = l }
    fun addUiListener(l: UiListener) { uiListeners.add(l) }
    fun removeUiListener(l: UiListener) { uiListeners.remove(l) }

    private val mainHandler = Handler(Looper.getMainLooper())
    private fun postToMain(block: () -> Unit) {
        if (Looper.myLooper() === Looper.getMainLooper()) block()
        else mainHandler.post(block)
    }

    private fun dispatch(block: (UiListener) -> Unit) {
        val primary = primaryListener
        val list = uiListeners
        if (primary == null && list.isEmpty()) return
        postToMain {
            primary?.let { block(it) }
            list.forEach { block(it) }
        }
    }

    // === 本地输入缓冲（由 UI 通过 setLocalPad 推送） ===
    @Volatile private var currentLocalPad = 0

    // === ★ v4.3 按槽位的帧历史：padsHistory[1..4] : 网络帧 -> pad ===
    private val padsHistory = Array(5) { ConcurrentHashMap<Long, Int>() }

    /** 网络帧号 = 本地帧 + frameOffset（房主恒 0；后加入按存档帧对齐）。 */
    @Volatile private var frameOffset = 0L

    /** 活跃槽位（要等待其输入的远端槽位 = activeRemoteSlots；本地槽位除外）。 */
    private val activeSlots = java.util.concurrent.ConcurrentHashMap.newKeySet<Int>()
    /** 同步接入中的槽位（peer_joined 后、resume 前 —— 不等待其输入）。 */
    private val pendingSlots = java.util.concurrent.ConcurrentHashMap.newKeySet<Int>()

    /** 本端槽位（1P=1…4P=4，服务器 hello 下发）。 */
    @Volatile var mySlot: Int = 1
        private set

    @Volatile private var inputDelay = 4
    @Volatile private var desyncCount = 0
    @Volatile private var role: String = "host"
        private set

    // === ★ 同步启动门状态 ===
    @Volatile private var engineLoaded = false
    @Volatile private var readySent = false
    @Volatile private var started = false
    private val startGate = Object()
    /** ★★ v4.3 后加入：同步暂停门（pause→resume 之间引擎线程挂起）。 */
    @Volatile private var syncPaused = false
    private val syncGate = Object()
    /** 引擎线程当前（或即将）暂停处的本地帧号 —— resume 时据此换算 frameOffset。 */
    @Volatile private var parkedLocalFrame: Long = -1L
    @Volatile private var lastWaitNoticeMs = 0L

    /** 存档 I/O 桥（EmulatorScreen 注入）。 */
    @Volatile private var stateIo: StateIo? = null

    /** 等待 UI 提示节流 */
    @Volatile private var lastSyncNoticeMs = 0L

    // === 网络 ===
    @Volatile private var net: BattleNetplay? = null

    private val netListener = object : BattleNetplay.Listener {
        override fun onRemoteInput(frame: Long, pad: Int, slot: Int) {
            if (slot in 1..4) padsHistory[slot][frame] = pad
        }

        override fun onReady(role: String, inputDelay: Int, slot: Int, players: List<Int>, playing: Boolean) {
            this@NetplayController.inputDelay = inputDelay.coerceIn(0, 16)
            this@NetplayController.role = role
            mySlot = slot.coerceIn(1, 4)
            activeSlots.clear()
            activeSlots.addAll(players)
            dispatch { it.onReady(role, inputDelay) }
            dispatch { it.onReadyDetail(role, inputDelay, slot, players, playing) }
        }

        override fun onStart(inputDelay: Int, players: List<Int>) {
            this@NetplayController.inputDelay = inputDelay.coerceIn(0, 16)
            activeSlots.clear()
            activeSlots.addAll(players)
            // 房主单开 / 全员同步开跑：帧 0 起，offset 恒 0
            frameOffset = 0
            parkedLocalFrame = -1
            android.util.Log.i("NetplayController", "onStart: delay=$inputDelay players=$players")
            synchronized(startGate) { started = true; startGate.notifyAll() }
            dispatch { it.onStart(inputDelay) }
            dispatch { it.onStartDetail(inputDelay, players) }
        }

        override fun onPause() {
            // 后加入同步开始：运行中的引擎线程在下一帧前挂起
            if (started) {
                syncPaused = true
                dispatch { it.onSyncStatus("有玩家接入：暂停等待同步…") }
            }
        }

        override fun onStateRequest() {
            // 房主：等待自身暂停 → 存档 → 上报（engine 线程挂在 syncGate 上）
            dispatch { it.onSyncStatus("新玩家接入：生成同步画面…") }
            Thread({
                try {
                    hostProduceState()
                } catch (t: Throwable) {
                    android.util.Log.w("NetplayController", "hostProduceState failed", t)
                    // 存档失败也要解除暂停，否则全员卡死
                    resumeInternal(null, inputDelay, activeSlots.toList())
                }
            }, "battle-state-host").apply { isDaemon = true }.start()
        }

        override fun onStateData(frame: Long, dataBase64: String, hist: Map<Int, List<Int>>?, histEnd: Long) {
            // 非房主成员：装载存档 + 对齐帧计数（引擎线程此刻挂在门上）
            dispatch { it.onSyncStatus("同步画面完成：装载中…") }
            Thread({
                try {
                    memberLoadState(frame, dataBase64, hist, histEnd)
                } catch (t: Throwable) {
                    android.util.Log.w("NetplayController", "memberLoadState failed", t)
                }
            }, "battle-state-join").apply { isDaemon = true }.start()
        }

        override fun onResume(frame: Long, inputDelay: Int, players: List<Int>,
                              dataBase64: String?, hist: Map<Int, List<Int>>?, histEnd: Long) {
            // 全员恢复：按同一存档帧对齐、同一延迟继续
            dispatch { it.onSyncStatus("玩家接入完成，继续对战！") }
            Thread({
                try {
                    resumeInternal(dataBase64, inputDelay, players, frame, hist, histEnd)
                } catch (t: Throwable) {
                    android.util.Log.w("NetplayController", "resume failed", t)
                    resumeInternal(null, inputDelay, players)
                }
            }, "battle-resume").apply { isDaemon = true }.start()
        }

        override fun onPeerReady(username: String) {
            dispatch { it.onPeerReady(username) }
        }

        override fun onPeerJoined(username: String, slot: Int) {
            if (slot in 1..4) pendingSlots.add(slot)
            dispatch { it.onPeerJoined(username) }
            dispatch { it.onPeerJoinedSlot(username, slot) }
        }

        override fun onPeerLeft(username: String, slot: Int) {
            pendingSlots.remove(slot)
            activeSlots.remove(slot)
            if (!started) {
                // 等待阶段对端离开：开门（引擎不死等）
                synchronized(startGate) { started = true; startGate.notifyAll() }
            }
            dispatch { it.onPeerLeft(username) }
            dispatch { it.onPeerLeftSlot(username, slot) }
        }

        override fun onError(message: String) {
            synchronized(startGate) { started = true; startGate.notifyAll() }
            synchronized(syncGate) { syncPaused = false; syncGate.notifyAll() }
            dispatch { it.onError(message) }
        }

        override fun onDisconnected() {
            if (started) {
                dispatch { it.onNetplayLost("与对战服务器的连接已断开") }
            }
            synchronized(startGate) { started = true; startGate.notifyAll() }
            synchronized(syncGate) { syncPaused = false; syncGate.notifyAll() }
            dispatch { it.onDisconnected() }
        }
    }

    /** 房主：等引擎暂停在帧 F → 存档 → 近 16 帧输入 → sendState。 */
    private fun hostProduceState() {
        val io = stateIo ?: run {
            dispatch { it.onSyncStatus("同步失败：无存档通道") }
            return
        }
        // 1. 等待引擎线程挂到 syncGate（它记录 parkedLocalFrame）
        var waitMs = 0L
        while (parkedLocalFrame < 0 && waitMs < 5000) {
            if (!started) return // 尚未开跑（理论不可达：state_req 仅在 playing 时来）
            Thread.sleep(10); waitMs += 10
        }
        val parkedLocal = parkedLocalFrame
        if (parkedLocal < 0) {
            // 引擎 5s 未暂停（异常）：放弃同步并解除暂停
            dispatch { it.onSyncStatus("同步超时：继续对战") }
            resumeInternal(null, inputDelay, activeSlots.toList())
            return
        }
        val stateFrame = parkedLocal + frameOffset   // 房主 offset 恒 0，双保险
        // 2. 即时存档（引擎线程挂在门上，安全）
        val file = File(io.stateDir(), "battle_join_sync.state")
        if (!io.saveStateTo(file)) {
            dispatch { it.onSyncStatus("同步失败：存档失败") }
            resumeInternal(null, inputDelay, activeSlots.toList())
            return
        }
        val bytes = try { file.readBytes() } catch (_: Throwable) { null }
        if (bytes == null || bytes.isEmpty()) {
            dispatch { it.onSyncStatus("同步失败：存档为空") }
            resumeInternal(null, inputDelay, activeSlots.toList())
            return
        }
        // 3. 近 16 帧各槽位输入（resume 后 inputDelay 窗口内要用）
        val hist = HashMap<Int, List<Int>>()
        for (slot in 1..4) {
            val pads = ArrayList<Int>(16)
            for (f in stateFrame - 16 until stateFrame) {
                pads.add(padsHistory[slot][f] ?: 0)
            }
            hist[slot] = pads
        }
        // 4. 上报（base64 单行 JSON；FBNeo 街机存档一般几十~几百 KB）
        val b64 = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
        net?.sendState(stateFrame, b64, hist)
        dispatch { it.onSyncStatus("已发送同步画面（${bytes.size / 1024}KB），等待装载…") }
    }

    /** 非房主成员（含新玩家）：装载存档 + 帧计数对齐 + 上报 state_ok。 */
    private fun memberLoadState(frame: Long, dataBase64: String,
                                hist: Map<Int, List<Int>>?, histEnd: Long) {
        val io = stateIo
        val n = net
        if (io == null || n == null) return
        val bytes = try {
            android.util.Base64.decode(dataBase64, android.util.Base64.NO_WRAP)
        } catch (_: Throwable) { null }
        if (bytes == null || bytes.isEmpty()) return
        // 引擎线程此刻必挂在门上（未 start → startGate；已 start → syncGate）。
        // 等 parkedLocalFrame 就绪（joiner 为 0，运行成员为其暂停帧）。
        var waitMs = 0L
        while (parkedLocalFrame < 0 && waitMs < 5000) {
            Thread.sleep(10); waitMs += 10
        }
        val parkedLocal = if (parkedLocalFrame >= 0) parkedLocalFrame else 0L
        val file = File(io.stateDir(), "battle_join_sync.state")
        try { file.writeBytes(bytes) } catch (_: Throwable) { return }
        if (!io.loadStateFrom(file)) {
            dispatch { it.onSyncStatus("装载同步画面失败") }
            return
        }
        // 对齐：网络帧号 = 本地帧 + offset → 恢复时从 frame 起
        frameOffset = frame - parkedLocal
        seedHist(hist, histEnd)
        // 新玩家：装载完成即上报（服务器收到后全员 resume）
        n.sendStateOk()
        dispatch { it.onSyncStatus("同步画面已装载（帧 #$frame），等待全员恢复…") }
    }

    /** resume：全员按统一存档/帧号/延迟恢复。data 空 = 仅解除暂停（同步中断兜底）。 */
    private fun resumeInternal(dataBase64: String?, finalDelay: Int, players: List<Int>,
                               frame: Long = -1L, hist: Map<Int, List<Int>>? = null, histEnd: Long = 0L) {
        // 已在 stateData 装载过（data 非空且本端非房主）→ 只需对齐；房主跳过装载
        val amHost = role == "host"
        if (dataBase64 != null && !amHost && frame >= 0) {
            val io = stateIo
            if (io != null) {
                // 未及在 stateData 阶段装载（如掉线重试场景）→ 兜底装载
                // 已装载则 loadState 同档重放，无害且保证一致
                val bytes = try {
                    android.util.Base64.decode(dataBase64, android.util.Base64.NO_WRAP)
                } catch (_: Throwable) { null }
                if (bytes != null && bytes.isNotEmpty()) {
                    val parkedLocal = if (parkedLocalFrame >= 0) parkedLocalFrame else 0L
                    val file = File(io.stateDir(), "battle_join_sync.state")
                    try {
                        file.writeBytes(bytes)
                        io.loadStateFrom(file)
                    } catch (_: Throwable) {}
                    frameOffset = frame - parkedLocal
                }
            }
        }
        if (frame >= 0 && amHost) {
            // 房主：存档即本端状态，只对齐帧号（offset 恒 0）
            frameOffset = 0
        }
        if (hist != null && frame >= 0) seedHist(hist, histEnd)

        inputDelay = finalDelay.coerceIn(0, 16)
        activeSlots.clear()
        activeSlots.addAll(players)
        pendingSlots.clear()

        // 开门：引擎线程继续（syncPaused 先放行，started 兜底放行 joiner）
        synchronized(syncGate) { syncPaused = false; syncGate.notifyAll() }
        synchronized(startGate) { started = true; startGate.notifyAll() }
        parkedLocalFrame = -1
    }

    /** hist（槽位→pad 序列，止于 histEnd 前）→ padsHistory 种子。 */
    private fun seedHist(hist: Map<Int, List<Int>>?, histEnd: Long) {
        if (hist == null || histEnd <= 0) return
        for ((slot, pads) in hist) {
            if (slot !in 1..4 || pads.isEmpty()) continue
            val startFrame = histEnd - pads.size
            for (i in pads.indices) {
                padsHistory[slot][startFrame + i] = pads[i]
            }
        }
    }

    /** ★ v4.3 注入存档通道（EmulatorScreen 持有引擎，进入联机界面时调用）。 */
    fun setStateIo(io: StateIo?) { stateIo = io }

    /**
     * 连接到中继服务器。非阻塞：[BattleNetplay.connect] 在自己的线程里完成 TCP 握手。
     */
    fun connect(host: String, port: Int, token: String, roomId: String) {
        val n = BattleNetplay(host, port, token, roomId, netListener)
        net = n
        n.connect()
    }

    /**
     * ★ 引擎加载完毕通知（由 EmulatorScreen 在 ROM 加载完成、模拟循环就位后调用）。
     * 触发：RTT 测量 → 建议 inputDelay → 发 ready。此后按服务器编排走三种开局。
     */
    fun notifyEngineReady() {
        if (engineLoaded) return
        engineLoaded = true
        val n = net ?: return
        if (readySent) return
        readySent = true
        Thread({
            var minRtt: Long? = null
            val samples = 4
            for (i in 0 until samples) {
                val latch = java.util.concurrent.CountDownLatch(1)
                var rtt: Long? = null
                try {
                    n.measureRtt(1000) { ms ->
                        rtt = ms
                        latch.countDown()
                    }
                } catch (_: Throwable) {
                    latch.countDown()
                }
                try { latch.await(1300, java.util.concurrent.TimeUnit.MILLISECONDS) } catch (_: InterruptedException) {}
                rtt?.let { ms -> minRtt = if (minRtt == null) ms else minOf(minRtt!!, ms) }
                if (i < samples - 1) {
                    try { Thread.sleep(180) } catch (_: InterruptedException) { return@Thread }
                }
            }
            val suggested = when (val r = minRtt) {
                null -> 4
                else -> (Math.ceil(r / 2.0 / 16.666) + 2).toInt().coerceIn(4, 12)
            }
            android.util.Log.i("NetplayController",
                "RTT min=${minRtt}ms → suggestedDelay=${suggested}f")
            try { n.sendReady(suggested) } catch (_: Throwable) {}
        }, "battle-rtt-probe").apply { isDaemon = true }.start()
    }

    /** 本地玩家摇杆输入（bit 布局与平台 pad 一致，由 OnScreenController / 物理手柄推送）。 */
    fun setLocalPad(bits: Int) {
        currentLocalPad = bits
    }

    /** 本端槽位（1P=1…4P=4）。 */
    val slot: Int get() = mySlot

    /**
     * 引擎在调用 runFrame 之前回调：采样本地输入 → 发给服务器 → 阻塞拿所有
     * 活跃槽位的同帧输入。返回 4 手柄（pad1..pad4）。
     *
     * 1. 启动门：未 start（新玩家 / 同步重启）→ 记录 parkedLocalFrame 并阻塞。
     * 2. 同步暂停门：后加入同步中 → 记录 parkedLocalFrame 并阻塞。
     * 3. 采样 + 发送（网络帧号 = 本地帧 + frameOffset，带槽位）。
     * 4. 执行帧 = 网络帧 - inputDelay（warmup 阶段 pad 全 0，各端一致）。
     * 5. 阻塞等所有活跃远端槽位的 execFrame 输入【绝不兜底】。
     */
    private fun beforeFrameImpl(frame: Long): IntArray? {
        // 1. 启动门
        if (!started) {
            synchronized(startGate) {
                while (!started) {
                    parkedLocalFrame = frame
                    val now = System.currentTimeMillis()
                    if (now - lastWaitNoticeMs > 1500) {
                        lastWaitNoticeMs = now
                        dispatch { it.onNetplayWait("等待同步开始（引擎就绪后自动开跑）…") }
                    }
                    try { startGate.wait(500) } catch (_: InterruptedException) { return IntArray(4) }
                }
            }
        }
        // 2. ★ v4.3 同步暂停门（后加入同步：房主/运行成员挂起在此）
        if (syncPaused) {
            synchronized(syncGate) {
                while (syncPaused) {
                    parkedLocalFrame = frame
                    val now = System.currentTimeMillis()
                    if (now - lastSyncNoticeMs > 1500) {
                        lastSyncNoticeMs = now
                        dispatch { it.onSyncStatus("新玩家接入：画面同步中…") }
                    }
                    try { syncGate.wait(500) } catch (_: InterruptedException) { return IntArray(4) }
                }
            }
        }

        val netFrame = frame + frameOffset
        val localPad = currentLocalPad
        padsHistory[mySlot][netFrame] = localPad

        val n = net
        if (n == null) {
            val pads = IntArray(4)
            pads[mySlot - 1] = localPad
            return pads
        }

        inputDelay = n.inputDelay.coerceIn(0, 16)

        // 3. 发送本帧输入（带槽位，非阻塞）
        try {
            n.sendInput(netFrame, localPad, mySlot)
        } catch (t: Throwable) {
            android.util.Log.w("NetplayController", "sendInput failed: ${t.message}")
        }

        // 4. 执行帧 = 网络帧 - inputDelay
        val execFrame = netFrame - inputDelay
        if (execFrame < 0) {
            return IntArray(4) // warmup：全 0，各端一致
        }

        // 5. 组装 4 手柄：本地槽位用本地历史；活跃远端槽位阻塞等待；
        //    未激活/接入中槽位恒 0。
        val pads = IntArray(4)
        for (slot in 1..4) {
            if (slot == mySlot) {
                pads[slot - 1] = padsHistory[slot][execFrame] ?: localPad
            } else if (activeSlots.contains(slot)) {
                var pad = padsHistory[slot][execFrame]
                if (pad == null) {
                    pad = awaitSlotInput(slot, execFrame)
                }
                if (pad == null) {
                    // 断连：停止模拟（全 0），后续 onPeerLeft/onNetplayLost 引导 UI
                    return IntArray(4)
                }
                pads[slot - 1] = pad
            }
        }
        return pads
    }

    /** 兼容旧 2 手柄路径（NES/SNES/GBA 等核心的 frameHook 调用）。 */
    override fun beforeFrame(frame: Long): Pair<Int, Int>? {
        return beforeFrameImpl(frame)?.let { pads -> pads[0] to pads[1] }
    }

    /** ★ v4.3 四手柄路径（FBNeo 等支持 setPad1..4 的核心经 NetplayHook 默认方法调用）。 */
    override fun beforeFramePads(frame: Long): IntArray? = beforeFrameImpl(frame)

    /**
     * ★ 阻塞等待某槽位第 [targetFrame] 帧的输入，直到到达 / 槽位离开 / 连接断开。
     */
    private fun awaitSlotInput(slot: Int, targetFrame: Long): Int? {
        var lastNotice = 0L
        while (true) {
            padsHistory[slot][targetFrame]?.let { return it }
            // 槽位已离开 / 掉线：不再等待
            if (!activeSlots.contains(slot)) return null
            val n = net
            val now = System.currentTimeMillis()
            if (n == null || !n.isConnected) return null
            if (lastNotice == 0L) {
                if (now - lastNotice >= 60) {
                    lastNotice = now
                    dispatch { it.onNetplayWait("网络抖动：等待 ${slot}P 输入中…（画面短暂停顿属正常）") }
                }
            } else if (now - lastNotice >= 1000) {
                lastNotice = now
                dispatch { it.onNetplayWait("网络抖动：等待 ${slot}P 输入中…（画面短暂停顿属正常）") }
            }
            try { Thread.sleep(2) } catch (_: InterruptedException) { return null }
        }
    }

    override fun afterFrame(frame: Long) {
        val netFrame = frame + frameOffset
        if (netFrame % 15 == 0L) {
            val il = inputDelay
            val dc = desyncCount
            dispatch { it.onFrameInfo(netFrame, il, dc) }
        }
        if (netFrame % 256 == 0L) {
            cleanupHistory(netFrame)
        }
    }

    private fun cleanupHistory(frame: Long) {
        val cutoff = frame - 1024
        if (cutoff > 0) {
            for (slot in 1..4) {
                padsHistory[slot].keys.removeAll { it < cutoff }
            }
        }
    }

    /**
     * 主动断开网络并清理状态。不会卸载 ROM —— 那是 [com.nesstation.app.core.engine.EmulatorEngine]
     * 的事。本方法只清掉自己的帧历史和 TCP 连接。
     */
    fun stop() {
        synchronized(startGate) { started = true; startGate.notifyAll() }
        synchronized(syncGate) { syncPaused = false; syncGate.notifyAll() }
        try { net?.close() } catch (_: Throwable) {}
        net = null
        for (slot in 1..4) padsHistory[slot].clear()
        activeSlots.clear()
        pendingSlots.clear()
        currentLocalPad = 0
        frameOffset = 0
        parkedLocalFrame = -1
        desyncCount = 0
        engineLoaded = false
        readySent = false
        started = false
        syncPaused = false
    }

    /** 当前是否已收到同步开始信号。 */
    val isStarted: Boolean get() = started

    /** 当前是否还在同帧同画同步（门未开或连接断开都算不可用）。 */
    val isAlive: Boolean get() = started && net != null && net!!.isConnected
}
