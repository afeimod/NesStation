package com.nesstation.app.core.jni

import android.view.Surface

/**
 * JNI surface to libpsxcore.so (PCSX-ReARMed — Sony PlayStation 1 core).
 *
 * Pull-model interface — Kotlin owns the emulation loop and pulls frames /
 * audio on demand. The native side dlopen()s the prebuilt
 * libpcsx_rearmed_libretro_android.so at runtime and forwards retro_* calls.
 *
 * PSX uses the standard 12-button libretro gamepad layout (same bit
 * layout as SNES):
 *   bit0  = □ (Square)    bit1  = ✕ (Cross)    bit2  = Select
 *   bit3  = Start         bit4  = Up    bit5  = Down
 *   bit6  = Left           bit7  = Right
 *   bit8  = △ (Triangle)   bit9  = ○ (Circle)
 *   bit10 = L1 / L2 (left shoulder)   bit11 = R1 / R2 (right shoulder)
 *
 * For 2D games (most PSX games) only D-pad + □✕△○ + L/R + Start/Select are used.
 * For analog games use the analog pad type (set via core option
 * "pcsx_rearmed_pad1type" = "analog").
 *
 * ## BIOS files
 * PCSX-ReARMed can run without a BIOS (HLE BIOS built-in), but for full
 * compatibility a real BIOS is recommended. Place one of these in the
 * system directory (set via [setPaths]):
 *   scph1000.bin  — Japanese BIOS (for JP region games)
 *   scph1001.bin  — American BIOS (for US region games)
 *   scph1002.bin  — European BIOS (for EU region games)
 *   scph5500.bin / scph5501.bin / scph5502.bin — newer variants
 *   psxonpsp660.bin — PSP-derived BIOS (no copyright issues in some regions)
 *
 * The core option "pcsx_rearmed_bios" = "auto" auto-detects region.
 * These BIOS files have copyright and cannot be bundled with the app.
 *
 * ## CD images
 * PSX games come as CD images: .cue + .bin pairs, .chd, .pbp (PSP eboot),
 * .m3u (playlist), .ecm (compressed). The loader passes the file path
 * directly to the core (no in-memory pre-load) so the core can open the
 * CD image and parse the TOC itself.
 */
object PsxNative {

    @Volatile private var loaded = false

    fun ensureLoaded(): Boolean {
        if (loaded) return true
        loaded = try {
            System.loadLibrary("psxcore")
            try {
                val coreLibPath = findCoreLibPath()
                if (coreLibPath != null) setCoreLibPath(coreLibPath)
            } catch (_: Throwable) { /* best-effort */ }
            true
        } catch (e: UnsatisfiedLinkError) {
            android.util.Log.e("PsxNative", "Failed to load libpsxcore.so", e)
            false
        } catch (e: SecurityException) {
            false
        }
        return loaded
    }

    /**
     * Find the absolute path to `libpcsx_rearmed_libretro_android.so` in
     * the app's native library directory. Returns null if not found.
     */
    private fun findCoreLibPath(): String? {
        return try {
            val ctx = appContext ?: return null
            val nativeDir = ctx.applicationInfo.nativeLibraryDir
            val libFile = java.io.File(nativeDir, "libpcsx_rearmed_libretro_android.so")
            if (libFile.exists()) libFile.absolutePath else null
        } catch (_: Throwable) { null }
    }

    /** Set the absolute path to libpcsx_rearmed_libretro_android.so for dlopen. */
    @JvmStatic external fun setCoreLibPath(path: String)

    /** App context — set by NesApp.onCreate so PsxNative can locate the lib. */
    @Volatile var appContext: android.content.Context? = null

    @JvmStatic external fun loadRom(path: String): Boolean
    @JvmStatic external fun unload()
    @JvmStatic external fun reset(hard: Boolean)
    @JvmStatic external fun runFrame()

    /** Standard libretro gamepad (port 0). See class doc for bit layout. */
    @JvmStatic external fun setPad1(bits: Int)
    /** Second controller (port 1, player 2). Same bit layout as [setPad1]. */
    @JvmStatic external fun setPad2(bits: Int)
    /** Third controller (port 2). Same bit layout as setPad1. */
    @JvmStatic external fun setPad3(bits: Int)
    /** Fourth controller (port 3). Same bit layout as setPad1. */
    @JvmStatic external fun setPad4(bits: Int)

    /**
     * ★★ v60 DualShock 模拟摇杆轴（1P / port 0）。int16 每轴（-32768..32767）。
     * cb_input_state 的 RETRO_DEVICE_ANALOG 查询读取 —— 虚拟摇杆 / 实体手柄轴 /
     * 按键映射的摇杆方向三路输入都汇到这。配合 setControllerDevice(0, 5)
     * （「手柄类型 = DualShock 模拟」）使用。中性位 = 全 0。
     */
    @JvmStatic external fun setAnalog1(lx: Int, ly: Int, rx: Int, ry: Int)

    @JvmStatic external fun setRegion(region: Int)
    @JvmStatic external fun setSampleRate(rate: Int)
    @JvmStatic external fun setFastForward(speed: Int)

