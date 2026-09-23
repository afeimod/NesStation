package com.nesstation.app.core.jni

import android.content.Context
import org.dolphinemu.dolphinemu.DolphinHost
import org.dolphinemu.dolphinemu.NativeLibrary
import org.dolphinemu.dolphinemu.model.GameFile
import org.dolphinemu.dolphinemu.model.GameFileCache
import org.dolphinemu.dolphinemu.model.IniFile
import org.dolphinemu.dolphinemu.utils.DirectoryInitialization
import java.io.File

/**
 * Ishiruka（NGC/WII）核心门面 —— 进程内嵌入 libmain.so（Dolphin fork,
 * 5.0-15560）。与 DC 核心同一进程内嵌入形态：
 *  - jniLibs/arm64-v8a/libmain.so 随 APK 发布，System.loadLibrary("main")；
 *  - JNI 契约类 org.dolphinemu.dolphinemu.* 随 NesStation 源码打包，
 *    与 libmain.so 的静态导出 Java_org_dolphinemu_dolphinemu_* 对应；
 *  - 模拟/渲染/音频（OpenSLES）在核心内部；Kotlin 侧负责 Surface、
 *    输入注入（Touchscreen 设备 id 映射）、INI 设置与扩展切换。
 *
 * 输入 id 与核心自带 GCPadNew.ini/WiimoteNew.ini 一致：
 *  GC:   A=0 B=1 Start=2 X=3 Y=4 Z=5 Dpad=6-9 主摇杆 11/13(±对) C摇杆 16/18
 *        扳机 L=20 R=21
 *  Wii:  A=100 B=101 −=102 +=103 Home=104 1=105 2=106 Dpad=107-110
 *        IR 112/113(上下) 114/115(左右) Hide=118 挥动 120-125 倾斜 127-130
 *        摇晃按钮 132-134 侧立/竖立 135-138 IR重定位 139
 *  双节棍: C=200 Z=201 摇杆 203/205 挥动 208-213 倾斜 215-218 摇晃 220-222
 *  经典: A=300 B=301 X=302 Y=303 −=304 +=305 Home=306 ZL=307 ZR=308
 *        Dpad=309-312 L摇杆 314/316 R摇杆 319/321 L=323 R=324
 *  震动: Rumble=700
 *
 * 需要 arm64 设备。
 */
object IshirukaNative {

    @Volatile
    var appContext: Context? = null

    private val loaded = object : Any() {}
    @Volatile private var isLoadedFlag = false
    @Volatile private var initializedDirs = false

    val isLoaded: Boolean get() = isLoadedFlag

    /** 核心库加载（幂等）。 */
    fun ensureLoaded(): Boolean {
        if (isLoadedFlag) return true
        return try {
            DolphinHost.ensureLibraryLoaded()
            isLoadedFlag = true
            true
        } catch (t: Throwable) {
            android.util.Log.e("IshirukaNative", "loadLibrary failed", t)
            false
        }
    }

    fun isAbiSupported(): Boolean =
        android.os.Build.SUPPORTED_ABIS.any { it.startsWith("arm64") }

    // === 目录 ===

    fun userDir(context: Context): String {
        val base = context.getExternalFilesDir(null) ?: context.filesDir
        return File(base, "dolphin-user").absolutePath
    }

    /** 目录初始化（Sys 解包 + Config 播种 + CreateUserDirectories）。 */
    fun ensureDirectories(context: Context): Boolean {
        if (initializedDirs) return true
        if (!ensureLoaded()) return false
        val ok = DirectoryInitialization.initialize(userDir(context), context)
        initializedDirs = ok
        return ok
    }

    fun configDir(context: Context): File = File(userDir(context), "Config")

    // === Surface ===

    fun surfaceChanged(surface: Surface?) {
        if (!isLoadedFlag) return
        try {
            if (surface != null) NativeLibrary.SurfaceChanged(surface)
            else NativeLibrary.SurfaceDestroyed()
        } catch (t: Throwable) {
            android.util.Log.w("IshirukaNative", "surfaceChanged", t)
        }
    }

    // === 模拟控制 ===

    /** 阻塞运行（模拟线程内）。 */
    fun run(paths: Array<String>, revision: String) =
        NativeLibrary.Run(paths, revision)

    fun pause() {
        if (isLoadedFlag) NativeLibrary.PauseEmulation()
    }

    fun unpause() {
        if (isLoadedFlag) NativeLibrary.UnPauseEmulation()
    }

    fun stop() {
        if (isLoadedFlag) NativeLibrary.StopEmulation()
    }

    fun isRunning(): Boolean = try {
        isLoadedFlag && NativeLibrary.IsRunning()
    } catch (t: Throwable) {
        false
    }

