# NesStation · DraStic（激烈）NDS 双核心集成说明

本次移植把 **DraStic（激烈）** r2.6.0.4a 的 NDS 模拟核心接入 NesStation，
成为 NDS 游戏启动时可选择的第二个核心（与原有 melonDS 并列二选一）。

```
启动 NDS 游戏
      │
      ▼
┌─ NdsCorePickerDialog（核心选择对话框）─┐
│  ○ melonDS   —— 高精度开源核心（默认） │
│  ○ DraStic   —— 高性能核心（激烈）     │
└──────────────┬──────────────────────────┘
               │ 每次启动都重新选择（联机对战固定 melonDS）
               ▼
   EmulatorScreen + 对应引擎（NdsEngine / DraSticEngine）
```

## 一、用户视角

| 功能 | melonDS | DraStic |
| --- | --- | --- |
| 启动选择 | 对话框二选一 | 对话框二选一 |
| 下屏触摸 | ✅ 全布局 | ✅ 全布局（画布渲染路径，与自由布局同链路） |
| 屏幕排列（上下/下上/左右/右左/单屏） | ✅ | ✅ |
| 自由布局（拖动两屏位置/大小） | ✅ | ✅ |
| 叠加滤镜（扫描线/CRT/点阵） | ✅ | ✅（视图层绘制） |
| 放大滤镜（HQ2X/HQ4X/XBR） | ✅ | ❌（核心无此能力） |
| 即时存档（10 槽） | ✅ | ✅（DraStic 原生槽位，同时镜像到 NesStation 槽位文件） |
| 快进 | ✅ | ✅（2..15 倍映射） |
| 软重置 | ✅ | ✅ |
| 声音 | ✅ | ✅（OpenSL 原生输出，满音量；跟随系统音量） |
| 联机对战 | ✅ | ❌（推模型核心，无法帧同步 —— 入口自动固定 melonDS） |
| DSi 模式 / OpenGL 渲染器 | ✅ | ❌（核心自带优化配置） |

**存档位置**：DraStic 的电池存档（.dsv）与即时存档（.dss）保存在
`<内部存储>/Android/data/com.nesstation.app/files/drastic/user/`；
NesStation 槽位 UI 会同时在该游戏存档目录里镜像一份 `.state`
（即真实 `.dss` 的副本），便于查看槽位占用与手动备份。

## 二、ABI 前提（重要）

DraStic 核心只提供 **armeabi-v7a（32 位）** 原生库（用户上传的
r2.6.0.4a APK 即为 32 位专用版）。因此：

- **默认构建**（armeabi-v7a + arm64-v8a + x86_64）在 64 位设备上以
  **64 位进程**运行 → 无法加载 32 位 libdrastic → 选择对话框中
  DraStic 选项自动置灰，并显示原因（不影响选 melonDS 玩）。
- **32 位专用构建**可完整启用 DraStic：

  ```bash
  # 32 位 APK（DraStic 可用；PS2/ARMSX2 等 64 位核心不可用）
  ./gradlew assembleRelease -PabiFilter=armeabi-v7a
  ```

  32 位包在支持 32 位的 64 位设备上同样可运行（较新的纯 64 位设备
  如 Pixel 7+ 不支持 32 位，属设备限制）。

## 三、技术架构（移植要点）

### 1. JNI 契约（`app/src/main/java/com/dsemu/drastic/`）

DraStic 的原生库通过**符号名 / 反射字符串**绑定 Java 类，本移植用
Kotlin 重写了三个契约类，包名/类名/方法签名/字段名与原版**严格一致**：

| 文件 | 作用 |
| --- | --- |
| `DraSticJNI.kt` | 72 个 native 方法的声明（生命周期/输入/视频/存档/作弊/配置） |
| `filesystem/NativePathHandle.kt` | 原生读字段 `filePath` / `fileFd` / `fileName` |
| `filesystem/DraSticPathCache.kt` | 原生回调 `open/remove/rename` —— 所有文件 IO 的桥 |

其中 `DraSticPathCache` 是**简化重实现**（替换原版 700+ 行 SAF 抽象）：
把 DraStic 的虚拟路径解析为真实文件（`"DraStic/"` → 系统目录、
`"User/"` → 用户目录、绝对路径直读），并带一个**写路径观察器**用于
即时存档后精确定位原生写出的 `.dss` 文件。

契约的关键语义均经 **capstone 反汇编 + jadx 反编译双重验证**：
- `updateInput(mask1, packedXY, mask2)`：mask 位布局
  bit0-3=方向、bit4-11=A/B/X/Y/L/R/Start/Select、bit31=触摸标志；
  `packedXY = x<<16 | y`（下屏像素 0..255 / 0..191）
