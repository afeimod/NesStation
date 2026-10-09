#!/usr/bin/env python3
"""把 SDK NDK 的 x86_64 host 工具链包装成 Termux aarch64 工具。

SDK NDK 28.2 (linux-x86_64) 的 clang/ld/llvm-* 全是 x86_64 ELF，
Termux(aarch64) 无法执行 → externalNativeBuild configure 失败。
Termux 自带 clang(默认 target=aarch64-unknown-linux-android24)+全套 llvm 工具，
可交叉编译 Android aarch64。本脚本把 NDK prebuilt/bin 下的工具全部替换成
指向 Termux 工具的 wrapper（原二进制备份到 bin.x86_64.orig/）。

arch-api 型 wrapper（如 aarch64-linux-android24-clang）需要保留 target 语义：
wrapper 从 argv[0] 解析出 <arch>-linux-android<api> 并追加 --target。
"""
import os, shutil, sys

NDK = os.path.expanduser("~/android-sdk/ndk/28.2.13676358")
BIN = os.path.join(NDK, "toolchains/llvm/prebuilt/linux-x86_64/bin")
PREFIX = "/data/data/com.termux/files/usr/bin"
BACKUP = os.path.join(BIN, "..", "bin.x86_64.orig")

if os.path.exists(BACKUP):
    print("backup exists, skip copying")
else:
    os.makedirs(BACKUP, exist_ok=True)
    for name in os.listdir(BIN):
        src = os.path.join(BIN, name)
        if os.path.islink(src) or not os.path.isfile(src):
            continue
        shutil.copy2(src, os.path.join(BACKUP, name))
    print(f"backed up x86_64 binaries -> {BACKUP}")

# 与 Termux 工具名的映射（NDK 名 -> Termux 名）
MAP = {
    "clang-19": "clang",
    "clang": "clang",
    "clang++": "clang++",
    "ld": "ld.lld",
    "ld.lld": "ld.lld",
    "lld": "lld",
    "llvm-ar": "llvm-ar",
    "ar": "llvm-ar",
    "llvm-as": "llvm-as",
    "as": "llvm-as",
    "llvm-nm": "llvm-nm",
    "nm": "llvm-nm",
    "llvm-strip": "llvm-strip",
    "strip": "llvm-strip",
    "llvm-ranlib": "llvm-ranlib",
    "ranlib": "llvm-ranlib",
    "llvm-objcopy": "llvm-objcopy",
    "objcopy": "llvm-objcopy",
    "llvm-readelf": "llvm-readelf",
    "llvm-objdump": "llvm-objdump",
    "llvm-size": "llvm-size",
    "llvm-strings": "llvm-strings",
    "llvm-cov": "llvm-cov",
    "llvm-profdata": "llvm-profdata",
    "llvm-symbolizer": "llvm-symbolizer",
    "llvm-dwarfdump": "llvm-dwarfdump",
    "llvm-bcanalyzer": "llvm-bcanalyzer",
    "llvm-cvtres": "llvm-cvtres",
    "llvm-rc": "llvm-rc",
    "llvm-mc": "llvm-mc",
    "llvm-ml": "llvm-ml",
    "llvm-mt": "llvm-mt",
    "llvm-tblgen": "llvm-tblgen",
    "llvm-dlltool": "llvm-dlltool",
    "llvm-otool": "llvm-otool",
    "llvm-pdbutil": "llvm-pdbutil",
    "llvm-rtdyld": "llvm-rtdyld",
    "llvm-opt": "opt",
    "opt": "opt",
    "dsymutil": "dsymutil",
    "llvm-dis": "llvm-dis",
    "llvm-extract": "llvm-extract",
    "llvm-link": "llvm-link",
    "llvm-lto": "llvm-lto",
    "llvm-lto2": "llvm-lto2",
    "llvm-modextract": "llvm-modextract",
    "llvm-split": "llvm-split",
    "llvm-diff": "llvm-diff",
}

def is_target_exe(name):
    """形如 aarch64-linux-android24-clang(+)/armv7a-linux-androideabi24-clang 等"""
    return (name.count("-") >= 3
            and ("-linux-android" in name or "-linux-androideabi" in name)
            and name.endswith(("clang", "clang++")))

def target_from_name(name):
    # aarch64-linux-android24-clang / armv7a-linux-androideabi24-clang++ / x86_64-linux-android24-clang
    body = name[:-len("-clang")] if name.endswith("-clang") else name[:-len("-clang++")]
    return body  # e.g. aarch64-linux-android24

def wrapper_script(name, target=None):
    termux = MAP.get(name)
    if target:
        return f"""#!/bin/sh
# wrapper {name} -> Termux clang (target {target})
exec {PREFIX}/clang --target={target} "$@"
"""
    if termux is None:
        return None
    if name == "clang++" or (name.startswith("clang") and name.endswith("++")):
        termux = "clang++"
    if name == "clang-19":
        termux = "clang"
    return f"""#!/bin/sh
# wrapper {name} -> Termux {termux}
exec {PREFIX}/{termux} "$@"
"""

written = 0
skipped = []
for name in list(os.listdir(BIN)):
    path = os.path.join(BIN, name)
    is_link = os.path.islink(path)
    if is_link:
        continue  # symlink 指向 wrapper（clang->clang-19 等），保留
    if not os.path.isfile(path):
        continue
    # 判断是否为 ELF 可执行（x86_64 host）
    with open(path, "rb") as f:
        head = f.read(4)
    is_elf = head == b"\x7fELF"
    if not is_elf:
        continue
    content = None
    if is_target_exe(name):
        content = wrapper_script(name, target=target_from_name(name))
    else:
        content = wrapper_script(name)
    if content is None:
        skipped.append(name)
        continue
    os.chmod(path, 0o755)
    with open(path, "w") as f:
        f.write(content)
    os.chmod(path, 0o755)
    written += 1
    print(f"wrapped {name}")

print(f"\nwrapped {written} tools; skipped (no Termux equiv): {skipped}")
