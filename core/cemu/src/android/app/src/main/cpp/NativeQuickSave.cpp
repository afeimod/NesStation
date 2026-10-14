#include "NativeQuickSave.h"
#include "Image.h"
#include "JNIUtils.h"

#include <dlfcn.h> // dlsym: malloc_trim is resolved at runtime (see TrimHeap below)
#include "Cafe/CafeSystem.h"
#include "Cafe/Savestate/SaveState.h"
#include "Cafe/HW/Latte/Renderer/Renderer.h"
#include "Cafe/TitleList/TitleId.h"
#include "config/ActiveSettings.h"
#include "Cemu/Logging/CemuLogging.h"

#include <android/bitmap.h>
#include <algorithm>
#include <chrono>
#include <cmath>
#include <condition_variable>
#include <ctime>
#include <filesystem>
#include <fstream>
#include <iterator>
#include <memory>
#include <mutex>
#include <optional>
#include <string>
#include <string_view>
#include <vector>

namespace fs = std::filesystem;

namespace
{
	constexpr int kMaxSlots = 6;
	constexpr int kAutoBackupSlot = -1; // internal slot used to keep a safety copy before restoring
	constexpr int kThumbMaxWidth = 320;
	constexpr int kThumbMaxHeight = 180;

	TitleId GetForegroundTitleIdSafe()
	{
		if (!CafeSystem::IsTitleRunning())
			return 0;
		return CafeSystem::GetForegroundTitleId();
	}

	fs::path GetTitleSaveDir(TitleId titleId)
	{
		return ActiveSettings::GetMlcPath(fmt::format("usr/save/{:08x}/{:08x}", (uint32)(titleId >> 32), (uint32)(titleId & 0xFFFFFFFF)));
	}

	fs::path GetSlotBaseDir(TitleId titleId)
	{
		return ActiveSettings::GetUserDataPath(fmt::format("savestates/{:08x}{:08x}", (uint32)(titleId >> 32), (uint32)(titleId & 0xFFFFFFFF)));
	}

	fs::path GetSlotDir(TitleId titleId, int slot)
	{
		if (slot == kAutoBackupSlot)
			return GetSlotBaseDir(titleId) / "autobackup";
		return GetSlotBaseDir(titleId) / fmt::format("slot{}", slot);
	}

	fs::path GetSlotSaveDir(TitleId titleId, int slot)
	{
		return GetSlotDir(titleId, slot) / "save";
	}

	// Returns free heap pages to the OS to keep the memory footprint low.
	// bionic only declares malloc_trim() in <malloc.h> when the compile-time
	// API level is >= 28, but the symbol exists on every device running it,
	// so we resolve it dynamically instead - this compiles with any minSdk
	// and is a no-op on the (theoretical) devices lacking the symbol.
	void TrimHeap()
	{
		using MallocTrimFn = int (*)(size_t);
		static const MallocTrimFn mallocTrimFn = reinterpret_cast<MallocTrimFn>(dlsym(RTLD_DEFAULT, "malloc_trim"));
		if (mallocTrimFn != nullptr)
			mallocTrimFn(0);
	}

	bool CopyDirectory(const fs::path& src, const fs::path& dst)
	{
		std::error_code ec;
		if (!fs::exists(src, ec))
			return false;

		fs::create_directories(dst, ec);
		if (ec)
			return false;

		for (const auto& entry : fs::recursive_directory_iterator(src, fs::directory_options::skip_permission_denied, ec))
		{
			if (ec)
				return false;

			const auto relative = fs::relative(entry.path(), src, ec);
			if (ec)
				return false;

			const auto target = dst / relative;
			if (entry.is_directory(ec))
			{
				fs::create_directories(target, ec);
			}
			else
			{
				fs::create_directories(target.parent_path(), ec);
				fs::copy_file(entry.path(), target, fs::copy_options::overwrite_existing, ec);
			}

			if (ec)
				return false;
		}
		return !ec;
	}

