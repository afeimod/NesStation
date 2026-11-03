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
    onDismiss: () -> Unit
) {
    val isPgm3 = engine is Pgm3Engine
    val isFbn = engine is FbNeoEngine
    val isCemu = engine is CemuEngine
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
                } else {
                    Text(
                        "当前核心未接入金手指。\n\n已内置支持：街机（FBN）、三国战记3HD（PGM3）、Wii U（Cemu）；" +
                            "PSP 与 NDS 的金手指开关在 设置→核心设置。",
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

    fun act(player: Int, action: String): Unit = when (action) {
        "insert" -> { val e = engine.insertCard(player); info = e ?: "1P/2P 卡已插入（P${player + 1}）"; tick++ }
        "eject" -> { val e = engine.ejectCard(player); info = e ?: "已弹卡（P${player + 1}）"; tick++ }
        "save" -> { val e = engine.saveCard(player); info = e ?: "已存卡（P${player + 1}）"; tick++ }
        else -> {}
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
