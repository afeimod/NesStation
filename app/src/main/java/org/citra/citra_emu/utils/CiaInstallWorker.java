package org.citra.citra_emu.utils;

import org.citra.citra_emu.NativeLibrary;

/**
 * CIA 安装入口 —— 与上游 org.citra.citra_emu.utils.CiaInstallWorker 保持一致的
 * JVM 形状：installCIA 为实例 native 方法
 * （Java_org_citra_citra_1emu_utils_CiaInstallWorker_installCIA 静态 JNI 导出）。
 *
 * 上游是 CoroutineWorker；NesStation 直接持有单例并在 IO 线程调用。
 * 装游戏界面见 com.nesstation.app.core.storage.CiaInstaller（批量导入 +
 * 进度 UI）。注意：安装加密 CIA 需要先导入 aes_keys.txt（3DS 解密密钥），
 * 否则 native 返回 ErrorEncrypted。
 */
public final class CiaInstallWorker {

    private static final CiaInstallWorker INSTANCE = new CiaInstallWorker();

    private CiaInstallWorker() {
    }

    public static CiaInstallWorker get() {
        return INSTANCE;
    }

    /**
     * 安装 CIA（阻塞调用，切后台线程执行）。
     * 返回 NativeLibrary.InstallStatus（Success / ErrorEncrypted / ...）。
     */
    private final native NativeLibrary.InstallStatus installCIA(String path);

    /** 公开包装：加载库后调用。 */
    public NativeLibrary.InstallStatus install(String path) {
        try {
            return installCIA(path);
        } catch (Throwable t) {
            android.util.Log.e("CitraHost", "installCIA failed", t);
            return NativeLibrary.InstallStatus.ErrorFailedToOpenFile;
        }
    }
}
