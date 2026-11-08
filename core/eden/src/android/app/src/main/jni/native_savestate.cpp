// SPDX-FileCopyrightText: Copyright 2026 NesStation / Eden Emulator Project
// SPDX-License-Identifier: GPL-3.0-or-later
//
// ★★★ NesStation 集成：Switch（Eden）核心即时存档 —— 原生实现 v7 ★★★
//
// v7（本轮）：按 Cemu 架构补齐 v5/v6 的两处根因缺陷 ——
//
//   ① 同会话读档必失败（用户报告「游戏内存档读取就提示失败」，logcat:
//      "restore torn (halting): unmap failed @ ... (state 077ebd05): 0d401"）
//      根因：nvmap::IocAlloc 对每次 nvmap 分配调用
//      LockForMapDeviceAddressSpace 给堆页置 DeviceShared 属性，而 HLE
//      nvdrv 只有 IocFree 的 can_unlock 分支对称清除 —— 存档时活跃的
//      nvmap 句柄（帧缓冲/纹理，几乎必然存在）使堆块在手术时仍带
//      DeviceShared；UnmapPages 的 CheckMemoryState 要求 attr==None
//      → 第一个 nvmap 堆块即 0xd401 InvalidCurrentMemory 撕裂。
//      v6 的 SavestateClearTransientLocks 只清 IpcLocked/Locked，漏了它。
//      修复：KMemoryBlock::SavestateResetForReplay（内核侧）一次性复位
//      属性位 + 锁/共享计数 + 禁合并位 + original_permission；本文件
//      手术入口改调 SavestatePrepareForReplay（只作用于即将整表卸载
//      重建的可回放块），并废除逐块 SetMemoryAttribute 清属性循环
//      （svc 路径状态位校验可能失败且无必要 —— 块随后即被卸载，属性由
//      重放阶段按存档原值恢复）。
//
//   ② 跨会话读档必重启（用户报告「重新进游戏后读取存档就提示重新启动」，
//      logcat: "precheck rejected (address space regions differ from save
//      (different session))"）根因：地址空间布局三处逐启动随机化 ——
//      KPageTableBase::InitializeForProcess 的四区域放置
//      （random_device 种子的 MT19937）、loader 的 aslr_offset
//      （Common::Random::Random64）、NCE 宿主预留基址
//      （HostMemory::ChooseVirtualBase）。三者全部确定性化（内核/加载器/
//      宿主三处配套修改）：同一游戏 + 同一配置 → 每次启动布局一致 =
//      Cemu「静态 MMURange」前提成立 → 旧会话槽位也能原地恢复。
//      真正不兼容（NCE 开关切换 / 旧版随机布局时代的槽位）仍回落
//      legacy 重启，不损数据。
//
//   ③ Cemu RestoreStateFromFile 的铁律「先全量校验、后应用」补齐：页组
//      预建（PrebuildSavedPageGroups）+ 布局冲突预检
//      （CheckLayoutCompatibility）全部提前到零副作用阶段 —— apply 阶段
//      只剩内核已验证的 UnmapPages / MapPageGroup 原语，可预见的 Torn
//      全部转化为干净报错。
//
// v5（历史）：完全按 Cemu（core/cemu/src/Cafe/Savestate/SaveState.cpp）的
//   读档架构重写 —— 核心变化是「布局比对门禁」换成「布局回放手术」：
//
//   Cemu 为什么从不提示"内存布局已变化"？因为 Wii U 的内存映射是启动时
//   固定的静态 MMURange 集：读档 = 按基址找当前区间、原地覆盖内容，
//   映射本身永远不需要动。Eden/Switch 的 KPageTable 布局是动态的（游戏
//   存档后继续分配/释放、disable-merge 标志漂移），v4 试图用「逐块全等
//   比对、不一致就回落重启」保证安全 —— 结果线上 100% 走重启路径
//   （1045/1023、95/89 块数漂移；845/845 时 block 0 属性差异也必然出现）。
//
//   v5 的 Cemu 等价翻译：**把"静态映射"这个前提造出来** —— 读档时用内核
//   自己的正规 API（KPageTableBase::UnmapPages / MapPageGroup /
//   SetMemoryPermission / SetMemoryAttribute，全部与游戏日常 syscall 同源、
//   经过整个内核验证的代码路径）把当前页表整表回放到存档时刻的布局：
//
//     ① nvmap Teardown：拆掉全部当前 GPU 设备映射（SMMU/GMMU/低位
//        pin；页表块的 DeviceShared 位由手术第 3 步
//        SavestatePrepareForReplay 一并复位 —— = Cemu 冻结 FSA IO
//        线程的等价物）
//     ② 预置引用：存档布局出现的所有物理页 SavestateAdjustRef(+1)
//        （MapPageGroup 的 Open 断言 ref>1 —— 内核约定"被映射的页必须
//        已被持有引用"；游戏释放过的页此刻 ref==0，预置绕过断言）
//     ③ SavestatePrepareForReplay：可回放块整表复位（属性位全部清零
//        含 DeviceShared + 锁/共享计数 + 禁合并位 —— v7 根因修复），
//        随后卸载当前全部可回放映射块（UnmapPages；宿主映射窗口外的
//        地址由 host_memory v5 窗口裁剪安全跳过 —— 修复读档/拆卸期
//        host_memory.cpp:770 断言闪退）
//     ④ 按存档原 VA + 原 PA 重放（MapPageGroup + 存档 perm/state，
//        别名关系/共享内存因 PA 精确而原样保留）+ Uncached/PermissionLocked
//        属性重放
//     ⑤ SavestateAdjustRef(-1) 归还预置 → SavestateRebuildFree 按
//        「引用计数==0 即空闲」重建空闲位图（分配器整表自洽）
//     ⑥ 记账回写（current_heap_end 等 = Cemu 的 sysAreaAllocatorOffset）
//     ⑦ 内容帧按 VA 写回（此刻映射已与存档逐块一致 —— Cemu 的
//        "内容原地覆盖"至此完全成立）
//     ⑧ nvmap Replay：按存档重建 SMMU/GMMU/低位 pin（指向⑦之后的新映射）
//     ⑨ rasterizer 缓存全失效 + icache 逐核清空（= Cemu 的
//        RequestGpuCacheReset / PPCRecompiler_invalidateRange）
//     ⑩ 线程寄存器组写回（System::Pause 已保证全部上下文停在
//        KThread::m_thread_context —— 等价于 Cemu 的
//        AreAllThreadContextsStored + 内存快照隐含保存）
//
//   v4 → v5 的其他修复：
//   · TLS 内容遗漏：KMemoryState::ThreadLocal 不带 FlagMapped 标志，
//     v4 的内容过滤器（FlagMapped）把 TLS 页排除在快照外 —— 读档后
//     线程本地存储是"当前时间线"的垃圾 → 闪退源之一。v5 内容过滤器
//     = FlagReferenceCounted || ThreadLocal。
//   · host_memory.cpp:770 断言：NCE 的宿主预留区只有 38 位（256GB），而
//     guest 地址空间 39 位；直接映射模式下 Map/Unmap/Protect 的外层
//     断言按"预留区内偏移"校验裸 guest VA，窗外高地址直接崩溃。v5 在
//     HostMemory 层做窗口裁剪（见 common/host_memory.cpp）。
//
//   与 Cemu 的对应关系总表：
//   ─────────────────────────────────────────────────────────────
//   Cemu 静态 MMURange（映射永不变）   →  v5 页表整表回放（把布局造回去）
//   Cemu 校验先行（zstd 逐帧解码丢弃） →  保留（ValidateFrame）
//   Cemu EmulationFreeze 协议          →  本文件 EmulationFreeze（RAII +
//        Pause → GPU WaitForIdle → ObtainContext）+ 进程级 FreezeOpMutex
//   Cemu sysAreaAllocatorOffset        →  SavestateBookkeeping（v5 新增）
//   Cemu FSA 文件句柄表                →  不做（HLE 句柄表跨时间线差异
//        以日志告警接受；nvmap 句柄表已完整快照/回放）
//   Cemu 调度器从内存重建              →  不适用（yuzu 系调度器状态在
//        KThread 宿主对象里，Pause/Run 周期天然保持自洽；仅回写寄存器组）
//   Cemu MADV_DONTNEED 零页弃置        →  不适用（memfd MAP_SHARED，
//        脏页在 page cache，madvise 不归零 —— v4 注释已论证，保留）
//
//   v1：NAND 存档数据快照 + legacy（关停游戏→写回→前端重启）（保留）
//   v2：state.bin 进程内全量状态捕获/原地恢复（保留）
//   v3：nvdrv GPU 状态段（保留；v5 拆 Teardown/Replay 夹住页表手术）
//   v4：流式 zstd / 布局门禁 / 线程集门禁（门禁废除，其余保留）
//   v5：页表手术 + PA 精确回放 + TLS 修复 + 窗口裁剪（保留）
//   v6：线程集差分重建 / 句柄回放 / 瞬态锁清位（保留；清位不完整 → v7）
//   v7：本文件 —— DeviceShared 根因修复 + 布局确定性化（跨会话原地
//       恢复）+ 物料预建/冲突预检（Cemu「先全量校验后应用」铁律）

