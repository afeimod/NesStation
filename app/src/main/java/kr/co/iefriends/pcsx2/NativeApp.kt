package kr.co.iefriends.pcsx2

import android.view.Surface

/**
 * JNI binding to ARMSX2's `libemucore_4k.so` (PCSX2 fork with ARM64 JIT
 * via vixl), compiled directly from source in this repo via
 * `ARMSX2-master/platforms/android/app/src/main/cpp`.
 *
 * The emucore is a **push-model** core — it does NOT use libretro
 * callbacks. Key semantics:
 *  - `initialize()` must be called once before boot: it pins the system /
 *    BIOS directories (EmuFolders::DataRoot etc).
 *  - `runVMThread(path)` blocks until the VM exits — call it on a
 *    background thread. GameBox wraps it in [Psx2Native.loadRom].
 *  - Rendering: pass the Android `Surface` via [onNativeSurfaceChanged];
 *    the core renders into it itself (Vulkan/GL/Software via renderXxx).
 *  - Audio: played internally via Oboe — no audio pull on the Kotlin side.
 *  - Input: [setPadButtonForPort] uses Android KeyEvent keycodes
 *    (19=DPadUp, 96=Cross, 97=Circle, 99=Square, 100=Triangle,
 *    108=Start, 109=Select, 102=L1, 103=R1, 104=L2, 105=R2,
 *    106=L3, 107=R3, 110..113 = L-stick U/R/D/L, 120..123 = R-stick,
 *    200=Analog toggle). `range` is 0..32767 stick magnitude (digital
 *    buttons pass 0/32767).
 *  - Settings: [setSetting] + [commitSettings] push PCSX2 ini keys
 *    ("EmuCore/Speedhacks/vuThread" etc). Several runtime knobs have
 *    dedicated JNI (renderVulkan/renderOpenGL/renderSoftware/renderAuto,
 *    renderUpscalemultiplier, setAspectRatio, speedhackEecyclerate,
 *    speedhackEecycleskip).
 *
 * Only a subset of the ARMSX2 NativeApp JNI surface is declared here —
 * JNI binds lazily per used method, so unused symbols are never resolved.
 */
object NativeApp {

    @Volatile private var libLoaded = false

    init {
        loadCore()
    }

    /**
     * Load `libemucore_4k.so`. On 16 KiB-page devices (Android 15+ on some
     * hardware) the 4k build refuses to load — fall back to the 16k build.
     */
    @Synchronized
    @JvmStatic
    fun ensureLoaded(): Boolean {
        if (libLoaded) return true
        loadCore()
        return libLoaded
    }

    private fun loadCore() {
        if (libLoaded) return
        for (lib in arrayOf("emucore_4k", "emucore_16k")) {
            try {
                System.loadLibrary(lib)
                libLoaded = true
                android.util.Log.i("NativeApp", "loaded $lib")
                return
            } catch (e: UnsatisfiedLinkError) {
                android.util.Log.w("NativeApp", "loadLibrary($lib) failed", e)
            }
        }
    }

    // ------------------------------------------------------------------
    // Lifecycle
    // ------------------------------------------------------------------

    /**
     * @param path       system folder (memcards / savestates / configs land
     *                   here) — GameBox passes `<filesDir>/ps2`
     * @param biosFolder folder containing the PS2 BIOS (GameBox:
     *                   `<systemDir>/pcsx2/bios`)
     * @param apiVer     emucore API version
     */
    @JvmStatic external fun initialize(path: String?, biosFolder: String?, apiVer: Int)

    /** Blocks until VM exit — run on a background thread. Returns false on boot failure. */
    @JvmStatic external fun runVMThread(path: String): Boolean

    /** Pass the emulation Surface (or null to detach). width/height 0 = keep window size. */
    @JvmStatic external fun onNativeSurfaceChanged(surface: Surface?, width: Int, height: Int)

    /** Release the surface; the GS thread stops presenting into the dead BufferQueue. */
    @JvmStatic external fun onNativeSurfaceDestroyed()

    @JvmStatic external fun pause()
    @JvmStatic external fun resume()
    @JvmStatic external fun shutdown()
    @JvmStatic external fun hasActiveVM(): Boolean

