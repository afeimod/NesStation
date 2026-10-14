#include "Cafe/Savestate/SaveState.h"

#include "Cafe/CafeSystem.h"
#include "Cafe/HW/MMU/MMU.h"
#include "Cafe/OS/libs/coreinit/coreinit_Thread.h"
#include "Cafe/OS/libs/coreinit/coreinit_Scheduler.h"
#include "Cafe/OS/libs/TCL/TCL.h"
#include "Cafe/HW/Latte/Core/Latte.h"
#include "Cafe/HW/Latte/Core/LatteTiming.h"
#include "Cafe/HW/Latte/Core/LatteAsyncCommands.h"
#include "Cafe/HW/Espresso/Recompiler/PPCRecompiler.h"
#include "Cafe/IOSU/fsa/iosu_fsa.h"
#include "Cemu/Logging/CemuLogging.h"
#include "Cafe/OS/libs/coreinit/coreinit_Alarm.h"
#include "Cafe/OS/libs/snd_core/ax.h"
#include "Cafe/OS/libs/gx2/GX2_Event.h"
#include "Cafe/Cheats/CheatManager.h"

#include <algorithm>
#include <chrono>
#include <ctime>
#include <zstd.h>
#include <fstream>
#include <thread>
#include <vector>
#include <atomic>
#include <cstdarg>
#include <cstdio>
#include <exception>
#include <cstring>
#if defined(__ANDROID__) || defined(__linux__)
#include <sys/mman.h>
#include <unistd.h>
#endif
#ifdef __ANDROID__
#include <android/log.h>
#include <signal.h>
#include <unwind.h>
#include <dlfcn.h>
#include <sys/prctl.h>
#include <ucontext.h>
#include <fcntl.h>
#endif
#include "Cafe/Savestate/SavestateFnRegistry.h"

namespace fs = std::filesystem;

// SysAllocator bump pointer, defined in coreinit_MEM.cpp. It is a host-side
// variable but points into the CEMU_AREA of emulated memory, so it must be
// captured and restored alongside the memory dump.
extern MPTR sysAreaAllocatorOffset;

namespace
{
                constexpr uint32 kStateMagic = 0x31475343; // "CSG1"
// version 2 adds a host-state blob after the FSA file entries (AX voice
// lists, AX callbacks and the coreinit alarm registry). Version 1 files
// (without the blob) can still be restored, the host lists are then left
// as they are
// version 3 compresses every memory range payload with zstd. This shrinks
// the on-disk state from ~1.4 GB of raw memory to roughly 100-300 MB for
// typical games (most of the emulated memory is zero pages) while staying
// fully backward compatible on the restore side
                constexpr uint32 kStateVersion = 3;
                // header flag: memory range payloads are zstd-compressed (version 3+)
                constexpr uint32 kStateFlagCompressedRanges = 0x00000001;

                constexpr uint32 kThreadsIdleTimeoutMs = 2000;
                constexpr uint32 kGpuIdleTimeoutMs = 2000;
                constexpr uint32 kFsaIdleTimeoutMs = 5000;
                constexpr uint32 kSettleWindowMs = 100;

                // v3.9: process-global freeze serialization. EmulationFreeze objects are
                // per-call locals, so the m_active member cannot detect overlapping
                // operations. Two concurrent freezes (a quick save fired while a quick
                // load is still running, a double tap on the load button, ...) would
                // interleave their pause/suspend cycles and their memory accesses and
                // corrupt the emulated state - which then crashes the scheduler when
                // emulation resumes. Serialize all capture/restore operations process
                // wide: an overlapping operation now fails cleanly with an error
                std::atomic<uint32> sFreezeOperationActive{ 0 };
                // v3.9: running index of freeze cycles, included in every freeze log
                // line so a crash on the Nth restore is immediately visible in logcat
                std::atomic<uint32> sFreezeCycleCounter{ 0 };

                struct SaveStateHeader
                {
                                uint32 magic;
                                uint32 version;
                                uint64 titleId;
                                uint64 timestamp;
                                uint32 rangeCount;
                                uint32 sysAreaAllocatorOffset;
                                uint32 fsEntryCount;
                                uint32 flags; // version 3+: kStateFlag... (was 'reserved')
                };
                static_assert(sizeof(SaveStateHeader) == 40);

                struct RangeHeader
                {
                                uint32 baseAddress;
                                uint32 size;
                };
                static_assert(sizeof(RangeHeader) == 8);

                // ---------------------------------------------------------------------------
                // v3.3 diagnostics: cemuLog is not wired into the Android logcat buffer, so
                // the whole save/load path used to be invisible there. Mirror every savestate
                // log line to logcat under the dedicated "CemuSaveState" tag, track the
                // current operation phase and install a fatal-signal tripwire while a
                // capture/restore runs - a crash now reports exactly which step died
                #ifdef __ANDROID__
                void LogCatLine(const char* msg)
                {
                                __android_log_print(ANDROID_LOG_INFO, "CemuSaveState", "%s", msg);
                }
                #else
                inline void LogCatLine(const char*) {}
                #endif

                #ifdef __ANDROID__
                std::atomic<const char*> g_saveStatePhase{ "init" };
                char g_phaseText[256];
                void SetPhase(const char* fmt, ...)
                {
                                va_list args;
                                va_start(args, fmt);
                                vsnprintf(g_phaseText, sizeof(g_phaseText), fmt, args);
                                va_end(args);
                                g_saveStatePhase.store(g_phaseText, std::memory_order_release);
                }
                                // async-signal-safe enough for a crash reporter: logcat write + re-raise so
                // the system still generates the regular tombstone
                // v3.7: module base captured when the reporter is armed, used to print
                // module-relative PCs which stay comparable across runs despite ASLR
                void* g_moduleBaseForSymbols = nullptr;

                                // v3.8: exclusive end of the emulator module mapping, resolved once when
                                // the reporter is armed so the fault handler never needs dladdr
                                // (dladdr fails for PCs in the JIT cache or other anonymous mappings)
                                uint64 g_moduleEndForSymbols = 0;

                                static uint32 SavestateHexVal(char c)
                                {
                                                if (c >= '0' && c <= '9') return (uint32)(c - '0');
                                                if (c >= 'a' && c <= 'f') return (uint32)(c - 'a' + 10);
                                                if (c >= 'A' && c <= 'F') return (uint32)(c - 'A' + 10);
                                                return 0xFFFFFFFFu;
                                }

                                // parse "start-end perms offset dev inode path" - path is optional
                                static bool SavestateParseMapsLine(const char* line, uint64& startOut, uint64& endOut, char* pathOut, size_t pathCap)
                                {
                                                uint64 start = 0, end = 0;
                                                const char* p = line;
                                                while (*p && *p != '-') { uint32 v = SavestateHexVal(*p); if (v == 0xFFFFFFFFu) return false; start = start * 16ull + (uint64)v; ++p; }
                                                if (*p != '-') return false;
                                                ++p;
                                                while (*p && *p != ' ') { uint32 v = SavestateHexVal(*p); if (v == 0xFFFFFFFFu) return false; end = end * 16ull + (uint64)v; ++p; }
                                                if (end <= start) return false;
                                                startOut = start;
                                                endOut = end;
                                                if (pathOut && pathCap)
                                                {
                                                                pathOut[0] = '\0';
                                                                int spaces = 0;
                                                                const char* path = nullptr;
                                                                for (const char* q = line; *q; ++q)
                                                                {
                                                                                if (*q == ' ')
                                                                                {
                                                                                                ++spaces;
                                                                                                if (spaces == 5)
                                                                                                {
                                                                                                                path = q + 1;
                                                                                                                while (*path == ' ') ++path; // maps pads the pathname with extra spaces
                                                                                                                break;
                                                                                                }
                                                                                }
                                                                }
                                                                if (path && *path)
                                                                {
                                                                                size_t i = 0;
                                                                                while (path[i] && i + 1 < pathCap) { pathOut[i] = path[i]; ++i; }
                                                                                pathOut[i] = '\0';
                                                                }
                                                }
                                                return true;
                                }

                                // armed-time (normal context) module span lookup via stdio
                                static uint64 SavestateFindMappingEnd(uint64 addr)
                                {
                                                FILE* f = fopen("/proc/self/maps", "r");
                                                if (!f) return 0;
                                                char line[1024];
                                                char refPath[256];
                                                refPath[0] = '\0';
                                                bool haveRef = false;
                                                uint64 bestEnd = 0;
                                                while (fgets(line, sizeof(line), f))
                                                {
                                                                uint64 start = 0, end = 0;
                                                                char path[256];
                                                                if (!SavestateParseMapsLine(line, start, end, path, sizeof(path))) continue;
                                                                if (path[0] == '\0') continue;
                                                                if (!haveRef && addr >= start && addr < end)
                                                                {
                                                                                strncpy(refPath, path, sizeof(refPath) - 1);
                                                                                refPath[sizeof(refPath) - 1] = '\0';
                                                                                haveRef = true;
                                                                }
                                                                if (haveRef && strcmp(path, refPath) == 0)
                                                                                bestEnd = end;
                                                }
                                                fclose(f);
                                                return bestEnd;
                                }

