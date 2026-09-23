package com.nesstation.app.core.storage

import android.content.Context
import android.net.Uri
import com.nesstation.app.core.model.GamePlatform
import java.io.File
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream

/**
 * ROM 平台检测器 —— 本地游戏库与对战平台共用的平台分类逻辑。
 *
 * 之前这段逻辑全部塞在 `ui/library/LibraryScreen.kt` 里（`detectPlatformFromUri` +
 * `detectPlatformFromFile` + `ARCADE_ROM_EXTENSIONS` + `CD_IMAGE_EXTENSIONS`），
 * 是 private 的，所以对战平台（`BattleMatchScreen`）没法复用，只能信服务端配置的
 * `platform` 字符串 —— 一旦服务端写小写或别名（比如 "arcade"），`GamePlatform.fromString`
 * 又是大小写敏感的，就会 fallback 到 NES，导致街机 ROM 默认用 fceumm 启动。
 *
 * 现在提取到 `core/storage/PlatformDetector.kt`，供 `LibraryScreen` 和
 * `BattleMatchScreen` 共用，保证「本地导入」和「对战平台进入」走的是同一套平台
 * 分类逻辑。
 */
object PlatformDetector {

    /**
     * CD-image 扩展名 —— DOSBox (DOS CD) / Genesis-Plus-GX (Mega-CD) /
     * Geargrafx (PCE-CD) 共用。需要靠平台 tab 或上下文（文件夹名 / zip 内其他文件）来消歧。
     */
    val CD_IMAGE_EXTENSIONS = setOf(
        "cue", "img", "iso", "ccd", "sub", "bin", "chd"
    )

    /**
     * MDF/MDS (Alcohol 120%) 与 NRG (Nero) 镜像。
     * PCEE2 (PCSX2) 核心支持 .mdf/.nrg(见 pcee2_libretro.info 的 supported_extensions),
     * MDS 是其子通道伴随文件(核心直接读 .mdf, 无需单独加载 .mds)。
     * 但 MDF/MDS 传统上也是 PS1 抓轨格式, 故需靠平台 tab/hint 消歧:
     * 用户在 PS2 平台页导入 → PS2, 否则默认 PSX(兼容旧库里已有的 PS1 MDF/MDS)。
     */
    val MDF_MDS_NRG_EXTENSIONS = setOf("mdf", "mds", "nrg")

    /**
     * 街机 ROM 在 zip 内常见的扩展名（NeoGeo / CPS1/2/3 / PGM / 通用 dump）。
     * 用于在 zip 里看到这些后缀时判定为 ARCADE（FBNeo）。
     */
    val ARCADE_ROM_EXTENSIONS = setOf(
        // NeoGeo
        "p1", "p2", "sp1", "sp2", "p3", "p4", "s1", "s2", "m1", "m2",
        "v1", "v2", "v3", "v4", "c1", "c2", "c3", "c4", "c5", "c6", "c7", "c8",
        "lo", "sm1", "sfix",
        // CPS1 / CPS2 / CPS3
        "prg", "gfx", "snd", "qsf", "q1", "q2", "q3", "q4", "q5",
        // PGM
        "b1", "b2", "t1", "t2", "u1", "u2", "v10", "v20", "v30", "v40"
        // 注意：旧表里的 "rom" 已移除 —— BIOS 包 / 固件包几乎必含 .rom，
        // 它会把 emulator BIOS 合集 zip 误判成街机；.bin 也太宽泛，不在此表。
    )

