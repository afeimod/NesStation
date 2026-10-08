package com.nesstation.app.ui.emulator

import android.content.Context
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.util.Log
import java.io.File
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * ★★★★ MMJ 后处理着色器 GL 呈现层（v3 重写，根治"mmj 依旧没有生效全局滤镜
 *   xbr 和 hqx，以及自带的所有滤镜"）★★★★
 *
 * 【原版管线（Citra_MMJ_20250220.apk 反编译全文在案，逐行复刻）】：
 *   MMJ 的后处理着色器由【Java GL 层】消费：核心照常直绘（原生自建上下文），
 *   Java 层 GLSurfaceView.Renderer（原版 c1/W 类）每帧调
 *   NativeLibrary.getScreenTexture() 取 [texId, w, h, fmt]，
 *   用「直通 program（RGB/BGR 两种）+ .glsl 后处理源码注入」画全屏 quad。
 *   getScreenTexture JNI（0x26dae0）在【调用方 GL 线程的上下文】里导出
 *   最新帧纹理（呈现模式 0 / 原版默认路径）→ 跨上下文安全。
 *
 * 【v2 黑屏的真正根源（本轮反汇编定论，v2 误判为"跨上下文无效"）】：
 *   v2 时代同时存在「着色器激活强制 screen_presentation_mode 0→1」——
 *   模式 1 走共享上下文导出（返回核心内部纹理 ID，在叠加层上下文无效）
 *   → 采样全黑；且 v2 的 onDrawFrame【先 glClear 再早退】（不透明清屏
 *   盖住底层直绘）→ 黑帧。本轮两处根治：
 *     ① 引擎侧删除呈现模式强制（见 CitraMmjEngine）—— 永远跑默认 0；
 *     ② 本层半透明 EGL 配置 + 不绘制时清 alpha=0（透出底层直绘）+
 *        glIsTexture 有效性守卫 + 连续失败自动停用 —— 黑屏在结构上不可能。
 *
 * 【与原版 c1/W 的对应关系】：
 *   - GLSL 常量逐字取自反编译的 W 构造器（顶点 / RGB-BGR 直通 / FX 头 /
 *     Sample 辅助 RGB-BGR 两变体）；
 *   - 编译链（#version 320 es 头 + glGetShaderInfoLog）= W.c/d；
 *   - program 构造（FX 激活时拼 头+辅助+用户源码；失败回落直通）= W.b；
 *   - 纹理绑定（fmt==1 → BGR program）= W.f；宽高比 letterbox = W.a；
 *   - 释放时 releaseScreenTexture()（复位核心导出标志）= W.e。
 *   与原版差异（均为增强）：float 精度宽高比；resolution/frame_count
 *   uniform 每帧正确赋值（原版从未赋值恒 0）；线程安全切换；失败自愈。
 *
 * 【★★★ 本轮根治（"全局 xbr/hqx 与后处理着色器全失效"的真正根因）★★★】
 *   getScreenTexture JNI（0x26dae0）反汇编实证：其数据源是核心内部的
 *   【呈现提供器全局对象】（adrp 0x77c000 + 0x688 读指针）—— 该对象在
 *   Run() 引导后期（渲染器初始化）才创建；引导期每次调用直接返回全零
 *   int[4]（cbz x0 → 跳过虚调用，结果保持 {0,0}）。旧实现的失败计数
 *   FAIL_GIVE_UP=60（60fps ≈ 1 秒）在【核心还在引导的 1 秒内就永久停用】
 *   —— 叠加层被移除、直接绘画面裸奔 —— 所有滤镜（全局 xbr/hqx 映射 +
 *   自带后处理着色器）100% 复现失效。本轮：
 *     ① 首次成功导出之前【永不计数】—— 引导期无条件透出直绘画面；
 *     ② 首次成功后失败计数才生效（阈值提高到 600 帧 ≈ 10s，只防
 *        "曾经能用、之后坏了"的真实异常），任何一次成功即清零。
 *   另：GLSL 编译增加 #version 320 es → 310 es 回退（低版本 GLES 3.1
 *   驱动上 320 头会编译失败回落直通 = "滤镜像没生效"的第二根因）。
 */
