package com.nesstation.app.ui.fsd

import android.app.ActivityManager
import android.content.Context
import android.graphics.BitmapFactory
import android.media.MediaPlayer
import android.net.Uri
import android.os.Environment
import android.os.StatFs
import android.view.Surface
import android.view.TextureView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.NetworkCheck
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.delay
import java.net.InetAddress
import java.net.NetworkInterface
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Xbox 360 Freestyle Dash (FSD) 风格的视觉组件集。
 *
 * FSD 桌面由四部分组成：
 *   [FsdBackdrop]    深蓝玻璃质感背景 + 斜向光带
 *   [FsdTopBar]      顶部系统状态条（CPU/内存/存储 + 时钟）
 *   [FsdBottomBar]   底部状态条（IP / 状态 / 日期 时间）
 *   [FsdButtonHints] 手柄按键提示（A/B/X/Y 彩色圆钮）
 */
object Fsd {
    /** 深蓝主背景（FSD 桌面壁纸基调） */
    val BgTop = Color(0xFF0A1D3A)
    val BgMid = Color(0xFF123B6E)
    val BgBottom = Color(0xFF081426)

    /** 磁贴蓝 / 黄（FSD 大磁贴的对角配色） */
    val TileBlueTop = Color(0xFF2E86E0)
    val TileBlueBottom = Color(0xFF0D4FA8)
    val TileYellowTop = Color(0xFFF7B500)
    val TileYellowBottom = Color(0xFFDE8A00)

    /** 状态条底色（半透明深色玻璃） */
    val BarBg = Color(0xCC061225)
    val BarText = Color(0xFFE8F1FF)
    val BarTextDim = Color(0xFF9FB6D4)
    val BarGreen = Color(0xFF7ED321)
    val BarYellow = Color(0xFFF8E71C)
    val BarRed = Color(0xFFE53935)

    /** 手柄按键标准色 */
    val BtnA = Color(0xFF6DBE28)   // 绿 — 确认/启动
    val BtnB = Color(0xFFE23B3B)   // 红 — 后退
    val BtnX = Color(0xFF2F7BD9)   // 蓝 — 切换/搜索
    val BtnY = Color(0xFFF2C200)   // 黄 — 选项/收藏
}

// ---------------------------------------------------------------------------
// FsdBackdrop — FSD 桌面壁纸：深蓝渐变 + 斜向光带
// ---------------------------------------------------------------------------

@Composable
fun FsdBackdrop(modifier: Modifier = Modifier) {
    Canvas(modifier = modifier.fillMaxSize()) { drawFsdBackdrop() }
}

private fun DrawScope.drawFsdBackdrop() {
    val w = size.width
    val h = size.height

    // 基底：垂直深蓝渐变
    drawRect(
        brush = Brush.verticalGradient(
            0f to Fsd.BgTop, 0.45f to Fsd.BgMid, 1f to Fsd.BgBottom
        )
    )

    // 中央高光（FSD 壁纸中心偏亮的放射感）
    drawRect(
        brush = Brush.radialGradient(
            colors = listOf(Color(0xFF2E6DB4).copy(alpha = 0.35f), Color.Transparent),
            center = Offset(w * 0.5f, h * 0.35f),
            radius = maxOf(w, h) * 0.75f
        )
    )

    // 斜向光带 — 从右上到左下的平行扫光
    val streaks = listOf(
        Streak(xFrac = 0.12f, widthFrac = 0.22f, alpha = 0.05f),
        Streak(xFrac = 0.38f, widthFrac = 0.10f, alpha = 0.07f),
        Streak(xFrac = 0.52f, widthFrac = 0.28f, alpha = 0.04f),
        Streak(xFrac = 0.72f, widthFrac = 0.12f, alpha = 0.08f),
        Streak(xFrac = 0.88f, widthFrac = 0.20f, alpha = 0.05f)
    )
    val diag = w + h
    streaks.forEach { s ->
        val cx = w * s.xFrac
        rotate(degrees = 24f, pivot = Offset(cx, h * 0.5f)) {
            drawRect(
                brush = Brush.horizontalGradient(
                    listOf(Color.Transparent, Color.White.copy(alpha = s.alpha), Color.Transparent)
                ),
                topLeft = Offset(cx - s.widthFrac * w, -h * 0.2f),
                size = androidx.compose.ui.geometry.Size(s.widthFrac * w, diag * 0.8f)
            )
        }
    }

    // 细亮线
    listOf(0.30f, 0.62f, 0.83f).forEach { xf ->
        val cx = w * xf
        rotate(degrees = 24f, pivot = Offset(cx, h * 0.5f)) {
            drawRect(
                color = Color.White.copy(alpha = 0.10f),
                topLeft = Offset(cx - 1.5f, -h * 0.2f),
                size = androidx.compose.ui.geometry.Size(3f, diag * 0.8f)
            )
        }
    }

    // 底部渐暗（让底部状态条更清晰）
    drawRect(
        brush = Brush.verticalGradient(
            0f to Color.Transparent, 1f to Color(0xFF04101F).copy(alpha = 0.55f),
            startY = h * 0.72f, endY = h
        )
    )
}

