package com.nesstation.app.core.storage

import android.content.Context
import android.net.Uri
import android.os.Build
import com.nesstation.app.core.jni.CemuNative
import info.cemu.cemu.nativeinterface.NativeSettings
import org.json.JSONObject
import java.io.File
import java.util.zip.ZipInputStream

/**
 * ★★ Wii U（Cemu-AZ）自定义 Vulkan 驱动管理 —— 上游 Cemu-AZ
 * common/customdrivers/CustomDrivers.kt + CustomDriversViewModel 安装逻辑移植 ★★
 *（NesStation 未引入 kotlinx-serialization，meta.json 用 org.json 解析，
 *  语义/校验规则与上游逐字段一致）
 *
 * 驱动包结构（adrenotools 约定，zip 内根级）：
 *   meta.json  — schemaVersion=1 / name / description / author / packageVersion
 *                / vendor / driverVersion / minApi / libraryName
 *   <libraryName>.so（如 libvulkan_turnip.so）
 *
 * 安装目录：<filesDir>/cemu/customDrivers/<uuid>/（= Cemu 用户目录下
 * customDrivers/，与上游 getCustomDriversDir() = getUserDataPath/customDrivers 一致）。
 *
 * 生效机制：NativeSettings.setCustomDriverPath(path) 写入 settings.xml；
 * 核心在 initializeRenderer → dlopen_vulkan_loader 时经 adrenotools 加载
 * （CUSTOM_DRIVER_LIB_NAME 拷贝到内部目录后 dlopen）—— 因此**切换驱动后需
 * 重启游戏生效**，与上游一致。
 */
object CemuDriverStore {

    const val META_FILE_NAME = "meta.json"
    const val SUPPORTED_SCHEMA_VERSION = 1
    const val CUSTOM_DRIVERS_DIR_NAME = "customDrivers"

    /** 上游 DriverMetadata 同构（org.json 解析）。 */
    data class CemuDriverMetadata(
        val schemaVersion: Int,
        val name: String,
        val description: String,
        val author: String,
        val packageVersion: String,
        val vendor: String,
        val driverVersion: String,
        val minApi: Int,
        val libraryName: String
    )

    data class CemuDriver(
        val path: String,
        val metadata: CemuDriverMetadata
    )

    enum class InstallStatus { INSTALLED, ALREADY_INSTALLED, ERROR_INSTALLING }

    /** 驱动安装目录（<filesDir>/cemu/customDrivers）。 */
    fun getCustomDriversDir(ctx: Context): File =
        File(File(ctx.filesDir, "cemu"), CUSTOM_DRIVERS_DIR_NAME)

    /** 扫描已安装驱动（读 meta.json，损坏/不合规的条目跳过）。 */
    fun parseInstalledDrivers(ctx: Context): List<CemuDriver> {
        val dir = getCustomDriversDir(ctx)
        val files = dir.listFiles() ?: return emptyList()
        val drivers = ArrayList<CemuDriver>()
        for (d in files) {
            if (!d.isDirectory) continue
            val meta = parseMetaFile(File(d, META_FILE_NAME)) ?: continue
            drivers.add(CemuDriver(path = d.absolutePath, metadata = meta))
        }
        // 稳定排序（上游 sortBy name）
        drivers.sortBy { it.metadata.name }
        return drivers
    }

