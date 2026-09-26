#!/usr/bin/env bash
# =============================================================================
# fetch_ps2_bios.sh — 把 PS2 BIOS 文件打包进 APK assets
#
# 用途：实现 "PS2 默认 BIOS 放 assets 自动识别"（用户零导入）。
#   把你手头合法取得的 PS2 BIOS dump（scph10000.bin / scph39001.bin /
#   scph70004.bin 等，任一完整 4MB dump 均可）拷入
#   app/src/main/assets/ps2/bios/，之后正常构建 APK：
#
#     ./scripts/fetch_ps2_bios.sh /path/to/scph39001.bin [更多文件...]
#     ./gradlew assembleRelease
#
#   运行时机制（无需任何配置）：
#     NesApp.ensurePs2Bios() 首启把 assets/ps2/bios/* 安装到
#     <filesDir>/ps2/pcsx2/bios/；PCSX2 (ARMSX2) LoadBIOS() 未配置
#     BIOS 时自动 FindBiosImage() 扫描该目录识别选用 —— 与 DC BIOS
#     (assets/dc/) 同一机制。
#
# 版权：PS2 BIOS 为 Sony 专有固件。请自行确保你拥有合法使用与分发权利。
# =============================================================================
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DEST="$REPO_ROOT/app/src/main/assets/ps2/bios"

if [[ $# -eq 0 ]]; then
    echo "用法: $0 <bios文件> [更多bios文件...]"
    echo "示例: $0 ~/Downloads/scph39001.bin"
    echo "目标: $DEST"
    exit 1
fi

mkdir -p "$DEST"

count=0
for f in "$@"; do
    if [[ ! -f "$f" ]]; then
        echo "❌ 文件不存在: $f"; exit 1
    fi
    size=$(stat -c %s "$f" 2>/dev/null || stat -f %z "$f")
    # PCSX2 IsBIOS() 要求 MIN_BIOS_SIZE..MAX_BIOS_SIZE（约 4MB 区间）——
    # 明显过小的文件（如说明文本 / 损坏 dump）直接拒绝打包。
    if (( size < 1000000 )); then
        echo "❌ $f 大小 ${size}B 过小（PS2 BIOS 应约 4MB），已跳过"
        continue
    fi
    cp -v "$f" "$DEST/"
    count=$((count+1))
done

echo ""
echo "✅ 已打包 $count 个 BIOS 文件到 $DEST"
echo "   下一步: ./gradlew assembleRelease —— 用户安装后无需导入即可玩 PS2。"
