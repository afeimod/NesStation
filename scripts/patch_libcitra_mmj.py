#!/usr/bin/env python3
"""
Patch libcitra_mmj.so (Citra_MMJ_20250220 libmain.so) to neutralize the
package-whitelist degradation blocks in the boot body.

Background (reverse engineering findings):
- The core's boot body runs a 4-stage whitelist:
    check0: settings[0x490] == 9-byte constant
    check1: package name [0x478] in {"org.citra.emu", "com.antutu.ABenchMark"}
    check2: magic u64 [0x4f0] == 0x00d660c4f937902a
    check3: string [0x4a8] == "Citra"
  On any failure a "degradation block" force-clears a group of runtime flags:
    [0x268]=0, [0x00]=1(restricted), [0x408]=0, [0x3fd]=0, [0x3ff]=0,
    [0x2a8]=0, [0x418]=0(+string@0x420 cleared)
  For non-original packages (e.g. NesStation) this always triggers and
  changes the renderer/feature configuration vs the original APK.
- Fix: NOP all four degradation blocks (0x267534/0x267630/0x267684/0x267708).
  Behaviour then equals the original whitelisted APK.
- Force the renderer factory GL path (never Vulkan), two-stage:
  v1: 0x3d4fac `cbz w8, .+0x24` -> `b .+0x24` (skip capability check,
      unconditional use_gles test) — Vulkan renderer has no pp-shader.
  v2: 0x3d4fec `mov w0,#0x1a8` (Vulkan alloc) -> `b 0x3d5004` + NOP the
      5-instruction Vulkan allocation block.  Even when use_gles!=0 and the
      virtual-method capability bit returns 0 (would choose Vulkan), we now
      always reach the OpenGL renderer (0x3d5004 -> alloc 0x258 ->
      ctor 0x4251a0) which contains the pp-shader loader (0x4254c4/0x4255d0).
      use_gles!=0 then selects the GLES subobject (0x426a90) at 0x425378.
"""
import struct, sys, hashlib, os

ROOT = os.path.dirname(os.path.abspath(__file__))
# default: current checkout's jniLibs. Override with
#   patch_libcitra_mmj.py <src_so> [<dst_so>]
default = os.path.join(ROOT, '..', 'app', 'src', 'main', 'jniLibs', 'arm64-v8a', 'libcitra_mmj.so')
SRC = sys.argv[1] if len(sys.argv) > 1 else default
DST = sys.argv[2] if len(sys.argv) > 2 else SRC

NOP = 0xD503201F

BLOCKS = {
    'B0': (0x267534, 0x26756C),
    'BA': (0x267630, 0x267668),
    'BB': (0x267684, 0x2676BC),
    'BC': (0x267708, 0x267730),
}

# expected instruction words at block entries (sanity)
EXPECT = {
    0x267534: 0x52800028,  # mov w8, #1
    0x267630: 0x52800028,
    0x267684: 0x52800028,
    0x267708: 0x52800028,
    0x3d4fac: 0x34000128,  # cbz w8, .+0x24
    0x3d4fec: 0x52803500,  # mov w0, #0x1a8  (Vulkan alloc size)
}

with open(SRC, 'rb') as f:
    data = bytearray(f.read())

def rd(vaddr):
    return struct.unpack_from('<I', data, vaddr)[0]

def wr(vaddr, word):
    struct.pack_into('<I', data, vaddr, word)

print("== sanity: entry words ==")
for a, e in EXPECT.items():
    w = rd(a)
    print(f"  {a:#x}: {w:08x} {'OK' if e is None or w == e else 'MISMATCH!!'}")
    if e is not None and w != e:
        sys.exit("unexpected instruction, abort")

# full-word check of the four blocks: every word must be one of the observed
# benign patterns (mov/strb/str/ldrb/strh/tbnz/b). We simply print them.
print("\n== block contents ==")
for name, (lo, hi) in BLOCKS.items():
    seq = [rd(a) for a in range(lo, hi, 4)]
    print(f"  {name} {lo:#x}-{hi:#x}: {' '.join(f'{w:08x}' for w in seq)}")