	uint64 GetDirectorySize(const fs::path& path)
	{
		std::error_code ec;
		if (!fs::exists(path, ec))
			return 0;

		uint64 totalSize = 0;
		for (const auto& entry : fs::recursive_directory_iterator(path, fs::directory_options::skip_permission_denied, ec))
		{
			if (!entry.is_directory(ec))
				totalSize += entry.file_size(ec);
		}
		return totalSize;
	}

	std::string GetCurrentTimestamp()
	{
		std::time_t now = std::time(nullptr);
		std::tm* localTime = std::localtime(&now);
		char buffer[64];
		std::strftime(buffer, sizeof(buffer), "%Y-%m-%d %H:%M:%S", localTime);
		return buffer;
	}

	std::string EscapeJsonString(std::string_view input)
	{
		std::string result;
		result.reserve(input.size() + 8);
		for (char c : input)
		{
			switch (c)
			{
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
				if (static_cast<unsigned char>(c) < 0x20)
					result += fmt::format("\\u{:04x}", c);
				else
					result += c;
				break;
			}
		}
		return result;
	}

	void WriteSlotInfoFile(const fs::path& slotDir)
	{
		std::error_code ec;
		fs::create_directories(slotDir, ec);
		std::ofstream file(slotDir / "info.txt", std::ios::binary | std::ios::trunc);
		if (file)
			file << GetCurrentTimestamp();
	}

	std::string ReadSlotInfoFile(const fs::path& slotDir)
	{
		std::ifstream file(slotDir / "info.txt", std::ios::binary);
		if (!file)
			return {};
		std::string content((std::istreambuf_iterator<char>(file)), std::istreambuf_iterator<char>());
		return content;
	}

	// Writes a downscaled uncompressed 24-bit TGA thumbnail from RGB pixel data
	void WriteThumbnailTGA(const fs::path& path, const uint8* rgb, int width, int height)
	{
		if (width <= 0 || height <= 0)
			return;

		std::error_code ec;
		fs::create_directories(path.parent_path(), ec);

		int thumbWidth = width;
		int thumbHeight = height;
		const double widthScale = (double)kThumbMaxWidth / (double)width;
		const double heightScale = (double)kThumbMaxHeight / (double)height;
		const double scale = std::min(1.0, std::min(widthScale, heightScale));
		thumbWidth = std::max(1, (int)std::lround(width * scale));
		thumbHeight = std::max(1, (int)std::lround(height * scale));

		std::vector<uint8> thumbData((size_t)thumbWidth * thumbHeight * 3);
		for (int y = 0; y < thumbHeight; ++y)
		{
			const int srcY = std::min(height - 1, (int)((sint64)y * height / thumbHeight));
			for (int x = 0; x < thumbWidth; ++x)
			{
				const int srcX = std::min(width - 1, (int)((sint64)x * width / thumbWidth));
				const uint8* src = rgb + ((size_t)srcY * width + srcX) * 3;
				uint8* dst = thumbData.data() + ((size_t)y * thumbWidth + x) * 3;
				dst[0] = src[0];
				dst[1] = src[1];
				dst[2] = src[2];
			}
		}

		std::ofstream file(path, std::ios::binary | std::ios::trunc);
		if (!file)
			return;

		uint8 header[18] = {};
		header[2] = 2; // uncompressed true-color image
		header[12] = (uint8)(thumbWidth % 256);
		header[13] = (uint8)(thumbWidth / 256);
		header[14] = (uint8)(thumbHeight % 256);
		header[15] = (uint8)(thumbHeight / 256);
		header[16] = 24; // bits per pixel
		header[17] = 0x20; // top-left origin
		file.write((const char*)header, sizeof(header));
		file.write((const char*)thumbData.data(), (std::streamsize)thumbData.size());
	}