                                // fault-time (signal context) mapping classification using only
                                // open/read/close - prints the maps line which contains addr
                                static void SavestateLogMappingForAddr(uint64 addr, const char* label)
                                {
                                                int fd = open("/proc/self/maps", O_RDONLY);
                                                if (fd < 0) return;
                                                static char rb[8192];
                                                static char line[1200];
                                                size_t lineLen = 0;
                                                bool found = false;
                                                while (!found)
                                                {
                                                                ssize_t n = read(fd, rb, sizeof(rb));
                                                                if (n <= 0) break;
                                                                for (ssize_t i = 0; i < n && !found; ++i)
                                                                {
                                                                                char c = rb[i];
                                                                                if (c != '\n' && lineLen + 1 < sizeof(line)) { line[lineLen++] = c; continue; }
                                                                                if (c != '\n') continue; // line too long, skip remainder
                                                                                line[lineLen] = '\0';
                                                                                lineLen = 0;
                                                                                uint64 start = 0, end = 0;
                                                                                if (SavestateParseMapsLine(line, start, end, nullptr, 0) && addr >= start && addr < end)
                                                                                {
                                                                                                __android_log_print(ANDROID_LOG_ERROR, "CemuSaveState", "%s %llx is in mapping: %s", label, (unsigned long long)addr, line);
                                                                                                found = true;
                                                                                }
                                                                }
                                                }
                                                if (!found)
                                                                __android_log_print(ANDROID_LOG_ERROR, "CemuSaveState", "%s %llx has no /proc/self/maps entry", label, (unsigned long long)addr);
                                                close(fd);
                                }
                
                void SaveStateFatalSignalHandler(int sig, siginfo_t* info, void* uctx)
                {
                                char threadName[16] = { 0 };
                                prctl(PR_GET_NAME, threadName);
                                __android_log_print(ANDROID_LOG_ERROR, "CemuSaveState",
                                                                "!!! FATAL SIGNAL %d (fault addr %p) on thread '%s' during: %s", sig,
                                                                info ? info->si_addr : nullptr, threadName,
                                                                g_saveStatePhase.load(std::memory_order_seq_cst));
                                // v3.7: the generic unwinder cannot reliably cross the signal trampoline
                                // (it produced phantom system frames in earlier logs), so the interrupted
                                // PC/LR/SP and the general purpose registers are taken directly from the
                                // kernel-provided ucontext
                                uint64 pcAbs = 0, lrAbs = 0, spAbs = 0;
                                const ucontext_t* uc = (const ucontext_t*)uctx;
                                #if defined(__aarch64__)
                                if (uc)
                                {
                                    pcAbs = (uint64)uc->uc_mcontext.pc;
                                    lrAbs = (uint64)uc->uc_mcontext.regs[30];
                                    spAbs = (uint64)uc->uc_mcontext.sp;
                                }
                                #endif
                                const uint64 symBase = (uint64)(uintptr_t)g_moduleBaseForSymbols;
                                // v3.8: prefer the module span resolved at arm time; fall back to a
                                // generous cap when the end could not be determined
                                const uint64 moduleSpan = (g_moduleEndForSymbols > symBase) ? (g_moduleEndForSymbols - symBase) : 0x20000000ULL;
                                auto relOf = [symBase](uint64 addr) -> long long
                                {
                                    return (symBase != 0 && addr >= symBase) ? (long long)(addr - symBase) : -1LL;
                                };
                                auto inModule = [symBase, moduleSpan](uint64 addr) -> bool
                                {
                                    return symBase != 0 && addr >= symBase && (addr - symBase) < moduleSpan;
                                };
                                __android_log_print(ANDROID_LOG_ERROR, "CemuSaveState",
                                                    "fault pc %p (lib+%llx) lr %p (lib+%llx) sp %p",
                                                    (void*)pcAbs, (unsigned long long)relOf(pcAbs),
                                                    (void*)lrAbs, (unsigned long long)relOf(lrAbs), (void*)spAbs);
                                if (inModule(pcAbs))
                                {
                                    uint64 fnDelta = 0;
                                    const char* fnName = savestate_fsym::FindNearest(pcAbs - symBase, symBase, fnDelta);
                                    __android_log_print(ANDROID_LOG_ERROR, "CemuSaveState", "fault pc in function: %s+%llu",
                                                        fnName ? fnName : "<not registered>", (unsigned long long)fnDelta);
                                }
                                else
                                {
                                    // v3.8: the PC is outside the emulator module (JIT cache, heap,
                                    // system library ...) - print the owning /proc/self/maps line so
                                    // the log is conclusive even without symbols
                                    SavestateLogMappingForAddr(pcAbs, "fault pc");
                                }
                                #if defined(__aarch64__)
                                if (uc)
                                {
                                    for (int regBase = 0; regBase < 31; regBase += 6)
                                    {
                                        char line[320];
                                        size_t len = 0;
                                        line[0] = '\0';
                                        for (int j = 0; j < 6 && (regBase + j) < 31; ++j)
                                        {
                                            len += (size_t)snprintf(line + len, sizeof(line) - len, "x%d=%llx ",
                                                    regBase + j, (unsigned long long)uc->uc_mcontext.regs[regBase + j]);
                                        }
                                        __android_log_print(ANDROID_LOG_ERROR, "CemuSaveState", "regs: %s", line);
                                    }
                                }
                                #endif
                                struct BacktraceCtx
                                {
                                    void* pcs[24];
                                    uint32 count;
                                };
                                BacktraceCtx bt{};
                                _Unwind_Backtrace(
                                    [](struct _Unwind_Context* ctx, void* user) -> _Unwind_Reason_Code
                                    {
                                        BacktraceCtx* bt = (BacktraceCtx*)user;
                                        if (bt->count < sizeof(bt->pcs) / sizeof(bt->pcs[0]))
                                            bt->pcs[bt->count++] = (void*)_Unwind_GetIP(ctx);
                                        return _URC_NO_REASON;
                                    },
                                    &bt);
                                for (uint32 i = 0; i < bt.count; ++i)
                                {
                                    const uint64 pc = (uint64)(uintptr_t)bt.pcs[i];
                                    if (inModule(pc))
                                    {
                                        uint64 fnDelta = 0;
                                        const char* fnName = savestate_fsym::FindNearest(pc - symBase, symBase, fnDelta);
                                        __android_log_print(ANDROID_LOG_ERROR, "CemuSaveState", "bt #%u pc %p lib+%llx in %s+%llu",
                                                            i, bt.pcs[i], (unsigned long long)(pc - symBase),
                                                            fnName ? fnName : "<not registered>", (unsigned long long)fnDelta);
                                    }
                                    else
                                        __android_log_print(ANDROID_LOG_ERROR, "CemuSaveState", "bt #%u pc %p (outside module)", i, bt.pcs[i]);
                                }
                                signal(sig, SIG_DFL);
                                raise(sig);
                }
                // v3.4: process-lifetime fatal-signal reporter. Armed once before the
                // first savestate operation and never removed, so a crash AFTER the
                // restore (the previous blind spot) also reports the signal, fault
                // address, failing thread and backtrace under the CemuSaveState tag
                void InstallSaveStateCrashReporter()
                {
                                static std::atomic<bool> s_installed{ false };
                                bool expected = false;
                                if (!s_installed.compare_exchange_strong(expected, true))
                                        return;
                                const int sigs[] = { SIGSEGV, SIGBUS, SIGABRT, SIGFPE, SIGILL };
                                struct sigaction sa {};
                                sa.sa_sigaction = &SaveStateFatalSignalHandler;
                                sa.sa_flags = SA_SIGINFO;
                                for (size_t i = 0; i < sizeof(sigs) / sizeof(sigs[0]); ++i)
                                        sigaction(sigs[i], &sa, nullptr);
                                Dl_info info{};
                                if (dladdr((void*)&InstallSaveStateCrashReporter, &info) && info.dli_fbase)
                                {
                                                // v3.8 fix: store the module base. v3.7 only printed it, so the
                                                // fault handler ran with a null base: no offsets, no function
                                                // names, every frame reported as "(outside module)"
                                                g_moduleBaseForSymbols = info.dli_fbase;
                                                g_moduleEndForSymbols = SavestateFindMappingEnd((uint64)(uintptr_t)info.dli_fbase);
                                                __android_log_print(ANDROID_LOG_INFO, "CemuSaveState", "crash tripwire armed (module base %p end %llx)", info.dli_fbase, (unsigned long long)g_moduleEndForSymbols);
                                }
                                else
                                        __android_log_print(ANDROID_LOG_INFO, "CemuSaveState", "crash tripwire armed");
                }
                #else
                inline void SetPhase(const char*, ...) {}
                inline void InstallSaveStateCrashReporter() {}
                #endif
                
