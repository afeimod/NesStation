package com.nesstation.app.ui.fsd

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * FSD 封面卡片（图标版）— 在线游戏 / SWF 等没有真实封面位图的条目使用。
 *
 * 视觉与游戏库的 FsdGameCover 完全同一套语言：
 *   - 深蓝底 (0xFF0D2C55) + 白色半透明边框
 *   - 「封面」区用强调色对角渐变替代游戏封面图（每个条目循环取色）
 *   - 居中白色大图标
 *   - 左上角半透明黑底徽标（对应 FsdPlatformBadge）
 *   - 底部黑色渐变可读性遮罩 + 标题/副标题（域名、文件大小等）
 *
 * 配合 [FsdCoverFlow] 使用即可获得与游戏库一致的封面流效果
 * （居中放大、两侧缩放淡出、倒影、D-pad 导航）。
 */
@Composable
fun FsdIconCoverCard(
    title: String,
    icon: ImageVector,
    accent: Color,
    modifier: Modifier = Modifier,
    badge: String? = null,        // 左上角徽标（如 "WEB" / "SWF" / "PC" / "手机"）
    subtitle: String? = null,     // 底部标题下的小字（域名 / 文件大小等）
    iconPath: String? = null      // 自定义封面图片路径（存在时替代渐变+图标）
) {
    // 缓存解码结果 — 多数情况下 iconPath 为空，布局成本可忽略
    val customBmp = androidx.compose.runtime.remember(iconPath) {
        if (iconPath.isNullOrBlank()) null
        else FsdImaging.decodeFile(iconPath, 512, 512)
    }
    Box(
        modifier = modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(10.dp))
            .background(Color(0xFF0D2C55))
            .border(
                width = 2.dp,
                color = Color.White.copy(alpha = 0.35f),
                shape = RoundedCornerShape(10.dp)
            )
    ) {
        if (customBmp != null) {
            // 自定义图标封面：整卡铺满图片
            androidx.compose.foundation.Image(
                bitmap = customBmp.asImageBitmap(),
                contentDescription = title,
                contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
            // 底部压一条渐变保证标题可读
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .background(
                        Brush.verticalGradient(
                            0f to Color.Transparent,
                            1f to Color.Black.copy(alpha = 0.80f)
                        )
                    )
                    .height(48.dp)
            )
        } else {
            // 「封面」区：强调色对角渐变（从左上到右下淡入深蓝，模拟封面 art）
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.linearGradient(
                            listOf(
                                accent.copy(alpha = 0.48f),
                                accent.copy(alpha = 0.16f),
                                Color(0xFF0D2C55)
                            )
                        )
                    )
            )

            // 居中白色大图标（替代封面主视觉）
            Icon(
                imageVector = icon,
                contentDescription = title,
                tint = Color.White.copy(alpha = 0.92f),
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(top = 14.dp)
                    .size(58.dp)
            )
        }

        // 左上角徽标 — 与 FsdPlatformBadge 同款半透明黑底白字
        if (badge != null) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(6.dp)
                    .clip(RoundedCornerShape(5.dp))
                    .background(Color.Black.copy(alpha = 0.55f))
                    .padding(horizontal = 6.dp, vertical = 2.dp)
            ) {
                Text(
                    text = badge,
                    color = Color.White,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        // 底部渐变 + 标题（与 FsdGameCover 相同的可读性遮罩）
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(
                    Brush.verticalGradient(
                        0f to Color.Transparent,
                        1f to Color.Black.copy(alpha = 0.72f)
                    )
                )
                .padding(horizontal = 10.dp, vertical = 6.dp)
        ) {
            Column {
                Text(
                    text = title,
                    color = Color.White,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (subtitle != null) {
                    Text(
                        text = subtitle,
                        color = Color(0xFF9FB6D4),
                        fontSize = 9.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}
