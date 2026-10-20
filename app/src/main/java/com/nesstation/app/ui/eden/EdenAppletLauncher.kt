package com.nesstation.app.ui.eden

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nesstation.app.core.model.GameEntry
import com.nesstation.app.core.model.GamePlatform

/**
 * ===========================================================================
 * ★★ v8：Switch 系统小程序（applet）启动器 ★★
 * ===========================================================================
 *
 * 上游 Eden/yuzu 的 AppletLauncherFragment + AppletAdapter 的 Compose 版：
 * 从固件 NAND 里启动系统小程序（相册 / Amiibo 管理 / Mii 编辑器 / 主菜单）。
 *
 * 启动链路（与上游 AppletAdapter.onClick 逐字同构）：
 *   LibraryScreen / NeonLibraryScreen（Switch 页列表上方的入口）
 *     → EdenAppletLauncherDialog（选择小程序；Cabinet 再选模式）
 *     → onLaunch(伪 GameEntry) → NesApp 路由 emulator/{gameId}
 *     → EmulatorScreen 启动 effect 检出 eden-applet:// 前缀
 *     → EdenEngine.loadApplet(appletId, entryId, cabinetMode)
 *     → NativeLibrary.setCurrentAppletId → getAppletLaunchPath → run(path)
 *
 * 伪条目编码（romPath 即路由 id）：
 *   eden-applet://album           相册（PhotoViewer）
 *   eden-applet://mii             Mii 编辑器（MiiEdit）
 *   eden-applet://qlaunch         主菜单（QLaunch / Home）
 *   eden-applet://cabinet?mode=N  Amiibo 管理（Cabinet，N=CabinetMode 0..3）
 *
 * AppletInfo / CabinetMode 取值逐字对照上游
 * core/eden/src/android/app/.../model/Applet.kt（entryId 为固件内 TitleID）。
 * ===========================================================================
 */
object EdenApplets {

    /** 可启动的小程序描述符。 */
    data class Desc(
        /** 伪条目 key（eden-applet://<key>） */
        val key: String,
        /** Service::AM::Applets::AppletId（setCurrentAppletId 参数） */
        val appletId: Int,
        /** 固件内 TitleID（getAppletLaunchPath 参数） */
        val entryId: Long,
        /** 显示名 */
        val title: String,
        /** 说明文字 */
        val description: String,
        /** 是否 Cabinet（需要二次选择操作模式） */
        val needsCabinetMode: Boolean
    )

    val ALL: List<Desc> = listOf(
        Desc("album", 0x15, 0x010000000000100DL, "相册 (Album)",
            "查看主机截图（需固件内置相册小程序）", false),
        Desc("cabinet", 0x0B, 0x0100000000001002L, "Amiibo 管理 (Cabinet)",
            "读写 Amiibo 数据：改主人昵称 / 擦除 / 恢复 / 格式化", true),
        Desc("mii", 0x12, 0x0100000000001009L, "Mii 编辑器 (Mii Edit)",
            "创建 / 编辑 Mii 形象（需固件）", false),
        Desc("qlaunch", 0x03, 0x0100000000001000L, "主菜单 (QLaunch)",
            "Switch 主页界面（需固件）", false)
    )

    /** Cabinet 操作模式（上游 CabinetMode 枚举 0..3）。 */
    val CABINET_MODES: List<Pair<Int, String>> = listOf(
        0 to "昵称与主人设置（写入 Amiibo 主人信息）",
        1 to "游戏数据擦除（删除 Amiibo 存档）",
        2 to "恢复出厂（还原 Amiibo）",
        3 to "格式化（清空全部数据）"
    )

