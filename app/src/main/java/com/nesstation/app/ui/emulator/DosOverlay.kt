package com.nesstation.app.ui.emulator

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nesstation.app.core.engine.DosEngine
import com.nesstation.app.core.jni.DosKeys
import com.nesstation.app.core.storage.ButtonLayout
import com.nesstation.app.core.storage.PadLayout
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

// ===========================================================================
// DOS On-Screen Controller - two modes:
//   1. "gamepad" mode: transparent circular buttons (matches screenshots
//      from ponyemu.main): D-pad on the left, action buttons (Esc/Enter/Space/
//      Tab/Ctrl/Alt/Shift), mouse buttons (L/R/Middle), and a mode switch key.
//   2. "keyboard" mode: full QWERTY capsule-style transparent keyboard with
//      function keys, modifier keys, and arrow cluster.
// Both modes:
//   - Have transparent backgrounds (alpha = padLayout.opacity * 0.5)
//   - Work in both landscape AND portrait (auto-repositions based on isPortrait)
//   - Support multi-touch (one pointer per button)
//   - Dispatch events through DosEngine's inject* methods
//   - Full-screen drag area moves the mouse cursor
//   - Single-tap on empty screen = left mouse click
//   - Two-finger tap on empty screen = right mouse click
// ===========================================================================

// Gamepad bit constants (used for the standard libretro gamepad input - the
// dosbox_pure core's auto-mapping converts these to DOS keys). These are
// prefixed with DOS_ because EmulatorScreen.kt already declares BTN_* in the
// same package, and the bit layout differs for DOS (X/Y at bits 10/11 and
// L/R at bits 8/9).
private const val DOS_BTN_UP = 0x10
private const val DOS_BTN_DOWN = 0x20
private const val DOS_BTN_LEFT = 0x40
private const val DOS_BTN_RIGHT = 0x80
private const val DOS_BTN_A = 0x01      // dosbox_pure -> Enter
private const val DOS_BTN_B = 0x02      // dosbox_pure -> Esc
private const val DOS_BTN_X = 0x400     // dosbox_pure -> Space (libretro ID 10)
private const val DOS_BTN_Y = 0x800     // dosbox_pure -> Tab   (libretro ID 11)
private const val DOS_BTN_L = 0x100     // dosbox_pure -> mouse left  (libretro ID 8)
private const val DOS_BTN_R = 0x200     // dosbox_pure -> mouse right (libretro ID 9)
private const val DOS_BTN_START = 0x08
private const val DOS_BTN_SELECT = 0x04

/**
 * The DOS on-screen controller - entry point. Switches between gamepad and
 * keyboard modes based on `padLayout.dosInputMode`. The mode toggle button is
 * always visible (top-right corner).
 */
@Composable
fun DosOnScreenController(
    engine: DosEngine,
    padLayout: PadLayout,
    surfaceSize: IntSize,
    isPortrait: Boolean,
    onToggleMode: () -> Unit,
    /** 按钮主题（游戏手柄模式的按下高亮色；键盘模式暂用默认）。 */
    overlayTheme: com.nesstation.app.core.storage.OverlayTheme = com.nesstation.app.core.storage.OverlayTheme()
) {
    val opacity = (padLayout.opacity * 0.5f).coerceIn(0.15f, 0.85f)

    Box(modifier = Modifier.fillMaxSize()) {
        // === Render the overlay FIRST, then ModeSwitchButton ON TOP. ===
        // The overlay's full-screen drag area uses awaitFirstDown(requireUnconsumed=true),
        // so it only intercepts touches that miss buttons. By rendering the
        // overlay first and ModeSwitchButton last, the button is on top of
        // the z-order and receives touch events first.
        if (padLayout.dosInputMode == "keyboard") {
            DosKeyboardOverlay(
                engine = engine,
                padLayout = padLayout,
                opacity = opacity,
                isPortrait = isPortrait,
                surfaceSize = surfaceSize
            )
        } else {
            DosGamepadOverlay(
                engine = engine,
                padLayout = padLayout,
                opacity = opacity,
                isPortrait = isPortrait,
                surfaceSize = surfaceSize,
                overlayTheme = overlayTheme
            )
        }

        // Mode switch button - top-right corner, always visible (ON TOP).
        ModeSwitchButton(
            isKeyboard = padLayout.dosInputMode == "keyboard",
            opacity = opacity,
            onClick = onToggleMode,
            modifier = Modifier.align(Alignment.TopEnd).padding(16.dp)
        )
    }
}

// ===========================================================================
// Shared full-screen mouse gesture handler.
//
// Handles three gestures on empty screen area (touches that miss buttons):
//   1. Drag (move)     -> injectMouseMove(dx, dy)  [single finger drag]
//   2. Single-tap      -> left mouse click (down + up)
//   3. Two-finger tap  -> right mouse click (down + up)
//
// DESIGN: Single awaitEachGesture loop.  We use awaitFirstDown with
// requireUnconsumed=false and then check isConsumed, because some Compose
// versions / layouts have issues with requireUnconsumed=true not firing
// for unconsumed events on sibling elements behind buttons.
//
// Tap detection strategy (SIMPLE & ROBUST):
//   - If primary pointer lifts WITHOUT having entered drag mode → tap
//   - No timing check — if finger didn't drag, it's a tap regardless of hold time
//   - Drag mode starts when movement exceeds dragSlopPx (24dp, generous for imprecise taps)
//   - Two-finger tap: second finger arrives while primary is down, neither drags → right click
// ===========================================================================

