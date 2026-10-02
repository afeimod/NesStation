package org.citra.emu

import androidx.annotation.Keep
import android.content.Context
import android.content.res.AssetManager
import android.hardware.HardwareBuffer

/**
 * ★★ Citra MMJ（weihuoya 分支 20250220）JNI 契约 —— vendored 宿主层 ★★
 *
 * 集成模式与 DraStic / Azahar 相同：从 Citra_MMJ_20250220.apk.zip 提取
 * libmain.so → `jniLibs/arm64-v8a/libcitra_mmj.so`，JNI 符号按
 * Java_org_citra_emu_NativeLibrary_<method> 绑定到本契约类。
 * 包名 `org.citra.emu`（与 Azahar 的 `org.citra.citra_emu` 不同，天然
 * 无符号冲突，两核心可同进程共存）。
 *
 * **全部方法签名逐一对照 MMJ APK classes.dex 反编译提取**（dex_full_sigs
 * 解析 method_ids/proto_ids），native 侧以 GetStaticMethodID 按名字+
 * 描述符查找 —— 签名必须逐字一致。
 *
 * 原生→Java 回调（so 内字符串实证被调用）：RemoteFile*（核心文件 IO
 * 走 Java）、getAssetManager、getEmulationContext、notifyGameShudown、
 * showInputBoxDialog、showMessageDialog、pickImage、updateProgress、
 * addNetPlayMessage、loadImageFromFile、saveImageToFile、
 * showRunningSetting、Saf*。此处给出可用的最小实现（本地文件直读）。
 */
object NativeLibrary {

    // ------------------------------------------------------------------
    // 生命周期 / 模拟循环（推模型：专用线程 Run(path) 阻塞至退出）
    // ------------------------------------------------------------------

    /** 启动模拟（阻塞调用 —— 必须在专用线程）。path = ROM 绝对路径。 */
    @Keep
    external fun Run(path: String)

    @Keep
    external fun PauseEmulation()

    @Keep
    external fun ResumeEmulation()

    @Keep
    external fun StopEmulation()

    @Keep
    external fun IsRunning(): Boolean

    // ------------------------------------------------------------------
    // Surface / 显示
    // ------------------------------------------------------------------

    @Keep
    external fun SurfaceChanged(surface: android.view.Surface)

    @Keep
    external fun SurfaceDestroyed()

    @Keep
    external fun WindowChanged()

    /**
     * 显示信息（屏幕几何）。参数为 (w, h, ?, ?, ?, density?) —— 与 MMJ
     * EmulationActivity 的调用序对齐（值语义见引擎）。
     */
    @Keep
    external fun SetDisplayInfo(p0: Int, p1: Int, p2: Int, p3: Int, p4: Int, p5: Float)

    /** 每帧呈现（Choreographer 驱动）。 */
    @Keep
    external fun doFrame(nanos: Long)

    @Keep
    external fun getScreenTexture(): IntArray?

    @Keep
    external fun releaseScreenTexture()

    @Keep
    external fun SetBatteryLevel(level: Int)

    // ------------------------------------------------------------------
    // 用户目录 / 配置
    // ------------------------------------------------------------------

    /** 设置用户数据根目录（nand/sdmc/config/states 等在其下）。 */
    @Keep
    external fun SetUserPath(path: String)

    @Keep
    external fun loadConfig()

    @Keep
    external fun saveConfig()

    @Keep
    external fun getConfigString(key: String): String?

    @Keep
    external fun getConfigInteger(key: String): Int

    @Keep
    external fun getConfigBoolean(key: String): Boolean

    @Keep
    external fun setConfigString(key: String, value: String)

    @Keep
    external fun setConfigInteger(key: String, value: Int)

    @Keep
    external fun setConfigBoolean(key: String, value: Boolean)

    /** 运行时设置（int 数组；MMJ 菜单同步机制）。 */
    @Keep
    external fun getRunningSettings(): IntArray

    @Keep
    external fun setRunningSettings(settings: IntArray)

    /** 自定义布局（landscape = true / portrait）。 */
    @Keep
    external fun setCustomLayout(landscape: Boolean, left: Int, top: Int, right: Int, bottom: Int)

    @Keep
    external fun getCustomLayout(landscape: Boolean): android.graphics.Rect

    // ------------------------------------------------------------------
    // 输入
    // ------------------------------------------------------------------