	// Requests a screenshot from the renderer and stores it as thumbnail. Returns true on success.
	bool CaptureScreenshotToThumbnail(const fs::path& thumbPath)
	{
		if (!g_renderer || !CafeSystem::IsTitleRunning())
			return false;

		// The callback may outlive this function (e.g. on timeout), so all shared
		// state is kept alive through a shared_ptr captured by value
		struct CaptureState
		{
			std::mutex mutex;
			std::condition_variable cv;
			bool done = false;
			bool captured = false;
			fs::path path;
		};
		const auto state = std::make_shared<CaptureState>();
		state->path = thumbPath;

		g_renderer->RequestScreenshot([state](const std::vector<uint8>& rgbData, int width, int height, bool) -> std::optional<std::string> {
			if ((size_t)width * height * 3 <= rgbData.size() && width > 0 && height > 0)
				WriteThumbnailTGA(state->path, rgbData.data(), width, height);

			std::lock_guard lock(state->mutex);
			state->captured = fs::exists(state->path);
			state->done = true;
			state->cv.notify_all();
			return std::nullopt;
		});

		{
			std::unique_lock lock(state->mutex);
			state->cv.wait_for(lock, std::chrono::seconds(3), [&state] { return state->done; });
		}

		// Make sure a timed-out request cannot fire into a destroyed stack frame later
		g_renderer->CancelScreenshotRequest();
		return state->captured;
	}
} // namespace

extern "C" [[maybe_unused]] JNIEXPORT jstring JNICALL
Java_info_cemu_cemu_nativeinterface_NativeQuickSave_getQuickSaveInfo([[maybe_unused]] JNIEnv* env, [[maybe_unused]] jclass clazz)
{
	jstring result = nullptr;
	// Inlined exception guard (same semantics as JNIUtils::HandleNativeException).
	// NDK r29 clang 20 mis-compiles consteval format-string checks inside lambdas
	// passed through function templates, so this function cannot use the helper.
	try
	{
		const TitleId titleId = GetForegroundTitleIdSafe();
		if (titleId == 0)
		{
			result = JNIUtils::ToJString(env, std::string_view(R"({"titleId":"","titleName":"","maxSlots":6,"slots":[]})"));
			return result;
		}

		std::string json = fmt::format(R"({{"titleId":"{:016x}","titleName":"{}","maxSlots":{},"slots":[)",
			(uint64)titleId,
			EscapeJsonString(CafeSystem::GetForegroundTitleName()),
			kMaxSlots);

		for (int slot = 1; slot <= kMaxSlots; ++slot)
		{
			const fs::path slotDir = GetSlotDir(titleId, slot);
			const fs::path statePath = slotDir / "state.bin";
			std::error_code ec;
			const bool hasState = fs::exists(statePath, ec);
			const bool hasSaveData = fs::exists(slotDir / "save", ec);
			const bool exists = hasState || hasSaveData;
			const std::string timestamp = exists ? ReadSlotInfoFile(slotDir) : std::string();
			uint64 size = 0;
			if (hasState)
				size += fs::file_size(statePath, ec);
			if (hasSaveData)
				size += GetDirectorySize(slotDir / "save");
			const bool thumb = fs::exists(slotDir / "thumb.tga");

			fmt::format_to(std::back_inserter(json), R"({{"slot":{},"exists":{},"timestamp":"{}","sizeBytes":{},"hasState":{},"thumb":{}}}{})",
				slot,
				exists ? "true" : "false",
				EscapeJsonString(timestamp),
				size,
				hasState ? "true" : "false",
				thumb ? "true" : "false",
				slot < kMaxSlots ? "," : "");
		}

		const fs::path autoBackupDir = GetSlotDir(titleId, kAutoBackupSlot);
		const bool autoBackupExists = fs::exists(GetSlotSaveDir(titleId, kAutoBackupSlot));
		const std::string autoBackupTimestamp = autoBackupExists ? ReadSlotInfoFile(autoBackupDir) : std::string();
		const bool autoBackupThumb = fs::exists(autoBackupDir / "thumb.tga");

		json += fmt::format(R"(],"autoBackup":{{"exists":{},"timestamp":"{}","thumb":{}}}}})",
			autoBackupExists ? "true" : "false",
			EscapeJsonString(autoBackupTimestamp),
			autoBackupThumb ? "true" : "false");
		result = JNIUtils::ToJString(env, json);
	}
	catch (const std::exception& exception)
	{
		const jclass exceptionClass = env->FindClass("info/cemu/cemu/nativeinterface/NativeException");
		env->ThrowNew(exceptionClass, exception.what());
	}
	catch (...)
	{
		const jclass exceptionClass = env->FindClass("info/cemu/cemu/nativeinterface/NativeException");
		env->ThrowNew(exceptionClass, "Unknown native exception");
	}
	return result;
}

