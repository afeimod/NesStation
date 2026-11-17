package com.nesstation.app.ui.library

import android.os.Environment
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import java.io.File
import java.util.Locale

// Dark pixel-art palette — matches FileListScreen.
private val BgColor = Color(0xFF0D1117)
private val CardColor = Color(0xFF1E2A3A)
private val PrimaryText = Color.White
private val SecondaryText = Color(0xFF9AA7B8)
private val AccentColor = Color(0xFF8A7BFF)
private val FocusedCardColor = Color(0xFF2A3A52)

/**
 * Built-in file browser dialog — used as a fallback when the system SAF
 * file/folder picker is unavailable (typical on Android TV boxes that ship
 * without DocumentsUI).
 *
 * Features:
 *  - Recursive scan of the selected folder for ROM files (up to 5 levels deep).
 *  - TV-friendly: every row is focusable and shows a focus highlight so D-pad
 *    navigation works.
 *  - Manual path entry: a text field at the top lets advanced users type or
 *    paste an absolute path directly (useful when the file system is large
 *    and walking it via D-pad is tedious).
 *  - Storage permission is required (caller is responsible for requesting it
 *    before showing this dialog; on Android 11+ we rely on the app's
 *    MANAGE_EXTERNAL_STORAGE or app-specific external dirs).
 *
 * @param initialDir  starting directory (defaults to external storage)
 * @param extensions  collection of lowercase extensions (without '.') to match
 * @param onPicked    called with the absolute path of the chosen folder
 * @param onDismiss   called when the user cancels
 */
