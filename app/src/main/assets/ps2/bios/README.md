# PS2 默认 BIOS 目录（assets/ps2/bios/）

把 PS2 BIOS 件文件放在本目录，打包进 APK 后应用会在**首次启动时自动安装**到
`<filesDir>/ps2/pcsx2/bios/`（见 `NesApp.ensurePs2Bios()`），PCSX2/ARMSX2 的
`LoadBIOS()` 在未配置 BIOS 时会自动扫描该目录并选用（`FindBiosImage()`，
见 `ARMSX2-master/pcsx2/ps2/BiosTools.cpp`）—— **用户零导入、零配置**，
与 DC BIOS（assets/dc/）完全同一机制。

## 支持的文件名（常见型号，任放其一即可）

| 文件名            | 型号 / 区域          |
|-------------------|----------------------|
| scph10000.bin     | SCPH-10000 (日, 初版) |
| scph10001.bin     | SCPH-10001 (美)      |
| scph10002.bin     | SCPH-10002 (欧)      |
| scph15000.bin     | SCPH-15000 (日)      |
| scph18000.bin     | SCPH-18000 (日, GH-...) |
| scph30000.bin 等  | 30000/35000/39000 系 |
| scph39001.bin     | SCPH-39001 (美, 推荐) |
| scph50000.bin 等  | 50000/50001/50002 系 |
| scph70004.bin     | SCPH-70004 (欧, 末代) |

文件名不要求严格匹配 —— 目录里**任何**能通过 PCSX2 `IsBIOS()` 魔数校验的
文件（4MB 左右的完整 BIOS dump）都会被自动识别使用。

## 快捷方式

```bash
# 从你手头的 BIOS 文件自动拷贝进本目录（之后正常构建 APK 即可）
./scripts/fetch_ps2_bios.sh /path/to/scph39001.bin [更多文件...]
```

## 版权提示

PS2 BIOS 属 Sony 专有固件，受版权保护。本仓库**不**随源码分发任何 BIOS
二进制；由构建者自行取得并决定打包合规性（本目录中的 README.md /
说明文件会被安装逻辑跳过，不会写入用户目录）。
