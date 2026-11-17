package com.nesstation.app.ui.tv

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.nesstation.app.core.model.GameEntry
import com.nesstation.app.core.model.GamePlatform
import com.nesstation.app.ui.fsd.FsdHomeScreen

/**
 * TV 主页 — 与手机主页统一的 Xbox 360 FSD（Freestyle Dash）磁贴桌面。
 *
 * 视觉与交互实现在 [FsdHomeScreen]（ui/fsd 包）：
 *   - 蓝/黄对角磁贴封面流（全部游戏 / 各平台 / 在线游戏 / 对战 / 设置…）
 *   - 顶部 CPU/内存/存储状态条，底部 IP/状态/日期时间条
 *   - 磁贴流内建 D-pad 焦点导航：遥控器左右键切换磁贴、OK 键进入
 *
 * TV 上无需触摸长按等手势 —— 全部入口都通过磁贴 + D-pad 完成。
 */
@Composable
fun TvHomeScreen(
    games: List<GameEntry>,
    onOpenLibrary: () -> Unit,
    onOpenPlatform: (GamePlatform?) -> Unit,
    onOpenOnlineGames: () -> Unit,
    onOpenBattle: () -> Unit,
    onOpenSwf: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenAbout: () -> Unit,
    onExit: () -> Unit,
    modifier: Modifier = Modifier
) {
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