    @JvmStatic external fun changeDisc(path: String)

    // ------------------------------------------------------------------
    // Input (Android KeyEvent keycodes — see class doc)
    // ------------------------------------------------------------------

    @JvmStatic external fun setPadButtonForPort(port: Int, key: Int, range: Int, pressed: Boolean)
    @JvmStatic external fun resetKeyStatus()

    // ------------------------------------------------------------------
    // Settings — PCSX2 ini keys, pushed pre-boot via commitSettings
    // ------------------------------------------------------------------

    @JvmStatic external fun setSetting(section: String, key: String, type: String, value: String)
    @JvmStatic external fun commitSettings()

    /** Runtime renderer switch (no restart needed). */
    @JvmStatic external fun renderVulkan()
    @JvmStatic external fun renderOpenGL()
    @JvmStatic external fun renderSoftware()
    @JvmStatic external fun renderAuto()

    /** Internal resolution multiplier (1x..4x). Mirrors psx2_upscale_multiplier. */
    @JvmStatic external fun renderUpscalemultiplier(multiplier: Float)

    /** GS pixel-level live pokes (see native-lib.cpp render* methods). */
    @JvmStatic external fun renderTvShader(value: Int)
    @JvmStatic external fun renderShadeBoost(enabled: Boolean, brightness: Int, contrast: Int, saturation: Int, gamma: Int)
    @JvmStatic external fun renderHalfpixeloffset(value: Int)
    @JvmStatic external fun renderPreloading(value: Int)

    /** AspectRatioType index (0=Stretch .. 5=Custom). */
    @JvmStatic external fun setAspectRatio(mode: Int)

    /** Speedhack knobs: EECycleRate (-3..3) / EECycleSkip (0..3). */
    @JvmStatic external fun speedhackEecyclerate(rate: Int)
    @JvmStatic external fun speedhackEecycleskip(skip: Int)

    /**
     * Frame limiter mode — the ACTUAL live fast-forward control in this
     * ARMSX2 fork (the FrameLimitEnable ini key is inert; frame pacing is
     * driven by VMManager::SetLimiterMode).
     * 0=Nominal (normal speed), 1=Turbo (fast-forward), 2=Slomo (slow-mo),
     * 3=Unlimited (frame-limit off, uncapped — the fast-forward the
     * upstream frontend demonstrably uses). Non-blocking: enqueues onto the
     * CPU thread.
     */
    @JvmStatic external fun speedhackLimitermode(mode: Int)

    /** SPU2 master volume (0..200 percent) + mute. */
    @JvmStatic external fun setAudioVolume(volume: Int)
    @JvmStatic external fun setAudioMuted(muted: Boolean)

    // ------------------------------------------------------------------
    // State save / load — slots 1..5, stored under DataRoot/savestates
    // ------------------------------------------------------------------

    @JvmStatic external fun saveStateToSlot(slot: Int): Boolean
    @JvmStatic external fun loadStateFromSlot(slot: Int): Boolean
    @JvmStatic external fun saveAutosaveState(): Boolean
    @JvmStatic external fun loadAutosaveState(): Boolean

    // ------------------------------------------------------------------
    // Telemetry
    // ------------------------------------------------------------------

    /** Presented FPS of the currently running VM (0 if none). */
    @JvmStatic external fun getFPS(): Float

    /** Internal (GS draw-call) fps — closer to the game's own refresh rate. */
    @JvmStatic external fun getVPS(): Float

    /** 0..100+ emulation speed percentage. */
    @JvmStatic external fun getEmuSpeedPercent(): Float

    @JvmStatic external fun getGameTitle(): String
    @JvmStatic external fun getGameSerial(): String

    // ------------------------------------------------------------------
    // Host callbacks invoked FROM the native emulation thread.
    //
    // The emucore caches these static method IDs at initialize() via
    // GetStaticMethodID with NO exception guard — a missing method throws
    // NoSuchMethodError at boot and aborts the app (SIGABRT, seen as "PS2
    // startup crash"). They MUST exist with these exact JNI signatures.
    // All are called on a non-Java thread (EE/IOP/GS) attached via JNI, so
    // they must never throw and must not touch UI state directly.
    // ------------------------------------------------------------------

