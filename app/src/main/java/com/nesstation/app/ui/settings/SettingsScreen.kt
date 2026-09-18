package com.nesstation.app.ui.settings

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings as AndroidSettings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.app.Activity
import android.content.pm.ActivityInfo
import com.nesstation.app.core.engine.EmulatorEngine
import com.nesstation.app.core.engine.NesEngine
import com.nesstation.app.core.engine.SnesEngine
import com.nesstation.app.core.engine.GbaEngine
import com.nesstation.app.core.model.GamePlatform
import com.nesstation.app.core.storage.PadLayoutStore
import com.nesstation.app.ui.components.AppBackgroundState
import com.nesstation.app.ui.components.PixelBackdrop

@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onOpenKeyMap: () -> Unit
) {
    val context = LocalContext.current
    var padLayout by remember { mutableStateOf(PadLayoutStore.load(context)) }
    var dialogText by remember { mutableStateOf<String?>(null) }

    // 当前打开的核心设置页（null = 设置主页）。点击「核心设置」里的某个
    // 核心后进入该核心的独立设置页，展示该核心专属的选项。
    var selectedCore by remember { mutableStateOf<GamePlatform?>(null) }

    // Detect TV mode — on TV the "屏幕手柄" toggle is hidden (the on-screen
    // pad is auto-hidden because there's no touchscreen).
    val isTv = remember {
        !context.packageManager.hasSystemFeature(
            android.content.pm.PackageManager.FEATURE_TOUCHSCREEN
        )
    }

    // Apply orientation setting immediately
    fun applyOrientation(orientation: String) {
        val activity = context as? Activity ?: return
        activity.requestedOrientation = when (orientation) {
            "landscape" -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            "portrait" -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
            else -> ActivityInfo.SCREEN_ORIENTATION_SENSOR  // auto (sensor)
        }
    }

    // Apply orientation on first load
    LaunchedEffect(Unit) { applyOrientation(padLayout.screenOrientation) }

    fun updateLayout(new: com.nesstation.app.core.storage.PadLayout) {
        // 背景变更时立即同步全局背景状态（根布局 + 各页面无需等待 ON_RESUME）
        if (new.homeBackgroundUri != padLayout.homeBackgroundUri ||
            new.homeBackgroundIsVideo != padLayout.homeBackgroundIsVideo
        ) {
            AppBackgroundState.update(new.homeBackgroundUri, new.homeBackgroundIsVideo)
        }
        padLayout = new
        PadLayoutStore.save(context, new)
        // Apply core options to whichever engine(s) are currently loaded.
        // Previously this hard-coded NesEngine.get() which silently dropped
        // options when the user was playing a SNES or GBA game.
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
            // Apply video filter (frontend post-processing)
            val filterInt = when (new.videoFilter) {
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
            nesEngine.setVideoFilter(filterInt)
        }
        // SNES engine: apply video filter if loaded
        val snesEngine = SnesEngine.get()
        if (snesEngine.isLoaded) {
            val filterInt = when (new.videoFilter) {
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
            snesEngine.setVideoFilter(filterInt)
        }
        // GBA engine: apply video filter if loaded
        val gbaEngine = GbaEngine.get()
        if (gbaEngine.isLoaded) {
            val filterInt = when (new.videoFilter) {
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
            gbaEngine.setVideoFilter(filterInt)
        }
    }

    // Permission launcher for Android <= 10
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        dialogText = if (result.values.any { it }) {
            "存储权限已授予"
        } else {
            "权限被拒绝。可使用「导入ROM」按钮通过系统文件选择器导入，无需存储权限。"
        }
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
            val permissions = arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
            permissionLauncher.launch(permissions)
        }
    }

    // SAF：主页背景图片/视频选择器（OpenDocument 支持持久化 URI 权限，
    // MediaPlayer / BitmapFactory 后续随时可读）。
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

    Box(modifier = Modifier.fillMaxSize()) {
        if (!AppBackgroundState.active) PixelBackdrop()
        // 自定义背景激活时，顶部标题/返回箭头改亮色，避免深色字盖在背景上看不清。
        val headerTint = if (AppBackgroundState.active) Color.White else Color(0xFF1E2A3A)
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = { if (selectedCore != null) selectedCore = null else onBack() }) {
                    Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = null, tint = headerTint)
                }
                Spacer(Modifier.size(8.dp))
                Text(
                    if (selectedCore == null) "设置" else "${selectedCore!!.displayName} 核心设置",
                    color = headerTint, fontSize = 20.sp, fontWeight = FontWeight.ExtraBold
                )
            }

            if (selectedCore == null) {
                // === 设置主页 ===
                LazyColumn(
                    contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    // === 通用视频 ===
                    item {
                        SettingsSection("视频") {
                            DropdownRow("画面缩放",
                                listOf(
                                    "stretch" to "全屏拉伸(默认)",
                                    "4:3" to "4:3",
                                    "3:2" to "3:2 (GBA 原生)",
                                    "8:7" to "8:7 (NES 像素比)",
                                    "16:9" to "16:9",
                                    "custom" to "自定义(拖动四角)"
                                ),
                                padLayout.videoScale
                            ) { updateLayout(padLayout.copy {videoScale = it}) }

                            DropdownRow("视频滤镜",
                                listOf("none" to "关闭", "scanline" to "扫描线", "crt" to "CRT", "dot" to "点阵",
                                       "xbr" to "XBR", "hq2x" to "HQ2X", "hq4x" to "HQ4X", "xbr_dot" to "XBR+点阵",
                                       "4xbr" to "4XBR", "4xbr_dot" to "4XBR+点阵", "hq4x_dot" to "HQ4X+点阵"),
                                padLayout.videoFilter
                            ) { updateLayout(padLayout.copy {videoFilter = it}) }
                        }
                    }

                    // === 核心设置入口 ===
                    item {
                        SettingsSection("核心设置") {
                            SettingsRow("FC / NES", "FCEUmm 核心 · 调色板/滤镜/区域/超频", trailing = { Arrow() }) { selectedCore = GamePlatform.NES }
                            SettingsRow("SFC / SNES", "Snes9x 核心 · 画面/图层/音频/超频", trailing = { Arrow() }) { selectedCore = GamePlatform.SFC }
                            SettingsRow("GB / GBA", "mGBA 核心 · 型号/色彩/跳帧", trailing = { Arrow() }) { selectedCore = GamePlatform.GB }
                            SettingsRow("MD / SEGA", "Genesis-Plus-GX 核心 · 区域/画面/手柄", trailing = { Arrow() }) { selectedCore = GamePlatform.MD }
                            SettingsRow("PCE / TG16", "Geargrafx 核心 · 主机/画面/CD", trailing = { Arrow() }) { selectedCore = GamePlatform.PCE }
                            SettingsRow("DOS", "DOSBox-Pure 核心 · 音频/CPU/内存/画面", trailing = { Arrow() }) { selectedCore = GamePlatform.DOS }
                            SettingsRow("街机 Arcade", "FBNeo 核心 · 旋转/跳帧/NeoGeo", trailing = { Arrow() }) { selectedCore = GamePlatform.ARCADE }
                            SettingsRow("NDS / DSi", "melonDS 核心 · 屏幕/OpenGL/JIT/触摸", trailing = { Arrow() }) { selectedCore = GamePlatform.NDS }
                            SettingsRow("PSX", "PCSX-ReARMed 核心 · DRC/GPU线程/超频/SPU/手柄", trailing = { Arrow() }) { selectedCore = GamePlatform.PSX }
                            SettingsRow("PS2", "PCSX2 (PCEE2) 核心 · 渲染器/分辨率倍数/双摇杆/肩键", trailing = { Arrow() }) { selectedCore = GamePlatform.PS2 }
                            SettingsRow("Java / J2ME", "J2ME 虚拟机 · 分辨率/缩放/按键映射/数字键盘", trailing = { Arrow() }) { selectedCore = GamePlatform.JAVA }
                        }
                    }

                    // === 显示 ===
                    item {
                        SettingsSection("显示") {
                            DropdownRow("横竖屏",
                                listOf("sensor" to "自动(传感器)", "landscape" to "强制横屏", "portrait" to "强制竖屏"),
                                padLayout.screenOrientation
                            ) {
                                updateLayout(padLayout.copy {screenOrientation = it})
                                applyOrientation(it)
                            }
                        }
                    }

                    // === 性能(缩放) ===
                    item {
                        SettingsSection("性能") {
                            // High-quality scaling toggle — controls native surface buffer geometry.
                            // false (default): source-res buffer + GPU upscale = fast (recommended for TV)
                            // true: display-res buffer + CPU scale = sharp (recommended for phones)
                            SettingsRow(
                                "高质量缩放",
                                if (padLayout.highQualityScaling) "清晰(手机推荐)" else "快速(TV推荐)",
                                showSubtitle = true,
                                trailing = {
                                    Switch(checked = padLayout.highQualityScaling, onCheckedChange = {
                                        updateLayout(padLayout.copy {highQualityScaling = it})
                                    }, colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = Color(0xFFE74C3C)))
                                }
                            )
                            // 全局 FPS 显示 —— 游戏画面左上角实时显示模拟帧率
                            SettingsRow(
                                "显示帧数",
                                if (padLayout.showFps) "开启" else "关闭",
                                showSubtitle = true,
                                trailing = {
                                    Switch(checked = padLayout.showFps, onCheckedChange = {
                                        updateLayout(padLayout.copy {showFps = it})
                                    }, colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = Color(0xFFE74C3C)))
                                }
                            )
                        }
                    }

                    // === 输入 ===
                    item {
                        SettingsSection("输入") {
                            // On TV the on-screen gamepad is useless (no touchscreen)
                            // and auto-hidden in EmulatorScreen — hide the toggle too
                            // so the user isn't confused about why it has no effect.
                            if (!isTv) {
                                SettingsRow("屏幕手柄", if (padLayout.showPad) "显示" else "隐藏",
                                    showSubtitle = false,
                                    trailing = {
                                        Switch(checked = padLayout.showPad, onCheckedChange = {
                                            updateLayout(padLayout.copy {showPad = it})
                                        }, colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = Color(0xFFE74C3C)))
                                    }
                                )
                                // 1P/2P/3P/4P 玩家切换悬浮球（小圆形、可拖动）
                                SettingsRow("玩家切换按钮", if (padLayout.showPlayerSwitch) "显示" else "隐藏",
                                    showSubtitle = false,
                                    trailing = {
                                        Switch(checked = padLayout.showPlayerSwitch, onCheckedChange = {
                                            updateLayout(padLayout.copy {showPlayerSwitch = it})
                                        }, colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = Color(0xFFE74C3C)))
                                    }
                                )
                            } else {
                                SettingsRow("屏幕手柄", "TV 模式自动隐藏",
                                    showSubtitle = false,
                                    trailing = { ValueText("TV") }
                                )
                            }
                            SettingsRow("按键映射", if (isTv) "按核心自定义 · TV" else "按核心自定义", trailing = { Arrow() }) { onOpenKeyMap() }
                        }
                    }

                    // === 存储 ===
                    item {
                        SettingsSection("存储") {
                            // 全局存档方式：所有核心通用（不再只是 NDS 独有）。
                            // NES/SFC/GB/GBA/PCE/DOS/街机/MD/PSX 写 .srm，NDS 写 .sav。
                            DropdownRow("存档方式",
                                listOf(
                                    "nesstation" to "统一存档目录 (推荐)",
                                    "core_builtin" to "ROM 同目录同名 (.srm/.sav)"
                                ),
                                padLayout.globalSaveMode
                            ) { updateLayout(padLayout.copy {globalSaveMode = it}) }
                            Text(
                                "「统一存档目录」把存档集中在应用内部 saves 目录（NDS 为 <游戏ID>.sav，其他核心为 .srm），content:// 导入的游戏互不干扰。" +
                                "「ROM 同目录」直接读写 ROM 旁的同名存档（与官方 melonDS APK / RetroArch 习惯一致，便于和电脑交换存档）。切换后需重进游戏。",
                                color = if (AppBackgroundState.active) Color.White.copy(alpha = 0.72f) else Color(0xFF4A5568),
                                fontSize = 11.sp,
                                lineHeight = 15.sp,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
                            )
                            SettingsRow("存储权限", "点击授权", trailing = { Arrow() }) { requestStoragePermission() }
                            SettingsRow("应用详情", "系统设置", trailing = { Arrow() }) { openAppSettings() }
                            SettingsRow("扫描ROM", "去游戏库导入", trailing = { Arrow() }) {
                                dialogText = "请到游戏库点击「导入ROM」或「导入文件夹」按钮导入游戏文件"
                            }
                        }
                    }

                    // === 外观（全局背景） ===
                    item {
                        SettingsSection("外观") {
                            SettingsRow("应用背景",
                                if (padLayout.homeBackgroundUri.isEmpty()) "默认深蓝壁纸"
                                else if (padLayout.homeBackgroundIsVideo) "自定义视频"
                                else "自定义图片",
                                trailing = { ValueText(if (padLayout.homeBackgroundUri.isEmpty()) "默认"
                                                       else if (padLayout.homeBackgroundIsVideo) "视频" else "图片") }
                            )
                            SettingsRow("设置背景图片", "从文件选择图片作为全局壁纸") {
                                try {
                                    bgImagePicker.launch(arrayOf("image/*"))
                                } catch (e: Exception) {
                                    dialogText = "无法打开选择器：${e.message}"
                                }
                            }
                            SettingsRow("设置背景视频", "循环静音播放的视频作为全局壁纸") {
                                try {
                                    bgVideoPicker.launch(arrayOf("video/*"))
                                } catch (e: Exception) {
                                    dialogText = "无法打开选择器：${e.message}"
                                }
                            }
                            SettingsRow("恢复默认背景", "还原 FSD 深蓝壁纸") {
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
                            Text(
                                "磁贴自定义图标：在主页长按任意磁贴（或按 Y 键）即可为其设置专属图标，立即生效并持久保存。",
                                color = if (AppBackgroundState.active) Color.White.copy(alpha = 0.72f) else Color(0xFF4A5568),
                                fontSize = 11.sp,
                                lineHeight = 15.sp,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
                            )
                        }
                    }

                    // === 关于 ===
                    item {
                        SettingsSection("关于") {
                            SettingsRow("版本", "3.6.0", trailing = { ValueText("3.6.0") })
                            SettingsRow("核心", "FCEUmm · Snes9x · mGBA · Genesis-Plus-GX · Geargrafx · DOSBox-Pure · FBNeo · melonDS · PCSX-ReARMed · PCEE2 (PCSX2)",
                                trailing = { ValueText("11 个模拟核心") })
                            SettingsRow("开源许可", "MIT License", trailing = { Arrow() }) {
                                dialogText = "GameBox 基于 FCEUmm (NES)、Snes9x (SFC)、mGBA (GB/GBC/GBA)、Genesis-Plus-GX (MD)、Geargrafx (PCE)、DOSBox-Pure (DOS)、FBNeo (Arcade)、melonDS (NDS)、PCSX-ReARMed (PSX)、PCEE2 / PCSX2 (PS2) 核心构建，遵循各自开源许可证"
                            }
                        }
                    }
                }
            } else {
                // === 核心设置子页 ===
                CoreSettingsPanel(
                    platform = selectedCore!!,
                    padLayout = padLayout,
                    updateLayout = ::updateLayout
                )
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

@Composable
internal fun SettingsSection(title: String, content: @Composable () -> Unit) {
    // 自定义背景(图片/视频)激活时改用半透明深色玻璃卡片，让背景透出来而不是被
    // 0.65 白色挡住；文字同步改亮色保证可读。默认壁纸时维持原白底深字观感。
    val onCustomBg = AppBackgroundState.active
    val cardBg = if (onCustomBg) Color.Black.copy(alpha = 0.42f) else Color.White.copy(alpha = 0.65f)
    val titleColor = if (onCustomBg) Color.White else Color(0xFF1E2A3A)
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, color = titleColor, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(start = 8.dp, bottom = 4.dp))
        Column(
            modifier = Modifier.clip(RoundedCornerShape(16.dp))
                .background(cardBg)
                .padding(vertical = 2.dp)
        ) {
            content()
        }
    }
}

