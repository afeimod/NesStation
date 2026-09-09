// Headless fceux test driver — loads a ROM, emulates N frames, reports
// framebuffer content statistics so gray/black screen issues are detectable
// without a UI.
#include <cstdio>
#include <cstdint>
#include <cstring>
#include <string>
#include <vector>

#include "driver.h"
#include "fceu.h"
#include "file.h"
#include "state.h"
#include "emufile.h"
#include "ppu.h"
#include "x6502.h"
#include "debug.h"

// ---- driver callbacks (mirrors rom_loader.cpp's global stubs) ------------
static uint32_t s_paletteTable[256];

void FCEUD_SetPalette(uint8 index, uint8 r, uint8 g, uint8 b) {
    s_paletteTable[index] = 0xFF000000u | ((uint32_t)r << 16) | ((uint32_t)g << 8) | (uint32_t)b;
}
void FCEUD_GetPalette(uint8 i, uint8 *r, uint8 *g, uint8 *b) {}

void FCEUD_PrintError(const char *s) { printf("[FCEU ERR] %s\n", s ? s : ""); }
void FCEUD_Message(const char *s)   { printf("[FCEU] %s\n", s ? s : ""); }

bool turbo = 0;
int closeFinishedMovie = 0;

uint64 FCEUD_GetTime(void) { return 0; }
uint64 FCEUD_GetTimeFreq(void) { return 0; }
bool FCEUD_ShouldDrawInputAids() { return false; }
unsigned int *GetKeyboard(void) { return 0; }

FILE *FCEUD_UTF8fopen(const char *fn, const char *mode) { return fopen(fn, mode); }
EMUFILE_FILE *FCEUD_UTF8_fstream(const char *n, const char *m) {
    EMUFILE_FILE *f = new EMUFILE_FILE(n, m);
    if (!f->is_open()) { delete f; return NULL; }
    return f;
}
FCEUFILE *FCEUD_OpenArchiveIndex(ArchiveScanRecord &asr, std::string &fname, int innerIndex) { return NULL; }
FCEUFILE *FCEUD_OpenArchive(ArchiveScanRecord &asr, std::string &fname, std::string *innerFilename) { return NULL; }
ArchiveScanRecord FCEUD_ScanArchive(std::string fname) { return ArchiveScanRecord(); }
const char *FCEUD_GetCompilerString() { return NULL; }

void FCEUD_SoundToggle(void) {}
void FCEUD_SoundVolumeAdjust(int) {}
void FCEUI_UseInputPreset(int preset) {}

void FCEUD_AviRecordTo(void) {}
void FCEUD_AviStop(void) {}
int FCEUI_AviBegin(const char *fname) { return 1; }
void FCEUI_AviEnd(void) {}
void FCEUI_AviVideoUpdate(const unsigned char *buffer) {}
void FCEUI_AviSoundUpdate(void *soundData, int soundLen) {}
bool FCEUI_AviIsRecording() { return false; }
bool FCEUI_AviEnableHUDrecording() { return false; }
void FCEUI_SetAviEnableHUDrecording(bool enable) {}
bool FCEUI_AviDisableMovieMessages() { return true; }
void FCEUI_SetAviDisableMovieMessages(bool disable) {}

int FCEUD_SendData(void *data, uint32 len) { return 1; }
int FCEUD_RecvData(void *data, uint32 len) { return 1; }
void FCEUD_NetplayText(uint8 *text) {}
void FCEUD_NetworkClose(void) {}

void FCEUD_SaveStateAs(void) {}
void FCEUD_LoadStateFrom(void) {}
void FCEUD_SetInput(bool fourscore, bool microphone, ESI port0, ESI port1, ESIFC fcexp) {}
void FCEUD_MovieRecordTo(void) {}
void FCEUD_MovieReplayFrom(void) {}
void FCEUD_LuaRunFrom(void) {}
void FCEUD_SetEmulationSpeed(int cmd) {}
void FCEUD_TurboOn(void) {}
void FCEUD_TurboOff(void) {}
void FCEUD_TurboToggle(void) {}
int FCEUD_ShowStatusIcon(void) { return 0; }
void FCEUD_ToggleStatusIcon(void) {}
void FCEUD_HideMenuToggle(void) {}
void FCEUD_CmdOpen(void) {}
void FCEUD_DebugBreakpoint(int bp_num) {}
void FCEUD_TraceInstruction(uint8 *opcode, int size) {}
void FCEUD_UpdateNTView(int scanline, bool drawall) {}
void FCEUD_UpdatePPUView(int scanline, int drawall) {}
bool FCEUD_PauseAfterPlayback() { return false; }
void FCEUD_VideoChanged() {}
void FCEUD_OnCloseGame(void) {}
void GetMouseData(uint32 (&md)[3]) {}
void RefreshThrottleFPS() {}

