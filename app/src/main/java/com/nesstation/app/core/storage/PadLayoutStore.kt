package com.nesstation.app.core.storage

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import com.nesstation.app.core.model.GamePlatform

/**
 * Individual button layout — each button has its own position and size.
 * Position is stored as a fraction of the screen (0.0–1.0).
 * Size is stored in dp (diameter for round buttons, width for pill buttons).
 */
data class ButtonLayout(
    val x: Float,       // 0.0 = left edge, 1.0 = right edge (center of button)
    val y: Float,       // 0.0 = top, 1.0 = bottom (center of button)
    val sizeDp: Int     // diameter/width in dp
)

/**
 * Extra key entry for dynamically added DOS keys (letters, numbers, symbols, F-keys, etc.).
 * These are not part of the fixed DosBtnType enum and can be freely added/removed.
 * Serialized as JSON for storage in PadLayout.
 */
data class DosExtraKeyEntry(
    val keyCode: Int,       // libretro RETROK_* constant
    val label: String,      // Display label (e.g. "A", "1", "F1")
    val x: Float = 0.5f,   // 0.0–1.0 fraction of screen width
    val y: Float = 0.5f,   // 0.0–1.0 fraction of screen height
    val sizeDp: Int = 36   // diameter in dp
) {
    fun toJson(): String {
        val arr = JSONArray()
        arr.put(keyCode); arr.put(label); arr.put(x); arr.put(y); arr.put(sizeDp)
        return arr.toString()
    }

    companion object {
        fun fromJson(json: String): DosExtraKeyEntry? {
            return try {
                val arr = JSONArray(json)
                DosExtraKeyEntry(
                    keyCode = arr.getInt(0),
                    label = arr.getString(1),
                    x = arr.getDouble(2).toFloat(),
                    y = arr.getDouble(3).toFloat(),
                    sizeDp = arr.getInt(4)
                )
            } catch (_: Exception) { null }
        }

        /** Parse a JSON array string into a list of DosExtraKeyEntry. */
        fun parseList(json: String): List<DosExtraKeyEntry> {
            if (json.isBlank()) return emptyList()
            return try {
                val arr = JSONArray(json)
                (0 until arr.length()).mapNotNull { fromJson(arr.getString(it)) }
            } catch (_: Exception) { emptyList() }
        }

        /** Serialize a list of DosExtraKeyEntry into a JSON array string. */
        fun formatList(entries: List<DosExtraKeyEntry>): String {
            val arr = JSONArray()
            entries.forEach { arr.put(it.toJson()) }
            return arr.toString()
        }
    }
}

/**
 * Complete on-screen controller layout with per-button positioning.
 * Every button can be individually positioned and resized.
 * Includes L/R shoulder buttons (GBA/SNES) and X/Y face buttons (SNES).
 *
 * IMPORTANT: 横屏 / 竖屏的按键位置互相独立，互不干扰。
 *   - landscape 布局：横向玩游戏时手柄排布（dpad 左下，A/B 右下，L/R 顶部）
 *   - portrait  布局：竖屏玩游戏时手柄排布（默认值适合竖屏，可单独调整）
 * 用户在横屏编辑手柄位置后切到竖屏，竖屏布局保持自己原来的设置；反之亦然。
 * 全局设置（透明度、是否显示手柄、核心选项等）两个方向共享。
 */
class PadLayout {
    // === 横屏布局（landscape） ===
    var dpad: ButtonLayout = ButtonLayout(x = 0.13f, y = 0.78f, sizeDp = 140)
    var btnA: ButtonLayout = ButtonLayout(x = 0.87f, y = 0.76f, sizeDp = 72)
    var btnB: ButtonLayout = ButtonLayout(x = 0.72f, y = 0.82f, sizeDp = 72)
    var btnTurboA: ButtonLayout = ButtonLayout(x = 0.87f, y = 0.60f, sizeDp = 48)
    var btnTurboB: ButtonLayout = ButtonLayout(x = 0.72f, y = 0.66f, sizeDp = 48)
    var btnStart: ButtonLayout = ButtonLayout(x = 0.62f, y = 0.92f, sizeDp = 56)
    var btnSelect: ButtonLayout = ButtonLayout(x = 0.38f, y = 0.92f, sizeDp = 56)
    var btnL: ButtonLayout = ButtonLayout(x = 0.10f, y = 0.15f, sizeDp = 56)
    var btnR: ButtonLayout = ButtonLayout(x = 0.90f, y = 0.15f, sizeDp = 56)
    var btnX: ButtonLayout = ButtonLayout(x = 0.88f, y = 0.54f, sizeDp = 60)
    var btnY: ButtonLayout = ButtonLayout(x = 0.73f, y = 0.60f, sizeDp = 60)
    // 即时存档 / 即时读档按钮（所有核心通用，横屏位置）
    var btnQuickSave: ButtonLayout = ButtonLayout(x = 0.42f, y = 0.18f, sizeDp = 40)
    var btnQuickLoad: ButtonLayout = ButtonLayout(x = 0.58f, y = 0.18f, sizeDp = 40)
    // === 竖屏布局（portrait）—— 默认值给竖屏一个更舒服的排布 ===
    // dpad 放左下、A/B 放右下，跟横屏差不多但 y 坐标稍微上移避开屏幕底部
    var dpadP: ButtonLayout = ButtonLayout(x = 0.18f, y = 0.74f, sizeDp = 130)
    var btnAP: ButtonLayout = ButtonLayout(x = 0.82f, y = 0.72f, sizeDp = 68)
    var btnBP: ButtonLayout = ButtonLayout(x = 0.68f, y = 0.80f, sizeDp = 68)
    var btnTurboAP: ButtonLayout = ButtonLayout(x = 0.82f, y = 0.56f, sizeDp = 46)
    var btnTurboBP: ButtonLayout = ButtonLayout(x = 0.68f, y = 0.62f, sizeDp = 46)
    var btnStartP: ButtonLayout = ButtonLayout(x = 0.62f, y = 0.90f, sizeDp = 54)
    var btnSelectP: ButtonLayout = ButtonLayout(x = 0.38f, y = 0.90f, sizeDp = 54)
    var btnLP: ButtonLayout = ButtonLayout(x = 0.12f, y = 0.12f, sizeDp = 54)
    var btnRP: ButtonLayout = ButtonLayout(x = 0.88f, y = 0.12f, sizeDp = 54)
    var btnXP: ButtonLayout = ButtonLayout(x = 0.83f, y = 0.50f, sizeDp = 56)
    var btnYP: ButtonLayout = ButtonLayout(x = 0.69f, y = 0.56f, sizeDp = 56)
    // 即时存档 / 即时读档按钮（所有核心通用，竖屏位置）
    var btnQuickSaveP: ButtonLayout = ButtonLayout(x = 0.42f, y = 0.18f, sizeDp = 38)
    var btnQuickLoadP: ButtonLayout = ButtonLayout(x = 0.58f, y = 0.18f, sizeDp = 38)
    // === 全局设置（横竖屏共享） ===
    var opacity: Float = 0.7f     // 0.3 – 1.0（通用核心虚拟按键透明度，可调）
    var showPad: Boolean = true
    // 全局 FPS 显示（所有平台通用）：开启后游戏画面左上角叠加实时帧率。
    // 帧率来自各引擎模拟线程的 onFrame 回调（每模拟帧 +1），
    // 可用于诊断 NDS 等核心的性能表现。
    var showFps: Boolean = false
    // 1P/2P/3P/4P 玩家切换悬浮球：可拖动、可隐藏的小圆形按钮。
    // 位置为归一化坐标（相对游戏区域容器），横竖屏共用。
    var showPlayerSwitch: Boolean = true
    var playerSwitchX: Float = 0.94f
    var playerSwitchY: Float = 0.07f
    // Core options — values MUST match FCEUmm's libretro_core_options.h
    var ntscFilter: String = "disabled"  // disabled | composite | svideo | rgb | monochrome
    var aspectRatio: String = "4:3"  // SNES9x: "4:3" | "uncorrected" | "auto" | "ntsc" | "pal"
    var palette: String = "default"      // default | dq | nx | asq | rp2 | ...
    var region: String = "Auto"          // Auto | NTSC | PAL | Dendy
    var soundQuality: String = "Low"     // Low | High | Very High
    var cropOverscan: String = "disabled"// disabled | enabled  (maps to 4 individual overscan keys)
    // Video scaling — controls SurfaceView layout aspect ratio (frontend-level, not FCEUmm option)
    // "custom" = free-form rect controlled by the 4-corner drag editor
    var videoScale: String = "stretch"   // stretch | 4:3 | 8:7 | 16:9 | custom
    // Custom free-form layout rect (normalized 0..1, relative to the game
    // surface container). Used when videoScale == "custom": left/top is the
    // top-left corner, right/bottom is the bottom-right corner. The user
    // drags the 4 corners to resize and the rectangle body to move.
    var customLayoutLeft: Float = 0f
    var customLayoutTop: Float = 0f
    var customLayoutRight: Float = 1f
    var customLayoutBottom: Float = 1f
    // 竖屏版自由布局矩形：横竖屏分别保存，旋转屏幕时不互相覆盖
    //（与手柄按钮 padLayoutP 等竖屏字段的 p_ 前缀命名保持一致）。
    var customLayoutLeftP: Float = 0f
    var customLayoutTopP: Float = 0f
    var customLayoutRightP: Float = 1f
    var customLayoutBottomP: Float = 1f
    // Video filter — applied in the native blit function (frontend-level post-processing)
    var videoFilter: String = "none"     // none | scanline | crt | dot | xbr | hq2x | hq4x | xbr_dot
    // Overclocking — adds dummy scanlines to the PPU loop, reducing slowdowns
    var overclocking: String = "disabled" // disabled | 2x-Postrender | 2x-VBlank
    // --- SFC/SNES (snes9x) specific options ---
    var sfcReduceSpriteFlicker: String = "disabled"  // disabled | enabled
    var sfcReduceSlowdown: String = "disabled"       // disabled | light | compatible | max
    var sfcAudioInterpolation: String = "gaussian"   // gaussian | cubic | sinc | none | linear
    var sfcGfxTransparency: String = "enabled"       // enabled | disabled
    var sfcGfxHires: String = "enabled"              // enabled | disabled
    var sfcGfxClip: String = "enabled"               // enabled | disabled
    var sfcBlockInvalidVram: String = "disabled"      // disabled(allow) | enabled(block) — allow by default to fix font garbling
    var sfcSoundOutput: String = "disabled"           // disabled | enabled (echo buffer hack)
    var sfcOverscan: String = "enabled"              // enabled | disabled | auto
    var sfcSideBySide: String = "disabled"            // disabled | merge | blur (hires blend)
    var sfcUpDownAllowed: String = "disabled"        // disabled | enabled
    var sfcSuperScope: String = "disabled"            // disabled | enabled (randomize memory)
    var sfcLayer1: String = "enabled"                // BG layer 1
    var sfcLayer2: String = "enabled"                // BG layer 2
    var sfcLayer3: String = "enabled"                // BG layer 3
    var sfcLayer4: String = "enabled"                // BG layer 4
    var sfcLayer5: String = "enabled"                // OBJ/sprite layer
    var sfcOverclock: String = "100%"                // 50%-500% (SuperFX frequency)
    // --- GB/GBA (mGBA) specific options ---
    var gbColorCorrection: String = "enabled"        // enabled | disabled
    var gbcColorPreset: String = "default"           // default | various presets
    var gbaColorCorrection: String = "enabled"       // enabled | disabled
    var gbaColorPreset: String = "default"           // default | various presets
    var gbaFrameBlending: String = "OFF"             // OFF | ON | fast
    var gbaAudioResampler: String = "sinc"         // sinc | nearest | cosine | cubic
    var gbaAudioLowPass: String = "disabled"         // disabled | enabled (mGBA engine default: disabled)
    var gbaAudioLowPassRange: String = "60"          // 0-100 (mGBA engine default: 60)
    var gbaFrameskipType: String = "disabled"        // disabled | auto | fixed
    var gbaFrameskipCount: String = "0"              // 0-10
    var gbaSolarSensor: String = "0"                 // 0-10
    var gbaIdleOptimization: String = "disabled"     // disabled | enabled (GBA only)
    var gbaForceRTC: String = "disabled"             // disabled | enabled
    var gbaAllowOpposite: String = "OFF"             // OFF | ON
    // --- Additional GB/GBA (mGBA) options ---
    var gbModel: String = "Autodetect"               // Autodetect | Game Boy | Super Game Boy | Game Boy Color | Game Boy Advance
    var gbSgbBorders: String = "ON"                  // ON | OFF
    var gbaFrameskipThreshold: String = "33"         // 0-100 (audio buffer threshold for auto frameskip)
    // --- DOSBox-Pure (DOS) specific options ---
    var dosMachine: String = "svga_s3"               // svga_s3 | vesa_nolfb | vesa_oldvbe | svga_et3000 | svga_et4000 | svga_paradise | vgaonly | ega | cga | tandy | pcjr | hercules（注意 "none" 非法已移除）
    var dosCycles: String = "auto"                    // auto | max | 6000 | 10000 | 20000 | 40000 | 80000 | custom
    var dosCyclesMax: String = "50000"                // string (used when dosCycles = custom)
    var dosSbType: String = "sb16"                    // sb1 | sb2 | sbpro1 | sbpro2 | sb16 | gb | none
    var dosSbAdlibMode: String = "off"                // on | off
    var dosSbAdlibEmu: String = "default"             // default | cms | dual
    var dosGus: String = "off"                        // off | on
    // === 音频：核心自带混音器相关 ===
    // 音频输出已统一为「核心默认采样率直通」（无 TV 兼容模式选项）。
    var dosAudiorate: String = "48000"                // dosbox_pure_audiorate: 48000 | 44100 | 32000 | 22050 | 11025 | 8000 | 49716
    var dosSwapStereo: String = "false"               // false | true (立体声反转)
    var dosTandySound: String = "auto"                // auto | on | off (Tandy 声卡)
    // === CPU / 内存（本预编译核心真实支持的选项）===
    var dosCpuCore: String = "auto"                   // auto | dynamic | normal | simple
    var dosCpuType: String = "auto"                   // auto | 386 | 386_slow | 386_prefetch | 486_slow | pentium_slow
    var dosMemorySize: String = "16"                  // 4 | 8 | 16 | 24 | 32 (MB)
    var dosMouseInput: String = "touchpad"            // touchpad | auto | virtual | direct | off
    var dosKeyboardLayout: String = "us"              // us | uk | br | de | it | fr | ru | es | ...
    var dosAutoMapping: String = "on"                 // on | off
    var dosSavestate: String = "on"                   // on | 500 | 1000 | 2000 | 4000 | 8000 | 0
    // === 视频补充（dosbox_pure_aspect_correction / cga 真实键）===
    var dosAspectCorrection: String = "false"         // false | true (CRT 宽高比修正)
    var dosCgaMode: String = "early_auto"             // early_auto | early_on | early_off | late_auto | late_on | late_off
    var dosVoodoo: String = "off"                     // off | on
    var dosForce60fps: String = "on"                  // off | on
    // DOS on-screen controller mode: "gamepad" (circular buttons, transparent)
    // or "keyboard" (full QWERTY layout). Switchable at runtime via a button.
    var dosInputMode: String = "gamepad"              // gamepad | keyboard
    // J2ME on-screen controller mode: "gamepad" (D-pad + A/B/X/Y/Start/Select)
    // or "phone" (12-key numeric keypad + soft keys). Switchable at runtime
    // via a button in the J2me overlay.
    var javaInputMode: String = "gamepad"             // gamepad | phone
    // J2ME 虚拟按键透明度（0.3–1.0，默认 0.8）。旧版直接复用全局 opacity
    // 再打对折（0.7*0.5=0.35），方向键深色底在深色游戏画面上几乎看不清 ——
    // 现在独立成键，默认提亮，并且可在布局编辑器/设置里调节。
    var javaOpacity: Float = 0.8f     // 0.3 – 1.0（J2ME 专用，不影响其他核心）
    // J2ME screen scale type: how the virtual MIDlet screen is scaled to fit
    // the device display. "stretch" = fill (may distort), "fit" = keep
    // aspect ratio with letterboxing, "center" = native resolution centered.
    var javaScaleType: String = "fit"                 // stretch | fit | center
    // J2ME FPS counter: show real-time FPS overlay inside the MIDlet screen.
    var javaShowFps: Boolean = false
    // J2ME immediate mode: when enabled, the MIDlet redraws immediately on
    // repaint() instead of waiting for the next frame. Better for interactive
    // menus but can cause flicker in fast games.
    var javaImmediateMode: Boolean = false
    // J2ME 虚拟分辨率（游戏内 Canvas 的逻辑分辨率，即 MIDlet 看到的屏幕尺寸）。
    // "default" = 不干预（使用每游戏 config.json 里的值）；
    // "auto"    = 跟随设备屏幕 (setVirtualSize(-1,-1))；
    // 其他值形如 "240x320" = 强制指定逻辑分辨率。
    var javaResolution: String = "default"
    // J2ME 画面缩放比例（百分比）。在 scaleType=0(原始分辨率) 时可放大/缩小
    // 画面；fit/stretch 模式下 >100 会被核心自动截到 100。
    var javaScaleRatio: String = "100"
    // J2ME 帧率限制（0 = 不限制）。对应 Canvas.setLimitFps。
    var javaFpsLimit: String = "0"
    // J2ME 触摸输入支持：控制 MIDlet hasPointerEvents() 的返回值。
    // 部分老游戏检测到触摸支持后会切换成触屏 UI，关闭可强制键盘 UI。
    var javaTouchInput: Boolean = true
    // J2ME 数字键兼作方向键：开启后虚拟键盘上的 2/4/6/8/5 在发送数字键的
    // 同时追加发送 上/左/右/下/确认 键（真机行为，兼容直接比较 KEY_UP 等
    // 原始常量的游戏）。手柄模式的 "123" 数字小键盘同样生效。
    var javaNumDualDispatch: Boolean = true
    // J2ME 虚拟按键 → 手机按键 映射表（仅记录被用户改过的项）。
    // 格式：逗号分隔的 "键位=MIDP键码"，如 "a=-5,b=-6,y=42"。
    // 键位取值见 JAVA_MAPPABLE_BUTTONS；空串 = 全部使用默认映射。
    var javaButtonKeyMap: String = ""
    // === 遮罩 / 按键主题（每核心独立，见 OverlayTheme.kt） ===
    // JSON blob：{ "NES": {...}, "SFC": {...}, ... }，由 PadLayoutStore 的
    // overlayThemeGet/Set 系列函数读写。这里只存一个字符串，避免为每个
    // 核心各加一组颜色字段（也能绕开 DEX 255 寄存器限制）。
    var overlayThemeJson: String = ""
    // J2ME 手机键盘模式布局：数字键盘（4行×3列）的中心位置与单键尺寸。
    var javaPhoneGrid: ButtonLayout = ButtonLayout(x = 0.5f, y = 0.80f, sizeDp = 48)
    // J2ME 手机键盘模式布局：顶部功能键行（L/F/R/C）的中心位置与单键尺寸。
    var javaPhoneTop: ButtonLayout = ButtonLayout(x = 0.5f, y = 0.60f, sizeDp = 40)
    // === DOS gamepad overlay button positions (landscape) ===
    // Each button has x/y (0.0-1.0 of screen) and sizeDp.
    // dosBtnEnabled controls whether the button is shown (user can hide/add).
    var dosDpad: ButtonLayout = ButtonLayout(x = 0.13f, y = 0.78f, sizeDp = 140)
    var dosBtnEsc: ButtonLayout = ButtonLayout(x = 0.87f, y = 0.62f, sizeDp = 56)
    var dosBtnEnter: ButtonLayout = ButtonLayout(x = 0.92f, y = 0.76f, sizeDp = 56)
    var dosBtnSpace: ButtonLayout = ButtonLayout(x = 0.78f, y = 0.82f, sizeDp = 56)
    var dosBtnTab: ButtonLayout = ButtonLayout(x = 0.87f, y = 0.92f, sizeDp = 56)
    var dosBtnCtrl: ButtonLayout = ButtonLayout(x = 0.30f, y = 0.92f, sizeDp = 48)
    var dosBtnAlt: ButtonLayout = ButtonLayout(x = 0.42f, y = 0.92f, sizeDp = 48)
    var dosBtnShift: ButtonLayout = ButtonLayout(x = 0.54f, y = 0.92f, sizeDp = 48)
    var dosBtnBack: ButtonLayout = ButtonLayout(x = 0.66f, y = 0.92f, sizeDp = 48)
    var dosBtnMouseL: ButtonLayout = ButtonLayout(x = 0.92f, y = 0.40f, sizeDp = 40)
    var dosBtnMouseR: ButtonLayout = ButtonLayout(x = 0.82f, y = 0.40f, sizeDp = 40)
    // === DOS gamepad overlay button positions (portrait - independent) ===
    var dosDpadP: ButtonLayout = ButtonLayout(x = 0.18f, y = 0.74f, sizeDp = 130)
    var dosBtnEscP: ButtonLayout = ButtonLayout(x = 0.82f, y = 0.58f, sizeDp = 52)
    var dosBtnEnterP: ButtonLayout = ButtonLayout(x = 0.88f, y = 0.72f, sizeDp = 52)
    var dosBtnSpaceP: ButtonLayout = ButtonLayout(x = 0.74f, y = 0.80f, sizeDp = 52)
    var dosBtnTabP: ButtonLayout = ButtonLayout(x = 0.82f, y = 0.90f, sizeDp = 52)
    var dosBtnCtrlP: ButtonLayout = ButtonLayout(x = 0.30f, y = 0.92f, sizeDp = 46)
    var dosBtnAltP: ButtonLayout = ButtonLayout(x = 0.42f, y = 0.92f, sizeDp = 46)
    var dosBtnShiftP: ButtonLayout = ButtonLayout(x = 0.54f, y = 0.92f, sizeDp = 46)
    var dosBtnBackP: ButtonLayout = ButtonLayout(x = 0.66f, y = 0.92f, sizeDp = 46)
    var dosBtnMouseLP: ButtonLayout = ButtonLayout(x = 0.92f, y = 0.36f, sizeDp = 38)
    var dosBtnMouseRP: ButtonLayout = ButtonLayout(x = 0.82f, y = 0.36f, sizeDp = 38)
    // === DOS extra buttons (addable via editor, hidden by default) ===
    var dosBtnInsert: ButtonLayout = ButtonLayout(x = 0.08f, y = 0.40f, sizeDp = 40)
    var dosBtnDelete: ButtonLayout = ButtonLayout(x = 0.08f, y = 0.50f, sizeDp = 40)
    var dosBtnHome: ButtonLayout = ButtonLayout(x = 0.08f, y = 0.30f, sizeDp = 40)
    var dosBtnEnd: ButtonLayout = ButtonLayout(x = 0.08f, y = 0.60f, sizeDp = 40)
    var dosBtnPageUp: ButtonLayout = ButtonLayout(x = 0.08f, y = 0.20f, sizeDp = 40)
    var dosBtnPageDown: ButtonLayout = ButtonLayout(x = 0.08f, y = 0.70f, sizeDp = 40)
    // Extra button portrait positions
    var dosBtnInsertP: ButtonLayout = ButtonLayout(x = 0.10f, y = 0.36f, sizeDp = 38)
    var dosBtnDeleteP: ButtonLayout = ButtonLayout(x = 0.10f, y = 0.46f, sizeDp = 38)
    var dosBtnHomeP: ButtonLayout = ButtonLayout(x = 0.10f, y = 0.26f, sizeDp = 38)
    var dosBtnEndP: ButtonLayout = ButtonLayout(x = 0.10f, y = 0.56f, sizeDp = 38)
    var dosBtnPageUpP: ButtonLayout = ButtonLayout(x = 0.10f, y = 0.16f, sizeDp = 38)
    var dosBtnPageDownP: ButtonLayout = ButtonLayout(x = 0.10f, y = 0.66f, sizeDp = 38)
    // === DOS button visibility toggles (which buttons are shown) ===
    var dosShowDpad: Boolean = true
    var dosShowEsc: Boolean = true
    var dosShowEnter: Boolean = true
    var dosShowSpace: Boolean = true
    var dosShowTab: Boolean = true
    var dosShowCtrl: Boolean = true
    var dosShowAlt: Boolean = true
    var dosShowShift: Boolean = true
    var dosShowBack: Boolean = true
    var dosShowMouseL: Boolean = true
    var dosShowMouseR: Boolean = true
    // Extra button visibility (hidden by default — user adds them via editor)
    var dosShowInsert: Boolean = false
    var dosShowDelete: Boolean = false
    var dosShowHome: Boolean = false
    var dosShowEnd: Boolean = false
    var dosShowPageUp: Boolean = false
    var dosShowPageDown: Boolean = false
    // --- Dynamic extra keys (letters, numbers, symbols, F-keys, etc.) ---
    // JSON-encoded list of DosExtraKeyEntry — can be freely added/removed by the user.
    var dosExtraKeys: String = ""          // landscape extra keys (JSON)
    var dosExtraKeysP: String = ""         // portrait extra keys (JSON)
    // --- Display orientation ---
    var screenOrientation: String = "sensor"          // sensor | landscape | portrait
    // --- Performance ---
    // When true, the native surface buffer matches the source resolution
    // (256x240 / 240x160) and the Android hardware compositor does GPU
    // upscaling — fast on TV/low-end devices but slightly softer image.
    // When false, the native buffer matches the display resolution and
    // the C++ blit does per-pixel nearest-neighbor scaling — sharper but
    // much heavier on CPU (can cause lag on low-power devices).
    var highQualityScaling: Boolean = false           // false = native-res buffer (fast), true = display-res buffer (sharp)

    // === FBNeo (Arcade) core options ===
    // Keys must match fbneo's libretro_core_options.h exactly.
    var arcadeAspect: String = "auto"                  // auto | 4:3 | 3:4 | 16:9 | 16:15
    var arcadeRotate: String = "norotate"              // norotate | cw | ccw | flip
    var arcadeVerticalMode: String = "disabled"        // disabled | enabled
    var arcadeCropOverscan: String = "enabled"         // enabled | disabled
    var arcadeCpuSpeed: String = "100"                 // 100 | 75 | 50 | 150 | 200 | 250
    var arcadeFrameskip: String = "0"                  // 0..10
    var arcadeForce60hz: String = "disabled"           // disabled | enabled
    var arcadeSampleRate: String = "48000"             // 48000 | 44100 | 22050
    var arcadeAudioInterp: String = "2"                // 0=off 1=nearest 2=linear 3=cubic
    var arcadeLowpass: String = "disabled"             // disabled | enabled
    var arcadeNeogeomode: String = "MVS"               // MVS | AES
    var arcadeMemcard: String = "enabled"              // enabled | disabled

