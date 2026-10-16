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

    // ★ NesStation R4: 阴影/光照渲染质量（三档）+ 帧率限制（运行中热生效）
    external fun setShadowRenderScale(scale: Float)
    external fun setAuxRenderScale(scale: Float)
    external fun setFrameLimit(fps: Int)
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
