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
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * PS2（ARMSX2）仿电视机（CRT 弧面）显示视图。
 *
 * ## 为什么 PS2 需要专门一条路径（"ps2核心画面没生效电视滤镜凸起"修复）
 *
 * PS2 引擎是 push 模型：ARMSX2 自带 VM 线程 + GS 渲染线程，直接把画面画到
 * 传入的 [android.view.Surface] 上，且没有帧缓冲回读 —— 所以既不能用
 * [TvCurvedGameView]（需要引擎无 Surface 时回写 frameBuffer 供拉帧），
 * 也不能只靠 [FilterOverlay] 平面叠加（没有弧面凸起）。此前 PS2 被排除在
 * 弧面路径外，选「仿电视机」只有平面扫描线，没有凸起。
 *
 * ## 本视图的做法：SurfaceTexture 中转 + 自有 GL 弧面通道
 *
 *   ARMSX2 ──渲染──▶ [SurfaceTexture]（离屏消费者，对本视图是纹理源）
 *                        │ updateTexImage()
 *                        ▼
 *   本视图 EGL/GL 线程 ──桶形弧面着色器──▶ SurfaceView 上屏
 *
 * 1. GL 线程创建 OES 外部纹理 + [SurfaceTexture]，包装成 [Surface] 交给
 *    引擎（`engine.setSurface` + `onSurfaceChanged`）—— 引擎看到的仍是
 *    一个带正尺寸的真实 ANativeWindow，MTGS::UpdateDisplayWindow 契约不变；
 * 2. 每收到一帧（onFrameAvailable 信号）就 updateTexImage 取为纹理，
 *    用与 [TvCurvedGameView] / J2ME GL 路径同款的 nsCurve 桶形映射 +
 *    全出血预缩放（无大黑边）绘制到本 SurfaceView；
 * 3. 叠加屏幕空间扫描线（与 NdsFilterPatterns.createScanlinePattern 同
 *    4px 周期、55% 暗行）、径向暗角、上部玻璃高光 —— 与其他核心的
 *    「仿电视机」观感统一。
 *
 * EGL/着色器/线程骨架复用 [DraSticGlView] 的成熟模式；EGL 初始化失败时
 * 回调 [onGlFailed]，UI 回退到普通 SurfaceView + 平面 FilterOverlay 路径
 * （画面可能没有凸起，但绝不黑屏）。
 *
 * 性能：每帧一次 updateTexImage + 一次全屏四边形 + swapBuffers，PS2 场景
 * 下 GPU 占用可忽略；无帧时不绘制（阻塞等信号，不做忙轮询）。
 */
class Ps2CurvedView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : SurfaceView(context, attrs, defStyleAttr), SurfaceHolder.Callback {

    /** 渲染引擎（帧源 + Surface 接收方），由 EmulatorScreen 注入。 */
    @Volatile
    var engine: EmulatorEngine? = null

    /**
     * UI 被菜单/设置等遮挡标志。与 TvCurvedGameView 保持对称的注入接口；
     * PS2 是核心自主推帧，遮挡时引擎照常渲染，我们照常绘制（菜单是
     * Compose 覆盖层），因此本标志不改变绘制行为。
     */
    @Volatile
    var uiBlocked: Boolean = false

    /** EGL 初始化失败回调（UI 收到后回退普通 SurfaceView 路径）。 */
    @Volatile
    var onGlFailed: (() -> Unit)? = null

