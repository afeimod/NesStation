package com.nesstation.app.core.jni

import android.content.Context
import com.nesstation.app.core.storage.AzaharDirs
import org.citra.citra_emu.CitraHost
import org.citra.citra_emu.CitraPermissions
import org.citra.citra_emu.NativeLibrary
import org.citra.citra_emu.utils.CiaInstallWorker
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Azahar（3DS）核心门面 —— 进程内嵌入 libcitra-android.so。
 *
 * 集成方式与 DC 核心（DcNative + libdccore.so dlopen Flycast）同一形态：
 *  - 核心库随 APK 发布（jniLibs/arm64-v8a/libcitra-android.so，39MB），
 *    System.loadLibrary("citra-android") 直接装载；
 *  - JNI 契约类 org.citra.citra_emu.*（NativeLibrary/applets/utils/...）
 *    随 NesStation 源码打包，与上游 AzaharPlus 2125 的静态 JNI 导出
 *    Java_org_citra_citra_1emu_* 一一对应；
 *  - 模拟循环/音频（OpenSLES）/双屏布局/体感（ASensorManager）全部在
 *    核心内部 —— Kotlin 侧只负责 Surface 生命周期、输入注入、设置落盘
 *    与 CIA 安装/解密流程。
 *
 * 需要 arm64 设备（核心只发布 arm64-v8a）。
 */
object AzaharNative {

    /** 由 NesApp.onCreate 注入（宿主上下文，供 CitraHost/目录初始化）。 */
    @Volatile
    var appContext: Context? = null

    private val loaded = AtomicBoolean(false)

    val isLoaded: Boolean get() = loaded.get()

    /** 核心库加载 + 宿主桥初始化（幂等）。失败返回 false（UI 显示错误）。 */
    fun ensureLoaded(): Boolean {
        if (loaded.get()) return true
        val ctx = appContext ?: return false
        return try {
            CitraHost.init(ctx)
            NativeLibrary.loadLibrary()
            loaded.set(true)
            true
        } catch (t: Throwable) {
            android.util.Log.e("AzaharNative", "loadLibrary failed", t)
            false
        }
    }

    /** abi 支持检查：核心只有 arm64-v8a。 */
    fun isAbiSupported(): Boolean =
        android.os.Build.SUPPORTED_ABIS.any { it.startsWith("arm64") }

    // === 目录 ===

    /** 3DS 用户目录（<files>/azahar-user），首次创建并播种 config。 */
    fun ensureUserDirectory(): String {
        val ctx = appContext ?: return ""
        val dir = AzaharDirs.ensureUserDir(ctx)
        CitraHost.setUserDirectory(dir)
        // 双通道：native setUserDirectory（核心存储路径）+ Java 回调
        // getUserDirectory()（CitraHost 覆盖）—— 与上游两条初始化路径都兼容
        try {
            if (loaded.get()) NativeLibrary.INSTANCE.setUserDirectory(dir)
        } catch (t: Throwable) {
            android.util.Log.w("AzaharNative", "setUserDirectory", t)
        }
        return dir
    }

    // === Surface ===

    fun surfaceChanged(surface: android.view.Surface?) {
        if (!loaded.get()) return
        try {
            if (surface != null) NativeLibrary.INSTANCE.surfaceChanged(surface)
            else NativeLibrary.INSTANCE.surfaceDestroyed()
        } catch (t: Throwable) {
            android.util.Log.w("AzaharNative", "surfaceChanged", t)
        }
    }

    // === 模拟控制 ===

    /** 阻塞运行（模拟线程内调用）。 */
    fun run(romPath: String) = NativeLibrary.INSTANCE.run(romPath)

    fun pause() {
        if (loaded.get()) NativeLibrary.INSTANCE.pauseEmulation()
    }

    fun unpause() {
        if (loaded.get()) NativeLibrary.INSTANCE.unPauseEmulation()
    }

    fun stop() {
        if (loaded.get()) NativeLibrary.INSTANCE.stopEmulation()
    }

    fun isRunning(): Boolean =
        try { loaded.get() && NativeLibrary.INSTANCE.isRunning() } catch (t: Throwable) { false }

    fun reset() {
        // 核心无 reset API —— stop + 由引擎重新 run
        stop()
    }

    // === 输入（ButtonType id，见 NativeLibrary.ButtonType） ===

    /** 按钮/十字键事件（pressed: true=按下）。 */
    fun buttonEvent(buttonId: Int, pressed: Boolean) {
        if (!loaded.get()) return
        try {
            NativeLibrary.INSTANCE.onGamePadEvent(
                NativeLibrary.TouchScreenDevice, buttonId, if (pressed) 1 else 0
            )
        } catch (t: Throwable) {
            android.util.Log.w("AzaharNative", "buttonEvent $buttonId", t)
        }
    }

    /** 摇杆向量（axisId=713 左摇杆 / 718 C 摇杆；x,y ∈ [-1,1]，y+ = 上）。 */
    fun stickMove(axisId: Int, x: Float, y: Float) {
        if (!loaded.get()) return
        try {
            NativeLibrary.INSTANCE.onGamePadMoveEvent(
                NativeLibrary.TouchScreenDevice, axisId, x, y
            )
        } catch (t: Throwable) {
            android.util.Log.w("AzaharNative", "stickMove $axisId", t)
        }
    }

    /** 单轴事件（X 轴/手柄外设）。 */
    fun axisEvent(axisId: Int, value: Float): Boolean = try {
        loaded.get() && NativeLibrary.INSTANCE.onGamePadAxisEvent(
            NativeLibrary.TouchScreenDevice, axisId, value
        )
    } catch (t: Throwable) {
        false
    }

