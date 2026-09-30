#!/usr/bin/env python3
"""patch_azahar_config.py — libazahar.so 配置文件名二进制补丁（等长就地替换）。

背景（问题根因，用户实测"3DS 全部设置无效 / 分辨率倍数无效"）：
  libazahar.so 提取自 AzaharPlus_2125.1.2 APK，其 jni/config.cpp 把 Android
  配置文件名硬编码为 "config-azahar-25game.ini"（AzaharPlus 作者的每游戏
  配置名，字符串原样烤进 .so，且代码侧以**定长 24 字节**内联拼接）：

      android_config_loc = ConfigDir + "config-azahar-25game.ini";   // 上游为 "config.ini"

  而 NesStation 的 AzaharEngine.flushConfig() 一直写 <userDir>/config/config.ini
  —— 核心永远读不到 → **所有 UI 设置（分辨率倍数/着色器/3D/布局/New3DS…）
  全部无效**，这是"3ds 分辨率倍数设置无效"的第一根因（第二根因是
  resolution_factor 取值标度错位，见 PadLayoutStore/UI 侧修复）。

修复方式（无法重编 C++ 核心，只能等长二进制补丁）：
  把 .rodata 里的 "config-azahar-25game.ini\0"（24 字节 + \0）就地替换为
  "config.ini\0" + 13 个 0x00 填充。核心的拼接代码按**定长 24 字节**把这段
  字面量拷进 std::string，因此：
    - 路径字符串内容为 ".../config/config.ini\\0har-..."（内嵌 null）；
    - 一切文件操作（fopen / FileUtil::Exists / CreateFullPath /
      ReadFileToString / WriteStringToFile）都经 c_str()，在 null 处截断
      → 实际读写 "<userDir>/config/config.ini" —— 与 NesStation 写入位置
      完全一致，与上游 Azahar 官方行为（config.ini）也一致；
    - 拼接处 adrp/add 定位（0x1b83ba0 → 0xce82d5，实测唯一一处引用）不受
      影响：字节就地替换不改变任何地址/偏移/段大小，ELF 布局零变化；
    - 字符串范围内无共享后缀引用（合并字符串池检查过），尾部清零安全。

  已验证（本补丁落地前）：
    - strings -a libazahar.so | grep "config-azahar-25game.ini" 恰好 1 处；
    - ADRP+ADD 交叉引用扫描：指向字符串内部的引用仅 0x1b83ba0（起始处）；
      0xce82ee 的引用属于相邻的 " =" 字符串，与本串无关。

用法：
  python3 scripts/patch_azahar_config.py [so路径]
  默认：app/src/main/jniLibs/arm64-v8a/libazahar.so
  幂等：已打补丁（找不到旧串但存在 "config.ini\\0" 痕迹）时直接通过。
"""
import sys
import os
import hashlib

OLD = b"config-azahar-25game.ini"
NEW = b"config.ini"

DEFAULT_SO = os.path.join(
    os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
    "app", "src", "main", "jniLibs", "arm64-v8a", "libazahar.so")


def main() -> int:
    so_path = sys.argv[1] if len(sys.argv) > 1 else DEFAULT_SO
    if not os.path.isfile(so_path):
        print(f"❌ 文件不存在: {so_path}")
        return 1

    with open(so_path, "rb") as f:
        data = f.read()
    md5_before = hashlib.md5(data).hexdigest()

    count = data.count(OLD)
    if count == 0:
        # 幂等检查：等长区域已是 "config.ini\0" + 零填充
        probe = NEW + b"\0" + b"\x00" * (len(OLD) - len(NEW))
        if probe in data:
            print(f"✅ 已打过补丁（无需变更）: {so_path}")
            print(f"   md5 = {md5_before}")
            return 0
        print(f"❌ 未找到目标字符串 config-azahar-25game.ini，也不是已补丁状态")
        print("   （so 可能来自不同的 Azahar 构建）")
        return 1
    if count > 1:
        print(f"❌ 目标字符串出现 {count} 次（预期 1 次）——为安全起见放弃补丁")
        return 1

    off = data.find(OLD)
    old_bytes = data[off:off + len(OLD) + 1]  # 含结尾 \0
    if old_bytes != OLD + b"\x00":
        print("❌ 字符串结尾不是预期的 \\0 —— 放弃补丁")
        return 1

    # 等长替换：NEW + '\0' + 零填充（保持总长 = len(OLD) + 1）
    replacement = NEW + b"\0" + b"\x00" * (len(OLD) - len(NEW))
    patched = data[:off] + replacement + data[off + len(OLD) + 1:]
    assert len(patched) == len(data), "补丁必须保持文件长度不变"

    with open(so_path, "wb") as f:
        f.write(patched)

    md5_after = hashlib.md5(patched).hexdigest()
    print(f"✅ 补丁完成: {so_path}")
    print(f"   偏移 0x{off:x}: {OLD.decode()} → {NEW.decode()}（定长区域，尾随清零）")
    print(f"   文件大小: {len(data)} → {len(patched)} (不变)")
    print(f"   md5: {md5_before} → {md5_after}")
    # 自检：新串可被 strings 语义读到，旧串不再存在
    assert patched.count(OLD) == 0
    assert NEW + b"\x00" in patched[off:off + len(OLD) + 1]
    print("   自检通过（旧串清除，新串就位）")
    return 0


if __name__ == "__main__":
    sys.exit(main())
