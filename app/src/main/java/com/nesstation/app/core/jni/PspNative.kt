package com.nesstation.app.core.jni

import android.view.Surface

/**
 * JNI surface to libpspcore.so (PPSSPP — Sony PSP core host driver).
 *
 * Push-model interface — the core owns its own render/emulation thread
 * (VulkanEmuThread spawned inside libppsspp_jni.so), Kotlin only feeds it
 * the Surface, input events and lifecycle commands.
 *
 * The native side dlopen()s the prebuilt official libppsspp_jni.so (PPSSPP
 * v1.20.4) at runtime and drives it through its exported JNI contract —
 * the exact same entry points the official PpssppActivity uses:
 *
 *   NativeApp.init(...)                  — boot (boot_filename = ROM path)
 *   NativeApp.{audioConfig,audioInit}    — OpenSL audio (native thread)
 *   NativeApp.setDisplayParameters       — display metrics before init
 *   NativeApp.{keyDown,keyUp,joystickAxis,touch} — input
 *   NativeApp.{pause,resume,shutdown}    — lifecycle
 *   PpssppActivity.registerCallbacks     — frame-command host (stub)
 *   PpssppActivity.runVulkanRenderLoop   — render loop on our Surface
 *   PpssppActivity.requestExitVulkanRenderLoop — loop teardown
 *   SaveState.{GetGamePrefix,SaveSlot,LoadSlot} — instant save states
 *
 * Requirements (all provided by the app):
 *   - libppsspp_jni.so + libc++_shared.so in jniLibs (official v1.20.4 pair,
 *     all three ABIs)
 *   - vendored org.ppsspp.ppsspp Java layer (JNI contract classes)
 *   - memstick tree seeded by PspEngine (ensurePpssppEnvironment)
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
     * Absolute path of the prebuilt core to dlopen. PPSSPP ships all three
     * ABIs so the ABI-matched directory is always the right one.
     */
    private fun findCoreLibPath(): String? {
        return try {
            val ctx = appContext ?: return null
            val nativeDir = ctx.applicationInfo.nativeLibraryDir
            val libFile = java.io.File(nativeDir, "libppsspp_jni.so")
            if (libFile.exists()) libFile.absolutePath else null
        } catch (_: Throwable) { null }
    }

    @Volatile var appContext: android.content.Context? = null

    /** Set the absolute path of libppsspp_jni.so for dlopen. */
    @JvmStatic external fun setCoreLibPath(path: String)

    /**
     * Boot the core (dlopen + registerCallbacks + setDisplayParameters +
     * audioConfig + NativeApp.init + audioInit). [stub] receives the core's
     * frame commands; must be an org.ppsspp.ppsspp.PspHostStub instance.
     */
    @JvmStatic external fun boot(
        stub: Any,
        model: String, deviceType: Int, langRegion: String, apkPath: String,
        dataDir: String, extStorageDir: String, extFilesDir: String,
        nativeLibDir: String, addStorageDirs: String, cacheDir: String,
        shortcutParam: String, installerName: String, androidVersion: Int,
        board: String, displayXres: Int, displayYres: Int, displayDpi: Int,
        displayRefresh: Float, optimalFpb: Int, optimalSr: Int
    ): Boolean

    /** True when the Vulkan render loop path is active (JavaGL == false). */
    @JvmStatic external fun vulkanActive(): Boolean

    /**
     * Start/stop the Vulkan render loop. `surface == null` requests loop
     * exit (joins the core's emu thread); a new surface restarts it.
     */
    @JvmStatic external fun setSurface(surface: Surface?, width: Int, height: Int)

    @JvmStatic external fun pause()
    @JvmStatic external fun resume()

    /** Android keycode press/release (PPSSPP default Android pad mapping). */
    @JvmStatic external fun keyDown(keycode: Int): Boolean
    @JvmStatic external fun keyUp(keycode: Int): Boolean

    /** Virtual left stick, values -1..1 (AXIS_X / AXIS_Y). */
    @JvmStatic external fun setAnalog(lx: Float, ly: Float)

    /**
     * Raw touch for PPSSPP's native touch controls.
     * [code]: PPSSPP semantics — 1=MOVE, 2=DOWN, 4=UP (finger tool bits are
     * added natively).
     */
    @JvmStatic external fun onTouch(x: Float, y: Float, code: Int, pointerId: Int)

    /** Instant save/load via the core's own SaveState slots. */
    @JvmStatic external fun saveState(slot: Int): Boolean
    @JvmStatic external fun loadState(slot: Int): Boolean

    /** Fast-forward (no-op on the official so — R2 hotkey handles it). */
    @JvmStatic external fun setFastForward(on: Boolean)

    /** Teardown: exit render loop, audioShutdown, NativeApp.shutdown. */
    @JvmStatic external fun unload()

    @JvmStatic external fun lastError(): String
}
