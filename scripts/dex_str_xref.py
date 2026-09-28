#!/usr/bin/env python3
"""扫描 dex 中指定 string_id 的 const-string 引用, 输出所在类/方法与调用上下文。
用法: python3 dex_str_xref.py <dex> <string_id...>
"""
import struct, sys

def uleb128(d, off):
    r = 0; s = 0
    while True:
        b = d[off]; off += 1
        r |= (b & 0x7f) << s
        if not (b & 0x80): break
        s += 7
    return r, off

def parse_dex(path):
    data = open(path, 'rb').read()
    def g32(off): return struct.unpack_from('<I', data, off)[0]
    def g16(off): return struct.unpack_from('<H', data, off)[0]
    hdr = {
        'string_ids_size': g32(56), 'string_ids_off': g32(60),
        'type_ids_size': g32(64), 'type_ids_off': g32(68),
        'proto_ids_size': g32(72), 'proto_ids_off': g32(76),
        'field_ids_size': g32(80), 'field_ids_off': g32(84),
        'method_ids_size': g32(88), 'method_ids_off': g32(92),
        'class_defs_size': g32(96), 'class_defs_off': g32(100),
    }
    strs = {}
    for i in range(hdr['string_ids_size']):
        soff = g32(hdr['string_ids_off'] + i*4)
        ln, e = uleb128(data, soff)
        raw = data[e:e+ln]
        try: strs[i] = raw.decode('utf-8', 'replace')
        except: strs[i] = repr(raw)
    types = {}
    for i in range(hdr['type_ids_size']):
        types[i] = strs[g32(hdr['type_ids_off'] + i*4)]
    methods = {}
    for i in range(hdr['method_ids_size']):
        off = hdr['method_ids_off'] + i*8
        cls_idx, proto_idx, name_idx = struct.unpack_from('<HHI', data, off)
        methods[i] = (types[cls_idx], strs[name_idx], proto_idx)
    classes = []
    for i in range(hdr['class_defs_size']):
        off = hdr['class_defs_off'] + i*32
        cls_idx, acc, super_idx, iface_off, src_idx, annot_off, cls_data_off, static_off = struct.unpack_from('<IIIIIIII', data, off)
        classes.append((i, cls_idx, acc, cls_data_off))
    # 方法名索引缓存
    name_by_idx = {i: strs[g32(hdr['method_ids_off']+i*8+4)] for i in range(hdr['method_ids_size'])}
    return data, hdr, strs, types, methods, classes

def class_methods(data, hdr, cls_data_off):
    """解析 class_data_item, 返回 (方法列表, 每项的 code_off)"""
    out = []
    if cls_data_off == 0:
        return out
    off = cls_data_off
    static_fields_size, off = uleb128(data, off)
    instance_fields_size, off = uleb128(data, off)
    direct_methods_size, off = uleb128(data, off)
    virtual_methods_size, off = uleb128(data, off)
    # 跳过字段条目: 每字段 2 个 uleb128 (idx_diff, access_flags)
    for _ in range(static_fields_size + instance_fields_size):
        _, off = uleb128(data, off)
        _, off = uleb128(data, off)
    idx = 0
    for m in range(direct_methods_size):
        d, off = uleb128(data, off); idx += d
        acc, off = uleb128(data, off)
        code_off, off = uleb128(data, off)
        out.append((idx, acc, code_off))
    idx = 0
    for m in range(virtual_methods_size):
        d, off = uleb128(data, off); idx += d
        acc, off = uleb128(data, off)
        code_off, off = uleb128(data, off)
        out.append((idx, acc, code_off))
    return out

def scan_const_string(data, code_off, target_ids, file_len, string_ids_size):
    """扫描 code_item 的 const-string, 返回命中的指令地址列表"""
    # code_item: 4x u16 (registers/ins/outs/tries), u32 debug_off@+8, u32 insns_size@+12, u16 insns[]@+16
    insns_size2 = struct.unpack_from('<I', data, code_off + 12)[0]
    insns_start = code_off + 16
    if insns_start + insns_size2*2 > file_len:
        return []
    insns_bytes = insns_size2 * 2
    hits = []
    pc = insns_start
    end = insns_start + insns_bytes - 4
    while pc <= end:
        insn = struct.unpack_from('<H', data, pc)[0]
        op = insn & 0xff
        if op == 0x1a:  # const-string vAA, string@BBBB
            str_idx = struct.unpack_from('<H', data, pc+2)[0]
            if str_idx in target_ids and str_idx < string_ids_size:
                hits.append((pc, str_idx))
            pc += 4
        elif op == 0x1b:  # const-string/jumbo vAAAA, string@BBBBBBBB
            str_idx = struct.unpack_from('<I', data, pc+2)[0]
            if str_idx in target_ids and str_idx < string_ids_size:
                hits.append((pc, str_idx))
            pc += 6
        else:
            # 按 op 高 4 位推断长度; 不确定时按 2 字节步进(窗口扫描)
            hi = op >> 4
            if hi == 0x0:
                pc += 2
            elif hi == 0x1:
                pc += 4 if op not in (0x11, 0x12) else 2
            elif hi == 0x2:
                pc += 4
            elif hi == 0x3:
                pc += 6
            elif hi == 0x8:
                pc += 6
            elif hi == 0x9:
                pc += 4
            else:
                pc += 2
    return hits

def main():
    path = sys.argv[1]
    target_ids = set(int(x, 0) for x in sys.argv[2:])
    data, hdr, strs, types, methods, classes = parse_dex(path)
    print("target string ids:", {t: strs[t] for t in target_ids})
    method_ids_size = hdr['method_ids_size']
    file_len = len(data)
    # 建立 method_idx → (class, name, proto)
    method_idx_map = {}
    for c in classes:
        ci, cls_idx, acc, cls_data_off = c
        if cls_data_off == 0: continue
        for (m_idx, m_acc, code_off) in class_methods(data, hdr, cls_data_off):
            if m_idx not in methods: continue
            cls, name, proto = methods[m_idx]
            method_idx_map[m_idx] = (cls, name, code_off)
            if code_off == 0: continue
            if code_off + 20 > file_len: continue
            hits = scan_const_string(data, code_off, target_ids, file_len, hdr['string_ids_size'])
            for pc, si in hits:
                print("%s->%s  code_off=0x%x insn_pc=0x%x  const-string '%s'" % (
                    cls, name, code_off, pc, strs[si]))

if __name__ == '__main__':
    main()
