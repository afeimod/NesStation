#!/usr/bin/env bash
# build_android.sh — Build Flycast libretro core for Android (arm64-v8a)
# Produces libflycast_libretro_android.so with NESSTATION_DC_SAFE_MEM patches.
#
# Usage:
#   ./core/flycast/scripts/build_android.sh [--abi arm64-v8a]
#
# Requires: Android NDK (ANDROID_HOME or ANDROID_NDK_HOME), CMake >= 3.22.1

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/../../.." && pwd)"
FLYCAST_DIR="$SCRIPT_DIR/.."
OUTPUT_DIR="$PROJECT_ROOT/app/src/main/jniLibs"

ABI="arm64-v8a"
while [[ $# -gt 0 ]]; do
    case "$1" in
        --abi) ABI="$2"; shift 2 ;;
        *) echo "Unknown option: $1"; exit 1 ;;
    esac
done

# Locate Android SDK / NDK
if [[ -z "${ANDROID_HOME:-}" && -z "${ANDROID_NDK_HOME:-}" ]]; then
    echo "ERROR: ANDROID_HOME or ANDROID_NDK_HOME must be set."
    exit 1
fi

NDK_HOME="${ANDROID_NDK_HOME:-${ANDROID_HOME}/ndk/28.2.13676358}"
if [[ ! -d "$NDK_HOME" ]]; then
    # Try common SDK locations
    for candidate in \
        "$ANDROID_HOME/ndk/28.2.13676358" \
        "$ANDROID_HOME/ndk/27.0.12077973" \
        "$ANDROID_HOME/ndk/26.3.11579264" \
        "$ANDROID_HOME/ndk-bundle"; do
        if [[ -d "$candidate" ]]; then
            NDK_HOME="$candidate"
            break
        fi
    done
fi

if [[ ! -d "$NDK_HOME" ]]; then
    echo "ERROR: NDK not found under $ANDROID_HOME"
    exit 1
fi

echo "NDK: $NDK_HOME"
echo "ABI: $ABI"

BUILD_DIR="$PROJECT_ROOT/build/flycast_core_${ABI}"
mkdir -p "$BUILD_DIR"

cmake -S "$FLYCAST_DIR" -B "$BUILD_DIR" \
    -DCMAKE_TOOLCHAIN_FILE="$NDK_HOME/build/cmake/android.toolchain.cmake" \
    -DANDROID_ABI="$ABI" \
    -DANDROID_PLATFORM=android-24 \
    -DANDROID_STL=c++_static \
    -DCMAKE_BUILD_TYPE=Release \
    -DLIBRETRO=ON \
    -DUSE_GLES=ON \
    -DUSE_VULKAN=OFF \
    -DUSE_DX9=OFF \
    -DUSE_DX11=OFF \
    -DUSE_BREAKPAD=OFF \
    -DUSE_LUA=OFF \
    -DBUILD_TESTING=OFF

cmake --build "$BUILD_DIR" --target flycast_libretro -j"$(nproc)"

# The output shared library is named flycast_libretro.so; rename to match
# the dlopen() name expected by flycast_loader.cpp.
OUTPUT_LIB="$BUILD_DIR/libflycast_libretro.so"
FINAL_NAME="libflycast_libretro_android.so"

mkdir -p "$OUTPUT_DIR/$ABI"
cp "$OUTPUT_LIB" "$OUTPUT_DIR/$ABI/$FINAL_NAME"
# Strip debug symbols to reduce size
"$NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-strip" \
    --strip-unneeded "$OUTPUT_DIR/$ABI/$FINAL_NAME" 2>/dev/null || true

echo "Done: $OUTPUT_DIR/$ABI/$FINAL_NAME"
