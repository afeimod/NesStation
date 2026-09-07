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

#include <stdio.h>

static uint8_t *CHRRAM;
static uint32_t CHRRAMSIZE;
static uint32_t Mapper195_PRGBanks;   // actual 8KB banks in the cart (not the power-of-two buffer)

static void Mapper195_PWrap(uint32_t A, uint8_t V) {
	// Waixing FS303 (mapper 195) pirate dumps frequently carry
	// non-power-of-two PRG, e.g. the 1.25MB Chinese translation of
	// Captain Tsubasa Vol.2 / 天使之翼2 中文版 = 160 x 8KB banks.  Two engine
	// gotchas are handled here:
	//
	//  1. setprg8r() always does "V &= PRGmask8[r]" (cart.c), so the mask
	//     MUST stay the full 0xFF from the power-of-two load buffer.  An
	//     arithmetic mask like 0x9F(=159) clears the bank's bit7, turning
	//     legal selects such as $8001=0x42 (bank 66) into 0x02.
	//  2. The MMC3 fixed-window sends arrive as ~0=0xFF and ~1=0xFE; on a
	//     160-bank cart those must land on the *last real banks* (159/158),
	//     not on the 0xFF padding that sits at banks 160-255 of the buffer.
	// Values >= the real bank count can only come from those fixed-window
	// sends, so walk them back from the last real bank (0xFF->last,
	// 0xFE->last-1, ...); genuine bank selects always fit below the count
	// and pass through untouched.
	fprintf(stderr, "PW A=%04X V=%02X banks=%u PC=%04X", A, V, Mapper195_PRGBanks, X.PC);
	if (V >= Mapper195_PRGBanks)
		V = (uint8_t)(Mapper195_PRGBanks - 1 - (0xFF - V));
	fprintf(stderr, " -> V=%02X\n", V);
	setprg8(A, V);
}

static void Mapper195_CHRWrap(uint32_t A, uint8_t V) {
	// Hacked Captain Tsubasa Vol.2 (Ch) and Crystalis (Ch) boards wire the
	// first 4KB of CHR (banks 0-3) to CHR-RAM and the rest to CHR-ROM.
	// This is the behavior used by the reference NostalgiaLite/FCEUX
	// implementation (M195CW, V <= 3 with 4KB CHRRAM). The 2022 fceumm
	// GAL-based auto-detection only matches the unhacked Japanese ROM and
	// greys out the Chinese translation hacks. Note: routing only banks 0-1
	// (2KB, the mapper-196 layout) is NOT enough — bank 1 (CHR addr $0400-
	// $0BFF region) is used as RAM by the hack, so the threshold must be 3.
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

	// Non-power-of-two PRG dumps (e.g. the 1.25MB 天使之翼2 Chinese hack)
	// are loaded into a power-of-two padded buffer, so PRGmask8[0] is 0xFF
	// and the MMC3's initial $E000-$FFFF map ("~0") reaches the 0xFF
	// padding instead of the last real bank -> reset vector $FFFF -> grey
	// screen before any game code runs.  Rather than shrinking PRGmask8
	// (which would also corrupt every legal bank number with bit7 set,
	// e.g. $8001=0x42 selecting bank 66 becoming 0x02), record the real
	// bank count here and let Mapper195_PWrap translate the fixed-window
	// selects down to the last real banks.
	Mapper195_PRGBanks = info->PRGRomSize >> 13;

	CHRRAMSIZE = 4096;
	CHRRAM =(uint8_t*)FCEU_gmalloc(CHRRAMSIZE);
	// The generic MMC3 power routine (GenMMC3Power) only zeroes its own
	// private CHRRAM; this board's CHR-RAM is an independent allocation
	// that would otherwise contain uninitialized garbage on power-up.
	FCEU_dwmemset(CHRRAM, 0, CHRRAMSIZE);
	SetupCartCHRMapping(0x10, CHRRAM, CHRRAMSIZE, 1);
	AddExState(CHRRAM, CHRRAMSIZE, 0, "CHRR");
}
