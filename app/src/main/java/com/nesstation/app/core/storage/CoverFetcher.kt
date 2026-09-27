package com.nesstation.app.core.storage

import android.content.Context
import android.util.Log
import com.nesstation.app.core.model.GameEntry
import com.nesstation.app.core.model.GamePlatform
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * ★ 全核心游戏封面自动获取（NesStation 集成补丁）。
 *
 * 需求来源："DC 模拟器 flycast 是可以获取游戏封面的，加入进去并考虑
 * 其他核心也要自动读取封面"。实现采用 libretro 官方缩略图库
 * thumbnails.libretro.com —— 免费公开、无需 API key、按 No-Intro 命名
 * 组织，**覆盖本应用全部核心平台**（含 Dreamcast）：
 *
 *   https://thumbnails.libretro.com/<System>/Named_Boxarts/<Name>.png
 *   https://thumbnails.libretro.com/<System>/Named_Snaps/<Name>.png   (兜底截图)
 *
 * 工作流：
 *   1. 扫描/刷新入库后，对缺封面（coverPath 为空且无自定义图标）的游戏
 *      逐个尝试下载；
 *   2. 文件名 → libretro 命名归一化（去扩展名 / 去分区/版本标签 / 下划线
 *      转空格），依次尝试"原名 → 去标签名 → 截图兜底"三个候选；
 *   3. 校验图片魔数（PNG/JPEG）后落盘 <filesDir>/covers/<gameId>.png，
 *      并写回 RomStore（coverPath 持久化，重启不丢）；
 *   4. 库卡片（FsdGameCover → GameIconExtractor.resolveIconPath 已支持
 *      coverPath）自动展示，无需 UI 改动。
 *
 * 线程模型：全部方法设计为在 IO 线程调用（fetchAllMissing 顺序抓取，
 * 间隔 250ms 温和限速）；单游戏失败只记日志，绝不中断批次。
 */
object CoverFetcher {

    private const val TAG = "CoverFetcher"
    private const val BASE = "https://thumbnails.libretro.com"
    private const val USER_AGENT = "NesStation/1.0 (Android; cover-fetcher)"
    private const val MAX_IMAGE_BYTES = 4 * 1024 * 1024
    private const val FETCH_INTERVAL_MS = 250L

    /** 平台 → libretro 缩略图系统目录名（null = 无源，跳过）。 */
    fun libretroSystemDir(platform: GamePlatform, romFileName: String?): List<String> {
        return when (platform) {
            GamePlatform.NES -> listOf("Nintendo - Nintendo Entertainment System")
            GamePlatform.SFC -> listOf("Nintendo - Super Nintendo Entertainment System")
            GamePlatform.GB -> listOf("Nintendo - Game Boy")
            GamePlatform.GBA -> listOf("Nintendo - Game Boy Advance")
            GamePlatform.MD -> listOf("Sega - Mega Drive - Genesis")
            GamePlatform.PCE -> listOf("NEC - PC Engine - TurboGrafx-16")
            GamePlatform.NDS -> listOf("Nintendo - Nintendo DS")
            GamePlatform.PSX -> listOf("Sony - PlayStation")
            GamePlatform.PS2 -> listOf("Sony - PlayStation 2")
            GamePlatform.DC -> listOf("Sega - Dreamcast")
            GamePlatform.N3DS -> listOf("Nintendo - Nintendo 3DS")
            // NGCWII 双平台共存：按 ROM 扩展名优先猜一个，两个都试。
            GamePlatform.NGCWII -> {
                val ext = romFileName?.substringAfterLast('.', "")?.lowercase() ?: ""
                if (ext in setOf("wbfs", "wad", "dol", "elf", "rvz")) {
                    listOf("Nintendo - Wii", "Nintendo - GameCube")
                } else {
                    listOf("Nintendo - GameCube", "Nintendo - Wii")
                }
            }
            // ★ 街机接入修复：libretro 缩略图库确实有 FBNeo/MAME 街机封面
            //   目录按 No-Intro 长名组织，驱动名（如 kof98h.zip）匹配率低，
            //   但少量 ROM 仍可命中。同时尝试两个目录提高命中率。
            GamePlatform.ARCADE -> listOf("FBNeo - Arcade Games", "MAME")
            // JAVA/DOS 仍无源 —— 跳过（Java 已有内置 icon，DOS 用占位）
            else -> emptyList()
        }
    }

