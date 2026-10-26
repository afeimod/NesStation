package com.nesstation.app.battle

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.view.Surface
import com.nesstation.app.core.jni.DosNative
import com.nesstation.app.core.jni.FbNeoNative
import com.nesstation.app.core.jni.GbaNative
import com.nesstation.app.core.jni.GenesisNative
import com.nesstation.app.core.jni.NesNative
import com.nesstation.app.core.jni.PceNative
import com.nesstation.app.core.jni.SnesNative
import com.nesstation.app.core.model.GamePlatform
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * 帧同步联机引擎：直接驱动 FbNeoNative，按帧推进模拟器。
 *
 * 设计（锁步 lockstep + 输入延迟）：
 *  - 双方各自本地运行同一 ROM（FBNeo 确定性核心），服务器只转发输入。
 *  - 双方帧计数从 0 同时起步，各以 60fps 节奏推进。
 *  - 每个"采样帧"：采集本地摇杆输入 -> 带 frame 号发给服务器 -> 存入本地历史。
 *  - 实际执行帧 = 采样帧 - inputDelay：此时对方的该帧输入早已到达（网络往返被
 *    输入延迟吸收），因此双方在完全相同的输入序列上运行 -> 状态一致。
 *  - 若对方某帧输入因抖动未到，短暂等待；超时则复用上一帧输入（橡皮筋），
 *    记录 desync 计数用于 UI 提示。
 *
 * 线程模型（与 FbNeoEngine 一致）：
 *  - emu 线程：采样 + 发送 + 按帧执行模拟器 + 60fps 节拍
 *  - audio 线程：从原生环形缓冲读音频写 AudioTrack
 *  - net 线程（BattleNetplay）：收对方输入，填充 remoteHistory
 */
