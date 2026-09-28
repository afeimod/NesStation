#!/usr/bin/env python3
"""精确 dex 反汇编工具。
用法:
  python3 dex_dis.py <dex> <class-substr> <method-substr>       # 反汇编匹配的类/方法
  python3 dex_dis.py <dex> --callers <method-substr> [--max N]  # 扫描所有调用该方法的调用者
  python3 dex_dis.py <dex> --str <string-substr> [--max N]      # 扫描 const-string 引用
"""
import struct, sys

def uleb(data, p):
    r = 0; s = 0
    while True:
        b = data[p]; p += 1
        r |= (b & 0x7f) << s
        if not (b & 0x80): return r, p
        s += 7

# units -> bytes(×2)
U = {}
U.update(dict.fromkeys([0x00,0x01,0x04,0x07,0x0a,0x0b,0x0c,0x0d,0x0e,0x0f,0x10,0x11,0x12,
                         0x1d,0x1e,0x1f,0x20,0x21,0x27,0x28], 1))
U.update(dict.fromkeys(range(0x9b,0xb6), 1))
U.update(dict.fromkeys(range(0xb6,0xcb), 1))
U.update(dict.fromkeys([0x02,0x03,0x05,0x06,0x08,0x09,0x13,0x15,0x16,0x19,0x1a,0x1c,0x22], 2))
U.update(dict.fromkeys([0x29,0x32,0x33,0x34,0x35,0x36,0x37,0x38,0x39,0x3a,0x3b,0x3c,0x3d], 2))
U.update(dict.fromkeys(range(0x4c,0x68), 2))
U.update(dict.fromkeys([0x72,0x79,0x7a,0x7b,0x7f], 2))
U.update(dict.fromkeys(range(0xcb,0xde), 2))
U.update(dict.fromkeys([0xfb,0xff], 2))
U.update(dict.fromkeys([0x14,0x17,0x1b,0x23,0x24,0x26,0x2a,0x2b,0x2c,0x2d,0x2e,0x2f,0x30,0x31], 3))
U.update(dict.fromkeys(range(0x3e,0x4c), 3))
U.update(dict.fromkeys(range(0x6e,0x73), 3))
U.update(dict.fromkeys([0xfd], 3))
U.update(dict.fromkeys([0x25,0x74,0x75,0x76,0x77,0x78,0xfc,0xfe], 4))
U[0x18] = 5
def W(op): return U.get(op, 2) * 2