extern "C" [[maybe_unused]] JNIEXPORT jint JNICALL
Java_info_cemu_cemu_nativeinterface_NativeQuickSave_quickSaveSlot([[maybe_unused]] JNIEnv* env, [[maybe_unused]] jclass clazz, jint slot)
{
	jint result = 0;
	JNIUtils::HandleNativeException(env, [&]() {
		if (!CafeSystem::IsTitleRunning())
		{
			result = (jint)NativeQuickSave::QUICKSAVE_ERROR_NOT_RUNNING;
			return;
		}

		const TitleId titleId = CafeSystem::GetForegroundTitleId();
		const fs::path slotDir = GetSlotDir(titleId, slot);
		std::error_code ec;
		fs::create_directories(slotDir, ec);
		if (ec)
		{
			result = (jint)NativeQuickSave::QUICKSAVE_ERROR_IO;
			return;
		}

		const bool wasPaused = CafeSystem::IsTitlePaused();

		// Capture the screenshot while the game is still running. While the title
		// is suspended no new frames are presented, so a screenshot request made
		// after pausing would never complete.
		if (!wasPaused)
			CaptureScreenshotToThumbnail(slotDir / "thumb.tga");

		// capture the full emulation state (all emulated memory incl. thread
		// contexts + open file handles) while the game is paused. The file is
		// what makes loading instantaneous: restoring it puts the game back to
		// exactly this moment without any restart
		CafeSaveState::EmulationFreeze freeze;
		std::string freezeError;
		if (!freeze.begin(freezeError))
		{
			result = (jint)NativeQuickSave::QUICKSAVE_ERROR_IO;
			return;
		}

		const fs::path statePath = slotDir / "state.bin";
		const auto captureResult = CafeSaveState::CaptureStateToFileLocked((uint64)titleId, statePath);
		if (!captureResult.success)
		{
			result = (jint)NativeQuickSave::QUICKSAVE_ERROR_IO;
			return;
		}

		// also copy the MLC save data as a fallback so the slot can still be
		// used (with a game restart) after the emulator has been closed
		bool copyOk = true;
		const fs::path saveDir = GetTitleSaveDir(titleId);
		if (fs::exists(saveDir, ec))
		{
			const fs::path slotSaveDir = GetSlotSaveDir(titleId, slot);
			fs::remove_all(slotSaveDir, ec);
			copyOk = CopyDirectory(saveDir, slotSaveDir);
		}
		if (copyOk)
			WriteSlotInfoFile(slotDir);

		// freeze is released by the destructor, which also resumes the game
		// unless the user had paused it manually
		result = (jint)NativeQuickSave::QUICKSAVE_OK;
	});
	return result;
}

