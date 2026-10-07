package com.nesstation.app.ui.online

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Snackbar
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nesstation.app.ui.components.AppBackgroundState
import com.nesstation.app.ui.fsd.FsdButtonHint
import com.nesstation.app.ui.fsd.FsdCoverFlow
import com.nesstation.app.ui.neon.SecBackdrop
import com.nesstation.app.ui.neon.SecIconBackdrop
import com.nesstation.app.ui.neon.SecBottomBar
import com.nesstation.app.ui.neon.SecBreadcrumb
import com.nesstation.app.ui.neon.SecButtonHints
import com.nesstation.app.ui.neon.SecCoverCard
import com.nesstation.app.ui.neon.SecCounter
import com.nesstation.app.ui.neon.SecTitleBanner
import com.nesstation.app.ui.neon.SecToolButton
import com.nesstation.app.ui.neon.SecTopBar
import kotlinx.coroutines.delay

// ---- 配色（对话框等浅色弹层与旧版一致） ----
private val PrimaryText = Color(0xFF1E2A3A)
private val SecondaryText = Color(0xFF4A5568)
private val SecondaryTextLight = Color(0xFF8899AA)
private val Accent = Color(0xFF8A7BFF)
private val DeleteColor = Color(0xFFE74C3C)

/** 封面卡片循环取用的强调色（与旧版色板一致，深蓝 FSD 底上很和谐）。 */
private val AccentPalette = listOf(
    Color(0xFF8A7BFF), // 紫
    Color(0xFFE74C3C), // 红
    Color(0xFF27AE60), // 绿
    Color(0xFF3498DB), // 蓝
    Color(0xFFE67E22), // 橙
    Color(0xFF1ABC9C), // 青绿
    Color(0xFF9B59B6), // 紫2
    Color(0xFFF1C40F), // 黄
    Color(0xFFE84393), // 粉
    Color(0xFF00CEC9), // 青
    Color(0xFF6C5CE7), // 靛
    Color(0xFFFDCB6E)  // 浅橙
)

/**
 * 在线网页游戏列表 — 与游戏库（LibraryScreen）同款 FSD 桌面效果。
 *
 * 关键改动（修复“在线游戏和 SWF 应该也要和游戏库一样的效果”）：
 * 1. 弃用旧的 PixelBackdrop 浅色像素风 + 平铺白色卡片网格，改为游戏库
 *    同款 FSD 深蓝桌面：FsdBackdrop 壁纸 + FsdTopBar/FsdBottomBar 状态条。
 * 2. 卡片改为 FsdCoverFlow 3D 封面流（居中放大、两侧缩放淡出、倒影）+
 *    FsdIconCoverCard 封面卡片（深蓝底 + 强调色渐变 + 徽标 + 底部标题条）。
 * 3. 补齐游戏库同款部件：FsdBreadcrumb 面包屑、FsdTitleBanner 标题横幅、
 *    FsdCounter 计数、FsdButtonHints 按键提示、FsdToolButton 工具按钮。
 * 4. 补齐 D-pad/手柄导航（TV 友好）：左右切卡、OK 启动、Y 长按选项、B 主页。
 * 5. 长按卡片弹出与游戏库一致的操作菜单（开始游戏 / 删除自定义游戏）。
 * 6. 保留原有功能：添加自定义游戏（UA 模式）、长按删除、Snackbar 提示。
 */
