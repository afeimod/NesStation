#!/usr/bin/env python3
# Temporary harness: test which MMC3 PRG-bank mapping lets tszy2.nes (mapper195)
# boot correctly. Modes:
#   fceux    : GENPWRAP V&0x7F, PRGmask8=0xFF (reference NostalgiaLite/FCEUX math)
#   lastbank : project current fix (fixed windows remap to last real banks)
import sys

ROM = "/storage/emulated/0/游戏/fc/tszy2.nes"
TRACE_PPU = False

class CPU:
    def __init__(self, mmu):
        self.m = mmu
        self.A = 0; self.X = 0; self.Y = 0
        self.SP = 0xFD; self.PC = 0
        self.N = 0; self.V = 0; self.D = 0; self.I = 1; self.Z = 0; self.C = 0
        self.cycles = 0
        self.halted = False

    def R(self, a): return self.m.read(a & 0xFFFF)
    def W(self, a, v): self.m.write(a & 0xFFFF, v & 0xFF)

    def push(self, v):
        self.W(0x100 + self.SP, v)
        self.SP = (self.SP - 1) & 0xFF
    def pop(self):
        self.SP = (self.SP + 1) & 0xFF
        return self.R(0x100 + self.SP)
    def setNZ(self, v):
        self.N = 1 if (v & 0x80) else 0
        self.Z = 1 if (v & 0xFF) == 0 else 0
    def getP(self):
        return 0x20 | (0x80 if self.N else 0) | (0x40 if self.V else 0) | \
               (0x08 if self.D else 0) | (0x04 if self.I else 0) | \
               (0x02 if self.Z else 0) | (0x01 if self.C else 0)
    def setP(self, p):
        self.N = (p >> 7) & 1; self.V = (p >> 6) & 1
        self.D = (p >> 3) & 1; self.I = (p >> 2) & 1
        self.Z = (p >> 1) & 1; self.C = p & 1

    def adc(self, v):
        res = self.A + v + self.C
        self.C = 1 if res > 0xFF else 0
        self.V = 1 if (~(self.A ^ v) & (self.A ^ res) & 0x80) else 0
        self.A = res & 0xFF
        self.setNZ(self.A)
    def sbc(self, v):
        self.adc(v ^ 0xFF)
    def cmp(self, a, v):
        self.C = 1 if a >= v else 0
        self.setNZ(a - v)

    # addressing
    def imm(self):
        v = self.R(self.PC); self.PC = (self.PC + 1) & 0xFFFF; return v
    def zp(self):
        v = self.R(self.PC); self.PC = (self.PC + 1) & 0xFFFF; return v
    def zpx(self):
        v = (self.R(self.PC) + self.X) & 0xFF; self.PC = (self.PC + 1) & 0xFFFF; return v
    def zpy(self):
        v = (self.R(self.PC) + self.Y) & 0xFF; self.PC = (self.PC + 1) & 0xFFFF; return v
    def absa(self):
        a = self.R(self.PC) | (self.R((self.PC + 1) & 0xFFFF) << 8)
        self.PC = (self.PC + 2) & 0xFFFF
        return a
    def absx(self):
        return (self.absa() + self.X) & 0xFFFF
    def absy(self):
        return (self.absa() + self.Y) & 0xFFFF
    def indx(self):
        z = (self.R(self.PC) + self.X) & 0xFF
        self.PC = (self.PC + 1) & 0xFFFF
        return self.R(z) | (self.R((z + 1) & 0xFF) << 8)
    def indy(self):
        z = self.R(self.PC)
        self.PC = (self.PC + 1) & 0xFFFF
        base = self.R(z) | (self.R((z + 1) & 0xFF) << 8)
        return (base + self.Y) & 0xFFFF
    def branch(self, cond, extra_pc=0):
        off = self.R(self.PC)
        self.PC = (self.PC + 1) & 0xFFFF
        if cond:
            self.PC = (self.PC + (off - 256 if off & 0x80 else off)) & 0xFFFF

    def interrupt(self, vec):
        self.push((self.PC >> 8) & 0xFF)
        self.push(self.PC & 0xFF)
        self.push(self.getP() | 0x20)
        self.I = 1
        self.PC = self.R(vec) | (self.R(vec + 1) << 8)
        self.cycles += 7

    def step(self):
        if self.halted:
            self.cycles += 20
            return None
        op = self.R(self.PC)
        self.PC = (self.PC + 1) & 0xFFFF
        A, X, Y = self.A, self.X, self.Y

        # LDA
        if op == 0xA9: A = self.imm(); self.setNZ(A)
        elif op == 0xA5: A = self.R(self.zp()); self.setNZ(A)
        elif op == 0xB5: A = self.R(self.zpx()); self.setNZ(A)
        elif op == 0xAD: A = self.R(self.absa()); self.setNZ(A)
        elif op == 0xBD: A = self.R(self.absx()); self.setNZ(A)
        elif op == 0xB9: A = self.R(self.absy()); self.setNZ(A)
        elif op == 0xA1: A = self.R(self.indx()); self.setNZ(A)
        elif op == 0xB1: A = self.R(self.indy()); self.setNZ(A)
        # STA
        elif op == 0x85: self.W(self.zp(), A)
        elif op == 0x95: self.W(self.zpx(), A)
        elif op == 0x8D: self.W(self.absa(), A)
        elif op == 0x9D: self.W(self.absx(), A)
        elif op == 0x99: self.W(self.absy(), A)
        elif op == 0x81: self.W(self.indx(), A)
        elif op == 0x91: self.W(self.indy(), A)
        # LDX
        elif op == 0xA2: X = self.imm(); self.setNZ(X)
        elif op == 0xA6: X = self.R(self.zp()); self.setNZ(X)
        elif op == 0xB6: X = self.R(self.zpy()); self.setNZ(X)
        elif op == 0xAE: X = self.R(self.absa()); self.setNZ(X)
        elif op == 0xBE: X = self.R(self.absy()); self.setNZ(X)
        # STX
        elif op == 0x86: self.W(self.zpy(), X)
        elif op == 0x96: self.W(self.zpy(), X)
        elif op == 0x8E: self.W(self.absa(), X)
        # LDY
        elif op == 0xA0: Y = self.imm(); self.setNZ(Y)
        elif op == 0xA4: Y = self.R(self.zp()); self.setNZ(Y)
        elif op == 0xB4: Y = self.R(self.zpx()); self.setNZ(Y)
        elif op == 0xAC: Y = self.R(self.absa()); self.setNZ(Y)
        elif op == 0xBC: Y = self.R(self.absx()); self.setNZ(Y)
        # STY
        elif op == 0x84: self.W(self.zp(), Y)
        elif op == 0x94: self.W(self.zpx(), Y)
        elif op == 0x8C: self.W(self.absa(), Y)
        # transfers
        elif op == 0xAA: X = A; self.setNZ(X)
        elif op == 0x8A: A = X; self.setNZ(A)
        elif op == 0xA8: Y = A; self.setNZ(Y)
        elif op == 0x98: A = Y; self.setNZ(A)
        elif op == 0xBA: X = self.SP; self.setNZ(X)
        elif op == 0x9A: self.SP = X
        elif op == 0xE8: X = (X + 1) & 0xFF; self.setNZ(X)
        elif op == 0xCA: X = (X - 1) & 0xFF; self.setNZ(X)
        elif op == 0xC8: Y = (Y + 1) & 0xFF; self.setNZ(Y)
        elif op == 0x88: Y = (Y - 1) & 0xFF; self.setNZ(Y)
        # stack
        elif op == 0x48: self.push(A)
        elif op == 0x68: A = self.pop(); self.setNZ(A)
        elif op == 0x08: self.push(self.getP() | 0x30)
        elif op == 0x28: self.setP(self.pop())
        elif op == 0x40: self.setP(self.pop()); lo = self.pop(); hi = self.pop(); self.PC = lo | (hi << 8); self.__class__.t = None; self.cycles += 4
        elif op == 0x60: lo = self.pop(); hi = self.pop(); self.PC = ((lo | (hi << 8)) + 1) & 0xFFFF; self.cycles += 4
        # ADC
        elif op == 0x69: self.adc(self.imm())
        elif op == 0x65: self.adc(self.R(self.zp()))
        elif op == 0x75: self.adc(self.R(self.zpx()))
        elif op == 0x6D: self.adc(self.R(self.absa()))
        elif op == 0x7D: self.adc(self.R(self.absx()))
        elif op == 0x79: self.adc(self.R(self.absy()))
        elif op == 0x61: self.adc(self.R(self.indx()))
        elif op == 0x71: self.adc(self.R(self.indy()))
        # SBC
        elif op == 0xE9: self.sbc(self.imm())
        elif op == 0xEB: self.sbc(self.imm())
        elif op == 0xE5: self.sbc(self.R(self.zp()))
        elif op == 0xF5: self.sbc(self.R(self.zpx()))
        elif op == 0xED: self.sbc(self.R(self.absa()))
        elif op == 0xFD: self.sbc(self.R(self.absx()))
        elif op == 0xF9: self.sbc(self.R(self.absy()))
        elif op == 0xE1: self.sbc(self.R(self.indx()))
        elif op == 0xF1: self.sbc(self.R(self.indy()))
        # ORA
        elif op == 0x09: A |= self.imm(); self.setNZ(A)
        elif op == 0x05: A |= self.R(self.zp()); self.setNZ(A)
        elif op == 0x15: A |= self.R(self.zpx()); self.setNZ(A)
        elif op == 0x0D: A |= self.R(self.absa()); self.setNZ(A)
        elif op == 0x1D: A |= self.R(self.absx()); self.setNZ(A)
        elif op == 0x19: A |= self.R(self.absy()); self.setNZ(A)
        elif op == 0x01: A |= self.R(self.indx()); self.setNZ(A)
        elif op == 0x11: A |= self.R(self.indy()); self.setNZ(A)
        # AND
        elif op == 0x29: A &= self.imm(); self.setNZ(A)
        elif op == 0x25: A &= self.R(self.zp()); self.setNZ(A)
        elif op == 0x35: A &= self.R(self.zpx()); self.setNZ(A)
        elif op == 0x2D: A &= self.R(self.absa()); self.setNZ(A)
        elif op == 0x3D: A &= self.R(self.absx()); self.setNZ(A)
        elif op == 0x39: A &= self.R(self.absy()); self.setNZ(A)
        elif op == 0x21: A &= self.R(self.indx()); self.setNZ(A)
        elif op == 0x31: A &= self.R(self.indy()); self.setNZ(A)
        # EOR
        elif op == 0x49: A ^= self.imm(); self.setNZ(A)
        elif op == 0x45: A ^= self.R(self.zp()); self.setNZ(A)
        elif op == 0x55: A ^= self.R(self.zpx()); self.setNZ(A)
        elif op == 0x4D: A ^= self.R(self.absa()); self.setNZ(A)
        elif op == 0x5D: A ^= self.R(self.absx()); self.setNZ(A)
        elif op == 0x59: A ^= self.R(self.absy()); self.setNZ(A)
        elif op == 0x41: A ^= self.R(self.indx()); self.setNZ(A)
        elif op == 0x51: A ^= self.R(self.indy()); self.setNZ(A)
        # shifts
        elif op == 0x0A: self.C = (A >> 7) & 1; A = (A << 1) & 0xFF; self.setNZ(A)
        elif op == 0x2A: oc = self.C; self.C = (A >> 7) & 1; A = ((A << 1) | oc) & 0xFF; self.setNZ(A)
        elif op == 0x4A: self.C = A & 1; A = (A >> 1) & 0xFF; self.setNZ(A)
        elif op == 0x6A: oc = self.C; self.C = A & 1; A = ((A >> 1) | (oc << 7)) & 0xFF; self.setNZ(A)
        # memory ASL/LSR/ROL/ROR (RMW)
        elif op == 0x06: ad = self.zp(); v = (self.R(ad) << 1) & 0xFF; self.C = (self.R(ad) >> 7) & 1; self.W(ad, v); self.setNZ(v)
        elif op == 0x16: ad = self.zpx(); v = (self.R(ad) << 1) & 0xFF; self.C = (self.R(ad) >> 7) & 1; self.W(ad, v); self.setNZ(v)
        elif op == 0x0E: ad = self.absa(); v = (self.R(ad) << 1) & 0xFF; self.C = (self.R(ad) >> 7) & 1; self.W(ad, v); self.setNZ(v)
        elif op == 0x1E: ad = self.absx(); v = (self.R(ad) << 1) & 0xFF; self.C = (self.R(ad) >> 7) & 1; self.W(ad, v); self.setNZ(v)
        elif op == 0x46: ad = self.zp(); v = (self.R(ad) >> 1) & 0xFF; self.C = self.R(ad) & 1; self.W(ad, v); self.setNZ(v)
        elif op == 0x56: ad = self.zpx(); v = (self.R(ad) >> 1) & 0xFF; self.C = self.R(ad) & 1; self.W(ad, v); self.setNZ(v)
        elif op == 0x4E: ad = self.absa(); v = (self.R(ad) >> 1) & 0xFF; self.C = self.R(ad) & 1; self.W(ad, v); self.setNZ(v)
        elif op == 0x5E: ad = self.absx(); v = (self.R(ad) >> 1) & 0xFF; self.C = self.R(ad) & 1; self.W(ad, v); self.setNZ(v)
        elif op == 0x26: ad = self.zp(); old = self.R(ad); v = ((old << 1) | self.C) & 0xFF; self.C = (old >> 7) & 1; self.W(ad, v); self.setNZ(v)
        elif op == 0x36: ad = self.zpx(); old = self.R(ad); v = ((old << 1) | self.C) & 0xFF; self.C = (old >> 7) & 1; self.W(ad, v); self.setNZ(v)
        elif op == 0x2E: ad = self.absa(); old = self.R(ad); v = ((old << 1) | self.C) & 0xFF; self.C = (old >> 7) & 1; self.W(ad, v); self.setNZ(v)
        elif op == 0x3E: ad = self.absx(); old = self.R(ad); v = ((old << 1) | self.C) & 0xFF; self.C = (old >> 7) & 1; self.W(ad, v); self.setNZ(v)
        elif op == 0x66: ad = self.zp(); old = self.R(ad); v = ((old >> 1) | (self.C << 7)) & 0xFF; self.C = old & 1; self.W(ad, v); self.setNZ(v)
        elif op == 0x76: ad = self.zpx(); old = self.R(ad); v = ((old >> 1) | (self.C << 7)) & 0xFF; self.C = old & 1; self.W(ad, v); self.setNZ(v)
        elif op == 0x6E: ad = self.absa(); old = self.R(ad); v = ((old >> 1) | (self.C << 7)) & 0xFF; self.C = old & 1; self.W(ad, v); self.setNZ(v)
        elif op == 0x7E: ad = self.absx(); old = self.R(ad); v = ((old >> 1) | (self.C << 7)) & 0xFF; self.C = old & 1; self.W(ad, v); self.setNZ(v)
        # CMP
        elif op == 0xC9: self.cmp(A, self.imm())
        elif op == 0xC5: self.cmp(A, self.R(self.zp()))
        elif op == 0xD5: self.cmp(A, self.R(self.zpx()))
        elif op == 0xCD: self.cmp(A, self.R(self.absa()))
        elif op == 0xDD: self.cmp(A, self.R(self.absx()))
        elif op == 0xD9: self.cmp(A, self.R(self.absy()))
        elif op == 0xC1: self.cmp(A, self.R(self.indx()))
        elif op == 0xD1: self.cmp(A, self.R(self.indy()))
        # CPX
        elif op == 0xE0: self.cmp(X, self.imm())
        elif op == 0xE4: self.cmp(X, self.R(self.zp()))
        elif op == 0xEC: self.cmp(X, self.R(self.absa()))
        # CPY
        elif op == 0xC0: self.cmp(Y, self.imm())
        elif op == 0xC4: self.cmp(Y, self.R(self.zp()))
        elif op == 0xCC: self.cmp(Y, self.R(self.absa()))
        # INC / DEC
        elif op == 0xE6: ad = self.zp(); v = (self.R(ad) + 1) & 0xFF; self.W(ad, v); self.setNZ(v)
        elif op == 0xF6: ad = self.zpx(); v = (self.R(ad) + 1) & 0xFF; self.W(ad, v); self.setNZ(v)
        elif op == 0xEE: ad = self.absa(); v = (self.R(ad) + 1) & 0xFF; self.W(ad, v); self.setNZ(v)
        elif op == 0xFE: ad = self.absx(); v = (self.R(ad) + 1) & 0xFF; self.W(ad, v); self.setNZ(v)
        elif op == 0xC6: ad = self.zp(); v = (self.R(ad) - 1) & 0xFF; self.W(ad, v); self.setNZ(v)
        elif op == 0xD6: ad = self.zpx(); v = (self.R(ad) - 1) & 0xFF; self.W(ad, v); self.setNZ(v)
        elif op == 0xCE: ad = self.absa(); v = (self.R(ad) - 1) & 0xFF; self.W(ad, v); self.setNZ(v)
        elif op == 0xDE: ad = self.absx(); v = (self.R(ad) - 1) & 0xFF; self.W(ad, v); self.setNZ(v)
        # BIT
        elif op == 0x24: v = self.R(self.zp()); self.V = (v >> 6) & 1; self.N = (v >> 7) & 1; self.Z = 1 if (A & v) == 0 else 0
        elif op == 0x2C: v = self.R(self.absa()); self.V = (v >> 6) & 1; self.N = (v >> 7) & 1; self.Z = 1 if (A & v) == 0 else 0
        # jumps
        elif op == 0x4C: self.PC = self.absa()
        elif op == 0x6C:
            a = self.absa(); lo = self.R(a); hi = self.R((a & 0xFF00) | ((a + 1) & 0xFF)); self.PC = lo | (hi << 8)
        elif op == 0x20:
            a = self.absa(); self.push((self.PC >> 8) & 0xFF); self.push(self.PC & 0xFF); self.PC = a
        # branches
        elif op == 0x10: self.branch(self.N == 0)
        elif op == 0x30: self.branch(self.N == 1)
        elif op == 0x50: self.branch(self.V == 0)
        elif op == 0x70: self.branch(self.V == 1)
        elif op == 0x90: self.branch(self.C == 0)
        elif op == 0xB0: self.branch(self.C == 1)
        elif op == 0xD0: self.branch(self.Z == 0)
        elif op == 0xF0: self.branch(self.Z == 1)
        # flags
        elif op == 0x18: self.C = 0
        elif op == 0x38: self.C = 1
        elif op == 0x58: self.I = 0
        elif op == 0x78: self.I = 1
        elif op == 0xD8: self.D = 0
        elif op == 0xF8: self.D = 1
        elif op == 0xB8: self.V = 0
        elif op == 0xEA: pass
        elif op == 0x00:
            self.push((self.PC >> 8) & 0xFF); self.push(self.PC & 0xFF)
            self.push(self.getP() | 0x30); self.I = 1
            self.PC = self.R(0xFFFE) | (self.R(0xFFFF) << 8)
        else:
            # unknown opcodes treated as NOP so garbage memory keeps running
            pass

        self.A, self.X, self.Y = A, X, Y
        self.cycles += 2
        return op


