package com.nesstation.app.core.storage

import android.content.Context
import android.net.Uri
import android.os.Environment
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Scans common ROM directories and any user-granted SAF tree for ROM files.
 * Supports NES/FDS, SNES/SFC, GB/GBC/GBA, DOSBox (.bat/.exe/.com/.dosz), and
 * J2ME JAR files.
 *
 * For DOS games, the scanner imports *only* the executable launcher file
 * (e.g. play.bat, run.bat, START.BAT) — not the data files in the same folder.
 * This matches the user's request: "导入文件夹时只导入执行文件比如play.bat".
 *
 * Chinese folder names are handled transparently — Java Strings are UTF-16
 * internally and Android's filesystem is UTF-8, so non-ASCII paths work without
 * any special encoding logic. The libretro core's `retro_load_game(path)`
 * receives a UTF-8 string and passes it through to dosbox_pure's VFS, which
 * uses standard POSIX file APIs (also UTF-8 on Android).
 */
class RomScanner(private val context: Context) {

    private fun isRomFile(name: String): Boolean =
        name.endsWith(".nes", ignoreCase = true) ||
        name.endsWith(".fds", ignoreCase = true) ||
        name.endsWith(".unf", ignoreCase = true) ||
        name.endsWith(".unif", ignoreCase = true) ||
        name.endsWith(".zip", ignoreCase = true) ||
        name.endsWith(".7z", ignoreCase = true) ||
        // SNES / SFC
        name.endsWith(".smc", ignoreCase = true) ||
        name.endsWith(".sfc", ignoreCase = true) ||
        name.endsWith(".swc", ignoreCase = true) ||
        name.endsWith(".fig", ignoreCase = true) ||
        // GB / GBC / GBA
        name.endsWith(".gb", ignoreCase = true) ||
        name.endsWith(".sgb", ignoreCase = true) ||
        name.endsWith(".gbc", ignoreCase = true) ||
        name.endsWith(".gba", ignoreCase = true) ||
        // DOSBox — bundled formats (NOT bare .bat/.exe/.com, which are
        // only imported via the dedicated folder-import flow to avoid
        // picking up unrelated system binaries).
        name.endsWith(".dosz", ignoreCase = true) ||
        name.endsWith(".iso", ignoreCase = true) ||
        // SEGA Mega Drive / Genesis / Master System / Game Gear / SG-1000
        // (Genesis-Plus-GX core). .bin is ambiguous (also DOS), so we don't
        // pick it up here — users import SEGA .bin files manually.
        name.endsWith(".md", ignoreCase = true) ||
        name.endsWith(".smd", ignoreCase = true) ||
        name.endsWith(".gen", ignoreCase = true) ||
        name.endsWith(".sms", ignoreCase = true) ||
        name.endsWith(".gg", ignoreCase = true) ||
        name.endsWith(".sg", ignoreCase = true) ||
        name.endsWith(".68k", ignoreCase = true) ||
        // Mega-CD / SEGA-CD disc images
        name.endsWith(".cue", ignoreCase = true) ||
        name.endsWith(".chd", ignoreCase = true) ||
        // Dreamcast / GD-ROM disc images (Flycast core) — .gdi (raw GD-ROM
        // index) and .cdi (DiscJuggler) are DC-only formats. Track files
        // referenced by a .gdi (track01.iso / track02.raw / .bin) are NOT
        // imported as separate games — see dedupeDiscTracks() below.
        name.endsWith(".gdi", ignoreCase = true) ||
        name.endsWith(".cdi", ignoreCase = true) ||
        // Geargrafx — PC-Engine / TurboGrafx-16 / SuperGrafx / PCE-CD
        // (PCE-CD uses .cue/.chd which are already covered above; the
        // disambiguation between MD-CD / DOS-CD / PCE-CD happens in
        // detectPlatformFromUri based on the user's platform tab.)
        name.endsWith(".pce", ignoreCase = true) ||
        name.endsWith(".sgx", ignoreCase = true) ||
        name.endsWith(".hes", ignoreCase = true) ||
        // Nintendo DS (melonDS)
        name.endsWith(".nds", ignoreCase = true) ||
        name.endsWith(".app", ignoreCase = true) ||
        name.endsWith(".ids", ignoreCase = true) ||
        name.endsWith(".srl", ignoreCase = true) ||
        name.endsWith(".dsi", ignoreCase = true) ||
        // PlayStation 1 (PCSX-ReARMed)
        name.endsWith(".pbp", ignoreCase = true) ||
        name.endsWith(".m3u", ignoreCase = true) ||
        name.endsWith(".ecm", ignoreCase = true) ||
        name.endsWith(".mdf", ignoreCase = true) ||
        name.endsWith(".mds", ignoreCase = true) ||
        name.endsWith(".ccd", ignoreCase = true)

