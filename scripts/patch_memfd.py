#!/usr/bin/env python3.13
"""
NesStation libishiiruka.so memfd patcher
========================================
Root cause: GrabSHMSegment uses open("/dev/ashmem") which SELinux blocks on
Android 11+ (neverallow untrusted_app ashmem_device). fd = -1 -> all
CreateView mmap()s fail -> MemoryMap_Setup fails -> SIGSEGV.

Fix (4 edits, all in .text, no ELF layout change):
  P1  code cave  : memfd_create(name, MFD_CLOEXEC) raw syscall stub (svc #0)
  P2  0x45dd24   : bl open@PLT          -> bl cave_stub
  P3a 0x45dd50   : mov w1,#ASHMEM_SET_SIZE -> mov x1, x20   (x20 = size arg)
  P3b 0x45dd54   : movk w1,#0x4008,lsl16   -> nop
  P3c 0x45dd60   : bl ioctl@PLT         -> bl ftruncate@PLT
      (the following tbnz at 0x45dd64 is KEPT: it now guards ftruncate,
       which returns 0 on memfd, so fd is stored correctly)

memfd backing file is created with size 0; ftruncate extends it so that
MAP_SHARED views never SIGBUS. ASHMEM_SET_NAME ioctl is left in place:
it returns ENOTTY on memfd and its result is never checked.
"""
import struct, sys
sys.path.insert(0, '/home/z/.local/lib/python3.13/site-packages')
import capstone

SRC = '/home/z/my-project/work/apkcheck/lib/arm64-v8a/libishiiruka.so'
DST = '/home/z/my-project/work/libishiiruka-memfd.so'

data = bytearray(open(SRC, 'rb').read())
print(f"loaded {len(data)} bytes")

# ---- constants ----
OPEN_PLT, IOCTL_PLT, FTRUNC_PLT = 0x9e6d0, 0x9f0b0, 0x9dbf0
SITE_OPEN    = 0x45dd24   # bl open@PLT
SITE_MOVW    = 0x45dd50   # mov w1, #0x7703
SITE_MOVK    = 0x45dd54   # movk w1, #0x4008, lsl #16
SITE_BL_IOCTL= 0x45dd60   # bl ioctl@PLT
NAME_VA      = 0x61fb53   # "dolphin-emu." (rodata, NUL-terminated, no '/')

NOP   = 0xD503201F
MOV_X1_X20 = 0xAA1403E1   # orr x1, xzr, x20

def rd32(va): return struct.unpack_from('<I', data, va)[0]
def wr32(va, w): struct.pack_into('<I', data, va, w)

def bl_from(site, target):
    off = (target - site) // 4
    assert -(1 << 25) <= off < (1 << 25), "BL out of range"
    return 0x94000000 | (off & 0x3FFFFFF)

# ---- sanity: verify original instructions ----
checks = [
    (SITE_OPEN,     ('bl', None)),      # bl open (dynamic target check below)
    (SITE_MOVW,     ('mov', 'w1, #0x7703')),
    (SITE_MOVK,     ('movk', 'w1, #0x4008, lsl #16')),
    (SITE_BL_IOCTL, ('bl', None)),
]
md = capstone.Cs(capstone.CS_ARCH_ARM64, capstone.CS_MODE_LITTLE_ENDIAN)
def dis1(va):
    for ins in md.disasm(bytes(data[va:va+4]), va):
        return ins
    return None

ins = dis1(SITE_OPEN)
assert ins.mnemonic == 'bl' and int(ins.op_str[1:], 16) == OPEN_PLT, f"unexpected @0x{SITE_OPEN:x}: {ins.mnemonic} {ins.op_str}"
ins = dis1(SITE_BL_IOCTL)
assert ins.mnemonic == 'bl' and int(ins.op_str[1:], 16) == IOCTL_PLT, f"unexpected @0x{SITE_BL_IOCTL:x}"
for va, mn, op in [(SITE_MOVW, 'mov', 'w1, #0x7703'), (SITE_MOVK, 'movk', 'w1, #0x4008, lsl #16')]:
    ins = dis1(va)
    assert ins.mnemonic == mn and ins.op_str == op, f"unexpected @0x{va:x}: {ins.mnemonic} {ins.op_str!r}"
print("[+] original instruction sanity checks passed")

# ---- P1: find a code cave (zero run >= 32B, 4-aligned, inside .text) ----
# parse section headers for .text bounds
e_shoff, = struct.unpack_from('<Q', data, 0x28)
e_shentsize, e_shnum, e_shstrndx = struct.unpack_from('<HHH', data, 0x3a)
sections = [struct.unpack_from('<IIQQQQIIQQ', bytes(data), e_shoff + i * e_shentsize) for i in range(e_shnum)]
shstr_off = sections[e_shstrndx][4]
def secname(n):
    end = data.index(b'\0', shstr_off + n)
    return data[shstr_off + n:end].decode()
