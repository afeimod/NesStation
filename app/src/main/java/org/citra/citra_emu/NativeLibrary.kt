@file:Suppress("unused", "MemberVisibilityCanBePrivate")

package org.citra.citra_emu

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.view.Surface
import androidx.annotation.Keep
import java.io.File
import java.lang.ref.WeakReference
import java.util.Date

/**
 * Azahar（3DS 模拟器核心）JNI 契约类 —— NesStation 集成移植。
 *
 * 包名 / 类名 / 方法签名与 Azahar 2125+ 官方 Android 移植
 * （src/android/app/src/main/java/org/citra/citra_emu/NativeLibrary.kt）**严格一致** ——
 * libcitra-android.so 的原生符号按 Java_org_citra_citra_emu_NativeLibrary_<method> 命名解析，
 * 同时原生侧 JNI_OnLoad 会 FindClass 本类并 GetStaticMethodID 一批回调方法
 * （见上游 src/android/app/src/main/jni/id_cache.cpp / src/common/android_utils.h 契约清单），
 * 任何改名 / 缺方法都会导致 UnsatisfiedLinkError 或库加载失败。AzaharPlus
 * （github.com/AzaharPlus/AzaharPlus）是官方 Azahar 的直接 fork，JNI 表面与本契约完全兼容。
 *
 * 库加载：System.loadLibrary("azahar") —— jniLibs 中的 libazahar.so 即上游
 * libcitra-android.so（AzaharPlus APK 内同名库，经 scripts/fetch_azahar_ishiruka_libs.sh
 * 重命名后放入）。依赖链（libc++_shared.so 等）由提取脚本一并放入 jniLibs。
 *
 * 与上游 EmulationFragment 的架构一致：
 *  1. [setUserDirectory] 设定用户目录（config/config.ini、sdmc、nand 均在其中）；
 *  2. [createConfigFile] 生成默认配置（或由宿主直接写入 config.ini 后调用 [reloadSettings]）；
 *  3. [surfaceChanged] 在 Surface 有效后调用；
 *  4. 专用线程调用 [run]（阻塞直至模拟结束）；
 *  5. UI 线程 Choreographer 每帧回调 [doFrame] 驱动呈现；
 *  6. 暂停/恢复用 [pauseEmulation]/[unPauseEmulation]，停止用 [stopEmulation]。
 *
 * 输入：按键走 [onGamePadEvent]（按钮 ID 见 [ButtonType]，700+ 系列）、摇杆走
 * [onGamePadMoveEvent]（方向轴 ID 见 [ButtonType] 的 STICK_*_UP/DOWN/LEFT/RIGHT）、
 * 触摸屏（3DS 下屏）走 [onTouchEvent]/[onTouchMoved]（视图像素坐标，由原生按布局映射）。
 *
 * NesStation 集成补丁说明（相对上游 NativeLibrary.kt）：
 *  - 移除对 EmulationActivity / CitraApplication / 原版 UI 体系的依赖，宿主为 Compose 引擎层；
 *  - 原生侧回调方法全部改为安全的最小实现（本地文件系统直读 + 日志），行为语义与上游一致；
 *  - 上游仅由原版 UI 调用的功能性 native 方法（压缩/联机/系统 Title 管理等）保留声明以维持
 *    契约完整，宿主不调用。
 */
@Keep
object NativeLibrary {

    /**
     * Default touchscreen device
     */
    const val TOUCHSCREEN_DEVICE = "Touchscreen"

    @JvmField
    var sEmulationActivity = WeakReference<Any?>(null)

    // ---- 原生方法（签名与上游逐字一致） ----

    /**
     * Handles button press events for a gamepad.
     *
     * @param device The input descriptor of the gamepad.
     * @param button Key code identifying which button was pressed.
     * @param action Mask identifying which action is happening (button pressed down, or button released).
     * @return If we handled the button press.
     */
    external fun onGamePadEvent(device: String, button: Int, action: Int): Boolean

    /**
     * Handles gamepad movement events.
     *
     * @param device The device ID of the gamepad.
     * @param axis   The axis ID
     * @param xAxis The value of the x-axis represented by the given ID.
     * @param yAxis The value of the y-axis represented by the given ID
     */
    external fun onGamePadMoveEvent(device: String, axis: Int, xAxis: Float, yAxis: Float): Boolean

