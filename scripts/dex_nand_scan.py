#!/usr/bin/env python3
"""Scan a dex file to find which classes construct the NAND/SDMC directory tree
and which strings they reference. Looks for '/nand/', '/sdmc', '/sysdata',
'00040000' path segments and reports const-string usage per method.

Usage: dex_nand_scan.py <classes.dex>
"""
import struct
import sys

data = open(sys.argv[1], 'rb').read()


def u16(off):
    return struct.unpack_from('<H', data, off)[0]


def u32(off):
    return struct.unpack_from('<I', data, off)[0]


def uleb(p):
    r = 0
    s = 0
    while True:
        b = data[p]
        p += 1
        r |= (b & 0x7f) << s
        if not (b & 0x80):
            return r, p
        s += 7


string_ids_size = u32(0x38)
string_ids_off = u32(0x3c)
type_ids_size = u32(0x40)
type_ids_off = u32(0x44)
method_ids_size = u32(0x58)
method_ids_off = u32(0x5c)
class_defs_size = u32(0x60)
class_defs_off = u32(0x64)

strings = []
for i in range(string_ids_size):
    data_off = u32(string_ids_off + 4 * i)
    pos = data_off
    length = 0
    shift = 0
    while True:
        b = data[pos]
        pos += 1
        length |= (b & 0x7f) << shift
        if not (b & 0x80):
            break
        shift += 7
    try:
        strings.append(data[pos:pos + length].decode('utf-8'))
    except Exception:
        strings.append('')

types = []
for i in range(type_ids_size):
    desc_idx = u32(type_ids_off + 4 * i)
    types.append(strings[desc_idx])

mname = []
for i in range(method_ids_size):
    off = method_ids_off + 8 * i
    mname.append(u32(off + 4))

# const-string scan per method
use = {}
for ci in range(class_defs_size):
    off = class_defs_off + 32 * ci
    class_idx = u32(off)
    cls = types[class_idx] if class_idx < len(types) else '?'
    class_data_off = u32(off + 24)
    if not class_data_off:
        continue
    p = class_data_off
    static_fields_size, p = uleb(p)
    instance_fields_size, p = uleb(p)
    direct_methods_size, p = uleb(p)
    virtual_methods_size, p = uleb(p)
    for _ in range(static_fields_size + instance_fields_size):
        _, p = uleb(p)
        _, p = uleb(p)
    prev = 0
    total = direct_methods_size + virtual_methods_size
    for m in range(total):
        diff, p = uleb(p)
        prev += diff
        acc, p = uleb(p)
        code_off, p = uleb(p)
        if not code_off:
            continue
        ni = mname[prev] if prev < len(mname) else -1
        mn = strings[ni] if 0 <= ni < len(strings) else '?'
        insns = u32(code_off + 12)
        code = data[code_off + 16: code_off + 16 + insns * 2]
        refs = set()
        i = 0
        b = code
        while i < len(b):
            op = b[i]
            if op == 0x1a:
                sidx = u16(code_off + 16 + i + 2)
                if sidx < len(strings):
                    refs.add(strings[sidx])
                i += 4
            elif op == 0x1b:
                if i + 6 <= len(b):
                    sidx = struct.unpack_from('<I', b, i + 2)[0]
                    if sidx < len(strings):
                        refs.add(strings[sidx])
                i += 6
            elif op == 0x00:
                i += 1
            else:
                i += 1  # rough scan: const-string ops are 0x1a/0x1b only
        if m == 0 and not refs:
            pass
        interesting = [r for r in refs if any(
            k in r for k in ('/nand', '/sdmc', '/sysdata', '00040000', '0004000e',
                             '0004008c', 'title', 'shared_font', 'config.ini',
                             'aes_keys', 'boot9', 'seeddb', '.app', 'NAND', 'SDMC')
        )]
        if interesting:
            use.setdefault(cls + '::' + mn, set()).update(interesting)

for meth, refs in sorted(use.items()):
    print('%-70s' % meth)
    for r in sorted(refs):
        print('     %r' % r)