package com.nesstation.app.ui.neon

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.HelpOutline
import androidx.compose.material.icons.automirrored.rounded.Logout
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.SportsEsports
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nesstation.app.core.model.GameEntry
import com.nesstation.app.core.storage.RomStore
import com.nesstation.app.ui.components.AppBackgroundState
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * ★★ Neon 3D 主页（完全重制版）★★
 *
 * 主页【不包含】核心列表 —— 核心列表在游戏库页。主页结构：
 *   - 顶部：品牌徽标 + 游戏总数 + 时钟
 *   - 菜单行（各类菜单补全，横滑）：游戏库 / 收藏 / 最近 / 在线游戏 /
 *     对战平台 / SWF / 设置 / 关于 / 退出
 *   - 主视觉：中央 3D 封面流（[NeonFlow]，弧形导轨 + 透视 + 倒影），
 *     最近游玩优先排前 —— 选中即预览
 *   - 信息区：选中游戏大标题 + 平台徽章 + 游玩信息 + 操作按钮
 *   - 底部：手柄按键提示栏
 *
 * 交互：拖拽/D-pad 左右切换、点击居中卡或 A 键启动、长按/Y 切换收藏。
 */
@Composable
fun NeonHomeScreen(
    games: List<GameEntry>,
    onOpenLibrary: () -> Unit,
    onOpenFavorites: () -> Unit,
    onOpenHistory: () -> Unit,
    onOpenOnlineGames: () -> Unit,
    onOpenBattle: () -> Unit,
    onOpenSwf: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenAbout: () -> Unit,
    onExit: () -> Unit,
    modifier: Modifier = Modifier,
    onOpenGame: (GameEntry) -> Unit = {},
    onGamesChanged: () -> Unit = {}
) {
    val context = LocalContext.current
    val coverCache = remember { android.util.LruCache<String, android.graphics.Bitmap>(40) }

    // 主视觉列表：最近游玩优先（lastPlayedAt 降序，0=未玩排后）
    val flowGames = remember(games) {
        games.sortedWith(
            compareByDescending<GameEntry> { it.lastPlayedAt > 0L }
                .thenByDescending { it.lastPlayedAt }
        )
    }

    var selectedIndex by remember { mutableIntStateOf(0) }
    // 列表变化（收藏/删除）后收敛到有效范围
    LaunchedEffect(flowGames.size) {
        if (selectedIndex >= flowGames.size) selectedIndex = 0
    }
    val selIdx = selectedIndex.coerceIn(0, (flowGames.size - 1).coerceAtLeast(0))
    val currentGame = flowGames.getOrNull(selIdx)

    fun toggleFavorite(game: GameEntry) {
        try {
            RomStore.toggleFavorite(context, game.id)
            onGamesChanged()
        } catch (_: Exception) { }
    }

    Box(modifier = modifier.fillMaxSize()) {
        if (!AppBackgroundState.active) NeonBackdrop()

        Column(modifier = Modifier.fillMaxSize()) {
            // ===== 顶部品牌条 =====
            NeonHomeTopBar(gameCount = games.size)

            // ===== 菜单行（各类菜单补全）=====
            NeonMenuRow(
                items = listOf(
                    NeonChip("游戏库", Icons.Rounded.GridView, Neon.Red, onOpenLibrary),
                    NeonChip("收藏", Icons.Rounded.Favorite, Neon.Gold, onOpenFavorites),
                    NeonChip("最近", Icons.Rounded.History, Neon.Blue, onOpenHistory),
                    NeonChip("在线游戏", Icons.Rounded.Public, Neon.Accent, onOpenOnlineGames),
                    NeonChip("对战平台", Icons.Rounded.SportsEsports, Neon.Purple, onOpenBattle),
                    NeonChip("SWF", Icons.Rounded.Bolt, Neon.Gold, onOpenSwf),
                    NeonChip("设置", Icons.Rounded.Settings, Neon.Text, onOpenSettings),
                    NeonChip("关于", Icons.AutoMirrored.Rounded.HelpOutline, Neon.Text, onOpenAbout),
                    NeonChip("退出", Icons.AutoMirrored.Rounded.Logout, Neon.Red, onExit)
                )
            )

            // ===== 主视觉 3D 封面流 =====
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) {
                if (flowGames.isEmpty()) {
                    // 空库引导
                    Column(
                        modifier = Modifier.align(Alignment.Center),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(
                            Icons.Rounded.SportsEsports, null,
                            tint = Neon.TextDim, modifier = Modifier.size(64.dp)
                        )
                        Spacer(Modifier.height(14.dp))
                        Text("还没有游戏", color = Neon.TextHi, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(6.dp))
                        Text("去游戏库导入 ROM 开始游玩", color = Neon.TextDim, fontSize = 12.sp)
                        Spacer(Modifier.height(18.dp))
                        NeonPrimaryButton("进入游戏库", Icons.Rounded.GridView, onOpenLibrary)
                    }
                } else {
                    Column(modifier = Modifier.fillMaxSize()) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f)
                        ) {
                            NeonFlow(
                                count = flowGames.size,
                                selectedIndex = selIdx,
                                onIndexChange = { selectedIndex = it },
                                onItemClick = { idx -> flowGames.getOrNull(idx)?.let(onOpenGame) },
                                onItemLongClick = { idx -> flowGames.getOrNull(idx)?.let(::toggleFavorite) },
                                grabFocusOnLaunch = true,
                                showReflection = true,
                                verticalShift = 16.dp,   // ★ 封面流整体下移，不遮挡菜单行
                                modifier = Modifier.fillMaxSize()
                            ) { i ->
                                val g = flowGames[i]
                                NeonCoverCard(
                                    game = g,
                                    cache = coverCache,
                                    glow = if (i == selIdx) 1f else 0f,
                                    modifier = Modifier.fillMaxSize()
                                )
                            }
                        }

                        // ===== 选中游戏信息区 =====
                        currentGame?.let { game ->
                            NeonGameInfoPanel(
                                game = game,
                                onPlay = { onOpenGame(game) },
                                onOpenLibrary = onOpenLibrary,
                                onToggleFavorite = { toggleFavorite(game) },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 34.dp)
                            )
                        }
                    }
                }
            }

            // ===== 底部按键提示 =====
            NeonHintsBar(
                hints = listOf(
                    Triple("A", "启动", Neon.BtnA),
                    Triple("Y", "收藏", Neon.BtnY),
                    Triple("X", "游戏库", Neon.BtnX)
                ),
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .padding(bottom = 10.dp)
            )
        }
    }
}