    // ===== 自动扫描（存储权限授予后的自动导入）专用严格判定 =====
    //
    // ★ 修复「授权后扫描乱七八糟的 zip/apk，全进了 arcade 和 dos/md 列表」：
    // 旧行为的两个漏洞：
    //  1. 兜底太宽 —— 内容无法识别的 zip 一律当街机（无 hint 时）或跟随
    //     当前平台页（有 hint 时），导致微信/浏览器下载的资源 zip、APK
    //     备份 zip、文档 zip 全部涌入街机/DOS/MD 列表；.7z/.gz 无条件判
    //     街机，连日志压缩包都不放过。
    //  2. 扩展名撞名 —— .md 撞 Markdown 文档、.sms 撞短信备份、.gz 撞
    //     日志压缩包、.bin/.img/.iso 撞固件与软件镜像、.app 撞 APK/应用
    //     数据，这些日常文件全被当成 ROM 入库。
    // 现在自动扫描只认「无歧义」格式：扩展名专属度极高、日常文件几乎不
    // 会撞名，或 zip 探头能验证出明确内容。识别失败一律返回 null（调用
    // 方跳过，不入库），绝不兜底。手动导入（用户主动选文件/文件夹）仍走
    // 宽松判定 —— 用户明确意图优先，不受影响。

    /**
     * 自动扫描信任的扩展名 → 平台白名单。
     * 只收录无歧义、日常文件几乎不会撞名的 ROM 格式。
     *
     * 刻意排除的高撞名扩展（手动导入仍支持，仅自动扫描不收）：
     *   md(Markdown 文档) / sms(短信备份) / gg·sg(过短易撞) / gz(日志压缩包) /
     *   7z(无法用 java.util.zip 探头验证) / bin·img·iso·cue·chd(固件、软件镜像) /
     *   exe·bat·com(Windows 程序) / jar(桌面 Java 程序) / app(APK、应用数据) /
     *   m3u(音乐播放列表) / elf(Linux 可执行) / pbp·ecm·mds·mdf(镜像类) 等。
     */
    val AUTO_TRUSTED_EXTENSIONS: Map<String, GamePlatform> = mapOf(
        // NES / Famicom（.nes/.fds 为专属格式，unf/unif/nez/unh 冷门但专属）
        "nes" to GamePlatform.NES, "fds" to GamePlatform.NES,
        "unf" to GamePlatform.NES, "unif" to GamePlatform.NES,
        "nez" to GamePlatform.NES, "unh" to GamePlatform.NES,
        // SNES / SFC
        "smc" to GamePlatform.SFC, "sfc" to GamePlatform.SFC,
        "swc" to GamePlatform.SFC, "fig" to GamePlatform.SFC,
        // GB / GBC
        "gb" to GamePlatform.GB, "sgb" to GamePlatform.GB, "gbc" to GamePlatform.GB,
        // GBA
        "gba" to GamePlatform.GBA,
        // DOSBox-Pure 专属打包格式
        "dosz" to GamePlatform.DOS,
        // SEGA MD / Genesis（排除 md/sms/gg/sg —— 分别撞 Markdown/短信备份等）
        "smd" to GamePlatform.MD, "gen" to GamePlatform.MD, "68k" to GamePlatform.MD,
        // PC-Engine / SuperGrafx
        "pce" to GamePlatform.PCE, "sgx" to GamePlatform.PCE,
        // Nintendo DS（排除 app —— 撞 APK / macOS 应用 / 应用数据）
        "nds" to GamePlatform.NDS, "srl" to GamePlatform.NDS,
        // SEGA Dreamcast / NAOMI（.gdi/.cdi 为 DC 专属格式，日常文件不撞名）
        "gdi" to GamePlatform.DC, "cdi" to GamePlatform.DC,
        // Nintendo 3DS（.3ds/.cci/.cxi/.cia/.3dsx 为 3DS 专属容器，不撞名；
        // .app 与 NDS 撞名、.elf 与 PS2 撞名 —— 两者仍只走手动导入 + hint 消歧）
        "3ds" to GamePlatform.TG3DS, "cci" to GamePlatform.TG3DS,
        "cxi" to GamePlatform.TG3DS, "cia" to GamePlatform.TG3DS,
        "3dsx" to GamePlatform.TG3DS,
        // GameCube / Wii（.gcm/.rvz/.gcz/.wbfs/.wad/.ciso/.nkit/.tgc/.dol
        // 为 Dolphin 生态专属；.iso 与 DOS/MD/PSX/PS2 共用，仍靠 hint 消歧）
        "gcm" to GamePlatform.NGCWII, "rvz" to GamePlatform.NGCWII,
        "gcz" to GamePlatform.NGCWII, "wbfs" to GamePlatform.NGCWII,
        "wad" to GamePlatform.NGCWII, "ciso" to GamePlatform.NGCWII,
        "nkit" to GamePlatform.NGCWII, "tgc" to GamePlatform.NGCWII,
        "dol" to GamePlatform.NGCWII
    )

