package com.nesstation.app.ui.emulator

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.Choreographer
import android.view.View
import com.nesstation.app.core.engine.EmulatorEngine
import kotlin.math.max
import kotlin.math.min

/**
 * 仿电视机（CRT 弧面）显示视图 —— 原生机型画布路径。
 *
 * 参照真机 CRT 照片（Metal Slug 实拍）：画面不是平铺的矩形，而是整体
 * 「略微凸起」—— 边中点处画面触到屏幕边缘、越靠四角越以弧线向内收进，
 * 四角被圆弧玻璃压暗，叠加扫描线 / 暗角 / 暗边框 / 上部玻璃高光。
 *
 * 几何与 J2ME GL 路径 (J2meFilterShaders.TV_GLSL_HELPERS → nsCurve) 完全
 * 一致，保证各平台「仿电视机」观感统一：
 *
 *   GLSL 正向（屏幕点 c → 采样点 s）：
 *       s.x = c.x * (1 + (c.y / 5.4)²)
 *       s.y = c.y * (1 + (c.x / 3.6)²)
 *
 * drawBitmapMesh 的隐式源映射是「纹理均匀网格 → 顶点位置」，因此本视图
 * 对每个纹理网格点求 nsCurve 的逆映射（定点迭代），把该纹素应出现的屏幕
 * 位置作为顶点坐标 —— 画面边界由几何直接形成弧线（边中点触边、四角内收
 * 约 3%~7%），边界之外不绘制，露出机身黑底。效果即照片中箭头所指的
 * 「边缘略微凸起」。
 *
 * 外观叠加（与 FilterOverlay 的 tv 分支同密度）：
 *   1. 扫描线图案（NdsFilterPatterns.createScanlinePattern，4px 周期）
 *   2. 径向暗角（边缘 62% 黑 —— 比 CRT 滤镜更浓的电视玻璃观感）
 *   3. 四角圆弧外填黑 + 圆角暗边框（短边 4.5% 圆角，70% 黑描边）
 *   4. 上部玻璃高光弧带（微弱白色渐变 —— 立体感）
 *
 * 帧来源：引擎在无 Surface 时会把每帧写入 [EmulatorEngine.frameBuffer]
 * （见各引擎模拟线程的 `if (!hasSurface)` 分支），本视图用 Choreographer
 * 轮询拉帧 → 缓存 Bitmap → drawBitmapMesh 弧面绘制。菜单遮挡
 * （[uiBlocked]）时不停止绘制，与 NdsDualScreenView 行为一致。
 *
 * 性能：所有 Paint/Shader/网格数组按视图尺寸缓存，每帧只有一次
 * setPixels + drawBitmapMesh + 三个渐变矩形；Choreographer 回调里按
 * 时间节流（≥14ms），60/90/120Hz 屏幕上都不会做超帧率冗余绘制。
 *
 * 适用范围：单屏原生机型。NDS 双屏（触摸映射依赖平面几何）与 PS2
 * （ARMSX2 必须持有真实 Surface）不走本视图，保持原路径。
 */
