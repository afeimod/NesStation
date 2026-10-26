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
 * ## 设计要点（★ 同步启动门 + 阻塞锁步 + ★★ v4.4 预测回滚）
 *
 *  - **同步启动**：引擎加载完 → 帧循环就位 → [beforeFrame] 阻塞在启动门上；
 *    本端测完 RTT 后发 ready → 服务器按开局类型（房主单开 / 全员就绪 / 后加入）
 *    分别回 start / 广播 start / 走存档同步后广播 resume。
 *  - **绝不兜底**：锁步联机中只要有一帧用替代输入执行，各端状态就永久发散。
 *    [beforeFrameImpl] 对每个活跃槽位的 execFrame 帧输入【阻塞等待直到到达】。
 *  - **★★ v4.4 回滚网络码（rollback netcode，GGPO 式）**：引擎支持快速存档
 *    （FBNeo）时启用 —— 不再“等不到就卡”：远端输入未到时用“延续上一输入”
 *    预测先行执行；真实输入到达且与预测不一致 → 装载最近检查点存档 →
 *    用修正后的输入把预测帧重放一遍（毫秒级，画面表现为 1 帧闪变）。
 *    本地输入延迟不再需要盖住整个 RTT（旧建议公式 ceil(RTT/2/16.7)+2 ≈
 *    半 RTT —— 中转拓扑下实际需要全 RTT，不够的部分全靠卡顿硬扛，
 *    这就是“同步没问题但延迟非常大”的根因）。回滚模式下延迟只要低帧数，
 *    其余 RTT 由预测+回滚吸收 → 手感从“RTT 级”降到“延迟帧级”。
 *    延迟帧数（inputDelay）仍由服务器统一裁决下发（保证各端执行同一
 *    (帧号→输入) 序列 —— 混合房间与 v4.3 客户端同场不发散）；纯 v4.4
 *    房间建议值 2 帧 → 全员 2 帧（服务器 inputDelay=0 自动模式）。
 *  - **★★ v4.4 观战模式**：spectate=true 加入 → 不占 1P-4P 槽位、不采样
 *    不发送，只收全员输入（阻塞等待全部活跃槽位 → 同画面观战）。
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
    val platform: GamePlatform = GamePlatform.ARCADE,
    /** ★★ v4.4：观战模式（不占槽位、不发送输入、阻塞等全员输入同画面观战） */
    private val spectate: Boolean = false,
    /** ★★ v4.4：期望座位 2/3/4（服务器 hello 回执的 slot 为准；0=自动） */
    private val wantSlot: Int = 0
) : NetplayHook {

    /**
     * ★★ v4.4 回滚联机：即时存档 I/O 桥（由 EmulatorScreen 注入 —— 它持有引擎）。
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
        /** ★★ v4.4 回滚：绕过主循环直接执行一帧（重放用；false=引擎不支持 → 回滚模式禁用）。 */
        fun runFrameDirect(pads: IntArray): Boolean = false
        /** ★★ v4.4 回滚：引擎是否支持回滚（快速存档 + 直接跑帧）。 */
        fun supportsRollback(): Boolean = false
        /** ★★ v4.4 回滚：丢弃待播音频样本（回滚重放前调用，防重复音效）。 */
        fun discardPendingAudio() {}
        /** ★★ v4.5.1 全员重开：核心复位到开机状态（引擎线程内调用；
         *  false = 引擎不支持复位 → 重开不可用，控制器干净退出）。 */
        fun resetToBoot(): Boolean = false
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
        /** ★★ v4.4：观战者加入/离开（不占座位，UI 显示“xx 正在观战”）。 */
        fun onSpectatorJoined(username: String) {}
        /** ★★ v4.4：观战者离开。 */
        fun onSpectatorLeft(username: String) {}
        /** ★★ v4.4 回滚统计（每 ~0.5s 一次：帧号 / 延迟帧 / 回滚次数 / 最深重放帧数）。 */
        fun onNetplayStats(frame: Long, delayFrames: Int, rollbacks: Int, maxReplayDepth: Int) {}
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

    companion object {
        /** 回滚检查点环大小（文件数 × 存档间隔 = 纠错覆盖窗口，32 帧 ≈ 533ms） */
        private const val RB_RING = 32
        /** 预测深度上限（帧）：确认帧落后超过它就不再预测 → 阻塞等待（退化延迟模式，防不可纠正的深预测） */
        private const val RB_MAX_PREDICT = 12
        /** 单次回滚最大重放帧数（性能保护；超过则放弃纠正并提示重进房间） */
        private const val RB_MAX_REPLAY = 96
        /** 检查点无效哨兵（tag 可为负 —— warmup 帧也存，作 frame 0 的重放基点） */
        private val RB_INVALID = java.lang.Long.MIN_VALUE
        /** RTT 探测次数/间隔（★★ v4.4 提速：4×1300ms+180ms×3 最坏 ~6s → 3×800ms+60ms×2 最坏 ~2.5s） */
        private const val RTT_SAMPLES = 3
        private const val RTT_INTERVAL_MS = 60L
        private const val RTT_TIMEOUT_MS = 800L
        /** ★★ v4.5 服务器侧存档：房主快照上传节奏 —— 按档大小分级（小档传得勤，
         *  回退窗口短；大档适当拉长间隔省流量）。首份在探测时（引擎就绪）就传。 */
        private const val UPLOAD_GAP_SMALL = 360L   // ≤128KB → 每 6s（帧）
        private const val UPLOAD_GAP_MEDIUM = 600L  // ≤512KB → 每 10s
        private const val UPLOAD_GAP_LARGE = 900L   // 更大 → 每 15s
        private const val UPLOAD_SIZE_SMALL = 128 * 1024
        private const val UPLOAD_SIZE_MEDIUM = 512 * 1024
        private const val UPLOAD_FIRST_FRAME = 60L
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

    /** 本端槽位（1P=1…4P=4，服务器 hello 下发；★★ v4.4 观战者恒 0）。 */
    @Volatile var mySlot: Int = 1
        private set

    /** ★★ v4.4：本端是否观战者（构造指定；不占座位、只收不发）。 */
    val isSpectator: Boolean get() = spectate || role == "spectator"

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

    // === ★★ v4.5 服务器侧存档：房主周期快照上传 ===
    /** 上一次已上传的网络帧号（-1 = 还没传过；仅引擎线程写）。 */
    private var lastUploadFrame = -1L
    /** 上传进行中（防堆积：上一份还没发完就到了下一份 → 跳过本次）。 */
    private val uploadInFlight = java.util.concurrent.atomic.AtomicBoolean(false)
    /** 存档文件读写串行化（引擎线程写 / 上传线程读）。 */
    private val uploadLock = Any()
    /** 下次上传的帧间隔（按上一份档大小自适应；探测/上传线程写、引擎线程读）。 */
    @Volatile private var uploadGapFrames = UPLOAD_GAP_SMALL

    // === ★★ v4.5.1 出档能力 + 全员重开 ===
    /** 本端能否出存档快照（引擎就绪时探测；0 未知 / 1 能 / -1 不能）。
     *  ★ 部分核心/驱动不支持快照（FBNeo 的 PGM/改版等）且 JNI 永远返回 true
     *   —— 只能靠“存档文件非空”判定。探测失败 → 快照同步/回滚都不可用，
     *   服务器会对这类房间改走“全员重开”（新人加入 = 新的一局）。 */
    @Volatile private var stateCap = 0
    /** 待应用的全员重开请求（网络线程置位；引擎线程在 beforeFrame 串行应用
     *  —— 与核心复位（retro_reset）无并发）。 */
    @Volatile private var restartPending = false

    // === ★★ v4.4 回滚网络码（rollback）状态 ===
    /** 回滚模式启用（引擎支持快速存档 && 非观战；notifyEngineReady 时评估）。 */
    @Volatile private var rollbackMode = false
    /** 每帧实际使用的 4 手柄输入（网络帧 → pads 拷贝）—— 重放与纠错比对的依据。 */
    private val inputLog = java.util.concurrent.ConcurrentHashMap<Long, IntArray>()
    /** 各槽位最新已知输入（预测策略：延续对手上一输入）。 */
    private val rbLastKnownPad = java.util.concurrent.atomic.AtomicIntegerArray(5)
    /** 各槽位最新已到达帧号（确认帧 = 活跃远端槽位最小值；-1=未收到）。 */
    private val rbSlotLatest = java.util.concurrent.atomic.AtomicLongArray(5)
    /** 最早待纠正帧（Long.MAX_VALUE = 无；网络线程 CAS 取 min，引擎线程消费）。 */
    private val rbPendingFrom = java.util.concurrent.atomic.AtomicLong(Long.MAX_VALUE)
    /** 最后已执行的网络帧（execFrame = netFrame - inputDelay）。 */
    @Volatile private var rbLastExecuted = -1L
    /** ★ 待应用的时间线重置帧（Long.MIN_VALUE = 无；网络线程请求，引擎线程应用）。 */
    @Volatile private var rbResetFrame = java.lang.Long.MIN_VALUE
    /** 检查点环：槽 i 文件对应的帧号（RB_INVALID=无效；仅引擎线程访问）。
     *  ★★ tag = 检查点包含的最后一个【执行帧】号（= netFrame - inputDelay），
     *    可为负（warmup 阶段 —— 作 frame 0 的重放基点）。仿真验证：按执行帧
     *    标记才能保证重放起点与快照内容严格衔接（按网络帧标记会丢 D 帧）。 */
    private val rbCkFrame = LongArray(RB_RING) { RB_INVALID }
    /** 检查点存档间隔（1=每帧；存档慢的设备自适应升到 2 降 CPU）。 */
    private var rbSaveEvery = 1
    private var rbSaveCounter = 0
    /** 存档耗时 EWMA(ms) —— 自适应间隔依据。 */
    private var rbSaveMsAvg = 2.0
    /** 统计：回滚次数 / 最深重放。 */
    @Volatile private var rbCount = 0L
    @Volatile private var rbMaxDepth = 0

    private val netListener = object : BattleNetplay.Listener {
        override fun onRemoteInput(frame: Long, pad: Int, slot: Int) {
            if (slot in 1..4) {
                padsHistory[slot][frame] = pad
                // ★★ v4.4 回滚：记录最新已知输入/帧号；已执行帧与预测不一致 → 标记待回滚
                if (rollbackMode) {
                    rbLastKnownPad.set(slot, pad)
                    val prev = rbSlotLatest.get(slot)
                    if (frame > prev) rbSlotLatest.set(slot, frame)
                    if (frame <= rbLastExecuted) {
                        val used = inputLog[frame]
                        if (used != null && used[slot - 1] != pad) {
                            // 取最早待纠正帧（CAS min，网络线程并发安全）
                            var p = rbPendingFrom.get()
                            while (frame < p) {
                                if (rbPendingFrom.compareAndSet(p, frame)) break
                                p = rbPendingFrom.get()
                            }
                        }
                    }
                }
            }
        }

        override fun onReady(role: String, inputDelay: Int, slot: Int, players: List<Int>, playing: Boolean) {
            this@NetplayController.inputDelay = inputDelay.coerceIn(0, 16)
            this@NetplayController.role = role
            // ★★ v4.4：观战者槽位恒 0（服务器下发 slot=0；防 coerceIn 把 0 抬成 1）
            mySlot = if (role == "spectator" || spectate) 0 else slot.coerceIn(1, 4)
            activeSlots.clear()
            activeSlots.addAll(players)
            dispatch { it.onReady(role, inputDelay) }
            dispatch { it.onReadyDetail(role, inputDelay, mySlot, players, playing) }
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
            // 后加入同步开始：运行中的引擎线程在下一帧前挂起。
            // ★★ v4.4：同时作废回滚状态（时间线即将被存档同步重置，旧检查点
            //   不再适用；恢复后首帧重建检查点）。
            if (started) {
                syncPaused = true
                rbRequestReset(-1L)
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
                    try { net?.sendStateFail() } catch (_: Throwable) {}
                    resumeInternal(null, inputDelay, activeSlots.toList())
                }
            }, "battle-state-host").apply { isDaemon = true }.start()
        }

        override fun onStateData(frame: Long, dataBase64: String, hist: Map<Int, List<Int>>?, histEnd: Long) {
            // ★★ v4.5：收到服务器转发的存档 —— 只做确认，不在这里装载。
            //   装载统一放到 onResume（全员同一份档、同一帧号恢复）：
            //   同步若中止（房主失败/超时/接入者离开），无人装载、全员从各自
            //   暂停帧继续 —— 不会出现“有人装了旧档、有人继续原时间线”的分叉。
            val n = net
            if (!started && n != null) {
                // 新玩家（还没开跑）：确认收到 → 服务器收到后广播全员 resume
                n.sendStateOk()
                dispatch { it.onSyncStatus("同步画面已就绪（帧 #$frame），等待全员恢复…") }
            } else {
                // 运行中的成员：等 resume 一起装载
                dispatch { it.onSyncStatus("新玩家接入：等待全员装载恢复…") }
            }
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

        override fun onRestart(inputDelay: Int, players: List<Int>) {
            // ★★ v4.5.1 全员重开（本作核心不支持画面快照同步 —— 服务器编排）：
            //   本端复位控制器簿记 + 置 restartPending（引擎线程下一个 beforeFrame
            //   串行做核心复位 retro_reset，无并发），随后回到启动门等全员重新
            //   ready → 服务器广播 start → 全员从帧 0（开机状态）同步开跑。
            dispatch { it.onSyncStatus("新玩家接入：本作不支持画面快照，全员重新开局…") }
            android.util.Log.i("NetplayController", "restart: 全员重开（players=$players delay=$inputDelay）")
            val wasLoaded = engineLoaded
            restartPending = true
            // 先开门让引擎线程能走到复位点（同步暂停门/启动门都放行到重启检查）
            synchronized(syncGate) { syncPaused = false; syncGate.notifyAll() }
            synchronized(startGate) { started = false; startGate.notifyAll() }
            readySent = false
            engineLoaded = false
            lastUploadFrame = -1L
            uploadGapFrames = UPLOAD_GAP_SMALL
            parkedLocalFrame = -1L
            frameOffset = 0L
            pendingSlots.clear()
            if (players.isNotEmpty()) {
                activeSlots.clear()
                activeSlots.addAll(players)
            }
            if (wasLoaded) {
                // 已加载完的端：等引擎线程应用完复位后重新探测能力 + 发 ready
                Thread({
                    var waitMs = 0L
                    while (restartPending && waitMs < 5000) {
                        Thread.sleep(10); waitMs += 10
                    }
                    notifyEngineReady()
                }, "battle-restart-ready").apply { isDaemon = true }.start()
            }
            // 引擎还在加载的端：复位对它本来就是开机态，等自然加载完成 →
            // EmulatorScreen 会调 notifyEngineReady（engineLoaded 已复位）
        }

        override fun onPeerReady(username: String) {
            dispatch { it.onPeerReady(username) }
        }

        override fun onPeerJoined(username: String, slot: Int, spec: Boolean) {
            if (spec) {
                // ★★ v4.4：观战者加入（不占座位，不影响槽位/等待）
                dispatch { it.onSpectatorJoined(username) }
                return
            }
            if (slot in 1..4) pendingSlots.add(slot)
            dispatch { it.onPeerJoined(username) }
            dispatch { it.onPeerJoinedSlot(username, slot) }
        }

        override fun onPeerLeft(username: String, slot: Int, spec: Boolean) {
            if (spec) {
                dispatch { it.onSpectatorLeft(username) }
                return
            }
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

    /** 房主：等引擎暂停在帧 F → 存档 → 近 16 帧输入 → sendState。
     * ★★ v4.5：任一失败路径都先向服务器上报 state_fail（服务器立即收尾同步：
     * 有缓存档则全员按缓存档恢复，否则接入者劝退）—— 绝不让房间卡在“同步中”。 */
    private fun hostProduceState() {
        val io = stateIo
        val n = net
        if (io == null || n == null) {
            dispatch { it.onSyncStatus("同步失败：无存档通道") }
            try { n?.sendStateFail() } catch (_: Throwable) {}
            resumeInternal(null, inputDelay, activeSlots.toList())
            return
        }
        // 1. 等待引擎线程挂到门上（它记录 parkedLocalFrame）
        var waitMs = 0L
        while (parkedLocalFrame < 0 && waitMs < 5000) {
            if (!started) {
                // 尚未开跑（理论不可达：state_req 仅在 playing 时来）—— 上报失败收尾
                try { n.sendStateFail() } catch (_: Throwable) {}
                return
            }
            Thread.sleep(10); waitMs += 10
        }
        val parkedLocal = parkedLocalFrame
        if (parkedLocal < 0) {
            // 引擎 5s 未暂停（异常）：上报失败 + 本地解除暂停
            dispatch { it.onSyncStatus("同步超时：继续对战") }
            try { n.sendStateFail() } catch (_: Throwable) {}
            resumeInternal(null, inputDelay, activeSlots.toList())
            return
        }
        val stateFrame = parkedLocal + frameOffset   // 房主 offset 恒 0，双保险
        // 2. 即时存档（引擎线程挂在门上，安全）
        val file = File(io.stateDir(), "battle_join_sync.state")
        if (!io.saveStateTo(file)) {
            dispatch { it.onSyncStatus("同步失败：存档失败") }
            try { n.sendStateFail() } catch (_: Throwable) {}
            resumeInternal(null, inputDelay, activeSlots.toList())
            return
        }
        val bytes = try { file.readBytes() } catch (_: Throwable) { null }
        if (bytes == null || bytes.isEmpty()) {
            dispatch { it.onSyncStatus("同步失败：存档为空") }
            try { n.sendStateFail() } catch (_: Throwable) {}
            resumeInternal(null, inputDelay, activeSlots.toList())
            return
        }
        // 3. 近 16 帧各槽位输入（resume 后 inputDelay 窗口内要用）
        val hist = buildHist(stateFrame)
        // 4. 上报（base64 单行 JSON；FBNeo 街机存档一般几十~几百 KB）
        val b64 = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
        n.sendState(stateFrame, b64, hist)
        dispatch { it.onSyncStatus("已发送同步画面（${bytes.size / 1024}KB），等待装载…") }
    }

    /** 槽位 -> 近 16 帧 pad 序列（止于 stateFrame 前；缺帧按 0 —— 全员一致即可）。
     *  房主同步出档（hostProduceState）与周期快照上传（uploadSnapshot）共用。 */
    private fun buildHist(stateFrame: Long): Map<Int, List<Int>> {
        val hist = HashMap<Int, List<Int>>()
        for (slot in 1..4) {
            val pads = ArrayList<Int>(16)
            for (f in stateFrame - 16 until stateFrame) {
                pads.add(padsHistory[slot][f] ?: 0)
            }
            hist[slot] = pads
        }
        return hist
    }

    /** ★★ v4.5 房主周期快照上传（引擎线程调用）：出一份档 → 后台线程编码发送。
     *  服务器缓存这份档 —— 2P/3P/4P/观战加入时直接从服务器拿（房主掉线或
     *  加入瞬间出档失败也有档可用），不再依赖加入瞬间房主本地出档。
     *  出档与读文件经 uploadLock 串行化（防文件被下一轮覆盖时读到半截）。 */
    private fun uploadSnapshot(netFrame: Long) {
        val io = stateIo ?: return
        val n = net ?: return
        if (!uploadInFlight.compareAndSet(false, true)) return // 上一份还在发：跳过本轮
        val file = File(io.stateDir(), "battle_upload.state")
        val ok = try {
            synchronized(uploadLock) { io.saveStateTo(file) }
        } catch (t: Throwable) {
            android.util.Log.w("NetplayController", "upload saveState: ${t.message}"); false
        }
        if (!ok) {
            uploadInFlight.set(false)
            return
        }
        // afterFrame(netFrame)：帧 [0..netFrame] 已执行完 → 下一帧从 netFrame+1 起
        val stateFrame = netFrame + 1
        Thread({
            try {
                val bytes = synchronized(uploadLock) {
                    try { file.readBytes() } catch (_: Throwable) { null }
                }
                if (bytes != null && bytes.isNotEmpty()) {
                    val b64 = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
                    n.sendStateUpload(stateFrame, b64, buildHist(stateFrame))
                    uploadGapFrames = gapForSize(bytes.size.toLong())
                    android.util.Log.i("NetplayController",
                        "快照已上传（帧 $stateFrame，${bytes.size / 1024}KB）")
                }
            } catch (t: Throwable) {
                android.util.Log.w("NetplayController", "upload send: ${t.message}")
            } finally {
                uploadInFlight.set(false)
            }
        }, "battle-state-upload").apply { isDaemon = true }.start()
    }

    /** resume：全员按统一存档/帧号/延迟恢复。data 空 = 仅解除暂停（同步中止兜底）。
     *
     * ★★ v4.5 统一装载：房主也不再跳过 —— 服务器可能下发“缓存档”（房主出档
     *   失败/掉线时的回退路径，比房主当前进度旧），全员装载同一份才能保证一致；
     *   房主自己刚出的新鲜档装载 = 无操作，行为不变。帧号重对齐全员统一：
     *   frameOffset = frame - parkedLocal（新鲜档 = 0；缓存档 = 负偏移回退，仍同帧）。 */
    private fun resumeInternal(dataBase64: String?, finalDelay: Int, players: List<Int>,
                               frame: Long = -1L, hist: Map<Int, List<Int>>? = null, histEnd: Long = 0L) {
        val io = stateIo
        if (dataBase64 != null && frame >= 0 && io != null) {
            val bytes = try {
                android.util.Base64.decode(dataBase64, android.util.Base64.NO_WRAP)
            } catch (_: Throwable) { null }
            if (bytes != null && bytes.isNotEmpty()) {
                // 引擎线程此刻挂在门上（未 start → startGate；已 start → syncGate）
                val parkedLocal = if (parkedLocalFrame >= 0) parkedLocalFrame else 0L
                val file = File(io.stateDir(), "battle_join_sync.state")
                val loaded = try {
                    file.writeBytes(bytes); io.loadStateFrom(file)
                } catch (_: Throwable) { false }
                if (!loaded) {
                    // 装载失败 = 必然发散：干净退出（引导重进房间），绝不带病继续
                    dispatch { it.onNetplayLost("同步画面装载失败，请退出后重新进入房间") }
                    try { net?.close() } catch (_: Throwable) {}
                    return
                }
                frameOffset = frame - parkedLocal
            }
        }
        if (hist != null && frame >= 0) seedHist(hist, histEnd)
        // ★★ v4.4：resume 帧之后的历史输入全部作废（旧时间线残留会被重新采样
        //   覆盖 —— 房主与成员统一清理，避免任何一端误用旧值）；回滚簿记重建
        //   （引擎线程过门后统一应用）。
        rbRequestReset(if (frame >= 0) frame else -1L)

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

    /**
     * ★★ v4.5.1 全员重开：引擎线程串行应用（beforeFrame 顶部 —— 与核心无并发）。
     * 核心复位到开机态（StateIo.resetToBoot：FBNeo = retro_reset + 重挂 frameHook
     * 顺带清零引擎帧计数），控制器簿记全部归零；复位后引擎停回启动门，
     * 等全员重新 ready → 服务器广播 start → 从帧 0 同步开跑。
     */
    private fun applyRestartOnEngineThread() {
        restartPending = false
        val io = stateIo
        if (io != null) {
            val ok = try { io.resetToBoot() } catch (t: Throwable) {
                android.util.Log.w("NetplayController", "resetToBoot failed: ${t.message}"); false
            }
            if (!ok) {
                // 引擎不支持复位（非 FBNeo 等）：干净退出，引导重进
                dispatch { it.onNetplayLost("该核心不支持中途加入（无法重开），请全员退出后重新开局") }
                try { net?.close() } catch (_: Throwable) {}
                return
            }
        }
        synchronized(startGate) { started = false }
        parkedLocalFrame = -1L
        frameOffset = 0L
        for (s in 1..4) padsHistory[s].clear()
        inputLog.clear()
        pendingSlots.clear()
        desyncCount = 0
        rbRequestReset(-1L)   // 回滚簿记全部作废（rbApplyReset(-1)：清 inputLog + 检查点 + 置 rbLastExecuted=-1）
        rbCount = 0L
        rbMaxDepth = 0
        dispatch { it.onSyncStatus("已重开：等待全员就绪同步开跑…") }
        android.util.Log.i("NetplayController", "restart applied on engine thread")
    }

    /** ★ v4.3 注入存档通道（EmulatorScreen 持有引擎，进入联机界面时调用）。 */
    fun setStateIo(io: StateIo?) { stateIo = io }

    /**
     * 连接到中继服务器。非阻塞：[BattleNetplay.connect] 在自己的线程里完成 TCP 握手。
     */
    fun connect(host: String, port: Int, token: String, roomId: String) {
        // ★★ v4.4：透传观战模式 + 期望座位（服务器 spectate/want 握手）
        val n = BattleNetplay(host, port, token, roomId, netListener, spectate, wantSlot)
        net = n
        n.connect()
    }

    /**
     * 引擎加载完毕通知（由 EmulatorScreen 在 ROM 加载完成、模拟循环就位后调用；
     * ★★ v4.5.1 全员重开后也会再次调用）。触发：出档能力探测 → 首份快照上传
     * （房主）→ RTT 测量 → 建议 inputDelay → 发 ready（带 stateCap）。
     *
     * ★★ v4.5.1 出档能力探测：此刻引擎线程正停在启动门（beforeFrame 阻塞）——
     *   天然的安全窗口，跨线程出一份档验证能否快照（部分核心/驱动不支持，
     *   且 JNI 的 saveState 永远返回 true，只能靠“文件存在且非空”判定）。
     *   探测结果：① 随 ready 上报服务器（stateCap —— 服务器据此决定快照同步
     *   还是全员重开）；② 门控回滚模式（出不了档就没法回滚）；③ 成功则这份
     *   探测档直接当首份快照上传（服务器立刻有档可给后来者）。
     */
    fun notifyEngineReady() {
        if (engineLoaded) return
        engineLoaded = true
        val n = net ?: return
        if (readySent) return
        readySent = true

        // ★★ v4.5.1 出档能力探测（引擎停在启动门的安全窗口内）
        var capOk = false
        var probeBytes: ByteArray? = null
        val io = stateIo
        if (io != null && !isSpectator) {
            try {
                val file = File(io.stateDir(), "battle_probe.state")
                val saved = synchronized(uploadLock) { io.saveStateTo(file) }
                probeBytes = if (saved) {
                    synchronized(uploadLock) { try { file.readBytes() } catch (_: Throwable) { null } }
                } else null
                capOk = probeBytes != null && probeBytes!!.isNotEmpty()
            } catch (t: Throwable) {
                android.util.Log.w("NetplayController", "state capability probe: ${t.message}")
                capOk = false
            }
        }
        stateCap = if (isSpectator) 0 else if (capOk) 1 else -1
        // ★★ v4.4 回滚模式评估：必须真能出档（探测通过）才启用 —— 否则检查点
        //   全是空档，回滚形同虚设还白费存档开销
        rollbackMode = !spectate && capOk
        android.util.Log.i("NetplayController",
            "engine ready: stateCap=$stateCap rollback=$rollbackMode (platform=$platform)")

        // ★★ v4.5.1 首份快照：房主且能出档 → 探测档（开机态，帧 0）直接上传，
        //   服务器即刻有档 —— 加入者第一时间就能走“服务器存档同步”
        if (capOk && role == "host" && !isSpectator && probeBytes != null) {
            try {
                val b64 = android.util.Base64.encodeToString(probeBytes, android.util.Base64.NO_WRAP)
                n.sendStateUpload(0L, b64, emptyMap())
                lastUploadFrame = 0L
                uploadGapFrames = gapForSize(probeBytes.size.toLong())
                android.util.Log.i("NetplayController", "首份快照已上传（${probeBytes.size / 1024}KB，开机态）")
            } catch (t: Throwable) {
                android.util.Log.w("NetplayController", "first upload: ${t.message}")
            }
        }

        Thread({
            var minRtt: Long? = null
            for (i in 0 until RTT_SAMPLES) {
                val latch = java.util.concurrent.CountDownLatch(1)
                var rtt: Long? = null
                try {
                    n.measureRtt(RTT_TIMEOUT_MS) { ms ->
                        rtt = ms
                        latch.countDown()
                    }
                } catch (_: Throwable) {
                    latch.countDown()
                }
                try { latch.await(RTT_TIMEOUT_MS + 200, java.util.concurrent.TimeUnit.MILLISECONDS) } catch (_: InterruptedException) {}
                rtt?.let { ms -> minRtt = if (minRtt == null) ms else minOf(minRtt!!, ms) }
                if (i < RTT_SAMPLES - 1) {
                    try { Thread.sleep(RTT_INTERVAL_MS) } catch (_: InterruptedException) { return@Thread }
                }
            }
            // ★★ v4.4 建议公式（中继拓扑修正）：
            //   回滚模式 → 2（RTT 由预测+回滚吸收，各端一致由服务器裁决保证）；
            //   延迟模式 → ceil(RTT/16.67)+1（全 RTT + 1 帧裕量）；
            //   观战 → 4（不参与手感，仅占位）。
            val suggested = when {
                rollbackMode -> 2
                spectate -> 4
                else -> when (val r = minRtt) {
                    null -> 4
                    else -> (Math.ceil(r / 16.667) + 1).toInt().coerceIn(2, 16)
                }
            }
            android.util.Log.i("NetplayController",
                "RTT min=${minRtt}ms rollback=$rollbackMode → suggestedDelay=${suggested}f")
            try { n.sendReady(suggested, stateCap) } catch (_: Throwable) {}
        }, "battle-rtt-probe").apply { isDaemon = true }.start()
    }

    /** 按档大小选下次上传间隔（小档传得勤 —— 回退窗口短）。 */
    private fun gapForSize(bytes: Long): Long =
        if (bytes <= UPLOAD_SIZE_SMALL) UPLOAD_GAP_SMALL
        else if (bytes <= UPLOAD_SIZE_MEDIUM) UPLOAD_GAP_MEDIUM
        else UPLOAD_GAP_LARGE

    /** 本地玩家摇杆输入（bit 布局与平台 pad 一致，由 OnScreenController / 物理手柄推送）。 */
    fun setLocalPad(bits: Int) {
        currentLocalPad = bits
    }

    /** 本端槽位（1P=1…4P=4）。 */
    val slot: Int get() = mySlot

    /**
     * 引擎在调用 runFrame 之前回调：采样本地输入 → 发给服务器 → 拿到所有
     * 活跃槽位的同帧输入。返回 4 手柄（pad1..pad4）。
     *
     * 1. 启动门：未 start（新玩家 / 同步重启）→ 记录 parkedLocalFrame 并阻塞。
     * 2. 同步暂停门：后加入同步中 → 记录 parkedLocalFrame 并阻塞。
     * 3. ★★ v4.4 观战模式：不采样不发送，阻塞等全员输入。
     * 4. ★★ v4.4 回滚模式：先处理待纠正帧（回滚重放），再采样发送，
     *    远端未到输入用“延续上一输入”预测（窗口内），返回后引擎立即跑帧。
     * 5. 延迟模式（原逻辑）：阻塞等所有活跃远端槽位的 execFrame 输入【绝不兜底】。
     */
    private fun beforeFrameImpl(frame: Long): IntArray? {
        // 0. ★★ v4.5.1 全员重开（引擎线程串行应用 —— 与核心复位 retro_reset
        //    无并发；复位后走启动门等全员重新 ready → start）
        if (restartPending) {
            applyRestartOnEngineThread()
        }
        // 1. 启动门（★ 重开请求 = 临时放行去应用复位，不死等）
        if (!started) {
            synchronized(startGate) {
                while (!started && !restartPending) {
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
                while (syncPaused && !restartPending) {
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
        // 门放行后若又有重开请求 → 本次帧跳过（下一迭代在顶部应用复位）
        if (restartPending) {
            return IntArray(4)
        }

        val netFrame = frame + frameOffset
        val n = net

        // ★★ v4.4 时间线重置应用（pause→state→resume 后新时间线首帧；
        //   仅此处（引擎线程）触碰 rbCkFrame/inputLog 结构性重置）
        val resetFrame = rbResetFrame
        if (resetFrame != java.lang.Long.MIN_VALUE) {
            rbResetFrame = java.lang.Long.MIN_VALUE
            rbApplyReset(resetFrame)
        }

        // ★★ v4.4 观战模式：不占座位、不采样不发送；等全员输入同画面观战。
        if (isSpectator) {
            if (n == null) return IntArray(4)
            inputDelay = n.inputDelay.coerceIn(0, 16)
            val execFrame = netFrame - inputDelay
            if (execFrame < 0) return IntArray(4) // warmup：全 0
            val pads = IntArray(4)
            for (slot in 1..4) {
                if (activeSlots.contains(slot)) {
                    var pad = padsHistory[slot][execFrame]
                    if (pad == null) pad = awaitSlotInput(slot, execFrame)
                    if (pad == null) return IntArray(4) // 断连：停模拟
                    pads[slot - 1] = pad
                }
            }
            return pads
        }

        // ★★ v4.4 回滚模式（预测 + 纠错重放，手感 = 延迟帧级而非 RTT 级）
        if (rollbackMode && stateIo != null) {
            return rbBeforeFrame(netFrame)
        }

        // ---- 延迟模式（v4.3 原逻辑）----
        val localPad = currentLocalPad
        if (mySlot in 1..4) padsHistory[mySlot][netFrame] = localPad

        if (n == null) {
            val pads = IntArray(4)
            if (mySlot in 1..4) pads[mySlot - 1] = localPad
            return pads
        }

        inputDelay = n.inputDelay.coerceIn(0, 16)

        // 3. 发送本帧输入（带槽位，非阻塞）
        if (mySlot in 1..4) {
            try {
                n.sendInput(netFrame, localPad, mySlot)
            } catch (t: Throwable) {
                android.util.Log.w("NetplayController", "sendInput failed: ${t.message}")
            }
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
            // ★★ v4.5.1 全员重开请求：立即让路（引擎线程下一迭代应用复位）
            if (restartPending) return null
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
        // ★★ v4.5 服务器侧存档：房主周期上传快照 → 服务器缓存（供 2P/3P/4P/观战
        //   加入时同步 —— “存档同步在服务器”）。引擎线程里出档、后台线程编码发送；
        //   暂停/同步期间引擎不跑帧，自然不会上传。间隔按档大小自适应。
        if (started && !isSpectator && role == "host" && netFrame >= 0 && stateCap > 0) {
            val due = if (lastUploadFrame < 0L) netFrame >= UPLOAD_FIRST_FRAME
                      else netFrame - lastUploadFrame >= uploadGapFrames
            if (due) {
                lastUploadFrame = netFrame
                uploadSnapshot(netFrame)
            }
        }
        if (rollbackMode) {
            // ★★ v4.4 回滚：引擎刚跑完 netFrame → 核心状态 = 执行帧 [0..netFrame-inputDelay]
            //   的折叠 → 写检查点（tag = 最后执行帧；warmup 阶段 tag 为负也存）。
            //   重放帧不走 here（runFrameDirect 不触发 afterFrame），不会重复记账。
            val io = stateIo
            if (io != null && netFrame >= 0) {
                rbSaveCounter++
                if (rbSaveCounter >= rbSaveEvery) {
                    rbSaveCounter = 0
                    rbSaveCheckpoint(io, netFrame - inputDelay)
                }
            }
            if (netFrame % 30 == 0L) {
                val cnt = rbCount.toInt(); val dep = rbMaxDepth
                val il = inputDelay
                dispatch { it.onNetplayStats(netFrame, il, cnt, dep) }
            }
            if (netFrame % 256 == 0L) {
                cleanupHistory(netFrame)
                val cutoff = netFrame - 512
                if (cutoff > 0) inputLog.keys.removeAll { it < cutoff }
            }
        } else if (netFrame % 256 == 0L) {
            cleanupHistory(netFrame)
        }
    }

    // =========================================================================
    // ★★ v4.4 回滚网络码（rollback netcode）：预测 + 检查点 + 纠错重放
    // =========================================================================

    /**
     * 回滚模式每帧入口（引擎线程，beforeFramePads 内）。
     *
     * 1. 有待纠正帧 → 先回滚重放（装载检查点 + 修正后的输入重跑预测帧）；
     *    覆盖不了（刚恢复/存档失败）→ 退化为阻塞等待实际输入。
     * 2. 采样本地输入 + 发送（与延迟模式一致，帧号/槽位协议不变）。
     * 3. 执行帧 = netFrame - inputDelay（服务器裁决值；各端一致保证同步）。
     * 4. 远端输入未到 → 窗口内用“延续上一输入”预测；窗口外 → 阻塞等待。
     */
    private fun rbBeforeFrame(netFrame: Long): IntArray? {
        val io = stateIo ?: return null
        val n = net ?: return null

        // 1. 待纠正帧处理（回滚重放；失败 → 阻塞等待退化）
        val pending = rbPendingFrom.get()
        if (pending != Long.MAX_VALUE) {
            if (!rbPerformRollback(io, pending)) {
                android.util.Log.w("NetplayController",
                    "rollback unavailable (pending=$pending lastExec=$rbLastExecuted) → block-wait")
                rbBlockWaitFor(pending)
            }
        }

        // 2. 采样 + 发送
        val localPad = currentLocalPad
        if (mySlot in 1..4) {
            padsHistory[mySlot][netFrame] = localPad
            try {
                n.sendInput(netFrame, localPad, mySlot)
            } catch (t: Throwable) {
                android.util.Log.w("NetplayController", "sendInput failed: ${t.message}")
            }
        }

        // 3. 执行帧 = 网络帧 - inputDelay（服务器裁决，保证各端同序列）
        inputDelay = n.inputDelay.coerceIn(0, 16)
        val execFrame = netFrame - inputDelay
        if (execFrame < 0) return IntArray(4) // warmup：全 0，各端一致

        // 4. 组装 4 手柄（未到输入 → 窗口内预测；窗口外阻塞等待）
        val pads = IntArray(4)
        val confirmed = rbConfirmedFrame()
        for (slot in 1..4) {
            when {
                slot == mySlot -> pads[slot - 1] = padsHistory[slot][execFrame] ?: localPad
                activeSlots.contains(slot) -> {
                    var pad = padsHistory[slot][execFrame]
                    if (pad == null) {
                        // 预测窗口：确认帧落后 ≤ RB_MAX_PREDICT 且有可用检查点才预测；
                        // 否则阻塞等待（退化延迟模式 —— 绝不执行无法纠正的深预测）。
                        val canPredict = confirmed >= 0 &&
                            (execFrame - confirmed) <= RB_MAX_PREDICT &&
                            rbBestCheckpointBelow(confirmed + 1) != RB_INVALID
                        if (canPredict) {
                            pad = rbLastKnownPad.get(slot) // 预测：延续对手上一输入
                        } else {
                            pad = awaitSlotInput(slot, execFrame)
                            if (pad == null) return IntArray(4) // 断连：停模拟
                        }
                    }
                    pads[slot - 1] = pad
                }
                else -> pads[slot - 1] = 0 // 未激活/接入中槽位恒 0（各端一致）
            }
        }
        inputLog[execFrame] = pads.copyOf()
        rbLastExecuted = execFrame
        return pads
    }

    /** 回滚模式当前确认帧（全部活跃远端槽位输入都已到达的最大帧；无远端 = MAX）。 */
    private fun rbConfirmedFrame(): Long {
        var min = Long.MAX_VALUE
        for (s in activeSlots) {
            if (s != mySlot) {
                val f = rbSlotLatest.get(s)
                if (f < min) min = f
            }
        }
        return min
    }

    /** 检查点环里 < [target] 的最新检查点帧号（RB_INVALID = 无）。仅引擎线程。 */
    private fun rbBestCheckpointBelow(target: Long): Long {
        var best = RB_INVALID
        for (i in 0 until RB_RING) {
            val f = rbCkFrame[i]
            if (f != RB_INVALID && f < target && (best == RB_INVALID || f > best)) best = f
        }
        return best
    }

    /** 检查点环槽位（tag 可为负 → 归一化到 [0, RB_RING)）。 */
    private fun rbSlot(tag: Long): Int = ((tag % RB_RING) + RB_RING).toInt() % RB_RING

    /** 检查点存档文件（环槽 tag 归一化）。 */
    private fun rbFile(io: StateIo, tag: Long) = File(io.stateDir(), "battle_rb_${rbSlot(tag)}.state")

    /**
     * 写检查点（引擎线程；调用时核心状态必须 = S(frame) —— afterFrame(netFrame)
     * 或回滚重放刚跑完 rbLastExecuted 时）。存档慢的设备自适应升到每 2 帧一存。
     */
    private fun rbSaveCheckpoint(io: StateIo, tag: Long) {
        val idx = rbSlot(tag)
        val t0 = System.nanoTime()
        val ok = try {
            io.saveStateTo(rbFile(io, tag))
        } catch (t: Throwable) {
            android.util.Log.w("NetplayController", "checkpoint save failed: ${t.message}")
            false
        }
        if (!ok) {
            rbCkFrame[idx] = RB_INVALID
            return
        }
        rbCkFrame[idx] = tag
        val ms = (System.nanoTime() - t0) / 1_000_000.0
        rbSaveMsAvg = rbSaveMsAvg * 0.9 + ms * 0.1
        if (rbSaveMsAvg > 8.0 && rbSaveEvery < 2) {
            rbSaveEvery = 2
            android.util.Log.i("NetplayController", "checkpoint save slow (avg=${"%.1f".format(rbSaveMsAvg)}ms) → every 2 frames")
        }
    }

    /**
     * 执行回滚：装载 < [pendingFrom] 的最新检查点 → 丢弃预测帧待播音频 →
     * 作废分叉后的旧检查点 → 用“最新到达的实际输入（有则）/延续预测”逐帧
     * 重放 (base, lastExec]（★逐帧存档 —— 新时间线立即恢复密集覆盖，
     * 连环纠正不会退化成单点幸存者被环形轮转挤掉）。
     * 返回 false = 无法回滚（无检查点/存档失败）。
     */
    private fun rbPerformRollback(io: StateIo, pendingFrom: Long): Boolean {
        val base = rbBestCheckpointBelow(pendingFrom)
        if (base == RB_INVALID) return false
        val lastExec = rbLastExecuted
        if (lastExec - base > RB_MAX_REPLAY) {
            // 超深重放：放弃纠正（画面与对手短暂不一致，网络恢复后一致）。
            // 预测窗口上限 + 按序到达（TCP）使常规不可能到这里（仿真 0 次/30 万帧）。
            android.util.Log.w("NetplayController", "replay too deep (${lastExec - base}f) — give up")
            rbPendingFrom.set(Long.MAX_VALUE)
            dispatch { it.onNetplayWait("网络深度抖动：已跳过纠正，可能短暂不同步…") }
            return true // 已处置（不再重试），调用方继续跑帧
        }
        // 1) 丢掉预测帧产生的待播音频（防重复音效；重放的新样本随后入环）
        try { io.discardPendingAudio() } catch (_: Throwable) {}
        // 2) 装载检查点（此刻核心状态 = 执行帧 [0..base] 的折叠）
        val loaded = try { io.loadStateFrom(rbFile(io, base)) } catch (_: Throwable) { false }
        if (!loaded) {
            rbCkFrame[rbSlot(base)] = RB_INVALID
            return false
        }
        // 3) ★ 作废 tag > base 的全部旧检查点：时间线从 base+1 起分叉，
        //    旧快照含“被纠正前的帧”，选中会复活错误输入（仿真抓出并验证）
        for (i in 0 until RB_RING) {
            val t = rbCkFrame[i]
            if (t != RB_INVALID && t > base) rbCkFrame[i] = RB_INVALID
        }
        // 4) 重放 base+1 .. lastExec：每帧重读 padsHistory（吸收重放期间新到的输入）
        var depth = 0
        for (f in base + 1..lastExec) {
            val pads = IntArray(4)
            for (slot in 1..4) {
                pads[slot - 1] = when {
                    slot == mySlot -> padsHistory[slot][f] ?: 0
                    activeSlots.contains(slot) -> padsHistory[slot][f] ?: rbLastKnownPad.get(slot)
                    else -> 0
                }
            }
            if (!io.runFrameDirect(pads)) {
                rbInvalidateCheckpoints()
                return false
            }
            inputLog[f] = pads.copyOf()
            rbSaveCheckpoint(io, f)   // ★ 重放逐帧存档
            depth++
        }
        rbPendingFrom.set(Long.MAX_VALUE)
        rbCount++
        if (depth > rbMaxDepth) rbMaxDepth = depth
        return true
    }

    /** 回滚不可用时的退化：阻塞等待待纠正帧及之后的实际输入到齐（不预测）。 */
    private fun rbBlockWaitFor(fromFrame: Long) {
        val lastExec = rbLastExecuted
        for (slot in activeSlots) {
            if (slot == mySlot) continue
            var f = fromFrame
            while (f <= lastExec) {
                if (padsHistory[slot][f] == null) {
                    awaitSlotInput(slot, f) // 断连返回 null：后续门/onPeerLeft 兜底
                }
                f++
            }
        }
        // 等到的实际输入覆盖了预测值 → 待纠正标记自然解除（不一致时会重新标记）
    }

    /** 作废全部检查点（存档失败/时间线重置）。 */
    private fun rbInvalidateCheckpoints() {
        java.util.Arrays.fill(rbCkFrame, RB_INVALID)
    }

    /**
     * 请求时间线重置（任意线程）：[resumeFrame] > 0 = 存档同步到该帧（清 ≥ frame
     * 的旧时间线残留）；-1 = 仅重置簿记（pause 阶段）。引擎线程过门后统一应用。
     */
    private fun rbRequestReset(resumeFrame: Long) {
        // 后到的时间线信息更新（state → resume 两次请求，末次为准）
        rbResetFrame = resumeFrame
    }

    /**
     * 应用时间线重置（★仅引擎线程）：存档同步装载后的回滚簿记重建。
     * 同时清 ≥ resumeFrame 的历史输入 —— 旧时间线残留会被新时间线重新采样覆盖，
     * 主动清掉避免任何一端误用旧值（也修复了 v4.3 暂停前多采样一帧的极端不同步）。
     */
    private fun rbApplyReset(resumeFrame: Long) {
        if (resumeFrame > 0) {
            for (slot in 1..4) {
                padsHistory[slot].keys.removeAll { it >= resumeFrame }
                rbSlotLatest.set(slot, -1L)
            }
        }
        inputLog.clear()
        rbInvalidateCheckpoints()
        rbPendingFrom.set(Long.MAX_VALUE)
        rbLastExecuted = if (resumeFrame > 0) resumeFrame - 1 else -1L
        rbSaveCounter = 0
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
        // ★★ v4.4：回滚状态清零
        rollbackMode = false
        inputLog.clear()
        rbInvalidateCheckpoints()
        rbPendingFrom.set(Long.MAX_VALUE)
        rbLastExecuted = -1L
        for (s in 1..4) rbSlotLatest.set(s, -1L)
        rbCount = 0
        rbMaxDepth = 0
    }

    /** 当前是否已收到同步开始信号。 */
    val isStarted: Boolean get() = started

    /** 当前是否还在同帧同画同步（门未开或连接断开都算不可用）。 */
    val isAlive: Boolean get() = started && net != null && net!!.isConnected
}
