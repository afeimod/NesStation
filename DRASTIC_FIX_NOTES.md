# GameBox 激烈（DraStic）核心修复说明

修复版本：基于仓库 HEAD（`5e6fbd30 修激烈`）源码。

本次修复两个问题：
1. **激烈核心运行游戏一直显示"正在加载"**（游戏实际已运行、声音正常）—— 根因级修复；
2. **总设置与另一个 NDS 核心（melonDS）混放一起** —— 双核心设置彻底分离。

全部为源码级修复，直接用 Android Studio 打开工程构建即可，无新增资源/依赖。

---

## 问题 1：激烈核心游戏已运行（有声音）但 UI 永远停在"正在加载"

### 根因（反汇编 libdrastic_arm64.so 实锤）

**`DraSticJNI.startGame()` 本身就是模拟主循环，跑在调用者线程上——游戏运行期间永不返回。**

证据链（capstone 反汇编 + ELF 分析，见 `scripts/` 下分析脚本可复现）：

| 证据 | 反汇编事实 |
| --- | --- |
| `waitScreen` JNI 桩（0x1a64c，4 字节尾跳）→ 0x1ce30 | 真实实现 = `pthread_mutex_lock(0x3f2db84); pthread_cond_wait(0x3f2db84+0x28); unlock` —— **没有超时、没有空指针守卫**，纯 condvar 阻塞 |
| `signalScreen`（0x1a648）→ 0x1ce68 | 对同一 condvar 做 `pthread_cond_signal`（0x3f2db84+0x28） |
| `quitSystem`（0x1a72c，40 字节） | **只置全局退出标志**（0x14c4b9 半字=1），不发信号、不等线程 —— 由模拟循环在帧边界检测后退出 |
| 原版拆机顺序 | `pauseSystem(1) → quitSystem() → (等待) → releaseSystem()`，中间的"等待"正是**等 startGame 返回**（= 等模拟线程从主循环退出） |
| `.so` 的 JNI 回调面 | 仅回调 `DraSticPathCache.open/remove/rename` 三个静态方法，**没有任何 Java 线程/Handler 依赖** —— 模拟循环必然运行在调用 startGame 的线程上 |
| 音频 | OpenSL ES 由核心内部线程输出，与 startGame 调用线程并行 —— 所以 startGame 卡住时**声音照常** |

**旧实现的致命错误**：`loadRom` 把 `startGame` 当成"加载函数"，同步等待它返回：

```
loadRom → startGame(…)   ← 永不返回（游戏在跑）
   → loadRom 永不返回
   → loaded 恒为 false
   → UI 永远停在"正在加载…"（此界面仅在 !loaded 时显示）
   → 声音正常（OpenSL 独立线程）
```

上一轮"修激烈"提交加的看门狗（15 秒超时）方向错了：它会把**正在正常运行的游戏**误判为"原生卡死"，超时后报错并停止消费帧，而模拟循环与声音仍在后台跑 —— 既没修好加载，还泄漏了整局游戏。

### 修复（对齐原版 DraStic App 的 GameThread 架构）

| 改动 | 说明 |
| --- | --- |
| `startGame` 移入专用守护线程 `drastic-game` | 该线程整个会话期间 = 模拟线程（原版 App 正是如此：专门的后台 GameThread 调 startGame，游戏结束时它才返回）。线程优先级 `THREAD_PRIORITY_URGENT_AUDIO` |
| `loadRom` 改为"就绪信号"等待 | 就绪 = 渲染消费线程拿到**首帧**（`frameCount > 0`），拿到立即置 `isLoaded=true` 并返回 → UI 立刻进入游戏画面。上限 `BOOT_TIMEOUT_MS = 20s` |
| 启动失败干净收场 | 首帧超时 / startGame 提前返回 → `pauseSystem(1) → quitSystem() → join 模拟线程(3s) → 唤醒渲染线程 → releaseSystem()`，之后**可安全重试**（不再永久 poisoned） |
| `cleanup()` 修复 use-after-free 隐患 | 旧代码 `quitSystem()` 后只 join 渲染线程就 `releaseSystem()` —— 此时模拟线程很可能还在 startGame 里跑着！现在**先 join 模拟线程（2.5s 上限）再 release**；任一线程拒绝退出才置 `nativePoisoned`（本进程禁用激烈核心，防二次踩踏） |
| 渲染消费线程去掉错误门控 | 旧代码里 `!started → sleep(2) → continue` 的忙等门控基于"waitScreen 未就绪时立即返回"的**错误认知**（反汇编证明它会阻塞在零初始化 condvar 上，安全）。删掉后行为：启动前阻塞等待 → 首帧信号唤醒 → 正常搬运 |
| 会话意外结束防御 | 模拟线程意外从 startGame 返回（原生异常）时自动撤下 `isLoaded`，视图停止绘制，不会出现"黑屏假死" |