    /**
     * Handles gamepad movement events.
     *
     * @param device   The device ID of the gamepad.
     * @param axisId  The axis ID
     * @param axisVal The value of the axis represented by the given ID.
     */
    external fun onGamePadAxisEvent(device: String?, axisId: Int, axisVal: Float): Boolean

    /**
     * Handles touch events.
     *
     * @param xAxis  The value of the x-axis.
     * @param yAxis  The value of the y-axis
     * @param pressed To identify if the touch held down or released.
     * @return true if the pointer is within the touchscreen
     */
    external fun onTouchEvent(xAxis: Float, yAxis: Float, pressed: Boolean): Boolean

    /**
     * Handles touch movement.
     *
     * @param xAxis The value of the instantaneous x-axis.
     * @param yAxis The value of the instantaneous y-axis.
     */
    external fun onTouchMoved(xAxis: Float, yAxis: Float)

    /**
     * Handles touch events on the secondary display.
     */
    external fun onSecondaryTouchEvent(xAxis: Float, yAxis: Float, pressed: Boolean): Boolean

    /**
     * Handles touch movement on the secondary display.
     */
    external fun onSecondaryTouchMoved(xAxis: Float, yAxis: Float)

    external fun reloadSettings()

    external fun getTitleId(filename: String): Long

    external fun getIsSystemTitle(path: String): Boolean

    /**
     * Sets the current working user directory
     * If not set, it auto-detects a location
     */
    external fun setUserDirectory(directory: String)

    private external fun getInstalledGamePathsImpl(): Array<String?>

    /**
     * 枚举已安装到 NAND 的数字版标题（CIA 安装产物 / 系统应用）。
     *
     * 原生返回 "<path>|<mediaType>" 字符串数组（上游 AzaharPlus
     * GameHelper.getInstalledGamePaths 同款解析）：path 为可直接 run()
     * 启动的 NAND 内 .app 真实路径，mediaType 为游戏媒体类型整数。
     *
     * @return (path, mediaType) 列表；无已安装标题时为空列表
     */
    fun getInstalledGamePaths(): List<Pair<String, Int>> =
        getInstalledGamePathsImpl().mapNotNull { entry ->
            entry?.let {
                val sep = it.lastIndexOf('|')
                if (sep <= 0) {
                    null
                } else {
                    val path = it.substring(0, sep)
                    val media = it.substring(sep + 1).toIntOrNull() ?: 0
                    path to media
                }
            }
        }

    // Create the config.ini file.
    external fun createConfigFile()
    external fun createLogFile()
    external fun logUserDirectory(directory: String)

    /**
     * Set the inserted cartridge that will appear in the home menu.
     * Empty string to clear.
     */
    external fun setInsertedCartridge(path: String)

    /**
     * Begins emulation.（阻塞调用 —— 必须在专用线程调用）
     */
    external fun run(path: String)

    // Surface Handling
    external fun surfaceChanged(surf: Surface)
    external fun surfaceDestroyed()
    external fun doFrame()

    // Second window
    external fun secondarySurfaceChanged(secondary_surface: Surface)
    external fun secondarySurfaceDestroyed()

    /**
     * Unpauses emulation from a paused state.
     */
    external fun unPauseEmulation()

    /**
     * Pauses emulation.
     */
    external fun pauseEmulation()

    /**
     * Stops emulation.
     */
    external fun stopEmulation()

    /**
     * Returns true if emulation is running (or is paused).
     */
    external fun isRunning(): Boolean

    /**
     * Returns the title ID of the currently running title, or 0 on failure.
     */
    external fun getRunningTitleId(): Long

    /**
     * Returns the performance stats for the current game：
     * [system_fps, game_fps, emulation_speed, time_vblank_interval, time_hle_svc,
     *  time_hle_ipc, time_gpu, time_swap, time_remaining]
     */
    external fun getPerfStats(): DoubleArray

    /**
     * Notifies the core emulation that the layout should be updated
     */
    external fun updateFramebuffer(isPortrait: Boolean)

