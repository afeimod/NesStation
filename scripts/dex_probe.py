import zipfile, struct
import sys

apk = sys.argv[1]
z = zipfile.ZipFile(apk)
data = z.read([n for n in z.namelist() if n.endswith('.dex')][0])

def u16(off): return struct.unpack_from('<H', data, off)[0]
def u32(off): return struct.unpack_from('<I', data, off)[0]
def vlen(data, off):
    i = off
    while data[i] & 0x80:
        i += 1
    return (data[off] & 0x7f) + 1, i - off + 1  # val, size

string_ids_size = u32(0x38); string_ids_off = u32(0x3c)
type_ids_size = u32(0x40); type_ids_off = u32(0x44)
method_ids_size = u32(0x58); method_ids_off = u32(0x5c)
class_defs_size = u32(0x60); class_defs_off = u32(0x64)

strings = [None] * string_ids_size
for i in range(string_ids_size):
    data_off = u32(string_ids_off + 4 * i)
    pos = data_off
    # ULEB128 length
    length = 0; shift = 0
    while True:
        b = data[pos]; pos += 1
        length |= (b & 0x7f) << shift
        if not (b & 0x80):
            break
        shift += 7
    s = data[pos:pos + length].decode('utf-8', 'ignore')
    strings[i] = s

type_strings = []
for i in range(type_ids_size):
    desc_idx = u32(type_ids_off + 4 * i)
    type_strings.append(strings[desc_idx])

mnames = {}
for i in range(method_ids_size):
    off = method_ids_off + 16 * i
    name_idx = u32(off + 4)
    mnames[i] = name_idx

print('total strings', string_ids_size)
targets = ['InternalResolution', 'Video_Settings', 'config.ini', 'GFX.ini', 'Dolphin.ini',
           'SetConfig', 'AspectRatio', 'ShowFPS', 'WaitForShadersBeforeStarting',
           'MaxAnisotropy', 'EFBScaledCopy', 'EFBToTextureEnable', 'EFBAccessEnable',
           'SettingsFile', 'EFBScale', 'InternalResolutionFrameDumps', 'MSAA', 'graphics_api',
           'config', 'Vulkan']
for target in targets:
    idxs = [i for i, s in enumerate(strings) if s == target]
    print(target, '->', idxs)

# Walk class_defs: for each method with code, scan code for const-string references
# to detect which classes use the target strings
use_map = {t: set() for t in targets}
for i in range(class_defs_size):
    off = class_defs_off + 32 * i
    class_idx = u32(off)
    class_data_off = u32(off + 24)
    cls = type_strings[class_idx] if class_idx < len(type_strings) else '?'
    if not class_data_off:
        continue
    p = class_data_off
    # skip fields
    for _ in range(3):
        val, sz = vlen(data, p); p += sz  # ignore val, advance by size
    # Hmm: need fields_size, methods_size, direct_size properly. Simpler: parse robustly below.
    # (this rough pass may misparse; fallback handled)

# Robust class_data parsing
for i in range(class_defs_size):
    off = class_defs_off + 32 * i
    class_idx = u32(off)
    class_data_off = u32(off + 24)
    cls = type_strings[class_idx] if class_idx < len(type_strings) else '?'
    if not class_data_off:
        continue
    p = class_data_off
    def uleb(p):
        r = 0; s = 0
        while True:
            b = data[p]; p += 1
            r |= (b & 0x7f) << s
            if not (b & 0x80):
                return r, p
            s += 7
    fields_size, p = uleb(p)
    methods_size, p = uleb(p)
    direct_size, p = uleb(p)
    for _ in range(fields_size):
        _, p = uleb(p)
        _, p = uleb(p)
    prev = 0
    for m in range(methods_size):
        diff, p = uleb(p); prev += diff
        _, p = uleb(p)  # access
        code_off, p = uleb(p)
        mid = prev
        name_idx = mnames[mid]
        mname = strings[name_idx]
        if mname in ('SetConfig',):
            print('SetConfig defined in', cls)
        if not code_off:
            continue
        # scan code item for const-string opcodes (0x1a, 0x1b) referencing string ids
        instrs_size = u32(code_off + 12)
        code = data[code_off + 16:code_off + 16 + instrs_size * 2]
        for opi in range(0, len(code) - 1, 2):
            op = code[opi]
            if op == 0x1a:
                sidx = u16(code_off + 16 + opi + 2)
                s = strings[sidx] if sidx < len(strings) else None
                if s in use_map:
                    use_map[s].add(cls + '::' + mname)
                    if s == 'InternalResolution' or s == 'Video_Settings':
                        pass

print('--- usage map ---')
for t, us in use_map.items():
    if us:
        print(t)
        for u in sorted(us):
            print('   ', u)