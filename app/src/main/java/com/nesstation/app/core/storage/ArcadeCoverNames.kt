package com.nesstation.app.core.storage

/**
 * ★ 街机封面搜索名映射器（驱动名 → libretro 英文标题）。
 *
 * 需求来源："街机应该读取扫描文件夹中实际 zip 文件名去下载封面"。
 * libretro 街机目录（FBNeo - Arcade Games / MAME）按**完整游戏标题**组织
 * （"Metal Slug 3.png"、"The King of Fighters '97.png"），zip 驱动名
 * （mslug3/kof97）直接搜不到。本表把常见驱动名映射到 libretro 收录的
 * **裸标题**（无序列号后缀 —— 裸标题文件在目录内普遍存在；带后缀的
 * 变体由 CoverFetcher.fuzzyMatch 的包含加分路径命中）。
 *
 * 未收录的驱动名原样返回（继续走模糊匹配）。
 *
 * ★★★ 本轮结构重写（与 CnGameNameMapper 同根问题）★★★
 *
 * 用户实测：CnGameNameMapper 因 ~540 条 put 全在类初始化器里，首次访问
 * 抛 ExceptionInInitializerError → 之后永久 NoClassDefFoundError →
 * 封面批次"尝试 14，成功 0"。本表是**同样的巨型 <clinit> 写法**（~330 条
 * mapOf 对 + 归一化派生表也在 clinit）—— 只是没有先炸（街机批尚未触发）。
 * 现在同步根治：
 *   1. <clinit> 清空，数据表懒加载；
 *   2. 巨表拆 4 个小构建函数（每个 ~85 条）；
 *   3. runCatching 兜底 + [lastInitError] 诊断（失败 → 空表 + 原名继续，
 *      封面抓取绝不中断）。
 */
object ArcadeCoverNames {

    /** 最近一次构建/查询失败的诊断信息（null = 健康）。 */
    @Volatile
    var lastInitError: String? = null
        private set

    private fun recordError(where: String, t: Throwable) {
        val chain = generateSequence<Throwable>(t) { it.cause }
            .take(4)
            .joinToString(" <- ") { "${it.javaClass.name}: ${it.message}" }
        lastInitError = "$where: $chain"
        try {
            android.util.Log.e("ArcadeCoverNames", "driver table $where failed: $chain", t)
        } catch (_: Throwable) {}
    }

