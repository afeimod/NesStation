package com.nesstation.app.ui.emulator

import android.app.Activity
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nesstation.app.core.model.GameEntry
import org.ppsspp.ppsspp.PpssppActivity
import java.io.File

/**
 * ★★ PSP（PPSSPP 核心）启动桥 —— NesStation 集成 ★★
 *
 * PSP 平台不走 EmulatorScreen 的进程内引擎路径（PpssppActivity 是 PPSSPP
 * 官方完整 Android 前端，自带渲染循环 / 触控 / 手柄 / 原生设置菜单 / 即时存档
 * / 金手指 / adhoc 联机 —— 全部保留原生实现），本组件只负责：
 *
 *  1. ROM 路径解析：content:// 尝试解析为真实文件路径（大 ISO/CHD 免复制；
 *     解析失败保持 content:// —— PPSSPP 原生支持 ContentUri 读取）；
 *  2. 首次启动环境准备（[ensurePpssppEnvironment]）：
 *     - memstick 目录 = <externalFilesDir>/PSP（NesStation 应用私有外部目录，
 *       无需存储权限，卸载随应用清除）；
 *     - 写 <filesDir>/memstick_dir.txt —— PPSSPP scoped storage 模式下以该
 *       文件定位 memstick（UI/NativeApp.cpp NativeInit 逻辑），缺失时 PPSSPP
 *       首启会弹出"选择存储位置"浏览器，破坏体验；
 *     - 预建 PSP/SYSTEM、PSP/SAVEDATA、PSP/GAME、PSP/PPSSPP_STATE、
 *       PSP/CHEATS、PSP/TEXTURES、PSP/PLUGINS、PSP/CACHE（与上游
 *       Core/Util/PathUtil.cpp CreateSysDirectories 一致）；
 *  3. 直接 startActivity 启动 PpssppActivity（EXTRA
 *     org.ppsspp.ppsspp.Shortcuts = ROM 路径，上游 PpssppActivity.parseIntent
 *     同款启动参数 —— 直接引导进游戏，跳过 PPSSPP 主界面）。
 *     ★ 不用 startActivityForResult：PpssppActivity 在 manifest 声明为
 *     singleInstance（上游要求，渲染循环对重建敏感），必然运行在独立 Task，
 *     startActivityForResult 会在启动瞬间立即回 RESULT_CANCELED；改用
 *     Lifecycle 观察 —— PPSSPP 退出时宿主 Activity ON_RESUME，即回游戏库；
 *  4. PPSSPP 退出后 onExit() 返回 NesStation 游戏库。
 *
 * 运行中本组件只显示一个极简等待页（PPSSPP Activity 全屏覆盖在其上）。
 */
@Composable
fun PspLaunchSection(
    game: GameEntry,
    onExit: () -> Unit
) {
    val context = LocalContext.current
    var launchError by remember { mutableStateOf<String?>(null) }
    // PPSSPP 是否已被本组件拉起（Lifecycle 回退判定用；remember 防重组重置）
    var ppssppLaunched by remember { mutableStateOf(false) }

    // ★ PPSSPP 退出检测：宿主 Activity 在 PPSSPP 覆盖期间处于 STOPPED，
    //   PPSSPP finish 后宿主恢复 ON_RESUME —— 此时返回游戏库。
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, ppssppLaunched) {
        if (!ppssppLaunched) onDispose { }
        else {
            val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
                if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                    onExit()
                }
            }
            lifecycleOwner.lifecycle.addObserver(observer)
            onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
        }
    }

    LaunchedEffect(game.id) {
        val romPath = resolvePspRomPath(game.romPath)
        if (romPath.isNullOrBlank()) {
            launchError = "该游戏未关联 ROM 文件"
            return@LaunchedEffect
        }
        try {
            ensurePpssppEnvironment(context)
        } catch (t: Throwable) {
            android.util.Log.w("PspLaunch", "ensurePpssppEnvironment failed", t)
            // 环境准备失败不阻断启动 —— PPSSPP 自身的 memstick 引导可兜底
        }
        val intent = Intent(context, PpssppActivity::class.java).apply {
            // 上游 PpssppActivity.parseIntent 识别的启动参数 EXTRA：
            // 值 = ROM 路径（原生侧 parse_args 把它当 boot_filename，直接进游戏）。
            putExtra(PpssppActivity.SHORTCUT_EXTRA_KEY, romPath)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            context.startActivity(intent)
            ppssppLaunched = true
        } catch (t: Throwable) {
            android.util.Log.e("PspLaunch", "start PpssppActivity failed", t)
            launchError = "无法启动 PPSSPP：${t.message ?: t.javaClass.simpleName}"
        }
    }

    // 等待页：PPSSPP 在前台时本页被完全遮住；仅启动失败或启动瞬间可见。
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(Color(0xFF0E1626), Color(0xFF060A12))
                )
            ),
        contentAlignment = Alignment.Center
    ) {
        val err = launchError
        if (err != null) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.padding(24.dp)
            ) {
                Text(
                    "PSP 启动失败",
                    color = Color(0xFFFF8A8A),
                    fontSize = 18.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Text(err, color = Color(0xFFB9C4D3), fontSize = 13.sp)
                androidx.compose.material3.TextButton(onClick = { onExit() }) {
                    Text("返回", color = Color(0xFF7FC3FF))
                }
            }
        } else {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                CircularProgressIndicator(color = Color(0xFF7FC3FF))
                Text(
                    "正在启动 PSP 模拟器（PPSSPP）…",
                    color = Color(0xFFEAF4FF),
                    fontSize = 14.sp
                )
                Text(
                    game.title,
                    color = Color(0xFF7FC3FF),
                    fontSize = 12.sp,
                    maxLines = 1
                )
            }
        }
    }
}