    // === Genesis-Plus-GX (MD/SEGA) core options ===
    // Keys must match genesis_plus_gx's libretro_core_options.h exactly.
    var mdRegion: String = "auto"                      // auto | ntsc-u | pal | ntsc-j
    var mdSystem: String = "auto"                      // auto | md | sms | gg | sg
    var mdAspect: String = "auto"                      // auto | 4:3 | 16:9 | stretch
    var mdRender: String = "normal"                    // normal | double | interlaced
    var mdNtscFilter: String = "disabled"              // disabled | monochrome | rf | composite | s-video | rgb
    var mdLcdFilter: String = "disabled"               // disabled | enabled
    var mdOverscan: String = "disabled"                // disabled | enabled
    var mdGgExtra: String = "disabled"                 // disabled | enabled (GG extended screen)
    var mdLeftBorder: String = "disabled"              // disabled | enabled
    var mdInput: String = "6 button"                   // 3 button | 6 button
    var mdAllowUpDown: String = "disabled"             // disabled | enabled
    var mdOverclock: String = "100%"                   // 100% | 125% | 150% | 200%
    var mdFrameskip: String = "0"                      // 0..5
    var mdCdFastboot: String = "enabled"               // enabled | disabled
    var mdSmsFm: String = "auto"                       // auto | on | off (SMS FM sound)
    var mdGgStretch: String = "disabled"               // disabled | enabled (Game Gear stretch)

    // === Geargrafx (PCE/TG16) core options ===
    // Keys AND values must match geargrafx's libretro_core_options.h exactly.
    // Geargrafx uses case-sensitive strcmp() to compare option values, so
    // "disabled" (lowercase) will NOT match "Disabled" and the option is
    // ignored. Values below are copied from the reference source's defaults.
    var pceConsoleType: String = "Auto"                // Auto | PC Engine (JAP) | SuperGrafx (JAP) | TurboGrafx-16 (USA)
    var pceAspect: String = "4:3 DAR"                  // 1:1 PAR | 4:3 DAR | 6:5 DAR | 16:9 DAR | 16:10 DAR
    var pceOverscan: String = "Disabled"               // Disabled | Enabled
    var pceNoSpriteLimit: String = "Disabled"          // Disabled | Enabled
    var pcePalette: String = "Standard RGB"            // Standard RGB | Turboxray | Kitrinx
    var pceCdromBios: String = "Auto"                  // Auto | System Card 1 | System Card 2 | System Card 3 | Game Express
    var pceTurbotap: String = "Disabled"               // Disabled | Enabled (5-player multitap)
    var pceMb128: String = "Auto"                      // Auto | Enabled | Disabled (Memory Base 128 save)
    var pceAllowUpDown: String = "Disabled"            // Disabled | Enabled

    // === NDS (melonDS) core options ===
    // Values must match the prebuilt melonDS libretro core (v1.1) exactly.
    // ndsUseFwBios / ndsFiltering / ndsScreensaver /
    // ndsMouseSpeed have NO matching option in the core — they are kept only for
    // storage compatibility and are no longer forwarded to the core.
    var ndsUseFwBios: String = "enabled"
    var ndsConsoleMode: String = "DS"                  // DS | DSi (anything except "DSi" = DS)
    var ndsScreenLayout: String = "Top/Bottom"         // Top/Bottom | Bottom/Top | Left/Right | Right/Left | Top Only | Bottom Only | Hybrid Top | Hybrid Bottom
    // 3D 渲染分辨率倍数 (melonds_opengl_resolution): 值格式必须匹配核心 libretro_core_options.h
    // 仅 OpenGL 渲染器生效，软件渲染器忽略此设置
    var ndsResolution: String = "1x native (256x192)"
    // OpenGL 渲染器：启用后使用硬件加速 3D 渲染，分辨率缩放仅在此模式下生效
    // 参考 melonDS 2.0.1 GH 官方 APK — 已实现硬件加速 3D 渲染，性能远胜软件渲染。
    // nds_loader.cpp 的 createEglContext + ensureEglContextCurrent 链路已就绪，
    // RETRO_ENVIRONMENT_SET_HW_RENDER 回调里会建立 EGL 上下文并调用 core context_reset。
    // 默认开启硬件加速，只有设备确实无 GLES2 才会自动回退到软件渲染。
    var ndsOpenGlRenderer: String = "enabled"           // enabled | disabled
    // OpenGL 多边形优化：改善多边形分割，减少图形错误
    var ndsOpenGlBetterPolygons: String = "disabled"   // enabled | disabled
    // OpenGL 纹理过滤：nearest(最近邻/锐利) | linear(线性/平滑)
    var ndsOpenGlFiltering: String = "nearest"         // nearest | linear
    var ndsFiltering: String = "nearest"
    var ndsScreensaver: String = "disabled"
    var ndsTouchMode: String = "Touch"                  // Touch | Mouse | Joystick
    var ndsMouseSpeed: String = "100"
    var ndsDsiSdcard: String = "disabled"              // disabled | enabled (DSi mode SD card)
    var ndsRandomizeMac: String = "disabled"           // disabled | enabled (randomize MAC for online play)
    var ndsJitEnable: String = "enabled"               // enabled | disabled (JIT compiler)
    var ndsAudioInterpolation: String = "Cosine"       // Cosine | Linear | Sinc | None
    var ndsUseFwSettings: String = "disabled"          // enabled | disabled (use firmware settings)
    var ndsScreenGap: String = "0"                     // 0..20 两屏间距
    // NDS 存档方式切换（旧版仅 NDS；已升级为下方全局 globalSaveMode）：
    //   "nesstation"  → 用 NesStation 自带统一存档目录 (<filesDir>/saves/<gameId>.sav)
    //                    saveName = game.id，每个游戏独立 .sav 文件，content:// URI
    //                    复制到 temp_rom.<ext> 也不会被覆盖。
    //   "core_builtin" → ROM 同目录同名 .sav（<ROM 所在目录>/<ROM 文件名>.sav），
    //                    与官方 melonDS APK 的存档行为完全一致——直接读取并回写
    //                    ROM 旁边的同名 .sav。若 ROM 目录不可写（未授权所有文件
    //                    访问），自动回退到应用内部目录，仍按 ROM 文件名命名。
    // ⚠ 已废弃（仅保留存储兼容）：UI 已改用全局 globalSaveMode，加载时自动迁移。
    var ndsSaveMode: String = "nesstation"             // nesstation | core_builtin

    // === 全局存档方式（适用于所有带电池存档的核心）===
    // 其他核心也有 .sav / .srm 存档，所以该开关放在全局设置：
    //   "nesstation"  → 统一存档目录 <filesDir>/saves/<gameId>.srm（NDS 为 .sav）
    //   "core_builtin" → ROM 同目录同名 .srm/.sav（与常见模拟器/RetroArch 交换兼容）
    var globalSaveMode: String = "nesstation"           // nesstation | core_builtin

    // === 主页个性化（FSD 桌面）===
    // 主页背景：空 = 默认深蓝壁纸；非空 = SAF 持久 URI（图片或视频）。
    var homeBackgroundUri: String = ""
    var homeBackgroundIsVideo: Boolean = false
    // 主页磁贴自定义图标：JSON map { tileKey → 图标文件绝对路径 }，
    // 图标已拷贝到 filesDir/icons，路径稳定可直接 BitmapFactory 解码。
    var homeTileIcons: String = ""
    // 磁贴自定义图标透明度：JSON map { tileKey → Float 0.05..1.0 }，缺省 1.0 不透明。
    // 与 homeTileIcons 分键存储，互不影响；仅对已设置自定义图标的磁贴有意义。
    var homeTileIconAlphas: String = ""
    var ndsSwapscreenMode: String = "Toggle"            // Toggle | Hold (换屏按钮模式)
    var ndsMicInput: String = "Blow Noise"              // Blow Noise | White Noise (麦克风输入类型)
    var ndsLanguage: String = "English"                  // Japanese | English | French | German | Italian | Spanish
    var ndsAudioBitrate: String = "Automatic"            // Automatic | 10-bit | 16-bit
    var ndsJitBlockSize: String = "32"                  // 1..32 JIT 块大小（与 melonDS 核心默认一致）
    var ndsJitFastMemory: String = "enabled"             // enabled | disabled
    var ndsJitBranchOptimisations: String = "enabled"    // enabled | disabled
    var ndsJitLiteralOptimisations: String = "enabled"   // enabled | disabled
    var ndsHybridSmallScreen: String = "Bottom"          // Bottom | Top | Duplicate

    // === DraStic（激烈）核心 ===
    // 仅激烈核心生效（melonDS 忽略）。drastic_volume 取值 0..100，
    // 运行时经 DraSticEngine.setCoreOption → DraSticJNI.setAudioVolume 应用。
    var ndsDrasticVolume: String = "100"

    // 以下激烈核心设置全部对照原版 DraStic r2.6.0.4a APK（jadx 反编译
    // f0.h 的 SharedPreferences 读写 + libdrastic_arm64.so 消费链路验证），
    // config 位域布局见 DraSticEngine.packConfig 的注释。
    // 默认值 = 原版 App 的出厂默认 —— 保证默认配置字与原版 f0.h.n()
    // 逐位一致（3D 渲染完整性的前提：模拟线程数按核数自动 1/2/3，
    // 高清渲染默认关，音频延迟默认"极高"等）。

    // 声音开关（config bit31，_SoundEnabled，原版默认开）。
    var ndsDrasticSound: String = "enabled"

    // 高清渲染（config bit41，_Hires3D）：开启后原生以 512×384（2x）内部分辨率
    // 渲染并强制 GL 显示路径；关闭则 256×192（原版默认）。
    // 需重进游戏生效（分辨率在 startGame 路径初始化）。
    var ndsDrasticHdRender: String = "disabled"

    // 跳帧类型（bits 5-7，_FrameskipType）：none=关闭 / manual=手动 / auto=自动。
    var ndsDrasticFrameskipType: String = "none"

    // 跳帧值 0-9（bits 0-3，_FrameskipValue，原版默认 4；仅手动跳帧使用）。
    var ndsDrasticFrameskipValue: String = "4"

    // 安全跳帧（bit 47，_FrameskipSafe，原版默认关）。
    var ndsDrasticFrameskipSafe: String = "disabled"

    // 多线程 3D 渲染（bit 28，_Threaded3D，原版默认关）：提升 3D 大作性能，
    // 但部分游戏可能出现画面异常（原版提示语同义）。
    var ndsDrasticThreaded3D: String = "disabled"

    // 16 位渲染（bit 23，_GlUse16Bit，原版默认关）。
    var ndsDrastic16Bit: String = "disabled"

    // 禁用边缘标记（bit 40，_DisableEdgeMarking，原版默认关）：
    // 3D 描边后处理，禁用可提速，个别游戏依赖它显示轮廓。
    var ndsDrasticEdgeMarking: String = "disabled"

    // 主屏固定在上屏（bit 35，_FixMainEngineScreen，原版默认关）：
    // 交换主/副 3D 引擎的显示顺序，修复少数游戏主屏错位。
    var ndsDrasticFixMainScreen: String = "disabled"

    // 音频延迟（bits 8-9，_AudioLatency）：0=低 1=中 2=高 3=极高（原版默认 3）。
    var ndsDrasticAudioLatency: String = "3"

    // 麦克风启用（bit 26，_MicEnabled，原版默认开）。
    var ndsDrasticMicEnabled: String = "enabled"

    // 麦克风等级 0-3（bits 37-38，_MicLevel，原版默认 1；增益表 [2,4,8,16]）。
    var ndsDrasticMicLevel: String = "1"

    // 连发速度 0-4（bits 32-34，_AutoFireSpeed，原版默认 2；1 最快 5 最慢）。
    var ndsDrasticAutofireSpeed: String = "2"

    // 快进速率 0-5（bits 12-15，_FfwdSpeed）：50%/150%/200%/300%/400%/无限制
    // （原版默认 2=200%）。
    var ndsDrasticFfwdRate: String = "2"

    // Slot2 卡带类型 0-5（bits 43-46，_Slot2Type，原版默认 1=GBA 卡）：
    // none/gba/sram/rumble/motion_official/motion_homebrew。
    var ndsDrasticSlot2Type: String = "gba"

    // RTC 使用系统时间（bit 39，_RtcSystemTime，原版默认关）。
    var ndsDrasticRtcSystemTime: String = "disabled"

    // 金手指启用（bit 27，_CheatsEnabled，原版默认开）。
    var ndsDrasticCheatsEnabled: String = "enabled"

    // Lua 启用（bit 42，_LuaEnabled，原版默认开）。
    var ndsDrasticLuaEnabled: String = "enabled"

    // 即时存档内保存游戏存档（bit 25，_BackupInSavestates，原版默认开）。
    var ndsDrasticBackupInSavestates: String = "enabled"

    // 忽略卡带容量（bit 24，_IgnoreGamecardLimit，原版默认关）。
    var ndsDrasticIgnoreCardLimit: String = "disabled"

    // ROM 自动裁边（bit 36，_AutoTrim，原版默认关）。
    var ndsDrasticAutoTrim: String = "disabled"

    // 预解压 ROM 到内存（bit 48，_PreloadRoms，原版默认关）。
    var ndsDrasticPreloadRoms: String = "disabled"

    // 原生显示 FPS（bit 30，_ShowFPS，原版默认关：帧内叠加 FPS）。
    var ndsDrasticShowFps: String = "disabled"

    // 模拟线程数：0=自动（≥4核=3，≥2核=2，否则1，与原版同构）；1-8=强制
    // （原版 threads.cfg 语义）。★ 3D 游戏显示不完整（只剩顶部一条）时
    // 调高此值或保持自动 —— 原版绝不会以 0 线程启动。
    var ndsDrasticThreads: String = "0"

    // 自动存档间隔秒：0=关 / 300 / 900 / 1800（原版 _AutosaveMode 语义）。
    var ndsDrasticAutosave: String = "0"

    // ★ 已废弃的独立存档模式（仅保留存储兼容，UI/引擎不再读取）：
    // 激烈核心不再有独立存档格式选项 —— 电池存档恒为裸 .sav（bit50 恒置位），
    // 位置与格式完全跟随全局存档方式（设置 → 存储 → 存档方式）。
    var ndsDrasticSaveFormat: String = "sav"

    // 显示平滑滤波（GL 纹理过滤，视图层实现，不入 config）：
    // enabled=双线性（平滑），disabled=最近邻（像素锐利）。
    var ndsDrasticSmoothFilter: String = "enabled"

    // 显示方式（视图层选择）："gl"=OpenGL 加速显示（默认，复刻原版 App
    // 的 renderFrame 显示路径：原生纹理上传 + 双缓冲读 + GPU 绘制，
    // 3D 游戏无撕裂），"canvas"=画布位图路径（传统行为）。
    // EGL 初始化失败时自动回退 canvas（高清会话下取到的是原生降采样帧，无需降档）。
    var ndsDrasticDisplayMode: String = "gl"

    // === NDS 双屏独立布局 (videoScale == "custom" 时生效) ===
    // 参照 melonDS 官方 Android 布局模型：上屏 / 下屏各占一个独立矩形，
    // 可分别拖动 4 角调整大小、拖动矩形内部移动位置。归一化 0..1，横竖屏分开保存。
    var ndsTopLayoutLeft: Float = 0.05f
    var ndsTopLayoutTop: Float = 0.05f
    var ndsTopLayoutRight: Float = 0.95f
    var ndsTopLayoutBottom: Float = 0.48f
    var ndsBottomLayoutLeft: Float = 0.05f
    var ndsBottomLayoutTop: Float = 0.52f
    var ndsBottomLayoutRight: Float = 0.95f
    var ndsBottomLayoutBottom: Float = 0.98f
    var ndsTopLayoutLeftP: Float = 0.05f
    var ndsTopLayoutTopP: Float = 0.05f
    var ndsTopLayoutRightP: Float = 0.95f
    var ndsTopLayoutBottomP: Float = 0.48f
    var ndsBottomLayoutLeftP: Float = 0.05f
    var ndsBottomLayoutTopP: Float = 0.52f
    var ndsBottomLayoutRightP: Float = 0.95f
    var ndsBottomLayoutBottomP: Float = 0.98f

    // === PSX (PCSX-ReARMed) core options ===
    // Keys AND values verified against the shipped libpcsx_rearmed_libretro_android.so
    // and upstream notaz/pcsx_rearmed frontend/libretro_core_options.h.
    var pscxBios: String = "auto"                      // auto | HLE | scph1000 | scph1001 | scph1002 | scph5500 | scph5501 | scph5502 | psxonpsp660
    var pscxRegion: String = "auto"                    // auto | ntsc | pal
    var pscxFrameskipType: String = "disabled"        // disabled | auto | auto_threshold | fixed_interval
    var pscxFrameskip: String = "3"                    // 1..10 (frameskip_interval, only fixed_interval)
    var pscxFrameskipThreshold: String = "33"          // 15..80 % (only auto_threshold)
    var pscxPad1Type: String = "standard"              // standard(数字手柄) | analog(DualShock) | negcon | gun
    var pscxPad2Type: String = "standard"              // standard | analog | negcon | gun
    var pscxVibration: String = "enabled"              // enabled | disabled
    var pscxDithering: String = "enabled"              // enabled | disabled
    var pscxSpuInterp: String = "simple"               // simple | gaussian | cubic | off
    var pscxSpuReverb: String = "enabled"              // enabled | disabled
    var pscxShowBootlogo: String = "disabled"          // disabled | enabled (show PSX BIOS boot logo)
    var pscxCdReadahead: String = "12"                 // 0..30 (CD read-ahead in sectors)
    var pscxMemcard1: String = "libretro"              // libretro | serial | shared | none
    var pscxMemcard2: String = "shared"                // libretro | shared | none
    // === Additional PSX/PCSX-ReARMed options ===
    var pscxDrc: String = "enabled"                 // enabled | disabled (dynarec JIT compiler — 性能关键)
    var pscxDrcThread: String = "auto"              // auto | disabled | enabled (DynaRec 线程化)
    var pscxClock: String = "auto"                  // auto | 30..100 (PSX CPU overclock %)
    var pscxGpuThreadRendering: String = "auto"     // auto | disabled | enabled (GPU 渲染线程化 — 性能关键)
    var pscxIcache: String = "enabled"              // enabled | disabled (iCache 模拟, F1 系列需要)
    var pscxCdTurbo: String = "disabled"            // disabled | enabled (CD 加速, 不安全)
    var pscxFractionalFps: String = "auto"          // auto | disabled | enabled (小数帧率, PAL 准确)
    var pscxAltFlip: String = "auto"                // auto | early | late
    var pscxRgb32: String = "disabled"              // disabled | enabled (32-bit color output)
    var pscxScaleHires: String = "disabled"         // disabled | enabled (downscale 480i/512i to 320x240)
    var pscxShowOverscan: String = "disabled"       // disabled | enabled (show overscan area)
    var pscxNeonInterlace: String = "disabled"      // auto | disabled | enabled (隔行扫描优化)
    var pscxNeonEnhance: String = "disabled"        // disabled | enabled (NEON 2倍分辨率增强, 慢)
    var pscxCentering: String = "auto"              // auto | game | borderless | manual
    var pscxMultitap: String = "disabled"           // disabled | port 1 | port 2 | ports 1 and 2
    var pscxNegconResponse: String = "linear"       // linear | quadratic | cubic
    var pscxNegconDeadzone: String = "0"            // 0..30% neGcon 扭转死区
    var pscxCdAudio: String = "enabled"             // enabled=播放 CD 音轨 | disabled=关闭(提速)
    var pscxXaAudio: String = "enabled"             // enabled=播放 XA 音频 | disabled=关闭(提速)
    var pscxSpuThread: String = "disabled"          // disabled | enabled (SPU 线程化)
    var pscxGpuOddEven: String = "disabled"         // disabled | enabled (Peops odd/even GPU hack — Chrono Cross)
    var pscxAnalogAxis: String = "square"           // circle | square (analog stick bounds)

    // === DC (libretro Flycast — Dreamcast / NAOMI / AtomisWave) core options ===
    // 键名/取值已对照预编译 libflycast_libretro_android.so（libretro buildbot）
    // 字符串提取校验；reicast_* 键由 dc_loader.cpp 下发（见 applyCoreOptions）。
    var dcResolution: String = "640x480 (Native)"     // 内部分辨率（与核心选项值逐字匹配）
    var dcAlphaSorting: String = "per-triangle (normal)"  // per-strip (fast, least accurate) | per-triangle (normal) | per-pixel (accurate)
    var dcThreadedRendering: String = "enabled"       // enabled | disabled (渲染线程化, 性能关键)
    var dcDelayFrameSwapping: String = "enabled"      // enabled | disabled (延迟帧交换, 防撕裂)
    var dcFrameSkipping: String = "disabled"          // enabled | disabled (跳帧)
    var dcWidescreenHack: String = "disabled"         // enabled | disabled (16:9 视锥拉伸)
    var dcWidescreenCheats: String = "disabled"       // enabled | disabled (宽屏兼容补丁)
    var dcGdromFastLoading: String = "enabled"        // enabled | disabled (GD-ROM 快速读盘)
    var dcHleBios: String = "disabled"                // disabled | enabled (HLE BIOS, 已内置真实 BIOS)
    var dcRam32mb: String = "disabled"                // disabled | enabled (32MB 内存改机)
    var dcForceWince: String = "disabled"             // disabled | enabled (WinCE 游戏强制模式)
    var dcEnableDsp: String = "disabled"              // disabled | enabled (AICA DSP 音效)
    var dcRegion: String = "Default"                  // Default | Japan | USA | Europe (主机区域)
    var dcLanguage: String = "Default"                // Default | Japanese | English | German | French | Spanish | Italian
    var dcBroadcast: String = "Default"               // Default | NTSC | PAL | PAL-M | PAL-N (电视制式)
    var dcCableType: String = "TV (Composite)"        // VGA | TV (Composite) (视频输出)

    // === PS2 (PCEE2 — PCSX2 v2.7.523 core) core options ===
    // ps2ResMulti: 内部分辨率倍数，发给核心的 pcsx2_upscale_multiplier。
    // 核心取值是纯数字字符串 "1".."4"（1=原生 640x448；2x=1280x896；
    // 3x=1920x1344；4x=2560x1792）。前端帧缓冲上限 2560x2048 全覆盖，
    // 超大帧仍有 box 降采样兜底。旧版 Play! 的 "1x"/"2x"/"4x"/"8x" 取值
    // 在 load() 时自动迁移到新枚举。键名已对照 PCEE2 源码验证。
    var ps2ResMulti: String = "1"                   // "1" | "2" | "3" | "4" (分辨率倍数)
    // ARMSX2 核心已编译 Vulkan (USE_VULKAN=ON)。可取 auto | opengl | vulkan |
    // software；auto 让 GSUtil::GetPreferredRenderer 按设备支持选择（有 Vulkan 的
    // Adreno/Mali 设备走 Vulkan，其余回 OpenGL）。默认 auto 与 ARMSX2 全新安装
    // 一致；旧存档里的 "opengl" 保持原样直接生效。
    var ps2Renderer: String = "auto"                // auto | opengl | vulkan | software (渲染器, 即时切换)
    var ps2Bilinear: String = "enabled"             // disabled | enabled → 映射为 nearest/bilinear_ps2

    // === PS2 (PCEE2) 补充核心选项 — 键名/取值对照 pcee2-libretro Libretro.cpp definitions[] 表 ===
    var ps2FastBoot: String = "enabled"              // pcsx2_fast_boot: enabled(跳过BIOS动画,默认) | disabled(播放BIOS动画)
    var ps2HwDownloadMode: String = "unsynchronized" // pcsx2_hw_download_mode: accurate|no_readbacks|unsynchronized|async|disabled (GPU回读/硬件下载模式,手机tiler提速)
    var ps2BlendingAccuracy: String = "basic"        // pcsx2_blending_accuracy: minimum|basic|medium|high|full|maximum
    var ps2Trilinear: String = "auto"                // pcsx2_trilinear_filtering: auto|off|ps2|forced
    var ps2Anisotropic: String = "0"                 // pcsx2_anisotropic_filtering: 0|2|4|8|16
    var ps2Dithering: String = "2"                   // pcsx2_dithering: 0|1|2 (2=Unscaled 默认)
    var ps2Mipmapping: String = "enabled"            // pcsx2_mipmapping: enabled|disabled
    var ps2Deinterlace: String = "0"                 // pcsx2_deinterlace_mode: 0..9 (0=自动)
    var ps2AspectRatio: String = "auto"              // pcsx2_aspect_ratio: auto|4:3|16:9
    var ps2Widescreen: String = "disabled"           // pcsx2_widescreen_patches: disabled|enabled
    var ps2NoInterlace: String = "disabled"          // pcsx2_no_interlacing_patches: disabled|enabled
    var ps2Mtvu: String = "enabled"                  // pcsx2_mtvu (多线程VU1, 性能关键)
    var ps2InstantVu1: String = "enabled"            // pcsx2_instant_vu1 (性能)
    var ps2EeCycleRate: String = "0"                 // pcsx2_ee_cycle_rate: -3..3 (超频/降频)
    var ps2EeCycleSkip: String = "0"                 // pcsx2_ee_cycle_skip: 0|1|2|3
    var ps2Rumble: String = "enabled"                // pcsx2_rumble: enabled|disabled
    // ARMSX2 native 渲染增强设置（renderTvShader/renderShadeBoost/
    // renderHalfpixeloffset/renderPreloading）——原先只有渲染器/分辨率等基础项。
    var ps2TvShader: String = "0"                    // pcsx2_tv_shader: 0..7 (0=关闭, ARMSX2 TVShader 枚举)
    var ps2ShadeBoost: String = "disabled"           // pcsx2_shade_boost: disabled|enabled (亮度/对比度/饱和度/伽马固定默认 50)
    var ps2HalfPixelOffset: String = "0"             // pcsx2_half_pixel_offset: 0..5 (GSHalfPixelOffset, 0=关闭)
    var ps2TexturePreloading: String = "1"           // pcsx2_texture_preloading: 0=Off|1=Partial|2=Full (Full 最吃显存)

    companion object {
        /** 把 Play! 时代的 "1x|2x|4x|8x" 迁移为 PCEE2 的 "1".."4"；非法值回落默认。 */
        fun normalizePs2ResMulti(raw: String?): String = when (raw?.trim()) {
            "1", "1x" -> "1"
            "2", "2x" -> "2"
            "3", "3x" -> "3"
            "4", "4x", "8x", "8" -> "4"   // 8x 在 PCEE2 无对应档位，就近映射 4x
            else -> "1"
        }
    }

