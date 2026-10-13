package com.nesstation.app.core.jni

import android.view.Surface

/**
 * JNI surface to libpspcore.so (libretro PPSSPP — Sony PSP core).
 *
 * Pull-model interface — Kotlin owns the emulation loop and pulls frames /
 * audio on demand. The native side dlopen()s the prebuilt
 * libppsspp_libretro_android.so at runtime and forwards retro_* calls —
 * the exact same integration pattern as DcNative / PsxNative.
 *
 * ★★ 集成架构说明（v3 — libretro 化重写）★★
 * 旧版（dlopen 官方 libppsspp_jni.so + PspHostStub 桩 + JNI 契约驱动）已
 * 整体废弃：那套方案与 DC/PSX 等核心的 libretro 模式完全异构，导致
 * PPSSPP 自带虚拟按键覆盖层、画面方向不受宿主管理、设置走 ppsspp.ini
 * 割裂、以及 JNI 契约连环崩溃。新版与 flycast/pcsx-rearmed 完全同构，
 * 统一虚拟按键 / 画面 / 设置全链路。
 *
 * PSP uses the standard 12-button libretro gamepad layout with PlayStation
 * label semantics (after the Kotlin-side ps2ToLibretroLayout() conversion —
 * the same conversion PS2 uses; the ppsspp core maps JOYPAD_B→✕ Cross,
 * JOYPAD_A→○ Circle, JOYPAD_Y→□ Square, JOYPAD_X→△ Triangle):
 *   bit0  = ✕ Cross (ppsspp: JOYPAD_B)
 *   bit1  = □ Square (ppsspp: JOYPAD_Y)
 *   bit2  = Select
 *   bit3  = Start
 *   bit4..7 = Up / Down / Left / Right
 *   bit8  = ○ Circle (ppsspp: JOYPAD_A)
 *   bit9  = △ Triangle (ppsspp: JOYPAD_X)
 *   bit10 = L
 *   bit11 = R
 *
 * ## System / save directories
 * The core follows the standard libretro mechanism:
 *   * system directory → the core derives <systemDir>/PPSSPP/ as its asset
 *     base (ppge_atlas.zim — in-game dialog atlas, compat.ini — per-game
 *     compat flags, flash0/font 目录的 .pgf 字体 — PSP system fonts, lang/ — system
 *     dialog strings). NesStation seeds it from assets "ppsspp/" on first
 *     run (PspSupport.ensurePpssppEnvironment).
 *   * save directory → the memstick root; the core creates
 *     PSP/{SAVEDATA,SYSTEM,GAME,...} inside (standard PPSSPP tree).
 * No BIOS is required for PSP content.
 *
 * ## Game images
 * .iso / .cso / .pbp / .elf / .chd are passed by path — the core streams
 * them itself (PBP/CSO random access; Disc ID derives per-game saves).
 *
 * ## Rendering
 * The core is a hardware-rendering core: the native side creates an EGL/GLES3
 * context, lets the core render into an FBO, then blits + swaps on the
 * ANativeWindow each frame (see psp_loader.cpp).
 */
object PspNative {

    @Volatile private var loaded = false

    fun ensureLoaded(): Boolean {
        if (loaded) return true
        loaded = try {
            System.loadLibrary("pspcore")
            try {
                val coreLibPath = findCoreLibPath()
                if (coreLibPath != null) setCoreLibPath(coreLibPath)
            } catch (_: Throwable) { /* best-effort */ }
            true
        } catch (e: UnsatisfiedLinkError) {
            android.util.Log.e("PspNative", "Failed to load libpspcore.so", e)
            false
        } catch (e: SecurityException) {
            false
        }
        return loaded
    }

    /**
     * Find the absolute path to the ppsspp libretro core library in the
     * app's native library directory. The canonical name is
     * `libppsspp_libretro_android.so` (same `lib` prefix convention as every
     * other dlopen'd core); the un-prefixed spelling is probed as fallback.
     * Returns null if not found.
     */
    private fun findCoreLibPath(): String? {
        return try {
            val ctx = appContext ?: return null
            val nativeDir = ctx.applicationInfo.nativeLibraryDir
            for (name in arrayOf(
                "libppsspp_libretro_android.so",   // canonical (lib* convention)
                "ppsspp_libretro_android.so"       // legacy un-prefixed name
            )) {
                val f = java.io.File(nativeDir, name)
                if (f.exists()) return f.absolutePath
            }
            null
        } catch (_: Throwable) { null }
    }

    /** Set the absolute path to libppsspp_libretro_android.so for dlopen. */
    @JvmStatic external fun setCoreLibPath(path: String)

    /** App context — set by NesApp.onCreate so PspNative can locate the lib. */
    @Volatile var appContext: android.content.Context? = null

    @JvmStatic external fun loadRom(path: String): Boolean
    @JvmStatic external fun unload()
    @JvmStatic external fun reset(hard: Boolean)
    @JvmStatic external fun runFrame()

    /**
     * 暂停时由模拟线程 tick 调用：取出挂起的存/读档操作（不跑帧）。
     *
     * ★ 即时存档线程约定（v5 修复“即时存档卡死”）：retro_serialize /
     * retro_unserialize 会与 PPSSPP 核心内部模拟线程握手（EmuThreadPause），
     * 绝不能在 UI 线程与 retro_run 并发调用 —— 会永久死锁。存/读档请求由
     * [saveState]/[loadState] 投递到原生队列，运行时在 stepFrame() 开头
     * （retro_run 之前）取件执行；暂停时本方法兑底取件。必须在拥有 EGL
     * 上下文的模拟线程（pspcore-loop）上调用。
     */
    @JvmStatic external fun drainStateOp()

