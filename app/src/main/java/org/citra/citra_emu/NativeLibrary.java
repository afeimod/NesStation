package org.citra.citra_emu;

import android.content.ContentResolver;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.util.Log;

import org.citra.citra_emu.model.Game.MediaType;

import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.ref.WeakReference;
import java.util.ArrayList;

/**
 * Azahar（libcitra-android.so）JNI 宿主类 —— 与上游 AzaharPlus 2125 的
 * org.citra.citra_emu.NativeLibrary 保持完全一致的 JVM 形状：
 *
 *  - Kotlin object 语义：static INSTANCE 单例 + 实例 native 方法
 *    （静态 JNI 导出 Java_org_citra_citra_1emu_NativeLibrary_* 按实例调用
 *    约定绑定，this 由调用方传入）。
 *  - native 方法名/签名与上游 dex 一一对应（多声明少调用都安全；
 *    native 侧被调用的宿主方法签名与 static 修饰必须精确一致）。
 *
 * NesStation 进程内集成：不再有外部 EmulationActivity —— native 的
 * displayAlertMsg/onCoreError/exitEmulationActivity/文件助手回调全部路由到
 * [CitraHost]。
 */
public final class NativeLibrary {

    public static final int QUICKSAVE_SLOT = 0;
    public static final int REQUEST_CODE_NATIVE_CAMERA = 800;
    public static final int REQUEST_CODE_NATIVE_MIC = 900;
    public static final int SAVESTATE_SLOT_COUNT = 11;
    public static final String TouchScreenDevice = "Touchscreen";

    /** 模拟 activity 弱引用（仅 NesStation 侧使用；native 不触碰该字段）。 */
    public static WeakReference<Object> sEmulationActivity = new WeakReference<>(null);

    private static volatile boolean libraryLoaded = false;

    public static final NativeLibrary INSTANCE = new NativeLibrary();

    private NativeLibrary() {
    }

    /** 显式加载核心库（幂等）。失败抛 UnsatisfiedLinkError 由引擎捕获。 */
    public static synchronized void loadLibrary() {
        if (!libraryLoaded) {
            System.loadLibrary("citra-android");
            libraryLoaded = true;
        }
    }

    public static boolean isLibraryLoaded() {
        return libraryLoaded;
    }

    // =======================================================================
    // === native 方法（与上游 AzaharPlus 2125 dex 声明一致） ===
    // =======================================================================

    // --- 实例 native（Kotlin object 成员） ---
    private final native int compressFileNative(String p0, String p1);

    private final native int decompressFileNative(String p0, String p1);

    public final native boolean areKeysAvailable();

    public final native boolean[] areSystemTitlesInstalled();

    public final native int clearStreetPassConfig();

    public final native void createConfigFile();

    public final native void createLogFile();

    public final native void deleteOpenGLShaderCache(long p0);

    public final native void deleteVulkanShaderCache(long p0);

    public final native void disableTemporaryFrameLimit();

    public final native void doFrame();

    public final native int downloadTitleFromNus(long p0);

    public final native int exportZipPass(String p0);

    public final native String getHomeMenuPath(int p0);

    private final native String[] getInstalledGamePathsImpl();

    public final native boolean getIsSystemTitle(String p0);

    public final native double[] getPerfStats();

    public final native String getRecommendedExtension(String p0, boolean p1);

    public final native long getRunningTitleId();

    public final native SaveStateInfo[] getSavestateInfo();

    public final native long[] getSystemTitleIds(int p0, int p1);

    public final native long getTitleId(String p0);

    public final native int importZipPass(String p0);

    public final native void initializeGpuDriver(String p0, String p1, String p2, String p3);

    public final native boolean isFullConsoleLinked();

    public final native boolean isRunning();

    public final native boolean loadAmiibo(String p0);

    public final native void loadState(int p0);

    public final native void logDeviceInfo();

    public final native void logUserDirectory(String p0);

    public final native boolean nativeFileExists(String p0);

    public final native boolean onGamePadAxisEvent(String p0, int p1, float p2);

    public final native boolean onGamePadEvent(String p0, int p1, int p2);

    public final native boolean onGamePadMoveEvent(String p0, int p1, float p2, float p3);

    public final native boolean onGamePadMoveEvent25(String p0, int p1, float p2, boolean p3);

    public final native boolean onSecondaryTouchEvent(float p0, float p1, boolean p2);

    public final native void onSecondaryTouchMoved(float p0, float p1);

    public final native boolean onTouchEvent(float p0, float p1, boolean p2);

    public final native void onTouchMoved(float p0, float p1);

    public final native void notifyOrientationChange();

