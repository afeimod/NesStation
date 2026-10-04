package com.nesstation.app.ui.neon

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings as AndroidSettings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.Gamepad
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.Percent
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nesstation.app.core.model.GamePlatform
import com.nesstation.app.core.storage.PadLayout
import com.nesstation.app.core.storage.PadLayoutStore
import com.nesstation.app.core.engine.NesEngine
import com.nesstation.app.core.engine.SnesEngine
import com.nesstation.app.core.engine.GbaEngine
import com.nesstation.app.ui.components.AppBackgroundState
import com.nesstation.app.ui.settings.CoreSettingsPanel

/**
 * ★★ Neon 3D 总设置（完全重制版）★★
 *
 * 结构：左侧分类导航（外观/视频/显示/性能/输入/存储/核心设置/关于）+
 * 右侧设置面板（Neon 深色切角卡片）。核心子页复用 [CoreSettingsPanel]
 * （共享行组件已 Neon 风格化，视觉统一）。
 *
 * 功能与 FSD 设置页一一对应，零缺失：主界面风格切换（FSD ↔ Neon）、
 * 全局背景、视频缩放/滤镜、横竖屏、高质量缩放/帧率、屏幕手柄/玩家切换/
 * 按键映射、存档方式/存储权限/应用详情、14 核心设置入口、关于。
 */
