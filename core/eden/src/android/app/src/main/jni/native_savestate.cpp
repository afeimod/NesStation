// SPDX-FileCopyrightText: Copyright 2026 NesStation / Eden Emulator Project
// SPDX-License-Identifier: GPL-3.0-or-later
//
// ★★★ NesStation 集成：Switch（Eden）核心即时存档 —— 原生实现 v4 ★★★
//
// v4（本轮）：修复 v3 的三个线上问题 ——
//   ① 读档后闪退（host_memory.cpp:770 断言 / blit memcpy 崩溃）：
//      根因是「只回滚 guest 内存内容，不校验宿主侧布局」。存档后游戏任何
//      一次映射变化（堆扩缩、线程栈/TLS、共享内存重映射）都会让回滚后的
//      guest 代码按过期视图访问内存 → NCE 野地址崩溃。v4 把完整内存块布局
//      （KMemoryBlockManager 视图：va/size/state/perm/attr，归一化）与
//      非 dummy 线程 id 集随档快照，读档前逐块比对——完全一致才允许原地
//      恢复；不一致则自动走 legacy 重启路径（绝不带着分叉状态续跑）。
//   ② 读档浪费性能：v3 的「临时负载文件 + 合并拷贝」双倍 I/O、每 4KB 页
//      一次 icache 刷新、整段解压到堆。v4 改为 Cemu 同款流式 zstd
//      （level 3 + 帧校验和 + pledged size，源直接取 guest 连续后备 run，
//      无中间拷贝）、有界 1MB 流式解压、host 连续 run 合并拷贝 + 按 run
//      粒度 icache 刷新。
//      （注：Cemu 的 MADV_DONTNEED 零页弃置不适用 Eden —— 其竞技场是
//      memfd 上的 MAP_SHARED，脏页存活于 page cache，madvise 不会归零，
//      只能照实写回。）
//   ③ 校验先行：读档先完整校验（头部 / 布局 / 线程集 / 逐帧 zstd 流式
//      解码丢弃验证）再动内存 —— 撕裂写入永不发生（Cemu 同款保障）；
//      应用中途 I/O 失败则直接关停核心而不是带着半恢复状态续跑。
//
// v4 新结果码 RESULT_OK_LEGACY_RESTART(7)：布局/线程集已变化、原地恢复
// 不可行时自动走 legacy 路径（重启游戏 + NAND 还原）并以此码告知前端。
//
// v3：nvdrv GPU 状态段（GMMU 映射 + nvmap 句柄表 + SMMU 设备地址分配）
//     随档快照/回放（保留不变，走 SMMU 正规 Map/Unmap API，缓存自洽）。
// v2：state.bin 进程内全量状态捕获/原地恢复（保留）。
// v1：NAND 存档数据快照 + legacy（关停游戏→写回→前端重启）（保留）。
//
//   Cemu (Wii U)                        Eden (Switch / yuzu 系)
//   ─────────────────────────────      ─────────────────────────────────────
//   静态 MMURange 集（布局固定）    →   动态地址空间 → v4 随档快照归一化布局
//                                        + 读档严格比对，不一致拒绝原地恢复
//   CafeSaveState::EmulationFreeze  →   本文件 EmulationFreeze（RAII +
//                                        Pause → GPU WaitForIdle → ObtainContext）
//   WriteCompressedRange（流式       →   ZstdFrameWriter（同款流式 + 帧校验
//     zstd level3 + 帧校验和）            和 + 直接从 guest 连续 run 取源）
//   ValidateCompressedRange（先校    →   ValidateFrame（同款流式解码丢弃）
//     验后落内存）
//   ReadCompressedRange（有界流式）  →   ApplyFrame（同款 + run 合并 icache）
//   进程级冻结串行化（v3.9）        →   FreezeOpMutex()（同款）
//   logcat 阶段诊断（CemuSaveState）→   tag "EdenSaveState"（阶段+耗时+RSS）
//   MLC usr/save 按标题拷贝         →   NAND user/save 按标题拷贝
//   CafeSystem::ShutdownTitle()     →   HaltEmulation + WaitForShutdown
//
// 状态一致性依据（已对照 Eden 内核源码逐行核对）：
//   · System::Pause() 返回即全部寄存器已停放进 KThread::m_thread_context
//     （KernelCore::SuspendEmulation 自旋等待核心卸载 guest 线程）。
//   · 恢复只需写回 m_thread_context：线程重回核心时 KScheduler::Reload →
//     PhysicalCore::LoadContext → interface->SetContext（dynarmic/NCE 同一
//     契约）。
//   · GPU：Pause → WaitForIdle → ObtainContext 排空异步 GPU；捕获前
//     rasterizer->FlushAll() 把在途脏写回刷入 guest 内存。
//   · 布局 + 线程集一致 ⇒ guest 内容回滚后，宿主 HLE 内核视图与存档时刻
//     逐块相同 ⇒ 无野地址（v3 闪退根因消除）。

#include "native_savestate.h"

#include "native.h"

#include "common/common_funcs.h"
#include "common/fs/path_util.h"
#include "common/logging.h"
#include "core/core.h"
#include "core/hardware_properties.h"
#include "core/hle/kernel/k_memory_block.h"
#include "core/hle/kernel/k_process.h"
#include "core/hle/kernel/k_thread.h"
#include "core/hle/kernel/kernel.h"
#include "core/hle/result.h"
#include "core/hle/service/nvdrv/core/container.h"
#include "core/hle/service/nvdrv/core/nvmap.h"
#include "core/hle/service/nvdrv/devices/nvdevice.h"
#include "core/hle/service/nvdrv/devices/nvhost_as_gpu.h"
#include "core/hle/service/nvdrv/nvdrv.h"
#include "core/hle/service/nvdrv/nvdrv_interface.h"
#include "core/hle/service/sm/sm.h"
#include "core/loader/loader.h"
#include "core/memory.h"
#include "video_core/gpu.h"
#include "video_core/host1x/host1x.h"
#include "video_core/memory_manager.h"
#include "video_core/rasterizer_interface.h"
#include "video_core/renderer_base.h"

#include <zstd.h>

#ifdef __ANDROID__
#include <android/log.h>
#endif

#include <dlfcn.h> // dlsym: malloc_trim 运行时解析（见 TrimHeap）

#include <algorithm>
#include <array>
#include <atomic>
#include <chrono>
#include <cmath>
#include <cstddef>
#include <cstdio>
#include <cstring>
#include <ctime>
#include <filesystem>
#include <fstream>
#include <iterator>
#include <memory>
#include <mutex>
#include <span>
#include <string>
#include <string_view>
#include <thread>
#include <vector>

#include <fmt/format.h>

namespace fs = std::filesystem;

