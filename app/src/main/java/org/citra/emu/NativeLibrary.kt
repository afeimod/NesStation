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
     *
     * ★★ 闪退根治（本轮）：★★
     *   旧实现只支持本地裸路径 —— 两种真实场景会拿到 null 句柄：
     *     1. 引擎/核心传入 "!"-前缀的原生绝对路径（Run("!path") 同款标记，
     *        Java File("!/storage/...").isFile == false）→ null；
     *     2. SAF 导入的游戏是 content:// URI → File() 必然 false → null。
     *   核心拿到 null 后继续解引用 → 原生 SIGSEGV → "MMJ 运行游戏闪退"。
     *   现在：剥 "!" 前缀；content:// 走 ContentResolver（只读 / 可写两种
     *   模式都支持，写入用 "w" 模式的 ParcelFileDescriptor）；其余照旧本地文件。
     */
    @Keep
    @JvmStatic
    fun RemoteFileOpen(path: String, mode: String): Any? = try {
        val p = path.removePrefix("!")
        if (p.startsWith("content://")) {
            // content:// 统一走 SafOpen 的 ContentResolver 通道（同款流式句柄）
            SafOpen(path, mode)
        } else {
            RemoteFileHandle.open(p, mode)
        }
    } catch (_: Throwable) { null }

    @Keep
    @JvmStatic
    fun RemoteFileRead(handle: Any?): Int = when (handle) {
        is RemoteFileHandle -> handle.read()
        is SafHandle -> handle.read()
        else -> -1
    }

    @Keep
    @JvmStatic
    fun RemoteFileSize(handle: Any?): Int = when (handle) {
        is RemoteFileHandle -> handle.size
        is SafHandle -> handle.size
        else -> 0
    }

    @Keep
    @JvmStatic
    fun RemoteFileData(handle: Any?): ByteArray? = when (handle) {
        is RemoteFileHandle -> handle.data()
        is SafHandle -> handle.data()
        else -> null
    }

    @Keep
    @JvmStatic
    fun RemoteFileStatus(handle: Any?): Int = when (handle) {
        is RemoteFileHandle -> handle.status()
        is SafHandle -> handle.status()
        else -> -1
    }

    @Keep
    @JvmStatic
    fun RemoteFileClose(handle: Any?) {
        when (handle) {
            is RemoteFileHandle -> handle.close()
            is SafHandle -> handle.close()
        }
    }

    /**
     * SAF 文件访问（content:// URI）—— ★★ 闪退根治（本轮）：旧实现直接返回
     *   null/-1，核心对 content:// ROM 调 SafOpen 拿 null 后崩溃。现在返回
     *   真实可用的 ParcelFileDescriptor 句柄（SafHandle）：
     *     - SafOpen：content:// 经 ContentResolver 打开（支持读/写模式）；
     *       本地路径（含 "!" 前缀）转交 RemoteFileHandle 同款逻辑。
     *     - SafNativeFd：返回底层 fd（dup 一份，核心用完关闭不影响句柄）。
     *     - SafClose：关闭句柄；SafLastModified：返回真实 mtime。
     */
    @Keep
    @JvmStatic
    fun SafOpen(path: String, mode: String): Any? {
        return try {
            val p = path.removePrefix("!")
            if (p.startsWith("content://")) {
                val ctx = hostContext ?: return null
                val write = mode.contains('w') || mode.contains('a')
                val uri = android.net.Uri.parse(p)
                val pfd = if (write) {
                    ctx.contentResolver.openFileDescriptor(uri, "wt")
                } else {
                    ctx.contentResolver.openFileDescriptor(uri, "r")
                }
                // ★ 流式句柄：不预读全量（大 ROM 可达 GB 级，预读必 OOM）——
                //   read() 按需从 fd 流式读块；核心若走 SafNativeFd 拿 fd 直读
                //   则完全不碰 read()。两条路径都安全。
                pfd?.let { SafHandle(it) }
            } else {
                RemoteFileHandle.open(p, mode)
            }
        } catch (_: Throwable) { null }
    }

    @Keep
    @JvmStatic
    fun SafNativeFd(handle: Any?): Int = try {
        when (handle) {
            is SafHandle -> android.os.ParcelFileDescriptor.dup(handle.pfd.fileDescriptor).detachFd()
            is RemoteFileHandle -> {
                // 本地文件句柄：取 fd 需要 dup —— RandomAccessFile 没有直接 fd 接口，
                // 返回 -1 让核心回落到 RemoteFileData 字节流通道（安全路径）。
                -1
            }
            else -> -1
        }
    } catch (_: Throwable) { -1 }

    @Keep
    @JvmStatic
    fun SafClose(handle: Any?) {
        try {
            when (handle) {
                is SafHandle -> handle.close()
                is RemoteFileHandle -> handle.close()
            }
        } catch (_: Throwable) {}
    }

    @Keep
    @JvmStatic
    fun SafLastModified(path: String): Long {
        return try {
            val p = path.removePrefix("!")
            if (p.startsWith("content://")) {
                val ctx = hostContext ?: return 0L
                val uri = android.net.Uri.parse(p)
                // ★ 返回真实 mtime（旧版误查 OpenableColumns.SIZE，把文件大小
                //   当时间戳返回）。优先 DocumentsContract 的 last_modified 列；
                //   提供方不支持时回落 fstat(pfd) 拿真实 mtime。
                val queried = ctx.contentResolver.query(
                    uri,
                    arrayOf(android.provider.DocumentsContract.Document.COLUMN_LAST_MODIFIED),
                    null, null, null
                )?.use { c ->
                    if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else null
                }
                queried ?: ctx.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                    try {
                        android.system.Os.fstat(pfd.fileDescriptor).st_mtime
                    } catch (_: Throwable) { 0L }
                } ?: 0L
            } else {
                java.io.File(p).lastModified()
            }
        } catch (_: Throwable) { 0L }
    }

    @Keep
    @JvmStatic
    fun getAssetManager(): AssetManager? = try {
        hostContext?.assets
    } catch (_: Throwable) { null }

    /**
     * ★★★ 启动闪退根治（本轮，libcitra_mmj.so 反汇编实证）★★★
     *
     * Run() 引导早期（updateProgress("BootGame") 之后）会执行签名/环境收集例程：
     * ```
     * ctx = NativeLibrary.getEmulationContext()
     * pm = ctx.getPackageManager()
     * pkgInfo = pm.getPackageInfo(ctx.getPackageName(), 64 /*GET_SIGNATURES*/)
     * sig = pkgInfo.signatures[0].toByteArray() → 哈希存入全局状态
     * versionName / packageName / codePath 逐一 GetStringUTFChars → std::string
     * name = ctx.getString(0x7f100020)   ← ★ 原包 R.string.app_name = "Citra"
     * ```
     *
     * 崩溃链（用户 tombstone，Redmi socrates / Android 15）：
     *   Run+108 → G(0x275928) → 0x276050 bl H → H 内 bl strlen(NULL) → SIGSEGV。
     * 根因：我们的 APK 资源表没有 0x7f100020 这一项 → Context.getString 抛
     * Resources$NotFoundException（JNI 挂起）→ CallObjectMethodV 返回 NULL →
     * GetStringUTFChars(NULL) 返回 NULL → strlen(NULL) 原生段错误。
     *
     * 修复：返回一个 ContextWrapper，其 getResources() 返回的 Resources 对
     * 0x7f100020 固定应答 "Citra"（与 Citra_MMJ_20250220.apk 的 app_name 逐字
     * 一致 —— 已从该 APK 的 resources.arsc 提取验证：type 0x10/string，
     * entry 0x20，值 "Citra"）。boot 阶段的核心还会比对 app_name == "Citra"
     * （len==5 + "Citr"+'a' 内联比较，so 0x2676bc），该值同时通过正版检查。
     * 其余资源 id 透传宿主，不影响 NesStation 自身 UI（包装仅给 MMJ 核心用）。
     */
    @Keep
    @JvmStatic
    fun getEmulationContext(): Context? = hostContext?.let(::mmjCompatContext)

    @Keep
    @JvmStatic
    fun getMainContext(): Context? = hostContext?.let(::mmjCompatContext)

    /** 兼容 Context 缓存（每个 base 只包一层；核心可能多次调用）。 */
    @Volatile
    private var cachedCompatContext: Context? = null

    @Volatile
    private var cachedCompatBase: Context? = null

    private fun mmjCompatContext(base: Context): Context {
        cachedCompatContext?.let { cached ->
            if (cachedCompatBase === base) return cached
        }
        synchronized(this) {
            cachedCompatContext?.let { cached ->
                if (cachedCompatBase === base) return cached
            }
            val wrapped = MmjCompatContext(base)
            cachedCompatBase = base
            cachedCompatContext = wrapped
            return wrapped
        }
    }

    /**
     * MMJ 核心专用 Context 包装：仅重写 getResources()，其余全部委托宿主。
     * Context.getString(int) 是 final（内部走 getResources().getString(id)），
     * 所以拦截点在 Resources 层 —— so 全库仅此一处资源查找（movk 0x7f10 扫描实证）。
     */
    private class MmjCompatContext(base: Context) : android.content.ContextWrapper(base) {

        private val mmjResources: android.content.res.Resources by lazy {
            MmjCompatResources(base.resources)
        }

        override fun getResources(): android.content.res.Resources = mmjResources
    }

    /**
     * 对 0x7f100020（原包 app_name）应答 "Citra"，其余 id 走宿主真实资源。
     * 构造用同 AssetManager —— 除被重写的 id 外行为与宿主 Resources 完全一致。
     */
    private class MmjCompatResources(base: android.content.res.Resources) :
        android.content.res.Resources(base.assets, base.displayMetrics, base.configuration) {

        override fun getString(id: Int): String =
            if (id == MMJ_RES_APP_NAME) MMJ_APP_NAME_VALUE else super.getString(id)

        companion object {
            /** Citra_MMJ_20250220.apk 的 R.string.app_name（resources.arsc 实证）。 */
            private const val MMJ_RES_APP_NAME = 0x7f100020

            /** 原包该资源的值（也是 so 正版检查的期望值）。 */
            private const val MMJ_APP_NAME_VALUE = "Citra"
        }
    }

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
 *
 * ★★ 本轮加固：open() 剥离 "!" 前缀（引擎 Run/SetUserPath 同款原生绝对路径
 *   标记）—— 旧实现对 "!/storage/..." 直接 File().isFile=false 返回 null，
 *   核心拿 null 句柄继续用 → SIGSEGV 闪退。
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
            // ★ 剥 "!" 前缀：核心经 TranslateFilePath 后可能把带标记的原生
            //   路径原样传回 Java 回调（Run("!path") 同款约定）。
            val p = path.removePrefix("!")
            val f = java.io.File(p)
            if (!f.isFile) return null
            val raf = java.io.RandomAccessFile(f, if (mode.contains('w') || mode.contains('a')) "rw" else "r")
            return RemoteFileHandle(raf, f.length().toInt())
        }
    }
}

/**
 * Saf* 回调的 content:// 句柄 —— 包装 ParcelFileDescriptor 的流式读取。
 * read()/data() 与 RemoteFileHandle 同契约（核心按字节流消费）；
 * size 取打开时 statSize 快照；close() 关闭 pfd。不预读全量 ——
 * 大 ROM（GB 级）预读必 OOM，流式按块读才安全。
 */
class SafHandle(val pfd: android.os.ParcelFileDescriptor) {
    private val stream = java.io.FileInputStream(pfd.fileDescriptor)
    private var lastRead: ByteArray = ByteArray(0)

    val size: Int = try {
        // ★ statSize 是 Kotlin 合成属性（Java getter getStatSize()），不能加括号调用。
        //   GB 级 ROM 钳制到 Int.MAX_VALUE，避免 toInt() 溢出变负数后归 0。
        pfd.statSize.coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()
    } catch (_: Throwable) { 0 }

    fun read(): Int = try {
        val chunk = ByteArray(64 * 1024)
        val n = stream.read(chunk)
        if (n > 0) {
            lastRead = if (n == chunk.size) chunk else chunk.copyOf(n)
            n
        } else {
            lastRead = ByteArray(0)
            -1
        }
    } catch (_: Throwable) { -1 }

    fun data(): ByteArray = lastRead

    fun status(): Int = 0

    fun close() {
        try { stream.close() } catch (_: Throwable) {}
        try { pfd.close() } catch (_: Throwable) {}
    }
}
