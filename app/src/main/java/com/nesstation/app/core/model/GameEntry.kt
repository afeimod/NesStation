package com.nesstation.app.core.model

import androidx.compose.ui.graphics.Color

/**
 * Game platform type.
 * NES    = NES/Famicom games (FCEUmm core)
 * SFC    = SNES/Super Famicom games (snes9x core)
 * GB     = Game Boy / Game Boy Color games (mGBA core)
 * GBA    = Game Boy Advance games (mGBA core)
 * DOS    = DOS/PC games (DOSBox-Pure core)
 * ARCADE = Arcade machines (FBNeo core — CPS1/2/3, NeoGeo, PGM, etc.)
 * MD     = SEGA Mega Drive / Genesis / Master System / Game Gear /
 *           Mega-CD / SG-1000 (Genesis-Plus-GX core)
 *           NOTE: SS (Saturn) is NOT supported by Genesis-Plus-GX —
 *           it requires a separate Saturn core (Yabause/Mednafen).
 * PCE    = PC-Engine / TurboGrafx-16 / SuperGrafx / PCE-CD
 *           (Geargrafx core)
 * NDS    = Nintendo DS / DSi (melonDS libretro core)
 * PSX    = Sony PlayStation 1 (PCSX-ReARMed core)
 * PS2    = Sony PlayStation 2 (PCEE2 libretro core — the official libretro
 *          Android build of upstream PCSX2, shipped by the buildbot)
 * DC     = Sega Dreamcast / Naomi / Atomiswave (Flycast libretro core)
 * JAVA   = J2ME/Java ME games (J2ME-Loader engine)
 */
enum class GamePlatform(val displayName: String) {
    NES("NES"),
    SFC("SFC"),
    GB("GB/GBC"),
    GBA("GBA"),
    DOS("DOS"),
    ARCADE("Arcade"),
    MD("MD/SEGA"),
    PCE("PCE/TG16"),
    NDS("NDS"),
    PSX("PSX"),
    PS2("PS2"),
    DC("DC"),
    JAVA("Java");