class NetplayEngine(
    private val romFile: File,
    private val systemDir: String,
    private val saveDir: String,
    val net: BattleNetplay,
    private val platform: GamePlatform = GamePlatform.ARCADE
) {

    interface Listener {
        /** 每执行一帧后回调（UI 可刷新延迟/帧号显示） */
        fun onFrame(frame: Long, inputDelay: Int, desyncCount: Int)
        /** 模拟器退出（正常结束） */
        fun onExit()
        /** 对端输入长时间缺失，连接已不可用 */
        fun onNetplayLost()
    }

    private val running = AtomicBoolean(false)
    private var emuThread: Thread? = null
    private var audioTrack: AudioTrack? = null
    private val audioRunning = AtomicBoolean(false)
    private var audioThread: Thread? = null

    @Volatile private var currentLocalPad = 0
    @Volatile private var lastRemotePad = 0

    // 帧历史：frame -> pad（环形容量 512，够输入延迟 + 抖动窗口）
    private val localHistory = ConcurrentHashMap<Long, Int>()
    private val remoteHistory = ConcurrentHashMap<Long, Int>()

    @Volatile private var inputDelay = 4
    @Volatile private var isLoaded = false
    @Volatile private var hasSurface = false
    @Volatile private var netplayLost = false

    private var listener: Listener? = null
    private val engineLock = Any()

    // 60fps 节拍
    private val frameNs = 1_000_000_000L / 60

    val loaded: Boolean get() = isLoaded

    fun setListener(l: Listener?) { listener = l }

    /** 本地玩家摇杆输入（bit 布局同 FBNeo 12 键） */
    fun setLocalPad(bits: Int) { currentLocalPad = bits }

    fun setSurface(surface: Surface?) {
        hasSurface = surface != null
        CoreDispatcher.setSurface(platform, surface)
    }

    fun videoWidth(): Int = if (isLoaded) CoreDispatcher.videoWidth(platform) else 320
    fun videoHeight(): Int = if (isLoaded) CoreDispatcher.videoHeight(platform) else 240

    fun setVideoFilter(filter: Int) = CoreDispatcher.setVideoFilter(platform, filter)
    fun setHighQualityScaling(enabled: Boolean) = CoreDispatcher.setHighQualityScaling(platform, enabled)
    fun setCoreOption(key: String, value: String) = CoreDispatcher.setCoreOption(platform, key, value)

    /**
     * 加载 ROM 并启动帧同步循环。
     * 连接成功后立即开始，无需等待服务器 start 信号。
     * 本方法不阻塞 UI 线程：ROM 加载在 emu 线程内完成。
     */
    fun start(listener: Listener? = null): Boolean = synchronized(engineLock) {
        this.listener = listener
        if (!CoreDispatcher.ensureLoaded(platform)) {
            return false
        }
        // 清理旧会话
        stopInternal()

        inputDelay = net.inputDelay.coerceIn(0, 8)
        running.set(true)

        emuThread = thread(name = "netplay-emu", isDaemon = true) {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_DISPLAY)

            // 1. 在 emu 线程加载 ROM（避免阻塞 UI）
            CoreDispatcher.setPaths(platform, systemDir, saveDir)
            CoreDispatcher.setSaveName(platform, "netplay_" + romFile.nameWithoutExtension)
            if (!CoreDispatcher.loadRom(platform, romFile.absolutePath)) {
                android.util.Log.e("NetplayEngine", "loadRom failed: ${CoreDispatcher.lastError(platform)}")
                running.set(false)
                listener?.onExit()
                return@thread
            }
            isLoaded = true

            // 2. 启动音频
            val rate = CoreDispatcher.audioTargetSampleRate(platform).takeIf { it > 0 } ?: 48000
            startAudio(rate)

            // 3. 帧同步循环（立即开始，不等待对方）
            var frame = 0L
            val startNs = System.nanoTime()
            var desync = 0
            try {
                while (running.get()) {
                    val t0 = System.nanoTime()

                    // 1. 采样本地输入，带当前帧号发送（对方 delay 帧后使用）
                    val localPad = currentLocalPad
                    localHistory[frame] = localPad
                    // ★ v4.3：BattleNetplay.sendInput 增加槽位参数（多玩家路由）。
                    //   本引擎为遗留路径（未被任何界面构造），槽位取本端槽位即可。
                    net.sendInput(frame, localPad, net.mySlot)

                    // 2. 执行帧 = 采样帧 - inputDelay（预热阶段不执行）
                    val execFrame = frame - inputDelay
                    if (execFrame >= 0) {
                        val localForExec = localHistory[execFrame] ?: localPad
                        val remoteForExec = waitForRemote(execFrame) { desync++ }
                        lastRemotePad = remoteForExec
                        CoreDispatcher.setPad1(platform, localForExec)
                        CoreDispatcher.setPad2(platform, remoteForExec)
                        CoreDispatcher.runFrame(platform)
                        listener?.onFrame(execFrame, inputDelay, desync)
                    }

                    frame++

                    // 清理过期的历史（保留最近 1024 帧）
                    if (frame % 256 == 0L) {
                        cleanupHistory(frame)
                    }

                    // 3. 60fps 节拍
                    val target = startNs + frame * frameNs
                    val wait = target - System.nanoTime()
                    if (wait > 0) {
                        try {
                            Thread.sleep(wait / 1_000_000, (wait % 1_000_000).toInt())
                        } catch (_: InterruptedException) {
                            break
                        }
                    }
                }
            } catch (t: Throwable) {
                android.util.Log.e("NetplayEngine", "Emu thread crashed", t)
            } finally {
                running.set(false)
                listener?.onExit()
            }
        }
        return true
    }

    /** 由 BattleNetplay 在收到 peer_joined 时更新状态 */
    fun onPeerJoined() {
        // 对方已加入，等待 start 信号
    }

    /**
     * 等待对方第 targetFrame 帧的输入。
     * 返回时已就绪的输入；若超时（网络抖动），回调 onMiss 并返回上一帧输入。
     */
    private fun waitForRemote(targetFrame: Long, onMiss: () -> Unit): Int {
        val deadline = System.currentTimeMillis() + 500 // 最多等 500ms
        while (running.get()) {
            remoteHistory[targetFrame]?.let { return it }
            if (System.currentTimeMillis() >= deadline) {
                if (!netplayLost) {
                    netplayLost = true
                    listener?.onNetplayLost()
                }
                onMiss()
                return lastRemotePad
            }
            try {
                Thread.sleep(2)
            } catch (_: InterruptedException) {
                return lastRemotePad
            }
        }
        return lastRemotePad
    }

    private fun cleanupHistory(frame: Long) {
        val cutoff = frame - 1024
        if (cutoff > 0) {
            localHistory.keys.removeAll { it < cutoff }
            remoteHistory.keys.removeAll { it < cutoff }
        }
    }

    // --- 音频（与 FbNeoEngine 相同的管道） ---

    private fun startAudio(sampleRate: Int) {
        stopAudio()
        try {
            val minBuf = AudioTrack.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_OUT_STEREO,
                AudioFormat.ENCODING_PCM_16BIT
            )
            val bufSize = (minBuf * 4).coerceAtLeast(8192)
            audioTrack = AudioTrack(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_GAME)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build(),
                AudioFormat.Builder()
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .build(),
                bufSize,
                AudioTrack.MODE_STREAM,
                AudioTrack.PERFORMANCE_MODE_LOW_LATENCY
            )
            audioTrack?.play()
        } catch (e: Exception) {
            audioTrack = null
        }

        audioRunning.set(true)
        audioThread = thread(name = "netplay-audio", isDaemon = true) {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_AUDIO)
            val buf = ShortArray(4096)
            try {
                while (audioRunning.get()) {
                    try {
                        val n = CoreDispatcher.readAudio(platform, buf)
                        if (n > 0) {
                            audioTrack?.write(buf, 0, n * 2, AudioTrack.WRITE_BLOCKING)
                        } else {
                            Thread.sleep(2)
                        }
                    } catch (_: InterruptedException) {
                        break
                    }
                }
            } catch (t: Throwable) {
                android.util.Log.e("NetplayEngine", "Audio thread crashed", t)
            }
        }
    }

    private fun stopAudio() {
        audioRunning.set(false)
        audioThread?.let {
            it.interrupt()
            try { it.join(200) } catch (_: InterruptedException) {}
        }
        audioThread = null
        audioTrack?.let {
            try { it.stop() } catch (_: Exception) {}
            try { it.release() } catch (_: Exception) {}
        }
        audioTrack = null
    }

    /** 接收对方输入的入口（由 BattleNetplay 回调） */
    fun onRemoteInput(frame: Long, pad: Int) {
        remoteHistory[frame] = pad
    }

    private fun stopInternal() {
        running.set(false)
        emuThread?.let { t ->
            t.interrupt()
            for (attempt in 0 until 6) {
                try { t.join(500) } catch (_: InterruptedException) { break }
                if (!t.isAlive) break
            }
        }
        emuThread = null
        stopAudio()
        if (isLoaded) {
            try { CoreDispatcher.unload(platform) } catch (_: Throwable) {}
            isLoaded = false
        }
        localHistory.clear()
        remoteHistory.clear()
        hasSurface = false
        netplayLost = false
    }

    companion object {
        /**
         * 核心分发器：根据平台选择对应的原生 JNI 类。
         * 所有对战平台的核心操作（加载 ROM、执行帧、音频读取等）
         * 都通过本对象分发，避免 NetplayEngine 硬编码绑定到某个核心。
         */
        private object CoreDispatcher {
            fun ensureLoaded(p: GamePlatform): Boolean = when (p) {
                GamePlatform.NES    -> NesNative.ensureLoaded()
                GamePlatform.SFC    -> SnesNative.ensureLoaded()
                GamePlatform.GB,
                GamePlatform.GBA    -> GbaNative.ensureLoaded()
                GamePlatform.DOS    -> DosNative.ensureLoaded()
                GamePlatform.ARCADE -> FbNeoNative.ensureLoaded()
                GamePlatform.MD     -> GenesisNative.ensureLoaded()
                GamePlatform.PCE    -> PceNative.ensureLoaded()
                else                -> NesNative.ensureLoaded()
            }

            fun setPaths(p: GamePlatform, systemDir: String, saveDir: String) {
                when (p) {
                    GamePlatform.NES    -> NesNative.setPaths(systemDir, saveDir)
                    GamePlatform.SFC    -> SnesNative.setPaths(systemDir, saveDir)
                    GamePlatform.GB,
                    GamePlatform.GBA    -> GbaNative.setPaths(systemDir, saveDir)
                    GamePlatform.DOS    -> DosNative.setPaths(systemDir, saveDir)
                    GamePlatform.ARCADE -> FbNeoNative.setPaths(systemDir, saveDir)
                    GamePlatform.MD     -> GenesisNative.setPaths(systemDir, saveDir)
                    GamePlatform.PCE    -> PceNative.setPaths(systemDir, saveDir)
                    else                -> NesNative.setPaths(systemDir, saveDir)
                }
            }

            fun setSaveName(p: GamePlatform, name: String) {
                when (p) {
                    GamePlatform.NES    -> NesNative.setSaveName(name)
                    GamePlatform.SFC    -> SnesNative.setSaveName(name)
                    GamePlatform.GB,
                    GamePlatform.GBA    -> GbaNative.setSaveName(name)
                    GamePlatform.DOS    -> DosNative.setSaveName(name)
                    GamePlatform.ARCADE -> FbNeoNative.setSaveName(name)
                    GamePlatform.MD     -> GenesisNative.setSaveName(name)
                    GamePlatform.PCE    -> PceNative.setSaveName(name)
                    else                -> NesNative.setSaveName(name)
                }
            }

            fun loadRom(p: GamePlatform, path: String): Boolean = when (p) {
                GamePlatform.NES    -> NesNative.loadRom(path)
                GamePlatform.SFC    -> SnesNative.loadRom(path)
                GamePlatform.GB,
                GamePlatform.GBA    -> GbaNative.loadRom(path)
                GamePlatform.DOS    -> DosNative.loadRom(path)
                GamePlatform.ARCADE -> FbNeoNative.loadRom(path)
                GamePlatform.MD     -> GenesisNative.loadRom(path)
                GamePlatform.PCE    -> PceNative.loadRom(path)
                else                -> NesNative.loadRom(path)
            }

            fun lastError(p: GamePlatform): String = when (p) {
                GamePlatform.NES    -> NesNative.lastError()
                GamePlatform.SFC    -> SnesNative.lastError()
                GamePlatform.GB,
                GamePlatform.GBA    -> GbaNative.lastError()
                GamePlatform.DOS    -> DosNative.lastError()
                GamePlatform.ARCADE -> FbNeoNative.lastError()
                GamePlatform.MD     -> GenesisNative.lastError()
                GamePlatform.PCE    -> PceNative.lastError()
                else                -> NesNative.lastError()
            }

            // Default audio — always the core's own sample rate (no TV special-casing)
            fun audioTargetSampleRate(p: GamePlatform): Int = when (p) {
                GamePlatform.NES    -> NesNative.audioSampleRate()
                GamePlatform.SFC    -> SnesNative.audioSampleRate()
                GamePlatform.GB,
                GamePlatform.GBA    -> GbaNative.audioSampleRate()
                GamePlatform.DOS    -> DosNative.audioSampleRate()
                GamePlatform.ARCADE -> FbNeoNative.audioSampleRate()
                GamePlatform.MD     -> GenesisNative.audioSampleRate()
                GamePlatform.PCE    -> PceNative.audioSampleRate()
                else                -> NesNative.audioSampleRate()
            }

            fun setPad1(p: GamePlatform, bits: Int) {
                when (p) {
                    GamePlatform.NES    -> NesNative.setPad1(bits)
                    GamePlatform.SFC    -> SnesNative.setPad1(bits)
                    GamePlatform.GB,
                    GamePlatform.GBA    -> GbaNative.setPad1(bits)
                    GamePlatform.DOS    -> DosNative.setPad1(bits)
                    GamePlatform.ARCADE -> FbNeoNative.setPad1(bits)
                    GamePlatform.MD     -> GenesisNative.setPad1(bits)
                    GamePlatform.PCE    -> PceNative.setPad1(bits)
                    else                -> NesNative.setPad1(bits)
                }
            }

            fun setPad2(p: GamePlatform, bits: Int) {
                when (p) {
                    GamePlatform.NES    -> NesNative.setPad2(bits)
                    GamePlatform.SFC    -> SnesNative.setPad2(bits)
                    GamePlatform.GB,
                    GamePlatform.GBA    -> GbaNative.setPad2(bits)
                    GamePlatform.DOS    -> DosNative.setPad2(bits)
                    GamePlatform.ARCADE -> FbNeoNative.setPad2(bits)
                    GamePlatform.MD     -> GenesisNative.setPad2(bits)
                    GamePlatform.PCE    -> PceNative.setPad2(bits)
                    else                -> NesNative.setPad2(bits)
                }
            }

            fun runFrame(p: GamePlatform) {
                when (p) {
                    GamePlatform.NES    -> NesNative.runFrame()
                    GamePlatform.SFC    -> SnesNative.runFrame()
                    GamePlatform.GB,
                    GamePlatform.GBA    -> GbaNative.runFrame()
                    GamePlatform.DOS    -> DosNative.runFrame()
                    GamePlatform.ARCADE -> FbNeoNative.runFrame()
                    GamePlatform.MD     -> GenesisNative.runFrame()
                    GamePlatform.PCE    -> PceNative.runFrame()
                    else                -> NesNative.runFrame()
                }
            }

            fun readAudio(p: GamePlatform, buf: ShortArray): Int = when (p) {
                GamePlatform.NES    -> NesNative.readAudio(buf)
                GamePlatform.SFC    -> SnesNative.readAudio(buf)
                GamePlatform.GB,
                GamePlatform.GBA    -> GbaNative.readAudio(buf)
                GamePlatform.DOS    -> DosNative.readAudio(buf)
                GamePlatform.ARCADE -> FbNeoNative.readAudio(buf)
                GamePlatform.MD     -> GenesisNative.readAudio(buf)
                GamePlatform.PCE    -> PceNative.readAudio(buf)
                else                -> NesNative.readAudio(buf)
            }

            fun unload(p: GamePlatform) {
                when (p) {
                    GamePlatform.NES    -> NesNative.unload()
                    GamePlatform.SFC    -> SnesNative.unload()
                    GamePlatform.GB,
                    GamePlatform.GBA    -> GbaNative.unload()
                    GamePlatform.DOS    -> DosNative.unload()
                    GamePlatform.ARCADE -> FbNeoNative.unload()
                    GamePlatform.MD     -> GenesisNative.unload()
                    GamePlatform.PCE    -> PceNative.unload()
                    else                -> NesNative.unload()
                }
            }

            fun setSurface(p: GamePlatform, surface: Surface?) {
                when (p) {
                    GamePlatform.NES    -> NesNative.setSurface(surface)
                    GamePlatform.SFC    -> SnesNative.setSurface(surface)
                    GamePlatform.GB,
                    GamePlatform.GBA    -> GbaNative.setSurface(surface)
                    GamePlatform.DOS    -> DosNative.setSurface(surface)
                    GamePlatform.ARCADE -> FbNeoNative.setSurface(surface)
                    GamePlatform.MD     -> GenesisNative.setSurface(surface)
                    GamePlatform.PCE    -> PceNative.setSurface(surface)
                    else                -> NesNative.setSurface(surface)
                }
            }

            fun videoWidth(p: GamePlatform): Int = when (p) {
                GamePlatform.NES    -> NesNative.videoWidth()
                GamePlatform.SFC    -> SnesNative.videoWidth()
                GamePlatform.GB,
                GamePlatform.GBA    -> GbaNative.videoWidth()
                GamePlatform.DOS    -> DosNative.videoWidth()
                GamePlatform.ARCADE -> FbNeoNative.videoWidth()
                GamePlatform.MD     -> GenesisNative.videoWidth()
                GamePlatform.PCE    -> PceNative.videoWidth()
                else                -> NesNative.videoWidth()
            }

            fun videoHeight(p: GamePlatform): Int = when (p) {
                GamePlatform.NES    -> NesNative.videoHeight()
                GamePlatform.SFC    -> SnesNative.videoHeight()
                GamePlatform.GB,
                GamePlatform.GBA    -> GbaNative.videoHeight()
                GamePlatform.DOS    -> DosNative.videoHeight()
                GamePlatform.ARCADE -> FbNeoNative.videoHeight()
                GamePlatform.MD     -> GenesisNative.videoHeight()
                GamePlatform.PCE    -> PceNative.videoHeight()
                else                -> NesNative.videoHeight()
            }

            fun setVideoFilter(p: GamePlatform, filter: Int) {
                when (p) {
                    GamePlatform.NES    -> NesNative.setVideoFilter(filter)
                    GamePlatform.SFC    -> SnesNative.setVideoFilter(filter)
                    GamePlatform.GB,
                    GamePlatform.GBA    -> GbaNative.setVideoFilter(filter)
                    GamePlatform.DOS    -> DosNative.setVideoFilter(filter)
                    GamePlatform.ARCADE -> FbNeoNative.setVideoFilter(filter)
                    GamePlatform.MD     -> GenesisNative.setVideoFilter(filter)
                    GamePlatform.PCE    -> PceNative.setVideoFilter(filter)
                    else                -> NesNative.setVideoFilter(filter)
                }
            }

            fun setHighQualityScaling(p: GamePlatform, enabled: Boolean) {
                when (p) {
                    GamePlatform.NES    -> NesNative.setHighQualityScaling(enabled)
                    GamePlatform.SFC    -> SnesNative.setHighQualityScaling(enabled)
                    GamePlatform.GB,
                    GamePlatform.GBA    -> GbaNative.setHighQualityScaling(enabled)
                    GamePlatform.DOS    -> DosNative.setHighQualityScaling(enabled)
                    GamePlatform.ARCADE -> FbNeoNative.setHighQualityScaling(enabled)
                    GamePlatform.MD     -> GenesisNative.setHighQualityScaling(enabled)
                    GamePlatform.PCE    -> PceNative.setHighQualityScaling(enabled)
                    else                -> NesNative.setHighQualityScaling(enabled)
                }
            }

            fun setCoreOption(p: GamePlatform, key: String, value: String) {
                when (p) {
                    GamePlatform.NES    -> NesNative.setCoreOption(key, value)
                    GamePlatform.SFC    -> SnesNative.setCoreOption(key, value)
                    GamePlatform.GB,
                    GamePlatform.GBA    -> GbaNative.setCoreOption(key, value)
                    GamePlatform.DOS    -> DosNative.setCoreOption(key, value)
                    GamePlatform.ARCADE -> FbNeoNative.setCoreOption(key, value)
                    GamePlatform.MD     -> GenesisNative.setCoreOption(key, value)
                    GamePlatform.PCE    -> PceNative.setCoreOption(key, value)
                    else                -> NesNative.setCoreOption(key, value)
                }
            }
        }
    }

    fun stop() = synchronized(engineLock) {
        stopInternal()
    }

    fun unload() = stop()
}