@Composable
private fun FullScreenMouseGestureBox(
    engine: DosEngine,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                val dragSlopPx = 24.dp.toPx()    // movement threshold to start drag
                val sensitivity = 1.5f           // mouse speed multiplier

                awaitEachGesture {
                    // Use requireUnconsumed=false to ensure we get the event,
                    // then skip if a button at higher z-order already consumed it.
                    val firstDown = awaitFirstDown(requireUnconsumed = false)
                    if (firstDown.isConsumed) return@awaitEachGesture
                    firstDown.consume()

                    val primaryId = firstDown.id.value  // Long for reliable comparison
                    val primaryDownX = firstDown.position.x
                    val primaryDownY = firstDown.position.y
                    var prevX = primaryDownX
                    var prevY = primaryDownY
                    var isDrag = false
                    // Accumulate movement while in slop zone, so when we first
                    // cross the slop threshold we can inject the full displacement.
                    var accumDx = 0f
                    var accumDy = 0f

                    // Secondary pointer tracking (for two-finger tap → right click)
                    var secondPointerId: Long? = null

                    // Process events until primary pointer lifts
                    while (true) {
                        val event = awaitPointerEvent()
                        var primaryUp = false

                        for (change in event.changes) {
                            if (change.id.value == primaryId) {
                                if (change.pressed) {
                                    // Primary pointer MOVE
                                    val dx = change.position.x - prevX
                                    val dy = change.position.y - prevY
                                    accumDx += dx
                                    accumDy += dy
                                    prevX = change.position.x
                                    prevY = change.position.y

                                    if (!isDrag) {
                                        val totalDx = change.position.x - primaryDownX
                                        val totalDy = change.position.y - primaryDownY
                                        if (hypot(totalDx, totalDy) > dragSlopPx) {
                                            // Crossed drag threshold — inject accumulated displacement
                                            isDrag = true
                                            engine.injectMouseMove(
                                                (accumDx * sensitivity).toInt(),
                                                (accumDy * sensitivity).toInt()
                                            )
                                        }
                                    } else {
                                        // Already in drag mode — inject incremental move
                                        if (dx != 0f || dy != 0f) {
                                            engine.injectMouseMove(
                                                (dx * sensitivity).toInt(),
                                                (dy * sensitivity).toInt()
                                            )
                                        }
                                    }
                                } else {
                                    // Primary pointer UP
                                    primaryUp = true
                                }
                            } else if (change.pressed && !isDrag && secondPointerId == null) {
                                // Second finger arrived while not dragging — potential two-finger tap
                                secondPointerId = change.id.value
                            }
                            change.consume()
                        }

                        if (primaryUp) break
                    }

                    // ── Primary pointer has lifted.  Decide: tap or drag? ──
                    if (!isDrag) {
                        // No significant movement → it's a tap
                        if (secondPointerId != null) {
                            // Two-finger tap → right click
                            engine.injectMouseButton(1, true)
                            engine.injectMouseButton(1, false)
                        } else {
                            // Single tap → left click
                            engine.injectMouseButton(0, true)
                            engine.injectMouseButton(0, false)
                        }
                    }
                    // If isDrag, we've already been injecting mouse moves — nothing more to do.
                }
            }
    )
}

// ===========================================================================
// Gamepad overlay - circular transparent buttons, matches screenshot style.
// Layout:
//   Left side: D-pad cross (up/down/left/right)
//   Right side: 4 circular action buttons in a diamond: Esc/Enter/Space/Tab
//   Right side (above action): Mouse L/R buttons (small circular)
//   Bottom-center: Ctrl/Alt/Shift/Back pill buttons
//   Empty screen area: drag to move mouse, single-tap = left click,
//                       two-finger tap = right click
// ===========================================================================

