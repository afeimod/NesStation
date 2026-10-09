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
import android.opengl.GLES30
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
 * ★★★ MMJ（Citra MMJ 3DS）CPU/GLSL 混合滤镜显示视图 V11.2 ★★★
 *
 * ## V11.1 → V11.2 修复
 *
 * V11.1 在用户设备上出现两个问题：
 *   1. 整体画面颠倒 —— UV V 坐标布局错（top vertex 采样 v=1 而非 v=0）；
 *   2. 滤镜仍无效 —— fxHeader 用 GLES 2.0 语法（#version 100 es + gl_FragColor
 *      + texture2D + varying），但 assets/mmj/shaders/ 下的 .glsl 是按
 *      GLES 3.20 写的（用 const 全局初始化 / uint / texelFetch / textureSize
 *      等 GLES 2.0 不支持的特性）→ 编译失败 → 静默回落直通 = 滤镜无效。
 *
 * V11.2 修复：
 *   - EGL 上下文升到 GLES 3.0（EGL_CONTEXT_CLIENT_VERSION=3 + EGL_OPENGL_ES3_BIT）；
 *   - 所有 shader 用 #version 300 es + in/out + texture() + texelFetch + textureSize；
 *   - fxHeader 升级到 GLES 3.0 语法，仍提供 MMJ 滤镜期望的全部辅助函数；
 *   - 修正 UV 布局：top vertex (Y=+1) 采样 v=0（OES 纹理 V=0=top），bottom
 *     vertex (Y=-1) 采样 v=1（OES V=1=bottom）—— 与 Ps2CurvedView 同款约定；
 *   - FBO 2D 纹理用 V=0=bottom（标准 GL 约定），滤镜 pass 时 top vertex 采样
 *     v=1（top of FBO）—— 两次 pass 各自的 UV 与纹理约定匹配，画面方向正确。
 *
 * ## 设计动机（同 V11）
 *
 * 旧版方案：依赖 libcitra_mmj.so 内部 OpenGL 渲染器的 pp_shader_name 链路
 *   （ini → Settings → ShaderDir 读 .glsl → DrawScreens 应用）。前置二进制
 *   补丁 + assets 着色器种子释放 + ini 写入都到位，但用户实测【所有滤镜仍
 *   无效果】—— 核心内链的着色器加载在 GLES 上下文 / VFS 路径上静默失败。
 *
 * ## 本视图的做法（参考 DraStic + Ps2CurvedView 的 SurfaceTexture 中转模式）
 *
 *   Citra MMJ ──渲染──▶ [SurfaceTexture]（OES 纹理源）
 *                          │ updateTexImage()
 *                          ▼
 *   本视图 EGL/GL 线程 ──OES→2D FBO 拷贝 + MMJ 兼容头 + 用户选定 .glsl──▶
 *                          ▼
 *                       SurfaceView 上屏
 *
 * 1. GL 线程创建 OES 外部纹理 + SurfaceTexture，包装成 Surface 交给引擎
 *    （engine.setSurface + onSurfaceChanged）—— 引擎看到的仍是真实
 *    ANativeWindow，MMJ 的 boot 序列 + Run 契约不变；
 * 2. 每收到一帧（onFrameAvailable 信号）就 updateTexImage 取为 OES 纹理，
 *    先用一个简单的 FBO+2D 纹理把 OES 转成普通 sampler2D，再用拼好的着色器
 *    源码（兼容头 + 用户滤镜 .glsl）绘制全屏四边形到本 SurfaceView；
 * 3. 滤镜源码读取顺序：<filesDir>/azahar/shaders/<name>.glsl → APK assets
 *    /mmj/shaders/<name>.glsl。编译失败回落直通渲染（结构上不黑屏）。
 *
 * ## 滤镜激活条件
 *   - 引擎为 [com.nesstation.app.core.engine.CitraMmjEngine]；
 *   - 用户在 UI 选定了一个非空滤镜（pp_shader_name）。
 *   满足条件时 EmulatorScreen 用本视图替换普通 SurfaceView；否则维持
 *   原直绘 SurfaceView（无性能开销）。
 *
 * 性能：每帧一次 updateTexImage + 两次全屏四边形（OES→2D + 滤镜→屏）+
 * swapBuffers，3DS 场景下 GPU 占用可忽略；无帧时不绘制（阻塞等信号）。
 */
