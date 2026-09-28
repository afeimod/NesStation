#!/usr/bin/env python3
"""Find all code items that const-string a given string, in all classes, and
print the full decoded instruction streams with a marker.

Usage: dex_find_str.py <dex> <string-substring>
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

# Matching string ids (const-string) whose content contains target
hit_str_ids = {i for i, s in enumerate(strings) if target in s}
print('hit string ids:', [(i, strings[i]) for i in sorted(hit_str_ids)])

def fmt(mid):
    if mid >= len(mname): return '<bad>'
    c = types[mcls[mid]] if mcls[mid] < len(types) else '?'
    n = strings[mname[mid]] if mname[mid] < len(strings) and mname[mid] >= 0 else '?'
    return '%s::%s' % (c, n)

def decode(code_off, code):
    out = []
    i = 0; n = len(code)
    while i < n:
        op = code[i]
        detail = (None, None)
        if op == 0x00: width = 1
        elif op == 0x01: width = 2
        elif 0x02 <= op <= 0x0b: width = 2
        elif 0x0c <= op <= 0x0d: width = 2
        elif 0x0e <= op <= 0x14: width = 4
        elif 0x15 <= op <= 0x17: width = 2
        elif 0x18 <= op <= 0x20: width = 4
        elif 0x21 <= op <= 0x27: width = 2
        elif 0x28 <= op <= 0x2d: width = 4
        elif 0x2e <= op <= 0x33: width = 2
        elif 0x34 <= op <= 0x35: width = 4
        elif 0x36 <= op <= 0x43: width = 4
        elif 0x44 <= op <= 0x51: width = 4
        elif 0x52 <= op <= 0x53: width = 4
        elif 0x54 <= op <= 0x55: width = 6
        elif 0x56 <= op <= 0x57: width = 6
        elif 0x58 <= op <= 0x5f: width = 4
        elif 0x60 <= op <= 0x6d: width = 4
        elif 0x6e <= op <= 0x72:
            mid = u16(code_off + i + 2)
            detail = ('invoke %s' % fmt(mid), mid)
            width = 4
        elif op == 0x73: width = 2
        elif 0x74 <= op <= 0x78:
            mid = u16(code_off + i + 2)
            detail = ('invoke %s' % fmt(mid), mid)
            width = 4
        elif 0x79 <= op <= 0x7a: width = 6
        elif 0x7b <= op <= 0x7f: width = 2
        elif op in (0x80, 0x81):
            sidx = u16(code_off + i + 2)
            detail = ('str %r' % (strings[sidx] if sidx < len(strings) else '?'), sidx)
            width = 4
        elif op == 0x82:
            sidx = u32(code_off + i + 2)
            detail = ('str %r' % (strings[sidx] if sidx < len(strings) else '?'), sidx)
            width = 6
        elif op in (0x83, 0x84, 0x85, 0x86, 0x87, 0x88, 0x89, 0x8a):
            v = 0
            if op == 0x87:
                v = (code[i+1] & 0xf) << 28 >> 28
            elif op == 0x88:
                v = struct.unpack_from('<h', code, i+2)[0]
            elif op in (0x89, 0x86):
                v = struct.unpack_from('<i', data, code_off + i + 2)[0]
            detail = ('const %d' % v, None)
            width = 2
        elif 0x8b <= op <= 0x8c: width = 6
        elif 0x8d <= op <= 0x8f: width = 2
        elif op in (0x90, 0x91): width = 4
        elif 0x92 <= op <= 0x93: width = 2
        elif 0x94 <= op <= 0x95: width = 4
        elif 0x96 <= op <= 0xa5: width = 4
        elif 0xa6 <= op <= 0xab: width = 4
        elif 0xac <= op <= 0xaf: width = 2
        elif 0xb0 <= op <= 0xff: width = 2
        else: width = 2
        if detail[0] is not None:
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
    prev = 0; methods = []
    for _ in range(dm + vm):
        diff, p = uleb(p); prev += diff
        acc, p = uleb(p); co, p = uleb(p)
        methods.append((prev, co))
    for mid, co in methods:
        if not co: continue
        insns = u32(co + 12)
        code = data[co + 16: co + 16 + insns*2]
        lines = decode(co, code)
        actual = [addr for addr, _, s in lines if s in hit_str_ids]
        if actual:
            print('=== %s::%s ===' % (cls, strings[mname[mid]] if mname[mid] < len(strings) and mname[mid] >= 0 else '?'))
            for addr, dtxt, sid in lines:
                marker = '>>>' if addr in actual else '   '
                print('  %s %04x  %s' % (marker, addr, dtxt))