@Composable
private fun DosGamepadOverlay(
    engine: DosEngine,
    padLayout: PadLayout,
    opacity: Float,
    isPortrait: Boolean,
    surfaceSize: IntSize,
    overlayTheme: com.nesstation.app.core.storage.OverlayTheme = com.nesstation.app.core.storage.OverlayTheme()
) {
    val density = LocalDensity.current
    val bgColor = Color.Black.copy(alpha = opacity * 0.5f)
    val fgColor = Color.White.copy(alpha = opacity)
    // DOS 手柄模式所有按键共用按下高亮色；用户配置了 A 键或十字键主题时替换。
    val pressedHighlightRaw = overlayTheme.buttons["a"]?.pressedColor
        ?: overlayTheme.buttons["a"]?.color
        ?: overlayTheme.buttons["dpad"]?.pressedColor
        ?: 0xFFFFD66B
    val pressedColor = Color(pressedHighlightRaw).copy(alpha = opacity)
    val borderColor = Color.White.copy(alpha = opacity * 0.6f)

    var dpadState by remember { mutableStateOf(0) }
    var pressedKeys by remember { mutableStateOf(setOf<Int>()) }
    var mouseLeft by remember { mutableStateOf(false) }
    var mouseRight by remember { mutableStateOf(false) }

    fun pushDpad() {
        val bits = dpadState and 0xF0
        engine.setPad1(bits)
    }

    fun keyDown(code: Int) {
        pressedKeys = pressedKeys + code
        engine.injectKeyDown(code, 0)
    }
    fun keyUp(code: Int) {
        pressedKeys = pressedKeys - code
        engine.injectKeyUp(code, 0)
    }

    // Helper: compute absolute pixel offset for a ButtonLayout centered at (x,y).
    fun btnOffset(layout: ButtonLayout): androidx.compose.ui.unit.IntOffset {
        val sizePx = with(density) { layout.sizeDp.dp.toPx() }
        val px = surfaceSize.width * layout.x - sizePx / 2
        val py = surfaceSize.height * layout.y - sizePx / 2
        return androidx.compose.ui.unit.IntOffset(px.toInt(), py.toInt())
    }

    // Select landscape or portrait layout for each button.
    val dpadL = if (isPortrait) padLayout.dosDpadP else padLayout.dosDpad
    val escL = if (isPortrait) padLayout.dosBtnEscP else padLayout.dosBtnEsc
    val enterL = if (isPortrait) padLayout.dosBtnEnterP else padLayout.dosBtnEnter
    val spaceL = if (isPortrait) padLayout.dosBtnSpaceP else padLayout.dosBtnSpace
    val tabL = if (isPortrait) padLayout.dosBtnTabP else padLayout.dosBtnTab
    val ctrlL = if (isPortrait) padLayout.dosBtnCtrlP else padLayout.dosBtnCtrl
    val altL = if (isPortrait) padLayout.dosBtnAltP else padLayout.dosBtnAlt
    val shiftL = if (isPortrait) padLayout.dosBtnShiftP else padLayout.dosBtnShift
    val backL = if (isPortrait) padLayout.dosBtnBackP else padLayout.dosBtnBack
    val mouseLL = if (isPortrait) padLayout.dosBtnMouseLP else padLayout.dosBtnMouseL
    val mouseRL = if (isPortrait) padLayout.dosBtnMouseRP else padLayout.dosBtnMouseR
    // Extra key buttons (addable via editor)
    val insertL = if (isPortrait) padLayout.dosBtnInsertP else padLayout.dosBtnInsert
    val deleteL = if (isPortrait) padLayout.dosBtnDeleteP else padLayout.dosBtnDelete
    val homeL = if (isPortrait) padLayout.dosBtnHomeP else padLayout.dosBtnHome
    val endL = if (isPortrait) padLayout.dosBtnEndP else padLayout.dosBtnEnd
    val pageUpL = if (isPortrait) padLayout.dosBtnPageUpP else padLayout.dosBtnPageUp
    val pageDownL = if (isPortrait) padLayout.dosBtnPageDownP else padLayout.dosBtnPageDown

    Box(modifier = Modifier.fillMaxSize()) {
        // === Full-screen mouse gesture area (BOTTOM of z-order) ===
        // Handles: drag-to-move-mouse, single-tap=left-click, 2-finger-tap=right-click.
        // Uses requireUnconsumed=true so buttons on top get first touch.
        FullScreenMouseGestureBox(engine = engine)

        // === D-pad ===
        if (padLayout.dosShowDpad) {
            val (dpadImg, dpadImgPressed) = rememberThemeButtonImages(overlayTheme, "dpad")
            CircularDpad(
                sizeDp = dpadL.sizeDp.dp,
                bgColor = bgColor,
                fgColor = fgColor,
                borderColor = borderColor,
                pressedColor = pressedColor,
                pressed = dpadState,
                onPressedChange = { dir, pressed ->
                    val bit = when (dir) {
                        "up" -> DOS_BTN_UP; "down" -> DOS_BTN_DOWN
                        "left" -> DOS_BTN_LEFT; "right" -> DOS_BTN_RIGHT
                        else -> 0
                    }
                    dpadState = if (pressed) dpadState or bit else dpadState and bit.inv()
                    pushDpad()
                },
                modifier = Modifier.offset { btnOffset(dpadL) },
                image = dpadImg,
                pressedImage = dpadImgPressed,
                opacity = opacity
            )
        }

        // === Action buttons (Esc/Enter/Space/Tab) - each independently positioned ===
        if (padLayout.dosShowEsc) {
            CircularKeyButton(
                label = "Esc",
                keyCode = DosKeys.ESCAPE,
                sizeDp = escL.sizeDp.dp,
                bgColor = bgColor, fgColor = fgColor, borderColor = borderColor,
                pressedColor = pressedColor, pressed = DosKeys.ESCAPE in pressedKeys,
                onPressedChange = { p -> if (p) keyDown(DosKeys.ESCAPE) else keyUp(DosKeys.ESCAPE) },
                modifier = Modifier.offset { btnOffset(escL) }
            )
        }
        if (padLayout.dosShowEnter) {
            CircularKeyButton(
                label = "Enter",
                keyCode = DosKeys.RETURN,
                sizeDp = enterL.sizeDp.dp,
                bgColor = bgColor, fgColor = fgColor, borderColor = borderColor,
                pressedColor = pressedColor, pressed = DosKeys.RETURN in pressedKeys,
                onPressedChange = { p -> if (p) keyDown(DosKeys.RETURN) else keyUp(DosKeys.RETURN) },
                modifier = Modifier.offset { btnOffset(enterL) }
            )
        }
        if (padLayout.dosShowSpace) {
            CircularKeyButton(
                label = "Space",
                keyCode = DosKeys.SPACE,
                sizeDp = spaceL.sizeDp.dp,
                bgColor = bgColor, fgColor = fgColor, borderColor = borderColor,
                pressedColor = pressedColor, pressed = DosKeys.SPACE in pressedKeys,
                onPressedChange = { p -> if (p) keyDown(DosKeys.SPACE) else keyUp(DosKeys.SPACE) },
                modifier = Modifier.offset { btnOffset(spaceL) }
            )
        }
        if (padLayout.dosShowTab) {
            CircularKeyButton(
                label = "Tab",
                keyCode = DosKeys.TAB,
                sizeDp = tabL.sizeDp.dp,
                bgColor = bgColor, fgColor = fgColor, borderColor = borderColor,
                pressedColor = pressedColor, pressed = DosKeys.TAB in pressedKeys,
                onPressedChange = { p -> if (p) keyDown(DosKeys.TAB) else keyUp(DosKeys.TAB) },
                modifier = Modifier.offset { btnOffset(tabL) }
            )
        }

        // === Modifier pills (Ctrl/Alt/Shift/Back) - each independently positioned ===
        if (padLayout.dosShowCtrl) {
            PillKeyButton("Ctrl", DosKeys.LCTRL, bgColor, fgColor, borderColor,
                pressedColor, DosKeys.LCTRL in pressedKeys,
                onPressedChange = { p -> if (p) keyDown(DosKeys.LCTRL) else keyUp(DosKeys.LCTRL) },
                modifier = Modifier.offset { btnOffset(ctrlL) })
        }
        if (padLayout.dosShowAlt) {
            PillKeyButton("Alt", DosKeys.LALT, bgColor, fgColor, borderColor,
                pressedColor, DosKeys.LALT in pressedKeys,
                onPressedChange = { p -> if (p) keyDown(DosKeys.LALT) else keyUp(DosKeys.LALT) },
                modifier = Modifier.offset { btnOffset(altL) })
        }
        if (padLayout.dosShowShift) {
            PillKeyButton("Shift", DosKeys.LSHIFT, bgColor, fgColor, borderColor,
                pressedColor, DosKeys.LSHIFT in pressedKeys,
                onPressedChange = { p -> if (p) keyDown(DosKeys.LSHIFT) else keyUp(DosKeys.LSHIFT) },
                modifier = Modifier.offset { btnOffset(shiftL) })
        }
        if (padLayout.dosShowBack) {
            PillKeyButton("Back", DosKeys.BACKSPACE, bgColor, fgColor, borderColor,
                pressedColor, DosKeys.BACKSPACE in pressedKeys,
                onPressedChange = { p -> if (p) keyDown(DosKeys.BACKSPACE) else keyUp(DosKeys.BACKSPACE) },
                modifier = Modifier.offset { btnOffset(backL) })
        }

        // === Mouse L/R buttons - each independently positioned ===
        if (padLayout.dosShowMouseL) {
            MouseKeyButton("L", mouseLeft, bgColor, fgColor, borderColor, pressedColor,
                onPressedChange = { p ->
                    mouseLeft = p
                    engine.injectMouseButton(0, p)
                },
                modifier = Modifier.offset { btnOffset(mouseLL) })
        }
        if (padLayout.dosShowMouseR) {
            MouseKeyButton("R", mouseRight, bgColor, fgColor, borderColor, pressedColor,
                onPressedChange = { p ->
                    mouseRight = p
                    engine.injectMouseButton(1, p)
                },
                modifier = Modifier.offset { btnOffset(mouseRL) })
        }

        // === Extra key buttons (addable via editor, each independently positioned) ===
        if (padLayout.dosShowInsert) {
            CircularKeyButton(
                label = "Ins", keyCode = DosKeys.INSERT,
                sizeDp = insertL.sizeDp.dp,
                bgColor = bgColor, fgColor = fgColor, borderColor = borderColor,
                pressedColor = pressedColor, pressed = DosKeys.INSERT in pressedKeys,
                onPressedChange = { p -> if (p) keyDown(DosKeys.INSERT) else keyUp(DosKeys.INSERT) },
                modifier = Modifier.offset { btnOffset(insertL) }
            )
        }
        if (padLayout.dosShowDelete) {
            CircularKeyButton(
                label = "Del", keyCode = DosKeys.DELETE,
                sizeDp = deleteL.sizeDp.dp,
                bgColor = bgColor, fgColor = fgColor, borderColor = borderColor,
                pressedColor = pressedColor, pressed = DosKeys.DELETE in pressedKeys,
                onPressedChange = { p -> if (p) keyDown(DosKeys.DELETE) else keyUp(DosKeys.DELETE) },
                modifier = Modifier.offset { btnOffset(deleteL) }
            )
        }
        if (padLayout.dosShowHome) {
            CircularKeyButton(
                label = "Home", keyCode = DosKeys.HOME,
                sizeDp = homeL.sizeDp.dp,
                bgColor = bgColor, fgColor = fgColor, borderColor = borderColor,
                pressedColor = pressedColor, pressed = DosKeys.HOME in pressedKeys,
                onPressedChange = { p -> if (p) keyDown(DosKeys.HOME) else keyUp(DosKeys.HOME) },
                modifier = Modifier.offset { btnOffset(homeL) }
            )
        }
        if (padLayout.dosShowEnd) {
            CircularKeyButton(
                label = "End", keyCode = DosKeys.END,
                sizeDp = endL.sizeDp.dp,
                bgColor = bgColor, fgColor = fgColor, borderColor = borderColor,
                pressedColor = pressedColor, pressed = DosKeys.END in pressedKeys,
                onPressedChange = { p -> if (p) keyDown(DosKeys.END) else keyUp(DosKeys.END) },
                modifier = Modifier.offset { btnOffset(endL) }
            )
        }
        if (padLayout.dosShowPageUp) {
            CircularKeyButton(
                label = "PgUp", keyCode = DosKeys.PAGEUP,
                sizeDp = pageUpL.sizeDp.dp,
                bgColor = bgColor, fgColor = fgColor, borderColor = borderColor,
                pressedColor = pressedColor, pressed = DosKeys.PAGEUP in pressedKeys,
                onPressedChange = { p -> if (p) keyDown(DosKeys.PAGEUP) else keyUp(DosKeys.PAGEUP) },
                modifier = Modifier.offset { btnOffset(pageUpL) }
            )
        }
        if (padLayout.dosShowPageDown) {
            CircularKeyButton(
                label = "PgDn", keyCode = DosKeys.PAGEDOWN,
                sizeDp = pageDownL.sizeDp.dp,
                bgColor = bgColor, fgColor = fgColor, borderColor = borderColor,
                pressedColor = pressedColor, pressed = DosKeys.PAGEDOWN in pressedKeys,
                onPressedChange = { p -> if (p) keyDown(DosKeys.PAGEDOWN) else keyUp(DosKeys.PAGEDOWN) },
                modifier = Modifier.offset { btnOffset(pageDownL) }
            )
        }

        // === Dynamic extra keys (letters, numbers, symbols, F-keys, etc.) ===
        // Parsed from dosExtraKeys / dosExtraKeysP JSON string.
        val extraKeysJson = if (isPortrait) padLayout.dosExtraKeysP else padLayout.dosExtraKeys
        val extraKeys = remember(extraKeysJson) {
            com.nesstation.app.core.storage.DosExtraKeyEntry.parseList(extraKeysJson)
        }
        extraKeys.forEach { entry ->
            val entryKeyCode = entry.keyCode
            val entryLabel = entry.label
            val entryLayout = ButtonLayout(x = entry.x, y = entry.y, sizeDp = entry.sizeDp)
            if (entryKeyCode < 0) {
                // Mouse button (e.g., middle click keyCode = -2)
                val btnIdx = when (entryKeyCode) {
                    -2 -> 2  // middle
                    else -> 0
                }
                var mouseBtn by remember { mutableStateOf(false) }
                CircularKeyButton(
                    label = entryLabel, keyCode = 0,
                    sizeDp = entry.sizeDp.dp,
                    bgColor = bgColor, fgColor = fgColor, borderColor = borderColor,
                    pressedColor = pressedColor, pressed = mouseBtn,
                    onPressedChange = { p ->
                        mouseBtn = p
                        engine.injectMouseButton(btnIdx, p)
                    },
                    modifier = Modifier.offset { btnOffset(entryLayout) }
                )
            } else {
                CircularKeyButton(
                    label = entryLabel, keyCode = entryKeyCode,
                    sizeDp = entry.sizeDp.dp,
                    bgColor = bgColor, fgColor = fgColor, borderColor = borderColor,
                    pressedColor = pressedColor, pressed = entryKeyCode in pressedKeys,
                    onPressedChange = { p -> if (p) keyDown(entryKeyCode) else keyUp(entryKeyCode) },
                    modifier = Modifier.offset { btnOffset(entryLayout) }
                )
            }
        }
    }
}


