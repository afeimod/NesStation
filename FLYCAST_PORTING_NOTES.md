# Flycast (Dreamcast) 移植与加固说明 — dcfix6

## 本轮做了什么

1. **flycast 源码完整入仓**：`core/flycast/`（基于 flyinghead/flycast 2026-09-11，
   含全部嵌套子模块；已裁剪 libretro 构建用不到的独立版依赖：gamesdk / breakpad /
   oboe / Spout）。
2. **core `libflycast_libretro_android.so` 由本仓库从源码编译**：
   - 构建脚本：`core/flycast/scripts/build_android.sh`
     （Android NDK + CMake，`-DLIBRETRO=ON`，产物安装到
     `app/src/main/jniLibs/arm64-v8a/`）
   - Gradle 任务 `:app:buildFlycastCore` 已接入 `preBuild`：
     环境有 NDK（ANDROID_HOME / ANDROID_NDK_HOME）时自动增量重编，
     没有 NDK 时跳过、直接打包 jniLibs 里已编译好的产物。
     可用 `-PskipFlycastCore` 强制跳过。
3. **SIGSEGV 加固（针对 Redmi socrates / HyperOS 上 addrspace::write32 +
   [anon:.bss] 的 SEGV_ACCERR 崩溃链）**：
   - `core/flycast/core/hw/mem/addrspace.{h,cpp}`：新增
     `addrspace::selfHealManagedFault()` —— SIGSEGV 兜底自愈。
     dynarec/纹理缓存依赖页写保护 + 进程级 SIGSEGV handler；当 handler 链被
     其它库（GL 驱动、OEM “崩溃监控”库）破坏或保护页未被认领时，受管内存
     （vmem arena / nvmem 缓冲）内的错误页直接 mprotect 恢复可写并继续运行；
     同一页重复出错则视为真野指针，放行给默认处理。
   - `core/flycast/core/linux/common.cpp`：fault_handler 在所有专责 handler
     之后调用自愈兜底。
   - `core/flycast/core/linux/posix_vmem.cpp`：vmem 初始化失败原子化
     （`region_unlock_safe`），任何一步失败都完整回退 nvmem 慢路径，
     不再留半初始化状态。
   - `core/flycast/core/hw/mem/addrspace.cpp reserve()`：尊重
     `virtmem::init()` 返回值并记日志。
4. **构建系统修复（dcfix6 工程侧）**：
   - `app/build.gradle.kts`：versionCode 9 / versionName 3.6.3-dcfix6；
     signingConfig `dcfix`（`signing/nesstation-dcfix.keystore`，
     storepass/keypass `nesstation123`，alias `nesstation`）；
     `-DCMAKE_BUILD_TYPE=Release`；新增 `stripCxxNativeLibs` 任务
     （AGP 强制注入 `-g` 且无法关闭，PS2 核心曾达 317MB，合并前就地 strip）。
   - `core/cmake/CMakeLists.txt`：configure 期剥离 CMAKE_C(XX)_FLAGS 里的 `-g`。
5. **产物验证**（APK 内 lib/arm64-v8a/libflycast_libretro_android.so）：
   - BuildId `967228ad8679bdf05da8df61abbda9ae0452e076`
     （历史崩溃构建为 a9bb8e... / 38e40477...，均已作废）
   - DT_NEEDED = libm/libdl/libc（bionic 动态链接，无静态 libc）
   - getauxval 为 UND 导入（修复 dlopen 构造函数 getauxval+28 崩溃）
   - LOAD 段 16KB 页对齐（Android 15 就绪）

## 如何从源码构建 APK

```bash
# 依赖：JDK 17、Android SDK（platform-34 / build-tools 33.0.1）、
#       NDK 28.2.13676358、CMake 3.22.1（SDK 自带即可）
export ANDROID_HOME=/path/to/android-sdk
./gradlew :app:assembleRelease -PabiFilter=arm64-v8a
# 产物：app/build/outputs/apk/release/app-release.apk
```

单独重编 flycast core：

```bash
ANDROID_HOME=/path/to/android-sdk ./core/flycast/scripts/build_android.sh --abi arm64-v8a
```

## 注意

- 本包 jniLibs 仅保留 arm64-v8a（用户设备为 arm64）；如需其它 ABI，
  把对应预编译核心放回 `app/src/main/jniLibs/<abi>/` 并去掉 abiFilter。
- flycast core 的 SONAME 保持 `flycast_libretro.so`（与
  `core/jni/flycast_loader.cpp` 的 dlopen 逻辑兼容）。

## dcfix8（SAFE_MEM，根治 write32 SIGSEGV 闪退）

dcfix6/7 的 SIGSEGV 探针+自愈链在部分 OEM ROM（HyperOS）上仍会被进程内其它
组件在帧中间抢走 SIGSEGV 处置，导致下一次"写保护页写"直接杀死进程
（tombstone: addrspace::write32 + [anon:.bss]，[anon:.bss] 即本核心 BSS 段的
JIT 代码缓存，见 core/oslib/virtmem.h DECLARE_CODE_CACHE 的 Android 分支）。

dcfix8 改为 SAFE_MEM 模式（CMake 全局宏 NESSTATION_DC_SAFE_MEM）：

1. `core/linux/posix_vmem.cpp` region_lock() 变 no-op —— 模拟器数据页
   （RAM/VRAM/ARAM）与 FPCB 一律不再 mprotect 写保护；reset_mem() 同样 no-op。
2. `core/hw/mem/addrspace.cpp` bm_reset() 改为急切填充 FPCB 全表
   （bm_vmem_pagefill），无 PROT_NONE 陷阱。
3. 纹理失效与自修改代码失效改由帧间轮询完成（SIGSEGV 依赖归零）：
   - `core/rend/TexCache.cpp` VramPollDirtyPages()：只比对当前挂了纹理锁的
     页（影子快照 memcmp），脏页走原 VramLockedWriteOffset() 失效路径；
     经 0x06 VRAM mirror 的写同样能被捕获（同物理页）。
   - `core/hw/sh4/dyna/blockmanager.cpp` bm_PollDirtyRamPages()：只比对注册
     了代码块的 RAM 页，脏页执行 bm_RamWriteAccess() 并重新武装
     unprotected_pages[]，保证后续块继续被监视。
   - 两者由 `shell/libretro/libretro.cpp` retro_run() 每帧调用（emu 线程），
     开销为几百个 4K 页的 memcmp，毫秒级以内。
4. 正确性代价（可接受）：失效最多延迟一帧；同帧内"写已编译代码页后再跳入"
   的极端自修改代码模式会执行一次旧块（下一帧自动纠正）。SIGSEGV 护甲、
   selfHealManagedFault 与 fault handler 全部保留作为兜底。

重编 core 后 BuildId 变为 008bb2e1...（区别于 dcfix6 的 967228ad...）。