/** 菜单行条目。 */
private data class NeonChip(
    val label: String,
    val icon: ImageVector,
    val accent: Color,
    val onClick: () -> Unit
)

/** 顶部品牌条：徽标 + 游戏总数 + 时钟。 */
@Composable
private fun NeonHomeTopBar(gameCount: Int) {
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
            .padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 品牌徽标（切角小牌）
        Row(
            modifier = Modifier
                .background(Neon.Accent.copy(alpha = 0.12f), neonChamfer(0.35f))
                .border(1.dp, Neon.Accent, neonChamfer(0.35f))
                .padding(horizontal = 12.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(7.dp)
        ) {
            Icon(Icons.Rounded.SportsEsports, null, tint = Neon.Accent, modifier = Modifier.size(17.dp))
            Text("NESSTATION", color = Neon.TextHi, fontSize = 14.sp, fontWeight = FontWeight.Black)
        }
        Spacer(Modifier.width(14.dp))
        Text(
            "$gameCount GAMES",
            color = Neon.Accent,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.sp
        )
        Spacer(Modifier.weight(1f))
        Text(clock, color = Neon.TextDim, fontSize = 13.sp, fontWeight = FontWeight.Medium)
    }
}

/** 菜单行：横滑切角图标胶囊。 */
@Composable
private fun NeonMenuRow(items: List<NeonChip>) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(9.dp)
    ) {
        items.forEach { chip ->
            val interaction = remember { MutableInteractionSource() }
            Row(
                modifier = Modifier
                    .background(Neon.BgPanel.copy(alpha = 0.62f), neonChamfer(0.30f))
                    .border(1.dp, Neon.Line, neonChamfer(0.30f))
                    .clickable(interactionSource = interaction, indication = null, onClick = chip.onClick)
                    .padding(horizontal = 13.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Icon(chip.icon, chip.label, tint = chip.accent, modifier = Modifier.size(15.dp))
                Text(chip.label, color = Neon.Text, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

/** 选中游戏信息面板：大标题 + 平台徽章 + 游玩数据 + 操作按钮。
 *  ★ 底部渐变遮罩：封面倒影无论怎么延伸都被压暗，游戏名/按钮永远清晰在前。 */
@Composable
private fun NeonGameInfoPanel(
    game: GameEntry,
    onPlay: () -> Unit,
    onOpenLibrary: () -> Unit,
    onToggleFavorite: () -> Unit,
    modifier: Modifier = Modifier
) {
    val title = game.customTitle?.takeIf { it.isNotBlank() } ?: game.title
    Box(
        modifier = modifier
            .padding(bottom = 8.dp)
            .background(
                Brush.verticalGradient(
                    0f to Color(0xB3050712),
                    0.4f to Color(0xE6050712),
                    1f to Color(0xF5050712)
                )
            )
    ) {
        Column(modifier = Modifier.padding(top = 6.dp)) {
        // 大标题 + 收藏星
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                title,
                color = Neon.TextHi,
                fontSize = 24.sp,
                fontWeight = FontWeight.Black,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false)
            )
            Spacer(Modifier.width(10.dp))
            Text(
                if (game.isFavorite) "★" else "☆",
                color = Neon.Gold,
                fontSize = 20.sp,
                modifier = Modifier.clickable(onClick = onToggleFavorite)
            )
        }
        Spacer(Modifier.height(5.dp))
        // 平台徽章 + 游玩信息
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .background(Neon.Accent.copy(alpha = 0.14f), neonChamfer(0.5f))
                    .border(1.dp, Neon.AccentDim, neonChamfer(0.5f))
                    .padding(horizontal = 9.dp, vertical = 3.dp)
            ) {
                Text(game.platform.displayName, color = Neon.Accent, fontSize = 10.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.width(10.dp))
            Text(
                "最近 ${neonFormatLastPlayed(game.lastPlayedAt)}",
                color = Neon.TextDim, fontSize = 11.sp, fontWeight = FontWeight.Medium
            )
            Spacer(Modifier.width(10.dp))
            Text(
                "时长 ${neonFormatPlayTime(game.playTimeMs)}",
                color = Neon.TextDim, fontSize = 11.sp, fontWeight = FontWeight.Medium
            )
        }
        Spacer(Modifier.height(11.dp))
        // 操作按钮
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            NeonPrimaryButton(
                text = if (game.lastPlayedAt > 0L) "继续游戏" else "开始游戏",
                icon = Icons.Rounded.PlayArrow,
                onClick = onPlay
            )
            NeonGhostButton("游戏库", Icons.Rounded.GridView, onOpenLibrary)
        }
        }
    }
}
