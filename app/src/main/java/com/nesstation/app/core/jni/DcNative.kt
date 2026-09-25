package com.nesstation.app.core.jni

import android.view.Surface

/**
 * JNI surface to libdccore.so (libretro Flycast — SEGA Dreamcast / NAOMI /
 * AtomisWave core).
 *
 * Pull-model interface — Kotlin owns the emulation loop and pulls frames /
 * audio on demand. The native side dlopen()s the prebuilt
 * libflycast_libretro_android.so at runtime and forwards retro_* calls —
 * the exact same integration pattern as PsxNative / FbNeoNative.
 *
 * DC uses the standard 12-button libretro gamepad layout (same bit layout
 * as SNES after the Kotlin-side dcToLibretroLayout() conversion):
 *   bit0  = A (flycast: DC_BTN_A — the big bottom-right button)
 *   bit1  = X (flycast: DC_BTN_X)
 *   bit2  = Select ("D" button — rarely used)
 *   bit3  = Start
 *   bit4..7 = Up / Down / Left / Right
 *   bit8  = B (flycast: DC_BTN_B)
 *   bit9  = Y (flycast: DC_BTN_Y)
 *   bit10 = L (analog trigger, digital value)
 *   bit11 = R (analog trigger, digital value)
 *
 * ## BIOS / system files
 * The core follows the RetroArch convention: everything lives in a `dc/`
 * subfolder of the system directory passed via [setPaths]. NesStation passes
 * the root filesDir, so the files live in <filesDir>/dc/:
 *   dc_boot.bin  — Dreamcast BIOS ROM (required for disc games)
 *   dc_flash.bin — Dreamcast flash (required for disc games)
 *   naomi.zip / naomi2.zip — NAOMI BIOS sets (required for NAOMI zips)
 *   awbios.zip   — AtomisWave BIOS set
 *   f355bios.zip / f355dlx.zip / hod2bios.zip / airlbios.zip — per-system
 *                  NAOMI BIOS variants
 * These files are seeded from assets/dc/ on app startup (NesApp.ensureDcBios).
 *
 * ## Game images
 * Disc images (.gdi/.cdi/.cue/.chd/.iso/.lst) and NAOMI/AtomisWave archives
 * (.zip) are passed by path — the core opens them itself (multi-file images
 * reference sibling track files, and archive naming drives game detection).
 *
 * ## Rendering
 * Flycast is a hardware-rendering core: the native side creates an EGL/GLES3
 * context, lets the core render into an FBO, then blits + swaps on the
 * ANativeWindow each frame (see dc_loader.cpp).
 */
object DcNative {

    @Volatile private var loaded = false

    fun ensureLoaded(): Boolean {
        if (loaded) return true
        loaded = try {
            System.loadLibrary("dccore")
            try {
                val coreLibPath = findCoreLibPath()
                if (coreLibPath != null) setCoreLibPath(coreLibPath)
            } catch (_: Throwable) { /* best-effort */ }
            true
        } catch (e: UnsatisfiedLinkError) {
            android.util.Log.e("DcNative", "Failed to load libdccore.so", e)
            false
        } catch (e: SecurityException) {
            false
        }
        return loaded
    }

    /**
     * Find the absolute path to the flycast core library in the app's native
     * library directory. The canonical name is `libflycast_libretro_android.so`
     * (same `lib` prefix convention as every other dlopen'd core); older
     * builds shipped it un-prefixed (`flycast_libretro_android.so`), so both
     * spellings are probed for robustness. Returns null if not found.
     */
    private fun findCoreLibPath(): String? {
        return try {
            val ctx = appContext ?: return null
            val nativeDir = ctx.applicationInfo.nativeLibraryDir
            for (name in arrayOf(
                "libflycast_libretro_android.so",   // canonical (lib* convention)
                "flycast_libretro_android.so"       // legacy un-prefixed name
            )) {
                val f = java.io.File(nativeDir, name)
                if (f.exists()) return f.absolutePath
            }
            null
        } catch (_: Throwable) { null }
    }

    /** Set the absolute path to libflycast_libretro_android.so for dlopen. */
    @JvmStatic external fun setCoreLibPath(path: String)

    /** App context — set by NesApp.onCreate so DcNative can locate the lib. */
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
     * Analog stick state for a controller port — libretro int16 axes
     * (−32768..32767, 0 = center), order LX/LY/RX/RY. DC 手柄的摇杆是模拟轴
     * （绝大多数 DC 游戏靠它移动）：flycast 每帧按
     * RETRO_DEVICE_INDEX_ANALOG_LEFT/RIGHT + ID_ANALOG_X/Y 轮询（libretro.cpp
     * joyx/joyy ← LEFT X/Y）。供 OnScreenController 的虚拟摇杆与物理手柄
     * 轴推送（DcEngine.setAnalogAxes 包装端口 0）。
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

    /** Core-reported refresh rate in Hz (59.94 NTSC / 50.0 PAL). */
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
     * Common Flycast keys (reicast_* — verified against the shipped
     * libflycast_libretro_android.so; wrong keys are silently ignored):
     *   "reicast_internal_resolution"  -> "640x480 (Native)" | "1280x960 (x2)" | ...
     *   "reicast_alpha_sorting"        -> "per-strip (fast, least accurate)"
     *                                   | "per-triangle (normal)"
     *                                   | "per-pixel (accurate)"
     *   "reicast_threaded_rendering"   -> "enabled" | "disabled"
     *   "reicast_delay_frame_swapping" -> "enabled" | "disabled"
     *   "reicast_frame_skipping"       -> "enabled" | "disabled"
     *   "reicast_widescreen_hack"      -> "enabled" | "disabled"
     *   "reicast_widescreen_cheats"    -> "enabled" | "disabled"
     *   "reicast_gdrom_fast_loading"   -> "enabled" | "disabled"
     *   "reicast_hle_bios"             -> "disabled" | "enabled"
     *   "reicast_dc_32mb_mod"          -> "disabled" | "enabled"
     *   "reicast_force_wince"          -> "disabled" | "enabled"
     *   "reicast_enable_dsp"           -> "disabled" | "enabled"
     *   "reicast_region"               -> "Default" | "Japan" | "USA" | "Europe"
     *   "reicast_language"             -> "Default" | "Japanese" | "English" | ...
     *   "reicast_broadcast"            -> "Default" | "NTSC" | "PAL" | "PAL-M" | "PAL-N"
     *   "reicast_cable_type"           -> "VGA" | "TV (Composite)"
     */
    @JvmStatic external fun setCoreOption(key: String, value: String)

    @JvmStatic external fun videoWidth(): Int
    @JvmStatic external fun videoHeight(): Int

    /** Video filter types (same as other cores): 0=none, 1=scanline, 2=crt, 3=dot, ... */
    @JvmStatic external fun setVideoFilter(filter: Int)

    /** Control native surface buffer geometry. false=fast, true=sharp. */
    @JvmStatic external fun setHighQualityScaling(enabled: Boolean)

    /** Check whether libflycast_libretro_android.so was successfully dlopen()'d. */
    @JvmStatic external fun isCoreLibLoaded(): Boolean
}
