package com.nesstation.app.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nesstation.app.core.dc.FlycastConfig
import com.nesstation.app.core.dc.FlycastPaths

/**
 * DC (Dreamcast / NAOMI / AtomisWave) —— Flycast 核心独立设置页。
 *
 * 与其它核心「设置存 PadLayout → 启动/运行时经 JNI 下发」的模式不同，
 * Flycast 是独立模拟器，其全部配置都在 <filesDir>/dc/emu.cfg（native 侧
 * core/cfg/ini.cpp 解析）。因此本页**直接读写 emu.cfg**：
 *
 *  · 所见即核心所得 —— 这里读的就是核心读的同一个文件；
 *  · 用户在游戏内 Flycast 原生菜单（返回键）改的设置同样写回 emu.cfg，
 *    与本页互不覆盖、双向同步；
 *  · 修改立即保存，下次启动游戏时生效（游戏内菜单可实时调整）。
 *
 * 键名/取值/默认值全部对照 Flycast 源码 core/cfg/option.cpp 的
 * Option(name, default, section) 定义（本工程 vendored 的 v2.7-119 版本）：
 *   · 长键名（Dreamcast.* / rend.* / aica.* / Sh4Clock / Dynarec.Enabled /
 *     pvr.rend 等）→ [config] 节；
 *   · 短键名（Enable / GGPO / UserName …）→ 独立节（[network] / [achievements]）。
 */
