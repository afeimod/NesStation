package com.nesstation.app.core.storage

import android.content.Context
import android.net.Uri
import com.nesstation.app.core.model.GamePlatform

/**
 * ★★ CIA 安装共享助手（本轮新增，回应"3ds核心新ui缺少安装cia按钮，安装cia后
 *   界面没有刷新游戏或者说没有读取对应目录包括游戏和dlc等"）★★
 *
 * 旧 UI（LibraryScreen）已有完整的 CIA 安装链路，但新 UI（Neon 游戏库）没有
 * 「安装CIA」入口 —— 本对象把安装流程抽成共享实现，两套 UI 都可用：
 *
 *  1. [installCias]：SAF 多选 .cia → 原生 CiaInstallWorker.installCIA 写入
 *     Azahar NAND（大文件优先真实路径直读，FUSE 不可读回退 SAF 拷贝缓存，
 *     ErrorFileNotFound 自动换通道重试 —— 与旧 UI 同一套修复链）。
 *  2. [importInstalledTitles]：枚举 NAND 已安装标题（游戏 + 更新 + DLC，
 *     经 NativeLibrary.getInstalledGamePaths()）→ 以「已安装标题」形式导入
 *     RomStore —— 解决"安装cia后界面没有刷新游戏/没有读取对应目录"：
 *     安装完成、手动刷新、甚至重启后调用都会把 NAND 里所有已装标题补进库。
 *
 * 线程：全部 IO 阻塞操作，调用方须在 Dispatchers.IO 执行；进度经
 * [CiaInstallWorker.listener]（安装线程同步回调）。
 */
object CiaInstaller {

    /** InstallStatus → 用户可读文案。 */
    fun statusText(s: org.citra.citra_emu.NativeLibrary.InstallStatus): String =
        when (s) {
            org.citra.citra_emu.NativeLibrary.InstallStatus.Success -> "成功"
            org.citra.citra_emu.NativeLibrary.InstallStatus.ErrorFailedToOpenFile -> "打开文件失败"
            org.citra.citra_emu.NativeLibrary.InstallStatus.ErrorFileNotFound -> "文件不存在"
            org.citra.citra_emu.NativeLibrary.InstallStatus.ErrorAborted -> "安装被中止"
            org.citra.citra_emu.NativeLibrary.InstallStatus.ErrorInvalid -> "CIA 无效或损坏"
            org.citra.citra_emu.NativeLibrary.InstallStatus.ErrorEncrypted ->
                "CIA 已加密（需在 3DS 系统目录放置 aes_keys.txt 密钥文件）"
        }

    /** 1 字节探测读：File.exists()/canRead() 为 true 不代表核心 native fopen
     *  一定成功（FUSE/权限层"可 stat 不可 open"场景）。 */
    private fun probeReadable(file: java.io.File): Boolean {
        if (!file.exists() || file.length() == 0L) return false
        return try {
            file.inputStream().use { it.read() } >= 0
        } catch (_: Throwable) {
            false
        }
    }

