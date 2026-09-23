#!/bin/bash
# GameBox 服务器构建脚本
# 支持交叉编译 Linux / macOS / Windows
#
# 用法:
#   ./build.sh                     # 默认编译当前平台
#   ./build.sh linux amd64         # Linux amd64
#   ./build.sh windows amd64       # Windows amd64
#   ./build.sh windows arm64       # Windows arm64
#   ./build.sh darwin amd64        # macOS amd64
#   ./build.sh darwin arm64        # macOS arm64 (Apple Silicon)
#   ./build.sh all                 # 编译所有平台

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$SCRIPT_DIR"

OUTPUT_DIR="build"
mkdir -p "$OUTPUT_DIR"

build() {
  local os="$1"
  local arch="$2"
  local binary="gamebox-server"

  if [ "$os" = "windows" ]; then
    binary="gamebox-server.exe"
  fi

  echo "==> 正在编译: $os/$arch -> $OUTPUT_DIR/$binary"

  GOOS="$os" GOARCH="$arch" \
    go build -trimpath -ldflags "-s -w" -o "$OUTPUT_DIR/$binary" .

  echo "    产物: $(ls -lh "$OUTPUT_DIR/$binary" | awk '{print $5}')"
}

# 预设平台列表
ALL_PLATFORMS=(
  "linux amd64"
  "linux arm64"
  "windows amd64"
  "windows arm64"
  "darwin amd64"
  "darwin arm64"
)

if [ "$#" -eq 0 ]; then
  # 默认编译当前平台
  GOOS="$(go env GOOS)"
  GOARCH="$(go env GOARCH)"
  build "$GOOS" "$GOARCH"

elif [ "$1" = "all" ]; then
  for platform in "${ALL_PLATFORMS[@]}"; do
    # shellcheck disable=SC2086
    build $platform
  done

elif [ "$#" -eq 2 ]; then
  build "$1" "$2"

else
  echo "用法: $0 [os arch|all]"
  echo ""
  echo "示例:"
  echo "  $0                 # 编译当前平台"
  echo "  $0 windows amd64   # 编译 Windows amd64"
  echo "  $0 all             # 编译所有平台"
  exit 1
fi

echo "==> 完成！所有产物在 $OUTPUT_DIR/"
ls -lh "$OUTPUT_DIR/"