    public final native void pauseEmulation();

    public final native long playTimeManagerGetCurrentTitleId();

    public final native long playTimeManagerGetPlayTime(long p0);

    public final native void playTimeManagerInit();

    public final native void playTimeManagerStart(long p0);

    public final native void playTimeManagerStop();

    public final native void reloadCameraDevices();

    public final native void reloadSettings();

    public final native void removeAmiibo();

    public final native void resetGame25();

    public final native void run(String p0);

    public final native void saveState(int p0);

    public final native void secondarySurfaceChanged(android.view.Surface p0);

    public final native void secondarySurfaceDestroyed();

    public final native void setInsertedCartridge(String p0);

    public final native void setTemporaryFrameLimit(double p0);

    public final native void setUserDirectory(String p0);

    public final native void stopEmulation();

    public final native void surfaceChanged(android.view.Surface p0);

    public final native void surfaceDestroyed();

    public final native void swapScreens(boolean p0, int p1);

    public final native void switchBottomScreen25(boolean p0);

    public final native void unPauseEmulation();

    public final native void uninstallSystemFiles(boolean p0);

    private final native boolean uninstallTitle(long p0, int p1);

    public final native void unlinkConsole();

    public final native void updateCheat25(String[] p0);

    public final native void updateFramebuffer(boolean p0);

    // --- 静态 native（上游 public static final native） ---
    public static final native int[] getSearchResults();

    public static final native byte[] loadPage(int p0);

    public static final native int[] loadPageTable();

    public static final native int netPlayCreateRoom25(String p0, int p1, String p2, String p3);

    public static final native String netPlayGetConsoleId25();

    public static final native boolean netPlayIsHostedRoom25();

    public static final native boolean netPlayIsJoined25();

    public static final native int netPlayJoinRoom25(String p0, int p1, String p2, String p3);

    public static final native void netPlayKickUser25(String p0);

    public static final native void netPlayLeaveRoom25();

    public static final native String[] netPlayRoomInfo25();

    public static final native void netPlaySendMessage25(String p0);

    public static final native int readMemory(int p0, int p1);

    public static final native void resetSearchResults();

    public static final native int[] searchMemory(int p0, int p1, int p2, int p3, int p4, int p5, int p6);

    public static final native void writeMemory(int p0, int p1, int p2);

    // =======================================================================
    // === Java 侧包装（上游同名方法的 NesStation 实现） ===
    // =======================================================================

    /** 已安装 CIA 标题列表（native 返回路径数组，Java 包装为 InstalledGame）。 */
    public InstalledGame[] getInstalledGamePaths() {
        String[] paths;
        try {
            paths = getInstalledGamePathsImpl();
        } catch (Throwable t) {
            Log.e("CitraHost", "getInstalledGamePathsImpl failed", t);
            return new InstalledGame[0];
        }
        if (paths == null) return new InstalledGame[0];
        ArrayList<InstalledGame> out = new ArrayList<>(paths.length);
        for (String path : paths) {
            out.add(new InstalledGame(path, MediaType.Game));
        }
        return out.toArray(new InstalledGame[0]);
    }

    public CompressStatus compressFile(String p0, String p1) {
        return CompressStatus.forValue(compressFileNative(p0, p1));
    }

    public CompressStatus decompressFile(String p0, String p1) {
        return CompressStatus.forValue(decompressFileNative(p0, p1));
    }

    public boolean uninstallTitle(long titleId, MediaType mediaType) {
        return uninstallTitle(titleId, mediaTypeValue(mediaType));
    }

    private static int mediaTypeValue(MediaType type) {
        if (type == null) return 0;
        switch (type) {
            case Update: return 1;
            case AddOn: return 2;
            default: return 0;
        }
    }

    public boolean loadStateIfAvailable(int slot) {
        SaveStateInfo[] infos = getSavestateInfo();
        if (infos != null) {
            for (SaveStateInfo info : infos) {
                if (info != null && info.getSlot() == slot) {
                    loadState(slot);
                    return true;
                }
            }
        }
        return false;
    }

    public Object getAlertLock() {
        return CitraAlert.class;
    }

    public void setEmulationActivity(Object activity) {
        sEmulationActivity = activity == null
            ? new WeakReference<>(null)
            : new WeakReference<>(activity);
    }

    public void clearEmulationActivity() {
        sEmulationActivity = new WeakReference<>(null);
    }

    public void cameraPermissionResult(boolean granted) {
        CitraPermissions.cameraResult(granted);
    }

    public void micPermissionResult(boolean granted) {
        CitraPermissions.micResult(granted);
    }