namespace {

using native_savestate::kAutoBackupSlot;
using native_savestate::kMaxSlots;

constexpr int kThumbMaxWidth = 320;
constexpr int kThumbMaxHeight = 180;

// ---------------------------------------------------------------------------
// state.bin v4 —— 进程内全量状态文件格式
//
//   [StateHeaderV4]
//   [BlockDescV4 × block_count]       （完整归一化布局：含未映射块）
//   [ContentDescV4 × content_count]   （FlagMapped 块的内容帧描述符；
//                                       compressed 字段先占位后回填）
//   [zstd 帧 × content_count]         （逐块流式压缩，帧校验和 + pledged size）
//   [nvdrv 段（NvdrvHeaderV3…）]
//   [线程记录 × thread_count]          （32B 元数据 + 0x320B 寄存器组）
//
//   布局来源：页表 QueryInfo 遍历（svcQueryMemory 同源序列），相邻同
//   state/perm/attr 的块归一合并（快照/比对两端同一规则）。
// ---------------------------------------------------------------------------

constexpr char kStateMagic[4] = {'E', 'D', 'S', 'S'};
constexpr u32 kStateVersion = 4;
constexpr size_t kPageSize = 4 * 1024; // 4KB，≤ 宿主页表粒度

// v3/v4：nvdrv GPU 状态段存在标志（GMMU 映射 + nvmap 句柄表随档保存）。
// 没有该段的存档原地恢复必拒（宿主 GPU 状态与回滚内存不一致 → 崩溃）。
constexpr u32 kStateFlagHasNvdrv = 1u << 0;

// 校验上限（防损坏头/错位文件把分配器炸穿）
constexpr u32 kMaxBlockCount = 65536;
constexpr u32 kMaxThreadCount = 16384;
constexpr u64 kMaxNvdrvSize = 256ull * 1024 * 1024;

struct StateHeaderV4 {
    char magic[4];
    u32 version;
    u64 program_id;
    u64 timestamp;           // time(nullptr)，诊断用
    u32 block_count;         // 归一化布局块数（全部，含未映射）
    u32 content_count;       // 内容帧数（= FlagMapped 归一块数）
    u32 thread_count;
    u32 flags;               // bit0: kStateFlagHasNvdrv
    u64 total_uncompressed;  // 全部内容帧未压缩字节
    u64 nvdrv_size;
    u64 layout_digest;       // 归一化布局 FNV-1a（快速预检）
};
static_assert(sizeof(StateHeaderV4) == 64);

struct BlockDescV4 {
    u64 va;
    u64 size;
    u32 state;    // KMemoryState 原始值
    u32 perm;     // KMemoryPermission 原始值
    u32 attr;     // KMemoryAttribute 原始值
    u32 reserved;
};
static_assert(sizeof(BlockDescV4) == 32);

struct ContentDescV4 {
    u64 va;
    u64 uncompressed; // 未压缩字节数（= 归一块大小）
    u64 compressed;   // zstd 帧字节数（写入时占位，帧完成后回填）
    u32 reserved0;
    u32 reserved1;
};
static_assert(sizeof(ContentDescV4) == 32);
static_assert(offsetof(ContentDescV4, compressed) == 16);

struct ThreadMetaV2 {
    u64 thread_id;
    u64 tls_addr;
    u32 raw_state; // 已屏蔽 SuspendFlagMask 的 ThreadState
    s32 active_core;
    s32 priority;
    u32 reserved;
};
static_assert(sizeof(ThreadMetaV2) == 32);

constexpr size_t kThreadRecordSize =
    sizeof(ThreadMetaV2) + sizeof(Kernel::Svc::ThreadContext);

// v3 nvdrv 段（保留不变）：
//   [NvdrvHeaderV3]
//   [GmmuSectionV3 + GpuMappingRecord × mapping_count] × gmmu_count
//   [HandleStateRecord × handle_count]
//（GpuMappingRecord = Tegra::MemoryManager::GpuMappingRecord，
//  HandleStateRecord = NvCore::NvMap::HandleStateRecord，均 POD）

struct NvdrvHeaderV3 {
    u32 gmmu_count;     // GMMU 实例段数
    u32 handle_count;   // nvmap 句柄记录数
    u32 next_handle_id; // nvmap id 序列
    u32 reserved;
};
static_assert(sizeof(NvdrvHeaderV3) == 16);

struct GmmuSectionV3 {
    u64 gmmu_id;       // Tegra::MemoryManager::GetID()，恢复时按此匹配实例
    u32 mapping_count; // 该实例的映射记录数
    u32 reserved;
};
static_assert(sizeof(GmmuSectionV3) == 16);

// ---------------------------------------------------------------------------
// 诊断（Cemu SaveState.cpp v3.3 同款）：cemuLog 之外的 logcat 镜像 +
// 阶段耗时 + RSS。native 侧信号三关（tripwire）不移植 —— NCE 自身占用
// SIGSEGV/SIGBUS 处理 fastmem 缺页，叠加竞争 handler 会破坏缺页路径。
// ---------------------------------------------------------------------------

#ifdef __ANDROID__
void SSLogcatLine(const char* msg) {
    __android_log_print(ANDROID_LOG_INFO, "EdenSaveState", "%s", msg);
}
void SSLogcatWarn(const char* msg) {
    __android_log_print(ANDROID_LOG_WARN, "EdenSaveState", "%s", msg);
}
u32 CurrentRssMB() {
    std::FILE* f = std::fopen("/proc/self/statm", "rb");
    if (f == nullptr) {
        return 0;
    }
    unsigned long total = 0, resident = 0;
    const int n = std::fscanf(f, "%lu %lu", &total, &resident);
    std::fclose(f);
    if (n != 2) {
        return 0;
    }
    return static_cast<u32>(resident * 4096 / (1024 * 1024));
}
#else
inline void SSLogcatLine(const char*) {}
inline void SSLogcatWarn(const char*) {}
inline u32 CurrentRssMB() {
    return 0;
}
#endif

void SSLogInfo(std::string_view msg) {
    LOG_INFO(Frontend, "Eden savestate: {}", msg);
    SSLogcatLine(fmt::format("{}", msg).c_str());
}

void SSLogWarn(std::string_view msg) {
    LOG_WARNING(Frontend, "Eden savestate: {}", msg);
    SSLogcatWarn(fmt::format("{}", msg).c_str());
}

std::chrono::steady_clock::time_point NowSteady() {
    return std::chrono::steady_clock::now();
}

s64 ElapsedMsSince(std::chrono::steady_clock::time_point t0) {
    return std::chrono::duration_cast<std::chrono::milliseconds>(NowSteady() - t0)
        .count();
}

// 进程级冻结串行化（Cemu v3.9 同款）：quick save 与 quick load 并发 /
// 双击连点会交织 pause/resume 与内存访问。前端 stateOpLock 之外再加
// native 侧互斥，双保险。
std::mutex& FreezeOpMutex() {
    static std::mutex m;
    return m;
}

// ---------------------------------------------------------------------------
// 会话 / 标题信息
// ---------------------------------------------------------------------------

EmulationSession& Session() {
    return EmulationSession::GetInstance();
}

bool IsTitleRunning() {
    return Session().IsRunning();
}

u64 GetForegroundProgramId() {
    try {
        return Session().System().GetApplicationProcessProgramID();
    } catch (...) {
        return 0;
    }
}

std::string GetForegroundTitleName() {
    try {
        std::string title;
        auto& loader = Session().System().GetAppLoader();
        if (loader.ReadTitle(title) == Loader::ResultStatus::Success && !title.empty()) {
            return title;
        }
    } catch (...) {
    }
    return {};
}

// ---------------------------------------------------------------------------
// 槽位目录布局
// ---------------------------------------------------------------------------

fs::path UserDir() {
    return Common::FS::GetEdenPath(Common::FS::EdenPath::EdenDir);
}

fs::path GetSlotBaseDir(u64 program_id) {
    return UserDir() / "savestates" / fmt::format("{:016x}", program_id);
}

fs::path GetSlotDir(u64 program_id, int slot) {
    if (slot == kAutoBackupSlot) {
        return GetSlotBaseDir(program_id) / "autobackup";
    }
    return GetSlotBaseDir(program_id) / fmt::format("slot{}", slot);
}

fs::path GetSlotSaveDir(u64 program_id, int slot) {
    return GetSlotDir(program_id, slot) / "savedata";
}

fs::path GetSlotStatePath(u64 program_id, int slot) {
    return GetSlotDir(program_id, slot) / "state.bin";
}

fs::path NandRoot() {
    return Common::FS::GetEdenPath(Common::FS::EdenPath::NANDDir);
}

// ---------------------------------------------------------------------------
// 按标题的 NAND 用户存档定位（legacy 后备数据源）
//
// yuzu 系存档布局（savedata_factory.cpp GetFullPath）：
//   旧 account： nand/user/save/0000000000000000/<uidHiLo>/<titleId>
//   新 account： nand/user/save/account/<uuid>/<baseTitleId>/0
//   device   ： nand/user/save/device/<titleId>/0
//   cache    ： nand/user/save/cache/<titleId>（可再生的派生数据，不快照）
// ---------------------------------------------------------------------------

struct SaveDirEntry {
    fs::path source;   // nand 下的绝对源路径
    fs::path relative; // 相对 nand 根的相对路径（含 user/save 前缀）
};

bool MatchesTitleDir(const std::string& name, u64 program_id) {
    const std::string exact = fmt::format("{:016X}", program_id);
    const std::string base = fmt::format("{:016X}", program_id & ~0xFFull);
    return name == exact || name == base;
}

std::vector<SaveDirEntry> CollectTitleSaveDirs(u64 program_id) {
    std::vector<SaveDirEntry> result;
    std::error_code ec;

    const fs::path user_save = NandRoot() / "user" / "save";
    if (!fs::exists(user_save, ec)) {
        return result;
    }

    for (const auto& level1 : fs::directory_iterator{user_save, ec}) {
        if (ec) {
            break;
        }
        const std::string l1name = level1.path().filename().string();
        if (l1name == "cache") {
            continue; // 可再生缓存，不进快照
        }

        if (l1name == "account") {
            // 新布局：account/<uuid>/<titleId>/0
            for (const auto& uuid_dir : fs::directory_iterator{level1.path(), ec}) {
                if (ec || !uuid_dir.is_directory()) {
                    ec.clear();
                    continue;
                }
                for (const auto& title_dir : fs::directory_iterator{uuid_dir.path(), ec}) {
                    if (ec || !title_dir.is_directory()) {
                        ec.clear();
                        continue;
                    }
                    if (MatchesTitleDir(title_dir.path().filename().string(), program_id)) {
                        result.push_back(
                            {title_dir.path(), fs::relative(title_dir.path(), NandRoot(), ec)});
                    }
                    ec.clear();
                }
            }
        } else if (l1name == "device") {
            // device/<titleId>/0
            for (const auto& title_dir : fs::directory_iterator{level1.path(), ec}) {
                if (ec || !title_dir.is_directory()) {
                    ec.clear();
                    continue;
                }
                if (MatchesTitleDir(title_dir.path().filename().string(), program_id)) {
                    result.push_back(
                        {title_dir.path(), fs::relative(title_dir.path(), NandRoot(), ec)});
                }
                ec.clear();
            }
        } else {
            // 旧布局：save/<全零用户目录>/<uidHiLo>/<titleId>
            for (const auto& uid_dir : fs::directory_iterator{level1.path(), ec}) {
                if (ec || !uid_dir.is_directory()) {
                    ec.clear();
                    continue;
                }
                for (const auto& title_dir : fs::directory_iterator{uid_dir.path(), ec}) {
                    if (ec || !title_dir.is_directory()) {
                        ec.clear();
                        continue;
                    }
                    if (MatchesTitleDir(title_dir.path().filename().string(), program_id)) {
                        result.push_back(
                            {title_dir.path(), fs::relative(title_dir.path(), NandRoot(), ec)});
                    }
                    ec.clear();
                }
            }
        }
        ec.clear();
    }
    return result;
}

// ---------------------------------------------------------------------------
// 通用文件工具（与 Cemu NativeQuickSave.cpp 逐块对应）
// ---------------------------------------------------------------------------

// 归还空闲堆页（bionic malloc_trim 只在 API>=28 的 <malloc.h> 里声明，
// 符号在所有设备上都存在 —— 运行时 dlsym 解析，任何 minSdk 可编译）
void TrimHeap() {
    using MallocTrimFn = int (*)(size_t);
    static const MallocTrimFn mallocTrimFn =
        reinterpret_cast<MallocTrimFn>(dlsym(RTLD_DEFAULT, "malloc_trim"));
    if (mallocTrimFn != nullptr) {
        mallocTrimFn(0);
    }
}

bool CopyDirectory(const fs::path& src, const fs::path& dst) {
    std::error_code ec;
    if (!fs::exists(src, ec)) {
        return false;
    }

    fs::create_directories(dst, ec);
    if (ec) {
        return false;
    }

    for (const auto& entry : fs::recursive_directory_iterator(
             src, fs::directory_options::skip_permission_denied, ec)) {
        if (ec) {
            return false;
        }

        const auto relative = fs::relative(entry.path(), src, ec);
        if (ec) {
            return false;
        }

        const auto target = dst / relative;
        if (entry.is_directory(ec)) {
            fs::create_directories(target, ec);
        } else {
            fs::create_directories(target.parent_path(), ec);
            fs::copy_file(entry.path(), target, fs::copy_options::overwrite_existing, ec);
        }

        if (ec) {
            return false;
        }
    }
    return !ec;
}

u64 GetDirectorySize(const fs::path& path) {
    std::error_code ec;
    if (!fs::exists(path, ec)) {
        return 0;
    }

    u64 totalSize = 0;
    for (const auto& entry : fs::recursive_directory_iterator(
             path, fs::directory_options::skip_permission_denied, ec)) {
        if (!entry.is_directory(ec)) {
            totalSize += entry.file_size(ec);
        }
    }
    return totalSize;
}

std::string GetCurrentTimestamp() {
    std::time_t now = std::time(nullptr);
    std::tm* localTime = std::localtime(&now);
    char buffer[64];
    std::strftime(buffer, sizeof(buffer), "%Y-%m-%d %H:%M:%S", localTime);
    return buffer;
}

std::string EscapeJsonString(std::string_view input) {
    std::string result;
    result.reserve(input.size() + 8);
    for (char c : input) {
        switch (c) {
        case '"':
            result += "\\\"";
            break;
        case '\\':
            result += "\\\\";
            break;
        case '\n':
            result += "\\n";
            break;
        case '\r':
            result += "\\r";
            break;
        case '\t':
            result += "\\t";
            break;
        default:
            if (static_cast<unsigned char>(c) < 0x20) {
                result += fmt::format("\\u{:04x}", c);
            } else {
                result += c;
            }
            break;
        }
    }
    return result;
}

void WriteSlotInfoFile(const fs::path& slotDir) {
    std::error_code ec;
    fs::create_directories(slotDir, ec);
    std::ofstream file(slotDir / "info.txt", std::ios::binary | std::ios::trunc);
    if (file) {
        file << GetCurrentTimestamp();
    }
}

std::string ReadSlotInfoFile(const fs::path& slotDir) {
    std::ifstream file(slotDir / "info.txt", std::ios::binary);
    if (!file) {
        return {};
    }
    std::string content((std::istreambuf_iterator<char>(file)), std::istreambuf_iterator<char>());
    return content;
}

// ---------------------------------------------------------------------------
// 缩略图（TGA）—— 写入端与 Cemu 相同（24bit 未压缩 TGA），读取端为本
// 文件内置的最小解析器（不依赖 CImg）。
// ---------------------------------------------------------------------------

// Eden 渲染器截图回读为 BGRA（VK_FORMAT_B8G8R8A8_UNORM）。
// TGA 真彩像 element 天然为 B/G/R 字节序 —— 直接逐通道拷贝即可。
void WriteThumbnailTGA(const fs::path& path, const u8* bgra, int width, int height) {
    if (width <= 0 || height <= 0) {
        return;
    }

    std::error_code ec;
    fs::create_directories(path.parent_path(), ec);

    int thumbWidth = width;
    int thumbHeight = height;
    const double widthScale = static_cast<double>(kThumbMaxWidth) / static_cast<double>(width);
    const double heightScale = static_cast<double>(kThumbMaxHeight) / static_cast<double>(height);
    const double scale = std::min(1.0, std::min(widthScale, heightScale));
    thumbWidth = std::max(1, static_cast<int>(std::lround(width * scale)));
    thumbHeight = std::max(1, static_cast<int>(std::lround(height * scale)));

    std::vector<u8> thumbData(static_cast<size_t>(thumbWidth) * thumbHeight * 3);
    for (int y = 0; y < thumbHeight; ++y) {
        const int srcY =
            std::min(height - 1, static_cast<int>(static_cast<s64>(y) * height / thumbHeight));
        for (int x = 0; x < thumbWidth; ++x) {
            const int srcX =
                std::min(width - 1, static_cast<int>(static_cast<s64>(x) * width / thumbWidth));
            const u8* src = bgra + (static_cast<size_t>(srcY) * width + srcX) * 4;
            u8* dst = thumbData.data() + (static_cast<size_t>(y) * thumbWidth + x) * 3;
            dst[0] = src[0]; // B
            dst[1] = src[1]; // G
            dst[2] = src[2]; // R
        }
    }

    std::ofstream file(path, std::ios::binary | std::ios::trunc);
    if (!file) {
        return;
    }

    u8 header[18] = {};
    header[2] = 2; // uncompressed true-color image
    header[12] = static_cast<u8>(thumbWidth % 256);
    header[13] = static_cast<u8>(thumbWidth / 256);
    header[14] = static_cast<u8>(thumbHeight % 256);
    header[15] = static_cast<u8>(thumbHeight / 256);
    header[16] = 24; // bits per pixel
    header[17] = 0x20; // top-left origin
    file.write(reinterpret_cast<const char*>(header), sizeof(header));
    file.write(reinterpret_cast<const char*>(thumbData.data()),
               static_cast<std::streamsize>(thumbData.size()));
}

// 请求渲染器截图并存为缩略图（Cemu CaptureScreenshotToThumbnail 同款：
// 挂起状态下无新帧合成，截图请求永不完成 —— 必须在冻结前、运行中发起，
// 3 秒超时放弃，不阻塞存档流程）。
bool CaptureScreenshotToThumbnail(const fs::path& thumbPath) {
    if (!IsTitleRunning()) {
        return false;
    }

    Core::System& system = Session().System();
    VideoCore::RendererBase& renderer = system.Renderer();

    if (renderer.IsScreenshotPending()) {
        return false; // 上一次请求尚未完成（UI 截图并发场景），跳过
    }

    const Layout::FramebufferLayout layout = renderer.GetRenderWindow().GetFramebufferLayout();
    const int width = layout.width;
    const int height = layout.height;
    if (width <= 0 || height <= 0) {
        return false;
    }

    struct CaptureState {
        std::mutex mutex;
        std::condition_variable cv;
        bool done = false;
    };
    const auto state = std::make_shared<CaptureState>();

    std::vector<u8> pixels(static_cast<size_t>(width) * height * 4);

    renderer.RequestScreenshot(
        pixels.data(),
        [state](bool) {
            std::lock_guard lock{state->mutex};
            state->done = true;
            state->cv.notify_all();
        },
        layout);

    {
        std::unique_lock lock(state->mutex);
        state->cv.wait_for(lock, std::chrono::seconds(3), [&state] { return state->done; });
    }

    // 超时后 pending 请求不能打进已销毁栈帧 —— 与 Cemu 相同：
    // 由渲染器在下一次合成时安全消费/丢弃，不在此处取消。

    if (!state->done) {
        return false;
    }

    WriteThumbnailTGA(thumbPath, pixels.data(), width, height);
    std::error_code ec;
    return fs::exists(thumbPath, ec);
}

// ---------------------------------------------------------------------------
// 冻结（★ 异步 GPU 同步架构核心）
//
// 与树内已验证的 ReloadDiskShaderCache 同步序列完全一致：
//   Pause → GPU().WaitForIdle() → ObtainContext() ... ReleaseContext → Run
// Pause() 返回即：全部 guest 线程挂起且寄存器停放进
// KThread::m_thread_context（SuspendEmulation 自旋等待核心卸载线程）。
//
// HaltInsteadOfResume()：应用阶段中途失败（内存已半恢复）时调用 ——
// 析构时直接关停核心而不是续跑撕裂状态（Cemu 以「先全量校验」规避，
// 这里对残余 I/O 风险再加一道兜底）。
// ---------------------------------------------------------------------------

class EmulationFreeze {
public:
    explicit EmulationFreeze(Core::System& system) : m_system{system} {
        m_was_paused = m_system.IsPaused();
        if (!m_was_paused) {
            m_system.Pause();
        }
        // ★ 异步 GPU 排空：投递同步操作并等待 GPU 线程越过栅栏 —— 确保
        //   捕获/写回时 guest 可见内存/NAND 视图不再有在途 GPU 写入
        //   （NVDEC 解码帧回写、半更新的缓冲等）。
        m_system.GPU().WaitForIdle();
        m_system.GPU().ObtainContext();
    }

