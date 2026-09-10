package com.nesstation.app.ui.fsd

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.HelpOutline
import androidx.compose.material.icons.automirrored.rounded.Logout
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.LocalPlay
import androidx.compose.material.icons.rounded.Computer
import androidx.compose.material.icons.rounded.Gamepad
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material.icons.rounded.LocalCafe
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.MenuBook
import androidx.compose.material.icons.rounded.MobileFriendly
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.Radio
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Smartphone
import androidx.compose.material.icons.rounded.SportsEsports
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material.icons.rounded.VideogameAsset
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.nesstation.app.core.model.GameEntry
import com.nesstation.app.core.model.GamePlatform
import com.nesstation.app.core.storage.PadLayoutStore
import com.nesstation.app.ui.components.AppBackgroundState
import kotlin.math.abs
import org.json.JSONObject
import java.io.File

/** 平台 → 专属图标：每个类别磁贴一个可辨识的图标，不再是千篇一律的手柄。 */
private fun platformIcon(p: GamePlatform) = when (p) {
    GamePlatform.NES    -> Icons.Rounded.Gamepad         // FC 十字手柄
    GamePlatform.SFC    -> Icons.Rounded.VideogameAsset  // SFC 手柄
    GamePlatform.GB     -> Icons.Rounded.Smartphone      // 竖向掌机
    GamePlatform.GBA    -> Icons.Rounded.MobileFriendly  // 横向掌机
    GamePlatform.MD     -> Icons.Rounded.Computer        // 16-bit 主机
    GamePlatform.PCE    -> Icons.Rounded.Radio           // PC-E 小白机
    GamePlatform.PSX    -> Icons.Rounded.Album           // CD 光盘
    GamePlatform.PS2    -> Icons.Rounded.Memory          // PS2 Emotion Engine 芯片
    GamePlatform.NDS    -> Icons.Rounded.MenuBook        // 翻盖双屏
    GamePlatform.ARCADE -> Icons.Rounded.LocalPlay       // 街机厅票券（legacy 集无 Arcade 图标）
    GamePlatform.DOS    -> Icons.Rounded.Terminal        // DOS 命令行
    GamePlatform.DC     -> Icons.Rounded.SportsEsports   // DC 手柄（圆盘+模拟摇杆）
    GamePlatform.JAVA   -> Icons.Rounded.LocalCafe       // Java 咖啡
}

/** 解析磁贴自定义图标 JSON（{ tileKey → iconPath }），损坏时返回空 map。 */
private fun parseTileIconMap(json: String): Map<String, String> {
    if (json.isBlank()) return emptyMap()
    return try {
        val obj = JSONObject(json)
        val map = LinkedHashMap<String, String>()
        obj.keys().forEach { key -> map[key] = obj.optString(key) }
        map.filterValues { it.isNotBlank() }
    } catch (_: Exception) {
        emptyMap()
    }
}

/** 解析磁贴图标透明度 JSON（{ tileKey → Float }），损坏/缺失时回退 1.0。 */
private fun parseTileAlphaMap(json: String): Map<String, Float> {
    if (json.isBlank()) return emptyMap()
    return try {
        val obj = JSONObject(json)
        val map = LinkedHashMap<String, Float>()
        obj.keys().forEach { key ->
            map[key] = obj.optDouble(key, 1.0).toFloat().coerceIn(0.05f, 1f)
        }
        map
    } catch (_: Exception) {
        emptyMap()
    }
}

/**
 * FSD 桌面主页 — 仿 Xbox 360 Freestyle Dash 的磁贴主菜单。
 *
 * 结构（对照用户提供的 FSD 截图）：
 *   - 顶部系统状态条（CPU/内存/存储/时钟，竖屏自动紧凑）
 *   - 左上面包屑「主菜单 ▪ 游戏库」
 *   - 蓝/黄对角磁贴封面流：全部游戏 → 各平台（专属图标+数量）→ 功能入口
 *   - 「N of M」计数 + 底部 A/B 按键提示
 *   - 底部状态条（IP / 状态 / 日期 时间）
 *
 * 个性化：
 *   - 主页背景可在设置里换成图片 / 循环视频（[FsdCustomBackground]）
 *   - 长按任意磁贴（或按 Y 键）可给该磁贴设置自定义图标
 *
 * 手机与 TV 共用：磁贴流内建 D-pad 焦点导航（左/右切换、OK 激活），
 * 触屏设备点击磁贴即可。平台磁贴仅在库内有游戏时显示。
 */
