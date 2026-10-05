package com.nesstation.app.ui.neon

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.NetworkCheck
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nesstation.app.ui.fsd.Fsd
import com.nesstation.app.ui.fsd.FsdBackdrop
import com.nesstation.app.ui.fsd.FsdBottomBar
import com.nesstation.app.ui.fsd.FsdBreadcrumb
import com.nesstation.app.ui.fsd.FsdButtonHint
import com.nesstation.app.ui.fsd.FsdButtonHints
import com.nesstation.app.ui.fsd.FsdCounter
import com.nesstation.app.ui.fsd.FsdIconCoverCard
import com.nesstation.app.ui.fsd.FsdTitleBanner
import com.nesstation.app.ui.fsd.FsdToolButton
import com.nesstation.app.ui.fsd.FsdTopBar
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * ★★ 二级页面主题适配层（Neon / FSD 自动切换）★★
 *
 * 需求原话："整体ui设置为neon时在线游戏和对战平台以及swf等没有正确
 * 设计为neon的主题ui，还在fsd"。
 *
 * 在线游戏 / 对战平台 / SWF 列表 / 文件浏览等二级页面原先硬编码 FSD
 * 组件（FsdBackdrop/FsdTopBar/FsdCoverFlow…）。本文件提供与 FSD 组件
 * 调用点【同签名】的 Sec* 适配组件：Neon 主题时渲染 Neon 风格
 * （深空底 + 电光青 + 切角），FSD 主题时原样委托 FSD 组件 ——
 * 调用方只把 Fsd 前缀换成 Sec 前缀即可，业务逻辑零改动。
 *
 * FsdCoverFlow 本体（3D 轮播机制）与主题无关，Neon 下直接复用；
 * 需要换装的是封面卡（[SecCoverCard]）。
 */
object SecChrome {
    /** 当前是否 Neon 风格（便捷读取）。 */
    val neon: Boolean get() = NeonUi.isNeon
}

// =====================================================================
// 背景 / 顶栏 / 底栏
// =====================================================================

/** 背景：Neon=深空网格底 / FSD=深蓝壁纸。 */
@Composable
fun SecBackdrop(modifier: Modifier = Modifier) {
    if (SecChrome.neon) NeonBackdrop(modifier) else FsdBackdrop(modifier)
}

/**
 * 顶栏：Neon=细条状态栏（品牌字 + 时钟，状态栏内边距）；
 * FSD=原版系统监视条（CPU/内存/存储）。
 */
@Composable
fun SecTopBar(modifier: Modifier = Modifier) {
    if (SecChrome.neon) {
        var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
        LaunchedEffect(Unit) {
            while (true) { now = System.currentTimeMillis(); delay(2500) }
        }
        val time = remember(now) {
            SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(now))
        }
        Row(
            modifier = modifier
                .fillMaxWidth()
                .background(Color(0xCC05070E))
                .statusBarsPadding()
                .padding(horizontal = 16.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 品牌字：电光青 + 字距拉开的科技感
            Text(
                "NES STATION",
                color = Neon.Accent,
                fontSize = 11.sp,
                fontWeight = FontWeight.Black,
                letterSpacing = 3.sp
            )
            Text(
                "  //  NEON",
                color = Neon.TextDim,
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 2.sp
            )
            Spacer(Modifier.weight(1f))
            Text(
                time,
                color = Neon.TextHi,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace
            )
        }
    } else {
        FsdTopBar(modifier)
    }
}

/**
 * 底栏：Neon=细条状态栏（网络 + 状态 + 日期时间）；FSD=原版状态条。
 */
@Composable
fun SecBottomBar(status: String = "空闲", modifier: Modifier = Modifier) {
    if (SecChrome.neon) {
        var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
        LaunchedEffect(Unit) {
            while (true) { now = System.currentTimeMillis(); delay(1000) }
        }
        val date = remember(now) {
            SimpleDateFormat("yyyy-MM-dd EEE", Locale.getDefault()).format(Date(now))
        }
        val time = remember(now) {
            SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(now))
        }
        val ip = remember { com.nesstation.app.ui.fsd.FsdSysInfo.localIp() }
        Row(
            modifier = modifier
                .fillMaxWidth()
                .background(Color(0xCC05070E))
                .navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 5.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Rounded.NetworkCheck, null,
                tint = Neon.TextDim, modifier = Modifier.size(14.dp)
            )
            Text(
                ip.ifEmpty { "未联网" },
                color = Neon.Text,
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                fontFamily = FontFamily.Monospace
            )
            Text("状态: $status", color = Neon.TextDim, fontSize = 11.sp)
            Spacer(Modifier.weight(1f))
            Text(date, color = Neon.TextDim, fontSize = 11.sp)
            Spacer(Modifier.width(12.dp))
            Text(
                time,
                color = Neon.TextHi,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace
            )
        }
    } else {
        FsdBottomBar(status = status, modifier = modifier)
    }
}

// =====================================================================
// 工具行
// =====================================================================

/** 工具按钮：Neon=切角工具钮 / FSD=原版。 */
@Composable
fun SecToolButton(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit
) {
    if (SecChrome.neon) {
        NeonToolbarButton(icon = icon, label = label, onClick = onClick)
    } else {
        FsdToolButton(icon = icon, label = label, onClick = onClick)
    }
}

