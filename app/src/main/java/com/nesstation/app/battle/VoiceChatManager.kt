package com.nesstation.app.battle

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.os.Process
import android.util.Base64
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean

/**
 * ★★ v65 对战同步语音（用户需求："给对战平台里对战时加入同步语音系统，
 * 打开后可以随意语音聊天对战"）。
 *
 * ## 传输通道
 *
 * 复用既有 TCP 中继（与聊天 type=chat 同构的新消息 type=voice）：本端采集
 * → base64 → controller.sendVoice → 服务器盖戳 from/slot/spec 转发房间其他
 * 成员 → NetplayController.onVoice → [submitReceived] 入播放队列 → AudioTrack
 * 出声。服务器只转发不编解码；旧服务器（<v65）会把 voice 静默丢弃 —— 仅语音
 * 不可用，游戏与聊天不受影响。
 *
 * ## 音频参数（语音通话级，带宽友好）
 *
 *   PCM16 / 单声道 / 16kHz / 40ms 一帧（640 样本 = 1280B）
 *   → base64 ≈ 1.8KB/帧 → ~45KB/s ≈ 360kbps，对战 TCP 中继毫无压力；
 *   帧长 40ms = 延迟与频率的折中（25 帧/s，比 20ms 少 20% 消息量，感知不出）。
 *
 * ## 采集端
 *
 *  - 音源 VOICE_COMMUNICATION：走系统语音通话处理链（自动增益 + 采样率自适应）；
 *  - AcousticEchoCanceler / NoiseSuppressor 可用即启用 —— 外放对战的回声/噪
 *    声抑制（回声不消，对方会听到自己的声音回授一圈）；
 *  - 采集线程 read 阻塞模式（不忙轮询），THREAD_PRIORITY_URGENT_AUDIO；
 *  - AudioRecord 构造失败（权限被收回/被通话占用）→ 置 lastError 并停，
 *    UI 以 Toast 提示，绝不崩溃。
 *
 * ## 播放端
 *
 *  - USAGE_VOICE_COMMUNICATION + CONTENT_TYPE_SPEECH 流模式 AudioTrack；
 *  - 单线程阻塞 write 消费队列；队列上限 12 帧（≈480ms 抖动缓冲），超出丢
 *    最老帧 —— 慢网络下宁丢帧不加延迟；
 *  - 不做本地回显（本端声音环境可自闻）；关闭语音 → stop 释放全部资源。
 *
 * 线程模型：全部 API 幂等可重入（start/stop 任意线程任意次数）；running/
 * playing AtomicBoolean 防重入；资源释放统一在各自线程内完成（不在调用线程
 * 释放另一线程正在用的对象）。
 */
object VoiceChatManager {

    private const val SAMPLE_RATE = 16_000
    private const val FRAME_MS = 40L
    private const val FRAME_SAMPLES = (SAMPLE_RATE * FRAME_MS / 1000).toInt()   // 640
    private const val FRAME_BYTES = FRAME_SAMPLES * 2                           // 1280 (PCM16)
    /** 播放抖动缓冲上限（帧）。480ms —— 超出丢最老，延迟封顶。 */
    private const val PLAY_QUEUE_MAX = 12

    /** 最近一次启动失败原因（UI 可读；null = 无错误）。 */
    @Volatile
    var lastError: String? = null
        private set

    private val running = AtomicBoolean(false)      // 采集（发送）侧
    private val playing = AtomicBoolean(false)      // 播放（接收）侧

    private var record: AudioRecord? = null
    private var recordThread: Thread? = null
    private var aec: AcousticEchoCanceler? = null
    private var ns: NoiseSuppressor? = null

    private var track: AudioTrack? = null
    private var playThread: Thread? = null
    private val playQueue = ConcurrentLinkedQueue<ByteArray>()
    @Volatile private var sendFrame: ((String) -> Unit)? = null

    /** 采集（发送）是否已开启。 */
    val isCapturing: Boolean get() = running.get()