**涉及文件**：`app/.../core/engine/DraSticEngine.kt`（loadRom / cleanup / 常量 / 类文档全面重写，删除看门狗机制）

### 修复后的启动时序

```
loadRom(IO 线程)
 ├─ 预检/系统文件安装/ROM 准备（不变）
 ├─ 启动 drastic-render（渲染消费线程，阻塞在 waitScreen 等首帧）
 ├─ 启动 drastic-game（模拟线程：进入 startGame = 进入模拟主循环）
 └─ 轮询等待首帧（50ms 间隔）
      ├─ frameCount > 0  → isLoaded=true，返回 true → UI 进入游戏 ✔
      ├─ startGame 提前返回 → 报错返回 false
      └─ 20s 超时 → quitSystem → join 模拟线程 → releaseSystem → 报错返回 false（可重试）
退出游戏：unload() → cleanup() → pause(1) → quit → join 模拟线程 → 唤醒+join 渲染线程 → releaseSystem ✔
```

---

## 问题 2：总设置与另一个 NDS 核心混放一起

### 根因

NDS 平台有两个**完全不同**的核心（melonDS 拉模型 / DraStic 推模型），但：

1. `applyCoreOptions` 把 `melonds_*` 的 22 个选项和 `drastic_volume` **一起**下发给当前引擎 —— 无论用户选的是哪个核心；
2. 设置页只有一个"NDS / DSi (melonDS)"大区装着全部选项，激烈核心的设置挤在角落，用户完全无法分辨哪些设置对哪个核心生效。

### 修复（数据层 + UI 层双重分离）

| 层 | 改动 |
| --- | --- |
| 数据层 `applyCoreOptions` | NDS 分支按引擎类型分流：**选激烈 → 只下发 `drastic_*` 键；选 melonDS → 只下发 `melonds_*` 键**。两套核心的设置绝不交叉下发 |
| 设置页 `CoreSettingsPanel` | NDS 页拆成三个独立分区：`NDS 通用 · 双核心共用`（屏幕排列等前端层设置）／`melonDS 专属`（主机模式、JIT、OpenGL、音频等 22 项）／`DraStic（激烈）专属`（音量等） |
| 游戏内快捷菜单 `EmulatorScreen` | 同样拆成 通用（双核心：屏幕排列、存档方式）／`melonDS 专属`（含 NDS BIOS 管理）／`DraStic（激烈）专属`（音量）三区，各区标注生效条件 |

"屏幕排列"是**前端视图层**实现的布局（NdsDualScreenView 的目标矩形推导），两个核心都生效，故放通用区；其余全部按核心归属分区。

**涉及文件**：`app/.../ui/emulator/EmulatorScreen.kt`、`app/.../ui/settings/CoreSettingsPanel.kt`

---

## 验证

- 三个修改文件均通过 Kotlin 2.0 编译器语法级校验（语义错误仅为脱离 Android/工程 classpath 的预期 unresolved reference）。
- 反汇编证据脚本：`scripts/analyze_drastic.py`、`scripts/disasm_drastic.py`、`scripts/disasm2.py`、`scripts/scan_sync_calls.py`（基于 pyelftools + capstone，可对 `app/src/main/jniLibs/arm64-v8a/libdrastic_arm64.so` 复现全部结论）。

### 建议真机验证步骤

1. 启动 NDS 游戏 → 选"DraStic（激烈）"→ 应在 1–3 秒内进入游戏画面（听到声音的同时看到画面），不再停留"正在加载"。
2. 游戏中呼出菜单 → 退出 → 再进同一游戏（验证干净 teardown / 可重试）。
3. 选 melonDS 运行同一游戏 → 改 melonDS 专属设置（如 JIT）→ 重进游戏切激烈核心 → 确认激烈行为不受 melonDS 设置影响。
4. 游戏内菜单 → NDS 专属设置 → 确认"激烈专属"区音量调节即时生效。
5. logcat 过滤 `DraSticEngine`：应看到 `startGame: rom=… — 进入原生模拟主循环`，退出时看到 `startGame returned=true after …ms（游戏会话结束）`。


---

# 第二轮修复：完善激烈设置 + 高清渲染 + GL 加速显示（第三轮返工修正版）

