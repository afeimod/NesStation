package com.nesstation.app

import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.nesstation.app.ui.Routes
import com.nesstation.app.ui.components.AppBackgroundState
import com.nesstation.app.ui.components.AppGlobalBackground
import com.nesstation.app.ui.components.rememberAppBackgroundState
import com.nesstation.app.ui.theme.NesTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Edge-to-edge: content draws behind status bar and nav bar.
        enableEdgeToEdge()
        WindowCompat.setDecorFitsSystemWindows(window, false)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        setContent { Root() }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            // Immersive mode: hide status bar + navigation bar.
            // User can swipe to reveal them temporarily; they auto-hide.
            WindowInsetsControllerCompat(window, window.decorView).apply {
                hide(WindowInsetsCompat.Type.systemBars())
                systemBarsBehavior =
                    WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Re-apply immersive mode on resume (e.g. after returning from settings)
        window.decorView.post {
            WindowInsetsControllerCompat(window, window.decorView).apply {
                hide(WindowInsetsCompat.Type.systemBars())
                systemBarsBehavior =
                    WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        }
    }
}

@Composable
private fun Root() {
    val nav = rememberNavController()
    val backStackEntry by nav.currentBackStackEntryAsState()
    val route = backStackEntry?.destination?.route
    // 游戏 / SWF 播放器 / Web 游戏 / 对战房等全屏场景不叠加全局背景
    val immersiveDestination = route == Routes.EMULATOR ||
        route == Routes.SWF_PLAYER ||
        route == Routes.WEB_GAME ||
        route == Routes.BATTLE_MATCH

    NesTheme {
        androidx.compose.foundation.layout.Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
        ) {
            // 全局背景：设置里选择的图片 / 视频背景统一渲染在最底层，
            // 各页面在存在全局背景时跳过自己的背景层，让背景透出来。
            rememberAppBackgroundState()
            if (AppBackgroundState.active && !immersiveDestination) {
                AppGlobalBackground()
            }
            com.nesstation.app.ui.NesApp(nav = nav)
        }
    }
}
