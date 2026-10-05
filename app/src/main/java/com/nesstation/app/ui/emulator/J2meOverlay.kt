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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.changedToDown
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.FastForward
import androidx.compose.material.icons.rounded.Fullscreen
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import com.nesstation.app.core.engine.J2meEngine
import com.nesstation.app.core.storage.ButtonLayout
import com.nesstation.app.core.storage.PadLayout

// ===========================================================================
// J2ME On-Screen Controller — two modes:
//   1. "gamepad" mode: D-pad + A/B/X/Y/Start/Select (standard gamepad).
//      Mirrors the generic OnScreenController visual style.
//   2. "phone"   mode: 12-key numeric keypad (1-9, *, 0, #) + soft keys
//      (SOFT_LEFT / SOFT_RIGHT) + FIRE + END. Mirrors the classic J2ME
//      phone layout that MIDlets expect for T9 input, menu navigation,
//      and number entry.
//
// Both modes:
//   - Transparent backgrounds (alpha = padLayout.opacity * 0.5)
//   - Work in both landscape AND portrait
//   - Support multi-touch (one pointer per button)
//   - Dispatch button state via J2meEngine.setPad1(bits)
//   - Mode toggle button in the top-right corner
// ===========================================================================

// Button bit constants for J2ME. Reuses the generic BTN_* constants for
// gamepad keys (defined in EmulatorScreen.kt) and defines J2ME-specific
// bits for the numeric keypad (bits 16-25, which are unused by BTN_*).
private const val J2ME_BTN_UP = 0x0010
private const val J2ME_BTN_DOWN = 0x0020
private const val J2ME_BTN_LEFT = 0x0040
private const val J2ME_BTN_RIGHT = 0x0080
private const val J2ME_BTN_A = 0x0001          // → Canvas.KEY_FIRE
private const val J2ME_BTN_B = 0x0002          // → Canvas.KEY_SOFT_LEFT
private const val J2ME_BTN_X = 0x0100          // → Canvas.KEY_SOFT_RIGHT
private const val J2ME_BTN_Y = 0x0200          // → Canvas.KEY_STAR
private const val J2ME_BTN_SELECT = 0x0004     // → Canvas.KEY_POUND
private const val J2ME_BTN_START = 0x0008      // → Canvas.KEY_END
private const val J2ME_BTN_NUM_0 = 0x010000
private const val J2ME_BTN_NUM_1 = 0x020000
private const val J2ME_BTN_NUM_2 = 0x040000
private const val J2ME_BTN_NUM_3 = 0x080000
private const val J2ME_BTN_NUM_4 = 0x100000
private const val J2ME_BTN_NUM_5 = 0x200000
private const val J2ME_BTN_NUM_6 = 0x400000
private const val J2ME_BTN_NUM_7 = 0x800000
private const val J2ME_BTN_NUM_8 = 0x1000000
private const val J2ME_BTN_NUM_9 = 0x2000000

// ===========================================================================
// 按钮主题（来自 PadLayout.overlayThemeJson，每核心独立）
// ===========================================================================

/** 从主题解析常规色；未配置用 default。 */
private fun j2meThemeColor(theme: com.nesstation.app.core.storage.OverlayTheme, id: String, default: Color): Color =
    theme.buttons[id]?.color?.let { Color(it) } ?: default

/** 从主题解析按压色；null = 未配置，由绘制函数用默认按压效果。 */
private fun j2meThemePressed(theme: com.nesstation.app.core.storage.OverlayTheme, id: String): Color? =
    theme.buttons[id]?.pressedColor?.let { Color(it) }

/**
 * Entry point for the J2ME on-screen controller. Switches between gamepad
 * and phone modes based on `padLayout.javaInputMode`. The mode toggle
 * button is always visible in the top-right corner.
 */
@Composable
fun J2meOnScreenController(
    engine: J2meEngine,
    padLayout: PadLayout,
    surfaceSize: IntSize,
    isPortrait: Boolean,
    onToggleMode: () -> Unit,
    /**
         * 落在虚拟按键之外（游戏画面区域）的触摸转发器。
         * 参数：根坐标 + MotionEvent action。J2ME 游戏画面（AndroidView）是
         * 本覆盖层的低 z 兄弟节点，Compose 命中测试不会穿透到手柄覆盖层下方，
         * 游戏画面收不到任何触摸 —— 由这里把未命中按键的触摸转发出去
         * （EmulatorScreen 换算成游戏视图局部坐标后注入 Canvas）。
         */
        onUnhandledTouch: ((rootPos: Offset, action: Int, pointerId: Int) -> Unit)? = null,
        /** 手柄模式的按钮主题（dpad/a/b/x/y/start/select；null 键 = 核心默认色）。 */
        overlayTheme: com.nesstation.app.core.storage.OverlayTheme = com.nesstation.app.core.storage.OverlayTheme()
    ) {    // J2ME 专属透明度：不再复用全局 opacity 打对折（旧逻辑 0.7*0.5=0.35，
    // 方向键在深色画面上几乎看不见）。默认 0.8，可在布局编辑器/设置里调。
    val opacity = padLayout.javaOpacity.coerceIn(0.3f, 1f)

    // 手柄模式下的数字小键盘开关（J2ME 游戏经常需要数字输入）
    var showNumPad by remember { mutableStateOf(false) }

    // 数字键兼作方向键：开启后 2/4/6/8/5 在发送数字键的同时追加发送
    // 方向/确认键（真机行为）。由设置里的“数字键兼作方向键”控制，
    // 手柄模式的 "123" 数字小键盘与手机键盘模式同样生效。
    androidx.compose.runtime.LaunchedEffect(padLayout.javaNumDualDispatch) {
        engine.phoneDualDispatch = padLayout.javaNumDualDispatch
    }

    // 覆盖层自身在窗口中的位置 —— 把覆盖层局部坐标换算成根坐标，
    // 再由 EmulatorScreen 减去游戏视图的根坐标得到游戏内局部坐标。
    var padPosInRoot by remember { mutableStateOf(Offset.Zero) }
    val currentOnUnhandledTouch by rememberUpdatedState(onUnhandledTouch)

    Box(modifier = Modifier.fillMaxSize().onGloballyPositioned { coords ->
        padPosInRoot = coords.positionInRoot()
    }) {
        if (padLayout.javaInputMode == "phone") {
            J2mePhoneOverlay(
                engine = engine,
                padLayout = padLayout,
                opacity = opacity,
                isPortrait = isPortrait,
                surfaceSize = surfaceSize,
                padPosInRoot = padPosInRoot,
                onUnhandledTouch = { pos, action, pid ->
                    android.util.Log.d("J2meTouch", "forward phone action=$action pid=$pid root=(${pos.x},${pos.y})")
                    currentOnUnhandledTouch?.invoke(pos, action, pid)
                }
            )
        } else {
            J2meGamepadOverlay(
                engine = engine,
                padLayout = padLayout,
                opacity = opacity,
                isPortrait = isPortrait,
                surfaceSize = surfaceSize,
                showNumPad = showNumPad,
                padPosInRoot = padPosInRoot,
                onUnhandledTouch = { pos, action, pid ->
                    android.util.Log.d("J2meTouch", "forward gamepad action=$action pid=$pid root=(${pos.x},${pos.y})")
                    currentOnUnhandledTouch?.invoke(pos, action, pid)
                },
                overlayTheme = overlayTheme
            )
        }

        // Mode switch button — top-right corner, always on top.
        J2meModeSwitchButton(
            isPhone = padLayout.javaInputMode == "phone",
            opacity = opacity,
            onClick = onToggleMode,
            modifier = Modifier.align(Alignment.TopEnd).padding(16.dp)
        )

        // 手柄模式的 "123" 数字键盘开关 — 在模式切换按钮左侧。
        if (padLayout.javaInputMode != "phone") {
            J2meModeSwitchButton(
                isPhone = showNumPad,
                opacity = opacity,
                onClick = { showNumPad = !showNumPad },
                label = if (showNumPad) "\u2715" else "123",
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 16.dp, end = 72.dp)
            )
        }
    }
}

// ===========================================================================
// Gamepad mode — D-pad + A/B/X/Y/Start/Select
// ===========================================================================

