package com.nesstation.app.ui.neon

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.HelpOutline
import androidx.compose.material.icons.automirrored.rounded.Logout
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.SportsEsports
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nesstation.app.core.model.GameEntry
import com.nesstation.app.core.model.GamePlatform
import com.nesstation.app.ui.components.AppBackgroundState
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 主页菜单项模型。 */
private data class NeonMenuItem(
    val key: String,           // all | fav | recent | platform:XXX | online | battle | swf | settings | about | exit
    val title: String,
    val subtitle: String,
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
    val accent: Color,
    val badge: String? = null
)

/** 主页菜单分区。 */
private data class NeonMenuSection(
    val title: String,
    val items: List<NeonMenuItem>
)

/**
 * ★★ Neon 主页（图3 参考稿）—— 赛博街机厅风格主菜单 ★★
 *
 * 布局（横屏；竖屏自动上下堆叠）：
 *   - 顶部：品牌徽标 + 游戏总数 + 时钟
 *   - 左侧（约 58%）：Hero 预览面板 —— 当前聚焦菜单的封面拼贴 / 大图标 +
 *     红蓝撞色大标题（ALL GAMES 样式）
 *   - 右侧（约 42%）：分区菜单列表（切角卡片 + 青色菱形选中指示）：
 *       全部游戏 / 收藏 / 最近游玩 / 各平台（带数量徽标）/
 *       在线游戏 / 对战平台 (4P) / SWF / 设置 / 关于 / 退出
 *   - 底部：手柄按键提示栏（A 选择 / B 返回 / X 收藏 / Y 搜索）
 *
 * 交互：菜单项可聚焦（D-pad 上下移动实时刷新 Hero）+ 点击直接激活；
 * 触屏设备即点即用。TV / 蓝牙手柄经 focus + 确认键激活。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun NeonHomeScreen(
    games: List<GameEntry>,
    onOpenAllGames: () -> Unit,
    onOpenFavorites: () -> Unit,
    onOpenHistory: () -> Unit,
    onOpenPlatform: (GamePlatform) -> Unit,
    onOpenOnlineGames: () -> Unit,
    onOpenBattle: () -> Unit,
    onOpenSwf: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenAbout: () -> Unit,
    onExit: () -> Unit,
    modifier: Modifier = Modifier
) {
    val coverCache = remember { android.util.LruCache<String, android.graphics.Bitmap>(32) }
    val isPortrait = androidx.compose.ui.platform.LocalConfiguration.current.orientation ==
        android.content.res.Configuration.ORIENTATION_PORTRAIT

    val platformOrder = listOf(
        GamePlatform.NES, GamePlatform.SFC, GamePlatform.GB, GamePlatform.GBA,
        GamePlatform.MD, GamePlatform.PCE, GamePlatform.PSX, GamePlatform.PS2,
        GamePlatform.NDS, GamePlatform.N3DS, GamePlatform.NGCWII, GamePlatform.DC,
        GamePlatform.ARCADE, GamePlatform.DOS, GamePlatform.JAVA
    )
    val countByPlatform = remember(games) { games.groupingBy { it.platform }.eachCount() }

    // ===== 菜单构建（各类菜单补全：库 / 收藏 / 最近 / 平台 / 功能）=====
    val sections = remember(games, countByPlatform) {
        listOf(
            NeonMenuSection("游戏库", listOf(
                NeonMenuItem("all", "全部游戏", "ALL GAMES", Icons.Rounded.GridView, Neon.Red, games.size.toString()),
                NeonMenuItem("fav", "收藏游戏", "FAVORITES", Icons.Rounded.Star, Neon.Gold,
                    games.count { it.isFavorite }.toString()),
                NeonMenuItem("recent", "最近游玩", "RECENT", Icons.Rounded.History, Neon.Blue,
                    games.count { it.lastPlayedAt > 0 }.toString())
            )),
            NeonMenuSection("游戏平台", platformOrder.mapNotNull { p ->
                val n = countByPlatform[p] ?: 0
                if (n > 0) NeonMenuItem("platform:${p.name}", p.displayName, platformEra(p),
                    neonPlatformIcon(p), Neon.Accent, n.toString())
                else null
            }),
            NeonMenuSection("功能", listOf(
                NeonMenuItem("online", "在线游戏", "ONLINE GAMES", Icons.Rounded.Public, Neon.Accent),
                NeonMenuItem("battle", "对战平台", "4P GAMES", Icons.Rounded.SportsEsports, Neon.Blue),
                NeonMenuItem("swf", "SWF / FLASH", "RUFFLE PLAYER", Icons.Rounded.PlayArrow, Neon.Gold),
                NeonMenuItem("settings", "设置", "SETTINGS", Icons.Rounded.Settings, Neon.Text),
                NeonMenuItem("about", "关于", "ABOUT", Icons.AutoMirrored.Rounded.HelpOutline, Neon.Text),
                NeonMenuItem("exit", "退出", "EXIT", Icons.AutoMirrored.Rounded.Logout, Neon.Red)
            ))
        )
    }
    val allItems = remember(sections) { sections.flatMap { it.items } }

    // 当前聚焦菜单项（Hero 预览随动）
    var selectedKey by remember { mutableStateOf("all") }
    val selectedItem = allItems.firstOrNull { it.key == selectedKey } ?: allItems.first()

    fun activate(item: NeonMenuItem) {
        when {
            item.key == "all" -> onOpenAllGames()
            item.key == "fav" -> onOpenFavorites()
            item.key == "recent" -> onOpenHistory()
            item.key.startsWith("platform:") ->
                onOpenPlatform(GamePlatform.fromString(item.key.removePrefix("platform:")))
            item.key == "online" -> onOpenOnlineGames()
            item.key == "battle" -> onOpenBattle()
            item.key == "swf" -> onOpenSwf()
            item.key == "settings" -> onOpenSettings()
            item.key == "about" -> onOpenAbout()
            item.key == "exit" -> onExit()
        }
    }

    // Hero 预览用的游戏集（按菜单类型取）
    val heroGames: List<GameEntry> = remember(selectedKey, games) {
        when {
            selectedKey == "all" -> games.sortedByDescending { it.lastPlayedAt }
            selectedKey == "fav" -> games.filter { it.isFavorite }
            selectedKey == "recent" -> games.filter { it.lastPlayedAt > 0 }.sortedByDescending { it.lastPlayedAt }
            selectedKey.startsWith("platform:") -> {
                val p = GamePlatform.fromString(selectedKey.removePrefix("platform:"))
                games.filter { it.platform == p }.sortedByDescending { it.lastPlayedAt }
            }
            else -> emptyList()
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        // 背景：全局背景激活时不自绘；Neon 深空底 + 网格微光
        if (!AppBackgroundState.active) {
            NeonBackdrop()
        }

        Column(modifier = Modifier.fillMaxSize()) {
            NeonTopBar(gameCount = games.size)

            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                if (isPortrait) {
                    // 竖屏：Hero 在上（36%），菜单在下
                    Column(modifier = Modifier.fillMaxSize()) {
                        Box(modifier = Modifier.fillMaxWidth().fillMaxHeight(0.36f)) {
                            NeonHeroPanel(selectedItem, heroGames, coverCache, modifier = Modifier.fillMaxSize())
                        }
                        NeonMenuList(
                            sections = sections,
                            selectedKey = selectedKey,
                            onSelect = { selectedKey = it },
                            onActivate = ::activate,
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                } else {
                    // 横屏：Hero 左 58% / 菜单右 42%（图3 布局）
                    Row(modifier = Modifier.fillMaxSize()) {
                        Box(modifier = Modifier.fillMaxWidth(0.58f).fillMaxHeight()) {
                            NeonHeroPanel(selectedItem, heroGames, coverCache, modifier = Modifier.fillMaxSize())
                        }
                        NeonMenuList(
                            sections = sections,
                            selectedKey = selectedKey,
                            onSelect = { selectedKey = it },
                            onActivate = ::activate,
                            modifier = Modifier.fillMaxWidth().fillMaxHeight()
                        )
                    }
                }
            }

            // 底部手柄提示栏
            NeonHintsBar(
                hints = listOf(
                    Triple("A", "选择", Neon.BtnA),
                    Triple("B", "返回", Neon.BtnB),
                    Triple("X", "收藏", Neon.BtnX),
                    Triple("Y", "搜索", Neon.BtnY)
                ),
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .padding(bottom = 10.dp)
            )
        }
    }
}

/** Neon 深空背景：纯深底 + 顶部青色微光 + 细网格线（低开销绘制层）。 */
@Composable
fun NeonBackdrop(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    0f to Color(0xFF0A1428),
                    0.45f to Neon.Bg,
                    1f to Color(0xFF03040A)
                )
            )
    ) {
        // 网格微纹（低透明度竖线）
        Row(modifier = Modifier.fillMaxSize()) {
            repeat(12) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .background(Neon.Line.copy(alpha = 0.14f))
                )
            }
        }
    }
}