/** 面包屑：Neon=斜杠路径（末段高亮青）/ FSD=原版。 */
@Composable
fun SecBreadcrumb(
    path: List<String>,
    modifier: Modifier = Modifier
) {
    if (SecChrome.neon) {
        Row(
            modifier = modifier.padding(horizontal = 20.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            path.forEachIndexed { idx, seg ->
                if (idx > 0) {
                    Text(
                        "  /  ",
                        color = Neon.TextDim,
                        fontSize = 12.sp
                    )
                }
                Text(
                    seg,
                    color = if (idx == path.lastIndex) Neon.TextHi else Neon.TextDim,
                    fontSize = if (idx == path.lastIndex) 18.sp else 13.sp,
                    fontWeight = if (idx == path.lastIndex) FontWeight.ExtraBold else FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    } else {
        FsdBreadcrumb(path = path, modifier = modifier)
    }
}

// =====================================================================
// 封面流配套
// =====================================================================

/**
 * 封面卡（FsdIconCoverCard 的 Neon 换装版，签名完全一致）：
 * 切角深空面板 + 强调色渐变 + 图标/自定义图 + 底部标题条 + 徽标。
 */
@Composable
fun SecCoverCard(
    title: String,
    icon: ImageVector,
    accent: Color,
    modifier: Modifier = Modifier,
    badge: String? = null,
    subtitle: String? = null,
    iconPath: String? = null
) {
    if (!SecChrome.neon) {
        FsdIconCoverCard(
            title = title, icon = icon, accent = accent, modifier = modifier,
            badge = badge, subtitle = subtitle, iconPath = iconPath
        )
        return
    }
    val customBmp = androidx.compose.runtime.remember(iconPath) {
        if (iconPath.isNullOrBlank()) null
        else com.nesstation.app.ui.fsd.FsdImaging.decodeFile(iconPath, 512, 512)
    }
    val shape = neonChamfer(0.10f)
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Neon.BgPanelHi, shape)
            .border(1.5.dp, accent.copy(alpha = 0.55f), shape)
            .clip(shape)
    ) {
        if (customBmp != null) {
            Image(
                bitmap = customBmp.asImageBitmapCompat(),
                contentDescription = title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            // 渐变底 + 居中图标
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        androidx.compose.ui.graphics.Brush.verticalGradient(
                            listOf(accent.copy(alpha = 0.34f), Neon.BgPanel.copy(alpha = 0.92f))
                        )
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    icon, title,
                    tint = Color.White.copy(alpha = 0.92f),
                    modifier = Modifier.size(56.dp)
                )
            }
        }
        // 徽标（左上切角内）
        if (badge != null) {
            Box(
                modifier = Modifier
                    .padding(6.dp)
                    .background(Color(0xB3000000), neonChamfer(0.35f))
                    .border(1.dp, accent.copy(alpha = 0.8f), neonChamfer(0.35f))
                    .padding(horizontal = 7.dp, vertical = 2.dp)
            ) {
                Text(badge, color = accent, fontSize = 9.sp, fontWeight = FontWeight.Black)
            }
        }
        // 底部标题条（渐变压暗保证可读）
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(
                    androidx.compose.ui.graphics.Brush.verticalGradient(
                        listOf(Color.Transparent, Color(0xD902040C))
                    )
                )
                .padding(horizontal = 10.dp, vertical = 8.dp)
        ) {
            Column {
                Text(
                    title,
                    color = Neon.TextHi,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (!subtitle.isNullOrBlank()) {
                    Text(
                        subtitle,
                        color = Neon.TextDim,
                        fontSize = 9.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

/** 按键提示：Neon=切角提示条 / FSD=原版。 */
@Composable
fun SecButtonHints(
    hints: List<FsdButtonHint>,
    modifier: Modifier = Modifier
) {
    if (SecChrome.neon) {
        NeonHintsBar(
            hints = hints.map { Triple(it.letter, it.label, it.color) },
            modifier = modifier
        )
    } else {
        FsdButtonHints(hints = hints, modifier = modifier)
    }
}

/** 标题横幅：Neon=切角面板（青描边）/ FSD=金属灰横幅。 */
@Composable
fun SecTitleBanner(
    text: String,
    modifier: Modifier = Modifier
) {
    if (SecChrome.neon) {
        Box(
            modifier = modifier
                .background(Color(0xCC0A1020), neonChamfer(0.5f))
                .border(1.dp, Neon.AccentDim, neonChamfer(0.5f))
                .padding(horizontal = 22.dp, vertical = 8.dp)
        ) {
            Text(
                text = text,
                color = Neon.TextHi,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    } else {
        FsdTitleBanner(text = text, modifier = modifier)
    }
}

/** 计数（N of M）：Neon=切角计数条 / FSD=原版。 */
@Composable
fun SecCounter(current: Int, total: Int, modifier: Modifier = Modifier) {
    if (SecChrome.neon) {
        Box(
            modifier = modifier
                .background(Color(0xCC05070E), neonChamfer(0.5f))
                .border(1.dp, Neon.Line, neonChamfer(0.5f))
                .padding(horizontal = 14.dp, vertical = 6.dp)
        ) {
            Text(
                text = "$current / $total",
                color = Neon.Accent,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                fontFamily = FontFamily.Monospace
            )
        }
    } else {
        FsdCounter(current = current, total = total, modifier = modifier)
    }
}

/** 兼容帮助：Bitmap → ImageBitmap（Compose 扩展函数转发）。 */
private fun android.graphics.Bitmap.asImageBitmapCompat(): androidx.compose.ui.graphics.ImageBitmap =
    this.asImageBitmap()
