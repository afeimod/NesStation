package com.nesstation.app.ui.emulator

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Eject
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Save
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nesstation.app.core.engine.AfbnEngine
import com.nesstation.app.core.engine.CemuEngine
import info.cemu.cemu.nativeinterface.NativeCheats
import com.nesstation.app.core.engine.FbNeoEngine
import com.nesstation.app.core.engine.Pgm3Engine
import com.nesstation.app.core.engine.RamCheatEngine
import com.nesstation.app.core.model.GamePlatform
import org.json.JSONObject
import java.io.File

/**
 * ★★ v68 金手指 / 虚拟插卡面板。
 *
 * 「全核心加入金手指的支持，他们都默认有支持，只是我的项目没加入」——
 * 各核心的金手指引擎都在，本项目此前缺的是把它们接出来的 UI：
 *
 *  - 街机（FbNeoEngine）：核心内置 FBNeo 金手指引擎（burn/cheat.cpp），
 *    读 <systemDir>/fbneo/cheats/<驱动名>.ini（官方金手指包格式：
 *    cheat "名" / default N / N "选项文本", cpu, addr, val, ...），
 *    每个条目暴露为核心选项 fbneo-cheat-<num>-<驱动名>-<条目名>，
 *    值 = "<i> - <选项文本>"（libretro/FBNeo burner/libretro/libretro.cpp
 *    create_variables_from_cheats 逐行核实）。setCoreOption 置值后核心
 *    在下一帧 GET_VARIABLE_UPDATE 热应用 —— 无需重启。
 *  - 三国战记3HD（Pgm3Engine）：libpgm3.so 原生金手指（cheat ini 同格式）
 *    + 虚拟插卡（IGS 记忆卡：建/选/插/弹/存，武将进度存 IC 卡）。
 *    JNI 签名经 libpgm3.so 反汇编核实，见 Pgm3Activity v68 注释。
 *  - Wii U（CemuEngine）：Cemu-AZ 自带金手指引擎（NativeCheats JNI），
 *    此前从未被 UI 调用 —— 本面板补齐清单/开关/重载。
 *  - PSP（PPSSPP）与 NDS（Drastic）的金手指开关此前已在核心设置面板
 *    （ppsspp_cheats / Drastic 金手指），不在本面板重复。
 */

// ---------------------------------------------------------------------------
// FBNeo cheat ini 解析（官方金手指包 finalburnneo/FBNeo-cheats 的 .ini 格式）
// ---------------------------------------------------------------------------

/** 单个金手指条目。[num] = 文件里的声明序号（0 起）—— 核心选项 key 的组成部分。 */
data class CheatEntry(
    val name: String,
    val options: List<String>,
    val default: Int,
    val num: Int
)

/** 引号内文本提取（跳过转义处理 —— 官方包不含转义引号）。 */
private fun quotedToken(line: String, startIdx: Int): Pair<String, Int>? {
    val open = line.indexOf('"', startIdx)
    if (open < 0) return null
    val close = line.indexOf('"', open + 1)
    if (close < 0) return null
    return line.substring(open + 1, close) to close + 1
}

/**
 * 解析 FBNeo cheat ini。与核心（burner/config.cpp 的 loader + libretro.cpp
 * create_variables_from_cheats）保持同一计数规则：每个 cheat 声明占一个
 * num（无论有无选项），选项按声明行序取 0..n。
 */
fun parseFbnCheatIni(file: File): List<CheatEntry> {
    if (!file.isFile) return emptyList()
    val entries = ArrayList<CheatEntry>()
    var current: CheatEntry? = null
    val optionNames = ArrayList<String>()
    try {
        file.readLines().forEach { raw ->
            val line = raw.trim()
            when {
                line.startsWith("cheat ") || line == "cheat" -> {
                    // 上一条收尾（cheat 声明 = 新条目开始）
                    current?.let {
                        if (it.options.isEmpty() && optionNames.isNotEmpty()) {
                            // 不可能分支，防御性保留
                        }
                        entries.add(it.copy(options = optionNames.toList()))
                    }
                    optionNames.clear()
                    val name = quotedToken(line, 5)?.first ?: line.substringAfter("cheat").trim()
                    current = CheatEntry(
                        name = name,
                        options = emptyList(),
                        default = 0,
                        num = entries.size
                    )
                }
                line.startsWith("default") -> {
                    val d = line.substringAfter("default").trim().toIntOrNull()
                    if (d != null) current = current?.copy(default = d)
                }
                else -> {
                    // N "选项文本" [, cpu, addr, val ...]
                    val numPart = line.substringBefore('"').trim()
                    val idx = numPart.toIntOrNull()
                    if (idx != null && numPart.isNotEmpty()) {
                        val text = quotedToken(line, numPart.length)?.first
                        if (text != null) {
                            // 选项下标必须与声明序一致（0..n），防御：按下标对位
                            while (optionNames.size <= idx) optionNames.add("")
                            optionNames[idx] = text
                        }
                    }
                }
            }
        }
        current?.let { entries.add(it.copy(options = optionNames.toList())) }
    } catch (_: Throwable) {
        // 解析中断：返回已解析部分（坏行不影响其余条目）
    }
    return entries.filter { it.options.isNotEmpty() }
}

/** 核心选项 key 的条目名变换（libretro.cpp：' '='=' ':' '#' → '_'）。 */
private fun cheatKey(name: String): String =
    name.replace(' ', '_').replace('=', '_').replace(':', '_').replace('#', '_')

/** FBNeo cheat 选项 key：fbneo-cheat-<num>-<驱动名>-<条目名>。 */
private fun fbnCheatKey(driver: String, entry: CheatEntry) =
    "fbneo-cheat-${entry.num}-$driver-${cheatKey(entry.name)}"

// ---------------------------------------------------------------------------
// ★★ v71 核心注册的选项定义（金手指 / PGM2 插卡的真实清单 —— 上游
//   create_variables_from_cheats + retro_pgm2_cards 经 SET_CORE_OPTIONS 注册，
//   fbneo_loader v71 起捕获。以此为准，前端不再依赖 ini 解析猜清单。）
// ---------------------------------------------------------------------------

/** 核心注册的单个选项定义。values.first = 提交给核心的值（必须原样回传），second = 显示文本。 */
data class FbnCoreOption(
    val key: String,
    val desc: String,
    val default: String,
    val values: List<Pair<String, String>>
)

private fun parseFbnCoreOptions(json: String?): List<FbnCoreOption> {
    if (json.isNullOrBlank()) return emptyList()
    val out = ArrayList<FbnCoreOption>()
    try {
        val arr = org.json.JSONArray(json)
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val key = o.optString("key")
            if (key.isEmpty()) continue
            val vals = ArrayList<Pair<String, String>>()
            val va = o.optJSONArray("values")
            if (va != null) {
                for (j in 0 until va.length()) {
                    val v = va.optJSONObject(j) ?: continue
                    val vv = v.optString("v")
                    if (vv.isEmpty()) continue
                    vals.add(vv to v.optString("l", vv))
                }
            }
            if (vals.isEmpty()) continue
            out.add(FbnCoreOption(key, o.optString("desc"), o.optString("default"), vals))
        }
    } catch (_: Throwable) {}
    return out
}

/** PGM2 记忆卡槽选项（fbneo-pgm2-ic-p1..pN；非插卡游戏 = 空）。 */
private fun fbnCardSlotOptions(engine: FbNeoEngine): List<FbnCoreOption> =
    parseFbnCoreOptions(engine.cheatOptionsJson())
        .filter { it.key.startsWith("fbneo-pgm2-ic-") }
        .sortedBy { it.key }

/** 卡槽选项值分组：固定选择（empty/default/temporary/new/latest_new_card_file）与卡文件（绝对路径）。 */
private fun splitFbnCardValues(opt: FbnCoreOption): Pair<List<Pair<String, String>>, List<Pair<String, String>>> {
    val fixed = ArrayList<Pair<String, String>>()
    val files = ArrayList<Pair<String, String>>()
    for (v in opt.values) {
        if (v.first.startsWith("/")) files.add(v) else fixed.add(v)
    }
    return fixed to files
}

// ---------------------------------------------------------------------------
// 主面板
// ---------------------------------------------------------------------------

@Composable
fun CheatsDialog(
    engine: Any?,
    gameTitle: String,
    onDismiss: () -> Unit,
    // ★★ v69：RAM 金手指用的每游戏稳定键（game.id）+ 平台（地址归一化）。
    cheatKey: String = "",
    platform: GamePlatform = GamePlatform.NES
) {
    val isPgm3 = engine is Pgm3Engine
    val isFbn = engine is FbNeoEngine
    val isAfbn = engine is AfbnEngine
    val isCemu = engine is CemuEngine
    val isRamCheat = engine is RamCheatEngine
    var tab by remember { mutableStateOf(0) } // PGM3: 0=金手指 1=插卡；FBN/afbn 同构
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("关闭") }
        },
        title = {
            Column {
                Text(
                    if (isPgm3 || (isFbn && tab == 1) || (isAfbn && tab == 1)) "金手指 / 插卡" else "金手指",
                    fontSize = 18.sp, fontWeight = FontWeight.SemiBold
                )
                Text(gameTitle, fontSize = 12.sp, color = Color(0xFF8FA0B5), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        },
        text = {
            Column {
                if (isPgm3) {
                    TabRowLite(0, 1, tab) { tab = it }
                    Spacer(Modifier.width(0.dp))
                    when (tab) {
                        0 -> Pgm3CheatsContent(engine as Pgm3Engine)
                        else -> Pgm3CardsContent(engine as Pgm3Engine)
                    }
                } else if (isFbn) {
                    val e = engine as FbNeoEngine
                    // ★ v71：FBN 核心注册了 fbneo-pgm2-ic-* 选项 = 该驱动有记忆卡槽
                    val cardSlots = remember(e.currentDriverName) { fbnCardSlotOptions(e) }
                    if (cardSlots.isEmpty()) {
                        FbnCheatsContent(e)
                    } else {
                        TabRowLite(0, 1, tab) { tab = it }
                        when (tab) {
                            0 -> FbnCheatsContent(e)
                            else -> FbnCardsContent(e, cardSlots)
                        }
                    }
                } else if (isAfbn) {
                    val e = engine as AfbnEngine
                    val supported = remember(e.hashCode()) { try { e.pgm2CardSupported() && e.pgm2MaxCardSlots() > 0 } catch (_: Throwable) { false } }
                    if (!supported) {
                        AfbnCheatsContent(e)
                    } else {
                        TabRowLite(0, 1, tab) { tab = it }
                        when (tab) {
                            0 -> AfbnCheatsContent(e)
                            else -> AfbnCardsContent(e)
                        }
                    }
                } else if (isCemu) {
                    CemuCheatsContent(engine as CemuEngine)
                } else if (isRamCheat) {
                    RamCheatsContent(engine as RamCheatEngine, cheatKey, platform)
                } else {
                    Text(
                        "本核心暂无专用金手指面板。\n\n已支持的核心（同在本面板）：\n" +
                            "· 8/16 位主机（NES/SFC/GB/GBA/MD/PCE/PSX/DC/NDS）—— RetroArch .cht 金手指，" +
                            "支持添加/编辑/删除，每帧补丁热生效\n" +
                            "· 街机（FBN）—— 核心清单（cheat.dat/ini 全量，热生效）+ ini 编辑（增/改/删）；" +
                            "PGM2 游戏（三国战纪2/西游释厄传2/三国战纪3）另有插卡页\n" +
                            "· 改版街机（afbn 核心：三剑圣等 666 改版）—— 核心原生金手指 + PGM2 插卡\n" +
                            "· 三国战记3HD（PGM3）—— 金手指 + 虚拟插卡（IC 卡）\n" +
                            "· Wii U（Cemu）—— 游戏规则 txt 清单开关\n" +
                            "PSP / NDS(DraStic) 的金手指开关在 设置→核心设置。",
                        fontSize = 13.sp, lineHeight = 19.sp
                    )
                }
            }
        }
    )
}

