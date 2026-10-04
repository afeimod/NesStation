package com.nesstation.app.ui.neon

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.focusable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * ★★ NeonFlow —— Neon 3D 封面滚动引擎（完全重制版核心组件）★★
 *
 * 「每个核心游戏显示封面选择滚动」的主视觉：
 *   - 选中封面居中放大、正对用户、青色辉光（卡片由 [content] 自绘，推荐 NeonCoverCard）
 *   - 两侧封面沿「弧形导轨」排布：Y 轴透视旋转（±[tiltDegrees]）、逐级缩小变暗、
 *     越远越下沉（模拟封面立在弧形轨道上向远处延伸的 3D 纵深）
 *   - 每个封面下方带垂直翻转倒影（渐隐到深空底色，高度 [reflectionRatio] 比例）
 *   - 左右拖拽跟手 + fling 惯性；点击侧边封面居中；点击中间封面触发 [onItemClick]
 *   - D-pad 左右移动 + OK 激活 + Y 选项（TV / 蓝牙手柄）
 *   - ★ 整体下移 + 高度自适应：[verticalShift] 让封面流重心下沉，不遮挡顶栏文字；
 *     横屏/矮容器时按可用高度整体缩小（fitScale），保证卡片永不越过容器上缘。
 *
 * 交互健壮性继承自 FsdCoverFlow 的实战修复：
 *   - 回调/列表长度经 rememberUpdatedState 实时读取（手势闭包不捕获过期索引）
 *   - 影子索引 shadowSel 同步推进（快速连滑不卡死）
 *   - 单次拖拽步数上限 16（长列表滑得动）
 *   - 翻页动画在绘制阶段读取（每帧只重绘不重组）
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun NeonFlow(
    count: Int,
    selectedIndex: Int,
    onIndexChange: (Int) -> Unit,
    onItemClick: (Int) -> Unit,
    onItemLongClick: (Int) -> Unit = {},
    modifier: Modifier = Modifier,
    itemWidth: Dp = 188.dp,
    itemHeight: Dp = 250.dp,
    gap: Dp = 20.dp,
    tiltDegrees: Float = 34f,
    fadePerStep: Float = 0.20f,
    scalePerStep: Float = 0.19f,
    centerScale: Float = 1.12f,
    showReflection: Boolean = true,
    reflectionRatio: Float = 0.28f,
    verticalShift: Dp = 16.dp,
    visibleHalfWindow: Int = 5,
    grabFocusOnLaunch: Boolean = false,
    content: @Composable (Int) -> Unit
) {
    // 单次拖拽手势允许的最大翻页步数
    val maxSwipeSteps = 16

    BoxWithConstraints(modifier = modifier) {
        if (count <= 0) return@BoxWithConstraints

        val sel = selectedIndex.coerceIn(0, count - 1)
        val animated by animateFloatAsState(
            targetValue = sel.toFloat(),
            animationSpec = tween(durationMillis = 260),
            label = "neon-flow-pos"
        )

        // 手势/键盘回调一律经 State 读取最新值（防止闭包捕获过期索引）
        val currentOnIndexChange by rememberUpdatedState(onIndexChange)
        val currentOnItemClick by rememberUpdatedState(onItemClick)
        val currentOnItemLongClick by rememberUpdatedState(onItemLongClick)
        val currentCount by rememberUpdatedState(count)

        // 影子索引：move() 内同步推进，不等重组；外部重置经 LaunchedEffect 回写
        var shadowSel by remember { mutableIntStateOf(selectedIndex) }
        LaunchedEffect(selectedIndex) { shadowSel = selectedIndex }

        // ★ 高度自适应 + 整体下移：内容列（卡片 + 倒影）比可用高度高时
        //   整体等比缩小（步长同步缩，防缩小后卡片水平重叠）；再加固定
        //   下移量，封面永不越过容器上缘去遮挡顶栏/搜索框文字。
        //   矮容器（横屏 TV 等）自动变小，高容器（竖屏手机）保持原尺寸。
        val reflectH = if (showReflection) itemHeight * reflectionRatio else 0.dp
        val columnH = itemHeight + reflectH
        val fitScale = if (maxHeight > 0.dp) {
            (((maxHeight - verticalShift - 10.dp) / columnH)).coerceIn(0.45f, 1f)
        } else 1f
        val stepPx = with(LocalDensity.current) { (itemWidth + gap).toPx() * fitScale }
        val shiftPx = with(LocalDensity.current) { verticalShift.toPx() }
        var dragAccum by remember { mutableFloatStateOf(0f) }

        // 实时拖拽跟手：拖动中封面直接随手指平移，松手残余偏移平滑归零
        var dragPx by remember { mutableFloatStateOf(0f) }
        var settling by remember { mutableStateOf(false) }
        LaunchedEffect(settling) {
            if (settling) {
                animate(
                    initialValue = dragPx,
                    targetValue = 0f,
                    animationSpec = tween(durationMillis = 200, easing = FastOutSlowInEasing)
                ) { v, _ -> dragPx = v }
                settling = false
            }
        }

        // TV 遥控器：进入界面后自动抓焦，否则 D-pad 首次按键无响应
        val focusRequester = remember { FocusRequester() }
        LaunchedEffect(grabFocusOnLaunch) {
            if (grabFocusOnLaunch) {
                kotlinx.coroutines.delay(150)
                runCatching { focusRequester.requestFocus() }
            }
        }

        fun move(delta: Int) {
            val n = currentCount.coerceAtLeast(1)
            val target = (shadowSel.coerceIn(0, n - 1) + delta).coerceIn(0, n - 1)
            shadowSel = target
            currentOnIndexChange(target)
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .focusRequester(focusRequester)
                .focusable()
                .onKeyEvent { e ->
                    if (e.type != androidx.compose.ui.input.key.KeyEventType.KeyUp) {
                        false
                    } else when (e.key) {
                        Key.DirectionLeft -> { move(-1); true }
                        Key.DirectionRight -> { move(1); true }
                        Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> {
                            onItemClick(shadowSel.coerceIn(0, currentCount - 1)); true
                        }
                        Key.Y, Key.ButtonY -> {
                            onItemLongClick(shadowSel.coerceIn(0, currentCount - 1)); true
                        }
                        else -> false
                    }
                }
                .pointerInput(Unit) {
                    // EMA 速度跟踪：快甩按速度翻页（惯性 fling）
                    var emaV = 0f
                    var lastTime = 0L
                    detectHorizontalDragGestures(
                        onDragStart = {
                            dragAccum = 0f
                            settling = false
                            dragPx = 0f
                            emaV = 0f
                            lastTime = 0L
                        },
                        onHorizontalDrag = { change, amount ->
                            val t = change.uptimeMillis
                            if (lastTime != 0L) {
                                val dt = (t - lastTime).coerceAtLeast(1)
                                val v = amount / dt * 1000f   // px/s
                                emaV = 0.7f * emaV + 0.3f * v
                            }
                            lastTime = t
                            dragAccum += amount
                            dragPx += amount
                            change.consume()
                        },
                        onDragEnd = {
                            // 半步即翻页 + fling（900 px/s 起翻，每 1800 px/s 多翻一步）
                            var steps = (-dragAccum / (stepPx * 0.45f)).roundToInt()
                            if (abs(emaV) > 900f) {
                                val fling = (abs(emaV) / 1800f).toInt() + 1
                                val dir = if (emaV < 0f) 1 else -1
                                if (steps * dir < fling) steps = fling * dir
                            }
                            if (steps != 0) move(steps.coerceIn(-maxSwipeSteps, maxSwipeSteps))
                            settling = true
                        },
                        onDragCancel = { settling = true }
                    )
                },
            contentAlignment = Alignment.Center
        ) {
            val from = (sel - visibleHalfWindow).coerceAtLeast(0)
            val to = (sel + visibleHalfWindow).coerceAtMost(count - 1)

            for (i in from..to) {
                val dist = abs(i - sel)   // 静态距离（决定可点击性）

                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .align(Alignment.Center)
                        .graphicsLayer {
                            // ===== 弧形导轨 3D 变换（绘制阶段，零重组）=====
                            val pos = i - animated
                            val aPos = abs(pos)
                            translationX = pos * stepPx + dragPx
                            // ★ 整体下移（不遮挡上方文字）+ 弧形轨道越远越下沉
                            translationY = shiftPx + aPos * aPos * 5.5f + aPos * 3f
                            // 缩放：中心放大、两侧逐级缩小（× fitScale 高度自适应）
                            val sideScale = (1f - scalePerStep * aPos).coerceAtLeast(0.5f)
                            val s = (if (aPos < 0.5f) centerScale else sideScale) * fitScale
                            scaleX = s
                            scaleY = s
                            // 透视旋转：侧边封面像 CoverFlow 一样向内立起
                            rotationY = (pos * tiltDegrees).coerceIn(-tiltDegrees * 1.6f, tiltDegrees * 1.6f)
                            cameraDistance = 9f * density   // 近相机 = 强透视
                            // 逐级变暗
                            alpha = (1f - fadePerStep * aPos).coerceIn(0.30f, 1f)
                            // 中心卡片的辉光/浮起由 NeonCoverCard 自绘
                            // （spotShadowColor 点光 + translationY，无 translationZ）
                        }
                        .combinedClickable(
                            onClick = {
                                if (dist == 0) currentOnItemClick(i) else currentOnIndexChange(i)
                            },
                            onLongClick = { currentOnItemLongClick(i) }
                        )
                ) {
                    // 封面主体（不裁剪：选中卡的辉光阴影/浮起需要溢出卡片范围）
                    Box(
                        modifier = Modifier
                            .width(itemWidth)
                            .height(itemHeight)
                    ) {
                        content(i)
                    }

                    if (showReflection) {
                        // 倒影：垂直翻转 + 渐隐到深空底（Neon 3D 标志性效果）
                        Box(
                            modifier = Modifier
                                .width(itemWidth)
                                .height(itemHeight * reflectionRatio)
                                .graphicsLayer {
                                    scaleY = -1f
                                    this.alpha = 0.24f
                                }
                        ) {
                            content(i)
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(
                                        Brush.verticalGradient(
                                            0f to Color.Transparent,
                                            0.72f to Neon.Bg.copy(alpha = 0.72f),
                                            1f to Neon.BgDeep
                                        )
                                    )
                            )
                        }
                    }
                }
            }
        }
    }
}