    /**
     * ROM 文件名 / 标题 → libretro 缩略图命名候选（按命中概率排序）：
     *   "Super Mario Bros (JU) [!]" →
     *     1) "Super Mario Bros (JU) [!]"（原名直试）
     *     2) "Super Mario Bros (JU)"（只去 [] 保留区域/Disc）
     *     3) "Super Mario Bros"（全去标签）
     *     4) "Super Mario Bros."（末尾加点 —— libretro No-Intro 规范）
     *
     * ★ 候选名清理修复：
     *   - 旧实现只生成 2 个候选（原名 + 全 stripped），丢失必要的
     *     `(USA)`、`(Disc 1)` 标签 → 多 Disc 游戏大量 404。
     *   - 旧实现对 SAF 导入的 content:// URI 直接处理导致双重 URL 编码。
     *   - 新实现增加"只去 []"、"末尾加点"变体，提高命中率。
     */
    fun nameCandidates(rawName: String): List<String> {
        val n0 = rawName.trim()
            .replace('_', ' ')
            .replace(Regex("\\s+"), " ")
            .trim()
        if (n0.isEmpty()) return emptyList()
        val candidates = LinkedHashSet<String>()
        candidates.add(n0)
        // 变体 1：只去 [] 标签（保留区域/Disc 信息）
        val noBrackets = n0.replace(Regex("\\s*\\[[^]]*]"), "").trim()
        if (noBrackets.isNotBlank() && noBrackets != n0) candidates.add(noBrackets)
        // 变体 2：全去标签（[] 与 () 都去）
        val stripped = noBrackets.replace(Regex("\\s*\\([^)]*\\)"), "").trim()
        if (stripped.isNotBlank() && stripped != n0 && stripped != noBrackets) candidates.add(stripped)
        // 变体 3：末尾加点（libretro No-Intro 习惯 "Super Mario Bros."）
        if (!n0.endsWith(".") && !n0.endsWith(" ")) {
            candidates.add("$n0.".replace("..", "."))
        }
        return candidates.toList()
    }

    /** 封面缓存目录。 */
    private fun coversDir(context: Context): File =
        File(context.filesDir, "covers").apply { mkdirs() }

    /**
     * 为单个游戏抓取封面。成功（已有或新下载）返回封面文件，否则 null。
     */
    fun fetchCover(context: Context, game: GameEntry): File? {
        // 已有封面 → 直接复用
        game.coverPath?.let { p ->
            val f = File(p)
            if (f.exists() && f.length() > 0) return f
        }
        val romFile = game.romPath?.substringAfterLast('/') ?: ""
        val systemDirs = libretroSystemDir(game.platform, romFile)
        if (systemDirs.isEmpty()) return null
        // ★ SAF URI 双重编码修复：game.romPath 在 SAF 导入时是 content:// URI，
        //   最后一段是 URL 编码的 documentId（含 %20 %28 %5B 等编码字符）。
        //   旧实现对它直接 substringAfterLast + substringBeforeLast('.'),
        //   再经 urlSeg 二次编码 → 永远 404。
        //   game.title 在导入时由 queryDisplayName 解码后写入，是干净的可读名。
        //   优先用 game.title；只有 title 为空时才退回 romPath 解析。
        val rawName = game.title.takeIf { it.isNotBlank() }
            ?: romFile.substringBeforeLast('.').takeIf { it.isNotBlank() }
            ?: return null
        val candidates = nameCandidates(rawName)
        if (candidates.isEmpty()) return null

        val dest = File(coversDir(context), "${game.id}.png")
        for (sys in systemDirs) {
            // 1) 盒装封面（Named_Boxarts）
            for (name in candidates) {
                val url = "$BASE/${urlSeg(sys)}/Named_Boxarts/${urlSeg(name)}.png"
                if (downloadImage(url, dest)) return dest
            }
            // 2) 游戏截图兜底（Named_Snaps —— 有图总比占位色块好）
            for (name in candidates) {
                val url = "$BASE/${urlSeg(sys)}/Named_Snaps/${urlSeg(name)}.png"
                if (downloadImage(url, dest)) return dest
            }
        }
        return null
    }