@Composable
fun FsdHomeScreen(
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
    val context = LocalContext.current

    // === 个性化状态（磁贴图标 + 图标透明度），ON_RESUME 时从存储重载 ===
    var tileIconsJson by rememberSaveable { mutableStateOf("") }
    var tileAlphasJson by rememberSaveable { mutableStateOf("") }

    fun reloadPersonalization() {
        val pl = PadLayoutStore.load(context)
        tileIconsJson = pl.homeTileIcons
        tileAlphasJson = pl.homeTileIconAlphas
    }

    LaunchedEffect(Unit) { reloadPersonalization() }
    // 从设置页改完背景/图标返回主页时立即生效
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) reloadPersonalization()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val tileIcons = remember(tileIconsJson) { parseTileIconMap(tileIconsJson) }
    val tileAlphas = remember(tileAlphasJson) { parseTileAlphaMap(tileAlphasJson) }

    val platformOrder = listOf(
        GamePlatform.NES, GamePlatform.SFC, GamePlatform.GB, GamePlatform.GBA,
        GamePlatform.MD, GamePlatform.PCE, GamePlatform.PSX, GamePlatform.PS2,
        GamePlatform.NDS, GamePlatform.ARCADE, GamePlatform.DOS, GamePlatform.DC,
        GamePlatform.JAVA
    )
    val countByPlatform = remember(games) {
        games.groupingBy { it.platform }.eachCount()
    }

    val tiles = remember(games, countByPlatform, tileIcons, tileAlphas) {
        fun alpha(key: String) = tileAlphas[key] ?: 1f
        buildList {
            add(FsdTileItem("all", "全部游戏", Icons.Rounded.GridView,
                badge = games.size.toString(), iconPath = tileIcons["all"], iconAlpha = alpha("all")))
            platformOrder.forEach { p ->
                val n = countByPlatform[p] ?: 0
                if (n > 0) {
                    add(FsdTileItem("platform:${p.name}", p.displayName, platformIcon(p),
                        badge = n.toString(), iconPath = tileIcons["platform:${p.name}"],
                        iconAlpha = alpha("platform:${p.name}")))
                }
            }
            add(FsdTileItem("online", "在线游戏", Icons.Rounded.Public,
                iconPath = tileIcons["online"], iconAlpha = alpha("online")))
            add(FsdTileItem("battle", "对战平台", Icons.Rounded.SportsEsports,
                iconPath = tileIcons["battle"], iconAlpha = alpha("battle")))
            add(FsdTileItem("swf", "SWF/Flash", Icons.Rounded.PlayArrow,
                iconPath = tileIcons["swf"], iconAlpha = alpha("swf")))
            add(FsdTileItem("settings", "设置", Icons.Rounded.Settings,
                iconPath = tileIcons["settings"], iconAlpha = alpha("settings")))
            add(FsdTileItem("about", "关于", Icons.AutoMirrored.Rounded.HelpOutline,
                iconPath = tileIcons["about"], iconAlpha = alpha("about")))
            add(FsdTileItem("exit", "退出", Icons.AutoMirrored.Rounded.Logout,
                iconPath = tileIcons["exit"], iconAlpha = alpha("exit")))
        }
    }

    // 长按磁贴弹出的「磁贴选项」对话框 —— 以磁贴 key 定位
    var tileMenuKey by remember { mutableStateOf<String?>(null) }
    var pendingTileKey by remember { mutableStateOf<String?>(null) }

    fun activate(tile: FsdTileItem) {
        when {
            tile.key == "all" -> onOpenLibrary()
            tile.key.startsWith("platform:") ->
                onOpenPlatform(GamePlatform.fromString(tile.key.removePrefix("platform:")))
            tile.key == "online" -> onOpenOnlineGames()
            tile.key == "battle" -> onOpenBattle()
            tile.key == "swf" -> onOpenSwf()
            tile.key == "settings" -> onOpenSettings()
            tile.key == "about" -> onOpenAbout()
            tile.key == "exit" -> onExit()
        }
    }

    // 分区化主页：3 个横向滑动的分区行 —— 游戏库 / 在线·对战·SWF / 设置·关于·退出。
    // 每个分区一段标题条 + 一行横向滑动的 FSD 磁贴（与游戏库封面流一致）；
    // 分区之间通过选中态区分（未选中整行变暗），可用 D-pad 上下切换分区。
    val sections = remember(tiles) {
        listOf(
            FsdMenuSection(
                key = "library",
                title = "游戏库",
                items = tiles.filter { it.key == "all" || it.key.startsWith("platform:") }
            ),
            FsdMenuSection(
                key = "online",
                title = "在线 · 对战 · SWF",
                items = tiles.filter {
                    it.key == "online" || it.key == "battle" || it.key == "swf"
                }
            ),
            FsdMenuSection(
                key = "system",
                title = "设置 · 关于 · 退出",
                items = tiles.filter {
                    it.key == "settings" || it.key == "about" || it.key == "exit"
                }
            )
        )
    }

    // === 分区导航状态 ===
    // selectedSection：当前选中的分区（上下切换，选中的一行在中间变大）；
    // selInSection：每个分区内选中的磁贴索引。
    var selectedSection by rememberSaveable { mutableIntStateOf(0) }
    val selInSection = remember(sections.size) {
        mutableStateOf(List(sections.size) { 0 })
    }
    val density = LocalDensity.current

    // 长按磁贴 → 挑选自定义图标（拷贝到 filesDir/icons，立即写回存储）
    val tileIconPicker = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        val key = pendingTileKey
        val tile = key?.let { k -> tiles.firstOrNull { it.key == k } }
        pendingTileKey = null
        val uri = uris.firstOrNull()
        if (uri == null || tile == null) return@rememberLauncherForActivityResult
        try {
            try {
                context.contentResolver.takePersistableUriPermission(
                    uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (_: Exception) { }
            val iconsDir = File(context.filesDir, "icons").apply { mkdirs() }
            val dest = File(iconsDir, "tile_${tile.key.replace(':', '_')}_${System.currentTimeMillis()}.png")
            context.contentResolver.openInputStream(uri)?.use { input ->
                dest.outputStream().use { output -> input.copyTo(output) }
            } ?: throw IllegalStateException("无法读取所选图片")
            val newMap = LinkedHashMap<String, String>(tileIcons)
            newMap[tile.key] = dest.absolutePath
            val json = JSONObject().apply { newMap.forEach { (k, v) -> put(k, v) } }.toString()
            val layout = PadLayoutStore.load(context)
            PadLayoutStore.save(context, layout.copy { homeTileIcons = json })
            tileIconsJson = json   // 立即刷新
        } catch (_: Exception) {
            // 拷贝失败保持现状，不打断主页
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        // 背景：全局背景已激活时由根布局统一渲染，此处不再自绘；
        // 否则使用默认 FSD 深蓝壁纸。
        if (!AppBackgroundState.active) {
            FsdBackdrop()
        }

        Column(modifier = Modifier.fillMaxSize()) {
            FsdTopBar()

            Spacer(Modifier.height(10.dp))

            // 3 个分区行以「垂直封面流」排布：选中的一行居中放大，
            // 上下两行缩小 + 变暗；D-pad 上下 / 触摸上下滑动切换所选行。
            // 行内仍为横向封面流（选中的卡片变大、其他卡片变小变暗）。
            val compact = LocalConfiguration.current.screenWidthDp < 600
            val rowH = if (compact) 150.dp else 190.dp
            val gapY = if (compact) 18.dp else 28.dp
            val flowStep = with(density) { (rowH + gapY).toPx() }
            val animatedSection by animateFloatAsState(
                targetValue = selectedSection.toFloat(),
                animationSpec = tween(durationMillis = 260),
                label = "home-section-flow"
            )

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    // 触摸纵向滑动切换所选分区（行）
                    .pointerInput(sections.size, selectedSection) {
                        var accum = 0f
                        detectVerticalDragGestures(
                            onDragStart = { accum = 0f },
                            onVerticalDrag = { _, amount -> accum += amount },
                            onDragEnd = {
                                val threshold = with(density) { 48.dp.toPx() }
                                when {
                                    accum < -threshold && selectedSection < sections.size - 1 ->
                                        selectedSection += 1
                                    accum > threshold && selectedSection > 0 ->
                                        selectedSection -= 1
                                }
                            },
                            onDragCancel = {}
                        )
                    },
                contentAlignment = Alignment.Center
            ) {
                sections.forEachIndexed { idx, section ->
                    val pos = idx - animatedSection
                    val absPos = abs(pos)
                    FsdSectionRow(
                        section = section,
                        selectedIndex = selInSection.value[idx]
                            .coerceIn(0, (section.items.size - 1).coerceAtLeast(0)),
                        isFocused = idx == selectedSection,
                        onIndexChange = { newIdx ->
                            selInSection.value = selInSection.value.toMutableList()
                                .also { it[idx] = newIdx }
                        },
                        onActivate = ::activate,
                        onItemLongClick = { tileMenuKey = it.key },
                        onFocusUp = { selectedSection = (selectedSection - 1).coerceAtLeast(0) },
                        onFocusDown = {
                            selectedSection = (selectedSection + 1).coerceAtMost(sections.size - 1)
                        },
                        onFocusSelf = { selectedSection = idx },
                        modifier = Modifier
                            .align(Alignment.Center)
                            // 垂直封面流：选中的一行在中间、放大，上下行缩小变暗
                            .graphicsLayer {
                                translationY = pos * flowStep
                                scaleX = (1f - 0.34f * absPos).coerceAtLeast(0.5f)
                                scaleY = (1f - 0.34f * absPos).coerceAtLeast(0.5f)
                                this.alpha = (1f - 0.45f * absPos).coerceIn(0.35f, 1f)
                            }
                    )
                }
            }

            // 底部按键提示 + 状态条
            FsdButtonHints(
                hints = listOf(
                    FsdButtonHint("A", "选择", Fsd.BtnA),
                    FsdButtonHint("B", "返回", Fsd.BtnB)
                ),
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .padding(bottom = 8.dp)
            )
            FsdBottomBar(status = "主菜单")
        }
    }

    // === 磁贴选项对话框：自定义图标 / 恢复默认 ===
    tileMenuKey?.let { menuKey ->
        val tile = tiles.firstOrNull { it.key == menuKey }
        if (tile == null) {
            tileMenuKey = null
        } else {
            AlertDialog(
                onDismissRequest = { tileMenuKey = null },
                title = {
                    Text(
                        "磁贴选项 · ${tile.label}",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold
                    )
                },
                text = {
                    Column {
                        Text(
                            "可为本磁贴设置自定义图标（支持图片）。图标立即生效并持久保存。",
                            fontSize = 13.sp,
                            color = Color(0xFF4A5568)
                        )
                        // 卡片透明度调节：拖动即时预览，松手持久化（对任意磁贴都可用）
                        Spacer(Modifier.height(10.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "卡片透明度",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF1E2A3A),
                                modifier = Modifier.weight(1f)
                            )
                            Text(
                                "${((tileAlphas[tile.key] ?: 1f) * 100).toInt()}%",
                                fontSize = 13.sp,
                                color = Color(0xFF4A5568)
                            )
                        }
                        Slider(
                            value = tileAlphas[tile.key] ?: 1f,
                            onValueChange = { v ->
                                // 拖动中：只更新内存状态，磁贴实时预览
                                val m = LinkedHashMap<String, Float>(tileAlphas)
                                m[tile.key] = v
                                tileAlphasJson = JSONObject().apply {
                                    m.forEach { (k, a) -> put(k, a.toDouble()) }
                                }.toString()
                            },
                            onValueChangeFinished = {
                                // 松手才写盘一次，避免拖动过程高频 SharedPreferences 写入
                                val layout = PadLayoutStore.load(context)
                                PadLayoutStore.save(
                                    context,
                                    layout.copy { homeTileIconAlphas = tileAlphasJson }
                                )
                            },
                            valueRange = 0.05f..1f
                        )
                        // 一键把当前透明度同步到所有卡片 —— 修复“透明度没有
                        // 应用到所有卡片”：以前只能逐个磁贴长按调节，现在
                        // 提供批量应用入口，一次设置全部生效。
                        TextButton(
                            onClick = {
                                val v = (tileAlphas[tile.key] ?: 1f).coerceIn(0.05f, 1f)
                                val json = JSONObject().apply {
                                    tiles.forEach { t -> put(t.key, v.toDouble()) }
                                }.toString()
                                tileAlphasJson = json
                                val layout = PadLayoutStore.load(context)
                                PadLayoutStore.save(
                                    context,
                                    layout.copy { homeTileIconAlphas = json }
                                )
                            }
                        ) {
                            Text(
                                "将此透明度应用到所有卡片",
                                fontSize = 12.sp,
                                color = Color(0xFF2E86E0)
                            )
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        pendingTileKey = tile.key
                        tileMenuKey = null
                        runCatching { tileIconPicker.launch(arrayOf("image/*")) }
                    }) { Text("自定义图标") }
                },
                dismissButton = {
                    if (tile.iconPath != null) {
                        TextButton(onClick = {
                            val newMap = LinkedHashMap<String, String>(tileIcons)
                            newMap.remove(tile.key)
                            val json = JSONObject().apply { newMap.forEach { (k, v) -> put(k, v) } }.toString()
                            val layout = PadLayoutStore.load(context)
                            PadLayoutStore.save(context, layout.copy { homeTileIcons = json })
                            tileIconsJson = json
                            tileMenuKey = null
                        }) { Text("恢复默认") }
                    } else {
                        TextButton(onClick = { tileMenuKey = null }) { Text("取消") }
                    }
                }
            )
        }
    }
}
