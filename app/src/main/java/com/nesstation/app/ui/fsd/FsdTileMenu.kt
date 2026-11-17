package com.nesstation.app.ui.fsd

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

/**
 * FSD 主菜单磁贴 — 仿 Xbox 360 Freestyle Dash 的蓝/黄对角大磁贴。
 *
 * 视觉拆解（对照截图）：
 *   - 圆角矩形，上部为蓝色渐变，下部为黄色渐变，交界是一条左低右高的对角线
 *   - 白色图标悬浮在蓝色区域中央偏上
 *   - 左下角白色粗体标签
 *   - 表面有一条淡淡的斜向高光，营造玻璃质感
 *
 * 直接作为 [FsdCoverFlow]（showReflection=false）的 item content 使用，
 * 获得 FSD 截图里「中间大、两侧渐退」的主菜单排布。
 */
data class FsdTileItem(
    val key: String,
    val label: String,
    val icon: ImageVector?,
    val badge: String? = null,   // 右上角小徽标（如游戏数量）
    val iconPath: String? = null, // 自定义图标（用户挑选的图片，绝对路径）；非空时优先于 [icon]
    val iconAlpha: Float = 1f    // 卡片透明度 0.05..1.0（磁贴选项里调节）；作用于整张卡片
)

@Composable
fun FsdTile(
    item: FsdTileItem,
    modifier: Modifier = Modifier,
    compactTile: Boolean = false
) {
    Box(
        modifier = modifier
            // 修复（卡片透明度不生效）：alpha 必须放在 clip/background 之前。
            // Compose 修饰符是从左到右包襄的：旧写法 .background(navy).alpha(x)
            // 里，不透明海军蓝底色画在 alpha 层之外，永远 100% 不透明 ——
            // 拖动透明度滑杆只能淡出渐变表面/图标/文字，卡片本身永不透光，
            // 看起来就是“透明度没有应用”。现在 alpha 放在最前，连同底色、
            // 渐变表面、图标、徽标、文字一起整体变透明。
            // （左侧传入的 modifier 里的选中高亮边框在 alpha 层外，
            //  保持全亮 —— 焦点指示不受透明度影响。）
            .alpha(item.iconAlpha.coerceIn(0.05f, 1f))
            .clip(RoundedCornerShape(10.dp))
            .background(Color(0xFF0D2C55))
    ) {
        // 蓝/黄对角渐变表面
        Canvas(modifier = Modifier.fillMaxSize()) { drawFsdTileSurface() }

        // 图标 — 自定义图片优先，否则用平台专属矢量图标；蓝色区域中央偏上
        if (item.iconPath != null) {
            val bmp = remember(item.iconPath) {
                FsdImaging.decodeFile(item.iconPath!!, 220, 220)
            }
            if (bmp != null) {
                Image(
                    bitmap = bmp.asImageBitmap(),
                    contentDescription = item.label,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = if (compactTile) 16.dp else 30.dp)
                        .size(if (compactTile) 46.dp else 68.dp)
                        .clip(RoundedCornerShape(10.dp))
                )
            } else if (item.icon != null) {
                TileVectorIcon(item, compactTile)
            }
        } else if (item.icon != null) {
            TileVectorIcon(item, compactTile)
        }

        // 徽标（游戏数量）
        if (item.badge != null) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(10.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color.Black.copy(alpha = 0.35f))
                    .padding(horizontal = 8.dp, vertical = 2.dp)
            ) {
                Text(
                    text = item.badge,
                    color = Color.White,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        // 标签 — 左下角
        Text(
            text = item.label,
            color = Color(0xFF1A1206),
            fontSize = if (compactTile) 15.sp else 19.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(
                    start = if (compactTile) 12.dp else 16.dp,
                    bottom = if (compactTile) 8.dp else 12.dp,
                    end = 12.dp
                )
        )
    }
}

/** 平台专属矢量图标（自定义图标缺失/解码失败时的回退）。 */
@Composable
private fun androidx.compose.foundation.layout.BoxScope.TileVectorIcon(
    item: FsdTileItem,
    compactTile: Boolean
) {
    Icon(
        imageVector = item.icon!!, // 调用方已判空
        contentDescription = item.label,
        tint = Color.White,
        modifier = Modifier
            .align(Alignment.TopCenter)
            .padding(top = if (compactTile) 18.dp else 34.dp)
            .size(if (compactTile) 44.dp else 56.dp)
    )
}

