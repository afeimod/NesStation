package com.nesstation.app.ui.emulator

import android.content.Context
import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.util.Log
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import com.nesstation.app.core.engine.EmulatorEngine
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * ★★★ MMJ（Citra MMJ 3DS）GLSL 滤镜显示视图 V13 ★★★
 *
 * ## V12 → V13 根治（"滤镜编译都成功了，但 xBR/HQx/SEDI 依然看不出效果"）★★★
 *
 * V12 修好了着色器编译（textureSize lod + highp），但【滤镜的输入源】
 * 仍是错的：本视图把窗口尺寸的 SurfaceTexture 交给核心，核心把最终
 * 合成帧按【窗口分辨率】渲染进来 —— 像素网格类滤镜（xBR/4xBR/HQ2X/
 * HQ4X/SEDI 全族）的算法假设 color_texture 是【原生低分辨率 framebuffer】
 * （3DS 上屏 400×240）：
 *   - HQ2X/4xBR 的 `fract(dc * GetResolution())` 在窗口分辨率输入下，
 *     每个纹素 ≈ 1 屏幕像素 → 边缘检测网格退化 → 输出 ≈ 直通；
 *   - 源图已被核心双线性放大过（像素已被混合），边缘导向插值无边缘
 *     可重建 → "编译成功但毫无视觉效果"。
 *
 * V13 方案：【原生分辨率中转 + 前端放大】（对齐核心内链的工作方式）：
 *   1. 滤镜激活时把 SurfaceTexture 默认缓冲设为【原生双屏布局尺寸】
 *      （竖屏堆叠 400×480 / 横屏并排 720×240），核心经自定义布局矩形
 *      1:1 渲染原生像素网格（见 CitraMmjEngine.setMmjNativeFilterMode）；
 *   2. 本视图的滤镜着色器以原生纹理为源，等比放大（letterbox 视口）
 *      绘满窗口 —— 几何与核心自布局完全一致，但 xBR/HQx 现在工作在
 *      真正的原生像素网格上 → 滤镜效果立现；
 *   3. 解析 .glsl 的 `//! mag_filter = nearest|linear` 指令并应用到
 *      OES 纹理采样（Citra 原生同款语义）：xBR/HQx/Dot 声明 nearest
 *      —— 最近邻采样保住原生像素的锐利边缘（线性采样会先糊开像素，
 *      是滤镜无效的帮凶之一）；min_filter 恒 LINEAR（前端只放大）；
 *   4. 直通路径（无滤镜/编译失败回落）：沿用原生缓冲 + 同款 letterbox
 *      视口，GPU 线性放大 ≈ 核心直绘观感，无回归；
 *   5. 用户自定义布局/单屏/大屏等不支持原生中转的情形
 *      （mmjNativeFilterLayout 返回 null）自动保持 V12 窗口缓冲
 *      行为 —— 滤镜效果退化但不破坏布局。
 *
 * ## V13 → V14 根治（"滤镜生效了，但画面被放大"）★
 *
 * V13 无视窗口宽高比硬编码"竖屏堆叠 400×480 / 横屏并排 720×240"+letterbox
 * 居中，与核心默认布局（framebuffer_layout.cpp DefaultFrameLayout）不符：
 * 核心默认布局与旋转家族无关，只取决于窗口宽高比 —— 宽窗（H/W<1.2）=
 * 两行网格（每屏只有半窗高，内容铺满整窗）；长窗（H/W≥1.2）= 同标尺
 * 堆叠且【顶部对齐】。横屏手机（H/W≈0.45）核心原画面是两行小屏，V13
 * 强设并排 → 每屏放大 ~1.48×（"画面被无情放大"实证）。
 *
 * V14 方案：引擎按【视图真实宽高】复刻 DefaultFrameLayout/SideFrameLayout
 * 的精确几何（上屏锚定 400×240 原生像素），缓冲 = 同宽高比原生网格；
 * 本视图按 fit 语义绘制：宽窗=铺满整窗（与核心满窗绘制一致）、长窗=
 * 顶对齐、并排=居中。滤镜开启前后画面大小/位置逐像素一致。
 *
 * ------------------------------------------------------------------
 *
 * ## V11.3 → V12 根治（"全部滤镜（全局 xbr/hqx 映射 + 自带 14 个 .glsl）
 *   依然无任何效果"的编译层根因）★
 *
 * V11.3 的兼容头里写的是【单参数】textureSize：
 * ```glsl
 *   ivec2 SampleSize() { return textureSize(color_texture); }
 * ```
 * GLSL ES 3.00 规范（第 8.8 节）中 textureSize 的【所有】重载都要求
 * 第二个 lod 参数，OES_EGL_image_external_essl3 扩展新增的外部纹理
 * 重载同样如此（ivec2 textureSize(samplerExternalOES, int lod)）。
 * 单参数调用在【任何 GLES 3.0 驱动上都是编译错误】—— SampleSize()
 * 位于所有滤镜共用的兼容头里 → 14 个滤镜 program 全部编译失败 →
 * rebuildFilterProgram 静默回落直通 → 全局 xbr/hqx 映射（xBR/4xBR/
 * HQ2X/HQ4X）与自带 SEDI/FXAA/bloom/spline36 等无一可见。
 * （v3 时代的 MmjGlView 写的是正确的 textureSize(color_texture, 0)，
 *   V11 重写兼容头时把 lod 参数丢了 —— 这就是 V11 滤镜全无效的根因。）
 *
 * 同时修正：兼容头 precision mediump → highp。xBR/HQx/SEDI/FXAA 全族
 * 依赖纹素级坐标数学（fract(dc * resolution)、1/resolution 偏移）。
 * mediump 在主流移动 GPU 上是 fp16（精确整数上限 2048，尾数 10bit）：
 * 分辨率超过 2048 或坐标小数精度不足时 fract()/偏移全错，滤镜要么
 * 产生严重伪影要么输出退化为直通观感。ES 3.0 片元着色器【强制】支持
 * highp（GLSL ES 3.00 规范要求），直接声明 highp 零风险。
 *
 * 另加固：glLoop 启动竞态 —— surfaceCreated 与 surfaceChanged 之间
 * GL 线程可能先跑，ensureCoreSurface 读到 0×0 时 coerceAtLeast(1)
 * 会把核心锁进 1×1 SurfaceTexture（首帧后永不重设 → 画面模糊/黑屏）。
 * 现在等到真实尺寸到位才把 surface 交给引擎。
 *
 * ## V11.2 → V11.3 重构（彻底修复画面颠倒 + 滤镜无效）
 *
 * V11.2 用【两次 pass】：
 *   pass 1: OES → 2D FBO 拷贝（progCopy）
 *   pass 2: 2D FBO → screen + 滤镜（progFilter）
 *
 * 问题：两次 pass 共用同一组 quad UV，但源纹理的 V 约定不同：
 *   - OES 纹理：V=0 = top（屏幕坐标系，已预翻转）
 *   - 标准 2D 纹理（FBO 附件）：V=0 = bottom（标准 GL 约定）
 *
 * pass 1 后 FBO 内容方向正确（top of OES → top of FBO）。但 pass 2 用同款
 * UV 布局采样 FBO 时，top vertex 采样 V=0 = bottom of FBO → 把 FBO 底部
 * 画到屏幕顶部 → 画面上下颠倒。
 *
 * V11.3 重构：参考 Ps2CurvedView 成熟模式，**单次 pass 直接把 OES 纹理用
 * 滤镜着色器渲染到屏幕**，去掉 FBO 中转。fxHeader 改用 `samplerExternalOES`
 * + `#extension GL_OES_EGL_image_external_essl3`，让 MMJ 原版 .glsl 在我们
 * 自己的 GLES 3.0 上下文里直接采样 OES 纹理。assets/mmj/shaders/ 下 14 个
 * .glsl 经扫描确认全部只用 `SampleLocation`/`Sample`，不依赖 `texelFetch`/
 * `SampleFetch`（OES 纹理不支持 texelFetch），可安全切换。
 *
 * ## 单次 pass 数据流
 *
 *   Citra MMJ ──渲染──▶ [SurfaceTexture]（OES 纹理源）
 *                          │ updateTexImage()
 *                          ▼
 *   本视图 EGL/GL 线程 ──OES 纹理 + MMJ 兼容头 + 用户选定 .glsl──▶ SurfaceView 上屏
 *
 * 1. GL 线程创建 OES 外部纹理 + SurfaceTexture，包装成 Surface 交给引擎
 *    （engine.setSurface + onSurfaceChanged）—— 引擎看到的仍是真实
 *    ANativeWindow，MMJ 的 boot 序列 + Run 契约不变；
 * 2. 每收到一帧（onFrameAvailable 信号）就 updateTexImage 取为 OES 纹理，
 *    用拼好的着色器源码（兼容头 + 用户滤镜 .glsl）绘制全屏四边形到本
 *    SurfaceView；
 * 3. 滤镜源码读取顺序：<filesDir>/azahar/shaders/<name>.glsl → APK assets
 *    /mmj/shaders/<name>.glsl。编译失败回落直通 OES 渲染（结构上不黑屏）。
 *
 * ## 滤镜激活条件
 *   - 引擎为 [com.nesstation.app.core.engine.CitraMmjEngine]；
 *   - 用户在 UI 选定了一个非空滤镜（pp_shader_name）。
 */
