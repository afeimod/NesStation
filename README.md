# NesStation (GameBox)
## 演示图

![donate](https://github.com/afeimod/NesStation/blob/main/Screenshot_2026-09-06-16-46-33-287_com.nesstation.app.jpg?raw=true)

一个为 Android 手机与 Android TV 打造的高质感多平台复古游戏模拟器。

支持 **13 大平台**：NES / SFC / GB / GBA / **NDS** / PCE / DOS / Arcade / MD / Java ME / **DC (Dreamcast)** / **3DS (Azahar 独立核心)** / **NGC-WII (Ishiruka 独立核心)**，通过
统一的 Compose UI 与一致的游戏内菜单体验，让你在 TV 大屏和手机小屏上都能
畅玩从 8-bit 到街机再到 128-bit 的所有经典游戏。

| 平台 | 核心 | 文件扩展名 | 是否需要 BIOS |
| --- | --- | --- | --- |
| **NES / FC**   | FCEUmm            | `.nes` `.fds` `.unf`           | FDS 游戏需要 `disksys.rom` |
| **SNES / SFC** | snes9x            | `.smc` `.sfc` `.fig` `.swc`   | 否 |
| **GB / GBC**   | mGBA              | `.gb` `.gbc` `.sgb`           | 否 |
| **GBA**        | mGBA              | `.gba`                        | 否 |
| **PCE / TG16** | **Geargrafx**     | `.pce` `.sgx` `.hes` `.cue` `.chd` | PCE-CD 游戏需要 System Card（`syscard1/2/3.pce`、`gexpress.pce`） |
| **DOS**        | DOSBox-Pure       | `.bat` `.exe` `.dosz` `.conf` `.iso` | 否 |
| **Arcade**     | **FBNeo**         | `.zip` `.7z`                  | NeoGeo / PGM / Mega-CD 游戏需要 |
| **MD / SEGA**  | **Genesis-Plus-GX** | `.md` `.smd` `.sms` `.gg` `.sg` `.cue` `.chd` | Mega-CD 游戏需要 |
| **NDS**        | **melonDS / DraStic（激烈）双核心，启动时二选一** | `.nds` | 否（melonDS 内置 FreeBIOS；DraStic 自带） |
| **Java ME**    | J2ME-Loader       | `.jar` `.jad`                 | 否 |
| **DC / Dreamcast** | **Flycast 独立核心**（NAOMI / AtomisWave） | `.gdi` `.cdi` `.chd` `.cue` `.lst` `.zip` | 光盘游戏需要 `dc_boot.bin` + `dc_flash.bin`（NAOMI zip 自带 BIOS 无需） |
| **3DS**        | **Azahar / 爱吾 AzaharPlus 独立核心桥** | `.3ds` `.cci` `.cxi` `.cia` `.3dsx` | 加密游戏需要 `aes_keys.txt`（设置 → 3DS → 解密密钥导入） |
| **NGC / WII**  | **Ishiruka (Dolphin fork) 独立核心桥** | `.gcm` `.iso` `.rvz` `.gcz` `.wbfs` `.wad` `.ciso` `.nkit` `.tgc` `.dol` | 否 |

> 主界面参考 Pico-8 / Analogue Pocket 的视觉语言：像素云朵天空 + 玻璃拟态卡片 + 圆角高亮。

---

---

## 🎮 各核心说明

### NES / FC（FCEUmm 核心）
- 业界精度与兼容性最好的 NES 模拟核心
- 支持 NTSC / PAL / Dendy 三种区域
- 支持 FDS 磁碟游戏（需 `disksys.rom` BIOS，已内置在 assets）
- NTSC 滤镜（composite / s-video / RGB）、调色板选择、超频
- 自动存档 ×5 槽位 + 10 个手动存档槽

### SNES / SFC（snes9x 核心）
- snes9x 是跨平台兼容性最好的 SNES 核心
- 支持 5 个图层开关（BG1 / BG2 / BG3 / BG4 / OBJ）
- 高分辨率模式（hires）、图形透明、减少精灵闪烁
- 音频插值（同步 / 异步）
- 阻止无效 VRAM 写入、允许上下同时输入

### GB / GBC / GBA（mGBA 核心）
- mGBA 是最活跃维护的高精度 GB/GBC/GBA 核心
- GBC 颜色预设（默认 / GB ASP / GBC LCD / GBA LCD 等）
- GBA 颜色预设（默认 / GBA LCD / GBA SP 101 等）
- 帧跳类型（自动 / 无 / 帧跳 / VBI）
- 强制 RTC（实时时钟，用于宝可梦等游戏）
- 允许相反方向输入

### PCE / TG16（Geargrafx 核心）
- Geargrafx 支持 PC-Engine / TurboGrafx-16 / SuperGrafx / PCE-CD
- 主机类型（自动 / PC Engine 日版 / SuperGrafx / TurboGrafx-16 美版）
- 画面比例（1:1 PAR / 4:3 DAR / 6:5 DAR / 16:9 DAR / 16:10 DAR）
- 过扫描裁剪、精灵数量上限开关、调色板（Standard RGB / Turboxray / Kitrinx）
- 支持 5 人 TurboTap 手柄扩展、Memory Base 128 存档、允许相反方向输入
- **虚拟按键显隐**：布局编辑器内可单独显示/隐藏每个按键（十字键 / I / II / RUN / SELECT / V / VI / IV / III / TURBO I / TURBO II），适合清理屏幕或仅保留常用键
- **BIOS 需求**：卡带游戏（`.pce` / `.sgx`）与 HES 音乐（`.hes`）无需 BIOS；PCE-CD 光盘游戏（`.cue` / `.chd`）需要 System Card（`syscard3.pce` 最常见），存放在系统目录

### DOS（DOSBox-Pure 核心）
- DOSBox-Pure 是为 RetroArch 优化的 DOSBox 分支
- 支持 .bat 启动器导入整个游戏文件夹
- 机器型号（SVGA / VGA / EGA / CGA / Tandy / PCjr）
- CPU 速度（自动 / 486 / 386 / 286 / 8086）、声卡类型（SB16 / SB Pro / GUS）
- 鼠标输入模式（手柄 / 键盘）、暗屏超时
- Voodoo 显卡模拟、强制 60fps

### **Arcade / 街机（FBNeo 核心）**
- FBNeo（Final Burn Neo）支持 CPS1 / CPS2 / CPS3 / NeoGeo / PGM / ST-V 等
- 街机游戏存储为 `.zip` / `.7z` 文件（zip 本身就是 ROM）
- **6 键街机布局**：A B X Y L R + Select(Coin) + Start
- NeoGeo 模式切换（MVS 街机 / AES 家用）
- 画面旋转（横向 / 竖向射击游戏必需）、竖屏模式
- CPU 速度调节（50% / 75% / 100% / 150% / 200% / 250%）
- 音频插值（最近邻 / 线性 / 三次）+ 低通滤波
- **BIOS 需求**：NeoGeo 游戏 → `neogeo.zip`，PGM 游戏（三国战纪/魔窟）→ `pgm.zip`，详见 `assets/fbneo/README.txt`

### **MD / SEGA（Genesis-Plus-GX 核心）**
- Genesis-Plus-GX 支持 Mega Drive / Master System / Game Gear / SG-1000 / Mega-CD
- ⚠️ **不支持 SEGA Saturn（SS）** — SS 需要单独的 Yabause / Mednafen 核心
- **3 键 / 6 键手柄切换**（经典 3 键 / 街机 6 键）
- 区域选择（自动 / NTSC-U 美 / PAL 欧 / NTSC-J 日）
- 系统型号（自动 / MD / SMS / GG / SG）
- NTSC 滤镜（黑白 / RF / 复合 / S-Video / RGB）、LCD 滤镜
- Game Gear 扩展屏幕（160×144 → 256×144）、画面拉伸
- Master System FM 音源（自动 / 开 / 关）
- Mega-CD CD 快速启动（跳过 BIOS 动画）
- 超频（100% / 125% / 150% / 200%）
- **BIOS 需求**：MD/SMS/GG/SG 卡带游戏无需 BIOS；Mega-CD 光盘游戏需要 `bios_CD_E/J/U.zip`，详见 `assets/genesis/README.txt`

### J2ME / Java ME（J2ME-Loader 引擎）
- 支持 `.jar` 格式的 Java ME 游戏 / MIDlet
- 通过 DexClassLoader 动态加载游戏 DEX 文件
- 完整兼容 MIDP 2.0 / CLDC 1.1 规范
- M3G 3D 渲染（OpenGL ES 1.1）+ Mascot Capsule Micro3D
- 9 种视频滤镜（无 / 扫描线 / CRT / 点阵 / XBR / 4XBR / XBR+点阵 / 4XBR+点阵 / HQ4x）
- 滤镜直接作用于游戏渲染管线而非全屏覆盖
- 独立的 J2ME 滤镜偏好存储（与 NES 滤镜完全隔离）

### **DC / Dreamcast（Flycast 独立核心）**
- Flycast 是 Dreamcast / NAOMI / AtomisWave 的高精度开源模拟器，
  以 **独立模拟器形态** 集成（区别于其它进程内 libretro 核心）
- 支持 DC 商业游戏（`.gdi` / `.cdi` / `.chd` / `.cue` / `.iso`）与
  NAOMI / AtomisWave 街机 ROM（`.zip`，含合并集 `.lst`）
- **完整 DC 专属设置**：主界面"系统设置"与**游戏内设置菜单**均可调
  内部分辨率（0.5x-6x）、透明排序精度、渲染线程化、宽屏 16:9 视锥拉伸 /
  兼容补丁、延迟帧交换、跳帧、区域 / 语言 / 电视制式 / 视频输出、
  HLE BIOS、32MB 内存改机、强制 WinCE、GD-ROM 快速读盘、AICA DSP
- **完整 12 键虚拟手柄**：十字键 + A/B/X/Y 四键 + L/R 肩键 +
  START/SELECT + 连发 A/B（布局编辑器内可拖动 / 显隐），
  输出经 dcToLibretroLayout 语义直转 flycast 官方映射
- **渲染**：Vulkan / OpenGL 双后端（含逐像素排序 OIT 变体）、内部分辨率
  0.5x-8x 无级缩放、xBRZ 纹理放大、各向异性过滤、宽屏 16:9 补丁、
  超宽屏、整数缩放、自动跳帧、线程渲染
- **主机**：区域 / 电视制式 / 主机语言 / 视频输出制式、32MB 内存改机、
  快速 GD-ROM 读取、HLE BIOS（免真实 BIOS 启动部分游戏）、
  自动存读档（启动/退出时）
- **音频**：AICA DSP 音效、缓冲大小 23ms-128ms、自动延迟调节、VMU 蜂鸣
- **网络**：DC 调制解调器 / BBA 宽带适配器模拟、NAOMI 多机互联（主机端/分机）、
  GGPO 回滚联机
- **RetroAchievements 成就**：连接 retroachievements.org，硬核模式
- **原生 ImGui 菜单**：游戏内按返回键呼出 —— 全部设置实时调整、
  即时存档/读档（10 槽）、手柄映射（含物理手柄按键绑定）、
  原生虚拟手柄（可编辑按键布局，支持模拟摇杆/扳机）
- 仅提供 **arm64-v8a**（64 位）原生库；32 位设备会收到明确提示
- 集成实现：`core/dc/FlycastLauncher.kt`（启动）＋
  `ui/settings/FlycastCoreSettings.kt`（设置页直读直写 emu.cfg）＋
  `com/flycast/emulator/`（vendored 上游 Java 宿主层，见下节）

#### Flycast 集成架构说明

Flycast 的 Java 宿主层（`com.flycast.emulator.*`，22 个类）与 swappy 胶水
（`com.google.androidgamesdk.*`）自上游 v2.7-119 源码原样 vendor 进本工程，
保持原包名以匹配 `libflycast.so` 的静态 JNI 符号绑定；预编译核心库
（`libflycast.so` + adrenotools 自定义驱动钩子链 `libmain_hook/libhook_impl/
libfile_redirect_hook/libgsl_alloc_hook.so`）位于 `app/src/main/jniLibs/arm64-v8a/`。
仅做了三处最小补丁（均有 `NesStation 集成补丁` 注释）：

1. `Emulator.java`：移除 appcompat 静态初始化（宿主为 Compose，无需 appcompat）；
2. `BaseGLActivity.java`：新增 `EXTRA_GAME_URI` extra 传游戏路径（避免
   StrictMode FileUriExposedException）＋ 字符串资源前缀；
3. `AndroidStorage.java` / `HomeMover.java`：字符串资源引用加 `flycast_` 前缀，
   R 类改指向宿主 `com.nesstation.app.R`。

release 构建的 keep 规则见 `app/proguard-rules.pro`（JNI 按名绑定，不可混淆）。

### 3DS 集成架构说明（Azahar / 爱吾 AzaharPlus）

3DS 以**独立模拟器形态**集成（外部核心桥）：模拟画面与触摸层由核心 APK
的 `EmulationActivity` 呈现，NesStation 负责平台页 / 扫描 / 解密检测 /
密钥管理 / CIA 安装 / 启动桥接。实现见 `core/external/ExternalCores.kt`
＋ `ui/emulator/ExternalCoreScreen.kt`：

- 启动协议（反编译确认）：核心 onCreate 仅从 extras 读一个键
  `"game"`（`BundleCompat.getParcelable`），类型为
  `org.citra.citra_emu.model.Game`（Parcelable，位于核心 dex）。
  NesStation 经 `DexClassLoader` 动态装载核心 APK，反射构造 Game
  Parcelable 后打包进 Intent —— 跨进程反序列化天然命中核心内同名类；
- **CIA 安装**：`.cia` 直接引导即安装（Azahar 引导 CIA 自动装入 NAND）；
  另提供「复制到 import/ 目录」的批量导入模式；
- **游戏解密**：启动前检测 NCCH 容器 crypto 标志位（头 0x188 flags[3]，
  0=已解密）；加密游戏提示导入 `aes_keys.txt`，自动写入
  `<Azahar数据目录>/keys/aes_keys.txt`（设置 → 3DS → 解密密钥导入）；
- ⚠️ 需要同时安装核心 APK（`com.aiwu.citra_emu`，即 AzaharPlus /
  爱吾3DS模拟器），未安装时给出明确指引。

### NGC/WII 集成架构说明（Ishiruka — Dolphin fork）

NGC/WII 同样以**独立模拟器形态**集成（外部核心桥）：

- 启动协议（反编译确认）：核心 `EmulationActivity`（exported）读取
  extras `SelectedGames`（String[] 游戏路径）与 `Platform`
  （0=GC / 1=Wii，按扩展名推断）——见 `ExternalCores.launchNgcwiiGame`；
- **全量虚拟按键 / 控制器切换 / 体感**：由核心触摸层呈现 GC 手柄
  （A/B/X/Y/Z + 双摇杆 + L/R 扳机）、Wii 遥控器（1/2/A/B/±/HOME）、
  双节棍（C/Z + 副摇杆）、经典手柄（双摇杆 + 全键）、摇动/倾斜/IR 指针。
  NesStation 的启动页与设置页提供**控制器切换**（gc / wiimote / nunchuk /
  classic），写入核心 `Config/WiimoteNew.ini` 的 `Extension` 字段与
  `Config/Dolphin.ini`，并可在游戏内布局编辑器逐键显隐 / 拖动；
- **数据目录直读直写**：自动探测
  `<外部存储>/Android/data/org.dolphin.ishiiruka/files/dolphin-emu`（需
  「所有文件访问」权限），核心设置界面可一键直达；
- ⚠️ 需要同时安装核心 APK（`org.dolphin.ishiiruka`，即 Ishiruka）。

#### DC 多文件游戏扫描修复

`.gdi`（GD-ROM 文本清单）/ `.cue` 多文件游戏在目录扫描 / SAF 扫描 /
SAF 多选导入 / 自动扫描四条路径上均做「单游戏只取一个启动文件」去重：
存在 `.gdi` 时跳过同目录 `track01.bin / track02.raw / track03.wav` 等轨道
附属文件（含「.gdi 在上层、轨道在同名子文件夹」的摆放），存在 `.cue` 时
跳过 `.img/.bin/.ccd/.sub/.iso` —— 每个游戏在库中只占一个条目。

---

## 🎮 控制 / 手柄映射

### NES / SNES / GBA / Arcade / MD

| 按键 | 屏幕按钮 | 物理手柄 / 键盘 |
| --- | --- | --- |
| Up / Down / Left / Right | 十字键 | 方向键 / D-pad |
| A | A | X / Button A |
| B | B | Z / Button B |
| X (SNES/MD/Arcade) | X | S / Button X |
| Y (SNES/MD/Arcade) | Y | A / Button Y |
| L (SNES/GBA/MD/Arcade) | L | Q / Button L1 |
| R (SNES/GBA/MD/Arcade) | R | W / Button R1 |
| Start | Start | Enter / Button Start |
| Select / Coin (Arcade) | Select | Shift / Button Select |

支持自定义映射：进入 **设置 → 按键映射**。

### PCE / TG16 专属
PCE 使用与 SNES 相同的共享屏幕布局槽位，但按钮名映射为 PCE 原生按键：

| 屏幕按钮 | PCE 原生按键 | 物理手柄 / 键盘 |
| --- | --- | --- |
| Up / Down / Left / Right | 十字键 | 方向键 / D-pad |
| A | **I**（动作 / 部分游戏攻击） | X / Button A |
| B | **II**（跳跃 / 部分游戏射击） | Z / Button B |
| X | **IV** | S / Button X |
| Y | **III** | A / Button Y |
| L | **V** | Q / Button L1 |
| R | **VI** | W / Button R1 |
| L2 | **TURBO II**（II 连发开关） | L2 |
| R2 | **TURBO I**（I 连发开关） | R2 |
| Start | RUN | Enter / Button Start |
| Select | SELECT | Shift / Button Select |

> 大多数 2 键 PCE 游戏只用到 I（A）和 II（B）；IV/III/V/VI 用于部分 6 键格斗游戏。连发开关（TURBO I/II）按下后开启/关闭对应键的自动连发。

### Arcade（FBNeo）专属
- Select 键 = **投币（Coin）** — 街机游戏必须投币才能开始
- Start 键 = 开始游戏 / 服务菜单
- 6 键布局映射：A=Btn1, B=Btn2, X=Btn3, Y=Btn4, L=Btn5, R=Btn6
- 4 键格斗游戏（KOF、街霸 2）只用 A/B/X/Y
- 6 键格斗游戏（街霸 Zero、恶魔战士）全部使用

### MD / SEGA 专属
- 3 键游戏：A=SEGA A（跳）、B=SEGA B（攻击）、Start、C=SEGA C（冲刺）
- 6 键游戏：A=SEGA A, B=SEGA B, X=SEGA C, Y=SEGA X, L=SEGA Y, R=SEGA Z
- Select = Mode（6 键手柄模式切换）
- 在设置中切换手柄类型（3 键 / 6 键）

### J2ME
J2ME 游戏使用 J2ME-Loader 的虚拟键盘系统，支持：
- 屏幕虚拟按键（可自定义布局）
- 蓝牙手柄 / 键盘映射
- 长按游戏卡片 → 设置 → 进入 J2ME 配置页面进行按键重映射

---

## 🔧 BIOS 文件管理

### FDS（NES 磁碟游戏）
- 已内置 `assets/disksys.rom`（8192 字节）
- 启动 FDS 游戏时自动加载，无需手动操作

### FBNeo 街机 BIOS
- **必需**：NeoGeo 游戏 → `neogeo.zip`，PGM 游戏（三国战纪/魔窟）→ `pgm.zip`
- CPS1/CPS2/CPS3 游戏无需 BIOS
- BIOS 文件位置：`<filesDir>/fbneo/`
- **两种添加方式**：
  1. **打包到 APK**（私有构建）：放入 `app/src/main/assets/fbneo/`，启动时自动解压
  2. **运行时导入**：游戏中按返回键 → 设置 → Arcade BIOS 管理 → 导入
- 详见 `app/src/main/assets/fbneo/README.txt`

### Genesis-Plus-GX（Mega-CD）BIOS
- **必需**：Mega-CD / SEGA-CD 光盘游戏 → `bios_CD_E.zip`（欧）/ `bios_CD_J.zip`（日）/ `bios_CD_U.zip`（美）
- 卡带游戏（MD/SMS/GG/SG）无需 BIOS
- BIOS 文件位置：`<filesDir>/genesis/`
- **两种添加方式**：同 FBNeo
- 详见 `app/src/main/assets/genesis/README.txt`

### Geargrafx（PCE-CD）BIOS
- **必需**：PCE-CD 光盘游戏 → `syscard3.pce`（System Card 3 / Arcade Card Pro，最常用）
- 可选：`syscard1.pce` / `syscard2.pce`（旧 System Card）/ `gexpress.pce`（少量游戏需要）
- 卡带游戏（`.pce` / `.sgx`）与 HES 音乐（`.hes`）无需 BIOS
- BIOS 文件位置：`<filesDir>/pce/`
- **两种添加方式**：同 FBNeo —— ① 将 `.pce` 文件放入 `app/src/main/assets/pce/` 后重新构建，应用启动时自动识别并解压到 `<filesDir>/pce/`；② 游戏内按返回键 → 设置 → PCE BIOS 导入，从文件选择器导入 `.pce` 文件并自动命名
- 详见 `app/src/main/assets/pce/README.txt`

### Flycast（Dreamcast）BIOS
- **必需**：DC 光盘游戏 → `dc_boot.bin`（BIOS ROM）+ `dc_flash.bin`（闪存）
- 无需 BIOS：NAOMI / AtomisWave 街机游戏（`.zip`，BIOS 内置于 ROM 集中）
- BIOS 文件位置：`<filesDir>/dc/data/`
- **两种添加方式**：
  1. **打包到 APK**（私有构建）：放入 `app/src/main/assets/dc/`，启动时自动解压
  2. **运行时导入**：设置 → DC / Dreamcast → BIOS 管理，从文件选择器导入（按文件名自动归类 dc_boot/dc_flash）
- VMU 存档（`vmu_save_A1.bin`…）与即时存档（`*.state`）自动保存在同目录，
  首次启动由核心自动创建 VMU，无需手动准备
- 详见 `app/src/main/assets/dc/README.txt`

> ⚠️ **法律声明**：所有 BIOS 文件（neogeo.zip、pgm.zip、bios_CD_*.zip 等）都包含受版权保护的代码（SNK、IGS、SEGA 等）。本仓库不包含任何 BIOS 文件，仅提供占位说明文档。你只能将合法获取的 BIOS 文件打包到私有 APK 中供个人使用，不能在公开渠道（GitHub、应用商店等）分发包含 BIOS 的 APK。

---

### 检查 BIOS 文件状态

```bash
./scripts/check_bios_files.sh
```

### 添加 BIOS 文件（可选 — 仅 NeoGeo / PGM / Mega-CD 游戏需要）

```bash
# FBNeo BIOS（neogeo.zip, pgm.zip 等）
cp /path/to/neogeo.zip app/src/main/assets/fbneo/
cp /path/to/pgm.zip    app/src/main/assets/fbneo/

# Genesis-Plus-GX Mega-CD BIOS
cp /path/to/bios_CD_E.zip app/src/main/assets/genesis/
cp /path/to/bios_CD_J.zip app/src/main/assets/genesis/
cp /path/to/bios_CD_U.zip app/src/main/assets/genesis/
```
---

## 📜 许可证

| 组件 | 许可证 |
| --- | --- |
| 应用代码 | MIT |
| FCEUmm（NES 核心） | GPLv2 — 见 `assets/legal/LICENSE-FCEUmm.txt` |
| snes9x（SNES 核心） | 非商业 — 见 `assets/legal/LICENSE-snes9x.txt` |
| mGBA（GBA 核心） | MPL-2.0 — 见 `assets/legal/LICENSE-mGBA.txt` |
| DOSBox-Pure（DOS 核心） | GPLv2 — 见 `assets/legal/LICENSE-DOSBox-Pure.txt` |
| FBNeo（Arcade 核心） | 非商业 — 见 `assets/legal/LICENSE-FBNeo.txt` |
| Genesis-Plus-GX（MD 核心） | GPLv2 — 见 `assets/legal/LICENSE-Genesis-Plus-GX.txt` |
| J2ME-Loader | Apache License 2.0 |
| M3G 3D 引擎 | Apache License 2.0 |
| HQ2X / HQ4X 算法 | Maxim Stepin（免费使用） |
| XBR 算法 | Hyllian / Zenju（免费使用） |

详细的 ROM / BIOS 法律声明见 `app/src/main/assets/legal/ROM_NOTICE.txt`。

---
## 捐赠支持

* 想捐钱我喝杯热水（¥0.01 起捐）

![donate](https://github.com/afeimod/NesStation/blob/main/IMG_20260906_153806.jpg?raw=true)

![donate](https://github.com/afeimod/NesStation/blob/main/IMG_20260906_153816.jpg?raw=true)



## 🙌 致谢

- **FCEUX / FCEUmm 团队** — NES 模拟核心
- **snes9x 团队** — SNES 模拟核心
- **mGBA 团队**（Vicki Pfau 等）— GB/GBC/GBA 模拟核心
- **DOSBox-Pure 团队**（Markus Mertama 等）— DOS 模拟核心
- **FBNeo 团队**（基于 Dave 的 FinalBurn）— Arcade 模拟核心
- **Genesis-Plus-GX 团队**（Eke-Eke，基于 Charles MacDonald 的 Genesis Plus）— SEGA 模拟核心
- **J2ME-Loader 项目**（[nikita-shakarun](https://github.com/nikita-shakarun/j2me-loader)） — J2ME/Java ME 模拟引擎
- **libretro 项目** — 提供 .so 预编译核心的 buildbot
- **Analogue / Pico-8** — 视觉风格灵感
- **XBR 算法** by Hyllian / Zenju
- **HQ4x 算法** by Maxim Stepin
