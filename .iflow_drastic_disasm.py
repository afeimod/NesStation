import sys
from elftools.elf.elffile import ELFFile
from capstone import Cs, CS_ARCH_ARM64, CS_MODE_ARM

lib = "/data/data/com.termux/files/home/nes/app/src/main/jniLibs/arm64-v8a/libdrastic_arm64.so"
start = int(sys.argv[1], 16)
size = int(sys.argv[2], 16)

with open(lib, "rb") as f:
    elf = ELFFile(f)
    # build VA->file offset map
    def va_to_off(va):
        for seg in elf.iter_segments():
            if seg['p_type'] != 'PT_LOAD':
                continue
            vaddr = seg['p_vaddr']
            filesz = seg['p_filesz']
            if vaddr <= va < vaddr + filesz:
                return seg['p_offset'] + (va - vaddr)
        return None
    off = va_to_off(start)
    data = f.read()
    code = data[off:off+size]
    md = Cs(CS_ARCH_ARM64, CS_MODE_ARM)
    md.detail = True
    for ins in md.disasm(code, start):
        print(f"{ins.address:#x}: {ins.mnemonic:12s} {ins.op_str}")
