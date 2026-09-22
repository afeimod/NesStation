package com.nesstation.app.ui.emulator

import android.app.Activity
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.SportsEsports
import androidx.compose.material.icons.rounded.WarningAmber
import com.nesstation.app.core.dc.FlycastLauncher
import com.nesstation.app.core.dc.FlycastPaths
import com.nesstation.app.core.model.GameEntry
import com.nesstation.app.core.storage.RomStore

/**
 * DC (Dreamcast / NAOMI / AtomisWave) —— Flycast 独立核心启动页。
 *
 * Flycast 与本工程其它进程内核心不同：它自带完整的运行循环、原生 ImGui
 * 设置菜单（游戏内按返回键呼出）、原生虚拟手柄、RetroAchievements、网络
 * 对战等全部功能，以独立 Activity（com.flycast.emulator.NativeGLActivity）
 * 形式接管游戏会话。本页面职责：
 *
 *  1. 启动前自检（ABI / ROM / BIOS / home 目录），问题给出可操作提示；
 *  2. 自动拉起 Flycast 游戏会话（首次进入自动启动一次）；
 *  3. 用户从游戏返回后停留在本页，可再次启动或退出；
 *  4. 会话时长累计到游戏库 playTimeMs（含在 Flycast 内的停留时间）。
 */
@Composable
fun FlycastLauncherScreen(
    game: GameEntry,
    onExit: () -> Unit
) {
    val context = LocalContext.current
    val activity = context as? Activity

    var launchError by remember { mutableStateOf<String?>(null) }
    var launchAttempted by remember { mutableStateOf(false) }
    // 会话时长累计：从首次组合到离开页面的总时长（近似游戏会话时长）
    var sessionStartMs by remember { mutableLongStateOf(0L) }
    // 启动次数变化令牌：再次点击启动时刷新
    var launchTick by remember { mutableIntStateOf(0) }

    // 返回键 = 退出启动页（回游戏库/主页）
    BackHandler { onExit() }

    // 首次进入自动启动一次（用户点游戏卡片即开始玩，符合其它平台的习惯）
    LaunchedEffect(game.id, launchTick) {
        if (!launchAttempted || launchTick > 0) {
            val act = activity
            if (act != null) {
                if (sessionStartMs == 0L) sessionStartMs = System.currentTimeMillis()
                launchError = FlycastLauncher.launch(act, game)
                launchAttempted = true
            } else {
                launchError = "无法获取宿主 Activity"
            }
        }
    }

    // 离开页面时结算会话时长
    androidx.compose.runtime.DisposableEffect(Unit) {
        onDispose {
            if (sessionStartMs > 0L) {
                val delta = System.currentTimeMillis() - sessionStartMs
                if (delta > 1000L) {
                    try { RomStore.addPlayTime(context, game.id, delta) } catch (_: Throwable) {}
                }
            }
        }
    }

    // ---- 自检状态 ----
    val abiOk = remember { FlycastLauncher.isAbiSupported() }
    val romOk = remember(game.id) { game.romPath?.isNotBlank() == true }
    val biosOk = remember(game.id) { FlycastPaths.hasConsoleBios(context) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(Color(0xFF0E1116), Color(0xFF141A22), Color(0xFF0E1116))
                )
            )
            .statusBarsPadding()
    ) {
        // 顶部栏
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onExit) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "返回",
                    tint = Color(0xFFEAF4FF))
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "Flycast · Dreamcast / NAOMI",
                    color = Color(0xFF9AA4B2),
                    fontSize = 11.sp,
                    letterSpacing = 1.5.sp
                )
                Text(
                    game.title,
                    color = Color(0xFFEAF4FF),
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            // 核心徽标
            Box(
                modifier = Modifier
                    .size(84.dp)
                    .background(
                        Brush.linearGradient(listOf(Color(0xFF1B6FE0), Color(0xFF0F4CA8))),
                        RoundedCornerShape(24.dp)
                    )
                    .border(1.dp, Color(0xFF3D8BFF).copy(alpha = 0.35f), RoundedCornerShape(24.dp)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Rounded.SportsEsports,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(44.dp)
                )
            }
            Spacer(Modifier.height(14.dp))
            Text("DC / Dreamcast", color = Color(0xFFEAF4FF), fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Text(
                "Flycast 独立核心 · v2.7",
                color = Color(0xFF7FC3FF),
                fontSize = 12.sp,
                modifier = Modifier.padding(top = 4.dp)
            )
            Spacer(Modifier.height(22.dp))

            // ---- 自检清单 ----
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF171C24), RoundedCornerShape(16.dp))
                    .border(1.dp, Color(0xFF2A3341), RoundedCornerShape(16.dp))
                    .padding(horizontal = 18.dp, vertical = 14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                StatusRow("64 位 (arm64-v8a) 设备", abiOk, "Flycast 核心仅提供 64 位库")
                StatusRow("ROM 已关联", romOk, "该游戏未关联 ROM 文件")
                StatusRow("Dreamcast BIOS (dc_boot / dc_flash)", biosOk,
                    "未检测到 —— 光盘游戏需要 BIOS；.zip 街机游戏（Naomi）无需")
            }

            launchError?.let { err ->
                Spacer(Modifier.height(14.dp))
                Text(
                    err,
                    color = Color(0xFFFF9C7A),
                    fontSize = 12.sp,
                    lineHeight = 17.sp
                )
            }

            Spacer(Modifier.height(22.dp))

            Button(
                onClick = {
                    sessionStartMs = System.currentTimeMillis()
                    launchTick++
                },
                enabled = abiOk && romOk,
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFFE74C3C),
                    contentColor = Color.White,
                    disabledContainerColor = Color(0xFF3A3F4A),
                    disabledContentColor = Color(0xFF79818F)
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp)
            ) {
                Icon(Icons.Rounded.SportsEsports, contentDescription = null, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text(if (launchAttempted) "重新启动游戏" else "启动游戏", fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.height(10.dp))
            OutlinedButton(
                onClick = onExit,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(46.dp)
            ) {
                Text("退出", color = Color(0xFF9AA4B2), fontSize = 14.sp)
            }

            Spacer(Modifier.height(20.dp))
            // 游戏内操作提示
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Info, contentDescription = null,
                    tint = Color(0xFF5B6675), modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(6.dp))
                Text(
                    "游戏内按返回键 = 打开 Flycast 菜单（设置/存档/手柄映射）",
                    color = Color(0xFF6B7787),
                    fontSize = 11.sp
                )
            }
        }
    }
}

@Composable
private fun StatusRow(label: String, ok: Boolean, failNote: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            if (ok) Icons.Rounded.CheckCircle else Icons.Rounded.WarningAmber,
            contentDescription = null,
            tint = if (ok) Color(0xFF6FE08C) else Color(0xFFFFC46B),
            modifier = Modifier.size(18.dp)
        )
        Spacer(Modifier.width(10.dp))
        Column {
            Text(
                if (ok) label else "$label —— $failNote",
                color = if (ok) Color(0xFFB9C4D3) else Color(0xFFFFC46B),
                fontSize = 12.sp,
                lineHeight = 16.sp
            )
        }
    }
}
