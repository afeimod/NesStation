package com.nesstation.app.ui.library

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.Settings as AndroidSettings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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

import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items as lazyItems

import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Clear
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Surface
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.core.content.ContextCompat
import com.nesstation.app.core.model.GameEntry
import com.nesstation.app.core.model.GamePlatform
import com.nesstation.app.core.storage.ArcadeTitleMapper
import com.nesstation.app.core.storage.JavaGameSettings
import com.nesstation.app.core.storage.JavaGameSettingsStore
import com.nesstation.app.core.storage.JavaGameStore
import com.nesstation.app.core.storage.PadLayoutStore
import com.nesstation.app.core.storage.RomStore
import com.nesstation.app.ui.components.AppBackgroundState
import com.nesstation.app.ui.components.PixelBackdrop
import com.nesstation.app.ui.fsd.Fsd
import com.nesstation.app.ui.fsd.FsdBackdrop
import com.nesstation.app.ui.fsd.FsdBottomBar
import com.nesstation.app.ui.fsd.FsdBreadcrumb
import com.nesstation.app.ui.fsd.FsdButtonHint
import com.nesstation.app.ui.fsd.FsdButtonHints
import com.nesstation.app.ui.fsd.FsdCoverFlow
import com.nesstation.app.ui.fsd.FsdCounter
import com.nesstation.app.ui.fsd.FsdTitleBanner
import com.nesstation.app.ui.fsd.FsdToolButton
import com.nesstation.app.ui.fsd.FsdTopBar
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import java.io.File

/// ROM file extensions we support (NES, SNES/SFC, GB/GBC/GBA, DOSBox,
/// Arcade, SEGA MD/SMS/GG/SG, Mega-CD).
/// NOTE on .bin: ambiguous (could be SEGA MD cart, arcade ROM, or DOS
/// disk image). It IS in this list so the file picker accepts it; the
/// platform is then resolved by detectPlatformFromUri — bare .bin files
/// (not inside a zip) default to MD (most common usage in user libraries).
/// Inside a zip, .bin is treated as arcade content (see ARCADE_ROM_EXTENSIONS).
val ROM_EXTENSIONS = listOf(
    "nes", "fds", "unf", "unif", "nez", "unh",  // NES/Famicom
    "smc", "sfc", "swc", "fig", "bs",            // SNES/SFC
    "gb", "sgb", "gbc", "gba",                    // GB/GBC/GBA
    "dosz",                                          // DOSBox-Pure bundle
    // CD images — used by BOTH DOSBox (DOS CD games) and Mega-CD.
    // Platform disambiguation uses the user's selected tab as a hint.
    "iso", "cue", "img", "ccd", "sub",
    // SEGA Mega Drive / Genesis / Master System / Game Gear / SG-1000
    "md", "smd", "gen", "sms", "gg", "sg", "68k",
    "bin",                                           // MD cart dump (ambiguous; see note above)
    "chd",                                           // Mega-CD CHD images
    // Geargrafx — PC-Engine / TurboGrafx-16 / SuperGrafx / PCE-CD
    "pce", "sgx", "hes",
    // Nintendo DS (melonDS)
    "nds", "app", "ids", "srl", "dsi",
    // PlayStation 1 (PCSX-ReARMed)
    "pbp", "m3u", "ecm", "mdf", "mds",
    // Arcade (FBNeo) — archives only; the filename IS the driver name
    "zip", "7z", "gz"
)

/// DOSBox launcher extensions (only these are imported as game entries when
/// a user picks a folder — data files like .DAT, .CFG, .PIC etc. are skipped).
val DOS_LAUNCHER_EXTENSIONS = setOf("bat", "exe", "com")

/// Preferred DOS launcher filenames in priority order. When a folder contains
/// multiple launchers, the first matching file (case-insensitive) is imported.
val DOS_LAUNCHER_PRIORITY = listOf(
    "play.bat", "run.bat", "start.bat", "autoexec.bat",
    "go.bat", "launch.bat", "main.bat",
    "play.exe", "run.exe", "start.exe", "setup.exe",
    "game.exe", "main.exe", "launch.exe"
)

