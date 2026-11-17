# SPDX-License-Identifier: MIT
# CMake build for melonDS core + libretro wrapper (static library).
#
# Builds the melonDS emulator engine as a static library (melonds-core)
# that is linked directly into the ndscore JNI shared library, replacing
# the previous dlopen-based approach.
#
# Features enabled:
#   - OpenGL ES 2.0 hardware renderer (OGLRENDERER_ENABLED + HAVE_OPENGL)
#   - ARM64 JIT recompiler (JIT_ENABLED + ARCHITECTURE_ARM64)
#   - Threading support (HAVE_THREADS)
#   - WiFi emulation (HAVE_WIFI)
#   - Libretro frontend wrapper
#   - Teakra DSP emulation
#
# Reference: core/melonds/libretro/Makefile.common

cmake_minimum_required(VERSION 3.22.1)
project(melonds-core C CXX ASM)

set(CMAKE_C_STANDARD 11)
set(CMAKE_C_STANDARD_REQUIRED ON)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

# ---------------------------------------------------------------------------
# Paths
# ---------------------------------------------------------------------------
set(MELON_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../melonds_temp/melonDS-android-lib-master/src")
set(CORE_DIR  "${CMAKE_CURRENT_SOURCE_DIR}/libretro")
set(LIBRETRO_COMM "${CORE_DIR}/libretro-common")

message(STATUS "melonDS core source dir: ${MELON_DIR}")
message(STATUS "melonDS libretro dir:   ${CORE_DIR}")

# ---------------------------------------------------------------------------
# JIT: the melonDS recompiler only has x86_64 and aarch64 backends.
# On Android it is enabled only for arm64-v8a; for every other ABI
# (armeabi-v7a, x86, x86_64) we build without JIT_ENABLED and melonDS
# falls back to its interpreter. Mirrors Makefile.common's JIT_ARCH gate.
# ---------------------------------------------------------------------------
if(ANDROID_ABI STREQUAL "arm64-v8a")
    set(MELONDS_JIT_ENABLED ON)
else()
    set(MELONDS_JIT_ENABLED OFF)
endif()
message(STATUS "melonDS JIT recompiler enabled: ${MELONDS_JIT_ENABLED}")

if(NOT EXISTS "${MELON_DIR}/NDS.cpp")
    message(FATAL_ERROR "melonDS core sources not found at ${MELON_DIR}.\n"
        "Ensure the melonDS-android-lib-master zip is extracted to:\n"
        "  core/melonds_temp/melonDS-android-lib-master/")
endif()

# ---------------------------------------------------------------------------
# C sources — 1:1 from Makefile.common
# ---------------------------------------------------------------------------
set(MELONDS_C_SOURCES
    # Core support libraries
    "${MELON_DIR}/xxhash/xxhash.c"
    "${MELON_DIR}/tiny-AES-c/aes.c"
    "${MELON_DIR}/fatfs/diskio.c"
    "${MELON_DIR}/fatfs/ff.c"
    "${MELON_DIR}/fatfs/ffsystem.c"
    "${MELON_DIR}/fatfs/ffunicode.c"
    "${MELON_DIR}/sha1/sha1.c"
    "${MELON_DIR}/blip-buf/blip_buf.c"

    # libretro-common (always linked — memory_stream is used by Savestate.cpp)
    "${LIBRETRO_COMM}/streams/memory_stream.c"
    "${LIBRETRO_COMM}/compat/compat_strl.c"
    "${LIBRETRO_COMM}/compat/fopen_utf8.c"
    "${LIBRETRO_COMM}/compat/compat_posix_string.c"
    "${LIBRETRO_COMM}/compat/compat_strcasestr.c"
    "${LIBRETRO_COMM}/encodings/encoding_utf.c"
    "${LIBRETRO_COMM}/file/file_path.c"
    "${LIBRETRO_COMM}/streams/file_stream.c"
    "${LIBRETRO_COMM}/streams/file_stream_transforms.c"
    "${LIBRETRO_COMM}/string/stdstring.c"
    "${LIBRETRO_COMM}/vfs/vfs_implementation.c"
)

# ---------------------------------------------------------------------------
# OpenGL C sources (HAVE_OPENGL)
# ---------------------------------------------------------------------------
set(MELONDS_OPENGL_C_SOURCES
    "${LIBRETRO_COMM}/glsm/glsm.c"
    "${LIBRETRO_COMM}/glsym/rglgen.c"
    "${LIBRETRO_COMM}/glsym/glsym_gl.c"
    "${MELON_DIR}/frontend/glad/glad.c"
)

# ---------------------------------------------------------------------------
# Thread C sources (HAVE_THREADS)
# ---------------------------------------------------------------------------
set(MELONDS_THREAD_C_SOURCES
    "${LIBRETRO_COMM}/rthreads/rthreads.c"
    "${LIBRETRO_COMM}/rthreads/rsemaphore.c"
)

# ---------------------------------------------------------------------------
# C++ core sources — 1:1 from Makefile.common
# ---------------------------------------------------------------------------
set(MELONDS_CXX_CORE_SOURCES
    "${MELON_DIR}/NDS.cpp"
    "${MELON_DIR}/ARCodeFile.cpp"
    "${MELON_DIR}/ARDatabaseDAT.cpp"
    "${MELON_DIR}/AREngine.cpp"
    "${MELON_DIR}/ARM.cpp"
    "${MELON_DIR}/ARMInterpreter.cpp"
    "${MELON_DIR}/ARMInterpreter_ALU.cpp"
    "${MELON_DIR}/ARMInterpreter_Branch.cpp"
    "${MELON_DIR}/ARMInterpreter_LoadStore.cpp"
    "${MELON_DIR}/CP15.cpp"
    "${MELON_DIR}/CRC32.cpp"
    "${MELON_DIR}/DMA.cpp"
    "${MELON_DIR}/DMA_Timings.cpp"
    "${MELON_DIR}/DSi.cpp"
    "${MELON_DIR}/DSi_AES.cpp"
    "${MELON_DIR}/DSi_Camera.cpp"
    "${MELON_DIR}/DSi_DSP.cpp"
    "${MELON_DIR}/DSi_I2C.cpp"
    "${MELON_DIR}/DSi_I2S.cpp"
    "${MELON_DIR}/DSi_NAND.cpp"
    "${MELON_DIR}/DSi_NDMA.cpp"
    "${MELON_DIR}/DSi_NWifi.cpp"
    "${MELON_DIR}/DSi_SD.cpp"
    "${MELON_DIR}/DSi_SPI_TSC.cpp"
    "${MELON_DIR}/FATIO.cpp"
    "${MELON_DIR}/FATStorage.cpp"
    "${MELON_DIR}/FreeBIOS.cpp"
    "${MELON_DIR}/GBACart.cpp"
    "${MELON_DIR}/GBACartMotionPak.cpp"
    "${MELON_DIR}/GPU.cpp"
    "${MELON_DIR}/GPU2D.cpp"
    "${MELON_DIR}/GPU2D_Soft.cpp"
    "${MELON_DIR}/GPU3D.cpp"
    "${MELON_DIR}/GPU3D_Soft.cpp"
    "${MELON_DIR}/GPU3D_Texcache.cpp"
    "${MELON_DIR}/Mic.cpp"
    "${MELON_DIR}/NDSCart.cpp"
    "${MELON_DIR}/NDSCartR4.cpp"
    "${MELON_DIR}/ROMList.cpp"
    "${MELON_DIR}/RTC.cpp"
    "${MELON_DIR}/Savestate.cpp"
    "${MELON_DIR}/SPI.cpp"
    "${MELON_DIR}/SPI_Firmware.cpp"
    "${MELON_DIR}/SPU.cpp"
    "${MELON_DIR}/Utils.cpp"
    "${MELON_DIR}/Wifi.cpp"
    "${MELON_DIR}/WifiAP.cpp"
)

# Optional files from Makefile.common that may not exist in this snapshot
if(EXISTS "${MELON_DIR}/DSiCrypto.cpp")
    list(APPEND MELONDS_CXX_CORE_SOURCES "${MELON_DIR}/DSiCrypto.cpp")
endif()
if(EXISTS "${MELON_DIR}/frontend/Util_ROM.cpp")
    list(APPEND MELONDS_CXX_CORE_SOURCES "${MELON_DIR}/frontend/Util_ROM.cpp")
endif()

# ---------------------------------------------------------------------------
# OpenGL C++ sources (OGLRENDERER_ENABLED)
# ---------------------------------------------------------------------------
set(MELONDS_OPENGL_CXX_SOURCES
    "${MELON_DIR}/GPU_OpenGL.cpp"
    "${MELON_DIR}/GPU3D_OpenGL.cpp"
    "${MELON_DIR}/OpenGLSupport.cpp"
    "${MELON_DIR}/GPU3D_TexcacheOpenGL.cpp"
    "${MELON_DIR}/GPU3D_Compute.cpp"
)

# ---------------------------------------------------------------------------
# JIT sources (ARM64) — from Makefile.common JIT_ARCH=aarch64 section.
# Only added when building for arm64-v8a (see MELONDS_JIT_ENABLED above).
# ---------------------------------------------------------------------------
set(MELONDS_JIT_CXX_SOURCES "")
set(MELONDS_JIT_ASM_SOURCES "")
if(MELONDS_JIT_ENABLED)
    set(MELONDS_JIT_CXX_SOURCES
        "${MELON_DIR}/ARMJIT.cpp"
        "${MELON_DIR}/ARMJIT_Memory.cpp"
        "${MELON_DIR}/ARM_InstrInfo.cpp"
        "${MELON_DIR}/ARMJIT_Global.cpp"
        "${MELON_DIR}/dolphin/CommonFuncs.cpp"
        "${MELON_DIR}/dolphin/Arm64Emitter.cpp"
        "${MELON_DIR}/dolphin/MathUtil.cpp"
        "${MELON_DIR}/ARMJIT_A64/ARMJIT_Compiler.cpp"
        "${MELON_DIR}/ARMJIT_A64/ARMJIT_ALU.cpp"
        "${MELON_DIR}/ARMJIT_A64/ARMJIT_LoadStore.cpp"
        "${MELON_DIR}/ARMJIT_A64/ARMJIT_Branch.cpp"
    )

    set(MELONDS_JIT_ASM_SOURCES
        "${MELON_DIR}/ARMJIT_A64/ARMJIT_Linkage.S"
    )
endif()

# ---------------------------------------------------------------------------
# Libretro wrapper C++ sources
# ---------------------------------------------------------------------------
set(MELONDS_LIBRETRO_CXX_SOURCES
    "${CORE_DIR}/libretro.cpp"
    "${CORE_DIR}/config.cpp"
    "${CORE_DIR}/input.cpp"
    "${CORE_DIR}/platform.cpp"
    "${CORE_DIR}/screenlayout.cpp"
    "${CORE_DIR}/utils.cpp"
    "${CORE_DIR}/opengl.cpp"
)

# ---------------------------------------------------------------------------
# Teakra (DSP coprocessor emulation) C++ sources
# ---------------------------------------------------------------------------
set(MELONDS_TEAKRA_CXX_SOURCES
    "${MELON_DIR}/teakra/src/ahbm.cpp"
    "${MELON_DIR}/teakra/src/apbp.cpp"
    "${MELON_DIR}/teakra/src/btdmp.cpp"
    "${MELON_DIR}/teakra/src/disassembler_c.cpp"
    "${MELON_DIR}/teakra/src/disassembler.cpp"
    "${MELON_DIR}/teakra/src/dma.cpp"
    "${MELON_DIR}/teakra/src/memory_interface.cpp"
    "${MELON_DIR}/teakra/src/mmio.cpp"
    "${MELON_DIR}/teakra/src/parser.cpp"
    "${MELON_DIR}/teakra/src/processor.cpp"
    "${MELON_DIR}/teakra/src/teakra_c.cpp"
    "${MELON_DIR}/teakra/src/teakra.cpp"
    "${MELON_DIR}/teakra/src/timer.cpp"
)

# ---------------------------------------------------------------------------
# DSP_HLE (DS emulation of the DSP coprocessor via host CPU) C++ sources
# ---------------------------------------------------------------------------
set(MELONDS_DSP_HLE_CXX_SOURCES
    "${MELON_DIR}/DSP_HLE/UcodeBase.cpp"
    "${MELON_DIR}/DSP_HLE/AACUcode.cpp"
    "${MELON_DIR}/DSP_HLE/G711Ucode.cpp"
    "${MELON_DIR}/DSP_HLE/GraphicsUcode.cpp"
)

# ---------------------------------------------------------------------------
# Aggregate all sources
# ---------------------------------------------------------------------------
add_library(melonds-core STATIC
    ${MELONDS_C_SOURCES}
    ${MELONDS_OPENGL_C_SOURCES}
    ${MELONDS_THREAD_C_SOURCES}
    ${MELONDS_CXX_CORE_SOURCES}
    ${MELONDS_OPENGL_CXX_SOURCES}
    ${MELONDS_JIT_CXX_SOURCES}
    ${MELONDS_JIT_ASM_SOURCES}
    ${MELONDS_LIBRETRO_CXX_SOURCES}
    ${MELONDS_TEAKRA_CXX_SOURCES}
    ${MELONDS_DSP_HLE_CXX_SOURCES}
)

# ---------------------------------------------------------------------------
# Include directories
# ---------------------------------------------------------------------------
target_include_directories(melonds-core PUBLIC
    # libretro-common headers (needed by nds_loader.cpp and bridge)
    "${LIBRETRO_COMM}/include"

    # libretro wrapper directory (for libretro.h, input.h, etc.)
    "${CORE_DIR}"

    # melonDS core source root
    "${MELON_DIR}"

    # Teakra headers
    "${MELON_DIR}/teakra/include"
    "${MELON_DIR}/teakra/src"

    # Frontend (Util_ROM, ScreenLayout, etc.)
    "${MELON_DIR}/frontend"
    "${MELON_DIR}/frontend/glad"    # glad OpenGL loader headers

    # ARMJIT A64 headers (for JIT compiler)
    "${MELON_DIR}/ARMJIT_A64"

    # Dolphin/common headers (for JIT)
    "${MELON_DIR}/dolphin"
)

# ---------------------------------------------------------------------------
# Compile definitions — matches Makefile.common + Android profile
# ---------------------------------------------------------------------------
target_compile_definitions(melonds-core PRIVATE
    __LIBRETRO__                    # libretro API mode
    OGLRENDERER_ENABLED             # Enable OpenGL 3D renderer
    HAVE_OPENGL                     # libretro OpenGL support (enables symbol table in glsym_gl.h)
    HAVE_OPENGLES                   # Android uses GLES; guards out desktop-only GL calls in glsm.c
    HAVE_EGL                        # EGL support (Android GL context)
    HAVE_THREADS                    # Threading support
    HAVE_WIFI                       # WiFi emulation
    MELONDS_VERSION="1.1"           # melonDS version string
    NDEBUG                          # Release build (assertions off)
    ANDROID                         # Android platform
)

# The ARM64 JIT backend is only supported on aarch64 (see ARMJIT_Compiler.h).
if(MELONDS_JIT_ENABLED)
    target_compile_definitions(melonds-core PRIVATE
        ARCHITECTURE_ARM64          # ARM64 JIT architecture
        JIT_ENABLED                 # Enable JIT recompiler
    )
endif()

# ---------------------------------------------------------------------------
# Compile options
# ---------------------------------------------------------------------------
target_compile_options(melonds-core PRIVATE
    -O3
    -fvisibility=hidden
    -ffunction-sections
    -fdata-sections
    -Wno-unused-parameter
    -Wno-unused-variable
    -Wno-sign-compare
    -Wno-deprecated-declarations
)

# ---------------------------------------------------------------------------
# Link libraries
# ---------------------------------------------------------------------------
# EGL + GLESv2 for OpenGL ES rendering context (required by melonDS 3D
# renderer). log and android for Android platform integration.
target_link_libraries(melonds-core
    log
    android
    EGL
    GLESv2
)