// ===========================================================================
// Keyboard overlay - full QWERTY transparent capsule-style keyboard.
// Layout (5 rows):
//   Row 1: Function keys F1-F12 (small capsules)
//   Row 2: 1-0 + symbols
//   Row 3: QWERTYUIOP + brackets
//   Row 4: ASDFGHJKL; + Enter
//   Row 5: ZXCVBNM,./ + Shift
//   Row 6: Ctrl/Alt/Space/Esc/Tab/Backspace + arrows
//
// Mouse support: full-screen drag area ABOVE the keyboard rows (i.e. on the
// game viewport). Single-tap = left click, two-finger tap = right click,
// drag = mouse move.
// ===========================================================================

@Composable
private fun DosKeyboardOverlay(
    engine: DosEngine,
    padLayout: PadLayout,
    opacity: Float,
    isPortrait: Boolean,
    surfaceSize: IntSize
) {
    val density = LocalDensity.current
    val bgColor = Color.Black.copy(alpha = opacity * 0.5f)
    val fgColor = Color.White.copy(alpha = opacity)
    val borderColor = Color.White.copy(alpha = opacity * 0.6f)
    val pressedColor = Color(0xFFFFD66B).copy(alpha = opacity)

    var pressedKeys by remember { mutableStateOf(setOf<Int>()) }
    var mouseLeft by remember { mutableStateOf(false) }
    var mouseRight by remember { mutableStateOf(false) }

    fun keyDown(code: Int) { pressedKeys = pressedKeys + code; engine.injectKeyDown(code, 0) }
    fun keyUp(code: Int)   { pressedKeys = pressedKeys - code; engine.injectKeyUp(code, 0) }

    val keySize = if (isPortrait) 30.dp else 36.dp
    val keySpacing = 3.dp
    val rowSpacing = 3.dp

    // Helper: compute absolute pixel offset for a ButtonLayout centered at (x,y).
    fun btnOffset(layout: ButtonLayout): androidx.compose.ui.unit.IntOffset {
        val sizePx = with(density) { layout.sizeDp.dp.toPx() }
        val px = surfaceSize.width * layout.x - sizePx / 2
        val py = surfaceSize.height * layout.y - sizePx / 2
        return androidx.compose.ui.unit.IntOffset(px.toInt(), py.toInt())
    }

    // Select landscape or portrait layout for mouse buttons.
    val mouseLL = if (isPortrait) padLayout.dosBtnMouseLP else padLayout.dosBtnMouseL
    val mouseRL = if (isPortrait) padLayout.dosBtnMouseRP else padLayout.dosBtnMouseR

    Box(modifier = Modifier.fillMaxSize()) {
        // === Full-screen mouse gesture area (BOTTOM of z-order) ===
        // Placed BEFORE the keyboard Column so it's underneath. The keyboard
        // rows consume their own touch events; touches on the empty viewport
        // area above the keyboard fall through to this handler.
        //
        // In keyboard mode, this covers the ENTIRE screen so you can drag
        // the mouse anywhere (including above the keyboard rows). Touches on
        // keyboard keys are consumed by the keys and never reach this handler.
        FullScreenMouseGestureBox(engine = engine)

        // === Mouse L/R buttons — positioned on the RIGHT side of screen ===
        // These appear above the keyboard, on the right edge, so the user can
        // hold left/right mouse buttons while using the keyboard with the other hand.
        if (padLayout.dosShowMouseL) {
            MouseKeyButton("L", mouseLeft, bgColor, fgColor, borderColor, pressedColor,
                onPressedChange = { p ->
                    mouseLeft = p
                    engine.injectMouseButton(0, p)
                },
                modifier = Modifier.offset { btnOffset(mouseLL) })
        }
        if (padLayout.dosShowMouseR) {
            MouseKeyButton("R", mouseRight, bgColor, fgColor, borderColor, pressedColor,
                onPressedChange = { p ->
                    mouseRight = p
                    engine.injectMouseButton(1, p)
                },
                modifier = Modifier.offset { btnOffset(mouseRL) })
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter),
            verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(rowSpacing)
        ) {
            // Function keys row (F1-F12)
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(keySpacing)
            ) {
                val fkeys = listOf(
                    "F1" to DosKeys.F1, "F2" to DosKeys.F2, "F3" to DosKeys.F3, "F4" to DosKeys.F4,
                    "F5" to DosKeys.F5, "F6" to DosKeys.F6, "F7" to DosKeys.F7, "F8" to DosKeys.F8,
                    "F9" to DosKeys.F9, "F10" to DosKeys.F10, "F11" to DosKeys.F11, "F12" to DosKeys.F12
                )
                fkeys.forEach { (label, code) ->
                    CapsuleKeyButton(
                        label = label,
                        keyCode = code,
                        modifier = Modifier.weight(1f),
                        heightDp = keySize,
                        bgColor = bgColor, fgColor = fgColor, borderColor = borderColor,
                        pressedColor = pressedColor, pressed = code in pressedKeys,
                        onPressedChange = { p -> if (p) keyDown(code) else keyUp(code) }
                    )
                }
            }

            // Number row: 1 2 3 4 5 6 7 8 9 0 - = Back
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(keySpacing)
            ) {
                val row = listOf(
                    "1" to DosKeys.K1, "2" to DosKeys.K2, "3" to DosKeys.K3,
                    "4" to DosKeys.K4, "5" to DosKeys.K5, "6" to DosKeys.K6,
                    "7" to DosKeys.K7, "8" to DosKeys.K8, "9" to DosKeys.K9,
                    "0" to DosKeys.K0, "-" to DosKeys.MINUS, "=" to DosKeys.EQUALS
                )
                row.forEach { (label, code) ->
                    CapsuleKeyButton(
                        label = label, keyCode = code,
                        modifier = Modifier.weight(1f), heightDp = keySize,
                        bgColor = bgColor, fgColor = fgColor, borderColor = borderColor,
                        pressedColor = pressedColor, pressed = code in pressedKeys,
                        onPressedChange = { p -> if (p) keyDown(code) else keyUp(code) }
                    )
                }
                CapsuleKeyButton(
                    label = "\u232b", keyCode = DosKeys.BACKSPACE,
                    modifier = Modifier.weight(1.5f), heightDp = keySize,
                    bgColor = bgColor, fgColor = fgColor, borderColor = borderColor,
                    pressedColor = pressedColor, pressed = DosKeys.BACKSPACE in pressedKeys,
                    onPressedChange = { p -> if (p) keyDown(DosKeys.BACKSPACE) else keyUp(DosKeys.BACKSPACE) }
                )
            }

            // QWERTY row
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(keySpacing)
            ) {
                val row = listOf(
                    "Q" to DosKeys.Q, "W" to DosKeys.W, "E" to DosKeys.E, "R" to DosKeys.R,
                    "T" to DosKeys.T, "Y" to DosKeys.Y, "U" to DosKeys.U, "I" to DosKeys.I,
                    "O" to DosKeys.O, "P" to DosKeys.P, "[" to DosKeys.LEFTBRACKET,
                    "]" to DosKeys.RIGHTBRACKET, "\\" to DosKeys.BACKSLASH
                )
                row.forEach { (label, code) ->
                    CapsuleKeyButton(
                        label = label, keyCode = code,
                        modifier = Modifier.weight(1f), heightDp = keySize,
                        bgColor = bgColor, fgColor = fgColor, borderColor = borderColor,
                        pressedColor = pressedColor, pressed = code in pressedKeys,
                        onPressedChange = { p -> if (p) keyDown(code) else keyUp(code) }
                    )
                }
            }

            // ASDF row
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(keySpacing)
            ) {
                CapsuleKeyButton(
                    label = "Caps", keyCode = DosKeys.CAPSLOCK,
                    modifier = Modifier.weight(1.5f), heightDp = keySize,
                    bgColor = bgColor, fgColor = fgColor, borderColor = borderColor,
                    pressedColor = pressedColor, pressed = DosKeys.CAPSLOCK in pressedKeys,
                    onPressedChange = { p -> if (p) keyDown(DosKeys.CAPSLOCK) else keyUp(DosKeys.CAPSLOCK) }
                )
                val row = listOf(
                    "A" to DosKeys.A, "S" to DosKeys.S, "D" to DosKeys.D, "F" to DosKeys.F,
                    "G" to DosKeys.G, "H" to DosKeys.H, "J" to DosKeys.J, "K" to DosKeys.K,
                    "L" to DosKeys.L, ";" to DosKeys.SEMICOLON, "'" to DosKeys.APOSTROPHE
                )
                row.forEach { (label, code) ->
                    CapsuleKeyButton(
                        label = label, keyCode = code,
                        modifier = Modifier.weight(1f), heightDp = keySize,
                        bgColor = bgColor, fgColor = fgColor, borderColor = borderColor,
                        pressedColor = pressedColor, pressed = code in pressedKeys,
                        onPressedChange = { p -> if (p) keyDown(code) else keyUp(code) }
                    )
                }
                CapsuleKeyButton(
                    label = "\u23ce", keyCode = DosKeys.RETURN,
                    modifier = Modifier.weight(1.5f), heightDp = keySize,
                    bgColor = bgColor, fgColor = fgColor, borderColor = borderColor,
                    pressedColor = pressedColor, pressed = DosKeys.RETURN in pressedKeys,
                    onPressedChange = { p -> if (p) keyDown(DosKeys.RETURN) else keyUp(DosKeys.RETURN) }
                )
            }

            // ZXCV row
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(keySpacing)
            ) {
                CapsuleKeyButton(
                    label = "Shift", keyCode = DosKeys.LSHIFT,
                    modifier = Modifier.weight(1.5f), heightDp = keySize,
                    bgColor = bgColor, fgColor = fgColor, borderColor = borderColor,
                    pressedColor = pressedColor, pressed = DosKeys.LSHIFT in pressedKeys,
                    onPressedChange = { p -> if (p) keyDown(DosKeys.LSHIFT) else keyUp(DosKeys.LSHIFT) }
                )
                val row = listOf(
                    "Z" to DosKeys.Z, "X" to DosKeys.X, "C" to DosKeys.C, "V" to DosKeys.V,
                    "B" to DosKeys.B, "N" to DosKeys.N, "M" to DosKeys.M,
                    "," to DosKeys.COMMA, "." to DosKeys.PERIOD, "/" to DosKeys.SLASH
                )
                row.forEach { (label, code) ->
                    CapsuleKeyButton(
                        label = label, keyCode = code,
                        modifier = Modifier.weight(1f), heightDp = keySize,
                        bgColor = bgColor, fgColor = fgColor, borderColor = borderColor,
                        pressedColor = pressedColor, pressed = code in pressedKeys,
                        onPressedChange = { p -> if (p) keyDown(code) else keyUp(code) }
                    )
                }
                CapsuleKeyButton(
                    label = "Shift", keyCode = DosKeys.RSHIFT,
                    modifier = Modifier.weight(1.5f), heightDp = keySize,
                    bgColor = bgColor, fgColor = fgColor, borderColor = borderColor,
                    pressedColor = pressedColor, pressed = DosKeys.RSHIFT in pressedKeys,
                    onPressedChange = { p -> if (p) keyDown(DosKeys.RSHIFT) else keyUp(DosKeys.RSHIFT) }
                )
            }

            // Bottom row: Ctrl Alt Space Esc Tab
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(keySpacing)
            ) {
                CapsuleKeyButton(
                    label = "Ctrl", keyCode = DosKeys.LCTRL,
                    modifier = Modifier.weight(1.3f), heightDp = keySize,
                    bgColor = bgColor, fgColor = fgColor, borderColor = borderColor,
                    pressedColor = pressedColor, pressed = DosKeys.LCTRL in pressedKeys,
                    onPressedChange = { p -> if (p) keyDown(DosKeys.LCTRL) else keyUp(DosKeys.LCTRL) }
                )
                CapsuleKeyButton(
                    label = "Alt", keyCode = DosKeys.LALT,
                    modifier = Modifier.weight(1.3f), heightDp = keySize,
                    bgColor = bgColor, fgColor = fgColor, borderColor = borderColor,
                    pressedColor = pressedColor, pressed = DosKeys.LALT in pressedKeys,
                    onPressedChange = { p -> if (p) keyDown(DosKeys.LALT) else keyUp(DosKeys.LALT) }
                )
                CapsuleKeyButton(
                    label = "Space", keyCode = DosKeys.SPACE,
                    modifier = Modifier.weight(4f), heightDp = keySize,
                    bgColor = bgColor, fgColor = fgColor, borderColor = borderColor,
                    pressedColor = pressedColor, pressed = DosKeys.SPACE in pressedKeys,
                    onPressedChange = { p -> if (p) keyDown(DosKeys.SPACE) else keyUp(DosKeys.SPACE) }
                )
                CapsuleKeyButton(
                    label = "Tab", keyCode = DosKeys.TAB,
                    modifier = Modifier.weight(1.3f), heightDp = keySize,
                    bgColor = bgColor, fgColor = fgColor, borderColor = borderColor,
                    pressedColor = pressedColor, pressed = DosKeys.TAB in pressedKeys,
                    onPressedChange = { p -> if (p) keyDown(DosKeys.TAB) else keyUp(DosKeys.TAB) }
                )
                CapsuleKeyButton(
                    label = "Esc", keyCode = DosKeys.ESCAPE,
                    modifier = Modifier.weight(1.3f), heightDp = keySize,
                    bgColor = bgColor, fgColor = fgColor, borderColor = borderColor,
                    pressedColor = pressedColor, pressed = DosKeys.ESCAPE in pressedKeys,
                    onPressedChange = { p -> if (p) keyDown(DosKeys.ESCAPE) else keyUp(DosKeys.ESCAPE) }
                )
            }

            // Arrow keys row
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp),
                horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(keySpacing)
            ) {
                androidx.compose.foundation.layout.Spacer(Modifier.weight(1f))
                CapsuleKeyButton(
                    label = "\u2190", keyCode = DosKeys.LEFT,
                    modifier = Modifier.weight(1f), heightDp = keySize,
                    bgColor = bgColor, fgColor = fgColor, borderColor = borderColor,
                    pressedColor = pressedColor, pressed = DosKeys.LEFT in pressedKeys,
                    onPressedChange = { p -> if (p) keyDown(DosKeys.LEFT) else keyUp(DosKeys.LEFT) }
                )
                CapsuleKeyButton(
                    label = "\u2191", keyCode = DosKeys.UP,
                    modifier = Modifier.weight(1f), heightDp = keySize,
                    bgColor = bgColor, fgColor = fgColor, borderColor = borderColor,
                    pressedColor = pressedColor, pressed = DosKeys.UP in pressedKeys,
                    onPressedChange = { p -> if (p) keyDown(DosKeys.UP) else keyUp(DosKeys.UP) }
                )
                CapsuleKeyButton(
                    label = "\u2193", keyCode = DosKeys.DOWN,
                    modifier = Modifier.weight(1f), heightDp = keySize,
                    bgColor = bgColor, fgColor = fgColor, borderColor = borderColor,
                    pressedColor = pressedColor, pressed = DosKeys.DOWN in pressedKeys,
                    onPressedChange = { p -> if (p) keyDown(DosKeys.DOWN) else keyUp(DosKeys.DOWN) }
                )
                CapsuleKeyButton(
                    label = "\u2192", keyCode = DosKeys.RIGHT,
                    modifier = Modifier.weight(1f), heightDp = keySize,
                    bgColor = bgColor, fgColor = fgColor, borderColor = borderColor,
                    pressedColor = pressedColor, pressed = DosKeys.RIGHT in pressedKeys,
                    onPressedChange = { p -> if (p) keyDown(DosKeys.RIGHT) else keyUp(DosKeys.RIGHT) }
                )
                androidx.compose.foundation.layout.Spacer(Modifier.weight(1f))
            }
        } // end Column
    } // end Box
}

