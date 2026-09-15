package com.nesstation.app.ui.emulator

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nesstation.app.core.engine.DraSticEngine

/**
 * NDS 游戏启动时的**双核心选择对话框**。
 *
 * NesStation 的 NDS 平台有两个可选拟核心：
 *  - **melonDS**（默认）：高精度开源核心，支持 DSi / OpenGL 渲染器 /
 *    放大滤镜 / 联机对战帧同步；
 *  - **DraStic（激烈）**：商用级性能核心，帧率与兼容性表现出色，
 *    同时提供 32 位（armeabi-v7a）与 64 位（arm64-v8a）原生库。
 *
 * 每次启动 NDS 游戏时弹出本对话框二选一（联机对战入口固定 melonDS，
 * 不经过本对话框）。
 *
 * DraStic 不可用时（x86 / x86_64 进程无 ARM 库，或设备 CPU 不受支持），
 * 对应选项显示禁用态与原因，避免用户选后黑屏。
 */
@Composable
fun NdsCorePickerDialog(
    gameTitle: String,
    onSelect: (coreId: String) -> Unit,
    onCancel: () -> Unit
) {
    val availability = remember {
        try {
            DraSticEngine.get().probeAvailability()
        } catch (_: Throwable) {
            DraSticEngine.Companion.Availability(false, "探测失败")
        }
    }

    AlertDialog(
        onDismissRequest = onCancel,
        title = {
            Column {
                Text("选择 NDS 模拟核心")
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
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    "本次游戏使用哪个核心运行？",
                    fontSize = 12.sp,
                    color = Color.Gray
                )

                CoreOptionCard(
                    title = "melonDS",
                    subtitle = "高精度开源核心 · 推荐",
                    description = "支持 DSi 模式、OpenGL 渲染器、放大滤镜、" +
                        "联机对战；BIOS 可选（缺省 FreeBIOS）。",
                    enabled = true,
                    onClick = { onSelect("melonds") }
                )

                CoreOptionCard(
                    title = "DraStic（激烈）",
                    subtitle = "高性能核心 · 高清渲染",
                    description = if (availability.available) {
                        "商用级性能与兼容性；支持 2x 高清渲染、GL 加速显示、" +
                            "快进与即时存档（DraStic 专属槽位）。"
                    } else {
                        (availability.reason ?: "当前进程不可用") +
                            "\n（选 melonDS 继续游戏）"
                    },
                    enabled = availability.available,
                    onClick = { if (availability.available) onSelect("drastic") }
                )

                if (!availability.available) {
                    Text(
                        text = "提示：DraStic 仅提供 ARM 库（含 32/64 位），" +
                            "x86 / x86_64 设备无法使用；ARM 设备上无需" +
                            "特殊构建参数，默认包即可运行。",
                        fontSize = 11.sp,
                        color = Color(0xFF9AA4B2),
                        lineHeight = 15.sp
                    )
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onCancel) { Text("取消") }
        }
    )
}

/** 单个核心选项卡片（玻璃拟态描边风格，与应用整体视觉语言一致）。 */
@Composable
private fun CoreOptionCard(
    title: String,
    subtitle: String,
    description: String,
    enabled: Boolean,
    onClick: () -> Unit
) {
    val cardBorder = if (enabled) Color(0x667FC3FF) else Color(0x33404A5A)
    val titleColor = if (enabled) Color(0xFFEAF4FF) else Color(0xFF6B7787)
    val subtitleColor = if (enabled) Color(0xFF7FC3FF) else Color(0xFF556070)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                Brush.verticalGradient(
                    listOf(Color(0x141E2E42), Color(0x0A0E1626))
                ),
                RoundedCornerShape(14.dp)
            )
            .border(0.8.dp, cardBorder, RoundedCornerShape(14.dp))
            .clickable(enabled = enabled) { onClick() }
            .padding(horizontal = 14.dp, vertical = 12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // 核心徽标点
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .background(subtitleColor, CircleShape)
            )
            Spacer(Modifier.width(8.dp))
            Text(title, color = titleColor, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.weight(1f))
            Text(subtitle, color = subtitleColor, fontSize = 11.sp, textAlign = TextAlign.End)
        }
        Spacer(Modifier.height(4.dp))
        Text(
            description,
            fontSize = 11.sp,
            color = if (enabled) Color(0xFFB9C4D3) else Color(0xFF59627A),
            lineHeight = 16.sp
        )
    }
}
