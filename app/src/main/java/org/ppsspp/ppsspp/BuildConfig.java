package org.ppsspp.ppsspp;

/**
 * NesStation 集成注：官方 PPSSPP 工程中本类由 Gradle（AGP buildConfig 特性）
 * 在 org.ppsspp.ppsspp 包下自动生成 —— 官方的 applicationId 恰好同为
 * org.ppsspp.ppsspp，因此源码里可以裸引用 BuildConfig。
 *
 * 宿主 NesStation 的 namespace/applicationId 是 com.nesstation.app
 * （app/build.gradle.kts:23/27），AGP 只会在 com.nesstation.app 包下生成
 * BuildConfig，不会在 org.ppsspp.ppsspp 下生成，于是 PpssppActivity
 * 的 BuildConfig.FLAVOR 引用报 "package BuildConfig does not exist"。
 *
 * 本手工版与官方生成版字段一一对应，语义对齐官方普通（非 VR）构建：
 *   - FLAVOR 固定 "normal" → PpssppActivity.isVRDevice() 恒 false，
 *     与官方 Play 商店普通版行为一致（该值仅此一处消费）。
 *   - 版本号取实际集成的 PPSSPP v1.20.4（jniLibs 内 libppsspp_jni.so
 *     与官方 1.20.4 APK 完全同 sha256）。
 *   - DEBUG 恒 false：本包无任何 DEBUG 消费点，release/debug 行为一致。
 */
public final class BuildConfig {
    public static final boolean DEBUG = false;
    public static final String APPLICATION_ID = "com.nesstation.app";
    public static final String BUILD_TYPE = "release";
    public static final String FLAVOR = "normal";
    public static final int VERSION_CODE = 12004;
    public static final String VERSION_NAME = "1.20.4";

    private BuildConfig() {
        // 纯常量类，禁止实例化（与 AGP 生成版一致）
    }
}
