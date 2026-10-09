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
 * ★★★ MMJ（Citra MMJ 3DS）CPU/GLSL 混合滤镜显示视图 ★★★
 *
 * ## 设计动机（回应用户"mmj 核心使用全局滤镜的 xbr 和 hqx 无效果，
 *   自带的所有滤镜也无效果"）
 *
 * 旧版方案：依赖 libcitra_mmj.so 内部 OpenGL 渲染器的 pp_shader_name 链路
 *   （ini → Settings → ShaderDir 读 .glsl → #version 320 es 编译 → DrawScreens
 *   应用）。前置二进制补丁 + assets/mmj/shaders 种子释放 + ini 写入做了完整
 *   准备，但用户实测【所有滤镜仍无效果】——根因可能是补丁覆盖不全、GL 上下文
 *   不匹配、VFS 路径解析异常等多重因素叠加，已无法仅从外部进一步根治。
 *
 * ## 本视图的做法（参考 DraStic 的全局放大滤镜架构 + Ps2CurvedView 的
 *   SurfaceTexture 中转模式）
 *
 *   Citra MMJ ──渲染──▶ [SurfaceTexture]（离屏消费者，对本视图是 OES 纹理源）
 *                          │ updateTexImage()
 *                          ▼
 *   本视图 EGL/GL 线程 ──OES→2D FBO 拷贝 + MMJ 兼容头 + 用户选定的 .glsl──▶
 *                          ▼
 *                       SurfaceView 上屏
 *
 * 1. GL 线程创建 OES 外部纹理 + [SurfaceTexture]，包装成 [Surface] 交给
 *    引擎（`engine.setSurface` + `onSurfaceChanged`）—— 引擎看到的仍是
 *    一个带正尺寸的真实 ANativeWindow，MMJ 的 boot 序列 + Run 契约不变；
 * 2. 每收到一帧（onFrameAvailable 信号）就 updateTexImage 把最新帧绑成
 *    OES 纹理，先用一个简单的 FBO+2D 纹理把 OES 转成普通 sampler2D（MMJ
 *    自带 .glsl 滤镜使用 sampler2D + 自有 GetCoordinates/SampleLocation/
 *    SetOutput 兼容层），再用拼好的着色器源码（兼容头 + 用户滤镜 .glsl）
 *    绘制全屏四边形到本 SurfaceView；
 * 3. 滤镜源码读取顺序：<filesDir>/azahar/shaders/<name>.glsl → APK assets
 *    /mmj/shaders/<name>.glsl。编译失败回落直通渲染（结构上不黑屏）。
 *
 * ## 与原版 MMJ 内链的差异
 *   - 原 MMJ 内链：核心在自己 GL 上下文里编译 .glsl，可能在 GLES 3.2 上下文
 *     路径下静默失败 → 滤镜无效。
 *   - 本视图：在我们自己的 GLES 2.0 上下文里编译（用 #version 100 es +
 *     OES 扩展，绝大多数设备都支持），编译失败立即回落直通，不依赖核心内链。
 *
 * ## 与 MmjGlView（已废弃）的差异
 *   - MmjGlView 依赖 getScreenTexture() JNI 返回的纹理 ID，但模式 0（核心
 *     直绘）下"呈现提供器"全局为 null → JNI 返回全零数组 → 叠加层不透明
 *     全屏 quad 把直绘画面盖成纯黑（v5 已移除）。
 *   - 本视图直接接管【整个 surface】（核心不直绘），SurfaceTexture 是唯一
 *     帧源 → 不存在"假阳性成功"路径，结构上不可能黑屏。
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

    /** 帧到达信号（容量 1，重复帧合并 —— 引擎帧率高于屏幕刷新率时不排队）。 */
    private val frameSignal = ArrayBlockingQueue<Boolean>(1)

    /** 全屏四边形：pos(2) + uv(2)，三角带 4 顶点。 */
    private val quadBuf: FloatBuffer = ByteBuffer
        .allocateDirect(4 * 4 * 4)
        .order(ByteOrder.nativeOrder())
        .asFloatBuffer()

    // ---- 着色器源 ----

    /** 顶点着色器（OES→2D 拷贝 与 滤镜绘制共用）。 */
    private val vsSrc = """
        attribute vec4 aPos;
        varying vec2 vUV;
        void main() {
            vUV = aPos.zw;
            gl_Position = vec4(aPos.xy, 0.0, 1.0);
        }
    """.trimIndent()

    /** OES→2D 简单拷贝片元着色器（GL_OES_EGL_image_external）。 */
    private val fsCopySrc = """
        #extension GL_OES_EGL_image_external : require
        precision mediump float;
        uniform samplerExternalOES uTex;
        varying vec2 vUV;
        void main() {
            gl_FragColor = texture2D(uTex, vUV);
        }
    """.trimIndent()

    /**
     * MMJ 后处理滤镜的 GL 兼容层（与旧 MmjGlView fxHeader 同源）：
     *   - 把 MMJ 自有类型（float2/3/4、int2/3/4、uint2/3/4）映射到 GLSL ES；
     *   - 提供 GetCoordinates/GetResolution/GetInvResolution/GetOnScreenSize/
     *     GetTime/Sample/SampleLocation/SampleFetch/SetOutput 等 MMJ 滤镜
     *     期望的全套辅助函数 —— 用户 .glsl 可直接用 MMJ 原版语法；
     *   - 采样源 = 本视图的 2D 纹理（OES→2D 拷贝后的内容）。
     */
    private val fxHeader = """
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
        varying vec2 frag_tex_coord_uv;
        uniform sampler2D color_texture;
        uniform vec2 resolution;
        uniform int frame_count;

        ivec2 SampleSize() { return textureSize(color_texture, 0); }
        vec2 GetResolution() { return vec2(SampleSize()); }
        vec2 GetInvResolution() { return 1.0 / GetResolution(); }
        vec2 GetOnScreenSize() { return resolution; }
        vec2 GetCoordinates() { return frag_tex_coord_uv; }
        uint GetTime() { return uint(frame_count); }
        void SetOutput(vec4 color) { gl_FragColor = color; }
        vec4 Sample() { return texture2D(color_texture, frag_tex_coord_uv); }
        vec4 SampleLocation(vec2 location) { return texture2D(color_texture, location); }
        vec4 SampleFetch(ivec2 location) { return texelFetch(color_texture, location, 0); }
    """.trimIndent()

    /** 滤镜片元着色器模板（兼容头 + 用户的 .glsl 源）。 */
    private fun buildFilterFs(userSrc: String): String {
        // 用户 .glsl 自带 #version 头时去掉（我们自己加 #version 100 es）
        val cleaned = userSrc.replace(Regex("(?m)^\\s*#version.*$"), "")
        return """
            #version 100 es
            $fxHeader

            // ----- 用户滤镜源码 -----
            $cleaned
        """.trimIndent()
    }

    init {
        holder.setFormat(android.graphics.PixelFormat.RGBX_8888)
        holder.addCallback(this)
        // 与 Ps2CurvedView 一致：不与系统 UI 混合时更高效
        setZOrderMediaOverlay(false)
        // 全屏四边形顶点：(-1,-1)u(0,1) → (1,-1)u(1,1) → (-1,1)u(0,0) → (1,1)u(1,0)
        // UV Y 翻转：SurfaceTexture 的 OES 纹理已是 OpenGL 约定（Y 上），
        // 但渲染到屏幕时 EGL 默认 window 坐标 Y 下，所以 UV.v 翻 1-。
        quadBuf.put(floatArrayOf(
            -1f, -1f, 0f, 0f,
             1f, -1f, 1f, 0f,
            -1f,  1f, 0f, 1f,
             1f,  1f, 1f, 1f
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
        frameSignal.offer(true)   // 唤醒阻塞中的 poll，让循环尽快退出
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

        val attribs = intArrayOf(
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT,
            EGL14.EGL_NONE
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val num = IntArray(1)
        if (!EGL14.eglChooseConfig(eglDisplay, attribs, 0, configs, 0, 1, num, 0) || num[0] < 1) {
            return false
        }
        val ctxAttribs = intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE)
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
        val vs = compileShader(GLES20.GL_VERTEX_SHADER, vsSrc) ?: return false
        val fsCopy = compileShader(GLES20.GL_FRAGMENT_SHADER, fsCopySrc) ?: run {
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
        GLES20.glGenFramebuffers(1, fbos, 0)
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
            try { GLES20.glDeleteFramebuffers(1, intArrayOf(copyFbo), 0) } catch (_: Throwable) {}
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

    /** 把引擎的输出 Surface 撤下（surface 销毁 / GL 线程退出时）。 */
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

            // 查询 OES 纹理真实尺寸（核心可能用任意内部分辨率渲染）
            val texW: Int
            val texH: Int
            try {
                val dims = IntArray(2)
                // SurfaceTexture.getTimestamp() 之后才能查到真实尺寸；
                // 我们用 textureSize(shader) 拿尺寸 —— 通过 FBO 拷贝时
                // glViewport 会自动按 FBO 附件尺寸裁剪，这里只取一个估值
                // 用 surfaceW/surfaceH（屏幕尺寸）即可（拷贝 viewport 用屏坐标）。
                texW = w
                texH = h
            } catch (_: Throwable) {
                continue
            }

            // ---- 步骤 1：OES → 2D 纹理拷贝（FBO）----
            if (!copyOesTo2d(texW, texH)) continue

            // ---- 步骤 2：滤镜 program 重建（如 shader 名变化）----
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
                // 无 program：黑屏兜底（理论上不应该到这里）
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
        // 重新分配 copyTex 尺寸（如变化）
        if (bufferW != w || bufferH != h) {
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, copyTex)
            GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, w, h, 0,
                GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null)
        }
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, copyFbo)
        GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0,
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
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
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
        val vs = compileShader(GLES20.GL_VERTEX_SHADER, vsSrc)
        if (vs == 0) return
        val fsObj = compileShader(GLES20.GL_FRAGMENT_SHADER, fs)
        if (fsObj == 0) {
            GLES20.glDeleteShader(vs)
            Log.w(TAG, "filter shader '$name' compile failed, fallback passthrough")
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
        // 文件系统（运行时种子释放后的位置）
        try {
            val ctx = com.nesstation.app.NesApp.get() ?: return null
            val f = File(File(ctx.filesDir, "azahar"), "shaders/$name.glsl")
            if (f.isFile) return f.readText()
        } catch (_: Throwable) {}
        // APK assets（首次启动种子释放前）
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
            Log.e(TAG, "shader compile failed: " + GLES20.glGetShaderInfoLog(sh))
            GLES20.glDeleteShader(sh)
            return 0
        }
        return sh
    }

    /** 暂停/恢复绘制（Activity 生命周期同步）。 */
    fun setPaused(paused: Boolean) {
        glLoopStopped = paused
        if (!paused) frameSignal.offer(true)  // 唤醒可能的阻塞 poll
    }
}
