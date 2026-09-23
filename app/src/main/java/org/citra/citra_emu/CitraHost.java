package org.citra.citra_emu;

import android.app.Activity;
import android.content.Context;
import java.lang.ref.WeakReference;

/**
 * NesStation ↔ Azahar(citra-android) 进程内桥。
 *
 * AzaharPlus 的 libcitra-android.so 以静态 JNI（Java_org_citra_citra_1emu_*）
 * 绑定到 org.citra.citra_emu.NativeLibrary 等宿主类。这些类随 NesStation 打包，
 * 不再需要任何外部 APK —— 与 DC 核心（libdccore.so dlopen Flycast）同一
 * 进程内嵌入模式。
 *
 * Native 回调（onCoreError / exitEmulationActivity / 文件助手等）统一经
 * [CitraHost] 路由到 NesStation 的上下文与 UI：
 *  - appContext  : application context（文件助手/资源）
 *  - activityRef : 当前模拟 activity（对话框宿主，可为 null）
 *  - userDirOverride : 3DS 用户目录（引擎启动前设置；getUserDirectory 返回它）
 *  - alert/coreError/exit hook : NesStation 侧决定如何呈现与放行
 */
public final class CitraHost {

    /** Azahar 错误/弹窗决定：true = 继续（继续运行/忽略），false = 中止。 */
    public interface DecisionHook {
        boolean onDecision(String title, String message, boolean yesNo);
    }

    /** 请求退出模拟界面（native exitEmulationActivity 回调）。 */
    public interface ExitHook {
        void onExit(int resultCode);
    }

    /** 3DS 加载类轻量状态提示（shader 缓存等）。 */
    public interface StatusHook {
        void onStatus(String message);
    }

    private static volatile Context appContext = null;
    private static volatile WeakReference<Activity> activityRef = new WeakReference<>(null);
    private static volatile String userDirOverride = null;
    private static volatile String cameraImagePath = null;
    private static volatile DecisionHook alertHook = null;
    private static volatile DecisionHook coreErrorHook = null;
    private static volatile ExitHook exitHook = null;
    private static volatile StatusHook statusHook = null;

    private CitraHost() {
    }

    public static void init(Context context) {
        appContext = context.getApplicationContext();
    }

    public static Context getAppContext() {
        return appContext;
    }

    public static void setActivity(Activity activity) {
        activityRef = activity == null
            ? new WeakReference<>(null)
            : new WeakReference<>(activity);
    }

    public static Activity getActivity() {
        return activityRef.get();
    }

    public static void setUserDirectory(String path) {
        userDirOverride = path;
    }

    public static String getUserDirectory() {
        return userDirOverride;
    }

    public static void setCameraImagePath(String path) {
        cameraImagePath = path;
    }

    public static String getCameraImagePath() {
        return cameraImagePath;
    }

    public static void setAlertHook(DecisionHook hook) {
        alertHook = hook;
    }

    public static DecisionHook getAlertHook() {
        return alertHook;
    }

    public static void setCoreErrorHook(DecisionHook hook) {
        coreErrorHook = hook;
    }

    public static DecisionHook getCoreErrorHook() {
        return coreErrorHook;
    }

    public static void setExitHook(ExitHook hook) {
        exitHook = hook;
    }

    public static ExitHook getExitHook() {
        return exitHook;
    }

    public static void setStatusHook(StatusHook hook) {
        statusHook = hook;
    }

    public static StatusHook getStatusHook() {
        return statusHook;
    }

    /** 全部反初始化（模拟界面退出时调用，避免泄漏 activity）。 */
    public static void resetSession() {
        activityRef = new WeakReference<>(null);
    }
}