private fun DrawScope.drawFsdTileSurface() {
    val w = size.width
    val h = size.height

    // 1. 蓝色基底
    drawRect(
        brush = Brush.verticalGradient(listOf(Fsd.TileBlueTop, Fsd.TileBlueBottom))
    )

    // 2. 黄色对角块 — 左低右高的斜线切分（FSD 标志性配色）
    val splitLeftY = h * 0.62f
    val splitRightY = h * 0.38f
    val p = Path().apply {
        moveTo(0f, splitLeftY)
        lineTo(w, splitRightY)
        lineTo(w, h)
        lineTo(0f, h)
        close()
    }
    drawPath(
        p,
        brush = Brush.verticalGradient(
            listOf(Fsd.TileYellowTop, Fsd.TileYellowBottom),
            startY = splitRightY, endY = h
        )
    )

    // 3. 对角分界高光细线
    drawLine(
        color = Color.White.copy(alpha = 0.35f),
        start = Offset(0f, splitLeftY),
        end = Offset(w, splitRightY),
        strokeWidth = 2f
    )

    // 4. 表面斜向高光（玻璃质感）
    val gloss = Path().apply {
        moveTo(0f, 0f)
        lineTo(w * 0.45f, 0f)
        lineTo(w * 0.10f, h)
        lineTo(0f, h)
        close()
    }
    drawPath(gloss, color = Color.White.copy(alpha = 0.06f))
}

/**
 * FSD 主菜单 — 由 [FsdTileItem] 列表驱动的磁贴封面流。
 *
 * 布局对照截图 2：
 *   - 中央磁贴最大，右侧磁贴依次渐退（cover-flow 排布）
 *   - 下方居中显示「N of M」计数
 *   - 磁贴下方标题即 [FsdTileItem.label]（在磁贴内部）
 *
 * 竖屏（<600dp 宽）自动切换紧凑尺寸：磁贴缩小到 ~190dp，左右邻贴各露一角，
 * 不再“整屏只看到一个磁贴”；横屏保持 296dp 大磁贴。
 */
@Composable
fun FsdTileFlow(
    items: List<FsdTileItem>,
    selectedIndex: Int,
    onIndexChange: (Int) -> Unit,
    onActivate: (Int) -> Unit,
    modifier: Modifier = Modifier,
    onItemLongClick: (Int) -> Unit = {}
) {
    val compact = LocalConfiguration.current.screenWidthDp < 600
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        FsdCoverFlow(
            count = items.size,
            selectedIndex = selectedIndex,
            onIndexChange = onIndexChange,
            onItemClick = onActivate,
            onItemLongClick = onItemLongClick,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            itemWidth = if (compact) 190.dp else 296.dp,
            itemHeight = if (compact) 122.dp else 196.dp,
            gap = if (compact) 16.dp else 26.dp,
            tiltDegrees = if (compact) 10f else 14f,
            fadePerStep = 0.26f,
            scalePerStep = 0.22f,
            showReflection = false,
            grabFocusOnLaunch = true
        ) { i ->
            FsdTile(items[i], modifier = Modifier.fillMaxSize(), compactTile = compact)
        }

        // N of M 计数
        if (items.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            FsdCounter(
                current = (selectedIndex + 1).coerceIn(1, items.size),
                total = items.size
            )
            Spacer(Modifier.height(10.dp))
        }
    }
}

/**
 * 主页分区：一个标题 + 一组磁贴。
 *
 * 主页为「3 个横向滑动的分区行」：游戏库 / 在线·对战·SWF / 设置·关于·退出。
 * 每个分区一个 [FsdMenuSection] —— 标题写在分区标题条上，磁贴保留 FSD 蓝/黄
 * 封面样式，按一行横向排列；页面整体上下滚动即可秒达任意分区，行内磁贴
 * 左右滑动浏览（与游戏库封面流一致）。
 */
data class FsdMenuSection(
    val key: String,          // 分区唯一标识（如 "library"）
    val title: String,        // 分区标题（如 "游戏库"）
    val items: List<FsdTileItem>
)

