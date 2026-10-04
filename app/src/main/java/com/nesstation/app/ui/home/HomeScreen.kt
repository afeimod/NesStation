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
 * 主页 —— 风格分发器（本轮新增 Neon 风格切换）。
 *
 * 总设置「外观 → 主界面风格」二选一：
 *   - FSD 经典：Xbox 360 Freestyle Dash 磁贴桌面（[FsdHomeScreen]）
 *   - Neon 新UI：赛博街机厅风格（[NeonHomeScreen]，图3 参考稿）——
 *     左侧 Hero 预览 + 右侧分区菜单 + 底部手柄提示栏，
 *     菜单含 全部游戏/收藏/最近/各平台/在线/对战(4P)/SWF/设置/关于/退出。
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
    onOpenHistory: () -> Unit = {}
) {
    val context = LocalContext.current
    NeonUi.ensureLoaded(context)

    if (NeonUi.isNeon) {
        NeonHomeScreen(
            games = games,
            onOpenAllGames = onOpenLibrary,
            onOpenFavorites = onOpenFavorites,
            onOpenHistory = onOpenHistory,
            onOpenPlatform = { p -> onOpenPlatform(p) },
            onOpenOnlineGames = onOpenOnlineGames,
            onOpenBattle = onOpenBattle,
            onOpenSwf = onOpenSwf,
            onOpenSettings = onOpenSettings,
            onOpenAbout = onOpenAbout,
            onExit = onExit,
            modifier = modifier
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
