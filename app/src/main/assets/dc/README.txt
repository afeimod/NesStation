Flycast (Dreamcast / Naomi / Atomiswave) BIOS 占位说明
=====================================================

此目录用于打包私有 BIOS（可选）。此仓库本身不包含任何 BIOS 文件。

支持的文件（启动时自动解压到 <filesDir>/dc/）：

  dc_boot.bin      Dreamcast 启动 ROM（GD-ROM 游戏必需，2 MB）
  dc_flash.bin     Dreamcast 闪存 ROM（必需，128 KB，含区域/语言/时钟设置）
  naomi.zip        Naomi 街机 BIOS（MAME romset，Naomi 游戏必需）
  atomiswave.zip   Atomiswave 街机 BIOS（MAME romset，Atomiswave 游戏必需）

常见来源命名（MAME / RetroArch 社区惯例）：
  dc_boot.bin  = boot.bin / dc_boot.bin (MD5: e10c53c2f8b90acc9603fd7b2b5d0d60)
  dc_flash.bin = flash.bin / dc_flash.bin (MD5: 0a93f702e3f749df621ccaecfc03a92e)

放置方式二选一：
  1. 将上述文件放入本目录后重新构建 APK（启动时自动解压）。
  2. 运行时放入设备私有目录 <filesDir>/dc/（可通过设备文件管理器或 adb push）。

注意：
  - BIOS 文件包含 SEGA 等厂商的版权代码，仅限个人合法获取使用，
    请勿公开分发包含 BIOS 的 APK。
  - Naomi / Atomiswave 游戏本体是 MAME romset（.zip），与 Arcade (FBNeo)
    的 zip 同名区分：导入时请选择「DC」平台标签页。
