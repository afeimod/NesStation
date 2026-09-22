package com.nesstation.app.core.dc

import android.content.Context
import android.util.Log
import java.io.File

/**
 * Flycast (Dreamcast / NAOMI / AtomisWave 核心) 目录约定。
 *
 * Flycast 独立版把全部内容放在一个「home」目录下（对应它自身的
 * emu.cfg / data/ / mappings/ / boxart/ 布局）：
 *
 * ```
 * <filesDir>/dc/                 ← home（写入 SharedPreferences "home_directory"，
 *                                  由 com.flycast.emulator.BaseGLActivity 读取）
 * ├── emu.cfg                    ← 全部核心配置（Kotlin 侧由 [FlycastConfig] 读写）
 * ├── data/                      ← 默认 BIOS / VMU / 存档目录
 * │   ├── dc_boot.bin            ← Dreamcast BIOS ROM（必需，DC 光盘/卡带游戏）
 * │   ├── dc_flash.bin           ← Dreamcast 闪存（必需；也接受 dc_bios.bin）
 * │   ├── vmu_save_A1.bin …      ← VMU（核心首次启动自动从内嵌模板创建）
 * │   └── *.state                ← 即时存档（核心 GUI 菜单里存读）
 * └── mappings/                  ← 按键映射（核心 GUI 里配置）
 * ```
 *
 * 与其它平台（genesis/、pce/、nds/ …）保持一致放在内部 filesDir 下。
 */
object FlycastPaths {

    private const val TAG = "FlycastPaths"

    /** home 目录 = <filesDir>/dc（与 BaseGLActivity 的 "home_directory" 偏好一致） */
    fun homeDir(ctx: Context): File = File(ctx.filesDir, "dc")

    /** data 目录 = <home>/data（BIOS / VMU / 即时存档） */
    fun dataDir(ctx: Context): File = File(homeDir(ctx), "data").apply { mkdirs() }

    /** emu.cfg 文件 */
    fun configFile(ctx: Context): File = File(homeDir(ctx), "emu.cfg")

    /** Dreamcast BIOS boot ROM（dc_boot.bin，也接受 dc_bios.bin 命名） */
    fun bootBios(ctx: Context): File? {
        val data = dataDir(ctx)
        return listOf("dc_boot.bin", "dc_bios.bin")
            .map { File(data, it) }
            .firstOrNull { it.exists() && it.length() > 0L }
    }

    /** Dreamcast 闪存 dc_flash.bin */
    fun flashBios(ctx: Context): File? {
        val data = dataDir(ctx)
        return listOf("dc_flash.bin", "dc_flash_wb.bin")
            .map { File(data, it) }
            .firstOrNull { it.exists() && it.length() > 0L }
    }

    /** 是否具备启动 DC 商业游戏所需 BIOS（Naomi/AtomisWave zip 无需） */
    fun hasConsoleBios(ctx: Context): Boolean = bootBios(ctx) != null && flashBios(ctx) != null

    /**
     * 启动前把 home 目录写进默认 SharedPreferences（key "home_directory"，
     * 与 com.flycast.emulator.BaseGLActivity / config.Config.pref_home 读取处一致）。
     * 必须在首次启动 NativeGLActivity 之前调用，否则核心会用它自己的默认
     * getExternalFilesDir(null) 作为 home，导致 BIOS 导入目录对不上。
     */
    fun ensureHomePref(ctx: Context) {
        val home = homeDir(ctx)
        if (!home.exists()) home.mkdirs()
        File(home, "data").mkdirs()
        try {
            android.preference.PreferenceManager
                .getDefaultSharedPreferences(ctx)
                .edit()
                .putString("home_directory", home.absolutePath)
                .apply()
        } catch (t: Throwable) {
            Log.w(TAG, "ensureHomePref failed", t)
        }
    }

    /**
     * 从 assets/dc/ 播种 BIOS（私有构建可选：把 dc_boot.bin / dc_flash.bin 放进
     * app/src/main/assets/dc/ 后重新打包，启动时自动识别解压 —— 与 fbneo/genesis/
     * pce 的「打包到 APK（私有构建）」方式完全一致）。
     * 已存在的用户导入文件优先，不覆盖。
     */
    fun ensureBiosFromAssets(ctx: Context) {
        val data = dataDir(ctx)
        for (name in listOf("dc_boot.bin", "dc_bios.bin", "dc_flash.bin", "dc_flash_wb.bin")) {
            val dest = File(data, name)
            if (dest.exists() && dest.length() > 0L) continue
            try {
                ctx.assets.open("dc/$name").use { input ->
                    dest.outputStream().use { output -> input.copyTo(output) }
                }
                Log.i(TAG, "DC BIOS seeded from assets: ${dest.name} (${dest.length()} bytes)")
            } catch (_: java.io.FileNotFoundException) {
                // assets 未打包该文件 —— 用户需手动导入，正常路径
            } catch (t: Throwable) {
                Log.w(TAG, "Failed to seed $name from assets", t)
                dest.delete()
            }
        }
    }
}
