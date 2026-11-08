// SPDX-FileCopyrightText: Copyright 2025 Eden Emulator Project
// SPDX-License-Identifier: GPL-3.0-or-later

// SPDX-FileCopyrightText: Copyright 2020 yuzu Emulator Project
// SPDX-License-Identifier: GPL-2.0-or-later

#pragma once

#include <array>
#include <tuple>

#include "common/common_funcs.h"
#include "core/hle/kernel/k_light_lock.h"
#include "core/hle/kernel/k_memory_layout.h"
#include "core/hle/kernel/k_page_heap.h"
#include "core/hle/kernel/k_typed_address.h"
#include "core/hle/result.h"

namespace Core {
class System;
}

namespace Kernel {

class KPageGroup;

class KMemoryManager {
public:
    enum class Pool : u32 {
        Application = 0,
        Applet = 1,
        System = 2,
        SystemNonSecure = 3,

        Count,

        Shift = 4,
        Mask = (0xF << Shift),

        // Aliases.
        Unsafe = Application,
        Secure = System,
    };

    enum class Direction : u32 {
        FromFront = 0,
        FromBack = 1,
        Shift = 0,
        Mask = (0xF << Shift),
    };

    static constexpr size_t MaxManagerCount = 10;

    explicit KMemoryManager(Core::System& system);

    void Initialize(KVirtualAddress management_region, size_t management_region_size);

    Result InitializeOptimizedMemory(u64 process_id, Pool pool);
    void FinalizeOptimizedMemory(u64 process_id, Pool pool);

    KPhysicalAddress AllocateAndOpenContinuous(size_t num_pages, size_t align_pages, u32 option);
    Result AllocateAndOpen(KPageGroup* out, size_t num_pages, u32 option);
    Result AllocateForProcess(KPageGroup* out, size_t num_pages, u32 option, u64 process_id,
                              u8 fill_pattern);

    Pool GetPool(KPhysicalAddress address) const {
        return this->GetManager(address).GetPool();
    }

    void Open(KPhysicalAddress address, size_t num_pages) {
        // Repeatedly open references until we've done so for all pages.
        while (num_pages) {
            auto& manager = this->GetManager(address);
            const size_t cur_pages = (std::min)(num_pages, manager.GetPageOffsetToEnd(address));

            {
                KScopedLightLock lk(m_pool_locks[static_cast<size_t>(manager.GetPool())]);
                manager.Open(address, cur_pages);
            }

            num_pages -= cur_pages;
            address += cur_pages * PageSize;
        }
    }

    void OpenFirst(KPhysicalAddress address, size_t num_pages) {
        // Repeatedly open references until we've done so for all pages.
        while (num_pages) {
            auto& manager = this->GetManager(address);
            const size_t cur_pages = (std::min)(num_pages, manager.GetPageOffsetToEnd(address));

            {
                KScopedLightLock lk(m_pool_locks[static_cast<size_t>(manager.GetPool())]);
                manager.OpenFirst(address, cur_pages);
            }

            num_pages -= cur_pages;
            address += cur_pages * PageSize;
        }
    }

    void Close(KPhysicalAddress address, size_t num_pages) {
        // Repeatedly close references until we've done so for all pages.
        while (num_pages) {
            auto& manager = this->GetManager(address);
            const size_t cur_pages = (std::min)(num_pages, manager.GetPageOffsetToEnd(address));

            {
                KScopedLightLock lk(m_pool_locks[static_cast<size_t>(manager.GetPool())]);
                manager.Close(address, cur_pages);
            }

            num_pages -= cur_pages;
            address += cur_pages * PageSize;
        }
    }

    size_t GetSize() {
        size_t total = 0;
        for (size_t i = 0; i < m_num_managers; i++) {
            total += m_managers[i].GetSize();
        }
        return total;
    }

    size_t GetSize(Pool pool) {
        constexpr Direction GetSizeDirection = Direction::FromFront;
        size_t total = 0;
        for (auto* manager = this->GetFirstManager(pool, GetSizeDirection); manager != nullptr;
             manager = this->GetNextManager(manager, GetSizeDirection)) {
            total += manager->GetSize();
        }
        return total;
    }

