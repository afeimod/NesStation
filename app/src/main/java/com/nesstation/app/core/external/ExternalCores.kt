package com.nesstation.app.core.external

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.Parcelable
import android.os.Environment
import dalvik.system.DexClassLoader
import java.io.File
import java.io.RandomAccessFile

/**
 * 外部独立核心桥（External Cores Bridge）
 * ===========================================================================
 *
 * 3DS（Azahar / 爱吾 AzaharPlus）与 NGC/WII（Ishiruka — Dolphin fork）以
 * **独立模拟器形态** 集成（与 README 中 Flycast 独立核心的历史集成形态一致）：
 *
 *   - 游戏库 / 平台页 / 扫描 / 设置页 / 启动桥 由 NesStation 负责；
 *   - 模拟画面与触摸层（含 NGC/WII 全量虚拟按键：GameCube 手柄 /
 *     Wii Remote / Nunchuk / Classic Controller / 体感摇动·倾斜·IR 指针）
 *     由核心 APK 自带的 EmulationActivity 呈现 —— 两个核心均为完整的
 *     独立模拟器，自带 overlay 与全部控制器切换能力；
 *   - 启动时经 [launch3dsGame] / [launchNgcwiiGame] 桥接传参。
 *
 * ## 3DS 启动协议（Azahar/爱吾 2125.1.2 反编译确认）
 *
 * `org.citra.citra_emu.activities.EmulationActivity`（exported=true）的
 * onCreate 仅从 Intent extras 读取一个键：`"game"`
 * （经 androidx.core.os.BundleCompat.getParcelable 读取），类型为
 * `org.citra.citra_emu.model.Game`（Parcelable，位于核心 APK 的
 * classes2.dex）。该 Activity 没有 ACTION_VIEW 代码路径（全 dex 无
 * getAction 调用），因此不能靠 manifest 的 VIEW filter 传参。
 *
 * 桥接方案（[buildAzaharGame]）：
 *   1. 经 PackageManager 拿到核心 APK 路径（sourceDir）；
 *   2. [DexClassLoader] 动态装载核心 dex（与 J2ME 游戏动态装载同一思路）；
 *   3. 反射调用 16 参主构造器构造 Game（字段序即构造参数序，Kotlin
 *      data class 保证）：
 *      valid, path, title, company, titleId, mediaType, regions,
 *      description, isCompressed, isSystemTitle, isVisibleSystemTitle,
 *      isInstalled, icon, fileType, isInsertable, filename；
 *   4. `bundle.putParcelable("game", game)` + 显式组件启动。
 *      Bundle 跨进程打包时走 Game 自身 CREATOR —— 目标进程内类名一致，
 *      反序列化天然命中核心 APK 内的同名类。
 *
 * ## NGC/WII 启动协议（Ishiruka 5.0-15560 反编译确认）
 *
 * `org.dolphinemu.dolphinemu.activities.EmulationActivity`（exported=true）
 * onCreate 读取：
 *   - `"SelectedGames"` : String[] —— 游戏路径（数组，通常单元素）；
 *   - `"Platform"`      : Int —— 0=GameCube / 1=Wii / 2=WiiWare；
 *   - `"SelectedTitle"` / `"SelectedGameId"` / `"SavedState"` : 可选。
 *
 * ## 游戏路径解析（[resolveGamePath]）
 *
 * 两个核心都按真实文件路径打开游戏。本地扫描入库的游戏直接传原路径；
 * SAF content:// 入库的游戏复制到公共桥接目录
 * `/sdcard/NesStation/bridge/<platform>/`（需"所有文件访问"权限，两个核心
 * 均按全文件权限模式使用）。文件名保留原始扩展名 —— Ishiruka / Azahar
 * 依赖扩展名识别容器格式。
 *
 * ## 3DS 游戏解密（[is3dsRomEncrypted] / [writeAesKeys]）
 *
 * 加密状态检测：NCCH 容器头 0x188 处的 flags[3]（crypto method）：
 * 0=无加密（已解密），非 0 = 需要 aes_keys.txt（Azahar 读取
 * `<用户目录>/keys/aes_keys.txt`，详见 writeAesKeys）。
 */
