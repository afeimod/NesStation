package org.citra.citra_emu.utils;

/**
 * Azahar 日志桥 —— 与上游 org.citra.citra_emu.utils.Log（Kotlin object）
 * 保持一致的 JVM 形状：static INSTANCE + 实例 native 方法
 * （Java_org_citra_citra_1emu_utils_Log_* 静态 JNI 导出）。
 *
 * native 侧只在导出方向绑定（Java→native），NesStation 自身也用这些方法
 * 把消息写进核心日志系统。
 */
public final class Log {

    public static final Log INSTANCE = new Log();

    private Log() {
    }

    public final native void debug(String message);

    public final native void info(String message);

    public final native void warning(String message);

    public final native void error(String message);

    public final native void critical(String message);

    /** 纯 Java 兜底（库未加载时可用）。 */
    public static void jinfo(String message) {
        android.util.Log.i("CitraCore", message);
    }

    public static void jerror(String message) {
        android.util.Log.e("CitraCore", message);
    }
}
