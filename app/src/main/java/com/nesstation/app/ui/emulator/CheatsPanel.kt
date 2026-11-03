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
    val isCemu = engine is CemuEngine
    val isRamCheat = engine is RamCheatEngine
    var tab by remember { mutableStateOf(0) } // PGM3: 0=金手指 1=插卡
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("关闭") }
        },
        title = {
            Column {
                Text(
                    if (isPgm3) "金手指 / 插卡" else "金手指",
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
                    FbnCheatsContent(engine as FbNeoEngine)
                } else if (isCemu) {
                    CemuCheatsContent(engine as CemuEngine)
                } else if (isRamCheat) {
                    RamCheatsContent(engine as RamCheatEngine, cheatKey, platform)
                } else {
                    Text(
                        "当前核心未接入金手指。\n\n已内置支持：\n" +
                            "· 8/16 位主机（NES/SFC/GB/GBA/MD/PCE/PSX/DC/NDS）—— 读 RetroArch 格式 .cht 金手指文件\n" +
                            "· 街机（FBN）—— 官方金手指包 .ini\n" +
                            "· 三国战记3HD（PGM3）—— 金手指 + 虚拟插卡\n" +
                            "· Wii U（Cemu）—— 游戏规则 txt\n" +
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

@Composable
private fun FbnCheatsContent(engine: FbNeoEngine) {
    val context = LocalContext.current
    val driver = engine.currentDriverName
    val cheatsDir = remember(engine.systemDirPath) {
        File(File(engine.systemDirPath, "fbneo"), "cheats").apply { mkdirs() }
    }
    val iniFile = remember(cheatsDir, driver) { File(cheatsDir, "$driver.ini") }
    var entries by remember(iniFile) { mutableStateOf(parseFbnCheatIni(iniFile)) }
    var selected by remember(iniFile) { mutableStateOf<Map<Int, Int>>(emptyMap()) }
    var info by remember { mutableStateOf<String?>(null) }

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
            Row(
                Modifier.fillMaxWidth()
                    .background(Color(0x14203040), RoundedCornerShape(8.dp))
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(e.name, fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(
                        "选项 ${selIdx.coerceIn(0, (e.options.size - 1).coerceAtLeast(0)) + 1}/${e.options.size}：${e.options.getOrElse(selIdx) { "?" }}",
                        fontSize = 11.sp, color = Color(0xFF8FA0B5), maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                }
                // 循环切换（0=通常为"Disabled"，再点回到 0）
                TextButton(onClick = {
                    val next = (selIdx + 1) % e.options.size
                    selected = selected + (e.num to next)
                    val v = "$next - ${e.options[next]}"
                    engine.setCoreOption(fbnCheatKey(driver, e), v)
                    persist(e, next)
                }) { Text("切换", fontSize = 12.sp) }
            }
        }
    }
    Spacer(Modifier.padding(2.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        TextButton(onClick = {
            // 恢复默认：全部条目写回 default（核心语义 = default 0 = 关闭）
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
    MsgLine(info)
    Text(
        "热生效：切换后立即应用，无需重启游戏。0 号选项通常为 Disabled（关闭）。",
        fontSize = 11.sp, color = Color(0xFF8FA0B5), lineHeight = 15.sp
    )
}

/** SAF 导入：用户选一个 .ini / .dat → 复制为 <cheatsDir>/<driver>.ini。 */
@Composable
private fun ImportCheatFileButton(
    cheatsDir: File,
    driver: String,
    onResult: (Boolean) -> Unit
) {
    val context = LocalContext.current
    val launcher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        var ok = false
        try {
            cheatsDir.mkdirs()
            val dst = File(cheatsDir, "$driver.ini")
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
        Text("导入金手指文件", fontSize = 12.sp)
    }
}

// ---------------------------------------------------------------------------
// 三国战记3HD（PGM3）金手指（核心原生 JSON 协议）
// ---------------------------------------------------------------------------

@Composable
private fun Pgm3CheatsContent(engine: Pgm3Engine) {
    var raw by remember { mutableStateOf<String?>(null) }
    var info by remember { mutableStateOf<String?>(null) }

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
        return
    }
    if (entries.isEmpty()) {
        Text("当前 ini 没有可用条目（或全部被核心判定为无效声明）。", fontSize = 13.sp)
        return
    }
    LazyColumn(
        modifier = Modifier.fillMaxWidth().heightIn(max = 300.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        itemsIndexed(entries) { _, (idx, name, opts) ->
            val selIdx = selected[idx] ?: 0
            Row(
                Modifier.fillMaxWidth()
                    .background(Color(0x14203040), RoundedCornerShape(8.dp))
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(name.ifBlank { "条目 $idx" }, fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(
                        "选项 ${selIdx + 1}/${opts.size}：${opts.getOrElse(selIdx) { "?" }}",
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
            var name by remember(p) { mutableStateOf("我的卡") }
            AlertDialog(
                onDismissRequest = { showNewCardFor = null },
                confirmButton = {
                    TextButton(onClick = {
                        val err = engine.createCard(p, name)
                        info = err ?: "已创建并选卡（P${p + 1}）：$name.bin —— 点\"插卡\"启用"
                        if (err == null) engine.persistCardChoice(p, engine.cardPath(p))
                        showNewCardFor = null
                        tick++
                    }) { Text("创建") }
                },
                dismissButton = { TextButton(onClick = { showNewCardFor = null }) { Text("取消") } },
                title = { Text("新建 P${p + 1} 记忆卡", fontSize = 16.sp) },
                text = {
                    androidx.compose.material3.OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text("卡名（存为 <卡名>.bin）") },
                        singleLine = true
                    )
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
                        Text("记忆卡目录还没有卡文件 —— 先\"建卡\"。", fontSize = 13.sp)
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
                                                .format(java.util.Date(f.lastModified())) + " · ${f.length() / 1024}KB",
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
        MsgLine(info)
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
                            "地址 0x%X · 值 %d · %d 字节".format(e.address, e.value, e.size)
                        else "该条目类型暂不支持（仅支持标准 RAM 补丁）",
                        fontSize = 11.sp, color = Color(0xFF8FA0B5),
                        maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                }
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
        RamImportCheatFileButton(chtFile) { ok ->
            info = if (ok) "已导入 ${chtFile.name}（覆盖旧文件）" else "导入失败"
            if (ok) reloadTick++
        }
    }
    MsgLine(info)
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
