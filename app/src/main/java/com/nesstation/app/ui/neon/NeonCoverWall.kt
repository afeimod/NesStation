package com.nesstation.app.ui.neon

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nesstation.app.core.model.GameEntry
import kotlin.math.abs

/**
 * ★★ Neon 弧形封面墙（图2 参考稿）—— 核心内部游戏库展示组件 ★★
 *
 * 沉浸式 2.5D 封面墙：游戏封面以多列网格铺满，每张卡片按其相对屏幕中心的
 * 水平距离施加 graphicsLayer 变换（边缘卡片 Y 轴旋转 + 缩小 + 变暗），
 * 形成"向用户微微凸起的弧形曲面墙"—— 图2 的影院级网格视觉。
 *
 * 性能：所有透视变换在**绘制阶段**（graphicsLayer lambda 读状态）计算，
 * 滚动时零重组开销；卡片位置经 onGloballyPositioned 写入状态。
 *
 * 交互：
 *   - 点击卡片 = 启动游戏；长按 = 选项菜单（与 LibraryScreen 现有长按菜单同源）
 *   - D-pad/手柄：卡片可聚焦，焦点移动实时回调选中项（A 启动 / Y 选项由
 *     LibraryScreen 的 onPreviewKeyEvent 统一处理）
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun NeonCoverWall(
    games: List<GameEntry>,
    coverCache: android.util.LruCache<String, android.graphics.Bitmap>,
    selectedIndex: Int,
    onSelectionChange: (Int) -> Unit,
    onOpenGame: (GameEntry) -> Unit,
    onLongPress: (GameEntry) -> Unit,
    modifier: Modifier = Modifier
) {
    val gridState = rememberLazyGridState()
    val density = LocalDensity.current

    // 屏幕中心 X（像素）—— 墙面曲率的参考轴
    var rootWidth by remember { mutableFloatStateOf(0f) }

    LazyVerticalGrid(
        state = gridState,
        columns = GridCells.Adaptive(minSize = 132.dp),
        modifier = modifier
            .fillMaxSize()
            .onGloballyPositioned { rootWidth = it.size.width.toFloat() },
        contentPadding = PaddingValues(horizontal = 26.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        itemsIndexed(games, key = { _, g -> g.id }) { index, game ->
            NeonWallCard(
                game = game,
                isSelected = index == selectedIndex,
                rootWidth = rootWidth,
                density = density.density,
                coverCache = coverCache,
                onTap = { onSelectionChange(index); onOpenGame(game) },
                onLongPressTap = { onSelectionChange(index); onLongPress(game) },
                onFocus = { onSelectionChange(index) }
            )
        }
    }
}

/**
 * 单张封面卡：3:4 封面 + 底部渐变标题 + 选中霓虹描边 + 弧形透视变换。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun NeonWallCard(
    game: GameEntry,
    isSelected: Boolean,
    rootWidth: Float,
    density: Float,
    coverCache: android.util.LruCache<String, android.graphics.Bitmap>,
    onTap: () -> Unit,
    onLongPressTap: () -> Unit,
    onFocus: () -> Unit
) {
    // 卡片实时位置（滚动时由 onGloballyPositioned 持续更新）
    var cardX by remember { mutableFloatStateOf(0f) }
    var cardW by remember { mutableFloatStateOf(0f) }

    val interaction = remember { MutableInteractionSource() }
    val title = game.customTitle?.takeIf { it.isNotBlank() } ?: game.title

    Box(
        modifier = Modifier
            .aspectRatio(0.74f)
            .fillMaxWidth()
            .onGloballyPositioned { coords ->
                cardX = coords.positionInRoot().x
                cardW = coords.size.width.toFloat()
            }
            .graphicsLayer {
                // ===== 弧形墙变换（绘制阶段，零重组）=====
                if (rootWidth > 0f && cardW > 0f) {
                    val center = cardX + cardW / 2f
                    // -1（最左）.. +1（最右），允许超界收敛
                    val d = ((center - rootWidth / 2f) / (rootWidth / 2f))
                        .coerceIn(-1.6f, 1.6f)
                    rotationY = d * 15f
                    cameraDistance = 13f * density
                    val s = (1f - 0.16f * abs(d)).coerceAtLeast(0.72f)
                    scaleX = s
                    scaleY = s
                    alpha = (1f - 0.30f * abs(d)).coerceIn(0.42f, 1f)
                }
                // 选中态轻微弹出
                if (isSelected) {
                    val s = 1.05f
                    scaleX *= s
                    scaleY *= s
                }
            }
            .clip(RoundedCornerShape(8.dp))
            .background(Neon.BgPanelHi)
            .border(
                width = if (isSelected) 2.dp else 1.dp,
                color = if (isSelected) Neon.Accent else Neon.Line,
                shape = RoundedCornerShape(8.dp)
            )
            .combinedClickable(
                interactionSource = interaction,
                indication = null,
                onClick = onTap,
                onLongClick = onLongPressTap
            )
            .focusable(interactionSource = interaction)
            .onFocusChangedCompat { focused -> if (focused) onFocus() }
    ) {
        // 封面
        NeonCoverImage(game, coverCache)

        // 底部渐变 + 标题（图2：半透明黑底白字悬浮在封面底部）
        Box(
            modifier = Modifier
                .align(androidx.compose.ui.Alignment.BottomCenter)
                .fillMaxWidth()
                .background(
                    androidx.compose.ui.graphics.Brush.verticalGradient(
                        0f to Color.Transparent,
                        1f to Color.Black.copy(alpha = 0.78f)
                    )
                )
                .padding(horizontal = 8.dp, vertical = 6.dp)
        ) {
            Column {
                Text(
                    title,
                    color = Color.White,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    game.platform.displayName,
                    color = Neon.Accent.copy(alpha = 0.8f),
                    fontSize = 8.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Start
                )
            }
        }
    }
}

/** focus 事件桥接。 */
private fun Modifier.onFocusChangedCompat(onFocus: (Boolean) -> Unit): Modifier =
    this.then(
        androidx.compose.ui.focus.onFocusChanged { onFocus(it.isFocused) }
    )