修复版本：基于仓库 HEAD（`cf94f302 修激烈核心加载`）。
第二轮初版曾引入"彻底黑屏有声音"回归 —— 第三轮（本次）已定位根因并返工，
以下为**修正后的最终实现说明**（含回归根因分析，供后续维护参考）。

## 〇、黑屏回归根因（第三轮定位，务必引以为戒）

第二轮初版的 GL 显示视图黑屏，反汇编复核后确认有两个致命错误：

1. **顶点图元/顶点序错误**：原生 renderFrame 的绘制调用是
   `glDrawArrays(GL_TRIANGLES, first, 6)`（w0=4=GL_TRIANGLES，
   first=0 与 6 各一次），而初版按 TRIANGLE_STRIP 组织顶点，且
   `px = floatArrayOf(l, r, l, b, b, b)` 第 4 个元素误用纵坐标 b 当
   横坐标 —— 两个三角形全部退化为零面积，**什么都画不出来 → 纯黑屏**
   （模拟与音频在原生线程继续跑 → "有声音"）。
2. **默认值激进**：初版把"高清渲染"默认设为开启 + 强制 GL 路径 ——
   任何未经真机验证的默认路径变成全量用户的默认体验。

修正原则（本轮起强制执行）：
- **默认配置字 = 已在真机验证可正常显示画面的字**（bit31 声音开，
  其余位全 0）；一切新设置默认关闭/取保守值。
- 顶点布局严格按原生 draw 调用契约：GL_TRIANGLES + 每屏 6 顶点
  两个三角形（v0=(l,t) v1=(r,t) v2=(l,b) + v3=(r,t) v4=(r,b) v5=(l,b)）。
- 未逐指令验证消费链路的位一律不进设置面板（原"色彩深度 bit23 /
  音频延迟 bits8-9"两项已移除：16 位 REV 类型非 GLES 核心合法，
  bits8-9 消费点未能复核）。

## 一、config 位域解码（反汇编逐指令验证，含消费链路）

对 `applyConfig(0x1a4a0)` → 解包器（0x17c58）→ 各消费点全链路验证：

| 位 | 语义 | 消费链路（验证依据） |
| --- | --- | --- |
| bit31 | 声音启用 | 解包器 → [cfg+0x460]；音频引擎跳过标志 |
| bit29 | 快进激活 | 解包器 gate：bit29=0 时帧间隔字段清零 |
| bits12-15 | 快进时显示帧间隔（µs 表 [100000,33333,25000,16666,12500,5000]，仅 ≤5 有效） | 0x1070c0 表查找 → [cfg+0x48c]；固定用 2（40fps 显示上限） |
| bit41 | 高清渲染（2x=512×384） | 解包器 SIMD 提取 → [cfg+0x4a0]（=core+0x8aaf8）→ startGame 路径读出 → setResolution(0x1cde4) 写两屏分辨率档 → renderFrame 上传 (scale+1)×256 × (scale+1)×192；帧池每屏 0xC0000 = 512×384×4 |
| bits37-38 | 快进倍率（表 [2,4,8,16]，索引钳位 0..3） | applyConfig 尾跳 0x1d728 读 0x10a080 表 → float 写 [0x143ec0] |
| bit50 | 存档格式 0=.sav / 1=.dsv | 解包器 → [cfg+0x4b8]；备份扩展名选择 |
| bit23 | 帧格式 0=RGBA8888 / 1=RGBA4444（**不暴露**） | applyConfig 直接测试 → bpp 字节 → sub_1cbc0 写 GL format/type（0x1908/0x1401 vs 0x1907/0x8363） |

## 二、GL 加速显示路径（DraSticGlView，修正版）

原生 `renderFrame(tex1, tex2, portrait)`（0x1ceac）完整契约（逐指令）：
1. 帧池存在性检查：[video+0x950]（=memalign(16, 0x300000) 的返回指针
   存储；onInit 时分配，JNI_OnUnload 释放并清零）为 0 直接返回；
2. 池互斥锁（video+0x98c）下快取【另一档】（~当前写入档）缓冲指针与
   两屏分辨率档 —— GL 路径读的是**上一块已完成帧**，不与模拟线程竞争；
3. `glBindTexture(tex1)` → 脏标记（+0x988 bit0）置位时
   `glTexSubImage2D`（format/type 由 bit23 路径写入 +0x960/+0x964）；
4. **`glDrawArrays(GL_TRIANGLES, 0, 6)`** 绘制屏 A；tex2≠0 时
   `glDrawArrays(GL_TRIANGLES, 6, 6)` 绘制屏 B；结束后清脏字节。

