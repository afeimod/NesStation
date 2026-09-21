package com.nesstation.app.ui.settings

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nesstation.app.core.model.GamePlatform
import com.nesstation.app.core.storage.ButtonTheme
import com.nesstation.app.core.storage.OverlayTheme
import com.nesstation.app.core.storage.PadLayout
import com.nesstation.app.core.storage.overlayThemeGet
import com.nesstation.app.core.storage.overlayThemeSet
import com.nesstation.app.core.storage.themeButtonsFor

/**
 * 预设色板（所有遮罩/按钮主题共用的一套颜色）。
 * 末尾的 null 表示「默认」，恢复核心自带的配色。
 */
internal val THEME_COLOR_PALETTE: List<Pair<String, Long?>> = listOf(
    "纯黑" to 0xFF000000,
    "炭黑" to 0xFF1A1A22,
    "铁灰" to 0xFF2C2C38,
    "蓝灰" to 0xFF39445A,
    "白" to 0xFFFFFFFF,
    "银灰" to 0xFF95A5A6,
    "红" to 0xFFE74C3C,
    "橙" to 0xFFE67E22,
    "黄" to 0xFFFFD66B,
    "亮绿" to 0xFF2ECC71,
    "青" to 0xFF1ABC9C,
    "蓝" to 0xFF3498DB,
    "深蓝" to 0xFF2980B9,
    "紫" to 0xFF9B59B6,
    "粉" to 0xFFE91E9B,
    "棕" to 0xFF8B5E3C,
    "默认" to null,
)

@Composable
private fun ColorSwatch(color: Long?, selected: Boolean, onClick: () -> Unit) {
    val base = if (color != null) Color(color) else Color(0xFF666666)
    Box(
        modifier = Modifier
            .size(30.dp)
            .background(base, CircleShape)
            .border(2.dp, if (selected) Color(0xFFE74C3C) else Color(0x22000000), CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        if (color == null) {
            Text("✕", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        }
    }
}

/** 一行色板选择器。 */
@Composable
private fun ColorPaletteRow(current: Long?, onSelect: (Long?) -> Unit) {
    LazyRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(THEME_COLOR_PALETTE) { (_, color) ->
            ColorSwatch(color = color, selected = current == color) { onSelect(color) }
        }
    }
}

/** 按键图片槽位：点击选择图片；已设置时可单独清除。 */
@Composable
private fun ImageSlotChip(
    label: String,
    active: Boolean,
    onClick: () -> Unit,
    onClear: (() -> Unit)?
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier
            .background(if (active) Color(0x332ECC71) else Color(0x0A000000), RoundedCornerShape(8.dp))
            .border(1.dp, if (active) Color(0x662ECC71) else Color(0x22000000), RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 4.dp)
    ) {
        Text(label, fontSize = 11.sp, color = if (active) Color(0xFF1E7D4B) else Color(0x88000000))
        if (onClear != null) {
            Text(
                "✕",
                fontSize = 11.sp,
                color = Color(0xFFFF6B6B),
                modifier = Modifier.clickable { onClear() }
            )
        }
    }
}

/**
 * 每个核心设置页里的「遮罩 / 按钮主题」入口区块。
 * 点击后打开编辑对话框；改动通过 updateLayout 写回
 * [PadLayoutStore.overlayThemeJson]，每核心独立存储。
 */
@Composable
fun OverlayThemeSection(
    platform: GamePlatform,
    padLayout: PadLayout,
    updateLayout: (PadLayout) -> Unit
) {
    var open by remember { mutableStateOf(false) }
    val theme = overlayThemeGet(padLayout.overlayThemeJson, platform)

    SettingsSection("遮罩 / 按钮主题") {
        SettingsRow(
            title = "画面遮罩 / 按键配色",
            subtitle = if (theme.maskEnabled || theme.bgColor != null || theme.hasButtonCustomizations) {
                "已自定义 · 每按键单独配色（含按压效果）"
            } else {
                "背景遮罩 + 按键配色（每核心独立）"
            },
            trailing = { Arrow() },
            onClick = { open = true }
        )
    }

    if (open) {
        OverlayThemeDialog(
            platform = platform,
            theme = theme,
            onApply = { newTheme ->
                updateLayout(
                    padLayout.copy {
                        overlayThemeJson = overlayThemeSet(overlayThemeJson, platform, newTheme)
                    }
                )
            },
            onDismiss = { open = false }
        )
    }
}

