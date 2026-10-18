// SPDX-FileCopyrightText: Copyright 2026 Eden Emulator Project
// SPDX-License-Identifier: GPL-2.0-or-later
//
// ★ NesStation 集成版 —— 自定义 GPU 驱动（Adreno/Turnip 系 zip）的
//   meta.json 解析。上游依赖 FileUtil；此处自包含（org.json 为 Android
//   平台内置），字段集与上游 GpuDriverMetadata 一致。

package org.yuzu.yuzu_emu.utils

import android.graphics.SurfaceTexture
import android.net.Uri
import android.os.Build
import android.view.Surface
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.util.zip.ZipFile

data class GpuDriverMetadata(
    var name: String? = null,
    var description: String? = null,
    var author: String? = null,
    var vendor: String? = null,
    var version: String? = null,
    var minApi: Int = 0,
    var libraryName: String? = null
) {
    constructor(metadataFile: File) : this() {
        if (!metadataFile.exists()) return
        // 与上游一致：meta.json 限 500KB，防超大文件卡 UI
        if (metadataFile.length() > MAX_META_SIZE_BYTES) return
        try {
            FileInputStream(metadataFile).use { stream ->
                parse(stream.readBytes().decodeToString())
            }
        } catch (_: Exception) {
        }
    }

    constructor(input: java.io.InputStream, size: Long) : this() {
        if (size > MAX_META_SIZE_BYTES) return
        try {
            parse(input.readBytes().decodeToString())
        } catch (_: Exception) {
        }
    }

    private fun parse(json: String?) {
        if (json.isNullOrEmpty()) return
        val obj = JSONObject(json)
        name = obj.optString("name", null)
        description = obj.optString("description", null)
        author = obj.optString("author", null)
        vendor = obj.optString("vendor", null)
        version = obj.optString("version", null)
        libraryName = obj.optString("library", null)
        minApi = obj.optInt("minApi", 0)
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as GpuDriverMetadata
        if (name != other.name) return false
        if (description != other.description) return false
        if (author != other.author) return false
        if (vendor != other.vendor) return false
        if (version != other.version) return false
        if (minApi != other.minApi) return false
        if (libraryName != other.libraryName) return false
        return true
    }

    override fun hashCode(): Int {
        var result = name?.hashCode() ?: 0
        result = 31 * result + (description?.hashCode() ?: 0)
        result = 31 * result + (author?.hashCode() ?: 0)
        result = 31 * result + (vendor?.hashCode() ?: 0)
        result = 31 * result + (version?.hashCode() ?: 0)
        result = 31 * result + minApi
        result = 31 * result + (libraryName?.hashCode() ?: 0)
        return result
    }

    companion object {
        private const val MAX_META_SIZE_BYTES = 500000
    }
}

/**
 * Eden（Switch）自定义 GPU 驱动管理 —— 上游 GpuDriverHelper 的自包含移植。
 *
 * 目录约定（全部位于 <filesDir>/eden/ 之下，与 CemuDriverStore 的
 * <filesDir>/cemu/gpuDrivers 同构）：
 *   /gpu_driver/          当前启用驱动解包目录（空 = 系统驱动）
 *   /gpu_driver_storage/  已导入驱动 zip 归档（可切换/删除）
 *   <externalFilesDir>/eden_gpu/vk_file_redirect/  文件重定向目录
 *   hook 库 = nativeLibraryDir（useLegacyPackaging 解压态 dlopen 最可靠）
 */
object GpuDriverHelper {
    private const val META_JSON_FILENAME = "meta.json"

    @JvmStatic var driverInstallationPath: String? = null
        private set
    @JvmStatic var driverStoragePath: String? = null
        private set
    @JvmStatic var hookLibPath: String? = null
        private set
    private var fileRedirectionPath: String? = null

    /** 由 EdenEngine 在核心初始化前调用（appContext 可用后）。 */
    @JvmStatic
    fun initializeDriverParameters(
        appContext: android.content.Context,
        nativeLibraryDir: String
    ) {
        try {
            fileRedirectionPath = File(
                appContext.getExternalFilesDir(null), "eden_gpu/vk_file_redirect"
            ).canonicalPath + "/"
            driverInstallationPath = File(
                appContext.filesDir, "eden/gpu_driver"
            ).canonicalPath + "/"
            driverStoragePath = File(
                appContext.filesDir, "eden/gpu_driver_storage"
            ).canonicalPath + "/"
        } catch (e: java.io.IOException) {
            throw RuntimeException(e)
        }
        initializeDirectories()
        hookLibPath = "$nativeLibraryDir/"
        NativeFreedrenoConfig.setFreedrenoBasePath(appContext.cacheDir.absolutePath)
        NativeFreedrenoConfig.initializeFreedrenoConfig()
        NativeFreedrenoConfig.reloadFreedrenoConfig()
        // 初始化 GPU 驱动（hook 库目录 / 自定义驱动目录 / 当前驱动名 / 重定向）
        org.yuzu.yuzu_emu.NativeLibrary.initializeGpuDriver(
            hookLibPath,
            driverInstallationPath,
            installedCustomDriverData.libraryName,
            fileRedirectionPath
        )
    }

    /** 已导入驱动 zip 列表（展示用）。 */
    fun getDrivers(): MutableList<Pair<String, GpuDriverMetadata>> {
        val zips = File(driverStoragePath!!).listFiles() ?: return mutableListOf()
        return zips.mapNotNull { zip ->
            val meta = getMetadataFromZip(zip)
            if (meta.name != null) Pair(zip.path, meta) else null
        }.sortedByDescending { it.second.name }.toMutableList()
    }

