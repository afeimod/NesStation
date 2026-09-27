#!/usr/bin/env python3
"""Extract class methods and their referenced string constants from a dex file.

Usage: dex_class_strings.py <classes.dex> <class-name-substring> [--detail]
Prints every method of matching classes and the string constants they reference.
"""
import struct
import sys

data = open(sys.argv[1], 'rb').read()
sub = sys.argv[2]
detail = '--detail' in sys.argv


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


# headers
string_ids_size = u32(0x38)
string_ids_off = u32(0x3c)
type_ids_size = u32(0x40)
type_ids_off = u32(0x44)
method_ids_size = u32(0x58)
method_ids_off = u32(0x5c)
class_defs_size = u32(0x60)
class_defs_off = u32(0x64)

# string table
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

# type table (descriptor strings)
types = []
for i in range(type_ids_size):
    desc_idx = u32(type_ids_off + 4 * i)
    types.append(strings[desc_idx])

# method table -> (class_idx, proto_idx, name_idx)  — 8 bytes each
mclass = []
mname = []
for i in range(method_ids_size):
    off = method_ids_off + 8 * i
    mclass.append(u16(off))
    mname.append(u32(off + 4))

# walk class_defs
for ci in range(class_defs_size):
    off = class_defs_off + 32 * ci
    class_idx = u32(off)
    cls = types[class_idx] if class_idx < len(types) else '?'
    if sub not in cls:
        continue
    print('=== %s ===' % cls)
    class_data_off = u32(off + 24)
    if not class_data_off:
        continue
    def show(mn, acc, code_off):
        static = 'static ' if acc & 0x10000 else ''
        native = 'native ' if acc & 0x100 else ''
        print('  %s%s%s' % (static, native, mn))
        # code_item: registers_size u16, ins_size u16, outs_size u16,
        # tries_size u16, debug_info_off u32, insns_size u32 (code units)
        if not code_off:
            return
        insns = u32(code_off + 12)
        code = data[code_off + 16: code_off + 16 + insns * 2]
        refs = []
        i = 0
        b = code
        while i < len(b):
            op = b[i]
            if op == 0x1a:  # const-string vAA, string@BBBB
                sidx = u16(code_off + 16 + i + 2)
                if sidx < len(strings):
                    refs.append(strings[sidx])
                i += 4
            elif op == 0x1b:  # const-string/jumbo (AAAA)
                sidx = struct.unpack_from('<I', b, i + 2)[0]
                if sidx < len(strings):
                    refs.append(strings[sidx])
                i += 6
            elif op == 0x00:
                i += 1
            elif 0x01 <= op <= 0x0b:
                i += 1
            elif 0x0c <= op <= 0x0d:
                i += 2
            elif 0x0e <= op <= 0x14:
                i += 4
            elif 0x15 <= op <= 0x17:
                i += 2
            elif 0x18 <= op <= 0x20:
                i += 4
            elif 0x21 <= op <= 0x27:
                i += 2
            elif 0x28 <= op <= 0x2d:
                i += 4
            else:
                i += 2
        seen = set()
        for r in refs:
            if r not in seen:
                seen.add(r)
                print('      -> %r' % r)
    p = class_data_off
    static_fields_size, p = uleb(p)
    instance_fields_size, p = uleb(p)
    direct_methods_size, p = uleb(p)
    virtual_methods_size, p = uleb(p)
    prev = 0
    objs = []  # (name_idx, access, code_off)
    for f in range(static_fields_size + instance_fields_size):
        _, p = uleb(p)
        _, p = uleb(p)
    for m in range(direct_methods_size):
        diff, p = uleb(p)
        prev += diff
        acc, p = uleb(p)
        code_off, p = uleb(p)
        objs.append((prev, acc, code_off))
    for m in range(virtual_methods_size):
        diff, p = uleb(p)
        prev += diff
        acc, p = uleb(p)
        code_off, p = uleb(p)
        objs.append((prev, acc, code_off))
    for mid, acc, code_off in objs:
        if mid >= len(mname):
            continue
        ni = mname[mid]
        mn = strings[ni] if ni < len(strings) else '<bad>'
        show(mn, acc, code_off)
        if not code_off or not detail:
            continue
        # code_item: registers_size u16, ins_size u16, outs_size u16,
        # tries_size u16, debug_info_off u32, insns_size u32
        insns = u32(code_off + 12)
        code = data[code_off + 16: code_off + 16 + insns * 2]
        refs = []
        i = 0
        while i < len(code):
            op = code[i]
            if op == 0x1a:  # const-string vAA, string@BBBB
                sidx = u16(code[i + 2])
                if sidx < len(strings):
                    refs.append(strings[sidx])
                i += 4
            elif op == 0x1b:  # const-string/jumbo
                sidx = struct.unpack_from('<I', bytes(code[i + 2:i + 6]))[0] if i + 6 <= len(code) else -1
                if 0 <= sidx < len(strings):
                    refs.append(strings[sidx])
                i += 6
            else:
                # determine instruction width from opcode high nibble (format)
                hi = op >> 4
                if hi == 0x00:
                    if op == 0x00:
                        i += 1
                    elif op == 0x01:  # move
                        i += 2
                    elif op == 0x02 or op == 0x03 or op == 0x04:
                        i += 2
                    elif 0x0e <= op <= 0x11:  # return-void variants wide
                        i += 1
                    elif 0x12 <= op <= 0x14:
                        i += 4
                    else:
                        i += 1
                elif hi == 0x01:
                    i += 2  # move 12x
                elif hi == 0x02:  # 11n
                    i += 2
                elif hi == 0x03 or hi == 0x04 or hi == 0x05 or hi == 0x06 or \
                        hi == 0x07 or hi == 0x08 or hi == 0x09:
                    i += 2  # 11x/10t/12x/22x/21t/21s
                elif hi in (0x0a, 0x0b):
                    i += 2  # 10x/12x->? treat as 2
                elif hi == 0x0c:  # 21t
                    i += 2
                elif hi == 0x0d:  # 21s
                    i += 2
                elif hi in (0x0e, 0x0f, 0x10, 0x11, 0x12, 0x13):
                    i += 4  # 22x/21t? use 4-byte formats
                elif hi in (0x14, 0x15):
                    i += 2  # 22c uses 4; approximate
                elif hi in (0x16, 0x17):
                    i += 4  # 22cs/32x
                elif hi == 0x18:  # 23x
                    i += 4
                elif hi in (0x19, 0x1a):
                    i += 2  # 22b?
                elif hi in (0x1b, 0x1c):
                    i += 2
                elif hi in (0x1d, 0x1e, 0x1f, 0x20, 0x21, 0x22, 0x23):
                    i += 4
                else:
                    i += 2
        # only print interesting refs
        interesting = [r for r in refs if r and len(r) < 200]
        seen = set()
        for r in interesting:
            if r not in seen:
                seen.add(r)
                print('      -> %r' % r)