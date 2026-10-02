package org.citra.citra_emu.utils

import android.content.Context
import androidx.annotation.Keep
import org.json.JSONObject
import java.io.File
import java.util.zip.ZipInputStream

/**
 * ★★ 3DS GPU 驱动安装与选择（本轮新增，对齐上游 Azahar Android 行为）★★
 *
 * 需求原话："核心缺少驱动安装和选择，可以去上游查看并添加"。
 *
 * 上游（Azahar / AzaharPlus Android）的 GPU 驱动机制：
 *  - libazahar.so 内嵌 adrenotools（已实测导出
 *    Java_org_citra_citra_1emu_utils_GpuDriverHelper_supportsCustomDriverLoading
 *    与 NativeLibrary.initializeGpuDriver）；
 *  - 驱动包 = 标准 adrenotools zip（meta.json + libvulkan_*.so），
 *    安装到 <filesDir>/gpu_driver/<id>/（必须内部存储，dlopen 无法从
 *    sdcard 映射）；
 *  - 运行前经 initializeGpuDriver(hookLibDir, driverDir, libraryName,
 *    fileRedirectDir) 把选中驱动的 libraryName 传给核心 —— 空串 = 系统
 *    驱动，非空 = 自定义驱动。
 *
 * 本类提供：本地 zip 导入安装 / 已安装驱动列表 / 选中与切换 / 删除 /
 * supportsCustomDriverLoading() 探测（非 Adreno 或不支持自定义驱动加载的
 * 设备上 UI 应隐藏驱动管理入口）。
 *
 * 驱动 zip 获取（用户侧）：Turnip（freedreno / Adreno 开源驱动）等社区
 * 驱动包（K11MCH1/AdrenoToolsDrivers、MrPurple 等）下载后从本地导入。
 */
object GpuDriverHelper {

    /** 已安装驱动根目录（= AzaharEngine 传给 initializeGpuDriver 的 driverInstallPath）。 */
    fun driversRoot(context: Context): File =
        File(context.filesDir, "gpu_driver").apply { mkdirs() }

    /** 驱动选中状态持久化（SharedPreferences）。 */
    private const val PREFS = "gpu_driver_prefs"
    private const val KEY_SELECTED = "selected_driver_id"

    // ---- 原生探测（libazahar.so 导出符号，缺失时返回 false 兜底） ----

    /**
     * 当前设备是否支持自定义驱动加载（Adreno GPU + Android 10+）。
     * 注：方法名必须与 so 导出符号
     * Java_org_citra_citra_1emu_utils_GpuDriverHelper_supportsCustomDriverLoading
     * 精确一致 —— 外部用 [isCustomDriverLoadingSupported]（兑底 catch），
     * native 声明用真名。so 未加载/未导出时 UnsatisfiedLinkError → 返回 false。
     */
    @JvmStatic
    fun isCustomDriverLoadingSupported(): Boolean = try {
        supportsCustomDriverLoading()
    } catch (_: Throwable) {
        false
    }

    @Keep
    @JvmStatic
    private external fun supportsCustomDriverLoading(): Boolean

    // ---- 驱动数据模型 ----

    data class InstalledDriver(
        val id: String,               // 安装目录名（自增或 zip 名）
        val name: String,             // meta.json name
        val libraryName: String,      // libvulkan_*.so 文件名（传给核心）
        val vendor: String,           // meta.json vendor
        val version: String,          // meta.json version
        val dir: File
    ) {
        val displayName: String
            get() = if (version.isNotBlank()) "$name ($version)" else name
    }

    // ---- 安装（本地 zip 导入） ----

    sealed class InstallResult {
        object Success : InstallResult()
        data class Failed(val reason: String) : InstallResult()
    }

