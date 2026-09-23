package org.dolphinemu.dolphinemu;

import android.app.Activity;

import org.dolphinemu.dolphinemu.utils.Java_GCAdapter;
import org.dolphinemu.dolphinemu.utils.Java_WiimoteAdapter;

/**
 * Ishiruka（Dolphin fork, 5.0-15560）JNI 宿主类 —— 与上游
 * org.dolphinemu.dolphinemu.NativeLibrary 保持完全一致的 JVM 形状：
 *
 *  - 全部静态 native（Java_org_dolphinemu_dolphinemu_NativeLibrary_* 静态导出，
 *    共 41 个方法；libmain.so 由 System.loadLibrary("main") 装载）。
 *  - native → Java 回调：displayAlertMsg（panic alert）、updateWindowSize、
 *    rumble（ciface Motor 输出）—— 签名/静态性必须精确一致。
 *
 * NesStation 进程内集成：弹窗/退出经 [DolphinHost] 路由到 NesStation。
 * 输入约定（Touchscreen 设备，来自核心自带 GCPadNew.ini/WiimoteNew.ini）：
 *  - onGamePadEvent("Touchscreen", id, pressed)   pressed: 1=按下, 0=抬起
 *  - onGamePadMoveEvent("Touchscreen", axis, value) axis id 见 IniProfileMap
 *  - GC: 按钮 0-9 / 主摇杆 11,12(上下) 13,14(左右) / C 摇杆 16-19 / 扳机 20,21
 *  - Wii: 按钮 100-139 / IR 112-115(112,113 上下 114,115 左右) / 挥动 120-125 /
 *    倾斜 127-130 / 摇晃按钮 132-134 / 双节棍 200-222 / 经典 300-324
 */
public final class NativeLibrary {

    public static final String TouchScreenDevice = "Touchscreen";

    /** 加载核心库（幂等）。 */
    public static synchronized void loadLibrary() {
        DolphinHost.ensureLibraryLoaded();
    }

    private NativeLibrary() {
    }

    // =======================================================================
    // === native 方法（与 libmain.so 静态导出一一对应） ===
    // =======================================================================

    public static native void ChangeDisc(String path);

    public static native String DecryptARCode(String p0);

    public static native String DefaultAudioBackend();

    public static native int DefaultCPUCore();

    public static native String[] GetAudioBackendList();

    public static native float GetGameAspectRatio();

    public static native float GetGameDisplayScale();

    public static native String GetGitRevision();

    public static native String GetUserDirectory();

    public static native String GetVersionString();

    public static native boolean IsRunning();

    public static native void LoadGameIniFile(String gamePath);

    public static native void LoadState(int slot);

    public static native void LoadStateAs(String path);

    public static native void NetPlay(String p0, boolean p1);

    public static native void PauseEmulation();

    public static native void RefreshWiimotes();

    public static native void ReloadWiimoteConfig();

    /** 阻塞运行（模拟线程内）：paths 为游戏文件数组。 */
    public static native void Run(String[] paths, String revision);

    public static native void SaveGameIniFile(String gamePath);

    public static native void SaveScreenShot();

    public static native void SaveState(int slot, boolean wait);

    public static native void SaveStateAs(String path, boolean wait);

    /** 写用户 INI：SetUserSetting(section, key, value, file) — file 如 "Dolphin.ini"/"GFX.ini"。 */
    public static native void SetUserSetting(String section, String setting, String value, String file);

    /** 同上但写游戏 INI（LoadGameIniFile 装载后的当前游戏配置）。 */
    public static native void SetConfig(String section, String setting, String value, String file);

    public static native void SetProfileSetting(String section, String setting, String value, String file);

    public static native void SetProfiling(boolean p0);

    public static native void SetScaledDensity(float p0);

    public static native void SetUserDirectory(String path);

    public static native void StopEmulation();

    public static native void SurfaceChanged(android.view.Surface surface);

    public static native void SurfaceDestroyed();

    public static native void UnPauseEmulation();

    public static native void WriteProfileResults();

    public static native void eglBindAPI(int api);

    public static native int[] getRunningSettings();

    public static native int[] getSysconfSettings();

    public static native boolean onGamePadEvent(String device, int buttonId, int action);

    public static native void onGamePadMoveEvent(String device, int axisId, float value);

    public static native void setRunningSettings(int[] p0);

    public static native void setSysconfSettings(int[] p0);

    public static native void setSystemLanguage(String language);

    // =======================================================================
    // === native → Java 回调（签名/静态性必须与上游一致） ===
    // =======================================================================

    /** native panic alert（阻塞等用户应答）。 */
    public static boolean displayAlertMsg(String title, String message, boolean yesNo) {
        android.util.Log.w("DolphinHost", "[Alert] " + title + ": " + message);
        return DolphinHost.displayAlert(title, message, yesNo);
    }

    /** native 通知窗口尺寸变化（NesStation 显示层自行管理，仅记录）。 */
    public static void updateWindowSize(int width, int height) {
        android.util.Log.d("DolphinHost", "updateWindowSize " + width + "x" + height);
    }

    /** ciface Motor 输出（震感）→ 系统振动器。 */
    public static void rumble(int padId, double strength) {
        DolphinHost.rumble(strength);
    }

    /** Java 侧网络状态（上游保留 API）。 */
    public static boolean isNetworkConnected(android.content.Context context) {
        try {
            android.net.ConnectivityManager cm = (android.net.ConnectivityManager)
                context.getSystemService(android.content.Context.CONNECTIVITY_SERVICE);
            android.net.NetworkInfo info = cm == null ? null : cm.getActiveNetworkInfo();
            return info != null && info.isConnected();
        } catch (Throwable t) {
            return false;
        }
    }

    /** 供 overlay 使用的 GC 适配器/BD 綁定（上游保留引用，USB 外设路径）。 */
    static {
        // 预绑定两个 USB 适配器类（native 经 FindClass 解析；
        // 提前触发类加载可避免模拟线程上首次 FindClass 的时序问题）
        try {
            Class.forName(Java_GCAdapter.class.getName());
            Class.forName(Java_WiimoteAdapter.class.getName());
        } catch (Throwable ignored) {
        }
    }
}
