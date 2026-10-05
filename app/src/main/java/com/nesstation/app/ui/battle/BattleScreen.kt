package com.nesstation.app.ui.battle

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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items as lazyItems
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.automirrored.rounded.Login
import androidx.compose.material.icons.rounded.PersonAdd
import androidx.compose.material.icons.rounded.SportsEsports
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.nesstation.app.battle.BattleApi
import com.nesstation.app.battle.BattleRomStore
import com.nesstation.app.battle.BattleSession
import com.nesstation.app.ui.components.AppBackgroundState
import com.nesstation.app.ui.components.PixelBackdrop
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// ---- 配色（★ 本轮 Neon 主题适配：全部改为随 NeonUi.isNeon 切换的动态取色，
//      整页文字/卡片/背景在 Neon 主题下自动切换为深空底+电光青风格）----
private val PrimaryText: Color
    get() = if (com.nesstation.app.ui.neon.NeonUi.isNeon) com.nesstation.app.ui.neon.Neon.TextHi
        else Color(0xFF1E2A3A)
private val SecondaryText: Color
    get() = if (com.nesstation.app.ui.neon.NeonUi.isNeon) com.nesstation.app.ui.neon.Neon.Text
        else Color(0xFF4A5568)
private val SecondaryTextLight: Color
    get() = if (com.nesstation.app.ui.neon.NeonUi.isNeon) com.nesstation.app.ui.neon.Neon.TextDim
        else Color(0xFF8899AA)
private val Accent: Color
    get() = if (com.nesstation.app.ui.neon.NeonUi.isNeon) com.nesstation.app.ui.neon.Neon.Accent
        else Color(0xFF8A7BFF)
private val Accent2: Color
    get() = if (com.nesstation.app.ui.neon.NeonUi.isNeon) com.nesstation.app.ui.neon.Neon.Accent
        else Color(0xFF4F8AC4)
private val DeleteColor = Color(0xFFE74C3C)
private val Success = Color(0xFF27AE60)
private val Warn = Color(0xFFF1C40F)
/** 卡片底：Neon=深空面板 / FSD=白色半透明 */
private val CardBg: Color
    get() = if (com.nesstation.app.ui.neon.NeonUi.isNeon) com.nesstation.app.ui.neon.Neon.BgPanelHi
        else Color.White.copy(alpha = 0.85f)
/** 弹窗底：Neon=深空面板 / FSD=白 */
private val DialogBg: Color
    get() = if (com.nesstation.app.ui.neon.NeonUi.isNeon) com.nesstation.app.ui.neon.Neon.BgPanel
        else Color.White

/** 街机厅桌子数量 */
private const val TABLE_COUNT = 10

/** 街机机台配色（按桌子索引循环） */
private val CabinetPalette = listOf(
    Color(0xFF8A7BFF), Color(0xFFE74C3C), Color(0xFF27AE60), Color(0xFF3498DB),
    Color(0xFFE67E22), Color(0xFF1ABC9C), Color(0xFF9B59B6), Color(0xFFE84393),
    Color(0xFF00CEC9), Color(0xFF6C5CE7)
)