@Composable
private fun OverlayThemeDialog(
    platform: GamePlatform,
    theme: OverlayTheme,
    onApply: (OverlayTheme) -> Unit,
    onDismiss: () -> Unit
) {
    var edit by remember { mutableStateOf(theme) }
    // 当前正在编辑的按键（null = 未打开按键子选择器）
    var editingButton by remember { mutableStateOf<String?>(null) }
    var editingSlot by remember { mutableStateOf("color") }

    val context = LocalContext.current
    // 图片选择目标：bg / mask / btn:<id>:normal / btn:<id>:pressed
    var pendingImageTarget by remember { mutableStateOf<String?>(null) }
    val imagePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        val target = pendingImageTarget ?: return@rememberLauncherForActivityResult
        pendingImageTarget = null
        if (uri == null) return@rememberLauncherForActivityResult
        try {
            context.contentResolver.takePersistableUriPermission(
                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        } catch (_: Exception) { }
        val u = uri.toString()
        edit = when {
            target == "bg" -> edit.copy(bgImageUri = u)
            target == "mask" -> edit.copy(maskImageUri = u)
            target.startsWith("btn:") -> {
                val parts = target.removePrefix("btn:").split(":")
                val id = parts[0]
                val slot = parts.getOrElse(1) { "normal" }
                val old = edit.button(id) ?: ButtonTheme()
                val newTheme = if (slot == "pressed") old.copy(pressedImageUri = u)
                               else old.copy(imageUri = u)
                edit.copy().also { it.setButton(id, newTheme) }
            }
            else -> edit
        }
    }
    fun pickImage(target: String) {
        pendingImageTarget = target
        imagePicker.launch(arrayOf("image/png", "image/jpeg"))
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("遮罩 / 按钮主题", fontSize = 16.sp, fontWeight = FontWeight.Bold) },
        text = {
            // 横屏可用高度远小于竖屏，整块内容改为可滚动，避免下半部分
            // （按键主题列表 / 恢复默认按钮）被截断到屏幕外无法操作。
            val configuration = LocalConfiguration.current
            val contentMaxHeight = if (configuration.screenHeightDp > configuration.screenWidthDp) 520.dp else 240.dp
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = contentMaxHeight)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // === 背景颜色（游戏画面周边） ===
                Text("背景颜色（游戏画面周边）", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                ColorPaletteRow(current = edit.bgColor) { c ->
                    edit = edit.copy(bgColor = c)
                }
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Text(
                        if (edit.bgImageUri != null) "背景图片：已设置" else "背景图片（png/jpg）",
                        fontSize = 12.sp,
                        modifier = Modifier.weight(1f)
                    )
                    if (edit.bgImageUri != null) {
                        TextButton(onClick = { edit = edit.copy(bgImageUri = null) }) {
                            Text("清除", fontSize = 12.sp)
                        }
                    }
                    TextButton(onClick = { pickImage("bg") }) { Text("选择图片", fontSize = 12.sp) }
                }

                // === 画面遮罩 ===
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Text(
                        "画面遮罩（叠加在游戏画面上）",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f)
                    )
                    Switch(checked = edit.maskEnabled, onCheckedChange = { edit = edit.copy(maskEnabled = it) })
                }
                if (edit.maskEnabled) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("强度", fontSize = 12.sp)
                        Slider(
                            value = edit.maskAlpha.toFloat(),
                            onValueChange = { edit = edit.copy(maskAlpha = it.toInt().coerceIn(0, 255)) },
                            valueRange = 0f..255f,
                            modifier = Modifier.weight(1f).padding(horizontal = 8.dp)
                        )
                        Text("${edit.maskAlpha}", fontSize = 12.sp)
                    }
                    // maskColor 可空：末尾 ✕（默认）= 不叠加颜色层。
                    // 已设置遮罩图片时图片优先，颜色层不再参与显示。
                    ColorPaletteRow(current = edit.maskColor) { c ->
                        edit = edit.copy(maskColor = c)
                    }
                    if (edit.maskImageUri != null) {
                        Text(
                            "已设置遮罩图片：图片优先显示，颜色层不再参与",
                            fontSize = 11.sp,
                            color = Color(0xFF1E7D4B)
                        )
                    } else if (edit.maskColor == null) {
                        Text(
                            "当前未选择颜色：遮罩不会叠加任何颜色（可选图片或点 ✕ 以外的色块）",
                            fontSize = 11.sp,
                            color = Color(0x88000000)
                        )
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        Text(
                            if (edit.maskImageUri != null) "遮罩图片：已设置" else "遮罩图片（png/jpg）",
                            fontSize = 12.sp,
                            modifier = Modifier.weight(1f)
                        )
                        if (edit.maskImageUri != null) {
                            TextButton(onClick = { edit = edit.copy(maskImageUri = null) }) {
                                Text("清除", fontSize = 12.sp)
                            }
                        }
                        TextButton(onClick = { pickImage("mask") }) { Text("选择图片", fontSize = 12.sp) }
                    }
                }

                // === 按键主题 ===
                Text("每个按键单独配色（含按压效果）",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold)
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    themeButtonsFor(platform).forEach { (id, label) ->
                        val btnTheme = edit.button(id)
                        val normal = btnTheme?.color
                        val pressed = btnTheme?.pressedColor
                        val hasNormalImg = btnTheme?.imageUri != null
                        val hasPressedImg = btnTheme?.pressedImageUri != null
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(Color(0x14000000), RoundedCornerShape(10.dp))
                                .padding(horizontal = 10.dp, vertical = 6.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(label, fontSize = 13.sp, modifier = Modifier.weight(1f))
                                // 常规色
                                Box(
                                    modifier = Modifier
                                        .size(28.dp)
                                        .background(if (normal != null) Color(normal) else Color(0xFF666666), CircleShape)
                                        .border(1.dp, Color(0x33000000), CircleShape)
                                        .clickable { editingButton = id; editingSlot = "color" },
                                    contentAlignment = Alignment.Center
                                ) {
                                    if (normal == null) Text("✕", color = Color.White, fontSize = 10.sp)
                                }
                                Box(Modifier.size(6.dp))
                                // 按压色
                                Box(
                                    modifier = Modifier
                                        .size(28.dp)
                                        .background(if (pressed != null) Color(pressed) else Color(0xFF888888), CircleShape)
                                        .border(1.dp, Color(0x33000000), CircleShape)
                                        .clickable { editingButton = id; editingSlot = "pressed" },
                                    contentAlignment = Alignment.Center
                                ) {
                                    if (pressed == null) Text("✕", color = Color.White, fontSize = 10.sp)
                                }
                                Box(Modifier.size(8.dp))
                                // 恢复默认
                                Text("重置", fontSize = 11.sp, color = Color(0x88000000))
                                Box(
                                    modifier = Modifier
                                        .size(22.dp)
                                        .background(Color.Transparent, CircleShape)
                                        .border(1.dp, Color(0x33000000), CircleShape)
                                        .clickable {
                                            edit = edit.copy().also { it.setButton(id, ButtonTheme(null, null)) }
                                        }
                                )
                            }
                            // 图片（常规 / 按压）选择与清除
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                ImageSlotChip(
                                    label = "常规图",
                                    active = hasNormalImg,
                                    onClick = { pickImage("btn:$id:normal") },
                                    onClear = if (hasNormalImg) {
                                        {
                                            edit = edit.copy().also {
                                                it.setButton(id, (it.button(id) ?: ButtonTheme()).copy(imageUri = null))
                                            }
                                        }
                                    } else null
                                )
                                ImageSlotChip(
                                    label = "按压图",
                                    active = hasPressedImg,
                                    onClick = { pickImage("btn:$id:pressed") },
                                    onClear = if (hasPressedImg) {
                                        {
                                            edit = edit.copy().also {
                                                it.setButton(id, (it.button(id) ?: ButtonTheme()).copy(pressedImageUri = null))
                                            }
                                        }
                                    } else null
                                )
                            }
                        }
                    }
                }
                if (edit.hasButtonCustomizations) {
                    TextButton(onClick = { edit = edit.copy(buttons = mutableMapOf()) }) {
                        Text("恢复全部按键默认配色", fontSize = 12.sp)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onApply(edit) }) { Text("确定") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )

    // 按键配色子选择器
    if (editingButton != null) {
        val id = editingButton!!
        val current = if (editingSlot == "color") edit.button(id)?.color else edit.button(id)?.pressedColor
        AlertDialog(
            onDismissRequest = { editingButton = null },
            title = {
                val label = themeButtonsFor(platform).firstOrNull { it.first == id }?.second ?: id
                Text(
                    "$label · ${if (editingSlot == "color") "常规色" else "按压色"}",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("选择后立即应用到该按键（末尾 ✕ = 恢复默认）", fontSize = 12.sp)
                    ColorPaletteRow(current = current) { c ->
                        val old = edit.button(id) ?: ButtonTheme()
                        val newTheme = if (editingSlot == "color") {
                            ButtonTheme(color = c, pressedColor = old.pressedColor)
                        } else {
                            ButtonTheme(color = old.color, pressedColor = c)
                        }
                        edit = edit.copy().also { it.setButton(id, newTheme) }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { editingButton = null }) { Text("完成") }
            }
        )
    }
}