    ~EmulationFreeze() {
        try {
            m_system.GPU().ReleaseContext();
            if (m_halt) {
                Session().HaltEmulation(); // 撕裂状态不续跑：干净关停
            } else if (!m_was_paused) {
                m_system.Run();
            }
        } catch (...) {
            // 析构不抛
        }
    }

    void HaltInsteadOfResume() {
        m_halt = true;
    }

    EmulationFreeze(const EmulationFreeze&) = delete;
    EmulationFreeze& operator=(const EmulationFreeze&) = delete;

private:
    Core::System& m_system;
    bool m_was_paused{};
    bool m_halt{};
};

// ---------------------------------------------------------------------------
// nvdrv GPU 状态（v3）—— 宿主侧 HLE 派生状态的快照 / 回放
//
// GPU 访问链：GPU VA →(GMMU，Tegra::MemoryManager)→ DAddr
//            →(SMMU，MaxwellDeviceMemoryManager)→ guest VA → guest 内存。
// 存档回滚只还原最后一环（guest 内存）；GMMU 页表、SMMU 分配、nvmap 句柄
// 表都是宿主侧状态，存档后游戏的重映射会让链路前端与回滚后的 guest 驱动
// 分叉 → 恢复运行后在途命令流按过期页表翻译 → 野指针（blit/memcpy 崩溃）。
// 与 Cemu 不同（Cemu 的 GPU 直接走 PPC 地址空间，无此问题），Eden/yuzu 系
// 必须把这三层随档快照、读档回放，才能达到 Cemu RestoreStateFromFile 的
// 原地恢复一致性。回放走 SMMU/GMMU 正规 Map/Unmap/AllocateFixed API，
// DeviceMemoryManager 的翻译缓存（tracked_entries/t_slot 等）随之自洽。
// ---------------------------------------------------------------------------

using NvdrvHandleRecord = Service::Nvidia::NvCore::NvMap::HandleStateRecord;
using GpuMappingRecord = Tegra::MemoryManager::GpuMappingRecord;

std::shared_ptr<Service::Nvidia::Module> GetNvdrvModule(Core::System& system) {
    auto nvdrv = system.ServiceManager().GetService<Service::Nvidia::NVDRV>("nvdrv");
    if (nvdrv == nullptr) {
        return nullptr;
    }
    return nvdrv->GetModule();
}

struct GmmuInstanceRef {
    u64 id;
    Tegra::MemoryManager* mm;
};

// 收集全部活跃 GMMU 实例：host1x 低位 pin AS + 每个已打开 nvhost_as_gpu
// 的 per-AS GMMU（channel_state->memory_manager 绑定的就是后者）。
void CollectGmmuInstances(Core::System& system, Service::Nvidia::Module& nvdrv,
                          std::vector<GmmuInstanceRef>& out) {
    out.clear();
    auto& host1x = system.Host1x();
    out.push_back({host1x.gmmu_manager.GetID(), &host1x.gmmu_manager});
    nvdrv.ForEachDevice([&](Service::Nvidia::Devices::nvdevice* device) {
        // -fno-rtti 构建下不可用 dynamic_cast；走 nvdevice 基类虚方法
        // （仅 nvhost_as_gpu 覆写，返回其 per-AS GMMU）。
        Tegra::MemoryManager* mm = device->GetAsGpuMemoryManager();
        if (mm == nullptr) {
            return;
        }
        for (const auto& existing : out) {
            if (existing.mm == mm) {
                return; // 同一实例只收一次
            }
        }
        out.push_back({mm->GetID(), mm});
    });
}

// 快照 nvdrv GPU 状态（须持 EmulationFreeze）。nvdrv 服务不可用返回 false。
bool CaptureNvdrvState(Core::System& system, std::vector<u8>& blob_out) {
    blob_out.clear();
    auto nvdrv = GetNvdrvModule(system);
    if (nvdrv == nullptr) {
        return false;
    }

    // 1) 各 GMMU 实例映射
    std::vector<GmmuInstanceRef> instances;
    CollectGmmuInstances(system, *nvdrv, instances);

    std::vector<u8> body;
    u32 gmmu_count = 0;
    for (const auto& inst : instances) {
        std::vector<GpuMappingRecord> mappings;
        inst.mm->SaveMappings(mappings);

        GmmuSectionV3 section{};
        section.gmmu_id = inst.id;
        section.mapping_count = static_cast<u32>(mappings.size());
        body.insert(body.end(), reinterpret_cast<const u8*>(&section),
                    reinterpret_cast<const u8*>(&section) + sizeof(section));
        if (!mappings.empty()) {
            body.insert(body.end(), reinterpret_cast<const u8*>(mappings.data()),
                        reinterpret_cast<const u8*>(mappings.data()) +
                            mappings.size() * sizeof(GpuMappingRecord));
        }
        ++gmmu_count;
    }

    // 2) nvmap 句柄表
    auto& nvmap = nvdrv->GetContainer().GetNvMapFile();
    std::vector<NvdrvHandleRecord> handles;
    u32 next_handle_id = 0;
    nvmap.SaveHandles(handles, next_handle_id);

    NvdrvHeaderV3 header{};
    header.gmmu_count = gmmu_count;
    header.handle_count = static_cast<u32>(handles.size());
    header.next_handle_id = next_handle_id;

    blob_out.insert(blob_out.end(), reinterpret_cast<const u8*>(&header),
                    reinterpret_cast<const u8*>(&header) + sizeof(header));
    if (!body.empty()) {
        blob_out.insert(blob_out.end(), body.data(), body.data() + body.size());
    }
    if (!handles.empty()) {
        blob_out.insert(blob_out.end(), reinterpret_cast<const u8*>(handles.data()),
                        reinterpret_cast<const u8*>(handles.data()) +
                            handles.size() * sizeof(NvdrvHandleRecord));
    }
    return true;
}

// 回放 nvdrv GPU 状态（须持 EmulationFreeze，且在 guest 内存写回之后、
// rasterizer 缓存失效之前调用）。失败返回 false 并填 *err（保持原状不再
// 继续原地恢复——半回放状态不可用）。
// 段布局（与 CaptureNvdrvState 写入顺序严格一致）：
//   [NvdrvHeaderV3][GmmuSectionV3 + 映射记录 × mapping_count] × gmmu_count
//   [句柄记录 × handle_count]
bool RestoreNvdrvState(Core::System& system, std::span<const u8> blob, std::string* err) {
    *err = {};
    if (blob.size() < sizeof(NvdrvHeaderV3)) {
        *err = "nvdrv section truncated";
        return false;
    }

    NvdrvHeaderV3 header{};
    std::memcpy(&header, blob.data(), sizeof(header));
    blob = blob.subspan(sizeof(header));

    auto nvdrv = GetNvdrvModule(system);
    if (nvdrv == nullptr) {
        *err = "nvdrv module unavailable";
        return false;
    }

    // ---- 解析阶段（顺序 = 文件布局）----

    // 1) 各 GMMU 实例段
    struct GmmuMappings {
        u64 gmmu_id;
        std::vector<GpuMappingRecord> mappings;
    };
    std::vector<GmmuMappings> gmmu_sections(header.gmmu_count);
    for (auto& section_records : gmmu_sections) {
        if (blob.size() < sizeof(GmmuSectionV3)) {
            *err = "nvdrv gmmu section truncated";
            return false;
        }
        GmmuSectionV3 section{};
        std::memcpy(&section, blob.data(), sizeof(section));
        blob = blob.subspan(sizeof(section));

        section_records.gmmu_id = section.gmmu_id;
        section_records.mappings.resize(section.mapping_count);
        const size_t mappings_bytes =
            section_records.mappings.size() * sizeof(GpuMappingRecord);
        if (blob.size() < mappings_bytes) {
            *err = "nvdrv gmmu mappings truncated";
            return false;
        }
        if (!section_records.mappings.empty()) {
            std::memcpy(section_records.mappings.data(), blob.data(), mappings_bytes);
        }
        blob = blob.subspan(mappings_bytes);
    }

    // 2) nvmap 句柄记录
    std::vector<NvdrvHandleRecord> handles(header.handle_count);
    const size_t handles_bytes = handles.size() * sizeof(NvdrvHandleRecord);
    if (blob.size() < handles_bytes) {
        *err = "nvdrv handle records truncated";
        return false;
    }
    if (!handles.empty()) {
        std::memcpy(handles.data(), blob.data(), handles_bytes);
    }
    blob = blob.subspan(handles_bytes);

    // ---- 执行阶段 ----

    // 3) GMMU 映射：按存档 gmmu_id 匹配当前实例，全量清空 + 按原地址重放
    //   （清空即触发 rasterizer 对全部受影响设备地址段的缓存丢弃）。
    std::vector<GmmuInstanceRef> instances;
    CollectGmmuInstances(system, *nvdrv, instances);
    for (const auto& section_records : gmmu_sections) {
        Tegra::MemoryManager* mm = nullptr;
        for (const auto& inst : instances) {
            if (inst.id == section_records.gmmu_id) {
                mm = inst.mm;
                break;
            }
        }
        if (mm == nullptr) {
            // 存档后该 AS 被重建（罕见）：跳过该实例映射，记日志
            LOG_WARNING(Frontend,
                        "Eden savestate: gmmu instance {} no longer exists, skipped",
                        section_records.gmmu_id);
            continue;
        }
        mm->RestoreMappings(section_records.mappings);
    }

    // 4) nvmap 句柄表回放：拆除当前设备映射 → 丢弃存档后新建句柄 → 按存档
    //    在原设备地址重建 SMMU 分配/映射、heap 映射与低位 pin（其低位 pin
    //    的 GMMU 重映射与第 3 步幂等）。
    auto& nvmap = nvdrv->GetContainer().GetNvMapFile();
    nvmap.RestoreHandles(handles, header.next_handle_id);
    return true;
}

// ---------------------------------------------------------------------------
// v4：内存布局（归一化）快照 / 比对 —— 原地恢复的安全前提
// ---------------------------------------------------------------------------

struct LayoutBlock {
    u64 va;
    u64 size;
    u32 state; // KMemoryState 原始值
    u32 perm;  // KMemoryPermission 原始值
    u32 attr;  // KMemoryAttribute 原始值
};

// 页表遍历收集全部内存块（svcQueryMemory 同源序列：QueryInfo 逐步推进），
// 并把相邻同 state/perm/attr 的块归一合并 —— 快照/比对两端同一规则，
// 免受块管理器碎片化表示差异的误报。
bool CollectLayoutBlocks(Core::System& system, std::vector<LayoutBlock>& out) {
    out.clear();

    Kernel::KProcess* process = system.ApplicationProcess();
    if (process == nullptr) {
        return false;
    }
    Kernel::KProcessPageTable& page_table = process->GetPageTable();

    // GetInteger 是定义在 Common 命名空间外的全局模板（common/typed_address.h
    // 中 } // namespace Common 之后），不能加 Common:: 前缀限定
    const u64 as_start = GetInteger(page_table.GetAddressSpaceStart());
    const u64 as_size = page_table.GetAddressSpaceSize();

    u64 cur = as_start;
    while (cur < as_start + as_size) {
        Kernel::KMemoryInfo info{};
        Kernel::Svc::PageInfo page_info{};
        const Common::ProcessAddress addr{cur};
        if (R_FAILED(page_table.QueryInfo(std::addressof(info), std::addressof(page_info),
                                          addr))) {
            break;
        }

        const u64 base = static_cast<u64>(info.m_address);
        const u64 size = static_cast<u64>(info.m_size);
        if (size == 0) {
            break; // 防御：QueryInfo 保证 size>0，双保险
        }

        const u32 state = static_cast<u32>(info.m_state);
        const u32 perm = static_cast<u32>(info.m_permission);
        const u32 attr = static_cast<u32>(info.m_attribute);

        if (!out.empty() && out.back().va + out.back().size == base &&
            out.back().state == state && out.back().perm == perm && out.back().attr == attr) {
            out.back().size += size; // 归一合并
        } else {
            out.push_back({base, size, state, perm, attr});
        }

        const u64 next = base + size;
        if (next <= cur) {
            break; // 回绕/溢出防御
        }
        cur = next;
    }
    return !out.empty();
}

bool IsMappedState(u32 state) {
    return True(static_cast<Kernel::KMemoryState>(state) & Kernel::KMemoryState::FlagMapped);
}

// 布局摘要（FNV-1a 64）——快速预检/日志用；权威判定永远是逐块全比较。
u64 LayoutDigest(const std::vector<LayoutBlock>& blocks) {
    constexpr u64 kPrime = 0x100000001B3ull;
    u64 h = 0xCBF29CE484222325ull;
    auto mix = [&h](u64 v) {
        h ^= v;
        h *= kPrime;
    };
    for (const auto& b : blocks) {
        mix(b.va);
        mix(b.size);
        mix(b.state);
        mix(b.perm);
        mix(b.attr);
    }
    return h;
}

bool SameLayout(const std::vector<LayoutBlock>& a, const std::vector<LayoutBlock>& b) {
    if (a.size() != b.size()) {
        return false;
    }
    for (size_t i = 0; i < a.size(); ++i) {
        if (a[i].va != b[i].va || a[i].size != b[i].size || a[i].state != b[i].state ||
            a[i].perm != b[i].perm || a[i].attr != b[i].attr) {
            return false;
        }
    }
    return true;
}

// 当前存活非 dummy 线程 id 集（排序后与存档集做全等比对）。
// —— v3 闪退根因之一：存档后新建/销毁的线程会让「内容回滚 + 当前宿主
//    线程集」分叉（堆内建线程栈不改变块布局，布局校验兜不住）。
std::vector<u64> CollectLiveThreadIds(Core::System& system) {
    std::vector<u64> ids;
    if (Kernel::KProcess* process = system.ApplicationProcess(); process != nullptr) {
        for (auto& thread : process->GetThreadList()) {
            if (thread.IsDummyThread()) {
                continue; // HLE 宿主辅助线程，无 guest 寄存器语义
            }
            ids.push_back(thread.GetThreadId());
        }
    }
    std::sort(ids.begin(), ids.end());
    return ids;
}

u8* PagePtr(Core::Memory::Memory& memory, u64 va) {
    return memory.GetPointerSilent(Common::ProcessAddress{va});
}

// ---------------------------------------------------------------------------
// v4：流式 zstd（Cemu WriteCompressedRange / ReadCompressedRange 同款）
// ---------------------------------------------------------------------------

constexpr int kZstdLevel = 3;                // Cemu 同款级别
constexpr size_t kZstdOutChunk = 1 << 20;    // 1MB 出块
constexpr size_t kZstdInChunk = 256 * 1024;  // 256KB 入块

// 单帧流式压缩器：源直接喂 guest 连续后备 run（无中间拷贝、无临时文件），
// 输出按 1MB 直接写盘。帧校验和 + pledged size 双保护（损坏载荷在读档
// 解压时被 zstd 拒绝，而不是把垃圾写进 guest 内存）。
class ZstdFrameWriter {
public:
    explicit ZstdFrameWriter(std::ofstream& out) : m_out{out}, m_buf(kZstdOutChunk) {}