@Composable
private fun J2meGamepadOverlay(
    engine: J2meEngine,
    padLayout: PadLayout,
    opacity: Float,
    isPortrait: Boolean,
    surfaceSize: IntSize,
    showNumPad: Boolean = false,
    /** 覆盖层在窗口中的根坐标（用于把局部坐标换算回根坐标后转发）。 */
    padPosInRoot: Offset = Offset.Zero,
    /** 未命中任何按键的触摸转发器（根坐标 + MotionEvent action）。 */
    onUnhandledTouch: ((rootPos: Offset, action: Int, pointerId: Int) -> Unit)? = null,
    /** 按钮主题（dpad/a/b/x/y/start/select；null 键 = 核心默认色）。 */
    overlayTheme: com.nesstation.app.core.storage.OverlayTheme = com.nesstation.app.core.storage.OverlayTheme()
) {
    val density = LocalDensity.current

    // Reuse the generic PadLayout button positions (landscape/portrait).
    val dpad = if (isPortrait) padLayout.dpadP else padLayout.dpad
    val btnA = if (isPortrait) padLayout.btnAP else padLayout.btnA
    val btnB = if (isPortrait) padLayout.btnBP else padLayout.btnB
    val btnX = if (isPortrait) padLayout.btnXP else padLayout.btnX
    val btnY = if (isPortrait) padLayout.btnYP else padLayout.btnY
    val btnStart = if (isPortrait) padLayout.btnStartP else padLayout.btnStart
    val btnSelect = if (isPortrait) padLayout.btnSelectP else padLayout.btnSelect

    // Track active pointers and compute OR'd bit mask.
    val activePointers = remember { mutableMapOf<Long, Int>() }
    var visualState by remember { mutableStateOf(0) }

    // 落在按键之外的指针 → 游戏画面触摸（转发给 Canvas 注入 J2ME 触屏事件）。
    // 没有这个转发，手柄覆盖层存在时游戏画面收不到任何触摸（Compose
    // 命中测试不穿透到低 z 的 AndroidView），触屏支持形同虚设。
    val unhandledPointers = remember { mutableMapOf<Long, Boolean>() }
    // 每个"游戏画面触摸"指针最近一次的根坐标，手势被取消时用它补发 CANCEL。
    val unhandledPositions = remember { mutableMapOf<Long, Offset>() }
    val currentOnUnhandledTouch by rememberUpdatedState(onUnhandledTouch)

    val sendState = remember(engine) {
        { bits: Int ->
            visualState = bits
            engine.setPad1(bits)
        }
    }

    // 手势以取消/异常结束时调用：给 MIDlet 补发 ACTION_CANCEL 并复位全部状态。
    // 否则按住游戏画面的手指会一直"卡"在 MIDlet 侧（DOWN 已注入、UP 永远收不到），
    // 之后所有触摸都被游戏当作一次持续拖拽丢弃 —— 表现为"触屏点一次就失效，
    // 切一下即时绘制模式/设置才恢复一次"。
    fun releaseAllPointers() {
        val forward = currentOnUnhandledTouch
        for (pid in unhandledPointers.keys.toList()) {
            if (forward != null) {
                forward(
                    unhandledPositions[pid] ?: Offset.Zero,
                    android.view.MotionEvent.ACTION_CANCEL,
                    pid.toInt()
                )
            }
            unhandledPointers.remove(pid)
            unhandledPositions.remove(pid)
        }
        if (activePointers.isNotEmpty()) {
            activePointers.clear()
            sendState(0)
        }
    }

    // Compute hit rects
    fun btnRect(layout: ButtonLayout, widthScale: Float = 1f, heightScale: Float = 1f): Rect {
        val sizePx = with(density) { layout.sizeDp.dp.toPx() }
        val w = sizePx * widthScale
        val h = sizePx * heightScale
        val cx = surfaceSize.width * layout.x
        val cy = surfaceSize.height * layout.y
        return Rect(cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2)
    }

    val dpadRect = btnRect(dpad)
    val aRect = btnRect(btnA)
    val bRect = btnRect(btnB)
    val xRect = btnRect(btnX)
    val yRect = btnRect(btnY)
    val startRect = btnRect(btnStart, 2.2f, 0.7f)
    val selectRect = btnRect(btnSelect, 2.2f, 0.7f)

    // 数字小键盘命中区域（手柄模式按 "123" 按钮后显示）
    val numKeySizeDp = 34.dp
    val numGapDp = 4.dp
    val numKeySizePx = with(density) { numKeySizeDp.toPx() }
    val numGapPx = with(density) { numGapDp.toPx() }
    val numGridWidth = 3 * numKeySizePx + 2 * numGapPx
    val numGridHeight = 4 * numKeySizePx + 3 * numGapPx
    // 居中偏上：横屏垂直居中，竖屏在屏幕中部偏上，避开底部 start/select
    val numCenterY = if (isPortrait) surfaceSize.height * 0.40f else surfaceSize.height * 0.50f
    val numGridLeft = (surfaceSize.width - numGridWidth) / 2f
    val numGridTop = numCenterY - numGridHeight / 2f
    val numGridWidthDp = with(density) { numGridWidth.toDp() }
    val numGridHeightDp = with(density) { numGridHeight.toDp() }

    val numKeys = listOf(
        "1" to J2ME_BTN_NUM_1, "2" to J2ME_BTN_NUM_2, "3" to J2ME_BTN_NUM_3,
        "4" to J2ME_BTN_NUM_4, "5" to J2ME_BTN_NUM_5, "6" to J2ME_BTN_NUM_6,
        "7" to J2ME_BTN_NUM_7, "8" to J2ME_BTN_NUM_8, "9" to J2ME_BTN_NUM_9,
        "*" to J2ME_BTN_Y,      "0" to J2ME_BTN_NUM_0, "#" to J2ME_BTN_SELECT,
    )
    val numKeyRects = numKeys.mapIndexed { idx, _ ->
        val row = idx / 3
        val col = idx % 3
        Rect(
            numGridLeft + col * (numKeySizePx + numGapPx),
            numGridTop + row * (numKeySizePx + numGapPx),
            numGridLeft + col * (numKeySizePx + numGapPx) + numKeySizePx,
            numGridTop + row * (numKeySizePx + numGapPx) + numKeySizePx
        )
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(surfaceSize) {
                awaitEachGesture {
                    try {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        // 记录按下是否已被更高 z 的控件（模式切换 / "123" 按钮）
                        // 消费 —— 已消费的按下属于那些控件自身，不能转发成
                        // 游戏区域触摸，否则点切换按钮会向画面注入一次多余触摸。
                        val downConsumedByOther = down.isConsumed
                        down.consume()
                        val id = down.id.value
                        // 与 OnScreenController 一致：用 pressedCount 跟踪按下的手指数，
                        // 全部抬起后 break 退出 while，让 awaitEachGesture 重新
                        // awaitFirstDown 开启下一轮手势。此前缺少该计数与 break，
                        // while(true) 永不退出：第一根手指抬起后 block 卡在
                        // awaitPointerEvent()，后续点击只能走"手势中途新手指"分支，
                        // 而该分支的 !change.isConsumed 在自身 consume 后恒为 false，
                        // 触摸被静默丢弃 —— 表现为"点一次就失效，切一次设置才恢复"。
                        var pressedCount = 1
                        val bits = hitTestGamepad(
                            down.position, dpadRect, aRect, bRect, xRect, yRect, startRect, selectRect,
                            if (showNumPad) numKeys else null, if (showNumPad) numKeyRects else null
                        )
                        if (bits != 0) {
                            activePointers[id] = bits
                            sendState(combineBits(activePointers))
                            unhandledPointers.remove(id)
                        } else if (!downConsumedByOther && currentOnUnhandledTouch != null) {
                            // 未命中任何按键 → 游戏画面触摸，转发给底层视图
                            unhandledPointers[id] = true
                            unhandledPositions[id] = down.position + padPosInRoot
                            currentOnUnhandledTouch?.invoke(
                                down.position + padPosInRoot,
                                android.view.MotionEvent.ACTION_DOWN,
                                id.toInt()
                            )
                        } else {
                            // 未命中按键且触摸被其他控件消费 / 无转发器：
                            // 不跟踪、不转发
                        }
                        while (true) {
                            val event = awaitPointerEvent()
                            event.changes.forEach { change ->
                                // 在自身 consume 之前捕获"是否被更高 z 的子控件消费"，
                                // 否则下面 !change.isConsumed 恒为 false（已被自身消费），
                                // 手势中途新加入的游戏区域手指会被静默丢弃。
                                val consumedByChild = change.isConsumed
                                change.consume()
                                if (change.changedToDown()) pressedCount++
                                if (change.changedToUp()) pressedCount--
                                val pid = change.id.value
                                val newBits = if (change.pressed) {
                                    hitTestGamepad(
                                        change.position, dpadRect, aRect, bRect, xRect, yRect, startRect, selectRect,
                                        if (showNumPad) numKeys else null, if (showNumPad) numKeyRects else null
                                    )
                                } else 0
                                if (unhandledPointers[pid] == true) {
                                    // 已标记为"游戏画面触摸"的指针：持续转发移动/抬起
                                    if (change.pressed) {
                                        unhandledPositions[pid] = change.position + padPosInRoot
                                        currentOnUnhandledTouch?.invoke(
                                            change.position + padPosInRoot,
                                            android.view.MotionEvent.ACTION_MOVE,
                                            pid.toInt()
                                        )
                                    } else {
                                        currentOnUnhandledTouch?.invoke(
                                            change.position + padPosInRoot,
                                            android.view.MotionEvent.ACTION_UP,
                                            pid.toInt()
                                        )
                                        unhandledPointers.remove(pid)
                                        unhandledPositions.remove(pid)
                                    }
                                    if (!change.pressed) {
                                        activePointers.remove(pid)
                                    }
                                    return@forEach
                                }
                                // 第二/更多根手指在同一手势期间按下（此前未在任何
                                // 按键或游戏区域登记过）：未命中按键且未被更高 z
                                // 控件（模式切换/"123"按钮）消费时，与第一根手指
                                // 走同一规则 —— 标记为"游戏画面触摸"并转发 DOWN，
                                // 后续移动/抬起由上面的 unhandled 分支继续转发。
                                // 此前这段事件被完全吞掉，多点触控游戏收不到后续手指。
                                if (change.pressed && !activePointers.containsKey(pid) &&
                                    newBits == 0 && !consumedByChild &&
                                    currentOnUnhandledTouch != null) {
                                    unhandledPointers[pid] = true
                                    unhandledPositions[pid] = change.position + padPosInRoot
                                    currentOnUnhandledTouch?.invoke(
                                        change.position + padPosInRoot,
                                        android.view.MotionEvent.ACTION_DOWN,
                                        pid.toInt()
                                    )
                                    return@forEach
                                }
                                val oldBits = activePointers[pid] ?: 0
                                if (oldBits != newBits) {
                                    activePointers[pid] = newBits
                                    sendState(combineBits(activePointers))
                                }
                                if (!change.pressed) {
                                    activePointers.remove(pid)
                                    sendState(combineBits(activePointers))
                                }
                            }
                            if (pressedCount <= 0) break
                        }
                    } catch (t: Throwable) {
                        // 手势以取消/异常结束：先给游戏画面触摸补发 CANCEL、复位
                        // 虚拟按键状态，再决定是否重新抛出。否则 MIDlet 侧会一直
                        // 认为手指/按键仍按住（卡触摸/卡按键），之后的触摸全部被
                        // 游戏当作持续拖拽丢弃 —— 表现为"切一次即时绘制模式触屏
                        // 才生效一次"。
                        releaseAllPointers()
                        // 手势结束由 awaitPointerEvent() 抛 CancellationException 表示，
                        // 必须原样抛出交给 awaitEachGesture 处理。其它异常绝不能逃逸：
                        // awaitEachGesture 只捕获 CancellationException，别的异常会让
                        // 整个 pointerInput 协程永久退出 —— 触摸转发就此失效，直到
                        // 控件被重组重建（表现为"改一次设置触屏才多生效一次"）。
                        if (t is java.util.concurrent.CancellationException) throw t
                        android.util.Log.e("J2meOverlay", "gamepad gesture error", t)
                    }
                }
            }
    ) {
        // Draw D-pad
        val (dpadImg, dpadImgPressed) = rememberThemeButtonImages(overlayTheme, "dpad")
        J2meDpadCanvas(
            dpad, surfaceSize, opacity, visualState and 0xF0,
            armColorInput = j2meThemeColor(overlayTheme, "dpad", Color(0xFF39445A)),
            pressedColorInput = j2meThemePressed(overlayTheme, "dpad") ?: Color(0xFFFFD66B),
            image = dpadImg,
            pressedImage = dpadImgPressed
        )
        // Draw A
        val (aImg, aImgPressed) = rememberThemeButtonImages(overlayTheme, "a")
        J2meActionButton(
            "A", Color(0xFFE74C3C), btnA, surfaceSize, opacity, visualState and J2ME_BTN_A != 0,
            pressedColor = j2meThemePressed(overlayTheme, "a"),
            image = aImg,
            pressedImage = aImgPressed
        )
        // Draw B
        val (bImg, bImgPressed) = rememberThemeButtonImages(overlayTheme, "b")
        J2meActionButton(
            "B", Color(0xFFE67E22), btnB, surfaceSize, opacity, visualState and J2ME_BTN_B != 0,
            pressedColor = j2meThemePressed(overlayTheme, "b"),
            image = bImg,
            pressedImage = bImgPressed
        )
        // Draw X
        val (xImg, xImgPressed) = rememberThemeButtonImages(overlayTheme, "x")
        J2meActionButton(
            "X", Color(0xFF3498DB), btnX, surfaceSize, opacity, visualState and J2ME_BTN_X != 0,
            pressedColor = j2meThemePressed(overlayTheme, "x"),
            image = xImg,
            pressedImage = xImgPressed
        )
        // Draw Y
        val (yImg, yImgPressed) = rememberThemeButtonImages(overlayTheme, "y")
        J2meActionButton(
            "Y", Color(0xFF2ECC71), btnY, surfaceSize, opacity, visualState and J2ME_BTN_Y != 0,
            pressedColor = j2meThemePressed(overlayTheme, "y"),
            image = yImg,
            pressedImage = yImgPressed
        )
        // Draw Start
        val (startImg, startImgPressed) = rememberThemeButtonImages(overlayTheme, "start")
        J2mePillButton(
            "START", btnStart, surfaceSize, opacity, visualState and J2ME_BTN_START != 0,
            normalColor = j2meThemeColor(overlayTheme, "start", Color(0xFF95A5A6)),
            pressedColor = j2meThemePressed(overlayTheme, "start"),
            image = startImg,
            pressedImage = startImgPressed
        )
        // Draw Select
        val (selectImg, selectImgPressed) = rememberThemeButtonImages(overlayTheme, "select")
        J2mePillButton(
            "SELECT", btnSelect, surfaceSize, opacity, visualState and J2ME_BTN_SELECT != 0,
            normalColor = j2meThemeColor(overlayTheme, "select", Color(0xFF95A5A6)),
            pressedColor = j2meThemePressed(overlayTheme, "select"),
            image = selectImg,
            pressedImage = selectImgPressed
        )

        // Draw number pad (toggle via "123" button) — J2ME 游戏经常需要数字输入
        if (showNumPad) {
            // 半透明遮罩底色，让键盘在游戏画面上可读
            Box(
                modifier = Modifier
                    .offset {
                        IntOffset(
                            numGridLeft.toInt() - 8.dp.roundToPx(),
                            numGridTop.toInt() - 8.dp.roundToPx()
                        )
                    }
                    .size(width = numGridWidthDp + 16.dp, height = numGridHeightDp + 16.dp)
                    .background(Color(0x66000000), RoundedCornerShape(10.dp))
            )
            numKeys.forEachIndexed { idx, (label, bit) ->
                val row = idx / 3
                val col = idx % 3
                val cx = numGridLeft + col * (numKeySizePx + numGapPx) + numKeySizePx / 2f
                val cy = numGridTop + row * (numKeySizePx + numGapPx) + numKeySizePx / 2f
                val isPressed = visualState and bit != 0
                J2meNumericKey(label, null, cx, cy, numKeySizeDp, opacity, isPressed)
            }
        }
    }
}