                void LogInfo(std::string_view msg)
                {
                                cemuLog_log(LogType::Force, "[SaveState] {}", msg);
                                LogCatLine(fmt::format("[SaveState] {}", msg).c_str());
                }

                void LogWarning(std::string_view msg)
                {
                                cemuLog_log(LogType::Force, "[SaveState] WARNING: {}", msg);
                                LogCatLine(fmt::format("[SaveState] WARNING: {}", msg).c_str());
                }

                // true when no emulated thread is still executing (all register contexts
                // have been stored back into the OSThread structures in emulated memory)
                bool AreThreadsIdle()
                {
                                return coreinit::AreAllThreadContextsStored();
                }

                // true when the GPU has processed every submitted command buffer and no
                // buffer swap is pending, which is the safest point to capture memory
                bool IsAtSafeGpuPoint()
                {
                                if (!TCL::IsGpuIdle())
                                                return false;
                                if (LatteGPUState.sharedArea)
                                {
                                                const uint32 requested = _swapEndianU32(LatteGPUState.sharedArea->flipRequestCountBE);
                                                const uint32 executed = _swapEndianU32(LatteGPUState.sharedArea->flipExecuteCountBE);
                                                if (requested != executed)
                                                                return false;
                                }
                                return true;
                }
                // host-state blob section types
                constexpr uint32 kHostSectionAx = 1;
                constexpr uint32 kHostSectionAlarms = 2;

                void AppendU32LE(std::vector<uint8>& out, uint32 v)
                {
                                out.push_back((uint8)(v));
                                out.push_back((uint8)(v >> 8));
                                out.push_back((uint8)(v >> 16));
                                out.push_back((uint8)(v >> 24));
                }

                uint32 ReadU32LE(const uint8* data)
                {
                                return (uint32)data[0] | ((uint32)data[1] << 8) | ((uint32)data[2] << 16) | ((uint32)data[3] << 24);
                }

                // captures the host-side state (AX voice lists/callbacks, alarm registry)
                // which is not covered by the emulated memory snapshot
                void AppendHostStateSections(std::vector<uint8>& blob)
                {
                                std::vector<uint8> section;
                                snd_core::SavestateCaptureHostState(section);
                                AppendU32LE(blob, kHostSectionAx);
                                AppendU32LE(blob, (uint32)section.size());
                                blob.insert(blob.end(), section.begin(), section.end());
                                section.clear();
                                coreinit::SavestateCaptureAlarms(section);
                                AppendU32LE(blob, kHostSectionAlarms);
                                AppendU32LE(blob, (uint32)section.size());
                                blob.insert(blob.end(), section.begin(), section.end());
                }

                // streaming zstd compression of one memory range. The source is the
                // emulated memory itself, so no additional host-sized buffer is needed;
                // output is flushed in 1 MB chunks directly into the state file
                bool WriteCompressedRange(std::ofstream& out, const void* data, uint32 size, uint64& writtenOut)
                {
                                ZSTD_CCtx* cctx = ZSTD_createCCtx();
                                if (!cctx)
                                                return false;
                                ZSTD_CCtx_setParameter(cctx, ZSTD_c_compressionLevel, 3);
                                // store a frame checksum so corrupted payloads are rejected during
                                // decompression instead of silently decoding garbage into emulated
                                // memory (which would crash the game after an in-place restore)
                                ZSTD_CCtx_setParameter(cctx, ZSTD_c_checksumFlag, 1);
                                constexpr size_t kChunkSize = 1024 * 1024;
                                std::vector<uint8> outChunk(kChunkSize);
                                ZSTD_inBuffer in{ data, size, 0 };
                                uint64 written = 0;
                                while (true)
                                {
                                                ZSTD_outBuffer outChunkDesc{ outChunk.data(), outChunk.size(), 0 };
                                                const size_t remaining = ZSTD_compressStream2(cctx, &outChunkDesc, &in, ZSTD_e_end);
                                                if (ZSTD_isError(remaining))
                                                {
                                                                ZSTD_freeCCtx(cctx);
                                                                return false;
                                                }
                                                out.write((const char*)outChunkDesc.dst, outChunkDesc.pos);
                                                written += outChunkDesc.pos;
                                                if (!out)
                                                {
                                                                ZSTD_freeCCtx(cctx);
                                                                return false;
                                                }
                                                if (remaining == 0)
                                                                break; // frame finished, input fully consumed
                                }
                                ZSTD_freeCCtx(cctx);
                                writtenOut = written;
                                return true;
                }

                // ---------------------------------------------------------------------------
                // v3.3 restore memory optimization: applying a snapshot used to write every
                // byte of the ~1.4 GB snapshot into emulated memory, forcing ALL of those
                // pages to become resident at once. On low-RAM Android devices the emulator
                // process (already holding ~1 GB from the running game) crossed the system
                // memory kill threshold mid-restore and was OOM-killed (SIGKILL - no crash
                // dump, the process just dies while loading, logcat shows only "has died").
                //
                // Fix: most snapshot pages are all-zero (this is why zstd compresses the
                // snapshot so well). Emulated memory is mapped MAP_PRIVATE|MAP_ANONYMOUS,
                // so untouched pages read as zero. Instead of writing zero pages we
                // decommit them with madvise(MADV_DONTNEED) - the content is identical,
                // but the resident footprint of the restored snapshot drops by the
                // snapshot zero-page ratio (typically 60-80%)
#if defined(__ANDROID__) || defined(__linux__)
                // kernel page size (madvise() requires page-aligned addresses)
                const size_t kApplyPageSize = []() -> size_t
                {
                        const long v = sysconf(_SC_PAGESIZE);
                        return v > 0 ? (size_t)v : (size_t)4096;
                }();

                bool IsZeroPage(const uint8* p)
                {
                        const uint64* q = (const uint64*)p;
                        for (size_t i = 0; i < kApplyPageSize / sizeof(uint64); ++i)
                        {
                                if (q[i] != 0)
                                        return false;
                        }
                        return true;
                }

                // writes one buffer of decoded data into emulated memory. runs of
                // all-zero kernel pages are decommitted instead of written, everything
                // else is copied. runs are batched to keep the syscall count low
                void ApplyMemoryChunk(void* dst, const uint8* src, size_t len)
                {
                        uint8* d = (uint8*)dst;
                        size_t off = 0;
                        while (off < len)
                        {
                                const size_t left = len - off;
                                if (left >= kApplyPageSize && IsZeroPage(src + off))
                                {
                                        size_t run = kApplyPageSize;
                                        while (run + kApplyPageSize <= left && IsZeroPage(src + off + run))
                                                run += kApplyPageSize;
                                        // if the kernel ever refuses (unaligned/foreign mapping) the
                                        // content must still be zero - write it explicitly
                                        if (madvise(d + off, run, MADV_DONTNEED) != 0)
                                                memset(d + off, 0, run);
                                        off += run;
                                }
                                else
                                {
                                        size_t copyLen = std::min<size_t>(left, kApplyPageSize);
                                        while (copyLen + kApplyPageSize <= left && !IsZeroPage(src + off + copyLen))
                                                copyLen += kApplyPageSize;
                                        memcpy(d + off, src + off, copyLen);
                                        off += copyLen;
                                }
                        }
                }
#ifdef __ANDROID__
                // resident set size of this process in MB - traces the restore memory
                // footprint in logcat (tag CemuSaveState)
                uint32 GetCurrentRssMB()
                {
                        FILE* f = fopen("/proc/self/statm", "rb");
                        if (!f)
                                return 0;
                        unsigned long totalPages = 0, residentPages = 0;
                        const int scanned = fscanf(f, "%lu %lu", &totalPages, &residentPages);
                        fclose(f);
                        if (scanned != 2)
                                return 0;
                        return (uint32)((residentPages * (unsigned long)kApplyPageSize) / (1024ul * 1024ul));
                }
#endif
#else
                void ApplyMemoryChunk(void* dst, const uint8* src, size_t len)
                {
                        memcpy(dst, src, len);
                }
#endif

