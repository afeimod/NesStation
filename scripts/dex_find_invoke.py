#!/usr/bin/env python3
"""Find all code items that invoke a specific method (by name substring) and
print the full decoded instruction stream of those callers.

Usage: dex_find_invoke.py <dex> <method-name-substring>
"""
import struct
import sys

data = open(sys.argv[1], 'rb').read()
target = sys.argv[2]

def u32(o): return struct.unpack_from('<I', data, o)[0]
def u16(o): return struct.unpack_from('<H', data, o)[0]

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
    try: strings.append(data[p:p+ln].decode('utf-8'))
    except Exception: strings.append('')

types = []
for i in range(type_ids_size):
    types.append(strings[u32(type_ids_off + 4*i)])

mcls = []; mname = []
for i in range(method_ids_size):
    o = method_ids_off + 8*i
    mcls.append(u16(o))
    mname.append(u32(o+4))

NSTR = len(strings); NTYP = len(types); NMID = len(mname)
target_ids = set(i for i in range(method_ids_size)
                 if mname[i] < NSTR and target in strings[mname[i]])
print('target method ids:', [(i, types[mcls[i]] + '::' + strings[mname[i]])
      for i in sorted(target_ids)])

def fmt(mid):
    if mid >= NMID: return '<bad>'
    c = types[mcls[mid]] if mcls[mid] < NTYP else '?'
    n = strings[mname[mid]] if mname[mid] < NSTR else '?'
    return '%s::%s' % (c, n)

def decode(code_off, code):
    out = []
    i = 0; n = len(code)
    while i < n:
        op = code[i]
        detail = None
        W = [2]*256
        W[0x00]=2; W[0x01]=2; W[0x02]=4; W[0x03]=4; W[0x04]=2; W[0x05]=4; W[0x06]=4
        W[0x07]=2; W[0x08]=4; W[0x09]=4; W[0x0a]=2; W[0x0b]=2; W[0x0c]=2; W[0x0d]=2
        W[0x0e]=2; W[0x0f]=2; W[0x10]=2; W[0x11]=2; W[0x12]=2
        W[0x13]=4; W[0x14]=6; W[0x15]=4; W[0x16]=4; W[0x17]=6; W[0x18]=10; W[0x19]=4
        W[0x1a]=4; W[0x1b]=6; W[0x1c]=4; W[0x1d]=2; W[0x1e]=2; W[0x1f]=4; W[0x20]=4
        W[0x21]=2; W[0x22]=4; W[0x23]=6; W[0x24]=6; W[0x25]=8; W[0x26]=6; W[0x27]=2
        W[0x28]=2; W[0x29]=4; W[0x2a]=6; W[0x2b]=6; W[0x2c]=6
        W[0x2d]=2; W[0x2e]=2; W[0x2f]=2; W[0x30]=2; W[0x31]=2
        for o in range(0x32, 0x3e): W[o]=2
        for o in range(0x3e, 0x4c): W[o]=6
        for o in range(0x4c, 0x68): W[o]=4
        for o in range(0x68, 0x6d): W[o]=6
        for o in range(0x6d, 0x72): W[o]=8
        for o in range(0x72, 0x74): W[o]=2
        for o in range(0x74, 0x79): W[o]=8
        for o in range(0x79, 0x7b): W[o]=8
        for o in range(0x7b, 0x87): W[o]=4
        for o in range(0x87, 0xdd): W[o]=4
        for o in range(0xdd, 0xf1): W[o]=2
        W[0xf1]=4; W[0xf3]=8
        if op == 0x00: width = W[0x00]
        else: width = W[op]
        if 0x68 <= op <= 0x6c:
            mid = u16(code_off + i + 2)
            detail = ('invoke %s' % fmt(mid), mid)
        elif 0x6d <= op <= 0x71:
            mid = u16(code_off + i + 2)
            detail = ('invoke %s' % fmt(mid), mid)
        elif op == 0x72:
            mid = u16(code_off + i + 2)
            detail = ('invoke-polymorphic %s' % fmt(mid), mid)
        elif 0x74 <= op <= 0x78:
            mid = u16(code_off + i + 2)
            detail = ('invoke %s' % fmt(mid), mid)
        elif op in (0x80, 0x81):
            sidx = u16(code_off + i + 2)
            detail = ('str %r' % (strings[sidx] if sidx < len(strings) else '?'), None)
        elif op == 0x82:
            sidx = u32(code_off + i + 2)
            detail = ('str %r' % (strings[sidx] if sidx < len(strings) else '?'), None)
        elif op == 0x87:
            v = (code[i+1] & 0xf) << 28 >> 28
            detail = ('const %d' % v, None)
        elif op == 0x88:
            v = struct.unpack_from('<h', code, i+2)[0]
            detail = ('const %d' % v, None)
        elif op in (0x89, 0x86):
            v = struct.unpack_from('<i', data, code_off + i + 2)[0]
            detail = ('const %d' % v, None)
        else:
            detail = None
        if detail:
            out.append((i, detail[0], detail[1]))
        i += width
    return out

for ci in range(class_defs_size):
    o = class_defs_off + 32*ci
    cls = types[u32(o)] if u32(o) < len(types) else '?'
    cdo = u32(o + 24)
    if not cdo: continue
    p = cdo
    sf, p = uleb(p); inf, p = uleb(p)
    dm, p = uleb(p); vm, p = uleb(p)
    for _ in range(sf + inf): _, p = uleb(p); _, p = uleb(p)
    methods = []
    prev = 0
    for _ in range(dm):
        diff, p = uleb(p); prev += diff
        acc, p = uleb(p); co, p = uleb(p)
        methods.append((prev, co))
    prev = 0
    for _ in range(vm):
        diff, p = uleb(p); prev += diff
        acc, p = uleb(p); co, p = uleb(p)
        methods.append((prev, co))
    for mid, co in methods:
        if not co: continue
        insns = u32(co + 12)
        code = data[co + 16: co + 16 + insns*2]
        lines = decode(co, code)
        actual = [addr for addr, _, midinfo in lines
                  if midinfo is not None and midinfo in target_ids]
        if actual:
            print('=== %s::%s ===' % (cls, strings[mname[mid]] if mid < NMID and mname[mid] < NSTR else '?'))
            for addr, dtxt, midinfo in lines:
                marker = '>>>' if addr in actual else '   '
                print('  %s %04x  %s' % (marker, addr, dtxt))