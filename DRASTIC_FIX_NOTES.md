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
