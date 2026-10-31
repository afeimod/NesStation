package com.nesstation.app.ui.settings

import android.view.KeyEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Gamepad
import androidx.compose.material.icons.rounded.Keyboard
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import android.view.View
import com.nesstation.app.core.model.GamePlatform
import com.nesstation.app.ui.components.AppBackgroundState
import com.nesstation.app.ui.components.PixelBackdrop
import com.nesstation.app.ui.neon.NeonBackdrop
import com.nesstation.app.ui.neon.NeonUi

// ---------------------------------------------------------------------------
// Key mapping model
// ---------------------------------------------------------------------------
/**
 * A single controller action (e.g. "A button") and the Android [keyCode]
 * that currently triggers it. [defaultKeyLabel] is the human-readable name
 * shown when no custom key has been assigned.
 */
data class KeyAction(
    val id: String,
    val name: String,
    val accent: Color,
    val defaultKeyCode: Int,
    val defaultKeyLabel: String
) {
    fun keyLabel(context: android.content.Context): String =
        KeyMapStore.get(context, id)?.let { KeyMapStore.keyCodeToLabel(it) } ?: defaultKeyLabel
}

// Per-platform button sets
private val NES_ACTIONS = listOf(
    KeyAction("nes_up",    "上",     Color(0xFF3498DB), KeyEvent.KEYCODE_DPAD_UP,    "方向上"),
    KeyAction("nes_down",  "下",     Color(0xFF3498DB), KeyEvent.KEYCODE_DPAD_DOWN,  "方向下"),
    KeyAction("nes_left",  "左",     Color(0xFF3498DB), KeyEvent.KEYCODE_DPAD_LEFT,  "方向左"),
    KeyAction("nes_right", "右",     Color(0xFF3498DB), KeyEvent.KEYCODE_DPAD_RIGHT, "方向右"),
    KeyAction("nes_a",     "A",      Color(0xFFE74C3C), KeyEvent.KEYCODE_BUTTON_A,   "手柄 A"),
    KeyAction("nes_b",     "B",      Color(0xFFE67E22), KeyEvent.KEYCODE_BUTTON_B,   "手柄 B"),
    KeyAction("nes_select","Select", Color(0xFF1E2A3A), KeyEvent.KEYCODE_BUTTON_SELECT, "Select"),
    KeyAction("nes_start", "Start",  Color(0xFF1E2A3A), KeyEvent.KEYCODE_BUTTON_START,  "Start"),
    KeyAction("nes_ta",    "连发 A", Color(0xFF8E44AD), KeyEvent.KEYCODE_BUTTON_L2,  "L2"),
    KeyAction("nes_tb",    "连发 B", Color(0xFF8E44AD), KeyEvent.KEYCODE_BUTTON_R2,  "R2")
)

// ★★ v60 SNES 专属表：旧版 SNES_ACTIONS = NES_ACTIONS + listOf(...) ——
//   基础键存的是 nes_* id，而引擎 SFC 分支解析 snes_* id → 基础键自定义
//   绑定静默失效（且会串台改动 NES 平台的绑定）。改为完整 snes_* 表。
private val SNES_ACTIONS = listOf(
    KeyAction("snes_up",    "上",     Color(0xFF3498DB), KeyEvent.KEYCODE_DPAD_UP,    "方向上"),
    KeyAction("snes_down",  "下",     Color(0xFF3498DB), KeyEvent.KEYCODE_DPAD_DOWN,  "方向下"),
    KeyAction("snes_left",  "左",     Color(0xFF3498DB), KeyEvent.KEYCODE_DPAD_LEFT,  "方向左"),
    KeyAction("snes_right", "右",     Color(0xFF3498DB), KeyEvent.KEYCODE_DPAD_RIGHT, "方向右"),
    KeyAction("snes_a",     "A",      Color(0xFFE74C3C), KeyEvent.KEYCODE_BUTTON_A,   "手柄 A"),
    KeyAction("snes_b",     "B",      Color(0xFFE67E22), KeyEvent.KEYCODE_BUTTON_B,   "手柄 B"),
    KeyAction("snes_x",     "X",      Color(0xFFE74C3C), KeyEvent.KEYCODE_BUTTON_X,   "手柄 X"),
    KeyAction("snes_y",     "Y",      Color(0xFFE67E22), KeyEvent.KEYCODE_BUTTON_Y,   "手柄 Y"),
    KeyAction("snes_l",     "L",      Color(0xFF2ECC71), KeyEvent.KEYCODE_BUTTON_L1,  "L1"),
    KeyAction("snes_r",     "R",      Color(0xFF2ECC71), KeyEvent.KEYCODE_BUTTON_R1,  "R1"),
    KeyAction("snes_select", "Select", Color(0xFF1E2A3A), KeyEvent.KEYCODE_BUTTON_SELECT, "Select"),
    KeyAction("snes_start", "Start",  Color(0xFF1E2A3A), KeyEvent.KEYCODE_BUTTON_START,  "Start")
)

// ★★ v60 GBA 专属表：同田旧版 GBA_ACTIONS = NES_ACTIONS + listOf(...) ——
//   基础键存 nes_*，引擎 GBA 分支解析 gba_* → 自定义绑定静默失效。改完整表。
private val GBA_ACTIONS = listOf(
    KeyAction("gba_up",    "上",     Color(0xFF3498DB), KeyEvent.KEYCODE_DPAD_UP,    "方向上"),
    KeyAction("gba_down",  "下",     Color(0xFF3498DB), KeyEvent.KEYCODE_DPAD_DOWN,  "方向下"),
    KeyAction("gba_left",  "左",     Color(0xFF3498DB), KeyEvent.KEYCODE_DPAD_LEFT,  "方向左"),
    KeyAction("gba_right", "右",     Color(0xFF3498DB), KeyEvent.KEYCODE_DPAD_RIGHT, "方向右"),
    KeyAction("gba_a",     "A",      Color(0xFFE74C3C), KeyEvent.KEYCODE_BUTTON_A,   "手柄 A"),
    KeyAction("gba_b",     "B",      Color(0xFFE67E22), KeyEvent.KEYCODE_BUTTON_B,   "手柄 B"),
    KeyAction("gba_l",     "L",      Color(0xFF2ECC71), KeyEvent.KEYCODE_BUTTON_L1,  "L1"),
    KeyAction("gba_r",     "R",      Color(0xFF2ECC71), KeyEvent.KEYCODE_BUTTON_R1,  "R1"),
    KeyAction("gba_select", "Select", Color(0xFF1E2A3A), KeyEvent.KEYCODE_BUTTON_SELECT, "Select"),
    KeyAction("gba_start", "Start",  Color(0xFF1E2A3A), KeyEvent.KEYCODE_BUTTON_START,  "Start")
)

// DOS gamepad mapping — dosbox_pure's auto-mapping converts these libretro
// gamepad buttons to DOS keys (A→Enter, B→Esc, X→Space, Y→Tab, L→mouse left,
// R→mouse right, etc.). The user can rebind which physical gamepad button
// triggers each libretro ID.
private val DOS_ACTIONS = listOf(
    KeyAction("dos_up",     "上",     Color(0xFF3498DB), KeyEvent.KEYCODE_DPAD_UP,    "方向上"),
    KeyAction("dos_down",   "下",     Color(0xFF3498DB), KeyEvent.KEYCODE_DPAD_DOWN,  "方向下"),
    KeyAction("dos_left",   "左",     Color(0xFF3498DB), KeyEvent.KEYCODE_DPAD_LEFT,  "方向左"),
    KeyAction("dos_right",  "右",     Color(0xFF3498DB), KeyEvent.KEYCODE_DPAD_RIGHT, "方向右"),
    KeyAction("dos_a",      "A / Enter",  Color(0xFFE74C3C), KeyEvent.KEYCODE_BUTTON_A, "手柄 A"),
    KeyAction("dos_b",      "B / Esc",    Color(0xFFE67E22), KeyEvent.KEYCODE_BUTTON_B, "手柄 B"),
    KeyAction("dos_x",      "X / Space",  Color(0xFF3498DB), KeyEvent.KEYCODE_BUTTON_X, "手柄 X"),
    KeyAction("dos_y",      "Y / Tab",    Color(0xFF2ECC71), KeyEvent.KEYCODE_BUTTON_Y, "手柄 Y"),
    KeyAction("dos_l",      "L / 鼠标左",  Color(0xFF2ECC71), KeyEvent.KEYCODE_BUTTON_L1, "L1"),
    KeyAction("dos_r",      "R / 鼠标右",  Color(0xFF2ECC71), KeyEvent.KEYCODE_BUTTON_R1, "R1"),
    KeyAction("dos_l2",     "L2 / 鼠标中", Color(0xFF2ECC71), KeyEvent.KEYCODE_BUTTON_L2, "L2"),
    KeyAction("dos_r2",     "R2",         Color(0xFF2ECC71), KeyEvent.KEYCODE_BUTTON_R2, "R2"),
    KeyAction("dos_select", "Select",     Color(0xFF1E2A3A), KeyEvent.KEYCODE_BUTTON_SELECT, "Select"),
    KeyAction("dos_start",  "Start",      Color(0xFF1E2A3A), KeyEvent.KEYCODE_BUTTON_START,  "Start"),
    KeyAction("dos_l3",     "L3 / 切换键盘", Color(0xFF1E2A3A), KeyEvent.KEYCODE_BUTTON_THUMBL, "左摇杆按下"),
    KeyAction("dos_r3",     "R3 / 菜单",  Color(0xFF1E2A3A), KeyEvent.KEYCODE_BUTTON_THUMBR, "右摇杆按下"),
    KeyAction("dos_mode",   "Mode / 菜单", Color(0xFF1E2A3A), KeyEvent.KEYCODE_BUTTON_MODE, "Mode")
)

