# NesStation 四大问题修复说明（2026-09-27）

本仓库已修复以下 4 个问题。所有修改均带 `★` 注释标注修复点与原因。

---

## 问题 1 · DC 核心虚拟按键组合键缺失 + 三指连键

### 1a. 组合键 picker 漏掉 DC 平台
**文件**：`app/src/main/java/com/nesstation/app/ui/emulator/EmulatorScreen.kt`
**位置**：`ComboButtonPickerDialog.availableButtons`（约 10295-10335 行）
**根因**：`if (platform == GamePlatform.SFC || ... || GamePlatform.PSX)` 漏了 `GamePlatform.DC`，
导致 DC 平台的组合键 picker 只能选 A/B/Start/Select 4 个键。
**修复**：在两个 `if` 条件里加 `|| platform == GamePlatform.DC`，并新增方向键（↑↓←→）
作为所有平台的通用组合成员（用于"↓+A 滑铲"、"↑+B 上挑"等组合）。

### 1b. DC 组合键不保存
**文件**：同上
**位置**：3 处 `when (platform)` 分支（约 9774、10096、10138 行）
**根因**：DC 平台读写不一致 —— `parseComboButtons` 把 DC 映射到 `padLayout.comboButtons`
（读路径正确），但保存/删除/添加的 `when` 分支里 DC 走 `else -> padLayout`（写路径丢弃）。
**修复**：3 处 when 分支统一改为
`GamePlatform.NES, GamePlatform.GB, GamePlatform.DC -> padLayout.copy {comboButtons = json}`

### 1c. 三指连键（多点触控按键粘连）
**文件**：同上
**位置**：`OnScreenController.pointerInput` 的 UP/MOVE 处理（约 6554、6578、6587、6595 行）
**根因**：UP 时用 `visualState = visualState and heldBits.inv()` 直接清位，
会误清掉其它手指还在按的同位按键。典型场景：指A 按 A、指B 按 AB 组合、指C 按 B，
松开指B → A 和 B 都被清掉（visualState 变 0），但用户仍按住 → "连键"。
**修复**：新增 `recomputeVisualState()` 和 `recomputeTurboState()` helper，从剩余的
`activePointers` 重算 visualState/turboState，确保多指重叠按键松手时只清该指贡献的位
（若该位被其它指继续按住则保留）。UP 和 DPAD 拖动均改用重算。

---

## 问题 2 · 封面获取问题（"Flycast 自带功能未实现"是误解）

### 关键澄清
NesStation 用的是 **libretro 版 Flycast 核心**（`libflycast_libretro_android.so`），
libretro 协议本身不暴露封面获取接口。所谓"Flycast 自带封面"是独立 Flycast Android
应用的 Java 层 HTTP 拉取，与核心无关。`CoverFetcher.kt` 已用 libretro 官方缩略图库
（`thumbnails.libretro.com/Sega - Dreamcast/`）做等效替代，且确实包含 DC 封面。
问题在于实现质量，而非功能缺失。

### 2a. SAF URI 双重编码（致命 P0）
**文件**：`app/src/main/java/com/nesstation/app/core/storage/CoverFetcher.kt`
**位置**：`fetchCover`（约 100-130 行）
**根因**：SAF 导入的 ROM `game.romPath` 是 `content://` URI，最后一段是 URL 编码的
documentId（含 `%20 %28 %5B` 等编码字符）。旧实现直接 `substringAfterLast('/')` +
`substringBeforeLast('.')` + `urlSeg()` → 双重 URL 编码 → 永远 404。
**修复**：优先用 `game.title`（导入时由 `queryDisplayName` 解码后写入，是干净的可读名），
只有 title 为空时才退回 romPath 解析。

### 2b. URL 括号编码不一致（致命 P0）
**文件**：同上
**位置**：`urlSeg`（约 197-198 行）
**根因**：`URLEncoder.encode("(", "UTF-8") = "%28"`，但 libretro 实际 URL 使用未编码括号
（如 `Named_Boxarts/Super Mario Bros. (World).png`），导致带 `(USA)/(World)/(Disc 1)`
的游戏名大量 404。
**修复**：把 `%28`/`%29` 还原为未编码括号。

### 2c. 候选名清理过激进
**文件**：同上
**位置**：`nameCandidates`（约 79-91 行）
**根因**：旧实现把所有 `(...)` 都去掉，丢失 libretro 必需的 `(USA)`、`(Disc 1)` 等标签。
**修复**：生成 4 个候选变体 —— 原名 / 只去 [] 保留 () / 全去标签 / 末尾加点（No-Intro 规范）。