    // === PS2 on-screen pad（专属全套布局：双摇杆 + 双肩键 + L3/R3）===
    // PS2 手柄 = DualShock 2：左摇杆/十字键左侧上下排布、右侧 △○×□ 菱形 +
    // 右摇杆下方、顶部 L2/L1 + R1/R2。与通用布局字段分开，避免挤占其他
    // 平台的默认位置。摇杆输出真实模拟轴（int16 LX/LY/RX/RY），不是数字 8 向。
    // 十字键（左侧上部）
    var ps2Dpad: ButtonLayout = ButtonLayout(x = 0.13f, y = 0.55f, sizeDp = 110)
    var ps2DpadP: ButtonLayout = ButtonLayout(x = 0.18f, y = 0.58f, sizeDp = 104)
    // 左/右摇杆（左下 / 右下）
    var ps2LStick: ButtonLayout = ButtonLayout(x = 0.13f, y = 0.88f, sizeDp = 112)
    var ps2RStick: ButtonLayout = ButtonLayout(x = 0.87f, y = 0.88f, sizeDp = 112)
    var ps2LStickP: ButtonLayout = ButtonLayout(x = 0.18f, y = 0.78f, sizeDp = 96)
    var ps2RStickP: ButtonLayout = ButtonLayout(x = 0.82f, y = 0.80f, sizeDp = 96)
    // 脸键菱形（右上）：× 下、○ 右、□ 左、△ 上（DualShock 标准）
    var ps2BtnA: ButtonLayout = ButtonLayout(x = 0.87f, y = 0.58f, sizeDp = 56)   // × Cross
    var ps2BtnB: ButtonLayout = ButtonLayout(x = 0.95f, y = 0.50f, sizeDp = 56)   // ○ Circle
    var ps2BtnX: ButtonLayout = ButtonLayout(x = 0.79f, y = 0.50f, sizeDp = 56)   // □ Square
    var ps2BtnY: ButtonLayout = ButtonLayout(x = 0.87f, y = 0.42f, sizeDp = 56)   // △ Triangle
    var ps2BtnAP: ButtonLayout = ButtonLayout(x = 0.82f, y = 0.60f, sizeDp = 50)
    var ps2BtnBP: ButtonLayout = ButtonLayout(x = 0.91f, y = 0.52f, sizeDp = 50)
    var ps2BtnXP: ButtonLayout = ButtonLayout(x = 0.73f, y = 0.52f, sizeDp = 50)
    var ps2BtnYP: ButtonLayout = ButtonLayout(x = 0.82f, y = 0.44f, sizeDp = 50)
    // 肩键（顶部）：L1/R1 外侧、L2/R2 内侧
    var ps2BtnL1: ButtonLayout = ButtonLayout(x = 0.10f, y = 0.13f, sizeDp = 52)
    var ps2BtnR1: ButtonLayout = ButtonLayout(x = 0.90f, y = 0.13f, sizeDp = 52)
    var ps2BtnL2: ButtonLayout = ButtonLayout(x = 0.22f, y = 0.08f, sizeDp = 44)
    var ps2BtnR2: ButtonLayout = ButtonLayout(x = 0.78f, y = 0.08f, sizeDp = 44)
    var ps2BtnL1P: ButtonLayout = ButtonLayout(x = 0.10f, y = 0.11f, sizeDp = 46)
    var ps2BtnR1P: ButtonLayout = ButtonLayout(x = 0.90f, y = 0.11f, sizeDp = 46)
    var ps2BtnL2P: ButtonLayout = ButtonLayout(x = 0.22f, y = 0.06f, sizeDp = 40)
    var ps2BtnR2P: ButtonLayout = ButtonLayout(x = 0.78f, y = 0.06f, sizeDp = 40)
    // 中下：Select / Start + L3 / R3（摇杆按下，屏幕上以小按钮提供）
    var ps2BtnStart: ButtonLayout = ButtonLayout(x = 0.60f, y = 0.93f, sizeDp = 48)
    var ps2BtnSelect: ButtonLayout = ButtonLayout(x = 0.40f, y = 0.93f, sizeDp = 48)
    var ps2BtnL3: ButtonLayout = ButtonLayout(x = 0.26f, y = 0.94f, sizeDp = 36)
    var ps2BtnR3: ButtonLayout = ButtonLayout(x = 0.74f, y = 0.94f, sizeDp = 36)
    var ps2BtnStartP: ButtonLayout = ButtonLayout(x = 0.58f, y = 0.92f, sizeDp = 42)
    var ps2BtnSelectP: ButtonLayout = ButtonLayout(x = 0.42f, y = 0.92f, sizeDp = 42)
    var ps2BtnL3P: ButtonLayout = ButtonLayout(x = 0.29f, y = 0.93f, sizeDp = 32)
    var ps2BtnR3P: ButtonLayout = ButtonLayout(x = 0.71f, y = 0.93f, sizeDp = 32)

    // === Arcade (FBNeo) on-screen pad extras ===
    // L2/R2 button positions (bit12/bit13 in the libretro joypad word).
    // Used for 6-button fight-stick layouts and as Coin/Start shortcuts.
    var btnL2: ButtonLayout = ButtonLayout(x = 0.08f, y = 0.32f, sizeDp = 48)
    var btnR2: ButtonLayout = ButtonLayout(x = 0.92f, y = 0.32f, sizeDp = 48)
    var btnL2P: ButtonLayout = ButtonLayout(x = 0.10f, y = 0.28f, sizeDp = 46)
    var btnR2P: ButtonLayout = ButtonLayout(x = 0.90f, y = 0.28f, sizeDp = 46)
    // Whether to show L2/R2 buttons on the arcade overlay (hidden by default —
    // 4 face buttons + L/R is enough for most arcade games; 6-button fight
    // games like SFII/KOF benefit from L2/R2 mapped to strong punch/kick).
    var arcadeShowL2R2: Boolean = false
    // Arcade input mode: "dpad" (digital D-pad) or "analog" (left stick →
    // D-pad bits). FBNeo uses the same bit layout for both; this toggle only
    // affects which on-screen control is drawn — "analog" draws a circular
    // analog-stick widget whose 8 directions map to the same bits as D-pad.
    var arcadeInputMode: String = "dpad"               // dpad | analog

    // === Combo buttons (per-platform) ===
    // JSON-encoded list of ComboButton entries. Each combo button is a single
    // on-screen button that, when pressed, activates multiple pad bits
    // simultaneously — e.g. "AB" (jump+attack in NES Mario), "A+B+↓" (slide
    // in some MD games), "L+R" (special move charge in SNES).
    //
    // Format: [{"id":"combo1","label":"AB","bits":3,"x":0.5,"y":0.85,"size":56,"color":-14031360}]
    // bits is the OR'd bit mask (BTN_A|BTN_B = 0x01|0x02 = 3).
    // This is per-platform: each platform tab has its own combo list.
    var comboButtons: String = ""         // NES combo list (JSON)
    var comboButtonsSfc: String = ""      // SNES combo list (JSON)
    var comboButtonsGba: String = ""      // GBA combo list (JSON)
    var comboButtonsArcade: String = ""   // Arcade combo list (JSON)
    var comboButtonsMd: String = ""       // MD combo list (JSON)
    var comboButtonsPce: String = ""      // PCE combo list (JSON)

    // === PCE button visibility toggles (which on-screen buttons are shown) ===
    // PCE uses the shared SNES/Arcade/MD layout slots (D-pad, I/II, RUN,
    // SELECT, V/VI, IV/III, Turbo I/II). Each can be individually shown or
    // hidden from the layout editor so the user can declutter the overlay.
    // These flags are global (shared between landscape and portrait).
    var pceShowDpad: Boolean = true
    var pceShowA: Boolean = true          // PCE "I"
    var pceShowB: Boolean = true          // PCE "II"
    var pceShowStart: Boolean = true      // PCE "RUN"
    var pceShowSelect: Boolean = true
    var pceShowL: Boolean = true          // PCE "V"
    var pceShowR: Boolean = true          // PCE "VI"
    var pceShowX: Boolean = true          // PCE "IV"
    var pceShowY: Boolean = true          // PCE "III"
    var pceShowL2: Boolean = true         // PCE "TURBO II"
    var pceShowR2: Boolean = true         // PCE "TURBO I"

    // === Per-platform hidden button lists ===
    // Comma-separated button key names that the user has hidden via the
    // "显隐按键" dialog in the pad layout editor. Each platform has its own
    // list so hiding a button in NES doesn't affect SNES, etc.
    // Valid keys: dpad, a, b, ta, tb, start, select, l, r, x, y, l2, r2
    // (ta/tb = turbo A/B; only shown on NES/GB when X/Y hidden).
    var hiddenButtons: String = ""         // NES/GB hidden button keys
    var hiddenButtonsSfc: String = ""     // SNES hidden button keys
    var hiddenButtonsGba: String = ""     // GBA hidden button keys
    var hiddenButtonsArcade: String = ""  // Arcade/FBNeo hidden button keys
    var hiddenButtonsMd: String = ""      // MD/SEGA hidden button keys
    var hiddenButtonsPce: String = ""     // PCE hidden button keys
    var hiddenButtonsNds: String = ""     // NDS hidden button keys
    var hiddenButtonsPsx: String = ""     // PSX hidden button keys
    var hiddenButtonsPs2: String = ""     // PS2 hidden button keys (含 l3/r3；双摇杆常驻不隐藏)
    // ★ DC 专属显隐列表 —— 旧版漏掉了 DC，导致显隐对话框对 DC 无效
    // （isButtonHidden 落到 else 分支永远返回 false，切换不生效）。
    var hiddenButtonsDc: String = ""      // DC hidden button keys
    var hiddenButtonsNgcwii: String = ""  // NGC/WII hidden button keys (外部核心 Ishiruka 配置态)

    // === NGC/WII 控制器切换（外部核心 Ishiruka 启动时的默认控制器形态）===
    // "gc"      = GameCube 手柄 (A/B/X/Y/Z + 双摇杆 + L/R 扳机)
    // "wiimote" = Wii Remote 横握 (1/2/A/B/±/HOME + 十字键)
    // "nunchuk" = Wii Remote + Nunchuk (CC/Z + 双摇杆)
    // "classic" = 经典手柄 Classic Controller (双摇杆 + 全键)
    // 该设置同时写入 Ishiruka 的 WiimoteNew.ini Extension 字段（见
    // ExternalCores.applyNgcwiiControllerMode），并在启动页提供快捷切换。
    var ngcwiiController: String = "gc"
    // NGC/WII 体感开关：开启时启动 Ishiruka 前保留体感（摇动/倾斜/IR 指针）
    // 绑定并提示开启设备传感器；关闭时写禁用（避免误触发摇一摇）。
    // 经 ExternalCores.applyNgcwiiControllerMode 写入核心配置。
    var ngcwiiMotion: String = "enabled"

    // === 3DS（外部核心 Azahar/爱吾）配置 ===
    // 启动前是否校验游戏加密状态（NCCH 标志位）：
    // "enabled"  = 校验 + 加密游戏提示导入 aes_keys.txt（推荐）
    // "disabled" = 直接交给核心处理（爱吾商店版多自带解密）
    var tg3dsDecryptCheck: String = "enabled"
    // CIA 导入策略："launch" = 启动即安装（Azahar 引导 CIA 时自动装 NAND）；
    // "copy"  = 先复制到核心数据目录 import/ 再启动。
    var tg3dsCiaMode: String = "launch"

    // === Input mode (joystick vs D-pad) ===
    // "dpad" = cross-shaped digital D-pad (default); "analog" = circular
    // analog stick. Applies to ALL platforms, not just Arcade. The rendering
    // difference is purely visual — both produce the same UP/DOWN/LEFT/RIGHT
    // bits. This implements the user's request for joystick/d-pad switching
    // across all engines.
    var inputMode: String = "dpad"         // dpad | analog


    /**
     * 块式复制:返回与当前实例字段完全相同的新实例,再执行 block 修改指定字段。
     * 注意:不能用 data class 的 copy() — 254 个参数会突破 DEX 的 invoke 指令
     * 255 寄存器上限,导致 ART 校验器 VerifyError("expected 8 argument registers,
     * method signature has 9 or more")。这里用无参构造 + copyFrom + block 实现。
     */
    fun copy(block: PadLayout.() -> Unit): PadLayout {
        return PadLayout().also { it.copyFrom(this) }.apply(block)
    }

    /** 将 another 的全部属性逐字段复制到当前实例。 */
    fun copyFrom(another: PadLayout) {
        dpad = another.dpad
        btnA = another.btnA
        btnB = another.btnB
        btnTurboA = another.btnTurboA
        btnTurboB = another.btnTurboB
        btnStart = another.btnStart
        btnSelect = another.btnSelect
        btnL = another.btnL
        btnR = another.btnR
        btnX = another.btnX
        btnY = another.btnY
        btnQuickSave = another.btnQuickSave
        btnQuickLoad = another.btnQuickLoad
        dpadP = another.dpadP
        btnAP = another.btnAP
        btnBP = another.btnBP
        btnTurboAP = another.btnTurboAP
        btnTurboBP = another.btnTurboBP
        btnStartP = another.btnStartP
        btnSelectP = another.btnSelectP
        btnLP = another.btnLP
        btnRP = another.btnRP
        btnXP = another.btnXP
        btnYP = another.btnYP
        btnQuickSaveP = another.btnQuickSaveP
        btnQuickLoadP = another.btnQuickLoadP
        opacity = another.opacity
        showPad = another.showPad
        showFps = another.showFps
        showPlayerSwitch = another.showPlayerSwitch
        playerSwitchX = another.playerSwitchX
        playerSwitchY = another.playerSwitchY
        ntscFilter = another.ntscFilter
        aspectRatio = another.aspectRatio
        palette = another.palette
        region = another.region
        soundQuality = another.soundQuality
        cropOverscan = another.cropOverscan
        videoScale = another.videoScale
        customLayoutLeft = another.customLayoutLeft
        customLayoutTop = another.customLayoutTop
        customLayoutRight = another.customLayoutRight
        customLayoutBottom = another.customLayoutBottom
        customLayoutLeftP = another.customLayoutLeftP
        customLayoutTopP = another.customLayoutTopP
        customLayoutRightP = another.customLayoutRightP
        customLayoutBottomP = another.customLayoutBottomP
        videoFilter = another.videoFilter
        overclocking = another.overclocking
        sfcReduceSpriteFlicker = another.sfcReduceSpriteFlicker
        sfcReduceSlowdown = another.sfcReduceSlowdown
        sfcAudioInterpolation = another.sfcAudioInterpolation
        sfcGfxTransparency = another.sfcGfxTransparency
        sfcGfxHires = another.sfcGfxHires
        sfcGfxClip = another.sfcGfxClip
        sfcBlockInvalidVram = another.sfcBlockInvalidVram
        sfcSoundOutput = another.sfcSoundOutput
        sfcOverscan = another.sfcOverscan
        sfcSideBySide = another.sfcSideBySide
        sfcUpDownAllowed = another.sfcUpDownAllowed
        sfcSuperScope = another.sfcSuperScope
        sfcLayer1 = another.sfcLayer1
        sfcLayer2 = another.sfcLayer2
        sfcLayer3 = another.sfcLayer3
        sfcLayer4 = another.sfcLayer4
        sfcLayer5 = another.sfcLayer5
        sfcOverclock = another.sfcOverclock
        gbColorCorrection = another.gbColorCorrection
        gbcColorPreset = another.gbcColorPreset
        gbaColorCorrection = another.gbaColorCorrection
        gbaColorPreset = another.gbaColorPreset
        gbaFrameBlending = another.gbaFrameBlending
        gbaAudioResampler = another.gbaAudioResampler
        gbaAudioLowPass = another.gbaAudioLowPass
        gbaAudioLowPassRange = another.gbaAudioLowPassRange
        gbaFrameskipType = another.gbaFrameskipType
        gbaFrameskipCount = another.gbaFrameskipCount
        gbaSolarSensor = another.gbaSolarSensor
        gbaIdleOptimization = another.gbaIdleOptimization
        gbaForceRTC = another.gbaForceRTC
        gbaAllowOpposite = another.gbaAllowOpposite
        gbModel = another.gbModel
        gbSgbBorders = another.gbSgbBorders
        gbaFrameskipThreshold = another.gbaFrameskipThreshold
        dosMachine = another.dosMachine
        dosCycles = another.dosCycles
        dosCyclesMax = another.dosCyclesMax
        dosSbType = another.dosSbType
        dosSbAdlibMode = another.dosSbAdlibMode
        dosSbAdlibEmu = another.dosSbAdlibEmu
        dosGus = another.dosGus
        // 音频（核心自带混音器）
        dosAudiorate = another.dosAudiorate
        dosSwapStereo = another.dosSwapStereo
        dosTandySound = another.dosTandySound
        // CPU / 内存 / 视频补充
        dosCpuCore = another.dosCpuCore
        dosCpuType = another.dosCpuType
        dosMemorySize = another.dosMemorySize
        dosAspectCorrection = another.dosAspectCorrection
        dosCgaMode = another.dosCgaMode
        dosMouseInput = another.dosMouseInput
        dosKeyboardLayout = another.dosKeyboardLayout
        dosAutoMapping = another.dosAutoMapping
        dosSavestate = another.dosSavestate
        dosVoodoo = another.dosVoodoo
        dosForce60fps = another.dosForce60fps
        dosInputMode = another.dosInputMode
        javaInputMode = another.javaInputMode
        javaOpacity = another.javaOpacity
        javaScaleType = another.javaScaleType
        javaShowFps = another.javaShowFps
        javaImmediateMode = another.javaImmediateMode
        javaResolution = another.javaResolution
        javaScaleRatio = another.javaScaleRatio
        javaFpsLimit = another.javaFpsLimit
        javaTouchInput = another.javaTouchInput
        javaNumDualDispatch = another.javaNumDualDispatch
        javaButtonKeyMap = another.javaButtonKeyMap
        overlayThemeJson = another.overlayThemeJson
        javaPhoneGrid = another.javaPhoneGrid
        javaPhoneTop = another.javaPhoneTop
        dosDpad = another.dosDpad
        dosBtnEsc = another.dosBtnEsc
        dosBtnEnter = another.dosBtnEnter
        dosBtnSpace = another.dosBtnSpace
        dosBtnTab = another.dosBtnTab
        dosBtnCtrl = another.dosBtnCtrl
        dosBtnAlt = another.dosBtnAlt
        dosBtnShift = another.dosBtnShift
        dosBtnBack = another.dosBtnBack
        dosBtnMouseL = another.dosBtnMouseL
        dosBtnMouseR = another.dosBtnMouseR
        dosDpadP = another.dosDpadP
        dosBtnEscP = another.dosBtnEscP
        dosBtnEnterP = another.dosBtnEnterP
        dosBtnSpaceP = another.dosBtnSpaceP
        dosBtnTabP = another.dosBtnTabP
        dosBtnCtrlP = another.dosBtnCtrlP
        dosBtnAltP = another.dosBtnAltP
        dosBtnShiftP = another.dosBtnShiftP
        dosBtnBackP = another.dosBtnBackP
        dosBtnMouseLP = another.dosBtnMouseLP
        dosBtnMouseRP = another.dosBtnMouseRP
        dosBtnInsert = another.dosBtnInsert
        dosBtnDelete = another.dosBtnDelete
        dosBtnHome = another.dosBtnHome
        dosBtnEnd = another.dosBtnEnd
        dosBtnPageUp = another.dosBtnPageUp
        dosBtnPageDown = another.dosBtnPageDown
        dosBtnInsertP = another.dosBtnInsertP
        dosBtnDeleteP = another.dosBtnDeleteP
        dosBtnHomeP = another.dosBtnHomeP
        dosBtnEndP = another.dosBtnEndP
        dosBtnPageUpP = another.dosBtnPageUpP
        dosBtnPageDownP = another.dosBtnPageDownP
        dosShowDpad = another.dosShowDpad
        dosShowEsc = another.dosShowEsc
        dosShowEnter = another.dosShowEnter
        dosShowSpace = another.dosShowSpace
        dosShowTab = another.dosShowTab
        dosShowCtrl = another.dosShowCtrl
        dosShowAlt = another.dosShowAlt
        dosShowShift = another.dosShowShift
        dosShowBack = another.dosShowBack
        dosShowMouseL = another.dosShowMouseL
        dosShowMouseR = another.dosShowMouseR
        dosShowInsert = another.dosShowInsert
        dosShowDelete = another.dosShowDelete
        dosShowHome = another.dosShowHome
        dosShowEnd = another.dosShowEnd
        dosShowPageUp = another.dosShowPageUp
        dosShowPageDown = another.dosShowPageDown
        dosExtraKeys = another.dosExtraKeys
        dosExtraKeysP = another.dosExtraKeysP
        screenOrientation = another.screenOrientation
        highQualityScaling = another.highQualityScaling
        arcadeAspect = another.arcadeAspect
        arcadeRotate = another.arcadeRotate
        arcadeVerticalMode = another.arcadeVerticalMode
        arcadeCropOverscan = another.arcadeCropOverscan
        arcadeCpuSpeed = another.arcadeCpuSpeed
        arcadeFrameskip = another.arcadeFrameskip
        arcadeForce60hz = another.arcadeForce60hz
        arcadeSampleRate = another.arcadeSampleRate
        arcadeAudioInterp = another.arcadeAudioInterp
        arcadeLowpass = another.arcadeLowpass
        arcadeNeogeomode = another.arcadeNeogeomode
        arcadeMemcard = another.arcadeMemcard
        mdRegion = another.mdRegion
        mdSystem = another.mdSystem
        mdAspect = another.mdAspect
        mdRender = another.mdRender
        mdNtscFilter = another.mdNtscFilter
        mdLcdFilter = another.mdLcdFilter
        mdOverscan = another.mdOverscan
        mdGgExtra = another.mdGgExtra
        mdLeftBorder = another.mdLeftBorder
        mdInput = another.mdInput
        mdAllowUpDown = another.mdAllowUpDown
        mdOverclock = another.mdOverclock
        mdFrameskip = another.mdFrameskip
        mdCdFastboot = another.mdCdFastboot
        mdSmsFm = another.mdSmsFm
        mdGgStretch = another.mdGgStretch
        pceConsoleType = another.pceConsoleType
        pceAspect = another.pceAspect
        pceOverscan = another.pceOverscan
        pceNoSpriteLimit = another.pceNoSpriteLimit
        pcePalette = another.pcePalette
        pceCdromBios = another.pceCdromBios
        pceTurbotap = another.pceTurbotap
        pceMb128 = another.pceMb128
        pceAllowUpDown = another.pceAllowUpDown
        ndsUseFwBios = another.ndsUseFwBios
        ndsConsoleMode = another.ndsConsoleMode
        ndsScreenLayout = another.ndsScreenLayout
        ndsResolution = another.ndsResolution
        ndsOpenGlRenderer = another.ndsOpenGlRenderer
        ndsOpenGlBetterPolygons = another.ndsOpenGlBetterPolygons
        ndsOpenGlFiltering = another.ndsOpenGlFiltering
        ndsFiltering = another.ndsFiltering
        ndsScreensaver = another.ndsScreensaver
        ndsTouchMode = another.ndsTouchMode
        ndsMouseSpeed = another.ndsMouseSpeed
        ndsDsiSdcard = another.ndsDsiSdcard
        ndsRandomizeMac = another.ndsRandomizeMac
        ndsJitEnable = another.ndsJitEnable
        ndsAudioInterpolation = another.ndsAudioInterpolation
        ndsUseFwSettings = another.ndsUseFwSettings
        ndsScreenGap = another.ndsScreenGap
        ndsSwapscreenMode = another.ndsSwapscreenMode
        ndsMicInput = another.ndsMicInput
        ndsLanguage = another.ndsLanguage
        ndsAudioBitrate = another.ndsAudioBitrate
        ndsJitBlockSize = another.ndsJitBlockSize
        ndsJitFastMemory = another.ndsJitFastMemory
        ndsJitBranchOptimisations = another.ndsJitBranchOptimisations
        ndsJitLiteralOptimisations = another.ndsJitLiteralOptimisations
        ndsHybridSmallScreen = another.ndsHybridSmallScreen
        ndsSaveMode = another.ndsSaveMode
        // ★ 全局存档方式必须逐字段复制：copy() 的实现是
        //   "new PadLayout()（字段=默认值）+ copyFrom(this) + block"。
        //   此前漏掉 globalSaveMode —— 任何其他设置的 padLayout.copy{...}
        //   都会生成一个 globalSaveMode=默认"nesstation" 的对象并经
        //   PadLayoutStore.save 落盘，导致用户选的「ROM 同目录同名」被
        //   静默重置回统一存档目录（改其他设置/进激烈核心改设置即复现）。
        globalSaveMode = another.globalSaveMode
        ndsDrasticVolume = another.ndsDrasticVolume
        ndsDrasticSound = another.ndsDrasticSound
        ndsDrasticHdRender = another.ndsDrasticHdRender
        ndsDrasticFrameskipType = another.ndsDrasticFrameskipType
        ndsDrasticFrameskipValue = another.ndsDrasticFrameskipValue
        ndsDrasticFrameskipSafe = another.ndsDrasticFrameskipSafe
        ndsDrasticThreaded3D = another.ndsDrasticThreaded3D
        ndsDrastic16Bit = another.ndsDrastic16Bit
        ndsDrasticEdgeMarking = another.ndsDrasticEdgeMarking
        ndsDrasticFixMainScreen = another.ndsDrasticFixMainScreen
        ndsDrasticAudioLatency = another.ndsDrasticAudioLatency
        ndsDrasticMicEnabled = another.ndsDrasticMicEnabled
        ndsDrasticMicLevel = another.ndsDrasticMicLevel
        ndsDrasticAutofireSpeed = another.ndsDrasticAutofireSpeed
        ndsDrasticFfwdRate = another.ndsDrasticFfwdRate
        ndsDrasticSlot2Type = another.ndsDrasticSlot2Type
        ndsDrasticRtcSystemTime = another.ndsDrasticRtcSystemTime
        ndsDrasticCheatsEnabled = another.ndsDrasticCheatsEnabled
        ndsDrasticLuaEnabled = another.ndsDrasticLuaEnabled
        ndsDrasticBackupInSavestates = another.ndsDrasticBackupInSavestates
        ndsDrasticIgnoreCardLimit = another.ndsDrasticIgnoreCardLimit
        ndsDrasticAutoTrim = another.ndsDrasticAutoTrim
        ndsDrasticPreloadRoms = another.ndsDrasticPreloadRoms
        ndsDrasticShowFps = another.ndsDrasticShowFps
        ndsDrasticThreads = another.ndsDrasticThreads
        ndsDrasticAutosave = another.ndsDrasticAutosave
        ndsDrasticSaveFormat = another.ndsDrasticSaveFormat
        ndsDrasticSmoothFilter = another.ndsDrasticSmoothFilter
        ndsDrasticDisplayMode = another.ndsDrasticDisplayMode
        ndsTopLayoutLeft = another.ndsTopLayoutLeft
        ndsTopLayoutTop = another.ndsTopLayoutTop
        ndsTopLayoutRight = another.ndsTopLayoutRight
        ndsTopLayoutBottom = another.ndsTopLayoutBottom
        ndsBottomLayoutLeft = another.ndsBottomLayoutLeft
        ndsBottomLayoutTop = another.ndsBottomLayoutTop
        ndsBottomLayoutRight = another.ndsBottomLayoutRight
        ndsBottomLayoutBottom = another.ndsBottomLayoutBottom
        ndsTopLayoutLeftP = another.ndsTopLayoutLeftP
        ndsTopLayoutTopP = another.ndsTopLayoutTopP
        ndsTopLayoutRightP = another.ndsTopLayoutRightP
        ndsTopLayoutBottomP = another.ndsTopLayoutBottomP
        ndsBottomLayoutLeftP = another.ndsBottomLayoutLeftP
        ndsBottomLayoutTopP = another.ndsBottomLayoutTopP
        ndsBottomLayoutRightP = another.ndsBottomLayoutRightP
        ndsBottomLayoutBottomP = another.ndsBottomLayoutBottomP
        pscxBios = another.pscxBios
        pscxRegion = another.pscxRegion
        pscxFrameskipType = another.pscxFrameskipType
        pscxFrameskip = another.pscxFrameskip
        pscxPad1Type = another.pscxPad1Type
        pscxPad2Type = another.pscxPad2Type
        pscxVibration = another.pscxVibration
        pscxDithering = another.pscxDithering
        pscxSpuInterp = another.pscxSpuInterp
        pscxSpuReverb = another.pscxSpuReverb
        pscxShowBootlogo = another.pscxShowBootlogo
        pscxCdReadahead = another.pscxCdReadahead
        pscxMemcard1 = another.pscxMemcard1
        pscxMemcard2 = another.pscxMemcard2
        pscxDrc = another.pscxDrc
        pscxDrcThread = another.pscxDrcThread
        pscxClock = another.pscxClock
        pscxGpuThreadRendering = another.pscxGpuThreadRendering
        pscxIcache = another.pscxIcache
        pscxCdTurbo = another.pscxCdTurbo
        pscxFractionalFps = another.pscxFractionalFps
        pscxAltFlip = another.pscxAltFlip
        pscxNeonInterlace = another.pscxNeonInterlace
        pscxNeonEnhance = another.pscxNeonEnhance
        pscxCentering = another.pscxCentering
        pscxNegconResponse = another.pscxNegconResponse
        pscxNegconDeadzone = another.pscxNegconDeadzone
        pscxCdAudio = another.pscxCdAudio
        pscxXaAudio = another.pscxXaAudio
        pscxSpuThread = another.pscxSpuThread
        pscxFrameskipThreshold = another.pscxFrameskipThreshold
        pscxRgb32 = another.pscxRgb32
        pscxScaleHires = another.pscxScaleHires
        pscxShowOverscan = another.pscxShowOverscan
        pscxMultitap = another.pscxMultitap
        pscxGpuOddEven = another.pscxGpuOddEven
        pscxAnalogAxis = another.pscxAnalogAxis
        dcResolution = another.dcResolution
        dcAlphaSorting = another.dcAlphaSorting
        dcThreadedRendering = another.dcThreadedRendering
        dcDelayFrameSwapping = another.dcDelayFrameSwapping
        dcFrameSkipping = another.dcFrameSkipping
        dcWidescreenHack = another.dcWidescreenHack
        dcWidescreenCheats = another.dcWidescreenCheats
        dcGdromFastLoading = another.dcGdromFastLoading
        dcHleBios = another.dcHleBios
        dcRam32mb = another.dcRam32mb
        dcForceWince = another.dcForceWince
        dcEnableDsp = another.dcEnableDsp
        dcRegion = another.dcRegion
        dcLanguage = another.dcLanguage
        dcBroadcast = another.dcBroadcast
        dcCableType = another.dcCableType
        ps2ResMulti = another.ps2ResMulti
        ps2Renderer = another.ps2Renderer
        ps2Bilinear = another.ps2Bilinear
        ps2FastBoot = another.ps2FastBoot
        ps2HwDownloadMode = another.ps2HwDownloadMode
        ps2BlendingAccuracy = another.ps2BlendingAccuracy
        ps2Trilinear = another.ps2Trilinear
        ps2Anisotropic = another.ps2Anisotropic
        ps2Dithering = another.ps2Dithering
        ps2Mipmapping = another.ps2Mipmapping
        ps2Deinterlace = another.ps2Deinterlace
        ps2AspectRatio = another.ps2AspectRatio
        ps2Widescreen = another.ps2Widescreen
        ps2NoInterlace = another.ps2NoInterlace
        ps2Mtvu = another.ps2Mtvu
        ps2InstantVu1 = another.ps2InstantVu1
        ps2EeCycleRate = another.ps2EeCycleRate
        ps2EeCycleSkip = another.ps2EeCycleSkip
        ps2TvShader = another.ps2TvShader
        ps2ShadeBoost = another.ps2ShadeBoost
        ps2HalfPixelOffset = another.ps2HalfPixelOffset
        ps2TexturePreloading = another.ps2TexturePreloading
        ps2Rumble = another.ps2Rumble
        ps2Dpad = another.ps2Dpad
        ps2DpadP = another.ps2DpadP
        ps2LStick = another.ps2LStick
        ps2RStick = another.ps2RStick
        ps2LStickP = another.ps2LStickP
        ps2RStickP = another.ps2RStickP
        ps2BtnA = another.ps2BtnA
        ps2BtnB = another.ps2BtnB
        ps2BtnX = another.ps2BtnX
        ps2BtnY = another.ps2BtnY
        ps2BtnAP = another.ps2BtnAP
        ps2BtnBP = another.ps2BtnBP
        ps2BtnXP = another.ps2BtnXP
        ps2BtnYP = another.ps2BtnYP
        ps2BtnL1 = another.ps2BtnL1
        ps2BtnR1 = another.ps2BtnR1
        ps2BtnL2 = another.ps2BtnL2
        ps2BtnR2 = another.ps2BtnR2
        ps2BtnL1P = another.ps2BtnL1P
        ps2BtnR1P = another.ps2BtnR1P
        ps2BtnL2P = another.ps2BtnL2P
        ps2BtnR2P = another.ps2BtnR2P
        ps2BtnStart = another.ps2BtnStart
        ps2BtnSelect = another.ps2BtnSelect
        ps2BtnL3 = another.ps2BtnL3
        ps2BtnR3 = another.ps2BtnR3
        ps2BtnStartP = another.ps2BtnStartP
        ps2BtnSelectP = another.ps2BtnSelectP
        ps2BtnL3P = another.ps2BtnL3P
        ps2BtnR3P = another.ps2BtnR3P
        hiddenButtonsPs2 = another.hiddenButtonsPs2
        hiddenButtonsDc = another.hiddenButtonsDc
        hiddenButtonsNgcwii = another.hiddenButtonsNgcwii
        ngcwiiController = another.ngcwiiController
        ngcwiiMotion = another.ngcwiiMotion
        tg3dsDecryptCheck = another.tg3dsDecryptCheck
        tg3dsCiaMode = another.tg3dsCiaMode
        btnL2 = another.btnL2
        btnR2 = another.btnR2
        btnL2P = another.btnL2P
        btnR2P = another.btnR2P
        arcadeShowL2R2 = another.arcadeShowL2R2
        arcadeInputMode = another.arcadeInputMode
        comboButtons = another.comboButtons
        comboButtonsSfc = another.comboButtonsSfc
        comboButtonsGba = another.comboButtonsGba
        comboButtonsArcade = another.comboButtonsArcade
        comboButtonsMd = another.comboButtonsMd
        comboButtonsPce = another.comboButtonsPce
        pceShowDpad = another.pceShowDpad
        pceShowA = another.pceShowA
        pceShowB = another.pceShowB
        pceShowStart = another.pceShowStart
        pceShowSelect = another.pceShowSelect
        pceShowL = another.pceShowL
        pceShowR = another.pceShowR
        pceShowX = another.pceShowX
        pceShowY = another.pceShowY
        pceShowL2 = another.pceShowL2
        pceShowR2 = another.pceShowR2
        hiddenButtons = another.hiddenButtons
        hiddenButtonsSfc = another.hiddenButtonsSfc
        hiddenButtonsGba = another.hiddenButtonsGba
        hiddenButtonsArcade = another.hiddenButtonsArcade
        hiddenButtonsMd = another.hiddenButtonsMd
        hiddenButtonsPce = another.hiddenButtonsPce
        hiddenButtonsNds = another.hiddenButtonsNds
        hiddenButtonsPsx = another.hiddenButtonsPsx
        inputMode = another.inputMode
        homeBackgroundUri = another.homeBackgroundUri
        homeBackgroundIsVideo = another.homeBackgroundIsVideo
        homeTileIcons = another.homeTileIcons
        homeTileIconAlphas = another.homeTileIconAlphas
    }
}

