#!/usr/bin/env python3
"""Resolve dex method bodies to (method-call, const, const-string) triplets.

Usage: dex_calls.py <classes.dex> <class-name-substring> [--max N]

For each class whose descriptor contains the substring, print every method and,
for each code item, the invoked methods (with declaring class + name), plus
const-string / const numeric values. Good enough to trace JNI call paths.
"""
import struct
import sys

data = open(sys.argv[1], 'rb').read()
sub = sys.argv[2]
max_methods = None
if '--max' in sys.argv:
    max_methods = int(sys.argv[sys.argv.index('--max') + 1])


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

mclass = []
mname = []
for i in range(method_ids_size):
    off = method_ids_off + 8 * i
    mclass.append(u16(off))
    mname.append(u32(off + 4))


def fmt_method(mid):
    if mid >= len(mname):
        return '<bad>'
    cls = types[mclass[mid]] if mclass[mid] < len(types) else '?'
    name = strings[mname[mid]] if mname[mid] < len(strings) else '?'
    return '%s::%s' % (cls, name)


def decode_insns(code_off, insns, strings):
    """Walk a code array, returning lists of (op, detail) where detail is
    a human string for interesting ops."""
    out = []
    i = 0
    n = len(insns)
    while i < n:
        op = insns[i]
        detail = None
        if op == 0x00:
            width = 1
        elif op == 0x01:  # move
            width = 2
        elif 0x02 <= op <= 0x04:
            width = 2
        elif 0x05 <= op <= 0x0b:  # move-wide / return / etc
            width = 2
        elif 0x0c <= op <= 0x0d:
            width = 2
        elif 0x0e <= op <= 0x14:
            width = 4
        elif 0x15 <= op <= 0x17:
            width = 2
        elif 0x18 <= op <= 0x20:
            width = 4
        elif 0x21 <= op <= 0x27:
            width = 2
        elif 0x28 <= op <= 0x2d:
            width = 4
        elif 0x2e <= op <= 0x33:
            width = 2
        elif 0x34 <= op <= 0x35:
            width = 4
        elif 0x36 <= op <= 0x43:
            width = 4
        elif 0x44 <= op <= 0x51:
            width = 4
        elif 0x52 <= op <= 0x53:
            width = 4
        elif 0x54 <= op <= 0x55:
            width = 6
        elif 0x56 <= op <= 0x57:
            width = 6
        elif 0x58 <= op <= 0x5f:
            width = 4
        elif 0x60 <= op <= 0x6d:
            width = 4
        elif 0x6e <= op <= 0x72:  # invoke-kind
            mid = u16(code_off + i + 2)
            detail = 'invoke %s' % fmt_method(mid)
            width = 4
        elif 0x73:  # unused
            width = 2
        elif 0x74 <= op <= 0x78:
            mid = u16(code_off + i + 2)
            detail = 'invoke %s' % fmt_method(mid)
            width = 4
        elif 0x79 <= op <= 0x7a:
            width = 6
        elif 0x7b <= op <= 0x7f:
            width = 2
        elif 0x80 <= op <= 0x81:  # const-string
            sidx = u16(code_off + i + 2)
            detail = 'str %r' % (strings[sidx] if sidx < len(strings) else '?')
            width = 4
        elif 0x82:  # const-string/jumbo
            sidx = struct.unpack_from('<I', data, code_off + i + 2)[0]
            detail = 'str %r' % (strings[sidx] if sidx < len(strings) else '?')
            width = 6
        elif 0x83 <= op <= 0x8a:  # const kinds
            if op in (0x84, 0x85, 0x86):  # const/high16, const-wide/high16, const-wide/32...
                v = struct.unpack_from('<i', data, code_off + i + 2)[0] if op == 0x86 else 0
                detail = 'const %d' % v
            elif op in (0x87, 0x88, 0x89, 0x8a):  # const/4, const/16
                v = 0
                if op == 0x87:
                    v = (insns[i + 1] & 0xf) << 28 >> 28  # sign-extended 4-bit
                elif op == 0x88:
                    v = struct.unpack_from('<h', insns, i + 2)[0]
                elif op == 0x89:
                    v = struct.unpack_from('<i', data, code_off + i + 2)[0]
                detail = 'const %d' % v
            width = 2
        elif 0x8b <= op <= 0x8c:
            width = 6
        elif 0x8d <= op <= 0x8f:
            width = 2
        elif 0x90 <= op <= 0x91:  # const-class, monitor
            width = 4
        elif 0x92 <= op <= 0x93:
            width = 2
        elif 0x94 <= op <= 0x95:
            width = 4
        elif 0x96 <= op <= 0xa5:
            width = 4
        elif 0xa6 <= op <= 0xab:
            width = 4
        elif 0xac <= op <= 0xaf:
            width = 2
        elif 0xb0 <= op <= 0xdf:
            width = 2
        elif 0xe0 <= op <= 0xe2:
            width = 4
        elif 0xe3 <= op <= 0xe7:
            width = 4
        elif 0xe8 <= op <= 0xe9:
            width = 2
        elif 0xea <= op <= 0xeb:
            width = 2
        elif 0xec <= op <= 0xee:
            width = 2
        elif 0xef <= op <= 0xf3:
            width = 4
        elif 0xf4 <= op <= 0xf8:  # invoke-polymorphic/custom (rare)
            mid = u16(code_off + i + 2)
            detail = 'invoke %s' % fmt_method(mid)
            width = 4
        elif 0xf9 <= op <= 0xfb:
            width = 2
        elif 0xfc <= op <= 0xff:
            width = 2
        else:
            width = 2
        if detail:
            out.append((i, op, detail))
        i += width
    return out