object ExternalCores {

    // === 核心包名 / 组件名（反编译 manifest 确认） =========================

    /** 爱吾 AzaharPlus 2125.1.2（3DS 核心，Citra/Azahar fork）。 */
    const val AZAHAR_PACKAGE = "com.aiwu.citra_emu"
    const val AZAHAR_EMULATION_ACTIVITY =
        "org.citra.citra_emu.activities.EmulationActivity"
    const val AZAHAR_MAIN_ACTIVITY =
        "org.citra.citra_emu.ui.main.MainActivity"
    const val AZAHAR_GAME_CLASS = "org.citra.citra_emu.model.Game"
    const val AZAHAR_MEDIA_TYPE_CLASS = "org.citra.citra_emu.model.Game\$MediaType"

    /** Ishiruka 01（NGC/WII 核心，Dolphin 5.0-15560 fork）。 */
    const val ISHIRUKA_PACKAGE = "org.dolphin.ishiiruka"
    const val ISHIRUKA_EMULATION_ACTIVITY =
        "org.dolphinemu.dolphinemu.activities.EmulationActivity"
    const val ISHIRUKA_MAIN_ACTIVITY =
        "org.dolphinemu.dolphinemu.ui.main.MainActivity"

    // Dolphin EmulationActivity "Platform" extra 的取值（GameFile.platform）。
    const val DOLPHIN_PLATFORM_GC = 0
    const val DOLPHIN_PLATFORM_WII = 1
    const val DOLPHIN_PLATFORM_WIIWARE = 2

    // Intent extra 键名（Ishiruka onCreate 字节码提取）。
    private const val EXTRA_SELECTED_GAMES = "SelectedGames"
    private const val EXTRA_PLATFORM = "Platform"

    // NesStation 自身偏好存储键（桥接配置持久化）。
    private const val PREFS = "external_cores"
    private const val KEY_AZAHAR_USER_DIR = "azahar_user_dir_uri"
    private const val KEY_ISHIRUKA_USER_DIR = "ishiruka_user_dir_path"

    // =========================================================================
    // 安装 / 版本检测
    // =========================================================================

    /** 核心是否已安装。 */
    fun isInstalled(context: Context, packageName: String): Boolean {
        return try {
            context.packageManager.getApplicationInfo(packageName, 0)
            true
        } catch (_: PackageManager.NameNotFoundException) {
            false
        }
    }

    fun isAzaharInstalled(context: Context) = isInstalled(context, AZAHAR_PACKAGE)
    fun isIshirukaInstalled(context: Context) = isInstalled(context, ISHIRUKA_PACKAGE)

    /** 核心版本名（读取失败返回空串）。 */
    fun coreVersion(context: Context, packageName: String): String {
        return try {
            context.packageManager.getPackageInfo(packageName, 0).versionName ?: ""
        } catch (_: Exception) {
            ""
        }
    }

    /** 核心 APK 的绝对路径（DexClassLoader 装载用），未安装返回 null。 */
    fun coreApkPath(context: Context, packageName: String): String? {
        return try {
            val info = context.packageManager.getApplicationInfo(packageName, 0)
            info.sourceDir ?: info.publicSourceDir
        } catch (_: Exception) {
            null
        }
    }

    // =========================================================================
    // 游戏路径解析 —— content:// 复制到公共桥接目录
    // =========================================================================