    // === 输入 ===

    /** 按键事件（id 见类注释；down=true 按下）。 */
    fun buttonEvent(id: Int, down: Boolean) {
        if (!isLoadedFlag) return
        try {
            NativeLibrary.onGamePadEvent(
                NativeLibrary.TouchScreenDevice, id, if (down) 1 else 0
            )
        } catch (t: Throwable) {
            android.util.Log.w("IshirukaNative", "buttonEvent $id", t)
        }
    }

    /** 轴事件（摇杆/IR/扳机/倾斜；值 -1..1）。 */
    fun axisEvent(id: Int, value: Float) {
        if (!isLoadedFlag) return
        try {
            NativeLibrary.onGamePadMoveEvent(NativeLibrary.TouchScreenDevice, id, value)
        } catch (t: Throwable) {
            android.util.Log.w("IshirukaNative", "axisEvent $id", t)
        }
    }

    // === 设置 ===

    /** 写用户 INI（file 如 "Dolphin.ini"/"GFX.ini"）。 */
    fun setUserSetting(file: String, section: String, key: String, value: String) {
        if (!isLoadedFlag) return
        try {
            NativeLibrary.SetUserSetting(section, key, value, file)
        } catch (t: Throwable) {
            android.util.Log.w("IshirukaNative", "setUserSetting", t)
        }
    }

    /** 读取用户 INI 值。 */
    fun readIniValue(context: Context, file: String, section: String, key: String): String? {
        return try {
            val f = File(configDir(context), file)
            if (!f.exists()) return null
            val ini = IniFile()
            if (!ini.loadFile(f.absolutePath)) return null
            val v = ini.getString(section, key, "")
            v.ifEmpty { null }
        } catch (t: Throwable) {
            null
        }
    }

    /**
     * ★ 控制器切换：写 WiimoteNew.ini [Wiimote1] Extension 并热重载。
     * extension: "None" / "Nunchuk" / "Classic"
     */
    fun setExtension(context: Context, extension: String) {
        if (!isLoadedFlag) return
        try {
            val f = File(configDir(context), "WiimoteNew.ini")
            val ini = IniFile()
            if (ini.loadFile(f.absolutePath)) {
                ini.setString("Wiimote1", "Extension", extension)
                ini.saveFile(f.absolutePath)
                NativeLibrary.ReloadWiimoteConfig()
            }
        } catch (t: Throwable) {
            android.util.Log.e("IshirukaNative", "setExtension", t)
        }
    }

    /** 读取当前扩展。 */
    fun getExtension(context: Context): String =
        readIniValue(context, "WiimoteNew.ini", "Wiimote1", "Extension") ?: "Nunchuk"

    // === 状态存取 ===

    fun saveState(slot: Int): Boolean = try {
        if (isLoadedFlag) {
            NativeLibrary.SaveState(slot, true)
            true
        } else false
    } catch (t: Throwable) {
        false
    }

    fun loadState(slot: Int): Boolean = try {
        if (isLoadedFlag) {
            NativeLibrary.LoadState(slot)
            true
        } else false
    } catch (t: Throwable) {
        false
    }

    // === 游戏元信息 ===

    /** 用核心 GameFileCache 查询游戏（GC/Wii 判定 + 标题）。 */
    fun queryGameInfo(path: String): Triple<Int, String, String>? = try {
        if (!isLoadedFlag) null
        else {
            val cache = GameFileCache()
            cache.addOrGet(path)?.let { Triple(it.getPlatform(), it.getName(), it.getGameId()) }
        }
    } catch (t: Throwable) {
        null
    }

    /** 游戏宽高比（IR overlay 用）。 */
    fun gameAspectRatio(): Float = try {
        if (isLoadedFlag) NativeLibrary.GetGameAspectRatio() else 1.7777f
    } catch (t: Throwable) {
        1.7777f
    }

    fun gameDisplayScale(): Float = try {
        if (isLoadedFlag) NativeLibrary.GetGameDisplayScale() else 1.0f
    } catch (t: Throwable) {
        1.0f
    }

    // === 回调挂接 ===

    fun attachSessionHooks(
        activity: android.app.Activity?,
        onExit: () -> Unit,
        onAlert: (String, String, Boolean) -> Boolean
    ) {
        if (activity != null) DolphinHost.setActivity(activity)
        DolphinHost.setExitHook { onExit() }
        DolphinHost.setAlertHook { title, msg, yesNo -> onAlert(title, msg, yesNo) }
    }

    fun detachSessionHooks() {
        DolphinHost.setActivity(null)
        DolphinHost.setExitHook(null)
        DolphinHost.setAlertHook(null)
    }
}