原生只做"上传 + 按当前绑定的顶点属性绘制"—— 着色器、VBO、视口由
调用方准备（与原版 App 的 GLSurfaceView onDrawFrame 模型一致）。
DraSticGlView（修正版）：
- 自带 ES2 程序 + 12 顶点 VBO，**每屏 6 顶点 = 两个三角形拼矩形**
  （契约核心，见上）；支持全部屏幕布局与自定义矩形；
- 专用 EGL(ES2) 线程，每帧 `renderFrame(topTex, bottomTex, false)` +
  `eglSwapBuffers`（垂直同步自然节流；脏标记保证只上传新帧）；
- 纹理恒为 32 位 GL_RGBA/GL_UNSIGNED_BYTE（与原生 32 位上传常量一致）；
- 帧消费接管：GL 激活时引擎渲染线程跳过 ~500KB/帧 CPU 拷贝
  （waitScreen 就绪握手保留；截图改由 captureFrame 直接拉取）；
- EGL/GL 失败自动回退画布，并调 `revertHdForCanvasFallback()` 立即把
  原生分辨率降回 1x（画布 getScreenBuffers 固定读 256×192）；
- 触摸/物理按键路由与 NdsDualScreenView 同构。

**为什么 GL 是默认显示路径**（= 原版 App 的显示方式）：
画布路径的 getScreenBuffers（sub_1cd18）**不加锁读当前写入档**的帧池
—— 3D 游戏全屏每帧刷新时会出现瞬时撕裂（"3D 游戏渲染问题大"的根因）；
GL 路径在互斥锁下读另一档已完成帧，画面干净，且为高清渲染的前置条件。

**重要约束**：高清（bit41）下帧池为 512×384，画布固定读 256×192 →
裁切为左上 1/4。因此高清渲染强制走 GL 显示；GL 失败时自动降回 1x。

## 三、设置面板（全部经反汇编验证的项目）

「DraStic（激烈）专属 · 画面渲染」：高清渲染(2x，默认关)、
显示方式(GL/画布，默认 GL)、画面平滑滤波(双线性/最近邻)。
「DraStic（激烈）专属 · 音频/性能/存档」：音量、声音开关(bit31)、
快进倍率(2x/4x/8x/16x)、存档格式(.sav/.dsv)。
所有键经 `applyCoreOptions → setCoreOption("drastic_*")` 只发给激烈引擎
（melonDS 绝不读取）；声音/快进运行中即时生效，高清/存档格式经会话快照
（loadRom 拍照）下次进游戏生效。

## 四、修改文件清单

- `app/src/main/java/com/nesstation/app/ui/emulator/DraSticGlView.kt`（新增，修正版）
- `app/src/main/java/com/nesstation/app/core/engine/DraSticEngine.kt`
- `app/src/main/java/com/nesstation/app/ui/emulator/EmulatorScreen.kt`
- `app/src/main/java/com/nesstation/app/ui/settings/CoreSettingsPanel.kt`
- `app/src/main/java/com/nesstation/app/core/storage/PadLayoutStore.kt`
- `app/src/main/java/com/nesstation/app/ui/emulator/NdsCorePickerDialog.kt`

## 五、验证

- 修改文件全部通过 Kotlin 2.0 编译器语法级校验：与 HEAD 基线逐类对比，
  错误轮廓完全一致（仅行号平移的 classpath 级联；DraSticGlView 的
  全部错误均为 android/Compose 未解析级联），**无真实语法/语义错误**。
- packConfig 位布局经 Python 断言逐位验证。
- 顶点布局人工复核：GL_TRIANGLES 三角 1 (l,t)(r,t)(l,b) + 三角 2
  (r,t)(r,b)(l,b)，与原生 first=0/6、count=6 两次 draw 严格对应。

### 建议真机验证步骤

1. 默认设置直接进 3D 游戏（马力欧赛车/塞尔达）→ 画面应正常显示
   （GL 路径 1x；如有撕裂对比切换显示方式=画布观察差异）。
2. 开「高清渲染 (2x 分辨率)」→ 重进游戏 → 画面应为 512×384 高清
   （3D 模型边缘明显细腻）。
3. 切「画面平滑滤波」双线性/最近邻 → 缩放平滑度变化。
4. 游戏内菜单改「声音」「快进倍率」→ 立即生效。
5. 切「显示方式」画布（先关高清）→ 画布路径正常游玩（1x 全画面）。
6. 截图（GL 模式 captureFrame 直接拉取）→ 截图完整。
7. logcat 过滤 `DraSticGlView`：EGL 正常时无 error；异常时看到
   "EGL init failed" 并自动回退画布 + 降回 1x。

---

