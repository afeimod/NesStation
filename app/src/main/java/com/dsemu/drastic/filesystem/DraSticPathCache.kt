@file:Suppress("unused")

package com.dsemu.drastic.filesystem

import androidx.annotation.Keep
import java.io.File

/**
 * DraStic 原生层的**文件系统回调桥**（简化重实现，替换原版 700+ 行的
 * SAF/Provider 抽象）。
 *
 * 原生侧按名查找本类的三个静态方法（.so 字符串验证）：
 *  - open(String, String) → NativePathHandle   —— 所有文件 IO 的入口
 *  - remove(String) → boolean
 *  - rename(String, String) → boolean
 *
 * 原版用虚拟路径（"DraStic/" 前缀 = 系统目录，"User/" 前缀 = 用户目录，
 * 无前缀 = changeRom 注册的 ROM 名）。本移植把 ROM 直接以真实绝对路径
 * 传给 startGame，于是 open() 额外支持绝对路径直读 —— 无论原生用哪种
 * 形式构造路径都能解析：
 *
 *   1. 绝对路径（"/..."）        → 直接按真实文件处理
 *   2. "DraStic/xxx"            → systemDir/xxx
 *   3. "User/xxx"               → userDir/xxx（存档/即时存档所在）
 *   4. 其他相对路径（兜底）      → userDir/xxx
 *
 * 所有方法 synchronized —— 原生可能从多个线程（模拟线程/输入线程）回调。
 *
 * 另附一个**写路径观察器**：open() 以写模式打开时把虚拟路径记录下来，
 * saveState 后即可精确定位 DraStic 实际写入的 .dss 文件（避免靠文件名
 * 规则猜测），用于 NesStation 槽位 UI 的存在性检查与备份。
 */
@Keep
object DraSticPathCache {

    /** DraStic 系统目录（对应原版 d.i()；config/ 等）。 */
    @Volatile
    private var systemDir: File? = null

    /** DraStic 用户目录（对应原版 d.j()；savestates/、.dsv 电池存档等）。 */
    @Volatile
    private var userDir: File? = null

    /** ROM 虚拟名 → 真实文件的映射（原版 changeRom）。 */
    private val romMap = HashMap<String, File>()

    /** open() 写模式观察日志（saveState 前清空，事后取最近写入路径）。 */
    private val writeLog = ArrayList<String>()

    /**
     * 设置基目录。必须在 startGame 之前调用。会确保目录存在。
     */
    @Synchronized
    fun setBaseDirs(system: File, user: File) {
        system.mkdirs()
        user.mkdirs()
        systemDir = system
        userDir = user
    }

    /** 注册 ROM（真实绝对路径直接传给 startGame 时无需调用）。 */
    @Synchronized
    fun changeRom(virtualPath: String, realFile: File) {
        romMap.clear()
        romMap[virtualPath] = realFile
    }

    /** 清空写路径观察日志（saveState 前调用）。 */
    @Synchronized
    fun resetWriteLog() {
        writeLog.clear()
    }

    /**
     * 取最近一次以写模式打开的虚拟路径（通常即刚保存的 .dss），
     * 可选过滤后缀（如 "dss"）。无匹配返回 null。
     */
    @Synchronized
    fun lastWritePath(extension: String? = null): String? {
        if (writeLog.isEmpty()) return null
        if (extension == null) return writeLog.last()
        return writeLog.lastOrNull { it.endsWith(".$extension", ignoreCase = true) }
    }

    /** 所有写模式打开的虚拟路径（诊断用）。 */
    @Synchronized
    fun writePaths(): List<String> = ArrayList(writeLog)

    /** 把虚拟路径解析为真实文件。open/remove/rename 共用的解析内核。 */
    private fun resolve(virtualPath: String): File {
        romMap[virtualPath]?.let { return it }
        val sys = systemDir
        val usr = userDir
        return when {
            virtualPath.startsWith("/") -> File(virtualPath)
            virtualPath.startsWith("DraStic/") && sys != null ->
                File(sys, virtualPath.removePrefix("DraStic/"))
            virtualPath.startsWith("User/") && usr != null ->
                File(usr, virtualPath.removePrefix("User/"))
            else -> if (usr != null) File(usr, virtualPath) else File(virtualPath)
        }
    }

    // ------------------------------------------------------------------
    // 原生回调（签名严格对齐原版 DraSticPathCache）
    // ------------------------------------------------------------------

    /**
     * 原生以 fopen 风格请求打开文件。
     * @param mode 原版模式串（"r"/"w"/"r+"/"w+"/"b" 变体），与原版一样
     *             忽略其中的 "b"
     * @return 文件句柄；文件不存在时按原版语义返回 null（由调用方自检）
     */
    @JvmStatic
    @Synchronized
    fun open(virtualPath: String, mode: String): NativePathHandle? {
        val normalized = mode.replace("b", "")
        val isWrite = normalized.contains('w') || normalized.contains("r+")
        if (isWrite) writeLog.add(virtualPath)

        val real = resolve(virtualPath)
        if (isWrite) {
            // 写模式：确保父目录存在（原版 Provider 语义），避免深层路径首次写入失败
            real.parentFile?.mkdirs()
            return NativePathHandle(real)
        }
        // 读模式：不存在时返回 null（原生按 fopen 失败处理）
        return if (real.isFile) NativePathHandle(real) else null
    }

    /** 原生请求删除文件。 */
    @JvmStatic
    @Synchronized
    fun remove(virtualPath: String): Boolean {
        return try {
            resolve(virtualPath).delete()
        } catch (e: Exception) {
            false
        }
    }

    /** 原生请求重命名（原版限定同目录内改名）。 */
    @JvmStatic
    @Synchronized
    fun rename(fromVirtual: String, toVirtual: String): Boolean {
        return try {
            val from = resolve(fromVirtual)
            val to = resolve(toVirtual)
            to.parentFile?.mkdirs()
            from.renameTo(to)
        } catch (e: Exception) {
            false
        }
    }

    /** 保留原版 API 形状（设置 Context）—— 本实现用不到，空操作。 */
    @JvmStatic
    fun setAppContext(context: Any?) {
        // 基目录由 setBaseDirs 显式配置；无需 Context。
    }

    /** 测试/诊断：直接解析虚拟路径。 */
    @Synchronized
    fun resolveForTest(virtualPath: String): File? = try {
        resolve(virtualPath)
    } catch (e: Exception) {
        null
    }
}
