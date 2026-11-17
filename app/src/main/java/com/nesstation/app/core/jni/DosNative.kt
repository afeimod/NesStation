package com.nesstation.app.core.jni

import android.view.Surface

/**
 * JNI surface to libdoscore.so (DOSBox-Pure core for DOS/PC games).
 *
 * Pull-model interface — Kotlin owns the emulation loop and pulls frames /
 * audio on demand. The native side dlopen()s the prebuilt
 * libdosbox_pure_libretro_android.so at runtime and forwards retro_* calls.
 *
 * Unlike NES/SNES/GBA which only need a small gamepad bitfield, DOSBox requires
 * full keyboard + mouse input. This bridge therefore exposes both:
 *   - [setPad1] for the standard libretro gamepad (D-pad + A/B/X/Y + L/R)
 *   - [injectKeyDown] / [injectKeyUp] for full IBM PC keyboard input
 *   - [injectMouseMove] / [injectMouseButton] for mouse input
 *
 * ## Key codes
 * Keyboard keys use libretro's RETROK_* constants (see libretro.h).
 * The most common ones are mirrored as constants in [DosKeys] for convenience.
 *
 * ## Gamepad bit layout (port 0, RETRO_DEVICE_JOYPAD)
 *   bit0  = A      (libretro ID 0)  → dosbox_pure maps to Enter
 *   bit1  = B      (libretro ID 1)  → Esc
 *   bit2  = Select (libretro ID 2)
 *   bit3  = Start  (libretro ID 3)
 *   bit4  = Up     (libretro ID 4)
 *   bit5  = Down   (libretro ID 5)
 *   bit6  = Left   (libretro ID 6)
 *   bit7  = Right  (libretro ID 7)
 *   bit8  = L      (libretro ID 8)  → mouse left click
 *   bit9  = R      (libretro ID 9)  → mouse right click
 *   bit10 = X      (libretro ID 10) → Space
 *   bit11 = Y      (libretro ID 11) → Tab
 *   bit12 = L2     (libretro ID 12)
 *   bit13 = R2     (libretro ID 13)
 *   bit14 = L3     (libretro ID 14) → opens keyboard overlay in dosbox_pure
 *   bit15 = R3     (libretro ID 15)
 *
 * The actual mapping to DOS keys is handled by dosbox_pure's auto-mapping
 * (controlled by the `dosbox_pure_auto_mapping` core option).
 */
object DosNative {

    @Volatile private var loaded = false

    fun ensureLoaded(): Boolean {
        if (loaded) return true
        loaded = try {
            System.loadLibrary("doscore")
            // Pass the absolute path to libdosbox_pure_libretro_android.so so
            // the native dlopen() call works on all Android versions (including
            // API 21-22 where bare-name dlopen may not find app-bundled libs).
            try {
                val coreLibPath = findCoreLibPath()
                if (coreLibPath != null) setCoreLibPath(coreLibPath)
            } catch (_: Throwable) { /* best-effort — dlopen will try bare name */ }
            true
        } catch (e: UnsatisfiedLinkError) {
            android.util.Log.e("DosNative", "Failed to load libdoscore.so", e)
            false
        } catch (e: SecurityException) {
            false
        }
        return loaded
    }

    /**
     * Find the absolute path to `libdosbox_pure_libretro_android.so` in the
     * app's native library directory. Returns null if not found.
     */
    private fun findCoreLibPath(): String? {
        return try {
            val ctx = appContext ?: return null
            val nativeDir = ctx.applicationInfo.nativeLibraryDir
            val libFile = java.io.File(nativeDir, "libdosbox_pure_libretro_android.so")
            if (libFile.exists()) libFile.absolutePath else null
        } catch (_: Throwable) { null }
    }

    /** Set the absolute path to libdosbox_pure_libretro_android.so for dlopen. */
    @JvmStatic external fun setCoreLibPath(path: String)

    /** App context — set by NesApp.onCreate so DosNative can locate the lib. */
    @Volatile var appContext: android.content.Context? = null

    @JvmStatic external fun loadRom(path: String): Boolean
    @JvmStatic external fun unload()
    @JvmStatic external fun reset(hard: Boolean)
    @JvmStatic external fun runFrame()

    /** Standard libretro gamepad (port 0). See class doc for bit layout. */
    @JvmStatic external fun setPad1(bits: Int)

    /** Second controller (port 1). DOS is single-player — this is a no-op. */
    @JvmStatic external fun setPad2(bits: Int)

