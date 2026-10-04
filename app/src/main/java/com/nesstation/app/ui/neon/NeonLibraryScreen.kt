package com.nesstation.app.ui.neon

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.CreateNewFolder
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.UploadFile
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.nesstation.app.core.model.GameEntry
import com.nesstation.app.core.model.GamePlatform
import com.nesstation.app.core.storage.ArcadeTitleMapper
import com.nesstation.app.core.storage.CoverFetcher
import com.nesstation.app.core.storage.JavaGameStore
import com.nesstation.app.core.storage.PlatformDetector
import com.nesstation.app.core.storage.RomStore
import com.nesstation.app.ui.components.AppBackgroundState
import com.nesstation.app.ui.library.CoverCandidateDialog
import com.nesstation.app.ui.library.JavaGameSettingsDialog
import com.nesstation.app.ui.library.ROM_EXTENSIONS
import com.nesstation.app.ui.library.findDosLauncherInSafTree
import com.nesstation.app.ui.library.scanUriForRomsRecursive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * ★★ Neon 3D 游戏库（完全重制版）—— 核心列表 + 每核心 3D 封面滚动 ★★
 *
 * 与主页的分工（用户需求）：核心列表【只】在游戏库页。结构：
 *   - 顶栏：返回 + 标题（游戏库/收藏/最近游玩 by [mode]）+ 工具栏
 *     （搜索 / 导入ROM / 导入文件夹 / 刷新 / 主页）
 *   - 左侧核心列表：全部 + 各平台（图标 + 名称 + 数量），选中青色辉光；
 *   - 右侧主视觉：当前核心游戏的 3D 封面滚动（[NeonFlow] 弧形导轨+透视+倒影）；
 *   - 信息条：选中游戏标题 + 平台徽章 + N of M 计数；
 *   - 长按（或 Y 键）：启动 / 收藏 / 重命名 / 删除 菜单。
 *
 * [mode]：all=游戏库 / fav=收藏 / recent=最近游玩（主页菜单行深链进来）。
 */