    size_t GetFreeSize() {
        size_t total = 0;
        for (size_t i = 0; i < m_num_managers; i++) {
            KScopedLightLock lk(m_pool_locks[static_cast<size_t>(m_managers[i].GetPool())]);
            total += m_managers[i].GetFreeSize();
        }
        return total;
    }

    size_t GetFreeSize(Pool pool) {
        KScopedLightLock lk(m_pool_locks[static_cast<size_t>(pool)]);

        constexpr Direction GetSizeDirection = Direction::FromFront;
        size_t total = 0;
        for (auto* manager = this->GetFirstManager(pool, GetSizeDirection); manager != nullptr;
             manager = this->GetNextManager(manager, GetSizeDirection)) {
            total += manager->GetFreeSize();
        }
        return total;
    }

    void DumpFreeList(Pool pool) {
        KScopedLightLock lk(m_pool_locks[static_cast<size_t>(pool)]);

        constexpr Direction DumpDirection = Direction::FromFront;
        for (auto* manager = this->GetFirstManager(pool, DumpDirection); manager != nullptr;
             manager = this->GetNextManager(manager, DumpDirection)) {
            manager->DumpFreeList();
        }
    }

    // ★★★ NesStation savestate (v5)：外层入口（详见 Impl 内注释） ★★★
    // 调用前提：模拟已整体冻结（System::Pause + GPU 排空），无任何并发
    // 分配/释放 —— 因此 SavestateRebuildFree 不逐池加锁（同一池可能有多个
    // manager，KLightLock 不可重入，重复加锁会死锁；冻结本身就是锁）。
    void SavestateAdjustRef(KPhysicalAddress address, size_t num_pages, s32 delta) {
        while (num_pages) {
            auto& manager = this->GetManager(address);
            const size_t cur_pages =
                (std::min)(num_pages, manager.GetPageOffsetToEnd(address));
            manager.SavestateAdjustRef(address, cur_pages, delta);
            num_pages -= cur_pages;
            address += cur_pages * PageSize;
        }
    }

    void SavestateRebuildFree() {
        for (size_t i = 0; i < m_num_managers; i++) {
            m_managers[i].SavestateRebuildFree();
        }
    }

public:
    static size_t CalculateManagementOverheadSize(size_t region_size) {
        return Impl::CalculateManagementOverheadSize(region_size);
    }

    static constexpr u32 EncodeOption(Pool pool, Direction dir) {
        return (static_cast<u32>(pool) << static_cast<u32>(Pool::Shift)) |
               (static_cast<u32>(dir) << static_cast<u32>(Direction::Shift));
    }

    static constexpr Pool GetPool(u32 option) {
        return static_cast<Pool>((option & static_cast<u32>(Pool::Mask)) >>
                                 static_cast<u32>(Pool::Shift));
    }

    static constexpr Direction GetDirection(u32 option) {
        return static_cast<Direction>((option & static_cast<u32>(Direction::Mask)) >>
                                      static_cast<u32>(Direction::Shift));
    }

    static constexpr std::tuple<Pool, Direction> DecodeOption(u32 option) {
        return std::make_tuple(GetPool(option), GetDirection(option));
    }

private:
    class Impl {
    public:
        static size_t CalculateManagementOverheadSize(size_t region_size);

        static constexpr size_t CalculateOptimizedProcessOverheadSize(size_t region_size) {
            return (Common::AlignUp((region_size / PageSize), Common::BitSize<u64>()) /
                    Common::BitSize<u64>()) *
                   sizeof(u64);
        }

    public:
        Impl() = default;

        size_t Initialize(KPhysicalAddress address, size_t size, KVirtualAddress management,
                          KVirtualAddress management_end, Pool p);