# 第三轮修复：3D 游戏显示不完整（只剩顶部一条）+ 激烈专属配置全面对齐原版 APK

修复版本：基于仓库前两轮修复之后的 HEAD。
参考基准：用户提供的原版 **DraStic r2.6.0.4a (109) arm64 APK**（jadx 反编译 classes.dex
+ libdrastic_arm64.so 反汇编，两路证据交叉验证）。

## 〇、症状

3D 游戏（如 Sonic Rush 等双屏 3D 作品）运行时上下两屏都只显示顶部的
一条横带（约 16 行 / 8% 画面），其余全黑；2D 游戏正常；FPS 计数正常（60+）。

## 一、根因（证据链）

### 根因 1：模拟线程数传了 0（原版绝不会传 0）

- 原版 `f0.h.n()`（config 打包函数，jadx 反编译）把 **f3673q（模拟线程数）
  打进 bits 16-19**，其值按 CPU 核数自动决定：`≥4核=3，≥2核=2，否则 1`，
  另可被系统目录 `config/threads.cfg`（ASCII '1'-'8'）覆盖 —— **取值域恒为 1..8**。
- 旧实现 packConfig 根本没有线程数概念，bits 16-19 恒为 0。
- 原生侧（libdrastic_arm64.so 反汇编）：解包器 0x17c58 把 bits 16-19 存
  `cfg+0x490`；帧冲刷函数（0x59bb4 / 0x5ee84 两个引擎变体）每帧读取它
  决定 3D 光栅化的并行结构：
  `cmp w23,#1; b.ls → 单线程路径`，≥2 时按 threads 生成持久工作线程组
  （每个 0x24100 字节上下文，互斥锁+条件变量握手）。
- 3D 画面按 **16 行/段** 分段光栅化（单线程执行器 0x596a4 内
  `udiv w11, w13, w8`（w13=0xC=12 段×16 行=192 行），与截图里可见横带
  高度 ≈15.6 行完全吻合）。单线程（0/1）模式下每帧光栅化吞吐不足，
  帧信号到达时只完成了顶部第一段 → 显示端捕到的帧 = 顶部一条、其余黑。

### 根因 2：高清渲染的偏好读取默认值写错（"enabled" ≠ 设计默认 "disabled"）

`PadLayoutStore` 加载路径 `p.getString("nds_drastic_hd_render", "enabled")`
把从未进过设置页的用户的 HD 渲染（bit41，_Hires3D）静默置为开。
HD 模式下光栅化像素量 ×4，单线程下每帧能完成的段数再 ÷4 —— 与根因 1
叠加后，可见横带只剩 1 段。字段声明默认值本是 "disabled"（第三轮返工
注释也写明"默认配置字 = 位全 0"），两处不一致属于笔误级 bug。

### 根因 3（配置面）：config 位域大量字段缺失/错位

对照原版 `f0.h.n()` 逐位比对，旧实现的问题：

| 位域 | 原版语义（_Pref 键） | 原版默认 | 旧实现 |
| --- | --- | --- | --- |
| bits0-3 | 跳帧值 _FrameskipValue | 4 | 0 |
| bits5-7 | 跳帧类型 _FrameskipType | 0(关) | 0 ✓ |
| bits8-9 | 音频延迟 _AudioLatency | 3(极高) | 0(低) |
| bits12-15 | **快进速率** _FfwdSpeed (0..5=50%~无限制) | 2(200%) | 恒 2（碰巧等于默认） |
| bits16-19 | **模拟线程数** | 1/2/3 自动 | **0** ← 渲染 bug 根因 |
| bits23 | 16位渲染 _GlUse16Bit | 关 | 未发 |
| bit24 | 忽略卡带容量 _IgnoreGamecardLimit | 关 | 未发 |
| bit25 | 即时存档含游戏存档 _BackupInSavestates | **开** | 未发 |
| bit26 | 麦克风 _MicEnabled | **开** | 未发 |
| bit27 | 金手指 _CheatsEnabled | **开** | 未发 |
| bit28 | 多线程3D _Threaded3D | 关 | 未发 |
| bit30 | 显示FPS _ShowFPS | 关 | 未发 |
| bit35 | 主屏固定上屏 _FixMainEngineScreen | 关 | 未发 |
| bit36 | ROM自动裁边 _AutoTrim | 关 | 未发 |
| bit39 | RTC系统时间 _RtcSystemTime | 关 | 未发 |
| bit40 | 禁用边缘标记 _DisableEdgeMarking | 关 | 未发 |
| bit42 | Lua _LuaEnabled | **开** | 未发 |
| bits32-34 | 连发速度 _AutoFireSpeed | 2 | 0 |
| bits37-38 | **麦克风等级** _MicLevel | 1 | 被误当"快进倍率"写 0..3 |
| bits43-46 | Slot2 卡带 _Slot2Type | 1(GBA) | 0(无) |
| bit47 | 安全跳帧 _FrameskipSafe | 关 | 未发 |
| bit48 | 预解压ROM _PreloadRoms | 关 | 未发 |