@Composable
fun LibraryScreen(
    games: List<GameEntry>,
    onOpenGame: (GameEntry) -> Unit,
    onBack: () -> Unit = {},
    onHome: () -> Unit = onBack,
    onGamesChanged: (() -> Unit)? = null,
    initialPlatform: GamePlatform? = null,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    // 关键修复：之前的实现里 importedGames 和外部传入的 games 参数都从 RomStore.loadAll 加载，
    //          然后 allGames = importedGames + games 把同一批数据拼了两份，导致每个游戏显示两次。
    //          现在：把传入的 [games] 当作初次数据，导入/刷新时直接覆盖 importedGames，
    //          列表用 importedGames.distinctBy { it.id } 显示，确保不会重复。
    val importedGames = remember { mutableStateListOf<GameEntry>().apply { addAll(games) } }
    var showPermissionDialog by remember { mutableStateOf(false) }
    var dialogMsg by remember { mutableStateOf<String?>(null) }
    // Built-in file browser dialog state — shown as a fallback when the
    // system SAF picker is unavailable (typical on Android TV boxes that
    // ship without DocumentsUI).
    var showFileBrowser by remember { mutableStateOf(false) }

    // 选中的平台分类标签（NES / Java）
    var selectedPlatform by remember { mutableStateOf(initialPlatform ?: GamePlatform.NES) }

    // 搜索关键字 — 空字符串表示不搜索，显示当前平台所有游戏
    var searchQuery by remember { mutableStateOf("") }
    // 搜索框展开状态 — 默认收起，点击搜索 pill 按钮才展开
    var showSearch by remember { mutableStateOf(false) }

    // 长按菜单相关状态
    var longPressGame by remember { mutableStateOf<GameEntry?>(null) }
    // J2ME 长按「游戏设置」：打开每游戏专属设置弹窗（分辨率/缩放/帧率/触摸/
    // 透明度等，独立保存，不影响其他 Java 游戏）
    var pendingJavaSettingsGame by remember { mutableStateOf<GameEntry?>(null) }
    var pendingIconGame by remember { mutableStateOf<GameEntry?>(null) }
    var pendingDeleteGame by remember { mutableStateOf<GameEntry?>(null) }
    var pendingRenameGame by remember { mutableStateOf<GameEntry?>(null) }

    // 刷新/重扫协程作用域。refreshList() 的文件夹重扫涉及 SAF 逐层 query +
    // 每个新文件的标题读取（含大量 CHD 镜像的目录尤其明显），整体搬到 IO
    // 线程，避免按钮/回调在主线程同步扫描导致列表刷新卡顿。
    val scope = rememberCoroutineScope()

    fun refreshList(postMessage: String? = null) {
        scope.launch(Dispatchers.IO) {
        // 1) Re-scan every folder the user has imported games from, to pick up
        //    newly added ROMs and remove ROMs that have been deleted from disk.
        //    Previously only the LAST imported folder was re-scanned, and only
        //    when the scan returned non-empty results, so:
        //      - ROMs added/deleted in folders imported earlier were never seen
        //      - deleting ALL ROMs in a folder left stale entries in the list
        //    Now every imported folder is re-scanned; SAF entries are removed
        //    when the folder no longer lists them (an empty result from a
        //    successful scan means the folder is genuinely empty), and local
        //    entries are removed when their file no longer exists or the
        //    folder itself is gone.
        var stepAdded = 0
        var stepRemoved = 0
        var lostFolderAccess = false
        val folders = RomStore.getImportedFolders(context)
        folders.forEach { (folderUriStr, hintPlatform) ->
            if (folderUriStr.startsWith("content://")) {
                try {
                    val folderUri = Uri.parse(folderUriStr)
                    // If this throws SecurityException the persistable URI
                    // permission was revoked (e.g. user cleared app data);
                    // we skip this folder instead of deleting its games.
                    val romFiles = scanUriForRomsRecursive(context, folderUri, folderUri, maxDepth = 5)

                    // Scan succeeded — even an empty list means the folder has
                    // no ROM files right now, so entries no longer listed here
                    // are safe to remove.
                    val foundUris = romFiles.map { it.second.toString() }.toMutableSet()

                    val existing = RomStore.loadAll(context)
                    val folderTreePrefix = run {
                        // content://com.android.externalstorage.documents/tree/primary%3AROMs%2Fsms/document/primary%3AROMs%2Fsms%2F...
                        // Match anything that starts with the tree prefix:
                        // "content://com.android.externalstorage.documents/tree/primary%3AROMs%2Fsms"
                        val s = folderUri.toString()
                        // Strip trailing "/document/..." if present
                        val docIdx = s.indexOf("/document/")
                        if (docIdx > 0) s.substring(0, docIdx) else s
                    }
                    // Boundary-safe prefix match: only games under THIS folder
                    // tree (e.g. ".../tree/primary%3AROMs/document/...") count.
                    // A sibling folder ".../tree/primary%3AROMs2/document/..."
                    // must NOT match — otherwise we'd delete the wrong games.
                    fun isUnderFolder(uriStr: String): Boolean =
                        uriStr == folderTreePrefix || uriStr.startsWith("$folderTreePrefix/document/")

                    val toRemove = existing.filter { game ->
                        val p = game.romPath ?: return@filter false
                        if (!p.startsWith("content://")) return@filter false
                        if (!isUnderFolder(p)) return@filter false
                        p !in foundUris
                    }
                    if (toRemove.isNotEmpty()) {
                        toRemove.forEach { RomStore.remove(context, it.id) }
                        stepRemoved += toRemove.size
                    }

                    // Add new ROMs found in the folder that aren't yet in the library
                    val existingPaths = RomStore.loadAll(context).mapNotNull { it.romPath }.toSet()
                    var added = 0
                    romFiles.forEach { (name, fileUri) ->
                        val uriStr = fileUri.toString()
                        if (uriStr !in existingPaths) {
                            try {
                                val platform = detectPlatformFromUri(context, fileUri, name, hintPlatform = hintPlatform)
                                val title = when (platform) {
                                    GamePlatform.ARCADE -> ArcadeTitleMapper.resolveDisplayTitle(name)
                                    GamePlatform.PSX -> {
                                        com.nesstation.app.core.storage.PsxTitleExtractor
                                            .extractTitle(context, uriStr)
                                            ?: name.substringBeforeLast('.')
                                    }
                                    else -> name.substringBeforeLast('.')
                                }
                                RomStore.add(context, title, uriStr, platform)
                                added++
                            } catch (_: Exception) { }
                        }
                    }
                    stepAdded += added
                } catch (_: SecurityException) {
                    // Persistable URI permission lost — we can't verify the
                    // folder's contents, so skip it without deleting anything.
                    lostFolderAccess = true
                } catch (_: Exception) {
                    // Any other scan failure — skip rather than deleting games
                    // based on an incomplete scan.
                }
            } else {
                // === FIX: local filesystem folder re-scan ===
                // RomStore.setLastImportFolder() was called with a plain path
                // (e.g. "/sdcard/ROMs/sms") by FileBrowserDialog. Re-walk the
                // folder, remove games whose files are gone, and add new ones.
                try {
                    val folder = java.io.File(folderUriStr)
                    if (!folder.exists() || !folder.isDirectory) {
                        // The imported folder itself is gone — remove every
                        // game that lived under it.
                        val folderAbs = folder.absolutePath
                        val existing = RomStore.loadAll(context)
                        val toRemove = existing.filter { game ->
                            val p = game.romPath ?: return@filter false
                            p.startsWith("/") && (p == folderAbs || p.startsWith("$folderAbs/"))
                        }
                        if (toRemove.isNotEmpty()) {
                            toRemove.forEach { RomStore.remove(context, it.id) }
                            stepRemoved += toRemove.size
                        }
                    } else {
                        val romFiles = scanLocalFolderForRoms(folder, maxDepth = 5)
                        val folderAbs = folder.absolutePath
                        val existing = RomStore.loadAll(context)

                        // Remove games under this folder whose file no longer
                        // exists on disk. File.exists() is exact, so this works
                        // even when ALL ROMs were deleted (an empty scan result
                        // used to block removal entirely).
                        //
                        // === FIX: same scoped-storage guard as below — if the
                        // folder itself can't be read (no "All files access" on
                        // Android 11+), File.exists() returns false for files
                        // that are actually still there, so only remove entries
                        // when the parent folder is readable.
                        val toRemove = existing.filter { game ->
                            val p = game.romPath ?: return@filter false
                            if (!p.startsWith("/")) return@filter false
                            if (p != folderAbs && !p.startsWith("$folderAbs/")) return@filter false
                            val f = java.io.File(p)
                            if (f.exists()) return@filter false
                            val parent = f.parentFile
                            parent != null && parent.canRead()
                        }
                        if (toRemove.isNotEmpty()) {
                            toRemove.forEach { RomStore.remove(context, it.id) }
                            stepRemoved += toRemove.size
                        }

                        // Add new ROMs
                        val existingPaths = RomStore.loadAll(context).mapNotNull { it.romPath }.toSet()
                        var added = 0
                        romFiles.forEach { file ->
                            val path = file.absolutePath
                            if (path !in existingPaths) {
                                try {
                                    val platform = detectPlatformFromFile(file, hintPlatform = hintPlatform)
                                    val title = when (platform) {
                                        GamePlatform.ARCADE -> ArcadeTitleMapper.resolveDisplayTitle(file.name)
                                        GamePlatform.PSX -> {
                                            com.nesstation.app.core.storage.PsxTitleExtractor
                                                .extractTitle(context, path)
                                                ?: file.nameWithoutExtension
                                        }
                                        else -> file.nameWithoutExtension
                                    }
                                    RomStore.add(context, title, path, platform)
                                    added++
                                } catch (_: Exception) { }
                            }
                        }
                        stepAdded += added
                    }
                } catch (_: Exception) {
                    // Skip failed folders rather than removing entries.
                }
            }
        }

        // 2) Load everything back from RomStore (this picks up the changes above
        //    plus filters out any games whose local files no longer exist).
        val nes = RomStore.loadAll(context)
        val java = JavaGameStore.loadAll(context)

        val finalNes = nes

        // Filter out games whose ROM file no longer exists.
        //
        // === FIX: don't delete games merely because File.exists() returned
        // false === On Android 11+ WITHOUT "All files access", File.exists()
        // can return false for paths under shared storage even though the file
        // is still there — the app simply can't see it. Only treat a local
        // ROM as deleted when its parent directory is readable (meaning the
        // scan has permission to know it's really gone).
        val validNes = finalNes.filter { game ->
            val path = game.romPath ?: ""
            if (path.startsWith("content://")) return@filter true
            if (!path.startsWith("/")) return@filter false
            val f = File(path)
            if (f.exists()) return@filter true
            // File not visible. If the parent dir can't be read (permission
            // scoping), keep the entry instead of deleting it.
            val parent = f.parentFile
            parent != null && parent.canRead()
        }
        // If any games were removed, persist the updated list
        if (validNes.size != finalNes.size) {
            RomStore.saveAll(context, validNes)
        }

        val removedCount = finalNes.size - validNes.size

        // Combined summary from step 1 (imported folders). The "已是最新"
        // message is only shown when every imported folder was scanned
        // successfully and nothing changed.
        val totalAdded = stepAdded
        val totalRemoved = stepRemoved + removedCount
        if (totalAdded > 0 || totalRemoved > 0) {
            dialogMsg = postMessage ?: "刷新完成：新增 $totalAdded 个，移除 $totalRemoved 个"
        } else if (lostFolderAccess) {
            dialogMsg = postMessage ?: "需要重新选择文件夹（之前的访问权限已失效）"
        } else {
            dialogMsg = postMessage ?: "已是最新（无新增/移除）"
        }

        val merged = (validNes + java).distinctBy { it.id }
        importedGames.clear()
        importedGames.addAll(merged)

        // Notify the parent (NesApp) so the Home screen / other Library
        // instances reload the latest list — otherwise the refreshed list
        // is replaced by stale data when navigating back.
        onGamesChanged?.invoke()
        }
    }

    // 当外部传入的 games 列表变化时（父级 NavHost 在 ON_RESUME 时重新加载），
    // 同步到本地列表，保留本地可能的新增（避免和远端并发写入时丢数据）
    LaunchedEffect(games) {
        // 以本地列表为基底合并外部列表：id 未出现的新增；id 已存在但
        // 图标/封面/标题字段与本地不同的一律采用外部版本 —— 外部数据来自
        // RomStore 全量重读，代表最新落盘状态（例如刚设置的自定义图标），
        // 旧的 distinctBy 首见优先会把过期条目留在列表里导致图标不刷新。
        var changed = importedGames.size != games.size
        val byId = LinkedHashMap<String, GameEntry>()
        importedGames.forEach { byId[it.id] = it }
        games.forEach { ext ->
            val local = byId[ext.id]
            if (local == null) {
                byId[ext.id] = ext
                changed = true
            } else if (local.customIconPath != ext.customIconPath ||
                       local.coverPath != ext.coverPath ||
                       local.customTitle != ext.customTitle ||
                       local.title != ext.title) {
                byId[ext.id] = ext
                changed = true
            }
        }
        if (changed) {
            importedGames.clear()
            importedGames.addAll(byId.values)
        }
    }

    // SAF file picker for importing individual ROM files
    val filePickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult

        // === Deduplicate multi-file CD images ===
        // When the user selects multiple files from the same game folder
        // (e.g. game.cue + game.bin + game.iso), only import the launch
        // file (.cue if present, otherwise .ccd, otherwise keep .iso/.chd
        // as single-file formats). Skip companion files (.bin/.img/.sub).
        // This matches the folder-scan behavior in scanUriForRomsRecursive
        // and prevents the user's reported bug of "3 entries per MD CD game".
        data class PickedFile(val uri: android.net.Uri, val name: String, val ext: String)
        val picked = mutableListOf<PickedFile>()
        uris.forEach { u ->
            val n = queryDisplayName(u) ?: return@forEach
            val e = n.substringAfterLast('.', "").lowercase()
            if (e in ROM_EXTENSIONS) picked.add(PickedFile(u, n, e))
        }
        val pickedExts = picked.map { it.ext }.toSet()
        val hasCue = "cue" in pickedExts
        val hasCcd = "ccd" in pickedExts
        // .bin is only skipped if we have a .cue (it's a CD data track).
        // Without .cue, a .bin is likely a SEGA MD cart dump — keep it.
        val skipIfCue = setOf("img", "bin", "ccd", "sub", "iso")
        val skipIfCcd = setOf("img", "sub")
        val filtered = picked.filter { c ->
            if (c.ext == "sub") return@filter false  // .sub is always a companion
            if (hasCue && c.ext in skipIfCue) return@filter false
            if (!hasCue && hasCcd && c.ext in skipIfCcd) return@filter false
            true
        }

        var count = 0
        var skipped = picked.size - filtered.size
        filtered.forEach { pf ->
            try {
                context.contentResolver.takePersistableUriPermission(
                    pf.uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (_: Exception) { }
            // Pass the user's selected platform tab as a hint so that
            // ambiguous CD-image extensions (.cue/.img/.iso/.ccd/.sub)
            // are resolved in favor of the user's intent. Without a hint,
            // SEGA-CD games would default to DOS.
            val platform = detectPlatformFromUri(context, pf.uri, pf.name, hintPlatform = selectedPlatform)
            // 街机游戏使用中文名映射（kof98h → 拳皇98 - ...）
            // PSX游戏从ISO/CUE头提取真实游戏名
            val title = when (platform) {
                GamePlatform.ARCADE -> ArcadeTitleMapper.resolveDisplayTitle(pf.name)
                GamePlatform.PSX -> {
                    com.nesstation.app.core.storage.PsxTitleExtractor
                        .extractTitle(context, pf.uri.toString())
                        ?: pf.name.substringBeforeLast('.')
                }
                else -> pf.name.substringBeforeLast('.')
            }
            RomStore.add(context, title, pf.uri.toString(), platform)
            count++
        }
        if (count > 0) {
            refreshList(
                postMessage = if (skipped > 0) {
                    "已导入 $count 个ROM文件（自动跳过 $skipped 个CD附属文件）"
                } else {
                    "已导入 $count 个ROM文件"
                }
            )
        }
    }

    // SAF folder picker — recursively scan selected folder
    val folderPickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        // Wrap the whole callback in a try-catch: on TV (and some phone ROMs)
        // the persistable URI permission can fail silently and the subsequent
        // contentResolver queries may throw SecurityException. We must not
        // crash — show a friendly message instead.
        try {
            try {
                context.contentResolver.takePersistableUriPermission(
                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (_: SecurityException) { }
            catch (_: Exception) { }

            // === DOSBox folder import ===
            // When the user is on the DOS platform tab and picks a folder, we
            // only import the executable launcher file (play.bat / run.bat /
            // START.BAT / setup.exe / ...). Data files in the same folder are
            // left untouched — dosbox_pure reads them via its own VFS at run
            // time using the launcher's parent directory as the working dir.
            if (selectedPlatform == GamePlatform.DOS) {
                val launcherUri = findDosLauncherInSafTree(context, uri)
                if (launcherUri == null) {
                    dialogMsg = "所选文件夹未找到 DOS 启动文件（支持 .bat / .exe / .com）\n" +
                                "建议命名：play.bat / run.bat / START.BAT"
                } else {
                    val launcherName = queryDisplayName(launcherUri) ?: "dos_game.bat"
                    val execName = launcherName.substringBeforeLast('.')
                    // Build title as "folderName(execName)" — e.g. folder "pal"
                    // + launcher "play.bat" → "pal(play)". This makes it easy to
                    // distinguish multiple games that share the same launcher name
                    // (e.g. several games each with their own play.bat).
                    val folderName = extractFolderNameFromTreeUri(uri)
                    val title = if (folderName.isNotEmpty()) {
                        "$folderName($execName)"
                    } else {
                        execName
                    }
                    RomStore.add(context, title, launcherUri.toString(), GamePlatform.DOS)
                    refreshList(postMessage = "已导入 DOS 游戏：$title")
                }
                return@rememberLauncherForActivityResult
            }

            // Recursively scan the selected folder for ROM files
            val romFiles = scanUriForRomsRecursive(context, uri, uri, maxDepth = 5)
            if (romFiles.isEmpty()) {
                dialogMsg = "所选文件夹未找到ROM文件（支持 .nes .smc .sfc .gb .gbc .gba .fds .md .smd .gen .sms .gg .sg .zip .7z .dosz .cue .chd）"
            } else {
                // Save the folder URI so the Refresh button can re-scan it
                // later (without re-asking the user to pick the folder again).
                RomStore.setLastImportFolder(context, uri.toString(), selectedPlatform)
                var count = 0
                var failed = 0
                romFiles.forEach { (name, fileUri) ->
                    try {
                        val platform = detectPlatformFromUri(context, fileUri, name, hintPlatform = selectedPlatform)
                        // 街机游戏使用中文名映射
                        // PSX游戏从ISO/CUE头提取真实游戏名
                        val title = when (platform) {
                            GamePlatform.ARCADE -> ArcadeTitleMapper.resolveDisplayTitle(name)
                            GamePlatform.PSX -> {
                                com.nesstation.app.core.storage.PsxTitleExtractor
                                    .extractTitle(context, fileUri.toString())
                                    ?: name.substringBeforeLast('.')
                            }
                            else -> name.substringBeforeLast('.')
                        }
                        RomStore.add(context, title, fileUri.toString(), platform)
                        count++
                    } catch (_: Exception) {
                        failed++
                    }
                }
                refreshList(
                    postMessage = if (failed > 0)
                        "从文件夹导入 $count 个ROM文件（$failed 个失败）"
                    else "从文件夹导入 $count 个ROM文件"
                )
            }
        } catch (e: SecurityException) {
            dialogMsg = "没有权限访问所选文件夹，请重试或选择其他文件夹"
        } catch (e: Exception) {
            dialogMsg = "导入文件夹失败：${e.message}"
        }
    }

    // Storage permission launcher (Android <= 10)
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        if (result.values.any { it }) {
            val entries = scanForRoms(context)
            if (entries.isNotEmpty()) {
                entries.forEach { (name, path) ->
                    val platform = detectPlatformFromFile(File(path), hintPlatform = selectedPlatform)
                    val title = when (platform) {
                        GamePlatform.ARCADE -> ArcadeTitleMapper.resolveDisplayTitle(name)
                        GamePlatform.PSX -> {
                            com.nesstation.app.core.storage.PsxTitleExtractor
                                .extractTitle(context, path)
                                ?: name.substringBeforeLast('.')
                        }
                        else -> name.substringBeforeLast('.')
                    }
                    RomStore.add(context, title, path, platform)
                }
                refreshList(postMessage = "权限已授予，扫描到 ${entries.size} 个ROM文件")
            } else {
                dialogMsg = "权限已授予，但未在常见目录找到ROM文件"
            }
        } else {
            showPermissionDialog = true
        }
    }

    // SAF picker for installing J2ME .jar games (Java platform tab)
    val jarPickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        var installed = 0
        uris.forEach { uri ->
            try {
                context.contentResolver.takePersistableUriPermission(
                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (_: Exception) { }
            if (JavaGameStore.installJar(context, uri) != null) installed++
        }
        refreshList(
            postMessage = if (installed > 0) "已安装 $installed 个 Java 游戏"
            else "安装失败，请检查 JAR 文件是否有效"
        )
    }

    // SAF picker for choosing a custom cover icon for a game
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
            // 拷贝到应用内部目录，使 BitmapFactory.decodeFile 可用
            val iconsDir = File(context.filesDir, "icons").apply { mkdirs() }
            val iconFile = File(iconsDir, "icon_${game.id}_${System.currentTimeMillis()}.png")
            context.contentResolver.openInputStream(uri)?.use { input ->
                iconFile.outputStream().use { output -> input.copyTo(output) }
            }
            RomStore.setCustomIcon(context, game.id, iconFile.absolutePath)
            refreshList(postMessage = "已设置自定义图标")
        } catch (e: Exception) {
            dialogMsg = "图标设置失败：${e.message}"
        }
        pendingIconGame = null
    }

    fun importFiles() {
        // SAF file picker works without storage permission on all Android versions.
        // Wrap in try-catch: on some TV devices the DocumentsUI activity may
        // not be available — in that case, fall back to the built-in browser.
        try {
            filePickerLauncher.launch(arrayOf("*/*"))
        } catch (_: android.content.ActivityNotFoundException) {
            showFileBrowser = true
        } catch (e: Exception) {
            dialogMsg = "无法打开文件选择器：${e.message}"
        }
    }

    fun importFolder() {
        // Same defensive wrapping as importFiles() — TV devices may not have
        // a DocumentsUI that handles ACTION_OPEN_DOCUMENT_TREE. When SAF is
        // unavailable, fall back to the built-in FileBrowserDialog which can
        // walk the file system directly (requires READ_EXTERNAL_STORAGE on
        // Android <= 10, or MANAGE_EXTERNAL_STORAGE on Android 11+).
        try {
            folderPickerLauncher.launch(null)
        } catch (_: android.content.ActivityNotFoundException) {
            // No system folder picker — use the built-in browser instead.
            showFileBrowser = true
        } catch (e: Exception) {
            dialogMsg = "无法打开文件夹选择器：${e.message}"
        }
    }

    fun requestManageStorage() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                try {
                    val intent = Intent(AndroidSettings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                        data = Uri.parse("package:${context.packageName}")
                    }
                    context.startActivity(intent)
                } catch (_: Exception) {
                    val intent = Intent(AndroidSettings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                    context.startActivity(intent)
                }
            } else {
                val entries = scanForRoms(context)
                if (entries.isNotEmpty()) {
                    entries.forEach { (name, path) ->
                        val platform = detectPlatformFromFile(File(path), hintPlatform = selectedPlatform)
                        RomStore.add(context, name.substringBeforeLast('.'), path, platform)
                    }
                    refreshList(postMessage = "已扫描到 ${entries.size} 个ROM文件")
                } else {
                    dialogMsg = "未在常见目录找到ROM文件"
                }
            }
        } else {
            permissionLauncher.launch(arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE))
        }
    }

    val isPortrait = LocalConfiguration.current.orientation == Configuration.ORIENTATION_PORTRAIT
    val allGames = importedGames.distinctBy { it.id }
    val platformGames = allGames.filter { it.platform == selectedPlatform }
    // When searching, search across ALL platforms for better discoverability
    val displayGames = if (searchQuery.isBlank()) {
        platformGames
    } else {
        allGames.filter { it.title.contains(searchQuery, ignoreCase = true) ||
                            (it.customTitle?.contains(searchQuery, ignoreCase = true) ?: false) }
    }

    // === FSD 封面流 UI（Xbox 360 Freestyle Dash 风格）===

    // 封面位图缓存 — 同一游戏封面被「封面主体 + 倒影」两处组合使用，
    // 在此统一解码一次，避免重复 IO。
    val coverCache = remember { HashMap<String, android.graphics.Bitmap>() }

    // 封面流选中索引
    var selectedIndex by remember { mutableStateOf(0) }
    // 切换平台 / 修改搜索词时回到第一个
    LaunchedEffect(selectedPlatform, searchQuery) { selectedIndex = 0 }
    // 列表变化（导入/删除）后收敛到有效范围
    LaunchedEffect(displayGames.size) {
        if (selectedIndex >= displayGames.size) selectedIndex = 0
    }

    val selIdx = selectedIndex.coerceIn(0, (displayGames.size - 1).coerceAtLeast(0))
    val curGame = displayGames.getOrNull(selIdx)

    Box(
        modifier = modifier
            .fillMaxSize()
            .onPreviewKeyEvent { e ->
                // 手柄按键（TV / 蓝牙手柄）：A=启动 B=返回主页 X=搜索 Y=选项
                if (e.type != KeyEventType.KeyUp) return@onPreviewKeyEvent false
                when (e.key) {
                    Key.ButtonA -> { curGame?.let(onOpenGame); true }
                    Key.ButtonB -> { onHome(); true }
                    Key.ButtonX, Key.X -> { showSearch = !showSearch; true }
                    Key.ButtonY, Key.Y -> { curGame?.let { longPressGame = it }; true }
                    else -> false
                }
            }
    ) {
        if (!AppBackgroundState.active) FsdBackdrop()

        Column(modifier = Modifier.fillMaxSize()) {
            FsdTopBar()

            // ===== 工具行：面包屑 + 操作按钮 =====
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(end = 24.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                FsdBreadcrumb(
                    listOf(
                        "游戏库",
                        if (searchQuery.isNotBlank()) "搜索「$searchQuery」"
                        else selectedPlatform.displayName
                    ),
                    modifier = Modifier.weight(1f)
                )
                FsdToolButton(Icons.Rounded.Search, "搜索") { showSearch = !showSearch }
                if (selectedPlatform != GamePlatform.JAVA) {
                    FsdToolButton(Icons.Rounded.Add, "导入ROM") { importFiles() }
                    FsdToolButton(Icons.Rounded.Folder, "导入文件夹") { importFolder() }
                    FsdToolButton(Icons.Rounded.Storage, "本地浏览") { showFileBrowser = true }
                } else {
                    FsdToolButton(Icons.Rounded.Add, "安装JAR") {
                        try {
                            jarPickerLauncher.launch(
                                arrayOf("application/java-archive", "application/java", "*/*")
                            )
                        } catch (_: android.content.ActivityNotFoundException) {
                            dialogMsg = "系统文件选择器不可用"
                        } catch (e: Exception) {
                            dialogMsg = "无法打开文件选择器：${e.message}"
                        }
                    }
                }
                FsdToolButton(Icons.Rounded.Refresh, "刷新") { refreshList() }
                Spacer(Modifier.size(6.dp))
                FsdToolButton(Icons.Rounded.Home, "主页") { onHome() }
            }

            // ===== 平台标签行 =====
            LazyRow(
                contentPadding = PaddingValues(horizontal = 34.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 2.dp)
            ) {
                lazyItems(listOf(
                    GamePlatform.NES, GamePlatform.SFC,
                    GamePlatform.GB, GamePlatform.GBA,
                    GamePlatform.DOS,
                    GamePlatform.ARCADE,
                    GamePlatform.MD,
                    GamePlatform.PCE,
                    GamePlatform.NDS,
                    GamePlatform.PSX,
                    GamePlatform.PS2,
                    GamePlatform.DC,
                    GamePlatform.JAVA
                )) { platform ->
                    FilterChip(
                        text = platform.displayName,
                        selected = selectedPlatform == platform,
                        onClick = { selectedPlatform = platform }
                    )
                }
            }

            // ===== 搜索栏（FSD 深色）=====
            if (showSearch) {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 34.dp, vertical = 4.dp),
                    placeholder = {
                        Text("搜索游戏名称…", fontSize = 13.sp, color = Fsd.BarTextDim)
                    },
                    leadingIcon = {
                        Icon(
                            Icons.Rounded.Search, contentDescription = null,
                            tint = Fsd.BarTextDim, modifier = Modifier.size(18.dp)
                        )
                    },
                    trailingIcon = {
                        IconButton(onClick = {
                            searchQuery = ""
                            showSearch = false
                        }) {
                            Icon(
                                Icons.Rounded.Clear, contentDescription = "清除并收起",
                                tint = Fsd.BarTextDim, modifier = Modifier.size(16.dp)
                            )
                        }
                    },
                    singleLine = true,
                    shape = RoundedCornerShape(10.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Color(0xFF4F8AC4),
                        unfocusedBorderColor = Color.White.copy(alpha = 0.25f),
                        focusedContainerColor = Color.Black.copy(alpha = 0.35f),
                        unfocusedContainerColor = Color.Black.copy(alpha = 0.25f),
                        cursorColor = Color(0xFF4F8AC4),
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White
                    )
                )
            }

            // ===== 权限提示横幅（Android 11+，FSD 深色样式）=====
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !Environment.isExternalStorageManager()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 34.dp, vertical = 2.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(0xFF7A4A00).copy(alpha = 0.55f))
                        .clickable { requestManageStorage() }
                        .padding(horizontal = 12.dp, vertical = 7.dp)
                ) {
                    Text(
                        text = "点击授予「所有文件访问权限」可自动扫描本地ROM；也可使用上方导入按钮。",
                        color = Color(0xFFFFD9A0),
                        fontSize = 11.sp
                    )
                }
            }

            // ===== 封面流主体 =====
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .clipToBounds()
            ) {
                if (displayGames.isEmpty()) {
                    // 空状态
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 34.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Text(
                            if (searchQuery.isNotBlank()) "未找到匹配「$searchQuery」的游戏"
                            else "该平台还没有游戏",
                            color = Fsd.BarText,
                            fontSize = 17.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(Modifier.size(8.dp))
                        Text(
                            if (selectedPlatform == GamePlatform.JAVA) "点击右上角「安装JAR」导入 Java 游戏"
                            else "点击右上角「导入ROM / 导入文件夹」添加游戏",
                            color = Fsd.BarTextDim,
                            fontSize = 13.sp
                        )
                    }
                } else {
                    FsdCoverFlow(
                        count = displayGames.size,
                        selectedIndex = selIdx,
                        onIndexChange = { selectedIndex = it },
                        onItemClick = { idx -> displayGames.getOrNull(idx)?.let(onOpenGame) },
                        onItemLongClick = { idx ->
                            displayGames.getOrNull(idx)?.let { longPressGame = it }
                        },
                        grabFocusOnLaunch = true,
                        modifier = Modifier.fillMaxSize()
                    ) { i ->
                        FsdGameCover(displayGames[i], coverCache)
                    }

                    // 底部左：按键提示
                    FsdButtonHints(
                        hints = listOf(
                            FsdButtonHint("A", "启动", Fsd.BtnA),
                            FsdButtonHint("B", "主页", Fsd.BtnB),
                            FsdButtonHint("Y", "选项", Fsd.BtnY),
                            FsdButtonHint("X", "搜索", Fsd.BtnX)
                        ),
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .padding(start = 34.dp, bottom = 8.dp)
                    )

                    // 底部中：标题横幅（平台 · 游戏名）
                    curGame?.let { g ->
                        val displayTitle = g.customTitle?.takeIf { it.isNotBlank() } ?: g.title
                        FsdTitleBanner(
                            text = "${g.platform.displayName}  $displayTitle",
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .padding(bottom = 38.dp)
                        )
                    }

                    // 右侧：N of M 计数
                    FsdCounter(
                        current = selIdx + 1,
                        total = displayGames.size,
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(end = 34.dp, bottom = 38.dp)
                    )
                }
            }

            FsdBottomBar(status = if (displayGames.isEmpty()) "空" else "游戏库")
        }
    }

    // Permission denied dialog
    if (showPermissionDialog) {
        AlertDialog(
            onDismissRequest = { showPermissionDialog = false },
            title = { Text("需要存储权限") },
            text = { Text("扫描本地ROM文件需要存储权限。\n\n您也可以直接点击「导入ROM」或「导入文件夹」按钮，通过系统文件选择器导入游戏文件，无需存储权限。") },
            confirmButton = {
                TextButton(onClick = {
                    showPermissionDialog = false
                    requestManageStorage()
                }) { Text("去授权") }
            },
            dismissButton = {
                TextButton(onClick = { showPermissionDialog = false }) { Text("取消") }
            }
        )
    }

    // Info dialog
    dialogMsg?.let { msg ->
        AlertDialog(
            onDismissRequest = { dialogMsg = null },
            text = { Text(msg) },
            confirmButton = {
                TextButton(onClick = { dialogMsg = null }) { Text("确定") }
            }
        )
    }

    // Built-in file browser dialog — fallback when the system SAF picker is
    // unavailable (TV devices, custom ROMs without DocumentsUI).
    if (showFileBrowser) {
        FileBrowserDialog(
            onPicked = { folderPath ->
                showFileBrowser = false
                val folder = File(folderPath)

                // === DOSBox folder import — local file system path ===
                if (selectedPlatform == GamePlatform.DOS) {
                    val launcher = findDosLauncherInLocalFolder(folder)
                    if (launcher == null) {
                        dialogMsg = "所选文件夹未找到 DOS 启动文件（支持 .bat / .exe / .com）\n" +
                                    "建议命名：play.bat / run.bat / START.BAT"
                    } else {
                        // Build title as "folderName(execName)" — e.g. folder "pal"
                        // + launcher "play.bat" → "pal(play)".
                        val folderName = folder.name
                        val execName = launcher.nameWithoutExtension
                        val title = "$folderName($execName)"
                        RomStore.add(
                            context,
                            title,
                            launcher.absolutePath,
                            GamePlatform.DOS
                        )
                        refreshList(postMessage = "已导入 DOS 游戏：$title")
                    }
                    return@FileBrowserDialog
                }

                // Recursively scan the chosen folder for ROM files (same logic
                // as the SAF folder picker callback above).
                val romFiles = scanLocalFolderForRoms(folder, maxDepth = 5)
                if (romFiles.isEmpty()) {
                    dialogMsg = "所选文件夹未找到ROM文件（支持 ${ROM_EXTENSIONS.joinToString()}）"
                } else {
                    // === FIX: save the local folder path so the Refresh button
                    // can re-scan it later (previously this branch only added
                    // games to RomStore but never called setLastImportFolder,
                    // so refreshList() had no folder to re-scan and the button
                    // did nothing).
                    RomStore.setLastImportFolder(context, folder.absolutePath, selectedPlatform)
                    var count = 0
                    var failed = 0
                    romFiles.forEach { file ->
                        try {
                            val platform = detectPlatformFromFile(file, hintPlatform = selectedPlatform)
                            RomStore.add(
                                context,
                                file.nameWithoutExtension,
                                file.absolutePath,
                                platform
                            )
                            count++
                        } catch (_: Exception) {
                            failed++
                        }
                    }
                    refreshList(
                        postMessage = if (failed > 0)
                            "从文件夹导入 $count 个ROM文件（$failed 个失败）"
                        else "从文件夹导入 $count 个ROM文件"
                    )
                }
            },
            onDismiss = { showFileBrowser = false }
        )
    }

    // 长按游戏卡片弹出的操作菜单 — 使用自定义 Dialog 确保
    // 即使游戏名过长，所有选项（包括删除）也始终可见/可滚动
    longPressGame?.let { game ->
        Dialog(onDismissRequest = { longPressGame = null }) {
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = Color.White,
                tonalElevation = 6.dp,
                modifier = Modifier.fillMaxWidth(0.88f)
            ) {
                Column(
                    modifier = Modifier
                        .padding(vertical = 8.dp)
                        .heightIn(max = 440.dp)
                ) {
                    // 标题 — 限制1行+省略号，避免占用过多空间
                    Text(
                        text = game.customTitle?.takeIf { it.isNotBlank() } ?: game.title,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 16.sp,
                        color = Color(0xFF1E2A3A),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp, vertical = 12.dp)
                    )

                    // 可滚动的菜单选项列表
                    Column(
                        modifier = Modifier
                            .verticalScroll(rememberScrollState())
                            .weight(1f, fill = false)
                    ) {
                        MenuOption("开始游戏") {
                            longPressGame = null
                            onOpenGame(game)
                        }
                        MenuOption("游戏设置") {
                            longPressGame = null
                            if (game.platform == GamePlatform.JAVA) {
                                // Java 游戏：打开本游戏专属设置（分辨率/缩放/帧率/
                                // 触摸/透明度等，按游戏单独保存，进游戏即生效）。
                                // 旧版只弹 Toast 指引进游戏内设置 —— 现在有真正的
                                // 每游戏设置界面。
                                pendingJavaSettingsGame = game
                            } else {
                                onOpenGame(game)
                            }
                        }
                        MenuOption("自定义图标") {
                            longPressGame = null
                            pendingIconGame = game
                            iconPickerLauncher.launch(arrayOf("image/*"))
                        }
                        MenuOption(if (game.isFavorite) "取消收藏" else "收藏") {
                            longPressGame = null
                            RomStore.toggleFavorite(context, game.id)
                            refreshList()
                        }
                        MenuOption("重命名") {
                            longPressGame = null
                            pendingRenameGame = game
                        }
                        MenuOption("删除游戏", danger = true) {
                            longPressGame = null
                            pendingDeleteGame = game
                        }
                    }

                    // 关闭按钮 — 始终固定在底部
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 4.dp),
                        contentAlignment = Alignment.CenterEnd
                    ) {
                        TextButton(onClick = { longPressGame = null }) { Text("关闭") }
                    }
                }
            }
        }
    }

    // J2ME 每游戏专属设置弹窗（长按卡片「游戏设置」）
    pendingJavaSettingsGame?.let { game ->
        JavaGameSettingsDialog(
            game = game,
            onDismiss = { pendingJavaSettingsGame = null }
        )
    }

    // 重命名弹窗
    pendingRenameGame?.let { game ->
        Dialog(onDismissRequest = { pendingRenameGame = null }) {
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = Color.White,
                tonalElevation = 6.dp,
                modifier = Modifier.fillMaxWidth(0.85f)
            ) {
                Column(
                    modifier = Modifier.padding(20.dp)
                ) {
                    Text("重命名游戏", fontWeight = FontWeight.SemiBold, fontSize = 16.sp,
                        color = Color(0xFF1E2A3A))
                    Text("当前: ${game.customTitle?.takeIf { it.isNotBlank() } ?: game.title}",
                        fontSize = 12.sp, color = Color.Gray)
                    Spacer(Modifier.size(12.dp))
                    var name by remember { mutableStateOf(game.customTitle?.takeIf { it.isNotBlank() } ?: game.title) }
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text("自定义名称") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.size(12.dp))
                    Row {
                        TextButton(onClick = { pendingRenameGame = null }) { Text("取消") }
                        Spacer(Modifier.weight(1f))
                        TextButton(onClick = {
                            RomStore.setCustomTitle(context, game.id, name.trim().takeIf { it.isNotBlank() })
                            pendingRenameGame = null
                            refreshList()
                        }) { Text("保存", color = Color(0xFF1976D2)) }
                    }
                }
            }
        }
    }

    // 删除游戏确认弹窗
    pendingDeleteGame?.let { game ->
        AlertDialog(
            onDismissRequest = { pendingDeleteGame = null },
            title = { Text("删除游戏") },
            text = { Text("确定要删除「${game.customTitle?.takeIf { it.isNotBlank() } ?: game.title}」吗？此操作不可撤销。") },
            confirmButton = {
                TextButton(onClick = {
                    if (game.platform == GamePlatform.JAVA) {
                        JavaGameStore.deleteGame(context, game)
                    } else {
                        RomStore.remove(context, game.id)
                    }
                    // 直接从列表中移除，确保 UI 立即更新
                    // 不调用 refreshList()，因为它会重新扫描文件夹，
                    // 如果 ROM 文件仍在磁盘上会重新添加已删除的游戏
                    importedGames.removeAll { it.id == game.id }
                    pendingDeleteGame = null
                    dialogMsg = "已删除「${game.customTitle?.takeIf { it.isNotBlank() } ?: game.title}」"
                    onGamesChanged?.invoke()
                }) { Text("删除") }
            },
            dismissButton = {
                TextButton(onClick = { pendingDeleteGame = null }) { Text("取消") }
            }
        )
    }
}