                // streaming zstd decompression into the target emulated memory range.
                // output is drained in bounded 1 MB chunks which are applied through
                // ApplyMemoryChunk (zero pages are decommitted, see above) - no
                // host-sized buffer for the whole range is ever allocated
                bool ReadCompressedRange(std::ifstream& in, void* dest, uint32 uncompressedSize, uint32 compressedSize)
                {
                        ZSTD_DCtx* dctx = ZSTD_createDCtx();
                        if (!dctx)
                                return false;
                        constexpr size_t kInChunkSize = 256 * 1024;
                        constexpr size_t kOutChunkSize = 1024 * 1024;
                        std::vector<uint8> inChunk(kInChunkSize);
                        std::vector<uint8> outChunk(kOutChunkSize);
                        uint64 inRemaining = compressedSize;
                        uint64 outTotal = 0;
                        bool frameDone = false;
                        while (!frameDone && inRemaining > 0)
                        {
                                const uint32 toRead = (uint32)std::min<uint64>(kInChunkSize, inRemaining);
                                in.read((char*)inChunk.data(), toRead);
                                if (!in)
                                {
                                        ZSTD_freeDCtx(dctx);
                                        return false;
                                }
                                inRemaining -= toRead;
                                ZSTD_inBuffer inDesc{ inChunk.data(), toRead, 0 };
                                while (inDesc.pos < inDesc.size)
                                {
                                        ZSTD_outBuffer outDesc{ outChunk.data(), outChunk.size(), 0 };
                                        const size_t r = ZSTD_decompressStream(dctx, &outDesc, &inDesc);
                                        if (ZSTD_isError(r))
                                        {
                                                ZSTD_freeDCtx(dctx);
                                                return false;
                                        }
                                        // defense in depth: the scan phase already verified that the
                                        // frame decodes to exactly uncompressedSize, but never write
                                        // past the end of the destination range
                                        if (outTotal > (uint64)uncompressedSize || outDesc.pos > (uint64)uncompressedSize - outTotal)
                                        {
                                                ZSTD_freeDCtx(dctx);
                                                return false;
                                        }
                                        ApplyMemoryChunk((uint8*)dest + outTotal, outChunk.data(), outDesc.pos);
                                        outTotal += outDesc.pos;
                                        if (r == 0)
                                        {
                                                frameDone = true;
                                                break;
                                        }
                                }
                        }
                        ZSTD_freeDCtx(dctx);
                        return frameDone && inRemaining == 0 && outTotal == (uint64)uncompressedSize;
                }

                // applies a raw (uncompressed, version 1/2) memory payload in bounded
                // chunks through ApplyMemoryChunk so zero pages of legacy snapshots are
                // decommitted here as well instead of ballooning the resident footprint
                bool ReadRawRange(std::ifstream& in, void* dest, uint32 size)
                {
                        constexpr size_t kRawChunkSize = 1024 * 1024;
                        std::vector<uint8> chunk(kRawChunkSize);
                        uint64 remaining = size;
                        uint8* dst = (uint8*)dest;
                        while (remaining > 0)
                        {
                                const uint32 toRead = (uint32)std::min<uint64>(kRawChunkSize, remaining);
                                in.read((char*)chunk.data(), toRead);
                                if (!in)
                                        return false;
                                ApplyMemoryChunk(dst, chunk.data(), toRead);
                                dst += toRead;
                                remaining -= toRead;
                        }
                        return true;
                }

                // pre-validate a compressed payload without touching emulated memory:
                // stream exactly compressedSize bytes through zstd, discarding the
                // output, and require the frame to be complete and to decode to exactly
                // uncompressedSize bytes. this runs during the scan phase so that a
                // corrupt payload is rejected before the apply phase overwrites the
                // emulation memory (a torn overwrite would crash the resumed game)
                bool ValidateCompressedRange(std::ifstream& in, uint32 uncompressedSize, uint32 compressedSize)
                {
                                ZSTD_DCtx* dctx = ZSTD_createDCtx();
                                if (!dctx)
                                                return false;
                                constexpr size_t kInChunkSize = 256 * 1024;
                                constexpr size_t kDiscardSize = 1024 * 1024;
                                std::vector<uint8> inChunk(kInChunkSize);
                                std::vector<uint8> discardChunk(kDiscardSize);
                                uint64 inRemaining = compressedSize;
                                uint64 totalOut = 0;
                                bool frameDone = false;
                                while (!frameDone && inRemaining > 0)
                                {
                                                const uint32 toRead = (uint32)std::min<uint64>(kInChunkSize, inRemaining);
                                                in.read((char*)inChunk.data(), toRead);
                                                if (!in)
                                                {
                                                                ZSTD_freeDCtx(dctx);
                                                                return false;
                                                }
                                                inRemaining -= toRead;
                                                ZSTD_inBuffer inDesc{ inChunk.data(), toRead, 0 };
                                                while (inDesc.pos < inDesc.size)
                                                {
                                                                ZSTD_outBuffer outDiscard{ discardChunk.data(), discardChunk.size(), 0 };
                                                                const size_t r = ZSTD_decompressStream(dctx, &outDiscard, &inDesc);
                                                                if (ZSTD_isError(r))
                                                                {
                                                                                ZSTD_freeDCtx(dctx);
                                                                                return false;
                                                                }
                                                                totalOut += outDiscard.pos;
                                                                if (r == 0)
                                                                {
                                                                                frameDone = true;
                                                                                break;
                                                                }
                                                                if (totalOut > (uint64)uncompressedSize)
                                                                {
                                                                                // decodes to more than the recorded range size
                                                                                ZSTD_freeDCtx(dctx);
                                                                                return false;
                                                                }
                                                }
                                }
                                ZSTD_freeDCtx(dctx);
                                // the frame must be complete, the payload must be consumed exactly
                                // and the decoded size must match the range size exactly
                                return frameDone && inRemaining == 0 && totalOut == (uint64)uncompressedSize;
                }

                // legacy compatibility: state files written by the defective build
                // placed the compressed size *after* the payload, so the payload starts
                // with the zstd frame magic where the size field is expected. decode and
                // discard the frame to locate its end, then read the trailing size field
                bool SkipLegacyCompressedRange(std::ifstream& in, uint32 uncompressedSize, uint32& compressedSizeOut)
                {
                                ZSTD_DCtx* dctx = ZSTD_createDCtx();
                                if (!dctx)
                                                return false;
                                constexpr size_t kInChunkSize = 256 * 1024;
                                constexpr size_t kDiscardSize = 1024 * 1024;
                                std::vector<uint8> inChunk(kInChunkSize);
                                std::vector<uint8> discardChunk(kDiscardSize);
                                bool frameDone = false;
                                uint64 totalOut = 0;
                                while (!frameDone)
                                {
                                                in.read((char*)inChunk.data(), kInChunkSize);
                                                const size_t got = (size_t)in.gcount();
                                                if (got > 0)
                                                                in.clear(); // a short read at EOF is fine, we received data
                                                if (got == 0)
                                                {
                                                                ZSTD_freeDCtx(dctx);
                                                                return false; // truncated payload
                                                }
                                                ZSTD_inBuffer inDesc{ inChunk.data(), got, 0 };
                                                while (inDesc.pos < inDesc.size)
                                                {
                                                                ZSTD_outBuffer outDiscard{ discardChunk.data(), discardChunk.size(), 0 };
                                                                const size_t r = ZSTD_decompressStream(dctx, &outDiscard, &inDesc);
                                                                if (ZSTD_isError(r))
                                                                {
                                                                                ZSTD_freeDCtx(dctx);
                                                                                return false;
                                                                }
                                                                totalOut += outDiscard.pos;
                                                                if (r == 0)
                                                                {
                                                                                frameDone = true;
                                                                                // the frame must decode to exactly the recorded range size
                                                                                if (totalOut != (uint64)uncompressedSize)
                                                                                {
                                                                                                ZSTD_freeDCtx(dctx);
                                                                                                return false;
                                                                                }
                                                                                break;
                                                                }
                                                }
                                                if (frameDone)
                                                {
                                                                // rewind to the exact end of the frame - the chunk read
                                                                // above may have overshot into the trailing size field
                                                                const size_t leftover = inDesc.size - inDesc.pos;
                                                                if (leftover > 0)
                                                                                in.seekg(-(std::streamoff)leftover, std::ios::cur);
                                                }
                                }
                                uint32 legacySize = 0;
                                in.read((char*)&legacySize, sizeof(legacySize));
                                ZSTD_freeDCtx(dctx);
                                if (!in || legacySize == 0)
                                                return false;
                                compressedSizeOut = legacySize;
                                return true;
                }
}

namespace CafeSaveState
{
                EmulationFreeze::~EmulationFreeze()
                {
                                end();
                }

