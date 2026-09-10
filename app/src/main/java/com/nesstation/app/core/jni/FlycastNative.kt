package com.nesstation.app.core.jni

import android.view.Surface

/**
 * JNI surface to libflycastcore.so (Flycast — Sega Dreamcast / Naomi /
 * Atomiswave core).
 *
 * Pull-model interface — Kotlin owns the emulation loop and pulls audio on
 * demand. Rendering is PUSHED by the core: flycast draws through the
 * frontend-provided OpenGL ES 3 framebuffer (libretro hardware-rendering
 * protocol). The native side creates the EGL context on the emulation
 * thread, hands the core an RGBA8+D24S8 FBO, composites the result into the
 * [Surface] after every frame and swaps. The prebuilt
 * libflycast_libretro_android.so is dlopen()'d at runtime and forwarded
 * retro_* calls.
 *
 * Dreamcast controller (4 maple ports, standard pad):
 *   屏幕 A → libretro JOYPAD_B(bit0)  → DC A
 *   屏幕 B → libretro JOYPAD_A(bit8)  → DC B
 *   屏幕 X → libretro JOYPAD_Y(bit1)  → DC X
 *   屏幕 Y → libretro JOYPAD_X(bit9)  → DC Y
 *   Start  → JOYPAD_START(bit3)
 *   L / R  → JOYPAD_L2 / R2 (bit12/13) → 模拟扳机 (全扣)
 *   十字键 → JOYPAD_UP/DOWN/LEFT/RIGHT (bit4-7)
 *   摇杆   → RETRO_DEVICE_ANALOG LEFT X/Y（经 [setAnalogAxis] 直接推送，
 *            与数字十字键相互独立 —— flycast 对 DC 摇杆必须走模拟轴）
 *   （JOYPAD_L/R = DC 的 C/Z 按键，bit10/11 —— 供 Na.o.m.i 街机键位使用）
 *
 * ## BIOS / 系统文件（核心在 <systemDir>/dc/ 下查找）
 *   dc_boot.bin    — Dreamcast 启动 ROM（必需）
 *   dc_flash.bin   — Dreamcast 闪存 ROM（必需，含语言/区域/时钟设置）
 *   naomi.zip      — Naomi 街机 BIOS（MAME romset，Naomi 游戏必需）
 *   atomiswave.zip — Atomiswave 街机 BIOS（MAME romset）
 * 核心会把 VMU/闪存写入 <systemDir>/dc/data/，把每游戏存档写到
 * <saveDir>/reicast/；这些目录由核心自动创建。
 *
 * ## 光盘镜像
 * .chd / .cdi / .gdi / .cue+.bin / .iso / .m3u（多碟列表）以及 .zip / .7z /
 * .lst（Naomi / Atomiswave MAME romset）都以路径传给核心 —— 核心自行打开
 * 并解析 TOC / 引用轨道 / zip 成员，与 PS1 的 CD 处理一致。
 */
object FlycastNative {

    @Volatile private var loaded = false

    fun ensureLoaded(): Boolean {
        if (loaded) return true
        loaded = try {
            System.loadLibrary("flycastcore")
            try {
                val coreLibPath = findCoreLibPath()
                if (coreLibPath != null) setCoreLibPath(coreLibPath)
            } catch (_: Throwable) { /* best-effort */ }
            true
        } catch (e: UnsatisfiedLinkError) {
            android.util.Log.e("FlycastNative", "Failed to load libflycastcore.so", e)
            false
        } catch (e: SecurityException) {
            false
        }
        return loaded
    }

    /**
     * Find the absolute path to `libflycast_libretro_android.so` in
     * the app's native library directory. Returns null if not found.
     */
    private fun findCoreLibPath(): String? {
        return try {
            val ctx = appContext ?: return null
            val nativeDir = ctx.applicationInfo.nativeLibraryDir
            val libFile = java.io.File(nativeDir, "libflycast_libretro_android.so")
            if (libFile.exists()) libFile.absolutePath else null
        } catch (_: Throwable) { null }
    }

    /** Set the absolute path to libflycast_libretro_android.so for dlopen. */
    @JvmStatic external fun setCoreLibPath(path: String)

    /** App context — set by NesApp.onCreate so FlycastNative can locate the lib. */
    @Volatile var appContext: android.content.Context? = null

    @JvmStatic external fun loadRom(path: String): Boolean
    @JvmStatic external fun unload()
    @JvmStatic external fun reset(hard: Boolean)
    @JvmStatic external fun runFrame()

    /** 标准手按键（port 0）。位布局见类注释（屏幕标签 → libretro 映射）。 */
    @JvmStatic external fun setPad1(bits: Int)
    /** 第二个手柄（port 1，双人）。位布局同 [setPad1]。 */
    @JvmStatic external fun setPad2(bits: Int)
    /** 第三个手柄（port 2）。位布局同 setPad1。 */
    @JvmStatic external fun setPad3(bits: Int)
    /** 第四个手柄（port 3）。位布局同 setPad1。 */
    @JvmStatic external fun setPad4(bits: Int)

    /**
     * 推送模拟摇杆轴值（port 0..3）。
     * @param axis 0=LX 1=LY 2=RX 3=RY（libretro 约定：右/下为正，
     *             与屏幕坐标一致，无需翻转）
     * @param value int16 范围 -32768..32767
     */
    @JvmStatic external fun setAnalogAxis(port: Int, axis: Int, value: Short)