/** Query the display name of a URI from the ContentResolver */
private fun queryDisplayName(uri: Uri): String? {
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

/**
 * Recursively scan a SAF folder URI for ROM files using DocumentsContract.
 * Traverses subdirectories up to [maxDepth] levels deep.
 */
private fun scanUriForRomsRecursive(
    context: android.content.Context,
    treeUri: Uri,
    folderUri: Uri,
    maxDepth: Int
): List<Pair<String, Uri>> {
    val results = mutableListOf<Pair<String, Uri>>()
    if (maxDepth <= 0) return results
    // === FIX: do NOT swallow SecurityException here ===
    // Previously the whole body was wrapped in `try { ... } catch (_: Exception) { }`,
    // which silently ate SecurityException when the persistable URI permission
    // had been revoked. As a result refreshList()'s outer catch never saw the
    // SecurityException and the user got no "需要重新选择文件夹" message — the
    // refresh button just did nothing.
    //
    // We now let SecurityException propagate to refreshList(). Other
    // exceptions (e.g. a single malformed cursor row) are still swallowed
    // per-row so a partial scan still returns something useful.
    val folderDocId = if (folderUri == treeUri) {
        DocumentsContract.getTreeDocumentId(treeUri)
    } else {
        DocumentsContract.getDocumentId(folderUri)
    }
    val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, folderDocId)

    // === Two-pass scan to deduplicate multi-file CD images ===
    // A typical Mega-CD / SEGA-CD dump consists of:
    //   game.cue + game.img + game.ccd + game.sub   (4 files!)
    //   OR  game.cue + game.bin + game.sub
    //   OR  game.chd                                 (single file)
    //   OR  game.iso                                 (single file)
    // Previously we imported .cue + .img + .ccd as 3 separate library
    // entries, which cluttered the UI. Now we do a pre-pass to collect
    // all file extensions in the folder, then:
    //   - If the folder has .cue → import only .cue (skip .img/.bin/.ccd/.sub/.iso)
    //   - If the folder has .ccd (no .cue) → import only .ccd (skip .img/.sub)
    //   - .chd and standalone .iso are always imported (single-file formats)
    // This ensures each game appears as ONE entry in the library.
    data class FileEntry(val name: String, val uri: Uri, val ext: String)
    val candidates = mutableListOf<FileEntry>()

    // NOTE: the .query() call itself can throw SecurityException on revoked
    // permissions — that's exactly what we WANT to propagate up so the
    // caller can show the "re-select folder" message.
    context.contentResolver.query(childrenUri, null, null, null, null)?.use { cursor ->
        while (cursor.moveToNext()) {
            try {
                val docId = cursor.getString(cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID))
                val name = cursor.getString(cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME))
                val mimeType = cursor.getString(cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE))

                if (mimeType == DocumentsContract.Document.MIME_TYPE_DIR) {
                    // Recurse into subdirectory
                    val subUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)
                    results.addAll(scanUriForRomsRecursive(context, treeUri, subUri, maxDepth - 1))
                } else {
                    val ext = name.substringAfterLast('.', "").lowercase()
                    if (ext in ROM_EXTENSIONS) {
                        val fileUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)
                        candidates.add(FileEntry(name, fileUri, ext))
                    }
                }
            } catch (_: Exception) {
                // Skip a single bad row but keep scanning the rest
            }
        }
    }

    // Pass 2: decide which candidates to keep based on folder contents.
    // CD companion files that should be skipped if a primary exists.
    val folderExts = candidates.map { it.ext }.toSet()
    val hasCue = "cue" in folderExts
    val hasCcd = "ccd" in folderExts
    // .bin is tricky — it could be a Mega-CD data track OR a SEGA MD
    // cartridge dump. Only skip .bin if we have a .cue (which references
    // it as a CD data track); otherwise keep it (it's likely a cart).
    val skipIfCue = setOf("img", "bin", "ccd", "sub", "iso")
    val skipIfCcd = setOf("img", "sub")

    for (c in candidates) {
        if (hasCue && c.ext in skipIfCue) continue
        if (hasCue.not() && hasCcd && c.ext in skipIfCcd) continue
        // .sub is always a companion file — never import standalone.
        if (c.ext == "sub") continue
        results.add(c.name to c.uri)
    }
    return results
}