@Composable
fun NeonLibraryScreen(
    games: List<GameEntry>,
    mode: String = "all",
    onOpenGame: (GameEntry) -> Unit,
    onBack: () -> Unit,
    onHome: () -> Unit,
    onGamesChanged: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val coverCache = remember { android.util.LruCache<String, android.graphics.Bitmap>(48) }

    // ---- 状态 ----
    var selectedCore by remember { mutableStateOf<GamePlatform?>(null) }   // null = 全部
    var searchQuery by remember { mutableStateOf("") }
    var searchActive by remember { mutableStateOf(false) }
    var importing by remember { mutableStateOf(false) }
    var refreshing by remember { mutableStateOf(false) }
    var statusMsg by remember { mutableStateOf<String?>(null) }
    var longPressGame by remember { mutableStateOf<GameEntry?>(null) }
    var pendingRenameGame by remember { mutableStateOf<GameEntry?>(null) }
    var renameText by remember { mutableStateOf("") }
    var pendingDeleteGame by remember { mutableStateOf<GameEntry?>(null) }
    var pendingJavaSettingsGame by remember { mutableStateOf<GameEntry?>(null) }
    var pendingIconGame by remember { mutableStateOf<GameEntry?>(null) }
    var pendingCoverGame by remember { mutableStateOf<GameEntry?>(null) }
    var pendingClearSaveGame by remember { mutableStateOf<GameEntry?>(null) }
    var selectedIndex by remember { mutableIntStateOf(0) }
    // ★ 获取封面：批量下载当前核心缺失封面（与 FSD 游戏库同一套 CoverFetcher）
    var coverFetching by remember { mutableStateOf(false) }
    var coverProgress by remember { mutableStateOf<Pair<Int, Int>?>(null) }

    // ---- 数据 ----
    val modeGames = remember(games, mode) {
        when (mode) {
            "fav" -> games.filter { it.isFavorite }
            "recent" -> games.filter { it.lastPlayedAt > 0L }.sortedByDescending { it.lastPlayedAt }
            else -> games
        }
    }
    val searching = searchActive && searchQuery.isNotBlank()
    val displayGames = remember(modeGames, selectedCore, searching, searchQuery) {
        if (searching) {
            modeGames.filter {
                it.title.contains(searchQuery, ignoreCase = true) ||
                    (it.customTitle?.contains(searchQuery, ignoreCase = true) ?: false)
            }
        } else if (selectedCore != null) {
            modeGames.filter { it.platform == selectedCore }
        } else {
            modeGames
        }
    }
    val countByCore = remember(modeGames) { modeGames.groupingBy { it.platform }.eachCount() }
    val coreOrder = remember {
        listOf(
            GamePlatform.NES, GamePlatform.SFC, GamePlatform.GB, GamePlatform.GBA,
            GamePlatform.MD, GamePlatform.PCE, GamePlatform.PSX, GamePlatform.PS2,
            GamePlatform.NDS, GamePlatform.N3DS, GamePlatform.NGCWII, GamePlatform.DC,
            GamePlatform.ARCADE, GamePlatform.DOS, GamePlatform.JAVA
        )
    }
    val coreEntries = remember(modeGames, countByCore, coreOrder, searching) {
        if (searching) {
            // 搜索态：核心列表退位为单一「搜索结果」伪核心
            listOf(NeonCoreEntry(null, "搜索结果", displayGames.size))
        } else {
            buildList {
                add(NeonCoreEntry(null, "全部", modeGames.size))
                coreOrder.forEach { p ->
                    val n = countByCore[p] ?: 0
                    if (n > 0) add(NeonCoreEntry(p, p.displayName, n))
                }
            }
        }
    }

    // 切核心 / 搜索词变化回到第一个；列表变化收敛索引
    LaunchedEffect(selectedCore, searchQuery, searchActive) { selectedIndex = 0 }
    LaunchedEffect(displayGames.size) {
        if (selectedIndex >= displayGames.size) selectedIndex = 0
    }
    val selIdx = selectedIndex.coerceIn(0, (displayGames.size - 1).coerceAtLeast(0))
    val currentGame = displayGames.getOrNull(selIdx)

    // ---- 导入 / 刷新（与 FSD 游戏库同一套判定链，逻辑共享）----

    /** SAF 多选文件导入（CD 多文件去重：.cue 优先，跳过 .bin/.img/.sub 附属）。 */
    suspend fun importPickedFiles(uris: List<Uri>): String = withContext(Dispatchers.IO) {
        data class Picked(val uri: Uri, val name: String, val ext: String)
        val picked = mutableListOf<Picked>()
        uris.forEach { u ->
            val n = neonQueryDisplayName(u) ?: return@forEach
            val e = n.substringAfterLast('.', "").lowercase()
            if (e in ROM_EXTENSIONS) picked.add(Picked(u, n, e))
        }
        val exts = picked.map { it.ext }.toSet()
        val hasCue = "cue" in exts
        val hasCcd = "ccd" in exts
        val skipIfCue = setOf("img", "bin", "ccd", "sub", "iso")
        val skipIfCcd = setOf("img", "sub")
        val filtered = picked.filter { c ->
            when {
                c.ext == "sub" -> false
                hasCue && c.ext in skipIfCue -> false
                !hasCue && hasCcd && c.ext in skipIfCcd -> false
                else -> true
            }
        }
        val hint = selectedCore
        val items = mutableListOf<Triple<String, String, GamePlatform>>()
        filtered.forEach { pf ->
            try {
                context.contentResolver.takePersistableUriPermission(
                    pf.uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (_: Exception) { }
            val platform = PlatformDetector.detectFromUri(context, pf.uri, pf.name, hint)
            val title = when (platform) {
                GamePlatform.ARCADE -> ArcadeTitleMapper.resolveDisplayTitle(pf.name)
                GamePlatform.PSX ->
                    com.nesstation.app.core.storage.PsxTitleExtractor
                        .extractTitle(context, pf.uri.toString())
                        ?: pf.name.substringBeforeLast('.')
                else -> pf.name.substringBeforeLast('.')
            }
            items.add(Triple(title, pf.uri.toString(), platform))
        }
        val added = RomStore.importGames(context, items)
        withContext(Dispatchers.Main) { onGamesChanged() }
        if (added.isNotEmpty()) "已导入 ${added.size} 个ROM文件" else "没有新增（可能已导入过）"
    }

    /** SAF 文件夹导入（DOS 找启动器 / 其他平台递归扫描）。 */
    suspend fun importFolder(uri: Uri): String = withContext(Dispatchers.IO) {
        val hint = selectedCore
        if (hint == GamePlatform.DOS) {
            val launcher = findDosLauncherInSafTree(context, uri)
            if (launcher == null) {
                "所选文件夹未找到 DOS 启动文件（.bat / .exe / .com）\n建议命名：play.bat / run.bat / START.BAT"
            } else {
                val launcherName = neonQueryDisplayName(launcher) ?: "dos_game.bat"
                val execName = launcherName.substringBeforeLast('.')
                val folderName = neonFolderNameFromTreeUri(uri)
                val title = if (folderName.isNotEmpty()) "$folderName($execName)" else execName
                RomStore.add(context, title, launcher.toString(), GamePlatform.DOS)
                withContext(Dispatchers.Main) { onGamesChanged() }
                "已导入 DOS 游戏：$title"
            }
        } else {
            val romFiles = scanUriForRomsRecursive(context, uri, uri, maxDepth = 5)
            if (romFiles.isEmpty()) {
                "所选文件夹未找到ROM文件（支持 ${ROM_EXTENSIONS.joinToString().take(80)}…）"
            } else {
                RomStore.setLastImportFolder(context, uri.toString(), hint)
                val items = mutableListOf<Triple<String, String, GamePlatform>>()
                romFiles.forEach { (name, fileUri) ->
                    try {
                        val platform = PlatformDetector.detectFromUri(context, fileUri, name, hint)
                        val title = when (platform) {
                            GamePlatform.ARCADE -> ArcadeTitleMapper.resolveDisplayTitle(name)
                            GamePlatform.PSX ->
                                com.nesstation.app.core.storage.PsxTitleExtractor
                                    .extractTitle(context, fileUri.toString())
                                    ?: name.substringBeforeLast('.')
                            else -> name.substringBeforeLast('.')
                        }
                        items.add(Triple(title, fileUri.toString(), platform))
                    } catch (_: Exception) { }
                }
                val added = RomStore.importGames(context, items)
                withContext(Dispatchers.Main) { onGamesChanged() }
                "从文件夹导入 ${added.size} 个ROM文件"
            }
        }
    }

    /** 重扫当前核心（全部=所有核心）已导入文件夹：补新增 + 清失效。 */
    suspend fun refreshCurrent(): String = withContext(Dispatchers.IO) {
        val target = selectedCore
        val folders = RomStore.getImportedFolders(context)
            .filter { target == null || it.second == target }
        if (folders.isEmpty()) {
            "没有已导入的文件夹记录（导入文件夹后刷新才有意义）"
        } else {
            val snapshot = RomStore.loadAll(context)
            val knownPaths = snapshot.mapNotNull { it.romPath }.toHashSet()
            val removeIds = mutableSetOf<String>()
            val addItems = mutableListOf<Triple<String, String, GamePlatform>>()
            var lostAccess = false
            folders.forEach { (folderUriStr, hintPlatform) ->
                if (target != null && hintPlatform != target) return@forEach
                if (folderUriStr.startsWith("content://")) {
                    try {
                        val folderUri = Uri.parse(folderUriStr)
                        val romFiles = scanUriForRomsRecursive(context, folderUri, folderUri, maxDepth = 5)
                        val found = romFiles.map { it.second.toString() }.toSet()
                        // 目录树前缀（防止误删兄弟目录的游戏）
                        val s = folderUriStr
                        val prefix = if (s.indexOf("/document/") > 0) s.substring(0, s.indexOf("/document/")) else s
                        snapshot.forEach { g ->
                            val p = g.romPath ?: return@forEach
                            if (!p.startsWith("content://")) return@forEach
                            if (g.platform != hintPlatform) return@forEach
                            val under = p == prefix || p.startsWith("$prefix/document/")
                            if (under && p !in found) removeIds.add(g.id)
                        }
                        romFiles.forEach { (name, u) ->
                            val us = u.toString()
                            if (us !in knownPaths) {
                                try {
                                    val platform = PlatformDetector.detectFromUri(context, u, name, hintPlatform)
                                    val title = when (platform) {
                                        GamePlatform.ARCADE -> ArcadeTitleMapper.resolveDisplayTitle(name)
                                        else -> name.substringBeforeLast('.')
                                    }
                                    addItems.add(Triple(title, us, platform))
                                    knownPaths.add(us)
                                } catch (_: Exception) { }
                            }
                        }
                    } catch (_: SecurityException) {
                        lostAccess = true
                    } catch (_: Exception) { }
                } else {
                    // 本地目录：目录消失/文件丢失的条目清理
                    val dir = File(folderUriStr)
                    if (!dir.exists()) {
                        snapshot.forEach { g ->
                            val p = g.romPath ?: return@forEach
                            if (g.platform == hintPlatform && p.startsWith(folderUriStr)) {
                                removeIds.add(g.id)
                            }
                        }
                    }
                }
            }
            val removed = if (removeIds.isNotEmpty()) RomStore.removeIds(context, removeIds) else 0
            val added = if (addItems.isNotEmpty()) RomStore.importGames(context, addItems).size else 0
            withContext(Dispatchers.Main) { onGamesChanged() }
            when {
                lostAccess -> "部分文件夹授权已失效，请重新「导入文件夹」"
                else -> "刷新完成：新增 $added，移除 $removed"
            }
        }
    }

    val filePickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        if (importing) { statusMsg = "上一次导入还在进行中"; return@rememberLauncherForActivityResult }
        importing = true
        scope.launch {
            try { statusMsg = importPickedFiles(uris) }
            catch (e: Exception) { statusMsg = "导入失败：${e.message}" }
            finally { importing = false }
        }
    }

    val folderPickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        try {
            context.contentResolver.takePersistableUriPermission(
                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        } catch (_: Exception) { }
        if (importing) { statusMsg = "上一次导入还在进行中"; return@rememberLauncherForActivityResult }
        importing = true
        scope.launch {
            try { statusMsg = importFolder(uri) }
            catch (e: SecurityException) { statusMsg = "没有权限访问所选文件夹，请重试" }
            catch (e: Exception) { statusMsg = "导入文件夹失败：${e.message}" }
            finally { importing = false }
        }
    }

    // ★ 自定义图标：SAF 选图 → 拷贝到内部目录 → RomStore.setCustomIcon
    //   （与 FSD 游戏库长按菜单「自定义图标」同一套链路）
    val iconPickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        val game = pendingIconGame
        if (uris.isEmpty() || game == null) {
            pendingIconGame = null
            return@rememberLauncherForActivityResult
        }
        val uri = uris.first()
        try {
            val iconsDir = File(context.filesDir, "icons").apply { mkdirs() }
            val iconFile = File(iconsDir, "icon_${game.id}_${System.currentTimeMillis()}.png")
            context.contentResolver.openInputStream(uri)?.use { input ->
                iconFile.outputStream().use { output -> input.copyTo(output) }
            }
            RomStore.setCustomIcon(context, game.id, iconFile.absolutePath)
            onGamesChanged()
            statusMsg = "已设置自定义图标"
        } catch (e: Exception) {
            statusMsg = "图标设置失败：${e.message}"
        }
        pendingIconGame = null
    }

    /** ★ 获取封面：批量下载当前核心（全部=整库）缺失封面，进度实时显示在按钮上。 */
    fun fetchCoversManual() {
        if (coverFetching) return
        coverFetching = true
        scope.launch(Dispatchers.IO) {
            try {
                val target = if (searching) modeGames
                else modeGames.filter { selectedCore == null || it.platform == selectedCore }
                val fetched = CoverFetcher.fetchAllMissing(
                    context, target, onlyMissing = true, limit = 400,
                    onProgress = { done, total -> coverProgress = done to total }
                )
                if (fetched > 0) {
                    withContext(Dispatchers.Main) {
                        statusMsg = "已获取 $fetched 个游戏封面"
                        onGamesChanged()
                    }
                } else {
                    val s = CoverFetcher.lastBatchStats
                    withContext(Dispatchers.Main) {
                        statusMsg = if (s.attempted == 0) {
                            "没有需要下载封面的游戏\n\n" + CoverFetcher.lastPendingInfo +
                                "\n（已有封面/自定义图标的条目不重复下载）"
                        } else {
                            "未下载到新封面\n\n" + s.summary() +
                                "\n\n完整诊断已写入：\nAndroid/data/com.nesstation.app/files/cover_debug.log"
                        }
                    }
                }
            } catch (t: Throwable) {
                if (t is kotlinx.coroutines.CancellationException) throw t
                withContext(Dispatchers.Main) { statusMsg = "封面获取失败：${t.message}" }
            } finally {
                coverFetching = false
                coverProgress = null
            }
        }
    }

    // ★ 封面自动补齐：首次进入每个核心页时后台静默拉取该核心缺失封面
    //   （每核心一次，会话内不重复；JAVA 用本地图标、全部/搜索态不自动跑）
    val coverAutoDone = remember { mutableStateListOf<GamePlatform>() }
    LaunchedEffect(selectedCore, searching) {
        val core = selectedCore ?: return@LaunchedEffect
        if (searching) return@LaunchedEffect
        if (core == GamePlatform.JAVA) return@LaunchedEffect
        if (core in coverAutoDone) return@LaunchedEffect
        coverAutoDone.add(core)
        if (coverFetching) return@LaunchedEffect
        val platformGames = modeGames.filter { it.platform == core }
        if (platformGames.isEmpty()) return@LaunchedEffect
        val fetched = withContext(Dispatchers.IO) {
            try {
                CoverFetcher.fetchAllMissing(context, platformGames, limit = 200)
            } catch (t: Throwable) {
                if (t is kotlinx.coroutines.CancellationException) throw t
                0
            }
        }
        if (fetched > 0) onGamesChanged()
    }

    // ---- 布局 ----
    val isPortrait = LocalConfiguration.current.orientation ==
        android.content.res.Configuration.ORIENTATION_PORTRAIT
    val modeTitle = when (mode) { "fav" -> "收藏游戏"; "recent" -> "最近游玩"; else -> "游戏库" }
    val modeSub = when (mode) { "fav" -> "FAVORITES"; "recent" -> "RECENT"; else -> "GAME LIBRARY" }

    Box(modifier = modifier.fillMaxSize()) {
        // ★ 未设置全局背景时：背景 = 当前选中游戏封面全图（随选中切换淡入淡出）
        if (!AppBackgroundState.active) NeonCoverBackdrop(currentGame, coverCache)

        Column(modifier = Modifier.fillMaxSize()) {
            // ===== 顶栏：返回 + 标题 + 工具栏 =====
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.AutoMirrored.Rounded.ArrowBack, null,
                    tint = Neon.TextHi,
                    modifier = Modifier
                        .size(38.dp)
                        .clickable { onBack() }
                        .padding(8.dp)
                )
                Column {
                    Text(modeTitle, color = Neon.TextHi, fontSize = 19.sp, fontWeight = FontWeight.ExtraBold)
                    Text(
                        "$modeSub · ${if (searching) "${displayGames.size} 结果" else "${modeGames.size} GAMES"}",
                        color = Neon.Accent, fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp
                    )
                }
                Spacer(Modifier.weight(1f))
                NeonToolbarButton(
                    icon = Icons.Rounded.Search,
                    label = "搜索",
                    tint = if (searchActive) Neon.Accent else Neon.Text
                ) {
                    searchActive = !searchActive
                    if (!searchActive) searchQuery = ""
                }
                Spacer(Modifier.width(7.dp))
                NeonToolbarButton(Icons.Rounded.UploadFile, "导入ROM") {
                    runCatching { filePickerLauncher.launch(arrayOf("*/*")) }
                }
                Spacer(Modifier.width(7.dp))
                NeonToolbarButton(Icons.Rounded.CreateNewFolder, "导入文件夹") {
                    runCatching { folderPickerLauncher.launch(null) }
                }
                Spacer(Modifier.width(7.dp))
                NeonToolbarButton(Icons.Rounded.Refresh, "刷新") {
                    if (!refreshing) {
                        refreshing = true
                        scope.launch {
                            try { statusMsg = refreshCurrent() }
                            catch (e: Exception) { statusMsg = "刷新失败：${e.message}" }
                            finally { refreshing = false }
                        }
                    }
                }
                Spacer(Modifier.width(7.dp))
                // ★ 获取封面：批量下载当前核心缺失封面（与 FSD 游戏库同款）
                NeonToolbarButton(
                    icon = Icons.Rounded.Image,
                    label = if (coverFetching) {
                        "封面 ${coverProgress?.first ?: 0}/${coverProgress?.second ?: 0}"
                    } else "获取封面",
                    tint = if (coverFetching) Neon.Accent else Neon.Text
                ) { fetchCoversManual() }
                Spacer(Modifier.width(7.dp))
                NeonToolbarButton(Icons.Rounded.Home, "主页", tint = Neon.Accent, onClick = onHome)
                Spacer(Modifier.width(4.dp))
            }

            // ===== 搜索行 =====
            if (searchActive) {
                NeonSearchField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 2.dp)
                )
            }

            // ===== 主体：核心列表 + 封面流 =====
            Row(modifier = Modifier.weight(1f).fillMaxWidth()) {
                // -- 左侧核心列表 --
                NeonCoreSidebar(
                    entries = coreEntries,
                    selected = if (searching) null else selectedCore,
                    searching = searching,
                    onSelect = { selectedCore = it },
                    modifier = Modifier
                        .width(if (isPortrait) 118.dp else 172.dp)
                        .fillMaxSize()
                        .padding(start = 10.dp, top = 4.dp, bottom = 4.dp)
                )

                // -- 右侧 3D 封面流 --
                Box(modifier = Modifier.weight(1f).fillMaxSize()) {
                    if (displayGames.isEmpty()) {
                        Column(
                            modifier = Modifier.align(Alignment.Center),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                if (searching) "未找到匹配「$searchQuery」的游戏"
                                else "该分类还没有游戏",
                                color = Neon.TextHi, fontSize = 16.sp, fontWeight = FontWeight.SemiBold
                            )
                            Spacer(Modifier.height(8.dp))
                            Text(
                                if (searching) "换个关键词试试，或点击 X 关闭搜索"
                                else "点击右上角「导入ROM / 导入文件夹」添加游戏",
                                color = Neon.TextDim, fontSize = 12.sp
                            )
                        }
                    } else {
                        // ★ 封面流铺满整个右侧区域（原尺寸不缩小，仅下移避开顶栏）；
                        //   倒影可沉入底部信息条下方渐隐
                        NeonFlow(
                            count = displayGames.size,
                            selectedIndex = selIdx,
                            onIndexChange = { selectedIndex = it },
                            onItemClick = { idx -> displayGames.getOrNull(idx)?.let(onOpenGame) },
                            onItemLongClick = { idx ->
                                displayGames.getOrNull(idx)?.let { longPressGame = it }
                            },
                            grabFocusOnLaunch = true,
                            showReflection = true,
                            // ★ 下移不缩小：整体下沉避开上方文本，尺寸不变
                            verticalShift = if (isPortrait) 12.dp else 26.dp,
                            itemWidth = if (isPortrait) 150.dp else 178.dp,
                            itemHeight = if (isPortrait) 200.dp else 238.dp,
                            modifier = Modifier.fillMaxSize()
                        ) { i ->
                            NeonCoverCard(
                                game = displayGames[i],
                                cache = coverCache,
                                glow = if (i == selIdx) 1f else 0f,
                                modifier = Modifier.fillMaxSize()
                            )
                        }

                        // ===== 信息条：叠加在封面流之上（后绘制 = 永远在前），
                        //   透明底 + 文字描影：不遮封面、无黑块 =====
                        currentGame?.let { game ->
                            NeonLibraryInfoBar(
                                game = game,
                                index = selIdx + 1,
                                total = displayGames.size,
                                modifier = Modifier
                                    .align(Alignment.BottomCenter)
                                    .fillMaxWidth()
                                    .padding(horizontal = 22.dp, vertical = 10.dp)
                            )
                        }
                    }
                }
            }

            // ===== 底部按键提示 =====
            NeonHintsBar(
                hints = listOf(
                    Triple("A", "启动", Neon.BtnA),
                    Triple("B", "返回", Neon.BtnB),
                    Triple("Y", "选项", Neon.BtnY),
                    Triple("X", "搜索", Neon.BtnX)
                ),
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .padding(bottom = 8.dp)
            )
        }
    }

    // ===== 对话框们 =====
    if (importing) {
        AlertDialog(
            onDismissRequest = { },
            confirmButton = { },
            title = { Text("正在导入…", fontSize = 16.sp, fontWeight = FontWeight.Bold) },
            text = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(
                        color = Neon.Accent,
                        modifier = Modifier.size(22.dp)
                    )
                    Spacer(Modifier.width(14.dp))
                    Text("正在扫描/判定平台并写入游戏库，大目录请稍候", fontSize = 13.sp)
                }
            }
        )
    }
    if (refreshing) {
        AlertDialog(
            onDismissRequest = { },
            confirmButton = { },
            title = { Text("正在刷新…", fontSize = 16.sp, fontWeight = FontWeight.Bold) },
            text = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(
                        color = Neon.Accent,
                        modifier = Modifier.size(22.dp)
                    )
                    Spacer(Modifier.width(14.dp))
                    Text("正在重扫已导入的文件夹", fontSize = 13.sp)
                }
            }
        )
    }
    statusMsg?.let { msg ->
        AlertDialog(
            onDismissRequest = { statusMsg = null },
            confirmButton = {
                TextButton(onClick = { statusMsg = null }) { Text("确定") }
            },
            text = { Text(msg) }
        )
    }

    // 长按选项菜单（Neon 深色弹窗）★ 与 FSD 游戏库长按菜单完全对齐（8 项）
    longPressGame?.let { game ->
        NeonGameOptionsMenu(
            game = game,
            onDismiss = { longPressGame = null },
            onPlay = { longPressGame = null; onOpenGame(game) },
            onGameSettings = {
                longPressGame = null
                if (game.platform == GamePlatform.JAVA) {
                    // Java 游戏：本游戏专属设置（分辨率/缩放/帧率/触摸/透明度等）
                    pendingJavaSettingsGame = game
                } else {
                    onOpenGame(game)
                }
            },
            onCustomIcon = {
                longPressGame = null
                pendingIconGame = game
                runCatching { iconPickerLauncher.launch(arrayOf("image/*")) }
            },
            onPickCover = {
                longPressGame = null
                pendingCoverGame = game
            },
            onToggleFavorite = {
                longPressGame = null
                try {
                    RomStore.toggleFavorite(context, game.id)
                    onGamesChanged()
                } catch (_: Exception) { }
            },
            onRename = {
                longPressGame = null
                pendingRenameGame = game
                renameText = game.customTitle?.takeIf { it.isNotBlank() } ?: game.title
            },
            onClear3dsSave = {
                longPressGame = null
                pendingClearSaveGame = game
            },
            onDelete = {
                longPressGame = null
                pendingDeleteGame = game
            }
        )
    }

    // Java 游戏专属设置弹窗（与 FSD 游戏库共用同一组件）
    pendingJavaSettingsGame?.let { game ->
        JavaGameSettingsDialog(
            game = game,
            onDismiss = { pendingJavaSettingsGame = null }
        )
    }

    // 封面候选选择弹窗（手动输入关键词搜索 + 模糊匹配，点选即设为正式封面）
    pendingCoverGame?.let { game ->
        CoverCandidateDialog(
            game = game,
            onDismiss = { pendingCoverGame = null },
            onPicked = {
                pendingCoverGame = null
                onGamesChanged()
                statusMsg = "封面已更新"
            },
            onError = { pendingCoverGame = null }
        )
    }

    // 3DS 存档清除确认弹窗（建档卡死急救，Azahar sdmc title 存档目录）
    pendingClearSaveGame?.let { game ->
        AlertDialog(
            onDismissRequest = { pendingClearSaveGame = null },
            title = { Text("清除 3DS 存档", fontWeight = FontWeight.Bold) },
            text = {
                Text(
                    "删除「${game.customTitle?.takeIf { it.isNotBlank() } ?: game.title}」的 3DS 存档数据？\n\n" +
                        "· 只删除 Azahar sdmc 里该游戏的存档（title/…/data），" +
                        "游戏本体与其它游戏不受影响；\n" +
                        "· 下次进入该游戏会重新建档（部分游戏建档卡死时的急救手段）；\n" +
                        "· 此操作不可撤销，现有存档进度将丢失。"
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val g = game
                    pendingClearSaveGame = null
                    scope.launch(Dispatchers.IO) {
                        val repair = com.nesstation.app.core.storage.AzaharSaveDataRepair
                        // 先从 ROM（NCCH 头 / 已安装路径）解析 Title ID
                        val tid = repair.parseTitleId(context, g.romPath)
                        val ok = tid != null && repair.clearGameSaveData(context, tid)
                        withContext(Dispatchers.Main) {
                            statusMsg = if (tid == null) {
                                "未能从该游戏文件解析 Title ID（${g.romPath?.substringAfterLast('/') ?: "?"}），" +
                                    "无法定位存档目录。"
                            } else if (ok) {
                                "已清除「${g.customTitle?.takeIf { it.isNotBlank() } ?: g.title}」的 3DS 存档" +
                                    "（Title ID $tid）。下次进入将重新建档。"
                            } else {
                                "该游戏当前没有可清除的存档数据（Title ID $tid）。"
                            }
                        }
                    }
                }) { Text("清除", color = Neon.Red) }
            },
            dismissButton = {
                TextButton(onClick = { pendingClearSaveGame = null }) { Text("取消") }
            }
        )
    }

    // 重命名
    pendingRenameGame?.let { game ->
        AlertDialog(
            onDismissRequest = { pendingRenameGame = null },
            title = { Text("重命名游戏", fontWeight = FontWeight.Bold) },
            text = {
                Column {
                    Text("仅修改显示名，ROM 文件本身不会被改动。", fontSize = 12.sp, color = Color(0xFF666666))
                    Spacer(Modifier.height(8.dp))
                    androidx.compose.material3.OutlinedTextField(
                        value = renameText,
                        onValueChange = { renameText = it },
                        singleLine = true,
                        label = { Text("显示名称") }
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val t = renameText.trim()
                    try {
                        // 空白 = 恢复默认标题（与 FSD 游戏库行为一致）
                        RomStore.setCustomTitle(context, game.id, t.takeIf { it.isNotEmpty() })
                        onGamesChanged()
                    } catch (_: Exception) { }
                    pendingRenameGame = null
                }) { Text("保存") }
            },
            dismissButton = {
                TextButton(onClick = { pendingRenameGame = null }) { Text("取消") }
            }
        )
    }

    // 删除确认
    pendingDeleteGame?.let { game ->
        AlertDialog(
            onDismissRequest = { pendingDeleteGame = null },
            title = { Text("删除游戏？", fontWeight = FontWeight.Bold) },
            text = {
                Text("将从游戏库移除「${game.customTitle?.takeIf { it.isNotBlank() } ?: game.title}」。\n（ROM 文件本身不会被删除，可随时重新导入）")
            },
            confirmButton = {
                TextButton(onClick = {
                    try {
                        // Java 游戏走 JavaGameStore（连带删除 JAR/配置），
                        // 其余走 RomStore（只移库记录）
                        if (game.platform == GamePlatform.JAVA) {
                            JavaGameStore.deleteGame(context, game)
                        } else {
                            RomStore.remove(context, game.id)
                        }
                        onGamesChanged()
                    } catch (_: Exception) { }
                    pendingDeleteGame = null
                }) { Text("删除", color = Neon.Red) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDeleteGame = null }) { Text("取消") }
            }
        )
    }
}

