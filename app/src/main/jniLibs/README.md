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
│   ├── libflycast.so                        # Flycast (Dreamcast/NAOMI standalone core, v2.7-119)
│   ├── libmain_hook.so                      # Flycast adrenotools 自定义 GPU 驱动钩子链
│   ├── libhook_impl.so                      #   (rend.CustomGpuDriver 功能，见
│   ├── libfile_redirect_hook.so             #    flycast 上游 shell/android 构建)
│   └── libgsl_alloc_hook.so                 #
├── armeabi-v7a/
│   ├── libdosbox_pure_libretro_android.so
│   ├── libfbneo_libretro_android.so
│   └── libgenesis_plus_gx_libretro_android.so
│   # 注：libflycast.so 仅提供 arm64-v8a（与上游 APK 一致）；
│   # 32 位设备上 DC 平台在启动前给出明确提示，不加载。
└── x86_64/
    ├── libdosbox_pure_libretro_android.so
    ├── libfbneo_libretro_android.so
    └── libgenesis_plus_gx_libretro_android.so
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
```

Or build from source — see the README in each upstream repository:
  - FBNeo: https://github.com/finalburnneo/FBNeo
  - Genesis-Plus-GX: https://github.com/libretro/Genesis-Plus-GX
  - DOSBox-Pure: https://github.com/schellingb/dosbox-pure

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
