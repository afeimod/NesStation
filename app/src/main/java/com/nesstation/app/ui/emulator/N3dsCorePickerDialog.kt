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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nesstation.app.core.engine.CitraMmjEngine

/**
 * ★★ 3DS 游戏启动时的**双核心选择对话框**（本轮新增，仿 NDS 选择器）★★
 *
 * NesStation 的 3DS 平台现有两个可选拟核心：
 *  - **Azahar**（默认）：现代开源核心（AzaharPlus 2125.x），全设置支持、
 *    即时存档、自定义驱动；
 *  - **Citra MMJ**（weihuoya 20250220）：高性能老牌核心，对老设备
 *    兼容性好，全部 MMJ 设置已接入设置面板。
 *
 * 每次启动 3DS 游戏时弹出本对话框二选一（联机对战固定 Azahar，不经过
 * 本对话框）。
 *
 * Citra MMJ 不可用时（x86 进程无 ARM64 库等），对应选项显示禁用态与
 * 原因，避免用户选后黑屏。
 */
@Composable
fun N3dsCorePickerDialog(
    gameTitle: String,
    onSelect: (coreId: String) -> Unit,
    onCancel: () -> Unit
) {
    val mmjAvailable = remember {
        try {
            CitraMmjEngine.get().probeAvailability().first
        } catch (_: Throwable) {
            false
        }
    }

    AlertDialog(
        onDismissRequest = onCancel,
        title = {
            Column {
                Text("选择 3DS 模拟核心")
                Text(
                    text = gameTitle,
                    fontSize = 12.sp,
                    color = Color.Gray,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
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

                N3dsCoreOptionCard(
                    title = "Azahar",
                    subtitle = "现代核心 · 推荐",
                    description = "全功能：即时存档、GPU 驱动管理、自定义布局、" +
                        "联机对战；设置面板支持全部选项。",
                    enabled = true,
                    onClick = { onSelect("azahar") }
                )

                N3dsCoreOptionCard(
                    title = "Citra MMJ",
                    subtitle = "高性能老核心",
                    description = if (mmjAvailable) {
                        "老设备性能与兼容性表现出色；MMJ 专属设置" +
                            "（分辨率/帧率/着色器/Hack 等）已全部接入；" +
                            "不支持即时存档（使用游戏内存储）与联机。"
                    } else {
                        "Citra MMJ 核心库不可用（仅提供 arm64 库；" +
                            "x86 / 32 位设备无法使用）。\n（选 Azahar 继续游戏）"
                    },
                    enabled = mmjAvailable,
                    onClick = { if (mmjAvailable) onSelect("citra_mmj") }
                )
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onCancel) { Text("取消") }
        }
    )
}

/** 单个核心选项卡片（与 NDS 选择器同款玻璃拟态风格）。 */
@Composable
private fun N3dsCoreOptionCard(
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
