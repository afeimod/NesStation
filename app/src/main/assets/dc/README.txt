Flycast (Dreamcast / NAOMI) BIOS 目录 — 私有构建播种说明
==========================================================

本目录对应运行时的 <filesDir>/dc/data/（Flycast 核心的默认 BIOS/VMU/存档目录）。

Dreamcast 光盘游戏（.gdi / .cdi / .cue / .chd / .iso）需要以下两个 BIOS 文件：

  dc_boot.bin    — Dreamcast BIOS ROM（约 2MB，常见 MD5: 5ea414f57a9cd257cba9e7c5cb77f2fe）
  dc_flash.bin   — Dreamcast 闪存（约 128KB，常见 MD5: 870e91db7b1c8b1a74e35e1c67e330ba）

  命名说明：核心也接受 dc_bios.bin 作为 boot ROM 别名、dc_flash_wb.bin 作为
  闪存别名；导入到 <filesDir>/dc/data/ 时请使用标准命名。

NAOMI / AtomisWave 街机游戏（.zip）自带 BIOS，无需放入任何文件。

私有构建（可选）：把 dc_boot.bin / dc_flash.bin 放进本目录
（app/src/main/assets/dc/）后重新构建 APK，应用启动时会自动识别并解压到
<filesDir>/dc/data/ —— 已存在的用户导入文件优先，不会被覆盖。

注意法律声明：BIOS 文件包含受版权保护的 SEGA 代码，本仓库不包含任何 BIOS
文件，也不能在公开渠道分发包含 BIOS 的 APK，仅限个人合法获取后私有使用。