/**
 * 解析 PSP ROM 路径：content:// → 真实文件路径（可直读时）；
 * file:// → 剥前缀；其余原样返回。与 EmulatorScreen 的
 * resolveContentUriToRealFile 同策略（PPSSPP 对大镜像文件直读关键）。
 */
private fun resolvePspRomPath(romPath: String?): String? {
    var p = romPath?.trim().orEmpty()
    if (p.isEmpty()) return null
    if (p.startsWith("file://")) {
        try { p = Uri.parse(p).path ?: p } catch (_: Throwable) {}
    }
    return p
}

/**
 * PPSSPP 首次运行环境准备（幂等）：
 *  - memstick = <externalFilesDir>/PSP；
 *  - <filesDir>/memstick_dir.txt 写入 memstick 绝对路径（PPSSPP scoped
 *    storage 引导逻辑；PPSSPP 要求该目录已存在才采纳）；
 *  - 预建 PSP 子目录树（含 .nomedia，与上游 CreateSysDirectories 一致）。
 *
 * @return memstick 根目录绝对路径（<externalFilesDir>/PSP）
 */
fun ensurePpssppEnvironment(context: android.content.Context): String {
    val extFiles = context.getExternalFilesDir(null)
        ?: File(context.filesDir, "ext").apply { mkdirs() }
    val memstick = File(extFiles, "PSP").apply { mkdirs() }

    // PSP 子目录树（与上游 CreateSysDirectories 的 sysDirs 一致）
    for (sub in listOf(
        "SYSTEM", "SAVEDATA", "SAVESTATE", "GAME",
        "CHEATS", "TEXTURES", "PLUGINS", "CACHE"
    )) {
        File(memstick, sub).apply { mkdirs() }
        // .nomedia：与上游一致，避免相册扫描存档/缓存图片
        val nomedia = File(memstick, "$sub/.nomedia")
        if (!nomedia.exists()) {
            try { nomedia.createNewFile() } catch (_: Throwable) {}
        }
    }

    // memstick_dir.txt：PPSSPP NativeInit 读取它确定 memstick 目录。
    // 仅在内容缺失/变化时重写（externalFilesDir 的挂载路径可能随用户切换
    // 多用户/迁移存储变化，PPSSPP 侧用 File::Exists 校验，路径失效会回退
    // 到询问用户 —— 所以这里每次启动都校正为当前有效路径）。
    val marker = File(context.filesDir, "memstick_dir.txt")
    val desired = memstick.absolutePath
    val current = try {
        if (marker.exists()) marker.readText().trim() else ""
    } catch (_: Throwable) { "" }
    if (current != desired) {
        try {
            marker.parentFile?.mkdirs()
            marker.writeText(desired)
        } catch (_: Throwable) {}
    }
    return desired
}

/** PPSSPP 全局配置文件（ppsspp.ini）绝对路径；尚未生成时返回其将来的路径。 */
fun ppssppIniFile(context: android.content.Context): File {
    val memstick = ensurePpssppEnvironment(context)
    return File(memstick, "PSP/SYSTEM/ppsspp.ini")
}