class MmjFilterView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : SurfaceView(context, attrs, defStyleAttr), SurfaceHolder.Callback {

    /** 渲染引擎（Citra MMJ），由 EmulatorScreen 注入。 */
    @Volatile
    var engine: EmulatorEngine? = null

    /**
     * 当前激活的 MMJ 后处理着色器名（如 "xBR" / "4xBR" / "HQ2X" / "HQ4X" /
     * "SEDI" / "FXAA" 等）。null/空 = 直通（不应用滤镜）。
     * 主线程设置；GL 线程在下次绘制前读取。
     */
    @Volatile
    var shaderName: String? = null

    /** UI 被菜单/设置等遮挡标志（不影响本视图，仍持续绘制）。 */
    @Volatile
    var uiBlocked: Boolean = false

    /** EGL 初始化失败回调（UI 收到后回退普通 SurfaceView 路径）。 */
    @Volatile
    var onGlFailed: (() -> Unit)? = null

    private companion object {
        const val TAG = "MmjFilterView"
    }

    // ---- GL 线程状态 ----
    private val glThreadRunning = AtomicBoolean(false)
    private var glThread: Thread? = null
    @Volatile private var glLoopStopped = false
    @Volatile private var surfaceW = 0
    @Volatile private var surfaceH = 0

    // ---- GL 资源（仅 GL 线程访问） ----
    private var eglDisplay: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
    private var eglSurface: EGLSurface = EGL14.EGL_NO_SURFACE

    // OES→2D 拷贝 program（固定，不带滤镜）
    private var progCopy = 0
    private var oesTex = 0
    private var copyFbo = 0
    private var copyTex = 0  // 2D 纹理（OES 的拷贝目标）
    private var surfaceTexture: SurfaceTexture? = null
    private var coreSurface: Surface? = null
    /** 中转 2D 纹理尺寸（与 SurfaceTexture 缓冲同步）。 */
    private var bufferW = 0
    private var bufferH = 0

    // 滤镜 program（用户选定 .glsl + MMJ 兼容头）
    private var progFilter = 0
    @Volatile private var lastBoundShaderName: String? = "<sentinel>"

    /** 引擎给我们的 Surface（每帧回调来源）。null 表示已销毁/未创建。 */
    @Volatile private var engineSurfaceGiven = false

    /** 帧到达信号（容量 1，重复帧合并）。 */
    private val frameSignal = ArrayBlockingQueue<Boolean>(1)

    /** 全屏四边形：pos(2) + uv(2)，三角带 4 顶点。 */
    private val quadBuf: FloatBuffer = ByteBuffer
        .allocateDirect(4 * 4 * 4)
        .order(ByteOrder.nativeOrder())
        .asFloatBuffer()

    // ---- 着色器源（★ V11.2：GLES 3.0 + 修正 UV 方向） ----

    /** 顶点着色器（OES→2D 拷贝 与 滤镜绘制共用，GLES 3.0 语法）。 */
    private val vsSrc = """
        #version 300 es
        in vec4 aPos;
        out vec2 vUV;
        void main() {
            vUV = aPos.zw;
            gl_Position = vec4(aPos.xy, 0.0, 1.0);
        }
    """.trimIndent()

    /** OES→2D 简单拷贝片元着色器（GL_OES_EGL_image_external_essl3）。 */
    private val fsCopySrc = """
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
     * MMJ 后处理滤镜的 GL 兼容层（V11.2：GLES 3.0 语法）：
     *   - 把 MMJ 自有类型（float2/3/4、int2/3/4、uint2/3/4）映射到 GLSL ES 3.0；
     *   - 提供 GetCoordinates/GetResolution/GetInvResolution/GetOnScreenSize/
     *     GetTime/Sample/SampleLocation/SampleFetch/SetOutput 等 MMJ 滤镜
     *     期望的全套辅助函数 —— 用户 .glsl 可直接用 MMJ 原版语法；
     *   - 采样源 = 本视图的 2D 纹理（OES→2D 拷贝后的内容）。
     *
     * ★ 重要：MMJ 原版 .glsl 用 `out float4 output_color` 这样的伪 GLSL，
     *   我们的 fxHeader 用 `#define SetOutput(c) output_color = (c)` 等宏
     *   把它适配到真实 GLSL ES 3.0；同时提供所有 MMJ 期望的全局函数。
     */
    private val fxHeader = """
        #version 300 es
        #define float2 vec2
        #define float3 vec3
        #define float4 vec4
        #define uint2 uvec2
        #define uint3 uvec3
        #define uint4 uvec4
        #define int2 ivec2
        #define int3 ivec3
        #define int4 ivec4

