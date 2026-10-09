#!/usr/bin/env python3
"""
D-PAD "上"失效 —— 行为验证补丁（探测用，非最终修复）。

背景（逆向结论）：
  InputEvent(key, value) JNI 入口 0x25c6a0：
    - 0x25c6ac: add x8, x0, w1, sxtw #2 ; ldr w1, [x8, #0x3c]  → key 查绑定数组[0x3c+key*4]
    - 0x25c6b4: fcsel s0, s1, s0, eq  （key∈{4,6} 时对 value 取反，s1=-s0）
    - 之后统一走状态机 0x25c6bc。
  十字键"上"= InputEvent(4, +1f) → 取反 → -1f → 轴槽 key=4（value<0）。
  若 8 个轴槽只有 code+0x1000（dir:+）方向条目 → key=4 不匹配 → 丢弃。

  变体 --no-negate（0x25c6b4 NOP）：
    InputEvent(4,+1f) 不再取反 → 轴槽 key=0x1004。若轴槽=0x1004 则"上"修复。
    副作用：LEFT(6) 也不再取反 → key=0x1006；若 LEFT 轴槽=6（dir:-）则左失效。
  变体 --move-semantics（0x25c6a0 首条改 b 0x25c6bc）：
    跳过数组查找 + 取反，等价于 MoveEvent(key 原样, value 原样)。
    UP key=4, +1f → 轴槽 key=0x1004。LEFT key=6, +1f → 0x1006。

用法：
  patch_mmj_dpad_test.py --no-negate <src.so> [<dst.so>]
  patch_mmj_dpad_test.py --move-semantics <src.so> [<dst.so>]
"""
import struct, sys, hashlib

SRC = None
DST = None
MODE = None
for a in sys.argv[1:]:
    if a == '--no-negate':
        MODE = 'no_negate'
    elif a == '--move-semantics':
        MODE = 'move_sem'
    elif not a.startswith('--'):
        if SRC is None:
            SRC = a
        else:
            DST = a
if not MODE:
    sys.exit("mode required: --no-negate | --move-semantics")
if not SRC:
    sys.exit("src.so required")

NOP = 0xD503201F
with open(SRC, 'rb') as f:
    data = bytearray(f.read())

def rd(vaddr):
    return struct.unpack_from('<I', data, vaddr)[0]

def wr(vaddr, word):
    struct.pack_into('<I', data, vaddr, word)

print(f"mode={MODE} src={SRC}")

if MODE == 'no_negate':
    a = 0x25c6b4
    before = rd(a)
    print(f"0x25c6b4 before: {before:08x}")
    if before == 0x1e2e2030:  # fcsel s0, s1, s0, eq
        wr(a, NOP)
        print(f"0x25c6b4 after : {rd(a):08x} (NOP — 禁用 key∈{4,6} 的 value 取反)")
    else:
        print("!! unexpected word at 0x25c6b4, abort")
        sys.exit(1)
else:  # move_sem
    # InputEvent JNI 0x264fe8:
    #   0x264fe8 mov w1, w2     ; w1 = key
    #   0x264fec scvtf s0, w3   ; s0 = (float)value
    #   0x264ff0 adrp x0, #0x77c000
    #   0x264ff4 add  x0, x0, #0x718   ; x0 = 绑定对象
    #   0x264ff8 b 0x25c6a0
    # 把最后一条 b 0x25c6a0 改为 b 0x25c6bc（跳过数组查找与取反）
    a = 0x264ff8
    before = rd(a)
    print(f"0x264ff8 before: {before:08x}")
    if (before & 0xfc000000) == 0x14000000:  # b
        # imm26 -> target
        imm = before & 0x03FFFFFF
        if imm & 0x02000000:
            imm -= 0x04000000
        tgt = a + imm * 4
        print(f"  target was {tgt:#x}")
        if tgt != 0x25c6a0:
            print("  !! not jumping to 0x25c6a0, abort")
            sys.exit(1)
        # b 0x25c6bc: imm26 = (0x25c6bc - a)>>2
        diff = 0x25c6bc - a
        assert diff % 4 == 0
        imm = (diff >> 2) & 0x03FFFFFF
        wr(a, 0x14000000 | imm)
        print(f"0x264ff8 after : {rd(a):08x} (b 0x25c6bc — MoveEvent 语义)")
    else:
        print("  !! unexpected word, abort")
        sys.exit(1)

if DST is None:
    DST = SRC + ('.noneg' if MODE == 'no_negate' else '.move')
with open(DST, 'wb') as f:
    f.write(data)
md5 = hashlib.md5(data).hexdigest()
print(f"written {DST}  md5={md5}")