### 2d. 街机平台未接入 libretro 缩略图
**文件**：同上
**位置**：`libretroSystemDir`（约 67-70 行）
**根因**：旧实现 `else -> emptyList()` 直接跳过街机平台。
**修复**：街机平台返回 `listOf("FBNeo - Arcade Games", "MAME")`，按驱动名匹配少量 ROM。

### 2e. chunked encoding 边界 case
**文件**：同上
**位置**：`downloadImage`（约 213-241 行）
**根因**：`len < 0`（chunked encoding 无 Content-Length）时直接返回 false。
**修复**：仅拦超大响应（>4MB），其它情况都走流式拷贝。

---

## 问题 3 · 3DS 游戏黑屏（核心立即退出 exit result=2）

### 用户日志关键序列
```
[+0.028] Frontend <Info> RunCitra:187 Azahar starting...
[+0.040] Config <Info> LogSettings:85 Azahar Configuration:
[+0.040] W AzaharNative: exitEmulationActivity(result=2)
```
核心启动后仅 1ms 就主动退出。通过对 `libazahar.so` 做 strings 分析，发现 native 层
`emu_window.cpp` 有 `"surface is nullptr"` 的 abort 路径 —— EmuWindow 构造时若 surface
为 null 直接 abort + exit(2)。

### 根因：surface 竞态
**文件**：`app/src/main/java/com/nesstation/app/core/engine/AzaharEngine.kt`
**位置**：`startEmulationLocked`（约 318-356 行）+ `setSurface`（约 442-464 行）

旧实现：
1. `startEmulationLocked` 在 line 322 用 `surface!!` 调 `surfaceChanged`
2. 然后 `thread(name = "AzaharNative") { val surf = surface ... lib.run(path) }` 启动新线程
3. 新线程在 line 338 **再次读 volatile `surface` 字段**

竞态窗口：
- `startEmulationLocked` 返回（释放 lifecycleLock）→ Compose 重组 → `setSurface(null)`
  抢到锁 → `this.surface = null` → 新线程读到 null surface → `lib.run(path)` 进入
  native EmuWindow 构造 → `s_surf` 为 null → `"surface is nullptr"` abort → exit(2)

### 修复：捕获局部变量
在 `startEmulationLocked` 同步块内捕获 `bootSurface = surface ?: return`，整个启动流程
都使用这个局部副本：
- 即便 `setSurface(null)` 在 `startEmulationLocked` 返回后立刻把 `this.surface` 改成 null，
  新启动的 emuThread 仍然用 `bootSurface` 调 `surfaceChanged`/`run`，native EmuWindow
  永远拿到非 null window。
- 若 `bootSurface` 已失效（Compose 销毁了 SurfaceView），循环重试最多 5 次拉取当前
  surface，命中即推给 native 并 break，避免永久黑屏。

### 配套修复：3DS 系统档案安装
**文件**：`app/src/main/java/com/nesstation/app/NesApp.kt`
**位置**：`ensureAzaharKeys`（约 806-860 行）
**根因**：旧实现只装 `aes_keys.txt`，但 3DS 加密卡带可能还需要 `boot9.bin`、
`boot11.bin`、`seeddb.bin`、`sysdata/` 目录。
**修复**：扩展为按同款"assets 自动安装"模式从 `assets/azahar/` 复制所有 3DS 系统档案
到 `<filesDir>/azahar/`，已存在则保留不覆盖。

### 配套修复：错误诊断增强
**文件**：`app/src/main/java/com/nesstation/app/core/engine/AzaharEngine.kt`
**位置**：emuThread 退出时（约 367-400 行）
**修复**：run() 提前退出时，附加详细诊断信息（surface 状态、密钥文件存在性等），
通过 `onPrematureExit` 回调呈现给用户，避免永久黑屏无提示。

---

## 问题 4 · NGC/Wii 按键/分辨率/红外全失效

### 4a. Wii `−`/`+` 按键名拼写错误（确定 bug）
**文件**：`app/src/main/java/com/nesstation/app/core/engine/IshirukaEngine.kt`
**位置**：约 289-290 行 + 329-330 行
**根因**：`wiiSet(sec, "Buttons/Minus", ...)` / `wiiSet(sec, "Buttons/Plus", ...)`，
但上游 Dolphin/Ishiiruka 的 ButtonManager 中 WiimoteEmu 的 Control 名是字面字符 `-`/`+`，
不是 `Minus`/`Plus`。绑定被静默忽略 → `−`/`+` 按键全部失效。
**修复**：改为 `wiiSet(sec, "Buttons/-", "Button 102")` / `wiiSet(sec, "Buttons/+", "Button 103")`，
Classic Controller 同款修复。