    /**
     * Swaps the top and bottom screens.
     */
    external fun swapScreens(swapScreens: Boolean, rotation: Int)

    external fun initializeGpuDriver(
        hookLibDir: String?,
        customDriverDir: String?,
        customDriverName: String?,
        fileRedirectDir: String?
    )

    external fun areKeysAvailable(): Boolean

    external fun getHomeMenuPath(region: Int): String

    external fun getSystemTitleIds(systemType: Int, region: Int): LongArray

    external fun areSystemTitlesInstalled(): BooleanArray

    external fun uninstallSystemFiles(old3DS: Boolean)

    external fun isFullConsoleLinked(): Boolean

    external fun unlinkConsole()

    external fun setTemporaryFrameLimit(speed: Double)

    external fun disableTemporaryFrameLimit()

    external fun playTimeManagerInit()
    external fun playTimeManagerStart(titleId: Long)
    external fun playTimeManagerStop()
    external fun playTimeManagerGetPlayTime(titleId: Long): Long
    external fun playTimeManagerGetCurrentTitleId(): Long

    private external fun uninstallTitle(titleId: Long, mediaType: Int): Boolean

    external fun nativeFileExists(path: String): Boolean

    external fun deleteOpenGLShaderCache(titleId: Long)
    external fun deleteVulkanShaderCache(titleId: Long)

    external fun reloadCameraDevices()

    external fun loadAmiibo(path: String?): Boolean
    external fun removeAmiibo()

    external fun getSavestateInfo(): Array<SaveStateInfo>?
    external fun saveState(slot: Int)
    external fun loadState(slot: Int)

    external fun logDeviceInfo()

    private external fun compressFileNative(inputPath: String?, outputPath: String): Int
    private external fun decompressFileNative(inputPath: String?, outputPath: String): Int
    external fun getRecommendedExtension(inputPath: String?, shouldCompress: Boolean): String

    external fun initMultiplayer()

    // ---- 原生侧回调（id_cache.cpp / android_utils.h 契约，签名逐一核对） ----
    // 以下方法由 libcitra-android.so 在 JNI_OnLoad 时 GetStaticMethodID 缓存，
    // 并在模拟过程中反向调用。方法名与签名必须与原生侧字符串完全一致。

    /**
     * Handles a core error.
     * @return true: continue; false: abort
     */
    @Keep
    @JvmStatic
    fun onCoreError(error: CoreError?, details: String): Boolean {
        android.util.Log.e("AzaharNative", "Core error: $error / $details")
        // 返回 true 表示"继续"——是否退出由宿主引擎决定
        return true
    }

    @Keep
    @JvmStatic
    fun exitEmulationActivity(result: Int) {
        android.util.Log.w("AzaharNative", "exitEmulationActivity(result=$result)")
        NesStationHost.notifyEmulationExited(result)
    }

    @Keep
    @JvmStatic
    fun requestCameraPermission(): Boolean = true

    @Keep
    @JvmStatic
    fun requestMicPermission(): Boolean = true

    @Keep
    @JvmStatic
    fun addNetPlayMessage(type: Int, message: String) {
        android.util.Log.i("AzaharNative", "NetPlay[$type] $message")
    }

    @Keep
    @JvmStatic
    fun clearChat() {
    }

    @Keep
    @JvmStatic
    fun onCompressProgress(progress: Long, total: Long) {
    }

    // ------------------------------------------------------------------
    // AzaharPlus 2125（"25" API）回调 —— libazahar.so JNI_OnLoad 实测契约
    // （BuildId 0758b08c834910db672499f203daad47bd3598eb，strings 提取）。
    // 该 fork 以 onSaveStateComplete25 / onNetPlayStatusMessageReceive25
    // 取代上游 addNetPlayMessage / onSaveStateComplete 的回调位。
    // ------------------------------------------------------------------

    /** 存档状态完成回调。JNI 签名: (Z)V */
    @Keep
    @JvmStatic
    fun onSaveStateComplete25(success: Boolean) {
        android.util.Log.i("AzaharNative", "SaveState25 complete: $success")
    }