class TvCurvedGameView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    /** 渲染引擎（帧缓冲来源），由 EmulatorScreen 注入，可随重组更新。 */
    var engine: EmulatorEngine? = null

    /**
     * UI 被菜单/设置等遮挡标志。保留与 SurfaceView 分支对称的注入接口；
     * 当前绘制策略：遮挡时仍继续拉帧绘制（与 NdsDualScreenView 一致），
     * 因此本标志暂不改变绘制行为，仅供调试/后续策略使用。
     */
    var uiBlocked: Boolean = false

    private companion object {
        // 与 J2ME GL 路径 nsCurve 的 vec2(5.4, 3.6) 常量一致
        const val CURVE_X = 5.4f
        const val CURVE_Y = 3.6f
        const val MESH_N = 40                       // 40×40 网格（41×41 顶点）
        const val CURVE_ITER = 6                    // 逆映射定点迭代次数
        const val MIN_FRAME_INTERVAL_NS = 14_000_000L  // ~71fps 上限，防高刷屏冗余绘制
        const val CORNER_RADIUS_RATIO = 0.045f      // 圆角半径 = 短边 4.5%
    }

    // ── 帧位图缓存 ────────────────────────────────────────────────────────
    private var frameBitmap: Bitmap? = null
    private var frameW = 0
    private var frameH = 0

    // ── 弧面网格（视图尺寸变化时重建） ────────────────────────────────────
    private val meshVerts = FloatArray((MESH_N + 1) * (MESH_N + 1) * 2)
    private var meshBuilt = false

    // ── 外观 Paint（按需重建） ────────────────────────────────────────────
    private val meshPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private var scanlinePaint: Paint? = null       // 扫描线图案（REPEAT shader）
    private var vignettePaint: Paint? = null       // 径向暗角
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = 0xB3000000.toInt()                 // 70% 黑 —— 与 J2ME CPU 路径一致
    }
    private var glossPaint: Paint? = null          // 上部玻璃高光
    private val clipPath = Path()                  // 圆角裁切路径（每帧 rewind 复用）
    private val workRect = RectF()                 // 临时矩形（每帧 set 复用）

    private var lastFrameNs = 0L

    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (isAttachedToWindow) {
                if (frameTimeNanos - lastFrameNs >= MIN_FRAME_INTERVAL_NS) {
                    lastFrameNs = frameTimeNanos
                    invalidate()
                }
                Choreographer.getInstance().postFrameCallback(this)
            }
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        lastFrameNs = 0L
        Choreographer.getInstance().postFrameCallback(frameCallback)
    }

    override fun onDetachedFromWindow() {
        Choreographer.getInstance().removeFrameCallback(frameCallback)
        super.onDetachedFromWindow()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        meshBuilt = false
        rebuildAppearancePaints()
    }

    /** 视图尺寸变化时重建外观 Paint（不逐帧分配）。 */
    private fun rebuildAppearancePaints() {
        val w = width.coerceAtLeast(1).toFloat()
        val h = height.coerceAtLeast(1).toFloat()

        scanlinePaint = Paint().apply {
            shader = BitmapShader(
                NdsFilterPatterns.createScanlinePattern(),
                Shader.TileMode.REPEAT, Shader.TileMode.REPEAT
            )
            isAntiAlias = false
        }

        vignettePaint = Paint().apply {
            shader = RadialGradient(
                w * 0.5f, h * 0.5f,
                max(w, h) * 0.72f,
                intArrayOf(0x00000000, 0x00000000, 0x9E000000.toInt()),
                floatArrayOf(0f, 0.55f, 1f),
                Shader.TileMode.CLAMP
            )
        }

        glossPaint = Paint().apply {
            shader = LinearGradient(
                0f, h * 0.04f, 0f, h * 0.38f,
                intArrayOf(0x00000000, 0x14FFFFFF, 0x00000000),
                floatArrayOf(0f, 0.5f, 1f),
                Shader.TileMode.CLAMP
            )
        }

        borderPaint.strokeWidth = max(1.5f, w * 0.006f)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val eng = engine ?: return

        // 机身底色（画面边界外 / 无帧时的「机壳」区域）。
        // 注：菜单遮挡（uiBlocked）时继续正常拉帧绘制 —— 与
        // NdsDualScreenView 行为一致，关闭菜单后画面无缝衔接。
        canvas.drawColor(0xFF000000.toInt())
        if (!eng.isLoaded) return

        val vw = eng.videoWidth().coerceAtLeast(1)
        val vh = eng.videoHeight().coerceAtLeast(1)
        val bmp = ensureFrameBitmap(vw, vh) ?: return
        if (!pullFrame(eng, vw, vh, bmp)) return
        if (width <= 0 || height <= 0) return
        if (!meshBuilt) buildMesh()

        val w = width.toFloat()
        val h = height.toFloat()
        val r = min(w, h) * CORNER_RADIUS_RATIO

        // 四角圆弧裁切：圆角矩形外的弧面四角不绘制（玻璃圆角）
        val save = canvas.save()
        workRect.set(0f, 0f, w, h)
        clipPath.rewind()
        clipPath.addRoundRect(workRect, r, r, Path.Direction.CW)
        canvas.clipPath(clipPath)

        // 1) 弧面画面（顶点已按 nsCurve 逆映射排布 → 弧形边界 + 桶形凸起）
        canvas.drawBitmapMesh(bmp, MESH_N, MESH_N, meshVerts, 0, null, 0, meshPaint)

        // 2) 扫描线（与 FilterOverlay tv 分支同图案同密度）
        scanlinePaint?.let { canvas.drawRect(0f, 0f, w, h, it) }

        // 3) 径向暗角
        vignettePaint?.let { canvas.drawRect(0f, 0f, w, h, it) }
        canvas.restoreToCount(save)

        // 4) 暗边框（圆角描边 —— 电视机边框内侧阴影）
        canvas.drawRoundRect(0f, 0f, w, h, r, r, borderPaint)

        // 5) 上部玻璃高光弧带（立体感）
        glossPaint?.let { canvas.drawRect(0f, 0f, w, h, it) }
    }

    /** 确保 frameBitmap 与引擎当前帧尺寸一致（尺寸变化时重建）。 */
    private fun ensureFrameBitmap(vw: Int, vh: Int): Bitmap? {
        var bmp = frameBitmap
        if (bmp == null || frameW != vw || frameH != vh || bmp.isRecycled) {
            if (bmp != null && !bmp.isRecycled) {
                try { bmp.recycle() } catch (_: Throwable) {}
            }
            bmp = try {
                Bitmap.createBitmap(vw, vh, Bitmap.Config.ARGB_8888)
            } catch (_: Throwable) {
                return null
            }
            frameBitmap = bmp
            frameW = vw
            frameH = vh
        }
        return bmp
    }

    /** 从引擎拉取当前帧到位图（ARGB）。返回 false 表示本帧不可用。 */
    private fun pullFrame(eng: EmulatorEngine, vw: Int, vh: Int, bmp: Bitmap): Boolean {
        val fb = eng.frameBuffer
        if (fb.size < vw * vh) return false
        return try {
            bmp.setPixels(fb, 0, vw, 0, 0, vw, vh)
            true
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * 构建弧面网格：对每个纹理网格点 (u,v)，求 GLSL nsCurve 的逆映射得到
     * 该纹素应出现的屏幕位置，作为顶点坐标。
     *
     * 正向：s.x = c.x * (1 + (c.y/5.4)²)；s.y = c.y * (1 + (c.x/3.6)²)
     * 逆映射定点迭代（Gauss-Seidel，6 次收敛到 <0.1%）：
     *   c.x ← s.x / (1 + (c.y/5.4)²)
     *   c.y ← s.y / (1 + (c.x/3.6)²)
     *
     * 结果：纹理边中点顶点恰落在视图边缘，四角顶点向内收 —— 弧面凸起。
     */
    private fun buildMesh() {
        val w = width.toFloat()
        val h = height.toFloat()
        var idx = 0
        for (j in 0..MESH_N) {
            val ty = (j.toFloat() / MESH_N) * 2f - 1f      // 纹理点 c.y
            for (i in 0..MESH_N) {
                val tx = (i.toFloat() / MESH_N) * 2f - 1f  // 纹理点 c.x
                var cx = tx
                var cy = ty
                repeat(CURVE_ITER) {
                    cx = tx / (1f + (cy / CURVE_X) * (cy / CURVE_X))
                    cy = ty / (1f + (cx / CURVE_Y) * (cx / CURVE_Y))
                }
                meshVerts[idx++] = (cx * 0.5f + 0.5f) * w
                meshVerts[idx++] = (cy * 0.5f + 0.5f) * h
            }
        }
        meshBuilt = true
    }
}
