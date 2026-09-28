#!/usr/bin/env python3
"""Find all invoke sites whose target method belongs to NativeLibrary (or any class substring).
Prints caller class::method -> target NativeLibrary::name.
Usage: dex_nl_calls.py <dex> <class-substring>
"""
import struct, sys

data = open(sys.argv[1], 'rb').read()
want = sys.argv[2]

def u32(o): return struct.unpack_from('<I', data, o)[0]
def u16(o): return struct.unpack_from('<H', data, o)[0]

def uleb(p):
    r = 0; s = 0
    while True:
        b = data[p]; p += 1
        r |= (b & 0x7f) << s
        if not (b & 0x80): return r, p
        s += 7

sid = u32(0x38); so = u32(0x3c)
tid = u32(0x40); to = u32(0x44)
mid = u32(0x58); mo = u32(0x5c)
cd = u32(0x60); co = u32(0x64)

strings = []
for i in range(sid):
    o = u32(so + 4*i); p = o; ln = 0; sh = 0
    while True:
        b = data[p]; p += 1
        ln |= (b & 0x7f) << sh
        if not (b & 0x80): break
        sh += 7
    strings.append(data[p:p+ln].decode('utf-8', 'replace'))
types = [strings[u32(to + 4*i)] for i in range(tid)]
mcls = []; mname = []
for i in range(mid):
    o = mo + 8*i
    mcls.append(u16(o)); mname.append(u32(o+4))
NSTR = len(strings); NTYP = len(types); NMID = len(mname)

def fmt(mid_):
    if mid_ >= NMID: return '<bad%d>' % mid_
    c = types[mcls[mid_]] if mcls[mid_] < NTYP else '?'
    n = strings[mname[mid_]] if mname[mid_] < NSTR else '?'
    return '%s::%s' % (c, n)

def class_of(mid_):
    return types[mcls[mid_]] if mid_ < NMID and mcls[mid_] < NTYP else '?'

# invoke opcode widths (bytes)
W = [2]*256
W[0x12]=2; W[0x13]=4; W[0x14]=6; W[0x15]=4; W[0x16]=4; W[0x17]=6; W[0x18]=10; W[0x19]=4
W[0x1a]=4; W[0x1b]=6; W[0x1c]=4
W[0x22]=4; W[0x23]=6; W[0x24]=6; W[0x25]=8; W[0x26]=6
W[0x28]=2; W[0x29]=4; W[0x2a]=6; W[0x2b]=6; W[0x2c]=6
for o in range(0x3e, 0x4c): W[o]=6
for o in range(0x4c, 0x68): W[o]=4
for o in range(0x68, 0x6d): W[o]=6
for o in range(0x6d, 0x72): W[o]=8
for o in range(0x72, 0x74): W[o]=2
for o in range(0x74, 0x79): W[o]=8
for o in range(0x79, 0x7b): W[o]=8
for o in range(0x7b, 0x87): W[o]=4
for o in range(0x87, 0xdd): W[o]=4
W[0xf1]=4; W[0xf3]=8

def scan_invokes(code_off, code):
    """return list of (pc, mid) of all invoke targets"""
    out = []
    i = 0; n = len(code)
    while i < n:
        op = code[i]
        w = W[op] if op < 256 else 2
        if (0x68 <= op <= 0x6c) or (0x6d <= op <= 0x71) or (0x74 <= op <= 0x78) or op in (0xf1, 0xf3):
            m = u16(code_off + i + 2)
            out.append((i, m))
        i += w
    return out

hits = []
for ci in range(cd):
    o = co + 32*ci
    cls = types[u32(o)] if u32(o) < NTYP else '?'
    cdo = u32(o + 24)
    if not cdo: continue
    p = cdo
    sf, p = uleb(p); inf, p = uleb(p)
    dm, p = uleb(p); vm, p = uleb(p)
    for _ in range(sf + inf): _, p = uleb(p); _, p = uleb(p)
    # direct
    prev = 0
    for _ in range(dm):
        d, p = uleb(p); prev += d
        acc, p = uleb(p); cof, p = uleb(p)
        if not cof: continue
        insns = u32(cof + 12)
        code = data[cof+16: cof+16+insns*2]
        for pc, m in scan_invokes(cof, code):
            if want in class_of(m):
                hits.append((cls, strings[mname[prev]] if prev < NMID and mname[prev] < NSTR else '?', pc, m, cof))
    # virtual
    prev = 0
    for _ in range(vm):
        d, p = uleb(p); prev += d
        acc, p = uleb(p); cof, p = uleb(p)
        if not cof: continue
        insns = u32(cof + 12)
        code = data[cof+16: cof+16+insns*2]
        for pc, m in scan_invokes(cof, code):
            if want in class_of(m):
                hits.append((cls, strings[mname[prev]] if prev < NMID and mname[prev] < NSTR else '?', pc, m, cof))

for cls, meth, pc, m, cof in sorted(hits):
    print('%s::%s (code_off=0x%x)  pc=0x%x -> %s' % (cls, meth, cof, pc, fmt(m)))
print('total', len(hits))