class MmjGlView(context: Context) : GLSurfaceView(context) {

    private val fxRenderer = Renderer()
    /** 连续取帧失败自动停用后通知 UI 隐藏本视图（主线程回调）。 */
    @Volatile private var onGiveUp: (() -> Unit)? = null

    private companion object {
        /** ★ 本轮：首次成功之后才计数的连续失败上限（60fps ≈ 10 秒）。
         *  引导期（首次成功前）永不计数 —— 见类头注释的根因分析。 */
        private const val FAIL_GIVE_UP = 600

        /** 失败日志节流（每 N 次失败打一条，避免 logcat 刷屏）。 */
        private const val FAIL_LOG_EVERY = 120
    }

    init {
        setEGLContextClientVersion(3)
        // ★★★ 半透明 EGL 配置：不绘制时透出底层直绘画面 —— 黑屏在结构上不可能
        setEGLConfigChooser(8, 8, 8, 8, 0, 0)
        holder.setFormat(android.graphics.PixelFormat.TRANSLUCENT)
        // 叠加在核心直绘 SurfaceView 之上（仍低于 Compose 手柄层 → 手柄可见可点）
        setZOrderMediaOverlay(true)
        setRenderer(fxRenderer)
        renderMode = RENDERMODE_CONTINUOUSLY
    }

    /**
     * 设置后处理着色器名（主线程调用）。null/空 = 直通（无后处理）。
     * 变化时渲染线程重建 program（原版 e() + 下次 b() 的重建语义）。
     */
    fun setPostFxShader(name: String?) {
        queueEvent { fxRenderer.setShaderName(name) }
    }

    /** 暂停/恢复后处理层的绘制（Activity 生命周期同步）。 */
    fun setFxPaused(paused: Boolean) {
        fxRenderer.paused = paused
    }

    /** 注册"连续取帧失败自动停用"回调（主线程调用）。 */
    fun setOnGiveUp(listener: (() -> Unit)?) {
        onGiveUp = listener
    }

    /** 释放 GL 资源并复位核心导出标志（GL 线程内执行）。 */
    fun releaseFx() {
        queueEvent { fxRenderer.release() }
    }

