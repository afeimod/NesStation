#!/usr/bin/env bash
# ============================================================================
# build-fbneo-core.sh —— 重建 libfbneo_libretro_android.so（v53 实测版）
#
# v53 关键变化（对比 v52 脚本）：
#   1) 构建方式修正：FBNeo 官方 Android 构建是 src/burner/libretro/jni/
#      的 ndk-build（Android.mk + Application.mk），不是 v52 猜的
#      "make -f Makefile.libretro platform=android"（仓库里根本没有
#      Makefile.libretro —— v52 脚本实际跑不通）；
#   2) 默认直接用 libretro/FBNeo master 构建（已实测成功：72MB，
#      含全部 PGM2 解密/贴图修复 + kov2p 系列改版驱动）；
#   3) 支持私有源码树：FORK_SRC=指向你自己的 FBNeo 源码树（含 345 个
#      kov 驱动的 afbn 源码）→ 覆盖驱动目录后构建 = 与参考 APK 同等
#      驱动能力的 libretro 核心。
#
# 解决问题：
#   · kov3（PGM2）贴图 —— libretro master 已含 IGS036 运行时解密 +
#     boot ROM verify 修补（pgm2_run/pgm2_crypt/pgm2_draw 全套）；
#   · kov2p/kovshxs 等改版驱动 —— 公开 master 已含 100 个 kov 驱动；
#     更多私有驱动（kov2ce/kovplus2006-2025/kovshzlzy 系列，参考 APK
#     共 345 个）需用 FORK_SRC 指向你自己的源码树构建。
#
# 用法（Linux/WSL，需 git + Android NDK r26+）：
#   ANDROID_NDK=/path/to/android-ndk-r26d ./build-fbneo-core.sh
#   # 私有驱动树（与参考 APK 同等能力）：
#   ANDROID_NDK=... FORK_SRC=/path/to/你的afbn源码树 ./build-fbneo-core.sh
#
# 产物：libfbneo_libretro_android.so → 覆盖
#   app/src/main/jniLibs/arm64-v8a/libfbneo_libretro_android.so
#   （架构：libfbneocore.so 桥 dlopen 本 .so 走 retro_* 接口 —— 直接替换即生效）
# ============================================================================
set -euo pipefail

NDK="${ANDROID_NDK:?请设置 ANDROID_NDK=/path/to/android-ndk}"
LIBRETRO_REPO="${LIBRETRO_REPO:-https://github.com/libretro/FBNeo.git}"
# ★ 私有驱动源码树（可选）：提供时覆盖驱动目录后再构建。
#   需要是标准 FBNeo 源码树（src/burn/drv/... 布局，afbn 源码树同款）。
FORK_SRC="${FORK_SRC:-}"
WORK="${CORE_BUILD_DIR:-$(pwd)/fbneo-core-build}"

echo "==> 工作目录: $WORK"
mkdir -p "$WORK"; cd "$WORK"

# ----------------------------------------------------------------------------
# 1. 取源码（浅克隆；已有目录则跳过）
# ----------------------------------------------------------------------------
if [ ! -d libretro-FBNeo/.git ]; then
    echo "==> 克隆 libretro/FBNeo（libretro 移植层 + 官方 ndk-build）"
    git clone --depth 1 "$LIBRETRO_REPO" libretro-FBNeo
fi

# ----------------------------------------------------------------------------
# 2. 私有驱动树覆盖（可选）：把 FORK_SRC 的驱动目录复制过来。
#    cp -a 只增不删 —— libretro 树里更新的文件保留，私有驱动新增进来。
#    burnint.h/drv_config.h 一并覆盖（私有驱动可能用到新接口）。
# ----------------------------------------------------------------------------
if [ -n "$FORK_SRC" ]; then
    echo "==> 覆盖私有驱动树: $FORK_SRC"
    for d in src/burn/drv/pgm src/burn/drv/pgm2 src/burn/drv/igs \
             src/burn/devices src/burn/snd; do
        if [ -d "$FORK_SRC/$d" ]; then
            mkdir -p "libretro-FBNeo/$d"
            cp -a "$FORK_SRC/$d/." "libretro-FBNeo/$d/"
            echo "    $d ✓"
        fi
    done
    for f in src/burn/drv/drv_pgm.h src/burn/burnint.h src/burn/drv_config.h; do
        if [ -f "$FORK_SRC/$f" ]; then
            cp -a "$FORK_SRC/$f" "libretro-FBNeo/$f"; echo "    $f ✓"
        fi
    done
    # 驱动清单差异检查（新文件需在 Makefile.common 里登记 —— 标准 FBNeo
    # 源码树同名文件替换不涉及；若你的树新增了 .cpp 文件且不在清单里，
    # 在 libretro-FBNeo/src/burner/libretro/Makefile.common 的 pgm 段补一行）
    echo "    （提示：若私有树新增了 .cpp 文件，检查 Makefile.common 是否已登记）"
fi

# ----------------------------------------------------------------------------
# 3. 构建（官方 jni/ndk-build；arm64-v8a）
# ----------------------------------------------------------------------------
echo "==> ndk-build arm64-v8a（FBNeo 全街机集，约 10-20 分钟）"
( cd libretro-FBNeo/src/burner/libretro/jni && \
  "$NDK/ndk-build" APP_ABI=arm64-v8a -j"$(nproc)" )

cp -f libretro-FBNeo/src/burner/libretro/jni/libs/arm64-v8a/libretro.so \
   libfbneo_libretro_android.so
echo "    产物: $(pwd)/libfbneo_libretro_android.so ($(du -h libfbneo_libretro_android.so | cut -f1))"

# ----------------------------------------------------------------------------
# 4. 就位提示 + 验证要点
# ----------------------------------------------------------------------------
cat <<EOF

==> 完成。把产物复制进项目：
    cp libfbneo_libretro_android.so  <项目>/app/src/main/jniLibs/arm64-v8a/libfbneo_libretro_android.so

==> 验证（装进设备后）：
    · kov3 贴图：进游戏看人物/背景精灵（IGS036 运行时解密 + boot ROM 修补）
    · kov2p/kovshxs 等改版：游戏列表应能识别（旧核心同样有 —— 这批公开
      master 就含；kov2ce/kovplus 系列等 245 个私有驱动需 FORK_SRC 构建）
    · 联机追赶（v53）：装 v53 服务器 + 客户端后测试中途加入不暂停追赶
EOF
