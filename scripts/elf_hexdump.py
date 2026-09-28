#!/usr/bin/env python3
"""Read raw bytes from an ELF at a virtual address. Usage: elf_hexdump.py <elf> <vaddr> <nbytes>"""
import struct
import sys

data = open(sys.argv[1], 'rb').read()
va = int(sys.argv[2], 16)
n = int(sys.argv[3], 16)

def u16(o): return struct.unpack_from('<H', data, o)[0]
def u32(o): return struct.unpack_from('<I', data, o)[0]
def u64(o): return struct.unpack_from('<Q', data, o)[0]

e_shoff = u64(0x28); e_shentsize = u16(0x3a); e_shnum = u16(0x3c); e_shstrndx = u16(0x3e)
sections = []
for i in range(e_shnum):
    o = e_shoff + i*e_shentsize
    sections.append(dict(name=u32(o), type=u32(o+4), addr=u64(o+0x10), offset=u64(o+0x18), size=u64(o+0x20)))
shstr = sections[e_shstrndx]
shstr_data = data[shstr['offset']:shstr['offset']+shstr['size']]
def sh_name(idx):
    end = shstr_data.find(b'\0', idx)
    return shstr_data[idx:end].decode('utf-8', 'replace')
for s in sections: s['sh_name'] = sh_name(s['name'])

base_vaddr = min(s['addr'] for s in sections if s['size'] > 0 and s['addr'] > 0)
file_off = None
for s in sections:
    if s['addr'] <= va < s['addr'] + s['size']:
        file_off = s['offset'] + (va - s['addr']); break
if file_off is None:
    # try first loadable segment via program headers
    e_phoff = u64(0x20); e_phentsize = u16(0x36); e_phnum = u16(0x38)
    for i in range(e_phnum):
        o = e_phoff + i*e_phentsize
        p_type = u32(o); p_offset = u64(o+8); p_vaddr = u64(o+0x10); p_filesz = u64(o+0x20)
        if p_type == 1 and p_vaddr <= va < p_vaddr + p_filesz:
            file_off = p_offset + (va - p_vaddr); break
if file_off is None:
    print('address 0x%x not in any section/segment' % va); sys.exit(1)

blob = data[file_off:file_off+n]
# dump as words
print('vaddr 0x%x  file_off 0x%x' % (va, file_off))
for o in range(0, len(blob), 16):
    row = blob[o:o+16]
    hexs = ' '.join('%02x' % b for b in row)
    # interpret 4-byte LE words
    words = []
    for i in range(0, 16, 4):
        if i+4 <= len(row): words.append(struct.unpack_from('<I', row, i)[0])
    print('%08x  %-48s  %s' % (va+o, hexs, ' '.join('%d' % w for w in words)))