// Java (J2ME) uses the same 12-key phone keypad mapping as the J2ME-Loader
// settings — but here we expose the gamepad buttons for D-pad + soft keys.
private val JAVA_ACTIONS = listOf(
    KeyAction("java_up",    "上",     Color(0xFF3498DB), KeyEvent.KEYCODE_DPAD_UP,    "方向上"),
    KeyAction("java_down",  "下",     Color(0xFF3498DB), KeyEvent.KEYCODE_DPAD_DOWN,  "方向下"),
    KeyAction("java_left",  "左",     Color(0xFF3498DB), KeyEvent.KEYCODE_DPAD_LEFT,  "方向左"),
    KeyAction("java_right", "右",     Color(0xFF3498DB), KeyEvent.KEYCODE_DPAD_RIGHT, "方向右"),
    KeyAction("java_a",     "A / 确认", Color(0xFFE74C3C), KeyEvent.KEYCODE_BUTTON_A, "手柄 A"),
    KeyAction("java_b",     "B / 返回", Color(0xFFE67E22), KeyEvent.KEYCODE_BUTTON_B, "手柄 B"),
    KeyAction("java_select","左软键",  Color(0xFF1E2A3A), KeyEvent.KEYCODE_BUTTON_SELECT, "Select"),
    KeyAction("java_start", "右软键",  Color(0xFF1E2A3A), KeyEvent.KEYCODE_BUTTON_START,  "Start")
)

// Arcade (FBNeo) — 6-button fight-stick layout (CPS / NeoGeo style).
// Coin + Start correspond to MAME's "Insert Coin" and "Start Game" inputs.
// 屏幕标签与街机机身一致（A/B/C/D = 街机键 1-4，L/R = 键 5/6）：
// EmulatorScreen.arcadeToLibretroLayout 把 bit8/bit9 按同语义转成
// libretro Y/X —— FBNeo 的 BAYX 指派里 JOYPAD_Y=键3(C)、JOYPAD_X=键4(D)。
private val ARCADE_ACTIONS = listOf(
    KeyAction("arc_up",     "上",     Color(0xFF3498DB), KeyEvent.KEYCODE_DPAD_UP,    "方向上"),
    KeyAction("arc_down",   "下",     Color(0xFF3498DB), KeyEvent.KEYCODE_DPAD_DOWN,  "方向下"),
    KeyAction("arc_left",   "左",     Color(0xFF3498DB), KeyEvent.KEYCODE_DPAD_LEFT,  "方向左"),
    KeyAction("arc_right",  "右",     Color(0xFF3498DB), KeyEvent.KEYCODE_DPAD_RIGHT, "方向右"),
    KeyAction("arc_a",      "A / 弱拳", Color(0xFFE74C3C), KeyEvent.KEYCODE_BUTTON_A, "手柄 A"),
    KeyAction("arc_b",      "B / 中拳", Color(0xFFE67E22), KeyEvent.KEYCODE_BUTTON_B, "手柄 B"),
    KeyAction("arc_x",      "C / 强拳", Color(0xFFE74C3C), KeyEvent.KEYCODE_BUTTON_X, "手柄 X"),
    KeyAction("arc_y",      "D / 弱脚", Color(0xFFE67E22), KeyEvent.KEYCODE_BUTTON_Y, "手柄 Y"),
    KeyAction("arc_l",      "L / 中脚", Color(0xFF2ECC71), KeyEvent.KEYCODE_BUTTON_L1, "L1"),
    KeyAction("arc_r",      "R / 强脚", Color(0xFF2ECC71), KeyEvent.KEYCODE_BUTTON_R1, "R1"),
    KeyAction("arc_l2",     "L2 / 投币", Color(0xFFF57C00), KeyEvent.KEYCODE_BUTTON_L2, "L2 / 投币"),
    KeyAction("arc_r2",     "R2 / 开始", Color(0xFFF57C00), KeyEvent.KEYCODE_BUTTON_R2, "R2 / 开始"),
    KeyAction("arc_select", "Select / 投币", Color(0xFF1E2A3A), KeyEvent.KEYCODE_BUTTON_SELECT, "Select"),
    KeyAction("arc_start",  "Start / 开始", Color(0xFF1E2A3A), KeyEvent.KEYCODE_BUTTON_START,  "Start")
)

// SEGA Mega Drive / Genesis — supports both 3-button (B/A/C + Start) and
// 6-button (X/Y/Z + Mode) controllers. Genesis-Plus-GX auto-detects the
// layout per game. A/B/C are the primary 3-button face buttons; X/Y/Z are
// the extra 6-button row; Mode toggles 3-button compatibility mode.
// SMS / Game Gear / SG-1000 only use buttons 1 and 2 (mapped to A and B).
private val MD_ACTIONS = listOf(
    KeyAction("md_up",     "上",     Color(0xFF3498DB), KeyEvent.KEYCODE_DPAD_UP,    "方向上"),
    KeyAction("md_down",   "下",     Color(0xFF3498DB), KeyEvent.KEYCODE_DPAD_DOWN,  "方向下"),
    KeyAction("md_left",   "左",     Color(0xFF3498DB), KeyEvent.KEYCODE_DPAD_LEFT,  "方向左"),
    KeyAction("md_right",  "右",     Color(0xFF3498DB), KeyEvent.KEYCODE_DPAD_RIGHT, "方向右"),
    KeyAction("md_a",      "A",      Color(0xFFE74C3C), KeyEvent.KEYCODE_BUTTON_A, "手柄 A"),
    KeyAction("md_b",      "B",      Color(0xFFE67E22), KeyEvent.KEYCODE_BUTTON_B, "手柄 B"),
    KeyAction("md_c",      "C",      Color(0xFF2ECC71), KeyEvent.KEYCODE_BUTTON_R1, "R1 / C"),
    KeyAction("md_x",      "X",      Color(0xFF9C27B0), KeyEvent.KEYCODE_BUTTON_X, "手柄 X"),
    KeyAction("md_y",      "Y",      Color(0xFF00BCD4), KeyEvent.KEYCODE_BUTTON_Y, "手柄 Y"),
    KeyAction("md_z",      "Z",      Color(0xFFFFEB3B), KeyEvent.KEYCODE_BUTTON_L1, "L1 / Z"),
    KeyAction("md_mode",   "Mode",   Color(0xFF1E2A3A), KeyEvent.KEYCODE_BUTTON_MODE, "Mode"),
    KeyAction("md_start",  "Start",  Color(0xFF1E2A3A), KeyEvent.KEYCODE_BUTTON_START, "Start")
)