    /**
     * 把 [romPath] 解析为两个外部核心都能直接打开的真实文件路径。
     *
     * - 已是真实路径（本地扫描 / 对战平台下载）：原样返回；
     * - content://（SAF 导入）：复制到公共桥接目录并返回复制后的路径。
     *   桥接目录在公共存储，NesStation 与核心（均为全文件权限模式）
     *   都可读写。未授予"所有文件访问"时返回 null（调用方提示）。
     *
     * @param launchExt 期望的文件扩展名（不含点），决定复制后的文件名。
     */
    fun resolveGamePath(
        context: Context,
        romPath: String?,
        launchExt: String,
        platformDir: String
    ): File? {
        if (romPath.isNullOrBlank()) return null
        if (!romPath.startsWith("content://")) {
            val f = File(romPath)
            return if (f.exists()) f else null
        }
        // SAF 游戏必须落到公共存储
        if (!hasAllFilesAccess(context)) return null
        val bridgeDir = File(
            Environment.getExternalStorageDirectory(),
            "NesStation/bridge/$platformDir"
        ).apply { mkdirs() }
        if (!bridgeDir.canWrite()) return null
        val out = File(bridgeDir, "game_$platformDir.${launchExt.lowercase()}")
        // 同名覆盖（每次启动都重新拷贝，保证与源同步；失败时复用旧副本兜底）
        return try {
            context.contentResolver.openInputStream(Uri.parse(romPath))?.use { input ->
                out.outputStream().buffered().use { output ->
                    input.copyTo(output, 1024 * 512)
                }
            }
            if (out.length() > 0) out else null
        } catch (_: Exception) {
            if (out.exists() && out.length() > 0) out else null
        }
    }

