package com.nesstation.app.ui.neon

import android.content.Context
import androidx.compose.foundation.shape.GenericShape
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import com.nesstation.app.core.model.GamePlatform
import com.nesstation.app.core.storage.PadLayoutStore

/**
 * ★★ Neon 3D 新 UI（完全重制版）—— 全局风格状态 + 设计令牌 ★★
 *
 * 总设置「外观 → 主界面风格」切换 FSD 经典 / Neon 3D 新UI。
 * 本对象是可观察的全局单例（同 AppBackgroundState 模式）：设置页改风格
 * 后主页 / 游戏库 / 设置页立即响应，无需等待路由重建。
 *
 *   "fsd"  → FSD 经典桌面（Xbox 360 Freestyle Dash 磁贴流，原风格不动）
 *   "neon" → Neon 3D 游戏站（本包全新实现，与 FSD 零共享组件）：
 *              · 主页    —— 顶部菜单行 + 中央 3D 封面流（不含核心列表）
 *              · 游戏库  —— 左侧核心列表 + 每核心 3D 封面滚动（NeonFlow）
 *              · 总设置  —— 左侧分类导航 + Neon 面板
 */
object NeonUi {
    /** 当前风格（"fsd" | "neon"）；首次读取前由 ensureLoaded 从持久化填充。 */
    var style by mutableStateOf("fsd")
        private set

    @Volatile
    private var inited = false

    /** 进程内首次调用时从 PadLayoutStore 载入持久化风格（幂等）。 */
    fun ensureLoaded(ctx: Context) {
        if (inited) return
        synchronized(this) {
            if (inited) return
            style = PadLayoutStore.load(ctx).homeUiStyle.let { if (it == "neon") "neon" else "fsd" }
            inited = true
        }
    }

    /** 设置页切换入口：更新全局状态并立即持久化。 */
    fun set(ctx: Context, value: String) {
        val v = if (value == "neon") "neon" else "fsd"
        style = v
        inited = true
        PadLayoutStore.save(ctx, PadLayoutStore.load(ctx).copy { homeUiStyle = v })
    }

    val isNeon: Boolean get() = style == "neon"
}

/**
 * Neon 3D 设计令牌 —— 深空底 + 电光青 + 警示红。
 * 全部页面（主页/游戏库/设置）共用同一套令牌，保证风格统一。
 */
object Neon {
    // ---- 底色 ----
    val Bg = Color(0xFF05070E)            // 深空黑蓝底
    val BgDeep = Color(0xFF02030A)        // 更深的底（渐变端点）
    val BgPanel = Color(0xFF0A1020)       // 面板底
    val BgPanelHi = Color(0xFF101A30)     // 面板高亮底
    val Line = Color(0xFF1C3350)          // 描线

    // ---- 主色 ----
    val Accent = Color(0xFF00E5FF)        // 电光青（选中/边框/箭头）
    val AccentDim = Color(0xFF00798C)     // 青暗调
    val Red = Color(0xFFFF2D3F)           // 警示红（ALL GAMES 撞色）
    val Blue = Color(0xFF2F7BD9)          // 撞色蓝
    val Gold = Color(0xFFF2C200)          // 黄（Y 键 / 收藏）
    val Green = Color(0xFF6DBE28)         // 绿（A 键）
    val Purple = Color(0xFF8B5CF6)        // 紫（在线/对战点缀）

    // ---- 文字 ----
    val TextHi = Color(0xFFEAF6FF)        // 主文字
    val Text = Color(0xFFB8C7D9)          // 次文字
    val TextDim = Color(0xFF5E6F85)       // 弱文字

    // ---- 手柄键色（A/B/X/Y，与 FSD 按键色一致保持肌肉记忆）----
    val BtnA = Green
    val BtnB = Color(0xFFE23B3B)
    val BtnX = Blue
    val BtnY = Gold
}

