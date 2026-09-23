package com.nesstation.app.ui.emulator

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Casino
import androidx.compose.material.icons.rounded.SportsEsports
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nesstation.app.core.external.ExternalCores
import com.nesstation.app.core.model.GameEntry
import com.nesstation.app.core.model.GamePlatform
import com.nesstation.app.core.storage.PadLayout
import com.nesstation.app.core.storage.PadLayoutStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 外部独立核心启动页（3DS / NGC-WII）。
 *
 * 3DS（Azahar/爱吾）与 NGC/WII（Ishiruka）以独立模拟器形态集成 —— 游戏画面
 * 与触摸层由核心 APK 的 EmulationActivity 呈现（NGC/WII 的全量虚拟按键、
 * 控制器切换与体感由核心 overlay 提供，NesStation 侧负责配置与桥接）。
 * 本页职责：
 *   1. 核心安装状态检测（未安装给出明确指引）；
 *   2. 3DS 启动前 NCCH 加密检测（加密游戏提示导入 aes_keys.txt，可跳过）；
 *   3. NGC/WII 启动前把控制器切换（GC 手柄 / Wii Remote / 双节棍 / 经典
 *      手柄）与体感开关写入 Ishiruka 的 WiimoteNew.ini / Dolphin.ini；
 *   4. 自动桥接启动；失败时给出可读错误并保留手动重试 / 打开核心主界面。
 */
