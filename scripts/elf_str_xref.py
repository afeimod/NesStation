#!/usr/bin/env python3
"""Find code references (adrp/add) to a byte string in an ELF, then list the
functions (via xref to that address from code text) and optionally disassemble
them with capstone.

Usage: elf_str_xref.py <so> <ascii-string> [--height MAX]
"""
import struct, sys

so = sys.argv[1]
needle = sys.argv[2].encode()

data = open(so, 'rb').read()
# ELF64 little-endian
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
    if p_type == 1:  # PT_LOAD
        segs.append((p_offset, p_vaddr, p_filesz))

def va_to_off(va):
    for soff, sv, sz in segs:
        if sv <= va < sv + sz:
            return soff + (va - sv)
    return None

# find all occurrences
locs = []
start = 0
while True:
    i = data.find(needle, start)
    if i < 0: break
    locs.append(i)
    start = i + 1

print('occurrences of %r:' % needle, locs)
# map to vaddr
for i in locs:
    va = None
    for soff, sv, sz in segs:
        if soff <= i < soff + sz:
            va = sv + (i - soff)
            break
    if va is None: continue
    # scan code text for adrp (0x90 + imm19) then add (0x91000000...) targeting va
    # collect xrefs from code
    xrefs = []
    for soff, sv, sz in segs:
        # code likely RX segment; just scan memory for instruction patterns
        code = data[soff:soff+sz]
        for c in range(len(code) - 4):
            ins = struct.unpack_from('<I', code, c)[0]
            # adrp xd, label : (ins>>24 & 0x9f) == 0x90, imm = label>>12
            if (ins >> 24) & 0x9f == 0x90:
                imm = ((ins >> 5) & 0x7ffff) << 12
                if (ins >> 31) & 1: imm -= 1 << 32
                base = sv + c
                base = (base >> 12) << 12
                target = base + imm
                if target <= va < target + 0x1000:
                    xrefs.append((c, ins))
    if xrefs:
        print('  vaddr 0x%x :' % va)
        for c, ins in xrefs[:8]:
            print('    adrp at fileoff 0x%x (vaddr 0x%x) pc=%s' % (c, sv+c, hex(sv+c)))
    else:
        print('  vaddr 0x%x : (no direct adrp found in first pass)' % va)