package com.nesstation.app.ui.eden

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.Face
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Nfc
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nesstation.app.core.model.GameEntry
import com.nesstation.app.core.model.GamePlatform

/**
 * ===========================================================================
 * ★★ v8/v9：Switch 系统小程序（applet）启动器 ★★
 * ===========================================================================
 *
 * 上游 Eden/yuzu 的 AppletLauncherFragment + AppletAdapter 的 Compose 版：
 * 从固件 NAND 里启动系统小程序（相册 / Amiibo 管理 / Mii 编辑器 / 主菜单）。
 *
 * 启动链路（与上游 AppletAdapter.onClick 逐字同构）：
 *   LibraryScreen / NeonLibraryScreen 工具行（Switch 页专属入口，v9 按用户
 *   反馈从「列表上方横幅」迁到 导入文件夹/刷新 同一工具行）
 *     → EdenAppletLauncherDialog（图标列表菜单；Cabinet 再选模式）
 *     → onLaunch(伪 GameEntry) → NesApp 路由 emulator/{game.id}
 *     → EmulatorScreen 启动 effect 检出 eden-applet:// 前缀
 *     → EdenEngine.loadApplet(appletId, entryId, cabinetMode)
 *     → NativeLibrary.setCurrentAppletId → getAppletLaunchPath → run(path)
 *
 * ★★ v9 路由安全 id（闪退修复的根因修复）★★
 *   v8 的伪条目 id 直接用了 romPath 本身（"eden-applet://album"）——
 *   Routes.emulator(id) = "emulator/$id" 不做 URL 编码，"//" 会被
 *   NavController 切成额外路径段、"?" 被当查询串 → navigate 找不到匹配
 *   目的地抛 IllegalArgumentException → 点任何小程序必闪退（用户实测）。
 *   现在 id（路由载体）与 romPath（引擎协议）分离：
 *     id      = "eden-applet-album" / "eden-applet-cabinet-m2"（单段、
 *               无 ? & / : —— 路由天然安全，无需依赖任何编码）
 *     romPath = "eden-applet://album" / "eden-applet://cabinet?mode=2"
 *               （保持 v8 原样，EdenApplets.parse / EmulatorScreen 零改动）
 *   NesApp 侧改用 parseRouteId(id) 还原 (key, mode)。
 *
 * AppletInfo / CabinetMode 取值逐字对照上游
 * core/eden/src/android/app/.../model/Applet.kt（entryId 为固件内 TitleID）。
 * ===========================================================================
 */
object EdenApplets {

    /** 可启动的小程序描述符。 */
    data class Desc(
        /** 伪条目 key（romPath 内 eden-applet://<key>） */
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
        val needsCabinetMode: Boolean,
        /** v9：菜单图标（material-icons-extended 已在依赖内） */
        val icon: ImageVector
    )

    val ALL: List<Desc> = listOf(
        Desc("album", 0x15, 0x010000000000100DL, "相册",
            "查看主机截图与相册小程序", false, Icons.Rounded.PhotoLibrary),
        Desc("cabinet", 0x0B, 0x0100000000001002L, "Amiibo 管理",
            "读写 Amiibo：改主人昵称 / 擦除 / 恢复 / 格式化", true, Icons.Rounded.Nfc),
        Desc("mii", 0x12, 0x0100000000001009L, "Mii 编辑器",
            "创建与编辑 Mii 形象", false, Icons.Rounded.Face),
        Desc("qlaunch", 0x03, 0x0100000000001000L, "主菜单",
            "Switch 主页界面（QLaunch）", false, Icons.Rounded.Home)
    )

