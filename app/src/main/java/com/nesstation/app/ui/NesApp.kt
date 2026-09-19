package com.nesstation.app.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.nesstation.app.core.model.GameEntry
import com.nesstation.app.core.model.GamePlatform
import com.nesstation.app.core.storage.ArcadeTitleMapper
import com.nesstation.app.core.storage.JavaGameStore
import com.nesstation.app.core.storage.PadLayoutStore
import com.nesstation.app.core.storage.PlatformDetector
import com.nesstation.app.core.storage.RomStore
import com.nesstation.app.ui.emulator.EmulatorScreen
import com.nesstation.app.ui.home.HomeScreen
import com.nesstation.app.ui.library.LibraryScreen
import com.nesstation.app.ui.library.scanForRoms
import com.nesstation.app.ui.settings.KeyMapScreen
import com.nesstation.app.ui.settings.SettingsScreen
import com.nesstation.app.ui.tv.TvHomeScreen
import com.nesstation.app.ui.files.FileListScreen
import com.nesstation.app.ui.swf.SwfListScreen
import com.nesstation.app.ui.swf.SwfPlayerScreen
import com.nesstation.app.ui.about.AboutScreen
import com.nesstation.app.ui.battle.BattleMatchArgs
import com.nesstation.app.ui.battle.BattleMatchScreen
import com.nesstation.app.ui.battle.BattleScreen
import com.nesstation.app.ui.online.OnlineGamesScreen
import com.nesstation.app.ui.online.WebGameScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

object Routes {
    const val HOME = "home"
    // 带可选 platform 查询参数：主页平台磁贴可直接深链到对应平台的封面流
    const val LIBRARY = "library?platform={platform}"
    const val FAVORITES = "favorites"
    const val HISTORY = "history"
    const val SETTINGS = "settings"
    const val KEYMAP = "keymap"
    const val FILE_LIST = "file_list"
    const val SWF_LIST = "swf_list"
    const val ONLINE_GAMES = "online_games"
    const val BATTLE = "battle"
    const val BATTLE_MATCH = "battle_match/{roomId}/{gameId}/{isHost}/{tcpAddr}/{platform}/{fileName}"
    const val WEB_GAME = "web_game/{url}/{uaMode}"
    const val ABOUT = "about"
    const val EMULATOR = "emulator/{gameId}"
    const val SWF_PLAYER = "swf_player/{swfPath}"
    fun library(platform: GamePlatform? = null): String =
        if (platform == null) "library" else "library?platform=${platform.name}"
    fun emulator(id: String) = "emulator/$id"
    fun swfPlayer(path: String) = "swf_player/${java.net.URLEncoder.encode(path, "UTF-8")}"
    fun webGame(url: String, uaMode: String) =
        "web_game/${java.net.URLEncoder.encode(url, "UTF-8")}/$uaMode"
    fun battleMatch(roomId: String, gameId: String, isHost: Boolean, tcpAddr: String, platform: String = "arcade", fileName: String = "") =
        "battle_match/$roomId/$gameId/$isHost/${java.net.URLEncoder.encode(tcpAddr, "UTF-8")}/${java.net.URLEncoder.encode(platform, "UTF-8")}/${java.net.URLEncoder.encode(fileName, "UTF-8")}"
}

@Composable
fun NesApp(nav: androidx.navigation.NavHostController) {
    val ctx = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val isTv = remember {
        ctx.packageManager.hasSystemFeature("android.hardware.touchscreen").not()
    }

    // 同时加载 NES 与 Java 游戏库（按 id 去重）
    fun loadAllGames(ctx: Context): List<GameEntry> {
        val nesGames = RomStore.loadAll(ctx)
        val javaGames = JavaGameStore.loadAll(ctx)
        return (nesGames + javaGames).distinctBy { it.id }
    }

    var games by remember { mutableStateOf(loadAllGames(ctx)) }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                games = loadAllGames(ctx)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val reloadGames: () -> Unit = { games = loadAllGames(ctx) }

    if (isTv) {
        TvNavHost(nav = nav, games = games, reloadGames = reloadGames)
    } else {
        PhoneNavHost(nav = nav, games = games, reloadGames = reloadGames)
    }

    // 首次启动：弹窗引导用户授予存储权限（手机 / TV 通用）
    FirstLaunchStoragePrompt()
}