    private companion object {
        const val TAG = "Ps2CurvedView"
        // 与 J2ME GL 路径 nsCurve 的 vec2(5.4, 3.6) 常量一致
        const val CURVE_X = 5.4f
        const val CURVE_Y = 3.6f
        // 全出血预缩放（nsCurve 内置，与 TvCurvedView/J2ME 路径一致）
        const val FULLBLEED_X = 1.0298f
        const val FULLBLEED_Y = 1.0727f
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
    private var prog = 0
    private var oesTex = 0
    private var surfaceTexture: SurfaceTexture? = null
    private var coreSurface: Surface? = null
    /** 当前中转缓冲尺寸（SurfaceTexture 无尺寸查询 API，自跟踪）。 */
    private var bufferW = 0
    private var bufferH = 0

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

    private val vsSrc = """
        attribute vec4 aPos;
        varying vec2 vUV;
        void main() {
            vUV = aPos.zw;
            gl_Position = vec4(aPos.xy, 0.0, 1.0);
        }
    """.trimIndent()

    // 桶形弧面 + 全出血预缩放 + 扫描线 + 暗角 + 玻璃高光。
    // 几何与 TvCurvedView / J2ME GL 路径（nsCurve 5.4/3.6 + 1.0298/1.0727
    // 预缩放）严格一致：纹理四角贴齐屏幕四角、边中点鼓出被裁掉 ——
    // 画面铺满、凸起保留、无大黑边（不遮挡扫描线遮罩）。
    private val fsSrc = """
        #extension GL_OES_EGL_image_external : require
        precision mediump float;
        uniform samplerExternalOES uTex;
        varying vec2 vUV;

        vec2 nsCurve(vec2 tc) {
            vec2 c = tc * 2.0 - 1.0;
            c /= vec2(${FULLBLEED_X}, ${FULLBLEED_Y});
            vec2 off = abs(c.yx) / vec2(${CURVE_X}, ${CURVE_Y});
            c = c + c * off * off;
            return c * 0.5 + 0.5;
        }

        // 四角/四边玻璃暗影（圆角矩形 SDF）—— 与 J2ME GL 路径
        // J2meFilterShaders.TV_GLSL_HELPERS 的 nsCornerMask 同参数：
        // ★ 灰边收窄：SDF 内缩 0.085 → 0.035，边缘暗带约 6.5% → 3.5%
        // 全幅宽度，四角按圆弧过渡（无黑边不遮挡遮罩）
        float nsCornerMask(vec2 tc) {
            vec2 p = abs(tc * 2.0 - 1.0);
            vec2 corner = vec2(0.965, 0.955) - 0.035;
            float dist = length(max(p - corner, vec2(0.0))) - 0.035;
            return 1.0 - 0.40 * smoothstep(-0.008, 0.016, dist);
        }

        void main() {
            // 弧面采样（恒在 [0,1] 内，clamp 仅防浮点误差 —— 无黑边）
            vec2 cuv = clamp(nsCurve(vUV), 0.0, 1.0);
            vec3 res = texture2D(uTex, cuv).rgb;

            // 扫描线：屏幕空间 4px 周期（3 行透明 + 1 行 55% 暗），
            // 与 NdsFilterPatterns.createScanlinePattern 同密度
            float f = fract(gl_FragCoord.y / 4.0);
            res *= 1.0 - 0.55 * smoothstep(0.70, 0.88, f);

            // 径向暗角（渐变玻璃观感，非不透明黑块）
            // ★ 灰边收窄：与 J2ME 路径 nsVignette 同步 —— 只在外缘 15%
            // （半幅）内渐变到 32% 暗，画面中心不受影响
            vec2 p = vUV * 2.0 - 1.0;
            vec2 ap = abs(p);
            res *= 1.0 - 0.32 * smoothstep(0.85, 1.0, max(ap.x, ap.y));

            // 四角/四边玻璃暗影（与 TvCurvedGameView / FilterOverlay 一致）
            res *= nsCornerMask(vUV);

            // 上部玻璃高光（与其他核心 TV 滤镜同强度）
            float gloss = 0.055 * smoothstep(0.42, 0.02, abs(vUV.y - 0.20));
            res += gloss;

            gl_FragColor = vec4(clamp(res, 0.0, 1.0), 1.0);
        }
    """.trimIndent()

    init {
        holder.setFormat(android.graphics.PixelFormat.RGBX_8888)
        holder.addCallback(this)
        // 与 DraSticGlView 一致：不与系统 UI 混合时更高效
        setZOrderMediaOverlay(false)
        // 全屏四边形顶点：(-1,-1)u(0,1) → (1,-1)u(1,1) → (-1,1)u(0,0) → (1,1)u(1,0)
        quadBuf.put(floatArrayOf(
            -1f, -1f, 0f, 1f,
             1f, -1f, 1f, 1f,
            -1f,  1f, 0f, 0f,
             1f,  1f, 1f, 0f
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
                    Log.e(TAG, "EGL init failed — fallback to flat overlay path")
                    view.post { onGlFailed?.invoke() }
                    return@Thread
                }
                if (!glInitResources()) {
                    Log.e(TAG, "GL resources init failed — fallback to flat overlay path")
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
        }, "ps2-curved-gl").also { it.isDaemon = true; it.start() }
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
        val vs = compileShader(GLES20.GL_VERTEX_SHADER, vsSrc) ?: return false
        val fs = compileShader(GLES20.GL_FRAGMENT_SHADER, fsSrc) ?: run {
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
        GLES20.glUseProgram(prog)
        val uTex = GLES20.glGetUniformLocation(prog, "uTex")
        GLES20.glUniform1i(uTex, 0)

        // OES 外部纹理 + SurfaceTexture（PS2 引擎的渲染目标）
        val texs = IntArray(1)
        GLES20.glGenTextures(1, texs, 0)
        oesTex = texs[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTex)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)

        val st = SurfaceTexture(oesTex)
        // 帧到达回调注册到主线程 Handler（GL 线程无 Looper；回调仅向
        // 容量 1 队列 offer 一个信号，主线程开销可忽略）
        st.setOnFrameAvailableListener({
            frameSignal.offer(true)
        }, Handler(Looper.getMainLooper()))
        surfaceTexture = st
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
        if (prog != 0) {
            try { GLES20.glDeleteProgram(prog) } catch (_: Throwable) {}
            prog = 0
        }
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
            Log.i(TAG, "PS2 core surface attached: ${w}x${h}")
        }
    }

    private fun glLoop() {
        val st = surfaceTexture ?: return
        // 首帧前先给引擎接上 Surface（带正尺寸，满足 MTGS 契约）
        ensureCoreSurface()

        while (!glLoopStopped) {
            // 无帧时阻塞等待（100ms 超时轮询 stop 标志），绝不忙等
            try { frameSignal.poll(100, TimeUnit.MILLISECONDS) } catch (_: InterruptedException) { break }

            val w = surfaceW
            val h = surfaceH
            if (w <= 0 || h <= 0) continue

            // 尺寸变化（surfaceChanged）：中转缓冲跟随视图尺寸
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

            GLES20.glViewport(0, 0, w, h)
            GLES20.glClearColor(0f, 0f, 0f, 1f)
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
            GLES20.glUseProgram(prog)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTex)
            val aPos = GLES20.glGetAttribLocation(prog, "aPos")
            quadBuf.position(0)
            GLES20.glEnableVertexAttribArray(aPos)
            GLES20.glVertexAttribPointer(aPos, 4, GLES20.GL_FLOAT, false, 0, quadBuf)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
            GLES20.glDisableVertexAttribArray(aPos)
            EGL14.eglSwapBuffers(eglDisplay, eglSurface)
        }
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
}