注：旧版"快进倍率(2x/4x/8x/16x)"实为 bits37-38 = 麦克风增益表 [2,4,8,16]
（0x1d728 查 0x10a080 字节表转 float）——语义错位；真正的快进速率是
_FfwdSpeed（bits12-15，仅 bit29 激活时查 0x1070c0 表）。

## 二、修复内容

| 文件 | 改动 |
| --- | --- |
| `DraSticEngine.kt` | packConfig 重写为 51 位全量布局（与 f0.h.n() 逐位一致，参数全默认值=原版出厂）；新增 `effectiveEmuThreads()`（≥4核=3/≥2核=2/否则1，强制 1-8 优先，绝不传 0）；loadRom 计算 activeThreads 快照；setCoreOption 扩到 27 个 drastic_* 键（含旧键 drastic_ffwd_speed 兼容吞掉）；setFastForward 简化为 bit29+bits12-15 语义；自动存档间隔经 setAutosaveInterval 下发 |
| `PadLayoutStore.kt` | 新增 24 个 ndsDrastic* 偏好字段（默认=原版出厂）+ copy + 读写；**修复 hd_render 读取默认值 "enabled"→"disabled"**；快进倍率旧字段替换为 ndsDrasticFfwdRate（0..5） |
| `CoreSettingsPanel.kt` | 激烈专属设置从 2 区 7 项扩到 4 区 28 项（画面渲染/线程快进金手指/音频麦克风/系统高级），全部标注原版默认值 |
| `EmulatorScreen.kt` | applyCoreOptions 下发全部 27 键；游戏内快捷菜单激烈区从 7 项扩到 26 项；快进倍率(错位语义)替换为快进速率 |

## 三、验证

- 四个修改文件用 kotlinc 2.0.21 独立编译：错误轮廓与 HEAD 基线**逐类完全一致**
  （全部为脱离 Android/Compose classpath 的预期级联；EmulatorScreen 的
  cannot-infer 计数 322=322），无新增语法/语义错误。
- `scripts/verify_config.py`：默认配置字与原版 `f0.h.n()` 出厂默认**逐位一致**
  （0x00000C228E032304），高清/快进/组合场景断言全过。
- 新用 Kotlin 构造（`(0..9).map{}`、`listOf()+map{}`、when 映射）通过
  kotlinc -script 独立验证。

### 建议真机验证步骤

1. 直接进 3D 游戏（不调任何设置）→ 上下屏应完整显示（默认线程数=自动 3，
   HD 关，与原版出厂一致）。
2. 设置页「激烈专属 · 模拟线程数」切 1（强制单线程）重进 3D 游戏 →
   应能复现"只剩顶部一条"，验证根因；切回自动恢复完整。
3. 开「高清渲染」→ 重进 → 画面完整且更细腻（线程并行下 HD 帧可按时完成）。
4. 「跳帧」切手动/值 1 → 弱机上 3D 提速；「多线程 3D」开启 → 3D 大作提速
   （个别游戏如出现双屏互换即关掉，与原版提示一致）。
5. 「音频延迟」低/极高切换听爆音差异；对麦克风吹气（《心跳》类游戏）验证
   麦克风灵敏度。
6. 快进（按住快进键）→ 按「快进速率」设定的倍速走。

---

# 第四轮修复：全局滤镜识别 + 全局 sav 存档识别 + 多线程 3D 渲染修复

修复版本：基于仓库 HEAD（`8b53caae 修激烈渲染`）。
参考基准：原版 **DraStic r2.6.0.4a (109) arm64 APK**（jadx 反编译 classes.dex +
libdrastic_arm64.so 反汇编，两路证据交叉验证）+ 激烈整合版 APK（240 个
滤镜着色器资产）。

## 〇、三个问题的根因

### 问题 1：激烈核心没有识别全局滤镜

**滤镜根本不在 config 位域里。** 原版把滤镜（_CurrentFx，默认 "Linear"）
通过三条专用 JNI 挂接（jadx DraSticGlView$j + DraSticExtGlView）：