/**
 * 切角（Chamfer）形状 —— Neon 3D 的科技边框语言：
 * 左上 + 右下两角斜切（对角对称），其余直角。
 * [fraction] 为斜切边相对短边的比例（0.12 ≈ 卡片的 12%）。
 */
fun neonChamfer(fraction: Float = 0.14f): GenericShape = GenericShape { size, _ ->
    val c = size.minDimension * fraction.coerceIn(0.02f, 0.5f)
    moveTo(c, 0f)
    lineTo(size.width, 0f)
    lineTo(size.width, size.height - c)
    lineTo(size.width - c, size.height)
    lineTo(0f, size.height)
    lineTo(0f, c)
    close()
}

/** 平台 → 全称（游戏库「全部」视图大标题 / 设置页等处使用）。 */
fun platformFullName(p: GamePlatform): String = when (p) {
    GamePlatform.NES -> "Nintendo Entertainment System"
    GamePlatform.SFC -> "Super Famicom / SNES"
    GamePlatform.GB -> "Game Boy / Game Boy Color"
    GamePlatform.GBA -> "Game Boy Advance"
    GamePlatform.MD -> "SEGA Mega Drive / Genesis"
    GamePlatform.PCE -> "PC Engine / TurboGrafx-16"
    GamePlatform.PSX -> "Sony PlayStation"
    GamePlatform.PS2 -> "PlayStation 2"
    GamePlatform.NDS -> "Nintendo DS / DSi"
    GamePlatform.N3DS -> "Nintendo 3DS"
    GamePlatform.NGCWII -> "GameCube / Wii"
    GamePlatform.DC -> "SEGA Dreamcast"
    GamePlatform.ARCADE -> "Arcade / FBNeo"
    GamePlatform.DOS -> "MS-DOS"
    GamePlatform.JAVA -> "Java Micro Edition"
}

/** 平台 → 建档年代副标题。 */
fun platformEra(p: GamePlatform): String = when (p) {
    GamePlatform.NES -> "1983 · 8-BIT"
    GamePlatform.SFC -> "1990 · 16-BIT"
    GamePlatform.GB -> "1989 · HANDHELD"
    GamePlatform.GBA -> "2001 · 32-BIT"
    GamePlatform.MD -> "1988 · 16-BIT"
    GamePlatform.PCE -> "1987 · 8/16-BIT"
    GamePlatform.PSX -> "1994 · 32-BIT CD"
    GamePlatform.PS2 -> "2000 · 128-BIT"
    GamePlatform.NDS -> "2004 · DUAL SCREEN"
    GamePlatform.N3DS -> "2011 · GLASSLESS 3D"
    GamePlatform.NGCWII -> "2001 / 2006 · OPTICAL"
    GamePlatform.DC -> "1998 · 128-BIT"
    GamePlatform.ARCADE -> "1978 · COIN-OP"
    GamePlatform.DOS -> "1981 · X86"
    GamePlatform.JAVA -> "2000 · J2ME"
}

/** 格式化游戏时长（playTimeMs → "3.2小时" / "42分钟" / "—")。 */
fun neonFormatPlayTime(ms: Long): String = when {
    ms <= 0L -> "—"
    ms < 60 * 60 * 1000L -> "${ms / 60_000L}分钟"
    else -> String.format("%.1f小时", ms / 3_600_000.0)
}

/** 格式化「最近游玩」相对时间。 */
fun neonFormatLastPlayed(ts: Long, now: Long = System.currentTimeMillis()): String = when {
    ts <= 0L -> "未游玩"
    now - ts < 60_000L -> "刚刚"
    now - ts < 3_600_000L -> "${(now - ts) / 60_000L}分钟前"
    now - ts < 86_400_000L -> "${(now - ts) / 3_600_000L}小时前"
    now - ts < 30L * 86_400_000L -> "${(now - ts) / 86_400_000L}天前"
    else -> "很久以前"
}