/** 对战平台主页：游戏库宫格 + 街机厅房间桌面宫格。 */
@Composable
fun BattleScreen(
    onBack: () -> Unit,
    onHome: () -> Unit,
    onOpenMatch: (BattleMatchArgs) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var loggedIn by remember { mutableStateOf(BattleSession.isLoggedIn(context)) }
    var username by remember { mutableStateOf(BattleSession.getUsername(context) ?: "") }

    // 服务器地址编辑
    var showServerDialog by remember { mutableStateOf(false) }
    // 登录 / 注册对话框（未登录时创建/加入房间触发）
    var showLoginDialog by remember { mutableStateOf(false) }

    // 游戏与房间数据
    var games by remember { mutableStateOf<List<BattleApi.Game>>(emptyList()) }
    var rooms by remember { mutableStateOf<List<BattleApi.Room>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var statusMsg by remember { mutableStateOf<String?>(null) }
    var downloading by remember { mutableStateOf<DownloadTask?>(null) }
    // 图标缓存版本号：每次刷新游戏列表时递增，强制 Coil 重新加载图标
    var iconVersion by remember { mutableStateOf(0) }

    // 当前选中的游戏（null = 游戏库宫格；非 null = 该游戏的街机厅桌面）
    var selectedGame by remember { mutableStateOf<BattleApi.Game?>(null) }

    // === 平台筛选（和本地游戏库一致） ===
    // 服务端的 games 列表可能包含多个平台（arcade / nes / snes / gba / md / pce ...），
    // 用户点 chip 切换平台，只看该平台的游戏。"全部" 显示所有平台。
    var selectedPlatform by remember { mutableStateOf<com.nesstation.app.core.model.GamePlatform?>(null) }

    // 加载游戏与房间（未登录也可浏览，进游戏时才需登录）
    fun refreshAll() {
        loading = true
        scope.launch(Dispatchers.IO) {
            try {
                val api = BattleApi(context)
                val g = api.games()
                val r = if (BattleSession.isLoggedIn(context)) api.rooms() else emptyList()
                withContext(Dispatchers.Main) {
                    games = g
                    rooms = r
                    iconVersion++
                    loading = false
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    loading = false
                    statusMsg = e.message
                }
            }
        }
    }

    // 进入对战平台即加载游戏/房间（未登录也可浏览）
    LaunchedEffect(Unit) { refreshAll() }

    // 街机厅内定时刷新房间列表，实时反映 1P / 2P 座位占用。
    // 将 loggedIn 纳入 key，确保在街机厅内登录成功后轮询也能重启。
    LaunchedEffect(selectedGame, loggedIn) {
        if (selectedGame != null && loggedIn) {
            while (true) {
                kotlinx.coroutines.delay(3000)
                scope.launch(Dispatchers.IO) {
                    try {
                        val r = BattleApi(context).rooms()
                        withContext(Dispatchers.Main) { rooms = r }
                    } catch (_: Exception) { }
                }
            }
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        // ★ Neon 主题：深空背景；FSD：像素浅色背景
        if (!AppBackgroundState.active) {
            if (com.nesstation.app.ui.neon.NeonUi.isNeon) {
                com.nesstation.app.ui.neon.NeonBackdrop()
            } else {
                PixelBackdrop()
            }
        }

        Column(modifier = Modifier.fillMaxSize()) {
            // ---- 顶部栏 ----
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = {
                    if (selectedGame != null) selectedGame = null else onBack()
                }) {
                    Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "返回", tint = PrimaryText)
                }
                HomePill(onClick = onHome, modifier = Modifier.padding(start = 2.dp))
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = 8.dp)
                ) {
                    Text(
                        if (selectedGame == null) "对战平台" else "${selectedGame!!.title} · 街机厅",
                        color = PrimaryText,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.ExtraBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        if (selectedGame == null) {
                            if (loggedIn) "已登录：$username" else "虚拟账号 · 实时联机"
                        } else {
                            "选择街机桌加入对战 · 1P / 2P"
                        },
                        color = SecondaryText,
                        fontSize = 10.sp
                    )
                }
                // 服务器设置入口
                IconButton(onClick = { showServerDialog = true }) {
                    Icon(Icons.Rounded.Groups, contentDescription = "服务器设置", tint = Accent2)
                }
            }

            if (selectedGame == null) {
                // ================= 游戏库宫格 =================
                GameLibraryGrid(
                    games = games,
                    loading = loading,
                    downloading = downloading,
                    selectedPlatform = selectedPlatform,
                    onPlatformChange = { selectedPlatform = it },
                    onDownloadAndEnter = { game ->
                        selectedGame = game
                        refreshAll()
                    }
                )
            } else {
                // ================= 街机厅房间桌面宫格 =================
                ArcadeHallGrid(
                    game = selectedGame!!,
                    rooms = rooms,
                    onBackToLibrary = { selectedGame = null },
                    onJoinTable = { room, isFull ->
                        if (!BattleSession.isLoggedIn(context)) {
                            showLoginDialog = true
                            return@ArcadeHallGrid
                        }
                        if (isFull) {
                            statusMsg = "该桌已满（1P / 2P 都有人）"
                            return@ArcadeHallGrid
                        }
                        val targetGame = selectedGame!!
                        // 进入房间前先确保 ROM 已下载；未下载则提示并下载，完成后自动进入房间
                        val has = BattleRomStore.hasRom(context, targetGame.fileName)
                        if (!has) {
                            statusMsg = "正在下载 ${targetGame.title}，完成后自动进入房间…"
                            downloading = DownloadTask(targetGame.id, targetGame.fileName, 0f)
                        }
                        scope.launch(Dispatchers.IO) {
                            try {
                                if (!has) {
                                    BattleApi(context).downloadRom(
                                        targetGame,
                                        BattleRomStore.romFile(context, targetGame.fileName)
                                    ) { done, total ->
                                        if (total > 0) {
                                            downloading = DownloadTask(
                                                targetGame.id, targetGame.fileName,
                                                (done.toFloat() / total).coerceIn(0f, 1f)
                                            )
                                        }
                                    }
                                }
                                withContext(Dispatchers.Main) { downloading = null }
                                // ROM 就绪，创建 / 加入房间
                                val token = BattleSession.getToken(context)!!
                                val (joined, tcp) = if (room == null) {
                                    BattleApi(context).createRoom(targetGame.id, token)
                                } else {
                                    BattleApi(context).joinRoom(room.id, token)
                                }
                                withContext(Dispatchers.Main) {
                                    onOpenMatch(
                                        BattleMatchArgs(
                                            roomId = joined.id,
                                            gameId = joined.gameId,
                                            isHost = room == null,
                                            tcpAddr = tcp,
                                            platform = com.nesstation.app.core.model.GamePlatform.fromString(targetGame.platform),
                                            fileName = targetGame.fileName
                                        )
                                    )
                                }
                            } catch (e: Exception) {
                                withContext(Dispatchers.Main) {
                                    downloading = null
                                    statusMsg = e.message
                                }
                            }
                        }
                    }
                )
            }
        }

        // 底部状态
        statusMsg?.let { msg ->
            androidx.compose.material3.Snackbar(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(16.dp),
                containerColor = Color(0xFF1E2A3A),
                contentColor = Color.White
            ) { Text(msg, fontSize = 12.sp) }
            LaunchedEffect(msg) {
                kotlinx.coroutines.delay(2400)
                statusMsg = null
            }
        }
    }

    if (showServerDialog) {
        ServerConfigDialog(
            onDismiss = { showServerDialog = false },
            onSaved = {
                showServerDialog = false
                statusMsg = "服务器地址已保存"
            }
        )
    }

    if (showLoginDialog) {
        LoginDialog(
            onDismiss = { showLoginDialog = false },
            onLoggedIn = { name ->
                showLoginDialog = false
                loggedIn = true
                username = name
                refreshAll()
            }
        )
    }
}

