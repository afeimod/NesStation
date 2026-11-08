// SPDX-FileCopyrightText: Copyright 2026 Eden Emulator Project
// SPDX-License-Identifier: GPL-3.0-or-later

// SPDX-FileCopyrightText: Copyright 2021 yuzu Emulator Project
// SPDX-License-Identifier: GPL-2.0-or-later

#pragma once

#include <array>

#include "common/assert.h"
#include "common/bit_field.h"
#include "common/common_types.h"
#include "core/hle/kernel/k_auto_object.h"
#include "core/hle/kernel/k_spin_lock.h"
#include "core/hle/kernel/k_thread.h"
#include "core/hle/kernel/kernel.h"
#include "core/hle/kernel/svc_common.h"
#include "core/hle/kernel/svc_results.h"
#include "core/hle/result.h"

namespace Kernel {

class KernelCore;

class KHandleTable {
    YUZU_NON_COPYABLE(KHandleTable);
    YUZU_NON_MOVEABLE(KHandleTable);

public:
    static constexpr size_t MaxTableSize = 1024;

public:
    explicit KHandleTable(KernelCore& kernel) {}

    Result Initialize(KernelCore& kernel, s32 size) {
        // Check that the table size is valid.
        R_UNLESS(size <= static_cast<s32>(MaxTableSize), ResultOutOfMemory);

        // Lock.
        KScopedDisableDispatch dd{kernel};
        KScopedSpinLock lk(m_lock);

        // Initialize all fields.
        m_max_count = 0;
        m_table_size = static_cast<s16>((size <= 0) ? MaxTableSize : size);
        m_next_linear_id = MinLinearId;
        m_count = 0;
        m_free_head_index = -1;

        // Free all entries.
        for (s32 i = 0; i < static_cast<s32>(m_table_size); ++i) {
            m_objects[i] = nullptr;
            m_entry_infos[i].next_free_index = static_cast<s16>(i - 1);
            m_free_head_index = i;
        }

        R_SUCCEED();
    }

    size_t GetTableSize() const {
        return m_table_size;
    }
    size_t GetCount() const {
        return m_count;
    }
    size_t GetMaxCount() const {
        return m_max_count;
    }

    void Finalize(KernelCore& kernel);
    bool Remove(KernelCore& kernel, Handle handle);

    template <typename T = KAutoObject>
    KScopedAutoObject<T> GetObjectWithoutPseudoHandle(KernelCore& kernel, Handle handle) const {
        // Lock and look up in table.
        KScopedDisableDispatch dd{kernel};
        KScopedSpinLock lk(m_lock);

        if constexpr (std::is_same_v<T, KAutoObject>) {
            return {kernel, this->GetObjectImpl(handle)};
        } else {
            if (auto* obj = this->GetObjectImpl(handle); obj != nullptr) [[likely]] {
                return {kernel, obj->DynamicCast<T*>()};
            } else {
                return {kernel, nullptr};
            }
        }
    }

    template <typename T = KAutoObject>
    KScopedAutoObject<T> GetObject(KernelCore& kernel, Handle handle) const {
        // Handle pseudo-handles.
        if constexpr (std::derived_from<KProcess, T>) {
            if (handle == Svc::PseudoHandle::CurrentProcess) {
                auto* const cur_process = GetCurrentProcessPointer(kernel);
                ASSERT(cur_process != nullptr);
                return {kernel, cur_process};
            }
        } else if constexpr (std::derived_from<KThread, T>) {
            if (handle == Svc::PseudoHandle::CurrentThread) {
                auto* const cur_thread = GetCurrentThreadPointer(kernel);
                ASSERT(cur_thread != nullptr);
                return {kernel, cur_thread};
            }
        }
        return this->template GetObjectWithoutPseudoHandle<T>(kernel, handle);
    }

    KScopedAutoObject<KAutoObject> GetObjectForIpcWithoutPseudoHandle(KernelCore& kernel, Handle handle) const {
        // Lock and look up in table.
        KScopedDisableDispatch dd{kernel};
        KScopedSpinLock lk(m_lock);
        return {kernel, this->GetObjectImpl(handle)};
    }
    KScopedAutoObject<KAutoObject> GetObjectForIpc(KernelCore& kernel, Handle handle, KThread* cur_thread) const;
    KScopedAutoObject<KAutoObject> GetObjectByIndex(KernelCore& kernel, Handle* out_handle, size_t index) const {
        KScopedDisableDispatch dd{kernel};
        KScopedSpinLock lk(m_lock);

        return {kernel, this->GetObjectByIndexImpl(out_handle, index)};
    }

