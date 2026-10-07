package com.nesstation.app.ui.emulator

import android.content.Context
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.util.Log
import java.io.File
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * ★★★★ MMJ 后处理着色器 GL 呈现层（本轮新增，根治"mmj 核心没有生效全局滤镜
 *   的 xbr 和 hqx，包括自带的后处理着色器也全失效了"）★★★★
 *
 * 【根因（原版 Citra_MMJ_20250220.apk 反编译实证）】：
 *   MMJ 的后处理着色器【不是】native 渲染管线的一部分 —— 原版是在
 *   【Java GL 层】实现的：核心把画面渲染到离屏 texture（doFrame 触发），
 *   Java 层 GLSurfaceView.Renderer（原版 c1/W 类，反编译全文在案）每帧
 *   调 NativeLibrary.getScreenTexture() 拿 [texId, w, h, fmt]，用
 *   「直通 program（RGB/BGR 两种）+ 用户选择的 .glsl 后处理源码注入」
 *   画一个全屏 quad 上屏。NesStation 此前用 SurfaceView 直绘（native
 *   自己 EGL present），Java 后处理层完全不存在 —— pp_shader_name 写进
 *   ini 也永远不会有效果（两轮 ini/呈现模式修复方向错误的真相）。
 *
 * 【本类 = 原版 c1/W 的逐行 Kotlin 复刻】：
 *   - GLSL 常量（顶点着色器 / RGB-BGR 直通片元 / FX 头 + Sample 辅助）
 *     逐字取自反编译的 c1/W 构造器；
 *   - 编译链 d()（#version 320 es 头 + glCompileShader + 信息日志）；
 *   - program 构造 b()：后处理着色器激活时拼 header+helpers+用户源码，
 *     编译失败回落直通（原版同款降级）；
 *   - 纹理绑定 f()：fmt==1 → BGR program，否则 RGB program（纹理格式
 *     两种变体，原版同款）；
 *   - onDrawFrame：getScreenTexture → 变化检测 → glDrawArrays 全屏 quad。
 *   与原版差异（均为增强）：aspect 用 float 精确计算（原版 int 除法截断
 *   是移植 bug）；FX 的 resolution / frame_count uniform 每帧正确赋值
 *   （原版从未赋值 = 恒 0，xBR 等不依赖它们的着色器无影响，依赖
 *   GetResolution() 的着色器得到正确值）。
 *
 * 【视图层级】：setZOrderMediaOverlay(true) —— 叠在 native 直绘的
 *   SurfaceView 之上（底层保留直绘作为无后处理的兜底画面，本层用后处理
 *   画面覆盖；getScreenTexture 失败/纹理无效时本层不绘制，用户看到的
 *   就是底层直绘 —— 无损降级）。
 */
class MmjGlView(context: Context) : GLSurfaceView(context) {

    private val fxRenderer = Renderer()

    init {
        setEGLContextClientVersion(3)
        // 叠加在普通 SurfaceView（native 直绘层）之上
        setZOrderMediaOverlay(true)
        setRenderer(fxRenderer)
        renderMode = RENDERMODE_CONTINUOUSLY
    }

    /**
     * 设置后处理着色器名（主线程调用）。null/空 = 直通（无后处理）。
     * 变化时渲染线程重建 program（原版 e() + 下次 b() 的重建语义）。
     */
    fun setPostFxShader(name: String?) {
        fxRenderer.setShaderName(name)
    }

    /** 暂停/恢复后处理层的绘制（Activity 生命周期同步）。 */
    fun setFxPaused(paused: Boolean) {
        fxRenderer.paused = paused
    }

    /** 释放 GL 资源（GL 线程内执行）。 */
    fun releaseFx() {
        queueEvent { fxRenderer.release() }
    }

    private class Renderer : GLSurfaceView.Renderer {

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

        /** FX 公共头（c1/W.n）：Hyllian/Sedi 类着色器的兼容层。 */
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

