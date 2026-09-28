#!/usr/bin/env python3
"""Disassemble a function (given as vaddr) from an ARM64 ELF, showing basic
block flow and branches. Simple linear sweep from the target address.

Usage: dump_func.py <so> <vaddr-hex> [length]
"""
import struct, sys
from capstone import Cs, CS_ARCH_ARM64, CS_MODE_ARM

so = sys.argv[1]
start = int(sys.argv[2], 16)
length = int(sys.argv[3], 16) if len(sys.argv) > 3 else 0x400

data = open(so, 'rb').read()
e_phoff = struct.unpack_from('<Q', data, 0x20)[0]
e_phentsize = struct.unpack_from('<H', data, 0x36)[0]
e_phnum = struct.unpack_from('<H', data, 0x38)[0]
segs = []
for i in range(e_phnum):
    off = e_phoff + i * e_phentsize
    p_type = struct.unpack_from('<I', data, off)[0]
    p_offset = struct.unpack_from('<Q', data, off + 8)[0]
    p_vaddr = struct.unpack_from('<Q', data, off + 16)[0]
    p_filesz = struct.unpack_from('<Q', data, off + 32)[0]
    if p_type == 1:
        segs.append((p_offset, p_vaddr, p_filesz))

def va_to_off(va):
    for soff, sv, sz in segs:
        if sv <= va < sv + sz:
            return soff + (va - sv)
    return None

def read_va(va, n):
    off = va_to_off(va)
    if off is None: return None
    return data[off:off + n]

code = read_va(start, length)
if not code:
    print('not mapped'); sys.exit(1)
md = Cs(CS_ARCH_ARM64, CS_MODE_ARM)
md.detail = True
for ins in md.disasm(code, start):
    print('%08x  %-28s %s' % (ins.address, ins.mnemonic, ins.op_str))