private fun hitTestGamepad(
    pos: Offset,
    dpadRect: Rect, aRect: Rect, bRect: Rect, xRect: Rect, yRect: Rect,
    startRect: Rect, selectRect: Rect,
    numKeys: List<Pair<String, Int>>? = null,
    numKeyRects: List<Rect>? = null
): Int {
    var bits = 0
    if (dpadRect.contains(pos)) {
        val cx = dpadRect.center.x
        val cy = dpadRect.center.y
        val hw = dpadRect.width / 2f
        val hh = dpadRect.height / 2f
        val dx = (pos.x - cx) / hw.coerceAtLeast(0.001f)
        val dy = (pos.y - cy) / hh.coerceAtLeast(0.001f)
        if (dy < -0.3f) bits = bits or J2ME_BTN_UP
        if (dy > 0.3f) bits = bits or J2ME_BTN_DOWN
        if (dx < -0.3f) bits = bits or J2ME_BTN_LEFT
        if (dx > 0.3f) bits = bits or J2ME_BTN_RIGHT
    }
    if (aRect.contains(pos)) bits = bits or J2ME_BTN_A
    if (bRect.contains(pos)) bits = bits or J2ME_BTN_B
    if (xRect.contains(pos)) bits = bits or J2ME_BTN_X
    if (yRect.contains(pos)) bits = bits or J2ME_BTN_Y
    if (startRect.contains(pos)) bits = bits or J2ME_BTN_START
    if (selectRect.contains(pos)) bits = bits or J2ME_BTN_SELECT
    // 数字键盘：命中即返回该键 bit（独占，避免误触叠加）
    if (numKeys != null && numKeyRects != null) {
        for (i in numKeyRects.indices) {
            if (numKeyRects[i].contains(pos)) return numKeys[i].second
        }
    }
    return bits
}