// ---------------------------------------------------------------------------
// 游戏库宫格（类似游戏库那种宫格，点击图标下载游戏）
// ---------------------------------------------------------------------------

@Composable
private fun GameLibraryGrid(
    games: List<BattleApi.Game>,
    loading: Boolean,
    downloading: DownloadTask?,
    selectedPlatform: com.nesstation.app.core.model.GamePlatform?,
    onPlatformChange: (com.nesstation.app.core.model.GamePlatform?) -> Unit,
    onDownloadAndEnter: (BattleApi.Game) -> Unit
) {
    val context = LocalContext.current

    // 计算服务端实际返回了哪些平台（动态生成 chip 列表，避免显示空 chip）
    val availablePlatforms = remember(games) {
        val set = linkedSetOf<com.nesstation.app.core.model.GamePlatform>()
        games.forEach { g ->
            set.add(com.nesstation.app.core.model.GamePlatform.fromString(g.platform))
        }
        set.toList()
    }

    // 应用平台筛选
    val visibleGames = remember(games, selectedPlatform) {
        if (selectedPlatform == null) games
        else games.filter {
            com.nesstation.app.core.model.GamePlatform.fromString(it.platform) == selectedPlatform
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // === 平台筛选 chip 行（和本地游戏库一致） ===
        // "全部" + 各平台 chip。"全部" = null，其他 = 对应的 GamePlatform。
        if (availablePlatforms.isNotEmpty()) {
            LazyRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                item {
                    BattleFilterChip(
                        text = "全部 ${games.size}",
                        selected = selectedPlatform == null,
                        onClick = { onPlatformChange(null) }
                    )
                }
                lazyItems(availablePlatforms) { platform ->
                    val count = games.count {
                        com.nesstation.app.core.model.GamePlatform.fromString(it.platform) == platform
                    }
                    BattleFilterChip(
                        text = "${platform.displayName} $count",
                        selected = selectedPlatform == platform,
                        onClick = { onPlatformChange(platform) }
                    )
                }
            }
        }

        Text(
            if (selectedPlatform == null) "全部游戏" else "${selectedPlatform.displayName} 游戏",
            color = PrimaryText,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(start = 22.dp, top = 6.dp, bottom = 2.dp)
        )

        if (visibleGames.isEmpty() && !loading) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Rounded.SportsEsports, contentDescription = null, tint = SecondaryTextLight, modifier = Modifier.size(40.dp))
                    Spacer(Modifier.height(10.dp))
                    Text(
                        if (games.isEmpty()) "服务器未返回游戏"
                        else "该平台暂无游戏",
                        color = PrimaryText, fontSize = 14.sp, fontWeight = FontWeight.SemiBold
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        if (games.isEmpty())
                            "请确认服务器 config.json 已配置 games 列表（如拳皇97 kof97）。"
                        else
                            "试试切换到其它平台 chip。",
                        color = SecondaryText,
                        fontSize = 11.sp,
                        textAlign = TextAlign.Center
                    )
                }
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(150.dp),
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(visibleGames, key = { it.id }) { game ->
                    BattleGameCard(
                        game = game,
                        downloaded = BattleRomStore.hasRom(context, game.fileName),
                        downloadTask = downloading?.takeIf { it.gameId == game.id },
                        onClick = { onDownloadAndEnter(game) }
                    )
                }
            }
        }
    }
}