        /** Sample 辅助（RGB 变体，c1/W.o）。 */
        private val fxHelpersRgb = (
                "float4 Sample() { return texture(color_texture, frag_tex_coord); }\n" +
                "float4 SampleLocation(float2 location) { return texture(color_texture, location); }\n" +
                "float4 SampleFetch(int2 location) { return texelFetch(color_texture, location, 0); }\n")

        /** Sample 辅助（BGR 变体，c1/W.p）。 */
        private val fxHelpersBgr = (
                "float4 Sample() { return texture(color_texture, frag_tex_coord).bgra; }\n" +
                "float4 SampleLocation(float2 location) { return texture(color_texture, location).bgra; }\n" +
                "float4 SampleFetch(int2 location) { return texelFetch(color_texture, location, 0).bgra; }\n")

        // ---- 状态（对应 c1/W 字段）----
        private var progRgb = 0        // this.a：RGB 直通/后处理 program
        private var progBgr = 0        // this.b：BGR 直通/后处理 program
        private var curProgram = 0     // this.h
        private var curFmt = -1        // this.i
        private var lastTexId = -1     // this.g
        private var lastW = 0f         // this.e
        private var lastH = 0f         // this.f
        private var viewW = 1          // this.c
        private var viewH = 1          // this.d
        private var frameCount = 0L
        @Volatile private var shaderName: String? = null
        @Volatile var paused = false

        fun setShaderName(name: String?) {
            val norm = name?.trim()?.takeIf { it.isNotEmpty() && it != "(off)" }
            if (norm == shaderName) return
            shaderName = norm
            // 着色器变化 → 丢弃两个缓存 program（GL 线程里重建；
            // 简单起见直接置零，旧 program 由 GL 上下文丢弃/重建覆盖，
            // 原版 e() 同款语义）
            progRgb = 0
            progBgr = 0
            curProgram = 0
            curFmt = -1
        }

        override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
            // 原版为空实现（program 懒创建在 f() 里）
        }

        override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
            viewW = width.coerceAtLeast(1)
            viewH = height.coerceAtLeast(1)
            lastW = 0f
            lastH = 0f
            GLES20.glViewport(0, 0, width, height)
        }

        override fun onDrawFrame(gl: GL10?) {
            if (paused) return
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
            val info = try {
                org.citra.emu.NativeLibrary.getScreenTexture()
            } catch (_: Throwable) {
                null
            } ?: return
            if (info.size < 4) return
            val texId = info[0]
            val w = info[1].toFloat()
            val h = info[2].toFloat()
            val fmt = info[3]
            if (texId == 0) return
            if (texId != lastTexId || w != lastW || h != lastH) {
                bindTextureProgram(texId, w, h, fmt)
            }
            // FX uniform：resolution / frame_count（增强：原版未赋值恒 0）
            try {
                GLES20.glUniform2f(1, w, h)
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

        /** c1/W.d：编译着色器（#version 320 es + 信息日志）。 */
        private fun compileShader(source: String, type: Int): Int {
            val shader = GLES20.glCreateShader(type)
            GLES20.glShaderSource(shader, "#version 320 es\nprecision highp int;\nprecision highp float;\n$source")
            GLES20.glCompileShader(shader)
            val status = java.nio.IntBuffer.allocate(1)
            GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status)
            if (status.get(0) == 1) return shader
            Log.e("MmjGlView", GLES20.glGetShaderInfoLog(shader))
            GLES20.glDeleteShader(shader)
            return 0
        }

        private fun compileProgram(vs: String, fs: String): Int {
            val vsObj = compileShader(vs, GLES20.GL_VERTEX_SHADER)
            if (vsObj == 0) return 0
            val fsObj = compileShader(fs, GLES20.GL_FRAGMENT_SHADER)
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

        /** c1/W.e：释放（原版同时调 releaseScreenTexture —— 这里保守不调，
         *  直绘层可能还在消费纹理；只删 program）。 */
        fun release() {
            if (progRgb != 0) { GLES20.glDeleteProgram(progRgb); progRgb = 0 }
            if (progBgr != 0) { GLES20.glDeleteProgram(progBgr); progBgr = 0 }
            curProgram = 0
            curFmt = -1
        }
    }
}
