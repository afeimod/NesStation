// SPDX-FileCopyrightText: Copyright 2026 Eden Emulator Project
// SPDX-License-Identifier: GPL-3.0-or-later

// SPDX-FileCopyrightText: 2022 yuzu Emulator Project
// SPDX-FileCopyrightText: 2022 Skyline Team and Contributors
// SPDX-License-Identifier: GPL-3.0-or-later

#include <algorithm>
#include <functional>

#include "common/alignment.h"
#include "common/assert.h"
#include "common/logging.h"
#include "core/hle/service/nvdrv/core/container.h"
#include "core/hle/service/nvdrv/core/heap_mapper.h"
#include "core/hle/service/nvdrv/core/nvmap.h"
#include "core/memory.h"
#include "video_core/host1x/host1x.h"

using Core::Memory::YUZU_PAGESIZE;
constexpr size_t BIG_PAGE_SIZE = YUZU_PAGESIZE * 16;

namespace Service::Nvidia::NvCore {
NvMap::Handle::Handle(u64 size_, Id id_)
    : size(size_), aligned_size(size), orig_size(size), id(id_) {
    flags.raw = 0;
}

NvResult NvMap::Handle::Alloc(Flags pFlags, u32 pAlign, u8 pKind, u64 pAddress,
                              NvCore::SessionId pSessionId) {
    std::scoped_lock lock(mutex);
    // Handles cannot be allocated twice
    if (allocated) {
        return NvResult::AccessDenied;
    }

    flags = pFlags;
    kind = pKind;
    align = pAlign < YUZU_PAGESIZE ? YUZU_PAGESIZE : pAlign;
    session_id = pSessionId;

    // This flag is only applicable for handles with an address passed
    if (pAddress) {
        flags.keep_uncached_after_free.Assign(0);
    } else {
        LOG_CRITICAL(Service_NVDRV,
                     "Mapping nvmap handles without a CPU side address is unimplemented!");
    }

    size = Common::AlignUp(size, YUZU_PAGESIZE);
    aligned_size = Common::AlignUp(size, align);
    address = pAddress;
    allocated = true;

    return NvResult::Success;
}

NvResult NvMap::Handle::Duplicate(bool internal_session) {
    std::scoped_lock lock(mutex);
    // Unallocated handles cannot be duplicated as duplication requires memory accounting (in HOS)
    if (!allocated) [[unlikely]] {
        return NvResult::BadValue;
    }

    // If we internally use FromId the duplication tracking of handles won't work accurately due to
    // us not implementing per-process handle refs.
    if (internal_session) {
        internal_dupes++;
    } else {
        dupes++;
    }

    return NvResult::Success;
}

NvMap::NvMap(Container& core_, Tegra::Host1x::Host1x& host1x_) : host1x{host1x_}, core{core_} {}

void NvMap::AddHandle(std::shared_ptr<Handle> handle_description) {
    std::scoped_lock lock(handles_lock);

    handles.emplace(handle_description->id, std::move(handle_description));
}

void NvMap::UnmapHandle(Handle& handle_description) {
    // Remove pending unmap queue entry if needed
    if (handle_description.unmap_queue_entry) {
        unmap_queue.erase(*handle_description.unmap_queue_entry);
        handle_description.unmap_queue_entry.reset();
    }

    // Free and unmap the handle from Host1x GMMU
    if (handle_description.pin_virt_address) {
        host1x.gmmu_manager.Unmap(static_cast<GPUVAddr>(handle_description.pin_virt_address),
                            handle_description.aligned_size);
        host1x.Allocator().Free(handle_description.pin_virt_address,
                                static_cast<u32>(handle_description.aligned_size));
        handle_description.pin_virt_address = 0;
    }

    // Free and unmap the handle from the SMMU
    const size_t map_size = handle_description.aligned_size;
    if (!handle_description.in_heap) {
        auto& smmu = host1x.MemoryManager();
        size_t aligned_up = Common::AlignUp(map_size, BIG_PAGE_SIZE);
        smmu.Unmap(handle_description.d_address, map_size);
        smmu.Free(handle_description.d_address, static_cast<size_t>(aligned_up));
        handle_description.d_address = 0;
        return;
    }
    const VAddr vaddress = handle_description.address;
    auto* session = core.GetSession(handle_description.session_id);
    session->mapper->Unmap(vaddress, map_size);
    handle_description.d_address = 0;
    handle_description.in_heap = false;
}

bool NvMap::TryRemoveHandle(const Handle& handle_description) {
    // No dupes left, we can remove from handle map
    if (handle_description.dupes == 0 && handle_description.internal_dupes == 0) {
        std::scoped_lock lock(handles_lock);

        auto it{handles.find(handle_description.id)};
        if (it != handles.end()) {
            handles.erase(it);
        }

        return true;
    } else {
        return false;
    }
}

NvResult NvMap::CreateHandle(u64 size, std::shared_ptr<NvMap::Handle>& result_out) {
    if (!size) [[unlikely]] {
        return NvResult::BadValue;
    }

    u32 id{next_handle_id.fetch_add(HandleIdIncrement, std::memory_order_relaxed)};
    auto handle_description{std::make_shared<Handle>(size, id)};
    AddHandle(handle_description);

    result_out = handle_description;
    return NvResult::Success;
}

std::shared_ptr<NvMap::Handle> NvMap::GetHandle(Handle::Id handle) {
    std::scoped_lock lock(handles_lock);
    try {
        return handles.at(handle);
    } catch (std::out_of_range&) {
        return nullptr;
    }
}

DAddr NvMap::GetHandleAddress(Handle::Id handle) {
    std::scoped_lock lock(handles_lock);
    try {
        return handles.at(handle)->d_address;
    } catch (std::out_of_range&) {
        return 0;
    }
}

DAddr NvMap::PinHandle(NvMap::Handle::Id handle, bool low_area_pin) {
    auto handle_description{GetHandle(handle)};
    if (!handle_description) [[unlikely]] {
        return 0;
    }

    std::scoped_lock lock(handle_description->mutex);
    const auto map_low_area = [&] {
        if (handle_description->pin_virt_address == 0) {
            u32 address = host1x.Allocator().Allocate(u32(handle_description->aligned_size));
            host1x.gmmu_manager.Map(GPUVAddr(address), handle_description->d_address, handle_description->aligned_size);
            handle_description->pin_virt_address = address;
        }
    };
    if (!handle_description->pins) {
        // If we're in the unmap queue we can just remove ourselves and return since we're already
        // mapped
        {
            // Lock now to prevent our queue entry from being removed for allocation in-between the
            // following check and erase
            std::scoped_lock queueLock(unmap_queue_lock);
            if (handle_description->unmap_queue_entry) {
                unmap_queue.erase(*handle_description->unmap_queue_entry);
                handle_description->unmap_queue_entry.reset();

                if (low_area_pin) {
                    map_low_area();
                    handle_description->pins++;
                    return static_cast<DAddr>(handle_description->pin_virt_address);
                }

                handle_description->pins++;
                return handle_description->d_address;
            }
        }

        using namespace std::placeholders;
        // If not then allocate some space and map it
        DAddr address{};
        auto& smmu = host1x.MemoryManager();
        auto* session = core.GetSession(handle_description->session_id);
        const VAddr vaddress = handle_description->address;
        const size_t map_size = handle_description->aligned_size;
        if (session->has_preallocated_area && session->mapper->IsInBounds(vaddress, map_size)) {
            handle_description->d_address = session->mapper->Map(vaddress, map_size);
            handle_description->in_heap = true;
        } else {
            size_t aligned_up = Common::AlignUp(map_size, BIG_PAGE_SIZE);
            while ((address = smmu.Allocate(aligned_up)) == 0) {
                // Free handles until the allocation succeeds
                std::scoped_lock queueLock(unmap_queue_lock);
                if (auto freeHandleDesc{unmap_queue.front()}) {
                    // Handles in the unmap queue are guaranteed not to be pinned so don't bother
                    // checking if they are before unmapping
                    std::scoped_lock freeLock(freeHandleDesc->mutex);
                    if (handle_description->d_address)
                        UnmapHandle(*freeHandleDesc);
                } else {
                    LOG_CRITICAL(Service_NVDRV, "Ran out of SMMU address space!");
                }
            }

            handle_description->d_address = address;
            smmu.Map(address, vaddress, map_size, session->asid, true);
            handle_description->in_heap = false;
        }
    }

    if (low_area_pin) {
        map_low_area();
    }

    handle_description->pins++;
    if (low_area_pin) {
        return static_cast<DAddr>(handle_description->pin_virt_address);
    }
    return handle_description->d_address;
}

void NvMap::UnpinHandle(Handle::Id handle) {
    auto handle_description{GetHandle(handle)};
    if (!handle_description) {
        return;
    }

    std::scoped_lock lock(handle_description->mutex);
    if (--handle_description->pins < 0) {
        LOG_WARNING(Service_NVDRV, "Pin count imbalance detected!");
    } else if (!handle_description->pins) {
        std::scoped_lock queueLock(unmap_queue_lock);

        // Add to the unmap queue allowing this handle's memory to be freed if needed
        unmap_queue.push_back(handle_description);
        handle_description->unmap_queue_entry = std::prev(unmap_queue.end());
    }
}

void NvMap::DuplicateHandle(Handle::Id handle, bool internal_session) {
    auto handle_description{GetHandle(handle)};
    if (!handle_description) {
        LOG_CRITICAL(Service_NVDRV, "Unregistered handle!");
        return;
    }

    auto result = handle_description->Duplicate(internal_session);
    if (result != NvResult::Success) {
        LOG_CRITICAL(Service_NVDRV, "Could not duplicate handle!");
    }
}

std::optional<NvMap::FreeInfo> NvMap::FreeHandle(Handle::Id handle, bool internal_session) {
    std::weak_ptr<Handle> hWeak{GetHandle(handle)};
    FreeInfo freeInfo;

    // We use a weak ptr here so we can tell when the handle has been freed and report that back to
    // guest
    if (auto handle_description = hWeak.lock()) {
        std::scoped_lock lock(handle_description->mutex);

        if (internal_session) {
            if (--handle_description->internal_dupes < 0)
                LOG_WARNING(Service_NVDRV, "Internal duplicate count imbalance detected!");
        } else {
            if (--handle_description->dupes < 0) {
                LOG_WARNING(Service_NVDRV, "User duplicate count imbalance detected!");
            } else if (handle_description->dupes == 0) {
                // Force unmap the handle
                if (handle_description->d_address) {
                    std::scoped_lock queueLock(unmap_queue_lock);
                    UnmapHandle(*handle_description);
                }

                handle_description->pins = 0;
            }
        }

        // Try to remove the shared ptr to the handle from the map, if nothing else is using the
        // handle then it will now be freed when `handle_description` goes out of scope
        if (TryRemoveHandle(*handle_description)) {
            LOG_DEBUG(Service_NVDRV, "Removed nvmap handle: {}", handle);
        } else {
            LOG_DEBUG(Service_NVDRV,
                      "Tried to free nvmap handle: {} but didn't as it still has duplicates",
                      handle);
        }

        freeInfo = {
            .address = handle_description->address,
            .size = handle_description->size,
            .was_uncached = handle_description->flags.map_uncached.Value() != 0,
            .can_unlock = true,
        };
    } else {
        return std::nullopt;
    }

    // If the handle hasn't been freed from memory, mark that
    if (!hWeak.expired()) {
        LOG_DEBUG(Service_NVDRV, "nvmap handle: {} wasn't freed as it is still in use", handle);
        freeInfo.can_unlock = false;
    }

    return freeInfo;
}

void NvMap::UnmapAllHandles(NvCore::SessionId session_id) {
    auto handles_copy = [&] {
        std::scoped_lock lk{handles_lock};
        return handles;
    }();

    for (auto& [id, handle] : handles_copy) {
        {
            std::scoped_lock lk{handle->mutex};
            if (handle->session_id.id != session_id.id || handle->dupes <= 0) {
                continue;
            }
        }
        FreeHandle(id, false);
    }
}

// ---------------------------------------------------------------------------
// ★★★ NesStation savestate：nvmap 句柄表快照 / 回放 ★★★
// ---------------------------------------------------------------------------

void NvMap::SaveHandles(std::vector<HandleStateRecord>& out, u32& next_id_out) {
    out.clear();
    next_id_out = next_handle_id.load(std::memory_order_relaxed);

    auto handles_copy = [&] {
        std::scoped_lock lk{handles_lock};
        return handles;
    }();
    out.reserve(handles_copy.size());

    for (const auto& [id, handle] : handles_copy) {
        std::scoped_lock lk{handle->mutex};
        HandleStateRecord rec{};
        rec.orig_size = handle->orig_size;
        rec.size = handle->size;
        rec.aligned_size = handle->aligned_size;
        rec.align = handle->align;
        rec.address = handle->address;
        rec.d_address = handle->d_address;
        rec.id = handle->id;
        rec.kind = handle->kind;
        rec.flags_raw = handle->flags.raw;
        rec.dupes = handle->dupes;
        rec.internal_dupes = handle->internal_dupes;
        rec.pins = handle->pins;
        rec.pin_virt_address = handle->pin_virt_address;
        rec.session_id = static_cast<u32>(handle->session_id.id);
        rec.in_heap = handle->in_heap ? 1u : 0u;
        rec.allocated = handle->allocated ? 1u : 0u;
        out.push_back(rec);
    }
}

void NvMap::RestoreHandles(const std::vector<HandleStateRecord>& in, u32 saved_next_id) {
    // ★ v5：拆为 Teardown / Replay 两段 —— 页表手术需要插在中间：
    //   Teardown 先拆掉全部当前设备映射（清 DeviceShared 属性，页表才能整表
    //   卸载重建）；手术完成后 Replay 再按存档重新 SMMU/GMMU/低位 pin。
    RestoreHandlesTeardown(in);
    RestoreHandlesReplay(in, saved_next_id);
}

void NvMap::RestoreHandlesTeardown(const std::vector<HandleStateRecord>& in) {
    auto& smmu = host1x.MemoryManager();

    // 1) 拆除当前全部句柄的设备映射（SMMU 分配 / heap 映射 / 低位 pin），
    //    与 UnmapHandle 的拆除序列一致；同时摘除待释放队列项。
    {
        auto handles_copy = [&] {
            std::scoped_lock lk{handles_lock};
            return handles;
        }();
        for (auto& [id, handle] : handles_copy) {
            std::scoped_lock lk{handle->mutex};
            if (handle->unmap_queue_entry) {
                std::scoped_lock queue_lock{unmap_queue_lock};
                if (handle->unmap_queue_entry) {
                    unmap_queue.erase(*handle->unmap_queue_entry);
                    handle->unmap_queue_entry.reset();
                }
            }
            if (handle->pin_virt_address) {
                host1x.gmmu_manager.Unmap(static_cast<GPUVAddr>(handle->pin_virt_address),
                                          handle->aligned_size);
                host1x.Allocator().Free(handle->pin_virt_address,
                                        static_cast<u32>(handle->aligned_size));
                handle->pin_virt_address = 0;
            }
            if (handle->d_address) {
                if (!handle->in_heap) {
                    const size_t aligned_up = Common::AlignUp(handle->aligned_size, BIG_PAGE_SIZE);
                    smmu.Unmap(handle->d_address, handle->aligned_size);
                    smmu.Free(handle->d_address, aligned_up);
                } else if (auto* session = core.GetSession(handle->session_id);
                           session != nullptr && session->mapper) {
                    session->mapper->Unmap(handle->address, handle->aligned_size);
                }
                handle->d_address = 0;
                handle->in_heap = false;
            }
        }
    }

    // 2) 丢弃存档之后新建的句柄（不在快照集合内）。
    {
        std::vector<u32> saved_ids;
        saved_ids.reserve(in.size());
        for (const auto& rec : in) {
            saved_ids.push_back(rec.id);
        }
        std::sort(saved_ids.begin(), saved_ids.end());

        std::scoped_lock lk{handles_lock};
        for (auto it = handles.begin(); it != handles.end();) {
            if (!std::binary_search(saved_ids.begin(), saved_ids.end(), it->first)) {
                it = handles.erase(it);
            } else {
                ++it;
            }
        }
    }
}

void NvMap::RestoreHandlesReplay(const std::vector<HandleStateRecord>& in, u32 saved_next_id) {
    auto& smmu = host1x.MemoryManager();

    // 3) 按快照重建句柄字段与设备映射（与 PinHandle 的映射序列一致：
    //    非 heap → SMMU AllocateFixed + Map（track）；heap → mapper->Map）。
    //    ★ v5：必须在页表手术之后调用 —— smmu.Map / mapper->Map 会锁
    //    进程页表并设置 DeviceShared 属性，指向手术后的新（＝存档时刻）映射。
    for (const auto& rec : in) {
        std::shared_ptr<Handle> handle = GetHandle(rec.id);
        if (!handle) {
            handle = std::make_shared<Handle>(rec.size, rec.id);
            AddHandle(handle);
        }
        std::scoped_lock lk{handle->mutex};
        handle->orig_size = rec.orig_size;
        handle->size = rec.size;
        handle->aligned_size = rec.aligned_size;
        handle->align = rec.align;
        handle->address = rec.address;
        handle->id = rec.id;
        handle->kind = static_cast<u8>(rec.kind);
        handle->flags.raw = rec.flags_raw;
        handle->dupes = rec.dupes;
        handle->internal_dupes = rec.internal_dupes;
        handle->pins = rec.pins;
        handle->session_id = NvCore::SessionId{static_cast<size_t>(rec.session_id)};
        handle->allocated = rec.allocated != 0;
        handle->in_heap = false;
        handle->d_address = 0;
        handle->pin_virt_address = 0;
        handle->unmap_queue_entry.reset();

        if (rec.d_address) {
            auto* session = core.GetSession(handle->session_id);
            if (session == nullptr) {
                LOG_WARNING(Service_NVDRV,
                            "savestate: session {} missing, handle {} mapping skipped",
                            rec.session_id, rec.id);
            } else if (rec.in_heap) {
                if (session->mapper) {
                    // heap 路径：HeapMapper 为确定性偏移算术，重放得到相同 DAddr
                    handle->d_address = session->mapper->Map(handle->address, handle->aligned_size);
                    handle->in_heap = handle->d_address != 0;
                }
            } else {
                const size_t aligned_up = Common::AlignUp(handle->aligned_size, BIG_PAGE_SIZE);
                smmu.AllocateFixed(rec.d_address, aligned_up);
                smmu.Map(rec.d_address, handle->address, handle->aligned_size, session->asid,
                         true);
                handle->d_address = rec.d_address;
                handle->in_heap = false;
            }
        }

        if (rec.pin_virt_address) {
            host1x.Allocator().AllocateFixed(rec.pin_virt_address,
                                             static_cast<u32>(handle->aligned_size));
            handle->pin_virt_address = rec.pin_virt_address;
            if (handle->d_address) {
                host1x.gmmu_manager.Map(static_cast<GPUVAddr>(rec.pin_virt_address),
                                        handle->d_address, handle->aligned_size);
            }
        }
    }

    // 4) 恢复句柄 id 序列（对残留的高位 id 保守取 max）。
    u32 next_id = saved_next_id;
    {
        std::scoped_lock lk{handles_lock};
        for (const auto& [id, handle] : handles) {
            next_id = std::max<u32>(next_id, id + HandleIdIncrement);
        }
    }
    next_handle_id.store(next_id, std::memory_order_relaxed);
}

} // namespace Service::Nvidia::NvCore
