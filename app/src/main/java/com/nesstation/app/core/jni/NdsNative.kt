package com.nesstation.app.core.jni

import android.view.Surface

/**
 * JNI surface to libndscore.so (melonDS — Nintendo DS / DSi core).
 *
 * Pull-model interface — Kotlin owns the emulation loop and pulls frames /
 * audio on demand. The melonDS libretro wrapper is compiled directly into
 * the binary via CMake — no dlopen/dlsym needed.
 *
 * DS uses the standard 12-button libretro gamepad layout (same bit layout
 * as SNES):
 *   bit0  = A (Right face button — Nintendo layout: B is left, A is right)
 *   bit1  = B (Left face button)
 *   bit2  = Select
 *   bit3  = Start
 *   bit4  = Up    bit5  = Down    bit6  = Left    bit7  = Right
 *   bit8  = X (top face button — upper of the 4)
 *   bit9  = Y (left face button — left of the 4)
 *   bit10 = L (left shoulder)
 *   bit11 = R (right shoulder)
 *
 * NOTE: On a real DS, A/B/X/Y are arranged in a diamond (Y top, X left,
 * A right, B bottom) — similar to SNES. The libretro port maps these to
 * the standard SNES bit layout, so the same keymap used for SNES works here.
 *
 * DS also has a touchscreen (bottom screen). Touch input is exposed via
 * [setTouchInput] — pass signed coordinates (-0x8000..0x7FFF) and a pressed
 * flag. The native bridge forwards these to the core via RETRO_DEVICE_POINTER.
 *
 * [setTouchInputDirect] is the PREFERRED input path: it takes bottom-screen
 * PIXEL coordinates (x: 0..255, y: 0..191) exactly like the official melonDS
 * Android frontend. The core applies them verbatim, bypassing the fragile
 * composite-frame coordinate round-trip — so touch keeps working with the
 * custom free-form layout, screen gaps, the GL renderer's 2-pixel gap rows
 * and every non-hybrid screen layout. Use [setTouchInput] only for Hybrid
 * layouts, whose on-screen geometry is only known to the core.
 *
 * ## BIOS files
 * melonDS uses FreeBIOS (built-in BIOS replacement) whenever the real
 * files are absent from the system directory (set via [setPaths]):
 *   bios7.bin      — ARM7 BIOS (loaded if present)
 *   bios9.bin      — ARM9 BIOS (loaded if present)
 *   firmware.bin   — DS firmware (loaded if present)
 *   dsi_arm7.bin   — (DSi only) ARM7 binary
 *   dsi_bios7.bin  — (DSi only) ARM7 BIOS
 *   dsi_bios9.bin  — (DSi only) ARM9 BIOS
 *   dsi_firmware.bin — (DSi only) DSi firmware
 *   dsi_nand.bin   — (DSi only) DSi NAND image
 *
 * The core option "melonds_console_mode" = "DS" (default) uses the NDS
 * BIOS set; "DSi" requires the DSi files.
 * These BIOS files have copyright and cannot be bundled with the app.
 *
 * ## ROM files
 * DS ROMs come as .nds (cartridge dump), .app (DSiWare), or .ids (some
 * ROM hacks). All are loaded into memory (max 512 MB). melonDS does NOT
 * support .zip / .7z archives — extract the ROM first.
 */
object NdsNative {

    @Volatile private var loaded = false

    fun ensureLoaded(): Boolean {
        if (loaded) return true
        loaded = try {
            System.loadLibrary("ndscore")
            true
        } catch (e: UnsatisfiedLinkError) {
            android.util.Log.e("NdsNative", "Failed to load libndscore.so", e)
            false
        } catch (e: SecurityException) {
            false
        }
        return loaded
    }

    /** App context — set by NesApp.onCreate so NdsNative can locate the lib. */
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
     * Touchscreen input via RETRO_DEVICE_POINTER.
     * @param x Signed X coordinate (-0x8000..0x7FFF, maps to 0..255 by the core).
     * @param y Signed Y coordinate (-0x8000..0x7FFF, maps to 0..191 by the core).
     * @param pressed true = touching the screen, false = released.
     */
    @JvmStatic external fun setTouchInput(x: Int, y: Int, pressed: Boolean)