private data class Streak(val xFrac: Float, val widthFrac: Float, val alpha: Float)

// ---------------------------------------------------------------------------
// FsdCustomBackground — 用户自定义主页背景（图片 / 循环静音视频）
// ---------------------------------------------------------------------------

/**
 * 用户自定义的 FSD 桌面壁纸。uri 为空时调用方应回退到默认 [FsdBackdrop]。
 *
 *   - 图片：降采样解码（防止 4K 壁纸 OOM），ContentScale.Crop 铺满
 *   - 视频：TextureView + MediaPlayer 循环静音播放（无需额外播放器依赖），
 *           硬解不支持时静默回退默认壁纸
 *   - 上层叠加深蓝渐变遮罩，保证面包屑/磁贴/状态条前景可读
 */
@Composable
fun FsdCustomBackground(
    uriString: String,
    isVideo: Boolean,
    modifier: Modifier = Modifier
) {
    if (uriString.isBlank()) return
    val context = LocalContext.current
    if (isVideo) {
        var playerRef by remember(uriString) { mutableStateOf<MediaPlayer?>(null) }
        // key(uriString)：换视频时强制重建 TextureView + MediaPlayer（factory 只在组合时执行一次）
        androidx.compose.runtime.key(uriString) {
            AndroidView(
                factory = { ctx ->
                    TextureView(ctx).apply {
                        surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                            override fun onSurfaceTextureAvailable(
                                st: android.graphics.SurfaceTexture, width: Int, height: Int
                            ) {
                                try {
                                    val mp = MediaPlayer()
                                    mp.setDataSource(ctx, Uri.parse(uriString))
                                    mp.setSurface(Surface(st))
                                    mp.isLooping = true
                                    mp.setVideoScalingMode(MediaPlayer.VIDEO_SCALING_MODE_SCALE_TO_FIT)
                                    mp.setVolume(0f, 0f) // 静音视频壁纸
                                    mp.prepareAsync()
                                    mp.setOnPreparedListener { it.start() }
                                    playerRef = mp
                                } catch (_: Exception) {
                                    // 解码器不支持等异常：保持黑屏，调用方遮罩仍在
                                }
                            }
                            override fun onSurfaceTextureSizeChanged(
                                st: android.graphics.SurfaceTexture, width: Int, height: Int
                            ) {}
                            override fun onSurfaceTextureDestroyed(
                                st: android.graphics.SurfaceTexture
                            ): Boolean {
                                playerRef?.release()
                                playerRef = null
                                return true
                            }
                            override fun onSurfaceTextureUpdated(
                                st: android.graphics.SurfaceTexture
                            ) {}
                        }
                    }
                },
                modifier = modifier.fillMaxSize()
            )
        }
    } else {
        val bmp = remember(uriString) {
            FsdImaging.decodeUri(context, uriString, 1280, 800)
        }
        if (bmp != null) {
            Image(
                bitmap = bmp.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = modifier.fillMaxSize()
            )
        } else {
            FsdBackdrop(modifier)
        }
    }
    // 深蓝渐变遮罩 —— 自定义内容之上压一层 FSD 色调，前景文字/磁贴可读性优先
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    0f to Fsd.BgTop.copy(alpha = 0.55f),
                    0.45f to Fsd.BgMid.copy(alpha = 0.40f),
                    1f to Fsd.BgBottom.copy(alpha = 0.66f)
                )
            )
    )
}

