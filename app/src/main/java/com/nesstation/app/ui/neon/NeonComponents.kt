package com.nesstation.app.ui.neon

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Computer
import androidx.compose.material.icons.rounded.Gamepad
import androidx.compose.material.icons.rounded.LocalCafe
import androidx.compose.material.icons.rounded.LocalPlay
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.MenuBook
import androidx.compose.material.icons.rounded.MobileFriendly
import androidx.compose.material.icons.rounded.Radio
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Smartphone
import androidx.compose.material.icons.rounded.SportsEsports
import androidx.compose.material.icons.rounded.TabletAndroid
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material.icons.rounded.Tv
import androidx.compose.material.icons.rounded.VideogameAsset
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nesstation.app.core.model.GameEntry
import com.nesstation.app.core.model.GamePlatform
import kotlin.math.abs

// =====================================================================
// 图标 / 文字工具
// =====================================================================

/** 平台 → 专属图标（与 FSD 主页同图标语义，保证跨风格辨识一致）。 */
fun neonPlatformIcon(p: GamePlatform): ImageVector = when (p) {
    GamePlatform.NES -> Icons.Rounded.Gamepad
    GamePlatform.SFC -> Icons.Rounded.VideogameAsset
    GamePlatform.GB -> Icons.Rounded.Smartphone
    GamePlatform.GBA -> Icons.Rounded.MobileFriendly
    GamePlatform.MD -> Icons.Rounded.Computer
    GamePlatform.PCE -> Icons.Rounded.Radio
    GamePlatform.PSX -> Icons.Rounded.Album
    GamePlatform.PS2 -> Icons.Rounded.Memory
    GamePlatform.NDS -> Icons.Rounded.MenuBook
    GamePlatform.ARCADE -> Icons.Rounded.LocalPlay
    GamePlatform.DOS -> Icons.Rounded.Terminal
    GamePlatform.JAVA -> Icons.Rounded.LocalCafe
    GamePlatform.DC -> Icons.Rounded.SportsEsports
    GamePlatform.N3DS -> Icons.Rounded.TabletAndroid
    GamePlatform.NGCWII -> Icons.Rounded.Tv
}

/** 手柄按键提示胶囊（底部提示栏单元）：圆形字母 + 说明文字。 */
@Composable
fun NeonHintPill(letter: String, label: String, color: Color, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        Box(
            modifier = Modifier
                .size(16.dp)
                .background(color.copy(alpha = 0.16f), CircleShape)
                .border(1.dp, color, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Text(letter, color = color, fontSize = 10.sp, fontWeight = FontWeight.Black)
        }
        Text(label, color = Neon.Text, fontSize = 11.sp, fontWeight = FontWeight.Medium)
    }
}

/** 底部手柄按键提示栏（切角胶囊）。 */
@Composable
fun NeonHintsBar(
    hints: List<Triple<String, String, Color>>,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .background(Color(0xCC05070E), neonChamfer(0.5f))
            .border(1.dp, Neon.Line, neonChamfer(0.5f))
            .padding(horizontal = 14.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        hints.forEach { (letter, label, color) ->
            NeonHintPill(letter, label, color)
        }
    }
}

// =====================================================================
// 背景
// =====================================================================

/**
 * Neon 深空背景：径向青光 + 顶部渐变 + 细网格线。
 * 全部页面（主页/游戏库/设置）共用，保证风格统一。
 */
@Composable
fun NeonBackdrop(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    0f to Color(0xFF0A1428),
                    0.45f to Neon.Bg,
                    1f to Neon.BgDeep
                )
            )
    ) {
        // 网格微纹（低透明度竖线，绘制阶段无重组开销）
        Row(modifier = Modifier.fillMaxSize()) {
            repeat(12) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxSize()
                        .background(Neon.Line.copy(alpha = 0.14f))
                )
            }
        }
        // 底部地平线微光（衬托封面流倒影）
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(2.dp)
                .align(Alignment.BottomCenter)
                .background(
                    Brush.horizontalGradient(
                        listOf(Color.Transparent, Neon.AccentDim.copy(alpha = 0.55f), Color.Transparent)
                    )
                )
        )
    }
}

// =====================================================================
// 封面
// =====================================================================

/**
 * Neon 封面图 —— 与 FsdGameCover 同源的位图解析链
 * （customIconPath → coverPath → GameIconExtractor 回退生成），
 * 共享 LruCache 防重复解码；仅渲染图片本体，外框由调用方按风格自绘。
 */