# scan whole .text for branches targeting inside blocks (excluding known)
print("\n== branch-target scan ==")
text_start, text_end = 0x254130, 0x254130 + 0x36f94c
def branch_target(pc, w):
    op = w >> 24
    if (w & 0xFC000000) == 0x14000000 or (w & 0xFC000000) == 0x94000000:  # B / BL
        imm = w & 0x03FFFFFF
        if imm & 0x02000000: imm -= 0x04000000
        return pc + imm * 4
    if (w & 0xFF000010) == 0x54000000:  # B.cond
        imm = (w >> 5) & 0x7FFFF
        if imm & 0x40000: imm -= 0x80000
        return pc + imm * 4
    if (w & 0x7E000000) == 0x34000000 or (w & 0x7E000000) == 0x36000000:  # CBZ/CBNZ/TBZ/TBNZ
        if (w & 0x7E000000) in (0x34000000, 0x35000000, 0x36000000, 0x37000000):
            if (w & 0x7F000000) in (0x34000000, 0x35000000):
                imm = (w >> 5) & 0x7FFFF
                if imm & 0x40000: imm -= 0x80000
                return pc + imm * 4
            if (w & 0x7F000000) in (0x36000000, 0x37000000):
                imm = (w >> 5) & 0x3FFF
                if imm & 0x2000: imm -= 0x4000
                return pc + imm * 4
    return None

KNOWN = {
    0x2674fc, 0x2675d0, 0x26758c, 0x2676d4,  # entries via b.ne
    0x267554, 0x26755c,                      # B0 internal
    0x267650, 0x267658,                      # BA internal
    0x2676a4, 0x2676ac,                      # BB internal
    0x267728,                                # BC internal (tbnz -> 0x267738 outside)
}
problems = 0
for pc in range(text_start, text_end, 4):
    w = rd(pc)
    t = branch_target(pc, w)
    if t is None:
        continue
    for name, (lo, hi) in BLOCKS.items():
        if lo <= t < hi and pc not in KNOWN and not (lo <= pc < hi):
            print(f"  !! external branch {pc:#x} -> {t:#x} ({name})")
            problems += 1
print(f"  external branch problems: {problems}")
if problems:
    sys.exit("external branches into blocks — manual review needed")

print("\n== applying NOPs ==")
for name, (lo, hi) in BLOCKS.items():
    for a in range(lo, hi, 4):
        wr(a, NOP)
    print(f"  {name}: {hi-lo:#x} bytes NOPed")

# factory v1: force GL (never Vulkan): cbz w8,+9 -> b +9
w = rd(0x3d4fac)
print(f"\nfactory 0x3d4fac before: {w:08x}")
assert (w & 0xFF000000) == 0x34000000, "expected cbz"
wr(0x3d4fac, 0x14000009)
print(f"factory 0x3d4fac after:  {rd(0x3d4fac):08x} (b .+0x24)")

# factory v2: never run the Vulkan renderer ctor even if use_gles!=0 and the
# virtual-method capability bit is clear -> always fall into OpenGL (0x3d5004)
print(f"\nfactory v2 0x3d4fec before: {rd(0x3d4fec):08x}")
wr(0x3d4fec, 0x14000006)  # b 0x3d5004
for a in (0x3d4ff0, 0x3d4ff4, 0x3d4ff8, 0x3d4ffc, 0x3d5000):
    wr(a, NOP)
print(f"factory v2 0x3d4fec after:  {rd(0x3d4fec):08x} (b 0x3d5004)")
print(f"factory v2 0x3d4ff0-0x3d5000 NOPed")

with open(DST, 'wb') as f:
    f.write(data)

md5 = hashlib.md5(data).hexdigest()
print(f"\nwritten {DST}  size={len(data)}  md5={md5}")
