package com.nesstation.app.ui.swf

import android.os.Environment
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nesstation.app.ui.components.AppBackgroundState
import com.nesstation.app.ui.fsd.Fsd
import com.nesstation.app.ui.fsd.FsdButtonHint
import com.nesstation.app.ui.fsd.FsdCoverFlow
import com.nesstation.app.ui.neon.Neon
import com.nesstation.app.ui.neon.SecBackdrop
import com.nesstation.app.ui.neon.SecBottomBar
import com.nesstation.app.ui.neon.SecBreadcrumb
import com.nesstation.app.ui.neon.SecButtonHints
import com.nesstation.app.ui.neon.SecCoverCard
import com.nesstation.app.ui.neon.SecCounter
import com.nesstation.app.ui.neon.SecTitleBanner
import com.nesstation.app.ui.neon.SecToolButton
import com.nesstation.app.ui.neon.SecTopBar
import kotlinx.coroutines.delay
import java.io.File

// ---- 配色（删除确认等弹层沿用浅色，与游戏库弹层一致） ----
private val DeleteColor = Color(0xFFE74C3C)

/** 封面卡片循环取用的强调色。 */
private val AccentPalette = listOf(
    Color(0xFF8A7BFF), Color(0xFFE74C3C), Color(0xFF27AE60), Color(0xFF3498DB),
    Color(0xFFE67E22), Color(0xFF1ABC9C), Color(0xFF9B59B6), Color(0xFFF1C40F)
)

/**
 * SWF 游戏库界面 — 与游戏库（LibraryScreen）同款 FSD 桌面效果。
 *
 * 关键改动（修复“在线游戏和 SWF 应该也要和游戏库一样的效果”）：
 * 1. 弃用旧的 PixelBackdrop 浅色像素风 + 平铺白色卡片网格，改为游戏库
 *    同款 FSD 深蓝桌面：FsdBackdrop 壁纸 + FsdTopBar/FsdBottomBar 状态条。
 * 2. 「我的游戏」改为 FsdCoverFlow 3D 封面流 + FsdIconCoverCard 封面卡片，
 *    配 FsdTitleBanner 标题横幅 / FsdCounter 计数 / FsdButtonHints 按键提示。
 * 3. 「浏览文件」改为 FSD 深色列表行（半透明玻璃底 + 白字），扫描按钮
 *    换成 FsdToolButton。
 * 4. 补齐 D-pad/手柄导航：B 返回/上级（与游戏库一致）。
 * 5. 保留全部原有功能：双 Tab、文件夹浏览、扫描添加、点击即玩、
 *    长按移除（确认弹窗）、Snackbar 提示。
 *
 * @param onBack  返回上一级（保留旧入口）
 * @param onHome  返回主页
 * @param onOpenSwf 打开指定 SWF 文件
 */
