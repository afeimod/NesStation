package com.nesstation.app

import android.app.Application
import android.util.Log
import com.nesstation.app.core.engine.NesEngine
import com.nesstation.app.core.engine.SnesEngine
import com.nesstation.app.core.engine.GbaEngine
import com.nesstation.app.core.engine.DosEngine
import com.nesstation.app.core.engine.FbNeoEngine
import com.nesstation.app.core.engine.GenesisEngine
import com.nesstation.app.core.engine.PceEngine
import com.nesstation.app.core.engine.PsxEngine
import com.nesstation.app.core.engine.Psx2Engine
import com.nesstation.app.core.engine.NdsEngine
import com.nesstation.app.core.engine.DcEngine
import com.nesstation.app.core.storage.AppContainer
import com.nesstation.app.core.storage.RomStore
import com.nesstation.app.core.storage.SettingsRepository
import java.io.File

/**
 * Application entry point.
 *
 * Design rules (learned from crash logs):
 *  1. onCreate() must NEVER throw — no matter what fails, the UI must load.
 *  2. NO eager initialisation of third-party libs (Room, DataStore, JNI) in
 *     onCreate(). Everything is lazy so a missing/stripped class degrades
 *     gracefully instead of producing ExceptionInInitializerError.
 *  3. A global UncaughtExceptionHandler logs every uncaught throw and
 *     swallows non-fatal ones so a rogue background thread can't kill the app.
 */
class NesApp : Application() {
    // DC (Dreamcast) 已改为与其他核心一致的 libretro Flycast 集成
    // （libdccore.so + libflycast_libretro_android.so，见 core/jni/dc_loader.cpp），
    // Application 不再继承 Flycast 独立模拟器的 Emulator 类。

    override fun onCreate() {
        super.onCreate()

        // 1. Install global crash guard FIRST — before anything else.
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            Log.e("NesApp", "Uncaught on ${thread.name}", throwable)
            // For the main thread we still let the default handler run so the
            // user sees the dialog; for background threads we swallow to keep
            // the app alive.
            if (thread === Thread.currentThread() && thread.name == "main") {
                previous?.uncaughtException(thread, throwable)
            }
        }

        // 2. Set the singleton reference — this is safe, just an assignment.
        instance = this

        // 3. Initialize J2ME ContextHolder FIRST so Config's static block
        //    can get a valid app context when any J2ME class is loaded.
        tryInit("J2ME-ContextHolder") { javax.microedition.util.ContextHolder.setApplication(this) }

