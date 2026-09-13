#!/usr/bin/env bash
# dcfix7 源码包说明：本 zip 为节省体积未包含 app/src/main/jniLibs/arm64-v8a/*.so
# （共 41 个预编译原生库，与 NesStation-3.6.3-dcfix7-arm64.apk 内的完全一致）。
# 恢复方法：把 APK 与本 zip 放同一目录后执行本脚本，或手动执行其中两行命令。
set -e
cd "$(dirname "$0")"
APK="${1:-NesStation-3.6.3-dcfix7-arm64.apk}"
if [ ! -f "$APK" ]; then
  echo "用法: $0 /path/to/NesStation-3.6.3-dcfix7-arm64.apk"
  exit 1
fi
mkdir -p app/src/main/jniLibs/arm64-v8a
unzip -j -o "$APK" 'lib/arm64-v8a/*.so' -d app/src/main/jniLibs/arm64-v8a
echo "OK: 41 个预编译 .so 已还原到 app/src/main/jniLibs/arm64-v8a/"
echo "快速出包(不重编 native): ./gradlew :app:assembleRelease -PabiFilter=arm64-v8a -PprebuiltNatives=true -PskipFlycastCore -PnoMinify=true"
echo "完整构建(重编全部原生): ./gradlew :app:assembleRelease -PabiFilter=arm64-v8a"
