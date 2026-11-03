package com.nesstation.app.core.jni

import android.view.Surface

/**
 * JNI surface to libpcecore.so (Geargrafx core for PC-Engine / TurboGrafx-16 /
 * SuperGrafx / PCE-CD).
 *
 * Pull-model interface — Kotlin owns the emulation loop and pulls frames /
 * audio on demand. The native side dlopen()s the prebuilt
 * libgeargrafx_libretro_android.so at runtime and forwards retro_* calls.
 *
 * Supported ROM types (per Geargrafx's retro_get_system_info):
 *   .pce  — PC-Engine / TurboGrafx-16 cartridge
 *   .sgx  — SuperGrafx cartridge
 *   .hes  — Hudson Entertainment Sound rip (audio only)
 *   .cue  — PCE-CD image (requires syscard3.pce / syscard1.pce / syscard2.pce
 *           / gexpress.pce in the system directory)
 *   .chd  — PCE-CD CHD image (same BIOS requirement)
 *
 * ## Gamepad bit layout (port 0, RETRO_DEVICE_JOYPAD)
 * The standard libretro JOYPAD buttons are mapped to the PCE controller:
 *   bit0  = A      → PCE II (commonly Jump / Shoot)
 *   bit1  = B      → PCE I  (commonly Action / Run)
 *   bit2  = Select → Select
 *   bit3  = Start  → Run (Start)
 *   bit4  = Up     bit5  = Down   bit6  = Left   bit7  = Right
 *
 * The PCE controller only has 2 face buttons (I and II) plus Select and Run.
 * Like most PCE libretro cores, Geargrafx maps SNES A → II and SNES B → I
 * (so the primary action button on the right of the pad is II).
 *
 * ## BIOS files (PCE-CD only)
 * Cartridge games (.pce/.sgx) and HES rips (.hes) do NOT require BIOS —
 * they boot directly.
 *
 * PCE-CD games (.cue/.chd) require a "System Card" BIOS in the system
 * directory (set via [setPaths]):
 *   - syscard1.pce — System Card 1
 *   - syscard2.pce — System Card 2
 *   - syscard3.pce — System Card 3 (Arcade Card Pro — most common)
 *   - gexpress.pce — Games Express BIOS (required for some adult games)
 *
 * NOTE: the Geargrafx core looks for "gexpress.pce", NOT "gameexpress.pce".
 *
 * These BIOS files have copyright and cannot be bundled with the app.
 * Users must provide them via the BIOS import UI in Settings, or by
 * manually copying the .pce files to the system directory.
 *
 * Video: Geargrafx outputs RGB565 at 256×242 (NTSC) / 256×263 (PAL) max.
 * The resampler converts the 44100 Hz / 48000 Hz core audio to 48000 Hz.
 */
object PceNative {

    @Volatile private var loaded = false

    fun ensureLoaded(): Boolean {
        if (loaded) return true
        loaded = try {
            System.loadLibrary("pcecore")
            try {
                val coreLibPath = findCoreLibPath()
                if (coreLibPath != null) setCoreLibPath(coreLibPath)
            } catch (_: Throwable) { /* best-effort */ }
            true
        } catch (e: UnsatisfiedLinkError) {
            android.util.Log.e("PceNative", "Failed to load libpcecore.so", e)
            false
        } catch (e: SecurityException) {
            false
        }
        return loaded
    }

    /**
     * Find the absolute path to `libgeargrafx_libretro_android.so` in the
     * app's native library directory. Returns null if not found.
     */
    private fun findCoreLibPath(): String? {
        return try {
            val ctx = appContext ?: return null
            val nativeDir = ctx.applicationInfo.nativeLibraryDir
            val libFile = java.io.File(nativeDir, "libgeargrafx_libretro_android.so")
            if (libFile.exists()) libFile.absolutePath else null
        } catch (_: Throwable) { null }
    }

    /** Set the absolute path to libgeargrafx_libretro_android.so for dlopen. */
    @JvmStatic external fun setCoreLibPath(path: String)

    /** App context — set by NesApp.onCreate so PceNative can locate the lib. */
    @Volatile var appContext: android.content.Context? = null

    @JvmStatic external fun loadRom(path: String): Boolean
    @JvmStatic external fun unload()
    @JvmStatic external fun reset(hard: Boolean)
    @JvmStatic external fun runFrame()

    /** Standard libretro gamepad (port 0). See class doc for bit layout. */
    @JvmStatic external fun setPad1(bits: Int)
    /** Second controller (port 1, player 2). Same bit layout as setPad1. */
    @JvmStatic external fun setPad2(bits: Int)

    @JvmStatic external fun setRegion(region: Int)
    @JvmStatic external fun setSampleRate(rate: Int)
    @JvmStatic external fun setFastForward(speed: Int)

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
     * Common Geargrafx keys (must match libretro_core_options.h):
     *   "geargrafx_console_type"          -> "Auto" | "PC Engine (JAP)" | "SuperGrafx (JAP)" | "TurboGrafx-16 (USA)"
     *   "geargrafx_backup_ram"            -> "Enabled" | "Disabled"
     *   "geargrafx_aspect_ratio"          -> "1:1 PAR" | "4:3 DAR" | "6:5 DAR" | "16:9 DAR" | "16:10 DAR"
     *   "geargrafx_overscan"              -> "disabled" | "enabled"
     *   "geargrafx_no_sprite_limit"       -> "disabled" | "enabled"
     *   "geargrafx_palette"               -> "default" | "real" | "pch" | ...
     *   "geargrafx_cdrom_type"            -> "Auto" | "Standard" | "Super CD-ROM" | "Arcade CD-ROM"
     *   "geargrafx_cdrom_bios"            -> "Auto" | "System Card 1" | "System Card 2" | "System Card 3" | "Game Express"
     *   "geargrafx_psg_volume"            -> "0".."200"
     *   "geargrafx_cdrom_volume"          -> "0".."200"
     *   "geargrafx_adpcm_volume"          -> "0".."200"
     *   "geargrafx_turbotap"              -> "disabled" | "enabled"
     *   "geargrafx_mb128"                 -> "disabled" | "enabled"
     *   "geargrafx_up_down_allowed"       -> "disabled" | "enabled"
     */
    @JvmStatic external fun setCoreOption(key: String, value: String)

    /** ★★ v69 全核心金手指：下发补丁列表（5 个 Int 一组：
     *  enabled / 地址（区域偏移或 CPU 总线地址）/ 值 / 字节数 1|2|4 / 位掩码，
     *  位掩码 0 = 整段写入）。空数组 = 清空全部。核心在每帧 runFrame 前重放
     *  补丁（RetroArch 同款逐帧 poke，游戏改写 RAM 也会被再补上）。
     *  布局见 core/jni/shared/cheat_engine.h。 */
    @JvmStatic external fun cheatSetList(list: IntArray)

    @JvmStatic external fun videoWidth(): Int
    @JvmStatic external fun videoHeight(): Int

    /** Video filter types (same as NES/GBA/MD): 0=none, 1=scanline, 2=crt, 3=dot, ... */
    @JvmStatic external fun setVideoFilter(filter: Int)

    /** Control native surface buffer geometry. false=fast, true=sharp. */
    @JvmStatic external fun setHighQualityScaling(enabled: Boolean)

    /** Check whether libgeargrafx_libretro_android.so was successfully dlopen()'d. */
    @JvmStatic external fun isCoreLibLoaded(): Boolean
}