                bool EmulationFreeze::begin(std::string& errorOut, bool gpuIdleRequired)
                {
                                if (m_active)
                                                return true;

                                if (!CafeSystem::IsTitleRunning())
                                {
                                                errorOut = "No title is running";
                                                return false;
                                }

                                // v3.9: process-wide serialization (see sFreezeOperationActive).
                                // the slot is released by end(), which always runs via the
                                // destructor even when begin() fails further down
                                {
                                                uint32 expected = 0;
                                                if (!sFreezeOperationActive.compare_exchange_strong(expected, 1))
                                                {
                                                                errorOut = "Another save/load operation is already running";
                                                                LogWarning(fmt::format("freeze: rejected, another capture/restore is already active (cycle #{})", sFreezeCycleCounter.load(std::memory_order_relaxed)));
                                                                return false;
                                                }
                                                m_slotHeld = true;
                                }
                                const uint32 freezeCycle = sFreezeCycleCounter.fetch_add(1, std::memory_order_relaxed) + 1;
                                #ifdef __ANDROID__
                                LogInfo(fmt::format("freeze cycle #{}: begin (rss {} MB)", freezeCycle, GetCurrentRssMB()));
                                #endif

                                const auto freezeStart = std::chrono::steady_clock::now();
                                auto elapsedMs = [&freezeStart]() { return std::chrono::duration_cast<std::chrono::milliseconds>(std::chrono::steady_clock::now() - freezeStart).count(); };
                                m_wasPaused = CafeSystem::IsTitlePaused();
                                if (!m_wasPaused)
                                {
                                                SetPhase("freeze: pausing title");
                                                CafeSystem::PauseTitle();
                                                m_pausedByUs = true;
                                }

                                // wait until every suspended PPC thread has finished its current
                                // timeslice and stored its registers into emulated memory
                                SetPhase("freeze: waiting for threads idle");
                                const auto deadline = std::chrono::steady_clock::now() + std::chrono::milliseconds(kThreadsIdleTimeoutMs);
                                while (std::chrono::steady_clock::now() < deadline)
                                {
                                                if (AreThreadsIdle())
                                                                break;
                                                std::this_thread::sleep_for(std::chrono::milliseconds(1));
                                }
                                if (!AreThreadsIdle())
                                {
                                                errorOut = "Emulated threads did not become idle in time";
                                                if (m_pausedByUs)
                                                {
                                                                CafeSystem::ResumeTitle();
                                                                m_pausedByUs = false;
                                                }
                                                return false;
                                }

                                LogInfo(fmt::format("freeze cycle #{}: threads idle ({} ms)", freezeCycle, elapsedMs()));
                                // v3.8: from this point no emulated thread may be scheduled until the
                                // freeze is fully released. The scheduler rebuild during a restore
                                // re-adds ready threads to the run queues, which would otherwise let
                                // the idle fibers execute restored threads while the restore tail
                                // (audio reset, cheat reapply, GPU cache reset) is still running
                                coreinit::SavestateSetSchedulerHold(true);
                                // freeze the FSA IO thread before anything else touches emulated memory.
                                // FSA reads/writes copy file data into and out of emulated buffers on a
                                // dedicated host thread - without this gate a file request finishing
                                // during the capture/restore leaves the state torn (probabilistic freeze
                                // or corrupted save files after an in-place restore)
                                SetPhase("freeze: engaging FSA gate");
                                iosu::fsa::SavestateSetFrozen(true);
                                // v3.9: a request still being processed means the FSA IO thread
                                // is copying data in or out of emulated buffers right now.
                                // Overwriting (restore) or reading (capture) that memory while
                                // the copy is in flight tears the state and crashes later.
                                // Abort the operation instead of proceeding - the caller gets a
                                // clean error and the game keeps running
                                if (!iosu::fsa::SavestateWaitIdle(kFsaIdleTimeoutMs))
                                {
                                                errorOut = "FSA IO thread did not become idle in time";
                                                LogWarning(fmt::format("freeze cycle #{}: FSA IO thread still busy after {} ms - aborting", freezeCycle, elapsedMs()));
                                                if (m_pausedByUs)
                                                {
                                                                CafeSystem::ResumeTitle();
                                                                m_pausedByUs = false;
                                                }
                                                return false;
                                }
                                LogInfo(fmt::format("freeze cycle #{}: fsa gate idle ({} ms)", freezeCycle, elapsedMs()));
                                // drain the command ring before pausing the vsync timing - a GPU thread
                                // blocked in a flip wait needs the vsync counter to keep advancing
                                SetPhase("freeze: waiting for gpu idle");
                                if (!TCL::WaitForGPUIdle(kGpuIdleTimeoutMs))
                                {
                                                                if (gpuIdleRequired)
                                                                {
                                                                                                errorOut = "GPU did not become idle in time";
                                                                                                if (m_pausedByUs)
                                                                                                {
                                                                                                                                CafeSystem::ResumeTitle();
                                                                                                                                m_pausedByUs = false;
                                                                                                }
                                                                                                return false;
                                                                }
                                                                LogWarning("GPU did not retire all commands within the timeout");
                                }

                                // commit all pending GPU work (occlusion queries, texture readbacks)
                                // into emulated memory now that the command ring is drained
                                SetPhase("freeze: gpu sync");
                                LatteAsyncCommands_queueSaveStateGpuSync(false);
                                if (!LatteAsyncCommands_waitUntilAllProcessedWithTimeout(kGpuIdleTimeoutMs))
                                {
                                                LogWarning("GPU thread did not process the pre-capture sync in time");
                                                if (m_pausedByUs)
                                                {
                                                                CafeSystem::ResumeTitle();
                                                                m_pausedByUs = false;
                                                }
                                                errorOut = "GPU sync timed out";
                                                return false;
                                }

                                LogInfo(fmt::format("freeze: gpu drained + sync done ({} ms)", elapsedMs()));
                                // v3.4: while the GPU thread is still running, wait for a pending flip
                                // to execute so the capture/restore point lands on an exact frame
                                // boundary. Once the vsync timer is paused below, a requested but
                                // unexecuted flip can never drain - the old code then proceeded with
                                // a flip still in flight ("Capture point is not an exact frame
                                // boundary") and restored mid-flip
                                SetPhase("freeze: waiting for exact frame boundary");
                                {
                                        const auto flipDeadline = std::chrono::steady_clock::now() + std::chrono::milliseconds(400);
                                        while (std::chrono::steady_clock::now() < flipDeadline)
                                        {
                                                if (IsAtSafeGpuPoint())
                                                        break;
                                                std::this_thread::sleep_for(std::chrono::milliseconds(1));
                                        }
                                        // v3.9: if the boundary is not reached, log the actual GPU
                                        // state. A persistent flipRequest != flipExecute desync (e.g.
                                        // left behind by a previous restore) would make every later
                                        // freeze wait the full 400 ms and then restore mid-flip
                                        if (!IsAtSafeGpuPoint())
                                        {
                                                        uint32 flipReq = 0, flipExec = 0;
                                                        if (LatteGPUState.sharedArea)
                                                        {
                                                                        flipReq = _swapEndianU32(LatteGPUState.sharedArea->flipRequestCountBE);
                                                                        flipExec = _swapEndianU32(LatteGPUState.sharedArea->flipExecuteCountBE);
                                                        }
                                                        LogWarning(fmt::format("freeze cycle #{}: frame boundary not reached in 400 ms (gpuIdle={} flipReq={} flipExec={})",
                                                                        freezeCycle, TCL::IsGpuIdle(), (unsigned)flipReq, (unsigned)flipExec));
                                        }
                                }
                                // stop the virtual vsync timer and the OS system events (AX/alarm/NFP)
                                // so that no host thread mutates emulated memory while we work
                                SetPhase("freeze: pausing timing + system events");
                                LatteTiming_setPaused(true);
                                coreinit::SetSystemEventsPaused(true);
                                m_frozenTiming = true;

                                // keep the GPU thread parked while the caller overwrites emulated memory
                                // (also stops OSScreen uploads from racing the snapshot)
                                g_savestateFreezeGpuThread.store(true);

                                // settle window: a vsync/flip already in flight on the GPU thread when
                                // LatteTiming_setPaused() landed can still mutate the emulated flip
                                // counters for a few more iterations. Give it time to finish, then
                                // re-verify that the capture point is stable - this closes the TOCTOU
                                // window between the pause flags and the actual snapshot copy
                                SetPhase("freeze: settle window");
                                std::this_thread::sleep_for(std::chrono::milliseconds(kSettleWindowMs));
                                if (!IsAtSafeGpuPoint())
                                {
                                                                // not fatal: the game will simply redraw the affected surfaces
                                                                LogWarning("Capture point is not an exact frame boundary");
                                }

                                LogInfo(fmt::format("freeze: active ({} ms)", elapsedMs()));
                                m_active = true;
                                return true;
                }