    private inner class Renderer : GLSurfaceView.Renderer {

        // ---- c1/W 构造器中的 GLSL 常量（逐字复刻）----
        private val vsSource = (
                "out vec2 frag_tex_coord;\n" +
                "layout(location=0) uniform vec2 size;\n" +
                "const vec2 vertices[4] = vec2[4](vec2(-1, -1), vec2(1, -1), vec2(-1, 1), vec2(1, 1));\n" +
                "void main() {\n" +
                "    gl_Position = vec4(vertices[gl_VertexID] * size, 0.0f, 1.0f);\n" +
                "    frag_tex_coord = (vertices[gl_VertexID] / 2.0 + 0.5) * mat2(0, 1, -1, 0);\n" +
                "    frag_tex_coord.y = 1.0f - frag_tex_coord.y;\n" +
                "}")

        private val fsPassthroughRgb = (
                "in vec2 frag_tex_coord;\n" +
                "out vec4 frag_color;\n" +
                "layout(binding=0) uniform sampler2D color_texture;\n" +
                "layout(location=1) uniform vec2 resolution;\n" +
                "layout(location=2) uniform uint frame_count;\n" +
                "void main() {\n" +
                "    vec4 color = texture(color_texture, frag_tex_coord);\n" +
                "    frag_color = vec4(color.rgb, 1.0f);\n" +
                "}")

        private val fsPassthroughBgr = (
                "in vec2 frag_tex_coord;\n" +
                "out vec4 frag_color;\n" +
                "layout(binding=0) uniform sampler2D color_texture;\n" +
                "layout(location=1) uniform vec2 resolution;\n" +
                "layout(location=2) uniform uint frame_count;\n" +
                "void main() {\n" +
                "    vec4 color = texture(color_texture, frag_tex_coord);\n" +
                "    frag_color = vec4(color.bgr, 1.0f);\n" +
                "}")

        /** FX 公共头（c1/W 构造器第 4 个字符串）：Hyllian/Sedi 类着色器兼容层。 */
        private val fxHeader = (
                "#define float2 vec2\n#define float3 vec3\n#define float4 vec4\n" +
                "#define uint2 uvec2\n#define uint3 uvec3\n#define uint4 uvec4\n" +
                "#define int2 ivec2\n#define int3 ivec3\n#define int4 ivec4\n\n" +
                "in float2 frag_tex_coord;\nout float4 output_color;\n\n" +
                "layout(binding=0) uniform sampler2D color_texture;\n" +
                "layout(location=1) uniform float2 resolution;\n" +
                "layout(location=2) uniform uint frame_count;\n\n" +
                "int2 SampleSize() { return textureSize(color_texture, 0); }\n" +
                "float2 GetResolution() { return float2(SampleSize()); }\n" +
                "float2 GetInvResolution() { return 1.0 / GetResolution(); }\n" +
                "float2 GetOnScreenSize() { return resolution; }\n" +
                "float2 GetCoordinates() { return frag_tex_coord; }\n" +
                "uint GetTime() { return frame_count; }\n" +
                "void SetOutput(float4 color) { output_color = color; }")

        /** Sample 辅助（RGB 变体）。 */
        private val fxHelpersRgb = (
                "float4 Sample() { return texture(color_texture, frag_tex_coord); }\n" +
                "float4 SampleLocation(float2 location) { return texture(color_texture, location); }\n" +
                "float4 SampleFetch(int2 location) { return texelFetch(color_texture, location, 0); }\n")

        /** Sample 辅助（BGR 变体）。 */
        private val fxHelpersBgr = (
                "float4 Sample() { return texture(color_texture, frag_tex_coord).bgra; }\n" +
                "float4 SampleLocation(float2 location) { return texture(color_texture, location).bgra; }\n" +
                "float4 SampleFetch(int2 location) { return texelFetch(color_texture, location, 0).bgra; }\n")

        // ---- 状态（对应 c1/W 字段）----
        private var progRgb = 0        // W.a：RGB 直通/后处理 program
        private var progBgr = 0        // W.b：BGR 直通/后处理 program
        private var curProgram = 0     // W.h
        private var curFmt = -1        // W.i
        private var lastTexId = -1     // W.g
        private var lastW = 0f         // W.e
        private var lastH = 0f         // W.f
        private var viewW = 1          // W.c
        private var viewH = 1          // W.d
        private var frameCount = 0L
        @Volatile private var shaderName: String? = null
        @Volatile var paused = false

        /** 连续取帧/纹理失败计数（★ 首次成功之后才开始计数；成功即清零）。 */
        private var failStreak = 0
        private var givenUp = false
        /** ★ 本轮：是否已成功导出过至少一帧（成功前永不 give-up）。 */
        private var everSucceeded = false

        /** GL 线程内执行（setPostFxShader 经 queueEvent 转发）。 */
        fun setShaderName(name: String?) {
            val norm = name?.trim()?.takeIf { it.isNotEmpty() && it != "(off)" }
            if (norm == shaderName) return
            shaderName = norm
            // 着色器变化 → 丢弃两个缓存 program（GL 线程里重建，原版 e() 语义）
            if (progRgb != 0) { GLES20.glDeleteProgram(progRgb); progRgb = 0 }
            if (progBgr != 0) { GLES20.glDeleteProgram(progBgr); progBgr = 0 }
            curProgram = 0
            curFmt = -1
        }

        override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
            // 原版为空实现（program 懒创建在 f() 里）；半透明清屏色
            GLES20.glClearColor(0f, 0f, 0f, 0f)
        }

