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

    // ★★ v4.4：座位选择对话框（点击已开的桌子弹出：2P/3P/4P 空闲座位 / 观战）
    var seatDialogRoom by remember { mutableStateOf<BattleApi.Room?>(null) }
    // ★★ v4.5：弹座位对话框的桌号（标题显示“加入 · 桌 N”）
    var seatDialogTable by remember { mutableStateOf(0) }

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

    // ★★ v4.4：进桌流程（统一入口）：room=null 创建新桌（★ v4.5 带桌号 —— 点哪桌开哪桌）；
    //   否则按选定座位/观战加入。
    //   spectate=true → 观战（不占座位，满员仍可）；wantSlot=2/3/4 → 用户点的座位
    //   （服务器分配结果以 hello 回执为准 —— 被抢先占位时自动顺延到下一空闲座）。
    fun startJoin(room: BattleApi.Room?, tableNo: Int, spectate: Boolean, wantSlot: Int) {
        val targetGame = selectedGame ?: return
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
                // ROM 就绪，创建 / 加入房间（★ v4.5 创建时带桌号：开 2 桌就是 2 桌）
                val token = BattleSession.getToken(context) ?: return@launch
                val (joined, tcp) = if (room == null) {
                    BattleApi(context).createRoom(targetGame.id, token, tableNo)
                } else {
                    BattleApi(context).joinRoom(room.id, token, spectate)
                }
                withContext(Dispatchers.Main) {
                    onOpenMatch(
                        BattleMatchArgs(
                            roomId = joined.id,
                            gameId = joined.gameId,
                            isHost = room == null,
                            tcpAddr = tcp,
                            platform = com.nesstation.app.core.model.GamePlatform.fromString(targetGame.platform),
                            fileName = targetGame.fileName,
                            spectate = spectate,
                            wantSlot = wantSlot
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
                            "选桌入座 · 2P/3P/4P · 满员可观战"   // ★ v4.4：不再是只有 1P/2P
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
                    onJoinTable = { room, tableNo ->
                        if (!BattleSession.isLoggedIn(context)) {
                            showLoginDialog = true
                            return@ArcadeHallGrid
                        }
                        if (room == null) {
                            // 空桌：在该桌创建（1P）—— ★ v4.5 开哪桌就是哪桌
                            startJoin(null, tableNo, spectate = false, wantSlot = 0)
                        } else {
                            // ★★ v4.4 已开的桌子：弹座位选择（空闲 2P/3P/4P + 观战；满员仅观战）
                            seatDialogRoom = room
                            seatDialogTable = tableNo
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

    // ★★ v4.4 座位选择对话框（点击已开的桌子弹出）
    seatDialogRoom?.let { room ->
        JoinSeatDialog(
            room = room,
            tableNo = if (room.table in 1..TABLE_COUNT) room.table else seatDialogTable,
            onDismiss = { seatDialogRoom = null },
            onPick = { spectate, wantSlot ->
                seatDialogRoom = null
                startJoin(room, room.table, spectate, wantSlot)
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
// 街机厅房间桌面宫格（10 张桌子，1P - 4P 座位）
// ★★ v4.5 桌号固定：房间的桌号由服务器下发（创建时点哪桌就是哪桌）——
//   开 2 桌就显示在 2 桌、点 2 桌加入的一定是 2 桌的房间。
//   旧服务器无桌号（table=0）的房间按创建顺序填进剩余空桌（兼容）。
// ---------------------------------------------------------------------------

@Composable
private fun ArcadeHallGrid(
    game: BattleApi.Game,
    rooms: List<BattleApi.Room>,
    onBackToLibrary: () -> Unit,
    onJoinTable: (BattleApi.Room?, tableNo: Int) -> Unit
) {
    // 当前游戏的房间，按创建时间排序（旧桌号兼容填充用）
    val gameRooms = remember(rooms, game.id) {
        rooms.filter { it.gameId == game.id }
            .sortedBy { it.createdAt }
    }
    // ★★ v4.5 桌号 → 房间映射：有桌号的房间固定在自己的桌上；
    //   无桌号（旧服务器）/ 桌号冲突的房间按创建顺序填空桌。
    val tableMap = remember(gameRooms) {
        val placed = HashMap<Int, BattleApi.Room>()
        val unplaced = ArrayList<BattleApi.Room>()
        for (r in gameRooms) {
            if (r.table in 1..TABLE_COUNT && r.table !in placed) {
                placed[r.table] = r
            } else {
                unplaced.add(r)
            }
        }
        for (t in 1..TABLE_COUNT) {
            if (unplaced.isEmpty()) break
            if (t !in placed) {
                placed[t] = unplaced.removeAt(0)
            }
        }
        placed
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
                val tableNo = index + 1
                val room = tableMap[tableNo]
                ArcadeTableCard(
                    tableNo = tableNo,
                    game = game,
                    room = room,
                    accent = CabinetPalette[index % CabinetPalette.size],
                    // ★★ v4.4：点击已开的桌子弹座位选择（满员在对话框里仅提供观战）；
                    //   ★★ v4.5 空桌带上桌号 —— 在这张桌创建房间
                    onClick = { onJoinTable(room, tableNo) }
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
    // ★ v4.3：4 座位（1P-4P）。slots 由服务器下发；老服务器缺字段时回退
    //   到 host/guest 双座位判定。
    val slots = room?.slots?.takeIf { it.isNotEmpty() } ?: run {
        val s = ArrayList<Int>()
        if (room != null) s.add(1)
        if (room != null && room.guest.isNotBlank()) s.add(2)
        s
    }
    val occupied1P = slots.contains(1)
    val occupied2P = slots.contains(2)
    val occupied3P = slots.contains(3)
    val occupied4P = slots.contains(4)
    val isFull = slots.size >= 4
    // 对战中但仍可加入（1P 单机开玩 / 已有人对战且 <4 人）
    val playingJoinable = room != null && (room.status == "playing" || room.status == "syncing") && !isFull
    // ★★ v4.4：观战人数（不占座位）
    val specCount = room?.spectators ?: 0

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
                        isFull -> if (specCount > 0) "4人满员·${specCount}人观战" else "4人满员·仅观战"
                        playingJoinable -> "对战中·可加入"
                        occupied1P -> "等待 ${slots.size + 1}P"
                        else -> "空桌"
                    },
                    color = if (isFull) Success else if (playingJoinable) Color(0xFF64B5F6) else if (occupied1P) Warn else Color.White.copy(alpha = 0.6f),
                    fontSize = 10.sp,
                    fontWeight = FontWeight.SemiBold
                )
                // ★★ v4.4：未满员但有观战者时也提示（满员行已并入上行文案）
                if (!isFull && specCount > 0) {
                    Text(
                        "${specCount} 人观战中",
                        color = Color.White.copy(alpha = 0.75f),
                        fontSize = 9.sp
                    )
                }
            }
        }

        Spacer(Modifier.height(8.dp))

        // ★ v4.3：1P - 4P 四座位（3P/4P 徽章按服务器槽位显示占用）
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            SeatBadge(
                label = "1P",
                name = if (occupied1P) room?.host else null,
                occupied = occupied1P,
                accent = accent,
                modifier = Modifier.weight(1f)
            )
            SeatBadge(
                label = "2P",
                name = if (occupied2P) room?.guest else null,
                occupied = occupied2P,
                accent = accent,
                modifier = Modifier.weight(1f)
            )
            SeatBadge(
                label = "3P",
                name = null,
                occupied = occupied3P,
                accent = accent,
                modifier = Modifier.weight(1f)
            )
            SeatBadge(
                label = "4P",
                name = null,
                occupied = occupied4P,
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
// ★★ v4.4 座位选择对话框：点击已开的桌子弹出 —— 选 2P/3P/4P 空闲座位或观战；
// 满员时只提供观战。被抢先占位时服务器自动顺延到下一空闲座（以 hello 回执为准）。
// ---------------------------------------------------------------------------

@Composable
private fun JoinSeatDialog(
    room: BattleApi.Room,
    tableNo: Int,
    onDismiss: () -> Unit,
    onPick: (spectate: Boolean, wantSlot: Int) -> Unit
) {
    val slots = room.slots.ifEmpty {
        // 老服务器无 slots 字段：回退到 host/guest 双座位判定
        buildList {
            add(1)
            if (room.guest.isNotBlank()) add(2)
        }
    }
    val isFull = slots.size >= 4
    val statusText = when (room.status) {
        "playing", "syncing" -> "对战中 · 接入后自动同步画面"
        else -> "等待中 · 同步开跑"
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("加入 · 桌 $tableNo", color = PrimaryText, fontWeight = FontWeight.ExtraBold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "${room.gameTitle} · $statusText · 在线 ${room.players} 人" +
                        if (room.spectators > 0) " · 观战 ${room.spectators} 人" else "",
                    color = SecondaryText, fontSize = 11.sp
                )
                // 1P（房主座位，只展示不可选）
                SeatPickRow(
                    label = "1P",
                    name = room.host.ifBlank { "房主" },
                    occupied = true,
                    enabled = false,
                    onClick = {}
                )
                // 2P / 3P / 4P：占用 → 禁用展示；空闲 → 点击入座
                for (slot in 2..4) {
                    val occupied = slots.contains(slot)
                    SeatPickRow(
                        label = "${slot}P",
                        name = when {
                            !occupied -> "空闲 · 点击入座"
                            slot == 2 && room.guest.isNotBlank() -> room.guest
                            else -> "占用中"
                        },
                        occupied = occupied,
                        enabled = !occupied,
                        onClick = { onPick(false, slot) }
                    )
                }
                // 观战（始终可用 —— 满员时唯一选项）
                Button(
                    onClick = { onPick(true, 0) },
                    colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = Color.White),
                    modifier = Modifier.fillMaxWidth().height(44.dp)
                ) {
                    Text(
                        if (isFull) "👁 观战（桌子已满）" else "👁 观战",
                        fontWeight = FontWeight.Bold
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

/** 座位选择行（1P/2P/3P/4P） */
@Composable
private fun SeatPickRow(
    label: String,
    name: String,
    occupied: Boolean,
    enabled: Boolean,
    onClick: () -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(
                when {
                    enabled -> Accent.copy(alpha = 0.12f)
                    occupied -> Color(0xFFF2F4F8)
                    else -> Color.Transparent
                }
            )
            .let { if (enabled) it.clickable(onClick = onClick) else it }
            .padding(horizontal = 12.dp, vertical = 10.dp)
    ) {
        Box(
            modifier = Modifier
                .size(14.dp)
                .clip(CircleShape)
                .background(if (occupied) Success else Accent)
        )
        Spacer(Modifier.width(10.dp))
        Text(label, color = PrimaryText, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.width(8.dp))
        Text(
            name,
            color = if (enabled) Accent else SecondaryTextLight,
            fontSize = 11.sp,
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
    val fileName: String = "",
    /** ★★ v4.4：观战模式（不占座位、只收不发） */
    val spectate: Boolean = false,
    /** ★★ v4.4：期望座位 2/3/4（0=自动） */
    val wantSlot: Int = 0
)

/** ROM 下载任务状态（用于大厅进度显示） */
private data class DownloadTask(val gameId: String, val fileName: String, val progress: Float)