                void EmulationFreeze::end()
                {
                                // let the GPU thread continue first so it can process the cache reset
                                SetPhase("freeze: releasing gpu park");
                                g_savestateFreezeGpuThread.store(false);
                                if (m_resetGpuCaches)
                                {
                                                                m_resetGpuCaches = false;
                                                                // drop all cached GPU data so everything is re-uploaded from the
                                                                // restored emulated memory
                                                                LatteAsyncCommands_queueSaveStateGpuSync(true);
                                                                if (!LatteAsyncCommands_waitUntilAllProcessedWithTimeout(kGpuIdleTimeoutMs))
                                                                                LogWarning("GPU cache reset did not complete in time - continuing");
                                }
                                LogInfo("freeze: releasing timing + gpu park");
                                // let the FSA IO thread process file requests again before any emulated
                                // thread is resumed
                                SetPhase("freeze: releasing FSA gate");
                                iosu::fsa::SavestateSetFrozen(false);
                                SetPhase("freeze: resuming timing + system events");
                                if (m_frozenTiming)
                                {
                                                coreinit::SetSystemEventsPaused(false);
                                                LatteTiming_setPaused(false);
                                                m_frozenTiming = false;
                                }
                                // v3.8: release the scheduler hold after timing/system events are
                                // running again but before the title is resumed - the entire restore
                                // tail above ran with zero emulated threads scheduled
                                coreinit::SavestateSetSchedulerHold(false);
                                if (m_pausedByUs)
                                {
                                                CafeSystem::ResumeTitle();
                                                m_pausedByUs = false;
                                }
                                LogInfo("freeze: emulation resumed");
                                // v3.4: keep the phase pointer meaningful for the global crash reporter
                                // - a crash after this point reports "running" instead of a stale
                                // restore phase, and the RSS trajectory below separates a native
                                // fault (stable rss + FATAL SIGNAL line) from a memory-pressure kill
                                // (climbing rss, process simply dies without any signal)
                                SetPhase("running: post-restore emulation (title resumed)");
                                // v3.9: release the process-wide freeze slot as the very last
                                // step so overlapping operations stay rejected until emulation
                                // has fully resumed
                                if (m_slotHeld)
                                {
                                                m_slotHeld = false;
                                                sFreezeOperationActive.store(0, std::memory_order_release);
                                }
                                #ifdef __ANDROID__
                                // v3.4: sample the resident footprint after the operation ends
                                std::thread([]() {
                                        const char* labels[3] = { "t+0s", "t+2s", "t+7s" };
                                        const int waits[3] = { 0, 2000, 5000 };
                                        for (int i = 0; i < 3; ++i)
                                        {
                                                std::this_thread::sleep_for(std::chrono::milliseconds(waits[i]));
                                                LogInfo(fmt::format("post-op rss {} = {} MB", labels[i], GetCurrentRssMB()));
                                        }
                                }).detach();
                                #endif
                                m_active = false;
                }

                void EmulationFreeze::RequestGpuCacheReset()
                {
                                m_resetGpuCaches = true;
                }

                OperationResult CaptureStateToFileLocked(uint64 titleId, const std::filesystem::path& filePath)
                {
                                OperationResult result;

                                std::error_code ec;
                                fs::path tmpPath = filePath;
                                tmpPath += ".tmp";
                                fs::create_directories(filePath.parent_path(), ec);

                                std::ofstream out(tmpPath, std::ios::binary | std::ios::trunc);
                                if (!out)
                                {
                                                result.errorMessage = "Failed to create state file";
                                                return result;
                                }

                                // collect all mapped memory ranges
                                std::vector<MMURange*> ranges;
                                for (MMURange* range : memory_getMMURanges())
                                {
                                                if (range->isMapped() && range->getPtr() && range->getSize() > 0)
                                                                ranges.emplace_back(range);
                                }

                                // collect the state of all files the game currently holds open
                                std::vector<iosu::fsa::FSAFileStateEntry> fileStates;
                                iosu::fsa::FSACollectOpenFileStates(fileStates);

                                SaveStateHeader header{};
                                SetPhase("capture: writing header");
                                header.magic = kStateMagic;
                                header.version = kStateVersion;
                                header.titleId = titleId;
                                header.timestamp = (uint64)time(nullptr);
                                header.rangeCount = (uint32)ranges.size();
                                header.sysAreaAllocatorOffset = (uint32)sysAreaAllocatorOffset;
                                header.fsEntryCount = (uint32)fileStates.size();
                                header.flags = kStateFlagCompressedRanges; // version 3: zstd-compressed memory payloads

                                out.write((const char*)&header, sizeof(header));

                                uint64 totalBytes = sizeof(header);
                                for (const MMURange* range : ranges)
                                {
                                                SetPhase("capture: compress range base=%08x size=%u",
                                                                                (unsigned)range->getBase(), (unsigned)range->getSize());
                                                RangeHeader rangeHeader{ range->getBase(), range->getSize() };
                                                out.write((const char*)&rangeHeader, sizeof(rangeHeader));
                                                // version 3: compress the payload with zstd (level 3). Emulated memory is
                                                // largely zero pages, so the on-disk state shrinks by roughly 5-10x.
                                                // streaming compression only knows the compressed size after the frame
                                                // is finished, so a placeholder is written before the payload and
                                                // patched once the payload is complete
                                                const std::streamoff sizeFieldPos = (std::streamoff)out.tellp();
                                                const uint32 sizePlaceholder = 0;
                                                out.write((const char*)&sizePlaceholder, sizeof(sizePlaceholder));
                                                uint64 written = 0;
                                                if (!WriteCompressedRange(out, range->getPtr(), range->getSize(), written))
                                                {
                                                                out.close();
                                                                fs::remove(tmpPath, ec);
                                                                result.errorMessage = "Failed to compress memory range to state file";
                                                                LogWarning(result.errorMessage);
                                                                return result;
                                                }
                                                const uint32 compressedSize = (uint32)written;
                                                // patch the placeholder which sits before the payload on disk
                                                const std::streamoff endPos = (std::streamoff)out.tellp();
                                                out.seekp(sizeFieldPos);
                                                out.write((const char*)&compressedSize, sizeof(compressedSize));
                                                out.seekp(endPos);
                                                if (!out)
                                                {
                                                                out.close();
                                                                fs::remove(tmpPath, ec);
                                                                result.errorMessage = "Failed to write memory range to state file";
                                                                LogWarning(result.errorMessage);
                                                                return result;
                                                }
                                                totalBytes += sizeof(rangeHeader) + sizeof(compressedSize) + written;
                                }

                                SetPhase("capture: writing file table");
                                for (const iosu::fsa::FSAFileStateEntry& entry : fileStates)
                                {
                                                const uint32 pathLength = (uint32)entry.path.size();
                                                out.write((const char*)&pathLength, sizeof(pathLength));
                                                out.write(entry.path.data(), pathLength);
                                                out.write((const char*)&entry.seek, sizeof(entry.seek));
                                                out.write((const char*)&entry.fileSize, sizeof(entry.fileSize));
                                                const uint8 writable = entry.writable ? 1 : 0;
                                                out.write((const char*)&writable, sizeof(writable));
                                                const uint32 contentLength = (uint32)entry.content.size();
                                                out.write((const char*)&contentLength, sizeof(contentLength));
                                                if (contentLength > 0)
                                                                out.write((const char*)entry.content.data(), contentLength);
                                                if (!out)
                                                {
                                                                out.close();
                                                                fs::remove(tmpPath, ec);
                                                                result.errorMessage = "Failed to write file handle state";
                                                                LogWarning(result.errorMessage);
                                                                return result;
                                                }
                                                totalBytes += contentLength;
                                }

                                // host-side state (AX voice lists/callbacks, alarm registry) which is
                                // not part of emulated memory
                                SetPhase("capture: writing host blob");
                                std::vector<uint8> hostBlob;
                                AppendHostStateSections(hostBlob);
                                const uint32 hostBlobSize = (uint32)hostBlob.size();
                                out.write((const char*)&hostBlobSize, sizeof(hostBlobSize));
                                if (hostBlobSize > 0)
                                                                out.write((const char*)hostBlob.data(), (std::streamsize)hostBlob.size());
                                totalBytes += sizeof(hostBlobSize) + hostBlob.size();

                                out.close();
                                if (!out)
{
                                                                fs::remove(tmpPath, ec);
                                                                result.errorMessage = "Failed to flush state file";
                                                                return result;
                                }

                                // move the finished snapshot into place (removes any old state file)
                                fs::remove(filePath, ec);
                                SetPhase("capture: finalizing (rename)");
                                fs::rename(tmpPath, filePath, ec);
                                if (ec)
                                {
                                                fs::remove(tmpPath, ec);
                                                result.errorMessage = "Failed to finalize state file";
                                                LogWarning(result.errorMessage);
                                                return result;
                                }

                                LogInfo(fmt::format("Captured state for {:016x} (zstd compressed, {:.1f} MB, {} file handles)",
                                                titleId, (double)totalBytes / 1024.0 / 1024.0, fileStates.size()));

                                result.success = true;
                                return result;
                }

