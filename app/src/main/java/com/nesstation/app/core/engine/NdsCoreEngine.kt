package com.nesstation.app.core.engine

/**
 * NDS 双引擎公共契约：melonDS（[NdsEngine]）与 DraStic（激烈，
 * [DraSticEngine]）共同实现的触摸 / 双屏渲染接口。
 *
 * EmulatorScreen 与 NdsDualScreenView 原先直接强转 `engine as? NdsEngine`；
 * 引入本接口后两个核心在触摸链路与自由布局渲染上完全同构 —— UI 层不再
 * 关心底下是哪个核心：
 *
 *  - 触摸（官方 melonDS 架构）：触点按布局映射为下屏像素坐标
 *    (x: 0..255, y: 0..191) 经 [setTouchInputDirect] 注入；
 *  - Hybrid 布局：小屏几何只有核心知道 → [setTouchInput] 的归一化坐标路径；
 *  - 自由布局渲染：NdsDualScreenView 从 [frameBuffer]（上屏 256×192 在前、
 *    下屏 256×192 在后的合成帧）按布局切片绘制，经 [frameStamp] 帧号节流，
 *    尺寸经 [filteredVideoWidth] / [filteredVideoHeight] 查询。
 *
 * [NdsEngine.frameBuffer] 在放大滤镜（HQ2X/HQ4X）时返回放大后的合成帧；
 * [DraSticEngine.frameBuffer] 恒为 256×384 原生合成帧（DraStic 无放大型
 * 原生滤镜，叠加型滤镜 scanline/crt/dot 仍由视图层绘制）。
 */
interface NdsCoreEngine : EmulatorEngine {

    /**
     * 触摸输入 —— 直接下屏像素坐标（0..255, 0..191）。
     * @param pressed true = 按下 / 移动，false = 抬起 / 释放。
     */
    fun setTouchInputDirect(x: Int, y: Int, pressed: Boolean)

    /**
     * 触摸输入 —— 归一化坐标路径（仅 Hybrid 布局需要）。
     * @param x Signed X (-0x8000..0x7FFF)
     * @param y Signed Y (-0x8000..0x7FFF)
     */
    fun setTouchInput(x: Int, y: Int, pressed: Boolean)

    /** 单调递增帧号：新帧到达时 +1，Choreographer 据此跳过冗余重绘。 */
    fun frameStamp(): Long

    /** 自由布局视图应呈现的合成帧宽度（melonDS 放大滤镜时为放大宽度）。 */
    fun filteredVideoWidth(): Int

    /** 自由布局视图应呈现的合成帧高度。 */
    fun filteredVideoHeight(): Int
}
