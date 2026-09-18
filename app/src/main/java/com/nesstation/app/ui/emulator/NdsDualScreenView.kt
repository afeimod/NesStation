package com.nesstation.app.ui.emulator

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.Choreographer
import android.view.MotionEvent
import android.view.View
import com.nesstation.app.core.engine.NdsCoreEngine

/**
 * 共享的滤镜图案生成器 —— 与 EmulatorScreen.FilterOverlay 使用完全相同的
 * 扫描线 / CRT / 点阵图案，保证 NDS 自由布局 (custom) 模式下看到的滤镜效果
 * 与 4:3、3:2 等普通缩放模式一致（修复"自由布局下全局滤镜失效"）。
 */
internal object NdsFilterPatterns {
    /** Scanline pattern: 2px wide, 4px tall — 3 transparent rows + 1 dark row. */
    fun createScanlinePattern(): Bitmap {
        val bmp = Bitmap.createBitmap(2, 4, Bitmap.Config.ARGB_8888)
        for (x in 0..1) {
            bmp.setPixel(x, 0, 0x00000000)
            bmp.setPixel(x, 1, 0x00000000)
            bmp.setPixel(x, 2, 0x00000000)
            bmp.setPixel(x, 3, 0x8C000000L.toInt()) // 55% black
        }
        return bmp
    }

    /** CRT pattern: 3px RGB phosphor triads, 6px tall, bottom row = scanline. */
    fun createCrtPattern(): Bitmap {
        val bmp = Bitmap.createBitmap(3, 6, Bitmap.Config.ARGB_8888)
        for (y in 0..4) {
            bmp.setPixel(0, y, 0x26FF0000) // red phosphor
            bmp.setPixel(1, y, 0x2600FF00) // green phosphor
            bmp.setPixel(2, y, 0x260000FF) // blue phosphor
        }
        for (x in 0..2) {
            bmp.setPixel(x, 5, 0x80000000L.toInt()) // 50% black scanline
        }
        return bmp
    }

    /** Dot pattern: LCD dot matrix with smoothstep circular alpha. */
    fun createDotPattern(): Bitmap {
        val size = 4
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val center = (size - 1) / 2.0f
        val dotRadius = 1.0f
        val maxDist = kotlin.math.sqrt(center * center + center * center)
        for (y in 0 until size) {
            for (x in 0 until size) {
                val dx = x - center
                val dy = y - center
                val dist = kotlin.math.sqrt(dx * dx + dy * dy)
                val t = ((dist - dotRadius) / (maxDist - dotRadius)).coerceIn(0f, 1f)
                val smoothT = t * t * (3 - 2 * t)
                val alpha = (smoothT * 128f).toInt().coerceIn(0, 255)
                bmp.setPixel(x, y, (alpha shl 24))
            }
        }
        return bmp
    }

    /**
     * 视频滤镜字符串 → 叠加层图案类型。
     * 与 EmulatorScreen 中 FilterOverlay 的判定保持一致：
     * scanline / crt / dot 以及 *_dot 组合会绘制叠加图案。
     */
    fun overlayPatternType(videoFilter: String): String? = when (videoFilter) {
        "scanline" -> "scanline"
        "crt" -> "crt"
        "dot" -> "dot"
        "xbr_dot", "4xbr_dot", "hq4x_dot" -> "dot"
        else -> null
    }
}