class MMUBase:
    def __init__(self, prg_buf):
        self.prg = prg_buf
        self.ram = bytearray(0x800)
        self.vram = bytearray(0x2000)
        self.vblank_countdown = 0
        self.vblank_pending = False
        self.ppu_writes = 0
        self.mmc3_writes = 0
        self.ctrl_write = None
        self.window = [0, 0, 0, 0]

    def read(self, a):
        if a < 0x2000:
            return self.ram[a & 0x7FF]
        if 0x2000 <= a < 0x4000:
            r = a & 7
            if r == 2:
                v = 0x80 if self.vblank_pending else 0x00
                self.vblank_pending = False
                return v
            return 0x00
        if 0x4000 <= a < 0x6000:
            return 0x00
        if 0x8000 <= a < 0x10000:
            w = (a >> 13) & 3
            bank = self.window[w]
            off = bank * 8192 + (a & 0x1FFF)
            return self.prg[off] if off < len(self.prg) else 0xFF
        return 0x00

    def write(self, a, v):
        if a < 0x2000:
            self.ram[a & 0x7FF] = v
            return
        if 0x2000 <= a < 0x4000:
            r = a & 7
            if r == 0:
                self.ctrl_write = v
            if r in (0, 1):
                self.ppu_writes += 1
            return