/** 降采样位图解码工具（图片背景 / 磁贴自定义图标共用）。 */
object FsdImaging {
    private fun sampleSize(opts: BitmapFactory.Options, reqW: Int, reqH: Int): Int {
        val w = opts.outWidth.coerceAtLeast(1)
        val h = opts.outHeight.coerceAtLeast(1)
        var sample = 1
        while (w / (sample * 2) >= reqW && h / (sample * 2) >= reqH) sample *= 2
        return sample
    }

    fun decodeFile(path: String, reqW: Int, reqH: Int): android.graphics.Bitmap? = try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        if (bounds.outWidth <= 0 && bounds.outHeight <= 0) null
        else BitmapFactory.decodeFile(
            path,
            BitmapFactory.Options().apply { inSampleSize = sampleSize(bounds, reqW, reqH) }
        )
    } catch (_: Exception) { null }

    fun decodeUri(context: Context, uriString: String, reqW: Int, reqH: Int): android.graphics.Bitmap? = try {
        val uri = Uri.parse(uriString)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, bounds)
        }
        if (bounds.outWidth <= 0 && bounds.outHeight <= 0) null
        else context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(
                it, null,
                BitmapFactory.Options().apply { inSampleSize = sampleSize(bounds, reqW, reqH) }
            )
        }
    } catch (_: Exception) { null }
}

// ---------------------------------------------------------------------------
// 系统状态数据采集（真实数据，不做假温度）
// ---------------------------------------------------------------------------

data class FsdSysStats(
    val cpuLoad: Float = 0f,          // 0..1
    val memUsedFraction: Float = 0f,  // 0..1
    val memText: String = "",
    val intUsedFraction: Float = 0f,
    val intText: String = "",
    val extUsedFraction: Float = -1f, // <0 = 无外置存储
    val extText: String = ""
)

object FsdSysInfo {
    /** 读取一次系统状态（CPU 采样内置两次 /proc/stat 读取，~120ms）。 */
    fun sample(context: Context): FsdSysStats {
        // --- 内存 ---
        var memFrac = 0f
        var memText = ""
        try {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            if (am != null) {
                val mi = ActivityManager.MemoryInfo()
                am.getMemoryInfo(mi)
                if (mi.totalMem > 0) {
                    memFrac = (mi.totalMem - mi.availMem).toFloat() / mi.totalMem
                    memText = formatGb(mi.totalMem - mi.availMem) + "/" + formatGb(mi.totalMem)
                }
            }
        } catch (_: Exception) {}

        // --- 内置存储 ---
        var intFrac = 0f
        var intText = ""
        try {
            val stat = StatFs(Environment.getDataDirectory().absolutePath)
            val total = stat.totalBytes
            val avail = stat.availableBytes
            if (total > 0) {
                intFrac = (total - avail).toFloat() / total
                intText = formatGb(total - avail) + "/" + formatGb(total)
            }
        } catch (_: Exception) {}

        // --- 外置存储（可能不存在）---
        var extFrac = -1f
        var extText = ""
        try {
            @Suppress("DEPRECATION")
            val extDir = Environment.getExternalStorageDirectory()
            if (extDir != null && extDir.exists()) {
                val stat = StatFs(extDir.absolutePath)
                val total = stat.totalBytes
                val avail = stat.availableBytes
                if (total > 0) {
                    extFrac = (total - avail).toFloat() / total
                    extText = formatGb(total - avail) + "/" + formatGb(total)
                }
            }
        } catch (_: Exception) {}

        return FsdSysStats(
            cpuLoad = readCpuLoad(),
            memUsedFraction = memFrac, memText = memText,
            intUsedFraction = intFrac, intText = intText,
            extUsedFraction = extFrac, extText = extText
        )
    }

    /** 从 /proc/stat 计算 CPU 占用（两次采样，粗粒度即可）。 */
    private fun readCpuLoad(): Float {
        return try {
            val first = readProcStat() ?: return 0f
            Thread.sleep(120)
            val second = readProcStat() ?: return 0f
            val dTotal = (second.first - first.first).toFloat()
            val dIdle = (second.second - first.second).toFloat()
            if (dTotal <= 0f) 0f else ((dTotal - dIdle) / dTotal).coerceIn(0f, 1f)
        } catch (_: Exception) { 0f }
    }