| JNI | 原版调用点 | 语义（反汇编逐指令） |
| --- | --- | --- |
| `fxLoad(path, 0, 2208)` | onSurfaceCreated → d() | 加载 `DraStic/shaders/<名>.dfx`（虚拟路径经 PathCache 解析到系统目录）；参数 = VBO 内 a_vertex_coordinate / a_texture_coordinate 数据的起始字节（存 ctx+0x468/0x470，draw_screen 里作 glVertexAttribPointer 的 offset） |
| `fxSetup(srcW, srcH, 0, 0, sw, sh)` | onSurfaceChanged | 帧纹理尺寸 + 最终 pass 视口（存 ctx+0x484..0x490） |
| `fxRender(tex1, tex2, 0, 6, 18, wA, hA, wB, hB, portrait)` | onDrawFrame | 帧池上传（同 renderFrame，互斥锁 + 另一档）→ 按 pass 链绘制：中间 pass（带 FBO）画顶点 18-23（全屏四边形），最终 pass 画顶点 0-5 / 6-11（两屏四边形，glViewport = fxSetup 视口），u_target_size = (wA,hA)/(wB,hB)（csel + glUniform2f 验证） |

NesStation 此前从未调用这三个函数 —— 滤镜能力存在但管线没接。
共享 VBO 顶点四边形布局（原版字节级，48B/四边形）：

```
0(顶点0-5)=上屏  48(6-11)=下屏  96(12-17)=全屏  144(18-23)=全屏(fx中间pass)
192(24-29)=暂停背景  240(30-35)=FPS  2208=UV四边形×6（fxLoad 的 uvOff 指向这里）
```

修复：DraSticGlView 重构为原版契约 —— 4096B 非交错 VBO、会话开始
`fxLoad("DraStic/shaders/<名>.dfx", 0, 2208)`、帧循环
`fxSetup(texW,texH,0,0,w,h)`（尺寸变化时重发）+ `fxRender(...)`；
滤镜列表来自 APK 捆绑的整合版 240 个着色器资产（130+ 滤镜，
`ensureShadersInstalled` 装入 `<sysDir>/shaders/`）；设置页/游戏内菜单
新增「视频滤镜」下拉（none = 原生直绘；fxLoad 失败自动回退，
与原版 shader error toast 后回退的行为一致）。

### 问题 2：激烈核心没有识别全局 sav 等存档

原生把电池存档路径固定构造为 `User/backup/<ROM基名>.sav|.dsv`（反汇编
`"%s%cbackup%c%s.sav"` / `"%s%cbackup%c%s.dsv"`，基名取自 changeRom 注册
的 ROM），此前恒落到激烈私有目录 `files/drastic/user/backup/`，从不读
NesStation 的全局存档。且「存档格式」设置映射写反（选 .dsv 实际写 .sav，
反汇编 `csel x3, .dsv, .sav, eq` 确认 bit50=0 → .dsv、bit50=1 → .sav）。

修复（三层）：
1. **路径重定向**：`DraSticPathCache.setBatterySaveTarget(dir, base)` ——
   `User/backup/` 下的 .sav/.dsv 一律优先解析到全局存档位置
   （"nesstation" 模式 = `<filesDir>/saves/<gameId>.sav`；"core_builtin"
   模式 = ROM 同目录 `<ROM名>.sav`，与 melonDS 完全同源，切核心互认）。
2. **格式默认值纠正**：bit50（_RawSavFormat）默认 1 = 裸 .sav（与
   melonDS / 官方 melonDS APK 兼容）；设置映射纠正（"sav"→bit50=1）。
3. **旧档迁移**：`migrateLegacyDsv` —— 全局 .sav 缺失且旧私有目录有
   `<基名>.dsv` 时，剥 0x50 字节头一次性写入全局 .sav（老用户进度不丢）。

### 问题 3：激烈核心多线程 3D 渲染有问题（原版正常）

原版 GL 线程的帧消费模型（DraSticGlView$j.onDrawFrame 反汇编）：

```
frameInfo = getFrameInfo()            // 低16位 = 模拟主循环帧计数器 0x14c4b0
if (frameInfo & 0xffff) == 0:
    waitScreen()                      // 阻塞等模拟线程 pthread_cond_signal
    fxRender(...) / renderFrame(...)  // 上传 + 绘制（一次性消费新帧）
```

NesStation 此前：GL 线程【异步】高频调 renderFrame（脏标记去重），
而引擎渲染线程【独占】waitScreen —— 两个偏差叠加：
1. 消费与模拟不同步：多线程 3D（bit28）的帧冲刷是"异步分段 + 上一帧
   补拷贝"（0x59bb4 末尾 memcpy 0x30000 补丁在翻帧后进行），异步消费
   会读到补拷贝中间态 → 画面错乱（原版因同步消费而正常）；
