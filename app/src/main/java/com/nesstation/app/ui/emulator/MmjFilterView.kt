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
 * ★★★ MMJ（Citra MMJ 3DS）GLSL 滤镜显示视图 V11.3 ★★★
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
     * MMJ 后处理滤镜的 GL 兼容层（V11.3：GLES 3.0 + samplerExternalOES）：
     *   - 把 MMJ 自有类型（float2/3/4、int2/3/4、uint2/3/4）映射到 GLSL ES 3.0；
     *   - 提供 GetCoordinates/GetResolution/GetInvResolution/GetOnScreenSize/
     *     GetTime/Sample/SampleLocation/SetOutput 等 MMJ 滤镜期望的全部辅助函数；
     *   - 采样源 = 本视图的 OES 外部纹理（来自 SurfaceTexture）。
     *
     * ★ 重要：assets/mmj/shaders/ 下 14 个 .glsl 经扫描确认全部只用
     *   SampleLocation/Sample（不依赖 SampleFetch/texelFetch），可安全
     *   切到 OES 纹理（OES 不支持 texelFetch）。
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

        precision mediump float;
        precision mediump int;

        in vec2 vUV;
        out vec4 output_color;
        uniform samplerExternalOES color_texture;
        uniform vec2 resolution;
        uniform int frame_count;

        ivec2 SampleSize() { return textureSize(color_texture); }
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
            eng?.onSurfaceDestroyed()
            eng?.setSurface(null)
        } catch (_: Throwable) {}
        try { coreSurface?.release() } catch (_: Throwable) {}
        coreSurface = null
    }

    private fun ensureCoreSurface() {
        if (engineSurfaceGiven) return
        val st = surfaceTexture ?: return
        val w = surfaceW.coerceAtLeast(1)
        val h = surfaceH.coerceAtLeast(1)
        st.setDefaultBufferSize(w, h)
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

    private fun glLoop() {
        val st = surfaceTexture ?: return
        ensureCoreSurface()

        while (!glLoopStopped) {
            try { frameSignal.poll(100, TimeUnit.MILLISECONDS) } catch (_: InterruptedException) { break }

            val w = surfaceW
            val h = surfaceH
            if (w <= 0 || h <= 0) continue

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

            GLES20.glUseProgram(prog)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTex)
            // 统一绑定 OES 纹理到 texture unit 0
            val uTexName = if (prog == progFilter) "color_texture" else "uTex"
            val uTex = GLES20.glGetUniformLocation(prog, uTexName)
            if (uTex >= 0) GLES20.glUniform1i(uTex, 0)

            if (prog == progFilter) {
                val uRes = GLES20.glGetUniformLocation(prog, "resolution")
                if (uRes >= 0) GLES20.glUniform2f(uRes, w.toFloat(), h.toFloat())
                val uFc = GLES20.glGetUniformLocation(prog, "frame_count")
                if (uFc >= 0) GLES20.glUniform1i(uFc, 0)
            }

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
            Log.i(TAG, "filter program '$name' compiled OK")
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