    /** Standard libretro gamepad (port 0). See class doc for bit layout. */
    @JvmStatic external fun setPad1(bits: Int)
    /** Second controller (port 1). Same bit layout as [setPad1]. */
    @JvmStatic external fun setPad2(bits: Int)
    /** Third controller (port 2). Same bit layout as setPad1. */
    @JvmStatic external fun setPad3(bits: Int)
    /** Fourth controller (port 3). Same bit layout as setPad1. */
    @JvmStatic external fun setPad4(bits: Int)

    /**
     * Analog stick state for a controller port — libretro int16 axes
     * (−32768..32767, 0 = center), order LX/LY/RX/RY. PSP 只有左摇杆：
     * ppsspp 每帧按 RETRO_DEVICE_INDEX_ANALOG_LEFT + ID_ANALOG_X/Y 轮询。
     * 供 OnScreenController 的虚拟摇杆与物理手柄轴推送
     * （PspEngine.setAnalogAxes 包装端口 0）。
     */
    @JvmStatic external fun setAnalogAxes(port: Int, lx: Int, ly: Int, rx: Int, ry: Int)

    @JvmStatic external fun setRegion(region: Int)
    @JvmStatic external fun setSampleRate(rate: Int)
    @JvmStatic external fun setFastForward(speed: Int)

    /**
     * Switch a controller port device (JOYPAD = 1 / ANALOG = 5).
     * Queued natively; applied on the emulation thread before the next
     * frame — safe to call from the UI thread at any time.
     */
    @JvmStatic external fun setControllerDevice(port: Int, device: Int)

    /** Core-reported refresh rate in Hz (59.94 NTSC). */
    @JvmStatic external fun videoFps(): Double

    /**
     * 读取并清零自上次轮询以来核心真实提交（视频回调非空）的帧数。
     * FPS HUD 每秒轮询一次，换算成真实帧率。
     */
    @JvmStatic external fun pollPresentedFrames(): Int

    /**
     * 即时存档（同步）：投递到模拟线程、在两帧之间执行 retro_serialize，
     * 阻塞等结果（典型 0.1~1s，超时 8s 返回 false）。与 RetroArch 的
     * PPSSPP 槽位兼容。
     */
    @JvmStatic external fun saveState(slot: Int, path: String): Boolean
    /** 即时读档（同步）：投递到模拟线程执行 retro_unserialize。 */
    @JvmStatic external fun loadState(slot: Int, path: String): Boolean

    @JvmStatic external fun getFrameBuffer(out: IntArray): Boolean

    /**
     * Screenshot: schedule a fresh glReadPixels from the core's FBO on the
     * emulation thread, wait briefly, then copy into `out` (w*h ARGB ints).
     */
    @JvmStatic external fun captureFrame(out: IntArray): Boolean

    @JvmStatic external fun readAudio(out: ShortArray): Int
    @JvmStatic external fun audioSampleRate(): Int
    @JvmStatic external fun audioTargetSampleRate(): Int
    @JvmStatic external fun setPaths(systemDir: String, saveDir: String)
    @JvmStatic external fun setSaveName(name: String)
    @JvmStatic external fun lastError(): String

    @JvmStatic external fun setSurface(surface: Surface?)

    /**
     * Set a core option by key and value.
     * ppsspp_* keys (verified against the core's libretro.cpp RetroOption
     * table; wrong keys are silently ignored):
     *   "ppsspp_internal_resolution"   -> "480x272" | "960x544" | "1440x816" | "1920x1088" | ...
     *   "ppsspp_rendering_mode"        -> "buffered" | "nonbuffered"
     *   "ppsspp_cpu_core"              -> "jit" | "IR jit" | "interpreter"
     *   "ppsspp_language"              -> "automatic" | "english" | "chinese_simplified" | ...
     *   "ppsspp_fast_memory"           -> "enabled" | "disabled"
     *   "ppsspp_frameskip"             -> "0".."10"
     *   "ppsspp_auto_frameskip"        -> "enabled" | "disabled"
     *   "ppsspp_io_timing_method"      -> "Fast" | "Host" | "Simulate UMD delays"
     *   "ppsspp_audio_latency"         -> "low" | "medium" | "high"
     *   "ppsspp_button_preference"     -> "cross" | "circle"
     *   "ppsspp_texture_scaling_level" -> "0".."5" (0 = off)
     *   "ppsspp_texture_scaling_type"  -> "xbrz" | "hybrid" | "bicubic" | "hybrid_bicubic"
     *   "ppsspp_texture_filtering"     -> "auto" | "nearest" | "linear" | "linear(FMV)"
     *   "ppsspp_cheats"                -> "enabled" | "disabled"
     */
    @JvmStatic external fun setCoreOption(key: String, value: String)

    @JvmStatic external fun videoWidth(): Int
    @JvmStatic external fun videoHeight(): Int

    /** Video filter types (same as other cores): 0=none, 1=scanline, 2=crt, 3=dot, ... */
    @JvmStatic external fun setVideoFilter(filter: Int)

    /** Control native surface buffer geometry. false=fast, true=sharp. */
    @JvmStatic external fun setHighQualityScaling(enabled: Boolean)

    /** Check whether libppsspp_libretro_android.so was successfully dlopen()'d. */
    @JvmStatic external fun isCoreLibLoaded(): Boolean
}