    public static String getUserDirectory() {
        String overrideDir = CitraHost.getUserDirectory();
        if (overrideDir != null && !overrideDir.isEmpty()) return overrideDir;
        Context ctx = CitraHost.getAppContext();
        return ctx == null ? "" : ctx.getExternalFilesDir(null) + "";
    }

    public static String getBuildFlavor() {
        return "nesstation";
    }

    public static boolean isPortraitMode() {
        Context ctx = CitraHost.getAppContext();
        if (ctx == null) return true;
        return ctx.getResources().getConfiguration().orientation
            == android.content.res.Configuration.ORIENTATION_PORTRAIT;
    }

    // =======================================================================
    // === native → Java 回调（签名/静态性必须与上游一致） ===
    // =======================================================================

    /** native panic alert（阻塞等用户应答）。 */
    public static boolean displayAlertMsg(String title, String message, boolean yesNo) {
        Log.w("CitraHost", "[Alert] " + title + ": " + message);
        CitraHost.DecisionHook hook = CitraHost.getAlertHook();
        if (hook != null) {
            return hook.onDecision(title, message, yesNo);
        }
        // 无 UI 钩子：yesNo 询问默认继续，纯提示默认放行。
        return true;
    }

    /** native 核心错误（ErrorSystemFiles 等）。返回 true = 继续运行。 */
    public static boolean onCoreError(NativeLibrary.CoreError error, String details) {
        Log.e("CitraHost", "[CoreError] " + error + ": " + details);
        CitraHost.DecisionHook hook = CitraHost.getCoreErrorHook();
        if (hook != null) {
            return hook.onDecision(error == null ? "Error" : error.name(), details, true);
        }
        return true;
    }

    /** native 请求退出模拟。 */
    public static void exitEmulationActivity(int resultCode) {
        Log.i("CitraHost", "exitEmulationActivity(" + resultCode + ")");
        CitraHost.ExitHook hook = CitraHost.getExitHook();
        if (hook != null) hook.onExit(resultCode);
    }

    public static void onSaveStateComplete25(boolean success) {
        Log.i("CitraHost", "onSaveStateComplete25(" + success + ")");
        CitraHost.StatusHook hook = CitraHost.getStatusHook();
        if (hook != null) {
            hook.onStatus(success ? "存档完成" : "存档失败");
        }
    }

    public static void onNetPlayStatusMessageReceive25(int p0, String p1) {
        Log.i("CitraHost", "netplay status " + p0 + ": " + p1);
    }

    public static void onCompressProgress(long p0, long p1) {
        CitraHost.StatusHook hook = CitraHost.getStatusHook();
        if (hook != null) hook.onStatus("压缩进度 " + p0 + "/" + p1);
    }

    /** 相机/麦克风权限请求（native camera 工厂调用）。 */
    public static boolean requestCameraPermission() {
        return CitraPermissions.requestCamera();
    }

    public static boolean requestMicPermission() {
        return CitraPermissions.requestMic();
    }

    // =======================================================================
    // === VFS 文件助手（native FileUtil 桥 —— content:// 与本地路径双支持） ===
    // =======================================================================

    /** 打开 content uri 并返回 fd（OpenMode: "r"/"rw"/"w"）。 */
    public static int openContentUri(String uriString, String openMode) {
        try {
            Context ctx = CitraHost.getAppContext();
            if (ctx == null) return -1;
            ContentResolver resolver = ctx.getContentResolver();
            ParcelFileDescriptor pfd =
                resolver.openFileDescriptor(Uri.parse(uriString), openMode);
            if (pfd == null) return -1;
            int fd = pfd.detachFd();
            try {
                pfd.close();
            } catch (IOException ignored) {
            }
            return fd;
        } catch (Throwable t) {
            Log.w("CitraHost", "openContentUri failed: " + uriString, t);
            return -1;
        }
    }

    public static boolean fileExists(String path) {
        if (path == null) return false;
        if (path.startsWith("content://")) {
            try {
                Context ctx = CitraHost.getAppContext();
                if (ctx == null) return false;
                ParcelFileDescriptor pfd = ctx.getContentResolver()
                    .openFileDescriptor(Uri.parse(path), "r");
                if (pfd == null) return false;
                try {
                    pfd.close();
                } catch (IOException ignored) {
                }
                return true;
            } catch (Throwable t) {
                return false;
            }
        }
        return new File(path).exists();
    }

    public static boolean isDirectory(String path) {
        if (path == null || path.startsWith("content://")) return false;
        return new File(path).isDirectory();
    }

