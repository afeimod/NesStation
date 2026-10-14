#!/usr/bin/env bash
# ============================================================================
# fetch_cemu_submodules.sh —— 拉取 Cemu（Wii U）核心的 vcpkg 子模块
#
# Cemu-AZ 源码树已移植到 core/cemu/。除 vcpkg 外的全部子模块
# （Vulkan-Headers / ZArchive / cubeb / imgui / libadrenotools /
#  metal-cpp / xbyak_aarch64）已随集成包附带源码快照，无需联网。
#
# vcpkg 体积大（含完整 git 历史，builtin-baseline 解析依赖提交历史），
# 无法随 zip 分发，需要本脚本按上游 .gitmodules 记录的固定 commit 拉取：
#   dependencies/vcpkg @ 9849e070ad4c3383890fb1c80ae0c54325e2484a
#
# 用法（在 NesStation 工程根目录）：
#   ./scripts/fetch_cemu_submodules.sh          # 常规：拉 vcpkg
#   ./scripts/fetch_cemu_submodules.sh --all    # 全量：连其余子模块也重新拉取
# ============================================================================
set -e

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
CEMU_DEPS="$ROOT_DIR/core/cemu/dependencies"
VCPKG_COMMIT="9849e070ad4c3383890fb1c80ae0c54325e2484a"   # 上游 .gitmodules 固定值

# 其余子模块（固定 commit = afeimod/Cemu-AZ 仓库 git ls-tree 记录）
declare -A SUBMODULES=(
  [Vulkan-Headers]="https://github.com/KhronosGroup/Vulkan-Headers.git 01393c3df0e5285b54ee6527466513f9e614be94"
  [ZArchive]="https://github.com/Exzap/ZArchive.git 965b66c8d67b6b7e30fd63b3b75aa91a99ff303b"
  [cubeb]="https://github.com/mozilla/cubeb.git 2071354a69aca7ed6df3b4222e305746c2113f60"
  [imgui]="https://github.com/ocornut/imgui.git f65bcf481ab34cd07d3909aab1479f409fa79f2f"
  [libadrenotools]="https://github.com/bylaws/libadrenotools.git 8fae8ce254dfc1344527e05301e43f37dea2df80"
  [metal-cpp]="https://github.com/bkaradzic/metal-cpp.git a63bd172ddcba73a3d87ca32032b66ad41ddb9a6"
  [xbyak_aarch64]="https://github.com/fujitsu/xbyak_aarch64 904b8923457f3ec0d6f82ea2d6832a792851194d"
)

clone_at() {
  local name="$1" url="$2" commit="$3" dest="$4"
  echo "[fetch] $name @ ${commit:0:10} ..."
  rm -rf "$dest"
  for i in 1 2 3; do
    if git clone "$url" "$dest"; then break; fi
    echo "  克隆失败，重试 $i/3 ..."
    rm -rf "$dest"; sleep 3
    [ "$i" = "3" ] && { echo "错误：无法克隆 $name（检查网络）"; exit 1; }
  done
  (cd "$dest" && git checkout -q "$commit")
  echo "[ok] $name"
}

# ---- vcpkg（必需） ----
if [ -e "$CEMU_DEPS/vcpkg/.git" ] || [ -e "$CEMU_DEPS/vcpkg/scripts/buildsystems/vcpkg.cmake" ]; then
  echo "[skip] vcpkg 已存在（core/cemu/dependencies/vcpkg）"
else
  clone_at vcpkg https://github.com/microsoft/vcpkg.git "$VCPKG_COMMIT" "$CEMU_DEPS/vcpkg"
  echo "[info] vcpkg 拉取完成（保留 .git —— baseline 解析需要提交历史）"
fi

# ---- 其余子模块（默认已随包附带；--all 时强制重拉） ----
if [ "$1" = "--all" ]; then
  for name in "${!SUBMODULES[@]}"; do
    read -r url commit <<< "${SUBMODULES[$name]}"
    clone_at "$name" "$url" "$commit" "$CEMU_DEPS/$name"
    # 快照形式即可（无需 .git 历史）
    rm -rf "$CEMU_DEPS/$name/.git"
  done
else
  for name in "${!SUBMODULES[@]}"; do
    if [ ! -d "$CEMU_DEPS/$name" ]; then
      echo "[miss] $name 缺失，单独补拉 ..."
      read -r url commit <<< "${SUBMODULES[$name]}"
      clone_at "$name" "$url" "$commit" "$CEMU_DEPS/$name"
      rm -rf "$CEMU_DEPS/$name/.git"
    fi
  done
fi

echo ""
echo "全部就绪。现在可以构建："
echo "  ./gradlew assembleDebug -PabiFilter=arm64-v8a"
echo "（首次构建 vcpkg 需编译 Boost/OpenSSL 等依赖，耗时较长属正常现象）"