INSN = {
0x00:'nop',0x01:'move',0x02:'move/from16',0x03:'move/16',0x04:'move-wide',
0x05:'move-wide/from16',0x06:'move-wide/16',0x07:'move-object',0x08:'move-object/from16',
0x09:'move-object/16',0x0a:'move-result',0x0b:'move-result-wide',0x0c:'move-result-object',
0x0d:'move-exception',0x0e:'return-void',0x0f:'return',0x10:'return-wide',0x11:'return-object',
0x12:'const/4',0x13:'const/16',0x14:'const',0x15:'const/high16',0x16:'const-wide/16',
0x17:'const-wide/32',0x18:'const-wide',0x19:'const-wide/high16',0x1a:'const-string',
0x1b:'const-string/jumbo',0x1c:'const-class',0x1d:'monitor-enter',0x1e:'monitor-exit',
0x1f:'check-cast',0x20:'instance-of',0x21:'array-length',0x22:'new-instance',0x23:'new-array',
0x24:'filled-new-array',0x25:'filled-new-array/range',0x26:'fill-array-data',
0x27:'return-void-no-barrier',0x28:'goto',0x29:'goto/16',0x2a:'goto/32',
0x2b:'packed-switch',0x2c:'sparse-switch',0x2d:'cmpl-float',0x2e:'cmpg-float',
0x2f:'cmpl-double',0x30:'cmpg-double',0x31:'cmp-long',0x32:'if-eq',0x33:'if-ne',
0x34:'if-lt',0x35:'if-ge',0x36:'if-gt',0x37:'if-le',0x38:'if-eqz',0x39:'if-nez',
0x3a:'if-ltz',0x3b:'if-gez',0x3c:'if-gtz',0x3d:'if-lez',0x3e:'aget',0x3f:'aget-wide',
0x40:'aget-object',0x41:'aget-boolean',0x42:'aget-byte',0x43:'aget-char',0x44:'aget-short',
0x45:'aput',0x46:'aput-wide',0x47:'aput-object',0x48:'aput-boolean',0x49:'aput-byte',
0x4a:'aput-char',0x4b:'aput-short',0x4c:'iget',0x4d:'iget-wide',0x4e:'iget-object',
0x4f:'iget-boolean',0x50:'iget-byte',0x51:'iget-char',0x52:'iget-short',0x53:'iput',
0x54:'iput-wide',0x55:'iput-object',0x56:'iput-boolean',0x57:'iput-byte',0x58:'iput-char',
0x59:'iput-short',0x5a:'sget',0x5b:'sget-wide',0x5c:'sget-object',0x5d:'sget-boolean',
0x5e:'sget-byte',0x5f:'sget-char',0x60:'sget-short',0x61:'sput',0x62:'sput-wide',
0x63:'sput-object',0x64:'sput-boolean',0x65:'sput-byte',0x66:'sput-char',0x67:'sput-short',
0x6e:'invoke-virtual',0x6f:'invoke-super',0x70:'invoke-direct',0x71:'invoke-static',
0x72:'invoke-interface',0x73:'unused',0x74:'invoke-virtual/range',
0x75:'invoke-super/range',0x76:'invoke-direct/range',0x77:'invoke-static/range',
0x78:'invoke-interface/range',0x79:'const-method-handle',0x7a:'const-method-type',
0x7b:'const-method-handle',0x7c:'unused',0x7d:'unused',0x7e:'unused',0x7f:'const-method-type',
0x80:'add-int',0x81:'sub-int',0x82:'mul-int',0x83:'div-int',0x84:'rem-int',0x85:'and-int',
0x86:'or-int',0x87:'xor-int',0x88:'shl-int',0x89:'shr-int',0x8a:'ushr-int',0x8b:'add-long',
0x8c:'sub-long',0x8d:'mul-long',0x8e:'div-long',0x8f:'rem-long',0x90:'and-long',
0x91:'or-long',0x92:'xor-long',0x93:'shl-long',0x94:'shr-long',0x95:'ushr-long',
0x96:'add-float',0x97:'sub-float',0x98:'mul-float',0x99:'div-float',0x9a:'rem-float',
0x9b:'add-double',0x9c:'sub-double',0x9d:'mul-double',0x9e:'div-double',0x9f:'rem-double',
0xa0:'add-int/2addr',0xa1:'sub-int/2addr',0xa2:'mul-int/2addr',0xa3:'div-int/2addr',
0xa4:'rem-int/2addr',0xa5:'and-int/2addr',0xa6:'or-int/2addr',0xa7:'xor-int/2addr',
0xa8:'shl-int/2addr',0xa9:'shr-int/2addr',0xaa:'ushr-int/2addr',0xab:'add-long/2addr',
0xac:'sub-long/2addr',0xad:'mul-long/2addr',0xae:'div-long/2addr',0xaf:'rem-long/2addr',
0xb0:'and-long/2addr',0xb1:'or-long/2addr',0xb2:'xor-long/2addr',0xb3:'shl-long/2addr',
0xb4:'shr-long/2addr',0xb5:'ushr-long/2addr',0xb6:'add-float/2addr',0xb7:'sub-float/2addr',
0xb8:'mul-float/2addr',0xb9:'div-float/2addr',0xba:'rem-float/2addr',0xbb:'add-double/2addr',
0xbc:'sub-double/2addr',0xbd:'mul-double/2addr',0xbe:'div-double/2addr',0xbf:'rem-double/2addr',
0xc0:'neg-int',0xc1:'not-int',0xc2:'neg-long',0xc3:'not-long',0xc4:'neg-float',
0xc5:'neg-double',0xc6:'int-to-long',0xc7:'int-to-float',0xc8:'int-to-double',
0xc9:'long-to-int',0xca:'long-to-float',0xcb:'long-to-double',0xcc:'float-to-int',
0xcd:'float-to-long',0xce:'float-to-double',0xcf:'double-to-int',0xd0:'double-to-long',
0xd1:'double-to-float',0xd2:'int-to-byte',0xd3:'int-to-char',0xd4:'int-to-short',
0xd5:'add-int/lit16',0xd6:'rsub-int',0xd7:'mul-int/lit16',0xd8:'div-int/lit16',
0xd9:'rem-int/lit16',0xda:'and-int/lit16',0xdb:'or-int/lit16',0xdc:'xor-int/lit16',
0xdd:'add-int/lit8',0xde:'rsub-int/lit8',0xdf:'mul-int/lit8',0xe0:'div-int/lit8',
0xe1:'rem-int/lit8',0xe2:'and-int/lit8',0xe3:'or-int/lit8',0xe4:'xor-int/lit8',
0xe5:'shl-int/lit8',0xe6:'shr-int/lit8',0xe7:'ushr-int/lit8',
0xf1:'invoke-polymorphic',0xf3:'invoke-polymorphic/range',0xfb:'invoke-polymorphic',
0xfc:'invoke-polymorphic/range',0xfd:'invoke-custom',0xfe:'invoke-custom/range',
}