@Composable
fun OnlineGamesScreen(
    onBack: () -> Unit,
    onHome: () -> Unit = onBack,
    onOpenGame: (WebGameEntry) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var games by remember { mutableStateOf(WebGameStore.loadAll(context)) }
    var selIdx by remember { mutableStateOf(0) }
    var showAddDialog by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<WebGameEntry?>(null) }
    var menuGame by remember { mutableStateOf<WebGameEntry?>(null) }
    var pendingRenameGame by remember { mutableStateOf<WebGameEntry?>(null) }
    var pendingIconGame by remember { mutableStateOf<WebGameEntry?>(null) }
    var snackbarMsg by remember { mutableStateOf<String?>(null) }

    fun refresh() {
        games = WebGameStore.loadAll(context)
        if (selIdx > games.size - 1) selIdx = (games.size - 1).coerceAtLeast(0)
    }

    // 长按 → 自定义图标：选图后拷贝到 filesDir/icons 并记录路径
    val iconPickerLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        val game = pendingIconGame
        pendingIconGame = null
        val uri = uris.firstOrNull()
        if (uri == null || game == null) return@rememberLauncherForActivityResult
        try {
            try {
                context.contentResolver.takePersistableUriPermission(
                    uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (_: Exception) { }
            val iconsDir = java.io.File(context.filesDir, "icons").apply { mkdirs() }
            val dest = java.io.File(iconsDir,
                "web_${System.currentTimeMillis()}_${uri.lastPathSegment?.substringAfterLast('/') ?: "icon"}.png")
            context.contentResolver.openInputStream(uri)?.use { input ->
                dest.outputStream().use { output -> input.copyTo(output) }
            } ?: throw IllegalStateException("无法读取所选图片")
            WebGameStore.setCustomIcon(context, game.url, dest.absolutePath)
            refresh()
            snackbarMsg = "已设置自定义图标：${game.title}"
        } catch (_: Exception) {
            snackbarMsg = "图标设置失败"
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            // 手柄按键（TV / 蓝牙手柄）：B=返回主页（与游戏库一致）
            .onPreviewKeyEvent { e ->
                if (e.type != KeyEventType.KeyUp) return@onPreviewKeyEvent false
                when (e.key) {
                    Key.ButtonB, Key.Escape, Key.Back -> { onHome(); true }
                    else -> false
                }
            }
    ) {
        // ★ 主题适配（Sec* 组件）：Neon 时渲染 Neon 深空风，FSD 时原样
        //   （需求："整体ui设置为neon时在线游戏…没有正确设计为neon"）
        // ★★ 封面背景（本轮新增）：Neon 时把选中条目的自定义图标整幅
        //   铺满作背景（与游戏库 NeonCoverBackdrop 同款视觉，压暗层保证
        //   前景可读）；无图标/FSD 主题回退 SecBackdrop —— 补齐
        //   “在线游戏界面背景没有显示封面”。
        if (!AppBackgroundState.active)
            SecIconBackdrop(games.getOrNull(selIdx)?.iconPath)

        Column(modifier = Modifier.fillMaxSize()) {
            SecTopBar()

            // ===== 工具行：面包屑 + 操作按钮（与游戏库同款） =====
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(end = 24.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                SecBreadcrumb(
                    listOf("在线游戏", "${games.size} 个站点"),
                    modifier = Modifier.weight(1f)
                )
                SecToolButton(Icons.Rounded.Add, "添加") { showAddDialog = true }
                SecToolButton(Icons.Rounded.Refresh, "刷新") { refresh() }
                SecToolButton(Icons.Rounded.Home, "主页") { onHome() }
            }

            // ===== 封面流主体（与游戏库同款） =====
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .clipToBounds()
            ) {
                if (games.isEmpty()) {
                    // 空状态
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 34.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Text(
                            "还没有在线游戏站点",
                            color = Color(0xFFE8F1FF),
                            fontSize = 17.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(Modifier.size(8.dp))
                        Text(
                            "点击右上角「添加」收藏你喜欢的网页游戏网站",
                            color = Color(0xFF9FB6D4),
                            fontSize = 13.sp
                        )
                    }
                } else {
                    FsdCoverFlow(
                        count = games.size,
                        selectedIndex = selIdx,
                        onIndexChange = { selIdx = it },
                        onItemClick = { idx -> games.getOrNull(idx)?.let(onOpenGame) },
                        onItemLongClick = { idx ->
                            games.getOrNull(idx)?.let { menuGame = it }
                        },
                        grabFocusOnLaunch = true,
                        modifier = Modifier.fillMaxSize()
                    ) { i ->
                        val g = games[i]
                        val displayTitle = g.customTitle?.takeIf { it.isNotBlank() } ?: g.title
                        SecCoverCard(
                            title = displayTitle,
                            icon = Icons.Rounded.Public,
                            accent = AccentPalette[i % AccentPalette.size],
                            badge = if (g.uaMode == "mobile") "手机" else "PC",
                            subtitle = g.url,
                            iconPath = g.iconPath
                        )
                    }

                    // 底部左：按键提示
                    SecButtonHints(
                        hints = listOf(
                            FsdButtonHint("A", "启动", com.nesstation.app.ui.fsd.Fsd.BtnA),
                            FsdButtonHint("B", "主页", com.nesstation.app.ui.fsd.Fsd.BtnB),
                            FsdButtonHint("Y", "选项", com.nesstation.app.ui.fsd.Fsd.BtnY)
                        ),
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .padding(start = 34.dp, bottom = 8.dp)
                    )

                    // 底部中：标题横幅
                    games.getOrNull(selIdx)?.let { g ->
                        val displayTitle = g.customTitle?.takeIf { it.isNotBlank() } ?: g.title
                        SecTitleBanner(
                            text = "${if (g.uaMode == "mobile") "手机端" else "PC端"}  $displayTitle",
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .padding(bottom = 38.dp)
                        )
                    }

                    // 右侧：N of M 计数
                    SecCounter(
                        current = selIdx + 1,
                        total = games.size,
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(end = 34.dp, bottom = 38.dp)
                    )
                }
            }

            SecBottomBar(status = if (games.isEmpty()) "空" else "在线游戏")
        }

        // ---- Snackbar ----
        snackbarMsg?.let { msg ->
            Snackbar(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(bottom = 56.dp, start = 16.dp, end = 16.dp),
                containerColor = Color(0xCC061225),
                contentColor = Color.White
            ) {
                Text(msg)
            }
            LaunchedEffect(msg) {
                delay(2200)
                snackbarMsg = null
            }
        }
    }

    // ---- 长按操作菜单（与游戏库的长按菜单同款样式） ----
    menuGame?.let { game ->
        AlertDialog(
            onDismissRequest = { menuGame = null },
            title = { Text(game.title, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            text = {
                Column(
                    modifier = Modifier
                        .heightIn(max = 320.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    MenuOption("开始游戏") {
                        menuGame = null
                        onOpenGame(game)
                    }
                    MenuOption("自定义图标") {
                        menuGame = null
                        pendingIconGame = game
                        iconPickerLauncher.launch(arrayOf("image/*"))
                    }
                    if (!game.iconPath.isNullOrBlank()) {
                        MenuOption("恢复默认图标") {
                            menuGame = null
                            WebGameStore.setCustomIcon(context, game.url, null)
                            refresh()
                            snackbarMsg = "已恢复默认图标：${game.title}"
                        }
                    }
                    MenuOption("重命名") {
                        menuGame = null
                        pendingRenameGame = game
                    }
                    if (!game.isBuiltin) {
                        MenuOption("删除游戏", danger = true) {
                            menuGame = null
                            pendingDelete = game
                        }
                    } else {
                        Text(
                            "内置站点不可删除",
                            color = SecondaryText,
                            fontSize = 12.sp,
                            modifier = Modifier.padding(vertical = 8.dp)
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { menuGame = null }) { Text("关闭") }
            }
        )
    }

    // ---- Add dialog ----
    if (showAddDialog) {
        AddGameDialog(
            onDismiss = { showAddDialog = false },
            onConfirm = { entry ->
                WebGameStore.save(context, entry)
                showAddDialog = false
                refresh()
                snackbarMsg = "已添加：${entry.title}"
            }
        )
    }

    // ---- Delete confirm dialog ----
    pendingDelete?.let { target ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("删除游戏", color = PrimaryText) },
            text = {
                Text(
                    "确定要删除「${target.title}」吗？\n（仅从自定义列表移除，内置游戏不受影响）",
                    color = SecondaryText
                )
            },
            containerColor = Color.White,
            titleContentColor = PrimaryText,
            textContentColor = SecondaryText,
            confirmButton = {
                TextButton(onClick = {
                    WebGameStore.delete(context, target.url)
                    pendingDelete = null
                    refresh()
                    snackbarMsg = "已删除：${target.title}"
                }) { Text("删除", color = DeleteColor) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) {
                    Text("取消", color = SecondaryText)
                }
            }
        )
    }

    // ---- Rename dialog ----
    pendingRenameGame?.let { game ->
        val displayTitle = game.customTitle?.takeIf { it.isNotBlank() } ?: game.title
        var name by remember { mutableStateOf(displayTitle) }
        AlertDialog(
            onDismissRequest = { pendingRenameGame = null },
            title = { Text("重命名游戏", color = PrimaryText) },
            text = {
                Column {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text("自定义名称") },
                        singleLine = true,
                        colors = lightFieldColors(),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.size(8.dp))
                    Text(
                        "留空则恢复原始名称",
                        color = SecondaryTextLight,
                        fontSize = 11.sp
                    )
                }
            },
            containerColor = Color.White,
            titleContentColor = PrimaryText,
            textContentColor = PrimaryText,
            confirmButton = {
                TextButton(onClick = {
                    WebGameStore.setCustomTitle(
                        context, game.url,
                        name.trim().takeIf { it.isNotEmpty() }
                    )
                    pendingRenameGame = null
                    refresh()
                    snackbarMsg = "已重命名：${game.title}"
                }) { Text("保存", color = Accent) }
            },
            dismissButton = {
                TextButton(onClick = { pendingRenameGame = null }) {
                    Text("取消", color = SecondaryText)
                }
            }
        )
    }
}

/** 长按菜单中的单个可点击选项（与游戏库 MenuOption 同款）。 */
@Composable
private fun MenuOption(text: String, danger: Boolean = false, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp, horizontal = 4.dp)
    ) {
        Text(
            text = text,
            color = if (danger) Color(0xFFE74C3C) else Color(0xFF1E2A3A),
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium
        )
    }
}

