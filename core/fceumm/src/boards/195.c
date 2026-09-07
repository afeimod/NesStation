/* FCEUmm - NES/Famicom Emulator
 *
 * Copyright notice for this file:
 *  Copyright (C) 2022
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301  USA
 */
#include "mapinc.h"
#include "mmc3.h"

static uint8_t *CHRRAM;
static uint32_t CHRRAMSIZE;

static void Mapper195_PWrap(uint32_t A, uint8_t V) {
	/* Reference behavior (NostalgiaLite / FCEUX boards/mmc3.cpp GENPWRAP):
	 * the Waixing FS303 board wires only A0-A6 of the MMC3 PRG bank output
	 * to the ROM address lines, so all bank selects are masked to 7 bits.
	 * PRGmask8[0] (set by GenMMC3_Init's 512KB parameter, further bounded
	 * by the actual PRG size) then clips the value to the real ROM size,
	 * exactly as on hardware. Keep this identical to FCEUX so Chinese
	 * hacks such as Captain Tsubasa Vol.2 (天使之翼2中文版) behave the same. */
	setprg8(A, V & 0x7F);
}

static void Mapper195_CHRWrap(uint32_t A, uint8_t V) {
	/* Hacked Captain Tsubasa Vol.2 (Ch) and Crystalis (Ch) boards wire the
	 * first 4KB of CHR (banks 0-3) to CHR-RAM and the rest to CHR-ROM.
	 * This mirrors the reference NostalgiaLite/FCEUX implementation
	 * (M195CW, V <= 3 -> 4KB CHRRAM). Note: routing only banks 0-1
	 * (2KB, the mapper-196 layout) is NOT enough - bank 1 (CHR addr
	 * $0400-$0BFF region) is used as RAM by the hack, so the threshold
	 * must be 3. */
	if (V <= 3)
		setchr1r(0x10, A, V);
	else
		setchr1r(0, A, V);
}

static void Mapper195_Power(void) {
	GenMMC3Power();
	setprg4r(0x10, 0x5000, 2);
	SetWriteHandler(0x5000, 0x5FFF, CartBW);
	SetReadHandler(0x5000, 0x5FFF, CartBR);
}

static void Mapper195_Close(void) {
	if (CHRRAM)
		FCEU_gfree(CHRRAM);
	CHRRAM = NULL;
}

void Mapper195_Init(CartInfo *info) {
	GenMMC3_Init(info, 512, 256, 16, info->battery);
	pwrap = Mapper195_PWrap;
	cwrap = Mapper195_CHRWrap;
	info->Power = Mapper195_Power;
	info->Reset = MMC3RegReset;
	info->Close = Mapper195_Close;

	CHRRAMSIZE = 4096;
	CHRRAM = (uint8_t*)FCEU_gmalloc(CHRRAMSIZE);
	/* The generic MMC3 power routine (GenMMC3Power) only zeroes its own
	 * private CHRRAM; this board's CHR-RAM is an independent allocation
	 * that would otherwise contain uninitialized garbage on power-up
	 * (GenMMC3Power also clears chip 0x10 via CartCHRIsRAM since
	 * commit "修复fc部分灰屏"). */
	FCEU_dwmemset(CHRRAM, 0, CHRRAMSIZE);
	SetupCartCHRMapping(0x10, CHRRAM, CHRRAMSIZE, 1);
	AddExState(CHRRAM, CHRRAMSIZE, 0, "CHRR");
}
