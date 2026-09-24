# jniLibs/ — prebuilt native libraries

This directory holds **prebuilt `.so` files** that are bundled with the APK
but not compiled from source by the CMake build. Currently this includes
the **dlopen'd libretro cores** — large emulator cores that are loaded at
runtime via `dlopen()` by the JNI bridge (see `core/jni/*_loader.cpp`).

> Normally you do **not** check these files into git if the source is
> available. The Android Gradle Plugin (AGP) populates this directory at
> build time via the `externalNativeBuild { cmake { ... } }` configuration
> in `app/build.gradle.kts` for cores built from source (FCEUmm / snes9x /
> mGBA). The cores below are exceptions — they are too complex or too
> rapidly-updated to maintain a from-source build, so we use the official
> libretro buildbot prebuilts.

## Layout

```
jniLibs/
├── arm64-v8a/
│   ├── libdosbox_pure_libretro_android.so   # DOSBox-Pure (DOS core)
│   ├── libfbneo_libretro_android.so         # FBNeo (Arcade core)
│   ├── libgenesis_plus_gx_libretro_android.so  # Genesis-Plus-GX (MD core)
│   ├── libgeargrafx_libretro_android.so     # Geargrafx (PCE core)
│   ├── libmelonds_libretro_android.so       # melonDS (NDS core)
│   ├── libpcsx_rearmed_libretro_android.so  # PCSX-ReARMed (PSX core)
│   ├── libflycast_libretro_android.so       # Flycast (Dreamcast/NAOMI libretro core)
│   ├── libdrastic_arm64.so / libdrastic_cpu.so  # DraStic（激烈 NDS 备选核心）
│   └── libfile_redirect_hook.so 等          # ARMSX2 adrenotools 驱动钩子链（arm64）
├── armeabi-v7a/
│   ├── libdosbox_pure_libretro_android.so
│   ├── libfbneo_libretro_android.so
│   ├── libgenesis_plus_gx_libretro_android.so
│   └── libflycast_libretro_android.so
└── x86_64/
    ├── libdosbox_pure_libretro_android.so
    ├── libfbneo_libretro_android.so
    ├── libgenesis_plus_gx_libretro_android.so
    └── libflycast_libretro_android.so
```

The CMake build also produces the following `.so` files from source
into the same directories at build time (NOT checked into git):

| Core | .so name | Source |
| --- | --- | --- |
| FCEUmm (NES) | `libnescore.so` | `core/fceumm/` (git submodule) |
| snes9x (SNES) | `libsnescore.so` | `core/snes9x/` (git submodule) |
| mGBA (GB/GBA) | `libgbacore.so` | `core/mgba/` (vendored) |
| FBNeo bridge | `libfbneocore.so` | `core/jni/fbneo_*.cpp` |
| Genesis bridge | `libgenesicore.so` | `core/jni/genesis_*.cpp` |
| DOS bridge | `libdoscore.so` | `core/jni/dos_*.cpp` |
| M3G (J2ME 3D) | `libjavam3g.so` | `app/src/main/cpp/m3g/` |
| Micro3D (J2ME) | `libmicro3d.so` | `app/src/main/cpp/micro3d/` |

## How the dlopen pattern works

```
                Kotlin                    C++ JNI bridge              Prebuilt .so
                ------                    ---------------              ------------
NesApp.onCreate → FbNeoEngine.ensureLoaded → FbNeoNative.ensureLoaded
                                          → System.loadLibrary("fbneocore")
                                                                       (links dl)
                                          → setCoreLibPath(nativeDir +
                                              "/libfbneo_libretro_android.so")
                                          → fbneo_loader.cpp::init():
                                              dlopen(path) → dlsym(retro_*)

FbNeoEngine.loadRom → FbNeoNative.loadRom → fbneo_loader.cpp::loadRom:
                                              retro_init()
                                              retro_load_game(path)
                                              retro_run() per frame
```

The bridge `.so` (e.g. `libfbneocore.so`) is tiny (~50 KiB) — it only
contains the JNI surface + libretro frontend logic. The actual emulator
code lives in the prebuilt `libfbneo_libretro_android.so` (~70 MiB for
arm64) which is loaded at runtime. This keeps the source tree small
and lets us update the prebuilt core independently of the app build.

## Updating the prebuilt .so files

To update to a newer libretro buildbot version:

```bash
# FBNeo
for abi in arm64-v8a armeabi-v7a x86_64; do
  curl -L -o /tmp/fbneo.zip \
    "https://buildbot.libretro.com/nightly/android/latest/${abi}/fbneo_libretro_android.so.zip"
  unzip -o /tmp/fbneo.zip -d /tmp/fbneo_extract
  cp /tmp/fbneo_extract/fbneo_libretro_android.so \
     app/src/main/jniLibs/${abi}/
done

# Genesis-Plus-GX
for abi in arm64-v8a armeabi-v7a x86_64; do
  curl -L -o /tmp/genesis.zip \
    "https://buildbot.libretro.com/nightly/android/latest/${abi}/genesis_plus_gx_libretro_android.so.zip"
  unzip -o /tmp/genesis.zip -d /tmp/genesis_extract
  cp /tmp/genesis_extract/genesis_plus_gx_libretro_android.so \
     app/src/main/jniLibs/${abi}/
done

# DOSBox-Pure
for abi in arm64-v8a armeabi-v7a x86_64; do
  curl -L -o /tmp/dosbox.zip \
    "https://buildbot.libretro.com/nightly/android/latest/${abi}/dosbox_pure_libretro_android.so.zip"
  unzip -o /tmp/dosbox.zip -d /tmp/dosbox_extract
  cp /tmp/dosbox_extract/dosbox_pure_libretro_android.so \
     app/src/main/jniLibs/${abi}/
done

# Flycast (Dreamcast / NAOMI / AtomisWave)
# NOTE: the buildbot zip extracts as flycast_libretro_android.so (no "lib"
# prefix). It MUST be renamed to libflycast_libretro_android.so to match the
# dlopen name used by dc_loader.cpp and the lib* convention of every other
# core (files without the lib prefix are skipped by the package extractor on
# some Android versions — "DC 核心文件缺少 lib 文本" fix).
for abi in arm64-v8a armeabi-v7a x86_64; do
  curl -L -o /tmp/flycast.zip \
    "https://buildbot.libretro.com/nightly/android/latest/${abi}/flycast_libretro_android.so.zip"
  unzip -o /tmp/flycast.zip -d /tmp/flycast_extract
  cp /tmp/flycast_extract/flycast_libretro_android.so \
     app/src/main/jniLibs/${abi}/libflycast_libretro_android.so
done
```

Or build from source — see the README in each upstream repository:
  - FBNeo: https://github.com/finalburnneo/FBNeo
  - Genesis-Plus-GX: https://github.com/libretro/Genesis-Plus-GX
  - DOSBox-Pure: https://github.com/schellingb/dosbox-pure
  - Flycast: https://github.com/libretro/flycast

## When the file is missing

If you cloned the repo and ran the app but `System.loadLibrary("fbneocore")`
throws `UnsatisfiedLinkError`, the prebuilt `.so` is missing. Two fixes:

### Fix 1 — Re-download the prebuilt (see commands above)

### Fix 2 — Use the stub core
In `gradle.properties`, set:
```
useStubCore=true
```
This switches CMake to `core/native-stub/CMakeLists.txt`, which produces
no-op stub `.so` files that let the app start without the real core
sources. The UI will render but the emulator will not run real games.

## License

The prebuilt `.so` files are licensed under their respective upstream
licenses — see `app/src/main/assets/legal/LICENSE-*.txt`:
  - `LICENSE-FBNeo.txt`           — FBNeo non-commercial license
  - `LICENSE-Genesis-Plus-GX.txt` — GPLv2
  - `LICENSE-DOSBox-Pure.txt`     — GPLv2

The bridge `.so` files (compiled from `core/jni/*_bridge.cpp`) are
licensed under the NesStation app's MIT license.

## Azahar (3DS) 与 Ishiiruka (NGC/WII) 核心库（3.7 新增）

两个"独立模拟器形态"核心参照 DraStic 模式集成：预编译 `.so` 放本目录，
由 vendored 原包名 JNI 契约类（`org.citra.citra_emu.NativeLibrary` /
`org.dolphinemu.ishiiruka.NativeLibrary`）按符号绑定：

| 核心库 | 来源 APK 内的库 | 平台 | 引擎 | 提取方式 |
| --- | --- | --- | --- | --- |
| `libazahar.so` | `libcitra-android.so`（AzaharPlus / 官方 Azahar 2125+ APK） | 3DS | `AzaharEngine` | `scripts/fetch_azahar_ishiruka_libs.sh --apk3ds <AzaharPlus.apk>` |
| `libishiiruka.so` | `libmain.so`（Ishiruka APK） | NGC/WII | `IshirukaEngine` | `scripts/fetch_azahar_ishiruka_libs.sh --apkngcwii <Ishiruka.apk>` |

仅提供 arm64-v8a（上游 Android 移植均为 64 位 only）。库缺失时应用正常启动，
进入对应平台游戏会明确提示"核心不可用"及提取方法，不会崩溃。