class NromMMU(MMUBase):
    def __init__(self, prg_buf, prg_size, mirror):
        super().__init__(prg_buf)
        n = prg_size >> 13
        if n >= 4:
            self.window = [0, 1, n - 2, n - 1]
        elif n == 2:
            self.window = [0, 0, 1, 1]
        else:
            self.window = [0, 0, 0, 0]


class MMC3MMU(MMUBase):
    """MMC3 machine with selectable PRG-bank policy."""

    def __init__(self, prg_buf, prg_size, mode):
        super().__init__(prg_buf)
        self.mode = mode
        self.lastbank = prg_size >> 13
        if mode == "exact":
            self.prgmask = self.lastbank - 1
        else:
            self.prgmask = 0xFF
        self.DRegBuf = [0, 2, 4, 5, 6, 7, 0, 1]
        self.cmd = 0
        self.fixprg()

    def pwrap(self, addr, V):
        if self.mode == "lastbank":
            if V >= self.lastbank:
                bank = self.lastbank - 1 - (0xFF - V)
            else:
                bank = V
        else:
            bank = V & 0x7F
        bank &= self.prgmask
        bank = min(bank, (len(self.prg) >> 13) - 1) if self.prg else 0
        self.window[(addr >> 13) & 3] = bank

    def fixprg(self):
        if self.cmd & 0x40:
            self.pwrap(0xC000, self.DRegBuf[6])
            self.pwrap(0x8000, 0xFE)
        else:
            self.pwrap(0x8000, self.DRegBuf[6])
            self.pwrap(0xC000, 0xFE)
        self.pwrap(0xA000, self.DRegBuf[7])
        self.pwrap(0xE000, 0xFF)

    def mmc3(self, a, v):
        self.mmc3_writes += 1
        r = a & 0xE001
        if r == 0x8000:
            if (v & 0x40) != (self.cmd & 0x40):
                self.fixprg()
            self.cmd = v
        elif r == 0x8001:
            idx = self.cmd & 7
            self.DRegBuf[idx] = v
            if idx == 6:
                if self.cmd & 0x40:
                    self.pwrap(0xC000, v)
                else:
                    self.pwrap(0x8000, v)
            elif idx == 7:
                self.pwrap(0xA000, v)
        # A000/A001/C000/C001/E000/E001 no-op for boot flow

    def write(self, a, v):
        if 0x8000 <= a < 0x10000:
            self.mmc3(a, v)
            return
        super().write(a, v)


