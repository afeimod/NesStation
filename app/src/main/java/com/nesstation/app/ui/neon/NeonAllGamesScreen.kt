package com.nesstation.app.ui.neon

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nesstation.app.core.model.GameEntry
import com.nesstation.app.core.model.GamePlatform
import com.nesstation.app.ui.components.AppBackgroundState

/**
 * ★★ Neon 总游戏库（图1 参考稿）—— 平台总览选择页 ★★
 *
 * 布局（横屏；竖屏上下堆叠）：
 *   - 左侧（约 58%）：复古 CRT 电视机造型预览舱 —— 金属灰外壳 + 屏幕区显示
 *     当前平台的游戏封面（1 大 + 2 小拼贴）+ 旋钮/扬声器格栅装饰 +
 *     “NESSTATION ARCADIA” 铭牌；电视下方是平台全称大标题（斜体红字，
 *     图1 的 “Nintendo Entertainment System” 样式）+ 年代副标题 + 数量。
 *   - 右侧（约 42%）：深色平台列表（图标 + 名称 + 数量徽标，青色选中边），
 *     点击进入该平台的核心内部游戏库（图2 弧形封面墙）。
 *   - 顶部：返回 + 标题「总游戏库 / ALL SYSTEMS」+ 总数。
 *   - 底部：手柄提示栏（A 进入 · B 返回）。
 */
@Composable
fun NeonAllGamesScreen(
    games: List<GameEntry>,
    onBack: () -> Unit,
    onOpenPlatform: (GamePlatform) -> Unit,
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
    val available = remember(games) {
        val byPlatform = games.groupBy { it.platform }
        platformOrder.mapNotNull { p -> byPlatform[p]?.let { p to it } }
    }

    var selected by remember { mutableStateOf(available.firstOrNull()?.first ?: GamePlatform.NES) }
    val selectedGames = remember(selected, games) {
        games.filter { it.platform == selected }.sortedByDescending { it.lastPlayedAt }
    }

    Box(modifier = modifier.fillMaxSize()) {
        if (!AppBackgroundState.active) NeonBackdrop()

        Column(modifier = Modifier.fillMaxSize()) {
            // ===== 顶部条 =====
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Rounded.ArrowBack, null, tint = Neon.TextHi)
                }
                Text("总游戏库", color = Neon.TextHi, fontSize = 19.sp, fontWeight = FontWeight.ExtraBold)
                Spacer(Modifier.width(8.dp))
                Text("ALL SYSTEMS", color = Neon.Accent, fontSize = 11.sp,
                    fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
                Spacer(Modifier.weight(1f))
                Text(
                    "${available.size} SYSTEMS · ${games.size} GAMES",
                    color = Neon.TextDim, fontSize = 11.sp, fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(end = 14.dp)
                )
            }

            // ===== 主体 =====
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                if (isPortrait) {
                    Column(modifier = Modifier.fillMaxSize()) {
                        Box(modifier = Modifier.fillMaxWidth().fillMaxHeight(0.42f)) {
                            NeonCrtPreview(selected, selectedGames, coverCache, Modifier.fillMaxSize())
                        }
                        NeonPlatformList(
                            available = available,
                            selected = selected,
                            onSelect = { selected = it },
                            onOpen = onOpenPlatform,
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                } else {
                    Row(modifier = Modifier.fillMaxSize()) {
                        Box(modifier = Modifier.fillMaxWidth(0.58f).fillMaxHeight()) {
                            NeonCrtPreview(selected, selectedGames, coverCache, Modifier.fillMaxSize())
                        }
                        NeonPlatformList(
                            available = available,
                            selected = selected,
                            onSelect = { selected = it },
                            onOpen = onOpenPlatform,
                            modifier = Modifier.fillMaxWidth().fillMaxHeight()
                        )
                    }
                }
            }

            NeonHintsBar(
                hints = listOf(
                    Triple("A", "进入平台", Neon.BtnA),
                    Triple("B", "返回", Neon.BtnB)
                ),
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .padding(bottom = 10.dp)
            )
        }
    }
}

/**
 * 左侧 CRT 电视预览舱（图1 主视觉）：
 * 外壳（金属灰渐变 + 圆角 + 扬声器格栅 + 旋钮）内嵌屏幕区显示平台封面拼贴；
 * 电视下方是斜体红字平台全称 + 年代 + 数量（图1 大标题样式）。
 */