@Composable
fun ExternalCoreScreen(
    game: GameEntry,
    onExit: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val platform = game.platform
    val is3ds = platform == GamePlatform.TG3DS

    var padLayout by remember { mutableStateOf(PadLayoutStore.load(context, platform)) }
    var status by remember { mutableStateOf("正在准备启动…") }
    var launching by remember { mutableStateOf(false) }
    var launched by remember { mutableStateOf(false) }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    var decryptWarn by remember { mutableStateOf<String?>(null) }
    // 加密检测结果：null=未知 / true=加密 / false=已解密
    var encrypted by remember { mutableStateOf<Boolean?>(null) }

    val coreInstalled = remember {
        if (is3ds) ExternalCores.isAzaharInstalled(context)
        else ExternalCores.isIshirukaInstalled(context)
    }
    val coreVersion = remember {
        if (is3ds) ExternalCores.coreVersion(context, ExternalCores.AZAHAR_PACKAGE)
        else ExternalCores.coreVersion(context, ExternalCores.ISHIRUKA_PACKAGE)
    }
    // 启动扩展名：优先从路径取；content:// 时先解码 URI 末段再取
    //（SAF 文档名常被编码在 segment 里，扩展名决定核心的容器识别）。
    val launchExt = remember(game.romPath) {
        val p = game.romPath.orEmpty()
        val lastSegment = if (p.startsWith("content://")) {
            try {
                java.net.URLDecoder.decode(p.substringAfterLast('/'), "UTF-8")
            } catch (_: Exception) {
                p.substringAfterLast('/')
            }
        } else p.substringAfterLast('/')
        lastSegment.substringBefore('?').substringAfterLast('.', "").lowercase().ifBlank { "bin" }
    }

    fun doLaunch(force: Boolean) {
        if (launching) return
        launching = true
        errorMsg = null
        scope.launch {
            val err = withContext(Dispatchers.IO) {
                if (is3ds) {
                    // 3DS：加密校验（可在设置关闭；加密游戏仍允许强制启动 ——
                    // 爱吾商店下载的游戏自带解密，仅用户导入的加密 ROM 需要密钥）
                    if (padLayout.tg3dsDecryptCheck == "enabled" && !force) {
                        val enc = ExternalCores.is3dsRomEncrypted(
                            ExternalCores.resolveGamePath(context, game.romPath, launchExt, "3ds")
                        )
                        encrypted = enc
                        if (enc == true) {
                            withContext(Dispatchers.Main) {
                                encrypted = true
                                decryptWarn =
                                    "检测到该游戏为加密状态。加密游戏需要在 Azahar 用户目录的 " +
                                    "keys/aes_keys.txt 放入密钥（在 设置 → 3DS → 解密密钥管理 导入）。\n\n" +
                                    "仍要启动吗？（已解密 / 商店版游戏请直接选择「仍要启动」）"
                                launching = false
                            }
                            return@withContext "pending_decrypt_confirm"
                        }
                    }
                    ExternalCores.launch3dsGame(context, game.romPath ?: "", launchExt) {
                        ExternalCores.launchCoreMainActivity(
                            context,
                            ExternalCores.AZAHAR_PACKAGE,
                            ExternalCores.AZAHAR_MAIN_ACTIVITY
                        )
                        onExit()
                    }
                } else {
                    // NGC/WII：启动前同步控制器切换 + 体感设置到核心 INI
                    val applyErr = withContext(Dispatchers.IO) {
                        ExternalCores.applyNgcwiiControllerMode(
                            context,
                            padLayout.ngcwiiController,
                            padLayout.ngcwiiMotion
                        )
                    }
                    if (applyErr != null) {
                        android.util.Log.w("ExternalCoreScreen", "控制器配置写入失败: $applyErr")
                        // 不阻塞启动 —— Ishiruka 自身有默认控制器配置
                    }
                    ExternalCores.launchNgcwiiGame(context, game.romPath ?: "", launchExt)
                }
            }
            launching = false
            when {
                err == null -> {
                    launched = true
                    status = "已切换到核心 — 可返回本应用"
                }
                err == "pending_decrypt_confirm" -> Unit   // 等待用户确认
                else -> errorMsg = err
            }
        }
    }

    // 首次进入自动启动（与游戏卡点击 → 进入即跑的体验一致）
    LaunchedEffect(coreInstalled) {
        if (coreInstalled) doLaunch(force = false)
        else status = "核心未安装"
    }

    // === 视觉：与全局 FSD 暗色风格一致 ===
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF10141E))
    ) {
        Column(
            modifier = Modifier
                .align(Alignment.Center)
                .fillMaxWidth(0.92f)
                .verticalScroll(rememberScrollState())
                .background(Color(0xE61E2A3A), RoundedCornerShape(20.dp))
                .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // 平台徽标 + 标题
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    if (is3ds) Icons.Rounded.Casino else Icons.Rounded.SportsEsports,
                    contentDescription = null,
                    tint = if (is3ds) Color(0xFFE9573F) else Color(0xFF9C6ADE),
                    modifier = Modifier.size(30.dp)
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    if (is3ds) "3DS · Azahar 核心" else "NGC/WII · Ishiruka 核心",
                    color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold
                )
            }
            Spacer(Modifier.height(6.dp))
            Text(game.title, color = Color(0xFFB0BEC5), fontSize = 13.sp)
            Spacer(Modifier.height(14.dp))

            // 核心安装状态
            val statusColor = if (coreInstalled) Color(0xFF88DD88) else Color(0xFFE74C3C)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(9.dp)
                        .clip(RoundedCornerShape(5.dp))
                        .background(statusColor)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    if (coreInstalled) "核心已安装" + (if (coreVersion.isNotBlank()) " · v$coreVersion" else "")
                    else "未检测到核心应用",
                    color = statusColor, fontSize = 13.sp
                )
            }

            if (!coreInstalled) {
                Spacer(Modifier.height(10.dp))
                Text(
                    (if (is3ds)
                        "请先安装 3DS 核心应用：AzaharPlus / 爱吾3DS模拟器\n（包名 ${ExternalCores.AZAHAR_PACKAGE}）"
                    else
                        "请先安装 NGC/WII 核心应用：Ishiruka\n（包名 ${ExternalCores.ISHIRUKA_PACKAGE}）") +
                        "\n\n安装后回到本页重新点击游戏即可自动桥接启动。",
                    color = Color(0xFFB0BEC5), fontSize = 12.sp, lineHeight = 17.sp
                )
            }

            Spacer(Modifier.height(14.dp))

            // === NGC/WII 专属：控制器切换 + 体感 ===
            if (!is3ds && coreInstalled) {
                Text("控制器切换", color = Color(0xFFFFD66B), fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ControllerChip("gc", "GC 手柄", padLayout.ngcwiiController) { next ->
                        padLayout = padLayout.copy { ngcwiiController = next }
                        PadLayoutStore.save(context, padLayout, platform)
                        applyController(context, padLayout)
                    }
                    ControllerChip("wiimote", "Wii 遥控器", padLayout.ngcwiiController) { next ->
                        padLayout = padLayout.copy { ngcwiiController = next }
                        PadLayoutStore.save(context, padLayout, platform)
                        applyController(context, padLayout)
                    }
                    ControllerChip("nunchuk", "双节棍", padLayout.ngcwiiController) { next ->
                        padLayout = padLayout.copy { ngcwiiController = next }
                        PadLayoutStore.save(context, padLayout, platform)
                        applyController(context, padLayout)
                    }
                    ControllerChip("classic", "经典手柄", padLayout.ngcwiiController) { next ->
                        padLayout = padLayout.copy { ngcwiiController = next }
                        PadLayoutStore.save(context, padLayout, platform)
                        applyController(context, padLayout)
                    }
                }
                Spacer(Modifier.height(10.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(Color(0xFF222C3D))
                        .clickable {
                            val next = if (padLayout.ngcwiiMotion == "enabled") "disabled" else "enabled"
                            padLayout = padLayout.copy { ngcwiiMotion = next }
                            PadLayoutStore.save(context, padLayout, platform)
                            applyController(context, padLayout)
                        }
                        .padding(horizontal = 12.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("体感操作", color = Color.White, fontSize = 13.sp)
                    Spacer(Modifier.weight(1f))
                    Text(
                        if (padLayout.ngcwiiMotion == "enabled") "已开启 (摇动/倾斜/IR 指针)"
                        else "已关闭",
                        color = if (padLayout.ngcwiiMotion == "enabled") Color(0xFF88DD88)
                                else Color(0xFF8899AA),
                        fontSize = 12.sp
                    )
                }
                Text(
                    "全量虚拟按键（GC A/B/X/Y/Z + 双摇杆 + L/R 扳机、Wii 1/2/A/B/±/HOME、" +
                    "双节棍 C/Z、经典手柄、十字键）由 Ishiruka 触摸层呈现；按键显隐 / 布局" +
                    "可在游戏的布局编辑器内逐键配置。",
                    color = Color(0xFF8899AA), fontSize = 10.sp, lineHeight = 14.sp
                )
                Spacer(Modifier.height(14.dp))
            }

            // 状态 / 加密提示 / 错误
            if (decryptWarn != null) {
                Text(
                    "游戏解密提示",
                    color = Color(0xFFFFD66B), fontSize = 13.sp, fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(6.dp))
                Text(decryptWarn!!, color = Color(0xFFB0BEC5), fontSize = 12.sp, lineHeight = 17.sp)
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    DialogButton("仍要启动", Color(0xFFFFD66B)) {
                        decryptWarn = null
                        doLaunch(force = true)
                    }
                    DialogButton("取消", Color(0xFF8899AA)) {
                        decryptWarn = null
                        onExit()
                    }
                }
            } else {
                Text(
                    if (encrypted == false) "解密状态：已解密 ✓"
                    else status,
                    color = Color(0xFF8899AA), fontSize = 12.sp
                )
            }

            errorMsg?.let { err ->
                Spacer(Modifier.height(10.dp))
                Text(err, color = Color(0xFFE74C3C), fontSize = 12.sp, lineHeight = 17.sp)
            }

            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (coreInstalled && decryptWarn == null) {
                    DialogButton(if (launching) "启动中…" else "启动游戏", Color(0xFFFFD66B)) {
                        doLaunch(force = true)
                    }
                    DialogButton("打开核心", Color(0xFF7FA6D9)) {
                        if (is3ds) ExternalCores.launchCoreMainActivity(
                            context, ExternalCores.AZAHAR_PACKAGE, ExternalCores.AZAHAR_MAIN_ACTIVITY)
                        else ExternalCores.launchCoreMainActivity(
                            context, ExternalCores.ISHIRUKA_PACKAGE, ExternalCores.ISHIRUKA_MAIN_ACTIVITY)
                    }
                }
                DialogButton("返回", Color(0xFF8899AA)) { onExit() }
            }
        }
    }
}

/** 把控制器切换 / 体感设置写入 Ishiruka INI（失败仅提示，不阻塞）。 */
private fun applyController(context: android.content.Context, layout: PadLayout) {
    val err = ExternalCores.applyNgcwiiControllerMode(
        context, layout.ngcwiiController, layout.ngcwiiMotion
    )
    if (err != null) {
        android.widget.Toast.makeText(context, err, android.widget.Toast.LENGTH_LONG).show()
    }
}

@Composable
private fun ControllerChip(
    value: String,
    label: String,
    current: String,
    onSelect: (String) -> Unit
) {
    val selected = value == current
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(9.dp))
            .background(if (selected) Color(0xFFFFD66B) else Color(0xFF2C2C38))
            .clickable { onSelect(value) }
            .padding(horizontal = 11.dp, vertical = 7.dp)
    ) {
        Text(
            label,
            color = if (selected) Color.Black else Color.White,
            fontSize = 11.sp, fontWeight = FontWeight.SemiBold
        )
    }
}

@Composable
private fun DialogButton(text: String, color: Color, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(Color(0xFF2C3A52))
            .clickable { onClick() }
            .padding(horizontal = 18.dp, vertical = 9.dp)
    ) {
        Text(text, color = color, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    }
}