    /** 联机状态消息回调（替代上游 addNetPlayMessage）。JNI 签名: (ILjava/lang/String;)V */
    @Keep
    @JvmStatic
    fun onNetPlayStatusMessageReceive25(type: Int, message: String) {
        android.util.Log.i("AzaharNative", "NetPlay25[$type] $message")
    }

    // ---- android_utils.h 文件系统桥 ----
    // 上游经 CitraApplication.documentsTree / FileUtil 走 SAF；NesStation 传给核心的均是
    // 真实文件路径（EmulatorScreen 已把 content:// 拷贝为真实文件），因此这里按本地文件实现，
    // 仅 content:// 前缀路径返回失败语义（-1 / false / 空数组），与上游"不可访问"语义一致。

    @Keep
    @JvmStatic
    fun createFile(directory: String, filename: String): Boolean = try {
        if (directory.startsWith("content://")) false
        else {
            val dir = File(directory).apply { mkdirs() }
            File(dir, filename).createNewFile()
        }
    } catch (_: Exception) {
        false
    }

    @Keep
    @JvmStatic
    fun createDir(directory: String, directoryName: String): Boolean = try {
        if (directory.startsWith("content://")) false
        else File(File(directory).apply { mkdirs() }, directoryName).mkdirs()
    } catch (_: Exception) {
        false
    }

    @Keep
    @JvmStatic
    fun openContentUri(filepath: String, openmode: String): Int = -1

    @Keep
    @JvmStatic
    fun getFilesName(filepath: String): Array<String> = try {
        if (filepath.startsWith("content://")) emptyArray()
        else {
            val dir = File(filepath)
            if (dir.isDirectory) dir.list() ?: emptyArray() else emptyArray()
        }
    } catch (_: Exception) {
        emptyArray()
    }

    @Keep
    @JvmStatic
    fun getUserDirectory(): String = NesStationHost.userDirectory()

    @Keep
    @JvmStatic
    fun copyFile(
        source: String,
        destinationPath: String,
        destinationFilename: String
    ): Boolean = try {
        if (source.startsWith("content://") || destinationPath.startsWith("content://")) false
        else {
            val dstDir = File(destinationPath).apply { mkdirs() }
            File(source).copyTo(File(dstDir, destinationFilename), overwrite = true)
            true
        }
    } catch (_: Exception) {
        false
    }

    @Keep
    @JvmStatic
    fun renameFile(source: String, filename: String): Boolean = try {
        if (source.startsWith("content://")) false
        else File(source).renameTo(File(File(source).parentFile, filename))
    } catch (_: Exception) {
        false
    }

    @Keep
    @JvmStatic
    fun updateDocumentLocation(sourcePath: String, destinationPath: String): Boolean = false

    @Keep
    @JvmStatic
    fun getBuildFlavor(): String = "vanilla"

    @Keep
    @JvmStatic
    fun isPortraitMode(): Boolean =
        NesStationHost.appContext()?.resources?.configuration?.orientation ==
            android.content.res.Configuration.ORIENTATION_PORTRAIT

    @Keep
    @JvmStatic
    fun isUsingAngleForOpenGL(): Boolean = false

    @Keep
    @JvmStatic
    fun moveFile(
        filename: String,
        sourceDirPath: String,
        destinationDirPath: String
    ): Boolean = try {
        if (sourceDirPath.startsWith("content://") || destinationDirPath.startsWith("content://"))
            false
        else {
            val dstDir = File(destinationDirPath).apply { mkdirs() }
            File(sourceDirPath, filename).renameTo(File(dstDir, filename))
        }
    } catch (_: Exception) {
        false
    }

    @Keep
    @JvmStatic
    fun isDirectory(path: String): Boolean = try {
        !path.startsWith("content://") && File(path).isDirectory
    } catch (_: Exception) {
        false
    }

    @Keep
    @JvmStatic
    fun fileExists(path: String): Boolean = try {
        !path.startsWith("content://") && File(path).exists()
    } catch (_: Exception) {
        false
    }

    @Keep
    @JvmStatic
    fun getSize(path: String): Long = try {
        if (path.startsWith("content://")) 0L else File(path).length()
    } catch (_: Exception) {
        0L
    }

    @Keep
    @JvmStatic
    fun deleteDocument(path: String): Boolean = try {
        !path.startsWith("content://") && File(path).delete()
    } catch (_: Exception) {
        false
    }

