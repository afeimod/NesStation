package info.cemu.cemu.nativeinterface

import android.view.Surface

object NativeEmulation {
    @JvmStatic
    external fun initializeEmulation()

    @JvmStatic
    external fun setDPI(dpi: Float)


    @JvmStatic
    external fun setSurface(surface: Surface?, isMainCanvas: Boolean)

    @JvmStatic
    external fun initializeSurface(isMainCanvas: Boolean)

    @JvmStatic
    external fun clearPadSurface()

    @JvmStatic
    external fun setSurfaceSize(width: Int, height: Int, isMainCanvas: Boolean)

    /**
     * ★ NesStation R2: 输出渲染分辨率缩放（0.25..1.0，1=原生）。
     * 游戏画面渲染进低分辨率离屏目标后线性放大回全尺寸交换链 ——
     * 显示大小不变、渲染填充率按 scale^2 下降；运行中调用即时热生效。
     */
    @JvmStatic
    external fun setOutputRenderScale(scale: Float)

    // ★ NesStation R5: 帧率限制（运行中热生效）+ GamePad 悬浮画面更新频率
    //   （setPadPresentInterval：纯呈现侧节流省电，0=每帧 / 33≈30fps / 50=20fps）
    external fun setFrameLimit(fps: Int)
    external fun setPadPresentInterval(intervalMs: Int)

    // ★★★ NesStation R7: 「取消阴影渲染」—— 着色器层把阴影比较
    //   采样替换为“完全受光”常数，阴影消失、画面亮度正常
    //   （不是变黑）。运行中切换会触发受影响着色器自动重编。
    external fun setShadowRenderingDisabled(disabled: Boolean)

    // ★★★ NesStation R11 性能四件套（参考「风之杖HD 重编译版」移植）：
    //   CPU 核心调度（0 默认/1 大核优先/2 小核优先，运行中热生效）、
    //   CPU 模式覆盖（-1 默认 Auto / 0 单核解释器 / 1 单核重编译 / 3 多核重编译，
    //   重启游戏完全生效）、GX2DrawDone 快速模式（默认关，实验性）
    external fun setCoreSchedulingPolicy(policy: Int)
    external fun setCpuModeOverride(mode: Int)
    external fun setDrawDoneSkipWait(skip: Boolean)

    // ★★★ NesStation R12 性能增强（参考同款 APK 深度逆向移植）：
    //   ADPF 动态性能提示（Android Dynamic Performance Framework，API 31+ 生效，
    //   默认开）+ 帧生成（呈现侧光流插帧，实验性，默认关）
    external fun setAdpfEnabled(enable: Boolean)
    external fun setFrameGenEnabled(enable: Boolean)
    @JvmStatic
    external fun initializeRenderer()

    object PrepareTitleResult {
        const val SUCCESSFUL: Int = 0
        const val ERROR_GAME_BASE_FILES_NOT_FOUND: Int = 1
        const val ERROR_NO_DISC_KEY: Int = 2
        const val ERROR_NO_TITLE_TIK: Int = 3
        const val ERROR_UNKNOWN: Int = 4
    }

    @JvmStatic
    external fun prepareTitle(launchPath: String?): Int

    @JvmStatic
    external fun launchTitle()

    @JvmStatic
    external fun pauseTitle()

    @JvmStatic
    external fun resumeTitle()

    @JvmStatic
    external fun isTitlePaused(): Boolean

    @JvmStatic
    external fun shutdownTitle()

    // ★ 核心渲染线程真实帧率（每秒刷新，未出帧为 0）—— NesStation 前端全局 FPS 显示用
    @JvmStatic
    external fun getRendererFps(): Double

    @JvmStatic
    external fun initializeSystems()

    @JvmStatic
    external fun setReplaceTVWithPadView(swapped: Boolean)

    @JvmStatic
    external fun supportsLoadingCustomDriver(): Boolean
}