    Result Reserve(KernelCore& kernel, Handle* out_handle);
    void Unreserve(KernelCore& kernel, Handle handle);

    Result Add(KernelCore& kernel, Handle* out_handle, KAutoObject* obj);
    void Register(KernelCore& kernel, Handle handle, KAutoObject* obj);

    // ★★★ NesStation savestate (v6)：句柄表逃生舱（读档重建用） ★★★
// guest 内存中存有句柄值（互斥锁所有者句柄、事件句柄等），读档后句柄表
// 必须与存档时刻一致 —— 与 Cemu 随档恢复 SysAllocator 记账同构。
// 仅恢复线程对象句柄（其它内核对象图不随档，维持现状语义）。
public:
    /// 找到指向 obj 的第一个句柄（无则 0）。冻结下调用，无并发。
    Handle SavestateFindHandle(KAutoObject* obj) const {
        KScopedSpinLock lk(m_lock);
        for (s32 i = 0; i < static_cast<s32>(m_table_size); ++i) {
            if (m_objects[i] == obj) {
                return EncodeHandle(static_cast<u16>(i), m_entry_infos[i].GetLinearId());
            }
        }
        return 0;
    }

    /// 丢弃指向 obj 的全部句柄（被移除线程防悬挂）。
    void SavestatePurgeObject(KernelCore& kernel, KAutoObject* obj) {
        KScopedSpinLock lk(m_lock);
        for (s32 i = 0; i < static_cast<s32>(m_table_size); ++i) {
            if (m_objects[i] == obj) {
                // FreeEntry 需要计数/链表一致 —— 直接手写等价逻辑并归还引用
                m_objects[i] = nullptr;
                m_entry_infos[i].next_free_index = static_cast<s16>(m_free_head_index);
                m_free_head_index = i;
                --m_count;
                obj->Close(kernel);
            }
        }
    }

    /// 把 obj 放回存档句柄值（槽位被占则先归还旧对象）；取新引用。
    bool SavestateRestoreHandle(KernelCore& kernel, Handle handle, KAutoObject* obj) {
        if (handle == 0) {
            return false;
        }
        const auto handle_pack = HandlePack(handle);
        if (handle_pack.reserved != 0 || handle_pack.linear_id == 0) {
            return false;
        }
        const s32 index = static_cast<s32>(handle_pack.index);
        KScopedSpinLock lk(m_lock);
        if (index >= static_cast<s32>(m_table_size)) {
            return false;
        }
        if (m_objects[index] != nullptr) {
            // 槽位被废弃时间线的对象占用：归还它（槽位此刻既不在空闲链上，
            // 也不需要入链 —— 下面直接装入新对象）
            KAutoObject* old = m_objects[index];
            m_objects[index] = nullptr;
            old->Close(kernel);
        } else {
            // 空闲槽：计数 +1（槽位从空闲链上摘除，见下）
            m_count++;
            m_max_count = (std::max)(m_max_count, m_count);
        }
        // 若该槽仍在空闲链上（初始为空闲的情形），线性扫描摘链
        s32 prev = -1;
        for (s32 i = m_free_head_index; i >= 0;) {
            if (i == index) {
                const s16 next = m_entry_infos[i].GetNextFreeIndex();
                if (prev < 0) {
                    m_free_head_index = next;
                } else {
                    m_entry_infos[prev].next_free_index = next;
                }
                break;
            }
            prev = i;
            i = m_entry_infos[i].GetNextFreeIndex();
        }
        // 装入：linear_id 用存档值（句柄值与存档完全一致）
        m_objects[index] = obj;
        m_entry_infos[index].linear_id = static_cast<u16>(handle_pack.linear_id);
        obj->Open(kernel);
        return true;
    }