    @Keep
    @JvmStatic
    fun loadImageFromFile(filePath: String, width: Int, height: Int): Bitmap? = try {
        if (filePath.startsWith("content://")) null
        else BitmapFactory.decodeFile(filePath)
    } catch (_: Exception) {
        null
    }

    /**
     * 上游压缩管线入口（宿主未接入 CIA 压缩 UI，保留入口语义）。
     */
    fun compressFile(inputPath: String?, outputPath: String): Int =
        compressFileNative(inputPath, outputPath)

    fun decompressFile(inputPath: String?, outputPath: String): Int =
        decompressFileNative(inputPath, outputPath)

    fun getInstalledGamePaths(): Array<String> = emptyArray()

    /**
     * NesStation 宿主桥 —— 由 AzaharEngine 在启动时注册，替代上游 EmulationActivity 上下文。
     */
    interface Host {
        fun appContext(): Context?
        fun azaharUserDirectory(): String
        fun onEmulationExited(result: Int)
    }

    object NesStationHost {
        @Volatile private var host: Host? = null

        @JvmStatic
        fun register(host: Host) {
            this.host = host
        }

        @JvmStatic
        fun appContext(): Context? = host?.appContext()

        @JvmStatic
        fun userDirectory(): String =
            host?.azaharUserDirectory()
                ?: (appContext()?.getExternalFilesDir(null)?.absolutePath ?: "/data/local/tmp")

        @JvmStatic
        fun notifyEmulationExited(result: Int) = host?.onEmulationExited(result)
    }

    data class SaveStateInfo(val slot: Int, val time: Date)

    enum class CoreError {
        ErrorSystemFiles,
        ErrorSavestate,
        ErrorArticDisconnected,
        ErrorN3DSApplication,
        ErrorCoreExceptionRaised,
        ErrorSavestateBuildMismatch,
        ErrorUnknown
    }

    enum class InstallStatus {
        Success,
        ErrorFailedToOpenFile,
        ErrorFileNotFound,
        ErrorAborted,
        ErrorInvalid,
        ErrorEncrypted
    }

    /**
     * 按键 / 摇杆 ID —— 与上游 NativeLibrary.ButtonType 及原生 input_manager.h 完全一致。
     */
    object ButtonType {
        const val BUTTON_A = 700
        const val BUTTON_B = 701
        const val BUTTON_X = 702
        const val BUTTON_Y = 703
        const val BUTTON_START = 704
        const val BUTTON_SELECT = 705
        const val BUTTON_HOME = 706
        const val BUTTON_ZL = 707
        const val BUTTON_ZR = 708
        const val DPAD_UP = 709
        const val DPAD_DOWN = 710
        const val DPAD_LEFT = 711
        const val DPAD_RIGHT = 712
        const val STICK_LEFT = 713
        const val STICK_LEFT_UP = 714
        const val STICK_LEFT_DOWN = 715
        const val STICK_LEFT_LEFT = 716
        const val STICK_LEFT_RIGHT = 717
        const val STICK_C = 718
        const val STICK_C_UP = 719
        const val STICK_C_DOWN = 720
        const val STICK_C_LEFT = 771
        const val STICK_C_RIGHT = 772
        const val TRIGGER_L = 773
        const val TRIGGER_R = 774
        const val DPAD = 780
        const val BUTTON_DEBUG = 781
        const val BUTTON_GPIO14 = 782
        const val BUTTON_SWAP = 800
        const val BUTTON_TURBO = 801
    }

    object ButtonState {
        const val RELEASED = 0
        const val PRESSED = 1
    }

    /**
     * 上游 Game.MediaType 的最小镜像（getInstalledGamePaths 解析用；宿主不调用）。
     */
    enum class MediaType(val value: Int) {
        Cartridge(0),
        Digital(1),
        Invalid(2);

        companion object {
            fun fromInt(value: Int): MediaType? =
                entries.firstOrNull { it.value == value }
        }
    }

    @Suppress("UNUSED_PARAMETER")
    private fun keepRefs(context: Context?, bitmap: Bitmap?, build: String = Build.MODEL) {
    }
}