    /**
     * Inject a keyboard key-down event.
     * @param keyCode libretro RETROK_* constant (see [DosKeys])
     * @param modifiers bitmask of RETROKMOD_* (SHIFT/CTRL/ALT/META/NUMLOCK/CAPSLOCK/SCROLLOCK)
     */
    @JvmStatic external fun injectKeyDown(keyCode: Int, modifiers: Int)

    /** Inject a keyboard key-up event. See [injectKeyDown]. */
    @JvmStatic external fun injectKeyUp(keyCode: Int, modifiers: Int)

    /**
     * Inject a mouse move event (relative delta).
     * @param dx relative X delta in pixels
     * @param dy relative Y delta in pixels
     */
    @JvmStatic external fun injectMouseMove(dx: Int, dy: Int)

    /**
     * Inject a mouse button event.
     * @param button 0=LEFT, 1=RIGHT, 2=MIDDLE, 3=WHEEL_UP, 4=WHEEL_DOWN,
     *               5=HORIZ_WHEEL_UP, 6=HORIZ_WHEEL_DOWN, 7=BUTTON_4, 8=BUTTON_5
     * @param pressed true = button down, false = button up
     */
    @JvmStatic external fun injectMouseButton(button: Int, pressed: Boolean)

    /**
     * Set the input device mode on port 0.
     *   0 = JOYPAD (default — auto-mapped gamepad)
     *   1 = KEYBOARD-only (full keyboard, no gamepad)
     *   2 = MOUSE-only (full mouse, no gamepad)
     *   3 = JOYPAD + KEYBOARD + MOUSE (combined — recommended for touch overlay)
     */
    @JvmStatic external fun setInputDeviceMode(mode: Int)

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
     * Common dosbox_pure keys:
     *   "dosbox_pure_machine"            -> "svga_s3" | "hercules" | "cga" | "tandy" | "pcjr" | "ega" | "vgaonly" | "none"
     *   "dosbox_pure_cycles"             -> "auto" | "max" | "6000" | "10000" | "20000" | "40000" | "80000" | "custom"
     *   "dosbox_pure_cycles_max"         -> "50000" (string, used when cycles=custom)
     *   "dosbox_pure_sblaster_type"      -> "sb1" | "sb2" | "sbpro1" | "sbpro2" | "sb16" | "gb" | "none"
     *   "dosbox_pure_sblaster_adlib_mode"-> "on" | "off"
     *   "dosbox_pure_sblaster_adlib_emu" -> "default" | "cms" | "dual"
     *   "dosbox_pure_gus"                -> "off" | "on"
     *   "dosbox_pure_mouse_input"        -> "touchpad" | "auto" | "virtual" | "direct" | "off"
     *   "dosbox_pure_mouse_timeout"      -> "off" | "3" | "5" | "10"
     *   "dosbox_pure_keyboard_layout"    -> "us" | "uk" | "br" | "de" | "it" | "fr" | "ru" | "es" | ...
     *   "dosbox_pure_keyboard_delay"     -> "100" | "200" | "300" | "400" | "500"
     *   "dosbox_pure_keyboard_rate"      -> "5" | "10" | "15" | "20" | "30"
     *   "dosbox_pure_auto_mapping"       -> "on" | "off"
     *   "dosbox_pure_savestate"          -> "on" | "500" | "1000" | "2000" | "4000" | "8000" | "0"
     *   "dosbox_pure_dim_screen"         -> "off" | "5" | "10" | "20" | "30" | "60"
     *   "dosbox_pure_resolution"         -> "custom" | "640x480" | "800x600" | "1024x768" | "1280x720" | "1600x900" | "1920x1080" | "original"
     *   "dosbox_pure_scale"              -> "1" | "2" | "3" | "4" | "5"
     *   "dosbox_pure_aspect_ratio"       -> "auto" | "4:3" | "16:9" | "16:10" | "stretch"
     *   "dosbox_pure_cga_colors"         -> "default" | "amber" | "green" | "white" | "bright"
     *   "dosbox_pure_voodoo"             -> "off" | "on"
     *   "dosbox_pure_force60fps"         -> "off" | "on"
     *   "dosbox_pure_time_announce"      -> "none" | "boot" | "quiet"
     */
    @JvmStatic external fun setCoreOption(key: String, value: String)

    @JvmStatic external fun videoWidth(): Int
    @JvmStatic external fun videoHeight(): Int

    /** Video filter types (same as NES/GBA): 0=none, 1=scanline, 2=crt, 3=dot, ... */
    @JvmStatic external fun setVideoFilter(filter: Int)