@Composable
fun FlycastCoreSettingsContent() {
    val context = LocalContext.current
    // 启动前兜底：确保 home 目录与偏好存在（正常已在 NesApp 初始化）
    remember { FlycastPaths.ensureHomePref(context); null }
    val config = remember { FlycastConfig.load(context) }
    // 保存令牌：每次变更 +1 触发整页重组，让所有下拉框读到新值
    var saveTick by remember { mutableIntStateOf(0) }

    fun put(key: String, value: String, section: String = FlycastConfig.CONFIG) {
        config.set(key, value, section)
        config.save()
        saveTick++
    }
    fun putInt(key: String, value: Int, section: String = FlycastConfig.CONFIG) =
        put(key, value.toString(), section)
    fun putBool(key: String, value: Boolean, section: String = FlycastConfig.CONFIG) =
        put(key, if (value) "yes" else "no", section)

    // saveTick 只作为重组 key 使用
    @Suppress("UNUSED_EXPRESSION")
    saveTick

    Column {
        // ============================ 画面 ============================
        SettingsSection("DC (Flycast) · 画面") {
            val renderer = config.getInt("pvr.rend", 0)
            DropdownRow("渲染器",
                listOf(
                    "0" to "OpenGL (默认)",
                    "4" to "Vulkan (硬件加速)",
                    "3" to "OpenGL 逐像素排序 (OIT, 精确但慢)",
                    "5" to "Vulkan 逐像素排序 (OIT, 精确但慢)"
                ),
                renderer.toString()
            ) { putInt("pvr.rend", it.toInt()) }

            val res = config.getInt("rend.Resolution", 480)
            DropdownRow("内部分辨率",
                listOf(
                    "240" to "0.5x (320x240)",
                    "480" to "1x (640x480 原生)",
                    "720" to "1.5x (960x720)",
                    "960" to "2x (1280x960)",
                    "1200" to "2.5x (1600x1200)",
                    "1440" to "3x (1920x1440)",
                    "1920" to "4x (2560x1920)",
                    "2160" to "4.5x (2880x2160 4K)",
                    "2880" to "6x (3840x2880 高配专用)",
                    "3840" to "8x (5120x3840 极高配)"
                ),
                res.toString()
            ) { putInt("rend.Resolution", it.toInt()) }

            DropdownRow("透明排序",
                listOf(
                    "no" to "按三角形 (快, 可能闪面)",
                    "yes" to "按条带 (较快, 更稳定)"
                ),
                if (config.getBool("rend.PerStripSorting", false)) "yes" else "no"
            ) { putBool("rend.PerStripSorting", it == "yes") }

            DropdownRow("宽屏 16:9 补丁",
                listOf("no" to "关闭 (4:3 原生)", "yes" to "开启 (16:9)"),
                if (config.getBool("rend.WideScreen", false)) "yes" else "no"
            ) { putBool("rend.WideScreen", it == "yes") }

            DropdownRow("超宽屏拉伸",
                listOf("no" to "关闭", "yes" to "开启 (跟随屏幕比例)"),
                if (config.getBool("rend.SuperWideScreen", false)) "yes" else "no"
            ) { putBool("rend.SuperWideScreen", it == "yes") }

            DropdownRow("宽屏游戏兼容补丁",
                listOf("no" to "关闭", "yes" to "开启 (部分游戏 16:9 无拉伸)"),
                if (config.getBool("rend.WidescreenGameHacks", false)) "yes" else "no"
            ) { putBool("rend.WidescreenGameHacks", it == "yes") }

            DropdownRow("整数缩放",
                listOf("no" to "关闭 (默认)", "yes" to "开启 (像素完美, 无变形)"),
                if (config.getBool("rend.IntegerScale", false)) "yes" else "no"
            ) { putBool("rend.IntegerScale", it == "yes") }

            DropdownRow("线性插值",
                listOf("yes" to "开启 (默认平滑)", "no" to "关闭 (最近邻锐利)"),
                if (config.getBool("rend.LinearInterpolation", true)) "yes" else "no"
            ) { putBool("rend.LinearInterpolation", it == "yes") }

            DropdownRow("垂直同步 (VSync)",
                listOf("yes" to "开启 (推荐)", "no" to "关闭"),
                if (config.getBool("rend.vsync", true)) "yes" else "no"
            ) { putBool("rend.vsync", it == "yes") }

            DropdownRow("帧同步 (Frame Pacing)",
                listOf("yes" to "开启 (实验性, 还原原机节奏)", "no" to "关闭"),
                if (config.getBool("rend.FramePacing", true)) "yes" else "no"
            ) { putBool("rend.FramePacing", it == "yes") }

            DropdownRow("延迟帧交换",
                listOf("yes" to "开启 (安卓默认, 防撕裂)", "no" to "关闭 (低延迟)"),
                if (config.getBool("rend.DelayFrameSwapping", true)) "yes" else "no"
            ) { putBool("rend.DelayFrameSwapping", it == "yes") }

            DropdownRow("显示帧率",
                listOf("no" to "关闭", "yes" to "开启 (画面左上角)"),
                if (config.getBool("rend.ShowFPS", false)) "yes" else "no"
            ) { putBool("rend.ShowFPS", it == "yes") }

            DropdownRow("显示 VMU 小屏幕",
                listOf("yes" to "开启 (游戏内右上角)", "no" to "关闭"),
                if (config.getBool("rend.FloatVMUs", false)) "yes" else "no"
            ) { putBool("rend.FloatVMUs", it == "yes") }

            val texUpscale = config.getInt("rend.TextureUpscale2", 1)
            DropdownRow("纹理放大 (xBRZ)",
                listOf(
                    "1" to "1x 关闭 (默认)",
                    "2" to "2x",
                    "4" to "4x (吃性能)",
                    "6" to "6x (极高配)"
                ),
                texUpscale.toString()
            ) { putInt("rend.TextureUpscale2", it.toInt()) }

            val texFilter = config.getInt("rend.TextureFiltering", 0)
            DropdownRow("纹理过滤",
                listOf(
                    "0" to "默认 (跟随游戏)",
                    "1" to "强制最近邻 (像素风)",
                    "2" to "强制线性 (平滑)",
                    "4" to "线性 Mipmap"
                ),
                texFilter.toString()
            ) { putInt("rend.TextureFiltering", it.toInt()) }

            val aniso = config.getInt("rend.AnisotropicFiltering", 1)
            DropdownRow("各向异性过滤",
                listOf("1" to "关闭", "2" to "2x", "4" to "4x", "8" to "8x", "16" to "16x"),
                aniso.toString()
            ) { putInt("rend.AnisotropicFiltering", it.toInt()) }

            DropdownRow("雾效",
                listOf("yes" to "开启 (默认)", "no" to "关闭 (提速)"),
                if (config.getBool("rend.Fog", true)) "yes" else "no"
            ) { putBool("rend.Fog", it == "yes") }

            val stretch = config.getInt("rend.ScreenStretching", 100)
            DropdownRow("屏幕拉伸",
                listOf(
                    "90" to "90%", "95" to "95%", "100" to "100% (默认)",
                    "105" to "105%", "110" to "110%", "120" to "120%", "130" to "130%"
                ),
                stretch.toString()
            ) { putInt("rend.ScreenStretching", it.toInt()) }

            DropdownRow("自动跳帧",
                listOf(
                    "0" to "关闭 (默认)",
                    "1" to "轻度 (部分跳帧)",
                    "2" to "中度 (更多跳帧)",
                    "3" to "重度 (尽量保帧率)"
                ),
                config.getInt("pvr.AutoSkipFrame", 0).toString()
            ) { putInt("pvr.AutoSkipFrame", it.toInt()) }

            DropdownRow("线程渲染",
                listOf("yes" to "开启 (默认, 推荐)", "no" to "关闭 (排查兼容问题)"),
                if (config.getBool("rend.ThreadedRendering", true)) "yes" else "no"
            ) { putBool("rend.ThreadedRendering", it == "yes") }

            DropdownRow("全帧缓冲模拟",
                listOf("no" to "关闭 (默认)", "yes" to "开启 (特殊特效游戏, 很慢)"),
                if (config.getBool("rend.EmulateFramebuffer", false)) "yes" else "no"
            ) { putBool("rend.EmulateFramebuffer", it == "yes") }

            DropdownRow("自定义 Adreno GPU 驱动",
                listOf("no" to "关闭 (默认)", "yes" to "开启 (rend.CustomGpuDriver)"),
                if (config.getBool("rend.CustomGpuDriver", false)) "yes" else "no"
            ) { putBool("rend.CustomGpuDriver", it == "yes") }
        }

        // ============================ 音频 ============================
        SettingsSection("DC (Flycast) · 音频") {
            DropdownRow("AICA DSP 音效",
                listOf("no" to "关闭 (默认)", "yes" to "开启 (个别游戏音效需要)"),
                if (config.getBool("aica.DSPEnabled", false)) "yes" else "no"
            ) { putBool("aica.DSPEnabled", it == "yes") }

            val buf = config.getInt("aica.BufferSize", 5644)
            DropdownRow("音频缓冲",
                listOf(
                    "1024" to "23ms (低延迟, 可能爆音)",
                    "2048" to "46ms",
                    "2822" to "64ms",
                    "5644" to "128ms (默认, 稳定)"
                ),
                buf.toString()
            ) { putInt("aica.BufferSize", it.toInt()) }

            DropdownRow("自动延迟调节",
                listOf("yes" to "开启 (安卓默认)", "no" to "关闭"),
                if (config.getBool("aica.AutoLatency", true)) "yes" else "no"
            ) { putBool("aica.AutoLatency", it == "yes") }

            DropdownRow("VMU 蜂鸣声",
                listOf("no" to "关闭 (默认)", "yes" to "开启"),
                if (config.getBool("VmuSound", false, "audio")) "yes" else "no"
            ) { putBool("VmuSound", it == "yes", "audio") }
        }

        // ============================ 主机 ============================
        SettingsSection("DC (Flycast) · 主机 (BIOS 设置)") {
            val region = config.getInt("Dreamcast.Region", 1)
            DropdownRow("主机区域",
                listOf(
                    "0" to "日本",
                    "1" to "美国 (默认)",
                    "2" to "欧洲",
                    "3" to "默认 (跟随游戏)"
                ),
                region.toString()
            ) { putInt("Dreamcast.Region", it.toInt()) }

            val broadcast = config.getInt("Dreamcast.Broadcast", 0)
            DropdownRow("电视制式",
                listOf(
                    "0" to "NTSC (默认)",
                    "1" to "PAL",
                    "2" to "PAL-M",
                    "3" to "PAL-N",
                    "4" to "默认"
                ),
                broadcast.toString()
            ) { putInt("Dreamcast.Broadcast", it.toInt()) }

            val language = config.getInt("Dreamcast.Language", 1)
            DropdownRow("主机语言",
                listOf(
                    "0" to "日语",
                    "1" to "英语 (默认)",
                    "2" to "德语",
                    "3" to "法语",
                    "4" to "西班牙语",
                    "5" to "意大利语",
                    "6" to "默认"
                ),
                language.toString()
            ) { putInt("Dreamcast.Language", it.toInt()) }

            val cable = config.getInt("Dreamcast.Cable", 3)
            DropdownRow("视频输出 (Cable)",
                listOf(
                    "0" to "VGA",
                    "2" to "RGB 分量",
                    "3" to "TV 复合 (默认)"
                ),
                cable.toString()
            ) { putInt("Dreamcast.Cable", it.toInt()) }

            DropdownRow("32MB 内存改机",
                listOf("no" to "关闭 (16MB 原生)", "yes" to "开启 (个别游戏/补丁需要)"),
                if (config.getBool("Dreamcast.RamMod32MB", false)) "yes" else "no"
            ) { putBool("Dreamcast.RamMod32MB", it == "yes") }

            DropdownRow("自动读档 (启动时)",
                listOf("no" to "关闭 (默认)", "yes" to "开启 (自动载入最近存档)"),
                if (config.getBool("Dreamcast.AutoLoadState", false)) "yes" else "no"
            ) { putBool("Dreamcast.AutoLoadState", it == "yes") }

            DropdownRow("自动存档 (退出时)",
                listOf("no" to "关闭 (默认)", "yes" to "开启 (退出自动存档)"),
                if (config.getBool("Dreamcast.AutoSaveState", false)) "yes" else "no"
            ) { putBool("Dreamcast.AutoSaveState", it == "yes") }

            DropdownRow("快速 GD-ROM 读取",
                listOf("no" to "关闭 (默认)", "yes" to "开启 (缩短读盘时间)"),
                if (config.getBool("FastGDRomLoad", false)) "yes" else "no"
            ) { putBool("FastGDRomLoad", it == "yes") }

            DropdownRow("HLE BIOS (免真实 BIOS)",
                listOf("no" to "关闭 (默认)", "yes" to "开启 (部分游戏可无 BIOS 启动)"),
                if (config.getBool("UseReios", false)) "yes" else "no"
            ) { putBool("UseReios", it == "yes") }
        }

        // ============================ CPU / 性能 ============================
        SettingsSection("DC (Flycast) · CPU / 性能") {
            DropdownRow("CPU 模式",
                listOf(
                    "yes" to "动态重编译 Dynarec (默认, 快)",
                    "no" to "解释器 Interpreter (精确, 慢)"
                ),
                if (config.getBool("Dynarec.Enabled", true)) "yes" else "no"
            ) { putBool("Dynarec.Enabled", it == "yes") }

            val clock = config.getInt("Sh4Clock", 200)
            DropdownRow("SH4 主频",
                listOf(
                    "100" to "100MHz (降频省电)",
                    "150" to "150MHz",
                    "200" to "200MHz (原生默认)",
                    "250" to "250MHz (超频)",
                    "300" to "300MHz (超频, 个别游戏加速)"
                ),
                clock.toString()
            ) { putInt("Sh4Clock", it.toInt()) }
        }

        // ============================ 网络 ============================
        SettingsSection("DC (Flycast) · 网络") {
            DropdownRow("网络功能",
                listOf("no" to "关闭 (默认)", "yes" to "开启 (DC 拨号/NAOMI 联机)"),
                if (config.getBool("Enable", false, "network")) "yes" else "no"
            ) { putBool("Enable", it == "yes", "network") }

            DropdownRow("模拟 BBA 宽带适配器",
                listOf("no" to "关闭 (默认, 使用调制解调器)", "yes" to "开启"),
                if (config.getBool("EmulateBBA", false, "network")) "yes" else "no"
            ) { putBool("EmulateBBA", it == "yes", "network") }

            DropdownRow("作为联机主机 (服务器)",
                listOf("no" to "关闭 (默认)", "yes" to "开启 (NAOMI 多机互联主机端)"),
                if (config.getBool("ActAsServer", false, "network")) "yes" else "no"
            ) { putBool("ActAsServer", it == "yes", "network") }

            DropdownRow("GGPO 网络对战",
                listOf("no" to "关闭 (默认)", "yes" to "开启 (GGPO 回滚联机)"),
                if (config.getBool("GGPO", false, "network")) "yes" else "no"
            ) { putBool("GGPO", it == "yes", "network") }
        }

        // ============================ 成就 ============================
        SettingsSection("DC (Flycast) · RetroAchievements 成就") {
            Text(
                "在 retroachievements.org 注册账号后填入用户名与 API Key（官网 " +
                "Settings → Keys 页生成）。开启后游戏内自动登录并解锁成就。",
                color = Color(0xFF4A5568), fontSize = 10.sp, lineHeight = 14.sp)
            SwitchRowFlycast(
                label = "成就功能",
                description = "连接 retroachievements.org",
                checked = config.getBool("Enabled", false, "achievements")
            ) { putBool("Enabled", it, "achievements") }
            SwitchRowFlycast(
                label = "硬核模式 (Hardcore)",
                description = "禁用快进/金手指/读档才能解锁成就",
                checked = config.getBool("HardcoreMode", false, "achievements")
            ) { putBool("HardcoreMode", it, "achievements") }
            TextRowFlycast(
                label = "用户名",
                value = config.get("UserName", "achievements") ?: ""
            ) { put("UserName", it, "achievements") }
            TextRowFlycast(
                label = "API Key (Token)",
                value = config.get("Token", "achievements") ?: ""
            ) { put("Token", it, "achievements") }
        }

        // ============================ BIOS 管理 ============================
        SettingsSection("DC · BIOS 管理") {
            Text(
                "Dreamcast 光盘游戏 (.gdi/.cdi/.cue/.chd/.iso) 需要真实 BIOS：" +
                "dc_boot.bin (BIOS ROM) + dc_flash.bin (闪存)。" +
                "NAOMI / AtomisWave 街机游戏 (.zip) 自带 BIOS，无需导入。\n" +
                "也可私有打包：把文件放入 app/src/main/assets/dc/ 重新构建，启动时自动识别。\n" +
                "VMU 存档与即时存档自动保存在 data/ 目录，游戏内按返回键 → Flycast 菜单可存读即时存档。",
                color = Color(0xFF4A5568), fontSize = 10.sp, lineHeight = 14.sp)
            FlycastBiosImportSection()
        }
    }
}

