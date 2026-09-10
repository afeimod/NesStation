cmake_minimum_required(VERSION 3.22.1)
project("nesstation-core" C CXX ASM)

# ASM 必须在顶层 configure 时启用：melonDS / mGBA 等子工程通过
# add_subdirectory 加入并各自 enable_language(ASM)，CMake 3.22 + Ninja
# 在生成阶段会因未注册 ASM 编译规则而报
# "required internal CMake variable not set: CMAKE_ASM_COMPILE_OBJECT"。
# 这里提前声明 ASM 让规则完整注册，并兜底补上编译对象规则。
if(NOT CMAKE_ASM_COMPILE_OBJECT)
    set(CMAKE_ASM_COMPILE_OBJECT
        "<CMAKE_ASM_COMPILER> <DEFINES> <INCLUDES> <FLAGS> -o <OBJECT> -c <SOURCE>")
endif()

set(CMAKE_C_STANDARD 11)
set(CMAKE_C_STANDARD_REQUIRED ON)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

# ---------------------------------------------------------------------------
# NES/FC core = NostalgiaLite's fceux engine (core/fceux), REPLACING the
# previous libretro-fceumm core. This is the engine that plays the previously
# gray-screening games (e.g. 天使之翼2中文版) correctly in the reference app.
# It is driven DIRECTLY through its native driver API (FCEUI_Initialize /
# FCEUI_LoadGame / FCEUI_Emulate ...) from core/jni/rom_loader.cpp — no
# libretro layer. The engine is compiled as a STATIC library (own
# C++ standard + flags, identical to NostalgiaLite's build) and linked into
# libnescore.so together with the JNI bridge.
# ---------------------------------------------------------------------------