// PCE / TurboGrafx-16 / SuperGrafx controller layout.
// Per Geargrafx reference source (libretro.cpp input descriptors):
//   SNES A   → PCE I    (bit0)
//   SNES B   → PCE II   (bit1)
//   SNES Y   → PCE III  (bit9)  [6-button Avenue Pad only]
//   SNES X   → PCE IV   (bit8)  [6-button Avenue Pad only]
//   SNES L   → PCE V    (bit10) [6-button Avenue Pad only]
//   SNES R   → PCE VI   (bit11) [6-button Avenue Pad only]
//   SNES L2  → Toggle Turbo II (bit12)
//   SNES R2  → Toggle Turbo I  (bit13)
//   SNES Select → Select (bit2)
//   SNES Start  → Run     (bit3)
// The standard PCE pad only has I/II/Select/Run, but the 6-button Avenue Pad
// (used by fighting games like Street Fighter II) adds III/IV/V/VI. We include
// all 12+2 buttons so the user can map a physical 6-button controller.
private val PCE_ACTIONS = listOf(
    // D-pad
    KeyAction("pce_up",     "上",     Color(0xFF3498DB), KeyEvent.KEYCODE_DPAD_UP,    "方向上"),
    KeyAction("pce_down",   "下",     Color(0xFF3498DB), KeyEvent.KEYCODE_DPAD_DOWN,  "方向下"),
    KeyAction("pce_left",   "左",     Color(0xFF3498DB), KeyEvent.KEYCODE_DPAD_LEFT,  "方向左"),
    KeyAction("pce_right",  "右",     Color(0xFF3498DB), KeyEvent.KEYCODE_DPAD_RIGHT, "方向右"),
    // Face buttons (2-button standard pad)
    KeyAction("pce_i",      "I",      Color(0xFFE74C3C), KeyEvent.KEYCODE_BUTTON_A, "PCE I (bit0)"),
    KeyAction("pce_ii",     "II",     Color(0xFFE67E22), KeyEvent.KEYCODE_BUTTON_B, "PCE II (bit1)"),
    // 6-button Avenue Pad extra face buttons
    KeyAction("pce_iii",    "III",    Color(0xFF2ECC71), KeyEvent.KEYCODE_BUTTON_Y, "PCE III (bit9, 6键手柄)"),
    KeyAction("pce_iv",     "IV",     Color(0xFF3498DB), KeyEvent.KEYCODE_BUTTON_X, "PCE IV (bit8, 6键手柄)"),
    KeyAction("pce_v",      "V",      Color(0xFF9C27B0), KeyEvent.KEYCODE_BUTTON_L1, "PCE V (bit10, 6键手柄)"),
    KeyAction("pce_vi",     "VI",     Color(0xFF00BCD4), KeyEvent.KEYCODE_BUTTON_R1, "PCE VI (bit11, 6键手柄)"),
    // Turbo toggle (L2/R2)
    KeyAction("pce_turbo_ii", "TURBO II", Color(0xFFFF9800), KeyEvent.KEYCODE_BUTTON_L2, "切换 II 连发 (bit12)"),
    KeyAction("pce_turbo_i",  "TURBO I",  Color(0xFFFF9800), KeyEvent.KEYCODE_BUTTON_R2, "切换 I 连发 (bit13)"),
    // Select / Run
    KeyAction("pce_select", "Select", Color(0xFF1E2A3A), KeyEvent.KEYCODE_BUTTON_SELECT, "Select"),
    KeyAction("pce_run",    "Run",    Color(0xFF1E2A3A), KeyEvent.KEYCODE_BUTTON_START,  "Run (Start)")
)

// PS2 (PCEE2 — PCSX2 core) — full DualShock 2: 16 buttons incl. L2/R2/L3/R3.
// ★★ v60 摇杆方向：左/右摇杆上下左右八向（默认无键 —— 点击右侧按键框
//   绑定任意物理键；DOWN/UP 经 GameSurfaceView.handleKeyStickEvent 合成
//   模拟轴推给核心 —— 只有十字键的手柄/键盘也能驱动摇杆）。
// Ids match EmulatorScreen's PS2 table.
private val PS2_ACTIONS = listOf(
    KeyAction("ps2_up",     "上",     Color(0xFF3498DB), KeyEvent.KEYCODE_DPAD_UP,    "方向上"),
    KeyAction("ps2_down",   "下",     Color(0xFF3498DB), KeyEvent.KEYCODE_DPAD_DOWN,  "方向下"),
    KeyAction("ps2_left",   "左",     Color(0xFF3498DB), KeyEvent.KEYCODE_DPAD_LEFT,  "方向左"),
    KeyAction("ps2_right",  "右",     Color(0xFF3498DB), KeyEvent.KEYCODE_DPAD_RIGHT, "方向右"),
    KeyAction("ps2_a",      "×",      Color(0xFFE74C3C), KeyEvent.KEYCODE_BUTTON_A,   "× Cross"),
    KeyAction("ps2_b",      "○",      Color(0xFFE67E22), KeyEvent.KEYCODE_BUTTON_B,   "○ Circle"),
    KeyAction("ps2_x",      "□",      Color(0xFF3498DB), KeyEvent.KEYCODE_BUTTON_X,   "□ Square"),
    KeyAction("ps2_y",      "△",      Color(0xFF2ECC71), KeyEvent.KEYCODE_BUTTON_Y,   "△ Triangle"),
    KeyAction("ps2_l",      "L1",     Color(0xFF2ECC71), KeyEvent.KEYCODE_BUTTON_L1,  "L1"),
    KeyAction("ps2_r",      "R1",     Color(0xFF2ECC71), KeyEvent.KEYCODE_BUTTON_R1,  "R1"),
    KeyAction("ps2_l2",     "L2",     Color(0xFF2ECC71), KeyEvent.KEYCODE_BUTTON_L2,  "L2"),
    KeyAction("ps2_r2",     "R2",     Color(0xFF2ECC71), KeyEvent.KEYCODE_BUTTON_R2,  "R2"),
    KeyAction("ps2_l3",     "L3",     Color(0xFF95A5A6), KeyEvent.KEYCODE_BUTTON_THUMBL, "左摇杆按下"),
    KeyAction("ps2_r3",     "R3",     Color(0xFF95A5A6), KeyEvent.KEYCODE_BUTTON_THUMBR, "右摇杆按下"),
    KeyAction("ps2_select", "Select", Color(0xFF1E2A3A), KeyEvent.KEYCODE_BUTTON_SELECT, "Select"),
    KeyAction("ps2_start",  "Start",  Color(0xFF1E2A3A), KeyEvent.KEYCODE_BUTTON_START,  "Start"),
    // ★★ v60 左摇杆四向
    KeyAction("ps2_ls_up",    "左摇杆·上", Color(0xFF9B59B6), 0, "未设置"),
    KeyAction("ps2_ls_down",  "左摇杆·下", Color(0xFF9B59B6), 0, "未设置"),
    KeyAction("ps2_ls_left",  "左摇杆·左", Color(0xFF9B59B6), 0, "未设置"),
    KeyAction("ps2_ls_right", "左摇杆·右", Color(0xFF9B59B6), 0, "未设置"),
    // ★★ v60 右摇杆四向
    KeyAction("ps2_rs_up",    "右摇杆·上", Color(0xFF8E44AD), 0, "未设置"),
    KeyAction("ps2_rs_down",  "右摇杆·下", Color(0xFF8E44AD), 0, "未设置"),
    KeyAction("ps2_rs_left",  "右摇杆·左", Color(0xFF8E44AD), 0, "未设置"),
    KeyAction("ps2_rs_right", "右摇杆·右", Color(0xFF8E44AD), 0, "未设置")
)