@Composable
private fun TabRowLite(a: Int, b: Int, sel: Int, onSelect: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf("金手指" to a, "插卡（记忆卡）" to b).forEach { (label, idx) ->
            Box(
                Modifier
                    .background(
                        if (sel == idx) Color(0xFF2F6FB2) else Color(0x338090A0),
                        RoundedCornerShape(8.dp)
                    )
                    .clickable { onSelect(idx) }
                    .padding(horizontal = 14.dp, vertical = 6.dp)
            ) {
                Text(label, color = Color.White, fontSize = 13.sp)
            }
        }
    }
}

@Composable
private fun MsgLine(text: String?) = text?.let {
    Text(it, color = Color(0xFFE8A33D), fontSize = 12.sp, lineHeight = 16.sp)
}

// ---------------------------------------------------------------------------
// 街机（FBNeo）金手指
// ---------------------------------------------------------------------------

/** 16进制/十进制通用解析（支持 0x / $ 前缀）。null = 无法解析。 */
private fun cheatHexOrDec(s: String): Long = try {
    val t = s.trim()
    when {
        t.startsWith("0x") || t.startsWith("0X") -> t.substring(2).toLong(16)
        t.startsWith("$") -> t.substring(1).toLong(16)
        t.isNotEmpty() -> t.toLong()
        else -> -1L
    }
} catch (_: Throwable) { -1L }

