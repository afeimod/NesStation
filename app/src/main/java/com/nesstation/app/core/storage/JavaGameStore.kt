package com.nesstation.app.core.storage

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import com.nesstation.app.core.model.GameEntry
import com.nesstation.app.core.model.GamePlatform
import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import ru.woesss.j2me.jar.Descriptor
import ru.woesss.util.zip.ZipFile
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Manages J2ME/Java ME game installation and listing.
 * Integrates with J2ME-Loader's Config and AppInstaller.
 *
 * ★★ v4.0 安装链路对齐 JL-Mod（woesss/JL-Mod AppInstaller）：
 *   1. 不再用 java.util.jar.JarFile 读 manifest —— 它会做签名/结构校验，
 *      对老游戏 jar（旧打包器产物、双签名/改包、非 UTF-8 文件名、无 manifest）
 *      大量抛 SecurityException/ZipException/NPE = 用户反馈的「无法安装」主因。
 *      改用 zip4j（ru.woesss.util.zip.ZipFile，与 JL-Mod 同款大小写不敏感查表）。
 *   2. manifest 解析改用 vendored ru.woesss.j2me.jar.Descriptor（支持折行/
 *      BOM/非 ASCII 属性），安装产物 converted.dex.conf 由 Descriptor.writeTo
 *      写出，不再手工拼字符串丢属性。
 *   3. 新增 .jad 支持：JAD 与同名/同批 JAR 一起选中即可（JAD 属性 merge 进
 *      manifest —— 索爱等机型专有 JAD 属性正是 SE 游戏识别机型所必需）；
 *      MIDlet-Jar-URL 为绝对 http(s) 时自动下载。
 *   4. 安装落盘改为 .tmp 目录 + rename（JL-Mod 同款），不再中途失败留半成品。
 *   5. dexlib 已同步 JL-Mod（BinaryPatcher + patches.bin 逐 CRC 补丁 +
 *      大小写不敏感路径处理），部分已知损坏/特殊游戏类在转 DEX 时被自动修复。
 */
object JavaGameStore {
    private const val TAG = "JavaGameStore"

    /** 最近一次安装失败的详细原因（UI 展示用；成功后清空） */
    @Volatile
    var lastInstallError: String? = null
        private set

    /** 最近一次成功安装的游戏目录（兼容旧调用方取路径） */
    @Volatile
    var lastInstallPath: String? = null
        private set

    private val JAVA_ACCENT_COLORS = listOf(
        0xFF6A1B9A.toInt(), 0xFF283593.toInt(), 0xFF0277BD.toInt(),
        0xFF00838F.toInt(), 0xFF2E7D32.toInt(), 0xFFEF6C00.toInt()
    )

    /**
     * Get the J2ME converted apps directory.
     * Uses Config.getAppDir() which is initialized by J2ME-Loader.
     */
    private fun getConvertedDir(): File {
        return try {
            File(ru.playsoftware.j2meloader.config.Config.getAppDir())
        } catch (e: Exception) {
            // Fallback if Config hasn't been initialized
            File(System.getProperty("user.dir"), "J2ME-Loader/converted")
        }
    }

    /**
     * Scan the converted directory for installed Java games.
     * Returns a list of GameEntry objects for each installed game.
     */
    fun loadAll(ctx: Context): List<GameEntry> {
        val results = mutableListOf<GameEntry>()
        val convertedDir = getConvertedDir()
        if (!convertedDir.exists() || !convertedDir.isDirectory) {
            return results
        }
        convertedDir.listFiles()?.forEach { appDir ->
            if (!appDir.isDirectory) return@forEach
            val dexFile = File(appDir, "converted.dex")
            val manifestFile = File(appDir, "converted.dex.conf")
            if (!dexFile.exists() || !manifestFile.exists()) return@forEach

            try {
                val manifest = parseManifest(manifestFile)
                val title = manifest["MIDlet-Name"] ?: appDir.name
                val iconFile = File(appDir, "icon.png")
                val iconPath = if (iconFile.exists()) iconFile.absolutePath else null
                val appPath = appDir.absolutePath

                // Check if this game is already in RomStore (for custom icon, favorite, etc.)
                val existing = RomStore.loadAll(ctx).firstOrNull { it.romPath == appPath }

                val entry = if (existing != null) {
                    existing.copy(
                        title = title,
                        platform = GamePlatform.JAVA,
                        coverPath = iconPath
                    )
                } else {
                    val accent = JAVA_ACCENT_COLORS[results.size % JAVA_ACCENT_COLORS.size]
                    GameEntry(
                        id = "java_${appDir.name}",
                        title = title,
                        romPath = appPath,
                        accent = Color(accent),
                        platform = GamePlatform.JAVA,
                        coverPath = iconPath
                    )
                }
                results.add(entry)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to parse Java game: ${appDir.name}", e)
            }
        }
        return results
    }

