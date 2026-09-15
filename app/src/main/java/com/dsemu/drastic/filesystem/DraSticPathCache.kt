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
 * ## 读模式语义（关键，与原版严格一致）
 *
 * 原版 File 后端（jadx 反编译 h0.b.e()）对读模式**无论文件是否存在都返回
 * 句柄**（filePath 指向解析出的真实路径），文件不存在时由原生侧 fopen
 * 失败自行处理（可选文件如真实 nds_bios_*.bin 缺失时走内置回退）。
 * 本类同样**永不返回 null** —— 原生层对 open() 结果不做判空（原版从不
 * 返回 null），返回 null 会导致 JNI 读取字段时空指针解引用，或触发
 * 原生错误分支以未初始化的 jmp_buf 调 longjmp → siglongjmp 处
 * SIGSEGV 闪退（Redmi K60 Ultra 上选激烈核心闪退的根因之一）。
 *
 * ## 布局候选回退
 *
 * 原版系统目录内还有二级结构（system/、config/、users/ 子目录）。为容忍
 * 不同版本原生库的路径构造差异，读模式按候选序列取第一个存在的文件：
 * "DraStic/system/x" 会依次尝试 systemDir/system/x → systemDir/x；
 * "DraStic/x" 会依次尝试 systemDir/x → systemDir/system/x。写模式恒用
 * 精确路径（保证回读一致性）。
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

    /**
     * 电池存档（.sav/.dsv）全局重定向目标。
     *
     * 原生把电池存档路径固定构造为 `User/backup/<ROM基名>.sav|.dsv`（反汇编
     * libdrastic_arm64.so："%s%cbackup%c%s.sav"/"%s%cbackup%c%s.dsv"，基名取自
     * changeRom 注册的 ROM）。NesStation 的"全局存档方式"要求所有核心共用
     * 同一份 .sav（nesstation 模式 = <filesDir>/saves/<gameId>.sav；core_builtin
     * 模式 = ROM 同目录 <ROM名>.sav，与官方 melonDS APK 兼容）。
     *
     * 设置本目标后，`User/backup/` 下的 .sav 与 .dsv 一律优先解析到
     * `<dir>/<base>.<ext>` —— 读写同一份全局存档文件，切核心互认存档。
     * 标准候选保留在映射目标之后作为读兜底（全局文件缺失时仍可读旧档）。
     */
    @Volatile
    private var batterySaveDir: File? = null

    @Volatile
    private var batterySaveBase: String? = null

    /** open() 写模式观察日志（saveState 前清空，事后取最近写入路径）。 */
    private val writeLog = ArrayList<String>()

    /**
     * 设置电池存档全局重定向目标（loadRom 时调用）。
     * @param dir 全局存档目录（null = 关闭重定向，走原版默认 User/backup/）
     * @param base 全局存档基名（不含扩展名；null = 沿用 ROM 基名）
     */
    @Synchronized
    fun setBatterySaveTarget(dir: File?, base: String?) {
        batterySaveDir = dir?.apply { mkdirs() }
        batterySaveBase = base?.takeIf { it.isNotBlank() }
    }

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

    /** 把虚拟路径解析为候选真实文件列表（读模式按序取第一个存在的；写模式取首个=精确路径）。 */
    private fun resolveCandidates(virtualPath: String): List<File> {
        romMap[virtualPath]?.let { return listOf(it) }
        val sys = systemDir
        val usr = userDir
        val out = ArrayList<File>(3)
        when {
            virtualPath.startsWith("/") -> out.add(File(virtualPath))
            virtualPath.startsWith("DraStic/") && sys != null -> {
                val rest = virtualPath.removePrefix("DraStic/")
                out.add(File(sys, rest))                       // 精确映射（与原版一致）
                if (rest.startsWith("system/")) {
                    out.add(File(sys, rest.removePrefix("system/")))  // 布局兜底：→ sys/x
                } else {
                    out.add(File(sys, "system/$rest"))                // 布局兜底：→ sys/system/x
                }
            }
            virtualPath.startsWith("User/") && usr != null -> {
                // 电池存档全局重定向（"全局 sav 存档"识别的核心）：
                // User/backup/<任意基名>.sav|.dsv → <全局目录>/<全局基名>.<扩展名>
                if (virtualPath.startsWith("User/backup/")) {
                    val name = virtualPath.removePrefix("User/backup/")
                    val dot = name.lastIndexOf('.')
                    if (dot > 0 && batterySaveDir != null) {
                        val ext = name.substring(dot + 1).lowercase()
                        if (ext == "sav" || ext == "dsv") {
                            val base = batterySaveBase ?: name.substring(0, dot)
                            out.add(File(batterySaveDir, "$base.$ext"))
                        }
                    }
                }
                out.add(File(usr, virtualPath.removePrefix("User/")))
            }
            else -> out.add(if (usr != null) File(usr, virtualPath) else File(virtualPath))
        }
        return out
    }

    /** 解析虚拟路径：优先已存在的候选，否则取首个（精确路径）。open/remove/rename 共用的解析内核。 */
    private fun resolve(virtualPath: String): File {
        val candidates = resolveCandidates(virtualPath)
        return candidates.firstOrNull { it.exists() } ?: candidates[0]
    }

    // ------------------------------------------------------------------
    // 原生回调（签名严格对齐原版 DraSticPathCache）
    // ------------------------------------------------------------------

    /**
     * 原生以 fopen 风格请求打开文件。
     * @param mode 原版模式串（"r"/"w"/"r+"/"w+"/"b" 变体），与原版一样
     *             忽略其中的 "b"
     * @return 文件句柄。**读模式与写模式都永不返回 null**（对齐原版
     *         h0.b.e() 语义：不存在时也返回句柄，由原生侧 fopen 失败
     *         自行处理 —— 原生不判空，返回 null 会直接闪退）。
     */
    @JvmStatic
    @Synchronized
    fun open(virtualPath: String, mode: String): NativePathHandle? {
        val normalized = mode.replace("b", "")
        val isWrite = normalized.contains('w') || normalized.contains("r+")
        if (isWrite) writeLog.add(virtualPath)

        val candidates = resolveCandidates(virtualPath)
        val real = if (isWrite) {
            candidates[0]  // 写模式：精确路径（确保回读一致）
        } else {
            // 读模式：按候选序取第一个存在的；都不存在时取精确路径
            // （句柄仍返回，原生 fopen 失败自行处理 —— 同原版）
            candidates.firstOrNull { it.isFile } ?: candidates[0]
        }
        if (isWrite) {
            // 写模式：确保父目录存在（原版 Provider 语义），避免深层路径首次写入失败
            real.parentFile?.mkdirs()
        }
        return NativePathHandle(real)
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
