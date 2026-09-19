package com.nesstation.app.core.jni

import android.view.Surface
import kr.co.iefriends.pcsx2.NativeApp
import java.io.File
import kotlin.concurrent.thread

/**
 * JNI surface to the ARMSX2 emucore (`libemucore_4k.so` — PCSX2 fork with
 * ARM64 JIT via vixl), compiled from source in this repo.
 *
 * This is a **forwarding layer**: GameBox's high-level engine (Psx2Engine)
 * and UI keep using the same method names/signatures as the previous
 * PCEE2/libretro build, but every call now forwards to the ARMSX2
 * `kr.co.iefriends.pcsx2.NativeApp` SDK. The core model is push-based:
 *   - the VM runs on its own thread (started by [loadRom] → `runVMThread`)
 *   - rendering goes straight to the Surface passed via [setSurface]
 *   - audio is played internally via Oboe (no [readAudio] pull anymore)
 *   - input is pushed per-button via Android keycodes (see [PAD_KEYS])
 *   - settings are pushed as PCSX2 ini keys, see [setCoreOption]
 *
 * PS2 16-button DualShock layout (consistent with previous versions):
 *   bit0  = × (Cross)     bit1  = □ (Square)   bit2  = Select
 *   bit3  = Start         bit4  = Up    bit5  = Down
 *   bit6  = Left          bit7  = Right
 *   bit8  = ○ (Circle)    bit9  = △ (Triangle)
 *   bit10 = L1            bit11 = R1
 *   bit12 = L2            bit13 = R2
 *   bit14 = L3            bit15 = R3
 *
 * Analog sticks (setAnalog1/2) take int16 values (-32768..32767) for
 * LX/LY/RX/RY and are mapped to ARMSX2's stick keycodes with magnitude
 * in `range` (110..113 = L-stick U/R/D/L, 120..123 = R-stick).
 *
 * ## BIOS files
 * ARMSX2 requires a real PS2 BIOS (scph10000.bin / scph39001.bin etc),
 * placed in `<systemDir>/pcsx2/bios/` (systemDir is passed to [setPaths]
 * and forwarded to NativeApp.initialize as the bios folder).
 *
 * ## Disc images
 * .iso / .chd / .cso / .zso / .cue+bin / .gz / .mdf / .nrg / .elf — the
 * path is passed straight to `runVMThread`.
 */
object Psx2Native {

    @Volatile private var loaded = false
    @Volatile private var vmThread: Thread? = null
    @Volatile private var lastSystemDir: String? = null

    /**
     * True once NativeApp.initialize() has run. The native side only creates
     * the settings layer (Host::LAYER_BASE) inside initialize() — every
     * render/setting JNI (renderVulkan, setSetting, ...) dereferences that
     * layer and would segfault if called before it exists. EmulatorScreen's
     * core-options LaunchedEffect fires during first composition, BEFORE
     * loadRom()/initialize() (NesApp already preloads the .so), so calls made
     * while this is false are queued and replayed once initialize() has run.
     */
    @Volatile private var initialized = false
    private val pendingOptions = java.util.concurrent.ConcurrentLinkedQueue<Pair<String, String>>()

    /**
     * GameBox 16-bit layout (bit0=Cross .. bit15=R3) → ARMSX2 Android
     * KeyEvent keycodes accepted by NativeApp.setPadButtonForPort.
     */
    private val PAD_KEYS = intArrayOf(
        96,  // bit0  × Cross
        99,  // bit1  □ Square
        109, // bit2  Select
        108, // bit3  Start
        19,  // bit4  Up
        20,  // bit5  Down
        21,  // bit6  Left
        22,  // bit7  Right
        97,  // bit8  ○ Circle
        100, // bit9  △ Triangle
        102, // bit10 L1
        103, // bit11 R1
        104, // bit12 L2
        105, // bit13 R2
        106, // bit14 L3
        107, // bit15 R3
    )

    fun ensureLoaded(): Boolean {
        if (loaded) return true
        loaded = try {
            NativeApp.ensureLoaded()
        } catch (t: Throwable) {
            android.util.Log.e("Psx2Native", "Failed to load ARMSX2 emucore", t)
            false
        }
        return loaded
    }