    private fun parseMetaFile(file: File): CemuDriverMetadata? {
        return try {
            if (!file.isFile) return null
            val root = JSONObject(file.readText())
            CemuDriverMetadata(
                schemaVersion = root.optInt("schemaVersion", -1),
                name = root.optString("name", ""),
                description = root.optString("description", ""),
                author = root.optString("author", ""),
                packageVersion = root.optString("packageVersion", ""),
                vendor = root.optString("vendor", ""),
                driverVersion = root.optString("driverVersion", ""),
                minApi = root.optInt("minApi", 0),
                libraryName = root.optString("libraryName", "")
            )
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * 从 zip 安装驱动（上游 installDriver 同款校验链）：
     *  1. 解压到 <cemu 用户目录>/<uuid> 临时目录（zip-slip 防护）；
     *  2. meta.json 存在 + schemaVersion 匹配 + minApi 不高于当前系统 +
     *     libraryName 文件存在；
     *  3. 与已装驱动元数据查重；
     *  4. 移入 customDrivers/<uuid>/。
     * @return 安装结果（[InstallStatus.INSTALLED] 携带新驱动对象）
     */
    fun installDriverFromZip(ctx: Context, zipUri: Uri): Pair<InstallStatus, CemuDriver?> {
        val userDir = File(ctx.filesDir, "cemu").apply { mkdirs() }
        val tempDir = File(userDir, java.util.UUID.randomUUID().toString())
        try {
            tempDir.mkdirs()
            ctx.contentResolver.openInputStream(zipUri)?.use { input ->
                unzipSafely(input, tempDir)
            } ?: return InstallStatus.ERROR_INSTALLING to null

            val meta = parseMetaFile(File(tempDir, META_FILE_NAME))
                ?: return InstallStatus.ERROR_INSTALLING to null
            if (meta.schemaVersion != SUPPORTED_SCHEMA_VERSION ||
                meta.minApi > Build.VERSION.SDK_INT ||
                meta.libraryName.isBlank() ||
                !File(tempDir, meta.libraryName).exists()
            ) {
                return InstallStatus.ERROR_INSTALLING to null
            }

            // 同元数据驱动已安装（上游 any { it.metadata == metadata }）
            if (parseInstalledDrivers(ctx).any { it.metadata == meta }) {
                return InstallStatus.ALREADY_INSTALLED to null
            }

            val driversDir = getCustomDriversDir(ctx).apply { mkdirs() }
            val destDir = File(driversDir, tempDir.name)
            if (!tempDir.renameTo(destDir)) {
                return InstallStatus.ERROR_INSTALLING to null
            }
            return InstallStatus.INSTALLED to CemuDriver(destDir.absolutePath, meta)
        } catch (_: Throwable) {
            return InstallStatus.ERROR_INSTALLING to null
        } finally {
            tempDir.deleteRecursively()
        }
    }

    /** 删除驱动（若为当前选中，同时回退系统驱动）。 */
    fun deleteDriver(ctx: Context, driver: CemuDriver) {
        if (getCurrentDriverPath() == driver.path) {
            selectSystemDriver()
        }
        try {
            File(driver.path).deleteRecursively()
        } catch (_: Throwable) {}
    }

    /**
     * 当前生效的驱动目录（null = 系统驱动）。核心未加载时返回 null
     *（调用方在核心可用时才允许操作驱动）。
     */
    fun getCurrentDriverPath(): String? {
        if (!ensureCore()) return null
        return try {
            NativeSettings.getCustomDriverPath()
        } catch (_: Throwable) {
            null
        }
    }

    /** 选择系统驱动（清空 custom_driver_path）。 */
    fun selectSystemDriver(): Boolean {
        if (!ensureCore()) return false
        return try {
            NativeSettings.setCustomDriverPath(null)
            NativeSettings.saveSettings()
            true
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * 选择自定义驱动。★ 立即写入核心 settings.xml 并保存（上游
     * CustomDriversViewModel.setDriverSelected 同款即时生效链路），
     * 但 adrenotools 在渲染器初始化时才 dlopen —— 重启游戏后生效。
     */
    fun selectDriver(driver: CemuDriver): Boolean {
        if (!ensureCore()) return false
        return try {
            NativeSettings.setCustomDriverPath(driver.path)
            NativeSettings.saveSettings()
            true
        } catch (_: Throwable) {
            false
        }
    }

    /** 平台是否支持加载自定义驱动（上游 supportsLoadingCustomDriver）。 */
    fun supportsLoadingCustomDriver(): Boolean {
        if (!ensureCore()) return false
        return try {
            info.cemu.cemu.nativeinterface.NativeEmulation.supportsLoadingCustomDriver()
        } catch (_: Throwable) {
            false
        }
    }

    private fun ensureCore(): Boolean = CemuNative.ensureLoaded()

    /**
     * 安全解压（zip-slip 防护：条目规范化路径必须落在 target 内）。
     * 上游 ZipUtils.unzip 的安全等价实现。
     */
    private fun unzipSafely(input: java.io.InputStream, targetDir: File) {
        ZipInputStream(input).use { zis ->
            val targetPath = targetDir.canonicalPath + File.separator
            while (true) {
                val entry = zis.nextEntry ?: break
                val out = File(targetDir, entry.name)
                if (!out.canonicalPath.startsWith(targetPath)) {
                    throw SecurityException("zip 条目越界（zip-slip）: ${entry.name}")
                }
                if (entry.isDirectory) {
                    out.mkdirs()
                } else {
                    out.parentFile?.mkdirs()
                    out.outputStream().use { os -> zis.copyTo(os) }
                }
                zis.closeEntry()
            }
        }
    }
}