@Composable
fun NeonCoverImage(
    game: GameEntry,
    cache: android.util.LruCache<String, android.graphics.Bitmap>
) {
    val context = LocalContext.current
    val cacheKey = "${game.id}|${game.customIconPath ?: ""}|${game.coverPath ?: ""}"
    val coverStamp = game.coverPath?.let {
        try { java.io.File(it).lastModified() } catch (_: Throwable) { 0L }
    } ?: 0L
    val fullKey = "$cacheKey|$coverStamp"
    val bmp = remember(fullKey) {
        cache.get(fullKey) ?: run {
            var b: android.graphics.Bitmap? = null
            val path = try {
                com.nesstation.app.core.storage.GameIconExtractor.resolveIconPath(context, game)
            } catch (_: Exception) { null }
            if (path != null) {
                try {
                    b = android.graphics.BitmapFactory.decodeFile(path)
                } catch (_: Exception) { b = null }
            }
            if (b == null) {
                try {
                    b = com.nesstation.app.core.storage.GameIconExtractor
                        .generateFallbackCover(game, 320, 420)
                } catch (_: Exception) {
                    b = android.graphics.Bitmap.createBitmap(4, 4, android.graphics.Bitmap.Config.ARGB_8888)
                }
            }
            cache.put(fullKey, b!!)
            b!!
        }
    }
    Image(
        bitmap = bmp.asImageBitmap(),
        contentDescription = game.customTitle?.takeIf { it.isNotBlank() } ?: game.title,
        contentScale = ContentScale.Crop,
        modifier = Modifier.fillMaxSize()
    )
}

/**
 * 3D 封面卡（NeonFlow / 横向行共用）：
 * 3:4 封面 + 切角描边 + 选中青色辉光（spotColor 点光阴影，API 28+ 生效）
 * + 收藏星标。[glow] 0f..1f 控制辉光强度（选中=1）。
 */
@Composable
fun NeonCoverCard(
    game: GameEntry,
    cache: android.util.LruCache<String, android.graphics.Bitmap>,
    glow: Float,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier.graphicsLayer {
            if (glow > 0.01f) {
                // 选中辉光：青色点光阴影 + 轻微浮起（API 28+ 彩色，以下版本黑色柔和阴影）
                shadowElevation = 18f * glow
                spotColor = Neon.Accent
                ambientColor = Neon.AccentDim
                translationZ = 6f * glow
            }
        }
    ) {
        // 封面本体
        Box(
            modifier = Modifier
                .matchParentSize()
                .background(Neon.BgPanelHi, neonChamfer(0.10f))
                .border(
                    width = if (glow > 0.5f) 2.dp else 1.dp,
                    color = if (glow > 0.5f) Neon.Accent else Neon.Line,
                    shape = neonChamfer(0.10f)
                )
                .clip(neonChamfer(0.10f))
        ) {
            NeonCoverImage(game, cache)
            // 顶部斜面高光（3D 质感，选中时更亮）
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(28.dp)
                    .background(
                        Brush.verticalGradient(
                            0f to Color.White.copy(alpha = if (glow > 0.5f) 0.22f else 0.12f),
                            1f to Color.Transparent
                        )
                    )
            )
        }
        // 收藏星标
        if (game.isFavorite) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(6.dp)
                    .size(14.dp)
                    .background(Color(0x99000000), CircleShape)
                    .border(1.dp, Neon.Gold.copy(alpha = 0.8f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Text("★", color = Neon.Gold, fontSize = 8.sp)
            }
        }
    }
}

// =====================================================================
// 3D 倾斜行卡片
// =====================================================================

/**
 * 3D 倾斜行卡容器：横向滚动行中的卡片按「距屏幕中心的水平距离」
 * 施加 Y 轴透视旋转 + 缩放 + 变暗（绘制阶段计算，滚动零重组）。
 * 用于主页的次要行（收藏等）—— 主视觉 3D 由 NeonFlow 负责。
 * 屏幕宽度经 LocalConfiguration 获取（行通常铺满屏宽，稳定可靠）。
 */