class MmjFilterView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : SurfaceView(context, attrs, defStyleAttr), SurfaceHolder.Callback {

    @Volatile
    var engine: EmulatorEngine? = null

    @Volatile
    var shaderName: String? = null

    @Volatile
    var uiBlocked: Boolean = false

    @Volatile
    var onGlFailed: (() -> Unit)? = null

    private companion object {
        const val TAG = "MmjFilterView"

        // ★ V14：前端视口拟合语义（引擎 mmjNativeFilterLayout 返回 fit）——
        //   复刻核心默认布局的绘制几何，滤镜开启前后画面大小/位置逐像素一致：
        //   CENTERED  = letterbox 居中（并排布局 / 自定义布局包围盒，V13 行为）
        //   STRETCH   = 铺满整窗（宽窗两行网格：核心本就满窗绘制）
        //   TOP_ALIGN = 顶部对齐（长窗堆叠：核心默认布局内容顶对齐下方留黑）
        const val FIT_CENTERED = 0
        const val FIT_STRETCH = 1
        const val FIT_TOP_ALIGNED = 2
    }

    private val glThreadRunning = AtomicBoolean(false)
    private var glThread: Thread? = null
    @Volatile private var glLoopStopped = false
    @Volatile private var surfaceW = 0
    @Volatile private var surfaceH = 0