@Composable
private fun NeonCrtPreview(
    platform: GamePlatform,
    games: List<GameEntry>,
    cache: android.util.LruCache<String, android.graphics.Bitmap>,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .padding(horizontal = 18.dp, vertical = 8.dp)
    ) {
        // ===== 电视机外壳 =====
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .background(
                    Brush.verticalGradient(
                        0f to Color(0xFF39424E),
                        0.08f to Color(0xFF2B333D),
                        1f to Color(0xFF1A2028)
                    ),
                    RoundedCornerShape(18.dp)
                )
                .border(2.dp, Color(0xFF4A5560), RoundedCornerShape(18.dp))
                .padding(14.dp)
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                // ===== 屏幕区（内嵌黑框 + 封面拼贴 + 扫描线感遮罩）=====
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .padding(4.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(0xFF020306))
                        .border(2.dp, Color(0xFF05070E), RoundedCornerShape(8.dp))
                ) {
                    if (games.isEmpty()) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text(
                                "NO CARTRIDGE", color = Neon.TextDim,
                                fontSize = 14.sp, fontWeight = FontWeight.Bold, letterSpacing = 3.sp
                            )
                        }
                    } else {
                        Row(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(8.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .weight(0.6f)
                                    .fillMaxHeight()
                                    .aspectRatio(0.75f, matchHeightConstraintsFirst = true)
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(Neon.BgPanelHi)
                            ) {
                                NeonCoverImage(games.first(), cache)
                            }
                            Column(
                                modifier = Modifier
                                    .weight(0.4f)
                                    .fillMaxHeight(),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                games.getOrNull(1)?.let {
                                    Box(
                                        modifier = Modifier
                                            .weight(1f)
                                            .fillMaxWidth()
                                            .clip(RoundedCornerShape(4.dp))
                                            .background(Neon.BgPanelHi)
                                    ) { NeonCoverImage(it, cache) }
                                }
                                games.getOrNull(2)?.let {
                                    Box(
                                        modifier = Modifier
                                            .weight(1f)
                                            .fillMaxWidth()
                                            .clip(RoundedCornerShape(4.dp))
                                            .background(Neon.BgPanelHi)
                                    ) { NeonCoverImage(it, cache) }
                                }
                            }
                        }
                        // CRT 扫描线感（低透明度横纹）
                        Column(modifier = Modifier.fillMaxSize()) {
                            repeat(24) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .weight(1f)
                                        .background(Color.Black.copy(alpha = 0.10f))
                                )
                                Box(modifier = Modifier.fillMaxWidth().height(2.dp))
                            }
                        }
                    }
                }

                Spacer(Modifier.height(10.dp))

                // ===== 电视底部控制条：铭牌 + 旋钮 + 扬声器格栅 =====
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "NESSTATION · ARCADIA",
                        color = Color(0xFF9AA7B4),
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Black,
                        letterSpacing = 2.sp
                    )
                    Spacer(Modifier.weight(1f))
                    // 旋钮 ×2
                    repeat(2) {
                        Box(
                            modifier = Modifier
                                .padding(horizontal = 4.dp)
                                .size(13.dp)
                                .background(Color(0xFF151A20), CircleShape)
                                .border(1.5.dp, Color(0xFF5A6672), CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(1.5.dp)
                                    .background(Color(0xFF8A97A4), CircleShape)
                            )
                        }
                    }
                    Spacer(Modifier.width(8.dp))
                    // 扬声器格栅
                    Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                        repeat(6) {
                            Box(
                                modifier = Modifier
                                    .size(width = 3.dp, height = 12.dp)
                                    .background(Color(0xFF10141A), RoundedCornerShape(1.dp))
                            )
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        // ===== 平台大标题（图1：斜体红字全称 + 年代）=====
        Text(
            platformFullName(platform),
            color = Neon.Red,
            fontSize = 24.sp,
            fontWeight = FontWeight.Black,
            fontStyle = FontStyle.Italic,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                platformEra(platform),
                color = Neon.TextDim,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 2.sp
            )
            Spacer(Modifier.width(10.dp))
            Box(Modifier.width(26.dp).height(1.5.dp).background(Neon.Accent))
            Spacer(Modifier.width(10.dp))
            Text(
                "${games.size} GAMES",
                color = Neon.Accent,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

/**
 * 右侧平台列表：图标 + 平台名 + 数量徽标；选中项青色边框 + 菱形指示；
 * 点击 = 选中并进入该平台游戏库（双击语义合并：单击直接进入）。
 */
@Composable
private fun NeonPlatformList(
    available: List<Pair<GamePlatform, List<GameEntry>>>,
    selected: GamePlatform,
    onSelect: (GamePlatform) -> Unit,
    onOpen: (GamePlatform) -> Unit,
    modifier: Modifier = Modifier
) {
    LazyColumn(
        modifier = modifier.padding(end = 16.dp, top = 4.dp, bottom = 4.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        items(available, key = { it.first.name }) { (platform, platformGames) ->
            val isSelected = platform == selected
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .graphicsLayer { alpha = if (isSelected) 1f else 0.82f }
                    .background(
                        if (isSelected) Neon.BgPanelHi else Neon.BgPanel.copy(alpha = 0.55f),
                        neonChamfer(0.2f)
                    )
                    .border(
                        1.dp,
                        if (isSelected) Neon.Accent else Neon.Line,
                        neonChamfer(0.2f)
                    )
                    .clickable {
                        onSelect(platform)
                        onOpen(platform)
                    }
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(9.dp)
                        .graphicsLayer { rotationZ = 45f }
                        .background(if (isSelected) Neon.Accent else Color.Transparent)
                )
                Spacer(Modifier.width(10.dp))
                Icon(
                    neonPlatformIcon(platform), null,
                    tint = if (isSelected) Neon.Accent else Neon.TextDim,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        platform.displayName,
                        color = if (isSelected) Neon.TextHi else Neon.Text,
                        fontSize = 14.sp,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                    )
                    Text(
                        platformEra(platform),
                        color = Neon.TextDim,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Medium,
                        letterSpacing = 1.sp
                    )
                }
                Text(
                    platformGames.size.toString(),
                    color = if (isSelected) Neon.Accent else Neon.TextDim,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}