    /**
     * Core reports the VM paused/resumed (Host::OnVMPaused / OnVMResumed).
     * GameBox drives pause from the UI via [pause]/[resume] and tracks the
     * state itself (Psx2Engine / EmulatorScreen), so this echo is
     * intentionally a no-op — forwarding it could race the UI's own
     * pause/resume/shutdown and flip a closing screen back to running.
     */
    @JvmStatic
    fun vmSetPaused(paused: Boolean) {
        // Intentionally empty — see KDoc.
    }

    /**
     * PS2 pad motor intensity changed (unified slot: 0=Player1, 1=Player2);
     * large/small are 0..255. Best-effort: drive the device's built-in
     * vibrator with the same weighted blend the reference frontend uses
     * (0.6*large + 0.4*small) so a small-motor-only pulse stays light and
     * distinct. Gated upstream by the core's "Pad1/ForceFeedback" setting
     * (pcsx2_rumble option). Must never throw — called from the IOP thread.
     */
    @JvmStatic
    fun onPadRumble(pad: Int, largeMotor: Int, smallMotor: Int) {
        try {
            if (pad < 0 || largeMotor < 0 || smallMotor < 0) return
            val intensity = (largeMotor / 255f * 0.6f + smallMotor / 255f * 0.4f)
                .coerceIn(0f, 1f)
            if (intensity <= 0f) return
            val ctx = com.nesstation.app.core.jni.Psx2Native.appContext ?: return
            val vibrator = if (android.os.Build.VERSION.SDK_INT >= 31) {
                (ctx.getSystemService(android.content.Context.VIBRATOR_MANAGER_SERVICE)
                    as? android.os.VibratorManager)?.defaultVibrator
            } else {
                ctx.getSystemService(android.content.Context.VIBRATOR_SERVICE)
                    as? android.os.Vibrator
            } ?: return
            if (!vibrator.hasVibrator()) return
            val amp = Math.round(intensity * 255f).coerceIn(1, 255)
            if (android.os.Build.VERSION.SDK_INT >= 26) {
                vibrator.vibrate(android.os.VibrationEffect.createOneShot(80, amp))
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(80)
            }
        } catch (t: Throwable) {
            android.util.Log.w("NativeApp", "onPadRumble", t)
        }
    }

    /**
     * RetroAchievements unlock/info/leaderboard sound — GameBox has no RA
     * audio integration, so this is a no-op. Must exist (cached at init).
     */
    @JvmStatic
    fun playSound(path: String?) {
        // Intentionally empty — GameBox doesn't play RA sounds.
    }

    /** Open a content:// URI, returning an owned fd (or -1). Used by the core
     *  for SAF-picked disc images. */
    @JvmStatic
    fun openContentUri(uriString: String?): Int {
        if (uriString.isNullOrEmpty()) return -1
        return try {
            val ctx = com.nesstation.app.core.jni.Psx2Native.appContext ?: return -1
            ctx.contentResolver
                .openFileDescriptor(android.net.Uri.parse(uriString), "r")
                ?.detachFd() ?: -1
        } catch (t: Throwable) {
            android.util.Log.w("NativeApp", "openContentUri($uriString)", t)
            -1
        }
    }

    /** java.io.File.mkdirs fallback for FUSE-emulated external storage
     *  (folder memory cards), where a raw libc mkdir() can be denied. */
    @JvmStatic
    fun createDirectoryPath(path: String?): Boolean {
        if (path.isNullOrEmpty()) return false
        return try {
            val dir = java.io.File(path)
            if (dir.isDirectory()) true else { dir.mkdirs(); dir.isDirectory() }
        } catch (t: Throwable) {
            false
        }
    }

    /** java.io.File.createNewFile fallback for FUSE-emulated external storage
     *  (new folder-card saves), where libc fopen(O_CREAT) can be denied. */
    @JvmStatic
    fun createFilePath(path: String?): Boolean {
        if (path.isNullOrEmpty()) return false
        return try {
            val file = java.io.File(path)
            if (file.isFile()) true else {
                file.parentFile?.let { if (!it.isDirectory) it.mkdirs() }
                file.createNewFile() || file.isFile()
            }
        } catch (t: Throwable) {
            false
        }
    }
}