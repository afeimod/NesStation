package org.dolphinemu.dolphinemu;

import android.view.Surface;

/**
 * Ishiiruka（NGC/Wii）核心 —— JNI 契约类（dolphinemu 包名）。
 *
 * 【为什么必须有这个类】libishiiruka.so 是预编译二进制，其 JNI_OnLoad 内
 *   FindClass("org/dolphinemu/dolphinemu/NativeLibrary")，
 *   且全部导出符号按 Java_org_dolphinemu_dolphinemu_NativeLibrary_* 绑定
 *   （BuildId 9aa1cc68f0c7c917b27cbb643e859c2842c066c4，反汇编实测）。
 *   原 vendored 类 org.dolphinemu.ishiiruka.NativeLibrary 包名不匹配，导致
 *   JNI_OnLoad 中 GetStaticMethodID 查不到方法抛 NoSuchMethodError，
 *   pending exception 未被 native 侧检查，下一次 FindClass 触发 ART 断言
 *   （AssertNoPendingException）→ SIGABRT 启动闪退。
 *
 * 【JNI_OnLoad 契约（反汇编 0xc60e8 实测，另有 Run 路径懒初始化副本 0xc5e30）】
 *   本类上 GetStaticMethodID 缓存三个静态方法，签名必须精确匹配：
 *     1. displayAlertMsg (Ljava/lang/String;Ljava/lang/String;Z)Z —— 三参！
 *        （旧版误写为单参 (Ljava/lang/String;)V，即本次启动闪退根因）
 *     2. rumble          (ID)V
 *     3. updateWindowSize (II)V
 *   任何签名/名字不匹配 → NoSuchMethodError → pending exception → SIGABRT。
 *
 * 【职责】
 *   1. static 块加载 libishiiruka.so（触发 JNI_OnLoad，此时本类已可被 FindClass 命中）；
 *   2. 提供原生层反向回调 displayAlertMsg / rumble / updateWindowSize；
 *   3. 声明 so 导出符号对应的 native 方法（符号自动绑定）。
 *      ⚠ Run 原生实现实为两参 (jstring path, jstring savestatePath)（反汇编
 *      0xd01d0 实测：x2、x3 均经 jstring→std::string 转换，x4 未读），
 *      Java 侧必须声明 ≥2 个 String 参数 —— 单参会读到垃圾指针。
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
        // 库加载（JNI_OnLoad 入口：FindClass 本类 + GetStaticMethodID 缓存
        // displayAlertMsg / rumble / updateWindowSize 三个回调，签名见类注释）
        // ------------------------------------------------------------------
        static
        {
                System.loadLibrary("ishiiruka");
        }

        // ------------------------------------------------------------------
        // 原生层反向回调（JNI_OnLoad GetStaticMethodID 锁定，签名必须精确）
        // ------------------------------------------------------------------

        /**
         * MsgAlert 弹窗回调。
         * 签名 (Ljava/lang/String;Ljava/lang/String;Z)Z：caption / text / yesNo。
         * yesNo=false 时为纯告警，返回值无意义；yesNo=true 时返回是否选择“是”。
         * NesStation 最小实现：记录日志并通知宿主，按“否”返回（不继续危险操作）。
         */
        public static boolean displayAlertMsg(final String caption, final String text,
                                              final boolean yesNo)
        {
                android.util.Log.e("DolphinNative",
                                "[NativeLibrary] Alert: " + caption + ": " + text);
                org.dolphinemu.ishiiruka.NativeLibrary.NesStationHost
                                .notifyPanicAlert("Ishiiruka",
                                                (caption == null ? "" : caption) + ": "
                                                                + (text == null ? "" : text),
                                                yesNo);
                return false;
        }

        /** 手柄震动回调。签名 (ID)V（device / strength，均为 libishiiruka 按名缓存）。
         *  NesStation 触觉反馈不透传到宿主，留日志桩。 */
        public static void rumble(int device, double strength)
        {
                android.util.Log.d("DolphinNative",
                                "[NativeLibrary] rumble device=" + device
                                                + " strength=" + strength);
        }

        /** 渲染窗口尺寸变化回调。签名 (II)V（width / height）。 */
        public static void updateWindowSize(int width, int height)
        {
                android.util.Log.d("DolphinNative",
                                "[NativeLibrary] updateWindowSize " + width + "x" + height);
        }

        /** 模拟结束回调（非 JNI_OnLoad 契约，仅 Java 侧兼容门面，so 并不按名查找）。 */
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

        /**
         * 开始模拟。原生实现实际读取两个 jstring（path / savestatePath，反汇编
         * 0xd01d0 实测），第三个参数（若上游有 boolean deleteSavestate）原生侧
         * 未读 —— 声明三参形态兼容上游 API 形状，且保证原生侧 x2/x3 不读到
         * 垃圾指针。无存档启动传 ""（空串）而非 null，避免 JNI 字符串转换空指针。
         */
        public static native void Run(String path, String savestatePath, boolean deleteSavestate);

        /** 单参便捷入口：无存档启动（等价于上游默认路径）。 */
        public static void Run(String path)
        {
                Run(path, "", false);
        }

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
}