        KPhysicalAddress AllocateBlock(s32 index, bool random) {
            return m_heap.AllocateBlock(index, random);
        }
        KPhysicalAddress AllocateAligned(s32 index, size_t num_pages, size_t align_pages) {
            return m_heap.AllocateAligned(index, num_pages, align_pages);
        }
        void Free(KPhysicalAddress addr, size_t num_pages) {
            m_heap.Free(addr, num_pages);
        }

        void SetInitialUsedHeapSize(size_t reserved_size) {
            m_heap.SetInitialUsedSize(reserved_size);
        }

        void InitializeOptimizedMemory(KernelCore& kernel);

        void TrackUnoptimizedAllocation(KernelCore& kernel, KPhysicalAddress block,
                                        size_t num_pages);
        void TrackOptimizedAllocation(KernelCore& kernel, KPhysicalAddress block, size_t num_pages);

        bool ProcessOptimizedAllocation(KernelCore& kernel, KPhysicalAddress block,
                                        size_t num_pages, u8 fill_pattern);

        constexpr Pool GetPool() const {
            return m_pool;
        }
        constexpr size_t GetSize() const {
            return m_heap.GetSize();
        }
        constexpr KPhysicalAddress GetEndAddress() const {
            return m_heap.GetEndAddress();
        }

        size_t GetFreeSize() const {
            return m_heap.GetFreeSize();
        }

        void DumpFreeList() const {
            UNIMPLEMENTED();
        }

        constexpr size_t GetPageOffset(KPhysicalAddress address) const {
            return m_heap.GetPageOffset(address);
        }
        constexpr size_t GetPageOffsetToEnd(KPhysicalAddress address) const {
            return m_heap.GetPageOffsetToEnd(address);
        }

        constexpr void SetNext(Impl* n) {
            m_next = n;
        }
        constexpr void SetPrev(Impl* n) {
            m_prev = n;
        }
        constexpr Impl* GetNext() const {
            return m_next;
        }
        constexpr Impl* GetPrev() const {
            return m_prev;
        }

        void OpenFirst(KPhysicalAddress address, size_t num_pages) {
            size_t index = this->GetPageOffset(address);
            const size_t end = index + num_pages;
            while (index < end) {
                const RefCount ref_count = (++m_page_reference_counts[index]);
                ASSERT(ref_count == 1);

                index++;
            }
        }

        void Open(KPhysicalAddress address, size_t num_pages) {
            size_t index = this->GetPageOffset(address);
            const size_t end = index + num_pages;
            while (index < end) {
                const RefCount ref_count = (++m_page_reference_counts[index]);
                ASSERT(ref_count > 1);

                index++;
            }
        }

        void Close(KPhysicalAddress address, size_t num_pages) {
            size_t index = this->GetPageOffset(address);
            const size_t end = index + num_pages;

            size_t free_start = 0;
            size_t free_count = 0;
            while (index < end) {
                ASSERT(m_page_reference_counts[index] > 0);
                const RefCount ref_count = (--m_page_reference_counts[index]);

                // Keep track of how many zero refcounts we see in a row, to minimize calls to free.
                if (ref_count == 0) {
                    if (free_count > 0) {
                        free_count++;
                    } else {
                        free_start = index;
                        free_count = 1;
                    }
                } else {
                    if (free_count > 0) {
                        this->Free(m_heap.GetAddress() + free_start * PageSize, free_count);
                        free_count = 0;
                    }
                }

                index++;
            }

            if (free_count > 0) {
                this->Free(m_heap.GetAddress() + free_start * PageSize, free_count);
            }
        }

