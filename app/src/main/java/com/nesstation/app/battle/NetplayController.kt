package com.nesstation.app.battle

import android.os.Handler
import android.os.Looper
import com.nesstation.app.core.engine.NetplayHook
import com.nesstation.app.core.model.GamePlatform
import java.util.concurrent.ConcurrentHashMap

/**
 * 帧同步联机控制器：通过 [NetplayHook] 接入到既有 [com.nesstation.app.core.engine.EmulatorEngine]
 * 的模拟循环中，让本地 / 远端两位玩家在同一 ROM 上同帧同画执行。
 *
 * ## 设计要点（★ 同步启动门 + 阻塞锁步 + RTT 自适应延迟）
 *
 *  - 双方各自本地运行同一 ROM（FBNeo / FCEUmm / snes9x / mGBA / Genesis-Plus-GX / Geargrafx /
 *    DOSBox-Pure 等确定性核心），服务器只转发输入。
 *  - **同步启动**：引擎加载完 → 帧循环就位 → [beforeFrame] 阻塞在启动门上；
 *    本端测完 RTT 后发 ready（建议延迟）→ 服务器等双方 ready 后同时广播 start
 *    → 两端同帧从 0 开跑。旧版"各自到点就跑"的启动时差（双方 ROM 加载耗时
 *    不同，慢设备可达数秒 → 帧计数错开几十上百帧）是"各玩各的"的第一根因，
 *    inputDelay（4-6 帧 ≈ 100ms）根本吸收不了。
 *  - **绝不兜底**（旧版"非阻塞 + lastRemotePad 兜底"是"各玩各的"的第二根因）：
 *    锁步联机中只要有一帧用替代输入执行，两端状态就永久发散。现在
 *    [beforeFrame] 对对方第 execFrame 帧输入【阻塞等待直到到达】——网络慢
 *    就让游戏变慢（正确行为），而不是变单机（错误行为）。
 *  - inputDelay 自适应：ready 前测 RTT（多次取最小），建议延迟 =
 *    ceil(RTT/2 / 16.6ms) + 2 帧；服务器取 max(双方建议, 配置) 随 start 下发，
 *    两端一致（两端延迟值不同会让执行帧错位 → 必然发散）。
 *
 * ## 线程模型
 *
 *  - 模拟器线程：[EmulatorEngine.loadRom] 起的引擎线程，每帧调用 [beforeFrame] /
 *    [afterFrame]。**启动门与远端输入等待都阻塞在这里**（有意为之：门未开时
 *    引擎一帧都不该跑 —— 门开前跑掉的帧会造成两端状态错位）。
 *  - 网络线程：[BattleNetplay.connect] 起的 TCP 读线程，收到对方输入时回调 [onRemoteInput]
 *    把数据写进 [remoteHistory]（线程安全的 ConcurrentHashMap）。
 *  - UI 线程：通过 [UiListener] 接收回调（已通过 main Handler 自动 post 到主线程）。
 */