/**
 * Recursively scan [folder] for ROM files (matching [ROM_EXTENSIONS]) up to
 * [maxDepth] levels deep. Returns a flat list of ROM File objects.
 *
 * Used by the built-in FileBrowserDialog when the system SAF picker is
 * unavailable (TV devices without DocumentsUI).
 */
private fun scanLocalFolderForRoms(folder: File, maxDepth: Int): List<File> {
    val results = mutableListOf<File>()
    if (maxDepth <= 0) return results
    val children = try {
        folder.listFiles() ?: return results
    } catch (_: Exception) {
        return results
    }

    // === Two-pass deduplication (same logic as scanUriForRomsRecursive) ===
    // If folder contains .cue → skip .img/.bin/.ccd/.sub/.iso (CD companions)
    // If folder contains .ccd (no .cue) → skip .img/.sub
    // .sub is always skipped (always a companion file).
    val fileChildren = children.filter { it.isFile && !it.name.startsWith(".") }
    val dirChildren = children.filter { it.isDirectory && !it.name.startsWith(".") }

    val folderExts = fileChildren.map { it.extension.lowercase() }.toSet()
    val hasCue = "cue" in folderExts
    val hasCcd = "ccd" in folderExts
    val skipIfCue = setOf("img", "bin", "ccd", "sub", "iso")
    val skipIfCcd = setOf("img", "sub")

    for (f in fileChildren) {
        val ext = f.extension.lowercase()
        if (ext !in ROM_EXTENSIONS) continue
        if (hasCue && ext in skipIfCue) continue
        if (hasCue.not() && hasCcd && ext in skipIfCcd) continue
        if (ext == "sub") continue
        results.add(f)
    }
    for (d in dirChildren) {
        results.addAll(scanLocalFolderForRoms(d, maxDepth - 1))
    }
    return results
}

