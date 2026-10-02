package com.nesstation.app.ui.emulator

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.citra.citra_emu.utils.GpuDriverHelper
import java.io.File

/**
 * ★★ 3DS（Azahar）GPU 驱动管理（本轮新增，对齐上游 Azahar DriverManager）★★
 *
 * 需求原话："核心缺少驱动安装和选择，可以去上游查看并添加"。
 *
 * 功能（与上游 Azahar Android 的 GPU Driver 设置页对齐）：
 *  - 显示当前选中驱动（默认 = 系统驱动）；
 *  - 已安装驱动列表：点选切换（下次启动游戏生效）、长列表可删除；
 *  - 本地导入 adrenotools 标准驱动 zip（meta.json + libvulkan_*.so，
 *    Turnip / Adreno Tools Drivers 下载的包即此格式）；
 *  - supportsCustomDriverLoading() 探测：设备不支持时给出说明并禁用导入。
 *
 * 选中状态持久化在 GpuDriverHelper（SharedPreferences）；
 * AzaharEngine.loadRom → initializeGpuDriver 时读取选中驱动的
 * libraryName 传给核心（空 = 系统驱动）。
 */
@Composable
fun AzaharDriverSection() {
    val context = LocalContext.current
    var refreshKey by remember { mutableStateOf(0) }
    var supported by remember { mutableStateOf(true) }
    var drivers by remember { mutableStateOf(listOf<GpuDriverHelper.InstalledDriver>()) }
    var selectedId by remember { mutableStateOf("") }
    var statusText by remember { mutableStateOf("") }

    LaunchedEffect(refreshKey) {
        supported = GpuDriverHelper.isCustomDriverLoadingSupported()
        drivers = GpuDriverHelper.installedDrivers(context)
        selectedId = GpuDriverHelper.selectedDriverId(context)
    }

    val zipPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            val result: String = try {
                // 先拷到临时文件（ContentResolver 流式读 → ZipInputStream）
                val tmp = File(context.cacheDir, "driver_import_${System.currentTimeMillis()}.zip")
                context.contentResolver.openInputStream(uri)?.use { input ->
                    tmp.outputStream().use { output -> input.copyTo(output) }
                } ?: return@rememberLauncherForActivityResult
                when (val r = GpuDriverHelper.installFromZip(context, tmp)) {
                    is GpuDriverHelper.InstallResult.Success -> {
                        refreshKey++
                        "驱动安装成功，可在上方列表中选择并重启游戏生效"
                    }
                    is GpuDriverHelper.InstallResult.Failed -> r.reason
                }
            } catch (e: Exception) {
                "安装失败: ${e.message}"
            }
            statusText = result
        }
    }

    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(
            if (supported) "设备支持自定义 GPU 驱动加载 (adrenotools)"
            else "当前设备不支持自定义驱动加载（非 Adreno GPU 或系统版本过低）—— 仅可使用系统驱动",
            color = if (supported) Color(0xFF88DD88) else Color(0xFFDAAA66),
            fontSize = 10.sp,
            modifier = Modifier.padding(bottom = 4.dp)
        )

        // 当前选中 + 系统驱动默认项
        val sysSelected = selectedId.isBlank()
        Row(modifier = Modifier
            .fillMaxWidth()
            .clickable {
                GpuDriverHelper.selectDriver(context, "")
                refreshKey++
            }
            .padding(vertical = 6.dp)) {
            Text(if (sysSelected) "● " else "○ ",
                color = Color(0xFFFFD66B), fontSize = 12.sp)
            Column {
                Text("系统驱动 (默认)",
                    color = if (sysSelected) Color(0xFFFFD66B) else Color.White,
                    fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                Text("使用系统内置 Vulkan/OpenGL 驱动", color = Color(0xFF8899AA), fontSize = 10.sp)
            }
        }

        // 已安装驱动列表
        if (drivers.isEmpty()) {
            Text("  未安装自定义驱动", color = Color(0xFF8899AA), fontSize = 10.sp,
                modifier = Modifier.padding(vertical = 4.dp))
        }
        for (d in drivers) {
            val sel = d.id == selectedId
            Row(modifier = Modifier
                .fillMaxWidth()
                .clickable {
                    GpuDriverHelper.selectDriver(context, d.id)
                    statusText = "已选中 ${d.displayName}（重新进入游戏后生效）"
                    refreshKey++
                }
                .padding(vertical = 6.dp)) {
                Text(if (sel) "● " else "○ ",
                    color = Color(0xFFFFD66B), fontSize = 12.sp)
                Column(modifier = Modifier.weight(1f)) {
                    Text(d.displayName,
                        color = if (sel) Color(0xFFFFD66B) else Color.White,
                        fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    Text(if (d.vendor.isBlank()) d.libraryName else "${d.vendor} · ${d.libraryName}",
                        color = Color(0xFF8899AA), fontSize = 10.sp)
                }
                Text("删除", color = Color(0xFFDD7A7A), fontSize = 11.sp,
                    modifier = Modifier
                        .clickable {
                            if (GpuDriverHelper.deleteDriver(context, d.id)) {
                                statusText = "已删除 ${d.displayName}"
                            } else {
                                statusText = "删除失败"
                            }
                            refreshKey++
                        }
                        .padding(6.dp))
            }
        }

        if (statusText.isNotBlank()) {
            Text(statusText, color = Color(0xFFFFD66B), fontSize = 11.sp,
                modifier = Modifier.padding(vertical = 4.dp))
        }

        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Text(
                "导入驱动 (.zip)",
                color = if (supported) Color(0xFFFFD66B) else Color(0xFF667788),
                fontSize = 13.sp,
                modifier = Modifier
                    .clickable(enabled = supported) {
                        zipPicker.launch(arrayOf("application/zip", "application/octet-stream", "*/*"))
                    }
                    .padding(8.dp)
            )
            Spacer(Modifier.size(12.dp))
            Text(
                "刷新",
                color = Color(0xFF8899AA),
                fontSize = 13.sp,
                modifier = Modifier
                    .clickable { refreshKey++ }
                    .padding(8.dp)
            )
        }
        Text(
            "驱动包 = adrenotools 标准格式 zip（meta.json + libvulkan_*.so），" +
                "如 Turnip（K11MCH1/AdrenoToolsDrivers 等）。安装到内部存储 " +
                "gpu_driver/ 目录；选中后重新进入游戏生效（VK 后端走自定义驱动）。",
            color = Color(0xFF667788), fontSize = 10.sp, lineHeight = 14.sp
        )
    }
}