private fun combineBits(pointers: Map<Long, Int>): Int {
    var result = 0
    for ((_, bits) in pointers) result = result or bits
    return result
}

// ===========================================================================
// Phone mode — 完整的 J2ME 真机键盘
//
//   顶行:  [L] [F] [R] [C]   L/R=软键  F=确认  C=清除/返回(END)
//   主键盘:  1..9 / * 0 #,  其中 2/4/6/8 兼作方向键, 5 兼作确认键
//                        (真机行为, 引擎层 dualKeyMap 双派发)
//
// 修复记录:
//  - 旧版 J2meNumericKey 把已换算的像素值再次当 dp 传给
//    Modifier.size(), 高密度屏幕上按键被放大约 3 倍 ("太大")
//  - 旧版软键用 J2mePillButton, 其位置换算用单键宽度的一半去
//    居中一个 2.2 倍宽的胶囊, 中心右偏 0.6 倍宽度, 且 SOFT_L
//    位于键盘左外侧, 胶囊右缘伸进键盘区域 ("重叠")
//  - 补全真机应有按键: 顶行 L/F/R/C 四个功能键 + 数字键
//    方向提示, 不再缺方向键/清除键
// ===========================================================================

private data class J2mePhoneKey(
    val label: String,
    val hint: String?,             // 方向/确认提示 (显示在数字下方)
    val bit: Int
)