// ===========================================================================
// Button primitives
// ===========================================================================

/** Mode toggle button (top-right corner). Tap to switch between gamepad/keyboard. */
@Composable
private fun ModeSwitchButton(
    isKeyboard: Boolean,
    opacity: Float,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val bgColor = Color.Black.copy(alpha = opacity * 0.6f)
    val fgColor = Color.White.copy(alpha = opacity)
    val borderColor = Color.White.copy(alpha = opacity * 0.7f)
    val label = if (isKeyboard) "\ud83c\udfae" else "\u2328"

    Box(
        modifier = modifier
            .size(44.dp)
            .clip(RoundedCornerShape(22.dp))
            .background(bgColor)
            .border(width = 1.dp, color = borderColor, shape = RoundedCornerShape(22.dp))
            .pointerInput(Unit) {
                detectTapGestures(onTap = { onClick() })
            }
    ) {
        Text(
            text = label,
            color = fgColor,
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            modifier = Modifier.align(Alignment.Center)
        )
    }
}

/**
 * Circular D-pad (cross shape with 4 directional buttons).
 * Drawn with Canvas primitives - transparent background, white border.
 */
@Composable
private fun CircularDpad(
    sizeDp: androidx.compose.ui.unit.Dp,
    bgColor: Color, fgColor: Color, borderColor: Color, pressedColor: Color,
    pressed: Int,
    onPressedChange: (dir: String, pressed: Boolean) -> Unit,
    modifier: Modifier = Modifier,
    image: androidx.compose.ui.graphics.ImageBitmap? = null,
    pressedImage: androidx.compose.ui.graphics.ImageBitmap? = null,
    opacity: Float = 1f
) {
    val density = LocalDensity.current
    val sizePx = with(density) { sizeDp.toPx() }

    val pointers = remember { mutableMapOf<Long, String>() }
    val activeImage = if (pressed != 0) (pressedImage ?: image) else image

    Box(
        modifier = modifier
            .size(sizeDp)
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    down.consume()
                    val id = down.id.value
                    val pos = down.position
                    val dir = hitTestDpad(pos, sizePx)
                    if (dir.isNotEmpty()) {
                        pointers[id] = dir
                        onPressedChange(dir, true)
                    }
                    while (true) {
                        val event = awaitPointerEvent()
                        event.changes.forEach { change ->
                            change.consume()
                            val pid = change.id.value
                            val oldDir = pointers[pid]
                            val newDir = if (change.pressed) hitTestDpad(change.position, sizePx) else ""
                            if (oldDir != newDir) {
                                if (oldDir != null) onPressedChange(oldDir, false)
                                if (newDir.isNotEmpty()) onPressedChange(newDir, true)
                                if (newDir.isEmpty()) pointers.remove(pid) else pointers[pid] = newDir
                            }
                            if (!change.pressed) {
                                oldDir?.let { onPressedChange(it, false) }
                                pointers.remove(pid)
                            }
                        }
                    }
                }
            }
    ) {
        if (activeImage != null) {
            Image(
                bitmap = activeImage,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize().alpha(if (pressed != 0) 0.9f else opacity)
            )
        } else {
            Canvas(modifier = Modifier.fillMaxSize()) {
                drawDpad(sizePx, bgColor, fgColor, borderColor, pressedColor, pressed)
            }
        }
    }
}