    private var eglDisplay: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
    private var eglSurface: EGLSurface = EGL14.EGL_NO_SURFACE

    // 直通 program（无滤镜时直接把 OES 纹理绘到屏幕）
    private var progPassthrough = 0
    private var oesTex = 0
    private var surfaceTexture: SurfaceTexture? = null
    private var coreSurface: Surface? = null

    // 滤镜 program（用户选定 .glsl + MMJ 兼容头）
    private var progFilter = 0
    @Volatile private var lastBoundShaderName: String? = "<sentinel>"

    // ---- ★ V13 原生分辨率滤镜模式状态 ----
    /** true = 核心正渲染到原生布局尺寸缓冲（需 letterbox 视口 + 布局恢复）。 */
    @Volatile private var nativeFilterMode = false

    /** 当前核心缓冲尺寸（原生模式下 = 原生布局尺寸；窗口模式 = 窗口尺寸）。 */
    private var bufW = 0
    private var bufH = 0

    /** ★ V14：当前视口拟合语义（引擎按核心默认布局几何给出）。 */
    @Volatile private var nativeFit = FIT_CENTERED

    /** GetTime() 帧计数（旧恒 0，个别动画类滤镜依赖）。 */
    private var frameCount = 0L

    @Volatile private var engineSurfaceGiven = false

    private val frameSignal = ArrayBlockingQueue<Boolean>(1)

    /** 全屏四边形：pos(2) + uv(2)，三角带 4 顶点。 */
    private val quadBuf: FloatBuffer = ByteBuffer
        .allocateDirect(4 * 4 * 4)
        .order(ByteOrder.nativeOrder())
        .asFloatBuffer()

    // ---- 着色器源（★ V11.3：单次 pass，OES 直采） ----

    /** 顶点着色器（直通 与 滤镜共用，GLES 3.0 语法）。 */
    private val vsSrc = """
        #version 300 es
        in vec4 aPos;
        out vec2 vUV;
        void main() {
            vUV = aPos.zw;
            gl_Position = vec4(aPos.xy, 0.0, 1.0);
        }
    """.trimIndent()

    /** 直通片元着色器（OES → 屏幕，无滤镜）。 */
    private val fsPassthroughSrc = """
        #version 300 es
        #extension GL_OES_EGL_image_external_essl3 : require
        precision mediump float;
        uniform samplerExternalOES uTex;
        in vec2 vUV;
        out vec4 fragColor;
        void main() {
            fragColor = texture(uTex, vUV);
        }
    """.trimIndent()

    /**
     * MMJ 后处理滤镜的 GL 兼容层（V12：GLES 3.0 + samplerExternalOES + highp）：
     *   - 把 MMJ 自有类型（float2/3/4、int2/3/4、uint2/3/4）映射到 GLSL ES 3.0；
     *   - 提供 GetCoordinates/GetResolution/GetInvResolution/GetOnScreenSize/
     *     GetTime/Sample/SampleLocation/SetOutput 等 MMJ 滤镜期望的全部辅助函数；
     *   - 采样源 = 本视图的 OES 外部纹理（来自 SurfaceTexture）。
     *
     * ★★★ V12 修复（滤镜全无效的最终根因）★★★
     *   1. textureSize(color_texture) → textureSize(color_texture, 0)：
     *      GLSL ES 3.00 所有 textureSize 重载（含 OES 扩展新增的）都要求
     *      lod 参数，单参调用在任何 GLES 3.0 驱动上都是编译错误 —— 旧头
     *      导致全部滤镜 program 编译失败 → 静默回落直通 → 滤镜全无效。
     *   2. precision mediump → highp：xBR/HQx/SEDI/FXAA 全族依赖纹素级
     *      坐标数学（fract(dc*resolution)、1/resolution 偏移），mediump
     *      （fp16，精确整数上限 2048）在大屏上必然出错；ES 3.0 片元着色
     *      器强制支持 highp，直接声明零风险。
     *
     * ★ 重要：assets/mmj/shaders/ 下 14 个 .glsl 经扫描确认全部只用
     *   SampleLocation/Sample（不依赖 SampleFetch/texelFetch），可安全
     *   采样 OES 纹理。
     */
    private val fxHeader = """
        #version 300 es
        #extension GL_OES_EGL_image_external_essl3 : require
        #define float2 vec2
        #define float3 vec3
        #define float4 vec4
        #define uint2 uvec2
        #define uint3 uvec3
        #define uint4 uvec4
        #define int2 ivec2
        #define int3 ivec3
        #define int4 ivec4

        precision highp float;
        precision highp int;

        in vec2 vUV;
        out vec4 output_color;
        uniform samplerExternalOES color_texture;
        uniform vec2 resolution;
        uniform int frame_count;

        ivec2 SampleSize() { return textureSize(color_texture, 0); }
        vec2 GetResolution() { return vec2(SampleSize()); }
        vec2 GetInvResolution() { return 1.0 / GetResolution(); }
        vec2 GetOnScreenSize() { return resolution; }
        vec2 GetCoordinates() { return vUV; }
        uint GetTime() { return uint(frame_count); }
        void SetOutput(vec4 color) { output_color = color; }
        vec4 Sample() { return texture(color_texture, vUV); }
        vec4 SampleLocation(vec2 location) { return texture(color_texture, location); }
    """.trimIndent()

