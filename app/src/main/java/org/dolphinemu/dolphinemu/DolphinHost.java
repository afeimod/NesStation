package org.dolphinemu.dolphinemu;

import android.app.Activity;

/**
 * Ishiruka ↔ NesStation 进程内桥（对应 Citra 侧的 CitraHost）。
 *
 * 负责：核心库装载、弹窗/振动/退出路由、活动引用。
 * 静态 JNI 绑定 org.dolphinemu.dolphinemu.NativeLibrary 与 model/utils 包。
 */
public final class DolphinHost {

    /** alert 应答：true = 继续 / 是。 */
    public interface DecisionHook {
        boolean onDecision(String title, String message, boolean yesNo);
    }

    public interface ExitHook {
        void onExit();
    }

    private static volatile boolean libraryLoaded = false;
    private static volatile Activity activity = null;
    private static volatile DecisionHook alertHook = null;
    private static volatile ExitHook exitHook = null;
    private static volatile long lastRumbleMs = 0L;

    private DolphinHost() {
    }

    /** System.loadLibrary("main") —— 幂等。 */
    public static synchronized void ensureLibraryLoaded() {
        if (!libraryLoaded) {
            System.loadLibrary("main");
            libraryLoaded = true;
        }
    }

    public static boolean isLibraryLoaded() {
        return libraryLoaded;
    }

    public static void init(Activity current) {
        activity = current;
    }

    public static Activity getActivity() {
        return activity;
    }

    public static void setActivity(Activity current) {
        activity = current;
    }

    public static void setAlertHook(DecisionHook hook) {
        alertHook = hook;
    }

    public static DecisionHook getAlertHook() {
        return alertHook;
    }

    public static void setExitHook(ExitHook hook) {
        exitHook = hook;
    }

    public static ExitHook getExitHook() {
        return exitHook;
    }

    public static boolean displayAlert(String title, String message, boolean yesNo) {
        DecisionHook hook = alertHook;
        if (hook != null) return hook.onDecision(title, message, yesNo);
        return true;
    }

    public static void requestExit() {
        ExitHook hook = exitHook;
        if (hook != null) hook.onExit();
    }

    /** 核心震动请求 → 系统振动器（节流 30ms）。 */
    public static void rumble(double strength) {
        Activity current = activity;
        if (current == null) return;
        long now = System.currentTimeMillis();
        if (now - lastRumbleMs < 30) return;
        lastRumbleMs = now;
        try {
            android.os.Vibrator vibrator = (android.os.Vibrator)
                current.getSystemService(android.content.Context.VIBRATOR_SERVICE);
            if (vibrator == null || !vibrator.hasVibrator()) return;
            int ms = (int) (40 + 60 * Math.min(1.0, Math.max(0.0, strength)));
            if (android.os.Build.VERSION.SDK_INT >= 26) {
                vibrator.vibrate(android.os.VibrationEffect.createOneShot(
                    ms, android.os.VibrationEffect.DEFAULT_AMPLITUDE));
            } else {
                vibrator.vibrate(ms);
            }
        } catch (Throwable ignored) {
        }
    }

    public static void resetSession() {
        activity = null;
    }
}
