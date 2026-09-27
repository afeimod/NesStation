import sys
from elftools.elf.elffile import ELFFile
from capstone import Cs, CS_ARCH_ARM64, CS_MODE_ARM

path = sys.argv[1]
target = int(sys.argv[2], 16)

with open(path, 'rb') as f:
    elf = ELFFile(f)
    text = elf.get_section_by_name('.text')
    tstart = text['sh_addr']
    tdata = text.data()
    md = Cs(CS_ARCH_ARM64, CS_MODE_ARM)
    md.detail = True
    full = list(md.disasm(tdata, tstart))
    d = {ins.address: ins for ins in full}

    tpage = (target >> 12) << 12
    hits = []
    for ins in full:
        if ins.mnemonic == 'adrp' and len(ins.operands) >= 2:
            rd = ins.operands[0].reg
            imm = ins.operands[1].imm
            eff_page = (ins.address & ~0xFFF) + imm
            if eff_page == tpage:
                # find add rd, rd, #imm shortly after
                for j in range(1, 12):
                    nxt = d.get(ins.address + j * 4)
                    if not nxt:
                        break
                    if nxt.mnemonic == 'add':
                        ops = nxt.operands
                        if len(ops) >= 3 and ops[0].reg == rd and ops[1].reg == rd:
                            eff = eff_page + ops[2].imm
                            if eff == target:
                                hits.append(nxt.address)
                                break
                    if nxt.mnemonic == 'ldr' or nxt.mnemonic == 'mov':
                        # ldr xN, [xM] style with previous adrp
                        pass
    print('total refs:', len(hits))
    for h in hits:
        print('REF at', hex(h))
        for k in range(1, 28):
            nn = d.get(h + k * 4)
            if not nn:
                break
            print('    ', hex(nn.address), nn.mnemonic, nn.op_str)
        print('---')