/** Hit-test a touch point against the D-pad's 4 quadrants. */
private fun hitTestDpad(pos: Offset, sizePx: Float): String {
    val cx = sizePx / 2; val cy = sizePx / 2
    val dx = pos.x - cx; val dy = pos.y - cy
    val radius = sizePx / 2
    if (hypot(dx, dy) > radius) return ""
    return if (kotlin.math.abs(dx) > kotlin.math.abs(dy)) {
        if (dx > 0) "right" else "left"
    } else {
        if (dy > 0) "down" else "up"
    }
}

/** Draw the D-pad cross using Canvas primitives. */
private fun DrawScope.drawDpad(
    sizePx: Float, bgColor: Color, fgColor: Color,
    borderColor: Color, pressedColor: Color, pressed: Int
) {
    val cx = sizePx / 2; val cy = sizePx / 2
    val armLen = sizePx * 0.42f
    val armW = sizePx * 0.32f
    val r = sizePx * 0.08f

    fun arm(isVertical: Boolean, dir: Int) {
        val isPressed = (pressed and dir) != 0
        val color = if (isPressed) pressedColor else bgColor
        val left: Float; val top: Float; val right: Float; val bottom: Float
        if (isVertical) {
            left = cx - armW/2; right = cx + armW/2
            if (dir == DOS_BTN_UP) { top = cy - armLen; bottom = cy + armW/2 }
            else                   { top = cy - armW/2; bottom = cy + armLen }
        } else {
            top = cy - armW/2; bottom = cy + armW/2
            if (dir == DOS_BTN_LEFT)  { left = cx - armLen; right = cx + armW/2 }
            else                      { left = cx - armW/2; right = cx + armLen }
        }
        drawRoundRect(
            color = color,
            topLeft = Offset(left, top),
            size = androidx.compose.ui.geometry.Size(right - left, bottom - top),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(r, r)
        )
        drawRoundRect(
            color = borderColor,
            topLeft = Offset(left, top),
            size = androidx.compose.ui.geometry.Size(right - left, bottom - top),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(r, r),
            style = Stroke(width = sizePx * 0.02f)
        )
        val tipColor = if (isPressed) Color.Black.copy(alpha = 0.7f) else fgColor
        val arrowSize = sizePx * 0.08f
        when (dir) {
            DOS_BTN_UP -> drawArrow(Offset(cx, cy - armLen + arrowSize*1.5f), 0, -1, arrowSize, tipColor)
            DOS_BTN_DOWN -> drawArrow(Offset(cx, cy + armLen - arrowSize*1.5f), 0, 1, arrowSize, tipColor)
            DOS_BTN_LEFT -> drawArrow(Offset(cx - armLen + arrowSize*1.5f, cy), -1, 0, arrowSize, tipColor)
            DOS_BTN_RIGHT -> drawArrow(Offset(cx + armLen - arrowSize*1.5f, cy), 1, 0, arrowSize, tipColor)
        }
    }
    arm(false, DOS_BTN_LEFT)
    arm(false, DOS_BTN_RIGHT)
    arm(true, DOS_BTN_UP)
    arm(true, DOS_BTN_DOWN)
    drawCircle(fgColor.copy(alpha = 0.5f), radius = sizePx * 0.04f, center = Offset(cx, cy))
}