/**
 * NDS 双屏自由布局渲染视图（videoScale == "custom" 时使用）。
 *
 * 参照 melonDS 官方 Android 布局模型：上屏 / 下屏是两个独立组件，各占一个
 * 独立矩形，可分别缩放/移动。melonDS libretro 核心在 `melonds_screen_layout`
 * 选中布局内把两屏合成到**一个**帧缓冲；DraStic 引擎则由渲染线程把
 * getScreenBuffers 的两屏合成为 256x384。两者都从
 * [NdsCoreEngine.frameBuffer] 按布局切出上屏/下屏源区域，绘制到两个目标矩形。
 *
 * - 全局滤镜（"自由布局下滤镜失效"修复）：
 *   - 叠加类滤镜（扫描线 / CRT / 点阵）在绘制完两屏后，把与
 *     FilterOverlay 完全相同的图案平铺到每个屏幕的目标矩形上（CRT 额外
 *     绘制边缘暗角）。
 *   - 放大类滤镜（HQ2X / HQ4X / XBR）由原生层在 cb_video 中处理 —— 无
 *     surface 时滤镜结果写入 s_filteredFrame，Kotlin 端通过
 *     [NdsCoreEngine.frameBuffer] / [NdsCoreEngine.filteredVideoWidth] 拉取放大后的
 *     合成帧，切片比例不变（2x/4x 等比放大），绘制路径完全一致。
 *     （DraStic 引擎：渲染线程经 NdsNative.applyUpscaleFilterArgb 把
 *     frameBuffer 替换为放大后的合成帧，行为与 melonDS 一致；叠加型滤镜
 *     scanline/crt/dot 同样在本视图绘制。）
 * - 触摸（官方 melonDS 架构）：触点位于下屏目标矩形内时，直接把触点线性
 *   映射为 DS 下屏像素坐标 (0..255, 0..191) 并调用 [NdsCoreEngine.setTouchInputDirect]。
 *   不再经过"合成帧归一化坐标"的间接层 —— 自由布局 / 屏幕间距 / GL gap /
 *   任何布局都不会影响映射。上屏内点击释放触摸。
 * - 刷新：通过 Choreographer 在 VSync 上轮询 [NdsCoreEngine.frameStamp]，
 *   仅在新帧到达时 invalidate() —— 90/120Hz 屏幕上不再做 2 倍冗余绘制。
 */
