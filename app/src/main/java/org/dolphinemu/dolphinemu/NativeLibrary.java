package org.dolphinemu.dolphinemu;

import android.view.Surface;

/**
 * Ishiiruka（NGC/Wii）核心 —— JNI 契约类（dolphinemu 包名）。
 *
 * 【为什么必须有这个类】libishiiruka.so 是预编译二进制，其 JNI_OnLoad 内
 *   FindClass("org/dolphinemu/dolphinemu/NativeLibrary")，
 *   且全部导出符号按 Java_org_dolphinemu_dolphinemu_NativeLibrary_* 绑定
 *   （BuildId 9aa1cc68f0c7c917b27cbb643e859c2842c066c4，strings 实测）。
 *   原 vendored 类 org.dolphinemu.ishiiruka.NativeLibrary 包名不匹配，导致
 *   JNI_OnLoad 中 GetStaticMethodID 收到 null jclass 直接 SIGABRT 闪退。
 *
 * 【职责】
 *   1. static 块加载 libishiiruka.so（触发 JNI_OnLoad，此时本类已可被 FindClass 命中）；
 *   2. 提供原生层反向回调 displayAlertMsg(String) / endEmulationActivity()；
 *   3. 声明 so 导出符号对应的 native 方法（符号自动绑定）。
 *      so 未导出的旧 API（GetConfig / GetBanner / GetTitle 等）改为 Java 兜底实现，
 *      避免调用时 UnsatisfiedLinkError。
 *
 * 【宿主转发】回到 org.dolphinemu.ishiiruka.NativeLibrary.NesStationHost（引擎注册），
 *   保持 IshirukaEngine 调用路径不变。
 */
public final class NativeLibrary
{
        private NativeLibrary() {}

        // ------------------------------------------------------------------
        // 库加载（JNI_OnLoad 入口：FindClass 本类 + 缓存 displayAlertMsg /
        // endEmulationActivity 两个回调，签名 (Ljava/lang/String;)V / ()V）
        // ------------------------------------------------------------------
        static
        {
                System.loadLibrary("ishiiruka");
        }

        // ------------------------------------------------------------------
        // 原生层反向回调（JNI_OnLoad GetStaticMethodID 锁定这两个签名，必须精确）
        // ------------------------------------------------------------------

        /** MsgAlert 弹窗回调。签名 (Ljava/lang/String;)V。 */
        public static void displayAlertMsg(final String message)
        {
                android.util.Log.e("DolphinNative", "[NativeLibrary] Alert: " + message);
                org.dolphinemu.ishiiruka.NativeLibrary.NesStationHost
                                .notifyPanicAlert("Ishiiruka", message == null ? "" : message, false);
        }

        /** 模拟结束回调。签名 ()V。 */
        public static void endEmulationActivity()
        {
                android.util.Log.i("DolphinNative", "endEmulationActivity");
                try
                {
                        StopEmulation();
                }
                catch (Throwable t)
                {
                        android.util.Log.e("DolphinNative", "endEmulationActivity: " + t);
                }
        }

        // ------------------------------------------------------------------
        // native 方法（so 导出符号存在，短名自动绑定；参数与 vendored 旧类一致）
        // ------------------------------------------------------------------

        public static native boolean onGamePadEvent(String Device, int Button, int Action);
        public static native void onGamePadMoveEvent(String Device, int Axis, float Value);

        public static native void SetUserSetting(String gameID, String Section, String Key, String Value);
        public static native void SetConfig(String configFile, String Section, String Key, String Value);

        public static native String GetVersionString();
        public static native String GetGitRevision();

        public static native void SaveScreenShot();
        public static native void SaveState(int slot, boolean wait);
        public static native void SaveStateAs(String path, boolean wait);
        public static native void LoadState(int slot);
        public static native void LoadStateAs(String path);

        public static native void SetUserDirectory(String directory);
        public static native String GetUserDirectory();

        public static native int DefaultCPUCore();
        public static native void Run(String path);
        public static native void ChangeDisc(String path);

        public static native void SurfaceChanged(Surface surf);
        public static native void SurfaceDestroyed();

        public static native void UnPauseEmulation();
        public static native void PauseEmulation();
        public static native void StopEmulation();
        public static native boolean IsRunning();

        public static native void SetProfiling(boolean enable);
        public static native void WriteProfileResults();
        public static native void eglBindAPI(int api);
        public static native void RefreshWiimotes();

        // ------------------------------------------------------------------
        // Java 兜底（so 未导出这些符号；保留旧 API 形状防 UnsatisfiedLinkError）
        // ------------------------------------------------------------------

        public static String GetUserSetting(String gameID, String Section, String Key)
        {
                return "";
        }

        public static void InitGameIni(String gameID)
        {
        }

        public static String GetConfig(String configFile, String Section, String Key, String Default)
        {
                return Default;
        }

        public static int[] GetBanner(String filename)
        {
                return null;
        }

        public static String GetTitle(String filename)
        {
                return "";
        }

        public static String GetDescription(String filename)
        {
                return "";
        }

        public static String GetGameId(String filename)
        {
                return "";
        }

        public static int GetCountry(String filename)
        {
                return 0;
        }

        public static String GetCompany(String filename)
        {
                return "";
        }

        public static long GetFilesize(String filename)
        {
                return 0L;
        }

        public static int GetPlatform(String filename)
        {
                return 0;
        }

        /** 上游老 API：老版本经此缓存 JNI 方法 ID。本 so 无此符号，空实现即可。 */
        public static void CacheClassesAndMethods()
        {
        }

        /** 三参 Run 为旧 API 重载形态；本 so 仅导出单参 Run（非重载短名绑定）。 */
        public static void Run(String path, String savestatePath, boolean deleteSavestate)
        {
                Run(path);
        }
}