/**
 * Persistent on-screen controller layout + core option settings.
 * Stores per-button positions (0.0–1.0), sizes (dp), and global options.
 *
 * 横屏 / 竖屏的按键位置用不同的 key 前缀持久化：
 *   - 横屏：`dpad_x` / `a_x` / `b_x` / ... (旧 key，兼容旧版本)
 *   - 竖屏：`p_dpad_x` / `p_a_x` / `p_b_x` / ... (新 key)
 * 全局设置（opacity / showPad / 核心选项 / 滤镜等）不分方向共享。
 */
object PadLayoutStore {
    private const val PREFS_NAME = "pad_layout_v2"

    // === 横屏 Button keys (旧 key 兼容旧版本) ===
    private const val KEY_DPAD_X = "dpad_x"
    private const val KEY_DPAD_Y = "dpad_y"
    private const val KEY_DPAD_SIZE = "dpad_size"
    private const val KEY_A_X = "a_x"
    private const val KEY_A_Y = "a_y"
    private const val KEY_A_SIZE = "a_size"
    private const val KEY_B_X = "b_x"
    private const val KEY_B_Y = "b_y"
    private const val KEY_B_SIZE = "b_size"
    private const val KEY_TA_X = "ta_x"
    private const val KEY_TA_Y = "ta_y"
    private const val KEY_TA_SIZE = "ta_size"
    private const val KEY_TB_X = "tb_x"
    private const val KEY_TB_Y = "tb_y"
    private const val KEY_TB_SIZE = "tb_size"
    private const val KEY_START_X = "start_x"
    private const val KEY_START_Y = "start_y"
    private const val KEY_START_SIZE = "start_size"
    private const val KEY_SELECT_X = "select_x"
    private const val KEY_SELECT_Y = "select_y"
    private const val KEY_SELECT_SIZE = "select_size"

    // L/R shoulder button keys
    private const val KEY_L_X = "l_x"
    private const val KEY_L_Y = "l_y"
    private const val KEY_L_SIZE = "l_size"
    private const val KEY_R_X = "r_x"
    private const val KEY_R_Y = "r_y"
    private const val KEY_R_SIZE = "r_size"

    // X/Y face button keys (SNES)
    private const val KEY_X_X = "x_x"
    private const val KEY_X_Y = "x_y"
    private const val KEY_X_SIZE = "x_size"
    private const val KEY_Y_X = "y_x"
    private const val KEY_Y_Y = "y_y"
    private const val KEY_Y_SIZE = "y_size"

    // 即时存档 / 即时读档按钮 keys
    private const val KEY_QS_X = "qs_x"
    private const val KEY_QS_Y = "qs_y"
    private const val KEY_QS_SIZE = "qs_size"
    private const val KEY_QL_X = "ql_x"
    private const val KEY_QL_Y = "ql_y"
    private const val KEY_QL_SIZE = "ql_size"

    // === 竖屏 Button keys (新 key，p_ 前缀) ===
    private const val KEY_PDAD_X = "p_dpad_x"
    private const val KEY_PDAD_Y = "p_dpad_y"
    private const val KEY_PDAD_SIZE = "p_dpad_size"
    private const val KEY_PA_X = "p_a_x"
    private const val KEY_PA_Y = "p_a_y"
    private const val KEY_PA_SIZE = "p_a_size"
    private const val KEY_PB_X = "p_b_x"
    private const val KEY_PB_Y = "p_b_y"
    private const val KEY_PB_SIZE = "p_b_size"
    private const val KEY_PTA_X = "p_ta_x"
    private const val KEY_PTA_Y = "p_ta_y"
    private const val KEY_PTA_SIZE = "p_ta_size"
    private const val KEY_PTB_X = "p_tb_x"
    private const val KEY_PTB_Y = "p_tb_y"
    private const val KEY_PTB_SIZE = "p_tb_size"
    private const val KEY_PSTART_X = "p_start_x"
    private const val KEY_PSTART_Y = "p_start_y"
    private const val KEY_PSTART_SIZE = "p_start_size"
    private const val KEY_PSELECT_X = "p_select_x"
    private const val KEY_PSELECT_Y = "p_select_y"
    private const val KEY_PSELECT_SIZE = "p_select_size"
    private const val KEY_PL_X = "p_l_x"
    private const val KEY_PL_Y = "p_l_y"
    private const val KEY_PL_SIZE = "p_l_size"
    private const val KEY_PR_X = "p_r_x"
    private const val KEY_PR_Y = "p_r_y"
    private const val KEY_PR_SIZE = "p_r_size"
    private const val KEY_PX_X = "p_x_x"
    private const val KEY_PX_Y = "p_x_y"
    private const val KEY_PX_SIZE = "p_x_size"
    private const val KEY_PY_X = "p_y_x"
    private const val KEY_PY_Y = "p_y_y"
    private const val KEY_PY_SIZE = "p_y_size"
    private const val KEY_PQS_X = "p_qs_x"
    private const val KEY_PQS_Y = "p_qs_y"
    private const val KEY_PQS_SIZE = "p_qs_size"
    private const val KEY_PQL_X = "p_ql_x"
    private const val KEY_PQL_Y = "p_ql_y"
    private const val KEY_PQL_SIZE = "p_ql_size"

    // Global keys
    private const val KEY_OPACITY = "opacity"
    private const val KEY_SHOW_PAD = "show_pad"
    private const val KEY_SHOW_FPS = "show_fps"
    private const val KEY_SHOW_PLAYER_SWITCH = "show_player_switch"
    private const val KEY_PLAYER_SWITCH_X = "player_switch_x"
    private const val KEY_PLAYER_SWITCH_Y = "player_switch_y"
    private const val KEY_HIGH_QUALITY_SCALING = "high_quality_scaling"
    // NDS GL 硬件加速默认值迁移标记（一次性）：旧版本默认 disabled 并已
    // 持久化到用户数据里，升级后需要迁移为 enabled（参考官方 melonDS APK
    // 默认开启硬件加速，软渲染是 NDS 卡顿的主因）。
    private const val KEY_NDS_GL_MIGRATION_V2 = "nds_gl_migration_v2"

    // Core option keys
    private const val KEY_NTSC_FILTER = "ntsc_filter"
    private const val KEY_ASPECT_RATIO = "aspect_ratio"
    private const val KEY_PALETTE = "palette"
    private const val KEY_REGION = "region"
    private const val KEY_SOUND_QUALITY = "sound_quality"
    private const val KEY_CROP_OVERSCAN = "crop_overscan"
    private const val KEY_VIDEO_SCALE = "video_scale"
    private const val KEY_VIDEO_FILTER = "video_filter"
    private const val KEY_OVERCLOCKING = "overclocking"
    // Custom free-form layout rect keys (used when videoScale == "custom")
    private const val KEY_CUSTOM_LAYOUT_LEFT = "custom_layout_left"
    private const val KEY_CUSTOM_LAYOUT_TOP = "custom_layout_top"
    private const val KEY_CUSTOM_LAYOUT_RIGHT = "custom_layout_right"
    private const val KEY_CUSTOM_LAYOUT_BOTTOM = "custom_layout_bottom"
    // 竖屏版自由布局 rect keys（横竖屏分别保存）
    private const val KEY_CUSTOM_LAYOUT_LEFT_P = "custom_layout_left_p"
    private const val KEY_CUSTOM_LAYOUT_TOP_P = "custom_layout_top_p"
    private const val KEY_CUSTOM_LAYOUT_RIGHT_P = "custom_layout_right_p"
    private const val KEY_CUSTOM_LAYOUT_BOTTOM_P = "custom_layout_bottom_p"
    // NDS 双屏独立布局 rect keys（videoScale=="custom" 时上/下屏各一个矩形）
    private const val KEY_NDS_TOP_LEFT = "nds_top_layout_left"
    private const val KEY_NDS_TOP_TOP = "nds_top_layout_top"
    private const val KEY_NDS_TOP_RIGHT = "nds_top_layout_right"
    private const val KEY_NDS_TOP_BOTTOM = "nds_top_layout_bottom"
    private const val KEY_NDS_BOTTOM_LEFT = "nds_bottom_layout_left"
    private const val KEY_NDS_BOTTOM_TOP = "nds_bottom_layout_top"
    private const val KEY_NDS_BOTTOM_RIGHT = "nds_bottom_layout_right"
    private const val KEY_NDS_BOTTOM_BOTTOM = "nds_bottom_layout_bottom"
    // 竖屏版
    private const val KEY_NDS_TOP_LEFT_P = "nds_top_layout_left_p"
    private const val KEY_NDS_TOP_TOP_P = "nds_top_layout_top_p"
    private const val KEY_NDS_TOP_RIGHT_P = "nds_top_layout_right_p"
    private const val KEY_NDS_TOP_BOTTOM_P = "nds_top_layout_bottom_p"
    private const val KEY_NDS_BOTTOM_LEFT_P = "nds_bottom_layout_left_p"
    private const val KEY_NDS_BOTTOM_TOP_P = "nds_bottom_layout_top_p"
    private const val KEY_NDS_BOTTOM_RIGHT_P = "nds_bottom_layout_right_p"
    private const val KEY_NDS_BOTTOM_BOTTOM_P = "nds_bottom_layout_bottom_p"

    private fun prefs(ctx: Context): SharedPreferences =
        ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // Helper: load a ButtonLayout from SharedPreferences with a default fallback.
    // Uses key prefix "<prefix>_x" / "<prefix>_y" / "<prefix>_size".
    private fun loadBtn(p: SharedPreferences, prefix: String, default: ButtonLayout): ButtonLayout {
        return ButtonLayout(
            x = p.getFloat("${prefix}_x", default.x),
            y = p.getFloat("${prefix}_y", default.y),
            sizeDp = p.getInt("${prefix}_size", default.sizeDp)
        )
    }

    // Helper: save a ButtonLayout to SharedPreferences with a key prefix.
    private fun SharedPreferences.Editor.saveBtn(prefix: String, layout: ButtonLayout) {
        putFloat("${prefix}_x", layout.x)
        putFloat("${prefix}_y", layout.y)
        putInt("${prefix}_size", layout.sizeDp)
    }

    // =========================================================================
    // 每核心独立存储（屏幕比例 / 自由布局 / 通用虚拟按键布局）
    // -------------------------------------------------------------------------
    // 把通用布局类 key 追加平台后缀（如 "dpad_x" → "dpad_x_NES"），实现每个
    // 核心各自保存视频缩放比例、宽高比、自由布局矩形和通用 A/B/X/Y… 按钮位置，
    // 避免不同核心互相覆盖。全局 key 保留作为「默认值」：某平台 key 不存在时
    // 回退到全局 key，用户在该核心调整后写平台 key，其它核心与全局不受影响。
    private fun pk(platform: GamePlatform?, key: String): String =
        if (platform == null) key else "${key}_${platform.name}"

    private fun SharedPreferences.getFp(platform: GamePlatform?, key: String, def: Float): Float {
        val global = getFloat(key, def)
        return if (platform == null) global
               else if (contains(pk(platform, key))) getFloat(pk(platform, key), global)
               else global
    }

    private fun SharedPreferences.getIp(platform: GamePlatform?, key: String, def: Int): Int {
        val global = getInt(key, def)
        return if (platform == null) global
               else if (contains(pk(platform, key))) getInt(pk(platform, key), global)
               else global
    }

    private fun SharedPreferences.getSp(platform: GamePlatform?, key: String, def: String): String {
        val global = getString(key, def) ?: def
        return if (platform == null) global
               else if (contains(pk(platform, key))) getString(pk(platform, key), global) ?: global
               else global
    }