@Composable
fun FileBrowserDialog(
    initialDir: File = Environment.getExternalStorageDirectory()
        ?: File("/"),
    extensions: Collection<String> = ROM_EXTENSIONS,
    onPicked: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var currentDir by remember { mutableStateOf(initialDir) }
    var manualPath by remember { mutableStateOf(currentDir.absolutePath) }
    val isPortrait =
        LocalConfiguration.current.orientation == android.content.res.Configuration.ORIENTATION_PORTRAIT

    // System back: walk up one level, or dismiss when at the initial root.
    BackHandler(enabled = true) {
        val parent = currentDir.parentFile
        if (currentDir == initialDir || parent == null) {
            onDismiss()
        } else {
            currentDir = parent
            manualPath = parent.absolutePath
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = false,
            dismissOnClickOutside = false
        )
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(if (isPortrait) 0.95f else 0.9f)
                .padding(8.dp),
            shape = RoundedCornerShape(18.dp),
            color = BgColor
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                // ── Header: back · current dir · up ──
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 4.dp, end = 4.dp, top = 8.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onDismiss) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = "关闭",
                            tint = PrimaryText
                        )
                    }
                    Column(modifier = Modifier.weight(1f).padding(horizontal = 4.dp)) {
                        Text(
                            text = "选择文件夹",
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
                    val canGoUp = currentDir.parentFile != null
                    IconButton(
                        onClick = {
                            currentDir.parentFile?.let {
                                currentDir = it
                                manualPath = it.absolutePath
                            }
                        },
                        enabled = canGoUp
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.ArrowUpward,
                            contentDescription = "上一级",
                            tint = if (canGoUp) AccentColor else SecondaryText
                        )
                    }
                }

                // ── Manual path entry ──
                val keyboard = LocalSoftwareKeyboardController.current
                OutlinedTextField(
                    value = manualPath,
                    onValueChange = { manualPath = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 4.dp),
                    singleLine = true,
                    placeholder = { Text("输入或粘贴绝对路径", color = SecondaryText, fontSize = 12.sp) },
                    textStyle = androidx.compose.ui.text.TextStyle(
                        color = PrimaryText,
                        fontSize = 13.sp
                    ),
                    trailingIcon = {
                        IconButton(onClick = {
                            val f = File(manualPath.trim())
                            if (f.exists() && f.isDirectory) {
                                currentDir = f
                                keyboard?.hide()
                            }
                        }) {
                            Icon(
                                imageVector = Icons.Rounded.Check,
                                contentDescription = "跳转",
                                tint = AccentColor
                            )
                        }
                    },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                    keyboardActions = KeyboardActions(onGo = {
                        val f = File(manualPath.trim())
                        if (f.exists() && f.isDirectory) {
                            currentDir = f
                            keyboard?.hide()
                        }
                    }),
                    shape = RoundedCornerShape(10.dp)
                )

                // ── Folder list ──
                val folders: List<File> = remember(currentDir) {
                    try {
                        currentDir.listFiles { f ->
                            !f.name.startsWith(".") && f.isDirectory
                        }?.toList()?.sortedBy { it.name.lowercase() }
                            ?: emptyList()
                    } catch (_: Exception) {
                        emptyList()
                    }
                }

                val romCount = remember(currentDir) { countRoms(currentDir, extensions, maxDepth = 5) }

                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    item {
                        Text(
                            text = "${folders.size} 个子文件夹 · 当前文件夹内含 $romCount 个 ROM 文件",
                            color = SecondaryText,
                            fontSize = 11.sp,
                            modifier = Modifier.padding(start = 4.dp, bottom = 4.dp)
                        )
                    }
                    if (folders.isEmpty()) {
                        item {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 32.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    "此目录下没有子文件夹",
                                    color = SecondaryText,
                                    fontSize = 13.sp
                                )
                            }
                        }
                    } else {
                        items(folders, key = { it.absolutePath }) { folder ->
                            FolderRow(
                                folder = folder,
                                romCount = countRoms(folder, extensions, maxDepth = 3),
                                onClick = {
                                    currentDir = folder
                                    manualPath = folder.absolutePath
                                }
                            )
                        }
                    }
                }

                // ── Footer: choose this folder / cancel ──
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(CardColor)
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = onDismiss) {
                        Text("取消", color = SecondaryText)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = if (romCount > 0) "含 $romCount 个 ROM" else "",
                            color = AccentColor,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(end = 8.dp)
                        )
                        TextButton(
                            onClick = { onPicked(currentDir.absolutePath) },
                            enabled = romCount > 0
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.FolderOpen,
                                contentDescription = null,
                                tint = if (romCount > 0) AccentColor else SecondaryText,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(Modifier.size(4.dp))
                            Text(
                                "选择此文件夹",
                                color = if (romCount > 0) AccentColor else SecondaryText,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * A single folder row — focusable for D-pad navigation.
 */
@Composable
private fun FolderRow(
    folder: File,
    romCount: Int,
    onClick: () -> Unit
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(if (focused) FocusedCardColor else CardColor)
            .border(
                width = if (focused) 2.dp else 0.dp,
                color = if (focused) AccentColor else Color.Transparent,
                shape = RoundedCornerShape(10.dp)
            )
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .focusable(interactionSource = interaction)
            .padding(horizontal = 14.dp, vertical = 12.dp)
    ) {
        Icon(
            imageVector = Icons.Rounded.Folder,
            contentDescription = null,
            tint = AccentColor,
            modifier = Modifier.size(22.dp)
        )
        Spacer(Modifier.size(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = folder.name,
                color = PrimaryText,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = if (romCount > 0) "含 $romCount 个 ROM 文件" else "空",
                color = if (romCount > 0) AccentColor else SecondaryText,
                fontSize = 11.sp
            )
        }
        Icon(
            imageVector = Icons.Rounded.ChevronRight,
            contentDescription = null,
            tint = SecondaryText,
            modifier = Modifier.size(18.dp)
        )
    }
}

/**
 * Recursively count ROM files in [dir] up to [maxDepth] levels deep.
 * Used to label each folder row with its ROM count and to enable/disable
 * the "choose this folder" button.
 */
private fun countRoms(
    dir: File,
    extensions: Collection<String>,
    maxDepth: Int
): Int {
    if (maxDepth <= 0) return 0
    val children = try {
        dir.listFiles() ?: return 0
    } catch (_: Exception) {
        return 0
    }
    var count = 0
    for (f in children) {
        if (f.name.startsWith(".")) continue
        if (f.isFile) {
            val ext = f.extension.lowercase()
            if (ext in extensions) count++
        } else if (f.isDirectory) {
            count += countRoms(f, extensions, maxDepth - 1)
        }
    }
    return count
}
