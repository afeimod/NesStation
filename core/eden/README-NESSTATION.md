# core/eden —— Eden（Nintendo Switch）核心源码树（NesStation 集成版）

## 来源与基线
- 上游：https://github.com/eden-emulator/mirror.git（eden-emulator/eden 的
  GitHub 镜像），2026-10 快照。
- 与 `:cemu-core`（Wii U / Cemu-AZ）的集成方式完全一致：完整源码树随
  NesStation 分发，经 `eden-core/` Gradle 模块（externalNativeBuild 指向
  本目录的 `CMakeLists.txt`）从源码编译产出 `libyuzu-android.so`，并入
  主 APK。构建参数与上游 `src/android/app/build.gradle.kts` 的 main
  flavor 逐项一致（差异仅两处：`-DYUZU_DOWNLOAD_ANDROID_VVL=OFF`、
  `-DENABLE_UPDATE_CHECKER=OFF`，均为“独立 App 形态”专属步骤，见
  `eden-core/build.gradle.kts` 注释）。

## 与上游 mirror 的差异（重要）
GitHub 上的 eden mirror 仓库带系统性反爬损坏：全部 855 处
`[[maybe_unused]]` 属性被删成 `[aybe_unused]]`（少一个 `[` 和一个 `m`，
分布于 175 个源文件），直接编译会得到一堆语法错误。本目录内的快照
已把这些机械性损坏全部修复为标准 `[[maybe_unused]]`（一次性 sed：
`s/\[aybe_unused\]\]/[[maybe_unused]]/g`），除此之外与上游 mirror
逐字节一致 —— 上游其余部分（`[[nodiscard]]`、`[[noreturn]]`、
`[[fallthrough]]` 等）经核对无损坏。

## 构建须知（详见 eden-core/build.gradle.kts 与根 README）
1. 首次构建 CPM 需联网编译 FFmpeg/OpenSSL/cpp-jwt 等依赖
   （约 25–45 分钟，之后有二进制缓存）；
2. SDK 需 NDK 28.2.13676358 与 CMake 3.31.6
   （`sdkmanager "ndk;28.2.13676358" "cmake;3.31.6"`，或
   `-PedenNdkVersion` / `-PedenCmakeVersion` 覆盖）；
3. tz 数据（nx_tzdb）、.patch 目录的 CPM 补丁、externals/ 内置依赖
   均已随本树附带，无需额外脚本；
4. 临时不需要 Switch 核心：`./gradlew assembleDebug -PskipEdenCore=true`。

## 上游许可证
Eden 及其组件按各自 LICENSE（GPL 系列 + 第三方许可）分发，见
`LICENSE.txt` 与 `LICENSES/`；本树不删除任何许可与版权声明。
