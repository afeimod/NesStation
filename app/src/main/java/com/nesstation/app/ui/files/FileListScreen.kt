package com.nesstation.app.ui.files

import android.os.Environment
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
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
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nesstation.app.ui.components.AppBackgroundState
import java.io.File
import java.util.Locale

// Dark pixel-art palette for the file browser.
// ★ Neon 主题适配（需求："swf等没有正确设计为neon的主题ui"）：
//   取色全部改为随 NeonUi.isNeon 切换的动态取色 + 背景换 Neon 深空底。
private val BgColor
    get() = if (com.nesstation.app.ui.neon.NeonUi.isNeon) com.nesstation.app.ui.neon.Neon.Bg
        else Color(0xFF0D1117)
private val CardColor
    get() = if (com.nesstation.app.ui.neon.NeonUi.isNeon) com.nesstation.app.ui.neon.Neon.BgPanelHi
        else Color(0xFF1E2A3A)
private val PrimaryText
    get() = if (com.nesstation.app.ui.neon.NeonUi.isNeon) com.nesstation.app.ui.neon.Neon.TextHi
        else Color.White
private val SecondaryText
    get() = if (com.nesstation.app.ui.neon.NeonUi.isNeon) com.nesstation.app.ui.neon.Neon.TextDim
        else Color(0xFF4A5568)
private val AccentColor
    get() = if (com.nesstation.app.ui.neon.NeonUi.isNeon) com.nesstation.app.ui.neon.Neon.Accent
        else Color(0xFF8A7BFF)

/**
 * A full-screen file browser for locating .swf files on the device.
 *
 * Starts at [Environment.getExternalStorageDirectory]. Folders are listed first
 * (with a folder icon), followed by .swf files (with a document icon and size).
 * Hidden entries (names starting with '.') are filtered out.
 *
 * - The top-left arrow exits the screen via [onBack].
 * - The header "up" button walks one directory level up (disabled at the root).
 * - The system back button also walks up one level, calling [onBack] only when
 *   already at the storage root.
 * - Tapping a folder descends into it; tapping a .swf file invokes [onOpenSwf]
 *   with its absolute path.
 */
@Composable
fun FileListScreen(
    onBack: () -> Unit,
    onOpenSwf: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val rootDir = remember { Environment.getExternalStorageDirectory() }
    var currentDir by remember { mutableStateOf(rootDir) }

    // System back: move up one directory, or exit when at the storage root.
    BackHandler(enabled = true) {
        val parent = currentDir.parentFile
        if (currentDir == rootDir || parent == null) {
            onBack()
        } else {
            currentDir = parent
        }
    }

    // Folders first (alphabetical), then .swf files (alphabetical).
    val entries: List<File> = remember(currentDir) {
        currentDir.listFiles()
            ?.toList()
            ?.filter { file ->
                !file.name.startsWith(".") &&
                    (file.isDirectory || file.extension.equals("swf", ignoreCase = true))
            }
            ?.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
            ?: emptyList()
    }

    val folderCount = entries.count { it.isDirectory }
    val swfCount = entries.count { it.isFile }
    val canGoUp = currentDir != rootDir && currentDir.parentFile != null

    Box(
        modifier = modifier
            .fillMaxSize()
    ) {
        // 背景：Neon=深空网格底 / FSD=纯深色；全局背景激活时透明
        if (!AppBackgroundState.active) {
            if (com.nesstation.app.ui.neon.NeonUi.isNeon) {
                com.nesstation.app.ui.neon.NeonBackdrop()
            } else {
                Box(Modifier.fillMaxSize().background(BgColor))
            }
        }
        Column(modifier = Modifier.fillMaxSize()) {
            // ---- Header: back arrow · current path · up-one-level ----
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 4.dp, end = 4.dp, top = 10.dp, bottom = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                        contentDescription = "返回",
                        tint = PrimaryText
                    )
                }
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 4.dp)
                ) {
                    Text(
                        text = if (currentDir == rootDir) "内部存储" else currentDir.name,
                        color = PrimaryText,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = currentDir.absolutePath,
                        color = SecondaryText,
                        fontSize = 11.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                IconButton(
                    onClick = { currentDir.parentFile?.let { currentDir = it } },
                    enabled = canGoUp
                ) {
                    Icon(
                        imageVector = Icons.Rounded.ArrowUpward,
                        contentDescription = "上一级",
                        tint = if (canGoUp) AccentColor else SecondaryText
                    )
                }
            }

            // ---- File list ----
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (entries.isEmpty()) {
                    item {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 72.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "此目录下没有 SWF 文件",
                                color = SecondaryText,
                                fontSize = 13.sp
                            )
                        }
                    }
                } else {
                    item {
                        Text(
                            text = "$folderCount 个文件夹 · $swfCount 个 SWF",
                            color = SecondaryText,
                            fontSize = 11.sp,
                            modifier = Modifier.padding(start = 4.dp, bottom = 4.dp)
                        )
                    }
                    items(entries, key = { it.absolutePath }) { file ->
                        FileEntryRow(
                            file = file,
                            onClick = {
                                if (file.isDirectory) {
                                    currentDir = file
                                } else {
                                    onOpenSwf(file.absolutePath)
                                }
                            }
                        )
                    }
                }
            }
        }
    }
}

/**
 * A single folder or .swf row. Folders use a folder icon; .swf files use a
 * document icon and display their size.
 */
@Composable
private fun FileEntryRow(
    file: File,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(CardColor)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp)
    ) {
        Icon(
            imageVector = if (file.isDirectory) Icons.Rounded.Folder else Icons.Rounded.Description,
            contentDescription = null,
            tint = if (file.isDirectory) AccentColor else PrimaryText,
            modifier = Modifier.size(24.dp)
        )
        Spacer(Modifier.size(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = file.name,
                color = PrimaryText,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = if (file.isFile) formatFileSize(file.length()) else "文件夹",
                color = SecondaryText,
                fontSize = 11.sp
            )
        }
        if (file.isDirectory) {
            Icon(
                imageVector = Icons.Rounded.ChevronRight,
                contentDescription = null,
                tint = SecondaryText,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

/** Formats a byte count as a human-readable size, e.g. "1.2 MB". */
private fun formatFileSize(bytes: Long): String {
    if (bytes <= 0L) return "0 B"
    return when {
        bytes < 1024L -> "$bytes B"
        bytes < 1024L * 1024L -> String.format(Locale.US, "%.1f KB", bytes / 1024.0)
        bytes < 1024L * 1024L * 1024L ->
            String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0))
        else -> String.format(Locale.US, "%.2f GB", bytes / (1024.0 * 1024.0 * 1024.0))
    }
}