// PSP (PPSSPP) — PPSSPP 默认 Android 手柄映射（defaultPadMapAndroid）。
// Ids match EmulatorScreen's PSP KeyActionInternal table; bits are routed
// through PspEngine（ps2ToLibretroLayout 位转换→libretro JOYPAD）。
// L2 = PPSSPP 暂停菜单（游戏内设置/即时存档/金手指入口）,
// R2 = PPSSPP 快进。PSP 无 L3/R3。
private val PSP_ACTIONS = listOf(
    KeyAction("psp_up",     "上",     Color(0xFF3498DB), KeyEvent.KEYCODE_DPAD_UP,    "方向上"),
    KeyAction("psp_down",   "下",     Color(0xFF3498DB), KeyEvent.KEYCODE_DPAD_DOWN,  "方向下"),
    KeyAction("psp_left",   "左",     Color(0xFF3498DB), KeyEvent.KEYCODE_DPAD_LEFT,  "方向左"),
    KeyAction("psp_right",  "右",     Color(0xFF3498DB), KeyEvent.KEYCODE_DPAD_RIGHT, "方向右"),
    KeyAction("psp_a",      "×",      Color(0xFFE74C3C), KeyEvent.KEYCODE_BUTTON_A,   "× Cross"),
    KeyAction("psp_b",      "○",      Color(0xFFE67E22), KeyEvent.KEYCODE_BUTTON_B,   "○ Circle"),
    KeyAction("psp_x",      "□",      Color(0xFF3498DB), KeyEvent.KEYCODE_BUTTON_X,   "□ Square"),
    KeyAction("psp_y",      "△",      Color(0xFF2ECC71), KeyEvent.KEYCODE_BUTTON_Y,   "△ Triangle"),
    KeyAction("psp_l",      "L",      Color(0xFF2ECC71), KeyEvent.KEYCODE_BUTTON_L1,  "L (L1)"),
    KeyAction("psp_r",      "R",      Color(0xFF2ECC71), KeyEvent.KEYCODE_BUTTON_R1,  "R (R1)"),
    KeyAction("psp_menu",   "菜单",    Color(0xFF95A5A6), KeyEvent.KEYCODE_BUTTON_L2,  "PPSSPP 菜单 (L2)"),
    KeyAction("psp_ff",     "快进",    Color(0xFF95A5A6), KeyEvent.KEYCODE_BUTTON_R2,  "PPSSPP 快进 (R2)"),
    KeyAction("psp_select", "Select", Color(0xFF1E2A3A), KeyEvent.KEYCODE_BUTTON_SELECT, "Select"),
    KeyAction("psp_start",  "Start",  Color(0xFF1E2A3A), KeyEvent.KEYCODE_BUTTON_START,  "Start"),
    // ★★ v60 PSP 单摇杆四向（PSP 只有左摇杆）
    KeyAction("psp_ls_up",    "摇杆·上", Color(0xFF9B59B6), 0, "未设置"),
    KeyAction("psp_ls_down",  "摇杆·下", Color(0xFF9B59B6), 0, "未设置"),
    KeyAction("psp_ls_left",  "摇杆·左", Color(0xFF9B59B6), 0, "未设置"),
    KeyAction("psp_ls_right", "摇杆·右", Color(0xFF9B59B6), 0, "未设置")
)

// DC (Dreamcast) — standard 12-button pad. Ids match EmulatorScreen's DC
// KeyActionInternal table. flycast's libretro mapping (JOYPAD_B→DC A,
// JOYPAD_A→DC B, JOYPAD_Y→DC X, JOYPAD_X→DC Y) is applied at output time by
// dcToLibretroLayout, so screen labels map 1:1 to in-game DC buttons.
private val DC_ACTIONS = listOf(
    KeyAction("dc_up",     "上",     Color(0xFF3498DB), KeyEvent.KEYCODE_DPAD_UP,    "方向上"),
    KeyAction("dc_down",   "下",     Color(0xFF3498DB), KeyEvent.KEYCODE_DPAD_DOWN,  "方向下"),
    KeyAction("dc_left",   "左",     Color(0xFF3498DB), KeyEvent.KEYCODE_DPAD_LEFT,  "方向左"),
    KeyAction("dc_right",  "右",     Color(0xFF3498DB), KeyEvent.KEYCODE_DPAD_RIGHT, "方向右"),
    KeyAction("dc_a",      "A",      Color(0xFFE74C3C), KeyEvent.KEYCODE_BUTTON_A,   "手柄 A"),
    KeyAction("dc_b",      "B",      Color(0xFFE67E22), KeyEvent.KEYCODE_BUTTON_B,   "手柄 B"),
    KeyAction("dc_x",      "X",      Color(0xFF3498DB), KeyEvent.KEYCODE_BUTTON_X,   "手柄 X"),
    KeyAction("dc_y",      "Y",      Color(0xFF2ECC71), KeyEvent.KEYCODE_BUTTON_Y,   "手柄 Y"),
    KeyAction("dc_l",      "L",      Color(0xFF9C27B0), KeyEvent.KEYCODE_BUTTON_L1,  "L 扳机"),
    KeyAction("dc_r",      "R",      Color(0xFF00BCD4), KeyEvent.KEYCODE_BUTTON_R1,  "R 扳机"),
    KeyAction("dc_select", "Select", Color(0xFF1E2A3A), KeyEvent.KEYCODE_BUTTON_SELECT, "Select (\"D\")"),
    KeyAction("dc_start",  "Start",  Color(0xFF1E2A3A), KeyEvent.KEYCODE_BUTTON_START,  "Start"),
    // ★★ v60 DC 摇杆四向（Dreamcast 手柄单摇杆）
    KeyAction("dc_ls_up",    "摇杆·上", Color(0xFF9B59B6), 0, "未设置"),
    KeyAction("dc_ls_down",  "摇杆·下", Color(0xFF9B59B6), 0, "未设置"),
    KeyAction("dc_ls_left",  "摇杆·左", Color(0xFF9B59B6), 0, "未设置"),
    KeyAction("dc_ls_right", "摇杆·右", Color(0xFF9B59B6), 0, "未设置")
)

private val N3DS_ACTIONS = listOf(
    KeyAction("n3ds_up",     "上",     Color(0xFF3498DB), KeyEvent.KEYCODE_DPAD_UP,    "方向上"),
    KeyAction("n3ds_down",   "下",     Color(0xFF3498DB), KeyEvent.KEYCODE_DPAD_DOWN,  "方向下"),
    KeyAction("n3ds_left",   "左",     Color(0xFF3498DB), KeyEvent.KEYCODE_DPAD_LEFT,  "方向左"),
    KeyAction("n3ds_right",  "右",     Color(0xFF3498DB), KeyEvent.KEYCODE_DPAD_RIGHT, "方向右"),
    KeyAction("n3ds_a",      "A",      Color(0xFFE74C3C), KeyEvent.KEYCODE_BUTTON_A,   "A"),
    KeyAction("n3ds_b",      "B",      Color(0xFFE67E22), KeyEvent.KEYCODE_BUTTON_B,   "B"),
    KeyAction("n3ds_x",      "X",      Color(0xFF3498DB), KeyEvent.KEYCODE_BUTTON_X,   "X"),
    KeyAction("n3ds_y",      "Y",      Color(0xFF2ECC71), KeyEvent.KEYCODE_BUTTON_Y,   "Y"),
    KeyAction("n3ds_l",      "L",      Color(0xFF9C27B0), KeyEvent.KEYCODE_BUTTON_L1,  "L"),
    KeyAction("n3ds_r",      "R",      Color(0xFF00BCD4), KeyEvent.KEYCODE_BUTTON_R1,  "R"),
    KeyAction("n3ds_zl",     "ZL",     Color(0xFF1F6F8B), KeyEvent.KEYCODE_BUTTON_L2,  "ZL (New3DS)"),
    KeyAction("n3ds_zr",     "ZR",     Color(0xFF1F8B6F), KeyEvent.KEYCODE_BUTTON_R2,  "ZR (New3DS)"),
    KeyAction("n3ds_select", "Select", Color(0xFF1E2A3A), KeyEvent.KEYCODE_BUTTON_SELECT, "Select"),
    KeyAction("n3ds_start",  "Start",  Color(0xFF1E2A3A), KeyEvent.KEYCODE_BUTTON_START,  "Start"),
    // ★★ v60 3DS CirclePad / C-Stick（New3DS）四向
    KeyAction("n3ds_ls_up",    "圆盘垫·上", Color(0xFF9B59B6), 0, "未设置"),
    KeyAction("n3ds_ls_down",  "圆盘垫·下", Color(0xFF9B59B6), 0, "未设置"),
    KeyAction("n3ds_ls_left",  "圆盘垫·左", Color(0xFF9B59B6), 0, "未设置"),
    KeyAction("n3ds_ls_right", "圆盘垫·右", Color(0xFF9B59B6), 0, "未设置"),
    KeyAction("n3ds_rs_up",    "C摇杆·上", Color(0xFF8E44AD), 0, "未设置"),
    KeyAction("n3ds_rs_down",  "C摇杆·下", Color(0xFF8E44AD), 0, "未设置"),
    KeyAction("n3ds_rs_left",  "C摇杆·左", Color(0xFF8E44AD), 0, "未设置"),
    KeyAction("n3ds_rs_right", "C摇杆·右", Color(0xFF8E44AD), 0, "未设置")
)