    /**
     * Touchscreen input with DIRECT bottom-screen pixel coordinates
     * (official melonDS Android frontend architecture).
     * @param x Bottom-screen pixel X (0..255).
     * @param y Bottom-screen pixel Y (0..191).
     * @param pressed true = touching the screen, false = released.
     */
    @JvmStatic external fun setTouchInputDirect(x: Int, y: Int, pressed: Boolean)

    @JvmStatic external fun setRegion(region: Int)
    @JvmStatic external fun setSampleRate(rate: Int)
    @JvmStatic external fun setFastForward(speed: Int)

    @JvmStatic external fun saveState(slot: Int, path: String): Boolean
    @JvmStatic external fun loadState(slot: Int, path: String): Boolean

    @JvmStatic external fun getFrameBuffer(out: IntArray): Boolean

    /**
     * Frame the custom dual-screen view should present: the upscaled
     * (HQ2X/HQ4X/XBR) composite frame when an upscale filter is active and
     * no surface is attached (videoScale == "custom"), the raw frame
     * otherwise. Pair with [filteredVideoWidth] / [filteredVideoHeight].
     */
    @JvmStatic external fun getFilteredFrameBuffer(out: IntArray): Boolean

    /** Width of the frame returned by [getFilteredFrameBuffer]. */
    @JvmStatic external fun filteredVideoWidth(): Int

    /** Height of the frame returned by [getFilteredFrameBuffer]. */
    @JvmStatic external fun filteredVideoHeight(): Int

    /**
     * Monotonic counter of frames delivered by the core. The dual-screen view
     * polls this from its Choreographer callback and only redraws when it
     * changes — eliminates redundant draws on 90/120 Hz displays.
     */
    @JvmStatic external fun frameStamp(): Long
    @JvmStatic external fun readAudio(out: ShortArray): Int
    @JvmStatic external fun audioSampleRate(): Int
    @JvmStatic external fun audioTargetSampleRate(): Int
    @JvmStatic external fun setPaths(systemDir: String, saveDir: String)
    @JvmStatic external fun setSaveName(name: String)
    @JvmStatic external fun lastError(): String

    @JvmStatic external fun setSurface(surface: Surface?)

    /**
     * Set a core option by key and value.
     * Keys/values MUST match the prebuilt melonDS libretro core (v1.1).
     * Valid options (from libretro_core_options.h):
     *   "melonds_boot_directly"          -> "enabled" | "disabled"
     *   "melonds_console_mode"           -> "DS" | "DSi"
     *   "melonds_screen_layout"          -> "Top/Bottom" | "Bottom/Top" | "Left/Right" | "Right/Left" | "Top Only" | "Bottom Only" | "Hybrid Top" | "Hybrid Bottom"
     *   "melonds_use_fw_settings"        -> "enabled" | "disabled"
     *   "melonds_touch_mode"             -> "Mouse" | "Touch" | "Joystick" | "disabled"
     *   "melonds_opengl_renderer"        -> "enabled" | "disabled" (OpenGL 3D renderer, requires HW context)
     *   "melonds_opengl_resolution"      -> "1x native (256x192)" .. "8x native (2048x1536)"
     *   "melonds_opengl_better_polygons" -> "enabled" | "disabled"
     *   "melonds_opengl_filtering"       -> "nearest" | "linear"
     *   "melonds_threaded_renderer"      -> "enabled" | "disabled"
     *   "melonds_dsi_sdcard"             -> "disabled" | "enabled"
     *   "melonds_randomize_mac_address"  -> "disabled" | "enabled"
     *   "melonds_jit_enable"             -> "enabled" | "disabled"
     *   "melonds_audio_interpolation"    -> "None" | "Linear" | "Cosine" | "Cubic"
     * NOTE: "melonds_use_fw_bios" / "melonds_screensaver" /
     * "melonds_mouse_speed" / "melonds_sysfile_directory" do NOT exist in the core
     * and are silently ignored.
     */
    @JvmStatic external fun setCoreOption(key: String, value: String)