    /**
     * 解析伪条目路径 → (appletId, entryId, cabinetMode)。
     * 非法路径返回 null（调用方给出可操作错误提示）。
     */
    fun parse(romPath: String): Triple<Int, Long, Int>? {
        if (!romPath.startsWith("eden-applet://")) return null
        val body = romPath.removePrefix("eden-applet://")
        val key = body.substringBefore('?')
        val desc = ALL.firstOrNull { it.key == key } ?: return null
        val cabinetMode = if (desc.needsCabinetMode) {
            body.substringAfter("mode=", "0").toIntOrNull()?.coerceIn(0, 3) ?: 0
        } else -1
        return Triple(desc.appletId, desc.entryId, cabinetMode)
    }

    /** 按伪条目路径取出显示名（NesApp 路由侧构造 GameEntry 用）。 */
    fun titleFor(romPath: String): String? =
        romPath.takeIf { it.startsWith("eden-applet://") }
            ?.let { p -> ALL.firstOrNull { it.key == p.removePrefix("eden-applet://").substringBefore('?') }?.title }

    /** 构造可直接走 emulator/{id} 路由的伪条目（id 与 romPath 相同）。 */
    fun buildPseudoEntry(key: String, cabinetMode: Int = 0): GameEntry {
        val desc = ALL.first { it.key == key }
        val path = if (desc.needsCabinetMode) "eden-applet://$key?mode=$cabinetMode"
                   else "eden-applet://$key"
        val title = if (desc.needsCabinetMode) {
            val modeName = CABINET_MODES.firstOrNull { it.first == cabinetMode }?.second ?: ""
            "${desc.title} · ${modeName.substringBefore('（')}"
        } else desc.title
        return GameEntry(
            id = path,
            title = title,
            romPath = path,
            platform = GamePlatform.NSWITCH
        )
    }
}

/**
 * 小程序启动器对话框（FSD 游戏库 / Neon 游戏库共用）。
 *
 * @param onLaunch 用户点选小程序后回调（携带可直接启动的伪 GameEntry）。
 * @param onDismiss 关闭对话框。
 */
@Composable
fun EdenAppletLauncherDialog(
    onLaunch: (GameEntry) -> Unit,
    onDismiss: () -> Unit
) {
    // null = 首层小程序列表；非 null = Cabinet 模式选择层（值为目标 entry）
    var cabinetPicked by remember { mutableStateOf<GameEntry?>(null) }

    val picked = cabinetPicked
    if (picked != null) {
        // === Cabinet 模式选择（上游 CabinetLauncherDialogFragment 同款四项）===
        AlertDialog(
            onDismissRequest = { cabinetPicked = null },
            title = { Text("选择 Amiibo 操作模式") },
            text = {
                Column {
                    Text(
                        "Amiibo 管理一次只做一件事，操作会直接写入 Amiibo：" +
                            "进入小程序后把 Amiibo 贴到虚拟 NFC 位置即可。",
                        fontSize = 11.sp, lineHeight = 15.sp
                    )
                    EdenApplets.CABINET_MODES.forEach { (mode, name) ->
                        TextButton(
                            onClick = {
                                val entry = EdenApplets.buildPseudoEntry("cabinet", mode)
                                cabinetPicked = null
                                onLaunch(entry)
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("$mode · $name", fontSize = 12.sp)
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { cabinetPicked = null }) { Text("返回") }
            }
        )
        return
    }

    // === 首层：小程序列表 ===
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Switch 小程序启动器") },
        text = {
            Column {
                Text(
                    "启动 Switch 系统自带的小程序。需要已安装官方固件" +
                        "（小程序内置于固件；未安装时启动会给出提示）。",
                    fontSize = 11.sp, lineHeight = 15.sp
                )
                EdenApplets.ALL.forEach { desc ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 6.dp)
                    ) {
                        TextButton(
                            onClick = {
                                if (desc.needsCabinetMode) {
                                    // Cabinet：进模式选择层（entry 先占位，模式选择时重建）
                                    cabinetPicked = EdenApplets.buildPseudoEntry(desc.key)
                                } else {
                                    onLaunch(EdenApplets.buildPseudoEntry(desc.key))
                                }
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column {
                                Text(desc.title, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                                Text(desc.description, fontSize = 10.sp, lineHeight = 13.sp)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}