/** 平台筛选 chip —— 极简版（比本地库的 FilterChip 还简单，无图标） */
@Composable
private fun BattleFilterChip(
    text: String,
    selected: Boolean,
    onClick: () -> Unit = {}
) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(20.dp))
            .background(
                if (selected) Accent
                else if (com.nesstation.app.ui.neon.NeonUi.isNeon)
                    com.nesstation.app.ui.neon.Neon.BgPanel
                else Color.White.copy(alpha = 0.5f)
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        Text(
            text,
            color = if (selected) Color.White else PrimaryText,
            fontSize = 11.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal
        )
    }
}

// ---------------------------------------------------------------------------
// 游戏卡片（宫格）
// ---------------------------------------------------------------------------

@Composable
private fun BattleGameCard(
    game: BattleApi.Game,
    downloaded: Boolean,
    downloadTask: DownloadTask?,
    onClick: () -> Unit
) {
    val accent = CabinetPalette[game.id.hashCode().mod(CabinetPalette.size)]

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(CardBg)
            .border(1.5.dp, accent.copy(alpha = 0.4f), RoundedCornerShape(20.dp))
            .clickable(onClick = onClick)
    ) {
        // 封面区
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(120.dp)
                .background(
                    Brush.verticalGradient(
                        listOf(accent.copy(alpha = 0.9f), accent.copy(alpha = 0.55f))
                    )
                ),
            contentAlignment = Alignment.Center
        ) {
            // 封面：优先加载网络图标，失败时显示首字母
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    game.title.take(1),
                    color = Color.White.copy(alpha = 0.35f),
                    fontSize = 46.sp,
                    fontWeight = FontWeight.ExtraBold
                )
                if (game.iconUrl.isNotBlank()) {
                    AsyncImage(
                        model = game.iconUrl,
                        contentDescription = game.title,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
            // 平台徽章
            Box(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(8.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color(0xFFF57C00).copy(alpha = 0.9f))
                    .padding(horizontal = 6.dp, vertical = 2.dp)
            ) {
                Text(
                    com.nesstation.app.core.model.GamePlatform.fromString(game.platform).displayName,
                    color = Color.White, fontSize = 9.sp, fontWeight = FontWeight.Bold
                )
            }
            // 已就绪徽章
            if (downloaded) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(8.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(Success.copy(alpha = 0.9f))
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Text("✓ 就绪", color = Color.White, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                }
            }
        }

        // 底部信息
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp)
        ) {
            Text(
                game.title,
                color = PrimaryText,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(3.dp))
            when {
                downloadTask != null -> {
                    LinearProgressIndicator(
                        progress = { downloadTask.progress },
                        modifier = Modifier.fillMaxWidth().height(6.dp),
                        color = accent,
                        trackColor = accent.copy(alpha = 0.15f)
                    )
                    Spacer(Modifier.height(3.dp))
                    Text(
                        "下载中 ${(downloadTask.progress * 100).toInt()}%",
                        color = SecondaryText,
                        fontSize = 10.sp
                    )
                }
                downloaded -> {
                    Text("点击进入街机厅", color = Success, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                }
                else -> {
                    Text("点击进入街机厅", color = SecondaryText, fontSize = 11.sp)
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// 街机厅房间桌面宫格（10 张桌子，1P / 2P 座位）
// ---------------------------------------------------------------------------

@Composable
private fun ArcadeHallGrid(
    game: BattleApi.Game,
    rooms: List<BattleApi.Room>,
    onBackToLibrary: () -> Unit,
    onJoinTable: (BattleApi.Room?, Boolean) -> Unit
) {
    // 只显示当前游戏的房间，按创建时间排序，映射到 10 张桌子
    val gameRooms = remember(rooms, game.id) {
        rooms.filter { it.gameId == game.id }
            .sortedBy { it.createdAt }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // 顶部：返回游戏库 + 标题
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = onBackToLibrary) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = null, tint = Accent, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text("返回游戏库", color = Accent, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.weight(1f))
            Text(
                "${gameRooms.size} / $TABLE_COUNT 桌占用",
                color = SecondaryText,
                fontSize = 11.sp
            )
        }

        LazyVerticalGrid(
            columns = GridCells.Adaptive(160.dp),
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            itemsIndexed((0 until TABLE_COUNT).toList(), key = { _, i -> i }) { index, _ ->
                val room = gameRooms.getOrNull(index)
                ArcadeTableCard(
                    tableNo = index + 1,
                    game = game,
                    room = room,
                    accent = CabinetPalette[index % CabinetPalette.size],
                    onClick = { onJoinTable(room, room != null && room.guest.isNotBlank()) }
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// 街机桌卡片（机台 + 1P / 2P 座位）
// ---------------------------------------------------------------------------

@Composable
private fun ArcadeTableCard(
    tableNo: Int,
    game: BattleApi.Game,
    room: BattleApi.Room?,
    accent: Color,
    onClick: () -> Unit
) {
    val occupied1P = room != null
    val occupied2P = room != null && room.guest.isNotBlank()
    val isFull = occupied1P && occupied2P

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(CardBg.copy(alpha = if (com.nesstation.app.ui.neon.NeonUi.isNeon) 1f else 0.88f))
            .border(
                width = if (isFull) 2.dp else 1.5.dp,
                color = if (isFull) Success else accent.copy(alpha = 0.45f),
                shape = RoundedCornerShape(20.dp)
            )
            .clickable(onClick = onClick)
            .padding(10.dp)
    ) {
        // 机台屏幕区
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(72.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(
                    Brush.verticalGradient(
                        listOf(Color(0xFF1E2A3A), Color(0xFF0E1626))
                    )
                ),
            contentAlignment = Alignment.Center
        ) {
            // 屏幕显示游戏图标
            if (game.iconUrl.isNotBlank()) {
                AsyncImage(
                    model = game.iconUrl,
                    contentDescription = game.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            }
            // 桌面号 + 状态
            Column(
                modifier = Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    "桌 $tableNo",
                    color = Color.White,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.ExtraBold
                )
                Text(
                    when {
                        isFull -> "对战中"
                        occupied1P -> "等待 2P"
                        else -> "空桌"
                    },
                    color = if (isFull) Success else if (occupied1P) Warn else Color.White.copy(alpha = 0.6f),
                    fontSize = 10.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }

        Spacer(Modifier.height(8.dp))

        // 1P / 2P 座位
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            SeatBadge(
                label = "1P",
                name = room?.host,
                occupied = occupied1P,
                accent = accent,
                modifier = Modifier.weight(1f)
            )
            SeatBadge(
                label = "2P",
                name = room?.guest,
                occupied = occupied2P,
                accent = accent,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

// ---------------------------------------------------------------------------
// 座位徽章
// ---------------------------------------------------------------------------

@Composable
private fun SeatBadge(
    label: String,
    name: String?,
    occupied: Boolean,
    accent: Color,
    modifier: Modifier = Modifier
) {
    val bg = when {
        occupied -> accent.copy(alpha = 0.18f)
        else -> Color(0xFFF2F4F8)
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(bg)
            .padding(horizontal = 8.dp, vertical = 6.dp)
    ) {
        Box(
            modifier = Modifier
                .size(12.dp)
                .clip(CircleShape)
                .background(if (occupied) Success else Color(0xFFB0B7C3))
        )
        Spacer(Modifier.width(6.dp))
        Text(
            label,
            color = SecondaryText,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold
        )
        Spacer(Modifier.width(4.dp))
        Text(
            name?.take(6) ?: "空",
            color = if (occupied) PrimaryText else SecondaryTextLight,
            fontSize = 10.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

// ---------------------------------------------------------------------------
// 登录 / 注册（对话框形式，进游戏时弹出；可滚动以适配横屏）
// ---------------------------------------------------------------------------

@Composable
private fun LoginDialog(
    onDismiss: () -> Unit,
    onLoggedIn: (String) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var mode by remember { mutableStateOf("login") } // login | register
    var busy by remember { mutableStateOf(false) }
    var errorMsg by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                if (mode == "login") "登录对战平台" else "注册虚拟账号",
                color = PrimaryText,
                fontWeight = FontWeight.ExtraBold
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .heightIn(max = 420.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    "开始对战前需要登录。虚拟账号由服务器统一管理，不涉及真实手机号。",
                    color = SecondaryText,
                    fontSize = 11.sp
                )

                OutlinedTextField(
                    value = username,
                    onValueChange = { username = it },
                    label = { Text("用户名（2-20 字符）") },
                    singleLine = true,
                    colors = lightFieldColors(),
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("密码（至少 4 位）") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    colors = lightFieldColors(),
                    modifier = Modifier.fillMaxWidth()
                )

                errorMsg?.let {
                    Text(it, color = DeleteColor, fontSize = 12.sp)
                }

                Button(
                    onClick = {
                        if (username.isBlank() || password.isBlank()) {
                            errorMsg = "请输入用户名和密码"
                            return@Button
                        }
                        busy = true
                        errorMsg = null
                        scope.launch(Dispatchers.IO) {
                            try {
                                val api = BattleApi(context)
                                val result = if (mode == "login") {
                                    api.login(username.trim(), password)
                                } else {
                                    api.register(username.trim(), password)
                                }
                                BattleSession.saveAuth(context, result.token, result.nickname)
                                withContext(Dispatchers.Main) {
                                    busy = false
                                    onLoggedIn(result.nickname)
                                }
                            } catch (e: Exception) {
                                withContext(Dispatchers.Main) {
                                    busy = false
                                    errorMsg = e.message
                                }
                            }
                        }
                    },
                    enabled = !busy,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Accent,
                        contentColor = Color.White
                    ),
                    modifier = Modifier.fillMaxWidth().height(44.dp)
                ) {
                    if (busy) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            color = Color.White,
                            strokeWidth = 2.dp
                        )
                    } else {
                        Icon(
                            if (mode == "login") Icons.AutoMirrored.Rounded.Login else Icons.Rounded.PersonAdd,
                            contentDescription = null
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(if (mode == "login") "登录" else "注册并登录", fontWeight = FontWeight.Bold)
                    }
                }

                TextButton(
                    onClick = {
                        mode = if (mode == "login") "register" else "login"
                        errorMsg = null
                    },
                    modifier = Modifier.align(Alignment.CenterHorizontally)
                ) {
                    Text(
                        if (mode == "login") "没有账号？立即注册" else "已有账号？去登录",
                        color = Accent,
                        fontSize = 12.sp
                    )
                }
            }
        },
        containerColor = DialogBg,
        titleContentColor = PrimaryText,
        textContentColor = PrimaryText,
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消", color = SecondaryText) }
        }
    )
}

// ---------------------------------------------------------------------------
// 服务器配置对话框
// ---------------------------------------------------------------------------

@Composable
private fun ServerConfigDialog(
    onDismiss: () -> Unit,
    onSaved: () -> Unit
) {
    val context = LocalContext.current
    var host by remember { mutableStateOf(BattleSession.getServerHost(context)) }
    var httpPort by remember { mutableStateOf(BattleSession.getServerHttpPort(context)) }
    var tcpPort by remember { mutableStateOf(BattleSession.getServerTcpPort(context)) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("对战服务器设置", color = PrimaryText) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "填写你的服务器公网 IP 或域名。HTTP 端口用于账号/房间/ROM，TCP 端口用于实时对战。",
                    color = SecondaryText,
                    fontSize = 11.sp
                )
                OutlinedTextField(
                    value = host,
                    onValueChange = { host = it },
                    label = { Text("服务器 IP / 域名") },
                    singleLine = true,
                    placeholder = { Text("例：203.0.113.5", color = SecondaryTextLight) },
                    colors = lightFieldColors(),
                    modifier = Modifier.fillMaxWidth()
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = httpPort,
                        onValueChange = { httpPort = it },
                        label = { Text("HTTP 端口") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        colors = lightFieldColors(),
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = tcpPort,
                        onValueChange = { tcpPort = it },
                        label = { Text("TCP 端口") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        colors = lightFieldColors(),
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        },
        containerColor = DialogBg,
        titleContentColor = PrimaryText,
        textContentColor = PrimaryText,
        confirmButton = {
            TextButton(onClick = {
                if (host.isNotBlank()) {
                    BattleSession.saveServer(context, host, httpPort, tcpPort)
                    onSaved()
                }
            }) { Text("保存", color = Accent) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消", color = SecondaryText) }
        }
    )
}

// ---------------------------------------------------------------------------
// 小组件
// ---------------------------------------------------------------------------

@Composable
private fun HomePill(onClick: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .clip(RoundedCornerShape(18.dp))
            .background(Color.White.copy(alpha = 0.6f))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 5.dp)
    ) {
        Icon(Icons.Rounded.Home, contentDescription = "返回主页", tint = PrimaryText, modifier = Modifier.size(14.dp))
        Spacer(Modifier.width(4.dp))
        Text("主页", color = PrimaryText, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
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
    unfocusedLabelColor = SecondaryText
)

/** 对战匹配参数（导航到对战界面的数据） */
data class BattleMatchArgs(
    val roomId: String,
    val gameId: String,
    val isHost: Boolean,
    val tcpAddr: String,
    val platform: com.nesstation.app.core.model.GamePlatform = com.nesstation.app.core.model.GamePlatform.ARCADE,
    val fileName: String = ""
)

/** ROM 下载任务状态（用于大厅进度显示） */
private data class DownloadTask(val gameId: String, val fileName: String, val progress: Float)