    /** 驱动名 → 英文标题总表（懒加载；失败 → 空表 + 诊断记录，下次访问重试）。 */
    private val DRIVER_TO_EN: Map<String, String> by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        try {
            buildMap<String, String> {
                entriesSnk()
                entriesCapcom()
                entriesClassic()
                entriesModern()
            }
        } catch (t: Throwable) {
            recordError("build", t)
            emptyMap()
        }
    }

    // ------------------------------------------------------------------
    // 分块数据表
    // ------------------------------------------------------------------

    /** SNK 格斗/射击：拳皇/合金弹头/侍魂/饿狼/龙虎/月华 等。 */
    private fun MutableMap<String, String>.entriesSnk() {
        // ===== SNK 拳皇（The King of Fighters）=====
        put("kof94", "The King of Fighters '94")
        put("kof94ea", "The King of Fighters '94")
        put("kof95", "The King of Fighters '95")
        put("kof95a", "The King of Fighters '95")
        put("kof96", "The King of Fighters '96")
        put("kof96ea", "The King of Fighters '96")
        put("kof97", "The King of Fighters '97")
        put("kof97a", "The King of Fighters '97")
        put("kof97h", "The King of Fighters '97")
        put("kof97pls", "The King of Fighters '97")
        put("kof98", "The King of Fighters '98 - Dream Match Never Ends")
        put("kof98a", "The King of Fighters '98 - Dream Match Never Ends")
        put("kof98h", "The King of Fighters '98 - Dream Match Never Ends")
        put("kof98k", "The King of Fighters '98 - Dream Match Never Ends")
        put("kof99", "The King of Fighters '99")
        put("kof99a", "The King of Fighters '99")
        put("kof99e", "The King of Fighters '99")
        put("kof99n", "The King of Fighters '99")
        put("kof99p", "The King of Fighters '99")
        put("kof2000", "The King of Fighters 2000")
        put("kof2000n", "The King of Fighters 2000")
        put("kof2001", "The King of Fighters 2001")
        put("kof2001h", "The King of Fighters 2001")
        put("kof2002", "The King of Fighters 2002")
        put("kof2002b", "The King of Fighters 2002")
        put("kof2002h", "The King of Fighters 2002")
        put("kof2003", "The King of Fighters 2003")
        put("kof2003h", "The King of Fighters 2003")
        put("kof10th", "The King of Fighters 2002 (10th Anniversary)")
        put("kof2k2um", "The King of Fighters 2002 Unlimited Match")
        put("kof98um", "The King of Fighters '98 Ultimate Match")
        put("kofxi", "The King of Fighters XI")
        put("kofxii", "The King of Fighters XII")
        put("kofxiii", "The King of Fighters XIII")
        // ===== SNK 合金弹头（Metal Slug）=====
        put("mslug", "Metal Slug - Super Vehicle-001")
        put("mslug4", "Metal Slug 4")
        put("mslug4p", "Metal Slug 4 Plus")
        put("mslug5", "Metal Slug 5")
        put("mslug5h", "Metal Slug 5")
        put("mslug6", "Metal Slug 6")
        put("mslugx", "Metal Slug X - Super Vehicle-001")
        put("mslug2", "Metal Slug 2 - Super Vehicle-001_II")
        put("mslug3", "Metal Slug 3")
        put("mslug3h", "Metal Slug 3")
        // ===== SNK 其它 =====
        put("samsho", "Samurai Shodown")
        put("samshoh", "Samurai Shodown")
        put("samsho2", "Samurai Shodown II")
        put("samsho2k", "Samurai Shodown II")
        put("samsho3", "Samurai Shodown III")
        put("samsho4", "Samurai Shodown IV")
        put("samsho5", "Samurai Shodown V")
        put("samsho5sp", "Samurai Shodown V Special")
        put("samsho6", "Samurai Shodown VI")
        put("fatfury1", "Fatal Fury - King of Fighters")
        put("fatfury2", "Fatal Fury 2")
        put("fatfury3", "Fatal Fury 3 - Road to the Final Victory")
        put("fatfursp", "Fatal Fury Special")
        put("garou", "Garou - Mark of the Wolves")
        put("garouh", "Garou - Mark of the Wolves")
        put("rbff1", "Real Bout Fatal Fury")
        put("rbff2", "Real Bout Fatal Fury 2 - The Newcomers")
        put("rbffspc", "Real Bout Fatal Fury Special")
        put("kizuna", "Kizuna Encounter - Super Tag Battle")
        put("savagere", "Savage Reign")
        put("wakuwak7", "Waku Waku 7")
        put("breakers", "Breakers")
        put("breakrev", "Breakers Revenge")
        put("neobombe", "Neo Bomberman")
        put("neodrift", "Neo Drift Out - New Technology")
        put("gowcaizr", "Voltage Fighter - Gowcaizer")
        put("wh1", "World Heroes")
        put("wh2", "World Heroes 2")
        put("wh2j", "World Heroes 2 Jet")
        put("whp", "World Heroes Perfect")
        put("lastblad", "The Last Blade")
        put("lastbladh", "The Last Blade")
        put("lastbld2", "The Last Blade 2")
        put("tophuntr", "Top Hunter - Roddy & Cathy")
        put("ncombat", "Neo Turf Masters")
        put("nblktop", "Neo Bomberman")
        put("joyjoy", "Pochi and Nyaa")
        put("zupapa", "Zupapa!")
    }

    /** Capcom（CPS1/2 + 恶魔战士/漫画系）与 CPS3。 */
    private fun MutableMap<String, String>.entriesCapcom() {
        put("sf2", "Street Fighter II - The World Warrior")
        put("sf2ce", "Street Fighter II' - Champion Edition")
        put("sf2hf", "Street Fighter II' - Hyper Fighting")
        put("sf2t", "Super Street Fighter II - The New Challengers")
        put("sf2tb", "Super Street Fighter II - The New Challengers")
        put("sf2tj", "Super Street Fighter II - The New Challengers")
        put("sfz2al", "Street Fighter Zero 2 Alpha")
        put("sfzch", "Street Fighter Zero 3")
        put("sfz3", "Street Fighter Zero 3")
        put("sfz3a", "Street Fighter Zero 3")
        put("sfa", "Street Fighter Alpha - Warriors' Dreams")
        put("sfa2", "Street Fighter Alpha 2")
        put("sfa3", "Street Fighter Alpha 3")
        put("sfiii", "Street Fighter III - New Generation")
        put("sfiii2", "Street Fighter III 2nd Impact - Giant Attack")
        put("sfiii3", "Street Fighter III 3rd Strike - Fight for the Future")
        put("sfiii3n", "Street Fighter III 3rd Strike - Fight for the Future")
        put("sf4", "Street Fighter IV")
        put("xmcota", "X-Men - Children of the Atom")
        put("xmvsf", "X-Men Vs. Street Fighter")
        put("msh", "Marvel Super Heroes Vs. Street Fighter")
        put("mshvsf", "Marvel Super Heroes Vs. Street Fighter")
        put("mvsc", "Marvel Vs. Capcom - Clash of Super Heroes")
        put("mvsc2", "Marvel Vs. Capcom 2 - New Age of Heroes")
        put("mvsc2u", "Marvel Vs. Capcom 2 - New Age of Heroes")
        put("capsnk", "Capcom Vs. SNK - Millennium Fight 2000")
        put("capsnk2", "Capcom Vs. SNK 2 - Mark of the Millennium 2001")
        put("csclub", "Capcom Sports Club")
        put("ddtod", "Dungeons & Dragons - Tower of Doom")
        put("ddshadow", "Dungeons & Dragons - Shadow over Mystara")
        put("armwar", "Armored Warriors")
        put("awbash", "Alien Vs. Predator")
        put("avsp", "Alien Vs. Predator")
        put("dstlk", "Darkstalkers - The Night Warriors")
        put("vhunt2", "Vampire Hunter 2 - Darkstalkers Revenge")
        put("vsav", "Vampire Savior - The Lord of Vampire")
        put("vsav2", "Vampire Savior 2 - The Lord of Vampire")
        put("smbomb", "Super Marvel Vs. Capcom")
        put("maribro", "Marvel Super Heroes")
        put("strider", "Strider Hiryu")
        put("strider2", "Strider 2")
        put("ffight", "Final Fight")
        put("ffightj", "Final Fight")
        put("ffrevia", "Final Fight Revenge")
        put("captcomm", "Captain Commando")
        put("knights", "Knights of the Round")
        put("knightsh", "Knights of the Round")
        put("megaman", "Mega Man - The Power Battle")
        put("megaman2", "Mega Man 2 - The Power Fighters")
        put("megamans", "Mega Man - The Power Battle")
        put("1941", "1941 - Counter Attack")
        put("19xx", "19XX - The War Against Destiny")
        put("1944", "1944 - The Loop Master")
        put("varth", "Varth - Operation Thunderstorm")
        put("cawing", "Carrier Air Wing")
        put("msamurai", "Mighty! Pang")
        put("mpang", "Mighty! Pang")
        put("spf2t", "Super Street Fighter II Turbo")
        put("superman", "Superman - The Man of Steel")
        put("progear", "Pro Gear no Arashi")
        put("dimahoo", "Dimahoo")
        put("tgm2", "Tetris The Grand Master 2 - The Absolute")
        put("giggman", "Gigaman")
        // ===== CPS3 =====
        put("jojo", "JoJo's Venture")
        put("jojor", "JoJo's Bizarre Adventure")
        put("jojoba", "JoJo's Bizarre Adventure")
        put("redearth", "Red Earth")
    }

    /** 经典街机（吃豆/大金刚/小蜜蜂/泡泡龙/雪人兄弟）+ 彩京弹幕 + IGS。 */
    private fun MutableMap<String, String>.entriesClassic() {
        put("pacman", "PuckMan (Japan set 1)")
        put("pacmania", "Pac-Mania")
        put("puckman", "PuckMan (Japan set 1)")
        put("puckmana", "PuckMan (Japan set 1)")
        put("mspacman", "Ms. Pac-Man")
        put("pacmanf", "Pac-Man - Gugeulpan")
        put("dkong", "Donkey Kong")
        put("dkongjr", "Donkey Kong Junior")
        put("dkongx", "Donkey Kong")
        put("frogger", "Frogger")
        put("galaga", "Galaga (Version A)")
        put("galaxian", "Galaxian (Version B)")
        put("digdug", "Dig Dug (Japan)")
        put("digdugat", "Dig Dug")
        put("bublbobl", "Bubble Bobble")
        put("bublboblr", "Bubble Bobble")
        put("bubbob", "Bubble Bobble")
        put("boblbobl", "Bubble Bobble")
        put("snowbroj", "Snow Bros. - Nick & Tom (Japan)")
        put("snowbros", "Snow Bros. - Nick & Tom")
        put("snowbros2", "Snow Bros. 2 - With New Elves")
        put("punipuni", "Puzzle & Action - PuniPuniBalloo")
        put("toki", "Toki - Going Ape Spit")
        put("rampage", "Rampage")
        put("turtles", "Teenage Mutant Ninja Turtles")
        put("tmht", "Teenage Mutant Ninja Turtles")
        put("tmnt2", "Teenage Mutant Ninja Turtles - Turtles in Time")
        put("tmht2", "Teenage Mutant Ninja Turtles - Turtles in Time")
        put("tsuru", "Turtle Ship")
        put("ggpo", "Skull & Crossbones")
        put("crakwn", "Crack Down")
        put("wonder3", "Wonder 3")
        put("virtue", "Virtua Fighter")
        put("vf", "Virtua Fighter")
        put("vf2", "Virtua Fighter 2")
        put("vfkids", "Virtua Fighter Kids")
        put("vrally", "V-Rally Edition 1999")
        put("hook", "Hook")
        put("hsf2", "Hyper Street Fighter 2 - The Anniversary Edition")
        put("news", "Downtown - Nekketsu Monogatari")
        // ===== 彩京/弹幕 =====
        put("dogyuun", "Dogyuun")
        put("batrider", "Armed Police Batrider")
        put("batridera", "Armed Police Batrider")
        put("batsugun", "Batsugun")
        put("batsugna", "Batsugun")
        put("tfvark", "Terra Cresta")
        put("s1945", "Strikers 1945")
        put("s1945a", "Strikers 1945")
        put("s1945ii", "Strikers 1945 II")
        put("s1945iii", "Strikers 1945 III")
        put("s1945j", "Strikers 1945")
        put("dfeveron", "Dangun Feveron")
        put("gunbird", "Gunbird")
        put("gunbird2", "Gunbird 2")
        put("donpachi", "DonPachi")
        put("dodonpachi", "DoDonPachi")
        put("esprade", "ESP Ra.De.")
        put("guwange", "Guwange")
        put("saber", "Sengoku Ace")
        put("senkyu", "Senkyu")
        put("tengai", "Tengai")
        // ===== IGS =====
        put("pgm", "Oriental Legend")
        put("orientl", "Oriental Legend")
        put("orientl1", "Oriental Legend")
        put("kov", "Knights of Valour")
        put("kovplus", "Knights of Valour Plus")
        put("kov2", "Knights of Valour 2")
        put("kov2p", "Knights of Valour 2 Plus")
        put("kovsh", "Knights of Valour - Sevengers")
        put("sango", "Knights of Valour - Sangoku Senki")
        put("sgongi", "Sengoku Giants")
        put("drgw2", "Dragon World II")
        put("drgw3", "Dragon World III")
        put("photoy2k", "Photo Y2K")
        put("picolo", "Photo Adventure")
        put("moonlgh", "Moonlight")
        put("sximo", "Xi You Shi E Zhuan Super")
        put("orlegend", "Oriental Legend")
        put("puzzli2", "Puzzli 2")
    }

    /** 现代 3D 街机 + NeoGeo 小品类。 */
    private fun MutableMap<String, String>.entriesModern() {
        put("tekken", "Tekken")
        put("tekken2", "Tekken 2")
        put("tekken3", "Tekken 3")
        put("tektagt", "Tekken Tag Tournament")
        put("soulclbr", "Soul Calibur")
        put("souledbr", "Soul Edge")
        put("beastor", "Bloody Roar")
        put("beastorz", "Bloody Roar 2")
        put("doapp", "Dead or Alive++")
        put("rvschool", "Rival Schools - Evolution 2")
        put("raizing", "Battle Garegga")
        put("batcir", "Battle Circuit")
        put("garegga", "Battle Garegga")
        put("blastoff", "Blast Off")
        put("trstar", "Transfer Student")
        put("ryujin", "Ryu Jin")
        put("samuraio", "Samurai Aces")
        put("kungfu", "Kung-Fu Master")
        put("sfexp", "Street Fighter - The Battle Edition")
        put("toutra", "Thunder Dragon")
        put("toutrun", "OutRun")
        put("outrun", "OutRun")
        put("afterbn2", "After Burner II")
        put("afterbur", "After Burner")
        put("swpower", "Sonic Wings")
        put("sonicwi", "Sonic Wings")
        put("sonicwi2", "Sonic Wings 2")
        put("sonicwi3", "Sonic Wings 3")
        put("turbo", "Turbo")
        put("gunsmoke", "Gun.Smoke")
        put("vulgus", "Vulgus")
        put("sonson", "SonSon")
        put("exedexes", "Exed Exes")
        put("sengekis", "Sengoku Strikers")
        put("sengoku", "Sengoku Denshou")
        put("sengoku2", "Sengoku 2 - Sengoku Denshou 2")
        put("sengoku3", "Sengoku 3")
        put("shocktrp", "Shock Troopers")
        put("shocktra", "Shock Troopers - 2nd Squad")
        put("lresort", "Last Resort")
        put("smshw2", "Super Sidekicks 2 - The World Championship")
        put("palamed", "Palamedes")
        put("joymach", "Joyful Road")
        put("algiers", "Algiers")
        put("cannonb", "Cannon Ball")
        put("maglord", "Magical Lord")
        put("socbrawl", "Soccer Brawl")
        put("superspy", "The Super Spy")
        put("mutnat", "Mutation Nation")
        put("sengokuh", "Sengoku Denshou")
        put("8ball", "8 Ball Action")
        put("2020bb", "2020 Super Baseball")
        put("3countb", "3 Count Bout")
        put("aof", "Art of Fighting")
        put("aof2", "Art of Fighting 2")
        put("aof3", "Art of Fighting 3 - The Path of the Warrior")
        put("alpham2", "Alpha Mission II")
        put("bstar", "Baseball Stars Professional")
        put("bstars2", "Baseball Stars 2")
        put("cyberlip", "Cyber-Lip")
        put("diggerma", "Digger Man")
        put("doubledr", "Double Dragon")
        put("fightfev", "Fight Fever")
        put("ghostlop", "Ghostlop")
        put("galaxyfg", "Galaxy Fight - Universal Warriors")
        put("ironclad", "Ironclad")
        put("janshin", "Janshin Denshou")
        put("kabukikl", "Kabuki Klash - Far East of Eden")
        put("kingofgl", "King of the Monsters")
        put("kingofg2", "King of the Monsters 2")
        put("league", "League Bowling")
        put("magdrop2", "Magical Drop II")
        put("magdrop3", "Magical Drop III")
        put("mahretsu", "Mahjong Kyoretsuden")
        put("minasan", "Minasan no Okagesama Desu")
        put("moneypee", "Money Puzzle Exchanger")
        put("mslug5p", "Metal Slug 5 Plus")
        put("nam1975", "NAM-1975")
        put("nitd", "Nightmare in the Dark")
        put("panicbom", "Panic Bomber")
        put("pgoal", "Pleasure Goal - 5 on 5 Mini Soccer")
        put("popbounc", "Pop'n Bounce")
        put("pspokes2", "Power Spikes II")
        put("quizdais", "Quiz King of Fighters")
        put("ridhero", "Riding Hero")
        put("roboarmy", "Robo Army")
        put("ssideki", "Super Sidekicks")
        put("ssideki2", "Super Sidekicks 2")
        put("ssideki3", "Super Sidekicks 3 - The Next Glory")
        put("ssideki4", "The Ultimate 11 - SNK Football Championship")
        put("stakwin", "Stakes Winner")
        put("stakwin2", "Stakes Winner 2")
        put("strhoop", "Street Hoop - Street Slam")
        put("twinsym", "Twinkle Star Sprites")
        put("viewpoin", "Viewpoint")
        put("wjudose", "Windjammers")
        put("xenoncr", "Xenon Crisis")
        put("zedblade", "Zed Blade")
        put("zintrick", "ZinTrick")
    }

    /** 归一化驱动名（小写、去扩展名/路径/空白/下划线）。 */
    private fun normalizeDriver(name: String): String =
        name.trim()
            .substringAfterLast('/')
            .substringAfterLast('\\')
            .substringBeforeLast('.')
            .trim()
            .replace('_', ' ')
            .replace(Regex("\\s+"), " ")
            .lowercase()

    /** 归一化键 → 英文标题（懒构建；失败退化空表）。 */
    private val NORMALIZED: Map<String, String> by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        try {
            DRIVER_TO_EN.entries.associate { normalizeDriver(it.key) to it.value }
        } catch (t: Throwable) {
            recordError("normalize", t)
            emptyMap()
        }
    }

    /**
     * 驱动名（zip 主干）→ libretro 英文标题。
     * 未收录返回 null（调用方继续用原名走模糊匹配）。
     * ★ 任何内部异常同样返回 null —— 街机封面抓取绝不因映射器故障中断。
     */
    fun lookup(driverName: String): String? = try {
        lookupInner(driverName)
    } catch (t: Throwable) {
        recordError("lookup", t)
        null
    }

    private fun lookupInner(driverName: String): String? {
        if (NORMALIZED.isEmpty()) return null
        val key = normalizeDriver(driverName)
        NORMALIZED[key]?.let { return it }
        // 兜底：常见修改版后缀（h/p/k/a/b）剥离后再查一次（"kof98h" 类
        // 变体已显式收录；这里覆盖未显式收录的变体）
        val stripped = key.replace(Regex("\\s+(plus|turbo|hack)$"), "").trim()
        if (stripped != key) NORMALIZED[stripped]?.let { return it }
        return null
    }
}