@Composable
internal fun SettingsRow(
    title: String,
    subtitle: String? = null,
    showSubtitle: Boolean = true,
    trailing: @Composable () -> Unit = { Arrow() },
    onClick: (() -> Unit)? = null
) {
    val interaction = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val onCustomBg = AppBackgroundState.active
    val rowBg = if (focused) {
        if (onCustomBg) Color.White.copy(alpha = 0.14f) else Color.White.copy(alpha = 0.85f)
    } else Color.Transparent
    val titleColor = if (onCustomBg) Color.White else Color(0xFF1E2A3A)
    val subtitleColor = if (onCustomBg) Color.White.copy(alpha = 0.72f) else Color(0xFF4A5568)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .background(rowBg)
            .clickable(interactionSource = interaction, indication = null) { onClick?.invoke() }
            .focusable(interactionSource = interaction)
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, color = titleColor, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            if (subtitle != null && showSubtitle) {
                Text(subtitle, color = subtitleColor, fontSize = 11.sp)
            }
        }
        trailing()
    }
}

@Composable
internal fun DropdownRow(
    label: String,
    options: List<Pair<String, String>>,
    selected: String,
    onSelect: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val selectedLabel = options.find { it.first == selected }?.second ?: selected
    val interaction = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val onCustomBg = AppBackgroundState.active
    val rowBg = if (focused) {
        if (onCustomBg) Color.White.copy(alpha = 0.14f) else Color.White.copy(alpha = 0.85f)
    } else Color.Transparent
    val labelColor = if (onCustomBg) Color.White else Color(0xFF1E2A3A)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(rowBg)
            .clickable(interactionSource = interaction, indication = null) { expanded = true }
            .focusable(interactionSource = interaction)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, color = labelColor, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
        Box {
            Text(selectedLabel, color = Color(0xFFE74C3C), fontSize = 13.sp)
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                options.forEach { (value, text) ->
                    DropdownMenuItem(text = { Text(text, fontSize = 13.sp) }, onClick = { onSelect(value); expanded = false })
                }
            }
        }
    }
}

@Composable internal fun Arrow() = Icon(Icons.Rounded.ChevronRight, contentDescription = null,
    tint = if (AppBackgroundState.active) Color.White.copy(alpha = 0.72f) else Color(0xFF4A5568),
    modifier = Modifier.size(18.dp))
@Composable private fun ValueText(v: String) = Text(v,
    color = if (AppBackgroundState.active) Color.White.copy(alpha = 0.72f) else Color(0xFF4A5568),
    fontSize = 12.sp)