    /**
     * 底屏触摸。action 为 MMJ 位掩码语义（so 反汇编实证）：
     * bit0(1)=按下 / bit1(2)=抬起 / bit2(4)=移动。x/y = 视图像素坐标。
     */
    @Keep
    external fun TouchEvent(action: Int, x: Int, y: Int): Boolean

    /**
     * 按键事件（key = 配置键映射的 id，action 1=按下 0=抬起）。
     * 输入管理器把 key 与配置键（button_* 等）的值匹配后分发。
     */
    @Keep
    external fun KeyEvent(key: Int, action: Int): Boolean

    /** 摇杆轴事件（key = 轴配置码，value 带符号 —— 负值按 key+0x1000 匹配反向槽）。 */
    @Keep
    external fun MoveEvent(key: Int, value: Float)

    /** 输入事件（key = 表索引通道，value 0..1）。 */
    @Keep
    external fun InputEvent(key: Int, value: Float)

    @Keep
    external fun KeyboardEvent(key: Int, text: String)

    // ------------------------------------------------------------------
    // GPU 驱动（adrenotools）
    // ------------------------------------------------------------------

    /** (hookLibDir, customDriverDir, customDriverName, fileRedirectDir)。 */
    @Keep
    external fun InitGpuDriver(hookLibDir: String, customDriverDir: String,
                               customDriverName: String, fileRedirectDir: String)

    // ------------------------------------------------------------------
    // ROM / 应用信息 / 安装
    // ------------------------------------------------------------------

    @Keep
    external fun InstallCIA(paths: Array<String>)

    @Keep
    external fun GetAppTitle(path: String): String?

    @Keep
    external fun GetAppId(path: String): String?

    @Keep
    external fun GetAppIcon(path: String): ByteArray?

    @Keep
    external fun GetAppRegion(path: String): Int

    @Keep
    external fun IsAppExecutable(path: String): Boolean

    @Keep
    external fun IsAppVisible(path: String): Boolean

    @Keep
    external fun GetBuildDate(): String?

    @Keep
    external fun GetDeviceIinfo(surface: android.view.Surface): String?

    // ------------------------------------------------------------------
    // Amiibo / 摄像头 / 其它（本期宿主不使用，保留契约形状）
    // ------------------------------------------------------------------

    @Keep
    external fun IsSearchingForAmiibos(): Boolean

    @Keep
    external fun LoadAmiibo(path: String): Boolean

    @Keep
    external fun RemoveAmiibo()

    @Keep
    external fun GetAmiiboName(): String?

    @Keep
    external fun ResetCamera()

    @Keep
    external fun reloadCheatCode()

    @Keep
    external fun readMemory(addr: Int, size: Int): Int

    @Keep
    external fun writeMemory(addr: Int, value: Int, size: Int)

    @Keep
    external fun searchMemory(a: Int, b: Int, c: Int, d: Int, e: Int, f: Int): IntArray?

    @Keep
    external fun getSearchResults(): IntArray?

    @Keep
    external fun resetSearchResults()

    @Keep
    external fun loadPageTable(): IntArray?

    @Keep
    external fun loadPage(index: Int): ByteArray?

    // ------------------------------------------------------------------
    // 原生 → Java 回调（签名逐字对齐 dex 反编译；so 经 GetStaticMethodID 调用）
    // ------------------------------------------------------------------

    /** 宿主桥（引擎注册；核心错误 / 退出通知）。 */
    @Volatile
    var host: MmjHost? = null

    interface MmjHost {
        fun onGameShutdown()
        fun onMessage(type: Int, message: String) {}
    }

    /**
     * 核心文件 IO（ROM / 存档 / 系统文件读取走 Java 侧）。
     * 句柄 = [RemoteFileHandle]。所有回调可能来自模拟线程 —— 实现必须线程安全。
     */
    @Keep
    @JvmStatic
    fun RemoteFileOpen(path: String, mode: String): Any? = try {
        RemoteFileHandle.open(path, mode)
    } catch (_: Throwable) { null }

    @Keep
    @JvmStatic
    fun RemoteFileRead(handle: Any?): Int = (handle as? RemoteFileHandle)?.read() ?: -1

    @Keep
    @JvmStatic
    fun RemoteFileSize(handle: Any?): Int = (handle as? RemoteFileHandle)?.size ?: 0

    @Keep
    @JvmStatic
    fun RemoteFileData(handle: Any?): ByteArray? = (handle as? RemoteFileHandle)?.data()

