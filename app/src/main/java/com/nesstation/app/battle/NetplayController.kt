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
 *  - **帧计数对齐**（★★ v4.7 重写）：网络帧号由控制器内部计数器发号，
 *    不再用引擎本地帧换算 —— start（0）/resume 装载（存档帧）/restart
 *    复位（0）三类对齐事件直接改写计数器，全员 (netFrame→输入) 序列
 *    严格一致（旧版引擎计数器在重开复位瞬间错位 → “1p 等 2p、2p 等
 *    1p” 互等死锁，v4.7 根治）。
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
     * 全部方法带默认空实现 —— 界面按需覆写（v51 聊天浮层这类"只关心个别事件"的
     * 监听器不必再被迫实现全部回调，修复 EmulatorScreen.kt:8434 匿名 object
     * 缺 onReady 抽象成员实现的编译错误；既有完整实现不受影响）。
     */
    interface UiListener {
        fun onReady(role: String, inputDelay: Int) {}
        fun onStart(inputDelay: Int) {}
        fun onPeerReady(username: String) {}
        fun onFrameInfo(frame: Long, inputDelay: Int, desyncCount: Int) {}
        fun onPeerJoined(username: String) {}
        fun onPeerLeft(username: String) {}
        fun onError(message: String) {}
        fun onDisconnected() {}
        fun onNetplayWait(reason: String) {}
        fun onNetplayLost(reason: String) {}

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
        /** ★★ v51 游戏内聊天收到（from = 发送者名字；slot = 其座位 1-4；spec = 观战者）。
         *  EmulatorScreen 据此显示消息气泡/更新聊天面板。 */
        fun onChat(from: String, text: String, slot: Int, spec: Boolean) {}
        /** ★★ v52 本端观战者热转座成功（slot = 新座位；输入采样已解锁，
         *  虚拟手柄立即生效）—— UI 显示提示/更新状态。 */
        fun onSeatChanged(slot: Int) {}
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
        /** ★★ v48：12 → 16 帧（≈267ms）。3-4 人房跨运营商（联通↔移动）RTT 波动
         *  更大，单人最小确认帧更保守；窗口加大减少退化阻塞等待的频率
         *  （回滚重放 ≤ RB_MAX_REPLAY 上限不变，检查点环 32 帧覆盖充足）。 */
        private const val RB_MAX_PREDICT = 16
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
        // ★★ v59 追赶提速（用户实测“追赶太慢”）：快照上传间隔整体收紧 ——
        //   接入者重放距离 = 最新帧 - 缓存快照帧，快照越新鲜重放越短。
        //   6/10/15s → 3/5/8s（小档 100KB 级，带宽代价司承受；v53+ 服务器
        //   还有 state_pull 静默拉档兑底，不依赖周期上传的时效）。
        private const val UPLOAD_GAP_SMALL = 180L   // ≤128KB → 每 3s（帧）
        private const val UPLOAD_GAP_MEDIUM = 300L  // ≤512KB → 每 5s
        private const val UPLOAD_GAP_LARGE = 480L   // 更大 → 每 8s
        private const val UPLOAD_SIZE_SMALL = 128 * 1024
        private const val UPLOAD_SIZE_MEDIUM = 512 * 1024
        private const val UPLOAD_FIRST_FRAME = 60L
        /** ★★ v54 老服务器聊天兑底（详见 sendChatFallback）：结束条帧号（负帧号 input 编码）。 */
        private const val CHAT_FALLBACK_END_FRAME = -100000L
        /** ★★ v54 老服务器聊天兑底：单条消息分片上限（200 汉字 UTF-8 ≈ 600B ≈ 200 条）。 */
        private const val CHAT_FALLBACK_MAX_CHUNKS = 220
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

    /** ★★ v4.7 已废弃：旧版“网络帧号 = 引擎本地帧 + frameOffset”换算的偏移量。
     *   仅作注释保留（新代码一律用内部帧号计数器 netFrameCounter）。
     *   引擎线程当前（或即将）暂停处的本地帧号仅作“引擎已挂门”信号使用。 */
    @Volatile private var frameOffset = 0L

    // === ★★ v4.7 控制器内部帧号（互等死锁根治） ===
    // 背景：v4.5.1~v4.6 的 netFrame = 引擎本地帧 + frameOffset。全员重开（restart）
    //   时 resetToBoot 把引擎帧计数器清回 0，但【正在飞行中的 beforeFrame 调用】
    //   还携带着旧帧号（房主 ~N+1、接入者 1）—— 各端引擎计数器在复位瞬间错位，
    //   frameOffset 又从不重对齐 → 重开后 1P 在 ~N-5 等 2P 的输入、2P 在 ~0 等
    //   1P 的输入，谁也等不到 → “1p 等 2p，2p 等 1p” 互等死锁。
    // 修复：网络帧号改由控制器【自己计数】（每次过门后发帧 +1）。引擎传什么帧号
    //   只当参考（引擎计数器复位错位不再影响同步）；start/resume/restart 三类
    //   对齐事件直接改写计数器，全员从同一帧号起跑。锁步所需的 (netFrame→pads)
    //   序列在各端严格一致。
    /** 下一个待发出的网络帧号（仅引擎线程消费；网络线程仅在引擎挂门时改写）。 */
    @Volatile private var netFrameCounter = 0L
    /** 最近一次 beforeFrame 已发出的网络帧号（afterFrame 记账用）。 */
    @Volatile private var lastIssuedNetFrame = -1L
    /** 本帧为零输入过渡帧（重启请求/无连接的让路帧）—— afterFrame 跳过记账。 */
    @Volatile private var zeroPadFrame = false

    /** 活跃槽位（要等待其输入的远端槽位 = activeRemoteSlots；本地槽位除外）。 */
    private val activeSlots = java.util.concurrent.ConcurrentHashMap.newKeySet<Int>()
    /** 同步接入中的槽位（peer_joined 后、resume 前 —— 不等待其输入）。 */
    private val pendingSlots = java.util.concurrent.ConcurrentHashMap.newKeySet<Int>()

    /** ★★ v48 离队槽位的最后输入帧号（slot -> 服务器 peer_left 下发的 frame）。
     *  旧版 peer_left 后各端立即把该槽位当作“不存在”：
     *   - 有人还在等它在途的最后一帧输入（等到）
     *   - 有人直接用 0 跑过去（没等）
     *  → 同一帧在不同端用了不同输入 → 单端永久发散（“三人/四人里有一个人
     *  不同步”的直接根源）。现在 ≤ lastFrame 的输入【TCP 有序必达，等到再用】，
     *  > lastFrame 的帧【确定性归零】—— 各端输入序列严格一致。 */
    private val deadSlotLastFrame = java.util.concurrent.ConcurrentHashMap<Int, Long>()

    /** ★★ v48 门事件标记（仅引擎线程读写）：等待远端输入期间发生 pause/
     *  restart —— 本帧作废，回到外层环重新过门对齐（存档装载/帧号改写
     *  都在引擎挂门期间完成），绝不能带着旧时间线的帧号继续跑帧。 */
    private var gateEventPending = false

    /** 本端槽位（1P=1…4P=4，服务器 hello 下发；★★ v4.4 观战者恒 0）。 */
    @Volatile var mySlot: Int = 1
        private set

    /** ★★ v4.4：本端是否观战者（构造指定；不占座位、只收不发）。 */
    /** ★★ v4.4 观战模式（★★ v52 起可运行时切换 —— 观战者热转座后置 false，输入采样解锁）。 */
    val isSpectator: Boolean get() = spectatorMode

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
    /** 引擎线程当前（或即将）暂停处的本地帧号 —— ★★ v4.7 仅作“引擎已挂门”信号
     *   （存档/装载的安全窗口判定）；不再参与网络帧号推算。 */
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
    /** ★★ v4.6 重开中核心复位失败（不再重新 ready —— 防止带着未复位的核心
     *  "从帧 0"开跑造成全程发散；见 applyRestartOnEngineThread）。 */
    @Volatile private var restartFailed = false

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

    // === ★★ v52 追赶接入（小马模拟器式“中途加入不暂停”）状态 ===
    /** 追赶模式：装档完成、引擎开始离屏快放追赶。★★ v54 起为【黑屏极速追赶】：
     *  引擎线程紧循环离屏快放（runFrameDirect 不渲染不出声）直到追平 ——
     *  屏幕保持黑屏（EmulatorScreen 全屏黑底进度浮层），追平后落入锁步，
     *  画面以同步状态直接出现（用户实测反馈：“应该黑屏快速直接追赶而不是
     *  启动游戏慢慢追赶，追赶成功再同步画面”）。 */
    @Volatile private var catchupMode = false
    /** 追赶目标帧下限（服务器组装的输入历史终点 —— v53 起只是【下限】：
     *  追赶期间服务器照常广播 input，onRemoteInput 持续更新 catchupLatestSeen，
     *  实际追平目标 = max(catchupHistEnd, catchupLatestSeen - CATCHUP_TAIL)。 */
    @Volatile private var catchupHistEnd = 0L
    /** 追赶起点（存档帧 —— 进度展示用）。 */
    @Volatile private var catchupFromFrame = 0L
    /** ★★ v53 追赶动态目标：追平判定不追到 histEnd 就停（那是装档时刻的
     *  冻结值），而是追到【最新已见远端输入帧 - 该尾距】—— 追赶期间
     *  onRemoteInput 持续刷新 catchupLatestSeen，目标随实时帧滚动前移，
     *  追平时与原玩家仅差 1.5 秒（锁步无缝并入，观感同步）。90 帧 = 60fps × 1.5s。 */
    @Volatile private var catchupLatestSeen = 0L
    /** ★★ v59 追赶尾距 90 → 60 帧（1.5s → 1.0s）：尾段重放受实时输入 60Hz
     *  到达率限制无法加速（输入还不存在），是追赶耗时的硬下限 —— 收紧
     *  尾距直接减 0.5s；锁步无缝并入的余量仍定裕（60 帧 + inputDelay 窗口）。 */
    private val CATCHUP_TAIL = 60L
    /** ★★ v54 黑屏极速追赶进度（"1234/5678"，UI 轮询显示；引擎线程写）。 */
    @Volatile var catchupProgressText: String = ""
        private set
    /** ★★ v54 当前是否在黑屏快速追赶中（EmulatorScreen 全屏黑底进度浮层依据）。 */
    val isCatchingUp: Boolean get() = catchupMode
    /** ★★ v54 追赶历史缺口标记：追赶重放等待某帧输入超时（仅引擎线程置位/读）。 */
    private var catchupTimedOut = false
    /** ★★ v54 追赶单帧输入等待上限：服务器组装的历史有缺口（老服务器/竞态）时
     *  自救 —— 超时请求全员重开（v52 降级语义的客户端兑底），绝不无限黑屏挂起。 */
    private val CATCHUP_INPUT_TIMEOUT_MS = 15000L
    /** ★★ v53 静默拉档：服务器 state_pull 请求（无缓存档/历史缺口时）——
     *  置标志，引擎线程 afterFrame 下一帧立即 uploadSnapshot（出档与跑帧
     *  同线程天然安全，无需挂门 —— 小马同款“零感知拉档”）。 */
    @Volatile private var pullSnapshotRequested = false
    /** ★★ v53 服务器协议版本（hello 回执 srv 字段；0 = 老服务器 <53）。
     *  UI 据此提示聊天/追赶能力（老服务器无 chat 中继 → 聊天面板提示）。 */
    @Volatile var serverProtoVersion = 0
        private set

    /** v52 座位延迟激活：槽位 → 激活帧（该帧及之前其输入不被等待 —— 追赶/转座接入的无缝对接保证）。 */
    private val slotActiveFrom = java.util.concurrent.ConcurrentHashMap<Int, Long>()
    /** ★★ v56 座位激活过渡：追赶追平（state_ok 已发）到 slot_active 到达之间，
     *  本端对自己的槽位同样确定性用 0（全员此刻都把它当“未激活”，
     *  不门控则该窗口内一按键即单端永久发散）。slot_active / seat_granted /
     *  resume / restart / start 到达即解除。 */
    @Volatile private var pendingOwnActivation = false
    /** v52 观战模式（hello 时初始化；热转座后置 false）。 */
    @Volatile private var spectatorMode = false

    private val netListener = object : BattleNetplay.Listener {
        override fun onRemoteInput(frame: Long, pad: Int, slot: Int) {
            // ★★ v54 老服务器聊天兑底：聊天分片编码在负帧号 input 上（详见
            //   sendChatFallback）—— 先于 padsHistory/回滚簿记拦截，绝不污染。
            if (frame < 0) {
                handleChatFragment(frame, pad, slot)
                return
            }
            if (slot in 1..4) {
                padsHistory[slot][frame] = pad
                // ★★ v53 追赶动态目标：追赶期间持续记录最新已见远端输入帧 ——
                //   追平目标随实时帧滚动前移（max(服务器 histEnd, 最新已见帧-90)），
                //   追平时与原玩家仅差 1.5 秒（v52 冻结 histEnd → 追平后错开
                //   “追赶耗时×60 帧”永久不同步的根治）。
                if (catchupMode && frame > catchupLatestSeen) {
                    catchupLatestSeen = frame
                }
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
            // ★★ v52：观战模式运行时可切换（热转座后置 false —— 采样解锁）
            spectatorMode = role == "spectator" || spectate
            activeSlots.clear()
            activeSlots.addAll(players)
            dispatch { it.onReady(role, inputDelay) }
            dispatch { it.onReadyDetail(role, inputDelay, mySlot, players, playing) }
        }

        override fun onStart(inputDelay: Int, players: List<Int>) {
            this@NetplayController.inputDelay = inputDelay.coerceIn(0, 16)
            activeSlots.clear()
            activeSlots.addAll(players)
            // 房主单开 / 全员同步开跑：帧 0 起（★★ v4.7 内部帧号计数器归零 ——
            //   引擎本地帧号不再参与网络帧号推算，引擎计数器错位不再影响同步）
            netFrameCounter = 0L
            lastIssuedNetFrame = -1L
            parkedLocalFrame = -1
            // ★★ v52：全新开局 —— 追赶/座位激活簿记一并清零
            catchupMode = false
            // ★★ v53：追赶动态目标/拉档簿记一并清零（新时间线从帧 0 起）
            catchupLatestSeen = 0L
            catchupHistEnd = 0L
            catchupProgressText = ""
            pullSnapshotRequested = false
            slotActiveFrom.clear()
            pendingOwnActivation = false
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

        override fun onStateData(frame: Long, dataBase64: String, hist: Map<Int, List<Int>>?, histEnd: Long,
                                 catchup: Boolean) {
            if (catchup) {
                // ★★ v52 追赶模式存档（不暂停流 —— 只有接入者会收到，原玩家全程不暂停）：
                //   后台线程装档 → 帧号对齐 → 种输入历史 → 开门进入离屏快放追赶。
                dispatch { it.onSyncStatus("同步画面已就绪（帧 #$frame），准备快进追赶…") }
                Thread({
                    try {
                        beginCatchup(frame, dataBase64, hist, histEnd)
                    } catch (t: Throwable) {
                        android.util.Log.w("NetplayController", "catchup begin failed", t)
                        dispatch { it.onSyncStatus("追赶装载失败，已请求全员重新同步…") }
                        try { net?.sendRestartReq() } catch (_: Throwable) {}
                    }
                }, "battle-catchup").apply { isDaemon = true }.start()
                return
            }
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
            // ★★ v52：暂停流接管（追赶中遭遇 pause 的竞态）——
            //   追赶作废，随全员一起装载恢复（resumeInternal 统一处理）
            catchupMode = false
            catchupProgressText = ""
            // ★★ v56：暂停流装载恢复后全员立即活跃 —— 解除激活过渡门控
            pendingOwnActivation = false
            // ★★ v56 旧时间线残留清理（在读线程内执行 —— 消息有序，此刻之后
            //   到达的 input 全是各端恢复后重新采样的新时间线值，不会被误清）：
            //   时间线回滚（存档帧 < 各端暂停前进度）后，存活玩家槽位上 ≥ 恢复帧
            //   的历史输入是旧时间线残留 —— 不清的话读端直接命中旧值而不再等待
            //   新采样值（远端重采样值与本端残留旧值不一致 → 单端永久发散）。
            //   离队槽位不清（≤ deadSlotLastFrame 的在途真实输入语义保留，各端一致）。
            if (frame >= 0) {
                for (s in 1..4) {
                    if (s == mySlot || players.contains(s)) {
                        padsHistory[s].keys.removeAll { it >= frame }
                    }
                }
            }
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
            // ★★ v4.7 全员同时重开（服务器编排；非街机核心的中途接入一律走这里
            //   ——  快照同步仅限 FBNeo，其余核心按"4.2 同时重开"语义）：
            //   本端置 restartPending（引擎线程下一个 beforeFrame 串行做核心复位
            //   retro_reset，无并发）→ 复位完成由 applyRestartOnEngineThread 直接触发
            //   重新探测 + 发 ready → 服务器收齐全员 ready 广播 start → 全员从
            //   帧 0（开机状态）同步开跑。
            dispatch { it.onSyncStatus("新玩家接入：全员同时重开一局，回到开局状态…") }
            android.util.Log.i("NetplayController", "restart: 全员重开（players=$players delay=$inputDelay）")
            // ★★ v52：重开 = 全新时间线 —— 追赶/座位激活簿记清零
            catchupMode = false
            // ★★ v53：追赶动态目标/拉档簿记一并清零
            catchupLatestSeen = 0L
            catchupHistEnd = 0L
            catchupProgressText = ""
            pullSnapshotRequested = false
            slotActiveFrom.clear()
            pendingOwnActivation = false
            restartPending = true
            restartFailed = false   // ★★ v4.6 每轮重开重置失败标记（上一轮失败不影响本轮）
            // 先开门让引擎线程能走到复位点（同步暂停门/启动门都放行到重启检查）
            synchronized(syncGate) { syncPaused = false; syncGate.notifyAll() }
            synchronized(startGate) { started = false; startGate.notifyAll() }
            readySent = false
            engineLoaded = false
            lastUploadFrame = -1L
            uploadGapFrames = UPLOAD_GAP_SMALL
            parkedLocalFrame = -1L
            pendingSlots.clear()
            if (players.isNotEmpty()) {
                activeSlots.clear()
                activeSlots.addAll(players)
            }
            // ★★ v4.7 已加载完的端：不再由定时线程等复位后发 ready ——
            //   applyRestartOnEngineThread（引擎线程、复位成功后）直接调
            //   notifyEngineReady（此刻核心刚复位、引擎线程就在现场 —— 探测/出档
            //   天然安全，且不存在"5 秒没等到复位就发 ready"的带病开跑窗口）。
            //   引擎还在加载的端：复位对它本来就是开机态，加载完成后
            //   EmulatorScreen 会调 notifyEngineReady（engineLoaded 已复位）。
        }

        override fun onSlotActive(frame: Long, slot: Int, players: List<Int>) {
            // ★★ v52 座位延迟激活（追赶接入者追平 / 观战者转座）：
            //   [slot] 座位从 [frame] 之后开始被等待其输入（之前全员对其槽位确定性归零 ——
            //   只显示“xx 以 NP 接入”，不暂停不打断）。
            if (slot in 1..4) slotActiveFrom[slot] = frame
            // ★★ v56：自己的座位激活确认到位 —— 解除激活过渡门控
            if (slot == mySlot) pendingOwnActivation = false
            if (players.isNotEmpty()) {
                activeSlots.clear()
                activeSlots.addAll(players)
            }
            pendingSlots.remove(slot)
            deadSlotLastFrame.remove(slot)
            dispatch { it.onSyncStatus(if (slot == mySlot) "${slot}P 座位已激活 —— 对战中！" else "${slot}P 已接入对战（无缝加入）") }
        }

        override fun onSeatGranted(frame: Long, slot: Int, players: List<Int>) {
            // ★★ v52 观战转座确认（仅转座者本人收到）：本端已从观战
            //   切到 [slot] 座 —— mySlot 从 0 切到座位、采样解锁，
            //   从下一帧开始正常采样发送（画面本就是当前局）。
            if (slot !in 1..4) return
            mySlot = slot
            spectatorMode = false
            // ★★ v56：seat_granted 自带激活帧 —— 无需过渡门控（slotActiveFrom 已就位）
            pendingOwnActivation = false
            if (players.isNotEmpty()) {
                activeSlots.clear()
                activeSlots.addAll(players)
            }
            slotActiveFrom[slot] = frame
            pendingSlots.remove(slot)
            deadSlotLastFrame.remove(slot)
            // 自己槽位 inputDelay 预热窗补零（转座前未采样的帧全员用 0
            //   —— 不补会误用当前手柄状态跑旧帧 → 单端发散；只补空缺口，
            //   不覆盖离队前辈的真实在途输入）
            seedOwnZeroWindow(netFrameCounter, overwrite = false)
            dispatch { it.onSyncStatus("已入座 ${slot}P —— 继续对战！") }
            dispatch { it.onSeatChanged(slot) }
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
            // ★★ v48：新玩家入座 —— 清除该槽位的离队记录（座位复用）
            deadSlotLastFrame.remove(slot)
            dispatch { it.onPeerJoined(username) }
            dispatch { it.onPeerJoinedSlot(username, slot) }
        }

        override fun onPeerLeft(username: String, slot: Int, spec: Boolean, lastFrame: Long) {
            if (spec) {
                dispatch { it.onSpectatorLeft(username) }
                return
            }
            pendingSlots.remove(slot)
            activeSlots.remove(slot)
            // ★★ v48 记录离队槽位最后输入帧：在途输入等到再用，其后归零
            if (lastFrame >= 0) {
                val old = deadSlotLastFrame[slot]
                if (old == null || lastFrame > old) deadSlotLastFrame[slot] = lastFrame
            }
            if (!started) {
                // 等待阶段对端离开：开门（引擎不死等）
                synchronized(startGate) { started = true; startGate.notifyAll() }
            }
            dispatch { it.onPeerLeft(username) }
            dispatch { it.onPeerLeftSlot(username, slot) }
        }

        // ★★ v51 游戏内聊天：服务器盖戳的 from/slot/spec 直接透传给 UI（EmulatorScreen）
        //   ★★ v59：同时入控制器留痕（UI 重组/菜单开关后历史不丢）。
        //   ★★ v60 channel=0：原生 chat 中继通道（与兜底通道去重配合 ——
        //   发送端双通道保险下同一条消息会到两次，这里只保留第一次）。
        override fun onChat(from: String, text: String, slot: Int, spec: Boolean) {
            if (recordIncomingChat(from, text, slot, spec, channel = 0)) {
                dispatch { it.onChat(from, text, slot, spec) }
            }
        }

        // ★★ v53 静默拉档：服务器无缓存档/历史缺口时请求房主立即出一份快照。
        //   不暂停不挂门 —— 只置标志，引擎线程 afterFrame 下一帧边界直接
        //   uploadSnapshot（出档与跑帧同线程，天然安全；小马同款"零感知拉档"）。
        override fun onStatePull() {
            pullSnapshotRequested = true
        }

        // ★★ v53 服务器协议版本记录（hello 回执 srv 字段；0 = 老服务器 <53）：
        //   UI 据此提示聊天/追赶能力（老服务器无 chat 中继 → 聊天面板显示
        //   "服务器版本较旧"提示 —— 用户实测"聊天对方收不到"的典型根因是
        //   线上服务器二进制未随客户端同步更新，此提示让问题可自诊断）。
        override fun onServerVersion(srv: Int) {
            serverProtoVersion = srv
            android.util.Log.i("NetplayController", "server proto version: $srv")
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
            pendingOwnActivation = false   // ★★ v56 卫生清理（断连后不再有激活广播）
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
        // ★★ v4.7：存档对应的网络帧号 = 最近已执行帧 + 1（引擎此刻挂在同步门上，
        //   帧号由控制器内部计数器推算 —— 引擎本地帧号复位错位不影响这里）
        val stateFrame = lastIssuedNetFrame + 1L
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
     *  出档与读文件经 uploadLock 串行化（防文件被下一轮覆盖时读到半截）。
     *  ★★ v55 返回是否已启动上传：false = 上一份还在发（in-flight 跳过）——
     *    拉档请求（state_pull）被跳过时调用方恢复 pullSnapshotRequested
     *    下一帧重试（v54 缺陷：拉档请求被 in-flight 周期上传吞掉 → 拉档等待
     *    接入者在启动门上干等下一轮周期上传（最长 15s）才拿到档）。 */
    private fun uploadSnapshot(netFrame: Long): Boolean {
        val io = stateIo ?: return true
        val n = net ?: return true
        if (!uploadInFlight.compareAndSet(false, true)) return false // 上一份还在发：跳过本轮（调用方重试拉档）
        val file = File(io.stateDir(), "battle_upload.state")
        val ok = try {
            synchronized(uploadLock) { io.saveStateTo(file) }
        } catch (t: Throwable) {
            android.util.Log.w("NetplayController", "upload saveState: ${t.message}"); false
        }
        if (!ok) {
            uploadInFlight.set(false)
            return true
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
        return true   // 上传已启动（启动后的一切失败由后台线程自行记录）
    }

    /** resume：全员按统一存档/帧号/延迟恢复。data 空 = 仅解除暂停（同步中止兜底）。
     *
     * ★★ v4.5 统一装载：房主也不再跳过 —— 服务器可能下发“缓存档”（房主出档
     *   失败/掉线时的回退路径，比房主当前进度旧），全员装载同一份才能保证一致；
     *   房主自己刚出的新鲜档装载 = 无操作，行为不变。
     * ★★ v4.7 帧号对齐：装载成功 → 控制器内部帧号计数器直接改写为存档帧 ——
     *   全员（含房主）下一个发出的网络帧号严格等于存档帧，不再依赖
     *   “引擎本地帧 + frameOffset”换算（引擎计数器在 restart 复位等场合会错位）。
     *   新鲜档 = 零回退；缓存档 = 回退到缓存帧，仍全员同帧。装载失败 → 计数器
     *   不动（各端从各自暂停前的网络帧继续 —— 暂停前本就同帧一致）。 */
    private fun resumeInternal(dataBase64: String?, finalDelay: Int, players: List<Int>,
                               frame: Long = -1L, hist: Map<Int, List<Int>>? = null, histEnd: Long = 0L) {
        val io = stateIo
        if (dataBase64 != null && frame >= 0 && io != null) {
            val bytes = try {
                android.util.Base64.decode(dataBase64, android.util.Base64.NO_WRAP)
            } catch (_: Throwable) { null }
            if (bytes != null && bytes.isNotEmpty()) {
                // ★★ v48 先等引擎真正挂门再装载：旧版假定“引擎此刻挂在门上”，
                //   但引擎线程可能仍在跑帧 / 阻塞在等待远端输入（v48 门事件修复后
                //   会主动回门，这里只作确定性等待）—— loadStateFrom 与 runFrame
                //   并发 = 核心状态损坏（3-4 人房“全部卡死/一人永久不同步”根源之一）。
                //   最多等 2s：超时放弃装载（各端从各自暂停帧继续，暂停前本就同帧）。
                if (!waitForEngineParked(2000)) {
                    dispatch { it.onSyncStatus("同步装载等待引擎超时，按当前进度恢复…") }
                    android.util.Log.w("NetplayController", "resume: engine not parked in 2s, skip state load")
                } else {
                val file = File(io.stateDir(), "battle_join_sync.state")
                val loaded = try {
                    file.writeBytes(bytes); io.loadStateFrom(file)
                } catch (_: Throwable) { false }
                if (!loaded) {
                    // ★★ v4.6 装载失败不再断开退出：向服务器请求全员重开
                    //   （回开机态、从帧 0 同步重跑 —— 接入者留下一起重开）。
                    //   本端先从各自暂停帧裸恢复（不装载），等 restart 到达接管；
                    //   混合旧客户端的房间服务器会回 error 说明 —— 也不卡死。
                    dispatch { it.onSyncStatus("同步画面装载失败，已请求全员重新开局…") }
                    try { net?.sendRestartReq() } catch (_: Throwable) {}
                } else {
                    // ★★ v4.7 内部帧号计数器 = 存档帧：全员下一个发出的网络帧号
                    //   严格一致（引擎此刻挂在门上，无并发消费 —— 改写安全）
                    netFrameCounter = frame
                }
                }
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

    /**
     * ★★ v48 等待引擎线程挂到门上（启动门/同步门都算 —— 引擎挂门时核心必然
     * 空闲，跨线程存档装载安全）。返回 false = 超时（引擎仍在跑帧/等待输入）。
     * 仅在同步收尾线程（battle-resume / hostProduceState 后续）调用。
     */
    private fun waitForEngineParked(timeoutMs: Long): Boolean {
        // 未开跑且无暂停请求：引擎要么还没进帧循环（加载中，核心空闲安全），
        // 要么正挂在启动门上 —— 都可视为“已停”。
        val t0 = System.currentTimeMillis()
        while (System.currentTimeMillis() - t0 < timeoutMs) {
            if (parkedLocalFrame >= 0) return true
            if (!started && !syncPaused) return true
            try { Thread.sleep(5) } catch (_: InterruptedException) { return parkedLocalFrame >= 0 }
        }
        return parkedLocalFrame >= 0
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
    // =========================================================================
    // ★★ v52 追赶接入（小马模拟器式“中途加入不暂停”）
    // =========================================================================

    /**
     * 追赶模式装载（battle-catchup 线程；引擎此刻挂在启动门 ——
     * 跨线程装档安全，与 resumeInternal 同一模式）。
     * 成功后开门：引擎线程下一帧进入 catchupMode 快放追赶。
     * 失败抛异常 → 调用方自救（sendRestartReq 全员重开）。
     */
    private fun beginCatchup(frame: Long, dataBase64: String, hist: Map<Int, List<Int>>?, histEnd: Long) {
        // 竞态护栏：装载过程中若又有暂停/重开接管（另一接入者触发
        // 暂停同步）—— 本轮追赶作废，交给暂停流程统一收尾（resume 会
        // 装载正确的档并清 catchupMode）。
        if (syncPaused || restartPending) {
            android.util.Log.w("NetplayController", "beginCatchup 被暂停/重开竞态接管，交给暂停流程")
            return
        }
        val io = stateIo ?: throw IllegalStateException("无存档通道")
        val bytes = try {
            android.util.Base64.decode(dataBase64, android.util.Base64.NO_WRAP)
        } catch (_: Throwable) { null }
        if (bytes == null || bytes.isEmpty()) throw IllegalStateException("存档为空")
        // 确认引擎挂门（启动门未开 = 核心空闲）
        if (!waitForEngineParked(2000)) {
            throw IllegalStateException("引擎未挂门")
        }
        val file = File(io.stateDir(), "battle_catchup.state")
        val loaded = try { file.writeBytes(bytes); io.loadStateFrom(file) } catch (_: Throwable) { false }
        if (!loaded) throw IllegalStateException("存档装载失败")
        // 帧号对齐存档帧（引擎挂门期间改写安全 —— 与 resumeInternal 同一模式）
        netFrameCounter = frame
        lastIssuedNetFrame = frame - 1
        // 种输入历史：16 帧热身窗 + [frame, histEnd) 重放窗（服务器
        // 从环形缓冲组装、缺帧补 0 —— 与各端实际执行的输入序列严格一致）
        seedHist(hist, histEnd)
        // 回滚簿记重置（仅簿记 —— 不清 padsHistory：这里的历史是“将要重放的未来”）
        rbRequestReset(-1L)
        catchupFromFrame = frame
        catchupHistEnd = histEnd
        // ★★ v53 动态目标初始化：装档时刻把“最新已见帧”设为 histEnd（服务器
        //   组装时的最新帧）—— 追赶期间 onRemoteInput 持续刷新，追平目标
        //   随实时帧滚动前移（绝不在冻结的 histEnd 提前收兵）。
        catchupLatestSeen = histEnd
        catchupMode = true
        catchupProgressText = ""
        dispatch { it.onSyncStatus("黑屏快速追赶中…") }
        // 开门（只开启动门 —— 同步门若因竞态已暂停则保持暂停，交给暂停流程收尾）
        synchronized(startGate) { started = true; startGate.notifyAll() }
        parkedLocalFrame = -1
    }

    /**
     * ★★ v53 追赶动态目标：max(服务器 histEnd 下限, 最新已见远端输入帧 - 90)。
     * 追赶期间服务器照常广播 input（onRemoteInput 持续刷新 catchupLatestSeen）
     * —— 目标随实时帧滚动前移，追平时与原玩家仅差 1.5 秒；纯 histEnd 模式
     * （追平目标 < histEnd，如装档时快放已超过实时）则追到 histEnd 保底。
     */
    private fun catchupTarget(): Long =
        maxOf(catchupHistEnd, catchupLatestSeen - CATCHUP_TAIL)

    /**
     * 追赶重放帧 [f] 的 4 手柄输入。
     * 服务器组装的历史是全员实际执行输入的地面真值（含离队
     * 槽位的在途真实输入）—— 直接采用，不做槽位门控（门控会把“加入前离
     * 队者”的历史输入误归零 → 重放出的画面与房主发散）。
     * 窗口外缺帧才回退到门控+阻塞等待（不该发生 —— 服务器组装保证完整）。
     * 返回 null = 门事件（pause/restart）或断连，调用方作废本帧。
     */
    private fun catchupPadsFor(f: Long): IntArray? = catchupPadsFor(f, 0L)

    /** ★★ v54 timeoutMs > 0 时等待输入带截止时间：超时置 catchupTimedOut 并返回 null
     *  （历史缺口自救，详见追赶主循环）。 */
    private fun catchupPadsFor(f: Long, timeoutMs: Long): IntArray? {
        val pads = IntArray(4)
        // ★★ v56 防御：负执行帧（理论不可达 —— 首份快照在帧 61 之后，远大于
        //   inputDelay ≤ 16）—— 直接全 0，绝不为负帧阻塞等待。
        if (f < 0) return pads
        val deadline = if (timeoutMs > 0) System.currentTimeMillis() + timeoutMs else 0L
        for (slot in 1..4) {
            var pad = padsHistory[slot][f]
            if (pad == null) {
                if (slot != mySlot && slotNeedsInput(slot, f)) {
                    pad = if (deadline > 0) awaitSlotInputDeadline(slot, f, deadline)
                          else awaitSlotInput(slot, f)
                    if (pad == null) return null
                } else {
                    pad = 0
                }
            }
            pads[slot - 1] = pad
        }
        return pads
    }

    /**
     * 自己槽位 inputDelay 预热窗 [fromFrame-16, fromFrame) 归零：
     * - 追赶者（overwrite=true）：强制清零 —— 服务器组装的历史里含上任者
     *   的真实输入（重放阶段已消费完毕），而激活帧前全员对新座位用 0；
     * - 观战转座者（overwrite=false）：只补空缺口 —— 离队前任 ≤ 最后中继帧
     *   的在途真实输入保留（全员在途规则同样用真实值），只把“永不再来的帧”补 0。
     * 不归零会让自己的预热帧误用当前手柄/旧输入 → 单端发散。
     */
    private fun seedOwnZeroWindow(fromFrame: Long, overwrite: Boolean) {
        if (mySlot !in 1..4) return
        for (f in maxOf(0L, fromFrame - 16) until fromFrame) {
            if (overwrite || padsHistory[mySlot][f] == null) padsHistory[mySlot][f] = 0
        }
    }

    /**
     * ★★ v52 观战者热转座：请求入座（小马 UpdateRole 同款 ——
     * 观战中随时点“入座”，不退出不重开：服务器分配空位 →
     * seat_granted（本端切座位解锁采样）+ 全员 slot_active（从激活帧
     * 后开始等它）。满座/混合旧版本房间服务器回 error → onError 提示。
     */
    fun requestSeat(want: Int = 0) {
        val n = net ?: return
        if (!spectatorMode) return
        try { n.sendTakeSeat(want) } catch (_: Throwable) {}
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
                // ★★ v4.6 复位失败不再断开连接：不再重新 ready（本端无法回到开机态，
                //   勉强重跑必然从帧 0 起就发散），引擎停在启动门、保持连接并明确
                //   提示 —— 玩家手动退出重开是唯一一致的自救；服务器等其他人的
                //   ready，本端离开后对局照常继续（v4.7 服务器在等待阶段有人
                //   离开时会重算开局）。此为防御性兑底，优先保证"绝不静默发散"。
                restartFailed = true
                dispatch { it.onSyncStatus("核心复位失败：无法全员重开，请退出后重新开局") }
                return
            }
        }
        synchronized(startGate) { started = false }
        parkedLocalFrame = -1L
        // ★★ v4.7 内部帧号计数器归零：重开后全员从帧 0 重新同步开跑 ——
        //   引擎本地帧号（可能还停在重开前的几千帧）不再参与网络帧号推算
        netFrameCounter = 0L
        lastIssuedNetFrame = -1L
        for (s in 1..4) padsHistory[s].clear()
        inputLog.clear()
        pendingSlots.clear()
        deadSlotLastFrame.clear()
        gateEventPending = false
        desyncCount = 0
        rbRequestReset(-1L)   // 回滚簿记全部作废（rbApplyReset(-1)：清 inputLog + 检查点 + 置 rbLastExecuted=-1）
        rbCount = 0L
        rbMaxDepth = 0
        dispatch { it.onSyncStatus("已重开：等待全员就绪同步开跑…") }
        android.util.Log.i("NetplayController", "restart applied on engine thread")
        // ★★ v4.7 复位成功后由引擎线程直接重新就绪（此刻核心刚复位、本线程
        //   就在现场 —— 探测/出档天然安全；替代旧版"网络线程等 5 秒"方案，
        //   不存在超时带病发 ready 的窗口）。notifyEngineReady 内部有
        //   engineLoaded/readySent 守卫，与 EmulatorScreen 的加载完成通知幂等。
        notifyEngineReady()
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

        val io = stateIo
        // ★★ v4.7 快照同步能力门控（用户指令："存档同步实在不行就用 4.2 的同时重开"）：
        //   快照同步（服务器缓存档 / state_req 新鲜档 / 回滚）只对"可回滚核心"
        //   （StateIo.supportsRollback —— 目前仅 FBNeo，实测稳定）开放；
        //   其他核心（NES/SFC/GBA/MD/PCE）一律报 stateCap=-1 且不做出档探测 ——
        //   服务器对这类房间改走"全员同时重开"（restart，4.2 语义：新人加入 =
        //   新的一局，全员回开机态从帧 0 同步开跑）。
        //   （v4.5~v4.6 这些核心的存档装载/帧号对齐在实测中反复翻车 —— 断开、
        //   互等死锁均出在这条链上；快照文件本身诚实与否已无法逐一验证，
        //   按用户要求直接降级，稳定优先。）
        val rollbackCapable = !isSpectator && (io?.supportsRollback() ?: false)
        // ★★ v52 追赶能力：引擎支持装档 + runFrameDirect 离屏快放
        //   （与回滚同一能力门控）；观战者也可追赶 —— 它是同一条输入流
        //   的只读消费者（服务器据此决定接入者走“不暂停追赶”还是传统暂停同步）。
        val catchupCapable = io?.supportsRollback() ?: false

        // ★★ v4.5.1 出档能力探测（引擎停在启动门的安全窗口内）—— 仅对可回滚
        //   核心执行；其余核心跳过（探测结果不影响降级决策，省一次开机出档）
        var capOk = false
        var probeBytes: ByteArray? = null
        if (rollbackCapable && io != null) {
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
        // 非可回滚核心恒 -1（→ 服务器全员重开）；可回滚核心按探测结果（PGM/改版
        // 出不了档的街机同样走重开）
        stateCap = if (isSpectator) 0 else if (capOk) 1 else -1
        // 回滚模式 = 可回滚核心 && 探测通过（★★ v4.6 双重门控保留：
        //   无 runFrameDirect 的核心绝不能进回滚 —— 检查点装载后重放失败会把
        //   核心留在过去的时间线，永久发散）
        rollbackMode = rollbackCapable && capOk
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
            try { n.sendReady(suggested, stateCap, catchupCapable) } catch (_: Throwable) {}
        }, "battle-rtt-probe").apply { isDaemon = true }.start()
    }

    /** 按档大小选下次上传间隔（小档传得勤 —— 回退窗口短）。 */
    private fun gapForSize(bytes: Long): Long =
        if (bytes <= UPLOAD_SIZE_SMALL) UPLOAD_GAP_SMALL
        else if (bytes <= UPLOAD_SIZE_MEDIUM) UPLOAD_GAP_MEDIUM
        else UPLOAD_GAP_LARGE

    /**
     * 本地玩家输入。★ v50 起位格式与本地直推 [com.nesstation.app.core.engine.EmulatorEngine.setPad1]
     * 完全一致 —— 即 EmulatorScreen.routePadBits 的输出：
     *   - NES/SNES/GBA/MD/PCE 等直通平台：项目位布局（bit0=A…）；
     *   - ARCADE/NDS/PSX/PS2/PSP/DC：已转换的标准 libretro JOYPAD 位
     *     （如街机 B=bit8/JOYPAD_A、C=bit1/JOYPAD_Y，见 arcadeToLibretroLayout）。
     * 此前虚拟按键联机时推的是未转换的项目原始位 → FBNeo 把项目 B 位当街机
     * 键3(C) → 对战街机 B/C 相反（实体手柄路径一直正确）；v50 起虚拟按键也
     * 统一走 routePadBits，本方法收到的即最终位布局，padsHistory 原样存转。
     */
    fun setLocalPad(bits: Int) {
        currentLocalPad = bits
    }

    /** 本端槽位（1P=1…4P=4）。 */
    val slot: Int get() = mySlot

    /** ★★ v51 游戏内聊天：发送文本给房间全员（玩家+观战者；服务器盖戳转发）。
     *  空白丢弃、限长 200 字符（与服务器规则一致）。观战者同样可发。
     *  ★★ v60 双通道保险（用户多轮实测"依旧看不到对方消息"的终结手段）：
     *  原生 chat 与输入通道兜底【同时发送】—— 无论服务器版本如何
     *  （<v51 无 chat 中继 → 兜底送达；v51+ → 原生送达；两条都到 → 接收端
     *  按通道去重只显示一条）。服务器版本探测（srv 字段）不再作为发送依据
     *  —— 探测失败/字段缺失/hello 竞态都不再影响送达。
     *  ★★ v59 本地留痕：发送即入 [chatLog]（UI 重组/开关菜单后历史不丢）。 */
    fun sendChat(text: String) {
        val n = net ?: return
        val t = text.trim()
        if (t.isEmpty()) return
        appendChatLog("${mySlot}P", t, mySlot, spectatorMode, mine = true)
        // ★ v60：两条通道都发，接收端去重（见 recordIncomingChat）。
        try {
            n.sendChat(t)
        } catch (_: Throwable) { /* 断连时丢消息即可，不影响对战 */ }
        sendChatFallback(t)
    }

    // =========================================================================
    // ★★ v59 聊天记录留痕（UI 生命周期无关）
    // =========================================================================
    // 背景：聊天消息此前只存在 Composable 的 remember 状态里 —— 用户打开游戏
    //   菜单（showMenu/设置/编辑器等）时聊天浮层离开组合，remember 状态清零；
    //   回到游戏后聊天面板【历史全没】+ 气泡只显示 5 秒 —— 用户感知就是
    //   "消息没显示/丢了"。留痕移入控制器（与 UI 同生命周期无关），
    //   浮层每次进入组合从快照恢复，历史跨菜单开关/面板开关不丢。
    /** 聊天留痕条目（from/slot/spec + mine=本端发出）。 */
    class ChatEntry(val from: String, val text: String, val slot: Int, val spec: Boolean, val mine: Boolean)

    /** 最近聊天留痕（上限 50 条，超出丢最老）。 */
    private val chatLog = java.util.concurrent.CopyOnWriteArrayList<ChatEntry>()

    /** 追加一条聊天留痕（发送本地回显 / 接收远端消息两处调用）。 */
    private fun appendChatLog(from: String, text: String, slot: Int, spec: Boolean, mine: Boolean) {
        chatLog.add(ChatEntry(from, text, slot, spec, mine))
        while (chatLog.size > 50) chatLog.removeAt(0)
    }

    /** 当前聊天留痕快照（UI 进入组合时恢复历史用）。 */
    fun chatLogSnapshot(): List<ChatEntry> = ArrayList(chatLog)

    /** 接收侧聊天入留痕（netListener.onChat / handleChatFragment 两处调用）。
     *  ★★ v60 跨通道去重：发送端双通道保险（原生 chat + 负帧号 input 兜底）
     *  会把同一条消息送达两次 —— 这里按 (slot, 文本) 键跟踪最近一条的
     *  【通道 + 时间】：2 秒内同键不同通道到达 = 双发回声 → 丢弃（返回
     *  false）；同通道连续到达（用户真的连发两句相同话）不受影响照常保留。
     *  去重表不随丢弃更新（保留首见通道/时间，保证后续真正的同通道重复
     *  仍能正确判定）。
     *  @param channel 0 = 原生 chat 中继；1 = 输入通道兜底 */
    private val chatDedup = java.util.concurrent.ConcurrentHashMap<String, Pair<Int, Long>>()

    private fun recordIncomingChat(
        from: String, text: String, slot: Int, spec: Boolean, channel: Int
    ): Boolean {
        val now = System.currentTimeMillis()
        val key = "$slot|$text"
        val prev = chatDedup[key]
        if (prev != null && prev.first != channel && now - prev.second < 2_000) {
            return false   // 双通道双发回声：丢弃
        }
        chatDedup[key] = channel to now
        // 防膨胀：超 128 条按时间清最老（正常聊天远达不到）
        if (chatDedup.size > 128) {
            chatDedup.entries.sortedBy { it.value.second }.take(chatDedup.size - 96)
                .forEach { chatDedup.remove(it.key) }
        }
        appendChatLog(from, text, slot, spec, mine = false)
        return true
    }

    // =========================================================================
    // ★★ v54 老服务器聊天兜底：聊天编码进 input 通道（帧号 < 0）
    // =========================================================================
    // 背景：chat 中继是 v51+ 服务器能力。线上服务器若还跑着 <v51 的旧二进制，
    //   客户端发出的 chat 消息被静默丢弃（用户连续多轮实测"聊天对方收不到"
    //   的最顽固根因 —— 服务器代码本身正确，但没重新编译部署）。
    //   input 中继是 v1 起的核心功能（所有服务器版本都转发）—— 把聊天编码成
    //   "帧号 < 0 的 input"即可在任意服务器版本上送达：
    //   - 服务器 input 转发原样透传 frame（真实输入帧号恒 >= 0，负帧号天然
    //     区分）；slot 由服务器盖戳为发送者真实座位（顺带提供可信身份）；
    //   - 旧客户端收到负帧号 input：帧号永不被等待、不进 padsHistory ——
    //     静默忽略，混合版本安全；
    //   - 服务器侧 inpHist 记账对负帧号无害（环形清理会带走；
    //     composeCatchupHist 只取正帧段）。
    //   注意：v48+ 服务器忽略观战者/追赶中连接的 input —— 这两类连接的
    //   兜底消息会丢（观战者聊天需 v51+ 服务器；追赶者追平后即可聊）。
    // 编码：文本 → UTF-8 字节（≤200 字符 ≈ ≤600 字节）→ 3 字节/条：
    //   数据条 i（0..n-1）：frame = -(i+1)，pad = (b0<<16)|(b1<<8)|b2（恒 ≥ 0）；
    //   结束条：frame = CHAT_FALLBACK_END_FRAME，pad = 条数 n。
    // 解码：按 slot 缓冲数据条，收到结束条按序拼装 UTF-8 解码 → onChat。
    //（CHAT_FALLBACK_* 常量在 companion object）

    /** 各槽位在途聊天分片（slot → index → 3 字节数据；仅网络线程访问）。 */
    private val chatFragments = HashMap<Int, java.util.TreeMap<Int, Int>>()

    /** ★★ v60 聊天兜底发送（无条件双发保险的一部分）：把文本编码成负帧号
     *  input 发送 —— input 中继是 v1 起所有服务器版本都转发的通道。
     *  v60 起不再看 serverProtoVersion（不再“二选一”）—— sendChat 总是
     *  原生 + 兜底双发，接收端按通道去重。 */
    private fun sendChatFallback(text: String) {
        val n = net ?: return   // 无连接：吞掉（与 sendChat 断连语义一致）
        val bytes = try { text.toByteArray(Charsets.UTF_8) } catch (_: Throwable) { return }
        if (bytes.isEmpty()) return
        val chunks = (bytes.size + 2) / 3
        if (chunks > CHAT_FALLBACK_MAX_CHUNKS) return   // 超限（UI 已截 200 字）
        try {
            for (i in 0 until chunks) {
                val b0 = bytes[i * 3].toInt() and 0xFF
                val b1 = if (i * 3 + 1 < bytes.size) bytes[i * 3 + 1].toInt() and 0xFF else 0
                val b2 = if (i * 3 + 2 < bytes.size) bytes[i * 3 + 2].toInt() and 0xFF else 0
                n.sendInput(-((i + 1).toLong()), (b0 shl 16) or (b1 shl 8) or b2, mySlot)
            }
            n.sendInput(CHAT_FALLBACK_END_FRAME, chunks, mySlot)
        } catch (_: Throwable) { /* 断连丢消息即可 */ }
    }

    /** 收到负帧号 input → 聊天分片解码（仅网络线程调用）。
     *  ★★ v59 修复（用户实测"发几十条一条都看不到"的根因）：
     *  编码端数据条 i（0..n-1）的帧号 = -(i+1) → 解码侧 TreeMap 键是
     *  【1..n】（1-based）。旧代码组装时用 `buf[i]`（i 从 0 起，0-based）
     *  取片 —— buf[0] 永不存在 → "缺片丢弃" → 兜底通道上【每一条】
     *  消息都在组装时被静默丢掉（老服务器 <v51 无原生 chat 中继时聊天
     *  全灭）。改为 buf[i + 1] 按 1-based 键取片。 */
    private fun handleChatFragment(frame: Long, pad: Int, slot: Int) {
        if (slot !in 1..4) return
        if (frame == CHAT_FALLBACK_END_FRAME) {
            val count = pad
            val buf = chatFragments.remove(slot) ?: return
            if (count <= 0 || count > CHAT_FALLBACK_MAX_CHUNKS || buf.size != count) return
            val bytes = ByteArray(count * 3)
            for (i in 0 until count) {
                val v = buf[i + 1] ?: return   // ★★ v59：键为 1..count（idx=-frame），
                bytes[i * 3] = (v shr 16).toByte()     // 0-based 取片永远缺第一片
                bytes[i * 3 + 1] = (v shr 8).toByte()
                bytes[i * 3 + 2] = v.toByte()
            }
            val text = try {
                var end = bytes.size
                while (end > 0 && bytes[end - 1].toInt() == 0) end--   // 去掉尾部补零
                String(bytes, 0, end, Charsets.UTF_8)
            } catch (_: Throwable) { return }
            if (text.isNotBlank()) {
                android.util.Log.i("NetplayController", "chat(fallback) from ${slot}P: $text")
                // ★ v60 channel=1：输入通道兜底（与原生 chat 通道去重配合）
                if (recordIncomingChat("${slot}P", text, slot, false, channel = 1)) {
                    dispatch { it.onChat("${slot}P", text, slot, false) }
                }
            }
            return
        }
        val idx = (-frame).toInt()
        if (idx <= 0 || idx > CHAT_FALLBACK_MAX_CHUNKS) return
        val buf = chatFragments.getOrPut(slot) { java.util.TreeMap() }
        if (idx == 1) buf.clear()   // 新消息开始：覆盖旧缓冲（发送端顺序发送）
        buf[idx] = pad
    }

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
        // ★★ v48 外层重试环：等待远端输入期间若发生"门事件"（同步暂停 pause /
        //   全员重开 restart），本帧作废 —— 回滚已消费的帧号、回到门上重新对齐
        //   （存档装载/帧号改写都在引擎挂门期间安全完成）。
        //   旧版缺陷：pause 到达时引擎线程可能正阻塞在 awaitSlotInput 里 ——
        //   ① parkedLocalFrame 永不置位 → 房主出档 5s 超时；② resume 的
        //   loadStateFrom/帧号改写与 runFrame 并发执行 → 核心状态损坏；
        //   ③ 引擎带着旧时间线帧号继续跑 → 检查点 tag 错位 → 永久发散。
        outer@ while (true) {
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
                            // ★★ v55 提示语区分场景（用户实测“等待同步开始太久”
                            //   的截图误导性提示）：已加入在跑的房间 = 在等
                            //   服务器发同步画面包（马上快进追赶）；初始开局 =
                            //   在等全员就绪统一开跑。
                            val inPlayingRoom = net?.roomPlaying == true
                            dispatch {
                                it.onNetplayWait(if (inPlayingRoom)
                                    "已加入对战，正在获取同步画面（马上开始快进追赶）…"
                                else "等待同步开始（全员就绪后自动开跑）…")
                            }
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
                // ★★ v4.7 零输入过渡帧：本帧未发号（计数器未消费）—— afterFrame
                //   跳过记账，防把“多跑的一帧”记到上一帧号头上
                zeroPadFrame = true
                return IntArray(4)
            }

            // ★★ v52 追赶接入（小马式中途加入不暂停）。★★ v54 黑屏极速追赶：
            //   引擎线程在此【紧循环离屏快放直到追平】（runFrameDirect 不渲染
            //   不出声），屏幕保持黑屏（EmulatorScreen 全屏黑底进度浮层），
            //   追平后落入下方正常锁步 —— 画面以同步状态直接出现。
            //   （v52/v53 为“每可见帧快放 15 帧”的可见快进，受引擎 60fps
            //   渲染节奏限制净速度只有 ~15 倍速 —— 用户实测“启动游戏慢慢
            //   追赶”；v54 改为不受渲染节奏约束的纯速度追赶，几十秒差距
            //   亚秒级追平，追赶期间黑屏 + 进度，追平即同步画面。）
            // ★★ v53 动态目标：追平目标 = max(histEnd, 最新已见远端输入帧-90)
            //   —— 追赶期间服务器照常广播 input、onRemoteInput 持续刷新
            //   catchupLatestSeen，目标随实时帧滚动前移：追平时与原玩家仅差
            //   1.5 秒，锁步无缝并入（v52 冻结目标追平后永久错开“追赶耗时×60
            //   帧”——用户实测“追赶后没同步”的根治）。
            if (catchupMode) {
                val n0 = net
                val io = stateIo
                if (n0 == null) { zeroPadFrame = true; return IntArray(4) } // 断连：停模拟
                if (io == null || !io.supportsRollback()) {
                    // 引擎不支持快放（不该到这 —— ready 能力上报门控过）：自救重开
                    catchupMode = false
                    catchupProgressText = ""
                    dispatch { it.onSyncStatus("追赶不可用，请求全员重新同步…") }
                    try { n0.sendRestartReq() } catch (_: Throwable) {}
                    zeroPadFrame = true
                    return IntArray(4)
                }
                var lastReport = 0L
                var lastReportDone = 0L
                while (netFrameCounter < catchupTarget()) {
                    // 门事件（暂停/重开接管追赶）：作废本轮追赶，回外层环过门重新
                    // 对齐（时间线将被存档装载/复位替换，已快放的帧自然作废）
                    if (syncPaused || restartPending) {
                        continue@outer
                    }
                    val f = netFrameCounter
                    catchupTimedOut = false
                    // ★★ v56 重放输入按【执行帧】索引（f - inputDelay）—— 与全员锁步
                    //   同一映射：存档代表 [0..f-1-inputDelay] 已执行完，核心下一帧应
                    //   消费 pads(f-inputDelay)。旧版直接取 pads(f)（网络帧索引），
                    //   重放输入序列比原玩家超前 inputDelay 帧 —— 重放窗口内任何
                    //   一端有按键，追赶者的画面就从接轨点起与原玩家永久错开
                    //   （“同步追赶成功后有时候各打各的”的头号根因）。
                    //   服务器 hist 自带 16 帧热身窗（[档帧-16, 档帧)），正是为覆盖
                    //   [档帧-inputDelay, 档帧) 这段执行窗（与暂停流 resume 同一语义）。
                    val pads = catchupPadsFor(f - inputDelay, CATCHUP_INPUT_TIMEOUT_MS)
                    if (pads == null) {
                        if (catchupTimedOut) {
                            // ★★ v54 历史缺口自救：等待重放输入超时（服务器组装的
                            //   历史有缺口 / 老服务器）—— 追赶永不能完成，请求全员
                            //   重开（绝不无限黑屏挂起；旧版这里会永久卡死追赶）
                            catchupMode = false
                            catchupProgressText = ""
                            dispatch { it.onSyncStatus("追赶失败（输入历史缺口），已请求全员重新同步…") }
                            try { n0.sendRestartReq() } catch (_: Throwable) {}
                            zeroPadFrame = true
                            return IntArray(4)
                        }
                        if (syncPaused || restartPending) {
                            // 门事件：作废本轮追赶，回外层环过门重新对齐
                            //（时间线将被存档装载/复位替换，已快放的帧自然作废）
                            continue@outer
                        }
                        // 断连：清追赶 + 零输入过渡帧（引擎按自身节奏跑帧 ——
                        //   绝不 continue 空转烧 CPU；后续 onNetplayLost 引导 UI）
                        catchupMode = false
                        catchupProgressText = ""
                        zeroPadFrame = true
                        return IntArray(4)
                    }
                    try { io.discardPendingAudio() } catch (_: Throwable) {}
                    if (!io.runFrameDirect(pads)) {
                        catchupMode = false
                        catchupProgressText = ""
                        dispatch { it.onSyncStatus("追赶重放失败，请求全员重新同步…") }
                        try { n0.sendRestartReq() } catch (_: Throwable) {}
                        zeroPadFrame = true
                        return IntArray(4)
                    }
                    netFrameCounter++
                    val now = System.currentTimeMillis()
                    if (now - lastReport >= 200) {
                        val done = netFrameCounter - catchupFromFrame
                        val total = (catchupTarget() - catchupFromFrame).coerceAtLeast(1)
                        // ★★ v59 追赶提速可视化：显示重放速率（帧/秒）+ 预计剩余 ——
                        //   重放速率远超 60fps 就是快进状态在满速推进；用户能看到
                        //   追赶在动，不再感知为"卡住/太慢"。
                        val rate = if (lastReport > 0 && done > lastReportDone)
                            ((done - lastReportDone) * 1000L / (now - lastReport)).coerceAtLeast(1L)
                        else 0L
                        lastReport = now
                        lastReportDone = done
                        val remain = (total - done).coerceAtLeast(0L)
                        val eta = if (rate > 0) "${remain / rate}s" else "…"
                        catchupProgressText = "$done/$total · ${rate}f/s · 剩$eta"
                        dispatch { it.onSyncStatus("极速追赶中… $done/$total 帧（${rate} 帧/秒，剩 $eta）") }
                    }
                }
                // 追平：自己槽位 inputDelay 预热窗归零（追赶期间
                //   全员对本槽位用 0 —— 服务器组装的历史里含上任
                //   者的真实输入，不清会误用 → 单端发散）；报告服务器
                //   追平（广播座位激活）；无缝落入下方正常锁步流程。
                catchupMode = false
                catchupProgressText = ""
                seedOwnZeroWindow(netFrameCounter, overwrite = true)
                lastIssuedNetFrame = netFrameCounter - 1
                // ★★ v56：state_ok 发出后到 slot_active 到达前，全员都把本座位当
                //   “未激活”（确定性 0）—— 本端同样归零（下方 mySlot 分支的
                //   pendingOwnActivation/slotActiveFrom 门控），激活帧之后才用
                //   实时输入。旧版本端在此窗口直接吃实时输入 → 只要按了键就
                //   单端永久发散。采样与发送不受影响（上方照常发 input）。
                if (mySlot in 1..4 && !isSpectator) pendingOwnActivation = true
                try { n0.sendStateOk() } catch (_: Throwable) {}
                dispatch { it.onSyncStatus("画面已追平，接入完成 —— 对战中！") }
                android.util.Log.i("NetplayController", "catchup done at frame $netFrameCounter")
            }

            // ★★ v4.7 内部帧号：网络帧号由控制器自己发号（不再用引擎本地帧换算 ——
            //   引擎计数器在 restart 复位瞬间会错位，各端互不一致，"1p 等 2p、
            //   2p 等 1p" 互等死锁的根源）。start（0）/resume 装载（存档帧）/restart
            //   复位（0）三类对齐事件直接改写计数器 —— 全员发出的 (netFrame→pads)
            //   序列严格一致。仅引擎线程在此消费；网络线程只在引擎挂门时改写。
            val prevIssuedNetFrame = lastIssuedNetFrame
            val netFrame = netFrameCounter
            netFrameCounter = netFrame + 1L
            lastIssuedNetFrame = netFrame
            val n = net

            // ★★ v4.4 时间线重置应用（pause→state→resume 后新时间线首帧；
            //   仅此处（引擎线程）触碰 rbCkFrame/inputLog 结构性重置）
            val resetFrame = rbResetFrame
            if (resetFrame != java.lang.Long.MIN_VALUE) {
                rbResetFrame = java.lang.Long.MIN_VALUE
                rbApplyReset(resetFrame)
            }

            // ★★ v48 门事件中止处理：本帧已发号/已发送输入但【尚未被任何端执行】
            //   （还在等某个对端的输入 = 所有端的执行进度都被它压在该帧之下），
            //   回滚发号后重新过门 —— 裸恢复（无存档）时按新发号重跑同一帧，
            //   带档恢复/重开时整个时间线被替换，作废帧自然丢弃。
            fun abortFrameForGateEvent(): IntArray {
                netFrameCounter = netFrame
                lastIssuedNetFrame = prevIssuedNetFrame
                gateEventPending = true
                return IntArray(4)
            }

            // ★★ v4.4 观战模式：不占座位、不采样不发送；等全员输入同画面观战。
            if (isSpectator) {
                if (n == null) { zeroPadFrame = true; return IntArray(4) }
                inputDelay = n.inputDelay.coerceIn(0, 16)
                val execFrame = netFrame - inputDelay
                if (execFrame < 0) return IntArray(4) // warmup：全 0
                val pads = IntArray(4)
                for (slot in 1..4) {
                    if (slotNeedsInput(slot, execFrame)) {
                        var pad = padsHistory[slot][execFrame]
                        if (pad == null) pad = awaitSlotInput(slot, execFrame)
                        if (pad == null) {
                            // ★★ v48：null = 门事件（pause/restart）或断连。
                            //   门事件：本帧作废（回滚发号）+ 回门重对齐 —— 绝不
                            //   执行零输入帧（其他端会继续用真实输入 → 发散）。
                            if (restartPending || syncPaused) {
                                abortFrameForGateEvent()
                                continue@outer
                            }
                            return IntArray(4) // 断连：停模拟
                        }
                        pads[slot - 1] = pad
                    }
                }
                return pads
            }

            // ★★ v4.4 回滚模式（预测 + 纠错重放，手感 = 延迟帧级而非 RTT 级）
            if (rollbackMode && stateIo != null) {
                gateEventPending = false
                val rbPads = rbBeforeFrame(netFrame)
                if (gateEventPending && (restartPending || syncPaused)) {
                    abortFrameForGateEvent()   // ★★ v48 门事件：回滚发号，本帧作废
                    continue@outer
                }
                return rbPads ?: IntArray(4)
            }

            // ---- 延迟模式（v4.3 原逻辑）----
            val localPad = currentLocalPad
            if (mySlot in 1..4) padsHistory[mySlot][netFrame] = localPad

            if (n == null) {
                // 无网络（断连/未连上）：本帧已发号但未发送 —— 记零输入过渡帧
                zeroPadFrame = true
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
            //    ★★ v48 离队槽位：≤ deadSlotLastFrame 的在途输入等到再用、
            //    其后确定性归零（各端一致）；未激活/接入中槽位恒 0。
            val pads = IntArray(4)
            for (slot in 1..4) {
                if (slot == mySlot) {
                    // ★★ v56 座位激活门控：激活帧（含）之前全员对本槽位确定性用 0
                    //   （其他端 slotNeedsInput 同一判定）—— 追赶/转座并入后的激活
                    //   窗口内，本端若直接用实时输入，一旦按键即与全员错开（永久
                    //   发散）。采样与发送不受影响（上方照常发 input）。
                    val actFrom = slotActiveFrom[mySlot]
                    pads[slot - 1] =
                        if (pendingOwnActivation || (actFrom != null && execFrame <= actFrom)) 0
                        else padsHistory[slot][execFrame] ?: localPad
                } else if (slotNeedsInput(slot, execFrame)) {
                    var pad = padsHistory[slot][execFrame]
                    if (pad == null) {
                        pad = awaitSlotInput(slot, execFrame)
                    }
                    if (pad == null) {
                        // ★★ v48：null = 门事件（pause/restart，本帧作废回外层环）
                        //   或断连（停模拟，后续 onPeerLeft/onNetplayLost 引导 UI）。
                        //   旧版在这里直接 return IntArray(4) 用全 0 跑帧 —— 而其他
                        //   端可能用离队者在途的真实输入 → 单端永久发散。
                        if (restartPending || syncPaused) {
                            abortFrameForGateEvent()
                            continue@outer
                        }
                        zeroPadFrame = true
                        return IntArray(4)
                    }
                    pads[slot - 1] = pad
                }
            }
            return pads
        }
    }

    /** ★★ v48 该槽位在 [execFrame] 帧是否需要等待/使用其输入：
     *  - 活跃槽位 → 需要；
     *  - 离队槽位且 execFrame ≤ 最后中继帧（输入仍在途，TCP 有序必达）→ 需要；
     *  - 其余（离队后的帧 / 从未激活）→ 不需要（确定性 0）。 */
    private fun slotNeedsInput(slot: Int, execFrame: Long): Boolean {
        // ★★ v52：追赶/转座新激活的座位 —— 激活帧（含）之前不算活跃
        //   （此前全员对其确定性归零；激活帧之后才开始等它 —— 各端一致）
        val actFrom = slotActiveFrom[slot]
        if (actFrom != null && execFrame <= actFrom) return false
        if (activeSlots.contains(slot)) return true
        val dead = deadSlotLastFrame[slot] ?: return false
        return execFrame <= dead
    }

    /** 兼容旧 2 手柄路径（NES/SNES/GBA 等核心的 frameHook 调用）。 */
    override fun beforeFrame(frame: Long): Pair<Int, Int>? {
        return beforeFrameImpl(frame)?.let { pads -> pads[0] to pads[1] }
    }

    /** ★ v4.3 四手柄路径（FBNeo 等支持 setPad1..4 的核心经 NetplayHook 默认方法调用）。 */
    override fun beforeFramePads(frame: Long): IntArray? = beforeFrameImpl(frame)

    /**
     * ★ 阻塞等待某槽位第 [targetFrame] 帧的输入。
     * ★★ v48 返回值语义：
     *  - 非 null：已解析的输入值 —— 真实输入，或【离队槽位在最后中继帧之后的
     *    确定性归零 0】（各端一致）；
     *  - null：门事件（restartPending / syncPaused —— 本帧作废，调用方回外层环）
     *    或连接断开（停模拟）。
     */
    private fun awaitSlotInput(slot: Int, targetFrame: Long): Int? {
        var lastNotice = 0L
        while (true) {
            padsHistory[slot][targetFrame]?.let { return it }
            // ★★ v4.5.1 全员重开请求：立即让路（引擎线程下一迭代应用复位）
            if (restartPending) return null
            // ★★ v48 同步暂停：立即让路（本帧作废，回外层环重新过门 ——
            //   旧版死等在途输入 → parkedLocalFrame 永不置位 → 房主出档超时 /
            //   resume 装载与跑帧并发 → 状态损坏）
            if (syncPaused) return null
            // ★★ v48 离队槽位：帧号 > 最后中继帧的输入永不再来 → 确定性归 0
            //   （≤ lastFrame 的仍在途，TCP 有序必达，继续等 —— 各端输入序列
            //   严格一致，不再各自提前清零导致单端发散）
            if (!activeSlots.contains(slot)) {
                val dead = deadSlotLastFrame[slot] ?: -1L
                if (targetFrame > dead) return 0
                // targetFrame <= dead：在途输入，继续等
            }
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

    /** ★★ v54 追赶输入等待（带截止时间，与 [awaitSlotInput] 同语义 + 超时）：
     *  - 非 null：输入已到（历史或实时）；
     *  - null + catchupTimedOut=true：超时（输入历史缺口 —— 追赶永不能完成，
     *    调用方自救请求全员重开）；
     *  - null + 未超时：门事件（pause/restart，追赶作废回门）或断连。 */
    private fun awaitSlotInputDeadline(slot: Int, targetFrame: Long, deadline: Long): Int? {
        var lastNotice = 0L
        while (true) {
            padsHistory[slot][targetFrame]?.let { return it }
            if (restartPending) return null
            if (syncPaused) return null
            if (!activeSlots.contains(slot)) {
                val dead = deadSlotLastFrame[slot] ?: -1L
                if (targetFrame > dead) return 0
                // targetFrame <= dead：在途输入，继续等
            }
            val n = net
            if (n == null || !n.isConnected) return null
            val now = System.currentTimeMillis()
            if (now >= deadline) {
                catchupTimedOut = true
                android.util.Log.w("NetplayController",
                    "catchup input wait timeout: slot=$slot frame=$targetFrame")
                return null
            }
            if (now - lastNotice >= 1000) {
                lastNotice = now
                dispatch { it.onNetplayWait("追赶：等待 ${slot}P 输入中…") }
            }
            try { Thread.sleep(2) } catch (_: InterruptedException) { return null }
        }
    }

    override fun afterFrame(frame: Long) {
        // ★★ v4.7 零输入过渡帧（重启让路/断连的无网络帧）：跳过记账 ——
        //   这帧没有对应的网络帧号（计数器未消费），绝不能把检查点/上传
        //   记到上一帧号头上（核心状态比帧号超前一帧 → 检查点 tag 错位）。
        if (zeroPadFrame) {
            zeroPadFrame = false
            return
        }
        // ★★ v4.7 帧号取自本控制器刚发出的网络帧号（引擎传来的本地帧号
        //   在 restart 复位等场合会错位，不再使用）
        val netFrame = if (lastIssuedNetFrame >= 0) lastIssuedNetFrame else frame
        if (netFrame % 15 == 0L) {
            val il = inputDelay
            val dc = desyncCount
            dispatch { it.onFrameInfo(netFrame, il, dc) }
        }
        // ★★ v4.5 服务器侧存档：房主周期上传快照 → 服务器缓存（供 2P/3P/4P/观战
        //   加入时同步 —— “存档同步在服务器”）。引擎线程里出档、后台线程编码发送；
        //   暂停/同步期间引擎不跑帧，自然不会上传。间隔按档大小自适应。
        // ★★ v53 静默拉档：服务器 state_pull 请求（无缓存档/历史缺口时有新接入者）
        //   → 立即出档上传（跳过间隔检查 —— 追赶接入者在等这份档，越快越好；
        //   出档与跑帧同线程天然安全，不打断任何人的对局）。
        if (started && !isSpectator && role == "host" && netFrame >= 0 && stateCap > 0) {
            val pullDue = pullSnapshotRequested
            if (pullDue) pullSnapshotRequested = false
            val due = pullDue || (if (lastUploadFrame < 0L) netFrame >= UPLOAD_FIRST_FRAME
                      else netFrame - lastUploadFrame >= uploadGapFrames)
            if (due) {
                lastUploadFrame = netFrame
                // ★★ v55 拉档不被 in-flight 周期上传吞掉：上一份还在发时
                //   恢复 pullSnapshotRequested，下一帧重试（拉档请求意味着
                //   有接入者在启动门上等这份档 —— 绝不能等下一轮周期间隔）
                if (!uploadSnapshot(netFrame) && pullDue) {
                    pullSnapshotRequested = true
                }
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
                slot == mySlot -> {
                    // ★★ v56 座位激活门控（与延迟模式同一判定）：激活帧前全员对
                    //   本槽位确定性 0 —— 回滚重放/inputLog 记录同样一致。
                    val actFrom = slotActiveFrom[mySlot]
                    pads[slot - 1] =
                        if (pendingOwnActivation || (actFrom != null && execFrame <= actFrom)) 0
                        else padsHistory[slot][execFrame] ?: localPad
                }
                !slotNeedsInput(slot, execFrame) -> pads[slot - 1] = 0 // ★★ v52 激活帧前不算活跃
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
                            if (pad == null) {
                                // ★★ v48：null = 门事件（pause/restart，本帧作废 ——
                                //   置标记让外层环回滚发号回门）或断连（停模拟）。
                                //   旧版直接 return 全 0 跑帧 —— 与其他端的在途
                                //   真实输入不一致 → 单端永久发散。
                                if (restartPending || syncPaused) gateEventPending = true
                                return IntArray(4)
                            }
                        }
                    }
                    pads[slot - 1] = pad
                }
                // ★★ v48 离队槽位：≤ deadSlotLastFrame 的在途输入等到再用、
                //   其后确定性归零（与延迟模式同一语义，各端一致）
                slotNeedsInput(slot, execFrame) -> {
                    var pad = padsHistory[slot][execFrame]
                    if (pad == null) pad = awaitSlotInput(slot, execFrame)
                    if (pad == null) {
                        if (restartPending || syncPaused) gateEventPending = true
                        return IntArray(4) // 门事件/断连
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
                    // ★★ v52 激活帧（含）之前不算活跃（全员对新座位确定性归零）
                    !slotNeedsInput(slot, f) -> 0
                    activeSlots.contains(slot) -> padsHistory[slot][f] ?: rbLastKnownPad.get(slot)
                    // ★★ v48 离队槽位：≤ 最后中继帧的在途输入用真实值（未到则 0，
                    //   后到会触发不一致→再回滚纠正）；其后确定性归零
                    else -> padsHistory[slot][f] ?: 0
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

    /** 回滚不可用时的退化：阻塞等待待纠正帧及之后的实际输入到齐（不预测）。
     *  ★★ v48：门事件（pause/restart）/断连立即返回（本帧作废，外层处理）。 */
    private fun rbBlockWaitFor(fromFrame: Long) {
        val lastExec = rbLastExecuted
        for (slot in activeSlots.toList()) {
            if (slot == mySlot) continue
            var f = fromFrame
            while (f <= lastExec) {
                if (padsHistory[slot][f] == null) {
                    val got = awaitSlotInput(slot, f) // null（门事件/断连）：立即让路
                    if (got == null) return
                    // 非 null：输入已到或离队归零 —— 继续
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
        deadSlotLastFrame.clear()
        gateEventPending = false
        currentLocalPad = 0
        frameOffset = 0
        netFrameCounter = 0L
        lastIssuedNetFrame = -1L
        zeroPadFrame = false
        parkedLocalFrame = -1
        desyncCount = 0
        engineLoaded = false
        readySent = false
        started = false
        syncPaused = false
        restartPending = false
        restartFailed = false
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