def parse_dex(path):
    data = open(path, 'rb').read()
    def g32(o): return struct.unpack_from('<I', data, o)[0]
    def g16(o): return struct.unpack_from('<H', data, o)[0]
    H = {
        'ss': g32(56), 'so': g32(60), 'ts': g32(64), 'to': g32(68),
        'ps': g32(72), 'po': g32(76), 'fs': g32(80), 'fo': g32(84),
        'ms': g32(88), 'mo': g32(92), 'cs': g32(96), 'co': g32(100),
    }
    strs = []
    for i in range(H['ss']):
        soff = g32(H['so'] + i*4)
        ln, e = uleb(data, soff)
        strs.append(data[e:e+ln].decode('utf-8', 'replace'))
    types = [strs[g32(H['to'] + i*4)] for i in range(H['ts'])]
    mname = [g32(H['mo'] + i*8 + 4) for i in range(H['ms'])]
    mcls  = [g16(H['mo'] + i*8)     for i in range(H['ms'])]
    classes = []
    for i in range(H['cs']):
        off = H['co'] + i*32
        classes.append((i, g32(off), g32(off+24)))  # (idx, cls_idx, class_data_off)
    return data, H, strs, types, mcls, mname, classes

def class_methods(data, cdo):
    """返回 [(idx, acc, code_off)], direct/virtual 各自相对 0 累加"""
    out = []
    if not cdo: return out
    off = cdo
    sf, off = uleb(data, off); inf, off = uleb(data, off)
    dm, off = uleb(data, off); vm, off = uleb(data, off)
    for _ in range(sf + inf):
        _, off = uleb(data, off); _, off = uleb(data, off)
    for cnt in (dm, vm):
        prev = 0
        for _ in range(cnt):
            d, off = uleb(data, off); prev += d
            acc, off = uleb(data, off)
            cof, off = uleb(data, off)
            out.append((prev, acc, cof))
    return out

def decode(data, code_off, code, strs, types, mcls, mname, fmt_mid):
    """返回 [(addr, text, mid_or_str)]"""
    out = []
    i = 0; n = len(code)
    while i < n:
        op = code[i]
        w = W(op)
        if i + w > n:
            out.append((i, '?? truncated', None)); break
        text = INSN.get(op, '??%02x' % op)
        mid = None; sidx = None
        if 0x6e <= op <= 0x72:
            mid = struct.unpack_from('<H', code, i+4)[0]
            text += ' ' + fmt_mid(mid)
        elif 0x74 <= op <= 0x78:
            mid = struct.unpack_from('<H', code, i+2)[0]
            text += ' ' + fmt_mid(mid)
        elif op == 0x1a or op == 0x1b:
            sidx = struct.unpack_from('<H' if op == 0x1a else '<I', code, i+2)[0]
            text += ' %r' % (strs[sidx] if sidx < len(strs) else '?%d' % sidx)
        elif op in (0x22, 0x1c):
            t = struct.unpack_from('<H', code, i+2)[0]
            text += ' %s' % (types[t] if t < len(types) else '?%d' % t)
        out.append((i, text, mid if mid is not None else sidx))
        i += w
    return out

