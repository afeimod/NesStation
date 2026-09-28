#!/usr/bin/env python3
"""List ELF .dynsym symbols filtered by substring. Usage: elf_dynsym.py <elf> [substr]"""
import struct
import sys

data = open(sys.argv[1], 'rb').read()
needle = sys.argv[2] if len(sys.argv) > 2 else ''

def u16(o): return struct.unpack_from('<H', data, o)[0]
def u32(o): return struct.unpack_from('<I', data, o)[0]
def u64(o): return struct.unpack_from('<Q', data, o)[0]

# ELF64 header (assume little-endian)
e_shoff = u64(0x28); e_shentsize = u16(0x3a); e_shnum = u16(0x3c); e_shstrndx = u16(0x3e)
sections = []
for i in range(e_shnum):
    o = e_shoff + i*e_shentsize
    sections.append({
        'name': u32(o), 'type': u32(o+4), 'flags': u64(o+8), 'addr': u64(o+0x10),
        'offset': u64(o+0x18), 'size': u64(o+0x20), 'link': u32(o+0x28),
        'info': u32(o+0x2c), 'align': u64(o+0x30), 'entsize': u64(o+0x38),
    })
shstr = sections[e_shstrndx]
shstr_data = data[shstr['offset']:shstr['offset']+shstr['size']]
def sh_name(idx):
    end = shstr_data.find(b'\0', idx)
    return shstr_data[idx:end].decode('utf-8', 'replace')
for i, s in enumerate(sections):
    sections[i]['sh_name'] = sh_name(s['name'])

strtab = None; dynsym = None
for s in sections:
    if s['sh_name'] == '.dynstr': strtab = s
    if s['sh_name'] == '.dynsym': dynsym = s

if dynsym is None or strtab is None:
    print('no dynsym/dynstr'); sys.exit(1)
st_data = data[strtab['offset']:strtab['offset']+strtab['size']]
def st_name(idx):
    end = st_data.find(b'\0', idx)
    return st_data[idx:end].decode('utf-8', 'replace')

entsize = dynsym['entsize'] or 24
count = dynsym['size'] // entsize
found = 0
for i in range(count):
    o = dynsym['offset'] + i*entsize
    name_idx = u32(o); info = data[o+4]; value = u64(o+8); size = u64(o+0x10)
    if not name_idx: continue
    name = st_name(name_idx)
    if needle in name:
        found += 1
        t = {0:'NOTYPE',1:'OBJECT',2:'FUNC',3:'SECTION',4:'FILE'}.get(info & 0xf, '?')
        bind = {0:'LOCAL',1:'GLOBAL',2:'WEAK'}.get(info >> 4, '?')
        print('%08x %8d %-6s %-6s %s' % (value, size, bind, t, name))
print('total matched:', found)