// ---------------------------------------------------------------------------
// Flycast BIOS 导入（dc_boot.bin / dc_flash.bin → <filesDir>/dc/data/）
// 模式与 EmulatorScreen.Psx2BiosImportSection 一致：SAF 选文件 → 按文件名
// 自动识别归属 → 复制落盘 → 状态回显。
// ---------------------------------------------------------------------------
@Composable
fun FlycastBiosImportSection() {
    val context = LocalContext.current
    val dataDir = remember { FlycastPaths.dataDir(context) }
    var statusText by remember { mutableStateOf("") }
    var refreshKey by remember { mutableIntStateOf(0) }

    LaunchedEffect(refreshKey) {
        statusText = buildString {
            val boot = FlycastPaths.bootBios(context)
            val flash = FlycastPaths.flashBios(context)
            append(if (boot != null) "✓ dc_boot.bin (${boot.length() / 1024}KB)\n"
                   else "✗ 未检测到 dc_boot.bin（BIOS ROM，必需）\n")
            append(if (flash != null) "✓ dc_flash.bin (${flash.length() / 1024}KB)\n"
                   else "✗ 未检测到 dc_flash.bin（闪存，必需）\n")
            append("\n目录: ${dataDir.absolutePath}")
        }
    }

    val biosPickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            var origName = ""
            try {
                context.contentResolver.query(uri, null, null, null, null)?.use { c ->
                    val idx = c.getColumnIndex(
                        android.provider.DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                    if (idx >= 0 && c.moveToFirst()) {
                        val n = c.getString(idx)
                        if (!n.isNullOrBlank()) origName = n
                    }
                }
            } catch (_: Exception) { }
            if (origName.isBlank()) {
                origName = uri.lastPathSegment?.let { android.net.Uri.decode(it) }
                    ?.substringAfterLast('/')?.substringAfterLast(':') ?: "dc_boot.bin"
            }

            val msg: String = try {
                // 按文件名自动归类（与 GenesisBiosImportSection 的自动命名同思路）：
                //   含 "flash" → dc_flash.bin；含 "boot"/"bios" → dc_boot.bin；
                //   其余保留原名（用户可手动改名后再导入）。
                val lower = origName.lowercase()
                val targetName = when {
                    lower.contains("flash") -> "dc_flash.bin"
                    lower.contains("boot") || lower.contains("bios") -> "dc_boot.bin"
                    else -> origName
                }
                val dest = java.io.File(dataDir, targetName)
                context.contentResolver.openInputStream(uri)?.use { input ->
                    dest.outputStream().use { output -> input.copyTo(output) }
                }
                "已导入 BIOS: ${dest.name} (${dest.length() / 1024}KB)"
            } catch (e: Exception) {
                "导入失败: ${e.message}"
            }
            refreshKey++
            statusText = msg
        }
    }

    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(
            statusText,
            color = Color(0xFF88DD88),
            fontSize = 11.sp,
            lineHeight = 14.sp,
            modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp)
        )
        androidx.compose.material3.Button(
            onClick = { biosPickerLauncher.launch(arrayOf("*/*")) },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("导入 BIOS 文件 (dc_boot.bin / dc_flash.bin)", fontSize = 12.sp)
        }
    }
}

// ---------------------------------------------------------------------------
// 开关 / 文本输入行（本文件专用；与 DropdownRow 风格保持一致）
// ---------------------------------------------------------------------------
@Composable
private fun SwitchRowFlycast(
    label: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(label, color = Color(0xFF1E2A3A), fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Text(description, color = Color(0xFF6B7787), fontSize = 11.sp)
        }
        androidx.compose.material3.Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = androidx.compose.material3.SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = Color(0xFFE74C3C)
            )
        )
    }
}

@Composable
private fun TextRowFlycast(
    label: String,
    value: String,
    onCommit: (String) -> Unit
) {
    var text by remember(value) { mutableStateOf(value) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Text(label, color = Color(0xFF1E2A3A), fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        androidx.compose.material3.OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            singleLine = true,
            textStyle = androidx.compose.material3.MaterialTheme.typography.bodySmall,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp),
            trailingIcon = {
                androidx.compose.material3.TextButton(onClick = { onCommit(text.trim()) }) {
                    Text("保存", fontSize = 12.sp)
                }
            }
        )
    }
}
