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
 *   2. 文件名 → libretro 命名候选（基础变体 + 标题补点 + 区域码映射，
 *      详见 [nameCandidates]），每个系统目录依次尝试盒装封面与截图兜底；
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

    /** 单个游戏最多尝试的候选名数量（防止极端命名膨胀请求数）。 */
    private const val MAX_CANDIDATES = 16

    /**
     * GoodTools 旧式区域码 → libretro No-Intro 区域名。
     *
     * 多区域组合（JU/JUE/UE...）按 libretro 常见收录形式展开多个候选：
     * No-Intro 里多区域条目常以 "(World)" 收录，也可能拆成单个区域
     * （实测 NES "Super Mario Bros. (Europe)" 才是收录名）。每个候选
     * 命中不了只会多一个 404，无害。
     */
    private val REGION_ALIASES: Map<String, List<String>> = mapOf(
        // 单区域缩写 → No-Intro 全名
        "U" to listOf("USA"),
        "US" to listOf("USA"),
        "USA" to listOf("USA"),
        "J" to listOf("Japan"),
        "JP" to listOf("Japan"),
        "JPN" to listOf("Japan"),
        "JAPAN" to listOf("Japan"),
        "E" to listOf("Europe"),
        "EU" to listOf("Europe"),
        "EUR" to listOf("Europe"),
        "EUROPE" to listOf("Europe"),
        "W" to listOf("World"),
        "WORLD" to listOf("World"),
        "UK" to listOf("UK"),
        "AU" to listOf("Australia"),
        "AUS" to listOf("Australia"),
        "AS" to listOf("Asia"),
        "ASIA" to listOf("Asia"),
        "CN" to listOf("China"),
        "CHN" to listOf("China"),
        "CHINA" to listOf("China"),
        "TW" to listOf("Taiwan"),
        "TWN" to listOf("Taiwan"),
        "TAIWAN" to listOf("Taiwan"),
        "KR" to listOf("Korea"),
        "KOR" to listOf("Korea"),
        "KOREA" to listOf("Korea"),
        "BR" to listOf("Brazil"),
        "BRA" to listOf("Brazil"),
        "BRAZIL" to listOf("Brazil"),
        "CA" to listOf("Canada"),
        "CAN" to listOf("Canada"),
        "CANADA" to listOf("Canada"),
        "F" to listOf("France"),
        "FR" to listOf("France"),
        "FRANCE" to listOf("France"),
        "G" to listOf("Germany"),
        "DE" to listOf("Germany"),
        "GER" to listOf("Germany"),
        "GERMANY" to listOf("Germany"),
        "ES" to listOf("Spain"),
        "SPA" to listOf("Spain"),
        "SPAIN" to listOf("Spain"),
        "I" to listOf("Italy"),
        "IT" to listOf("Italy"),
        "ITALY" to listOf("Italy"),
        "NL" to listOf("Netherlands"),
        "NED" to listOf("Netherlands"),
        "NETHERLANDS" to listOf("Netherlands"),
        "SW" to listOf("Sweden"),
        "SWE" to listOf("Sweden"),
        "SWEDEN" to listOf("Sweden"),
        "RU" to listOf("Russia"),
        "RUS" to listOf("Russia"),
        "RUSSIA" to listOf("Russia"),
        "HK" to listOf("Hong Kong"),
        "GR" to listOf("Greece"),
        "GREECE" to listOf("Greece"),
        "NO" to listOf("Norway"),
        "NOR" to listOf("Norway"),
        "NORWAY" to listOf("Norway"),
        "SC" to listOf("Scandinavia"),
        "SCANDINAVIA" to listOf("Scandinavia"),
        // 多区域组合（GoodTools 旧码 → 常见 No-Intro 收录形态）
        "JU" to listOf("World", "USA", "Japan", "Europe"),
        "UJ" to listOf("World", "USA", "Japan", "Europe"),
        "JUE" to listOf("World", "USA", "Europe", "Japan"),
        "UJE" to listOf("World", "USA", "Europe", "Japan"),
        "JEU" to listOf("World", "USA", "Europe", "Japan"),
        "EJU" to listOf("World", "USA", "Europe", "Japan"),
        "EUJ" to listOf("World", "USA", "Europe", "Japan"),
        "UE" to listOf("World", "USA", "Europe"),
        "JE" to listOf("World", "Japan", "Europe"),
        "EJ" to listOf("World", "Japan", "Europe"),
        "WE" to listOf("World", "Europe"),
        "USJ" to listOf("World", "USA", "Japan"),
        "USE" to listOf("World", "USA", "Europe")
    )

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
     * ROM 文件名 / 标题 → libretro 缩略图命名候选（按命中概率排序）。
     *
     * libretro 缩略图库按 **No-Intro 命名** 组织（"Contra (USA).png"、
     * "Super Mario Bros. (Europe).png"），而用户 ROM 文件常是旧式 GoodTools
     * 命名（"Contra (U) [!].nes"、"Super Mario Bros (JU) [!].nes"），两者
     * 不一致是命中率的最大瓶颈。本函数分三档生成候选：
     *
     *   1. 基础变体：原名 → 只去 []（处理 [!]/[b]/[h] 等标签，保留区域/Disc）→ 全去标签
     *   2. 标题补点：No-Intro 标题里的缩写常带句点（"Bros."/"Dr."/"Vs."），
     *      对标题部分末尾补 "."（"Super Mario Bros" → "Super Mario Bros."）
     *   3. 区域码映射：GoodTools 旧区域码（(U)/(J)/(E)/(JU)/(JUE)...）替换为
     *      No-Intro 区域名（(USA)/(Japan)/(Europe)/(World)...），并对每个
     *      映射结果同样生成补点变体
     *
     * ★ 命中率修复：旧实现只生成"原名 + 全 stripped"2 个候选，既丢失
     *   `(USA)`/`(Disc 1)` 等 No-Intro 必需的标签，也无法处理 `(U)/(JU)`
     *   旧码与 "Bros." 句点 → 大量非 No-Intro 命名 ROM 永久 404。
     *   实测（thumbnails.libretro.com）："Mega Man 2 (U)" 只有映射成
     *   "Mega Man 2 (USA)" 才 200；"Super Mario Bros (JU) [!]" 必须走到
     *   "Super Mario Bros. (Europe)" 补点 + 区域映射组合才命中。
     */
    fun nameCandidates(rawName: String): List<String> {
        val n0 = rawName.trim()
            .replace('_', ' ')
            .replace(Regex("\\s+"), " ")
            .trim()
        if (n0.isEmpty()) return emptyList()
        val candidates = LinkedHashSet<String>()
        // 1) 基础变体
        val noBrackets = n0.replace(Regex("\\s*\\[[^]]*]"), "").trim()
        val noTags = noBrackets.replace(Regex("\\s*\\([^)]*\\)"), "").trim()
        candidates.add(n0)
        if (noBrackets.isNotBlank() && noBrackets != n0) candidates.add(noBrackets)
        if (noTags.isNotBlank() && noTags != n0 && noTags != noBrackets) candidates.add(noTags)
        // 2) 标题补点变体（No-Intro 缩写句点）
        for (v in listOf(n0, noBrackets, noTags)) {
            dotTitle(v)?.let { candidates.add(it) }
        }
        // 3) 区域码映射变体（GoodTools 旧码 → No-Intro 区域名）+ 补点组合
        for (mapped in mapRegions(noBrackets)) {
            candidates.add(mapped)
            dotTitle(mapped)?.let { candidates.add(it) }
        }
        // 极端命名（多 () 标签全命中映射表）可能膨胀候选，截断防止单游戏
        // 发过多请求。
        return candidates.toList().take(MAX_CANDIDATES)
    }

    /**
     * 标题部分末尾补句点（No-Intro 命名规范）。
     *
     *   "Super Mario Bros (JU)" → "Super Mario Bros. (JU)"
     *   "Contra"                → "Contra."
     *
     * 只对标题最后一个字符是字母/数字（或 !/?) 时追加 "."；标题已以句点 /
     * 空格 / 括号结尾时不处理。补点变体命中不了也只是多一个 404，无害；
     * 命中 "Bros."/"Dr."/"Vs." 类标题时是关键修复。
     */
    private fun dotTitle(name: String): String? {
        if (name.isBlank()) return null
        val idx = name.indexOf('(')
        val title = if (idx >= 0) name.substring(0, idx).trim() else name.trim()
        if (title.isEmpty()) return null
        val last = title.last()
        if (!last.isLetterOrDigit() && last !in "!?") return null
        return if (idx >= 0) "$title. ${name.substring(idx)}" else "$title."
    }

    /**
     * 区域码映射：把 GoodTools 旧式区域标签替换为 No-Intro 区域名。
     *
     *   "Contra (U)"          → "Contra (USA)"
     *   "Super Mario Bros (JU)" → "Super Mario Bros (World)" / "(USA)" / "(Japan)" / "(Europe)"
     *
     * 只处理 () 标签且内容命中 [REGION_ALIASES]；"Disc 1"、"Rev 1"、
     * "(En)" 等非区域标签不映射。每次只替换一个标签，保留其余部分。
     */
    private fun mapRegions(name: String): List<String> {
        if (name.isEmpty()) return emptyList()
        val results = LinkedHashSet<String>()
        val tagPattern = Regex("\\(([^)]+)\\)")
        for (m in tagPattern.findAll(name)) {
            val code = m.groupValues[1].trim().uppercase()
            val mapped = REGION_ALIASES[code] ?: continue
            for (r in mapped) {
                results.add(name.replaceRange(m.range, "($r)"))
            }
        }
        return results.toList()
    }

    /** 封面缓存目录。 */
    private fun coversDir(context: Context): File =
        File(context.filesDir, "covers").apply { mkdirs() }

    /**
     * 为单个游戏抓取封面。成功（已有或新下载）返回封面文件，否则 null。
     *
     * @param cache 批内 URL 结果去重缓存（[fetchAllMissing] 创建并共享）：
     *              同一 URL 在一批里只请求一次，成功结果直接复制复用。
     *              单游戏调用不传即可。
     */
    fun fetchCover(
        context: Context,
        game: GameEntry,
        cache: MutableMap<String, File?>? = null
    ): File? {
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
                if (downloadCached(url, dest, cache)) return dest
            }
            // 2) 游戏截图兜底（Named_Snaps —— 有图总比占位色块好）
            for (name in candidates) {
                val url = "$BASE/${urlSeg(sys)}/Named_Snaps/${urlSeg(name)}.png"
                if (downloadCached(url, dest, cache)) return dest
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
        // ★ 批内 URL 结果去重：同一批次里大量游戏的候选名重复（如
        //   "Contra.nes" 与 "Contra (U).nes" 都生成 "Contra (USA)"），
        //   不缓存的话同一 URL 会被反复请求。缓存后每个 URL 只请求一次，
        //   命中结果直接复制给后续同候选游戏，请求量大幅下降。
        val urlCache = HashMap<String, File?>()
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
                val cover = fetchCover(context, game, urlCache)
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

    /**
     * 带批内去重缓存的下载。
     *
     * - URL 未缓存：真正请求 [downloadImage]，成功缓存已落盘文件、
     *   失败缓存 null；
     * - URL 已缓存成功：把缓存文件复制到本游戏 dest，不重复联网；
     * - URL 已缓存失败：直接返回 false，跳过。
     */
    private fun downloadCached(
        url: String,
        dest: File,
        cache: MutableMap<String, File?>?
    ): Boolean {
        if (cache != null && cache.containsKey(url)) {
            val cached = cache[url]
            if (cached != null && cached.exists() && cached.length() > 0) {
                if (cached.absolutePath != dest.absolutePath) {
                    try {
                        cached.copyTo(dest, overwrite = true)
                        return true
                    } catch (_: Throwable) {
                        return false
                    }
                }
                return true
            }
            return false
        }
        val ok = downloadImage(url, dest)
        cache?.put(url, if (ok) dest else null)
        return ok
    }

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