@Composable
fun SwfListScreen(
    onBack: () -> Unit,
    onHome: () -> Unit = onBack,
    onOpenSwf: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var currentTab by remember { mutableStateOf(0) } // 0=我的游戏, 1=浏览文件
    var swfList by remember { mutableStateOf(SwfStore.list(context).distinctBy { it.path }) }
    var selIdx by remember { mutableStateOf(0) }
    var snackbarMsg by remember { mutableStateOf<String?>(null) }
    var pendingRemove by remember { mutableStateOf<SwfStore.Entry?>(null) }
    var menuEntry by remember { mutableStateOf<SwfStore.Entry?>(null) }
    var pendingRenameEntry by remember { mutableStateOf<SwfStore.Entry?>(null) }
    var pendingIconEntry by remember { mutableStateOf<SwfStore.Entry?>(null) }

    fun refreshList() {
        // 关键修复：去重逻辑
        // 之前 swfList 直接赋值为 SwfStore.list(context)，如果 SwfStore.add 因为某种
        // 边界情况没去重（比如在并发调用下），这里再加一道 distinctBy 兜底。
        swfList = SwfStore.list(context).distinctBy { it.path }
        if (selIdx > swfList.size - 1) selIdx = (swfList.size - 1).coerceAtLeast(0)
    }

    // 长按 → 自定义图标：把图片拷贝到 filesDir/icons/swf_.. 并记录路径
    val iconPickerLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        val entry = pendingIconEntry
        pendingIconEntry = null
        val uri = uris.firstOrNull()
        if (uri == null || entry == null) return@rememberLauncherForActivityResult
        try {
            try {
                context.contentResolver.takePersistableUriPermission(
                    uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (_: Exception) { }
            val iconsDir = File(context.filesDir, "icons").apply { mkdirs() }
            val dest = File(iconsDir,
                "swf_${System.currentTimeMillis()}_${uri.lastPathSegment?.substringAfterLast('/') ?: "icon"}.png")
            context.contentResolver.openInputStream(uri)?.use { input ->
                dest.outputStream().use { output -> input.copyTo(output) }
            } ?: throw IllegalStateException("无法读取所选图片")
            SwfStore.setCustomIcon(context, entry.path, dest.absolutePath)
            refreshList()
            snackbarMsg = "已设置自定义图标：${entry.title}"
        } catch (_: Exception) {
            snackbarMsg = "图标设置失败"
        }
    }

    // 文件浏览状态
    val startDir = remember { Environment.getExternalStorageDirectory() ?: File("/") }
    var currentDir by remember { mutableStateOf(startDir) }

    val swfFiles = remember(currentDir) {
        try {
            currentDir.listFiles()
                ?.filter { it.isFile && it.extension.equals("swf", ignoreCase = true) }
                ?.sortedBy { it.name.lowercase() }
                ?: emptyList()
        } catch (_: Exception) { emptyList() }
    }

    val subDirs = remember(currentDir) {
        try {
            currentDir.listFiles()
                ?.filter { it.isDirectory && !it.name.startsWith(".") }
                ?.sortedBy { it.name.lowercase() }
                ?: emptyList()
        } catch (_: Exception) { emptyList() }
    }

    // B 键 / 系统返回：文件浏览模式下先回上级目录，否则返回上一页
    fun goBack() {
        if (currentTab == 1 && currentDir != startDir && currentDir.parentFile != null) {
            currentDir = currentDir.parentFile!!
        } else {
            onBack()
        }
    }

    BackHandler { goBack() }

    Box(
        modifier = modifier
            .fillMaxSize()
            // 手柄按键（TV / 蓝牙手柄）：B=返回/上级（与游戏库一致）
            .onPreviewKeyEvent { e ->
                if (e.type != KeyEventType.KeyUp) return@onPreviewKeyEvent false
                when (e.key) {
                    Key.ButtonB, Key.Escape, Key.Back -> { goBack(); true }
                    else -> false
                }
            }
    ) {
        // FSD 深蓝壁纸（与游戏库一致）；全局背景激活时由根布局统一渲染
        if (!AppBackgroundState.active) SecBackdrop()

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
                    listOf(
                        "SWF 游戏",
                        if (currentTab == 1) "浏览文件" else "我的游戏 ${swfList.size}"
                    ),
                    modifier = Modifier.weight(1f)
                )
                if (currentTab == 1) {
                    SecToolButton(Icons.Rounded.Search, "扫描此文件夹") {
                        val count = SwfStore.scanFolder(context, currentDir.absolutePath)
                        if (count > 0) {
                            refreshList()
                            snackbarMsg = "已扫描添加 $count 个 SWF 文件"
                        } else {
                            snackbarMsg = "此文件夹没有 SWF 文件"
                        }
                    }
                }
                SecToolButton(Icons.Rounded.Home, "主页") { onHome() }
            }

            // ===== Tab 切换行（FSD 风格胶囊，与游戏库平台标签一致） =====
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 34.dp, vertical = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FsdTabChip("我的游戏", currentTab == 0) { currentTab = 0 }
                FsdTabChip("浏览文件", currentTab == 1) { currentTab = 1 }
            }

            // ===== 内容区 =====
            if (currentTab == 0) {
                // 我的游戏：FSD 封面流（与游戏库同款效果）
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .clipToBounds()
                ) {
                    if (swfList.isEmpty()) {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(horizontal = 34.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            Icon(
                                Icons.Rounded.Movie,
                                contentDescription = null,
                                tint = Fsd.BarTextDim,
                                modifier = Modifier.size(56.dp)
                            )
                            Spacer(Modifier.size(16.dp))
                            Text(
                                "还没有添加 SWF 游戏",
                                color = Fsd.BarText,
                                fontSize = 17.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                            Spacer(Modifier.size(6.dp))
                            Text(
                                "切换到「浏览文件」添加本地 SWF 文件",
                                color = Fsd.BarTextDim,
                                fontSize = 13.sp
                            )
                        }
                    } else {
                        FsdCoverFlow(
                            count = swfList.size,
                            selectedIndex = selIdx,
                            onIndexChange = { selIdx = it },
                            onItemClick = { idx -> swfList.getOrNull(idx)?.let { onOpenSwf(it.path) } },
                            onItemLongClick = { idx ->
                                swfList.getOrNull(idx)?.let { entry ->
                                    menuEntry = entry
                                }
                            },
                            grabFocusOnLaunch = true,
                            modifier = Modifier.fillMaxSize()
                        ) { i ->
                            val entry = swfList[i]
                            SecCoverCard(
                                title = SwfStore.displayTitle(entry),
                                icon = Icons.Rounded.Movie,
                                accent = AccentPalette[i % AccentPalette.size],
                                badge = "SWF",
                                subtitle = formatSize(entry.size),
                                iconPath = entry.iconPath
                            )
                        }

                        // 底部左：按键提示
                        SecButtonHints(
                            hints = listOf(
                                FsdButtonHint("A", "启动", Fsd.BtnA),
                                FsdButtonHint("B", "返回", Fsd.BtnB),
                                FsdButtonHint("Y", "选项", Fsd.BtnY)
                            ),
                            modifier = Modifier
                                .align(Alignment.BottomStart)
                                .padding(start = 34.dp, bottom = 8.dp)
                        )

                        // 底部中：标题横幅
                        swfList.getOrNull(selIdx)?.let { g ->
                            SecTitleBanner(
                                text = SwfStore.displayTitle(g),
                                modifier = Modifier
                                    .align(Alignment.BottomCenter)
                                    .padding(bottom = 38.dp)
                            )
                        }

                        // 右侧：N of M 计数
                        SecCounter(
                            current = selIdx + 1,
                            total = swfList.size,
                            modifier = Modifier
                                .align(Alignment.BottomEnd)
                                .padding(end = 34.dp, bottom = 38.dp)
                        )
                    }
                }
            } else {
                // 浏览文件：FSD 深色列表
                LazyColumn(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentPadding = PaddingValues(horizontal = 34.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    // 当前目录信息
                    item {
                        Text(
                            "${subDirs.size} 文件夹 · ${swfFiles.size} SWF · ${currentDir.absolutePath}",
                            color = Fsd.BarTextDim,
                            fontSize = 11.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    // Up
                    if (currentDir.parentFile != null) {
                        item {
                            FsdFileRow(
                                icon = Icons.Rounded.Folder,
                                iconTint = Fsd.BarTextDim,
                                title = "..",
                                subtitle = null
                            ) { currentDir = currentDir.parentFile!! }
                        }
                    }
                    // 子目录
                    items(subDirs) { dir ->
                        FsdFileRow(
                            icon = Icons.Rounded.Folder,
                            iconTint = Fsd.TileYellowTop,
                            title = dir.name,
                            subtitle = null
                        ) { currentDir = dir }
                    }
                    // SWF 文件
                    items(swfFiles) { file ->
                        FsdFileRow(
                            icon = Icons.Rounded.Movie,
                            iconTint = Fsd.TileBlueTop,
                            title = file.name,
                            subtitle = formatSize(file.length()),
                            trailing = {
                                Icon(
                                    Icons.Rounded.PlayArrow,
                                    contentDescription = "播放",
                                    tint = Fsd.BarText,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        ) {
                            SwfStore.add(context, file.absolutePath, file.nameWithoutExtension)
                            refreshList()
                            onOpenSwf(file.absolutePath)
                        }
                    }
                    if (subDirs.isEmpty() && swfFiles.isEmpty()) {
                        item {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(40.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    "此目录没有 SWF 文件",
                                    color = Fsd.BarTextDim,
                                    fontSize = 13.sp
                                )
                            }
                        }
                    }
                }
            }

            SecBottomBar(status = "SWF 游戏")
        }

        // Snackbar
        snackbarMsg?.let { msg ->
            Snackbar(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 56.dp, start = 16.dp, end = 16.dp),
                containerColor = Color(0xCC061225),
                contentColor = Color.White,
                action = {
                    TextButton(onClick = { snackbarMsg = null }) {
                        Text("确定", color = Color(0xFFF7B500))
                    }
                }
            ) {
                Text(msg)
            }
            LaunchedEffect(msg) {
                delay(2200)
                snackbarMsg = null
            }
        }
    }
    // ---- 移除确认弹窗（与旧版一致，防止误触） ----
    pendingRemove?.let { entry ->
        AlertDialog(
            onDismissRequest = { pendingRemove = null },
            title = { Text("移除游戏") },
            text = { Text("确定要从列表中移除「${entry.title}」吗？\n（不会删除原文件）") },
            confirmButton = {
                TextButton(onClick = {
                    SwfStore.remove(context, entry.path)
                    pendingRemove = null
                    refreshList()
                    snackbarMsg = "已移除: ${entry.title}"
                }) { Text("移除", color = DeleteColor) }
            },
            dismissButton = {
                TextButton(onClick = { pendingRemove = null }) { Text("取消") }
            }
        )
    }

    // ---- 长按操作菜单（与游戏库同款） ----
    menuEntry?.let { entry ->
        AlertDialog(
            onDismissRequest = { menuEntry = null },
            title = { Text(SwfStore.displayTitle(entry), fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            text = {
                Column {
                    MenuRow("开始游戏") {
                        menuEntry = null
                        onOpenSwf(entry.path)
                    }
                    MenuRow("自定义图标") {
                        menuEntry = null
                        pendingIconEntry = entry
                        iconPickerLauncher.launch(arrayOf("image/*"))
                    }
                    if (!entry.iconPath.isNullOrBlank()) {
                        MenuRow("恢复默认图标") {
                            menuEntry = null
                            SwfStore.setCustomIcon(context, entry.path, null)
                            refreshList()
                            snackbarMsg = "已恢复默认图标：${entry.title}"
                        }
                    }
                    MenuRow("重命名") {
                        menuEntry = null
                        pendingRenameEntry = entry
                    }
                    MenuRow("移除", danger = true) {
                        menuEntry = null
                        pendingRemove = entry
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { menuEntry = null }) { Text("关闭") }
            }
        )
    }

    // ---- 重命名弹窗 ----
    pendingRenameEntry?.let { entry ->
        var name by remember(entry.path) { mutableStateOf(SwfStore.displayTitle(entry)) }
        AlertDialog(
            onDismissRequest = { pendingRenameEntry = null },
            title = { Text("重命名游戏", color = Color(0xFF1E2A3A)) },
            text = {
                Column {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text("自定义名称") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.size(8.dp))
                    Text(
                        "留空则恢复原始名称",
                        color = Color.Gray,
                        fontSize = 11.sp
                    )
                }
            },
            containerColor = Color.White,
            titleContentColor = Color(0xFF1E2A3A),
            confirmButton = {
                TextButton(onClick = {
                    SwfStore.setCustomTitle(
                        context, entry.path,
                        name.trim().takeIf { it.isNotEmpty() }
                    )
                    pendingRenameEntry = null
                    refreshList()
                    snackbarMsg = "已重命名：${entry.title}"
                }) { Text("保存", color = Color(0xFF1976D2)) }
            },
            dismissButton = {
                TextButton(onClick = { pendingRenameEntry = null }) { Text("取消") }
            }
        )
    }
}

/** 长按菜单中的单行选项（深色背景列表内的白字菜单，SVF 复用） */
@Composable
private fun MenuRow(text: String, danger: Boolean = false, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp, horizontal = 4.dp)
    ) {
        Text(
            text = text,
            color = if (danger) DeleteColor else Color(0xFF1E2A3A),
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium
        )
    }
}

