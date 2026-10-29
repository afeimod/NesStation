package org.libsdl.app

/**
 * ★★ v55 afbn 引擎桥（参考 APK 的 org.libsdl.app.SDLActivity 精简移植）。
 *
 * libarcade.so / libSDL2.so（参考 APK afbn 的核心）的 JNI 静态符号按「声明类
 * 全限定名」绑定 —— 本类的包名/类名必须与核心编译时的绑定类一致
 * （org.libsdl.app.SDLActivity），与真正的 "Activity" 概念无关（本应用不使用
 * 参考 APK 的 Activity 界面，仅提供核心回调所需的最小静态面）。
 *
 * 核心回调协议（反编译参考 APK + 二进制核实）：
 *  - 路径：getRomsPath()/getDataPath()/getRomDataPath()/getCachePath()/
 *          getSelectedArchiveName()/getSelectedLaunchArchiveName()/
 *          getEmuCoreName()/getOpenBorDriverName() —— 返回下方静态字段
 *          （AfbnEngine 启动前设置）；
 *  - 渲染：核心 SDL "android" 视频驱动经 getNativeSurface() 取
 *          ANativeWindow 直接 EGL 直绘（推模型，与 Pgm3Engine 同架构）；
 *  - 音频：核心音频线程回调 audioOpen/audioWriteByteBuffer/audioWriteShortBuffer/
 *          audioClose（AudioTrack 在本类内管理 —— 与参考实现同语义）；
 *  - 进度条：showProgressBar/hideProgressBar/setProgressBar（启动大 ROM 时核心
 *          回调；本应用无全局进度对话框，记日志空实现，绝不崩溃）。
 *
 * JNI 惰性解析：Java_ 符号在方法首次调用时才 dlsym —— 只声明实际用到的
 * 子集即可（核心另有 nativeCheat/nativeLua/nativeDip 等入口，本桥不调用，
 * 不声明也不影响加载）。
 */
class SDLActivity {