    @JvmStatic external fun setRegion(region: Int)
    @JvmStatic external fun setSampleRate(rate: Int)
    @JvmStatic external fun setFastForward(speed: Int)

    /**
     * 切换 maple 端口设备类型。
     * @param port 0..3
     * @param device RETRO_DEVICE_JOYPAD (1) / RETRO_DEVICE_KEYBOARD (3) /
     *        RETRO_DEVICE_LIGHTGUN (10) 等 —— 常规游戏保持 JOYPAD。
     *        队列化，模拟线程下一帧前生效，可随时从 UI 线程调用。
     */
    @JvmStatic external fun setControllerDevice(port: Int, device: Int)

    /** 核心上报的刷新率（59.826 NTSC / 50.0 PAL）。 */
    @JvmStatic external fun videoFps(): Double

    /**
     * 读取并清零自上次轮询以来核心真实提交（FBO 帧回调）的帧数。
     * FPS HUD 每秒轮询一次，换算成真实帧率。
     */
    @JvmStatic external fun pollPresentedFrames(): Int

    @JvmStatic external fun saveState(slot: Int, path: String): Boolean
    @JvmStatic external fun loadState(slot: Int, path: String): Boolean

    /** CPU 侧帧缓冲读取（无 Surface 兜底 / 截图）。先调 [requestFrameReadback]。 */
    @JvmStatic external fun getFrameBuffer(out: IntArray): Boolean
    /** 请求模拟线程在下一帧后 glReadPixels 刷新 CPU 帧缓冲。 */
    @JvmStatic external fun requestFrameReadback()
    @JvmStatic external fun readAudio(out: ShortArray): Int
    @JvmStatic external fun audioSampleRate(): Int
    @JvmStatic external fun audioTargetSampleRate(): Int
    @JvmStatic external fun setPaths(systemDir: String, saveDir: String)
    @JvmStatic external fun setSaveName(name: String)
    @JvmStatic external fun lastError(): String

    /** 挂接/摘除渲染 Surface —— EGL 窗口在模拟线程按需创建。 */
    @JvmStatic external fun setSurface(surface: Surface?)

    /**
     * Set a core option by key and value.
     * Keys MUST match flycast's libretro_core_options.h（前缀 reicast，
     * 已对照 buildbot 预编译核心与上游源码校验）：
     *   "reicast_region"                -> "Japan"|"USA"|"Europe"|"Default"
     *   "reicast_language"              -> "Japanese".."Italian"|"Default"
     *   "reicast_hle_bios"              -> "disabled"|"enabled"
     *   "reicast_enable_dsp"            -> "disabled"|"enabled"
     *   "reicast_internal_resolution"   -> "320x240".."2560x1920"(UI 上限)
     *   "reicast_alpha_sorting"         -> "per-strip (fast, least accurate)"
     *                                      |"per-triangle (normal)"
     *                                      |"per-pixel (accurate)"
     *   "reicast_anisotropic_filtering" -> "off"|"2"|"4"|"8"|"16"
     *   "reicast_texture_filtering"     -> "0"|"1"|"2"
     *   "reicast_threaded_rendering"    -> "disabled"|"enabled"
     *   "reicast_auto_skip_frame"       -> "disabled"|"some"|"more"
     *   "reicast_frame_skipping"        -> "disabled"|"1".."6"
     *   "reicast_gdrom_fast_loading"    -> "disabled"|"enabled"
     *   "reicast_widescreen_hack"       -> "disabled"|"enabled"
     *   "reicast_emulate_framebuffer"   -> "disabled"|"enabled"
     *   "reicast_sh4clock"              -> "100".."330" (MHz)
     *   "reicast_broadcast"             -> "NTSC"|"PAL"|"PAL_N"|"PAL_M"|"Default"
     *   "reicast_cable_type"            -> "VGA"|"TV (RGB)"|"TV (Composite)"
     *   "reicast_device_port1_slot1"    -> "VMU"|"Purupuru"|"DreamPotato"|"None"
     *   "reicast_per_content_vmus"      -> "disabled"|"VMU A1"|"All VMUs"
     *   "reicast_allow_service_buttons" -> "disabled"|"enabled" (Naomi/Atomiswave)
     *   "reicast_force_freeplay"        -> "disabled"|"enabled" (街机投币)
     *   ... 完整列表见上游 shell/libretro/libretro_core_options.h
     */
    @JvmStatic external fun setCoreOption(key: String, value: String)

    @JvmStatic external fun videoWidth(): Int
    @JvmStatic external fun videoHeight(): Int

    /** 前端滤镜接口 —— GPU 渲染路径下为 no-op（保持与其他引擎接口一致）。 */
    @JvmStatic external fun setVideoFilter(filter: Int)

    /** 接口一致性保留 —— GPU 路径下无 CPU blit，可忽略。 */
    @JvmStatic external fun setHighQualityScaling(enabled: Boolean)

    /** Check whether libflycast_libretro_android.so was successfully dlopen()'d. */
    @JvmStatic external fun isCoreLibLoaded(): Boolean

    /** 核心是否已注册硬件渲染上下文（SET_HW_RENDER 接受成功）。 */
    @JvmStatic external fun isHwRenderAvailable(): Boolean
}