    ~ZstdFrameWriter() {
        if (m_cctx != nullptr) {
            ZSTD_freeCCtx(m_cctx);
        }
    }

    bool Begin(u64 pledged_src_size) {
        if (m_cctx == nullptr) {
            m_cctx = ZSTD_createCCtx();
            if (m_cctx == nullptr) {
                return false;
            }
        }
        ZSTD_CCtx_reset(m_cctx, ZSTD_reset_session_only);
        ZSTD_CCtx_setParameter(m_cctx, ZSTD_c_compressionLevel, kZstdLevel);
        ZSTD_CCtx_setParameter(m_cctx, ZSTD_c_checksumFlag, 1);
        if (ZSTD_isError(ZSTD_CCtx_setPledgedSrcSize(m_cctx, pledged_src_size))) {
            return false;
        }
        m_written = 0;
        return true;
    }

    bool Write(const void* data, size_t len) {
        ZSTD_inBuffer in{data, len, 0};
        while (in.pos < in.size) {
            ZSTD_outBuffer out{m_buf.data(), m_buf.size(), 0};
            const size_t r = ZSTD_compressStream2(m_cctx, &out, &in, ZSTD_e_continue);
            if (ZSTD_isError(r)) {
                return false;
            }
            if (!WriteOut(out.pos)) {
                return false;
            }
        }
        return true;
    }

    bool Finish() {
        ZSTD_inBuffer in{nullptr, 0, 0};
        for (;;) {
            ZSTD_outBuffer out{m_buf.data(), m_buf.size(), 0};
            const size_t r = ZSTD_compressStream2(m_cctx, &out, &in, ZSTD_e_end);
            if (ZSTD_isError(r)) {
                return false;
            }
            if (!WriteOut(out.pos)) {
                return false;
            }
            if (r == 0) {
                return true; // 帧完成、输入全部消费（pledged 校验通过）
            }
        }
    }

    u64 written() const {
        return m_written;
    }

private:
    bool WriteOut(size_t bytes) {
        if (bytes == 0) {
            return true;
        }
        m_out.write(reinterpret_cast<const char*>(m_buf.data()),
                    static_cast<std::streamsize>(bytes));
        if (!m_out) {
            return false;
        }
        m_written += bytes;
        return true;
    }