    /** 滤镜片元着色器模板（兼容头 + 用户的 .glsl 源）。 */
    private fun buildFilterFs(userSrc: String): String {
        val cleaned = userSrc.replace(Regex("(?m)^\\s*#version.*$"), "")
        val cleaned2 = cleaned.replace(Regex("(?m)^\\s*precision\\s+(highp|mediump|lowp)\\s+(float|int|sampler2D).*;$"), "")
        return """
            $fxHeader

            // ----- 用户滤镜源码 -----
            $cleaned2
        """.trimIndent()
    }

    init {
        holder.setFormat(android.graphics.PixelFormat.RGBX_8888)
        holder.addCallback(this)
        setZOrderMediaOverlay(false)
        // ★ V11.3：与 Ps2CurvedView 完全同款 UV 布局
        //   OES 纹理约定 V=0=top（屏幕坐标系，已预翻转），
        //   所以 top vertex (Y=+1) 采样 v=0 = top of source（正确方向）
        quadBuf.put(floatArrayOf(
            -1f, -1f, 0f, 1f,   // bottom-left vertex, uv (0, 1) = bottom of source
             1f, -1f, 1f, 1f,   // bottom-right
            -1f,  1f, 0f, 0f,   // top-left,    uv (0, 0) = top of source
             1f,  1f, 1f, 0f    // top-right
        )).position(0)
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        startGlThread()
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        surfaceW = width
        surfaceH = height
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        stopGlThread()
    }