/** Scan common directories for ROM files (requires storage permission) */
private fun scanForRoms(context: android.content.Context): List<Pair<String, String>> {
    val results = mutableListOf<Pair<String, String>>()
    val dirs = mutableListOf<File>()
    if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.Q || Environment.isExternalStorageManager()) {
        dirs.add(Environment.getExternalStorageDirectory())
        dirs.add(File(Environment.getExternalStorageDirectory(), "Download"))
        dirs.add(File(Environment.getExternalStorageDirectory(), "ROMs"))
        dirs.add(File(Environment.getExternalStorageDirectory(), "NES"))
        dirs.add(File(Environment.getExternalStorageDirectory(), "Games"))
    }
    dirs.add(context.getExternalFilesDir(null) ?: context.filesDir)
    dirs.add(File(context.filesDir, "roms"))

    dirs.forEach { dir ->
        if (dir.exists() && dir.isDirectory) {
            dir.walkTopDown().take(500).forEach { file ->
                if (file.isFile && file.extension.lowercase() in ROM_EXTENSIONS) {
                    results.add(file.name to file.absolutePath)
                }
            }
        }
    }
    return results
}

/**
 * Detect the game platform from a file URI.
 *
 * 实际逻辑已迁移到 [com.nesstation.app.core.storage.PlatformDetector.detectFromUri]，
 * 这里保留薄壳避免影响 LibraryScreen 的其他调用点。对战平台
 * （BattleMatchScreen）直接用 PlatformDetector，确保和本地游戏库走的是
 * 同一套平台分类逻辑。
 */