    std::ofstream& m_out;
    ZSTD_CCtx* m_cctx{};
    std::vector<u8> m_buf;
    u64 m_written{};
};

// 预校验单帧：流式解码并丢弃输出，要求帧完整、输入精确消费、解码量
// 与记录的 uncompressed 精确一致（Cemu ValidateCompressedRange 同款）。
// 帧校验和存在时由 zstd 自动核对。
bool ValidateFrame(std::ifstream& in, u64 uncompressed, u64 compressed) {
    ZSTD_DCtx* dctx = ZSTD_createDCtx();
    if (dctx == nullptr) {
        return false;
    }
    std::vector<u8> inBuf(kZstdInChunk);
    std::vector<u8> discard(kZstdOutChunk);
    u64 inRemaining = compressed;
    u64 outTotal = 0;
    bool frameDone = false;
    bool ok = false;
    while (!frameDone && inRemaining > 0) {
        const size_t toRead = static_cast<size_t>(std::min<u64>(kZstdInChunk, inRemaining));
        in.read(reinterpret_cast<char*>(inBuf.data()),
                static_cast<std::streamsize>(toRead));
        if (static_cast<size_t>(in.gcount()) != toRead) {
            goto finish;
        }
        inRemaining -= toRead;
        ZSTD_inBuffer inDesc{inBuf.data(), toRead, 0};
        while (inDesc.pos < inDesc.size) {
            ZSTD_outBuffer outDesc{discard.data(), discard.size(), 0};
            const size_t r = ZSTD_decompressStream(dctx, &outDesc, &inDesc);
            if (ZSTD_isError(r)) {
                goto finish;
            }
            outTotal += outDesc.pos;
            if (outTotal > uncompressed) {
                goto finish; // 解码量超过记录范围
            }
            if (r == 0) {
                frameDone = true;
                break;
            }
        }
    }
    ok = frameDone && inRemaining == 0 && outTotal == uncompressed;
finish:
    ZSTD_freeDCtx(dctx);
    return ok;
}

// 应用单帧到 guest 内存：流式解压（有界 1MB 出块，永不整帧入堆），
// 每块按 host 连续后备 run 合并拷贝 + run 粒度 icache 刷新（NCE 从
// guest 页直接执行代码；dynarmic 仅作数据，刷新无害）。
bool ApplyFrame(std::ifstream& in, Core::System& system, u64 va, u64 uncompressed,
                u64 compressed, std::string* err) {
    *err = {};
    Core::Memory::Memory& memory = system.ApplicationMemory();

    ZSTD_DCtx* dctx = ZSTD_createDCtx();
    if (dctx == nullptr) {
        *err = "zstd dctx alloc failed";
        return false;
    }
    std::vector<u8> inBuf(kZstdInChunk);
    std::vector<u8> outBuf(kZstdOutChunk);
    u64 inRemaining = compressed;
    u64 outTotal = 0;
    bool frameDone = false;
    bool ok = false;
    while (!frameDone && inRemaining > 0) {
        const size_t toRead = static_cast<size_t>(std::min<u64>(kZstdInChunk, inRemaining));
        in.read(reinterpret_cast<char*>(inBuf.data()),
                static_cast<std::streamsize>(toRead));
        if (static_cast<size_t>(in.gcount()) != toRead) {
            *err = fmt::format("payload read failed @ {:016x}", va + outTotal);
            goto finish;
        }
        inRemaining -= toRead;
        ZSTD_inBuffer inDesc{inBuf.data(), toRead, 0};
        while (inDesc.pos < inDesc.size) {
            ZSTD_outBuffer outDesc{outBuf.data(), outBuf.size(), 0};
            const size_t r = ZSTD_decompressStream(dctx, &outDesc, &inDesc);
            if (ZSTD_isError(r)) {
                *err = fmt::format("zstd decode error @ {:016x}: {}", va + outTotal,
                                   ZSTD_getErrorName(r));
                goto finish;
            }
            if (outDesc.pos > 0) {
                // 越界保护：解码量不得超过该帧记录的未压缩大小
                if (outTotal + outDesc.pos > uncompressed) {
                    *err = fmt::format("frame overruns range @ {:016x}", va);
                    goto finish;
                }
                const u64 chunk_va = va + outTotal;
                const u8* src = outBuf.data();
                size_t off = 0;
                while (off < outDesc.pos) {
                    const u64 page_va = chunk_va + off;
                    const size_t in_page = kPageSize - (page_va % kPageSize);
                    const size_t take = std::min(in_page, outDesc.pos - off);
                    u8* dst = PagePtr(memory, page_va);
                    if (dst == nullptr) {
                        // 布局一致校验后不应出现；容错跳过（保持 v2 语义）
                        off += take;
                        continue;
                    }
                    if (take == kPageSize) {
                        // host 连续 run 合并：单次 memcpy + 单次 icache 刷新
                        size_t run = kPageSize;
                        while (off + run + kPageSize <= outDesc.pos) {
                            u8* const next = PagePtr(memory, page_va + run);
                            if (next != dst + run) {
                                break;
                            }
                            run += kPageSize;
                        }
                        std::memcpy(dst, src + off, run);
                        __builtin___clear_cache(reinterpret_cast<char*>(dst),
                                                reinterpret_cast<char*>(dst + run));
                        off += run;
                    } else {
                        std::memcpy(dst, src + off, take);
                        __builtin___clear_cache(reinterpret_cast<char*>(dst),
                                                reinterpret_cast<char*>(dst + take));
                        off += take;
                    }
                }
                outTotal += outDesc.pos;
            }
            if (r == 0) {
                frameDone = true;
                break;
            }
        }
    }
    ok = frameDone && inRemaining == 0 && outTotal == uncompressed;
    if (!ok && err->empty()) {
        *err = fmt::format("frame size mismatch @ {:016x}", va);
    }
finish:
    ZSTD_freeDCtx(dctx);
    return ok;
}

// ---------------------------------------------------------------------------
// state.bin —— 捕获（quickSaveSlot 调用，必须持冻结）
// ---------------------------------------------------------------------------

bool CaptureStateBin(Core::System& system, const fs::path& state_path, u64 program_id,
                     std::string* err) {
    *err = {};
    const auto t0 = NowSteady();

    Kernel::KProcess* process = system.ApplicationProcess();
    if (process == nullptr) {
        *err = "application process is null";
        return false;
    }

    // 1) 归一化布局收集 + 摘要
    std::vector<LayoutBlock> blocks;
    if (!CollectLayoutBlocks(system, blocks)) {
        *err = "layout walk failed";
        return false;
    }
    const u64 digest = LayoutDigest(blocks);

    // 2) 内容段 = FlagMapped 归一块
    std::vector<LayoutBlock> content_blocks;
    u64 total_uncompressed = 0;
    for (const auto& b : blocks) {
        if (IsMappedState(b.state)) {
            content_blocks.push_back(b);
            total_uncompressed += b.size;
        }
    }
    if (content_blocks.empty()) {
        *err = "no mapped regions found";
        return false;
    }

    Core::Memory::Memory& memory = system.ApplicationMemory();

    // 3) nvdrv 段 + 线程记录（先备好，布局表/内容表/帧一次顺序写出）
    std::vector<u8> nvdrv_blob;
    const bool nvdrv_ok = CaptureNvdrvState(system, nvdrv_blob);
    if (!nvdrv_ok) {
        SSLogWarn("capture: nvdrv GPU state unavailable, in-place restore disabled for this slot");
    }

    std::vector<u8> thread_blob;
    u32 thread_count = 0;
    for (auto& thread : process->GetThreadList()) {
        if (thread.IsDummyThread()) {
            continue; // HLE 宿主辅助线程，无 guest 寄存器语义
        }
        ThreadMetaV2 meta{};
        meta.thread_id = thread.GetThreadId();
        meta.tls_addr = GetInteger(thread.GetTlsAddress());
        meta.raw_state = static_cast<u32>(thread.GetRawState() &
                                          ~Kernel::ThreadState::SuspendFlagMask);
        meta.active_core = thread.GetActiveCore();
        meta.priority = thread.GetPriority();
        meta.reserved = 0;

        const Kernel::Svc::ThreadContext& ctx = thread.GetContext();
        thread_blob.insert(thread_blob.end(), reinterpret_cast<const u8*>(&meta),
                           reinterpret_cast<const u8*>(&meta) + sizeof(meta));
        thread_blob.insert(thread_blob.end(), reinterpret_cast<const u8*>(&ctx),
                           reinterpret_cast<const u8*>(&ctx) + sizeof(ctx));
        ++thread_count;
    }

    // 4) 顺序写 state.tmp：header → 块表 → 内容表（占位）→ zstd 帧 →
    //    nvdrv → 线程 → 回填内容表 compressed 字段 → 原子改名。
    //    （v3 的「负载临时文件 + 16MB 步长合并拷贝」双倍 I/O 已移除。）
    const fs::path state_tmp = state_path.string() + ".tmp";
    std::vector<u64> compressed_sizes;
    compressed_sizes.reserve(content_blocks.size());

    {
        std::ofstream out(state_tmp, std::ios::binary | std::ios::trunc);
        if (!out) {
            *err = "failed to open state.bin temp file";
            return false;
        }

        StateHeaderV4 header{};
        std::memcpy(header.magic, kStateMagic, sizeof(kStateMagic));
        header.version = kStateVersion;
        header.program_id = program_id;
        header.timestamp = static_cast<u64>(std::time(nullptr));
        header.block_count = static_cast<u32>(blocks.size());
        header.content_count = static_cast<u32>(content_blocks.size());
        header.thread_count = thread_count;
        header.flags = nvdrv_ok ? kStateFlagHasNvdrv : 0;
        header.total_uncompressed = total_uncompressed;
        header.nvdrv_size = nvdrv_blob.size();
        header.layout_digest = digest;

        out.write(reinterpret_cast<const char*>(&header), sizeof(header));
        if (!out) {
            *err = "state.bin header write failed";
            fs::remove(state_tmp);
            return false;
        }

        std::vector<BlockDescV4> block_descs;
        block_descs.reserve(blocks.size());
        for (const auto& b : blocks) {
            block_descs.push_back({b.va, b.size, b.state, b.perm, b.attr, 0});
        }
        out.write(reinterpret_cast<const char*>(block_descs.data()),
                  static_cast<std::streamsize>(block_descs.size() * sizeof(BlockDescV4)));
        if (!out) {
            *err = "state.bin block table write failed";
            fs::remove(state_tmp);
            return false;
        }

        const std::streamoff content_table_pos = out.tellp();
        std::vector<ContentDescV4> content_descs;
        content_descs.reserve(content_blocks.size());
        for (const auto& b : content_blocks) {
            content_descs.push_back({b.va, b.size, 0, 0, 0}); // compressed 占位
        }
        out.write(reinterpret_cast<const char*>(content_descs.data()),
                  static_cast<std::streamsize>(content_descs.size() * sizeof(ContentDescV4)));
        if (!out) {
            *err = "state.bin content table write failed";
            fs::remove(state_tmp);
            return false;
        }

        // 5) 逐块流式压缩：源 = guest 连续后备 run（直读直压）
        ZstdFrameWriter writer(out);
        static const std::array<u8, kPageSize> zero_page{};
        u64 reported = 0;
        for (size_t ci = 0; ci < content_blocks.size(); ++ci) {
            const auto& b = content_blocks[ci];
            if (!writer.Begin(b.size)) {
                *err = fmt::format("zstd cctx failed @ {:016x}", b.va);
                fs::remove(state_tmp);
                return false;
            }
            u64 off = 0;
            while (off < b.size) {
                const u64 page_va = b.va + off;
                const size_t in_page = kPageSize - (page_va % kPageSize);
                const size_t take = std::min(in_page, b.size - off);
                u8* p = PagePtr(memory, page_va);
                if (p == nullptr) {
                    // 无后备页（MMIO 等）：喂零页保持帧长一致
                    // （v2 的段尾截断语义升级为显式零填充）
                    if (!writer.Write(zero_page.data(), take)) {
                        *err = fmt::format("zstd feed failed @ {:016x}", page_va);
                        fs::remove(state_tmp);
                        return false;
                    }
                    off += take;
                    continue;
                }
                size_t run = take;
                if (take == kPageSize) {
                    // host 连续后备 run 扩展（堆等大块通常连续）
                    while (off + run + kPageSize <= b.size) {
                        u8* const next = PagePtr(memory, b.va + off + run);
                        if (next != p + run) {
                            break;
                        }
                        run += kPageSize;
                    }
                }
                if (!writer.Write(p, run)) {
                    *err = fmt::format("zstd feed failed @ {:016x}", page_va);
                    fs::remove(state_tmp);
                    return false;
                }
                off += run;
            }
            if (!writer.Finish()) {
                *err = fmt::format("zstd frame finish failed @ {:016x}", b.va);
                fs::remove(state_tmp);
                return false;
            }
            compressed_sizes.push_back(writer.written());

            reported += b.size;
            if ((ci & 0xF) == 0xF || ci + 1 == content_blocks.size()) {
                SSLogInfo(fmt::format("capture: {}/{} blocks, {}/{} MB, {} ms",
                                      ci + 1, content_blocks.size(), reported >> 20,
                                      total_uncompressed >> 20, ElapsedMsSince(t0)));
            }
        }

        out.write(reinterpret_cast<const char*>(nvdrv_blob.data()),
                  static_cast<std::streamsize>(nvdrv_blob.size()));
        out.write(reinterpret_cast<const char*>(thread_blob.data()),
                  static_cast<std::streamsize>(thread_blob.size()));
        if (!out) {
            *err = "state.bin tail write failed (disk full?)";
            fs::remove(state_tmp);
            return false;
        }

        // 6) 回填内容表 compressed 字段（Cemu 占位符回填同款）
        {
            const std::streamoff table_base = content_table_pos;
            for (size_t i = 0; i < content_descs.size(); ++i) {
                const std::streamoff pos =
                    table_base + static_cast<std::streamoff>(i * sizeof(ContentDescV4)) +
                    static_cast<std::streamoff>(offsetof(ContentDescV4, compressed));
                out.seekp(pos);
                out.write(reinterpret_cast<const char*>(&compressed_sizes[i]), sizeof(u64));
            }
            if (!out) {
                *err = "state.bin descriptor patch failed";
                fs::remove(state_tmp);
                return false;
            }
        }
    }

    // 7) 原子替换（v2 兜底序列）
    std::error_code ec;
    fs::rename(state_tmp, state_path, ec);
    if (ec) {
        fs::remove(state_path, ec);
        ec.clear();
        fs::rename(state_tmp, state_path, ec);
        if (ec) {
            fs::remove(state_tmp, ec);
            *err = "failed to finalize state.bin";
            return false;
        }
    }

    SSLogInfo(fmt::format("capture: done, {} blocks / {} frames / {} threads / {:.1} MB disk, {} ms (rss {} MB)",
                          blocks.size(), content_blocks.size(), thread_count,
                          static_cast<double>(
                              [&] {
                                  u64 s = 0;
                                  for (u64 c : compressed_sizes) {
                                      s += c;
                                  }
                                  return s;
                              }()) / 1048576.0,
                          ElapsedMsSince(t0), CurrentRssMB()));
    return true;
}

// ---------------------------------------------------------------------------
// state.bin —— 原地恢复（quickRestoreSlot 调用，必须持冻结）
// ---------------------------------------------------------------------------

enum class RestoreOutcome {
    Ok,             // 原地恢复成功
    LayoutChanged,  // 内存布局与存档不一致 → 调用方走 legacy 重启路径
    ThreadsChanged, // 线程集与存档不一致 → 同上
    Error,          // 校验失败（内存未动，可安全回落/报错）
    Torn,           // 应用中途失败（内存已半恢复 → 必须关停核心）
};

RestoreOutcome RestoreStateBin(Core::System& system, const fs::path& state_path,
                               u64 program_id, std::string* err) {
    *err = {};
    const auto t0 = NowSteady();

    std::error_code ec;
    if (!fs::exists(state_path, ec)) {
        *err = "state.bin missing";
        return RestoreOutcome::Error;
    }
    const u64 file_size = fs::file_size(state_path, ec);
    if (ec || file_size < sizeof(StateHeaderV4)) {
        *err = "state.bin too small";
        return RestoreOutcome::Error;
    }

    std::ifstream in(state_path, std::ios::binary);
    if (!in) {
        *err = "failed to open state.bin";
        return RestoreOutcome::Error;
    }

    // ---- 1) 头部 ----
    StateHeaderV4 header{};
    in.read(reinterpret_cast<char*>(&header), sizeof(header));
    if (in.gcount() != static_cast<std::streamsize>(sizeof(header)) ||
        std::memcmp(header.magic, kStateMagic, sizeof(kStateMagic)) != 0) {
        *err = "bad state.bin magic";
        return RestoreOutcome::Error;
    }
    if (header.version != kStateVersion) {
        *err = fmt::format("unsupported state.bin version {} (re-save to upgrade)",
                           header.version);
        return RestoreOutcome::Error;
    }
    if (header.program_id != program_id) {
        *err = fmt::format("program id mismatch: slot {:016x} vs current {:016x}",
                           header.program_id, program_id);
        return RestoreOutcome::Error;
    }
    if ((header.flags & kStateFlagHasNvdrv) == 0 || header.nvdrv_size == 0) {
        // 无 nvdrv 段的槽位（保存时服务不可用）：宿主 GPU 状态无法与回滚
        // 内存同步，原地恢复必拒。重新存档即可。
        *err = "state.bin lacks nvdrv GPU state - re-save to upgrade";
        return RestoreOutcome::Error;
    }
    if (header.block_count == 0 || header.block_count > kMaxBlockCount ||
        header.content_count == 0 || header.content_count > header.block_count ||
        header.thread_count > kMaxThreadCount || header.nvdrv_size > kMaxNvdrvSize) {
        *err = "state.bin header sanity check failed";
        return RestoreOutcome::Error;
    }

    // ---- 2) 块表 / 内容表 ----
    std::vector<LayoutBlock> saved_blocks(header.block_count);
    {
        std::vector<BlockDescV4> descs(header.block_count);
        in.read(reinterpret_cast<char*>(descs.data()),
                static_cast<std::streamsize>(descs.size() * sizeof(BlockDescV4)));
        if (in.gcount() != static_cast<std::streamsize>(descs.size() * sizeof(BlockDescV4))) {
            *err = "state.bin block table truncated";
            return RestoreOutcome::Error;
        }
        for (size_t i = 0; i < descs.size(); ++i) {
            saved_blocks[i] = {descs[i].va, descs[i].size, descs[i].state, descs[i].perm,
                               descs[i].attr};
        }
    }

    std::vector<ContentDescV4> content_descs(header.content_count);
    in.read(reinterpret_cast<char*>(content_descs.data()),
            static_cast<std::streamsize>(content_descs.size() * sizeof(ContentDescV4)));
    if (in.gcount() !=
        static_cast<std::streamsize>(content_descs.size() * sizeof(ContentDescV4))) {
        *err = "state.bin content table truncated";
        return RestoreOutcome::Error;
    }

    const std::streamoff frames_start = in.tellg();
    u64 frames_bytes = 0;
    u64 total_uncompressed = 0;
    for (const auto& d : content_descs) {
        // zstd 最坏轻微膨胀；超过此界视为损坏
        if (d.compressed > d.uncompressed + (d.uncompressed >> 3) + (64ull << 20)) {
            *err = fmt::format("implausible compressed size @ {:016x}", d.va);
            return RestoreOutcome::Error;
        }
        frames_bytes += d.compressed;
        total_uncompressed += d.uncompressed;
    }
    if (total_uncompressed != header.total_uncompressed) {
        *err = "state.bin uncompressed total mismatch";
        return RestoreOutcome::Error;
    }

    const u64 nvdrv_off = static_cast<u64>(frames_start) + frames_bytes;
    const u64 thread_off = nvdrv_off + header.nvdrv_size;
    const u64 expected_end = thread_off +
                             static_cast<u64>(header.thread_count) * kThreadRecordSize;
    if (expected_end != file_size) {
        *err = fmt::format("state.bin size mismatch: expected {} bytes, file has {}",
                           expected_end, file_size);
        return RestoreOutcome::Error;
    }

    // ---- 3) 校验：布局（逐块全比较，权威） ----
    {
        std::vector<LayoutBlock> current_blocks;
        if (!CollectLayoutBlocks(system, current_blocks)) {
            *err = "current layout walk failed";
            return RestoreOutcome::Error;
        }
        if (LayoutDigest(current_blocks) != header.layout_digest || !SameLayout(current_blocks, saved_blocks)) {
            size_t cur_i = 0, save_i = 0;
            std::string first_diff;
            while (cur_i < current_blocks.size() || save_i < saved_blocks.size()) {
                const bool has_cur = cur_i < current_blocks.size();
                const bool has_save = save_i < saved_blocks.size();
                if (has_cur && has_save && current_blocks[cur_i].va == saved_blocks[save_i].va) {
                    if (current_blocks[cur_i].size != saved_blocks[save_i].size ||
                        current_blocks[cur_i].state != saved_blocks[save_i].state ||
                        current_blocks[cur_i].perm != saved_blocks[save_i].perm ||
                        current_blocks[cur_i].attr != saved_blocks[save_i].attr) {
                        first_diff = fmt::format("block {:016x} attrs differ",
                                                 current_blocks[cur_i].va);
                        break;
                    }
                    ++cur_i;
                    ++save_i;
                } else if (has_cur && (!has_save || current_blocks[cur_i].va < saved_blocks[save_i].va)) {
                    first_diff = fmt::format("mapped after save @ {:016x}+{:x}",
                                             current_blocks[cur_i].va,
                                             current_blocks[cur_i].size);
                    break;
                } else {
                    first_diff = fmt::format("missing after save @ {:016x}+{:x}",
                                             saved_blocks[save_i].va, saved_blocks[save_i].size);
                    break;
                }
            }
            SSLogWarn(fmt::format("restore: layout changed ({} saved / {} current blocks; {}) - falling back to legacy",
                                  saved_blocks.size(), current_blocks.size(),
                                  first_diff.empty() ? "count differs" : first_diff));
            *err = "memory layout changed since save";
            return RestoreOutcome::LayoutChanged;
        }
    }

    // ---- 4) 校验：线程集（全等） ----
    std::vector<u64> saved_thread_ids;
    saved_thread_ids.reserve(header.thread_count);
    {
        // 定位线程段（帧 + nvdrv 之后），整段读入（≤ 16384×0x340 ≈ 13MB，
        // 实际几十 KB）
        const size_t thread_bytes =
            static_cast<size_t>(header.thread_count) * kThreadRecordSize;
        std::vector<u8> thread_blob(thread_bytes);
        in.seekg(static_cast<std::streamoff>(thread_off), std::ios::beg);
        in.read(reinterpret_cast<char*>(thread_blob.data()),
                static_cast<std::streamsize>(thread_bytes));
        if (static_cast<size_t>(in.gcount()) != thread_bytes) {
            *err = "state.bin thread section truncated";
            return RestoreOutcome::Error;
        }
        for (u32 i = 0; i < header.thread_count; ++i) {
            ThreadMetaV2 meta{};
            std::memcpy(&meta, thread_blob.data() + i * kThreadRecordSize, sizeof(meta));
            saved_thread_ids.push_back(meta.thread_id);
        }
        std::sort(saved_thread_ids.begin(), saved_thread_ids.end());

        const std::vector<u64> current_ids = CollectLiveThreadIds(system);
        if (current_ids != saved_thread_ids) {
            SSLogWarn(fmt::format("restore: thread set changed ({} saved / {} live) - falling back to legacy",
                                  saved_thread_ids.size(), current_ids.size()));
            *err = "thread set changed since save";
            return RestoreOutcome::ThreadsChanged;
        }
        // 线程记录留在内存里，应用阶段直接写回（不再二次读盘）
        //（thread_blob 在下方应用阶段通过参数传递）
        // 恢复流指针到帧区
        in.clear();
        in.seekg(frames_start, std::ios::beg);

        // ---- 5) 校验：逐帧 zstd 流式解码丢弃（动内存前的最后把关） ----
        for (size_t i = 0; i < content_descs.size(); ++i) {
            const auto& d = content_descs[i];
            if (!ValidateFrame(in, d.uncompressed, d.compressed)) {
                *err = fmt::format("corrupt payload @ {:016x}", d.va);
                return RestoreOutcome::Error;
            }
            if ((i & 0xF) == 0xF || i + 1 == content_descs.size()) {
                SSLogInfo(fmt::format("restore: validated {}/{} frames, {} ms", i + 1,
                                      content_descs.size(), ElapsedMsSince(t0)));
            }
        }

        // ---- 应用阶段（到这里文件已全量校验，I/O 仍可能中途失败 → Torn） ----
        in.clear();
        in.seekg(frames_start, std::ios::beg);
        for (size_t i = 0; i < content_descs.size(); ++i) {
            const auto& d = content_descs[i];
            if (!ApplyFrame(in, system, d.va, d.uncompressed, d.compressed, err)) {
                return RestoreOutcome::Torn;
            }
            if ((i & 0xF) == 0xF || i + 1 == content_descs.size()) {
                SSLogInfo(fmt::format("restore: applied {}/{} frames, {} ms (rss {} MB)",
                                      i + 1, content_descs.size(), ElapsedMsSince(t0),
                                      CurrentRssMB()));
            }
        }

        // nvdrv GPU 状态回放：SMMU/nvmap 句柄表 + GMMU 映射（在 guest 内存
        // 写回之后、rasterizer 缓存失效之前）。
        {
            std::vector<u8> nvdrv_blob(static_cast<size_t>(header.nvdrv_size));
            in.clear();
            in.seekg(static_cast<std::streamoff>(nvdrv_off), std::ios::beg);
            in.read(reinterpret_cast<char*>(nvdrv_blob.data()),
                    static_cast<std::streamsize>(nvdrv_blob.size()));
            if (static_cast<size_t>(in.gcount()) != nvdrv_blob.size()) {
                *err = "nvdrv section truncated";
                return RestoreOutcome::Torn;
            }
            std::string nvdrv_err;
            if (!RestoreNvdrvState(system, nvdrv_blob, &nvdrv_err)) {
                *err = "nvdrv restore failed: " + nvdrv_err;
                return RestoreOutcome::Torn;
            }
        }

        // GPU 指令/数据缓存一致性：宿主纹理、缓冲、渲染目标缓存全部
        // 失效，从还原后的 guest 内存重建（InvalidateGPUCache 与
        // ReloadDiskShaderCache 同源语义；GMMU 清空重放亦已触发逐段丢弃）。
        if (auto* rasterizer = system.Renderer().ReadRasterizer(); rasterizer != nullptr) {
            rasterizer->InvalidateGPUCache();
            rasterizer->FlushCommands();
        }

        // ARM 指令缓存：逐核清空（NCE 指令跳板 / dynarmic 翻译缓存；
        // 页粒度 icache 已在 ApplyFrame 按 run 刷新）。
        if (Kernel::KProcess* process = system.ApplicationProcess(); process != nullptr) {
            for (std::size_t core = 0; core < Core::Hardware::NUM_CPU_CORES; ++core) {
                if (Core::ArmInterface* iface = process->GetArmInterface(core); iface != nullptr) {
                    iface->ClearInstructionCache();
                }
            }
        }

        // 线程寄存器组原地写回（按 thread_id 匹配；线程集已全等校验，
        // 每条记录必有目标）。挂起的线程解冻后被重新调度时经
        // KScheduler::Reload → LoadContext → SetContext 装载恢复后的
        // 寄存器组 —— 从存档时刻继续。
        {
            Kernel::KProcess* process = system.ApplicationProcess();
            if (process == nullptr) {
                *err = "application process is null";
                return RestoreOutcome::Torn;
            }
            Kernel::Svc::ThreadContext ctx_copy{};
            for (u32 i = 0; i < header.thread_count; ++i) {
                ThreadMetaV2 meta{};
                std::memcpy(&meta, thread_blob.data() + i * kThreadRecordSize, sizeof(meta));
                std::memcpy(&ctx_copy, thread_blob.data() + i * kThreadRecordSize + sizeof(meta),
                            sizeof(ctx_copy));

                for (auto& thread : process->GetThreadList()) {
                    if (thread.GetThreadId() != meta.thread_id || thread.IsDummyThread()) {
                        continue;
                    }
                    Kernel::Svc::ThreadContext& dst = thread.GetContext();
                    std::memcpy(&dst, &ctx_copy, sizeof(Kernel::Svc::ThreadContext));
                    break;
                }
            }
        }

        SSLogInfo(fmt::format("restore: done, {} frames / {} threads, {} ms (rss {} MB)",
                              content_descs.size(), header.thread_count,
                              ElapsedMsSince(t0), CurrentRssMB()));
        return RestoreOutcome::Ok;
    }
}

// ---------------------------------------------------------------------------
// 存档数据快照 / 还原（NAND，legacy 后备 —— 与 v1 一致）
// ---------------------------------------------------------------------------

bool SnapshotTitleSaveData(u64 program_id, const fs::path& slot_save_dir) {
    const auto dirs = CollectTitleSaveDirs(program_id);
    if (dirs.empty()) {
        return true; // 该作无 NAND 存档（如尚无游戏内存档）也是合法快照
    }

    std::error_code ec;
    fs::remove_all(slot_save_dir, ec);
    // 每条存档目录按「相对 nand 根」的相对路径镜像进槽（单/多账户、
    // account 新旧布局与 device 布局并存时逐条还原）
    for (const auto& dir : dirs) {
        if (!CopyDirectory(dir.source, slot_save_dir / dir.relative)) {
            return false;
        }
    }
    return true;
}

bool RestoreTitleSaveData(u64 program_id, const fs::path& slot_save_dir) {
    std::error_code ec;
    if (!fs::exists(slot_save_dir, ec)) {
        return false;
    }

    const fs::path nand = NandRoot();
    const fs::path slot_user_save = slot_save_dir / "user" / "save";
    if (!fs::exists(slot_user_save, ec)) {
        return false;
    }

    // ★ 精确移除：只删槽内快照覆盖到的「本作」存档目录（叶子 = titleDir），
    //   绝不按顶层空间目录整删 —— user/save/0000000000000000/ 与 account/
    //   下还有其它游戏的存档，整删会殃及。结构与 CollectTitleSaveDirs
    //   的布局规则一一对应（旧 account 三层 / 新 account 两层 / device 一层）。
    for (const auto& level1 : fs::directory_iterator{slot_user_save, ec}) {
        if (ec) {
            return false;
        }
        const std::string l1name = level1.path().filename().string();
        if (l1name == "account") {
            for (const auto& uuid_dir : fs::directory_iterator{level1.path(), ec}) {
                for (const auto& title_dir : fs::directory_iterator{uuid_dir.path(), ec}) {
                    fs::remove_all(nand / "user" / "save" / "account" /
                                       uuid_dir.path().filename() / title_dir.path().filename(),
                                   ec);
                    ec.clear();
                }
                ec.clear();
            }
        } else if (l1name == "device") {
            for (const auto& title_dir : fs::directory_iterator{level1.path(), ec}) {
                fs::remove_all(nand / "user" / "save" / "device" / title_dir.path().filename(),
                               ec);
                ec.clear();
            }
        } else {
            for (const auto& uid_dir : fs::directory_iterator{level1.path(), ec}) {
                for (const auto& title_dir : fs::directory_iterator{uid_dir.path(), ec}) {
                    fs::remove_all(nand / "user" / "save" / l1name /
                                       uid_dir.path().filename() / title_dir.path().filename(),
                                   ec);
                    ec.clear();
                }
                ec.clear();
            }
        }
        ec.clear();
    }
    ec.clear();

    // 槽内全量按相对路径还原（覆盖写）
    for (const auto& entry : fs::recursive_directory_iterator{
             slot_save_dir, fs::directory_options::skip_permission_denied, ec}) {
        if (ec) {
            return false;
        }
        const auto relative = fs::relative(entry.path(), slot_save_dir, ec);
        if (ec) {
            return false;
        }
        const auto target = nand / relative;
        if (entry.is_directory(ec)) {
            fs::create_directories(target, ec);
        } else {
            fs::create_directories(target.parent_path(), ec);
            fs::copy_file(entry.path(), target, fs::copy_options::overwrite_existing, ec);
        }
        if (ec) {
            return false;
        }
    }
    return true;
}

bool SlotHasSaveData(u64 program_id, int slot) {
    std::error_code ec;
    const fs::path dir = GetSlotSaveDir(program_id, slot);
    if (!fs::exists(dir, ec)) {
        return false;
    }
    // savedata 非空（至少一个文件）才算有数据
    for (const auto& entry : fs::recursive_directory_iterator{dir, ec}) {
        if (!entry.is_directory(ec)) {
            return true;
        }
    }
    return false;
}

} // namespace