    /**
     * 批量为缺封面的游戏抓取（顺序 + 限速，IO 线程调用）。
     *
     * @param games 候选列表（通常为当前平台页的全部游戏）
     * @param onlyMissing true = 只处理无封面且无自定义图标的条目
     * @param onProgress 每完成一个回调（done, total）—— UI 进度提示用
     * @param limit 单批上限（默认 200，防止一次刷几百个请求）
     * @return 实际新下载成功的数量
     */
    fun fetchAllMissing(
        context: Context,
        games: List<GameEntry>,
        onlyMissing: Boolean = true,
        limit: Int = 200,
        onProgress: ((Int, Int) -> Unit)? = null
    ): Int {
        val pending = games.filter { g ->
            !onlyMissing || (g.coverPath.isNullOrBlank() && g.customIconPath.isNullOrBlank())
        }
        if (pending.isEmpty()) return 0
        val batch = pending.take(limit)
        var done = 0
        var fetched = 0
        // 批量缓冲：每 25 个或批次结束时一次 setCoverPaths（线性 IO），
        // 中途被杀最多丢最近 25 个的入库记录（封面文件仍在缓存目录，
        // 下次抓取会重新关联）。
        val buffer = LinkedHashMap<String, String>()
        fun flush() {
            if (buffer.isNotEmpty()) {
                try {
                    RomStore.setCoverPaths(context, buffer)
                    fetched += buffer.size
                } catch (t: Throwable) {
                    Log.w(TAG, "cover persist failed: ${t.message}")
                }
                buffer.clear()
            }
        }
        for (game in batch) {
            try {
                val cover = fetchCover(context, game)
                if (cover != null && !cover.absolutePath.equals(game.coverPath)) {
                    buffer[game.id] = cover.absolutePath
                }
            } catch (t: Throwable) {
                Log.w(TAG, "cover fetch failed for ${game.title}: ${t.message}")
            }
            done++
            onProgress?.invoke(done, batch.size)
            if (buffer.size >= 25) flush()
            if (done < batch.size) {
                try { Thread.sleep(FETCH_INTERVAL_MS) } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    break
                }
            }
        }
        flush()
        Log.i(TAG, "Cover fetch batch done: $fetched/${batch.size} succeeded")
        return fetched
    }

    // ------------------------------------------------------------------
    // 内部
    // ------------------------------------------------------------------

    // ★ URL 括号编码修复：libretro 缩略图库的实际 URL 使用未编码的括号
    //   （如 ".../Named_Boxarts/Super Mario Bros. (World).png"），
    //   但 URLEncoder.encode("(", "UTF-8") = "%28"，
    //   导致带 (USA)/(World)/(Disc 1) 的游戏名大量 404。
    //   修复：把 %28/%29 还原为未编码括号。
    private fun urlSeg(s: String): String =
        URLEncoder.encode(s, "UTF-8")
            .replace("+", "%20")
            .replace("%28", "(")
            .replace("%29", ")")

    /** 下载并校验图片魔数；成功落盘返回 true。 */
    private fun downloadImage(urlStr: String, dest: File): Boolean {
        var conn: HttpURLConnection? = null
        return try {
            val conn0 = URL(urlStr).openConnection() as HttpURLConnection
            conn = conn0
            conn0.connectTimeout = 8000
            conn0.readTimeout = 12000
            conn0.instanceFollowRedirects = true
            conn0.setRequestProperty("User-Agent", USER_AGENT)
            conn0.setRequestProperty("Accept", "image/png,image/*")
            val code = conn0.responseCode
            if (code != HttpURLConnection.HTTP_OK) return false
            val len = conn0.contentLengthLong
            // ★ chunked encoding 修复：len < 0 表示未知长度（chunked），
            //   旧实现直接返回 false 导致部分 libretro 缩略图失败。
            //   仅拦超大响应（>4MB），其它情况都走流式拷贝。
            if (len in 1..MAX_IMAGE_BYTES.toLong() || len <= 0) {
                val tmp = File(dest.absolutePath + ".tmp")
                conn0.inputStream.use { input ->
                    val head = ByteArray(12)
                    val out = tmp.outputStream()
                    try {
                        val read = input.read(head)
                        if (read >= 8 && !looksLikeImage(head)) {
                            out.close(); tmp.delete(); return false
                        }
                        if (read > 0) out.write(head, 0, read)
                        input.copyTo(out)
                    } finally {
                        try { out.close() } catch (_: Exception) {}
                    }
                }
                val tmpLen = tmp.length()
                if (tmpLen <= 0L || tmpLen >= MAX_IMAGE_BYTES) {
                    tmp.delete(); return false
                }
                if (dest.exists()) dest.delete()
                if (!tmp.renameTo(dest)) {
                    tmp.copyTo(dest, overwrite = true); tmp.delete()
                }
                return true
            }
            false
        } catch (t: Throwable) {
            Log.d(TAG, "download miss: $urlStr (${t.message})")
            false
        } finally {
            try { conn?.disconnect() } catch (_: Throwable) {}
        }
    }

    /**
     * PNG / JPEG / GIF / WebP 魔数粗校验（libretro 偶发返回 HTML 错误页）。
     *
     * ★ 编译修复：head 是 ByteArray，元素为 Byte，直接与 Int 十六进制
     *   字面量比较在 Kotlin 中会编译失败（"Operator '==' cannot be
     *   applied to 'Byte' and 'Int'"）。所有字节比较统一先做无符号
     *   整数转换：(head[i].toInt() and 0xFF)。
     */
    private fun looksLikeImage(head: ByteArray): Boolean {
        val png = head.size >= 8 &&
            (head[0].toInt() and 0xFF) == 0x89 &&
            (head[1].toInt() and 0xFF) == 0x50 &&
            (head[2].toInt() and 0xFF) == 0x4E &&
            (head[3].toInt() and 0xFF) == 0x47
        val jpg = head.size >= 3 &&
            (head[0].toInt() and 0xFF) == 0xFF &&
            (head[1].toInt() and 0xFF) == 0xD8
        val gif = head.size >= 4 &&
            (head[0].toInt() and 0xFF) == 0x47 &&
            (head[1].toInt() and 0xFF) == 0x49 &&
            (head[2].toInt() and 0xFF) == 0x46
        val webp = head.size >= 12 &&
            (head[8].toInt() and 0xFF) == 0x57 &&
            (head[9].toInt() and 0xFF) == 0x45 &&
            (head[10].toInt() and 0xFF) == 0x42 &&
            (head[11].toInt() and 0xFF) == 0x50
        return png || jpg || gif || webp
    }
}