    /**
     * Boot the game on the ARMSX2 VM thread. Blocks that background thread
     * until VM exit (runVMThread is blocking) — returns immediately here.
     */
    @JvmStatic fun loadRom(path: String): Boolean {
        if (!ensureLoaded()) return false
        // 给上一个 VM 充分的退出时间（换游戏场景）：大游戏收尾可能数秒。
        stopVmThread(8000)
        // ★ 关闭大游戏闪退修复：上一个 VM 未完全退出时绝不能启动新 VM。
        // NativeApp.shutdown() 对重游戏可能超过 5 秒等待窗口（大内存池释放、
        // 存档/记录刷盘、卡在长 JIT 块），此时上一个 VM 线程还活着 —— 旧实
        // 现置之不理直接 initialize/runVMThread，两个 VM 并存 → 原生态双
        // 初始化 → SIGSEGV 闪退。这里确认 VM 真正消亡后才继续；超时则拒
        // 绝本次启动（UI 报“启动失败”），绝不冒双 VM 的险。
        if (vmThread?.isAlive == true || NativeApp.hasActiveVM()) {
            android.util.Log.e("Psx2Native", "previous VM still active — refusing to boot a new one (avoid double-VM crash)")
            return false
        }
        // Core is initialized (Psx2Engine calls setPaths → initialize before
        // loadRom) — now safe to replay options queued by the UI pre-boot.
        flushPendingOptions()
        vmThread = thread(name = "armsx2-vm", isDaemon = true) {
            try {
                android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO)
                // Seed ARMSX2's runtime resources (shaders/GameIndex/fonts/...)
                // before the VM boots — the GS reads them from <DataRoot>/resources
                // and a missing shaders/opengl/convert.glsl aborts the render
                // device (black screen). Runs here on the VM thread so the ~10MB
                // asset copy never touches the UI thread.
                val ctx = appContext
                val sd = lastSystemDir
                if (ctx != null && sd != null) ensureResources(ctx, sd)
                NativeApp.runVMThread(path)
                android.util.Log.i("Psx2Native", "runVMThread exited")
            } catch (t: Throwable) {
                android.util.Log.e("Psx2Native", "VM thread crashed", t)
            }
        }
        return true
    }

    /**
     * 等待 VM 线程真正退出（可从 unload 或换游戏路径调用）。
     * ★ 旧实现只 join(300ms) 就放弃并丢掉引用 —— 大游戏关闭时
     * VMManager::Shutdown(false) + CPUThreadShutdown 释放大内存池/刷盘
     * 常超过 300ms，线程被遗留后台继续跑，后续 initialize/换游戏与它
     * 竞态 → 关闭大核心游戏闪退的主因之一。现分片等待至多 [waitMs]，
     * 期间不占用调用方过多时间且不留僵尸线程。
     */
    private fun stopVmThread(waitMs: Long = 300) {
        vmThread?.let { t ->
            try { t.interrupt() } catch (_: Throwable) {}
            val deadline = System.currentTimeMillis() + waitMs
            while (t.isAlive && System.currentTimeMillis() < deadline) {
                try { t.join(200) } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    break
                }
            }
            if (t.isAlive) {
                android.util.Log.w("Psx2Native", "VM thread still alive after ${waitMs}ms wait")
            }
        }
        vmThread = null
    }

    @JvmStatic fun unload() {
        try { NativeApp.shutdown() } catch (t: Throwable) { android.util.Log.w("Psx2Native", "shutdown", t) }
        // ★ shutdown() 内部最多只等 5 秒 VMState::Shutdown，超时后 VM 线程
        // 仍在收尾（大游戏常见）——这里给足时间让 runVMThread 真正返回，
        // 避免后台遗留半死 VM 与下一次启动/initialize 竞态闪退。
        stopVmThread(8000)
        // 再给 VM 状态一个短暂沉降窗口（VMState → Shutdown 的收尾）。
        var settle = 0
        while (settle < 20) {
            try {
                if (!NativeApp.hasActiveVM()) break
            } catch (_: Throwable) { break }
            try { Thread.sleep(100) } catch (_: InterruptedException) { Thread.currentThread().interrupt(); break }
            settle++
        }
    }

    /** ARMSX2 has no libretro-style reset; the core handles resets internally. */
    @JvmStatic fun reset(hard: Boolean) { /* no-op */ }

    /** ARMSX2 runs its own VM loop — nothing to pull. */
    @JvmStatic fun runFrame() { /* no-op */ }

    // ------------------------------------------------------------------
    // Input
    // ------------------------------------------------------------------

    @JvmStatic fun setPad1(bits: Int) = pushPad(0, bits)
    @JvmStatic fun setPad2(bits: Int) = pushPad(1, bits)
    @JvmStatic fun setPad3(bits: Int) = pushPad(2, bits)
    @JvmStatic fun setPad4(bits: Int) = pushPad(3, bits)

    private fun pushPad(port: Int, bits: Int) {
        if (!loaded || !NativeApp.hasActiveVM()) return
        // Full re-push: ARMSX2 stores per-key state, so every bit change is
        // reflected by re-issuing all 16 buttons each time (cheap JNI call).
        for (i in 0 until 16) {
            val pressed = (bits ushr i) and 1 != 0
            NativeApp.setPadButtonForPort(port, PAD_KEYS[i], 0, pressed)
        }
    }

    /** Dual analog sticks (port 0). Values are int16: LX, LY, RX, RY. */
    @JvmStatic fun setAnalog1(lx: Int, ly: Int, rx: Int, ry: Int) = pushSticks(0, lx, ly, rx, ry)
    /** Dual analog sticks (port 1). Same order as [setAnalog1]. */
    @JvmStatic fun setAnalog2(lx: Int, ly: Int, rx: Int, ry: Int) = pushSticks(1, lx, ly, rx, ry)

    private fun pushSticks(port: Int, lx: Int, ly: Int, rx: Int, ry: Int) {
        if (!loaded || !NativeApp.hasActiveVM()) return
        // L-stick keycodes 110..113, R-stick 120..123 (UP/RIGHT/DOWN/LEFT).
        stickAxis(port, 110, 111, 112, 113, lx, ly)
        stickAxis(port, 120, 121, 122, 123, rx, ry)
    }

    /** Push one 2-axis stick as four directional buttons with magnitude range. */
    private fun stickAxis(port: Int, upKey: Int, rightKey: Int, downKey: Int, leftKey: Int, x: Int, y: Int) {
        val up = -y
        val down = y
        val left = -x
        val right = x
        NativeApp.setPadButtonForPort(port, upKey, if (up > 0) up else 0, up > 0)
        NativeApp.setPadButtonForPort(port, downKey, if (down > 0) down else 0, down > 0)
        NativeApp.setPadButtonForPort(port, leftKey, if (left > 0) left else 0, left > 0)
        NativeApp.setPadButtonForPort(port, rightKey, if (right > 0) right else 0, right > 0)
    }

    // ------------------------------------------------------------------
    // Audio / video — push model: core handles both internally
    // ------------------------------------------------------------------

    @JvmStatic fun setRegion(region: Int) { /* ARMSX2 auto-detects from disc */ }
    @JvmStatic fun setSampleRate(rate: Int) { /* core runs Oboe internally */ }

    /**
     * Toggle full-speed mode: drop frame-limit when fast-forwarding.
     *
     * ARMSX2 中 EmuCore/GS/FrameLimitEnable 这个 ini 键是惰性的
     * （native-lib.cpp: "FrameLimitEnable is inert in this fork"），实际
     * 帧率由 VMManager::SetLimiterMode 驱动。因此除同步写 FrameLimitEnable
     * （供下次启动时 runVMThread 重读）外，必须调用 speedhackLimitermode：
     *   - 快进开启 → mode 3 (Unlimited，去掉帧限、不封顶 —— 上游实测有效的路径)
     *   - 快进关闭 → mode 0 (Nominal，正常 60fps 限帧)
     * SetLimiterMode 是非阻塞的（Host::RunOnCPUThread 默认 block=false），
     * 不再调用阻塞的 commitSettings()，避免快进切换时 UI 线程等待卡顿。
     */
    @JvmStatic fun setFastForward(speed: Int) {
        if (!loaded) return
        try {
            val on = speed > 0
            NativeApp.setSetting("EmuCore/GS", "FrameLimitEnable", "bool", if (on) "false" else "true")
            NativeApp.speedhackLimitermode(if (on) 3 else 0)
        } catch (t: Throwable) { /* best-effort */ }
    }

    /**
     * Pushes queued settings into the running VM — but only when a VM is
     * actually up. native commitSettings() blocks on the UI thread
     * (Host::RunOnCPUThread(block=true)) until the CPU thread drains its
     * queue; during VM bring-up the vm thread is busy in
     * while(!s_window)/Initialize and never drains, which would hang the
     * caller until ANR. Before the VM runs, writes to the base settings
     * layer are enough — VMManager::Initialize reads them at boot.
     */
    private fun commitSettingsIfVmActive() {
        try {
            if (NativeApp.hasActiveVM()) NativeApp.commitSettings()
        } catch (t: Throwable) { /* best-effort */ }
    }

    /** ARMSX2 always exposes both pads as DualShock2. */
    @JvmStatic fun setControllerDevice(port: Int, device: Int) { /* no-op */ }

    /** VP (internal draw-rate) of the running VM — used for HUD pacing. */
    @JvmStatic fun videoFps(): Double {
        return try {
            if (NativeApp.hasActiveVM()) {
                val vps = NativeApp.getVPS().toDouble()
                if (vps > 1.0 && vps < 500.0) vps else 60.0
            } else 60.0
        } catch (t: Throwable) { 60.0 }
    }

    /**
     * ARMSX2 核心的真实实时帧率（PerformanceMetrics::GetFPS，即核心实际
     * 呈现到 Surface 的帧率）。修复 FPS HUD：旧实现靠心跳线程固定间隔打点，
     * 永远显示 ~60，游戏掉帧也看不出来；现在直接读核心指标。
     * VM 未启动/未出帧时返回 0，UI 回退到帧计数。
     */
    @JvmStatic fun realtimeFps(): Double {
        return try {
            if (NativeApp.hasActiveVM()) {
                val fps = NativeApp.getFPS().toDouble()
                if (fps > 0.5 && fps < 5000.0) fps else 0.0
            } else 0.0
        } catch (t: Throwable) { 0.0 }
    }

    @JvmStatic fun saveState(slot: Int, path: String): Boolean =
        try { NativeApp.saveStateToSlot(slot) } catch (t: Throwable) { false }

    @JvmStatic fun loadState(slot: Int, path: String): Boolean =
        try { NativeApp.loadStateFromSlot(slot) } catch (t: Throwable) { false }

    /** No frame buffer to pull — ARMSX2 renders straight to the Surface. */
    @JvmStatic fun getFrameBuffer(out: IntArray): Boolean = false

    /** No audio to pull — ARMSX2 plays internally via Oboe. */
    @JvmStatic fun readAudio(out: ShortArray): Int = 0
    @JvmStatic fun audioSampleRate(): Int = 48000
    @JvmStatic fun audioTargetSampleRate(): Int = 48000

    /** systemDir = <filesDir>/ps2; ARMSX2 reads BIOS from <systemDir>/pcsx2/bios. */
    @JvmStatic fun setPaths(systemDir: String, saveDir: String) {
        lastSystemDir = systemDir
        try {
            NativeApp.initialize(systemDir, File(systemDir, "pcsx2/bios").absolutePath, 1)
            initialized = true
        } catch (t: Throwable) {
            android.util.Log.e("Psx2Native", "initialize failed", t)
        }
    }

    /**
     * Seeds ARMSX2's runtime resources (shaders/GameIndex/fonts/...) from the
     * APK assets into <systemDir>/resources — the folder the core pins as
     * EmuFolders::Resources. The GS renderer reads shaders/opengl/convert.glsl
     * (and the Vulkan equivalents) from there; if it's missing the render
     * device fails to create and the VM never boots (black screen). Skips when
     * already seeded.
     */
    private fun ensureResources(context: android.content.Context, systemDir: String) {
        try {
            val dest = File(systemDir, "resources")
            // Key file that gates the whole seed: if the OpenGL convert shader
            // is already in place, resources are present — avoid re-copying
            // ~10MB on every boot.
            if (File(dest, "shaders/opengl/convert.glsl").isFile) return
            copyAssetTree(context, "resources", dest)
            android.util.Log.i("Psx2Native", "Seeded ARMSX2 resources -> $dest")
        } catch (t: Throwable) {
            android.util.Log.e("Psx2Native", "Seed ARMSX2 resources failed", t)
        }
    }

    /** Recursively copies an APK asset tree into [dest]. */
    private fun copyAssetTree(context: android.content.Context, assetPath: String, dest: File) {
        val assets = context.assets
        val names = assets.list(assetPath) ?: return
        if (names.isEmpty()) {
            // Leaf file.
            dest.parentFile?.mkdirs()
            assets.open(assetPath).use { input ->
                dest.outputStream().use { out -> input.copyTo(out) }
            }
            return
        }
        dest.mkdirs()
        for (name in names) {
            copyAssetTree(context, "$assetPath/$name", File(dest, name))
        }
    }

    @JvmStatic fun setSaveName(name: String) { /* ARMSX2 keeps save slots itself */ }

    @JvmStatic fun lastError(): String = ""

    /** Pass the emulation Surface (or null). Size 0/0 → core keeps window size. */
    @JvmStatic fun setSurface(surface: Surface?) {
        if (loaded) {
            try { NativeApp.onNativeSurfaceChanged(surface, 0, 0) }
            catch (t: Throwable) { android.util.Log.w("Psx2Native", "onNativeSurfaceChanged", t) }
        }
    }

    /**
     * Pass the emulation Surface together with its real pixel size.
     *
     * Unlike [setSurface], this triggers `MTGS::UpdateDisplayWindow()` in the
     * core (native `onNativeSurfaceChanged` only reposts an update when
     * width/height are positive). Called from `surfaceChanged` so the GS
     * thread switches its presentation target to the current surface —
     * otherwise it keeps posting frames to a stale/abandoned BufferQueue and
     * the screen stays black while emulation (and audio) run fine.
     */
    @JvmStatic fun setSurface(surface: Surface?, width: Int, height: Int) {
        if (loaded) {
            try { NativeApp.onNativeSurfaceChanged(surface, width, height) }
            catch (t: Throwable) { android.util.Log.w("Psx2Native", "onNativeSurfaceChanged(size)", t) }
        }
    }

    /**
     * Tell the core the display surface was destroyed so the GS thread can
     * release its swapchain and stop posting frames to a dead BufferQueue.
     * A null-Surface call to [setSurface] is NOT equivalent: native only
     * reposts `MTGS::UpdateDisplayWindow()` when width/height > 0, so the GS
     * thread would never learn the surface died.
     */
    @JvmStatic fun surfaceDestroyed() {
        if (loaded) {
            try { NativeApp.onNativeSurfaceDestroyed() }
            catch (t: Throwable) { android.util.Log.w("Psx2Native", "onNativeSurfaceDestroyed", t) }
        }
    }

    // ------------------------------------------------------------------
    // Core options: pcsx2_* (previous libretro keys) → ARMSX2 ini keys
    // ------------------------------------------------------------------

    @JvmStatic fun setCoreOption(key: String, value: String) {
        if (!loaded) return
        if (!initialized) {
            // Core .so is loaded (NesApp preloads it) but initialize() has not
            // run yet — EmulatorScreen's core-options LaunchedEffect fires
            // before loadRom during first composition, and the native settings
            // layer (Host::LAYER_BASE) does not exist until initialize(). Any
            // renderVulkan()/setSetting() here would crash on a null layer.
            // Queue and replay right after setPaths()/initialize() in loadRom.
            pendingOptions.add(key to value)
            return
        }
        applyCoreOption(key, value)
    }

    private fun applyCoreOption(key: String, value: String) {
        try {
            when (key) {
                "pcsx2_renderer" -> when (value) {
                    "vulkan" -> NativeApp.renderVulkan()
                    "opengl" -> NativeApp.renderOpenGL()
                    "software" -> NativeApp.renderSoftware()
                    else -> NativeApp.renderAuto()
                }

                "pcsx2_upscale_multiplier" ->
                    NativeApp.renderUpscalemultiplier(value.toFloatOrNull() ?: 1f)

                "pcsx2_fast_boot" ->
                    NativeApp.setSetting("EmuCore", "EnableFastBoot", "bool", truthy(value))
                "pcsx2_rumble" ->
                    NativeApp.setSetting("Pad1", "ForceFeedback", "bool", truthy(value))

                "pcsx2_mtvu" ->
                    NativeApp.setSetting("EmuCore/Speedhacks", "vuThread", "bool", truthy(value))
                "pcsx2_instant_vu1" ->
                    NativeApp.setSetting("EmuCore/Speedhacks", "vu1Instant", "bool", truthy(value))
                "pcsx2_ee_cycle_rate" ->
                    NativeApp.setSetting("EmuCore/Speedhacks", "EECycleRate", "int", value)
                "pcsx2_ee_cycle_skip" ->
                    NativeApp.setSetting("EmuCore/Speedhacks", "EECycleSkip", "int", value)

                "pcsx2_aspect_ratio" ->
                    NativeApp.setAspectRatio(mapAspectRatio(value))

                "pcsx2_widescreen_patches" ->
                    NativeApp.setSetting("EmuCore", "EnableWideScreenPatches", "bool", truthy(value))
                "pcsx2_no_interlacing_patches" ->
                    NativeApp.setSetting("EmuCore", "EnableNoInterlacingPatches", "bool", truthy(value))

                // --- Pixel-level GS options ---
                // 键名/枚举值对照 ARMSX2 核心 Pcsx2Config.cpp::GSOptions::LoadSave
                // (EmuCore/GS 段)。旧实现把字符串枚举名当 int 传，native setSetting
                // 用 FromChars 解析失败会静默丢弃 → 这些设置以前根本没生效；且部分
                // 键名写错(Dithering/AnisoFilter/Deinterlace/BlendingAccuracy 都不是
                // 核心实际读取的键)。下方一律改为核心真实键名 + int 枚举索引。
                "pcsx2_texture_filtering" ->
                    // BiFiltering: 0=Nearest, 1=Forced, 2=PS2, 3=Forced_But_Sprite。
                    // GameBox「双线性过滤」enabled=bilinear_ps2(PS2 原生平滑=2)，
                    // disabled=nearest(像素风=0)。键名是 filter(不是 TriFilter)。
                    NativeApp.setSetting("EmuCore/GS", "filter", "int",
                        if (value.contains("bilinear")) "2" else "0")
                "pcsx2_mipmapping" ->
                    NativeApp.setSetting("EmuCore/GS", "hw_mipmap", "bool", truthy(value))
                "pcsx2_dithering" ->
                    // 核心键名是 dithering_ps2(不是 Dithering)。值 0/1/2 本就是 int。
                    NativeApp.setSetting("EmuCore/GS", "dithering_ps2", "int", value)
                "pcsx2_blending_accuracy" ->
                    // AccBlendLevel: 0=Minimum..5=Maximum。UI 传枚举名(minimum/basic/…)，
                    // 必须转成 int，键名是 accurate_blending_unit(不是 BlendingAccuracy)。
                    NativeApp.setSetting("EmuCore/GS", "accurate_blending_unit", "int",
                        mapBlendingAccuracy(value))
                "pcsx2_trilinear_filtering" ->
                    // TriFiltering(s8): -1=Automatic, 0=Off, 1=PS2, 2=Forced。
                    // UI 传 auto/off/ps2/forced 枚举名，需转 int。
                    NativeApp.setSetting("EmuCore/GS", "TriFilter", "int",
                        mapTrilinearFiltering(value))
                "pcsx2_anisotropic_filtering" ->
                    // 核心键名是 MaxAnisotropy(不是 AnisoFilter)。值 0/2/4/8/16 已是 int。
                    NativeApp.setSetting("EmuCore/GS", "MaxAnisotropy", "int", value)
                "pcsx2_deinterlace_mode" ->
                    // 核心键名是 deinterlace_mode(不是 Deinterlace)。值 0/1/4/5/8/9 已是 int。
                    NativeApp.setSetting("EmuCore/GS", "deinterlace_mode", "int", value)
                "pcsx2_hw_download_mode" ->
                    // GSHardwareDownloadMode: 0=Accurate, 1=AccurateForceFull,
                    // 2=NoReadbacks(禁用回读,同步GS线程), 3=Unsynchronized(非同步),
                    // 4=Disabled(禁用/忽略), 5=Asynchronous(异步)。UI 传枚举名，需转 int。
                    // 旧实现是 no-op → 设置完全不起作用，现接回 EmuCore/GS/HWDownloadMode。
                    NativeApp.setSetting("EmuCore/GS", "HWDownloadMode", "int",
                        mapHwDownloadMode(value))

                // --- ARMSX2 live GS pokes (see native-lib.cpp render* methods) ---
                "pcsx2_tv_shader" ->
                    NativeApp.renderTvShader(value.toIntOrNull()?.coerceIn(0, 7) ?: 0)
                "pcsx2_shade_boost" ->
                    NativeApp.renderShadeBoost(value == "enabled", 50, 50, 50, 50)
                "pcsx2_half_pixel_offset" ->
                    NativeApp.renderHalfpixeloffset(value.toIntOrNull()?.coerceIn(0, 5) ?: 0)
                "pcsx2_texture_preloading" ->
                    NativeApp.renderPreloading(value.toIntOrNull()?.coerceIn(0, 2) ?: 1)

                else -> android.util.Log.w("Psx2Native", "unmapped core option: $key=$value")
            }
            // Persist to the core's ini layer; read at next VM boot.
            commitSettingsIfVmActive()
        } catch (t: Throwable) {
            android.util.Log.w("Psx2Native", "setCoreOption($key=$value)", t)
        }
    }

    /** Replays core options queued before initialize() (see [setCoreOption]). */
    private fun flushPendingOptions() {
        while (true) {
            val entry = pendingOptions.poll() ?: break
            applyCoreOption(entry.first, entry.second)
        }
    }

    private fun truthy(value: String): String =
        if (value.equals("true", true) || value == "1" || value.equals("enabled", true)) "true" else "false"

    /** GameBox pcsx2_aspect_ratio strings → ARMSX2 AspectRatioType index. */
    private fun mapAspectRatio(value: String): Int = when (value.lowercase()) {
        "auto" -> 1
        "4:3" -> 2
        "16:9" -> 3
        "16:10" -> 4
        "stretch" -> 0
        else -> 1
    }

    /** GameBox pcsx2_blending_accuracy 枚举名 → AccBlendLevel int (0..5)。 */
    private fun mapBlendingAccuracy(value: String): String = when (value.lowercase()) {
        "minimum" -> "0"; "basic" -> "1"; "medium" -> "2"
        "high" -> "3"; "full" -> "4"; "maximum" -> "5"
        else -> value.toIntOrNull()?.coerceIn(0, 5)?.toString() ?: "1"
    }

    /** GameBox pcsx2_trilinear_filtering 枚举名 → TriFiltering s8 (-1..2)。 */
    private fun mapTrilinearFiltering(value: String): String = when (value.lowercase()) {
        "auto", "automatic" -> "-1"; "off" -> "0"
        "ps2" -> "1"; "forced" -> "2"
        else -> value.toIntOrNull()?.coerceIn(-1, 2)?.toString() ?: "-1"
    }

    /**
     * GameBox pcsx2_hw_download_mode 枚举名 → GSHardwareDownloadMode int (0..5)。
     * 0=Accurate(精准) 1=ForceFull 2=NoReadbacks(禁用回读,同步GS线程)
     * 3=Unsynchronized(非同步) 4=Disabled(禁用/忽略) 5=Asynchronous(异步)。
     */
    private fun mapHwDownloadMode(value: String): String = when (value.lowercase()) {
        "accurate" -> "0"; "force_full", "forcefull" -> "1"
        "no_readbacks", "noreadbacks", "disable_readbacks" -> "2"
        "unsynchronized", "unsync" -> "3"
        "disabled", "disable" -> "4"
        "async", "asynchronous" -> "5"
        else -> value.toIntOrNull()?.coerceIn(0, 5)?.toString() ?: "0"
    }

    @JvmStatic fun videoWidth(): Int = 640
    @JvmStatic fun videoHeight(): Int = 448

    /** ARMSX2 renders directly — video filters not applied on this path. */
    @JvmStatic fun setVideoFilter(filter: Int) { /* no-op */ }
    @JvmStatic fun setHighQualityScaling(enabled: Boolean) { /* no-op */ }

    /** Whether libemucore_4k.so was successfully loaded. */
    @JvmStatic fun isCoreLibLoaded(): Boolean = loaded

    /** App context — retained for API compatibility (previously used to locate the prebuilt lib). */
    @Volatile var appContext: android.content.Context? = null
}