private fun detectPlatformFromUri(
    context: android.content.Context,
    uri: Uri,
    fileName: String,
    hintPlatform: GamePlatform? = null
): GamePlatform =
    com.nesstation.app.core.storage.PlatformDetector.detectFromUri(
        context, uri, fileName, hintPlatform
    )

/**
 * Detect the game platform from a local File.
 *
 * 实际逻辑已迁移到 [com.nesstation.app.core.storage.PlatformDetector.detectFromFile]。
 */
private fun detectPlatformFromFile(
    file: File,
    hintPlatform: GamePlatform? = null,
    pathHint: String? = null
): GamePlatform =
    com.nesstation.app.core.storage.PlatformDetector.detectFromFile(
        file, hintPlatform, pathHint
    )

@Composable
private fun FilterChip(text: String, selected: Boolean, onClick: () -> Unit = {}) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(16.dp))
            .background(
                if (selected) Brush.verticalGradient(
                    listOf(Fsd.TileBlueTop, Fsd.TileBlueBottom)
                ) else SolidColor(Color.White.copy(alpha = 0.12f))
            )
            .border(
                width = 1.dp,
                color = if (selected) Color.White.copy(alpha = 0.65f)
                        else Color.White.copy(alpha = 0.22f),
                shape = RoundedCornerShape(16.dp)
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 6.dp)
    ) {
        Text(
            text,
            color = if (selected) Color.White else Fsd.BarTextDim,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold
        )
    }
}

