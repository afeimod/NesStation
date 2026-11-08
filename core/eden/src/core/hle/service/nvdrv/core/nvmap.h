// SPDX-FileCopyrightText: Copyright 2026 Eden Emulator Project
// SPDX-License-Identifier: GPL-3.0-or-later

// SPDX-FileCopyrightText: 2022 yuzu Emulator Project
// SPDX-FileCopyrightText: 2022 Skyline Team and Contributors
// SPDX-License-Identifier: GPL-3.0-or-later

#pragma once

#include <atomic>
#include <list>
#include <memory>
#include <mutex>
#include <optional>
#include <vector>
#include "common/container/unordered_map.h"
#include <assert.h>

#include "common/bit_field.h"
#include "common/common_types.h"
#include "core/hle/service/nvdrv/core/container.h"
#include "core/hle/service/nvdrv/nvdata.h"

namespace Tegra {

namespace Host1x {
class Host1x;
} // namespace Host1x

} // namespace Tegra

namespace Service::Nvidia::NvCore {

class Container;
/**
 * @brief The nvmap core class holds the global state for nvmap and provides methods to manage
 * handles
 */
class NvMap {
public:
    /**
     * @brief A handle to a contiguous block of memory in an application's address space
     */
    struct Handle {
        std::mutex mutex;

        u64 align{};      //!< The alignment to use when pinning the handle onto the SMMU
        u64 size;         //!< Page-aligned size of the memory the handle refers to
        u64 aligned_size; //!< `align`-aligned size of the memory the handle refers to
        u64 orig_size;    //!< Original unaligned size of the memory this handle refers to

        s32 dupes{1};          //!< How many guest references there are to this handle
        s32 internal_dupes{0}; //!< How many emulator-internal references there are to this handle

        using Id = u32;
        Id id; //!< A globally unique identifier for this handle

        s64 pins{};
        u32 pin_virt_address{};
        std::optional<typename std::list<std::shared_ptr<Handle>>::iterator> unmap_queue_entry{};

        union Flags {
            u32 raw;
            BitField<0, 1, u32> map_uncached; //!< If the handle should be mapped as uncached
            BitField<2, 1, u32> keep_uncached_after_free; //!< Only applicable when the handle was
                                                          //!< allocated with a fixed address
            BitField<4, 1, u32> _unk0_;                   //!< Passed to IOVMM for pins
        } flags{};
        static_assert(sizeof(Flags) == sizeof(u32));

        VAddr address{}; //!< The memory location in the guest's AS that this handle corresponds to,
                         //!< this can also be in the nvdrv tmem
        bool is_shared_mem_mapped{}; //!< If this nvmap has been mapped with the MapSharedMem IPC
                                     //!< call

        u8 kind{};        //!< Used for memory compression
        bool allocated{}; //!< If the handle has been allocated with `Alloc`
        bool in_heap{};
        NvCore::SessionId session_id{};

        DAddr d_address{}; //!< The memory location in the device's AS that this handle corresponds
                           //!< to, this can also be in the nvdrv tmem

        Handle(u64 size, Id id);

        /**
         * @brief Sets up the handle with the given memory config, can allocate memory from the tmem
         * if a 0 address is passed
         */
        [[nodiscard]] NvResult Alloc(Flags pFlags, u32 pAlign, u8 pKind, u64 pAddress,
                                     NvCore::SessionId pSessionId);

        /**
         * @brief Increases the dupe counter of the handle for the given session
         */
        [[nodiscard]] NvResult Duplicate(bool internal_session);

        /**
         * @brief Obtains a pointer to the handle's memory and marks the handle it as having been
         * mapped
         */
        u8* GetPointer() {
            if (!address) {
                return nullptr;
            }

            is_shared_mem_mapped = true;
            return reinterpret_cast<u8*>(address);
        }
    };

    /**
     * @brief Encapsulates the result of a FreeHandle operation
     */
    struct FreeInfo {
        u64 address;       //!< Address the handle referred to before deletion
        u64 size;          //!< Page-aligned handle size
        bool was_uncached; //!< If the handle was allocated as uncached
        bool can_unlock;   //!< If the address region is ready to be unlocked
    };

    explicit NvMap(Container& core, Tegra::Host1x::Host1x& host1x);

    /**
     * @brief Creates an unallocated handle of the given size
     */
    [[nodiscard]] NvResult CreateHandle(u64 size, std::shared_ptr<NvMap::Handle>& result_out);

    std::shared_ptr<Handle> GetHandle(Handle::Id handle);

    DAddr GetHandleAddress(Handle::Id handle);