// ===========================================================================
// JNI —— Java_org_yuzu_yuzu_1emu_NativeLibrary_{quickSaveSlot,
// quickRestoreSlot,getQuickSaveInfo,getQuickSaveThumbnail}
// （符号与 app 源码 vendored 契约 org.yuzu.yuzu_emu.NativeLibrary 绑定，
//  命名与 Cemu 的 info.cemu.cemu.nativeinterface.NativeQuickSave 同构）
// ===========================================================================

extern "C" [[maybe_unused]] JNIEXPORT jstring JNICALL
Java_org_yuzu_yuzu_1emu_NativeLibrary_getQuickSaveInfo([[maybe_unused]] JNIEnv* env,
                                                       [[maybe_unused]] jobject thiz) {
    try {
        const u64 program_id = IsTitleRunning() ? GetForegroundProgramId() : 0;
        if (program_id == 0) {
            return env->NewStringUTF(R"({"titleId":"","titleName":"","maxSlots":6,"slots":[]})");
        }

        const std::string title_name = GetForegroundTitleName();
        std::string json = fmt::format(
            R"({{"titleId":"{:016x}","titleName":"{}","maxSlots":{},"slots":[)",
            program_id, EscapeJsonString(title_name), kMaxSlots);

        for (int slot = 1; slot <= kMaxSlots; ++slot) {
            const fs::path slot_dir = GetSlotDir(program_id, slot);
            const fs::path state_path = GetSlotStatePath(program_id, slot);
            std::error_code ec;
            const bool has_state = fs::exists(state_path, ec);
            const bool has_save_data = SlotHasSaveData(program_id, slot);
            const bool exists = has_state || has_save_data;
            const std::string timestamp = exists ? ReadSlotInfoFile(slot_dir) : std::string();
            u64 size = 0;
            if (has_state) {
                size += fs::file_size(state_path, ec);
            }
            if (has_save_data) {
                size += GetDirectorySize(GetSlotSaveDir(program_id, slot));
            }
            const bool thumb = [&] {
                std::error_code ec2;
                return fs::exists(slot_dir / "thumb.tga", ec2);
            }();

            fmt::format_to(std::back_inserter(json),
                           R"({{"slot":{},"exists":{},"timestamp":"{}","sizeBytes":{},"hasState":{},"thumb":{}}}{})",
                           slot, exists ? "true" : "false", EscapeJsonString(timestamp), size,
                           has_state ? "true" : "false", thumb ? "true" : "false",
                           slot < kMaxSlots ? "," : "");
        }

        const fs::path backup_dir = GetSlotDir(program_id, kAutoBackupSlot);
        const bool backup_exists = SlotHasSaveData(program_id, kAutoBackupSlot);
        const std::string backup_timestamp =
            backup_exists ? ReadSlotInfoFile(backup_dir) : std::string();
        const bool backup_thumb = [&] {
            std::error_code ec2;
            return fs::exists(backup_dir / "thumb.tga", ec2);
        }();

        json += fmt::format(
            R"(],"autoBackup":{{"exists":{},"timestamp":"{}","thumb":{}}}}})",
            backup_exists ? "true" : "false", EscapeJsonString(backup_timestamp),
            backup_thumb ? "true" : "false");
        return env->NewStringUTF(json.c_str());
    } catch (...) {
        return env->NewStringUTF(R"({"titleId":"","titleName":"","maxSlots":6,"slots":[]})");
    }
}