    /** SAF URI 显示名（ContentResolver 查询，失败退 lastPathSegment）。 */
    fun queryDisplayName(context: Context, uri: Uri): String? {
        return try {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (nameIndex >= 0 && cursor.moveToFirst()) cursor.getString(nameIndex) else null
            }
        } catch (_: Exception) {
            uri.lastPathSegment
        }
    }

    /**
     * 安装选中的 .cia 文件到 Azahar NAND（阻塞，须 IO 线程）。
     *
     * @return 结果消息（成功 N 个 / 失败明细）
     */
    fun installCias(
        context: Context,
        uris: List<Uri>,
        onProgress: (name: String, max: Int, progress: Int) -> Unit
    ): String {
        val lib = org.citra.citra_emu.NativeLibrary
        // 与 AzaharEngine.loadRom 相同的用户目录初始化（NAND 路径解析依赖它）
        val userDir = java.io.File(context.filesDir, "azahar").apply { mkdirs() }.absolutePath
        try {
            // ★ CIA 安装失败修复（与旧 UI 同款）：注册 NesStationHost —— 原生
            //   installCIA 经 android_utils.h 的 getUserDirectory() Java 回调
            //   定位 NAND/SDMC 基路径；未注册时回落应用私有目录之外 → 安装
            //   ErrorFailedToOpenFile。
            org.citra.citra_emu.NativeLibrary.NesStationHost.register(object : org.citra.citra_emu.NativeLibrary.Host {
                override fun appContext(): android.content.Context? = context.applicationContext
                override fun azaharUserDirectory(): String = userDir
                override fun onEmulationExited(result: Int) {}
            })
            // 参考环境系统数据种子（nand 系统数据/字体/seeddb 就绪后安装链路
            // 与参考 APK 一致）
            com.nesstation.app.core.storage.AzaharSystemData.ensureSeeded(
                context, java.io.File(userDir)
            )
            // ⚠ 先 createLogFile() 再 createConfigFile()/reloadSettings() ——
            //   日志后端未初始化时 Config::ReadValues() 触发 native abort。
            com.nesstation.app.core.jni.AzaharNative.initConfigPipeline(userDir)
            try { lib.reloadSettings() } catch (_: Throwable) {}
        } catch (t: Throwable) {
            android.util.Log.w("CiaInstaller", "azahar user dir init failed", t)
        }

        var successCount = 0
        val failures = mutableListOf<String>()
        uris.forEach { uri ->
            val name = queryDisplayName(context, uri) ?: "cia_${System.currentTimeMillis()}.cia"
            if (!name.endsWith(".cia", ignoreCase = true)) {
                failures += "$name：不是 .cia 文件"
                return@forEach
            }
            // ★ 大文件安装：优先真实路径直读（对齐参考 APK getNativePath 语义），
            //   避免把可达数 GB 的 CIA 全量拷进内部 cacheDir；仅当真实路径不可用
            //   （非主存储/权限未授予）时才回退 SAF 拷贝到缓存。
            var direct = com.nesstation.app.ui.emulator.resolveNativeRomFile(uri.toString())
            if (direct != null && !probeReadable(direct)) direct = null
            val tmp = if (direct == null) {
                java.io.File(
                    context.cacheDir,
                    "cia_install_${System.currentTimeMillis()}_" +
                        name.replace(Regex("[^\\w.-]"), "_")
                )
            } else null
            try {
                if (tmp != null) {
                    val opened = context.contentResolver.openInputStream(uri)
                    if (opened == null) {
                        failures += "$name：无法读取所选文件（文件可能已被移动或权限被收回）"
                        return@forEach
                    }
                    opened.use { input -> tmp.outputStream().use { input.copyTo(it) } }
                    if (!probeReadable(tmp)) {
                        failures += "$name：临时副本写入后不可读（存储空间不足？）"
                        return@forEach
                    }
                }
                var installPath = direct?.absolutePath ?: tmp!!.absolutePath
                var lastUi = 0L
                org.citra.citra_emu.utils.CiaInstallWorker.listener = { max: Int, progress: Int ->
                    val now = System.currentTimeMillis()
                    if (now - lastUi > 300) {
                        lastUi = now
                        onProgress(name, max, progress)
                    }
                }
                var status = try {
                    // ★ '!' 前缀 = 原生绝对路径标记：Azahar 原生侧 InstallCIA
                    //   经 TranslateFilePath 翻译，裸绝对路径会被拼成
                    //   <userDir>/<原路径> → 永远"不存在" → ErrorFileNotFound。
                    org.citra.citra_emu.utils.CiaInstallWorker().installCIA("!$installPath")
                } finally {
                    org.citra.citra_emu.utils.CiaInstallWorker.listener = null
                }
                // ★ ErrorFileNotFound 自动换通道重试（直读 ↔ SAF 拷贝互换）
                if (status == org.citra.citra_emu.NativeLibrary.InstallStatus.ErrorFileNotFound) {
                    android.util.Log.w("CiaInstaller",
                        "CIA install ErrorFileNotFound on $installPath, retrying via alternate channel")
                    if (tmp == null && direct != null) {
                        val retry = java.io.File(
                            context.cacheDir,
                            "cia_install_${System.currentTimeMillis()}_" +
                                name.replace(Regex("[^\\w.-]"), "_")
                        )
                        try {
                            context.contentResolver.openInputStream(uri)?.use { input ->
                                retry.outputStream().use { input.copyTo(it) }
                            }
                            if (probeReadable(retry)) {
                                org.citra.citra_emu.utils.CiaInstallWorker.listener = { max: Int, progress: Int ->
                                    val now = System.currentTimeMillis()
                                    if (now - lastUi > 300) {
                                        lastUi = now
                                        onProgress("$name（重试）", max, progress)
                                    }
                                }
                                try {
                                    status = org.citra.citra_emu.utils.CiaInstallWorker()
                                        .installCIA("!" + retry.absolutePath)
                                } finally {
                                    org.citra.citra_emu.utils.CiaInstallWorker.listener = null
                                }
                                installPath = retry.absolutePath
                            }
                        } catch (_: Throwable) {
                        } finally {
                            try { retry.delete() } catch (_: Throwable) {}
                        }
                    }
                }
                if (status == org.citra.citra_emu.NativeLibrary.InstallStatus.Success) {
                    successCount++
                } else {
                    failures += "$name：${statusText(status)}（$installPath）"
                }
            } finally {
                tmp?.delete()
            }
        }

        // 安装产物入库：NAND 已安装标题 → 3DS 游戏库（含游戏本体 + 更新 + DLC）
        val addedTitles = importInstalledTitles(context, userDir)

        return buildString {
            append("CIA 安装完成：成功 $successCount 个")
            if (failures.isNotEmpty()) {
                append("，失败 ${failures.size} 个（${failures.joinToString("；")}）")
            }
            if (addedTitles > 0) {
                append("\n已加入 $addedTitles 个数字版标题（游戏/更新/DLC）到 3DS 游戏库")
            } else if (successCount > 0) {
                append("\n（数字版标题已在此前入库）")
            }
        }
    }

    /**
     * ★★ 枚举 NAND 已安装标题并导入 RomStore（安装后刷新 / 手动刷新共用）。
     *
     * 解决"安装cia后界面没有刷新游戏或者说没有读取对应目录包括游戏和dlc等"：
     * getInstalledGamePaths() 枚举 NAND 里全部已安装内容（数字版游戏本体、
     * 更新包、DLC），以 file:// 前缀真实路径入库（可直接启动）。已在库中的
     * 路径自动跳过（幂等，可重复调用）。
     *
     * @return 新导入的标题数（0 = 无新增）
     */
    fun importInstalledTitles(context: Context, userDirOverride: String? = null): Int {
        val lib = org.citra.citra_emu.NativeLibrary
        val userDir = userDirOverride
            ?: java.io.File(context.filesDir, "azahar").apply { mkdirs() }.absolutePath
        return try {
            // 枚举前先确保原生用户目录已初始化（进程刚启动 / 未进过 3DS 游戏时
            // setUserDirectory 尚未调用，getInstalledGamePaths 会按未初始化的
            // 路径枚举 → 返回空）。这里做一次轻量初始化（幂等）。
            try {
                org.citra.citra_emu.NativeLibrary.NesStationHost.register(object : org.citra.citra_emu.NativeLibrary.Host {
                    override fun appContext(): android.content.Context? = context.applicationContext
                    override fun azaharUserDirectory(): String = userDir
                    override fun onEmulationExited(result: Int) {}
                })
                com.nesstation.app.core.jni.AzaharNative.initConfigPipeline(userDir)
            } catch (_: Throwable) {}
            val installed = lib.getInstalledGamePaths()
            val known = RomStore.loadAll(context).mapNotNull { it.romPath }.toSet()
            val items = installed.mapNotNull { (rawPath, _) ->
                // '!'-userDir 模式：原生返回 "!<userDir>/…/xxx.app" 绝对路径；
                // 剥掉 '!' 前缀即真实路径。剥完不存在时再按根相对路径拼
                // userDir 兜底 —— 两种模式收敛到同一真实文件。
                var path = rawPath
                if (path.startsWith("!")) path = path.substring(1)
                if (!java.io.File(path).exists()) {
                    val joined = java.io.File(userDir, path.removePrefix("/")).absolutePath
                    if (java.io.File(joined).exists()) path = joined
                }
                val fileUri = "file://$path"
                if (fileUri in known) return@mapNotNull null
                // NAND 路径形如 …/nand/title/<高ID>/<低ID>/content/xxxx.app
                val lowId = path.substringBeforeLast("/content/", "")
                    .substringAfterLast('/')
                    .uppercase().padStart(8, '0')
                val title = if (lowId.length == 8) "已安装标题 $lowId"
                    else java.io.File(path).nameWithoutExtension
                Triple(title, fileUri, GamePlatform.N3DS)
            }
            if (items.isNotEmpty()) {
                RomStore.importGames(context, items).size
            } else 0
        } catch (t: Throwable) {
            android.util.Log.w("CiaInstaller", "enumerate installed titles failed", t)
            0
        }
    }
}