private val NGCWII_ACTIONS = listOf(
    KeyAction("ngc_up",         "上",       Color(0xFF3498DB), KeyEvent.KEYCODE_DPAD_UP,      "GC/Wii 方向上"),
    KeyAction("ngc_down",       "下",       Color(0xFF3498DB), KeyEvent.KEYCODE_DPAD_DOWN,    "GC/Wii 方向下"),
    KeyAction("ngc_left",       "左",       Color(0xFF3498DB), KeyEvent.KEYCODE_DPAD_LEFT,    "GC/Wii 方向左"),
    KeyAction("ngc_right",      "右",       Color(0xFF3498DB), KeyEvent.KEYCODE_DPAD_RIGHT,   "GC/Wii 方向右"),
    KeyAction("ngc_a",          "A",        Color(0xFFE74C3C), KeyEvent.KEYCODE_BUTTON_A,     "GC A / Wii A"),
    KeyAction("ngc_b",          "B",        Color(0xFFE67E22), KeyEvent.KEYCODE_BUTTON_B,     "GC B / Wii B"),
    KeyAction("ngc_x",          "X",        Color(0xFF3498DB), KeyEvent.KEYCODE_BUTTON_X,     "GC X"),
    KeyAction("ngc_y",          "Y",        Color(0xFF2ECC71), KeyEvent.KEYCODE_BUTTON_Y,     "GC Y"),
    KeyAction("ngc_z",          "Z",        Color(0xFF8E44AD), KeyEvent.KEYCODE_BUTTON_Z,     "GC Z / Wii IR 隐藏"),
    KeyAction("ngc_l",          "L",        Color(0xFF9C27B0), KeyEvent.KEYCODE_BUTTON_L1,    "GC L 扳机 / 摇晃X"),
    KeyAction("ngc_r",          "R",        Color(0xFF00BCD4), KeyEvent.KEYCODE_BUTTON_R1,    "GC R 扳机 / 摇晃Z"),
    KeyAction("ngc_start",      "Start",    Color(0xFF1E2A3A), KeyEvent.KEYCODE_BUTTON_START, "GC Start"),
    KeyAction("ngc_wii_1",      "Wii 1",    Color(0xFF2ECC71), KeyEvent.KEYCODE_BUTTON_1,     "Wii 1"),
    KeyAction("ngc_wii_2",      "Wii 2",    Color(0xFF3498DB), KeyEvent.KEYCODE_BUTTON_2,     "Wii 2"),
    KeyAction("ngc_wii_plus",   "Wii +",    Color(0xFF1E2A3A), KeyEvent.KEYCODE_BUTTON_START, "Wii +"),
    KeyAction("ngc_wii_minus",  "Wii −",    Color(0xFF1E2A3A), KeyEvent.KEYCODE_BUTTON_SELECT,"Wii −"),
    KeyAction("ngc_wii_home",   "Wii HOME", Color(0xFF607D8B), KeyEvent.KEYCODE_BUTTON_MODE, "Wii HOME"),
    // ★ 双节棍 C/Z 默认改绑 BUTTON_C/Z（L2/R2 让位给倾斜体感）
    KeyAction("ngc_wii_c",      "双节棍 C", Color(0xFF16A085), KeyEvent.KEYCODE_BUTTON_C,     "双节棍 C"),
    KeyAction("ngc_wii_z",      "双节棍 Z", Color(0xFF27AE60), KeyEvent.KEYCODE_BUTTON_Z,     "双节棍 Z"),
    // ★ Wii 倾斜体感（可自定义键位）：L2 左倾 / R2 右倾 / L3 前倾 / R3 后倾
    KeyAction("ngc_wii_tilt_l", "倾斜·左",  Color(0xFF95A5A6), KeyEvent.KEYCODE_BUTTON_L2,     "Wii 向左倾斜"),
    KeyAction("ngc_wii_tilt_r", "倾斜·右",  Color(0xFF95A5A6), KeyEvent.KEYCODE_BUTTON_R2,     "Wii 向右倾斜"),
    KeyAction("ngc_wii_tilt_f", "倾斜·前",  Color(0xFF7F8C8D), KeyEvent.KEYCODE_BUTTON_THUMBL, "Wii 向前倾斜"),
    KeyAction("ngc_wii_tilt_b", "倾斜·后",  Color(0xFF7F8C8D), KeyEvent.KEYCODE_BUTTON_THUMBR, "Wii 向后倾斜"),
    // ★★ v60 NGC 主摇杆 / C 摇杆（Wii 双节棍摇杆同主摇杆轴）四向
    KeyAction("ngc_ls_up",    "主摇杆·上", Color(0xFF9B59B6), 0, "未设置"),
    KeyAction("ngc_ls_down",  "主摇杆·下", Color(0xFF9B59B6), 0, "未设置"),
    KeyAction("ngc_ls_left",  "主摇杆·左", Color(0xFF9B59B6), 0, "未设置"),
    KeyAction("ngc_ls_right", "主摇杆·右", Color(0xFF9B59B6), 0, "未设置"),
    KeyAction("ngc_rs_up",    "C摇杆·上", Color(0xFF8E44AD), 0, "未设置"),
    KeyAction("ngc_rs_down",  "C摇杆·下", Color(0xFF8E44AD), 0, "未设置"),
    KeyAction("ngc_rs_left",  "C摇杆·左", Color(0xFF8E44AD), 0, "未设置"),
    KeyAction("ngc_rs_right", "C摇杆·右", Color(0xFF8E44AD), 0, "未设置")
)

// ★★ Wii U（Cemu-AZ 源码核心）GamePad 按键动作表：id 与 EmulatorScreen 的
//   KeyActionInternal wiiu_* 表一一对应，实体手柄按键经 actionToBits 的
//   WIIU 分支映射（L2/R2→ZL/ZR、THUMBL/R→摇杆按下），CemuEngine 按项目
//   位布局直收并翻译为 VPAD 事件。TV 切换/麦克风为虚拟键专属（无实体默认键）。
private val WIIU_ACTIONS = listOf(
    KeyAction("wiiu_up",     "上",    Color(0xFF3498DB), KeyEvent.KEYCODE_DPAD_UP,       "方向上"),
    KeyAction("wiiu_down",   "下",    Color(0xFF3498DB), KeyEvent.KEYCODE_DPAD_DOWN,     "方向下"),
    KeyAction("wiiu_left",   "左",    Color(0xFF3498DB), KeyEvent.KEYCODE_DPAD_LEFT,     "方向左"),
    KeyAction("wiiu_right",  "右",    Color(0xFF3498DB), KeyEvent.KEYCODE_DPAD_RIGHT,    "方向右"),
    KeyAction("wiiu_a",      "A",     Color(0xFFE74C3C), KeyEvent.KEYCODE_BUTTON_A,      "GamePad A"),
    KeyAction("wiiu_b",      "B",     Color(0xFFE67E22), KeyEvent.KEYCODE_BUTTON_B,      "GamePad B"),
    KeyAction("wiiu_x",      "X",     Color(0xFF3498DB), KeyEvent.KEYCODE_BUTTON_X,      "GamePad X"),
    KeyAction("wiiu_y",      "Y",     Color(0xFF2ECC71), KeyEvent.KEYCODE_BUTTON_Y,      "GamePad Y"),
    KeyAction("wiiu_l",      "L",     Color(0xFF9C27B0), KeyEvent.KEYCODE_BUTTON_L1,     "GamePad L"),
    KeyAction("wiiu_r",      "R",     Color(0xFF00BCD4), KeyEvent.KEYCODE_BUTTON_R1,     "GamePad R"),
    KeyAction("wiiu_zl",     "ZL",    Color(0xFF1F6F8B), KeyEvent.KEYCODE_BUTTON_L2,     "GamePad ZL"),
    KeyAction("wiiu_zr",     "ZR",    Color(0xFF1F8B6F), KeyEvent.KEYCODE_BUTTON_R2,     "GamePad ZR"),
    KeyAction("wiiu_stickl", "L3",    Color(0xFF7F8C8D), KeyEvent.KEYCODE_BUTTON_THUMBL, "左摇杆按下"),
    KeyAction("wiiu_stickr", "R3",    Color(0xFF7F8C8D), KeyEvent.KEYCODE_BUTTON_THUMBR, "右摇杆按下"),
    KeyAction("wiiu_minus",  "−",     Color(0xFF1E2A3A), KeyEvent.KEYCODE_BUTTON_SELECT, "GamePad −"),
    KeyAction("wiiu_plus",   "+",     Color(0xFF1E2A3A), KeyEvent.KEYCODE_BUTTON_START,  "GamePad +"),
    KeyAction("wiiu_home",   "HOME",  Color(0xFF1E2A3A), KeyEvent.KEYCODE_BUTTON_MODE,   "GamePad HOME"),
    // ★★ v60 Wii U GamePad 左/右摇杆四向
    KeyAction("wiiu_ls_up",    "左摇杆·上", Color(0xFF9B59B6), 0, "未设置"),
    KeyAction("wiiu_ls_down",  "左摇杆·下", Color(0xFF9B59B6), 0, "未设置"),
    KeyAction("wiiu_ls_left",  "左摇杆·左", Color(0xFF9B59B6), 0, "未设置"),
    KeyAction("wiiu_ls_right", "左摇杆·右", Color(0xFF9B59B6), 0, "未设置"),
    KeyAction("wiiu_rs_up",    "右摇杆·上", Color(0xFF8E44AD), 0, "未设置"),
    KeyAction("wiiu_rs_down",  "右摇杆·下", Color(0xFF8E44AD), 0, "未设置"),
    KeyAction("wiiu_rs_left",  "右摇杆·左", Color(0xFF8E44AD), 0, "未设置"),
    KeyAction("wiiu_rs_right", "右摇杆·右", Color(0xFF8E44AD), 0, "未设置")
)