    @Keep
    @JvmStatic
    fun RemoteFileStatus(handle: Any?): Int = (handle as? RemoteFileHandle)?.status() ?: -1

    @Keep
    @JvmStatic
    fun RemoteFileClose(handle: Any?) { (handle as? RemoteFileHandle)?.close() }

    /** SAF 文件访问（content:// URI）—— 本地文件直读为主，SAF 仅返回不可用。 */
    @Keep
    @JvmStatic
    fun SafOpen(path: String, mode: String): Any? = null

    @Keep
    @JvmStatic
    fun SafNativeFd(handle: Any?): Int = -1

    @Keep
    @JvmStatic
    fun SafClose(handle: Any?) {}

    @Keep
    @JvmStatic
    fun SafLastModified(path: String): Long = 0L

    @Keep
    @JvmStatic
    fun getAssetManager(): AssetManager? = try {
        hostContext?.assets
    } catch (_: Throwable) { null }

    @Keep
    @JvmStatic
    fun getEmulationContext(): Context? = hostContext

    @Keep
    @JvmStatic
    fun getMainContext(): Context? = hostContext

    @Keep
    @JvmStatic
    fun checkPermission(permission: String): Boolean = true

    @Keep
    @JvmStatic
    fun isValidFile(path: String): Boolean = try {
        java.io.File(path).isFile
    } catch (_: Throwable) { false }

    @Keep
    @JvmStatic
    fun notifyGameShudown() {
        android.util.Log.w("CitraMmj", "notifyGameShudown")
        host?.onGameShutdown()
    }

    @Keep
    @JvmStatic
    fun showMessageDialog(type: Int, message: String) {
        android.util.Log.i("CitraMmj", "message[$type] $message")
        host?.onMessage(type, message)
    }

    @Keep
    @JvmStatic
    fun showInputBoxDialog(type: Int, title: String?, message: String?,
                           default: String?, a: String?, b: String?) {
        android.util.Log.i("CitraMmj", "inputBox[$type] $title / $message")
    }

    @Keep
    @JvmStatic
    fun pickImage(w: Int, h: Int) {}

    @Keep
    @JvmStatic
    fun updateProgress(text: String?, done: Long, total: Long) {
        android.util.Log.i("CitraMmj", "progress: $text ($done/$total)")
    }

    @Keep
    @JvmStatic
    fun addNetPlayMessage(type: Int, message: String) {
        android.util.Log.i("CitraMmj", "netplay[$type] $message")
    }

    @Keep
    @JvmStatic
    fun setHardwareBuffer(buffer: HardwareBuffer?) {}

    @Keep
    @JvmStatic
    fun showRunningSetting() {}

    @Keep
    @JvmStatic
    fun loadImageFromFile(path: String) {}

    @Keep
    @JvmStatic
    fun saveImageToFile(path: String, pixels: IntArray, w: Int, h: Int) {}

    /** 宿主 Context（引擎初始化时注入）。 */
    @Volatile
    var hostContext: Context? = null
}

/**
 * RemoteFile* 回调的文件句柄 —— 线程安全的本地文件读取封装。
 * read() 顺序读取下一块（64KB），data() 返回最近读取的字节，
 * status() = 0（正常）/ -1（失败）。
 */
class RemoteFileHandle private constructor(
    private val raf: java.io.RandomAccessFile,
    val size: Int
) {
    private var lastRead: ByteArray = ByteArray(0)

    fun read(): Int = try {
        val remaining = (size - raf.filePointer).toInt()
        if (remaining <= 0) {
            lastRead = ByteArray(0)
            -1
        } else {
            val chunk = ByteArray(minOf(64 * 1024, remaining))
            val n = raf.read(chunk)
            if (n > 0) {
                lastRead = if (n == chunk.size) chunk else chunk.copyOf(n)
                n
            } else -1
        }
    } catch (_: Throwable) { -1 }

    fun data(): ByteArray = lastRead

    fun status(): Int = try {
        if (raf.filePointer <= size) 0 else -1
    } catch (_: Throwable) { -1 }

    fun close() { try { raf.close() } catch (_: Throwable) {} }

    companion object {
        fun open(path: String, mode: String): RemoteFileHandle? {
            val f = java.io.File(path)
            if (!f.isFile) return null
            val raf = java.io.RandomAccessFile(f, if (mode.contains('w') || mode.contains('a')) "rw" else "r")
            return RemoteFileHandle(raf, f.length().toInt())
        }
    }
}