    /** 切回系统默认驱动（清空解包目录 = 核心回落系统驱动）。 */
    fun installDefaultDriver(
        appContext: android.content.Context,
        nativeLibraryDir: String
    ) {
        File(driverInstallationPath!!).deleteRecursively()
        initializeDriverParameters(appContext, nativeLibraryDir)
    }

    /** 从 zip（SAF 选中）导入并启用自定义驱动。 */
    fun installCustomDriver(
        appContext: android.content.Context,
        nativeLibraryDir: String,
        driverUri: Uri
    ): InstallResult {
        initializeDirectories()
        val copied = copyUri(driverStoragePath!!, driverUri)
            ?: return InstallResult.INVALID_ZIP
        val metadata = getMetadataFromZip(copied)
        if (metadata.name == null || metadata.libraryName == null) {
            copied.delete()
            return InstallResult.INVALID_ZIP
        }
        if (metadata.minApi > Build.VERSION.SDK_INT) {
            copied.delete()
            return InstallResult.MIN_API
        }
        // 先回退系统驱动（防选了个坏驱动后全灭），再解包启用
        File(driverInstallationPath!!).deleteRecursively()
        if (!unzip(copied, File(driverInstallationPath!!))) {
            copied.delete()
            return InstallResult.INVALID_ZIP
        }
        initializeDriverParameters(appContext, nativeLibraryDir)
        return if (installedCustomDriverData.name != null) InstallResult.INSTALLED
        else InstallResult.INVALID_ZIP
    }

    /** 删除已导入驱动 zip 归档（仅删归档；启用中的解包目录不受影响，
     *   需禁用请调 [installDefaultDriver]）。 */
    fun deleteDriver(driverZipPath: String) {
        File(driverZipPath).delete()
    }

    fun getMetadataFromZip(driver: File): GpuDriverMetadata {
        if (!driver.exists()) return GpuDriverMetadata()
        try {
            ZipFile(driver).use { zf ->
                val entries = zf.entries()
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    if (!entry.isDirectory && entry.name.lowercase().contains(".json")) {
                        zf.getInputStream(entry).use {
                            return GpuDriverMetadata(it, entry.size)
                        }
                    }
                }
            }
        } catch (_: Exception) {
        }
        return GpuDriverMetadata()
    }

    external fun supportsCustomDriverLoading(): Boolean

    external fun getSystemDriverInfo(
        surface: Surface = Surface(SurfaceTexture(true)),
        hookLibPath: String
    ): Array<String>?

    external fun getGpuModel(
        surface: Surface = Surface(SurfaceTexture(true)),
        hookLibPath: String
    ): String?

    fun isAdrenoGpu(): Boolean = try {
        supportsCustomDriverLoading()
    } catch (e: Exception) {
        false
    }

    val installedCustomDriverData: GpuDriverMetadata
        get() = GpuDriverMetadata(File(driverInstallationPath, META_JSON_FILENAME))

    fun initializeDirectories() {
        listOf(
            File(fileRedirectionPath!!),
            File(driverInstallationPath!!),
            File(driverStoragePath!!)
        ).forEach { if (!it.exists()) it.mkdirs() }
    }

    // ---- 内部小工具（不依赖上游 FileUtil）----
    private fun copyUri(destDir: String, uri: Uri): File? = try {
        val cr = ContextHolder.resolver ?: return null
        val name = queryDisplayName(cr, uri)
            ?: uri.lastPathSegment?.substringAfterLast('/') ?: "driver.zip"
        val dst = File(destDir, name)
        cr.openInputStream(uri)?.use { input ->
            dst.outputStream().use { output -> input.copyTo(output) }
        } ?: return null
        if (dst.length() == 0L) { dst.delete(); null } else dst
    } catch (e: Exception) { null }

    private fun queryDisplayName(
        resolver: android.content.ContentResolver,
        uri: Uri
    ): String? = try {
        resolver.query(uri, null, null, null, null)?.use { cursor ->
            val idx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && cursor.moveToFirst()) cursor.getString(idx) else null
        }
    } catch (e: Exception) { null }

    private fun unzip(zipFile: File, destDir: File): Boolean = try {
        destDir.mkdirs()
        java.util.zip.ZipInputStream(FileInputStream(zipFile)).use { zis ->
            var entry = zis.nextEntry
            val buf = ByteArray(64 * 1024)
            while (entry != null) {
                val out = File(destDir, entry.name)
                if (!entry.isDirectory) {
                    out.parentFile?.mkdirs()
                    java.io.FileOutputStream(out).use { fos ->
                        while (true) {
                            val n = zis.read(buf)
                            if (n < 0) break
                            fos.write(buf, 0, n)
                        }
                    }
                } else {
                    out.mkdirs()
                }
                entry = zis.nextEntry
            }
        }
        true
    } catch (e: Exception) { false }

    enum class InstallResult { INSTALLED, INVALID_ZIP, MIN_API }

    /** ContentResolver 桥（由 EdenEngine 注入）—— 上游依赖
     *  YuzuApplication 单例，NesStation 集成改为显式注入。 */
    object ContextHolder {
        @JvmStatic var resolver: android.content.ContentResolver? = null
    }
}
