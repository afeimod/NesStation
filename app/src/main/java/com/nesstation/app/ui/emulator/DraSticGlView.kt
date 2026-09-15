package com.nesstation.app.ui.emulator

import android.content.Context
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES20
import android.util.Log
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import com.dsemu.drastic.DraSticJNI
import com.nesstation.app.core.engine.DraSticEngine
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlin.math.roundToInt

/**
 * DraStic（激烈）核心的 OpenGL 显示视图 —— 复刻原版 App 的显示路径
 * （含滤镜管线，第四轮修复：全局滤镜 + 帧同步对齐）。
 *
 * ## 原生渲染契约（libdrastic_arm64.so 逐指令反汇编验证）
 * 帧池：双缓冲，各 0x180000 字节（两屏 × 0xC0000 = 512×384×4，按 2x 容量
 * 预分配）；缓冲指针存 video_state+0/+8，当前写入档存 +0x958。
 *
 * ### 帧同步（问题 3 修复 —— 与原版 onDrawFrame 逐字对齐）
 * 原版 GL 线程每帧：`getFrameInfo()` 低 16 位（模拟主循环 0x14c4b0 帧计数
 * 器）== 0 时 → `waitScreen()`（阻塞等模拟线程 signal）→ `fxRender`/`renderFrame`
 * 上传 + 绘制。本视图完全复刻该模型：
 *  1. GL 线程成为唯一的 waitScreen 消费者（引擎渲染线程在 GL 接管后驻车，
 *     不再互偷 pthread_cond_signal —— 两个等待者会各拿一半信号，双路径都丢帧）；
 *  2. 消费与模拟严格同步后，多线程 3D（bit28）的"异步分段 + 上一帧补拷贝"
 *     帧冲刷不再被异步读到中间态 —— 画面错乱消失，与原版一致。
 *
 * ### 滤镜管线（问题 1 修复 —— 原版 DraSticGlView$j 契约复刻）
 * 原版通过三条 JNI 挂接 .dfx 着色器链（滤镜不在 config 位域内）：
 *  - `fxLoad(path, vOff, uvOff)`：加载 "DraStic/shaders/<名>.dfx"
 *    （虚拟路径经 PathCache 解析到 <sysDir>/shaders/），vOff/uvOff = 共享
 *    VBO 内 a_vertex_coordinate / a_texture_coordinate 数据的起始字节；
 *  - `fxSetup(srcW, srcH, x, y, sw, sh)`：帧纹理尺寸 + 最终 pass 视口；
 *  - `fxRender(tex1, tex2, vertA, vertB, vertFull, wA, hA, wB, hB, portrait)`：
 *    上传帧池 → 按 pass 链绘制：中间 pass（带 FBO）画顶点 18-23（全屏
 *    四边形），最终 pass（FBO=0）画顶点 0-5 / 6-11（两屏四边形，
 *    glViewport = fxSetup 的 (x,y,sw,sh)），u_target_size = (wA,hA)/(wB,hB)
 *    （两屏目标矩形像素尺寸，反汇编 csel + glUniform2f 确认）。
 * 共享 VBO 布局（与原版字节级一致，positions 2 float/顶点、UV 同构分离）：
 *
 * ```
 *  字节 0    顶点 0-5   上屏位置四边形（布局变化时重传）
 *  字节 48   顶点 6-11  下屏位置四边形
 *  字节 96   顶点 12-17 全屏四边形（静态）
 *  字节 144  顶点 18-23 全屏四边形（静态；fx 中间 pass 绘制 first=18）
 *  字节 192  顶点 24-29 暂停背景四边形（静态）
 *  字节 240  顶点 30-35 FPS 叠加四边形（本前端不用，置零）
 *  字节 2208 UV 四边形 ×6（静态全 [0..1]；fxLoad 的 uvOff 指向这里）
 * ```
 * 滤镜 = "none" 或 fxLoad 失败时回退原生 renderFrame 直绘（同一 VBO 布局：
 * renderFrame 只做上传 + glDrawArrays，着色器/指针由调用方准备）。
 *
 * ### 触摸输入
 * 复刻 NdsDualScreenView：下屏矩形内线性映射为 DS 触点 (0..255, 0..191)，
 * 矩形外释放。
 */