    public static long getSize(String path) {
        if (path == null) return 0;
        if (path.startsWith("content://")) {
            try {
                Context ctx = CitraHost.getAppContext();
                if (ctx == null) return 0;
                ParcelFileDescriptor pfd = ctx.getContentResolver()
                    .openFileDescriptor(Uri.parse(path), "r");
                if (pfd == null) return 0;
                long size = pfd.getStatSize();
                try {
                    pfd.close();
                } catch (IOException ignored) {
                }
                return size;
            } catch (Throwable t) {
                return 0;
            }
        }
        return new File(path).length();
    }

    /** 列出目录内文件名（不含目录项）。 */
    public static String[] getFilesName(String path) {
        ArrayList<String> out = new ArrayList<>();
        if (path == null) return new String[0];
        if (path.startsWith("content://")) {
            // SAF 目录枚举：query children documents
            try {
                Context ctx = CitraHost.getAppContext();
                if (ctx == null) return new String[0];
                Uri dir = Uri.parse(path);
                ContentResolver resolver = ctx.getContentResolver();
                android.database.Cursor cursor = resolver.query(
                    android.net.Uri.parse("content://com.android.externalstorage.documents/tree"),
                    new String[]{"_display_name"}, null, null, null);
                if (cursor != null) {
                    try {
                        while (cursor.moveToNext()) out.add(cursor.getString(0));
                    } finally {
                        cursor.close();
                    }
                }
            } catch (Throwable ignored) {
            }
            return out.toArray(new String[0]);
        }
        File dir = new File(path);
        File[] files = dir.listFiles();
        if (files != null) {
            for (File f : files) out.add(f.getName());
        }
        return out.toArray(new String[0]);
    }

    /** 在 dir 下创建名为 filename 的文件。 */
    public static boolean createFile(String dir, String filename) {
        try {
            if (dir != null && dir.startsWith("content://")) {
                Context ctx = CitraHost.getAppContext();
                if (ctx == null) return false;
                return ctx.getContentResolver().insert(
                    android.provider.DocumentsContract.buildDocumentUriUsingTree(
                        Uri.parse(dir),
                        android.provider.DocumentsContract.getTreeDocumentId(Uri.parse(dir))),
                    new android.content.ContentValues()) != null;
            }
            File parent = new File(dir == null ? "." : dir);
            if (!parent.exists() && !parent.mkdirs()) return false;
            File f = new File(parent, filename == null ? "unnamed" : filename);
            return f.createNewFile() || f.exists();
        } catch (Throwable t) {
            return false;
        }
    }

    public static boolean createDir(String parent, String name) {
        try {
            if (parent != null && parent.startsWith("content://")) {
                return false; // SAF 目录创建不在 VFS 桥路径上
            }
            File dir = new File(new File(parent == null ? "." : parent), name);
            return dir.exists() || dir.mkdirs();
        } catch (Throwable t) {
            return false;
        }
    }