    /**
     * 从本地驱动 zip 安装（标准 adrenotools 格式：
     * meta.json + libvulkan_*.so；无 meta.json 时合成一份以 so 名为准）。
     * 安装目录 = <filesDir>/gpu_driver/<id>/。
     */
    fun installFromZip(context: Context, zip: File): InstallResult {
        try {
            if (!zip.isFile || zip.length() == 0L) return InstallResult.Failed("驱动包不存在或为空")
            val root = driversRoot(context)
            val id = "drv_" + System.currentTimeMillis()
            val dest = File(root, id).apply { mkdirs() }
            var metaJson: JSONObject? = null
            val soNames = mutableListOf<String>()
            ZipInputStream(zip.inputStream().buffered()).use { zis ->
                var entry = zis.nextEntry
                val buf = ByteArray(64 * 1024)
                while (entry != null) {
                    val name = entry.name.substringAfterLast('/')
                    if (name.isBlank()) { entry = zis.nextEntry; continue }
                    when {
                        name.equals("meta.json", true) -> {
                            val text = zis.readBytes().toString(Charsets.UTF_8)
                            try { metaJson = JSONObject(text) } catch (_: Throwable) {}
                        }
                        name.endsWith(".so") && name.lowercase().contains("vulkan") -> {
                            File(dest, name).outputStream().use { out ->
                                while (true) {
                                    val n = zis.read(buf)
                                    if (n < 0) break
                                    out.write(buf, 0, n)
                                }
                            }
                            soNames.add(name)
                        }
                    }
                    entry = zis.nextEntry
                }
            }
            val libName = soNames.firstOrNull()
                ?: return InstallResult.Failed("驱动包中未找到 libvulkan_*.so")
            val meta = metaJson ?: JSONObject().apply {
                put("name", libName.removePrefix("lib").removeSuffix(".so"))
                put("library_name", libName)
            }
            // 规范化 meta.json（核心侧按约定字段读取）
            meta.put("library_name", meta.optString("library_name", libName).ifBlank { libName })
            File(dest, "meta.json").writeText(meta.toString())
            return InstallResult.Success
        } catch (t: Throwable) {
            return InstallResult.Failed("安装失败: ${t.message ?: t.javaClass.simpleName}")
        }
    }

    // ---- 列表 / 选中 / 删除 ----

    fun installedDrivers(context: Context): List<InstalledDriver> {
        val root = driversRoot(context)
        return root.listFiles { f -> f.isDirectory }
            ?.mapNotNull { dir ->
                val metaFile = File(dir, "meta.json")
                if (!metaFile.isFile) return@mapNotNull null
                try {
                    val meta = JSONObject(metaFile.readText())
                    val lib = meta.optString("library_name", "")
                        .ifBlank {
                            dir.listFiles { f -> f.name.endsWith(".so") }
                                ?.firstOrNull()?.name ?: return@mapNotNull null
                        }
                    if (!File(dir, lib).isFile) return@mapNotNull null
                    InstalledDriver(
                        id = dir.name,
                        name = meta.optString("name", lib.removePrefix("lib").removeSuffix(".so")),
                        libraryName = lib,
                        vendor = meta.optString("vendor", ""),
                        version = meta.optString("version", meta.optString("driver_version", "")),
                        dir = dir
                    )
                } catch (_: Throwable) { null }
            }
            ?.sortedBy { it.name.lowercase() }
            ?: emptyList()
    }

    fun selectedDriverId(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_SELECTED, "") ?: ""

    fun selectDriver(context: Context, id: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_SELECTED, id).apply()
    }

    /**
     * 当前选中的驱动（未选中/无效 → null = 使用系统驱动）。
     */
    fun selectedDriver(context: Context): InstalledDriver? {
        val id = selectedDriverId(context)
        if (id.isBlank()) return null
        return installedDrivers(context).firstOrNull { it.id == id }
    }

    /**
     * 传给 NativeLibrary.initializeGpuDriver 的第 3 参：
     * 空串 = 系统驱动；否则 = 选中驱动的 libraryName。
     */
    fun selectedLibraryName(context: Context): String =
        selectedDriver(context)?.libraryName ?: ""

    fun deleteDriver(context: Context, id: String): Boolean {
        val root = driversRoot(context)
        val dir = File(root, id)
        if (!dir.isDirectory) return false
        if (selectedDriverId(context) == id) selectDriver(context, "")
        return dir.deleteRecursively()
    }
}
