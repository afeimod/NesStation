#!/usr/bin/env python3
"""Fast scan: find all code items referencing a given substring (via
const-string / const-string-jumbo) and print containing class::method.
Uses authoritative dalvik instruction width table.

Usage: dex_fast_strscan.py <dex> <substring>...
"""
import struct
import sys

data = open(sys.argv[1], 'rb').read()
needles = [s.encode() for s in sys.argv[2:]]

# authoritative instruction widths (indexed by opcode)
W = [1]*256
for op in (0x01,0x02,0x04,0x05,0x07,0x08,0x0a,0x0b,0x0c,0x0d,0x0f,0x10,0x11,
           0x12,0x15,0x1d,0x1e,0x21,0x27,0x28,0x2d,0x2e,0x2f,0x30,0x31,
           0x7b,0x7c,0x7d,0x7e,0x7f,0x80,0x81,0x82,0x83,0x84,0x85,0x86,
           0x87,0x88,0x89,0x8a,0x8b,0x8c,0x8d,0x8e,0x8f):
    W[op] = 2
for op in range(0x32, 0x3e):  # if-*
    W[op] = 4
for op in range(0x44, 0x73):  # aget/aput/iget/iput/sget/sput/invoke(35c)
    W[op] = 4
for op in range(0x74, 0x79):  # invoke/range
    W[op] = 6
for op in range(0x90, 0xb0):  # binop
    W[op] = 2
for op in range(0xb0, 0xd0):  # binop/2addr
    W[op] = 2
for op in range(0xd0, 0xe0):  # binop/lit16
    W[op] = 4
for op in range(0xe0, 0xeb):  # binop/lit8
    W[op] = 4
# explicit non-default widths
for op, w in {0x03:4,0x06:4,0x09:4,0x13:4,0x14:4,0x16:4,0x17:6,0x18:6,
              0x1a:4,0x1b:6,0x1c:4,0x1f:4,0x20:4,0x22:4,0x23:4,0x24:4,
              0x25:6,0x26:6,0x29:4,0x2a:6,0x2b:6,0x2c:6,
              0x79:6,0x7a:6,0xf4:4,0xf5:4,0xf6:4,0xf7:4,0xf8:4,0xf9:4,
              0xfa:4,0xfb:6,0xfc:4,0xfd:6}.items():
    W[op] = w

def u16(o): return struct.unpack_from('<H', data, o)[0]
def u32(o): return struct.unpack_from('<I', data, o)[0]
def uleb(p):
    r = 0; s = 0
    while True:
        b = data[p]; p += 1
        r |= (b & 0x7f) << s
        if not (b & 0x80): return r, p
        s += 7

string_ids_size = u32(0x38); string_ids_off = u32(0x3c)
type_ids_size = u32(0x40); type_ids_off = u32(0x44)
method_ids_size = u32(0x58); method_ids_off = u32(0x5c)
class_defs_size = u32(0x60); class_defs_off = u32(0x64)

strings = []
for i in range(string_ids_size):
    o = u32(string_ids_off + 4*i); p = o; ln = 0; sh = 0
    while True:
        b = data[p]; p += 1
        ln |= (b & 0x7f) << sh
        if not (b & 0x80): break
        sh += 7
    try: strings.append(data[p:p+ln].decode('utf-8', 'replace'))
    except Exception: strings.append('')

types = [strings[u32(type_ids_off + 4*i)] for i in range(type_ids_size)]
mname = [u32(method_ids_off + 8*i + 4) for i in range(method_ids_size)]

# string ids whose content matches any needle
hit_ids = set()
for i, s in enumerate(strings):
    if s and any(n in s.encode() for n in needles):
        hit_ids.add(i)
print('matching string ids:', sorted(hit_ids))
for i in sorted(hit_ids):
    print('   %d: %r' % (i, strings[i][:160]))

hits = 0
for ci in range(class_defs_size):
    o = class_defs_off + 32*ci
    cls = types[u32(o)] if u32(o) < len(types) else '?'
    cdo = u32(o + 24)
    if not cdo: continue
    p = cdo
    sf, p = uleb(p); inf, p = uleb(p)
    dm, p = uleb(p); vm, p = uleb(p)
    # skip field records: each has field_idx_diff + access_flags
    for _ in range(sf + inf):
        _, p = uleb(p); _, p = uleb(p)
    prev = 0; methods = []
    for _ in range(dm):
        diff, p = uleb(p); prev += diff
        _, p = uleb(p); co, p = uleb(p)
        methods.append((prev, co))
    for mid, co in methods:
        if not co: continue
        insns = u32(co + 12)
        code = data[co + 16: co + 16 + insns*2]
        got = set()
        i = 0; n = len(code)
        while i < n:
            op = code[i]
            if op == 0x1a:
                if i+4 <= n:
                    sidx = u16(code[i+2])
                    if sidx in hit_ids: got.add(sidx)
                i += 4
            elif op == 0x1b:
                if i+6 <= n:
                    sidx = struct.unpack_from('<I', code, i+2)[0]
                    if sidx in hit_ids: got.add(sidx)
                i += 6
            else:
                w = W[op]
                i += w if w else 2
        if got:
            hits += 1
            mn = strings[mname[mid]] if mname[mid] < len(strings) else '?'
            print('--- %s::%s' % (cls, mn))
            for sidx in sorted(got):
                print('    const-string %r' % strings[sidx][:160])
print('total code items with matches:', hits)