extern "C" [[maybe_unused]] JNIEXPORT jint JNICALL
Java_org_yuzu_yuzu_1emu_NativeLibrary_quickSaveSlot([[maybe_unused]] JNIEnv* env,
                                                    [[maybe_unused]] jobject thiz, jint slot) {
    // ★ 进程级冻结串行化（Cemu v3.9 同款）：与读档/其它存档互斥
    std::lock_guard freeze_guard{FreezeOpMutex()};
    try {
        if (!IsTitleRunning()) {
            return native_savestate::RESULT_ERROR_NOT_RUNNING;
        }

        const u64 program_id = GetForegroundProgramId();
        if (program_id == 0) {
            return native_savestate::RESULT_ERROR_NOT_RUNNING;
        }

        const fs::path slot_dir = GetSlotDir(program_id, static_cast<int>(slot));
        std::error_code ec;
        fs::create_directories(slot_dir, ec);
        if (ec) {
            return native_savestate::RESULT_ERROR_IO;
        }

        Core::System& system = Session().System();
        const bool was_paused = system.IsPaused();

        // 截图必须在冻结前发起（挂起状态下无新帧合成，请求永不完成）
        if (!was_paused) {
            CaptureScreenshotToThumbnail(slot_dir / "thumb.tga");
        }

        // ★ 冻结 + 异步 GPU 排空 + 全量状态捕获（RAII 解冻）
        {
            EmulationFreeze freeze{system};

            // 在途 GPU 脏写回（宿主→guest）刷入 guest 内存后再捕获，
            // 保证 state.bin 与 NAND 快照视图一致（与 Cemu 同等语义）。
            if (auto* rasterizer = system.Renderer().ReadRasterizer(); rasterizer != nullptr) {
                rasterizer->FlushAll();
            }

            std::string state_err;
            const bool state_ok =
                CaptureStateBin(system, GetSlotStatePath(program_id, static_cast<int>(slot)),
                                program_id, &state_err);
            if (!state_ok) {
                LOG_ERROR(Frontend, "Eden savestate capture failed: {}", state_err);
                SSLogWarn(fmt::format("capture failed: {}", state_err));
                return native_savestate::RESULT_ERROR_IO;
            }

            // NAND 存档数据按标题快照（后备：模拟器重启后仍可用该槽）
            if (!SnapshotTitleSaveData(program_id, GetSlotSaveDir(program_id, static_cast<int>(slot)))) {
                // 仅影响后备路径；state.bin 已就位，不算失败
                LOG_WARNING(Frontend, "Eden savestate: NAND save copy failed (non-fatal)");
            }

            WriteSlotInfoFile(slot_dir);
        }

        // 归还空闲堆页（压缩缓冲与槽位拷贝的临时缓冲）
        TrimHeap();
        return native_savestate::RESULT_OK;
    } catch (...) {
        return native_savestate::RESULT_ERROR_IO;
    }
}