// ★★ v60 PSX（PCSX-ReARMed）专属表：此前 PSX 复用 SNES_ACTIONS（snes_* id）——
//   而 EmulatorScreen 解析的是 psx_* id，自定义绑定【全部静默失效】（绑了
//   没反应）。现与引擎侧 id 对齐（psx_*）+ 补 DualShock 双摇杆八向（原生
//   setAnalog1 新链路，配合「设置→PSX→手柄类型=DualShock 模拟」生效）。
private val PSX_ACTIONS = listOf(
    KeyAction("psx_up",     "上",     Color(0xFF3498DB), KeyEvent.KEYCODE_DPAD_UP,    "方向上"),
    KeyAction("psx_down",   "下",     Color(0xFF3498DB), KeyEvent.KEYCODE_DPAD_DOWN,  "方向下"),
    KeyAction("psx_left",   "左",     Color(0xFF3498DB), KeyEvent.KEYCODE_DPAD_LEFT,  "方向左"),
    KeyAction("psx_right",  "右",     Color(0xFF3498DB), KeyEvent.KEYCODE_DPAD_RIGHT, "方向右"),
    KeyAction("psx_a",      "×",      Color(0xFFE74C3C), KeyEvent.KEYCODE_BUTTON_A,   "× Cross"),
    KeyAction("psx_b",      "○",      Color(0xFFE67E22), KeyEvent.KEYCODE_BUTTON_B,   "○ Circle"),
    KeyAction("psx_x",      "□",      Color(0xFF3498DB), KeyEvent.KEYCODE_BUTTON_X,   "□ Square"),
    KeyAction("psx_y",      "△",      Color(0xFF2ECC71), KeyEvent.KEYCODE_BUTTON_Y,   "△ Triangle"),
    KeyAction("psx_l",      "L1",     Color(0xFF2ECC71), KeyEvent.KEYCODE_BUTTON_L1,  "L1"),
    KeyAction("psx_r",      "R1",     Color(0xFF2ECC71), KeyEvent.KEYCODE_BUTTON_R1,  "R1"),
    KeyAction("psx_select", "Select", Color(0xFF1E2A3A), KeyEvent.KEYCODE_BUTTON_SELECT, "Select"),
    KeyAction("psx_start",  "Start",  Color(0xFF1E2A3A), KeyEvent.KEYCODE_BUTTON_START,  "Start"),
    // ★★ v60 DualShock 双摇杆八向
    KeyAction("psx_ls_up",    "左摇杆·上", Color(0xFF9B59B6), 0, "未设置"),
    KeyAction("psx_ls_down",  "左摇杆·下", Color(0xFF9B59B6), 0, "未设置"),
    KeyAction("psx_ls_left",  "左摇杆·左", Color(0xFF9B59B6), 0, "未设置"),
    KeyAction("psx_ls_right", "左摇杆·右", Color(0xFF9B59B6), 0, "未设置"),
    KeyAction("psx_rs_up",    "右摇杆·上", Color(0xFF8E44AD), 0, "未设置"),
    KeyAction("psx_rs_down",  "右摇杆·下", Color(0xFF8E44AD), 0, "未设置"),
    KeyAction("psx_rs_left",  "右摇杆·左", Color(0xFF8E44AD), 0, "未设置"),
    KeyAction("psx_rs_right", "右摇杆·右", Color(0xFF8E44AD), 0, "未设置")
)

// ★★ v60 NDS 专属表：同因 id 不对齐（旧用 snes_*，引擎解析 nds_*）而全静默失效。
private val NDS_ACTIONS = listOf(
    KeyAction("nds_up",     "上",     Color(0xFF3498DB), KeyEvent.KEYCODE_DPAD_UP,    "方向上"),
    KeyAction("nds_down",   "下",     Color(0xFF3498DB), KeyEvent.KEYCODE_DPAD_DOWN,  "方向下"),
    KeyAction("nds_left",   "左",     Color(0xFF3498DB), KeyEvent.KEYCODE_DPAD_LEFT,  "方向左"),
    KeyAction("nds_right",  "右",     Color(0xFF3498DB), KeyEvent.KEYCODE_DPAD_RIGHT, "方向右"),
    KeyAction("nds_a",      "A",      Color(0xFFE74C3C), KeyEvent.KEYCODE_BUTTON_A,   "手柄 A"),
    KeyAction("nds_b",      "B",      Color(0xFFE67E22), KeyEvent.KEYCODE_BUTTON_B,   "手柄 B"),
    KeyAction("nds_x",      "X",      Color(0xFF3498DB), KeyEvent.KEYCODE_BUTTON_X,   "手柄 X"),
    KeyAction("nds_y",      "Y",      Color(0xFF2ECC71), KeyEvent.KEYCODE_BUTTON_Y,   "手柄 Y"),
    KeyAction("nds_l",      "L",      Color(0xFF2ECC71), KeyEvent.KEYCODE_BUTTON_L1,  "L"),
    KeyAction("nds_r",      "R",      Color(0xFF2ECC71), KeyEvent.KEYCODE_BUTTON_R1,  "R"),
    KeyAction("nds_select", "Select", Color(0xFF1E2A3A), KeyEvent.KEYCODE_BUTTON_SELECT, "Select"),
    KeyAction("nds_start",  "Start",  Color(0xFF1E2A3A), KeyEvent.KEYCODE_BUTTON_START,  "Start")
)