- `startGame(romPath, slot, packedConfig, 0, false, -1)` 后模拟线程与
  OpenSL 音频均由**原生线程**接管
- 帧拉取：`waitScreen()`（阻塞等待）+ `getScreenBuffers(top, bottom)`
  （ARGB_8888 256×192）

### 2. 引擎层（`core/engine/`）

- `NdsCoreEngine.kt` —— 新增双引擎共享接口（触摸 / 双屏渲染 API），
  `NdsEngine`（melonDS）与 `DraSticEngine` 共同实现，UI 层不再强转
  具体引擎类
- `DraSticEngine.kt` —— 推模型引擎：
  - 渲染线程 `waitScreen → getScreenBuffers → 合成 256×384 帧缓冲`
  - 按键位布局换算（libretro 12 键 → DraStic 位布局）
  - 暂停/快进/重置/存档槽/截图 全生命周期管理
  - 退出时序：`pauseSystem → quitSystem → signalScreen 唤醒渲染线程 →
    join → releaseSystem`（对齐原版，避免线程阻塞在已释放的 condvar）

### 3. UI 层（`ui/emulator/`）

- `NdsCorePickerDialog.kt` —— 启动时核心选择对话框（DraStic 不可用时
  置灰 + 原因 + 构建提示）
- `EmulatorScreen.kt`：
  - 引擎创建前插入选择门（联机对战跳过、固定 melonDS）
  - 所有 NDS 触摸路径改走 `NdsCoreEngine` 接口
  - `GameSurfaceView` 为 DraStic 增加**画布渲染分支**（NdsDualScreenView，
    任何缩放模式都可用；标准模式按屏幕布局派生两屏矩形 + 布局原生宽高比）
- `NdsDualScreenView.kt` —— 引擎类型改为共享接口；DraStic 合成帧恒为
  上屏在前，切片布局固定 Top/Bottom、屏幕位置由目标矩形表达

### 4. 原生库

```
app/src/main/jniLibs/armeabi-v7a/
├── libdrastic.so         # 主核心（NEON）
├── libdrastic_compat.so  # Tegra2 兼容核心
└── libdrastic_cpu.so     # CPU 探测（getCpuType 决定加载上面哪个）
```

### 5. 混淆保护

`app/proguard-rules.pro` 新增 `-keep class com.dsemu.drastic.** { *; }` ——
release 构建的 R8 看不到原生层对这些类的反射引用，不 keep 会在运行时
抛 `NoSuchMethodError` / `UnsatisfiedLinkError`。

## 四、修改文件清单

```
新增 (7):
  app/src/main/java/com/dsemu/drastic/DraSticJNI.kt
  app/src/main/java/com/dsemu/drastic/filesystem/NativePathHandle.kt
  app/src/main/java/com/dsemu/drastic/filesystem/DraSticPathCache.kt
  app/src/main/java/com/nesstation/app/core/engine/NdsCoreEngine.kt
  app/src/main/java/com/nesstation/app/core/engine/DraSticEngine.kt
  app/src/main/java/com/nesstation/app/ui/emulator/NdsCorePickerDialog.kt
  README_DRASTIC.md

修改 (6):
  app/src/main/java/com/nesstation/app/ui/emulator/EmulatorScreen.kt
    （核心选择门 + 触摸路径接口化 + DraStic 画布渲染分支 + 设置面板文案）
  app/src/main/java/com/nesstation/app/ui/emulator/NdsDualScreenView.kt
    （engine 类型 → NdsCoreEngine；切片注释）
  app/src/main/java/com/nesstation/app/core/engine/NdsEngine.kt
    （实现 NdsCoreEngine 接口，5 个方法加 override）
  app/src/main/java/com/nesstation/app/core/engine/EmulatorEngine.kt
    （工厂文档）
  app/src/main/java/com/nesstation/app/NesApp.kt
    （启动注册 DraSticEngine：appContext + 可用性预探测）
  app/proguard-rules.pro
    （keep com.dsemu.drastic.**）

新增原生库 (3):
  app/src/main/jniLibs/armeabi-v7a/{libdrastic,libdrastic_compat,libdrastic_cpu}.so
```

## 五、构建

```bash
# 常规构建（64 位设备上 DraStic 选项置灰）
./gradlew assembleRelease

# 32 位构建（DraStic 完整可用）
./gradlew assembleRelease -PabiFilter=armeabi-v7a
```

无需额外配置 —— 原生库已置于 `jniLibs`，混淆规则已就位，
NesApp 启动时自动注册探测。

## 六、法律提示

DraStic 为 Exophase 的商用闭源模拟器。本集成将其随用户提供的 APK
提取的原生库用于个人使用，**不得将含这些库的构建公开分发**。