@Composable
fun NeonSettingsScreen(
    onBack: () -> Unit,
    onOpenKeyMap: () -> Unit
) {
    val context = LocalContext.current
    var padLayout by remember { mutableStateOf(PadLayoutStore.load(context)) }
    var dialogText by remember { mutableStateOf<String?>(null) }
    NeonUi.ensureLoaded(context)

    // 当前分类（null=主页概览）；核心子页由「核心设置」分类内的条目进入
    var selectedCategory by remember { mutableStateOf<String?>(null) }
    var selectedCore by remember { mutableStateOf<GamePlatform?>(null) }

    val isTv = remember {
        !context.packageManager.hasSystemFeature(
            android.content.pm.PackageManager.FEATURE_TOUCHSCREEN
        )
    }

    fun applyOrientation(orientation: String) {
        val activity = context as? android.app.Activity ?: return
        activity.requestedOrientation = when (orientation) {
            "landscape" -> android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            "portrait" -> android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
            else -> android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR
        }
    }
    LaunchedEffect(Unit) { applyOrientation(padLayout.screenOrientation) }

    /** 应用设置到已加载引擎（与 FSD 设置页同一套逻辑）。 */
    fun updateLayout(new: PadLayout) {
        if (new.homeBackgroundUri != padLayout.homeBackgroundUri ||
            new.homeBackgroundIsVideo != padLayout.homeBackgroundIsVideo
        ) {
            AppBackgroundState.update(new.homeBackgroundUri, new.homeBackgroundIsVideo)
        }
        padLayout = new
        PadLayoutStore.save(context, new)
        val nesEngine = NesEngine.get()
        if (nesEngine.isLoaded) {
            nesEngine.setCoreOption("fceumm_ntsc_filter", new.ntscFilter)
            nesEngine.setCoreOption("fceumm_palette", new.palette)
            nesEngine.setCoreOption("fceumm_region", new.region)
            nesEngine.setCoreOption("fceumm_overclocking", new.overclocking)
            val cropVal = if (new.cropOverscan == "enabled") "8" else "0"
            nesEngine.setCoreOption("fceumm_overscan_h_left", cropVal)
            nesEngine.setCoreOption("fceumm_overscan_h_right", cropVal)
            nesEngine.setCoreOption("fceumm_overscan_v_top", cropVal)
            nesEngine.setCoreOption("fceumm_overscan_v_bottom", cropVal)
            nesEngine.setVideoFilter(neonFilterValue(new.videoFilter))
        }
        val snesEngine = SnesEngine.get()
        if (snesEngine.isLoaded) {
            snesEngine.setVideoFilter(neonFilterValue(new.videoFilter))
        }
        val gbaEngine = GbaEngine.get()
        if (gbaEngine.isLoaded) {
            gbaEngine.setVideoFilter(neonFilterValue(new.videoFilter))
        }
    }

    // ---- 权限 / 选择器 ----
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        dialogText = if (result.values.any { it }) "存储权限已授予"
        else "权限被拒绝。可使用游戏库「导入ROM」按钮通过系统文件选择器导入，无需存储权限。"
    }

    fun requestStoragePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                try {
                    val intent = Intent(AndroidSettings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                        data = Uri.parse("package:${context.packageName}")
                    }
                    context.startActivity(intent)
                } catch (_: Exception) {
                    val intent = Intent(AndroidSettings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                    context.startActivity(intent)
                }
            } else {
                dialogText = "已有所有文件访问权限"
            }
        } else {
            permissionLauncher.launch(arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE))
        }
    }

    val bgImagePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        try {
            context.contentResolver.takePersistableUriPermission(
                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        } catch (_: Exception) { }
        updateLayout(padLayout.copy {
            homeBackgroundUri = uri.toString()
            homeBackgroundIsVideo = false
        })
        dialogText = "主页背景已更新（图片）"
    }
    val bgVideoPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        try {
            context.contentResolver.takePersistableUriPermission(
                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        } catch (_: Exception) { }
        updateLayout(padLayout.copy {
            homeBackgroundUri = uri.toString()
            homeBackgroundIsVideo = true
        })
        dialogText = "主页背景已更新（视频，循环静音播放）"
    }

    fun openAppSettings() {
        val intent = Intent(AndroidSettings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.fromParts("package", context.packageName, null)
        }
        context.startActivity(intent)
    }

    // ---- 分类定义 ----
    data class SettingsCat(val key: String, val label: String, val icon: ImageVector, val accent: Color)
    val categories = listOf(
        SettingsCat("appearance", "外观", Icons.Rounded.Palette, Neon.Accent),
        SettingsCat("video", "视频", Icons.Rounded.Tune, Neon.Blue),
        SettingsCat("display", "显示", Icons.Rounded.Speed, Neon.Green),
        SettingsCat("performance", "性能", Icons.Rounded.Percent, Neon.Gold),
        SettingsCat("input", "输入", Icons.Rounded.Gamepad, Neon.BtnX),
        SettingsCat("storage", "存储", Icons.Rounded.Dns, Neon.Purple),
        SettingsCat("cores", "核心设置", Icons.Rounded.Memory, Neon.Red),
        SettingsCat("about", "关于", Icons.Rounded.Info, Neon.Text)
    )

    Box(modifier = Modifier.fillMaxSize()) {
        if (!AppBackgroundState.active) NeonBackdrop()

        Column(modifier = Modifier.fillMaxSize()) {
            // ===== 顶栏 =====
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.AutoMirrored.Rounded.ArrowBack, null,
                    tint = Neon.TextHi,
                    modifier = Modifier
                        .size(38.dp)
                        .clickable {
                            if (selectedCore != null) selectedCore = null
                            else if (selectedCategory != null) selectedCategory = null
                            else onBack()
                        }
                        .padding(8.dp)
                )
                Column {
                    Text(
                        when {
                            selectedCore != null -> "${selectedCore!!.displayName} 核心设置"
                            selectedCategory != null -> categories.firstOrNull { it.key == selectedCategory }?.label ?: "设置"
                            else -> "设置"
                        },
                        color = Neon.TextHi, fontSize = 19.sp, fontWeight = FontWeight.ExtraBold
                    )
                    Text(
                        "SETTINGS · NEON",
                        color = Neon.Accent, fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp
                    )
                }
            }

            Row(modifier = Modifier.weight(1f).fillMaxWidth()) {
                // ===== 左侧分类导航 =====
                LazyColumn(
                    modifier = Modifier
                        .width(158.dp)
                        .fillMaxHeight()
                        .padding(start = 12.dp, top = 4.dp, bottom = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(7.dp)
                ) {
                    items(categories, key = { it.key }) { cat ->
                        val isSelected = selectedCategory == cat.key && selectedCore == null
                        val interaction = remember { MutableInteractionSource() }
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .graphicsLayer { if (isSelected) { scaleX = 1.03f; scaleY = 1.03f; translationX = 4f } }
                                .background(
                                    if (isSelected) Neon.BgPanelHi else Neon.BgPanel.copy(alpha = 0.55f),
                                    neonChamfer(0.22f)
                                )
                                .border(
                                    1.dp, if (isSelected) cat.accent else Neon.Line, neonChamfer(0.22f)
                                )
                                .clickable(interactionSource = interaction, indication = null) {
                                    selectedCategory = if (isSelected) null else cat.key
                                    selectedCore = null
                                }
                                .padding(horizontal = 11.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(cat.icon, cat.label, tint = if (isSelected) cat.accent else Neon.TextDim,
                                modifier = Modifier.size(17.dp))
                            Spacer(Modifier.width(9.dp))
                            Text(
                                cat.label,
                                color = if (isSelected) Neon.TextHi else Neon.Text,
                                fontSize = 13.sp,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                            )
                        }
                    }
                }

                // ===== 右侧内容面板 =====
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .padding(horizontal = 10.dp, vertical = 4.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                    ) {
                        when {
                            // --- 核心子页（复用 CoreSettingsPanel，共享行已 Neon 化）---
                            selectedCore != null -> {
                                CoreSettingsPanel(
                                    platform = selectedCore!!,
                                    padLayout = padLayout,
                                    updateLayout = ::updateLayout
                                )
                            }
                            // --- 分类内容 ---
                            selectedCategory == "appearance" -> NeonSettingsSection("外观") {
                                NeonDropdownRow(
                                    "主界面风格",
                                    listOf(
                                        "fsd" to "FSD 经典 (Xbox360 磁贴桌面)",
                                        "neon" to "Neon 3D 游戏站 (新UI)"
                                    ),
                                    NeonUi.style
                                ) {
                                    NeonUi.set(context, it)
                                    padLayout = PadLayoutStore.load(context)
                                }
                                NeonSettingsRow(
                                    "应用背景",
                                    when {
                                        padLayout.homeBackgroundUri.isEmpty() -> "默认深空底"
                                        padLayout.homeBackgroundIsVideo -> "自定义视频"
                                        else -> "自定义图片"
                                    }
                                ) { }
                                NeonSettingsRow("设置背景图片", "从文件选择图片作为全局壁纸") {
                                    runCatching { bgImagePicker.launch(arrayOf("image/*")) }
                                        .onFailure { dialogText = "无法打开选择器：${it.message}" }
                                }
                                NeonSettingsRow("设置背景视频", "循环静音播放的视频作为全局壁纸") {
                                    runCatching { bgVideoPicker.launch(arrayOf("video/*")) }
                                        .onFailure { dialogText = "无法打开选择器：${it.message}" }
                                }
                                NeonSettingsRow("恢复默认背景", "还原 Neon 深空底") {
                                    if (padLayout.homeBackgroundUri.isNotEmpty()) {
                                        updateLayout(padLayout.copy {
                                            homeBackgroundUri = ""
                                            homeBackgroundIsVideo = false
                                        })
                                        dialogText = "已恢复默认背景"
                                    } else {
                                        dialogText = "当前已是默认背景"
                                    }
                                }
                            }
                            selectedCategory == "video" -> NeonSettingsSection("视频") {
                                NeonDropdownRow("画面缩放",
                                    listOf(
                                        "stretch" to "全屏拉伸(默认)",
                                        "4:3" to "4:3",
                                        "3:2" to "3:2 (GBA 原生)",
                                        "8:7" to "8:7 (NES 像素比)",
                                        "16:9" to "16:9",
                                        "custom" to "自定义(拖动四角)"
                                    ),
                                    padLayout.videoScale
                                ) { updateLayout(padLayout.copy { videoScale = it }) }
                                NeonDropdownRow("视频滤镜",
                                    listOf(
                                        "none" to "关闭", "scanline" to "扫描线", "crt" to "CRT",
                                        "dot" to "点阵", "xbr" to "XBR", "hq2x" to "HQ2X",
                                        "hq4x" to "HQ4X", "xbr_dot" to "XBR+点阵", "4xbr" to "4XBR",
                                        "4xbr_dot" to "4XBR+点阵", "hq4x_dot" to "HQ4X+点阵"
                                    ),
                                    padLayout.videoFilter
                                ) { updateLayout(padLayout.copy { videoFilter = it }) }
                            }
                            selectedCategory == "display" -> NeonSettingsSection("显示") {
                                NeonDropdownRow("横竖屏",
                                    listOf(
                                        "sensor" to "自动(传感器)",
                                        "landscape" to "强制横屏",
                                        "portrait" to "强制竖屏"
                                    ),
                                    padLayout.screenOrientation
                                ) {
                                    updateLayout(padLayout.copy { screenOrientation = it })
                                    applyOrientation(it)
                                }
                            }
                            selectedCategory == "performance" -> NeonSettingsSection("性能") {
                                NeonSwitchRow(
                                    "高质量缩放",
                                    if (padLayout.highQualityScaling) "清晰(手机推荐)" else "快速(TV推荐)",
                                    padLayout.highQualityScaling
                                ) { updateLayout(padLayout.copy { highQualityScaling = it }) }
                                NeonSwitchRow(
                                    "显示帧数",
                                    if (padLayout.showFps) "开启" else "关闭",
                                    padLayout.showFps
                                ) { updateLayout(padLayout.copy { showFps = it }) }
                            }
                            selectedCategory == "input" -> NeonSettingsSection("输入") {
                                if (!isTv) {
                                    NeonSwitchRow(
                                        "屏幕手柄",
                                        if (padLayout.showPad) "显示" else "隐藏",
                                        padLayout.showPad
                                    ) { updateLayout(padLayout.copy { showPad = it }) }
                                    NeonSwitchRow(
                                        "玩家切换按钮",
                                        if (padLayout.showPlayerSwitch) "显示" else "隐藏",
                                        padLayout.showPlayerSwitch
                                    ) { updateLayout(padLayout.copy { showPlayerSwitch = it }) }
                                } else {
                                    NeonSettingsRow("屏幕手柄", "TV 模式自动隐藏") { }
                                }
                                NeonSettingsRow(
                                    "按键映射",
                                    if (isTv) "按核心自定义 · TV" else "按核心自定义",
                                    showArrow = true
                                ) { onOpenKeyMap() }
                            }
                            selectedCategory == "storage" -> NeonSettingsSection("存储") {
                                NeonDropdownRow("存档方式",
                                    listOf(
                                        "nesstation" to "统一存档目录 (推荐)",
                                        "core_builtin" to "ROM 同目录同名 (.srm/.sav)"
                                    ),
                                    padLayout.globalSaveMode
                                ) { updateLayout(padLayout.copy { globalSaveMode = it }) }
                                Text(
                                    "「统一存档目录」把存档集中在应用内部 saves 目录（NDS 为 <游戏ID>.sav，其他核心为 .srm）。" +
                                        "「ROM 同目录」直接读写 ROM 旁的同名存档（与官方 melonDS APK / RetroArch 习惯一致，便于和电脑交换存档）。切换后需重进游戏。",
                                    color = Neon.TextDim,
                                    fontSize = 11.sp,
                                    lineHeight = 15.sp,
                                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)
                                )
                                NeonSettingsRow("存储权限", "点击授权", showArrow = true) { requestStoragePermission() }
                                NeonSettingsRow("应用详情", "系统设置", showArrow = true) { openAppSettings() }
                                NeonSettingsRow("扫描ROM", "去游戏库导入") {
                                    dialogText = "请到游戏库点击「导入ROM」或「导入文件夹」按钮导入游戏文件"
                                }
                            }
                            selectedCategory == "cores" -> NeonSettingsSection("核心设置") {
                                NeonSettingsRow("FC / NES", "FCEUmm 核心 · 调色板/滤镜/区域/超频", showArrow = true) { selectedCore = GamePlatform.NES }
                                NeonSettingsRow("SFC / SNES", "Snes9x 核心 · 画面/图层/音频/超频", showArrow = true) { selectedCore = GamePlatform.SFC }
                                NeonSettingsRow("GB / GBA", "mGBA 核心 · 型号/色彩/跳帧", showArrow = true) { selectedCore = GamePlatform.GB }
                                NeonSettingsRow("MD / SEGA", "Genesis-Plus-GX 核心 · 区域/画面/手柄", showArrow = true) { selectedCore = GamePlatform.MD }
                                NeonSettingsRow("PCE / TG16", "Geargrafx 核心 · 主机/画面/CD", showArrow = true) { selectedCore = GamePlatform.PCE }
                                NeonSettingsRow("DOS", "DOSBox-Pure 核心 · 音频/CPU/内存/画面", showArrow = true) { selectedCore = GamePlatform.DOS }
                                NeonSettingsRow("街机 Arcade", "FBNeo 核心 · 旋转/跳帧/Neogeo", showArrow = true) { selectedCore = GamePlatform.ARCADE }
                                NeonSettingsRow("NDS / DSi", "melonDS 核心 · 屏幕/OpenGL/JIT/触摸", showArrow = true) { selectedCore = GamePlatform.NDS }
                                NeonSettingsRow("PSX", "PCSX-ReARMed 核心 · DRC/GPU线程/超频/SPU/手柄", showArrow = true) { selectedCore = GamePlatform.PSX }
                                NeonSettingsRow("PS2", "PCSX2 (PCEE2) 核心 · 渲染器/分辨率倍数/双摇杆/肩键", showArrow = true) { selectedCore = GamePlatform.PS2 }
                                NeonSettingsRow("Java / J2ME", "J2ME 虚拟机 · 分辨率/缩放/按键映射/数字键盘", showArrow = true) { selectedCore = GamePlatform.JAVA }
                                NeonSettingsRow("DC / Dreamcast", "Flycast 核心 · 分辨率/宽屏/排序/主机区域/GD-ROM", showArrow = true) { selectedCore = GamePlatform.DC }
                                NeonSettingsRow("3DS", "Azahar 核心 · 图形后端/分辨率/屏幕布局/3D/系统", showArrow = true) { selectedCore = GamePlatform.N3DS }
                                NeonSettingsRow("NGC / Wii", "Ishiiruka (Dolphin) 核心 · 后端/EFB 分辨率/CPU/控制模式", showArrow = true) { selectedCore = GamePlatform.NGCWII }
                            }
                            selectedCategory == "about" -> NeonSettingsSection("关于") {
                                NeonSettingsRow("版本", "3.7") { }
                                NeonSettingsRow(
                                    "核心",
                                    "FCEUmm · Snes9x · mGBA · Genesis-Plus-GX · Geargrafx · DOSBox-Pure · FBNeo · melonDS · PCSX-ReARMed · PCEE2 (PCSX2) · Flycast (DC/NAOMI) · Azahar (3DS) · Ishiiruka (NGC/WII)"
                                ) { }
                                NeonSettingsRow("开源许可", "MIT License", showArrow = true) {
                                    dialogText = "NesStation 基于 FCEUmm (NES)、Snes9x (SFC)、mGBA (GB/GBC/GBA)、Genesis-Plus-GX (MD)、Geargrafx (PCE)、DOSBox-Pure (DOS)、FBNeo (Arcade)、melonDS (NDS)、PCSX-ReARMed (PSX)、PCEE2 / PCSX2 (PS2)、Flycast (DC/NAOMI)、Azahar / AzaharPlus (3DS)、Ishiiruka / Dolphin (NGC/WII) 核心构建，遵循各自开源许可证"
                                }
                            }
                            // --- 默认概览：全部分类的快捷入口说明 ---
                            else -> {
                                NeonSettingsSection("设置") {
                                    Text(
                                        "在左侧选择分类，或直接点击下方常用设置：",
                                        color = Neon.TextDim, fontSize = 12.sp,
                                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)
                                    )
                                    NeonSettingsRow("主界面风格", "FSD 经典 ↔ Neon 3D 新UI 切换", showArrow = true) {
                                        selectedCategory = "appearance"
                                    }
                                    NeonSettingsRow("按键映射", "按核心自定义按键", showArrow = true) { onOpenKeyMap() }
                                    NeonSettingsRow("核心设置", "14 个模拟核心的专属选项", showArrow = true) {
                                        selectedCategory = "cores"
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    dialogText?.let { text ->
        AlertDialog(
            onDismissRequest = { dialogText = null },
            text = { Text(text) },
            confirmButton = {
                TextButton(onClick = { dialogText = null }) { Text("确定") }
            }
        )
    }
}

// =====================================================================
// Neon 设置行组件
// =====================================================================

/** 设置分组面板（深色切角卡）。 */
@Composable
private fun NeonSettingsSection(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(
            title,
            color = Neon.Text,
            fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(start = 10.dp, bottom = 3.dp)
        )
        Column(
            modifier = Modifier
                .clip(RoundedCornerShape(14.dp))
                .background(Color(0xE60A1020))
                .border(1.dp, Neon.Line.copy(alpha = 0.6f), RoundedCornerShape(14.dp))
                .padding(vertical = 2.dp)
        ) {
            content()
        }
    }
}

/** 设置行：标题 + 副标题 + 尾部（箭头），可点击。 */
@Composable
private fun NeonSettingsRow(
    title: String,
    subtitle: String? = null,
    showArrow: Boolean = false,
    onClick: (() -> Unit)? = null
) {
    val interaction = remember { MutableInteractionSource() }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = onClick != null
            ) { onClick?.invoke() }
            .padding(horizontal = 14.dp, vertical = 11.dp)
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, color = Neon.TextHi, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            if (subtitle != null) {
                Text(subtitle, color = Neon.TextDim, fontSize = 11.sp, maxLines = 2)
            }
        }
        if (showArrow) {
            // 注：icons 1.6.8 的 ChevronRight 无 AutoMirrored 变体（ArrowBack 才有），
            // 直接用 Rounded.ChevronRight。
            Icon(
                Icons.Rounded.ChevronRight, null,
                tint = Neon.TextDim, modifier = Modifier.size(18.dp)
            )
        }
    }
}

