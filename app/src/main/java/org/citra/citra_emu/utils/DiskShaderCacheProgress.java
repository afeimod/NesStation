package org.citra.citra_emu.utils;

import android.util.Log;

import org.citra.citra_emu.CitraHost;

/**
 * 着色器磁盘缓存加载进度回调（native → Java）。
 *
 * 上游在此显示进度 UI；NesStation 进程内模式只记录 + 轻量状态钩子。
 * static loadProgress(LoadCallbackStage,int,int,String) 被 native 直接调用，
 * 签名/静态性必须与上游一致。
 */
public final class DiskShaderCacheProgress {

    /** 阶段枚举（ordinal 被 native 使用 —— 顺序不可改）。 */
    public enum LoadCallbackStage {
        Prepare,
        Decompile,
        Build,
        Complete
    }

    public static final DiskShaderCacheProgress INSTANCE = new DiskShaderCacheProgress();

    private DiskShaderCacheProgress() {
    }

    public static void loadProgress(LoadCallbackStage stage, int current, int total, String obj) {
        Log.i("CitraHost", "shader cache " + stage + " " + current + "/" + total
            + (obj == null || obj.isEmpty() ? "" : " " + obj));
        CitraHost.StatusHook hook = CitraHost.getStatusHook();
        if (hook != null && stage == LoadCallbackStage.Complete) {
            hook.onStatus("着色器缓存就绪");
        }
    }
}
