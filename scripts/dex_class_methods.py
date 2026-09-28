#!/usr/bin/env python3
"""List all methods of a class (with method indices and code_off).
Usage: dex_class_methods.py <dex> <class-descriptor>
"""
import struct
import sys

data = open(sys.argv[1], 'rb').read()

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

mcls = []; mproto = []; mname = []
for i in range(method_ids_size):
    o = method_ids_off + 8*i
    mcls.append(u16(o))
    mproto.append(u16(o+2))
    mname.append(u32(o+4))

NSTR = len(strings); NTYP = len(types); NMID = len(mname)

want = sys.argv[2]
for ci in range(class_defs_size):
    o = class_defs_off + 32*ci
    cls = types[u32(o)] if u32(o) < len(types) else '?'
    if cls != want: continue
    cdo = u32(o + 24)
    if not cdo:
        print(cls, 'no class_data'); continue
    p = cdo
    sf, p = uleb(p); inf, p = uleb(p)
    dm, p = uleb(p); vm, p = uleb(p)
    for _ in range(sf + inf): _, p = uleb(p); _, p = uleb(p)
    prev = 0
    n = 0
    for _ in range(dm):
        diff, p = uleb(p); prev += diff
        acc, p = uleb(p); co, p = uleb(p)
        name = strings[mname[prev]] if prev < NMID and mname[prev] < NSTR else '?%d' % prev
        print('D %s::%s acc=0x%x code_off=0x%x' % (cls, name, acc, co))
        n += 1
    prev = 0
    for _ in range(vm):
        diff, p = uleb(p); prev += diff
        acc, p = uleb(p); co, p = uleb(p)
        name = strings[mname[prev]] if prev < NMID and mname[prev] < NSTR else '?%d' % prev
        print('V %s::%s acc=0x%x code_off=0x%x' % (cls, name, acc, co))
        n += 1
    break