    fun load(ctx: Context, platform: GamePlatform? = null): PadLayout {
        val p = prefs(ctx)
        return PadLayout().apply {
            // === 横屏布局（每核心独立，回退全局） ===
            dpad = ButtonLayout(
                p.getFp(platform, KEY_DPAD_X, 0.13f),
                p.getFp(platform, KEY_DPAD_Y, 0.78f),
                p.getIp(platform, KEY_DPAD_SIZE, 140)
            )
            btnA = ButtonLayout(
                p.getFp(platform, KEY_A_X, 0.87f),
                p.getFp(platform, KEY_A_Y, 0.76f),
                p.getIp(platform, KEY_A_SIZE, 72)
            )
            btnB = ButtonLayout(
                p.getFp(platform, KEY_B_X, 0.72f),
                p.getFp(platform, KEY_B_Y, 0.82f),
                p.getIp(platform, KEY_B_SIZE, 72)
            )
            btnTurboA = ButtonLayout(
                p.getFp(platform, KEY_TA_X, 0.87f),
                p.getFp(platform, KEY_TA_Y, 0.60f),
                p.getIp(platform, KEY_TA_SIZE, 48)
            )
            btnTurboB = ButtonLayout(
                p.getFp(platform, KEY_TB_X, 0.72f),
                p.getFp(platform, KEY_TB_Y, 0.66f),
                p.getIp(platform, KEY_TB_SIZE, 48)
            )
            btnStart = ButtonLayout(
                p.getFp(platform, KEY_START_X, 0.62f),
                p.getFp(platform, KEY_START_Y, 0.92f),
                p.getIp(platform, KEY_START_SIZE, 56)
            )
            btnSelect = ButtonLayout(
                p.getFp(platform, KEY_SELECT_X, 0.38f),
                p.getFp(platform, KEY_SELECT_Y, 0.92f),
                p.getIp(platform, KEY_SELECT_SIZE, 56)
            )
            btnL = ButtonLayout(
                p.getFp(platform, KEY_L_X, 0.10f),
                p.getFp(platform, KEY_L_Y, 0.15f),
                p.getIp(platform, KEY_L_SIZE, 56)
            )
            btnR = ButtonLayout(
                p.getFp(platform, KEY_R_X, 0.90f),
                p.getFp(platform, KEY_R_Y, 0.15f),
                p.getIp(platform, KEY_R_SIZE, 56)
            )
            btnX = ButtonLayout(
                p.getFp(platform, KEY_X_X, 0.88f),
                p.getFp(platform, KEY_X_Y, 0.54f),
                p.getIp(platform, KEY_X_SIZE, 60)
            )
            btnY = ButtonLayout(
                p.getFp(platform, KEY_Y_X, 0.73f),
                p.getFp(platform, KEY_Y_Y, 0.60f),
                p.getIp(platform, KEY_Y_SIZE, 60)
            )
            btnQuickSave = ButtonLayout(
                p.getFp(platform, KEY_QS_X, 0.42f),
                p.getFp(platform, KEY_QS_Y, 0.18f),
                p.getIp(platform, KEY_QS_SIZE, 40)
            )
            btnQuickLoad = ButtonLayout(
                p.getFp(platform, KEY_QL_X, 0.58f),
                p.getFp(platform, KEY_QL_Y, 0.18f),
                p.getIp(platform, KEY_QL_SIZE, 40)
            )
            // === 竖屏布局（每核心独立，回退全局） ===
            dpadP = ButtonLayout(
                p.getFp(platform, KEY_PDAD_X, 0.18f),
                p.getFp(platform, KEY_PDAD_Y, 0.74f),
                p.getIp(platform, KEY_PDAD_SIZE, 130)
            )
            btnAP = ButtonLayout(
                p.getFp(platform, KEY_PA_X, 0.82f),
                p.getFp(platform, KEY_PA_Y, 0.72f),
                p.getIp(platform, KEY_PA_SIZE, 68)
            )
            btnBP = ButtonLayout(
                p.getFp(platform, KEY_PB_X, 0.68f),
                p.getFp(platform, KEY_PB_Y, 0.80f),
                p.getIp(platform, KEY_PB_SIZE, 68)
            )
            btnTurboAP = ButtonLayout(
                p.getFp(platform, KEY_PTA_X, 0.82f),
                p.getFp(platform, KEY_PTA_Y, 0.56f),
                p.getIp(platform, KEY_PTA_SIZE, 46)
            )
            btnTurboBP = ButtonLayout(
                p.getFp(platform, KEY_PTB_X, 0.68f),
                p.getFp(platform, KEY_PTB_Y, 0.62f),
                p.getIp(platform, KEY_PTB_SIZE, 46)
            )
            btnStartP = ButtonLayout(
                p.getFp(platform, KEY_PSTART_X, 0.62f),
                p.getFp(platform, KEY_PSTART_Y, 0.90f),
                p.getIp(platform, KEY_PSTART_SIZE, 54)
            )
            btnSelectP = ButtonLayout(
                p.getFp(platform, KEY_PSELECT_X, 0.38f),
                p.getFp(platform, KEY_PSELECT_Y, 0.90f),
                p.getIp(platform, KEY_PSELECT_SIZE, 54)
            )
            btnLP = ButtonLayout(
                p.getFp(platform, KEY_PL_X, 0.12f),
                p.getFp(platform, KEY_PL_Y, 0.12f),
                p.getIp(platform, KEY_PL_SIZE, 54)
            )
            btnRP = ButtonLayout(
                p.getFp(platform, KEY_PR_X, 0.88f),
                p.getFp(platform, KEY_PR_Y, 0.12f),
                p.getIp(platform, KEY_PR_SIZE, 54)
            )
            btnXP = ButtonLayout(
                p.getFp(platform, KEY_PX_X, 0.83f),
                p.getFp(platform, KEY_PX_Y, 0.50f),
                p.getIp(platform, KEY_PX_SIZE, 56)
            )
            btnYP = ButtonLayout(
                p.getFp(platform, KEY_PY_X, 0.69f),
                p.getFp(platform, KEY_PY_Y, 0.56f),
                p.getIp(platform, KEY_PY_SIZE, 56)
            )
            btnQuickSaveP = ButtonLayout(
                p.getFp(platform, KEY_PQS_X, 0.42f),
                p.getFp(platform, KEY_PQS_Y, 0.18f),
                p.getIp(platform, KEY_PQS_SIZE, 38)
            )
            btnQuickLoadP = ButtonLayout(
                p.getFp(platform, KEY_PQL_X, 0.58f),
                p.getFp(platform, KEY_PQL_Y, 0.18f),
                p.getIp(platform, KEY_PQL_SIZE, 38)
            )
            // === 全局设置（两个方向共享，不按平台区分） ===
            opacity = p.getFloat(KEY_OPACITY, 0.7f)
            showPad = p.getBoolean(KEY_SHOW_PAD, true)
            showFps = p.getBoolean(KEY_SHOW_FPS, false)
            showPlayerSwitch = p.getBoolean(KEY_SHOW_PLAYER_SWITCH, true)
            playerSwitchX = p.getFloat(KEY_PLAYER_SWITCH_X, 0.94f)
            playerSwitchY = p.getFloat(KEY_PLAYER_SWITCH_Y, 0.07f)
            highQualityScaling = p.getBoolean(KEY_HIGH_QUALITY_SCALING, false)
            ntscFilter = p.getString(KEY_NTSC_FILTER, "disabled") ?: "disabled"
            aspectRatio = p.getSp(platform, KEY_ASPECT_RATIO, "4:3")
            palette = p.getString(KEY_PALETTE, "default") ?: "default"
            region = p.getString(KEY_REGION, "Auto") ?: "Auto"
            soundQuality = p.getString(KEY_SOUND_QUALITY, "Low") ?: "Low"
            cropOverscan = p.getString(KEY_CROP_OVERSCAN, "disabled") ?: "disabled"
            videoScale = p.getSp(platform, KEY_VIDEO_SCALE, "stretch")
            customLayoutLeft = p.getFp(platform, KEY_CUSTOM_LAYOUT_LEFT, 0f)
            customLayoutTop = p.getFp(platform, KEY_CUSTOM_LAYOUT_TOP, 0f)
            customLayoutRight = p.getFp(platform, KEY_CUSTOM_LAYOUT_RIGHT, 1f)
            customLayoutBottom = p.getFp(platform, KEY_CUSTOM_LAYOUT_BOTTOM, 1f)
            customLayoutLeftP = p.getFp(platform, KEY_CUSTOM_LAYOUT_LEFT_P, 0f)
            customLayoutTopP = p.getFp(platform, KEY_CUSTOM_LAYOUT_TOP_P, 0f)
            customLayoutRightP = p.getFp(platform, KEY_CUSTOM_LAYOUT_RIGHT_P, 1f)
            customLayoutBottomP = p.getFp(platform, KEY_CUSTOM_LAYOUT_BOTTOM_P, 1f)
            videoFilter = p.getString(KEY_VIDEO_FILTER, "none") ?: "none"
            overclocking = p.getString(KEY_OVERCLOCKING, "disabled") ?: "disabled"
            sfcReduceSpriteFlicker = p.getString("sfc_reduce_sprite_flicker", "disabled") ?: "disabled"
            sfcReduceSlowdown = p.getString("sfc_reduce_slowdown", "disabled") ?: "disabled"
            sfcAudioInterpolation = p.getString("sfc_audio_interpolation", "gaussian") ?: "gaussian"
            sfcGfxTransparency = p.getString("sfc_gfx_transparency", "enabled") ?: "enabled"
            sfcGfxHires = p.getString("sfc_gfx_hires", "enabled") ?: "enabled"
            sfcGfxClip = p.getString("sfc_gfx_clip", "enabled") ?: "enabled"
            sfcBlockInvalidVram = p.getString("sfc_block_invalid_vram", "disabled") ?: "disabled"
            sfcSoundOutput = p.getString("sfc_sound_output", "disabled") ?: "disabled"
            sfcOverscan = p.getString("sfc_overscan", "enabled") ?: "enabled"
            sfcSideBySide = p.getString("sfc_side_by_side", "disabled") ?: "disabled"
            sfcUpDownAllowed = p.getString("sfc_up_down_allowed", "disabled") ?: "disabled"
            sfcSuperScope = p.getString("sfc_superscope", "disabled") ?: "disabled"
            sfcLayer1 = p.getString("sfc_layer_1", "enabled") ?: "enabled"
            sfcLayer2 = p.getString("sfc_layer_2", "enabled") ?: "enabled"
            sfcLayer3 = p.getString("sfc_layer_3", "enabled") ?: "enabled"
            sfcLayer4 = p.getString("sfc_layer_4", "enabled") ?: "enabled"
            sfcLayer5 = p.getString("sfc_layer_5", "enabled") ?: "enabled"
            sfcOverclock = p.getString("sfc_overclock", "100%") ?: "100%"
            gbColorCorrection = p.getString("gb_color_correction", "enabled") ?: "enabled"
            gbcColorPreset = p.getString("gbc_color_preset", "default") ?: "default"
            gbaColorCorrection = p.getString("gba_color_correction", "enabled") ?: "enabled"
            gbaColorPreset = p.getString("gba_color_preset", "default") ?: "default"
            gbaFrameBlending = p.getString("gba_frame_blending", "OFF") ?: "OFF"
            gbaAudioResampler = p.getString("gba_audio_resampler", "sinc") ?: "sinc"
            gbaAudioLowPass = p.getString("gba_audio_low_pass", "disabled") ?: "disabled"
            gbaAudioLowPassRange = p.getString("gba_audio_low_pass_range", "60") ?: "60"
            // One-time migration (GBA audio fix): old builds hard-coded mGBA's
            // low-pass filter to enabled/50 in the native layer while the
            // in-app toggles were never wired to the core — so a stored
            // "enabled"/"50" pair can only be the old hard-coded default,
            // never an actual user choice. Reset it to the mGBA engine's own
            // default (disabled/60) exactly once, so GBA audio is no longer
            // muffled after upgrade. The filter can still be turned back on
            // in settings — and it now genuinely takes effect.
            if (p.getInt("gba_audio_lowpass_migrated", 0) < 1) {
                if (gbaAudioLowPass == "enabled" && gbaAudioLowPassRange == "50") {
                    gbaAudioLowPass = "disabled"
                    gbaAudioLowPassRange = "60"
                }
                p.edit()
                    .putString("gba_audio_low_pass", gbaAudioLowPass)
                    .putString("gba_audio_low_pass_range", gbaAudioLowPassRange)
                    .putInt("gba_audio_lowpass_migrated", 1)
                    .apply()
            }
            gbaFrameskipType = p.getString("gba_frameskip_type", "disabled") ?: "disabled"
            gbaFrameskipCount = p.getString("gba_frameskip_count", "0") ?: "0"
            gbaSolarSensor = p.getString("gba_solar_sensor", "0") ?: "0"
            gbaIdleOptimization = p.getString("gba_idle_optimization", "disabled") ?: "disabled"
            gbaForceRTC = p.getString("gba_force_rtc", "disabled") ?: "disabled"
            gbaAllowOpposite = p.getString("gba_allow_opposite", "OFF") ?: "OFF"
            gbModel = p.getString("gb_model", "Autodetect") ?: "Autodetect"
            gbSgbBorders = p.getString("gb_sgb_borders", "ON") ?: "ON"
            gbaFrameskipThreshold = p.getString("gba_frameskip_threshold", "33") ?: "33"
            // DOSBox-Pure options
            dosMachine = p.getString("dos_machine", "svga_s3") ?: "svga_s3"
            dosCycles = p.getString("dos_cycles", "auto") ?: "auto"
            dosCyclesMax = p.getString("dos_cycles_max", "50000") ?: "50000"
            dosSbType = p.getString("dos_sb_type", "sb16") ?: "sb16"
            dosSbAdlibMode = p.getString("dos_sb_adlib_mode", "off") ?: "off"
            dosSbAdlibEmu = p.getString("dos_sb_adlib_emu", "default") ?: "default"
            dosGus = p.getString("dos_gus", "off") ?: "off"
            // Migrate the old invalid dosbox_pure_mouse_input values
            // ("emulated"/"absolute"/"ps2"/"none") to the valid "touchpad".
            dosMouseInput = run {
                val v = p.getString("dos_mouse_input", "touchpad") ?: "touchpad"
                if (v in setOf("emulated", "absolute", "ps2", "none")) "touchpad" else v
            }
            // 音频（核心自带混音器）+ CPU/内存/视频补充选项
            dosAudiorate = p.getString("dos_audiorate", "48000") ?: "48000"
            dosSwapStereo = p.getString("dos_swap_stereo", "false") ?: "false"
            dosTandySound = p.getString("dos_tandysound", "auto") ?: "auto"
            dosCpuCore = p.getString("dos_cpu_core", "auto") ?: "auto"
            dosCpuType = p.getString("dos_cpu_type", "auto") ?: "auto"
            dosMemorySize = p.getString("dos_memory_size", "16") ?: "16"
            dosAspectCorrection = p.getString("dos_aspect_correction", "false") ?: "false"
            // 迁移旧的无效键 dos_cga_colors → 新 cga 模式值
            dosCgaMode = p.getString("dos_cga_mode", "early_auto") ?: "early_auto"
            dosKeyboardLayout = p.getString("dos_keyboard_layout", "us") ?: "us"
            dosAutoMapping = p.getString("dos_auto_mapping", "on") ?: "on"
            dosSavestate = p.getString("dos_savestate", "on") ?: "on"
            dosVoodoo = p.getString("dos_voodoo", "off") ?: "off"
            dosForce60fps = p.getString("dos_force60fps", "on") ?: "on"
            dosInputMode = p.getString("dos_input_mode", "gamepad") ?: "gamepad"
            javaInputMode = p.getString("java_input_mode", "gamepad") ?: "gamepad"
            javaOpacity = p.getFloat("java_opacity", 0.8f).coerceIn(0.3f, 1f)
            javaScaleType = p.getString("java_scale_type", "fit") ?: "fit"
            javaShowFps = p.getBoolean("java_show_fps", false)
            javaImmediateMode = p.getBoolean("java_immediate_mode", false)
            javaResolution = p.getString("java_resolution", "default") ?: "default"
            javaScaleRatio = p.getString("java_scale_ratio", "100") ?: "100"
            javaFpsLimit = p.getString("java_fps_limit", "0") ?: "0"
            javaTouchInput = p.getBoolean("java_touch_input", true)
            javaNumDualDispatch = p.getBoolean("java_num_dual_dispatch", true)
            javaButtonKeyMap = p.getString("java_button_key_map", "") ?: ""
            overlayThemeJson = p.getString("overlay_theme_json", "") ?: ""
            javaPhoneGrid = loadBtn(p, "java_phone_grid", ButtonLayout(x = 0.5f, y = 0.80f, sizeDp = 48))
            javaPhoneTop = loadBtn(p, "java_phone_top", ButtonLayout(x = 0.5f, y = 0.60f, sizeDp = 40))
            // DOS gamepad overlay button positions (landscape)
            dosDpad = loadBtn(p, "dos_dpad", ButtonLayout(x = 0.13f, y = 0.78f, sizeDp = 140))
            dosBtnEsc = loadBtn(p, "dos_btn_esc", ButtonLayout(x = 0.87f, y = 0.62f, sizeDp = 56))
            dosBtnEnter = loadBtn(p, "dos_btn_enter", ButtonLayout(x = 0.92f, y = 0.76f, sizeDp = 56))
            dosBtnSpace = loadBtn(p, "dos_btn_space", ButtonLayout(x = 0.78f, y = 0.82f, sizeDp = 56))
            dosBtnTab = loadBtn(p, "dos_btn_tab", ButtonLayout(x = 0.87f, y = 0.92f, sizeDp = 56))
            dosBtnCtrl = loadBtn(p, "dos_btn_ctrl", ButtonLayout(x = 0.30f, y = 0.92f, sizeDp = 48))
            dosBtnAlt = loadBtn(p, "dos_btn_alt", ButtonLayout(x = 0.42f, y = 0.92f, sizeDp = 48))
            dosBtnShift = loadBtn(p, "dos_btn_shift", ButtonLayout(x = 0.54f, y = 0.92f, sizeDp = 48))
            dosBtnBack = loadBtn(p, "dos_btn_back", ButtonLayout(x = 0.66f, y = 0.92f, sizeDp = 48))
            dosBtnMouseL = loadBtn(p, "dos_btn_mouse_l", ButtonLayout(x = 0.92f, y = 0.40f, sizeDp = 40))
            dosBtnMouseR = loadBtn(p, "dos_btn_mouse_r", ButtonLayout(x = 0.82f, y = 0.40f, sizeDp = 40))
            // DOS gamepad overlay button positions (portrait)
            dosDpadP = loadBtn(p, "dos_dpad_p", ButtonLayout(x = 0.18f, y = 0.74f, sizeDp = 130))
            dosBtnEscP = loadBtn(p, "dos_btn_esc_p", ButtonLayout(x = 0.82f, y = 0.58f, sizeDp = 52))
            dosBtnEnterP = loadBtn(p, "dos_btn_enter_p", ButtonLayout(x = 0.88f, y = 0.72f, sizeDp = 52))
            dosBtnSpaceP = loadBtn(p, "dos_btn_space_p", ButtonLayout(x = 0.74f, y = 0.80f, sizeDp = 52))
            dosBtnTabP = loadBtn(p, "dos_btn_tab_p", ButtonLayout(x = 0.82f, y = 0.90f, sizeDp = 52))
            dosBtnCtrlP = loadBtn(p, "dos_btn_ctrl_p", ButtonLayout(x = 0.30f, y = 0.92f, sizeDp = 46))
            dosBtnAltP = loadBtn(p, "dos_btn_alt_p", ButtonLayout(x = 0.42f, y = 0.92f, sizeDp = 46))
            dosBtnShiftP = loadBtn(p, "dos_btn_shift_p", ButtonLayout(x = 0.54f, y = 0.92f, sizeDp = 46))
            dosBtnBackP = loadBtn(p, "dos_btn_back_p", ButtonLayout(x = 0.66f, y = 0.92f, sizeDp = 46))
            dosBtnMouseLP = loadBtn(p, "dos_btn_mouse_l_p", ButtonLayout(x = 0.92f, y = 0.36f, sizeDp = 38))
            dosBtnMouseRP = loadBtn(p, "dos_btn_mouse_r_p", ButtonLayout(x = 0.82f, y = 0.36f, sizeDp = 38))
            // DOS extra buttons (landscape)
            dosBtnInsert = loadBtn(p, "dos_btn_insert", ButtonLayout(x = 0.08f, y = 0.40f, sizeDp = 40))
            dosBtnDelete = loadBtn(p, "dos_btn_delete", ButtonLayout(x = 0.08f, y = 0.50f, sizeDp = 40))
            dosBtnHome = loadBtn(p, "dos_btn_home", ButtonLayout(x = 0.08f, y = 0.30f, sizeDp = 40))
            dosBtnEnd = loadBtn(p, "dos_btn_end", ButtonLayout(x = 0.08f, y = 0.60f, sizeDp = 40))
            dosBtnPageUp = loadBtn(p, "dos_btn_pageup", ButtonLayout(x = 0.08f, y = 0.20f, sizeDp = 40))
            dosBtnPageDown = loadBtn(p, "dos_btn_pagedown", ButtonLayout(x = 0.08f, y = 0.70f, sizeDp = 40))
            // DOS extra buttons (portrait)
            dosBtnInsertP = loadBtn(p, "dos_btn_insert_p", ButtonLayout(x = 0.10f, y = 0.36f, sizeDp = 38))
            dosBtnDeleteP = loadBtn(p, "dos_btn_delete_p", ButtonLayout(x = 0.10f, y = 0.46f, sizeDp = 38))
            dosBtnHomeP = loadBtn(p, "dos_btn_home_p", ButtonLayout(x = 0.10f, y = 0.26f, sizeDp = 38))
            dosBtnEndP = loadBtn(p, "dos_btn_end_p", ButtonLayout(x = 0.10f, y = 0.56f, sizeDp = 38))
            dosBtnPageUpP = loadBtn(p, "dos_btn_pageup_p", ButtonLayout(x = 0.10f, y = 0.16f, sizeDp = 38))
            dosBtnPageDownP = loadBtn(p, "dos_btn_pagedown_p", ButtonLayout(x = 0.10f, y = 0.66f, sizeDp = 38))
            // DOS button visibility toggles
            dosShowDpad = p.getBoolean("dos_show_dpad", true)
            dosShowEsc = p.getBoolean("dos_show_esc", true)
            dosShowEnter = p.getBoolean("dos_show_enter", true)
            dosShowSpace = p.getBoolean("dos_show_space", true)
            dosShowTab = p.getBoolean("dos_show_tab", true)
            dosShowCtrl = p.getBoolean("dos_show_ctrl", true)
            dosShowAlt = p.getBoolean("dos_show_alt", true)
            dosShowShift = p.getBoolean("dos_show_shift", true)
            dosShowBack = p.getBoolean("dos_show_back", true)
            dosShowMouseL = p.getBoolean("dos_show_mouse_l", true)
            dosShowMouseR = p.getBoolean("dos_show_mouse_r", true)
            dosShowInsert = p.getBoolean("dos_show_insert", false)
            dosShowDelete = p.getBoolean("dos_show_delete", false)
            dosShowHome = p.getBoolean("dos_show_home", false)
            dosShowEnd = p.getBoolean("dos_show_end", false)
            dosShowPageUp = p.getBoolean("dos_show_pageup", false)
            dosShowPageDown = p.getBoolean("dos_show_pagedown", false)
            dosExtraKeys = p.getString("dos_extra_keys", "") ?: ""
            dosExtraKeysP = p.getString("dos_extra_keys_p", "") ?: ""
            screenOrientation = p.getString("screen_orientation", "sensor") ?: "sensor"
            // FBNeo (Arcade) options
            arcadeAspect = p.getString("arcade_aspect", "auto") ?: "auto"
            arcadeRotate = p.getString("arcade_rotate", "norotate") ?: "norotate"
            arcadeVerticalMode = p.getString("arcade_vertical_mode", "disabled") ?: "disabled"
            arcadeCropOverscan = p.getString("arcade_crop_overscan", "enabled") ?: "enabled"
            arcadeCpuSpeed = p.getString("arcade_cpu_speed", "100") ?: "100"
            arcadeFrameskip = p.getString("arcade_frameskip", "0") ?: "0"
            arcadeForce60hz = p.getString("arcade_force_60hz", "disabled") ?: "disabled"
            arcadeSampleRate = p.getString("arcade_sample_rate", "48000") ?: "48000"
            arcadeAudioInterp = p.getString("arcade_audio_interp", "2") ?: "2"
            arcadeLowpass = p.getString("arcade_lowpass", "disabled") ?: "disabled"
            arcadeNeogeomode = p.getString("arcade_neogeo_mode", "MVS") ?: "MVS"
            arcadeMemcard = p.getString("arcade_memcard", "enabled") ?: "enabled"
            // Genesis-Plus-GX (MD/SEGA) options
            mdRegion = p.getString("md_region", "auto") ?: "auto"
            mdSystem = p.getString("md_system", "auto") ?: "auto"
            mdAspect = p.getString("md_aspect", "auto") ?: "auto"
            mdRender = p.getString("md_render", "normal") ?: "normal"
            mdNtscFilter = p.getString("md_ntsc_filter", "disabled") ?: "disabled"
            mdLcdFilter = p.getString("md_lcd_filter", "disabled") ?: "disabled"
            mdOverscan = p.getString("md_overscan", "disabled") ?: "disabled"
            mdGgExtra = p.getString("md_gg_extra", "disabled") ?: "disabled"
            mdLeftBorder = p.getString("md_left_border", "disabled") ?: "disabled"
            mdInput = p.getString("md_input", "6 button") ?: "6 button"
            mdAllowUpDown = p.getString("md_allow_up_down", "disabled") ?: "disabled"
            mdOverclock = p.getString("md_overclock", "100%") ?: "100%"
            mdFrameskip = p.getString("md_frameskip", "0") ?: "0"
            mdCdFastboot = p.getString("md_cd_fastboot", "enabled") ?: "enabled"
            mdSmsFm = p.getString("md_sms_fm", "auto") ?: "auto"
            mdGgStretch = p.getString("md_gg_stretch", "disabled") ?: "disabled"
            // NDS (melonDS) options
            // Values must match the 0.9.3 core; migrate legacy stored values.
            ndsUseFwBios = p.getString("nds_use_fw_bios", "enabled") ?: "enabled"
            ndsConsoleMode = when (p.getString("nds_console_mode", "DS") ?: "DS") {
                "ds" -> "DS"
                else -> (p.getString("nds_console_mode", "DS") ?: "DS")
            }
            ndsTouchMode = when (p.getString("nds_touch_mode", "Touch") ?: "Touch") {
                "mouse" -> "Mouse"
                "touch" -> "Touch"
                else -> (p.getString("nds_touch_mode", "Touch") ?: "Touch")
            }
            ndsScreenLayout = when (p.getString("nds_screen_layout", "Top/Bottom") ?: "Top/Bottom") {
                "top_bottom" -> "Top/Bottom"
                "bottom_top" -> "Bottom/Top"
                "left_right" -> "Left/Right"
                "right_left" -> "Right/Left"
                "top_only" -> "Top Only"
                "bottom_only" -> "Bottom Only"
                else -> (p.getString("nds_screen_layout", "Top/Bottom") ?: "Top/Bottom")
            }
            ndsResolution = when (val v = p.getString("nds_resolution", "1x native (256x192)") ?: "1x native (256x192)") {
                "1" -> "1x native (256x192)"
                "2" -> "2x native (512x384)"
                "3" -> "3x native (768x576)"
                "4" -> "4x native (1024x768)"
                "5" -> "5x native (1280x960)"
                "6" -> "6x native (1536x1152)"
                "7" -> "7x native (1792x1344)"
                "8" -> "8x native (2048x1536)"
                // 迁移旧格式（不含 "native" 关键字）
                "1x (256x192)" -> "1x native (256x192)"
                "2x (512x384)" -> "2x native (512x384)"
                "3x (768x576)" -> "3x native (768x576)"
                "4x (1024x768)" -> "4x native (1024x768)"
                "5x (1280x960)" -> "5x native (1280x960)"
                "6x (1536x1152)" -> "6x native (1536x1152)"
                "7x (1792x1344)" -> "7x native (1792x1344)"
                "8x (2048x1536)" -> "8x native (2048x1536)"
                else -> v
            }
            // 默认 "enabled"：NDS 硬件加速 GL 渲染（参考官方 melonDS APK 已默认开启）。
            // 注意必须与类字段默认值一致，否则 load() 会把内存默认值覆盖回 disabled。
            ndsOpenGlRenderer = p.getString("nds_opengl_renderer", "enabled") ?: "enabled"
            // === 一次性迁移：旧版本默认 disabled 且已写入用户数据 ===
            // 升级安装的用户（保留应用数据）会带着旧默认值 "disabled" 进来，
            // 导致 GL 硬件加速修复实际不生效（NDS 仍然卡顿）。这里把旧默认值
            // 迁移为 "enabled"；迁移标记确保只做一次——之后用户若手动关闭
            // 会被尊重（UI 里有 "3D 渲染器" 开关）。
            if (ndsOpenGlRenderer == "disabled" &&
                !p.getBoolean(KEY_NDS_GL_MIGRATION_V2, false)) {
                ndsOpenGlRenderer = "enabled"
            }
            ndsOpenGlBetterPolygons = p.getString("nds_opengl_better_polygons", "disabled") ?: "disabled"
            ndsOpenGlFiltering = p.getString("nds_opengl_filtering", "nearest") ?: "nearest"
            ndsFiltering = p.getString("nds_filtering", "nearest") ?: "nearest"
            ndsScreensaver = p.getString("nds_screensaver", "disabled") ?: "disabled"
            ndsMouseSpeed = p.getString("nds_mouse_speed", "100") ?: "100"
            ndsDsiSdcard = p.getString("nds_dsi_sdcard", "disabled") ?: "disabled"
            ndsRandomizeMac = p.getString("nds_randomize_mac", "disabled") ?: "disabled"
            ndsJitEnable = p.getString("nds_jit_enable", "enabled") ?: "enabled"
            ndsAudioInterpolation = p.getString("nds_audio_interpolation", "Cosine") ?: "Cosine"
            ndsUseFwSettings = p.getString("nds_use_fw_settings", "disabled") ?: "disabled"
            ndsSaveMode = p.getString("nds_save_mode", "nesstation") ?: "nesstation"
            // DraStic（激烈）核心：音量 0..100
            ndsDrasticVolume = (p.getString("nds_drastic_volume", "100") ?: "100")
                .toIntOrNull()?.coerceIn(0, 100)?.toString() ?: "100"
            // DraStic 其余设置（均为字符串枚举，非法值回退默认）。
            // ★ 默认值必须与原版出厂默认一致（尤其 hd_render=disabled：
            // 旧版本此处误写 "enabled" 导致高清默认开启、3D 大游戏单线程
            // 光栅化来不及完成 → 画面只剩顶部一条，已修正）。
            fun dr(key: String, def: String, allowed: Set<String>? = null): String {
                val v = p.getString(key, def) ?: def
                return if (allowed != null && v !in allowed) def else v
            }
            ndsDrasticSound = dr("nds_drastic_sound", "enabled", setOf("enabled", "disabled"))
            ndsDrasticHdRender = dr("nds_drastic_hd_render", "disabled", setOf("enabled", "disabled"))
            ndsDrasticFrameskipType = dr("nds_drastic_frameskip_type", "none", setOf("none", "manual", "auto"))
            ndsDrasticFrameskipValue = (p.getString("nds_drastic_frameskip_value", "4") ?: "4")
                .toIntOrNull()?.coerceIn(0, 9)?.toString() ?: "4"
            ndsDrasticFrameskipSafe = dr("nds_drastic_frameskip_safe", "disabled", setOf("enabled", "disabled"))
            ndsDrasticThreaded3D = dr("nds_drastic_threaded_3d", "disabled", setOf("enabled", "disabled"))
            ndsDrastic16Bit = dr("nds_drastic_16bit", "disabled", setOf("enabled", "disabled"))
            ndsDrasticEdgeMarking = dr("nds_drastic_edge_marking", "disabled", setOf("enabled", "disabled"))
            ndsDrasticFixMainScreen = dr("nds_drastic_fix_main_screen", "disabled", setOf("enabled", "disabled"))
            ndsDrasticAudioLatency = (p.getString("nds_drastic_audio_latency", "3") ?: "3")
                .toIntOrNull()?.coerceIn(0, 3)?.toString() ?: "3"
            ndsDrasticMicEnabled = dr("nds_drastic_mic_enabled", "enabled", setOf("enabled", "disabled"))
            ndsDrasticMicLevel = (p.getString("nds_drastic_mic_level", "1") ?: "1")
                .toIntOrNull()?.coerceIn(0, 3)?.toString() ?: "1"
            ndsDrasticAutofireSpeed = (p.getString("nds_drastic_autofire_speed", "2") ?: "2")
                .toIntOrNull()?.coerceIn(0, 4)?.toString() ?: "2"
            ndsDrasticFfwdRate = (p.getString("nds_drastic_ffwd_rate", "2") ?: "2")
                .toIntOrNull()?.coerceIn(0, 5)?.toString() ?: "2"
            ndsDrasticSlot2Type = dr("nds_drastic_slot2_type", "gba",
                setOf("none", "gba", "sram", "rumble", "motion_official", "motion_homebrew"))
            ndsDrasticRtcSystemTime = dr("nds_drastic_rtc_system_time", "disabled", setOf("enabled", "disabled"))
            ndsDrasticCheatsEnabled = dr("nds_drastic_cheats_enabled", "enabled", setOf("enabled", "disabled"))
            ndsDrasticLuaEnabled = dr("nds_drastic_lua_enabled", "enabled", setOf("enabled", "disabled"))
            ndsDrasticBackupInSavestates = dr("nds_drastic_backup_in_savestates", "enabled", setOf("enabled", "disabled"))
            ndsDrasticIgnoreCardLimit = dr("nds_drastic_ignore_card_limit", "disabled", setOf("enabled", "disabled"))
            ndsDrasticAutoTrim = dr("nds_drastic_auto_trim", "disabled", setOf("enabled", "disabled"))
            ndsDrasticPreloadRoms = dr("nds_drastic_preload_roms", "disabled", setOf("enabled", "disabled"))
            ndsDrasticShowFps = dr("nds_drastic_show_fps", "disabled", setOf("enabled", "disabled"))
            ndsDrasticThreads = (p.getString("nds_drastic_threads", "0") ?: "0")
                .toIntOrNull()?.coerceIn(0, 8)?.toString() ?: "0"
            ndsDrasticAutosave = dr("nds_drastic_autosave", "0", setOf("0", "300", "900", "1800"))
            ndsDrasticSaveFormat = dr("nds_drastic_save_format", "sav", setOf("sav", "dsv"))
            ndsDrasticSmoothFilter = dr("nds_drastic_smooth_filter", "enabled", setOf("enabled", "disabled"))
            ndsDrasticDisplayMode = dr("nds_drastic_display_mode", "gl", setOf("gl", "canvas"))
            // 全局存档方式迁移：老版本只有 nds_save_mode（NDS 独有），
            // 升级后继承用户已选的 NDS 存档方式作为全局默认。
            globalSaveMode = p.getString("global_save_mode", null)
                ?: p.getString("nds_save_mode", "nesstation")
                ?: "nesstation"
            // === 主页个性化 ===
            homeBackgroundUri = p.getString("home_bg_uri", "") ?: ""
            homeBackgroundIsVideo = p.getBoolean("home_bg_is_video", false)
            homeTileIcons = p.getString("home_tile_icons", "") ?: ""
            homeTileIconAlphas = p.getString("home_tile_icon_alphas", "") ?: ""
            ndsTopLayoutLeft = p.getFloat(KEY_NDS_TOP_LEFT, ndsTopLayoutLeft)
            ndsTopLayoutTop = p.getFloat(KEY_NDS_TOP_TOP, ndsTopLayoutTop)
            ndsTopLayoutRight = p.getFloat(KEY_NDS_TOP_RIGHT, ndsTopLayoutRight)
            ndsTopLayoutBottom = p.getFloat(KEY_NDS_TOP_BOTTOM, ndsTopLayoutBottom)
            ndsBottomLayoutLeft = p.getFloat(KEY_NDS_BOTTOM_LEFT, ndsBottomLayoutLeft)
            ndsBottomLayoutTop = p.getFloat(KEY_NDS_BOTTOM_TOP, ndsBottomLayoutTop)
            ndsBottomLayoutRight = p.getFloat(KEY_NDS_BOTTOM_RIGHT, ndsBottomLayoutRight)
            ndsBottomLayoutBottom = p.getFloat(KEY_NDS_BOTTOM_BOTTOM, ndsBottomLayoutBottom)
            ndsTopLayoutLeftP = p.getFloat(KEY_NDS_TOP_LEFT_P, ndsTopLayoutLeftP)
            ndsTopLayoutTopP = p.getFloat(KEY_NDS_TOP_TOP_P, ndsTopLayoutTopP)
            ndsTopLayoutRightP = p.getFloat(KEY_NDS_TOP_RIGHT_P, ndsTopLayoutRightP)
            ndsTopLayoutBottomP = p.getFloat(KEY_NDS_TOP_BOTTOM_P, ndsTopLayoutBottomP)
            ndsBottomLayoutLeftP = p.getFloat(KEY_NDS_BOTTOM_LEFT_P, ndsBottomLayoutLeftP)
            ndsBottomLayoutTopP = p.getFloat(KEY_NDS_BOTTOM_TOP_P, ndsBottomLayoutTopP)
            ndsBottomLayoutRightP = p.getFloat(KEY_NDS_BOTTOM_RIGHT_P, ndsBottomLayoutRightP)
            ndsBottomLayoutBottomP = p.getFloat(KEY_NDS_BOTTOM_BOTTOM_P, ndsBottomLayoutBottomP)
            // PSX (PCSX-ReARMed) options
            pscxBios = p.getString("psx_bios", "auto") ?: "auto"
            pscxRegion = p.getString("psx_region", "auto") ?: "auto"
            pscxFrameskipType = run {
                val v = p.getString("psx_frameskip_type", "disabled") ?: "disabled"
                // 修复旧版值: "fixed" → 规范的 "fixed_interval" (核心真实枚举)
                if (v == "fixed") "fixed_interval" else v
            }
            pscxFrameskipThreshold = p.getString("psx_frameskip_threshold", "33") ?: "33"
            pscxFrameskip = run {
                val v = p.getString("psx_frameskip", "3") ?: "3"
                if (v == "0" || v.isBlank()) "3" else v   // 旧默认 0 对 fixed_interval 无意义
            }
            pscxDrcThread = p.getString("psx_drc_thread", "auto") ?: "auto"
            pscxGpuThreadRendering = p.getString("psx_gpu_thread_rendering", "auto") ?: "auto"
            pscxIcache = p.getString("psx_icache", "enabled") ?: "enabled"
            pscxCdTurbo = p.getString("psx_cd_turbo", "disabled") ?: "disabled"
            pscxFractionalFps = p.getString("psx_fractional_fps", "auto") ?: "auto"
            pscxAltFlip = p.getString("psx_alt_flip", "auto") ?: "auto"
            pscxNeonInterlace = p.getString("psx_neon_interlace", "disabled") ?: "disabled"
            pscxNeonEnhance = p.getString("psx_neon_enhance", "disabled") ?: "disabled"
            pscxCentering = p.getString("psx_centering", "auto") ?: "auto"
            pscxCdAudio = p.getString("psx_cd_audio", "enabled") ?: "enabled"
            pscxXaAudio = p.getString("psx_xa_audio", "enabled") ?: "enabled"
            pscxSpuThread = p.getString("psx_spu_thread", "disabled") ?: "disabled"
            pscxNegconResponse = p.getString("psx_negcon_response", "linear") ?: "linear"
            pscxNegconDeadzone = p.getString("psx_negcon_deadzone", "0") ?: "0"
            pscxPad1Type = p.getString("psx_pad1_type", "standard") ?: "standard"
            pscxPad2Type = p.getString("psx_pad2_type", "standard") ?: "standard"
            pscxVibration = p.getString("psx_vibration", "enabled") ?: "enabled"
            pscxDithering = p.getString("psx_dithering", "enabled") ?: "enabled"
            pscxSpuInterp = p.getString("psx_spu_interp", "simple") ?: "simple"
            pscxSpuReverb = p.getString("psx_spu_reverb", "enabled") ?: "enabled"
            pscxShowBootlogo = p.getString("psx_show_bootlogo", "disabled") ?: "disabled"
            pscxCdReadahead = p.getString("psx_cd_readahead", "12") ?: "12"
            pscxMemcard1 = p.getString("psx_memcard1", "libretro") ?: "libretro"
            pscxMemcard2 = p.getString("psx_memcard2", "shared") ?: "shared"
            pscxDrc = p.getString("psx_drc", "enabled") ?: "enabled"
            pscxClock = p.getString("psx_clock", "auto") ?: "auto"
            pscxRgb32 = p.getString("psx_rgb32", "disabled") ?: "disabled"
            pscxScaleHires = p.getString("psx_scale_hires", "disabled") ?: "disabled"
            pscxShowOverscan = p.getString("psx_show_overscan", "disabled") ?: "disabled"
            pscxMultitap = run {
                val v = p.getString("psx_multitap", "disabled") ?: "disabled"
                // 修复旧版无效值 port1/port2/both → 核心枚举
                when (v) { "port1" -> "port 1"; "port2" -> "port 2"; "both" -> "ports 1 and 2"; else -> v }
            }
            pscxGpuOddEven = p.getString("psx_gpu_odd_even", "disabled") ?: "disabled"
            pscxAnalogAxis = p.getString("psx_analog_axis", "square") ?: "square"
            // DC (libretro Flycast) options
            dcResolution = p.getString("dc_resolution", "640x480 (Native)") ?: "640x480 (Native)"
            dcAlphaSorting = p.getString("dc_alpha_sorting", "per-triangle (normal)") ?: "per-triangle (normal)"
            dcThreadedRendering = p.getString("dc_threaded_rendering", "enabled") ?: "enabled"
            dcDelayFrameSwapping = p.getString("dc_delay_frame_swapping", "enabled") ?: "enabled"
            dcFrameSkipping = p.getString("dc_frame_skipping", "disabled") ?: "disabled"
            dcWidescreenHack = p.getString("dc_widescreen_hack", "disabled") ?: "disabled"
            dcWidescreenCheats = p.getString("dc_widescreen_cheats", "disabled") ?: "disabled"
            dcGdromFastLoading = p.getString("dc_gdrom_fast_loading", "enabled") ?: "enabled"
            dcHleBios = p.getString("dc_hle_bios", "disabled") ?: "disabled"
            dcRam32mb = p.getString("dc_ram_32mb", "disabled") ?: "disabled"
            dcForceWince = p.getString("dc_force_wince", "disabled") ?: "disabled"
            dcEnableDsp = p.getString("dc_enable_dsp", "disabled") ?: "disabled"
            dcRegion = p.getString("dc_region", "Default") ?: "Default"
            dcLanguage = p.getString("dc_language", "Default") ?: "Default"
            dcBroadcast = p.getString("dc_broadcast", "Default") ?: "Default"
            dcCableType = p.getString("dc_cable_type", "TV (Composite)") ?: "TV (Composite)"
            // === PS2 (PCEE2 — PCSX2 core) core options + 专属按键布局 ===
            // PCEE2 迁移：旧值 "1x"/"2x"/"4x"/"8x" 自动归一到 "1".."4"
            ps2ResMulti = PadLayout.normalizePs2ResMulti(p.getString("ps2_res_multi", null))
            // 核心已编译 Vulkan，auto/opengl/vulkan/software 均为合法值；非法旧值回落 auto。
            ps2Renderer = p.getString("ps2_renderer", "auto")?.takeIf {
                it in setOf("auto", "opengl", "vulkan", "software") } ?: "auto"
            ps2Bilinear = p.getString("ps2_bilinear", "enabled")?.takeIf { it == "disabled" || it == "enabled" } ?: "enabled"
            ps2FastBoot = p.getString("ps2_fast_boot", "enabled")?.takeIf { it == "disabled" || it == "enabled" } ?: "enabled"
            ps2HwDownloadMode = p.getString("ps2_hw_download_mode", "unsynchronized")?.takeIf {
                it in setOf("accurate", "force_full", "no_readbacks", "unsynchronized", "async", "disabled") } ?: "unsynchronized"
            ps2BlendingAccuracy = p.getString("ps2_blending_accuracy", "basic")?.takeIf {
                it in setOf("minimum", "basic", "medium", "high", "full", "maximum") } ?: "basic"
            ps2Trilinear = p.getString("ps2_trilinear", "auto")?.takeIf {
                it in setOf("auto", "off", "ps2", "forced") } ?: "auto"
            ps2Anisotropic = p.getString("ps2_anisotropic", "0")?.takeIf {
                it in setOf("0", "2", "4", "8", "16") } ?: "0"
            ps2Dithering = p.getString("ps2_dithering", "2")?.takeIf {
                it in setOf("0", "1", "2") } ?: "2"
            ps2Mipmapping = p.getString("ps2_mipmapping", "enabled")?.takeIf {
                it == "disabled" || it == "enabled" } ?: "enabled"
            ps2Deinterlace = p.getString("ps2_deinterlace", "0")?.takeIf {
                it.toIntOrNull() in 0..9 } ?: "0"
            ps2AspectRatio = p.getString("ps2_aspect_ratio", "auto")?.takeIf {
                it == "auto" || it == "4:3" || it == "16:9" } ?: "auto"
            ps2Widescreen = p.getString("ps2_widescreen", "disabled")?.takeIf {
                it == "disabled" || it == "enabled" } ?: "disabled"
            ps2NoInterlace = p.getString("ps2_no_interlace", "disabled")?.takeIf {
                it == "disabled" || it == "enabled" } ?: "disabled"
            ps2Mtvu = p.getString("ps2_mtvu", "enabled")?.takeIf {
                it == "disabled" || it == "enabled" } ?: "enabled"
            ps2InstantVu1 = p.getString("ps2_instant_vu1", "enabled")?.takeIf {
                it == "disabled" || it == "enabled" } ?: "enabled"
            ps2EeCycleRate = p.getString("ps2_ee_cycle_rate", "0")?.takeIf {
                it in setOf("-3", "-2", "-1", "0", "1", "2", "3") } ?: "0"
            ps2EeCycleSkip = p.getString("ps2_ee_cycle_skip", "0")?.takeIf {
                it in setOf("0", "1", "2", "3") } ?: "0"
            ps2TvShader = p.getString("ps2_tv_shader", "0")?.takeIf { it.toIntOrNull() in 0..7 } ?: "0"
                        ps2ShadeBoost = p.getString("ps2_shade_boost", "disabled")?.takeIf {
                            it == "disabled" || it == "enabled" } ?: "disabled"
                        ps2HalfPixelOffset = p.getString("ps2_half_pixel_offset", "0")?.takeIf {
                            it.toIntOrNull() in 0..5 } ?: "0"
                        ps2TexturePreloading = p.getString("ps2_texture_preloading", "1")?.takeIf {
                            it.toIntOrNull() in 0..2 } ?: "1"
                        ps2Rumble = p.getString("ps2_rumble", "enabled")?.takeIf { it == "disabled" || it == "enabled" } ?: "enabled"
            ps2Dpad = loadBtn(p, "ps2_dpad", ButtonLayout(x = 0.13f, y = 0.55f, sizeDp = 110))
            ps2DpadP = loadBtn(p, "ps2_p_dpad", ButtonLayout(x = 0.18f, y = 0.58f, sizeDp = 104))
            ps2LStick = loadBtn(p, "ps2_lstick", ButtonLayout(x = 0.13f, y = 0.88f, sizeDp = 112))
            ps2RStick = loadBtn(p, "ps2_rstick", ButtonLayout(x = 0.87f, y = 0.88f, sizeDp = 112))
            ps2LStickP = loadBtn(p, "ps2_p_lstick", ButtonLayout(x = 0.18f, y = 0.78f, sizeDp = 96))
            ps2RStickP = loadBtn(p, "ps2_p_rstick", ButtonLayout(x = 0.82f, y = 0.80f, sizeDp = 96))
            ps2BtnA = loadBtn(p, "ps2_btn_a", ButtonLayout(x = 0.87f, y = 0.58f, sizeDp = 56))
            ps2BtnB = loadBtn(p, "ps2_btn_b", ButtonLayout(x = 0.95f, y = 0.50f, sizeDp = 56))
            ps2BtnX = loadBtn(p, "ps2_btn_x", ButtonLayout(x = 0.79f, y = 0.50f, sizeDp = 56))
            ps2BtnY = loadBtn(p, "ps2_btn_y", ButtonLayout(x = 0.87f, y = 0.42f, sizeDp = 56))
            ps2BtnAP = loadBtn(p, "ps2_p_btn_a", ButtonLayout(x = 0.82f, y = 0.60f, sizeDp = 50))
            ps2BtnBP = loadBtn(p, "ps2_p_btn_b", ButtonLayout(x = 0.91f, y = 0.52f, sizeDp = 50))
            ps2BtnXP = loadBtn(p, "ps2_p_btn_x", ButtonLayout(x = 0.73f, y = 0.52f, sizeDp = 50))
            ps2BtnYP = loadBtn(p, "ps2_p_btn_y", ButtonLayout(x = 0.82f, y = 0.44f, sizeDp = 50))
            ps2BtnL1 = loadBtn(p, "ps2_btn_l1", ButtonLayout(x = 0.10f, y = 0.13f, sizeDp = 52))
            ps2BtnR1 = loadBtn(p, "ps2_btn_r1", ButtonLayout(x = 0.90f, y = 0.13f, sizeDp = 52))
            ps2BtnL2 = loadBtn(p, "ps2_btn_l2", ButtonLayout(x = 0.22f, y = 0.08f, sizeDp = 44))
            ps2BtnR2 = loadBtn(p, "ps2_btn_r2", ButtonLayout(x = 0.78f, y = 0.08f, sizeDp = 44))
            ps2BtnL1P = loadBtn(p, "ps2_p_btn_l1", ButtonLayout(x = 0.10f, y = 0.11f, sizeDp = 46))
            ps2BtnR1P = loadBtn(p, "ps2_p_btn_r1", ButtonLayout(x = 0.90f, y = 0.11f, sizeDp = 46))
            ps2BtnL2P = loadBtn(p, "ps2_p_btn_l2", ButtonLayout(x = 0.22f, y = 0.06f, sizeDp = 40))
            ps2BtnR2P = loadBtn(p, "ps2_p_btn_r2", ButtonLayout(x = 0.78f, y = 0.06f, sizeDp = 40))
            ps2BtnStart = loadBtn(p, "ps2_btn_start", ButtonLayout(x = 0.60f, y = 0.93f, sizeDp = 48))
            ps2BtnSelect = loadBtn(p, "ps2_btn_select", ButtonLayout(x = 0.40f, y = 0.93f, sizeDp = 48))
            ps2BtnL3 = loadBtn(p, "ps2_btn_l3", ButtonLayout(x = 0.26f, y = 0.94f, sizeDp = 36))
            ps2BtnR3 = loadBtn(p, "ps2_btn_r3", ButtonLayout(x = 0.74f, y = 0.94f, sizeDp = 36))
            ps2BtnStartP = loadBtn(p, "ps2_p_btn_start", ButtonLayout(x = 0.58f, y = 0.92f, sizeDp = 42))
            ps2BtnSelectP = loadBtn(p, "ps2_p_btn_select", ButtonLayout(x = 0.42f, y = 0.92f, sizeDp = 42))
            ps2BtnL3P = loadBtn(p, "ps2_p_btn_l3", ButtonLayout(x = 0.29f, y = 0.93f, sizeDp = 32))
            ps2BtnR3P = loadBtn(p, "ps2_p_btn_r3", ButtonLayout(x = 0.71f, y = 0.93f, sizeDp = 32))
            hiddenButtonsPs2 = p.getString("hidden_buttons_ps2", "") ?: ""
            // ★ DC + NGC/WII 显隐列表（此前 DC 漏掉导致显隐开关不生效）
            hiddenButtonsDc = p.getString("hidden_buttons_dc", "") ?: ""
            hiddenButtonsNgcwii = p.getString("hidden_buttons_ngcwii", "") ?: ""
            // NGC/WII 控制器切换 / 体感
            ngcwiiController = p.getString("ngcwii_controller", "gc") ?: "gc"
            ngcwiiMotion = p.getString("ngcwii_motion", "enabled") ?: "enabled"
            // 3DS 解密校验 / CIA 策略
            tg3dsDecryptCheck = p.getString("tg3ds_decrypt_check", "enabled") ?: "enabled"
            tg3dsCiaMode = p.getString("tg3ds_cia_mode", "launch") ?: "launch"
            // === Arcade extras ===
            btnL2 = loadBtn(p, "btn_l2", ButtonLayout(x = 0.08f, y = 0.32f, sizeDp = 48))
            btnR2 = loadBtn(p, "btn_r2", ButtonLayout(x = 0.92f, y = 0.32f, sizeDp = 48))
            btnL2P = loadBtn(p, "p_btn_l2", ButtonLayout(x = 0.10f, y = 0.28f, sizeDp = 46))
            btnR2P = loadBtn(p, "p_btn_r2", ButtonLayout(x = 0.90f, y = 0.28f, sizeDp = 46))
            arcadeShowL2R2 = p.getBoolean("arcade_show_l2r2", false)
            arcadeInputMode = p.getString("arcade_input_mode", "dpad") ?: "dpad"
            // === Combo buttons (per-platform JSON) ===
            comboButtons = p.getString("combo_buttons", "") ?: ""
            comboButtonsSfc = p.getString("combo_buttons_sfc", "") ?: ""
            comboButtonsGba = p.getString("combo_buttons_gba", "") ?: ""
            comboButtonsArcade = p.getString("combo_buttons_arcade", "") ?: ""
            comboButtonsMd = p.getString("combo_buttons_md", "") ?: ""
            comboButtonsPce = p.getString("combo_buttons_pce", "") ?: ""
            // PCE button visibility toggles
            pceShowDpad = p.getBoolean("pce_show_dpad", true)
            pceShowA = p.getBoolean("pce_show_a", true)
            pceShowB = p.getBoolean("pce_show_b", true)
            pceShowStart = p.getBoolean("pce_show_start", true)
            pceShowSelect = p.getBoolean("pce_show_select", true)
            pceShowL = p.getBoolean("pce_show_l", true)
            pceShowR = p.getBoolean("pce_show_r", true)
            pceShowX = p.getBoolean("pce_show_x", true)
            pceShowY = p.getBoolean("pce_show_y", true)
            pceShowL2 = p.getBoolean("pce_show_l2", true)
            pceShowR2 = p.getBoolean("pce_show_r2", true)
            // === Per-platform hidden button lists ===
            hiddenButtons = p.getString("hidden_buttons", "") ?: ""
            hiddenButtonsSfc = p.getString("hidden_buttons_sfc", "") ?: ""
            hiddenButtonsGba = p.getString("hidden_buttons_gba", "") ?: ""
            hiddenButtonsArcade = p.getString("hidden_buttons_arcade", "") ?: ""
            hiddenButtonsMd = p.getString("hidden_buttons_md", "") ?: ""
            hiddenButtonsPce = p.getString("hidden_buttons_pce", "") ?: ""
            hiddenButtonsNds = p.getString("hidden_buttons_nds", "") ?: ""
            hiddenButtonsPsx = p.getString("hidden_buttons_psx", "") ?: ""
            // === Input mode ===
            inputMode = p.getString("input_mode", "dpad") ?: "dpad"
    }
    }

