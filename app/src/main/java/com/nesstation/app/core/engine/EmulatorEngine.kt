package com.nesstation.app.core.engine

import android.view.Surface
import com.nesstation.app.core.model.GamePlatform
import java.io.File

/**
 * Captured frame data for screenshots.
 */
data class FrameCapture(
    val pixels: IntArray,
    val width: Int,
    val height: Int
)

/**
 * Per-frame lockstep hook for online multiplayer.
 *
 * When attached to an [EmulatorEngine] via [EmulatorEngine.frameHook], the
 * engine's emulation loop calls [beforeFrame] before each `runFrame()` and
 * [afterFrame] after it. The hook is responsible for:
 *
 * 1. Sampling the local player's pad bits (pushed by the UI through
 *    [NetplayController.setLocalPad]).
 * 2. Sending the local pad bits to the network for the upcoming frame.
 * 3. Synchronously waiting for the remote player's pad bits for the
 *    executing frame (lockstep).
 * 4. Returning `(pad1, pad2)` so the engine can push them to the core
 *    immediately before `runFrame()` — guaranteeing both players execute
 *    the same input sequence on the same frame.
 *
 * When the hook returns `null` from [beforeFrame], the engine falls back
 * to single-player behavior (uses whatever pad state was last set via
 * `setPad1()` / `setPad2()`).
 *
 * Threading: [beforeFrame] / [afterFrame] are called on the emulation
 * thread. They MUST NOT block the UI thread. Blocking inside [beforeFrame]
 * (e.g. waiting for the remote input) is expected and is what enables
 * lockstep synchronization.
 */
interface NetplayHook {
    /**
     * Called on the emulation thread before each `runFrame()`.
     *
     * @param frame The executing frame index, 0-based, monotonically
     *             increasing while the hook is attached. Reset to 0 when
     *             the hook is detached / a new ROM loads.
     * @return `(pad1, pad2)` bit masks to push to the core for this frame,
     *         or `null` to let the engine use whatever was last set
     *         (single-player behavior).
     */
    fun beforeFrame(frame: Long): Pair<Int, Int>?

    /** Called on the emulation thread after each `runFrame()`. */
    fun afterFrame(frame: Long) {}
}

/**
 * Common interface for all emulator engines (NES, SNES, GB/GBC/GBA).
 * EmulatorScreen uses this interface instead of a concrete engine class,
 * allowing any platform's engine to drive the same UI.
 */
interface EmulatorEngine {

    /** Frame buffer for fallback Bitmap rendering (when no Surface is attached). */
    val frameBuffer: IntArray

    /** Whether a ROM is currently loaded. */
    val isLoaded: Boolean

    /** Load the native library. Returns true on success. */
    fun ensureLoaded(): Boolean

    /**
     * Load a ROM and start the emulation thread.
     * @return true if the ROM loaded and emulation started
     */
    fun loadRom(rom: File, systemDir: String, saveDir: String, onFrame: () -> Unit): Boolean

    /** Attach/detach a Surface for hardware-accelerated direct rendering. */
    fun setSurface(surface: Surface?)

    /**
     * Notify the engine that the display surface changed size.
     *
     * Default no-op keeps libretro-style engines (which size their buffer
     * from the core's own resolution) unaffected. PS2 (ARMSX2) requires the
     * real width/height to be forwarded to the core so it can update its
     * presentation window (`MTGS::UpdateDisplayWindow()`); without a positive
     * size the GS thread is never told about the surface and keeps rendering
     * to an abandoned BufferQueue (symptom: audio but black screen).
     *
     * @param surface The (possibly recreated) Surface, or null.
     * @param width   Current surface width in pixels (>0 when valid).
     * @param height  Current surface height in pixels (>0 when valid).
     */
    fun onSurfaceChanged(surface: Surface?, width: Int, height: Int) {}

    /**
     * Notify the engine that the display surface was destroyed.
     *
     * Default no-op for libretro-style engines. ARMSX2 needs a dedicated
     * destroy notification so the GS thread can release its swapchain and
     * stop posting frames to a dead BufferQueue. Passing a null Surface via
     * [setSurface] is NOT equivalent — the core only reposts an update when
     * width/height are positive, so the GS thread would never learn the
     * surface died.
     */
    fun onSurfaceDestroyed() {}

    /**
     * Set an explicit .srm basename for the next ROM load.
     * Pass a stable per-game identifier (e.g. the game's DB id) so that
     * content:// URI games (which share a temp ROM file) get per-game .srm
     * files instead of clobbering each other. Must be called before loadRom().
     */
    fun setSaveName(name: String)

    /** Set a core option by key/value. */
    fun setCoreOption(key: String, value: String)

    /** Current video width from the core. */
    fun videoWidth(): Int

    /** Current video height from the core. */
    fun videoHeight(): Int

    /** Set the frontend video post-processing filter (0-10). */
    fun setVideoFilter(filter: Int)

    /**
     * Control the native surface buffer geometry for performance vs quality.
     * - `false` (default): buffer = source resolution (256x240 / 240x160),
     *   1:1 blit + hardware-compositor GPU upscale — fast, slightly softer.
     * - `true`: buffer = display resolution, C++ per-pixel nearest-neighbor
     *   scale — sharper, much heavier CPU (can lag on low-power devices).
     */
    fun setHighQualityScaling(enabled: Boolean)