        // 4. Initialise subsystems ONE BY ONE. Each is wrapped in its own
        //    try-catch so a failure in one doesn't prevent the others.
        tryInit("SettingsRepository") { SettingsRepository.init(this) }
        tryInit("AppContainer")       { _container = AppContainer(this) }
        tryInit("NesEngine")          { NesEngine.ensureLoaded() }
        tryInit("SnesEngine")         { SnesEngine.ensureLoaded() }
        tryInit("GbaEngine")          { GbaEngine.ensureLoaded() }
        tryInit("DosEngine")          {
            // Set the app context first so DosNative can locate the prebuilt
            // libdosbox_pure_libretro_android.so in the app's native lib dir.
            com.nesstation.app.core.jni.DosNative.appContext = this
            DosEngine.ensureLoaded()
        }
        tryInit("FbNeoEngine")         {
            // FBNeo arcade core — dlopen()s libfbneo_libretro_android.so.
            com.nesstation.app.core.jni.FbNeoNative.appContext = this
            FbNeoEngine.ensureLoaded()
        }
        tryInit("GenesisEngine")       {
            // Genesis-Plus-GX SEGA core — dlopen()s
            // libgenesis_plus_gx_libretro_android.so.
            com.nesstation.app.core.jni.GenesisNative.appContext = this
            GenesisEngine.ensureLoaded()
        }
        tryInit("PceEngine")           {
            // Geargrafx PCE core — dlopen()s
            // libgeargrafx_libretro_android.so.
            com.nesstation.app.core.jni.PceNative.appContext = this
            PceEngine.ensureLoaded()
        }
        tryInit("PsxEngine")           {
            // PCSX-ReARMed PSX core — dlopen()s
            // libpcsx_rearmed_libretro_android.so.
            com.nesstation.app.core.jni.PsxNative.appContext = this
            PsxEngine.ensureLoaded()
        }
        tryInit("Psx2Engine")          {
            // PCEE2 (PCSX2) PS2 core — dlopen()s libpcee2_libretro_android.so.
            com.nesstation.app.core.jni.Psx2Native.appContext = this
            Psx2Engine.ensureLoaded()
        }
        tryInit("DcEngine")            {
            // libretro Flycast DC core — dlopen()s
            // libflycast_libretro_android.so.
            com.nesstation.app.core.jni.DcNative.appContext = this
            DcEngine.ensureLoaded()
        }
        tryInit("NdsEngine")           {
            // melonDS NDS core — dlopen()s
            // libmelonds_libretro_android.so.
            com.nesstation.app.core.jni.NdsNative.appContext = this
            NdsEngine.ensureLoaded()
        }
        tryInit("DraSticEngine")       {
            // DraStic（激烈）NDS core —— 与 melonDS 并列的第二个 NDS 核心，
            // 启动游戏时由玩家在核心选择对话框里二选一。
            // libdrastic*.so 提供 armeabi-v7a（32 位）与 arm64-v8a（64 位：
            // drastic_arm64）两套库，按进程 ABI 自动选择加载；x86/x86_64
            // 进程加载失败 → probeAvailability 返回不可用，UI 禁用该选项
            // （不崩溃）。提前在启动时探测并缓存结果，对话框弹出时无需
            // 再等库加载。
            com.nesstation.app.core.engine.DraSticEngine.get().also { engine ->
                engine.appContext = this
                engine.probeAvailability()
            }
        }
        tryInit("AzaharEngine")        {
            // Azahar 3DS core —— 原包名 JNI 契约 + 预编译 libazahar.so
            // （来自 AzaharPlus APK，经 scripts/fetch_azahar_ishiruka_libs.sh 提取）。
            // 启动时探测可用性，库缺失时 UI 报告原因（不崩溃）。
            com.nesstation.app.core.engine.AzaharEngine.get().also { engine ->
                engine.appContext = this
                engine.probeAvailability()
            }
        }
        tryInit("IshirukaEngine")      {
            // Ishiiruka NGC/WII core —— Dolphin 优化分支，原包名 JNI 契约 +
            // 预编译 libishiiruka.so（来自 Ishiruka APK，经
            // scripts/fetch_azahar_ishiruka_libs.sh 提取）。启动时探测可用性。
            com.nesstation.app.core.engine.IshirukaEngine.get().also { engine ->
                engine.appContext = this
                engine.probeAvailability()
            }
        }
        tryInit("FdsBios")            { ensureFdsBios() }
        tryInit("FbNeoBios")          { ensureFbNeoBios() }
        tryInit("GenesisBios")        { ensureGenesisBios() }
        tryInit("PceBios")            { ensurePceBios() }
        tryInit("NdsBios")            { ensureNdsBios() }
        tryInit("PsxBios")            { ensurePsxBios() }
        tryInit("DcBios")             { ensureDcBios() }
        tryInit("Ps2Bios")            { ensurePs2Bios() }
        // ★ 3DS 加密卡带密钥（aes_keys.txt）自动安装：构建者把密钥放进
        //   assets/azahar/aes_keys.txt 即可让加密 .3ds 直接运行（缺失时
        //   核心加载失败 → 表现为黑屏；配合 AzaharEngine 的错误上报，
        //   缺密钥时用户能看到明确提示）。
        tryInit("AzaharKeys")         { ensureAzaharKeys() }
        tryInit("ArcadeTitleMigrate") { migrateArcadeTitles() }
        tryInit("LibraryJunkSanitize") { sanitizeLibraryOnce() }
    }

    /**
     * 一次性游戏库垃圾清理（任务：修复乱扫描 apk/zip 的存量收尾）。
     *
     * 旧版宽松扫描把 APK 安装包 / 资源 zip 等垃圾灌进了游戏库；新扫描逻辑
     * （PlatformDetector.detectForRefresh* + 探头验证）已经堵住入口，但存量
     * 垃圾不会自己消失。升级后首次启动在后台线程清扫一次，
     * prefs 标记防重复，只跑一次。
     */
    private fun sanitizeLibraryOnce() {
        val prefs = getSharedPreferences("rom_library", MODE_PRIVATE)
        if (prefs.getBoolean("library_junk_sanitized", false)) return
        Thread {
            try {
                val removed = RomStore.sanitizeLibrary(this)
                if (removed > 0) {
                    Log.i("NesApp", "游戏库垃圾清理：已移除 $removed 条非 ROM 条目")
                }
            } catch (t: Throwable) {
                Log.w("NesApp", "游戏库清理失败（下次启动重试）", t)
            } finally {
                // 清理成功与否都标记，避免每次启动都扫一遍库
                try { prefs.edit().putBoolean("library_junk_sanitized", true).apply() } catch (_: Throwable) {}
            }
        }.start()
    }

    /**
     * One-time migration of arcade ROM titles to Chinese display names.
     *
     * Older app versions stored arcade ROMs with the raw driver name as title
     * (e.g. "kof98h", "mvc", "sf2ce"). The ArcadeTitleMapper can now map these
     * driver names to user-friendly Chinese names. This migration runs on
     * every startup but is a no-op for games that already have a Chinese title
     * or whose driver name isn't in the mapping.
     *
     * Wrapped in try-catch so a SharedPreferences failure can never block app
     * startup.
     */
    private fun migrateArcadeTitles() {
        try {
            val updated = com.nesstation.app.core.storage.RomStore.migrateArcadeTitles(this)
            if (updated > 0) {
                Log.i("NesApp", "Arcade title migration: $updated game(s) updated to Chinese names")
            }
        } catch (t: Throwable) {
            Log.w("NesApp", "Arcade title migration failed", t)
        }
    }

    /**
     * Auto-extract FDS BIOS (disksys.rom) from APK assets to filesDir.
     *
     * Strategy:
     *   - If filesDir/disksys.rom already exists AND is valid (size 8192,
     *     reset vector points into BIOS region 0xE000-0xFFFF), keep it —
     *     the user may have imported a real BIOS via Settings.
     *   - Otherwise, extract from assets and validate.
     *   - If the assets BIOS is also invalid, delete it so the user gets
     *     a clear "BIOS missing" error instead of a silent gray screen.
     *
     * Why validate the reset vector:
     *   A corrupted/fake disksys.rom (e.g. one filled with NOP padding with
     *   a reset vector pointing to zero-page RAM 0x00xx) will be accepted
     *   by FCEUmm without complaint, but the CPU will never boot the BIOS
     *   and the screen stays gray. The reset vector check catches this.
     *   A REAL FDS BIOS always has its reset vector in 0xE000-0xFFFF because
     *   that's where the BIOS is mapped in the CPU address space.
     */
    private fun ensureFdsBios() {
        val dest = File(filesDir, "disksys.rom")

        // If a valid BIOS already exists (imported via Settings), keep it.
        // This prevents the assets BIOS from overwriting a user-imported one.
        if (dest.exists() && isValidFdsBios(dest)) {
            Log.i("NesApp", "FDS BIOS already present and valid: ${dest.absolutePath}")
            return
        }

        // Try to extract from assets
        try {
            assets.open("disksys.rom").use { input ->
                dest.outputStream().use { output -> input.copyTo(output) }
            }
            if (!isValidFdsBios(dest)) {
                dest.delete()
                Log.w("NesApp", "FDS BIOS in assets is invalid (bad reset vector or wrong size), deleted. " +
                        "Please place a real disksys.rom (MD5 ca30b50f880eb660a4062209e9986140) in assets/ " +
                        "or import via Settings.")
                return
            }
            Log.i("NesApp", "FDS BIOS extracted from assets to ${dest.absolutePath}")
        } catch (e: java.io.FileNotFoundException) {
            // No disksys.rom in assets — user must import manually
            Log.i("NesApp", "No disksys.rom in assets; user must import via Settings")
        } catch (e: Exception) {
            Log.w("NesApp", "Failed to extract FDS BIOS from assets", e)
        }
    }

    /**
     * Validates an FDS BIOS file:
     *   1. Size == 8192 bytes
     *   2. Reset vector (offset 0x1FFC-0x1FFD, little-endian) points into
     *      0xE000-0xFFFF — the BIOS region where FDSInit maps the BIOS.
     *
     * A real FDS BIOS always has its reset vector in this range. A corrupted/
     * fake BIOS (e.g. NOP-padded stub) has a reset vector pointing to 0x00xx
     * (RAM), causing the CPU to execute garbage and produce a gray screen.
     */
    private fun isValidFdsBios(file: File): Boolean {
        if (file.length() != 8192L) return false
        try {
            file.inputStream().use { input ->
                val bytes = input.readBytes()
                if (bytes.size != 8192) return false
                // Reset vector at offset 0x1FFC-0x1FFD (CPU addr 0xFFFC-0xFFFD)
                val resetLo = bytes[0x1FFC].toInt() and 0xFF
                val resetHi = bytes[0x1FFD].toInt() and 0xFF
                val resetVec = (resetHi shl 8) or resetLo
                // Must point into BIOS region 0xE000-0xFFFF
                if (resetVec < 0xE000 || resetVec > 0xFFFF) {
                    Log.w("NesApp", "FDS BIOS reset vector 0x%04X is invalid (must be 0xE000-0xFFFF)".format(resetVec))
                    return false
                }
            }
        } catch (_: Exception) {
            return false
        }
        return true
    }

    /** Container is lazy-nullable: null if init failed, created on first successful init. */
    val container: AppContainer?
        get() = _container ?: tryInit("AppContainer-lazy") {
            _container = AppContainer(this)
        }.let { _container }

    private var _container: AppContainer? = null

    /**
     * Auto-extract FBNeo BIOS zip files (neogeo.zip, pgm.zip, etc.) from
     * APK assets to the system directory (<filesDir>/fbneo/).
     *
     * FBNeo looks for BIOS files by filename in the system directory. The
     * most common ones users may bundle:
     *   - neogeo.zip  — NeoGeo BIOS (required for all NeoGeo games)
     *   - pgm.zip     — PolyGame Master BIOS (required for all PGM games)
     *   - neocdz.zip  — NeoGeo CD BIOS
     *   - cvs2.zip    — Capcom VS SNK 2 decryption key
     *
     * These BIOS files have copyright and cannot be bundled in the open-
     * source release. Users must either:
     *   1. Place the BIOS zips in `app/src/main/assets/fbneo/` before
     *      building the APK (for personal distribution to their own devices).
     *   2. Import them at runtime via the BIOS management UI in Settings.
     *
     * This method extracts any BIOS files found in `assets/fbneo/` to
     * <filesDir>/fbneo/. If the destination already exists, it is kept
     * (user-imported BIOS takes precedence).
     */
    private fun ensureFbNeoBios() {
        val destDir = File(filesDir, "fbneo")
        if (!destDir.exists()) destDir.mkdirs()

        // Known BIOS filenames FBNeo looks for. We only extract those that
        // actually exist in assets/fbneo/ — no error if none are present.
        val biosFiles = listOf(
            "neogeo.zip", "pgm.zip", "neocdz.zip", "cvs2.zip",
            "cps1.zip", "cps2.zip", "stvbios.zip", "tickgal.zip"
        )

        var extracted = 0
        for (name in biosFiles) {
            val dest = File(destDir, name)
            if (dest.exists() && dest.length() > 0) continue  // keep existing
            try {
                assets.open("fbneo/$name").use { input ->
                    dest.outputStream().use { output -> input.copyTo(output) }
                }
                extracted++
                Log.i("NesApp", "FBNeo BIOS extracted: $name")
            } catch (_: java.io.FileNotFoundException) {
                // Not bundled — user must import via Settings
            } catch (e: Exception) {
                Log.w("NesApp", "Failed to extract FBNeo BIOS $name", e)
                if (dest.exists()) dest.delete()
            }
        }
        if (extracted > 0) {
            Log.i("NesApp", "FBNeo BIOS: $extracted file(s) extracted to ${destDir.absolutePath}")
        } else {
            Log.i("NesApp", "FBNeo BIOS: no bundled BIOS files found in assets/fbneo/. " +
                    "Import via Settings → Arcade → BIOS Management.")
        }
    }

    /**
     * Auto-extract Genesis-Plus-GX BIOS zip files (Mega-CD BIOSes) from
     * APK assets to the system directory (<filesDir>/genesis/).
     *
     * Genesis-Plus-GX looks for Mega-CD BIOS files by filename:
     *   - bios_CD_E.bin  — European Mega-CD BIOS
     *   - bios_CD_J.bin  — Japanese Mega-CD BIOS
     *   - bios_CD_U.bin  — US SEGA-CD BIOS
     *
     * Cartridge games (MD/SMS/GG/SG) do NOT require BIOS — only Mega-CD
     * games need these. Like FBNeo BIOS, these have copyright and cannot
     * be bundled in the open-source release.
     *
     * ## 之前 bug：只复制 zip 不解压
     *
     * assets/genesis/ 里存的是 `bios_CD_E.zip`（内部含 `bios_CD_E.bin`）。
     * 之前的实现把 zip 复制到 `<filesDir>/genesis/` 后**没有解压**，导致：
     *   - 用户打开"BIOS 管理"看到"有.zip但无.bin — 建议重新导入以自动解压"
     *   - genplus 核心加载 Mega-CD 游戏时找不到 `bios_CD_E.bin`，黑屏
     *
     * ## 修复：复制 zip 后立即解压出 .bin
     *
     * 检测到 `bios_CD_<region>.zip` 但没有对应的 `bios_CD_<region>.bin` 时，
     * 自动解压 zip 里的 .bin 文件出来。已存在的 .bin 不会被覆盖（用户导入
     * 的优先于 assets 里的）。
     */
    private fun ensureGenesisBios() {
        val destDir = File(filesDir, "genesis")
        if (!destDir.exists()) destDir.mkdirs()

        val biosFiles = listOf("bios_CD_E.zip", "bios_CD_J.zip", "bios_CD_U.zip")

        var extracted = 0
        for (name in biosFiles) {
            val zipDest = File(destDir, name)
            // 如果 zip 不存在，从 assets 复制
            if (!zipDest.exists() || zipDest.length() <= 0) {
                try {
                    assets.open("genesis/$name").use { input ->
                        zipDest.outputStream().use { output -> input.copyTo(output) }
                    }
                    extracted++
                    Log.i("NesApp", "Genesis BIOS copied: $name")
                } catch (_: java.io.FileNotFoundException) {
                    // assets 里没有这个 zip，跳过（开源发布不带版权 BIOS）
                    continue
                } catch (e: Exception) {
                    Log.w("NesApp", "Failed to copy Genesis BIOS $name", e)
                    if (zipDest.exists()) zipDest.delete()
                    continue
                }
            }

            // === 关键修复：解压 zip 里的 .bin ===
            // assets 里存的是 zip（内部含 .bin），genplus 核心要的是 .bin 文件本身。
            // 之前只复制了 zip 没解压，导致核心找不到 BIOS。
            val binName = name.replace(".zip", ".bin")  // bios_CD_E.zip -> bios_CD_E.bin
            val binDest = File(destDir, binName)
            if (binDest.exists() && binDest.length() > 0) {
                // .bin 已存在（用户之前导入过或上次解压过），不覆盖
                continue
            }
            try {
                java.util.zip.ZipInputStream(zipDest.inputStream().buffered()).use { zin ->
                    while (true) {
                        val entry = zin.nextEntry ?: break
                        val entryName = entry.name.lowercase()
                        // 找到 zip 里的 .bin 或 .rom 文件，解压为 bios_CD_<region>.bin
                        if (entryName.endsWith(".bin") || entryName.endsWith(".rom")) {
                            binDest.outputStream().buffered().use { out ->
                                val buf = ByteArray(8192)
                                while (true) {
                                    val n = zin.read(buf)
                                    if (n <= 0) break
                                    out.write(buf, 0, n)
                                }
                            }
                            Log.i("NesApp", "Genesis BIOS extracted: ${zipDest.name} → ${binDest.name} (${binDest.length() / 1024}KB)")
                            break
                        }
                        zin.closeEntry()
                    }
                }
            } catch (e: Exception) {
                Log.w("NesApp", "Failed to extract .bin from ${zipDest.name}", e)
                if (binDest.exists() && binDest.length() == 0L) binDest.delete()
            }
        }
        if (extracted > 0) {
            Log.i("NesApp", "Genesis BIOS: $extracted file(s) extracted to ${destDir.absolutePath}")
        } else {
            Log.i("NesApp", "Genesis BIOS: no bundled BIOS files found in assets/genesis/. " +
                    "Import via Settings → MD/SEGA → BIOS Management (only needed for Mega-CD games).")
        }
    }

    /**
     * Auto-extract Geargrafx PCE-CD BIOS files from APK assets to the
     * system directory (<filesDir>/pce/).
     *
     * Geargrafx looks for PCE-CD BIOS files by filename:
     *   - syscard1.pce — System Card 1
     *   - syscard2.pce — System Card 2
     *   - syscard3.pce — System Card 3 / Arcade Card Pro (most common)
     *   - gexpress.pce — Games Express BIOS
     *
     * IMPORTANT: the core uses the filename "gexpress.pce" (NOT
     * "gameexpress.pce"). Everything in this app must use "gexpress.pce".
     *
     * Two extraction passes run:
     *   1. Files already named canonically in assets/pce/ (syscardN.pce /
     *      gexpress.pce) are copied 1:1.
     *   2. Any other .pce file found in assets/pce/ is auto-detected by its
     *      source filename (e.g. "System Card 3.0.pce", "ArcadeCardPro.pce",
     *      "Game Express.pce") and copied under the canonical name the core
     *      expects. This lets users bundle BIOS packs without renaming files.
     *
     * User-imported BIOS files already present in <filesDir>/pce/ are never
     * overwritten (imported BIOS takes precedence over bundled ones).
     *
     * Cartridge games (.pce/.sgx) and HES rips (.hes) do NOT require BIOS —
     * only PCE-CD games need these. Like the other BIOS files, these have
     * copyright and cannot be bundled in the open-source release; this
     * function is a no-op if the assets are not present.
     */
    private fun ensurePceBios() {
        val destDir = File(filesDir, "pce")
        if (!destDir.exists()) destDir.mkdirs()

        var extracted = 0

        // Pass 1 — copy files that are already named canonically.
        // (gexpress.pce is the filename the core actually looks for, NOT
        // gameexpress.pce.)
        val canonicalFiles = listOf(
            "syscard1.pce", "syscard2.pce", "syscard3.pce", "gexpress.pce"
        )
        for (name in canonicalFiles) {
            val dest = File(destDir, name)
            if (dest.exists() && dest.length() > 0) continue  // keep existing
            try {
                assets.open("pce/$name").use { input ->
                    dest.outputStream().use { output -> input.copyTo(output) }
                }
                extracted++
                Log.i("NesApp", "PCE BIOS extracted: $name")
            } catch (_: java.io.FileNotFoundException) {
                // Not bundled under canonical name — Pass 2 may find an alias
            } catch (e: Exception) {
                Log.w("NesApp", "Failed to extract PCE BIOS $name", e)
                if (dest.exists()) dest.delete()
            }
        }

        // Pass 2 — auto-detect any other .pce files dropped into assets/pce/
        // and copy them under the canonical name the core expects.
        extracted += autoDetectPceBiosFromAssets(destDir)

        if (extracted > 0) {
            Log.i("NesApp", "PCE BIOS: $extracted file(s) extracted to ${destDir.absolutePath}")
        } else {
            Log.i("NesApp", "PCE BIOS: no bundled BIOS files found in assets/pce/. " +
                    "Import via Settings → PCE → PCE-CD BIOS Management (only needed for PCE-CD games).")
        }
    }

    /**
     * Auto-extract melonDS (NDS / DSi) BIOS files from APK assets to the
     * system directory (<filesDir>/nds/).
     *
     * melonDS looks for BIOS files by filename:
     *   bios7.bin      — ARM7 BIOS (required for NDS)
     *   bios9.bin      — ARM9 BIOS (required for NDS)
     *   firmware.bin   — DS firmware (required for NDS)
     *   dsi_arm7.bin   — (DSi only) ARM7 binary
     *   dsi_bios7.bin  — (DSi only) ARM7 BIOS
     *   dsi_bios9.bin  — (DSi only) ARM9 BIOS
     *   dsi_firmware.bin — (DSi only) DSi firmware
     *   dsi_nand.bin   — (DSi only) DSi NAND image
     *
     * These BIOS files have copyright and cannot be bundled in the open-source
     * release. Users can place them in app/src/main/assets/nds/ before building
     * the APK, or import them via Settings → NDS → BIOS Management.
     *
     * Already-extracted files in <filesDir>/nds/ are NOT overwritten (user
     * imports take precedence over bundled assets).
     */
    private fun ensureNdsBios() {
        val destDir = File(filesDir, "nds")
        if (!destDir.exists()) destDir.mkdirs()

        val biosFiles = listOf(
            "bios7.bin", "bios9.bin", "firmware.bin",
            "dsi_arm7.bin", "dsi_bios7.bin", "dsi_bios9.bin",
            "dsi_firmware.bin", "dsi_nand.bin"
        )

        var extracted = 0
        for (name in biosFiles) {
            val dest = File(destDir, name)
            if (dest.exists() && dest.length() > 0) continue  // don't overwrite
            try {
                assets.open("nds/$name").use { input ->
                    dest.outputStream().use { output -> input.copyTo(output) }
                }
                extracted++
                Log.i("NesApp", "NDS BIOS extracted: $name")
            } catch (_: java.io.FileNotFoundException) {
                // Not bundled — skip
            } catch (e: Exception) {
                Log.w("NesApp", "Failed to extract NDS BIOS $name", e)
                if (dest.exists()) dest.delete()
            }
        }
        if (extracted > 0) {
            Log.i("NesApp", "NDS BIOS: $extracted file(s) extracted to ${destDir.absolutePath}")
        } else {
            Log.i("NesApp", "NDS BIOS: no bundled BIOS files found in assets/nds/. " +
                    "Import via Settings → NDS → BIOS Management.")
        }
    }

    /**
     * Auto-extract PCSX-ReARMed (PSX) BIOS files from APK assets to the
     * system directory (<filesDir>/psx/).
     *
     * PCSX-ReARMed looks for BIOS files by filename:
     *   scph1000.bin     — Japanese BIOS
     *   scph1001.bin     — American BIOS
     *   scph1002.bin     — European BIOS
     *   scph5500.bin     — Japanese (newer)
     *   scph5501.bin     — American (newer)
     *   scph5502.bin     — European (newer)
     *   psxonpsp660.bin  — PSP-derived (no copyright issues in some regions)
     *
     * PCSX-ReARMed also supports HLE BIOS (built-in, no file needed) —
     * selectable via the "pcsx_rearmed_bios" = "HLE" core option. This means
     * PSX games CAN run without a BIOS file (less compatible). Users who want
     * full compatibility place BIOS files in app/src/main/assets/psx/ before
     * building, or import them via Settings → PSX → BIOS Management.
     *
     * Already-extracted files in <filesDir>/psx/ are NOT overwritten.
     */
    private fun ensurePsxBios() {
        val destDir = File(filesDir, "psx")
        if (!destDir.exists()) destDir.mkdirs()

        val biosFiles = listOf(
            "scph1000.bin", "scph1001.bin", "scph1002.bin",
            "scph5500.bin", "scph5501.bin", "scph5502.bin",
            "psxonpsp660.bin"
        )

        var extracted = 0
        for (name in biosFiles) {
            val dest = File(destDir, name)
            if (dest.exists() && dest.length() > 0) continue  // don't overwrite
            try {
                assets.open("psx/$name").use { input ->
                    dest.outputStream().use { output -> input.copyTo(output) }
                }
                extracted++
                Log.i("NesApp", "PSX BIOS extracted: $name")
            } catch (_: java.io.FileNotFoundException) {
                // Not bundled — skip
            } catch (e: Exception) {
                Log.w("NesApp", "Failed to extract PSX BIOS $name", e)
                if (dest.exists()) dest.delete()
            }
        }
        if (extracted > 0) {
            Log.i("NesApp", "PSX BIOS: $extracted file(s) extracted to ${destDir.absolutePath}")
        } else {
            Log.i("NesApp", "PSX BIOS: no bundled BIOS files found in assets/psx/. " +
                    "Import via Settings → PSX → BIOS Management, or use HLE BIOS (pcsx_rearmed_bios = HLE).")
        }
    }

    /**
     * Auto-extract Dreamcast / NAOMI / AtomisWave BIOS files from APK assets
     * to the system directory (<filesDir>/dc/).
     *
     * libretro Flycast follows the RetroArch convention: with the app passing
     * <filesDir> as the system directory (EmulatorScreen → DcEngine →
     * dc_loader.cpp → GET_SYSTEM_DIRECTORY), the core looks for its content
     * under <system>/dc/:
     *   - dc_boot.bin  — Dreamcast BIOS ROM (required for disc games)
     *   - dc_bios.bin  — accepted boot-ROM alias (core tries dc_boot first)
     *   - dc_flash.bin — Dreamcast flash (required for disc games)
     *   - dc_nvmem.bin — Dreamcast NVMEM flash (some builds / games)
     *   - naomi.zip / naomi2.zip — NAOMI BIOS sets
     *   - awbios.zip   — AtomisWave BIOS set
     *   - f355bios.zip / f355dlx.zip / hod2bios.zip / airlbios.zip —
     *     per-system NAOMI variants
     * VMU saves (vmu_save_*.bin) live in the saves directory (<filesDir>/saves/)
     * like every other core's save data — also seeded here when bundled.
     *
     * This method extracts any BIOS files found in `assets/dc/` to
     * <filesDir>/dc/ (and VMU files to <filesDir>/saves/). If the destination
     * already exists, it is kept (user-imported files take precedence).
     */
    private fun ensureDcBios() {
        val destDir = File(filesDir, "dc")
        if (!destDir.exists()) destDir.mkdirs()

        // Known BIOS filenames flycast looks for in <system>/dc/. We only
        // extract those that actually exist in assets/dc/ — no error if none.
        val biosFiles = listOf(
            "dc_boot.bin", "dc_bios.bin", "dc_flash.bin", "dc_nvmem.bin",
            "naomi.zip", "naomi2.zip", "awbios.zip",
            "f355bios.zip", "f355dlx.zip", "hod2bios.zip", "airlbios.zip"
        )

        var extracted = 0
        for (name in biosFiles) {
            val dest = File(destDir, name)
            if (dest.exists() && dest.length() > 0) continue  // keep existing
            try {
                assets.open("dc/$name").use { input ->
                    dest.outputStream().use { output -> input.copyTo(output) }
                }
                extracted++
                Log.i("NesApp", "DC BIOS extracted: $name")
            } catch (_: java.io.FileNotFoundException) {
                // Not bundled — normal for the open-source build
            } catch (e: Exception) {
                Log.w("NesApp", "Failed to extract DC BIOS $name", e)
                if (dest.exists()) dest.delete()
            }
        }

        // VMU templates (vmu_save_A1.bin .. vmu_save_D1.bin) — flycast keeps
        // them in the save directory, so seed them there as well.
        val savesDir = File(filesDir, "saves")
        if (!savesDir.exists()) savesDir.mkdirs()
        val vmuFiles = listOf("vmu_save_A1.bin", "vmu_save_B1.bin",
                              "vmu_save_C1.bin", "vmu_save_D1.bin")
        for (name in vmuFiles) {
            val dest = File(savesDir, name)
            if (dest.exists() && dest.length() > 0) continue  // keep existing
            try {
                assets.open("dc/$name").use { input ->
                    dest.outputStream().use { output -> input.copyTo(output) }
                }
                extracted++
                Log.i("NesApp", "DC VMU seeded: $name")
            } catch (_: java.io.FileNotFoundException) {
                // Not bundled — flycast creates fresh VMUs on first boot
            } catch (e: Exception) {
                Log.w("NesApp", "Failed to seed DC VMU $name", e)
                if (dest.exists()) dest.delete()
            }
        }

        if (extracted > 0) {
            Log.i("NesApp", "DC BIOS/VMU: $extracted file(s) deployed " +
                    "(BIOS → ${destDir.absolutePath}, VMU → ${savesDir.absolutePath})")
        } else {
            Log.i("NesApp", "DC BIOS: no bundled BIOS files found in assets/dc/. " +
                    "Dreamcast disc games require dc_boot.bin + dc_flash.bin " +
                    "in <filesDir>/dc/ (import via Settings → DC).")
        }
    }

    /**
     * ★ PS2 默认 BIOS 自动识别（NesStation 集成补丁）：
     * 把 APK assets/ps2/bios/ 内打包的 BIOS 文件自动安装到
     * `<filesDir>/ps2/pcsx2/bios/`（ARMSX2/PCSX2 的 BIOS 目录，由
     * Psx2Native.setPaths → NativeApp.initialize 指定）。
     *
     * 为什么放进去就能用（零配置）：PCSX2 的 LoadBIOS() 在未配置
     * BIOS 文件名时会自动调用 FindBiosImage() 扫描整个 BIOS 目录，
     * 按大小校验 + IsBIOS 魔数识别后自动选用（见 ARMSX2-master/
     * pcsx2/ps2/BiosTools.cpp）—— 用户无需手动导入，也无需写任何
     * 配置项。
     *
     * 安装策略（与 DC/PSX 一致）：
     *   - 目标已存在且非空 → 保留（用户自行导入的 BIOS 优先）；
     *   - assets 里放多少装多少（assets.list 枚举，不限定文件名，
     *     scph10000.bin / scph39001.bin / scph70004.bin 等均可）；
     *   - 空目录 / 未打包 → 记录日志静默跳过（开源自建流程不崩溃）。
     *
     * ROM 版权说明：PS2 BIOS 为 Sony 专有固件，开源仓库不随包分发；
     * 构建者可自行放入 assets/ps2/bios/（见该目录 README.md 与
     * scripts/fetch_ps2_bios.sh），运行侧机制与 DC BIOS 完全一致。
     */
    private fun ensurePs2Bios() {
        val destDir = File(File(filesDir, "ps2"), "pcsx2/bios")
        if (!destDir.exists()) destDir.mkdirs()
        // 旧版目录迁移：<filesDir>/ps2/bios/ 里的文件搬进规范位置
        //（与设置面板说明的“旧版 ps2/bios 的文件会自动迁移”一致）。
        val legacyDir = File(File(filesDir, "ps2"), "bios")
        var migrated = 0
        if (legacyDir.isDirectory) {
            legacyDir.listFiles()?.forEach { src ->
                if (src.isFile && src.length() > 0) {
                    val dest = File(destDir, src.name)
                    if (!dest.exists() || dest.length() == 0L) {
                        try {
                            src.copyTo(dest, overwrite = false)
                            migrated++
                        } catch (_: Exception) { }
                    }
                }
            }
            if (migrated > 0) {
                Log.i("NesApp", "PS2 BIOS: migrated $migrated file(s) from legacy ps2/bios/")
            }
        }

        var extracted = 0
        try {
            val names = assets.list("ps2/bios") ?: emptyArray()
            for (name in names) {
                if (name.endsWith(".md", true) || name.endsWith(".txt", true)) continue
                val dest = File(destDir, name)
                if (dest.exists() && dest.length() > 0) continue  // keep existing
                try {
                    assets.open("ps2/bios/$name").use { input ->
                        dest.outputStream().use { output -> input.copyTo(output) }
                    }
                    extracted++
                    Log.i("NesApp", "PS2 BIOS installed from assets: $name")
                } catch (e: Exception) {
                    Log.w("NesApp", "Failed to install PS2 BIOS $name", e)
                    if (dest.exists()) dest.delete()
                }
            }
        } catch (e: Exception) {
            Log.w("NesApp", "Failed to enumerate assets/ps2/bios/", e)
        }

        if (extracted + migrated > 0) {
            Log.i("NesApp", "PS2 BIOS: ${extracted + migrated} file(s) present in " +
                    destDir.absolutePath + " — PCSX2 will auto-detect (FindBiosImage)")
        } else {
            Log.i("NesApp", "PS2 BIOS: none bundled in assets/ps2/bios/. " +
                    "Users can import one via Settings → PS2 · BIOS 管理, or the " +
                    "builder can drop files there before packaging (see README).")
        }
    }

    /**
     * ★ 3DS 黑屏修复链配套：把 assets/azahar/aes_keys.txt（构建者自备）
     * 安装到 `<filesDir>/azahar/aes_keys.txt`（Azahar 用户目录根 ——
     * AzaharEngine.loadRom 的 userDir()）。加密 .3ds 卡带缺少该密钥时
     * 核心无法解密 ROM，加载失败表现为黑屏。与 DC/PS2 BIOS 同一
     * "assets 自动安装" 模式：文件不打包则静默跳过（开源自建流程不受影响），
     * 已存在（用户手动放置）则保留不覆盖。
     */
    private fun ensureAzaharKeys() {
        val dest = File(File(filesDir, "azahar"), "aes_keys.txt")
        if (dest.exists() && dest.length() > 0) return  // 用户自备优先
        try {
            assets.open("azahar/aes_keys.txt").use { input ->
                dest.parentFile?.mkdirs()
                dest.outputStream().use { output -> input.copyTo(output) }
            }
            Log.i("NesApp", "Azahar aes_keys.txt installed from assets -> ${dest.absolutePath}")
        } catch (_: java.io.FileNotFoundException) {
            // 未打包（默认）—— 正常
        } catch (e: Exception) {
            Log.w("NesApp", "Failed to install azahar aes_keys.txt", e)
            if (dest.exists()) dest.delete()
        }
    }

    /**
     * Scans assets/pce/ for any .pce files that were not already extracted
     * under a canonical name and copies them to [destDir] under the name
     * Geargrafx expects, detecting the canonical name from the source
     * filename. Returns the number of files newly extracted.
     */
    private fun autoDetectPceBiosFromAssets(destDir: File): Int {
        val assetNames: Array<String> = try {
            assets.list("pce") ?: return 0
        } catch (_: Exception) {
            return 0
        }

        var copied = 0
        for (assetName in assetNames) {
            val lower = assetName.lowercase()
            if (!lower.endsWith(".pce")) continue  // README.txt etc.

            val canonical = when {
                lower.contains("syscard1") || lower.contains("system card 1") ||
                lower.contains("system_card_1") || lower.contains("sc1") -> "syscard1.pce"
                lower.contains("syscard2") || lower.contains("system card 2") ||
                lower.contains("system_card_2") || lower.contains("sc2") -> "syscard2.pce"
                lower.contains("syscard3") || lower.contains("system card 3") ||
                lower.contains("system_card_3") || lower.contains("sc3") ||
                lower.contains("arcade card") || lower.contains("accard") -> "syscard3.pce"
                lower.contains("gexpress") || lower.contains("gameexpress") ||
                lower.contains("game express") || lower.contains("game_express") -> "gexpress.pce"
                else -> assetName  // keep original name; core looks for its canonical name
            }

            val dest = File(destDir, canonical)
            if (dest.exists() && dest.length() > 0) continue  // already present

            try {
                assets.open("pce/$assetName").use { input ->
                    dest.outputStream().use { output -> input.copyTo(output) }
                }
                copied++
                Log.i("NesApp", "PCE BIOS auto-detected: $assetName -> $canonical")
            } catch (e: Exception) {
                Log.w("NesApp", "Failed to auto-extract PCE BIOS $assetName", e)
            }
        }
        return copied
    }

    private fun tryInit(tag: String, block: () -> Unit) {
        try {
            block()
        } catch (t: Throwable) {
            Log.e("NesApp", "Init [$tag] failed", t)
        }
    }

    companion object {
        @Volatile private var instance: NesApp? = null

        /** Returns the Application instance, or null if onCreate hasn't run yet. */
        fun get(): NesApp? = instance

        /**
         * Returns the Application instance, throwing if not yet created.
         * Use only in contexts where the app is guaranteed to be running.
         */
        fun require(): NesApp =
            instance ?: throw IllegalStateException("NesApp not yet created")
    }
}