// ---- main ----------------------------------------------------------------
int main(int argc, char **argv) {
    if (argc < 2) {
        printf("usage: %s <rom> [frames]\n", argv[0]);
        return 1;
    }
    setvbuf(stdout, NULL, _IONBF, 0);
    const char *rom = argv[1];
    int frames = (argc > 2) ? atoi(argv[2]) : 120;

    printf("[1] start\n");
    fflush(stdout);
    if (!FCEUI_Initialize()) {
        printf("FCEUI_Initialize failed\n");
        return 1;
    }
    printf("[2] initialized\n");
    fflush(stdout);
    FCEUI_SetSoundVolume(100);
    FCEUI_SetLowPass(1);
    FCEUI_SetSoundQuality(0);
    FCEUI_Sound(48000);

    FCEUGI *g = FCEUI_LoadGame(rom, 0);
    if (!g) {
        printf("=== LOAD FAILED ===\n");
        return 1;
    }
    printf("=== LOADED mapper=%d name='%s' vidsys=%d ===\n",
           g->mappernum, (const char *)g->name, (int)g->vidsys);

    uint32_t pads = 0;
    FCEUI_SetInputFourscore(true);
    FCEUI_SetInput(0, SI_GAMEPAD, (void*)&pads, 0);
    FCEUI_SetInput(1, SI_GAMEPAD, (void*)&pads, 0);

    uint8 *xbuf = nullptr;
    int32 *sbuf = nullptr;
    int32 ssize = 0;

    // Frame stats: count distinct colors, min/max luminance, all-same check.
    int allSameCount = 0;
    int allZeroCount = 0;
    int lastPC = -1, stuckCount = 0;
    bool dumped = false;
    for (int f = 0; f < frames; f++) {
        FCEUI_Emulate(&xbuf, &sbuf, &ssize, 0);
        if (!xbuf) { printf("frame %d: xbuf NULL!\n", f); continue; }

        if (X.PC == lastPC) stuckCount++; else stuckCount = 0;
        lastPC = X.PC;

        std::vector<uint32_t> hist(256, 0);
        int nonZero = 0;
        for (int i = 0; i < 256 * 240; i++) {
            uint8_t v = xbuf[i];
            hist[v]++;
            if (v != 0) nonZero++;
        }
        // count distinct palette indices present
        int distinct = 0;
        for (int i = 0; i < 256; i++) if (hist[i]) distinct++;
        bool allSame = (distinct == 1);
        if (allSame) {
            allSameCount++;
            if (hist[0] == 256 * 240) allZeroCount++;
        }
        if (f < 5 || f % 20 == 0 || (f >= 5 && f < 20 && f % 5 == 0)) {
            int topIdx = (int)(std::max_element(hist.begin(), hist.end()) - hist.begin());
            int topCnt = (int)*std::max_element(hist.begin(), hist.end());
            uint32_t rgb = s_paletteTable[topIdx];
            printf("frame %3d: distinct=%3d nonzero=%6d top=%3d(%d) rgb=%08x PC=%04x PPU2000=%02x PPU2001=%02x %s\n",
                   f, distinct, nonZero, topIdx, topCnt, rgb,
                   (unsigned)X.PC, PPU[0], PPU[1],
                   allSame ? "ALLSAME" : "");
        }
        // stuck detection: same PC for 20+ frames -> dump memory context once
        if (stuckCount == 20 && !dumped) {
            dumped = true;
            printf("=== STUCK at PC=%04X A=%02X X=%02X Y=%02X S=%02X P=%02X ===\n",
                   (unsigned)X.PC, (unsigned)X.A, (unsigned)X.X, (unsigned)X.Y,
                   (unsigned)X.S, (unsigned)X.P);
            // dump memory around PC (both the 8KB PRG window and WRAM)
            for (unsigned a = (X.PC & 0xFFF8); a < (X.PC & 0xFFF8) + 0x20; a++)
                printf("  $%04X=%02X", a, GetMem(a));
            printf("\n");
            printf("  WRAM $5000-$50FF:\n");
            for (unsigned a = 0x5000; a < 0x50FF; a += 16) {
                printf("  $%04X:", a);
                for (unsigned b = a; b < a + 16; b++) printf(" %02X", GetMem(b));
                printf("\n");
            }
        }
    }
    printf("=== all-same-color frames: %d/%d (all-zero: %d) ===\n",
           allSameCount, frames, allZeroCount);

    FCEUI_CloseGame();
    return 0;
}