    fun save(ctx: Context, layout: PadLayout, platform: GamePlatform? = null) {
        prefs(ctx).edit().apply {
            // === 横屏布局（按平台独立存储；platform==null 时写入全局默认） ===
            putFloat(pk(platform, KEY_DPAD_X), layout.dpad.x)
            putFloat(pk(platform, KEY_DPAD_Y), layout.dpad.y)
            putInt(pk(platform, KEY_DPAD_SIZE), layout.dpad.sizeDp)

            putFloat(pk(platform, KEY_A_X), layout.btnA.x)
            putFloat(pk(platform, KEY_A_Y), layout.btnA.y)
            putInt(pk(platform, KEY_A_SIZE), layout.btnA.sizeDp)

            putFloat(pk(platform, KEY_B_X), layout.btnB.x)
            putFloat(pk(platform, KEY_B_Y), layout.btnB.y)
            putInt(pk(platform, KEY_B_SIZE), layout.btnB.sizeDp)

            putFloat(pk(platform, KEY_TA_X), layout.btnTurboA.x)
            putFloat(pk(platform, KEY_TA_Y), layout.btnTurboA.y)
            putInt(pk(platform, KEY_TA_SIZE), layout.btnTurboA.sizeDp)

            putFloat(pk(platform, KEY_TB_X), layout.btnTurboB.x)
            putFloat(pk(platform, KEY_TB_Y), layout.btnTurboB.y)
            putInt(pk(platform, KEY_TB_SIZE), layout.btnTurboB.sizeDp)

            putFloat(pk(platform, KEY_START_X), layout.btnStart.x)
            putFloat(pk(platform, KEY_START_Y), layout.btnStart.y)
            putInt(pk(platform, KEY_START_SIZE), layout.btnStart.sizeDp)

            putFloat(pk(platform, KEY_SELECT_X), layout.btnSelect.x)
            putFloat(pk(platform, KEY_SELECT_Y), layout.btnSelect.y)
            putInt(pk(platform, KEY_SELECT_SIZE), layout.btnSelect.sizeDp)

            putFloat(pk(platform, KEY_L_X), layout.btnL.x)
            putFloat(pk(platform, KEY_L_Y), layout.btnL.y)
            putInt(pk(platform, KEY_L_SIZE), layout.btnL.sizeDp)

            putFloat(pk(platform, KEY_R_X), layout.btnR.x)
            putFloat(pk(platform, KEY_R_Y), layout.btnR.y)
            putInt(pk(platform, KEY_R_SIZE), layout.btnR.sizeDp)

            putFloat(pk(platform, KEY_X_X), layout.btnX.x)
            putFloat(pk(platform, KEY_X_Y), layout.btnX.y)
            putInt(pk(platform, KEY_X_SIZE), layout.btnX.sizeDp)

            putFloat(pk(platform, KEY_Y_X), layout.btnY.x)
            putFloat(pk(platform, KEY_Y_Y), layout.btnY.y)
            putInt(pk(platform, KEY_Y_SIZE), layout.btnY.sizeDp)

            putFloat(pk(platform, KEY_QS_X), layout.btnQuickSave.x)
            putFloat(pk(platform, KEY_QS_Y), layout.btnQuickSave.y)
            putInt(pk(platform, KEY_QS_SIZE), layout.btnQuickSave.sizeDp)
            putFloat(pk(platform, KEY_QL_X), layout.btnQuickLoad.x)
            putFloat(pk(platform, KEY_QL_Y), layout.btnQuickLoad.y)
            putInt(pk(platform, KEY_QL_SIZE), layout.btnQuickLoad.sizeDp)

            // === 竖屏布局（按平台独立存储，跟横屏互不干扰） ===
            putFloat(pk(platform, KEY_PDAD_X), layout.dpadP.x)
            putFloat(pk(platform, KEY_PDAD_Y), layout.dpadP.y)
            putInt(pk(platform, KEY_PDAD_SIZE), layout.dpadP.sizeDp)

            putFloat(pk(platform, KEY_PA_X), layout.btnAP.x)
            putFloat(pk(platform, KEY_PA_Y), layout.btnAP.y)
            putInt(pk(platform, KEY_PA_SIZE), layout.btnAP.sizeDp)

            putFloat(pk(platform, KEY_PB_X), layout.btnBP.x)
            putFloat(pk(platform, KEY_PB_Y), layout.btnBP.y)
            putInt(pk(platform, KEY_PB_SIZE), layout.btnBP.sizeDp)

            putFloat(pk(platform, KEY_PTA_X), layout.btnTurboAP.x)
            putFloat(pk(platform, KEY_PTA_Y), layout.btnTurboAP.y)
            putInt(pk(platform, KEY_PTA_SIZE), layout.btnTurboAP.sizeDp)

            putFloat(pk(platform, KEY_PTB_X), layout.btnTurboBP.x)
            putFloat(pk(platform, KEY_PTB_Y), layout.btnTurboBP.y)
            putInt(pk(platform, KEY_PTB_SIZE), layout.btnTurboBP.sizeDp)

            putFloat(pk(platform, KEY_PSTART_X), layout.btnStartP.x)
            putFloat(pk(platform, KEY_PSTART_Y), layout.btnStartP.y)
            putInt(pk(platform, KEY_PSTART_SIZE), layout.btnStartP.sizeDp)

            putFloat(pk(platform, KEY_PSELECT_X), layout.btnSelectP.x)
            putFloat(pk(platform, KEY_PSELECT_Y), layout.btnSelectP.y)
            putInt(pk(platform, KEY_PSELECT_SIZE), layout.btnSelectP.sizeDp)

            putFloat(pk(platform, KEY_PL_X), layout.btnLP.x)
            putFloat(pk(platform, KEY_PL_Y), layout.btnLP.y)
            putInt(pk(platform, KEY_PL_SIZE), layout.btnLP.sizeDp)

            putFloat(pk(platform, KEY_PR_X), layout.btnRP.x)
            putFloat(pk(platform, KEY_PR_Y), layout.btnRP.y)
            putInt(pk(platform, KEY_PR_SIZE), layout.btnRP.sizeDp)

            putFloat(pk(platform, KEY_PX_X), layout.btnXP.x)
            putFloat(pk(platform, KEY_PX_Y), layout.btnXP.y)
            putInt(pk(platform, KEY_PX_SIZE), layout.btnXP.sizeDp)

            putFloat(pk(platform, KEY_PY_X), layout.btnYP.x)
            putFloat(pk(platform, KEY_PY_Y), layout.btnYP.y)
            putInt(pk(platform, KEY_PY_SIZE), layout.btnYP.sizeDp)

            putFloat(pk(platform, KEY_PQS_X), layout.btnQuickSaveP.x)
            putFloat(pk(platform, KEY_PQS_Y), layout.btnQuickSaveP.y)
            putInt(pk(platform, KEY_PQS_SIZE), layout.btnQuickSaveP.sizeDp)
            putFloat(pk(platform, KEY_PQL_X), layout.btnQuickLoadP.x)
            putFloat(pk(platform, KEY_PQL_Y), layout.btnQuickLoadP.y)
            putInt(pk(platform, KEY_PQL_SIZE), layout.btnQuickLoadP.sizeDp)

            // === 全局设置 ===
            putFloat(KEY_OPACITY, layout.opacity)
            putBoolean(KEY_SHOW_PAD, layout.showPad)
            putBoolean(KEY_SHOW_FPS, layout.showFps)
            putBoolean(KEY_SHOW_PLAYER_SWITCH, layout.showPlayerSwitch)
            putFloat(KEY_PLAYER_SWITCH_X, layout.playerSwitchX)
            putFloat(KEY_PLAYER_SWITCH_Y, layout.playerSwitchY)
            // 迁移标记（与 load() 里的迁移逻辑配合：标记写入后，load() 不再把
            // 用户数据中的旧默认值 disabled 迁移为 enabled；用户手动关闭会被尊重）
            putBoolean(KEY_NDS_GL_MIGRATION_V2, true)
            putBoolean(KEY_HIGH_QUALITY_SCALING, layout.highQualityScaling)

            putString(KEY_NTSC_FILTER, layout.ntscFilter)
            putString(pk(platform, KEY_ASPECT_RATIO), layout.aspectRatio)
            putString(KEY_PALETTE, layout.palette)
            putString(KEY_REGION, layout.region)
            putString(KEY_SOUND_QUALITY, layout.soundQuality)
            putString(KEY_CROP_OVERSCAN, layout.cropOverscan)
            putString(pk(platform, KEY_VIDEO_SCALE), layout.videoScale)
            putFloat(pk(platform, KEY_CUSTOM_LAYOUT_LEFT), layout.customLayoutLeft)
            putFloat(pk(platform, KEY_CUSTOM_LAYOUT_TOP), layout.customLayoutTop)
            putFloat(pk(platform, KEY_CUSTOM_LAYOUT_RIGHT), layout.customLayoutRight)
            putFloat(pk(platform, KEY_CUSTOM_LAYOUT_BOTTOM), layout.customLayoutBottom)
            putFloat(pk(platform, KEY_CUSTOM_LAYOUT_LEFT_P), layout.customLayoutLeftP)
            putFloat(pk(platform, KEY_CUSTOM_LAYOUT_TOP_P), layout.customLayoutTopP)
            putFloat(pk(platform, KEY_CUSTOM_LAYOUT_RIGHT_P), layout.customLayoutRightP)
            putFloat(pk(platform, KEY_CUSTOM_LAYOUT_BOTTOM_P), layout.customLayoutBottomP)
            putString(KEY_VIDEO_FILTER, layout.videoFilter)
            putString(KEY_OVERCLOCKING, layout.overclocking)

            // SFC specific options
            putString("sfc_reduce_sprite_flicker", layout.sfcReduceSpriteFlicker)
            putString("sfc_reduce_slowdown", layout.sfcReduceSlowdown)
            putString("sfc_audio_interpolation", layout.sfcAudioInterpolation)
            putString("sfc_gfx_transparency", layout.sfcGfxTransparency)
            putString("sfc_gfx_hires", layout.sfcGfxHires)
            putString("sfc_gfx_clip", layout.sfcGfxClip)
            putString("sfc_block_invalid_vram", layout.sfcBlockInvalidVram)
            putString("sfc_sound_output", layout.sfcSoundOutput)
            putString("sfc_overscan", layout.sfcOverscan)
            putString("sfc_side_by_side", layout.sfcSideBySide)
            putString("sfc_up_down_allowed", layout.sfcUpDownAllowed)
            putString("sfc_superscope", layout.sfcSuperScope)
            putString("sfc_layer_1", layout.sfcLayer1)
            putString("sfc_layer_2", layout.sfcLayer2)
            putString("sfc_layer_3", layout.sfcLayer3)
            putString("sfc_layer_4", layout.sfcLayer4)
            putString("sfc_layer_5", layout.sfcLayer5)
            putString("sfc_overclock", layout.sfcOverclock)
            // GB/GBA specific options
            putString("gb_color_correction", layout.gbColorCorrection)
            putString("gbc_color_preset", layout.gbcColorPreset)
            putString("gba_color_correction", layout.gbaColorCorrection)
            putString("gba_color_preset", layout.gbaColorPreset)
            putString("gba_frame_blending", layout.gbaFrameBlending)
            putString("gba_audio_resampler", layout.gbaAudioResampler)
            putString("gba_audio_low_pass", layout.gbaAudioLowPass)
            putString("gba_audio_low_pass_range", layout.gbaAudioLowPassRange)
            putString("gba_frameskip_type", layout.gbaFrameskipType)
            putString("gba_frameskip_count", layout.gbaFrameskipCount)
            putString("gba_solar_sensor", layout.gbaSolarSensor)
            putString("gba_idle_optimization", layout.gbaIdleOptimization)
            putString("gba_force_rtc", layout.gbaForceRTC)
            putString("gba_allow_opposite", layout.gbaAllowOpposite)
            putString("gb_model", layout.gbModel)
            putString("gb_sgb_borders", layout.gbSgbBorders)
            putString("gba_frameskip_threshold", layout.gbaFrameskipThreshold)
            // DOSBox-Pure options
            putString("dos_machine", layout.dosMachine)
            putString("dos_cycles", layout.dosCycles)
            putString("dos_cycles_max", layout.dosCyclesMax)
            putString("dos_sb_type", layout.dosSbType)
            putString("dos_sb_adlib_mode", layout.dosSbAdlibMode)
            putString("dos_sb_adlib_emu", layout.dosSbAdlibEmu)
            putString("dos_gus", layout.dosGus)
            putString("dos_mouse_input", layout.dosMouseInput)
            // 音频（核心自带混音器）+ CPU/内存/视频补充
            putString("dos_audiorate", layout.dosAudiorate)
            putString("dos_swap_stereo", layout.dosSwapStereo)
            putString("dos_tandysound", layout.dosTandySound)
            putString("dos_cpu_core", layout.dosCpuCore)
            putString("dos_cpu_type", layout.dosCpuType)
            putString("dos_memory_size", layout.dosMemorySize)
            putString("dos_aspect_correction", layout.dosAspectCorrection)
            putString("dos_cga_mode", layout.dosCgaMode)
            putString("dos_keyboard_layout", layout.dosKeyboardLayout)
            putString("dos_auto_mapping", layout.dosAutoMapping)
            putString("dos_savestate", layout.dosSavestate)
            putString("dos_voodoo", layout.dosVoodoo)
            putString("dos_force60fps", layout.dosForce60fps)
            putString("dos_input_mode", layout.dosInputMode)
            putString("java_input_mode", layout.javaInputMode)
            putFloat("java_opacity", layout.javaOpacity)
            putString("java_scale_type", layout.javaScaleType)
            putBoolean("java_show_fps", layout.javaShowFps)
            putBoolean("java_immediate_mode", layout.javaImmediateMode)
            putString("java_resolution", layout.javaResolution)
            putString("java_scale_ratio", layout.javaScaleRatio)
            putString("java_fps_limit", layout.javaFpsLimit)
            putBoolean("java_touch_input", layout.javaTouchInput)
            putBoolean("java_num_dual_dispatch", layout.javaNumDualDispatch)
            putString("java_button_key_map", layout.javaButtonKeyMap)
            putString("overlay_theme_json", layout.overlayThemeJson)
            saveBtn("java_phone_grid", layout.javaPhoneGrid)
            saveBtn("java_phone_top", layout.javaPhoneTop)
            // DOS gamepad overlay button positions (landscape)
            saveBtn("dos_dpad", layout.dosDpad)
            saveBtn("dos_btn_esc", layout.dosBtnEsc)
            saveBtn("dos_btn_enter", layout.dosBtnEnter)
            saveBtn("dos_btn_space", layout.dosBtnSpace)
            saveBtn("dos_btn_tab", layout.dosBtnTab)
            saveBtn("dos_btn_ctrl", layout.dosBtnCtrl)
            saveBtn("dos_btn_alt", layout.dosBtnAlt)
            saveBtn("dos_btn_shift", layout.dosBtnShift)
            saveBtn("dos_btn_back", layout.dosBtnBack)
            saveBtn("dos_btn_mouse_l", layout.dosBtnMouseL)
            saveBtn("dos_btn_mouse_r", layout.dosBtnMouseR)
            // DOS gamepad overlay button positions (portrait)
            saveBtn("dos_dpad_p", layout.dosDpadP)
            saveBtn("dos_btn_esc_p", layout.dosBtnEscP)
            saveBtn("dos_btn_enter_p", layout.dosBtnEnterP)
            saveBtn("dos_btn_space_p", layout.dosBtnSpaceP)
            saveBtn("dos_btn_tab_p", layout.dosBtnTabP)
            saveBtn("dos_btn_ctrl_p", layout.dosBtnCtrlP)
            saveBtn("dos_btn_alt_p", layout.dosBtnAltP)
            saveBtn("dos_btn_shift_p", layout.dosBtnShiftP)
            saveBtn("dos_btn_back_p", layout.dosBtnBackP)
            saveBtn("dos_btn_mouse_l_p", layout.dosBtnMouseLP)
            saveBtn("dos_btn_mouse_r_p", layout.dosBtnMouseRP)
            // DOS extra buttons (landscape)
            saveBtn("dos_btn_insert", layout.dosBtnInsert)
            saveBtn("dos_btn_delete", layout.dosBtnDelete)
            saveBtn("dos_btn_home", layout.dosBtnHome)
            saveBtn("dos_btn_end", layout.dosBtnEnd)
            saveBtn("dos_btn_pageup", layout.dosBtnPageUp)
            saveBtn("dos_btn_pagedown", layout.dosBtnPageDown)
            // DOS extra buttons (portrait)
            saveBtn("dos_btn_insert_p", layout.dosBtnInsertP)
            saveBtn("dos_btn_delete_p", layout.dosBtnDeleteP)
            saveBtn("dos_btn_home_p", layout.dosBtnHomeP)
            saveBtn("dos_btn_end_p", layout.dosBtnEndP)
            saveBtn("dos_btn_pageup_p", layout.dosBtnPageUpP)
            saveBtn("dos_btn_pagedown_p", layout.dosBtnPageDownP)
            // DOS button visibility toggles
            putBoolean("dos_show_dpad", layout.dosShowDpad)
            putBoolean("dos_show_esc", layout.dosShowEsc)
            putBoolean("dos_show_enter", layout.dosShowEnter)
            putBoolean("dos_show_space", layout.dosShowSpace)
            putBoolean("dos_show_tab", layout.dosShowTab)
            putBoolean("dos_show_ctrl", layout.dosShowCtrl)
            putBoolean("dos_show_alt", layout.dosShowAlt)
            putBoolean("dos_show_shift", layout.dosShowShift)
            putBoolean("dos_show_back", layout.dosShowBack)
            putBoolean("dos_show_mouse_l", layout.dosShowMouseL)
            putBoolean("dos_show_mouse_r", layout.dosShowMouseR)
            putBoolean("dos_show_insert", layout.dosShowInsert)
            putBoolean("dos_show_delete", layout.dosShowDelete)
            putBoolean("dos_show_home", layout.dosShowHome)
            putBoolean("dos_show_end", layout.dosShowEnd)
            putBoolean("dos_show_pageup", layout.dosShowPageUp)
            putBoolean("dos_show_pagedown", layout.dosShowPageDown)
            // Dynamic extra keys
            putString("dos_extra_keys", layout.dosExtraKeys)
            putString("dos_extra_keys_p", layout.dosExtraKeysP)
            // Display orientation
            putString("screen_orientation", layout.screenOrientation)
            // FBNeo (Arcade) options
            putString("arcade_aspect", layout.arcadeAspect)
            putString("arcade_rotate", layout.arcadeRotate)
            putString("arcade_vertical_mode", layout.arcadeVerticalMode)
            putString("arcade_crop_overscan", layout.arcadeCropOverscan)
            putString("arcade_cpu_speed", layout.arcadeCpuSpeed)
            putString("arcade_frameskip", layout.arcadeFrameskip)
            putString("arcade_force_60hz", layout.arcadeForce60hz)
            putString("arcade_sample_rate", layout.arcadeSampleRate)
            putString("arcade_audio_interp", layout.arcadeAudioInterp)
            putString("arcade_lowpass", layout.arcadeLowpass)
            putString("arcade_neogeo_mode", layout.arcadeNeogeomode)
            putString("arcade_memcard", layout.arcadeMemcard)
            // Genesis-Plus-GX (MD/SEGA) options
            putString("md_region", layout.mdRegion)
            putString("md_system", layout.mdSystem)
            putString("md_aspect", layout.mdAspect)
            putString("md_render", layout.mdRender)
            putString("md_ntsc_filter", layout.mdNtscFilter)
            putString("md_lcd_filter", layout.mdLcdFilter)
            putString("md_overscan", layout.mdOverscan)
            putString("md_gg_extra", layout.mdGgExtra)
            putString("md_left_border", layout.mdLeftBorder)
            putString("md_input", layout.mdInput)
            putString("md_allow_up_down", layout.mdAllowUpDown)
            putString("md_overclock", layout.mdOverclock)
            putString("md_frameskip", layout.mdFrameskip)
            putString("md_cd_fastboot", layout.mdCdFastboot)
            putString("md_sms_fm", layout.mdSmsFm)
            putString("md_gg_stretch", layout.mdGgStretch)
            // NDS (melonDS) options
            putString("nds_use_fw_bios", layout.ndsUseFwBios)
            putString("nds_console_mode", layout.ndsConsoleMode)
            putString("nds_screen_layout", layout.ndsScreenLayout)
            putString("nds_resolution", layout.ndsResolution)
            putString("nds_opengl_renderer", layout.ndsOpenGlRenderer)
            putString("nds_opengl_better_polygons", layout.ndsOpenGlBetterPolygons)
            putString("nds_opengl_filtering", layout.ndsOpenGlFiltering)
            putString("nds_filtering", layout.ndsFiltering)
            putString("nds_screensaver", layout.ndsScreensaver)
            putString("nds_touch_mode", layout.ndsTouchMode)
            putString("nds_mouse_speed", layout.ndsMouseSpeed)
            putString("nds_dsi_sdcard", layout.ndsDsiSdcard)
            putString("nds_randomize_mac", layout.ndsRandomizeMac)
            putString("nds_jit_enable", layout.ndsJitEnable)
            putString("nds_audio_interpolation", layout.ndsAudioInterpolation)
            putString("nds_use_fw_settings", layout.ndsUseFwSettings)
            putString("nds_save_mode", layout.ndsSaveMode)   // legacy (NDS-only), 迁移到 global_save_mode
            putString("nds_drastic_volume", layout.ndsDrasticVolume)   // DraStic（激烈）核心
            putString("nds_drastic_sound", layout.ndsDrasticSound)
            putString("nds_drastic_hd_render", layout.ndsDrasticHdRender)
            putString("nds_drastic_frameskip_type", layout.ndsDrasticFrameskipType)
            putString("nds_drastic_frameskip_value", layout.ndsDrasticFrameskipValue)
            putString("nds_drastic_frameskip_safe", layout.ndsDrasticFrameskipSafe)
            putString("nds_drastic_threaded_3d", layout.ndsDrasticThreaded3D)
            putString("nds_drastic_16bit", layout.ndsDrastic16Bit)
            putString("nds_drastic_edge_marking", layout.ndsDrasticEdgeMarking)
            putString("nds_drastic_fix_main_screen", layout.ndsDrasticFixMainScreen)
            putString("nds_drastic_audio_latency", layout.ndsDrasticAudioLatency)
            putString("nds_drastic_mic_enabled", layout.ndsDrasticMicEnabled)
            putString("nds_drastic_mic_level", layout.ndsDrasticMicLevel)
            putString("nds_drastic_autofire_speed", layout.ndsDrasticAutofireSpeed)
            putString("nds_drastic_ffwd_rate", layout.ndsDrasticFfwdRate)
            putString("nds_drastic_slot2_type", layout.ndsDrasticSlot2Type)
            putString("nds_drastic_rtc_system_time", layout.ndsDrasticRtcSystemTime)
            putString("nds_drastic_cheats_enabled", layout.ndsDrasticCheatsEnabled)
            putString("nds_drastic_lua_enabled", layout.ndsDrasticLuaEnabled)
            putString("nds_drastic_backup_in_savestates", layout.ndsDrasticBackupInSavestates)
            putString("nds_drastic_ignore_card_limit", layout.ndsDrasticIgnoreCardLimit)
            putString("nds_drastic_auto_trim", layout.ndsDrasticAutoTrim)
            putString("nds_drastic_preload_roms", layout.ndsDrasticPreloadRoms)
            putString("nds_drastic_show_fps", layout.ndsDrasticShowFps)
            putString("nds_drastic_threads", layout.ndsDrasticThreads)
            putString("nds_drastic_autosave", layout.ndsDrasticAutosave)
            putString("nds_drastic_save_format", layout.ndsDrasticSaveFormat)
            putString("nds_drastic_smooth_filter", layout.ndsDrasticSmoothFilter)
            putString("nds_drastic_display_mode", layout.ndsDrasticDisplayMode)
            putString("global_save_mode", layout.globalSaveMode)
            // === 主页个性化 ===
            putString("home_bg_uri", layout.homeBackgroundUri)
            putBoolean("home_bg_is_video", layout.homeBackgroundIsVideo)
            putString("home_tile_icons", layout.homeTileIcons)
            putString("home_tile_icon_alphas", layout.homeTileIconAlphas)
            putFloat(KEY_NDS_TOP_LEFT, layout.ndsTopLayoutLeft)
            putFloat(KEY_NDS_TOP_TOP, layout.ndsTopLayoutTop)
            putFloat(KEY_NDS_TOP_RIGHT, layout.ndsTopLayoutRight)
            putFloat(KEY_NDS_TOP_BOTTOM, layout.ndsTopLayoutBottom)
            putFloat(KEY_NDS_BOTTOM_LEFT, layout.ndsBottomLayoutLeft)
            putFloat(KEY_NDS_BOTTOM_TOP, layout.ndsBottomLayoutTop)
            putFloat(KEY_NDS_BOTTOM_RIGHT, layout.ndsBottomLayoutRight)
            putFloat(KEY_NDS_BOTTOM_BOTTOM, layout.ndsBottomLayoutBottom)
            putFloat(KEY_NDS_TOP_LEFT_P, layout.ndsTopLayoutLeftP)
            putFloat(KEY_NDS_TOP_TOP_P, layout.ndsTopLayoutTopP)
            putFloat(KEY_NDS_TOP_RIGHT_P, layout.ndsTopLayoutRightP)
            putFloat(KEY_NDS_TOP_BOTTOM_P, layout.ndsTopLayoutBottomP)
            putFloat(KEY_NDS_BOTTOM_LEFT_P, layout.ndsBottomLayoutLeftP)
            putFloat(KEY_NDS_BOTTOM_TOP_P, layout.ndsBottomLayoutTopP)
            putFloat(KEY_NDS_BOTTOM_RIGHT_P, layout.ndsBottomLayoutRightP)
            putFloat(KEY_NDS_BOTTOM_BOTTOM_P, layout.ndsBottomLayoutBottomP)
            // === PSX (PCSX-ReARMed) ===
            putString("psx_bios", layout.pscxBios)
            putString("psx_region", layout.pscxRegion)
            putString("psx_frameskip_type", layout.pscxFrameskipType)
            putString("psx_frameskip_threshold", layout.pscxFrameskipThreshold)
            putString("psx_frameskip", layout.pscxFrameskip)
            putString("psx_drc_thread", layout.pscxDrcThread)
            putString("psx_gpu_thread_rendering", layout.pscxGpuThreadRendering)
            putString("psx_icache", layout.pscxIcache)
            putString("psx_cd_turbo", layout.pscxCdTurbo)
            putString("psx_fractional_fps", layout.pscxFractionalFps)
            putString("psx_alt_flip", layout.pscxAltFlip)
            putString("psx_neon_interlace", layout.pscxNeonInterlace)
            putString("psx_neon_enhance", layout.pscxNeonEnhance)
            putString("psx_centering", layout.pscxCentering)
            putString("psx_cd_audio", layout.pscxCdAudio)
            putString("psx_xa_audio", layout.pscxXaAudio)
            putString("psx_spu_thread", layout.pscxSpuThread)
            putString("psx_negcon_response", layout.pscxNegconResponse)
            putString("psx_negcon_deadzone", layout.pscxNegconDeadzone)
            putString("psx_pad1_type", layout.pscxPad1Type)
            putString("psx_pad2_type", layout.pscxPad2Type)
            putString("psx_vibration", layout.pscxVibration)
            putString("psx_dithering", layout.pscxDithering)
            putString("psx_spu_interp", layout.pscxSpuInterp)
            putString("psx_spu_reverb", layout.pscxSpuReverb)
            putString("psx_show_bootlogo", layout.pscxShowBootlogo)
            putString("psx_cd_readahead", layout.pscxCdReadahead)
            putString("psx_memcard1", layout.pscxMemcard1)
            putString("psx_memcard2", layout.pscxMemcard2)
            putString("psx_drc", layout.pscxDrc)
            putString("psx_clock", layout.pscxClock)
            putString("psx_rgb32", layout.pscxRgb32)
            putString("psx_scale_hires", layout.pscxScaleHires)
            putString("psx_show_overscan", layout.pscxShowOverscan)
            putString("psx_multitap", layout.pscxMultitap)
            putString("psx_gpu_odd_even", layout.pscxGpuOddEven)
            putString("psx_analog_axis", layout.pscxAnalogAxis)
            // === DC (libretro Flycast) ===
            putString("dc_resolution", layout.dcResolution)
            putString("dc_alpha_sorting", layout.dcAlphaSorting)
            putString("dc_threaded_rendering", layout.dcThreadedRendering)
            putString("dc_delay_frame_swapping", layout.dcDelayFrameSwapping)
            putString("dc_frame_skipping", layout.dcFrameSkipping)
            putString("dc_widescreen_hack", layout.dcWidescreenHack)
            putString("dc_widescreen_cheats", layout.dcWidescreenCheats)
            putString("dc_gdrom_fast_loading", layout.dcGdromFastLoading)
            putString("dc_hle_bios", layout.dcHleBios)
            putString("dc_ram_32mb", layout.dcRam32mb)
            putString("dc_force_wince", layout.dcForceWince)
            putString("dc_enable_dsp", layout.dcEnableDsp)
            putString("dc_region", layout.dcRegion)
            putString("dc_language", layout.dcLanguage)
            putString("dc_broadcast", layout.dcBroadcast)
            putString("dc_cable_type", layout.dcCableType)
            // === PS2 (PCEE2 — PCSX2 core) core options + 专属按键布局 ===
            putString("ps2_res_multi", layout.ps2ResMulti)
            putString("ps2_renderer", layout.ps2Renderer)
            putString("ps2_bilinear", layout.ps2Bilinear)
            putString("ps2_fast_boot", layout.ps2FastBoot)
            putString("ps2_hw_download_mode", layout.ps2HwDownloadMode)
            putString("ps2_blending_accuracy", layout.ps2BlendingAccuracy)
            putString("ps2_trilinear", layout.ps2Trilinear)
            putString("ps2_anisotropic", layout.ps2Anisotropic)
            putString("ps2_dithering", layout.ps2Dithering)
            putString("ps2_mipmapping", layout.ps2Mipmapping)
            putString("ps2_deinterlace", layout.ps2Deinterlace)
            putString("ps2_aspect_ratio", layout.ps2AspectRatio)
            putString("ps2_widescreen", layout.ps2Widescreen)
            putString("ps2_no_interlace", layout.ps2NoInterlace)
            putString("ps2_mtvu", layout.ps2Mtvu)
            putString("ps2_instant_vu1", layout.ps2InstantVu1)
            putString("ps2_ee_cycle_rate", layout.ps2EeCycleRate)
            putString("ps2_ee_cycle_skip", layout.ps2EeCycleSkip)
            putString("ps2_tv_shader", layout.ps2TvShader)
            putString("ps2_shade_boost", layout.ps2ShadeBoost)
            putString("ps2_half_pixel_offset", layout.ps2HalfPixelOffset)
            putString("ps2_texture_preloading", layout.ps2TexturePreloading)
            putString("ps2_rumble", layout.ps2Rumble)
            saveBtn("ps2_dpad", layout.ps2Dpad)
            saveBtn("ps2_p_dpad", layout.ps2DpadP)
            saveBtn("ps2_lstick", layout.ps2LStick)
            saveBtn("ps2_rstick", layout.ps2RStick)
            saveBtn("ps2_p_lstick", layout.ps2LStickP)
            saveBtn("ps2_p_rstick", layout.ps2RStickP)
            saveBtn("ps2_btn_a", layout.ps2BtnA)
            saveBtn("ps2_btn_b", layout.ps2BtnB)
            saveBtn("ps2_btn_x", layout.ps2BtnX)
            saveBtn("ps2_btn_y", layout.ps2BtnY)
            saveBtn("ps2_p_btn_a", layout.ps2BtnAP)
            saveBtn("ps2_p_btn_b", layout.ps2BtnBP)
            saveBtn("ps2_p_btn_x", layout.ps2BtnXP)
            saveBtn("ps2_p_btn_y", layout.ps2BtnYP)
            saveBtn("ps2_btn_l1", layout.ps2BtnL1)
            saveBtn("ps2_btn_r1", layout.ps2BtnR1)
            saveBtn("ps2_btn_l2", layout.ps2BtnL2)
            saveBtn("ps2_btn_r2", layout.ps2BtnR2)
            saveBtn("ps2_p_btn_l1", layout.ps2BtnL1P)
            saveBtn("ps2_p_btn_r1", layout.ps2BtnR1P)
            saveBtn("ps2_p_btn_l2", layout.ps2BtnL2P)
            saveBtn("ps2_p_btn_r2", layout.ps2BtnR2P)
            saveBtn("ps2_btn_start", layout.ps2BtnStart)
            saveBtn("ps2_btn_select", layout.ps2BtnSelect)
            saveBtn("ps2_btn_l3", layout.ps2BtnL3)
            saveBtn("ps2_btn_r3", layout.ps2BtnR3)
            saveBtn("ps2_p_btn_start", layout.ps2BtnStartP)
            saveBtn("ps2_p_btn_select", layout.ps2BtnSelectP)
            saveBtn("ps2_p_btn_l3", layout.ps2BtnL3P)
            saveBtn("ps2_p_btn_r3", layout.ps2BtnR3P)
            putString("hidden_buttons_ps2", layout.hiddenButtonsPs2)
            // ★ DC + NGC/WII 显隐 / 控制器切换 / 体感 / 3DS 解密·CIA
            putString("hidden_buttons_dc", layout.hiddenButtonsDc)
            putString("hidden_buttons_ngcwii", layout.hiddenButtonsNgcwii)
            putString("ngcwii_controller", layout.ngcwiiController)
            putString("ngcwii_motion", layout.ngcwiiMotion)
            putString("tg3ds_decrypt_check", layout.tg3dsDecryptCheck)
            putString("tg3ds_cia_mode", layout.tg3dsCiaMode)
            // === Arcade extras ===
            saveBtn("btn_l2", layout.btnL2)
            saveBtn("btn_r2", layout.btnR2)
            saveBtn("p_btn_l2", layout.btnL2P)
            saveBtn("p_btn_r2", layout.btnR2P)
            putBoolean("arcade_show_l2r2", layout.arcadeShowL2R2)
            putString("arcade_input_mode", layout.arcadeInputMode)
            // === Combo buttons (per-platform JSON) ===
            putString("combo_buttons", layout.comboButtons)
            putString("combo_buttons_sfc", layout.comboButtonsSfc)
            putString("combo_buttons_gba", layout.comboButtonsGba)
            putString("combo_buttons_arcade", layout.comboButtonsArcade)
            putString("combo_buttons_md", layout.comboButtonsMd)
            putString("combo_buttons_pce", layout.comboButtonsPce)
            // PCE button visibility toggles
            putBoolean("pce_show_dpad", layout.pceShowDpad)
            putBoolean("pce_show_a", layout.pceShowA)
            putBoolean("pce_show_b", layout.pceShowB)
            putBoolean("pce_show_start", layout.pceShowStart)
            putBoolean("pce_show_select", layout.pceShowSelect)
            putBoolean("pce_show_l", layout.pceShowL)
            putBoolean("pce_show_r", layout.pceShowR)
            putBoolean("pce_show_x", layout.pceShowX)
            putBoolean("pce_show_y", layout.pceShowY)
            putBoolean("pce_show_l2", layout.pceShowL2)
            putBoolean("pce_show_r2", layout.pceShowR2)
            // === Per-platform hidden button lists ===
            putString("hidden_buttons", layout.hiddenButtons)
            putString("hidden_buttons_sfc", layout.hiddenButtonsSfc)
            putString("hidden_buttons_gba", layout.hiddenButtonsGba)
            putString("hidden_buttons_arcade", layout.hiddenButtonsArcade)
            putString("hidden_buttons_md", layout.hiddenButtonsMd)
            putString("hidden_buttons_pce", layout.hiddenButtonsPce)
            putString("hidden_buttons_nds", layout.hiddenButtonsNds)
            putString("hidden_buttons_psx", layout.hiddenButtonsPsx)
            // === Input mode ===
            putString("input_mode", layout.inputMode)
        }.apply()
    }

