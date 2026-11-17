================================================================================
  Geargrafx — PCE-CD (PC Engine CD) BIOS Bundle
================================================================================

This directory is scanned at app startup by NesApp.ensurePceBios().
Any .pce BIOS file found here is auto-detected, renamed to the canonical
name the core expects, and extracted to <filesDir>/pce/ on first launch.
After extraction, Geargrafx will look up PCE-CD BIOS files in that
directory when loading PCE-CD disc images.

--------------------------------------------------------------------------------
WHEN BIOS FILES ARE NEEDED
--------------------------------------------------------------------------------

           +---------------------------+----------------------+
           |  Cartridge / HuCard games |  Disc (PCE-CD) games |
           |  PCE / SuperGrafx / HES   |  .cue / .chd         |
+----------+---------------------------+----------------------+
| BIOS?    |  NO — boots directly     |  YES — required      |
| Format   |  .pce .sgx .hes          |  .cue / .chd         |
|          |                          |  (paired w/ .bin)    |
+----------+---------------------------+----------------------+

PCE-CD games use a "System Card" BIOS that boots the CD-ROM unit and loads
the game disc. Without it the core cannot boot ANY PCE-CD game and will
report "CD-ROM BIOS not found: syscard3.pce".

HuCard / cartridge games (.pce / .sgx) and HES music rips (.hes) do NOT
use a BIOS — they boot directly from the cartridge ROM.

--------------------------------------------------------------------------------
REQUIRED BIOS FILES
--------------------------------------------------------------------------------

File           | Description                                  | Required
---------------|----------------------------------------------|---------
syscard1.pce   | CD-ROM System Card V1.xx (Japan)             | optional
syscard2.pce   | CD-ROM System Card V2.xx (Japan)             | optional
syscard3.pce   | Super CD-ROM2 System V3.xx / Arcade Card Pro | REQUIRED (recommended)
gexpress.pce   | Game Express CD Card (Japan)                 | optional

syscard3.pce is the most common and is auto-selected by the core when the
"geargrafx_cdrom_bios" option is "Auto" (the app's default). It supports
Super CD-ROM2 games (the majority of PCE-CD releases). Install at least
syscard3.pce; the others are only needed for older or special titles.

NOTE ON THE FILENAME:
  The core looks for "gexpress.pce" — NOT "gameexpress.pce". If you import
  / bundle a Games Express BIOS, make sure it ends up named gexpress.pce.

Verification (md5sum — from the libretro core documentation):
  syscard3.pce  -> 38179df8f4ac870017db21ebcbf53114

Geargrafx also recognises these BIOS variants by CRC32 (from the core's
built-in database):
  CD-ROM System Card [1.0] (J)         CRC32 3F9F95A4
  CD-ROM System Card [2.0] (J)         CRC32 52520BC6
  CD-ROM System Card [2.1] (J)         CRC32 283B74E0
  Super CD-ROM System [3.0] (J)        CRC32 6D9A73EF
  TurboGrafx CD System Card [2.0] (USA) CRC32 FF2A5EC3
  TurboGrafx CD Super System [3.0] (USA) CRC32 2B5B75FE
  Game Express Card [Blue] (J)         CRC32 51A12D90
  Game Express Card [Green] (J)        CRC32 16AAF05A

The core pads smaller original dumps (e.g. the 32/64 KiB System Card
dumps) into its internal 256 KiB buffer, so size mismatch alone is not a
problem — but a corrupt or wrong-region file will still fail to boot.

--------------------------------------------------------------------------------
WHERE TO GET THEM
--------------------------------------------------------------------------------

These BIOS files are part of the standard libretro / RetroArch BIOS pack.
The Geargrafx project does NOT host them due to copyright.

Common sources:
  - "RetroArch BIOS pack" (community collection) — files are typically
    named syscard1.pce / syscard2.pce / syscard3.pce / gexpress.pce
  - RetroArch's built-in Online Updater → Download BIOS (fetches the
    official libretro system files)
  - The libretro documentation page for the Beetle PCE Fast core lists
    the same syscard3.pce / syscard2.pce / syscard1.pce / gexpress.pce
    files and their md5sums.

Verify the files by md5sum / CRC32 (see above) — corrupted or wrong-region
BIOS files will cause "CD-ROM BIOS not found" / boot failures.

--------------------------------------------------------------------------------
HOW TO ADD BIOS FILES (TWO WAYS)
--------------------------------------------------------------------------------

WAY 1 — BUNDLE IN APK (recommended for personal-use builds):
  1. Copy syscard1.pce, syscard2.pce, syscard3.pce and/or gexpress.pce
     into THIS directory (app/src/main/assets/pce/). Files with other
     names are also fine — NesApp.ensurePceBios() auto-detects them by
     their filename (e.g. "System Card 3.0.pce" → syscard3.pce,
     "Game Express.pce" → gexpress.pce) and copies them under the
     canonical name the core expects.
  2. Build and install the APK
  3. NesApp.ensurePceBios() auto-extracts them to <filesDir>/pce/ on
     first launch — no manual steps required

WAY 2 — IMPORT AT RUNTIME (for distributed APKs):
  1. Launch any PCE-CD game (or open Settings → PCE → PCE-CD BIOS 管理)
  2. Tap "导入 PCE-CD BIOS (.pce)"
  3. Pick the .pce file using the system file picker
  4. The file is copied to <filesDir>/pce/ immediately (filename is
     auto-detected; anything unrecognised becomes syscard3.pce)
  5. Reload the game — BIOS is now available

--------------------------------------------------------------------------------
ROM FORMATS SUPPORTED BY GEARGRAFX
--------------------------------------------------------------------------------

Cartridge / HuCard:
  .pce                  — PC Engine / TurboGrafx-16 ROMs (most common)
  .sgx                  — SuperGrafx ROMs
  .hes                  — HES music rips (no BIOS needed)

Disc (PCE-CD — REQUIRES BIOS):
  .cue + .bin           — Standard CD image (cue sheet + binary data)
  .chd                  — MAME compressed CD image (single file)

--------------------------------------------------------------------------------
LEGAL NOTICE — READ CAREFULLY
--------------------------------------------------------------------------------

These BIOS files contain copyrighted NEC / Hudson Soft firmware code. You
may NOT redistribute them publicly (e.g. on GitHub, in a public APK
release, or via any file-sharing service).

You may only bundle them in private APK builds for your own personal use
on your own devices. For public / open-source distribution, leave this
directory empty — users will be prompted to import the BIOS files
manually via the Settings → PCE → PCE-CD BIOS 管理 UI.

By using this emulator you agree that you are solely responsible for
obtaining BIOS files legally and for complying with all applicable
copyright laws in your jurisdiction.

PC Engine, TurboGrafx-16, SuperGrafx, and Super CD-ROM2 are trademarks of
their respective owners. This project is not affiliated with, endorsed by,
or sponsored by NEC or Hudson Soft.