/** 长按菜单中的单个可点击选项 */
@Composable
private fun MenuOption(text: String, danger: Boolean = false, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
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

/**
 * FSD 封面卡片 — 封面流中的单个游戏封面。
 * 优先使用真实封面（自定义图标 / Java MIDlet-Icon / 街机 zip 内预览图），
 * 否则用 [GameIconExtractor.generateFallbackCover] 生成带游戏名首字的
 * 主题色封面。位图通过 [cache] 复用（封面主体与倒影共享一次解码）。
 */
@Composable
private fun FsdGameCover(
    game: GameEntry,
    cache: HashMap<String, android.graphics.Bitmap>
) {
    val context = LocalContext.current
    // 修复：缓存键必须包含图标来源标识。旧实现 remember 键虽然含 customIconPath，
    // 但 cache.getOrPut(game.id) 用固定键取位图 —— 设置新图标后 remember 块重执行
    // 时命中的仍是旧位图，导致"自定义图标不立即刷新，进游戏退游戏才生效"。
    // 现在图标路径变化 → 缓存键变化 → 重新解码；同时移除旧键防止内存累积。
    val cacheKey = "${game.id}|${game.customIconPath ?: ""}|${game.coverPath ?: ""}"
    val staleKey = "${game.id}|"
    val bmp = remember(cacheKey) {
        if (cache.size > 60) cache.clear()
        // 清掉同一游戏旧图标的残留位图
        cache.keys.filter { it.startsWith(staleKey) && it != cacheKey }.forEach { cache.remove(it) }
        cache.getOrPut(cacheKey) {
            var b: android.graphics.Bitmap? = null
            val path = try {
                com.nesstation.app.core.storage.GameIconExtractor.resolveIconPath(context, game)
            } catch (_: Exception) { null }
            if (path != null) {
                try { b = BitmapFactory.decodeFile(path) } catch (_: Exception) { b = null }
            }
            if (b == null) {
                try {
                    b = com.nesstation.app.core.storage.GameIconExtractor
                        .generateFallbackCover(game, 320, 420)
                } catch (_: Exception) {
                    b = android.graphics.Bitmap.createBitmap(
                        4, 4, android.graphics.Bitmap.Config.ARGB_8888
                    )
                }
            }
            b!!
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(10.dp))
            .background(Color(0xFF0D2C55))
            .border(
                width = 2.dp,
                color = Color.White.copy(alpha = 0.35f),
                shape = RoundedCornerShape(10.dp)
            )
    ) {
        Image(
            bitmap = bmp.asImageBitmap(),
            contentDescription = game.customTitle?.takeIf { it.isNotBlank() } ?: game.title,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize()
        )

        // 平台徽标 — 左上角
        FsdPlatformBadge(
            game.platform,
            modifier = Modifier.align(Alignment.TopStart)
        )

        // 底部渐变 + 游戏名（倒影上方的可读性遮罩）
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
            Text(
                text = game.customTitle?.takeIf { it.isNotBlank() } ?: game.title,
                color = Color.White,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/** FSD 平台徽标 — 半透明黑底白字（区别于旧版彩色徽标，更贴近 FSD 质感） */
@Composable
private fun FsdPlatformBadge(platform: GamePlatform, modifier: Modifier = Modifier) {
    val label = when (platform) {
        GamePlatform.NES    -> "FC"
        GamePlatform.SFC    -> "SFC"
        GamePlatform.GB     -> "GB"
        GamePlatform.GBA    -> "GBA"
        GamePlatform.DOS    -> "DOS"
        GamePlatform.ARCADE -> "ARC"
        GamePlatform.MD     -> "MD"
        GamePlatform.PCE    -> "PCE"
        GamePlatform.NDS    -> "NDS"
        GamePlatform.PSX    -> "PSX"
        GamePlatform.PS2    -> "PS2"
        GamePlatform.DC     -> "DC"
        GamePlatform.JAVA   -> "Java"
    }
    Box(
        modifier = modifier
            .padding(6.dp)
            .clip(RoundedCornerShape(5.dp))
            .background(Color.Black.copy(alpha = 0.55f))
            .padding(horizontal = 6.dp, vertical = 2.dp)
    ) {
        Text(
            text = label,
            color = Color.White,
            fontSize = 9.sp,
            fontWeight = FontWeight.Bold
        )
    }
}

// ---------------------------------------------------------------------------
// DOSBox folder-import helpers
// ---------------------------------------------------------------------------

/**
 * Extract the folder display name from a SAF tree URI.
 *
 * SAF tree URIs look like:
 *   content://com.android.externalstorage.documents/tree/primary:Games%2Fpal
 *   content://com.android.externalstorage.documents/tree/msf%3A1234%3BGames%2Fpal
 *
 * The last path segment (after "tree/") is the document ID, URL-encoded.
 * After decoding, it looks like "primary:Games/pal" or "msf:1234;Games/pal".
 * The folder name is the part after the last "/" (or after ":" if no "/").
 *
 * Returns "" if the name cannot be extracted.
 *
 * Chinese folder names are handled transparently — URL decoding produces the
 * original Unicode string.
 */
private fun extractFolderNameFromTreeUri(treeUri: Uri): String {
    return try {
        val paths = treeUri.pathSegments
        val treeIdx = paths.indexOf("tree")
        if (treeIdx < 0 || treeIdx + 1 >= paths.size) return ""
        val treeDocId = android.net.Uri.decode(paths[treeIdx + 1])
        // treeDocId looks like "primary:Games/pal" or "primary:Games%2Fpal" (already decoded)
        // The folder name is the last segment after "/" or ":".
        val afterColon = treeDocId.substringAfter(':')
        val afterSlash = afterColon.substringAfterLast('/')
        afterSlash.ifBlank { treeDocId.substringAfterLast(':').ifBlank { treeDocId } }
    } catch (_: Exception) {
        ""
    }
}

/**
 * Recursively scan a SAF tree folder for DOS launcher files (.bat / .exe / .com)
 * and return the URI of the best launch candidate by priority:
 *   play.bat > run.bat > START.BAT > autoexec.bat > go.bat > launch.bat >
 *   main.bat > play.exe > ... > setup.exe > any .exe > any .com
 *
 * Returns null if no launcher is found.
 *
 * Works with content:// URIs that contain UTF-8 percent-encoded Chinese
 * characters — DocumentsContract handles the encoding transparently.
 */
private fun findDosLauncherInSafTree(
    context: android.content.Context,
    treeUri: Uri
): Uri? {
    data class Candidate(val uri: Uri, val name: String, val isBat: Boolean, val isExe: Boolean)

    val candidates = mutableListOf<Candidate>()

    fun walk(folderUri: Uri) {
        try {
            val folderDocId = if (folderUri == treeUri) {
                DocumentsContract.getTreeDocumentId(treeUri)
            } else {
                DocumentsContract.getDocumentId(folderUri)
            }
            val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, folderDocId)
            context.contentResolver.query(childrenUri, null, null, null, null)?.use { cursor ->
                while (cursor.moveToNext()) {
                    val docId = cursor.getString(cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID))
                    val name = cursor.getString(cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)) ?: continue
                    val mimeType = cursor.getString(cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE))

                    if (mimeType == DocumentsContract.Document.MIME_TYPE_DIR) {
                        val subUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)
                        walk(subUri)
                    } else {
                        val ext = name.substringAfterLast('.', missingDelimiterValue = "").lowercase()
                        if (ext in DOS_LAUNCHER_EXTENSIONS) {
                            val fileUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)
                            candidates.add(Candidate(fileUri, name.lowercase(),
                                isBat = ext == "bat", isExe = ext == "exe"))
                        }
                    }
                }
            }
        } catch (_: Exception) { }
    }
    walk(treeUri)

    if (candidates.isEmpty()) return null

    // Match by priority list (case-insensitive)
    for (preferred in DOS_LAUNCHER_PRIORITY) {
        val match = candidates.firstOrNull { it.name == preferred }
        if (match != null) return match.uri
    }
    // Fallback: prefer .bat, then .exe, then .com
    return candidates.firstOrNull { it.isBat }?.uri
        ?: candidates.firstOrNull { it.isExe }?.uri
        ?: candidates.firstOrNull()?.uri
}

/**
 * Scan a local file system folder for DOS launcher files and return the best
 * launch candidate by priority. Used by the built-in FileBrowserDialog when
 * the system SAF picker is unavailable (TV devices).
 *
 * Chinese folder/file names work transparently — Java's File class is
 * Unicode-native and Android's filesystem is UTF-8.
 */
private fun findDosLauncherInLocalFolder(folder: File): File? {
    if (!folder.exists() || !folder.isDirectory) return null

    val candidates = folder.walkTopDown()
        .filter { it.isFile && it.extension.lowercase() in DOS_LAUNCHER_EXTENSIONS }
        .toList()

    if (candidates.isEmpty()) return null

    for (preferred in DOS_LAUNCHER_PRIORITY) {
        val match = candidates.firstOrNull { it.name.equals(preferred, ignoreCase = true) }
        if (match != null) return match
    }
    return candidates.firstOrNull { it.name.endsWith(".bat", ignoreCase = true) }
        ?: candidates.firstOrNull { it.name.endsWith(".exe", ignoreCase = true) }
        ?: candidates.firstOrNull { it.name.endsWith(".com", ignoreCase = true) }
}