    /**
     * Install a JAR file as a J2ME game (单文件兼容入口，同步执行)。
     * Returns the installed game path on success, null on failure.
     * 批量/JAD 配对请用 [installJars]（suspend，IO 线程）。
     */
    fun installJar(ctx: Context, jarUri: Uri): String? {
        val (ok, _) = installJarsSync(ctx, listOf(jarUri))
        return if (ok > 0) lastInstallPath else null
    }

    /**
     * ★ 批量安装（v4.0）：支持同时选中 JAR 与 JAD（JAD 与同名 JAR 或
     * MIDlet-Jar-URL 指向的 JAR 配对）。返回 (成功数, 结果消息)。
     */
    suspend fun installJars(ctx: Context, uris: List<Uri>): Pair<Int, String> =
        withContext(Dispatchers.IO) { installJarsSync(ctx, uris) }

    /** 同步实现（IO 调度器内执行，也可直接调用） */
    fun installJarsSync(ctx: Context, uris: List<Uri>): Pair<Int, String> {
        lastInstallError = null
        if (uris.isEmpty()) return 0 to "未选择文件"
        val cacheDir = File(ctx.cacheDir, "installer")
        cacheDir.mkdirs()

        // ---- 第一步：把 SAF 文档全部落到缓存（保留扩展名，记录原始名） ----
        data class Staged(val file: File, val display: String)
        val staged = mutableListOf<Staged>()
        for (uri in uris) {
            try {
                val name = queryDisplayName(ctx, uri)
                    ?: "pick_${System.currentTimeMillis()}_${staged.size}.jar"
                val ext = name.substringAfterLast('.', "jar").lowercase()
                val f = File(cacheDir, "src_${System.currentTimeMillis()}_${staged.size}.$ext")
                ctx.contentResolver.openInputStream(uri)?.use { input ->
                    f.outputStream().use { output -> input.copyTo(output) }
                } ?: run {
                    lastInstallError = "无法读取所选文件"
                    return 0 to "无法读取所选文件"
                }
                staged.add(Staged(f, name))
            } catch (e: Exception) {
                Log.w(TAG, "stage failed for $uri", e)
            }
        }
        if (staged.isEmpty()) {
            lastInstallError = "无法读取所选文件（文件被占用或已删除）"
            return 0 to lastInstallError!!
        }

        // ---- 第二步：JAD 配对 JAR ----
        val jads = staged.filter { it.display.endsWith(".jad", true) }
        val jars = staged.filter { !it.display.endsWith(".jad", true) }.toMutableList()
        // 非法扩展名提前拒（提示更友好）
        jars.filter { !it.display.endsWith(".jar", true) && !it.display.endsWith(".zip", true) }
            .forEach {
                lastInstallError = "不支持的文件类型：${it.display}\n（Java 游戏需要 .jar 安装包，可选配套 .jad）"
            }
        val pairs = mutableListOf<Pair<Staged, Descriptor?>>() // jar -> jad?
        val unmatchedJads = mutableListOf<Staged>()
        for (jad in jads) {
            val desc = try {
                Descriptor(jad.file, true)
            } catch (e: Exception) {
                Log.w(TAG, "parse jad failed: ${jad.display}", e)
                unmatchedJads.add(jad)
                continue
            }
            val url = try { desc.getJarUrl() } catch (_: Exception) { null }
            val jarStaged: Staged? = when {
                url != null && url.startsWith("http", true) -> {
                    // 绝对地址 → 下载
                    try {
                        val dl = File(cacheDir, "dl_${System.currentTimeMillis()}.jar")
                        downloadTo(URL(url), dl)
                        Staged(dl, url.substringAfterLast('/'))
                    } catch (e: Exception) {
                        Log.w(TAG, "download jar failed: $url", e)
                        null
                    }
                }
                else -> {
                    // 同批 JAR 按名字配对（JAD 同名 / MIDlet-Jar-URL 基名）
                    val base = (url ?: jad.display.substringBeforeLast('.'))
                        .substringAfterLast('/').substringBeforeLast('.')
                    val jarName = if (url != null) url.substringAfterLast('/') else null
                    jars.firstOrNull { j ->
                        j.display.equals(jarName, true) ||
                                j.display.substringBeforeLast('.').equals(base, true)
                    } ?: jars.firstOrNull { j ->
                        j.display.endsWith(".jar", true)
                    }?.takeIf { jars.size == 1 && jads.size == 1 }
                }
            }
            if (jarStaged != null) {
                pairs.add(jarStaged to desc)
                jars.remove(jarStaged)
            } else {
                unmatchedJads.add(jad)
            }
        }
        for (j in jars) pairs.add(j to null)

        // ---- 第三步：逐个安装 ----
        var installed = 0
        val errors = mutableListOf<String>()
        for ((jar, jad) in pairs) {
            val r = installOne(ctx, jar.file, jad)
            if (r == null) installed++ else errors.add(r)
        }
        errors += unmatchedJads.map { "${it.display}：未找到对应 JAR（请把 JAD 和 JAR 一起选中）" }

        // 清缓存
        staged.forEach { runCatching { it.file.delete() } }
        runCatching {
            cacheDir.listFiles()?.forEach { f ->
                if (f.name.startsWith("dl_") || f.name.startsWith("src_")) f.delete()
            }
        }

        val msg = when {
            installed > 0 && errors.isEmpty() -> "已安装 $installed 个 Java 游戏"
            installed > 0 -> "已安装 $installed 个，${errors.size} 个失败：${errors.first()}"
            errors.isNotEmpty() -> {
                lastInstallError = errors.joinToString("\n")
                "安装失败：${errors.first()}"
            }
            else -> "没有可安装的文件"
        }
        return installed to msg
    }