extern "C" [[maybe_unused]] JNIEXPORT jint JNICALL
Java_org_yuzu_yuzu_1emu_NativeLibrary_quickRestoreSlot([[maybe_unused]] JNIEnv* env,
                                                       [[maybe_unused]] jobject thiz,
                                                       jint slot, jint flags) {
    // ★ 进程级冻结串行化（Cemu v3.9 同款）
    std::lock_guard freeze_guard{FreezeOpMutex()};
    try {
        if (!IsTitleRunning()) {
            return native_savestate::RESULT_ERROR_NOT_RUNNING;
        }

        const u64 program_id = GetForegroundProgramId();
        if (program_id == 0) {
            return native_savestate::RESULT_ERROR_NOT_RUNNING;
        }

        const fs::path state_path = GetSlotStatePath(program_id, static_cast<int>(slot));
        const fs::path slot_save_dir = GetSlotSaveDir(program_id, static_cast<int>(slot));
        std::error_code ec;
        const bool has_state = fs::exists(state_path, ec);
        const bool has_save_data = SlotHasSaveData(program_id, static_cast<int>(slot));
        if (!has_state && !has_save_data) {
            return native_savestate::RESULT_ERROR_SLOT_EMPTY;
        }

        // ★★ 首选路径：state.bin 进程内原地恢复（= Cemu RestoreStateFromFile）
        //    游戏暂停 → 校验（布局/线程集/逐帧）→ 内存快照覆盖 → 从存档时刻
        //    继续 —— 完全不重启。布局或线程集已变化时自动回落 legacy 重启
        //    路径（RESULT_OK_LEGACY_RESTART），绝不带着分叉状态续跑。
        bool legacy_by_layout = false;
        if (has_state) {
            Core::System& system = Session().System();
            {
                EmulationFreeze freeze{system};
                std::string state_err;
                const RestoreOutcome outcome = RestoreStateBin(system, state_path, program_id,
                                                               &state_err);
                switch (outcome) {
                case RestoreOutcome::Ok:
                    TrimHeap();
                    return native_savestate::RESULT_OK_RESTORED_IN_PLACE;

                case RestoreOutcome::LayoutChanged:
                case RestoreOutcome::ThreadsChanged:
                    // 原地恢复不可行（宿主侧布局/线程集与存档分叉）：
                    // 自动回落 legacy —— 前端 relaunchGame() 重启加载。
                    legacy_by_layout = true;
                    break;

                case RestoreOutcome::Error:
                    // 校验失败，内存未动
                    LOG_ERROR(Frontend, "Eden savestate in-place restore rejected: {}",
                              state_err);
                    SSLogWarn(fmt::format("restore rejected: {}", state_err));
                    if ((flags & native_savestate::RESTORE_FLAG_ALLOW_LEGACY) == 0) {
                        // 不静默回落破坏性 legacy 路径 —— 如实报错
                        return native_savestate::RESULT_ERROR_STATE_RESTORE_FAILED;
                    }
                    // 安全模式：继续走下方 legacy 路径
                    break;

                case RestoreOutcome::Torn:
                    // 应用中途失败：内存已半恢复 —— 关停核心（不续跑撕裂状态）
                    LOG_ERROR(Frontend, "Eden savestate restore torn: {}", state_err);
                    SSLogWarn(fmt::format("restore torn (halting): {}", state_err));
                    freeze.HaltInsteadOfResume();
                    if ((flags & native_savestate::RESTORE_FLAG_ALLOW_LEGACY) == 0) {
                        return native_savestate::RESULT_ERROR_STATE_RESTORE_FAILED;
                    }
                    // legacy 会整体关停重写，撕裂状态被覆盖 —— 可继续
                    break;
                }
            } // freeze 析构：恢复运行（或 Torn 时关停）

            if (legacy_by_layout) {
                SSLogInfo("restore: falling back to legacy (restart) path");
            }
        }

        // ── legacy 路径（老槽位无 state.bin / 布局分叉自动回落 / 安全模式）──
        // 与 Cemu ShutdownTitle 同语义：先自动备份当前存档（autobackup 槽），
        // 停止运行中的游戏让游戏释放全部存档文件句柄，杜绝写回与还原拷贝
        // 竞争，随后写回 NAND，由前端 relaunchGame() 进程内重启游戏。
        if (!has_save_data) {
            // 有 state.bin 但原地恢复不可行且无 NAND 后备 —— 如实报恢复失败
            // （仅无任何数据的空槽才报槽位为空）
            return has_state ? native_savestate::RESULT_ERROR_STATE_RESTORE_FAILED
                             : native_savestate::RESULT_ERROR_SLOT_EMPTY;
        }

        // 覆盖前安全备份当前存档（Cemu autobackup 槽同款）
        if (!CollectTitleSaveDirs(program_id).empty()) {
            const fs::path backup_dir = GetSlotDir(program_id, kAutoBackupSlot);
            fs::remove_all(backup_dir);
            if (SnapshotTitleSaveData(program_id, GetSlotSaveDir(program_id, kAutoBackupSlot))) {
                WriteSlotInfoFile(backup_dir);
            }
        }

        // 停止运行中的游戏（= Cemu ShutdownTitle）
        Session().HaltEmulation();
        if (!Session().WaitForShutdown(std::chrono::seconds(10))) {
            return native_savestate::RESULT_ERROR_IO;
        }

        // 槽位存档数据写回 NAND
        if (!RestoreTitleSaveData(program_id, slot_save_dir)) {
            return native_savestate::RESULT_ERROR_IO;
        }

        TrimHeap();
        // RESULT_OK = legacy 语义（Cemu 同款）：存档数据已还原、游戏已
        // 关停，由前端 relaunchGame() 进程内重启游戏加载恢复的存档。
        // RESULT_OK_LEGACY_RESTART：布局分叉自动回落时的同路径返回码 ——
        // 前端据此提示「内存布局已变化，已重启游戏加载」而非误导性的
        // 原地恢复话术。
        return legacy_by_layout ? native_savestate::RESULT_OK_LEGACY_RESTART
                                : native_savestate::RESULT_OK;
    } catch (...) {
        return native_savestate::RESULT_ERROR_IO;
    }
}

extern "C" [[maybe_unused]] JNIEXPORT jobject JNICALL
Java_org_yuzu_yuzu_1emu_NativeLibrary_getQuickSaveThumbnail([[maybe_unused]] JNIEnv* env,
                                                            [[maybe_unused]] jobject thiz,
                                                            jint slot) {
    try {
        const u64 program_id = IsTitleRunning() ? GetForegroundProgramId() : 0;
        if (program_id == 0) {
            return nullptr;
        }

        const fs::path thumb_path =
            GetSlotDir(program_id, static_cast<int>(slot)) / "thumb.tga";
        std::error_code ec;
        if (!fs::exists(thumb_path, ec)) {
            return nullptr;
        }

        std::ifstream file(thumb_path, std::ios::binary);
        if (!file) {
            return nullptr;
        }
        std::vector<u8> data((std::istreambuf_iterator<char>(file)),
                             std::istreambuf_iterator<char>());
        if (data.size() < 18) {
            return nullptr;
        }

        // 最小 TGA 解析：仅未压缩真彩（写入端格式），支持上/下origin
        const u8 id_length = data[0];
        const u8 color_map_type = data[1];
        const u8 image_type = data[2];
        if (color_map_type != 0 || image_type != 2) {
            return nullptr;
        }
        const int width = data[12] | (data[13] << 8);
        const int height = data[14] | (data[15] << 8);
        const u8 bpp = data[16];
        const u8 descriptor = data[17];
        if (width <= 0 || height <= 0 || (bpp != 24 && bpp != 32)) {
            return nullptr;
        }
        const bool top_down = (descriptor & 0x20) != 0;

        const size_t pixel_offset = 18 + id_length;
        const size_t pixel_bytes = static_cast<size_t>(width) * height * (bpp / 8);
        if (data.size() < pixel_offset + pixel_bytes) {
            return nullptr;
        }

        const jintArray pixel_array = env->NewIntArray(width * height);
        if (pixel_array == nullptr) {
            return nullptr;
        }
        std::vector<jint> argb(static_cast<size_t>(width) * height);
        const u8* src = data.data() + pixel_offset;
        for (int y = 0; y < height; ++y) {
            const int dst_y = top_down ? y : (height - 1 - y);
            for (int x = 0; x < width; ++x) {
                const u8* px =
                    src + (static_cast<size_t>(y) * width + x) * (bpp / 8);
                argb[static_cast<size_t>(dst_y) * width + x] =
                    static_cast<jint>(0xFF000000u) | (static_cast<jint>(px[2]) << 16) | // R
                    (static_cast<jint>(px[1]) << 8) |                                    // G
                    static_cast<jint>(px[0]);                                            // B
            }
        }
        env->SetIntArrayRegion(pixel_array, 0, width * height, argb.data());

        const jclass bitmapClass = env->FindClass("android/graphics/Bitmap");
        if (bitmapClass == nullptr) {
            env->DeleteLocalRef(pixel_array);
            return nullptr;
        }
        const jmethodID createBitmapMID =
            env->GetStaticMethodID(bitmapClass, "createBitmap",
                                   "([IIILandroid/graphics/Bitmap$Config;)Landroid/graphics/Bitmap;");
        const jclass bitmapConfigClass = env->FindClass("android/graphics/Bitmap$Config");
        const jfieldID argbFieldID = env->GetStaticFieldID(
            bitmapConfigClass, "ARGB_8888", "Landroid/graphics/Bitmap$Config;");
        const jobject bitmapConfig = env->GetStaticObjectField(bitmapConfigClass, argbFieldID);
        const jobject bitmap = env->CallStaticObjectMethod(bitmapClass, createBitmapMID,
                                                           pixel_array, width, height, bitmapConfig);

        env->DeleteLocalRef(pixel_array);
        env->DeleteLocalRef(bitmapConfig);
        env->DeleteLocalRef(bitmapConfigClass);
        env->DeleteLocalRef(bitmapClass);
        return bitmap;
    } catch (...) {
        return nullptr;
    }
}