    /**
     * 自动扫描严格判定（存储权限授予后的自动导入专用）。
     *
     * 与 [detectFromFile] 的区别：识别不出就返回 **null**（调用方跳过该
     * 文件，不入库），绝不兜底到 ARCADE/NES，也不接受平台页 hint —— 否则
     * 任意 zip 都会被灌进街机或当前平台页的列表。
     *
     * 规则：
     *  1. 裸文件：扩展名必须在 [AUTO_TRUSTED_EXTENSIONS] 白名单内；
     *     `.apk` 显式排除（APK 是安装包不是 ROM，保险丝防止将来误加）。
     *  2. `.zip`：探头验证 ——
     *     a) 含街机特征扩展（p1/sp1/c1…）→ ARCADE；
     *     b) zip 名是已知街机驱动名（kof97.zip / mslug2.zip …，
     *        ArcadeTitleMapper 600+ 映射表）→ ARCADE —— 兜住只含 .bin
     *        的 CPS1 dump 风格街机包；
     *     c) 含白名单平台扩展（nes/sfc/gba/smd… 的合集包）→ 对应平台；
     *     d) 其余（仅 .bin/.apk/未知内容/空包）→ null 跳过。
     *  3. `.7z`/`.gz` 无法用 java.util.zip 探头验证，自动扫描一律跳过
     *     （手动导入仍支持）。
     *
     * @return 判定出的平台；无法可信识别时返回 null（调用方应跳过该文件）
     */
    fun detectFromFileStrict(file: File): GamePlatform? {
        val ext = file.extension.lowercase()
        if (ext == "apk") return null        // APK 是安装包不是 ROM，显式排除
        if (ext == "zip") return detectZipStrict(file)
        return AUTO_TRUSTED_EXTENSIONS[ext]
    }

    /**
     * zip 探头严格判定：只有能验证出明确内容的 zip 才返回平台，
     * 否则返回 null（跳过）。这是修复「任意 zip 全进街机」的核心。
     */
    private fun detectZipStrict(file: File): GamePlatform? {
        val entryExts = listZipEntryExtensions(file)
        if (entryExts.isEmpty()) return null   // 空包 / 损坏包
        // a) 街机特征扩展 —— 真正的 FBNeo ROM zip 几乎必含 p1/sp1/c1…
        if (entryExts.any { it in ARCADE_ROM_EXTENSIONS }) return GamePlatform.ARCADE
        // b) zip 名是已知街机驱动名 —— 强信号
        if (ArcadeTitleMapper.lookupByFileName(file.name) != null) return GamePlatform.ARCADE
        // c) 含可信平台扩展 —— NES/SFC/GBA 等合集包
        entryExts.firstNotNullOfOrNull { AUTO_TRUSTED_EXTENSIONS[it] }?.let { return it }
        // d) 无法验证 —— 不再兜底街机 / MD / DOS，直接跳过
        return null
    }