    /** Control native surface buffer geometry. false=fast, true=sharp. */
    @JvmStatic external fun setHighQualityScaling(enabled: Boolean)

    /** Check whether libdosbox_pure_libretro_android.so was successfully dlopen()'d. */
    @JvmStatic external fun isCoreLibLoaded(): Boolean
}

/**
 * Libretro RETROK_* key code constants for DOSBox keyboard input.
 * These mirror the values in libretro.h (subset — most common keys).
 *
 * Use these constants with [DosNative.injectKeyDown] / [DosNative.injectKeyUp].
 *
 * The values are the same as the upstream libretro RETROK_* constants, which
 * themselves derive from SDL 1.2 keysym codes.
 */
object DosKeys {
    // Arrow keys
    const val BACKSPACE = 8
    const val TAB = 9
    const val CLEAR = 12
    const val RETURN = 13
    const val PAUSE = 19
    const val ESCAPE = 27
    const val SPACE = 32

    // Digits
    const val K0 = 48; const val K1 = 49; const val K2 = 50; const val K3 = 51; const val K4 = 52
    const val K5 = 53; const val K6 = 54; const val K7 = 55; const val K8 = 56; const val K9 = 57

    // Letters (uppercase ASCII)
    const val A = 97;  const val B = 98;  const val C = 99;  const val D = 100; const val E = 101
    const val F = 102; const val G = 103; const val H = 104; const val I = 105; const val J = 106
    const val K = 107; const val L = 108; const val M = 109; const val N = 110; const val O = 111
    const val P = 112; const val Q = 113; const val R = 114; const val S = 115; const val T = 116
    const val U = 117; const val V = 118; const val W = 119; const val X = 120; const val Y = 121
    const val Z = 122

    // Function keys
    const val F1 = 282; const val F2 = 283; const val F3 = 284; const val F4 = 285
    const val F5 = 286; const val F6 = 287; const val F7 = 288; const val F8 = 289
    const val F9 = 290; const val F10 = 291; const val F11 = 292; const val F12 = 293

    // Modifiers — values MUST match libretro.h RETROK_* constants exactly.
    // (Previous version had L/R swapped — LCTRL was 305 but RETROK_LCTRL is 306.
    // This caused modifier keys to send the wrong code, breaking Ctrl+Key combos.)
    const val RSHIFT = 303; const val LSHIFT = 304   // RETROK_RSHIFT=303, RETROK_LSHIFT=304
    const val RCTRL = 305;  const val LCTRL = 306    // RETROK_RCTRL=305,  RETROK_LCTRL=306
    const val RALT = 307;   const val LALT = 308     // RETROK_RALT=307,   RETROK_LALT=308
    const val RMETA = 309;  const val LMETA = 310    // RETROK_RMETA=309,  RETROK_LMETA=310

    // Navigation / editing
    const val CAPSLOCK = 301
    const val NUMLOCK = 300
    const val SCROLLOCK = 302
    const val INSERT = 277
    const val HOME = 278
    const val PAGEUP = 280
    const val DELETE = 127
    const val END = 279
    const val PAGEDOWN = 281
    const val RIGHT = 275
    const val LEFT = 276
    const val DOWN = 274
    const val UP = 273

    // Keypad
    const val KP0 = 256; const val KP1 = 257; const val KP2 = 258; const val KP3 = 259
    const val KP4 = 260; const val KP5 = 261; const val KP6 = 262; const val KP7 = 263
    const val KP8 = 264; const val KP9 = 265
    const val KP_PERIOD = 266
    const val KP_DIVIDE = 267
    const val KP_MULTIPLY = 268
    const val KP_MINUS = 269
    const val KP_PLUS = 270
    const val KP_ENTER = 271
    const val KP_EQUALS = 272

    // Symbols
    const val MINUS = 45
    const val EQUALS = 61
    const val LEFTBRACKET = 91
    const val RIGHTBRACKET = 93
    const val BACKSLASH = 92
    const val SEMICOLON = 59
    const val APOSTROPHE = 39
    const val GRAVE = 96
    const val COMMA = 44
    const val PERIOD = 46
    const val SLASH = 47

    // Modifier bitmasks for use as the `modifiers` argument
    object Mod {
        const val NONE = 0
        const val SHIFT = 0x003F
        const val CTRL = 0x00C0
        const val ALT = 0x0300
        const val META = 0x0C00
        const val NUMLOCK = 0x1000
        const val CAPSLOCK = 0x2000
        const val SCROLLOCK = 0x4000
    }
}
