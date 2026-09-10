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
import com.nesstation.app.core.engine.FlycastEngine
import com.nesstation.app.core.storage.AppContainer
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
        tryInit("NdsEngine")           {
            // melonDS NDS core — dlopen()s
            // libmelonds_libretro_android.so.
            com.nesstation.app.core.jni.NdsNative.appContext = this
            NdsEngine.ensureLoaded()
        }
        tryInit("FlycastEngine")       {
            // Flycast DC core — dlopen()s libflycast_libretro_android.so.
            com.nesstation.app.core.jni.FlycastNative.appContext = this
            FlycastEngine.ensureLoaded()
        }
        tryInit("FdsBios")            { ensureFdsBios() }
        tryInit("FbNeoBios")          { ensureFbNeoBios() }
        tryInit("GenesisBios")        { ensureGenesisBios() }
        tryInit("PceBios")            { ensurePceBios() }
        tryInit("NdsBios")            { ensureNdsBios() }
        tryInit("PsxBios")            { ensurePsxBios() }
        tryInit("DcBios")             { ensureDcBios() }
        tryInit("ArcadeTitleMigrate") { migrateArcadeTitles() }
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
     * 准备 Flycast (Dreamcast/Naomi/Atomiswave) 的系统目录结构。
     *
     * flycast 核心把所有系统文件放在 <systemDir>/dc/ 下（systemDir 直接传
     * filesDir，见 EmulatorScreen 的 DC 分支），启动时按文件名查找：
     *   dc_boot.bin    — Dreamcast 启动 ROM（GD-ROM 游戏必需）
     *   dc_flash.bin   — Dreamcast 闪存 ROM（必需，含区域/语言/时钟）
     *   naomi.zip      — Naomi 街机 BIOS（MAME romset，Naomi 游戏必需）
     *   atomiswave.zip — Atomiswave 街机 BIOS（MAME romset）
     * 核心还会在 dc/ 下自动创建 data/ 存放 VMU/闪存写入（启动时自己建，
     * loader 的 setPaths 也兜底建一次）。
     *
     * 这里只创建目录结构 + 从 assets/dc/ 提取预置 BIOS（若打包了的话）。
     * BIOS 有版权不能随 APK 分发 —— 缺失时游戏会给出明确的错误提示，
     * 用户可经「设置 → 系统 → BIOS 管理」或手动放入文件。
     */
    private fun ensureDcBios() {
        val dcDir = File(filesDir, "dc")
        if (!dcDir.exists()) dcDir.mkdirs()
        // data/ 目录由核心写入 VMU/flash —— 提前建好避免首启动写入失败。
        val dataDir = File(dcDir, "data")
        if (!dataDir.exists()) dataDir.mkdirs()

        // 全量预置文件清单：
        //   - Dreamcast 主机 BIOS：dc_boot.bin + dc_flash.bin（必需），
        //     dc_nvmem.bin 为初始 NVRAM 备份（可选）。
        //   - 街机 BIOS（MAME romset zip，Flycast 原名识别）：
        //       naomi.zip    Naomi
        //       naomi2.zip   Naomi 2（部分游戏如无限航路用）
        //       awbios.zip   Atomiswave
        //       hod2bios.zip Naomi 2 专用 BIOS（House of the Dead 2 等）
        //       f355bios.zip / f355dlx.zip / airlbios.zip 多板卡游戏专用 BIOS
        //   - 预置空白 VMU（vmu_save_A1..D1.bin）：避免部分游戏首次启动
        //     因 VMU 缺失而报错/无法存档。
        val biosFiles = listOf(
            "dc_boot.bin", "dc_flash.bin", "dc_nvmem.bin", "dc_bios.bin",
            "naomi.zip", "naomi2.zip", "awbios.zip", "hod2bios.zip",
            "f355bios.zip", "f355dlx.zip", "airlbios.zip",
            "vmu_save_A1.bin", "vmu_save_B1.bin", "vmu_save_C1.bin", "vmu_save_D1.bin"
        )

        var extracted = 0
        for (name in biosFiles) {
            val dest = File(dcDir, name)
            if (dest.exists() && dest.length() > 0) continue  // don't overwrite
            try {
                assets.open("dc/$name").use { input ->
                    dest.outputStream().use { output -> input.copyTo(output) }
                }
                extracted++
                Log.i("NesApp", "DC BIOS extracted: $name")
            } catch (_: java.io.FileNotFoundException) {
                // Not bundled — skip
            } catch (e: Exception) {
                Log.w("NesApp", "Failed to extract DC BIOS $name", e)
                if (dest.exists()) dest.delete()
            }
        }

        // === boot ROM 一致性校验 ===
        // Flycast 只认 <sysdir>/dc_boot.bin 这个名字。社区/抓包渠道常把同一
        // 片 boot ROM 存成 dc_bios.bin。已知合法 boot ROM dump 的 MD5 为
        // e10c53c2f8b90bab96ead2d368858623（与 MAME dc.xml / RetroArch 系统目录
        // 校验值一致）。若 assets 里打包的 dc_boot.bin 与该值不符，而
        // dc_bios.bin 匹配（说明 dc_boot.bin 是别的版本/损坏 dump），
        // 则以 dc_bios.bin 内容生成 dc_boot.bin，保证开箱即用。
        try {
            val boot = File(dcDir, "dc_boot.bin")
            val alt = File(dcDir, "dc_bios.bin")
            if (boot.exists() && alt.exists()) {
                val GOOD_BOOT_MD5 = "e10c53c2f8b90bab96ead2d368858623"
                val bootMd5 = md5Of(boot)
                if (!bootMd5.equals(GOOD_BOOT_MD5, ignoreCase = true) &&
                    md5Of(alt).equals(GOOD_BOOT_MD5, ignoreCase = true)
                ) {
                    alt.copyTo(boot, overwrite = true)
                    Log.i("NesApp", "DC BIOS: dc_boot.bin MD5 mismatch ($bootMd5) — " +
                            "replaced with bundled dc_bios.bin (known-good dump)")
                }
            }
        } catch (e: Exception) {
            Log.w("NesApp", "DC BIOS boot ROM check failed", e)
        }

        if (extracted > 0) {
            Log.i("NesApp", "DC BIOS: $extracted file(s) extracted to ${dcDir.absolutePath}")
        } else {
            Log.i("NesApp", "DC BIOS: no bundled BIOS files in assets/dc/. " +
                    "Place dc_boot.bin + dc_flash.bin (Dreamcast) or naomi.zip / " +
                    "atomiswave.zip (Naomi / Atomiswave) in ${dcDir.absolutePath}.")
        }
    }

    /** 计算文件 MD5（小写 hex）。用于 DC boot ROM 一致性校验。 */
    private fun md5Of(f: File): String {
        val md = java.security.MessageDigest.getInstance("MD5")
        f.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
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
