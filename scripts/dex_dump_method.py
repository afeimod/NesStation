#!/usr/bin/env python3
"""Dump a dex method's code as readable pseudo-instructions.
Usage: dex_dump_method.py <dex> <class-descriptor> <method-name> [method-name ...]
Finds the method in class_data (with field skipping) and decodes its code_item.
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

def s16(p): return struct.unpack_from('<h', data, p)[0]
def s32(p): return struct.unpack_from('<i', data, p)[0]

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

def fmt_type(t):
    return types[t] if t < NTYP else '?%d' % t

def fmt_mid(mid):
    if mid >= NMID: return '<bad%d>' % mid
    c = fmt_type(mcls[mid])
    n = strings[mname[mid]] if mname[mid] < NSTR else '?'
    return '%s::%s' % (c, n)

INSN = {
    0x00: 'nop', 0x01: 'move', 0x02: 'move/from16', 0x03: 'move/16',
    0x04: 'move-wide', 0x05: 'move-wide/from16', 0x06: 'move-wide/16',
    0x07: 'move-object', 0x08: 'move-object/from16', 0x09: 'move-object/16',
    0x0a: 'move-result', 0x0b: 'move-result-wide', 0x0c: 'move-result-object',
    0x0d: 'move-exception', 0x0e: 'return-void', 0x0f: 'return',
    0x10: 'return-wide', 0x11: 'return-object', 0x12: 'const/4',
    0x13: 'const/16', 0x14: 'const', 0x15: 'const/high16',
    0x16: 'const-wide/16', 0x17: 'const-wide/32', 0x18: 'const-wide',
    0x19: 'const-wide/high16', 0x1a: 'const-string', 0x1b: 'const-string/jumbo',
    0x1c: 'const-class', 0x1d: 'monitor-enter', 0x1e: 'monitor-exit',
    0x1f: 'check-cast', 0x20: 'instance-of', 0x21: 'array-length',
    0x22: 'new-instance', 0x23: 'new-array', 0x24: 'filled-new-array',
    0x25: 'filled-new-array/range', 0x26: 'fill-array-data', 0x27: 'throw',
    0x28: 'goto', 0x29: 'goto/16', 0x2a: 'goto/32', 0x2b: 'packed-switch',
    0x2c: 'sparse-switch', 0x2d: 'cmpl-float', 0x2e: 'cmpg-float',
    0x2f: 'cmpl-double', 0x30: 'cmpg-double', 0x31: 'cmp-long',
    0x32: 'if-eq', 0x33: 'if-ne', 0x34: 'if-lt', 0x35: 'if-ge',
    0x36: 'if-gt', 0x37: 'if-le', 0x38: 'if-eqz', 0x39: 'if-nez',
    0x3a: 'if-ltz', 0x3b: 'if-gez', 0x3c: 'if-gtz', 0x3d: 'if-lez',
    0x3e: 'aget', 0x3f: 'aget-wide', 0x40: 'aget-object', 0x41: 'aget-boolean',
    0x42: 'aget-byte', 0x43: 'aget-char', 0x44: 'aget-short',
    0x45: 'aput', 0x46: 'aput-wide', 0x47: 'aput-object', 0x48: 'aput-boolean',
    0x49: 'aput-byte', 0x4a: 'aput-char', 0x4b: 'aput-short',
    0x4c: 'iget', 0x4d: 'iget-wide', 0x4e: 'iget-object', 0x4f: 'iget-boolean',
    0x50: 'iget-byte', 0x51: 'iget-char', 0x52: 'iget-short',
    0x53: 'iput', 0x54: 'iput-wide', 0x55: 'iput-object', 0x56: 'iput-boolean',
    0x57: 'iput-byte', 0x58: 'iput-char', 0x59: 'iput-short',
    0x5a: 'sget', 0x5b: 'sget-wide', 0x5c: 'sget-object', 0x5d: 'sget-boolean',
    0x5e: 'sget-byte', 0x5f: 'sget-char', 0x60: 'sget-short',
    0x61: 'sput', 0x62: 'sput-wide', 0x63: 'sput-object', 0x64: 'sput-boolean',
    0x65: 'sput-byte', 0x66: 'sput-char', 0x67: 'sput-short',
    0x68: 'invoke-virtual', 0x69: 'invoke-super', 0x6a: 'invoke-direct',
    0x6b: 'invoke-static', 0x6c: 'invoke-interface', 0x6d: 'invoke-virtual/range',
    0x6e: 'invoke-super/range', 0x6f: 'invoke-direct/range',
    0x70: 'invoke-static/range', 0x71: 'invoke-interface/range',
    0x72: 'neg-int', 0x73: 'not-int', 0x74: 'neg-long', 0x75: 'not-long',
    0x76: 'neg-float', 0x77: 'neg-double', 0x78: 'int-to-long',
    0x79: 'int-to-float', 0x7a: 'int-to-double', 0x7b: 'long-to-int',
    0x7c: 'long-to-float', 0x7d: 'long-to-double', 0x7e: 'float-to-int',
    0x7f: 'float-to-long', 0x80: 'float-to-double', 0x81: 'double-to-int',
    0x82: 'double-to-long', 0x83: 'double-to-float', 0x84: 'int-to-byte',
    0x85: 'int-to-char', 0x86: 'int-to-short', 0x87: 'add-int',
    0x88: 'sub-int', 0x89: 'mul-int', 0x8a: 'div-int', 0x8b: 'rem-int',
    0x8c: 'and-int', 0x8d: 'or-int', 0x8e: 'xor-int', 0x8f: 'shl-int',
    0x90: 'shr-int', 0x91: 'ushr-int', 0x92: 'add-long', 0x93: 'sub-long',
    0x94: 'mul-long', 0x95: 'div-long', 0x96: 'rem-long', 0x97: 'and-long',
    0x98: 'or-long', 0x99: 'xor-long', 0x9a: 'shl-long', 0x9b: 'shr-long',
    0x9c: 'ushr-long', 0x9d: 'add-float', 0x9e: 'sub-float', 0x9f: 'mul-float',
    0xa0: 'div-float', 0xa1: 'rem-float', 0xa2: 'add-double', 0xa3: 'sub-double',
    0xa4: 'mul-double', 0xa5: 'div-double', 0xa6: 'rem-double',
    0xa7: 'add-int/2addr', 0xa8: 'sub-int/2addr', 0xa9: 'mul-int/2addr',
    0xaa: 'div-int/2addr', 0xab: 'rem-int/2addr', 0xac: 'and-int/2addr',
    0xad: 'or-int/2addr', 0xae: 'xor-int/2addr', 0xaf: 'shl-int/2addr',
    0xb0: 'shr-int/2addr', 0xb1: 'ushr-int/2addr', 0xb2: 'add-long/2addr',
    0xb3: 'sub-long/2addr', 0xb4: 'mul-long/2addr', 0xb5: 'div-long/2addr',
    0xb6: 'rem-long/2addr', 0xb7: 'and-long/2addr', 0xb8: 'or-long/2addr',
    0xb9: 'xor-long/2addr', 0xba: 'shl-long/2addr', 0xbb: 'shr-long/2addr',
    0xbc: 'ushr-long/2addr', 0xbd: 'add-float/2addr', 0xbe: 'sub-float/2addr',
    0xbf: 'mul-float/2addr', 0xc0: 'div-float/2addr', 0xc1: 'rem-float/2addr',
    0xc2: 'add-double/2addr', 0xc3: 'sub-double/2addr', 0xc4: 'mul-double/2addr',
    0xc5: 'div-double/2addr', 0xc6: 'rem-double/2addr', 0xc7: 'add-int/lit16',
    0xc8: 'rsub-int', 0xc9: 'mul-int/lit16', 0xca: 'div-int/lit16',
    0xcb: 'rem-int/lit16', 0xcc: 'and-int/lit16', 0xcd: 'or-int/lit16',
    0xce: 'xor-int/lit16', 0xcf: 'shl-int/lit16', 0xd0: 'shr-int/lit16',
    0xd1: 'ushr-int/lit16', 0xd2: 'add-int/lit8', 0xd3: 'rsub-int/lit8',
    0xd4: 'mul-int/lit8', 0xd5: 'div-int/lit8', 0xd6: 'rem-int/lit8',
    0xd7: 'and-int/lit8', 0xd8: 'or-int/lit8', 0xd9: 'xor-int/lit8',
    0xda: 'shl-int/lit8', 0xdb: 'shr-int/lit8', 0xdc: 'ushr-int/lit8',
    0xdd: 'add-int/lit16?', 0xde: 'rsub?', 0xdf: 'mul?',
    0xe0: 'div?', 0xe1: 'rem?', 0xe2: 'and?', 0xe3: 'or?', 0xe4: 'xor?',
    0xe5: 'shl?', 0xe6: 'shr?', 0xe7: 'ushr?',
}

# width table in BYTES (= 2 * dex code units)
W = [2]*256
W[0x00]=2; W[0x01]=2; W[0x02]=4; W[0x03]=4; W[0x04]=2; W[0x05]=4; W[0x06]=4
W[0x07]=2; W[0x08]=4; W[0x09]=4; W[0x0a]=2; W[0x0b]=2; W[0x0c]=2; W[0x0d]=2
W[0x0e]=2; W[0x0f]=2; W[0x10]=2; W[0x11]=2; W[0x12]=2
W[0x13]=4; W[0x14]=6; W[0x15]=4; W[0x16]=4; W[0x17]=6; W[0x18]=10; W[0x19]=4
W[0x1a]=4; W[0x1b]=6; W[0x1c]=4; W[0x1d]=2; W[0x1e]=2; W[0x1f]=4; W[0x20]=4
W[0x21]=2; W[0x22]=4; W[0x23]=6; W[0x24]=6; W[0x25]=8; W[0x26]=6; W[0x27]=2
W[0x28]=2; W[0x29]=4; W[0x2a]=6; W[0x2b]=6; W[0x2c]=6
W[0x2d]=2; W[0x2e]=2; W[0x2f]=2; W[0x30]=2; W[0x31]=2
W[0x32]=2; W[0x33]=2; W[0x34]=2; W[0x35]=2; W[0x36]=2; W[0x37]=2
W[0x38]=2; W[0x39]=2; W[0x3a]=2; W[0x3b]=2; W[0x3c]=2; W[0x3d]=2
W[0x3e]=4; W[0x3f]=4; W[0x40]=4; W[0x41]=4; W[0x42]=4; W[0x43]=4; W[0x44]=4
W[0x45]=4; W[0x46]=4; W[0x47]=4; W[0x48]=4; W[0x49]=4; W[0x4a]=4; W[0x4b]=4
for o in range(0x4c, 0x68): W[o]=4
for o in range(0x68, 0x6d): W[o]=6
for o in range(0x6d, 0x72): W[o]=8
for o in range(0x72, 0x74): W[o]=2
for o in range(0x74, 0x79): W[o]=8
for o in range(0x79, 0x7b): W[o]=8
for o in range(0x7b, 0x87): W[o]=4
for o in range(0x87, 0xdd): W[o]=4
for o in range(0xdd, 0xf1): W[o]=2
W[0xf1]=4   # invoke-polymorphic (45cc, 2 units)
W[0xf3]=8   # invoke-polymorphic/range (4rcc, 4 units)

def decode(code_off, code, insns_count):
    out = []
    i = 0; n = len(code)
    while i < n:
        op = code[i]
        if i + 1 >= n: break
        regA = code[i+1] & 0x0f
        regB = code[i+1] >> 4
        base = INSN.get(op, '0x%02x' % op)
        width = W[op]
        detail = None
        if op == 0x00: width = 2; text = 'nop'
        elif op == 0x01: text = 'move v%d, v%d' % (regA, regB)
        elif op == 0x02: text = 'move/from16 v%d, v%d' % (regA, u16(code_off+i+2))
        elif op == 0x03: text = 'move/16 v%d, v%d' % (u16(code_off+i+2), u16(code_off+i+4))
        elif op == 0x04: text = 'move-wide v%d, v%d' % (regA, regB)
        elif op == 0x05: text = 'move-wide/from16 v%d, v%d' % (regA, u16(code_off+i+2))
        elif op == 0x06: text = 'move-wide/16 v%d, v%d' % (u16(code_off+i+2), u16(code_off+i+4))
        elif op == 0x07: text = 'move-object v%d, v%d' % (regA, regB)
        elif op == 0x08: text = 'move-object/from16 v%d, v%d' % (regA, u16(code_off+i+2))
        elif op == 0x09: text = 'move-object/16 v%d, v%d' % (u16(code_off+i+2), u16(code_off+i+4))
        elif 0x0a <= op <= 0x0b: text = base + ' v%d' % regA
        elif 0x0c <= op <= 0x0d: text = base + ' v%d' % regA
        elif op == 0x0e: text = 'return-void'
        elif op == 0x0f: text = 'return v%d' % regA
        elif op == 0x10: text = 'return-wide v%d' % regA
        elif op == 0x11: text = 'return-object v%d' % regA
        elif op == 0x12: text = 'const/4 v%d, #%d' % (regA, (code[i+1] >> 4 << 28) >> 28)
        elif op == 0x13: text = 'const/16 v%d, #%d' % (regA, s16(code_off+i+2))
        elif op == 0x14: text = 'const v%d, #%d' % (regA, s32(code_off+i+2))
        elif op == 0x15: text = 'const/high16 v%d, #%d' % (regA, s16(code_off+i+2) << 16)
        elif op == 0x16: text = 'const-wide/16 v%d, #%d' % (regA, s16(code_off+i+2))
        elif op == 0x17: text = 'const-wide/32 v%d, #%d' % (regA, s32(code_off+i+2))
        elif op == 0x18: text = 'const-wide v%d, #%d' % (regA, u32(code_off+i+4))
        elif op == 0x19: text = 'const-wide/high16 v%d, #%d' % (regA, s16(code_off+i+2) << 16)
        elif op == 0x1a:
            sidx = u16(code_off+i+2); text = "const-string v%d, %r" % (regA, strings[sidx] if sidx < len(strings) else '?')
            detail = sidx
        elif op == 0x1b:
            sidx = u32(code_off+i+2); text = "const-string/jumbo v%d, %r" % (regA, strings[sidx] if sidx < len(strings) else '?')
            detail = sidx
        elif op == 0x1c:
            tidx = u16(code_off+i+2); text = 'const-class v%d, %s' % (regA, fmt_type(tidx))
        elif op == 0x1d: text = 'monitor-enter v%d' % regA
        elif op == 0x1e: text = 'monitor-exit v%d' % regA
        elif op == 0x1f:
            tidx = u16(code_off+i+2); text = 'check-cast v%d, %s' % (regA, fmt_type(tidx))
        elif op == 0x20:
            tidx = u16(code_off+i+2); text = 'instance-of v%d, v%d, %s' % (regA, code[i+1]>>4, fmt_type(tidx))
        elif op == 0x21: text = 'array-length v%d, v%d' % (regA, regB)
        elif op == 0x22:
            tidx = u16(code_off+i+2); text = 'new-instance v%d, %s' % (regA, fmt_type(tidx))
        elif op == 0x23:
            tidx = u16(code_off+i+2); text = 'new-array v%d, v%d, %s' % (regA, regB, fmt_type(tidx))
        elif op == 0x24:
            mid = u16(code_off+i+2); text = 'filled-new-array {v%d..}, %s' % (regA, fmt_mid(mid))
        elif op == 0x25:
            mid = u16(code_off+i+2); text = 'filled-new-array/range {v%d..}, %s' % (regA, fmt_mid(mid))
        elif op == 0x26: text = 'fill-array-data v%d, +%d' % (regA, s32(code_off+i+2))
        elif op == 0x27: text = 'throw v%d' % regA
        elif op == 0x28: text = 'goto +%d' % s16(code_off+i+1)
        elif op == 0x29: text = 'goto/16 +%d' % s16(code_off+i+2)
        elif op == 0x2a: text = 'goto/32 +%d' % s32(code_off+i+2)
        elif 0x2b <= op <= 0x2c: text = '%s v%d, +%d' % (base, regA, s32(code_off+i+2))
        elif 0x2d <= op <= 0x31: text = '%s v%d, v%d, v%d' % (base, regA, code[i+1]>>4, code[i+2])
        elif 0x32 <= op <= 0x37: text = '%s v%d, v%d, +%d' % (base, regA, regB, s16(code_off+i+2))
        elif 0x38 <= op <= 0x3d: text = '%s v%d, +%d' % (base, regA, s16(code_off+i+2))
        elif 0x3e <= op <= 0x44: text = '%s v%d, v%d, v%d' % (base, regA, regB, code[i+2])
        elif 0x45 <= op <= 0x4b: text = '%s v%d, v%d, v%d' % (base, regA, regB, code[i+2])
        elif 0x4c <= op <= 0x52:
            fidx = u16(code_off+i+2); text = '%s v%d, v%d, field#%d' % (base, regA, regB, fidx)
        elif 0x53 <= op <= 0x59:
            fidx = u16(code_off+i+2); text = '%s v%d, v%d, field#%d' % (base, regA, regB, fidx)
        elif 0x5a <= op <= 0x60:
            fidx = u16(code_off+i+2); text = '%s v%d, field#%d' % (base, regA, fidx)
        elif 0x61 <= op <= 0x67:
            fidx = u16(code_off+i+2); text = '%s v%d, field#%d' % (base, regA, fidx)
        elif 0x68 <= op <= 0x6c:
            mid = u16(code_off+i+2); text = '%s {v%d..v%d}, %s' % (base, regA, regA + (code[i+1]>>4), fmt_mid(mid))
            detail = mid
        elif 0x6d <= op <= 0x71:
            mid = u16(code_off+i+2); text = '%s/range {v%d..v%d}, %s' % (base, regA, code[i+1]>>4, fmt_mid(mid))
            detail = mid
        elif op == 0x72: text = 'neg-int v%d, v%d' % (regA, regB)
        elif op == 0x73: text = 'not-int v%d, v%d' % (regA, regB)
        elif op == 0x74: text = 'neg-long v%d, v%d' % (regA, regB)
        elif op == 0x75: text = 'not-long v%d, v%d' % (regA, regB)
        elif op == 0x76: text = 'neg-float v%d, v%d' % (regA, regB)
        elif op == 0x77: text = 'neg-double v%d, v%d' % (regA, regB)
        elif 0x78 <= op <= 0x83: text = '%s v%d, v%d' % (base, regA, regB)
        elif 0x84 <= op <= 0x86: text = '%s v%d, v%d' % (base, regA, regB)
        elif 0x87 <= op <= 0x91: text = '%s v%d, v%d, v%d' % (base, regA, regB, code[i+2])
        elif 0x92 <= op <= 0x9c: text = '%s v%d, v%d, v%d' % (base, regA, regB, code[i+2])
        elif 0x9d <= op <= 0xa6: text = '%s v%d, v%d, v%d' % (base, regA, regB, code[i+2])
        elif 0xa7 <= op <= 0xc6: text = '%s v%d, v%d' % (base, regA, regB)
        elif 0xc7 <= op <= 0xd1:
            lit = s16(code_off+i+2); text = '%s v%d, v%d, #%d' % (base, regA, regB, lit)
        elif 0xd2 <= op <= 0xdc:
            lit = (code[i+2] << 24) >> 24; text = '%s v%d, v%d, #%d' % (base, regA, regB, lit)
        elif op == 0xdd: text = 'add-int/lit16? v%d, v%d' % (regA, regB)
        elif op == 0xde: text = 'rsub? v%d, v%d' % (regA, regB)
        elif 0xdf <= op <= 0xe7: text = '%s v%d, v%d' % (base, regA, regB)
        elif 0xe8 <= op <= 0xf0: text = '0x%02x' % op
        elif 0xf1 <= op <= 0xf7: text = '0x%02x' % op
        elif 0xf8 <= op <= 0xfe: text = '0x%02x' % op
        elif op == 0xff: text = '0xff invoke-polymorphic'
        else: text = 'op%02x' % op
        out.append((i, text, detail))
        i += width
    return out

# find the target class
want_cls = sys.argv[2]
want_names = set(sys.argv[3:])
found = False
for ci in range(class_defs_size):
    o = class_defs_off + 32*ci
    cls = types[u32(o)] if u32(o) < len(types) else '?'
    if cls != want_cls: continue
    cdo = u32(o + 24)
    if not cdo: continue
    p = cdo
    sf, p = uleb(p); inf, p = uleb(p)
    dm, p = uleb(p); vm, p = uleb(p)
    for _ in range(sf + inf): _, p = uleb(p); _, p = uleb(p)
    prev = 0
    for _ in range(dm):
        diff, p = uleb(p); prev += diff
        acc, p = uleb(p); co, p = uleb(p)
        name = strings[mname[prev]] if prev < NMID and mname[prev] < NSTR else '?'
        if name not in want_names: continue
        found = True
        print('=== %s::%s (code_off=0x%x) ===' % (cls, name, co))
        if not co:
            print('  (abstract/native, no code)')
            continue
        regs = u16(co); ins = u16(co+2); outs = u16(co+4)
        insns = u32(co+12)
        code = data[co+16: co+16+insns*2]
        print('  regs=%d ins=%d outs=%d insns=%d' % (regs, ins, outs, insns))
        for addr, text, detail in decode(co, code, insns):
            print('  %04x: %s' % (addr, text))
    prev = 0
    for _ in range(vm):
        diff, p = uleb(p); prev += diff
        acc, p = uleb(p); co, p = uleb(p)
        name = strings[mname[prev]] if prev < NMID and mname[prev] < NSTR else '?'
        if name not in want_names: continue
        found = True
        print('=== %s::%s (code_off=0x%x) ===' % (cls, name, co))
        if not co:
            print('  (abstract/native, no code)')
            continue
        regs = u16(co); ins = u16(co+2); outs = u16(co+4)
        insns = u32(co+12)
        code = data[co+16: co+16+insns*2]
        print('  regs=%d ins=%d outs=%d insns=%d' % (regs, ins, outs, insns))
        for addr, text, detail in decode(co, code, insns):
            print('  %04x: %s' % (addr, text))
    if not found:
        print('class or method not found: %s %s' % (want_cls, ' '.join(want_names)))