    /**
     * 是否已授予对应的公共存储访问权限：
     * - Android 11+：MANAGE_EXTERNAL_STORAGE（所有文件访问）；
     * - Android 10 及以下：WRITE_EXTERNAL_STORAGE 已在 manifest 声明且
     *   运行时授过（LibraryScreen 的存储权限流程），直接返回 true。
     * SAF 导入的游戏桥接启动时依赖此权限复制到公共目录。
     */
    fun hasAllFilesAccess(context: Context? = null): Boolean {
        return try {
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                Environment.isExternalStorageManager()
            } else {
                context?.let {
                    androidx.core.content.ContextCompat.checkSelfPermission(
                        it, android.Manifest.permission.WRITE_EXTERNAL_STORAGE
                    ) == android.content.pm.PackageManager.PERMISSION_GRANTED
                } ?: true   // ≤ Android 10 声明即授权（legacy external storage）
            }
        } catch (_: Throwable) {
            false
        }
    }

    // =========================================================================
    // 3DS（Azahar / 爱吾）启动
    // =========================================================================

    /**
     * 启动一个 3DS 游戏。
     *
     * @return null = 已成功发起启动；非 null = 用户可读的错误说明。
     * @param onFallbackMain 核心已安装但 Game 桥接失败时的兜底回调
     *        （打开核心主界面让用户手动选择游戏）。
     */
    fun launch3dsGame(
        context: Context,
        gamePath: String,
        launchExt: String,
        onFallbackMain: () -> Unit
    ): String? {
        if (!isAzaharInstalled(context)) {
            return "未检测到 3DS 核心应用（AzaharPlus / 爱吾3DS模拟器，包名 " +
                "$AZAHAR_PACKAGE）。请先安装核心 APK 后重试。"
        }
        val resolved = resolveGamePath(context, gamePath, launchExt, "3ds")
            ?: return ("无法解析游戏文件路径：" +
                if (romPathIsContent(gamePath) && !hasAllFilesAccess(context))
                    "该游戏是通过 SAF 导入的，需要先授予 NesStation「所有文件访问」权限，" +
                    "游戏文件才能复制到公共桥接目录供核心读取。"
                else "文件不存在或不可读。")

        val game = try {
            buildAzaharGame(context, resolved)
        } catch (t: Throwable) {
            android.util.Log.e("ExternalCores", "Azahar Game 桥接失败", t)
            null
        }
        if (game == null) {
            // Game Parcelable 桥接失败（核心版本差异等）——兜底打开核心主界面
            onFallbackMain()
            return null
        }
        val bundle = Bundle().apply { putParcelable("game", game) }
        val intent = Intent().apply {
            setClassName(AZAHAR_PACKAGE, AZAHAR_EMULATION_ACTIVITY)
            putExtras(bundle)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return try {
            context.startActivity(intent)
            null
        } catch (t: Throwable) {
            android.util.Log.e("ExternalCores", "Azahar 启动失败", t)
            "启动 3DS 核心 EmulationActivity 失败：${t.message}"
        }
    }

    private fun romPathIsContent(path: String?) = path != null && path.startsWith("content://")

    /**
     * 经 [DexClassLoader] 反射构造核心的 Game Parcelable。
     * 字段/构造参数序来自反编译（见类注释），构造前做最小合法性校验，
     * 任何异常都向上抛出由调用方兜底。
     */
    private fun buildAzaharGame(context: Context, rom: File): Any {
        val apkPath = coreApkPath(context, AZAHAR_PACKAGE)
            ?: throw IllegalStateException("核心 APK 路径不可用")
        val odexDir = File(context.cacheDir, "azahar_dexopt").apply { mkdirs() }
        val loader = DexClassLoader(apkPath, odexDir.absolutePath, null, context.classLoader)
        val gameClass = loader.loadClass(AZAHAR_GAME_CLASS)
        val mediaTypeClass = loader.loadClass(AZAHAR_MEDIA_TYPE_CLASS)
        val gameCard = mediaTypeClass.getDeclaredField("GAME_CARD").get(null)

        // 构造器：(Z valid, String path, String title, String company,
        //          J titleId, MediaType mediaType, String regions,
        //          String description, Z isCompressed, Z isSystemTitle,
        //          Z isVisibleSystemTitle, Z isInstalled, int[] icon,
        //          String fileType, Z isInsertable, String filename)
        val ctor = gameClass.constructors.firstOrNull { c ->
            val ps = c.parameterTypes
            ps.size == 16 &&
                ps[0] == Boolean::class.javaPrimitiveType &&
                ps[1] == String::class.java &&
                ps[4] == Long::class.javaPrimitiveType &&
                ps[5] == mediaTypeClass &&
                ps[12] == IntArray::class.java
        } ?: throw NoSuchMethodException("Azahar Game 16参构造器未找到（核心版本不兼容）")

        val title = rom.nameWithoutExtension
        return ctor.newInstance(
            true,                       // valid —— EmulationActivity 据此放行启动
            rom.absolutePath,           // path —— 核心按路径打开游戏
            title,                      // title
            "",                         // company
            0L,                         // titleId（CIA 启动时核心自行解析）
            gameCard,                   // mediaType = GAME_CARD（文件型游戏）
            "",                         // regions
            "",                         // description
            false,                      // isCompressed
            false,                      // isSystemTitle
            false,                      // isVisibleSystemTitle
            false,                      // isInstalled（非 NAND 安装标题）
            null,                       // icon（核心侧自行读取图标）
            "",                         // fileType
            false,                      // isInsertable
            rom.name                    // filename
        )
    }

    // =========================================================================
    // NGC/WII（Ishiruka / Dolphin fork）启动
    // =========================================================================

    /**
     * 根据扩展名猜测 Dolphin 平台 extra（0=GC / 1=Wii）。
     * .wad/.wbfs/.rvz 几乎必为 Wii；.gcm/.dol/.elf 默认 GC；
     * .iso/.gcz/.ciso/.nkit 常见为 Wii 备份，也判 Wii（GC 镜像通过
     * Ishiruka 侧自查仍可正常识别，Platform extra 仅影响 overlay 偏好）。
     */
    fun guessDolphinPlatform(ext: String): Int {
        return when (ext.lowercase()) {
            "gcm", "dol", "elf" -> DOLPHIN_PLATFORM_GC
            else -> DOLPHIN_PLATFORM_WII
        }
    }

    /**
     * 启动一个 NGC/WII 游戏（Ishiruka EmulationActivity，
     * extras: SelectedGames=[path] + Platform）。
     */
    fun launchNgcwiiGame(
        context: Context,
        gamePath: String,
        launchExt: String
    ): String? {
        if (!isIshirukaInstalled(context)) {
            return "未检测到 NGC/WII 核心应用（Ishiruka，包名 " +
                "$ISHIRUKA_PACKAGE）。请先安装核心 APK 后重试。"
        }
        val resolved = resolveGamePath(context, gamePath, launchExt, "ngcwii")
            ?: return ("无法解析游戏文件路径：" +
                if (romPathIsContent(gamePath) && !hasAllFilesAccess(context))
                    "该游戏是通过 SAF 导入的，需要先授予 NesStation「所有文件访问」权限，" +
                    "游戏文件才能复制到公共桥接目录供核心读取。"
                else "文件不存在或不可读。")

        val intent = Intent().apply {
            setClassName(ISHIRUKA_PACKAGE, ISHIRUKA_EMULATION_ACTIVITY)
            putExtra(EXTRA_SELECTED_GAMES, arrayOf(resolved.absolutePath))
            putExtra(EXTRA_PLATFORM, guessDolphinPlatform(launchExt))
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return try {
            context.startActivity(intent)
            null
        } catch (t: Throwable) {
            android.util.Log.e("ExternalCores", "Ishiruka 启动失败", t)
            "启动 NGC/WII 核心 EmulationActivity 失败：${t.message}"
        }
    }

    /** 兜底：打开核心主界面（Game 桥接失败 / 用户手动模式）。 */
    fun launchCoreMainActivity(context: Context, packageName: String, activityName: String) {
        try {
            val intent = Intent().apply {
                setClassName(packageName, activityName)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (t: Throwable) {
            android.util.Log.e("ExternalCores", "打开核心主界面失败", t)
        }
    }

    // =========================================================================
    // 3DS 游戏解密 —— NCCH 加密检测 + aes_keys.txt 管理
    // =========================================================================

    /**
     * 检测 3DS 游戏是否加密。
     *
     * NCCH 容器：头部 0x188 偏移处有 8 字节 flags，flags[3] 为
     * crypto method —— 0 = 无加密（已解密），非 0（fixed-key 1 /
     * secure 2..4 / 7.x keys）= 加密。
     * - .3ds/.cci：首个 NCCH 位于文件偏移 0x100；
     * - .cia：首个 NCCH 偏移 = headerSize + certSize + tikSize + tmdSize
     *   （四个 u32 LE 分别在 CIA 头 0x00/0x08/0x0C/0x10）。
     *
     * @return true=加密 / false=已解密 / null=无法判断（文件过小或格式异常）
     */
    fun is3dsRomEncrypted(file: File?): Boolean? {
        if (file == null || !file.exists() || file.length() < 0x300) return null
        return try {
            RandomAccessFile(file, "r").use { raf ->
                val ncchOffset: Long = when (file.extension.lowercase()) {
                    "cia" -> {
                        val head = ByteArray(0x20)
                        raf.readFully(head)
                        fun u32(at: Int): Long =
                            ((head[at].toLong() and 0xFF)) or
                            ((head[at + 1].toLong() and 0xFF) shl 8) or
                            ((head[at + 2].toLong() and 0xFF) shl 16) or
                            ((head[at + 3].toLong() and 0xFF) shl 24)
                        val headerSize = u32(0x00)
                        val certSize = u32(0x08)
                        val tikSize = u32(0x0C)
                        val tmdSize = u32(0x10)
                        headerSize + certSize + tikSize + tmdSize
                    }
                    else -> 0x100L    // .3ds/.cci/.cxi 直接从 0x100 找 NCCH
                }
                raf.seek(ncchOffset + 0x100)
                val magic = ByteArray(4)
                raf.readFully(magic)
                if (String(magic, Charsets.US_ASCII) != "NCCH") return null
                raf.seek(ncchOffset + 0x188 + 3)
                val crypto = raf.read()
                if (crypto < 0) return null
                crypto != 0
            }
        } catch (_: Exception) {
            null
        }
    }

    // ---- Azahar 用户目录（aes_keys.txt 写入目标） --------------------------

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** 用户在设置里指定的 Azahar 用户目录（SAF tree Uri 字符串），可空。 */
    fun getAzaharUserDirUri(context: Context): String? =
        prefs(context).getString(KEY_AZAHAR_USER_DIR, null)

    fun setAzaharUserDirUri(context: Context, uri: String?) {
        prefs(context).edit().putString(KEY_AZAHAR_USER_DIR, uri).apply()
    }

    /** 用户指定的 Ishiruka 数据目录（覆盖自动探测），可空。 */
    fun getIshirukaUserDirPath(context: Context): String? =
        prefs(context).getString(KEY_ISHIRUKA_USER_DIR, null)

    fun setIshirukaUserDirPath(context: Context, path: String?) {
        prefs(context).edit().putString(KEY_ISHIRUKA_USER_DIR, path).apply()
    }

    /**
     * 把 aes_keys.txt 写入 Azahar 用户目录（keys/aes_keys.txt）。
     *
     * Azahar（Citra 系）启动加密游戏时读取 `<用户目录>/keys/aes_keys.txt`。
     * 用户从标准工具导出的 keys 文件内容形如：
     *   generator = ...
     *   main      = <hex>（boot9 common key 等派生密钥）
     * 只需在 3DS 核心设置里导入一次，之后所有加密游戏均可直接启动。
     *
     * @param treeUri 设置页目录选择器返回的 SAF tree Uri 字符串
     *        （= Azahar「选择数据目录」里选的同一个目录）
     * @return null=成功；非 null=错误说明
     */
    fun writeAesKeys(context: Context, treeUri: String, content: String): String? {
        return try {
            val rootUri = Uri.parse(treeUri)
            val keysDirDoc = DocumentsContractHelper.createOrGetDir(context, rootUri, "keys")
                ?: return "无法在所选目录创建 keys 子目录"
            DocumentsContractHelper.writeDocument(
                context, keysDirDoc, "aes_keys.txt", content.toByteArray()
            ) ?: return "无法写入 keys/aes_keys.txt（目录可能只读）"
            null
        } catch (t: Throwable) {
            "写入失败：${t.message}"
        }
    }

    /**
     * CIA 导入（"copy" 模式）：把 .cia 流式复制到 Azahar 用户目录 import/
     * 子目录，供 Azahar 的 CIA 管理/安装界面批量安装（"launch" 模式则
     * 直接引导 CIA，Azahar 引导 CIA 时会自动安装到 NAND，无需复制）。
     * 用流式拷贝 —— CIA 动辄数 GB，不整段进内存。
     */
    fun importCia(context: Context, treeUri: String, source: java.io.InputStream, fileName: String): String? {
        return try {
            val rootUri = Uri.parse(treeUri)
            val importDirDoc = DocumentsContractHelper.createOrGetDir(context, rootUri, "import")
                ?: return "无法在所选目录创建 import 子目录"
            DocumentsContractHelper.writeDocument(context, importDirDoc, fileName, source)
                ?: return "无法写入 $fileName（目录可能只读）"
            null
        } catch (t: Throwable) {
            "CIA 导入失败：${t.message}"
        }
    }

    // =========================================================================
    // Ishiruka 数据目录 / INI 写入（所有设置直读直写，同 Flycast 设置页模式）
    // =========================================================================

    /**
     * 探测 Ishiruka 数据目录。老 Dolphin 默认把用户目录放在
     * `<外部存储>/Android/data/<包名>/files/dolphin-emu`，
     * NesStation 持有 MANAGE_EXTERNAL_STORAGE 时可直接读写。
     * 优先用用户在设置里手动指定的目录，其次探测默认路径。
     */
    fun locateIshirukaUserDir(context: Context): File? {
        getIshirukaUserDirPath(context)?.let { p ->
            val f = File(p)
            if (f.isDirectory) return f
        }
        val base = File(
            Environment.getExternalStorageDirectory(),
            "Android/data/$ISHIRUKA_PACKAGE/files/dolphin-emu"
        )
        if (base.isDirectory) return base
        // 兜底：以自身 external files 目录的兄弟包名探测（同一路径的另一种表述）
        val selfExt = context.getExternalFilesDir(null) ?: return null
        val alt = File(selfExt.parentFile?.parentFile, "$ISHIRUKA_PACKAGE/files/dolphin-emu")
        return if (alt.isDirectory) alt else null
    }

    /**
     * NGC/WII 控制器切换 —— 把 NesStation 的控制器形态设置写入 Ishiruka：
     *   - WiimoteNew.ini [Wiimote1] Extension：
     *       gc/wiimote → None（横握 Wii Remote）
     *       nunchuk    → Nunchuk（双节棍：C/Z + 副摇杆）
     *       classic    → Classic（经典手柄：双摇杆 + 全键）
     *   - 体感开关（motion）：写入 Dolphin.ini [Wiimote]
     *     ContinuousScanning（真机遥控器扫描）；屏幕体感按钮
     *     （Shake/Tilt/IR 指针）始终由核心 overlay 提供。
     *
     * @return null=成功；非 null=错误说明（目录不可达等）
     */
    fun applyNgcwiiControllerMode(
        context: Context,
        controller: String,       // gc | wiimote | nunchuk | classic
        motion: String            // enabled | disabled
    ): String? {
        val dir = locateIshirukaUserDir(context)
            ?: return "未找到 Ishiruka 数据目录（请先启动一次 Ishiruka 让其初始化，" +
               "或在设置里手动指定数据目录）"
        return try {
            val configDir = File(dir, "Config").apply { mkdirs() }
            val wiimoteNew = File(configDir, "WiimoteNew.ini")
            if (!wiimoteNew.exists()) {
                // 首次运行未生成控制器 INI —— 写入与核心 assets 一致的最小集
                wiimoteNew.writeText("[Wiimote1]\nDevice = Android/0/Touchscreen\n")
            }
            val extension = when (controller) {
                "nunchuk" -> "Nunchuk"
                "classic" -> "Classic"
                else -> "None"
            }
            iniSet(wiimoteNew, "Wiimote1", "Extension", extension)
            val dolphinIni = File(configDir, "Dolphin.ini")
            if (!dolphinIni.exists()) dolphinIni.writeText("[Core]\n")
            iniSet(dolphinIni, "Wiimote", "ContinuousScanning",
                if (motion == "enabled") "True" else "False")
            null
        } catch (t: Throwable) {
            "写入 Ishiruka 控制器配置失败：${t.message}"
        }
    }

    /**
     * Ishiruka 通用设置写入（设置页各下拉项经此落盘
     * Dolphin.ini / GFX.ini / WiimoteNew.ini）。
     * @param file "Dolphin" | "GFX" | "WiimoteNew"
     */
    fun writeIshirukaSetting(
        context: Context,
        file: String,
        section: String,
        key: String,
        value: String
    ): String? {
        val dir = locateIshirukaUserDir(context)
            ?: return "未找到 Ishiruka 数据目录（请先启动一次 Ishiruka 让其初始化，" +
               "或在设置里手动指定数据目录）"
        return try {
            val configDir = File(dir, "Config").apply { mkdirs() }
            val ini = File(configDir, "$file.ini")
            if (!ini.exists()) ini.writeText("[$section]\n")
            iniSet(ini, section, key, value)
            null
        } catch (t: Throwable) {
            "写入 ${file}.ini 失败：${t.message}"
        }
    }

    /**
     * 极简 INI section/key 写入器：读原文件 → 更新或追加 key → 写回。
     * 保留原有行序与注释（不重排），只动目标行。这与 Flycast 设置页
     * "直读直写 emu.cfg" 的模式一致。
     */
    fun iniSet(iniFile: File, section: String, key: String, value: String) {
        val lines = if (iniFile.exists()) iniFile.readLines().toMutableList() else mutableListOf()
        val header = "[$section]"
        var sectionStart = lines.indexOfFirst { it.trim().equals(header, ignoreCase = true) }
        if (sectionStart < 0) {
            if (lines.isNotEmpty() && lines.last().isNotBlank()) lines.add("")
            lines.add(header)
            lines.add("$key = $value")
        } else {
            // 找 section 边界
            var sectionEnd = lines.size
            for (i in sectionStart + 1 until lines.size) {
                val t = lines[i].trim()
                if (t.startsWith("[") && t.endsWith("]")) { sectionEnd = i; break }
            }
            var keyLine = -1
            for (i in sectionStart + 1 until sectionEnd) {
                val t = lines[i].trim()
                if (t.startsWith("$key =", ignoreCase = true) ||
                    t.startsWith("$key=", ignoreCase = true)) {
                    keyLine = i; break
                }
            }
            if (keyLine >= 0) lines[keyLine] = "$key = $value"
            else lines.add(sectionEnd, "$key = $value")
        }
        iniFile.writeText(lines.joinToString("\n") + "\n")
    }

    // =========================================================================
    // SAF 辅助（目录选择器结果落地）
    // =========================================================================

    private object DocumentsContractHelper {
        /** 在 tree 目录下创建（或获取）名为 [name] 的子目录 document Uri。 */
        fun createOrGetDir(context: Context, treeUri: Uri, name: String): Uri? {
            val docId = android.provider.DocumentsContract.getTreeDocumentId(treeUri)
            // 先找
            try {
                val children = android.provider.DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, docId)
                context.contentResolver.query(
                    children, arrayOf(
                        android.provider.DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                        android.provider.DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                        android.provider.DocumentsContract.Document.COLUMN_MIME_TYPE
                    ), null, null, null
                )?.use { c ->
                    while (c.moveToNext()) {
                        val n = c.getString(1)
                        val mime = c.getString(2)
                        if (name == n && mime == android.provider.DocumentsContract.Document.MIME_TYPE_DIR) {
                            return android.provider.DocumentsContract.buildDocumentUriUsingTree(treeUri, c.getString(0))
                        }
                    }
                }
            } catch (_: Exception) { }
            // 再建
            return try {
                val parentDoc = android.provider.DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)
                android.provider.DocumentsContract.createDocument(
                    context.contentResolver, parentDoc,
                    android.provider.DocumentsContract.Document.MIME_TYPE_DIR, name
                )
            } catch (_: Exception) {
                null
            }
        }

        /** 在 [dirUri] 下创建/覆盖名为 [name] 的文档并写入 [bytes]。 */
        fun writeDocument(context: Context, dirUri: Uri, name: String, bytes: ByteArray): Uri? {
            return try {
                val doc = android.provider.DocumentsContract.createDocument(
                    context.contentResolver, dirUri, "application/octet-stream", name
                ) ?: return null
                context.contentResolver.openOutputStream(doc, "wt")?.use { it.write(bytes) }
                    ?: return null
                doc
            } catch (_: Exception) {
                null
            }
        }

        /** 在 [dirUri] 下创建/覆盖名为 [name] 的文档并流式写入 [source]。 */
        fun writeDocument(context: Context, dirUri: Uri, name: String, source: java.io.InputStream): Uri? {
            return try {
                val doc = android.provider.DocumentsContract.createDocument(
                    context.contentResolver, dirUri, "application/octet-stream", name
                ) ?: return null
                context.contentResolver.openOutputStream(doc, "wt")?.use { out ->
                    source.use { it.copyTo(out, 1024 * 512) }
                } ?: return null
                doc
            } catch (_: Exception) {
                null
            }
        }
    }
}