    /** 单个 (jar, jad?) 安装；返回 null=成功，否则为错误消息 */
    private fun installOne(ctx: Context, jarFile: File, jad: Descriptor?): String? {
        try {
            // ---- manifest 读取（zip4j，大小写不敏感；绝不用 JarFile 做校验） ----
            val desc: Descriptor = try {
                ZipFile(jarFile).use { zf ->
                    val header = zf.getFileHeader("META-INF/MANIFEST.MF")
                        ?: return "JAR 内无 META-INF/MANIFEST.MF（不是 MIDlet 安装包）"
                    val text = zf.getInputStream(header).use { it.readBytes().toString(Charsets.UTF_8) }
                    Descriptor(text, false)
                }
            } catch (e: Exception) {
                Log.w(TAG, "read manifest failed", e)
                return "JAR 结构异常（${e.message ?: e.javaClass.simpleName}）"
            }
            if (jad != null) {
                try { desc.merge(jad) } catch (e: Exception) {
                    Log.w(TAG, "merge jad failed", e)
                }
            }

            val title = desc.getName()?.takeIf { it.isNotBlank() }
                ?: jarFile.nameWithoutExtension

            // ---- 目录名与 .tmp 暂存（JL-Mod 同款：失败不留半成品） ----
            val convertedDir = getConvertedDir()
            convertedDir.mkdirs()
            val appDirName = title.replace(Regex("[^\\w]"), "_").take(30)
            var finalDir = File(convertedDir, appDirName)
            var counter = 1
            while (finalDir.exists()) {
                finalDir = File(convertedDir, "${appDirName}_$counter")
                counter++
            }
            val tmpDir = File(convertedDir, ".tmp")
            if (tmpDir.exists()) tmpDir.deleteRecursively()
            if (!tmpDir.mkdirs()) return "无法创建临时目录"

            // ---- DEX 转换（dexlib 已同步 JL-Mod：CRC 逐类补丁 + 大小写不敏感路径） ----
            try {
                com.android.dx.command.dexer.Main.main(arrayOf(
                    "--no-optimize", "--core-library",
                    "--output=${File(tmpDir, "converted.dex").absolutePath}",
                    jarFile.absolutePath
                ))
            } catch (t: Throwable) {
                Log.e(TAG, "DEX conversion failed for $title", t)
                tmpDir.deleteRecursively()
                return "$title：JAR 转 DEX 失败（${t.message ?: t.javaClass.simpleName}）"
            }
            if (!File(tmpDir, "converted.dex").exists()) {
                tmpDir.deleteRecursively()
                return "$title：JAR 转 DEX 失败（dx 无输出，类文件可能损坏）"
            }

            // ---- res.jar / 图标 / manifest ----
            jarFile.copyTo(File(tmpDir, "res.jar"), overwrite = true)
            val icon = try { desc.getIcon() } catch (_: Exception) { null }
            if (icon != null) {
                try {
                    ZipFile(jarFile).use { zf ->
                        val path = icon.removePrefix("/")
                        val header = zf.getFileHeader(path)
                        if (header != null) {
                            zf.getInputStream(header).use { input ->
                                File(tmpDir, "icon.png").outputStream().use { output ->
                                    input.copyTo(output)
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "icon extract failed: $icon", e)
                }
            }
            desc.writeTo(File(tmpDir, "converted.dex.conf"))

            // ---- 原子落位 ----
            if (finalDir.exists()) finalDir.deleteRecursively()
            if (!tmpDir.renameTo(finalDir)) {
                tmpDir.copyRecursively(finalDir, overwrite = true)
                tmpDir.deleteRecursively()
            }

            // ---- 默认 config.json（MicroLoader.init() 依赖，安装时即建好） ----
            try {
                val configDir = File(
                    ru.playsoftware.j2meloader.config.Config.getConfigsDir(),
                    finalDir.name
                )
                if (!configDir.exists()) configDir.mkdirs()
                val defaultParams =
                    ru.playsoftware.j2meloader.config.ProfileModel(configDir)
                ru.playsoftware.j2meloader.config.ProfilesManager.saveConfig(defaultParams)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to create default config for $title", e)
            }

            RomStore.add(ctx, title, finalDir.absolutePath, GamePlatform.JAVA)
            Log.i(TAG, "Java game installed: $title at ${finalDir.absolutePath}")
            lastInstallPath = finalDir.absolutePath
            return null
        } catch (e: Exception) {
            Log.e(TAG, "Failed to install JAR", e)
            return "${e.message ?: e.javaClass.simpleName}"
        }
    }

    private fun queryDisplayName(ctx: Context, uri: Uri): String? = try {
        ctx.contentResolver.query(uri, null, null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0) c.getString(idx) else null
            } else null
        }
    } catch (e: Exception) {
        null
    }

    private fun downloadTo(url: URL, dst: File) {
        val conn = url.openConnection() as HttpURLConnection
        conn.connectTimeout = 15000
        conn.readTimeout = 60000
        conn.instanceFollowRedirects = true
        try {
            if (conn.responseCode !in 200..299) throw IOException("HTTP ${conn.responseCode}")
            conn.inputStream.use { input ->
                FileOutputStream(dst).use { output -> input.copyTo(output) }
            }
        } finally {
            conn.disconnect()
        }
    }

    /**
     * Launch a Java game using J2ME-Loader's Config.startApp().
     * Before launching, synchronizes the video filter setting from PadLayoutStore
     * to the J2ME preferences so MicroActivity reads the correct filter mode.
     */
    fun launchGame(ctx: Context, game: GameEntry) {
        try {
            val path = game.romPath ?: return
            val title = game.title

            // Synchronize video filter setting to J2ME prefs.
            // PadLayoutStore stores filter as string ("xbr", "4xbr", etc.)
            // MicroActivity reads from j2me_prefs as integer.
            syncVideoFilter(ctx)

            ru.playsoftware.j2meloader.config.Config.startApp(ctx, title, path, false)
            // Update last played
            RomStore.updateLastPlayed(ctx, game.id)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch Java game: ${game.title}", e)
        }
    }

    /**
     * Map video filter string from PadLayoutStore to J2ME integer mode.
     * Mode mapping: 0=none, 1=scanline, 2=CRT, 3=dot, 4=2XBR, 5=4XBR,
     *   6=2XBR+dot, 7=4XBR+dot, 8=HQ4x, 9=HQ4x+dot,
     *   10=TV(仿电视机), 11=2XBR+扫描线, 12=4XBR+扫描线, 13=HQ4x+扫描线,
     *   14=2XBR+仿电视机, 15=4XBR+仿电视机, 16=HQ4x+仿电视机
     */
    private fun videoFilterToJ2meMode(filter: String): Int = when (filter) {
        "scanline" -> 1
        "crt"      -> 2
        "dot"      -> 3
        "tv"       -> 10
        "xbr"      -> 4
        "4xbr"     -> 5
        "xbr_dot"  -> 6
        "4xbr_dot" -> 7
        "hq4x"     -> 8
        "hq4x_dot" -> 9
        "xbr_scanline"  -> 11
        "4xbr_scanline" -> 12
        "hq2x_scanline",
        "hq4x_scanline" -> 13
        "xbr_tv"        -> 14
        "4xbr_tv"       -> 15
        "hq2x_tv",
        "hq4x_tv"       -> 16
        else -> 0
    }

    /**
     * Synchronize the video filter from PadLayoutStore to j2me_prefs
     * SharedPreferences, so MicroActivity reads the correct filter mode.
     * Also applies the mode directly via Canvas.setJ2meFilterMode() for
     * immediate effect if the game is already running.
     */
    fun syncVideoFilter(ctx: Context) {
        try {
            val padLayout = PadLayoutStore.load(ctx)
            val j2meMode = videoFilterToJ2meMode(padLayout.videoFilter)
            // Persist to j2me_prefs for MicroActivity to read on startup
            val j2mePrefs = ctx.getSharedPreferences("j2me_prefs", Context.MODE_PRIVATE)
            j2mePrefs.edit().putInt("j2me_video_filter", j2meMode).apply()
            // Also set directly in case MicroActivity is already active
            javax.microedition.lcdui.Canvas.setJ2meFilterMode(j2meMode)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to sync J2ME video filter", e)
        }
    }

    /**
     * Open J2ME config/settings for a game.
     */
    fun openSettings(ctx: Context, game: GameEntry) {
        try {
            val path = game.romPath ?: return
            val title = game.title
            ru.playsoftware.j2meloader.config.Config.startApp(ctx, title, path, true)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open Java game settings: ${game.title}", e)
        }
    }

    /**
     * Delete a Java game from the converted directory.
     */
    fun deleteGame(ctx: Context, game: GameEntry) {
        try {
            val path = game.romPath ?: return
            val dir = File(path)
            if (dir.exists() && dir.isDirectory) {
                dir.deleteRecursively()
            }
            // GameBox: 同步删除 <configs>/<目录名>/，否则卸载后残留旧
            // config.json，重装同名游戏会继承陈旧的分辨率/缩放等设置。
            try {
                val configDir = File(
                    ru.playsoftware.j2meloader.config.Config.getConfigsDir(),
                    dir.name
                )
                if (configDir.exists()) configDir.deleteRecursively()
            } catch (e: Exception) {
                Log.w(TAG, "Failed to delete config dir for ${dir.name}", e)
            }
            RomStore.remove(ctx, game.id)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to delete Java game: ${game.title}", e)
        }
    }

    /**
     * Get the icon path for a Java game.
     */
    fun getIconPath(game: GameEntry): String? {
        val path = game.romPath ?: return null
        val iconFile = File(path, "icon.png")
        return if (iconFile.exists()) iconFile.absolutePath else null
    }

    private fun parseManifest(file: File): Map<String, String> {
        val result = mutableMapOf<String, String>()
        file.readLines().forEach { line ->
            val idx = line.indexOf(':')
            if (idx > 0) {
                val key = line.substring(0, idx).trim()
                val value = line.substring(idx + 1).trim()
                result[key] = value
            }
        }
        return result
    }
}