    /**
     * zip 内容探头（参考全能模拟器「先识别内容、再分类核心」的思路）：
     * 只有能验证出明确内容的 zip 才返回对应平台，验证不出返回 null。
     *
     * 探测顺序：
     *  1. 街机特征扩展（p1/sp1/c1…）→ ARCADE —— 真 FBNeo ROM zip 几乎必含；
     *  2. zip 名是已知街机驱动名（kof97.zip / mslug2.zip …，600+ 映射表）→ ARCADE；
     *  3. 含白名单平台扩展（nes/sfc/gba/smd… 的合集包）→ 对应平台；
     *  4. 仅含 CD 镜像扩展（cue/bin/iso…）→ 跟随 hint（无 hint 默认 MD）；
     *  5. 其余（仅 .bin/.apk/未知内容/空包）→ null —— 资源 zip、APK 备份 zip、
     *     文档 zip 全部在此被拒之门外。
     *
     * 供「刷新重扫收紧判定」与 [RomStore.sanitizeLibrary] 存量垃圾清理共用。
     */
    fun isZipContentRecognizable(file: File, hintPlatform: GamePlatform? = null): GamePlatform? {
        val entryExts = listZipEntryExtensions(file)
        return recognizeZipEntries(entryExts, file.name, hintPlatform)
    }

    /** [isZipContentRecognizable] 的 SAF Uri 版本，供 SAF 文件夹重扫使用。 */
    fun isZipContentRecognizable(
        context: Context,
        uri: Uri,
        fileName: String,
        hintPlatform: GamePlatform? = null
    ): GamePlatform? {
        val entryExts = listZipEntryExtensions(context, uri)
        return recognizeZipEntries(entryExts, fileName, hintPlatform)
    }

    /** zip 探头的公共实现（条目扩展名集合 + zip 文件名 + hint → 平台或 null）。 */
    private fun recognizeZipEntries(
        entryExts: List<String>,
        zipFileName: String,
        hintPlatform: GamePlatform?
    ): GamePlatform? {
        if (entryExts.isEmpty()) return null                                  // 空包 / 损坏包
        // 1. 街机特征扩展
        if (entryExts.any { it in ARCADE_ROM_EXTENSIONS }) return GamePlatform.ARCADE
        // 2. zip 名是已知街机驱动名
        if (ArcadeTitleMapper.lookupByFileName(zipFileName) != null) return GamePlatform.ARCADE
        // 3. 白名单平台扩展（NES/SFC/GBA 等合集包）
        entryExts.firstNotNullOfOrNull { AUTO_TRUSTED_EXTENSIONS[it] }?.let { return it }
        // 4. 仅 CD 镜像扩展 —— 跟随 hint 消歧
        if (entryExts.any { it in CD_IMAGE_EXTENSIONS }) {
            return when (hintPlatform) {
                GamePlatform.MD, GamePlatform.PCE, GamePlatform.DOS,
                GamePlatform.PSX, GamePlatform.PS2, GamePlatform.DC -> hintPlatform
                // 外部核心：仅在 3DS / NGC-WII 平台页重扫时跟随提示
                GamePlatform.TG3DS, GamePlatform.NGCWII -> hintPlatform
                else -> GamePlatform.MD
            }
        }
        // 5. 无法验证 —— 跳过
        return null
    }

    /** 刷新重扫时允许跟随 hint 消歧的平台集合（CD 镜像类）。 */
    private val REFRESH_HINT_ALLOWED = setOf(
        GamePlatform.MD, GamePlatform.PCE, GamePlatform.DOS,
        GamePlatform.PSX, GamePlatform.PS2, GamePlatform.DC,
        // 外部核心：.iso/.cue/.chd 在 3DS / NGC-WII 页导入时归对应平台
        GamePlatform.TG3DS, GamePlatform.NGCWII
    )