/** Draw an arrow (triangle) at `pos` pointing in direction (dx, dy). */
private fun DrawScope.drawArrow(pos: Offset, dx: Int, dy: Int, size: Float, color: Color) {
    val path = androidx.compose.ui.graphics.Path().apply {
        moveTo(pos.x, pos.y)
        if (dx != 0) {
            lineTo(pos.x - dx * size, pos.y - size * 0.6f)
            lineTo(pos.x - dx * size, pos.y + size * 0.6f)
        } else {
            lineTo(pos.x - size * 0.6f, pos.y - dy * size)
            lineTo(pos.x + size * 0.6f, pos.y - dy * size)
        }
        close()
    }
    drawPath(path, color)
}

/** Circular key button with a text label (gamepad mode). */
@Composable
private fun CircularKeyButton(
    label: String,
    keyCode: Int,
    sizeDp: androidx.compose.ui.unit.Dp,
    bgColor: Color, fgColor: Color, borderColor: Color, pressedColor: Color,
    pressed: Boolean,
    onPressedChange: (pressed: Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    var currentPointerId by remember { mutableStateOf<Long?>(null) }

    Box(
        modifier = modifier
            .size(sizeDp)
            .pointerInput(keyCode) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    down.consume()
                    if (currentPointerId == null) {
                        currentPointerId = down.id.value
                        onPressedChange(true)
                    }
                    while (true) {
                        val event = awaitPointerEvent()
                        event.changes.forEach { change ->
                            change.consume()
                            if (change.id.value == currentPointerId && !change.pressed) {
                                onPressedChange(false)
                                currentPointerId = null
                            }
                        }
                        if (currentPointerId == null) break
                    }
                }
            }
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val cx = size.width / 2; val cy = size.height / 2
            val r = size.width * 0.46f
            drawCircle(if (pressed) pressedColor else bgColor, r, Offset(cx, cy))
            drawCircle(borderColor, r, Offset(cx, cy), style = Stroke(width = size.width * 0.04f))
            if (!pressed) {
                drawCircle(Color.White.copy(alpha = 0.1f), r * 0.85f,
                    Offset(cx, cy - r * 0.15f))
            }
        }
        Text(
            text = label,
            color = if (pressed) Color.Black.copy(alpha = 0.85f) else fgColor,
            fontSize = (sizeDp.value * 0.28f).sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            modifier = Modifier.align(Alignment.Center)
        )
    }
}