@Composable
private fun FbnCheatsContent(engine: FbNeoEngine) {
    val driver = engine.currentDriverName
    val cheatsDir = remember(engine.systemDirPath) {
        File(File(engine.systemDirPath, "fbneo"), "cheats").apply { mkdirs() }
    }
    val iniFile = remember(cheatsDir, driver) { File(cheatsDir, "$driver.ini") }

    // ★★ v71 双模式：
    //  · 核心清单（默认）—— fbneo_loader v71 起捕获核心经 SET_CORE_OPTIONS 注册的
    //    fbneo-cheat-* 定义。这是核心【实际加载】的金手指（ini + cheat.dat +
    //    父集回退 + include 全算入），与"只显示一条"的前端 ini 猜测彻底告别。
    //  · ini 编辑模式 —— 增/改/删条目写回 <driver>.ini（重启游戏生效）。
    val coreOptions = remember(driver) {
        parseFbnCoreOptions(engine.cheatOptionsJson()).filter { it.key.startsWith("fbneo-cheat-") }
    }
    var iniMode by remember(iniFile) { mutableStateOf(coreOptions.isEmpty()) }

    Row(Modifier.fillMaxWidth().padding(bottom = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(
            Modifier
                .background(if (!iniMode) Color(0xFF2F6FB2) else Color(0x338090A0), RoundedCornerShape(8.dp))
                .clickable { iniMode = false }
                .padding(horizontal = 12.dp, vertical = 5.dp)
        ) { Text("核心清单（热生效）", color = Color.White, fontSize = 12.sp) }
        Box(
            Modifier
                .background(if (iniMode) Color(0xFF2F6FB2) else Color(0x338090A0), RoundedCornerShape(8.dp))
                .clickable { iniMode = true }
                .padding(horizontal = 12.dp, vertical = 5.dp)
        ) { Text("ini 编辑", color = Color.White, fontSize = 12.sp) }
    }

    if (iniMode) {
        FbnIniCheatsContent(engine, cheatsDir, iniFile)
    } else {
        FbnCoreCheatsContent(engine, coreOptions, driver)
    }
}

/** 核心清单模式：选项即核心注册的 fbneo-cheat-*，值串必须原样回传（apply_cheats_from_variables 精确匹配）。 */
@Composable
private fun FbnCoreCheatsContent(engine: FbNeoEngine, coreOptions: List<FbnCoreOption>, driver: String) {
    val context = LocalContext.current
    var selected by remember(coreOptions, driver) { mutableStateOf<Map<String, Int>>(emptyMap()) }
    var info by remember { mutableStateOf<String?>(null) }
    var expandedKey by remember(coreOptions) { mutableStateOf<String?>(null) }

    // 打开时回放上次选择（持久化键 = 核心选项 key，与 ini 模式共享 "fbn_cheats"）
    LaunchedEffect(coreOptions, driver) {
        if (coreOptions.isEmpty()) return@LaunchedEffect
        val map = HashMap<String, Int>()
        try {
            val json = JSONObject(
                context.getSharedPreferences("fbn_cheats", android.content.Context.MODE_PRIVATE)
                    .getString(driver, "{}") ?: "{}"
            )
            for (o in coreOptions) {
                val v = json.optString(o.key, "")
                if (v.isNotEmpty()) {
                    val i = o.values.indexOfFirst { it.first == v }
                    if (i >= 0) {
                        map[o.key] = i
                        engine.setCoreOption(o.key, v)
                    }
                }
            }
        } catch (_: Throwable) {}
        selected = map
    }

    fun persist(o: FbnCoreOption, i: Int) {
        try {
            val prefs = context.getSharedPreferences("fbn_cheats", android.content.Context.MODE_PRIVATE)
            val json = JSONObject(prefs.getString(driver, "{}") ?: "{}")
            json.put(o.key, o.values[i].first)
            prefs.edit().putString(driver, json.toString()).apply()
        } catch (_: Throwable) {}
    }

    if (coreOptions.isEmpty()) {
        Text(
            "核心没有注册本游戏的金手指选项。\n\n" +
                "两种可能：\n" +
                "1. 未放金手指文件 —— 切到「ini 编辑」导入官方 $driver.ini（支持 cheat \"名\" / default N / N \"选项\", cpu, 地址, 值…），导入后重启游戏；\n" +
                "2. 该游戏没有官方金手指。\n\n" +
                "核心会同时查找：cheat.dat（MAME 格式）、<驱动>.ini、<驱动>.dat、父 ROM 的同名文件。",
            fontSize = 13.sp, lineHeight = 19.sp
        )
        return
    }

    fun displayName(o: FbnCoreOption): String {
        // 上游 friendly_name = "[Cheat][<file>] <heading><name>" —— 去掉前缀直接显示条目名
        var d = o.desc
        if (d.startsWith("[Cheat][")) {
            val close = d.indexOf("] ")
            if (close >= 0) d = d.substring(close + 2)
        }
        return d.ifBlank { o.key }
    }

    LazyColumn(
        modifier = Modifier.fillMaxWidth().heightIn(max = 360.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        itemsIndexed(coreOptions, key = { _, o -> o.key }) { _, o ->
            val selIdx = selected[o.key] ?: o.values.indexOfFirst { it.first == o.default }.takeIf { it >= 0 } ?: 0
            val expanded = expandedKey == o.key
            Column(
                Modifier.fillMaxWidth()
                    .background(Color(0x14203040), RoundedCornerShape(8.dp))
                    .clickable { expandedKey = if (expanded) null else o.key }
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(displayName(o), fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(
                            "选项 ${selIdx + 1}/${o.values.size}：${o.values.getOrNull(selIdx)?.second ?: "?"}" +
                                if (expanded) "（点此收起）" else "（点击展开全部 ${o.values.size} 个选项）",
                            fontSize = 11.sp, color = Color(0xFF8FA0B5), maxLines = 1, overflow = TextOverflow.Ellipsis
                        )
                    }
                    TextButton(onClick = {
                        val next = (selIdx + 1) % o.values.size
                        selected = selected + (o.key to next)
                        engine.setCoreOption(o.key, o.values[next].first)
                        persist(o, next)
                    }) { Text("切换", fontSize = 12.sp) }
                }
                if (expanded) {
                    o.values.forEachIndexed { vi, (vv, vl) ->
                        Row(
                            Modifier.fillMaxWidth()
                                .clickable {
                                    selected = selected + (o.key to vi)
                                    engine.setCoreOption(o.key, vv)
                                    persist(o, vi)
                                }
                                .padding(start = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(selected = vi == selIdx, onClick = {
                                selected = selected + (o.key to vi)
                                engine.setCoreOption(o.key, vv)
                                persist(o, vi)
                            })
                            Text(vl.ifBlank { vv }, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }
    }
    Spacer(Modifier.padding(2.dp))
    TextButton(onClick = {
        val json = JSONObject()
        coreOptions.forEach { o ->
            engine.setCoreOption(o.key, o.default)
            json.put(o.key, o.default)
        }
        try {
            context.getSharedPreferences("fbn_cheats", android.content.Context.MODE_PRIVATE)
                .edit().putString(driver, json.toString()).apply()
        } catch (_: Throwable) {}
        selected = coreOptions.associate { o ->
            o.key to (o.values.indexOfFirst { it.first == o.default }.takeIf { it >= 0 } ?: 0)
        }
        info = "已全部恢复默认（${coreOptions.size} 项）"
    }) {
        Icon(Icons.Rounded.Refresh, null, Modifier.width(16.dp))
        Text("全部恢复默认", fontSize = 12.sp)
    }
    MsgLine(info)
    Text(
        "清单来自核心（含 cheat.dat / ini / 父集回退的全部条目）。切换立即热生效，0 号选项通常为 Disabled。" +
            "要新增/修改金手指请切到「ini 编辑」。",
        fontSize = 11.sp, color = Color(0xFF8FA0B5), lineHeight = 15.sp
    )
}

/** ini 分块读取：返回 (前导文本, 每个 cheat 声明一个块)。 */
private fun readFbnIniBlocks(file: File): Pair<String, List<String>> {
    if (!file.isFile) return "" to emptyList()
    val preamble = StringBuilder()
    val blocks = ArrayList<String>()
    var cur: StringBuilder? = null
    for (line in file.readLines()) {
        val t = line.trimStart()
        if (t.startsWith("cheat ") || t == "cheat") {
            cur?.let { blocks.add(it.toString()) }
            cur = StringBuilder(line).append('\n')
        } else {
            val c = cur
            if (c != null) c.append(line).append('\n') else preamble.append(line).append('\n')
        }
    }
    cur?.let { blocks.add(it.toString()) }
    return preamble.toString() to blocks
}

private fun writeFbnIniBlocks(file: File, preamble: String, blocks: List<String>) {
    file.parentFile?.mkdirs()
    val sb = StringBuilder(preamble)
    for (b in blocks) {
        sb.append(b)
        if (!b.endsWith("\n")) sb.append('\n')
    }
    file.writeText(sb.toString())
}

/** ini 编辑模式：v70 的清单/导入/新增 + v71 的逐条编辑与删除。 */
@Composable
private fun FbnIniCheatsContent(engine: FbNeoEngine, cheatsDir: File, iniFile: File) {
    val context = LocalContext.current
    val driver = engine.currentDriverName
    var entries by remember(iniFile) { mutableStateOf(parseFbnCheatIni(iniFile)) }
    var selected by remember(iniFile) { mutableStateOf<Map<Int, Int>>(emptyMap()) }
    var info by remember { mutableStateOf<String?>(null) }
    var expandedNum by remember(iniFile) { mutableStateOf<Int?>(null) }
    var showAddEntry by remember { mutableStateOf(false) }
    // ★ v71 编辑/删除
    var editBlockIdx by remember { mutableStateOf<Int?>(null) }

    // 打开时回放上次选择（跨启动持久化在 "fbn_cheats"）
    LaunchedEffect(iniFile, driver) {
        if (entries.isEmpty()) return@LaunchedEffect
        val map = HashMap<Int, Int>()
        try {
            val json = JSONObject(
                context.getSharedPreferences("fbn_cheats", android.content.Context.MODE_PRIVATE)
                    .getString(driver, "{}") ?: "{}"
            )
            for (e in entries) {
                val v = json.optString(fbnCheatKey(driver, e), "")
                if (v.isNotEmpty()) {
                    val i = v.substringBefore(" - ").trim().toIntOrNull()
                    if (i != null && i in e.options.indices) {
                        map[e.num] = i
                        engine.setCoreOption(fbnCheatKey(driver, e), v)
                    }
                }
            }
        } catch (_: Throwable) {}
        selected = map
    }

    fun persist(e: CheatEntry, i: Int) {
        try {
            val prefs = context.getSharedPreferences("fbn_cheats", android.content.Context.MODE_PRIVATE)
            val json = JSONObject(prefs.getString(driver, "{}") ?: "{}")
            json.put(fbnCheatKey(driver, e), "$i - ${e.options[i]}")
            prefs.edit().putString(driver, json.toString()).apply()
        } catch (_: Throwable) {}
    }

    if (driver.isBlank()) {
        Text("游戏未加载，无法读取金手指。", fontSize = 13.sp)
        return
    }

    if (entries.isEmpty()) {
        Text(
            "未找到 ${driver} 的金手指文件。\n\n" +
                "把官方金手指包的 $driver.ini 放到（可用下方按钮导入）：\n" +
                "${iniFile.absolutePath}\n\n" +
                "支持 FBNeo 金手指 ini 格式（cheat \"名\" / default N / N \"选项\", cpu, 地址, 值…）。",
            fontSize = 13.sp, lineHeight = 19.sp
        )
        ImportCheatFileButton(cheatsDir, driver) { ok ->
            info = if (ok) "已导入：$driver.ini（重新打开本面板生效）" else "导入失败"
            if (ok) entries = parseFbnCheatIni(iniFile)
        }
        MsgLine(info)
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxWidth().heightIn(max = 360.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        itemsIndexed(entries, key = { _, e -> e.num }) { _, e ->
            val selIdx = selected[e.num] ?: e.default
            val expanded = expandedNum == e.num
            Column(
                Modifier.fillMaxWidth()
                    .background(Color(0x14203040), RoundedCornerShape(8.dp))
                    .clickable { expandedNum = if (expanded) null else e.num }
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(e.name, fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(
                            "选项 ${selIdx.coerceIn(0, (e.options.size - 1).coerceAtLeast(0)) + 1}/${e.options.size}：${e.options.getOrElse(selIdx) { "?" }}" +
                                if (expanded) "（点此收起）" else "（点击展开全部 ${e.options.size} 个选项）",
                            fontSize = 11.sp, color = Color(0xFF8FA0B5), maxLines = 1, overflow = TextOverflow.Ellipsis
                        )
                    }
                    TextButton(onClick = {
                        val next = (selIdx + 1) % e.options.size
                        selected = selected + (e.num to next)
                        val v = "$next - ${e.options[next]}"
                        engine.setCoreOption(fbnCheatKey(driver, e), v)
                        persist(e, next)
                    }) { Text("切换", fontSize = 12.sp) }
                }
                if (expanded) {
                    e.options.forEachIndexed { oi, ot ->
                        Row(
                            Modifier.fillMaxWidth()
                                .clickable {
                                    selected = selected + (e.num to oi)
                                    val v = "$oi - $ot"
                                    engine.setCoreOption(fbnCheatKey(driver, e), v)
                                    persist(e, oi)
                                }
                                .padding(start = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(selected = oi == selIdx, onClick = {
                                selected = selected + (e.num to oi)
                                val v = "$oi - $ot"
                                engine.setCoreOption(fbnCheatKey(driver, e), v)
                                persist(e, oi)
                            })
                            Text(ot, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    // ★ v71 编辑 / 删除（写回 ini，重启游戏后核心加载新定义）
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        TextButton(onClick = { editBlockIdx = entries.indexOfFirst { it.num == e.num }.takeIf { it >= 0 } }) {
                            Text("编辑", fontSize = 11.sp)
                        }
                        TextButton(onClick = {
                            try {
                                val (pre, blocks) = readFbnIniBlocks(iniFile)
                                val idx = entries.indexOfFirst { it.num == e.num }
                                if (idx in blocks.indices) {
                                    val nb = blocks.toMutableList()
                                    nb.removeAt(idx)
                                    writeFbnIniBlocks(iniFile, pre, nb)
                                    entries = parseFbnCheatIni(iniFile)
                                    info = "已删除「${e.name}」—— 重启游戏后生效"
                                }
                            } catch (t: Throwable) { info = "删除失败：${t.message}" }
                        }) { Text("删除", fontSize = 11.sp, color = Color(0xFFD06050)) }
                    }
                }
            }
        }
    }
    Spacer(Modifier.padding(2.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        TextButton(onClick = {
            val json = JSONObject()
            entries.forEach { e ->
                val v = "${e.default} - ${e.options.getOrElse(e.default) { "" }}"
                engine.setCoreOption(fbnCheatKey(driver, e), v)
                json.put(fbnCheatKey(driver, e), v)
            }
            try {
                context.getSharedPreferences("fbn_cheats", android.content.Context.MODE_PRIVATE)
                    .edit().putString(driver, json.toString()).apply()
            } catch (_: Throwable) {}
            selected = entries.associate { it.num to it.default }
            info = "已全部恢复默认（${entries.size} 项）"
        }) {
            Icon(Icons.Rounded.Refresh, null, Modifier.width(16.dp))
            Text("全部恢复默认", fontSize = 12.sp)
        }
        ImportCheatFileButton(cheatsDir, driver) { ok ->
            info = if (ok) "已导入：$driver.ini（重新打开本面板生效）" else "导入失败"
            if (ok) entries = parseFbnCheatIni(iniFile)
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        TextButton(onClick = { showAddEntry = true }) {
            Icon(Icons.Rounded.Add, null, Modifier.width(16.dp))
            Text("添加自定义条目", fontSize = 12.sp)
        }
    }
    MsgLine(info)
    Text(
        "ini 编辑模式：新增/修改/删除写回 $driver.ini，重启游戏后由核心加载。" +
            "要热生效的开关请切回「核心清单」。",
        fontSize = 11.sp, color = Color(0xFF8FA0B5), lineHeight = 15.sp
    )

    // ★ v70 追加自定义条目对话框（FBNeo 官方 ini 语法）
    if (showAddEntry) {
        var enName by remember { mutableStateOf("自定义金手指") }
        var enAddr by remember { mutableStateOf("") }
        var enVal by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showAddEntry = false },
            confirmButton = {
                TextButton(onClick = {
                    val a = cheatHexOrDec(enAddr)
                    val v = cheatHexOrDec(enVal)
                    if (a < 0 || v < 0 || a > 0xFFFFFFL || v > 0xFFL) {
                        info = "地址需 0x0–0xFFFFFF，值需 0x0–0xFF（示例：地址 0x815D70，值 0x64）"
                        showAddEntry = false
                        return@TextButton
                    }
                    try {
                        iniFile.parentFile?.mkdirs()
                        val add = StringBuilder()
                        if (iniFile.isFile && iniFile.length() > 0) {
                            val cur = iniFile.readText()
                            if (!cur.endsWith("\n")) add.append('\n')
                        }
                        add.append("cheat \"${enName.trim().ifBlank { "自定义金手指" }}\"\n")
                        add.append("default 0\n")
                        add.append("0 \"Disabled\"\n")
                        add.append("1 \"启用\", 0, 0x${a.toString(16)}, 0x${v.toString(16)}\n")
                        iniFile.appendText(add.toString())
                        entries = parseFbnCheatIni(iniFile)
                        info = "已追加「${enName.trim()}」—— 重启游戏后核心加载新条目"
                    } catch (t: Throwable) {
                        info = "写入失败：${t.message}"
                    }
                    showAddEntry = false
                }) { Text("写入 ini") }
            },
            dismissButton = { TextButton(onClick = { showAddEntry = false }) { Text("取消") } },
            title = { Text("添加自定义金手指（FBNeo）", fontSize = 16.sp) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        "追加到 $driver.ini：启用时向内存地址写入固定值（8 位写）。",
                        fontSize = 12.sp, color = Color(0xFF8FA0B5), lineHeight = 16.sp
                    )
                    OutlinedTextField(
                        value = enName, onValueChange = { enName = it },
                        label = { Text("名称") }, singleLine = true
                    )
                    OutlinedTextField(
                        value = enAddr, onValueChange = { enAddr = it },
                        label = { Text("内存地址（如 0x815D70）") }, singleLine = true
                    )
                    OutlinedTextField(
                        value = enVal, onValueChange = { enVal = it },
                        label = { Text("写入值（如 0x64 = 100）") }, singleLine = true
                    )
                }
            }
        )
    }

    // ★ v71 编辑既有条目对话框（原文编辑选项行，最大限度保留语法）
    editBlockIdx?.let { idx ->
        val blocks = remember(idx) { readFbnIniBlocks(iniFile).second }
        val block = blocks.getOrNull(idx)
        if (block == null) {
            editBlockIdx = null
        } else {
            // 从块中解析出名称 / default / 选项行
            val lines = block.lines().filter { it.isNotBlank() }
            val nameLine = lines.firstOrNull { it.trimStart().startsWith("cheat") } ?: ""
            val curName = quotedToken(nameLine, nameLine.indexOf('"').coerceAtLeast(0))?.first ?: ""
            val defLine = lines.firstOrNull { it.trimStart().startsWith("default") }
            val curDefault = defLine?.substringAfter("default")?.trim() ?: "0"
            val optionLines = lines.filter { it !== nameLine && it !== defLine }
                .filter { it.trimStart().firstOrNull()?.isDigit() == true }
            var edName by remember(idx) { mutableStateOf(curName) }
            var edDefault by remember(idx) { mutableStateOf(curDefault) }
            var edLines by remember(idx) { mutableStateOf(optionLines.joinToString("\n")) }
            AlertDialog(
                onDismissRequest = { editBlockIdx = null },
                confirmButton = {
                    TextButton(onClick = {
                        val name = edName.trim().ifBlank { "未命名" }
                        val def = edDefault.trim().toIntOrNull()?.coerceIn(0, 999) ?: 0
                        val body = edLines.trim()
                        if (body.isEmpty()) {
                            info = "选项行不能为空（每行格式：N \"选项文本\", cpu, 地址, 值）"
                            return@TextButton
                        }
                        try {
                            val (pre, blks) = readFbnIniBlocks(iniFile)
                            val nb = blks.toMutableList()
                            val newBlock = buildString {
                                append("cheat \"").append(name).append("\"\n")
                                append("default ").append(def).append('\n')
                                append(body).append('\n')
                            }
                            if (idx in nb.indices) nb[idx] = newBlock else nb.add(newBlock)
                            writeFbnIniBlocks(iniFile, pre, nb)
                            entries = parseFbnCheatIni(iniFile)
                            info = "已保存「$name」—— 重启游戏后生效"
                            editBlockIdx = null
                        } catch (t: Throwable) { info = "保存失败：${t.message}" }
                    }) { Text("保存") }
                },
                dismissButton = { TextButton(onClick = { editBlockIdx = null }) { Text("取消") } },
                title = { Text("编辑金手指条目", fontSize = 16.sp) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            "写回 $driver.ini 后重启游戏生效。选项行每行一条：N \"文本\", cpu, 地址, 值（十六进制需 0x 前缀）。",
                            fontSize = 11.sp, color = Color(0xFF8FA0B5), lineHeight = 15.sp
                        )
                        OutlinedTextField(
                            value = edName, onValueChange = { edName = it },
                            label = { Text("条目名称") }, singleLine = true
                        )
                        OutlinedTextField(
                            value = edDefault, onValueChange = { edDefault = it },
                            label = { Text("默认选项号（0 = 关闭）") }, singleLine = true
                        )
                        OutlinedTextField(
                            value = edLines, onValueChange = { edLines = it },
                            label = { Text("选项行（每行一条）") }, minLines = 3, maxLines = 6
                        )
                    }
                }
            )
        }
    }
}

// ---------------------------------------------------------------------------
// ★★ v71 街机（FBNeo 核心）PGM2 记忆卡 —— 核心选项 fbneo-pgm2-ic-p1..4 驱动。
//   上游 retro_pgm2_cards.cpp：卡目录 <saveDir>/fbneo/pgm2_memcards/，默认卡
//   <drv>_pN_default.pg2 自动建；"new" 核心自产时间戳卡并 SET_VARIABLE 回
//   latest_new_card_file；卡文件接受 .pg2/.bin（≤120 个/槽）。选卡后需在
//   进入选人画面之前生效。
// ---------------------------------------------------------------------------

@Composable
private fun FbnCardsContent(engine: FbNeoEngine, cardSlots: List<FbnCoreOption>) {
    val context = LocalContext.current
    var selected by remember(cardSlots) { mutableStateOf<Map<String, String>>(emptyMap()) }
    var info by remember { mutableStateOf<String?>(null) }
    var pickFor by remember { mutableStateOf<String?>(null) }

    val cardDir = remember(engine.saveDirPath) {
        engine.saveDirPath.takeIf { it.isNotBlank() }?.let { File(File(it, "fbneo"), "pgm2_memcards") }
    }

    // 回放持久化选择
    LaunchedEffect(cardSlots) {
        val map = HashMap<String, String>()
        try {
            val prefs = context.getSharedPreferences("fbn_cards", android.content.Context.MODE_PRIVATE)
            for (o in cardSlots) {
                val v = prefs.getString(o.key, null) ?: continue
                if (o.values.any { it.first == v }) {
                    map[o.key] = v
                    engine.setCoreOption(o.key, v)
                }
            }
        } catch (_: Throwable) {}
        selected = map
    }

    fun persist(key: String, v: String) {
        try {
            context.getSharedPreferences("fbn_cards", android.content.Context.MODE_PRIVATE)
                .edit().putString(key, v).apply()
        } catch (_: Throwable) {}
    }

    fun apply(opt: FbnCoreOption, v: String) {
        engine.setCoreOption(opt.key, v)
        persist(opt.key, v)
        selected = selected + (opt.key to v)
        info = when {
            v == "new" -> "已建新卡 —— 核心自动切换到「最新新卡文件」并插入"
            v == "empty" -> "已弹出（空槽）"
            v == "temporary" -> "使用临时卡（内存卡，退出即失效）"
            v.startsWith("/") -> "已选卡：${File(v).name}"
            else -> "已应用：$v"
        }
    }

    Column(Modifier.fillMaxWidth()) {
        Text(
            "PGM2 基板 IC 卡（三国战纪2群雄争霸 / 西游释厄传2 / 三国战纪3 等）。" +
                (cardDir?.let { "\n卡文件目录：${it.absolutePath}" } ?: ""),
            fontSize = 12.sp, color = Color(0xFF8FA0B5), lineHeight = 16.sp
        )
        Spacer(Modifier.padding(2.dp))
        for (opt in cardSlots) {
            val (fixed, files) = splitFbnCardValues(opt)
            val slotLabel = opt.key.substringAfterLast("fbneo-pgm2-ic-").uppercase()
            val cur = selected[opt.key] ?: opt.default
            Column(
                Modifier.fillMaxWidth()
                    .background(Color(0x14203040), RoundedCornerShape(10.dp))
                    .padding(8.dp)
            ) {
                Text(
                    "$slotLabel 记忆卡 —— " + when {
                        cur == "empty" -> "空槽"
                        cur == "temporary" -> "临时卡"
                        cur == "default" -> "默认卡"
                        cur == "new" -> "正在建卡…"
                        cur == "latest_new_card_file" -> "最新新卡"
                        cur.startsWith("/") -> File(cur).name
                        else -> cur
                    },
                    fontSize = 13.sp, fontWeight = FontWeight.SemiBold
                )
                Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    TextButton(onClick = { apply(opt, "empty") }) { Text("空槽", fontSize = 11.sp) }
                    TextButton(onClick = { apply(opt, "default") }) { Text("默认卡", fontSize = 11.sp) }
                    TextButton(onClick = { apply(opt, "temporary") }) { Text("临时卡", fontSize = 11.sp) }
                    TextButton(onClick = { apply(opt, "new") }) { Text("建卡", fontSize = 11.sp) }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    TextButton(onClick = { apply(opt, "latest_new_card_file") }) { Text("最新新卡", fontSize = 11.sp) }
                    TextButton(onClick = { pickFor = opt.key }) { Text("选卡（${files.size}）", fontSize = 11.sp) }
                }
            }
            Spacer(Modifier.padding(2.dp))
        }
        MsgLine(info)
        Text(
            "核心在每次切换时自动保存当前卡。新建的卡要等下次启动才会进「选卡」列表（本局用「最新新卡」即可）。" +
                "建议在选人画面之前完成选卡。",
            fontSize = 11.sp, color = Color(0xFF8FA0B5), lineHeight = 15.sp
        )
    }

    pickFor?.let { key ->
        val opt = cardSlots.firstOrNull { it.key == key } ?: run { pickFor = null; null }
        if (opt != null) {
            val (_, files) = splitFbnCardValues(opt)
            AlertDialog(
                onDismissRequest = { pickFor = null },
                confirmButton = { TextButton(onClick = { pickFor = null }) { Text("关闭") } },
                title = { Text("选择 ${opt.key.substringAfterLast("fbneo-pgm2-ic-").uppercase()} 记忆卡", fontSize = 16.sp) },
                text = {
                    if (files.isEmpty()) {
                        Text("还没有卡文件 —— 点「建卡」新建，或「默认卡」用核心自建卡。", fontSize = 13.sp)
                    } else {
                        LazyColumn(Modifier.heightIn(max = 280.dp)) {
                            itemsIndexed(files) { _, (path, label) ->
                                Row(
                                    Modifier.fillMaxWidth().clickable {
                                        apply(opt, path)
                                        pickFor = null
                                    }.padding(horizontal = 4.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Checkbox(checked = (selected[opt.key] ?: opt.default) == path, onCheckedChange = null)
                                    Text(label.ifBlank { File(path).name }, fontSize = 13.sp)
                                }
                            }
                        }
                    }
                }
            )
        }
    }
}

// ---------------------------------------------------------------------------
// ★★ v71 afbn 核心（改版街机）金手指 —— libarcade.so 原生金手指引擎，
//   nativeCheatGetList JSON 协议（与 libpgm3 同族；解析容错）。
// ---------------------------------------------------------------------------

@Composable
private fun AfbnCheatsContent(engine: AfbnEngine) {
    var raw by remember { mutableStateOf<String?>(null) }
    var info by remember { mutableStateOf<String?>(null) }
    var expandedIdx by remember { mutableStateOf<Int?>(null) }

    fun refresh() { raw = engine.cheatListJson() }
    LaunchedEffect(Unit) { refresh() }

    // 容错解析：entries/cheats/list 数组或顶层数组；name/desc/title，options/opts/values，selected/current/cur
    val parsed = remember(raw) {
        val list = ArrayList<Triple<Int, String, List<String>>>() // (idx, name, options)
        val selMap = HashMap<Int, Int>()
        var note = ""
        val r = raw
        try {
            if (r == null || r.isBlank()) {
                note = "null"
            } else {
                val trimmed = r.trimStart()
                val arr: org.json.JSONArray? = if (trimmed.startsWith("[")) {
                    try { org.json.JSONArray(r) } catch (_: Throwable) { null }
                } else {
                    JSONObject(r).let { o ->
                        o.optJSONArray("entries") ?: o.optJSONArray("cheats")
                            ?: o.optJSONArray("list") ?: o.optJSONArray("cheatlist")
                    }
                }
                if (arr == null) {
                    note = "raw"
                } else {
                    for (i in 0 until arr.length()) {
                        val e = arr.optJSONObject(i)
                        if (e != null) {
                            val name = e.optString("name", e.optString("desc", e.optString("title", "")))
                            val opts = ArrayList<String>()
                            val oa = e.optJSONArray("options") ?: e.optJSONArray("opts") ?: e.optJSONArray("values")
                            if (oa != null) {
                                for (j in 0 until oa.length()) {
                                    val s = oa.optString(j)
                                    if (s.isNotEmpty()) opts.add(s)
                                }
                            }
                            list.add(Triple(i, name, opts))
                            selMap[i] = e.optInt("selected", e.optInt("current", e.optInt("cur", 0)))
                        } else {
                            val s = arr.optString(i)
                            if (s.isNotEmpty()) list.add(Triple(i, s, emptyList()))
                        }
                    }
                }
            }
        } catch (t: Throwable) {
            note = "error"
        }
        Triple(list, selMap, note)
    }
    val entries = parsed.first
    val selectedMap = parsed.second
    val note = parsed.third

    if (raw == null || note == "null") {
        Text(
            "核心未返回金手指清单（会话未启动或核心未加载金手指）。afbn 核心会自动查找 " +
                "cheat.dat / <驱动>.ini 等官方金手指包文件。",
            fontSize = 13.sp, lineHeight = 19.sp
        )
        return
    }
    if (note == "raw" || note == "error") {
        Text("核心返回了无法识别的清单格式：", fontSize = 13.sp)
        Text(
            raw!!.take(600),
            fontSize = 11.sp, color = Color(0xFF8FA0B5), lineHeight = 15.sp
        )
        return
    }
    if (entries.isEmpty()) {
        Text("本游戏没有金手指条目（核心返回空清单）。", fontSize = 13.sp)
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxWidth().heightIn(max = 300.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        itemsIndexed(entries) { _, (idx, name, opts) ->
            val selIdx = selectedMap[idx] ?: 0
            val expanded = expandedIdx == idx
            Column(
                Modifier.fillMaxWidth()
                    .background(Color(0x14203040), RoundedCornerShape(8.dp))
                    .clickable { expandedIdx = if (expanded) null else idx }
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(name.ifBlank { "条目 $idx" }, fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        if (opts.isNotEmpty()) {
                            Text(
                                "选项 ${selIdx.coerceIn(0, opts.size - 1) + 1}/${opts.size}：${opts.getOrElse(selIdx.coerceIn(0, (opts.size - 1).coerceAtLeast(0))) { "?" }}" +
                                    if (expanded) "（点此收起）" else "（点击展开全部 ${opts.size} 个选项）",
                                fontSize = 11.sp, color = Color(0xFF8FA0B5), maxLines = 1, overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                    if (opts.size > 1) {
                        TextButton(onClick = {
                            val next = (selIdx + 1) % opts.size
                            val ok = engine.cheatSetOption(idx, next)
                            info = if (ok) null else "应用失败（条目 $idx）"
                            refresh()
                        }) { Text("切换", fontSize = 12.sp) }
                    }
                }
                if (expanded && opts.isNotEmpty()) {
                    opts.forEachIndexed { oi, ot ->
                        Row(
                            Modifier.fillMaxWidth()
                                .clickable {
                                    val ok = engine.cheatSetOption(idx, oi)
                                    info = if (ok) null else "应用失败（条目 $idx）"
                                    refresh()
                                }
                                .padding(start = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(selected = oi == selIdx, onClick = {
                                val ok = engine.cheatSetOption(idx, oi)
                                info = if (ok) null else "应用失败（条目 $idx）"
                                refresh()
                            })
                            Text(ot, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }
    }
    Spacer(Modifier.padding(2.dp))
    TextButton(onClick = {
        engine.cheatResetAll()
        info = "已全部恢复默认"
        refresh()
    }) {
        Icon(Icons.Rounded.Refresh, null, Modifier.width(16.dp))
        Text("全部恢复默认", fontSize = 12.sp)
    }
    MsgLine(info)
}

// ---------------------------------------------------------------------------
// ★★ v71 afbn 核心 PGM2 记忆卡 —— 参考 APK 插卡菜单同源 JNI
//   （nativePgm2* 家族：建卡核心自产 p<槽>_memorycard_<时间戳>.pg2，
//   内置 blank_kov2nl/orleg2 卡模板；接受 .pg2/.bin/.mem/.fc）。
// ---------------------------------------------------------------------------

/** 递归收集 JSON 里全部字符串（卡清单协议容错）。 */
private fun collectJsonStrings(o: Any?, out: ArrayList<String>) {
    when (o) {
        is JSONObject -> {
            val it = o.keys()
            while (it.hasNext()) collectJsonStrings(o.opt(it.next()), out)
        }
        is org.json.JSONArray -> for (i in 0 until o.length()) collectJsonStrings(o.opt(i), out)
        is String -> if (o.isNotBlank()) out.add(o)
    }
}

@Composable
private fun AfbnCardsContent(engine: AfbnEngine) {
    var tick by remember { mutableStateOf(0) }
    var info by remember { mutableStateOf<String?>(null) }
    var pickFor by remember { mutableStateOf<Int?>(null) }

    val slots = remember(tick) {
        try { if (engine.pgm2CardSupported()) engine.pgm2MaxCardSlots() else 0 } catch (_: Throwable) { 0 }
    }

    if (slots <= 0) {
        Text(
            "本游戏不支持记忆卡（仅 PGM2 基板游戏支持：三国战纪2群雄争霸 / 西游释厄传2 / 三国战纪3 等）。",
            fontSize = 13.sp, lineHeight = 19.sp
        )
        return
    }

    Column(Modifier.fillMaxWidth()) {
        Text(
            "PGM2 基板 IC 卡 —— 武将/角色进度存 IC 卡。核心自动管理卡文件（建卡自产" +
                " p<槽>_memorycard_<时间戳>.pg2，模板内置）。先建卡或选卡，再插卡。",
            fontSize = 12.sp, color = Color(0xFF8FA0B5), lineHeight = 16.sp
        )
        Spacer(Modifier.padding(3.dp))
        for (s in 0 until slots) {
            val status = remember(tick, s) { engine.pgm2CardStatus(s) }
            val enabled = remember(tick, s) { try { engine.pgm2IsCardEnabled(s) } catch (_: Throwable) { false } }
            val path = remember(tick, s) { engine.pgm2CardPath(s) }
            Column(
                Modifier.fillMaxWidth()
                    .background(Color(0x14203040), RoundedCornerShape(10.dp))
                    .padding(8.dp)
            ) {
                Text(
                    "P${s + 1} 记忆卡" + when {
                        enabled -> "（已插入）"
                        path != null -> "（已选中未插入）"
                        status != 0 -> "（占用）"
                        else -> "（空）"
                    },
                    fontSize = 13.sp, fontWeight = FontWeight.SemiBold
                )
                Text(
                    path?.let { File(it).name } ?: "未选卡",
                    fontSize = 11.sp, color = Color(0xFF8FA0B5), maxLines = 1, overflow = TextOverflow.Ellipsis
                )
                Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    TextButton(onClick = {
                        val e = engine.pgm2CreateCard(s)
                        info = e ?: "已建卡（P${s + 1}）：${engine.pgm2CardPath(s)?.let { f -> File(f).name } ?: ""} —— 点「插卡」启用"
                        tick += 1
                    }) { Text("建卡", fontSize = 11.sp) }
                    TextButton(onClick = { pickFor = s }) { Text("选卡", fontSize = 11.sp) }
                    if (enabled) {
                        TextButton(onClick = {
                            val e = engine.pgm2EjectCard(s)
                            info = e ?: "已弹卡（P${s + 1}）"
                            tick += 1
                        }) { Text("弹卡", fontSize = 11.sp) }
                    } else {
                        TextButton(onClick = {
                            val e = engine.pgm2InsertCard(s)
                            info = e ?: "已插卡（P${s + 1}）"
                            tick += 1
                        }) { Text("插卡", fontSize = 11.sp) }
                    }
                    TextButton(onClick = {
                        val e = engine.pgm2SaveCard(s)
                        info = e ?: "已存卡（P${s + 1}）"
                        tick += 1
                    }) { Text("存卡", fontSize = 11.sp) }
                }
            }
            Spacer(Modifier.padding(2.dp))
        }
        MsgLine(info)
    }

    pickFor?.let { slot ->
        val files = remember(slot, tick) {
            val out = ArrayList<String>()
            val rawJson = engine.pgm2ListCardFilesJson(slot)
            if (rawJson != null) {
                try {
                    collectJsonStrings(if (rawJson.trimStart().startsWith("[")) org.json.JSONArray(rawJson) else JSONObject(rawJson), out)
                } catch (_: Throwable) {}
            }
            // 过滤出像卡文件的条目；无路径的裸文件名尝试用核心数据目录的 memcard/ 拼全路径
            val dirs = listOfNotNull(
                org.libsdl.app.SDLActivity.datapath.takeIf { it.isNotBlank() }?.let { File(File(it, "memcard").absolutePath) },
                org.libsdl.app.SDLActivity.romdatapath.takeIf { it.isNotBlank() }?.let { File(File(it, "memcard").absolutePath) }
            )
            val seen = LinkedHashSet<String>()
            for (s0 in out) {
                val t = s0.trim()
                if (t.contains("/")) { seen.add(t); continue }
                if (listOf(".pg2", ".bin", ".mem", ".fc").any { t.lowercase().endsWith(it) }) {
                    val full = dirs.firstOrNull { File(it, t).isFile }?.let { File(it, t).absolutePath } ?: t
                    seen.add(full)
                }
            }
            seen.toList()
        }
        AlertDialog(
            onDismissRequest = { pickFor = null },
            confirmButton = { TextButton(onClick = { pickFor = null }) { Text("关闭") } },
            title = { Text("选择 P${slot + 1} 记忆卡", fontSize = 16.sp) },
            text = {
                if (files.isEmpty()) {
                    Text("核心未返回卡文件清单 —— 先「建卡」。", fontSize = 13.sp)
                } else {
                    LazyColumn(Modifier.heightIn(max = 280.dp)) {
                        itemsIndexed(files) { _, f ->
                            Row(
                                Modifier.fillMaxWidth().clickable {
                                    val err = engine.pgm2SelectCardPath(slot, f)
                                    info = err ?: "已选卡（P${slot + 1}）：${File(f).name} —— 点「插卡」启用"
                                    pickFor = null
                                    tick += 1
                                }.padding(horizontal = 4.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Checkbox(checked = engine.pgm2CardPath(slot) == f, onCheckedChange = null)
                                Text(File(f).name, fontSize = 13.sp)
                            }
                        }
                    }
                }
            }
        )
    }
}

/** SAF 导入：用户选一个 .ini / .dat → 复制为 <cheatsDir>/<driver>.ini。 */
@Composable
private fun ImportCheatFileButton(
    cheatsDir: File,
    driver: String,
    onResult: (Boolean) -> Unit
) {
    ImportToButton(File(cheatsDir, "$driver.ini"), "导入金手指文件", onResult)
}

/** ★ v70 通用 SAF 导入（复制用户所选文件到 [dst]）。 */
@Composable
private fun ImportToButton(
    dst: File,
    label: String,
    onResult: (Boolean) -> Unit
) {
    val context = LocalContext.current
    val launcher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        var ok = false
        try {
            dst.parentFile?.mkdirs()
            context.contentResolver.openInputStream(uri)?.use { input ->
                dst.outputStream().use { input.copyTo(it) }
            }
            ok = dst.length() > 0
        } catch (_: Throwable) {
            ok = false
        }
        onResult(ok)
    }
    TextButton(onClick = { launcher.launch("*/*") }) {
        Icon(Icons.Rounded.Add, null, Modifier.width(16.dp))
        Text(label, fontSize = 12.sp)
    }
}

// ---------------------------------------------------------------------------
// 三国战记3HD（PGM3）金手指（核心原生 JSON 协议）
// ---------------------------------------------------------------------------

@Composable
private fun Pgm3CheatsContent(engine: Pgm3Engine) {
    var raw by remember { mutableStateOf<String?>(null) }
    var info by remember { mutableStateOf<String?>(null) }
    // ★ v70：展开查看全部选项
    var expandedIdx by remember { mutableStateOf<Int?>(null) }

    fun refresh() { raw = engine.cheatListJson() }
    LaunchedEffect(Unit) { refresh() }

    // 简易 JSON 提取（org.json）
    val entries = remember(raw) {
        val list = ArrayList<Triple<Int, String, List<String>>>() // entry, name, options
        try {
            val obj = JSONObject(raw ?: return@remember list)
            val arr = obj.optJSONArray("entries") ?: return@remember list
            for (i in 0 until arr.length()) {
                val e = arr.optJSONObject(i) ?: continue
                val opts = ArrayList<String>()
                val oa = e.optJSONArray("options")
                if (oa != null) for (j in 0 until oa.length()) opts.add(oa.optString(j))
                list.add(Triple(i, e.optString("name"), opts))
            }
        } catch (_: Throwable) {}
        list
    }
    val selected = remember(raw) {
        val map = HashMap<Int, Int>()
        try {
            val obj = JSONObject(raw ?: return@remember map)
            val arr = obj.optJSONArray("entries") ?: return@remember map
            for (i in 0 until arr.length()) {
                val e = arr.optJSONObject(i) ?: continue
                map[i] = e.optInt("selected", 0)
            }
        } catch (_: Throwable) {}
        map
    }

    if (raw == null) {
        Text(
            "核心未返回金手指清单。\n\n把 kov3hd 的金手指 ini 放到：\n" +
                (engine.cheatIniFile()?.absolutePath ?: "<PGM3>/cheats/kov3hd.ini") +
                "\n（文件不存在属正常 —— 放入后重进游戏即可加载）",
            fontSize = 13.sp, lineHeight = 19.sp
        )
        // ★ v70：直接导入 kov3hd 金手指 ini（无需手动找目录）
        engine.cheatIniFile()?.let { dst ->
            ImportToButton(dst, "导入 kov3hd 金手指 ini") { ok ->
                info = if (ok) "已导入 —— 退出并重进游戏后核心加载新金手指" else "导入失败"
            }
        }
        MsgLine(info)
        return
    }
    if (entries.isEmpty()) {
        Text("当前 ini 没有可用条目（或全部被核心判定为无效声明）。", fontSize = 13.sp)
        engine.cheatIniFile()?.let { dst ->
            ImportToButton(dst, "导入 kov3hd 金手指 ini") { ok ->
                info = if (ok) "已导入 —— 退出并重进游戏后核心加载新金手指" else "导入失败"
            }
        }
        MsgLine(info)
        return
    }
    LazyColumn(
        modifier = Modifier.fillMaxWidth().heightIn(max = 300.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        itemsIndexed(entries) { _, (idx, name, opts) ->
            val selIdx = selected[idx] ?: 0
            val expanded = expandedIdx == idx
            Column(
                Modifier.fillMaxWidth()
                    .background(Color(0x14203040), RoundedCornerShape(8.dp))
                    // ★ v70：点条目 = 展开/收起全部选项（与 FBN 同样：此前每条只露当前一个选项）
                    .clickable { expandedIdx = if (expanded) null else idx }
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(name.ifBlank { "条目 $idx" }, fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(
                            "选项 ${selIdx + 1}/${opts.size}：${opts.getOrElse(selIdx) { "?" }}" +
                                if (expanded) "（点此收起）" else "（点击展开全部 ${opts.size} 个选项）",
                            fontSize = 11.sp, color = Color(0xFF8FA0B5), maxLines = 1, overflow = TextOverflow.Ellipsis
                        )
                    }
                    TextButton(onClick = {
                        val next = (selIdx + 1) % opts.size.coerceAtLeast(1)
                        val ok = engine.cheatSetOption(idx, next)
                        info = if (ok) null else "应用失败（条目 $idx）"
                        refresh()
                    }) { Text("切换", fontSize = 12.sp) }
                }
                if (expanded) {
                    // ★ v70：全部选项直选（radio）
                    opts.forEachIndexed { oi, ot ->
                        Row(
                            Modifier.fillMaxWidth()
                                .clickable {
                                    val ok = engine.cheatSetOption(idx, oi)
                                    info = if (ok) null else "应用失败（条目 $idx）"
                                    refresh()
                                }
                                .padding(start = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = oi == selIdx,
                                onClick = {
                                    val ok = engine.cheatSetOption(idx, oi)
                                    info = if (ok) null else "应用失败（条目 $idx）"
                                    refresh()
                                }
                            )
                            Text(ot, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }
    }
    Spacer(Modifier.padding(2.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        TextButton(onClick = {
            engine.cheatResetAll()
            info = "已全部恢复默认"
            refresh()
        }) {
            Icon(Icons.Rounded.Refresh, null, Modifier.width(16.dp))
            Text("全部恢复默认", fontSize = 12.sp)
        }
        // ★ v70：导入 kov3hd 金手指 ini（覆盖后需重进游戏让核心重新解析）
        engine.cheatIniFile()?.let { dst ->
            ImportToButton(dst, "导入金手指 ini") { ok ->
                info = if (ok) "已导入 —— 退出并重进游戏后核心加载新金手指" else "导入失败"
            }
        }
    }
    MsgLine(info)
}

// ---------------------------------------------------------------------------
// 三国战记3HD（PGM3）虚拟插卡
// ---------------------------------------------------------------------------

@Composable
private fun Pgm3CardsContent(engine: Pgm3Engine) {
    var tick by remember { mutableStateOf(0) } // 状态刷新器
    var info by remember { mutableStateOf<String?>(null) }
    var showNewCardFor by remember { mutableStateOf<Int?>(null) }
    var showPickFor by remember { mutableStateOf<Int?>(null) }

    val cards = remember(tick) { engine.listCardFiles() }

    fun act(player: Int, action: String) {
        when (action) {
            "insert" -> { val e = engine.insertCard(player); info = e ?: "1P/2P 卡已插入（P${player + 1}）"; tick += 1 }
            "eject" -> { val e = engine.ejectCard(player); info = e ?: "已弹卡（P${player + 1}）"; tick += 1 }
            "save" -> { val e = engine.saveCard(player); info = e ?: "已存卡（P${player + 1}）"; tick += 1 }
            else -> {}
        }
    }

    Column(Modifier.fillMaxWidth()) {
        Text(
            "武将进度/装备/功勋保存在 IC 卡（记忆卡）里：先建卡或选卡，再插卡；" +
                "游戏内存档后自动写入卡文件。选中卡会在下次启动时自动插入。",
            fontSize = 12.sp, color = Color(0xFF8FA0B5), lineHeight = 17.sp
        )
        Spacer(Modifier.padding(3.dp))
        // ★ v73 展示记忆卡目录（外部可见存储：文件管理器可直接放入已有卡文件）
        Text(
            "记忆卡目录（把已有卡文件放进去，选卡即可看到）：\n" +
                (engine.memcardDir()?.absolutePath ?: "未就绪"),
            fontSize = 11.sp, color = Color(0xFF8FA0B5), lineHeight = 15.sp
        )
        Spacer(Modifier.padding(3.dp))
        for (p in 0..1) {
            val status = engine.cardStatus(p)
            val inserted = status and 0x2 != 0
            val hasCard = status != 0
            val path = engine.cardPath(p)
            val persisted = engine.persistedCardChoice(p)
            Row(
                Modifier.fillMaxWidth()
                    .background(Color(0x14203040), RoundedCornerShape(10.dp))
                    .padding(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        "P${p + 1} 记忆卡" + when {
                            inserted -> "（已插入）"
                            hasCard -> "（已选中未插入）"
                            else -> "（空）"
                        },
                        fontSize = 13.sp, fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        (path?.takeIf { it.isNotBlank() }?.let { File(it).name } ?: "未选卡") +
                            (if (persisted != null) " · 开机自动插入" else ""),
                        fontSize = 11.sp, color = Color(0xFF8FA0B5), maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                }
                Column(horizontalAlignment = Alignment.End) {
                    Row {
                        TextButton(onClick = { showNewCardFor = p }) { Text("建卡", fontSize = 11.sp) }
                        TextButton(onClick = { showPickFor = p }) { Text("选卡", fontSize = 11.sp) }
                    }
                    Row {
                        if (inserted) {
                            TextButton(onClick = { act(p, "eject") }) {
                                Icon(Icons.Rounded.Eject, null, Modifier.width(14.dp)); Text("弹卡", fontSize = 11.sp)
                            }
                        } else {
                            TextButton(onClick = {
                                val e = engine.insertCard(p)
                                info = e ?: "已插卡（P${p + 1}）"
                                if (e == null) engine.persistCardChoice(p, engine.cardPath(p))
                                tick++
                            }) { Text("插卡", fontSize = 11.sp) }
                        }
                        TextButton(onClick = { act(p, "save") }) {
                            Icon(Icons.Rounded.Save, null, Modifier.width(14.dp)); Text("存卡", fontSize = 11.sp)
                        }
                    }
                }
            }
            Spacer(Modifier.padding(2.dp))
        }
        MsgLine(info)

        if (cards.isNotEmpty()) {
            Text("记忆卡目录现有 ${cards.size} 个卡文件（选卡见各玩家\"选卡\"按钮）", fontSize = 11.sp, color = Color(0xFF8FA0B5))
        }

        // 新建卡
        showNewCardFor?.let { p ->
            // ★ v73 默认卡名对齐参考实现（Pgm3CardDialog：1p_yyyyMMddHHmm，.bin 由引擎自动补）
            var name by remember(p) {
                mutableStateOf(
                    "${p + 1}p_" + java.text.SimpleDateFormat("yyyyMMddHHmm", java.util.Locale.US)
                        .format(java.util.Date())
                )
            }
            AlertDialog(
                onDismissRequest = { showNewCardFor = null },
                confirmButton = {
                    TextButton(onClick = {
                        val err = engine.createCard(p, name)
                        info = err ?: ("已创建并选卡（P${p + 1}）：" +
                            (engine.cardPath(p)?.let { File(it).name } ?: "") +
                            " —— 点\"插卡\"启用")
                        if (err == null) engine.persistCardChoice(p, engine.cardPath(p))
                        showNewCardFor = null
                        tick++
                    }) { Text("创建") }
                },
                dismissButton = { TextButton(onClick = { showNewCardFor = null }) { Text("取消") } },
                title = { Text("新建 P${p + 1} 记忆卡", fontSize = 16.sp) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        OutlinedTextField(
                            value = name,
                            onValueChange = { name = it },
                            label = { Text("卡名（自动补 .bin 与 1p_/2p_ 玩家标记）") },
                            singleLine = true
                        )
                        Text(
                            "已有的任意扩展名卡文件（.bin / .memcard / .sav…）放进上方记忆卡目录，\"选卡\"即可看到并使用。",
                            fontSize = 11.sp, color = Color(0xFF8FA0B5), lineHeight = 15.sp
                        )
                    }
                }
            )
        }

        // 选卡（列出记忆卡目录文件）
        showPickFor?.let { p ->
            AlertDialog(
                onDismissRequest = { showPickFor = null },
                confirmButton = { TextButton(onClick = { showPickFor = null }) { Text("关闭") } },
                title = { Text("选择 P${p + 1} 记忆卡", fontSize = 16.sp) },
                text = {
                    if (cards.isEmpty()) {
                        Text("记忆卡目录还没有卡文件 —— 先\"建卡\"，或把已有卡文件放进记忆卡目录。", fontSize = 13.sp)
                    } else {
                        LazyColumn(Modifier.heightIn(max = 280.dp)) {
                            itemsIndexed(cards) { _, f ->
                                Row(
                                    Modifier.fillMaxWidth().clickable {
                                        val err = engine.selectCard(p, f)
                                        info = err ?: "已选卡（P${p + 1}）：${f.name} —— 点\"插卡\"启用"
                                        if (err == null) engine.persistCardChoice(p, f.absolutePath)
                                        showPickFor = null
                                        tick++
                                    }.padding(horizontal = 4.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Checkbox(checked = engine.cardPath(p) == f.absolutePath, onCheckedChange = null)
                                    Column {
                                        Text(f.name, fontSize = 13.sp)
                                        Text(
                                            java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault())
                                                .format(java.util.Date(f.lastModified())) +
                                            (if (f.length() < 1024) " · ${f.length()}B" else " · ${f.length() / 1024}KB"),
                                            fontSize = 11.sp, color = Color(0xFF8FA0B5)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Wii U（Cemu）金手指 —— Cemu-AZ 原生引擎（NativeCheats）
// ---------------------------------------------------------------------------

@Composable
private fun CemuCheatsContent(engine: CemuEngine) {
    var msg by remember { mutableStateOf<String?>(null) }
    var count by remember { mutableStateOf(-1) }
    var enabled by remember { mutableStateOf<List<Boolean>>(emptyList()) }

    fun reload() {
        try {
            NativeCheats.reloadCheats()
            val n = NativeCheats.getCheatCount()
            count = n
            enabled = (0 until n).map { NativeCheats.isCheatEnabled(it) }
        } catch (t: Throwable) {
            count = -1
            msg = "读取失败：${t.message}"
        }
    }
    LaunchedEffect(Unit) { reload() }

    when {
        count < 0 -> Text(msg ?: "金手指引擎未就绪。", fontSize = 13.sp)
        count == 0 -> Text(
            "当前游戏没有可用金手指。\n\nCemu 金手指 = 游戏规则 txt（Cemu-AZ 格式），放在：" +
                "\n<应用数据>/cemu/gameProfiles/\n（按游戏 titleId 命名，重启游戏后重新打开本面板）",
            fontSize = 13.sp, lineHeight = 19.sp
        )
        else -> LazyColumn(
            modifier = Modifier.fillMaxWidth().heightIn(max = 340.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            itemsIndexed(enabled) { i, on ->
                val name = try { NativeCheats.getCheatName(i) } catch (_: Throwable) { null } ?: "条目 $i"
                val comment = try { NativeCheats.getCheatComment(i) } catch (_: Throwable) { null }
                Row(
                    Modifier.fillMaxWidth()
                        .background(Color(0x14203040), RoundedCornerShape(8.dp))
                        .padding(horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(checked = on, onCheckedChange = { v ->
                        try { NativeCheats.setCheatEnabled(i, v) } catch (_: Throwable) {}
                        enabled = enabled.toMutableList().also { it[i] = v }
                    })
                    Column(Modifier.weight(1f)) {
                        Text(name, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        if (!comment.isNullOrBlank()) {
                            Text(comment, fontSize = 11.sp, color = Color(0xFF8FA0B5), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }
    }
    Spacer(Modifier.padding(2.dp))
    TextButton(onClick = { reload() }) {
        Icon(Icons.Rounded.Refresh, null, Modifier.width(16.dp))
        Text("重新加载金手指", fontSize = 12.sp)
    }
    MsgLine(msg)
}

// ---------------------------------------------------------------------------
// ★★ v69 全核心 RAM 金手指（RetroArch 同款：.cht 文件 + 每帧补丁）
//
// 覆盖核心：NES(fceux 总线) / SFC(snes9x) / GB·GBA(mgba) / MD(genesis_plus_gx)
// / PCE(geargrafx) / PSX(pcsx_rearmed) / DC(flycast) / NDS(melonDS)。
// 补丁经 RamCheatEngine.pushCheatPatchList 下发，核心每帧 runFrame 前重放
// （游戏改写 RAM 也会被再补上 —— 无限血/无限币标准做法，布局见
// core/jni/shared/cheat_engine.h）。
// ---------------------------------------------------------------------------

/** 单条 RAM 金手指（.cht 里的一条 cheatN_*）。 */
data class RamCheat(
    val index: Int,
    val desc: String,
    val address: Long,
    val value: Int,
    val size: Int,        // 1/2/4 字节（小端）
    val mask: Int,        // 0 = 整段写；否则按位读-改-写
    val enabledInFile: Boolean,
    val supported: Boolean
)

private fun chtNumber(s: String): Long = try {
    val t = s.trim().removeSuffix("L")
    when {
        t.startsWith("0x") || t.startsWith("0X") -> t.substring(2).toLong(16)
        t.startsWith("$") -> t.substring(1).toLong(16)
        else -> t.toLong()
    }
} catch (_: Throwable) { -1L }

/** 解析 RetroArch 格式 .cht（cheats = N / cheatI_desc / _enable / _address / _value / _memory_search_size / _address_bit_position / _cheat_type）。 */
fun parseRetroArchCht(file: File): List<RamCheat> {
    if (!file.isFile) return emptyList()
    // index -> 字段表（RetroArch 写出的字段顺序不固定，逐行收集）
    val rows = HashMap<Int, HashMap<String, String>>()
    try {
        file.readLines().forEach { raw ->
            val line = raw.trim()
            val m = Regex("^cheat(\\d+)_([A-Za-z_]+)\\s*=\\s*(.+)$").find(line) ?: return@forEach
            val idx = m.groupValues[1].toIntOrNull() ?: return@forEach
            rows.getOrPut(idx) { HashMap() }[m.groupValues[2].lowercase()] = m.groupValues[3].trim()
        }
    } catch (_: Throwable) {
        // 坏行忽略（返回已解析部分）
    }
    return rows.keys.sorted().mapNotNull { i ->
        val f = rows[i] ?: return@mapNotNull null
        val desc = f["desc"]?.removePrefix("\"")?.removeSuffix("\"") ?: "金手指 $i"
        val addr = chtNumber(f["address"] ?: return@mapNotNull null)
        if (addr < 0) return@mapNotNull null
        val value = chtNumber(f["value"] ?: "0").toInt()
        val sizeFromCht = (chtNumber(f["memory_search_size"] ?: "0")).toInt()
        val size = when (sizeFromCht) { 1 -> 2; 2 -> 4; else -> 1 } // 0=1B,1=2B,2=4B
        val mask = (chtNumber(f["address_bit_position"] ?: "0")).toInt()
            .let { if (it in 1..0xFFFFFF) it else 0 }
        val type = (chtNumber(f["cheat_type"] ?: "1")).toInt()
        RamCheat(
            index = i, desc = desc, address = addr, value = value, size = size, mask = mask,
            enabledInFile = (f["enable"] ?: "").equals("true", true) ||
                (f["enable"] ?: "") == "1",
            supported = type == 1  // 1 = CHEAT_TYPE_STANDARD（普通 RAM poke）
        )
    }
}

/**
 * 总线地址 → 核心内存区域偏移（各核心 retro_get_memory_data(SYSTEM_RAM)
 * 的基址约定不同；越界条目原生侧会静默跳过，这里尽量归一到能命中的偏移）。
 */
fun normalizeCheatAddress(platform: GamePlatform, addr: Long): Int {
    val a = addr
    return when (platform) {
        // snes9x SYSTEM_RAM = WRAM 128KB（$7E0000 起）；0x0000-0x1FFFF 直映
        GamePlatform.SFC -> when {
            a in 0x7E0000L until 0x800000L -> (a - 0x7E0000L).toInt()
            else -> a.toInt()
        }
        // mgba：GB WRAM $C000-$DFFF（E000 echo）；GBA EWRAM $02000000 / IWRAM $03000000
        GamePlatform.GB -> when {
            a in 0xC000L until 0xE000L -> (a - 0xC000L).toInt()
            a in 0xE000L until 0xF000L -> (a - 0xE000L).toInt()
            else -> a.toInt()
        }
        GamePlatform.GBA -> when {
            a in 0x02000000L until 0x02040000L -> (a - 0x02000000L).toInt()
            a in 0x03000000L until 0x03008000L -> (a - 0x03000000L).toInt()
            else -> a.toInt()
        }
        // genesis_plus_gx：68K work RAM 64KB（$FF0000 起）
        GamePlatform.MD -> when {
            a in 0xFF0000L until 0x1000000L -> (a - 0xFF0000L).toInt()
            else -> a.toInt()
        }
        // geargrafx：主 RAM 8KB（$1F0000 起）
        GamePlatform.PCE -> when {
            a in 0x1F0000L until 0x1F2000L -> (a - 0x1F0000L).toInt()
            else -> a.toInt()
        }
        // pcsx_rearmed：主 RAM 2MB；cht 地址常带 KUSEG 基址 0x80000000
        GamePlatform.PSX -> when {
            a >= 0x80000000L -> (a and 0x1FFFFFFFL).toInt()
            else -> a.toInt()
        }
        // melonDS：DS 主 RAM 4MB（$02000000 起）
        GamePlatform.NDS -> when {
            a in 0x02000000L until 0x02400000L -> (a - 0x02000000L).toInt()
            else -> a.toInt()
        }
        // flycast / NES(fceux 总线) / 其他：原样（越界由原生侧跳过）
        else -> a.toInt()
    }
}

/** 组装补丁列表（5 Int 一组：enabled / 偏移 / 值 / 字节数 / 位掩码）。 */
private fun buildRamPatchList(
    entries: List<RamCheat>,
    enabled: Map<Int, Boolean>,
    platform: GamePlatform
): IntArray {
    val out = ArrayList<Int>(entries.size * 5)
    for (e in entries) {
        if (!e.supported) continue
        val on = enabled[e.index] ?: e.enabledInFile
        out.add(if (on) 1 else 0)
        out.add(normalizeCheatAddress(platform, e.address))
        out.add(e.value)
        out.add(e.size)
        out.add(e.mask)
    }
    return out.toIntArray()
}

/** 每游戏持久化（SharedPreferences "ram_cheats"，值 = {条目下标: 开关}）。 */
fun loadRamCheatEnabled(context: android.content.Context, cheatKey: String): Map<Int, Boolean> {
    if (cheatKey.isBlank()) return emptyMap()
    return try {
        val json = JSONObject(
            context.getSharedPreferences("ram_cheats", android.content.Context.MODE_PRIVATE)
                .getString(cheatKey, "{}") ?: "{}"
        )
        val map = HashMap<Int, Boolean>()
        for (k in json.keys()) {
            val i = k.toIntOrNull() ?: continue
            map[i] = json.optBoolean(k, false)
        }
        map
    } catch (_: Throwable) { emptyMap() }
}

fun saveRamCheatEnabled(
    context: android.content.Context, cheatKey: String, enabled: Map<Int, Boolean>
) {
    if (cheatKey.isBlank()) return
    try {
        val json = JSONObject()
        for ((k, v) in enabled) json.put(k.toString(), v)
        context.getSharedPreferences("ram_cheats", android.content.Context.MODE_PRIVATE)
            .edit().putString(cheatKey, json.toString()).apply()
    } catch (_: Throwable) {}
}

/** cheat 文件路径（<filesDir>/cheats/<cheatKey>.cht）。 */
fun ramCheatFile(context: android.content.Context, cheatKey: String): File {
    val safe = cheatKey.ifBlank { "game" }.replace(Regex("[^A-Za-z0-9._-]"), "_")
    return File(File(context.filesDir, "cheats").apply { mkdirs() }, "$safe.cht")
}

/**
 * 游戏加载后自动重放上次的 RAM 金手指（EmulatorScreen LaunchedEffect 调）。
 * 无 cheat 文件也必须下发一次空列表 —— 核心引擎是跨游戏复用的单例，
 * 不清空会把上一局的补丁漏进这一局。
 */
fun pushPersistedRamCheats(
    context: android.content.Context,
    cheatKey: String,
    engine: RamCheatEngine,
    platform: GamePlatform
) {
    val entries = parseRetroArchCht(ramCheatFile(context, cheatKey))
    val enabled = loadRamCheatEnabled(context, cheatKey)
    try { engine.pushCheatPatchList(buildRamPatchList(entries, enabled, platform)) } catch (_: Throwable) {}
}

/**
 * 把条目表写回 RetroArch 格式 .cht（★ v70 编辑器用）。
 * 保留原 index 编号（允许空洞）—— enabled 开关持久化按键依赖 index 稳定；
 * RetroArch/本项目解析器均按 cheatI_* 逐行读取，不要求连续编号。
 */
fun writeRetroArchCht(file: File, entries: List<RamCheat>) {
    file.parentFile?.mkdirs()
    val sb = StringBuilder()
    sb.append("cheats = ").append(entries.size).append('\n')
    for (e in entries) {
        sb.append('\n')
        sb.append("cheat").append(e.index).append("_desc = \"")
            .append(e.desc.replace("\"", "'")).append("\"\n")
        sb.append("cheat").append(e.index).append("_enable = ")
            .append(if (e.enabledInFile) "true" else "false").append('\n')
        sb.append("cheat").append(e.index).append("_address = 0x")
            .append(e.address.toString(16)).append('\n')
        sb.append("cheat").append(e.index).append("_address_bit_position = ")
            .append(e.mask).append('\n')
        sb.append("cheat").append(e.index).append("_cheat_type = 1\n")
        sb.append("cheat").append(e.index).append("_memory_search_size = ")
            .append(when (e.size) { 2 -> 1; 4 -> 2; else -> 0 }).append('\n')
        sb.append("cheat").append(e.index).append("_value = ")
            .append(e.value).append('\n')
    }
    file.writeText(sb.toString())
}

@Composable
private fun RamCheatsContent(
    engine: RamCheatEngine,
    cheatKey: String,
    platform: GamePlatform
) {
    val context = LocalContext.current
    val chtFile = remember(cheatKey) { ramCheatFile(context, cheatKey) }
    var reloadTick by remember { mutableStateOf(0) }
    val entries = remember(chtFile, reloadTick) { parseRetroArchCht(chtFile) }
    var enabled by remember(chtFile, reloadTick) {
        mutableStateOf(loadRamCheatEnabled(context, cheatKey))
    }
    var info by remember { mutableStateOf<String?>(null) }
    // ★ v70 编辑器：editTarget = null 关闭；RamCheat? 为编辑对象；新建用哨兵空条目
    var editTarget by remember { mutableStateOf<RamCheat?>(null) }
    var showAdd by remember { mutableStateOf(false) }

    // 打开面板即生效当前选择（进入游戏时 pushPersistedRamCheats 已重放过一次）
    androidx.compose.runtime.LaunchedEffect(chtFile, reloadTick) {
        try { engine.pushCheatPatchList(buildRamPatchList(entries, enabled, platform)) } catch (_: Throwable) {}
    }

    fun toggle(e: RamCheat) {
        val cur = enabled[e.index] ?: e.enabledInFile
        val next = enabled + (e.index to !cur)
        enabled = next
        saveRamCheatEnabled(context, cheatKey, next)
        try { engine.pushCheatPatchList(buildRamPatchList(entries, next, platform)) } catch (_: Throwable) {}
    }

    Text(
        "RetroArch 格式 .cht 金手指，每帧自动补丁（热生效，无需重启）。\n" +
            "把 ${chtFile.name} 放到或导入：\n${chtFile.absolutePath}",
        fontSize = 12.sp, color = Color(0xFF8FA0B5), lineHeight = 17.sp
    )
    Spacer(Modifier.padding(3.dp))

    if (entries.isEmpty()) {
        Text(
            "还没有 ${platform.displayName} 的 .cht 金手指文件。\n\n" +
                "RetroArch 的 cheats 目录、金手指网站的 .cht 都可以直接用；" +
                "导入后立即生效（地址为游戏总线地址，本核心会自动映射到内存区域）。",
            fontSize = 13.sp, lineHeight = 19.sp
        )
        RamImportCheatFileButton(chtFile) { ok ->
            info = if (ok) "已导入 ${chtFile.name}" else "导入失败"
            if (ok) reloadTick++
        }
        // ★ v70：没有文件也能直接新建条目（首次创建 .cht）
        TextButton(onClick = { showAdd = true }) {
            Icon(Icons.Rounded.Add, null, Modifier.width(16.dp))
            Text("添加金手指", fontSize = 12.sp)
        }
        MsgLine(info)
        // ★ v70：新建对话框（空文件状态也可用）
        if (showAdd) {
            RamCheatEditDialog(
                title = "添加金手指",
                initial = null,
                onDismiss = { showAdd = false },
                onSave = { e ->
                    val nextIndex = (parseRetroArchCht(chtFile).maxOfOrNull { it.index } ?: -1) + 1
                    val list = parseRetroArchCht(chtFile) + e.copy(index = nextIndex)
                    try {
                        writeRetroArchCht(chtFile, list)
                        if (e.enabledInFile) {
                            enabled = enabled + (nextIndex to true)
                            saveRamCheatEnabled(context, cheatKey, enabled)
                        }
                        reloadTick++
                        info = "已添加「${e.desc}」并立即生效"
                    } catch (t: Throwable) {
                        info = "写入失败：${t.message}"
                    }
                    showAdd = false
                }
            )
        }
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxWidth().heightIn(max = 340.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        itemsIndexed(entries, key = { _, e -> e.index }) { _, e ->
            val on = enabled[e.index] ?: e.enabledInFile
            Row(
                Modifier.fillMaxWidth()
                    .background(Color(0x14203040), RoundedCornerShape(8.dp))
                    .padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Checkbox(checked = on, onCheckedChange = { if (e.supported) toggle(e) })
                Column(Modifier.weight(1f)) {
                    Text(
                        e.desc, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
                        color = if (e.supported) Color.White else Color(0xFF6B7A8C)
                    )
                    Text(
                        if (e.supported)
                            "地址 0x%X · 值 %s · %d 字节".format(
                                e.address,
                                if (e.size == 4) "0x%X".format(e.value.toLong() and 0xFFFFFFFFL) else e.value.toString(),
                                e.size
                            )
                        else "该条目类型暂不支持（仅支持标准 RAM 补丁）",
                        fontSize = 11.sp, color = Color(0xFF8FA0B5),
                        maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                }
                // ★ v70 编辑器：编辑 / 删除（写回 .cht 后立即重放补丁）
                TextButton(onClick = { editTarget = e }) { Text("编辑", fontSize = 11.sp) }
                TextButton(onClick = {
                    val list = entries.filterNot { it.index == e.index }
                    try {
                        writeRetroArchCht(chtFile, list)
                        enabled = enabled - e.index
                        saveRamCheatEnabled(context, cheatKey, enabled)
                        reloadTick++
                        info = "已删除「${e.desc}」"
                    } catch (t: Throwable) {
                        info = "删除失败：${t.message}"
                    }
                }) { Text("删除", fontSize = 11.sp, color = Color(0xFFE06C5A)) }
            }
        }
    }
    Spacer(Modifier.padding(2.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        TextButton(onClick = {
            val off = entries.filter { it.supported }.associate { it.index to false }
            enabled = off
            saveRamCheatEnabled(context, cheatKey, off)
            try { engine.pushCheatPatchList(buildRamPatchList(entries, off, platform)) } catch (_: Throwable) {}
            info = "已全部关闭"
        }) {
            Icon(Icons.Rounded.Refresh, null, Modifier.width(16.dp))
            Text("全部关闭", fontSize = 12.sp)
        }
        // ★ v70：添加金手指条目（内存地址/值自定义）
        TextButton(onClick = { showAdd = true }) {
            Icon(Icons.Rounded.Add, null, Modifier.width(16.dp))
            Text("添加金手指", fontSize = 12.sp)
        }
        RamImportCheatFileButton(chtFile) { ok ->
            info = if (ok) "已导入 ${chtFile.name}（覆盖旧文件）" else "导入失败"
            if (ok) reloadTick++
        }
    }
    MsgLine(info)
    Text(
        "地址为游戏总线地址（自动映射到核心内存区域）；编辑/删除/添加立即重放补丁，热生效。",
        fontSize = 11.sp, color = Color(0xFF8FA0B5), lineHeight = 15.sp
    )

    // ★ v70：编辑现有条目对话框
    editTarget?.let { target ->
        RamCheatEditDialog(
            title = "编辑金手指",
            initial = target,
            onDismiss = { editTarget = null },
            onSave = { e ->
                val list = entries.map { if (it.index == target.index) e.copy(index = target.index) else it }
                try {
                    writeRetroArchCht(chtFile, list)
                    // 文件内 enable 变化同步到持久化开关表
                    if (e.enabledInFile != (enabled[target.index] ?: target.enabledInFile)) {
                        enabled = enabled + (target.index to e.enabledInFile)
                        saveRamCheatEnabled(context, cheatKey, enabled)
                    }
                    reloadTick++
                    info = "已更新「${e.desc}」并立即生效"
                } catch (t: Throwable) {
                    info = "写入失败：${t.message}"
                }
                editTarget = null
            }
        )
    }

    // ★ v70：新建条目对话框
    if (showAdd) {
        RamCheatEditDialog(
            title = "添加金手指",
            initial = null,
            onDismiss = { showAdd = false },
            onSave = { e ->
                val nextIndex = (entries.maxOfOrNull { it.index } ?: -1) + 1
                try {
                    writeRetroArchCht(chtFile, entries + e.copy(index = nextIndex))
                    if (e.enabledInFile) {
                        enabled = enabled + (nextIndex to true)
                        saveRamCheatEnabled(context, cheatKey, enabled)
                    }
                    reloadTick++
                    info = "已添加「${e.desc}」并立即生效"
                } catch (t: Throwable) {
                    info = "写入失败：${t.message}"
                }
                showAdd = false
            }
        )
    }
}

/**
 * ★ v70 RAM 金手指编辑对话框（添加/编辑共用）。
 * 地址/值支持 0x 十六进制、$ 十六进制、十进制；掩码 0 = 整段写，
 * 非 0 = 按位读-改-写（RetroArch cheat_address_bit_position 语义）。
 */
@Composable
private fun RamCheatEditDialog(
    title: String,
    initial: RamCheat?,
    onDismiss: () -> Unit,
    onSave: (RamCheat) -> Unit
) {
    var desc by remember { mutableStateOf(initial?.desc ?: "") }
    var addr by remember { mutableStateOf(initial?.let { "0x%X".format(it.address) } ?: "") }
    var value by remember {
        mutableStateOf(
            initial?.let {
                if (it.size == 4) "0x%X".format(it.value.toLong() and 0xFFFFFFFFL)
                else it.value.toString()
            } ?: ""
        )
    }
    var sizeIdx by remember { mutableStateOf(listOf(1, 2, 4).indexOf(initial?.size ?: 1).coerceAtLeast(0)) }
    var maskText by remember { mutableStateOf(initial?.mask?.toString() ?: "0") }
    var enabled by remember { mutableStateOf(initial?.enabledInFile ?: true) }
    var err by remember { mutableStateOf<String?>(null) }
    val sizeBytes = listOf(1, 2, 4)[sizeIdx]
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = {
                val a = cheatHexOrDec(addr)
                val vRaw = cheatHexOrDec(value)
                val mask = cheatHexOrDec(maskText)
                val maxVal = when (sizeBytes) { 4 -> 0xFFFFFFFFL; 2 -> 0xFFFFL; else -> 0xFFL }
                when {
                    desc.isBlank() -> err = "请填写描述"
                    a < 0 || a > 0xFFFFFFFFL -> err = "地址无效（范围 0x0–0xFFFFFFFF）"
                    vRaw < 0 || vRaw > maxVal -> err = "值超出 ${sizeBytes} 字节范围（0–$maxVal）"
                    mask < 0 || mask > 0xFFFFFFL -> err = "掩码无效（0 = 整段写；或 0x1–0xFFFFFF）"
                    else -> {
                        onSave(
                            RamCheat(
                                index = initial?.index ?: 0,
                                desc = desc.trim(),
                                address = a,
                                value = vRaw.toInt(),
                                size = sizeBytes,
                                mask = mask.toInt(),
                                enabledInFile = enabled,
                                supported = true
                            )
                        )
                    }
                }
            }) { Text("保存并生效") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
        title = { Text(title, fontSize = 16.sp) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedTextField(
                    value = desc, onValueChange = { desc = it },
                    label = { Text("描述（如：无限血）") }, singleLine = true
                )
                OutlinedTextField(
                    value = addr, onValueChange = { addr = it },
                    label = { Text("内存地址（如 0x815D70 / ${'$'}815D70 / 十进制）") }, singleLine = true
                )
                OutlinedTextField(
                    value = value, onValueChange = { value = it },
                    label = { Text("写入值（如 0x64 = 100）") }, singleLine = true
                )
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("字节数", fontSize = 12.sp, color = Color(0xFF8FA0B5))
                    listOf("1 字节" to 0, "2 字节" to 1, "4 字节" to 2).forEach { (label, idx) ->
                        TextButton(onClick = { sizeIdx = idx }) {
                            Text(
                                label, fontSize = 12.sp,
                                color = if (sizeIdx == idx) Color(0xFF7ED957) else Color(0xFF8FA0B5),
                                fontWeight = if (sizeIdx == idx) FontWeight.SemiBold else FontWeight.Normal
                            )
                        }
                    }
                }
                OutlinedTextField(
                    value = maskText, onValueChange = { maskText = it },
                    label = { Text("位掩码（0 = 整段写；非 0 = 按位，如 0x80 只改最高位）") }, singleLine = true
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = enabled, onCheckedChange = { enabled = it })
                    Text("保存后立即启用", fontSize = 12.sp)
                }
                err?.let { Text(it, color = Color(0xFFE06C5A), fontSize = 12.sp) }
                Text(
                    "地址为游戏总线地址（NES 的 ${'$'}0000-${'$'}FFFF、SFC 的 ${'$'}7E0000 段等，会自动映射到核心内存区域）。",
                    fontSize = 11.sp, color = Color(0xFF8FA0B5), lineHeight = 15.sp
                )
            }
        }
    )
}

/** SAF 导入 .cht（复制为 <cheats>/<cheatKey>.cht）。 */
@Composable
private fun RamImportCheatFileButton(dst: File, onResult: (Boolean) -> Unit) {
    val context = LocalContext.current
    val launcher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        var ok = false
        try {
            dst.parentFile?.mkdirs()
            context.contentResolver.openInputStream(uri)?.use { input ->
                dst.outputStream().use { input.copyTo(it) }
            }
            ok = dst.length() > 0
        } catch (_: Throwable) {
            ok = false
        }
        onResult(ok)
    }
    TextButton(onClick = { launcher.launch("*/*") }) {
        Icon(Icons.Rounded.Add, null, Modifier.width(16.dp))
        Text("导入 .cht 文件", fontSize = 12.sp)
    }
}
