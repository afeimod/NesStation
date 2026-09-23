package org.dolphinemu.dolphinemu.utils;

import android.content.Context;

import org.dolphinemu.dolphinemu.DolphinHost;
import org.dolphinemu.dolphinemu.NativeLibrary;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Dolphin 用户/系统目录初始化 —— 与上游 DirectoryInitialization 的 native
 * 契约一致：private static native SetSysDirectory(String) + CreateUserDirectories()。
 *
 * NesStation 流程（DolphinInitializer 执行）：
 *  1. 解包 assets/dolphin/Sys/** → <userDir>/Sys/（GC dsp/fonts、Wii 系统数据）
 *  2. SetUserDirectory(<userDir>)
 *  3. SetSysDirectory(<userDir>/Sys)
 *  4. CreateUserDirectories()  → 生成 Config/GC/Wii/Load/Cache/StateSaves 等
 *  5. 首次启动播种 assets/dolphin/{GCPadNew,WiimoteNew,WiimoteProfile}.ini
 *     → <userDir>/Config/（触摸屏输入 id 映射与核心一致）
 */
public final class DirectoryInitialization {

    /** private static native —— 上游契约。 */
    private static native void SetSysDirectory(String path);

    /** private static native —— 上游契约。 */
    private static native void CreateUserDirectories();

    private DirectoryInitialization() {
    }

    /** 完整初始化（引擎 ensureDirectories 调用）。 */
    public static boolean initialize(String userDir, Context context) {
        try {
            File user = new File(userDir);
            if (!user.exists() && !user.mkdirs()) return false;
            File sys = new File(user, "Sys");
            extractAssets(context, "dolphin", user);
            NativeLibrary.SetUserDirectory(user.getAbsolutePath());
            SetSysDirectory(sys.getAbsolutePath());
            CreateUserDirectories();
            seedControllerInis(context, user);
            return true;
        } catch (Throwable t) {
            android.util.Log.e("DolphinHost", "DirectoryInitialization failed", t);
            return false;
        }
    }

    /** 上游 b(Context)：异步初始化占位（NesStation 用同步 initialize）。 */
    public static void start(Context context) {
        DolphinHost.ensureLibraryLoaded();
    }

    /** 上游 a()：是否已初始化。 */
    public static boolean areDirectoriesReady() {
        return DolphinHost.isLibraryLoaded();
    }

    private static void seedControllerInis(Context context, File userDir) {
        File config = new File(userDir, "Config");
        if (!config.exists()) config.mkdirs();
        copyAssetIfMissing(context, "dolphin/GCPadNew.ini", new File(config, "GCPadNew.ini"));
        copyAssetIfMissing(context, "dolphin/WiimoteNew.ini", new File(config, "WiimoteNew.ini"));
        copyAssetIfMissing(context, "dolphin/WiimoteProfile.ini", new File(config, "WiimoteProfile.ini"));
    }

    private static void extractAssets(Context context, String assetRoot, File targetRoot) {
        try {
            String[] entries = context.getAssets().list(assetRoot);
            if (entries == null) return;
            for (String entry : entries) {
                String assetPath = assetRoot + "/" + entry;
                File target = new File(targetRoot, entry);
                if (assetPath.equals("dolphin/GCPadNew.ini")
                    || assetPath.equals("dolphin/WiimoteNew.ini")
                    || assetPath.equals("dolphin/WiimoteProfile.ini")) {
                    // 控制器 INI 走 seedControllerInis（只在 Config 缺失时播种）
                    continue;
                }
                if (entries.length > 0 && isDirectory(context, assetPath)) {
                    if (!target.exists()) target.mkdirs();
                    extractAssets(context, assetPath, target);
                } else {
                    copyAssetIfMissing(context, assetPath, target);
                }
            }
        } catch (IOException t) {
            android.util.Log.w("DolphinHost", "extractAssets " + assetRoot, t);
        }
    }

    private static boolean isDirectory(Context context, String path) {
        try {
            String[] children = context.getAssets().list(path);
            return children != null && children.length > 0;
        } catch (IOException t) {
            return false;
        }
    }

    private static void copyAssetIfMissing(Context context, String assetPath, File target) {
        if (target.exists() && target.length() > 0) return;
        File parent = target.getParentFile();
        if (parent != null && !parent.exists()) parent.mkdirs();
        InputStream in = null;
        OutputStream out = null;
        try {
            in = context.getAssets().open(assetPath);
            out = new FileOutputStream(target);
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        } catch (IOException t) {
            android.util.Log.w("DolphinHost", "copyAsset " + assetPath, t);
        } finally {
            try {
                if (in != null) in.close();
            } catch (IOException ignored) {
            }
            try {
                if (out != null) out.close();
            } catch (IOException ignored) {
            }
        }
    }
}