@Composable
private fun J2mePhoneOverlay(
    engine: J2meEngine,
    padLayout: PadLayout,
    opacity: Float,
    isPortrait: Boolean,
    surfaceSize: IntSize,
    /** 覆盖层在窗口中的根坐标（用于把局部坐标换算回根坐标后转发）。 */
    padPosInRoot: Offset = Offset.Zero,
    /** 未命中任何按键的触摸转发器（根坐标 + MotionEvent action）。 */
    onUnhandledTouch: ((rootPos: Offset, action: Int, pointerId: Int) -> Unit)? = null
) {
    val density = LocalDensity.current

    // ---- 几何参数: 先把 dp 换算成 px, 后续全部用 px 运算 ----
    // 布局来自 PadLayout（可在布局编辑器里拖动/调尺寸），不再硬编码。
    val keySizeDp = padLayout.javaPhoneGrid.sizeDp.dp.coerceIn(28.dp, 72.dp)
    val keyGapDp = 6.dp
    val keySizePx = with(density) { keySizeDp.toPx() }
    val keyGapPx = with(density) { keyGapDp.toPx() }

    // 4 行 × 3 列数字键盘, 以 javaPhoneGrid 为中心
    val gridWidth = 3 * keySizePx + 2 * keyGapPx
    val gridHeight = 4 * keySizePx + 3 * keyGapPx
    val gridLeft = surfaceSize.width * padLayout.javaPhoneGrid.x - gridWidth / 2f
    val gridTop = surfaceSize.height * padLayout.javaPhoneGrid.y - gridHeight / 2f

    // 顶部功能键行: 4 个圆形键（L/F/R/C）, 以 javaPhoneTop 为中心,
    // 键距与数字键盘保持一致
    val topSizeDp = padLayout.javaPhoneTop.sizeDp.dp.coerceIn(24.dp, 60.dp)
    val topSizePx = with(density) { topSizeDp.toPx() }
    val topSlot = keySizePx + keyGapPx
    val topRowLeft = surfaceSize.width * padLayout.javaPhoneTop.x - 2f * topSlot
    val topCy = surfaceSize.height * padLayout.javaPhoneTop.y

    val topKeys = listOf(
        J2mePhoneKey("L", null, J2ME_BTN_B),       // SOFT_LEFT
        J2mePhoneKey("F", null, J2ME_BTN_A),       // FIRE
        J2mePhoneKey("R", null, J2ME_BTN_X),       // SOFT_RIGHT
        J2mePhoneKey("C", null, J2ME_BTN_START),   // END / 清除
    )

    val numKeys = listOf(
        J2mePhoneKey("1", null, J2ME_BTN_NUM_1),
        J2mePhoneKey("2", "\u25b2", J2ME_BTN_NUM_2),   // ▲ → UP
        J2mePhoneKey("3", null, J2ME_BTN_NUM_3),
        J2mePhoneKey("4", "\u25c0", J2ME_BTN_NUM_4),   // ◀ → LEFT
        J2mePhoneKey("5", "\u25cf", J2ME_BTN_NUM_5),   // ● → FIRE
        J2mePhoneKey("6", "\u25b6", J2ME_BTN_NUM_6),   // ▶ → RIGHT
        J2mePhoneKey("7", null, J2ME_BTN_NUM_7),
        J2mePhoneKey("8", "\u25bc", J2ME_BTN_NUM_8),   // ▼ → DOWN
        J2mePhoneKey("9", null, J2ME_BTN_NUM_9),
        J2mePhoneKey("*", null, J2ME_BTN_Y),            // → KEY_STAR
        J2mePhoneKey("0", null, J2ME_BTN_NUM_0),
        J2mePhoneKey("#", null, J2ME_BTN_SELECT),       // → KEY_POUND
    )

    // 命中矩形 (与绘制使用同一套坐标)
    val topKeyRects = topKeys.mapIndexed { i, _ ->
        val cx = topRowLeft + topSlot * (i + 0.5f)
        Rect(cx - topSizePx / 2, topCy - topSizePx / 2, cx + topSizePx / 2, topCy + topSizePx / 2)
    }
    val numKeyRects = numKeys.mapIndexed { idx, _ ->
        val row = idx / 3
        val col = idx % 3
        val x = gridLeft + col * (keySizePx + keyGapPx)
        val y = gridTop + row * (keySizePx + keyGapPx)
        Rect(x, y, x + keySizePx, y + keySizePx)
    }

    val activePointers = remember { mutableMapOf<Long, Int>() }
    var visualState by remember { mutableStateOf(0) }

    // 同手柄模式：落在键盘按键之外的触摸转发给游戏画面（触屏支持）。
    val unhandledPointers = remember { mutableMapOf<Long, Boolean>() }
    // 每个"游戏画面触摸"指针最近一次的根坐标，手势被取消时用它补发 CANCEL。
    val unhandledPositions = remember { mutableMapOf<Long, Offset>() }
    val currentOnUnhandledTouch by rememberUpdatedState(onUnhandledTouch)

    val sendState = remember(engine) {
        { bits: Int ->
            visualState = bits
            engine.setPad1(bits)
        }
    }

    // 手势以取消/异常结束时调用：给 MIDlet 补发 ACTION_CANCEL 并复位全部状态。
    // 否则按住游戏画面的手指会一直"卡"在 MIDlet 侧（DOWN 已注入、UP 永远收不到），
    // 之后所有触摸都被游戏当作一次持续拖拽丢弃 —— 表现为"触屏点一次就失效"。
    fun releaseAllPointers() {
        val forward = currentOnUnhandledTouch
        for (pid in unhandledPointers.keys.toList()) {
            if (forward != null) {
                forward(
                    unhandledPositions[pid] ?: Offset.Zero,
                    android.view.MotionEvent.ACTION_CANCEL,
                    pid.toInt()
                )
            }
            unhandledPointers.remove(pid)
            unhandledPositions.remove(pid)
        }
        if (activePointers.isNotEmpty()) {
            activePointers.clear()
            sendState(0)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(surfaceSize) {
                awaitEachGesture {
                    try {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        // 记录按下是否已被更高 z 的控件（模式切换 / "123" 按钮）
                        // 消费 —— 已消费的按下属于那些控件自身，不能转发成
                        // 游戏区域触摸，否则点切换按钮会向画面注入一次多余触摸。
                        val downConsumedByOther = down.isConsumed
                        down.consume()
                        val id = down.id.value
                        var pressedCount = 1
                        val bits = hitTestPhone(down.position, topKeys, topKeyRects, numKeys, numKeyRects)
                        if (bits != 0) {
                            activePointers[id] = bits
                            sendState(combineBits(activePointers))
                            unhandledPointers.remove(id)
                        } else if (!downConsumedByOther && currentOnUnhandledTouch != null) {
                            // 未命中任何按键 → 游戏画面触摸，转发给底层视图
                            unhandledPointers[id] = true
                            unhandledPositions[id] = down.position + padPosInRoot
                            currentOnUnhandledTouch?.invoke(
                                down.position + padPosInRoot,
                                android.view.MotionEvent.ACTION_DOWN,
                                id.toInt()
                            )
                        } else {
                            // 未命中按键且触摸被其他控件消费 / 无转发器：
                            // 不跟踪、不转发
                        }
                        while (true) {
                            val event = awaitPointerEvent()
                            event.changes.forEach { change ->
                                val consumedByChild = change.isConsumed
                                change.consume()
                                if (change.changedToDown()) pressedCount++
                                if (change.changedToUp()) pressedCount--
                                val pid = change.id.value
                                val newBits = if (change.pressed) {
                                    hitTestPhone(change.position, topKeys, topKeyRects, numKeys, numKeyRects)
                                } else 0
                                if (unhandledPointers[pid] == true) {
                                    // 已标记为"游戏画面触摸"的指针：持续转发移动/抬起
                                    if (change.pressed) {
                                        unhandledPositions[pid] = change.position + padPosInRoot
                                        currentOnUnhandledTouch?.invoke(
                                            change.position + padPosInRoot,
                                            android.view.MotionEvent.ACTION_MOVE,
                                            pid.toInt()
                                        )
                                    } else {
                                        currentOnUnhandledTouch?.invoke(
                                            change.position + padPosInRoot,
                                            android.view.MotionEvent.ACTION_UP,
                                            pid.toInt()
                                        )
                                        unhandledPointers.remove(pid)
                                        unhandledPositions.remove(pid)
                                    }
                                    if (!change.pressed) {
                                        activePointers.remove(pid)
                                    }
                                    return@forEach
                                }
                                // 与手柄模式一致：手势中途新加入的手指（此前未在
                                // 任何按键/游戏区域登记）未命中按键且未被更高 z
                                // 控件消费时，同样转发为游戏画面触摸 DOWN。
                                if (change.pressed && !activePointers.containsKey(pid) &&
                                    newBits == 0 && !consumedByChild &&
                                    currentOnUnhandledTouch != null) {
                                    unhandledPointers[pid] = true
                                    unhandledPositions[pid] = change.position + padPosInRoot
                                    currentOnUnhandledTouch?.invoke(
                                        change.position + padPosInRoot,
                                        android.view.MotionEvent.ACTION_DOWN,
                                        pid.toInt()
                                    )
                                    return@forEach
                                }
                                val oldBits = activePointers[pid] ?: 0
                                if (oldBits != newBits) {
                                    activePointers[pid] = newBits
                                    sendState(combineBits(activePointers))
                                }
                                if (!change.pressed) {
                                    activePointers.remove(pid)
                                    sendState(combineBits(activePointers))
                                }
                            }
                            if (pressedCount <= 0) break
                        }
                    } catch (t: Throwable) {
                        // 手势以取消/异常结束：先给游戏画面触摸补发 CANCEL、复位
                        // 虚拟按键状态，再决定是否重新抛出。否则 MIDlet 侧会一直
                        // 认为手指/按键仍按住（卡触摸/卡按键），之后的触摸全部被
                        // 游戏当作持续拖拽丢弃 —— 表现为"切一次即时绘制模式触屏
                        // 才生效一次"。
                        releaseAllPointers()
                        // 手势结束由 awaitPointerEvent() 抛 CancellationException 表示，
                        // 必须原样抛出交给 awaitEachGesture 处理。其它异常绝不能逃逸：
                        // awaitEachGesture 只捕获 CancellationException，别的异常会让
                        // 整个 pointerInput 协程永久退出 —— 触摸转发就此失效，直到
                        // 控件被重组重建（表现为"改一次设置触屏才多生效一次"）。
                        if (t is java.util.concurrent.CancellationException) throw t
                        android.util.Log.e("J2meOverlay", "phone gesture error", t)
                    }
                }
            }
    ) {
        // 顶部功能键行: L / F / R / C
        topKeys.forEachIndexed { i, key ->
            val cx = topRowLeft + topSlot * (i + 0.5f)
            val isPressed = visualState and key.bit != 0
            val color = when (key.label) {
                "F" -> Color(0xFFE74C3C)                       // FIRE 红
                "L", "R" -> Color(0xFF3498DB)                  // 软键 蓝
                else -> Color(0xFF95A5A6)                      // 清除 灰
            }
            J2meRoundActionKey(key.label, color, cx, topCy, topSizeDp, opacity, isPressed)
        }

        // 数字键盘 (2/4/6/8 带方向提示, 5 带确认提示)
        numKeys.forEachIndexed { idx, key ->
            val row = idx / 3
            val col = idx % 3
            val cx = gridLeft + col * (keySizePx + keyGapPx) + keySizePx / 2f
            val cy = gridTop + row * (keySizePx + keyGapPx) + keySizePx / 2f
            val isPressed = visualState and key.bit != 0
            J2meNumericKey(key.label, key.hint, cx, cy, keySizeDp, opacity, isPressed)
        }
    }
}

private fun hitTestPhone(
    pos: Offset,
    topKeys: List<J2mePhoneKey>,
    topKeyRects: List<Rect>,
    numKeys: List<J2mePhoneKey>,
    numKeyRects: List<Rect>
): Int {
    for (i in topKeyRects.indices) {
        if (topKeyRects[i].contains(pos)) return topKeys[i].bit
    }
    for (i in numKeyRects.indices) {
        if (numKeyRects[i].contains(pos)) return numKeys[i].bit
    }
    return 0
}

// ===========================================================================
// Visual components
// ===========================================================================

@Composable
private fun J2meDpadCanvas(
    layout: ButtonLayout,
    surfaceSize: IntSize,
    opacity: Float,
    pressedDirs: Int,
    armColorInput: Color = Color(0xFF39445A),
    pressedColorInput: Color = Color(0xFFFFD66B),
    image: androidx.compose.ui.graphics.ImageBitmap? = null,
    pressedImage: androidx.compose.ui.graphics.ImageBitmap? = null
) {
    val density = LocalDensity.current
    val sizeDp = layout.sizeDp.dp
    val sizePx = with(density) { sizeDp.toPx() }
    val px = (surfaceSize.width * layout.x - sizePx / 2f)
    val py = (surfaceSize.height * layout.y - sizePx / 2f)
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

                // 方向键配色增亮：旧版底色 0xFF2C2C38（近黑）在深色游戏画面上
                // 即便不透明也几乎与背景融为一体。改为亮一档的蓝灰底 +
                // 白色描边 + 高亮箭头，任何背景下都能看清按键轮廓。
                // （主题开启时使用用户配置的常规色/按压色）
                val armColor = armColorInput.copy(alpha = opacity)
                val pressedColor = pressedColorInput.copy(alpha = (opacity * 0.9f).coerceAtMost(1f))
                val outlineColor = Color.White.copy(alpha = (opacity * 0.45f).coerceAtMost(1f))

                drawRoundRect(armColor, Offset(cx - armLen, cy - halfThick), Size(armLen * 2, armThick), cr)
                drawRoundRect(armColor, Offset(cx - halfThick, cy - armLen), Size(armThick, armLen * 2), cr)
                // 十字轮廓描边（把横臂与竖臂的边缘都勾出来）
                drawRoundRect(
                    outlineColor,
                    Offset(cx - armLen, cy - halfThick), Size(armLen * 2, armThick), cr,
                    style = androidx.compose.ui.graphics.drawscope.Stroke(1.5.dp.toPx())
                )
                drawRoundRect(
                    outlineColor,
                    Offset(cx - halfThick, cy - armLen), Size(armThick, armLen * 2), cr,
                    style = androidx.compose.ui.graphics.drawscope.Stroke(1.5.dp.toPx())
                )
                // 中心亮斑：提示操作区域
                drawCircle(Color.White.copy(alpha = opacity * 0.18f), halfThick * 0.55f, Offset(cx, cy))

                val armTipLen = armLen * 0.42f
                val tipThick = armThick * 0.7f
                if (pressedDirs and J2ME_BTN_UP != 0) drawRoundRect(pressedColor, Offset(cx - tipThick / 2, cy - armLen), Size(tipThick, armTipLen), cr)
                if (pressedDirs and J2ME_BTN_DOWN != 0) drawRoundRect(pressedColor, Offset(cx - tipThick / 2, cy + armLen - armTipLen), Size(tipThick, armTipLen), cr)
                if (pressedDirs and J2ME_BTN_LEFT != 0) drawRoundRect(pressedColor, Offset(cx - armLen, cy - tipThick / 2), Size(armTipLen, tipThick), cr)
                if (pressedDirs and J2ME_BTN_RIGHT != 0) drawRoundRect(pressedColor, Offset(cx + armLen - armTipLen, cy - tipThick / 2), Size(armTipLen, tipThick), cr)
            }
        }
    }
}

