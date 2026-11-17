================================================================================
  FBNeo (Final Burn Neo) — Arcade BIOS Bundle
================================================================================

This directory is scanned at app startup by NesApp.ensureFbNeoBios().
Any BIOS .zip found here is auto-extracted to <filesDir>/fbneo/ on first
launch. After extraction, FBNeo will look up BIOS files in that directory
when loading arcade ROMs.

--------------------------------------------------------------------------------
WHY BIOS FILES ARE NEEDED
--------------------------------------------------------------------------------

Arcade machines are not self-contained like console cartridges. The actual
game ROM zip (e.g. kof97.zip, mvc.zip) only contains the program / graphics /
audio data for that specific game. The BIOS files contain the SYSTEM code
that boots the hardware (sound CPU program, fix layer font, security PIC
decryption keys, etc.) — these are SHARED across all games on the same
hardware platform and are looked up by FBNeo from the system directory.

If a required BIOS file is missing, FBNeo will refuse to load the game and
show an error like "Files missing: neogeo.zip" or "BIOS romset <name> not
found". CPS1/CPS2/CPS3 games generally boot without BIOS (the BIOS is
integrated into the program ROM), but NeoGeo and PGM games ALWAYS require
their respective BIOS zip.

--------------------------------------------------------------------------------
REQUIRED BIOS FILES BY HARDWARE
--------------------------------------------------------------------------------

File             | Hardware          | Required for
-----------------|-------------------|-------------------------------------------
neogeo.zip       | NeoGeo MVS/AES    | ALL NeoGeo games (KOF, Metal Slug, SamSho)
pgm.zip          | PolyGame Master   | ALL PGM games (Knights of Valour / 三国战纪,
                 |                   |   Demon Front / 魔窟, Espgaluda, DoDonPachi)
neocdz.zip       | NeoGeo CDZ        | NeoGeo CD-based games (rare)
cvs2.zip         | CPS2 decrypt key  | Capcom VS SNK 2 (cvs2, cvspro)
cps1.zip         | CPS1 BIOS         | Some CPS1 bootlegs (varies by set)
cps2.zip         | CPS2 BIOS         | Some CPS2 games (varies by set)
stvbios.zip      | ST-V BIOS         | ST-V arcade games (varies)
tickgal.zip      | decryption key    | Some Galaxian-based bootlegs
-----------------|-------------------|-------------------------------------------

CPS1 / CPS2 / CPS3 games (Street Fighter II, Marvel vs Capcom, Darkstalkers,
Progear, etc.) DO NOT require any BIOS files — they are fully self-contained.

--------------------------------------------------------------------------------
WHAT EACH neogeo.zip SHOULD CONTAIN
--------------------------------------------------------------------------------

neogeo.zip must contain the following files (exact filenames, lowercase):
  - 000-lo.lo    — LO ROM (system program / locator, 64 KiB)
  - sm1.sm1      — SM1 sound CPU program (Z80 code, 128 KiB)
  - sfix.sfix    — SFIX layer ROM (fix layer font/graphics, 128 KiB)
  - sp1.sp1 / sp-j3.sp1 / sp-1v1_3db6c.sp1 / vs-bios.rom / japan-jmp.rom
                  — One of these main BIOS ROMs (128 KiB each)
                  - sp1.sp1           = US MVS BIOS (most common)
                  - sp-j3.sp1         = Japan MVS BIOS
                  - japan-jmp.rom     = Japan AES BIOS
                  - uni-bios.rom      = Universe BIOS (alternative, see below)

PGM zip should contain:
  - pgm_bios.rom — PGM main BIOS (512 KiB, IGS)
  - pgm_memcard.rom — memory card data (optional)

--------------------------------------------------------------------------------
WHERE TO GET THEM
--------------------------------------------------------------------------------

These BIOS files are part of the standard FBNeo / MAME full ROM set.
Common distribution names you may find on the web:
  - "FBNeo full ROM set"
  - "MAME 0.2xx ROM set (complete)"
  - "NeoGeo complete BIOS pack"

The official FBNeo / MAME projects do NOT host these files due to copyright.
You must obtain them yourself. Only use BIOS files for hardware you own or
for arcade boards you have legally dumped yourself.

The Universe BIOS (uni-bios.rom) by Razoola is freely downloadable for
personal use from http://unibios.free.fr/ — but its license EXPLICITLY
forbids redistribution, so it cannot be bundled in this APK. You can
download it manually and add it to your neogeo.zip as a replacement for
sp1.sp1 / sp-j3.sp1 — it provides cheat menus, region switching, debug
options, etc.

--------------------------------------------------------------------------------
HOW TO ADD BIOS FILES (TWO WAYS)
--------------------------------------------------------------------------------

WAY 1 — BUNDLE IN APK (recommended for personal-use builds):
  1. Copy neogeo.zip, pgm.zip, etc. into THIS directory
     (app/src/main/assets/fbneo/)
  2. Build and install the APK
  3. NesApp.ensureFbNeoBios() auto-extracts them to <filesDir>/fbneo/ on
     first launch — no manual steps required

WAY 2 — IMPORT AT RUNTIME (for distributed APKs):
  1. Launch any arcade game (or open Settings → Arcade → BIOS Management)
  2. Tap "导入BIOS zip" / "Import BIOS zip"
  3. Pick the .zip file using the system file picker
  4. The file is copied to <filesDir>/fbneo/ immediately
  5. Reload the game — BIOS is now available

--------------------------------------------------------------------------------
LEGAL NOTICE — READ CAREFULLY
--------------------------------------------------------------------------------

These BIOS files contain copyrighted code (SNK for NeoGeo, IGS for PGM,
Capcom for CPS2 keys, etc.). You may NOT redistribute them publicly
(e.g. on GitHub, in a public APK release, or via any file-sharing service).

You may only bundle them in private APK builds for your own personal use
on your own devices. For public / open-source distribution, leave this
directory empty — users will be prompted to import the BIOS files
manually via the Settings → Arcade → BIOS Management UI.

By using this emulator you agree that you are solely responsible for
obtaining BIOS files legally and for complying with all applicable
copyright laws in your jurisdiction.