    /**
     * Check if a specific button is hidden for a given platform.
     * For PCE, uses the legacy pceShow* booleans (backward compatible).
     * For all other platforms, checks the hiddenButtons* comma-separated string.
     *
     * @param platform The game platform
     * @param key Button key: "dpad", "a", "b", "ta", "tb", "start", "select", "l", "r", "x", "y", "l2", "r2"
     * @return true if the button should be hidden
     */
    fun isButtonHidden(layout: PadLayout, platform: GamePlatform, key: String): Boolean {
        return when (platform) {
            GamePlatform.PCE -> {
                // PCE uses the legacy per-button boolean toggles for the
                // standard buttons; qs/ql live in the generic list.
                when (key) {
                    "dpad" -> !layout.pceShowDpad
                    "a" -> !layout.pceShowA
                    "b" -> !layout.pceShowB
                    "start" -> !layout.pceShowStart
                    "select" -> !layout.pceShowSelect
                    "l" -> !layout.pceShowL
                    "r" -> !layout.pceShowR
                    "x" -> !layout.pceShowX
                    "y" -> !layout.pceShowY
                    "l2" -> !layout.pceShowL2
                    "r2" -> !layout.pceShowR2
                    "qs", "ql" -> isHiddenInList(layout.hiddenButtonsPce, key)
                    else -> false
                }
            }
            GamePlatform.NES, GamePlatform.GB -> isHiddenInList(layout.hiddenButtons, key)
            GamePlatform.SFC -> isHiddenInList(layout.hiddenButtonsSfc, key)
            GamePlatform.GBA -> isHiddenInList(layout.hiddenButtonsGba, key)
            GamePlatform.ARCADE -> isHiddenInList(layout.hiddenButtonsArcade, key)
            GamePlatform.MD -> isHiddenInList(layout.hiddenButtonsMd, key)
            GamePlatform.NDS -> isHiddenInList(layout.hiddenButtonsNds, key)
            GamePlatform.PSX -> isHiddenInList(layout.hiddenButtonsPsx, key)
            GamePlatform.PS2 -> isHiddenInList(layout.hiddenButtonsPs2, key)
            // ★ DC 显隐修复：旧版落到 else 恒 false，显隐对话框对 DC 完全无效。
            GamePlatform.DC -> isHiddenInList(layout.hiddenButtonsDc, key)
            GamePlatform.NGCWII -> isHiddenInList(layout.hiddenButtonsNgcwii, key)
            else -> false
        }
    }

