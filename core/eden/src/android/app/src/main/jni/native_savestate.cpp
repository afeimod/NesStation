// SPDX-FileCopyrightText: Copyright 2026 NesStation / Eden Emulator Project
// SPDX-License-Identifier: GPL-3.0-or-later
//
// ★★★ NesStation 集成：Switch（Eden）核心即时存档 —— 原生实现 v2 ★★★
//
// v1：NAND 存档数据快照 + legacy（关停游戏→写回→前端重启）。
// v2：新增 state.bin 进程内全量状态捕获/原地恢复 —— 与 Cemu 的
//     NativeQuickSave.cpp（core/cemu/src/android/app/src/main/cpp/）
//     语义逐块对齐：
// v3：新增 nvdrv GPU 状态段（GMMU 映射 + nvmap 句柄表 + SMMU 设备地址
//     分配）。v2 只回滚 guest 内存，但 GPU 访问链是三层：
//       GPU VA →(GMMU，宿主 HLE)→ DAddr →(SMMU/nvmap，宿主 HLE)→ guest VA
//     后两层是宿主侧状态，不随 guest 内存回滚 —— 存档后游戏重映射 GPU
//     缓冲会导致恢复后按过期页表翻译地址 → blit/memcpy 野指针崩溃
//    （Redmi socrates 闪退栈：SoftwareBlitEngine::Blit→memcpy）。
//     v3 随档快照这两层、读档原地址回放，使全链路与 Cemu 的
//     RestoreStateFromFile 同等一致性；GMMU 全量清空+重放会逐设备
//     地址段触发 rasterizer 缓存丢弃（= Cemu queueSaveStateGpuSync(true)
//     「丢弃全部 GPU 缓存数据、从内存重新上传」同构）。
//
//   Cemu (Wii U)                        Eden (Switch / yuzu 系)
//   ─────────────────────────────      ─────────────────────────────────────
//   CafeSystem::IsTitleRunning()   →   EmulationSession::IsRunning()
//   g_renderer->RequestScreenshot  →   RendererBase::RequestScreenshot
//   CafeSaveState::EmulationFreeze →   本文件 EmulationFreeze（RAII）
//     （System 全量冻结）               ★ yuzu 系差异：冻结序列
//                                        System::Pause（挂起+等停稳）
//                                        + GPU().WaitForIdle（异步 GPU 排空）
//                                        + GPU().ObtainContext（独占图形上下文）
//   CaptureStateToFileLocked       →   CaptureStateBin（本文件）
//     state.bin（全量内存+线程）         ★ 页表 QueryInfo 遍历 + 4KB 页粒度
//                                        zstd 压缩 + 全线程寄存器组（0x320）
//   RestoreStateFromFile           →   RestoreStateBin（本文件，原地恢复）
//     （原地恢复，不重启）                ★ 写回内存 → InvalidateGPUCache
//                                        → 逐核 ClearInstructionCache + icache
//                                        刷新 → 写回线程寄存器组 → 解冻续跑
//   MLC usr/save 按标题拷贝         →   NAND user/save 按标题拷贝（后备）
//   CafeSystem::ShutdownTitle()    →   EmulationSession::HaltEmulation()
//                                        + WaitForShutdown()（legacy 路径）
//
// 状态一致性依据（已对照 Eden 内核源码逐行核对）：
//   · KernelCore::SuspendEmulation：挂起全部线程后自旋等待所有核心调度器
//     卸下 guest 线程 —— System::Pause() 返回即全部寄存器已停放进
//     KThread::m_thread_context（KScheduler::Unload → PhysicalCore::
//     SaveContext → interface->GetContext(thread->GetContext())）。
//   · 恢复只需写回 m_thread_context：线程重回核心时 KScheduler::Reload →
//     PhysicalCore::LoadContext → interface->SetContext(...)（dynarmic 与
//     NCE 实现同一契约）。
//   · GPU 线程异步执行命令表：存档前 WaitForIdle 投递同步栅栏并等待排空
//     （与树内 ReloadDiskShaderCache 同步序列同源）；再 FlushAll 把在途
//     宿主→guest 脏写回刷入 guest 内存，捕获视图一致。
//   · 恢复写回后 InvalidateGPUCache：纹理/缓冲宿主缓存全部失效，从还原
//     后的 guest 内存重建（GPU 缓存是 guest 内存的派生视图）。
//   · HLE 服务（vsync/音频/HID）运行在宿主真实时间轴：原地恢复后自然
//     继续触发事件，唤醒（恢复上下文的）等待线程。

