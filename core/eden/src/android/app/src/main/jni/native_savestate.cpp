// SPDX-FileCopyrightText: Copyright 2026 NesStation / Eden Emulator Project
// SPDX-License-Identifier: GPL-3.0-or-later
//
// ★★★ NesStation 集成：Switch（Eden）核心即时存档 —— 原生实现 ★★★
//
// 移植自 NesStation 的 Cemu 集成
// （core/cemu/src/android/app/src/main/cpp/NativeQuickSave.cpp），逐块对应：
//
//   Cemu (Wii U)                        Eden (Switch / yuzu 系)
//   ─────────────────────────────      ─────────────────────────────────────
//   CafeSystem::IsTitleRunning()   →   EmulationSession::IsRunning()
//   g_renderer->RequestScreenshot  →   RendererBase::RequestScreenshot
//   CafeSaveState::EmulationFreeze →   本文件 EmulationFreeze（RAII）
//     （核心全量冻结 + state.bin）       ★ yuzu 系差异：内核对象无序列化
//                                        器，无进程内 state.bin 路径；冻结
//                                        用于保证存档数据拷贝的瞬时一致性
//   MLC usr/save 按标题拷贝         →   NAND user/save 按标题拷贝
//                                     （account 旧/新布局 + device 布局）
//   CafeSystem::ShutdownTitle()    →   EmulationSession::HaltEmulation()
//                                        + WaitForShutdown()（新增）
//
// 异步 GPU 架构（用户要求的核心点）：
//   yuzu 系 GPU 模拟运行在独立线程（video_core/gpu_thread），CPU 侧只是
//   提交命令表。直接在运行中拷贝存档数据会捕获到“半执行”状态 —— 例如
//   NVDEC 异步解码帧正写回 guest 内存、栅栏未落地的半更新缓冲。本实现
//   的冻结序列与上游 ReloadDiskShaderCache 完全一致（在树内已被验证的
//   同步模式）：
//       System::Pause()                 —— 内核挂起全部 guest 线程
//       GPU().WaitForIdle()             —— 投递同步栅栏并等待 GPU 线程
//                                           排空（异步 GPU 排空 ★）
//       GPU().ObtainContext()           —— 独占 GPU 图形上下文
//       ... 拷贝存档数据 ...
//       GPU().ReleaseContext()
//       System::Run()                   —— 解冻（若冻结前未暂停）

#include "native_savestate.h"

#include "native.h"

#include "common/fs/path_util.h"
#include "core/core.h"
#include "core/loader/loader.h"
#include "video_core/gpu.h"
#include "video_core/renderer_base.h"

#include <dlfcn.h> // dlsym: malloc_trim 运行时解析（见 TrimHeap）

#include <algorithm>
#include <chrono>
#include <cmath>
#include <condition_variable>
#include <cstring>
#include <ctime>
#include <filesystem>
#include <fstream>
#include <iterator>
#include <mutex>
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

fs::path NandRoot() {
    return Common::FS::GetEdenPath(Common::FS::EdenPath::NANDDir);
}

// ---------------------------------------------------------------------------
// 按标题的 NAND 用户存档定位
//
// yuzu 系存档布局（savedata_factory.cpp GetFullPath）：
//   旧 account： nand/user/save/0000000000000000/<uidHiLo>/<titleId>
//   新 account： nand/user/save/account/<uuid>/<baseTitleId>/0
//   device   ： nand/user/save/device/<titleId>/0
//   cache    ： nand/user/save/cache/<titleId>（可再生的派生数据，不快照）
//
// 存档属性里的 title_id 为进程程序 ID（或其基数）。此处按叶子目录名
// 匹配「程序 ID / 基程序 ID（~0xFF）」两形态，收集 (源路径, 相对 nand
// 的相对路径) 列表，槽内镜像保持原目录结构 —— 写回时按相对路径原样还原。
// ---------------------------------------------------------------------------