    /**
     * 开启语音（麦克风采集 + 播放器）。[onFrame] 收到每帧 base64 音频
     * （调用方应转发 controller.sendVoice； VoiceChatManager 不依赖控制器，
     * 便于单测/复用）。重复调用无副作用；RECORD_AUDIO 权限由调用方保证。
     */
    @SuppressLint("MissingPermission")
    @Synchronized
    fun start(onFrame: (String) -> Unit) {
        if (running.get()) return
        lastError = null
        sendFrame = onFrame
        startPlaying()   // 播放侧无论采集成败都先备好（对端已在说话时零等待）

        val minBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (minBuf <= 0) {
            lastError = "麦克风初始化失败（缓冲区异常 $minBuf）"
            stopCaptureInternal()
            return
        }
        val rec = try {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                maxOf(minBuf, FRAME_BYTES * 4)
            )
        } catch (t: Throwable) {
            lastError = "麦克风不可用：${t.message}（请检查录音权限）"
            stopCaptureInternal()
            return
        }
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            rec.release()
            lastError = "麦克风初始化失败（权限或被其他应用占用）"
            stopCaptureInternal()
            return
        }
        // 外放对战的回声消除 / 噪声抑制（可用即启用；失败不影响主流程）
        try {
            if (AcousticEchoCanceler.isAvailable()) aec = AcousticEchoCanceler.create(rec.audioSessionId)
            if (NoiseSuppressor.isAvailable()) ns = NoiseSuppressor.create(rec.audioSessionId)
        } catch (_: Throwable) {}
        record = rec
        running.set(true)
        val t = Thread({
            android.os.Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
            val buf = ByteArray(FRAME_BYTES)
            rec.startRecording()
            try {
                while (running.get()) {
                    // blocking read：read=0 时让一步（设备瞬时异常防忙转）
                    val n = rec.read(buf, 0, FRAME_BYTES)
                    if (n <= 0) {
                        Thread.sleep(10)
                        continue
                    }
                    val sender = sendFrame ?: continue
                    if (n == FRAME_BYTES) {
                        sender(Base64.encodeToString(buf, Base64.NO_WRAP))
                    } else {
                        // 短读（理论上阻塞模式不会）：按实际长度补齐再发
                        sender(Base64.encodeToString(buf.copyOf(n), Base64.NO_WRAP))
                    }
                }
            } catch (_: InterruptedException) {
            } finally {
                try { rec.stop() } catch (_: Throwable) {}
                try { rec.release() } catch (_: Throwable) {}
                aec?.release(); aec = null
                ns?.release(); ns = null
            }
        }, "netplay-voice-capture").apply { isDaemon = true }
        recordThread = t
        t.start()
    }

    /** 关闭语音（采集 + 播放 + 释放全部资源）。幂等。 */
    @Synchronized
    fun stop() {
        running.set(false)
        recordThread?.interrupt()
        recordThread = null
        record = null
        aec?.release(); aec = null
        ns?.release(); ns = null
        sendFrame = null
        playing.set(false)
        playThread?.interrupt()
        playThread = null
        try { track?.pause(); track?.flush(); track?.release() } catch (_: Throwable) {}
        track = null
        playQueue.clear()
    }

    /** 对端语音帧入口（NetplayController.onVoice → UI → 这里；任意线程可调）。 */
    fun submitReceived(dataBase64: String) {
        if (!playing.get()) return
        val bytes = try {
            Base64.decode(dataBase64, Base64.NO_WRAP)
        } catch (_: Throwable) { return }
        if (bytes.isEmpty()) return
        playQueue.add(bytes)
        // 抖动缓冲封顶：超出丢最老帧（宁丢帧不加延迟）
        while (playQueue.size > PLAY_QUEUE_MAX) playQueue.poll()
    }

    /** 启动播放线程（幂等；capture 开关无关 —— 语音开着就听）。 */
    private fun startPlaying() {
        if (playing.get()) return
        val minBuf = AudioTrack.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (minBuf <= 0) return
        val tr = try {
            AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build())
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build())
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setBufferSizeInBytes(maxOf(minBuf, FRAME_BYTES * 4))
                .setSessionId(AudioManager.AUDIO_SESSION_ID_GENERATE)
                .build()
        } catch (_: Throwable) { return }
        if (tr.state != AudioTrack.STATE_INITIALIZED) {
            try { tr.release() } catch (_: Throwable) {}
            return
        }
        track = tr
        playing.set(true)
        val t = Thread({
            android.os.Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
            try { tr.play() } catch (_: Throwable) {}
            try {
                while (playing.get()) {
                    val frame = playQueue.poll()
                    if (frame == null) {
                        // 队列空：小睡等待（1/4 帧 —— 平衡延迟与 CPU）
                        Thread.sleep(FRAME_MS / 4)
                        continue
                    }
                    // 阻塞写：短写续写（AudioTrack 对 MODE_STREAM 保证顺序）
                    var off = 0
                    while (off < frame.size && playing.get()) {
                        off += tr.write(frame, off, frame.size - off)
                    }
                }
            } catch (_: InterruptedException) {
            } finally {
                try { tr.stop() } catch (_: Throwable) {}
                try { tr.release() } catch (_: Throwable) {}
            }
        }, "netplay-voice-playback").apply { isDaemon = true }
        playThread = t
        t.start()
    }

    /** 采集侧启动失败后的内部收尾（不停播放侧 —— 对端语音照常可听）。 */
    private fun stopCaptureInternal() {
        running.set(false)
        recordThread?.interrupt()
        recordThread = null
        record = null
        sendFrame = null
    }
}