class NdsDualScreenView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    /** 需要渲染的 NDS 引擎（melonDS [NdsCoreEngine] 或 DraStic 引擎，可能随 GameSurfaceView 重建而重新注入）。 */
    var engine: NdsCoreEngine? = null

    /** 核心当前合成布局："Top/Bottom" | "Bottom/Top" | "Left/Right" | "Right/Left" | "Top Only" | "Bottom Only"。 */
    var screenLayout: String = "Top/Bottom"

    /** 上屏目标矩形（归一化 0..1 [left, top, right, bottom]）。 */
    private val topRect = floatArrayOf(0.05f, 0.05f, 0.95f, 0.48f)

    /** 下屏目标矩形（归一化 0..1 [left, top, right, bottom]，也是触屏区域）。 */
    private val bottomRect = floatArrayOf(0.05f, 0.52f, 0.95f, 0.98f)

    /** UI 被菜单/设置等遮挡时，不消费触摸/按键。 */
    var uiBlocked: Boolean = false

    /** 当前视频滤镜（"none" | "scanline" | "crt" | "dot" | "hq2x" | ... 同 PadLayoutStore）。 */
    var videoFilter: String = "none"
        set(value) {
            if (field != value) {
                field = value
                rebuildFilterPaints()
                invalidate()
            }
        }

    private val paint: Paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

    private var cacheBitmap: Bitmap? = null
    private var cacheW = 0
    private var cacheH = 0

    // --- 叠加滤镜（scanline / crt / dot）绘制状态 -------------------------
    // 图案 BitmapShader 的 Paint 只在滤镜切换时重建一次；CRT 暗角 Paint 按
    // 目标矩形尺寸缓存，矩形/视图尺寸变化时重建（不在每帧分配）。
    private var patternPaint: Paint? = null
    private var vignetteTopPaint: Paint? = null
    private var vignetteBottomPaint: Paint? = null
    private var vignetteTopKey = ""
    private var vignetteBottomKey = ""
    private var lastDrawnStamp = -1L
    private var rectsDirty = true

    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (isAttachedToWindow) {
                // 仅在核心产生了新帧（或矩形刚变化）时重绘 —— 高刷新率屏幕
                // (90/120Hz) 上避免对 60fps 的模拟帧流做 2 倍冗余绘制。
                val eng = engine
                val stamp = if (eng != null && eng.isLoaded) eng.frameStamp() else -1L
                if (stamp != lastDrawnStamp || rectsDirty) {
                    lastDrawnStamp = stamp
                    rectsDirty = false
                    invalidate()
                }
                Choreographer.getInstance().postFrameCallback(this)
            }
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        Choreographer.getInstance().postFrameCallback(frameCallback)
    }

    override fun onDetachedFromWindow() {
        Choreographer.getInstance().removeFrameCallback(frameCallback)
        super.onDetachedFromWindow()
    }

    /** 设置双屏目标矩形（归一化 0..1）。 */
    fun setRects(top: FloatArray, bottom: FloatArray) {
        setRectInto(topRect, top)
        setRectInto(bottomRect, bottom)
        rectsDirty = true
        invalidate()
    }

    fun getTopRect(): FloatArray = topRect.copyOf()
    fun getBottomRect(): FloatArray = bottomRect.copyOf()

    private fun setRectInto(dst: FloatArray, src: FloatArray) {
        dst[0] = src[0]; dst[1] = src[1]; dst[2] = src[2]; dst[3] = src[3]
    }

    private fun rebuildFilterPaints() {
        val patternType = NdsFilterPatterns.overlayPatternType(videoFilter)
        if (patternType == null) {
            patternPaint = null
            vignetteTopPaint = null
            vignetteBottomPaint = null
            vignetteTopKey = ""
            vignetteBottomKey = ""
            return
        }
        val bmp = when (patternType) {
            "scanline" -> NdsFilterPatterns.createScanlinePattern()
            "crt" -> NdsFilterPatterns.createCrtPattern()
            else -> NdsFilterPatterns.createDotPattern()
        }
        patternPaint = Paint().apply {
            shader = BitmapShader(bmp, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
            isFilterBitmap = false
            isAntiAlias = false
        }
        // CRT 暗角画笔按矩形尺寸缓存 —— 键失效时在 onDraw 中重建。
        vignetteTopKey = ""
        vignetteBottomKey = ""
    }

    /** 为某个屏幕矩形创建 CRT 边缘暗角 Paint（按矩形尺寸缓存，调用方管理键）。 */
    private fun makeVignettePaint(rect: RectF): Paint {
        val radius = kotlin.math.min(rect.width(), rect.height()) * 0.7f
        return Paint().apply {
            shader = RadialGradient(
                rect.centerX(), rect.centerY(), radius.coerceAtLeast(1f),
                intArrayOf(Color.TRANSPARENT, (0.35f * 255).toInt().shl(24)),
                floatArrayOf(0f, 1f),
                Shader.TileMode.CLAMP
            )
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val eng = engine ?: return
        if (!eng.isLoaded) return

        // 自由布局模式下 frameBuffer 保存的是（可能被 HQ2X/HQ4X/XBR 放大
        // 过的）合成帧；切片比例与原始帧完全一致。
        val vw = eng.filteredVideoWidth().coerceAtLeast(1)
        val vh = eng.filteredVideoHeight().coerceAtLeast(1)
        val fb = eng.frameBuffer
        if (fb.size < vw * vh) return

        if (cacheBitmap == null || cacheW != vw || cacheH != vh) {
            cacheBitmap = Bitmap.createBitmap(vw, vh, Bitmap.Config.ARGB_8888)
            cacheW = vw
            cacheH = vh
        }
        val bmp = cacheBitmap ?: return
        bmp.setPixels(fb, 0, vw, 0, 0, vw, vh)

        val viewW = width.coerceAtLeast(1)
        val viewH = height.coerceAtLeast(1)

        // 按核心合成布局切出上屏/下屏源区域
        val (topSrc, bottomSrc) = computeSrcRects(vw, vh)

        val dstTop = rectToViewRect(topRect, viewW, viewH)
        val dstBottom = rectToViewRect(bottomRect, viewW, viewH)

        if (topSrc != null) {
            val srcRect = Rect(
                topSrc.left.toInt(),
                topSrc.top.toInt(),
                topSrc.right.toInt(),
                topSrc.bottom.toInt()
            )
            canvas.drawBitmap(bmp, srcRect, dstTop, paint)
        }
        if (bottomSrc != null) {
            val srcRect = Rect(
                bottomSrc.left.toInt(),
                bottomSrc.top.toInt(),
                bottomSrc.right.toInt(),
                bottomSrc.bottom.toInt()
            )
            canvas.drawBitmap(bmp, srcRect, dstBottom, paint)
        }

        // === 叠加类全局滤镜（与 FilterOverlay 相同的图案/暗角） ===
        patternPaint?.let { pp ->
            if (topSrc != null) canvas.drawRect(dstTop, pp)
            if (bottomSrc != null) canvas.drawRect(dstBottom, pp)
        }
        if (videoFilter == "crt") {
            val topKey = "${dstTop.width().toInt()}x${dstTop.height().toInt()}"
            if (vignetteTopKey != topKey || vignetteTopPaint == null) {
                vignetteTopPaint = makeVignettePaint(dstTop)
                vignetteTopKey = topKey
            }
            vignetteTopPaint?.let { canvas.drawRect(dstTop, it) }

            val bottomKey = "${dstBottom.width().toInt()}x${dstBottom.height().toInt()}"
            if (vignetteBottomKey != bottomKey || vignetteBottomPaint == null) {
                vignetteBottomPaint = makeVignettePaint(dstBottom)
                vignetteBottomKey = bottomKey
            }
            vignetteBottomPaint?.let { canvas.drawRect(dstBottom, it) }
        }
    }

    /**
     * 根据核心合成布局计算上屏/下屏在合成帧中的源矩形。
     *
     * 布局由 melonDS libretro core `melonds_screen_layout` 决定：
     *   Top/Bottom  → 上屏在上半、下屏在下半
     *   Bottom/Top  → 下屏在上半、上屏在下半
     *   Left/Right  → 上屏在左半、下屏在右半
     *   Right/Left  → 上屏在右半、下屏在左半
     *   Top Only    → 只有上屏
     *   Bottom Only → 只有下屏
     *
     * 上下排列时，合成帧为 256x(384+GAP)：两屏各 192 行，中间夹着
     * 黑色 gap 行（GL 合成器固定 GAP=2，软件路径按 `melonds_screen_gap`
     * 选项可为 0..20）。因此不能简单 vh/2 对半切，需按 192*(vw/256) 推算
     * 单屏高度，gap 行落在两屏之间而不被划入任何一屏。
     *
     * 该比例推导对放大 2x/4x 的过滤帧同样成立（等比缩放）。
     */
    private fun computeSrcRects(vw: Int, vh: Int): Pair<RectF?, RectF?> {
        val halfW = vw / 2
        // 单屏宽 256，高度 192（按合成帧宽度同比推算，适配 GL/软件两种路径）
        val scale = vw / 256f
        val screenH = (192 * scale).toInt().coerceIn(1, vh)
        val gapH = (vh - 2 * screenH).coerceAtLeast(0)   // 两屏中间的黑行间隔
        val bottomTop = screenH + gapH                    // 下屏起始行（Top/Bottom）
        return when (screenLayout) {
            "Bottom/Top" -> RectF(0f, bottomTop.toFloat(), vw.toFloat(), vh.toFloat()) to
                            RectF(0f, 0f, vw.toFloat(), screenH.toFloat())
            "Left/Right" -> RectF(0f, 0f, halfW.toFloat(), vh.toFloat()) to
                            RectF(halfW.toFloat(), 0f, vw.toFloat(), vh.toFloat())
            "Right/Left" -> RectF(halfW.toFloat(), 0f, vw.toFloat(), vh.toFloat()) to
                            RectF(0f, 0f, halfW.toFloat(), vh.toFloat())
            "Top Only" -> RectF(0f, 0f, vw.toFloat(), vh.toFloat()) to null
            "Bottom Only" -> null to RectF(0f, 0f, vw.toFloat(), vh.toFloat())
            else -> RectF(0f, 0f, vw.toFloat(), screenH.toFloat()) to
                    RectF(0f, bottomTop.toFloat(), vw.toFloat(), vh.toFloat())
        }
    }

    private fun rectToViewRect(rect: FloatArray, viewW: Int, viewH: Int): RectF =
        RectF(
            rect[0] * viewW,
            rect[1] * viewH,
            rect[2] * viewW,
            rect[3] * viewH
        )

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (uiBlocked) return false
        val eng = engine ?: return false
        if (!eng.isLoaded) return false

        val viewW = width.coerceAtLeast(1)
        val viewH = height.coerceAtLeast(1)

        return when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                val bottomDst = rectToViewRect(bottomRect, viewW, viewH)
                if (bottomDst.contains(event.x, event.y)) {
                    // 官方 melonDS 架构：触点在下屏矩形内直接线性映射为
                    // DS 下屏像素坐标 (0..255, 0..191)。与核心的合成布局 /
                    // 屏幕间距 / GL gap 完全解耦 —— 自由布局下触摸可靠。
                    val t = ((event.x - bottomDst.left) / bottomDst.width()).coerceIn(0f, 1f)
                    val s = ((event.y - bottomDst.top) / bottomDst.height()).coerceIn(0f, 1f)
                    val px = (t * 255.5f).toInt().coerceIn(0, 255)
                    val py = (s * 191.5f).toInt().coerceIn(0, 191)
                    eng.setTouchInputDirect(px, py, true)
                } else {
                    // 点击不在触屏（下屏）矩形内 → 释放触摸
                    eng.setTouchInputDirect(0, 0, false)
                }
                true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                eng.setTouchInputDirect(0, 0, false)
                true
            }
            else -> false
        }
    }
}