def load_rom(path):
    data = open(path, 'rb').read()
    # iNES header
    prg_units = data[4]
    chr_units = data[5]
    mapper = ((data[6] >> 4) & 0xF) | ((data[7] >> 4) << 4)
    mirror = 1 if (data[6] & 1) else 0
    prg_size = prg_units * 16384
    chr_size = chr_units * 8192
    data2 = data[16:]
    if data[6] & 4:
        data2 = data2[512:]
    # pow2 buffers, matched to reference iNES loader
    pow2 = lambda n: 1 << (n - 1).bit_length() if n else 0
    prg_pow2 = pow2(prg_size) if prg_size else 0
    buf = bytearray([0xFF]) * prg_pow2
    buf[0:len(data2)] = data2
    buf = buf[:prg_pow2] if prg_pow2 else bytearray()
    return buf, prg_size, chr_size, mapper, mirror


def run(path, mode, max_instr=800000, bank_override=None, label=""):
    buf, prg_size, chr_size, mapper, mirror = load_rom(path)
    if mode == "nrom":
        m = NromMMU(buf, prg_size, mirror)
    elif mode in ("fceux", "lastbank", "exact"):
        m = MMC3MMU(buf, prg_size, mode)
    else:
        raise SystemExit("bad mode %r" % mode)
    if bank_override is not None:
        m.lastbank = bank_override
    cpu = CPU(m)
    cpu.PC = m.read(0xFFFC) | (m.read(0xFFFD) << 8)

    ppu0 = 0
    last_pc = 0
    repeats = 0
    window_boot = list(m.window)
    log = []
    mmc3_banks = []
    for i in range(max_instr):
        pc = cpu.PC
        op = cpu.step()
        # vblank framing
        m.vblank_countdown -= 1
        if m.vblank_countdown <= 0:
            m.vblank_pending = True
            m.vblank_countdown = 30000
        # keep PPU ctrl reg value for NMI check
        if m.ctrl_write is not None:
            ppu0 = m.ctrl_write
            m.ctrl_write = None
        if m.vblank_pending and (ppu0 & 0x80) and not cpu.I:
            cpu.interrupt(0xFFFA)
            m.vblank_pending = False
        # tight-loop detection with memory-idle check
        if last_pc == pc:
            repeats += 1
        else:
            repeats = 0
        last_pc = pc
        if repeats > 5000000:
            break
    reset = None
    return m, cpu, window_boot, i + 1


if __name__ == "__main__":
    tests = [
        ("/storage/emulated/0/游戏/fc/超级玛丽.nes", "nrom", {}),
        ("/storage/emulated/0/游戏/fc/魂斗罗力量HACK阿木一坑版（20260601重修版）.nes", "fceux", {}),
        ("/storage/emulated/0/游戏/fc/tszy2.nes", "fceux", {}),
        ("/storage/emulated/0/游戏/fc/tszy2.nes", "lastbank", {}),
        ("/storage/emulated/0/游戏/fc/tszy2.nes", "exact", {}),
    ]
    for path, mode, kw in tests:
        m, cpu, wb, n = run(path, mode, **kw)
        print("%-14s mode=%-8s len=%d lastbank=%d resetPC=$%04X boot=%s instr=%d ppu=%d mmc3=%d pc=$%04X" %
              (path.split("/")[-1], mode, len(m.prg), getattr(m, "lastbank", -1), cpu.PC, wb, n, m.ppu_writes, m.mmc3_writes, cpu.PC))