#include "native_savestate.h"

#include "native.h"

#include "common/common_funcs.h"
#include "common/fs/path_util.h"
#include "common/logging.h"
#include "core/core.h"
#include "core/hardware_properties.h"
#include "core/hle/kernel/k_memory_block.h"
#include "core/hle/kernel/k_memory_manager.h"
#include "core/hle/kernel/k_page_group.h"
#include "core/hle/kernel/k_page_table.h"
#include "core/hle/kernel/k_page_table_base.h"
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
#include <type_traits>
#include <vector>

#include <fmt/format.h>

namespace fs = std::filesystem;

namespace {

using native_savestate::kAutoBackupSlot;
using native_savestate::kMaxSlots;

constexpr int kThumbMaxWidth = 320;
constexpr int kThumbMaxHeight = 180;

// ---------------------------------------------------------------------------
// state.bin v5 —— 进程内全量状态文件格式
//
//   [StateHeaderV5]
//   [BookkeepingV5]                    （页表动态记账 + 会话区域校验字段）
//   [BlockDescV5 × block_count]        （完整归一化布局：含未映射块）
//   [PARun 表 × block_count]           （每块 u32 run_count + PARunV5 × n；
//                                       仅 has_pg 块非零 —— VA→PA 回放脚本）
//   [ContentDescV5 × content_count]    （内容帧描述符；compressed 先占位后回填）
//   [zstd 帧 × content_count]          （逐块流式压缩，帧校验和 + pledged size）
//   [nvdrv 段（NvdrvHeaderV3…）]
//   [线程记录 × thread_count]          （32B 元数据 + 0x320B 寄存器组）
//
//   布局来源：页表 QueryInfo 遍历（svcQueryMemory 同源序列），相邻同
//   state/perm/attr 的块归一合并（快照/回放两端同一规则）。
//   内容块 = FlagReferenceCounted || ThreadLocal（v4 只看 FlagMapped，
//   漏掉 TLS —— v5 修复）。
// ---------------------------------------------------------------------------

constexpr char kStateMagic[4] = {'E', 'D', 'S', 'S'};
constexpr u32 kStateVersion = 6;
constexpr size_t kPageSize = 4 * 1024; // 4KB，≤ 宿主页表粒度

// v3/v4：nvdrv GPU 状态段存在标志（GMMU 映射 + nvmap 句柄表随档保存）。
// 没有该段的存档原地恢复必拒（宿主 GPU 状态与回滚内存不一致 → 崩溃）。
constexpr u32 kStateFlagHasNvdrv = 1u << 0;

// BlockDescV5.flags
constexpr u32 kBlockFlagHasPg = 1u << 0; // 有 PARun（可精确重映射）

// 校验上限（防损坏头/错位文件把分配器炸穿）
constexpr u32 kMaxBlockCount = 65536;
constexpr u32 kMaxThreadCount = 16384;
constexpr u64 kMaxNvdrvSize = 256ull * 1024 * 1024;
constexpr u32 kMaxPaRunTotal = 4u << 20; // 4M 条 PARun 记录封顶

struct StateHeaderV5 {
    char magic[4];
    u32 version;
    u64 program_id;
    u64 timestamp;           // time(nullptr)，诊断用
    u32 block_count;         // 归一化布局块数（全部，含未映射）
    u32 content_count;       // 内容帧数（= 可回放映射归一块数）
    u32 thread_count;
    u32 flags;               // bit0: kStateFlagHasNvdrv
    u64 total_uncompressed;  // 全部内容帧未压缩字节
    u64 nvdrv_size;
    u64 layout_digest;       // 归一化布局 FNV-1a（诊断/日志用，不再做门禁）
    u64 pa_run_total;        // v5: PARun 记录总数（校验用）
    u64 reserved[3];
};
static_assert(sizeof(StateHeaderV5) == 96);

// KPageTableBase::SavestateBookkeeping 的 POD 镜像（文件格式侧）
struct BookkeepingV5 {
    u64 current_heap_end;
    u64 heap_region_start, heap_region_end;
    u64 alias_region_start, alias_region_end;
    u64 stack_region_start, stack_region_end;
    u64 kernel_map_region_start, kernel_map_region_end;
    u64 code_region_start, code_region_end;
    u64 alias_code_region_start, alias_code_region_end;
    u64 address_space_start, address_space_end;
    u64 mapped_physical_memory_size;
    u64 mapped_unsafe_physical_memory;
    u64 mapped_insecure_memory;
    u64 mapped_ipc_server_memory;
    u64 alias_region_extra_size;
    u64 max_heap_size;
};
static_assert(sizeof(BookkeepingV5) ==
              sizeof(Kernel::KPageTableBase::SavestateBookkeeping));

struct BlockDescV5 {
    u64 va;
    u64 size;
    u32 state;    // KMemoryState 原始值
    u32 perm;     // KMemoryPermission 原始值
    u32 attr;     // KMemoryAttribute 原始值
    u32 flags;    // kBlockFlag*
};
static_assert(sizeof(BlockDescV5) == 32);

struct PARunV5 {
    u64 pa;     // guest 物理地址（DramMemoryMap::Base 起）
    u32 pages;  // 连续页数
    u32 reserved;
};
static_assert(sizeof(PARunV5) == 16);

struct ContentDescV5 {
    u64 va;
    u64 uncompressed; // 未压缩字节数（= 归一块大小）
    u64 compressed;   // zstd 帧字节数（写入时占位，帧完成后回填）
    u32 reserved0;
    u32 reserved1;
};
static_assert(sizeof(ContentDescV5) == 32);
static_assert(offsetof(ContentDescV5, compressed) == 16);

struct ThreadMetaV2 {
    u64 thread_id;
    u64 tls_addr;
    u64 virt_affinity_mask; // v6：重建线程的亲和掩码回写（调度正确性）
    u64 stack_top;          // v6：KThread::Initialize 的 user_stack_top
    u32 raw_state; // 已屏蔽 SuspendFlagMask 的 ThreadState
    u32 thread_handle; // v6：捕获时的句柄值（guest 互斥锁字/TCB 里存有句柄）
    s32 active_core;
    s32 priority;
    u32 reserved;
};
static_assert(sizeof(ThreadMetaV2) == 56);

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
//
// ★ v5 三段式（原 RestoreNvdrvState 一步执行 → 拆为 Parse / Teardown /
//   Replay）：页表手术必须插在 nvmap 拆除（SMMU/GMMU 设备映射拆除；
//   页表块的 DeviceShared 属性由 SavestatePrepareForReplay 复位）与
//   nvmap 重建（在新映射上重新 pin）之间 —— GMMU 部分是纯 GPUVA 侧、
//   与 CPU 页表无耦合，整体挪到 Replay 阶段执行。

struct NvdrvState {
    u32 next_handle_id = 0;
    struct GmmuMappings {
        u64 gmmu_id;
        std::vector<GpuMappingRecord> mappings;
    };
    std::vector<GmmuMappings> gmmu_sections;
    std::vector<NvdrvHandleRecord> handles;
};

bool ParseNvdrvState(std::span<const u8> blob, NvdrvState* out, std::string* err) {
    *err = {};
    out->gmmu_sections.clear();
    out->handles.clear();
    out->next_handle_id = 0;
    if (blob.size() < sizeof(NvdrvHeaderV3)) {
        *err = "nvdrv section truncated";
        return false;
    }

    NvdrvHeaderV3 header{};
    std::memcpy(&header, blob.data(), sizeof(header));
    blob = blob.subspan(sizeof(header));
    out->next_handle_id = header.next_handle_id;

    // 1) 各 GMMU 实例段
    out->gmmu_sections.resize(header.gmmu_count);
    for (auto& section_records : out->gmmu_sections) {
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
    out->handles.resize(header.handle_count);
    const size_t handles_bytes = out->handles.size() * sizeof(NvdrvHandleRecord);
    if (blob.size() < handles_bytes) {
        *err = "nvdrv handle records truncated";
        return false;
    }
    if (!out->handles.empty()) {
        std::memcpy(out->handles.data(), blob.data(), handles_bytes);
    }
    blob = blob.subspan(handles_bytes);
    return true;
}

// Teardown：拆除全部当前 nvmap 设备映射（SMMU 分配 / heap 映射 / 低位
// pin —— 归还设备地址；页表块的 DeviceShared 位由
// SavestatePrepareForReplay 复位），丢弃存档后新建的
// 句柄。必须在页表手术【前】调用。
void NvdrvTeardown(Core::System& system, const NvdrvState& state) {
    auto nvdrv = GetNvdrvModule(system);
    if (nvdrv == nullptr) {
        return;
    }
    auto& nvmap = nvdrv->GetContainer().GetNvMapFile();
    nvmap.RestoreHandlesTeardown(state.handles);
}

// Replay：GMMU 映射按原地址重放（全量清空 + 重放，清空即触发 rasterizer
// 缓存丢弃）+ nvmap 句柄表重建（SMMU/GMMU/低位 pin 指向手术后的新映射）。
// 必须在页表手术【后】调用。
void NvdrvReplay(Core::System& system, const NvdrvState& state) {
    auto nvdrv = GetNvdrvModule(system);
    if (nvdrv == nullptr) {
        return;
    }

    // 1) GMMU 映射：按存档 gmmu_id 匹配当前实例
    std::vector<GmmuInstanceRef> instances;
    CollectGmmuInstances(system, *nvdrv, instances);
    for (const auto& section_records : state.gmmu_sections) {
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

    // 2) nvmap 句柄表回放
    auto& nvmap = nvdrv->GetContainer().GetNvMapFile();
    nvmap.RestoreHandlesReplay(state.handles, state.next_handle_id);
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

bool IsContentState(u32 state) {
    // v5 内容/回放谓词：FlagReferenceCounted（内核可 Open/Close 引用的
    // 池内页 —— 堆/代码/栈/共享/别名/Transferred/GeneratedCode…），
    // 外加 ThreadLocal（v4 只看 FlagMapped，而 ThreadLocal 不带该标志，
    // TLS 页被整体排除在快照外 —— 读档后线程本地存储是当前时间线的
    // 垃圾，这是 v4 闪退源之一）。
    const auto s = static_cast<Kernel::KMemoryState>(state);
    return True(s & Kernel::KMemoryState::FlagReferenceCounted) ||
           s == Kernel::KMemoryState::ThreadLocal;
}

bool IsPgState(u32 state) {
    // 可经 MakeAndOpenPageGroup 抓 VA→PA 页组的状态（同上；ThreadLocal
    // 走逐页 GetPhysicalAddress，两者都算"可精确重映射"）
    return IsContentState(state);
}

bool IsThreadLocalState(u32 state) {
    return static_cast<Kernel::KMemoryState>(state) == Kernel::KMemoryState::ThreadLocal;
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

// v5：存档侧的块视图（布局 + PA 回放脚本）
struct SavedBlock {
    u64 va;
    u64 size;
    u32 state;
    u32 perm;
    u32 attr;
    bool has_pg;                                       // 有 PARun（可精确重映射）
    std::vector<std::pair<u64, u32>> runs;             // (pa, pages)，VA 顺序
};

// PA run 合并：把（可能跨块重叠的 —— 别名映射共享物理页）全部 run
// 合成不相交区间序列。预置/归还引用必须"每页恰好一次"。
std::vector<std::pair<u64, u64>> MergePaRuns(const std::vector<SavedBlock>& blocks) {
    std::vector<std::pair<u64, u64>> spans; // (pa_start, pa_end)
    spans.reserve(blocks.size() * 2);
    for (const auto& b : blocks) {
        if (!b.has_pg) {
            continue;
        }
        for (const auto& [pa, pages] : b.runs) {
            spans.emplace_back(pa, pa + static_cast<u64>(pages) * kPageSize);
        }
    }
    std::sort(spans.begin(), spans.end());
    std::vector<std::pair<u64, u64>> merged;
    merged.reserve(spans.size());
    for (const auto& [start, end] : spans) {
        if (!merged.empty() && start <= merged.back().second) {
            merged.back().second = std::max(merged.back().second, end);
        } else {
            merged.emplace_back(start, end);
        }
    }
    return merged;
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

    // ★ v5：确保应用进程是全局 current 页表 —— GetPointerSilent（内容读
    // 取）与宿主映射门控都走 current_page_table；中途创建的 applet 会把
    // current 翻走（KProcess::Initialize 每次进程创建都 Set —— 同一语义）
    system.ApplicationMemory().SetCurrentPageTable(*process);

    // 1) 归一化布局收集 + 摘要 + 页表记账（v5：current_heap_end 等，
    //    = Cemu 的 sysAreaAllocatorOffset —— 内核记账状态随档保存）
    std::vector<LayoutBlock> blocks;
    if (!CollectLayoutBlocks(system, blocks)) {
        *err = "layout walk failed";
        return false;
    }
    const u64 digest = LayoutDigest(blocks);

    Kernel::KProcessPageTable& page_table = process->GetPageTable();
    auto& kernel = system.Kernel();

    BookkeepingV5 bookkeeping{};
    {
        Kernel::KPageTableBase::SavestateBookkeeping bk{};
        page_table.GetBasePageTable().SavestateCaptureBookkeeping(bk);
        static_assert(std::is_trivially_copyable_v<decltype(bk)>);
        std::memcpy(&bookkeeping, &bk, sizeof(bookkeeping));
    }

    // 2) SavedBlock 视图：内容块 = IsContentState（v5：含 ThreadLocal）；
    //    逐块抓 VA→PA 回放脚本（FlagReferenceCounted → MakeAndOpenPageGroup；
    //    ThreadLocal 无该标志，逐页 GetPhysicalAddress —— 页同样出自池）
    std::vector<SavedBlock> saved_blocks;
    saved_blocks.reserve(blocks.size());
    u64 total_uncompressed = 0;
    u64 pa_run_total = 0;
    u32 content_count = 0;
    for (const auto& b : blocks) {
        SavedBlock sb{};
        sb.va = b.va;
        sb.size = b.size;
        sb.state = b.state;
        sb.perm = b.perm;
        sb.attr = b.attr;
        sb.has_pg = false;

        if (IsPgState(b.state) && b.size % kPageSize == 0) {
            const size_t npages = b.size / kPageSize;
            if (IsThreadLocalState(b.state)) {
                bool ok = true;
                for (size_t i = 0; i < npages; ++i) {
                    Kernel::KPhysicalAddress pa{};
                    if (!page_table.GetPhysicalAddress(&pa, sb.va + i * kPageSize)) {
                        ok = false;
                        break;
                    }
                    sb.runs.emplace_back(GetInteger(pa), 1);
                }
                if (ok) {
                    sb.has_pg = true;
                } else {
                    sb.runs.clear();
                    SSLogWarn(fmt::format(
                        "capture: TLS page translate failed @ {:016x} - block not replayable",
                        sb.va));
                }
            } else {
                Kernel::KPageGroup pg(kernel, page_table.GetBlockInfoManager());
                const Result pg_result = page_table.MakeAndOpenPageGroup(
                    &pg, sb.va, npages, Kernel::KMemoryState::All,
                    static_cast<Kernel::KMemoryState>(b.state),
                    Kernel::KMemoryPermission::None, Kernel::KMemoryPermission::None,
                    Kernel::KMemoryAttribute::None, Kernel::KMemoryAttribute::None);
                if (R_SUCCEEDED(pg_result)) {
                    for (const auto& node : pg) {
                        sb.runs.emplace_back(GetInteger(node.GetAddress()),
                                             static_cast<u32>(node.GetNumPages()));
                    }
                    pg.CloseAndReset(kernel); // 归还 MakeAndOpen 打开的引用
                    sb.has_pg = true;
                } else {
                    SSLogWarn(fmt::format(
                        "capture: page group failed @ {:016x} (state {:08x})",
                        sb.va, b.state));
                }
            }
        }

        if (IsContentState(b.state)) {
            if (!sb.has_pg) {
                // 内容块必须可精确重映射，否则该槽位无法原地恢复 ——
                // 干净失败（冻结中止，游戏继续），好过写出有缺陷的档
                *err = fmt::format("mapped block {:016x} has no capturable page group",
                                   sb.va);
                return false;
            }
            total_uncompressed += b.size;
            ++content_count;
        }
        if (sb.has_pg) {
            pa_run_total += sb.runs.size();
        }
        saved_blocks.push_back(std::move(sb));
    }
    if (content_count == 0) {
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
        meta.virt_affinity_mask = thread.GetVirtualAffinityMaskForSavestate();
        meta.stack_top = GetInteger(thread.GetUserStackTop());
        meta.raw_state = static_cast<u32>(thread.GetRawState() &
                                          ~Kernel::ThreadState::SuspendFlagMask);
        // v6：句柄值随档（guest 互斥锁所有者字、TCB 存有句柄值 —— 读档后
        // 句柄表必须与存档时刻一致，与 Cemu 恢复 SysAllocator 记账同构）
        meta.thread_handle =
            static_cast<u32>(process->GetHandleTable().SavestateFindHandle(&thread));
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

    // 4) 顺序写 state.tmp：header → 记账 → 块表 → PARun 表 → 内容表（占位）
    //    → zstd 帧 → nvdrv → 线程 → 回填内容表 compressed 字段 → 原子改名。
    //    （v3 的「负载临时文件 + 16MB 步长合并拷贝」双倍 I/O 已移除。）
    const fs::path state_tmp = state_path.string() + ".tmp";
    std::vector<u64> compressed_sizes;
    compressed_sizes.reserve(content_count);

    {
        std::ofstream out(state_tmp, std::ios::binary | std::ios::trunc);
        if (!out) {
            *err = "failed to open state.bin temp file";
            return false;
        }

        StateHeaderV5 header{};
        std::memcpy(header.magic, kStateMagic, sizeof(kStateMagic));
        header.version = kStateVersion;
        header.program_id = program_id;
        header.timestamp = static_cast<u64>(std::time(nullptr));
        header.block_count = static_cast<u32>(saved_blocks.size());
        header.content_count = content_count;
        header.thread_count = thread_count;
        header.flags = nvdrv_ok ? kStateFlagHasNvdrv : 0;
        header.total_uncompressed = total_uncompressed;
        header.nvdrv_size = nvdrv_blob.size();
        header.layout_digest = digest;
        header.pa_run_total = pa_run_total;

        out.write(reinterpret_cast<const char*>(&header), sizeof(header));
        if (!out) {
            *err = "state.bin header write failed";
            fs::remove(state_tmp);
            return false;
        }

        out.write(reinterpret_cast<const char*>(&bookkeeping), sizeof(bookkeeping));
        if (!out) {
            *err = "state.bin bookkeeping write failed";
            fs::remove(state_tmp);
            return false;
        }

        std::vector<BlockDescV5> block_descs;
        block_descs.reserve(saved_blocks.size());
        for (const auto& b : saved_blocks) {
            block_descs.push_back({b.va, b.size, b.state, b.perm, b.attr,
                                   b.has_pg ? kBlockFlagHasPg : 0u});
        }
        out.write(reinterpret_cast<const char*>(block_descs.data()),
                  static_cast<std::streamsize>(block_descs.size() * sizeof(BlockDescV5)));
        if (!out) {
            *err = "state.bin block table write failed";
            fs::remove(state_tmp);
            return false;
        }

        // v5：PARun 表（与块表同序；每块 u32 run_count + PARunV5×n）
        for (const auto& b : saved_blocks) {
            const u32 run_count = static_cast<u32>(b.runs.size());
            out.write(reinterpret_cast<const char*>(&run_count), sizeof(run_count));
            for (const auto& [pa, pages] : b.runs) {
                const PARunV5 run{pa, pages, 0};
                out.write(reinterpret_cast<const char*>(&run), sizeof(run));
            }
        }
        if (!out) {
            *err = "state.bin pa run table write failed";
            fs::remove(state_tmp);
            return false;
        }

        const std::streamoff content_table_pos = out.tellp();
        std::vector<ContentDescV5> content_descs;
        content_descs.reserve(content_count);
        for (const auto& b : saved_blocks) {
            if (!IsContentState(b.state)) {
                continue;
            }
            content_descs.push_back({b.va, b.size, 0, 0, 0}); // compressed 占位
        }
        out.write(reinterpret_cast<const char*>(content_descs.data()),
                  static_cast<std::streamsize>(content_descs.size() * sizeof(ContentDescV5)));
        if (!out) {
            *err = "state.bin content table write failed";
            fs::remove(state_tmp);
            return false;
        }

        // 5) 逐块流式压缩：源 = guest 连续后备 run（直读直压）
        ZstdFrameWriter writer(out);
        static const std::array<u8, kPageSize> zero_page{};
        u64 reported = 0;
        size_t ci = 0;
        for (const auto& b : saved_blocks) {
            if (!IsContentState(b.state)) {
                continue;
            }
            ++ci;
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
            if ((ci & 0xF) == 0xF || ci == content_count) {
                SSLogInfo(fmt::format("capture: {}/{} blocks, {}/{} MB, {} ms",
                                      ci, content_count, reported >> 20,
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
                    table_base + static_cast<std::streamoff>(i * sizeof(ContentDescV5)) +
                    static_cast<std::streamoff>(offsetof(ContentDescV5, compressed));
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

    SSLogInfo(fmt::format("capture: done, {} blocks / {} frames / {} pa-runs / {} threads / {:.1} MB disk, {} ms (rss {} MB)",
                          saved_blocks.size(), content_count, pa_run_total, thread_count,
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
// v5：页表整表回放手术（原地恢复的核心）
//
// Cemu 的读档之所以稳定：Wii U 的映射是静态 MMURange，读档只覆盖内容。
// Eden 的映射是动态的 —— v5 的思路是"把 Cemu 的静态前提造出来"：
// 读档时用内核正规 API（游戏 syscall 同源代码路径）把当前页表整表
// 回放到存档时刻的布局（原 VA + 原 PA + 原 state/perm/attr），然后
// 内容帧按 VA 原地覆盖 —— Cemu 语义至此完全成立。
//
// 引用计数协议：MapPageGroup 内部经 KScopedPageGroup 走 Open()，
// Open 断言 ref>1（内核约定"被映射的页必须已被持有引用"）。游戏在
// 存档后释放过的页此刻 ref==0 —— 手术前先对所有存档 PA 预置 +1
//（SavestateAdjustRef 绕过断言；冻结期间无并发分配，空闲位图暂时
// 不一致无妨），手术末尾 -1 归还，再按"引用计数==0 即空闲"整表重建
// 空闲位图（SavestateRebuildFree）。别名/共享内存因 PA 精确而原样
// 保留（多个 VA 映射同一 PA 的引用数自然正确）。
// ---------------------------------------------------------------------------

enum class SurgeryOutcome {
    Ok,
    Incompatible, // 会话不兼容（区域不一致 / 瞬态锁在途）—— 未做任何修改
    Torn,         // 破坏阶段失败（内核状态已改动 → 必须关停核心）
};

// 手术前置校验（无副作用）：会话区域一致（跨启动/配置的槽位拒绝）。
// v6：瞬态锁检查已废除 —— IpcLocked/Locked 是 IPC 在途的瞬态内核状态，
// 属于「已废弃时间线」；Cemu 对这类状态从不拒绝（丢弃排队事件回调同
// 语义）。手术入口的 SavestatePrepareForReplay 负责整表复位。
// v7：区域布局已确定性化（InitializeForProcess / loader aslr_offset /
// HostMemory::ChooseVirtualBase 三处配套修改）—— 同一游戏 + 同一配置
// 跨启动布局一致，旧会话槽位也能原地恢复；本检查命中仅剩真正不兼容
// 的场景（NCE 开关切换、旧版随机布局时代的槽位等）→ 干净回落 legacy。
bool SurgeryPrecheck(Core::System& system, const BookkeepingV5& bookkeeping, std::string* err) {
    *err = {};
    Kernel::KProcess* process = system.ApplicationProcess();
    if (process == nullptr) {
        *err = "application process is null";
        return false;
    }

    // 区域一致（= Cemu 的 "range not mapped / size changed" 校验 —— 静态
    // 区域跨启动不变，不一致说明是另一个会话的槽位）
    {
        BookkeepingV5 cur{};
        Kernel::KPageTableBase::SavestateBookkeeping bk{};
        process->GetPageTable().GetBasePageTable().SavestateCaptureBookkeeping(bk);
        std::memcpy(&cur, &bk, sizeof(cur));
        const bool same_regions =
            cur.heap_region_start == bookkeeping.heap_region_start &&
            cur.heap_region_end == bookkeeping.heap_region_end &&
            cur.alias_region_start == bookkeeping.alias_region_start &&
            cur.alias_region_end == bookkeeping.alias_region_end &&
            cur.stack_region_start == bookkeeping.stack_region_start &&
            cur.stack_region_end == bookkeeping.stack_region_end &&
            cur.kernel_map_region_start == bookkeeping.kernel_map_region_start &&
            cur.kernel_map_region_end == bookkeeping.kernel_map_region_end &&
            cur.code_region_start == bookkeeping.code_region_start &&
            cur.code_region_end == bookkeeping.code_region_end &&
            cur.alias_code_region_start == bookkeeping.alias_code_region_start &&
            cur.alias_code_region_end == bookkeeping.alias_code_region_end &&
            cur.address_space_start == bookkeeping.address_space_start &&
            cur.address_space_end == bookkeeping.address_space_end;
        if (!same_regions) {
            *err = "address space regions differ from save (different session)";
            return false;
        }
    }
    return true;
}

// ---------------------------------------------------------------------------
// v7：手术前置物料 —— 存档布局的页组预建 + 当前布局兼容性预检
//
// Cemu RestoreStateFromFile 的铁律：先把整份档解析、校验、逐 range 与
// 当前映射比对完（任何失败都在动内存之前发生，零副作用报错），然后才
// 进入 apply 阶段。v5/v6 把「页组重建」放在手术循环中段 —— AddBlock
// 失败（块信息 slab 枯竭）或页数不符会以 Torn（半撕裂）收场。v7 把全部
// 页组预先建好、全部可预见的布局冲突预先排除：apply 阶段只剩内核已
// 验证过的 UnmapPages / MapPageGroup 原语。
// ---------------------------------------------------------------------------

struct PrebuiltGroup {
    size_t block_index;                      // saved_blocks 下标
    std::unique_ptr<Kernel::KPageGroup> pg;  // AddBlock 预建（不持引用）
};

// 为存档全部可回放块预建页组（纯解析，无内核副作用：AddBlock 只从块
// 信息 slab 分配 KBlockInfo 节点，不触碰页表/引用计数）。失败返回 false
//（调用点位于校验阶段，内存未动 → 可安全报错/回落）。
bool PrebuildSavedPageGroups(Core::System& system, const std::vector<SavedBlock>& saved,
                             std::vector<PrebuiltGroup>* out, std::string* err) {
    *err = {};
    out->clear();

    Kernel::KProcess* process = system.ApplicationProcess();
    if (process == nullptr) {
        *err = "application process is null";
        return false;
    }
    Kernel::KProcessPageTable& page_table = process->GetPageTable();

    out->reserve(saved.size());
    for (size_t i = 0; i < saved.size(); ++i) {
        const auto& b = saved[i];
        if (!b.has_pg) {
            continue;
        }
        auto pg = std::make_unique<Kernel::KPageGroup>(system.Kernel(),
                                                       page_table.GetBlockInfoManager());
        bool pg_ok = true;
        for (const auto& [pa, pages] : b.runs) {
            if (R_FAILED(pg->AddBlock(pa, pages))) {
                pg_ok = false;
                break;
            }
        }
        if (!pg_ok || pg->GetNumPages() != b.size / kPageSize) {
            *err = fmt::format("page group rebuild failed @ {:016x}", b.va);
            out->clear(); // unique_ptr 析构即释放已建页组的块信息节点
            return false;
        }
        out->push_back({i, std::move(pg)});
    }
    SSLogInfo(fmt::format("precheck: {} page groups prebuilt", out->size()));
    return true;
}

// 布局兼容性预检（零副作用）：存档的可回放块不能与当前会话的不可回放
// 块（Io/Static 等手术不卸载的状态）重叠 —— MapPageGroup 要求目标区间
// 为 Free，重叠意味着 apply 阶段必然 Torn。同会话/确定性布局跨会话下
// 不可回放块的位置恒定，本检查正常情况下恒通过；命中即说明槽位来自布
// 局不兼容的会话（如 NCE 开关切换后），干净报错（可回落 legacy）。
bool CheckLayoutCompatibility(Core::System& system, const std::vector<SavedBlock>& saved,
                              std::string* err) {
    *err = {};

    std::vector<LayoutBlock> current;
    if (!CollectLayoutBlocks(system, current)) {
        *err = "current layout walk failed";
        return false;
    }

    // 当前会话的不可回放区间（手术保留不卸载的）
    std::vector<std::pair<u64, u64>> frozen;
    frozen.reserve(current.size());
    for (const auto& b : current) {
        if (!IsPgState(b.state)) {
            frozen.emplace_back(b.va, b.va + b.size);
        }
    }
    std::sort(frozen.begin(), frozen.end());

    for (const auto& b : saved) {
        if (!b.has_pg) {
            continue; // 存档的不可回放块与当前不可回放块同源（硬件固定区）
        }
        const u64 lo = b.va;
        const u64 hi = b.va + b.size;
        auto it = std::lower_bound(
            frozen.begin(), frozen.end(), lo,
            [](const std::pair<u64, u64>& span, u64 v) { return span.second <= v; });
        for (; it != frozen.end() && it->first < hi; ++it) {
            *err = fmt::format("saved block {:016x} overlaps non-replayable region {:016x}",
                               b.va, it->first);
            return false;
        }
    }
    return true;
}

// 页表整表回放。前提：EmulationFreeze（全部 guest 线程挂起，NCE 不跑）
// + NvdrvTeardown 已完成（SMMU/GMMU 设备映射已拆，nvmap 句柄已按存档
// 裁剪）+ 页组已预建（PrebuildSavedPageGroups）+ 布局兼容已预检
// （CheckLayoutCompatibility）。
SurgeryOutcome ReplayPageTableLayout(Core::System& system, const std::vector<SavedBlock>& saved,
                                     std::vector<PrebuiltGroup>& groups,
                                     const BookkeepingV5& bookkeeping, std::string* err) {
    *err = {};
    const auto t0 = NowSteady();

    Kernel::KProcess* process = system.ApplicationProcess();
    if (process == nullptr) {
        *err = "application process is null";
        return SurgeryOutcome::Torn;
    }
    Kernel::KProcessPageTable& page_table = process->GetPageTable();
    Kernel::KPageTableBase& base = page_table.GetBasePageTable();
    auto& kernel = system.Kernel();

    // ★ v5：确保应用进程是全局 current 页表（同捕获侧 —— 手术的宿主
    // 映射门控与后续 ApplyFrame 的 PagePtr 都依赖 current_page_table）
    system.ApplicationMemory().SetCurrentPageTable(*process);

    // 1) 当前布局
    std::vector<LayoutBlock> current;
    if (!CollectLayoutBlocks(system, current)) {
        *err = "current layout walk failed";
        return SurgeryOutcome::Torn;
    }

    // 2) 预置引用（+1）：存档布局的全部 PA（去重合并 —— 别名块共享页）
    const auto preopen = MergePaRuns(saved);
    const auto adjust_all = [&](s32 delta) {
        for (const auto& [start, end] : preopen) {
            kernel.MemoryManager().SavestateAdjustRef(start, (end - start) / kPageSize, delta);
        }
    };
    adjust_all(+1);

    // 3) ★ v7 根因修复：可回放块整备。SavestatePrepareForReplay 一次性
    //    复位全部瞬态/设备簿记 —— IpcLocked/Locked 锁位、nvmap 的
    //    DeviceShared（+ m_device_use_count + 禁合并位）、Uncached/
    //    PermissionLocked 持久属性、original_permission。v5/v6 只清两个
    //    锁位，漏掉 DeviceShared → nvmap 堆块（帧缓冲/纹理，几乎必然
    //    存在）unmap 必 0xd401 —— 同会话读档必失败的根因（详见文件头
    //    v7 说明与 KMemoryBlock::SavestateResetForReplay）。不再逐块
    //    SetMemoryAttribute 清属性：svc 路径状态位校验可能失败且无必
    //    要 —— 块随后整表卸载，属性由第 5 步按存档原值恢复。
    base.SavestatePrepareForReplay();

    // 4) 卸载当前全部可回放映射块（Io/Static 等不可回放状态原样保留；
    //    宿主映射窗口外的地址由 HostMemory v5 窗口裁剪安全跳过）
    for (const auto& b : current) {
        if (!IsPgState(b.state) || b.size % kPageSize != 0) {
            continue;
        }
        const Result r = page_table.UnmapPages(b.va, b.size / kPageSize,
                                               static_cast<Kernel::KMemoryState>(b.state));
        if (R_FAILED(r)) {
            *err = fmt::format("unmap failed @ {:016x} (state {:08x}): {:05x}", b.va, b.state,
                               r.raw);
            return SurgeryOutcome::Torn;
        }
    }

    // 5) 按存档重放：原 VA + 原 PA + 原 state/perm（MapPageGroup 与游戏
    //    日常映射同一条内核路径；页组为校验阶段预建物料 —— AddBlock 已
    //    在零副作用阶段完成，apply 期间不会再因页组重建失败而撕裂），
    //    随后重放 Uncached / PermissionLocked。
    size_t replayed_blocks = 0;
    for (auto& g : groups) {
        const auto& b = saved[g.block_index];
        const Result r = page_table.MapPageGroup(
            b.va, *g.pg, static_cast<Kernel::KMemoryState>(b.state),
            static_cast<Kernel::KMemoryPermission>(b.perm));
        if (R_FAILED(r)) {
            *err = fmt::format("remap failed @ {:016x} (state {:08x}): {:05x}", b.va, b.state,
                               r.raw);
            return SurgeryOutcome::Torn;
        }

        const auto attr = static_cast<Kernel::KMemoryAttribute>(b.attr);
        if (True(attr & Kernel::KMemoryAttribute::Uncached)) {
            page_table.SetMemoryAttribute(b.va, b.size, Kernel::KMemoryAttribute::Uncached,
                                          Kernel::KMemoryAttribute::Uncached);
        }
        if (True(attr & Kernel::KMemoryAttribute::PermissionLocked)) {
            page_table.SetMemoryAttribute(b.va, b.size, Kernel::KMemoryAttribute::PermissionLocked,
                                          Kernel::KMemoryAttribute::PermissionLocked);
        }
        ++replayed_blocks;
    }
    groups.clear(); // 页组已全部消费（unique_ptr 析构释放块信息节点）

    // 6) 归还预置引用（-1）
    adjust_all(-1);

    // 7) 分配器空闲位图整表重建（"引用计数==0 即空闲"；头尾拆分与伙伴
    //    合并全部走 KPageHeap::Free 既有代码路径）
    kernel.MemoryManager().SavestateRebuildFree();

    // 8) 页表记账回写（current_heap_end / mapped_physical_memory_size 等，
    //    = Cemu 的 sysAreaAllocatorOffset 随档恢复）
    {
        Kernel::KPageTableBase::SavestateBookkeeping bk{};
        std::memcpy(&bk, &bookkeeping, sizeof(bk));
        if (!base.SavestateRestoreBookkeeping(bk)) {
            // Precheck 已校验区域一致，这里不应失败 —— 防御性关停
            *err = "bookkeeping restore rejected";
            return SurgeryOutcome::Torn;
        }
    }

    SSLogInfo(fmt::format("surgery: {} blocks replayed, {} preopen spans, {} ms",
                          replayed_blocks, preopen.size(), ElapsedMsSince(t0)));
    return SurgeryOutcome::Ok;
}

// ---------------------------------------------------------------------------
// state.bin —— 原地恢复（quickRestoreSlot 调用，必须持冻结）
// ---------------------------------------------------------------------------

enum class RestoreOutcome {
    Ok,             // 原地恢复成功
    Incompatible,   // 布局真正不兼容（NCE 开关切换 / 旧版随机布局时代的
                    // 槽位等 —— v7 确定性布局下仅剩这些场景）
    Error,          // 校验失败（内存未动，可安全报错/回落）
    TooOld,         // 旧版本格式槽位 → 提示重新存档（不回落、不重启）
    Torn,           // 应用中途失败（内核状态已改动 → 必须关停核心）
};
// v6：ThreadsChanged（线程集全等门禁）已废除 —— Cemu 读档从不比对
// 线程集，而是从恢复后的状态重建调度器。v6 同构：多余的线程确定性
// 移除（SavestateRemoveThread），缺失的线程按存档重建（含 thread_id /
// TLS / 句柄值回写）。这是 v5 用户反复命中「与当前会话不匹配→重启」
// 回退的主因（游戏运行期间短命工作线程很常见）。

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
    if (ec || file_size < sizeof(StateHeaderV5)) {
        *err = "state.bin too small";
        return RestoreOutcome::Error;
    }

    std::ifstream in(state_path, std::ios::binary);
    if (!in) {
        *err = "failed to open state.bin";
        return RestoreOutcome::Error;
    }

    // ---- 1) 头部 ----
    StateHeaderV5 header{};
    in.read(reinterpret_cast<char*>(&header), sizeof(header));
    if (in.gcount() != static_cast<std::streamsize>(sizeof(header)) ||
        std::memcmp(header.magic, kStateMagic, sizeof(kStateMagic)) != 0) {
        *err = "bad state.bin magic";
        return RestoreOutcome::Error;
    }
    if (header.version != kStateVersion) {
        *err = fmt::format("unsupported state.bin version {} (re-save to upgrade)",
                           header.version);
        return RestoreOutcome::TooOld;
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
        return RestoreOutcome::TooOld;
    }
    if (header.block_count == 0 || header.block_count > kMaxBlockCount ||
        header.content_count == 0 || header.content_count > header.block_count ||
        header.thread_count > kMaxThreadCount || header.nvdrv_size > kMaxNvdrvSize ||
        header.pa_run_total > kMaxPaRunTotal) {
        *err = "state.bin header sanity check failed";
        return RestoreOutcome::Error;
    }

    // ---- 2) 记账 / 块表 / PARun 表 / 内容表 ----
    BookkeepingV5 bookkeeping{};
    in.read(reinterpret_cast<char*>(&bookkeeping), sizeof(bookkeeping));
    if (in.gcount() != static_cast<std::streamsize>(sizeof(bookkeeping))) {
        *err = "state.bin bookkeeping truncated";
        return RestoreOutcome::Error;
    }

    std::vector<SavedBlock> saved_blocks(header.block_count);
    {
        std::vector<BlockDescV5> descs(header.block_count);
        in.read(reinterpret_cast<char*>(descs.data()),
                static_cast<std::streamsize>(descs.size() * sizeof(BlockDescV5)));
        if (in.gcount() != static_cast<std::streamsize>(descs.size() * sizeof(BlockDescV5))) {
            *err = "state.bin block table truncated";
            return RestoreOutcome::Error;
        }
        for (size_t i = 0; i < descs.size(); ++i) {
            saved_blocks[i].va = descs[i].va;
            saved_blocks[i].size = descs[i].size;
            saved_blocks[i].state = descs[i].state;
            saved_blocks[i].perm = descs[i].perm;
            saved_blocks[i].attr = descs[i].attr;
            saved_blocks[i].has_pg = (descs[i].flags & kBlockFlagHasPg) != 0;
        }
    }

    // PARun 表（与块表同序；每块 u32 run_count + PARunV5×n）
    u64 run_total = 0;
    for (auto& b : saved_blocks) {
        u32 run_count = 0;
        in.read(reinterpret_cast<char*>(&run_count), sizeof(run_count));
        if (in.gcount() != static_cast<std::streamsize>(sizeof(run_count))) {
            *err = "state.bin pa run table truncated";
            return RestoreOutcome::Error;
        }
        if (!b.has_pg && run_count != 0) {
            *err = "state.bin pa run table inconsistent";
            return RestoreOutcome::Error;
        }
        if (run_count == 0) {
            continue;
        }
        b.runs.resize(run_count);
        in.read(reinterpret_cast<char*>(b.runs.data()),
                static_cast<std::streamsize>(run_count * sizeof(PARunV5)));
        if (in.gcount() != static_cast<std::streamsize>(run_count * sizeof(PARunV5))) {
            *err = "state.bin pa run table truncated";
            return RestoreOutcome::Error;
        }
        u64 pages_total = 0;
        for (auto& [pa, pages] : b.runs) {
            if (pages == 0 || pa % kPageSize != 0) {
                *err = fmt::format("bad pa run in block {:016x}", b.va);
                return RestoreOutcome::Error;
            }
            pages_total += pages;
        }
        if (pages_total * kPageSize != b.size) {
            *err = fmt::format("pa run page count mismatch @ {:016x}", b.va);
            return RestoreOutcome::Error;
        }
        run_total += run_count;
    }
    if (run_total != header.pa_run_total) {
        *err = "state.bin pa run total mismatch";
        return RestoreOutcome::Error;
    }

    std::vector<ContentDescV5> content_descs(header.content_count);
    in.read(reinterpret_cast<char*>(content_descs.data()),
            static_cast<std::streamsize>(content_descs.size() * sizeof(ContentDescV5)));
    if (in.gcount() !=
        static_cast<std::streamsize>(content_descs.size() * sizeof(ContentDescV5))) {
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

    // ---- 3) 线程记录读取 + 线程集差分（v6：不再全等门禁） ----
    // Cemu 读档从不比对线程集 —— 宿主侧调度器从恢复后的状态整表重建
    //（SavestateRebuildSchedulerAfterRestore）。eden 同构：
    //   多余线程（当前有、存档无）= 已废弃时间线产物 → 确定性移除
    //   缺失线程（存档有、当前无）→ 按存档重建（id/TLS/句柄/上下文全回放）
    std::vector<u64> saved_thread_ids;
    saved_thread_ids.reserve(header.thread_count);
    std::vector<u8> thread_blob;
    {
        const size_t thread_bytes =
            static_cast<size_t>(header.thread_count) * kThreadRecordSize;
        thread_blob.resize(thread_bytes);
        in.seekg(static_cast<std::streamoff>(thread_off), std::ios::beg);
        in.read(reinterpret_cast<char*>(thread_blob.data()),
                static_cast<std::streamsize>(thread_bytes));
        if (thread_bytes > 0 && static_cast<size_t>(in.gcount()) != thread_bytes) {
            *err = "state.bin thread section truncated";
            return RestoreOutcome::Error;
        }
        for (u32 i = 0; i < header.thread_count; ++i) {
            ThreadMetaV2 meta{};
            std::memcpy(&meta, thread_blob.data() + i * kThreadRecordSize, sizeof(meta));
            if (meta.thread_id == 0) {
                *err = "state.bin thread record has zero id";
                return RestoreOutcome::Error;
            }
            saved_thread_ids.push_back(meta.thread_id);
        }
        std::sort(saved_thread_ids.begin(), saved_thread_ids.end());
    }

    // ---- 4) nvdrv 段解析（纯解析，无副作用；执行拆到 Teardown/Replay） ----
    NvdrvState nvdrv_state{};
    {
        std::vector<u8> nvdrv_blob(static_cast<size_t>(header.nvdrv_size));
        in.clear();
        in.seekg(static_cast<std::streamoff>(nvdrv_off), std::ios::beg);
        in.read(reinterpret_cast<char*>(nvdrv_blob.data()),
                static_cast<std::streamsize>(nvdrv_blob.size()));
        if (static_cast<size_t>(in.gcount()) != nvdrv_blob.size()) {
            *err = "nvdrv section truncated";
            return RestoreOutcome::Error;
        }
        std::string nvdrv_err;
        if (!ParseNvdrvState(nvdrv_blob, &nvdrv_state, &nvdrv_err)) {
            *err = "nvdrv parse failed: " + nvdrv_err;
            return RestoreOutcome::Error;
        }
    }

    // ---- 5) 手术前置校验（会话区域一致；须在 Teardown 之前，失败时零
    //      副作用 → 可安全回落 legacy）。v7 起区域布局已确定性化
    //      （InitializeForProcess / loader aslr_offset / HostMemory 预留
    //      基址三处配套修改）—— 同一游戏 + 同一配置跨启动布局一致，
    //      旧会话槽位也能原地恢复；本检查命中仅剩真正不兼容的场景
    //      （NCE 开关切换、旧版随机布局时代的槽位等）→ 干净回落 legacy ----
    if (!SurgeryPrecheck(system, bookkeeping, err)) {
        SSLogWarn(fmt::format("restore: precheck rejected ({})", *err));
        return RestoreOutcome::Incompatible;
    }

    // ---- 5b) v7：手术物料预建 + 布局兼容性预检（Cemu
    //      RestoreStateFromFile 的「先全量校验、后应用」铁律 —— 一切可
    //      预见的失败在此以零副作用收场，apply 阶段只剩内核原语，
    //      不会再因页组重建/布局冲突而撕裂） ----
    std::vector<PrebuiltGroup> prebuilt_groups;
    if (!PrebuildSavedPageGroups(system, saved_blocks, &prebuilt_groups, err)) {
        SSLogWarn(fmt::format("restore: prebuild rejected ({})", *err));
        return RestoreOutcome::Error;
    }
    if (!CheckLayoutCompatibility(system, saved_blocks, err)) {
        SSLogWarn(fmt::format("restore: layout compatibility rejected ({})", *err));
        return RestoreOutcome::Error;
    }

    // ---- 6) 逐帧 zstd 流式解码丢弃验证（动内存前的最后把关；Cemu
    //      ValidateCompressedRange 同款） ----
    in.clear();
    in.seekg(frames_start, std::ios::beg);
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

    // ════════════════ 应用阶段（此后内核状态被改动；失败一律 Torn） ════════════════

    Kernel::KProcess* process = system.ApplicationProcess();
    if (process == nullptr) {
        *err = "application process is null";
        return RestoreOutcome::Torn;
    }

    // ---- 7) v6 多余线程移除（存档后新建的线程 = 已废弃时间线产物） ----
    //      Cemu 语义：废弃时间线的宿主状态全部丢弃（同 GX2 丢弃排队事件
    //      回调）。确定性移除不执行任何 guest 代码；必须先清句柄表引用，
    //      且在页表手术前完成（移除过程中的 TLS/PI 清理操作当前页表）。
    {
        const std::vector<u64> current_ids = CollectLiveThreadIds(system);
        std::vector<Kernel::KThread*> to_remove;
        for (auto& thread : process->GetThreadList()) {
            if (thread.IsDummyThread()) {
                continue;
            }
            if (!std::binary_search(saved_thread_ids.begin(), saved_thread_ids.end(),
                                    thread.GetThreadId())) {
                to_remove.push_back(std::addressof(thread));
            }
        }
        if (!to_remove.empty()) {
            SSLogInfo(fmt::format(
                "restore: thread diff ({} saved / {} live) - removing {} extra from discarded timeline",
                saved_thread_ids.size(), current_ids.size(), to_remove.size()));
            // 顺序关键：先 SavestateRemoveThread（标记终止 + 释放运行引用，
            // 对象仍存活），再清句柄表（通常触发最后一次 Close → Finalize
            // 的干净析构：注销 + TLS 槽归还 + 资源限额释放）。
            for (Kernel::KThread* thread : to_remove) {
                thread->SavestateRemoveThread(system.Kernel());
            }
            for (Kernel::KThread* thread : to_remove) {
                process->GetHandleTable().SavestatePurgeObject(system.Kernel(), thread);
            }
        }
    }

    // ---- 8) nvmap Teardown：拆除当前全部设备映射（SMMU/GMMU/低位 pin；
    //      页表块上的 DeviceShared 属性由手术第 3 步
    //      SavestatePrepareForReplay 一并复位 —— v5/v6 依赖本步清属性
    //      是不成立的：IocFree 的 can_unlock 才是对称清除路径，存档时
    //      活跃的句柄会把它带进手术现场） ----
    NvdrvTeardown(system, nvdrv_state);

    // ---- 9) 页表整表回放手术：当前布局 → 存档布局（原 VA/PA/权限；
    //      页组为 5b) 预建物料） ----
    {
        const SurgeryOutcome so =
            ReplayPageTableLayout(system, saved_blocks, prebuilt_groups, bookkeeping, err);
        if (so != SurgeryOutcome::Ok) {
            // Teardown 已拆除设备映射、手术可能半途 —— 带撕裂状态续跑必崩，
            // 上层按 Torn 关停核心（legacy 允许时回落重启路径）
            SSLogWarn(fmt::format("restore: surgery failed ({})", *err));
            return RestoreOutcome::Torn;
        }
    }

    // ---- 10) 内容帧写回（此刻映射已与存档逐块一致 —— Cemu 的
    //      "内容原地覆盖" 至此完全成立） ----
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

    // ---- 11) nvdrv Replay：GMMU 映射 + nvmap 句柄表按存档重建
    //      （SMMU/GMMU/低位 pin 指向手术后的新映射；GMMU 全量清空即触发
    //      rasterizer 对全部受影响设备地址段的缓存丢弃 —— Cemu
    //      RequestGpuCacheReset 同构） ----
    NvdrvReplay(system, nvdrv_state);

    // ---- 12) GPU 指令/数据缓存一致性：宿主纹理、缓冲、渲染目标缓存全部
    //      失效，从还原后的 guest 内存重建（InvalidateGPUCache 与
    //      ReloadDiskShaderCache 同源语义） ----
    if (auto* rasterizer = system.Renderer().ReadRasterizer(); rasterizer != nullptr) {
        rasterizer->InvalidateGPUCache();
        rasterizer->FlushCommands();
    }

    // ---- 13) ARM 指令缓存：逐核清空（NCE 指令跳板 / dynarmic 翻译缓存；
    //      页粒度 icache 已在 ApplyFrame 按 run 刷新） ----
    for (std::size_t core = 0; core < Core::Hardware::NUM_CPU_CORES; ++core) {
        if (Core::ArmInterface* iface = process->GetArmInterface(core); iface != nullptr) {
            iface->ClearInstructionCache();
        }
    }

    // ---- 14) v6 TLS 宿主册簿整表重建：页表手术已恢复 TLS 映射，这里按
    //      存档线程集重建 KThreadLocalPage 树（哪些页、页内哪些槽在用）。
    //      与 Cemu 恢复 SysAllocator 记账同构。须在线程重建之前。 ----
    {
        std::vector<u64> saved_tls;
        saved_tls.reserve(header.thread_count);
        for (u32 i = 0; i < header.thread_count; ++i) {
            ThreadMetaV2 meta{};
            std::memcpy(&meta, thread_blob.data() + i * kThreadRecordSize, sizeof(meta));
            if (meta.tls_addr != 0) {
                saved_tls.push_back(meta.tls_addr);
            }
        }
        process->SavestateRebuildTlsState(system.Kernel(), saved_tls);
    }

    // ---- 15) v6 缺失线程重建（存档时有、现已消亡的线程）----
    //      Cemu SavestateRebuildSchedulerAfterRestore 同构 —— 宿主侧线程
    //      对象按存档重建：InitializeUserThread（与游戏 svcCreateThread
    //      同一条内核路径）→ 身份回写（thread_id / TLS / 亲和掩码）→
    //      Runnable 启动 → 上下文覆盖（存档时刻寄存器组）→ 句柄表按
    //      存档值回放。恢复后的 guest 代码自行重验其同步原语（互斥锁
    //      fastpath 在 guest 侧循环重查 —— 这是 HOS 线程恢复的既有语义）。
    {
        u32 recreated = 0;
        for (u32 i = 0; i < header.thread_count; ++i) {
            ThreadMetaV2 meta{};
            std::memcpy(&meta, thread_blob.data() + i * kThreadRecordSize, sizeof(meta));
            Kernel::Svc::ThreadContext ctx{};
            std::memcpy(&ctx, thread_blob.data() + i * kThreadRecordSize + sizeof(meta),
                        sizeof(ctx));

            // 幸存？
            bool survives = false;
            for (auto& thread : process->GetThreadList()) {
                if (!thread.IsDummyThread() && thread.GetThreadId() == meta.thread_id) {
                    survives = true;
                    break;
                }
            }
            if (survives) {
                continue;
            }

            Kernel::KThread* t =
                Kernel::KThread::Create(system.Kernel());
            if (t == nullptr) {
                *err = fmt::format("thread slab exhausted @ id {:016x}", meta.thread_id);
                return RestoreOutcome::Torn;
            }
            // InitializeUserThread 会分配一个新 TLS 槽 —— 立即归还，随后
            // 按存档回写真正的 TLS 地址（页表手术已恢复其映射）。
            const s32 virt_core = (meta.active_core >= 0 &&
                                   meta.active_core < static_cast<s32>(Core::Hardware::NUM_CPU_CORES))
                                      ? meta.active_core
                                      : 0;
            const Result r = Kernel::KThread::InitializeUserThread(
                system, t, Kernel::KProcessAddress(ctx.pc),
                static_cast<uintptr_t>(ctx.r[0]),
                Kernel::KProcessAddress(meta.stack_top), meta.priority, virt_core, process);
            if (R_FAILED(r)) {
                t->Close(system.Kernel());
                *err = fmt::format("thread recreate failed @ id {:016x}: {:05x}", meta.thread_id,
                                   r.raw);
                return RestoreOutcome::Torn;
            }
            Kernel::KThread::Register(system.Kernel(), t);
            if (Kernel::KProcessAddress fresh_tls = t->GetTlsAddress();
                GetInteger(fresh_tls) != 0) {
                process->DeleteThreadLocalRegion(system.Kernel(), fresh_tls);
            }
            t->SavestateSetIdentity(meta.thread_id, meta.virt_affinity_mask,
                                    Kernel::KProcessAddress(meta.tls_addr));
            t->SavestateRecreateRun(system.Kernel());
            Kernel::Svc::ThreadContext& dst = t->GetContext();
            std::memcpy(&dst, &ctx, sizeof(Kernel::Svc::ThreadContext));
            if (meta.thread_handle != 0 &&
                process->GetHandleTable().SavestateRestoreHandle(
                    system.Kernel(), static_cast<Kernel::Handle>(meta.thread_handle), t)) {
                // 句柄表接管对象引用（svc 创建路径的 SCOPE_EXIT Close 同款）
                t->Close(system.Kernel());
            }
            // thread_handle == 0（存档时表中无句柄）则保留 Create 引用作
            // 对象生命周期持有者 —— 无泄漏风险（进程关闭时整体回收）
            ++recreated;
        }
        if (recreated > 0) {
            // 线程 ID 计数器前移到存档最大 ID 之上：后续新建线程不会拿到
            // 与重建线程重复的 ID（guest 内存里存有线程 ID）
            u64 max_saved_id = 0;
            for (u64 id : saved_thread_ids) {
                max_saved_id = std::max(max_saved_id, id);
            }
            system.Kernel().SavestateAdvanceThreadID(max_saved_id + 1);
            SSLogInfo(fmt::format("restore: recreated {} missing thread(s)", recreated));
        }
    }

    // ---- 16) 幸存线程：寄存器组原地写回 + 句柄按存档回放（guest 互斥锁
    //      字/TCB 存有句柄值，句柄表必须与存档时刻一致）。挂起的线程解冻
    //      后被重新调度时经 KScheduler::Reload → LoadContext → SetContext
    //      装载恢复后的寄存器组 —— 从存档时刻继续。 ----
    {
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
                if (meta.thread_handle != 0) {
                    // 先清当前指向该线程的句柄（废弃时间线可能关闭过原句柄、
                    // 开过重复句柄），再按存档值回放
                    process->GetHandleTable().SavestatePurgeObject(system.Kernel(),
                                                                   std::addressof(thread));
                    process->GetHandleTable().SavestateRestoreHandle(
                        system.Kernel(), static_cast<Kernel::Handle>(meta.thread_handle),
                        std::addressof(thread));
                }
                break;
            }
        }
    }

    SSLogInfo(fmt::format("restore: done, {} frames / {} threads, {} ms (rss {} MB)",
                          content_descs.size(), header.thread_count,
                          ElapsedMsSince(t0), CurrentRssMB()));
    return RestoreOutcome::Ok;
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
        //    游戏暂停 → 全量校验（含 v7 页组预建/布局预检）→ nvmap 拆除 →
        //    可回放块整备（SavestatePrepareForReplay：含 nvmap DeviceShared
        //    复位）→ 页表整表回放手术（原 VA/PA/权限）→ 内容覆盖 →
        //    nvmap 重建 → 从存档时刻继续，完全不重启。
        //    v7 起布局确定性化：跨会话（重启游戏后）的槽位同样原地恢复；
        //    仅当真正不兼容（NCE 开关切换 / 旧版随机布局时代的槽位）或
        //    应用中途失败时才回落 legacy。
        bool legacy_by_incompat = false;
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

                case RestoreOutcome::Incompatible:
                    // 原地恢复不可行（布局真正不兼容的槽位 —— 校验阶段失败，
                    // 内核零改动；v7 确定性布局下仅剩 NCE 开关切换、旧版
                    // 随机布局时代的槽位等场景）：自动回落 legacy —— 前端
                    // relaunchGame() 重启加载。
                    legacy_by_incompat = true;
                    break;

                case RestoreOutcome::TooOld:
                    // 旧版本格式槽位：如实提示重新存档 —— 不回落、不重启
                    //（Cemu 对 v1/v2 档保持可读；格式不兼容时明确报错）
                    LOG_ERROR(Frontend, "Eden savestate slot format too old: {}", state_err);
                    SSLogWarn(fmt::format("restore too old: {}", state_err));
                    return native_savestate::RESULT_ERROR_STATE_TOO_OLD;

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

            if (legacy_by_incompat) {
                SSLogInfo("restore: falling back to legacy (restart) path");
            }
        }

        // ── legacy 路径（老槽位无 state.bin / 会话不兼容自动回落 / 安全模式）──
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
        // RESULT_OK_LEGACY_RESTART：布局不兼容自动回落时的同路径返回码 ——
        // 前端据此提示「已重启游戏加载」而非误导性的原地恢复话术
        // （v7 确定性布局下仅剩 NCE 开关切换、旧版随机布局时代的槽位等
        // 真正不兼容场景才会走到这里）。
        return legacy_by_incompat ? native_savestate::RESULT_OK_LEGACY_RESTART
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