    /** Cabinet 操作模式（上游 CabinetMode 枚举 0..3）。 */
    val CABINET_MODES: List<Triple<Int, String, String>> = listOf(
        Triple(0, "昵称与主人设置", "写入 / 修改 Amiibo 的主人昵称"),
        Triple(1, "游戏数据擦除", "删除 Amiibo 上的游戏存档数据"),
        Triple(2, "恢复出厂", "把 Amiibo 还原为出厂状态"),
        Triple(3, "格式化", "清空 Amiibo 全部数据（不可逆）")
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

    /**
     * ★★ v9：解析路由安全 id → (key, cabinetMode)。
     *   id 格式：eden-applet-<key> 或 eden-applet-cabinet-m<0..3>。
     *   非本格式返回 null（NesApp 继续走「未知游戏」兜底）。
     */
    fun parseRouteId(id: String): Pair<String, Int>? {
        if (!id.startsWith("eden-applet-")) return null
        val body = id.removePrefix("eden-applet-")
        // cabinet 带 -m<mode> 尾缀；其余 key 不含 '-'（见 ALL 定义）
        if (body.startsWith("cabinet-m")) {
            val mode = body.removePrefix("cabinet-m").toIntOrNull() ?: return null
            return "cabinet" to mode.coerceIn(0, 3)
        }
        val desc = ALL.firstOrNull { it.key == body && !it.needsCabinetMode } ?: return null
        return desc.key to 0
    }

    /** 按伪条目路径取出显示名（保留 v8 接口；romPath 侧使用）。 */
    fun titleFor(romPath: String): String? =
        romPath.takeIf { it.startsWith("eden-applet://") }
            ?.let { p -> ALL.firstOrNull { it.key == p.removePrefix("eden-applet://").substringBefore('?') }?.title }

    /** 按路由 id 取出显示名（v9：NesApp 未知条目兜底场景备用）。 */
    fun titleForRouteId(id: String): String? =
        parseRouteId(id)?.let { (key, mode) ->
            ALL.firstOrNull { it.key == key }?.let { d ->
                if (d.needsCabinetMode) {
                    "${d.title} · ${CABINET_MODES.firstOrNull { it.first == mode }?.second ?: ""}"
                } else d.title
            }
        }

    /**
     * 构造可直接走 emulator/{id} 路由的伪条目。
     * ★ v9：id 用路由安全格式（无 : / ?），romPath 保持 v8 引擎协议格式。
     */
    fun buildPseudoEntry(key: String, cabinetMode: Int = 0): GameEntry {
        val desc = ALL.first { it.key == key }
        val romPath = if (desc.needsCabinetMode) "eden-applet://$key?mode=$cabinetMode"
                   else "eden-applet://$key"
        val id = if (desc.needsCabinetMode) "eden-applet-$key-m${cabinetMode.coerceIn(0, 3)}"
              else "eden-applet-$key"
        val title = if (desc.needsCabinetMode) {
            val modeName = CABINET_MODES.firstOrNull { it.first == cabinetMode }?.second ?: ""
            "${desc.title} · $modeName"
        } else desc.title
        return GameEntry(
            id = id,
            title = title,
            romPath = romPath,
            platform = GamePlatform.NSWITCH
        )
    }
}

// ---------------------------------------------------------------------------
// 菜单 UI（v9 重做：图标 + 双行条目 + 分隔线的列表式菜单）
// ---------------------------------------------------------------------------

/** 图标底色（菜单条目统一视觉基调）。 */
private val AppletIconBg = Color(0xFF20344F)
private val AppletIconTint = Color(0xFFBFD8F7)
private val AppletTitleColor = Color(0xFFF2F6FA)
private val AppletDescColor = Color(0xFF8FA6BC)
private val AppletDividerColor = Color(0x1FFFFFFF)

/**
 * 单个小程序条目：圆角图标底 + 标题/描述双行 + 整行可点。
 */
@Composable
private fun AppletListRow(
    icon: ImageVector,
    title: String,
    description: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(38.dp)
                .background(AppletIconBg, RoundedCornerShape(10.dp)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                icon, contentDescription = title,
                tint = AppletIconTint, modifier = Modifier.size(22.dp)
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, color = AppletTitleColor, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(1.dp))
            Text(description, color = AppletDescColor, fontSize = 10.5.sp, lineHeight = 14.sp)
        }
    }
}

/** 条目间细分隔线。 */
@Composable
private fun AppletDivider() {
    Box(
        Modifier
            .fillMaxWidth()
            .height(0.5.dp)
            .background(AppletDividerColor)
    )
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
            containerColor = Color(0xFF16222F),
            titleContentColor = AppletTitleColor,
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Rounded.Nfc, contentDescription = null,
                        tint = AppletIconTint, modifier = Modifier.size(20.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("Amiibo 操作模式", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                }
            },
            text = {
                Column {
                    Text(
                        "Amiibo 管理一次只做一件事，操作会直接写入 Amiibo：" +
                            "进入小程序后把 Amiibo 贴到虚拟 NFC 位置即可。",
                        color = AppletDescColor, fontSize = 11.sp, lineHeight = 15.sp
                    )
                    Spacer(Modifier.height(6.dp))
                    EdenApplets.CABINET_MODES.forEachIndexed { idx, (mode, name, desc) ->
                        if (idx > 0) AppletDivider()
                        AppletListRow(
                            icon = Icons.Rounded.Nfc,
                            title = "$mode · $name",
                            description = desc,
                            onClick = {
                                val entry = EdenApplets.buildPseudoEntry("cabinet", mode)
                                cabinetPicked = null
                                onLaunch(entry)
                            }
                        )
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
        containerColor = Color(0xFF16222F),
        titleContentColor = AppletTitleColor,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Rounded.Apps, contentDescription = null,
                    tint = AppletIconTint, modifier = Modifier.size(20.dp)
                )
                Spacer(Modifier.width(8.dp))
                Column {
                    Text("Switch 小程序启动器", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    Text(
                        "系统内置小程序 · 需已安装官方固件",
                        color = AppletDescColor, fontSize = 10.sp, fontWeight = FontWeight.Normal
                    )
                }
            }
        },
        text = {
            Column {
                EdenApplets.ALL.forEachIndexed { idx, desc ->
                    if (idx > 0) AppletDivider()
                    AppletListRow(
                        icon = desc.icon,
                        title = desc.title,
                        description = desc.description,
                        onClick = {
                            if (desc.needsCabinetMode) {
                                // Cabinet：进模式选择层（entry 先占位，模式选择时重建）
                                cabinetPicked = EdenApplets.buildPseudoEntry(desc.key)
                            } else {
                                onLaunch(EdenApplets.buildPseudoEntry(desc.key))
                            }
                        }
                    )
                }
                Spacer(Modifier.height(2.dp))
                Text(
                    "未安装固件或缺少密钥时，启动会给出具体提示（不会黑屏）。",
                    color = AppletDescColor, fontSize = 9.5.sp, lineHeight = 12.sp
                )
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}
