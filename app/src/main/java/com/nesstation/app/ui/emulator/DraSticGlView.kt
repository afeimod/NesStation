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

/**
 * DraStic（激烈）核心的 OpenGL 显示视图 —— 复刻原版 App 的显示路径。
 *
 * ## 原生渲染契约（libdrastic_arm64.so 逐指令反汇编验证）
 * 帧池：双缓冲，各 0x180000 字节（两屏 × 0xC0000 = 512×384×4，按 2x 容量
 * 预分配）；缓冲指针存 video_state+0/+8，当前写入档存 +0x958。
 * 原生 renderFrame(0x1ceac) 每次调用的行为：
 *  1. 池互斥锁（+0x98c）下快取【另一档】缓冲指针与两屏分辨率档
 *     （+0x968 双字，由 config bit41 经 setResolution 写入：
 *     0→256×192，1→512×384）；
 *  2. 屏 A：glBindTexture(tex1) → 脏标记（+0x988 bit0）置位时
 *     glTexSubImage2D 上传（宽=(scale+1)×256、高=(scale+1)×192；
 *     32 位模式 format=GL_RGBA(0x1908)/type=GL_UNSIGNED_BYTE(0x1401)，
 *     由 applyConfig 的 bit23 路径写入 +0x960/+0x964）；
 *  3. 【glDrawArrays(GL_TRIANGLES, first=0,  count=6)】绘制屏 A；
 *  4. tex2≠0 时同样流程绘制屏 B：first=6, count=6；结束后清脏字节。
 *
 * 原生只做"上传 + 按当前绑定的顶点属性绘制"，不绑定程序/不设顶点指针
 * —— 着色器、VBO、视口全部由调用方（本视图）准备，与原版 App 的
 * GLSurfaceView 调用模型一致。
 *
 * ### 顶点布局（契约核心）
 * 原生绘制图元是 GL_TRIANGLES（w0=4，非 TRIANGLE_STRIP）：
 *  - 顶点 0..5 = 屏 A：两个三角形拼成矩形
 *    v0=(l,t) v1=(r,t) v2=(l,b) | v3=(r,t) v4=(r,b) v5=(l,b)
 *  - 顶点 6..11 = 屏 B：同样两个三角形
 * 位置随屏幕布局（上下/下上/左右/右左/单屏/自定义矩形）实时更新。
 *
 * ### 帧同步
 * 帧循环不调 waitScreen：eglSwapBuffers 的垂直同步提供自然节流，
 * renderFrame 的脏标记机制保证只上传新帧（上传后清标记，无新帧时仅
 * 重绘既有纹理）——与原版 App 的 onDrawFrame 模型完全一致。
 * 引擎侧的渲染消费线程继续 waitScreen 维持就绪握手。
 *
 * 触摸输入复刻 NdsDualScreenView：下屏矩形内线性映射为 DS 触点
 * (0..255, 0..191)，矩形外释放。
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

    /** 12 顶点 × (pos2 + uv2)，GL 线程内更新。 */
    private val vertexData = FloatArray(12 * 4)
    private val vertexBuf: FloatBuffer = ByteBuffer
        .allocateDirect(12 * 4 * 4)
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
        // 着色器程序
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

        // 顶点缓冲（12 顶点双四边形）
        val vbos = IntArray(1)
        GLES20.glGenBuffers(1, vbos, 0)
        vbo = vbos[0]
        vertexBuf.clear()
        vertexBuf.put(vertexData).flip()
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vbo)
        GLES20.glBufferData(
            GLES20.GL_ARRAY_BUFFER, vertexData.size * 4, vertexBuf, GLES20.GL_DYNAMIC_DRAW
        )

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

    /** 按引擎会话快照（activeHdRender）分配两张屏幕纹理。
     * 恒为 32 位 GL_RGBA/UNSIGNED_BYTE（与原生 32 位上传常量一致；
     * 16 位格式不提供 —— 其 REV 类型组合非 ES 核心合法）。 */
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

    /** 平滑/最近邻纹理过滤（NPOT 纹理只允许非 mipmap 过滤）。 */
    private fun applyTexFilter() {
        val f = if (smoothFilter) GLES20.GL_LINEAR else GLES20.GL_NEAREST
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, f)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, f)
    }

    // ------------------------------------------------------------------
    // 帧循环
    // ------------------------------------------------------------------

    private fun glLoop() {
        var sessionLoaded = false
        var lastSmooth = true
        // 修复（高清渲染热切换后画面失效）：核心的 bit41（_Hires3D）可在
        // 游戏运行中经 applyConfig 热更新，原生帧池随之在 256×192 / 512×384
        // 两档间切换（setResolution 写 +0x968 分辨率档）。旧实现只在会话
        // 首帧分配一次纹理 —— 档位切换后 glTexSubImage2D 仍按新尺寸向旧
        // 纹理上传，宽/高超出时返回 GL_INVALID_OPERATION 且脏标记照常被
        // 清掉，之后纹理内容永远不再更新（画面停在切换前的最后一帧 /
        // 黑屏）。现在跟踪引擎的 HD 快照，档位变化即重建纹理。
        var lastTexHd = false

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

            // 会话首帧：按引擎在 loadRom 时的快照分配纹理，并接管帧消费
            // （渲染消费线程停止 CPU 帧拷贝）。
            if (!sessionLoaded) {
                allocTextures(eng)
                lastTexHd = eng.activeHdRender
                eng.glDisplayActive = true
                sessionLoaded = true
                lastSmooth = smoothFilter
                layoutDirty = true
            }
            // 高清渲染档位热切换：重建两张屏幕纹理到新分辨率
            if (eng.activeHdRender != lastTexHd) {
                allocTextures(eng)
                lastTexHd = eng.activeHdRender
                layoutDirty = true
            }
            if (smoothFilter != lastSmooth) {
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texTop); applyTexFilter()
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texBottom); applyTexFilter()
                lastSmooth = smoothFilter
            }

            GLES20.glViewport(0, 0, surfaceW.coerceAtLeast(1), surfaceH.coerceAtLeast(1))

            if (layoutDirty) {
                rebuildVertices()
                layoutDirty = false
            }

            // 绑定程序 + 顶点属性（原生 renderFrame 的 glDrawArrays 复用此状态）
            GLES20.glUseProgram(prog)
            GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vbo)
            GLES20.glEnableVertexAttribArray(aPosLoc)
            GLES20.glVertexAttribPointer(aPosLoc, 2, GLES20.GL_FLOAT, false, 16, 0)
            GLES20.glEnableVertexAttribArray(aUvLoc)
            GLES20.glVertexAttribPointer(aUvLoc, 2, GLES20.GL_FLOAT, false, 16, 8)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)

            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

            // 原生上传 + 绘制（互斥锁保护帧池；脏标记控制上传频度）
            try {
                DraSticJNI.renderFrame(texTop, texBottom, false)
            } catch (t: Throwable) {
                Log.w(TAG, "renderFrame failed", t)
            }

            if (!swap()) break
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
    // 顶点布局（12 顶点：上屏 0..5，下屏 6..11）
    // ------------------------------------------------------------------

    /** 依据 screenLayout / 自定义矩形重建顶点数据并上传 VBO。 */
    private fun rebuildVertices() {
        val topRect: FloatArray?
        val bottomRect: FloatArray?
        val ct = customTopRect
        val cb = customBottomRect
        if (ct != null && cb != null) {
            topRect = ct
            bottomRect = cb
        } else {
            val (t, b) = ndsLayoutRects(screenLayout)
            topRect = if (t[2] > t[0] && t[3] > t[1]) t else null
            bottomRect = if (b[2] > b[0] && b[3] > b[1]) b else null
        }

        putQuad(0, topRect)
        putQuad(6, bottomRect)

        vertexBuf.clear()
        vertexBuf.put(vertexData).flip()
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vbo)
        GLES20.glBufferSubData(GLES20.GL_ARRAY_BUFFER, 0, vertexData.size * 4, vertexBuf)
    }

    /** 写入一个 6 顶点四边形（GL_TRIANGLES：两个三角形拼成矩形）。
     * 顶点序与原生 draw 调用严格对应：first=0/6、count=6、图元 GL_TRIANGLES
     * （原生 renderFrame 两次 glDrawArrays 均为 w0=4=GL_TRIANGLES）。 */
    private fun putQuad(base: Int, rect: FloatArray?) {
        if (rect == null) {
            // 无此屏：全部折叠到同一点（零面积三角形，不产生像素）
            for (i in 0 until 6) {
                vertexData[(base + i) * 4] = -1f
                vertexData[(base + i) * 4 + 1] = -1f
                vertexData[(base + i) * 4 + 2] = 0.5f
                vertexData[(base + i) * 4 + 3] = 0.5f
            }
            return
        }
        // 归一化 → 裁剪空间（GLES Y 轴向上）
        val l = rect[0] * 2f - 1f
        val r = rect[2] * 2f - 1f
        val t = 1f - rect[1] * 2f
        val b = 1f - rect[3] * 2f
        // GL_TRIANGLES 两个三角形拼成矩形（与原生 first/count 契约一致）：
        //   三角 1 = v0(l,t) v1(r,t) v2(l,b)
        //   三角 2 = v3(r,t) v4(r,b) v5(l,b)
        val px = floatArrayOf(l, r, l, r, r, l)
        val py = floatArrayOf(t, t, b, t, b, b)
        // UV：纹理 v=0 对应帧池数据的第 0 行（画面顶部）→ 屏幕矩形顶部
        val u = floatArrayOf(0f, 1f, 0f, 1f, 1f, 0f)
        val v = floatArrayOf(0f, 0f, 1f, 0f, 1f, 1f)
        for (i in 0 until 6) {
            vertexData[(base + i) * 4] = px[i]
            vertexData[(base + i) * 4 + 1] = py[i]
            vertexData[(base + i) * 4 + 2] = u[i]
            vertexData[(base + i) * 4 + 3] = v[i]
        }
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
        val (t, b) = ndsLayoutRects(screenLayout)
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