                OperationResult RestoreStateFromFileLocked(uint64 expectedTitleId, const std::filesystem::path& filePath)
                {
                                OperationResult result;
                                std::error_code ec;
                                if (!fs::exists(filePath, ec))
                                {
                                                result.errorMessage = "State file does not exist";
                                                return result;
                                }

                                // parse and validate the whole file BEFORE touching any emulation state.
                                // the memory payloads are kept as stream offsets so we never need to
                                // hold the entire 1.4+ GB snapshot in host memory
                                std::ifstream in(filePath, std::ios::binary);
                                if (!in)
                                {
                                                result.errorMessage = "Failed to open state file";
                                                return result;
                                }

                                SaveStateHeader header{};
                                in.read((char*)&header, sizeof(header));
                                if (!in || header.magic != kStateMagic || header.version < 1 || header.version > kStateVersion)
                                {
                                                result.errorMessage = "Not a valid savestate file";
                                                return result;
                                }
                                if (header.titleId != expectedTitleId)
                                {
                                                result.errorMessage = fmt::format("State file belongs to title {:016x}", header.titleId);
                                                return result;
                                }

                                SetPhase("restore: reading header");
                                if (header.rangeCount > 64 || header.fsEntryCount > 4096)
                                {
                                                                result.errorMessage = "State file has an implausible range or file handle count";
                                                                return result;
                                }
                                LogInfo(fmt::format("restore: header ok (version {}, {} ranges, {} handles, flags {:08x})",
                                                                header.version, header.rangeCount, header.fsEntryCount, header.flags));
                                struct PendingRange
                                {
                                                uint32 baseAddress;
                                                uint32 size;
                                                uint64 fileOffset;
                                                uint32 compressedSize{ 0 }; // 0 = raw payload (legacy version 1/2 files)
                                };
                                std::vector<PendingRange> pendingRanges;
                                pendingRanges.reserve(header.rangeCount);

                                // version 3+ stores zstd-compressed memory payloads (header flag),
                                // version 1/2 files contain raw memory dumps and remain readable
                                const bool compressedRanges =
                                        header.version >= 3 && (header.flags & kStateFlagCompressedRanges) != 0;
                                // resolve and validate every range against the current memory mapping
                                const std::vector<MMURange*> currentRanges = memory_getMMURanges();
                                for (uint32 i = 0; i < header.rangeCount; ++i)
                                {
                                                RangeHeader rangeHeader{};
                                                in.read((char*)&rangeHeader, sizeof(rangeHeader));
                                                if (!in)
                                                {
                                                                result.errorMessage = "State file is truncated (range table)";
                                                                return result;
                                                }

                                                MMURange* target = nullptr;
                                                for (MMURange* range : currentRanges)
                                                {
                                                                if (range->getBase() == rangeHeader.baseAddress)
                                                                {
                                                                                target = range;
                                                                                break;
                                                                }
                                                }
                                                if (!target || !target->isMapped() || !target->getPtr())
                                                {
                                                                result.errorMessage = fmt::format("Memory range {:08x} is not mapped anymore", rangeHeader.baseAddress);
                                                                return result;
                                                }
                                                if (target->getSize() != rangeHeader.size)
                                                {
                                                                result.errorMessage = fmt::format("Size of memory range {:08x} changed", rangeHeader.baseAddress);
                                                                return result;
                                                }

                                                PendingRange pending;
                                                pending.baseAddress = rangeHeader.baseAddress;
                                                pending.size = rangeHeader.size;
                                                SetPhase("scan range %u/%u base=%08x size=%u", (unsigned)(i + 1),
                                                                (unsigned)header.rangeCount, (unsigned)rangeHeader.baseAddress,
                                                                (unsigned)rangeHeader.size);
                                                if (compressedRanges)
                                                {
                                                                // the position currently holds either the compressed size (correct
                                                                // v3 layout) or, in files written by the defective build, the zstd
                                                                // frame magic itself (payload directly follows the range header)
                                                                pending.fileOffset = (uint64)in.tellg();
                                                                uint32 compressedSize = 0;
                                                                in.read((char*)&compressedSize, sizeof(compressedSize));
                                                                if (!in)
                                                                {
                                                                                result.errorMessage = "State file is truncated (range payload)";
                                                                                return result;
                                                                }
                                                                if (compressedSize == ZSTD_MAGICNUMBER)
                                                                {
                                                                                // legacy defective layout: [payload][size]. the frame begins
                                                                                // at fileOffset (the magic just read is part of it), so rewind
                                                                                // there before decoding
                                                                                in.seekg((std::streamoff)pending.fileOffset, std::ios::beg);
                                                                                uint32 legacySize = 0;
                                                                                SetPhase("legacy-scan range base=%08x size=%u",
                                                                                                                                                (unsigned)rangeHeader.baseAddress, (unsigned)pending.size);
                                                                                if (!SkipLegacyCompressedRange(in, pending.size, legacySize))
                                                                                {
                                                                                                result.errorMessage = "State file is corrupted (legacy compressed payload)";
                                                                                                return result;
                                                                                }
                                                                                pending.compressedSize = legacySize;
                                                                                // fileOffset already points at the payload start
                                                                }
                                                                else
                                                                {
                                                                                if (compressedSize == 0)
                                                                                {
                                                                                                // a zstd frame is never smaller than its magic - a zero
                                                                                                // size field means the size patch-up failed when this
                                                                                                // file was written
                                                                                                result.errorMessage = fmt::format("Memory range {:08x} has a zero compressed size", rangeHeader.baseAddress);
                                                                                                return result;
                                                                                }
                                                                                pending.compressedSize = compressedSize;
                                                                                pending.fileOffset = (uint64)in.tellg(); // start of the payload
                                                                                // pre-validate the payload by streaming it once through zstd
                                                                                // (discarding the output) - a corrupt or mismatched frame is
                                                                                // rejected before the apply phase overwrites emulation memory.
                                                                                // on success the stream is positioned at the end of the payload
                                                                                SetPhase("validate range base=%08x size=%u comp=%u",
                                                                                                                                                (unsigned)rangeHeader.baseAddress, (unsigned)pending.size,
                                                                                                                                                (unsigned)compressedSize);
                                                                                if (!ValidateCompressedRange(in, pending.size, compressedSize))
                                                                                {
                                                                                                result.errorMessage = fmt::format("Memory range {:08x} has a corrupted compressed payload", rangeHeader.baseAddress);
                                                                                                return result;
                                                                                }
                                                                }
                                                }
                                                else
                                                {
                                                                pending.fileOffset = (uint64)in.tellg();
                                                                in.seekg((std::streamoff)pending.size, std::ios::cur);
                                                }
                                                if (!in)
                                                {
                                                                result.errorMessage = "State file is truncated (range payload)";
                                                                return result;
                                                }
                                                pendingRanges.emplace_back(pending);
                                }

                                // read the file handle states. all memory payload reads in the
                                // apply phase below are guaranteed to succeed because the
                                // validation loop above read through every range payload
                                std::vector<iosu::fsa::FSAFileStateEntry> fileStates;
                                for (uint32 i = 0; i < header.fsEntryCount; ++i)
                                {
                                                iosu::fsa::FSAFileStateEntry entry;
                                                uint32 pathLength = 0;
                                                in.read((char*)&pathLength, sizeof(pathLength));
                                                if (!in || pathLength > 4096)
                                                {
                                                                result.errorMessage = "State file is truncated (file table)";
                                                                return result;
                                                }
                                                entry.path.resize(pathLength);
                                                in.read(entry.path.data(), pathLength);
                                                in.read((char*)&entry.seek, sizeof(entry.seek));
                                                in.read((char*)&entry.fileSize, sizeof(entry.fileSize));
                                                uint8 writable = 0;
                                                in.read((char*)&writable, sizeof(writable));
                                                entry.writable = writable != 0;
                                                uint32 contentLength = 0;
                                                in.read((char*)&contentLength, sizeof(contentLength));
                                                if (!in || contentLength > (64u * 1024u * 1024u))
                                                {
                                                                result.errorMessage = "State file is truncated (file content)";
                                                                return result;
                                                }
                                                entry.content.resize(contentLength);
                                                if (contentLength > 0)
                                                                in.read((char*)entry.content.data(), contentLength);
                                                if (!in)
                                                {
                                                                result.errorMessage = "State file is truncated (file content)";
                                                                return result;
                                                }
                                                fileStates.emplace_back(std::move(entry));
                                }

                                // parse the host-state blob (version 2+). this happens before anything
                                // is applied so a truncated file never touches the running emulation
                                std::vector<std::pair<uint32, std::vector<uint8>>> hostSections;
                                if (header.version >= 2)
                                {
                                                                uint32 hostBlobSize = 0;
                                                                in.read((char*)&hostBlobSize, sizeof(hostBlobSize));
                                                                if (!in || hostBlobSize > (4u * 1024u * 1024u))
                                                                {
                                                                                                result.errorMessage = "State file is truncated (host state)";
                                                                                                return result;
                                                                }
                                                                std::vector<uint8> hostBlob(hostBlobSize);
                                                                if (hostBlobSize > 0)
                                                                {
                                                                                                in.read((char*)hostBlob.data(), (std::streamsize)hostBlob.size());
                                                                                                if (!in)
                                                                                                {
                                                                                                                                result.errorMessage = "State file is truncated (host state)";
                                                                                                                                return result;
                                                                                                }
                                                                }
                                                                size_t blobOffset = 0;
                                                                while (blobOffset + 8 <= hostBlob.size())
                                                                {
                                                                                                const uint32 sectionType = ReadU32LE(hostBlob.data() + blobOffset);
                                                                                                const uint32 sectionSize = ReadU32LE(hostBlob.data() + blobOffset + 4);
                                                                                                blobOffset += 8;
                                                                                                if (blobOffset + sectionSize > hostBlob.size())
                                                                                                                                break;
                                                                                                hostSections.emplace_back(sectionType, std::vector<uint8>(hostBlob.begin() + blobOffset, hostBlob.begin() + blobOffset + sectionSize));
                                                                                                blobOffset += sectionSize;
                                                                }
                                }

                                LogInfo(fmt::format("restore: validation done ({} ranges, {} file handles)", pendingRanges.size(), fileStates.size()));
#ifdef __ANDROID__
                                LogInfo(fmt::format("restore: apply begin (rss {} MB)", GetCurrentRssMB()));
#endif
                                // everything validated - now apply the snapshot (caller holds the freeze)

                                for (const PendingRange& pending : pendingRanges)
                                {
                                                MMURange* target = nullptr;
                                                for (MMURange* range : currentRanges)
                                                {
                                                                if (range->getBase() == pending.baseAddress)
                                                                {
                                                                                target = range;
                                                                                break;
                                                                }
                                                }
                                                in.seekg((std::streamoff)pending.fileOffset, std::ios::beg);
                                                if (pending.compressedSize > 0)
                                                {
                                                                SetPhase("apply range base=%08x size=%u comp=%u",
                                                                                                                (unsigned)pending.baseAddress, (unsigned)pending.size,
                                                                                                                (unsigned)pending.compressedSize);
                                                                if (!ReadCompressedRange(in, target->getPtr(), pending.size, pending.compressedSize))
                                                                {
                                                                                result.errorMessage = fmt::format("Failed to decompress memory range {:08x}", pending.baseAddress);
                                                                                LogWarning(result.errorMessage);
                                                                                return result;
                                                                }
                                                }
                                                else
                                                {
                                                                // compressed state files must never fall back to a raw read -
                                                                // that would load a compressed payload into emulated memory
                                                                // as-is and crash the resumed game
                                                                if (compressedRanges)
                                                                {
                                                                                result.errorMessage = fmt::format("Memory range {:08x} lost its compressed payload", pending.baseAddress);
                                                                                LogWarning(result.errorMessage);
                                                                                return result;
                                                                }
                                                                if (!ReadRawRange(in, target->getPtr(), pending.size))
                                                                {
                                                                                result.errorMessage = "Failed to read memory range from state file";
                                                                                LogWarning(result.errorMessage);
                                                                                return result;
                                                                }
                                                }
                                }

#ifdef __ANDROID__
                                LogInfo(fmt::format("restore: apply done (rss {} MB)", GetCurrentRssMB()));
#endif
                                // restore host-side state
                                sysAreaAllocatorOffset = (MPTR)header.sysAreaAllocatorOffset;
                                iosu::fsa::FSARestoreFileStates(fileStates);

                                // restore the host-side registries which are not covered by the memory
                                // snapshot (AX voice lists/callbacks, alarm registry)
                                for (const auto& section : hostSections)
                                {
                                                                const uint8* sectionData = section.second.data();
                                                                const uint8* sectionEnd = sectionData + section.second.size();
                                                                if (section.first == kHostSectionAx)
                                                                {
                                                                                                if (!snd_core::SavestateRestoreHostState(sectionData, sectionEnd))
                                                                                                                                LogWarning("AX host state section is invalid");
                                                                }
                                                                else if (section.first == kHostSectionAlarms)
                                                                {
                                                                                                if (!coreinit::SavestateRestoreAlarms(sectionData, sectionEnd))
                                                                                                                                LogWarning("Alarm host state section is invalid");
                                                                }
                                }

                                LogInfo("restore: memory + host state applied");
                                // resync the host-side pending flip counter with the restored emulated
                                // counters, otherwise the GPU keeps executing phantom flips and
                                // GX2WaitSwap never observes request == execute
                                if (LatteGPUState.sharedArea)
                                {
                                                                const uint32 requestedFlips = _swapEndianU32(LatteGPUState.sharedArea->flipRequestCountBE);
                                                                const uint32 executedFlips = _swapEndianU32(LatteGPUState.sharedArea->flipExecuteCountBE);
                                                                LatteGPUState.flipRequestCount.store(requestedFlips > executedFlips ? (uint64)(requestedFlips - executedFlips) : 0ull);
                                }

                                // drop GX2 event callbacks which were queued after the snapshot was
                                // taken (they belong to the discarded timeline)
                                GX2::ResetQueuedEventCallbacks();

                                // rebuild the host scheduler state from the restored emulated memory
                                coreinit::SavestateRebuildSchedulerAfterRestore();
                                // v3.9: audit the rebuilt scheduler before anything is scheduled.
                                // A thread that is schedulable but has no host fiber (or a run
                                // queue whose semaphore count disagrees with its content) would
                                // crash the scheduler thread the moment emulation resumes - this
                                // makes such a state visible in the log BEFORE the crash
                                coreinit::SavestateLogSchedulerInvariants();

                                // the restored memory may contain different code than the currently
                                // JIT-compiled fragments (self-modifying code), so drop everything
                                PPCRecompiler_invalidateRange(0x00010000, 0x10000000);
                                PPCRecompiler_invalidateRange(0xF6000000, 0xF8000000);

                                LogInfo("restore: scheduler rebuilt + recompiler invalidated");
                                // drop AX IST frame messages left over from the pre-restore
                                // timeline while the emulated threads are still suspended -
                                // the resumed IST thread must not run stale mixer frames
                                snd_core::AXIst_DrainPendingFrames();
                                // the host audio output must be reset after an in-place restore:
                                // stale queued blocks, partially accumulated DMA groups and a
                                // desynced frame gate would otherwise cause probabilistic silence
                                snd_core::AXOut_ResetAfterRestore();

                                // cheat patches live in emulated memory and were just overwritten
                                // by the snapshot - re-apply all enabled cheats
                                Cheats::CheatManager::GetInstance().ReapplyAll();

                                LogInfo("restore: audio reset + cheats reapplied");
                                LogInfo(fmt::format("Restored state for {:016x} ({} ranges, {} file handles)",
                                                expectedTitleId, pendingRanges.size(), fileStates.size()));

                                result.success = true;
                                return result;
                }