    /**
     * Set a button's hidden state for a given platform.
     * For PCE, updates the legacy pceShow* booleans.
     * For all other platforms, updates the hiddenButtons* comma-separated string.
     */
    fun setButtonHidden(layout: PadLayout, platform: GamePlatform, key: String, hidden: Boolean): PadLayout {
        return when (platform) {
            GamePlatform.PCE -> {
                when (key) {
                    "dpad" -> layout.copy {pceShowDpad = !hidden}
                    "a" -> layout.copy {pceShowA = !hidden}
                    "b" -> layout.copy {pceShowB = !hidden}
                    "start" -> layout.copy {pceShowStart = !hidden}
                    "select" -> layout.copy {pceShowSelect = !hidden}
                    "l" -> layout.copy {pceShowL = !hidden}
                    "r" -> layout.copy {pceShowR = !hidden}
                    "x" -> layout.copy {pceShowX = !hidden}
                    "y" -> layout.copy {pceShowY = !hidden}
                    "l2" -> layout.copy {pceShowL2 = !hidden}
                    "r2" -> layout.copy {pceShowR2 = !hidden}
                    "qs", "ql" -> layout.copy {hiddenButtonsPce = updateHiddenList(layout.hiddenButtonsPce, key, hidden)}
                    else -> layout
                }
            }
            GamePlatform.NES, GamePlatform.GB -> layout.copy {hiddenButtons = updateHiddenList(layout.hiddenButtons, key, hidden)}
            GamePlatform.SFC -> layout.copy {hiddenButtonsSfc = updateHiddenList(layout.hiddenButtonsSfc, key, hidden)}
            GamePlatform.GBA -> layout.copy {hiddenButtonsGba = updateHiddenList(layout.hiddenButtonsGba, key, hidden)}
            GamePlatform.ARCADE -> layout.copy {hiddenButtonsArcade = updateHiddenList(layout.hiddenButtonsArcade, key, hidden)}
            GamePlatform.MD -> layout.copy {hiddenButtonsMd = updateHiddenList(layout.hiddenButtonsMd, key, hidden)}
            GamePlatform.NDS -> layout.copy {hiddenButtonsNds = updateHiddenList(layout.hiddenButtonsNds, key, hidden)}
            GamePlatform.PSX -> layout.copy {hiddenButtonsPsx = updateHiddenList(layout.hiddenButtonsPsx, key, hidden)}
            GamePlatform.PS2 -> layout.copy {hiddenButtonsPs2 = updateHiddenList(layout.hiddenButtonsPs2, key, hidden)}
            // ★ DC 显隐修复：与 NES/PSX 同模式持久化到专属列表。
            GamePlatform.DC -> layout.copy {hiddenButtonsDc = updateHiddenList(layout.hiddenButtonsDc, key, hidden)}
            GamePlatform.NGCWII -> layout.copy {hiddenButtonsNgcwii = updateHiddenList(layout.hiddenButtonsNgcwii, key, hidden)}
            else -> layout
        }
    }

    /**
     * Get the input mode ("dpad" or "analog") for a given platform.
     * Arcade still uses its legacy arcadeInputMode field for backward compatibility.
     * All other platforms use the global inputMode field.
     */
    fun getInputMode(layout: PadLayout, platform: GamePlatform): String {
        return if (platform == GamePlatform.ARCADE) layout.arcadeInputMode else layout.inputMode
    }

    /**
     * Set the input mode for a given platform.
     * Arcade updates arcadeInputMode; all others update the global inputMode.
     */
    fun setInputMode(layout: PadLayout, platform: GamePlatform, mode: String): PadLayout {
        return if (platform == GamePlatform.ARCADE) {
            layout.copy {arcadeInputMode = mode}
        } else {
            layout.copy {inputMode = mode}
        }
    }

    /** Check if a key exists in a comma-separated hidden list. */
    private fun isHiddenInList(list: String, key: String): Boolean {
        if (list.isBlank()) return false
        return list.split(",").map { it.trim() }.contains(key)
    }

    /** Add or remove a key from a comma-separated hidden list. */
    private fun updateHiddenList(list: String, key: String, hidden: Boolean): String {
        val keys = if (list.isBlank()) emptyList() else list.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        val updated = if (hidden) {
            if (keys.contains(key)) keys else keys + key
        } else {
            keys.filter { it != key }
        }
        return updated.joinToString(",")
    }

    /**
     * Return the list of available button keys for a given platform,
     * in display order. Used by the visibility toggle dialog.
     */
    fun getAvailableButtons(platform: GamePlatform): List<Pair<String, String>> {
        // Each pair: (key, displayLabel)
        // 连射 A/B（小 AB 连发键）在所有平台都提供显隐开关——包括有
        // X/Y 键的 6 键平台（SFC/MD/NDS/街机）。渲染层不再因为平台有
        // X/Y 就强制隐藏连发键，改由用户按需控制。
        // 即时存档 / 即时读档按钮（qs/ql）同样所有平台通用，追加在末尾。
        return when (platform) {
            GamePlatform.NES, GamePlatform.GB -> listOf(
                "dpad" to "十字键", "a" to "A键", "b" to "B键",
                "ta" to "连射A", "tb" to "连射B",
                "start" to "START", "select" to "SELECT"
            )
            GamePlatform.GBA -> listOf(
                "dpad" to "十字键", "a" to "A键", "b" to "B键",
                "ta" to "连射A", "tb" to "连射B",
                "l" to "L键", "r" to "R键",
                "start" to "START", "select" to "SELECT"
            )
            GamePlatform.SFC, GamePlatform.NDS -> listOf(
                "dpad" to "十字键", "a" to "A键", "b" to "B键",
                "x" to "X键", "y" to "Y键",
                "ta" to "连射A", "tb" to "连射B",
                "l" to "L键", "r" to "R键",
                "start" to "START", "select" to "SELECT"
            )
            GamePlatform.ARCADE -> listOf(
                // 街机（FBNeo）：四个动作键按街机机身命名 A/B/C/D
                // （bit8=街机键3=C、bit9=街机键4=D），输出经
                // arcadeToLibretroLayout 按同语义转换
                "dpad" to "十字键", "a" to "A键", "b" to "B键",
                "x" to "C键", "y" to "D键",
                "ta" to "连射A", "tb" to "连射B",
                "l" to "L键", "r" to "R键",
                "l2" to "L2键", "r2" to "R2键",
                "start" to "START", "select" to "SELECT"
            )
            GamePlatform.MD -> listOf(
                "dpad" to "十字键", "a" to "A键", "b" to "B键",
                "x" to "C键", "y" to "X键",
                "ta" to "连射A", "tb" to "连射B",
                "l" to "Y键", "r" to "Z键",
                "start" to "START", "select" to "SELECT"
            )
            GamePlatform.PCE -> listOf(
                "dpad" to "十字键", "a" to "I键", "b" to "II键",
                "x" to "IV键", "y" to "III键",
                "l" to "V键", "r" to "VI键",
                "l2" to "TURBO II", "r2" to "TURBO I",
                "start" to "RUN", "select" to "SELECT"
            )
            GamePlatform.PSX -> listOf(
                "dpad" to "十字键", "a" to "×键", "b" to "○键",
                "x" to "□键", "y" to "△键",
                "l" to "L1键", "r" to "R1键",
                "l2" to "L2键", "r2" to "R2键",
                "start" to "START", "select" to "SELECT"
            )
            GamePlatform.PS2 -> listOf(
                "dpad" to "十字键", "a" to "×键", "b" to "○键",
                "x" to "□键", "y" to "△键",
                "l" to "L1键", "r" to "R1键",
                "l2" to "L2键", "r2" to "R2键",
                "l3" to "L3键", "r3" to "R3键",
                "start" to "START", "select" to "SELECT"
                // 双摇杆为 PS2 常驻控件，不参与显隐
            )
            GamePlatform.DC -> listOf(
                // DC（libretro Flycast）：屏幕 A/B/X/Y 按标签语义直转
                //（dcToLibretroLayout：A→DC A、B→DC B、X→DC X、Y→DC Y）
                "dpad" to "十字键", "a" to "A键", "b" to "B键",
                "x" to "X键", "y" to "Y键",
                "ta" to "连射A", "tb" to "连射B",
                "l" to "L键", "r" to "R键",
                "start" to "START", "select" to "SELECT"
            )
            GamePlatform.TG3DS -> listOf(
                // 3DS（外部核心 Azahar/爱吾）：NesStation 侧的虚拟按键配置态。
                // 实机触摸层由 Azahar 自带 overlay 呈现；这里的显隐/布局配置
                // 与按键映射页共用一份键位语义（A/B/X/Y/L/R + START/SELECT）。
                "dpad" to "十字键", "a" to "A键", "b" to "B键",
                "x" to "X键", "y" to "Y键",
                "ta" to "连射A", "tb" to "连射B",
                "l" to "L键", "r" to "R键",
                "start" to "START", "select" to "SELECT"
            )
            GamePlatform.NGCWII -> listOf(
                // NGC/WII（外部核心 Ishiruka）：全量虚拟按键配置态 ——
                // GameCube 手柄 (A/B/X/Y/Z + 双摇杆 + L/R 扳机) 与
                // Wii Remote (1/2/A/B/±/HOME + 十字键 + C/Z + 体感)
                // 全键位均可在显隐对话框单独开关（控制器切换见
                // ngcwiiController，在启动页与核心设置页可改）。
                "dpad" to "十字键 (GC/Wii)",
                "a" to "A键 (GC A / Wii 2H A)",
                "b" to "B键 (GC B / Wii B)",
                "x" to "X键 (GC X)", "y" to "Y键 (GC Y)",
                "l" to "Z/扳机L (GC Z / L)",
                "r" to "R扳机 (GC R)",
                "l2" to "1键 (Wii 1)", "r2" to "2键 (Wii 2)",
                "ta" to "±/−键 (Wii Plus/Minus)",
                "tb" to "HOME键",
                "l3" to "C键 (Nunchuk)", "r3" to "Z键 (Nunchuk)",
                "start" to "START", "select" to "SELECT"
                // 体感开关 / 摇杆切换 / 即时存读档为通用尾项（qs/ql 全局追加，
                // 体感见 ngcwiiMotion，摇杆切换见 inputMode）
            )
            else -> emptyList()
        } + listOf("qs" to "即时存档", "ql" to "即时读档")
    }
}

// ===========================================================================
// J2ME 虚拟按键 → 手机按键 映射（Java 游戏专用）
//
// 映射方向：GameBox 虚拟手柄上的按键（A/B/X/Y/START/SELECT/方向键）
//   → J2ME 手机上的物理按键（MIDP 键码）。
// 映射列表覆盖真机手机的所有按键：数字键 1-9/0、*、#、四个方向键、
// 确认(FIRE)、左/右软键、清除键、挂机键以及 GAME_A~GAME_D。
// 仅记录用户显式修改过的项；未记录的键位使用内置默认映射
// （见 JAVA_BUTTON_DEFAULT_KEY）。
// ===========================================================================

/** 可参与映射的 J2ME 虚拟手柄键位（id → 显示名）。 */
val JAVA_MAPPABLE_BUTTONS: List<Pair<String, String>> = listOf(
    "up" to "方向键 ↑",
    "down" to "方向键 ↓",
    "left" to "方向键 ←",
    "right" to "方向键 →",
    "a" to "按键 A",
    "b" to "按键 B",
    "x" to "按键 X",
    "y" to "按键 Y",
    "start" to "START",
    "select" to "SELECT"
)

/** 各键位的默认 MIDP 键码（与 J2meEngine 内置 keyMap 一致）。 */
val JAVA_BUTTON_DEFAULT_KEY: Map<String, Int> = mapOf(
    "up" to -1,       // KEY_UP
    "down" to -2,     // KEY_DOWN
    "left" to -3,     // KEY_LEFT
    "right" to -4,    // KEY_RIGHT
    "a" to -5,        // KEY_FIRE (确认)
    "b" to -6,        // KEY_SOFT_LEFT (左软键)
    "x" to -7,        // KEY_SOFT_RIGHT (右软键)
    "y" to 42,        // KEY_STAR (*)
    "start" to -11,   // KEY_END (挂机键)
    "select" to 35    // KEY_POUND (#)
)

/**
 * 映射目标列表 —— 手机（J2ME 设备）的所有按键。
 * value = MIDP 键码，label = 用户可读名称。
 */
val JAVA_PHONE_KEY_OPTIONS: List<Pair<String, String>> = listOf(
    "1" to "数字键 1", "2" to "数字键 2", "3" to "数字键 3",
    "4" to "数字键 4", "5" to "数字键 5", "6" to "数字键 6",
    "7" to "数字键 7", "8" to "数字键 8", "9" to "数字键 9",
    "0" to "数字键 0",
    "42" to "* 键", "35" to "# 键",
    "-1" to "方向 上", "-2" to "方向 下", "-3" to "方向 左", "-4" to "方向 右",
    "-5" to "确认键 (FIRE)",
    "-6" to "左软键", "-7" to "右软键",
    "-8" to "清除键 (CLEAR)",
    "-11" to "挂机键 (END)",
    "-10" to "接听键 (SEND)",
    "9" to "游戏键 GAME_A", "10" to "游戏键 GAME_B",
    "11" to "游戏键 GAME_C", "12" to "游戏键 GAME_D"
)

/** 解析 javaButtonKeyMap 字符串为 (键位id → MIDP键码) 表。 */
fun parseJavaButtonKeyMap(raw: String): Map<String, Int> {
    if (raw.isBlank()) return emptyMap()
    val out = mutableMapOf<String, Int>()
    for (entry in raw.split(',')) {
        val parts = entry.split('=')
        if (parts.size != 2) continue
        val id = parts[0].trim()
        val code = parts[1].trim().toIntOrNull() ?: continue
        if (JAVA_BUTTON_DEFAULT_KEY.containsKey(id)) out[id] = code
    }
    return out
}

/** 读取某键位当前生效的 MIDP 键码（未映射时返回默认值）。 */
fun javaButtonKeyMapGet(raw: String, buttonId: String): Int =
    parseJavaButtonKeyMap(raw)[buttonId] ?: JAVA_BUTTON_DEFAULT_KEY[buttonId]
    ?: 0

/** 设置/更新某键位的映射，返回新的 javaButtonKeyMap 字符串。设为默认值时移除该条目。 */
fun javaButtonKeyMapSet(raw: String, buttonId: String, keyCode: Int): String {
    val map = parseJavaButtonKeyMap(raw).toMutableMap()
    if (keyCode == JAVA_BUTTON_DEFAULT_KEY[buttonId]) {
        map.remove(buttonId)
    } else {
        map[buttonId] = keyCode
    }
    return map.entries.joinToString(",") { "${it.key}=${it.value}" }
}

// ===========================================================================
// J2ME 每游戏单独设置（per-game overrides）
//
// 背景：J2ME 游戏分辨率五花八门（128x128 / 176x208 / 240x320 / 360x640 …），
// 缩放/帧率限制/触摸支持等设置如果全局共用，切换游戏就要反复改。
// 现在每个游戏可以有一份独立配置：
//   · 游戏内设置面板改 J2ME 设置 → 写入该游戏的专属配置（不再污染全局）；
//   · 游戏库长按卡片「游戏设置」→ 编辑同一份专属配置，进游戏即生效；
//   · 无专属配置的游戏使用全局默认（与旧行为兼容）；
//   · 「恢复全局默认」删除专属配置，回到跟随全局。
// 存储键用 romPath 的目录名（converted/<目录名>），重装同名游戏仍能命中。
// ===========================================================================

/** 一份 J2ME 游戏专属设置（字段与 PadLayout 的 java* 子集一一对应）。 */
data class JavaGameSettings(
    val javaInputMode: String,
    val javaScaleType: String,
    val javaShowFps: Boolean,
    val javaImmediateMode: Boolean,
    val javaResolution: String,
    val javaScaleRatio: String,
    val javaFpsLimit: String,
    val javaTouchInput: Boolean,
    val javaNumDualDispatch: Boolean,
    val javaButtonKeyMap: String,
    val javaPhoneGrid: ButtonLayout,
    val javaPhoneTop: ButtonLayout,
    val javaOpacity: Float
) {
    /** 从 PadLayout 抽取当前生效的 J2ME 设置快照。 */
    companion object {
        fun of(l: PadLayout): JavaGameSettings = JavaGameSettings(
            javaInputMode = l.javaInputMode,
            javaScaleType = l.javaScaleType,
            javaShowFps = l.javaShowFps,
            javaImmediateMode = l.javaImmediateMode,
            javaResolution = l.javaResolution,
            javaScaleRatio = l.javaScaleRatio,
            javaFpsLimit = l.javaFpsLimit,
            javaTouchInput = l.javaTouchInput,
            javaNumDualDispatch = l.javaNumDualDispatch,
            javaButtonKeyMap = l.javaButtonKeyMap,
            javaPhoneGrid = l.javaPhoneGrid,
            javaPhoneTop = l.javaPhoneTop,
            javaOpacity = l.javaOpacity
        )

        fun fromJson(json: String): JavaGameSettings? = try {
            val o = org.json.JSONObject(json)
            fun btn(name: String): ButtonLayout {
                val b = o.optJSONObject(name) ?: return ButtonLayout(0.5f, 0.8f, 48)
                return ButtonLayout(
                    b.optDouble("x", 0.5).toFloat(),
                    b.optDouble("y", 0.8).toFloat(),
                    b.optInt("size", 48)
                )
            }
            JavaGameSettings(
                javaInputMode = o.optString("inputMode", "gamepad"),
                javaScaleType = o.optString("scaleType", "fit"),
                javaShowFps = o.optBoolean("showFps", false),
                javaImmediateMode = o.optBoolean("immediateMode", false),
                javaResolution = o.optString("resolution", "default"),
                javaScaleRatio = o.optString("scaleRatio", "100"),
                javaFpsLimit = o.optString("fpsLimit", "0"),
                javaTouchInput = o.optBoolean("touchInput", true),
                javaNumDualDispatch = o.optBoolean("numDualDispatch", true),
                javaButtonKeyMap = o.optString("buttonKeyMap", ""),
                javaPhoneGrid = btn("phoneGrid"),
                javaPhoneTop = btn("phoneTop"),
                javaOpacity = o.optDouble("opacity", 0.8).toFloat().coerceIn(0.3f, 1f)
            )
        } catch (_: Exception) {
            null
        }
    }

    fun toJson(): String {
        val o = org.json.JSONObject()
        o.put("inputMode", javaInputMode)
        o.put("scaleType", javaScaleType)
        o.put("showFps", javaShowFps)
        o.put("immediateMode", javaImmediateMode)
        o.put("resolution", javaResolution)
        o.put("scaleRatio", javaScaleRatio)
        o.put("fpsLimit", javaFpsLimit)
        o.put("touchInput", javaTouchInput)
        o.put("numDualDispatch", javaNumDualDispatch)
        o.put("buttonKeyMap", javaButtonKeyMap)
        o.put("phoneGrid", org.json.JSONObject()
            .put("x", javaPhoneGrid.x.toDouble())
            .put("y", javaPhoneGrid.y.toDouble())
            .put("size", javaPhoneGrid.sizeDp))
        o.put("phoneTop", org.json.JSONObject()
            .put("x", javaPhoneTop.x.toDouble())
            .put("y", javaPhoneTop.y.toDouble())
            .put("size", javaPhoneTop.sizeDp))
        o.put("opacity", javaOpacity.toDouble())
        return o.toString()
    }
}

/** 把一份 J2ME 设置快照应用到 PadLayout（返回新实例，不修改原对象）。 */
fun PadLayout.withJavaSettings(s: JavaGameSettings): PadLayout = copy {
    javaInputMode = s.javaInputMode
    javaScaleType = s.javaScaleType
    javaShowFps = s.javaShowFps
    javaImmediateMode = s.javaImmediateMode
    javaResolution = s.javaResolution
    javaScaleRatio = s.javaScaleRatio
    javaFpsLimit = s.javaFpsLimit
    javaTouchInput = s.javaTouchInput
    javaNumDualDispatch = s.javaNumDualDispatch
    javaButtonKeyMap = s.javaButtonKeyMap
    javaPhoneGrid = s.javaPhoneGrid
    javaPhoneTop = s.javaPhoneTop
    javaOpacity = s.javaOpacity
}

/**
 * J2ME 每游戏设置的持久化仓库。
 * SharedPreferences 文件 "java_game_settings"，key = 游戏目录名，value = JSON。
 */
object JavaGameSettingsStore {
    private const val PREFS_NAME = "java_game_settings"

    private fun prefs(ctx: Context) =
        ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * 由 romPath 推导存储键。Java 游戏的 romPath 是转换目录
     * (<emulatorDir>/converted/<目录名>)，取最后一段即目录名，全局唯一且稳定。
     * 非 Java 路径 / 空路径返回 null（调用方按"无专属配置"处理）。
     */
    fun gameKey(romPath: String?): String? {
        val p = romPath?.trim() ?: return null
        if (p.isEmpty()) return null
        return p.trimEnd('/').substringAfterLast('/').takeIf { it.isNotEmpty() }
    }

    fun load(ctx: Context, key: String?): JavaGameSettings? {
        if (key == null) return null
        val raw = prefs(ctx).getString(key, null) ?: return null
        return JavaGameSettings.fromJson(raw)
    }

    fun has(ctx: Context, key: String?): Boolean {
        if (key == null) return false
        return prefs(ctx).contains(key)
    }

    fun save(ctx: Context, key: String?, settings: JavaGameSettings) {
        if (key == null) return
        prefs(ctx).edit().putString(key, settings.toJson()).apply()
    }

    fun remove(ctx: Context, key: String?) {
        if (key == null) return
        prefs(ctx).edit().remove(key).apply()
    }
}
