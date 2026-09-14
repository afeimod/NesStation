import sys
from elftools.elf.elffile import ELFFile

lib = "/data/data/com.termux/files/home/nes/app/src/main/jniLibs/arm64-v8a/libdrastic_arm64.so"
with open(lib, "rb") as f:
    elf = ELFFile(f)
    symtab = elf.get_section_by_name(".symtab")
    if symtab is None:
        symtab = elf.get_section_by_name(".dynsym")
    target = None
    for s in symtab.iter_symbols():
        name = s.name
        if name.startswith("Java_com_dsemu_drastic_DraSticJNI_"):
            print(f"{name:60s} size={s['st_size']:8d} addr={s['st_value']:#x}")
