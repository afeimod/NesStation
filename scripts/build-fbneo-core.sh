#!/usr/bin/env bash
# ============================================================================
# build-fbneo-core.sh —— 重建 libfbneo_libretro_android.so（治本脚本）
#
# 解决问题（用户反馈）：
#   1. 三国战记3（kov3，IGS PGM2 世代）部分贴图错误
#   2. 大部分改版游戏（kov2ce/kov2hack/kov2fyyq/kov115u/kovshxs 等）
#      缺驱动或贴图异常
#
# 根因（已核实，见《修复说明-v52.md》第二节）：
#   NesStation 捆绑的 libfbneo_libretro_android.so 是较早的构建；作者公开
#   fork（afeimod/FBNeo，本脚本默认使用 commit a0467d9b）此后已合并关键修复：
#     · 52c94c99  pgm2: supports decryption drivers [taoenwen, b0l40]
#       （PGM2 解密驱动 —— kov3 精灵/图层解密，即“贴图修复”）
#     · 209a0af2  pgm2: Modify several DIP switch settings
#     · 7361879c  pgm2: fix dip settings for kof98umh
#     · 6d6bf8dd  d_snes: crashban hacks
#     · 以及大量 PGM1 改版驱动（kov2ce/kov2hack/kov2fyyq/kov115u/...）
#   这些都在核心 .so 里（C++ 驱动代码）—— 没有源码就无法打补丁，必须重编。
#
# 用法（Linux / WSL / CI，需 git + Android NDK r25+ 与 cmake 无关）：
#   ANDROID_NDK=/path/to/android-ndk-r26d ./build-fbneo-core.sh
# 产物：fbneo_libretro_android.so → 覆盖到
#   app/src/main/jniLibs/<abi>/libfbneo_libretro_android.so
#   （arm64-v8a 必装；armeabi-v7a / x86_64 按需）
#
# 脚本策略：
#   libretro 移植层在 libretro/FBNeo（Makefile.libretro + jni ndk-build）；
#   驱动修复在 afeimod/FBNeo。两者同源（finalburnneo/FBNeo）——把 afeimod
#   fork 的驱动目录（burn/drv/pgm、pgm2、igs + burn/devices + burn/sound 等
#   PGM 相关实现）覆盖进 libretro 树后编译。
#   ※ 若你的项目本身已有一套合并源码树，只需同步 afeimod/FBNeo 最新
#     master 的 src/burn/ 后按原流程重编即可，无需本脚本。
# ============================================================================
set -euo pipefail

FORK_REPO="${FORK_REPO:-https://github.com/afeimod/FBNeo.git}"
FORK_REF="${FORK_REF:-master}"
LIBRETRO_REPO="${LIBRETRO_REPO:-https://github.com/libretro/FBNeo.git}"
NDK="${ANDROID_NDK:?请设置 ANDROID_NDK=/path/to/android-ndk}"
WORK="${CORE_BUILD_DIR:-$(pwd)/fbneo-core-build}"

echo "==> 工作目录: $WORK"
mkdir -p "$WORK"; cd "$WORK"

# ----------------------------------------------------------------------------
# 1. 取源码（浅克隆；已有目录则跳过）
# ----------------------------------------------------------------------------
if [ ! -d libretro-FBNeo/.git ]; then
    echo "==> 克隆 libretro/FBNeo（libretro 移植层）"
    git clone --depth 1 "$LIBRETRO_REPO" libretro-FBNeo
fi
if [ ! -d afei-FBNeo/.git ]; then
    echo "==> 克隆 afeimod/FBNeo（驱动修复源）"
    git clone --depth 1 -b "$FORK_REF" "$FORK_REPO" afei-FBNeo
fi

# ----------------------------------------------------------------------------
# 2. 驱动层覆盖（PGM/PGM2/IGS 全量 —— fork 的驱动目录自包含）
# ----------------------------------------------------------------------------
echo "==> 覆盖驱动目录（pgm / pgm2 / igs / devices / snd）"
for d in src/burn/drv/pgm src/burn/drv/pgm2 src/burn/drv/igs src/burn/devices src/burn/snd; do
    if [ -d "afei-FBNeo/$d" ]; then
        mkdir -p "libretro-FBNeo/$d"
        cp -a "afei-FBNeo/$d/." "libretro-FBNeo/$d/"
        echo "    $d ✓"
    fi
done
# 驱动清单文件（FBNeo 用 generated 文件登记驱动表）
for f in src/burn/drv/drv_pgm.h src/burn/burnint.h src/burn/drv_config.h; do
    if [ -f "afei-FBNeo/$f" ]; then cp -a "afei-FBNeo/$f" "libretro-FBNeo/$f"; echo "    $f ✓"; fi
done

# ----------------------------------------------------------------------------
# 3. 编译（FBNeo 官方 Makefile.libretro 的 android 目标；产物同名 .so）
# ----------------------------------------------------------------------------
build_one() {
    local ABI="$1" TRIPLE="$2"
    echo "==> 编译 $ABI"
    ( cd libretro-FBNeo && make -f Makefile.libretro clean >/dev/null 2>&1 || true
      make -f Makefile.libretro -j"$(nproc)" platform=android \
           NDK="$NDK" NDK_ABI="$TRIPLE" BUILD="fbneo_libretro_android" )
    cp -f libretro-FBNeo/fbneo_libretro_android.so "libfbneo_libretro_android.$ABI.so"
    echo "    产物: libfbneo_libretro_android.$ABI.so"
}

build_one arm64-v8a arm64-v8a
if [ "${BUILD_V7A:-0}" = "1" ]; then build_one armeabi-v7a armeabi-v7a; fi
if [ "${BUILD_X86_64:-0}" = "1" ]; then build_one x86_64 x86_64; fi

# ----------------------------------------------------------------------------
# 4. 就位提示
# ----------------------------------------------------------------------------
cat <<EOF

==> 完成。把产物复制进项目（按 ABI）：
    cp libfbneo_libretro_android.arm64-v8a.so  <项目>/app/src/main/jniLibs/arm64-v8a/libfbneo_libretro_android.so

==> 验证要点（装进设备后）：
    · kov3 贴图：进游戏看人物/背景精灵是否正常（解密驱动修复）
    · 改版驱动：kov2ce / kov2hack / kov2fyyq / kov115u / kovshxs 等
      现在应出现在游戏列表（旧核心里没有这些驱动）
    · 若个别游戏报缺 ROM，把 afeimod 参考包 assets/defaults/roms/pgm.zip
      里的新条目（protection_data.u6 / svgcpb / Dmnfrnt ASIC）合进你的
      pgm.zip（v52 包内的 pgm.zip 已合并好，直接替换亦可）
EOF