extern "C" [[maybe_unused]] JNIEXPORT jint JNICALL
Java_info_cemu_cemu_nativeinterface_NativeQuickSave_quickRestoreSlot([[maybe_unused]] JNIEnv* env, [[maybe_unused]] jclass clazz, jint slot, jint flags)
{
	jint result = 0;
	JNIUtils::HandleNativeException(env, [&]() {
		if (!CafeSystem::IsTitleRunning())
		{
			result = (jint)NativeQuickSave::QUICKSAVE_ERROR_NOT_RUNNING;
			return;
		}

		const TitleId titleId = CafeSystem::GetForegroundTitleId();
		std::error_code ec;

		// preferred path: restore the captured emulation state in-place. The
		// game is paused, its memory is overwritten with the snapshot and it
		// resumes exactly where the snapshot was taken - no restart at all
		const fs::path statePath = GetSlotDir(titleId, slot) / "state.bin";
		if (fs::exists(statePath, ec))
		{
			const auto restoreResult = CafeSaveState::RestoreStateFromFile((uint64)titleId, statePath);
			if (restoreResult.success)
			{
				// return freed heap pages (GPU cache unload, restore scratch data)
				// back to the OS right away to keep the memory footprint low
				TrimHeap();
				result = (jint)NativeQuickSave::QUICKSAVE_OK_RESTORED_IN_PLACE;
				return;
			}
			if ((flags & NativeQuickSave::QUICKRESTORE_FLAG_ALLOW_LEGACY) == 0)
			{
					// do not silently fall back to the destructive legacy path when
					// the state restore failed - report the failure instead
					result = (jint)NativeQuickSave::QUICKSAVE_ERROR_STATE_RESTORE_FAILED;
					return;
			}
			// safe mode was requested: continue below with the legacy path
			// (shut the title down and restore the slot save data)
		}

		const fs::path slotSaveDir = GetSlotSaveDir(titleId, slot);
		std::error_code existsEc;
		if (!fs::exists(slotSaveDir, existsEc))
		{
			result = (jint)NativeQuickSave::QUICKSAVE_ERROR_SLOT_EMPTY;
			return;
		}

		// legacy fallback (slots created by older builds without a state file):
		// shut down the running title first so the game releases all save file
		// handles and no writes can corrupt the restored data. The caller is
		// expected to relaunch the title afterwards
		CafeSystem::ShutdownTitle();

		const fs::path saveDir = GetTitleSaveDir(titleId);

		// Keep a safety copy of the current save progress before overwriting it
		if (fs::exists(saveDir, ec))
		{
			const fs::path autoBackupSaveDir = GetSlotSaveDir(titleId, kAutoBackupSlot);
			fs::remove_all(autoBackupSaveDir, ec);
			if (CopyDirectory(saveDir, autoBackupSaveDir))
				WriteSlotInfoFile(GetSlotDir(titleId, kAutoBackupSlot));
		}

		// Restore the selected slot into the MLC save directory
		fs::remove_all(saveDir, ec);
		if (!CopyDirectory(slotSaveDir, saveDir))
		{
			result = (jint)NativeQuickSave::QUICKSAVE_ERROR_IO;
			return;
		}

		result = (jint)NativeQuickSave::QUICKSAVE_OK;
	});
	return result;
}

extern "C" [[maybe_unused]] JNIEXPORT jobject JNICALL
Java_info_cemu_cemu_nativeinterface_NativeQuickSave_getQuickSaveThumbnail([[maybe_unused]] JNIEnv* env, [[maybe_unused]] jclass clazz, jint slot)
{
	jobject result = nullptr;
	JNIUtils::HandleNativeException(env, [&]() {
		const TitleId titleId = GetForegroundTitleIdSafe();
		if (titleId == 0)
			return;

		const fs::path thumbPath = GetSlotDir(titleId, slot) / "thumb.tga";
		std::error_code ec;
		if (!fs::exists(thumbPath, ec))
			return;

		std::ifstream file(thumbPath, std::ios::binary);
		if (!file)
			return;

		std::vector<uint8> fileData((std::istreambuf_iterator<char>(file)), std::istreambuf_iterator<char>());
		if (fileData.empty())
			return;

		Image image(fileData);
		if (!image.IsOk())
			return;

		const jclass bitmapClass = env->FindClass("android/graphics/Bitmap");
		const jmethodID createBitmapMID = env->GetStaticMethodID(bitmapClass, "createBitmap", "([IIILandroid/graphics/Bitmap$Config;)Landroid/graphics/Bitmap;");
		const jclass bitmapConfigClass = env->FindClass("android/graphics/Bitmap$Config");
		const jfieldID argbFieldID = env->GetStaticFieldID(bitmapConfigClass, "ARGB_8888", "Landroid/graphics/Bitmap$Config;");
		const jobject bitmapConfig = env->GetStaticObjectField(bitmapConfigClass, argbFieldID);

		const jintArray pixelArray = env->NewIntArray(image.width * image.height);
		env->SetIntArrayRegion(pixelArray, 0, image.width * image.height, image.colors);
		const jobject bitmap = env->CallStaticObjectMethod(bitmapClass, createBitmapMID, pixelArray, image.width, image.height, bitmapConfig);

		env->DeleteLocalRef(pixelArray);
		env->DeleteLocalRef(bitmapConfig);
		env->DeleteLocalRef(bitmapConfigClass);
		env->DeleteLocalRef(bitmapClass);
		result = bitmap;
	});
	return result;
}
