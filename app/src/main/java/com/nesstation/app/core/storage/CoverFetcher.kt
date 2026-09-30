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

    // ★★ 多镜像加固（本轮）★★
    //   thumbnails.libretro.com（Apache 直出）在部分网络环境下载缓慢/不稳，
    //   而缩略图库在 GitHub 有官方镜像（libretro-thumbnails 组织，文件名
    //   与站点完全一致），jsDelivr CDN 对其可达性/速度普遍更好。每个 URL
    private const val MIRROR_BASE = "https://cdn.jsdelivr.net/gh/libretro-thumbnails"

    // ★★★ 本轮（第三次“封面全部跳过”根治）：gh 代理链 ★★★
    //   用户实测（大陆网络）：libretro 直连 + 三个 jsDelivr 通道全部不可达
    //   → 精确候选与索引全部失败 → “根本没有去搜索并下载封面”。
    //   raw.githubusercontent.com 内容经公共 gh 代理转发在大陆可达性最好
    //   （实测 gh-proxy.com / ghfast.top / ghproxy.net 均能 200 拿到同一张
    //   封面），作为第二优先通道加入；文件路径与 GitHub 完全一致。
    private const val RAW_PREFIX = "https://raw.githubusercontent.com/libretro-thumbnails"
    private val GH_PROXIES = listOf(
        "https://gh-proxy.com/",
        "https://ghfast.top/",
        "https://ghproxy.net/"
    )

    // ★ 通道健康记忆（会话级）：连续 3 次连接级失败（DNS/连接/超时，
    //   404 不算）的通道在本次进程内禁用 —— 批量抓取时迅速收敛到可用
    //   通道，避免每个候选名都把全部死通道超时一遍（旧实现 14 个游戏
    //   每个候选 × 4 通道 × 8s 连接超时 = 分钟级卡死）。
    private val CHANNEL_FAIL_THRESHOLD = 3
    private val channelFailCount = HashMap<String, Int>()
    private fun channelAlive(channel: String): Boolean =
        (channelFailCount[channel] ?: 0) < CHANNEL_FAIL_THRESHOLD
    private fun noteChannelConnFailed(channel: String) {
        channelFailCount[channel] = (channelFailCount[channel] ?: 0) + 1
    }
    private fun noteChannelAlive(channel: String) {
        channelFailCount.remove(channel)
    }
    /** 本会话内被禁用的通道列表（诊断用）。 */
    fun deadChannels(): List<String> =
        channelFailCount.filter { it.value >= CHANNEL_FAIL_THRESHOLD }.keys.toList()

    /** 批量抓取统计（fetchAllMissing 结束后 UI 可读取，用于诊断提示）。 */
    data class BatchStats(
        var attempted: Int = 0,
        var succeeded: Int = 0,
        var indexFailed: Int = 0,
        var notFound: Int = 0
    ) {
        fun summary(): String =
            "尝试 $attempted，成功 $succeeded；${
                if (indexFailed > 0) "封面索引不可用 $indexFailed 次（网络受限）" else ""
            }${
                if (notFound > 0) "${if (indexFailed > 0) "；" else ""}未匹配到 $notFound 个" else ""
            }"
    }

    /** 最近一次批量抓取统计（UI 线程读取）。 */
    @Volatile
    var lastBatchStats: BatchStats = BatchStats()
        private set

    /**
     * 构建某系统目录下同一张封面图的多通道 URL 列表（按优先级）。
     * 系统目录名中的空格在 GitHub 仓库名里是下划线
     * ("Nintendo - NES" → "Nintendo_-_NES")。
     *
     * ★★ 多镜像加固：★★
     *   用户网络环境（中国大陆）实测 thumbnails.libretro.com 直连与
     *   cdn.jsdelivr.net 均常不可达 → “封面全部跳过”的直接原因。
     *   现按优先级依次尝试 7 个通道：
     *     1. libretro 官方直连；
     *     2. gh-proxy.com（raw.githubusercontent 内容，大陆可达性好）；
     *     3. ghfast.top（同上）；
     *     4. ghproxy.net（同上）；
     *     5. fastly.jsdelivr.net（Fastly 边缘）；
     *     6. gcore.jsdelivr.net（Gcore 边缘）；
     *     7. cdn.jsdelivr.net（主站）。
     *   文件名与目录结构在各通道完全一致（同一份 GitHub 仓库内容），
     *   任一通道 200 即命中；通道健康记忆（channelFailCount）会让批量
     *   抓取在 2-3 次失败后自动收敛到可用通道。
     *
     * @return (通道名, URL) 列表 —— 通道名用于健康记忆与诊断。
     */
    fun coverImageUrls(system: String, subDir: String, name: String): List<Pair<String, String>> {
        val jsRepo = system.replace(' ', '_')
        val segName = urlSeg(name)
        val segDir = urlSeg(system)
        val rawPath = "$jsRepo/master/$subDir/$segName.png"
        return buildList {
            add("libretro" to "$BASE/$segDir/$subDir/$segName.png")
            for (p in GH_PROXIES) add("ghproxy:${p.removePrefix("https://")}" to "$p$RAW_PREFIX/$rawPath")
            add("jsdelivr:fastly" to "https://fastly.jsdelivr.net/gh/libretro-thumbnails/$jsRepo/$subDir/$segName.png")
            add("jsdelivr:gcore" to "https://gcore.jsdelivr.net/gh/libretro-thumbnails/$jsRepo/$subDir/$segName.png")
            add("jsdelivr:cdn" to "$MIRROR_BASE/$jsRepo/$subDir/$segName.png")
        }
    }

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
            // ★ PCE 目录名修正：libretro 站上实际目录是 "TurboGrafx 16"（空格，
            //   站点目录列表实测），旧值 "TurboGrafx-16" 404 → PCE 封面全挂。
            GamePlatform.PCE -> listOf("NEC - PC Engine - TurboGrafx 16")
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
        // 4) ★ 区域后缀变体（本轮新增 —— 索引免依赖直命中）：
        //   libretro 收录名几乎全部带区域标签（"Metal Max 4 - Gekkou no
        //   Diva (Japan)"），而翻译/手输的搜索名常无区域 → 精确候选全部
        //   404，只能依赖"拉整个系统索引（可达 12MB）+ 模糊匹配"兜底；
        //   网络受限时索引拉不下来 → "封面全部跳过"。对**无任何 () 区域
        //   标签**的名字直接补 4 个常见区域变体，精确通道即可命中：
        //     日文游戏翻译名（中文名映射而来）→ (Japan) 放最前。
        if (!n0.contains('(')) {
            for (r in listOf("Japan", "USA", "Europe", "World")) {
                val v = "$n0 ($r)"
                candidates.add(v)
                dotTitle(v)?.let { candidates.add(it) }
            }
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
        // ★ Java 游戏：JAR 内嵌图标/封面直接提取（J2ME 游戏自带 icon，
        //   上游模拟器均直接读 JAR 内资源）—— 无需联网。
        if (game.platform == GamePlatform.JAVA) {
            extractJarCover(context, game)?.let { return it }
            // JAR 无内嵌图 → 继续走后续缩略图库路径（用游戏名）
        }
        if (systemDirs.isEmpty()) return null
        // ★ SAF URI 双重编码修复：game.romPath 在 SAF 导入时是 content:// URI，
        //   最后一段是 URL 编码的 documentId（含 %20 %28 %5B 等编码字符）。
        //   旧实现对它直接 substringAfterLast + substringBeforeLast('.'),
        //   再经 urlSeg 二次编码 → 永远 404。
        //   game.title 在导入时由 queryDisplayName 解码后写入，是干净的可读名。
        // ★★★ 封面搜索名（平台感知 + 中文翻译 + 多名序列）★★★
        //   1. 街机：用**扫描文件夹的实际 zip 名**（SAF URI 解码后的真实文件名）
        //      经 [ArcadeCoverNames] 转换为 libretro 收录的英文标题，驱动名主干
        //      作为兜底搜索名 —— "读取实际 zip 名"的需求；
        //   2. DC：flycast boxart 上游约定按内容文件名取图，同样用实际名；
        //   3. 全平台：标题含中文时先经 CnGameNameMapper 翻译成英文
        //      （"魂斗罗"→"Contra"）再走模糊匹配 —— 中文模糊转英文下载。
        val searchNames = searchNamesFor(game)
        if (searchNames.isEmpty()) return null
        val candidates = searchNames.flatMap { nameCandidates(it) }.distinct()
        if (candidates.isEmpty()) return null

        val dest = File(coversDir(context), "${game.id}.png")
        // ★★★ 封面下载主流程（回归 3.8 分支基线结构）★★★
        //   用户实测："封面获取逻辑被完全破坏，所有封面都无法获取"。
        //   3.8 备份分支（用户确认可用）的顺序是：每个系统目录先逐一尝试
        //   盒装封面（每个候选名一个 URL），全部 404 后再整批尝试截图兜底，
        //   最后才走索引+模糊匹配。主通道 URL 与 3.8 完全一致（libretro 直连
        //   原名），jsDelivr 镜像仅作为**同名第二通道**（直连失败时兜底），
        //   不改变 3.8 的尝试顺序与命中优先级。
        for (sys in systemDirs) {
            // 1) 盒装封面（Named_Boxarts）—— 多通道：libretro 直连 → gh 代理链 → jsDelivr 链
            for (name in candidates) {
                for ((ch, url) in coverImageUrls(sys, "Named_Boxarts", name)) {
                    if (downloadCached(url, dest, cache, ch)) {
                        syncDcBoxart(context, game, dest)
                        return dest
                    }
                }
            }
            // 2) 游戏截图兜底（Named_Snaps —— 有图总比占位色块好）
            for (name in candidates) {
                for ((ch, url) in coverImageUrls(sys, "Named_Snaps", name)) {
                    if (downloadCached(url, dest, cache, ch)) {
                        syncDcBoxart(context, game, dest)
                        return dest
                    }
                }
            }
        }
        // ★★ 封面模糊匹配（第二轮）：所有精确候选都 404 后，用系统索引
        //   + 本地模糊匹配找到最接近的官方 No-Intro 名再试。每个系统
        //   只拉一次索引（磁盘缓存 30 天），覆盖"标题改写/分隔符差异/
        //   副标题缺失/序号写法不同"等精确候选永远覆盖不到的命名差异
        //   （"模糊读取 ROM 名而不是绝对名字"的需求）。
        for (sys in systemDirs) {
            val index = fetchSystemIndex(context, sys)
            if (index == null) {
                Log.w(TAG, "cover index unavailable: $sys")
                lastBatchStats.indexFailed++
                continue
            }
            // 每个搜索名（街机 = 英文标题 + 驱动名主干）各自模糊一遍
            val fuzzy = searchNames.flatMap { searchName ->
                fuzzyMatch(searchName, index)
            }.distinct()
            Log.i(TAG, "fuzzy hits for '$searchNames' ($sys): $fuzzy")
            for (name in fuzzy) {
                // 模糊命中名与已试过的精确候选重叠时跳过（避免重复 404）
                if (candidates.any { it.equals(name, ignoreCase = true) }) continue
                for ((ch, url) in coverImageUrls(sys, "Named_Boxarts", name)) {
                    if (downloadImage(url, dest, ch)) {
                        Log.i(TAG, "fuzzy cover hit: '$searchNames' -> '$name' ($sys)")
                        syncDcBoxart(context, game, dest)
                        return dest
                    }
                }
            }
            lastBatchStats.notFound++
        }
        return null
    }

    /**
     * ★ 封面搜索名（平台感知 + 中文翻译）：
     *   - ARCADE → 扫描文件夹的实际 zip/7z 文件名（libretro 街机目录按
     *     完整游戏标题组织；显示名/中文名搜不到）——"读取实际 zip 名"的需求；
     *     命中 [ArcadeCoverNames] 后转换为 libretro 收录的英文标题；
     *   - DC → 实际 ROM 文件名（flycast boxart 上游按内容文件名约定）；
     *   - 其它 → game.title；含 CJK 时先过 CnGameNameMapper 中→英翻译。
     */
    fun coverSearchName(game: GameEntry): String? = searchNamesFor(game).firstOrNull()

    /**
     * ★★ 完整搜索名序列（按优先级，第一个为首选）。
     *
     * ★ 街机/DC 真实文件名修复（SAF URI 解码）：romPath 在 SAF 导入时是
     *   content:// URI（documentId URL 编码，"primary%3AROMs%2Farcade%2F
     *   kof97.zip"），旧实现 substringAfterLast('/') 拿到的是**整段编码
     *   documentId** → "街机不用真实文件名"的直接根因。现在统一经
     *   [romFileStem] 解码：URL 解码 → 取 documentId 冒号后路径 → 取文件段。
     *
     * 街机返回 [英文标题（映射命中）, 驱动名主干] 两个搜索名 —— 先试
     * libretro 收录标题（精确命中），全部 404 再用驱动名走模糊匹配。
     */
    fun searchNamesFor(game: GameEntry): List<String> {
        val out = LinkedHashMap<String, Boolean>()
        when (game.platform) {
            GamePlatform.ARCADE, GamePlatform.DC -> {
                val stem = romFileStem(game)
                val base = stem
                    ?: game.title.takeIf { it.isNotBlank() }
                    ?: return emptyList()
                if (game.platform == GamePlatform.ARCADE) {
                    // 1) 驱动名 → libretro 英文标题（"kof97" → "The King of Fighters '97"）
                    ArcadeCoverNames.lookup(base)?.let { out[it] = true }
                    // 2) 驱动名主干原样兜底（模糊匹配路径）
                    out[base] = true
                    // 3) 标题若为中文（自定义名）也尝试翻译
                    game.title.takeIf { !it.equals(base, ignoreCase = true) }
                        ?.let { translateIfCjk(it)?.let { t -> out[t] = true } }
                } else {
                    out[translateIfCjk(base)] = true
                    // ★ 数字后缀变体：DC 中文名常带续作序号（"疾风忍者传2" →
                    //   "Naruto 2"），翻译键未收录序号时补一个带数字的搜索名。
                    addDigitVariant(out, base, translateIfCjk(base))
                }
            }
            else -> {
                val base = game.title.takeIf { it.isNotBlank() }
                    ?: romFileStem(game)?.takeIf { it.isNotBlank() }
                    ?: return emptyList()
                val translated = translateIfCjk(base)
                out[translated] = true
                // ★★ 数字后缀变体（本轮命中率根治）：★★
                //   CnGameNameMapper 的键多是不带序号的系列正名（"重装机兵" →
                //   "Metal Max"）。中文 ROM 名通常带续作序号（"重装机兵4F3.03
                //   完结版"）—— 前缀命中只得到 "Metal Max"，与索引里的
                //   "Metal Max 4 - Gekkou no Diva (Japan)" 对不上（相似度低于
                //   阈值）。从原名提取数字后缀补一个 "翻译名 + 序号" 的搜索名
                //   （"Metal Max 4"）→ 模糊命中（词序列包含加分 0.95）→ 封面
                //   下载成功。序号≤2位，避免 "F3.03" 这类版本号被误当序号。
                addDigitVariant(out, base, translated)
            }
        }
        return out.keys.filter { it.isNotBlank() }
    }

    /**
     * 从原始中文名提取首位数字（1-2 位），若翻译名不含该数字则补一个
     * "翻译名 + 数字" 搜索名（见 [searchNamesFor] 注释）。
     */
    private fun addDigitVariant(out: LinkedHashMap<String, Boolean>, raw: String, translated: String) {
        if (translated == raw) return  // 未发生翻译（原名即英文）→ 无需补
        val m = Regex("(?<![0-9.])([0-9]{1,2})(?![0-9.])").find(raw) ?: return
        val digit = m.groupValues[1]
        if (translated.contains(Regex("\\b$digit\\b"))) return  // 翻译名已含序号
        val variant = "$translated $digit"
        if (variant !in out) out[variant] = true
    }

    /**
     * 从 romPath 提取"实际 ROM 文件名主干"（无扩展名）。
     *
     *   content://.../document/primary%3AROMs%2Farcade%2Fkof97.zip
     *     → URL 解码 "primary:ROMs/arcade/kof97.zip"
     *     → 去设备前缀/目录 → "kof97"
     *   /sdcard/ROMs/kof97.zip → "kof97"
     *
     * 纯数字段（MediaProvider 的 /file/<id> 形式 URI 无文件名）返回 null，
     * 由调用方回退到 game.title。
     */
    private fun romFileStem(game: GameEntry): String? {
        val raw = game.romPath?.takeIf { it.isNotBlank() } ?: return null
        val fileName = if (raw.startsWith("content://")) {
            val lastSeg = raw.substringAfterLast('/')
            val decoded = try {
                java.net.URLDecoder.decode(lastSeg, "UTF-8")
            } catch (_: Throwable) {
                lastSeg
            }
            // documentId 形如 "primary:ROMs/arcade/kof97.zip"（冒号后为路径）
            decoded.substringAfterLast(':').substringAfterLast('/')
        } else {
            raw.substringAfterLast('/').substringAfterLast('\\')
        }
        val stem = fileName.substringBeforeLast('.').trim()
        if (stem.isEmpty()) return null
        // 纯数字（MediaStore 数字 id）不是可用名
        if (stem.all { it.isDigit() }) return null
        return stem
    }

    /** 含 CJK 的名字先过中文→英文映射；未命中原样返回（继续走模糊匹配）。 */
    private fun translateIfCjk(name: String): String {
        if (!CnGameNameMapper.containsCjk(name)) return name
        CnGameNameMapper.resolve(name)?.let {
            Log.i(TAG, "cn name mapped: '$name' -> '$it'")
            return it
        }
        return name
    }

    /**
     * ★ Java (J2ME) 游戏内嵌封面提取 —— 直接从 JAR 取图，无需联网。
     *   来源优先级（与上游 J2ME 模拟器一致）：
     *     1. META-INF/MANIFEST.MF 的 MIDlet-Icon 字段；
     *     2. META-INF/MANIFEST.MF 的 MIDlet-n 三元组中第 2 个字段（图标路径）；
     *     3. 同目录 .jad 描述文件的 MIDlet-Icon（若与 jar 同在）；
     *     4. JAR 根目录下最大的 .png/.jpg（多数 J2ME 游戏首页图标即封面）。
     *   提取失败返回 null（调用方继续走缩略图库路径）。
     */
    fun extractJarCover(context: Context, game: GameEntry): File? {
        val romPath = game.romPath ?: return null
        val dest = File(coversDir(context), "${game.id}.png")
        if (dest.exists() && dest.length() > 0) return dest
        try {
            val file = if (romPath.startsWith("content://")) {
                // SAF 导入的 jar：拷到缓存再解（JavaGameStore 已物化到本地，
                // romPath 一般是真实路径；此处仅兑底）
                null
            } else {
                java.io.File(romPath)
            }
            if (file == null || !file.exists()) return null
            var iconPath: String? = null
            var largest: Pair<Long, java.util.zip.ZipEntry>? = null
            java.util.zip.ZipFile(file).use { zf ->
                // 1/2) MANIFEST 声明的图标
                val mf = zf.getEntry("META-INF/MANIFEST.MF")
                if (mf != null) {
                    val text = zf.getInputStream(mf).bufferedReader().readText()
                    // MIDlet-Icon: icon.png
                    Regex("(?im)^MIDlet-Icon\\s*:\\ *(.+)$").find(text)?.let {
                        iconPath = it.groupValues[1].trim()
                    }
                    if (iconPath.isNullOrBlank()) {
                        // MIDlet-1: 名称, 图标, 类（第 2 字段）
                        Regex("(?im)^MIDlet-1\\s*:\\ *([^,]*),\\s*([^,]*),").find(text)?.let {
                            val p = it.groupValues[2].trim()
                            if (p.isNotBlank()) iconPath = p
                        }
                    }
                }
                iconPath?.takeIf { it.isNotBlank() }?.let { declared ->
                    // 大小写不敏感 + 去前缀 '/' 查找
                    val want = declared.removePrefix("/").lowercase()
                    val entries = zf.entries()
                    while (entries.hasMoreElements()) {
                        val e = entries.nextElement()
                        if (!e.isDirectory && e.name.lowercase() == want) {
                            saveEntryIfImage(zf, e, dest)?.let { return it }
                        }
                    }
                }
                // 4) 根目录最大图片兑底
                val entries = zf.entries()
                while (entries.hasMoreElements()) {
                    val e = entries.nextElement()
                    if (e.isDirectory) continue
                    val n = e.name
                    if ('/' in n) continue  // 只看根目录
                    val lower = n.lowercase()
                    if (lower.endsWith(".png") || lower.endsWith(".jpg") || lower.endsWith(".jpeg")) {
                        val cur = largest
                        if (cur == null || e.size > cur.first) largest = e.size to e
                    }
                }
                largest?.second?.let { e ->
                    saveEntryIfImage(zf, e, dest)?.let { return it }
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "jar cover extract failed: ${t.message}")
        }
        return null
    }

    /** 解压 JAR 条目到 dest（校验图片魔数）；非图片/损坏返回 null。 */
    private fun saveEntryIfImage(
        zf: java.util.zip.ZipFile,
        e: java.util.zip.ZipEntry,
        dest: File
    ): File? {
        try {
            val data = zf.getInputStream(e).use { it.readBytes() }
            if (data.size > MAX_IMAGE_BYTES || data.size < 32) return null
            if (!looksLikeImage(data.copyOfRange(0, 8.coerceAtMost(data.size)))) return null
            dest.parentFile?.mkdirs()
            dest.writeBytes(data)
            Log.i(TAG, "jar cover extracted: ${e.name} (${data.size}B)")
            return dest
        } catch (_: Throwable) {
            return null
        }
    }

    /**
     * ★ DC 专用：下载成功的封面同步落一份到 flycast 上游约定的
     *   boxart 目录（<filesDir>/dc/boxart/<ROM 文件名>.png）。flycast 核心按
     *   "Home Folder/boxart/<内容文件名>" 查找封面（lib 内字符串实测：
     *   "If empty, Flycast will use the default Home Folder/boxart for
     *   downloads and generated art"），NesStation 的 flycast home = dc/。
     */
    private fun syncDcBoxart(context: Context, game: GameEntry, cover: File) {
        if (game.platform != GamePlatform.DC) return
        try {
            val romFile = game.romPath?.substringAfterLast('/') ?: return
            val stem = romFile.substringBeforeLast('.').takeIf { it.isNotBlank() } ?: return
            val dir = File(File(context.filesDir, "dc"), "boxart")
            dir.mkdirs()
            java.io.File(dir, "$stem.png").takeIf { !it.exists() }?.let {
                it.writeBytes(cover.readBytes())
            }
        } catch (t: Throwable) {
            Log.w(TAG, "dc boxart sync failed: ${t.message}")
        }
    }

    /**
     * ★★★ 封面候选列表（让玩家选择）★★★
     * 对单游戏下载最多 [max] 张候选封面（精确候选 + 模糊命中名），
     * 每张独立落盘 covers/<gameId>_cand<i>.png，配合库页面长按菜单的
     * "选择封面"弹窗展示 —— 玩家点选哪张就用哪张（[pickCandidate]）。
     * @return (文件, 候选名) 列表；全部失败返回空表。
     */
    fun fetchCandidates(
        context: Context,
        game: GameEntry,
        max: Int = 8,
        onProgress: ((Int, Int) -> Unit)? = null
    ): List<Pair<File, String>> {
        // ★ 整体防御：任何一步失败都返回空列表而非抛出（调用方为 UI 协程，
        //   未捕获异常会直接闪退 —— "选择封面按钮点击就闪退"的根治措施之一）
        return try {
            fetchCandidatesInner(context, game, max, onProgress)
        } catch (t: Throwable) {
            Log.w(TAG, "fetchCandidates failed: ${t.message}")
            emptyList()
        }
    }

    private fun fetchCandidatesInner(
        context: Context,
        game: GameEntry,
        max: Int,
        onProgress: ((Int, Int) -> Unit)?
    ): List<Pair<File, String>> {
        // ★ 多搜索名序列（街机 = 英文标题 + 驱动名主干；中文已翻译）
        val searchNames = searchNamesFor(game)
        if (searchNames.isEmpty()) return emptyList()
        val romFile = game.romPath?.substringAfterLast('/') ?: ""
        val systemDirs = libretroSystemDir(game.platform, romFile)
        if (systemDirs.isEmpty()) return emptyList()
        val results = LinkedHashMap<String, File>() // name -> file（去重）
        val out = ArrayList<Pair<File, String>>()
        try {
            val exact = searchNames.flatMap { nameCandidates(it) }.distinct()
            // 组装完整候选名序列：精确优先，然后模糊命中（每个搜索名各模糊一遍）
            val names = ArrayList(exact)
            for (sys in systemDirs) {
                val index = fetchSystemIndex(context, sys) ?: continue
                for (searchName in searchNames) {
                    for (n in fuzzyMatch(searchName, index)) {
                        if (names.none { it.equals(n, ignoreCase = true) }) names.add(n)
                        if (names.size >= max * 3) break  // 候选池上限（避免拉全表）
                    }
                    if (names.size >= max * 3) break
                }
                if (names.size >= max * 3) break
            }
            for (sys in systemDirs) {
                for (name in names) {
                    if (out.size >= max) break
                    if (results.containsKey(name.lowercase())) continue
                    val i = out.size
                    val f = File(coversDir(context), "${game.id}_cand$i.png")
                    var hit = false
                    for ((ch, url) in coverImageUrls(sys, "Named_Boxarts", name)) {
                        if (downloadImage(url, f, ch)) { hit = true; break }
                    }
                    if (hit) {
                        results[name.lowercase()] = f
                        out.add(f to name)
                        onProgress?.invoke(out.size, max)
                        try { Thread.sleep(FETCH_INTERVAL_MS) } catch (_: InterruptedException) {}
                    }
                }
                if (out.size >= max) break
            }
        } catch (t: Throwable) {
            Log.w(TAG, "fetchCandidates failed: ${t.message}")
        }
        return out
    }

    /**
     * 玩家选定候选封面 → 复制为正式封面并写回 RomStore。
     * @return true 写回成功（UI 刷新列表即可看到新封面）。
     */
    fun pickCandidate(context: Context, game: GameEntry, candidate: File): Boolean {
        return try {
            val dest = File(coversDir(context), "${game.id}.png")
            dest.parentFile?.mkdirs()
            java.io.FileInputStream(candidate).use { input ->
                dest.outputStream().use { input.copyTo(it) }
            }
            RomStore.setCoverPath(context, game.id, dest.absolutePath)
            // DC：玩家选定后同样同步 flycast boxart 目录
            syncDcBoxart(context, game, dest)
            true
        } catch (t: Throwable) {
            Log.w(TAG, "pickCandidate failed: ${t.message}")
            false
        }
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
     *
     * ★★ 索引可靠性加固（本轮）★★
     *   中文 ROM 的精确候选几乎必然 404，命中全靠索引 → 模糊匹配，
     *   索引拉取失败 = 该游戏封面彻底没戏。加固：
     *   1) 直连失败自动重试一次（移动网络抖动常见）；
     *   2) 仍失败 → GitHub 官方镜像 git/trees API 兜底（JSON，一系统
     *      一请求，同样磁盘缓存 30 天）。
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
        // ★★ 通道 0：assets 内置索引（本轮新增 —— 离线模糊匹配兑底）。
        //   各主平台封面名索引已随 APK 打包（assets/cover_index/<safe>.txt，
        //   生成于 2026-10，共 14 平台 ≈ 2.9MB）。命中后种到磁盘缓存（30 天
        //   TTL 从种子时刻起算，过期后自动走网络刷新）。彻底解决“索引拉不
        //   下来 → 中文/异名 ROM 模糊匹配永久失效 → 封面全部跳过”。
        val bundled = loadBundledIndex(context, system)
        if (bundled != null) {
            indexMemory[system] = bundled
            try { cacheFile.writeText(bundled.joinToString("\n")) } catch (_: Throwable) {}
            Log.i(TAG, "cover index loaded from bundled assets: $system (${bundled.size} names)")
            return bundled
        }
        // 通道 1：libretro 直连 autoindex（失败重试一次）
        val primary = fetchAutoindexIndex(system)
        if (primary != null) {
            indexMemory[system] = primary
            try { cacheFile.writeText(primary.joinToString("\n")) } catch (_: Throwable) {}
            return primary
        }
        // 通道 2：GitHub 镜像 trees API（api.github.com + gh 代理链）
        Log.w(TAG, "cover index primary failed, trying GitHub mirror: $system")
        val mirror = fetchGithubTreeIndex(system)
        if (mirror != null) {
            indexMemory[system] = mirror
            try { cacheFile.writeText(mirror.joinToString("\n")) } catch (_: Throwable) {}
            Log.i(TAG, "cover index loaded via GitHub: $system (${mirror.size} names)")
            return mirror
        }
        Log.w(TAG, "cover index unavailable from all sources: $system")
        return null
    }

    /** assets 内置索引：assets/cover_index/<safeName>.txt（每行一个官方名）。 */
    private fun loadBundledIndex(context: Context, system: String): List<String>? {
        val safeName = system.replace(Regex("[^A-Za-z0-9]+"), "_").trim('_')
        return try {
            context.assets.open("cover_index/$safeName.txt").bufferedReader().use { r ->
                r.readLines().filter { it.isNotBlank() }
            }.takeIf { it.isNotEmpty() }
        } catch (_: Throwable) {
            null
        }
    }

    /** libretro 站点 autoindex 拉取（含一次重试；通道健康记忆参与）。 */
    private fun fetchAutoindexIndex(system: String): List<String>? {
        for (attempt in 1..2) {
            if (!channelAlive("libretro")) return null  // 本会话已判死
            var conn: HttpURLConnection? = null
            try {
                val url = "$BASE/${urlSeg(system)}/Named_Boxarts/"
                val c = URL(url).openConnection() as HttpURLConnection
                conn = c
                c.connectTimeout = 20000
                c.readTimeout = 90000
                c.instanceFollowRedirects = true
                c.setRequestProperty("User-Agent", USER_AGENT)
                val code = try {
                    c.responseCode
                } catch (t: Throwable) {
                    noteChannelConnFailed("libretro")
                    throw t
                }
                noteChannelAlive("libretro")
                if (code != HttpURLConnection.HTTP_OK) {
                    Log.w(TAG, "cover index HTTP $code (attempt $attempt): $system")
                    continue
                }
                val html = c.inputStream.use { input ->
                    val buf = java.io.ByteArrayOutputStream()
                    val buf8k = ByteArray(8192)
                    var total = 0
                    while (true) {
                        val n = input.read(buf8k)
                        if (n < 0) break
                        total += n
                        if (total > 12 * 1024 * 1024) return null
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
                if (names.isNotEmpty()) {
                    val list = names.toList()
                    Log.i(TAG, "cover index loaded: $system (${list.size} names)")
                    return list
                }
            } catch (t: Throwable) {
                Log.w(TAG, "cover index fetch failed (attempt $attempt): $system (${t.message})")
            } finally {
                try { conn?.disconnect() } catch (_: Throwable) {}
            }
        }
        return null
    }

    /** GitHub 官方镜像兜底：git/trees?recursive=1 一请求拿全系统文件树。
     *
     * ★★ 通道加固（本轮）：api.github.com 之后按序追加 3 个 gh 代理
     *   （gh-proxy.com / ghfast.top / ghproxy.net —— 同样能转发 api.github.com
     *   的 JSON 响应，实测 200）。api.github.com 在大陆网络经常不可达/被
     *   限流，代理通道命中后索引照常落盘缓存（30 天），后续请求不再依赖
     *   任何单一通道。
     */
    private fun fetchGithubTreeIndex(system: String): List<String>? {
        val repo = system.replace(' ', '_')
        val api = "https://api.github.com/repos/libretro-thumbnails/$repo/git/trees/master?recursive=1"
        val urls = buildList {
            add("github:api" to api)
            for (p in GH_PROXIES) {
                add("ghproxy:${p.removePrefix("https://")}" to "$p$api")
            }
        }
        for ((ch, url) in urls) {
            if (!channelAlive(ch)) continue  // 本会话已判死的通道直接跳过
            val result = tryFetchGithubTree(url, ch)
            if (result != null) return result
        }
        return null
    }

    private fun tryFetchGithubTree(url: String, channel: String? = null): List<String>? {
        var conn: HttpURLConnection? = null
        return try {
            val c = URL(url).openConnection() as HttpURLConnection
            conn = c
            c.connectTimeout = 20000
            c.readTimeout = 90000
            c.instanceFollowRedirects = true
            c.setRequestProperty("User-Agent", USER_AGENT)
            c.setRequestProperty("Accept", "application/vnd.github+json")
            val code = try {
                c.responseCode
            } catch (t: Throwable) {
                if (channel != null) noteChannelConnFailed(channel)
                throw t
            }
            if (channel != null) noteChannelAlive(channel)
            if (code != HttpURLConnection.HTTP_OK) {
                Log.w(TAG, "GitHub trees HTTP $code: $url")
                return null
            }
            val json = c.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
            // 轻量解析 "path":"Named_Boxarts/<name>.png"（避免引 JSON 依赖）
            val names = LinkedHashSet<String>()
            for (m in Regex("\"path\"\\s*:\\s*\"Named_Boxarts/([^\"]+)\\.png\"").findAll(json)) {
                val raw = m.groupValues[1]
                // JSON 字符串转义还原（文件名常见 \" \\ \uXXXX 极少；常规用例无转义）
                val name = raw.replace("\\\"", "\"").replace("\\\\", "\\")
                if (name.isNotBlank()) names.add(name)
            }
            if (names.isEmpty()) null else names.toList()
        } catch (t: Throwable) {
            Log.w(TAG, "GitHub trees fetch failed: $url (${t.message})")
            null
        } finally {
            try { conn?.disconnect() } catch (_: Throwable) {}
        }
    }

    /**
     * 本地模糊匹配：把 ROM 名与官方名列表比对，返回最接近的
     * 前几个官方名（按相似度降序，低于阈值的不返回）。
     *
     * ★★ 命中率根治升级（TOSEC 兼容 + 双键匹配 + 包含加分）★★
     *   实测 thumbnails.libretro.com 各系统目录命名**不统一**：
     *     - SFC/GB/MD/NDS/PSX/3DS 等 → No-Intro（"Contra III - The Alien Wars (USA)"）；
     *     - NES → **TOSEC**（"Contra (1988-02)(Konami)(US)[!]"）—— 旧实现的
     *       候选名（"Contra (USA)"）与 TOSEC 名完全对不上，且旧相似度对
     *       "contra" vs "contra 1988 02 konami us" 打分 ~0.2 → 永远低于
     *       阈值 → "魂斗罗三个字都下载不到封面"的直接根因。
     *
     *   每个候选官方名生成两个归一化键，取两键最高分：
     *     key_full = 全名归一化（去区域/版本括号后保序）；
     *     key_bare = **剥掉全部 (...)与[...] 段后的裸标题**（"Contra
     *       (1988-02)(Konami)(US)" → "contra"；No-Intro 的 "(USA)" 类
     *       区域段同样被剥 —— 与无区域 ROM 名的匹配完全对齐）。
     *
     *   相似度 = 0.5·Jaccard + 0.35·编辑距离 + 0.15·token 包含率，另有：
     *     - 完全相等直接 1.0；
     *     - **包含加分**：搜索名整体作为连续词序列出现在候选中（或反向）
     *       —— "the king of fighters 97" ⊆ "king of fighters 97 ngm 232
     *       ngh 232" 类街机序号后缀差异 → 0.95。
     */
    fun fuzzyMatch(rawName: String, officialNames: List<String>): List<String> {
        val target = normalizeForFuzzy(rawName)
        if (target.isBlank()) return emptyList()
        val targetTokens = target.split(' ').filter { it.isNotBlank() }.toSet()
        data class Scored(val name: String, val score: Double)
        val scored = ArrayList<Scored>(officialNames.size)
        for (official in officialNames) {
            val candFull = normalizeForFuzzy(official)
            if (candFull.isBlank()) continue
            val candBare = bareTitleForFuzzy(official)
            fun scoreAgainst(cand: String): Double {
                if (cand.isBlank()) return 0.0
                if (cand == target) return 1.0
                val candTokens = cand.split(' ').filter { it.isNotBlank() }.toSet()
                val inter = targetTokens.intersect(candTokens).size.toDouble()
                val union = targetTokens.union(candTokens).size.toDouble()
                val jaccard = if (union > 0) inter / union else 0.0
                val lev = levenshteinRatio(target, cand)
                val containRate = if (targetTokens.isEmpty()) 0.0
                else inter / targetTokens.size
                var s = 0.5 * jaccard + 0.35 * lev + 0.15 * containRate
                // 前缀包含加分（短候选名长目标）
                if (target.length >= 4 && (cand.startsWith(target) || target.startsWith(cand))) s += 0.08
                // ★ 整体词序列包含：目标所有词按原顺序连续出现在候选中
                //   （或候选所有词连续出现在目标中）→ 序号/区域后缀差异全兼容
                if (targetTokens.isNotEmpty() && candTokens.isNotEmpty()) {
                    val t = target.split(' ').filter { it.isNotBlank() }
                    val c = cand.split(' ').filter { it.isNotBlank() }
                    val contains = if (t.size <= c.size) containsRun(c, t) else containsRun(t, c)
                    if (contains) s = maxOf(s, 0.95)
                }
                return s
            }
            var score = maxOf(scoreAgainst(candFull), scoreAgainst(candBare))
            if (score > 1.0) score = 1.0
            if (score >= FUZZY_MIN_SCORE) scored.add(Scored(official, score))
        }
        // ★ 排序细化：同分时优先规范名 ——
        //   1) 含 hack/bootleg/no title/翻译补丁 等标记的条目大幅降权
        //      （"Super Mario Bros. (19xx)(-)[p][no title...]" 这类 dump
        //      杂项不应排在标准版前面）；
        //   2) 同分取更短名（标准版常比带序号/区域后缀的短）。
        fun markerPenalty(name: String): Int {
            val n = name.lowercase()
            var p = 0
            for (marker in listOf(
                "no title", "hack", "bootleg", "aftermarket", "pirate",
                "homebrew", "prototype", "proto ", "unl", "[b]", "[p]", "[h]", "[t]",
                "[tr ", "(beta)", "(sample)", "(promo)", "(unknown)"
            )) {
                if (marker in n) p += 2
            }
            if ("tr " in n || "tr]" in n) p += 1
            return p
        }
        return scored.sortedWith(
            compareByDescending<Scored> { it.score }
                .thenBy { markerPenalty(it.name) }
                .thenBy { it.name.length }
        )
            .take(MAX_FUZZY_TRIES)
            .map { it.name }
    }

    /** 词序列包含判定：small 的全部词是否按原顺序连续出现在 big 中。 */
    private fun containsRun(big: List<String>, small: List<String>): Boolean {
        if (small.isEmpty() || big.size < small.size) return false
        outer@ for (start in 0..big.size - small.size) {
            for (j in small.indices) {
                if (big[start + j] != small[j]) continue@outer
            }
            return true
        }
        return false
    }

    /**
     * 裸标题键：剥掉全部 (...) 与 [...] 段后归一化。
     * "Contra (1988-02)(Konami)(US)[!]" → "contra"；
     * "Super Mario Bros. (USA)" → "super mario bros"。
     */
    private fun bareTitleForFuzzy(name: String): String =
        normalizeForFuzzy(
            name.replace(Regex("\\s*\\([^)]*\\)"), " ")
                .replace(Regex("\\s*\\[[^]]*]"), " ")
        )

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
        // ★★ "全部跳过"根治（用户实测：所有游戏都提示未下载到封面）★★
        //   旧过滤只看 coverPath 字符串是否为空 —— 重新安装 APK / 清理缓存 /
        //   存储路径变化后，库里残留的 coverPath 指向的文件早已不存在，
        //   这些游戏全部被当成"已有封面"跳过 → 一次下载都不会发起，
        //   提示永远是"未下载到新封面（尝试 0）"。现在把"coverPath 指向的
        //   文件已丢失"视为缺封面，重新纳入抓取。
        fun coverFileUsable(g: GameEntry): Boolean {
            val p = g.coverPath ?: return false
            if (p.isBlank()) return false
            val f = File(p)
            return f.exists() && f.length() > 0
        }
        val pending = games.filter { g ->
            if (!onlyMissing) {
                true
            } else {
                if (!g.customIconPath.isNullOrBlank()) {
                    false                      // 自定义图标优先，无需联网封面
                } else {
                    !coverFileUsable(g)        // 无封面 或 封面文件已丢失
                }
            }
        }
        if (pending.isEmpty()) {
            // 统计归零并标注全部已有封面（UI 提示更准确）
            lastBatchStats = BatchStats(attempted = 0, succeeded = 0)
            return 0
        }
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
        val stats = BatchStats(attempted = batch.size)
        lastBatchStats = stats
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
        stats.succeeded = fetched
        Log.i(TAG, "Cover fetch batch done: $fetched/${batch.size} succeeded; " +
                "indexFailed=${stats.indexFailed}, notFound=${stats.notFound}")
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
        cache: MutableMap<String, File?>?,
        channel: String? = null
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
        val ok = downloadImage(url, dest, channel)
        cache?.put(url, if (ok) dest else null)
        return ok
    }

    /**
     * 下载并校验图片魔数；成功落盘返回 true。
     *
     * ★ 通道健康记忆（本轮）：调用方传入 [channel]（通道名，null = 未知）。
     * - 任何 HTTP 响应码（含 404）都证明通道活着 → 清零该通道失败计数；
     * - 连接级异常（DNS 解析失败/连接被拒/超时）→ 计数 +1，连续 3 次
     *   后本会话内该通道被 [coverImageUrlsChanneled] 消费方跳过。
     * 404 不计入失败 —— 它只说明这个候选名不存在，与通道健康无关。
     */
    private fun downloadImage(urlStr: String, dest: File, channel: String? = null): Boolean {
        // 通道已被本会话标记为死亡 → 直接跳过（不再吃连接超时）
        if (channel != null && !channelAlive(channel)) return false
        var conn: HttpURLConnection? = null
        return try {
            val conn0 = URL(urlStr).openConnection() as HttpURLConnection
            conn = conn0
            conn0.connectTimeout = 8000
            conn0.readTimeout = 12000
            conn0.instanceFollowRedirects = true
            conn0.setRequestProperty("User-Agent", USER_AGENT)
            conn0.setRequestProperty("Accept", "image/png,image/*")
            val code = try {
                conn0.responseCode
            } catch (t: Throwable) {
                // 连接层就挂了（DNS/拒连/超时）→ 记通道失败
                if (channel != null) noteChannelConnFailed(channel)
                throw t
            }
            // 拿到 HTTP 响应码（含 404/5xx）→ 通道活着
            if (channel != null) noteChannelAlive(channel)
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