class DraSticGlView @JvmOverloads constructor(
    context: Context
) : SurfaceView(context), SurfaceHolder.Callback {

    companion object {
        private const val TAG = "DraSticGlView"

        /** 上/下屏纹理的 1x / 2x 尺寸（与原生帧池容量一致）。 */
        private const val SCREEN_W_1X = 256
        private const val SCREEN_H_1X = 192
        private const val SCREEN_W_2X = 512
        private const val SCREEN_H_2X = 384

        // ---- 共享 VBO 布局（原版字节级契约，见类文档） ----
        private const val VBO_SIZE = 4096
        private const val VBO_UV_OFFSET = 2208      // fxLoad 的 uvOff 参数
        private const val QUAD_FLOATS = 12          // 6 顶点 × 2 float
        private const val QUAD_BYTES = QUAD_FLOATS * 4
        private const val OFF_POS_TOP = 0           // 顶点 0-5：上屏
        private const val OFF_POS_BOTTOM = 48       // 顶点 6-11：下屏
        private const val OFF_POS_FULL = 96         // 顶点 12-17：全屏
        private const val OFF_POS_FULL2 = 144       // 顶点 18-23：全屏（fx 中间 pass）

        private const val VS_SRC = """
            attribute vec2 aPos;
            attribute vec2 aUV;
            varying vec2 vUV;
            void main() {
                gl_Position = vec4(aPos, 0.0, 1.0);
                vUV = aUV;
            }
        """

        private const val FS_SRC = """
            precision mediump float;
            varying vec2 vUV;
            uniform sampler2D uTex;
            void main() {
                gl_FragColor = texture2D(uTex, vUV);
            }
        """
    }

    /** 激烈引擎（渲染 / 触摸 / 生命周期全部经由它）。 */
    @Volatile
    var engine: DraSticEngine? = null

    /** 菜单展开等 UI 阻断（true 时忽略触摸与按键）。 */
    @Volatile
    var uiBlocked: Boolean = false

    /** 屏幕布局（与 NdsDualScreenView 同一套取值）。 */
    @Volatile
    var screenLayout: String = "Top/Bottom"

    /** 双线性平滑滤波（false = 最近邻，像素风更锐利）。 */
    @Volatile
    var smoothFilter: Boolean = true

    /** 自定义布局矩形（归一化 left/top/right/bottom；null = 按 screenLayout 派生）。 */
    @Volatile
    var customTopRect: FloatArray? = null
    @Volatile
    var customBottomRect: FloatArray? = null

    /** EGL 初始化失败回调（UI 收到后切换回 Canvas 路径）。 */
    @Volatile
    var onGlFailed: (() -> Unit)? = null

    // ---- GL 线程状态 ----
    private val glThreadRunning = AtomicBoolean(false)
    private var glThread: Thread? = null
    private val surfaceReady = AtomicBoolean(false)
    @Volatile private var surfaceW = 0
    @Volatile private var surfaceH = 0
    @Volatile private var layoutDirty = true

    // ---- GL 资源（仅 GL 线程访问） ----
    private var eglDisplay: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
    private var eglSurface: EGLSurface = EGL14.EGL_NO_SURFACE
    private var prog = 0
    private var aPosLoc = 0
    private var aUvLoc = 0
    private var vbo = 0
    private var texTop = 0
    private var texBottom = 0
    private var texW = SCREEN_W_1X
    private var texH = SCREEN_H_1X

    // ---- 滤镜（fx）状态（仅 GL 线程访问） ----
    /** 当前已请求加载的滤镜名（含 "none"；与引擎 optFilter 比对触发重载）。 */
    private var fxWanted: String? = null
    /** fxLoad 成功 → 帧循环走 fxRender 滤镜路径。 */
    private var fxUsable = false

    /** 两屏位置四边形（GL 线程内更新；原版顶点序：两个三角形拼矩形）。 */
    private val posData = FloatArray(QUAD_FLOATS * 2)
    private val posBuf: FloatBuffer = ByteBuffer
        .allocateDirect(QUAD_FLOATS * 2 * 4)
        .order(ByteOrder.nativeOrder())
        .asFloatBuffer()
    private val fullQuadBuf: FloatBuffer = ByteBuffer
        .allocateDirect(QUAD_BYTES)
        .order(ByteOrder.nativeOrder())
        .asFloatBuffer()
    private val uvQuadsBuf: FloatBuffer = ByteBuffer
        .allocateDirect(QUAD_BYTES * 6)
        .order(ByteOrder.nativeOrder())
        .asFloatBuffer()

    init {
        holder.addCallback(this)
        // GLSurfaceView 的合成类型：不与系统 UI 混合时更高效
        setZOrderMediaOverlay(false)
    }

    // ------------------------------------------------------------------
    // SurfaceHolder.Callback
    // ------------------------------------------------------------------

    override fun surfaceCreated(holder: SurfaceHolder) {
        surfaceReady.set(true)
        startGlThread()
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        surfaceW = width
        surfaceH = height
        layoutDirty = true
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        surfaceReady.set(false)
        stopGlThread()
    }

    /** 布局参数变化时通知 GL 线程重建顶点。 */
    fun notifyLayoutChanged() {
        layoutDirty = true
    }

    // ------------------------------------------------------------------
    // GL 线程
    // ------------------------------------------------------------------

    private fun startGlThread() {
        if (glThreadRunning.getAndSet(true)) return
        val view = this
        glThread = thread(name = "drastic-gl", isDaemon = true) {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_DISPLAY)
            try {
                if (!eglInit(holder.surface)) {
                    Log.e(TAG, "EGL init failed — fallback to canvas path")
                    view.post { onGlFailed?.invoke() }
                    return@thread
                }
                if (!glInitResources()) {
                    Log.e(TAG, "GL resources init failed — fallback to canvas path")
                    glReleaseEgl()
                    view.post { onGlFailed?.invoke() }
                    return@thread
                }
                glLoop()
            } catch (t: Throwable) {
                Log.e(TAG, "GL thread died", t)
                view.post { onGlFailed?.invoke() }
            } finally {
                // 释放 GL 帧消费接管（画布路径恢复帧搬运）
                engine?.glDisplayActive = false
                glReleaseResources()
                glReleaseEgl()
                glThreadRunning.set(false)
            }
        }
    }

    private fun stopGlThread() {
        val t = glThread
        if (t != null) {
            try { t.join(1500) } catch (_: InterruptedException) {}
        }
        glThread = null
    }

    private fun eglInit(surface: android.view.Surface): Boolean {
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
        // 着色器程序（无滤镜路径用；滤镜路径的程序由原生 fxLoad/每 pass 自建）
        val vs = compileShader(GLES20.GL_VERTEX_SHADER, VS_SRC) ?: return false
        val fs = compileShader(GLES20.GL_FRAGMENT_SHADER, FS_SRC) ?: run {
            GLES20.glDeleteShader(vs); return false
        }
        prog = GLES20.glCreateProgram()
        GLES20.glAttachShader(prog, vs)
        GLES20.glAttachShader(prog, fs)
        GLES20.glLinkProgram(prog)
        GLES20.glDeleteShader(vs)
        GLES20.glDeleteShader(fs)
        val linked = IntArray(1)
        GLES20.glGetProgramiv(prog, GLES20.GL_LINK_STATUS, linked, 0)
        if (linked[0] == 0) {
            Log.e(TAG, "program link failed: " + GLES20.glGetProgramInfoLog(prog))
            GLES20.glDeleteProgram(prog)
            prog = 0
            return false
        }
        aPosLoc = GLES20.glGetAttribLocation(prog, "aPos")
        aUvLoc = GLES20.glGetAttribLocation(prog, "aUV")
        GLES20.glUseProgram(prog)
        val uTex = GLES20.glGetUniformLocation(prog, "uTex")
        GLES20.glUniform1i(uTex, 0)

        // 共享顶点缓冲（非交错布局：位置四边形 0-287B，UV 四边形 2208B 起）
        val vbos = IntArray(1)
        GLES20.glGenBuffers(1, vbos, 0)
        vbo = vbos[0]
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vbo)
        GLES20.glBufferData(
            GLES20.GL_ARRAY_BUFFER, VBO_SIZE, null, GLES20.GL_DYNAMIC_DRAW
        )
        uploadStaticVertices()

        // 纹理（尺寸 / 格式随后按引擎配置分配）
        val texs = IntArray(2)
        GLES20.glGenTextures(2, texs, 0)
        texTop = texs[0]
        texBottom = texs[1]

        GLES20.glDisable(GLES20.GL_BLEND)
        GLES20.glDisable(GLES20.GL_DEPTH_TEST)
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        return true
    }

    /** 上传静态顶点数据：全屏四边形 ×2 + 暂停背景四边形 + 全零 FPS 四边形
     *  + UV 四边形 ×6（2208B 起，fxLoad 的 uvOff 指向这里）。
     *  全部与原版 onSurfaceCreated / c() 逐字节同构。 */
    private fun uploadStaticVertices() {
        // 全屏 NDC 四边形（原版：{-1,1, -1,-1, 1,-1, -1,1, 1,-1, 1,1}）
        val full = floatArrayOf(-1f, 1f, -1f, -1f, 1f, -1f, -1f, 1f, 1f, -1f, 1f, 1f)
        fullQuadBuf.clear()
        fullQuadBuf.put(full).flip()
        GLES20.glBufferSubData(GLES20.GL_ARRAY_BUFFER, OFF_POS_FULL, QUAD_BYTES, fullQuadBuf)
        GLES20.glBufferSubData(GLES20.GL_ARRAY_BUFFER, OFF_POS_FULL2, QUAD_BYTES, fullQuadBuf)
        GLES20.glBufferSubData(GLES20.GL_ARRAY_BUFFER, OFF_POS_FULL2 + QUAD_BYTES, QUAD_BYTES, fullQuadBuf)
        // FPS 叠加四边形（顶点 30-35）：本前端不画原生 FPS，置零
        val zeros = FloatArray(QUAD_FLOATS)
        fullQuadBuf.clear()
        fullQuadBuf.put(zeros).flip()
        GLES20.glBufferSubData(
            GLES20.GL_ARRAY_BUFFER, OFF_POS_FULL2 + QUAD_BYTES * 2, QUAD_BYTES, fullQuadBuf
        )
        // UV 四边形 ×6：全 [0..1]（原版 c()：v0(0,0) v1(0,1) v2(1,1) v3(0,0) v4(1,1) v5(1,0)）
        val uvQuad = floatArrayOf(0f, 0f, 0f, 1f, 1f, 1f, 0f, 0f, 1f, 1f, 1f, 0f)
        val uvs = FloatArray(QUAD_FLOATS * 6)
        for (q in 0 until 6) System.arraycopy(uvQuad, 0, uvs, q * QUAD_FLOATS, QUAD_FLOATS)
        uvQuadsBuf.clear()
        uvQuadsBuf.put(uvs).flip()
        GLES20.glBufferSubData(
            GLES20.GL_ARRAY_BUFFER, VBO_UV_OFFSET, QUAD_BYTES * 6, uvQuadsBuf
        )
        GLES20.glGetError() // 清理可能的残留错误
    }

    /** 按引擎会话快照（activeHdRender）分配两张屏幕纹理。
     * 恒为 32 位 GL_RGBA/UNSIGNED_BYTE（与原生 32 位上传常量一致）。 */
    private fun allocTextures(eng: DraSticEngine) {
        val hd = eng.activeHdRender
        texW = if (hd) SCREEN_W_2X else SCREEN_W_1X
        texH = if (hd) SCREEN_H_2X else SCREEN_H_1X
        for (tex in intArrayOf(texTop, texBottom)) {
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
            applyTexFilter()
            GLES20.glTexImage2D(
                GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, texW, texH, 0,
                GLES20.GL_RGBA,
                GLES20.GL_UNSIGNED_BYTE,
                null
            )
        }
        GLES20.glGetError() // 清理可能的分配错误残留
    }

    private fun glReleaseResources() {
        if (eglDisplay === EGL14.EGL_NO_DISPLAY) return
        try {
            if (vbo != 0) {
                GLES20.glDeleteBuffers(1, intArrayOf(vbo), 0)
                vbo = 0
            }
            if (texTop != 0 || texBottom != 0) {
                GLES20.glDeleteTextures(2, intArrayOf(texTop, texBottom), 0)
                texTop = 0
                texBottom = 0
            }
            if (prog != 0) {
                GLES20.glDeleteProgram(prog)
                prog = 0
            }
        } catch (_: Throwable) {
        }
    }

    private fun compileShader(type: Int, src: String): Int? {
        val sh = GLES20.glCreateShader(type)
        GLES20.glShaderSource(sh, src)
        GLES20.glCompileShader(sh)
        val ok = IntArray(1)
        GLES20.glGetShaderiv(sh, GLES20.GL_COMPILE_STATUS, ok, 0)
        if (ok[0] == 0) {
            Log.e(TAG, "shader compile failed: " + GLES20.glGetShaderInfoLog(sh))
            GLES20.glDeleteShader(sh)
            return null
        }
        return sh
    }

    /** 平滑/最近邻纹理过滤（NPOT 纹理只允许非 mipmap 过滤）。 */
    private fun applyTexFilter() {
        val f = if (smoothFilter) GLES20.GL_LINEAR else GLES20.GL_NEAREST
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, f)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, f)
    }

    // ------------------------------------------------------------------
    // 滤镜管线（原版 fxLoad / fxSetup / fxRender 契约）
    // ------------------------------------------------------------------

    /** 滤镜检查点：引擎 optFilter 与已加载名不一致时重载 .dfx 着色器链。
     *  @param force 强制重载（会话开始 / 新 EGL 上下文后） */
    private fun fxCheck(eng: DraSticEngine, force: Boolean = false) {
        val want = eng.optFilter.ifBlank { "none" }
        if (!force && want == fxWanted) return
        fxWanted = want
        fxUsable = false
        if (want == "none") return
        // 原版 f0.h.g()：虚拟路径 DraStic/shaders/<名>.dfx → PathCache →
        // <sysDir>/shaders/<名>.dfx；参数 (0, 2208) = 位置/UV 数据 VBO 偏移
        val rc = try {
            DraSticJNI.fxLoad("DraStic/shaders/$want.dfx", 0, VBO_UV_OFFSET)
        } catch (t: Throwable) {
            Log.w(TAG, "fxLoad threw", t)
            -1
        }
        if (rc == 0) {
            fxUsable = true
            fxSetupNow()
            Log.i(TAG, "fxLoad ok: $want")
        } else {
            // 原版行为：toast 提示 + 回退无滤镜渲染（draw_screen pass 数为 0 → 直绘）
            Log.w(TAG, "fxLoad failed rc=$rc: $want — fallback to plain renderFrame")
        }
    }

    /** fxSetup：帧纹理尺寸 + 最终 pass 视口（原版 onSurfaceChanged 语义）。 */
    private fun fxSetupNow() {
        try {
            DraSticJNI.fxSetup(
                texW, texH, 0, 0,
                surfaceW.coerceAtLeast(1), surfaceH.coerceAtLeast(1)
            )
        } catch (t: Throwable) {
            Log.w(TAG, "fxSetup failed", t)
            fxUsable = false
        }
    }

    // ------------------------------------------------------------------
    // 帧循环（原版 onDrawFrame 同步模型）
    // ------------------------------------------------------------------

    private fun glLoop() {
        var sessionLoaded = false
        var lastSmooth = true
        var lastFxW = 0
        var lastFxH = 0

        while (glThreadRunning.get() && surfaceReady.get()) {
            val eng = engine
            if (eng == null || !eng.isLoaded) {
                // 未加载：清屏等待（加载遮罩由 Compose 层显示）
                GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
                swap()
                Thread.sleep(50)
                sessionLoaded = false
                continue
            }

            // 会话首帧：分配纹理 → 接管帧同步（引擎渲染线程驻车）→ 加载滤镜
            if (!sessionLoaded) {
                allocTextures(eng)
                eng.glDisplayActive = true
                sessionLoaded = true
                lastSmooth = smoothFilter
                layoutDirty = true
                lastFxW = 0; lastFxH = 0
                fxCheck(eng, force = true)
                GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vbo)
                rebuildVertices()
                layoutDirty = false
            }

            // ---- 帧同步门（原版 getFrameInfo 低 16 位 == 0 时消费新帧） ----
            val frameInfo = try {
                DraSticJNI.getFrameInfo()
            } catch (_: Throwable) {
                0
            }
            if ((frameInfo and 0xFFFF) == 0) {
                // 会话可能恰在门与等待之间结束（cleanup 置 isLoaded=false 且
                // 不再有信号源）——重查一次避免阻塞在无人唤醒的 condvar 上。
                if (!eng.isLoaded) {
                    sessionLoaded = false
                    continue
                }
                // 阻塞到模拟线程产出新帧（原版 waitScreen 语义）——
                // 本线程是唯一消费者：消费与模拟严格同步，多线程 3D 的
                // 异步帧冲刷不会被读到中间态。
                DraSticJNI.waitScreen()
                if (!glThreadRunning.get() || !surfaceReady.get()) break

                if (layoutDirty) {
                    GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vbo)
                    rebuildVertices()
                    layoutDirty = false
                }
                if (smoothFilter != lastSmooth) {
                    GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texTop); applyTexFilter()
                    GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texBottom); applyTexFilter()
                    lastSmooth = smoothFilter
                }
                // 滤镜热切换检查点（设置页改滤镜 → 下一帧重载，与原版一致）
                fxCheck(eng)

                // 表面尺寸变化：fx 需要重新 fxSetup（视口 + pass 尺寸链）
                if (fxUsable && (surfaceW != lastFxW || surfaceH != lastFxH)) {
                    lastFxW = surfaceW; lastFxH = surfaceH
                    fxSetupNow()
                }

                GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
                GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
                GLES20.glDisable(GLES20.GL_BLEND)

                if (fxUsable) {
                    // 滤镜路径：原生按 pass 链自用程序/属性指针/视口，
                    // 只要求共享 VBO 已绑定、纹理单元 0 可用。
                    GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vbo)
                    GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
                    val s = screenPixelSizes()
                    try {
                        DraSticJNI.fxRender(texTop, texBottom, 0, 6, 18, s[0], s[1], s[2], s[3], false)
                    } catch (t: Throwable) {
                        Log.w(TAG, "fxRender failed — fallback to plain", t)
                        fxUsable = false
                        drawPlain(eng)
                    }
                } else {
                    drawPlain(eng)
                }
                eng.notifyGlFrame()
            } else {
                // 模拟批次进行中（快进等）：不重绘不清屏，保持上一帧画面
                Thread.sleep(4)
            }

            if (!swap()) break
        }
    }

    /** 无滤镜直绘路径：本前端程序 + 非交错属性指针（offset 0 / 2208），
     *  原生 renderFrame 负责帧池上传 + glDrawArrays（复用当前 GL 状态）。 */
    private fun drawPlain(eng: DraSticEngine) {
        GLES20.glViewport(0, 0, surfaceW.coerceAtLeast(1), surfaceH.coerceAtLeast(1))
        GLES20.glUseProgram(prog)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vbo)
        GLES20.glEnableVertexAttribArray(aPosLoc)
        GLES20.glVertexAttribPointer(aPosLoc, 2, GLES20.GL_FLOAT, false, 8, 0)
        GLES20.glEnableVertexAttribArray(aUvLoc)
        GLES20.glVertexAttribPointer(aUvLoc, 2, GLES20.GL_FLOAT, false, 8, VBO_UV_OFFSET)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        try {
            DraSticJNI.renderFrame(texTop, texBottom, false)
        } catch (t: Throwable) {
            Log.w(TAG, "renderFrame failed", t)
        }
    }

    private fun swap(): Boolean {
        if (!EGL14.eglSwapBuffers(eglDisplay, eglSurface)) {
            val err = EGL14.eglGetError()
            if (err == EGL14.EGL_BAD_NATIVE_WINDOW || err == EGL14.EGL_BAD_SURFACE ||
                err == EGL14.EGL_NOT_INITIALIZED || err == EGL14.EGL_CONTEXT_LOST) {
                return false // 显示面已销毁 / 上下文丢失 —— 退出循环
            }
        }
        return true
    }

    // ------------------------------------------------------------------
    // 顶点布局（原版契约：顶点序 = 三角 1 (l,t)(l,b)(r,b) + 三角 2 (l,t)(r,b)(r,t)）
    // ------------------------------------------------------------------

    /** 把一个 6 顶点四边形写入 [out] 的 [base] float 处；rect=null 折叠为零面积。 */
    private fun putQuad(out: FloatArray, base: Int, rect: FloatArray?) {
        if (rect == null) {
            for (i in 0 until 6) {
                out[base + i * 2] = -1f
                out[base + i * 2 + 1] = -1f
            }
            return
        }
        // 归一化 → 裁剪空间（GLES Y 轴向上）
        val l = rect[0] * 2f - 1f
        val r = rect[2] * 2f - 1f
        val t = 1f - rect[1] * 2f
        val b = 1f - rect[3] * 2f
        // 原版顶点序（DraSticGlView$j l() / c()）：
        //   三角 1 = (l,t) (l,b) (r,b)
        //   三角 2 = (l,t) (r,b) (r,t)
        val px = floatArrayOf(l, l, r, l, r, r)
        val py = floatArrayOf(t, b, b, t, b, t)
        for (i in 0 until 6) {
            out[base + i * 2] = px[i]
            out[base + i * 2 + 1] = py[i]
        }
    }

    /** 当前两屏归一化矩形（top, bottom；不可见 = null）。 */
    private fun currentRects(): Pair<FloatArray?, FloatArray?> {
        val ct = customTopRect
        val cb = customBottomRect
        if (ct != null && cb != null) return ct to cb
        val (t, b) = ndsLayoutRects(screenLayout)
        val top = if (t[2] > t[0] && t[3] > t[1]) t else null
        val bottom = if (b[2] > b[0] && b[3] > b[1]) b else null
        return top to bottom
    }

    /** 依据 screenLayout / 自定义矩形重建两屏位置四边形并上传 VBO。 */
    private fun rebuildVertices() {
        val (top, bottom) = currentRects()
        putQuad(posData, 0, top)
        putQuad(posData, QUAD_FLOATS, bottom)
        posBuf.clear()
        posBuf.put(posData).flip()
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vbo)
        GLES20.glBufferSubData(GLES20.GL_ARRAY_BUFFER, OFF_POS_TOP, QUAD_BYTES * 2, posBuf)
    }

    /** 两屏目标矩形的像素尺寸（fxRender 的 u_target_size；不可见 = 0）。 */
    private fun screenPixelSizes(): IntArray {
        val (top, bottom) = currentRects()
        val w = surfaceW.coerceAtLeast(1).toFloat()
        val h = surfaceH.coerceAtLeast(1).toFloat()
        val wa = if (top != null) ((top[2] - top[0]) * w).roundToInt().coerceAtLeast(0) else 0
        val ha = if (top != null) ((top[3] - top[1]) * h).roundToInt().coerceAtLeast(0) else 0
        val wb = if (bottom != null) ((bottom[2] - bottom[0]) * w).roundToInt().coerceAtLeast(0) else 0
        val hb = if (bottom != null) ((bottom[3] - bottom[1]) * h).roundToInt().coerceAtLeast(0) else 0
        return intArrayOf(wa, ha, wb, hb)
    }

    // ------------------------------------------------------------------
    // 触摸 / 按键（复刻 NdsDualScreenView 的输入模型）
    // ------------------------------------------------------------------

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (uiBlocked) return false
        val eng = engine ?: return false
        if (!eng.isLoaded) return false

        val viewW = width.coerceAtLeast(1).toFloat()
        val viewH = height.coerceAtLeast(1).toFloat()

        return when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                val bottom = currentBottomRect() ?: return true
                val bl = bottom[0] * viewW
                val bt = bottom[1] * viewH
                val br = bottom[2] * viewW
                val bb = bottom[3] * viewH
                if (event.x >= bl && event.x <= br && event.y >= bt && event.y <= bb) {
                    val t = ((event.x - bl) / (br - bl).coerceAtLeast(1e-6f)).coerceIn(0f, 1f)
                    val s = ((event.y - bt) / (bb - bt).coerceAtLeast(1e-6f)).coerceIn(0f, 1f)
                    val px = (t * 255.5f).toInt().coerceIn(0, 255)
                    val py = (s * 191.5f).toInt().coerceIn(0, 191)
                    eng.setTouchInputDirect(px, py, true)
                } else {
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

    /** 当前下屏归一化矩形（触摸热区）。 */
    fun currentBottomRect(): FloatArray? {
        customBottomRect?.let { return it }
        val (_, b) = ndsLayoutRects(screenLayout)
        return if (b[2] > b[0] && b[3] > b[1]) b else null
    }

    /** 挂到 SurfaceView 的物理按键路由（与 NdsDualScreenView 同构）。 */
    fun attachKeyListener(
        uiBlockedGetter: () -> Boolean,
        onKey: (keyCode: Int, event: KeyEvent) -> Boolean
    ) {
        isFocusable = true
        isFocusableInTouchMode = true
        setOnKeyListener { _, keyCode, event ->
            if (uiBlockedGetter()) false else onKey(keyCode, event)
        }
    }
}