    private fun readProcStat(): Pair<Long, Long>? {
        return try {
            val line = java.io.File("/proc/stat").bufferedReader().readLine() ?: return null
            val parts = line.split(Regex("\\s+")).filter { it.isNotBlank() }
            // cpu user nice system idle iowait irq softirq steal ...
            val idle = parts[4].toLong() + (parts.getOrElse(5) { "0" }).toLong()
            var total = 0L
            parts.drop(1).forEach { total += it.toLongOrNull() ?: 0L }
            total to idle
        } catch (_: Exception) { null }
    }

    fun formatGb(bytes: Long): String {
        val gb = bytes / (1024.0 * 1024.0 * 1024.0)
        return if (gb >= 10) "%.0fGB".format(gb) else "%.1fGB".format(gb)
    }

    /** 取本机局域网 IPv4（WLAN/Ethernet 优先）。 */
    fun localIp(): String {
        return try {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return ""
            var fallback = ""
            for (nif in interfaces) {
                if (!nif.isUp || nif.isLoopback) continue
                val name = nif.name.lowercase(Locale.US)
                for (addr in nif.inetAddresses) {
                    if (addr is InetAddress && !addr.isLoopbackAddress) {
                        val ip = addr.hostAddress ?: continue
                        if (ip.contains(':')) continue // 跳过 IPv6
                        if (name.startsWith("wlan") || name.startsWith("eth")) {
                            return ip
                        }
                        if (fallback.isEmpty()) fallback = ip
                    }
                }
            }
            fallback
        } catch (_: Exception) { "" }
    }
}

// ---------------------------------------------------------------------------
// FsdTopBar — 顶部系统状态条：CPU / 内存 / 存储 + 时钟
// ---------------------------------------------------------------------------

/** 单个状态项：标签 + 数值 + 迷你进度条 */
@Composable
private fun SysStatChip(
    label: String,
    value: String,
    fraction: Float,
    barWidth: Dp = 86.dp,
    compact: Boolean = false
) {
    val barColor = when {
        fraction >= 0.9f -> Fsd.BarRed
        fraction >= 0.7f -> Fsd.BarYellow
        else -> Fsd.BarGreen
    }
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = label,
                color = Fsd.BarTextDim,
                fontSize = if (compact) 9.sp else 10.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                softWrap = false
            )
            Spacer(Modifier.width(5.dp))
            Text(
                text = value,
                color = Fsd.BarText,
                fontSize = if (compact) 9.sp else 10.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
                softWrap = false   // 竖屏禁止换行 —— 旧版“外置 423GB/462GB”换行把顶栏撑高了一倍
            )
        }
        Spacer(Modifier.height(if (compact) 2.dp else 3.dp))
        Box(
            modifier = Modifier
                .width(barWidth)
                .height(5.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(Color.White.copy(alpha = 0.14f))
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(fraction.coerceIn(0.02f, 1f))
                    .height(5.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(
                        Brush.horizontalGradient(
                            listOf(barColor.copy(alpha = 0.65f), barColor)
                        )
                    )
            )
        }
    }
}

@Composable
fun FsdTopBar(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var stats by remember { mutableStateOf(FsdSysStats()) }
    var time by remember { mutableStateOf("") }

    // 紧凑模式（竖屏手机）：压缩高度、缩短进度条、GB 缩写、隐藏品牌字，
    // 外置与内置容量相同时不再重复展示 —— 解决“上方存储信息占用太多”的问题。
    val compact = LocalConfiguration.current.screenWidthDp < 600

    LaunchedEffect(Unit) {
        while (true) {
            // 采样含 120ms 的 /proc/stat 双读，放到 IO 线程避免卡 UI
            stats = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                FsdSysInfo.sample(context)
            }
            time = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
            delay(2500)
        }
    }

    fun abbrev(s: String) = if (compact) s.replace("GB", "G") else s

    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(Fsd.BarBg)
            .windowInsetsPadding(WindowInsets.statusBars)
            .padding(horizontal = if (compact) 10.dp else 14.dp, vertical = if (compact) 3.dp else 6.dp),
        horizontalArrangement = Arrangement.spacedBy(if (compact) 10.dp else 18.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        SysStatChip("CPU", "%d%%".format((stats.cpuLoad * 100).toInt()), stats.cpuLoad,
            barWidth = if (compact) 44.dp else 86.dp, compact = compact)
        SysStatChip("内存", abbrev(stats.memText), stats.memUsedFraction,
            barWidth = if (compact) 44.dp else 86.dp, compact = compact)
        SysStatChip("存储", abbrev(stats.intText), stats.intUsedFraction,
            barWidth = if (compact) 44.dp else 86.dp, compact = compact)
        if (stats.extUsedFraction >= 0f && (!compact || stats.extText != stats.intText)) {
            SysStatChip("外置", abbrev(stats.extText), stats.extUsedFraction,
                barWidth = if (compact) 44.dp else 86.dp, compact = compact)
        }
        Spacer(Modifier.weight(1f))
        if (!compact) {
            Text(
                text = "NES STATION",
                color = Fsd.BarTextDim,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.5.sp
            )
            Spacer(Modifier.width(10.dp))
        }
        Text(
            text = time,
            color = Fsd.BarText,
            fontSize = if (compact) 12.sp else 13.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace
        )
    }
}