    /**
     * Switch a controller port between digital and DualShock (analog).
     * @param port 0..3
     * @param device RETRO_DEVICE_JOYPAD (1) or RETRO_DEVICE_ANALOG (5).
     *        Queued natively; applied on the emulation thread before the next
     *        frame — safe to call from the UI thread at any time.
     */
    @JvmStatic external fun setControllerDevice(port: Int, device: Int)

    /** Core-reported refresh rate in Hz (59.82614 NTSC / 50.0 PAL). */
    @JvmStatic external fun videoFps(): Double

    /**
     * 读取并清零自上次轮询以来核心真实提交（视频回调非空）的帧数。
     * FPS HUD 每秒轮询一次，换算成真实帧率 —— 游戏内部掉帧时数值
     * 会低于步进频率，不再是永远被帧率限制器凑出来的 60。
     */
    @JvmStatic external fun pollPresentedFrames(): Int

    @JvmStatic external fun saveState(slot: Int, path: String): Boolean
    @JvmStatic external fun loadState(slot: Int, path: String): Boolean

    @JvmStatic external fun getFrameBuffer(out: IntArray): Boolean
    @JvmStatic external fun readAudio(out: ShortArray): Int
    @JvmStatic external fun audioSampleRate(): Int
    @JvmStatic external fun audioTargetSampleRate(): Int
    @JvmStatic external fun setPaths(systemDir: String, saveDir: String)
    @JvmStatic external fun setSaveName(name: String)
    @JvmStatic external fun lastError(): String

    @JvmStatic external fun setSurface(surface: Surface?)

    /**
     * Set a core option by key and value.
     * Common PCSX-ReARMed keys (must match libretro_core_options.h —
     * verified against the shipped libpcsx_rearmed_libretro_android.so):
     *   "pcsx_rearmed_bios"                -> "auto" | "HLE" | "scph1000" | ...
     *   "pcsx_rearmed_region"              -> "auto" | "ntsc" | "pal"
     *   "pcsx_rearmed_frameskip_type"      -> "disabled" | "auto" | "auto_threshold" | "fixed_interval"
     *   "pcsx_rearmed_frameskip_threshold" -> "15".."80"  (auto_threshold mode)
     *   "pcsx_rearmed_frameskip_interval"  -> "1".."10"   (fixed_interval mode)
     *   "pcsx_rearmed_drc"                 -> "enabled" | "disabled"  (dynarec)
     *   "pcsx_rearmed_drc_thread"          -> "auto" | "disabled" | "enabled"
     *   "pcsx_rearmed_psxclock"            -> "auto" | "30".."100"
     *   "pcsx_rearmed_gpu_thread_rendering"-> "auto" | "disabled" | "enabled"
     *   "pcsx_rearmed_vibration"           -> "enabled" | "disabled"
     *   "pcsx_rearmed_dithering"           -> "enabled" | "disabled"
     *   "pcsx_rearmed_spu_interpolation"   -> "simple" | "gaussian" | "cubic" | "off"
     *   "pcsx_rearmed_spu_reverb"          -> "enabled" | "disabled"
     *   "pcsx_rearmed_nocdaudio"           -> "enabled"(play) | "disabled"(mute)  [inverted]
     *   "pcsx_rearmed_noxadecoding"        -> "enabled"(play) | "disabled"(skip)  [inverted]
     *   "pcsx_rearmed_rgb32_output"        -> "disabled" | "enabled"
     *   "pcsx_rearmed_memcard1/2"          -> "libretro" | "serial" | "shared" | "none"
     * Controller types are NOT core options — use [setControllerDevice].
     */
    @JvmStatic external fun setCoreOption(key: String, value: String)

    @JvmStatic external fun videoWidth(): Int
    @JvmStatic external fun videoHeight(): Int

    /** Video filter types (same as other cores): 0=none, 1=scanline, 2=crt, 3=dot, ... */
    @JvmStatic external fun setVideoFilter(filter: Int)

    /** Control native surface buffer geometry. false=fast, true=sharp. */
    @JvmStatic external fun setHighQualityScaling(enabled: Boolean)

    /**
     * ★ 分辨率倍数（前端整数放大，1..4）—— 参照 ARMSX upscale_multiplier 的
     * 前端等价实现：把核心输出按整数倍最近邻放大后上屏，窗口 buffer 变为
     * N× 原生尺寸、系统合成器只做轻微缩放 —— 像素锐利不再"糊成一片"。
     * 运行中可随时热切换（原子写，下一帧生效），无需重进游戏；对 2D/CG/
     * FMV/高分辨率模式全部生效（上游 NEON 2x 增强仅 3D 且需重进游戏）。
     * 与 XBR/HQX 像素滤镜、高质量缩放路径互斥（两者自带放大，自动跳过）。
     */
    @JvmStatic external fun setResolutionScale(scale: Int)

    /** Check whether libpcsx_rearmed_libretro_android.so was successfully dlopen()'d. */
    @JvmStatic external fun isCoreLibLoaded(): Boolean
}