    /**
     * 刷新 / 重扫文件夹专用收紧判定。
     *
     * 与手动导入的宽松判定 [detectFromFile] 的区别：手动导入是用户明确意图，
     * 识别不出可以兜底；刷新重扫是【批量扫文件夹】，文件夹里什么都可能有 ——
     * 微信资源 zip、APK 安装包、文档压缩包。若沿用宽松判定，每次刷新都会
     * 把这些垃圾重新灌进游戏库（旧行为：未知 zip 兑 ARCADE/跟随 hint，
     * .gz 恒街机，未知扩展→NES）。
     *
     * 规则：
     *  - .apk 显式排除（安装包不是 ROM）；
     *  - .zip 探头验证内容（[isZipContentRecognizable]），验证不出 → null 跳过；
     *  - .7z/.gz 无法探头 → 仅街机页（hint=ARCADE）重扫时收街机，其余 null；
     *  - 无歧义 ROM 扩展（nes/sfc/gba…）→ 对应平台；
     *  - CD 镜像扩展 → 跟随平台页 hint（无 hint 不收）；
     *  - 其余未知扩展 → null，绝不兜底。
     *
     * @return 平台；不可信识别时返回 null（调用方应跳过该文件）
     */
    fun detectForRefresh(file: File, hintPlatform: GamePlatform? = null): GamePlatform? {
        val ext = file.extension.lowercase()
        if (ext == "apk") return null
        if (ext == "zip") return isZipContentRecognizable(file, hintPlatform)
        if (ext == "7z" || ext == "gz") {
            return if (hintPlatform == GamePlatform.ARCADE) GamePlatform.ARCADE else null
        }
        AUTO_TRUSTED_EXTENSIONS[ext]?.let { return it }
        if (ext in CD_IMAGE_EXTENSIONS || ext in MDF_MDS_NRG_EXTENSIONS) {
            return hintPlatform?.takeIf { it in REFRESH_HINT_ALLOWED }
        }
        return null
    }

    /** [detectForRefresh] 的 SAF Uri 版本（refreshList 的 content:// 路径用）。 */
    fun detectForRefreshUri(
        context: Context,
        uri: Uri,
        fileName: String,
        hintPlatform: GamePlatform? = null
    ): GamePlatform? {
        val ext = fileName.substringAfterLast('.', "").lowercase()
        if (ext == "apk") return null
        if (ext == "zip") return isZipContentRecognizable(context, uri, fileName, hintPlatform)
        if (ext == "7z" || ext == "gz") {
            return if (hintPlatform == GamePlatform.ARCADE) GamePlatform.ARCADE else null
        }
        AUTO_TRUSTED_EXTENSIONS[ext]?.let { return it }
        if (ext in CD_IMAGE_EXTENSIONS || ext in MDF_MDS_NRG_EXTENSIONS) {
            return hintPlatform?.takeIf { it in REFRESH_HINT_ALLOWED }
        }
        return null
    }

    /**
     * 从 SAF Uri 检测 ROM 平台（用于本地游戏库导入流程）。
     *
     * @param hintPlatform 当用户在某个平台 tab 下导入时，CD-image 扩展名
     *   (.cue/.img/.iso) 会按这个 hint 解析。
     */
    fun detectFromUri(
        context: Context,
        uri: Uri,
        fileName: String,
        hintPlatform: GamePlatform? = null
    ): GamePlatform {
        val ext = fileName.substringAfterLast('.', "").lowercase()

        // === CD-image 扩展名歧义 ===
        if (ext in CD_IMAGE_EXTENSIONS) {
            return when (hintPlatform) {
                GamePlatform.DOS -> GamePlatform.DOS
                GamePlatform.PCE -> GamePlatform.PCE
                GamePlatform.PSX -> GamePlatform.PSX
                GamePlatform.PS2 -> GamePlatform.PS2
                GamePlatform.DC -> GamePlatform.DC
                // 外部核心：.iso/.cue/.chd 在 3DS / NGC-WII 页导入 → 对应平台
                GamePlatform.TG3DS -> GamePlatform.TG3DS
                GamePlatform.NGCWII -> GamePlatform.NGCWII
                else -> GamePlatform.MD
            }
        }

        // === MDF/MDS/NRG 镜像：靠 hint 消歧，PS2 页导入 → PS2，否则默认 PSX ===
        if (ext in MDF_MDS_NRG_EXTENSIONS) {
            return when (hintPlatform) {
                GamePlatform.PS2 -> GamePlatform.PS2
                else -> GamePlatform.PSX
            }
        }

        // 直接的 ROM 后缀（.nes / .smc / .gba / .md / .pce / .7z / .bat ...）
        GamePlatform.fromExtension(ext)?.let { return it }

        // .zip：解压看里面
        if (ext == "zip") {
            val entryExts = listZipEntryExtensions(context, uri)
            return detectFromExtensions(entryExts, hintPlatform)
        }

        // .7z 永远是街机
        if (ext == "7z") return GamePlatform.ARCADE

        // .gz 当成街机
        if (ext == "gz") return GamePlatform.ARCADE

        // 裸 .bin（不在 zip 里）：按 SEGA Mega Drive 处理
        if (ext == "bin") return GamePlatform.MD

        return GamePlatform.NES
    }