@Composable
fun NeonTiltRowCard(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    var cardX by remember { mutableFloatStateOf(0f) }
    var cardW by remember { mutableFloatStateOf(0f) }
    val screenW = with(androidx.compose.ui.platform.LocalDensity.current) {
        androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp.dp.toPx()
    }
    Box(
        modifier = modifier
            .onGloballyPositioned { coords ->
                cardX = coords.positionInRoot().x
                cardW = coords.size.width.toFloat()
            }
            .graphicsLayer {
                if (screenW > 0f && cardW > 0f) {
                    val center = cardX + cardW / 2f
                    val d = ((center - screenW / 2f) / (screenW / 2f)).coerceIn(-1.5f, 1.5f)
                    rotationY = d * 16f
                    cameraDistance = 12f * density
                    val s = (1f - 0.14f * abs(d)).coerceAtLeast(0.78f)
                    scaleX = s
                    scaleY = s
                    alpha = (1f - 0.26f * abs(d)).coerceIn(0.5f, 1f)
                }
            }
    ) {
        content()
    }
}

// =====================================================================
// 按钮 / 输入
// =====================================================================

/**
 * Neon 切角工具栏按钮（游戏库顶部工具条 / 设置页操作）。
 * 图标 + 下方小字标签，聚焦/选中时青色描边提亮。
 */
@Composable
fun NeonToolbarButton(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = Neon.Text
) {
    val interaction = remember { MutableInteractionSource() }
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
            .background(Neon.BgPanel.copy(alpha = 0.6f), neonChamfer(0.22f))
            .border(1.dp, Neon.Line, neonChamfer(0.22f))
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 7.dp)
    ) {
        Icon(icon, label, tint = tint, modifier = Modifier.size(20.dp))
        Spacer(Modifier.height(3.dp))
        Text(label, color = tint, fontSize = 9.sp, fontWeight = FontWeight.Medium, maxLines = 1)
    }
}

/** Neon 搜索输入框（游戏库顶栏内嵌，深色底 + 青色光标）。 */
@Composable
fun NeonSearchField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    hint: String = "搜索游戏…"
) {
    Row(
        modifier = modifier
            .background(Color(0xCC05070E), neonChamfer(0.4f))
            .border(1.dp, if (value.isNotEmpty()) Neon.AccentDim else Neon.Line, neonChamfer(0.4f))
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Rounded.Search, null, tint = Neon.TextDim, modifier = Modifier.size(15.dp))
        Spacer(Modifier.width(7.dp))
        Box(modifier = Modifier.weight(1f)) {
            if (value.isEmpty()) {
                Text(hint, color = Neon.TextDim, fontSize = 11.sp)
            }
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                textStyle = TextStyle(color = Neon.TextHi, fontSize = 11.sp),
                cursorBrush = Brush.verticalGradient(listOf(Neon.Accent, Neon.Accent)),
                modifier = Modifier.fillMaxWidth()
            )
        }
        if (value.isNotEmpty()) {
            Spacer(Modifier.width(5.dp))
            Box(
                modifier = Modifier
                    .size(15.dp)
                    .background(Neon.Line, CircleShape)
                    .clickable { onValueChange("") },
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Rounded.Close, null, tint = Neon.Text, modifier = Modifier.size(10.dp))
            }
        }
    }
}

/** Neon 主操作按钮（切角 + 青色渐变底，主页「继续游戏」等）。 */
@Composable
fun NeonPrimaryButton(
    text: String,
    icon: ImageVector? = null,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val interaction = remember { MutableInteractionSource() }
    Row(
        modifier = modifier
            .background(
                Brush.horizontalGradient(
                    listOf(Neon.AccentDim.copy(alpha = 0.85f), Neon.Accent.copy(alpha = 0.55f))
                ),
                neonChamfer(0.3f)
            )
            .border(1.dp, Neon.Accent, neonChamfer(0.3f))
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp)
    ) {
        icon?.let { Icon(it, null, tint = Color(0xFF02030A), modifier = Modifier.size(16.dp)) }
        Text(text, color = Color(0xFF02030A), fontSize = 13.sp, fontWeight = FontWeight.Black)
    }
}

/** Neon 次操作按钮（描边幽灵按钮）。 */
@Composable
fun NeonGhostButton(
    text: String,
    icon: ImageVector? = null,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val interaction = remember { MutableInteractionSource() }
    Row(
        modifier = modifier
            .background(Neon.BgPanel.copy(alpha = 0.55f), neonChamfer(0.3f))
            .border(1.dp, Neon.Line, neonChamfer(0.3f))
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        icon?.let { Icon(it, null, tint = Neon.Text, modifier = Modifier.size(15.dp)) }
        Text(text, color = Neon.Text, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
    }
}
