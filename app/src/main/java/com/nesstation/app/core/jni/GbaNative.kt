package com.nesstation.app.core.jni

import android.view.Surface

/**
 * JNI surface to libgbacore.so (mGBA core for GB/GBC/GBA).
 * Same pull-model interface as [NesNative].
 *
 * Button bit layout (10 buttons for GBA, 8 for GB/GBC):
 *   bit0=A, bit1=B, bit2=Select, bit3=Start, bit4=Up, bit5=Down, bit6=Left, bit7=Right
 *   bit8=L, bit9=R  (GBA only)
 */
object GbaNative {

    @Volatile private var loaded = false

    fun ensureLoaded(): Boolean {
        if (loaded) return true
        loaded = try {
            System.loadLibrary("gbacore")
            true
        } catch (e: UnsatisfiedLinkError) {
            false
        } catch (e: SecurityException) {
            false
        }
        return loaded
    }

    @JvmStatic external fun loadRom(path: String): Boolean
    @JvmStatic external fun unload()

    /** Flush the current battery SRAM to disk now (no-op when unloaded). */
    @JvmStatic external fun flushSave()
    @JvmStatic external fun reset(hard: Boolean)
    @JvmStatic external fun runFrame()

    /** Bit layout: bit0=A..bit9=R (see class doc) */
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

    /**
     * Deprecated TV-era hook — now returns the core's own sample rate
     * (same as [audioSampleRate]). Kept only for ABI/符号兼容。
     */
    @JvmStatic external fun audioTargetSampleRate(): Int
    @JvmStatic external fun setPaths(systemDir: String, saveDir: String)

    /**
     * Set an explicit .srm basename for the next ROM load.
     *
     * Pass a stable per-game identifier (e.g. the game's DB id, or a sanitized
     * game title) so that each game gets its own `<name>.srm` file even when
     * the ROM is loaded from a content:// URI and copied to a shared temp file
     * (cacheDir/temp_rom.<ext>). Without this, all content:// URI games would
     * share the same temp_rom.srm and clobber each other's saves.
     *
     * Must be called BEFORE [loadRom]. Pass an empty string to revert to
     * deriving the .srm name from the ROM file path.
     */
    @JvmStatic external fun setSaveName(name: String)
    @JvmStatic external fun lastError(): String

    @JvmStatic external fun setSurface(surface: Surface?)

    /**
     * Set a core option by key and value.
     * Common mGBA keys:
     *   "mgba_gb_model"             -> "Autodetect" | "Game Boy" | "Super Game Boy" | "Game Boy Color" | "Game Boy Advance"
     *   "mgba_gb_colors"            -> "enabled" | "disabled"
     *   "mgba_gb_colors_preset"     -> "default" | "gb" | "gbc" | "sgb" | "ags" | "smb" | "pocket"
     *   "mgba_gba_colors"           -> "enabled" | "disabled"
     *   "mgba_gba_colors_preset"    -> "default" | "gba" | "ags" | "spb" | "nsp"
     *   "mgba_interframe_blending"  -> "OFF" | "ON" | "fast"
     *   "mgba_frameskip"            -> "0".."10"
     *   "mgba_frameskip_type"       -> "disabled" | "auto" | "manual"
     *   "mgba_audio_resampler"      -> "nearest" | "cosine" | "cubic" | "sinc"
     *   "mgba_audio_low_pass_filter"-> "disabled" | "enabled"
     *   "mgba_sgb_borders"          -> "ON" | "OFF"
     *   "mgba_allow_opposite_directions" -> "OFF" | "ON"
     *   "mgba_gba_forceRTC"         -> "disabled" | "enabled"
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

    /**
     * Video filter types (same as NES):
     *   0=none, 1=scanline, 2=crt, 3=dot, 4=xbr, 5=hq2x, 6=hq4x,
     *   7=xbr+dot, 8=4xbr, 9=4xbr+dot, 10=hq4x+dot
     */
    @JvmStatic external fun setVideoFilter(filter: Int)

    /**
     * Control the native surface buffer geometry.
     * - `false`: buffer = source resolution (240x160) → fast 1:1 blit + GPU upscale
     * - `true`:  buffer = display resolution → sharp C++ per-pixel scale (heavier CPU)
     */
    @JvmStatic external fun setHighQualityScaling(enabled: Boolean)
}