def main():
    args = sys.argv[1:]
    path = args[0]
    data, H, strs, types, mcls, mname, classes = parse_dex(path)
    def fmt_mid(m):
        if m >= len(mcls): return '<bad%d>' % m
        c = types[mcls[m]] if mcls[m] < len(types) else '?'
        nm = strs[mname[m]] if mname[m] < len(strs) else '?'
        return '%s::%s' % (c, nm)
    mode = args[1] if len(args) > 1 else ''
    if mode == '--callers':
        target = args[2]
        tids = set(i for i in range(len(mname)) if target in strs[mname[i]])
        print('targets:', [(i, fmt_mid(i)) for i in sorted(tids)])
        mx = int(args[args.index('--max')+1]) if '--max' in args else 40
        cnt = 0
        for c in classes:
            for (idx, acc, cof) in class_methods(data, c[2]):
                if not cof: continue
                insns = struct.unpack_from('<I', data, cof+12)[0]
                if cof+16+insns*2 > len(data): continue
                code = data[cof+16: cof+16+insns*2]
                hits = [a for a, t, m in decode(data, cof, code, strs, types, mcls, mname, fmt_mid)
                        if m in tids]
                if hits:
                    cls = types[c[1]] if c[1] < len(types) else '?'
                    nm = strs[mname[idx]] if mname[idx] < len(strs) else '?'
                    print('=== %s::%s ===' % (cls, nm))
                    for a, t, m in decode(data, cof, code, strs, types, mcls, mname, fmt_mid):
                        mark = '>>' if m in tids else '  '
                        print('  %s %04x  %s' % (mark, a, t))
                    cnt += 1
                    if cnt >= mx: return
    elif mode == '--str':
        target = args[2]
        tids = set(i for i in range(len(strs)) if target in strs[i])
        print('target strs:', [(i, strs[i]) for i in sorted(tids)])
        mx = int(args[args.index('--max')+1]) if '--max' in args else 40
        cnt = 0
        for c in classes:
            for (idx, acc, cof) in class_methods(data, c[2]):
                if not cof: continue
                insns = struct.unpack_from('<I', data, cof+12)[0]
                if cof+16+insns*2 > len(data): continue
                code = data[cof+16: cof+16+insns*2]
                for a, t, m in decode(data, cof, code, strs, types, mcls, mname, fmt_mid):
                    if m in tids:
                        cls = types[c[1]] if c[1] < len(types) else '?'
                        nm = strs[mname[idx]] if mname[idx] < len(strs) else '?'
                        print('%s::%s  code=0x%x pc=0x%x  %s' % (cls, nm, cof, a, t))
                        cnt += 1
                        if cnt >= mx: return
    else:
        cls_sub = args[1]; m_sub = args[2] if len(args) > 2 else ''
        mx = int(args[args.index('--max')+1]) if '--max' in args else 8
        shown = 0
        for c in classes:
            cls = types[c[1]] if c[1] < len(types) else '?'
            if cls_sub not in cls: continue
            for (idx, acc, cof) in class_methods(data, c[2]):
                nm = strs[mname[idx]] if mname[idx] < len(strs) else '?'
                if m_sub and m_sub not in nm: continue
                if not cof: continue
                insns = struct.unpack_from('<I', data, cof+12)[0]
                if cof+16+insns*2 > len(data): continue
                code = data[cof+16: cof+16+insns*2]
                print('=== %s::%s (code=0x%x insns=%d) ===' % (cls, nm, cof, insns))
                for a, t, m in decode(data, cof, code, strs, types, mcls, mname, fmt_mid):
                    print('  %04x  %s' % (a, t))
                shown += 1
                if shown >= mx: return

if __name__ == '__main__':
    main()