    /**
     * @brief Maps a handle into the SMMU address space
     * @note This operation is refcounted, the number of calls to this must eventually match the
     * number of calls to `UnpinHandle`
     * @return The SMMU virtual address that the handle has been mapped to
     */
    DAddr PinHandle(Handle::Id handle, bool low_area_pin);

    /**
     * @brief When this has been called an equal number of times to `PinHandle` for the supplied
     * handle it will be added to a list of handles to be freed when necessary
     */
    void UnpinHandle(Handle::Id handle);

    /**
     * @brief Tries to duplicate a handle
     */
    void DuplicateHandle(Handle::Id handle, bool internal_session = false);

    /**
     * @brief Tries to free a handle and remove a single dupe
     * @note If a handle has no dupes left and has no other users a FreeInfo struct will be returned
     * describing the prior state of the handle
     */
    std::optional<FreeInfo> FreeHandle(Handle::Id handle, bool internal_session);

    void UnmapAllHandles(NvCore::SessionId session_id);

    // ★★★ NesStation savestate：nvmap 句柄表快照 / 回放 ★★★
    //
    // nvmap 句柄表（含每个句柄的 SMMU 设备地址分配与 DAddr→guest VA 映射）
    // 是宿主侧 HLE 状态，回滚 guest 内存不会回滚它。快照/回放保证读档后
    // GPU 经 GMMU→SMMU→guest RAM 的两级翻译与回滚后的 guest 驱动一致。
    struct HandleStateRecord {
        u64 orig_size;
        u64 size;              // 页对齐大小
        u64 aligned_size;      // align 对齐大小
        u64 align;
        VAddr address;         // guest VA（0 = 无）
        DAddr d_address;       // 设备 AS 地址（0 = 从未 pin）
        u32 id;
        u32 kind;
        u32 flags_raw;
        s32 dupes;
        s32 internal_dupes;
        s32 pins;
        u32 pin_virt_address;  // host1x 低位 GPU VA pin（0 = 无）
        u32 session_id;
        u32 in_heap;           // 0/1
        u32 allocated;         // 0/1
    };
    static_assert(sizeof(HandleStateRecord) == 88);

    /// 快照当前全部句柄（含设备映射字段）与 id 序列。
    void SaveHandles(std::vector<HandleStateRecord>& out, u32& next_id_out);

    /// 原地回放：拆除当前全部设备映射 → 丢弃存档后新建的句柄 → 按存档
    /// 字段重建/覆盖句柄 → 在原设备地址重建 SMMU 分配与映射、heap 映射、
    /// 低位 pin → 恢复 id 序列。需在 EmulationFreeze（GPU 排空、guest 暂停）
    /// 下调用。
    void RestoreHandles(const std::vector<HandleStateRecord>& in, u32 saved_next_id);

    /// ★ v5：RestoreHandles 的前半段（拆除当前设备映射 + 丢弃存档后新建的
    /// 句柄）。清空 DeviceShared 页表属性后，进程页表才能整表卸载重建 ——
    /// 页表手术（native_savestate v5）需要插在本段与 Replay 之间执行。
    void RestoreHandlesTeardown(const std::vector<HandleStateRecord>& in);

    /// ★ v5：RestoreHandles 的后半段（按存档重建句柄与 SMMU/GMMU/低位
    /// pin）。必须在页表手术之后调用 —— 设备映射指向手术后的新映射。
    void RestoreHandlesReplay(const std::vector<HandleStateRecord>& in, u32 saved_next_id);

private:
    std::list<std::shared_ptr<Handle>> unmap_queue{};
    std::mutex unmap_queue_lock{}; //!< Protects access to `unmap_queue`

    ::Common::unordered_map<Handle::Id, std::shared_ptr<Handle>>
        handles{};           //!< Main owning map of handles
    std::mutex handles_lock; //!< Protects access to `handles`

    static constexpr u32 HandleIdIncrement{
        4}; //!< Each new handle ID is an increment of 4 from the previous
    std::atomic<u32> next_handle_id{HandleIdIncrement};
    Tegra::Host1x::Host1x& host1x;

    void AddHandle(std::shared_ptr<Handle> handle);

    /**
     * @brief Unmaps and frees the SMMU memory region a handle is mapped to
     * @note Both `unmap_queue_lock` and `handle_description.mutex` MUST be locked when calling this
     */
    void UnmapHandle(Handle& handle_description);

    /**
     * @brief Removes a handle from the map taking its dupes into account
     * @note handle_description.mutex MUST be locked when calling this
     * @return If the handle was removed from the map
     */
    bool TryRemoveHandle(const Handle& handle_description);

    Container& core;
};
} // namespace Service::Nvidia::NvCore
