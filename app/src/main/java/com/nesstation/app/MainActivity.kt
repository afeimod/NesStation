package com.nesstation.app

import android.Manifest
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
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

    companion object {
        private const val PREFS = "nesstation_prefs"
        private const val KEY_STORAGE_PERMISSION_ASKED = "storage_permission_asked_v1"
    }

    private lateinit var prefs: SharedPreferences

    /** Android 6.0..9 / 10：运行时 READ/WRITE_EXTERNAL_STORAGE 授权结果。 */
    private val legacyPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            val granted = result.values.any { it }
            android.util.Log.i("MainActivity",
                "Legacy storage permissions ${if (granted) "granted" else "denied"}")
        }

    /** Android 11+：跳转「所有文件访问」系统页后返回。 */
    private val allFilesAccessLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            val manager = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                Environment.isExternalStorageManager()
            } else false
            android.util.Log.i("MainActivity",
                "All-files-access page returned, isExternalStorageManager=$manager")
        }

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
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        maybeAskStoragePermissionOnFirstLaunch()
        setContent { Root() }
    }

    /**
     * 首次启动提示并引导存储权限。
     *
     * 为什么需要：游戏库直接扫描 ROM 目录（设置里也允许把 ROM 放到
     * 公共目录 NesStation/ 下），没有「所有文件访问」/读存储权限时
     * 扫描结果为空。通过系统文件选择器导入不受影响，但体验差一截。
     *
     * 流程（只弹一次，之后可在 设置 → 系统 里重新授权）：
     *   1. Android 11+ (R)：先弹解释对话框 → 跳系统「所有文件访问」开关页
     *   2. Android 6..10   ：先弹解释对话框 → 运行时请求读写存储权限
     *   3. Android 5 及以下：清单权限即装即得，无需申请
     * 用户拒绝也不阻塞使用（仍可用 SAF 导入），标记一次性标记位避免骚扰。
     */
    private fun maybeAskStoragePermissionOnFirstLaunch() {
        val alreadyAsked = prefs.getBoolean(KEY_STORAGE_PERMISSION_ASKED, false)
        if (alreadyAsked) return
        // 标记先写：无论用户在系统页里怎么选，都不再自动弹第二次
        //（想重新授权走 设置 → 系统 → 存储权限）。
        prefs.edit().putBoolean(KEY_STORAGE_PERMISSION_ASKED, true).apply()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (Environment.isExternalStorageManager()) return  // 已授权，无需打扰
            AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("需要存储权限")
                .setMessage(
                    "NesStation 需要读取存储来扫描你的游戏 ROM 目录。\n\n" +
                    "点击「去授权」后在下一个页面打开「允许访问所有文件」开关。\n" +
                    "（也可以稍后在 设置 → 系统 → 存储权限 中开启）"
                )
                .setPositiveButton("去授权") { _, _ ->
                    try {
                        val intent = Intent(
                            Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                            Uri.parse("package:$packageName")
                        )
                        allFilesAccessLauncher.launch(intent)
                    } catch (_: Exception) {
                        try {
                            allFilesAccessLauncher.launch(
                                Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                            )
                        } catch (e: Exception) {
                            android.util.Log.w("MainActivity",
                                "Cannot open all-files-access settings", e)
                        }
                    }
                }
                .setNegativeButton("暂不") { dialog, _ -> dialog.dismiss() }
                .setCancelable(true)
                .show()
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val needed = arrayOf(
                Manifest.permission.READ_EXTERNAL_STORAGE,
                Manifest.permission.WRITE_EXTERNAL_STORAGE
            )
            val anyMissing = needed.any {
                checkSelfPermission(it) != android.content.pm.PackageManager.PERMISSION_GRANTED
            }
            if (!anyMissing) return
            AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("需要存储权限")
                .setMessage(
                    "NesStation 需要读写存储权限来扫描和管理你的游戏 ROM。\n\n" +
                    "点击「去授权」在系统弹窗中选择「允许」。\n" +
                    "（也可以稍后在 设置 → 系统 → 存储权限 中开启）"
                )
                .setPositiveButton("去授权") { _, _ ->
                    legacyPermissionLauncher.launch(needed)
                }
                .setNegativeButton("暂不") { dialog, _ -> dialog.dismiss() }
                .setCancelable(true)
                .show()
        }
        // API < 23: install-time permissions, nothing to do.
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