@Composable
private fun AddGameDialog(
    onDismiss: () -> Unit,
    onConfirm: (WebGameEntry) -> Unit
) {
    var title by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    var isMobile by remember { mutableStateOf(false) }

    val canSubmit = title.isNotBlank() && url.isNotBlank()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("添加在线游戏", color = PrimaryText) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("游戏名称") },
                    singleLine = true,
                    isError = title.isBlank(),
                    colors = lightFieldColors(),
                    keyboardOptions = KeyboardOptions.Default,
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text("网址 URL") },
                    singleLine = true,
                    isError = url.isBlank(),
                    placeholder = {
                        Text(
                            "https://example.com",
                            color = SecondaryTextLight.copy(alpha = 0.6f)
                        )
                    },
                    colors = lightFieldColors(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier.fillMaxWidth()
                )

                Text("UA 模式", color = SecondaryText, fontSize = 12.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    UaToggleOption(
                        label = "PC（桌面端）",
                        selected = !isMobile,
                        accent = Accent,
                        onClick = { isMobile = false },
                        modifier = Modifier.weight(1f)
                    )
                    UaToggleOption(
                        label = "手机端",
                        selected = isMobile,
                        accent = Accent,
                        onClick = { isMobile = true },
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        },
        containerColor = Color.White,
        titleContentColor = PrimaryText,
        textContentColor = PrimaryText,
        confirmButton = {
            TextButton(
                enabled = canSubmit,
                onClick = {
                    val raw = url.trim()
                    val finalUrl = if (raw.startsWith("http://") || raw.startsWith("https://")) {
                        raw
                    } else {
                        "https://$raw"
                    }
                    onConfirm(
                        WebGameEntry(
                            title = title.trim(),
                            url = finalUrl,
                            isBuiltin = false,
                            uaMode = if (isMobile) "mobile" else "desktop"
                        )
                    )
                }
            ) { Text("添加", color = if (canSubmit) Accent else SecondaryTextLight) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消", color = SecondaryText) }
        }
    )
}

@Composable
private fun UaToggleOption(
    label: String,
    selected: Boolean,
    accent: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(if (selected) accent.copy(alpha = 0.2f) else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            color = if (selected) accent else SecondaryText,
            fontSize = 12.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal
        )
    }
}

@Composable
private fun lightFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedTextColor = PrimaryText,
    unfocusedTextColor = PrimaryText,
    cursorColor = Accent,
    focusedBorderColor = Accent,
    unfocusedBorderColor = SecondaryTextLight.copy(alpha = 0.5f),
    focusedLabelColor = Accent,
    unfocusedLabelColor = SecondaryText,
    errorCursorColor = DeleteColor,
    errorBorderColor = DeleteColor,
    errorLabelColor = DeleteColor
)
