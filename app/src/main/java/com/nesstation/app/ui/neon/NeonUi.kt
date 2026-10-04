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
 * ★★ Neon 新 UI（本轮新增）—— 全局风格状态 + 设计令牌 ★★
 *
 * 总设置「外观 → 主界面风格」切换 FSD 经典 / Neon 新 UI（图1/2/3 参考稿）。
 * 本对象是可观察的全局单例（同 AppBackgroundState 模式）：设置页改风格
 * 后主页 / 游戏库立即响应，无需等待路由重建。
 *
 *   "fsd"  → FSD 经典桌面（Xbox 360 Freestyle Dash 磁贴流）
 *   "neon" → Neon 街机厅（赛博切角风：图3 主页 + 图1 总游戏库 + 图2 弧形封面墙）
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

/** Neon 设计令牌 —— 深空底 + 霓虹青 + 警示红（对照图3 参考稿）。 */
object Neon {
    val Bg = Color(0xFF05070E)            // 深空黑蓝底
    val BgPanel = Color(0xFF0A1020)       // 面板底
    val BgPanelHi = Color(0xFF101A30)     // 面板高亮底
    val Accent = Color(0xFF00E5FF)        // 电光青（选中/边框/箭头）
    val AccentDim = Color(0xFF00798C)     // 青暗调
    val Red = Color(0xFFFF2D3F)           // 警示红（ALL GAMES 撞色）
    val Blue = Color(0xFF2F7BD9)          // 撞色蓝
    val Gold = Color(0xFFF2C200)          // 黄（Y 键）
    val Green = Color(0xFF6DBE28)         // 绿（A 键）
    val TextHi = Color(0xFFEAF6FF)        // 主文字
    val Text = Color(0xFFB8C7D9)          // 次文字
    val TextDim = Color(0xFF5E6F85)       // 弱文字
    val Line = Color(0xFF1C3350)          // 描线

    /** A/B/X/Y 手柄键色（与 FSD 按键色一致，保持肌肉记忆）。 */
    val BtnA = Color(0xFF6DBE28)
    val BtnB = Color(0xFFE23B3B)
    val BtnX = Color(0xFF2F7BD9)
    val BtnY = Color(0xFFF2C200)
}

/**
 * 切角（Chamfer）形状 —— 图3 参考稿的科技边框语言：
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

/** 平台 → 全称（图1 参考稿：顶部大标题展示系统完整名称）。 */
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

/** 平台 → 建档年代副标题（图1 参考稿：Logo 下的年代行）。 */
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