set(FCEUX_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../fceux")

if(NOT EXISTS "${FCEUX_DIR}/fceu.cpp")
    message(FATAL_ERROR
        "fceux sources not found at ${FCEUX_DIR}.\n"
        "The FC core was replaced with NostalgiaLite's fceux engine; the tree\n"
        "must contain fceu.cpp/ines.cpp/boards//input//utils/.")
endif()

# Source set = same files NostalgiaLite compiles (GLOB_RECURSE *.c *.cpp
# under its cpp/ dir, which is fceux/** + its Nes.cpp driver). The driver
# glue lives in core/jni/rom_loader.cpp (compiled below with the bridge).
file(GLOB_RECURSE FCEUX_SOURCES CONFIGURE_DEPENDS
    "${FCEUX_DIR}/*.c"
    "${FCEUX_DIR}/*.cpp")

# OBJECT library: every fceux object file lands in libnescore.so — no
# archive member resolution needed (the engine is one closed unit).
add_library(fceux_core OBJECT ${FCEUX_SOURCES})

# Same include dirs and defines as NostalgiaLite's appnes CMakeLists:
#   ./ fceux/ fceux/boards/ fceux/input/
# NOTE: do NOT add fceux/utils to the include path — its endian.h would
# shadow the system <endian.h> pulled in by sys/types.h and break the build.
target_include_directories(fceux_core PUBLIC
    "${FCEUX_DIR}"
    "${FCEUX_DIR}/boards"
    "${FCEUX_DIR}/input"
)

target_compile_definitions(fceux_core PRIVATE
    PSS_STYLE=1
    HAVE_ASPRINTF
    LSB_FIRST
    FRAMESKIP
    IOAPI_NO_64
    _GLIBCXX_USE_WCHAR_T
    NDEBUG
)

# The fceux codebase predates modern C++; keep it on gnu++11 exactly like
# the reference app, and silence its (harmless) warning noise so the rest
# of the build stays readable.
set_target_properties(fceux_core PROPERTIES
    CXX_STANDARD 11
    C_STANDARD 11
    CXX_EXTENSIONS ON
)
target_compile_options(fceux_core PRIVATE
    -O2
    -w
    -Wno-error
)

# --- Our JNI bridge + engine driver ------------------------------------------
set(APP_JNI_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../jni")
set(APP_JNI_SOURCES
    "${APP_JNI_DIR}/bridge.cpp"
    "${APP_JNI_DIR}/rom_loader.cpp"
)

# --- HQX library (Maxim Stepin's HQ2X/HQ4X reference implementation) -------
set(HQX_DIR "${APP_JNI_DIR}/hqx")
set(HQX_SOURCES
    "${HQX_DIR}/init.c"
    "${HQX_DIR}/hq2x.c"
    "${HQX_DIR}/hq4x.c"
)

add_library(nescore SHARED
    ${APP_JNI_SOURCES}
    ${HQX_SOURCES}
    $<TARGET_OBJECTS:fceux_core>
)

target_include_directories(nescore PRIVATE
    "${APP_JNI_DIR}"
    "${HQX_DIR}"
    "${CMAKE_CURRENT_SOURCE_DIR}/.."   # so rom_loader.cpp can #include "fceux/xxx.h"
    "${FCEUX_DIR}"
)

target_compile_definitions(nescore PRIVATE
    # fceux headers (types.h) pulled in by rom_loader.cpp require these:
    PSS_STYLE=1
    LSB_FIRST
    HAVE_ASPRINTF
    NDEBUG
)

target_compile_options(nescore PRIVATE
    -O2
    -fvisibility=hidden
    -ffunction-sections -fdata-sections
    -Wno-unused-parameter
    -Wno-write-strings
)

# Android log + native_window (ANativeWindow) for hardware-accelerated surface
# rendering, plus zlib (fceux utils/unzip.cpp — built-in .zip ROM support).
target_link_libraries(nescore log android z)

# AGP's externalNativeBuild automatically picks up the .so from CMake's
# build output — no manual LIBRARY_OUTPUT_DIRECTORY needed.

# ---------------------------------------------------------------------------
# libretro-snes9x (the best-in-class SNES/SFC core) — compiled as a separate
# .so (libsnescore.so) to avoid retro_* symbol conflicts with libnescore.so.
# Driven through the standard libretro API, same pull-model as FCEUmm.
# ---------------------------------------------------------------------------
set(SNES9X_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../snes9x")
set(SNES9X_SRC "${SNES9X_DIR}")

if(EXISTS "${SNES9X_SRC}/libretro/libretro.cpp")
    set(SNES9X_CORE_SOURCES
        "${SNES9X_SRC}/filter/snes_ntsc.c"
        "${SNES9X_SRC}/apu/apu.cpp"
        "${SNES9X_SRC}/apu/bapu/dsp/sdsp.cpp"
        "${SNES9X_SRC}/apu/bapu/smp/smp.cpp"
        "${SNES9X_SRC}/apu/bapu/smp/smp_state.cpp"
        "${SNES9X_SRC}/bsx.cpp"
        "${SNES9X_SRC}/c4.cpp"
        "${SNES9X_SRC}/c4emu.cpp"
        "${SNES9X_SRC}/cheats.cpp"
        "${SNES9X_SRC}/cheats2.cpp"
        "${SNES9X_SRC}/clip.cpp"
        "${SNES9X_SRC}/conffile.cpp"
        "${SNES9X_SRC}/controls.cpp"
        "${SNES9X_SRC}/cpu.cpp"
        "${SNES9X_SRC}/cpuexec.cpp"
        "${SNES9X_SRC}/cpuops.cpp"
        "${SNES9X_SRC}/crosshairs.cpp"
        "${SNES9X_SRC}/dma.cpp"
        "${SNES9X_SRC}/dsp.cpp"
        "${SNES9X_SRC}/dsp1.cpp"
        "${SNES9X_SRC}/dsp2.cpp"
        "${SNES9X_SRC}/dsp3.cpp"
        "${SNES9X_SRC}/dsp4.cpp"
        "${SNES9X_SRC}/fxinst.cpp"
        "${SNES9X_SRC}/fxemu.cpp"
        "${SNES9X_SRC}/gfx.cpp"
        "${SNES9X_SRC}/globals.cpp"
        "${SNES9X_SRC}/memmap.cpp"
        "${SNES9X_SRC}/obc1.cpp"
        "${SNES9X_SRC}/msu1.cpp"
        "${SNES9X_SRC}/ppu.cpp"
        "${SNES9X_SRC}/stream.cpp"
        "${SNES9X_SRC}/sa1.cpp"
        "${SNES9X_SRC}/sa1cpu.cpp"
        "${SNES9X_SRC}/screenshot.cpp"
        "${SNES9X_SRC}/sdd1.cpp"
        "${SNES9X_SRC}/sdd1emu.cpp"
        "${SNES9X_SRC}/seta.cpp"
        "${SNES9X_SRC}/seta010.cpp"
        "${SNES9X_SRC}/seta011.cpp"
        "${SNES9X_SRC}/seta018.cpp"
        "${SNES9X_SRC}/snapshot.cpp"
        "${SNES9X_SRC}/snes9x.cpp"
        "${SNES9X_SRC}/spc7110.cpp"
        "${SNES9X_SRC}/srtc.cpp"
        "${SNES9X_SRC}/tile.cpp"
        "${SNES9X_SRC}/tileimpl-n1x1.cpp"
        "${SNES9X_SRC}/tileimpl-n2x1.cpp"
        "${SNES9X_SRC}/tileimpl-h2x1.cpp"
        "${SNES9X_SRC}/sha256.cpp"
        "${SNES9X_SRC}/bml.cpp"
        "${SNES9X_SRC}/movie.cpp"
        "${SNES9X_SRC}/fscompat.cpp"
        # libretro frontend (provides retro_init / retro_load_game / retro_run ...)
        "${SNES9X_SRC}/libretro/libretro.cpp"
    )

    set(SNES9X_JNI_SOURCES
        "${APP_JNI_DIR}/snes_bridge.cpp"
        "${APP_JNI_DIR}/snes_loader.cpp"
        ${HQX_SOURCES}
    )

    add_library(snescore SHARED
        ${SNES9X_CORE_SOURCES}
        ${SNES9X_JNI_SOURCES}
    )

    target_include_directories(snescore PRIVATE
        "${SNES9X_SRC}"
        "${SNES9X_SRC}/libretro"
        "${SNES9X_SRC}/libretro/libretro-common/include"
        "${SNES9X_SRC}/apu"
        "${SNES9X_SRC}/apu/bapu"
        "${APP_JNI_DIR}"
        "${HQX_DIR}"
    )

    target_compile_definitions(snescore PRIVATE
        __LIBRETRO__
        ANDROID
        HAVE_STRINGS_H
        RIGHTSHIFT_IS_SAR
        NDEBUG
    )

    target_compile_options(snescore PRIVATE
        -O2
        -fvisibility=hidden
        -ffunction-sections -fdata-sections
        -fno-rtti
        -fno-exceptions
        -Wno-unused-parameter
        -Wno-unused-variable
        -Wno-sign-compare
        -Wno-write-strings
        -Wno-deprecated-declarations
    )

    target_link_libraries(snescore log android)
endif()

# ---------------------------------------------------------------------------
# mGBA (GB/GBC/GBA core) — built as a static library via its CMake, then
# linked with our JNI bridge + libretro frontend into libgbacore.so.
# Separate .so avoids retro_* symbol conflicts with the other cores.
# ---------------------------------------------------------------------------
set(MGBA_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../mgba")

if(EXISTS "${MGBA_DIR}/CMakeLists.txt")
    # Build mGBA core as a static library (no frontends, no deps)
    set(LIBMGBA_ONLY ON CACHE BOOL "" FORCE)
    set(BINARY_NAME mgba CACHE INTERNAL "")
    add_subdirectory("${MGBA_DIR}" "${CMAKE_BINARY_DIR}/mgba-build" EXCLUDE_FROM_ALL)

    set(GBA_JNI_SOURCES
        "${APP_JNI_DIR}/gba_bridge.cpp"
        "${APP_JNI_DIR}/gba_loader.cpp"
        "${MGBA_DIR}/src/platform/libretro/libretro.c"
        ${HQX_SOURCES}
    )

    add_library(gbacore SHARED ${GBA_JNI_SOURCES})

    target_include_directories(gbacore PRIVATE
        "${MGBA_DIR}/include"
        "${MGBA_DIR}/src"
        "${MGBA_DIR}/src/platform/libretro"
        "${CMAKE_BINARY_DIR}/mgba-build/include"
        "${APP_JNI_DIR}"
        "${HQX_DIR}"
    )

    # ABI-critical: these defines MUST match the ones used when building the
    # mgba static library target (via add_subdirectory above).  Mismatched
    # defines change struct layouts (mCore, mColor) and cause silent crashes.
    #
    # Library defines (from mgba CMakeLists.txt):
    #   M_CORE_GBA, M_CORE_GB       — via add_definitions()
    #   ENABLE_VFS, ENABLE_DIRECTORIES — via FEATURE_DEFINES (ENABLE_VFS=ON)
    #   ENABLE_VFS_FD, BUILD_STATIC    — via OS_DEFINES (Linux/Android)
    #   _GNU_SOURCE, USE_PTHREADS      — via add_definitions()
    #
    # NOT defined by library (so must NOT be defined here either):
    #   MINIMAL_CORE   — would shrink mCore struct (inputMap, startVideoLog…)
    #   COLOR_16_BIT   — would change mColor from uint32_t to uint16_t
    #   COLOR_5_6_5    — only meaningful with COLOR_16_BIT
    #
    # DISABLE_THREADING is safe: it only affects mLockstep/mRewindContext,
    # neither of which libretro.c accesses directly.
    target_compile_definitions(gbacore PRIVATE
        __LIBRETRO__
        DISABLE_THREADING
        MGBA_STANDALONE
        ENABLE_VFS
        ENABLE_DIRECTORIES
        ENABLE_VFS_FD
        M_CORE_GBA
        M_CORE_GB
        ANDROID
        NDEBUG
    )

    target_compile_options(gbacore PRIVATE
        -O2
        -fvisibility=hidden
        -ffunction-sections -fdata-sections
        -Wno-unused-parameter
        -Wno-unused-variable
        -Wno-sign-compare
        -Wno-deprecated-declarations
        -Wno-cpp
    )

    target_link_libraries(gbacore mgba log android)
endif()

# ---------------------------------------------------------------------------
# DOSBox-Pure (DOS/PC emulator) — built as libdoscore.so, which dlopen()s the
# prebuilt libdosbox_pure_libretro_android.so from app/src/main/jniLibs/<abi>/
# at runtime. This avoids having to compile the complex dosbox-pure source tree
# and lets us reuse the prebuilt binaries from the libretro buildbot.
#
# Our JNI bridge (dos_bridge.cpp) + libretro frontend (dos_loader.cpp) drive
# the core through the standard retro_* API, same pull-model as the other cores.
# The loader also requires the HQX library for video filter upscalers.
# ---------------------------------------------------------------------------
set(DOS_JNI_SOURCES
    "${APP_JNI_DIR}/dos_bridge.cpp"
    "${APP_JNI_DIR}/dos_loader.cpp"
    ${HQX_SOURCES}
)

add_library(doscore SHARED ${DOS_JNI_SOURCES})

target_include_directories(doscore PRIVATE
    "${APP_JNI_DIR}"
    "${APP_JNI_DIR}/shared"
    "${HQX_DIR}"
)

target_compile_definitions(doscore PRIVATE
    __LIBRETRO__
    ANDROID
    NDEBUG
)

target_compile_options(doscore PRIVATE
    -O2
    -fvisibility=hidden
    -ffunction-sections -fdata-sections
    -fno-rtti
    -fno-exceptions
    -Wno-unused-parameter
    -Wno-unused-variable
    -Wno-sign-compare
    -Wno-write-strings
    -Wno-deprecated-declarations
)

# `dl` is required for dlopen() / dlsym() on the prebuilt dosbox_pure .so.
target_link_libraries(doscore log android dl)

# ---------------------------------------------------------------------------
# FBNeo (Arcade) — built as libfbneocore.so, which dlopen()s the prebuilt
# libfbneo_libretro_android.so from app/src/main/jniLibs/<abi>/ at runtime.
# Same dlopen pattern as DOSBox-Pure: avoids compiling the huge FBNeo source
# tree (and the uploaded snapshot doesn't include FBNeo's libretro port).
#
# The prebuilt .so comes from the libretro buildbot (or you can build it
# yourself by running `make -f Makefile.libretro platform=unix` inside the
# FBNeo source tree with the libretro subdir restored).
#
# Our JNI bridge (fbneo_bridge.cpp) + libretro frontend (fbneo_loader.cpp)
# drive the core through the standard retro_* API. Supports the HQX library
# for video filter upscalers. BIOS files (neogeo.zip, pgm.zip, etc.) are
# looked up by the core in the system directory — see NesApp.ensureFbNeoBios.
# ---------------------------------------------------------------------------
set(FBNEO_JNI_SOURCES
    "${APP_JNI_DIR}/fbneo_bridge.cpp"
    "${APP_JNI_DIR}/fbneo_loader.cpp"
    ${HQX_SOURCES}
)

add_library(fbneocore SHARED ${FBNEO_JNI_SOURCES})

target_include_directories(fbneocore PRIVATE
    "${APP_JNI_DIR}"
    "${APP_JNI_DIR}/shared"
    "${HQX_DIR}"
)

target_compile_definitions(fbneocore PRIVATE
    __LIBRETRO__
    ANDROID
    NDEBUG
)

target_compile_options(fbneocore PRIVATE
    -O2
    -fvisibility=hidden
    -ffunction-sections -fdata-sections
    -fno-rtti
    -fno-exceptions
    -Wno-unused-parameter
    -Wno-unused-variable
    -Wno-sign-compare
    -Wno-write-strings
    -Wno-deprecated-declarations
)

# `dl` is required for dlopen() / dlsym() on the prebuilt FBNeo .so.
target_link_libraries(fbneocore log android dl)

# ---------------------------------------------------------------------------
# Genesis-Plus-GX (SEGA MD / SMS / GG / SG / Mega-CD) — built as
# libgenesicore.so, which dlopen()s the prebuilt
# libgenesis_plus_gx_libretro_android.so from app/src/main/jniLibs/<abi>/.
# Same dlopen pattern as DOSBox-Pure / FBNeo.
#
# NOTE: Genesis-Plus-GX does NOT support the SEGA Saturn (SS). Saturn
# requires a separate Saturn core (Yabause / Mednafen). The MD platform
# covers MD / SMS / GG / SG / Mega-CD only.
#
# BIOS files (bios_CD_E.zip, bios_CD_J.zip, bios_CD_U.zip) for Mega-CD
# games are looked up by the core in the system directory — see
# NesApp.ensureGenesisBios.
# ---------------------------------------------------------------------------
set(GENESIS_JNI_SOURCES
    "${APP_JNI_DIR}/genesis_bridge.cpp"
    "${APP_JNI_DIR}/genesis_loader.cpp"
    ${HQX_SOURCES}
)

add_library(genesicore SHARED ${GENESIS_JNI_SOURCES})

target_include_directories(genesicore PRIVATE
    "${APP_JNI_DIR}"
    "${APP_JNI_DIR}/shared"
    "${HQX_DIR}"
)

target_compile_definitions(genesicore PRIVATE
    __LIBRETRO__
    ANDROID
    NDEBUG
)

target_compile_options(genesicore PRIVATE
    -O2
    -fvisibility=hidden
    -ffunction-sections -fdata-sections
    -fno-rtti
    -fno-exceptions
    -Wno-unused-parameter
    -Wno-unused-variable
    -Wno-sign-compare
    -Wno-write-strings
    -Wno-deprecated-declarations
)

# `dl` is required for dlopen() / dlsym() on the prebuilt Genesis-Plus-GX .so.
target_link_libraries(genesicore log android dl)

# ---------------------------------------------------------------------------
# Geargrafx (PC-Engine / TurboGrafx-16 / SuperGrafx / PCE-CD) — built as
# libpcecore.so, which dlopen()s the prebuilt
# libgeargrafx_libretro_android.so from app/src/main/jniLibs/<abi>/.
# Same dlopen pattern as DOSBox-Pure / FBNeo / Genesis-Plus-GX.
#
# BIOS files (syscard1/2/3.pce, gexpress.pce) for PCE-CD games are looked
# up by the core in the system directory — see NesApp.ensurePceBios.
# ---------------------------------------------------------------------------
set(PCE_JNI_SOURCES
    "${APP_JNI_DIR}/pce_bridge.cpp"
    "${APP_JNI_DIR}/pce_loader.cpp"
    ${HQX_SOURCES}
)

add_library(pcecore SHARED ${PCE_JNI_SOURCES})

target_include_directories(pcecore PRIVATE
    "${APP_JNI_DIR}"
    "${APP_JNI_DIR}/shared"
    "${HQX_DIR}"
)

target_compile_definitions(pcecore PRIVATE
    __LIBRETRO__
    ANDROID
    NDEBUG
)

target_compile_options(pcecore PRIVATE
    -O2
    -fvisibility=hidden
    -ffunction-sections -fdata-sections
    -fno-rtti
    -fno-exceptions
    -Wno-unused-parameter
    -Wno-unused-variable
    -Wno-sign-compare
    -Wno-write-strings
    -Wno-deprecated-declarations
)

# `dl` is required for dlopen() / dlsym() on the prebuilt Geargrafx .so.
target_link_libraries(pcecore log android dl)

# ---------------------------------------------------------------------------
# PCSX-ReARMed (Sony PlayStation 1) — built as libpsxcore.so, which dlopen()s
# the prebuilt libpcsx_rearmed_libretro_android.so from app/src/main/jniLibs/<abi>/.
# Same dlopen pattern as FBNeo / Genesis-Plus-GX / Geargrafx.
#
# The prebuilt .so comes from the libretro buildbot
# (https://buildbot.libretro.com/nightly/android/latest/arm64-v8a/).
#
# Our JNI bridge (psx_bridge.cpp) + libretro frontend (psx_loader.cpp) drive
# the core through the standard retro_* API. Supports the HQX library for
# video filter upscalers. PSX BIOS files (scph1000.bin / scph1001.bin /
# scph5500.bin etc.) are looked up by the core in the system directory — the
# user can place them there manually or via a future BIOS import UI.
# ---------------------------------------------------------------------------
set(PSX_JNI_SOURCES
    "${APP_JNI_DIR}/psx_bridge.cpp"
    "${APP_JNI_DIR}/psx_loader.cpp"
    ${HQX_SOURCES}
)

add_library(psxcore SHARED ${PSX_JNI_SOURCES})

target_include_directories(psxcore PRIVATE
    "${APP_JNI_DIR}"
    "${APP_JNI_DIR}/shared"
    "${HQX_DIR}"
)

target_compile_definitions(psxcore PRIVATE
    __LIBRETRO__
    ANDROID
    NDEBUG
)

target_compile_options(psxcore PRIVATE
    -O2
    -fvisibility=hidden
    -ffunction-sections -fdata-sections
    -fno-rtti
    -fno-exceptions
    -Wno-unused-parameter
    -Wno-unused-variable
    -Wno-sign-compare
    -Wno-write-strings
    -Wno-deprecated-declarations
)

# `dl` is required for dlopen() / dlsym() on the prebuilt PCSX-ReARMed .so.
target_link_libraries(psxcore log android dl)

# ---------------------------------------------------------------------------
# ARMSX2 (Sony PlayStation 2) — source-compiled emucore (PCSX2 fork with
# ARM64 JIT via vixl). Replaces the previous PCEE2 dlopen approach.
#
# Built from ARMSX2-master/platforms/android/app/src/main/cpp via
# add_subdirectory, producing libemucore_4k.so — a self-contained push-model
# JNI core (kr.co.iefriends.pcsx2.NativeApp) that:
#   - runs its own VM thread (runVMThread — blocking until VM exit)
#   - renders directly to the ANativeWindow passed via onNativeSurfaceChanged
#   - plays audio internally via Oboe (no external audio pull)
#   - takes input via setPadButtonForPort (PadKeyCode + analog range)
#   - takes settings via setSetting("EmuCore/GS/Renderer",...) + commitSettings()
#   - saves/loads state via saveStateToSlot/loadStateFromSlot
#
# ABI limitation: PCSX2 + vixl JIT requires arm64-v8a (aarch64).
# armeabi-v7a and x86_64 are skipped — no PS2 core on those ABIs.
#
# PS2 BIOS files go in <systemDir>/pcsx2/bios/ (e.g. scph10000.bin).
# ---------------------------------------------------------------------------
if(ANDROID_ABI STREQUAL "arm64-v8a")
    set(ARMSX2_ANDROID_CPP
        "${CMAKE_CURRENT_SOURCE_DIR}/../../ARMSX2-master/platforms/android/app/src/main/cpp"
        CACHE PATH "ARMSX2 Android native build root")

    # ARMSX2's cpp/CMakeLists.txt is a self-contained project that
    # add_subdirectory's common/, pcsx2/, 3rdparty/ from the ARMSX2 repo
    # root. It defines the emucore_4k shared library target (JNI SDK).
    # Placed last so ARMSX2's compile options (C++20, -fno-exceptions, etc.)
    # only affect its own subtree, not the GameBox targets above.
    #
    # Vulkan renderer: ARMSX2's Vulkan GS backend needs the shaderc
    # third_party deps (3rdparty/shaderc/third_party/{spirv-tools,
    # spirv-headers, glslang}) which the ARMSX2 source download does not
    # pack. They are now vendored into ARMSX2's cpp/3rdparty/shaderc/
    # third_party/ (plus the spirv-tools/external/spirv-headers symlink),
    # so USE_VULKAN defaults to ON here via BuildParameters. SearchForStuff
    # locates them with CMAKE_CURRENT_SOURCE_DIR (GameBox add_subdirectory
    # build: CMAKE_SOURCE_DIR would point at core/cmake).
    add_subdirectory("${ARMSX2_ANDROID_CPP}" "${CMAKE_BINARY_DIR}/armsx2")

    # ARMSX2's vendored 7-Zip lzma (pcsx2-lzma) compiles AesOpt.c with ARMv8
    # hardware-AES intrinsics (vaeseq_u8/vaesmcq_u8), but BuildParameters.cmake
    # adds -march=armv8-a for android/arm64 (no crypto). Clang ignores
    # per-function __attribute__((target("crypto"))) on AArch64, so the
    # always_inline intrinsics fail with "requires target feature 'aes'".
    # Enable crypto for this target only; target-level options land after the
    # directory-level -march above, so clang takes the last one.
    if(TARGET pcsx2-lzma)
        target_compile_options(pcsx2-lzma PRIVATE "-march=armv8-a+crypto")
    endif()
endif()

# ---------------------------------------------------------------------------
# Flycast (Sega Dreamcast / Naomi / Atomiswave) — built as libflycastcore.so,
# which dlopen()s the prebuilt libflycast_libretro_android.so from
# app/src/main/jniLibs/<abi>/ at runtime (same pattern as FBNeo / PCSX-ReARMed
# / Genesis-Plus-GX / Geargrafx). The prebuilt .so comes from the libretro
# buildbot (https://buildbot.libretro.com/nightly/android/latest/<abi>/).
#
# Unlike the 2D cores, flycast is a GPU core: it renders the Dreamcast's
# PowerVR2 output through OpenGL ES 3 (glsm) and signals frames via
# video_cb(RETRO_HW_FRAME_BUFFER_VALID). Our frontend (flycast_loader.cpp)
# therefore implements the libretro hardware-rendering protocol on top of
# EGL: GLES3 context on the ANativeWindow, an RGBA8+D24S8 FBO handed to the
# core via get_current_framebuffer, a texture compositor after retro_run and
# eglSwapBuffers. When no Surface is attached the context falls back to a
# pbuffer so captureFrame / CPU readback still work.
#
# BIOS / system files (inside <systemDir>/dc/):
#   dc_boot.bin + dc_flash.bin            — Dreamcast (required)
#   naomi.zip / atomiswave.zip            — Naomi / Atomiswave MAME BIOS sets
# Per-game VMU / naomi eeprom saves live in <saveDir>/reicast/ and
# <systemDir>/dc/data/ (created on demand by the core).
# ---------------------------------------------------------------------------
set(FLYCAST_JNI_SOURCES
    "${APP_JNI_DIR}/flycast_bridge.cpp"
    "${APP_JNI_DIR}/flycast_loader.cpp"
)

add_library(flycastcore SHARED ${FLYCAST_JNI_SOURCES})

target_include_directories(flycastcore PRIVATE
    "${APP_JNI_DIR}"
    "${APP_JNI_DIR}/shared"
)

target_compile_definitions(flycastcore PRIVATE
    __LIBRETRO__
    ANDROID
    NDEBUG
)

target_compile_options(flycastcore PRIVATE
    -O2
    -fvisibility=hidden
    -ffunction-sections -fdata-sections
    -fno-rtti
    -fno-exceptions
    -Wno-unused-parameter
    -Wno-unused-variable
    -Wno-sign-compare
    -Wno-write-strings
    -Wno-deprecated-declarations
)

# `dl` is required for dlopen()/dlsym() on the prebuilt flycast .so;
# EGL/GLESv3 for the hardware-rendering frontend, `android` for
# ANativeWindow.
target_link_libraries(flycastcore log android dl EGL GLESv3)


# ---------------------------------------------------------------------------
# melonDS (Nintendo DS / DSi) — built as libndscore.so, which links the
# melonDS core + libretro wrapper as a static library (melonds-core),
# compiled directly from source via core/melonds/CMakeLists.txt.
#
# The melonDS core is built as a static library (melonds-core) and linked
# directly into the ndscore JNI shared library, replacing the previous
# dlopen approach. This allows full control over compilation flags, JIT
# recompiler settings, and OpenGL ES integration.
#
# Our JNI bridge (nds_bridge.cpp) + libretro frontend (nds_loader.cpp) drive
# the core through the standard retro_* API. melonDS BIOS files (bios7.bin,
# bios9.bin, firmware.bin) are looked up by the core in the system directory.
# DSi mode additionally requires dsi_arm7.bin / dsi_bios7.bin / dsi_bios9.bin
# / dsi_firmware.bin / dsi_nand.bin.
# ---------------------------------------------------------------------------

# Build melonDS core + libretro wrapper as a static library.
# The melonds-core target exposes all necessary include directories
# and compile definitions through its PUBLIC interface.
add_subdirectory("${CMAKE_CURRENT_SOURCE_DIR}/../melonds" "${CMAKE_BINARY_DIR}/melonds-build")

set(NDS_JNI_SOURCES
    "${APP_JNI_DIR}/nds_bridge.cpp"
    "${APP_JNI_DIR}/nds_loader.cpp"
    ${HQX_SOURCES}
)

add_library(ndscore SHARED ${NDS_JNI_SOURCES})

target_include_directories(ndscore PRIVATE
    "${APP_JNI_DIR}"
    "${APP_JNI_DIR}/shared"
    "${HQX_DIR}"
)

# melonds-core provides compile definitions via its PUBLIC interface,
# but we also set minimal definitions needed by the JNI bridge.
target_compile_definitions(ndscore PRIVATE
    __LIBRETRO__
    ANDROID
    NDEBUG
)

target_compile_options(ndscore PRIVATE
    -O2
    -fvisibility=hidden
    -ffunction-sections -fdata-sections
    -fno-rtti
    -fno-exceptions
    -Wno-unused-parameter
    -Wno-unused-variable
    -Wno-sign-compare
    -Wno-write-strings
    -Wno-deprecated-declarations
)

# Link the melonDS static library. The melonds-core target brings in
# EGL + GLESv2 + log + android transitively through its PUBLIC link
# dependencies. `dl` is no longer needed since we no longer dlopen.
target_link_libraries(ndscore
    melonds-core
    log
    android
)

# ---------------------------------------------------------------------------
# J2ME native libraries (libjavam3g.so + libmicro3d.so)
# Converted from J2ME-Loader's Android.mk to CMake
# ---------------------------------------------------------------------------
set(J2ME_NATIVE_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../../app/src/main/cpp")

# libjavam3g.so — M3G/JSR-184 3D engine (GLES 1.1)
add_library(javam3g SHARED
    "${J2ME_NATIVE_DIR}/m3g/CSynchronization.cpp"
    "${J2ME_NATIVE_DIR}/m3g/m3g_android_java_api.cpp"
    "${J2ME_NATIVE_DIR}/m3g/src/m3g_core.c"
    "${J2ME_NATIVE_DIR}/m3g/src/m3g_android.cpp"
    "${J2ME_NATIVE_DIR}/m3g/src/m3g_android_gl.cpp"
)
target_include_directories(javam3g PRIVATE "${J2ME_NATIVE_DIR}/m3g/inc/")
target_compile_definitions(javam3g PRIVATE M3G_TARGET_ANDROID M3G_GL_ES_1_1)
target_compile_options(javam3g PRIVATE -O3 -Wno-unused-parameter -Wno-unused-variable -Wno-int-conversion -Wno-void-pointer-to-int-cast -Wno-int-to-pointer-cast -Wno-shift-negative-value -Wno-pointer-to-int-cast)
target_link_libraries(javam3g log EGL GLESv1_CM z jnigraphics)

# libmicro3d.so — Mascot Capsule 3D helper (GLES 2.0)
add_library(micro3d SHARED
    "${J2ME_NATIVE_DIR}/micro3d/src/utils.cpp"
)
target_include_directories(micro3d PRIVATE "${J2ME_NATIVE_DIR}/micro3d/inc")
target_link_libraries(micro3d log GLESv2 jnigraphics)
