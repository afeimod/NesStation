DC (Dreamcast / NAOMI / AtomisWave) BIOS 目录 — 私有构建自动识别
================================================================

本目录的文件由 NesApp.ensureDcBios() 在应用启动时自动部署（已存在的
用户文件优先，不会被覆盖）：

  · BIOS → <filesDir>/dc/          （libretro Flycast 的 <system>/dc/）
  · VMU → <filesDir>/saves/        （vmu_save_A1.bin … D1.bin，与其他核心
                                     的存档同一目录）

libretro Flycast 核心按 RetroArch 惯例在系统目录的 dc/ 子目录下查找
BIOS；NesStation 传给核心的系统目录是 filesDir 根目录，因此部署目标
就是 <filesDir>/dc/（与其他平台的 psx/、pce/、genesis/ 目录约定一致）。

文件清单
--------

Dreamcast 光盘游戏（.gdi / .cdi / .cue / .chd / .iso）需要：

  dc_boot.bin    — Dreamcast BIOS ROM（约 2MB）
  dc_bios.bin    — 核心接受的 boot ROM 别名（核心优先读 dc_boot.bin）
  dc_flash.bin   — Dreamcast 闪存（约 128KB）
  dc_nvmem.bin   — Dreamcast NVMEM（部分核心路径使用）

NAOMI / AtomisWave 街机游戏（.zip）需要对应系统的 BIOS 包：

  naomi.zip      — NAOMI（多数 NAOMI 游戏）
  naomi2.zip     — NAOMI 2（后期 NAOMI 游戏）
  awbios.zip     — AtomisWave
  f355bios.zip   — F355 Challenge（NAOMI 专用 BIOS）
  f355dlx.zip    — F355 Deluxe
  hod2bios.zip   — The House of the Dead 2
  airlbios.zip   — Airline Pilots

VMU（记忆卡，核心在存档目录创建/使用）：

  vmu_save_A1.bin … vmu_save_D1.bin — 四个手柄槽位的 VMU 模板

注意法律声明：BIOS 文件包含受版权保护的 SEGA 代码，本仓库（公开版）
不包含任何 BIOS 文件；此目录由私有构建按需放置，仅限个人合法获取后
私有使用。