        override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
            viewW = width.coerceAtLeast(1)
            viewH = height.coerceAtLeast(1)
            lastW = 0f
            lastH = 0f
            GLES20.glViewport(0, 0, width, height)
        }

        override fun onDrawFrame(gl: GL10?) {
            if (paused || givenUp) return
            val info = try {
                org.citra.emu.NativeLibrary.getScreenTexture()
            } catch (_: Throwable) {
                null
            }
            // ★ 有效性守卫：null/长度不足（核心未就绪）、texId==0（导出通道
            //   未就绪 —— 引导期提供器全局为 null 时 JNI 返回全零数组）或
            //   glIsTexture==false（该 ID 不属于本上下文）都视为失败 ——
            //   半透明清屏后透出底层直绘，绝不画黑帧。
            // ★★ 本轮根治：核心 Run() 引导期（可达数秒）提供器尚未创建，
            //   旧实现 60 帧（~1s）就永久 give-up → 滤镜全失效。现在
            //   【首次成功之前不计数】，成功后才有失败上限（见类头注释）。
            if (info == null || info.size < 4 ||
                info[0] == 0 || !GLES20.glIsTexture(info[0])) {
                GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)   // alpha=0：透出底层
                if (everSucceeded) {
                    failStreak++
                    if (failStreak > FAIL_GIVE_UP) {
                        givenUp = true
                        Log.w("MmjGlView",
                            "getScreenTexture broken after first success (streak=$failStreak), overlay gives up -> direct view")
                        try { org.citra.emu.NativeLibrary.releaseScreenTexture() } catch (_: Throwable) {}
                        postGiveUp()
                    } else if (failStreak % FAIL_LOG_EVERY == 0) {
                        Log.w("MmjGlView", "getScreenTexture failing (streak=$failStreak)")
                    }
                }
                return
            }
            everSucceeded = true
            failStreak = 0
            val texId = info[0]
            val w = info[1].toFloat()
            val h = info[2].toFloat()
            val fmt = info[3]
            if (w <= 0f || h <= 0f) return
            if (texId != lastTexId || w != lastW || h != lastH) {
                bindTextureProgram(texId, w, h, fmt)
            }
            // FX uniform：location1 = resolution（屏上尺寸，GetOnScreenSize 语义），
            // location2 = frame_count（GetTime 语义）。原版从未赋值恒 0。
            try {
                GLES20.glUniform2f(1, viewW.toFloat(), viewH.toFloat())
                GLES20.glUniform1i(2, (frameCount and 0xffffffffL).toInt())
            } catch (_: Throwable) {}
            frameCount++
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        }

        /** c1/W.f：按纹理格式选 program（fmt==1 → BGR）+ 绑纹理 + 宽高比。 */
        private fun bindTextureProgram(texId: Int, w: Float, h: Float, fmt: Int) {
            if (fmt != curFmt) {
                if (fmt != 1) {
                    if (progRgb == 0) {
                        progRgb = buildProgram(fsPassthroughRgb, fxHelpersRgb)
                    }
                    curProgram = progRgb
                } else {
                    if (progBgr == 0) {
                        progBgr = buildProgram(fsPassthroughBgr, fxHelpersBgr)
                    }
                    curProgram = progBgr
                }
                GLES20.glUseProgram(curProgram)
                curFmt = fmt
            }
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texId)
            aspectUniform(w, h)
            lastTexId = texId
            lastW = w
            lastH = h
        }

        /** c1/W.a：保持核心画面宽高比（float 精确版）。 */
        private fun aspectUniform(texW: Float, texH: Float) {
            val viewAspect = viewW.toFloat() / viewH.toFloat()
            val texAspect = texW / texH
            var sx = 1f
            var sy = 1f
            if (viewAspect >= texAspect) {
                if (viewAspect > texAspect) sy = texAspect / viewAspect
            } else {
                sx = viewAspect / texAspect
            }
            try { GLES20.glUniform2f(0, sx, sy) } catch (_: Throwable) {}
        }

        /** c1/W.b：后处理着色器激活时注入源码；编译失败回落直通。 */
        private fun buildProgram(defaultFs: String, helpers: String): Int {
            val name = shaderName
            val fxSrc = if (name.isNullOrBlank()) null else readShaderSource(name)
            val fs = if (!fxSrc.isNullOrEmpty()) {
                fxHeader + "\n" + helpers + "\n" + fxSrc
            } else {
                defaultFs
            }
            val prog = compileProgram(vsSource, fs)
            return if (prog != 0) prog else compileProgram(vsSource, defaultFs)
        }

        /**
         * c1/W.d：编译着色器（#version 头 + 信息日志）。
         * ★ 本轮：glslVersion 非空时先试该版本；320 头在 GLES 3.1 及以下
         *   上下文会编译失败 —— 失败后由 compileProgram 用 310 头重试
         *   （本文件全部 GLSL 特性 —— layout(binding)/uint/textureSize ——
         *   均在 GLES 3.1 基线内）。
         */
        private fun compileShader(source: String, type: Int, glslVersion: String?): Int {
            val shader = GLES20.glCreateShader(type)
            val header = if (glslVersion != null) "#version $glslVersion\n" else ""
            GLES20.glShaderSource(shader, "${header}precision highp int;\nprecision highp float;\n$source")
            GLES20.glCompileShader(shader)
            val status = java.nio.IntBuffer.allocate(1)
            GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status)
            if (status.get(0) == 1) return shader
            Log.e("MmjGlView", GLES20.glGetShaderInfoLog(shader))
            GLES20.glDeleteShader(shader)
            return 0
        }

        private fun compileProgram(vs: String, fs: String): Int {
            // ★ 本轮：先按原版 #version 320 es 编译；任一阶段失败且设备上下文
            //   版本较低时用 310 es 整链重试（两阶段都失败才回落直通）。
            for (version in listOf("320 es", "310 es")) {
                val prog = compileProgramWithVersion(vs, fs, version)
                if (prog != 0) return prog
            }
            return 0
        }

        private fun compileProgramWithVersion(vs: String, fs: String, version: String): Int {
            val vsObj = compileShader(vs, GLES20.GL_VERTEX_SHADER, version)
            if (vsObj == 0) return 0
            val fsObj = compileShader(fs, GLES20.GL_FRAGMENT_SHADER, version)
            if (fsObj == 0) { GLES20.glDeleteShader(vsObj); return 0 }
            val prog = GLES20.glCreateProgram()
            GLES20.glAttachShader(prog, vsObj)
            GLES20.glAttachShader(prog, fsObj)
            GLES20.glLinkProgram(prog)
            GLES20.glDeleteShader(vsObj)
            GLES20.glDeleteShader(fsObj)
            val status = java.nio.IntBuffer.allocate(1)
            GLES20.glGetProgramiv(prog, GLES20.GL_LINK_STATUS, status)
            if (status.get(0) == 1) return prog
            Log.e("MmjGlView", GLES20.glGetProgramInfoLog(prog))
            GLES20.glDeleteProgram(prog)
            return 0
        }

        /** d1.a.u 的 NesStation 版：读 <filesDir>/azahar/shaders/<name>.glsl。 */
        private fun readShaderSource(name: String): String? {
            return try {
                // NesApp.get() 返回 NesApp?（Application 未创建时为 null）——
                // 此处安静降级：返回 null = 直通渲染（无后处理），绝不抛错
                val ctx = com.nesstation.app.NesApp.get() ?: return null
                val f = File(File(ctx.filesDir, "azahar"), "shaders/$name.glsl")
                if (f.isFile) f.readText() else null
            } catch (e: Throwable) {
                Log.w("MmjGlView", "read shader '$name' failed: ${e.message}")
                null
            }
        }

        /**
         * c1/W.e：释放（原版同时调 releaseScreenTexture 复位核心导出标志 ——
         * 本层停用/退出时必须复位，核心直绘呈现通道不受影响）。
         */
        fun release() {
            if (progRgb != 0) { GLES20.glDeleteProgram(progRgb); progRgb = 0 }
            if (progBgr != 0) { GLES20.glDeleteProgram(progBgr); progBgr = 0 }
            curProgram = 0
            curFmt = -1
            givenUp = true
            try { org.citra.emu.NativeLibrary.releaseScreenTexture() } catch (_: Throwable) {}
        }

        private fun postGiveUp() {
            // 回调切主线程（onGiveUp 只做 UI 隐藏，无重活）
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                try { onGiveUp?.invoke() } catch (_: Throwable) {}
            }
        }
    }
}