if '--find-callers' in sys.argv:
    target = sys.argv[sys.argv.index('--find-callers') + 1]
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
        prev = 0
        objs = []
        for _ in range(static_fields_size + instance_fields_size):
            _, p = uleb(p)
            _, p = uleb(p)
        for _ in range(direct_methods_size):
            diff, p = uleb(p)
            prev += diff
            acc, p = uleb(p)
            code_off, p = uleb(p)
            objs.append((prev, acc, code_off))
        for _ in range(virtual_methods_size):
            diff, p = uleb(p)
            prev += diff
            acc, p = uleb(p)
            code_off, p = uleb(p)
            objs.append((prev, acc, code_off))
        for mid, acc, code_off in objs:
            if not code_off:
                continue
            name = strings[mname[mid]] if mid < len(mname) and mname[mid] < len(strings) else '?'
            insns = u32(code_off + 12)
            code = data[code_off + 16: code_off + 16 + insns * 2]
            hits = [d for _, _, d in decode_insns(code_off, code, strings) if d and d.startswith('invoke') and target in d]
            if hits:
                print('%s::%s' % (cls, name))
                for h in hits:
                    print('    %s' % h)
    sys.exit(0)

count = 0
for ci in range(class_defs_size):
    off = class_defs_off + 32 * ci
    class_idx = u32(off)
    cls = types[class_idx] if class_idx < len(types) else '?'
    if sub not in cls:
        continue
    print('=== %s ===' % cls)
    class_data_off = u32(off + 24)
    if not class_data_off:
        print('  (no class data)')
        continue
    p = class_data_off
    static_fields_size, p = uleb(p)
    instance_fields_size, p = uleb(p)
    direct_methods_size, p = uleb(p)
    virtual_methods_size, p = uleb(p)
    prev = 0
    objs = []
    for _ in range(static_fields_size + instance_fields_size):
        _, p = uleb(p)
        _, p = uleb(p)
    for _ in range(direct_methods_size):
        diff, p = uleb(p)
        prev += diff
        acc, p = uleb(p)
        code_off, p = uleb(p)
        objs.append((prev, acc, code_off, True))
    for _ in range(virtual_methods_size):
        diff, p = uleb(p)
        prev += diff
        acc, p = uleb(p)
        code_off, p = uleb(p)
        objs.append((prev, acc, code_off, False))
    for mid, acc, code_off, direct in objs:
        if mid >= len(mname):
            continue
        name = strings[mname[mid]] if mname[mid] < len(strings) else '?'
        static = 'static ' if acc & 0x10000 else ''
        native = 'native ' if acc & 0x100 else ''
        print('  %s%s%s%s' % ('direct ' if direct else '', static, native, name))
        if not code_off:
            continue
        insns = u32(code_off + 12)
        code = data[code_off + 16: code_off + 16 + insns * 2]
        for addr, op, detail in decode_insns(code_off, code, strings):
            print('    %04x  %s' % (addr, detail))
    count += 1
    if max_methods and count >= max_methods:
        break
