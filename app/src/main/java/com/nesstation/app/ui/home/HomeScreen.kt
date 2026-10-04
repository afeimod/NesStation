package com.nesstation.app.ui.home

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.nesstation.app.core.model.GameEntry
import com.nesstation.app.core.model.GamePlatform
import com.nesstation.app.ui.fsd.FsdHomeScreen
import com.nesstation.app.ui.neon.NeonHomeScreen
import com.nesstation.app.ui.neon.NeonUi

/**
 * 主页 —— 风格分发器。
 *
 * 总设置「外观 → 主界面风格」二选一：
 *   - FSD 经典：Xbox 360 Freestyle Dash 磁贴桌面（[FsdHomeScreen]，原风格不变）
 *   - Neon 3D：完全重制的新UI（[NeonHomeScreen]）——
 *     顶部菜单行 + 中央 3D 封面流 + 游戏信息区。
 *     ★ 主页【不含】核心列表 —— 核心列表在游戏库页（NeonLibraryScreen）。
 *
 * TV 模式不走本分发（TvNavHost 使用 TvHomeScreen）。
 */
@Composable
fun HomeScreen(
    games: List<GameEntry>,
    onOpenLibrary: () -> Unit,
    onOpenPlatform: (GamePlatform?) -> Unit,
    onOpenOnlineGames: () -> Unit,
    onOpenBattle: () -> Unit,
    onOpenSwf: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenAbout: () -> Unit,
    onExit: () -> Unit,
    modifier: Modifier = Modifier,
    onOpenFavorites: () -> Unit = {},
    onOpenHistory: () -> Unit = {},
    onOpenGame: (GameEntry) -> Unit = {},
    onGamesChanged: () -> Unit = {}
) {
    val context = LocalContext.current
    NeonUi.ensureLoaded(context)

    if (NeonUi.isNeon) {
        NeonHomeScreen(
            games = games,
            onOpenLibrary = onOpenLibrary,
            onOpenFavorites = onOpenFavorites,
            onOpenHistory = onOpenHistory,
            onOpenOnlineGames = onOpenOnlineGames,
            onOpenBattle = onOpenBattle,
            onOpenSwf = onOpenSwf,
            onOpenSettings = onOpenSettings,
            onOpenAbout = onOpenAbout,
            onExit = onExit,
            modifier = modifier,
            onOpenGame = onOpenGame,
            onGamesChanged = onGamesChanged
        )
    } else {
        FsdHomeScreen(
            games = games,
            onOpenLibrary = onOpenLibrary,
            onOpenPlatform = onOpenPlatform,
            onOpenOnlineGames = onOpenOnlineGames,
            onOpenBattle = onOpenBattle,
            onOpenSwf = onOpenSwf,
            onOpenSettings = onOpenSettings,
            onOpenAbout = onOpenAbout,
            onExit = onExit,
            modifier = modifier
        )
    }
}
