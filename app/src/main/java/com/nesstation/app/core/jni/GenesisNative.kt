package com.nesstation.app.core.jni

import android.view.Surface

/**
 * JNI surface to libgenesicore.so (Genesis-Plus-GX core for SEGA MD/SMS/GG/SG/Mega-CD).
 *
 * Pull-model interface — Kotlin owns the emulation loop and pulls frames /
 * audio on demand. The native side dlopen()s the prebuilt
 * libgenesis_plus_gx_libretro_android.so at runtime and forwards retro_* calls.
 *
 * NOTE: Genesis-Plus-GX does NOT support the SEGA Saturn (SS). Saturn
 * requires a separate Saturn core (Yabause / Mednafen). The MD platform
 * covers MD / SMS / GG / SG / Mega-CD only.
 *
 * ## Gamepad bit layout (port 0, RETRO_DEVICE_JOYPAD)
 * The standard libretro JOYPAD buttons are remapped by Genesis-Plus-GX
 * to the SEGA 3/6-button controller layout:
 *   bit0  = A      → SEGA A  (commonly Jump / Weak attack)
 *   bit1  = B      → SEGA B  (commonly Attack / Medium attack)
 *   bit2  = Select → Mode    (6-button only — used by some games to switch modes)
 *   bit3  = Start  → Start
 *   bit4  = Up     bit5  = Down    bit6  = Left    bit7  = Right
 *   bit8  = X      → SEGA C  (commonly Dash / Strong attack)
 *   bit9  = Y      → SEGA X  (6-button only)
 *   bit10 = L      → SEGA Y  (6-button only)
 *   bit11 = R      → SEGA Z  (6-button only)
 *
 * For 3-button games (most pre-1993 SEGA games) only A/B/C/Start are used.
 * For 6-button games (Street Fighter II SCE, Eternal Champions, Comix Zone)
 * all six face buttons (A/B/C/X/Y/Z) are used.
 *
 * ## BIOS files
 * Mega-CD / SEGA-CD games require BIOS zip files in the system directory
 * (set via [setPaths]):
 *   - bios_CD_E.zip  — European Mega-CD BIOS (contains bios_CD_E.bin)
 *   - bios_CD_J.zip  — Japanese Mega-CD BIOS (contains bios_CD_J.bin)
 *   - bios_CD_U.zip  — US SEGA-CD BIOS (contains bios_CD_U.bin)
 *
 * Cartridge games (MD/SMS/GG/SG) do NOT require BIOS — they boot directly.
 *
 * These BIOS files have copyright and cannot be bundled with the app.
 * Users must provide them via the BIOS import UI in Settings, or by
 * manually copying the zip files to the system directory.
 */
object GenesisNative {

    @Volatile private var loaded = false

    fun ensureLoaded(): Boolean {
        if (loaded) return true
        loaded = try {
            System.loadLibrary("genesicore")
            try {
                val coreLibPath = findCoreLibPath()
                if (coreLibPath != null) setCoreLibPath(coreLibPath)
            } catch (_: Throwable) { /* best-effort */ }
            true
        } catch (e: UnsatisfiedLinkError) {
            android.util.Log.e("GenesisNative", "Failed to load libgenesicore.so", e)
            false
        } catch (e: SecurityException) {
            false
        }
        return loaded
    }

    /**
     * Find the absolute path to `libgenesis_plus_gx_libretro_android.so` in the
     * app's native library directory. Returns null if not found.
     */
    private fun findCoreLibPath(): String? {
        return try {
            val ctx = appContext ?: return null
            val nativeDir = ctx.applicationInfo.nativeLibraryDir
            val libFile = java.io.File(nativeDir, "libgenesis_plus_gx_libretro_android.so")
            if (libFile.exists()) libFile.absolutePath else null
        } catch (_: Throwable) { null }
    }

    /** Set the absolute path to libgenesis_plus_gx_libretro_android.so for dlopen. */
    @JvmStatic external fun setCoreLibPath(path: String)

    /** App context — set by NesApp.onCreate so GenesisNative can locate the lib. */
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
     * Common Genesis-Plus-GX keys (must match libretro_core_options.h):
     *   "genesis_plus_gx_region"              -> "auto" | "ntsc-u" | "pal" | "ntsc-j"
     *   "genesis_plus_gx_system"              -> "auto" | "md" | "sms" | "gg" | "sg"
     *   "genesis_plus_gx_bios"                -> "disabled" | "enabled"
     *   "genesis_plus_gx_force_dtack"         -> "enabled" | "disabled"
     *   "genesis_plus_gx_addr_error"          -> "enabled" | "disabled"
     *   "genesis_plus_gx_left_border"         -> "disabled" | "enabled" | "left"
     *   "genesis_plus_gx_aspect_ratio"        -> "auto" | "4:3" | "16:9" | "stretch"
     *   "genesis_plus_gx_render"              -> "normal" | "double" | "interlaced"
     *   "genesis_plus_gx_blargg_ntsc_filter"  -> "disabled" | "monochrome" | "rf" | "composite" | "s-video" | "rgb"
     *   "genesis_plus_gx_lcd_filter"          -> "disabled" | "enabled"
     *   "genesis_plus_gx_overscan"            -> "disabled" | "enabled"
     *   "genesis_plus_gx_gg_extra"            -> "disabled" | "enabled"
     *   "genesis_plus_gx_audio_filter"        -> "disabled" | "enabled"
     *   "genesis_plus_gx_lowpass_range"       -> "0".."100"
     *   "genesis_plus_gx_psg_preamp"          -> "0".."200"
     *   "genesis_plus_gx_fm_preamp"           -> "0".."200"
     *   "genesis_plus_gx_input"               -> "3 button" | "6 button"
     *   "genesis_plus_gx_multitap"            -> "disabled" | "enabled" | "4-way"
     *   "genesis_plus_gx_allow_up_down_allowed" -> "disabled" | "enabled"
     *   "genesis_plus_gx_cd_bios"             -> "auto" | "bios_CD_E" | "bios_CD_J" | "bios_CD_U"
     *   "genesis_plus_gx_cd_perfect_sync"     -> "disabled" | "enabled"
     *   "genesis_plus_gx_cd_fastboot"         -> "enabled" | "disabled"
     *   "genesis_plus_gx_overclock"           -> "100%" | "125%" | "150%" | "200%"
     *   "genesis_plus_gx_frameskip"           -> "0".."5"
     *   "genesis_plus_gx_sms_fm"              -> "auto" | "on" | "off"
     *   "genesis_plus_gx_gg_stretch"          -> "disabled" | "enabled"
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

    /** Video filter types (same as NES/GBA): 0=none, 1=scanline, 2=crt, 3=dot, ... */
    @JvmStatic external fun setVideoFilter(filter: Int)

    /** Control native surface buffer geometry. false=fast, true=sharp. */
    @JvmStatic external fun setHighQualityScaling(enabled: Boolean)

    /** Check whether libgenesis_plus_gx_libretro_android.so was successfully dlopen()'d. */
    @JvmStatic external fun isCoreLibLoaded(): Boolean
}
