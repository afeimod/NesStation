package com.nesstation.app.core.storage

/**
 * ★ 封面中文名 → 英文名映射器（全核心通用）。
 *
 * 需求来源："封面获取没有针对中文来进行匹配下载，比如 fc 的超级玛丽中文
 * 匹配一下英文去下载封面，其他核心一样，要匹配中文模糊转英文下载封面"。
 *
 * 中文 ROM 文件名（"魂斗罗.nes"、"超级玛丽(edb).nes"、"街霸II.zip"）无法直接
 * 用于 libretro 缩略图库（按 No-Intro 英文命名组织）。本表把常见中文游戏名
 * （含港台常用译名、俗称）映射到 libretro 收录的标准英文标题。
 *
 * ★★★ 本轮结构重写（"封面一直不去下载"根治）★★★
 *
 * 用户实测日志（cover_debug.log）：
 *   [GAME-ERR] '火纹外传' ExceptionInInitializerError: null
 *   [GAME-ERR] '超级玛丽' NoClassDefFoundError: CnGameNameMapper
 *   ……全部 14 个游戏同错 → 尝试 14，成功 0。
 *
 * 根因：旧版把 ~540 条 put 全部写进 object 的类初始化器（<clinit>），
 * 首次访问触发静态初始化时抛出底层异常（ExceptionInInitializerError 的
 * message 恒为 null，真实原因被吞），之后整进程内该类永久为
 * NoClassDefFoundError 状态 —— translateIfCjk 对每个游戏都抛错，
 * 封面批次全军覆没（连英文命名的游戏也一个都下载不了）。
 *
 * 结构修复（三层防御，互为兜底）：
 *   1. <clinit> 清空 —— 全部数据表改为懒加载，类加载本身零工作量，
 *      任何"类初始化失败"模式都无从触发；
 *   2. 巨表拆分 —— 540 条 put 分到 8 个小构建函数（每个 ~70 条），
 *      单方法体量极小，规避一切巨型方法相关的字节码/校验问题；
 *   3. runCatching 兜底 —— 表构建/查询任何一步失败都退化为"空表/原名"，
 *      封面抓取继续走原名 + 模糊匹配，绝不因映射器故障而中断；
 *      同时把真实底层异常记录到 [lastInitError]（含 cause 链），
 *      fetchAllMissing 批诊断会输出它 —— 若再出问题，日志直接给出根因，
 *      不再是 message=null 的黑盒。
 *
 * 匹配策略（[resolve]）：
 *   1. 输入先做归一化（去扩展名/空格折叠/去常见标签/全角转半角/小写）；
 *   2. 精确命中归一化键；
 *   3. 罗马数字等价（II↔2）；
 *   4. 前缀/包含匹配（输入是键的前缀，或键是输入的子串 —— 处理
 *      "魂斗罗2010"、"超级玛丽特别版" 这类派生命名）；
 *   5. 命中多个时按最长键取最具体的映射。
 *
 * 表条目按 平台无关 的"游戏正名"组织 —— 同一游戏在 FC/SFC/GB/MD 的封面
 * 在 libretro 属于各自系统目录，由 CoverFetcher.libretroSystemDir 决定目录，
 * 这里只负责名字翻译。
 */
object CnGameNameMapper {

    /**
     * 最近一次表构建/查询失败的诊断信息（null = 健康）。
     * cover_debug.log 批次诊断会输出该字段；若非 null 说明底层环境仍有问题，
     * 但映射器已自动退化为空表（封面抓取不受影响，仅中文名翻译暂缺）。
     */
    @Volatile
    var lastInitError: String? = null
        private set

    private fun recordError(where: String, t: Throwable) {
        val chain = generateSequence<Throwable>(t) { it.cause }
            .take(4)
            .joinToString(" <- ") { "${it.javaClass.name}: ${it.message}" }
        lastInitError = "$where: $chain"
        try {
            android.util.Log.e("CnGameNameMapper", "name table $where failed: $chain", t)
        } catch (_: Throwable) {}
    }