    /** 3DS 下屏触摸（视图像素坐标；pressed=false 表示抬起）。 */
    fun touch(x: Float, y: Float, pressed: Boolean) {
        if (!loaded.get()) return
        try {
            NativeLibrary.INSTANCE.onTouchEvent(x, y, pressed)
        } catch (t: Throwable) {
            android.util.Log.w("AzaharNative", "touch", t)
        }
    }

    /** 3DS 下屏触摸移动。 */
    fun touchMoved(x: Float, y: Float) {
        if (!loaded.get()) return
        try {
            NativeLibrary.INSTANCE.onTouchMoved(x, y)
        } catch (t: Throwable) {
            android.util.Log.w("AzaharNative", "touchMoved", t)
        }
    }

    // === 状态存取 ===

    fun saveState(slot: Int): Boolean = try {
        if (loaded.get()) {
            NativeLibrary.INSTANCE.saveState(slot)
            true
        } else false
    } catch (t: Throwable) {
        android.util.Log.w("AzaharNative", "saveState", t)
        false
    }

    fun loadState(slot: Int): Boolean = try {
        if (loaded.get()) {
            NativeLibrary.INSTANCE.loadState(slot)
            true
        } else false
    } catch (t: Throwable) {
        android.util.Log.w("AzaharNative", "loadState", t)
        false
    }

    // === 性能 ===

    /** [0] = 实时 FPS（核心 perf stats）。 */
    fun perfFps(): Double = try {
        val stats = if (loaded.get()) NativeLibrary.INSTANCE.getPerfStats() else null
        if (stats != null && stats.isNotEmpty()) stats[0] else 0.0
    } catch (t: Throwable) {
        0.0
    }

    // === 设置 / 加密 / 安装 ===

    /** 设置变更后热加载（config.ini 落盘后调用）。 */
    fun reloadSettings() {
        if (loaded.get()) {
            try {
                NativeLibrary.INSTANCE.reloadSettings()
            } catch (t: Throwable) {
                android.util.Log.w("AzaharNative", "reloadSettings", t)
            }
        }
    }

    /** 解密密钥是否可用（aes_keys.txt 已导入）。 */
    fun areKeysAvailable(): Boolean = try {
        loaded.get() && NativeLibrary.INSTANCE.areKeysAvailable()
    } catch (t: Throwable) {
        false
    }

    /** 安装 CIA（阻塞；后台线程调用）。返回上游 InstallStatus 名。 */
    fun installCia(path: String): String = try {
        val status = CiaInstallWorker.get().install(path)
        status?.name ?: "ErrorFailedToOpenFile"
    } catch (t: Throwable) {
        android.util.Log.e("AzaharNative", "installCia", t)
        "ErrorFailedToOpenFile"
    }

    /** 已安装 CIA 标题路径列表。 */
    fun installedGamePaths(): List<String> = try {
        if (loaded.get()) {
            NativeLibrary.INSTANCE.getInstalledGamePaths().mapNotNull { it?.path }
        } else emptyList()
    } catch (t: Throwable) {
        emptyList()
    }

    /** 游戏加密状态查询（GameInfo.isEncrypted —— 文件不存在/解析失败 = false）。 */
    fun isEncrypted(path: String): Boolean = try {
        if (loaded.get()) {
            val info = org.citra.citra_emu.model.GameInfo(path)
            val ok = info.isValid()
            val encrypted = info.isEncrypted()
            encrypted && ok
        } else false
    } catch (t: Throwable) {
        false
    }

    /** 切换上下双屏（swap = true 时交换）。 */
    fun swapScreens(swap: Boolean, portrait: Boolean) {
        if (!loaded.get()) return
        try {
            NativeLibrary.INSTANCE.swapScreens(swap, if (portrait) 1 else 0)
        } catch (t: Throwable) {
            android.util.Log.w("AzaharNative", "swapScreens", t)
        }
    }

    /** 快进：核心侧帧率上限（倍率；4x = 240fps 上限）。 */
    fun setTemporaryFrameLimit(multiplier: Double) {
        if (!loaded.get()) return
        try {
            NativeLibrary.INSTANCE.setTemporaryFrameLimit(60.0 * multiplier)
        } catch (t: Throwable) {
            android.util.Log.w("AzaharNative", "setTemporaryFrameLimit", t)
        }
    }

    fun disableTemporaryFrameLimit() {
        if (!loaded.get()) return
        try {
            NativeLibrary.INSTANCE.disableTemporaryFrameLimit()
        } catch (t: Throwable) {
            android.util.Log.w("AzaharNative", "disableTemporaryFrameLimit", t)
        }
    }

    // === 回调挂接（模拟界面 / 设置页调用） ===

    fun attachSessionHooks(
        activity: android.app.Activity?,
        onExit: (Int) -> Unit,
        onStatus: (String) -> Unit,
        onCoreError: (String, String) -> Boolean
    ) {
        if (activity != null) CitraHost.setActivity(activity)
        CitraHost.setExitHook { code -> onExit(code) }
        CitraHost.setStatusHook { msg -> onStatus(msg) }
        // DecisionHook.onDecision(title, message, yesNo) 为 3 参接口；
        // coreError 场景 yesNo 恒为 false（无选择语义），忽略后交由上层决定。
        CitraHost.setCoreErrorHook { title, msg, _ -> onCoreError(title, msg) }
        CitraHost.setAlertHook { title, msg, yesNo -> onCoreError(title, msg) }
    }

    fun detachSessionHooks() {
        CitraHost.setActivity(null)
        CitraHost.setExitHook(null)
        CitraHost.setStatusHook(null)
        CitraHost.setCoreErrorHook(null)
        CitraHost.setAlertHook(null)
    }
}