    @JvmStatic external fun videoWidth(): Int
    @JvmStatic external fun videoHeight(): Int

    /** Video filter types (same as other cores): 0=none, 1=scanline, 2=crt, 3=dot, ... */
    @JvmStatic external fun setVideoFilter(filter: Int)

    /** Control native surface buffer geometry. false=fast, true=sharp. */
    @JvmStatic external fun setHighQualityScaling(enabled: Boolean)

    /** Check whether the melonDS libretro wrapper is available (always true when statically linked). */
    @JvmStatic external fun isCoreLibLoaded(): Boolean

    /**
     * 对一帧 w×h 的 0xAARRGGBB int 像素（[src]）执行与 melonDS surface
     * 路径相同的 CPU 放大滤镜（[filter]），把输出（2x 或 4x）以 RGBA8888
     * 字节序写入直接缓冲 [dst]，返回写入的字节数。
     *
     * 供 DraSticGlView 在激烈核心 GL 显示路径实现全局放大滤镜使用：
     * 激烈核心是预编译 .so，renderFrame 只会原样上传帧池，放大滤镜由
     * Kotlin 侧取帧后经本函数处理再自行上传纹理。
     *
     * filter 取值：4=xbr(2x) 5=hq2x(2x) 7=xbr+dot(2x) 6=hq4x(4x)
     * 8=4xbr(4x) 9=4xbr+dot(4x) 10=hq4x+dot(4x)；其余返回 0。
     * [dst] 容量须 ≥ w*h*(2x→16 / 4x→64) 字节（256×192 源 4x 时最大
     * 1024×768×4 = 3MB）。
     */
    @JvmStatic external fun applyUpscaleFilter(
        filter: Int, src: IntArray, w: Int, h: Int, dst: java.nio.ByteBuffer
    ): Int

    /**
     * [applyUpscaleFilter] 的画布路径变体：滤镜输出保持 0xAARRGGBB int
     * 格式，直接写入 [dst] 的 [dstOffset] 像素偏移处，返回写入的像素数。
     * 供 DraSticEngine 渲染线程在画布回退路径合成放大后的 frameBuffer。
     * filter 取值与失败语义同上。
     */
    @JvmStatic external fun applyUpscaleFilterArgb(
        filter: Int, src: IntArray, w: Int, h: Int, dst: IntArray, dstOffset: Int
    ): Int

    /**
     * DraStic（激烈）帧一致性读取（libndscore 对预编译 libdrastic*.so 的补充
     * 通道，见 core/jni/drastic_frames.cpp）：
     *
     * 拉取【刚完成的帧】（原生 renderFrame 同款语义：读帧池"另一档"缓冲，
     * 而不是原生 getScreenBuffers 的"当前写入档"）—— 修复画布路径读取写入中
     * 缓冲导致的撕裂 / 多线程 3D 渲染时"上下屏部分贴图错乱"。
     *
     * 同时返回帧池的【真实分辨率】（高清渲染 bit41 开启时为 512×384/屏，
     * 而原生 getScreenBuffers 在 HD 下只返回 2:1 抽取降采样的 256×192），
     * 画布路径由此完整呈现 HD 帧的分辨率增益。
     *
     * @param top    上屏输出（需 ≥ w*h int；调用方按 512×384 上限分配）
     * @param bottom 下屏输出（同上）
     * @param outDims 输出 {topW, topH, bottomW, bottomH}
     * @return false = 校验失败（核心未启动 / 16 位渲染 / 档位异常 /
     *         一致性校验未通过），调用方应回落原生 getScreenBuffers。
     */
    @JvmStatic external fun drasticGetCompletedFrames(
        top: IntArray, bottom: IntArray, outDims: IntArray
    ): Boolean
}
