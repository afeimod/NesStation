Flycast (Dreamcast / Naomi / Atomiswave) BIOS 说明
===================================================

此目录打包预置 BIOS，应用首次启动时自动解压到 <filesDir>/dc/。

Dreamcast 主机 BIOS（GD-ROM 游戏必需）：
  dc_boot.bin      启动 ROM（2 MB）
  dc_flash.bin     闪存 ROM（128 KB，含区域/语言/时钟设置）
  dc_nvmem.bin     NVRAM 初始备份（128 KB，可选）
  dc_bios.bin      boot ROM 备用命名副本（若 dc_boot.bin 非标准 dump，
                   启动时校验 MD5 后自动以其内容生成 dc_boot.bin）

街机 BIOS（MAME romset zip，Flycast 按原名识别，Naomi / Atomiswave 游戏必需）：
  naomi.zip        Naomi
  naomi2.zip       Naomi 2
  awbios.zip       Atomiswave
  hod2bios.zip     Naomi 2 专用（House of the Dead 2 等）
  f355bios.zip     F355 Challenge（多板卡）
  f355dlx.zip      F355 Deluxe（多板卡）
  airlbios.zip     Airline Pilots（多板卡）

预置 VMU 存档卡（128 KB × 4，避免首启因 VMU 缺失无法存档）：
  vmu_save_A1.bin / vmu_save_B1.bin / vmu_save_C1.bin / vmu_save_D1.bin

常见来源命名对照（MAME / RetroArch 社区惯例）：
  dc_boot.bin = boot.bin / dc_bios.bin (MD5: e10c53c2f8b90bab96ead2d368858623)
  dc_flash.bin = flash.bin (MD5: 0a93f702e3f749df621ccaecfc03a92e)

运行时手动放置：将上述文件放入设备私有目录 <filesDir>/dc/（adb push 或
文件管理器）。已有同名文件不会被预置文件覆盖。

注意：
  - BIOS 文件包含 SEGA 等厂商的版权代码，仅限个人合法获取使用，
    请勿公开分发包含 BIOS 的 APK。
  - Naomi / Atomiswave 游戏本体是 MAME romset（.zip），与 Arcade (FBNeo)
    的 zip 同名区分：导入时请选择「DC」平台标签页。