class NetplayController(
    val platform: GamePlatform = GamePlatform.ARCADE
) : NetplayHook {

    /**
     * UI 回调接口（所有方法保证在主线程被调用）。
     */
    interface UiListener {
        /**
         * 服务器握手完成（hello 收到）—— TCP 连接 + JWT 验证 + 房间加入全部 OK。
         *
         * 时序（新版同步启动协议）：双方都在 onReady 后进入 EmulatorScreen 加载
         * 引擎（帧被启动门挡住），引擎加载完后由 [notifyEngineReady] 触发 RTT
         * 测量 + 发 ready；双方都 ready 后服务器广播 start → [onStart]。
         */
        fun onReady(role: String, inputDelay: Int)
        /**
         * ★ 同步开始：双方就绪、服务器同时广播了 start。
         * 引擎从这一刻起从帧 0 开始模拟。[inputDelay] 为服务器裁决的最终值（两端一致）。
         */
        fun onStart(inputDelay: Int)
        /**
         * 对方已 ready（引擎已加载完，正等 start）。
         */
        fun onPeerReady(username: String)
        /** 每执行一帧后回调（UI 可刷新延迟/帧号显示） */
        fun onFrameInfo(frame: Long, inputDelay: Int, desyncCount: Int)
        /** 对方加入 */
        fun onPeerJoined(username: String)
        /** 对方断开 */
        fun onPeerLeft(username: String)
        /** 连接被拒绝 / 协议错误 */
        fun onError(message: String)
        /** 连接关闭（非主动） */
        fun onDisconnected()
        /** 状态提示（等待对手就绪 / 网络抖动中等待对方输入等，非致命） */
        fun onNetplayWait(reason: String)
        /** 连接已不可用（对端断开等致命场景） */
        fun onNetplayLost(reason: String)
    }

    @Volatile private var primaryListener: UiListener? = null
    private val uiListeners = java.util.concurrent.CopyOnWriteArrayList<UiListener>()

    /** 设置主 UI 监听器（向后兼容旧 API）。建议改用 [addUiListener] / [removeUiListener]。 */
    fun setUiListener(l: UiListener?) { primaryListener = l }

    /** 添加一个 UI 监听器。多个监听器可同时存在（例如 BattleMatchScreen 监听致命错误，同时
     *  EmulatorScreen 监听状态条信息）。所有监听器在主线程被回调。 */
    fun addUiListener(l: UiListener) { uiListeners.add(l) }

    /** 移除一个 UI 监听器。 */
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

    // === 帧历史：frame -> pad（环形容量 1024，足够 inputDelay + 抖动窗口） ===
    private val localHistory = ConcurrentHashMap<Long, Int>()
    private val remoteHistory = ConcurrentHashMap<Long, Int>()

    /** 由服务器在 hello/start 中下发。start 消息携带最终值（两端一致）。 */
    @Volatile private var inputDelay = 4
    @Volatile private var lastRemotePad = 0
    @Volatile private var desyncCount = 0

    // === ★ 同步启动门状态 ===
    /** 引擎是否已加载完（EmulatorScreen 通知）—— 之后才测 RTT + 发 ready */
    @Volatile private var engineLoaded = false
    /** 是否已向服务器发过 ready */
    @Volatile private var readySent = false
    /** 是否已收到服务器的 start（门开） */
    @Volatile private var started = false
    /** 引擎线程在门上的等待锁 */
    private val startGate = Object()
    /** 等待对方输入时最后提示 UI 的时间（避免每帧 dispatch） */
    @Volatile private var lastWaitNoticeMs = 0L

    /**
     * 角色：1P (host) 还是 2P (guest)。由服务器在 hello 回执里下发（按房主身份，
     * 不再按连接顺序 —— 服务器端已改）。
     *
     * ## pad1/pad2 的映射
     *
     * 街机/主机游戏里 pad1 永远是 1P 的输入，pad2 永远是 2P 的输入。
     * 双方各自的引擎都从 frame 0 跑同一个 ROM，画面完全相同（确定性核心保证）。
     * 区别只在于"谁的输入推给 pad1 / pad2"：
     *  - 1P (host): 本地输入 → pad1, 远端输入 → pad2
     *  - 2P (guest): 本地输入 → pad2, 远端输入 → pad1
     */
    @Volatile var role: String = "host"
        private set

    // === 网络 ===
    @Volatile private var net: BattleNetplay? = null

    private val netListener = object : BattleNetplay.Listener {
        override fun onRemoteInput(frame: Long, pad: Int) {
            // 网络线程：直接写入 remoteHistory（ConcurrentHashMap 线程安全）
            remoteHistory[frame] = pad
        }

        override fun onReady(role: String, inputDelay: Int) {
            this@NetplayController.inputDelay = inputDelay.coerceIn(0, 16)
            this@NetplayController.role = role
            android.util.Log.i("NetplayController", "onReady: role=$role inputDelay=$inputDelay")
            dispatch { it.onReady(role, inputDelay) }
        }

        override fun onStart(inputDelay: Int) {
            // ★ 同步开始：最终延迟值以服务器裁决为准（两端一致），打开启动门
            this@NetplayController.inputDelay = inputDelay.coerceIn(0, 16)
            android.util.Log.i("NetplayController", "onStart: finalInputDelay=$inputDelay")
            synchronized(startGate) { started = true; startGate.notifyAll() }
            dispatch { it.onStart(inputDelay) }
        }

        override fun onPeerReady(username: String) {
            dispatch { it.onPeerReady(username) }
        }

        override fun onPeerJoined(username: String) {
            dispatch { it.onPeerJoined(username) }
        }

        override fun onPeerLeft(username: String) {
            // 对端离开 = 连接不可用：打开门（让引擎线程不再死等），
            // UI 层（BattleMatchScreen）会显示"对手已离开"并退出
            synchronized(startGate) { started = true; startGate.notifyAll() }
            dispatch { it.onPeerLeft(username) }
        }

        override fun onError(message: String) {
            synchronized(startGate) { started = true; startGate.notifyAll() }
            dispatch { it.onError(message) }
        }

        override fun onDisconnected() {
            synchronized(startGate) { started = true; startGate.notifyAll() }
            dispatch { it.onDisconnected() }
        }
    }

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
     * 触发：RTT 测量（ping/pong 取最小）→ 按半程延迟计算建议 inputDelay → 发 ready。
     * 引擎线程此时尚且阻塞在 [beforeFrame] 的启动门上，等服务器广播 start。
     */
    fun notifyEngineReady() {
        if (engineLoaded) return
        engineLoaded = true
        val n = net ?: return
        if (readySent) return
        readySent = true
        // 在网络回调线程链上做多次 RTT 测量（每轮 200ms 间隔，取最小值）
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
            // 建议延迟 = ceil(RTT/2 / 16.6ms) + 2，钳制 [4, 12]
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

    /**
     * 引擎在调用 runFrame 之前回调：采样本地输入 → 发给服务器 → 阻塞拿对方同帧输入。
     *
     * ## 执行顺序（★ 每一环都是同步正确性的组成部分）
     *
     * 1. **启动门**：未收到服务器 start 前阻塞（对方还没就绪/引擎没加载完时，
     *    引擎一帧都不能跑 —— 两端跑掉的帧数不同 = 状态永久错位）。
     * 2. 采样本地输入 + 发给服务器（非阻塞，TCP 缓冲）。
     * 3. 执行帧 = 采样帧 - inputDelay（warmup 阶段 pad 全 0，两端一致）。
     * 4. **阻塞等待**对方的 execFrame 帧输入【直到到达】：
     *    - 99% 情况它早就在 remoteHistory 里（inputDelay 缓冲了半程 RTT + 启动偏差）；
     *    - 网络抖动 → 游戏短暂变慢（正确）—— 绝不用上一帧输入补帧（那样一次就
     *      让两端状态永久发散 = 各玩各的）；
     *    - 对端断开 → netListener 打开门并标记 started，循环退出条件命中，返回 0/0。
     *
     * @param frame 引擎内部单调递增的帧号（0-based，hook attach 时被重置为 0）。
     * @return (pad1, pad2)；warmup 阶段（frame < inputDelay）返回 (0, 0)。
     */
    override fun beforeFrame(frame: Long): Pair<Int, Int>? {
        // 1. ★ 启动门：start 未到（或引擎加载未完成）→ 阻塞引擎线程
        if (!started) {
            synchronized(startGate) {
                while (!started) {
                    // 每 1.5s 提示一次 UI "等待开始"（首次立即提示）
                    val now = System.currentTimeMillis()
                    if (now - lastWaitNoticeMs > 1500) {
                        lastWaitNoticeMs = now
                        dispatch { it.onNetplayWait("等待同步开始（双方引擎就绪后自动开跑）…") }
                    }
                    try { startGate.wait(500) } catch (_: InterruptedException) { return 0 to 0 }
                }
            }
        }

        val localPad = currentLocalPad
        localHistory[frame] = localPad

        val n = net
        if (n == null) {
            // 网络未就绪（理论不可达：门要求 started，而 started 只有网络事件能置位）
            return localPad to lastRemotePad
        }

        // 每帧从服务器握手/start 响应里同步 inputDelay 与 role（兜底，防竞态）
        inputDelay = n.inputDelay.coerceIn(0, 16)
        if (n.role.isNotEmpty()) role = n.role

        // 2. 把本地这一帧的输入送给服务器（非阻塞，TCP 发送缓冲）
        try {
            n.sendInput(frame, localPad)
        } catch (t: Throwable) {
            android.util.Log.w("NetplayController", "sendInput failed: ${t.message}")
        }

        // 3. 实际执行帧 = 采样帧 - inputDelay（warmup 阶段不执行真实输入）
        val execFrame = frame - inputDelay
        if (execFrame < 0) {
            // 预热：什么都不按，避免输入乱序
            return 0 to 0
        }

        val localForExec = localHistory[execFrame] ?: localPad

        // 4. ★ 阻塞锁步：等对方的 execFrame 帧输入【绝不兜底】
        var remoteForExec = remoteHistory[execFrame]
        if (remoteForExec == null) {
            remoteForExec = awaitRemoteBlocking(execFrame)
        }
        // 对方输入仍为 null = 连接已断（netListener 开门时才会放行到这里）
        if (remoteForExec == null) {
            // 断连：停止模拟（返回全 0 且后续 onPeerLeft 会引导 UI 退出）
            return 0 to 0
        }
        lastRemotePad = remoteForExec

        // 5. 角色映射：1P 本地→pad1；2P 本地→pad2
        return if (role == "guest") {
            lastRemotePad to localForExec
        } else {
            localForExec to lastRemotePad
        }
    }

    /**
     * ★ 阻塞等待对方第 [targetFrame] 帧的输入，直到到达或连接断开。
     *
     * - 轮询间隔 2ms（对方输入通常在几毫秒内由网络线程放进 map）；
     * - 每 1s 提示一次 UI"网络抖动，正在等待对方"（游戏此刻是暂停感，属预期）；
     * - 等待期间每 2s 检查一次连接活性：`net` 已关闭 → 返回 null（调用方停帧）；
     * - 无总超时上限：只要连接活着就一直等（对端可能只是暂停了游戏/菜单 ——
     *   恢复后继续，这本身就是锁步的正确语义）。
     *
     * @return 收到的输入；连接断开返回 null。
     */
    private fun awaitRemoteBlocking(targetFrame: Long): Int? {
        var lastNotice = 0L
        var aliveCheck = 0L
        while (true) {
            remoteHistory[targetFrame]?.let { return it }
            val n = net
            val now = System.currentTimeMillis()
            // 连接已断（主动 stop / onDisconnected 置 null）→ 停止等待
            if (n == null || !n.isConnected) return null
            // 每 2.5s 无输入且连接"看似"还在 → 再做一次活性检查
            if (aliveCheck == 0L) aliveCheck = now
            else if (now - aliveCheck > 2500) {
                aliveCheck = now
                if (n != null && !n.isConnected) return null
            }
            // 每秒提示一次 UI（首次等待 60ms 后才提示，避免极短抖动闪提示）
            if (lastNotice == 0L) {
                if (now - lastNotice >= 60) {
                    lastNotice = now
                    dispatch { it.onNetplayWait("网络抖动：等待对方输入中…（画面短暂停顿属正常）") }
                }
            } else if (now - lastNotice >= 1000) {
                lastNotice = now
                dispatch { it.onNetplayWait("网络抖动：等待对方输入中…（画面短暂停顿属正常）") }
            }
            try { Thread.sleep(2) } catch (_: InterruptedException) { return null }
        }
    }

    override fun afterFrame(frame: Long) {
        // 在引擎线程触发。节流：UI 状态条每秒更新 4 次足够。
        if (frame % 15 == 0L) {
            val il = inputDelay
            val dc = desyncCount
            dispatch { it.onFrameInfo(frame, il, dc) }
        }
        if (frame % 256 == 0L) {
            cleanupHistory(frame)
        }
    }

    private fun cleanupHistory(frame: Long) {
        val cutoff = frame - 1024
        if (cutoff > 0) {
            localHistory.keys.removeAll { it < cutoff }
            remoteHistory.keys.removeAll { it < cutoff }
        }
    }

    /**
     * 主动断开网络并清理状态。不会卸载 ROM —— 那是 [com.nesstation.app.core.engine.EmulatorEngine]
     * 的事。本方法只清掉自己的帧历史和 TCP 连接。
     */
    fun stop() {
        synchronized(startGate) { started = true; startGate.notifyAll() }
        try { net?.close() } catch (_: Throwable) {}
        net = null
        localHistory.clear()
        remoteHistory.clear()
        currentLocalPad = 0
        lastRemotePad = 0
        desyncCount = 0
        engineLoaded = false
        readySent = false
        started = false
    }

    /** 当前是否已收到同步开始信号。 */
    val isStarted: Boolean get() = started

    /** 当前是否还在和对方同帧同画同步（门未开或连接断开都算不可用）。 */
    val isAlive: Boolean get() = started && net != null && net!!.isConnected
}