    /**
     * Whether a file name looks like a DOS launcher (.bat / .exe / .com).
     * Used by the folder-import flow to pick the launch candidate.
     */
    private fun isDosLauncher(name: String): Boolean {
        val ext = name.substringAfterLast('.', missingDelimiterValue = "").lowercase()
        return ext in setOf("bat", "exe", "com")
    }

    suspend fun scanDefaults(): List<File> = withContext(Dispatchers.IO) {
        val candidates = buildList {
            // /sdcard/ROMs, /sdcard/NesStation, /sdcard/Download/NesStation
            val sd = Environment.getExternalStorageDirectory()
            add(File(sd, "ROMs"))
            add(File(sd, "NesStation"))
            add(File(sd, "Download/NesStation"))
            // app-private dir, useful for sideloading
            add(context.getExternalFilesDir("roms") ?: File(context.filesDir, "roms"))
        }
        candidates.filter { it.exists() && it.isDirectory }
            .flatMap { it.walkTopDown().filter(File::isFile).toList() }
            .filter { isRomFile(it.name) }
            .let { dedupeDiscTracks(it, File::getParentFile, File::getName) }
    }

    suspend fun scanSafTree(treeUri: Uri): List<File> = withContext(Dispatchers.IO) {
        val doc = DocumentFile.fromTreeUri(context, treeUri) ?: return@withContext emptyList()
        val out = mutableListOf<File>()
        doc.traverse { name ->
            if (isRomFile(name)) out.add(File(name))
        }
        // name is the full SAF document path ("primary:Games/dc/disc.gdi"),
        // so we can group by the virtual parent path the same way as the
        // local-file scan.
        dedupeDiscTracks(out, File::getParentFile, File::getName)
    }

    companion object {
        /**
         * Disc-image track 去重（参考 Flycast game_scanner.cpp 的行为：
         * 只有 .gdi/.cdi/.chd/.cue/.zip/.7z/.m3u 才是游戏入口，散装音轨
         * 文件永不单独列出）。
         *
         * 一个 GDI dump 目录的典型内容：
         *   disc.gdi + track01.iso + track02.raw + track03.iso ...
         * .gdi 是文本索引文件，用相对路径引用各音轨；目录里散装的
         * .iso/.raw/.bin 只是音轨，不是独立游戏。此前扫描器不认识
         * .gdi，反而把每个 track .iso 都当成独立游戏列进了游戏库。
         *
         * 规则：同一目录内存在 .gdi 或 .cdi（两者都是 DC 专属完整镜像）
         * 时，该目录下的 .iso/.raw/.bin/.img/.dat 音轨不再单独导入。
         * .gdi/.cdi/.chd/.cue 本身永远保留。
         *
         * @param files   候选 ROM 列表
         * @param parent  取父目录（分组键）的函数
         * @param name    取文件名的函数
         */
        fun <T> dedupeDiscTracks(
            files: List<T>,
            parent: (T) -> Any?,
            name: (T) -> String
        ): List<T> {
            // 按"父目录 → 是否有 DC 完整镜像"分组检测。
            val dirHasDisc = HashMap<Any?, Boolean>()
            for (f in files) {
                val n = name(f)
                val ext = n.substringAfterLast('.', "").lowercase()
                if (ext == "gdi" || ext == "cdi") {
                    val p = parent(f) ?: continue
                    dirHasDisc[p] = true
                }
            }
            if (dirHasDisc.isEmpty()) return files
            return files.filter { f ->
                val n = name(f)
                val ext = n.substringAfterLast('.', "").lowercase()
                if (dirHasDisc[parent(f)] != true) return@filter true
                // DC 完整镜像目录：散装音轨不再作为独立游戏
                ext !in setOf("iso", "raw", "bin", "img", "dat")
            }
        }
    }

