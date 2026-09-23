package org.citra.citra_emu.utils;

/**
 * GPU 驱动助手 —— 与上游 org.citra.citra_emu.utils.GpuDriverHelper
 * （Kotlin object）保持一致的 JVM 形状：static INSTANCE + 实例 native。
 *
 * supportsCustomDriverLoading() = 当前设备是否支持 Adreno 定制驱动
 * （free Mesa turnip 等）。NesStation 3DS 设置页「GPU 驱动」分组用它
 * 决定是否展示驱动导入入口。
 */
public final class GpuDriverHelper {

    public static final GpuDriverHelper INSTANCE = new GpuDriverHelper();

    private GpuDriverHelper() {
    }

    public final native boolean supportsCustomDriverLoading();
}
