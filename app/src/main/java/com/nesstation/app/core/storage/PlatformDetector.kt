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
        "b1", "b2", "t1", "t2", "u1", "u2", "v10", "v20", "v30", "v40",
        // Generic arcade dumps
        "rom"   // 注意：.bin 单独放在通用 dump 里太宽泛，下方专门处理
    )

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
                else -> GamePlatform.MD
            }
        }

        // === DC 专属镜像扩展（.cdi / .gdi）===
        // 无歧义：DiscJuggler (.cdi) 与原始 GD-ROM (.gdi) 是 Dreamcast 标准
        // dump 格式，其他平台不用。flycast 支持列表里的 .lst/.dat 是 Naomi
        // 旧版 romset（MAME 格式化前），也归 DC。
        if (ext == "cdi" || ext == "gdi" || ext == "lst" || ext == "dat") {
            return GamePlatform.DC
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
                val dcHints = listOf("dc", "dreamcast", "naomi", "atomiswave", "flycast", "gdrom")
                if (dcHints.any { lowerPath.contains(it) }) return GamePlatform.DC
            }
            return when (hintPlatform) {
                GamePlatform.DOS -> GamePlatform.DOS
                GamePlatform.PCE -> GamePlatform.PCE
                GamePlatform.PSX -> GamePlatform.PSX
                GamePlatform.PS2 -> GamePlatform.PS2
                GamePlatform.DC -> GamePlatform.DC
                else -> GamePlatform.MD
            }
        }

        // === DC 专属镜像扩展（.cdi / .gdi / .lst / .dat）===
        if (ext == "cdi" || ext == "gdi" || ext == "lst" || ext == "dat") {
            return GamePlatform.DC
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
     * 优先级：arcade 特征扩展 > 其他平台扩展 > CD-image + hint > arcade 兜底。
     */
    private fun detectFromExtensions(
        entryExts: List<String>,
        hintPlatform: GamePlatform?
    ): GamePlatform {
        // Pass 1: 街机特征扩展名（最高优先级）
        // 注意：.bin 在街机 zip 里非常常见（CPS/NeoGeo 的 ROM 文件），所以也作为 arcade 信号。
        if (entryExts.any { it in ARCADE_ROM_EXTENSIONS || it == "bin" }) {
            return GamePlatform.ARCADE
        }

        // Pass 2: 任意一个被其他平台识别的扩展名
        for (entryExt in entryExts) {
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
                GamePlatform.DC -> GamePlatform.DC
                else -> GamePlatform.ARCADE
            }
        }

        // Pass 4: 没有任何识别信号 —— 当成街机
        return GamePlatform.ARCADE
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