    template <typename T>
    bool GetMultipleObjects(KernelCore& kernel, T** out, const Handle* handles, size_t num_handles) const {
        // Try to convert and open all the handles.
        size_t num_opened;
        {
            // Lock the table.
            KScopedDisableDispatch dd{kernel};
            KScopedSpinLock lk(m_lock);
            for (num_opened = 0; num_opened < num_handles; num_opened++) {
                // Get the current handle.
                const auto cur_handle = handles[num_opened];

                // Get the object for the current handle.
                KAutoObject* cur_object = this->GetObjectImpl(cur_handle);
                if (cur_object == nullptr) [[unlikely]] {
                    break;
                }

                // Cast the current object to the desired type.
                T* cur_thread = cur_object->DynamicCast<T*>();
                if (cur_thread == nullptr) [[unlikely]] {
                    break;
                }

                // Open a reference to the current object.
                cur_thread->Open(kernel);
                out[num_opened] = cur_thread;
            }
        }

        // If we converted every object, succeed.
        if (num_opened == num_handles) [[likely]] {
            return true;
        }

        // If we didn't convert entry object, close the ones we opened.
        for (size_t i = 0; i < num_opened; i++) {
            out[i]->Close(kernel);
        }

        return false;
    }

private:
    s32 AllocateEntry() {
        ASSERT(m_count < m_table_size);
        const auto index = m_free_head_index;
        m_free_head_index = m_entry_infos[index].GetNextFreeIndex();
        m_max_count = (std::max)(m_max_count, ++m_count);
        return index;
    }

    void FreeEntry(s32 index) {
        ASSERT(m_count > 0);

        m_objects[index] = nullptr;
        m_entry_infos[index].next_free_index = static_cast<s16>(m_free_head_index);

        m_free_head_index = index;

        --m_count;
    }

    u16 AllocateLinearId() {
        const u16 id = m_next_linear_id++;
        if (m_next_linear_id > MaxLinearId) {
            m_next_linear_id = MinLinearId;
        }
        return id;
    }

    bool IsValidHandle(Handle handle) const {
        // Unpack the handle.
        const auto handle_pack = HandlePack(handle);
        const auto raw_value = handle_pack.raw;
        const auto index = handle_pack.index;
        const auto linear_id = handle_pack.linear_id;
        const auto reserved = handle_pack.reserved;
        ASSERT(reserved == 0);

        // Validate our indexing information.
        if (raw_value == 0) [[unlikely]] {
            return false;
        }
        if (linear_id == 0) [[unlikely]] {
            return false;
        }
        if (index >= m_table_size) [[unlikely]] {
            return false;
        }

        // Check that there's an object, and our serial id is correct.
        if (m_objects[index] == nullptr) [[unlikely]] {
            return false;
        }
        if (m_entry_infos[index].GetLinearId() != linear_id) [[unlikely]] {
            return false;
        }

        return true;
    }

    KAutoObject* GetObjectImpl(Handle handle) const {
        // Handles must not have reserved bits set.
        const auto handle_pack = HandlePack(handle);
        if (handle_pack.reserved != 0) [[unlikely]] {
            return nullptr;
        }

        if (this->IsValidHandle(handle)) [[likely]] {
            return m_objects[handle_pack.index];
        } else {
            return nullptr;
        }
    }

    KAutoObject* GetObjectByIndexImpl(Handle* out_handle, size_t index) const {
        // Index must be in bounds.
        if (index >= m_table_size) [[unlikely]] {
            return nullptr;
        }

        // Ensure entry has an object.
        if (KAutoObject* obj = m_objects[index]; obj != nullptr) {
            *out_handle = EncodeHandle(static_cast<u16>(index), m_entry_infos[index].GetLinearId());
            return obj;
        } else {
            return nullptr;
        }
    }

private:
    union HandlePack {
        constexpr HandlePack() = default;
        constexpr HandlePack(Handle handle) : raw{static_cast<u32>(handle)} {}

        u32 raw{};
        BitField<0, 15, u32> index;
        BitField<15, 15, u32> linear_id;
        BitField<30, 2, u32> reserved;
    };

    static constexpr Handle EncodeHandle(u16 index, u16 linear_id) {
        HandlePack handle{};
        handle.index.Assign(index);
        handle.linear_id.Assign(linear_id);
        handle.reserved.Assign(0);
        return handle.raw;
    }

private:
    static constexpr u16 MinLinearId = 1;
    static constexpr u16 MaxLinearId = 0x7FFF;

    union EntryInfo {
        u16 linear_id;
        s16 next_free_index;

        constexpr u16 GetLinearId() const {
            return linear_id;
        }
        constexpr s32 GetNextFreeIndex() const {
            return next_free_index;
        }
    };

private:
    std::array<EntryInfo, MaxTableSize> m_entry_infos{};
    std::array<KAutoObject*, MaxTableSize> m_objects{};
    mutable KSpinLock m_lock;
    s32 m_free_head_index{};
    u16 m_table_size{};
    u16 m_max_count{};
    u16 m_next_linear_id{};
    u16 m_count{};
};

} // namespace Kernel