// ===========================================================================
// J2ME 每游戏专属设置弹窗
//
// 长按 Java 游戏卡片 →「游戏设置」：编辑该游戏独立保存的 J2ME 配置
// （分辨率/缩放/帧率/触摸/透明度/输入模式等）。没有专属配置时以全局
// 默认值作为起点，保存后生成专属配置；「恢复全局默认」删除专属配置。
// 配置在下次进入游戏时生效（游戏内设置面板则即时生效）。
// ===========================================================================
@Composable
private fun JavaGameSettingsDialog(
    game: GameEntry,
    onDismiss: () -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val gameKey = remember { JavaGameSettingsStore.gameKey(game.romPath) }
    val hasOverride = remember { JavaGameSettingsStore.has(context, gameKey) }

    // 初始值：优先读专属配置，否则用全局默认
    val initial = remember {
        JavaGameSettingsStore.load(context, gameKey)
            ?: JavaGameSettings.of(PadLayoutStore.load(context))
    }

    var inputMode by remember { mutableStateOf(initial.javaInputMode) }
    var scaleType by remember { mutableStateOf(initial.javaScaleType) }
    var resolution by remember { mutableStateOf(initial.javaResolution) }
    var scaleRatio by remember { mutableStateOf(initial.javaScaleRatio) }
    var fpsLimit by remember { mutableStateOf(initial.javaFpsLimit) }
    var showFps by remember { mutableStateOf(initial.javaShowFps) }
    var immediateMode by remember { mutableStateOf(initial.javaImmediateMode) }
    var touchInput by remember { mutableStateOf(initial.javaTouchInput) }
    var numDualDispatch by remember { mutableStateOf(initial.javaNumDualDispatch) }
    var opacity by remember { mutableStateOf(initial.javaOpacity) }
    // 当前展开的下拉项（null = 全部收起）
    var openDropdown by remember { mutableStateOf<String?>(null) }

    fun snapshot(): JavaGameSettings = JavaGameSettings(
        javaInputMode = inputMode,
        javaScaleType = scaleType,
        javaShowFps = showFps,
        javaImmediateMode = immediateMode,
        javaResolution = resolution,
        javaScaleRatio = scaleRatio,
        javaFpsLimit = fpsLimit,
        javaTouchInput = touchInput,
        javaNumDualDispatch = numDualDispatch,
        javaButtonKeyMap = initial.javaButtonKeyMap,
        javaPhoneGrid = initial.javaPhoneGrid,
        javaPhoneTop = initial.javaPhoneTop,
        javaOpacity = opacity
    )

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = Color(0xFF16212E),
            tonalElevation = 6.dp,
            modifier = Modifier.fillMaxWidth(0.92f)
        ) {
            Column(
                modifier = Modifier
                    .padding(16.dp)
                    .heightIn(max = 560.dp)
            ) {
                // 标题
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("游戏设置 · ${game.title}",
                            color = Color.White, fontSize = 15.sp,
                            fontWeight = FontWeight.Bold, maxLines = 1,
                            overflow = TextOverflow.Ellipsis)
                        Text(
                            if (hasOverride) "本游戏专属设置（独立保存）"
                            else "基于全局默认 · 保存后为本游戏专属",
                            color = if (hasOverride) Color(0xFF7BD88F) else Color(0xFF8899AA),
                            fontSize = 10.sp
                        )
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(androidx.compose.material.icons.Icons.Rounded.Clear, "关闭",
                            tint = Color.White)
                    }
                }
                Spacer(Modifier.size(6.dp))

                Column(
                    modifier = Modifier
                        .weight(1f, fill = false)
                        .verticalScroll(rememberScrollState())
                ) {
                    // --- 输入模式 ---
                    androidx.compose.material3.ListItem(
                        headlineContent = { Text("输入模式", color = Color.White, fontSize = 13.sp) },
                        trailingContent = {
                            Text(
                                if (inputMode == "phone") "手机键盘 ▾" else "手柄布局 ▾",
                                color = Color(0xFFFFD66B), fontSize = 12.sp,
                                modifier = Modifier.clickable {
                                    openDropdown = if (openDropdown == "input") null else "input"
                                }
                            )
                        },
                        colors = androidx.compose.material3.ListItemDefaults.colors(
                            containerColor = Color.Transparent
                        )
                    )
                    if (openDropdown == "input") {
                        listOf("gamepad" to "手柄布局 (方向键 + ABXY)",
                               "phone" to "手机键盘 (数字 + 软键 + 方向)").forEach { (v, label) ->
                            JavaSettingsOptionRow(label, inputMode == v) { inputMode = v; openDropdown = null }
                        }
                    }

                    // --- 屏幕缩放 ---
                    androidx.compose.material3.ListItem(
                        headlineContent = { Text("屏幕缩放", color = Color.White, fontSize = 13.sp) },
                        trailingContent = {
                            Text(
                                when (scaleType) {
                                    "stretch" -> "全屏拉伸 ▾"
                                    "center" -> "原始分辨率 ▾"
                                    else -> "适应屏幕 ▾"
                                },
                                color = Color(0xFFFFD66B), fontSize = 12.sp,
                                modifier = Modifier.clickable {
                                    openDropdown = if (openDropdown == "scale") null else "scale"
                                }
                            )
                        },
                        colors = androidx.compose.material3.ListItemDefaults.colors(
                            containerColor = Color.Transparent
                        )
                    )
                    if (openDropdown == "scale") {
                        listOf("fit" to "适应屏幕 (保持比例，推荐)",
                               "stretch" to "全屏拉伸",
                               "center" to "原始分辨率 (居中)").forEach { (v, label) ->
                            JavaSettingsOptionRow(label, scaleType == v) { scaleType = v; openDropdown = null }
                        }
                    }

                    // --- 游戏分辨率 ---
                    androidx.compose.material3.ListItem(
                        headlineContent = { Text("游戏分辨率", color = Color.White, fontSize = 13.sp) },
                        trailingContent = {
                            Text(
                                (when (resolution) {
                                    "default" -> "默认"
                                    "auto" -> "自动"
                                    else -> resolution.replace("x", "×")
                                }) + " ▾",
                                color = Color(0xFFFFD66B), fontSize = 12.sp,
                                modifier = Modifier.clickable {
                                    openDropdown = if (openDropdown == "res") null else "res"
                                }
                            )
                        },
                        colors = androidx.compose.material3.ListItemDefaults.colors(
                            containerColor = Color.Transparent
                        )
                    )
                    if (openDropdown == "res") {
                        listOf(
                            "default" to "默认 (跟随游戏配置)",
                            "auto" to "自动 (跟随设备屏幕)",
                            "128x128" to "128 × 128",
                            "176x208" to "176 × 208 (S60 经典)",
                            "176x220" to "176 × 220",
                            "208x208" to "208 × 208",
                            "240x320" to "240 × 320 (最常见)",
                            "240x400" to "240 × 400",
                            "320x240" to "320 × 240 (横屏)",
                            "360x640" to "360 × 640",
                            "480x800" to "480 × 800",
                            "640x360" to "640 × 360 (横屏)"
                        ).forEach { (v, label) ->
                            JavaSettingsOptionRow(label, resolution == v) { resolution = v; openDropdown = null }
                        }
                    }

                    // --- 画面缩放比例 ---
                    androidx.compose.material3.ListItem(
                        headlineContent = { Text("画面缩放比例", color = Color.White, fontSize = 13.sp) },
                        trailingContent = {
                            Text("$scaleRatio% ▾", color = Color(0xFFFFD66B), fontSize = 12.sp,
                                modifier = Modifier.clickable {
                                    openDropdown = if (openDropdown == "ratio") null else "ratio"
                                })
                        },
                        colors = androidx.compose.material3.ListItemDefaults.colors(
                            containerColor = Color.Transparent
                        )
                    )
                    if (openDropdown == "ratio") {
                        listOf("25", "50", "75", "100", "125", "150", "175", "200", "300", "400")
                            .forEach { v ->
                                JavaSettingsOptionRow(
                                    if (v == "100") "100% (默认)" else "$v%",
                                    scaleRatio == v
                                ) { scaleRatio = v; openDropdown = null }
                            }
                    }

                    // --- 帧率限制 ---
                    androidx.compose.material3.ListItem(
                        headlineContent = { Text("帧率限制", color = Color.White, fontSize = 13.sp) },
                        trailingContent = {
                            Text(
                                (if (fpsLimit == "0") "不限制" else "$fpsLimit FPS") + " ▾",
                                color = Color(0xFFFFD66B), fontSize = 12.sp,
                                modifier = Modifier.clickable {
                                    openDropdown = if (openDropdown == "fps") null else "fps"
                                }
                            )
                        },
                        colors = androidx.compose.material3.ListItemDefaults.colors(
                            containerColor = Color.Transparent
                        )
                    )
                    if (openDropdown == "fps") {
                        listOf("0" to "不限制 (默认)", "60" to "60 FPS", "50" to "50 FPS",
                               "40" to "40 FPS", "30" to "30 FPS", "25" to "25 FPS",
                               "15" to "15 FPS").forEach { (v, label) ->
                            JavaSettingsOptionRow(label, fpsLimit == v) { fpsLimit = v; openDropdown = null }
                        }
                    }

                    // --- 开关项 ---
                    JavaSettingsSwitchRow("显示 J2ME 帧数", "由 MIDlet 画面内部绘制实时帧率", showFps) { showFps = it }
                    JavaSettingsSwitchRow("即时绘制模式", "提升按键/触摸响应速度，少数游戏需关闭", immediateMode) { immediateMode = it }
                    JavaSettingsSwitchRow("触摸输入支持", "触屏版游戏的触摸操作；关闭强制键盘 UI", touchInput) { touchInput = it }
                    JavaSettingsSwitchRow("数字键兼作方向键", "2/4/6/8/5 同时发送方向/确认键（真机行为）", numDualDispatch) { numDualDispatch = it }

                    // --- 虚拟按键透明度 ---
                    Text("虚拟按键透明度", color = Color.White, fontSize = 13.sp,
                        modifier = Modifier.padding(start = 16.dp, top = 6.dp))
                    Row(verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = 16.dp)) {
                        androidx.compose.material3.Slider(
                            value = opacity.coerceIn(0.3f, 1f),
                            onValueChange = { opacity = it.coerceIn(0.3f, 1f) },
                            valueRange = 0.3f..1f,
                            colors = androidx.compose.material3.SliderDefaults.colors(
                                thumbColor = Color(0xFFFFD66B),
                                activeTrackColor = Color(0xFFFFD66B),
                                inactiveTrackColor = Color(0xFF3A4A5A)
                            ),
                            modifier = Modifier.weight(1f)
                        )
                        Text("${(opacity.coerceIn(0.3f, 1f) * 100).toInt()}%",
                            color = Color(0xFFFFD66B), fontSize = 12.sp,
                            modifier = Modifier.padding(start = 8.dp))
                    }

                    // --- 恢复全局默认 ---
                    if (hasOverride) {
                        TextButton(onClick = {
                            JavaGameSettingsStore.remove(context, gameKey)
                            onDismiss()
                        }) {
                            Text("恢复全局默认设置", color = Color(0xFFFF6B6B), fontSize = 12.sp)
                        }
                    }
                    Spacer(Modifier.size(4.dp))
                    Text(
                        "设置在下次进入游戏时生效；按键映射请进游戏后打开菜单 → 设置",
                        color = Color(0xFF8899AA), fontSize = 10.sp, lineHeight = 14.sp,
                        modifier = Modifier.padding(horizontal = 16.dp)
                    )
                }

                Spacer(Modifier.size(10.dp))
                // 底部操作
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = onDismiss) { Text("取消", color = Color(0xFF8899AA)) }
                    TextButton(onClick = {
                        JavaGameSettingsStore.save(context, gameKey, snapshot())
                        onDismiss()
                    }) {
                        Text("保存", color = Color(0xFFFFD66B), fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

/** 设置弹窗里的单选选项行（右侧打勾）。 */
@Composable
private fun JavaSettingsOptionRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 24.dp, vertical = 8.dp)
    ) {
        Text(
            label,
            color = if (selected) Color(0xFFFFD66B) else Color.White,
            fontSize = 12.sp,
            modifier = Modifier.weight(1f)
        )
        if (selected) Text("✓", color = Color(0xFFFFD66B), fontSize = 13.sp)
    }
}

/** 设置弹窗里的开关行。 */
@Composable
private fun JavaSettingsSwitchRow(
    label: String,
    description: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(label, color = Color.White, fontSize = 13.sp)
            Text(description, color = Color(0xFF8899AA), fontSize = 10.sp, lineHeight = 13.sp)
        }
        androidx.compose.material3.Switch(
            checked = checked,
            onCheckedChange = onChange,
            colors = androidx.compose.material3.SwitchDefaults.colors(
                checkedThumbColor = Color(0xFFFFD66B),
                checkedTrackColor = Color(0xFF5A4A1F)
            )
        )
    }
}