struct SaveDirEntry {
    fs::path source;      // nand 下的绝对源路径
    fs::path relative;    // 相对 nand 根的相对路径（含 user/save 前缀）
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
        const int srcY = std::min(height - 1, static_cast<int>(static_cast<s64>(y) * height / thumbHeight));
        for (int x = 0; x < thumbWidth; ++x) {
            const int srcX = std::min(width - 1, static_cast<int>(static_cast<s64>(x) * width / thumbWidth));
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
// 冻结（★ 异步 GPU 同步架构核心）★
//
// 与树内已验证的 ReloadDiskShaderCache 同步序列完全一致：
//   Pause → GPU().WaitForIdle() → ObtainContext() ... ReleaseContext → Run
// ---------------------------------------------------------------------------

class EmulationFreeze {
public:
    explicit EmulationFreeze(Core::System& system) : m_system{system} {
        m_was_paused = m_system.IsPaused();
        if (!m_was_paused) {
            m_system.Pause();
        }
        // ★ 异步 GPU 排空：投递同步操作并等待 GPU 线程越过栅栏 —— 确保
        //   存档数据拷贝时，guest 可见内存/NAND 视图不再有在途 GPU 写入
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
// 存档数据快照 / 还原
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
                fs::remove_all(nand / "user" / "save" / "device" / title_dir.path().filename(), ec);
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
            const bool has_data = SlotHasSaveData(program_id, slot);
            const std::string timestamp = has_data ? ReadSlotInfoFile(slot_dir) : std::string();
            const u64 size = has_data ? GetDirectorySize(GetSlotSaveDir(program_id, slot)) : 0;
            const bool thumb = [&] {
                std::error_code ec;
                return fs::exists(slot_dir / "thumb.tga", ec);
            }();

            fmt::format_to(std::back_inserter(json),
                           R"({{"slot":{},"exists":{},"timestamp":"{}","sizeBytes":{},"hasState":{},"thumb":{}}}{})",
                           slot, has_data ? "true" : "false", EscapeJsonString(timestamp), size,
                           has_data ? "true" : "false", thumb ? "true" : "false",
                           slot < kMaxSlots ? "," : "");
        }

        const fs::path backup_dir = GetSlotDir(program_id, kAutoBackupSlot);
        const bool backup_exists = SlotHasSaveData(program_id, kAutoBackupSlot);
        const std::string backup_timestamp =
            backup_exists ? ReadSlotInfoFile(backup_dir) : std::string();
        const bool backup_thumb = [&] {
            std::error_code ec;
            return fs::exists(backup_dir / "thumb.tga", ec);
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

        // 本实现的存档载体 = 按标题快照的 NAND 用户存档：游戏内尚无任何
        // 存档（首次游玩未保存过）时无物可快照 —— 如实报 NO_SAVE_DATA，
        // 引导玩家先在游戏内保存（与 Cemu 契约同款语义）。
        if (CollectTitleSaveDirs(program_id).empty()) {
            return native_savestate::RESULT_ERROR_NO_SAVE_DATA;
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

        // ★ 冻结 + 异步 GPU 排空，RAII 解冻（与 Cemu EmulationFreeze 同构）
        {
            EmulationFreeze freeze{system};
            if (!SnapshotTitleSaveData(program_id, GetSlotSaveDir(program_id, static_cast<int>(slot)))) {
                return native_savestate::RESULT_ERROR_IO;
            }
            WriteSlotInfoFile(slot_dir);
        }

        // 归还空闲堆页（槽位拷贝的临时缓冲）
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
        (void)flags; // 预留（核心支持进程内 state.bin 恢复前，唯一路径即下述流程）

        if (!IsTitleRunning()) {
            return native_savestate::RESULT_ERROR_NOT_RUNNING;
        }

        const u64 program_id = GetForegroundProgramId();
        if (program_id == 0) {
            return native_savestate::RESULT_ERROR_NOT_RUNNING;
        }

        if (!SlotHasSaveData(program_id, static_cast<int>(slot))) {
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

        // 停止运行中的游戏（= Cemu ShutdownTitle）：让游戏释放全部存档
        // 文件句柄，杜绝写回与还原拷贝竞争。随后等待核心关停完成。
        Session().HaltEmulation();
        if (!Session().WaitForShutdown(std::chrono::seconds(10))) {
            return native_savestate::RESULT_ERROR_IO;
        }

        // 槽位存档数据写回 NAND
        if (!RestoreTitleSaveData(program_id, GetSlotSaveDir(program_id, static_cast<int>(slot)))) {
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