#include "native_savestate.h"

#include "native.h"

#include "common/common_funcs.h"
#include "common/fs/path_util.h"
#include "common/logging.h"
#include "common/zstd_compression.h"
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

#include <dlfcn.h> // dlsym: malloc_trim 运行时解析（见 TrimHeap）

#include <algorithm>
#include <chrono>
#include <cmath>
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
// state.bin v2 —— 进程内全量状态文件格式
//
//   [StateHeaderV2]
//   [RegionDescV2 × region_count]        （区段描述符数组）
//   [压缩负载 × region_count]              （逐区段 zstd 帧，顺序与描述符一致）
//   [线程记录 × thread_count]              （32B 元数据 + 0x320B 寄存器组）
//
//   区段来源：页表 QueryInfo 遍历，凡 KMemoryState::FlagMapped 且宿主
//   有后备页（GetPointerSilent 非空）皆捕获；超过 kChunkSize 的区段拆
//   成多个连续描述符。4KB 页粒度逐页拷贝（宿主后备页不保证连续）。
// ---------------------------------------------------------------------------

constexpr char kStateMagic[4] = {'E', 'D', 'S', 'S'};
constexpr u32 kStateVersion = 3;
constexpr size_t kChunkSize = 4 * 1024 * 1024; // 4MB 未压缩分片
constexpr size_t kPageSize = 4 * 1024;          // ≤ 宿主页表粒度，见下注

// 注：Common::PageTable::PageBits ≥ 12（4KB），kPageSize 逐页寻址总是
// 落在单个页表项内 —— 对更大页粒度的实现同样安全。

// v3：nvdrv GPU 状态段存在标志（GMMU 映射 + nvmap 句柄表随档保存）。
// 没有该段的 v2 存档原地恢复必拒（宿主 GPU 状态与回滚内存不一致 → 崩溃）。
constexpr u32 kStateFlagHasNvdrv = 1u << 0;

struct StateHeaderV2 {
    char magic[4];
    u32 version;
    u64 program_id;
    u32 region_count;
    u32 thread_count;
    u64 total_uncompressed; // 全部区段未压缩字节（统计/校验）
    u32 flags;              // bit0: kStateFlagHasNvdrv；其余保留
    u32 pad0;               // 对齐占位（保持 48B，与 v2 二进制兼容布局）
    u64 nvdrv_size;         // v3：nvdrv GPU 状态段字节数（无则 0）
};
static_assert(sizeof(StateHeaderV2) == 48);

struct RegionDescV2 {
    u64 guest_va;
    u64 size;              // 未压缩字节数（≤ kChunkSize）
    u32 mem_state;         // KMemoryState 原始值（诊断用）
    u32 mem_perm;          // KMemoryPermission 原始值（诊断用）
    u64 compressed_size;   // zstd 压缩后字节
    u64 uncompressed_size; // == size（冗余校验）
};
static_assert(sizeof(RegionDescV2) == 40);

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

// v3 nvdrv 段：
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
            if (!m_was_paused) {
                m_system.Run();
            }
        } catch (...) {
            // 析构不抛
        }
    }

    EmulationFreeze(const EmulationFreeze&) = delete;
    EmulationFreeze& operator=(const EmulationFreeze&) = delete;

private:
    Core::System& m_system;
    bool m_was_paused{};
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
// 原地恢复一致性。
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
// state.bin —— 捕获（quickSaveSlot 调用，必须持冻结）
// ---------------------------------------------------------------------------

// 页表遍历收集全部已映射区段（base/size 对），供捕获使用。
// 遍历方式 = svcQueryMemory 同源序列（KPageTableBase::QueryInfo），
// 从地址空间起点逐步推进直至地址回绕/越界。
bool CollectMappedRegions(Core::System& system, std::vector<std::pair<u64, u64>>& out) {
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

        const bool mapped =
            True(static_cast<Kernel::KMemoryState>(info.m_state) & Kernel::KMemoryState::FlagMapped);
        if (mapped) {
            // 区段起点可能低于 cur（块包含 cur），裁剪到未覆盖部分
            const u64 seg_start = std::max(cur, base);
            const u64 seg_end = base + size;
            if (seg_end > seg_start) {
                out.emplace_back(seg_start, seg_end - seg_start);
            }
        }

        const u64 next = base + size;
        if (next <= cur) {
            break; // 回绕/溢出防御
        }
        cur = next;
    }
    return !out.empty();
}