// ===== 首次启动存储权限引导 =====

private const val APP_PREFS_NAME = "nesstation_app_prefs"
private const val KEY_STORAGE_PERMISSION_PROMPTED = "storage_permission_prompted"

/** 存储权限是否已就绪：Android 11+ 检查“所有文件访问”，更低版本检查 READ_EXTERNAL_STORAGE */
private fun storagePermissionReady(ctx: Context): Boolean =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        Environment.isExternalStorageManager()
    } else {
        ContextCompat.checkSelfPermission(
            ctx, Manifest.permission.READ_EXTERNAL_STORAGE
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
    }

/**
 * 首次启动弹窗：引导用户授予存储权限（手机 / TV 通用）。
 *
 * 只在首次启动时出现一次（SharedPreferences 标记，清除应用数据后重新计数）：
 *  - 用户点「去授权」：Android 11+ 跳转系统“所有文件访问权限”页面；
 *    Android 10 及以下直接弹系统运行时权限对话框。
 *  - 用户点「暂不」或点外部取消：不再自动弹出，之后仍可在游戏库页面
 *    （顶部的权限提示横幅）或系统设置中授权。
 *  - 用户授权成功后：自动扫描常见目录的 ROM 并入库（与游戏库的
 *    「去授权」流程同一套扫描逻辑），Toast 报告扫描结果。
 */
@Composable
private fun FirstLaunchStoragePrompt() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var showPrompt by remember { mutableStateOf(false) }
    // 用户点过「去授权」后：等待权限就绪（从系统授权页/权限弹窗返回时检查）
    var awaitingGrant by remember { mutableStateOf(false) }
    // 防止 ON_RESUME 与权限回调同时触发导致重复扫描
    var scanStarted by remember { mutableStateOf(false) }

    fun scanAndImportOnce() {
        if (scanStarted) return
        scanStarted = true
        scope.launch {
            val added = runScanImport(ctx)
            withContext(Dispatchers.Main) {
                Toast.makeText(
                    ctx,
                    if (added > 0) "已自动扫描到 $added 个游戏文件"
                    else "存储权限已授予，未在常见目录找到游戏文件，可手动「导入ROM」",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    // Android 10 及以下：运行时权限请求（READ + WRITE，WRITE 在 manifest 里 maxSdk=29）
    val runtimeLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        if (result.values.any { it }) scanAndImportOnce()
    }

    // 从系统授权页面返回后：权限就绪则自动扫描一次。
    // 返回前台时 NesApp 的 ON_RESUME 监听会重新 loadAllGames，扫描入库
    // 的 ROM 会直接出现在主页/游戏库，无需手动刷新。
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME && awaitingGrant) {
                if (storagePermissionReady(ctx)) {
                    awaitingGrant = false
                    scanAndImportOnce()
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(Unit) {
        val prefs = ctx.getSharedPreferences(APP_PREFS_NAME, Context.MODE_PRIVATE)
        // 只提示一次：无论用户是否授权，首次启动后都不再自动弹出
        if (prefs.getBoolean(KEY_STORAGE_PERMISSION_PROMPTED, false)) {
            return@LaunchedEffect
        }
        prefs.edit().putBoolean(KEY_STORAGE_PERMISSION_PROMPTED, true).apply()
        // 权限已就绪（如覆盖升级且此前已授权过）则不打扰用户
        if (!storagePermissionReady(ctx)) {
            showPrompt = true
        }
    }

    if (!showPrompt) return

    AlertDialog(
        onDismissRequest = { showPrompt = false },
        title = { Text("需要存储权限") },
        text = {
            Text(
                "NesStation 需要存储权限来扫描本地游戏ROM、读取封面和保存游戏进度。\n\n" +
                    "点击「去授权」并在接下来的页面中允许访问，应用会自动扫描您设备中的游戏文件。\n\n" +
                    "您也可以选择「暂不」，之后通过「导入ROM」按钮或系统文件选择器导入游戏。"
            )
        },
        confirmButton = {
            TextButton(onClick = {
                showPrompt = false
                awaitingGrant = true
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    // Android 11+：跳转系统“所有文件访问权限”页面
                    try {
                        val intent = Intent(
                            android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION
                        ).apply {
                            data = Uri.parse("package:${ctx.packageName}")
                        }
                        ctx.startActivity(intent)
                    } catch (_: Exception) {
                        try {
                            ctx.startActivity(
                                Intent(android.provider.Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                            )
                        } catch (_: Exception) {
                            // 部分 TV 盒子两个 action 都不响应，给出兜底提示
                            awaitingGrant = false
                            Toast.makeText(
                                ctx, "无法打开授权页面，请到系统设置中手动开启存储权限",
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    }
                } else {
                    // Android 10 及以下：READ / WRITE 运行时权限弹窗
                    runtimeLauncher.launch(
                        arrayOf(
                            Manifest.permission.READ_EXTERNAL_STORAGE,
                            Manifest.permission.WRITE_EXTERNAL_STORAGE
                        )
                    )
                }
            }) { Text("去授权") }
        },
        dismissButton = {
            TextButton(onClick = { showPrompt = false }) { Text("暂不") }
        }
    )
}

/**
 * 首次授权成功后的自动扫描：与游戏库「去授权」流程走同一套
 * [scanForRoms] 扫描 + 批量入库逻辑（RomStore.importGames 按 romPath
 * 去重，重复扫描不会产生重复条目）。返回新增的游戏数量。
 *
 * ★ 严格判定（修复"授权后乱七八糟的 zip/apk 全进 arcade/dos/md 列表"）：
 * 用 [PlatformDetector.detectFromFileStrict] 判定平台 —— 只收无歧义
 * 扩展名或探头可验证内容的 zip，识别不出的一律跳过，绝不兜底街机/
 * NES，也不再受平台页/服务端配置影响。
 */
private suspend fun runScanImport(ctx: Context): Int = withContext(Dispatchers.IO) {
    var added = 0
    try {
        val items = mutableListOf<Triple<String, String, GamePlatform>>()
        scanForRoms(ctx).forEach { (name, path) ->
            val platform = PlatformDetector.detectFromFileStrict(File(path))
                ?: return@forEach   // 无法可信识别（乱 zip/apk 等）→ 跳过不入库
            val title = when (platform) {
                GamePlatform.ARCADE -> ArcadeTitleMapper.resolveDisplayTitle(name)
                else -> name.substringBeforeLast('.')
            }
            items.add(Triple(title, path, platform))
        }
        added = RomStore.importGames(ctx, items).size
    } catch (_: Exception) { }
    added
}

@Composable
private fun PhoneNavHost(
    nav: androidx.navigation.NavHostController,
    games: List<GameEntry>,
    reloadGames: () -> Unit
) {
    val ctx = LocalContext.current

    // Apply saved orientation setting on startup
    LaunchedEffect(Unit) {
        val padLayout = PadLayoutStore.load(ctx)
        val activity = ctx as? android.app.Activity ?: return@LaunchedEffect
        activity.requestedOrientation = when (padLayout.screenOrientation) {
            "landscape" -> android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            "portrait" -> android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
            else -> android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR
        }
    }

    // 所有平台（含 J2ME）统一走 EmulatorScreen：J2meEngine 实现了 EmulatorEngine，
    // J2meGameView 在 Compose 内托管 Canvas 视图，不再需要单独的 MicroActivity。
    val openGame: (GameEntry) -> Unit = { game ->
        nav.navigate(Routes.emulator(game.id))
    }

    NavHost(
        navController = nav,
        startDestination = Routes.HOME,
        modifier = Modifier.fillMaxSize()
    ) {
        composable(Routes.HOME) {
            HomeScreen(
                games = games,
                onOpenLibrary = { nav.navigate(Routes.library()) },
                onOpenPlatform = { p -> nav.navigate(Routes.library(p)) },
                onOpenOnlineGames = { nav.navigate(Routes.ONLINE_GAMES) },
                onOpenBattle = { nav.navigate(Routes.BATTLE) },
                onOpenSwf = { nav.navigate(Routes.SWF_LIST) },
                onOpenSettings = { nav.navigate(Routes.SETTINGS) },
                onOpenAbout = { nav.navigate(Routes.ABOUT) },
                onExit = { nav.context.let { (it as? android.app.Activity)?.finishAffinity() } }
            )
        }
        composable(Routes.BATTLE) {
            BattleScreen(
                onBack = { nav.popBackStack() },
                onHome = { nav.popBackStack(Routes.HOME, inclusive = false) },
                onOpenMatch = { args ->
                    nav.navigate(Routes.battleMatch(args.roomId, args.gameId, args.isHost, args.tcpAddr, args.platform.name, args.fileName))
                }
            )
        }
        composable(
            Routes.BATTLE_MATCH,
            arguments = listOf(
                navArgument("roomId") { type = NavType.StringType },
                navArgument("gameId") { type = NavType.StringType },
                navArgument("isHost") { type = NavType.BoolType },
                navArgument("tcpAddr") { type = NavType.StringType },
                navArgument("platform") { type = NavType.StringType },
                navArgument("fileName") { type = NavType.StringType }
            )
        ) { entry ->
            val roomId = entry.arguments?.getString("roomId") ?: ""
            val gameId = entry.arguments?.getString("gameId") ?: ""
            val isHost = entry.arguments?.getBoolean("isHost") ?: false
            val tcpAddr = java.net.URLDecoder.decode(
                entry.arguments?.getString("tcpAddr") ?: "",
                "UTF-8"
            )
            val platformStr = java.net.URLDecoder.decode(
                entry.arguments?.getString("platform") ?: "arcade",
                "UTF-8"
            )
            val fileName = java.net.URLDecoder.decode(
                entry.arguments?.getString("fileName") ?: "",
                "UTF-8"
            )
            BattleMatchScreen(
                args = BattleMatchArgs(
                    roomId = roomId,
                    gameId = gameId,
                    isHost = isHost,
                    tcpAddr = tcpAddr,
                    platform = com.nesstation.app.core.model.GamePlatform.fromString(platformStr),
                    fileName = fileName
                ),
                onExit = {
                    nav.popBackStack(Routes.BATTLE, inclusive = false)
                }
            )
        }
        composable(
            Routes.LIBRARY,
            arguments = listOf(
                navArgument("platform") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                }
            )
        ) { entry ->
            val platformStr = entry.arguments?.getString("platform")
            LibraryScreen(
                games = games,
                onOpenGame = openGame,
                onBack = { nav.popBackStack() },
                onHome = {
                    // 返回主页：弹出到 HOME 路由
                    nav.popBackStack(Routes.HOME, inclusive = false)
                },
                onGamesChanged = reloadGames,
                initialPlatform = platformStr?.takeIf { it.isNotBlank() }
                    ?.let { GamePlatform.fromString(it) }
            )
        }
        composable(Routes.FILE_LIST) {
            FileListScreen(
                onBack = { nav.popBackStack() },
                onOpenSwf = { path -> nav.navigate(Routes.swfPlayer(path)) }
            )
        }
        composable(Routes.SWF_LIST) {
            SwfListScreen(
                onBack = { nav.popBackStack() },
                onHome = {
                    // 返回主页：弹出到 HOME 路由为止，确保按返回键不会回到 SWF 列表
                    nav.popBackStack(Routes.HOME, inclusive = false)
                },
                onOpenSwf = { path -> nav.navigate(Routes.swfPlayer(path)) }
            )
        }
        composable(Routes.ONLINE_GAMES) {
            OnlineGamesScreen(
                onBack = { nav.popBackStack() },
                onHome = {
                    // 返回主页：弹出到 HOME 路由
                    nav.popBackStack(Routes.HOME, inclusive = false)
                },
                onOpenGame = { game ->
                    nav.navigate(Routes.webGame(game.url, game.uaMode))
                }
            )
        }
        composable(
            Routes.WEB_GAME,
            arguments = listOf(
                navArgument("url") { type = NavType.StringType },
                navArgument("uaMode") { type = NavType.StringType }
            )
        ) { entry ->
            val encodedUrl = entry.arguments?.getString("url") ?: ""
            val url = java.net.URLDecoder.decode(encodedUrl, "UTF-8")
            val uaMode = entry.arguments?.getString("uaMode") ?: "desktop"
            WebGameScreen(
                url = url,
                uaMode = uaMode,
                onExit = { nav.popBackStack() }
            )
        }
        composable(Routes.ABOUT) {
            AboutScreen(onBack = { nav.popBackStack() })
        }
        composable(Routes.FAVORITES) {
            LibraryScreen(
                games = games.filter { it.isFavorite },
                onOpenGame = openGame,
                onBack = { nav.popBackStack() },
                onHome = { nav.popBackStack(Routes.HOME, inclusive = false) },
                onGamesChanged = reloadGames
            )
        }
        composable(Routes.HISTORY) {
            LibraryScreen(
                games = games,
                onOpenGame = openGame,
                onBack = { nav.popBackStack() },
                onHome = { nav.popBackStack(Routes.HOME, inclusive = false) },
                onGamesChanged = reloadGames
            )
        }
        composable(Routes.SETTINGS) {
            SettingsScreen(
                onBack = { nav.popBackStack() },
                onOpenKeyMap = { nav.navigate(Routes.KEYMAP) }
            )
        }
        composable(Routes.KEYMAP) {
            KeyMapScreen(onBack = { nav.popBackStack() })
        }
        composable(
            Routes.EMULATOR,
            arguments = listOf(navArgument("gameId") { type = NavType.StringType })
        ) { entry ->
            val id = entry.arguments?.getString("gameId") ?: ""
            val game = games.firstOrNull { it.id == id }
                ?: RomStore.loadAll(ctx).firstOrNull { it.id == id }
                ?: GameEntry(id, "未知游戏")
            EmulatorScreen(game = game, onExit = { nav.popBackStack() })
        }
        composable(
            Routes.SWF_PLAYER,
            arguments = listOf(navArgument("swfPath") { type = NavType.StringType })
        ) { entry ->
            val encodedPath = entry.arguments?.getString("swfPath") ?: ""
            val swfPath = java.net.URLDecoder.decode(encodedPath, "UTF-8")
            SwfPlayerScreen(swfPath = swfPath, onExit = { nav.popBackStack() })
        }
    }
}

@Composable
private fun TvNavHost(
    nav: androidx.navigation.NavHostController,
    games: List<GameEntry>,
    reloadGames: () -> Unit
) {
    val ctx = LocalContext.current

    // All platforms (including J2ME) use the unified EmulatorScreen in TV mode.
    val openGame: (GameEntry) -> Unit = { game ->
        nav.navigate(Routes.emulator(game.id))
    }

    NavHost(
        navController = nav,
        startDestination = Routes.HOME,
        modifier = Modifier.fillMaxSize()
    ) {
        composable(Routes.HOME) {
            TvHomeScreen(
                games = games,
                onOpenLibrary = { nav.navigate(Routes.library()) },
                onOpenPlatform = { p -> nav.navigate(Routes.library(p)) },
                onOpenOnlineGames = { nav.navigate(Routes.ONLINE_GAMES) },
                onOpenBattle = { nav.navigate(Routes.BATTLE) },
                onOpenSwf = { nav.navigate(Routes.SWF_LIST) },
                onOpenSettings = { nav.navigate(Routes.SETTINGS) },
                onOpenAbout = { nav.navigate(Routes.ABOUT) },
                onExit = { nav.context.let { (it as? android.app.Activity)?.finishAffinity() } }
            )
        }
        composable(Routes.BATTLE) {
            BattleScreen(
                onBack = { nav.popBackStack() },
                onHome = { nav.popBackStack(Routes.HOME, inclusive = false) },
                onOpenMatch = { args ->
                    nav.navigate(Routes.battleMatch(args.roomId, args.gameId, args.isHost, args.tcpAddr, args.platform.name, args.fileName))
                }
            )
        }
        composable(
            Routes.BATTLE_MATCH,
            arguments = listOf(
                navArgument("roomId") { type = NavType.StringType },
                navArgument("gameId") { type = NavType.StringType },
                navArgument("isHost") { type = NavType.BoolType },
                navArgument("tcpAddr") { type = NavType.StringType },
                navArgument("platform") { type = NavType.StringType },
                navArgument("fileName") { type = NavType.StringType }
            )
        ) { entry ->
            val roomId = entry.arguments?.getString("roomId") ?: ""
            val gameId = entry.arguments?.getString("gameId") ?: ""
            val isHost = entry.arguments?.getBoolean("isHost") ?: false
            val tcpAddr = java.net.URLDecoder.decode(
                entry.arguments?.getString("tcpAddr") ?: "",
                "UTF-8"
            )
            val platformStr = java.net.URLDecoder.decode(
                entry.arguments?.getString("platform") ?: "arcade",
                "UTF-8"
            )
            val fileName = java.net.URLDecoder.decode(
                entry.arguments?.getString("fileName") ?: "",
                "UTF-8"
            )
            BattleMatchScreen(
                args = BattleMatchArgs(
                    roomId = roomId,
                    gameId = gameId,
                    isHost = isHost,
                    tcpAddr = tcpAddr,
                    platform = com.nesstation.app.core.model.GamePlatform.fromString(platformStr),
                    fileName = fileName
                ),
                onExit = {
                    nav.popBackStack(Routes.BATTLE, inclusive = false)
                }
            )
        }
        composable(
            Routes.LIBRARY,
            arguments = listOf(
                navArgument("platform") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                }
            )
        ) { entry ->
            val platformStr = entry.arguments?.getString("platform")
            LibraryScreen(
                games = games,
                onOpenGame = openGame,
                onBack = { nav.popBackStack() },
                onHome = { nav.popBackStack(Routes.HOME, inclusive = false) },
                onGamesChanged = reloadGames,
                initialPlatform = platformStr?.takeIf { it.isNotBlank() }
                    ?.let { GamePlatform.fromString(it) }
            )
        }
        composable(Routes.SWF_LIST) {
            SwfListScreen(
                onBack = { nav.popBackStack() },
                onHome = { nav.popBackStack(Routes.HOME, inclusive = false) },
                onOpenSwf = { path -> nav.navigate(Routes.swfPlayer(path)) }
            )
        }
        composable(Routes.ONLINE_GAMES) {
            OnlineGamesScreen(
                onBack = { nav.popBackStack() },
                onHome = { nav.popBackStack(Routes.HOME, inclusive = false) },
                onOpenGame = { game ->
                    nav.navigate(Routes.webGame(game.url, game.uaMode))
                }
            )
        }
        composable(
            Routes.WEB_GAME,
            arguments = listOf(
                navArgument("url") { type = NavType.StringType },
                navArgument("uaMode") { type = NavType.StringType }
            )
        ) { entry ->
            val encodedUrl = entry.arguments?.getString("url") ?: ""
            val url = java.net.URLDecoder.decode(encodedUrl, "UTF-8")
            val uaMode = entry.arguments?.getString("uaMode") ?: "desktop"
            WebGameScreen(
                url = url,
                uaMode = uaMode,
                onExit = { nav.popBackStack() }
            )
        }
        composable(Routes.ABOUT) {
            AboutScreen(onBack = { nav.popBackStack() })
        }
        composable(Routes.SETTINGS) {
            SettingsScreen(
                onBack = { nav.popBackStack() },
                onOpenKeyMap = { nav.navigate(Routes.KEYMAP) }
            )
        }
        composable(Routes.KEYMAP) {
            KeyMapScreen(onBack = { nav.popBackStack() })
        }
        composable(
            Routes.EMULATOR,
            arguments = listOf(navArgument("gameId") { type = NavType.StringType })
        ) { entry ->
            val id = entry.arguments?.getString("gameId") ?: ""
            val game = games.firstOrNull { it.id == id }
                ?: RomStore.loadAll(ctx).firstOrNull { it.id == id }
                ?: GameEntry(id, "未知游戏")
            EmulatorScreen(game = game, onExit = { nav.popBackStack() })
        }
        composable(
            Routes.SWF_PLAYER,
            arguments = listOf(navArgument("swfPath") { type = NavType.StringType })
        ) { entry ->
            val encodedPath = entry.arguments?.getString("swfPath") ?: ""
            val swfPath = java.net.URLDecoder.decode(encodedPath, "UTF-8")
            SwfPlayerScreen(swfPath = swfPath, onExit = { nav.popBackStack() })
        }
    }
}