// =====================================================================
// 私有组件
// =====================================================================

/** 核心列表条目（platform=null 表示「全部」）。 */
private data class NeonCoreEntry(
    val platform: GamePlatform?,
    val label: String,
    val count: Int
)

/** 左侧核心列表：全部 + 各平台（图标 + 名称 + 数量徽标），选中青色辉光弹出。 */
@Composable
private fun NeonCoreSidebar(
    entries: List<NeonCoreEntry>,
    selected: GamePlatform?,
    searching: Boolean,
    onSelect: (GamePlatform?) -> Unit,
    modifier: Modifier = Modifier
) {
    LazyColumn(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        items(entries, key = { it.label }) { entry ->
            val isSelected = searching || entry.platform == selected
            val interaction = remember { MutableInteractionSource() }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .graphicsLayer {
                        // 选中 3D 弹出：放大 + 浮起
                        if (isSelected) {
                            scaleX = 1.04f
                            scaleY = 1.04f
                            translationX = 5f
                        }
                    }
                    .background(
                        if (isSelected) Neon.BgPanelHi else Neon.BgPanel.copy(alpha = 0.55f),
                        neonChamfer(0.20f)
                    )
                    .border(
                        1.dp,
                        if (isSelected) Neon.Accent else Neon.Line,
                        neonChamfer(0.20f)
                    )
                    .clickable(
                        interactionSource = interaction,
                        indication = null,
                        enabled = !searching
                    ) { onSelect(entry.platform) }
                    .padding(horizontal = 10.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 选中指示菱形
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .graphicsLayer { rotationZ = 45f }
                        .background(if (isSelected) Neon.Accent else Color.Transparent)
                )
                Spacer(Modifier.width(9.dp))
                Icon(
                    entry.platform?.let { neonPlatformIcon(it) } ?: Icons.Rounded.Home,
                    null,
                    tint = if (isSelected) Neon.Accent else Neon.TextDim,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(8.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        entry.label,
                        color = if (isSelected) Neon.TextHi else Neon.Text,
                        fontSize = 13.sp,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                        maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                    entry.platform?.let {
                        Text(
                            platformEra(it),
                            color = Neon.TextDim,
                            fontSize = 8.sp,
                            maxLines = 1, overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                Text(
                    entry.count.toString(),
                    color = if (isSelected) Neon.Accent else Neon.TextDim,
                    fontSize = 12.sp, fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

/** 封面流底部信息条（★ 透明底无黑块：文字描影直接叠在封面/倒影上，
 *  后绘制永远在封面之前；游戏名 13sp 小巧不拓场面）。 */
@Composable
private fun NeonLibraryInfoBar(
    game: GameEntry,
    index: Int,
    total: Int,
    modifier: Modifier = Modifier
) {
    val title = game.customTitle?.takeIf { it.isNotBlank() } ?: game.title
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // 游戏名：透明底 + 描影（★ 不再包黑块横幅）
        Text(
            title,
            color = Neon.TextHi,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = neonShadowTextStyle(),
            modifier = Modifier.weight(1f, fill = false)
        )
        // 平台徽章（青色小标）
        Box(
            modifier = Modifier
                .background(Neon.Accent.copy(alpha = 0.16f), neonChamfer(0.5f))
                .border(1.dp, Neon.AccentDim, neonChamfer(0.5f))
                .padding(horizontal = 7.dp, vertical = 2.dp)
        ) {
            Text(
                game.platform.displayName,
                color = Neon.Accent, fontSize = 9.sp, fontWeight = FontWeight.Bold
            )
        }
        Spacer(Modifier.weight(1f))
        // N of M 计数：透明底 + 描影（★ 去黑块）
        Text(
            "$index of $total",
            color = Neon.Accent,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            style = neonShadowTextStyle()
        )
    }
}

/** 长按游戏的操作菜单（Neon 深色切角弹窗）——与 FSD 游戏库长按菜单完全对齐（8 项）。 */
@Composable
private fun NeonGameOptionsMenu(
    game: GameEntry,
    onDismiss: () -> Unit,
    onPlay: () -> Unit,
    onGameSettings: () -> Unit,
    onCustomIcon: () -> Unit,
    onPickCover: () -> Unit,
    onToggleFavorite: () -> Unit,
    onRename: () -> Unit,
    onClear3dsSave: () -> Unit,
    onDelete: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = Color(0xF2050712),
            modifier = Modifier.fillMaxWidth(0.86f)
        ) {
            Column(
                modifier = Modifier
                    .padding(vertical = 10.dp)
                    .heightIn(max = 420.dp)
            ) {
                Text(
                    game.customTitle?.takeIf { it.isNotBlank() } ?: game.title,
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp,
                    color = Neon.TextHi,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 12.dp)
                )
                Column(
                    modifier = Modifier
                        .verticalScroll(rememberScrollState())
                        .weight(1f, fill = false)
                ) {
                    NeonMenuOption("开始游戏") { onDismiss(); onPlay() }
                    NeonMenuOption("游戏设置") { onDismiss(); onGameSettings() }
                    NeonMenuOption("自定义图标") { onDismiss(); onCustomIcon() }
                    // 封面候选选择：中文名/模糊命名搜不到满意封面时手动搜索指定
                    NeonMenuOption("选择封面") { onDismiss(); onPickCover() }
                    NeonMenuOption(if (game.isFavorite) "取消收藏" else "收藏") { onDismiss(); onToggleFavorite() }
                    NeonMenuOption("重命名") { onDismiss(); onRename() }
                    // 3DS 存档急救（建档卡死兑底）：删除 Azahar sdmc 里该游戏的
                    // title 存档目录，下次进游戏重新完整建档
                    if (game.platform == GamePlatform.N3DS) {
                        NeonMenuOption("清除3DS存档（建档卡死急救）", danger = true) {
                            onDismiss(); onClear3dsSave()
                        }
                    }
                    NeonMenuOption("删除游戏", danger = true) { onDismiss(); onDelete() }
                }
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 4.dp),
                    contentAlignment = Alignment.CenterEnd
                ) {
                    TextButton(onClick = onDismiss) { Text("关闭", color = Neon.TextDim) }
                }
            }
        }
    }
}

/** 菜单选项行（Neon 深色）。 */
@Composable
private fun NeonMenuOption(
    label: String,
    danger: Boolean = false,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(6.dp)
                .background(
                    if (danger) Neon.Red else Neon.Accent,
                    androidx.compose.foundation.shape.CircleShape
                )
        )
        Spacer(Modifier.width(12.dp))
        Text(
            label,
            color = if (danger) Neon.Red else Neon.Text,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium
        )
    }
}

// =====================================================================
// SAF 小工具（本地私有版本）
// =====================================================================

/** 查询 content:// URI 的显示名。 */
private fun neonQueryDisplayName(uri: Uri): String? {
    return try {
        val context = com.nesstation.app.NesApp.get() ?: return null
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (nameIndex >= 0 && cursor.moveToFirst()) cursor.getString(nameIndex) else null
        }
    } catch (_: Exception) {
        uri.lastPathSegment
    }
}

/** 从 tree URI 提取文件夹名（DOS 标题用）。 */
private fun neonFolderNameFromTreeUri(treeUri: Uri): String {
    return try {
        val paths = treeUri.pathSegments
        val treeIdx = paths.indexOf("tree")
        if (treeIdx < 0 || treeIdx + 1 >= paths.size) return ""
        val treeDocId = Uri.decode(paths[treeIdx + 1])
        val afterColon = treeDocId.substringAfter(':')
        val afterSlash = afterColon.substringAfterLast('/')
        afterSlash.ifBlank { treeDocId.substringAfterLast(':').ifBlank { treeDocId } }
    } catch (_: Exception) {
        ""
    }
}