// ---------------------------------------------------------------------------
// FsdToolButton — FSD 工具按钮：顶部图标 + 小字标签（TV / D-pad 友好）
// ---------------------------------------------------------------------------

/**
 * FSD 工具按钮 — 顶部图标 + 小字标签。
 *
 * 游戏库 / 在线游戏 / SWF 列表等 FSD 风页面共用；原先只在 LibraryScreen
 * 里私有定义，现提为公共组件供各页复用，保证视觉一致。
 */
@Composable
fun FsdToolButton(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 3.dp)
    ) {
        Icon(
            icon,
            contentDescription = label,
            tint = Color.White.copy(alpha = 0.88f),
            modifier = Modifier.size(18.dp)
        )
        Text(
            label,
            color = Fsd.BarTextDim,
            fontSize = 9.sp,
            maxLines = 1
        )
    }
}

// ---------------------------------------------------------------------------
// FsdBottomBar — 底部状态条：IP / 状态 / 日期 时间
// ---------------------------------------------------------------------------

@Composable
fun FsdBottomBar(
    status: String = "空闲",
    modifier: Modifier = Modifier
) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) { now = System.currentTimeMillis(); delay(1000) }
    }
    val date = remember(now) {
        SimpleDateFormat("yyyy-MM-dd EEE", Locale.getDefault()).format(Date(now))
    }
    val time = remember(now) {
        SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(now))
    }
    val ip = remember { FsdSysInfo.localIp() }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(Fsd.BarBg)
            .windowInsetsPadding(WindowInsets.navigationBars)
            .padding(horizontal = 14.dp, vertical = 5.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = Icons.Rounded.NetworkCheck,
            contentDescription = null,
            tint = Fsd.BarTextDim,
            modifier = Modifier.size(14.dp)
        )
        Text(
            text = ip.ifEmpty { "未联网" },
            color = Fsd.BarText,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            fontFamily = FontFamily.Monospace
        )
        Text(
            text = "状态: $status",
            color = Fsd.BarTextDim,
            fontSize = 11.sp
        )
        Spacer(Modifier.weight(1f))
        Text(
            text = date,
            color = Fsd.BarTextDim,
            fontSize = 11.sp
        )
        Spacer(Modifier.width(12.dp))
        Text(
            text = time,
            color = Fsd.BarText,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace
        )
    }
}

// ---------------------------------------------------------------------------
// FsdButtonHints — 手柄按键提示（Xbox 彩色圆钮）
// ---------------------------------------------------------------------------

data class FsdButtonHint(val letter: String, val label: String, val color: Color)

/** A=确认 B=后退 X=切换 Y=选项（Xbox 标准配色） */
@Composable
fun FsdButtonHints(
    hints: List<FsdButtonHint>,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        hints.forEach { hint ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(20.dp)
                        .clip(CircleShape)
                        .background(
                            Brush.radialGradient(
                                listOf(hint.color, hint.color.copy(alpha = 0.55f))
                            )
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = hint.letter,
                        color = Color.White,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Black
                    )
                }
                Spacer(Modifier.width(5.dp))
                Text(
                    text = hint.label,
                    color = Fsd.BarText,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}