@Composable
private fun J2meActionButton(
    label: String, color: Color, layout: ButtonLayout, surfaceSize: IntSize, opacity: Float, isPressed: Boolean,
    pressedColor: Color? = null,
    image: androidx.compose.ui.graphics.ImageBitmap? = null,
    pressedImage: androidx.compose.ui.graphics.ImageBitmap? = null
) {
    val density = LocalDensity.current
    val sizeDp = layout.sizeDp.dp
    val sizePx = with(density) { sizeDp.toPx() }
    val px = (surfaceSize.width * layout.x - sizePx / 2f)
    val py = (surfaceSize.height * layout.y - sizePx / 2f)
    val activeImage = if (isPressed) (pressedImage ?: image) else image

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
                modifier = Modifier.fillMaxSize().alpha(if (isPressed) 0.9f else opacity)
            )
        } else {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val cx = size.width / 2f
                val cy = size.height / 2f
                val r = size.width * 0.46f
                drawCircle(color.copy(alpha = opacity * 0.3f), r + 3.dp.toPx(), Offset(cx, cy))
                drawCircle(
                    if (isPressed) (pressedColor ?: color.copy(alpha = (opacity * 1.5f).coerceAtMost(1f)))
                    else color.copy(alpha = opacity),
                    r, Offset(cx, cy)
                )
                drawCircle(Color.White.copy(alpha = if (isPressed) 0.1f else 0.15f), r * 0.7f, Offset(cx - r * 0.15f, cy - r * 0.15f))
            }
            Text(label, color = Color.White, fontSize = (sizeDp.value * 0.35f).sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun J2mePillButton(
    label: String, layout: ButtonLayout, surfaceSize: IntSize, opacity: Float, isPressed: Boolean,
    normalColor: Color = Color(0xFF95A5A6), pressedColor: Color? = null,
    image: androidx.compose.ui.graphics.ImageBitmap? = null,
    pressedImage: androidx.compose.ui.graphics.ImageBitmap? = null
) {
    val density = LocalDensity.current
    val sizeDp = layout.sizeDp.dp
    val sizePx = with(density) { sizeDp.toPx() }
    // 修复: 胶囊实际尺寸是 2.2×sizePx 宽、0.7×sizePx 高, 旧代码用单键
    // 尺寸的一半去居中, 导致胶囊中心右偏 0.6×sizePx、下偏 0.15×sizePx。
    // 这里按胶囊真实宽高的一半回退, 使 layout.x/y 准确对应胶囊中心。
    val pillWidthPx = sizePx * 2.2f
    val pillHeightPx = sizePx * 0.7f
    val px = (surfaceSize.width * layout.x - pillWidthPx / 2f)
    val py = (surfaceSize.height * layout.y - pillHeightPx / 2f)
    val activeImage = if (isPressed) (pressedImage ?: image) else image

    Box(
        modifier = Modifier
            .offset { IntOffset(px.toInt(), py.toInt()) }
            .size(width = sizeDp * 2.2f, height = sizeDp * 0.7f),
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
                val cx = size.width / 2f
                val cy = size.height / 2f
                val rx = size.width * 0.46f
                val ry = size.height * 0.46f
                val color = (if (isPressed) (pressedColor ?: normalColor.copy(alpha = (opacity * 1.5f).coerceAtMost(1f)))
                             else normalColor.copy(alpha = opacity))
                // 圆角胶囊外形 (旧版画圆, 与 2.2:0.7 的容器不匹配)
                val cornerR = size.height / 2f
                drawRoundRect(
                    color.copy(alpha = opacity * 0.5f),
                    Offset(cx - rx, cy - ry),
                    Size(rx * 2, ry * 2),
                    androidx.compose.ui.geometry.CornerRadius(cornerR, cornerR)
                )
                drawRoundRect(
                    color,
                    Offset(cx - rx, cy - ry),
                    Size(rx * 2, ry * 2),
                    androidx.compose.ui.geometry.CornerRadius(cornerR, cornerR),
                    style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.5.dp.toPx())
                )
            }
            Text(label, color = Color.White, fontSize = (sizeDp.value * 0.28f).sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun J2meNumericKey(
    label: String,
    hint: String?,
    cx: Float,
    cy: Float,
    sizeDp: androidx.compose.ui.unit.Dp,
    opacity: Float,
    isPressed: Boolean
) {
    // 修复: 旧版把 px 值再次当 dp 用 (Modifier.size(sizePx.dp)),
    // 高密度屏幕上按键放大约密度倍数。现改为传入 Dp,
    // 在 offset 回调里用 Density 接收器换算。
    val bgColor = if (isPressed) Color(0xFFFFD66B).copy(alpha = (opacity * 0.95f).coerceAtMost(1f))
                  else Color(0xFF1A1A22).copy(alpha = opacity)
    val fgColor = if (isPressed) Color(0xFF14141C)
                  else Color(0xFFFFD66B).copy(alpha = (opacity * 1.4f).coerceAtMost(1f))
    val hintColor = if (isPressed) Color(0xFF14141C)
                    else Color.White.copy(alpha = opacity * 0.8f)
    val borderColor = Color.White.copy(alpha = opacity * 0.4f)

    Box(
        modifier = Modifier
            .offset {
                IntOffset(
                    (cx - sizeDp.toPx() / 2).toInt(),
                    (cy - sizeDp.toPx() / 2).toInt()
                )
            }
            .size(sizeDp)
            .clip(CircleShape)
            .background(bgColor)
            .border(1.dp, borderColor, CircleShape),
        contentAlignment = Alignment.Center
    ) {
        if (hint == null) {
            Text(
                text = label,
                color = fgColor,
                fontSize = (sizeDp.value * 0.38f).sp,
                fontWeight = FontWeight.Bold
            )
        } else {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = label,
                    color = fgColor,
                    fontSize = (sizeDp.value * 0.28f).sp,
                    fontWeight = FontWeight.Bold,
                    lineHeight = (sizeDp.value * 0.32f).sp
                )
                Text(
                    text = hint,
                    color = hintColor,
                    fontSize = (sizeDp.value * 0.16f).sp,
                    lineHeight = (sizeDp.value * 0.20f).sp
                )
            }
        }
    }
}