    /**
     * 从本地 [File] 检测 ROM 平台（用于对战平台 ROM 已经下到本地后的检测）。
     *
     * @param hintPlatform 服务端 / 用户配置的平台 hint，CD-image 扩展名按它解析。
     * @param pathHint 文件夹路径提示，用于 CD-image 消歧（含 "pce" / "dos" / "mega" 等
     *   关键字时优先返回对应平台）。
     */
    fun detectFromFile(
        file: File,
        hintPlatform: GamePlatform? = null,
        pathHint: String? = null
    ): GamePlatform {
        val ext = file.extension.lowercase()

        // CD-image 扩展名歧义消解：先看路径关键字，再看 hint，最后默认 MD
        if (ext in CD_IMAGE_EXTENSIONS) {
            val path = pathHint ?: file.parent
            if (path != null) {
                val lowerPath = path.lowercase()
                val pceHints = listOf("pce", "pcengine", "pc-engine", "turbografx", "tg16", "pc_engine")
                if (pceHints.any { lowerPath.contains(it) }) return GamePlatform.PCE
                val dosHints = listOf("dos", "dosbox", "pcgame", "pc_game")
                if (dosHints.any { lowerPath.contains(it) }) return GamePlatform.DOS
                val mdHints = listOf("mega", "sega", "genesis", "megacd", "mega-cd", "md")
                if (mdHints.any { lowerPath.contains(it) }) return GamePlatform.MD
                val psxHints = listOf("psx", "ps1", "playstation", "sony")
                if (psxHints.any { lowerPath.contains(it) }) return GamePlatform.PSX
                val ps2Hints = listOf("ps2", "playstation2", "psx2")
                if (ps2Hints.any { lowerPath.contains(it) }) return GamePlatform.PS2
                // DC 专属关键字放后面（"dc" 过短，避免误伤其它路径）
                val dcHints = listOf("dreamcast", "naomi", "atomiswave", "flycast", "/dc/")
                if (dcHints.any { lowerPath.contains(it) }) return GamePlatform.DC
                // 外部核心：3DS / NGC-WII 目录关键字
                val tg3dsHints = listOf("3ds", "citra", "azahar", "nintendo3ds")
                if (tg3dsHints.any { lowerPath.contains(it) }) return GamePlatform.TG3DS
                val ngcwiiHints = listOf("ngc", "wii", "gamecube", "dolphin", "ishiiruka")
                if (ngcwiiHints.any { lowerPath.contains(it) }) return GamePlatform.NGCWII
            }
            return when (hintPlatform) {
                GamePlatform.DOS -> GamePlatform.DOS
                GamePlatform.PCE -> GamePlatform.PCE
                GamePlatform.PSX -> GamePlatform.PSX
                GamePlatform.PS2 -> GamePlatform.PS2
                GamePlatform.DC -> GamePlatform.DC
                // 外部核心：.iso/.cue/.chd 在 3DS / NGC-WII 页导入 → 对应平台
                GamePlatform.TG3DS -> GamePlatform.TG3DS
                GamePlatform.NGCWII -> GamePlatform.NGCWII
                else -> GamePlatform.MD
            }
        }

        // === MDF/MDS/NRG 镜像：先看路径关键字(ps2)，再看 hint，最后默认 PSX ===
        if (ext in MDF_MDS_NRG_EXTENSIONS) {
            val path = pathHint ?: file.parent
            if (path != null) {
                val lowerPath = path.lowercase()
                if (lowerPath.contains("ps2") || lowerPath.contains("playstation2") ||
                    lowerPath.contains("psx2")) return GamePlatform.PS2
                if (lowerPath.contains("psx") || lowerPath.contains("ps1") ||
                    lowerPath.contains("playstation")) return GamePlatform.PSX
            }
            return when (hintPlatform) {
                GamePlatform.PS2 -> GamePlatform.PS2
                else -> GamePlatform.PSX
            }
        }

        GamePlatform.fromExtension(ext)?.let { return it }

        if (ext == "zip") {
            val entryExts = listZipEntryExtensions(file)
            return detectFromExtensions(entryExts, hintPlatform)
        }

        if (ext == "7z") return GamePlatform.ARCADE
        if (ext == "gz") return GamePlatform.ARCADE
        if (ext == "bin") return GamePlatform.MD

        return GamePlatform.NES
    }

