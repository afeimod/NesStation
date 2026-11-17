================================================================================
  Genesis-Plus-GX — Mega-CD / SEGA-CD BIOS Bundle
================================================================================

This directory is scanned at app startup by NesApp.ensureGenesisBios().
Any BIOS .zip found here is auto-extracted to <filesDir>/genesis/ on first
launch. After extraction, Genesis-Plus-GX will look up Mega-CD BIOS files
in that directory when loading Mega-CD / SEGA-CD disc images.

--------------------------------------------------------------------------------
WHEN BIOS FILES ARE NEEDED
--------------------------------------------------------------------------------

           +--------------------------+-------------------+
           |  Cartridge games         |  Disc games       |
           |  MD / SMS / GG / SG      |  Mega-CD / SEGA-CD|
+----------+--------------------------+-------------------+
| BIOS?    |  NO — boots directly    |  YES — required   |
| Format   |  .md .smd .gen .sms     |  .cue .chd .iso   |
|          |  .gg .sg .68k .bin      |  (paired w/ .bin) |
+----------+--------------------------+-------------------+

Mega-CD / SEGA-CD games have a bootstrap BIOS that initializes the CD
controller, displays the SEGA logo / BIOS animation, then loads the game
disc's IP.BIN boot sector. Without the BIOS ROM, the core cannot boot
any Mega-CD game and will report "BIOS not found".

Cartridge games (MD / SMS / GG / SG) do NOT use a BIOS — they boot
directly from the cartridge ROM.

--------------------------------------------------------------------------------
REQUIRED BIOS FILES
--------------------------------------------------------------------------------

File           | Region   | Required for                  | MD5 (verification)
----------------|----------|-------------------------------|------------------------
bios_CD_E.zip  | Europe   | European Mega-CD games        | e66fa1dc5820d254611fdcdba0662372
bios_CD_J.zip  | Japan    | Japanese Mega-CD games        | 278a9397d192149e84e820ac621a8edd
bios_CD_U.zip  | USA      | US SEGA-CD games              | 2efd74e3232ff260e371b99f84024f7f
----------------|----------|-------------------------------|------------------------

Each zip must contain a single .bin file:
  bios_CD_E.zip  →  bios_CD_E.bin  (~512 KiB)
  bios_CD_J.zip  →  bios_CD_J.bin  (~512 KiB)
  bios_CD_U.zip  →  bios_CD_U.bin  (~512 KiB)

The core option "genesis_plus_gx_cd_bios" (auto | bios_CD_E | bios_CD_J |
bios_CD_U) controls which BIOS is used when "auto" detects the disc's
region incorrectly. Set it explicitly in Settings → MD/SEGA if you have
a region-mismatched disc.

--------------------------------------------------------------------------------
WHERE TO GET THEM
--------------------------------------------------------------------------------

These BIOS files are part of the standard libretro / RetroArch BIOS pack.
The official Genesis-Plus-GX project does NOT host them due to copyright.

Common filenames / pack names you may find them under:
  - "RetroArch BIOS pack"
  - "libretro system BIOS"
  - "Mega-CD BIOS" / "SEGA-CD BIOS"
  - Files are typically named bios_CD_E.bin / bios_CD_J.bin / bios_CD_U.bin

Verify the files by their MD5 (see table above) — corrupted or wrong-region
BIOS files will cause "BIOS load failed" errors.

--------------------------------------------------------------------------------
HOW TO ADD BIOS FILES (TWO WAYS)
--------------------------------------------------------------------------------

WAY 1 — BUNDLE IN APK (recommended for personal-use builds):
  1. Copy bios_CD_E.zip, bios_CD_J.zip, bios_CD_U.zip into THIS directory
     (app/src/main/assets/genesis/)
  2. Build and install the APK
  3. NesApp.ensureGenesisBios() auto-extracts them to <filesDir>/genesis/
     on first launch — no manual steps required

WAY 2 — IMPORT AT RUNTIME (for distributed APKs):
  1. Launch any Mega-CD game (or open Settings → MD/SEGA → BIOS Management)
  2. Tap "导入BIOS zip" / "Import BIOS zip"
  3. Pick the .zip file using the system file picker
  4. The file is copied to <filesDir>/genesis/ immediately
  5. Reload the game — BIOS is now available

--------------------------------------------------------------------------------
ROM FORMATS SUPPORTED BY GENESIS-PLUS-GX
--------------------------------------------------------------------------------

Cartridge:
  .md / .smd / .gen     — Mega Drive / Genesis ROMs (most common)
  .bin                  — Raw MD ROM dump (paired with .bin + .cue for CD)
  .sms                  — Master System ROMs
  .gg                   — Game Gear ROMs
  .sg                   — SG-1000 ROMs
  .68k                  — Mega Drive ROM (raw 68000 dump)

Disc (Mega-CD / SEGA-CD — REQUIRES BIOS):
  .cue + .bin           — Standard CD image (cue sheet + binary data)
  .chd                  — MAME compressed CD image (single file)
  .iso                  — Plain ISO image (some games work, others need .cue)

--------------------------------------------------------------------------------
LEGAL NOTICE — READ CAREFULLY
--------------------------------------------------------------------------------

These BIOS files contain copyrighted SEGA firmware code. You may NOT
redistribute them publicly (e.g. on GitHub, in a public APK release, or
via any file-sharing service).

You may only bundle them in private APK builds for your own personal use
on your own devices. For public / open-source distribution, leave this
directory empty — users will be prompted to import the BIOS files
manually via the Settings → MD/SEGA → BIOS Management UI.

By using this emulator you agree that you are solely responsible for
obtaining BIOS files legally and for complying with all applicable
copyright laws in your jurisdiction.

SEGA, Mega Drive, Mega-CD, Master System, Game Gear, and SG-1000 are
trademarks of SEGA Holdings Co., Ltd. This project is not affiliated
with, endorsed by, or sponsored by SEGA.