// 圆形功能按键 (L/F/R/C 顶部行用) — 位置即圆心, 无旧版胶囊的居中偏移问题
@Composable
private fun J2meRoundActionKey(
    label: String,
    color: Color,
    cx: Float,
    cy: Float,
    sizeDp: androidx.compose.ui.unit.Dp,
    opacity: Float,
    isPressed: Boolean
) {
    Box(
        modifier = Modifier
            .offset {
                IntOffset(
                    (cx - sizeDp.toPx() / 2).toInt(),
                    (cy - sizeDp.toPx() / 2).toInt()
                )
            }
            .size(sizeDp),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val c = Offset(size.width / 2f, size.height / 2f)
            val r = size.minDimension / 2f * 0.92f
            drawCircle(color.copy(alpha = opacity * 0.35f), r + 2.dp.toPx(), c)
            drawCircle(
                if (isPressed) color.copy(alpha = (opacity * 1.5f).coerceAtMost(1f))
                else color.copy(alpha = opacity * 0.85f),
                r, c
            )
            drawCircle(
                Color.White.copy(alpha = 0.15f),
                r * 0.7f,
                Offset(c.x - r * 0.15f, c.y - r * 0.15f)
            )
        }
        Text(
            text = label,
            color = Color.White,
            fontSize = (sizeDp.value * 0.36f).sp,
            fontWeight = FontWeight.Bold
        )
    }
}

// ===========================================================================
// Mode switch button — top-right corner
// ===========================================================================

@Composable
private fun J2meModeSwitchButton(
    isPhone: Boolean,
    opacity: Float,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    label: String? = null
) {
    val bgColor = Color.Black.copy(alpha = opacity * 0.6f)
    val fgColor = Color.White.copy(alpha = opacity)
    val borderColor = Color.White.copy(alpha = opacity * 0.7f)
    // 🎮 = gamepad, ☎ = phone; label 参数供 "123" 数字键盘开关复用
    val text = label ?: if (isPhone) "\ud83c\udfae" else "\u260e\ufe0f"

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
            text = text,
            color = fgColor,
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            modifier = Modifier.align(Alignment.Center)
        )
    }
}

// ===========================================================================
// J2ME In-Game Menu Overlay
//
// Shows J2ME-relevant controls (与其它核心的 MenuOverlay 同款深蓝底 + 图标行):
//   - Pause / Resume (toggle MIDlet running state)
//   - Overlay mode toggle (gamepad ↔ phone)
//   - Layout editor (手柄模式下编辑虚拟按键位置, 与其它核心一致)
//   - Settings (输入模式/屏幕缩放/J2ME帧率/即时绘制等专属设置)
//   - Exit (unload MIDlet, return to game library)
//
// Does NOT show libretro-only features:
//   - Save / Load state (J2ME uses RMS, not native save states)
//   - Fast-forward (J2ME is event-driven, not frame-pumped)
//   - Screenshot (J2ME renders to its own SurfaceView)
//   - Reset (J2ME restart = unload + reload)
// ===========================================================================

@Composable
fun J2meMenuOverlay(
    gameTitle: String,
    running: Boolean,
    isPhoneMode: Boolean,
    isPortrait: Boolean = false,
    onTogglePause: () -> Unit,
    onToggleOverlayMode: () -> Unit,
    onLayoutEditor: () -> Unit,
    onSettings: () -> Unit,
    onClose: () -> Unit,
    onExit: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0x88000000))
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown()
                    // Don't consume — let horizontal drags reach the scrollable menu row.
                }
            }
    )
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = if (isPortrait) Alignment.TopCenter else Alignment.BottomCenter
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp)
                .background(Color(0xDD1E2A3A), RoundedCornerShape(16.dp))
                .padding(horizontal = 12.dp, vertical = 6.dp)
                .horizontalScroll(rememberScrollState()),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                gameTitle,
                color = Color.White,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f).padding(end = 8.dp),
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
            )
            Spacer(Modifier.width(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.CenterVertically) {
                // ★ 本轮：每个按钮下方加文字标签（与主 MenuOverlay 同款），
                //   需求："每个按钮下方应该加入文本以免很多人不知道按钮的作用"。
                // Pause / Resume
                J2meMenuButton(
                    label = if (running) "暂停" else "继续",
                    onClick = onTogglePause
                ) {
                    Icon(
                        if (running) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                        "暂停/继续",
                        tint = Color.White
                    )
                }
                // Overlay mode toggle (gamepad ↔ phone)
                J2meMenuButton(
                    label = if (isPhoneMode) "切手柄" else "切手机",
                    onClick = onToggleOverlayMode
                ) {
                    Icon(
                        Icons.Rounded.SwapHoriz,
                        if (isPhoneMode) "切换到手柄" else "切换到手机键盘",
                        tint = if (isPhoneMode) Color(0xFFFFD66B) else Color.White
                    )
                }
                // Virtual keypad layout editor — same as other cores' 手柄布局
                J2meMenuButton(label = "手柄布局", onClick = onLayoutEditor) {
                    Icon(Icons.Rounded.Tune, "虚拟按键布局", tint = Color.White)
                }
                // Engine-specific settings (输入模式/屏幕缩放/J2ME帧率/即时绘制)
                J2meMenuButton(label = "设置", onClick = onSettings) {
                    Icon(Icons.Rounded.Settings, "设置", tint = Color.White)
                }
                // Close menu
                J2meMenuButton(label = "隐藏菜单", onClick = onClose) {
                    Icon(Icons.Rounded.Fullscreen, "隐藏菜单", tint = Color(0xFF4A90D9))
                }
                // Exit
                J2meMenuButton(label = "退出", onClick = onExit) {
                    Icon(Icons.Rounded.Close, "退出", tint = Color(0xFFFF6B6B))
                }
            }
        }
    }
}

/**
 * J2ME 菜单按钮：图标 + 下方文字标签（与 EmulatorScreen 的
 * FocusableIconButton(label=…) 同款视觉），让用户知道每个按钮的作用。
 */
@Composable
private fun J2meMenuButton(
    label: String,
    onClick: () -> Unit,
    content: @Composable () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.width(46.dp)
    ) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center
        ) {
            content()
        }
        Text(
            label,
            color = Color(0xFFAAB8C8),
            fontSize = 9.sp,
            maxLines = 1,
            modifier = Modifier.padding(top = 1.dp)
        )
    }
}

// ===========================================================================
// J2ME 手机键盘专用布局编辑器
//
// 切换到“手机键盘”模式后，虚拟按键布局同样可以调整：
//   - 数字键盘（4 行 × 3 列，1-9 / * 0 #）— 拖动位置、滑杆调键距大小
//   - 功能键行（L / F / R / C）          — 拖动位置、滑杆调键大小
// 布局持久化在 PadLayout.javaPhoneGrid / javaPhoneTop（横竖屏共用），
// 保存时机与通用布局编辑器一致（EmulatorScreen 里的 400ms 防抖）。
// ===========================================================================

private enum class J2mePhoneEditTarget(val label: String) {
    GRID("数字键盘"), TOP("功能键 L/F/R/C")
}