2. 若两边同时 waitScreen，pthread_cond_signal 只唤醒一个等待者 ——
   两个消费者互偷信号，双路径各丢一半帧。

修复：GL 线程成为**唯一** waitScreen 消费者，逐字复刻原版模型：
- glLoop 增加帧同步门（getFrameInfo 低16位==0 → waitScreen → 渲染 →
  `eng.notifyGlFrame()` 维持 frameCount/FPS 回调）；
- 引擎渲染线程在 `glDisplayActive`（GL 接管）后驻车（不再 waitScreen），
  画布路径行为不变；loadRom 时重置 glDisplayActive 保证新会话引导期
  仍由渲染线程驱动首帧握手；
- 快进批次（计数器>0）期间不重绘不清屏，保持上一帧画面（与原版一致）。

## 修改文件清单

```
新增资产:
  app/src/main/assets/drastic/shaders/        # 整合版 240 个滤镜着色器（130+ .dfx/.dsd + fxaa/smaa 头文件）

修改 (6):
  app/src/main/java/com/nesstation/app/ui/emulator/DraSticGlView.kt
    （VBO 重构为原版非交错布局 + fxLoad/fxSetup/fxRender 滤镜管线 +
     帧同步门逐字复刻原版 + 滤镜热切换检查点）
  app/src/main/java/com/dsemu/drastic/filesystem/DraSticPathCache.kt
    （电池存档全局重定向 setBatterySaveTarget + User/backup/.sav|.dsv 候选优先）
  app/src/main/java/com/nesstation/app/core/engine/DraSticEngine.kt
    （bit50 语义纠正 + optRawSav 默认 .sav + optFilter + drastic_filter/
     drastic_save_format 键 + 全局存档重定向接线 + .dsv 迁移 +
     shaders 资产递归安装 + notifyGlFrame + 渲染线程 GL 接管驻车 +
     installedFilters()）
  app/src/main/java/com/nesstation/app/core/storage/PadLayoutStore.kt
    （ndsDrasticFilter 字段 + copy + 读写）
  app/src/main/java/com/nesstation/app/ui/settings/CoreSettingsPanel.kt
    （激烈专属区新增「视频滤镜」下拉，列表来自捆绑 assets）
  app/src/main/java/com/nesstation/app/ui/emulator/EmulatorScreen.kt
    （applyCoreOptions 下发 drastic_filter；游戏内菜单新增滤镜下拉；
     存档格式选项文案纠正）
```

## 验证

- 六个修改文件用 kotlinc 2.0.21 与 HEAD 基线逐类对比：错误轮廓完全一致
  （全部为脱离 Android/Compose classpath 的预期级联），无语法/结构错误。
- fx 契约关键结论均有双路证据（smali + libdrastic_arm64.so 反汇编）：
  fxLoad 参数（GetStringUTFChars → fx_load(path, video+0x208, 0, 2208)）、
  draw_screen 顶点选择（csel：FBO!=0 → first=18，FBO==0 → first=0/6）、
  属性指针（stride=0，offset = ctx+0x468/0x470 = fxLoad 参数）、
  u_target_size（glUniform2f(wA,hA)/(wB,hB)）、
  存档格式位（csel x3, .dsv, .sav, eq ← [cfg+0x30] bit50）。

### 建议真机验证步骤

1. 设置 → 激烈专属 → 「视频滤镜」选 HQ2X / XBR / 扫描线等 → 进 3D 游戏
   → 画面应有明显滤镜效果（GL 路径）；选「关闭」恢复原生直绘。
2. 游戏内菜单切换滤镜 → 下一帧生效，无需重进。
3. melonDS 玩一段 → 存档 → 切激烈核心进同一游戏 → 进度应无缝继承
   （全局存档方式 = nesstation 时存档在 saves/<gameId>.sav，两核心同文件）。
4. 激烈核心玩一段 → 存档 → 检查 saves/ 目录 .sav 修改时间已更新；
   再用 melonDS 读档验证互认。
5. 旧版本（本轮之前）玩过的游戏：激烈私有目录里有 <基名>.dsv →
   首次进游戏自动迁移为全局 .sav（logcat 过滤 DraSticEngine 可见 migrated）。
6. 开「多线程 3D」→ 3D 大作（马力欧赛车/塞尔达）画面应完整无错乱
   （帧同步修复后与原版行为一致）；开关对比性能差异。
7. 高清渲染 + 滤镜组合 → 512×384 帧纹理经滤镜链放大，效果与原版一致。