                OperationResult CaptureStateToFile(uint64 titleId, const std::filesystem::path& filePath)
                {
                                InstallSaveStateCrashReporter();
                                SetPhase("capture: acquiring freeze (titleId %016llx)", (unsigned long long)titleId);
                                try
                                {
                                                EmulationFreeze freeze;
                                                std::string freezeError;
                                                if (!freeze.begin(freezeError))
                                                                return { false, freezeError };
                                                return CaptureStateToFileLocked(titleId, filePath);
                                }
                                catch (const std::exception& e)
                                {
                                                LogWarning(fmt::format("capture threw an exception: {}", e.what()));
                                                return { false, fmt::format("Exception during capture: {}", e.what()) };
                                }
                                catch (...)
                                {
                                                LogWarning("capture threw an unknown exception");
                                                return { false, "Unknown exception during capture" };
                                }
                }

                OperationResult RestoreStateFromFile(uint64 expectedTitleId, const std::filesystem::path& filePath)
                {
                                // the file is validated before the freeze is acquired so that an
                                // invalid file never interrupts the running emulation
                                std::error_code ec;
                                if (!std::filesystem::exists(filePath, ec))
                                                return { false, "State file does not exist" };
                                // v3.3: mirror progress to logcat and catch any fatal signal / C++
                                // exception with the operation phase attached
                                InstallSaveStateCrashReporter();
                                SetPhase("restore: acquiring freeze (titleId %016llx)", (unsigned long long)expectedTitleId);
                                try
                                {
                                                EmulationFreeze freeze;
                                                std::string freezeError;
                                                if (!freeze.begin(freezeError))
                                                                return { false, freezeError };
                                                auto result = RestoreStateFromFileLocked(expectedTitleId, filePath);
                                                if (result.success)
                                                                freeze.RequestGpuCacheReset();
                                                return result;
                                }
                                catch (const std::exception& e)
                                {
                                                LogWarning(fmt::format("restore threw an exception: {}", e.what()));
                                                return { false, fmt::format("Exception during restore: {}", e.what()) };
                                }
                                catch (...)
                                {
                                                LogWarning("restore threw an unknown exception");
                                                return { false, "Unknown exception during restore" };
                                }
                }

// v3.7: register savestate functions for the crash reporter
SAVESTATE_REGISTER_FN(RestoreStateFromFileLocked)
SAVESTATE_REGISTER_FN(CaptureStateToFile)
}
