import sys
from elftools.elf.elffile import ELFFile

path = sys.argv[1]
targets = [b'InternalResolution', b'Video_Settings', b'WaitForShadersBeforeStarting', b'EFBScale']

with open(path, 'rb') as f:
    elf = ELFFile(f)
    rodata = {}
    for seg in elf.iter_segments():
        if seg['p_type'] == 'PT_LOAD':
            pass
    # find .rodata/.data section
    sections = {}
    for sec in elf.iter_sections():
        name = sec.name
        if name in ('.rodata', '.data.rel.ro', '.data', '.got'):
            sections.setdefault(name, []).append(sec)

    # build full file bytes
    data = open(path, 'rb').read()

    # For each target string, find offset (vaddr where possible)
    for t in targets:
        idx = data.find(t)
        print(t, 'fileoff', hex(idx) if idx >= 0 else 'NF')
        if idx >= 0:
            # convert file offset to vaddr via program headers mapping
            vaddr = None
            for seg in elf.iter_segments():
                if seg['p_type'] == 'PT_LOAD':
                    start = seg['p_offset']
                    end = start + seg['p_filesz']
                    if start <= idx < end:
                        vaddr = seg['p_vaddr'] + (idx - start)
                        break
            print('   vaddr', hex(vaddr) if vaddr else '?')