text = next(s for s in sections if secname(s[0]) == '.text')
T_LO, T_OFF, T_SZ = text[3], text[4], text[5]
print(f"[+] .text vaddr 0x{T_LO:x} size 0x{T_SZ:x}")

cave = None
run_start = None
i = T_OFF
end = T_OFF + T_SZ
while i < end:
    if data[i] == 0:
        if run_start is None:
            run_start = i
    else:
        if run_start is not None and (i - run_start) >= 32:
            cave_va = T_LO + (run_start - T_OFF)
            cave_va = (cave_va + 3) & ~3
            cave = cave_va
            break
        run_start = None
    i += 1
assert cave, "no code cave found"
print(f"[+] code cave @ 0x{cave:x} (zero padding between functions)")

# ---- build stub ----
def adrp(rd, pc, target):
    pc_page, t_page = pc & ~0xFFF, target & ~0xFFF
    imm = (t_page - pc_page) >> 12          # 21-bit signed
    assert -(1 << 20) <= imm < (1 << 20), "adrp out of range"
    imm &= 0x1FFFFF
    immlo = imm & 0x3                       # imm bits 0-1
    immhi = (imm >> 2) & 0x7FFFF            # imm bits 2-20
    return (1 << 31) | (immlo << 29) | (0b10000 << 24) | (immhi << 5) | rd

stub = [
    adrp(16, cave, NAME_VA),          # adrp x16, page("dolphin-emu.")
    0x912D4C00 | (16),                # add x16, x16, #0xb53  -> careful: Rn must be x16 too
    0xAA1003E0,                       # mov x0, x16
    0x52800021,                       # mov w1, #1   (MFD_CLOEXEC)
    0x528022E8,                       # mov w8, #279 (__NR_memfd_create)
    0xD4000001,                       # svc #0
    0xD65F03C0,                       # ret
]
# fix add encoding: ADD imm12: sf=1 op=0 S=0 100010 sh=0 imm12 Rn Rd
stub[1] = 0x91000000 | (0xb53 << 10) | (16 << 5) | 16

for j, w in enumerate(stub):
    wr32(cave + j * 4, w)
print(f"[+] stub written @0x{cave:x}: " + " ".join(f"{w:08x}" for w in stub))

# verify stub decodes + adrp resolves to the right page
for j, w in enumerate(stub):
    ins = dis1(cave + j * 4)
    print(f"    0x{cave + j*4:x}: {ins.mnemonic:8s} {ins.op_str}")
ins = dis1(cave)
assert ins.mnemonic == 'adrp'
tgt = int(ins.op_str.split('#')[1], 16)
assert tgt == (NAME_VA & ~0xFFF), f"adrp resolves to 0x{tgt:x}, expected 0x{NAME_VA & ~0xFFF:x}"
print(f"[+] adrp target verified: 0x{tgt:x}")

# ensure no branch in .text lands inside the cave
cave_end = cave + len(stub) * 4
text_bytes = bytes(data[T_OFF:T_OFF + T_SZ])
bad = 0
for off in range(0, T_SZ - 4, 4):
    w, = struct.unpack_from('<I', text_bytes, off)
    op = w >> 26
    if op in (0x25, 0x05):  # BL / B
        imm = w & 0x3FFFFFF
        if imm & 0x2000000: imm -= 0x4000000
        t = T_LO + off + imm * 4
        if cave - 4 <= t < cave_end:
            print(f"    !! branch at 0x{T_LO+off:x} targets cave")
            bad += 1
assert bad == 0, "cave is branch target — unsafe"
print("[+] no branches target the cave region")

# ---- P2: redirect open -> stub ----
wr32(SITE_OPEN, bl_from(SITE_OPEN, cave))
# ---- P3: ioctl(ASHMEM_SET_SIZE) -> ftruncate(fd, size) ----
wr32(SITE_MOVW, MOV_X1_X20)
wr32(SITE_MOVK, NOP)
wr32(SITE_BL_IOCTL, bl_from(SITE_BL_IOCTL, FTRUNC_PLT))

# ---- verify patched sites ----
print("[+] patched sites:")
for va in (SITE_OPEN, SITE_MOVW, SITE_MOVK, SITE_BL_IOCTL, 0x45dd58, 0x45dd5c, 0x45dd64, 0x45dd68):
    ins = dis1(va)
    note = ''
    if ins.mnemonic == 'bl':
        t = int(ins.op_str[1:], 16)
        note = {cave: ' -> memfd stub', FTRUNC_PLT: ' -> ftruncate@PLT', OPEN_PLT: ' -> open@PLT', IOCTL_PLT: ' -> ioctl@PLT'}.get(t, '')
    print(f"    0x{va:x}: {ins.mnemonic:8s} {ins.op_str}{note}")

open(DST, 'wb').write(bytes(data))
print(f"[+] written {DST}")

import hashlib
print("MD5 original :", hashlib.md5(open(SRC,'rb').read()).hexdigest())
print("MD5 patched  :", hashlib.md5(bytes(data)).hexdigest())