/** FSD 分区标题条：黄色圆点 + 白色粗体大标题，替代原来顶部单一面包屑。 */
@Composable
fun FsdSectionHeader(
    title: String,
    subtitle: String? = null,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 黄色圆点（FSD 标志色）
        Box(
            modifier = Modifier
                .size(12.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(Fsd.TileYellowTop)
        )
        Spacer(Modifier.width(12.dp))
        Text(
            text = title,
            color = Color.White,
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        if (subtitle != null) {
            Spacer(Modifier.width(10.dp))
            Text(
                text = subtitle,
                color = Fsd.BarTextDim,
                fontSize = 13.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/**
 * 主页分区行 — 一行横向封面流磁贴（配合 [FsdMenuSection]）。
 *
 * 主页 3 个分区（游戏库 / 在线·对战·SWF / 设置·关于·退出）按「三行
 * cover flow」排布：选中行居中放大、上下行变小变暗（由 [FsdHomeScreen]
 * 的垂直封面流控制位移/缩放/变暗）。每行内部同样采用横向封面流 [FsdCoverFlow]：
 *   - 行内选中的卡片居中放大、正对用户，带黄色高亮边框
 *   - 两侧卡片按距离缩放 + 淡出（cover-flow 效果）
 *
 * TV/遥控器：左右切换行内磁贴、上下切换分区、OK 激活、Y 呼出磁贴选项。
 * 触屏：横向滑动浏览行内磁贴、纵向滑动切换分区、点按激活、长按呼出磁贴选项。
 * 点击/聚焦未选中的行（任意磁贴）会先把该行选中居中，再切换行内磁贴。
 */
@Composable
fun FsdSectionRow(
    section: FsdMenuSection,
    selectedIndex: Int,
    isFocused: Boolean,
    onIndexChange: (Int) -> Unit,
    onActivate: (FsdTileItem) -> Unit,
    onItemLongClick: (FsdTileItem) -> Unit,
    onFocusUp: () -> Unit,
    onFocusDown: () -> Unit,
    onFocusSelf: () -> Unit,
    modifier: Modifier = Modifier
) {
    val compact = LocalConfiguration.current.screenWidthDp < 600
    val itemW = if (compact) 168.dp else 220.dp
    val itemH = if (compact) 104.dp else 136.dp
    val gap = if (compact) 14.dp else 24.dp
    val focusRequester = remember { FocusRequester() }

    // 分区被选中时：抓焦点（之后 D-pad 上下切换分区）
    LaunchedEffect(isFocused) {
        if (isFocused) {
            delay(80)
            runCatching { focusRequester.requestFocus() }
        }
    }

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        FsdSectionHeader(
            title = section.title,
            modifier = Modifier.padding(horizontal = 14.dp)
        )
        // 行内横向封面流：选中的卡片居中放大，两侧卡片变小变暗。
        // 外层 Box 统一处理 D-pad（左右/上下/OK/Y），避免与封面流内置键位冲突。
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(itemH)
                .focusRequester(focusRequester)
                .focusable()
                .onKeyEvent { e ->
                    if (e.type != androidx.compose.ui.input.key.KeyEventType.KeyUp) {
                        false
                    } else when (e.key) {
                        Key.DirectionLeft -> {
                            if (selectedIndex > 0) onIndexChange(selectedIndex - 1)
                            true
                        }
                        Key.DirectionRight -> {
                            if (selectedIndex < section.items.size - 1) onIndexChange(selectedIndex + 1)
                            true
                        }
                        Key.DirectionUp -> { onFocusUp(); true }
                        Key.DirectionDown -> { onFocusDown(); true }
                        Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> {
                            section.items.getOrNull(selectedIndex)?.let(onActivate)
                            true
                        }
                        Key.Y, Key.ButtonY -> {
                            section.items.getOrNull(selectedIndex)?.let(onItemLongClick)
                            true
                        }
                        else -> false
                    }
                }
        ) {
            FsdCoverFlow(
                count = section.items.size,
                selectedIndex = selectedIndex,
                // 未选中行内的点击：先把该行选中居中，再切换行内磁贴
                onIndexChange = { i ->
                    if (!isFocused) onFocusSelf()
                    onIndexChange(i)
                },
                // 选中行点中间磁贴 = 激活；未选中行点任意磁贴 = 选中该行并同步行内磁贴
                onItemClick = { i ->
                    if (!isFocused) {
                        onFocusSelf()
                        onIndexChange(i)
                    } else {
                        onActivate(section.items[i])
                    }
                },
                onItemLongClick = { i -> onItemLongClick(section.items[i]) },
                modifier = Modifier.fillMaxSize(),
                itemWidth = itemW,
                itemHeight = itemH,
                gap = gap,
                tiltDegrees = if (compact) 8f else 10f,
                fadePerStep = 0.2f,
                scalePerStep = 0.18f,
                showReflection = false,
                visibleHalfWindow = 5,
                grabFocusOnLaunch = false
            ) { i ->
                FsdTile(
                    item = section.items[i],
                    modifier = Modifier
                        .fillMaxSize()
                        .then(
                            // 选中分区中，当前磁贴加黄色高亮边框
                            if (isFocused && i == selectedIndex) {
                                Modifier.border(
                                    width = 3.dp,
                                    color = Fsd.TileYellowTop,
                                    shape = RoundedCornerShape(10.dp)
                                )
                            } else Modifier
                        ),
                    compactTile = true
                )
            }
        }
    }
}