    /**
     * 根据 zip 内条目的扩展名集合判定平台（双方共用）。
     *
     * ★ 优先级（修复"首次添加某项核心游戏目录时，其他存储的杂 zip 被刷进
     * 街机目录"）：
     *  1. 街机特征扩展（p1/sp1/c1/q1…）→ ARCADE：真正的 FBNeo ROM zip 几乎
     *     必含特征扩展，任何提示下都判街机；
     *  2. 弱街机信号（zip 内含 .bin）→ 仅在【无平台页提示】时判街机。带提示
     *     时 .bin 太宽泛（CPS dump 与杂项数据包都可能只有 .bin），跟随用户的
     *     平台页选择，避免把资源 zip / APK 备份 zip 等误归街机；
     *  3. 其他平台扩展 → 按扩展判定；
     *  4. 仅 CD-image 扩展 → 按 hint 消歧（无 hint 默认 MD）；
     *  5. 无任何识别信号 → 有平台页提示时【跟随提示】（用户在哪个核心页
     *     导入就归哪个核心），无提示保持旧默认 ARCADE。
     */
    private fun detectFromExtensions(
        entryExts: List<String>,
        hintPlatform: GamePlatform?
    ): GamePlatform {
        // Pass 1: 街机特征扩展名（最高优先级）
        if (entryExts.any { it in ARCADE_ROM_EXTENSIONS }) {
            return GamePlatform.ARCADE
        }

        // Pass 1b: .bin 弱信号 —— 仅无提示时视为街机（旧行为）。
        // 带提示时落到下方 Pass 2/4/5 按扩展或提示判定。
        if (entryExts.any { it == "bin" } && hintPlatform == null) {
            return GamePlatform.ARCADE
        }

        // Pass 2: 任意一个被其他平台识别的扩展名。
        // CD-image 扩展（.cue/.iso/.img/.ccd/.sub/.chd/.bin）跳过 —— 统一
        // 交给 Pass 3 按平台页提示消歧（fromExtension 把 .cue/.iso 映射到
        // DOS，直接判会把 Mega-CD/PCE-CD 的 zip 全带偏）。
        for (entryExt in entryExts) {
            if (entryExt in CD_IMAGE_EXTENSIONS) continue
            GamePlatform.fromExtension(entryExt)?.let { return it }
        }

        // Pass 3: zip 里只看到 CD-image 扩展名 —— 按 hint 解析
        if (entryExts.any { it in CD_IMAGE_EXTENSIONS }) {
            return when (hintPlatform) {
                GamePlatform.MD -> GamePlatform.MD
                GamePlatform.PCE -> GamePlatform.PCE
                GamePlatform.DOS -> GamePlatform.DOS
                GamePlatform.PSX -> GamePlatform.PSX
                GamePlatform.PS2 -> GamePlatform.PS2
                GamePlatform.NES -> GamePlatform.NES
                GamePlatform.ARCADE -> GamePlatform.ARCADE
                GamePlatform.DC -> GamePlatform.DC
                // 外部核心：zip 内含 .iso/.cue 的 3DS/NGC-WII 合集包
                GamePlatform.TG3DS -> GamePlatform.TG3DS
                GamePlatform.NGCWII -> GamePlatform.NGCWII
                else -> GamePlatform.MD
            }
        }

        // Pass 4: 没有任何识别信号 —— 跟随平台页提示；无提示兜底街机
        // （保持旧行为：自动扫描等无上下文场景把未知 zip 归街机）。
        return hintPlatform ?: GamePlatform.ARCADE
    }