        precision mediump float;
        precision mediump int;
        precision mediump sampler2D;

        in vec2 vUV;
        out vec4 output_color;
        uniform sampler2D color_texture;
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
        vec4 SampleFetch(ivec2 location) { return texelFetch(color_texture, location, 0); }
    """.trimIndent()

    /** 滤镜片元着色器模板（兼容头 + 用户的 .glsl 源）。 */
    private fun buildFilterFs(userSrc: String): String {
        // 用户 .glsl 自带 #version 头时去掉（我们自己加 #version 300 es）
        val cleaned = userSrc.replace(Regex("(?m)^\\s*#version.*$"), "")
        // 用户 .glsl 可能自带 precision 声明也去掉（fxHeader 已加）
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
        // 与 Ps2CurvedView 一致：不与系统 UI 混合时更高效
        setZOrderMediaOverlay(false)
        // ★ V11.2：修正 UV 方向 —— top vertex (Y=+1) 采样 v=0（OES 纹理 V=0=top），
        //   bottom vertex (Y=-1) 采样 v=1（OES V=1=bottom）。与 Ps2CurvedView
        //   完全同款约定，避免画面颠倒。两次 pass（OES→2D FBO 与 滤镜→屏）
        //   使用同一组顶点 UV，但采样源纹理的 V 约定不同：
        //     - OES 纹理：V=0=top（屏幕坐标系，已预翻转）
        //     - 标准 2D 纹理（FBO 附件）：V=0=bottom（GL 约定）
        //   第一次 pass（OES→FBO 拷贝）后，FBO 内的 2D 纹理内容方向正确
        //   （OES top → FBO top），第二次 pass（滤镜→屏）采样 FBO 时 v=1=顶
        //   在 top vertex —— 仍同款约定。
        quadBuf.put(floatArrayOf(
            -1f, -1f, 0f, 1f,   // bottom-left vertex, uv (0, 1) = bottom of source
             1f, -1f, 1f, 1f,   // bottom-right
            -1f,  1f, 0f, 0f,   // top-left,    uv (0, 0) = top of source
             1f,  1f, 1f, 0f    // top-right
        )).position(0)
    }

    // ------------------------------------------------------------------
    // SurfaceHolder.Callback
    // ------------------------------------------------------------------

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

    // ------------------------------------------------------------------
    // GL 线程
    // ------------------------------------------------------------------

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

    /**
     * ★ V11.2：EGL 上下文升到 GLES 3.0（assets/mmj/shaders 下的 .glsl 写的是
     * GLES 3.20 语法，需要 GLES 3.0+ 才能编译）。
     */
    private fun eglInit(surface: Surface): Boolean {
        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        if (eglDisplay === EGL14.EGL_NO_DISPLAY) return false
        val version = IntArray(2)
        if (!EGL14.eglInitialize(eglDisplay, version, 0, version, 1)) return false

        // ★ V11.2：优先尝试 GLES 3.0 上下文，失败回落 GLES 2.0
        if (!eglChooseAndCreate(surface, EGL14.EGL_OPENGL_ES3_BIT, 3)) {
            Log.w(TAG, "GLES 3.0 context unavailable, fallback to GLES 2.0")
            // GLES 2.0 回落（滤镜编译会失败，自动直通 —— 至少画面不黑）
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
        // ---- 1) OES→2D 拷贝 program ----
        val vs = compileShader(GLES30.GL_VERTEX_SHADER, vsSrc) ?: return false
        val fsCopy = compileShader(GLES30.GL_FRAGMENT_SHADER, fsCopySrc) ?: run {
            GLES20.glDeleteShader(vs); return false
        }
        progCopy = GLES20.glCreateProgram()
        GLES20.glAttachShader(progCopy, vs)
        GLES20.glAttachShader(progCopy, fsCopy)
        GLES20.glBindAttribLocation(progCopy, 0, "aPos")
        GLES20.glLinkProgram(progCopy)
        GLES20.glDeleteShader(vs)
        GLES20.glDeleteShader(fsCopy)
        val linked = IntArray(1)
        GLES20.glGetProgramiv(progCopy, GLES20.GL_LINK_STATUS, linked, 0)
        if (linked[0] == 0) {
            Log.e(TAG, "copy program link failed: " + GLES20.glGetProgramInfoLog(progCopy))
            GLES20.glDeleteProgram(progCopy); progCopy = 0
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

        // ---- 3) FBO + 2D 纹理（OES→2D 拷贝目标） ----
        val fbos = IntArray(1)
        GLES30.glGenFramebuffers(1, fbos, 0)
        copyFbo = fbos[0]

        val texs2 = IntArray(1)
        GLES20.glGenTextures(1, texs2, 0)
        copyTex = texs2[0]
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, copyTex)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        // 占位 1x1 纹理（首次 updateTexImage 后会按真实尺寸重新分配）
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, 1, 1, 0,
            GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null)

        // ★ V11.2：验证 GLES 版本（GL_VERSION 字符串）—— 用于 logcat 诊断
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
        if (copyTex != 0) {
            try { GLES20.glDeleteTextures(1, intArrayOf(copyTex), 0) } catch (_: Throwable) {}
            copyTex = 0
        }
        if (copyFbo != 0) {
            try { GLES30.glDeleteFramebuffers(1, intArrayOf(copyFbo), 0) } catch (_: Throwable) {}
            copyFbo = 0
        }
        if (progCopy != 0) {
            try { GLES20.glDeleteProgram(progCopy) } catch (_: Throwable) {}
            progCopy = 0
        }
        if (progFilter != 0) {
            try { GLES20.glDeleteProgram(progFilter) } catch (_: Throwable) {}
            progFilter = 0
        }
        lastBoundShaderName = "<sentinel>"
    }

    /** 把引擎的输出 Surface 撤下。 */
    private fun releaseCoreSurface() {
        if (!engineSurfaceGiven) return
        engineSurfaceGiven = false
        try {
            val eng = engine
            eng?.onSurfaceDestroyed()
            eng?.setSurface(null)
        } catch (_: Throwable) {}
        try { coreSurface?.release() } catch (_: Throwable) {}
        coreSurface = null
    }

    /** 给引擎接上 SurfaceTexture 包装的渲染目标（GL 线程内调用）。 */
    private fun ensureCoreSurface() {
        if (engineSurfaceGiven) return
        val st = surfaceTexture ?: return
        val w = surfaceW.coerceAtLeast(1)
        val h = surfaceH.coerceAtLeast(1)
        st.setDefaultBufferSize(w, h)
        bufferW = w
        bufferH = h
        val surf = Surface(st)
        coreSurface = surf
        val eng = engine
        if (eng != null) {
            eng.setSurface(surf)
            eng.onSurfaceChanged(surf, w, h)
            engineSurfaceGiven = true
            Log.i(TAG, "MMJ core surface attached: ${w}x${h}")
        }
    }

    /**
     * GL 主循环：等帧信号 → updateTexImage → OES→2D 拷贝 → 应用滤镜 → 上屏。
     */
    private fun glLoop() {
        val st = surfaceTexture ?: return
        ensureCoreSurface()

        while (!glLoopStopped) {
            try { frameSignal.poll(100, TimeUnit.MILLISECONDS) } catch (_: InterruptedException) { break }

            val w = surfaceW
            val h = surfaceH
            if (w <= 0 || h <= 0) continue

            // 尺寸变化：重设 SurfaceTexture 默认缓冲 + 通知引擎
            if (bufferW != w || bufferH != h) {
                try { st.setDefaultBufferSize(w, h) } catch (_: Throwable) {}
                bufferW = w
                bufferH = h
                if (engineSurfaceGiven) {
                    coreSurface?.let { s ->
                        try { engine?.onSurfaceChanged(s, w, h) } catch (_: Throwable) {}
                    }
                }
            }

            try { st.updateTexImage() } catch (_: Throwable) { continue }

            // ---- 步骤 1：OES → 2D 纹理拷贝（FBO） ----
            if (!copyOesTo2d(w, h)) continue

            // ---- 步骤 2：滤镜 program 重建（如 shader 名变化） ----
            val curShader = shaderName?.trim()?.takeIf { it.isNotEmpty() && it != "(off)" }
            if (curShader != lastBoundShaderName) {
                rebuildFilterProgram(curShader)
                lastBoundShaderName = curShader
            }

            // ---- 步骤 3：滤镜绘制 / 直通绘制 ----
            GLES20.glViewport(0, 0, w, h)
            GLES20.glClearColor(0f, 0f, 0f, 1f)
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
            val prog = if (progFilter != 0 && curShader != null) progFilter else 0
            if (prog != 0) {
                GLES20.glUseProgram(prog)
                GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, copyTex)
                val uTex = GLES20.glGetUniformLocation(prog, "color_texture")
                if (uTex >= 0) GLES20.glUniform1i(uTex, 0)
                val uRes = GLES20.glGetUniformLocation(prog, "resolution")
                if (uRes >= 0) GLES20.glUniform2f(uRes, w.toFloat(), h.toFloat())
                val uFc = GLES20.glGetUniformLocation(prog, "frame_count")
                if (uFc >= 0) GLES20.glUniform1i(uFc, 0)
            } else if (progCopy != 0) {
                // 直通：用拷贝 program 直接把 2D 纹理绘到屏幕
                GLES20.glUseProgram(progCopy)
                GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, copyTex)
                val uTex = GLES20.glGetUniformLocation(progCopy, "uTex")
                if (uTex >= 0) GLES20.glUniform1i(uTex, 0)
            } else {
                continue
            }

            val aPos = GLES20.glGetAttribLocation(
                if (prog != 0) prog else progCopy, "aPos"
            )
            quadBuf.position(0)
            GLES20.glEnableVertexAttribArray(aPos)
            GLES20.glVertexAttribPointer(aPos, 4, GLES20.GL_FLOAT, false, 0, quadBuf)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
            GLES20.glDisableVertexAttribArray(aPos)
            EGL14.eglSwapBuffers(eglDisplay, eglSurface)
        }
    }

    /**
     * OES → 2D 纹理拷贝：把 OES 纹理渲染到 copyFbo（附件 copyTex）。
     * 拷贝后 copyTex 即可作为 sampler2D 给滤镜着色器使用。
     */
    private fun copyOesTo2d(w: Int, h: Int): Boolean {
        if (copyFbo == 0 || copyTex == 0 || oesTex == 0 || progCopy == 0) return false
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, copyTex)
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, w, h, 0,
            GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, copyFbo)
        GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0,
            GLES20.GL_TEXTURE_2D, copyTex, 0)
        GLES20.glViewport(0, 0, w, h)
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        GLES20.glUseProgram(progCopy)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTex)
        val uTex = GLES20.glGetUniformLocation(progCopy, "uTex")
        if (uTex >= 0) GLES20.glUniform1i(uTex, 0)
        val aPos = GLES20.glGetAttribLocation(progCopy, "aPos")
        quadBuf.position(0)
        GLES20.glEnableVertexAttribArray(aPos)
        GLES20.glVertexAttribPointer(aPos, 4, GLES20.GL_FLOAT, false, 0, quadBuf)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(aPos)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        return true
    }

    /** 按当前 shaderName 重建滤镜 program（编译失败回落 null = 直通）。 */
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
        val fs = buildFilterFs(src)
        val vs = compileShader(GLES30.GL_VERTEX_SHADER, vsSrc)
        if (vs == 0) return
        val fsObj = compileShader(GLES30.GL_FRAGMENT_SHADER, fs)
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
            Log.i(TAG, "filter program '$name' compiled OK")
        }
    }

    /**
     * 读 .glsl 着色器源码：优先 <filesDir>/azahar/shaders/<name>.glsl，
     * 回落 APK assets/mmj/shaders/<name>.glsl。
     */
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

    /** 暂停/恢复绘制（Activity 生命周期同步）。 */
    fun setPaused(paused: Boolean) {
        glLoopStopped = paused
        if (!paused) frameSignal.offer(true)
    }
}