@Composable
fun J2mePhoneLayoutEditor(
    padLayout: PadLayout,
    isPortrait: Boolean,
    onLayoutChange: (PadLayout) -> Unit,
    surfaceSize: IntSize,
    onClose: () -> Unit
) {
    val density = LocalDensity.current
    var selected by remember { mutableStateOf(J2mePhoneEditTarget.GRID) }

    val grid = padLayout.javaPhoneGrid
    val top = padLayout.javaPhoneTop

    // 与 J2mePhoneOverlay 相同的几何计算，保证编辑器里看到的就是游戏中的样子
    val keySizeDp = grid.sizeDp.dp.coerceIn(28.dp, 72.dp)
    val keyGapDp = 6.dp
    val keySizePx = with(density) { keySizeDp.toPx() }
    val keyGapPx = with(density) { keyGapDp.toPx() }
    val gridWidth = 3 * keySizePx + 2 * keyGapPx
    val gridHeight = 4 * keySizePx + 3 * keyGapPx
    val gridLeft = surfaceSize.width * grid.x - gridWidth / 2f
    val gridTop = surfaceSize.height * grid.y - gridHeight / 2f

    val topSizeDp = top.sizeDp.dp.coerceIn(24.dp, 60.dp)
    val topSizePx = with(density) { topSizeDp.toPx() }
    val topSlot = keySizePx + keyGapPx
    val topRowLeft = surfaceSize.width * top.x - 2f * topSlot
    val topCy = surfaceSize.height * top.y

    val gridRect = Rect(gridLeft, gridTop, gridLeft + gridWidth, gridTop + gridHeight)
    val topRect = Rect(
        topRowLeft - topSizePx / 2f, topCy - topSizePx / 2f,
        topRowLeft + 4f * topSlot - topSizePx / 2f, topCy + topSizePx / 2f
    )

    // 手势稳定模式（与通用 PadLayoutEditor 的 EditableDpad 一致）：
    // pointerInput(Unit) 只启动一次，通过 rememberUpdatedState 读取最新值，
    // 避免拖动过程中因 key 变化导致手势被取消。
    val curGrid by rememberUpdatedState(grid)
    val curTop by rememberUpdatedState(top)
    val curSurfaceSize by rememberUpdatedState(surfaceSize)
    val curGridRect by rememberUpdatedState(gridRect)
    val curTopRect by rememberUpdatedState(topRect)
    var dragStartX by remember { mutableStateOf(0f) }
    var dragStartY by remember { mutableStateOf(0f) }
    var layoutStartX by remember { mutableStateOf(0f) }
    var layoutStartY by remember { mutableStateOf(0f) }

    fun updateGrid(nx: Float, ny: Float) {
        onLayoutChange(
            padLayout.copy {
                javaPhoneGrid = ButtonLayout(
                    x = nx.coerceIn(0.08f, 0.92f),
                    y = ny.coerceIn(0.08f, 0.92f),
                    sizeDp = padLayout.javaPhoneGrid.sizeDp
                )
            }
        )
    }
    fun updateTop(nx: Float, ny: Float) {
        onLayoutChange(
            padLayout.copy {
                javaPhoneTop = ButtonLayout(
                    x = nx.coerceIn(0.08f, 0.92f),
                    y = ny.coerceIn(0.08f, 0.92f),
                    sizeDp = padLayout.javaPhoneTop.sizeDp
                )
            }
        )
    }

    Box(modifier = Modifier.fillMaxSize().background(Color(0x88000000))) {
        // 可拖动的键盘预览
        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        down.consume()
                        // 命中哪个元素就拖哪个；两个都命中时选更靠近中心的一个
                        val hitGrid = curGridRect.contains(down.position)
                        val hitTop = curTopRect.contains(down.position)
                        selected = when {
                            hitGrid && hitTop -> {
                                val dGrid = (down.position - curGridRect.center).getDistance()
                                val dTop = (down.position - curTopRect.center).getDistance()
                                if (dGrid <= dTop) J2mePhoneEditTarget.GRID else J2mePhoneEditTarget.TOP
                            }
                            hitGrid -> J2mePhoneEditTarget.GRID
                            hitTop -> J2mePhoneEditTarget.TOP
                            else -> selected
                        }
                        // 记录拖动起点与被拖元素的初始位置，后续按相对位移更新
                        dragStartX = down.position.x
                        dragStartY = down.position.y
                        when (selected) {
                            J2mePhoneEditTarget.GRID -> {
                                layoutStartX = curGrid.x
                                layoutStartY = curGrid.y
                            }
                            J2mePhoneEditTarget.TOP -> {
                                layoutStartX = curTop.x
                                layoutStartY = curTop.y
                            }
                        }
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: continue
                            change.consume()
                            if (!change.pressed) break
                            val dxFrac = (change.position.x - dragStartX) / curSurfaceSize.width
                            val dyFrac = (change.position.y - dragStartY) / curSurfaceSize.height
                            when (selected) {
                                J2mePhoneEditTarget.GRID -> updateGrid(layoutStartX + dxFrac, layoutStartY + dyFrac)
                                J2mePhoneEditTarget.TOP -> updateTop(layoutStartX + dxFrac, layoutStartY + dyFrac)
                            }
                        }
                    }
                }
        ) {
            // 功能键行预览 (L/F/R/C)
            val topKeys = listOf("L", "F", "R", "C")
            val topColors = listOf(
                Color(0xFF3498DB), Color(0xFFE74C3C), Color(0xFF3498DB), Color(0xFF95A5A6)
            )
            topKeys.forEachIndexed { i, label ->
                val cx = topRowLeft + topSlot * (i + 0.5f)
                J2meRoundActionKey(label, topColors[i], cx, topCy, topSizeDp, 0.7f, false)
                if (selected == J2mePhoneEditTarget.TOP) {
                    Box(
                        modifier = Modifier
                            .offset {
                                IntOffset(
                                    (cx - topSizePx / 2).toInt(),
                                    (topCy - topSizePx / 2).toInt()
                                )
                            }
                            .size(with(density) { topSizePx.toDp() })
                            .border(2.dp, Color(0xFFFFD66B), RoundedCornerShape(22.dp))
                    )
                }
            }
            // 数字键盘预览 (1-9 / * 0 #)
            val previewKeys = listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "*", "0", "#")
            previewKeys.forEachIndexed { idx, label ->
                val row = idx / 3
                val col = idx % 3
                val cx = gridLeft + col * (keySizePx + keyGapPx) + keySizePx / 2f
                val cy = gridTop + row * (keySizePx + keyGapPx) + keySizePx / 2f
                J2meNumericKey(label, null, cx, cy, keySizeDp, 0.7f, false)
            }
            if (selected == J2mePhoneEditTarget.GRID) {
                Box(
                    modifier = Modifier
                        .offset { IntOffset(gridLeft.toInt(), gridTop.toInt()) }
                        .size(
                            width = with(density) { gridWidth.toDp() },
                            height = with(density) { gridHeight.toDp() }
                        )
                        .border(2.dp, Color(0xFFFFD66B), RoundedCornerShape(10.dp))
                )
            }
        }

        // 底部控制面板：选中项 + 尺寸滑杆 + 关闭
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(16.dp)
                .background(Color(0xDD1E2A3A), RoundedCornerShape(16.dp))
                .padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (selected == J2mePhoneEditTarget.GRID) "手机键盘布局 · 数字键盘" else "手机键盘布局 · 功能键行",
                    color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    "拖动调整位置",
                    color = Color(0xFF8899AA), fontSize = 11.sp,
                    modifier = Modifier.padding(end = 8.dp)
                )
                IconButton(onClick = onClose) {
                    Icon(Icons.Rounded.Close, "完成", tint = Color.White)
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("大小", color = Color(0xFF8899AA), fontSize = 12.sp)
                Spacer(Modifier.width(8.dp))
                androidx.compose.material3.Slider(
                    value = when (selected) {
                        J2mePhoneEditTarget.GRID -> padLayout.javaPhoneGrid.sizeDp.coerceIn(28, 72).toFloat()
                        J2mePhoneEditTarget.TOP -> padLayout.javaPhoneTop.sizeDp.coerceIn(24, 60).toFloat()
                    },
                    onValueChange = { v ->
                        when (selected) {
                            J2mePhoneEditTarget.GRID ->
                                onLayoutChange(padLayout.copy { javaPhoneGrid = ButtonLayout(
                                    x = padLayout.javaPhoneGrid.x, y = padLayout.javaPhoneGrid.y,
                                    sizeDp = v.toInt().coerceIn(28, 72)) })
                            J2mePhoneEditTarget.TOP ->
                                onLayoutChange(padLayout.copy { javaPhoneTop = ButtonLayout(
                                    x = padLayout.javaPhoneTop.x, y = padLayout.javaPhoneTop.y,
                                    sizeDp = v.toInt().coerceIn(24, 60)) })
                        }
                    },
                    valueRange = when (selected) {
                        J2mePhoneEditTarget.GRID -> 28f..72f
                        J2mePhoneEditTarget.TOP -> 24f..60f
                    },
                    modifier = Modifier.weight(1f)
                )
            }
            // 透明度调节 —— J2ME 专属（javaOpacity），与其他核心的全局
            // 透明度互不影响。拖动即时生效，随布局一起持久化。
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("透明度", color = Color(0xFF8899AA), fontSize = 12.sp)
                Spacer(Modifier.width(8.dp))
                androidx.compose.material3.Slider(
                    value = padLayout.javaOpacity.coerceIn(0.3f, 1f),
                    onValueChange = { v ->
                        onLayoutChange(padLayout.copy { javaOpacity = v.coerceIn(0.3f, 1f) })
                    },
                    valueRange = 0.3f..1f,
                    colors = androidx.compose.material3.SliderDefaults.colors(
                        thumbColor = Color(0xFFFFD66B),
                        activeTrackColor = Color(0xFFFFD66B),
                        inactiveTrackColor = Color(0xFF4A5568)
                    ),
                    modifier = Modifier.weight(1f)
                )
                Text(
                    "${(padLayout.javaOpacity.coerceIn(0.3f, 1f) * 100).toInt()}%",
                    color = Color(0xFFFFD66B), fontSize = 12.sp,
                    modifier = Modifier.width(44.dp),
                    textAlign = TextAlign.End
                )
            }
        }
    }
}