/** FSD 风格 Tab 胶囊（与游戏库的平台 FilterChip 同款视觉）。
 *  ★ Neon 主题时自动换装：切角 + 电光青（深空底上更协调）。 */
@Composable
private fun FsdTabChip(
    text: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    val neon = com.nesstation.app.ui.neon.NeonUi.isNeon
    val shape = if (neon) com.nesstation.app.ui.neon.neonChamfer(0.4f)
        else RoundedCornerShape(16.dp)
    Box(
        modifier = Modifier
            .clip(shape)
            .background(
                if (neon) {
                    SolidColor(
                        if (selected) Neon.Accent.copy(alpha = 0.30f)
                        else Neon.BgPanel.copy(alpha = 0.75f)
                    )
                } else if (selected) Brush.verticalGradient(
                    listOf(Fsd.TileBlueTop, Fsd.TileBlueBottom)
                ) else SolidColor(Color.White.copy(alpha = 0.12f))
            )
            .border(
                width = 1.dp,
                color = if (neon) {
                    if (selected) Neon.Accent else Neon.Line
                } else if (selected) Color.White.copy(alpha = 0.65f)
                        else Color.White.copy(alpha = 0.22f),
                shape = shape
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 6.dp)
    ) {
        Text(
            text,
            color = if (neon) {
                if (selected) Neon.TextHi else Neon.TextDim
            } else if (selected) Color.White else Fsd.BarTextDim,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold
        )
    }
}

/** FSD 深色文件/文件夹行（浏览文件模式）。 */
@Composable
private fun FsdFileRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    iconTint: Color,
    title: String,
    subtitle: String?,
    trailing: (@Composable () -> Unit)? = null,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Color.White.copy(alpha = 0.08f))
            .border(
                width = 1.dp,
                color = Color.White.copy(alpha = 0.14f),
                shape = RoundedCornerShape(12.dp)
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = iconTint,
            modifier = Modifier.size(22.dp)
        )
        Spacer(Modifier.size(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                title,
                color = Fsd.BarText,
                fontSize = 14.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                fontWeight = FontWeight.Medium
            )
            if (subtitle != null) {
                Text(
                    subtitle,
                    color = Fsd.BarTextDim,
                    fontSize = 10.sp
                )
            }
        }
        trailing?.invoke()
    }
}

private fun formatSize(bytes: Long): String {
    if (bytes < 1024) return "${bytes} B"
    if (bytes < 1024 * 1024) return "${bytes / 1024} KB"
    return String.format(java.util.Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0))
}