// ★★ v60 Switch（Eden）专属表：同因 id 不对齐（旧用 snes_*，引擎解析
//   switch_*）而全静默失效 + 补双摇杆八向。
private val SWITCH_ACTIONS = listOf(
    KeyAction("switch_up",     "上",     Color(0xFF3498DB), KeyEvent.KEYCODE_DPAD_UP,    "方向上"),
    KeyAction("switch_down",   "下",     Color(0xFF3498DB), KeyEvent.KEYCODE_DPAD_DOWN,  "方向下"),
    KeyAction("switch_left",   "左",     Color(0xFF3498DB), KeyEvent.KEYCODE_DPAD_LEFT,  "方向左"),
    KeyAction("switch_right",  "右",     Color(0xFF3498DB), KeyEvent.KEYCODE_DPAD_RIGHT, "方向右"),
    KeyAction("switch_a",      "A",      Color(0xFFE74C3C), KeyEvent.KEYCODE_BUTTON_A,   "手柄 A"),
    KeyAction("switch_b",      "B",      Color(0xFFE67E22), KeyEvent.KEYCODE_BUTTON_B,   "手柄 B"),
    KeyAction("switch_x",      "X",      Color(0xFF3498DB), KeyEvent.KEYCODE_BUTTON_X,   "手柄 X"),
    KeyAction("switch_y",      "Y",      Color(0xFF2ECC71), KeyEvent.KEYCODE_BUTTON_Y,   "手柄 Y"),
    KeyAction("switch_l",      "L",      Color(0xFF2ECC71), KeyEvent.KEYCODE_BUTTON_L1,  "L"),
    KeyAction("switch_r",      "R",      Color(0xFF2ECC71), KeyEvent.KEYCODE_BUTTON_R1,  "R"),
    KeyAction("switch_zl",     "ZL",     Color(0xFF1F6F8B), KeyEvent.KEYCODE_BUTTON_L2,  "ZL"),
    KeyAction("switch_zr",     "ZR",     Color(0xFF1F8B6F), KeyEvent.KEYCODE_BUTTON_R2,  "ZR"),
    KeyAction("switch_stickl", "L3",     Color(0xFF7F8C8D), KeyEvent.KEYCODE_BUTTON_THUMBL, "左摇杆按下"),
    KeyAction("switch_stickr", "R3",     Color(0xFF7F8C8D), KeyEvent.KEYCODE_BUTTON_THUMBR, "右摇杆按下"),
    KeyAction("switch_minus",  "−",      Color(0xFF1E2A3A), KeyEvent.KEYCODE_BUTTON_SELECT, "−"),
    KeyAction("switch_plus",   "+",      Color(0xFF1E2A3A), KeyEvent.KEYCODE_BUTTON_START,  "+"),
    KeyAction("switch_home",   "HOME",   Color(0xFF607D8B), KeyEvent.KEYCODE_BUTTON_MODE,   "HOME"),
    // ★★ v60 Switch 左/右摇杆八向
    KeyAction("switch_ls_up",    "左摇杆·上", Color(0xFF9B59B6), 0, "未设置"),
    KeyAction("switch_ls_down",  "左摇杆·下", Color(0xFF9B59B6), 0, "未设置"),
    KeyAction("switch_ls_left",  "左摇杆·左", Color(0xFF9B59B6), 0, "未设置"),
    KeyAction("switch_ls_right", "左摇杆·右", Color(0xFF9B59B6), 0, "未设置"),
    KeyAction("switch_rs_up",    "右摇杆·上", Color(0xFF8E44AD), 0, "未设置"),
    KeyAction("switch_rs_down",  "右摇杆·下", Color(0xFF8E44AD), 0, "未设置"),
    KeyAction("switch_rs_left",  "右摇杆·左", Color(0xFF8E44AD), 0, "未设置"),
    KeyAction("switch_rs_right", "右摇杆·右", Color(0xFF8E44AD), 0, "未设置")
)

private fun actionsFor(platform: GamePlatform, player: Int = 0): List<KeyAction> {
    val suffix = "_p${player + 1}"
    return when (platform) {
        GamePlatform.NES    -> NES_ACTIONS.map { it.copy(id = it.id + suffix) }
        GamePlatform.SFC    -> SNES_ACTIONS.map { it.copy(id = it.id + suffix) }
        GamePlatform.GB     -> NES_ACTIONS.map { it.copy(id = it.id + suffix) }
        GamePlatform.GBA    -> GBA_ACTIONS.map { it.copy(id = it.id + suffix) }
        GamePlatform.DOS    -> DOS_ACTIONS.map { it.copy(id = it.id + suffix) }
        GamePlatform.ARCADE -> ARCADE_ACTIONS.map { it.copy(id = it.id + suffix) }
        GamePlatform.MD     -> MD_ACTIONS.map { it.copy(id = it.id + suffix) }
        GamePlatform.PCE    -> PCE_ACTIONS.map { it.copy(id = it.id + suffix) }
        // ★★ v60 NDS/PSX/Switch 改用 id 对齐的专属表（旧版复用 SNES_ACTIONS
        //   → 存储的 snes_* 绑定与引擎解析的 nds_*/psx_*/switch_* id 对不上
        //   → 自定义映射静默失效）；PSX/Switch 同时补齐摇杆八向。
        GamePlatform.NDS    -> NDS_ACTIONS.map { it.copy(id = it.id + suffix) }
        GamePlatform.PSX    -> PSX_ACTIONS.map { it.copy(id = it.id + suffix) }
        GamePlatform.PS2    -> PS2_ACTIONS.map { it.copy(id = it.id + suffix) }
        GamePlatform.JAVA   -> JAVA_ACTIONS.map { it.copy(id = it.id + suffix) }
        // DC（libretro Flycast）与其他核心同一按键映射体系：id 对应
        // EmulatorScreen 的 KeyActionInternal DC 表（dc_*），输出经
        // dcToLibretroLayout 转换为 flycast 官方 JOYPAD 映射。
        GamePlatform.DC     -> DC_ACTIONS.map { it.copy(id = it.id + suffix) }
        // 3DS（Azahar）/ NGC-WII（Ishiiruka）：id 对应 EmulatorScreen 的
        // KeyActionInternal n3ds_* / ngc_* 表，引擎按项目位布局直收。
        GamePlatform.N3DS   -> N3DS_ACTIONS.map { it.copy(id = it.id + suffix) }
        GamePlatform.NGCWII -> NGCWII_ACTIONS.map { it.copy(id = it.id + suffix) }
        // PSP（PPSSPP 进程内引擎）：id 对应 EmulatorScreen 的 KeyActionInternal
        // psp_* 表，引擎按项目位布局直收（位→Android 键码→NativeApp.keyDown）。
        GamePlatform.PSP    -> PSP_ACTIONS.map { it.copy(id = it.id + suffix) }
        // ★ Wii U（Cemu-AZ 源码核心）：id 对应 EmulatorScreen 的 wiiu_* 表，
        // 位→VPAD 事件由 CemuEngine.setPad1 翻译。
        GamePlatform.WIIU   -> WIIU_ACTIONS.map { it.copy(id = it.id + suffix) }
        // ★★ v60 Switch：switch_* id（与引擎侧对齐）+ 摇杆八向。
        GamePlatform.NSWITCH -> SWITCH_ACTIONS.map { it.copy(id = it.id + suffix) }
    }
}