    companion object {
        // ===== 核心回调读取的静态路径/游戏字段（AfbnEngine.loadRom 前赋值）=====
        // 参考 APK 语义（反编译核实）：rompath = ROM 目录；datapath = 数据根；
        // statespath = 存档目录；rom = romset 名；romDriverName = 驱动名
        //（nativeInitWithArgs 入参同款）；selectedArchiveName = 启动 zip 名（可空）；
        // selectedStateName = 启动存档（空）；romSystemName/romBoardName = 输入
        // 配置分类用（取驱动名即可）。
        @JvmStatic var rompath: String = ""
        @JvmStatic var datapath: String = ""
        @JvmStatic var romdatapath: String = ""
        @JvmStatic var statespath: String = ""
        @JvmStatic var cachepath: String = ""
        @JvmStatic var rom: String = ""
        @JvmStatic var romDriverName: String = ""
        @JvmStatic var selectedArchiveName: String? = null
        @JvmStatic var selectedStateName: String? = null
        @JvmStatic var parentRomName: String? = null
        @JvmStatic var romSystemName: String? = null
        @JvmStatic var romBoardName: String? = null
        @JvmStatic var openBorDriverName: String? = null
        @JvmStatic var tempExtractDir: String? = null
        @JvmStatic var vertical: Boolean = false
        @JvmStatic var buttonCount: Int = 4
        @JvmStatic var mScreenEmuSizeX: Int = 320
        @JvmStatic var mScreenEmuSizeY: Int = 240

        /** 渲染目标 Surface（AfbnEngine.setSurface 注入；核心 EGL 直绘）。 */
        @JvmStatic var nativeSurface: android.view.Surface? = null

        /** AudioTrack（核心音频线程回调 audioOpen/audioWrite 驱动）。 */
        @JvmStatic var mAudioTrack: android.media.AudioTrack? = null

        // ===== 供本类内部 / AfbnEngine 使用的状态 =====
        @JvmStatic var mIsSurfaceReady: Boolean = false
        @JvmStatic var mIsPaused: Boolean = false
        @JvmStatic var mHasFocus: Boolean = true

        // ===== 路径/元数据 getter（核心 JNI 方法 ID 缓存表逐个回调）=====
        @JvmStatic fun getRomsPath(): String = rompath
        @JvmStatic fun getDataPath(): String = datapath
        @JvmStatic fun getRomDataPath(): String = romdatapath
        @JvmStatic fun getCachePath(): String = cachepath
        @JvmStatic fun getSelectedArchiveName(): String? =
            selectedStateName?.takeIf { it.trim().isNotEmpty() } ?: selectedArchiveName
        @JvmStatic fun getSelectedLaunchArchiveName(): String? = selectedArchiveName
        @JvmStatic fun getEmuCoreName(): String = romSystemName ?: romDriverName
        @JvmStatic fun getOpenBorDriverName(): String? = openBorDriverName

        // ===== Surface：核心 android 视频驱动经此取 ANativeWindow =====
        @JvmStatic fun getNativeSurface(): android.view.Surface? = nativeSurface

        // ===== 音频（参考实现同语义的 AudioTrack 管理）=====
        /**
         * 核心音频线程打开音频设备时回调（参考 APK SDLActivity.audioOpen 同语义）。
         * @param sampleRate 采样率（如 44100）
         * @param is16Bit true=16bit(SHORT) false=8bit(BYTE)
         * @param stereo true=双声道
         * @param desiredFrames 期望帧数（SDL 缓冲语义）
         * @return 0=成功 / -1=失败
         */
        @JvmStatic fun audioOpen(sampleRate: Int, is16Bit: Boolean, stereo: Boolean, desiredFrames: Int): Int {
            try {
                audioClose()
                val channelConfig = if (stereo)
                    android.media.AudioFormat.CHANNEL_OUT_STEREO
                else android.media.AudioFormat.CHANNEL_OUT_MONO
                val encoding = if (is16Bit)
                    android.media.AudioFormat.ENCODING_PCM_16BIT
                else android.media.AudioFormat.ENCODING_PCM_8BIT
                val bytesPerSample = if (is16Bit) 2 else 1
                val channels = if (stereo) 2 else 1
                val bytesPerFrame = bytesPerSample * channels
                val minBuf = android.media.AudioTrack.getMinBufferSize(sampleRate, channelConfig, encoding)
                if (minBuf <= 0) return -1
                var frameCount = if (desiredFrames > 0) desiredFrames else (sampleRate / 60)
                val bufFrames = (minBuf + bytesPerFrame - 1) / bytesPerFrame
                if (frameCount < bufFrames) frameCount = bufFrames
                val track = android.media.AudioTrack(
                    android.media.AudioAttributes.Builder()
                        .setUsage(android.media.AudioAttributes.USAGE_GAME)
                        .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build(),
                    android.media.AudioFormat.Builder()
                        .setSampleRate(sampleRate)
                        .setEncoding(encoding)
                        .setChannelMask(channelConfig)
                        .build(),
                    frameCount * bytesPerFrame,
                    android.media.AudioTrack.MODE_STREAM,
                    android.media.AudioManager.AUDIO_SESSION_ID_GENERATE
                )
                if (track.state != android.media.AudioTrack.STATE_INITIALIZED) {
                    track.release()
                    return -1
                }
                track.play()
                mAudioTrack = track
                return 0
            } catch (t: Throwable) {
                android.util.Log.w("SDLActivity", "audioOpen: ${t.message}")
                return -1
            }
        }

        /** 核心音频线程阻塞写（short 缓冲 —— 16bit 模式）。 */
        @JvmStatic fun audioWriteShortBuffer(buffer: ShortArray) {
            val track = mAudioTrack ?: return
            try {
                var off = 0
                while (off < buffer.size) {
                    val n = track.write(buffer, off, buffer.size - off)
                    if (n > 0) off += n
                }
            } catch (_: Throwable) {}
        }

        /** 核心音频线程阻塞写（byte 缓冲 —— 8bit 模式）。 */
        @JvmStatic fun audioWriteByteBuffer(buffer: ByteArray) {
            val track = mAudioTrack ?: return
            try {
                var off = 0
                while (off < buffer.size) {
                    val n = track.write(buffer, off, buffer.size - off)
                    if (n > 0) off += n
                }
            } catch (_: Throwable) {}
        }

        /** 关闭音频设备（释放 AudioTrack）。 */
        @JvmStatic fun audioClose() {
            try {
                mAudioTrack?.let { it.stop(); it.release() }
            } catch (_: Throwable) {}
            mAudioTrack = null
        }

        // ===== 进度条回调（核心加载大 ROM 时回调；本应用无全局对话框 —— 空实现）=====
        // 反编译核实签名：showProgressBar(String message, int max) / hideProgressBar() /
        // setProgressBar(String message, int pct)。
        @JvmStatic fun showProgressBar(message: String?, max: Int) { /* 引擎自有加载浮层 */ }
        @JvmStatic fun hideProgressBar() { }
        @JvmStatic fun setProgressBar(message: String?, pct: Int) { }

        // ===== 生命周期 native（AfbnEngine 调用；实现在 libSDL2.so / libarcade.so）=====
        /** 启动模拟主循环（SDLMain 线程调用）：args = [驱动名]。核心返回后模拟结束。 */
        @JvmStatic external fun nativeInitWithArgs(args: Array<String>)

        /** 应用完整性（反篡改）：核心信任调用方提供的摘要+包名（二进制核实 ——
         *  与嵌入常量比对通过才置内部许可位）。AfbnEngine 传入参考 APK 的
         *  官方签名摘要 + 包名，与原应用行为完全一致。 */
        @JvmStatic external fun nativeSetAppIntegrity(digest: ByteArray, packageName: String)

        /** 生命周期：surface 变更 / 销毁通知（核心 android 视频驱动重取 Surface）。 */
        @JvmStatic external fun onNativeSurfaceChanged()
        @JvmStatic external fun onNativeSurfaceDestroyed()
        @JvmStatic external fun onNativeResize(width: Int, height: Int, format: Int)
        @JvmStatic external fun nativePause()
        @JvmStatic external fun nativeResume()
        @JvmStatic external fun nativeQuit()
        @JvmStatic external fun nativeLowMemory()
        @JvmStatic external fun nativeGetHint(name: String): String?

        /** 输入（libarcade 核心；位布局见 AfbnEngine.toAfbnPad）。 */
        @JvmStatic external fun setPadData(player: Int, bits: Long)

        /** 停止模拟并卸载（引擎 unload 调用）。 */
        @JvmStatic external fun emustop()
        @JvmStatic external fun ispaused(): Int
        @JvmStatic external fun getslotnum(): Int

        /** 当前模拟帧号（FPS 计数用）。 */
        @JvmStatic external fun nativeGetEmulatedFrameCount(): Int

        /** 快进开关（追帧/快进按钮）。 */
        @JvmStatic external fun nativeSetFastForward(on: Boolean)

        /** 旧版帧跳（与 nativeSetFastForward 配合）。 */
        @JvmStatic external fun setfskip(skip: Int)

        /** 软复位（内部触发核心级 reset 请求）。 */
        @JvmStatic external fun nativeRequestSoftReset(): Boolean

        /** 捕获当前帧（ARGB int[]；核心内部帧缓冲 —— 与 GL 直绘独立）。 */
        @JvmStatic external fun nativeCaptureFrameArgb(): IntArray?
    }
}
