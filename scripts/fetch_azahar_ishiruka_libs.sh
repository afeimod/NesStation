#!/usr/bin/env bash
# =============================================================================
# fetch_azahar_ishiruka_libs.sh — 提取 Azahar(3DS) 与 Ishiruka(NGC/WII) 核心库
#
# NesStation 3.7 集成的两个"独立模拟器形态"核心参照 DraStic（激烈）的模式：
# 预编译 .so 放入 app/src/main/jniLibs/arm64-v8a/，由 vendored 的原包名 JNI
# 契约类（org.citra.citra_emu.NativeLibrary / org.dolphinemu.ishiiruka.NativeLibrary）
# 绑定。本脚本从核心 APK 中提取 .so 并重命名到 jniLibs 约定位置。
#
# 用法：
#   # 方式一（推荐）：从你手头的 APK 提取（AzaharPlus_2125.1.2.apk / Ishiruka_01.APK）
#   ./scripts/fetch_azahar_ishiruka_libs.sh --apk3ds  /path/to/AzaharPlus_2125.1.2.apk
#   ./scripts/fetch_azahar_ishiruka_libs.sh --apkngcwii /path/to/Ishiruka_01.APK
#
#   # 方式二：直接下载官方 Azahar APK（AzaharPlus 是官方 fork，JNI 契约一致）
#   ./scripts/fetch_azahar_ishiruka_libs.sh --download-azahar 2125.1
#
# 两种方式可叠加；完成后 jniLibs/arm64-v8a/ 应出现：
#   libazahar.so       ← APK 内 libcitra-android.so（Azahar 3DS 核心）
#   libishiiruka.so    ← APK 内 libmain.so（Ishiiruka NGC/WII 核心）
#   （及其余依赖库原样拷入，如 libc++_shared.so / adrenotools 钩子链）
#
# 注意：仅支持 arm64-v8a（上游两个 Android 移植均为 64 位 only）。
# =============================================================================
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
JNI_DIR="$REPO_ROOT/app/src/main/jniLibs/arm64-v8a"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

mkdir -p "$JNI_DIR"

extract_apk() {
    local apk="$1" main_lib="$2" new_name="$3" tag="$4"
    if [[ ! -f "$apk" ]]; then
        echo "❌ APK 不存在: $apk"; exit 1
    fi
    echo "── [$tag] 解包 $apk"
    rm -rf "$TMP/$tag"; mkdir -p "$TMP/$tag"
    unzip -q -o "$apk" 'lib/arm64-v8a/*' -d "$TMP/$tag" 2>/dev/null || {
        echo "❌ $apk 中没有 lib/arm64-v8a/（请确认是 arm64 构建的 APK）"; exit 1
    }
    if [[ ! -f "$TMP/$tag/lib/arm64-v8a/$main_lib" ]]; then
        echo "❌ $apk 缺少 lib/arm64-v8a/$main_lib —— 这不是预期的核心 APK。"
        echo "   APK 内实际包含:"; ls "$TMP/$tag/lib/arm64-v8a/" 2>/dev/null || true
        exit 1
    fi
    # 主库重命名到 NesStation 约定名，其余依赖（libc++_shared 等）原样拷贝
    cp -v "$TMP/$tag/lib/arm64-v8a/$main_lib" "$JNI_DIR/$new_name"
    for so in "$TMP/$tag/lib/arm64-v8a/"*.so; do
        local base; base="$(basename "$so")"
        if [[ "$base" != "$main_lib" && ! -f "$JNI_DIR/$base" ]]; then
            cp -v "$so" "$JNI_DIR/$base"
        fi
    done
    echo "✅ [$tag] 完成：$JNI_DIR/$new_name"
}

download_azahar() {
    local ver="$1"
    # 官方 Azahar release 资产名随版本可能为 Azahar-<ver>-android.apk /
    # azahar-<ver>-android64.apk 等，逐个尝试。
    local base="https://github.com/azahar-emu/azahar/releases/download/v$ver"
    local candidates=(
        "$base/Azahar-$ver-android.apk"
        "$base/azahar-$ver-android.apk"
        "$base/Azahar-$ver-android64.apk"
    )
    local apk="$TMP/azahar.apk" ok=""
    for url in "${candidates[@]}"; do
        echo "── 尝试下载 $url"
        if curl -fSL --retry 3 -o "$apk" "$url"; then ok=1; break; fi
    done
    if [[ -z "$ok" ]]; then
        echo "❌ 自动下载失败（资产命名可能变化）。请到"
        echo "   https://github.com/azahar-emu/azahar/releases 手动下载 Android APK"
        echo "   后用 --apk3ds 方式提取。"
        exit 1
    fi
    extract_apk "$apk" "libcitra-android.so" "libazahar.so" "azahar"
}

case "${1:-}" in
    --apk3ds)
        [[ $# -ge 2 ]] || { echo "用法: $0 --apk3ds <AzaharPlus.apk>"; exit 1; }
        extract_apk "$2" "libcitra-android.so" "libazahar.so" "azahar"
        ;;
    --apkngcwii)
        [[ $# -ge 2 ]] || { echo "用法: $0 --apkngcwii <Ishiruka.apk>"; exit 1; }
        extract_apk "$2" "libmain.so" "libishiiruka.so" "ishiiruka"
        ;;
    --download-azahar)
        [[ $# -ge 2 ]] || { echo "用法: $0 --download-azahar <版本号 如 2125.1>"; exit 1; }
        download_azahar "$2"
        ;;
    *)
        echo "用法: $0 {--apk3ds <AzaharPlus.apk> | --apkngcwii <Ishiruka.apk> | --download-azahar <版本>}"
        exit 1
        ;;
esac

echo
echo "全部完成。jniLibs/arm64-v8a 当前内容："
ls -la "$JNI_DIR" | awk '{print $9, "("$5" bytes)"}' | tail -n +2