    /**
     * Scan a local folder for a DOS launcher file (.bat / .exe / .com).
     * Returns the best launch candidate by priority:
     *   play.bat > run.bat > START.BAT > autoexec.bat > go.bat > launch.bat >
     *   main.bat > play.exe > run.exe > ... > setup.exe > any .exe > any .com
     *
     * Returns null if no launcher is found.
     *
     * Folder names with Chinese characters work transparently — Java's String
     * and File classes are Unicode-native and Android's filesystem is UTF-8.
     */
    suspend fun findDosLauncher(folder: File): File? = withContext(Dispatchers.IO) {
        if (!folder.exists() || !folder.isDirectory) return@withContext null

        // Step 1: collect all candidate launchers (case-insensitive).
        val candidates = folder.walkTopDown()
            .filter { it.isFile && isDosLauncher(it.name) }
            .toList()

        if (candidates.isEmpty()) return@withContext null

        // Step 2: match by priority list (case-insensitive).
        val priority = listOf(
            "play.bat", "run.bat", "start.bat", "autoexec.bat",
            "go.bat", "launch.bat", "main.bat",
            "play.exe", "run.exe", "start.exe", "setup.exe",
            "game.exe", "main.exe", "launch.exe"
        )
        for (preferred in priority) {
            val match = candidates.firstOrNull { it.name.equals(preferred, ignoreCase = true) }
            if (match != null) return@withContext match
        }

        // Step 3: fallback — prefer .bat (most likely to set up sound + cdrom),
        // then .exe, then .com.
        candidates.firstOrNull { it.name.endsWith(".bat", ignoreCase = true) }
            ?: candidates.firstOrNull { it.name.endsWith(".exe", ignoreCase = true) }
            ?: candidates.firstOrNull { it.name.endsWith(".com", ignoreCase = true) }
    }

    /**
     * Scan a SAF tree folder for a DOS launcher.
     * Returns the file's URI (as a String) of the best launch candidate.
     *
     * Works with content:// URIs that contain UTF-8 percent-encoded Chinese
     * characters — DocumentFile.fromTreeUri handles the encoding transparently.
     */
    suspend fun findDosLauncherSaf(treeUri: Uri): Uri? = withContext(Dispatchers.IO) {
        val doc = DocumentFile.fromTreeUri(context, treeUri) ?: return@withContext null
        if (!doc.isDirectory) return@withContext null

        data class Candidate(val uri: Uri, val name: String, val isBat: Boolean, val isExe: Boolean)

        val candidates = mutableListOf<Candidate>()
        fun walk(d: DocumentFile) {
            d.listFiles().forEach { c ->
                if (c.isDirectory) walk(c)
                else if (c.isFile) {
                    val n = c.name ?: ""
                    val ext = n.substringAfterLast('.', missingDelimiterValue = "").lowercase()
                    if (ext in setOf("bat", "exe", "com")) {
                        candidates.add(Candidate(c.uri, n.lowercase(),
                            isBat = ext == "bat", isExe = ext == "exe"))
                    }
                }
            }
        }
        walk(doc)

        if (candidates.isEmpty()) return@withContext null

        val priority = listOf(
            "play.bat", "run.bat", "start.bat", "autoexec.bat",
            "go.bat", "launch.bat", "main.bat",
            "play.exe", "run.exe", "start.exe", "setup.exe",
            "game.exe", "main.exe", "launch.exe"
        )
        for (preferred in priority) {
            val match = candidates.firstOrNull { it.name == preferred }
            if (match != null) return@withContext match.uri
        }

        candidates.firstOrNull { it.isBat }?.uri
            ?: candidates.firstOrNull { it.isExe }?.uri
            ?: candidates.firstOrNull()?.uri
    }

    private fun DocumentFile.traverse(visit: (String) -> Unit) {
        val children = listFiles()
        children.forEach { c ->
            if (c.isDirectory) c.traverse(visit)
            else if (c.isFile) c.uri.lastPathSegment?.let(visit)
        }
    }
}