/** 开关设置行。 */
@Composable
private fun NeonSwitchRow(
    title: String,
    subtitle: String?,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 6.dp)
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, color = Neon.TextHi, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            if (subtitle != null) Text(subtitle, color = Neon.TextDim, fontSize = 11.sp)
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = Neon.AccentDim,
                uncheckedTrackColor = Color(0xFF1C3350)
            )
        )
    }
}

/** 下拉选择设置行。 */
@Composable
private fun NeonDropdownRow(
    label: String,
    options: List<Pair<String, String>>,
    selected: String,
    onSelect: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val selectedLabel = options.find { it.first == selected }?.second ?: selected
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { expanded = true }
            .padding(horizontal = 14.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            label,
            color = Neon.TextHi, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f)
        )
        Box {
            Text(selectedLabel, color = Neon.Accent, fontSize = 13.sp)
            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false }
            ) {
                // material3 1.2.1 的 DropdownMenu 尚无 containerColor 参数（1.3.0 才加），
                // 用内容层深色底实现同效果（系统浅色主题下也保持 Neon 深色菜单）。
                Column(
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background(Color(0xF20A1020))
                ) {
                    options.forEach { (value, text) ->
                        DropdownMenuItem(
                            text = { Text(text, fontSize = 13.sp, color = Neon.Text) },
                            onClick = { onSelect(value); expanded = false }
                        )
                    }
                }
            }
        }
    }
}

/** 视频滤镜名 → 引擎数值（与 FSD 设置页同一映射）。 */
private fun neonFilterValue(filter: String): Int = when (filter) {
    "scanline" -> 1
    "crt" -> 2
    "dot" -> 3
    "xbr" -> 4
    "hq2x" -> 5
    "hq4x" -> 6
    "xbr_dot" -> 7
    "4xbr" -> 8
    "4xbr_dot" -> 9
    "hq4x_dot" -> 10
    else -> 0
}