/** 顶部条：品牌徽标 + 游戏总数 + 时钟。 */
@Composable
private fun NeonTopBar(gameCount: Int) {
    var clock by remember { mutableStateOf("") }
    LaunchedEffect(Unit) {
        while (true) {
            clock = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
            kotlinx.coroutines.delay(30_000)
        }
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 品牌徽标（切角小牌）
        Row(
            modifier = Modifier
                .background(Neon.Accent.copy(alpha = 0.12f), neonChamfer(0.35f))
                .border(1.dp, Neon.Accent, neonChamfer(0.35f))
                .padding(horizontal = 10.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Icon(Icons.Rounded.SportsEsports, null, tint = Neon.Accent, modifier = Modifier.size(16.dp))
            Text("NESSTATION", color = Neon.TextHi, fontSize = 13.sp, fontWeight = FontWeight.Black)
        }
        Spacer(Modifier.width(12.dp))
        Text(
            "$gameCount GAMES",
            color = Neon.Accent,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold
        )
        Spacer(Modifier.weight(1f))
        Text(clock, color = Neon.TextDim, fontSize = 12.sp, fontWeight = FontWeight.Medium)
    }
}

/**
 * Hero 预览面板（图3 左侧主视觉区）：
 * 上部 = 封面拼贴（1 大 + 2 小）或发光大图标；下部 = 红蓝撞色大标题 + 副标题。
 */
@Composable
private fun NeonHeroPanel(
    item: NeonMenuItem,
    heroGames: List<GameEntry>,
    coverCache: android.util.LruCache<String, android.graphics.Bitmap>,
    modifier: Modifier = Modifier
) {
    val glow by animateFloatAsState(
        targetValue = if (item.accent == Neon.Red) 1f else 0.65f,
        animationSpec = tween(300), label = "hero-glow"
    )
    Column(
        modifier = modifier
            .padding(horizontal = 18.dp, vertical = 10.dp)
            .background(Neon.BgPanel.copy(alpha = 0.72f), neonChamfer(0.06f))
            .border(1.5.dp, item.accent.copy(alpha = glow), neonChamfer(0.06f))
            .padding(16.dp)
    ) {
        // ===== 封面拼贴 / 大图标 =====
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        ) {
            if (heroGames.isNotEmpty()) {
                Row(
                    modifier = Modifier.fillMaxSize(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // 大封面（左 62%）
                    NeonCoverTile(heroGames.first(), coverCache, modifier = Modifier
                        .weight(0.62f)
                        .fillMaxHeight()
                        .graphicsLayer { scaleX = 0.97f; scaleY = 0.97f })
                    // 右列 2 小封面
                    Column(
                        modifier = Modifier
                            .weight(0.38f)
                            .fillMaxHeight(),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        heroGames.getOrNull(1)?.let {
                            NeonCoverTile(it, coverCache, modifier = Modifier.weight(1f).fillMaxWidth())
                        }
                        heroGames.getOrNull(2)?.let {
                            NeonCoverTile(it, coverCache, modifier = Modifier.weight(1f).fillMaxWidth())
                        }
                    }
                }
            } else {
                // 功能项：发光大图标 + 网格装饰
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            item.icon, null,
                            tint = item.accent,
                            modifier = Modifier
                                .size(92.dp)
                                .graphicsLayer { alpha = 0.92f }
                        )
                        Spacer(Modifier.height(10.dp))
                        Text(
                            "NesStation ARCADE SYSTEM",
                            color = Neon.TextDim,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(14.dp))

        // ===== 红蓝撞色大标题（图3 ALL GAMES 样式：中文红 + 英文蓝）=====
        Text(
            item.title,
            color = if (item.key == "all") Neon.Red else Neon.TextHi,
            fontSize = 30.sp,
            fontWeight = FontWeight.Black,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                item.subtitle,
                color = if (item.key == "all") Neon.Blue else item.accent.copy(alpha = 0.85f),
                fontSize = if (item.key == "all") 22.sp else 13.sp,
                fontWeight = FontWeight.Black,
                letterSpacing = if (item.key == "all") 1.sp else 2.sp
            )
            item.badge?.let {
                Spacer(Modifier.width(10.dp))
                Text(
                    "×$it",
                    color = Neon.Accent,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
        // 标题下霓虹分隔线
        Box(
            modifier = Modifier
                .padding(top = 8.dp)
                .fillMaxWidth(0.42f)
                .height(2.dp)
                .background(Brush.horizontalGradient(listOf(item.accent, Color.Transparent)))
        )
    }
}

/** 拼贴单元：封面 + 细框（保持 3:4 封面比例，防止拉伸变形）。 */
@Composable
private fun NeonCoverTile(
    game: GameEntry,
    cache: android.util.LruCache<String, android.graphics.Bitmap>,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .aspectRatio(0.72f, matchHeightConstraintsFirst = true)
            .background(Neon.BgPanelHi, RoundedCornerShape(6.dp))
            .border(1.dp, Neon.Line, RoundedCornerShape(6.dp))
            .clip(RoundedCornerShape(6.dp))
    ) {
        NeonCoverImage(game, cache)
    }
}

/**
 * 右侧分区菜单列表：分区标题 + 切角菜单卡（图标 + 标题/副标题 + 数量徽标 +
 * 青色菱形选中指示）。可滚动；聚焦/点击选中（选中即刷新 Hero 预览），
 * 点击（或手柄 A）直接激活。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun NeonMenuList(
    sections: List<NeonMenuSection>,
    selectedKey: String,
    onSelect: (String) -> Unit,
    onActivate: (NeonMenuItem) -> Unit,
    modifier: Modifier = Modifier
) {
    val listState = rememberLazyListState()
    LazyColumn(
        state = listState,
        modifier = modifier.padding(end = 16.dp, top = 4.dp, bottom = 4.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        sections.forEach { section ->
            item(key = "hdr:${section.title}") {
                Text(
                    section.title.uppercase(),
                    color = Neon.TextDim,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Black,
                    letterSpacing = 2.sp,
                    modifier = Modifier.padding(start = 6.dp, top = 6.dp, bottom = 2.dp)
                )
            }
            items(section.items, key = { it.key }) { item ->
                val selected = item.key == selectedKey
                val interaction = remember { MutableInteractionSource() }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            if (selected) Neon.BgPanelHi else Neon.BgPanel.copy(alpha = 0.55f),
                            neonChamfer(0.22f)
                        )
                        .border(
                            1.dp,
                            if (selected) item.accent else Neon.Line,
                            neonChamfer(0.22f)
                        )
                        .combinedClickable(
                            interactionSource = interaction,
                            indication = null,
                            onClick = { onActivate(item) },
                            onLongClick = { onSelect(item.key) }
                        )
                        .focusable(interactionSource = interaction)
                        .onFocusChangedCompat { focused -> if (focused) onSelect(item.key) }
                        .padding(horizontal = 12.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // 选中指示：青色菱形
                    Box(
                        modifier = Modifier
                            .size(9.dp)
                            .graphicsLayer { rotationZ = 45f }
                            .background(if (selected) item.accent else Color.Transparent)
                    )
                    Spacer(Modifier.width(10.dp))
                    Icon(item.icon, null, tint = if (selected) item.accent else Neon.TextDim,
                        modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(10.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            item.title,
                            color = if (selected) Neon.TextHi else Neon.Text,
                            fontSize = 14.sp,
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                            maxLines = 1, overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            item.subtitle,
                            color = Neon.TextDim,
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Medium,
                            letterSpacing = 1.sp,
                            maxLines = 1, overflow = TextOverflow.Ellipsis
                        )
                    }
                    item.badge?.let {
                        Text(
                            it,
                            color = if (selected) item.accent else Neon.TextDim,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
    }
}

/** focus 事件桥接（避免直接依赖内部 API 的兼容小封装）。 */
private fun Modifier.onFocusChangedCompat(onFocus: (Boolean) -> Unit): Modifier =
    this.then(
        androidx.compose.ui.focus.onFocusChanged { onFocus(it.isFocused) }
    )