    private fun startGlThread() {
        if (glThreadRunning.getAndSet(true)) return
        glLoopStopped = false
        val view = this
        glThread = Thread({
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_DISPLAY)
            try {
                if (!eglInit(holder.surface)) {
                    Log.e(TAG, "EGL init failed — fallback to flat SurfaceView path")
                    view.post { onGlFailed?.invoke() }
                    return@Thread
                }
                if (!glInitResources()) {
                    Log.e(TAG, "GL resources init failed — fallback to flat SurfaceView path")
                    releaseCoreSurface()
                    glReleaseEgl()
                    view.post { onGlFailed?.invoke() }
                    return@Thread
                }
                glLoop()
            } catch (t: Throwable) {
                Log.e(TAG, "GL thread died", t)
                view.post { onGlFailed?.invoke() }
            } finally {
                releaseCoreSurface()
                glReleaseResources()
                glReleaseEgl()
                glThreadRunning.set(false)
            }
        }, "mmj-filter-gl").also { it.isDaemon = true; it.start() }
    }

    private fun stopGlThread() {
        glLoopStopped = true
        frameSignal.offer(true)
        val t = glThread
        if (t != null) {
            try { t.join(1500) } catch (_: InterruptedException) {}
        }
        glThread = null
    }

    private fun eglInit(surface: Surface): Boolean {
        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        if (eglDisplay === EGL14.EGL_NO_DISPLAY) return false
        val version = IntArray(2)
        if (!EGL14.eglInitialize(eglDisplay, version, 0, version, 1)) return false

        // ★ V11.2.2：EGL_OPENGL_ES3_BIT 不在 Android EGL14 类中暴露
        //   用 EGL_OPENGL_ES2_BIT 选 config + CLIENT_VERSION=3 创建 GLES 3.0 上下文
        //   （所有支持 GLES 3.0 的 config 都同时支持 GLES 2.0）
        if (!eglChooseAndCreate(surface, EGL14.EGL_OPENGL_ES2_BIT, 3)) {
            Log.w(TAG, "GLES 3.0 context unavailable, fallback to GLES 2.0")
            if (!eglChooseAndCreate(surface, EGL14.EGL_OPENGL_ES2_BIT, 2)) {
                return false
            }
        }
        return true
    }

    private fun eglChooseAndCreate(surface: Surface, renderableBit: Int, clientVersion: Int): Boolean {
        val attribs = intArrayOf(
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, renderableBit,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT,
            EGL14.EGL_NONE
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val num = IntArray(1)
        if (!EGL14.eglChooseConfig(eglDisplay, attribs, 0, configs, 0, 1, num, 0) || num[0] < 1) {
            return false
        }
        val ctxAttribs = intArrayOf(
            EGL14.EGL_CONTEXT_CLIENT_VERSION, clientVersion,
            EGL14.EGL_NONE
        )
        eglContext = EGL14.eglCreateContext(eglDisplay, configs[0], EGL14.EGL_NO_CONTEXT, ctxAttribs, 0)
        if (eglContext === EGL14.EGL_NO_CONTEXT) return false

        val surfAttribs = intArrayOf(EGL14.EGL_NONE)
        eglSurface = EGL14.eglCreateWindowSurface(eglDisplay, configs[0], surface, surfAttribs, 0)
        if (eglSurface === EGL14.EGL_NO_SURFACE) {
            EGL14.eglDestroyContext(eglDisplay, eglContext)
            eglContext = EGL14.EGL_NO_CONTEXT
            return false
        }
        if (!EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) {
            EGL14.eglDestroySurface(eglDisplay, eglSurface)
            eglSurface = EGL14.EGL_NO_SURFACE
            EGL14.eglDestroyContext(eglDisplay, eglContext)
            eglContext = EGL14.EGL_NO_CONTEXT
            return false
        }
        return true
    }

    private fun glReleaseEgl() {
        try {
            if (eglDisplay !== EGL14.EGL_NO_DISPLAY) {
                EGL14.eglMakeCurrent(
                    eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT
                )
                if (eglSurface !== EGL14.EGL_NO_SURFACE) {
                    EGL14.eglDestroySurface(eglDisplay, eglSurface)
                }
                if (eglContext !== EGL14.EGL_NO_CONTEXT) {
                    EGL14.eglDestroyContext(eglDisplay, eglContext)
                }
                EGL14.eglTerminate(eglDisplay)
            }
        } catch (_: Throwable) {
        } finally {
            eglSurface = EGL14.EGL_NO_SURFACE
            eglContext = EGL14.EGL_NO_CONTEXT
            eglDisplay = EGL14.EGL_NO_DISPLAY
        }
    }

    private fun glInitResources(): Boolean {
        // ---- 1) 直通 program（OES → screen，无滤镜）----
        val vs = compileShader(GLES20.GL_VERTEX_SHADER, vsSrc) ?: return false
        val fsPass = compileShader(GLES20.GL_FRAGMENT_SHADER, fsPassthroughSrc) ?: run {
            GLES20.glDeleteShader(vs); return false
        }
        progPassthrough = GLES20.glCreateProgram()
        GLES20.glAttachShader(progPassthrough, vs)
        GLES20.glAttachShader(progPassthrough, fsPass)
        GLES20.glBindAttribLocation(progPassthrough, 0, "aPos")
        GLES20.glLinkProgram(progPassthrough)
        GLES20.glDeleteShader(vs)
        GLES20.glDeleteShader(fsPass)
        val linked = IntArray(1)
        GLES20.glGetProgramiv(progPassthrough, GLES20.GL_LINK_STATUS, linked, 0)
        if (linked[0] == 0) {
            Log.e(TAG, "passthrough program link failed: " + GLES20.glGetProgramInfoLog(progPassthrough))
            GLES20.glDeleteProgram(progPassthrough); progPassthrough = 0
            return false
        }

        // ---- 2) OES 外部纹理 + SurfaceTexture（核心的渲染目标） ----
        val texs = IntArray(1)
        GLES20.glGenTextures(1, texs, 0)
        oesTex = texs[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTex)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)

        val st = SurfaceTexture(oesTex)
        st.setOnFrameAvailableListener({
            frameSignal.offer(true)
        }, Handler(Looper.getMainLooper()))
        surfaceTexture = st

        try {
            val glVer = GLES20.glGetString(GLES20.GL_VERSION)
            val glslVer = GLES20.glGetString(GLES20.GL_SHADING_LANGUAGE_VERSION)
            Log.i(TAG, "EGL/GLES ready: GL_VERSION=$glVer, GLSL_VERSION=$glslVer")
        } catch (_: Throwable) {}

        return true
    }

    private fun glReleaseResources() {
        try {
            surfaceTexture?.release()
        } catch (_: Throwable) {}
        surfaceTexture = null
        if (oesTex != 0) {
            try { GLES20.glDeleteTextures(1, intArrayOf(oesTex), 0) } catch (_: Throwable) {}
            oesTex = 0
        }
        if (progPassthrough != 0) {
            try { GLES20.glDeleteProgram(progPassthrough) } catch (_: Throwable) {}
            progPassthrough = 0
        }
        if (progFilter != 0) {
            try { GLES20.glDeleteProgram(progFilter) } catch (_: Throwable) {}
            progFilter = 0
        }
        lastBoundShaderName = "<sentinel>"
    }

    private fun releaseCoreSurface() {
        if (!engineSurfaceGiven) return
        engineSurfaceGiven = false
        try {
            val eng = engine
            // ★ V13：退出原生滤镜模式时恢复用户布局（ini 矩形键/开关原值）
            if (nativeFilterMode && eng is com.nesstation.app.core.engine.CitraMmjEngine) {
                try { eng.setMmjNativeFilterMode(false) } catch (_: Throwable) {}
            }
            nativeFilterMode = false
            eng?.onSurfaceDestroyed()
            eng?.setSurface(null)
        } catch (_: Throwable) {}
        try { coreSurface?.release() } catch (_: Throwable) {}
        coreSurface = null
    }

    /**
     * ★ V13：当前 display rotation 是否属竖屏家族（决定原生布局家族：
     * 竖屏 = 堆叠 400×480；横屏 = 并排 720×240）。与引擎 onSurfaceChanged
     * 下发给核心的 rotation 同源（同一 display），保证布局家族一致。
     */
    private fun isPortraitRotation(): Boolean {
        val rot = try {
            val wm = context.getSystemService(Context.WINDOW_SERVICE)
                as? android.view.WindowManager
            @Suppress("DEPRECATION") val r = wm?.defaultDisplay?.rotation
            r ?: Surface.ROTATION_0
        } catch (_: Throwable) { Surface.ROTATION_0 }
        return rot == Surface.ROTATION_0 || rot == Surface.ROTATION_180
    }

    private fun ensureCoreSurface() {
        if (engineSurfaceGiven) return
        val st = surfaceTexture ?: return
        val eng = engine
        val w = surfaceW.coerceAtLeast(1)
        val h = surfaceH.coerceAtLeast(1)

        // ★★★ V13/V14：滤镜激活且布局设置支持时，把核心渲染缓冲切到
        //   【原生双屏布局尺寸】，让像素网格类滤镜工作在原生像素网格上。
        //   V14：布局由引擎按视图真实宽高复刻核心默认布局几何（宽窗两行
        //   网格 / 长窗堆叠 / 并排），并给出视口拟合语义。失败/不支持 →
        //   保持窗口缓冲（V12 行为，滤镜效果退化但无回归）。
        var bw = w
        var bh = h
        nativeFit = FIT_CENTERED
        val curShader = shaderName?.trim()?.takeIf { it.isNotEmpty() && it != "(off)" }
        if (curShader != null && eng is com.nesstation.app.core.engine.CitraMmjEngine) {
            val native = try {
                eng.mmjNativeFilterLayout(isPortraitRotation(), w, h)
            } catch (_: Throwable) { null }
            if (native != null) {
                bw = native.bufW
                bh = native.bufH
                nativeFit = native.fit
                try { eng.setMmjNativeFilterMode(true) } catch (_: Throwable) {
                    bw = w; bh = h; nativeFit = FIT_CENTERED
                }
                nativeFilterMode = bw != w || bh != h
                if (nativeFilterMode) {
                    Log.i(TAG, "V14 native filter buffer: ${bw}x${bh} fit=${nativeFit} (window ${w}x${h})")
                }
            }
        }
        bufW = bw
        bufH = bh
        st.setDefaultBufferSize(bw, bh)
        val surf = Surface(st)
        coreSurface = surf
        if (eng != null) {
            eng.setSurface(surf)
            eng.onSurfaceChanged(surf, bw, bh)
            engineSurfaceGiven = true
            Log.i(TAG, "MMJ core surface attached: ${bw}x${bh}")
        }
    }

    private fun glLoop() {
        val st = surfaceTexture ?: return

        // ★★★ V12 启动竞态加固 ★★★
        //   surfaceCreated 立即启动 GL 线程，surfaceChanged（真实宽高）稍后
        //   才到。旧实现在此直接 ensureCoreSurface()：读到 0×0 时
        //   coerceAtLeast(1) 会以 1×1 尺寸 setDefaultBufferSize + 把核心
        //   锁进 1×1 SurfaceTexture（engineSurfaceGiven 一次性门永不重设）
        //   → 核心只渲染 1×1 → 画面模糊/黑块。现在等真实尺寸到位（上限
        //   3s，与引擎启动等待窗口对齐）再挂核心 surface。
        var waitedForSize = 0
        while ((surfaceW <= 0 || surfaceH <= 0) && !glLoopStopped && waitedForSize < 3000) {
            try { Thread.sleep(16) } catch (_: InterruptedException) { break }
            waitedForSize += 16
        }

        ensureCoreSurface()

        while (!glLoopStopped) {
            try { frameSignal.poll(100, TimeUnit.MILLISECONDS) } catch (_: InterruptedException) { break }

            val w = surfaceW
            val h = surfaceH
            if (w <= 0 || h <= 0) continue

            // ★ V14：布局变化（旋转家族 / 窗口比例 / 交换屏幕 / 切布局选项）
            //   → 原生缓冲尺寸与视口拟合随之切换。引擎查询侧已把新矩形落盘
            //   + loadConfig/WindowChanged 热生效；这里只重设缓冲尺寸 + 通知
            //   引擎重算布局。查询返回 null（如切到单屏/大屏）→ 退回窗口
            //   缓冲 + 全窗视口（V12 行为，布局绝不破坏）。
            if (nativeFilterMode) {
                val native = try {
                    (engine as? com.nesstation.app.core.engine.CitraMmjEngine)
                        ?.mmjNativeFilterLayout(isPortraitRotation(), w, h)
                } catch (_: Throwable) { null }
                if (native == null) {
                    nativeFilterMode = false
                    nativeFit = FIT_CENTERED
                    try {
                        st.setDefaultBufferSize(w, h)
                        engine?.onSurfaceChanged(coreSurface, w, h)
                        Log.i(TAG, "V14 native mode exited (layout unsupported) -> window buffer ${w}x${h}")
                    } catch (_: Throwable) {}
                } else if (native.bufW != bufW || native.bufH != bufH || native.fit != nativeFit) {
                    bufW = native.bufW
                    bufH = native.bufH
                    nativeFit = native.fit
                    try {
                        st.setDefaultBufferSize(bufW, bufH)
                        engine?.onSurfaceChanged(coreSurface, bufW, bufH)
                        Log.i(TAG, "V14 native buffer reconfigured: ${bufW}x${bufH} fit=${nativeFit}")
                    } catch (_: Throwable) {}
                }
            }

            try { st.updateTexImage() } catch (_: Throwable) { continue }

            // 滤镜 program 重建（如 shader 名变化）
            val curShader = shaderName?.trim()?.takeIf { it.isNotEmpty() && it != "(off)" }
            if (curShader != lastBoundShaderName) {
                rebuildFilterProgram(curShader)
                lastBoundShaderName = curShader
            }

            // 单次 pass：直通 or 滤镜，都直接采样 OES 纹理渲染到屏幕
            GLES20.glViewport(0, 0, w, h)
            GLES20.glClearColor(0f, 0f, 0f, 1f)
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

            val prog = if (progFilter != 0 && curShader != null) progFilter else progPassthrough
            if (prog == 0) continue

            // ★ V14 视口：按引擎给出的拟合语义复刻核心默认布局几何 ——
            //   STRETCH   = 铺满整窗（宽窗两行网格，缓冲≈窗口宽高比，与核心
            //               满窗绘制逐像素一致）；
            //   TOP_ALIGN = 等比放大、顶部对齐（长窗堆叠：核心内容顶对齐、
            //               下方留黑，GL 的 y 原点在底部 → y 偏移 = h-vh）；
            //   CENTERED  = 等比 letterbox 居中（并排/自定义包围盒，V13 行为）；
            //   窗口模式（非原生）= 全屏。
            var vw = w
            var vh = h
            if (nativeFilterMode && bufW > 0 && bufH > 0) {
                when (nativeFit) {
                    FIT_STRETCH -> {
                        vw = w; vh = h
                    }
                    FIT_TOP_ALIGNED -> {
                        val scale = minOf(w.toFloat() / bufW, h.toFloat() / bufH)
                        vw = (bufW * scale).toInt().coerceAtLeast(1)
                        vh = (bufH * scale).toInt().coerceAtLeast(1)
                        GLES20.glViewport((w - vw) / 2, h - vh, vw, vh)
                    }
                    else -> {
                        val scale = minOf(w.toFloat() / bufW, h.toFloat() / bufH)
                        vw = (bufW * scale).toInt().coerceAtLeast(1)
                        vh = (bufH * scale).toInt().coerceAtLeast(1)
                        GLES20.glViewport((w - vw) / 2, (h - vh) / 2, vw, vh)
                    }
                }
            }

            GLES20.glUseProgram(prog)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTex)
            // 统一绑定 OES 纹理到 texture unit 0
            val uTexName = if (prog == progFilter) "color_texture" else "uTex"
            val uTex = GLES20.glGetUniformLocation(prog, uTexName)
            if (uTex >= 0) GLES20.glUniform1i(uTex, 0)

            if (prog == progFilter) {
                val uRes = GLES20.glGetUniformLocation(prog, "resolution")
                // ★ V13：GetOnScreenSize() 语义 = 实际绘制内容区（letterbox 后）
                if (uRes >= 0) GLES20.glUniform2f(uRes, vw.toFloat(), vh.toFloat())
                val uFc = GLES20.glGetUniformLocation(prog, "frame_count")
                if (uFc >= 0) GLES20.glUniform1i(uFc, (frameCount and 0xffffffffL).toInt())
            }
            frameCount++

            val aPos = GLES20.glGetAttribLocation(prog, "aPos")
            quadBuf.position(0)
            GLES20.glEnableVertexAttribArray(aPos)
            GLES20.glVertexAttribPointer(aPos, 4, GLES20.GL_FLOAT, false, 0, quadBuf)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
            GLES20.glDisableVertexAttribArray(aPos)
            EGL14.eglSwapBuffers(eglDisplay, eglSurface)
        }
    }

    private fun rebuildFilterProgram(name: String?) {
        if (progFilter != 0) {
            GLES20.glDeleteProgram(progFilter)
            progFilter = 0
        }
        if (name.isNullOrBlank()) return
        val src = readShaderSource(name) ?: run {
            Log.w(TAG, "shader '$name' source not found, fallback passthrough")
            return
        }
        // ★★ V13：解析 //! mag_filter / min_filter 指令（Citra 原生同款语义）。
        //   xBR/4xBR/HQ2X/HQ4X/Dot 声明 nearest —— 像素网格类滤镜必须最近邻
        //   采样源纹理：线性采样会把原生像素先糊开，边缘检测全废（滤镜
        //   无效的帮凶之一）。min_filter 恒 LINEAR（前端只放大不缩小）。
        val magNearest = Regex("!\\s*mag_filter\\s*=\\s*nearest").containsMatchIn(src)
        val minNearest = Regex("!\\s*min_filter\\s*=\\s*nearest").containsMatchIn(src)
        val fs = buildFilterFs(src)
        val vs = compileShader(GLES20.GL_VERTEX_SHADER, vsSrc)
        if (vs == 0) return
        val fsObj = compileShader(GLES20.GL_FRAGMENT_SHADER, fs)
        if (fsObj == 0) {
            GLES20.glDeleteShader(vs)
            Log.w(TAG, "filter shader '$name' compile failed, fallback passthrough. FS source:\n$fs")
            return
        }
        progFilter = GLES20.glCreateProgram()
        GLES20.glAttachShader(progFilter, vs)
        GLES20.glAttachShader(progFilter, fsObj)
        GLES20.glBindAttribLocation(progFilter, 0, "aPos")
        GLES20.glLinkProgram(progFilter)
        GLES20.glDeleteShader(vs)
        GLES20.glDeleteShader(fsObj)
        val linked = IntArray(1)
        GLES20.glGetProgramiv(progFilter, GLES20.GL_LINK_STATUS, linked, 0)
        if (linked[0] == 0) {
            Log.e(TAG, "filter program '$name' link failed: " + GLES20.glGetProgramInfoLog(progFilter))
            GLES20.glDeleteProgram(progFilter)
            progFilter = 0
        } else {
            // ★ V13：按 .glsl 指令设置 OES 纹理采样模式（纹理对象状态，
            //   设一次持续有效；直通 program 不经此处 —— 切回直通时
            //   glReleaseResources/glInitResources 会重建纹理为默认 LINEAR）。
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTex)
            GLES20.glTexParameteri(
                GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER,
                if (magNearest) GLES20.GL_NEAREST else GLES20.GL_LINEAR)
            GLES20.glTexParameteri(
                GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER,
                if (minNearest) GLES20.GL_NEAREST else GLES20.GL_LINEAR)
            Log.i(TAG, "filter program '$name' compiled OK " +
                "(mag=${if (magNearest) "nearest" else "linear"})")
        }
    }

    private fun readShaderSource(name: String): String? {
        try {
            val ctx = com.nesstation.app.NesApp.get() ?: return null
            val f = File(File(ctx.filesDir, "azahar"), "shaders/$name.glsl")
            if (f.isFile) return f.readText()
        } catch (_: Throwable) {}
        return try {
            val ctx = com.nesstation.app.NesApp.get() ?: return null
            ctx.assets.open("mmj/shaders/$name.glsl").use { it.readBytes().toString(Charsets.UTF_8) }
        } catch (_: Throwable) { null }
    }

    private fun compileShader(type: Int, src: String): Int {
        val sh = GLES20.glCreateShader(type)
        GLES20.glShaderSource(sh, src)
        GLES20.glCompileShader(sh)
        val status = IntArray(1)
        GLES20.glGetShaderiv(sh, GLES20.GL_COMPILE_STATUS, status, 0)
        if (status[0] == 0) {
            Log.e(TAG, "shader compile failed: " + GLES20.glGetShaderInfoLog(sh)
                + "\n----- source -----\n$src")
            GLES20.glDeleteShader(sh)
            return 0
        }
        return sh
    }

    fun setPaused(paused: Boolean) {
        glLoopStopped = paused
        if (!paused) frameSignal.offer(true)
    }
}