/** Pill-shaped (rounded rectangle) key button. */
@Composable
private fun PillKeyButton(
    label: String,
    keyCode: Int,
    bgColor: Color, fgColor: Color, borderColor: Color, pressedColor: Color,
    pressed: Boolean,
    onPressedChange: (pressed: Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    var currentPointerId by remember { mutableStateOf<Long?>(null) }

    Box(
        modifier = modifier
            .wrapContentSize(Alignment.Center)
            .clip(RoundedCornerShape(20.dp))
            .background(if (pressed) pressedColor else bgColor)
            .border(
                width = 1.dp,
                color = borderColor,
                shape = RoundedCornerShape(20.dp)
            )
            .pointerInput(keyCode) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    down.consume()
                    if (currentPointerId == null) {
                        currentPointerId = down.id.value
                        onPressedChange(true)
                    }
                    while (true) {
                        val event = awaitPointerEvent()
                        event.changes.forEach { change ->
                            change.consume()
                            if (change.id.value == currentPointerId && !change.pressed) {
                                onPressedChange(false)
                                currentPointerId = null
                            }
                        }
                        if (currentPointerId == null) break
                    }
                }
            }
            .padding(horizontal = 14.dp, vertical = 8.dp)
    ) {
        Text(
            text = label,
            color = if (pressed) Color.Black.copy(alpha = 0.85f) else fgColor,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
            modifier = Modifier.align(Alignment.Center)
        )
    }
}

/** Capsule-style key button for the full keyboard overlay. */
@Composable
private fun CapsuleKeyButton(
    label: String,
    keyCode: Int,
    modifier: Modifier = Modifier,
    heightDp: androidx.compose.ui.unit.Dp,
    bgColor: Color, fgColor: Color, borderColor: Color, pressedColor: Color,
    pressed: Boolean,
    onPressedChange: (pressed: Boolean) -> Unit
) {
    var currentPointerId by remember { mutableStateOf<Long?>(null) }

    Box(
        modifier = modifier
            .height(heightDp)
            .clip(RoundedCornerShape(heightDp * 0.3f))
            .background(if (pressed) pressedColor else bgColor)
            .border(
                width = 1.dp,
                color = borderColor,
                shape = RoundedCornerShape(heightDp * 0.3f)
            )
            .pointerInput(keyCode) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    down.consume()
                    if (currentPointerId == null) {
                        currentPointerId = down.id.value
                        onPressedChange(true)
                    }
                    while (true) {
                        val event = awaitPointerEvent()
                        event.changes.forEach { change ->
                            change.consume()
                            if (change.id.value == currentPointerId && !change.pressed) {
                                onPressedChange(false)
                                currentPointerId = null
                            }
                        }
                        if (currentPointerId == null) break
                    }
                }
            }
    ) {
        Text(
            text = label,
            color = if (pressed) Color.Black.copy(alpha = 0.85f) else fgColor,
            fontSize = (heightDp.value * 0.35f).sp,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.Center,
            modifier = Modifier.align(Alignment.Center)
        )
    }
}

/** Mouse button (small circular button with letter label). */
@Composable
private fun MouseKeyButton(
    label: String,
    pressed: Boolean,
    bgColor: Color, fgColor: Color, borderColor: Color, pressedColor: Color,
    onPressedChange: (pressed: Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    var currentPointerId by remember { mutableStateOf<Long?>(null) }
    val sizeDp = 40.dp

    Box(
        modifier = modifier
            .size(sizeDp)
            .pointerInput(label) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    down.consume()
                    if (currentPointerId == null) {
                        currentPointerId = down.id.value
                        onPressedChange(true)
                    }
                    while (true) {
                        val event = awaitPointerEvent()
                        event.changes.forEach { change ->
                            change.consume()
                            if (change.id.value == currentPointerId && !change.pressed) {
                                onPressedChange(false)
                                currentPointerId = null
                            }
                        }
                        if (currentPointerId == null) break
                    }
                }
            }
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val cx = size.width / 2; val cy = size.height / 2
            val r = size.width * 0.46f
            drawCircle(if (pressed) pressedColor else bgColor, r, Offset(cx, cy))
            drawCircle(borderColor, r, Offset(cx, cy), style = Stroke(width = size.width * 0.04f))
        }
        Text(
            text = label,
            color = if (pressed) Color.Black.copy(alpha = 0.85f) else fgColor,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.align(Alignment.Center)
        )
    }
}
