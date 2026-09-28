#!/usr/bin/env python3
"""精读 libishiiruka.so 0xcac00-0xcb100：onGamePadMoveEvent 核心接收路径。
重点确认设备名匹配、state 条目、轴/按钮树、以及 float 阈值逻辑。"""
import struct
from capstone import Cs, CS_ARCH_ARM64, CS_MODE_ARM

SO = "/data/data/com.termux/files/home/nes/app/src/main/jniLibs/arm64-v8a/libishiiruka.so"
data = open(SO, "rb").read()

phoff = struct.unpack_from("<Q", data, 0x20)[0]
phentsize = struct.unpack_from("<H", data, 0x36)[0]
phnum = struct.unpack_from("<H", data, 0x38)[0]

vaddr = off = size = None
for i in range(phnum):
    p = data[phoff + i*phentsize: phoff + (i+1)*phentsize]
    p_type = struct.unpack_from("<I", p, 0)[0]
    p_flags = struct.unpack_from("<I", p, 4)[0]
    p_off = struct.unpack_from("<Q", p, 8)[0]
    p_vaddr = struct.unpack_from("<Q", p, 16)[0]
    p_filesz = struct.unpack_from("<Q", p, 32)[0]
    if p_type == 1 and p_flags & 4:
        vaddr, off, size = p_vaddr, p_off, p_filesz
        break

def va2off(va): return va - vaddr + off
def readbytes(va, n): o = va2off(va); return data[o:o+n]

def dump_func(va, length, label):
    code = readbytes(va, length)
    md = Cs(CS_ARCH_ARM64, CS_MODE_ARM)
    md.detail = True
    print(f"\n===== {label} @ 0x{va:x} len=0x{length:x} =====")
    for i in md.disasm(code, va):
        print(f"  0x{i.address:x}: {i.mnemonic:8s} {i.op_str}")

dump_func(0xcac00, 0x560, "onGamePadMoveEvent core (from 0xcac00)")