        // ★★★ NesStation savestate (v5)：页表手术期间的分配器一致性维护 ★★★
        //
        // 即时存档读档需要把应用进程的页表整表回放到存档时刻的布局
        //（KPageTableBase::UnmapPages + MapPageGroup）。MapPageGroup 内部经
        // KScopedPageGroup 走 Open()，而 Open 断言 ref_count > 1 —— 也就是说
        // 内核约定"被映射的页必须已被某处持有引用"。存档后游戏释放过的页
        // 此时引用计数为 0（仍在空闲列表里），直接回放映射会撞断言。
        //
        // 方案：手术开始前用 SavestateAdjustRef(+1) 把存档布局里出现的所有
        // 物理页引用预置 +1（绕过 Open 的断言；空闲位图暂不一致 —— 全部
        // guest 线程已冻结，无并发分配）；手术结束后 SavestateAdjustRef(-1)
        // 归还预置，最后 SavestateRebuildFree 按"引用计数 == 0 即空闲"整表
        // 重建空闲位图（清位 + 对每段 0 计数连续区跑标准 Free()，头尾拆分
        // 与伙伴合并全部走既有代码路径）。
        void SavestateAdjustRef(KPhysicalAddress address, size_t num_pages, s32 delta) {
            size_t index = this->GetPageOffset(address);
            const size_t end = index + num_pages;
            // 越界保护：调用方只允许喂本 manager 管辖的池内物理页
            ASSERT(address >= m_heap.GetAddress());
            ASSERT(end <= m_page_reference_counts.size());
            while (index < end) {
                if (delta > 0) {
                    m_page_reference_counts[index] = static_cast<RefCount>(
                        static_cast<s32>(m_page_reference_counts[index]) + delta);
                } else if (delta < 0) {
                    ASSERT(m_page_reference_counts[index] > 0);
                    m_page_reference_counts[index] = static_cast<RefCount>(
                        static_cast<s32>(m_page_reference_counts[index]) + delta);
                }
                index++;
            }
        }

        void SavestateRebuildFree() {
            m_heap.SavestateClearFreeBits();
            const size_t total = m_page_reference_counts.size();
            size_t run_start = 0;
            bool in_run = false;
            for (size_t i = 0; i < total; ++i) {
                if (m_page_reference_counts[i] == 0) {
                    if (!in_run) {
                        run_start = i;
                        in_run = true;
                    }
                } else if (in_run) {
                    m_heap.Free(m_heap.GetAddress() + run_start * PageSize, i - run_start);
                    in_run = false;
                }
            }
            if (in_run) {
                m_heap.Free(m_heap.GetAddress() + run_start * PageSize, total - run_start);
            }
        }

    private:
        using RefCount = u16;

        KPageHeap m_heap;
        std::vector<RefCount> m_page_reference_counts;
        KVirtualAddress m_management_region{};
        Pool m_pool{};
        Impl* m_next{};
        Impl* m_prev{};
    };

private:
    Impl& GetManager(KPhysicalAddress address) {
        return m_managers[m_memory_layout.GetPhysicalLinearRegion(address).GetAttributes()];
    }

    const Impl& GetManager(KPhysicalAddress address) const {
        return m_managers[m_memory_layout.GetPhysicalLinearRegion(address).GetAttributes()];
    }

    constexpr Impl* GetFirstManager(Pool pool, Direction dir) {
        return dir == Direction::FromBack ? m_pool_managers_tail[static_cast<size_t>(pool)]
                                          : m_pool_managers_head[static_cast<size_t>(pool)];
    }

    constexpr Impl* GetNextManager(Impl* cur, Direction dir) {
        if (dir == Direction::FromBack) {
            return cur->GetPrev();
        } else {
            return cur->GetNext();
        }
    }

    Result AllocatePageGroupImpl(KPageGroup* out, size_t num_pages, Pool pool, Direction dir,
                                 bool unoptimized, bool random);

private:
    template <typename T>
    using PoolArray = std::array<T, static_cast<size_t>(Pool::Count)>;

    Core::System& m_system;
    const KMemoryLayout& m_memory_layout;
    PoolArray<KLightLock> m_pool_locks;
    std::array<Impl*, MaxManagerCount> m_pool_managers_head{};
    std::array<Impl*, MaxManagerCount> m_pool_managers_tail{};
    std::array<Impl, MaxManagerCount> m_managers;
    size_t m_num_managers{};
    PoolArray<u64> m_optimized_process_ids{};
    PoolArray<bool> m_has_optimized_process{};
};

} // namespace Kernel