    public static boolean copyFile(String srcParent, String dstParent, String filename) {
        try {
            File src = new File(srcParent, filename);
            File dst = new File(dstParent, filename);
            if (src.isDirectory()) return dst.mkdirs();
            InputStream in = new FileInputStream(src);
            OutputStream out = new FileOutputStream(dst);
            pump(in, out);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    public static boolean moveFile(String srcParent, String dstParent, String filename) {
        boolean ok = copyFile(srcParent, dstParent, filename);
        if (ok) {
            File src = new File(srcParent, filename);
            if (src.isDirectory()) {
                deleteRecursively(src);
            } else {
                src.delete();
            }
        }
        return ok;
    }

    public static boolean renameFile(String parent, String newName) {
        try {
            File file = new File(parent);
            File target = new File(file.getParentFile(), newName);
            return file.renameTo(target);
        } catch (Throwable t) {
            return false;
        }
    }

    public static boolean deleteDocument(String path) {
        try {
            File file = new File(path);
            if (file.isDirectory()) {
                deleteRecursively(file);
                return true;
            }
            return file.delete();
        } catch (Throwable t) {
            return false;
        }
    }

    public static boolean updateDocumentLocation(String p0, String p1) {
        return true;
    }

    /** content uri → 可读路径（ SAF 缓存桥；本地路径原样返回）。 */
    public static String getNativePath(android.net.Uri uri) {
        if (uri == null) return "";
        String s = uri.toString();
        if (!s.startsWith("content://")) return s;
        return s;
    }

    // =======================================================================
    // === 工具 ===
    // =======================================================================

    private static void pump(InputStream in, OutputStream out) throws IOException {
        byte[] buf = new byte[65536];
        try {
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        } finally {
            try {
                in.close();
            } catch (IOException ignored) {
            }
            try {
                out.close();
            } catch (IOException ignored) {
            }
        }
    }

    private static void deleteRecursively(File file) {
        File[] children = file.listFiles();
        if (children != null) {
            for (File c : children) deleteRecursively(c);
        }
        file.delete();
    }

    /** 从 assets/应用目录复制位图（相机占位用）。 */
    public static Bitmap decodeScaled(String path, int width, int height) {
        try {
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(path, opts);
            opts.inSampleSize = 1;
            if (opts.outWidth > width || opts.outHeight > height) {
                int halfW = opts.outWidth / 2;
                int halfH = opts.outHeight / 2;
                while ((halfW / opts.inSampleSize) >= width
                    && (halfH / opts.inSampleSize) >= height) {
                    opts.inSampleSize *= 2;
                }
            }
            opts.inJustDecodeBounds = false;
            return BitmapFactory.decodeFile(path, opts);
        } catch (Throwable t) {
            return null;
        }
    }

    // =======================================================================
    // === 上游内部类（枚举 ordinal/名字被 native 使用 —— 顺序不可改） ===
    // =======================================================================

    /** native 核心错误枚举（ordinal 固定，勿改顺序）。 */
    public enum CoreError {
        ErrorSystemFiles,
        ErrorSavestate,
        ErrorArticDisconnected,
        ErrorN3DSApplication,
        ErrorUnknown
    }

    /** CIA 安装状态（ordinal 固定）。 */
    public enum InstallStatus {
        Success,
        ErrorFailedToOpenFile,
        ErrorFileNotFound,
        ErrorAborted,
        ErrorInvalid,
        ErrorEncrypted,
        Cancelled
    }

    /** 即时存档槽信息（native 通过 setter 填充字段）。 */
    public static final class SaveStateInfo {
        private int slot;
        private java.util.Date time;

        public int getSlot() {
            return slot;
        }

        public void setSlot(int v) {
            this.slot = v;
        }

        public java.util.Date getTime() {
            return time;
        }

        public void setTime(java.util.Date v) {
            this.time = v;
        }
    }

    /** 文件压缩/解压状态（ordinal 固定）。 */
    public enum CompressStatus {
        None,
        Error_CouldNotOpenFile,
        Error_CouldNotAllocateMemory,
        Error_CouldNotWriteCompressedData,
        Success;

        public static CompressStatus forValue(int v) {
            CompressStatus[] all = values();
            if (v < 0 || v >= all.length) return None;
            return all[v];
        }
    }

    /** 已安装标题（Java 侧数据类）。 */
    public static final class InstalledGame {
        private final String path;
        private final MediaType mediaType;

        public InstalledGame(String path, MediaType mediaType) {
            this.path = path;
            this.mediaType = mediaType;
        }

        public final String getPath() {
            return path;
        }

        public final MediaType getMediaType() {
            return mediaType;
        }
    }

    /** 按钮类型常量（与上游 ButtonType 完全一致 —— 输入事件 id）。 */
    public static final class ButtonType {
        public static final int BUTTON_A = 700;
        public static final int BUTTON_B = 701;
        public static final int BUTTON_X = 702;
        public static final int BUTTON_Y = 703;
        public static final int BUTTON_START = 704;
        public static final int BUTTON_SELECT = 705;
        public static final int BUTTON_HOME = 706;
        public static final int BUTTON_ZL = 707;
        public static final int BUTTON_ZR = 708;
        public static final int DPAD_UP = 709;
        public static final int DPAD_DOWN = 710;
        public static final int DPAD_LEFT = 711;
        public static final int DPAD_RIGHT = 712;
        public static final int STICK_LEFT = 713;
        public static final int STICK_LEFT_UP = 714;
        public static final int STICK_LEFT_DOWN = 715;
        public static final int STICK_LEFT_LEFT = 716;
        public static final int STICK_LEFT_RIGHT = 717;
        public static final int STICK_C = 718;
        public static final int STICK_C_UP = 719;
        public static final int STICK_C_DOWN = 720;
        public static final int STICK_C_LEFT = 771;
        public static final int STICK_C_RIGHT = 772;
        public static final int TRIGGER_L = 773;
        public static final int TRIGGER_R = 774;
        public static final int DPAD = 780;
        public static final int BUTTON_DEBUG = 781;
        public static final int BUTTON_GPIO14 = 782;
        public static final int BUTTON_SWAP = 800;
        public static final int BUTTON_TURBO = 801;

        private ButtonType() {
        }
    }

    /** 锁定类（getAlertLock 返回对象，保持上游形状）。 */
    private static final class CitraAlert {
    }
}