// 逐 4KB 页拷贝一段 guest 内存到 host 缓冲（拷贝端）。宿主后备页不保证
// 连续，必须逐页寻址；无后备页（MMIO 等）静默跳过。
// 返回实际拷贝的字节数（可能小于请求值 —— 尾部无后备页时截断）。
size_t CopyGuestRangeOut(Core::System& system, u64 va, size_t size, u8* dst) {
    Core::Memory::Memory& memory = system.ApplicationMemory();
    size_t done = 0;
    while (done < size) {
        const u64 page_va = va + done;
        const size_t in_page = kPageSize - (page_va % kPageSize);
        const size_t take = std::min(in_page, size - done);
        const u8* src = memory.GetPointerSilent(Common::ProcessAddress{page_va});
        if (src == nullptr) {
            break; // 无后备页：截断（诊断已由区段首页非空保证大概率整段可用）
        }
        std::memcpy(dst + done, src, take);
        done += take;
    }
    return done;
}

// 捕获 state.bin：header + 区段描述符 + zstd 压缩负载 + 线程记录。
// 写入走临时负载文件后合并（描述符在压缩完成后才知道大小）。
// 成功时返回 true；失败时 *err 填充诊断信息并清理临时文件。
bool CaptureStateBin(Core::System& system, const fs::path& state_path, u64 program_id,
                     std::string* err) {
    *err = {};

    Kernel::KProcess* process = system.ApplicationProcess();
    if (process == nullptr) {
        *err = "application process is null";
        return false;
    }

    // 1) 收集全部已映射区段
    std::vector<std::pair<u64, u64>> regions;
    if (!CollectMappedRegions(system, regions)) {
        *err = "no mapped regions found";
        return false;
    }

    // 2) 逐段逐片压缩到临时负载文件
    const fs::path payload_tmp = state_path.string() + ".payload.tmp";
    const fs::path state_tmp = state_path.string() + ".tmp";
    std::vector<RegionDescV2> descs;
    std::vector<u8> page_scratch(kChunkSize);

    {
        std::ofstream payload(payload_tmp, std::ios::binary | std::ios::trunc);
        if (!payload) {
            *err = "failed to open payload temp file";
            return false;
        }

        u64 total_uncompressed = 0;
        for (const auto& [va, size] : regions) {
            u64 off = 0;
            while (off < size) {
                const size_t take = static_cast<size_t>(std::min<u64>(kChunkSize, size - off));
                const size_t got = CopyGuestRangeOut(system, va + off, take, page_scratch.data());
                if (got != take) {
                    // 段尾出现无后备页（罕见）：按实际拷贝量截断该分片
                    if (got == 0) {
                        off += take;
                        continue;
                    }
                }
                const std::vector<u8> compressed =
                    Common::Compression::CompressDataZSTD(page_scratch.data(), got, 1);
                if (compressed.empty()) {
                    payload.close();
                    fs::remove(payload_tmp);
                    *err = fmt::format("zstd compress failed @ {:016x}", va + off);
                    return false;
                }
                payload.write(reinterpret_cast<const char*>(compressed.data()),
                               static_cast<std::streamsize>(compressed.size()));
                if (!payload) {
                    fs::remove(payload_tmp);
                    *err = "payload write failed";
                    return false;
                }

                RegionDescV2 desc{};
                desc.guest_va = va + off;
                desc.size = got;
                desc.mem_state = 0; // 诊断字段：块粒度状态不保留（分片跨块无意义）
                desc.mem_perm = 0;
                desc.compressed_size = compressed.size();
                desc.uncompressed_size = got;
                descs.push_back(desc);

                total_uncompressed += got;
                off += take;
            }
        }
        payload.close();

        // 3) 线程记录（挂起态：寄存器组已停放进 KThread::m_thread_context）
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

        // 3.5) nvdrv GPU 状态（v3）：GMMU 映射 + nvmap 句柄表（宿主侧 HLE
        //      派生状态，须随档保存；不可用时该槽将拒绝原地恢复）
        std::vector<u8> nvdrv_blob;
        const bool nvdrv_ok = CaptureNvdrvState(system, nvdrv_blob);
        if (!nvdrv_ok) {
            LOG_WARNING(Frontend,
                        "Eden savestate: nvdrv GPU state unavailable, "
                        "in-place restore disabled for this slot");
        }

        // 4) 合并写最终 state.bin（先写临时文件再原子替换）
        {
            std::ofstream out(state_tmp, std::ios::binary | std::ios::trunc);
            if (!out) {
                fs::remove(payload_tmp);
                *err = "failed to open state.bin temp file";
                return false;
            }

            StateHeaderV2 header{};
            std::memcpy(header.magic, kStateMagic, sizeof(kStateMagic));
            header.version = kStateVersion;
            header.program_id = program_id;
            header.region_count = static_cast<u32>(descs.size());
            header.thread_count = thread_count;
            header.total_uncompressed = total_uncompressed;
            header.flags = nvdrv_ok ? kStateFlagHasNvdrv : 0;
            header.pad0 = 0;
            header.nvdrv_size = nvdrv_blob.size();

            out.write(reinterpret_cast<const char*>(&header), sizeof(header));
            out.write(reinterpret_cast<const char*>(descs.data()),
                      static_cast<std::streamsize>(descs.size() * sizeof(RegionDescV2)));

            // 负载文件按 16MB 步长搬入（文件布局：header → 描述符 → 压缩负载
            // → 线程记录，与 RestoreStateBin 的读取顺序严格一致）
            std::ifstream pin(payload_tmp, std::ios::binary);
            if (!pin) {
                out.close();
                fs::remove(payload_tmp);
                fs::remove(state_tmp);
                *err = "failed to reopen payload temp file";
                return false;
            }
            std::vector<char> buf(16 * 1024 * 1024);
            while (pin) {
                pin.read(buf.data(), static_cast<std::streamsize>(buf.size()));
                const auto n = pin.gcount();
                if (n > 0) {
                    out.write(buf.data(), n);
                    if (!out) {
                        fs::remove(payload_tmp);
                        fs::remove(state_tmp);
                        *err = "state.bin write failed (disk full?)";
                        return false;
                    }
                }
            }

            // nvdrv 段（紧随压缩负载之后、线程记录之前；与 RestoreStateBin
            // 读取顺序严格一致）
            if (!nvdrv_blob.empty()) {
                out.write(reinterpret_cast<const char*>(nvdrv_blob.data()),
                          static_cast<std::streamsize>(nvdrv_blob.size()));
                if (!out) {
                    fs::remove(payload_tmp);
                    fs::remove(state_tmp);
                    *err = "state.bin write failed (disk full?)";
                    return false;
                }
            }

            // 线程记录位于全部压缩负载之后（与 RestoreStateBin 读取顺序一致）
            out.write(reinterpret_cast<const char*>(thread_blob.data()),
                      static_cast<std::streamsize>(thread_blob.size()));
            if (!out) {
                fs::remove(payload_tmp);
                fs::remove(state_tmp);
                *err = "state.bin write failed (disk full?)";
                return false;
            }
            out.close();
        }

        fs::remove(payload_tmp);
    }

    std::error_code ec;
    fs::rename(state_tmp, state_path, ec);
    if (ec) {
        // rename 失败兜底：删除旧 state 后移动
        fs::remove(state_path, ec);
        ec.clear();
        fs::rename(state_tmp, state_path, ec);
        if (ec) {
            fs::remove(state_tmp, ec);
            *err = "failed to finalize state.bin";
            return false;
        }
    }
    return true;
}

