// SPDX-FileCopyrightText: Copyright 2026 Eden Emulator Project
// SPDX-License-Identifier: GPL-3.0-or-later

// SPDX-FileCopyrightText: Copyright 2021 yuzu Emulator Project
// SPDX-FileCopyrightText: Copyright 2014 The Android Open Source Project
// SPDX-License-Identifier: GPL-3.0-or-later
// Parts of this implementation were based on:
// https://cs.android.com/android/platform/superproject/+/android-5.1.1_r38:frameworks/native/libs/gui/BufferQueueCore.cpp

#include "common/assert.h"
#include <cstring>

#include "core/hle/service/nvnflinger/buffer_queue_core.h"

namespace Service::android {

BufferQueueCore::BufferQueueCore() = default;

BufferQueueCore::~BufferQueueCore() = default;

bool BufferQueueCore::SaveState(SavedState& state) const {
    std::scoped_lock lk{mutex};
    if (is_abandoned || is_allocating) return false;
    state = {};
    state.frame_counter = frame_counter;
    state.override_max_buffer_count = override_max_buffer_count;
    state.default_max_buffer_count = default_max_buffer_count;
    state.default_width = default_width;
    state.default_height = default_height;
    state.default_buffer_format = static_cast<u32>(default_buffer_format);
    state.consumer_usage_bit = consumer_usage_bit;
    state.transform_hint = transform_hint;
    state.flags = (buffer_has_been_queued ? 1u : 0u);
    for (size_t i = 0; i < slots.size(); ++i) {
        const auto& slot = slots[i];
        auto& saved = state.slots[i];
        saved.state = static_cast<u32>(slot.buffer_state);
        saved.frame_number = slot.frame_number;
        saved.flags = (slot.graphic_buffer ? 1u : 0u) |
                      (slot.request_buffer_called ? 2u : 0u) |
                      (slot.is_preallocated ? 4u : 0u) |
                      (slot.attached_by_consumer ? 8u : 0u);
        if (slot.graphic_buffer) saved.buffer = *slot.graphic_buffer;
    }
    return true;
}

bool BufferQueueCore::ValidateState(const SavedState& state) const {
    std::scoped_lock lk{mutex};
    if (is_abandoned || is_allocating || (state.flags & ~1u) != 0 ||
        state.override_max_buffer_count < 0 ||
        state.override_max_buffer_count > BufferQueueDefs::NUM_BUFFER_SLOTS ||
        state.default_max_buffer_count < 0 ||
        state.default_max_buffer_count > BufferQueueDefs::NUM_BUFFER_SLOTS) return false;
    for (size_t i = 0; i < slots.size(); ++i) {
        const auto& saved = state.slots[i];
        const auto& buffer = slots[i].graphic_buffer;
        if (saved.state > static_cast<u32>(BufferState::Acquired) ||
            (saved.flags & ~15u) != 0 ||
            ((saved.flags & 1u) != 0) != static_cast<bool>(buffer)) return false;
        if (buffer && std::memcmp(&saved.buffer,
                static_cast<const NvGraphicBuffer*>(buffer.get()), sizeof(NvGraphicBuffer)) != 0) {
            return false;
        }
    }
    return true;
}

void BufferQueueCore::RestoreState(const SavedState& state) {
    std::scoped_lock lk{mutex};
    // Retire host-owned frames; only the saved producer can still own a dequeue.
    // The compositor cache is discarded under the VI composition lock.
    queue.clear();
    frame_counter = state.frame_counter;
    override_max_buffer_count = state.override_max_buffer_count;
    default_max_buffer_count = state.default_max_buffer_count;
    default_width = state.default_width;
    default_height = state.default_height;
    default_buffer_format = static_cast<PixelFormat>(state.default_buffer_format);
    consumer_usage_bit = state.consumer_usage_bit;
    transform_hint = state.transform_hint;
    buffer_has_been_queued = (state.flags & 1u) != 0;
    for (size_t i = 0; i < slots.size(); ++i) {
        auto& slot = slots[i];
        const auto& saved = state.slots[i];
        slot.buffer_state = saved.state == static_cast<u32>(BufferState::Dequeued)
                                ? BufferState::Dequeued : BufferState::Free;
        slot.frame_number = saved.frame_number;
        slot.request_buffer_called = (saved.flags & 2u) != 0;
        slot.is_preallocated = (saved.flags & 4u) != 0;
        slot.attached_by_consumer = (saved.flags & 8u) != 0;
        slot.acquire_called = false;
        slot.needs_cleanup_on_release = false;
        slot.fence = Fence::NoFence();
    }
    {
        std::scoped_lock history_lock{buffer_history_mutex};
        buffer_history_map.clear();
        buffer_history_order.clear();
    }
    SignalDequeueCondition();
}

void BufferQueueCore::PushHistory(u64 frame_number, s64 queue_time, s64 presentation_time, BufferState state) {
    std::lock_guard lk(buffer_history_mutex);

    auto it = buffer_history_map.find(frame_number);
    if (it != buffer_history_map.end()) {
        it->second.state = state;
        return;
    }

    buffer_history_map.emplace(frame_number, BufferHistoryInfo{
        frame_number,
        queue_time,
        presentation_time,
        state
    });
    buffer_history_order.push_back(frame_number);

    if (buffer_history_order.size() > BUFFER_HISTORY_SIZE) {
        u64 oldest_frame = buffer_history_order.front();
        buffer_history_order.pop_front();
        buffer_history_map.erase(oldest_frame);
    }
}

void BufferQueueCore::UpdateHistory(u64 frame_number, BufferState state) {
    std::lock_guard lk(buffer_history_mutex);

    auto it = buffer_history_map.find(frame_number);
    if (it != buffer_history_map.end()) {
        it->second.state = state;
    }
}

void BufferQueueCore::SignalDequeueCondition() {
    dequeue_possible.store(true);
    dequeue_condition.notify_all();
}

bool BufferQueueCore::WaitForDequeueCondition(std::unique_lock<std::mutex>& lk) {
    dequeue_condition.wait(lk, [&] { return dequeue_possible.load(); });
    dequeue_possible.store(false);

    return true;
}

s32 BufferQueueCore::GetMinUndequeuedBufferCountLocked(bool async) const {
    if (!use_async_buffer) {
        return 0;
    }

    if (dequeue_buffer_cannot_block || async) {
        return max_acquired_buffer_count + 1;
    }

    return max_acquired_buffer_count;
}

s32 BufferQueueCore::GetMinMaxBufferCountLocked(bool async) const {
    return GetMinUndequeuedBufferCountLocked(async);
}

s32 BufferQueueCore::GetMaxBufferCountLocked(bool async) const {
    const auto min_buffer_count = GetMinMaxBufferCountLocked(async);
    auto max_buffer_count = std::max(default_max_buffer_count, min_buffer_count);

    if (override_max_buffer_count != 0) {
        ASSERT(override_max_buffer_count >= min_buffer_count);
        return override_max_buffer_count;
    }

    for (s32 slot = max_buffer_count; slot < BufferQueueDefs::NUM_BUFFER_SLOTS; ++slot) {
        const auto state = slots[slot].buffer_state;
        if (state == BufferState::Queued || state == BufferState::Dequeued) {
            max_buffer_count = slot + 1;
        }
    }

    return max_buffer_count;
}

s32 BufferQueueCore::GetPreallocatedBufferCountLocked() const {
    return static_cast<s32>(std::count_if(slots.begin(), slots.end(),
                                          [](const auto& slot) { return slot.is_preallocated; }));
}

void BufferQueueCore::FreeBufferLocked(s32 slot) {
    LOG_DEBUG(Service_Nvnflinger, "slot {}", slot);

    slots[slot].graphic_buffer.reset();

    if (slots[slot].buffer_state == BufferState::Acquired) {
        slots[slot].needs_cleanup_on_release = true;
    }

    slots[slot].buffer_state = BufferState::Free;
    slots[slot].frame_number = UINT32_MAX;
    slots[slot].acquire_called = false;
    slots[slot].fence = Fence::NoFence();
}

void BufferQueueCore::FreeAllBuffersLocked() {
    buffer_has_been_queued = false;

    for (s32 slot = 0; slot < BufferQueueDefs::NUM_BUFFER_SLOTS; ++slot) {
        FreeBufferLocked(slot);
    }
}

bool BufferQueueCore::StillTracking(const BufferItem& item) const {
    const BufferSlot& slot = slots[item.slot];

    return (slot.graphic_buffer != nullptr) && (item.graphic_buffer == slot.graphic_buffer);
}

void BufferQueueCore::WaitWhileAllocatingLocked() const {
    while (is_allocating) {
        is_allocating_condition.wait(mutex);
    }
}

} // namespace Service::android
