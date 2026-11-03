package com.nesstation.app.core.jni

import android.view.Surface

/**
 * JNI surface to libsnescore.so (snes9x core).
 * Same pull-model interface as [NesNative].
 *
 * Button bit layout (12 buttons for SNES):
 *   bit0=A, bit1=B, bit2=Select, bit3=Start, bit4=Up, bit5=Down, bit6=Left, bit7=Right
 *   bit8=X, bit9=Y, bit10=L, bit11=R
 */
object SnesNative {

    @Volatile private var loaded = false

    fun ensureLoaded(): Boolean {
        if (loaded) return true
        loaded = try {
            System.loadLibrary("snescore")
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
    @JvmStatic external fun reset(hard: Boolean)
    @JvmStatic external fun runFrame()

    /** Bit layout: bit0=A..bit11=R (see class doc) */
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
     * Common snes9x keys (MUST match libretro_core_options.h exactly):
     *   "snes9x_aspect"             -> "4:3" | "uncorrected" | "auto" | "ntsc" | "pal"
     *   "snes9x_overclock"          -> "50%" to "500%" (SuperFX frequency, "100%" default)
     *   "snes9x_overclock_cycles"   -> "disabled" | "light" | "compatible" | "max"
     *   "snes9x_blargg"             -> "disabled" | "monochrome" | "rf" | "composite" | "s-video" | "rgb"
     *   "snes9x_audio_interpolation"-> "gaussian" | "cubic" | "sinc" | "none" | "linear"
     *   "snes9x_reduce_sprite_flicker" -> "disabled" | "enabled"
     *   "snes9x_gfx_clip"           -> "enabled" | "disabled" (CRITICAL for text rendering)
     *   "snes9x_gfx_transp"         -> "enabled" | "disabled" (CRITICAL for text rendering)
     *   "snes9x_gfx_hires"          -> "enabled" | "disabled"
     *   "snes9x_block_invalid_vram_access" -> "enabled" | "disabled"
     *   "snes9x_overscan"           -> "enabled" | "disabled" | "auto"
     *   "snes9x_up_down_allowed"    -> "disabled" | "enabled"
     *   "snes9x_layer_1" to "snes9x_layer_5" -> "enabled" | "disabled"
     *   "snes9x_sndchan_1" to "snes9x_sndchan_8" -> "enabled" | "disabled"
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
     * - `false`: buffer = source resolution → fast 1:1 blit + GPU upscale
     * - `true`:  buffer = display resolution → sharp C++ per-pixel scale (heavier CPU)
     */
    @JvmStatic external fun setHighQualityScaling(enabled: Boolean)
}