    private fun listZipEntryExtensions(context: Context, uri: Uri): List<String> {
        val result = mutableListOf<String>()
        try {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                ZipInputStream(stream).use { zis ->
                    var entry = zis.nextEntry
                    while (entry != null) {
                        if (!entry.isDirectory) {
                            val entryExt = entry.name.substringAfterLast('.', "").lowercase()
                            if (entryExt.isNotEmpty()) result.add(entryExt)
                        }
                        zis.closeEntry()
                        entry = zis.nextEntry
                    }
                }
            }
        } catch (_: Exception) { }
        return result
    }

    private fun listZipEntryExtensions(file: File): List<String> {
        val result = mutableListOf<String>()
        try {
            ZipFile(file).use { zip ->
                val entries = zip.entries()
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    if (!entry.isDirectory) {
                        val entryExt = entry.name.substringAfterLast('.', "").lowercase()
                        if (entryExt.isNotEmpty()) result.add(entryExt)
                    }
                }
            }
        } catch (_: Exception) { }
        return result
    }

    /**
     * 综合平台判定：先信服务端 / 用户配置的 [declared] 字符串（用 `GamePlatform.fromString`
     * 大小写不敏感 + 别名匹配），失败时（即返回 NES fallback）再用 [romFile] 兜底检测。
     *
     * 这是给对战平台用的：避免服务端配置错误 / 写小写 / 写别名时选错核心。
     *
     * @param declared 服务端 `Game.platform` 字符串（如 "arcade" / "ARCADE" / "fbneo"）。
     * @param romFile 已下载到本地的 ROM 文件，用于文件名 / zip 内容兜底检测。
     * @return 最终选择的平台。如果服务端字段无效（= NES 兜底）且 ROM 文件能识别出
     *   非 NES 平台，则用 ROM 文件的检测结果覆盖。
     */
    fun resolve(declared: String?, romFile: File?): GamePlatform {
        // 1. 先看服务端 / 用户配置的 platform 字符串
        val declaredPlatform = GamePlatform.fromString(declared)
        // 如果服务端配置成功匹配（且不是 NES 兜底，或者字符串确实就是 nes 系列），
        // 直接信服务端。
        // 注意：fromString 在完全无法识别时会返回 NES。我们用"原始字符串不空且
        // 解析结果是 NES 但原始字符串不像 nes"来判定是否真的 fallback 了。
        val isLikelyFallback = declared.isNullOrBlank() ||
            (declaredPlatform == GamePlatform.NES && !looksLikeNes(declared))
        if (!isLikelyFallback) return declaredPlatform

        // 2. 服务端字段无效 / fallback：用 ROM 文件兜底
        if (romFile != null && romFile.exists()) {
            val detected = detectFromFile(romFile, hintPlatform = declaredPlatform)
            // 如果文件检测也返回 NES（比如 .nes 文件），那 NES 就是对的；
            // 如果检测到别的平台（比如街机 zip），覆盖之。
            if (detected != GamePlatform.NES) return detected
        }

        // 3. 最后兜底：NES（保持旧行为）
        return declaredPlatform
    }

    /** 判断 [value] 是否是 NES 平台的合法标识（用于区分"匹配 NES" vs "fallback 到 NES"）。 */
    private fun looksLikeNes(value: String): Boolean {
        val v = value.trim().lowercase()
            .replace("-", "").replace("_", "").replace("/", "").replace(".", "")
        return v in setOf("nes", "fc", "famicom", "fceumm", "fceux", "nestopia")
    }
}