// ---------------------------------------------------------------------------
// state.bin —— 原地恢复（quickRestoreSlot 调用，必须持冻结）
// ---------------------------------------------------------------------------

// 逐 4KB 页写回一段 guest 内存（还原端）。写指令页后刷新 icache
// （NCE 直接从 guest 内存页执行代码；dynarmic 仅作数据，刷新无害）。
size_t CopyGuestRangeIn(Core::System& system, u64 va, size_t size, const u8* src) {
    Core::Memory::Memory& memory = system.ApplicationMemory();
    size_t done = 0;
    while (done < size) {
        const u64 page_va = va + done;
        const size_t in_page = kPageSize - (page_va % kPageSize);
        const size_t take = std::min(in_page, size - done);
        u8* dst = memory.GetPointerSilent(Common::ProcessAddress{page_va});
        if (dst == nullptr) {
            break; // 该页当前无映射/无后备（存档后有解映射）：截断
        }
        std::memcpy(dst, src + done, take);
        __builtin___clear_cache(reinterpret_cast<char*>(dst),
                                reinterpret_cast<char*>(dst + take));
        done += take;
    }
    return done;
}

bool RestoreStateBin(Core::System& system, const fs::path& state_path, u64 program_id,
                     std::string* err) {
    *err = {};

    std::error_code ec;
    if (!fs::exists(state_path, ec)) {
        *err = "state.bin missing";
        return false;
    }

    std::ifstream in(state_path, std::ios::binary);
    if (!in) {
        *err = "failed to open state.bin";
        return false;
    }

    // 1) 头部校验
    StateHeaderV2 header{};
    in.read(reinterpret_cast<char*>(&header), sizeof(header));
    if (in.gcount() != static_cast<std::streamsize>(sizeof(header)) ||
        std::memcmp(header.magic, kStateMagic, sizeof(kStateMagic)) != 0) {
        *err = "bad state.bin magic";
        return false;
    }
    if (header.version != kStateVersion) {
        *err = fmt::format("unsupported state.bin version {}", header.version);
        return false;
    }
    if (header.program_id != program_id) {
        *err = fmt::format("program id mismatch: slot {:016x} vs current {:016x}",
                           header.program_id, program_id);
        return false;
    }
    if ((header.flags & kStateFlagHasNvdrv) == 0 || header.nvdrv_size == 0) {
        // v2 老档 / nvdrv 段缺失：宿主 GPU 状态无法与回滚内存同步，原地恢复
        // 必拒（否则必崩）。重新存档即可得到 v3 槽位。
        *err = "state.bin lacks nvdrv GPU state (v2 slot?) - re-save to upgrade";
        return false;
    }

    // 2) 区段描述符
    std::vector<RegionDescV2> descs(header.region_count);
    if (!descs.empty()) {
        in.read(reinterpret_cast<char*>(descs.data()),
                static_cast<std::streamsize>(descs.size() * sizeof(RegionDescV2)));
        if (in.gcount() != static_cast<std::streamsize>(descs.size() * sizeof(RegionDescV2))) {
            *err = "state.bin descs truncated";
            return false;
        }
    }

    // 3) 逐分片解压写回 guest 内存
    {
        std::vector<u8> comp_scratch;
        for (const auto& desc : descs) {
            comp_scratch.resize(static_cast<size_t>(desc.compressed_size));
            in.read(reinterpret_cast<char*>(comp_scratch.data()),
                    static_cast<std::streamsize>(comp_scratch.size()));
            if (in.gcount() != static_cast<std::streamsize>(comp_scratch.size())) {
                *err = fmt::format("payload truncated @ {:016x}", desc.guest_va);
                return false;
            }

            const std::vector<u8> decompressed =
                Common::Compression::DecompressDataZSTD(std::span<const u8>{comp_scratch});
            if (decompressed.size() != desc.uncompressed_size) {
                *err = fmt::format("zstd size mismatch @ {:016x}", desc.guest_va);
                return false;
            }

            // 写回（只写当前已映射部分；尾部解映射页截断为可接受损失）
            if (desc.uncompressed_size > 0) {
                CopyGuestRangeIn(system, desc.guest_va, desc.uncompressed_size,
                                 decompressed.data());
            }
        }
    }

    // 3.5) nvdrv GPU 状态回放：SMMU/nvmap 句柄表 + GMMU 映射（在 guest
    //      内存写回之后、rasterizer 缓存失效之前）。GMMU 全量清空+重放
    //      本身就会按设备地址段触发 rasterizer 缓存丢弃 —— Cemu
    //      queueSaveStateGpuSync(true) 的「丢弃全部 GPU 缓存数据」同构。
    {
        std::vector<u8> nvdrv_blob(static_cast<size_t>(header.nvdrv_size));
        if (!nvdrv_blob.empty()) {
            in.read(reinterpret_cast<char*>(nvdrv_blob.data()),
                    static_cast<std::streamsize>(nvdrv_blob.size()));
            if (in.gcount() != static_cast<std::streamsize>(nvdrv_blob.size())) {
                *err = "nvdrv section truncated";
                return false;
            }
        }
        std::string nvdrv_err;
        if (!RestoreNvdrvState(system, nvdrv_blob, &nvdrv_err)) {
            *err = "nvdrv restore failed: " + nvdrv_err;
            return false;
        }
    }

    // 4) GPU 指令/数据缓存一致性：宿主纹理、缓冲、渲染目标缓存全部
    //    失效，从还原后的 guest 内存重建（GPU 缓存是 guest 内存的派生
    //    视图 —— InvalidateGPUCache 与 ReloadDiskShaderCache 同源语义）。
    if (auto* rasterizer = system.Renderer().ReadRasterizer(); rasterizer != nullptr) {
        rasterizer->InvalidateGPUCache();
        rasterizer->FlushCommands();
    }

    // 5) ARM 指令缓存：逐核清空（NCE 指令跳板 / dynarmic 翻译缓存）
    if (Kernel::KProcess* process = system.ApplicationProcess(); process != nullptr) {
        for (std::size_t core = 0; core < Core::Hardware::NUM_CPU_CORES; ++core) {
            if (Core::ArmInterface* iface = process->GetArmInterface(core); iface != nullptr) {
                iface->ClearInstructionCache();
            }
        }
    }

    // 6) 线程寄存器组原地写回（按 thread_id 匹配；不改动当前调度状态：
    //    挂起的线程解冻后被重新调度时经 KScheduler::Reload →
    //    LoadContext → SetContext 装载恢复后的寄存器组 —— 从存档时刻继续）
    {
        Kernel::KProcess* process = system.ApplicationProcess();
        if (process == nullptr) {
            *err = "application process is null";
            return false;
        }

        std::vector<u8> record(kThreadRecordSize);
        Kernel::Svc::ThreadContext ctx_copy{};
        for (u32 i = 0; i < header.thread_count; ++i) {
            in.read(reinterpret_cast<char*>(record.data()),
                    static_cast<std::streamsize>(record.size()));
            if (in.gcount() != static_cast<std::streamsize>(record.size())) {
                *err = fmt::format("thread record {} truncated", i);
                return false;
            }

            ThreadMetaV2 meta{};
            std::memcpy(&meta, record.data(), sizeof(meta));
            std::memcpy(&ctx_copy, record.data() + sizeof(meta), sizeof(ctx_copy));

            for (auto& thread : process->GetThreadList()) {
                if (thread.GetThreadId() != meta.thread_id || thread.IsDummyThread()) {
                    continue;
                }
                // 逐字段写回（GetContext 返回可写引用；v 寄存器数组直接拷贝）
                Kernel::Svc::ThreadContext& dst = thread.GetContext();
                std::memcpy(&dst, &ctx_copy, sizeof(Kernel::Svc::ThreadContext));
                break;
            }
        }
    }

    return true;
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
        //    游戏暂停 → 内存被快照覆盖 → 从快照时刻继续 —— 完全不重启。
        if (has_state) {
            Core::System& system = Session().System();
            {
                EmulationFreeze freeze{system};
                std::string state_err;
                if (!RestoreStateBin(system, state_path, program_id, &state_err)) {
                    LOG_ERROR(Frontend, "Eden savestate in-place restore failed: {}", state_err);
                    if ((flags & native_savestate::RESTORE_FLAG_ALLOW_LEGACY) == 0) {
                        // 不静默回落破坏性 legacy 路径 —— 如实报错
                        return native_savestate::RESULT_ERROR_STATE_RESTORE_FAILED;
                    }
                    // 安全模式：继续走下方 legacy 路径
                } else {
                    TrimHeap();
                    return native_savestate::RESULT_OK_RESTORED_IN_PLACE;
                }
            }
        }

        // ── legacy 路径（老槽位无 state.bin / 原地恢复失败且允许回落）──
        // 与 Cemu ShutdownTitle 同语义：先自动备份当前存档（autobackup 槽），
        // 停止运行中的游戏让游戏释放全部存档文件句柄，杜绝写回与还原拷贝
        // 竞争，随后写回 NAND，由前端 relaunchGame() 进程内重启游戏。
        if (!has_save_data) {
            return native_savestate::RESULT_ERROR_SLOT_EMPTY;
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
        return native_savestate::RESULT_OK;
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