### 4b. IR 指针 4 处连环 bug
**文件**：同上
**位置**：`setPointer`（约 816-854 行）+ `setPointerDepth`（约 856-877 行）+ `setPad1`
Wii 分支（约 695-708 行）

| Bug | 位置 | 问题 |
|-----|------|------|
| B1 | `setPointer` line 822-825 | X/Y 互换：IR_UP/IR_LEFT 算错用 X/Y |
| B2 | 同上 | 负方向绑定 `"Axis 112-"` 收正值 → 永不激活 |
| B3 | `setPad1` line 698-699 | IR+/IR- 走按键事件而非轴事件 |
| B4 | `setPointerDepth` line 851 | `value = if (pressed) 1f else 0f` 恒正，Forward 永不激活 |

**修复**：
- `setPointer`：X 轴 → IR_LEFT/IR_RIGHT，Y 轴 → IR_UP/IR_DOWN，负方向绑定发负值
- `setPad1` 的 Wii pairs 列表移除 IR_FAR/IR_NEAR，改在末尾调用
  `setPointerDepth(forward = true/false, pressed = ...)` 走轴事件
- `setPointerDepth`：Forward 按下发 `-1f`，Backward 按下发 `+1f`

### 4c. 分辨率倍数设置无效（三重原因）
**文件**：同上
**位置**：`setCoreOption`（约 432-436 行）
**根因**：
1. 运行中改设置只调 `NativeLibrary.SetConfig`（路径被 so 内部 `GetUserPath` 决定，
   与核心读取的 `<userDir>/Config/` 可能不一致，异常被 catch 静默吞掉）
2. `writeCoreIni` 只在 `loadRom` 调用一次，运行中改设置不写盘
3. Dolphin 不热重载 EFBScale（这是 Dolphin 本身限制）

**修复**：在 `setCoreOption` 中对 `Dolphin.ini/*` 和 `GFX.ini/*` 复合键同步直写
INI 文件（`writeIniMerged`），保证下次启动 / 下次 `writeCoreIni` 时已是最新值。

注：Dolphin 不热重载 EFBScale 是上游限制，运行中改分辨率仍需重进游戏生效，
但本次修复保证下次启动一定生效（旧实现下次启动也不生效，因为没写盘）。

---

## 验证清单

修复完成后，建议按以下顺序验证：

### 3DS 黑屏
1. 启动游戏，观察是否还出现 `exitEmulationActivity(result=2)`
2. 若仍退出，看错误对话框的诊断信息（surface 状态、密钥文件存在性）
3. 把 `aes_keys.txt` + `boot9.bin` + `seeddb.bin` 放入 `<filesDir>/azahar/` 后重试

### DC 组合键 + 三指连键
1. 进入 DC 游戏的布局编辑器 → 添加组合键 → 确认能选 X/Y/L/R/方向键
2. 添加一个组合键 → 退出布局编辑器 → 重新进入 → 确认组合键还在（保存成功）
3. 游戏中三指同时触摸（A + AB组合 + B）→ 松开中间指 → 确认 A 和 B 仍按住

### 封面
1. SAF 导入 ROM → 进入库 → 确认封面能下载（不再是占位色块）
2. 文件名带 `(USA)`/`(World)` 的游戏 → 确认能命中
3. 街机游戏 → 确认能尝试拉取（命中率取决于驱动名映射）

### Wii
1. 启动 Wii 游戏 → 按 `−`/`+` 按钮 → 确认有效
2. 触摸游戏区域 → 确认 IR 指针按方向移动（不是错乱）
3. 设置 → 内部分辨率 → 改为 2x → 退出游戏重进 → 确认分辨率变化

---

## 改动文件清单

| 文件 | 改动行数 | 修复 |
|------|---------|------|
| `app/src/main/java/com/nesstation/app/core/engine/AzaharEngine.kt` | ~50行 | 3DS 黑屏 surface 竞态 + 错误诊断 |
| `app/src/main/java/com/nesstation/app/NesApp.kt` | ~50行 | 3DS 系统档案安装扩展 |
| `app/src/main/java/com/nesstation/app/ui/emulator/EmulatorScreen.kt` | ~80行 | DC 组合键 + 三指连键 |
| `app/src/main/java/com/nesstation/app/core/storage/CoverFetcher.kt` | ~60行 | 封面 SAF URI + URL 括号 + 候选名 |
| `app/src/main/java/com/nesstation/app/core/engine/IshirukaEngine.kt` | ~50行 | Wii 按键名 + IR + 分辨率 |
