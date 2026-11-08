// SPDX-FileCopyrightText: Copyright 2026 Eden Emulator Project
// SPDX-License-Identifier: GPL-3.0-or-later

// SPDX-FileCopyrightText: Copyright 2019 yuzu Emulator Project
// SPDX-License-Identifier: GPL-2.0-or-later

#include <cstring>
#include "common/settings.h"
#include "common/random.h"
#include "core/file_sys/kernel_executable.h"
#include "core/file_sys/program_metadata.h"
#include "core/hle/kernel/code_set.h"
#include "core/hle/kernel/k_page_table.h"
#include "core/hle/kernel/k_process.h"
#include "core/loader/kip.h"
#include "core/memory.h"

namespace Loader {

namespace {
constexpr u32 PageAlignSize(u32 size) {
    return static_cast<u32>((size + Core::Memory::YUZU_PAGEMASK) & ~Core::Memory::YUZU_PAGEMASK);
}
} // Anonymous namespace

AppLoader_KIP::AppLoader_KIP(FileSys::VirtualFile file_)
    : AppLoader(std::move(file_)), kip(std::make_unique<FileSys::KIP>(file)) {}

AppLoader_KIP::~AppLoader_KIP() = default;

FileType AppLoader_KIP::IdentifyType(const FileSys::VirtualFile& in_file) {
    u32_le magic{};
    if (in_file->GetSize() < sizeof(u32) || in_file->ReadObject(&magic) != sizeof(u32)) {
        return FileType::Error;
    }

    if (magic == Common::MakeMagic('K', 'I', 'P', '1')) {
        return FileType::KIP;
    }

    return FileType::Error;
}

FileType AppLoader_KIP::GetFileType() const {
    return (kip != nullptr && kip->GetStatus() == ResultStatus::Success) ? FileType::KIP
                                                                         : FileType::Error;
}

AppLoader::LoadResult AppLoader_KIP::Load(Kernel::KProcess& process,
                                          [[maybe_unused]] Core::System& system) {
    if (is_loaded) {
        return {ResultStatus::ErrorAlreadyLoaded, {}};
    }

    if (kip == nullptr) {
        return {ResultStatus::ErrorNullFile, {}};
    }

    if (kip->GetStatus() != ResultStatus::Success) {
        return {kip->GetStatus(), {}};
    }

    const auto get_kip_address_space_type = [](const auto& kip_type) {
        return kip_type.Is64Bit()
                   ? (kip_type.Is39BitAddressSpace() ? FileSys::ProgramAddressSpaceType::Is39Bit
                                                     : FileSys::ProgramAddressSpaceType::Is36Bit)
                   : FileSys::ProgramAddressSpaceType::Is32Bit;
    };

    const auto address_space = get_kip_address_space_type(*kip);

    FileSys::ProgramMetadata metadata;
    metadata.LoadManual(kip->Is64Bit(), address_space, kip->GetMainThreadPriority(),
                        kip->GetMainThreadCpuCore(), kip->GetMainThreadStackSize(),
                        kip->GetTitleID(), 0xFFFFFFFFFFFFFFFF, 0x1FE00000,
                        kip->GetKernelCapabilities());

    Kernel::CodeSet codeset;
    codeset.memory.resize(PageAlignSize(kip->GetBSSOffset()) + kip->GetBSSSize());
    const auto load_segment = [&codeset](Kernel::CodeSet::Segment& segment, std::span<const u8> data, u32 offset) {
        segment.addr = offset;
        segment.offset = offset;
        segment.size = PageAlignSize(u32(data.size()));
        std::memcpy(codeset.memory.data() + offset, data.data(), data.size());
    };
    load_segment(codeset.CodeSegment(), kip->GetTextSection(), kip->GetTextOffset());
    load_segment(codeset.RODataSegment(), kip->GetRODataSection(), kip->GetRODataOffset());
    load_segment(codeset.DataSegment(), kip->GetDataSection(), kip->GetDataOffset());
    codeset.DataSegment().size += kip->GetBSSSize();

    // TODO: this is bad form of ASLR, it sucks
    // ★★★ NesStation savestate (v7)：代码基址确定性放置 ★★★
    // 原本的随机回退（Common::Random::Random64 = std::random_device）让
    // 同一游戏每次启动的代码基址漂移在 [0, 16MB) 内 —— 页表区域布局由
    // process_code_start 派生，跨会话读档的 precheck（区域一致校验）必然
    // 拒绝（= Cemu 的静态 MMURange 前提不成立）。改为固定常数：模拟器
    // 不是安全边界，确定性基址对 guest 完全透明，且与
    // KPageTableBase::InitializeForProcess / HostMemory::ChooseVirtualBase
    // 的确定性化合力，使同一游戏每次启动布局完全一致 → 跨会话槽位也能
    // 原地恢复。用户显式设置 rng_seed 时仍尊重之（同样是确定性的）。
    // 0x5A5 & 0xfff → 偏移 0x5A5000（约 5.8MB，仍在入口点之上，保持
    // 原 ASLR 注释里 skyline 类程序的兼容语义）。
    std::uintptr_t aslr_offset = ((::Settings::values.rng_seed_enabled.GetValue()
        ? ::Settings::values.rng_seed.GetValue() : 0x5A5ULL) << 12) & 0xfff000;

    // Setup the process code layout
    if (process.LoadFromMetadata(system.Kernel(), FileSys::ProgramMetadata::GetDefault(), codeset.memory.size(), 0, aslr_offset).IsError()) {
        return {ResultStatus::ErrorNotInitialized, {}};
    }
    const VAddr base_address = GetInteger(process.GetEntryPoint());
    process.LoadModule(system.Kernel(), std::move(codeset), base_address);

    LOG_DEBUG(Loader, "loaded module {} @ {:#x}", kip->GetName(), base_address);

    is_loaded = true;
    return {ResultStatus::Success,
            LoadParameters{kip->GetMainThreadPriority(), kip->GetMainThreadStackSize()}};
}

} // namespace Loader