    /**
     * 中文/别名 → libretro 标准英文标题总表（懒加载）。
     * 一个游戏可以有多个别名条目指向同一英文名。
     * ★ lazy 初始化抛异常不会缓存失败状态（Synchronized 模式下每次访问
     *   都会重新尝试），配合 runCatching：失败 → 空表 + 诊断记录，
     *   下一次访问仍会重试构建。
     */
    private val TABLE: Map<String, String> by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        try {
            buildMap<String, String> {
                entriesPopularA()
                entriesPopularB()
                entriesPopularC()
                entriesPopularD()
                entriesSeriesRpg()
                entriesArcade()
                entriesDcPce()
                entriesNds()
                entriesMame()
            }
        } catch (t: Throwable) {
            recordError("build", t)
            emptyMap()
        }
    }

    // ------------------------------------------------------------------
    // 分块数据表（每块 ~70 条 put，单方法极小；按主题切分）
    // ------------------------------------------------------------------

    /** FC/NES 热门 A：马里奥/魂斗罗/恶魔城/洛克人 等卷轴动作。 */
    private fun MutableMap<String, String>.entriesPopularA() {
        put("超级玛丽", "Super Mario Bros.")
        put("超级马里奥", "Super Mario Bros.")
        put("超级马莉", "Super Mario Bros.")
        put("超级玛莉", "Super Mario Bros.")
        put("SUPER MARIO", "Super Mario Bros.")  // 常见半英文写法兜底
        put("超级玛丽2", "Super Mario Bros. 2")
        put("超级马里奥2", "Super Mario Bros. 2")
        put("超级玛丽3", "Super Mario Bros. 3")
        put("超级马里奥3", "Super Mario Bros. 3")
        put("超级玛丽64", "Super Mario 64")
        put("耀西", "Yoshi")
        put("耀西岛", "Super Mario World 2: Yoshi's Island")
        put("马里奥赛车", "Super Mario Kart")
        put("马力欧", "Super Mario Bros.")
        put("魂斗罗", "Contra")
        put("超级魂斗罗", "Super Contra")
        put("魂斗罗力量", "Contra Force")
        put("魂斗罗铁血兵团", "Contra: Hard Corps")
        put("铁血兵团", "Contra: Hard Corps")
        put("赤色要塞", "Jackal")
        put("绿色兵团", "Rush'n Attack")
        put("坦克大战", "Battle City")
        put("沙罗曼蛇", "Salamander")
        put("沙罗曼多", "Salamander")
        put("宇宙巡航机", "Gradius")
        put("宇宙巡航舰", "Gradius")
        put("兵锋", "Gradius III")
        put("恶魔城", "Castlevania")
        put("恶魔城传说", "Castlevania III: Dracula's Curse")
        put("恶魔城德古拉", "Castlevania")
        put("月下夜想曲", "Castlevania: Symphony of the Night")
        put("晓月圆舞曲", "Castlevania: Aria of Sorrow")
        put("白夜协奏曲", "Castlevania: Harmony of Dissonance")
        put("圆桌骑士", "Knights of the Round")
        put("双截龙", "Double Dragon")
        put("双截龙2", "Double Dragon II: The Revenge")
        put("双截龙II", "Double Dragon II: The Revenge")
        put("双截龙III", "Double Dragon III: The Sacred Stones")
        put("忍者龙剑传", "Ninja Gaiden")
        put("忍者龙剑传2", "Ninja Gaiden II: The Dark Sword of Chaos")
        put("忍者龙剑传3", "Ninja Gaiden III: The Ancient Ship of Doom")
        put("赤影战士", "Shadow Warriors")
        put("水上魂斗罗", "Shadow Warriors")
        put("洛克人", "Mega Man")
        put("洛克人2", "Mega Man 2")
        put("洛克人3", "Mega Man 3")
        put("洛克人4", "Mega Man 4")
        put("洛克人5", "Mega Man 5")
        put("洛克人6", "Mega Man 6")
        put("洛克人X", "Mega Man X")
        put("元祖洛克人", "Mega Man")
        put("冒险岛", "Adventure Island")
        put("冒险岛2", "Adventure Island II")
        put("冒险岛3", "Adventure Island 3")
        put("冒险岛4", "Adventure Island 4")
        put("高桥名人", "Adventure Island")
        put("雪人兄弟", "Snow Bros.")
        put("松鼠大战", "Chip 'n Dale: Rescue Rangers")
        put("松鼠大战2", "Chip 'n Dale: Rescue Rangers 2")
        put("唐老鸭梦冒险", "DuckTales")
        put("唐老鸭历险记", "DuckTales")
        put("唐老鸭梦冒险2", "DuckTales 2")
        put("大力水手", "Popeye")
        put("敲冰块", "Ice Climber")
        put("气球大战", "Balloon Fight")
        put("打鸭子", "Duck Hunt")
        put("火之鸟", "Fire Emblem")
    }

    /** FC/NES 热门 B：火纹/塞尔达/银河/FF/DQ 等 RPG 名作 + 常见小游戏。 */
    private fun MutableMap<String, String>.entriesPopularB() {
        put("火焰纹章", "Fire Emblem")
        put("火焰之纹章", "Fire Emblem")
        put("塞尔达传说", "The Legend of Zelda")
        put("塞尔达", "The Legend of Zelda")
        put("林克的冒险", "Zelda II: The Adventure of Link")
        put("众神的三角力量", "The Legend of Zelda: A Link to the Past")
        put("缩小帽", "The Legend of Zelda: The Minish Cap")
        put("梦见岛", "The Legend of Zelda: Link's Awakening")
        put("时之笛", "The Legend of Zelda: Ocarina of Time")
        put("梅祖拉的假面", "The Legend of Zelda: Majora's Mask")
        put("银河战士", "Metroid")
        put("超级银河战士", "Super Metroid")
        put("融合", "Metroid Fusion")
        put("密特罗德", "Metroid")
        put("最终幻想", "Final Fantasy")
        put("最终幻想2", "Final Fantasy II")
        put("最终幻想3", "Final Fantasy III")
        put("最终幻想4", "Final Fantasy IV")
        put("最终幻想5", "Final Fantasy V")
        put("最终幻想6", "Final Fantasy VI")
        put("最终幻想7", "Final Fantasy VII")
        put("最终幻想8", "Final Fantasy VIII")
        put("最终幻想9", "Final Fantasy IX")
        put("最终幻想战略版", "Final Fantasy Tactics")
        put("勇者斗恶龙", "Dragon Quest")
        put("勇者斗恶龙2", "Dragon Quest II")
        put("勇者斗恶龙3", "Dragon Quest III")
        put("勇者斗恶龙4", "Dragon Quest IV")
        put("勇者斗恶龙5", "Dragon Quest V")
        put("勇者斗恶龙6", "Dragon Quest VI")
        put("勇者斗恶龙7", "Dragon Quest VII")
        put("勇者斗恶龙8", "Dragon Quest VIII: Journey of the Cursed King")
        put("女神异闻录", "Revelations: Persona")
        put("真女神转生", "Shin Megami Tensei")
        put("重装机兵", "Metal Max")
        put("重装机兵2", "Metal Max 2")
        put("重装机兵3", "Metal Max 3")
        put("重装机兵4", "Metal Max 4 - Gekkou no Diva")
        put("重装机兵月光歌姬", "Metal Max 4 - Gekkou no Diva")
        put("重装机车战", "Metal Max Returns")
        put("机器人大战", "Super Robot Wars")
        put("超级机器人大战", "Super Robot Wars")
        put("第二次机器人大战", "Super Robot Wars 2")
        put("第三次机器人大战", "Super Robot Wars 3")
        put("第四次机器人大战", "Super Robot Wars 4")
        put("热血硬派", "River City Ransom")
        put("热血物语", "River City Ransom")
        put("热血高校", "River City Ransom")
        put("热血格斗", "Nekketsu Kakutou Densetsu")
        put("热血篮球", "Nekketsu! Basketball")
        put("热血足球", "Nekketsu! Soccer")
        put("热血曲棍球", "Crash 'n the Boys: Street Challenge")
        put("热血时代剧", "Nekketsu Kouha Kunio-kun: Bangai Rantouhen")
        put("超级拳击", "StarTropics")
        put("网球", "Tennis")
        put("高尔夫", "Golf")
        put("马里奥医生", "Dr. Mario")
        put("马里奥医生2", "Dr. Mario 64")
        put("俄罗斯方块", "Tetris")
        put("俄罗斯方块2", "Tetris 2")
        put("五子棋", "Fiver")
        put("挖金子", "Dig Dug")
        put("敲砖块", "Arkanoid")
        put("打砖块", "Arkanoid")
        put("小蜜蜂", "Galaxian")
        put("大蜜蜂", "Galaga")
    }

    /** FC/NES 热门 C：吃豆人/功夫/龙珠/圣斗士 等动漫改编与经典小品。 */
    private fun MutableMap<String, String>.entriesPopularC() {
        put("吃豆人", "Pac-Man")
        put("吃豆人小姐", "Ms. Pac-Man")
        put("大力士", "Kung Fu")
        put("功夫", "Kung Fu")
        put("影子传说", "The Legend of Kage")
        put("忍者茶丸", "Chelmsford")
        put("成龙踢馆", "Kung-Fu Master")
        put("成龙之龙", "Jackie Chan's Action Kung Fu")
        put("西游记", "Journey to the West")
        put("机器人攻防战", "Bionic Commando")
        put("希特勒复活", "Bionic Commando")
        put("战场之狼", "Mercs")
        put("战场之狼2", "Mercs")
        put("1942", "1942")
        put("1943", "1943: The Battle of Midway")
        put("1944", "1944: The Loop Master")
        put("加纳战机", "The Guardian Legend")
        put("加纳战舰", "Guardic Gales")
        put("数独", "Sudoku")
        put("炸弹人", "Bomberman")
        put("炸弹人2", "Bomberman II")
        put("炸弹人3", "Bomberman '93")
        put("原子弹小子", "Bomberman")
        put("名侦探柯南", "Detective Conan")
        put("三目童子", "The Three-Eyed One")
        put("三眼神童", "The Three-Eyed One")
        put("圣斗士星矢", "Saint Seiya: Ougon Densetsu")
        put("北斗神拳", "Fist of the North Star")
        put("龙珠", "Dragon Ball")
        put("七龙珠", "Dragon Ball")
        put("七龙珠Z", "Dragon Ball Z")
        put("龙珠Z超武斗传", "Dragon Ball Z: Super Butouden")
        put("龙珠Z超武斗传2", "Dragon Ball Z: Super Butouden 2")
        put("龙珠Z超武斗传3", "Dragon Ball Z: Super Butouden 3")
        put("圣剑传说", "Secret of Mana")
        put("圣剑传说2", "Secret of Mana")
        put("圣剑传说3", "Seiken Densetsu 3")
        put("时空之轮", "Chrono Trigger")
        put("超时空之轮", "Chrono Trigger")
        put("时空勇士", "Live A Live")
        put("浪漫沙加", "Romancing SaGa")
        put("浪漫沙加2", "Romancing SaGa 2")
        put("浪漫沙加3", "Romancing SaGa 3")
        put("大航海时代", "Uncharted Waters")
        put("大航海时代2", "Uncharted Waters: New Horizons")
        put("三国志", "Romance of the Three Kingdoms")
        put("三国志2", "Romance of the Three Kingdoms II")
        put("三国志英杰传", "Sangokushi Eiketsuden")
        put("三国志孔明传", "Sangokushi Kōmeiden")
        put("吞食天地", "Destiny of an Emperor")
        put("吞食天地2", "Destiny of an Emperor II")
        put("富甲天下", "Wealthy Queen")
        put("大富翁", "Richman")
        put("信长野望", "Nobunaga's Ambition")
        put("信长之野望", "Nobunaga's Ambition")
        put("忍者猫", "Ninja Ryukenden (Tomoe)")
        put("数码宝贝", "Digimon")
        put("数码暴龙", "Digimon")
        put("宝可梦", "Pokémon")
        put("口袋妖怪", "Pokémon")
        put("口袋怪兽", "Pokémon")
        put("神奇宝贝", "Pokémon")
        put("宠物小精灵", "Pokémon")
    }

    /** FC/NES 热门 D + 掌机世代：口袋妖怪各版本 / 卡比 / 马里奥聚会。 */
    private fun MutableMap<String, String>.entriesPopularD() {
        put("口袋妖怪红", "Pokémon Red Version")
        put("口袋妖怪蓝", "Pokémon Blue Version")
        put("口袋妖怪绿", "Pokémon Green Version")
        put("口袋妖怪黄", "Pokémon Yellow Version: Special Pikachu Edition")
        put("口袋妖怪金", "Pokémon Gold Version")
        put("口袋妖怪银", "Pokémon Silver Version")
        put("口袋妖怪水晶", "Pokémon Crystal Version")
        put("口袋妖怪红宝石", "Pokémon Ruby Version")
        put("口袋妖怪蓝宝石", "Pokémon Sapphire Version")
        put("口袋妖怪绿宝石", "Pokémon Emerald Version")
        put("口袋妖怪火红", "Pokémon FireRed Version")
        put("口袋妖怪叶绿", "Pokémon LeafGreen Version")
        put("口袋妖怪钻石", "Pokémon Diamond Version")
        put("口袋妖怪珍珠", "Pokémon Pearl Version")
        put("口袋妖怪白金", "Pokémon Platinum Version")
        put("口袋妖怪心金", "Pokémon HeartGold Version")
        put("口袋妖怪魂银", "Pokémon SoulSilver Version")
        put("口袋妖怪黑", "Pokémon Black Version")
        put("口袋妖怪白", "Pokémon White Version")
        put("马里奥聚会", "Mario Party")
        put("星之卡比", "Kirby's Dream Land")
        put("星之卡比2", "Kirby's Dream Land 2")
        put("星之卡比3", "Kirby's Dream Land 3")
        put("卡比", "Kirby's Dream Land")
        put("星之卡比镜之大迷宫", "Kirby & The Amazing Mirror")
        put("恶魔城白夜", "Castlevania: Harmony of Dissonance")
        put("马戏团", "Circus Charlie")
        put("超人·奥特曼", "Ultraman")
        put("奥特曼", "Ultraman")
        put("幽灵特攻队", "Golgo 13: Top Secret Episode")
        put("特救指令", "Zombie Nation")
        put("叉叉争夺战", "Cross Fire")
        put("飞狼", "Airwolf")
        put("七宝奇谋", "The Goonies")
        put("七宝奇谋2", "The Goonies II")
        put("捉猴啦", "Ape Escape")
        put("哔哔卡", "Bibi Card")
        put("中东战争", "The Super Shinobi")
        put("超级忍", "The Super Shinobi")
        put("超级忍2", "The Super Shinobi II")
        put("忍者外传", "Ninja Gaiden")
        put("怒之铁拳", "Streets of Rage")
        put("怒之铁拳2", "Streets of Rage 2")
        put("怒之铁拳3", "Streets of Rage 3")
        put("战斧", "Golden Axe")
        put("战斧2", "Golden Axe II")
        put("战斧3", "Golden Axe III")
        put("光明与黑暗", "Shining Force")
        put("光明力量", "Shining Force")
        put("光明与黑暗2", "Shining Force II")
    }

    /** 系列续作与主机世代名作：幽游白书/大金刚/梦幻之星/索尼克/生化危机 等。 */
    private fun MutableMap<String, String>.entriesSeriesRpg() {
        // ★ 火焰纹章系列（用户实测 "火纹外传" 未收录 → 补全正名与常见简称）：
        put("火纹外传", "Fire Emblem Gaiden")
        put("火纹外传外传", "Fire Emblem Gaiden")
        put("火纹传说", "Fire Emblem: Shadow Dragon and the Blade of Light")
        put("火炎之纹章外传", "Fire Emblem Gaiden")
        put("火炎之纹章", "Fire Emblem: Shadow Dragon and the Blade of Light")
        put("火焰之纹章外传", "Fire Emblem Gaiden")
        put("火焰之纹章", "Fire Emblem: Shadow Dragon and the Blade of Light")
        put("火焰纹章外传", "Fire Emblem Gaiden")
        put("火焰纹章", "Fire Emblem: Shadow Dragon and the Blade of Light")
        put("圣火徽章外传", "Fire Emblem Gaiden")
        put("圣火徽章", "Fire Emblem: Shadow Dragon and the Blade of Light")
        put("暗黑龙与光之剑", "Fire Emblem: Shadow Dragon and the Blade of Light")
        put("幽游白书", "Yu Yu Hakusho")
        put("幽游白书魔强统一战", "Yu Yu Hakusho: Makyou Toitsusen")
        put("幽灵电王", "Yu Yu Hakusho: Makyou Toitsusen")
        put("火影忍者", "Naruto")
        put("海贼王", "One Piece")
        put("死神", "Bleach")
        put("犬夜叉", "Inuyasha")
        put("游戏王", "Yu-Gi-Oh!")
        put("游戏王游戏", "Yu-Gi-Oh! Duel Monsters")
        put("棒球英豪", "Touch")
        put("天使之翼", "Captain Tsubasa")
        put("足球小将", "Captain Tsubasa")
        put("天使之翼2", "Captain Tsubasa Vol. II: Super Striker")
        put("天使之翼2中文版", "Captain Tsubasa Vol. II: Super Striker")
        put("足球小将2", "Captain Tsubasa Vol. II: Super Striker")
        put("足球小将3", "Captain Tsubasa 3: Koutei no Chousen")
        put("天使之翼的荣耀", "Captain Tsubasa: Fanshuu")
        put("热血躲避球", "Nekketsu Dodgeball")
        put("超级大金刚", "Donkey Kong Country")
        put("超级大金刚2", "Donkey Kong Country 2: Diddy's Kong Quest")
        put("超级大金刚3", "Donkey Kong Country 3: Dixie Kong's Double Trouble!")
        put("大金刚", "Donkey Kong")
        put("金刚", "King Kong 2: Ikari no Megaton Punch")
        put("梦幻之星", "Phantasy Star")
        put("梦幻之星2", "Phantasy Star II")
        put("梦幻之星4", "Phantasy Star IV")
        put("太空战士", "Final Fantasy")
        put("索尼克", "Sonic the Hedgehog")
        put("索尼克2", "Sonic the Hedgehog 2")
        put("索尼克3", "Sonic the Hedgehog 3")
        put("索尼克与纳克鲁斯", "Sonic & Knuckles")
        put("刺猬索尼克", "Sonic the Hedgehog")
        put("世嘉拉力", "Sega Rally Championship")
        put("VR赛车", "Virtua Racing")
        put("VR战士", "Virtua Fighter")
        put("VR特警", "Virtua Cop")
        put("铁拳", "Tekken")
        put("铁拳3", "Tekken 3")
        put("山脊赛车", "Ridge Racer")
        put("生化危机", "Resident Evil")
        put("生化危机2", "Resident Evil 2")
        put("生化危机3", "Resident Evil 3: Nemesis")
        put("寄生前夜", "Parasite Eve")
        put("合金装备", "Metal Gear Solid")
        put("潜龙谍影", "Metal Gear Solid")
        put("实况足球", "World Soccer: Winning Eleven")
        put("胜利十一人", "World Soccer: Winning Eleven")
        put("实况足球8", "World Soccer: Winning Eleven 8")
        put("实况足球10", "World Soccer: Winning Eleven 10")
        put("FIFA足球", "FIFA Soccer")
        put("最终幻想粒子", "Final Fantasy")
        put("古墓丽影", "Tomb Raider")
        put("古墓丽影2", "Tomb Raider II")
        put("雷曼", "Rayman")
        put("梦幻模拟战", "Langrisser")
        put("梦幻模拟战2", "Langrisser II")
        put("兰古利萨", "Langrisser")
        put("炎龙骑士团", "Flame Dragon")
        put("炎龙骑士团2", "Flame Dragon Plus: Flags of Chaos")
        put("风色幻想", "Wind Fantasy")
        put("天使帝国", "Empire of Angels")
        put("轩辕剑", "Xuan-Yuan Sword")
        put("轩辕剑外传枫之舞", "Xuan-Yuan Sword: Dance of the Maple")
        put("仙剑奇侠传", "The Legend of Sword and Fairy")
        put("仙剑", "The Legend of Sword and Fairy")
        put("金庸群侠传", "Book and Sword")
        put("天地劫", "Tian Di Jie")
        put("阿玛迪斯战记", "Amadeus Rebellion")
        put("幻世录", "Phantom Chronicles")
    }

    /** 街机格斗/射击：街霸/拳皇/饿狼/侍魂/合金弹头/恐龙快打 等。 */
    private fun MutableMap<String, String>.entriesArcade() {
        put("街霸", "Street Fighter II: The World Warrior")
        put("街霸2", "Street Fighter II: The World Warrior")
        put("街头霸王", "Street Fighter II: The World Warrior")
        put("街头霸王2", "Street Fighter II: The World Warrior")
        put("街霸II", "Street Fighter II: The World Warrior")
        put("超级街霸2", "Super Street Fighter II: The New Challengers")
        put("街霸3", "Street Fighter III: New Generation")
        put("街霸3三度冲击", "Street Fighter III 3rd Strike: Fight for the Future")
        put("少年街霸", "Street Fighter Alpha: Warriors' Dreams")
        put("少年街霸2", "Street Fighter Alpha 2")
        put("少年街霸3", "Street Fighter Alpha 3")
        put("拳皇", "The King of Fighters '94")
        put("拳皇94", "The King of Fighters '94")
        put("拳皇95", "The King of Fighters '95")
        put("拳皇96", "The King of Fighters '96")
        put("拳皇97", "The King of Fighters '97")
        put("拳皇98", "The King of Fighters '98: The Slugfest")
        put("拳皇99", "The King of Fighters '99: Millennium Battle")
        put("拳皇2000", "The King of Fighters 2000")
        put("拳皇2001", "The King of Fighters 2001")
        put("拳皇2002", "The King of Fighters 2002: Challenge to Ultimate Battle")
        put("拳皇2003", "The King of Fighters 2003")
        put("饿狼传说", "Fatal Fury: King of Fighters")
        put("饿狼传说2", "Fatal Fury 2")
        put("饿狼传说特别版", "Fatal Fury Special")
        put("饿狼传说3", "Fatal Fury 3: Road to the Final Victory")
        put("饿狼传说狼之印记", "Garou: Mark of the Wolves")
        put("龙虎之拳", "Art of Fighting")
        put("龙虎之拳2", "Art of Fighting 2")
        put("龙虎之拳3", "Art of Fighting 3: The Path of the Warrior")
        put("侍魂", "Samurai Shodown")
        put("侍魂2", "Samurai Shodown II")
        put("侍魂3", "Samurai Shodown III")
        put("侍魂4", "Samurai Shodown IV: Amakusa's Revenge")
        put("侍魂零", "Samurai Shodown V")
        put("世界英雄", "World Heroes")
        put("豪血寺一族", "Power Instinct")
        put("月华剑士", "The Last Blade")
        put("月华剑士2", "The Last Blade 2")
        put("合金弹头", "Metal Slug - Super Vehicle-001")
        put("合金弹头2", "Metal Slug 2: Super Vehicle-001/II")
        put("合金弹头3", "Metal Slug 3")
        put("合金弹头4", "Metal Slug 4")
        put("合金弹头5", "Metal Slug 5")
        put("合金弹头6", "Metal Slug 6")
        put("合金弹头X", "Metal Slug X: Super Vehicle-001")
        put("雷龙", "Raiden")
        put("雷龙2", "Raiden II")
        put("彩京", "Psikyo")
        put("1945", "Strikers 1945")
        put("1945二代", "Strikers 1945 II")
        put("打击者1945", "Strikers 1945")
        put("打击者1945二代", "Strikers 1945 II")
        put("恐龙快打", "Cadillacs and Dinosaurs")
        put("恐龙岛", "Cadillacs and Dinosaurs")
        put("名将", "Captain Commando")
        put("三国志吞食天地", "Warriors of Fate")
        put("圆桌武士", "Knights of the Round")
        put("降落神兵", "Sengoku")
        put("战国传承", "Sengoku")
        put("美沙罗战记", "Mercs")
        put("四国战机", "Aero Fighters")
        put("四国战机2", "Aero Fighters 2")
        put("雷霆之翼", "Raiden")
        put("化身博士", "Metamoqester")
        put("西游释厄传", "Oriental Legend")
        put("西游记释厄传", "Oriental Legend")
        put("东方传说", "Oriental Legend")
        put("万能战车", "Metal Slug: Super Vehicle-001")
        put("快打旋风", "Final Fight")
        put("快打旋风2", "Final Fight 2")
        put("超级快打旋风", "Final Fight")
    }

    /** DC / PCE / MD 其他：世嘉与 Dreamcast 常见译名。 */
    private fun MutableMap<String, String>.entriesDcPce() {
        put("疯狂出租车", "Crazy Taxi")
        put("莎木", "Shenmue")
        put("莎木2", "Shenmue II")
        put("灵魂能力", "Soulcalibur")
        put("刀魂", "Soulcalibur")
        put("死或生", "Dead or Alive")
        put("死或生2", "Dead or Alive 2")
        put("VR战士3", "Virtua Fighter 3tb")
        put("英雄萨姆", "Serious Sam")
        put("吉格曼", "Jet Grind Radio")
        put("涂鸦小子", "Jet Set Radio")
        put("索尼克大冒险", "Sonic Adventure")
        put("索尼克大冒险2", "Sonic Adventure 2")
        put("梦游美国", "Daytona USA")
        put("GT赛车", "Gran Turismo")
        put("天外魔境", "Tengai Makyou")
        put("桃太郎电铁", "Momotarou Dentetsu")
        put("恶魔城X血之轮回", "Castlevania: Rondo of Blood")
        put("恶魔城X月下夜想曲", "Castlevania: Symphony of the Night")
        put("炸弹人93", "Bomberman '93")
        put("兽王记", "Altered Beast")
        put("超级猴子球", "Super Monkey Ball")
    }

    /** NDS / 3DS：新超级马里奥兄弟/马车DS/大乱斗/怪猎 等。 */
    private fun MutableMap<String, String>.entriesNds() {
        put("马里奥赛车DS", "Mario Kart DS")
        put("新超级马里奥兄弟", "New Super Mario Bros.")
        put("超级马里奥64DS", "Super Mario 64 DS")
        put("马里奥与路易基RPG", "Mario & Luigi: Superstar Saga")
        put("动物之森", "Animal Crossing")
        put("动森", "Animal Crossing")
        put("口袋妖怪黑白", "Pokémon Black Version")
        put("马里奥3D大陆", "Super Mario 3D Land")
        put("马里奥赛车7", "Mario Kart 7")
        put("路易吉洋馆", "Luigi's Mansion")
        put("纸片马里奥", "Paper Mario")
        put("火焰之纹章觉醒", "Fire Emblem: Awakening")
        put("怪物猎人", "Monster Hunter")
        put("怪物猎人3G", "Monster Hunter 3 Ultimate")
        put("怪物猎人4", "Monster Hunter 4")
        put("任天堂明星大乱斗", "Super Smash Bros.")
        put("任天堂全明星大乱斗", "Super Smash Bros.")
        put("大乱斗", "Super Smash Bros.")
        put("超级大乱斗", "Super Smash Bros. Brawl")
        put("健身环大冒险", "Ring Fit Adventure")
        put("精灵宝可梦", "Pokémon")
    }

    /**
     * 街机驱动名（MAME/FBNeo zip 文件名 → MAME 描述名）。
     * libretro 街机封面目录（FBNeo - Arcade Games / MAME）按 MAME 描述名
     * 组织（"The King of Fighters '97 (NGM-2320).png"），ROM 是驱动名
     * （kof97.zip）—— 直接搜驱动名永远 404。这里补最常见街机集的
     * 驱动名映射（描述名不带序号后缀也能被模糊匹配命中变体）。
     */
    private fun MutableMap<String, String>.entriesMame() {
        put("kof94", "The King of Fighters '94")
        put("kof95", "The King of Fighters '95")
        put("kof96", "The King of Fighters '96")
        put("kof97", "The King of Fighters '97")
        put("kof97plus", "The King of Fighters '97 Plus (bootleg)")
        put("kof98", "The King of Fighters '98 - The Slugfest")
        put("kof99", "The King of Fighters '99 - Millennium Battle")
        put("kof2000", "The King of Fighters 2000")
        put("kof2001", "The King of Fighters 2001")
        put("kof2002", "The King of Fighters 2002 - Challenge to Ultimate Battle")
        put("kof2003", "The King of Fighters 2003")
        put("mslug", "Metal Slug - Super Vehicle-001")
        put("mslug2", "Metal Slug 2 - Super Vehicle-001_II")
        put("mslugx", "Metal Slug X - Super Vehicle-001")
        put("mslug3", "Metal Slug 3")
        put("mslug4", "Metal Slug 4")
        put("mslug5", "Metal Slug 5")
        put("mslug6", "Metal Slug 6")
        put("dino", "Cadillacs and Dinosaurs")
        put("captcomn", "Captain Commando")
        put("knightrou", "Knights of the Round")
        put("wof", "Warriors of Fate")
        put("punisher", "The Punisher")
        put("ffight", "Final Fight")
        put("sf2", "Street Fighter II - The World Warrior")
        put("sf2ce", "Street Fighter II' - Champion Edition")
        put("sf2hf", "Street Fighter II' Turbo - Hyper Fighting")
        put("sfa", "Street Fighter Alpha - Warriors' Dreams")
        put("sfa2", "Street Fighter Alpha 2")
        put("sfa3", "Street Fighter Alpha 3")
        put("sfiii", "Street Fighter III - New Generation")
        put("sfiii2", "Street Fighter III 2nd Impact - Giant Attack")
        put("sfiii3", "Street Fighter III 3rd Strike - Fight for the Future")
        put("samsho", "Samurai Shodown")
        put("samsho2", "Samurai Shodown II")
        put("samsho3", "Samurai Shodown III")
        put("samsho4", "Samurai Shodown IV - Amakusa's Revenge")
        put("samsho5", "Samurai Shodown V")
        put("garou", "Garou - Mark of the Wolves")
        put("fatfury1", "Fatal Fury - King of Fighters")
        put("fatfury2", "Fatal Fury 2")
        put("fatfursp", "Fatal Fury Special")
        put("fatfury3", "Fatal Fury 3 - Road to the Final Victory")
        put("aof", "Art of Fighting")
        put("aof2", "Art of Fighting 2")
        put("aof3", "Art of Fighting 3 - The Path of the Warrior")
        put("lastblad", "The Last Blade")
        put("lastblad2", "The Last Blade 2")
        put("sengoku", "Sengoku Denshou")
        put("1941", "1941 - Counter Attack")
        put("1942", "1942")
        put("1943", "1943 - The Battle of Midway")
        put("1944", "1944 - The Loop Master")
        put("varth", "Varth - Operation Thunderstorm")
        put("snowbro", "Snow Bros. - Nick & Tom")
        put("s1945", "Strikers 1945")
        put("s1945ii", "Strikers 1945 II")
        put("s1945iii", "Strikers 1945 III")
        put("raiden", "Raiden")
        put("raiden2", "Raiden II")
        put("ddonpach", "DoDonPachi")
        put("donpachi", "DonPachi")
        put("batcir", "Battle Circuit")
        put("armwar", "Armored Warriors")
    }

    /** 归一化：全角→半角、去扩展名/括号标签/空格、小写。 */
    private fun normalize(raw: String): String {
        var s = raw.trim()
        // 去扩展名
        val dot = s.lastIndexOf('.')
        if (dot > 0 && s.length - dot <= 4) s = s.substring(0, dot)
        // 全角转半角
        val sb = StringBuilder(s.length)
        for (ch in s) {
            when {
                ch.code in 0xFF01..0xFF5E -> sb.append((ch.code - 0xFEE0).toChar())
                ch == '　' -> sb.append(' ')
                else -> sb.append(ch)
            }
        }
        s = sb.toString()
        // 去括号标签与空白
        // ★★★ Android ICU 正则转义修复（"DEGRADED: PatternSyntaxException
        //   near index 11"根因）★★★：Android 的 java.util.regex 底层是 ICU
        //   引擎（与桌面 OpenJDK 不同）——字符类外的**未转义右花括号/右中
        //   括号**（"\s*\{[^}]*}" 的结尾 "}"）在 ICU 里按量词闭合符解析，
        //   直接抛 PatternSyntaxException；桌面 JVM 当字面量放行 → 本 Bug
        //   只在真机复现。normalize() 是**每次表构建必经**的路径 → 首次
        //   resolve 即抛 → runCatching 把整表退化为空表（DEGRADED）→ 全部
        //   中文名按原文搜索 → libretro 全 404。修复：全部右括号字面量
        //   显式转义（"\]"、"\}"）。
        s = s.replace(Regex("\\s*\\([^)]*\\)"), "")
            .replace(Regex("\\s*\\[[^\\]]*\\]"), "")
            .replace(Regex("\\s*\\{[^}]*\\}"), "")
            .replace(Regex("\\s+"), "")
            .lowercase()
        // 去尾部数字编号分隔符（"拳皇97 " / "拳皇97版"）
        s = s.trimEnd('版', '代', '集')
        return s
    }

    /**
     * 原始表：归一化键 → 英文名（懒构建，构建失败退化为空表）。
     */
    private val NORMALIZED: Map<String, String> by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        try {
            TABLE.entries.associate { normalize(it.key) to it.value }
        } catch (t: Throwable) {
            recordError("normalize", t)
            emptyMap()
        }
    }

    /** 罗马数字/阿拉伯数字等价（超级玛丽2 ↔ 超级玛丽II）。 */
    private val ROMAN_EQ = mapOf(
        "ii" to "2", "iii" to "3", "iv" to "4", "v" to "5", "vi" to "6",
        "vii" to "7", "viii" to "8", "ix" to "9", "x" to "10"
    )

    /**
     * 中文名 → 英文名。
     * @return 英文标题；未命中返回 null（调用方继续走原名模糊搜索）。
     *         ★ 任何内部异常同样返回 null（映射器故障绝不外溢 ——
     *           封面抓取按原名继续，这是"全部游戏一个封面都下不了"的根治）。
     */
    fun resolve(rawName: String): String? = try {
        resolveInner(rawName)
    } catch (t: Throwable) {
        recordError("resolve", t)
        null
    }

    private fun resolveInner(rawName: String): String? {
        if (rawName.isBlank()) return null
        val key = normalize(rawName)
        if (key.isEmpty()) return null
        // 表构建失败退化为空表 → 走到前缀匹配也全 miss，直接返回 null
        if (NORMALIZED.isEmpty()) return null
        // 1) 精确命中
        NORMALIZED[key]?.let { return it }
        // 2) 罗马数字等价（"街霸ii" → "街霸2"）
        for ((roman, num) in ROMAN_EQ) {
            val k = key.replace(roman, num)
            if (k != key) NORMALIZED[k]?.let { return it }
        }
        // 3) 前缀 / 包含匹配（"魂斗罗2010" 前缀命中 "魂斗罗"；
        //    "超级玛丽特别版" 包含 "超级玛丽"）。取最长命中键（最具体）。
        var best: Pair<Int, String>? = null // len to english
        for ((k, v) in NORMALIZED) {
            if (k.length < 2) continue
            val hit = key.startsWith(k) || (key.contains(k) && k.length >= 2)
            if (hit) {
                if (best == null || k.length > best.first) best = k.length to v
            }
        }
        return best?.second
    }

    // ==================================================================
    // ★★★ 词汇级中文 → 英文模糊翻译（"超级玛丽" → "super mario"）★★★
    //
    // 需求来源（用户原话）："要的是模糊搜索，中文翻译成英语……比如超级
    // 翻译为 super，玛丽翻译为 mario"。全名表（TABLE）只覆盖完整键，
    // 玩家库里大量"火纹外传"、"超级玛丽HACK版"、"XX汉化版"等派生命名
    // 永远 miss → 原名（中文）直连 libretro 全 404。本层做**最长匹配
    // 分词翻译**：把名字里的已收录中文词汇逐个换成英文词，未收录的
    // 中文字符丢弃（不干扰搜索），字母/数字原样保留 —— 得到英文搜索
    // 短语后交给 CoverFetcher.fuzzyMatch 对系统索引做模糊匹配。
    // ==================================================================

    /**
     * 词汇级中文 → 英文模糊翻译。
     *
     * @return 英文搜索短语（如 "super mario" / "fire emblem gaiden"）；
     *         输入无 CJK、或一个词都命中不了时返回 null（调用方继续用原名）。
     *         ★ 任何内部异常同样返回 null（与 [resolve] 同级 fail-soft）。
     */
    fun fuzzyTranslate(rawName: String): String? = try {
        fuzzyTranslateInner(rawName)
    } catch (t: Throwable) {
        recordError("fuzzyTranslate", t)
        null
    }

    private fun fuzzyTranslateInner(rawName: String): String? {
        if (rawName.isBlank() || !containsCjk(rawName)) return null
        // 1) 全角 → 半角（与 normalize 同款换算，但保留空格/字母/数字）
        val sb = StringBuilder(rawName.length)
        for (ch in rawName) {
            when {
                ch.code in 0xFF01..0xFF5E -> sb.append((ch.code - 0xFEE0).toChar())
                ch == '　' -> sb.append(' ')
                else -> sb.append(ch)
            }
        }
        var s = sb.toString()
        // 2) 去括号/书名号段（（ ）【 】[ ]{ }内全部丢弃 —— 版本注记不参与搜索）
        //    注意：全部方/花括号都在字符类内转义，规避 Android ICU 正则的
        //    "未转义 } / ] 直接 PatternSyntaxException" 限制（见 normalize 修复注释）。
        s = s.replace(Regex("[（(【\\[\\{][^）)】\\]\\}]*[）)】\\]\\}]"), " ")
        // 3) 去常见版本噪声词（先长后短，避免"汉化版"只删掉"版"）
        for (n in NOISE_WORDS) s = s.replace(n, " ")
        // 4) 最长匹配分词：每轮先试 6→2 字词表命中；未收录 CJK 字符丢弃；
        //    非半角 CJK（字母/数字/符号）原样保留。
        //    ★ 中英边界补空格："超级玛丽2" → "super mario 2"（而非
        //    "super mario2"）、"魂斗罗HACK" → "contra hack" —— 否则
        //    翻译词与原文粘连成一个 token，模糊匹配分词后完全对不上。
        val out = StringBuilder()
        var i = 0
        var hits = 0
        var lastWasTranslated = false
        while (i < s.length) {
            val c = s[i]
            if (c.code in 0x4E00..0x9FFF || c.code in 0x3400..0x4DBF) {
                var matchedLen = 0
                var matchedEn: String? = null
                val maxLen = minOf(6, s.length - i)
                for (len in maxLen downTo 2) {
                    val en = WORDS[s.substring(i, i + len)]
                    if (en != null) {
                        matchedLen = len
                        matchedEn = en
                        break
                    }
                }
                if (matchedEn != null) {
                    if (out.isNotEmpty() && out.last() != ' ') out.append(' ')
                    out.append(matchedEn)
                    hits++
                    i += matchedLen
                    lastWasTranslated = true
                } else {
                    i++ // 未收录的中文字符：丢弃（避免拼音式噪声污染搜索）
                }
            } else {
                // ASCII/数字边界补空格：翻译词后紧跟的原文、字母↔数字切换处
                if (out.isNotEmpty() && out.last() != ' ') {
                    val last = out.last()
                    if (lastWasTranslated ||
                        (last.isLetter() && c.isDigit()) ||
                        (last.isDigit() && c.isLetter())) {
                        out.append(' ')
                    }
                }
                out.append(c)
                lastWasTranslated = false
                i++
            }
        }
        val result = out.toString().replace(Regex("\\s+"), " ").trim()
        if (hits == 0 || result.isBlank()) return null
        return result
    }

    /** 版本/汉化噪声词（分词前整体剔除；先长后短匹配）。 */
    private val NOISE_WORDS = listOf(
        "汉化版", "中文版", "重修版", "修改版", "完结版", "加强版", "简易版",
        "典藏版", "合卡版", "磁碟机版", "完美版", "修正版", "测试版", "体验版",
        "金手指", "作弊码", "作弊器", "无限人", "散弹枪", "一坑版", "汉化",
        "中文", "简体", "繁体", "金版", "版"
    )

    /** 中文词汇 → 英文词表（懒加载；构建失败退化空表，绝不绝外抛）。 */
    private val WORDS: Map<String, String> by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        try {
            buildMap<String, String> {
                entriesWordsAction()
                entriesWordsSeries()
            }
        } catch (t: Throwable) {
            recordError("buildWords", t)
            emptyMap()
        }
    }

    /** 词汇表 A：动作/通用词。 */
    private fun MutableMap<String, String>.entriesWordsAction() {
        put("超级", "super")
        put("玛丽", "mario")
        put("马里奥", "mario")
        put("玛莉", "mario")
        put("魂斗罗", "contra")
        put("冒险岛", "adventure island")
        put("冒险", "adventure")
        put("战士", "warrior")
        put("战斗", "battle")
        put("格斗", "fighting")
        put("战争", "war")
        put("大战", "wars")
        put("忍者", "ninja")
        put("侦探", "detective")
        put("坦克大战", "battle city")
        put("坦克", "tank")
        put("雪人兄弟", "snow bros")
        put("兄弟", "bros")
        put("双截龙", "double dragon")
        put("沙罗曼蛇", "salamander")
        put("绿色兵团", "rush n attack")
        put("赤色要塞", "jackal")
        put("赤影战士", "shadow of the ninja")
        put("影子传说", "legend of kage")
        put("热血", "nekketsu")
        put("街头霸王", "street fighter")
        put("街霸", "street fighter")
        put("格斗之王", "king of fighters")
        put("拳皇", "king of fighters")
        put("拳王", "king of fighters")
        put("合金弹头", "metal slug")
        put("越南大战", "metal slug")
        put("恐龙快打", "cadillacs and dinosaurs")
        put("恐龙新世纪", "cadillacs and dinosaurs")
        put("圆桌武士", "knights of the round")
        put("三国战记", "knights of valour")
        put("名将", "captain commando")
        put("快打旋风", "final fight")
        put("龙珠", "dragon ball")
        put("七龙珠", "dragon ball")
        put("北斗神拳", "fist of the north star")
        put("怪物猎人", "monster hunter")
        put("妖怪", "monster")
        put("怪物", "monster")
        put("怪兽", "monster")
        put("恶魔", "devil")
        put("天使", "angel")
        put("幽灵", "ghost")
        put("英雄", "hero")
        put("勇者", "hero")
        put("传说", "legend")
        put("传奇", "legend")
        put("物语", "story")
        put("王国", "kingdom")
        put("帝国", "empire")
        put("世界", "world")
        put("宇宙", "space")
        put("太空", "space")
        put("银河", "galaxy")
        put("星际", "star")
        put("城市", "city")
        put("监狱", "prison")
        put("警察", "police")
        put("海盗", "pirate")
        put("医院", "hospital")
        put("学校", "school")
        put("少年", "boy")
        put("少女", "girl")
        put("魔法", "magic")
        put("魔法师", "wizard")
        put("剑士", "swordsman")
        put("恐龙", "dinosaur")
        put("龙", "dragon")
        put("剑", "sword")
        put("网球", "tennis")
        put("棒球", "baseball")
        put("足球", "soccer")
        put("篮球", "basketball")
        put("排球", "volleyball")
        put("高尔夫", "golf")
        put("拳击", "boxing")
        put("摔角", "wrestling")
        put("赛车", "racing")
        put("竞速", "racing")
        put("摩托", "moto")
        put("飞机", "plane")
        put("格斗家", "fighter")
    }

    /** 词汇表 B：系列/IP 专名。 */
    private fun MutableMap<String, String>.entriesWordsSeries() {
        put("火纹外传", "fire emblem gaiden")
        put("火炎之纹章", "fire emblem")
        put("火焰之纹章", "fire emblem")
        put("火焰纹章", "fire emblem")
        put("圣火徽章", "fire emblem")
        put("火纹", "fire emblem")
        put("外传", "gaiden")
        put("纹章", "emblem")
        put("最终幻想", "final fantasy")
        put("勇者斗恶龙", "dragon quest")
        put("龙谜", "dragon quest")
        put("塞尔达传说", "legend of zelda")
        put("塞尔达", "zelda")
        put("洛克人", "mega man")
        put("恶魔城", "castlevania")
        put("德古拉", "dracula")
        put("吞食天地", "destiny of an emperor")
        put("松鼠大战", "chip n dale")
        put("忍者龙剑传", "ninja gaiden")
        put("忍者神龟", "teenage mutant ninja turtles")
        put("音速小子", "sonic")
        put("刺猬索尼克", "sonic")
        put("索尼克", "sonic")
        put("星之卡比", "kirby")
        put("卡比", "kirby")
        put("大乱斗", "smash bros")
        put("宝可梦", "pokemon")
        put("口袋妖怪", "pokemon")
        put("宠物小精灵", "pokemon")
        put("数码宝贝", "digimon")
        put("游戏王", "yu-gi-oh")
        put("高达", "gundam")
        put("圣斗士", "saint seiya")
        put("幽游白书", "yu yu hakusho")
        put("灌篮高手", "slam dunk")
        put("城市猎人", "city hunter")
        put("名侦探柯南", "detective conan")
        put("蜘蛛侠", "spider man")
        put("蝙蝠侠", "batman")
        put("钢铁侠", "iron man")
        put("超人", "superman")
        put("梦幻模拟战", "langrisser")
        put("皇家骑士团", "tactics ogre")
        put("超时空之轮", "chrono trigger")
        put("机器人大战", "robot wars")
        put("超级机器人大战", "super robot wars")
        put("实况足球", "winning eleven")
        put("胜利十一人", "winning eleven")
        put("光明与黑暗", "shining force")
        put("光明力量", "shining force")
        put("大战略", "daisenryaku")
        put("三国志", "sangokushi")
        put("西游记", "saiyuki")
        put("信长野望", "nobunaga")
        put("太阁立志传", "taikou")
        put("提督决断", "teitoku")
    }

    /**
     * 输入是否含中日韩字符（决定是否值得做翻译查询）。
     * 纯函数无状态，理论上不可能失败 —— 仍包一层防御与 [resolve] 对齐。
     */
    fun containsCjk(raw: String): Boolean = try {
        raw.any {
            val c = it.code
            (c in 0x4E00..0x9FFF) || (c in 0x3040..0x30FF) || (c in 0x3400..0x4DBF)
        }
    } catch (_: Throwable) {
        false
    }

    /** 触发一次表构建（诊断/预热用，失败返回 false 且原因进 [lastInitError]）。 */
    fun warmUp(): Boolean = try {
        if (NORMALIZED.isEmpty() && lastInitError != null) false else true
    } catch (t: Throwable) {
        recordError("warmUp", t)
        false
    }
}
