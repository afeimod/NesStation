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
            // ★ GBC 封面目录修复：libretro 缩略图库把 Game Boy Color 游戏
            //   （口袋妖怪 金/银/水晶 等）放在 "Nintendo - Game Boy Color"
            //   目录下，纯 GB 目录里没有 → .gbc ROM 全部 404。按扩展名
            //   优先猜一个、两个都试（与 NGCWII 双目录策略一致）。
            GamePlatform.GB -> {
                val ext = romFileName?.substringAfterLast('.', "")?.lowercase() ?: ""
                if (ext == "gbc") {
                    listOf("Nintendo - Game Boy Color", "Nintendo - Game Boy")
                } else {
                    listOf("Nintendo - Game Boy", "Nintendo - Game Boy Color")
                }
            }
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
     *
     * ★★ 封面模糊匹配修复（本轮）：精确候选仍是第一梯队（零额外请求、
     *   最准确）；全部失败后，新增 [fetchSystemIndex] + [fuzzyMatch]
     *   路径 —— 拉取一次该系统的完整封面名索引并本地模糊匹配，
     *   覆盖"标题改写/分隔符差异/副标题缺失/序号写法不同"等精确候选
     *   永远覆盖不到的命名差异（"模糊读取 ROM 名而不是绝对名字"的需求）。
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
        // ★★ 中文 ROM 名 → 英文匹配修复 ★★
        //   中文命名 ROM（"超级玛丽 (汉化)"、"魂斗罗2"、"口袋妖怪金"）对
        //   No-Intro/libretro 的英文封面库，精确与模糊（英文词集）都打不中。
        //   现在先用内置词典把中文名直译成英文候选（剥汉化/中文标签 +
        //   长词优先短语翻译 + 序号保留），放在候选最前；纯英文名完全
        //   不受影响（zhToEnCandidates 对无 CJK 输入返回空）。
        val zhCandidates = zhToEnCandidates(rawName)
        val candidates = (zhCandidates + nameCandidates(rawName))
            .distinct()
            .take(MAX_CANDIDATES)
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
        // ★★ 封面模糊匹配（第二轮）：所有精确候选都 404 后，用系统索引
        //   + 本地模糊匹配找到最接近的官方 No-Intro 名再试。每个系统
        //   只拉一次索引（磁盘缓存 30 天），覆盖"标题改写/分隔符差异/
        //   副标题缺失/序号写法不同"等精确候选永远覆盖不到的命名差异
        //   （"模糊读取 ROM 名而不是绝对名字"的需求）。
        for (sys in systemDirs) {
            val index = fetchSystemIndex(context, sys) ?: continue
            // ★ 中文直译名同样参与模糊匹配（"超级玛丽" → "Super Mario"）
            val fuzzyNames = LinkedHashSet<String>()
            for (t in listOf(rawName) + zhCandidates) {
                fuzzyNames.addAll(fuzzyMatch(t, index))
            }
            val fuzzy = fuzzyNames.toList().take(MAX_FUZZY_TRIES)
            for (name in fuzzy) {
                // 模糊命中名与已试过的精确候选重叠时跳过（避免重复 404）
                if (candidates.any { it.equals(name, ignoreCase = true) }) continue
                val url = "$BASE/${urlSeg(sys)}/Named_Boxarts/${urlSeg(name)}.png"
                if (downloadImage(url, dest)) {
                    Log.i(TAG, "fuzzy cover hit: '$rawName' -> '$name' ($sys)")
                    return dest
                }
            }
        }
        return null
    }

    // ------------------------------------------------------------------
    // ★★ 封面模糊匹配（索引 + 本地相似度）
    // ------------------------------------------------------------------

    /** 内存内索引缓存：system → 官方 No-Intro 名列表（不含 .png 后缀）。 */
    private val indexMemory = HashMap<String, List<String>>()

    /** 索引磁盘缓存有效期（毫秒，30 天）。 */
    private const val INDEX_TTL_MS = 30L * 24 * 60 * 60 * 1000

    /** 单游戏最多尝试的模糊命中名数量。 */
    private const val MAX_FUZZY_TRIES = 3

    /** 模糊匹配最低相似度阈值（低于此值视为无匹配，避免误配到别的游戏）。 */
    private const val FUZZY_MIN_SCORE = 0.62

    /**
     * 拉取并缓存某系统的 Named_Boxarts 目录索引（Apache autoindex HTML）。
     *
     * thumbnails.libretro.com 对目录请求返回自动生成的 HTML 列表
     * （实测 HTTP 200，每系统约几百 KB ~ 数 MB）。解析出所有 .png
     * 链接名（= 官方 No-Intro 名），磁盘缓存 30 天。
     */
    private fun fetchSystemIndex(context: Context, system: String): List<String>? {
        indexMemory[system]?.let { return it }
        val dir = File(context.filesDir, "cover_index").apply { mkdirs() }
        // 文件名：系统名做了 url 友好化（避免文件系统非法字符）
        val safeName = system.replace(Regex("[^A-Za-z0-9]+"), "_").trim('_')
        val cacheFile = File(dir, "$safeName.txt")
        if (cacheFile.exists() &&
            System.currentTimeMillis() - cacheFile.lastModified() < INDEX_TTL_MS) {
            try {
                val lines = cacheFile.readLines().filter { it.isNotBlank() }
                if (lines.isNotEmpty()) {
                    indexMemory[system] = lines
                    return lines
                }
            } catch (_: Throwable) {}
        }
        // 拉取目录列表（限 8MB，防异常大响应）
        var conn: HttpURLConnection? = null
        return try {
            val url = "$BASE/${urlSeg(system)}/Named_Boxarts/"
            val c = URL(url).openConnection() as HttpURLConnection
            conn = c
            c.connectTimeout = 10000
            c.readTimeout = 20000
            c.instanceFollowRedirects = true
            c.setRequestProperty("User-Agent", USER_AGENT)
            if (c.responseCode != HttpURLConnection.HTTP_OK) return null
            val html = c.inputStream.use { input ->
                val buf = java.io.ByteArrayOutputStream()
                val buf8k = ByteArray(8192)
                var total = 0
                while (true) {
                    val n = input.read(buf8k)
                    if (n < 0) break
                    total += n
                    if (total > 8 * 1024 * 1024) return null
                    buf.write(buf8k, 0, n)
                }
                buf.toString("UTF-8")
            }
            // Apache autoindex 行形如 <a href="Contra%20%28USA%29.png">Contra (USA).png</a>
            // 用显示名（已解码）比 href 更可靠。
            val names = LinkedHashSet<String>()
            for (m in Regex("<a\\s+href=\"([^\"]+)\">([^<]*)</a>", RegexOption.IGNORE_CASE).findAll(html)) {
                val text = m.groupValues[2].trim()
                if (!text.endsWith(".png", ignoreCase = true)) continue
                val name = text.removeSuffix(".png").removeSuffix(".PNG")
                if (name.isNotBlank()) names.add(name)
            }
            if (names.isEmpty()) return null
            val list = names.toList()
            indexMemory[system] = list
            try { cacheFile.writeText(list.joinToString("\n")) } catch (_: Throwable) {}
            Log.i(TAG, "cover index loaded: $system (${list.size} names)")
            list
        } catch (t: Throwable) {
            Log.d(TAG, "cover index fetch failed: $system (${t.message})")
            null
        } finally {
            try { conn?.disconnect() } catch (_: Throwable) {}
        }
    }

    /**
     * 本地模糊匹配：把 ROM 名与官方 No-Intro 名列表比对，返回最接近的
     * 前几个官方名（按相似度降序，低于阈值的不返回）。
     *
     * 归一化策略（对双方一致应用）：
     *   - 全小写；
     *   - 去掉所有括号段（()/[]，区域/版本/标签差异不参与匹配）；
     *   - "&" → "and"、罗马数字 → 阿拉伯数字（II→2）、去标点、压缩空格；
     *   - "the" 等冠词丢弃。
     * 相似度 = 词集合 Jaccard 相似度 + 编辑距离比加权；完全相等直接 1.0，
     * 前缀包含有加分。
     */
    fun fuzzyMatch(rawName: String, officialNames: List<String>): List<String> {
        val target = normalizeForFuzzy(rawName)
        if (target.isBlank()) return emptyList()
        val targetTokens = target.split(' ').filter { it.isNotBlank() }.toSet()
        data class Scored(val name: String, val score: Double)
        val scored = ArrayList<Scored>(officialNames.size)
        for (official in officialNames) {
            val cand = normalizeForFuzzy(official)
            if (cand.isBlank()) continue
            var score = if (cand == target) 1.0 else {
                val candTokens = cand.split(' ').filter { it.isNotBlank() }.toSet()
                val inter = targetTokens.intersect(candTokens).size.toDouble()
                val union = targetTokens.union(candTokens).size.toDouble()
                val jaccard = if (union > 0) inter / union else 0.0
                val lev = levenshteinRatio(target, cand)
                var s = 0.55 * jaccard + 0.45 * lev
                // 前缀包含加分："zelda" vs "the legend of zelda" 类标题差异
                if (target.length >= 4 && (cand.startsWith(target) || target.startsWith(cand))) s += 0.08
                // ★ 词集包含加分：中文直译名常是"系列名+序号"（"魂斗罗2" →
                //   "contra 2"），而官方名是全称（"Contra II" 归一化后同为
                //   "contra 2" 命中；"Super Mario Bros. 3" 等全称更长）。
                //   目标词集是候选词集的子集且 ≥2 词时视为强相关
                //   （"castlevania 2" ⊆ "castlevania 2 simon s quest"）。
                if (targetTokens.size >= 2 && candTokens.containsAll(targetTokens)) s += 0.15
                s
            }
            if (score > 1.0) score = 1.0
            if (score >= FUZZY_MIN_SCORE) scored.add(Scored(official, score))
        }
        return scored.sortedByDescending { it.score }
            .take(MAX_FUZZY_TRIES)
            .map { it.name }
    }

    /** 模糊匹配归一化（双方一致应用，见 [fuzzyMatch] 注释）。 */
    private fun normalizeForFuzzy(name: String): String {
        var s = name.lowercase()
        // 去掉括号段（()/[]）：区域/版本/标签不参与匹配
        s = s.replace(Regex("\\([^)]*\\)"), " ")
        s = s.replace(Regex("\\[[^]]*]"), " ")
        s = s.replace("&", " and ")
        // 常见罗马数字（词边界）→ 阿拉伯数字（"Final Fight II" → "final fight 2"）
        s = s.replace(Regex("\\bii\\b"), "2")
        s = s.replace(Regex("\\biii\\b"), "3")
        s = s.replace(Regex("\\biv\\b"), "4")
        s = s.replace(Regex("\\bvi\\b"), "6")
        s = s.replace(Regex("\\bvii\\b"), "7")
        s = s.replace(Regex("\\bviii\\b"), "8")
        s = s.replace(Regex("\\bix\\b"), "9")
        // 去标点（保留字母数字与空格，保留 CJK）
        s = s.replace(Regex("[^a-z0-9\\u4e00-\\u9fff]+"), " ")
        // 丢冠词
        s = s.split(' ').filter { it.isNotBlank() && it != "the" }.joinToString(" ")
        return s.trim()
    }

    /** 编辑距离相似度（0..1）。 */
    private fun levenshteinRatio(a: String, b: String): Double {
        if (a == b) return 1.0
        if (a.isEmpty() || b.isEmpty()) return 0.0
        val maxLen = maxOf(a.length, b.length).toDouble()
        var prev = IntArray(b.length + 1) { it }
        var cur = IntArray(b.length + 1)
        for (i in 1..a.length) {
            cur[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + cost)
            }
            val tmp = prev; prev = cur; cur = tmp
        }
        return 1.0 - prev[b.length] / maxLen
    }

    // ------------------------------------------------------------------
    // ★★ 中文 ROM 名 → 英文直译（中文模糊转英文下载封面）
    // ------------------------------------------------------------------

    /** 判断字符串是否含汉字（CJK 统一表意文字区）。 */
    private fun hasCJK(s: String): Boolean = s.any { it.code in 0x4E00..0x9FFF }

    /**
     * 中文→英文词典（常见游戏系列 / 作品名，FC/SFC/GB/GBC/GBA/MD/DC/街机）。
     * 匹配按 **键长降序** 进行（"超级魂斗罗" 先于 "魂斗罗"，"超级马里奥世界"
     * 先于 "超级马里奥"），序号（阿拉伯数字 / 罗马数字）由后续流程保留，
     * 因此 "魂斗罗2" → "Contra 2"、"超级马里奥兄弟3" → "Super Mario Bros 3"。
     */
    private val ZH_EN: List<Pair<String, String>> = listOf(
        // --- 马里奥家族（长词优先） ---
        "超级马里奥兄弟" to "Super Mario Bros",
        "超级马力欧兄弟" to "Super Mario Bros",
        "超级玛丽兄弟" to "Super Mario Bros",
        "超级玛丽世界" to "Super Mario World",
        "超级马里奥世界" to "Super Mario World",
        "超级马力欧世界" to "Super Mario World",
        "超级马里奥赛车" to "Mario Kart",
        "超级马里奥" to "Super Mario",
        // ★ "超级玛丽" 译为全称 Super Mario Bros —— FC 版官方名。译成短名
        //   "Super Mario" 时精确候选与官方库全不匹配，模糊轮还会命中
        //   盗版卡带（"Super Mario 9" 等黑客版）的封面。
        "超级玛丽" to "Super Mario Bros",
        "超级玛莉" to "Super Mario Bros",
        "超级马力欧" to "Super Mario",
        "马里奥赛车" to "Mario Kart",
        "马力欧卡丁车" to "Mario Kart",
        "马里奥医生" to "Dr. Mario",
        "马力欧医生" to "Dr. Mario",
        "马里奥派对" to "Mario Party",
        "路易吉洋馆" to "Luigi's Mansion",
        "马里奥" to "Mario",
        "马力欧" to "Mario",
        "玛丽奥" to "Mario",
        "玛莉奥" to "Mario",
        "大金刚国度" to "Donkey Kong Country",
        "森喜刚" to "Donkey Kong",
        "大金刚" to "Donkey Kong",
        "耀西岛" to "Yoshi's Island",
        "耀西" to "Yoshi",
        // --- 塞尔达 ---
        "塞尔达传说 众神的三角力量" to "The Legend of Zelda A Link to the Past",
        "塞尔达传说众神的三角力量" to "The Legend of Zelda A Link to the Past",
        "众神的三角力量" to "A Link to the Past",
        "三角力量" to "A Link to the Past",
        "塞尔达传说 织梦岛" to "The Legend of Zelda Link's Awakening",
        "织梦岛" to "Link's Awakening",
        "塞尔达传说" to "The Legend of Zelda",
        "塞尔达" to "The Legend of Zelda",
        "梦见岛" to "Link's Awakening",
        "姆吉拉的假面" to "Majora's Mask",
        "时之笛" to "Ocarina of Time",
        // --- 科乐美系 ---
        "超级魂斗罗" to "Super Contra",
        "魂斗罗力量" to "Contra Force",
        "魂斗罗" to "Contra",
        "赤色要塞" to "Jackal",
        "沙罗曼蛇" to "Life Force",
        "恶魔城传说" to "Castlevania III Dracula's Curse",
        "恶魔城" to "Castlevania",
        "忍者龙剑传" to "Ninja Gaiden",
        "赤影战士" to "Shadow Warriors",
        "影子传说" to "The Legend of Kage",
        "热血硬派" to "River City Ransom",
        "热血物语" to "River City Ransom",
        // --- 卡普空系 ---
        "洛克人" to "Mega Man",
        "街头霸王" to "Street Fighter",
        "街霸" to "Street Fighter",
        "快打旋风" to "Final Fight",
        "恐龙快打" to "Cadillacs and Dinosaurs",
        "名将" to "Captain Commando",
        "三国志Ⅱ" to "Dynasty Wars 2",
        "圆桌骑士" to "Knights of the Round",
        "吞食天地" to "Destiny of an Emperor",
        "大战鲨鱼帮" to "Yo! Noid",
        // --- 世嘉系 ---
        "战斧" to "Golden Axe",
        "怒之铁拳" to "Streets of Rage",
        "索尼克" to "Sonic",
        "刺猬索尼克" to "Sonic the Hedgehog",
        // --- SNK 系 ---
        "拳皇" to "The King of Fighters",
        "格斗之王" to "The King of Fighters",
        "饿狼传说" to "Fatal Fury",
        "龙虎之拳" to "Art of Fighting",
        "侍魂" to "Samurai Shodown",
        "合金弹头" to "Metal Slug",
        "三国战记" to "Knights of Valour",
        "西游释厄传" to "Oriental Legend",
        // --- 谜题 / 休闲 ---
        "雪人兄弟" to "Snow Bros",
        "泡泡龙" to "Bubble Bobble",
        "松鼠大战" to "Chip n Dale Rescue Rangers",
        "俄罗斯方块" to "Tetris",
        "打砖块" to "Arkanoid",
        "吃豆人" to "Pac-Man",
        "挖金子" to "Lode Runner",
        // --- 平台 / 冒险 ---
        "冒险岛" to "Adventure Island",
        "忍者神龟" to "Teenage Mutant Ninja Turtles",
        "忍者龟" to "Teenage Mutant Ninja Turtles",
        "蝙蝠侠" to "Batman",
        "星之卡比" to "Kirby",
        "卡比" to "Kirby",
        "银河战士" to "Metroid",
        "星际火狐" to "Star Fox",
        // --- RPG / 模拟 ---
        "牧场物语" to "Harvest Moon",
        "火焰之纹章" to "Fire Emblem",
        "火焰纹章" to "Fire Emblem",
        "高级战争" to "Advance Wars",
        "黄金太阳" to "Golden Sun",
        "勇者斗恶龙" to "Dragon Quest",
        "最终幻想" to "Final Fantasy",
        "太空战士" to "Final Fantasy",
        "重装机兵" to "Metal Max",
        "超级机器人大战" to "Super Robot Wars",
        "三国演义" to "Romance of the Three Kingdoms",
        "三国志" to "Romance of the Three Kingdoms",
        "信长的野望" to "Nobunaga's Ambition",
        // --- 漫画改编 ---
        "圣斗士星矢" to "Saint Seiya",
        "龙珠" to "Dragon Ball",
        "幽游白书" to "Yu Yu Hakusho",
        "火影忍者" to "Naruto",
        "数码宝贝" to "Digimon",
        "口袋妖怪" to "Pokemon",
        "宠物小精灵" to "Pokemon",
        "神奇宝贝" to "Pokemon",
        "宝可梦" to "Pokemon",
        // ★ 带空格分隔的版本词（"口袋妖怪 金"）：合成短语整段翻译，避免
        //   版本单字（金/银/红/蓝…）残留汉字导致译文被丢弃，也避免把
        //   单字误替换进无关名（如"大金刚"）。
        "口袋妖怪 红宝石" to "Pokemon Ruby",
        "口袋妖怪 蓝宝石" to "Pokemon Sapphire",
        "口袋妖怪 绿宝石" to "Pokemon Emerald",
        "口袋妖怪 白金" to "Pokemon Platinum",
        "口袋妖怪 钻石" to "Pokemon Diamond",
        "口袋妖怪 珍珠" to "Pokemon Pearl",
        "口袋妖怪 水晶" to "Pokemon Crystal",
        "口袋妖怪 金" to "Pokemon Gold",
        "口袋妖怪 银" to "Pokemon Silver",
        "口袋妖怪 红" to "Pokemon Red",
        "口袋妖怪 蓝" to "Pokemon Blue",
        "口袋妖怪 黄" to "Pokemon Yellow",
        "口袋妖怪 皮卡丘" to "Pokemon Yellow",
        "宝可梦 红宝石" to "Pokemon Ruby",
        "宝可梦 蓝宝石" to "Pokemon Sapphire",
        "宝可梦 绿宝石" to "Pokemon Emerald",
        "宝可梦 金" to "Pokemon Gold",
        "宝可梦 银" to "Pokemon Silver",
        "宠物小精灵 金" to "Pokemon Gold",
        "宠物小精灵 银" to "Pokemon Silver",
        "神奇宝贝 金" to "Pokemon Gold",
        "神奇宝贝 银" to "Pokemon Silver",
        "口袋妖怪黑" to "Pokemon Black",
        "口袋妖怪白" to "Pokemon White",
        "口袋妖怪红宝石" to "Pokemon Ruby",
        "口袋妖怪蓝宝石" to "Pokemon Sapphire",
        "口袋妖怪绿宝石" to "Pokemon Emerald",
        // --- 其他名作 ---
        "生化危机" to "Resident Evil",
        "合金装备" to "Metal Gear Solid",
        "皇牌空战" to "Ace Combat",
        "实况足球" to "Pro Evolution Soccer",
        "恶魔城白夜" to "Castlevania Harmony of Dissonance",
        "晓月圆舞曲" to "Castlevania Aria of Sorrow",
        "月下夜想曲" to "Castlevania Symphony of the Night"
    )

    /** 键长降序的词典视图（首次访问时排序，进程内复用）。 */
    private val ZH_EN_SORTED: List<Pair<String, String>> by lazy {
        ZH_EN.sortedByDescending { it.first.length }
    }

    /**
     * 中文名 → 英文候选名列表（0..2 个）。
     *
     * 处理链：
     *   1. 去 ()/[] 段 —— "(汉化)(中文)[简体]" 等标签随括号去除；
     *   2. 去无括号的中文限定词 —— "汉化版/中文版/简体/繁体/官方中文" 与
     *      数量词 "第N代/N代/第N部/第N集"（"吞食天地2代" → "吞食天地2"）；
     *   3. 词典长词优先直译，序号/字母数字原样保留；
     *   4. 仍残留汉字的翻译结果丢弃（词典未收录 → 交给模糊轮兜底）；
     *   5. 混合名（中英并存，如 "超级玛丽 Super Mario Bros"）额外给出
     *      纯 ASCII 提取候选。
     */
    fun zhToEnCandidates(rawName: String): List<String> {
        if (!hasCJK(rawName)) return emptyList()
        var s = rawName
            .replace(Regex("\\([^)]*\\)"), " ")
            .replace(Regex("\\[[^]]*]"), " ")
        s = s.replace(Regex("汉化版|汉化|中文版|官方中文|简体中文|繁体中文|简体|繁体|机翻|中文"), " ")
        s = s.replace(Regex("第[一二三四五六七八九十0-9]+代|第[一二三四五六七八九十0-9]+[部集]|[一二三四五六七八九十0-9]+[部集]"), " ")
        s = s.replace('_', ' ').replace(Regex("\\s+"), " ").trim()
        if (s.isEmpty()) return emptyList()
        val out = LinkedHashSet<String>()
        // 词典直译（长词优先）
        val translated = translatePhrases(s)
        if (translated.isNotBlank() && !hasCJK(translated)) out.add(translated)
        // 混合名：提取纯 ASCII 段（中英并存时英文段往往就是标题）
        if (hasCJK(translated)) {
            val ascii = s.replace(Regex("[^A-Za-z0-9&'!.\\-]+"), " ")
                .replace(Regex("\\s+"), " ").trim()
            if (ascii.length >= 3) out.add(ascii)
        }
        return out.filter { it.isNotBlank() }
    }

    /** 词典长词优先直译（只替换仍为中文的段落中的短语，字母数字段原样保留）。 */
    private fun translatePhrases(s: String): String {
        var result = s
        for ((zh, en) in ZH_EN_SORTED) {
            if (zh !in result) continue
            result = result.replace(zh, " $en ")
        }
        return result.replace(Regex("\\s+"), " ").trim()
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