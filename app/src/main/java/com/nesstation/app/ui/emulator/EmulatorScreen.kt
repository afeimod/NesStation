package com.nesstation.app.ui.emulator

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Shader
import android.os.Environment
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.MotionEvent
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusRequester
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CameraAlt
import com.nesstation.app.ui.swf.NdsScreenPositionEditor
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.FastForward
import androidx.compose.material.icons.rounded.Fullscreen
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Save
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.Upload
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.changedToDown
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import android.content.res.Configuration
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.nesstation.app.core.engine.EmulatorEngine
import com.nesstation.app.core.engine.J2meEngine
import com.nesstation.app.core.model.GameEntry
import com.nesstation.app.core.model.GamePlatform
import com.nesstation.app.core.storage.ButtonLayout
import com.nesstation.app.core.storage.PadLayout
import com.nesstation.app.core.jni.DosKeys
import com.nesstation.app.core.storage.PadLayoutStore
import com.nesstation.app.core.storage.DosExtraKeyEntry
import com.nesstation.app.core.storage.JavaGameSettings
import com.nesstation.app.core.storage.JavaGameSettingsStore
import com.nesstation.app.core.storage.JAVA_MAPPABLE_BUTTONS
import com.nesstation.app.core.storage.JAVA_PHONE_KEY_OPTIONS
import com.nesstation.app.core.storage.javaButtonKeyMapGet
import com.nesstation.app.core.storage.javaButtonKeyMapSet
import com.nesstation.app.core.storage.withJavaSettings
import com.nesstation.app.ui.swf.ScreenPositionEditor
import com.nesstation.app.ui.fsd.FsdImaging
import com.nesstation.app.ui.settings.KeyMapStore
import android.view.KeyEvent
import android.view.View
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// ---------------------------------------------------------------------------
// TV mode detection — used to hide the touch-only on-screen gamepad on TV
// (where there is no touchscreen) and to enable D-pad / gamepad key routing.
// ---------------------------------------------------------------------------
private fun isTvMode(context: android.content.Context): Boolean {
    val pm = context.packageManager
    return !pm.hasSystemFeature(android.content.pm.PackageManager.FEATURE_TOUCHSCREEN)
}

// ---------------------------------------------------------------------------
// Physical gamepad key → controller bit mapping
// Used on TV (and whenever a Bluetooth/USB gamepad is connected) to drive
// the engine directly from a View.OnKeyListener attached to the SurfaceView.
// Bit layout must match the BTN_* constants below.
// ---------------------------------------------------------------------------
private fun gamepadKeyToBits(keyCode: Int, platform: GamePlatform): Int {
    // L/R bit values differ by platform:
    //   SNES: L=bit10 R=bit11 (X=bit8 Y=bit9 — SNES face layout)
    //   GBA:  L=bit8  R=bit9  (no X/Y face buttons)
    //   ARCADE/MD/PCE: L=bit10 R=bit11 (same as SNES — 6-button layout)
    val lBit = if (platform == GamePlatform.GBA) BTN_L_GBA else BTN_L_SNES
    val rBit = if (platform == GamePlatform.GBA) BTN_R_GBA else BTN_R_SNES
    return when (keyCode) {
        KeyEvent.KEYCODE_DPAD_UP       -> BTN_UP
        KeyEvent.KEYCODE_DPAD_DOWN     -> BTN_DOWN
        KeyEvent.KEYCODE_DPAD_LEFT     -> BTN_LEFT
        KeyEvent.KEYCODE_DPAD_RIGHT    -> BTN_RIGHT
        KeyEvent.KEYCODE_BUTTON_A      -> BTN_A
        KeyEvent.KEYCODE_BUTTON_B      -> BTN_B
        KeyEvent.KEYCODE_BUTTON_X      -> BTN_X
        KeyEvent.KEYCODE_BUTTON_Y      -> BTN_Y
        KeyEvent.KEYCODE_BUTTON_L1     -> lBit
        KeyEvent.KEYCODE_BUTTON_R1     -> rBit
        KeyEvent.KEYCODE_BUTTON_L2     -> BTN_L2
        KeyEvent.KEYCODE_BUTTON_R2     -> BTN_R2
        KeyEvent.KEYCODE_BUTTON_THUMBL -> BTN_L3
        KeyEvent.KEYCODE_BUTTON_THUMBR -> BTN_R3
        KeyEvent.KEYCODE_BUTTON_START,
        KeyEvent.KEYCODE_MENU          -> BTN_START
        KeyEvent.KEYCODE_BUTTON_SELECT -> BTN_SELECT
        else -> 0
    }
}

// ---------------------------------------------------------------------------
// Resolve key bits considering custom KeyMapStore mappings per player.
// Checks if the pressed keyCode has a custom binding for the current
// platform+player in KeyMapStore; if so, returns the bits for that action.
// Otherwise falls back to the default gamepadKeyToBits mapping.
// ---------------------------------------------------------------------------
private fun resolveKeyBits(
    keyCode: Int,
    platform: GamePlatform,
    player: Int,
    context: android.content.Context
): Int {
    val suffix = "_p${player + 1}"
    // Build the list of action IDs for this platform+player, then check
    // KeyMapStore for a custom keyCode match.
    val actions = buildKeyActions(platform)
    for (action in actions) {
        val customKeyCode = KeyMapStore.get(context, action.id + suffix)
        if (customKeyCode == keyCode) {
            return actionToBits(action, platform)
        }
    }
    // No custom mapping — use default
    return gamepadKeyToBits(keyCode, platform)
}

// Internal action model used by resolveKeyBits — mirrors KeyAction but
// without the Compose dependencies (Color, defaultKeyLabel).
private data class KeyActionInternal(
    val id: String,
    val defaultKeyCode: Int
)

private fun buildKeyActions(platform: GamePlatform): List<KeyActionInternal> {
    val base = when (platform) {
        GamePlatform.NES -> listOf(
            KeyActionInternal("nes_up", KeyEvent.KEYCODE_DPAD_UP),
            KeyActionInternal("nes_down", KeyEvent.KEYCODE_DPAD_DOWN),
            KeyActionInternal("nes_left", KeyEvent.KEYCODE_DPAD_LEFT),
            KeyActionInternal("nes_right", KeyEvent.KEYCODE_DPAD_RIGHT),
            KeyActionInternal("nes_a", KeyEvent.KEYCODE_BUTTON_A),
            KeyActionInternal("nes_b", KeyEvent.KEYCODE_BUTTON_B),
            KeyActionInternal("nes_select", KeyEvent.KEYCODE_BUTTON_SELECT),
            KeyActionInternal("nes_start", KeyEvent.KEYCODE_BUTTON_START),
            KeyActionInternal("nes_ta", KeyEvent.KEYCODE_BUTTON_L2),
            KeyActionInternal("nes_tb", KeyEvent.KEYCODE_BUTTON_R2)
        )
        GamePlatform.SFC -> listOf(
            KeyActionInternal("snes_up", KeyEvent.KEYCODE_DPAD_UP),
            KeyActionInternal("snes_down", KeyEvent.KEYCODE_DPAD_DOWN),
            KeyActionInternal("snes_left", KeyEvent.KEYCODE_DPAD_LEFT),
            KeyActionInternal("snes_right", KeyEvent.KEYCODE_DPAD_RIGHT),
            KeyActionInternal("snes_a", KeyEvent.KEYCODE_BUTTON_A),
            KeyActionInternal("snes_b", KeyEvent.KEYCODE_BUTTON_B),
            KeyActionInternal("snes_x", KeyEvent.KEYCODE_BUTTON_X),
            KeyActionInternal("snes_y", KeyEvent.KEYCODE_BUTTON_Y),
            KeyActionInternal("snes_l", KeyEvent.KEYCODE_BUTTON_L1),
            KeyActionInternal("snes_r", KeyEvent.KEYCODE_BUTTON_R1),
            KeyActionInternal("snes_select", KeyEvent.KEYCODE_BUTTON_SELECT),
            KeyActionInternal("snes_start", KeyEvent.KEYCODE_BUTTON_START)
        )
        GamePlatform.GB -> listOf(
            KeyActionInternal("nes_up", KeyEvent.KEYCODE_DPAD_UP),
            KeyActionInternal("nes_down", KeyEvent.KEYCODE_DPAD_DOWN),
            KeyActionInternal("nes_left", KeyEvent.KEYCODE_DPAD_LEFT),
            KeyActionInternal("nes_right", KeyEvent.KEYCODE_DPAD_RIGHT),
            KeyActionInternal("nes_a", KeyEvent.KEYCODE_BUTTON_A),
            KeyActionInternal("nes_b", KeyEvent.KEYCODE_BUTTON_B),
            KeyActionInternal("nes_select", KeyEvent.KEYCODE_BUTTON_SELECT),
            KeyActionInternal("nes_start", KeyEvent.KEYCODE_BUTTON_START)
        )
        GamePlatform.GBA -> listOf(
            KeyActionInternal("gba_up", KeyEvent.KEYCODE_DPAD_UP),
            KeyActionInternal("gba_down", KeyEvent.KEYCODE_DPAD_DOWN),
            KeyActionInternal("gba_left", KeyEvent.KEYCODE_DPAD_LEFT),
            KeyActionInternal("gba_right", KeyEvent.KEYCODE_DPAD_RIGHT),
            KeyActionInternal("gba_a", KeyEvent.KEYCODE_BUTTON_A),
            KeyActionInternal("gba_b", KeyEvent.KEYCODE_BUTTON_B),
            KeyActionInternal("gba_l", KeyEvent.KEYCODE_BUTTON_L1),
            KeyActionInternal("gba_r", KeyEvent.KEYCODE_BUTTON_R1),
            KeyActionInternal("gba_select", KeyEvent.KEYCODE_BUTTON_SELECT),
            KeyActionInternal("gba_start", KeyEvent.KEYCODE_BUTTON_START)
        )
        GamePlatform.DOS -> listOf(
            KeyActionInternal("dos_up", KeyEvent.KEYCODE_DPAD_UP),
            KeyActionInternal("dos_down", KeyEvent.KEYCODE_DPAD_DOWN),
            KeyActionInternal("dos_left", KeyEvent.KEYCODE_DPAD_LEFT),
            KeyActionInternal("dos_right", KeyEvent.KEYCODE_DPAD_RIGHT),
            KeyActionInternal("dos_a", KeyEvent.KEYCODE_BUTTON_A),
            KeyActionInternal("dos_b", KeyEvent.KEYCODE_BUTTON_B),
            KeyActionInternal("dos_x", KeyEvent.KEYCODE_BUTTON_X),
            KeyActionInternal("dos_y", KeyEvent.KEYCODE_BUTTON_Y),
            KeyActionInternal("dos_l", KeyEvent.KEYCODE_BUTTON_L1),
            KeyActionInternal("dos_r", KeyEvent.KEYCODE_BUTTON_R1),
            KeyActionInternal("dos_l2", KeyEvent.KEYCODE_BUTTON_L2),
            KeyActionInternal("dos_r2", KeyEvent.KEYCODE_BUTTON_R2),
            KeyActionInternal("dos_select", KeyEvent.KEYCODE_BUTTON_SELECT),
            KeyActionInternal("dos_start", KeyEvent.KEYCODE_BUTTON_START)
        )
        GamePlatform.ARCADE -> listOf(
            KeyActionInternal("arc_up", KeyEvent.KEYCODE_DPAD_UP),
            KeyActionInternal("arc_down", KeyEvent.KEYCODE_DPAD_DOWN),
            KeyActionInternal("arc_left", KeyEvent.KEYCODE_DPAD_LEFT),
            KeyActionInternal("arc_right", KeyEvent.KEYCODE_DPAD_RIGHT),
            KeyActionInternal("arc_a", KeyEvent.KEYCODE_BUTTON_A),
            KeyActionInternal("arc_b", KeyEvent.KEYCODE_BUTTON_B),
            KeyActionInternal("arc_x", KeyEvent.KEYCODE_BUTTON_X),
            KeyActionInternal("arc_y", KeyEvent.KEYCODE_BUTTON_Y),
            KeyActionInternal("arc_l", KeyEvent.KEYCODE_BUTTON_L1),
            KeyActionInternal("arc_r", KeyEvent.KEYCODE_BUTTON_R1),
            KeyActionInternal("arc_l2", KeyEvent.KEYCODE_BUTTON_L2),
            KeyActionInternal("arc_r2", KeyEvent.KEYCODE_BUTTON_R2),
            KeyActionInternal("arc_select", KeyEvent.KEYCODE_BUTTON_SELECT),
            KeyActionInternal("arc_start", KeyEvent.KEYCODE_BUTTON_START)
        )
        GamePlatform.MD -> listOf(
            KeyActionInternal("md_up", KeyEvent.KEYCODE_DPAD_UP),
            KeyActionInternal("md_down", KeyEvent.KEYCODE_DPAD_DOWN),
            KeyActionInternal("md_left", KeyEvent.KEYCODE_DPAD_LEFT),
            KeyActionInternal("md_right", KeyEvent.KEYCODE_DPAD_RIGHT),
            KeyActionInternal("md_a", KeyEvent.KEYCODE_BUTTON_A),
            KeyActionInternal("md_b", KeyEvent.KEYCODE_BUTTON_B),
            KeyActionInternal("md_c", KeyEvent.KEYCODE_BUTTON_R1),
            KeyActionInternal("md_x", KeyEvent.KEYCODE_BUTTON_X),
            KeyActionInternal("md_y", KeyEvent.KEYCODE_BUTTON_Y),
            KeyActionInternal("md_z", KeyEvent.KEYCODE_BUTTON_L1),
            KeyActionInternal("md_mode", KeyEvent.KEYCODE_BUTTON_MODE),
            KeyActionInternal("md_start", KeyEvent.KEYCODE_BUTTON_START)
        )
        GamePlatform.PCE -> listOf(
            KeyActionInternal("pce_up", KeyEvent.KEYCODE_DPAD_UP),
            KeyActionInternal("pce_down", KeyEvent.KEYCODE_DPAD_DOWN),
            KeyActionInternal("pce_left", KeyEvent.KEYCODE_DPAD_LEFT),
            KeyActionInternal("pce_right", KeyEvent.KEYCODE_DPAD_RIGHT),
            KeyActionInternal("pce_i", KeyEvent.KEYCODE_BUTTON_A),
            KeyActionInternal("pce_ii", KeyEvent.KEYCODE_BUTTON_B),
            KeyActionInternal("pce_iii", KeyEvent.KEYCODE_BUTTON_Y),
            KeyActionInternal("pce_iv", KeyEvent.KEYCODE_BUTTON_X),
            KeyActionInternal("pce_v", KeyEvent.KEYCODE_BUTTON_L1),
            KeyActionInternal("pce_vi", KeyEvent.KEYCODE_BUTTON_R1),
            KeyActionInternal("pce_turbo_ii", KeyEvent.KEYCODE_BUTTON_L2),
            KeyActionInternal("pce_turbo_i", KeyEvent.KEYCODE_BUTTON_R2),
            KeyActionInternal("pce_select", KeyEvent.KEYCODE_BUTTON_SELECT),
            KeyActionInternal("pce_run", KeyEvent.KEYCODE_BUTTON_START)
        )
        GamePlatform.JAVA -> listOf(
            KeyActionInternal("java_up", KeyEvent.KEYCODE_DPAD_UP),
            KeyActionInternal("java_down", KeyEvent.KEYCODE_DPAD_DOWN),
            KeyActionInternal("java_left", KeyEvent.KEYCODE_DPAD_LEFT),
            KeyActionInternal("java_right", KeyEvent.KEYCODE_DPAD_RIGHT),
            KeyActionInternal("java_a", KeyEvent.KEYCODE_BUTTON_A),
            KeyActionInternal("java_b", KeyEvent.KEYCODE_BUTTON_B),
            KeyActionInternal("java_select", KeyEvent.KEYCODE_BUTTON_SELECT),
            KeyActionInternal("java_start", KeyEvent.KEYCODE_BUTTON_START)
        )
        // NDS / PSX use the same 12-button SNES layout
        GamePlatform.NDS, GamePlatform.PSX -> listOf(
            KeyActionInternal("${if (platform == GamePlatform.NDS) "nds" else "psx"}_up", KeyEvent.KEYCODE_DPAD_UP),
            KeyActionInternal("${if (platform == GamePlatform.NDS) "nds" else "psx"}_down", KeyEvent.KEYCODE_DPAD_DOWN),
            KeyActionInternal("${if (platform == GamePlatform.NDS) "nds" else "psx"}_left", KeyEvent.KEYCODE_DPAD_LEFT),
            KeyActionInternal("${if (platform == GamePlatform.NDS) "nds" else "psx"}_right", KeyEvent.KEYCODE_DPAD_RIGHT),
            KeyActionInternal("${if (platform == GamePlatform.NDS) "nds" else "psx"}_a", KeyEvent.KEYCODE_BUTTON_A),
            KeyActionInternal("${if (platform == GamePlatform.NDS) "nds" else "psx"}_b", KeyEvent.KEYCODE_BUTTON_B),
            KeyActionInternal("${if (platform == GamePlatform.NDS) "nds" else "psx"}_x", KeyEvent.KEYCODE_BUTTON_X),
            KeyActionInternal("${if (platform == GamePlatform.NDS) "nds" else "psx"}_y", KeyEvent.KEYCODE_BUTTON_Y),
            KeyActionInternal("${if (platform == GamePlatform.NDS) "nds" else "psx"}_l", KeyEvent.KEYCODE_BUTTON_L1),
            KeyActionInternal("${if (platform == GamePlatform.NDS) "nds" else "psx"}_r", KeyEvent.KEYCODE_BUTTON_R1),
            KeyActionInternal("${if (platform == GamePlatform.NDS) "nds" else "psx"}_select", KeyEvent.KEYCODE_BUTTON_SELECT),
            KeyActionInternal("${if (platform == GamePlatform.NDS) "nds" else "psx"}_start", KeyEvent.KEYCODE_BUTTON_START)
        )
        // PS2 (PCEE2 — PCSX2 core) — full DualShock 2: 16 buttons, incl. L2/R2/L3/R3.
        // Analog sticks come from physical gamepads via the OS — only button
        // events reach this key-action table.
        GamePlatform.PS2 -> listOf(
            KeyActionInternal("ps2_up", KeyEvent.KEYCODE_DPAD_UP),
            KeyActionInternal("ps2_down", KeyEvent.KEYCODE_DPAD_DOWN),
            KeyActionInternal("ps2_left", KeyEvent.KEYCODE_DPAD_LEFT),
            KeyActionInternal("ps2_right", KeyEvent.KEYCODE_DPAD_RIGHT),
            KeyActionInternal("ps2_a", KeyEvent.KEYCODE_BUTTON_A),      // × Cross
            KeyActionInternal("ps2_b", KeyEvent.KEYCODE_BUTTON_B),      // ○ Circle
            KeyActionInternal("ps2_x", KeyEvent.KEYCODE_BUTTON_X),      // □ Square
            KeyActionInternal("ps2_y", KeyEvent.KEYCODE_BUTTON_Y),      // △ Triangle
            KeyActionInternal("ps2_l", KeyEvent.KEYCODE_BUTTON_L1),
            KeyActionInternal("ps2_r", KeyEvent.KEYCODE_BUTTON_R1),
            KeyActionInternal("ps2_l2", KeyEvent.KEYCODE_BUTTON_L2),
            KeyActionInternal("ps2_r2", KeyEvent.KEYCODE_BUTTON_R2),
            KeyActionInternal("ps2_l3", KeyEvent.KEYCODE_BUTTON_THUMBL),
            KeyActionInternal("ps2_r3", KeyEvent.KEYCODE_BUTTON_THUMBR),
            KeyActionInternal("ps2_select", KeyEvent.KEYCODE_BUTTON_SELECT),
            KeyActionInternal("ps2_start", KeyEvent.KEYCODE_BUTTON_START)
        )
        // DC (Flycast — Dreamcast / Naomi / Atomiswave)。DC 标准手柄：
        // 十字键 + 摇杆(独立模拟轴) + A/B/X/Y + L/R 模拟扳机 + Start。
        // 屏幕标签 A/B/X/Y 与 DC 键同名；L/R 默认映射到 L2/R2 位（扳机）。
        // Naomi：Select=Coin、L3=Test、R3=Service（flycast 输入描述符）。
        GamePlatform.DC -> listOf(
            KeyActionInternal("dc_up", KeyEvent.KEYCODE_DPAD_UP),
            KeyActionInternal("dc_down", KeyEvent.KEYCODE_DPAD_DOWN),
            KeyActionInternal("dc_left", KeyEvent.KEYCODE_DPAD_LEFT),
            KeyActionInternal("dc_right", KeyEvent.KEYCODE_DPAD_RIGHT),
            KeyActionInternal("dc_a", KeyEvent.KEYCODE_BUTTON_A),      // DC A
            KeyActionInternal("dc_b", KeyEvent.KEYCODE_BUTTON_B),      // DC B
            KeyActionInternal("dc_x", KeyEvent.KEYCODE_BUTTON_X),      // DC X
            KeyActionInternal("dc_y", KeyEvent.KEYCODE_BUTTON_Y),      // DC Y
            KeyActionInternal("dc_l", KeyEvent.KEYCODE_BUTTON_L2),     // L 扳机
            KeyActionInternal("dc_r", KeyEvent.KEYCODE_BUTTON_R2),     // R 扳机
            KeyActionInternal("dc_select", KeyEvent.KEYCODE_BUTTON_SELECT),
            KeyActionInternal("dc_start", KeyEvent.KEYCODE_BUTTON_START)
        )
    }
    return base
}

// Convert an internal action to its bit mask, matching gamepadKeyToBits logic.
private fun actionToBits(action: KeyActionInternal, platform: GamePlatform): Int {
    val lBit = if (platform == GamePlatform.GBA) BTN_L_GBA else BTN_L_SNES
    val rBit = if (platform == GamePlatform.GBA) BTN_R_GBA else BTN_R_SNES
    return when (action.defaultKeyCode) {
        KeyEvent.KEYCODE_DPAD_UP       -> BTN_UP
        KeyEvent.KEYCODE_DPAD_DOWN     -> BTN_DOWN
        KeyEvent.KEYCODE_DPAD_LEFT     -> BTN_LEFT
        KeyEvent.KEYCODE_DPAD_RIGHT    -> BTN_RIGHT
        KeyEvent.KEYCODE_BUTTON_A      -> BTN_A
        KeyEvent.KEYCODE_BUTTON_B      -> BTN_B
        KeyEvent.KEYCODE_BUTTON_X      -> BTN_X
        KeyEvent.KEYCODE_BUTTON_Y      -> BTN_Y
        KeyEvent.KEYCODE_BUTTON_L1     -> lBit
        KeyEvent.KEYCODE_BUTTON_R1     -> rBit
        KeyEvent.KEYCODE_BUTTON_L2     -> BTN_L2
        KeyEvent.KEYCODE_BUTTON_R2     -> BTN_R2
        KeyEvent.KEYCODE_BUTTON_THUMBL -> BTN_L3
        KeyEvent.KEYCODE_BUTTON_THUMBR -> BTN_R3
        KeyEvent.KEYCODE_BUTTON_START  -> BTN_START
        KeyEvent.KEYCODE_BUTTON_SELECT -> BTN_SELECT
        else -> 0
    }
}

// ---------------------------------------------------------------------------
// Button types for multi-touch tracking
// ---------------------------------------------------------------------------
// GAME_AREA: pointer landed on no pad button — forwarded to the game view
// (NDS bottom-screen touchscreen) via OnScreenController.onUnhandledTouch.
// This is REQUIRED because Compose hit-testing stops at the topmost hit
// sibling that doesn't share pointer input (shareWithSiblings=false default):
// this pad overlay's fillMaxSize pointerInput Box sits ABOVE the game view
// (AndroidView sibling), so without forwarding the game view would NEVER
// receive any touch event while the pad is visible.
private enum class BtnType { DPAD, A, B, TURBO_A, TURBO_B, START, SELECT, L, R, X, Y, L2, R2, L3, R3, LSTICK, RSTICK, COMBO, QUICK_SAVE, QUICK_LOAD, GAME_AREA }

// Bit masks for NES/SNES/GBA controller
// NES/GB/GBC: A B SEL STA U D L R (8 buttons)
// GBA: adds L(bit8) R(bit9) (10 buttons)
// SNES: adds X(bit8) Y(bit9) L(bit10) R(bit11) (12 buttons)
// Arcade/MD: extends with L2(bit12) R2(bit13) for 6-button fight sticks
internal const val BTN_UP = 0x10
internal const val BTN_DOWN = 0x20
internal const val BTN_LEFT = 0x40
internal const val BTN_RIGHT = 0x80
internal const val BTN_A = 0x01
internal const val BTN_B = 0x02
internal const val BTN_SELECT = 0x04
internal const val BTN_START = 0x08
internal const val BTN_X = 0x100       // bit8 — SNES X / Arcade button 3
internal const val BTN_Y = 0x200       // bit9 — SNES Y / Arcade button 4
internal const val BTN_L_SNES = 0x400  // bit10 — SNES L / Arcade button 5
internal const val BTN_R_SNES = 0x800  // bit11 — SNES R / Arcade button 6
internal const val BTN_L_GBA = 0x100   // bit8 — GBA L
internal const val BTN_R_GBA = 0x200   // bit9 — GBA R
internal const val BTN_L2 = 0x1000     // bit12 — L2 (Arcade / extra)
internal const val BTN_R2 = 0x2000     // bit13 — R2 (Arcade / extra)
internal const val BTN_L3 = 0x4000     // bit14 — L3 (left stick click)
internal const val BTN_R3 = 0x8000     // bit15 — R3 (right stick click)

// 将项目 SNES 风格布局转换为 libretro 标准 JOYPAD 布局。
// 项目布局：A=bit0, B=bit1, Select=bit2, Start=bit3, Up=bit4, Down=bit5, Left=bit6, Right=bit7,
//            X=bit8, Y=bit9, L=bit10, R=bit11
// libretro 标准：B=bit0, Y=bit1, Select=bit2, Start=bit3, Up=bit4, Down=bit5, Left=bit6, Right=bit7,
//                A=bit8, X=bit9, L=bit10, R=bit11
// melonDS 内部用 ADD_KEY_TO_MASK(libretro_id, nds_bit) 将 libretro 布局映射到 NDS KEYINPUT。
private fun projectToLibretroLayout(bits: Int): Int {
    var r = 0
    if (bits and BTN_A != 0)       r = r or (1 shl 8)   // proj A(bit0) -> libretro A(bit8)
    if (bits and BTN_B != 0)       r = r or (1 shl 0)   // proj B(bit1) -> libretro B(bit0)
    if (bits and BTN_SELECT != 0)  r = r or (1 shl 2)   // Select(bit2) -> Select(bit2)
    if (bits and BTN_START != 0)   r = r or (1 shl 3)   // Start(bit3)  -> Start(bit3)
    if (bits and BTN_UP != 0)      r = r or (1 shl 4)   // Up(bit4)     -> Up(bit4)
    if (bits and BTN_DOWN != 0)    r = r or (1 shl 5)   // Down(bit5)   -> Down(bit5)
    if (bits and BTN_LEFT != 0)    r = r or (1 shl 6)   // Left(bit6)   -> Left(bit6)
    if (bits and BTN_RIGHT != 0)   r = r or (1 shl 7)   // Right(bit7)  -> Right(bit7)
    if (bits and BTN_X != 0)       r = r or (1 shl 9)   // proj X(bit8) -> libretro X(bit9)
    if (bits and BTN_Y != 0)       r = r or (1 shl 1)   // proj Y(bit9) -> libretro Y(bit1)
    if (bits and BTN_L_SNES != 0)  r = r or (1 shl 10)  // L(bit10)     -> L(bit10)
    if (bits and BTN_R_SNES != 0)  r = r or (1 shl 11)  // R(bit11)     -> R(bit11)
    if (bits and BTN_L2 != 0)      r = r or (1 shl 12)  // L2
    if (bits and BTN_R2 != 0)      r = r or (1 shl 13)  // R2
    if (bits and BTN_L3 != 0)      r = r or (1 shl 14)  // L3
    if (bits and BTN_R3 != 0)      r = r or (1 shl 15)  // R3
    return r
}

// PS2 (PCEE2) 专用：项目位布局 → libretro 16 键 DualShock 布局。
// PS2 虚拟按键的 label 映射为：A=×(Cross)、B=○(Circle)、X=□(Square)、Y=△(Triangle)，
// 而 libretro 标准位是 B=bit0(Cross)、Y=bit1(Square)、A=bit8(Circle)、X=bit9(Triangle)，
// 与 projectToLibretroLayout 的 SNES 习惯（A→8, B→0, X→9, Y→1）不同 —— 这里按
// PS2 的 label 语义直转，保证屏幕上的 × 就是真的 Cross。
//   × (项目 A/bit0)  -> libretro bit0  (B = Cross)
//   ○ (项目 B/bit1)  -> libretro bit8  (A = Circle)
//   □ (项目 X/bit8)  -> libretro bit1  (Y = Square)
//   △ (项目 Y/bit9)  -> libretro bit9  (X = Triangle)
//   方向/Select/Start/L1/R1/L2/R2/L3/R3 与 libretro 同位。
private fun ps2ToLibretroLayout(bits: Int): Int {
    var r = 0
    if (bits and BTN_A != 0)       r = r or (1 shl 0)   // × Cross
    if (bits and BTN_B != 0)       r = r or (1 shl 8)   // ○ Circle
    if (bits and BTN_X != 0)       r = r or (1 shl 1)   // □ Square
    if (bits and BTN_Y != 0)       r = r or (1 shl 9)   // △ Triangle
    if (bits and BTN_SELECT != 0)  r = r or (1 shl 2)   // Select
    if (bits and BTN_START != 0)   r = r or (1 shl 3)   // Start
    if (bits and BTN_UP != 0)      r = r or (1 shl 4)   // Up
    if (bits and BTN_DOWN != 0)    r = r or (1 shl 5)   // Down
    if (bits and BTN_LEFT != 0)    r = r or (1 shl 6)   // Left
    if (bits and BTN_RIGHT != 0)   r = r or (1 shl 7)   // Right
    if (bits and BTN_L_SNES != 0)  r = r or (1 shl 10)  // L1
    if (bits and BTN_R_SNES != 0)  r = r or (1 shl 11)  // R1
    if (bits and BTN_L2 != 0)      r = r or (1 shl 12)  // L2
    if (bits and BTN_R2 != 0)      r = r or (1 shl 13)  // R2
    if (bits and BTN_L3 != 0)      r = r or (1 shl 14)  // L3
    if (bits and BTN_R3 != 0)      r = r or (1 shl 15)  // R3
    return r
}

// PSX (PCSX-ReARMed) 专用：项目位布局 → libretro 16 键 DualShock 布局。
// PCSX-ReARMed 的 libretro 前端把 PS1 键位映射为：
//   Cross(✕)=JOYPAD_B(bit0)、Square(□)=JOYPAD_Y(bit1)、
//   Circle(○)=JOYPAD_A(bit8)、Triangle(△)=JOYPAD_X(bit9)。
// PSX 屏幕标签与 PS2 不同：A=✕、B=○、X=△、Y=□，所以 X/Y 的转换与
// ps2ToLibretroLayout 相反，不能直接复用 projectToLibretroLayout（那是
// SNES 习惯 A→8/B→0/X→9/Y→1，会把 ✕ 输出成 Circle）。
//   ✕ (项目 A/bit0)  -> libretro bit0  (B = Cross)
//   ○ (项目 B/bit1)  -> libretro bit8  (A = Circle)
//   △ (项目 X/bit8)  -> libretro bit9  (X = Triangle)
//   □ (项目 Y/bit9)  -> libretro bit1  (Y = Square)
//   方向/Select/Start/L1/R1/L2/R2/L3/R3 与 libretro 同位。
private fun psxToLibretroLayout(bits: Int): Int {
    var r = 0
    if (bits and BTN_A != 0)       r = r or (1 shl 0)   // ✕ Cross
    if (bits and BTN_B != 0)       r = r or (1 shl 8)   // ○ Circle
    if (bits and BTN_X != 0)       r = r or (1 shl 9)   // △ Triangle
    if (bits and BTN_Y != 0)       r = r or (1 shl 1)   // □ Square
    if (bits and BTN_SELECT != 0)  r = r or (1 shl 2)   // Select
    if (bits and BTN_START != 0)   r = r or (1 shl 3)   // Start
    if (bits and BTN_UP != 0)      r = r or (1 shl 4)   // Up
    if (bits and BTN_DOWN != 0)    r = r or (1 shl 5)   // Down
    if (bits and BTN_LEFT != 0)    r = r or (1 shl 6)   // Left
    if (bits and BTN_RIGHT != 0)   r = r or (1 shl 7)   // Right
    if (bits and BTN_L_SNES != 0)  r = r or (1 shl 10)  // L1
    if (bits and BTN_R_SNES != 0)  r = r or (1 shl 11)  // R1
    if (bits and BTN_L2 != 0)      r = r or (1 shl 12)  // L2
    if (bits and BTN_R2 != 0)      r = r or (1 shl 13)  // R2
    if (bits and BTN_L3 != 0)      r = r or (1 shl 14)  // L3
    if (bits and BTN_R3 != 0)      r = r or (1 shl 15)  // R3
    return r
}

// DC (Flycast) 专用：项目位布局 → libretro JOYPAD 布局。
// flycast 的 dc_joymap（shell/libretro/libretro.cpp，已对照上游源码）：
//   JOYPAD_B(bit0)=DC A / 街机 Button 1    JOYPAD_A(bit8)=DC B / Button 2
//   JOYPAD_Y(bit1)=DC X / Button 3         JOYPAD_X(bit9)=DC Y / Button 4
//   JOYPAD_L(bit10)=DC C / Button 6        JOYPAD_R(bit11)=DC Z / Button 5
//   JOYPAD_L2(bit12)=左扳机 / Button 8     JOYPAD_R2(bit13)=右扳机 / Button 7
//   SELECT(bit2)=Coin（Naomi 投币）/ DC D 键   L3/R3=Test/Service（Naomi）
// 因此屏幕标签 A/B/X/Y 与 DC 键位同名直传：
//   屏A(bit0)→libretro bit0、屏B(bit1)→bit8、屏X(bit8)→bit1、屏Y(bit9)→bit9。
// DC 的 L/R 扳机在屏幕上用 L2/R2 位（详见 lBit/rBit 的 DC 分支），
// 物理 L1/R1 仍落在 C/Z（Naomi Button 6/5），L3/R3 保留 Test/Service。
private fun dcToLibretroLayout(bits: Int): Int {
    var r = 0
    if (bits and BTN_A != 0)       r = r or (1 shl 0)   // DC A / Button 1
    if (bits and BTN_B != 0)       r = r or (1 shl 8)   // DC B / Button 2
    if (bits and BTN_X != 0)       r = r or (1 shl 1)   // DC X / Button 3
    if (bits and BTN_Y != 0)       r = r or (1 shl 9)   // DC Y / Button 4
    if (bits and BTN_SELECT != 0)  r = r or (1 shl 2)   // Coin (Naomi) / DC D
    if (bits and BTN_START != 0)   r = r or (1 shl 3)   // Start
    if (bits and BTN_UP != 0)      r = r or (1 shl 4)   // Up
    if (bits and BTN_DOWN != 0)    r = r or (1 shl 5)   // Down
    if (bits and BTN_LEFT != 0)    r = r or (1 shl 6)   // Left
    if (bits and BTN_RIGHT != 0)   r = r or (1 shl 7)   // Right
    if (bits and BTN_L_SNES != 0)  r = r or (1 shl 10)  // C / Button 6
    if (bits and BTN_R_SNES != 0)  r = r or (1 shl 11)  // Z / Button 5
    if (bits and BTN_L2 != 0)      r = r or (1 shl 12)  // 左扳机 / Button 8
    if (bits and BTN_R2 != 0)      r = r or (1 shl 13)  // 右扳机 / Button 7
    if (bits and BTN_L3 != 0)      r = r or (1 shl 14)  // Test (Naomi)
    if (bits and BTN_R3 != 0)      r = r or (1 shl 15)  // Service (Naomi)
    return r
}

// ---------------------------------------------------------------------------
// Game folder loader (DOS games and PCE-CD / Mega-CD games)
// ---------------------------------------------------------------------------
// Both DOSBox-Pure and Geargrafx need the ENTIRE game folder to run a CD
// game:
//   * DOSBox-Pure needs launcher + assets + data files; when the user
//     imports a DOS game we only persist the launcher URI.
//   * Geargrafx (PCE-CD) opens the .cue file, which references .bin audio
//     tracks by RELATIVE path. If we copied only the .cue (as the plain
//     single-file SAF import does), the core cannot find the .bin tracks,
//     retro_load_game() fails, and pce_loader.cpp appends a misleading
//     "System Card BIOS missing" message — even when the BIOS is present.
// This function:
//   1. Resolves the launcher's parent folder via DocumentsContract.
//   2. Recursively copies the whole folder (preserving subfolder structure)
//      into <filesDir>/<subDir>/<sanitizedGameId>/.
//   3. Returns the absolute path of the copied launcher file, or null on
//      failure.
//
// The copy is cached per-game-id: if the destination folder already exists
// and contains the launcher, we skip the copy (so re-launching a game is
// instant). Delete the folder to force a re-copy.
//
// Works with content:// URIs that contain UTF-8 percent-encoded Chinese
// characters — DocumentsContract handles the encoding transparently, and
// the destination filenames use the original Unicode names.
// ---------------------------------------------------------------------------
private fun loadDosGameFolder(
    context: android.content.Context,
    launcherUriStr: String,
    gameId: String
): java.io.File? = loadGameFolder(context, launcherUriStr, gameId, "dos_games")

/** Same as [loadDosGameFolder], but copies into [subDir] under filesDir. */
private fun loadGameFolder(
    context: android.content.Context,
    launcherUriStr: String,
    gameId: String,
    subDir: String
): java.io.File? {
    // === Local file path fast-path ===
    // If the stored path is a plain filesystem path (not a content:// URI),
    // the launcher file is already on disk — just return it directly. The
    // folder is already accessible to the core via standard file I/O.
    if (!launcherUriStr.startsWith("content://")) {
        val f = java.io.File(launcherUriStr)
        return if (f.exists()) f else null
    }

    val uri = android.net.Uri.parse(launcherUriStr)
    // Determine the document ID of the launcher file. For a document URI
    // built via buildDocumentUriUsingTree, this is the last path segment
    // after "/document/".
    val docId = try {
        android.provider.DocumentsContract.getDocumentId(uri)
    } catch (_: Exception) {
        // Fallback: try tree URI (rare — means user picked a folder directly)
        try { android.provider.DocumentsContract.getTreeDocumentId(uri) }
        catch (_: Exception) { return null }
    }

    // Split the document ID into parent path + leaf filename.
    // SAF document IDs typically look like "primary:Games/DOSGame/play.bat"
    // or "msf:1234;Games/DOSGame/play.bat". We strip the last segment.
    val lastSlash = docId.lastIndexOf('/')
    val parentDocId = if (lastSlash > 0) docId.substring(0, lastSlash) else docId
    val launcherName = if (lastSlash > 0) docId.substring(lastSlash + 1) else docId

    // Derive the tree URI from the launcher URI. SAF document URIs look like:
    //   content://<authority>/tree/<treeDocId>/document/<docId>
    // The tree URI is just the first two path segments:
    //   content://<authority>/tree/<treeDocId>
    // We extract it by finding the "tree" path segment and taking the next
    // segment. For URIs that don't follow this shape we fall back to using
    // the parent doc id as the tree id.
    val treeUri = run {
        val paths = uri.pathSegments
        val treeIdx = paths.indexOf("tree")
        if (treeIdx >= 0 && treeIdx + 1 < paths.size) {
            // Standard SAF tree URI — reuse the original (already URL-encoded)
            // tree segment so persistable URI permissions match.
            android.net.Uri.Builder()
                .scheme(android.content.ContentResolver.SCHEME_CONTENT)
                .authority(uri.authority)
                .appendPath("tree")
                .appendPath(paths[treeIdx + 1])
                .build()
        } else {
            // Fallback: treat parentDocId as the tree root.
            android.net.Uri.Builder()
                .scheme(android.content.ContentResolver.SCHEME_CONTENT)
                .authority(uri.authority)
                .appendPath("tree")
                .appendPath(parentDocId)
                .build()
        }
    }

    val parentUri = android.provider.DocumentsContract.buildDocumentUriUsingTree(treeUri, parentDocId)

    // Sanitize game id → folder name (filesystem-safe, lowercase).
    val safeId = gameId.lowercase().replace(Regex("[^a-z0-9._-]"), "_")
        .takeIf { it.isNotBlank() } ?: "game"

    val destRoot = java.io.File(context.filesDir, "$subDir/$safeId")
    val destLauncher = java.io.File(destRoot, launcherName)

    // 复制完成标记：仅在整份复制成功写盘后才落盘。旧 fast-path 只看
    // destLauncher 是否存在/非空——若上次进程在复制大 CHD 中途被杀，
    // 残留的截断文件会被永久当作完整镜像返回；核心能读出 CHD 头部却
    // 读不到游戏数据 → BIOS 动画后黑屏。标记缺失时下次启动自动重拷。
    val copyMarker = java.io.File(destRoot, ".${safeId}.copy_ok")

    // Fast path: launcher already copied AND a full copy completed (marker
    // present). (User can clear app data to force re-copy.)
    if (destLauncher.exists() && destLauncher.length() > 0 && copyMarker.exists()) {
        return destLauncher
    }

    // === 历史副本兼容（copy_ok 标记机制引入之前复制的旧副本）===
    // 标记缺失但目标已存在且非空时，先统计源 SAF 文件夹与本地缓存的
    // 文件数 + 总字节数。完全一致 → 旧副本是完整的：回填标记直接复用，
    // 避免每次启动都删除目录重新复制多 GB 镜像（这正是"PS2 一直
    // 正在加载"的根因）。不一致（上次复制中途被杀留下的截断文件）→
    // 继续走删除重拷。
    if (destLauncher.exists() && destLauncher.length() > 0L && destRoot.isDirectory) {
        val srcStats = countSafFolder(context, treeUri, parentUri)
        val localStats = destRoot.walkTopDown()
            .filter { it.isFile && it.name != "${copyMarker.name}" }
            .fold(0L to 0L) { (cnt, total), f -> cnt + 1 to total + f.length() }
        if (srcStats != null &&
            srcStats.first == localStats.first &&
            srcStats.second == localStats.second
        ) {
            try { copyMarker.writeText("ok") } catch (_: Exception) { }
            return destLauncher
        }
    }

    // Wipe any stale partial copy.
    if (destRoot.exists()) destRoot.deleteRecursively()
    destRoot.mkdirs()

    // Recursively copy the parent folder into destRoot.
    try {
        copySafFolderRecursive(context, treeUri, parentUri, destRoot)
        // Copy finished without throwing — record completion so the fast
        // path never returns a truncated image left by a killed mid-copy.
        if (destLauncher.exists() && destLauncher.length() > 0) {
            copyMarker.writeText("ok")
        }
    } catch (e: Exception) {
        android.util.Log.e("DosLoader", "Folder copy failed", e)
        return null
    }

    // The launcher file should now exist in destRoot under its original name.
    return if (destLauncher.exists() && copyMarker.exists()) destLauncher else {
        // Fallback: find the first .bat/.exe/.com in destRoot.
        destRoot.walkTopDown()
            .firstOrNull { it.isFile && it.extension.lowercase() in setOf("bat", "exe", "com") }
    }
}

/**
 * 递归统计 SAF 文件夹下的文件数与总字节数。
 * 返回 (文件数, 总字节数)；无法读取时返回 null（调用方视为不可验证，
 * 走重新复制兜底）。用于验证历史副本（无 copy_ok 标记）是否完整。
 */
private fun countSafFolder(
    context: android.content.Context,
    treeUri: android.net.Uri,
    folderUri: android.net.Uri
): Pair<Long, Long>? {
    return try {
        var count = 0L
        var total = 0L
        val cr = context.contentResolver
        val childrenUri = android.provider.DocumentsContract.buildChildDocumentsUriUsingTree(
            treeUri,
            android.provider.DocumentsContract.getDocumentId(folderUri)
        )
        cr.query(childrenUri, null, null, null, null)?.use { cursor ->
            while (cursor.moveToNext()) {
                val docId = cursor.getString(cursor.getColumnIndexOrThrow(
                    android.provider.DocumentsContract.Document.COLUMN_DOCUMENT_ID)) ?: continue
                val name = cursor.getString(cursor.getColumnIndexOrThrow(
                    android.provider.DocumentsContract.Document.COLUMN_DISPLAY_NAME)) ?: continue
                val mimeType = cursor.getString(cursor.getColumnIndexOrThrow(
                    android.provider.DocumentsContract.Document.COLUMN_MIME_TYPE))
                val childUri = android.provider.DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)
                if (mimeType == android.provider.DocumentsContract.Document.MIME_TYPE_DIR) {
                    val sub = countSafFolder(context, treeUri, childUri) ?: return null
                    count += sub.first
                    total += sub.second
                } else {
                    // 用 SAF 报告的大小；若拿不到（个别 provider），尝试流读取可读长度。
                    var size = 0L
                    val sizeIdx = cursor.getColumnIndex(
                        android.provider.DocumentsContract.Document.COLUMN_SIZE)
                    if (sizeIdx >= 0 && !cursor.isNull(sizeIdx)) {
                        try { size = cursor.getLong(sizeIdx) } catch (_: Exception) { size = -1L }
                    } else {
                        size = -1L
                    }
                    if (size < 0L) {
                        try {
                            cr.openInputStream(childUri)?.use { input ->
                                size = input.available().toLong()
                            } ?: return null
                        } catch (_: Exception) { return null }
                    }
                    count++
                    total += size
                }
            }
        }
        count to total
    } catch (_: Exception) { null }
}

/** Recursively copy a SAF folder tree to a local File tree. */
private fun copySafFolderRecursive(
    context: android.content.Context,
    treeUri: android.net.Uri,
    folderUri: android.net.Uri,
    destFolder: java.io.File
) {
    val cr = context.contentResolver
    val childrenUri = android.provider.DocumentsContract.buildChildDocumentsUriUsingTree(
        treeUri,
        android.provider.DocumentsContract.getDocumentId(folderUri)
    )
    cr.query(childrenUri, null, null, null, null)?.use { cursor ->
        while (cursor.moveToNext()) {
            val docId = cursor.getString(cursor.getColumnIndexOrThrow(
                android.provider.DocumentsContract.Document.COLUMN_DOCUMENT_ID))
            val name = cursor.getString(cursor.getColumnIndexOrThrow(
                android.provider.DocumentsContract.Document.COLUMN_DISPLAY_NAME)) ?: continue
            val mimeType = cursor.getString(cursor.getColumnIndexOrThrow(
                android.provider.DocumentsContract.Document.COLUMN_MIME_TYPE))

            val childUri = android.provider.DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)
            val destFile = java.io.File(destFolder, name)

            if (mimeType == android.provider.DocumentsContract.Document.MIME_TYPE_DIR) {
                destFile.mkdirs()
                copySafFolderRecursive(context, treeUri, childUri, destFile)
            } else {
                // Copy file content.
                try {
                    cr.openInputStream(childUri)?.use { input ->
                        destFile.outputStream().use { out -> input.copyTo(out) }
                    }
                } catch (e: Exception) {
                    android.util.Log.w("DosLoader", "Failed to copy $name", e)
                    // Continue with other files — one missing asset shouldn't
                    // abort the whole game load.
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Main Emulator Screen
// ---------------------------------------------------------------------------
@Composable
fun EmulatorScreen(
    game: GameEntry,
    onExit: () -> Unit,
    /**
     * 可选：联机对战控制器。非 null 时，引擎的模拟循环会通过它做帧同步，
     * 路由到 [com.nesstation.app.core.engine.EmulatorEngine.frameHook]。
     *
     * 这是「进入对战」与「本地游戏」走同一条启动路径的关键 —— EmulatorScreen
     * 内部对 ROM 加载、SurfaceView、OnScreenController、菜单、布局编辑器、
     * 存档 等全部走本地游戏的逻辑；联机对战只是给引擎多挂了一个 hook。
     */
    netplayController: com.nesstation.app.battle.NetplayController? = null
) {
    val engine = remember { EmulatorEngine.forPlatform(game.platform) }
    val platform = game.platform
    val context = LocalContext.current
    // 用于在非协程回调（如菜单"重置"按钮）里启动协程，把 J2ME 重新加载
    // 这类重 IO 移出主线程——reset() 内部会调 loadRom()，MicroLoader 的
    // clearDirectory/MD5/dexopt 在主线程执行会卡死 5s+ 触发 ANR，
    // InputDispatcher 随即停止派发触摸并杀进程（1.txt 记录的根因）。
    val resetScope = rememberCoroutineScope()
    // TV mode: hide the touch-only on-screen gamepad and route all input
    // through the physical gamepad / D-pad key handler below.
    val isTv = remember { isTvMode(context) }
    // Tracks currently-held physical gamepad button bits. Uses a plain array
    // (not Compose state) so key presses don't trigger recomposition — the
    // bits are pushed directly to the engine via setPad1().
    val gamepadBitsHolder = remember { intArrayOf(0) }
    var running by remember { mutableStateOf(true) }
    var fastForwardSpeed by remember { mutableStateOf(0) } // 0=off, 6=default
    // Remembers the last non-zero FF speed so the toolbar toggle re-engages
    // at the user's chosen multiplier instead of always falling back to 6x.
    var lastNonZeroFFSpeed by remember { mutableStateOf(6) }
    var loaded by remember { mutableStateOf(false) }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    var surfaceSize by remember { mutableStateOf(IntSize.Zero) }

    var showMenu by remember { mutableStateOf(false) }
    var showLayoutEditor by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var saveLoadSlot by remember { mutableStateOf(0) } // 0-9 state slots
    var showSlotPicker by remember { mutableStateOf<String?>(null) } // "save" | "load" | null
    var showFFSpeedPicker by remember { mutableStateOf(false) }

    // Current active player for on-screen controller input (0-indexed).
    // 0 = player 1, 1 = player 2, etc.
    var currentPlayer by remember { mutableStateOf(0) }
    // Max players supported by this platform.
    // ARCADE/DC=4, NES/SFC/MD/PCE=2, GB/GBA/DOS/JAVA=1
    val maxPlayers = when (platform) {
        GamePlatform.ARCADE, GamePlatform.DC -> 4
        GamePlatform.DOS, GamePlatform.JAVA, GamePlatform.GB, GamePlatform.GBA -> 1
        else -> 2
    }

    // === 全局 FPS 显示（所有平台通用） ===
    // 模拟线程每跑完一帧回调 onFrame → counter+1（原子操作，线程安全）；
    // 采样协程每秒读取并清零，刷新显示值。显示的是"模拟帧率"（核心实际
    // 跑了多少帧），不是屏幕刷新率 —— 可用于诊断 NDS 卡顿。
    //
    // 修复（帧数显示不准）：PS2 / PS1 等平台优先读核心的"真实帧率"
    // （engine.realtimeFps()）——
    //   · PS2(ARMSX2) 是推模型核心，旧实现靠心跳线程按固定间隔打点，
    //     计数永远是 ~60，掉帧/快进都看不出来；现在直接读核心内部的
    //     PerformanceMetrics（getFPS）。
    //   · PS1(PCSX-ReARMed) 旧实现用模拟循环步进计数，只等于帧率限制器
    //     的目标值（NTSC 60 / PAL 50）；现在统计核心视频回调里真实提交
    //     的帧数，游戏内部掉帧（30/20fps 游戏）时能显示真实值。
    // 其它平台（NES/SFC/GBA/NDS/MD/PCE/DOS/ARCADE）仍用帧计数 ——
    // 拉模型核心每步恰好一帧，计数本身就是真实帧率。
    val fpsFrameCounter = remember { java.util.concurrent.atomic.AtomicInteger(0) }
    var fpsDisplay by remember { mutableStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(1000)
            // 无论走哪条路径都要清零计数器，避免 PS2 心跳计数长期累积
            val counted = fpsFrameCounter.getAndSet(0)
            val real = try { engine.realtimeFps() } catch (_: Throwable) { 0.0 }
            fpsDisplay = if (real > 0.5) Math.round(real).toInt() else counted
        }
    }

    // === J2ME 每游戏单独设置 ===
    // Java 游戏分辨率五花八门，缩放/分辨率/帧率/触摸等设置全局共用会互相
    // 踩踏 —— 现在按游戏单独保存（JavaGameSettingsStore，key=游戏目录名）：
    //   · 启动时读取该游戏的专属配置覆盖进会话状态（无则用全局默认）；
    //   · 游戏里改 J2ME 设置 → 持久化到专属配置，同时全局 prefs 里的 java*
    //     值保持进入会话前的全局快照（互不污染）；
    //   · 「恢复全局默认」删除专属配置并还原全局值。
    val javaGameKey = remember(game.id) {
        if (platform == GamePlatform.JAVA) JavaGameSettingsStore.gameKey(game.romPath) else null
    }
    val javaInit = remember {
        val loaded = PadLayoutStore.load(context, platform)
        if (platform == GamePlatform.JAVA && javaGameKey != null) {
            val perGame = JavaGameSettingsStore.load(context, javaGameKey)
            Triple(loaded, JavaGameSettings.of(loaded), perGame)
        } else {
            Triple(loaded, JavaGameSettings.of(loaded), null)
        }
    }
    var globalJavaSnapshot by remember { mutableStateOf(javaInit.second) }
    var javaHasOverride by remember { mutableStateOf(javaInit.third != null) }
    var padLayout by remember {
        mutableStateOf(
            if (javaInit.third != null) javaInit.first.withJavaSettings(javaInit.third!!)
            else javaInit.first
        )
    }
    // 恢复全局默认：删除专属配置 + 把会话状态还原成全局快照
    val resetJavaToGlobal: () -> Unit = {
        JavaGameSettingsStore.remove(context, javaGameKey)
        javaHasOverride = false
        padLayout = padLayout.withJavaSettings(globalJavaSnapshot)
    }

    // J2ME 游戏视图在窗口中的位置（触屏转发坐标换算用，见 J2meGameView 分支）
    var j2meViewPosInRoot by remember { mutableStateOf(androidx.compose.ui.geometry.Offset.Zero) }

    val isPortrait = LocalConfiguration.current.orientation == Configuration.ORIENTATION_PORTRAIT

    // Custom free-form screen layout editor state (videoScale == "custom").
    // customRect holds the live-dragged normalized rect [left, top, right, bottom];
    // it is persisted into padLayout on touch-up / exit, then saved by the
    // debounced LaunchedEffect below.
    //
    // 横竖屏分别保存布局：isPortrait 变化（旋转屏幕）时重新加载对应方向的
    // 矩形，避免竖屏下设置的布局被"同等压缩"后套用到横屏。
    var showCustomLayoutEditor by remember { mutableStateOf(false) }
    var customRect by remember(isPortrait) {
        mutableStateOf(floatArrayOf(
            if (isPortrait) padLayout.customLayoutLeftP else padLayout.customLayoutLeft,
            if (isPortrait) padLayout.customLayoutTopP else padLayout.customLayoutTop,
            if (isPortrait) padLayout.customLayoutRightP else padLayout.customLayoutRight,
            if (isPortrait) padLayout.customLayoutBottomP else padLayout.customLayoutBottom
        ))
    }

    // NDS 双屏自由布局编辑器（videoScale == "custom" 且 NDS 平台时使用）。
    // 参照 melonDS 官方 Android 布局模型：上屏 / 下屏是两个独立组件，各有
    // 独立归一化矩形 (left, top, right, bottom)，横竖屏分开保存。
    var showNdsCustomLayoutEditor by remember { mutableStateOf(false) }
    var ndsTopRect by remember(isPortrait) {
        mutableStateOf(floatArrayOf(
            if (isPortrait) padLayout.ndsTopLayoutLeftP else padLayout.ndsTopLayoutLeft,
            if (isPortrait) padLayout.ndsTopLayoutTopP else padLayout.ndsTopLayoutTop,
            if (isPortrait) padLayout.ndsTopLayoutRightP else padLayout.ndsTopLayoutRight,
            if (isPortrait) padLayout.ndsTopLayoutBottomP else padLayout.ndsTopLayoutBottom
        ))
    }
    var ndsBottomRect by remember(isPortrait) {
        mutableStateOf(floatArrayOf(
            if (isPortrait) padLayout.ndsBottomLayoutLeftP else padLayout.ndsBottomLayoutLeft,
            if (isPortrait) padLayout.ndsBottomLayoutTopP else padLayout.ndsBottomLayoutTop,
            if (isPortrait) padLayout.ndsBottomLayoutRightP else padLayout.ndsBottomLayoutRight,
            if (isPortrait) padLayout.ndsBottomLayoutBottomP else padLayout.ndsBottomLayoutBottom
        ))
    }

    // === Debounced persistence of PadLayout ===
    // Dragging a button fires onLayoutChange on EVERY pointer move event
    // (60+ times per second). Calling PadLayoutStore.save() on each event
    // serializes the entire layout to SharedPreferences on disk, causing
    // severe jank ("不跟手"). Instead we just update in-memory state here
    // and persist via a debounced LaunchedEffect — it waits 400ms after
    // the last change before writing to disk, so a continuous drag only
    // triggers ONE save at the end.
    LaunchedEffect(padLayout) {
        kotlinx.coroutines.delay(400)
        if (platform == GamePlatform.JAVA && javaGameKey != null) {
            // J2ME 每游戏单独保存：java* 子集写入专属配置（游戏中改的分辨率/
            // 缩放/帧率/触摸/透明度等只影响当前游戏）；全局 prefs 的 java*
            // 字段回写为进入会话前的全局快照，其他 Java 游戏不受影响。
            JavaGameSettingsStore.save(context, javaGameKey, JavaGameSettings.of(padLayout))
            if (!javaHasOverride) javaHasOverride = true
            PadLayoutStore.save(context, padLayout.withJavaSettings(globalJavaSnapshot), platform)
        } else {
            PadLayoutStore.save(context, padLayout, platform)
        }
    }

    // On TV, auto-hide the on-screen pad regardless of the user's setting —
    // the touch overlay is useless without a touchscreen and only wastes GPU.
    val effectiveShowPad = padLayout.showPad && !isTv

    // === NDS 触摸屏：游戏视图在窗口中的几何 ===
    // 虚拟手柄覆盖层（fillMaxSize + pointerInput）是游戏视图的高 z 兄弟节点，
    // Compose 命中测试会在它命中后停止（pointerInput 默认不与兄弟共享事件），
    // 因此手柄可见时 AndroidView（SurfaceView / NdsDualScreenView）收不到任何
    // 触摸事件。修复：由手柄的 pointerInput 把“未命中任何按键”的触摸转发给
    // NDS 触摸屏（见 OnScreenController.onUnhandledTouch）。转发时需要把
    // 根坐标换成游戏视图局部坐标 —— 这里追踪游戏视图的位置和尺寸。
    var gameViewPosInRoot by remember { mutableStateOf(androidx.compose.ui.geometry.Offset.Zero) }
    var gameViewSize by remember { mutableStateOf(androidx.compose.ui.unit.IntSize.Zero) }
    val gameViewTracker = Modifier.onGloballyPositioned { coords ->
        gameViewPosInRoot = coords.positionInRoot()
        gameViewSize = coords.size
    }

    // Apply high-quality scaling flag to the engine whenever it changes.
    // This controls whether the native surface buffer uses source resolution
    // (fast, GPU upscales) or display resolution (sharp, CPU scales).
    LaunchedEffect(padLayout.highQualityScaling) {
        engine.setHighQualityScaling(padLayout.highQualityScaling)
    }

    // Apply core options on load and when they change
    // 注意：videoScale（全局画面缩放）也在触发列表里 —— PS2 的画面比例
    // 在 "auto" 时跟随全局设置推导（见 applyCoreOptions PS2 分支），
    // 游戏中改全局画面缩放必须重新应用，否则 PS2 不会跟随变化。
    LaunchedEffect(padLayout.ntscFilter, padLayout.palette,
                   padLayout.region, padLayout.cropOverscan,
                   padLayout.videoFilter, padLayout.overclocking,
                   padLayout.aspectRatio, padLayout.videoScale,
                   padLayout.sfcReduceSpriteFlicker, padLayout.sfcReduceSlowdown,
                   padLayout.sfcAudioInterpolation, padLayout.sfcGfxTransparency,
                   padLayout.sfcGfxHires, padLayout.sfcUpDownAllowed,
                   padLayout.sfcBlockInvalidVram,
                   padLayout.sfcLayer1, padLayout.sfcLayer2, padLayout.sfcLayer3,
                   padLayout.sfcLayer4, padLayout.sfcLayer5,
                   padLayout.gbcColorPreset, padLayout.gbaColorPreset,
                   padLayout.gbaFrameskipType, padLayout.gbaForceRTC,
                   padLayout.gbaAllowOpposite,
                   // DOSBox-Pure options — trigger applyCoreOptions when changed
                   padLayout.dosMachine, padLayout.dosCycles, padLayout.dosCyclesMax,
                   padLayout.dosSbType, padLayout.dosSbAdlibMode, padLayout.dosSbAdlibEmu,
                   padLayout.dosGus, padLayout.dosMouseInput,
                   padLayout.dosKeyboardLayout,
                   padLayout.dosAutoMapping, padLayout.dosSavestate,
                   padLayout.dosVoodoo, padLayout.dosForce60fps,
                   // DOS 音频/新选项 (全部与预编译核心支持的键一一对应)
                   padLayout.dosAudiorate, padLayout.dosSwapStereo, padLayout.dosTandySound,
                   padLayout.dosCpuCore, padLayout.dosCpuType, padLayout.dosMemorySize,
                   padLayout.dosCgaMode, padLayout.dosAspectCorrection,
                   // MD / Genesis-Plus-GX options
                   padLayout.mdAspect, padLayout.mdRegion, padLayout.mdCdFastboot,
                   padLayout.mdInput, padLayout.mdAllowUpDown, padLayout.mdOverclock,
                   padLayout.mdFrameskip, padLayout.mdSmsFm, padLayout.mdGgStretch,
                   // PCE / Geargrafx options
                   padLayout.pceConsoleType, padLayout.pceAspect, padLayout.pceOverscan,
                   padLayout.pceNoSpriteLimit, padLayout.pcePalette, padLayout.pceCdromBios,
                   padLayout.pceTurbotap, padLayout.pceMb128, padLayout.pceAllowUpDown,
                   // NDS / melonDS options
                   padLayout.ndsUseFwBios, padLayout.ndsConsoleMode, padLayout.ndsScreenLayout, padLayout.ndsResolution,
                   padLayout.ndsFiltering, padLayout.ndsScreensaver, padLayout.ndsTouchMode,
                   padLayout.ndsMouseSpeed, padLayout.ndsDsiSdcard, padLayout.ndsRandomizeMac,
                   padLayout.ndsJitEnable, padLayout.ndsAudioInterpolation, padLayout.ndsUseFwSettings,
                   // 补全：以下选项在 applyCoreOptions 中设置但此前不在触发列表里，
                   // 导致 UI 里改了不生效（GL 硬件加速/JIT 细项/音频等），
                   // 要等重进游戏才被应用。
                   padLayout.ndsOpenGlRenderer, padLayout.ndsOpenGlBetterPolygons,
                   padLayout.ndsOpenGlFiltering,
                   padLayout.ndsJitBlockSize, padLayout.ndsJitFastMemory,
                   padLayout.ndsJitBranchOptimisations, padLayout.ndsJitLiteralOptimisations,
                   padLayout.ndsAudioBitrate, padLayout.ndsMicInput, padLayout.ndsLanguage,
                   padLayout.ndsScreenGap, padLayout.ndsSwapscreenMode, padLayout.ndsHybridSmallScreen,
                   // PSX / PCSX-ReARMed options — keys verified against the
                   // shipped core; wrong-key entries (padNtype/cpu_clock 等) 已移除
                   padLayout.pscxBios, padLayout.pscxRegion, padLayout.pscxFrameskipType,
                   padLayout.pscxFrameskip, padLayout.pscxFrameskipThreshold,
                   padLayout.pscxVibration, padLayout.pscxDithering, padLayout.pscxSpuInterp,
                   padLayout.pscxSpuReverb, padLayout.pscxShowBootlogo, padLayout.pscxCdReadahead,
                   padLayout.pscxMemcard1, padLayout.pscxMemcard2,
                   padLayout.pscxDrc, padLayout.pscxDrcThread, padLayout.pscxClock,
                   padLayout.pscxIcache, padLayout.pscxCdTurbo,
                   padLayout.pscxGpuThreadRendering, padLayout.pscxRgb32,
                   padLayout.pscxScaleHires, padLayout.pscxShowOverscan,
                   padLayout.pscxFractionalFps, padLayout.pscxAltFlip,
                   padLayout.pscxNeonInterlace, padLayout.pscxNeonEnhance,
                   padLayout.pscxCentering,
                   padLayout.pscxCdAudio, padLayout.pscxXaAudio, padLayout.pscxSpuThread,
                   padLayout.pscxPad1Type, padLayout.pscxPad2Type,
                   padLayout.pscxMultitap, padLayout.pscxNegconResponse,
                   padLayout.pscxNegconDeadzone, padLayout.pscxGpuOddEven,
                   padLayout.pscxAnalogAxis,
                   // PS2 / PCEE2 (PCSX2) options
                   padLayout.ps2Renderer, padLayout.ps2ResMulti, padLayout.ps2Bilinear,
                   padLayout.ps2FastBoot, padLayout.ps2HwDownloadMode, padLayout.ps2BlendingAccuracy,
                   padLayout.ps2Trilinear, padLayout.ps2Anisotropic, padLayout.ps2Dithering,
                   padLayout.ps2Mipmapping, padLayout.ps2Deinterlace, padLayout.ps2AspectRatio,
                   padLayout.ps2Widescreen, padLayout.ps2NoInterlace,
                   padLayout.ps2Mtvu, padLayout.ps2InstantVu1,
                   padLayout.ps2EeCycleRate, padLayout.ps2EeCycleSkip, padLayout.ps2Rumble,
                   padLayout.ps2TvShader, padLayout.ps2ShadeBoost,
                   padLayout.ps2HalfPixelOffset, padLayout.ps2TexturePreloading,
                   // J2ME options — 游戏中改设置需即时生效
                   padLayout.javaInputMode, padLayout.javaScaleType, padLayout.javaShowFps,
                   padLayout.javaImmediateMode, padLayout.javaResolution,
                   padLayout.javaScaleRatio, padLayout.javaFpsLimit,
                   padLayout.javaTouchInput, padLayout.javaNumDualDispatch,
                   padLayout.javaButtonKeyMap
                   ) {
        applyCoreOptions(engine, padLayout, platform)
        // Apply video filter (frontend post-processing, not a core option)
        //
        // WORKAROUND for native XBR color bleeding:
        // The native C XBR implementation (Hyllian 5xBR v3.5a in libnescore/
        // libsnescore/libgbacore) produces color bleeding artifacts at hard
        // edges — red/purple/yellow dots appear exposed at font and sprite
        // edges in SFC/GBA games. This is a known issue with the 5xBR
        // algorithm when pixels with high color channel contrast are adjacent.
        //
        // Since we cannot modify the native C code, we map XBR requests to
        // HQ2X (filter=5) for native engine games. HQ2X provides similar
        // edge-smoothing without the color bleeding artifact. J2ME games
        // use the Java-side XBR implementation (J2meBitmapFilter) which has
        // been patched with additional color clamping to suppress bleeding.
        val filterInt = when (padLayout.videoFilter) {
            "scanline" -> 1
            "crt" -> 2
            "dot" -> 3
            "xbr" -> 5      // native XBR(4) → HQ2X(5) to avoid color bleeding
            "hq2x" -> 5
            "hq4x" -> 6
            "xbr_dot" -> 5  // native XBR+dot(7) → HQ2X(5), dot added by FilterOverlay
            "4xbr" -> 6     // native 4XBR(8) → HQ4X(6) to avoid color bleeding
            "4xbr_dot" -> 6 // native 4XBR+dot(9) → HQ4X(6), dot added by FilterOverlay
            "hq4x_dot" -> 10
            else -> 0
        }
        engine.setVideoFilter(filterInt)

        // J2ME games use a separate rendering pipeline (Canvas + GLRenderer).
        // Map native filter codes to J2ME filter modes and apply directly.
        if (platform == GamePlatform.JAVA) {
            val j2meMode = when (padLayout.videoFilter) {
                "scanline" -> 1   // scanline
                "crt"      -> 2   // CRT
                "dot"      -> 3   // dot
                "xbr"      -> 4   // 2xBR
                "4xbr"     -> 5   // 4xBR
                "xbr_dot"  -> 6   // 2xBR+dot
                "4xbr_dot" -> 7   // 4xBR+dot
                "hq4x"     -> 8   // HQ4x
                "hq4x_dot" -> 9   // HQ4x+dot
                else -> 0         // none (hq2x not supported in J2ME)
            }
            javax.microedition.lcdui.Canvas.setJ2meFilterMode(j2meMode)
        }
    }

    BackHandler(enabled = !showMenu && !showLayoutEditor && !showSettings && !showCustomLayoutEditor && !showNdsCustomLayoutEditor) {
        showMenu = true
    }
    BackHandler(enabled = showMenu && !showLayoutEditor && !showSettings) {
        showMenu = false
    }
    BackHandler(enabled = showLayoutEditor) { showLayoutEditor = false }
    BackHandler(enabled = showSettings) { showSettings = false }
    BackHandler(enabled = showCustomLayoutEditor) { showCustomLayoutEditor = false }
    BackHandler(enabled = showNdsCustomLayoutEditor) { showNdsCustomLayoutEditor = false }

    // === 联机对战：把 NetplayController 挂到引擎的 frameHook 上 ===
    // 必须在 LaunchedEffect(game) 之前设置，否则引擎模拟线程的第 0 帧会
    // 没有这个 hook 而按单机模式跑（虽然之后会自动切换，但前 inputDelay 帧
    // 的输入序列可能不一致）。Compose 中 LaunchedEffect 按声明顺序执行，
    // 所以这里放在 LaunchedEffect(game) 之前即可。
    var netplayStatusText by remember { mutableStateOf("") }
    val netplayUiListener = remember {
        object : com.nesstation.app.battle.NetplayController.UiListener {
            override fun onReady(role: String, inputDelay: Int) {
                netplayStatusText = "已连线（$role · 延迟 ${inputDelay}f）"
            }
            override fun onPeerReady(username: String) {
                netplayStatusText = "对手 $username 已就绪"
            }
            override fun onFrameInfo(frame: Long, inputDelay: Int, desyncCount: Int) {
                netplayStatusText = "对战中 · 帧 #$frame · 延迟 ${inputDelay}f · desync $desyncCount"
            }
            override fun onPeerJoined(username: String) {
                netplayStatusText = "对手 $username 已加入"
            }
            override fun onPeerLeft(username: String) {
                netplayStatusText = "对手 $username 已离开"
            }
            override fun onError(message: String) {
                netplayStatusText = "对战错误：$message"
            }
            override fun onDisconnected() {
                netplayStatusText = "与对战服务器断开连接"
            }
            override fun onNetplayLost(reason: String) {
                netplayStatusText = reason
            }
        }
    }
    LaunchedEffect(netplayController) {
        engine.frameHook = netplayController
        netplayController?.addUiListener(netplayUiListener)
    }
    DisposableEffect(netplayController) {
        onDispose {
            // 离开 EmulatorScreen 时移除监听器；BattleMatchScreen 的监听器不受影响
            try { netplayController?.removeUiListener(netplayUiListener) } catch (_: Throwable) {}
        }
    }

    // Load ROM
    LaunchedEffect(game) {
        // Ensure previous game is fully cleaned up before loading a new one.
        // loadRom() internally calls cleanup(), but an explicit unload() here
        // guarantees the audio thread, emulation thread, and native core are
        // fully torn down — preventing stale state when switching games.
        try { engine.unload() } catch (_: Throwable) {}

        // DOSBox-Pure 音频：统一使用核心自带采样率输出（默认行为，无
        // TV 模式特殊处理），由 DosEngine.loadRom 内部自动设置，无需注入。

        var romPath = game.romPath ?: ""
        if (romPath.isEmpty()) {
            errorMsg = "该游戏未关联 ROM 文件"
            return@LaunchedEffect
        }

        // === 直接读取优化：content:// → 外部存储真实路径 ===
        // libretro/ARMSX2 等 native 核心只能通过文件系统路径读 ROM；SAF
        // 选择的 content:// URI 传统上需要先复制进内部目录才能交给核心。
        // App 已申请"所有文件访问"权限，外部存储主卷/次卷的文件可以直接
        // 解析成真实路径交给核心（对大 CHD/ISO 镜像尤为重要，省去复制）。
        // 解析失败（云盘/USB OTG 等 provider）时保持原逻辑走复制兜底。
        if (romPath.startsWith("content://")) {
            try {
                val real = resolveContentUriToRealFile(romPath)
                if (real != null && real.isFile && real.length() > 0L && real.canRead()) {
                    android.util.Log.i("EmulatorScreen",
                        "SafToRealPath: ${real.absolutePath} (${real.length()})")
                    romPath = real.absolutePath
                }
            } catch (_: Throwable) { }
        }

        // Compute a stable per-game save name so that battery-backed SRAM
        // (.srm) files are unique per game even when the ROM is loaded from
        // a content:// URI and copied to a shared temp file (temp_rom.<ext>).
        // We use the game's DB id, sanitized to be filesystem-safe.
        val saveName = game.id.lowercase()
            .replace(Regex("[^a-z0-9._-]"), "_")
            .takeIf { it.isNotBlank() } ?: "game"

        // Dedicated saves directory: <filesDir>/saves/
        // Created here to guarantee it exists before the native core tries
        // to write the .srm file.
        val savesDir = java.io.File(context.filesDir, "saves").apply { mkdirs() }
        // System directory: each core looks for BIOS files in this dir.
        // FBNeo expects BIOS zips (neogeo.zip, pgm.zip, etc.) in <filesDir>/fbneo/.
        // Genesis-Plus-GX expects Mega-CD BIOS zips in <filesDir>/genesis/.
        // Geargrafx expects PCE-CD BIOS files (syscard1/2/3.pce, gexpress.pce)
        // in <filesDir>/pce/.
        // melonDS expects BIOS files (bios7.bin, bios9.bin, firmware.bin) in
        // <filesDir>/nds/.
        // PCSX-ReARMed expects PSX BIOS files (scph1001.bin, psxonpsp660.bin)
        // in <filesDir>/psx/.
        // PCEE2 (PCSX2) expects PS2 BIOS files in <filesDir>/ps2/pcsx2/bios/
        // (e.g. scph10000.bin); the native loader auto-migrates a legacy
        // <filesDir>/ps2/bios/ folder from previous releases on first load.
        // Flycast (DC) builds its own dc/ folder under the system dir:
        // <filesDir>/dc/dc_boot.bin + dc_flash.bin (+ naomi.zip /
        // atomiswave.zip for Naomi / Atomiswave romsets) — the core appends
        // dc/ itself, so we pass filesDir directly (see NesApp.ensureDcBios).
        // Other cores (NES/SNES/GBA/DOS) use the root filesDir.
        val systemDir = when (platform) {
            GamePlatform.ARCADE -> java.io.File(context.filesDir, "fbneo").apply { mkdirs() }.absolutePath
            GamePlatform.MD     -> java.io.File(context.filesDir, "genesis").apply { mkdirs() }.absolutePath
            GamePlatform.PCE    -> java.io.File(context.filesDir, "pce").apply { mkdirs() }.absolutePath
            GamePlatform.NDS    -> java.io.File(context.filesDir, "nds").apply { mkdirs() }.absolutePath
            GamePlatform.PSX    -> java.io.File(context.filesDir, "psx").apply { mkdirs() }.absolutePath
            GamePlatform.PS2    -> java.io.File(context.filesDir, "ps2").apply { mkdirs() }.absolutePath
            // flycast appends dc/ to the system dir → BIOS root = filesDir
            GamePlatform.DC     -> java.io.File(context.filesDir, "dc").apply {
                // 双保险：确保 dc/ 与 dc/data/ 存在（NesApp.ensureDcBios 已建）
                mkdirs()
                java.io.File(this, "data").mkdirs()
            }.parentFile!!.absolutePath
            else                -> context.filesDir.absolutePath
        }
        val filesDir = systemDir  // pass the platform-specific system dir to the core

        // === 全局存档方式解析（所有核心通用，不再是 NDS 独有）===
        // globalSaveMode == "nesstation" :
        //   存档放在 NesStation 统一存档目录 <filesDir>/saves/<gameId>.srm
        //   （NDS 核心是 .sav），文件名 = game.id，content:// URI 复制到
        //   temp_rom.<ext> 时也不会相互覆盖。
        // globalSaveMode == "core_builtin" :
        //   存档放在 ROM 同目录、与 ROM 同名（NDS = 官方 melonDS APK 的
        //   <ROM名>.sav；NES/SFC/GBA/PCE/MD/街机/PSX/DOS = <ROM名>.srm，
        //   与 RetroArch/常见模拟器交换习惯一致）。ROM 目录不可写时自动
        //   回退到应用内部目录。J2ME (JAVA) 平台的存档在核心内部管理，
        //   不参与此切换。
        var romAdjSaveDirPath: String? = null
        var romAdjSaveNameOverride: String? = null
        var romAdjSaveNotice: String? = null
        if (platform != GamePlatform.JAVA && padLayout.globalSaveMode == "core_builtin") {
            val resolved = resolveNdsRomAdjacentSave(context, romPath, savesDir)
            romAdjSaveDirPath = resolved.dir
            romAdjSaveNameOverride = resolved.basename
            romAdjSaveNotice = resolved.notice
        }
        val savesDirPath = romAdjSaveDirPath ?: savesDir.absolutePath

        // Tell the native core to use this stable name for the save file.
        // Must be called BEFORE loadRom() so the name is in effect when
        // retro_load_game() returns and we read the SRAM into SAVE_RAM.
        // For NDS, this name is also propagated to the melonDS libretro core
        // via melonds_set_save_basename_override() so the .sav file is named
        // after game.id (nesstation mode) or the ROM basename (core_builtin
        // mode — same-directory save, compatible with the official melonDS
        // APK). Never pass "" for NDS: an empty override would make melonDS
        // derive the name from info->path, which is "temp_rom" for cached
        // content:// URIs (all games would share one temp_rom.sav).
        val coreSaveName = romAdjSaveNameOverride ?: saveName
        engine.setSaveName(coreSaveName)

        // === Mega-CD BIOS pre-check ===
        // If the user is launching a Mega-CD / SEGA-CD game (.cue/.iso/.chd),
        // verify at least one BIOS file (bios_CD_E.bin / .J.bin / .U.bin) is
        // present in <filesDir>/genesis/. Without a BIOS the genplus core
        // produces a black screen — this pre-check gives the user a clear,
        // actionable error instead of leaving them wondering what went wrong.
        if (platform == GamePlatform.MD) {
            val isCdExt = romPath.endsWith(".cue", ignoreCase = true) ||
                          romPath.endsWith(".iso", ignoreCase = true) ||
                          romPath.endsWith(".chd", ignoreCase = true)
            // game.title may contain "Mega-CD" / "SEGA CD" hint for cue sheets
            // that don't have a CD-specific extension at the romPath level.
            val titleHint = game.title.contains("CD", ignoreCase = true) ||
                            game.title.contains("Mega-CD", ignoreCase = true) ||
                            game.title.contains("SEGA-CD", ignoreCase = true)
            if (isCdExt || titleHint) {
                val genesisDir = java.io.File(context.filesDir, "genesis")

                // === 自动解压 zip 里的 .bin ===
                // 如果有 .zip 但没对应的 .bin，先尝试解压。这避免用户看到
                // "有.zip但无.bin" 提示后还要手动操作。
                val binNames = listOf("bios_CD_E.bin", "bios_CD_J.bin", "bios_CD_U.bin")
                for (binName in binNames) {
                    val binFile = java.io.File(genesisDir, binName)
                    if (binFile.exists() && binFile.length() > 0) continue
                    val zipFile = java.io.File(genesisDir, binName.replace(".bin", ".zip"))
                    if (!zipFile.exists() || zipFile.length() <= 0) continue
                    try {
                        java.util.zip.ZipInputStream(zipFile.inputStream().buffered()).use { zin ->
                            while (true) {
                                val entry = zin.nextEntry ?: break
                                val entryName = entry.name.lowercase()
                                if (entryName.endsWith(".bin") || entryName.endsWith(".rom")) {
                                    binFile.outputStream().buffered().use { out ->
                                        val buf = ByteArray(8192)
                                        while (true) {
                                            val n = zin.read(buf)
                                            if (n <= 0) break
                                            out.write(buf, 0, n)
                                        }
                                    }
                                    break
                                }
                                zin.closeEntry()
                            }
                        }
                    } catch (_: Exception) { /* 忽略，下面 hasBios 检查会兜底 */ }
                }

                val hasBios = listOf("bios_CD_E.bin", "bios_CD_J.bin", "bios_CD_U.bin",
                                     "bios_CD_E.zip", "bios_CD_J.zip", "bios_CD_U.zip")
                    .any { java.io.File(genesisDir, it).exists() }
                if (!hasBios) {
                    errorMsg = "Mega-CD/SEGA-CD 游戏需要 BIOS 文件才能运行（当前未检测到）。\n\n" +
                               "请先到 设置 → MD/SEGA → Mega-CD BIOS 管理，" +
                               "导入 bios_CD_E.bin (欧) 或 bios_CD_J.bin (日) 或 bios_CD_U.bin (美)。\n" +
                               "支持导入 .bin 或 .zip（自动解压）。"
                    return@LaunchedEffect
                }
            }
        }

        // === PCE-CD BIOS pre-check ===
        // If the user is launching a PCE-CD game (.cue/.chd/.iso), verify at
        // least one System Card BIOS file (syscard1/2/3.pce or gexpress.pce)
        // is present in <filesDir>/pce/. Without a BIOS Geargrafx refuses to
        // load CD games — this pre-check gives the user a clear error.
        // NOTE: Geargrafx looks for "gexpress.pce", NOT "gameexpress.pce".
        if (platform == GamePlatform.PCE) {
            val isCdExt = romPath.endsWith(".cue", ignoreCase = true) ||
                          romPath.endsWith(".iso", ignoreCase = true) ||
                          romPath.endsWith(".chd", ignoreCase = true)
            if (isCdExt) {
                val pceDir = java.io.File(context.filesDir, "pce")
                // Only count non-empty BIOS files — a 0-byte placeholder (or
                // a truncated download) passes exists() but fails to boot.
                val hasBios = listOf("syscard1.pce", "syscard2.pce", "syscard3.pce",
                                     "gexpress.pce")
                    .any { val f = java.io.File(pceDir, it); f.exists() && f.length() > 0 }
                if (!hasBios) {
                    // If the user dropped a "gameexpress.pce" (wrong name), tell
                    // them to rename it to gexpress.pce instead of a generic error.
                    val wrongName = java.io.File(pceDir, "gameexpress.pce")
                    errorMsg = if (wrongName.exists() && wrongName.length() > 0) {
                        "检测到 gameexpress.pce，但 Geargrafx 只认 gexpress.pce。\n\n" +
                        "请把文件重命名为 gexpress.pce 后重试。\n" +
                        "PCE-CD 还需要 syscard1.pce / syscard2.pce / syscard3.pce（推荐）。"
                    } else {
                        "PCE-CD 游戏需要 System Card BIOS 文件才能运行（当前未检测到）。\n\n" +
                        "请先到 设置 → PCE → PCE-CD BIOS 管理，" +
                        "导入 syscard1.pce / syscard2.pce / syscard3.pce (推荐) 或 gexpress.pce。\n" +
                        "也可以把 BIOS 文件放入 app/src/main/assets/pce/ 重新打包，启动时自动识别。\n" +
                        "卡带游戏 (.pce/.sgx) 和 HES 音乐文件 (.hes) 不需要 BIOS。"
                    }
                    return@LaunchedEffect
                }
            }
        }

        val romFile = java.io.File(romPath)
        if (platform == GamePlatform.DOS) {
            // === DOS-specific loading ===
            // DOSBox-Pure needs the FULL game folder (the .bat launcher usually
            // references other files: .exe, .dat, .cfg, assets...). When the
            // user imported the game we only stored the launcher URI, so here
            // we must rebuild the folder context by:
            //   1. Walking the SAF tree from the launcher URI's parent folder.
            //   2. Copying every file into <filesDir>/dos_games/<gameId>/.
            //   3. Passing the copied launcher path to the core.
            // Without this, DOSBox-Pure cannot find the executable referenced
            // by the .bat file and falls back to its "Start Menu" with the
            // message "No executable file found" — exactly the bug we're fixing.
            val result = withContext(Dispatchers.IO) {
                loadDosGameFolder(context, romPath, game.id)
            }
            if (result == null) {
                errorMsg = "DOS 游戏加载失败：无法读取文件夹内容"
            } else {
                val ok = engine.loadRom(result, filesDir, savesDirPath) { fpsFrameCounter.incrementAndGet() }
                if (!ok) {
                    val err = engine.lastError()
                    errorMsg = err.ifEmpty { "DOS 游戏加载失败" }
                } else {
                    loaded = true
                }
            }
        } else if (romFile.exists()) {
            // FDS BIOS is auto-extracted from assets by NesApp on startup.
            // If missing, the core will report the error; user can import via Settings.
            //
            // === iNES Header Patching for pirate multicarts (500-in-1 etc.) ===
            // Patch the header in the actual file so FCEUmm sees the correct
            // PRG/CHR size regardless of whether it uses game.data or game.path.
            // This is a Kotlin-side backup for the in-memory patching done in
            // rom_loader.cpp — both layers patch, ensuring the patch always
            // takes effect.
            if (platform == GamePlatform.NES) {
                try {
                    val patchResult = com.nesstation.app.core.storage.InesHeaderPatcher
                        .patchIfNeeded(romFile)
                    android.util.Log.i("EmulatorScreen", "iNES patch: $patchResult")
                } catch (e: Exception) {
                    android.util.Log.w("EmulatorScreen", "iNES patch failed: ${e.message}")
                }
            }
            val ok = if (platform == GamePlatform.JAVA) {
                withContext(Dispatchers.IO) {
                    engine.loadRom(romFile, filesDir, savesDirPath) { fpsFrameCounter.incrementAndGet() }
                }
            } else {
                engine.loadRom(romFile, filesDir, savesDirPath) { fpsFrameCounter.incrementAndGet() }
            }
            if (!ok) {
                val err = engine.lastError()
                errorMsg = err.ifEmpty { "ROM 加载失败" }
            } else {
                loaded = true
            }
        } else if (platform == GamePlatform.MD &&
                   (romPath.endsWith(".cue", ignoreCase = true) ||
                    romPath.endsWith(".iso", ignoreCase = true) ||
                    romPath.endsWith(".chd", ignoreCase = true))) {
            // === Mega-CD via SAF (content://) ===
            // The .cue file references .bin audio tracks by RELATIVE path.
            // Copying only the .cue → temp_rom.cue makes GPGX fail to open
            // the CD tracks (temp_rom.bin doesn't exist next to temp_rom.cue).
            // Core silently returns false → black screen.
            //
            // Copy the whole folder so all .bin tracks are next to the .cue,
            // then pass the copied .cue path to the core.
            val cdFile = withContext(Dispatchers.IO) {
                loadGameFolder(context, romPath, game.id, "md_cd")
            }
            if (cdFile == null) {
                errorMsg = "Mega-CD 加载失败：无法读取文件夹内容（.cue/.bin 音轨）"
            } else {
                val ok = engine.loadRom(cdFile, filesDir, savesDirPath) { fpsFrameCounter.incrementAndGet() }
                if (!ok) {
                    val err = engine.lastError()
                    errorMsg = err.ifEmpty { "Mega-CD 加载失败" }
                } else {
                    loaded = true
                }
            }
        } else if (platform == GamePlatform.PCE &&
                   (romPath.endsWith(".cue", ignoreCase = true) ||
                    romPath.endsWith(".chd", ignoreCase = true) ||
                    romPath.endsWith(".iso", ignoreCase = true))) {
            // === PCE-CD via SAF (content://) ===
            // The .cue file references .bin audio tracks by RELATIVE path.
            // Copying only the .cue (as the single-file branch below would)
            // makes Geargrafx fail to open the CD, and pce_loader.cpp then
            // misreports it as "System Card BIOS missing" — even when the
            // BIOS is present. Copy the whole folder so the .bin tracks
            // are available next to the .cue.
            val cdFile = withContext(Dispatchers.IO) {
                loadGameFolder(context, romPath, game.id, "pce_cd")
            }
            if (cdFile == null) {
                errorMsg = "PCE-CD 加载失败：无法读取文件夹内容（.cue/.bin 音轨）"
            } else {
                val ok = engine.loadRom(cdFile, filesDir, savesDirPath) { fpsFrameCounter.incrementAndGet() }
                if (!ok) {
                    val err = engine.lastError()
                    errorMsg = err.ifEmpty { "PCE-CD 加载失败" }
                } else {
                    loaded = true
                }
            }
        } else if ((platform == GamePlatform.PSX || platform == GamePlatform.PS2) &&
                   (romPath.endsWith(".cue", ignoreCase = true) ||
                    romPath.endsWith(".chd", ignoreCase = true) ||
                    romPath.endsWith(".iso", ignoreCase = true) ||
                    romPath.endsWith(".cso", ignoreCase = true) ||
                    romPath.endsWith(".mdf", ignoreCase = true) ||
                    romPath.endsWith(".mds", ignoreCase = true) ||
                    romPath.endsWith(".ccd", ignoreCase = true))) {
            // === PSX CD image via SAF (content://) ===
            // The .cue file references .bin audio tracks by RELATIVE path.
            // Copying only the .cue (as the single-file branch below would)
            // makes PCSX-ReARMed fail to open the disc — it cannot find the
            // .bin tracks. Copy the whole folder so the .bin tracks are
            // available next to the .cue. Also handle .chd/.iso/.mdf/.mds
            // the same way for consistency (these are single-file images but
            // the folder-copy path is safe and handles edge cases like
            // .mdf+.mds pairs).
            val cdFile = withContext(Dispatchers.IO) {
                loadGameFolder(context, romPath, game.id, "psx_cd")
            }
            if (cdFile == null) {
                errorMsg = if (platform == GamePlatform.PS2) "PS2 加载失败：无法读取文件夹内容（.cue/.bin 音轨）"
                           else "PS1 加载失败：无法读取文件夹内容（.cue/.bin 音轨）"
            } else {
                val ok = engine.loadRom(cdFile, filesDir, savesDirPath) { fpsFrameCounter.incrementAndGet() }
                if (!ok) {
                    val err = engine.lastError()
                    errorMsg = err.ifEmpty { if (platform == GamePlatform.PS2) "PS2 加载失败" else "PS1 加载失败" }
                } else {
                    loaded = true
                }
            }
        } else if (platform == GamePlatform.DC &&
                   (romPath.endsWith(".cue", ignoreCase = true) ||
                    romPath.endsWith(".chd", ignoreCase = true) ||
                    romPath.endsWith(".iso", ignoreCase = true) ||
                    romPath.endsWith(".cdi", ignoreCase = true) ||
                    romPath.endsWith(".gdi", ignoreCase = true) ||
                    romPath.endsWith(".m3u", ignoreCase = true))) {
            // === DC / GD-ROM image via SAF (content://) ===
            // 与 PSX 同理：.cue/.gdi/.m3u 用相对路径引用轨道文件（.bin/.raw
            // 多轨道数据），只拷单个文件会让 flycast 找不到轨道。整目录拷贝。
            // .cdi/.chd/.iso 虽是单文件镜像，同样走目录拷贝保持一致
            // （顺带覆盖 .cdi+子轨道等边角情况）。
            val cdFile = withContext(Dispatchers.IO) {
                loadGameFolder(context, romPath, game.id, "dc_cd")
            }
            if (cdFile == null) {
                errorMsg = "DC 加载失败：无法读取文件夹内容（.gdi/.cue 轨道文件）"
            } else {
                val ok = engine.loadRom(cdFile, filesDir, savesDirPath) { fpsFrameCounter.incrementAndGet() }
                if (!ok) {
                    val err = engine.lastError()
                    errorMsg = err.ifEmpty { "DC 加载失败" }
                } else {
                    loaded = true
                }
            }
        } else {
            try {
                val input = context.contentResolver.openInputStream(android.net.Uri.parse(romPath))
                if (input != null) {
                    // ----------------------------------------------------------------
                    // FBNeo arcade ROMs MUST preserve their original filename —
                    // FBNeo uses the .zip filename (minus extension) as the
                    // MAME-style driver name to identify the ROM set. Copying
                    // kof98h.zip → temp_rom.zip would make FBNeo reject it
                    // with "Romset is unknown". Same applies to .7z archives.
                    //
                    // For arcade we therefore query the SAF for the original
                    // display name (NOT game.title, which may be a localized
                    // user-facing name like "拳皇98"). For other platforms the
                    // filename is irrelevant to the core, so we keep the
                    // legacy temp_rom.<ext> path.
                    // ----------------------------------------------------------------
                    val origName: String = if (platform == GamePlatform.ARCADE || platform == GamePlatform.DC) {
                        // Query SAF for the original filename — this preserves
                        // the driver name (kof98h.zip, mvc.zip, etc.).
                        // DC (Flycast): Naomi / Atomiswave romsets are MAME
                        // zips too — the filename doubles as the game
                        // identifier hint, so it must be preserved like
                        // arcade sets.
                        // NOTE: game.title is the localized Chinese display name
                        // (e.g. "拳皇98 - ...") and is NOT a valid driver name.
                        // If the SAF query fails, we fall back to the URI's last
                        // path segment (URL-decoded), which usually contains the
                        // encoded original filename.
                        var name = ""
                        try {
                            val uri = android.net.Uri.parse(romPath)
                            context.contentResolver.query(
                                uri, null, null, null, null
                            )?.use { c ->
                                val idx = c.getColumnIndex(
                                    android.provider.DocumentsContract.Document.COLUMN_DISPLAY_NAME
                                )
                                if (idx >= 0 && c.moveToFirst()) {
                                    val n = c.getString(idx)
                                    if (!n.isNullOrBlank()) name = n
                                }
                            }
                        } catch (_: Exception) { }
                        if (name.isBlank()) {
                            // Fallback: extract filename from URI last path segment.
                            // SAF URIs look like:
                            //   content://com.android.externalstorage.documents/tree/primary%3AROMs%2Fkof98h.zip
                            // or:
                            //   content://.../document/primary%3AROMs%2Fkof98h.zip
                            // The last path segment, URL-decoded, ends with the original filename.
                            try {
                                val uri = android.net.Uri.parse(romPath)
                                val lastSeg = uri.lastPathSegment
                                if (!lastSeg.isNullOrBlank()) {
                                    name = android.net.Uri.decode(lastSeg)
                                        .substringAfterLast('/')
                                        .substringAfterLast(':')
                                }
                            } catch (_: Exception) { }
                        }
                        name
                    } else {
                        // === FIX: SMS/GG black-screen bug ===
                        // Previously this used `game.title.ifBlank { romPath.substringAfterLast('/') }`
                        // — but `game.title` is the *display name without extension* (e.g. "Sonic"),
                        // NOT the original ROM filename. As a result none of the extension checks
                        // below matched, and the temp file always fell through to the platform
                        // default (`.md` for MD-platform games). GPGX then initialised as Mega Drive
                        // and tried to fall back to SMS via header detection — leaving the VDP in
                        // an inconsistent state and producing a black screen for SMS/GG games.
                        //
                        // Fix: always resolve the actual original filename (with extension):
                        //   - content:// URIs → SAF display name query (same logic as ARCADE)
                        //   - local file paths → File(romPath).name
                        // This ensures an SMS ROM "Sonic.sms" produces temp_rom.sms (not .md),
                        // and a GG ROM "Alex.gg" produces temp_rom.gg, so GPGX detects the
                        // correct system on the first pass.
                        if (romPath.startsWith("content://")) {
                            var name = ""
                            try {
                                val uri = android.net.Uri.parse(romPath)
                                context.contentResolver.query(
                                    uri, null, null, null, null
                                )?.use { c ->
                                    val idx = c.getColumnIndex(
                                        android.provider.DocumentsContract.Document.COLUMN_DISPLAY_NAME
                                    )
                                    if (idx >= 0 && c.moveToFirst()) {
                                        val n = c.getString(idx)
                                        if (!n.isNullOrBlank()) name = n
                                    }
                                }
                            } catch (_: Exception) { }
                            if (name.isBlank()) {
                                try {
                                    val uri = android.net.Uri.parse(romPath)
                                    val lastSeg = uri.lastPathSegment
                                    if (!lastSeg.isNullOrBlank()) {
                                        name = android.net.Uri.decode(lastSeg)
                                            .substringAfterLast('/')
                                            .substringAfterLast(':')
                                    }
                                } catch (_: Exception) { }
                            }
                            name.ifBlank { romPath.substringAfterLast('/') }
                        } else {
                            // Local file path — use the actual filename
                            try {
                                java.io.File(romPath).name
                            } catch (_: Throwable) {
                                romPath.substringAfterLast('/')
                            }
                        }
                    }
                    // Sanitize to filesystem-safe characters (SAF display names
                    // are usually safe, but we want to be defensive).
                    val sanitizedOrigName = origName.replace(Regex("[^A-Za-z0-9._-]"), "_")
                    val tempFileName = when {
                        (platform == GamePlatform.ARCADE || platform == GamePlatform.DC) &&
                            sanitizedOrigName.isNotBlank() -> sanitizedOrigName
                        // PSX single-file formats (.pbp/.ecm/.m3u/.ccd): use game ID
                        // to create unique temp files. Previously all PSX games
                        // shared "temp_rom.pbp" etc., causing the second game
                        // opened to load the first game's data.
                        platform == GamePlatform.PSX -> "psx_${saveName}"
                        // PS2 disc images (.iso/.cso/.chd/.cue/.isz): same
                        // per-game temp file strategy as PSX.
                        platform == GamePlatform.PS2 -> "ps2_${saveName}"
                        else -> "temp_rom"
                    }
                    val ext = when {
                        origName.endsWith(".fds", ignoreCase = true) -> ".fds"
                        origName.endsWith(".unf", ignoreCase = true) || origName.endsWith(".unif", ignoreCase = true) -> ".unf"
                        origName.endsWith(".smc", ignoreCase = true) -> ".smc"
                        origName.endsWith(".sfc", ignoreCase = true) -> ".sfc"
                        origName.endsWith(".swc", ignoreCase = true) -> ".swc"
                        origName.endsWith(".fig", ignoreCase = true) -> ".fig"
                        origName.endsWith(".gbc", ignoreCase = true) -> ".gbc"
                        origName.endsWith(".gba", ignoreCase = true) -> ".gba"
                        origName.endsWith(".gb", ignoreCase = true) -> ".gb"
                        origName.endsWith(".sgb", ignoreCase = true) -> ".sgb"
                        // SEGA MD / SMS / GG / SG extensions
                        origName.endsWith(".md", ignoreCase = true) -> ".md"
                        origName.endsWith(".smd", ignoreCase = true) -> ".smd"
                        origName.endsWith(".gen", ignoreCase = true) -> ".gen"
                        origName.endsWith(".sms", ignoreCase = true) -> ".sms"
                        origName.endsWith(".gg", ignoreCase = true) -> ".gg"
                        origName.endsWith(".sg", ignoreCase = true) -> ".sg"
                        origName.endsWith(".68k", ignoreCase = true) -> ".68k"
                        origName.endsWith(".bin", ignoreCase = true) -> ".bin"
                        origName.endsWith(".cue", ignoreCase = true) -> ".cue"
                        origName.endsWith(".chd", ignoreCase = true) -> ".chd"
                        // Nintendo DS (melonDS) extensions
                        origName.endsWith(".nds", ignoreCase = true) -> ".nds"
                        origName.endsWith(".app", ignoreCase = true) -> ".app"
                        origName.endsWith(".ids", ignoreCase = true) -> ".ids"
                        origName.endsWith(".srl", ignoreCase = true) -> ".srl"
                        origName.endsWith(".dsi", ignoreCase = true) -> ".dsi"
                        // PlayStation 1 (PCSX-ReARMed) extensions
                        origName.endsWith(".pbp", ignoreCase = true) -> ".pbp"
                        origName.endsWith(".m3u", ignoreCase = true) -> ".m3u"
                        origName.endsWith(".ecm", ignoreCase = true) -> ".ecm"
                        origName.endsWith(".mdf", ignoreCase = true) -> ".mdf"
                        origName.endsWith(".mds", ignoreCase = true) -> ".mds"
                        origName.endsWith(".ccd", ignoreCase = true) -> ".ccd"
                        origName.endsWith(".iso", ignoreCase = true) -> ".iso"
                        // PC-Engine / TurboGrafx-16 / SuperGrafx / PCE-CD extensions
                        origName.endsWith(".pce", ignoreCase = true) -> ".pce"
                        origName.endsWith(".sgx", ignoreCase = true) -> ".sgx"
                        origName.endsWith(".hes", ignoreCase = true) -> ".hes"
                        // Dreamcast / Naomi / Atomiswave (Flycast) extensions
                        origName.endsWith(".cdi", ignoreCase = true) -> ".cdi"
                        origName.endsWith(".gdi", ignoreCase = true) -> ".gdi"
                        origName.endsWith(".lst", ignoreCase = true) -> ".lst"
                        origName.endsWith(".dat", ignoreCase = true) -> ".dat"
                        // Arcade: FBNeo loads .zip / .7z archives
                        origName.endsWith(".zip", ignoreCase = true) -> ".zip"
                        origName.endsWith(".7z", ignoreCase = true) -> ".7z"
                        romPath.contains(".fds", ignoreCase = true) -> ".fds"
                        romPath.contains(".unf", ignoreCase = true) -> ".unf"
                        romPath.contains(".sfc", ignoreCase = true) -> ".sfc"
                        romPath.contains(".smc", ignoreCase = true) -> ".smc"
                        romPath.contains(".gba", ignoreCase = true) -> ".gba"
                        romPath.contains(".gbc", ignoreCase = true) -> ".gbc"
                        romPath.contains(".gb", ignoreCase = true) -> ".gb"
                        // Default extension based on platform
                        platform == GamePlatform.ARCADE -> ".zip"
                        platform == GamePlatform.DC -> ".chd"
                        platform == GamePlatform.MD -> ".md"
                        platform == GamePlatform.PCE -> ".pce"
                        else -> ".nes"
                    }
                    // For arcade / DC archives, tempFileName already includes
                    // the extension (sanitizedOrigName keeps the original
                    // .zip/.7z suffix). For other platforms, append ext.
                    val tempFile = if ((platform == GamePlatform.ARCADE || platform == GamePlatform.DC) &&
                                       sanitizedOrigName.endsWith(ext, ignoreCase = true)) {
                        java.io.File(context.cacheDir, tempFileName)
                    } else {
                        java.io.File(context.cacheDir, "$tempFileName$ext")
                    }
                    withContext(Dispatchers.IO) {
                        tempFile.outputStream().use { out -> input.copyTo(out) }
                    }
                    input.close()
                    // === iNES Header Patching for pirate multicarts (500-in-1) ===
                    // Patch the temp file's iNES header so FCEUmm loads the
                    // full PRG ROM (the header often claims 1MB but the file
                    // is 16MB+). Without this, FCEUmm truncates to the header
                    // size and the multicart menu can't switch banks → gray
                    // screen.
                    if (platform == GamePlatform.NES) {
                        try {
                            val patchResult = com.nesstation.app.core.storage.InesHeaderPatcher
                                .patchIfNeeded(tempFile)
                            android.util.Log.i("EmulatorScreen", "iNES patch: $patchResult")
                        } catch (e: Exception) {
                            android.util.Log.w("EmulatorScreen", "iNES patch failed: ${e.message}")
                        }
                    }
                    val ok = if (platform == GamePlatform.JAVA) {
                        withContext(Dispatchers.IO) {
                            engine.loadRom(tempFile, filesDir, savesDirPath) { fpsFrameCounter.incrementAndGet() }
                        }
                    } else {
                        engine.loadRom(tempFile, filesDir, savesDirPath) { fpsFrameCounter.incrementAndGet() }
                    }
                    if (!ok) {
                        val err = engine.lastError()
                        errorMsg = err.ifEmpty { "ROM 加载失败" }
                    } else {
                        loaded = true
                    }
                } else {
                    errorMsg = "无法读取ROM文件: $romPath"
                }
            } catch (e: Exception) {
                errorMsg = "ROM 加载失败: ${e.message}"
            }
        }

        // 同目录存档回退提示（core_builtin 模式且 ROM 目录不可访问时）
        if (loaded) {
            romAdjSaveNotice?.let {
                Toast.makeText(context, it, Toast.LENGTH_LONG).show()
            }
        }

        // === J2ME：loadRom 完成后重放一次核心设置 ===
        // J2meEngine.loadRom() 内部会调用 MicroLoader.applyConfiguration()，
        // 用每游戏 config.json 里的值覆盖 Canvas 的缩放/FPS/即时渲染/分辨率
        // 等设置（组合时先于 loadRom 运行的那次 applyCoreOptions 会被整个
        // 覆盖掉）。加载成功后重新应用 GameBox 的 J2ME 设置，保证
        // “游戏内设置 / 全局 Java 设置”真正生效且优先级高于旧配置文件。
        if (loaded && platform == GamePlatform.JAVA) {
            applyCoreOptions(engine, padLayout, platform)
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            // 联机对战：先卸下 hook 再 stop controller，避免引擎线程在
            // hook 已经销毁后还回调到 NetplayController。
            try { engine.frameHook = null } catch (_: Throwable) {}
            try { netplayController?.stop() } catch (_: Throwable) {}
            engine.unload()
        }
    }

    LaunchedEffect(fastForwardSpeed) { engine.setFastForward(fastForwardSpeed) }
    LaunchedEffect(running) { engine.setPaused(!running) }

    // 当前核心的遮罩 / 按钮主题（每核心独立；未配置时用默认纯黑背景）。
    // GBA 回退：设置页里 mGBA 核心只有「GB / GBA」一个入口（写入 "GB" 键），
    // 没有单独配置 GBA 的 UI；当 "GBA" 键完全未自定义时回退读 "GB" 键，
    // 否则 GBA 游戏永远拿不到用户在设置页做的遮罩 / 按键自定义
    //（GB/GBC 游戏直接读 "GB" 键所以正常，GBA 读自己的空键所以失效）。
    val overlayTheme = run {
        val own = com.nesstation.app.core.storage.overlayThemeGet(padLayout.overlayThemeJson, platform)
        if (platform == GamePlatform.GBA && own.isDefault) {
            com.nesstation.app.core.storage.overlayThemeGet(padLayout.overlayThemeJson, GamePlatform.GB)
        } else {
            own
        }
    }
    // 自定义背景图片（覆盖在背景色之上、游戏画面之下）
    val themeBgImage = remember(overlayTheme.bgImageUri) {
        overlayTheme.bgImageUri?.let { FsdImaging.decodeUri(context, it, 1280, 800) }
    }

    Box(modifier = Modifier.fillMaxSize().background(overlayTheme.bgColor?.let { Color(it) } ?: Color.Black)) {
        if (themeBgImage != null) {
            Image(
                bitmap = themeBgImage.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        }
        if (loaded) {
            if (platform == GamePlatform.JAVA && engine is J2meEngine) {
                // === J2ME 游戏窗口同样接收全局「画面缩放」(videoScale) ===
                // 与 GameSurfaceView 一致的形状约束：stretch=铺满（旧行为）、
                // 4:3/2:3/3:2/8:7/16:9=按比例约束窗口、custom=四角自定义矩形。
                // J2ME Canvas 内部的 javaScaleType（适应/拉伸/原始分辨率）继续
                // 在窗口内生效，两者叠加：窗口形状由 videoScale 决定，窗口内
                // 画面适配由 javaScaleType 决定。
                val j2meCustom = padLayout.videoScale == "custom"
                val j2meContainerAlignment = when {
                    j2meCustom -> Alignment.TopStart
                    isPortrait -> Alignment.TopCenter
                    else -> Alignment.Center
                }
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .onSizeChanged { surfaceSize = it }
                        .then(gameViewTracker),
                    contentAlignment = j2meContainerAlignment
                ) {
                    val j2meScaleModifier = when (padLayout.videoScale) {
                        "4:3" -> Modifier.aspectRatio(4f / 3f)
                        "2:3" -> Modifier.aspectRatio(2f / 3f)
                        "3:2" -> Modifier.aspectRatio(3f / 2f)
                        "8:7" -> Modifier.aspectRatio(8f / 7f)
                        "16:9" -> Modifier.aspectRatio(16f / 9f)
                        "custom" -> {
                            val maxW = surfaceSize.width.coerceAtLeast(1)
                            val maxH = surfaceSize.height.coerceAtLeast(1)
                            val leftPx = (customRect[0] * maxW).toInt().coerceIn(0, maxW)
                            val topPx = (customRect[1] * maxH).toInt().coerceIn(0, maxH)
                            val wPx = ((customRect[2] - customRect[0]) * maxW).toInt().coerceIn(1, maxW)
                            val hPx = ((customRect[3] - customRect[1]) * maxH).toInt().coerceIn(1, maxH)
                            val density = LocalDensity.current
                            Modifier
                                .offset { IntOffset(leftPx, topPx) }
                                .size(width = with(density) { wPx.toDp() }, height = with(density) { hPx.toDp() })
                        }
                        else -> Modifier.fillMaxSize() // stretch (default)
                    }
                    J2meGameView(
                        engine = engine,
                        modifier = Modifier.then(j2meScaleModifier).onGloballyPositioned { coords ->
                            // 追踪 J2ME 游戏视图（AndroidView）在窗口中的位置：
                            // 手柄覆盖层转发的根坐标减去它得到视图局部坐标，
                            // 再注入 Canvas 触屏事件（触屏支持修复的关键链路）。
                            j2meViewPosInRoot = coords.positionInRoot()
                        }
                    )
                }
            } else {
                GameSurfaceView(
                    engine = engine,
                    videoScale = padLayout.videoScale,
                    videoFilter = padLayout.videoFilter,
                    isPortrait = isPortrait,
                    platform = platform,
                    currentPlayer = currentPlayer,
                    gamepadBitsHolder = gamepadBitsHolder,
                    uiBlocked = showMenu || showLayoutEditor || showSettings || showCustomLayoutEditor ||
                                showNdsCustomLayoutEditor || showSlotPicker != null || showFFSpeedPicker,
                    onMenuToggle = { showMenu = !showMenu },
                    customRect = customRect,
                    netplayController = netplayController,
                    ndsScreenLayout = padLayout.ndsScreenLayout,
                    ndsScreenGapPx = padLayout.ndsScreenGap.toIntOrNull()?.coerceIn(0, 20) ?: 0,
                    ndsOpenGl = padLayout.ndsOpenGlRenderer == "enabled",
                    ndsTopRect = ndsTopRect,
                    ndsBottomRect = ndsBottomRect,
                    gameViewTracker = gameViewTracker,
                    modifier = Modifier
                        .fillMaxSize()
                        .onSizeChanged { surfaceSize = it }
                )
            }

            // === 联机对战状态条（顶部居中） ===
            // 显示帧号 / 延迟 / desync 计数 / 对手加入离开等提示。
            // 单机模式下不显示。
            if (netplayController != null && netplayStatusText.isNotBlank()) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .statusBarsPadding()
                        .padding(top = 6.dp, start = 8.dp, end = 8.dp)
                        .background(
                            Color(0xAA0E1626),
                            androidx.compose.foundation.shape.RoundedCornerShape(10.dp)
                        )
                        .padding(horizontal = 10.dp, vertical = 4.dp)
                ) {
                    Text(
                        text = netplayStatusText,
                        color = Color.White,
                        fontSize = 10.sp,
                        maxLines = 1
                    )
                }
            }

            // === 画面遮罩（主题可配置，按核心独立存储） ===
            // 覆盖在游戏画面之上的半透明层：降亮度 / 减眩光 / 沉浸。
            // z 顺序在游戏视图之上、手柄覆盖层之下，不影响按键可读性。
            if (overlayTheme.maskEnabled) {
                val maskImage = remember(overlayTheme.maskImageUri) {
                    overlayTheme.maskImageUri?.let { FsdImaging.decodeUri(context, it, 1280, 800) }
                }
                if (maskImage != null) {
                    Image(
                        bitmap = maskImage.asImageBitmap(),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .fillMaxSize()
                            .alpha((overlayTheme.maskAlpha / 255f).coerceIn(0f, 1f))
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(
                                Color(overlayTheme.maskColor).copy(
                                    alpha = (overlayTheme.maskAlpha / 255f).coerceIn(0f, 1f)
                                )
                            )
                    )
                }
            }
        } else {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp),
                contentAlignment = Alignment.Center
            ) {
                if (errorMsg != null && errorMsg!!.length > 60) {
                    // Multi-line error (e.g. FBNeo ROM missing files) — show in
                    // a scrollable panel so the user can read the full message.
                    Column(
                        modifier = Modifier
                            .fillMaxWidth(0.92f)
                            .verticalScroll(rememberScrollState())
                            .background(Color(0xDD1E2A3A), androidx.compose.foundation.shape.RoundedCornerShape(12.dp))
                            .padding(16.dp)
                    ) {
                        Text(
                            text = "ROM 加载失败",
                            color = Color(0xFFFF6B6B),
                            fontSize = 15.sp,
                            fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
                        )
                        Spacer(Modifier.size(8.dp))
                        Text(
                            text = errorMsg!!,
                            color = Color(0xFFE0E0E0),
                            fontSize = 11.sp,
                            lineHeight = 15.sp
                        )
                        if (platform == GamePlatform.ARCADE) {
                            Spacer(Modifier.size(10.dp))
                            Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(Color(0x33FFFFFF)))
                            Spacer(Modifier.size(8.dp))
                            Text(
                                text = "提示：街机游戏常见问题请查看 设置 → 街机 → ROM 兼容性帮助",
                                color = Color(0xFFFFD66B),
                                fontSize = 11.sp,
                                lineHeight = 15.sp
                            )
                        }
                    }
                } else {
                    Text(
                        text = errorMsg ?: "正在加载…",
                        color = Color.White,
                        fontSize = 16.sp
                    )
                }
            }
        }

        // On-screen controller with multi-touch — hidden on TV (no touchscreen)
        if (loaded && effectiveShowPad && !showMenu && !showLayoutEditor && !showSettings && !showCustomLayoutEditor && surfaceSize != IntSize.Zero) {
            if (platform == GamePlatform.DOS && engine is com.nesstation.app.core.engine.DosEngine) {
                // DOS uses a dedicated overlay with two modes (gamepad / keyboard)
                // and full keyboard + mouse support. The mode toggle is handled
                // by flipping padLayout.dosInputMode and persisting it.
                DosOnScreenController(
                    engine = engine,
                    padLayout = padLayout,
                    surfaceSize = surfaceSize,
                    isPortrait = isPortrait,
                    overlayTheme = overlayTheme,
                    onToggleMode = {
                        val newMode = if (padLayout.dosInputMode == "gamepad") "keyboard" else "gamepad"
                        val newLayout = padLayout.copy {dosInputMode = newMode}
                        padLayout = newLayout
                        // Persisted by the debounced LaunchedEffect above.
                    }
                )
            } else if (platform == GamePlatform.JAVA && engine is com.nesstation.app.core.engine.J2meEngine) {
                // J2ME uses a dedicated overlay with two modes (gamepad / phone).
                // Gamepad: D-pad + A/B/X/Y/Start/Select.
                // Phone: 12-key numeric keypad + soft keys + FIRE.
                J2meOnScreenController(
                    engine = engine,
                    padLayout = padLayout,
                    surfaceSize = surfaceSize,
                    isPortrait = isPortrait,
                    overlayTheme = overlayTheme,
                    onToggleMode = {
                        val newMode = if (padLayout.javaInputMode == "gamepad") "phone" else "gamepad"
                        val newLayout = padLayout.copy { javaInputMode = newMode }
                        padLayout = newLayout
                    },
                    // 触屏支持修复：手柄覆盖层是游戏视图的高 z 兄弟节点，
                    // Compose 命中测试不会穿透 —— 覆盖层把"未命中任何按键"的
                    // 游戏区域触摸转发到这里，换算成视图局部坐标后注入 Canvas，
                    // J2ME 触屏版游戏（pointerPressed/Dragged/Released）才能收到触摸。
                    onUnhandledTouch = { rootPos, action, pid ->
                        val localX = rootPos.x - j2meViewPosInRoot.x
                        val localY = rootPos.y - j2meViewPosInRoot.y
                        // GameBox 诊断日志：覆盖层 forward 日志与 Canvas
                        // postTouchAction 日志之间唯一没有日志的环节。若这里
                        // 打印而 Canvas 侧没有 postTouchAction，说明事件在
                        // J2meEngine.postTouch 或坐标换算被丢弃。
                        android.util.Log.d(
                            "J2meTouch",
                            "emulatorScreen action=$action pid=$pid root=(${rootPos.x},${rootPos.y})" +
                                " viewPos=(${j2meViewPosInRoot.x},${j2meViewPosInRoot.y})" +
                                " local=($localX,$localY)"
                        )
                        engine.postTouch(action, pid, localX, localY)
                    }
                )
            } else {
                OnScreenController(
                    padLayout = padLayout,
                    surfaceSize = surfaceSize,
                    platform = platform,
                    isPortrait = isPortrait,
                    // NDS 触摸屏转发：手柄覆盖层可见时，Compose 命中测试不会
                    // 继续到下层的 AndroidView（游戏视图），所以未命中任何
                    // 按键的触摸从这里转发给 NDS 下屏。rootPos 为根坐标，
                    // 换算成游戏视图局部坐标后走与 onTouch 相同的映射。
                    onUnhandledTouch = if (platform == GamePlatform.NDS) {
                        { rootPos, action ->
                            val ndsEngine = engine as? com.nesstation.app.core.engine.NdsEngine
                            if (ndsEngine != null) {
                                if (action == android.view.MotionEvent.ACTION_UP ||
                                    action == android.view.MotionEvent.ACTION_CANCEL) {
                                    // 释放（两条路径都清，与 handleNdsTouch 一致）
                                    ndsEngine.setTouchInput(0, 0, false)
                                    ndsEngine.setTouchInputDirect(0, 0, false)
                                } else {
                                    if (padLayout.videoScale == "custom") {
                                        // 自由布局：NdsDualScreenView 是 fillMaxSize，
                                        // 根坐标减去视图位置即视图局部坐标。
                                        val vw = gameViewSize.width.coerceAtLeast(1)
                                        val vh = gameViewSize.height.coerceAtLeast(1)
                                        val lx = rootPos.x - gameViewPosInRoot.x
                                        val ly = rootPos.y - gameViewPosInRoot.y
                                        if (lx >= 0 && ly >= 0 && lx < vw && ly < vh) {
                                            // 触点在视图内 → 判断是否落在下屏矩形
                                            val bottomDst = androidx.compose.ui.geometry.Rect(
                                                gameViewPosInRoot.x + ndsBottomRect[0] * vw,
                                                gameViewPosInRoot.y + ndsBottomRect[1] * vh,
                                                gameViewPosInRoot.x + ndsBottomRect[2] * vw,
                                                gameViewPosInRoot.y + ndsBottomRect[3] * vh
                                            )
                                            if (bottomDst.contains(rootPos)) {
                                                val t = ((rootPos.x - bottomDst.left) / bottomDst.width).coerceIn(0f, 1f)
                                                val s = ((rootPos.y - bottomDst.top) / bottomDst.height).coerceIn(0f, 1f)
                                                val px = (t * 255.5f).toInt().coerceIn(0, 255)
                                                val py = (s * 191.5f).toInt().coerceIn(0, 191)
                                                ndsEngine.setTouchInputDirect(px, py, true)
                                            } else {
                                                // 触在上屏/视图空白区 → 释放
                                                ndsEngine.setTouchInputDirect(0, 0, false)
                                            }
                                        } else {
                                            ndsEngine.setTouchInputDirect(0, 0, false)
                                        }
                                    } else {
                                        // 标准布局：SurfaceView 可能被 aspectRatio
                                        // 裁剪（信箱式留边），换算成视图局部归一化坐标
                                        val vw = gameViewSize.width.coerceAtLeast(1)
                                        val vh = gameViewSize.height.coerceAtLeast(1)
                                        val lx = rootPos.x - gameViewPosInRoot.x
                                        val ly = rootPos.y - gameViewPosInRoot.y
                                        if (lx >= 0 && ly >= 0 && lx < vw && ly < vh) {
                                            mapNormalizedToDsTouch(
                                                ndsEngine,
                                                lx / vw,
                                                ly / vh,
                                                padLayout.ndsScreenLayout,
                                                padLayout.ndsScreenGap.toIntOrNull()?.coerceIn(0, 20) ?: 0,
                                                padLayout.ndsOpenGlRenderer == "enabled"
                                            )
                                        } else {
                                            // 触点在游戏视图之外（留边区域）→ 释放
                                            ndsEngine.setTouchInputDirect(0, 0, false)
                                        }
                                    }
                                }
                            }
                        }
                    } else null,
                    onPadBits = { bits ->
                        // 联机对战：把本地输入送给 NetplayController，由它打包
                        // 发给对方；引擎线程在 beforeFrame 里会把 (local, remote)
                        // 推回 setPad1/setPad2。本地路径：直接 routePadBits。
                        if (netplayController != null) {
                            netplayController.setLocalPad(bits)
                        } else {
                            routePadBits(engine, currentPlayer, bits, platform = platform)
                        }
                    },
                    // PS2 / DC：把归一化拇指位置转成 int16 libretro 轴值
                    // 直接推给核心（数字位走上面的 onPadBits 链路）。
                    // PS2 用双摇杆（PCEE2 原生模拟轴）；DC 用左摇杆
                    //（flycast 的 DC 摇杆必须走 RETRO_DEVICE_ANALOG 轴）。
                    onAnalogAxes = if (platform == GamePlatform.PS2) {
                        { lx, ly, rx, ry ->
                            (engine as? com.nesstation.app.core.engine.Psx2Engine)?.setAnalogAxes(
                                (lx * 32767).toInt(),
                                (ly * 32767).toInt(),
                                (rx * 32767).toInt(),
                                (ry * 32767).toInt()
                            )
                        }
                    } else if (platform == GamePlatform.DC) {
                        { lx, ly, rx, ry ->
                            (engine as? com.nesstation.app.core.engine.FlycastEngine)?.setAnalogAxes(
                                lx, ly, rx, ry
                            )
                        }
                    } else null,
                    // 即时存档 / 即时读档：直接操作当前槽位 saveLoadSlot，
                    // 复用与 SlotPickerDialog 相同的引擎链路与 Toast 反馈。
                    onQuickSave = {
                        val savesDirS = java.io.File(context.filesDir, "saves").apply { mkdirs() }
                        val slot = saveLoadSlot
                        val stateFile = java.io.File(savesDirS, "${game.id}_slot${slot}.state")
                        try {
                            val ok = engine.saveState(slot, stateFile)
                            if (ok) {
                                Toast.makeText(context, "已快速存档 [槽位 $slot]", Toast.LENGTH_SHORT).show()
                            } else {
                                Toast.makeText(context, "存档失败：核心未能生成即时存档", Toast.LENGTH_SHORT).show()
                            }
                        } catch (e: Exception) {
                            Toast.makeText(context, "存档失败: ${e.message}", Toast.LENGTH_SHORT).show()
                        }
                    },
                    onQuickLoad = {
                        val savesDirS = java.io.File(context.filesDir, "saves").apply { mkdirs() }
                        val slot = saveLoadSlot
                        val stateFile = java.io.File(savesDirS, "${game.id}_slot${slot}.state")
                        if (stateFile.exists()) {
                            try {
                                val ok = engine.loadState(slot, stateFile)
                                if (ok) {
                                    Toast.makeText(context, "已读取存档 [槽位 $slot]", Toast.LENGTH_SHORT).show()
                                } else {
                                    Toast.makeText(context, "读档失败：存档损坏或版本不兼容", Toast.LENGTH_SHORT).show()
                                }
                            } catch (e: Exception) {
                                Toast.makeText(context, "读档失败: ${e.message}", Toast.LENGTH_SHORT).show()
                            }
                        } else {
                            Toast.makeText(context, "当前槽位 ($slot) 无存档", Toast.LENGTH_SHORT).show()
                        }
                    },
                    overlayTheme = overlayTheme
                )
            }
        }

        // 玩家切换悬浮球 —— 独立于虚拟手柄的显示开关（手柄隐藏时仍可切换玩家）。
        // 小圆形、可拖动（松手后位置持久化）、可在设置里隐藏。
        // 联机对战时不显示：2P 由远端玩家控制，本地只能操作 1P。
        if (loaded && maxPlayers > 1 && netplayController == null &&
            padLayout.showPlayerSwitch && !showMenu && !showLayoutEditor && !showSettings &&
            !showCustomLayoutEditor && !showNdsCustomLayoutEditor &&
            surfaceSize != IntSize.Zero) {
            PlayerSwitchButton(
                currentPlayer = currentPlayer,
                surfaceSize = surfaceSize,
                initialX = padLayout.playerSwitchX,
                initialY = padLayout.playerSwitchY,
                onSwitch = {
                    currentPlayer = (currentPlayer + 1) % maxPlayers
                },
                onMove = { nx, ny ->
                    // 位置变化写回 padLayout，由防抖 LaunchedEffect 持久化
                    if (padLayout.playerSwitchX != nx || padLayout.playerSwitchY != ny) {
                        padLayout = padLayout.copy {
                            playerSwitchX = nx
                            playerSwitchY = ny
                        }
                    }
                }
            )
        }

        // 全局 FPS 悬浮显示 —— 左上角小字，实时显示模拟帧率
        if (loaded && padLayout.showFps && !showMenu && !showLayoutEditor && !showSettings &&
            !showCustomLayoutEditor && !showNdsCustomLayoutEditor) {
            Text(
                text = "FPS $fpsDisplay",
                color = if (fpsDisplay >= 55) Color(0xFF00E676)
                        else if (fpsDisplay >= 40) Color(0xFFFFD54F)
                        else Color(0xFFFF5252),
                fontSize = 11.sp,
                fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(8.dp)
                    .background(Color(0x66000000), RoundedCornerShape(6.dp))
                    .padding(horizontal = 6.dp, vertical = 2.dp)
            )
        }

        if (loaded && showMenu && !showLayoutEditor && !showSettings) {
            if (platform == GamePlatform.JAVA && engine is com.nesstation.app.core.engine.J2meEngine) {
                J2meMenuOverlay(
                    gameTitle = game.title,
                    running = running,
                    isPhoneMode = padLayout.javaInputMode == "phone",
                    isPortrait = isPortrait,
                    onTogglePause = { running = !running },
                    onToggleOverlayMode = {
                        val newMode = if (padLayout.javaInputMode == "gamepad") "phone" else "gamepad"
                        padLayout = padLayout.copy { javaInputMode = newMode }
                    },
                    onLayoutEditor = { showLayoutEditor = true },
                    onSettings = { showSettings = true },
                    onClose = { showMenu = false },
                    onExit = { onExit() }
                )
            } else {
                MenuOverlay(
                    gameTitle = game.title,
                    running = running,
                    fastForwardSpeed = fastForwardSpeed,
                    isPortrait = isPortrait,
                    onTogglePause = { running = !running },
                    onToggleFastForward = {
                        if (fastForwardSpeed > 0) fastForwardSpeed = 0
                        else fastForwardSpeed = lastNonZeroFFSpeed
                    },
                    onCycleFFSpeed = { showFFSpeedPicker = true },
                    onScreenshot = {
                        val capture = engine.captureFrame()
                        if (capture != null) {
                            try {
                                val bitmap = Bitmap.createBitmap(
                                    capture.pixels, capture.width, capture.height, Bitmap.Config.ARGB_8888
                                )
                                val screenshotsDir = java.io.File(
                                    context.getExternalFilesDir(Environment.DIRECTORY_PICTURES)
                                        ?: context.filesDir,
                                    "screenshots"
                                )
                                screenshotsDir.mkdirs()
                                val timestamp = java.text.SimpleDateFormat(
                                    "yyyyMMdd_HHmmss", java.util.Locale.getDefault()
                                ).format(java.util.Date())
                                val safeTitle = game.title.replace(Regex("[^a-zA-Z0-9_-]"), "_")
                                val file = java.io.File(screenshotsDir, "${safeTitle}_${timestamp}.png")
                                file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                                Toast.makeText(context, "截图已保存: ${file.name}", Toast.LENGTH_SHORT).show()
                            } catch (e: Exception) {
                                Toast.makeText(context, "截图失败: ${e.message}", Toast.LENGTH_SHORT).show()
                            }
                        } else {
                            Toast.makeText(context, "截图失败：无画面数据", Toast.LENGTH_SHORT).show()
                        }
                    },
                    onSaveState = { showSlotPicker = "save" },
                    onLoadState = { showSlotPicker = "load" },
                    onReset = {
                        resetScope.launch {
                            withContext(Dispatchers.IO) {
                                engine.reset(hard = false)
                            }
                            Toast.makeText(context, "已重置", Toast.LENGTH_SHORT).show()
                        }
                    },
                    onLayoutEditor = { showLayoutEditor = true },
                    onSettings = { showSettings = true },
                    onClose = { showMenu = false },
                    onExit = { onExit() }
                )
            }
        }

        // State slot picker dialog
        if (showSlotPicker != null) {
            val savesDir = java.io.File(context.filesDir, "saves").apply { mkdirs() }
            SlotPickerDialog(
                mode = showSlotPicker!!,
                currentSlot = saveLoadSlot,
                gameId = game.id,
                savesDir = savesDir,
                gameTitle = game.title,
                onSlotSelected = { slot ->
                    val stateFile = java.io.File(savesDir, "${game.id}_slot${slot}.state")
                    if (showSlotPicker == "save") {
                        try {
                            // native 链路会校验 serialize 结果与文件写入字节数，
                            // 失败时返回 false（不再产生 35B 假档还提示成功）。
                            val ok = engine.saveState(slot, stateFile)
                            if (ok) {
                                Toast.makeText(context, "存档已保存 [槽位 $slot]", Toast.LENGTH_SHORT).show()
                            } else {
                                Toast.makeText(context, "存档失败：核心未能生成即时存档", Toast.LENGTH_SHORT).show()
                            }
                        } catch (e: Exception) {
                            Toast.makeText(context, "存档失败: ${e.message}", Toast.LENGTH_SHORT).show()
                        }
                    } else {
                        if (stateFile.exists()) {
                            try {
                                val ok = engine.loadState(slot, stateFile)
                                if (ok) {
                                    Toast.makeText(context, "存档已读取 [槽位 $slot]", Toast.LENGTH_SHORT).show()
                                } else {
                                    Toast.makeText(context, "读档失败：存档损坏或版本不兼容，请重新存档", Toast.LENGTH_SHORT).show()
                                }
                            } catch (e: Exception) {
                                Toast.makeText(context, "读档失败: ${e.message}", Toast.LENGTH_SHORT).show()
                            }
                        } else {
                            Toast.makeText(context, "槽位 $slot 无存档", Toast.LENGTH_SHORT).show()
                        }
                    }
                    saveLoadSlot = slot
                    showSlotPicker = null
                },
                onDismiss = { showSlotPicker = null }
            )
        }

        // Fast-forward speed picker dialog
        if (showFFSpeedPicker) {
            androidx.compose.material3.AlertDialog(
                onDismissRequest = { showFFSpeedPicker = false },
                title = { Text("快进速度") },
                text = {
                    Column {
                        listOf(2, 4, 6, 8, 16).forEach { speed ->
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        fastForwardSpeed = speed
                                        lastNonZeroFFSpeed = speed
                                        showFFSpeedPicker = false
                                    }
                                    .padding(vertical = 12.dp, horizontal = 4.dp)
                            ) {
                                Text(
                                    "$speed 倍速" + when (speed) {
                                        6 -> " (默认)"
                                        16 -> " (极速，需高性能设备)"
                                        else -> ""
                                    },
                                    color = if (fastForwardSpeed == speed) Color(0xFFFFD66B) else Color.White,
                                    fontSize = 16.sp
                                )
                            }
                        }
                    }
                },
                confirmButton = {
                    androidx.compose.material3.TextButton(onClick = { showFFSpeedPicker = false }) { Text("关闭") }
                }
            )
        }

        if (loaded && showLayoutEditor) {
            PadLayoutEditor(
                padLayout = padLayout,
                platform = platform,
                isPortrait = isPortrait,
                onLayoutChange = { newLayout ->
                    // Just update in-memory state — the LaunchedEffect above
                    // will persist to disk 400ms after the last change.
                    // (Previously called PadLayoutStore.save() here on every
                    // pointer-move event, causing severe drag lag.)
                    padLayout = newLayout
                },
                surfaceSize = surfaceSize,
                onClose = { showLayoutEditor = false }
            )
        }

        if (loaded && showSettings) {
            SettingsPanel(
                padLayout = padLayout,
                platform = platform,
                onLayoutChange = { newLayout ->
                    padLayout = newLayout
                    applyCoreOptions(engine, newLayout, platform)
                },
                onEnterCustomLayout = {
                    val isNdsPlatform = platform == GamePlatform.NDS
                    if (isNdsPlatform) {
                        ndsTopRect = floatArrayOf(
                            if (isPortrait) padLayout.ndsTopLayoutLeftP else padLayout.ndsTopLayoutLeft,
                            if (isPortrait) padLayout.ndsTopLayoutTopP else padLayout.ndsTopLayoutTop,
                            if (isPortrait) padLayout.ndsTopLayoutRightP else padLayout.ndsTopLayoutRight,
                            if (isPortrait) padLayout.ndsTopLayoutBottomP else padLayout.ndsTopLayoutBottom
                        )
                        ndsBottomRect = floatArrayOf(
                            if (isPortrait) padLayout.ndsBottomLayoutLeftP else padLayout.ndsBottomLayoutLeft,
                            if (isPortrait) padLayout.ndsBottomLayoutTopP else padLayout.ndsBottomLayoutTop,
                            if (isPortrait) padLayout.ndsBottomLayoutRightP else padLayout.ndsBottomLayoutRight,
                            if (isPortrait) padLayout.ndsBottomLayoutBottomP else padLayout.ndsBottomLayoutBottom
                        )
                        showSettings = false
                        showNdsCustomLayoutEditor = true
                    } else {
                        customRect = floatArrayOf(
                            if (isPortrait) padLayout.customLayoutLeftP else padLayout.customLayoutLeft,
                            if (isPortrait) padLayout.customLayoutTopP else padLayout.customLayoutTop,
                            if (isPortrait) padLayout.customLayoutRightP else padLayout.customLayoutRight,
                            if (isPortrait) padLayout.customLayoutBottomP else padLayout.customLayoutBottom
                        )
                        showSettings = false
                        showCustomLayoutEditor = true
                    }
                },
                javaIsPerGame = javaHasOverride,
                onResetJavaToGlobal = if (platform == GamePlatform.JAVA) resetJavaToGlobal else null,
                onClose = { showSettings = false }
            )
        }

        // Custom free-form layout editor — 4-corner drag to resize, drag the
        // rectangle body to move. ScreenPositionEditor (a native View) draws
        // the handles and intercepts touches; this Compose block only adds the
        // hint bar + confirm/reset buttons on top.
        if (showCustomLayoutEditor) {
            AndroidView(
                factory = { ctx ->
                    ScreenPositionEditor(ctx).apply {
                        setRect(customRect[0], customRect[1], customRect[2], customRect[3])
                        listener = object : ScreenPositionEditor.Listener {
                            override fun onRectChanged(x1: Float, y1: Float, x2: Float, y2: Float, confirm: Boolean) {
                                // Live-update the game surface while dragging
                                customRect = floatArrayOf(x1, y1, x2, y2)
                                if (confirm) {
                                    // Touch-up — persist into padLayout (saved by the debounced effect)
                                    padLayout = if (isPortrait) {
                                        padLayout.copy {
                                            customLayoutLeftP = x1
 customLayoutTopP = y1
                                            customLayoutRightP = x2
 customLayoutBottomP = y2
                                        }
                                    } else {
                                        padLayout.copy {
                                            customLayoutLeft = x1
 customLayoutTop = y1
                                            customLayoutRight = x2
 customLayoutBottom = y2
                                        }
                                    }
                                }
                            }
                        }
                    }
                },
                update = { ed ->
                    ed.setRect(customRect[0], customRect[1], customRect[2], customRect[3])
                },
                modifier = Modifier.fillMaxSize()
            )
            // Top hint bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xCC000000))
                    .padding(8.dp)
            ) {
                Text(
                    "自由布局:拖动 4 角调整大小,拖动矩形内部移动位置",
                    color = Color.White,
                    fontSize = 13.sp
                )
            }
            // Bottom-right confirm button
            androidx.compose.material3.Button(
                onClick = {
                    showCustomLayoutEditor = false
                    // Persist the current rect even if the last drag was cancelled
                    padLayout = if (isPortrait) {
                        padLayout.copy {
                            customLayoutLeftP = customRect[0]
 customLayoutTopP = customRect[1]
                            customLayoutRightP = customRect[2]
 customLayoutBottomP = customRect[3]
                        }
                    } else {
                        padLayout.copy {
                            customLayoutLeft = customRect[0]
 customLayoutTop = customRect[1]
                            customLayoutRight = customRect[2]
 customLayoutBottom = customRect[3]
                        }
                    }
                },
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(16.dp)
            ) {
                Text("完成")
            }
            // Bottom-left reset button (restore fullscreen rect)
            androidx.compose.material3.OutlinedButton(
                onClick = {
                    customRect = floatArrayOf(0f, 0f, 1f, 1f)
                    padLayout = if (isPortrait) {
                        padLayout.copy {
                            customLayoutLeftP = 0f
 customLayoutTopP = 0f
                            customLayoutRightP = 1f
 customLayoutBottomP = 1f
                        }
                    } else {
                        padLayout.copy {
                            customLayoutLeft = 0f
 customLayoutTop = 0f
                            customLayoutRight = 1f
 customLayoutBottom = 1f
                        }
                    }
                },
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(16.dp)
            ) {
                Text("重置")
            }
        }

        // NDS 双屏自由布局编辑器 — 上屏/下屏各自独立矩形，可分别拖动调整。
        // NdsScreenPositionEditor 同时管理两个矩形（上屏蓝色、下屏粉红），
        // 参照 melonDS 官方 Android 布局模型（TOP_SCREEN / BOTTOM_SCREEN）。
        if (showNdsCustomLayoutEditor) {
            AndroidView(
                factory = { ctx ->
                    NdsScreenPositionEditor(ctx).apply {
                        setRects(ndsTopRect, ndsBottomRect)
                        listener = object : NdsScreenPositionEditor.Listener {
                            override fun onRectChanged(top: FloatArray, bottom: FloatArray, confirm: Boolean) {
                                // Live-update 双屏渲染
                                ndsTopRect = top
                                ndsBottomRect = bottom
                                if (confirm) {
                                    // Touch-up — persist into padLayout
                                    padLayout = if (isPortrait) {
                                        padLayout.copy {
                                            ndsTopLayoutLeftP = top[0]
                                            ndsTopLayoutTopP = top[1]
                                            ndsTopLayoutRightP = top[2]
                                            ndsTopLayoutBottomP = top[3]
                                            ndsBottomLayoutLeftP = bottom[0]
                                            ndsBottomLayoutTopP = bottom[1]
                                            ndsBottomLayoutRightP = bottom[2]
                                            ndsBottomLayoutBottomP = bottom[3]
                                        }
                                    } else {
                                        padLayout.copy {
                                            ndsTopLayoutLeft = top[0]
                                            ndsTopLayoutTop = top[1]
                                            ndsTopLayoutRight = top[2]
                                            ndsTopLayoutBottom = top[3]
                                            ndsBottomLayoutLeft = bottom[0]
                                            ndsBottomLayoutTop = bottom[1]
                                            ndsBottomLayoutRight = bottom[2]
                                            ndsBottomLayoutBottom = bottom[3]
                                        }
                                    }
                                }
                            }
                        }
                    }
                },
                update = { ed: NdsScreenPositionEditor ->
                    ed.setRects(ndsTopRect, ndsBottomRect)
                },
                modifier = Modifier.fillMaxSize()
            )
            // Top hint bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xCC000000))
                    .padding(8.dp)
            ) {
                Text(
                    "NDS 双屏自由布局:分别拖动 上屏(蓝)/下屏(粉) 的 4 角与内部",
                    color = Color.White,
                    fontSize = 13.sp
                )
            }
            // Bottom-right confirm button
            androidx.compose.material3.Button(
                onClick = {
                    showNdsCustomLayoutEditor = false
                    padLayout = if (isPortrait) {
                        padLayout.copy {
                            ndsTopLayoutLeftP = ndsTopRect[0]
                                            ndsTopLayoutTopP = ndsTopRect[1]
                            ndsTopLayoutRightP = ndsTopRect[2]
                                            ndsTopLayoutBottomP = ndsTopRect[3]
                            ndsBottomLayoutLeftP = ndsBottomRect[0]
                                            ndsBottomLayoutTopP = ndsBottomRect[1]
                            ndsBottomLayoutRightP = ndsBottomRect[2]
                                            ndsBottomLayoutBottomP = ndsBottomRect[3]
                        }
                    } else {
                        padLayout.copy {
                            ndsTopLayoutLeft = ndsTopRect[0]
                                            ndsTopLayoutTop = ndsTopRect[1]
                            ndsTopLayoutRight = ndsTopRect[2]
                                            ndsTopLayoutBottom = ndsTopRect[3]
                            ndsBottomLayoutLeft = ndsBottomRect[0]
                                            ndsBottomLayoutTop = ndsBottomRect[1]
                            ndsBottomLayoutRight = ndsBottomRect[2]
                                            ndsBottomLayoutBottom = ndsBottomRect[3]
                        }
                    }
                },
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(16.dp)
            ) {
                Text("完成")
            }
            // Bottom-left reset button (restore default stacked layout)
            androidx.compose.material3.OutlinedButton(
                onClick = {
                    val defaultTop = if (isPortrait) {
                        floatArrayOf(0.05f, 0.05f, 0.95f, 0.48f)
                    } else {
                        floatArrayOf(0.05f, 0.05f, 0.95f, 0.48f)
                    }
                    val defaultBottom = if (isPortrait) {
                        floatArrayOf(0.05f, 0.52f, 0.95f, 0.98f)
                    } else {
                        floatArrayOf(0.05f, 0.52f, 0.95f, 0.98f)
                    }
                    ndsTopRect = defaultTop
                    ndsBottomRect = defaultBottom
                    padLayout = if (isPortrait) {
                        padLayout.copy {
                            ndsTopLayoutLeftP = defaultTop[0]
                                            ndsTopLayoutTopP = defaultTop[1]
                            ndsTopLayoutRightP = defaultTop[2]
                                            ndsTopLayoutBottomP = defaultTop[3]
                            ndsBottomLayoutLeftP = defaultBottom[0]
                                            ndsBottomLayoutTopP = defaultBottom[1]
                            ndsBottomLayoutRightP = defaultBottom[2]
                                            ndsBottomLayoutBottomP = defaultBottom[3]
                        }
                    } else {
                        padLayout.copy {
                            ndsTopLayoutLeft = defaultTop[0]
                                            ndsTopLayoutTop = defaultTop[1]
                            ndsTopLayoutRight = defaultTop[2]
                                            ndsTopLayoutBottom = defaultTop[3]
                            ndsBottomLayoutLeft = defaultBottom[0]
                                            ndsBottomLayoutTop = defaultBottom[1]
                            ndsBottomLayoutRight = defaultBottom[2]
                                            ndsBottomLayoutBottom = defaultBottom[3]
                        }
                    }
                },
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(16.dp)
            ) {
                Text("重置")
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Route gamepad bits to the correct player port
// ---------------------------------------------------------------------------
/**
 * 把摇杆 / 手柄 bit 路由到核心。
 *
 * - 单机模式：直接调 [EmulatorEngine.setPad1] / [setPad2] / pad3 / pad4。
 * - 联机模式：1P 输入送给 [com.nesstation.app.battle.NetplayController.setLocalPad]，
 *   由它的 frame hook 在 [NetplayHook.beforeFrame] 里和远端输入一起推回给核心；
 *   其它 player 槽位在联机下被忽略（2P 由远端控制，3P/4P 不参与）。
 */
// ---------------------------------------------------------------------------
// NDS "ROM 同目录同名 .sav" 解析（core_builtin 存档方式，兼容官方 melonDS APK）
// ---------------------------------------------------------------------------
/**
 * 解析 NDS 游戏的 .sav 目标位置。
 *
 * 返回三元组：
 *  - [NdsSaveResolution.dir]      —— .sav 所在目录（传给引擎作为 saveDir）
 *  - [NdsSaveResolution.basename] —— .sav 基名（不含扩展名；null = 解析失败，
 *                                     调用方回退到 game.id / 内部目录）
 *  - [NdsSaveResolution.notice]   —— 需要向用户提示的回退说明（null = 正常）
 *
 * 解析优先级：
 *  1. romPath 是真实文件路径 → 取其父目录（可写时直接使用）
 *  2. romPath 是 content:// → SAF 查询原始文件名；再从 URI 末段推导真实
 *     父目录（外部存储文档 URI 形如 primary:ROMs/xxx.nds →
 *     /storage/emulated/0/ROMs），可写时使用
 *  3. 都失败 → 应用内部 saves 目录 + ROM 原始文件名（保证多游戏不共用
 *     temp_rom.sav），并给出提示
 */
private data class NdsSaveResolution(
    val dir: String,
    val basename: String?,
    val notice: String?
)

private fun resolveNdsRomAdjacentSave(
    context: android.content.Context,
    romPath: String,
    internalSavesDir: java.io.File
): NdsSaveResolution {
    // 1) 真实文件路径（直接文件访问）
    if (!romPath.startsWith("content://")) {
        val f = java.io.File(romPath)
        if (f.exists()) {
            val dir = f.parentFile
            if (dir != null && isDirWritable(dir)) {
                return NdsSaveResolution(dir.absolutePath, f.nameWithoutExtension, null)
            }
            return NdsSaveResolution(internalSavesDir.absolutePath, f.nameWithoutExtension,
                "ROM 所在目录不可写，.sav 将保存在应用内部存档目录")
        }
    }

    // 2) content:// URI —— 查询原始文件名
    var origName = ""
    try {
        val uri = android.net.Uri.parse(romPath)
        context.contentResolver.query(uri, null, null, null, null)?.use { c ->
            val idx = c.getColumnIndex(
                android.provider.DocumentsContract.Document.COLUMN_DISPLAY_NAME
            )
            if (idx >= 0 && c.moveToFirst()) {
                val n = c.getString(idx)
                if (!n.isNullOrBlank()) origName = n
            }
        }
    } catch (_: Exception) { }
    if (origName.isBlank()) {
        try {
            val lastSeg = android.net.Uri.parse(romPath).lastPathSegment
            if (!lastSeg.isNullOrBlank()) {
                origName = android.net.Uri.decode(lastSeg).substringAfterLast('/')
            }
        } catch (_: Exception) { }
    }
    if (origName.isBlank()) {
        // 连原始文件名都拿不到 —— 让调用方保持默认（game.id / 内部目录）
        return NdsSaveResolution(internalSavesDir.absolutePath, null, null)
    }
    val base = origName.substringBeforeLast('.').ifBlank { null }

    // 尝试从 URI 推导真实父目录（外部存储 document/tree URI）
    val realDir = deriveRealDirFromSafUri(romPath)
    if (realDir != null && isDirWritable(realDir)) {
        return NdsSaveResolution(realDir.absolutePath, base, null)
    }
    // 3) 回退：内部目录 + ROM 原始文件名
    return NdsSaveResolution(internalSavesDir.absolutePath, base,
        "无法访问 ROM 所在目录，.sav 将保存在应用内部存档目录")
}

/**
 * 把 content:// 文档 URI 解析为外部存储上的真实文件路径。
 *
 * 形如 content://com.android.externalstorage.documents/tree/primary%3AROMs%2Fnds/document/primary%3AROMs%2Fnds%2Fgame.nds
 * 或 .../document/primary%3AROMs%2Fnds%2Fgame.nds 的 URI 末段解码后为
 * "primary:ROMs/nds/game.nds" → /storage/emulated/0/ROMs/nds/game.nds。
 *
 * 仅支持外部存储主卷/第二卷（volume id = primary 或含连字符的卷号，如
 * 17FB-1E12）。其它 provider（下载、云盘等）没有稳定的磁盘路径，返回
 * null —— 调用方应回退到复制缓存方案。
 *
 * 注意：真实路径存在 ≠ 一定可读。Android 11+ 作用域存储下，App 必须在
 * 运行时获得 MANAGE_EXTERNAL_STORAGE（或旧版 READ_EXTERNAL_STORAGE）
 * 才能直接按路径读取；调用方仍需用 canRead()/openInputStream 验证。
 */
private fun resolveContentUriToRealFile(uriStr: String): java.io.File? {
    if (!uriStr.startsWith("content://")) return null
    return try {
        val uri = android.net.Uri.parse(uriStr)
        val lastSeg = android.net.Uri.decode(uri.lastPathSegment ?: return null)
        // "primary:ROMs/nds/game.nds" 或 "17FB-1E12:ROMS/game.chd"
        if (!lastSeg.contains(':')) return null
        val volume = lastSeg.substringBefore(':', "").trim()
        val pathPart = lastSeg.substringAfter(':', "").trimStart('/')
        if (pathPart.isBlank() || pathPart.isEmpty()) return null
        val storageRoot = when {
            volume.equals("primary", ignoreCase = true) -> "/storage/emulated/0"
            volume.contains('-') -> "/storage/$volume"
            else -> return null
        }
        val file = java.io.File(storageRoot, pathPart)
        file.takeIf { it.isFile }
    } catch (_: Exception) { null }
}

/**
 * 从 SAF document/tree URI 推导 ROM 的真实父目录。
 * 形如 content://com.android.externalstorage.documents/tree/primary%3AROMs%2Fnds/document/primary%3AROMs%2Fnds%2Fgame.nds
 * 的 URI 末段解码后为 "primary:ROMs/nds/game.nds" → /storage/emulated/0/ROMs/nds。
 * 仅支持外部存储主卷/第二卷；其它 provider（下载、云盘等）返回 null。
 */
private fun deriveRealDirFromSafUri(romPath: String): java.io.File? {
    return try {
        val uri = android.net.Uri.parse(romPath)
        val lastSeg = android.net.Uri.decode(uri.lastPathSegment ?: return null)
        // "primary:ROMs/nds/game.nds" 或 "17FB-1E12:ROMs/game.nds"
        val volume = lastSeg.substringBefore(':', "")
        val pathPart = if (lastSeg.contains(':')) lastSeg.substringAfter(':') else lastSeg
        val parent = pathPart.substringBeforeLast('/', "")
        if (parent.isBlank()) return null
        val storageRoot = when {
            volume.equals("primary", ignoreCase = true) -> "/storage/emulated/0"
            volume.contains('-') -> "/storage/$volume"
            else -> "/storage/emulated/0"
        }
        val dir = java.io.File(storageRoot, parent)
        if (dir.isDirectory) dir else null
    } catch (_: Exception) { null }
}

/** 通过实际创建/删除探测文件判断目录是否可写（canWrite() 在作用域存储下不可靠）。 */
private fun isDirWritable(dir: java.io.File): Boolean {
    return try {
        val probe = java.io.File(dir, ".nesstation_probe_${System.currentTimeMillis()}")
        val ok = probe.createNewFile()
        if (ok) probe.delete()
        ok
    } catch (_: Exception) { false }
}

// ---------------------------------------------------------------------------
// 连发 A/B（小 AB）默认位置避让
// ---------------------------------------------------------------------------
/**
 * 6 键平台（SFC/MD/NDS/街机等有 X/Y 键）上连发键的默认位置避让。
 *
 * 连发 A/B 的存储字段（btnTurboA/B）与 NES/GB 共用，历史默认位置
 * （横屏 0.87/0.60、0.72/0.66）与 X/Y 键（0.88/0.54、0.73/0.60）重叠。
 * 当平台显示 X/Y 且连发键仍处于历史默认位置（说明用户从未拖动过，
 * NES/GB 用户拖动后位置不再匹配默认值）时，返回挪到 A/B 附近、
 * 与所有按键都不重叠的默认位置；否则原样返回用户自定义位置。
 */
private fun shiftTurboDefault(
    turbo: ButtonLayout,
    showXY: Boolean,
    isPortrait: Boolean,
    isA: Boolean
): ButtonLayout {
    if (!showXY) return turbo
    return if (isPortrait) {
        if (isA) {
            if (turbo.x == 0.82f && turbo.y == 0.56f) turbo.copy(x = 0.94f, y = 0.62f) else turbo
        } else {
            if (turbo.x == 0.68f && turbo.y == 0.62f) turbo.copy(x = 0.93f, y = 0.88f) else turbo
        }
    } else {
        if (isA) {
            if (turbo.x == 0.87f && turbo.y == 0.60f) turbo.copy(x = 0.96f, y = 0.72f) else turbo
        } else {
            if (turbo.x == 0.72f && turbo.y == 0.66f) turbo.copy(x = 0.95f, y = 0.90f) else turbo
        }
    }
}

private fun routePadBits(
    engine: EmulatorEngine,
    player: Int,
    bits: Int,
    netplayController: com.nesstation.app.battle.NetplayController? = null,
    platform: GamePlatform = GamePlatform.NES
) {
    // NDS / PSX / PS2 / DC 使用项目位布局到 libretro 标准位布局的转换。
    // NDS（melonDS）按标准 libretro JOYPAD 位理解，用 projectToLibretroLayout。
    // PSX（PCSX-ReARMed）libretro 映射为：✕=bit0(B)、□=bit1(Y)、
    // ○=bit8(A)、△=bit9(X)，且 PSX 屏幕标签是 A=✕、B=○、X=△、Y=□，
    // 因此用 psxToLibretroLayout（A→bit0、B→bit8、X→bit9、Y→bit1）。
    // PS2（PCEE2）按键 label 语义不同（×=bit0、□=bit1、○=bit8、△=bit9），
    // 用 ps2ToLibretroLayout 单独转换。
    // DC（Flycast）按 dc_joymap 同名直传：A=bit0、B=bit8、X=bit1、Y=bit9、
    // L2/R2=扳机，用 dcToLibretroLayout。
    val ndsBits = if (platform == GamePlatform.NDS) projectToLibretroLayout(bits)
                  else if (platform == GamePlatform.PSX) psxToLibretroLayout(bits)
                  else if (platform == GamePlatform.PS2) ps2ToLibretroLayout(bits)
                  else if (platform == GamePlatform.DC) dcToLibretroLayout(bits)
                  else bits
    if (netplayController != null) {
        // 联机对战：只接受本地 1P 输入；2P 由远端玩家控制
        if (player == 0) netplayController.setLocalPad(ndsBits)
        return
    }
    when (player) {
        0 -> engine.setPad1(ndsBits)
        1 -> engine.setPad2(ndsBits)
        // 3P/4P：FbNeo 与 Flycast 支持 4 端口（街机/DC 4 人游戏）
        2 -> when (engine) {
            is com.nesstation.app.core.engine.FbNeoEngine -> engine.setPad3(ndsBits)
            is com.nesstation.app.core.engine.FlycastEngine -> engine.setPad3(ndsBits)
            else -> {}
        }
        3 -> when (engine) {
            is com.nesstation.app.core.engine.FbNeoEngine -> engine.setPad4(ndsBits)
            is com.nesstation.app.core.engine.FlycastEngine -> engine.setPad4(ndsBits)
            else -> {}
        }
    }
}

// ---------------------------------------------------------------------------
// Apply core options to engine — platform-aware option mapping
// ---------------------------------------------------------------------------
private fun applyCoreOptions(engine: EmulatorEngine, layout: PadLayout, platform: GamePlatform = GamePlatform.NES) {
    when (platform) {
        GamePlatform.NES -> {
            engine.setCoreOption("fceumm_ntsc_filter", layout.ntscFilter)
            engine.setCoreOption("fceumm_palette", layout.palette)
            engine.setCoreOption("fceumm_region", layout.region)
            val cropVal = if (layout.cropOverscan == "enabled") "8" else "0"
            engine.setCoreOption("fceumm_overscan_h_left", cropVal)
            engine.setCoreOption("fceumm_overscan_h_right", cropVal)
            engine.setCoreOption("fceumm_overscan_v_top", cropVal)
            engine.setCoreOption("fceumm_overscan_v_bottom", cropVal)
            engine.setCoreOption("fceumm_overclocking", layout.overclocking)
        }
        GamePlatform.SFC -> {
            // SNES9x aspect ratio values: "4:3" | "uncorrected" | "auto" | "ntsc" | "pal"
            // The dropdown already provides these values directly.
            engine.setCoreOption("snes9x_aspect", layout.aspectRatio)
            engine.setCoreOption("snes9x_overclock", layout.sfcOverclock)
            engine.setCoreOption("snes9x_blargg", layout.ntscFilter)
            engine.setCoreOption("snes9x_overscan", layout.sfcOverscan)
            engine.setCoreOption("snes9x_up_down_allowed", layout.sfcUpDownAllowed)
            engine.setCoreOption("snes9x_reduce_sprite_flicker", layout.sfcReduceSpriteFlicker)
            engine.setCoreOption("snes9x_overclock_cycles", layout.sfcReduceSlowdown)
            engine.setCoreOption("snes9x_audio_interpolation", layout.sfcAudioInterpolation)
            engine.setCoreOption("snes9x_gfx_transp", layout.sfcGfxTransparency)
            engine.setCoreOption("snes9x_gfx_hires", layout.sfcGfxHires)
            engine.setCoreOption("snes9x_gfx_clip", layout.sfcGfxClip)
            engine.setCoreOption("snes9x_block_invalid_vram_access", layout.sfcBlockInvalidVram)
            engine.setCoreOption("snes9x_hires_blend", layout.sfcSideBySide)
            engine.setCoreOption("snes9x_echo_buffer_hack", layout.sfcSoundOutput)
            engine.setCoreOption("snes9x_randomize_memory", layout.sfcSuperScope)
            engine.setCoreOption("snes9x_region", "auto")
            engine.setCoreOption("snes9x_layer_1", layout.sfcLayer1)
            engine.setCoreOption("snes9x_layer_2", layout.sfcLayer2)
            engine.setCoreOption("snes9x_layer_3", layout.sfcLayer3)
            engine.setCoreOption("snes9x_layer_4", layout.sfcLayer4)
            engine.setCoreOption("snes9x_layer_5", layout.sfcLayer5)
            // Sound channels (8 individual channels)
            engine.setCoreOption("snes9x_sndchan_1", "enabled")
            engine.setCoreOption("snes9x_sndchan_2", "enabled")
            engine.setCoreOption("snes9x_sndchan_3", "enabled")
            engine.setCoreOption("snes9x_sndchan_4", "enabled")
            engine.setCoreOption("snes9x_sndchan_5", "enabled")
            engine.setCoreOption("snes9x_sndchan_6", "enabled")
            engine.setCoreOption("snes9x_sndchan_7", "enabled")
            engine.setCoreOption("snes9x_sndchan_8", "enabled")
        }
        GamePlatform.GB, GamePlatform.GBA -> {
            engine.setCoreOption("mgba_gb_model", layout.gbModel)
            engine.setCoreOption("mgba_gb_colors", layout.gbColorCorrection)
            engine.setCoreOption("mgba_gb_colors_preset", layout.gbcColorPreset)
            engine.setCoreOption("mgba_gba_colors", layout.gbaColorCorrection)
            engine.setCoreOption("mgba_gba_colors_preset", layout.gbaColorPreset)
            engine.setCoreOption("mgba_interframe_blending", layout.gbaFrameBlending)
            engine.setCoreOption("mgba_solar_sensor_level", layout.gbaSolarSensor)
            engine.setCoreOption("mgba_frameskip", layout.gbaFrameskipCount)
            engine.setCoreOption("mgba_frameskip_type", layout.gbaFrameskipType)
            engine.setCoreOption("mgba_frameskip_threshold", layout.gbaFrameskipThreshold)
            // Audio: do NOT set any audio options. Let mGBA use its built-in defaults.
            engine.setCoreOption("mgba_sgb_borders", layout.gbSgbBorders)
            engine.setCoreOption("mgba_gba_forceRTC", layout.gbaForceRTC)
            engine.setCoreOption("mgba_allow_opposite_directions", layout.gbaAllowOpposite)
            if (platform == GamePlatform.GBA) {
                engine.setCoreOption("mgba_gba_idle_optimization", layout.gbaIdleOptimization)
            }
        }
        GamePlatform.DOS -> {
            // Apply all DOSBox-Pure core options. Keys AND values verified
            // against the shipped libdosbox_pure_libretro_android.so
            // (strings-dumped; invalid keys are silently ignored by cores).
            engine.setCoreOption("dosbox_pure_machine", layout.dosMachine)
            engine.setCoreOption("dosbox_pure_cycles", layout.dosCycles)
            engine.setCoreOption("dosbox_pure_cycles_max", layout.dosCyclesMax)
            engine.setCoreOption("dosbox_pure_sblaster_type", layout.dosSbType)
            engine.setCoreOption("dosbox_pure_sblaster_adlib_mode", layout.dosSbAdlibMode)
            engine.setCoreOption("dosbox_pure_sblaster_adlib_emu", layout.dosSbAdlibEmu)
            engine.setCoreOption("dosbox_pure_gus", layout.dosGus)
            // === 音频：核心自带混音器输出速率（修复爆音的关键设置之一）===
            // 默认 48000 与前端 AudioTrack 完全一致 → 本地重采样器旁路。
            engine.setCoreOption("dosbox_pure_audiorate", layout.dosAudiorate)
            engine.setCoreOption("dosbox_pure_swapstereo", layout.dosSwapStereo)
            engine.setCoreOption("dosbox_pure_tandysound", layout.dosTandySound)
            engine.setCoreOption("dosbox_pure_mouse_input", layout.dosMouseInput)
            engine.setCoreOption("dosbox_pure_keyboard_layout", layout.dosKeyboardLayout)
            engine.setCoreOption("dosbox_pure_auto_mapping", layout.dosAutoMapping)
            engine.setCoreOption("dosbox_pure_savestate", layout.dosSavestate)
            engine.setCoreOption("dosbox_pure_voodoo", layout.dosVoodoo)
            engine.setCoreOption("dosbox_pure_force60fps", layout.dosForce60fps)
            // CPU / memory / video (真正存在于本核心的选项)
            engine.setCoreOption("dosbox_pure_cpu_core", layout.dosCpuCore)
            engine.setCoreOption("dosbox_pure_cpu_type", layout.dosCpuType)
            engine.setCoreOption("dosbox_pure_memory_size", layout.dosMemorySize)
            engine.setCoreOption("dosbox_pure_cga", layout.dosCgaMode)
            engine.setCoreOption("dosbox_pure_aspect_correction", layout.dosAspectCorrection)
            // NOTE: dosMachine/dosCycles 等旧字段里 "none"/"custom" 等无效值已从 UI 移除。
            // 以下键在本预编译核心中不存在，不再发送：
            //   time_announce / keyboard_delay / keyboard_rate / mouse_timeout /
            //   dim_screen / resolution / scale / aspect_ratio / cga_colors
        }
        GamePlatform.ARCADE -> {
            // FBNeo core options — keys match libretro_core_options.h.
            engine.setCoreOption("fbneo-aspect", layout.arcadeAspect)
            engine.setCoreOption("fbneo-rotate-mode", layout.arcadeRotate)
            engine.setCoreOption("fbneo-vertical-mode", layout.arcadeVerticalMode)
            engine.setCoreOption("fbneo-crop-overscan", layout.arcadeCropOverscan)
            engine.setCoreOption("fbneo-cpu-speed", layout.arcadeCpuSpeed)
            engine.setCoreOption("fbneo-cpu-frameskip", layout.arcadeFrameskip)
            engine.setCoreOption("fbneo-force-60hz", layout.arcadeForce60hz)
            engine.setCoreOption("fbneo-samplerate", layout.arcadeSampleRate)
            engine.setCoreOption("fbneo-audio-interpolation", layout.arcadeAudioInterp)
            engine.setCoreOption("fbneo-lowpass", layout.arcadeLowpass)
            engine.setCoreOption("fbneo-neogeo-mode", layout.arcadeNeogeomode)
            engine.setCoreOption("fbneo-memcard-mode", layout.arcadeMemcard)
        }
        GamePlatform.MD -> {
            // Genesis-Plus-GX core options — keys match libretro_core_options.h.
            engine.setCoreOption("genesis_plus_gx_region", layout.mdRegion)
            engine.setCoreOption("genesis_plus_gx_system", layout.mdSystem)
            engine.setCoreOption("genesis_plus_gx_aspect_ratio", layout.mdAspect)
            engine.setCoreOption("genesis_plus_gx_render", layout.mdRender)
            engine.setCoreOption("genesis_plus_gx_blargg_ntsc_filter", layout.mdNtscFilter)
            engine.setCoreOption("genesis_plus_gx_lcd_filter", layout.mdLcdFilter)
            engine.setCoreOption("genesis_plus_gx_overscan", layout.mdOverscan)
            engine.setCoreOption("genesis_plus_gx_gg_extra", layout.mdGgExtra)
            engine.setCoreOption("genesis_plus_gx_left_border", layout.mdLeftBorder)
            engine.setCoreOption("genesis_plus_gx_input", layout.mdInput)
            engine.setCoreOption("genesis_plus_gx_allow_up_down_allowed", layout.mdAllowUpDown)
            engine.setCoreOption("genesis_plus_gx_overclock", layout.mdOverclock)
            engine.setCoreOption("genesis_plus_gx_frameskip", layout.mdFrameskip)
            engine.setCoreOption("genesis_plus_gx_cd_fastboot", layout.mdCdFastboot)
            engine.setCoreOption("genesis_plus_gx_sms_fm", layout.mdSmsFm)
            engine.setCoreOption("genesis_plus_gx_gg_stretch", layout.mdGgStretch)
        }
        GamePlatform.PCE -> {
            // Geargrafx core options — keys match libretro_core_options.h.
            engine.setCoreOption("geargrafx_console_type", layout.pceConsoleType)
            engine.setCoreOption("geargrafx_aspect_ratio", layout.pceAspect)
            engine.setCoreOption("geargrafx_overscan", layout.pceOverscan)
            engine.setCoreOption("geargrafx_no_sprite_limit", layout.pceNoSpriteLimit)
            engine.setCoreOption("geargrafx_palette", layout.pcePalette)
            engine.setCoreOption("geargrafx_cdrom_bios", layout.pceCdromBios)
            engine.setCoreOption("geargrafx_turbotap", layout.pceTurbotap)
            engine.setCoreOption("geargrafx_mb128", layout.pceMb128)
            engine.setCoreOption("geargrafx_up_down_allowed", layout.pceAllowUpDown)
        }
        GamePlatform.NDS -> {
            // melonDS core options — keys/values must match the prebuilt
            // melonDS libretro core (v1.1, libretro_core_options.h).
            engine.setCoreOption("melonds_boot_directly", "enabled")   // jump straight into the game, not the grey FW menu
            engine.setCoreOption("melonds_console_mode", layout.ndsConsoleMode)   // "DS" | "DSi"
            engine.setCoreOption("melonds_screen_layout", layout.ndsScreenLayout) // "Top/Bottom" | "Bottom/Top" | "Left/Right" | ...
            // OpenGL 渲染器：启用后分辨率缩放生效，3D 渲染使用硬件加速
            engine.setCoreOption("melonds_opengl_renderer", layout.ndsOpenGlRenderer) // "enabled" | "disabled"
            // OpenGL 内部分辨率：仅 OpenGL 渲染器生效，值格式 "1x native (256x192)" .. "8x native (2048x1536)"
            engine.setCoreOption("melonds_opengl_resolution", layout.ndsResolution)
            // OpenGL 多边形优化：改善多边形分割，减少图形错误
            engine.setCoreOption("melonds_opengl_better_polygons", layout.ndsOpenGlBetterPolygons)
            // OpenGL 纹理过滤：nearest(锐利) | linear(平滑)
            engine.setCoreOption("melonds_opengl_filtering", layout.ndsOpenGlFiltering)
            // 当 OpenGL 渲染器启用时，核心会自动禁用软件线程渲染器，所以这里不再设置
            // melonds_threaded_renderer
            engine.setCoreOption("melonds_touch_mode", layout.ndsTouchMode)       // "Mouse" | "Touch" | "Joystick"
            engine.setCoreOption("melonds_dsi_sdcard", layout.ndsDsiSdcard)
            engine.setCoreOption("melonds_randomize_mac_address", layout.ndsRandomizeMac)
            engine.setCoreOption("melonds_jit_enable", layout.ndsJitEnable)
            engine.setCoreOption("melonds_audio_interpolation", layout.ndsAudioInterpolation)
            engine.setCoreOption("melonds_use_fw_settings", layout.ndsUseFwSettings)
            engine.setCoreOption("melonds_screen_gap", layout.ndsScreenGap)
            engine.setCoreOption("melonds_swapscreen_mode", layout.ndsSwapscreenMode)
            engine.setCoreOption("melonds_mic_input", layout.ndsMicInput)
            engine.setCoreOption("melonds_language", layout.ndsLanguage)
            engine.setCoreOption("melonds_audio_bitrate", layout.ndsAudioBitrate)
            engine.setCoreOption("melonds_jit_block_size", layout.ndsJitBlockSize)
            engine.setCoreOption("melonds_jit_fast_memory", layout.ndsJitFastMemory)
            engine.setCoreOption("melonds_jit_branch_optimisations", layout.ndsJitBranchOptimisations)
            engine.setCoreOption("melonds_jit_literal_optimisations", layout.ndsJitLiteralOptimisations)
            engine.setCoreOption("melonds_hybrid_small_screen", layout.ndsHybridSmallScreen)
        }
        GamePlatform.PSX -> {
            // PCSX-ReARMed core options — keys/values verified against the
            // shipped libpcsx_rearmed_libretro_android.so AND upstream
            // notaz/pcsx_rearmed frontend/libretro_core_options.h.
            // 注意: 之前的实现里有几个键名是错的，导致设置完全不生效:
            //   pcsx_rearmed_frameskip   (legacy, 改用 frameskip_interval)
            //   pcsx_rearmed_pad1type/2type (不存在! 手柄类型要用
            //       retro_set_controller_port_device — 见下方 setPadTypes)
            //   pcsx_rearmed_cpu_clock   → pcsx_rearmed_psxclock
            //   pcsx_rearmed_rgb32       → pcsx_rearmed_rgb32_output
            //   pcsx_rearmed_gpu_odd_even→ pcsx_rearmed_gpu_peops_odd_even_bit
            //   pcsx_rearmed_analog_axis → pcsx_rearmed_analog_axis_modifier

            // --- 系统 / BIOS ---
            engine.setCoreOption("pcsx_rearmed_bios", layout.pscxBios)
            engine.setCoreOption("pcsx_rearmed_region", layout.pscxRegion)
            engine.setCoreOption("pcsx_rearmed_show_bios_bootlogo", layout.pscxShowBootlogo)
            engine.setCoreOption("pcsx_rearmed_memcard1", layout.pscxMemcard1)
            engine.setCoreOption("pcsx_rearmed_memcard2", layout.pscxMemcard2)
            engine.setCoreOption("pcsx_rearmed_cd_readahead", layout.pscxCdReadahead)

            // --- CPU / 性能 ---
            engine.setCoreOption("pcsx_rearmed_drc", layout.pscxDrc)              // 动态重编译(性能关键)
            engine.setCoreOption("pcsx_rearmed_drc_thread", layout.pscxDrcThread) // 编译器线程化
            engine.setCoreOption("pcsx_rearmed_psxclock", layout.pscxClock)       // PSX CPU 频率 %
            engine.setCoreOption("pcsx_rearmed_icache_emulation", layout.pscxIcache)
            engine.setCoreOption("pcsx_rearmed_cd_turbo", layout.pscxCdTurbo)
            engine.setCoreOption("pcsx_rearmed_nocompathacks", "disabled")

            // --- GPU / 显示 ---
            engine.setCoreOption("pcsx_rearmed_gpu_thread_rendering", layout.pscxGpuThreadRendering) // GPU 渲染线程化(性能关键)
            engine.setCoreOption("pcsx_rearmed_dithering", layout.pscxDithering)
            engine.setCoreOption("pcsx_rearmed_gpu_slow_llists", "auto")
            engine.setCoreOption("pcsx_rearmed_rgb32_output", layout.pscxRgb32)
            engine.setCoreOption("pcsx_rearmed_scale_hires", layout.pscxScaleHires)
            engine.setCoreOption("pcsx_rearmed_show_overscan", layout.pscxShowOverscan)
            engine.setCoreOption("pcsx_rearmed_fractional_framerate", layout.pscxFractionalFps)
            engine.setCoreOption("pcsx_rearmed_alt_flip", layout.pscxAltFlip)
            engine.setCoreOption("pcsx_rearmed_neon_interlace_enable_v2", layout.pscxNeonInterlace)
            engine.setCoreOption("pcsx_rearmed_neon_enhancement_enable", layout.pscxNeonEnhance)
            engine.setCoreOption("pcsx_rearmed_screen_centering", layout.pscxCentering)

            // --- 跳帧 (注意: 类型值必须是 disabled/auto/auto_threshold/fixed_interval) ---
            engine.setCoreOption("pcsx_rearmed_frameskip_type", layout.pscxFrameskipType)
            engine.setCoreOption("pcsx_rearmed_frameskip_threshold", layout.pscxFrameskipThreshold)
            engine.setCoreOption("pcsx_rearmed_frameskip_interval", layout.pscxFrameskip)

            // --- SPU / 音频 ---
            engine.setCoreOption("pcsx_rearmed_spu_interpolation", layout.pscxSpuInterp)
            engine.setCoreOption("pcsx_rearmed_spu_reverb", layout.pscxSpuReverb)
            // 反向逻辑: enabled = 播放 CD-DA/XA 音轨; disabled = 关闭(提速)
            engine.setCoreOption("pcsx_rearmed_nocdaudio", layout.pscxCdAudio)
            engine.setCoreOption("pcsx_rearmed_noxadecoding", layout.pscxXaAudio)
            engine.setCoreOption("pcsx_rearmed_spu_thread", layout.pscxSpuThread)

            // --- 手柄 / 输入 ---
            // pad1/pad2 类型不是 core option! 通过控制器端口设备来设置
            // (standard = 数字手柄 JOYPAD, analog = DualShock 模拟手柄)
            (engine as? com.nesstation.app.core.engine.PsxEngine)?.setPadTypes(
                layout.pscxPad1Type, layout.pscxPad2Type)
            engine.setCoreOption("pcsx_rearmed_vibration", layout.pscxVibration)
            engine.setCoreOption("pcsx_rearmed_analog_axis_modifier", layout.pscxAnalogAxis)
            engine.setCoreOption("pcsx_rearmed_multitap", layout.pscxMultitap)
            engine.setCoreOption("pcsx_rearmed_negcon_response", layout.pscxNegconResponse)
            engine.setCoreOption("pcsx_rearmed_negcon_deadzone", layout.pscxNegconDeadzone)
            engine.setCoreOption("pcsx_rearmed_gpu_peops_odd_even_bit", layout.pscxGpuOddEven)
        }
        GamePlatform.DC -> {
            // Flycast core options — 键名/取值已对照 flycast 上游
            // shell/libretro/libretro_core_options.h（CORE_OPTION_NAME = "reicast"）
            // 与 buildbot 预编译 libflycast_libretro_android.so 内嵌字符串双向校验。
            // 分辨率/滤镜等改后即时生效；hle_bios 等标注重启生效的项在
            // applyCoreOptions 先于 loadRom 运行，下次进游戏即生效。

            // --- 系统 / BIOS ---
            engine.setCoreOption("reicast_region", layout.dcRegion)             // Japan|USA|Europe|Default
            engine.setCoreOption("reicast_language", layout.dcLanguage)        // BIOS/游戏语言
            engine.setCoreOption("reicast_hle_bios", layout.dcHleBios)         // 强制 HLE BIOS（重启）
            engine.setCoreOption("reicast_enable_dsp", layout.dcEnableDsp)     // AICA DSP 音频精度
            engine.setCoreOption("reicast_broadcast", layout.dcBroadcast)      // NTSC|PAL|PAL_N|PAL_M|Default
            engine.setCoreOption("reicast_cable_type", layout.dcCableType)     // VGA|TV (RGB)|TV (Composite)
            engine.setCoreOption("reicast_dc_32mb_mod", layout.dc32MbMod)      // 32MB 内存改造(自制软件)

            // --- CPU / SH4 ---
            engine.setCoreOption("reicast_sh4clock", layout.dcSh4Clock)        // SH4 主频 MHz

            // --- 画面 / GPU (PowerVR2) ---
            engine.setCoreOption("reicast_internal_resolution", layout.dcInternalRes)
            engine.setCoreOption("reicast_alpha_sorting", layout.dcAlphaSorting)
            engine.setCoreOption("reicast_anisotropic_filtering", layout.dcAnisotropic)
            engine.setCoreOption("reicast_texture_filtering", layout.dcTextureFiltering)
            engine.setCoreOption("reicast_mipmapping", layout.dcMipmapping)
            engine.setCoreOption("reicast_fog", layout.dcFog)
            engine.setCoreOption("reicast_volume_modifier_enable", layout.dcVolumeModifier)
            engine.setCoreOption("reicast_widescreen_hack", layout.dcWidescreenHack)
            engine.setCoreOption("reicast_pvr2_filtering", layout.dcPvr2Filtering)
            engine.setCoreOption("reicast_emulate_framebuffer", layout.dcEmulateFramebuffer)
            engine.setCoreOption("reicast_enable_rttb", layout.dcEnableRttb)
            engine.setCoreOption("reicast_native_depth_interpolation", layout.dcDepthInterpolation)
            engine.setCoreOption("reicast_fix_upscale_bleeding_edge", layout.dcFixUpscaleBleeding)
            engine.setCoreOption("reicast_texupscale", layout.dcTexUpscale)
            engine.setCoreOption("reicast_texupscale_max_filtered_texture_size", layout.dcTexUpscaleMaxSize)
            engine.setCoreOption("reicast_screen_rotation", layout.dcScreenRotation)
            engine.setCoreOption("reicast_delay_frame_swapping", layout.dcDelayFrameSwapping)

            // --- 性能 / 线程 ---
            engine.setCoreOption("reicast_threaded_rendering", layout.dcThreadedRendering)
            engine.setCoreOption("reicast_auto_skip_frame", layout.dcAutoSkipFrame)
            engine.setCoreOption("reicast_frame_skipping", layout.dcFrameSkipping)
            engine.setCoreOption("reicast_gdrom_fast_loading", layout.dcGdromFastLoading)

            // --- 音频 / VMU ---
            engine.setCoreOption("reicast_vmu_sound", layout.dcVmuSound)
            engine.setCoreOption("reicast_per_content_vmus", layout.dcPerContentVmus)
            engine.setCoreOption("reicast_device_port1_slot1", layout.dcPort1Slot1)
            engine.setCoreOption("reicast_device_port1_slot2", layout.dcPort1Slot2)
            engine.setCoreOption("reicast_device_port2_slot1", layout.dcPort2Slot1)
            engine.setCoreOption("reicast_device_port2_slot2", layout.dcPort2Slot2)
            engine.setCoreOption("reicast_device_port3_slot1", layout.dcPort3Slot1)
            engine.setCoreOption("reicast_device_port3_slot2", layout.dcPort3Slot2)
            engine.setCoreOption("reicast_device_port4_slot1", layout.dcPort4Slot1)
            engine.setCoreOption("reicast_device_port4_slot2", layout.dcPort4Slot2)

            // --- 街机（Naomi / Atomiswave）---
            engine.setCoreOption("reicast_allow_service_buttons", layout.dcAllowServiceButtons)
            engine.setCoreOption("reicast_force_freeplay", layout.dcForceFreeplay)
            engine.setCoreOption("reicast_coin_limit", layout.dcCoinLimit)

            // --- 输入 ---
            engine.setCoreOption("reicast_analog_stick_deadzone", layout.dcStickDeadzone)
            engine.setCoreOption("reicast_trigger_deadzone", layout.dcTriggerDeadzone)
            engine.setCoreOption("reicast_digital_triggers", layout.dcDigitalTriggers)
        }
        GamePlatform.PS2 -> {
            // --- PCEE2 (PCSX2 v2.7.523) core options ---
            // 键名/取值已对照 pcee2-libretro 源码 Libretro.cpp definitions[] 表验证
            // (WizzardSK/pcee2-libretro, 与预编译 libpcee2_libretro_android.so 同源)。
            // 分辨率倍数/渲染器改后即时生效；需要重启的项(fast_boot/bios/multitap)
            // 在 applyCoreOptions 先于 loadRom 运行，故下次进游戏即生效。
            engine.setCoreOption("pcsx2_renderer", layout.ps2Renderer)
            engine.setCoreOption("pcsx2_upscale_multiplier", layout.ps2ResMulti)
            engine.setCoreOption(
                "pcsx2_texture_filtering",
                if (layout.ps2Bilinear == "enabled") "bilinear_ps2" else "nearest"
            )
            // System: BIOS 快速启动(跳过BIOS动画) / 手柄震动
            engine.setCoreOption("pcsx2_fast_boot", layout.ps2FastBoot)
            engine.setCoreOption("pcsx2_rumble", layout.ps2Rumble)
            // Graphics — 性能关键(移动 GPU 是 tiler 架构, 回读模式提速明显)
            engine.setCoreOption("pcsx2_hw_download_mode", layout.ps2HwDownloadMode)
            engine.setCoreOption("pcsx2_blending_accuracy", layout.ps2BlendingAccuracy)
            engine.setCoreOption("pcsx2_trilinear_filtering", layout.ps2Trilinear)
            engine.setCoreOption("pcsx2_anisotropic_filtering", layout.ps2Anisotropic)
            engine.setCoreOption("pcsx2_dithering", layout.ps2Dithering)
            engine.setCoreOption("pcsx2_mipmapping", layout.ps2Mipmapping)
            engine.setCoreOption("pcsx2_deinterlace_mode", layout.ps2Deinterlace)
            // ARMSX2 渲染增强 — TVShader / ShadeBoost / HalfPixelOffset /
            // TexturePreloading (live GS pokes, 由 Psx2Native 转发 native)
            engine.setCoreOption("pcsx2_tv_shader", layout.ps2TvShader)
            engine.setCoreOption("pcsx2_shade_boost", layout.ps2ShadeBoost)
            engine.setCoreOption("pcsx2_half_pixel_offset", layout.ps2HalfPixelOffset)
            engine.setCoreOption("pcsx2_texture_preloading", layout.ps2TexturePreloading)
            // 画面比例 —— 修复：PS2 始终 4:3、不跟随全局「画面缩放」的问题。
            //
            // 原因：ARMSX2 是推模型核心，它会在拿到的 Surface 内部按自己的
            // AspectRatio 配置自函黑边（RAuto4_3_3_2 → 永远 4:3）。旧代码直接把
            // ps2AspectRatio（默认 "auto"）透传给核心，全局 videoScale 只约束了
            // Surface 尺寸，核心仍然在里面函 4:3 黑边 → 表现为“始终 4:3”。
            //
            // 修复：PS2 专属设置为 "auto"（默认）时改为跟随全局画面缩放：
            //   4:3 / 16:9  → 直接映射到核心的同名比例（与 Surface 形状一致，填满）；
            //   stretch / custom / 2:3 / 8:3 / 3:2 / 8:7 → 映射为 Stretch(0)，
            //     核心拉满整个 Surface，形状交给 Compose 的 aspectRatio/自定义矩形控制
            //     （与其它平台“核心填满 Surface”的行为完全一致）。
            // 只有用户在 PS2 专属设置里显式锁定 4:3 / 16:9 时才优先专属设置。
            val effectivePs2Aspect = when (layout.ps2AspectRatio) {
                "4:3", "16:9" -> layout.ps2AspectRatio
                else -> when (layout.videoScale) {
                    "4:3" -> "4:3"
                    "16:9" -> "16:9"
                    else -> "stretch"   // stretch / custom / 其它比例 → 核心拉满 Surface
                }
            }
            engine.setCoreOption("pcsx2_aspect_ratio", effectivePs2Aspect)
            // Patches
            engine.setCoreOption("pcsx2_widescreen_patches", layout.ps2Widescreen)
            engine.setCoreOption("pcsx2_no_interlacing_patches", layout.ps2NoInterlace)
            // Performance — 速度作弊(可能破坏个别游戏)
            engine.setCoreOption("pcsx2_mtvu", layout.ps2Mtvu)
            engine.setCoreOption("pcsx2_instant_vu1", layout.ps2InstantVu1)
            engine.setCoreOption("pcsx2_ee_cycle_rate", layout.ps2EeCycleRate)
        }
        GamePlatform.JAVA -> {
            // J2ME settings are applied via Canvas static methods, not libretro core options.
            // Canvas 缩放语义: scaleType 0=原始分辨率 1=适应屏幕(保持比例) 2=全屏拉伸;
            // gravity: 0=左 1=上 2=居中 3=右 4=下。
            // (旧映射把 stretch→0/center→2 搞反了, 且 gravity 48 不在任何分支内)
            val scaleType = when (layout.javaScaleType) {
                "stretch" -> 2  // 全屏拉伸(不保持比例)
                "center"  -> 0  // 原始分辨率(不缩放)
                else      -> 1  // fit 适应屏幕保持比例
            }
            val scaleRatio = layout.javaScaleRatio.toIntOrNull()?.coerceIn(25, 400) ?: 100
            javax.microedition.lcdui.Canvas.setScale(2, scaleType, scaleRatio)
            javax.microedition.lcdui.Canvas.setShowFps(layout.javaShowFps)
            javax.microedition.lcdui.event.EventQueue.setImmediate(layout.javaImmediateMode)
            // 补全原 J2ME-Loader 设置里游戏会用到的其余项：
            // 虚拟分辨率（游戏逻辑分辨率，"default"=不干预，交由每游戏 config.json）
            if (engine is com.nesstation.app.core.engine.J2meEngine) {
                engine.applyVirtualResolution(layout.javaResolution)
                // 按键映射：虚拟手柄按键 → 手机按键（ABXY/START/SELECT/方向键）
                engine.setCustomKeyMap(layout.javaButtonKeyMap)
            }
            // 帧率限制（0 = 不限制）
            val fpsLimit = layout.javaFpsLimit.toIntOrNull() ?: 0
            javax.microedition.lcdui.Canvas.setLimitFps(fpsLimit)
            // 触摸输入支持（MIDlet hasPointerEvents() 的返回值）
            javax.microedition.lcdui.Canvas.setHasTouchInput(layout.javaTouchInput)
        }
    }
}

// ---------------------------------------------------------------------------
// GameSurfaceView
// ---------------------------------------------------------------------------
@Composable
private fun GameSurfaceView(
    engine: EmulatorEngine,
    videoScale: String,
    videoFilter: String,
    isPortrait: Boolean = false,
    platform: GamePlatform = GamePlatform.NES,
    currentPlayer: Int = 0,
    gamepadBitsHolder: IntArray = intArrayOf(0),
    uiBlocked: Boolean = false,
    onMenuToggle: () -> Unit = {},
    modifier: Modifier = Modifier,
    // Normalized 0..1 rect [left, top, right, bottom] used when videoScale == "custom"
    customRect: FloatArray = floatArrayOf(0f, 0f, 1f, 1f),
    // NDS 双屏自由布局：上屏/下屏各自的归一化目标矩形（videoScale == "custom" 且 NDS 时使用）
    ndsTopRect: FloatArray = floatArrayOf(0f, 0f, 1f, 0.48f),
    ndsBottomRect: FloatArray = floatArrayOf(0f, 0.52f, 1f, 1f),
    /**
     * 联机对战控制器。非 null 时，物理手柄 / 键盘的 pad 输入会通过它走帧同步，
     * 而不是直接 engine.setPad1。
     */
    netplayController: com.nesstation.app.battle.NetplayController? = null,
    // NDS 屏幕布局，用于动态计算宽高比 + 下屏触摸区域
    ndsScreenLayout: String = "Top/Bottom",
    // NDS 屏幕间距 (px, 0..20) —— 用于精确计算下屏在复合帧中的位置
    ndsScreenGapPx: Int = 0,
    // NDS OpenGL 渲染器是否启用（GL 合成帧固定 256x386，含 2px gap）
    ndsOpenGl: Boolean = false,
    // 追踪游戏视图在窗口中位置/尺寸的 Modifier（onGloballyPositioned）。
    // 声明在 EmulatorScreen 中，游戏视图两个分支（NdsDualScreenView /
    // SurfaceView）都要挂上，供手柄覆盖层的 GAME_AREA 触摸转发换算坐标。
    gameViewTracker: Modifier = Modifier
) {
    val ctx = LocalContext.current
    val isCustom = videoScale == "custom"
    // In custom layout mode the user controls position/size directly, so the
    // surface is anchored top-start and moved via offset; otherwise align the
    // game to top (portrait) or center (landscape).
    val contentAlignment = when {
        isCustom -> Alignment.TopStart
        isPortrait -> Alignment.TopCenter
        else -> Alignment.Center
    }
    // NDS 双屏动态宽高比：
    //   Top/Bottom, Bottom/Top → 2:3  (256x384)
    //   Left/Right, Right/Left → 8:3  (512x192)
    //   Top Only, Bottom Only  → 4:3  (256x192)
    //
    // NDS 双屏专门处理：每个屏幕原生就是 4:3（256x192）。合成帧把两屏并成
    // 一张图（256x384 等），若把这张合成帧整体拉伸到用户选的 4:3 / 16:9 /
    // 3:2 等比例，双屏会被当成一个整体挤压、每屏各自变形。因此 NDS 平台
    // 所有非 custom 的「画面缩放」都忽略所选整体比例，只按布局原生比例做
    // 等比适配 —— 两屏各自保持 4:3 等比放大填满，互不挤压。需要分别控制
    // 两屏大小/位置时用「自定义」布局（NdsDualScreenView，可分别缩放）。
    val effectiveVideoScale = if (platform == GamePlatform.NDS && videoScale != "custom") {
        when (ndsScreenLayout) {
            "Left/Right", "Right/Left" -> "8:3"
            "Top Only", "Bottom Only" -> "4:3"
            else -> "2:3" // Top/Bottom, Bottom/Top
        }
    } else {
        videoScale
    }
    BoxWithConstraints(modifier = modifier, contentAlignment = contentAlignment) {
        // NDS 双屏自由布局：videoScale == "custom" 时投屏改为 NdsDualScreenView，
        // 从 engine.frameBuffer 按布局切出上/下屏绘制到两个独立矩形；不做 native
        // surface blit（engine 不设置 surface，cb_video 的 blit 会因 window 为空跳过）。
        val isNdsCustom = platform == GamePlatform.NDS && isCustom
        if (isNdsCustom) {
            AndroidView(
                factory = { ctx ->
                    NdsDualScreenView(ctx).apply {
                        this.uiBlocked = uiBlocked
                        this.videoFilter = videoFilter
                        setRects(ndsTopRect, ndsBottomRect)
                        // 设置焦点以便接收物理手柄 / 键盘按键
                        isFocusable = true
                        isFocusableInTouchMode = true
                        requestFocus()
                        // NDS custom 模式仍保留实体按键路由（与 SurfaceView 分支一致）
                        setOnKeyListener { v, keyCode, event ->
                            if (uiBlocked) {
                                false
                            } else {
                                val bits = resolveKeyBits(keyCode, platform, currentPlayer, ctx)
                                if (bits != 0) {
                                    when (event.action) {
                                        KeyEvent.ACTION_DOWN -> {
                                            gamepadBitsHolder[0] = gamepadBitsHolder[0] or bits
                                            routePadBits(engine, currentPlayer, gamepadBitsHolder[0], netplayController, platform)
                                            true
                                        }
                                        KeyEvent.ACTION_UP -> {
                                            gamepadBitsHolder[0] = gamepadBitsHolder[0] and bits.inv()
                                            routePadBits(engine, currentPlayer, gamepadBitsHolder[0], netplayController, platform)
                                            true
                                        }
                                        else -> false
                                    }
                                } else if (event.action == KeyEvent.ACTION_DOWN &&
                                           (keyCode == KeyEvent.KEYCODE_MENU ||
                                            keyCode == KeyEvent.KEYCODE_BACK)) {
                                    onMenuToggle()
                                    true
                                } else {
                                    false
                                }
                            }
                        }
                    }
                },
                update = { v ->
                    v.engine = engine as? com.nesstation.app.core.engine.NdsEngine
                    v.screenLayout = ndsScreenLayout
                    v.uiBlocked = uiBlocked
                    v.videoFilter = videoFilter
                    v.setRects(ndsTopRect, ndsBottomRect)
                    // 与 SurfaceView 分支相同：uiBlocked 变化/重连焦点时修正按键状态
                    if (uiBlocked && gamepadBitsHolder[0] != 0) {
                        gamepadBitsHolder[0] = 0
                        routePadBits(engine, currentPlayer, 0, netplayController, platform)
                    }
                    if (!uiBlocked) {
                        v.isFocusable = true
                        v.isFocusableInTouchMode = true
                        v.requestFocus()
                    }
                },
                modifier = Modifier.fillMaxSize().then(gameViewTracker)
            )
        } else {
        val surfaceModifier = when (effectiveVideoScale) {
            "4:3" -> Modifier.aspectRatio(4f / 3f)
            "2:3" -> Modifier.aspectRatio(2f / 3f)   // NDS 上下双屏 (256x384)
            "8:3" -> Modifier.aspectRatio(8f / 3f)   // NDS 左右双屏 (512x192)
            "3:2" -> Modifier.aspectRatio(3f / 2f)   // GBA 原生比例 (240x160)
            "8:7" -> Modifier.aspectRatio(8f / 7f)
            "16:9" -> Modifier.aspectRatio(16f / 9f)
            "custom" -> {
                val maxW = constraints.maxWidth
                val maxH = constraints.maxHeight
                val leftPx = (customRect[0] * maxW).toInt().coerceIn(0, maxW)
                val topPx = (customRect[1] * maxH).toInt().coerceIn(0, maxH)
                val wPx = ((customRect[2] - customRect[0]) * maxW).toInt().coerceIn(1, maxW)
                val hPx = ((customRect[3] - customRect[1]) * maxH).toInt().coerceIn(1, maxH)
                val density = LocalDensity.current
                Modifier
                    .offset { IntOffset(leftPx, topPx) }
                    .size(width = with(density) { wPx.toDp() }, height = with(density) { hPx.toDp() })
            }
            else -> Modifier.fillMaxSize() // stretch (default)
        }
        AndroidView(
            factory = { ctx ->
                SurfaceView(ctx).apply {
                    // Set the pixel format BEFORE registering the surface
                    // callback — otherwise the first surfaceCreated may fire
                    // with the default OPAQUE format and the first frame will
                    // render with the wrong format before being recreated.
                    holder.setFormat(android.graphics.PixelFormat.RGBX_8888)
                    // Lifecycle: surfaceCreated attaches, surfaceChanged
                    // notifies the engine of the real size (PS2/ARMSX2 needs
                    // it to trigger MTGS::UpdateDisplayWindow — without a
                    // positive size the GS thread keeps presenting to an
                    // abandoned BufferQueue: black screen, audio OK), and
                    // surfaceDestroyed gives the engine a chance to release
                    // its swapchain. Default engine implementations are no-ops.
                    holder.addCallback(object : SurfaceHolder.Callback {
                        override fun surfaceCreated(holder: SurfaceHolder) {
                            engine.setSurface(holder.surface)
                        }
                        override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                            engine.onSurfaceChanged(holder.surface, width, height)
                        }
                        override fun surfaceDestroyed(holder: SurfaceHolder) {
                            engine.onSurfaceDestroyed()
                            engine.setSurface(null)
                        }
                    })
                    // NDS touchscreen input: capture touch events on the
                    // game surface and forward them to the engine.
                    //
                    // 官方 melonDS 架构：按屏幕布局把触点直接映射为 DS 下屏
                    // 像素坐标 (0..255, 0..191)，经 setTouchInputDirect 注入，
                    // 不再经过复合帧归一化坐标的间接层 —— 布局/间距/GL gap
                    // 不会破坏映射。Hybrid 布局的小屏几何只有核心知道，
                    // 保留旧的 POINTER 归一化路径。
                    if (platform == GamePlatform.NDS) {
                        setOnTouchListener { v, event ->
                            val ndsEngine = (engine as? com.nesstation.app.core.engine.NdsEngine) ?: return@setOnTouchListener false
                            handleNdsTouch(ndsEngine, event, ndsScreenLayout, ndsScreenGapPx, ndsOpenGl, v.width, v.height)
                        }
                    }
                    // Make the SurfaceView focusable so it receives physical
                    // gamepad / D-pad key events on TV and when a Bluetooth
                    // controller is connected.
                    isFocusable = true
                    isFocusableInTouchMode = true
                    requestFocus()
                    // Physical gamepad / D-pad key routing via Android's
                    // View.OnKeyListener. This is more reliable than Compose's
                    // onKeyEvent across different Compose versions.
                    //
                    // IMPORTANT: when uiBlocked is true (menu / dialog / settings
                    // is open), we do NOT consume gamepad keys here — they must
                    // propagate to the Compose UI so the user can navigate the
                    // menu with the D-pad. Only the Back/Menu key is handled
                    // here to toggle the menu open/closed.
                    setOnKeyListener { _, keyCode, event ->
                        if (uiBlocked) {
                            // UI is blocking — let Compose handle all keys
                            // (including Back, which the BackHandler will catch).
                            false
                        } else {
                            val bits = resolveKeyBits(keyCode, platform, currentPlayer, ctx)
                            if (bits != 0) {
                                when (event.action) {
                                    KeyEvent.ACTION_DOWN -> {
                                        gamepadBitsHolder[0] = gamepadBitsHolder[0] or bits
                                        routePadBits(engine, currentPlayer, gamepadBitsHolder[0], netplayController, platform)
                                        true
                                    }
                                    KeyEvent.ACTION_UP -> {
                                        gamepadBitsHolder[0] = gamepadBitsHolder[0] and bits.inv()
                                        routePadBits(engine, currentPlayer, gamepadBitsHolder[0], netplayController, platform)
                                        true
                                    }
                                    else -> false
                                }
                            } else if (event.action == KeyEvent.ACTION_DOWN &&
                                       (keyCode == KeyEvent.KEYCODE_MENU ||
                                        keyCode == KeyEvent.KEYCODE_BACK)) {
                                onMenuToggle()
                                true
                            } else {
                                false
                            }
                        }
                    }
                }
            },
            update = { sv ->
                // Re-bind the key listener whenever uiBlocked changes so the
                // closure captures the latest value.
                sv.setOnKeyListener { _, keyCode, event ->
                    val bits = resolveKeyBits(keyCode, platform, currentPlayer, ctx)
                    if (uiBlocked) {
                        // UI is blocking — let Compose handle D-pad navigation.
                        // But still process KEYUP for gamepad buttons so that
                        // any button held when the menu opened gets released
                        // (prevents stuck buttons when menu closes).
                        if (bits != 0 && event.action == KeyEvent.ACTION_UP) {
                            gamepadBitsHolder[0] = gamepadBitsHolder[0] and bits.inv()
                            routePadBits(engine, currentPlayer, gamepadBitsHolder[0], netplayController, platform)
                        }
                        // Don't consume — let Compose UI navigate
                        false
                    } else {
                        // Also ensure any stale button bits are cleared on
                        // KEYUP even if they weren't tracked as DOWN (e.g.
                        // menu just closed while button was held).
                        if (bits != 0 && event.action == KeyEvent.ACTION_UP) {
                            gamepadBitsHolder[0] = gamepadBitsHolder[0] and bits.inv()
                            routePadBits(engine, currentPlayer, gamepadBitsHolder[0], netplayController, platform)
                            true
                        } else if (bits != 0 && event.action == KeyEvent.ACTION_DOWN) {
                            gamepadBitsHolder[0] = gamepadBitsHolder[0] or bits
                            routePadBits(engine, currentPlayer, gamepadBitsHolder[0], netplayController, platform)
                            true
                        } else if (event.action == KeyEvent.ACTION_DOWN &&
                                   (keyCode == KeyEvent.KEYCODE_MENU ||
                                    keyCode == KeyEvent.KEYCODE_BACK)) {
                            onMenuToggle()
                            true
                        } else {
                            false
                        }
                    }
                }
                // When UI becomes blocked, release ALL held gamepad buttons
                // so the game doesn't think buttons are stuck down.
                if (uiBlocked && gamepadBitsHolder[0] != 0) {
                    gamepadBitsHolder[0] = 0
                    routePadBits(engine, currentPlayer, 0, netplayController, platform)
                }
                // When UI becomes unblocked (menu closed), re-request focus
                // so the SurfaceView can receive gamepad keys again.
                if (!uiBlocked) {
                    sv.isFocusable = true
                    sv.isFocusableInTouchMode = true
                    sv.requestFocus()
                }
                // NDS touchscreen: update touch listener in case the engine
                // instance changed (defensive — normally it's the same engine).
                if (platform == GamePlatform.NDS) {
                    sv.setOnTouchListener { v, event ->
                        val ndsEngine = (engine as? com.nesstation.app.core.engine.NdsEngine) ?: return@setOnTouchListener false
                        handleNdsTouch(ndsEngine, event, ndsScreenLayout, ndsScreenGapPx, ndsOpenGl, v.width, v.height)
                    }
                }
            },
            modifier = surfaceModifier.then(gameViewTracker)
        )
            // GPU-accelerated filter overlay — scanline/CRT/dot/*+dot drawn by Compose
            if (videoFilter in listOf("scanline", "crt", "dot", "xbr_dot", "4xbr_dot", "hq4x_dot")) {
                FilterOverlay(
                    if (videoFilter.endsWith("_dot")) "dot" else videoFilter,
                    surfaceModifier
                )
            }
        }
    }
}

// NDS 触摸 —— 官方 melonDS 架构的直接像素映射
// ---------------------------------------------------------------------------
// 把游戏视图上的触点按当前屏幕布局换算成 DS 下屏像素坐标
// (x: 0..255, y: 0..191) 并经 setTouchInputDirect 注入。与旧的
// "复合帧归一化坐标" 路径相比，这条链路不依赖复合帧几何 —— 布局切换、
// 屏幕间距 (melonds_screen_gap)、GL 渲染器的 2px gap 都不会破坏映射。
//
// 下屏在游戏视图（与复合帧等比）中的归一化区域：
//   Top/Bottom  : y ∈ [(192+gap)/(384+gap), 1]，GL 固定 [194/386, 1]
//   Bottom/Top  : y ∈ [0, 192/(384+gap)]，GL 固定 [0, 192/386]
//   Left/Right  : x ∈ [0.5, 1]（水平布局无间距）
//   Right/Left  : x ∈ [0, 0.5]
//   Bottom Only : 全屏
//   Hybrid *    : 小屏几何只有核心知道 → 走旧 POINTER 路径
// 触点落在上屏区域时释放触摸（与核心旧行为一致）。
//
// mapNormalizedToDsTouch 是共享映射内核：
//   - SurfaceView 的 onTouch（手柄隐藏时事件直达视图）
//   - 虚拟手柄覆盖层的转发（手柄可见时 Compose 命中测试拦截了视图的
//     事件，见 OnScreenController.onUnhandledTouch）
// ---------------------------------------------------------------------------
private fun mapNormalizedToDsTouch(
    ndsEngine: com.nesstation.app.core.engine.NdsEngine,
    nx: Float,
    ny: Float,
    screenLayout: String,
    screenGapPx: Int,
    openGl: Boolean
) {
    // 计算下屏在 view 中的归一化矩形 (left, top, right, bottom)
    val bottomRect: FloatArray? = when (screenLayout) {
        "Top/Bottom" -> {
            // 软件路径复合帧 256x(384+gap)；GL 固定 256x386 (gap=2)
            val bufH = if (openGl) 386f else (384f + screenGapPx.coerceIn(0, 20))
            val gap = if (openGl) 2 else screenGapPx.coerceIn(0, 20)
            floatArrayOf(0f, (192f + gap) / bufH, 1f, 1f)
        }
        "Bottom/Top" -> {
            val bufH = if (openGl) 386f else (384f + screenGapPx.coerceIn(0, 20))
            floatArrayOf(0f, 0f, 1f, 192f / bufH)
        }
        "Left/Right" -> floatArrayOf(0.5f, 0f, 1f, 1f)
        "Right/Left" -> floatArrayOf(0f, 0f, 0.5f, 1f)
        "Bottom Only" -> floatArrayOf(0f, 0f, 1f, 1f)
        else -> null // "Top Only"（核心忽略触摸）与 Hybrid（走旧路径）
    }

    if (bottomRect != null) {
        if (nx >= bottomRect[0] && nx < bottomRect[2] &&
            ny >= bottomRect[1] && ny < bottomRect[3]) {
            // 触点在下屏内 → 直接线性映射为下屏像素坐标
            val t = (nx - bottomRect[0]) / (bottomRect[2] - bottomRect[0])
            val s = (ny - bottomRect[1]) / (bottomRect[3] - bottomRect[1])
            val px = (t * 255.5f).toInt().coerceIn(0, 255)
            val py = (s * 191.5f).toInt().coerceIn(0, 191)
            ndsEngine.setTouchInputDirect(px, py, true)
        } else {
            // 触在上屏 / gap 区域 → 释放触摸
            ndsEngine.setTouchInputDirect(0, 0, false)
        }
    } else if (screenLayout == "Hybrid Top" || screenLayout == "Hybrid Bottom") {
        // Hybrid 布局：小屏位置只有核心知道 —— 走旧 POINTER 归一化路径
        val x = (nx * 0xFFFF - 0x8000).toInt().coerceIn(-0x8000, 0x7FFF)
        val y = (ny * 0xFFFF - 0x8000).toInt().coerceIn(-0x8000, 0x7FFF)
        ndsEngine.setTouchInput(x, y, true)
    } else {
        // "Top Only" —— 无下屏，释放
        ndsEngine.setTouchInputDirect(0, 0, false)
    }
}

private fun handleNdsTouch(
    ndsEngine: com.nesstation.app.core.engine.NdsEngine,
    event: MotionEvent,
    screenLayout: String,
    screenGapPx: Int,
    openGl: Boolean,
    viewW: Int,
    viewH: Int
): Boolean {
    when (event.actionMasked) {
        MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
            val w = viewW.coerceAtLeast(1)
            val h = viewH.coerceAtLeast(1)
            val nx = event.x / w   // 0..1 within the (aspect-matched) view
            val ny = event.y / h
            mapNormalizedToDsTouch(ndsEngine, nx, ny, screenLayout, screenGapPx, openGl)
            return true
        }
        MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
            // 两条路径都释放，确保任何布局下触摸状态都被清干净
            ndsEngine.setTouchInput(0, 0, false)
            ndsEngine.setTouchInputDirect(0, 0, false)
            return true
        }
        else -> return false
    }
}

/**
 * Hosts the J2ME Canvas's [Displayable] view inside EmulatorScreen's
 * Compose hierarchy.
 *
 * J2ME is fundamentally different from the libretro-based engines: there is
 * no native core, no continuous emulation loop, and no direct frame buffer.
 * The MIDlet renders to its own SurfaceView (inside Canvas.getDisplayableView()),
 * and switches between Displayables (Canvas / Form / List / Alert / TextBox)
 * at runtime. This composable:
 *
 * 1. Polls [J2meEngine.getDisplayableView] every 50 ms for the current
 *     Displayable's Android View.
 * 2. Hosts it inside a [FrameLayout] via [AndroidView].
 * 3. Swaps the child view when the MIDlet switches screens (e.g. game
 *     Canvas -> pause Form -> back to Canvas).
 *
 * The polling is intentional: the view is created on the main looper by
 * [J2meEngine.setCurrent], and Compose has no push-based notification
 * channel into the J2ME runtime. A 50 ms poll is fast enough that the user
 * never sees a stale screen, and cheap enough to run continuously.
 */
@Composable
private fun J2meGameView(
    engine: J2meEngine,
    modifier: Modifier = Modifier
) {
    var viewSwapKey by remember { androidx.compose.runtime.mutableIntStateOf(0) }

    androidx.compose.runtime.LaunchedEffect(engine) {
        var lastView: android.view.View? = engine.getDisplayableView()
        while (true) {
            kotlinx.coroutines.delay(50)
            val current = engine.getDisplayableView()
            if (current != lastView) {
                lastView = current
                viewSwapKey++ // forces AndroidView recreation with new view
            }
        }
    }

    androidx.compose.runtime.key(viewSwapKey) {
        androidx.compose.ui.viewinterop.AndroidView(
            factory = { context ->
                android.widget.FrameLayout(context).apply {
                    layoutParams = android.widget.FrameLayout.LayoutParams(
                        android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                        android.widget.FrameLayout.LayoutParams.MATCH_PARENT
                    )
                    engine.getDisplayableView()?.let { view ->
                        addView(view, android.widget.FrameLayout.LayoutParams(
                            android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                            android.widget.FrameLayout.LayoutParams.MATCH_PARENT
                        ))
                    }
                }
            },
            update = { frameLayout ->
                val view = engine.getDisplayableView()
                val child = if (frameLayout.childCount > 0) frameLayout.getChildAt(0) else null
                if (view != child) {
                    frameLayout.removeAllViews()
                    view?.let {
                        frameLayout.addView(it, android.widget.FrameLayout.LayoutParams(
                            android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                            android.widget.FrameLayout.LayoutParams.MATCH_PARENT
                        ))
                    }
                }
            },
            modifier = modifier
        )
    }
}

// GPU-accelerated filter overlay using BitmapShader — a single GPU texture
// draw instead of hundreds of individual drawLine calls.
// The pattern bitmap is small (1x3 or 3x3) and tiled via REPEAT mode.
@Composable
private fun FilterOverlay(
    filterType: String,
    modifier: Modifier = Modifier
) {
    // Pre-create the pattern bitmap once per filter type
    val patternBitmap = remember(filterType) {
        when (filterType) {
            "scanline" -> NdsFilterPatterns.createScanlinePattern()
            "crt" -> NdsFilterPatterns.createCrtPattern()
            "dot" -> NdsFilterPatterns.createDotPattern()
            else -> null
        }
    }
    // Pre-create the shader + paint once per filter type too — allocating a
    // BitmapShader + Paint on every Canvas redraw (60 times/second) was a
    // significant per-frame allocation hotspot.
    val shaderPaint = remember(filterType, patternBitmap) {
        patternBitmap?.let { bmp ->
            android.graphics.Paint().apply {
                shader = BitmapShader(bmp, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
                isFilterBitmap = false
                isAntiAlias = false
            }
        }
    }

    Canvas(modifier = modifier) {
        shaderPaint?.let { paint ->
            drawIntoCanvas { canvas ->
                canvas.nativeCanvas.drawRect(0f, 0f, size.width, size.height, paint)
            }
        }
        // CRT vignette — radial gradient darkening at edges
        if (filterType == "crt") {
            drawRect(
                brush = Brush.radialGradient(
                    colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.35f)),
                    center = Offset(size.width / 2, size.height / 2),
                    radius = minOf(size.width, size.height) * 0.7f
                )
            )
        }
    }
}

// Scanline / CRT / Dot 图案生成函数已迁移到 NdsFilterPatterns 共享对象
// （NdsDualScreenView.kt），SurfaceView 分支 (FilterOverlay) 与 NDS 自由布局
// 分支 (NdsDualScreenView) 使用完全相同的图案，保证各缩放模式下滤镜观感一致。

// ---------------------------------------------------------------------------
// Combo buttons — single on-screen button that activates multiple pad bits
// ---------------------------------------------------------------------------
// Example: "AB" combo button → pressing it sets bits BTN_A|BTN_B = 0x03.
// Used for simultaneous button presses that are awkward on a touchscreen
// (e.g. A+B for slide/dash in NES/MD games, L+R for special moves in SNES).

/**
 * A single combo button definition. [bits] is the OR'd bit mask of all
 * pad bits this combo activates when pressed (e.g. BTN_A or BTN_B = 0x03).
 */
data class ComboButtonEntry(
    val id: String,
    val label: String,
    val bits: Int,
    val x: Float,
    val y: Float,
    val sizeDp: Int,
    val color: Int          // ARGB int (e.g. 0xFFE74C3C)
)

/**
 * Parse the per-platform combo button JSON from PadLayout into a list of
 * [ComboButtonEntry]. Returns empty list on parse error or empty JSON.
 */
private fun parseComboButtons(padLayout: PadLayout, platform: GamePlatform): List<ComboButtonEntry> {
    val json = when (platform) {
        GamePlatform.NES    -> padLayout.comboButtons
        GamePlatform.SFC    -> padLayout.comboButtonsSfc
        GamePlatform.GB     -> padLayout.comboButtons      // GB shares NES combos
        GamePlatform.GBA    -> padLayout.comboButtonsGba
        GamePlatform.ARCADE -> padLayout.comboButtonsArcade
        GamePlatform.MD     -> padLayout.comboButtonsMd
        GamePlatform.PCE    -> padLayout.comboButtonsPce
        GamePlatform.DOS    -> ""
        GamePlatform.NDS    -> padLayout.comboButtonsSfc  // NDS uses SNES-style combos
        GamePlatform.PSX    -> padLayout.comboButtonsSfc  // PSX uses SNES-style combos
        GamePlatform.PS2    -> padLayout.comboButtonsSfc  // PS2 uses SNES-style combos
        GamePlatform.DC     -> padLayout.comboButtonsSfc  // DC uses SNES-style combos
        GamePlatform.JAVA   -> ""
    }
    if (json.isBlank()) return emptyList()
    return try {
        val arr = org.json.JSONArray(json)
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            ComboButtonEntry(
                id = o.optString("id", "combo$i"),
                label = o.optString("label", "AB"),
                bits = o.optInt("bits", 0),
                x = o.optDouble("x", 0.5).toFloat(),
                y = o.optDouble("y", 0.85).toFloat(),
                sizeDp = o.optInt("size", 56),
                color = o.optInt("color", 0xFF9C27B0.toInt())
            )
        }
    } catch (_: Exception) {
        emptyList()
    }
}

/** Serialize a list of [ComboButtonEntry] back to JSON for persistence. */
private fun serializeComboButtons(list: List<ComboButtonEntry>): String {
    if (list.isEmpty()) return ""
    val arr = org.json.JSONArray()
    for (c in list) {
        val o = org.json.JSONObject()
        o.put("id", c.id)
        o.put("label", c.label)
        o.put("bits", c.bits)
        o.put("x", c.x.toDouble())
        o.put("y", c.y.toDouble())
        o.put("size", c.sizeDp)
        o.put("color", c.color)
        arr.put(o)
    }
    return arr.toString()
}

// ---------------------------------------------------------------------------
// PlayerSwitchButton — 小圆形可拖动悬浮球，点击切换 1P/2P/3P/4P
// ---------------------------------------------------------------------------
/**
 * 玩家切换悬浮球。
 *
 * - 小圆形（32dp），不再占用大块屏幕
 * - 拖动 = 移动位置（松手后经 [onMove] 持久化到 PadLayout，横竖屏共用）
 * - 点击 = 切换到下一个玩家
 * - 显示/隐藏由设置里的"玩家切换按钮"开关控制（padLayout.showPlayerSwitch）
 *
 * 位置以归一化坐标（0..1）存储，渲染时换算为相对游戏区域容器的像素偏移；
 * 拖动期间读取当前指针位移增量，兼容任意起始位置。
 */
@Composable
private fun BoxScope.PlayerSwitchButton(
    currentPlayer: Int,
    surfaceSize: IntSize,
    initialX: Float,
    initialY: Float,
    onSwitch: () -> Unit,
    onMove: (Float, Float) -> Unit
) {
    val label = "${currentPlayer + 1}P"
    val color = when (currentPlayer) {
        0 -> Color(0xFF4A90D9)  // blue for P1
        1 -> Color(0xFFE74C3C)  // red for P2
        2 -> Color(0xFF2ECC71)  // green for P3
        3 -> Color(0xFFF39C12)  // orange for P4
        else -> Color(0xFF4A90D9)
    }

    // 本地归一化位置（0..1）。key 在 initialX/Y 上：外部位置重置（如换设备/
    // 恢复默认）时重新初始化；本地拖动只改 px/py，不触发重建。
    var px by remember(initialX) { mutableStateOf(initialX) }
    var py by remember(initialY) { mutableStateOf(initialY) }
    // 拖动期间禁用点击（避免松手时误触发切换）
    var dragging by remember { mutableStateOf(false) }

    val buttonSize = 32.dp
    val sizePx = with(LocalDensity.current) { buttonSize.toPx() }

    Box(
        modifier = Modifier
            .align(Alignment.TopStart)
            .offset {
                androidx.compose.ui.unit.IntOffset(
                    x = ((px * surfaceSize.width - sizePx / 2f)
                        .coerceIn(0f, (surfaceSize.width - sizePx).coerceAtLeast(0f))).toInt(),
                    y = ((py * surfaceSize.height - sizePx / 2f)
                        .coerceIn(0f, (surfaceSize.height - sizePx).coerceAtLeast(0f))).toInt()
                )
            }
            .size(buttonSize)
            .background(color.copy(alpha = 0.85f), androidx.compose.foundation.shape.CircleShape)
            .pointerInput(Unit) {
                detectTapGestures {
                    if (!dragging) onSwitch()
                }
            }
            .pointerInput(surfaceSize) {
                detectDragGestures(
                    onDragStart = { dragging = true },
                    onDragEnd = {
                        dragging = false
                        // 松手时持久化（clamp 到安全范围，保证球心不出屏）
                        val nx = px.coerceIn(0.03f, 0.97f)
                        val ny = py.coerceIn(0.03f, 0.97f)
                        px = nx; py = ny
                        onMove(nx, ny)
                    },
                    onDragCancel = {
                        dragging = false
                        onMove(px.coerceIn(0.03f, 0.97f), py.coerceIn(0.03f, 0.97f))
                    }
                ) { change, dragAmount ->
                    change.consume()
                    if (surfaceSize.width > 0 && surfaceSize.height > 0) {
                        px = (px + dragAmount.x / surfaceSize.width).coerceIn(0.03f, 0.97f)
                        py = (py + dragAmount.y / surfaceSize.height).coerceIn(0.03f, 0.97f)
                    }
                }
            },
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            color = Color.White,
            fontSize = 11.sp,
            fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
        )
    }
}

// ---------------------------------------------------------------------------
// OnScreenController — SINGLE pointerInput for true multi-touch
// ---------------------------------------------------------------------------
@Composable
fun OnScreenController(
    padLayout: PadLayout,
    surfaceSize: IntSize,
    onPadBits: (Int) -> Unit,
    platform: GamePlatform = GamePlatform.NES,
    isPortrait: Boolean = false,
    /**
     * PS2 双摇杆模拟轴回调（仅 PS2 使用）。值为归一化 -1..1（X 右正、Y 下正），
     * 由调用方转换为 int16 libretro 轴值推给核心。
     */
    onAnalogAxes: ((lx: Float, ly: Float, rx: Float, ry: Float) -> Unit)? = null,
    /**
     * 即时存档 / 即时读档按钮回调。在对应按钮被触摸按下时触发一次，
     * 由调用方执行 engine.saveState / engine.loadState 并给出 Toast 反馈。
     */
    onQuickSave: (() -> Unit)? = null,
    onQuickLoad: (() -> Unit)? = null,
    /**
     * Forwarder for touches that land on NO pad button (i.e. on the game
     * screen area). Receives the pointer position in ROOT coordinates plus
     * the action (MotionEvent.ACTION_DOWN / ACTION_MOVE / ACTION_UP).
     *
     * Needed for the NDS touchscreen: the pad overlay's full-screen
     * pointerInput Box is a higher-z sibling of the game view and Compose
     * hit-testing does NOT continue to lower siblings unless
     * shareWithSiblings is set (no public API in Compose 1.6), so the
     * AndroidView (SurfaceView / NdsDualScreenView) never sees touches
     * while the pad is visible. The pad is the ONLY place that reliably
     * sees every touch, so unhandled ones are forwarded from here.
     */
    onUnhandledTouch: ((rootPos: androidx.compose.ui.geometry.Offset, action: Int) -> Unit)? = null,
    /**
     * 画面遮罩 / 按钮主题（每核心独立，来自 PadLayout.overlayThemeJson）。
     * 为 null 的字段表示使用核心默认配色。
     */
    overlayTheme: com.nesstation.app.core.storage.OverlayTheme = com.nesstation.app.core.storage.OverlayTheme()
) {
    val density = LocalDensity.current
    val opacity = padLayout.opacity
    val isPs2 = platform == GamePlatform.PS2

    // Which extra buttons to show based on platform.
    // SNES / ARCADE / MD / PCE: 6-button layout — show all of A/B/X/Y/L/R.
    // GBA: 4 face buttons — show L/R but no X/Y.
    // NES / GB: only A/B + Start/Select.
    //
    // PCE button mapping (per Geargrafx reference source libretro.cpp):
    //   bit0 (BTN_A)   → PCE I    (A button label)
    //   bit1 (BTN_B)   → PCE II   (B button label)
    //   bit8 (BTN_X)   → PCE IV   (X button label)
    //   bit9 (BTN_Y)   → PCE III  (Y button label)
    //   bit10 (BTN_L)  → PCE V    (L button label)
    //   bit11 (BTN_R)  → PCE VI   (R button label)
    //   bit12 (BTN_L2) → Toggle Turbo II
    //   bit13 (BTN_R2) → Toggle Turbo I
    // PCE uses the SNES/ARCADE/MD bit layout (L/R on bit10/11), not GBA.
    val showLR = platform == GamePlatform.GBA || platform == GamePlatform.SFC ||
                 platform == GamePlatform.ARCADE || platform == GamePlatform.MD ||
                 platform == GamePlatform.PCE || platform == GamePlatform.NDS ||
                 platform == GamePlatform.PSX || platform == GamePlatform.PS2 ||
                 platform == GamePlatform.DC
    val showXY = platform == GamePlatform.SFC ||
                 platform == GamePlatform.ARCADE || platform == GamePlatform.MD ||
                 platform == GamePlatform.PCE || platform == GamePlatform.NDS ||
                 platform == GamePlatform.PSX || platform == GamePlatform.PS2 ||
                 platform == GamePlatform.DC
    // L2/R2 (Turbo toggle for PCE, L2/R2 for PSX) — show for ARCADE when explicitly enabled,
    // always for PCE (PCE has turbo toggle as a standard feature), and for PSX
    // (DualShock L2/R2 mapped to libretro bits 12/13).
    val showL2R2 = (platform == GamePlatform.ARCADE && padLayout.arcadeShowL2R2) ||
                   platform == GamePlatform.PCE ||
                   platform == GamePlatform.PSX || platform == GamePlatform.PS2
    // PS2 专属：L3/R3（摇杆按下）小按钮，可在显隐对话框里关闭
    val showL3Btn = isPs2 && !PadLayoutStore.isButtonHidden(padLayout, platform, "l3")
    val showR3Btn = isPs2 && !PadLayoutStore.isButtonHidden(padLayout, platform, "r3")

    // === Per-button visibility for ALL platforms ===
    // Each platform can independently hide/show individual buttons via the
    // "显隐按键" dialog in the pad layout editor. PCE uses legacy pceShow*
    // booleans; all other platforms use hiddenButtons* comma-separated strings.
    // The helper function PadLayoutStore.isButtonHidden() handles both cases.
    // 即时存档 / 即时读档按钮（所有核心通用，可在显隐对话框里开关）。
    val showQuickSaveBtn = !PadLayoutStore.isButtonHidden(padLayout, platform, "qs")
    val showQuickLoadBtn = !PadLayoutStore.isButtonHidden(padLayout, platform, "ql")
    val showDpadBtn = !PadLayoutStore.isButtonHidden(padLayout, platform, "dpad")
    val showABtn = !PadLayoutStore.isButtonHidden(padLayout, platform, "a")
    val showBBtn = !PadLayoutStore.isButtonHidden(padLayout, platform, "b")
    val showStartBtn = !PadLayoutStore.isButtonHidden(padLayout, platform, "start")
    val showSelectBtn = !PadLayoutStore.isButtonHidden(padLayout, platform, "select")
    val showLBtn = !PadLayoutStore.isButtonHidden(padLayout, platform, "l")
    val showRBtn = !PadLayoutStore.isButtonHidden(padLayout, platform, "r")
    val showXBtn = !PadLayoutStore.isButtonHidden(padLayout, platform, "x")
    val showYBtn = !PadLayoutStore.isButtonHidden(padLayout, platform, "y")
    val showL2Btn = !PadLayoutStore.isButtonHidden(padLayout, platform, "l2")
    val showR2Btn = !PadLayoutStore.isButtonHidden(padLayout, platform, "r2")
    // 连发 A/B 仅在支持的平台提供（NES/GB/GBA/SFC/MD/NDS/街机）。
    // PCE 已有专用 TURBO I/II 切换键（L2/R2），PSX 不提供连发 —— 都不渲染。
    val supportsTurboAB = platform == GamePlatform.NES || platform == GamePlatform.GB ||
                          platform == GamePlatform.GBA || platform == GamePlatform.SFC ||
                          platform == GamePlatform.MD || platform == GamePlatform.NDS ||
                          platform == GamePlatform.ARCADE
    val showTurboABtn = supportsTurboAB && !PadLayoutStore.isButtonHidden(padLayout, platform, "ta")
    val showTurboBBtn = supportsTurboAB && !PadLayoutStore.isButtonHidden(padLayout, platform, "tb")

    // === Input mode: D-Pad vs Analog Stick (all platforms) ===
    // When inputMode == "analog", we render a circular analog stick
    // instead of the cross-shaped D-Pad. Both produce the same BTN_UP/DOWN/
    // LEFT/RIGHT bits — the difference is purely visual + how direction is
    // computed (analog uses thumb position relative to center, with a
    // deadzone; D-Pad uses quadrant hit-test). Arcade uses its legacy
    // arcadeInputMode field; all other platforms use the global inputMode.
    val useAnalogStick = PadLayoutStore.getInputMode(padLayout, platform) == "analog"
    // Track analog thumb offset (in fraction of stick radius, -1..1 on each axis)
    // for rendering. Updated by the analog gesture handler below.
    var analogThumbX by remember { mutableStateOf(0f) }
    var analogThumbY by remember { mutableStateOf(0f) }

    // 即时存档 / 即时读档按钮回调用 rememberUpdatedState 包装，
    // 手势协程重启期间始终拿到最新 lambda
    val currentOnQuickSave by rememberUpdatedState(onQuickSave)
    val currentOnQuickLoad by rememberUpdatedState(onQuickLoad)

    // === PS2 双摇杆状态 ===
    // 左/右摇杆的拇指位置（-1..1），拖动时更新并经 onAnalogAxes 回调输出
    // 真实模拟轴；同时超阈值方向会置对应数字方向位（兼容纯数字游戏）。
    var lStickTX by remember { mutableStateOf(0f) }
    var lStickTY by remember { mutableStateOf(0f) }
    var rStickTX by remember { mutableStateOf(0f) }
    var rStickTY by remember { mutableStateOf(0f) }
    // 摇杆自身的方向位（仅用于摇杆箭头高亮，独立于 visualState 的 D-pad 数字位）。
    // 摇杆只输出模拟轴（LX/LY、RX/RY），数字方向由十字键独占 —— 三者互不串联。
    var lStickDirs by remember { mutableIntStateOf(0) }
    var rStickDirs by remember { mutableIntStateOf(0) }
    // 回调用 rememberUpdatedState 包装，手势协程重启期间始终拿到最新 lambda
    val currentOnAnalogAxes by rememberUpdatedState(onAnalogAxes)

    // L/R bit values differ between GBA (bit8/9) and SNES/ARCADE/MD (bit10/11).
    // DC: 屏幕的 L/R 就是 Dreamcast 的模拟扳机，直接用 L2/R2 位
    // （dcToLibretroLayout 把 bit12/13 转 libretro JOYPAD_L2/R2 = 扳机）。
    val lBit = when {
        platform == GamePlatform.GBA -> BTN_L_GBA
        platform == GamePlatform.DC  -> BTN_L2
        else -> BTN_L_SNES
    }
    val rBit = when {
        platform == GamePlatform.GBA -> BTN_R_GBA
        platform == GamePlatform.DC  -> BTN_R2
        else -> BTN_R_SNES
    }
    // 即时存档 / 即时读档按钮回调用 rememberUpdatedState 包装，
    // 手势协程重启期间始终拿到最新 lambda
    val currentOnQuickSave by rememberUpdatedState(onQuickSave)
    val currentOnQuickLoad by rememberUpdatedState(onQuickLoad)

    // === 横竖屏布局选择 ===
    // 横屏用 dpad / btnA / btnB / ...，竖屏用 dpadP / btnAP / btnBP / ...
    // 两套布局各自独立保存，互不干扰。
    // PS2 使用专属全套布局字段（双摇杆 + 错层位置，避免与通用布局冲突）。
    val dpad = if (isPs2) (if (isPortrait) padLayout.ps2DpadP else padLayout.ps2Dpad)
               else if (isPortrait) padLayout.dpadP else padLayout.dpad
    val btnA = if (isPs2) (if (isPortrait) padLayout.ps2BtnAP else padLayout.ps2BtnA)
               else if (isPortrait) padLayout.btnAP else padLayout.btnA
    val btnB = if (isPs2) (if (isPortrait) padLayout.ps2BtnBP else padLayout.ps2BtnB)
               else if (isPortrait) padLayout.btnBP else padLayout.btnB
    // 连发 A/B（小 AB）：所有平台可见（可在"显示/隐藏按键"里按需隐藏）。
    // 6 键平台（有 X/Y）且用户从未拖动过时，用避让后的默认位置，
    // 防止与 X/Y 键重叠（见 shiftTurboDefault）。
    val btnTurboA = shiftTurboDefault(
        if (isPortrait) padLayout.btnTurboAP else padLayout.btnTurboA,
        showXY, isPortrait, isA = true)
    val btnTurboB = shiftTurboDefault(
        if (isPortrait) padLayout.btnTurboBP else padLayout.btnTurboB,
        showXY, isPortrait, isA = false)
    val btnStart = if (isPs2) (if (isPortrait) padLayout.ps2BtnStartP else padLayout.ps2BtnStart)
                   else if (isPortrait) padLayout.btnStartP else padLayout.btnStart
    val btnSelect = if (isPs2) (if (isPortrait) padLayout.ps2BtnSelectP else padLayout.ps2BtnSelect)
                    else if (isPortrait) padLayout.btnSelectP else padLayout.btnSelect
    val btnL = if (isPs2) (if (isPortrait) padLayout.ps2BtnL1P else padLayout.ps2BtnL1)
               else if (isPortrait) padLayout.btnLP else padLayout.btnL
    val btnR = if (isPs2) (if (isPortrait) padLayout.ps2BtnR1P else padLayout.ps2BtnR1)
               else if (isPortrait) padLayout.btnRP else padLayout.btnR
    val btnX = if (isPs2) (if (isPortrait) padLayout.ps2BtnXP else padLayout.ps2BtnX)
               else if (isPortrait) padLayout.btnXP else padLayout.btnX
    val btnY = if (isPs2) (if (isPortrait) padLayout.ps2BtnYP else padLayout.ps2BtnY)
               else if (isPortrait) padLayout.btnYP else padLayout.btnY
    val btnL2 = if (isPs2) (if (isPortrait) padLayout.ps2BtnL2P else padLayout.ps2BtnL2)
                else if (isPortrait) padLayout.btnL2P else padLayout.btnL2
    val btnR2 = if (isPs2) (if (isPortrait) padLayout.ps2BtnR2P else padLayout.ps2BtnR2)
                else if (isPortrait) padLayout.btnR2P else padLayout.btnR2
    // PS2 专属：双摇杆 + L3/R3（摇杆常驻，不参与显隐）
    val ps2LStick = if (isPortrait) padLayout.ps2LStickP else padLayout.ps2LStick
    val ps2RStick = if (isPortrait) padLayout.ps2RStickP else padLayout.ps2RStick
    val ps2BtnL3 = if (isPortrait) padLayout.ps2BtnL3P else padLayout.ps2BtnL3
    val ps2BtnR3 = if (isPortrait) padLayout.ps2BtnR3P else padLayout.ps2BtnR3
    // 即时存档 / 即时读档按钮（所有核心通用，位置可编辑器拖动）
    val btnQuickSave = if (isPortrait) padLayout.btnQuickSaveP else padLayout.btnQuickSave
    val btnQuickLoad = if (isPortrait) padLayout.btnQuickLoadP else padLayout.btnQuickLoad

    // === Combo buttons for this platform ===
    // Parse the per-platform JSON combo list. Each entry has {id,label,bits,x,y,size,color}.
    val comboList = remember(padLayout, platform) { parseComboButtons(padLayout, platform) }

    // Compute button hit-areas in pixels
    fun btnRect(layout: ButtonLayout, widthScale: Float = 1f, heightScale: Float = 1f): androidx.compose.ui.geometry.Rect {
        val sizePx = with(density) { layout.sizeDp.dp.toPx() }
        val w = sizePx * widthScale
        val h = sizePx * heightScale
        val cx = surfaceSize.width * layout.x
        val cy = surfaceSize.height * layout.y
        return androidx.compose.ui.geometry.Rect(cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2)
    }

    // Track active pointers: pointerId -> (BtnType, direction bits for dpad)
    val activePointers = remember { mutableMapOf<Long, Pair<BtnType, Int>>() }
    var visualState by remember { mutableStateOf(0) } // bits for drawing pressed state
    var turboState by remember { mutableStateOf(0) }  // turbo hold bits

    // Keep the latest forwarder lambda without restarting the pointerInput
    // gesture coroutine (which would interrupt any in-progress drag).
    val currentOnUnhandledTouch by rememberUpdatedState(onUnhandledTouch)

    // The pad overlay's position in root coordinates — used to convert
    // pad-local touch positions to root coordinates for the forwarder.
    // (PointerInputScope has no positionInRoot(); tracked via
    // onGloballyPositioned instead.)
    var padPosInRoot by remember { mutableStateOf(androidx.compose.ui.geometry.Offset.Zero) }

    // Send button state to engine immediately on change (zero-latency input).
    // The LaunchedEffect loop below maintains state at 60fps for turbo and
    // held buttons, but this ensures D-pad moves and button presses feel
    // instant with no 16ms frame delay.
    val sendStateNow = remember {
        { vs: Int, ts: Int ->
            if (ts != 0) {
                // Turbo active: send combined state immediately so D-pad
                // changes are instant, turbo cycling continues in the loop.
                onPadBits(vs or ts)
            } else {
                onPadBits(vs)
            }
        }
    }

    // PS2 双摇杆轴输出：把四轴拇指位置（-1..1）打包回调给调用方，
    // 由主 composable 转成 int16 libretro 轴值推给 Psx2Engine。
    // libretro ANALOG 轴约定与屏幕坐标一致：X 右正、Y 下正 —— 无需翻转。
    fun pushAnalog() {
        currentOnAnalogAxes?.invoke(lStickTX, lStickTY, rStickTX, rStickTY)
    }

    // Turbo auto-fire: simulates rapid short taps (press 2 frames, release 4 frames)
    // FC turbo buttons rapidly press/release the A/B button at ~10Hz.
    // Also maintains held-button state at 60fps for the emulation core.
    // OPTIMIZATION: when nothing is held (visualState == 0 && turboState == 0)
    // we skip the JNI call entirely — the engine already has 0 in its pad
    // state and continuously re-sending 0 wastes CPU and wakes the native
    // thread 60 times/second for no reason.
    var turboCounter by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            if (turboState != 0) {
                turboCounter++
                // 6-frame cycle: 2 frames ON, 4 frames OFF = ~10Hz rapid tap
                val turboOn = turboCounter % 6 < 2
                val effective = if (turboOn) visualState or turboState else visualState
                onPadBits(effective)
            } else if (visualState != 0) {
                onPadBits(visualState)
            }
            // else: idle — don't call onPadBits(0) every frame; the engine
            // already has 0 from the last release.
            delay(16)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            // Track the pad overlay's root position so pad-local touch
            // positions can be converted to root coordinates for the NDS
            // touchscreen forwarder (onUnhandledTouch).
            .onGloballyPositioned { coords -> padPosInRoot = coords.positionInRoot() }
            .pointerInput(padLayout, surfaceSize, isPortrait, useAnalogStick) {
                // Compute hit areas once (recomputed when key changes)
                // For analog stick mode, expand the hit area to a square around
                // the stick center so the user can drag outside the visual base.
                // Hidden buttons (PCE per-button visibility) get a null rect so
                // touches on them are ignored.
                val dpadRect = if (showDpadBtn) {
                    if (useAnalogStick) {
                        // Analog stick hit area: 1.5x the visual size, so the user
                        // can drag their thumb beyond the stick base for large movements.
                        btnRect(dpad, 1.5f, 1.5f)
                    } else {
                        btnRect(dpad)
                    }
                } else null
                val aRect = if (showABtn) btnRect(btnA) else null
                val bRect = if (showBBtn) btnRect(btnB) else null
                // Turbo A/B hit areas — 所有平台都生效（显隐由
                // KeyVisibilityDialog 的 ta/tb 开关控制）
                val taRect = if (showTurboABtn) btnRect(btnTurboA) else null
                val tbRect = if (showTurboBBtn) btnRect(btnTurboB) else null
                val startRect = if (showStartBtn) btnRect(btnStart, 2.2f, 0.7f) else null
                val selectRect = if (showSelectBtn) btnRect(btnSelect, 2.2f, 0.7f) else null
                val lRect = if (showLR && showLBtn) btnRect(btnL, 1.6f, 0.7f) else null
                val rRect = if (showLR && showRBtn) btnRect(btnR, 1.6f, 0.7f) else null
                val xRect = if (showXY && showXBtn) btnRect(btnX) else null
                val yRect = if (showXY && showYBtn) btnRect(btnY) else null
                val l2Rect = if (showL2R2 && showL2Btn) btnRect(btnL2) else null
                val r2Rect = if (showL2R2 && showR2Btn) btnRect(btnR2) else null
                // 即时存档 / 即时读档按钮命中区（Pill 形，跟 START/SELECT 一样放宽）
                val qsRect = if (showQuickSaveBtn) btnRect(btnQuickSave, 2.2f, 0.7f) else null
                val qlRect = if (showQuickLoadBtn) btnRect(btnQuickLoad, 2.2f, 0.7f) else null
                // PS2 专属：L3/R3 小按钮 + 双摇杆（摇杆命中区 1.3x，方便拖动）
                val l3Rect = if (showL3Btn) btnRect(ps2BtnL3, 1.4f, 1.4f) else null
                val r3Rect = if (showR3Btn) btnRect(ps2BtnR3, 1.4f, 1.4f) else null
                val lStickRect = if (isPs2) btnRect(ps2LStick, 1.3f, 1.3f) else null
                val rStickRect = if (isPs2) btnRect(ps2RStick, 1.3f, 1.3f) else null
                // Combo button hit areas
                val comboRects = comboList.map { c ->
                    c.id to btnRect(ButtonLayout(c.x, c.y, c.sizeDp))
                }

                // Compute direction bits from a touch position within the
                // dpad/stick rect. For analog mode, use a radial deadzone
                // and allow continuous thumb tracking. For D-Pad mode, use
                // the quadrant hit-test (8-direction).
                // Returns (bits, thumbX, thumbY) where thumbX/Y are in [-1, 1].
                fun computeDirection(pos: Offset): Triple<Int, Float, Float> {
                    // This is only reached when a touch landed on the dpad/stick
                    // hit area, so the rect is non-null there; bail out safely
                    // if the dpad button is hidden.
                    val rect = dpadRect ?: return Triple(0, 0f, 0f)
                    if (useAnalogStick) {
                        // Analog mode: thumb offset relative to stick center,
                        // normalized to [-1, 1] based on stick radius.
                        val cx = rect.center.x
                        val cy = rect.center.y
                        val radius = rect.width / 2f
                        val dx = (pos.x - cx) / radius
                        val dy = (pos.y - cy) / radius
                        // Clamp magnitude to 1.0 (allow dragging outside, but
                        // thumb visual stays within the stick base)
                        val mag = kotlin.math.sqrt(dx * dx + dy * dy)
                        val clampedDx: Float
                        val clampedDy: Float
                        if (mag > 1f) {
                            clampedDx = dx / mag
                            clampedDy = dy / mag
                        } else {
                            clampedDx = dx
                            clampedDy = dy
                        }
                        // Radial deadzone: 0.25 of radius. Below this, no direction.
                        val deadzone = 0.25f
                        var bits = 0
                        if (mag > deadzone) {
                            // Use clamped values for direction test (so diagonals work)
                            val absX = kotlin.math.abs(clampedDx)
                            val absY = kotlin.math.abs(clampedDy)
                            // Cardinal + diagonal: threshold at 0.4
                            val cardThreshold = 0.4f
                            if (clampedDx < -cardThreshold) bits = bits or BTN_LEFT
                            else if (clampedDx > cardThreshold) bits = bits or BTN_RIGHT
                            if (clampedDy < -cardThreshold) bits = bits or BTN_UP
                            else if (clampedDy > cardThreshold) bits = bits or BTN_DOWN
                            // If no cardinal direction triggered but we're past
                            // the deadzone, fall back to the dominant axis so
                            // small movements still register.
                            if (bits == 0) {
                                if (absX > absY) {
                                    bits = bits or (if (clampedDx < 0) BTN_LEFT else BTN_RIGHT)
                                } else {
                                    bits = bits or (if (clampedDy < 0) BTN_UP else BTN_DOWN)
                                }
                            }
                        }
                        return Triple(bits, clampedDx, clampedDy)
                    } else {
                        // D-Pad mode: 8-direction quadrant hit-test.
                        return Triple(computeDpadDirection(pos, rect), 0f, 0f)
                    }
                }

                // Process a pointer DOWN at the given position.
                // Returns true if the pointer landed on a button.
                fun processDown(pid: Long, pos: Offset) {
                    // Check combo buttons first (they may overlap regular buttons)
                    var comboMatch: ComboButtonEntry? = null
                    for ((cid, rect) in comboRects) {
                        if (rect.contains(pos)) {
                            comboMatch = comboList.firstOrNull { it.id == cid }
                            break
                        }
                    }
                    val btnType = when {
                        comboMatch != null -> BtnType.COMBO
                        dpadRect?.contains(pos) == true -> BtnType.DPAD
                        aRect?.contains(pos) == true -> BtnType.A
                        bRect?.contains(pos) == true -> BtnType.B
                        taRect?.contains(pos) == true -> BtnType.TURBO_A
                        tbRect?.contains(pos) == true -> BtnType.TURBO_B
                        startRect?.contains(pos) == true -> BtnType.START
                        selectRect?.contains(pos) == true -> BtnType.SELECT
                        lRect?.contains(pos) == true -> BtnType.L
                        rRect?.contains(pos) == true -> BtnType.R
                        xRect?.contains(pos) == true -> BtnType.X
                        yRect?.contains(pos) == true -> BtnType.Y
                        l2Rect?.contains(pos) == true -> BtnType.L2
                        r2Rect?.contains(pos) == true -> BtnType.R2
                        qsRect?.contains(pos) == true -> BtnType.QUICK_SAVE
                        qlRect?.contains(pos) == true -> BtnType.QUICK_LOAD
                        // PS2 专属按键（先于摇杆判定，L3/R3 面积小优先命中）
                        l3Rect?.contains(pos) == true -> BtnType.L3
                        r3Rect?.contains(pos) == true -> BtnType.R3
                        lStickRect?.contains(pos) == true -> BtnType.LSTICK
                        rStickRect?.contains(pos) == true -> BtnType.RSTICK
                        else -> null
                    }
                    if (btnType == null) {
                        // Touch landed on no pad button — it's a game-area touch
                        // (NDS touchscreen). Forward it so the game view (which
                        // Compose hit-testing never reaches under this overlay)
                        // can still receive touch input.
                        if (currentOnUnhandledTouch != null) {
                            activePointers[pid] = BtnType.GAME_AREA to 0
                            currentOnUnhandledTouch?.invoke(
                                pos + padPosInRoot,
                                android.view.MotionEvent.ACTION_DOWN
                            )
                        }
                        return
                    }
                    if (btnType != null) {
                        var bits = 0
                        var turboBits = 0
                        // 摇杆只输出模拟轴，不写 D-pad 数字位（避免与十字键串联）
                        var stickOnly = false
                        when (btnType) {
                            BtnType.DPAD -> {
                                val (b, tx, ty) = computeDirection(pos)
                                bits = b
                                if (useAnalogStick) {
                                    // 模拟模式：控件渲染为摇杆样式，拇指位置仅作视觉反馈；
                                    // 输入仍是纯数字方向位 —— 十字键与左摇杆完全解耦，
                                    // 左摇杆轴只由独立的 LSTICK 控件输出（PS2 游戏里
                                    // 十字键=十字键，左摇杆=左摇杆，互不串扰）。
                                    analogThumbX = tx
                                    analogThumbY = ty
                                    if (platform == GamePlatform.DC) {
                                        // DC：摇杆模式下摇杆控件同时输出真实模拟轴
                                        //（flycast 的 DC 摇杆必须走 ANALOG 轴，
                                        // 仅数字位大多数 3D 游戏动不了）；
                                        // 数字方向位同时保留（纯数字游戏兼容）。
                                        lStickTX = tx
                                        lStickTY = ty
                                        pushAnalog()
                                    }
                                }
                            }
                            BtnType.LSTICK -> {
                                val (b, tx, ty) = computeStickDirection(pos, lStickRect!!)
                                lStickDirs = b
                                lStickTX = tx
                                lStickTY = ty
                                pushAnalog()
                                stickOnly = true
                            }
                            BtnType.RSTICK -> {
                                val (b, tx, ty) = computeStickDirection(pos, rStickRect!!)
                                rStickDirs = b
                                rStickTX = tx
                                rStickTY = ty
                                pushAnalog()
                                stickOnly = true
                            }
                            BtnType.A -> bits = BTN_A
                            BtnType.B -> bits = BTN_B
                            BtnType.TURBO_A -> turboBits = BTN_A
                            BtnType.TURBO_B -> turboBits = BTN_B
                            BtnType.START -> bits = BTN_START
                            BtnType.SELECT -> bits = BTN_SELECT
                            BtnType.L -> bits = lBit
                            BtnType.R -> bits = rBit
                            BtnType.X -> bits = BTN_X
                            BtnType.Y -> bits = BTN_Y
                            BtnType.L2 -> bits = BTN_L2
                            BtnType.R2 -> bits = BTN_R2
                            BtnType.L3 -> bits = BTN_L3
                            BtnType.R3 -> bits = BTN_R3
                            BtnType.COMBO -> bits = comboMatch?.bits ?: 0
                            // 即时存档 / 即时读档：按下瞬间触发一次回调，
                            // 不写任何输入位（不影响游戏内的按键状态）。
                            BtnType.QUICK_SAVE -> currentOnQuickSave?.invoke()
                            BtnType.QUICK_LOAD -> currentOnQuickLoad?.invoke()
                            // GAME_AREA 指针在上方 btnType == null 分支已提前
                            // return，永远到不了这里；补空分支仅为穷举完整性。
                            BtnType.GAME_AREA -> {}
                        }
                        activePointers[pid] = btnType to (if (turboBits != 0) turboBits else bits)
                        if (stickOnly) {
                            // 摇杆只推模拟轴（已在上面 pushAnalog()），数字方向由
                            // 十字键独占 —— 不写 visualState，避免三者串联。
                        } else if (turboBits != 0) {
                            turboState = turboState or turboBits
                            sendStateNow(visualState, turboState)
                        } else {
                            visualState = visualState or bits
                            sendStateNow(visualState, turboState)
                        }
                    }
                }

                // Main gesture loop:
                // awaitFirstDown() and awaitPointerEvent() are members of
                // AwaitPointerEventScope, so we wrap everything in
                // awaitPointerEventScope { }. This captures the first finger
                // DOWN immediately (single-touch works) and then processes all
                // subsequent events (multi-touch, moves, ups) in the inner loop.
                awaitPointerEventScope {
                    while (true) {
                        val firstDown = awaitFirstDown(requireUnconsumed = false)
                        processDown(firstDown.id.value, firstDown.position)

                        var pressedCount = 1 // firstDown gave us one pressed finger

                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Main)

                            for (change in event.changes) {
                                val pid = change.id.value

                                if (change.changedToDown()) {
                                    pressedCount++
                                    processDown(pid, change.position)
                                } else if (change.changedToUp()) {
                                    pressedCount--
                                    val entry = activePointers.remove(pid)
                                    if (entry != null) {
                                        val (bt, heldBits) = entry
                                        when (bt) {
                                            BtnType.GAME_AREA -> {
                                                // Game-area touch released — forward
                                                // as ACTION_UP (releases the NDS touch).
                                                currentOnUnhandledTouch?.invoke(
                                                    change.position + padPosInRoot,
                                                    android.view.MotionEvent.ACTION_UP
                                                )
                                            }
                                            BtnType.DPAD, BtnType.A, BtnType.B,
                                            BtnType.START, BtnType.SELECT,
                                            BtnType.L, BtnType.R,
                                            BtnType.X, BtnType.Y,
                                            BtnType.L2, BtnType.R2,
                                            BtnType.L3, BtnType.R3,
                                            BtnType.COMBO -> {
                                                visualState = visualState and heldBits.inv()
                                                sendStateNow(visualState, turboState)
                                                // Reset analog thumb when DPAD is released
                                                if (bt == BtnType.DPAD && useAnalogStick) {
                                                    analogThumbX = 0f
                                                    analogThumbY = 0f
                                                    if (platform == GamePlatform.DC) {
                                                        // DC：松手回中 —— 轴值归零并推送
                                                        lStickTX = 0f
                                                        lStickTY = 0f
                                                        pushAnalog()
                                                    }
                                                }
                                            }
                                            // 即时存档 / 即时读档无输入位，UP 仅移除指针
                                            BtnType.QUICK_SAVE, BtnType.QUICK_LOAD -> {}
                                            BtnType.LSTICK -> {
                                                // 松手：拇指回中 + 清摇杆方向位 + 轴归零
                                                lStickDirs = 0
                                                lStickTX = 0f
                                                lStickTY = 0f
                                                pushAnalog()
                                            }
                                            BtnType.RSTICK -> {
                                                rStickDirs = 0
                                                rStickTX = 0f
                                                rStickTY = 0f
                                                pushAnalog()
                                            }
                                            BtnType.TURBO_A, BtnType.TURBO_B -> {
                                                turboState = turboState and heldBits.inv()
                                                sendStateNow(visualState, turboState)
                                            }
                                        }
                                    }
                                } else if (change.positionChanged()) {
                                    val entry = activePointers[pid]
                                    if (entry != null && entry.first == BtnType.DPAD) {
                                        val oldBits = entry.second
                                        visualState = visualState and oldBits.inv()
                                        val (newBits, tx, ty) = computeDirection(change.position)
                                        visualState = visualState or newBits
                                        activePointers[pid] = BtnType.DPAD to newBits
                                        if (useAnalogStick) {
                                            analogThumbX = tx
                                            analogThumbY = ty
                                            if (platform == GamePlatform.DC) {
                                                // DC：拖动时同步更新模拟轴（同上）
                                                lStickTX = tx
                                                lStickTY = ty
                                                pushAnalog()
                                            }
                                        }
                                        sendStateNow(visualState, turboState)
                                    } else if (entry != null && entry.first == BtnType.LSTICK) {
                                        // PS2 左摇杆拖动：更新拇指位置 + 轴回调（不写 D-pad 数字位）
                                        val (newBits, tx, ty) = computeStickDirection(change.position, lStickRect!!)
                                        lStickDirs = newBits
                                        activePointers[pid] = BtnType.LSTICK to newBits
                                        lStickTX = tx
                                        lStickTY = ty
                                        pushAnalog()
                                    } else if (entry != null && entry.first == BtnType.RSTICK) {
                                        val (newBits, tx, ty) = computeStickDirection(change.position, rStickRect!!)
                                        rStickDirs = newBits
                                        activePointers[pid] = BtnType.RSTICK to newBits
                                        rStickTX = tx
                                        rStickTY = ty
                                        pushAnalog()
                                    } else if (entry != null && entry.first == BtnType.GAME_AREA) {
                                        // Drag on the game area — forward as
                                        // ACTION_MOVE (continuous NDS touch tracking).
                                        currentOnUnhandledTouch?.invoke(
                                            change.position + padPosInRoot,
                                            android.view.MotionEvent.ACTION_MOVE
                                        )
                                    }
                                }
                            }

                            if (pressedCount <= 0) break
                        }
                    }
                }
            }
    ) {
        // Draw D-pad OR Analog Stick depending on arcadeInputMode.
        if (showDpadBtn) {
            if (useAnalogStick) {
                val (analogImg, analogImgPressed) = rememberThemeButtonImages(overlayTheme, "dpad")
                AnalogStickCanvas(
                    layout = dpad,
                    surfaceSize = surfaceSize,
                    opacity = opacity,
                    pressedDirs = visualState and 0xF0,
                    thumbX = analogThumbX,
                    thumbY = analogThumbY,
                    thumbColor = themeButtonColor(overlayTheme, "l3", Color(0xFFFFD66B)),
                    thumbPressedColor = themePressedButtonColor(overlayTheme, "l3") ?: Color(0xFFFFE57F),
                    image = analogImg,
                    pressedImage = analogImgPressed
                )
            } else {
                val (dpadImg, dpadImgPressed) = rememberThemeButtonImages(overlayTheme, "dpad")
                DpadCanvas(
                    layout = dpad,
                    surfaceSize = surfaceSize,
                    opacity = opacity,
                    pressedDirs = visualState and 0xF0,
                    armColor = themeButtonColor(overlayTheme, "dpad", Color(0xFF2C2C38)),
                    pressedTipColor = themePressedButtonColor(overlayTheme, "dpad") ?: Color(0xFFFFD66B),
                    image = dpadImg,
                    pressedImage = dpadImgPressed
                )
            }
        }
        // Draw A
        // For PCE the buttons are labeled with PCE's native names.
        // Per Geargrafx reference: bit0 (BTN_A) → PCE I, bit1 (BTN_B) → PCE II.
        // (Earlier comment had I/II swapped — corrected after reading the
        // reference source's input descriptor: A="I", B="II".)
        val labelA = when (platform) {
            GamePlatform.PCE -> "I"
            GamePlatform.PSX, GamePlatform.PS2 -> "✕"  // Cross
            else -> "A"
        }
        val labelB = when (platform) {
            GamePlatform.PCE -> "II"
            GamePlatform.PSX, GamePlatform.PS2 -> "○"  // Circle
            else -> "B"
        }
        val labelX = when (platform) {
            GamePlatform.PCE -> "IV"
            // PSX 屏幕排布：X 在右上 = △；PS2 专属菱形：X 在左 = □
            GamePlatform.PSX -> "△"  // Triangle
            GamePlatform.PS2 -> "□"  // Square
            // MD 6 键手柄：libretro X 对应 SEGA C（A/B/C/X/Y/Z 六键布局）
            GamePlatform.MD -> "C"
            else -> "X"
        }
        val labelY = when (platform) {
            GamePlatform.PCE -> "III"
            GamePlatform.PSX -> "□"  // Square
            GamePlatform.PS2 -> "△"  // Triangle
            // MD 6 键手柄：libretro Y 对应 SEGA X
            GamePlatform.MD -> "X"
            else -> "Y"
        }
        val labelL = when (platform) {
            GamePlatform.PCE -> "V"
            GamePlatform.PSX, GamePlatform.PS2 -> "L1"
            // MD 6 键手柄：libretro L 对应 SEGA Y
            GamePlatform.MD -> "Y"
            else -> "L"
        }
        val labelR = when (platform) {
            GamePlatform.PCE -> "VI"
            GamePlatform.PSX, GamePlatform.PS2 -> "R1"
            // MD 6 键手柄：libretro R 对应 SEGA Z
            GamePlatform.MD -> "Z"
            else -> "R"
        }
        val labelL2 = when (platform) {
            GamePlatform.PCE -> "TURBO II"
            GamePlatform.PSX, GamePlatform.PS2 -> "L2"
            else -> "L2"
        }
        val labelR2 = when (platform) {
            GamePlatform.PCE -> "TURBO I"
            GamePlatform.PSX, GamePlatform.PS2 -> "R2"
            else -> "R2"
        }
        if (showABtn) {
            val aColor = when (platform) {
                GamePlatform.PSX, GamePlatform.PS2 -> Color(0xFF2ECC71)  // Green (Cross)
                else -> Color(0xFFE74C3C)
            }
            val (aImg, aImgPressed) = rememberThemeButtonImages(overlayTheme, "a")
            ActionButtonCanvas(
                labelA, aColor, btnA, surfaceSize, opacity, visualState and BTN_A != 0,
                pressedColor = themePressedButtonColor(overlayTheme, "a"),
                image = aImg,
                pressedImage = aImgPressed
            )
        }
        // Draw B
        if (showBBtn) {
            val bColor = when (platform) {
                GamePlatform.PSX, GamePlatform.PS2 -> Color(0xFFE74C3C)  // Red (Circle)
                else -> Color(0xFFE67E22)
            }
            val (bImg, bImgPressed) = rememberThemeButtonImages(overlayTheme, "b")
            ActionButtonCanvas(
                labelB, bColor, btnB, surfaceSize, opacity, visualState and BTN_B != 0,
                pressedColor = themePressedButtonColor(overlayTheme, "b"),
                image = bImg,
                pressedImage = bImgPressed
            )
        }
        // Turbo A/B（小 AB 连发键）— 所有平台按需渲染：显隐由
        // "显示/隐藏按键"里的连射A/连射B开关控制；6 键平台用避让后的
        // 默认位置（见 shiftTurboDefault），不会与 X/Y 重叠。
        if (showTurboABtn) {
            val (taImg, taImgPressed) = rememberThemeButtonImages(overlayTheme, "ta")
            TurboButtonCanvas(
                labelA, Color(0xFFE74C3C), btnTurboA, surfaceSize, opacity, turboState and BTN_A != 0,
                pressedColor = themePressedButtonColor(overlayTheme, "ta"),
                image = taImg,
                pressedImage = taImgPressed
            )
        }
        if (showTurboBBtn) {
            val (tbImg, tbImgPressed) = rememberThemeButtonImages(overlayTheme, "tb")
            TurboButtonCanvas(
                labelB, Color(0xFFE67E22), btnTurboB, surfaceSize, opacity, turboState and BTN_B != 0,
                pressedColor = themePressedButtonColor(overlayTheme, "tb"),
                image = tbImg,
                pressedImage = tbImgPressed
            )
        }
        // Start
        if (showStartBtn) {
            val (startImg, startImgPressed) = rememberThemeButtonImages(overlayTheme, "start")
            PillButtonCanvas(
                if (platform == GamePlatform.PCE) "RUN" else "START", btnStart, surfaceSize, opacity, visualState and BTN_START != 0,
                normalColor = themeButtonColor(overlayTheme, "start", Color(0xFF2A3040)),
                pressedColor = themePressedButtonColor(overlayTheme, "start"),
                image = startImg,
                pressedImage = startImgPressed
            )
        }
        // Select
        if (showSelectBtn) {
            val (selectImg, selectImgPressed) = rememberThemeButtonImages(overlayTheme, "select")
            PillButtonCanvas(
                "SELECT", btnSelect, surfaceSize, opacity, visualState and BTN_SELECT != 0,
                normalColor = themeButtonColor(overlayTheme, "select", Color(0xFF2A3040)),
                pressedColor = themePressedButtonColor(overlayTheme, "select"),
                image = selectImg,
                pressedImage = selectImgPressed
            )
        }
        // 即时存档 / 即时读档按钮（所有核心通用，位置可在布局编辑器里拖动）
        // 用绿色/蓝色区分存/读，字面提示交给 PillButtonCanvas 的文字。
        if (showQuickSaveBtn) {
            val (qsImg, qsImgPressed) = rememberThemeButtonImages(overlayTheme, "qs")
            PillButtonCanvas(
                "存档", btnQuickSave, surfaceSize, opacity, isPressed = false,
                normalColor = themeButtonColor(overlayTheme, "qs", Color(0xFF1D6B46)),
                pressedColor = themePressedButtonColor(overlayTheme, "qs"),
                image = qsImg,
                pressedImage = qsImgPressed
            )
        }
        if (showQuickLoadBtn) {
            val (qlImg, qlImgPressed) = rememberThemeButtonImages(overlayTheme, "ql")
            PillButtonCanvas(
                "读档", btnQuickLoad, surfaceSize, opacity, isPressed = false,
                normalColor = themeButtonColor(overlayTheme, "ql", Color(0xFF1D4E6B)),
                pressedColor = themePressedButtonColor(overlayTheme, "ql"),
                image = qlImg,
                pressedImage = qlImgPressed
            )
        }
        // L/R shoulder buttons (GBA/SNES/ARCADE/MD/PCE)
        if (showLR && showLBtn) {
            val (lImg, lImgPressed) = rememberThemeButtonImages(overlayTheme, "l")
            ShoulderButtonCanvas(
                labelL, btnL, surfaceSize, opacity, visualState and lBit != 0,
                normalColor = themeButtonColor(overlayTheme, "l", Color(0xFF2A3040)),
                pressedColor = themePressedButtonColor(overlayTheme, "l"),
                image = lImg,
                pressedImage = lImgPressed
            )
        }
        if (showLR && showRBtn) {
            val (rImg, rImgPressed) = rememberThemeButtonImages(overlayTheme, "r")
            ShoulderButtonCanvas(
                labelR, btnR, surfaceSize, opacity, visualState and rBit != 0,
                normalColor = themeButtonColor(overlayTheme, "r", Color(0xFF2A3040)),
                pressedColor = themePressedButtonColor(overlayTheme, "r"),
                image = rImg,
                pressedImage = rImgPressed
            )
        }
        // X/Y face buttons (SNES/Arcade/MD/PCE/NDS/PSX/PS2)
        if (showXY && showXBtn) {
            val xColor = when (platform) {
                GamePlatform.PSX -> Color(0xFFE91E9B)  // Pink (Triangle)
                GamePlatform.PS2 -> Color(0xFF3498DB)  // Blue (Square — PS2 X 位置在左)
                else -> Color(0xFF3498DB)
            }
            val (xImg, xImgPressed) = rememberThemeButtonImages(overlayTheme, "x")
            ActionButtonCanvas(
                labelX, xColor, btnX, surfaceSize, opacity, visualState and BTN_X != 0,
                pressedColor = themePressedButtonColor(overlayTheme, "x"),
                image = xImg,
                pressedImage = xImgPressed
            )
        }
        if (showXY && showYBtn) {
            val yColor = when (platform) {
                GamePlatform.PSX -> Color(0xFF3498DB)  // Blue (Square)
                GamePlatform.PS2 -> Color(0xFFE91E9B)  // Pink (Triangle — PS2 Y 位置在上)
                else -> Color(0xFF2ECC71)
            }
            val (yImg, yImgPressed) = rememberThemeButtonImages(overlayTheme, "y")
            ActionButtonCanvas(
                labelY, yColor, btnY, surfaceSize, opacity, visualState and BTN_Y != 0,
                pressedColor = themePressedButtonColor(overlayTheme, "y"),
                image = yImg,
                pressedImage = yImgPressed
            )
        }
        // L2/R2 extra buttons:
        //   Arcade 6-button fight layout — hidden by default, enabled via Settings
        //   PCE — Turbo toggle buttons (L2=Toggle Turbo II, R2=Toggle Turbo I)
        //   PSX — DualShock L2/R2 shoulder buttons (bits 12/13)
        if (showL2R2 && showL2Btn) {
            val l2Color = when (platform) {
                GamePlatform.PSX, GamePlatform.PS2 -> Color(0xFF95A5A6)  // Silver/gray (DualShock L2)
                else -> Color(0xFFFF9800)
            }
            val (l2Img, l2ImgPressed) = rememberThemeButtonImages(overlayTheme, "l2")
            ActionButtonCanvas(
                labelL2, l2Color, btnL2, surfaceSize, opacity, visualState and BTN_L2 != 0,
                pressedColor = themePressedButtonColor(overlayTheme, "l2"),
                image = l2Img,
                pressedImage = l2ImgPressed
            )
        }
        if (showL2R2 && showR2Btn) {
            val r2Color = when (platform) {
                GamePlatform.PSX, GamePlatform.PS2 -> Color(0xFF95A5A6)  // Silver/gray (DualShock R2)
                else -> Color(0xFFFF9800)
            }
            val (r2Img, r2ImgPressed) = rememberThemeButtonImages(overlayTheme, "r2")
            ActionButtonCanvas(
                labelR2, r2Color, btnR2, surfaceSize, opacity, visualState and BTN_R2 != 0,
                pressedColor = themePressedButtonColor(overlayTheme, "r2"),
                image = r2Img,
                pressedImage = r2ImgPressed
            )
        }
        // PS2 专属：双模拟摇杆（常驻，左下/右下）+ L3/R3 小按钮。
        // 摇杆输出真实模拟轴（onAnalogAxes），箭头高亮跟随数字方向位。
        if (isPs2) {
            val (l3Img, l3ImgPressed) = rememberThemeButtonImages(overlayTheme, "l3")
            val (r3Img, r3ImgPressed) = rememberThemeButtonImages(overlayTheme, "r3")
            AnalogStickCanvas(
                layout = ps2LStick,
                surfaceSize = surfaceSize,
                opacity = opacity,
                pressedDirs = lStickDirs,
                thumbX = lStickTX,
                thumbY = lStickTY,
                thumbColor = themeButtonColor(overlayTheme, "l3", Color(0xFFFFD66B)),
                thumbPressedColor = themePressedButtonColor(overlayTheme, "l3") ?: Color(0xFFFFE57F),
                image = l3Img,
                pressedImage = l3ImgPressed
            )
            AnalogStickCanvas(
                layout = ps2RStick,
                surfaceSize = surfaceSize,
                opacity = opacity,
                pressedDirs = 0,   // 右摇杆箭头不做数字高亮（轴专用）
                thumbX = rStickTX,
                thumbY = rStickTY,
                thumbColor = themeButtonColor(overlayTheme, "r3", Color(0xFFFFD66B)),
                thumbPressedColor = themePressedButtonColor(overlayTheme, "r3") ?: Color(0xFFFFE57F),
                image = r3Img,
                pressedImage = r3ImgPressed
            )
            if (showL3Btn) {
                ActionButtonCanvas(
                    "L3", Color(0xFF95A5A6), ps2BtnL3, surfaceSize, opacity, visualState and BTN_L3 != 0,
                    pressedColor = themePressedButtonColor(overlayTheme, "l3"),
                    image = l3Img,
                    pressedImage = l3ImgPressed
                )
            }
            if (showR3Btn) {
                ActionButtonCanvas(
                    "R3", Color(0xFF95A5A6), ps2BtnR3, surfaceSize, opacity, visualState and BTN_R3 != 0,
                    pressedColor = themePressedButtonColor(overlayTheme, "r3"),
                    image = r3Img,
                    pressedImage = r3ImgPressed
                )
            }
        }
        // Combo buttons (per-platform, user-defined)
        comboList.forEach { combo ->
            val pressed = (visualState and combo.bits) == combo.bits
            val (comboImg, comboImgPressed) = rememberThemeButtonImages(overlayTheme, "combo_${combo.id}")
            ActionButtonCanvas(
                combo.label,
                Color(combo.color),
                ButtonLayout(combo.x, combo.y, combo.sizeDp),
                surfaceSize,
                opacity,
                pressed,
                pressedColor = themePressedButtonColor(overlayTheme, "combo_${combo.id}"),
                image = comboImg,
                pressedImage = comboImgPressed
            )
        }
    }
}

// Compute PS2 analog-stick direction + thumb offset from a touch position.
// Returns (directionBits, thumbX, thumbY): bits are the digital direction
// flags (BTN_UP/DOWN/LEFT/RIGHT, for games that read the d-pad), thumbX/Y
// are the continuous axis values in [-1, 1] (screen coords: Y down = +1;
// pushAnalog() 翻转后送给核心)。
private fun computeStickDirection(
    pos: Offset,
    rect: androidx.compose.ui.geometry.Rect
): Triple<Int, Float, Float> {
    val cx = rect.center.x
    val cy = rect.center.y
    val radius = rect.width / 2f
    val dx = (pos.x - cx) / radius
    val dy = (pos.y - cy) / radius
    val mag = kotlin.math.sqrt(dx * dx + dy * dy)
    val clampedDx: Float
    val clampedDy: Float
    if (mag > 1f) {
        clampedDx = dx / mag
        clampedDy = dy / mag
    } else {
        clampedDx = dx
        clampedDy = dy
    }
    // Radial deadzone: 0.2 of radius (比 dpad analog 模式略小，PS2 摇杆更灵敏)
    val deadzone = 0.2f
    var bits = 0
    if (mag > deadzone) {
        val absX = kotlin.math.abs(clampedDx)
        val absY = kotlin.math.abs(clampedDy)
        val cardThreshold = 0.4f
        if (clampedDx < -cardThreshold) bits = bits or BTN_LEFT
        else if (clampedDx > cardThreshold) bits = bits or BTN_RIGHT
        if (clampedDy < -cardThreshold) bits = bits or BTN_UP
        else if (clampedDy > cardThreshold) bits = bits or BTN_DOWN
        if (bits == 0) {
            // 死区内但未过阈值：主轴方向兑底，保证小位移也能触发方向
            if (absX > absY) {
                bits = bits or (if (clampedDx < 0) BTN_LEFT else BTN_RIGHT)
            } else {
                bits = bits or (if (clampedDy < 0) BTN_UP else BTN_DOWN)
            }
        }
    }
    return Triple(bits, clampedDx, clampedDy)
}

// Compute D-pad direction from touch position within dpad rect.
// Supports 8 directions: up, down, left, right, and 4 diagonals.
private fun computeDpadDirection(
    pos: Offset,
    rect: androidx.compose.ui.geometry.Rect
): Int {
    val cx = rect.center.x
    val cy = rect.center.y
    val dx = pos.x - cx
    val dy = pos.y - cy
    val absX = kotlin.math.abs(dx)
    val absY = kotlin.math.abs(dy)
    val deadZone = rect.width * 0.15f
    if (absX < deadZone && absY < deadZone) return 0

    var bits = 0
    if (absX > deadZone) {
        bits = bits or (if (dx < 0) BTN_LEFT else BTN_RIGHT)
    }
    if (absY > deadZone) {
        bits = bits or (if (dy < 0) BTN_UP else BTN_DOWN)
    }
    return bits
}

// ---------------------------------------------------------------------------
// Button drawing composables (no pointer input — purely visual)
// ---------------------------------------------------------------------------
private fun buttonOffset(layout: ButtonLayout, surfaceSize: IntSize, density: androidx.compose.ui.unit.Density): Pair<Float, Float> {
    val sizePx = with(density) { layout.sizeDp.dp.toPx() }
    val px = surfaceSize.width * layout.x - sizePx / 2
    val py = surfaceSize.height * layout.y - sizePx / 2
    return px to py
}

/** 从主题解析按钮常规色；未配置时回退 default。 */
private fun themeButtonColor(
    theme: com.nesstation.app.core.storage.OverlayTheme,
    buttonId: String,
    default: Color
): Color =
    theme.buttons[buttonId]?.color?.let { Color(it) } ?: default

/** 从主题解析按钮按压色；null 表示未配置（由绘制函数使用默认按压效果）。 */
private fun themePressedButtonColor(
    theme: com.nesstation.app.core.storage.OverlayTheme,
    buttonId: String
): Color? =
    theme.buttons[buttonId]?.pressedColor?.let { Color(it) }

/** 从主题读取并解码某按键的常规/按压图片；未配置时对应项为 null。 */
@Composable
internal fun rememberThemeButtonImages(
    theme: com.nesstation.app.core.storage.OverlayTheme,
    buttonId: String
): Pair<androidx.compose.ui.graphics.ImageBitmap?, androidx.compose.ui.graphics.ImageBitmap?> {
    val ctx = LocalContext.current
    val btn = theme.buttons[buttonId]
    val normalUri = btn?.imageUri
    val pressedUri = btn?.pressedImageUri
    val normal = remember(normalUri) {
        normalUri?.let { FsdImaging.decodeUri(ctx, it, 256, 256)?.asImageBitmap() }
    }
    val pressed = remember(pressedUri) {
        pressedUri?.let { FsdImaging.decodeUri(ctx, it, 256, 256)?.asImageBitmap() }
    }
    return normal to pressed
}

@Composable
private fun DpadCanvas(
    layout: ButtonLayout,
    surfaceSize: IntSize,
    opacity: Float,
    pressedDirs: Int,
    armColor: Color = Color(0xFF2C2C38),
    pressedTipColor: Color = Color(0xFFFFD66B),
    image: androidx.compose.ui.graphics.ImageBitmap? = null,
    pressedImage: androidx.compose.ui.graphics.ImageBitmap? = null
) {
    val density = LocalDensity.current
    val sizeDp = layout.sizeDp.dp
    val (px, py) = buttonOffset(layout, surfaceSize, density)
    val activeImage = if (pressedDirs != 0) (pressedImage ?: image) else image

    Box(
        modifier = Modifier
            .offset { IntOffset(px.toInt(), py.toInt()) }
            .size(sizeDp),
        contentAlignment = Alignment.Center
    ) {
        if (activeImage != null) {
            Image(
                bitmap = activeImage,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize().alpha(if (pressedDirs != 0) 0.9f else opacity)
            )
        } else {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val cx = size.width / 2f
                val cy = size.height / 2f
                val halfSize = size.width / 2f
                val armLen = halfSize * 0.95f
                val armThick = size.width * 0.30f
                val halfThick = armThick / 2f
                val cornerR = armThick * 0.15f
                val cr = androidx.compose.ui.geometry.CornerRadius(cornerR, cornerR)

                val baseColor = Color(0xFF1A1A22).copy(alpha = opacity)
                val armColor = armColor.copy(alpha = opacity)
                val pressedColor = pressedTipColor.copy(alpha = opacity * 0.8f)

                drawRoundRect(armColor, Offset(cx - armLen, cy - halfThick), Size(armLen * 2, armThick), cr)
                drawRoundRect(armColor, Offset(cx - halfThick, cy - armLen), Size(armThick, armLen * 2), cr)
                drawRoundRect(baseColor, Offset(cx - halfThick * 0.85f, cy - halfThick * 0.85f), Size(halfThick * 1.7f, halfThick * 1.7f), cr)

                val armTipLen = armLen * 0.42f
                val tipThick = armThick * 0.7f
                if (pressedDirs and BTN_UP != 0) drawRoundRect(pressedColor, Offset(cx - tipThick/2, cy - armLen), Size(tipThick, armTipLen), cr)
                if (pressedDirs and BTN_DOWN != 0) drawRoundRect(pressedColor, Offset(cx - tipThick/2, cy + armLen - armTipLen), Size(tipThick, armTipLen), cr)
                if (pressedDirs and BTN_LEFT != 0) drawRoundRect(pressedColor, Offset(cx - armLen, cy - tipThick/2), Size(armTipLen, tipThick), cr)
                if (pressedDirs and BTN_RIGHT != 0) drawRoundRect(pressedColor, Offset(cx + armLen - armTipLen, cy - tipThick/2), Size(armTipLen, tipThick), cr)

                val arrowSize = armThick * 0.18f
                val arrowOffset = armLen * 0.68f
                val dirs = listOf(Triple(0f, -1f, BTN_UP), Triple(0f, 1f, BTN_DOWN), Triple(-1f, 0f, BTN_LEFT), Triple(1f, 0f, BTN_RIGHT))
                for ((dx, dy, bit) in dirs) {
                    val ax = cx + dx * arrowOffset
                    val ay = cy + dy * arrowOffset
                    val isActive = pressedDirs and bit != 0
                    drawTriangle(ax, ay, dx, dy, arrowSize, if (isActive) Color(0xFF1A1A22) else Color(0x99FFFFFF))
                }
            }
        }
    }
}

private fun DrawScope.drawTriangle(cx: Float, cy: Float, dx: Float, dy: Float, size: Float, color: Color) {
    val sx = cx - dy * size; val sy = cy + dx * size
    val ex = cx + dy * size; val ey = cy - dx * size
    val tx = cx + dx * size * 1.5f; val ty = cy + dy * size * 1.5f
    drawPath(androidx.compose.ui.graphics.Path().apply { moveTo(sx, sy); lineTo(ex, ey); lineTo(tx, ty); close() }, color)
}

// ---------------------------------------------------------------------------
// AnalogStickCanvas — circular analog stick used when arcadeInputMode == "analog"
// ---------------------------------------------------------------------------
// Renders a circular base with directional arrows and a movable thumb circle.
// The thumb position is driven by `thumbX`/`thumbY` (in [-1, 1]) which are
// updated by the gesture handler in OnScreenController.
// Pressed directions (BTN_UP/DOWN/LEFT/RIGHT) highlight the corresponding
// arrow on the base rim, matching the D-Pad visual feedback style.
@Composable
private fun AnalogStickCanvas(
    layout: ButtonLayout,
    surfaceSize: IntSize,
    opacity: Float,
    pressedDirs: Int,
    thumbX: Float,
    thumbY: Float,
    thumbColor: Color = Color(0xFFFFD66B),
    thumbPressedColor: Color = Color(0xFFFFE57F),
    image: androidx.compose.ui.graphics.ImageBitmap? = null,
    pressedImage: androidx.compose.ui.graphics.ImageBitmap? = null
) {
    val density = LocalDensity.current
    val sizeDp = layout.sizeDp.dp
    val (px, py) = buttonOffset(layout, surfaceSize, density)
    val activeImage = if (pressedDirs != 0) (pressedImage ?: image) else image

    Box(
        modifier = Modifier
            .offset { IntOffset(px.toInt(), py.toInt()) }
            .size(sizeDp),
        contentAlignment = Alignment.Center
    ) {
        if (activeImage != null) {
            Image(
                bitmap = activeImage,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize().alpha(if (pressedDirs != 0) 0.9f else opacity)
            )
        } else {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val cx = size.width / 2f
                val cy = size.height / 2f
                val baseR = size.width * 0.48f  // outer base radius
                val thumbR = size.width * 0.22f // thumb cap radius

                val baseColor = Color(0xFF1A1A22).copy(alpha = opacity)
                val ringColor = Color(0xFF2C2C38).copy(alpha = opacity)
                val thumbColor = thumbColor.copy(alpha = opacity)
                val thumbPressedColor = thumbPressedColor.copy(alpha = (opacity * 1.2f).coerceAtMost(1f))
                val arrowColor = Color(0x99FFFFFF)

                // Outer base (filled circle)
                drawCircle(baseColor, baseR, Offset(cx, cy))
                // Outer ring
                drawCircle(ringColor, baseR, Offset(cx, cy), style = Stroke(width = 2.dp.toPx()))
                // Inner well (slightly darker)
                drawCircle(Color(0xFF101015).copy(alpha = opacity), baseR * 0.78f, Offset(cx, cy))

                // Directional arrows on the rim (same style as D-Pad)
                val arrowSize = baseR * 0.16f
                val arrowOffset = baseR * 0.72f
                val dirs = listOf(
                    Triple(0f, -1f, BTN_UP), Triple(0f, 1f, BTN_DOWN),
                    Triple(-1f, 0f, BTN_LEFT), Triple(1f, 0f, BTN_RIGHT)
                )
                for ((dx, dy, bit) in dirs) {
                    val ax = cx + dx * arrowOffset
                    val ay = cy + dy * arrowOffset
                    val isActive = pressedDirs and bit != 0
                    // When active, draw a small highlight circle behind the arrow
                    if (isActive) {
                        drawCircle(
                            Color(0xFFFFD66B).copy(alpha = opacity * 0.4f),
                            arrowSize * 1.8f,
                            Offset(ax, ay)
                        )
                    }
                    drawTriangle(
                        ax, ay, dx, dy, arrowSize,
                        if (isActive) Color(0xFFFFD66B).copy(alpha = opacity) else arrowColor
                    )
                }

                // Thumb cap position: center + offset * (baseR - thumbR)
                // (so the thumb stays within the base circle)
                val maxOffset = baseR - thumbR - 2.dp.toPx()
                val thumbCx = cx + thumbX * maxOffset
                val thumbCy = cy + thumbY * maxOffset
                val isPressed = pressedDirs != 0
                // Thumb shadow (slight offset for depth)
                drawCircle(
                    Color(0x44000000),
                    thumbR + 1.dp.toPx(),
                    Offset(thumbCx + 1f, thumbCy + 2f)
                )
                // Thumb cap
                drawCircle(
                    if (isPressed) thumbPressedColor else thumbColor,
                    thumbR,
                    Offset(thumbCx, thumbCy)
                )
                // Thumb highlight (top-left, gives 3D feel)
                drawCircle(
                    Color.White.copy(alpha = opacity * 0.25f),
                    thumbR * 0.5f,
                    Offset(thumbCx - thumbR * 0.25f, thumbCy - thumbR * 0.25f)
                )
            }
        }
    }
}

@Composable
private fun ActionButtonCanvas(
    label: String, color: Color, layout: ButtonLayout, surfaceSize: IntSize, opacity: Float, isPressed: Boolean,
    pressedColor: Color? = null,
    image: androidx.compose.ui.graphics.ImageBitmap? = null,
    pressedImage: androidx.compose.ui.graphics.ImageBitmap? = null
) {
    val density = LocalDensity.current
    val sizeDp = layout.sizeDp.dp
    val (px, py) = buttonOffset(layout, surfaceSize, density)
    val activeImage = if (isPressed) (pressedImage ?: image) else image

    Box(
        modifier = Modifier.offset { IntOffset(px.toInt(), py.toInt()) }.size(sizeDp),
        contentAlignment = Alignment.Center
    ) {
        if (activeImage != null) {
            Image(
                bitmap = activeImage,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize().alpha(if (isPressed) 0.9f else opacity)
            )
        } else {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val cx = size.width / 2f; val cy = size.height / 2f; val r = size.width * 0.46f
                drawCircle(color.copy(alpha = opacity * 0.3f), r + 3.dp.toPx(), Offset(cx, cy))
                drawCircle(
                    if (isPressed) (pressedColor ?: color.copy(alpha = (opacity * 1.5f).coerceAtMost(1f)))
                    else color.copy(alpha = opacity),
                    r, Offset(cx, cy)
                )
                drawCircle(Color.White.copy(alpha = if (isPressed) 0.1f else 0.15f), r * 0.7f, Offset(cx - r * 0.15f, cy - r * 0.15f))
            }
            Text(label, color = Color.White, fontSize = (sizeDp.value * 0.35f).sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
        }
    }
}

@Composable
private fun TurboButtonCanvas(
    label: String, color: Color, layout: ButtonLayout, surfaceSize: IntSize, opacity: Float, isPressed: Boolean,
    pressedColor: Color? = null,
    image: androidx.compose.ui.graphics.ImageBitmap? = null,
    pressedImage: androidx.compose.ui.graphics.ImageBitmap? = null
) {
    val density = LocalDensity.current
    val sizeDp = layout.sizeDp.dp
    val (px, py) = buttonOffset(layout, surfaceSize, density)
    val activeImage = if (isPressed) (pressedImage ?: image) else image

    Box(
        modifier = Modifier.offset { IntOffset(px.toInt(), py.toInt()) }.size(sizeDp),
        contentAlignment = Alignment.Center
    ) {
        if (activeImage != null) {
            Image(
                bitmap = activeImage,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize().alpha(if (isPressed) 0.9f else opacity)
            )
        } else {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val cx = size.width / 2f; val cy = size.height / 2f; val r = size.width * 0.44f
                drawCircle(color.copy(alpha = opacity * 0.4f), r + 2.dp.toPx(), Offset(cx, cy), style = Stroke(width = 1.5.dp.toPx()))
                drawCircle(
                    if (isPressed) (pressedColor ?: color.copy(alpha = (opacity * 1.5f).coerceAtMost(1f)))
                    else color.copy(alpha = opacity * 0.7f),
                    r, Offset(cx, cy)
                )
            }
            Text(label, color = Color.White.copy(alpha = 0.85f), fontSize = (sizeDp.value * 0.32f).sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Medium)
        }
    }
}

@Composable
private fun PillButtonCanvas(
    label: String, layout: ButtonLayout, surfaceSize: IntSize, opacity: Float, isPressed: Boolean,
    normalColor: Color = Color(0xFF2A3040), pressedColor: Color? = null,
    image: androidx.compose.ui.graphics.ImageBitmap? = null,
    pressedImage: androidx.compose.ui.graphics.ImageBitmap? = null
) {
    val density = LocalDensity.current
    val sizeDp = layout.sizeDp.dp
    val widthDp = sizeDp * 2.2f
    val heightDp = sizeDp * 0.7f
    val wPx = with(density) { widthDp.toPx() }
    val hPx = with(density) { heightDp.toPx() }
    val px = surfaceSize.width * layout.x - wPx / 2
    val py = surfaceSize.height * layout.y - hPx / 2
    val activeImage = if (isPressed) (pressedImage ?: image) else image

    Box(
        modifier = Modifier.offset { IntOffset(px.toInt(), py.toInt()) }.size(width = widthDp, height = heightDp),
        contentAlignment = Alignment.Center
    ) {
        if (activeImage != null) {
            Image(
                bitmap = activeImage,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize().alpha(if (isPressed) 0.9f else opacity)
            )
        } else {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val w = size.width; val h = size.height; val r = h * 0.4f
                val cr = androidx.compose.ui.geometry.CornerRadius(r, r)
                drawRoundRect(
                    if (isPressed) (pressedColor ?: normalColor.copy(alpha = (opacity * 1.5f).coerceAtMost(1f)))
                    else normalColor.copy(alpha = opacity),
                    Offset(0f, 0f), Size(w, h), cr
                )
                drawRoundRect(Color.White.copy(alpha = if (isPressed) 0.05f else 0.1f), Offset(w * 0.1f, h * 0.15f), Size(w * 0.8f, h * 0.25f), androidx.compose.ui.geometry.CornerRadius(r * 0.5f, r * 0.5f))
            }
            Text(label, color = Color.White.copy(alpha = 0.8f), fontSize = (sizeDp.value * 0.22f).sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Medium)
        }
    }
}

// Shoulder button (L/R) — wide pill-shaped, top corners
@Composable
private fun ShoulderButtonCanvas(
    label: String,
    layout: ButtonLayout,
    surfaceSize: IntSize,
    opacity: Float,
    isPressed: Boolean,
    normalColor: Color = Color(0xFF2A3040),
    pressedColor: Color? = null,
    image: androidx.compose.ui.graphics.ImageBitmap? = null,
    pressedImage: androidx.compose.ui.graphics.ImageBitmap? = null
) {
    val density = LocalDensity.current
    val sizeDp = layout.sizeDp.dp
    val widthDp = sizeDp * 1.6f
    val heightDp = sizeDp * 0.7f
    val wPx = with(density) { widthDp.toPx() }
    val hPx = with(density) { heightDp.toPx() }
    val px = surfaceSize.width * layout.x - wPx / 2
    val py = surfaceSize.height * layout.y - hPx / 2
    val activeImage = if (isPressed) (pressedImage ?: image) else image

    Box(
        modifier = Modifier.offset { IntOffset(px.toInt(), py.toInt()) }.size(width = widthDp, height = heightDp),
        contentAlignment = Alignment.Center
    ) {
        if (activeImage != null) {
            Image(
                bitmap = activeImage,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize().alpha(if (isPressed) 0.9f else opacity)
            )
        } else {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val w = size.width; val h = size.height; val r = h * 0.4f
                val cr = androidx.compose.ui.geometry.CornerRadius(r, r)
                drawRoundRect(
                    if (isPressed) (pressedColor ?: normalColor.copy(alpha = (opacity * 1.5f).coerceAtMost(1f)))
                    else normalColor.copy(alpha = opacity),
                    Offset(0f, 0f), Size(w, h), cr
                )
                drawRoundRect(Color.White.copy(alpha = if (isPressed) 0.05f else 0.1f), Offset(w * 0.1f, h * 0.15f), Size(w * 0.8f, h * 0.25f), androidx.compose.ui.geometry.CornerRadius(r * 0.5f, r * 0.5f))
            }
            Text(label, color = Color.White.copy(alpha = 0.85f), fontSize = (sizeDp.value * 0.28f).sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
        }
    }
}

// ---------------------------------------------------------------------------
// Menu overlay
// ---------------------------------------------------------------------------
@Composable
private fun MenuOverlay(
    gameTitle: String,
    running: Boolean,
    fastForwardSpeed: Int,
    isPortrait: Boolean = false,
    onTogglePause: () -> Unit,
    onToggleFastForward: () -> Unit,
    onCycleFFSpeed: () -> Unit,
    onScreenshot: () -> Unit,
    onSaveState: () -> Unit,
    onLoadState: () -> Unit,
    onReset: () -> Unit,
    onLayoutEditor: () -> Unit,
    onSettings: () -> Unit,
    onClose: () -> Unit,
    onExit: () -> Unit
) {
    Box(
        modifier = Modifier.fillMaxSize().background(Color(0x88000000))
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown()
                    // Don't consume — let horizontal drags reach the scrollable
                    // menu row below; only block clicks/taps on the backdrop.
                }
            }
    )
    // In landscape: menu bar at bottom; in portrait: at top
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = if (isPortrait) Alignment.TopCenter else Alignment.BottomCenter
    ) {
    // Focus requester that grabs focus when the menu opens so the D-pad
    // immediately controls the first menu button (pause) instead of being
    // stuck on the SurfaceView behind the overlay.
    val firstButtonFocus = remember { androidx.compose.ui.focus.FocusRequester() }
    androidx.compose.runtime.LaunchedEffect(Unit) {
        try { firstButtonFocus.requestFocus() } catch (_: Exception) {}
    }
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)
            .background(Color(0xDD1E2A3A), RoundedCornerShape(16.dp))
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .horizontalScroll(rememberScrollState()),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(gameTitle, color = Color.White, fontSize = 14.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold, modifier = Modifier.weight(1f).padding(end = 8.dp), maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
        Spacer(Modifier.width(4.dp))
        // Each IconButton is explicitly focusable so D-pad navigation works
        // on TV. The default IconButton is clickable but not focusable, which
        // makes TV remote navigation impossible.
        androidx.compose.foundation.layout.Row(horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.CenterVertically) {
            FocusableIconButton(onClick = onTogglePause, focusRequester = firstButtonFocus) { Icon(if (running) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, "暂停/继续", tint = Color.White) }
            FocusableIconButton(onClick = onToggleFastForward) { Icon(Icons.Rounded.FastForward, "快进", tint = if (fastForwardSpeed > 0) Color(0xFFFFD66B) else Color.White) }
            Text(
                if (fastForwardSpeed > 0) "${fastForwardSpeed}x" else "",
                color = Color(0xFFFFD66B),
                fontSize = 12.sp,
                modifier = Modifier.clickable { onCycleFFSpeed() }
            )
            FocusableIconButton(onClick = onScreenshot) { Icon(Icons.Rounded.CameraAlt, "截图", tint = Color.White) }
            FocusableIconButton(onClick = onSaveState) { Icon(Icons.Rounded.Save, "存档", tint = Color.White) }
            FocusableIconButton(onClick = onLoadState) { Icon(Icons.Rounded.Upload, "读档", tint = Color.White) }
            FocusableIconButton(onClick = onReset) { Icon(Icons.Rounded.Refresh, "重置", tint = Color(0xFFFFD66B)) }
            FocusableIconButton(onClick = onLayoutEditor) { Icon(Icons.Rounded.Tune, "手柄布局", tint = Color.White) }
            FocusableIconButton(onClick = onSettings) { Icon(Icons.Rounded.Settings, "设置", tint = Color.White) }
            FocusableIconButton(onClick = onClose) { Icon(Icons.Rounded.Fullscreen, "隐藏菜单", tint = Color(0xFF4A90D9)) }
            FocusableIconButton(onClick = onExit) { Icon(Icons.Rounded.Close, "退出", tint = Color(0xFFFF6B6B)) }
        }
    }
    }
}

/**
 * TV-friendly IconButton wrapper — explicitly focusable so the D-pad can
 * navigate between buttons on TV. Shows a subtle highlight when focused.
 * Optionally accepts a FocusRequester so the caller can programmatically
 * grab focus (e.g. when the menu opens).
 */
@Composable
private fun FocusableIconButton(
    onClick: () -> Unit,
    focusRequester: androidx.compose.ui.focus.FocusRequester? = null,
    content: @Composable () -> Unit
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val baseModifier = Modifier
        .size(40.dp)
    val mod = if (focusRequester != null) {
        baseModifier.focusRequester(focusRequester)
    } else {
        baseModifier
    }
    Box(
        modifier = mod
            .focusable(interactionSource = interaction)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        if (focused) {
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .background(Color.White.copy(alpha = 0.25f), RoundedCornerShape(8.dp))
            )
        }
        content()
    }
}

// ---------------------------------------------------------------------------
// State slot picker dialog — choose a save slot (0-9) for save/load state
//
// Shows a list of 10 slots (one per row) with:
//   - Slot number (0-9)
//   - Whether a savestate file exists for THIS game in this slot
//   - The file's last-modified timestamp (formatted as yyyy-MM-dd HH:mm)
//   - File size (KB)
// Empty slots are dimmed and show "空槽位" in load mode.
//
// Savestate files are per-game: <savesDir>/<gameId>_slot<N>.state
// Each game has its own 10 slots, independent of other games.
// ---------------------------------------------------------------------------
@Composable
private fun SlotPickerDialog(
    mode: String, // "save" | "load"
    currentSlot: Int,
    gameId: String,
    savesDir: java.io.File,
    gameTitle: String,
    onSlotSelected: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    // Re-scan the saves directory every time the dialog opens so the
    // slot status is fresh (a savestate written this session shows up).
    val slotStates = remember(gameId, savesDir, mode) {
        (0..9).map { slot ->
            val file = java.io.File(savesDir, "${gameId}_slot${slot}.state")
            if (file.exists()) {
                SlotState(slot, exists = true, lastModified = file.lastModified(), sizeBytes = file.length())
            } else {
                SlotState(slot, exists = false, lastModified = 0L, sizeBytes = 0L)
            }
        }
    }

    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column {
                Text(if (mode == "save") "保存即时存档" else "读取即时存档")
                Text(
                    text = gameTitle,
                    fontSize = 12.sp,
                    color = Color.Gray,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    if (mode == "save")
                        "选择槽位覆盖存档（每个游戏独立 10 槽）"
                    else
                        "选择槽位读取（每个游戏独立 10 槽）",
                    fontSize = 12.sp,
                    color = Color.Gray,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
                // Scrollable list of 10 slot rows — each row shows slot number,
                // timestamp, and file size. Far more readable than the old
                // 2×5 grid of tiny 40dp buttons.
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 360.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    items(slotStates, key = { it.slot }) { state ->
                        SlotRow(
                            state = state,
                            isCurrent = state.slot == currentSlot,
                            mode = mode,
                            onClick = { onSlotSelected(state.slot) }
                        )
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            androidx.compose.material3.TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}

/** Per-slot state: whether a savestate file exists, and its metadata. */
private data class SlotState(
    val slot: Int,
    val exists: Boolean,
    val lastModified: Long,  // epoch millis
    val sizeBytes: Long
)

/** Format epoch millis as "yyyy-MM-dd HH:mm". */
private fun formatSlotTime(epochMillis: Long): String {
    if (epochMillis <= 0L) return ""
    val cal = java.util.Calendar.getInstance().apply { timeInMillis = epochMillis }
    val y = cal.get(java.util.Calendar.YEAR)
    val mo = cal.get(java.util.Calendar.MONTH) + 1
    val d = cal.get(java.util.Calendar.DAY_OF_MONTH)
    val h = cal.get(java.util.Calendar.HOUR_OF_DAY)
    val mi = cal.get(java.util.Calendar.MINUTE)
    return "%04d-%02d-%02d %02d:%02d".format(y, mo, d, h, mi)
}

/** Format file size as "N.N KB" or "N bytes". */
private fun formatSlotSize(bytes: Long): String {
    return if (bytes >= 1024) {
        "%.1f KB".format(bytes / 1024.0)
    } else {
        "$bytes B"
    }
}

@Composable
private fun SlotRow(
    state: SlotState,
    isCurrent: Boolean,
    mode: String,
    onClick: () -> Unit
) {
    val hasSave = state.exists
    // In load mode, empty slots are not clickable
    val clickable = mode == "save" || hasSave
    val bg = when {
        isCurrent -> Color(0xFF4F8AC4)
        hasSave -> Color(0xFF2A3B52)
        else -> Color(0xFF1A1A2E)
    }
    val fg = if (isCurrent) Color.White else if (hasSave) Color(0xFFE0E0E0) else Color(0xFF707080)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(bg)
            .then(if (clickable) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Slot number badge
        Box(
            modifier = Modifier
                .size(32.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(if (isCurrent) Color.White.copy(alpha = 0.25f) else Color(0xFF0D1421)),
            contentAlignment = Alignment.Center
        ) {
            Text(
                "${state.slot}",
                color = fg,
                fontSize = 15.sp,
                fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
            )
        }
        Spacer(Modifier.width(12.dp))
        // Slot info: timestamp + size, or "empty"
        Column(modifier = Modifier.weight(1f)) {
            if (hasSave) {
                Text(
                    formatSlotTime(state.lastModified),
                    color = fg,
                    fontSize = 13.sp,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.Medium
                )
                Text(
                    formatSlotSize(state.sizeBytes),
                    color = fg.copy(alpha = 0.7f),
                    fontSize = 11.sp
                )
            } else {
                Text(
                    if (mode == "save") "空槽位 — 点击保存" else "空槽位",
                    color = fg,
                    fontSize = 13.sp
                )
            }
        }
        // Status indicator on the right
        if (hasSave) {
            Text(
                "●",
                color = if (isCurrent) Color.White else Color(0xFF4ADE80),
                fontSize = 14.sp
            )
        } else if (mode == "load") {
            Text(
                "—",
                color = fg.copy(alpha = 0.5f),
                fontSize = 14.sp
            )
        }
    }
}

// ---------------------------------------------------------------------------
// DOS-specific pad layout editor
//
// Full editor for the DOS gamepad overlay. Supports:
//   - Drag any visible button to reposition (landscape/portrait independent)
//   - Tap a button to select it; use the bottom slider to resize
//   - Toggle each button's visibility (show/hide) via checkboxes
//   - Opacity slider (shared with all platforms via padLayout.opacity)
//   - Input mode toggle (gamepad <-> keyboard)
//   - Reset to defaults
//
// Button positions are stored in PadLayout as ButtonLayout(x, y, sizeDp)
// where x/y are fractions of the screen (0.0-1.0). The DosGamepadOverlay
// reads these positions at render time.
// ---------------------------------------------------------------------------
@Composable
private fun DosPadLayoutEditor(
    padLayout: PadLayout,
    isPortrait: Boolean,
    onLayoutChange: (PadLayout) -> Unit,
    surfaceSize: IntSize,
    onClose: () -> Unit
) {
    var selectedBtn by remember { mutableStateOf<DosBtnType?>(null) }
    var selectedExtraKey by remember { mutableStateOf<DosExtraKeyEntry?>(null) }

    // Parse the extra keys list for the current orientation
    val extraKeysList = remember(padLayout, isPortrait) {
        val json = if (isPortrait) padLayout.dosExtraKeysP else padLayout.dosExtraKeys
        DosExtraKeyEntry.parseList(json)
    }

    fun getExtraKeys(): List<DosExtraKeyEntry> = extraKeysList

    fun addExtraKey(entry: DosExtraKeyEntry) {
        val current = getExtraKeys()
        if (current.any { it.keyCode == entry.keyCode }) return // already added
        val newList = current + entry
        val json = DosExtraKeyEntry.formatList(newList)
        onLayoutChange(
            if (isPortrait) padLayout.copy {dosExtraKeysP = json}
            else padLayout.copy {dosExtraKeys = json}
        )
    }

    fun removeExtraKey(keyCode: Int) {
        val current = getExtraKeys()
        val newList = current.filter { it.keyCode != keyCode }
        val json = DosExtraKeyEntry.formatList(newList)
        onLayoutChange(
            if (isPortrait) padLayout.copy {dosExtraKeysP = json}
            else padLayout.copy {dosExtraKeys = json}
        )
    }

    fun updateExtraKey(oldKeyCode: Int, newEntry: DosExtraKeyEntry) {
        val current = getExtraKeys()
        val newList = current.map { if (it.keyCode == oldKeyCode) newEntry else it }
        val json = DosExtraKeyEntry.formatList(newList)
        onLayoutChange(
            if (isPortrait) padLayout.copy {dosExtraKeysP = json}
            else padLayout.copy {dosExtraKeys = json}
        )
    }

    // Get the current landscape or portrait layout for each button.
    fun getLayout(btn: DosBtnType): ButtonLayout =
        if (isPortrait) btn.portraitLayout(padLayout) else btn.landscapeLayout(padLayout)

    // Update a button's layout (writes back to the correct landscape/portrait field).
    fun updateBtn(btn: DosBtnType, newLayout: ButtonLayout) {
        onLayoutChange(
            if (isPortrait) btn.updatePortrait(padLayout, newLayout)
            else btn.updateLandscape(padLayout, newLayout)
        )
    }

    // Toggle a button's visibility.
    fun toggleVisible(btn: DosBtnType) {
        onLayoutChange(btn.toggleVisible(padLayout))
    }

    // All available extra keys (not in DosBtnType) that can be added
    val allAvailableExtraKeys = remember {
        listOf(
            // Letters A-Z
            "A" to DosKeys.A, "B" to DosKeys.B, "C" to DosKeys.C, "D" to DosKeys.D,
            "E" to DosKeys.E, "F" to DosKeys.F, "G" to DosKeys.G, "H" to DosKeys.H,
            "I" to DosKeys.I, "J" to DosKeys.J, "K" to DosKeys.K, "L" to DosKeys.L,
            "M" to DosKeys.M, "N" to DosKeys.N, "O" to DosKeys.O, "P" to DosKeys.P,
            "Q" to DosKeys.Q, "R" to DosKeys.R, "S" to DosKeys.S, "T" to DosKeys.T,
            "U" to DosKeys.U, "V" to DosKeys.V, "W" to DosKeys.W, "X" to DosKeys.X,
            "Y" to DosKeys.Y, "Z" to DosKeys.Z,
            // Digits 0-9
            "0" to DosKeys.K0, "1" to DosKeys.K1, "2" to DosKeys.K2, "3" to DosKeys.K3,
            "4" to DosKeys.K4, "5" to DosKeys.K5, "6" to DosKeys.K6, "7" to DosKeys.K7,
            "8" to DosKeys.K8, "9" to DosKeys.K9,
            // Function keys
            "F1" to DosKeys.F1, "F2" to DosKeys.F2, "F3" to DosKeys.F3, "F4" to DosKeys.F4,
            "F5" to DosKeys.F5, "F6" to DosKeys.F6, "F7" to DosKeys.F7, "F8" to DosKeys.F8,
            "F9" to DosKeys.F9, "F10" to DosKeys.F10, "F11" to DosKeys.F11, "F12" to DosKeys.F12,
            // Arrow keys
            "↑" to DosKeys.UP, "↓" to DosKeys.DOWN, "←" to DosKeys.LEFT, "→" to DosKeys.RIGHT,
            // Symbols
            "-" to DosKeys.MINUS, "=" to DosKeys.EQUALS,
            "[" to DosKeys.LEFTBRACKET, "]" to DosKeys.RIGHTBRACKET,
            ";" to DosKeys.SEMICOLON, "'" to DosKeys.APOSTROPHE,
            "," to DosKeys.COMMA, "." to DosKeys.PERIOD, "/" to DosKeys.SLASH,
            "\\" to DosKeys.BACKSLASH, "`" to DosKeys.GRAVE,
            // Mouse middle button
            "M中" to -2,  // special keyCode for mouse middle button
            // Navigation extra
            "PgUp" to DosKeys.PAGEUP, "PgDn" to DosKeys.PAGEDOWN,
            "Caps" to DosKeys.CAPSLOCK, "NumLk" to DosKeys.NUMLOCK
        )
    }

    // Dialog state for add/delete
    var showAddDialog by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }

    Box(modifier = Modifier.fillMaxSize().background(Color(0x66000000))) {
        // === Editable button previews (full screen, behind the control panel) ===
        Box(modifier = Modifier.fillMaxSize()) {
            DosBtnType.values().forEach { btnType ->
                val layout = getLayout(btnType)
                val visible = btnType.isVisible(padLayout)
                if (visible) {
                    DosEditableButton(
                        label = btnType.label,
                        color = btnType.color,
                        layout = layout,
                        surfaceSize = surfaceSize,
                        isSelected = selectedBtn == btnType,
                        onMove = { nx, ny ->
                            updateBtn(btnType, layout.copy(
                                x = nx.coerceIn(0.02f, 0.98f),
                                y = ny.coerceIn(0.02f, 0.98f)
                            ))
                        },
                        onSelect = { selectedBtn = btnType; selectedExtraKey = null },
                        onLongPress = { toggleVisible(btnType) }
                    )
                }
            }
            // Render extra key buttons (letters, numbers, etc.)
            extraKeysList.forEach { entry ->
                val isSel = selectedExtraKey?.keyCode == entry.keyCode
                DosEditableButton(
                    label = entry.label,
                    color = Color(0xFF3498DB),
                    layout = ButtonLayout(x = entry.x, y = entry.y, sizeDp = entry.sizeDp),
                    surfaceSize = surfaceSize,
                    isSelected = isSel,
                    onMove = { nx, ny ->
                        updateExtraKey(entry.keyCode, entry.copy(
                            x = nx.coerceIn(0.02f, 0.98f),
                            y = ny.coerceIn(0.02f, 0.98f)
                        ))
                    },
                    onSelect = { selectedExtraKey = entry; selectedBtn = null },
                    onLongPress = { removeExtraKey(entry.keyCode) }
                )
            }
        }

        // === Centered control panel (combines toolbar + controls) ===
        // Keep NARROW and at TopCenter so it doesn't cover the on-screen
        // buttons being dragged (same fix as PadLayoutEditor).
        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 8.dp)
                .widthIn(min = 240.dp, max = 320.dp)
                .background(Color(0xDD1E2A3A), RoundedCornerShape(12.dp))
                .padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            // --- Top toolbar row ---
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "DOS 按键设置",
                    color = Color.White,
                    fontSize = 13.sp,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
                )
                Spacer(Modifier.weight(1f))
                Text(
                    if (isPortrait) "(竖屏)" else "(横屏)",
                    color = Color(0xFF8899AA),
                    fontSize = 10.sp
                )
                Spacer(Modifier.size(6.dp))
                IconButton(onClick = {
                    val defaults = PadLayout()
                    onLayoutChange(padLayout.copy {
                        dosDpad = defaults.dosDpad
 dosBtnEsc = defaults.dosBtnEsc
                        dosBtnEnter = defaults.dosBtnEnter
 dosBtnSpace = defaults.dosBtnSpace
                        dosBtnTab = defaults.dosBtnTab
 dosBtnCtrl = defaults.dosBtnCtrl
                        dosBtnAlt = defaults.dosBtnAlt
 dosBtnShift = defaults.dosBtnShift
                        dosBtnBack = defaults.dosBtnBack
                        dosBtnMouseL = defaults.dosBtnMouseL
 dosBtnMouseR = defaults.dosBtnMouseR
                        dosBtnInsert = defaults.dosBtnInsert
 dosBtnDelete = defaults.dosBtnDelete
                        dosBtnHome = defaults.dosBtnHome
 dosBtnEnd = defaults.dosBtnEnd
                        dosBtnPageUp = defaults.dosBtnPageUp
 dosBtnPageDown = defaults.dosBtnPageDown
                        dosDpadP = defaults.dosDpadP
 dosBtnEscP = defaults.dosBtnEscP
                        dosBtnEnterP = defaults.dosBtnEnterP
 dosBtnSpaceP = defaults.dosBtnSpaceP
                        dosBtnTabP = defaults.dosBtnTabP
 dosBtnCtrlP = defaults.dosBtnCtrlP
                        dosBtnAltP = defaults.dosBtnAltP
 dosBtnShiftP = defaults.dosBtnShiftP
                        dosBtnBackP = defaults.dosBtnBackP
                        dosBtnMouseLP = defaults.dosBtnMouseLP
 dosBtnMouseRP = defaults.dosBtnMouseRP
                        dosBtnInsertP = defaults.dosBtnInsertP
 dosBtnDeleteP = defaults.dosBtnDeleteP
                        dosBtnHomeP = defaults.dosBtnHomeP
 dosBtnEndP = defaults.dosBtnEndP
                        dosBtnPageUpP = defaults.dosBtnPageUpP
 dosBtnPageDownP = defaults.dosBtnPageDownP
                        dosShowDpad = true
 dosShowEsc = true
 dosShowEnter = true
                        dosShowSpace = true
 dosShowTab = true
 dosShowCtrl = true
                        dosShowAlt = true
 dosShowShift = true
 dosShowBack = true
                        dosShowMouseL = true
 dosShowMouseR = true
                        dosShowInsert = false
 dosShowDelete = false
 dosShowHome = false
                        dosShowEnd = false
 dosShowPageUp = false
 dosShowPageDown = false
                        dosExtraKeys = ""
 dosExtraKeysP = ""
                    })
                }) {
                    Icon(Icons.Rounded.Refresh, "重置", tint = Color(0xFFFFD66B))
                }
                IconButton(onClick = onClose) {
                    Text(
                        "完成",
                        color = Color.White,
                        fontSize = 13.sp,
                        fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
                    )
                }
            }

            Spacer(Modifier.size(4.dp))
            // --- Opacity row (compact) ---
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("透明度", color = Color.White, fontSize = 10.sp,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)
                Spacer(Modifier.weight(1f))
                Text("${(padLayout.opacity * 100).toInt()}%", color = Color(0xFFFFD66B), fontSize = 10.sp)
            }
            Slider(
                value = padLayout.opacity,
                onValueChange = { newVal ->
                    onLayoutChange(padLayout.copy {opacity = newVal.coerceIn(0.3f, 1.0f)})
                },
                valueRange = 0.3f..1.0f,
                colors = SliderDefaults.colors(
                    thumbColor = Color(0xFFFFD66B),
                    activeTrackColor = Color(0xFFFFD66B),
                    inactiveTrackColor = Color(0xFF4A5568)
                )
            )

            // --- Input mode toggle (compact) ---
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(6.dp))
                        .background(
                            if (padLayout.dosInputMode == "gamepad") Color(0xFFFFD66B)
                            else Color(0xFF2A3A4A)
                        )
                        .border(
                            1.dp,
                            if (padLayout.dosInputMode == "gamepad") Color(0xFFFFD66B)
                            else Color(0xFF4A5568),
                            RoundedCornerShape(6.dp)
                        )
                        .pointerInput(Unit) {
                            detectTapGestures {
                                onLayoutChange(padLayout.copy {dosInputMode = "gamepad"})
                            }
                        }
                        .padding(vertical = 4.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        "手柄",
                        color = if (padLayout.dosInputMode == "gamepad") Color.Black else Color.White,
                        fontSize = 10.sp,
                        fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold
                    )
                }
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(6.dp))
                        .background(
                            if (padLayout.dosInputMode == "keyboard") Color(0xFFFFD66B)
                            else Color(0xFF2A3A4A)
                        )
                        .border(
                            1.dp,
                            if (padLayout.dosInputMode == "keyboard") Color(0xFFFFD66B)
                            else Color(0xFF4A5568),
                            RoundedCornerShape(6.dp)
                        )
                        .pointerInput(Unit) {
                            detectTapGestures {
                                onLayoutChange(padLayout.copy {dosInputMode = "keyboard"})
                            }
                        }
                        .padding(vertical = 4.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        "键盘",
                        color = if (padLayout.dosInputMode == "keyboard") Color.Black else Color.White,
                        fontSize = 10.sp,
                        fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold
                    )
                }
            }

            Spacer(Modifier.size(3.dp))

            // --- Selected button size slider ---
            val sel = selectedBtn
            val selExtra = selectedExtraKey
            if (sel != null) {
                val currentSize = getLayout(sel).sizeDp
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("${sel.label} 大小", color = Color.White, fontSize = 10.sp,
                        fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)
                    Spacer(Modifier.weight(1f))
                    Text("${currentSize}dp", color = Color(0xFFFFD66B), fontSize = 10.sp)
                }
                Slider(
                    value = currentSize.toFloat(),
                    onValueChange = { newVal ->
                        val intVal = newVal.toInt()
                        updateBtn(sel, getLayout(sel).copy(sizeDp = intVal))
                    },
                    valueRange = (sel.minSize).toFloat()..(sel.maxSize).toFloat(),
                    colors = SliderDefaults.colors(
                        thumbColor = Color(0xFFFFD66B),
                        activeTrackColor = Color(0xFFFFD66B),
                        inactiveTrackColor = Color(0xFF4A5568)
                    )
                )
            } else if (selExtra != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("${selExtra.label} 大小", color = Color.White, fontSize = 10.sp,
                        fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)
                    Spacer(Modifier.weight(1f))
                    Text("${selExtra.sizeDp}dp", color = Color(0xFFFFD66B), fontSize = 10.sp)
                }
                Slider(
                    value = selExtra.sizeDp.toFloat(),
                    onValueChange = { newVal ->
                        val intVal = newVal.toInt()
                        updateExtraKey(selExtra.keyCode, selExtra.copy(sizeDp = intVal))
                    },
                    valueRange = 24f..80f,
                    colors = SliderDefaults.colors(
                        thumbColor = Color(0xFFFFD66B),
                        activeTrackColor = Color(0xFFFFD66B),
                        inactiveTrackColor = Color(0xFF4A5568)
                    )
                )
            }

            // --- Add / Delete buttons ---
            Spacer(Modifier.size(2.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                // Add button
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(6.dp))
                        .background(Color(0xFF2ECC71).copy(alpha = 0.15f))
                        .border(1.dp, Color(0xFF2ECC71), RoundedCornerShape(6.dp))
                        .pointerInput(Unit) { detectTapGestures { showAddDialog = true } }
                        .padding(vertical = 4.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text("＋ 添加", color = Color(0xFF2ECC71), fontSize = 11.sp,
                        fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)
                }
                // Delete button
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(6.dp))
                        .background(Color(0xFFFF8888).copy(alpha = 0.12f))
                        .border(1.dp, Color(0xFFFF8888), RoundedCornerShape(6.dp))
                        .pointerInput(Unit) { detectTapGestures { showDeleteDialog = true } }
                        .padding(vertical = 4.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text("✕ 删除", color = Color(0xFFFF8888), fontSize = 11.sp,
                        fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)
                }
            }

            Text(
                "拖动移动 · 点击选中调大小 · 长按删除",
                color = Color(0xFF8899AA),
                fontSize = 8.sp
            )
        }

        // === Add key dialog ===
        if (showAddDialog) {
            DosKeyPickerDialog(
                title = "添加按键",
                existingExtraKeyCodes = extraKeysList.map { it.keyCode },
                padLayout = padLayout,
                allAvailableKeys = allAvailableExtraKeys,
                onAddFixedBtn = { btnType ->
                    if (!btnType.isVisible(padLayout)) toggleVisible(btnType)
                },
                onAddExtraKey = { entry -> addExtraKey(entry) },
                onDismiss = { showAddDialog = false }
            )
        }

        // === Delete key dialog ===
        if (showDeleteDialog) {
            DosKeyDeleteDialog(
                padLayout = padLayout,
                extraKeys = extraKeysList,
                onDeleteFixedBtn = { btnType ->
                    if (btnType.isVisible(padLayout)) toggleVisible(btnType)
                },
                onDeleteExtraKey = { keyCode -> removeExtraKey(keyCode) },
                onDismiss = { showDeleteDialog = false }
            )
        }
    }
}

/** Dialog for adding keys — shows all available keys organized by category. */
@Composable
private fun DosKeyPickerDialog(
    title: String,
    existingExtraKeyCodes: List<Int>,
    padLayout: PadLayout,
    allAvailableKeys: List<Pair<String, Int>>,
    onAddFixedBtn: (DosBtnType) -> Unit,
    onAddExtraKey: (DosExtraKeyEntry) -> Unit,
    onDismiss: () -> Unit
) {
    // Compute which fixed buttons are hidden (can be added)
    val hiddenFixedBtns = DosBtnType.values().filter { !it.isVisible(padLayout) }
    // Compute which extra keys are not yet added
    val availableExtraKeys = allAvailableKeys.filter { (_, code) ->
        code !in existingExtraKeyCodes
    }

    // Categorize extra keys
    val letters = availableExtraKeys.filter { it.second in 97..122 }
    val digits = availableExtraKeys.filter { it.second in 48..57 }
    val fKeys = availableExtraKeys.filter { it.second in 282..293 }
    val arrows = availableExtraKeys.filter { it.second in listOf(273,274,275,276) }
    val symbols = availableExtraKeys.filter { it.second in listOf(45,61,91,93,59,39,44,46,47,92,96) }
    val mouseKeys = availableExtraKeys.filter { it.second < 0 }
    val otherKeys = availableExtraKeys.filter { it !in letters && it !in digits && it !in fKeys && it !in arrows && it !in symbols && it !in mouseKeys }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xAA000000))
            .pointerInput(Unit) { detectTapGestures { onDismiss() } }
    ) {
        Column(
            modifier = Modifier
                .align(Alignment.Center)
                .fillMaxWidth(0.9f)
                .heightIn(max = 400.dp)
                .background(Color(0xEE1E2A3A), RoundedCornerShape(16.dp))
                .padding(16.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, color = Color.White, fontSize = 15.sp,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                Spacer(Modifier.weight(1f))
                Text("点击外部关闭", color = Color(0xFF8899AA), fontSize = 10.sp)
            }
            Spacer(Modifier.size(8.dp))

            // Fixed buttons (from DosBtnType)
            if (hiddenFixedBtns.isNotEmpty()) {
                Text("固定按键", color = Color(0xFFFFD66B), fontSize = 12.sp,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)
                Spacer(Modifier.size(4.dp))
                KeyGrid(hiddenFixedBtns.map { it.label to it }, cols = 5) { (_, btnType) ->
                    onAddFixedBtn(btnType)
                }
                Spacer(Modifier.size(8.dp))
            }

            // Letters
            if (letters.isNotEmpty()) {
                Text("字母", color = Color(0xFF3498DB), fontSize = 12.sp,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)
                Spacer(Modifier.size(4.dp))
                KeyGrid(letters, cols = 9) { (label, code) ->
                    onAddExtraKey(DosExtraKeyEntry(
                        keyCode = code, label = label,
                        x = 0.5f, y = 0.5f, sizeDp = 36
                    ))
                }
                Spacer(Modifier.size(8.dp))
            }

            // Digits
            if (digits.isNotEmpty()) {
                Text("数字", color = Color(0xFF2ECC71), fontSize = 12.sp,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)
                Spacer(Modifier.size(4.dp))
                KeyGrid(digits, cols = 5) { (label, code) ->
                    onAddExtraKey(DosExtraKeyEntry(
                        keyCode = code, label = label,
                        x = 0.5f, y = 0.5f, sizeDp = 36
                    ))
                }
                Spacer(Modifier.size(8.dp))
            }

            // F-keys
            if (fKeys.isNotEmpty()) {
                Text("功能键", color = Color(0xFF9B59B6), fontSize = 12.sp,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)
                Spacer(Modifier.size(4.dp))
                KeyGrid(fKeys, cols = 6) { (label, code) ->
                    onAddExtraKey(DosExtraKeyEntry(
                        keyCode = code, label = label,
                        x = 0.5f, y = 0.5f, sizeDp = 32
                    ))
                }
                Spacer(Modifier.size(8.dp))
            }

            // Arrows
            if (arrows.isNotEmpty()) {
                Text("方向键", color = Color(0xFFE67E22), fontSize = 12.sp,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)
                Spacer(Modifier.size(4.dp))
                KeyGrid(arrows, cols = 4) { (label, code) ->
                    onAddExtraKey(DosExtraKeyEntry(
                        keyCode = code, label = label,
                        x = 0.5f, y = 0.5f, sizeDp = 36
                    ))
                }
                Spacer(Modifier.size(8.dp))
            }

            // Symbols
            if (symbols.isNotEmpty()) {
                Text("符号", color = Color(0xFF1ABC9C), fontSize = 12.sp,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)
                Spacer(Modifier.size(4.dp))
                KeyGrid(symbols, cols = 6) { (label, code) ->
                    onAddExtraKey(DosExtraKeyEntry(
                        keyCode = code, label = label,
                        x = 0.5f, y = 0.5f, sizeDp = 32
                    ))
                }
                Spacer(Modifier.size(8.dp))
            }

            // Mouse keys
            if (mouseKeys.isNotEmpty()) {
                Text("鼠标", color = Color(0xFFFFD66B), fontSize = 12.sp,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)
                Spacer(Modifier.size(4.dp))
                KeyGrid(mouseKeys, cols = 4) { (label, code) ->
                    onAddExtraKey(DosExtraKeyEntry(
                        keyCode = code, label = label,
                        x = 0.5f, y = 0.5f, sizeDp = 36
                    ))
                }
                Spacer(Modifier.size(8.dp))
            }

            // Other
            if (otherKeys.isNotEmpty()) {
                Text("其他", color = Color(0xFF8899AA), fontSize = 12.sp,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)
                Spacer(Modifier.size(4.dp))
                KeyGrid(otherKeys, cols = 5) { (label, code) ->
                    onAddExtraKey(DosExtraKeyEntry(
                        keyCode = code, label = label,
                        x = 0.5f, y = 0.5f, sizeDp = 32
                    ))
                }
            }
        }
    }
}

/** Reusable key grid component. */
@Composable
private fun <T> KeyGrid(
    items: List<T>,
    cols: Int = 5,
    onSelect: (T) -> Unit
) {
    val rows = items.chunked(cols)
    rows.forEach { rowItems ->
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            rowItems.forEach { item ->
                val label = when (item) {
                    is DosBtnType -> item.label
                    is Pair<*, *> -> item.first as? String ?: ""
                    else -> item.toString()
                }
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(6.dp))
                        .background(Color(0xFF2ECC71).copy(alpha = 0.15f))
                        .border(1.dp, Color(0xFF2ECC71).copy(alpha = 0.5f), RoundedCornerShape(6.dp))
                        .pointerInput(item) { detectTapGestures { onSelect(item) } }
                        .padding(vertical = 5.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        "+ $label",
                        color = Color(0xFF2ECC71),
                        fontSize = 10.sp,
                        fontWeight = androidx.compose.ui.text.font.FontWeight.Medium
                    )
                }
            }
            repeat(cols - rowItems.size) { Spacer(Modifier.weight(1f)) }
        }
        Spacer(Modifier.size(3.dp))
    }
}

/** Dialog for deleting keys — shows all currently visible keys. */
@Composable
private fun DosKeyDeleteDialog(
    padLayout: PadLayout,
    extraKeys: List<DosExtraKeyEntry>,
    onDeleteFixedBtn: (DosBtnType) -> Unit,
    onDeleteExtraKey: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    val visibleFixedBtns = DosBtnType.values().filter { it.isVisible(padLayout) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xAA000000))
            .pointerInput(Unit) { detectTapGestures { onDismiss() } }
    ) {
        Column(
            modifier = Modifier
                .align(Alignment.Center)
                .fillMaxWidth(0.85f)
                .heightIn(max = 350.dp)
                .background(Color(0xEE1E2A3A), RoundedCornerShape(16.dp))
                .padding(16.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("删除按键", color = Color.White, fontSize = 15.sp,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                Spacer(Modifier.weight(1f))
                Text("点击外部关闭", color = Color(0xFF8899AA), fontSize = 10.sp)
            }
            Spacer(Modifier.size(8.dp))

            // Fixed buttons
            if (visibleFixedBtns.isNotEmpty()) {
                Text("固定按键", color = Color(0xFFFFD66B), fontSize = 12.sp,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)
                Spacer(Modifier.size(4.dp))
                val rows = visibleFixedBtns.chunked(5)
                rows.forEach { rowBtns ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(3.dp)
                    ) {
                        rowBtns.forEach { btnType ->
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(Color(0xFFFF8888).copy(alpha = 0.12f))
                                    .border(1.dp, Color(0xFFFF8888).copy(alpha = 0.5f), RoundedCornerShape(6.dp))
                                    .pointerInput(btnType) {
                                        detectTapGestures { onDeleteFixedBtn(btnType) }
                                    }
                                    .padding(vertical = 5.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    "− ${btnType.label}",
                                    color = Color(0xFFFF8888),
                                    fontSize = 10.sp,
                                    fontWeight = androidx.compose.ui.text.font.FontWeight.Medium
                                )
                            }
                        }
                        repeat(5 - rowBtns.size) { Spacer(Modifier.weight(1f)) }
                    }
                    Spacer(Modifier.size(3.dp))
                }
                Spacer(Modifier.size(8.dp))
            }

            // Extra keys (letters, numbers, etc.)
            if (extraKeys.isNotEmpty()) {
                Text("自定义按键", color = Color(0xFF3498DB), fontSize = 12.sp,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)
                Spacer(Modifier.size(4.dp))
                val rows = extraKeys.chunked(5)
                rows.forEach { rowKeys ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(3.dp)
                    ) {
                        rowKeys.forEach { entry ->
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(Color(0xFFFF8888).copy(alpha = 0.12f))
                                    .border(1.dp, Color(0xFFFF8888).copy(alpha = 0.5f), RoundedCornerShape(6.dp))
                                    .pointerInput(entry.keyCode) {
                                        detectTapGestures { onDeleteExtraKey(entry.keyCode) }
                                    }
                                    .padding(vertical = 5.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    "− ${entry.label}",
                                    color = Color(0xFFFF8888),
                                    fontSize = 10.sp,
                                    fontWeight = androidx.compose.ui.text.font.FontWeight.Medium
                                )
                            }
                        }
                        repeat(5 - rowKeys.size) { Spacer(Modifier.weight(1f)) }
                    }
                    Spacer(Modifier.size(3.dp))
                }
            }
        }
    }
}

// DOS button type enum for the editor.
private enum class DosBtnType(
    val label: String,
    val color: Color,
    val minSize: Int,
    val maxSize: Int
) {
    DPAD("D-Pad", Color(0xFFFFD66B), 80, 220),
    ESC("Esc", Color(0xFFE74C3C), 36, 100),
    ENTER("Enter", Color(0xFF2ECC71), 36, 100),
    SPACE("Space", Color(0xFF3498DB), 36, 100),
    TAB("Tab", Color(0xFF9B59B6), 36, 100),
    CTRL("Ctrl", Color(0xFFE67E22), 32, 90),
    ALT("Alt", Color(0xFFE67E22), 32, 90),
    SHIFT("Shift", Color(0xFFE67E22), 32, 90),
    BACK("Back", Color(0xFFE67E22), 32, 90),
    MOUSE_L("L", Color(0xFFFFD66B), 28, 80),
    MOUSE_R("R", Color(0xFFFFD66B), 28, 80),
    // Extra buttons (addable via editor, hidden by default)
    INSERT("Ins", Color(0xFF1ABC9C), 28, 80),
    DELETE("Del", Color(0xFF1ABC9C), 28, 80),
    HOME("Home", Color(0xFF1ABC9C), 28, 80),
    END("End", Color(0xFF1ABC9C), 28, 80),
    PAGEUP("PgUp", Color(0xFF1ABC9C), 28, 80),
    PAGEDOWN("PgDn", Color(0xFF1ABC9C), 28, 80);

    fun landscapeLayout(p: PadLayout): ButtonLayout = when (this) {
        DPAD -> p.dosDpad
        ESC -> p.dosBtnEsc
        ENTER -> p.dosBtnEnter
        SPACE -> p.dosBtnSpace
        TAB -> p.dosBtnTab
        CTRL -> p.dosBtnCtrl
        ALT -> p.dosBtnAlt
        SHIFT -> p.dosBtnShift
        BACK -> p.dosBtnBack
        MOUSE_L -> p.dosBtnMouseL
        MOUSE_R -> p.dosBtnMouseR
        INSERT -> p.dosBtnInsert
        DELETE -> p.dosBtnDelete
        HOME -> p.dosBtnHome
        END -> p.dosBtnEnd
        PAGEUP -> p.dosBtnPageUp
        PAGEDOWN -> p.dosBtnPageDown
    }

    fun portraitLayout(p: PadLayout): ButtonLayout = when (this) {
        DPAD -> p.dosDpadP
        ESC -> p.dosBtnEscP
        ENTER -> p.dosBtnEnterP
        SPACE -> p.dosBtnSpaceP
        TAB -> p.dosBtnTabP
        CTRL -> p.dosBtnCtrlP
        ALT -> p.dosBtnAltP
        SHIFT -> p.dosBtnShiftP
        BACK -> p.dosBtnBackP
        MOUSE_L -> p.dosBtnMouseLP
        MOUSE_R -> p.dosBtnMouseRP
        INSERT -> p.dosBtnInsertP
        DELETE -> p.dosBtnDeleteP
        HOME -> p.dosBtnHomeP
        END -> p.dosBtnEndP
        PAGEUP -> p.dosBtnPageUpP
        PAGEDOWN -> p.dosBtnPageDownP
    }

    fun updateLandscape(p: PadLayout, l: ButtonLayout): PadLayout = when (this) {
        DPAD -> p.copy {dosDpad = l}
        ESC -> p.copy {dosBtnEsc = l}
        ENTER -> p.copy {dosBtnEnter = l}
        SPACE -> p.copy {dosBtnSpace = l}
        TAB -> p.copy {dosBtnTab = l}
        CTRL -> p.copy {dosBtnCtrl = l}
        ALT -> p.copy {dosBtnAlt = l}
        SHIFT -> p.copy {dosBtnShift = l}
        BACK -> p.copy {dosBtnBack = l}
        MOUSE_L -> p.copy {dosBtnMouseL = l}
        MOUSE_R -> p.copy {dosBtnMouseR = l}
        INSERT -> p.copy {dosBtnInsert = l}
        DELETE -> p.copy {dosBtnDelete = l}
        HOME -> p.copy {dosBtnHome = l}
        END -> p.copy {dosBtnEnd = l}
        PAGEUP -> p.copy {dosBtnPageUp = l}
        PAGEDOWN -> p.copy {dosBtnPageDown = l}
    }

    fun updatePortrait(p: PadLayout, l: ButtonLayout): PadLayout = when (this) {
        DPAD -> p.copy {dosDpadP = l}
        ESC -> p.copy {dosBtnEscP = l}
        ENTER -> p.copy {dosBtnEnterP = l}
        SPACE -> p.copy {dosBtnSpaceP = l}
        TAB -> p.copy {dosBtnTabP = l}
        CTRL -> p.copy {dosBtnCtrlP = l}
        ALT -> p.copy {dosBtnAltP = l}
        SHIFT -> p.copy {dosBtnShiftP = l}
        BACK -> p.copy {dosBtnBackP = l}
        MOUSE_L -> p.copy {dosBtnMouseLP = l}
        MOUSE_R -> p.copy {dosBtnMouseRP = l}
        INSERT -> p.copy {dosBtnInsertP = l}
        DELETE -> p.copy {dosBtnDeleteP = l}
        HOME -> p.copy {dosBtnHomeP = l}
        END -> p.copy {dosBtnEndP = l}
        PAGEUP -> p.copy {dosBtnPageUpP = l}
        PAGEDOWN -> p.copy {dosBtnPageDownP = l}
    }

    fun isVisible(p: PadLayout): Boolean = when (this) {
        DPAD -> p.dosShowDpad
        ESC -> p.dosShowEsc
        ENTER -> p.dosShowEnter
        SPACE -> p.dosShowSpace
        TAB -> p.dosShowTab
        CTRL -> p.dosShowCtrl
        ALT -> p.dosShowAlt
        SHIFT -> p.dosShowShift
        BACK -> p.dosShowBack
        MOUSE_L -> p.dosShowMouseL
        MOUSE_R -> p.dosShowMouseR
        INSERT -> p.dosShowInsert
        DELETE -> p.dosShowDelete
        HOME -> p.dosShowHome
        END -> p.dosShowEnd
        PAGEUP -> p.dosShowPageUp
        PAGEDOWN -> p.dosShowPageDown
    }

    fun toggleVisible(p: PadLayout): PadLayout = when (this) {
        DPAD -> p.copy {dosShowDpad = !p.dosShowDpad}
        ESC -> p.copy {dosShowEsc = !p.dosShowEsc}
        ENTER -> p.copy {dosShowEnter = !p.dosShowEnter}
        SPACE -> p.copy {dosShowSpace = !p.dosShowSpace}
        TAB -> p.copy {dosShowTab = !p.dosShowTab}
        CTRL -> p.copy {dosShowCtrl = !p.dosShowCtrl}
        ALT -> p.copy {dosShowAlt = !p.dosShowAlt}
        SHIFT -> p.copy {dosShowShift = !p.dosShowShift}
        BACK -> p.copy {dosShowBack = !p.dosShowBack}
        MOUSE_L -> p.copy {dosShowMouseL = !p.dosShowMouseL}
        MOUSE_R -> p.copy {dosShowMouseR = !p.dosShowMouseR}
        INSERT -> p.copy {dosShowInsert = !p.dosShowInsert}
        DELETE -> p.copy {dosShowDelete = !p.dosShowDelete}
        HOME -> p.copy {dosShowHome = !p.dosShowHome}
        END -> p.copy {dosShowEnd = !p.dosShowEnd}
        PAGEUP -> p.copy {dosShowPageUp = !p.dosShowPageUp}
        PAGEDOWN -> p.copy {dosShowPageDown = !p.dosShowPageDown}
    }

    /** Key code for injection (used by DosGamepadOverlay to render the button). */
    fun keyCode(): Int = when (this) {
        DPAD -> 0  // Dpad uses setPad1, not injectKeyDown
        ESC -> DosKeys.ESCAPE
        ENTER -> DosKeys.RETURN
        SPACE -> DosKeys.SPACE
        TAB -> DosKeys.TAB
        CTRL -> DosKeys.LCTRL
        ALT -> DosKeys.LALT
        SHIFT -> DosKeys.LSHIFT
        BACK -> DosKeys.BACKSPACE
        MOUSE_L -> 0  // Mouse buttons use injectMouseButton
        MOUSE_R -> 0
        INSERT -> DosKeys.INSERT
        DELETE -> DosKeys.DELETE
        HOME -> DosKeys.HOME
        END -> DosKeys.END
        PAGEUP -> DosKeys.PAGEUP
        PAGEDOWN -> DosKeys.PAGEDOWN
    }

    /** Whether this button type uses injectKeyDown/injectKeyUp. */
    fun isKeyButton(): Boolean = when (this) {
        DPAD, MOUSE_L, MOUSE_R -> false
        else -> true
    }
}

// Draggable DOS button preview for the editor.
@Composable
private fun DosEditableButton(
    label: String,
    color: Color,
    layout: ButtonLayout,
    surfaceSize: IntSize,
    isSelected: Boolean,
    onMove: (targetX: Float, targetY: Float) -> Unit,
    onSelect: () -> Unit,
    onLongPress: () -> Unit
) {
    val density = LocalDensity.current
    // In the editor, show buttons at 65% of their actual size so they're
    // compact, don't overlap much, and don't occlude each other.
    val editorScale = 0.65f
    val sizeDp = (layout.sizeDp * editorScale).dp
    val sizePx = with(density) { sizeDp.toPx() }

    val currentLayout by rememberUpdatedState(layout)
    val currentOnMove by rememberUpdatedState(onMove)
    val currentOnSelect by rememberUpdatedState(onSelect)
    val currentSurfaceSize by rememberUpdatedState(surfaceSize)

    // Compute pixel offset from fraction coords.
    val px = if (surfaceSize.width > 0) surfaceSize.width * layout.x - sizePx / 2 else 0f
    val py = if (surfaceSize.height > 0) surfaceSize.height * layout.y - sizePx / 2 else 0f

    var dragStartX by remember { mutableStateOf(0f) }
    var dragStartY by remember { mutableStateOf(0f) }
    var layoutStartX by remember { mutableStateOf(0f) }
    var layoutStartY by remember { mutableStateOf(0f) }

    Box(
        modifier = Modifier
            .offset { IntOffset(px.toInt(), py.toInt()) }
            .size(sizeDp)
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    down.consume()
                    currentOnSelect()
                    dragStartX = down.position.x
                    dragStartY = down.position.y
                    layoutStartX = currentLayout.x
                    layoutStartY = currentLayout.y

                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) { change.consume(); break }
                        if (change.positionChanged()) {
                            val dxPx = change.position.x - dragStartX
                            val dyPx = change.position.y - dragStartY
                            if (currentSurfaceSize.width > 0 && currentSurfaceSize.height > 0) {
                                val dxFrac = dxPx / currentSurfaceSize.width
                                val dyFrac = dyPx / currentSurfaceSize.height
                                currentOnMove(layoutStartX + dxFrac, layoutStartY + dyFrac)
                                change.consume()
                            }
                        }
                    }
                }
            },
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val r = size.width * 0.46f
            drawCircle(
                color.copy(alpha = if (isSelected) 0.5f else 0.25f),
                r, Offset(size.width / 2f, size.height / 2f)
            )
            drawCircle(
                color, r, Offset(size.width / 2f, size.height / 2f),
                style = Stroke(width = if (isSelected) 2.5.dp.toPx() else 1.5.dp.toPx())
            )
        }
        Text(
            label,
            color = color,
            fontSize = (sizeDp.value * 0.28f).sp,
            fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
        )
    }
}


// ---------------------------------------------------------------------------
// Pad layout editor — drag to move (fixed), tap to select + slider for size
// ---------------------------------------------------------------------------
@Composable
private fun PadLayoutEditor(
    padLayout: PadLayout,
    platform: GamePlatform = GamePlatform.NES,
    isPortrait: Boolean = false,
    onLayoutChange: (PadLayout) -> Unit,
    surfaceSize: IntSize,
    onClose: () -> Unit
) {
    // === DOS uses a dedicated overlay with its own button set ===
    // (Esc/Enter/Space/Tab/Ctrl/Alt/Shift/Mouse L/R + full QWERTY keyboard).
    // The standard NES/FC editor (D-pad + A/B + START/SELECT) does NOT apply
    // to DOS games. Route to a DOS-specific editor instead.
    if (platform == GamePlatform.DOS) {
        DosPadLayoutEditor(
            padLayout = padLayout,
            isPortrait = isPortrait,
            onLayoutChange = onLayoutChange,
            surfaceSize = surfaceSize,
            onClose = onClose
        )
        return
    }

    // === JAVA 手机键盘模式使用专用编辑器 ===
    // 手机键盘（数字盘 + L/F/R/C 功能键）的位置旧版是硬编码的，无法调整。
    // 现在布局存在 javaPhoneGrid / javaPhoneTop，切换到手机键盘后也支持
    // 布局调整 —— 路由到 J2ME 专用编辑器；手柄模式继续用下面的通用编辑器
    // （X/Y 已加入可编辑列表，见 showXY）。
    if (platform == GamePlatform.JAVA && padLayout.javaInputMode == "phone") {
        J2mePhoneLayoutEditor(
            padLayout = padLayout,
            isPortrait = isPortrait,
            onLayoutChange = onLayoutChange,
            surfaceSize = surfaceSize,
            onClose = onClose
        )
        return
    }

    var selectedBtn by remember { mutableStateOf<BtnType?>(null) }
    // Combo button picker dialog state — when true, shows a dialog that lets
    // the user pick 2-4 buttons to combine into a single on-screen combo key.
    var showComboPickerDialog by remember { mutableStateOf(false) }

    val showLR = platform == GamePlatform.GBA || platform == GamePlatform.SFC ||
                 platform == GamePlatform.ARCADE || platform == GamePlatform.MD ||
                 platform == GamePlatform.PCE || platform == GamePlatform.NDS ||
                 platform == GamePlatform.PSX || platform == GamePlatform.PS2 ||
                 platform == GamePlatform.DC
    // JAVA（J2ME）手柄模式的虚拟按键同样带 X/Y 两个键（X=右软键，Y=*键），
    // 布局编辑器必须允许拖动它们 —— 旧版没包含 JAVA，编辑器里缺少 X/Y。
    val showXY = platform == GamePlatform.SFC || platform == GamePlatform.JAVA ||
                 platform == GamePlatform.ARCADE || platform == GamePlatform.MD ||
                 platform == GamePlatform.PCE || platform == GamePlatform.NDS ||
                 platform == GamePlatform.PSX || platform == GamePlatform.PS2 ||
                 platform == GamePlatform.DC
    // L2/R2 editable in edit mode for Arcade (when enabled) and PCE (turbo toggle)
    val showL2R2 = (platform == GamePlatform.ARCADE && padLayout.arcadeShowL2R2) ||
                   platform == GamePlatform.PCE || platform == GamePlatform.PSX ||
                   platform == GamePlatform.PS2
    val isPs2 = platform == GamePlatform.PS2
    // PS2 专属键的可编辑开关（双摇杆常驻始终可编辑；L3/R3 可隐）
    val showL3Btn = isPs2 && !PadLayoutStore.isButtonHidden(padLayout, platform, "l3")
    val showR3Btn = isPs2 && !PadLayoutStore.isButtonHidden(padLayout, platform, "r3")

    // === Per-button visibility for ALL platforms ===
    // Each platform can independently hide/show individual buttons via the
    // "显隐按键" dialog. PCE uses legacy pceShow* booleans; all other
    // platforms use hiddenButtons* comma-separated strings.
    val showDpadBtn = !PadLayoutStore.isButtonHidden(padLayout, platform, "dpad")
    val showABtn = !PadLayoutStore.isButtonHidden(padLayout, platform, "a")
    val showBBtn = !PadLayoutStore.isButtonHidden(padLayout, platform, "b")
    val showStartBtn = !PadLayoutStore.isButtonHidden(padLayout, platform, "start")
    val showSelectBtn = !PadLayoutStore.isButtonHidden(padLayout, platform, "select")
    val showLBtn = !PadLayoutStore.isButtonHidden(padLayout, platform, "l")
    val showRBtn = !PadLayoutStore.isButtonHidden(padLayout, platform, "r")
    val showXBtn = !PadLayoutStore.isButtonHidden(padLayout, platform, "x")
    val showYBtn = !PadLayoutStore.isButtonHidden(padLayout, platform, "y")
    val showL2Btn = !PadLayoutStore.isButtonHidden(padLayout, platform, "l2")
    val showR2Btn = !PadLayoutStore.isButtonHidden(padLayout, platform, "r2")
    // 连发 A/B 仅在支持的平台提供（NES/GB/GBA/SFC/MD/NDS/街机）。
    // PCE 已有专用 TURBO I/II 切换键（L2/R2），PSX 不提供连发 —— 都不渲染。
    val supportsTurboAB = platform == GamePlatform.NES || platform == GamePlatform.GB ||
                          platform == GamePlatform.GBA || platform == GamePlatform.SFC ||
                          platform == GamePlatform.MD || platform == GamePlatform.NDS ||
                          platform == GamePlatform.ARCADE
    val showTurboABtn = supportsTurboAB && !PadLayoutStore.isButtonHidden(padLayout, platform, "ta")
    val showTurboBBtn = supportsTurboAB && !PadLayoutStore.isButtonHidden(padLayout, platform, "tb")

    // "显示/隐藏按键" dialog — available for ALL engines (not just PCE).
    // Lets the user toggle each button's visibility so the on-screen overlay
    // only shows the keys they need.
    var showKeyVisibilityDialog by remember { mutableStateOf(false) }

    // === 横竖屏布局选择 ===
    // 横屏编辑修改 dpad / btnA / ...，竖屏编辑修改 dpadP / btnAP / ...
    // 全局设置（透明度、核心选项等）在两个方向共享，编辑器不动这些。
    // PS2 平台读写专属全套字段（与 OnScreenController 一致）。
    val dpad = if (isPs2) (if (isPortrait) padLayout.ps2DpadP else padLayout.ps2Dpad)
               else if (isPortrait) padLayout.dpadP else padLayout.dpad
    val btnA = if (isPs2) (if (isPortrait) padLayout.ps2BtnAP else padLayout.ps2BtnA)
               else if (isPortrait) padLayout.btnAP else padLayout.btnA
    val btnB = if (isPs2) (if (isPortrait) padLayout.ps2BtnBP else padLayout.ps2BtnB)
               else if (isPortrait) padLayout.btnBP else padLayout.btnB
    // 连发 A/B 编辑器位置：6 键平台且未拖动过时用避让后的默认位置，
    // 保证编辑器里看到的与游戏中渲染的一致。
    val btnTurboA = shiftTurboDefault(
        if (isPortrait) padLayout.btnTurboAP else padLayout.btnTurboA,
        showXY, isPortrait, isA = true)
    val btnTurboB = shiftTurboDefault(
        if (isPortrait) padLayout.btnTurboBP else padLayout.btnTurboB,
        showXY, isPortrait, isA = false)
    val btnStart = if (isPs2) (if (isPortrait) padLayout.ps2BtnStartP else padLayout.ps2BtnStart)
                   else if (isPortrait) padLayout.btnStartP else padLayout.btnStart
    val btnSelect = if (isPs2) (if (isPortrait) padLayout.ps2BtnSelectP else padLayout.ps2BtnSelect)
                    else if (isPortrait) padLayout.btnSelectP else padLayout.btnSelect
    val btnL = if (isPs2) (if (isPortrait) padLayout.ps2BtnL1P else padLayout.ps2BtnL1)
               else if (isPortrait) padLayout.btnLP else padLayout.btnL
    val btnR = if (isPs2) (if (isPortrait) padLayout.ps2BtnR1P else padLayout.ps2BtnR1)
               else if (isPortrait) padLayout.btnRP else padLayout.btnR
    val btnX = if (isPs2) (if (isPortrait) padLayout.ps2BtnXP else padLayout.ps2BtnX)
               else if (isPortrait) padLayout.btnXP else padLayout.btnX
    val btnY = if (isPs2) (if (isPortrait) padLayout.ps2BtnYP else padLayout.ps2BtnY)
               else if (isPortrait) padLayout.btnYP else padLayout.btnY
    val btnL2 = if (isPs2) (if (isPortrait) padLayout.ps2BtnL2P else padLayout.ps2BtnL2)
                else if (isPortrait) padLayout.btnL2P else padLayout.btnL2
    val btnR2 = if (isPs2) (if (isPortrait) padLayout.ps2BtnR2P else padLayout.ps2BtnR2)
                else if (isPortrait) padLayout.btnR2P else padLayout.btnR2
    val ps2LStick = if (isPortrait) padLayout.ps2LStickP else padLayout.ps2LStick
    val ps2RStick = if (isPortrait) padLayout.ps2RStickP else padLayout.ps2RStick
    val ps2BtnL3 = if (isPortrait) padLayout.ps2BtnL3P else padLayout.ps2BtnL3
    val ps2BtnR3 = if (isPortrait) padLayout.ps2BtnR3P else padLayout.ps2BtnR3
    // 即时存档 / 即时读档按钮（所有核心通用，编辑器内可拖动 + 调尺寸）
    val btnQuickSave = if (isPortrait) padLayout.btnQuickSaveP else padLayout.btnQuickSave
    val btnQuickLoad = if (isPortrait) padLayout.btnQuickLoadP else padLayout.btnQuickLoad
    val showQuickSaveBtn = !PadLayoutStore.isButtonHidden(padLayout, platform, "qs")
    val showQuickLoadBtn = !PadLayoutStore.isButtonHidden(padLayout, platform, "ql")

    // 把当前选中按钮的新位置写回 PadLayout 的对应方向字段。
    // PS2 写回专属字段（ps2*），其他平台写通用字段。
    fun updateBtn(btnType: BtnType, newLayout: ButtonLayout) {
        val updated = when (btnType) {
            BtnType.DPAD -> if (isPs2) (if (isPortrait) padLayout.copy {this.ps2DpadP = newLayout} else padLayout.copy {this.ps2Dpad = newLayout})
                            else if (isPortrait) padLayout.copy {this.dpadP = newLayout} else padLayout.copy {this.dpad = newLayout}
            BtnType.A -> if (isPs2) (if (isPortrait) padLayout.copy {this.ps2BtnAP = newLayout} else padLayout.copy {this.ps2BtnA = newLayout})
                         else if (isPortrait) padLayout.copy {this.btnAP = newLayout} else padLayout.copy {this.btnA = newLayout}
            BtnType.B -> if (isPs2) (if (isPortrait) padLayout.copy {this.ps2BtnBP = newLayout} else padLayout.copy {this.ps2BtnB = newLayout})
                         else if (isPortrait) padLayout.copy {this.btnBP = newLayout} else padLayout.copy {this.btnB = newLayout}
            BtnType.TURBO_A -> if (isPortrait) padLayout.copy {this.btnTurboAP = newLayout} else padLayout.copy {this.btnTurboA = newLayout}
            BtnType.TURBO_B -> if (isPortrait) padLayout.copy {this.btnTurboBP = newLayout} else padLayout.copy {this.btnTurboB = newLayout}
            BtnType.START -> if (isPs2) (if (isPortrait) padLayout.copy {this.ps2BtnStartP = newLayout} else padLayout.copy {this.ps2BtnStart = newLayout})
                             else if (isPortrait) padLayout.copy {this.btnStartP = newLayout} else padLayout.copy {this.btnStart = newLayout}
            BtnType.SELECT -> if (isPs2) (if (isPortrait) padLayout.copy {this.ps2BtnSelectP = newLayout} else padLayout.copy {this.ps2BtnSelect = newLayout})
                              else if (isPortrait) padLayout.copy {this.btnSelectP = newLayout} else padLayout.copy {this.btnSelect = newLayout}
            BtnType.L -> if (isPs2) (if (isPortrait) padLayout.copy {this.ps2BtnL1P = newLayout} else padLayout.copy {this.ps2BtnL1 = newLayout})
                         else if (isPortrait) padLayout.copy {this.btnLP = newLayout} else padLayout.copy {this.btnL = newLayout}
            BtnType.R -> if (isPs2) (if (isPortrait) padLayout.copy {this.ps2BtnR1P = newLayout} else padLayout.copy {this.ps2BtnR1 = newLayout})
                         else if (isPortrait) padLayout.copy {this.btnRP = newLayout} else padLayout.copy {this.btnR = newLayout}
            BtnType.X -> if (isPs2) (if (isPortrait) padLayout.copy {this.ps2BtnXP = newLayout} else padLayout.copy {this.ps2BtnX = newLayout})
                         else if (isPortrait) padLayout.copy {this.btnXP = newLayout} else padLayout.copy {this.btnX = newLayout}
            BtnType.Y -> if (isPs2) (if (isPortrait) padLayout.copy {this.ps2BtnYP = newLayout} else padLayout.copy {this.ps2BtnY = newLayout})
                         else if (isPortrait) padLayout.copy {this.btnYP = newLayout} else padLayout.copy {this.btnY = newLayout}
            BtnType.L2 -> if (isPs2) (if (isPortrait) padLayout.copy {this.ps2BtnL2P = newLayout} else padLayout.copy {this.ps2BtnL2 = newLayout})
                          else if (isPortrait) padLayout.copy {this.btnL2P = newLayout} else padLayout.copy {this.btnL2 = newLayout}
            BtnType.R2 -> if (isPs2) (if (isPortrait) padLayout.copy {this.ps2BtnR2P = newLayout} else padLayout.copy {this.ps2BtnR2 = newLayout})
                          else if (isPortrait) padLayout.copy {this.btnR2P = newLayout} else padLayout.copy {this.btnR2 = newLayout}
            BtnType.L3 -> if (isPortrait) padLayout.copy {this.ps2BtnL3P = newLayout} else padLayout.copy {this.ps2BtnL3 = newLayout}
            BtnType.R3 -> if (isPortrait) padLayout.copy {this.ps2BtnR3P = newLayout} else padLayout.copy {this.ps2BtnR3 = newLayout}
            BtnType.LSTICK -> if (isPortrait) padLayout.copy {this.ps2LStickP = newLayout} else padLayout.copy {this.ps2LStick = newLayout}
            BtnType.RSTICK -> if (isPortrait) padLayout.copy {this.ps2RStickP = newLayout} else padLayout.copy {this.ps2RStick = newLayout}
            BtnType.QUICK_SAVE -> if (isPortrait) padLayout.copy {this.btnQuickSaveP = newLayout} else padLayout.copy {this.btnQuickSave = newLayout}
            BtnType.QUICK_LOAD -> if (isPortrait) padLayout.copy {this.btnQuickLoadP = newLayout} else padLayout.copy {this.btnQuickLoad = newLayout}
            BtnType.COMBO -> padLayout  // combo buttons handled via dedicated UI
            // 游戏区域不是可编辑的实体按键，没有位置/大小可写回 —— 直接返回原布局。
            BtnType.GAME_AREA -> padLayout
        }
        onLayoutChange(updated)
    }

    Box(modifier = Modifier.fillMaxSize().background(Color(0x88000000))) {
        // Draggable button previews — full screen, behind the control panel
        Box(modifier = Modifier.fillMaxSize()) {
            if (showDpadBtn) {
                EditableDpad(
                    layout = dpad,
                    surfaceSize = surfaceSize,
                    isSelected = selectedBtn == BtnType.DPAD,
                    isPortrait = isPortrait,
                    onMove = { targetX, targetY ->
                        val nx = targetX.coerceIn(0.03f, 0.5f)
                        val ny = targetY.coerceIn(0.25f, 0.95f)
                        updateBtn(BtnType.DPAD, dpad.copy(x = nx, y = ny))
                    },
                    onSelect = { selectedBtn = BtnType.DPAD }
                )
            }
            if (showABtn) {
                EditableRoundBtn(if (platform == GamePlatform.PCE) "I" else "A", Color(0xFFE74C3C), btnA, surfaceSize, selectedBtn == BtnType.A,
                    onMove = { targetX, targetY ->
                        val nx = targetX.coerceIn(0.4f, 0.95f)
                        val ny = targetY.coerceIn(0.3f, 0.97f)
                        updateBtn(BtnType.A, btnA.copy(x = nx, y = ny))
                    },
                    onSelect = { selectedBtn = BtnType.A }
                )
            }
            if (showBBtn) {
                EditableRoundBtn(if (platform == GamePlatform.PCE) "II" else "B", Color(0xFFE67E22), btnB, surfaceSize, selectedBtn == BtnType.B,
                    onMove = { targetX, targetY ->
                        val nx = targetX.coerceIn(0.4f, 0.95f)
                        val ny = targetY.coerceIn(0.3f, 0.97f)
                        updateBtn(BtnType.B, btnB.copy(x = nx, y = ny))
                    },
                    onSelect = { selectedBtn = BtnType.B }
                )
            }
            if (showTurboABtn) {
                EditableRoundBtn("TA", Color(0xFFE74C3C), btnTurboA, surfaceSize, selectedBtn == BtnType.TURBO_A,
                    onMove = { targetX, targetY ->
                        val nx = targetX.coerceIn(0.4f, 0.95f)
                        val ny = targetY.coerceIn(0.3f, 0.97f)
                        updateBtn(BtnType.TURBO_A, btnTurboA.copy(x = nx, y = ny))
                    },
                    onSelect = { selectedBtn = BtnType.TURBO_A }
                )
            }
            if (showTurboBBtn) {
                EditableRoundBtn("TB", Color(0xFFE67E22), btnTurboB, surfaceSize, selectedBtn == BtnType.TURBO_B,
                    onMove = { targetX, targetY ->
                        val nx = targetX.coerceIn(0.4f, 0.95f)
                        val ny = targetY.coerceIn(0.3f, 0.97f)
                        updateBtn(BtnType.TURBO_B, btnTurboB.copy(x = nx, y = ny))
                    },
                    onSelect = { selectedBtn = BtnType.TURBO_B }
                )
            }
            if (showStartBtn) {
                EditablePillBtn(if (platform == GamePlatform.PCE) "RUN" else "START", btnStart, surfaceSize, selectedBtn == BtnType.START,
                    onMove = { targetX, targetY ->
                        val nx = targetX.coerceIn(0.1f, 0.9f)
                        val ny = targetY.coerceIn(0.3f, 0.97f)
                        updateBtn(BtnType.START, btnStart.copy(x = nx, y = ny))
                    },
                    onSelect = { selectedBtn = BtnType.START }
                )
            }
            if (showSelectBtn) {
                EditablePillBtn("SELECT", btnSelect, surfaceSize, selectedBtn == BtnType.SELECT,
                    onMove = { targetX, targetY ->
                        val nx = targetX.coerceIn(0.1f, 0.9f)
                        val ny = targetY.coerceIn(0.3f, 0.97f)
                        updateBtn(BtnType.SELECT, btnSelect.copy(x = nx, y = ny))
                    },
                    onSelect = { selectedBtn = BtnType.SELECT }
                )
            }
            // L/R shoulder buttons (GBA/SNES/ARCADE/MD/PCE)
            if (showLR && showLBtn) {
                val lLabel = when (platform) {
                    GamePlatform.PCE -> "V"
                    GamePlatform.MD -> "Y"  // libretro L → SEGA Y
                    else -> "L"
                }
                EditablePillBtn(lLabel, btnL, surfaceSize, selectedBtn == BtnType.L,
                    onMove = { targetX, targetY ->
                        val nx = targetX.coerceIn(0.02f, 0.6f)
                        val ny = targetY.coerceIn(0.02f, 0.97f)
                        updateBtn(BtnType.L, btnL.copy(x = nx, y = ny))
                    },
                    onSelect = { selectedBtn = BtnType.L }
                )
            }
            if (showLR && showRBtn) {
                val rLabel = when (platform) {
                    GamePlatform.PCE -> "VI"
                    GamePlatform.MD -> "Z"  // libretro R → SEGA Z
                    else -> "R"
                }
                EditablePillBtn(rLabel, btnR, surfaceSize, selectedBtn == BtnType.R,
                    onMove = { targetX, targetY ->
                        val nx = targetX.coerceIn(0.4f, 0.98f)
                        val ny = targetY.coerceIn(0.02f, 0.97f)
                        updateBtn(BtnType.R, btnR.copy(x = nx, y = ny))
                    },
                    onSelect = { selectedBtn = BtnType.R }
                )
            }
            // X/Y face buttons (SNES/Arcade/MD/PCE)
            if (showXY && showXBtn) {
                val xLabel = when (platform) {
                    GamePlatform.PCE -> "IV"
                    GamePlatform.MD -> "C"  // libretro X → SEGA C
                    else -> "X"
                }
                EditableRoundBtn(xLabel, Color(0xFF3498DB), btnX, surfaceSize, selectedBtn == BtnType.X,
                    onMove = { targetX, targetY ->
                        val nx = targetX.coerceIn(0.4f, 0.95f)
                        val ny = targetY.coerceIn(0.3f, 0.97f)
                        updateBtn(BtnType.X, btnX.copy(x = nx, y = ny))
                    },
                    onSelect = { selectedBtn = BtnType.X }
                )
            }
            if (showXY && showYBtn) {
                val yLabel = when (platform) {
                    GamePlatform.PCE -> "III"
                    GamePlatform.MD -> "X"  // libretro Y → SEGA X
                    else -> "Y"
                }
                EditableRoundBtn(yLabel, Color(0xFF2ECC71), btnY, surfaceSize, selectedBtn == BtnType.Y,
                    onMove = { targetX, targetY ->
                        val nx = targetX.coerceIn(0.4f, 0.95f)
                        val ny = targetY.coerceIn(0.3f, 0.97f)
                        updateBtn(BtnType.Y, btnY.copy(x = nx, y = ny))
                    },
                    onSelect = { selectedBtn = BtnType.Y }
                )
            }
            // L2/R2 extra buttons (Arcade when enabled, PCE turbo toggle always)
            if (showL2R2 && showL2Btn) {
                val l2Label = if (platform == GamePlatform.PCE) "TURBO II" else "L2"
                EditableRoundBtn(l2Label, Color(0xFFFF9800), btnL2, surfaceSize, selectedBtn == BtnType.L2,
                    onMove = { targetX, targetY ->
                        val nx = targetX.coerceIn(0.02f, 0.5f)
                        val ny = targetY.coerceIn(0.02f, 0.97f)
                        updateBtn(BtnType.L2, btnL2.copy(x = nx, y = ny))
                    },
                    onSelect = { selectedBtn = BtnType.L2 }
                )
            }
            if (showL2R2 && showR2Btn) {
                val r2Label = if (platform == GamePlatform.PCE) "TURBO I" else "R2"
                EditableRoundBtn(r2Label, Color(0xFFFF9800), btnR2, surfaceSize, selectedBtn == BtnType.R2,
                    onMove = { targetX, targetY ->
                        val nx = targetX.coerceIn(0.5f, 0.98f)
                        val ny = targetY.coerceIn(0.02f, 0.97f)
                        updateBtn(BtnType.R2, btnR2.copy(x = nx, y = ny))
                    },
                    onSelect = { selectedBtn = BtnType.R2 }
                )
            }
            // 即时存档 / 即时读档按钮（所有平台可拖动，位置默认在顶部两侧）
            if (showQuickSaveBtn) {
                EditablePillBtn("存档", btnQuickSave, surfaceSize, selectedBtn == BtnType.QUICK_SAVE,
                    onMove = { targetX, targetY ->
                        val nx = targetX.coerceIn(0.1f, 0.5f)
                        val ny = targetY.coerceIn(0.02f, 0.95f)
                        updateBtn(BtnType.QUICK_SAVE, btnQuickSave.copy(x = nx, y = ny))
                    },
                    onSelect = { selectedBtn = BtnType.QUICK_SAVE }
                )
            }
            if (showQuickLoadBtn) {
                EditablePillBtn("读档", btnQuickLoad, surfaceSize, selectedBtn == BtnType.QUICK_LOAD,
                    onMove = { targetX, targetY ->
                        val nx = targetX.coerceIn(0.5f, 0.9f)
                        val ny = targetY.coerceIn(0.02f, 0.95f)
                        updateBtn(BtnType.QUICK_LOAD, btnQuickLoad.copy(x = nx, y = ny))
                    },
                    onSelect = { selectedBtn = BtnType.QUICK_LOAD }
                )
            }
            // PS2 专属可编辑控件：双摇杆（常驻）+ L3/R3
            if (isPs2) {
                EditableRoundBtn("左摇杆", Color(0xFFFFD66B), ps2LStick, surfaceSize, selectedBtn == BtnType.LSTICK,
                    onMove = { targetX, targetY ->
                        val nx = targetX.coerceIn(0.02f, 0.45f)
                        val ny = targetY.coerceIn(0.3f, 0.97f)
                        updateBtn(BtnType.LSTICK, ps2LStick.copy(x = nx, y = ny))
                    },
                    onSelect = { selectedBtn = BtnType.LSTICK }
                )
                EditableRoundBtn("右摇杆", Color(0xFFFFD66B), ps2RStick, surfaceSize, selectedBtn == BtnType.RSTICK,
                    onMove = { targetX, targetY ->
                        val nx = targetX.coerceIn(0.55f, 0.98f)
                        val ny = targetY.coerceIn(0.3f, 0.97f)
                        updateBtn(BtnType.RSTICK, ps2RStick.copy(x = nx, y = ny))
                    },
                    onSelect = { selectedBtn = BtnType.RSTICK }
                )
                if (showL3Btn) {
                    EditableRoundBtn("L3", Color(0xFF95A5A6), ps2BtnL3, surfaceSize, selectedBtn == BtnType.L3,
                        onMove = { targetX, targetY ->
                            val nx = targetX.coerceIn(0.02f, 0.5f)
                            val ny = targetY.coerceIn(0.3f, 0.97f)
                            updateBtn(BtnType.L3, ps2BtnL3.copy(x = nx, y = ny))
                        },
                        onSelect = { selectedBtn = BtnType.L3 }
                    )
                }
                if (showR3Btn) {
                    EditableRoundBtn("R3", Color(0xFF95A5A6), ps2BtnR3, surfaceSize, selectedBtn == BtnType.R3,
                        onMove = { targetX, targetY ->
                            val nx = targetX.coerceIn(0.5f, 0.98f)
                            val ny = targetY.coerceIn(0.3f, 0.97f)
                            updateBtn(BtnType.R3, ps2BtnR3.copy(x = nx, y = ny))
                        },
                        onSelect = { selectedBtn = BtnType.R3 }
                    )
                }
            }
            // Combo buttons (draggable, per-platform)
            val combos = remember(padLayout, platform) { parseComboButtons(padLayout, platform) }
            combos.forEach { combo ->
                EditableRoundBtn(
                    combo.label,
                    Color(combo.color),
                    ButtonLayout(combo.x, combo.y, combo.sizeDp),
                    surfaceSize,
                    selectedBtn == BtnType.COMBO,
                    onMove = { targetX, targetY ->
                        val nx = targetX.coerceIn(0.05f, 0.95f)
                        val ny = targetY.coerceIn(0.3f, 0.97f)
                        val updated = combos.map { if (it.id == combo.id) it.copy(x = nx, y = ny) else it }
                        val json = serializeComboButtons(updated)
                        val newLayout = when (platform) {
                            GamePlatform.NES, GamePlatform.GB -> padLayout.copy {comboButtons = json}
                            GamePlatform.SFC -> padLayout.copy {comboButtonsSfc = json}
                            GamePlatform.GBA -> padLayout.copy {comboButtonsGba = json}
                            GamePlatform.ARCADE -> padLayout.copy {comboButtonsArcade = json}
                            GamePlatform.MD -> padLayout.copy {comboButtonsMd = json}
                            GamePlatform.PCE -> padLayout.copy {comboButtonsPce = json}
                            else -> padLayout
                        }
                        onLayoutChange(newLayout)
                    },
                    onSelect = { selectedBtn = BtnType.COMBO }
                )
            }
        }

        // === Centered control panel ===
        // IMPORTANT: keep this panel NARROW and centered. A wide panel
        // (e.g. fillMaxWidth(0.85f)) covers the on-screen virtual buttons
        // the user is trying to drag — see user report "虚拟按键布局界面咋又宽了
        // ... 应该在中间位置不要那么长啊, 挡住按键". We cap the width to a
        // content-fit size and align it to TopCenter so it doesn't overlap
        // the action buttons (which live in the lower half of the screen).
        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 8.dp)
                .widthIn(min = 240.dp, max = 320.dp)
                .background(Color(0xDD1E2A3A), RoundedCornerShape(12.dp))
                .padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            // --- Toolbar row ---
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (isPortrait) "竖屏布局" else "横屏布局",
                    color = Color.White, fontSize = 13.sp,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
                )
                Spacer(Modifier.weight(1f))
                // "显隐按键" button — available for ALL engines. Opens a dialog
                // that lets the user toggle each on-screen button's visibility.
                androidx.compose.material3.TextButton(
                    onClick = { showKeyVisibilityDialog = true }
                ) {
                    Text("显隐按键", color = Color(0xFFFFD66B), fontSize = 11.sp)
                }
                // Direction control toggle: D-Pad vs Analog Stick. Available
                // for all engines. Lets the user switch between a cross-shaped
                // digital D-pad and a circular analog stick.
                androidx.compose.material3.TextButton(
                    onClick = {
                        val current = PadLayoutStore.getInputMode(padLayout, platform)
                        val next = if (current == "analog") "dpad" else "analog"
                        onLayoutChange(PadLayoutStore.setInputMode(padLayout, platform, next))
                    }
                ) {
                    val mode = PadLayoutStore.getInputMode(padLayout, platform)
                    Text(
                        if (mode == "analog") "摇杆" else "十字键",
                        color = Color(0xFFFFD66B), fontSize = 11.sp
                    )
                }
                IconButton(onClick = {
                    val defaults = PadLayout()
                    if (isPortrait) {
                        onLayoutChange(padLayout.copy {
                            dpadP = defaults.dpadP
 btnAP = defaults.btnAP
 btnBP = defaults.btnBP
                            btnTurboAP = defaults.btnTurboAP
 btnTurboBP = defaults.btnTurboBP
                            btnStartP = defaults.btnStartP
 btnSelectP = defaults.btnSelectP
                            btnLP = defaults.btnLP
 btnRP = defaults.btnRP
                            btnXP = defaults.btnXP
 btnYP = defaults.btnYP
                            btnQuickSaveP = defaults.btnQuickSaveP
                            btnQuickLoadP = defaults.btnQuickLoadP
                            pceShowDpad = defaults.pceShowDpad
 pceShowA = defaults.pceShowA
                            pceShowB = defaults.pceShowB
 pceShowStart = defaults.pceShowStart
                            pceShowSelect = defaults.pceShowSelect
 pceShowL = defaults.pceShowL
                            pceShowR = defaults.pceShowR
 pceShowX = defaults.pceShowX
                            pceShowY = defaults.pceShowY
 pceShowL2 = defaults.pceShowL2
                            pceShowR2 = defaults.pceShowR2
                        })
                    } else {
                        onLayoutChange(padLayout.copy {
                            this.dpad = defaults.dpad
                            this.btnA = defaults.btnA
                            this.btnB = defaults.btnB
                            this.btnTurboA = defaults.btnTurboA
                            this.btnTurboB = defaults.btnTurboB
                            this.btnStart = defaults.btnStart
                            this.btnSelect = defaults.btnSelect
                            this.btnL = defaults.btnL
                            this.btnR = defaults.btnR
                            this.btnX = defaults.btnX
                            this.btnY = defaults.btnY
                            this.btnQuickSave = defaults.btnQuickSave
                            this.btnQuickLoad = defaults.btnQuickLoad
                            pceShowDpad = defaults.pceShowDpad
 pceShowA = defaults.pceShowA
                            pceShowB = defaults.pceShowB
 pceShowStart = defaults.pceShowStart
                            pceShowSelect = defaults.pceShowSelect
 pceShowL = defaults.pceShowL
                            pceShowR = defaults.pceShowR
 pceShowX = defaults.pceShowX
                            pceShowY = defaults.pceShowY
 pceShowL2 = defaults.pceShowL2
                            pceShowR2 = defaults.pceShowR2
                        })
                    }
                }) {
                    Icon(Icons.Rounded.Refresh, "重置", tint = Color(0xFFFFD66B))
                }
                IconButton(onClick = onClose) {
                    Text("完成", color = Color.White, fontSize = 13.sp,
                        fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                }
            }

            // --- Opacity slider (ALL cores) ---
            // 透明度调节：所有核心的布局编辑器统一提供。J2ME 使用专属
            // javaOpacity（默认 0.8，与其他核心互不影响）；其余平台使用
            // 全局 opacity（默认 0.7）。拖动即时生效，随布局一起持久化。
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("透明度", color = Color.White, fontSize = 11.sp,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)
                Spacer(Modifier.weight(1f))
                Text(
                    "${(((if (platform == GamePlatform.JAVA) padLayout.javaOpacity else padLayout.opacity).coerceIn(0.3f, 1f)) * 100).toInt()}%",
                    color = Color(0xFFFFD66B), fontSize = 11.sp
                )
            }
            Slider(
                value = (if (platform == GamePlatform.JAVA) padLayout.javaOpacity else padLayout.opacity).coerceIn(0.3f, 1f),
                onValueChange = { newVal ->
                    val v = newVal.coerceIn(0.3f, 1f)
                    onLayoutChange(
                        if (platform == GamePlatform.JAVA) padLayout.copy { javaOpacity = v }
                        else padLayout.copy { opacity = v }
                    )
                },
                valueRange = 0.3f..1f,
                colors = SliderDefaults.colors(
                    thumbColor = Color(0xFFFFD66B),
                    activeTrackColor = Color(0xFFFFD66B),
                    inactiveTrackColor = Color(0xFF4A5568)
                )
            )

            // --- Size slider when a button is selected ---
            val sel = selectedBtn
            if (sel != null) {
                val currentSize: Int
                val minSize: Int
                val maxSize: Int
                val label: String
                when (sel) {
                    BtnType.DPAD -> { currentSize = dpad.sizeDp; minSize = 80; maxSize = 220; label = "十字键大小" }
                    BtnType.A -> { currentSize = btnA.sizeDp; minSize = 40; maxSize = 120; label = "A键大小" }
                    BtnType.B -> { currentSize = btnB.sizeDp; minSize = 40; maxSize = 120; label = "B键大小" }
                    BtnType.TURBO_A -> { currentSize = btnTurboA.sizeDp; minSize = 30; maxSize = 90; label = "连射A大小" }
                    BtnType.TURBO_B -> { currentSize = btnTurboB.sizeDp; minSize = 30; maxSize = 90; label = "连射B大小" }
                    BtnType.START -> { currentSize = btnStart.sizeDp; minSize = 30; maxSize = 100; label = "START大小" }
                    BtnType.SELECT -> { currentSize = btnSelect.sizeDp; minSize = 30; maxSize = 100; label = "SELECT大小" }
                    BtnType.L -> { currentSize = btnL.sizeDp; minSize = 36; maxSize = 90; label = "L键大小" }
                    BtnType.R -> { currentSize = btnR.sizeDp; minSize = 36; maxSize = 90; label = "R键大小" }
                    BtnType.X -> { currentSize = btnX.sizeDp; minSize = 40; maxSize = 120; label = "X键大小" }
                    BtnType.Y -> { currentSize = btnY.sizeDp; minSize = 40; maxSize = 120; label = "Y键大小" }
                    BtnType.L2 -> { currentSize = btnL2.sizeDp; minSize = 36; maxSize = 90; label = "L2键大小" }
                    BtnType.R2 -> { currentSize = btnR2.sizeDp; minSize = 36; maxSize = 90; label = "R2键大小" }
                    BtnType.LSTICK -> { currentSize = ps2LStick.sizeDp; minSize = 80; maxSize = 220; label = "左摇杆大小" }
                    BtnType.RSTICK -> { currentSize = ps2RStick.sizeDp; minSize = 80; maxSize = 220; label = "右摇杆大小" }
                    BtnType.L3 -> { currentSize = ps2BtnL3.sizeDp; minSize = 24; maxSize = 80; label = "L3大小" }
                    BtnType.R3 -> { currentSize = ps2BtnR3.sizeDp; minSize = 24; maxSize = 80; label = "R3大小" }
                    BtnType.QUICK_SAVE -> { currentSize = btnQuickSave.sizeDp; minSize = 24; maxSize = 80; label = "即时存档大小" }
                    BtnType.QUICK_LOAD -> { currentSize = btnQuickLoad.sizeDp; minSize = 24; maxSize = 80; label = "即时读档大小" }
                    BtnType.COMBO -> { currentSize = 56; minSize = 36; maxSize = 100; label = "组合键大小" }
                    // GAME_AREA 不出现在布局编辑器（selectedBtn 只会指向实体按键），
                    // 此分支仅为穷举完整性而设，正常流程不会执行。
                    BtnType.GAME_AREA -> { currentSize = 0; minSize = 0; maxSize = 1; label = "游戏区域" }
                }

                Spacer(Modifier.size(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(label, color = Color.White, fontSize = 11.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)
                    Spacer(Modifier.weight(1f))
                    Text("${currentSize}dp", color = Color(0xFFFFD66B), fontSize = 11.sp)
                }
                Slider(
                    value = currentSize.toFloat(),
                    onValueChange = { newVal ->
                        val intVal = newVal.toInt()
                        val source = when (sel) {
                            BtnType.DPAD -> dpad
                            BtnType.A -> btnA
                            BtnType.B -> btnB
                            BtnType.TURBO_A -> btnTurboA
                            BtnType.TURBO_B -> btnTurboB
                            BtnType.START -> btnStart
                            BtnType.SELECT -> btnSelect
                            BtnType.L -> btnL
                            BtnType.R -> btnR
                            BtnType.X -> btnX
                            BtnType.Y -> btnY
                            BtnType.L2 -> btnL2
                            BtnType.R2 -> btnR2
                            BtnType.LSTICK -> ps2LStick
                            BtnType.RSTICK -> ps2RStick
                            BtnType.L3 -> ps2BtnL3
                            BtnType.R3 -> ps2BtnR3
                            BtnType.QUICK_SAVE -> btnQuickSave
                            BtnType.QUICK_LOAD -> btnQuickLoad
                            BtnType.COMBO -> ButtonLayout(0.5f, 0.85f, 56)  // combo size handled separately
                            BtnType.GAME_AREA -> ButtonLayout(0.5f, 0.85f, 0)  // 不可达：编辑器不会选中游戏区域
                        }
                        updateBtn(sel, source.copy(sizeDp = intVal))
                    },
                    valueRange = minSize.toFloat()..maxSize.toFloat(),
                    colors = SliderDefaults.colors(
                        thumbColor = Color(0xFFFFD66B),
                        activeTrackColor = Color(0xFFFFD66B),
                        inactiveTrackColor = Color(0xFF4A5568)
                    )
                )
            } else {
                Text("拖动移动 · 点击选中调大小", color = Color(0xFF8899AA), fontSize = 9.sp)
            }

            // === Combo button management ===
            // Per-platform: each platform tab has its own combo button list.
            // Tapping "+ 添加组合键" opens a dialog where the user picks
            // 2-4 buttons (A/B/X/Y/L/R/L2/R2/Start/Select) to combine into
            // a single on-screen button. Previously this was hardcoded to AB
            // only — now any 2-4 button combo is supported per the user's
            // request ("组合键不应该只是添加ab，而且可以任意自定义2-4个按键组合").
            Spacer(Modifier.size(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("组合键", color = Color(0xFFFFD66B), fontSize = 11.sp,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)
                Spacer(Modifier.weight(1f))
                androidx.compose.material3.TextButton(
                    onClick = { showComboPickerDialog = true }
                ) {
                    Text("+ 添加组合键", color = Color(0xFFFFD66B), fontSize = 11.sp)
                }
            }
            // List existing combos with delete option
            val combos2 = remember(padLayout, platform) { parseComboButtons(padLayout, platform) }
            combos2.forEach { combo ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("• ${combo.label} (bits=0x${combo.bits.toString(16)})",
                        color = Color.White, fontSize = 10.sp, modifier = Modifier.weight(1f))
                    androidx.compose.material3.TextButton(
                        onClick = {
                            val updated = combos2.filter { it.id != combo.id }
                            val json = serializeComboButtons(updated)
                            val newLayout = when (platform) {
                                GamePlatform.NES, GamePlatform.GB -> padLayout.copy {comboButtons = json}
                                GamePlatform.SFC -> padLayout.copy {comboButtonsSfc = json}
                                GamePlatform.GBA -> padLayout.copy {comboButtonsGba = json}
                                GamePlatform.ARCADE -> padLayout.copy {comboButtonsArcade = json}
                                GamePlatform.MD -> padLayout.copy {comboButtonsMd = json}
                                GamePlatform.PCE -> padLayout.copy {comboButtonsPce = json}
                                else -> padLayout
                            }
                            onLayoutChange(newLayout)
                        }
                    ) {
                        Text("删除", color = Color(0xFFE74C3C), fontSize = 10.sp)
                    }
                }
            }
        }
    }

    // === Combo Button Picker Dialog ===
    // Lets the user pick 2-4 buttons to combine into a single on-screen combo key.
    // Available buttons depend on the platform (NES only has A/B/Start/Select,
    // SNES/Arcade/MD also have X/Y/L/R, Arcade may have L2/R2).
    if (showComboPickerDialog) {
        ComboButtonPickerDialog(
            platform = platform,
            onConfirm = { selectedBits, label ->
                showComboPickerDialog = false
                if (selectedBits != 0) {
                    val current = parseComboButtons(padLayout, platform)
                    val newCombo = ComboButtonEntry(
                        id = "combo_${System.currentTimeMillis()}",
                        label = label,
                        bits = selectedBits,
                        x = 0.5f,
                        y = 0.85f,
                        sizeDp = 56,
                        color = 0xFF9C27B0.toInt()
                    )
                    val updated = current + newCombo
                    val json = serializeComboButtons(updated)
                    val newLayout = when (platform) {
                        GamePlatform.NES, GamePlatform.GB -> padLayout.copy {comboButtons = json}
                        GamePlatform.SFC -> padLayout.copy {comboButtonsSfc = json}
                        GamePlatform.GBA -> padLayout.copy {comboButtonsGba = json}
                        GamePlatform.ARCADE -> padLayout.copy {comboButtonsArcade = json}
                        GamePlatform.MD -> padLayout.copy {comboButtonsMd = json}
                        GamePlatform.PCE -> padLayout.copy {comboButtonsPce = json}
                        else -> padLayout
                    }
                    onLayoutChange(newLayout)
                }
            },
            onDismiss = { showComboPickerDialog = false }
        )
    }

    // === Key Visibility Dialog (all engines) ===
    // Lets the user show/hide each on-screen button independently.
    // Works for all platforms: PCE uses legacy pceShow* booleans,
    // all others use hiddenButtons* comma-separated strings.
    if (showKeyVisibilityDialog) {
        KeyVisibilityDialog(
            padLayout = padLayout,
            platform = platform,
            onToggle = { key, show ->
                val newLayout = PadLayoutStore.setButtonHidden(padLayout, platform, key, !show)
                onLayoutChange(newLayout)
                // If the hidden button was selected in the editor, deselect it
                // (its draggable preview is no longer rendered).
                if (!show) {
                    val hiddenBtn = when (key) {
                        "dpad" -> BtnType.DPAD
                        "a" -> BtnType.A
                        "b" -> BtnType.B
                        "ta" -> BtnType.TURBO_A
                        "tb" -> BtnType.TURBO_B
                        "start" -> BtnType.START
                        "select" -> BtnType.SELECT
                        "l" -> BtnType.L
                        "r" -> BtnType.R
                        "x" -> BtnType.X
                        "y" -> BtnType.Y
                        "l2" -> BtnType.L2
                        "r2" -> BtnType.R2
                        "l3" -> BtnType.L3
                        "r3" -> BtnType.R3
                        else -> null
                    }
                    if (hiddenBtn != null && selectedBtn == hiddenBtn) {
                        selectedBtn = null
                    }
                }
            },
            onDismiss = { showKeyVisibilityDialog = false }
        )
    }
}

// ---------------------------------------------------------------------------
// Key Visibility Dialog — lets the user show/hide each on-screen button
// for ANY platform. Uses PadLayoutStore helpers to read/write visibility
// state. PCE uses legacy pceShow* booleans; all others use hiddenButtons*.
// ---------------------------------------------------------------------------
@Composable
private fun KeyVisibilityDialog(
    padLayout: PadLayout,
    platform: GamePlatform,
    onToggle: (key: String, show: Boolean) -> Unit,
    onDismiss: () -> Unit
) {
    data class KeyItem(val key: String, val label: String, val isVisible: Boolean)
    val items = PadLayoutStore.getAvailableButtons(platform).map { (key, label) ->
        KeyItem(key, label, !PadLayoutStore.isButtonHidden(padLayout, platform, key))
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xAA000000))
            .pointerInput(Unit) { detectTapGestures { onDismiss() } }
    ) {
        Column(
            modifier = Modifier
                .align(Alignment.Center)
                .fillMaxWidth(0.9f)
                .heightIn(max = 460.dp)
                .background(Color(0xEE1E2A3A), RoundedCornerShape(16.dp))
                .padding(16.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("显示 / 隐藏按键", color = Color.White, fontSize = 15.sp,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                Spacer(Modifier.weight(1f))
                Text("点击外部关闭", color = Color(0xFF8899AA), fontSize = 10.sp)
            }
            Spacer(Modifier.size(8.dp))
            items.forEach { item ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { onToggle(item.key, !item.isVisible) }
                        .padding(vertical = 8.dp, horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(item.label, color = Color.White, fontSize = 13.sp,
                        modifier = Modifier.weight(1f),
                        fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)
                    Box(
                        modifier = Modifier
                            .size(width = 42.dp, height = 24.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(if (item.isVisible) Color(0xFF2ECC71) else Color(0xFF4A5568))
                            .padding(2.dp),
                        contentAlignment = if (item.isVisible) Alignment.CenterEnd else Alignment.CenterStart
                    ) {
                        Box(
                            Modifier
                                .size(20.dp)
                                .background(Color.White, androidx.compose.foundation.shape.CircleShape)
                        )
                    }
                }
                Spacer(Modifier.size(2.dp))
            }
            Spacer(Modifier.size(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("隐藏的按键在游戏中不显示", color = Color(0xFF8899AA), fontSize = 10.sp)
                Spacer(Modifier.weight(1f))
                androidx.compose.material3.TextButton(onClick = onDismiss) {
                    Text("完成", color = Color(0xFFFFD66B), fontSize = 13.sp,
                        fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Combo Button Picker Dialog — lets the user choose 2-4 buttons to combine
// ---------------------------------------------------------------------------
// Lists the platform's available buttons (A/B/X/Y/L/R/L2/R2/Start/Select)
// as toggleable chips. The user must select 2-4 buttons. The dialog shows
// the resulting label (auto-generated from selected button names) and the
// bitmask value in real time. On confirm, the combo is added to the layout.
@Composable
private fun ComboButtonPickerDialog(
    platform: GamePlatform,
    onConfirm: (bits: Int, label: String) -> Unit,
    onDismiss: () -> Unit
) {
    // L/R bit values differ between GBA (bit8/9) and SNES/ARCADE/MD (bit10/11)
    val lBit = if (platform == GamePlatform.GBA) BTN_L_GBA else BTN_L_SNES
    val rBit = if (platform == GamePlatform.GBA) BTN_R_GBA else BTN_R_SNES

    // Available buttons for this platform
    data class ButtonOption(val name: String, val bit: Int)
    val availableButtons = remember(platform) {
        val list = mutableListOf(
            ButtonOption("A", BTN_A),
            ButtonOption("B", BTN_B),
            ButtonOption("Start", BTN_START),
            ButtonOption("Select", BTN_SELECT)
        )
        // X/Y available on SNES/Arcade/MD/PCE/NDS/PSX
        if (platform == GamePlatform.SFC || platform == GamePlatform.ARCADE || platform == GamePlatform.MD ||
            platform == GamePlatform.PCE || platform == GamePlatform.NDS || platform == GamePlatform.PSX) {
            list.add(ButtonOption("X", BTN_X))
            list.add(ButtonOption("Y", BTN_Y))
        }
        // L/R available on GBA/SNES/Arcade/MD/PCE/NDS/PSX
        if (platform == GamePlatform.GBA || platform == GamePlatform.SFC ||
            platform == GamePlatform.ARCADE || platform == GamePlatform.MD ||
            platform == GamePlatform.PCE || platform == GamePlatform.NDS || platform == GamePlatform.PSX) {
            list.add(ButtonOption("L", lBit))
            list.add(ButtonOption("R", rBit))
        }
        // L2/R2 on Arcade (6-button fight layout) and PCE (turbo toggle)
        if (platform == GamePlatform.ARCADE || platform == GamePlatform.PCE) {
            list.add(ButtonOption("L2", BTN_L2))
            list.add(ButtonOption("R2", BTN_R2))
        }
        list.toList()
    }

    // Track selected buttons (by name, to support toggling)
    val selected = remember { mutableStateListOf<String>() }

    fun toggle(name: String) {
        if (name in selected) {
            selected.remove(name)
        } else if (selected.size < 4) {
            selected.add(name)
        }
    }

    // Compute bits and label from selected buttons
    val bits = selected.sumOf { name ->
        availableButtons.firstOrNull { it.name == name }?.bit ?: 0
    }
    val label = selected.joinToString("")

    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text("添加组合键", color = Color.White,
                fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    "选择 2-4 个按键组合为一个虚拟按键：",
                    color = Color(0xFFB0BEC5), fontSize = 12.sp
                )
                Spacer(Modifier.size(8.dp))
                // Button chips grid — use a simple Column+Row layout to avoid
                // FlowRow API version issues across Compose versions.
                val rows = availableButtons.chunked(4)
                rows.forEach { rowButtons ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        rowButtons.forEach { opt ->
                            val isSelected = opt.name in selected
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(
                                        if (isSelected) Color(0xFFFFD66B)
                                        else Color(0xFF2C2C38)
                                    )
                                    .clickable { toggle(opt.name) }
                                    .padding(horizontal = 8.dp, vertical = 8.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    opt.name,
                                    color = if (isSelected) Color.Black else Color.White,
                                    fontSize = 13.sp,
                                    fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold
                                )
                            }
                        }
                        // Fill empty slots so layout stays aligned
                        repeat(4 - rowButtons.size) {
                            Spacer(Modifier.weight(1f))
                        }
                    }
                }
                Spacer(Modifier.size(12.dp))
                // Live preview
                val previewLabel = if (selected.isEmpty()) "（请选择按键）" else label
                val countColor = when {
                    selected.size < 2 -> Color(0xFFE74C3C)  // red - too few
                    selected.size > 4 -> Color(0xFFE74C3C)  // red - too many (shouldn't happen)
                    else -> Color(0xFF88DD88)               // green - valid
                }
                Text(
                    "已选: $previewLabel  (${selected.size}/4)",
                    color = countColor, fontSize = 12.sp,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold
                )
                if (bits != 0) {
                    Text(
                        "按键位: 0x${bits.toString(16)}",
                        color = Color(0xFF8899AA), fontSize = 10.sp
                    )
                }
                Spacer(Modifier.size(6.dp))
                Text(
                    "提示: 组合键按下时会同时触发所选的全部按键。常见用途:\n" +
                    "• A+B → 跑/跳/滑铲 (FC/MD动作游戏)\n" +
                    "• A+B+X+Y → 必杀技 (格斗游戏)\n" +
                    "• L+R → 特殊操作 (SNES/GBA)",
                    color = Color(0xFF8899AA), fontSize = 9.sp, lineHeight = 12.sp
                )
            }
        },
        confirmButton = {
            androidx.compose.material3.TextButton(
                enabled = selected.size in 2..4,
                onClick = { onConfirm(bits, label) }
            ) {
                Text(
                    "添加",
                    color = if (selected.size in 2..4) Color(0xFFFFD66B) else Color(0xFF555555),
                    fontSize = 13.sp
                )
            }
        },
        dismissButton = {
            androidx.compose.material3.TextButton(onClick = onDismiss) {
                Text("取消", color = Color(0xFF8899AA), fontSize = 13.sp)
            }
        },
        containerColor = Color(0xFF1A1A22)
    )
}

// ---------------------------------------------------------------------------
// Editable button — drag to move (uses awaitEachGesture with proper delta)
// ---------------------------------------------------------------------------
@Composable
private fun EditableRoundBtn(
    label: String,
    color: Color,
    layout: ButtonLayout,
    surfaceSize: IntSize,
    isSelected: Boolean,
    onMove: (targetX: Float, targetY: Float) -> Unit,
    onSelect: () -> Unit
) {
    val density = LocalDensity.current
    val sizeDp = layout.sizeDp.dp
    val (px, py) = buttonOffset(layout, surfaceSize, density)
    // Track drag start position to compute accurate absolute target
    var dragStartX by remember { mutableStateOf(0f) }
    var dragStartY by remember { mutableStateOf(0f) }
    var layoutStartX by remember { mutableStateOf(0f) }
    var layoutStartY by remember { mutableStateOf(0f) }

    // CRITICAL: use rememberUpdatedState so the gesture handler (which has
    // pointerInput(Unit) and doesn't restart) always reads the LATEST values.
    // Without this, moving button A then dragging button B would use stale
    // padLayout (captured at initial composition), resetting A's position.
    val currentLayout by rememberUpdatedState(layout)
    val currentOnMove by rememberUpdatedState(onMove)
    val currentOnSelect by rememberUpdatedState(onSelect)
    val currentSurfaceSize by rememberUpdatedState(surfaceSize)

    Box(
        modifier = Modifier
            .offset { IntOffset(px.toInt(), py.toInt()) }
            .size(sizeDp)
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    down.consume()  // prevent parent from double-processing
                    currentOnSelect()
                    dragStartX = down.position.x
                    dragStartY = down.position.y
                    layoutStartX = currentLayout.x
                    layoutStartY = currentLayout.y

                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) { change.consume(); break }
                        if (change.positionChanged()) {
                            val dxPx = change.position.x - dragStartX
                            val dyPx = change.position.y - dragStartY
                            val dxFrac = dxPx / currentSurfaceSize.width
                            val dyFrac = dyPx / currentSurfaceSize.height
                            currentOnMove(layoutStartX + dxFrac, layoutStartY + dyFrac)
                            change.consume()  // reduce recomposition overhead
                        }
                    }
                }
            },
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val r = size.width * 0.46f
            drawCircle(color.copy(alpha = if (isSelected) 0.5f else 0.35f), r, Offset(size.width / 2f, size.height / 2f))
            drawCircle(color, r, Offset(size.width / 2f, size.height / 2f), style = Stroke(width = if (isSelected) 3.dp.toPx() else 2.dp.toPx()))
        }
        Text(label, color = color, fontSize = (sizeDp.value * 0.2f).sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
    }
}

@Composable
private fun EditableDpad(
    layout: ButtonLayout,
    surfaceSize: IntSize,
    isSelected: Boolean,
    isPortrait: Boolean = false,
    onMove: (targetX: Float, targetY: Float) -> Unit,
    onSelect: () -> Unit
) {
    // 布局由调用方传入：PS2 用 ps2Dpad/ps2DpadP，其它平台用通用 dpad/dpadP。
    // 之前这里硬编码读 padLayout.dpad（通用字段），而 PS2 的写入目标是
    // ps2Dpad —— 结果十字键在编辑器里显示在通用位置、拖了也不动。
    val density = LocalDensity.current
    val sizeDp = layout.sizeDp.dp
    val (px, py) = buttonOffset(layout, surfaceSize, density)
    var dragStartX by remember { mutableStateOf(0f) }
    var dragStartY by remember { mutableStateOf(0f) }
    var layoutStartX by remember { mutableStateOf(0f) }
    var layoutStartY by remember { mutableStateOf(0f) }

    val currentLayout by rememberUpdatedState(layout)
    val currentOnMove by rememberUpdatedState(onMove)
    val currentOnSelect by rememberUpdatedState(onSelect)
    val currentSurfaceSize by rememberUpdatedState(surfaceSize)

    Box(
        modifier = Modifier
            .offset { IntOffset(px.toInt(), py.toInt()) }
            .size(sizeDp)
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    down.consume()
                    currentOnSelect()
                    dragStartX = down.position.x
                    dragStartY = down.position.y
                    layoutStartX = currentLayout.x
                    layoutStartY = currentLayout.y

                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) { change.consume(); break }
                        if (change.positionChanged()) {
                            val dxPx = change.position.x - dragStartX
                            val dyPx = change.position.y - dragStartY
                            val dxFrac = dxPx / currentSurfaceSize.width
                            val dyFrac = dyPx / currentSurfaceSize.height
                            currentOnMove(layoutStartX + dxFrac, layoutStartY + dyFrac)
                            change.consume()
                        }
                    }
                }
            },
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val r = size.width * 0.46f
            drawCircle(Color(0xFFFFD66B).copy(alpha = if (isSelected) 0.5f else 0.35f), r, Offset(size.width / 2f, size.height / 2f))
            drawCircle(Color(0xFFFFD66B), r, Offset(size.width / 2f, size.height / 2f), style = Stroke(width = if (isSelected) 3.dp.toPx() else 2.dp.toPx()))
        }
        Text("D-Pad", color = Color(0xFFFFD66B), fontSize = (sizeDp.value * 0.15f).sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
    }
}

@Composable
private fun EditablePillBtn(
    label: String,
    layout: ButtonLayout,
    surfaceSize: IntSize,
    isSelected: Boolean,
    onMove: (targetX: Float, targetY: Float) -> Unit,
    onSelect: () -> Unit
) {
    val density = LocalDensity.current
    val sizeDp = layout.sizeDp.dp
    val widthDp = sizeDp * 2.2f
    val heightDp = sizeDp * 0.7f
    val wPx = with(density) { widthDp.toPx() }
    val hPx = with(density) { heightDp.toPx() }
    val px = surfaceSize.width * layout.x - wPx / 2
    val py = surfaceSize.height * layout.y - hPx / 2
    var dragStartX by remember { mutableStateOf(0f) }
    var dragStartY by remember { mutableStateOf(0f) }
    var layoutStartX by remember { mutableStateOf(0f) }
    var layoutStartY by remember { mutableStateOf(0f) }

    val currentLayout by rememberUpdatedState(layout)
    val currentOnMove by rememberUpdatedState(onMove)
    val currentOnSelect by rememberUpdatedState(onSelect)
    val currentSurfaceSize by rememberUpdatedState(surfaceSize)

    Box(
        modifier = Modifier
            .offset { IntOffset(px.toInt(), py.toInt()) }
            .size(width = widthDp, height = heightDp)
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    down.consume()
                    currentOnSelect()
                    dragStartX = down.position.x
                    dragStartY = down.position.y
                    layoutStartX = currentLayout.x
                    layoutStartY = currentLayout.y

                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) { change.consume(); break }
                        if (change.positionChanged()) {
                            val dxPx = change.position.x - dragStartX
                            val dyPx = change.position.y - dragStartY
                            val dxFrac = dxPx / currentSurfaceSize.width
                            val dyFrac = dyPx / currentSurfaceSize.height
                            currentOnMove(layoutStartX + dxFrac, layoutStartY + dyFrac)
                            change.consume()
                        }
                    }
                }
            },
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val w = size.width; val h = size.height; val r = h * 0.4f
            val cr = androidx.compose.ui.geometry.CornerRadius(r, r)
            drawRoundRect(Color(0xFF4A90D9).copy(alpha = if (isSelected) 0.5f else 0.35f), Offset(0f, 0f), Size(w, h), cr)
            drawRoundRect(Color(0xFF4A90D9), Offset(0f, 0f), Size(w, h), cr, style = Stroke(width = if (isSelected) 3.dp.toPx() else 2.dp.toPx()))
        }
        Text(label, color = Color(0xFF4A90D9), fontSize = (sizeDp.value * 0.2f).sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
    }
}

// ---------------------------------------------------------------------------
// Settings panel (in-game) — unified with main SettingsScreen via PadLayoutStore
// Includes FDS BIOS import for Famicom Disk System game support.
// ---------------------------------------------------------------------------
@Composable
private fun SettingsPanel(
    padLayout: PadLayout,
    platform: GamePlatform = GamePlatform.NES,
    onLayoutChange: (PadLayout) -> Unit,
    onClose: () -> Unit,
    onEnterCustomLayout: () -> Unit = {},
    // J2ME 每游戏单独设置：当前游戏是否已有专属配置 + 恢复全局默认回调。
    // 非 JAVA 平台 / 无存储键时传默认值，UI 不显示相关控件。
    javaIsPerGame: Boolean = false,
    onResetJavaToGlobal: (() -> Unit)? = null
) {
    val context = LocalContext.current
    var biosStatus by remember { mutableStateOf(checkFdsBiosStatus(context)) }

    Box(
        modifier = Modifier.fillMaxSize().background(Color(0x88000000))
    )

    Column(
        modifier = Modifier.fillMaxWidth().padding(16.dp)
            .background(Color(0xDD1E2A3A), RoundedCornerShape(16.dp))
            .padding(16.dp)
            .verticalScroll(rememberScrollState())
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("核心设置", color = Color.White, fontSize = 16.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
            Spacer(Modifier.weight(1f))
            IconButton(onClick = onClose) { Icon(Icons.Rounded.Close, "关闭", tint = Color.White) }
        }
        Spacer(Modifier.size(8.dp))

        // Common video settings for all platforms
        DropdownSetting("画面缩放",
            listOf(
                "stretch" to "全屏拉伸(默认)",
                "4:3" to "4:3",
                "2:3" to "2:3 (NDS 双屏)",
                "3:2" to "3:2 (GBA 原生)",
                "8:7" to "8:7 (NES 像素比)",
                "16:9" to "16:9",
                "custom" to "自定义(拖动四角)"
            ),
            padLayout.videoScale
        ) {
            onLayoutChange(padLayout.copy {videoScale = it})
            if (it == "custom") onEnterCustomLayout()
        }

        DropdownSetting("视频滤镜",
            listOf("none" to "关闭", "scanline" to "扫描线", "crt" to "CRT", "dot" to "点阵",
                   "xbr" to "XBR", "hq2x" to "HQ2X", "hq4x" to "HQ4X", "xbr_dot" to "XBR+点阵",
                   "4xbr" to "4XBR", "4xbr_dot" to "4XBR+点阵", "hq4x_dot" to "HQ4X+点阵"),
            padLayout.videoFilter
        ) { onLayoutChange(padLayout.copy {videoFilter = it}) }

        DropdownSetting("横竖屏",
            listOf("sensor" to "自动(传感器)", "landscape" to "强制横屏", "portrait" to "强制竖屏"),
            padLayout.screenOrientation
        ) {
            onLayoutChange(padLayout.copy {screenOrientation = it})
            // Apply orientation change immediately
            val activity = context as? android.app.Activity
            activity?.requestedOrientation = when (it) {
                "landscape" -> android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                "portrait" -> android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
                else -> android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR
            }
        }

        // Direction control: D-Pad vs Analog Stick. Available for all
        // non-DOS/non-JAVA platforms. DOS uses its own overlay; JAVA uses J2ME.
        if (platform != GamePlatform.DOS && platform != GamePlatform.JAVA) {
            DropdownSetting("方向控制",
                listOf("dpad" to "十字键 D-Pad", "analog" to "摇杆 Analog Stick"),
                PadLayoutStore.getInputMode(padLayout, platform)
            ) {
                onLayoutChange(PadLayoutStore.setInputMode(padLayout, platform, it))
            }
        }

        // High-quality scaling toggle — controls native surface buffer geometry.
        // false (default): source-res buffer + GPU upscale = fast (recommended for TV)
        // true: display-res buffer + CPU scale = sharp (recommended for phones)
        SwitchSetting(
            label = "高质量缩放",
            description = "关闭=快速(推荐TV) · 开启=清晰(推荐手机)",
            checked = padLayout.highQualityScaling
        ) { onLayoutChange(padLayout.copy {highQualityScaling = it}) }

        // 全局 FPS 显示 —— 所有平台通用，实时显示模拟帧率
        //（可用来诊断 NDS 等核心是否满速运行）
        SwitchSetting(
            label = "显示帧数",
            description = "游戏画面左上角实时显示模拟 FPS",
            checked = padLayout.showFps
        ) { onLayoutChange(padLayout.copy {showFps = it}) }

        // 玩家切换悬浮球 —— 1P/2P/3P/4P 小圆形按钮（可拖动）
        SwitchSetting(
            label = "玩家切换按钮",
            description = "小圆形悬浮球 · 点击切换玩家 · 可拖动",
            checked = padLayout.showPlayerSwitch
        ) { onLayoutChange(padLayout.copy {showPlayerSwitch = it}) }

        Spacer(Modifier.size(8.dp))
        Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(Color(0x33FFFFFF)))
        Spacer(Modifier.size(8.dp))

        when (platform) {
            GamePlatform.NES -> {
                Text("NES 专属设置", color = Color(0xFFFFD66B), fontSize = 13.sp,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                Spacer(Modifier.size(6.dp))

                DropdownSetting("NTSC 滤镜",
                    listOf("disabled" to "关闭", "composite" to "复合", "svideo" to "S-Video", "rgb" to "RGB", "monochrome" to "黑白"),
                    padLayout.ntscFilter
                ) { onLayoutChange(padLayout.copy {ntscFilter = it}) }

                DropdownSetting("调色板",
                    listOf(
                        "default" to "默认", "asqrealc" to "AspiringSquire", "wii-vc" to "Wii VC",
                        "rgb" to "Nintendo RGB", "yuv-v3" to "FBX YUV-V3", "unsaturated-final" to "Unsaturated",
                        "sony-cxa2025as-us" to "Sony CXA", "pal" to "PAL", "bmf-final2" to "BMF Final 2",
                        "smooth-fbx" to "FBX Smooth", "composite-direct-fbx" to "FBX Composite",
                        "ntsc-hardware-fbx" to "FBX NTSC HW", "nes-classic-fbx" to "FBX NES Classic"
                    ),
                    padLayout.palette
                ) { onLayoutChange(padLayout.copy {palette = it}) }

                DropdownSetting("区域",
                    listOf("Auto" to "自动", "NTSC" to "NTSC", "PAL" to "PAL", "Dendy" to "Dendy"),
                    padLayout.region
                ) { onLayoutChange(padLayout.copy {region = it}) }

                DropdownSetting("裁剪过扫描",
                    listOf("disabled" to "关闭", "enabled" to "开启"),
                    padLayout.cropOverscan
                ) { onLayoutChange(padLayout.copy {cropOverscan = it}) }

                DropdownSetting("超频(减少慢动作)",
                    listOf("disabled" to "关闭", "2x-Postrender" to "后渲染(兼容性好)", "2x-VBlank" to "VBlank(推荐·魂斗罗力量)"),
                    padLayout.overclocking
                ) { onLayoutChange(padLayout.copy {overclocking = it}) }

                Spacer(Modifier.size(12.dp))
                Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(Color(0x33FFFFFF)))
                Spacer(Modifier.size(8.dp))
                Text("FDS BIOS (磁盘系统)", color = Color(0xFFFFD66B), fontSize = 14.sp,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                Spacer(Modifier.size(4.dp))
                Text(
                    "如已将disksys.rom放入assets目录，FDS游戏将自动加载BIOS。" +
                    "也可手动导入disksys.rom (8KB)。",
                    color = Color(0xFF8899AA), fontSize = 10.sp, lineHeight = 14.sp
                )
                Spacer(Modifier.size(6.dp))
                FdsBiosImportSection(
                    biosStatus = biosStatus,
                    onImport = { uri ->
                        val result = importFdsBios(context, uri)
                        biosStatus = checkFdsBiosStatus(context)
                        biosStatus = biosStatus.copy(message = result)
                    }
                )
            }
            GamePlatform.SFC -> {
                Text("SFC/SNES 专属设置", color = Color(0xFFFFD66B), fontSize = 13.sp,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                Spacer(Modifier.size(6.dp))

                Text("画面", color = Color(0xFF8899AA), fontSize = 11.sp)
                DropdownSetting("画面比例",
                    listOf("4:3" to "4:3 (标准)", "uncorrected" to "8:7 (原始像素比)",
                           "auto" to "自动", "ntsc" to "NTSC", "pal" to "PAL"),
                    padLayout.aspectRatio
                ) { onLayoutChange(padLayout.copy {aspectRatio = it}) }

                DropdownSetting("NTSC 滤镜",
                    listOf("disabled" to "关闭", "monochrome" to "黑白", "rf" to "RF",
                           "composite" to "复合", "s-video" to "S-Video", "rgb" to "RGB"),
                    padLayout.ntscFilter
                ) { onLayoutChange(padLayout.copy {ntscFilter = it}) }

                DropdownSetting("裁剪过扫描",
                    listOf("enabled" to "开启", "disabled" to "关闭", "auto" to "自动"),
                    padLayout.sfcOverscan
                ) { onLayoutChange(padLayout.copy {sfcOverscan = it}) }

                DropdownSetting("高分辨率模式",
                    listOf("enabled" to "开启", "disabled" to "关闭"),
                    padLayout.sfcGfxHires
                ) { onLayoutChange(padLayout.copy {sfcGfxHires = it}) }

                DropdownSetting("透明效果",
                    listOf("enabled" to "开启", "disabled" to "关闭"),
                    padLayout.sfcGfxTransparency
                ) { onLayoutChange(padLayout.copy {sfcGfxTransparency = it}) }

                DropdownSetting("图形裁剪",
                    listOf("enabled" to "开启", "disabled" to "关闭"),
                    padLayout.sfcGfxClip
                ) { onLayoutChange(padLayout.copy {sfcGfxClip = it}) }

                DropdownSetting("允许无效VRAM访问",
                    listOf("disabled" to "开启 (允许)", "enabled" to "关闭 (禁止)"),
                    padLayout.sfcBlockInvalidVram
                ) { onLayoutChange(padLayout.copy {sfcBlockInvalidVram = it}) }

                DropdownSetting("高分辨率混合",
                    listOf("disabled" to "关闭", "merge" to "合并", "blur" to "模糊"),
                    padLayout.sfcSideBySide
                ) { onLayoutChange(padLayout.copy {sfcSideBySide = it}) }

                Spacer(Modifier.size(4.dp))
                Text("性能", color = Color(0xFF8899AA), fontSize = 11.sp)
                DropdownSetting("超频(SuperFX)",
                    listOf("100%" to "100% (默认)", "150%" to "150%", "200%" to "200%",
                           "300%" to "300%", "400%" to "400%", "500%" to "500%"),
                    padLayout.sfcOverclock
                ) { onLayoutChange(padLayout.copy {sfcOverclock = it}) }

                DropdownSetting("减少精灵闪烁",
                    listOf("disabled" to "关闭", "enabled" to "开启"),
                    padLayout.sfcReduceSpriteFlicker
                ) { onLayoutChange(padLayout.copy {sfcReduceSpriteFlicker = it}) }

                DropdownSetting("减少慢动作",
                    listOf("disabled" to "关闭", "light" to "轻微",
                           "compatible" to "兼容", "max" to "最大"),
                    padLayout.sfcReduceSlowdown
                ) { onLayoutChange(padLayout.copy {sfcReduceSlowdown = it}) }

                Spacer(Modifier.size(4.dp))
                Text("音频", color = Color(0xFF8899AA), fontSize = 11.sp)
                DropdownSetting("音频插值",
                    listOf("gaussian" to "高斯(默认)", "cubic" to "三次", "sinc" to "Sinc",
                           "linear" to "线性", "none" to "无"),
                    padLayout.sfcAudioInterpolation
                ) { onLayoutChange(padLayout.copy {sfcAudioInterpolation = it}) }

                DropdownSetting("回声缓冲Hack",
                    listOf("disabled" to "关闭", "enabled" to "开启(旧版Addmusic)"),
                    padLayout.sfcSoundOutput
                ) { onLayoutChange(padLayout.copy {sfcSoundOutput = it}) }

                Spacer(Modifier.size(4.dp))
                Text("输入", color = Color(0xFF8899AA), fontSize = 11.sp)
                DropdownSetting("上下方向同时输入",
                    listOf("disabled" to "关闭", "enabled" to "开启"),
                    padLayout.sfcUpDownAllowed
                ) { onLayoutChange(padLayout.copy {sfcUpDownAllowed = it}) }

                DropdownSetting("随机内存(不安全)",
                    listOf("disabled" to "关闭", "enabled" to "开启"),
                    padLayout.sfcSuperScope
                ) { onLayoutChange(padLayout.copy {sfcSuperScope = it}) }

                Spacer(Modifier.size(4.dp))
                Text("图层显示", color = Color(0xFF8899AA), fontSize = 11.sp)
                DropdownSetting("BG图层 1",
                    listOf("enabled" to "显示", "disabled" to "隐藏"),
                    padLayout.sfcLayer1
                ) { onLayoutChange(padLayout.copy {sfcLayer1 = it}) }

                DropdownSetting("BG图层 2",
                    listOf("enabled" to "显示", "disabled" to "隐藏"),
                    padLayout.sfcLayer2
                ) { onLayoutChange(padLayout.copy {sfcLayer2 = it}) }

                DropdownSetting("BG图层 3",
                    listOf("enabled" to "显示", "disabled" to "隐藏"),
                    padLayout.sfcLayer3
                ) { onLayoutChange(padLayout.copy {sfcLayer3 = it}) }

                DropdownSetting("BG图层 4",
                    listOf("enabled" to "显示", "disabled" to "隐藏"),
                    padLayout.sfcLayer4
                ) { onLayoutChange(padLayout.copy {sfcLayer4 = it}) }

                DropdownSetting("精灵图层",
                    listOf("enabled" to "显示", "disabled" to "隐藏"),
                    padLayout.sfcLayer5
                ) { onLayoutChange(padLayout.copy {sfcLayer5 = it}) }
            }
            GamePlatform.GB, GamePlatform.GBA -> {
                val platName = if (platform == GamePlatform.GBA) "GBA" else "GB/GBC"
                Text("$platName 专属设置", color = Color(0xFFFFD66B), fontSize = 13.sp,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                Spacer(Modifier.size(6.dp))

                Text("系统", color = Color(0xFF8899AA), fontSize = 11.sp)
                DropdownSetting("主机型号",
                    listOf("Autodetect" to "自动", "Game Boy" to "Game Boy (DMG)",
                           "Super Game Boy" to "Super Game Boy", "Game Boy Color" to "Game Boy Color",
                           "Game Boy Advance" to "Game Boy Advance"),
                    padLayout.gbModel
                ) { onLayoutChange(padLayout.copy {gbModel = it}) }

                DropdownSetting("SGB 边框",
                    listOf("ON" to "显示", "OFF" to "隐藏"),
                    padLayout.gbSgbBorders
                ) { onLayoutChange(padLayout.copy {gbSgbBorders = it}) }

                Spacer(Modifier.size(4.dp))
                Text("色彩校正", color = Color(0xFF8899AA), fontSize = 11.sp)
                if (platform == GamePlatform.GB) {
                    DropdownSetting("GB色彩校正",
                        listOf("enabled" to "开启", "disabled" to "关闭"),
                        padLayout.gbColorCorrection
                    ) { onLayoutChange(padLayout.copy {gbColorCorrection = it}) }

                    DropdownSetting("GB色彩预设",
                        listOf("default" to "默认", "AGB" to "GBA风格", "GB Pocket" to "Pocket风格",
                               "GB Light" to "亮色", "GB Original" to "原始"),
                        padLayout.gbcColorPreset
                    ) { onLayoutChange(padLayout.copy {gbcColorPreset = it}) }
                }
                if (platform == GamePlatform.GBA) {
                    DropdownSetting("GBA色彩校正",
                        listOf("enabled" to "开启", "disabled" to "关闭"),
                        padLayout.gbaColorCorrection
                    ) { onLayoutChange(padLayout.copy {gbaColorCorrection = it}) }

                    DropdownSetting("GBA色彩预设",
                        listOf("default" to "默认", "AGB" to "GBA原机", "GBA SP" to "GBA SP风格",
                               "GB Micro" to "GB Micro风格"),
                        padLayout.gbaColorPreset
                    ) { onLayoutChange(padLayout.copy {gbaColorPreset = it}) }
                }

                Spacer(Modifier.size(4.dp))
                Text("画面", color = Color(0xFF8899AA), fontSize = 11.sp)
                DropdownSetting("帧混合",
                    listOf("OFF" to "关闭", "ON" to "开启", "fast" to "快速"),
                    padLayout.gbaFrameBlending
                ) { onLayoutChange(padLayout.copy {gbaFrameBlending = it}) }

                Spacer(Modifier.size(4.dp))
                Text("音频", color = Color(0xFF8899AA), fontSize = 11.sp)
                DropdownSetting("音频重采样器",
                    listOf("nearest" to "最近邻(快速)", "sinc" to "Sinc(高质量)",
                           "cosine" to "余弦(均衡)", "cubic" to "三次(高质量)"),
                    padLayout.gbaAudioResampler
                ) { onLayoutChange(padLayout.copy {gbaAudioResampler = it}) }

                DropdownSetting("低通滤波",
                    listOf("disabled" to "关闭", "enabled" to "开启"),
                    padLayout.gbaAudioLowPass
                ) { onLayoutChange(padLayout.copy {gbaAudioLowPass = it}) }

                DropdownSetting("低通滤波范围",
                    listOf("20" to "20", "40" to "40", "60" to "60 (默认)",
                           "80" to "80", "100" to "100"),
                    padLayout.gbaAudioLowPassRange
                ) { onLayoutChange(padLayout.copy {gbaAudioLowPassRange = it}) }

                Spacer(Modifier.size(4.dp))
                Text("性能", color = Color(0xFF8899AA), fontSize = 11.sp)
                DropdownSetting("跳帧类型",
                    listOf("disabled" to "关闭", "auto" to "自动跳帧", "fixed" to "固定跳帧"),
                    padLayout.gbaFrameskipType
                ) { onLayoutChange(padLayout.copy {gbaFrameskipType = it}) }

                DropdownSetting("跳帧数量",
                    listOf("0" to "0", "1" to "1", "2" to "2", "3" to "3",
                           "4" to "4", "5" to "5", "6" to "6", "7" to "7",
                           "8" to "8", "9" to "9", "10" to "10"),
                    padLayout.gbaFrameskipCount
                ) { onLayoutChange(padLayout.copy {gbaFrameskipCount = it}) }

                DropdownSetting("跳帧阈值(自动)",
                    listOf("10" to "10", "20" to "20", "33" to "33 (默认)",
                           "50" to "50", "70" to "70", "90" to "90"),
                    padLayout.gbaFrameskipThreshold
                ) { onLayoutChange(padLayout.copy {gbaFrameskipThreshold = it}) }

                if (platform == GamePlatform.GBA) {
                    DropdownSetting("空闲优化",
                        listOf("disabled" to "关闭", "enabled" to "开启"),
                        padLayout.gbaIdleOptimization
                    ) { onLayoutChange(padLayout.copy {gbaIdleOptimization = it}) }
                }

                Spacer(Modifier.size(4.dp))
                Text("高级", color = Color(0xFF8899AA), fontSize = 11.sp)
                DropdownSetting("允许相反方向",
                    listOf("OFF" to "关闭", "ON" to "开启"),
                    padLayout.gbaAllowOpposite
                ) { onLayoutChange(padLayout.copy {gbaAllowOpposite = it}) }

                DropdownSetting("太阳能传感器",
                    listOf("0" to "0 (黑暗)", "1" to "1", "2" to "2", "3" to "3",
                           "4" to "4", "5" to "5 (中等)", "6" to "6", "7" to "7",
                           "8" to "8", "9" to "9", "10" to "10 (明亮)"),
                    padLayout.gbaSolarSensor
                ) { onLayoutChange(padLayout.copy {gbaSolarSensor = it}) }

                if (platform == GamePlatform.GBA) {
                    DropdownSetting("强制RTC",
                        listOf("disabled" to "关闭", "enabled" to "开启"),
                        padLayout.gbaForceRTC
                    ) { onLayoutChange(padLayout.copy {gbaForceRTC = it}) }
                }
            }
            GamePlatform.DOS -> {
                Text("DOSBox 专属设置", color = Color(0xFFFFD66B), fontSize = 13.sp,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                Spacer(Modifier.size(6.dp))

                Text("机器类型", color = Color(0xFF8899AA), fontSize = 11.sp)
                DropdownSetting("显示芯片",
                    listOf(
                        "svga_s3" to "SVGA (S3 Trio64, 推荐)",
                        "vgaonly" to "VGA Only",
                        "ega" to "EGA",
                        "cga" to "CGA",
                        "tandy" to "Tandy",
                        "pcjr" to "PCjr",
                        "hercules" to "Hercules",
                        "none" to "无(仅文本模式)"
                    ),
                    padLayout.dosMachine
                ) { onLayoutChange(padLayout.copy {dosMachine = it}) }

                Text("CPU 性能", color = Color(0xFF8899AA), fontSize = 11.sp)
                DropdownSetting("CPU 周期",
                    listOf(
                        "auto" to "自动(推荐)",
                        "max" to "最大",
                        "6000" to "6000 (80386)",
                        "10000" to "10000 (80486)",
                        "20000" to "20000 (Pentium)",
                        "40000" to "40000 (Pentium II)",
                        "80000" to "80000 (Pentium III)",
                        "custom" to "自定义"
                    ),
                    padLayout.dosCycles
                ) { onLayoutChange(padLayout.copy {dosCycles = it}) }

                if (padLayout.dosCycles == "custom") {
                    DropdownSetting("自定义周期",
                        listOf("10000" to "10000", "20000" to "20000",
                               "30000" to "30000", "50000" to "50000",
                               "80000" to "80000", "100000" to "100000"),
                        padLayout.dosCyclesMax
                    ) { onLayoutChange(padLayout.copy {dosCyclesMax = it}) }
                }

                Spacer(Modifier.size(8.dp))
                Text("音频", color = Color(0xFF8899AA), fontSize = 11.sp)
                DropdownSetting("声霸卡类型",
                    listOf(
                        "sb16" to "Sound Blaster 16 (推荐·默认)",
                        "sbpro2" to "Sound Blaster Pro 2",
                        "sbpro1" to "Sound Blaster Pro",
                        "sb2" to "Sound Blaster 2.0",
                        "none" to "关闭声音"
                    ),
                    padLayout.dosSbType
                ) { onLayoutChange(padLayout.copy {dosSbType = it}) }

                // 移除复杂的 Adlib / GUS 设置，使用 DOSBox-Pure 默认值即可。
                // 大部分 DOS 游戏使用 Sound Blaster 16 即可获得原始声音效果，
                // 这些高级选项反而容易导致声音异常或延迟。
                // 如需调整可手动通过 PadLayout 字段设置。

                Spacer(Modifier.size(8.dp))
                Text("鼠标", color = Color(0xFF8899AA), fontSize = 11.sp)
                DropdownSetting("鼠标输入模式",
                    listOf(
                        "touchpad" to "触控板(推荐·默认)",
                        "auto" to "自动",
                        "virtual" to "虚拟鼠标",
                        "direct" to "直接控制",
                        "off" to "关闭"
                    ),
                    padLayout.dosMouseInput
                ) { onLayoutChange(padLayout.copy {dosMouseInput = it}) }


                DropdownSetting("混音器采样率(核心)",
                    listOf(
                        "48000" to "48000 Hz (推荐)",
                        "44100" to "44100 Hz",
                        "32000" to "32000 Hz",
                        "22050" to "22050 Hz",
                        "11025" to "11025 Hz",
                        "8000" to "8000 Hz",
                        "49716" to "49716 Hz (OPL 完美还原)"
                    ),
                    padLayout.dosAudiorate
                ) { onLayoutChange(padLayout.copy {dosAudiorate = it}) }

                Spacer(Modifier.size(8.dp))
                Text("键盘", color = Color(0xFF8899AA), fontSize = 11.sp)
                DropdownSetting("键盘布局",
                    listOf(
                        "us" to "US (美式)", "uk" to "UK (英式)",
                        "de" to "德语", "fr" to "法语", "it" to "意大利语",
                        "es" to "西班牙语", "br" to "巴西", "ru" to "俄语",
                        "jp" to "日语"
                    ),
                    padLayout.dosKeyboardLayout
                ) { onLayoutChange(padLayout.copy {dosKeyboardLayout = it}) }

                Spacer(Modifier.size(8.dp))
                Text("画面", color = Color(0xFF8899AA), fontSize = 11.sp)
                DropdownSetting("宽高比修正(CRT)",
                    listOf("false" to "关闭", "true" to "开启"),
                    padLayout.dosAspectCorrection
                ) { onLayoutChange(padLayout.copy {dosAspectCorrection = it}) }

                DropdownSetting("CGA 模式",
                    listOf(
                        "early_auto" to "早期型 · 复合自动 (默认)",
                        "early_on" to "早期型 · 复合开",
                        "early_off" to "早期型 · 复合关",
                        "late_auto" to "后期型 · 复合自动",
                        "late_on" to "后期型 · 复合开",
                        "late_off" to "后期型 · 复合关"
                    ),
                    padLayout.dosCgaMode
                ) { onLayoutChange(padLayout.copy {dosCgaMode = it}) }

                Spacer(Modifier.size(8.dp))
                Text("高级", color = Color(0xFF8899AA), fontSize = 11.sp)
                DropdownSetting("自动键位映射",
                    listOf("on" to "开启(推荐)", "off" to "关闭"),
                    padLayout.dosAutoMapping
                ) { onLayoutChange(padLayout.copy {dosAutoMapping = it}) }

                DropdownSetting("Voodoo 显卡",
                    listOf("off" to "关闭", "on" to "开启"),
                    padLayout.dosVoodoo
                ) { onLayoutChange(padLayout.copy {dosVoodoo = it}) }

                DropdownSetting("强制 60fps",
                    listOf("on" to "开启(推荐)", "off" to "关闭"),
                    padLayout.dosForce60fps
                ) { onLayoutChange(padLayout.copy {dosForce60fps = it}) }

                DropdownSetting("存档大小",
                    listOf("on" to "默认", "500" to "500MB", "1000" to "1GB",
                           "2000" to "2GB", "4000" to "4GB", "8000" to "8GB", "0" to "关闭"),
                    padLayout.dosSavestate
                ) { onLayoutChange(padLayout.copy {dosSavestate = it}) }

                Spacer(Modifier.size(8.dp))
                Text("输入模式", color = Color(0xFF8899AA), fontSize = 11.sp)
                DropdownSetting("虚拟按键模式",
                    listOf("gamepad" to "手柄(圆形按钮)", "keyboard" to "全键盘(QWERTY)"),
                    padLayout.dosInputMode
                ) { onLayoutChange(padLayout.copy {dosInputMode = it}) }
            }
            GamePlatform.ARCADE -> {
                Text("Arcade (FBNeo) 专属设置", color = Color(0xFFFFD66B), fontSize = 13.sp,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                Spacer(Modifier.size(6.dp))

                Text("输入", color = Color(0xFF8899AA), fontSize = 11.sp)
                DropdownSetting("方向控制",
                    listOf("dpad" to "十字键 D-Pad", "analog" to "摇杆 Analog Stick"),
                    padLayout.arcadeInputMode
                ) { onLayoutChange(padLayout.copy {arcadeInputMode = it}) }
                DropdownSetting("显示 L2/R2 按键",
                    listOf("false" to "关闭 (4键默认)", "true" to "开启 (6键格斗)"),
                    padLayout.arcadeShowL2R2.toString()
                ) { onLayoutChange(padLayout.copy {arcadeShowL2R2 = it.toBoolean()}) }

                Spacer(Modifier.size(4.dp))
                Text("画面", color = Color(0xFF8899AA), fontSize = 11.sp)
                DropdownSetting("画面比例",
                    listOf("auto" to "自动", "4:3" to "4:3 (标准)",
                           "3:4" to "3:4 (竖屏)", "16:9" to "16:9", "16:15" to "16:15"),
                    padLayout.arcadeAspect
                ) { onLayoutChange(padLayout.copy {arcadeAspect = it}) }

                DropdownSetting("画面旋转",
                    listOf("norotate" to "不旋转", "cw" to "顺时针90°",
                           "ccw" to "逆时针90°", "flip" to "翻转180°"),
                    padLayout.arcadeRotate
                ) { onLayoutChange(padLayout.copy {arcadeRotate = it}) }

                DropdownSetting("竖屏模式",
                    listOf("disabled" to "关闭", "enabled" to "开启"),
                    padLayout.arcadeVerticalMode
                ) { onLayoutChange(padLayout.copy {arcadeVerticalMode = it}) }

                DropdownSetting("裁剪过扫描",
                    listOf("enabled" to "开启", "disabled" to "关闭"),
                    padLayout.arcadeCropOverscan
                ) { onLayoutChange(padLayout.copy {arcadeCropOverscan = it}) }

                Spacer(Modifier.size(4.dp))
                Text("性能", color = Color(0xFF8899AA), fontSize = 11.sp)
                DropdownSetting("CPU速度",
                    listOf("100" to "100%", "75" to "75%", "50" to "50%",
                           "150" to "150%", "200" to "200%", "250" to "250%"),
                    padLayout.arcadeCpuSpeed
                ) { onLayoutChange(padLayout.copy {arcadeCpuSpeed = it}) }

                DropdownSetting("跳帧",
                    listOf("0" to "0", "1" to "1", "2" to "2", "3" to "3",
                           "4" to "4", "5" to "5", "6" to "6", "8" to "8", "10" to "10"),
                    padLayout.arcadeFrameskip
                ) { onLayoutChange(padLayout.copy {arcadeFrameskip = it}) }

                DropdownSetting("强制60Hz",
                    listOf("disabled" to "关闭", "enabled" to "开启"),
                    padLayout.arcadeForce60hz
                ) { onLayoutChange(padLayout.copy {arcadeForce60hz = it}) }

                Spacer(Modifier.size(4.dp))
                Text("音频", color = Color(0xFF8899AA), fontSize = 11.sp)
                DropdownSetting("采样率",
                    listOf("48000" to "48000 Hz", "44100" to "44100 Hz",
                           "22050" to "22050 Hz"),
                    padLayout.arcadeSampleRate
                ) { onLayoutChange(padLayout.copy {arcadeSampleRate = it}) }

                DropdownSetting("音频插值",
                    listOf("0" to "关闭", "1" to "最近邻", "2" to "线性(推荐)", "3" to "三次"),
                    padLayout.arcadeAudioInterp
                ) { onLayoutChange(padLayout.copy {arcadeAudioInterp = it}) }

                DropdownSetting("低通滤波",
                    listOf("disabled" to "关闭", "enabled" to "开启"),
                    padLayout.arcadeLowpass
                ) { onLayoutChange(padLayout.copy {arcadeLowpass = it}) }

                Spacer(Modifier.size(4.dp))
                Text("NeoGeo", color = Color(0xFF8899AA), fontSize = 11.sp)
                DropdownSetting("NeoGeo模式",
                    listOf("MVS" to "MVS(街机)", "AES" to "AES(家用)"),
                    padLayout.arcadeNeogeomode
                ) { onLayoutChange(padLayout.copy {arcadeNeogeomode = it}) }

                DropdownSetting("记忆卡",
                    listOf("enabled" to "开启", "disabled" to "关闭"),
                    padLayout.arcadeMemcard
                ) { onLayoutChange(padLayout.copy {arcadeMemcard = it}) }

                Spacer(Modifier.size(12.dp))
                Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(Color(0x33FFFFFF)))
                Spacer(Modifier.size(8.dp))
                Text("FBNeo BIOS 管理", color = Color(0xFFFFD66B), fontSize = 14.sp,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                Spacer(Modifier.size(4.dp))
                Text(
                    "街机游戏需要BIOS文件放在系统目录(<filesDir>/fbneo/)。" +
                    "NeoGeo游戏需要 neogeo.zip, PGM游戏(三国战纪/魔窟等)需要 pgm.zip。" +
                    "下方可手动导入BIOS zip文件。",
                    color = Color(0xFF8899AA), fontSize = 10.sp, lineHeight = 14.sp
                )
                Spacer(Modifier.size(6.dp))
                ArcadeBiosImportSection()

                Spacer(Modifier.size(12.dp))
                Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(Color(0x33FFFFFF)))
                Spacer(Modifier.size(8.dp))
                Text("街机 ROM 兼容性帮助", color = Color(0xFFFFD66B), fontSize = 14.sp,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                Spacer(Modifier.size(4.dp))
                Text(
                    "FBNeo 对 ROM 集要求严格。若打开游戏时出现 \"Romset is unknown\"、" +
                    "\"missing files\"、\"Verify the following romsets\" 等错误，请按下列步骤排查：",
                    color = Color(0xFF8899AA), fontSize = 10.sp, lineHeight = 14.sp
                )
                Spacer(Modifier.size(6.dp))
                Text("1. BIOS 缺失", color = Color(0xFFFFD66B), fontSize = 11.sp,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                Text(
                    "• NeoGeo 游戏（拳皇/合金弹头/侍魂/月华等）必须将 neogeo.zip " +
                    "放在系统目录。\n" +
                    "• PGM 游戏（三国战纪/西游释厄传/形意拳/神剑伏魔录）必须将 " +
                    "pgm.zip 放在系统目录。\n" +
                    "• CPS1/CPS2 游戏一般不需要 BIOS，但部分需要 cps1.zip/cps2.zip。\n" +
                    "• 用上方 \"FBNeo BIOS 管理\" 导入，或把 zip 文件放入 " +
                    "app/src/main/assets/fbneo/ 重新构建。",
                    color = Color(0xFFB0BEC5), fontSize = 10.sp, lineHeight = 14.sp
                )
                Spacer(Modifier.size(6.dp))
                Text("2. 父 ROM 缺失（克隆版/测试版/改版）", color = Color(0xFFFFD66B), fontSize = 11.sp,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                Text(
                    "• 克隆版（如 kof98h）需要父 ROM（如 kof98.zip）同时存在。\n" +
                    "• 测试版（如 kof97t，拳皇97三问测试版）需要 kof97.zip 父 ROM " +
                    "和 neogeo.zip BIOS。\n" +
                    "• 改版/魔改版（带 bl/h/x 后缀）通常也需要父 ROM。\n" +
                    "• 把父 ROM 和克隆版放在同一目录即可，FBNeo 会自动加载。",
                    color = Color(0xFFB0BEC5), fontSize = 10.sp, lineHeight = 14.sp
                )
                Spacer(Modifier.size(6.dp))
                Text("3. ROM 版本不匹配", color = Color(0xFFFFD66B), fontSize = 11.sp,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                Text(
                    "• FBNeo 核心 ROM 集定义会随版本更新。若你的 ROM 是从老版本 " +
                    "FBNeo/MAME 提取的，可能在新版核心中找不到对应驱动。\n" +
                    "• 解决：使用与本核心版本匹配的 ROM 集（推荐从 " +
                    "https://docs.libretro.com/development/roms/ 查找兼容 ROM）。\n" +
                    "• 不要随意重命名 zip 文件 — 文件名就是驱动名，错了就找不到。",
                    color = Color(0xFFB0BEC5), fontSize = 10.sp, lineHeight = 14.sp
                )
                Spacer(Modifier.size(6.dp))
                Text("4. CRC 校验失败", color = Color(0xFFFFD66B), fontSize = 11.sp,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                Text(
                    "• FBNeo 会校验每个 ROM 文件的 CRC32。若 ROM 被修改过或损坏，" +
                    "会报 \"ROM with name XXX and CRC 0xYYYY is missing\"。\n" +
                    "• 这不是 app 的 bug，而是 ROM 集本身不完整。请重新下载完整 ROM。",
                    color = Color(0xFFB0BEC5), fontSize = 10.sp, lineHeight = 14.sp
                )
                Spacer(Modifier.size(6.dp))
                Text("常见错误示例", color = Color(0xFFFFD66B), fontSize = 11.sp,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                Text(
                    "• kof97t.zip 报 \"Verify: kof97t kof97 neogeo\" + 缺 232-p1t.dif 等:\n" +
                    "  → 需要把 kof97.zip（父 ROM）和 neogeo.zip（BIOS）放入同目录。\n" +
                    "  → kof97t.zip 必须是匹配本 FBNeo 版本的完整测试版 ROM 集。\n" +
                    "• mslug3.zip 报 \"missing neogeo BIOS\":\n" +
                    "  → 把 neogeo.zip 放入系统目录（Settings → 街机 → BIOS 管理）。\n" +
                    "• kof98.zip 报 \"Romset is unknown\":\n" +
                    "  → FBNeo 不认识这个 ROM，可能版本太老或文件名错误。",
                    color = Color(0xFFB0BEC5), fontSize = 10.sp, lineHeight = 14.sp
                )
            }
            GamePlatform.MD -> {
                Text("MD/SEGA (Genesis-Plus-GX) 专属设置", color = Color(0xFFFFD66B), fontSize = 13.sp,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                Spacer(Modifier.size(6.dp))
                Text("注意: SS(Saturn)不在本核心支持范围内, 仅MD/SMS/GG/SG/Mega-CD。",
                    color = Color(0xFFFFAAAA), fontSize = 10.sp, lineHeight = 14.sp)
                Spacer(Modifier.size(6.dp))

                Text("系统", color = Color(0xFF8899AA), fontSize = 11.sp)
                DropdownSetting("区域",
                    listOf("auto" to "自动", "ntsc-u" to "NTSC-U(美)",
                           "pal" to "PAL(欧)", "ntsc-j" to "NTSC-J(日)"),
                    padLayout.mdRegion
                ) { onLayoutChange(padLayout.copy {mdRegion = it}) }

                DropdownSetting("系统型号",
                    listOf("auto" to "自动", "md" to "Mega Drive",
                           "sms" to "Master System", "gg" to "Game Gear", "sg" to "SG-1000"),
                    padLayout.mdSystem
                ) { onLayoutChange(padLayout.copy {mdSystem = it}) }

                Spacer(Modifier.size(4.dp))
                Text("画面", color = Color(0xFF8899AA), fontSize = 11.sp)
                DropdownSetting("画面比例",
                    listOf("auto" to "自动", "4:3" to "4:3 (标准)",
                           "16:9" to "16:9", "stretch" to "全屏拉伸"),
                    padLayout.mdAspect
                ) { onLayoutChange(padLayout.copy {mdAspect = it}) }

                DropdownSetting("渲染模式",
                    listOf("normal" to "普通", "double" to "双倍",
                           "interlaced" to "隔行扫描"),
                    padLayout.mdRender
                ) { onLayoutChange(padLayout.copy {mdRender = it}) }

                DropdownSetting("NTSC滤镜",
                    listOf("disabled" to "关闭", "monochrome" to "黑白", "rf" to "RF",
                           "composite" to "复合", "s-video" to "S-Video", "rgb" to "RGB"),
                    padLayout.mdNtscFilter
                ) { onLayoutChange(padLayout.copy {mdNtscFilter = it}) }

                DropdownSetting("LCD滤镜",
                    listOf("disabled" to "关闭", "enabled" to "开启"),
                    padLayout.mdLcdFilter
                ) { onLayoutChange(padLayout.copy {mdLcdFilter = it}) }

                DropdownSetting("过扫描",
                    listOf("disabled" to "关闭", "enabled" to "开启"),
                    padLayout.mdOverscan
                ) { onLayoutChange(padLayout.copy {mdOverscan = it}) }

                DropdownSetting("GG扩展屏幕",
                    listOf("disabled" to "关闭(原始160x144)", "enabled" to "开启(扩展256x144)"),
                    padLayout.mdGgExtra
                ) { onLayoutChange(padLayout.copy {mdGgExtra = it}) }

                DropdownSetting("GG画面拉伸",
                    listOf("disabled" to "关闭", "enabled" to "开启"),
                    padLayout.mdGgStretch
                ) { onLayoutChange(padLayout.copy {mdGgStretch = it}) }

                DropdownSetting("左侧边框",
                    listOf("disabled" to "关闭", "enabled" to "开启"),
                    padLayout.mdLeftBorder
                ) { onLayoutChange(padLayout.copy {mdLeftBorder = it}) }

                Spacer(Modifier.size(4.dp))
                Text("输入", color = Color(0xFF8899AA), fontSize = 11.sp)
                DropdownSetting("手柄类型",
                    listOf("3 button" to "3键手柄(经典)", "6 button" to "6键手柄(街机)"),
                    padLayout.mdInput
                ) { onLayoutChange(padLayout.copy {mdInput = it}) }

                DropdownSetting("允许上下同时输入",
                    listOf("disabled" to "关闭", "enabled" to "开启"),
                    padLayout.mdAllowUpDown
                ) { onLayoutChange(padLayout.copy {mdAllowUpDown = it}) }

                Spacer(Modifier.size(4.dp))
                Text("性能", color = Color(0xFF8899AA), fontSize = 11.sp)
                DropdownSetting("超频",
                    listOf("100%" to "100%", "125%" to "125%",
                           "150%" to "150%", "200%" to "200%"),
                    padLayout.mdOverclock
                ) { onLayoutChange(padLayout.copy {mdOverclock = it}) }

                DropdownSetting("跳帧",
                    listOf("0" to "0", "1" to "1", "2" to "2", "3" to "3", "4" to "4", "5" to "5"),
                    padLayout.mdFrameskip
                ) { onLayoutChange(padLayout.copy {mdFrameskip = it}) }

                Spacer(Modifier.size(4.dp))
                Text("Mega-CD", color = Color(0xFF8899AA), fontSize = 11.sp)
                DropdownSetting("CD快速启动",
                    listOf("enabled" to "开启(跳过BIOS动画)", "disabled" to "关闭"),
                    padLayout.mdCdFastboot
                ) { onLayoutChange(padLayout.copy {mdCdFastboot = it}) }

                Spacer(Modifier.size(4.dp))
                Text("Master System", color = Color(0xFF8899AA), fontSize = 11.sp)
                DropdownSetting("FM音源",
                    listOf("auto" to "自动", "on" to "开启", "off" to "关闭"),
                    padLayout.mdSmsFm
                ) { onLayoutChange(padLayout.copy {mdSmsFm = it}) }

                Spacer(Modifier.size(12.dp))
                Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(Color(0x33FFFFFF)))
                Spacer(Modifier.size(8.dp))
                Text("Mega-CD BIOS 管理", color = Color(0xFFFFD66B), fontSize = 14.sp,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                Spacer(Modifier.size(4.dp))
                Text(
                    "Mega-CD/SEGA-CD游戏需要BIOS文件放在系统目录(< filesDir >/genesis/)。" +
                    "卡带游戏(MD/SMS/GG/SG)无需BIOS。" +
                    "需要: bios_CD_E.zip(欧), bios_CD_J.zip(日), bios_CD_U.zip(美)。",
                    color = Color(0xFF8899AA), fontSize = 10.sp, lineHeight = 14.sp
                )
                Spacer(Modifier.size(6.dp))
                GenesisBiosImportSection()
            }
            GamePlatform.PCE -> {
                Text("PCE/TG16 (Geargrafx) 专属设置", color = Color(0xFFFFD66B), fontSize = 13.sp,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                Spacer(Modifier.size(6.dp))
                Text("支持: PC-Engine / TurboGrafx-16 / SuperGrafx / PCE-CD。",
                    color = Color(0xFF8899AA), fontSize = 10.sp, lineHeight = 14.sp)
                Text("卡带(.pce/.sgx)和HES音乐文件(.hes)无需BIOS; PCE-CD(.cue/.chd)需要System Card BIOS。",
                    color = Color(0xFF8899AA), fontSize = 10.sp, lineHeight = 14.sp)
                Spacer(Modifier.size(6.dp))

                Text("系统", color = Color(0xFF8899AA), fontSize = 11.sp)
                DropdownSetting("主机型号",
                    listOf("Auto" to "自动", "PC Engine (JAP)" to "PC-Engine(日)",
                           "SuperGrafx (JAP)" to "SuperGrafx(日)",
                           "TurboGrafx-16 (USA)" to "TurboGrafx-16(美)"),
                    padLayout.pceConsoleType
                ) { onLayoutChange(padLayout.copy {pceConsoleType = it}) }

                Spacer(Modifier.size(4.dp))
                Text("画面", color = Color(0xFF8899AA), fontSize = 11.sp)
                DropdownSetting("画面比例",
                    listOf("1:1 PAR" to "1:1 (像素方形)",
                           "4:3 DAR" to "4:3 (标准)",
                           "6:5 DAR" to "6:5",
                           "16:9 DAR" to "16:9", "16:10 DAR" to "16:10"),
                    padLayout.pceAspect
                ) { onLayoutChange(padLayout.copy {pceAspect = it}) }

                DropdownSetting("过扫描",
                    listOf("Disabled" to "关闭", "Enabled" to "开启"),
                    padLayout.pceOverscan
                ) { onLayoutChange(padLayout.copy {pceOverscan = it}) }

                DropdownSetting("精灵数限制",
                    listOf("Disabled" to "关闭(原始,可能有闪烁)", "Enabled" to "开启(消除闪烁)"),
                    padLayout.pceNoSpriteLimit
                ) { onLayoutChange(padLayout.copy {pceNoSpriteLimit = it}) }

                DropdownSetting("调色板",
                    listOf("Standard RGB" to "标准RGB", "Turboxray" to "Turboxray", "Kitrinx" to "Kitrinx"),
                    padLayout.pcePalette
                ) { onLayoutChange(padLayout.copy {pcePalette = it}) }

                Spacer(Modifier.size(4.dp))
                Text("输入", color = Color(0xFF8899AA), fontSize = 11.sp)
                DropdownSetting("允许上下同时输入",
                    listOf("Disabled" to "关闭", "Enabled" to "开启"),
                    padLayout.pceAllowUpDown
                ) { onLayoutChange(padLayout.copy {pceAllowUpDown = it}) }

                DropdownSetting("TurboTap(5人多人)",
                    listOf("Disabled" to "关闭", "Enabled" to "开启"),
                    padLayout.pceTurbotap
                ) { onLayoutChange(padLayout.copy {pceTurbotap = it}) }

                DropdownSetting("Memory Base 128",
                    listOf("Auto" to "自动", "Enabled" to "开启", "Disabled" to "关闭"),
                    padLayout.pceMb128
                ) { onLayoutChange(padLayout.copy {pceMb128 = it}) }

                Spacer(Modifier.size(4.dp))
                Text("PCE-CD", color = Color(0xFF8899AA), fontSize = 11.sp)
                DropdownSetting("CD BIOS",
                    listOf("Auto" to "自动",
                           "System Card 1" to "System Card 1",
                           "System Card 2" to "System Card 2",
                           "System Card 3" to "System Card 3 (推荐)",
                           "Game Express" to "Games Express"),
                    padLayout.pceCdromBios
                ) { onLayoutChange(padLayout.copy {pceCdromBios = it}) }

                Spacer(Modifier.size(12.dp))
                Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(Color(0x33FFFFFF)))
                Spacer(Modifier.size(8.dp))
                Text("PCE-CD BIOS 管理", color = Color(0xFFFFD66B), fontSize = 14.sp,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                Spacer(Modifier.size(4.dp))
                PceBiosImportSection()
            }
            GamePlatform.NDS -> {
                Text("NDS / DSi (melonDS) 专属设置", color = Color(0xFFFFD66B), fontSize = 13.sp,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                Spacer(Modifier.size(6.dp))
                Text("melonDS 0.9.3 内置 FreeBIOS，无需 BIOS 文件即可直接运行游戏。",
                    color = Color(0xFF8899AA), fontSize = 10.sp, lineHeight = 14.sp)
                Text("如需使用真实 BIOS，请将其放入系统目录（下方「NDS BIOS 管理」）。",
                    color = Color(0xFF8899AA), fontSize = 10.sp, lineHeight = 14.sp)

                Spacer(Modifier.size(6.dp))
                Text("系统", color = Color(0xFF8899AA), fontSize = 11.sp)
                DropdownSetting("主机模式",
                    listOf("DS" to "DS", "DSi" to "DSi"),
                    padLayout.ndsConsoleMode
                ) { onLayoutChange(padLayout.copy {ndsConsoleMode = it}) }

                Spacer(Modifier.size(4.dp))
                Text("屏幕布局", color = Color(0xFF8899AA), fontSize = 11.sp)
                DropdownSetting("屏幕排列",
                    listOf("Top/Bottom" to "上下排列(上屏在上)",
                           "Bottom/Top" to "下上排列(下屏在上)",
                           "Left/Right" to "左右排列(上屏在左)",
                           "Right/Left" to "右左排列(上屏在右)",
                           "Top Only" to "仅上方屏",
                           "Bottom Only" to "仅下方屏",
                           "Hybrid Top" to "混合(上屏大)",
                           "Hybrid Bottom" to "混合(下屏大)"),
                    padLayout.ndsScreenLayout
                ) { onLayoutChange(padLayout.copy {ndsScreenLayout = it}) }

                DropdownSetting("屏幕间距",
                    (0..20).map { it.toString() to "${it}px" },
                    padLayout.ndsScreenGap
                ) { onLayoutChange(padLayout.copy {ndsScreenGap = it}) }

                DropdownSetting("混合小屏模式",
                    listOf("Bottom" to "下方", "Top" to "上方", "Duplicate" to "复制双屏"),
                    padLayout.ndsHybridSmallScreen
                ) { onLayoutChange(padLayout.copy {ndsHybridSmallScreen = it}) }

                Spacer(Modifier.size(4.dp))
                Text("触摸/输入", color = Color(0xFF8899AA), fontSize = 11.sp)
                DropdownSetting("触摸模式",
                    listOf("Touch" to "触摸", "Mouse" to "鼠标", "Joystick" to "摇杆", "disabled" to "关闭"),
                    padLayout.ndsTouchMode
                ) { onLayoutChange(padLayout.copy {ndsTouchMode = it}) }

                Spacer(Modifier.size(4.dp))
                Text("DSi 模式", color = Color(0xFF8899AA), fontSize = 11.sp)
                DropdownSetting("DSi SD 卡",
                    listOf("disabled" to "关闭", "enabled" to "开启"),
                    padLayout.ndsDsiSdcard
                ) { onLayoutChange(padLayout.copy {ndsDsiSdcard = it}) }

                DropdownSetting("随机 MAC 地址",
                    listOf("disabled" to "关闭", "enabled" to "开启"),
                    padLayout.ndsRandomizeMac
                ) { onLayoutChange(padLayout.copy {ndsRandomizeMac = it}) }

                DropdownSetting("换屏模式",
                    listOf("Toggle" to "切换", "Hold" to "按住"),
                    padLayout.ndsSwapscreenMode
                ) { onLayoutChange(padLayout.copy {ndsSwapscreenMode = it}) }

                Spacer(Modifier.size(4.dp))
                Text("存档", color = Color(0xFF8899AA), fontSize = 11.sp)
                // 全局存档方式切换（所有核心通用，与 设置→存储 里的全局选项是同一份配置）：
                //   nesstation   → NesStation 统一存档目录：saves/<gameId>.sav(.srm)
                //                  每游戏独立文件，content:// URI 复制到 temp_rom.<ext>
                //                  也不会被覆盖。
                //   core_builtin → ROM 同目录同名存档（NDS = 官方 melonDS APK 的
                //                  <ROM名>.sav；其他核心 = <ROM名>.srm）。
                //                  ROM 目录不可写时自动回退到应用内部目录。
                DropdownSetting("存档方式(全局)",
                    listOf(
                        "nesstation" to "NesStation (统一存档目录)",
                        "core_builtin" to "ROM 同目录同名 (.sav/.srm)"
                    ),
                    padLayout.globalSaveMode
                ) { onLayoutChange(padLayout.copy {globalSaveMode = it}) }
                Text("对全部核心生效（NDS 写 .sav 兼容官方 melonDS，其他核心写 .srm）。切换后需重进游戏。",
                    color = Color(0xFF8899AA), fontSize = 10.sp, lineHeight = 14.sp)

                Spacer(Modifier.size(4.dp))
                Text("性能/音频", color = Color(0xFF8899AA), fontSize = 11.sp)
                // GL 硬件加速渲染器开关：官方 melonDS APK 默认开启（参考实现）。
                // 启用后 3D 渲染走 OpenGL 硬件加速，卡顿大幅降低；分辨率缩放
                // 仅在开启时生效。部分设备 GL 驱动异常时可切回软件渲染。
                // 注：切换后需重进游戏生效（核心在加载 ROM 时创建 GL 上下文）。
                DropdownSetting("3D 渲染器",
                    listOf("enabled" to "硬件加速 OpenGL (推荐)",
                           "disabled" to "软件渲染 (兼容模式)"),
                    padLayout.ndsOpenGlRenderer
                ) { onLayoutChange(padLayout.copy {ndsOpenGlRenderer = it}) }

                DropdownSetting("3D 渲染分辨率",
                    (1..8).map { it.toString() to "${it}x native (${256*it}x${192*it})" },
                    padLayout.ndsResolution
                ) { onLayoutChange(padLayout.copy {ndsResolution = it}) }

                DropdownSetting("JIT 编译器",
                    listOf("enabled" to "开启(加速)", "disabled" to "关闭(解释器)"),
                    padLayout.ndsJitEnable
                ) { onLayoutChange(padLayout.copy {ndsJitEnable = it}) }

                DropdownSetting("JIT 块大小",
                    (1..24).map { it.toString() to it.toString() },
                    padLayout.ndsJitBlockSize
                ) { onLayoutChange(padLayout.copy {ndsJitBlockSize = it}) }

                DropdownSetting("JIT 快速内存",
                    listOf("enabled" to "开启", "disabled" to "关闭"),
                    padLayout.ndsJitFastMemory
                ) { onLayoutChange(padLayout.copy {ndsJitFastMemory = it}) }

                DropdownSetting("JIT 分支优化",
                    listOf("enabled" to "开启", "disabled" to "关闭"),
                    padLayout.ndsJitBranchOptimisations
                ) { onLayoutChange(padLayout.copy {ndsJitBranchOptimisations = it}) }

                DropdownSetting("JIT 字面量优化",
                    listOf("enabled" to "开启", "disabled" to "关闭"),
                    padLayout.ndsJitLiteralOptimisations
                ) { onLayoutChange(padLayout.copy {ndsJitLiteralOptimisations = it}) }

                DropdownSetting("音频插值",
                    listOf("Cosine" to "余弦(高质量)", "Linear" to "线性(中等)",
                           "Sinc" to "Sinc(最高质量)", "None" to "无(低质量)"),
                    padLayout.ndsAudioInterpolation
                ) { onLayoutChange(padLayout.copy {ndsAudioInterpolation = it}) }

                DropdownSetting("音频比特率",
                    listOf("Automatic" to "自动", "10-bit" to "10-bit", "16-bit" to "16-bit"),
                    padLayout.ndsAudioBitrate
                ) { onLayoutChange(padLayout.copy {ndsAudioBitrate = it}) }

                DropdownSetting("麦克风输入",
                    listOf("Blow Noise" to "吹气声", "White Noise" to "白噪声"),
                    padLayout.ndsMicInput
                ) { onLayoutChange(padLayout.copy {ndsMicInput = it}) }

                DropdownSetting("语言",
                    listOf("Japanese" to "日本語", "English" to "English",
                           "French" to "Français", "German" to "Deutsch",
                           "Italian" to "Italiano", "Spanish" to "Español"),
                    padLayout.ndsLanguage
                ) { onLayoutChange(padLayout.copy {ndsLanguage = it}) }

                DropdownSetting("使用固件设置",
                    listOf("disabled" to "关闭(推荐)", "enabled" to "开启"),
                    padLayout.ndsUseFwSettings
                ) { onLayoutChange(padLayout.copy {ndsUseFwSettings = it}) }

                Spacer(Modifier.size(12.dp))
                Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(Color(0x33FFFFFF)))
                Spacer(Modifier.size(8.dp))
                Text("NDS BIOS 管理", color = Color(0xFFFFD66B), fontSize = 14.sp,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                Spacer(Modifier.size(4.dp))
                NdsBiosImportSection()
            }
            GamePlatform.PSX -> {
                Text("PSX/PlayStation 专属设置", color = Color(0xFFFFD66B), fontSize = 13.sp,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                Spacer(Modifier.size(6.dp))

                Text("BIOS/区域", color = Color(0xFF8899AA), fontSize = 11.sp)
                DropdownSetting("BIOS",
                    listOf("auto" to "自动", "HLE" to "HLE(无BIOS)",
                           "scph1000" to "SCPH-1000", "scph1001" to "SCPH-1001",
                           "scph1002" to "SCPH-1002", "scph5500" to "SCPH-5500",
                           "scph5501" to "SCPH-5501", "scph5502" to "SCPH-5502",
                           "psxonpsp660" to "PSP-660"),
                    padLayout.pscxBios
                ) { onLayoutChange(padLayout.copy {pscxBios = it}) }

                DropdownSetting("区域",
                    listOf("auto" to "自动", "ntsc" to "NTSC", "pal" to "PAL"),
                    padLayout.pscxRegion
                ) { onLayoutChange(padLayout.copy {pscxRegion = it}) }

                DropdownSetting("显示开机LOGO",
                    listOf("disabled" to "关闭", "enabled" to "开启"),
                    padLayout.pscxShowBootlogo
                ) { onLayoutChange(padLayout.copy {pscxShowBootlogo = it}) }

                Spacer(Modifier.size(4.dp))
                Text("CPU/性能", color = Color(0xFF8899AA), fontSize = 11.sp)
                DropdownSetting("DRC(JIT)",
                    listOf("enabled" to "开启(推荐)", "disabled" to "关闭"),
                    padLayout.pscxDrc
                ) { onLayoutChange(padLayout.copy {pscxDrc = it}) }

                DropdownSetting("CPU 时钟",
                    listOf("auto" to "自动", "30" to "30%", "50" to "50%", "75" to "75%",
                           "100" to "100%", "125" to "125%", "150" to "150%", "200" to "200%"),
                    padLayout.pscxClock
                ) { onLayoutChange(padLayout.copy {pscxClock = it}) }

                DropdownSetting("跳帧类型",
                    listOf("disabled" to "关闭", "auto" to "自动", "fixed" to "固定"),
                    padLayout.pscxFrameskipType
                ) { onLayoutChange(padLayout.copy {pscxFrameskipType = it}) }

                DropdownSetting("跳帧数",
                    listOf("0" to "0", "1" to "1", "2" to "2", "3" to "3",
                           "4" to "4", "5" to "5", "6" to "6", "7" to "7",
                           "8" to "8", "9" to "9", "10" to "10"),
                    padLayout.pscxFrameskip
                ) { onLayoutChange(padLayout.copy {pscxFrameskip = it}) }

                Spacer(Modifier.size(4.dp))
                Text("画面", color = Color(0xFF8899AA), fontSize = 11.sp)
                DropdownSetting("RGB32 输出",
                    listOf("disabled" to "关闭", "enabled" to "开启"),
                    padLayout.pscxRgb32
                ) { onLayoutChange(padLayout.copy {pscxRgb32 = it}) }

                DropdownSetting("缩放高分辨率",
                    listOf("disabled" to "关闭", "enabled" to "开启"),
                    padLayout.pscxScaleHires
                ) { onLayoutChange(padLayout.copy {pscxScaleHires = it}) }

                DropdownSetting("显示过扫描区域",
                    listOf("disabled" to "关闭", "enabled" to "开启"),
                    padLayout.pscxShowOverscan
                ) { onLayoutChange(padLayout.copy {pscxShowOverscan = it}) }

                DropdownSetting("GPU 奇偶行修正",
                    listOf("disabled" to "关闭", "enabled" to "开启"),
                    padLayout.pscxGpuOddEven
                ) { onLayoutChange(padLayout.copy {pscxGpuOddEven = it}) }

                DropdownSetting("抖动效果",
                    listOf("enabled" to "开启", "disabled" to "关闭"),
                    padLayout.pscxDithering
                ) { onLayoutChange(padLayout.copy {pscxDithering = it}) }

                Spacer(Modifier.size(4.dp))
                Text("手柄", color = Color(0xFF8899AA), fontSize = 11.sp)
                DropdownSetting("手柄1类型",
                    listOf("standard" to "标准", "analog" to "模拟", "negcon" to "力反馈", "gun" to "光枪"),
                    padLayout.pscxPad1Type
                ) { onLayoutChange(padLayout.copy {pscxPad1Type = it}) }

                DropdownSetting("手柄2类型",
                    listOf("standard" to "标准", "analog" to "模拟", "negcon" to "力反馈", "gun" to "光枪"),
                    padLayout.pscxPad2Type
                ) { onLayoutChange(padLayout.copy {pscxPad2Type = it}) }

                DropdownSetting("振动",
                    listOf("enabled" to "开启", "disabled" to "关闭"),
                    padLayout.pscxVibration
                ) { onLayoutChange(padLayout.copy {pscxVibration = it}) }

                DropdownSetting("模拟摇杆边界",
                    listOf("circle" to "圆形", "square" to "方形"),
                    padLayout.pscxAnalogAxis
                ) { onLayoutChange(padLayout.copy {pscxAnalogAxis = it}) }

                DropdownSetting("多手柄",
                    listOf("disabled" to "关闭", "port1" to "端口1", "port2" to "端口2", "both" to "全部"),
                    padLayout.pscxMultitap
                ) { onLayoutChange(padLayout.copy {pscxMultitap = it}) }

                Spacer(Modifier.size(4.dp))
                Text("音频", color = Color(0xFF8899AA), fontSize = 11.sp)
                DropdownSetting("SPU 插值",
                    listOf("simple" to "简单", "gaussian" to "高斯", "cubic" to "三次", "off" to "关闭"),
                    padLayout.pscxSpuInterp
                ) { onLayoutChange(padLayout.copy {pscxSpuInterp = it}) }

                DropdownSetting("SPU 混响",
                    listOf("enabled" to "开启", "disabled" to "关闭"),
                    padLayout.pscxSpuReverb
                ) { onLayoutChange(padLayout.copy {pscxSpuReverb = it}) }

                Spacer(Modifier.size(4.dp))
                Text("CD/记忆卡", color = Color(0xFF8899AA), fontSize = 11.sp)
                DropdownSetting("CD 预读扇区",
                    listOf("0" to "0", "6" to "6", "12" to "12(默认)", "18" to "18",
                           "24" to "24", "30" to "30"),
                    padLayout.pscxCdReadahead
                ) { onLayoutChange(padLayout.copy {pscxCdReadahead = it}) }

                DropdownSetting("记忆卡1",
                    listOf("libretro" to "Libretro", "shared" to "共享", "disabled" to "关闭"),
                    padLayout.pscxMemcard1
                ) { onLayoutChange(padLayout.copy {pscxMemcard1 = it}) }

                DropdownSetting("记忆卡2",
                    listOf("libretro" to "Libretro", "shared" to "共享", "disabled" to "关闭"),
                    padLayout.pscxMemcard2
                ) { onLayoutChange(padLayout.copy {pscxMemcard2 = it}) }

                Spacer(Modifier.size(12.dp))
                Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(Color(0x33FFFFFF)))
                Spacer(Modifier.size(8.dp))
                Text("PSX BIOS 管理", color = Color(0xFFFFD66B), fontSize = 14.sp,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                Spacer(Modifier.size(4.dp))
                PsxBiosImportSection()
            }
            GamePlatform.PS2 -> {
                Text("PS2 专属设置 (PCSX2 核心)", color = Color(0xFFFFD66B), fontSize = 13.sp,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                Spacer(Modifier.size(6.dp))

                Spacer(Modifier.size(4.dp))
                Text("画面", color = Color(0xFF8899AA), fontSize = 11.sp)
                // 渲染器：auto = 核心按设备支持选择（Vulkan 优先）；Vulkan/OpenGL
                // = GPU 硬件渲染；Software = CPU 软渲染。核心已编译 Vulkan
                // (USE_VULKAN=ON)，所有选项都可在游戏中途切换。
                DropdownSetting("渲染器",
                    listOf("auto" to "Auto (按设备选择)",
                           "vulkan" to "Vulkan (硬件加速)",
                           "opengl" to "OpenGL (硬件加速)",
                           "software" to "Software (软渲染)"),
                    padLayout.ps2Renderer
                ) { onLayoutChange(padLayout.copy {ps2Renderer = it}) }

                // 分辨率倍数：pcsx2_upscale_multiplier。1x = 原生 640x448；
                // 2x/3x/4x 逐级放大。硬件渲染下倍率越高越清晰但越吃性能 ——
                // 手机建议 1x~2x 起步。
                DropdownSetting("分辨率倍数",
                    listOf("1" to "1x (原生 640x448)", "2" to "2x (1280x896)",
                           "3" to "3x (1920x1344)", "4" to "4x (2560x1792 高配专用)"),
                    padLayout.ps2ResMulti
                ) { onLayoutChange(padLayout.copy {ps2ResMulti = it}) }

                DropdownSetting("双线性过滤",
                    listOf("enabled" to "开启 (PS2 原生平滑)", "disabled" to "关闭 (像素风)"),
                    padLayout.ps2Bilinear
                ) { onLayoutChange(padLayout.copy {ps2Bilinear = it}) }

                DropdownSetting("三线性过滤",
                    listOf("auto" to "自动 (默认)", "off" to "关闭",
                           "ps2" to "PS2 原生", "forced" to "强制"),
                    padLayout.ps2Trilinear
                ) { onLayoutChange(padLayout.copy {ps2Trilinear = it}) }

                DropdownSetting("各向异性过滤",
                    listOf("0" to "关闭", "2" to "2x", "4" to "4x", "8" to "8x", "16" to "16x"),
                    padLayout.ps2Anisotropic
                ) { onLayoutChange(padLayout.copy {ps2Anisotropic = it}) }

                DropdownSetting("去隔行模式",
                    listOf("0" to "自动 (默认)", "1" to "关闭", "4" to "Bob (TFF)",
                           "5" to "Bob (BFF)", "8" to "Adaptive (TFF)", "9" to "Adaptive (BFF)"),
                    padLayout.ps2Deinterlace
                ) { onLayoutChange(padLayout.copy {ps2Deinterlace = it}) }

                // ARMSX2 渲染增强 — 与设置面板同步
                DropdownSetting("电视滤镜 (TV Shader)",
                    listOf("0" to "无 (默认)", "1" to "扫描线 (Scanline)",
                           "2" to "对角线 (Diagonal)", "3" to "三角 (Triangular)",
                           "4" to "波浪 (Wave)", "5" to "Lottes CRT",
                           "6" to "4xRGSS", "7" to "NxAGSS"),
                    padLayout.ps2TvShader
                ) { onLayoutChange(padLayout.copy {ps2TvShader = it}) }

                DropdownSetting("画面增强 (ShadeBoost)",
                    listOf("disabled" to "关闭 (默认)", "enabled" to "开启 (亮度对比度饱和度伽马=50)"),
                    padLayout.ps2ShadeBoost
                ) { onLayoutChange(padLayout.copy {ps2ShadeBoost = it}) }

                DropdownSetting("半像素偏移 (Half-pixel)",
                    listOf("0" to "关闭 (默认)", "1" to "普通 (Normal)",
                           "2" to "特殊 (Special)", "3" to "特殊激进 (Special Aggressive)",
                           "4" to "原生 (Native)", "5" to "原生+纹理偏移"),
                    padLayout.ps2HalfPixelOffset
                ) { onLayoutChange(padLayout.copy {ps2HalfPixelOffset = it}) }

                DropdownSetting("纹理预加载",
                    listOf("0" to "关闭 (Off)", "1" to "部分 (Partial, 推荐)",
                           "2" to "完整 (Full, 最吃显存)"),
                    padLayout.ps2TexturePreloading
                ) { onLayoutChange(padLayout.copy {ps2TexturePreloading = it}) }

                DropdownSetting("画面比例",
                    listOf("auto" to "跟随全局画面缩放", "4:3" to "4:3 (锁定)", "16:9" to "16:9 (锁定)"),
                    padLayout.ps2AspectRatio
                ) { onLayoutChange(padLayout.copy {ps2AspectRatio = it}) }

                Spacer(Modifier.size(4.dp))
                Text("性能 (速度作弊)", color = Color(0xFF8899AA), fontSize = 11.sp)
                DropdownSetting("GPU 回读模式",
                    listOf("accurate" to "精准 (慢, 特效完整)",
                           "no_readbacks" to "禁用回读 (同步GS线程, 加速)",
                           "unsynchronized" to "非同步 (快)",
                           "async" to "异步 (实验性, 最快, 滞后1帧)",
                           "disabled" to "禁用/忽略 (最快, 部分特效异常)"),
                    padLayout.ps2HwDownloadMode
                ) { onLayoutChange(padLayout.copy {ps2HwDownloadMode = it}) }

                DropdownSetting("混色精度",
                    listOf("minimum" to "最低 (最快)", "basic" to "基础 (推荐)",
                           "medium" to "中等", "high" to "高", "full" to "全 (慢)",
                           "maximum" to "最高 (很慢)"),
                    padLayout.ps2BlendingAccuracy
                ) { onLayoutChange(padLayout.copy {ps2BlendingAccuracy = it}) }

                DropdownSetting("MTVU (多线程 VU1)",
                    listOf("enabled" to "开启 (推荐)", "disabled" to "关闭 (个别游戏)"),
                    padLayout.ps2Mtvu
                ) { onLayoutChange(padLayout.copy {ps2Mtvu = it}) }

                DropdownSetting("Instant VU1",
                    listOf("enabled" to "开启 (推荐)", "disabled" to "关闭"),
                    padLayout.ps2InstantVu1
                ) { onLayoutChange(padLayout.copy {ps2InstantVu1 = it}) }

                DropdownSetting("EE 周期率",
                    listOf("-3" to "50% (降频)", "-2" to "60% (降频)", "-1" to "75% (降频)",
                           "0" to "100% (默认)", "1" to "130% (超频)", "2" to "180% (超频)",
                           "3" to "300% (超频)"),
                    padLayout.ps2EeCycleRate
                ) { onLayoutChange(padLayout.copy {ps2EeCycleRate = it}) }

                DropdownSetting("快速启动 (跳过 BIOS 动画)",
                    listOf("enabled" to "开启 (推荐)", "disabled" to "关闭 (看 BIOS 画面)"),
                    padLayout.ps2FastBoot
                ) { onLayoutChange(padLayout.copy {ps2FastBoot = it}) }

                DropdownSetting("手柄震动",
                    listOf("enabled" to "开启", "disabled" to "关闭"),
                    padLayout.ps2Rumble
                ) { onLayoutChange(padLayout.copy {ps2Rumble = it}) }

                Spacer(Modifier.size(4.dp))
                Text("双摇杆", color = Color(0xFF8899AA), fontSize = 11.sp)
                Text(
                    "PS2 虚拟手柄自带左/右双摇杆（屏幕左下/右下），输出真实模拟轴，" +
                    "支持 L1/R1/L2/R2 肩键与 L3/R3。键位可在布局编辑器中自由拖动。",
                    color = Color(0xFF8899AA), fontSize = 11.sp, lineHeight = 15.sp
                )

                Spacer(Modifier.size(12.dp))
                Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(Color(0x33FFFFFF)))
                Spacer(Modifier.size(8.dp))
                Text("PS2 BIOS 管理", color = Color(0xFFFFD66B), fontSize = 14.sp,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                Spacer(Modifier.size(4.dp))
                Text(
                    "PCSX2 必须要真实 PS2 BIOS 才能启动游戏。下方可导入 BIOS 文件到 " +
                    "ps2/pcsx2/bios/ 目录；导入后下次进入游戏自动生效(快速启动默认跳过 " +
                    "BIOS 开机动画)。",
                    color = Color(0xFF8899AA), fontSize = 11.sp, lineHeight = 15.sp
                )
                Psx2BiosImportSection()
            }
            GamePlatform.JAVA -> {
                Text("J2ME 专属设置", color = Color(0xFFFFD66B), fontSize = 13.sp,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                Spacer(Modifier.size(6.dp))
                // 每游戏单独保存提示：此处的 J2ME 设置只对当前游戏生效
                if (javaIsPerGame) {
                    Text(
                        "✓ 本游戏使用专属设置（独立于其他 Java 游戏保存）",
                        color = Color(0xFF7BD88F), fontSize = 10.sp
                    )
                } else {
                    Text(
                        "当前修改将保存为本游戏的专属设置，不影响其他 Java 游戏",
                        color = Color(0xFF8899AA), fontSize = 10.sp
                    )
                }
                if (onResetJavaToGlobal != null && javaIsPerGame) {
                    androidx.compose.material3.TextButton(
                        onClick = onResetJavaToGlobal,
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp)
                    ) {
                        Text("恢复全局默认设置", color = Color(0xFFFF6B6B), fontSize = 11.sp)
                    }
                }
                Spacer(Modifier.size(4.dp))

                DropdownSetting("输入模式",
                    listOf(
                        "gamepad" to "手柄布局 (方向键 + ABXY)",
                        "phone" to "手机键盘 (数字 + 软键 + 方向)"
                    ),
                    padLayout.javaInputMode
                ) { onLayoutChange(padLayout.copy {javaInputMode = it}) }

                // 虚拟按键透明度（J2ME 专属，方向键等按键的可见度）
                Text("虚拟按键透明度", color = Color.White, fontSize = 12.sp,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Slider(
                        value = padLayout.javaOpacity.coerceIn(0.3f, 1f),
                        onValueChange = { v ->
                            onLayoutChange(padLayout.copy {javaOpacity = v.coerceIn(0.3f, 1f)})
                        },
                        valueRange = 0.3f..1f,
                        colors = SliderDefaults.colors(
                            thumbColor = Color(0xFFFFD66B),
                            activeTrackColor = Color(0xFFFFD66B),
                            inactiveTrackColor = Color(0xFF4A5568)
                        ),
                        modifier = Modifier.weight(1f)
                    )
                    Text("${(padLayout.javaOpacity.coerceIn(0.3f, 1f) * 100).toInt()}%",
                        color = Color(0xFFFFD66B), fontSize = 11.sp,
                        modifier = Modifier.padding(start = 8.dp))
                }

                DropdownSetting("屏幕缩放",
                    listOf(
                        "fit" to "适应屏幕 (保持比例，推荐)",
                        "stretch" to "全屏拉伸",
                        "center" to "原始分辨率 (居中)"
                    ),
                    padLayout.javaScaleType
                ) { onLayoutChange(padLayout.copy {javaScaleType = it}) }

                // 游戏逻辑分辨率（MIDlet 看到的屏幕尺寸）。旧版只能跳转
                // J2ME-Loader 原生设置修改，现在补全到游戏内设置：
                // default=跟随每游戏配置；auto=跟随设备屏幕；其余为强制分辨率。
                DropdownSetting("游戏分辨率",
                    listOf(
                        "default" to "默认 (跟随游戏配置)",
                        "auto" to "自动 (跟随设备屏幕)",
                        "128x128" to "128 × 128",
                        "176x208" to "176 × 208 (S60 经典)",
                        "176x220" to "176 × 220",
                        "208x208" to "208 × 208",
                        "240x320" to "240 × 320 (最常见)",
                        "240x400" to "240 × 400",
                        "320x240" to "320 × 240 (横屏)",
                        "360x640" to "360 × 640",
                        "480x800" to "480 × 800",
                        "640x360" to "640 × 360 (横屏)"
                    ),
                    padLayout.javaResolution
                ) { onLayoutChange(padLayout.copy {javaResolution = it}) }

                // 画面缩放比例：配合「屏幕缩放」使用。center(原始分辨率) 模式下
                // 可放大/缩小画面；fit/stretch 模式下 >100 会被核心自动截断。
                DropdownSetting("画面缩放比例",
                    listOf(
                        "25" to "25%", "50" to "50%", "75" to "75%",
                        "100" to "100% (默认)", "125" to "125%", "150" to "150%",
                        "175" to "175%", "200" to "200%", "300" to "300%", "400" to "400%"
                    ),
                    padLayout.javaScaleRatio
                ) { onLayoutChange(padLayout.copy {javaScaleRatio = it}) }

                DropdownSetting("帧率限制",
                    listOf(
                        "0" to "不限制 (默认)", "60" to "60 FPS", "50" to "50 FPS",
                        "40" to "40 FPS", "30" to "30 FPS", "25" to "25 FPS", "15" to "15 FPS"
                    ),
                    padLayout.javaFpsLimit
                ) { onLayoutChange(padLayout.copy {javaFpsLimit = it}) }

                SwitchSetting(
                    label = "显示 J2ME 帧数",
                    description = "由 MIDlet 画面内部绘制的实时帧率",
                    checked = padLayout.javaShowFps
                ) { onLayoutChange(padLayout.copy {javaShowFps = it}) }

                SwitchSetting(
                    label = "即时绘制模式",
                    description = "提升部分游戏的按键/触摸响应速度，少数游戏可能需要关闭",
                    checked = padLayout.javaImmediateMode
                ) { onLayoutChange(padLayout.copy {javaImmediateMode = it}) }

                SwitchSetting(
                    label = "触摸输入支持",
                    description = "部分游戏检测到触摸后会切换触屏 UI，关闭可强制键盘操作",
                    checked = padLayout.javaTouchInput
                ) { onLayoutChange(padLayout.copy {javaTouchInput = it}) }

                SwitchSetting(
                    label = "数字键兼作方向键",
                    description = "2/4/6/8/5 同时发送方向/确认键（真机行为），修复 123 数字键盘无法操作菜单的问题",
                    checked = padLayout.javaNumDualDispatch
                ) { onLayoutChange(padLayout.copy {javaNumDualDispatch = it}) }

                Spacer(Modifier.size(12.dp))
                Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(Color(0x33FFFFFF)))
                Spacer(Modifier.size(8.dp))
                Text("按键映射 (虚拟按键 → 手机按键)", color = Color(0xFFFFD66B), fontSize = 14.sp,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                Spacer(Modifier.size(4.dp))
                Text(
                    "把手柄上的每个按键映射到 J2ME 手机的任意按键。" +
                    "映射列表覆盖手机的全部按键：数字 0-9、*、#、方向、确认、" +
                    "软键、清除、挂机等。留默认即为出厂映射。",
                    color = Color(0xFF8899AA), fontSize = 10.sp, lineHeight = 14.sp
                )
                Spacer(Modifier.size(6.dp))
                JAVA_MAPPABLE_BUTTONS.forEach { (buttonId, buttonLabel) ->
                    val currentCode = javaButtonKeyMapGet(padLayout.javaButtonKeyMap, buttonId)
                    DropdownSetting(buttonLabel, JAVA_PHONE_KEY_OPTIONS, currentCode.toString()) { code ->
                        val newMap = javaButtonKeyMapSet(padLayout.javaButtonKeyMap, buttonId, code.toIntOrNull() ?: 0)
                        onLayoutChange(padLayout.copy {javaButtonKeyMap = newMap})
                    }
                }
            }
        }

        Spacer(Modifier.size(8.dp))
        Text("修改后即时生效。设置与主界面设置同步。", color = Color(0xFF8899AA), fontSize = 11.sp)
    }
}

// FDS BIOS status data
private data class FdsBiosStatus(val exists: Boolean, val valid: Boolean, val message: String = "")

// Check if disksys.rom exists and is valid in the app's filesDir
private fun checkFdsBiosStatus(context: android.content.Context): FdsBiosStatus {
    val biosFile = java.io.File(context.filesDir, "disksys.rom")
    if (!biosFile.exists()) {
        return FdsBiosStatus(exists = false, valid = false, message = "未导入")
    }
    val size = biosFile.length()
    if (size != 8192L) {
        return FdsBiosStatus(exists = true, valid = false,
            message = "文件大小错误: ${size}字节 (需要8192字节)")
    }
    // Validate reset vector points into BIOS region 0xE000-0xFFFF.
    // A corrupted/fake BIOS has a reset vector pointing to 0x00xx (RAM),
    // causing a permanent gray screen.
    if (!isValidFdsBiosContent(biosFile)) {
        return FdsBiosStatus(exists = true, valid = false,
            message = "BIOS无效 (复位向量错误)")
    }
    return FdsBiosStatus(exists = true, valid = true, message = "已导入 ✓")
}

// Validate FDS BIOS content: reset vector (offset 0x1FFC-0x1FFD) must
// point into 0xE000-0xFFFF (the BIOS region).
private fun isValidFdsBiosContent(file: java.io.File): Boolean {
    try {
        file.inputStream().use { input ->
            val bytes = input.readBytes()
            if (bytes.size != 8192) return false
            val resetLo = bytes[0x1FFC].toInt() and 0xFF
            val resetHi = bytes[0x1FFD].toInt() and 0xFF
            val resetVec = (resetHi shl 8) or resetLo
            if (resetVec < 0xE000 || resetVec > 0xFFFF) return false
        }
    } catch (_: Exception) {
        return false
    }
    return true
}

// Import FDS BIOS from a content URI to filesDir/disksys.rom
private fun importFdsBios(context: android.content.Context, uri: android.net.Uri): String {
    return try {
        val biosFile = java.io.File(context.filesDir, "disksys.rom")
        context.contentResolver.openInputStream(uri)?.use { input ->
            biosFile.outputStream().use { output -> input.copyTo(output) }
        } ?: return "导入失败: 无法读取文件"

        val size = biosFile.length()
        if (size != 8192L) {
            return "导入失败: 文件大小${size}字节不正确 (需要8192字节)"
        }

        if (!isValidFdsBiosContent(biosFile)) {
            biosFile.delete()
            return "导入失败: BIOS无效 (复位向量不在0xE000-0xFFFF范围)"
        }
        "导入成功! 请重新加载FDS游戏"
    } catch (e: Exception) {
        "导入失败: ${e.message}"
    }
}

@Composable
private fun FdsBiosImportSection(
    biosStatus: FdsBiosStatus,
    onImport: (android.net.Uri) -> Unit
) {
    val context = LocalContext.current
    val biosPickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            try {
                context.contentResolver.takePersistableUriPermission(
                    uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (_: Exception) { }
            onImport(uri)
        }
    }

    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Status indicator
        val statusColor = if (biosStatus.valid) Color(0xFF4CAF50) else Color(0xFFFF5252)
        Text("●", color = statusColor, fontSize = 14.sp)
        Spacer(Modifier.size(6.dp))
        Text(
            biosStatus.message,
            color = if (biosStatus.valid) Color(0xFF88DD88) else Color(0xFFFFAAAA),
            fontSize = 12.sp,
            modifier = Modifier.weight(1f)
        )
        // Import button
        Text(
            "导入BIOS",
            color = Color(0xFFFFD66B),
            fontSize = 13.sp,
            modifier = Modifier
                .clickable { biosPickerLauncher.launch(arrayOf("*/*")) }
                .padding(8.dp)
        )
    }
}

// ---------------------------------------------------------------------------
// FBNeo (Arcade) BIOS management — lets the user import BIOS zip files
// (neogeo.zip, pgm.zip, etc.) into <filesDir>/fbneo/.
// ---------------------------------------------------------------------------
@Composable
private fun ArcadeBiosImportSection() {
    val context = LocalContext.current
    val biosDir = remember { java.io.File(context.filesDir, "fbneo").apply { mkdirs() } }
    var statusText by remember { mutableStateOf("") }
    var refreshKey by remember { mutableStateOf(0) }

    // Refresh BIOS status on first composition and after each import.
    LaunchedEffect(refreshKey) {
        statusText = buildString {
            val known = listOf(
                "neogeo.zip" to "NeoGeo",
                "pgm.zip" to "PGM",
                "neocdz.zip" to "NeoGeo CD",
                "cvs2.zip" to "Capcom VS SNK 2",
                "cps1.zip" to "CPS1",
                "cps2.zip" to "CPS2",
                "stvbios.zip" to "ST-V"
            )
            var found = 0
            for ((name, label) in known) {
                val f = java.io.File(biosDir, name)
                if (f.exists() && f.length() > 0) {
                    append("✓ $label ($name, ${f.length() / 1024}KB)\n")
                    found++
                }
            }
            if (found == 0) {
                append("未检测到任何BIOS文件\n")
            }
            append("\n目录: ${biosDir.absolutePath}")
        }
    }

    val biosPickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            try {
                context.contentResolver.takePersistableUriPermission(
                    uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (_: Exception) { }
            // Determine destination filename from URI's display name.
            val name = uri.lastPathSegment?.substringAfterLast('/')?.substringAfterLast('%')
                ?: "bios.zip"
            val safeName = if (name.endsWith(".zip", ignoreCase = true)) name else "$name.zip"
            val dest = java.io.File(biosDir, safeName)
            try {
                context.contentResolver.openInputStream(uri)?.use { input ->
                    dest.outputStream().use { output -> input.copyTo(output) }
                }
                refreshKey++
            } catch (e: Exception) {
                statusText = "导入失败: ${e.message}"
            }
        }
    }

    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(
            statusText,
            color = Color(0xFF88DD88),
            fontSize = 11.sp,
            lineHeight = 14.sp,
            modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp)
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "导入BIOS zip",
                color = Color(0xFFFFD66B),
                fontSize = 13.sp,
                modifier = Modifier
                    .clickable { biosPickerLauncher.launch(arrayOf("*/*")) }
                    .padding(8.dp)
            )
            Spacer(Modifier.size(12.dp))
            Text(
                "刷新",
                color = Color(0xFF8899AA),
                fontSize = 13.sp,
                modifier = Modifier
                    .clickable { refreshKey++ }
                    .padding(8.dp)
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Genesis-Plus-GX (Mega-CD) BIOS management — lets the user import
// BIOS files into <filesDir>/genesis/. Supports BOTH .bin and .zip:
//   - .bin  → saved directly as bios_CD_E.bin / bios_CD_J.bin / bios_CD_U.bin
//             (region auto-detected from original filename: _E/_J/_U or 欧区/日区/美区)
//   - .zip  → extracted: the .bin file inside is saved as bios_CD_<region>.bin
//             (the .zip itself is also kept for compatibility with cores that
//              accept .zip directly)
//
// The genplus core logs "BIOS should be located at: .../bios_CD_E.bin" — i.e.
// it expects a .bin file. Previously we saved as .zip only, which is why MD-CD
// games showed a black screen even after the user imported the BIOS.
// ---------------------------------------------------------------------------
@Composable
private fun GenesisBiosImportSection() {
    val context = LocalContext.current
    val biosDir = remember { java.io.File(context.filesDir, "genesis").apply { mkdirs() } }
    var statusText by remember { mutableStateOf("") }
    var refreshKey by remember { mutableStateOf(0) }

    // Region detection from BIOS filename.
    // Accepted cues: bios_CD_E / bios_CD_J / bios_CD_U, or 1 / 2 / 3 suffix,
    // or EU/JP/US, or 欧区/日区/美区. Returns 'E'|'J'|'U'|null.
    fun detectRegion(name: String): Char? {
        val n = name.uppercase()
        return when {
            n.contains("_E") || n.contains("EU") || n.contains("PAL") ||
            n.contains("欧") || n.contains("欧洲") -> 'E'
            n.contains("_J") || n.contains("JP") || n.contains("NTSC_J") ||
            n.contains("日") || n.contains("日本") -> 'J'
            n.contains("_U") || n.contains("US") || n.contains("USA") ||
            n.contains("NTSC_U") || n.contains("美") || n.contains("美国") -> 'U'
            else -> null
        }
    }

    LaunchedEffect(refreshKey) {
        statusText = buildString {
            // Check BOTH .bin (preferred by genplus core) and .zip (legacy)
            val known = listOf(
                "bios_CD_E.bin" to "Mega-CD (欧洲)",
                "bios_CD_J.bin" to "Mega-CD (日本)",
                "bios_CD_U.bin" to "SEGA-CD (美国)"
            )
            var found = 0
            for ((name, label) in known) {
                val binFile = java.io.File(biosDir, name)
                val zipFile = java.io.File(biosDir, name.replace(".bin", ".zip"))
                val binOk = binFile.exists() && binFile.length() > 0
                val zipOk = zipFile.exists() && zipFile.length() > 0
                if (binOk) {
                    append("✓ $label ($name, ${binFile.length() / 1024}KB)\n")
                    found++
                } else if (zipOk) {
                    // === 自动解压修复 ===
                    // 之前 ensureGenesisBios 或旧版导入只复制了 zip 没解压 .bin，
                    // 这里检测到这种情况时自动解压一次，避免用户看到"建议重新导入"。
                    val autoExtracted = try {
                        java.util.zip.ZipInputStream(zipFile.inputStream().buffered()).use { zin ->
                            var ok = false
                            while (true) {
                                val entry = zin.nextEntry ?: break
                                val entryName = entry.name.lowercase()
                                if (entryName.endsWith(".bin") || entryName.endsWith(".rom")) {
                                    binFile.outputStream().buffered().use { out ->
                                        val buf = ByteArray(8192)
                                        while (true) {
                                            val n = zin.read(buf)
                                            if (n <= 0) break
                                            out.write(buf, 0, n)
                                        }
                                    }
                                    ok = true
                                    break
                                }
                                zin.closeEntry()
                            }
                            ok
                        }
                    } catch (_: Exception) { false }

                    if (autoExtracted && binFile.exists() && binFile.length() > 0) {
                        append("✓ $label ($name, ${binFile.length() / 1024}KB, 自动解压自 ${zipFile.name})\n")
                        found++
                    } else {
                        append("⚠ $label (有.zip但无.bin — 建议重新导入以自动解压)\n")
                    }
                }
            }
            if (found == 0) {
                append("未检测到Mega-CD BIOS文件\n")
                append("卡带游戏(MD/SMS/GG/SG)无需BIOS, 仅Mega-CD游戏需要。\n")
                append("支持导入 .bin 或 .zip 文件, 文件名含_E/_J/_U 或 欧/日/美 自动识别区域。\n")
            }
            append("\n目录: ${biosDir.absolutePath}")
        }
    }

    val biosPickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            try {
                context.contentResolver.takePersistableUriPermission(
                    uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (_: Exception) { }
            // Query original display name (handles SAF percent-encoded URIs)
            var origName = ""
            try {
                context.contentResolver.query(uri, null, null, null, null)?.use { c ->
                    val idx = c.getColumnIndex(
                        android.provider.DocumentsContract.Document.COLUMN_DISPLAY_NAME
                    )
                    if (idx >= 0 && c.moveToFirst()) {
                        val n = c.getString(idx)
                        if (!n.isNullOrBlank()) origName = n
                    }
                }
            } catch (_: Exception) { }
            if (origName.isBlank()) {
                origName = uri.lastPathSegment?.let { android.net.Uri.decode(it) }
                    ?.substringAfterLast('/')?.substringAfterLast(':') ?: "bios.bin"
            }

            val region = detectRegion(origName) ?: 'E'  // default to EU if unknown
            val isZip = origName.endsWith(".zip", ignoreCase = true)
            val msg: String = try {
                // Always copy the original file first (preserves user's input format)
                val origExt = if (isZip) ".zip" else ".bin"
                val origDestName = "bios_CD_$region$origExt"
                val origDest = java.io.File(biosDir, origDestName)
                context.contentResolver.openInputStream(uri)?.use { input ->
                    origDest.outputStream().use { output -> input.copyTo(output) }
                }

                // If it's a zip, extract the .bin file inside and save as
                // bios_CD_<region>.bin (this is what the genplus core looks for).
                if (isZip) {
                    try {
                        val zipIn = java.util.zip.ZipInputStream(origDest.inputStream())
                        var extracted = false
                        while (true) {
                            val entry = zipIn.nextEntry ?: break
                            val entryName = entry.name.lowercase()
                            if (entryName.endsWith(".bin") || entryName.endsWith(".rom")) {
                                val binDest = java.io.File(biosDir, "bios_CD_$region.bin")
                                binDest.outputStream().use { out ->
                                    val buf = ByteArray(8192)
                                    while (true) {
                                        val n = zipIn.read(buf)
                                        if (n <= 0) break
                                        out.write(buf, 0, n)
                                    }
                                }
                                extracted = true
                                break
                            }
                            zipIn.closeEntry()
                        }
                        zipIn.close()
                        if (extracted) {
                            "已导入 BIOS (区域=$region): ${origDest.name} + 已解压 bios_CD_$region.bin"
                        } else {
                            "已导入 ${origDest.name}, 但 zip 中未找到 .bin 文件 — 请确认 zip 内含 BIOS .bin"
                        }
                    } catch (e: Exception) {
                        "已导入 ${origDest.name}, 但解压 .bin 失败: ${e.message}"
                    }
                } else {
                    // .bin file: already saved with correct name. Done.
                    "已导入 BIOS (区域=$region): ${origDest.name} (${origDest.length() / 1024}KB)"
                }
            } catch (e: Exception) {
                "导入失败: ${e.message}"
            }
            refreshKey++
            statusText = msg
        }
    }

    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(
            statusText,
            color = Color(0xFF88DD88),
            fontSize = 11.sp,
            lineHeight = 14.sp,
            modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp)
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "导入BIOS (.bin 或 .zip)",
                color = Color(0xFFFFD66B),
                fontSize = 13.sp,
                modifier = Modifier
                    .clickable { biosPickerLauncher.launch(arrayOf("*/*")) }
                    .padding(8.dp)
            )
            Spacer(Modifier.size(12.dp))
            Text(
                "刷新",
                color = Color(0xFF8899AA),
                fontSize = 13.sp,
                modifier = Modifier
                    .clickable { refreshKey++ }
                    .padding(8.dp)
            )
        }
    }
}

/**
 * PCE-CD BIOS import section for the Geargrafx core.
 *
 * PCE-CD games require a "System Card" BIOS in <filesDir>/pce/. Geargrafx
 * looks for these by filename:
 *   syscard1.pce     — System Card 1 (rarely used)
 *   syscard2.pce     — System Card 2 (rarely used)
 *   syscard3.pce     — System Card 3 / Arcade Card Pro (RECOMMENDED —
 *                      most games require this; auto-selected when
 *                      geargrafx_cdrom_bios = "Auto")
 *   gexpress.pce     — Games Express BIOS (required for a handful of
 *                      adult games; otherwise unused)
 *
 * NOTE: the core looks for "gexpress.pce", NOT "gameexpress.pce".
 *
 * This section lets the user import a .pce file from SAF and rename it
 * to the canonical name based on the source filename or a manual pick.
 */
@Composable
private fun PceBiosImportSection() {
    val context = LocalContext.current
    val biosDir = remember { java.io.File(context.filesDir, "pce").apply { mkdirs() } }
    var statusText by remember { mutableStateOf("") }
    var refreshKey by remember { mutableStateOf(0) }

    // Map source filename → canonical syscardN.pce / gexpress.pce
    fun detectCanonicalName(name: String): String? {
        val n = name.lowercase()
        return when {
            n.contains("syscard3") || n.contains("system_card_3") ||
            n.contains("system card 3") || n.contains("sc3") ||
            n.contains("arcade card") || n.contains("accard") -> "syscard3.pce"
            n.contains("syscard2") || n.contains("system_card_2") ||
            n.contains("system card 2") || n.contains("sc2") -> "syscard2.pce"
            n.contains("syscard1") || n.contains("system_card_1") ||
            n.contains("system card 1") || n.contains("sc1") -> "syscard1.pce"
            n.contains("gexpress") || n.contains("gameexpress") ||
            n.contains("game_express") || n.contains("game express") ||
            n.contains("games express") || n.contains("ge.pce") -> "gexpress.pce"
            else -> null
        }
    }

    LaunchedEffect(refreshKey) {
        statusText = buildString {
            val known = listOf(
                "syscard1.pce" to "System Card 1",
                "syscard2.pce" to "System Card 2",
                "syscard3.pce" to "System Card 3 (推荐)",
                "gexpress.pce" to "Games Express"
            )
            var found = 0
            for ((name, label) in known) {
                val f = java.io.File(biosDir, name)
                if (f.exists() && f.length() > 0) {
                    append("✓ $label ($name, ${f.length() / 1024}KB)\n")
                    found++
                }
            }
            if (found == 0) {
                append("未检测到PCE-CD BIOS文件\n")
                append("卡带游戏(.pce/.sgx)和HES(.hes)无需BIOS, 仅PCE-CD需要。\n")
                append("推荐导入 syscard3.pce (System Card 3 / Arcade Card Pro)。\n")
                append("导入时文件名含 syscard1/2/3 或 gexpress 自动识别。\n")
            }
            append("\n目录: ${biosDir.absolutePath}")
        }
    }

    val biosPickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            try {
                context.contentResolver.takePersistableUriPermission(
                    uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (_: Exception) { }
            var origName = ""
            try {
                context.contentResolver.query(uri, null, null, null, null)?.use { c ->
                    val idx = c.getColumnIndex(
                        android.provider.DocumentsContract.Document.COLUMN_DISPLAY_NAME
                    )
                    if (idx >= 0 && c.moveToFirst()) {
                        val n = c.getString(idx)
                        if (!n.isNullOrBlank()) origName = n
                    }
                }
            } catch (_: Exception) { }
            if (origName.isBlank()) {
                origName = uri.lastPathSegment?.let { android.net.Uri.decode(it) }
                    ?.substringAfterLast('/') ?: "syscard3.pce"
            }
            val canonical = detectCanonicalName(origName) ?: "syscard3.pce"
            try {
                context.contentResolver.openInputStream(uri)?.use { input ->
                    java.io.File(biosDir, canonical).outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
                refreshKey++
            } catch (e: Exception) {
                android.util.Log.e("PceBiosImport", "Copy failed", e)
            }
        }
    }

    Text(statusText, color = Color(0xFF8899AA), fontSize = 10.sp, lineHeight = 14.sp)
    Spacer(Modifier.size(6.dp))
    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        androidx.compose.material3.Button(onClick = { biosPickerLauncher.launch(arrayOf("*/*")) }) {
            Text("导入 PCE-CD BIOS (.pce)")
        }
        Spacer(Modifier.size(8.dp))
        Icon(
            imageVector = androidx.compose.material.icons.Icons.Rounded.Refresh,
            contentDescription = "刷新",
            tint = Color(0xFF8899AA),
            modifier = Modifier.size(20.dp).clickable { refreshKey++ }.padding(4.dp)
        )
    }
}

/**
 * NDS / DSi BIOS import section for the melonDS core.
 *
 * The prebuilt core is melonDS 0.9.3. It uses FreeBIOS (built-in ARM7/ARM9
 * BIOS replacement + generated firmware) whenever no real BIOS files are
 * found — there is NO "melonds_use_fw_bios" option in this version.
 *
 * If real BIOS files exist in <filesDir>/nds/ (the system directory), the
 * core loads them instead of FreeBIOS for better accuracy:
 *      bios7.bin      — ARM7 BIOS (required for NDS)
 *      bios9.bin      — ARM9 BIOS (required for NDS)
 *      firmware.bin   — DS firmware (required for NDS)
 *      dsi_arm7.bin   — (DSi only) ARM7 binary
 *      dsi_bios7.bin  — (DSi only) ARM7 BIOS
 *      dsi_bios9.bin  — (DSi only) ARM9 BIOS
 *      dsi_firmware.bin — (DSi only) DSi firmware
 *      dsi_nand.bin   — (DSi only) DSi NAND image
 *
 * These BIOS files have copyright and cannot be bundled with the app.
 * Users provide them via this import UI.
 */
@Composable
private fun NdsBiosImportSection() {
    val context = LocalContext.current
    val biosDir = remember { java.io.File(context.filesDir, "nds").apply { mkdirs() } }
    var statusText by remember { mutableStateOf("") }
    var refreshKey by remember { mutableStateOf(0) }

    LaunchedEffect(refreshKey) {
        // === Auto-extract from assets/nds/ if present ===
        // Mirrors ensureNdsBios() in NesApp — re-runs here so the user can
        // drop BIOS files into assets/nds/ between builds and have them
        // picked up without a full reinstall (during dev).
        val known = listOf(
            "bios7.bin" to "ARM7 BIOS (NDS 必需)",
            "bios9.bin" to "ARM9 BIOS (NDS 必需)",
            "firmware.bin" to "DS Firmware (NDS 必需)",
            "dsi_arm7.bin" to "DSi ARM7 (DSi 模式)",
            "dsi_bios7.bin" to "DSi ARM7 BIOS (DSi 模式)",
            "dsi_bios9.bin" to "DSi ARM9 BIOS (DSi 模式)",
            "dsi_firmware.bin" to "DSi Firmware (DSi 模式)",
            "dsi_nand.bin" to "DSi NAND (DSi 模式)"
        )
        for ((name, _) in known) {
            val dest = java.io.File(biosDir, name)
            if (dest.exists() && dest.length() > 0) continue
            try {
                context.assets.open("nds/$name").use { input ->
                    dest.outputStream().use { output -> input.copyTo(output) }
                }
            } catch (_: Exception) { /* not bundled */ }
        }

        statusText = buildString {
            var found = 0
            for ((name, label) in known) {
                val f = java.io.File(biosDir, name)
                if (f.exists() && f.length() > 0) {
                    append("✓ $label ($name, ${f.length() / 1024}KB)\n")
                    found++
                }
            }
            if (found < 3) {
                append("\nℹ 内置 FreeBIOS 已开启，无需 BIOS 文件即可运行 NDS 游戏\n")
                append("如需使用真实 BIOS 以获得更好兼容性，请导入 bios7.bin + bios9.bin + firmware.bin\n")
                append("并在设置中关闭「内置 BIOS」选项\n")
            }
            append("\n目录: ${biosDir.absolutePath}")
        }
    }

    val biosPickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            try {
                context.contentResolver.takePersistableUriPermission(
                    uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (_: Exception) { }
            var origName = ""
            try {
                context.contentResolver.query(uri, null, null, null, null)?.use { c ->
                    val idx = c.getColumnIndex(android.provider.DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                    if (idx >= 0 && c.moveToFirst()) {
                        val n = c.getString(idx)
                        if (!n.isNullOrBlank()) origName = n
                    }
                }
            } catch (_: Exception) { }
            if (origName.isBlank()) {
                origName = uri.lastPathSegment?.let { android.net.Uri.decode(it) }
                    ?.substringAfterLast('/')?.substringAfterLast(':') ?: "bios.bin"
            }

            val msg: String = try {
                val dest = java.io.File(biosDir, origName)
                context.contentResolver.openInputStream(uri)?.use { input ->
                    dest.outputStream().use { output -> input.copyTo(output) }
                }
                "已导入 BIOS: ${dest.name} (${dest.length() / 1024}KB)"
            } catch (e: Exception) {
                "导入失败: ${e.message}"
            }
            refreshKey++
            statusText = msg
        }
    }

    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(
            statusText,
            color = Color(0xFF88DD88),
            fontSize = 11.sp,
            lineHeight = 14.sp,
            modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp)
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "导入 BIOS (.bin)",
                color = Color(0xFFFFD66B),
                fontSize = 13.sp,
                modifier = Modifier
                    .clickable { biosPickerLauncher.launch(arrayOf("*/*")) }
                    .padding(8.dp)
            )
            Spacer(Modifier.size(12.dp))
            Text(
                "刷新",
                color = Color(0xFF8899AA),
                fontSize = 13.sp,
                modifier = Modifier
                    .clickable { refreshKey++ }
                    .padding(8.dp)
            )
        }
    }
}

/**
 * PSX BIOS import section for the PCSX-ReARMed core.
 *
 * PCSX-ReARMed can use either:
 *   1. HLE BIOS (built-in, no file needed) — less compatible but works
 *   2. Real BIOS files in <filesDir>/psx/:
 *      scph1000.bin  — Japanese BIOS
 *      scph1001.bin  — American BIOS
 *      scph1002.bin  — European BIOS
 *      scph5500.bin  — Japanese (newer)
 *      scph5501.bin  — American (newer)
 *      scph5502.bin  — European (newer)
 *      psxonpsp660.bin — PSP-derived (no copyright issues in some regions)
 *
 * The "pcsx_rearmed_bios" core option selects which BIOS to use:
 *   "auto" — auto-detect by region
 *   "HLE"  — use HLE BIOS (no file)
 *   "scph1001" / "scph1002" / ... — use specific BIOS file
 *
 * This section imports BIOS files and shows which are present. The BIOS
 * selection dropdown is in the CoreSettingsPanel (already added).
 */
@Composable
private fun PsxBiosImportSection() {
    val context = LocalContext.current
    val biosDir = remember { java.io.File(context.filesDir, "psx").apply { mkdirs() } }
    var statusText by remember { mutableStateOf("") }
    var refreshKey by remember { mutableStateOf(0) }

    LaunchedEffect(refreshKey) {
        // === Auto-extract from assets/psx/ if present ===
        val known = listOf(
            "scph1000.bin" to "SCPH-1000 (日)",
            "scph1001.bin" to "SCPH-1001 (美)",
            "scph1002.bin" to "SCPH-1002 (欧)",
            "scph5500.bin" to "SCPH-5500 (日)",
            "scph5501.bin" to "SCPH-5501 (美)",
            "scph5502.bin" to "SCPH-5502 (欧)",
            "psxonpsp660.bin" to "PSP-660 (免版权)"
        )
        for ((name, _) in known) {
            val dest = java.io.File(biosDir, name)
            if (dest.exists() && dest.length() > 0) continue
            try {
                context.assets.open("psx/$name").use { input ->
                    dest.outputStream().use { output -> input.copyTo(output) }
                }
            } catch (_: Exception) { /* not bundled */ }
        }

        statusText = buildString {
            var found = 0
            for ((name, label) in known) {
                val f = java.io.File(biosDir, name)
                if (f.exists() && f.length() > 0) {
                    append("✓ $label ($name, ${f.length() / 1024}KB)\n")
                    found++
                }
            }
            if (found == 0) {
                append("未检测到 PSX BIOS 文件\n")
                append("可在设置 → PSX → BIOS 选 'HLE(无 BIOS)' 免 BIOS 运行\n")
                append("或导入 scph1001.bin(美) / scph1002.bin(欧) / scph1000.bin(日)\n")
            }
            append("\n目录: ${biosDir.absolutePath}")
        }
    }

    val biosPickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            try {
                context.contentResolver.takePersistableUriPermission(
                    uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (_: Exception) { }
            var origName = ""
            try {
                context.contentResolver.query(uri, null, null, null, null)?.use { c ->
                    val idx = c.getColumnIndex(android.provider.DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                    if (idx >= 0 && c.moveToFirst()) {
                        val n = c.getString(idx)
                        if (!n.isNullOrBlank()) origName = n
                    }
                }
            } catch (_: Exception) { }
            if (origName.isBlank()) {
                origName = uri.lastPathSegment?.let { android.net.Uri.decode(it) }
                    ?.substringAfterLast('/')?.substringAfterLast(':') ?: "scph1001.bin"
            }

            val msg: String = try {
                val dest = java.io.File(biosDir, origName)
                context.contentResolver.openInputStream(uri)?.use { input ->
                    dest.outputStream().use { output -> input.copyTo(output) }
                }
                "已导入 BIOS: ${dest.name} (${dest.length() / 1024}KB)"
            } catch (e: Exception) {
                "导入失败: ${e.message}"
            }
            refreshKey++
            statusText = msg
        }
    }

    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(
            statusText,
            color = Color(0xFF88DD88),
            fontSize = 11.sp,
            lineHeight = 14.sp,
            modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp)
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "导入 BIOS (.bin)",
                color = Color(0xFFFFD66B),
                fontSize = 13.sp,
                modifier = Modifier
                    .clickable { biosPickerLauncher.launch(arrayOf("*/*")) }
                    .padding(8.dp)
            )
            Spacer(Modifier.size(12.dp))
            Text(
                "刷新",
                color = Color(0xFF8899AA),
                fontSize = 13.sp,
                modifier = Modifier
                    .clickable { refreshKey++ }
                    .padding(8.dp)
            )
        }
    }
}

/**
 * PS2 (PCEE2 / PCSX2) BIOS 导入区。PCSX2 必须有真实 PS2 BIOS 才能启动。
 * BIOS 文件(如 scph39001.bin / scph10000.bin)需放到
 * `<filesDir>/ps2/pcsx2/bios/` (PCEE2 核心从 <systemDir>/pcsx2/bios 读取,
 * systemDir = <filesDir>/ps2)。本组件显示目录内已有 BIOS 状态并提供导入按钮。
 */
@Composable
fun Psx2BiosImportSection() {
    val context = LocalContext.current
    // PCEE2 期望 <systemDir>/pcsx2/bios/，其中 systemDir = <filesDir>/ps2。
    val biosDir = remember {
        java.io.File(java.io.File(context.filesDir, "ps2"), "pcsx2/bios").apply { mkdirs() }
    }
    var statusText by remember { mutableStateOf("") }
    var refreshKey by remember { mutableStateOf(0) }

    LaunchedEffect(refreshKey) {
        statusText = buildString {
            val files = biosDir.listFiles()
                ?.filter { it.isFile && it.length() > 0 }
                ?.sortedBy { it.name } ?: emptyList()
            if (files.isEmpty()) {
                append("未检测到 PS2 BIOS 文件\n")
                append("PCSX2 必须要有真实 PS2 BIOS 才能启动游戏。\n")
                append("请导入 scph39001.bin / scph10000.bin / scph70004.bin 等任一 BIOS\n")
            } else {
                for (f in files) {
                    append("✓ ${f.name} (${f.length() / 1024}KB)\n")
                }
            }
            append("\n目录: ${biosDir.absolutePath}")
        }
    }

    val biosPickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            try {
                context.contentResolver.takePersistableUriPermission(
                    uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (_: Exception) { }
            var origName = ""
            try {
                context.contentResolver.query(uri, null, null, null, null)?.use { c ->
                    val idx = c.getColumnIndex(android.provider.DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                    if (idx >= 0 && c.moveToFirst()) {
                        val n = c.getString(idx)
                        if (!n.isNullOrBlank()) origName = n
                    }
                }
            } catch (_: Exception) { }
            if (origName.isBlank()) {
                origName = uri.lastPathSegment?.let { android.net.Uri.decode(it) }
                    ?.substringAfterLast('/')?.substringAfterLast(':') ?: "scph39001.bin"
            }

            val msg: String = try {
                val dest = java.io.File(biosDir, origName)
                context.contentResolver.openInputStream(uri)?.use { input ->
                    dest.outputStream().use { output -> input.copyTo(output) }
                }
                "已导入 BIOS: ${dest.name} (${dest.length() / 1024}KB)"
            } catch (e: Exception) {
                "导入失败: ${e.message}"
            }
            refreshKey++
            statusText = msg
        }
    }

    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(
            statusText,
            color = Color(0xFF88DD88),
            fontSize = 11.sp,
            lineHeight = 14.sp,
            modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp)
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "导入 BIOS (.bin/.rom)",
                color = Color(0xFFFFD66B),
                fontSize = 13.sp,
                modifier = Modifier
                    .clickable { biosPickerLauncher.launch(arrayOf("*/*")) }
                    .padding(8.dp)
            )
            Spacer(Modifier.size(12.dp))
            Text(
                "刷新",
                color = Color(0xFF8899AA),
                fontSize = 13.sp,
                modifier = Modifier
                    .clickable { refreshKey++ }
                    .padding(8.dp)
            )
        }
    }
}

@Composable
private fun DropdownSetting(
    label: String,
    options: List<Pair<String, String>>,
    selected: String,
    onSelect: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val selectedLabel = options.find { it.first == selected }?.second ?: selected
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(if (focused) Color.White.copy(alpha = 0.15f) else Color.Transparent)
            .clickable(interactionSource = interaction, indication = null) { expanded = true }
            .focusable(interactionSource = interaction)
            .padding(horizontal = 4.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, color = Color.White, fontSize = 13.sp, modifier = Modifier.padding(end = 8.dp))
        Spacer(Modifier.weight(1f))
        Box {
            Text(
                selectedLabel, color = Color(0xFFFFD66B), fontSize = 13.sp,
                modifier = Modifier.padding(8.dp)
            )
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                options.forEach { (value, text) ->
                    DropdownMenuItem(text = { Text(text, fontSize = 13.sp) }, onClick = { onSelect(value); expanded = false })
                }
            }
        }
    }
}

/**
 * TV-friendly switch setting row — focusable, toggles on D-pad OK press.
 */
@Composable
private fun SwitchSetting(
    label: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(if (focused) Color.White.copy(alpha = 0.15f) else Color.Transparent)
            .clickable(interactionSource = interaction, indication = null) {
                onCheckedChange(!checked)
            }
            .focusable(interactionSource = interaction)
            .padding(horizontal = 4.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(label, color = Color.White, fontSize = 13.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)
            Text(description, color = Color.White.copy(alpha = 0.6f), fontSize = 10.sp)
        }
        Spacer(Modifier.size(8.dp))
        androidx.compose.material3.Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = androidx.compose.material3.SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = Color(0xFFE74C3C)
            )
        )
    }
}