// ---------------------------------------------------------------------------
// KeyMapScreen — per-core key mapping with TV D-pad support
// ---------------------------------------------------------------------------
@Composable
fun KeyMapScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var selectedPlatform by remember { mutableStateOf(GamePlatform.NES) }
    var currentPlayer by remember { mutableStateOf(0) } // 0-indexed: 0=1P, 1=2P, 2=3P, 3=4P
    var capturingActionId by remember { mutableStateOf<String?>(null) }

    // ★★ v60 Neon 主题适配（用户反馈"neno 的设置里按键映射主题还是 fsd"）：
    //   Neon 新 UI 模式下本页仍用 FSD 像素风浅色背板 + 深色文字 —— 现按
    //   全局风格切换：Neon = 深空背板 + 白字；FSD = 原像素风浅色不变。
    NeonUi.ensureLoaded(context)
    val isNeon = NeonUi.isNeon
    val titleColor = if (isNeon) Color.White else Color(0xFF1E2A3A)
    val subtitleColor = if (isNeon) Color(0x99FFFFFF) else Color(0xFF4A5568)

    // Detect TV mode to show a hint
    val isTv = remember {
        !context.packageManager.hasSystemFeature(
            android.content.pm.PackageManager.FEATURE_TOUCHSCREEN
        )
    }

    // Max players supported by this platform (same logic as EmulatorScreen)
    val maxPlayers = when (selectedPlatform) {
        GamePlatform.ARCADE -> 4
        GamePlatform.DOS, GamePlatform.JAVA, GamePlatform.GB, GamePlatform.GBA -> 1
        else -> 2
    }
    // Clamp player if platform changed to one with fewer players
    if (currentPlayer >= maxPlayers) currentPlayer = 0

    val actions = actionsFor(selectedPlatform, currentPlayer)

    Box(modifier = Modifier.fillMaxSize()) {
        // ★★ v60：Neon 模式用深空背板（与 NeonSettingsScreen 同款）；
        //   FSD 模式保持像素风背板不变。
        if (!AppBackgroundState.active) {
            if (isNeon) NeonBackdrop() else PixelBackdrop()
        }
        Column(modifier = Modifier.fillMaxSize().padding(top = 24.dp)) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                FocusableBackButton(onBack, isNeon)
                Spacer(Modifier.size(8.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text("按键映射", color = titleColor, fontSize = 22.sp, fontWeight = FontWeight.ExtraBold)
                    if (isTv) {
                        Text(
                            "TV 模式 · 选择核心和玩家后按 OK 键重映射",
                            color = subtitleColor,
                            fontSize = 11.sp
                        )
                    }
                }
            }

            // Platform selector tabs
            LazyRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(
                    listOf(
                        GamePlatform.NES to "NES",
                        GamePlatform.SFC to "SFC",
                        GamePlatform.GBA to "GBA",
                        GamePlatform.GB to "GB/GBC",
                        GamePlatform.MD to "MD",
                        GamePlatform.PCE to "PCE",
                        GamePlatform.NDS to "NDS",
                        GamePlatform.PSX to "PSX",
                        GamePlatform.PS2 to "PS2",
                        GamePlatform.ARCADE to "街机",
                        GamePlatform.DOS to "DOS",
                        GamePlatform.JAVA to "Java",
                        GamePlatform.DC to "DC",
                        GamePlatform.N3DS to "3DS",
                        GamePlatform.NGCWII to "NGC/WII",
                        // ★★ v60 补齐缺失入口：PSP/WiiU/Switch 此前根本没有
                        //   tab —— 表存在但用户无法进入（"所有核心的按键映射"
                        //   检查不到这三个平台）。
                        GamePlatform.PSP to "PSP",
                        GamePlatform.WIIU to "WIIU",
                        GamePlatform.NSWITCH to "Switch"
                    )
                ) { (platform, label) ->
                    PlatformTab(
                        label = label,
                        selected = selectedPlatform == platform,
                        onClick = { selectedPlatform = platform },
                        isNeon = isNeon
                    )
                }
            }

            // Player selector tabs (only show when maxPlayers > 1)
            if (maxPlayers > 1) {
                LazyRow(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(maxPlayers) { playerIdx ->
                        PlayerTab(
                            label = "${playerIdx + 1}P",
                            selected = currentPlayer == playerIdx,
                            onClick = { currentPlayer = playerIdx },
                            isNeon = isNeon
                        )
                    }
                }
            }

            // Action list
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 8.dp)
            ) {
                item {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(start = 8.dp, end = 8.dp, bottom = 4.dp)
                    ) {
                        Icon(
                            Icons.Rounded.Gamepad,
                            contentDescription = null,
                            tint = subtitleColor,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(Modifier.size(6.dp))
                        Text(
                            "${selectedPlatform.displayName} · ${(currentPlayer + 1)}P 按键 · 点击右侧按键框重映射",
                            color = subtitleColor,
                            fontSize = 12.sp
                        )
                    }
                }
                items(actions, key = { it.id }) { action ->
                    KeyMapRow(
                        action = action,
                        currentLabel = action.keyLabel(context),
                        isCapturing = capturingActionId == action.id,
                        isNeon = isNeon,
                        onClick = {
                            capturingActionId = if (capturingActionId == action.id) null else action.id
                        }
                    )
                }
                item {
                    // Reset button
                    Spacer(Modifier.size(8.dp))
                    ResetAllButton(
                        isNeon = isNeon,
                        onClick = {
                            actions.forEach { KeyMapStore.remove(context, it.id) }
                        }
                    )
                }
            }
        }
    }

    // Key capture overlay — when capturingActionId != null, the user presses
    // any physical key to assign it. Uses an AndroidView with a View.OnKeyListener
    // for maximum reliability across Compose versions.
    if (capturingActionId != null) {
        val captureId = capturingActionId!!
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xCC000000)),
            contentAlignment = Alignment.Center
        ) {
            AndroidView(
                factory = { ctx ->
                    View(ctx).apply {
                        isFocusable = true
                        isFocusableInTouchMode = true
                        requestFocus()
                        setOnKeyListener { _, keyCode, event ->
                            if (event.action == KeyEvent.ACTION_DOWN) {
                                if (keyCode == KeyEvent.KEYCODE_BACK) {
                                    capturingActionId = null
                                    true
                                } else {
                                    KeyMapStore.put(context, captureId, keyCode)
                                    capturingActionId = null
                                    true
                                }
                            } else {
                                false
                            }
                        }
                    }
                },
                modifier = Modifier.fillMaxSize()
            )
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    Icons.Rounded.Keyboard,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(48.dp)
                )
                Spacer(Modifier.size(12.dp))
                Text(
                    "按下想要绑定的按键…",
                    color = Color.White,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.size(4.dp))
                Text(
                    "按 Back / 返回 键取消",
                    color = Color.White.copy(alpha = 0.7f),
                    fontSize = 12.sp
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Row components
// ---------------------------------------------------------------------------
@Composable
private fun PlatformTab(label: String, selected: Boolean, onClick: () -> Unit, isNeon: Boolean = false) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(20.dp))
            .background(
                if (selected) Color(0xFF1E2A3A)
                else if (focused) Color.White.copy(alpha = 0.85f)
                else if (isNeon) Color(0x1FFFFFFF) else Color.White.copy(alpha = 0.5f)
            )
            .border(
                width = if (focused) 2.dp else 0.dp,
                color = if (focused) Color(0xFF8A7BFF) else Color.Transparent,
                shape = RoundedCornerShape(20.dp)
            )
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .focusable(interactionSource = interaction)
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Text(
            label,
            color = if (selected) Color.White else if (isNeon) Color.White else Color(0xFF1E2A3A),
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold
        )
    }
}

@Composable
private fun PlayerTab(label: String, selected: Boolean, onClick: () -> Unit, isNeon: Boolean = false) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(20.dp))
            .background(
                if (selected) Color(0xFFE74C3C)
                else if (focused) Color.White.copy(alpha = 0.85f)
                else if (isNeon) Color(0x1FFFFFFF) else Color.White.copy(alpha = 0.4f)
            )
            .border(
                width = if (focused) 2.dp else 0.dp,
                color = if (focused) Color(0xFF8A7BFF) else Color.Transparent,
                shape = RoundedCornerShape(20.dp)
            )
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .focusable(interactionSource = interaction)
            .padding(horizontal = 14.dp, vertical = 6.dp)
    ) {
        Text(
            label,
            color = if (selected) Color.White else if (isNeon) Color.White else Color(0xFF1E2A3A),
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold
        )
    }
}

@Composable
private fun KeyMapRow(
    action: KeyAction,
    currentLabel: String,
    isCapturing: Boolean,
    onClick: () -> Unit,
    isNeon: Boolean = false
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(
                if (isCapturing) Color(0xFF8A7BFF).copy(alpha = 0.2f)
                else if (focused) Color.White.copy(alpha = if (isNeon) 0.18f else 0.85f)
                else Color.White.copy(alpha = if (isNeon) 0.08f else 0.7f)
            )
            .border(
                width = if (isCapturing || focused) 2.dp else 0.dp,
                color = if (isCapturing) Color(0xFF8A7BFF)
                else if (focused) Color(0xFF4F8AC4)
                else Color.Transparent,
                shape = RoundedCornerShape(14.dp)
            )
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .focusable(interactionSource = interaction)
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .background(action.accent, RoundedCornerShape(50))
        )
        Spacer(Modifier.size(12.dp))
        Text(
            action.name,
            color = if (isNeon) Color.White else Color(0xFF1E2A3A),
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f)
        )
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(10.dp))
                .background(
                    if (isCapturing) Color(0xFF8A7BFF)
                    else action.accent.copy(alpha = if (isNeon) 0.22f else 0.15f)
                )
                .padding(horizontal = 12.dp, vertical = 6.dp)
        ) {
            Text(
                if (isCapturing) "等待按键…" else currentLabel,
                color = if (isCapturing) Color.White else if (isNeon) Color.White else action.accent,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

@Composable
private fun ResetAllButton(onClick: () -> Unit, isNeon: Boolean = false) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(
                if (focused) Color(0xFFE74C3C).copy(alpha = 0.85f)
                else Color(0xFFE74C3C).copy(alpha = 0.15f)
            )
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .focusable(interactionSource = interaction)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            "重置当前核心为默认按键",
            color = if (focused) Color.White else Color(0xFFE74C3C),
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold
        )
    }
}

@Composable
private fun FocusableBackButton(onBack: () -> Unit, isNeon: Boolean = false) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(
                if (focused) Color.White.copy(alpha = if (isNeon) 0.18f else 0.8f)
                else Color.Transparent
            )
            .clickable(interactionSource = interaction, indication = null, onClick = onBack)
            .focusable(interactionSource = interaction),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            Icons.AutoMirrored.Rounded.ArrowBack,
            contentDescription = "返回",
            tint = if (isNeon) Color.White else Color(0xFF1E2A3A)
        )
    }
}
