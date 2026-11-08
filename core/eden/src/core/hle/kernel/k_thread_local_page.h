// SPDX-FileCopyrightText: Copyright 2025 Eden Emulator Project
// SPDX-License-Identifier: GPL-3.0-or-later

// SPDX-FileCopyrightText: Copyright 2022 yuzu Emulator Project
// SPDX-License-Identifier: GPL-2.0-or-later

#pragma once

#include <algorithm>
#include <array>
#include <memory>

#include "common/alignment.h"
#include "common/assert.h"
#include "common/common_types.h"
#include "common/intrusive_red_black_tree.h"
#include <ranges>
#include "core/hle/kernel/memory_types.h"
#include "core/hle/kernel/slab_helpers.h"
#include "core/hle/result.h"

namespace Kernel {

class KernelCore;
class KProcess;

class KThreadLocalPage final : public Common::IntrusiveRedBlackTreeBaseNode<KThreadLocalPage>,
                               public KSlabAllocated<KThreadLocalPage> {
public:
    static constexpr size_t RegionsPerPage = PageSize / Svc::ThreadLocalRegionSize;
    static_assert(RegionsPerPage > 0);

public:
    constexpr explicit KThreadLocalPage(KernelCore&, KProcessAddress addr = {})
        : m_virt_addr(addr) {
        m_is_region_free.fill(true);
    }

    constexpr KProcessAddress GetAddress() const {
        return m_virt_addr;
    }

    Result Initialize(KernelCore& kernel, KProcess* process);
    Result Finalize();

    KProcessAddress Reserve();
    void Release(KProcessAddress addr);

    bool IsAllUsed() const {
        return std::ranges::all_of(m_is_region_free.begin(), m_is_region_free.end(),
                                   [](bool is_free) { return !is_free; });
    }

    bool IsAllFree() const {
        return std::ranges::all_of(m_is_region_free.begin(), m_is_region_free.end(),
                                   [](bool is_free) { return is_free; });
    }

    bool IsAnyUsed() const {
        return !this->IsAllFree();
    }

    bool IsAnyFree() const {
        return !this->IsAllUsed();
    }

public:
    using RedBlackKeyType = KProcessAddress;

    static constexpr RedBlackKeyType GetRedBlackKey(const RedBlackKeyType& v) {
        return v;
    }
    static constexpr RedBlackKeyType GetRedBlackKey(const KThreadLocalPage& v) {
        return v.GetAddress();
    }

    template <typename T>
        requires(std::same_as<T, KThreadLocalPage> || std::same_as<T, RedBlackKeyType>)
    static constexpr int Compare(const T& lhs, const KThreadLocalPage& rhs) {
        const KProcessAddress lval = GetRedBlackKey(lhs);
        const KProcessAddress rval = GetRedBlackKey(rhs);

        if (lval < rval) {
            return -1;
        } else if (lval == rval) {
            return 0;
        } else {
            return 1;
        }
    }

private:
    constexpr KProcessAddress GetRegionAddress(size_t i) const {
        return this->GetAddress() + i * Svc::ThreadLocalRegionSize;
    }

    constexpr bool Contains(KProcessAddress addr) const {
        return this->GetAddress() <= addr && addr < this->GetAddress() + PageSize;
    }

    constexpr size_t GetRegionIndex(KProcessAddress addr) const {
        ASSERT(Common::IsAligned(GetInteger(addr), Svc::ThreadLocalRegionSize));
        ASSERT(this->Contains(addr));
        return (addr - this->GetAddress()) / Svc::ThreadLocalRegionSize;
    }

public:
    /// ★★★ NesStation savestate (v6)：按存档直接设定页内槽位占用 ★★★
    /// 读档后 TLS 树整表重建（KProcess::SavestateRebuildTlsState）—— 页的
    /// 虚拟地址与映射由页表手术恢复，这里只重建宿主册簿（哪些槽在用）。
    constexpr void SavestateSetRegionUsage(const std::array<bool, RegionsPerPage>& used) {
        for (size_t i = 0; i < RegionsPerPage; ++i) {
            m_is_region_free[i] = !used[i];
        }
    }

    constexpr bool SavestateIsRegionUsed(size_t i) const {
        return i < RegionsPerPage && !m_is_region_free[i];
    }

    /// ★★★ NesStation savestate (v6)：以既有地址构造册簿对象（不映射新页） ★★★
    static KThreadLocalPage* SavestateCreateForAddress(KernelCore& kernel, KProcess* owner,
                                                       KProcessAddress addr) {
        KThreadLocalPage* tlp = KThreadLocalPage::Allocate(kernel);
        if (tlp == nullptr) {
            return nullptr;
        }
        KThreadLocalPage* p = std::construct_at(tlp, kernel, addr);
        p->m_owner = owner;
        p->m_kernel = std::addressof(kernel);
        return p;
    }

private:
    KProcessAddress m_virt_addr{};
    KProcess* m_owner{};
    KernelCore* m_kernel{};
    std::array<bool, RegionsPerPage> m_is_region_free{};
};

} // namespace Kernel