    companion object {
        /**
         * 从字符串解析平台标识。
         *
         * 大小写不敏感，并支持常见别名（避免服务端用小写 "arcade" 或核心名 "fbneo"
         * 时 fallback 到 NES，导致对战平台默认用 fceumm 启动街机 ROM 的 bug）。
         *
         * 别名表（节选）：
         *   NES    ← nes / fc / famicom / fceumm / fceux / nestopia
         *   SFC    ← sfc / snes / supernintendo / snes9x
         *   GB     ← gb / gbc / gameboy / gameboycolor
         *   GBA    ← gba / gameboyadvance
         *   DOS    ← dos / dosbox / dosboxpure
         *   ARCADE ← arcade / fbneo / fbneocore / mame / cps1 / cps2 / cps3 / neogeo / pgm
         *   MD     ← md / genesis / megadrive / segagenesis / megacd / segacd / genplus
         *   PCE    ← pce / pcengine / turbografx / tg16 / geargrafx / supergrafx
         *   PSX    ← psx / ps1 / playstation / playstation1 / sony / pcsx / pcsxrearmed / pcsxr
         *   PS2    ← ps2 / playstation2 / play / psx2 / pcsx2
         *   DC     ← dc / dreamcast / flycast / reicast / naomi / atomiswave / gdrom
         *   JAVA   ← java / j2me / midlet
         *
         * 旧版（大小写敏感 + `entries.firstOrNull { it.name == value } ?: NES`）
         * 在服务端返回 "arcade" 时会 fallback 到 NES，触发对战平台默认走 fceumm。
         */
        fun fromString(value: String?): GamePlatform {
            if (value.isNullOrBlank()) return NES
            // 归一化：小写 + 去掉 - _ / .
            val v = value.trim().lowercase()
                .replace("-", "").replace("_", "").replace("/", "").replace(".", "")
            return when (v) {
                // GBC 历史迁移：合并到 GB
                "gbc", "gameboycolor" -> GB
                // NES / Famicom
                "nes", "fc", "famicom", "fceumm", "fceux", "nestopia" -> NES
                // SNES / Super Famicom
                "sfc", "snes", "supernintendo", "snes9x" -> SFC
                // GB
                "gb", "gameboy" -> GB
                // GBA
                "gba", "gameboyadvance" -> GBA
                // DOSBox-Pure
                "dos", "dosbox", "dosboxpure" -> DOS
                // FBNeo / Arcade
                "arcade", "fbneo", "fbneocore", "mame", "cps1", "cps2", "cps3",
                "neogeo", "pgm", "finalburn" -> ARCADE
                // Genesis / Mega Drive
                "md", "genesis", "megadrive", "segagenesis", "megacd", "segacd",
                "genplus", "genplusgx", "genesisplusgx" -> MD
                // PC-Engine / TurboGrafx-16
                "pce", "pcengine", "turbografx", "tg16", "tg16cd", "geargrafx",
                "supergrafx" -> PCE
                // Nintendo DS (melonDS)
                "nds", "ds", "nintendo", "nintendods", "melonds" -> NDS
                // Sony PlayStation 1 (PCSX-ReARMed)
                "psx", "ps1", "playstation", "playstation1", "sony", "pcsx",
                "pcsxrearmed", "pcsxr" -> PSX
                // Sony PlayStation 2 (PCEE2 libretro core — PCSX2)
                "ps2", "playstation2", "play", "psx2", "pcsx2" -> PS2
                // Sega Dreamcast / Naomi / Atomiswave (Flycast)
                "dc", "dreamcast", "flycast", "reicast", "naomi",
                "atomiswave", "gdrom", "segadreamcast" -> DC
                // J2ME
                "java", "j2me", "midlet" -> JAVA
                // 兜底：未识别的字符串保持 NES 行为不变（旧 API 兼容）
                else -> values().firstOrNull { it.name.equals(value, ignoreCase = true) } ?: NES
            }
        }

        /**
         * Determine platform from a ROM file extension.
         * GB and GBC are merged into a single GB category.
         *
         * DOSBox accepts: .bat (batch launcher), .exe (DOS executable),
         * .com (small DOS executable), .dosz (dosbox-pure zip bundle),
         * .conf (dosbox config), .iso/.cue/.img (CD images),
         * .ima/.vhd/.hd (hard disk images).
         *
         * FBNeo (Arcade) accepts: .zip / .7z archives (the archive itself
         * IS the ROM — arcade ROMs are stored as zip files named after
         * their MAME-style driver, e.g. "mvc.zip", "kof97.zip").
         *
         * Genesis-Plus-GX (MD) accepts MD/Genesis ROMs (.md/.smd/.gen),
         * Master System (.sms), Game Gear (.gg), SG-1000 (.sg),
         * and Mega-CD images (.cue/.chd/.iso).
         *
         * NOTE on .zip: arcade ROMs are .zip files, but users may also
         * store other ROM types in .zip archives. The fromExtension()
         * function returns null for .zip so the caller (detectPlatformFromUri)
         * can peek inside the zip to find the actual ROM extension. If
         * the zip contains no recognized ROM extension, the caller should
         * default to ARCADE (since arcade zips contain raw .bin ROM files
         * which are not part of any other platform's standard).
         */
        fun fromExtension(ext: String): GamePlatform? {
            return when (ext.lowercase()) {
                "nes", "unf", "unif", "fds", "nez", "unh" -> NES
                "smc", "sfc", "swc", "fig", "bs" -> SFC
                "gb", "sgb", "gbc" -> GB
                "gba" -> GBA
                // DOSBox-Pure — executable launchers and bundle formats.
                "bat", "exe", "com", "dosz", "conf", "iso", "cue", "img", "ima", "vhd", "hd" -> DOS
                // FBNeo (Arcade) — .7z is unambiguously arcade (no other
                // platform uses .7z in this app). .zip is handled by the
                // caller (detectPlatformFromUri) because users may store
                // other ROM types in zip archives.
                "7z" -> ARCADE
                // Genesis-Plus-GX — MD/SMS/GG/SG cartridge + Mega-CD images.
                // NOTE: .bin is intentionally NOT mapped here — it is too
                // ambiguous (also used by arcade ROMs and DOS disk images).
                // .bin files inside .zip archives are inspected by
                // detectPlatformFromUri, which checks for arcade-style
                // extensions first. Bare .bin files default to MD via
                // the platform-tab selection in the import flow.
                "md", "smd", "gen", "sms", "gg", "sg", "68k" -> MD
                "chd" -> MD   // SEGA CD / Mega-CD CHD images
                // Geargrafx — PC-Engine / TurboGrafx-16 / SuperGrafx /
                // PCE-CD. .pce = cart, .sgx = SuperGrafx cart, .hes = sound
                // rip. (.cue/.chd for PCE-CD share extensions with Mega-CD
                // and DOS — disambiguated by the user's platform tab in
                // detectPlatformFromUri.)
                "pce", "sgx", "hes" -> PCE
                // Nintendo DS (melonDS libretro)
                // .nds = DS cartridge, .app = DSiWare, .ids = some ROM hacks,
                // .srl = DS emulator save/ROM, .dsi = DSi cartridge
                "nds", "app", "ids", "srl", "dsi" -> NDS
                // Sony PlayStation 1 (PCSX-ReARMed)
                // .bin/.cue = CD image pair (most common), .pbp = PSP-style PSX
                // eboot bundle, .m3u = playlist, .chd = compressed CD,
                // .ecm = compressed CD, .mds/.mdf = Alcohol 120% image.
                // .bin alone is ambiguous (also used by MD/arcade) — but in
                // a .cue/.pbp context it's PSX. detectPlatformFromUri handles
                // .cue by looking at sibling files.
                "pbp", "m3u", "ecm", "mds", "mdf" -> PSX
                // Sony PlayStation 2 (PCEE2 libretro core — PCSX2)
                // .iso is shared with DOS (disambiguated by the user's
                // platform tab in detectPlatformFromUri). Unambiguous PS2-only
                // extensions: .cso/.zso (compressed iso), .isz (legacy,
                // kept for old libraries), .elf (PS2 homebrew/executable).
                // NOTE: .elf is ALSO a Dreamcast homebrew format — flycast's
                // supported extensions include .elf. The PS2 mapping wins for
                // historical reasons; DC users can pick the DC tab during
                // import (detectFromUri hint) which overrides this.
                "cso", "isz", "elf", "zso" -> PS2
                // Sega Dreamcast / Naomi / Atomiswave (Flycast).
                // .cdi = DiscJuggler image (most common DC dump),
                // .gdi = raw GD-ROM (two tracks + data), .chd = compressed,
                // .cue/.iso/.m3u shared with other CD platforms
                // (disambiguated by the platform tab / hint).
                // .zip/.7z = Naomi / Atomiswave MAME romsets (shared with
                // Arcade — disambiguated by the platform tab / zip peek).
                "cdi", "gdi" -> DC
                "jar", "jad" -> JAVA
                // .zip is intentionally NOT mapped — see detectPlatformFromUri
                // for the disambiguation logic.
                // .bin is intentionally NOT mapped — too ambiguous.
                else -> null
            }
        }

        /**
         * DOSBox launcher file extensions (used by the folder-import flow).
         * When a user picks a folder, we look for files with these extensions
         * and pick the best launch candidate (play.bat > run.bat > START.BAT >
         * autoexec.bat > setup.exe > any .exe > any .com).
         */
        val DOS_LAUNCHER_EXTENSIONS = setOf("bat", "exe", "com")

        /**
         * Files preferred as folder-import launch targets, in priority order.
         * The first matching file (case-insensitive) becomes the game's entry.
         */
        val DOS_LAUNCHER_PRIORITY = listOf(
            "play.bat", "run.bat", "start.bat", "autoexec.bat",
            "go.bat", "launch.bat", "main.bat",
            "play.exe", "run.exe", "start.exe", "setup.exe",
            "game.exe", "main.exe", "launch.exe"
        )
    }
}

data class GameEntry(
    val id: String,
    val title: String,
    val subtitle: String? = null,
    val accent: Color = Color(0xFFE74C3C),
    val romPath: String? = null,
    val coverPath: String? = null,
    val lastPlayedAt: Long = 0L,
    val playTimeMs: Long = 0L,
    val isFavorite: Boolean = false,
    val platform: GamePlatform = GamePlatform.NES,
    val customIconPath: String? = null,
    /**
     * User-defined display name. When non-null and non-blank, this overrides
     * [title] for display purposes in the home screen, library, and long-press
     * menus. The underlying ROM file name is NEVER changed — this is purely
     * an app-level cosmetic override.
     */
    val customTitle: String? = null
)