    /** Set fast-forward speed (0 = off, 2/4/6/8/16 = speed multiplier). */
    fun setFastForward(speed: Int)

    /** Pause or resume emulation. */
    fun setPaused(paused: Boolean)

    /** Reset the emulation. */
    fun reset(hard: Boolean = false)

    /** Unload the current ROM and stop the emulation thread. */
    fun unload()

    /** Full shutdown. */
    fun shutdown()

    /** Push controller state for player 1 (port 0). Bit layout depends on platform. */
    fun setPad1(bits: Int)

    /** Push controller state for player 2 (port 1). Same bit layout as setPad1. */
    fun setPad2(bits: Int)

    /**
     * Optional lockstep hook for online multiplayer.
     *
     * - Set non-null while a network match is active: the engine's
     *   emulation loop calls [NetplayHook.beforeFrame] before each
     *   `runFrame()` and [NetplayHook.afterFrame] after it, allowing
     *   the hook to sample local input, send it over the network, and
     *   synchronously wait for the remote player's input for the same
     *   frame (lockstep).
     * - Set null for single-player (default): the engine's loop just
     *   calls `runFrame()` repeatedly and reads whatever pad state was
     *   last pushed via [setPad1] / [setPad2].
     *
     * The engine resets its internal frame counter to 0 when the hook
     * is attached, so the hook always sees frame indices starting from 0.
     */
    var frameHook: NetplayHook?

    /** Set region hint (0=NTSC, 1=PAL). */
    fun setRegion(region: Int)

    /** Set audio sample rate hint. */
    fun setSampleRate(rate: Int)

    /** Save state to a file. Returns false if the core failed to serialize
     *  (e.g. DSi mode) or the file could not be written. */
    fun saveState(slot: Int, dst: File): Boolean

    /** Load state from a file. Returns false if the file is missing/corrupt
     *  or the core rejected it (wrong version, truncated, etc.). */
    fun loadState(slot: Int, src: File): Boolean

    /** Capture the current frame as an ARGB bitmap (0xAARRGGBB).
     * Returns the bitmap array and dimensions, or null if no frame is available.
     * Works even with hardware-accelerated surface rendering by requesting
     * a fresh frame buffer copy from the native core.
     */
    fun captureFrame(): FrameCapture?

    /**
     * 实时帧率查询（FPS HUD 用）。
     *
     * 返回 > 0 表示该核心支持真实的实时帧率读取（PS2 读 ARMSX2 的
     * PerformanceMetrics，PS1 读核心实际提交的帧数），UI 应优先采用；
     * 返回 0 表示不支持，UI 回退到 onFrame 帧计数（NES/SFC/GBA/NDS/
     * MD/PCE/DOS/ARCADE 等拉模型核心的计数本身就是真实模拟帧率）。
     *
     * 为什么需要它：PS2（ARMSX2）是推模型核心，前端没有模拟循环，
     * 心跳线程按固定间隔打的 onFrame 计数永远是 ~60，与真实性能无关；
     * 只有核心内部的 PerformanceMetrics 才知道真实帧率。
     */
    fun realtimeFps(): Double = 0.0

    /** Last error message from the core. */
    fun lastError(): String

    companion object {
        /**
         * Factory: return the appropriate engine for the given platform.
         * NES    -> NesEngine
         * SFC    -> SnesEngine
         * GB/GBA -> GbaEngine
         * DOS    -> DosEngine (DOSBox-Pure)
         * ARCADE -> FbNeoEngine (FBNeo — CPS1/2/3, NeoGeo, PGM, etc.)
         * MD     -> GenesisEngine (Genesis-Plus-GX — MD/SMS/GG/SG/Mega-CD)
         * PCE    -> PceEngine (Geargrafx — PC-Engine/TurboGrafx-16/SuperGrafx/PCE-CD)
         * NDS    -> NdsEngine (melonDS — Nintendo DS / DSi)
         * PSX    -> PsxEngine (PCSX-ReARMed — Sony PlayStation 1)
         * PS2    -> Psx2Engine (PCEE2 — PCSX2 — Sony PlayStation 2)
         * DC     -> FlycastEngine (Flycast — Sega Dreamcast / Naomi / Atomiswave)
         */
        fun forPlatform(platform: GamePlatform): EmulatorEngine = when (platform) {
            GamePlatform.NES    -> NesEngine.get()
            GamePlatform.SFC    -> SnesEngine.get()
            GamePlatform.GB     -> GbaEngine.get()
            GamePlatform.GBA    -> GbaEngine.get()
            GamePlatform.DOS    -> DosEngine.get()
            GamePlatform.ARCADE -> FbNeoEngine.get()
            GamePlatform.MD     -> GenesisEngine.get()
            GamePlatform.PCE    -> PceEngine.get()
            GamePlatform.NDS    -> NdsEngine.get()
            GamePlatform.PSX    -> PsxEngine.get()
            GamePlatform.PS2    -> Psx2Engine.get()
            GamePlatform.DC     -> FlycastEngine.get()
            GamePlatform.JAVA   -> J2meEngine.get()
        }
    }
}
