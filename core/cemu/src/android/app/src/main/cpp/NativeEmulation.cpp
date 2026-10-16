#include "AndroidFilesystemCallbacks.h"
#include "AndroidInputHelpers.h"
#include "Cafe/CafeSystem.h"
#include "Cafe/HW/Latte/Core/LatteOverlay.h"
#include "Cafe/HW/Latte/Core/LatteTexture.h"
#include "Cafe/HW/Latte/Core/LattePerformanceMonitor.h"
#include "Cafe/HW/Latte/Renderer/Vulkan/VulkanAPI.h"
#include "Cafe/HW/Latte/Renderer/Vulkan/VulkanRenderer.h"
#include "Cafe/OS/libs/snd_core/ax.h"
#include "Cafe/TitleList/TitleId.h"
#include "Cafe/TitleList/TitleList.h"
#include "Cemu/ncrypto/ncrypto.h"
#include "config/ActiveSettings.h"
#include "input/InputManager.h"
#include "JNIUtils.h"
#include "WindowSystem.h"

#if HAS_CUBEB
#include "audio/CubebAPI.h"
#endif // HAS_CUBEB

#include <android/native_window_jni.h>

// forward declaration from main.cpp
void CemuCommonInit();

namespace NativeEmulation
{

	void CreateAudioDevice(IAudioAPI::AudioAPI audioApi, AudioChannels channels, sint32 volume, bool isTV)
	{
		constexpr int AX_FRAMES_PER_GROUP = 4;
		std::unique_lock lock(g_audioMutex);
		auto& audioDevice = isTV ? g_tvAudio : g_padAudio;

		switch (audioApi)
		{
#if HAS_CUBEB
		case IAudioAPI::AudioAPI::Cubeb:
		{
			audioDevice.reset();
			auto deviceDescriptionPtr = std::make_shared<CubebAPI::CubebDeviceDescription>(nullptr, std::string(), std::wstring());
			audioDevice = IAudioAPI::CreateDevice(
				IAudioAPI::AudioAPI::Cubeb,
				deviceDescriptionPtr,
				48000,
				CemuConfig::AudioChannelsToNChannels(channels),
				snd_core::AX_SAMPLES_PER_3MS_48KHZ * AX_FRAMES_PER_GROUP,
				16);
			audioDevice->SetVolume(volume);
			break;
		}
#endif // HAS_CUBEB
		default:
			cemuLog_log(LogType::Force, "Invalid audio api: {}", audioApi);
			break;
		}
	}

	void InitializeAudioDevices()
	{
		auto& config = GetConfig();
		if (!config.tv_device.empty())
			CreateAudioDevice(IAudioAPI::AudioAPI::Cubeb, config.tv_channels, config.tv_volume, true);

		if (!config.pad_device.empty())
			CreateAudioDevice(IAudioAPI::AudioAPI::Cubeb, config.pad_channels, config.pad_volume, false);
	}

	void SetDefaultDeviceController()
	{
		for (size_t i = 0; i < InputManager::kMaxController; ++i)
		{
			auto emulatedController = InputManager::instance().get_controller(i);

			if (!emulatedController)
			{
				continue;
			}

			for (const auto& controller : emulatedController->get_controllers())
			{
				if (controller->api() == InputAPI::Device)
				{
					return;
				}
			}
		}

		if (auto emulatedController = InputManager::instance().get_controller(0))
		{
			auto controller = CreateDefaultDeviceController();
			emulatedController->add_controller(controller);
		}
	}

	void CreateCemuDirectories()
	{
		const auto mlc = ActiveSettings::GetMlcPath();

		// create sys/usr folder in mlc01
		const auto sysFolder = mlc / "sys";
		fs::create_directories(sysFolder);

		const auto usrFolder = mlc / "usr";
		fs::create_directories(usrFolder);
		fs::create_directories(usrFolder / "title/00050000"); // base
		fs::create_directories(usrFolder / "title/0005000c"); // dlc
		fs::create_directories(usrFolder / "title/0005000e"); // update

		// Mii Maker save folders {0x500101004A000, 0x500101004A100, 0x500101004A200},
		fs::create_directories(mlc / "usr/save/00050010/1004a100/user/common/db");
		fs::create_directories(mlc / "usr/save/00050010/1004a000/user/common/db");
		fs::create_directories(mlc / "usr/save/00050010/1004a200/user/common/db");

		// lang files
		auto langDir = mlc / "sys/title/0005001b/1005c000/content";
		fs::create_directories(langDir);

		auto langFile = langDir / "language.txt";

		if (!fs::exists(langFile))
		{
			if (std::ofstream file{langFile})
			{
				const auto langStrings = {"ja", "en", "fr", "de", "it", "es", "ko", "nl", "pt", "ru", "zh"};
				for (const char* lang : langStrings)
					fmt::println(file, R"("{}",)", lang);
			}
		}

		auto countryFile = langDir / "country.txt";
		if (!fs::exists(countryFile))
		{
			if (std::ofstream file{countryFile})
			{
				for (sint32 i = 0; i < 201; i++)
				{
					const char* countryCode = NCrypto::GetCountryAsString(i);
					if (boost::iequals(countryCode, "NN"))
						fmt::println(file, "NULL,");
					else
						fmt::println(file, R"("{}",)", countryCode);
				}
			}
		}

		// cemu directories
		fs::create_directories(ActiveSettings::GetConfigPath("controllerProfiles"));
		fs::create_directories(ActiveSettings::GetUserDataPath("memorySearcher"));
	}

	enum PrepareTitleResult : sint32
	{
		SUCCESSFUL = 0,
		ERROR_GAME_BASE_FILES_NOT_FOUND = 1,
		ERROR_NO_DISC_KEY = 2,
		ERROR_NO_TITLE_TIK = 3,
		ERROR_UNKNOWN = 4,
	};

	class TestSurface
	{
	  public:
		TestSurface()
		{
			JNIEnv* env = JNIUtils::GetEnv();

			jclass surfaceTextureClass = env->FindClass("android/graphics/SurfaceTexture");
			jmethodID ctorSurfaceTexture = env->GetMethodID(surfaceTextureClass, "<init>", "(I)V");
			jobject localSurfaceTexture = env->NewObject(surfaceTextureClass, ctorSurfaceTexture, 0);

			jclass surfaceClass = env->FindClass("android/view/Surface");
			jmethodID ctorSurface = env->GetMethodID(surfaceClass, "<init>", "(Landroid/graphics/SurfaceTexture;)V");
			jobject localSurface = env->NewObject(surfaceClass, ctorSurface, localSurfaceTexture);

			m_surfaceTexture = env->NewGlobalRef(localSurfaceTexture);
			m_surface = env->NewGlobalRef(localSurface);

			m_window = ANativeWindow_fromSurface(env, m_surface);
			ANativeWindow_acquire(m_window);

			env->DeleteLocalRef(localSurfaceTexture);
			env->DeleteLocalRef(localSurface);
			env->DeleteLocalRef(surfaceTextureClass);
			env->DeleteLocalRef(surfaceClass);
		}

		~TestSurface()
		{
			JNIEnv* env = JNIUtils::GetEnv();

			ANativeWindow_release(m_window);

			jclass surfaceClass = env->FindClass("android/view/Surface");
			jmethodID releaseSurface = env->GetMethodID(surfaceClass, "release", "()V");
			env->CallVoidMethod(m_surface, releaseSurface);
			env->DeleteGlobalRef(m_surface);
			m_surface = nullptr;
			env->DeleteLocalRef(surfaceClass);

			jclass surfaceTextureClass = env->FindClass("android/graphics/SurfaceTexture");
			jmethodID releaseSurfaceTexture = env->GetMethodID(surfaceTextureClass, "release", "()V");
			env->CallVoidMethod(m_surfaceTexture, releaseSurfaceTexture);
			env->DeleteGlobalRef(m_surfaceTexture);
			m_surfaceTexture = nullptr;
			env->DeleteLocalRef(surfaceTextureClass);
		}

		ANativeWindow* getWindow()
		{
			return m_window;
		}

	  private:
		ANativeWindow* m_window;
		jobject m_surface = nullptr;
		jobject m_surfaceTexture = nullptr;
	};
} // namespace NativeEmulation

extern "C" [[maybe_unused]] JNIEXPORT void JNICALL
Java_info_cemu_cemu_nativeinterface_NativeEmulation_setReplaceTVWithPadView([[maybe_unused]] JNIEnv* env, [[maybe_unused]] jclass clazz, jboolean swapped)
{
	// Emulate pressing the TAB key for showing DRC instead of TV
	WindowSystem::GetWindowInfo().set_keystate(static_cast<uint32>(WindowSystem::PlatformKeyCodes::TAB), swapped);
}

extern "C" [[maybe_unused]] JNIEXPORT void JNICALL
Java_info_cemu_cemu_nativeinterface_NativeEmulation_initializeEmulation([[maybe_unused]] JNIEnv* env, [[maybe_unused]] jclass clazz)
{
	FilesystemAndroid::SetFilesystemCallbacks(std::make_shared<AndroidFilesystemCallbacks>());
	GetConfigHandle().SetFilename(ActiveSettings::GetConfigPath("settings.xml").generic_wstring());
	NativeEmulation::CreateCemuDirectories();
	NetworkConfig::LoadOnce();
	ActiveSettings::Init();
	LatteOverlay_init();
	CemuCommonInit();
}

extern "C" [[maybe_unused]] JNIEXPORT void JNICALL
Java_info_cemu_cemu_nativeinterface_NativeEmulation_initializeRenderer(JNIEnv* env, [[maybe_unused]] jclass clazz)
{
	static std::unique_ptr<NativeEmulation::TestSurface> testSurface;

	InitializeGlobalVulkan();
	JNIUtils::HandleNativeException(env, [&]() {
		testSurface = std::make_unique<NativeEmulation::TestSurface>();

		WindowSystem::GetWindowInfo().window_main.surface = testSurface->getWindow();

		g_renderer = std::make_unique<VulkanRenderer>();
	});
}

extern "C" [[maybe_unused]] JNIEXPORT void JNICALL
Java_info_cemu_cemu_nativeinterface_NativeEmulation_setDPI([[maybe_unused]] JNIEnv* env, [[maybe_unused]] jclass clazz, jfloat dpi)
{
	auto& windowInfo = WindowSystem::GetWindowInfo();
	windowInfo.dpi_scale = windowInfo.pad_dpi_scale = dpi;
}

extern "C" [[maybe_unused]] JNIEXPORT void JNICALL
Java_info_cemu_cemu_nativeinterface_NativeEmulation_clearPadSurface([[maybe_unused]] JNIEnv* env, [[maybe_unused]] jclass clazz)
{
	// the renderer is created asynchronously and may not exist yet when
	// an early pad surface is torn down (e.g. floating GamePad overlay)
	if (g_renderer)
		VulkanRenderer::GetInstance()->StopUsingPadAndWait();
	WindowSystem::GetWindowInfo().pad_open = false;
}

extern "C" [[maybe_unused]] JNIEXPORT jboolean JNICALL
Java_info_cemu_cemu_nativeinterface_NativeEmulation_supportsLoadingCustomDriver([[maybe_unused]] JNIEnv* env, [[maybe_unused]] jclass clazz)
{
	return SupportsLoadingCustomDriver();
}

extern "C" [[maybe_unused]] JNIEXPORT void JNICALL
Java_info_cemu_cemu_nativeinterface_NativeEmulation_setSurface(JNIEnv* env, [[maybe_unused]] jclass clazz, jobject surface, jboolean isMainCanvas)
{
	JNIUtils::HandleNativeException(env, [&]() {
		auto& windowHandleInfo = isMainCanvas ? WindowSystem::GetWindowInfo().canvas_main : WindowSystem::GetWindowInfo().canvas_pad;
		auto oldWindow = windowHandleInfo.surface.load();
		if (oldWindow != nullptr)
			ANativeWindow_release(static_cast<ANativeWindow*>(oldWindow));
		auto newSurface = ANativeWindow_fromSurface(env, surface);
		ANativeWindow_acquire(newSurface);
		windowHandleInfo.surface = newSurface;
		windowHandleInfo.surface.notify_all();
	});
}

extern "C" [[maybe_unused]] JNIEXPORT void JNICALL
Java_info_cemu_cemu_nativeinterface_NativeEmulation_initializeSurface(JNIEnv* env, [[maybe_unused]] jclass clazz, jboolean isMainCanvas)
{
	JNIUtils::HandleNativeException(env, [&]() {
		if (!g_renderer)
		{
			// initializeRenderer() has not created the renderer yet; the
			// Kotlin side retries the pad surface once it is ready
			return;
		}
		int width, height;
		if (isMainCanvas)
		{
			WindowSystem::GetWindowPhysSize(width, height);
		}
		else
		{
			WindowSystem::GetPadWindowPhysSize(width, height);
			WindowSystem::GetWindowInfo().pad_open = true;
		}

		VulkanRenderer::GetInstance()->InitializeSurface({width, height}, isMainCanvas);
	});
}

extern "C" [[maybe_unused]] JNIEXPORT void JNICALL
Java_info_cemu_cemu_nativeinterface_NativeEmulation_setSurfaceSize([[maybe_unused]] JNIEnv* env, [[maybe_unused]] jclass clazz, jint width, jint height, jboolean isMainCanvas)
{
	auto& windowInfo = WindowSystem::GetWindowInfo();
	if (isMainCanvas)
	{
		windowInfo.width = windowInfo.phys_width = width;
		windowInfo.height = windowInfo.phys_height = height;
	}
	else
	{
		windowInfo.pad_width = windowInfo.phys_pad_width = width;
		windowInfo.pad_height = windowInfo.phys_pad_height = height;
	}
}

// ★ NesStation R3: 全局内部分辨率缩放（0.25..3.0）—— 渲染目标纹理按比例
// 创建（真实降低/提升游戏内部渲染分辨率，填充率按 scale^2 变化），视口/
// 剪裁/拷贝由 Cemu 图形包分辨率管线自动联动，输出画面大小不变。任意线程
// 记录待生效值，GPU 线程在帧边界应用并重建纹理缓存（运行中热生效）。
extern "C" JNIEXPORT void JNICALL
Java_info_cemu_cemu_nativeinterface_NativeEmulation_setOutputRenderScale([[maybe_unused]] JNIEnv* env, [[maybe_unused]] jclass clazz, jfloat scale)
{
	LatteTexture_SetInternalRenderScale(scale);
}

// ★ NesStation R4: 阴影渲染质量（三档）—— 独立深度目标（阴影图/深度
// prepass）按比例创建；主画面深度缓冲不受影响。1.0=原生 / 0.5 / 0.3
extern "C" JNIEXPORT void JNICALL
Java_info_cemu_cemu_nativeinterface_NativeEmulation_setShadowRenderScale([[maybe_unused]] JNIEnv* env, [[maybe_unused]] jclass clazz, jfloat scale)
{
	LatteTexture_SetShadowRenderScale(scale);
}

// ★ NesStation R4: 光照渲染质量（三档）—— 泛光/光照等辅助小目标按比例
// 创建，多级模糊 pass 填充率按比例平方下降。1.0=原生 / 0.6 / 0.35
extern "C" JNIEXPORT void JNICALL
Java_info_cemu_cemu_nativeinterface_NativeEmulation_setAuxRenderScale([[maybe_unused]] JNIEnv* env, [[maybe_unused]] jclass clazz, jfloat scale)
{
	LatteTexture_SetAuxRenderScale(scale);
}

// ★ NesStation R4: 帧率限制（省电）—— 复用 LatteTiming 自定义 vsync 频率
// 通道：翻转锁步游戏按该频率节流，GPU/CPU 占用随帧率线性下降。<=0 关闭。
extern "C" JNIEXPORT void JNICALL
Java_info_cemu_cemu_nativeinterface_NativeEmulation_setFrameLimit([[maybe_unused]] JNIEnv* env, [[maybe_unused]] jclass clazz, jint fps)
{
	if (fps <= 0)
		LatteTiming_disableCustomVsyncFrequency();
	else
		LatteTiming_setCustomVsyncFrequency(fps);
}

extern "C" [[maybe_unused]] JNIEXPORT void JNICALL
Java_info_cemu_cemu_nativeinterface_NativeEmulation_initializeSystems([[maybe_unused]] JNIEnv* env, [[maybe_unused]] jclass clazz)
{
	NativeEmulation::SetDefaultDeviceController();
	WindowSystem::GetWindowInfo().set_keystatesup();
	NativeEmulation::InitializeAudioDevices();
}

extern "C" [[maybe_unused]] JNIEXPORT jint JNICALL
Java_info_cemu_cemu_nativeinterface_NativeEmulation_prepareTitle([[maybe_unused]] JNIEnv* env, [[maybe_unused]] jclass clazz, jstring launchPathJava)
{
	fs::path launchPath = JNIUtils::FromJString(env, launchPathJava);

	TitleInfo launchTitle{launchPath};

	using enum NativeEmulation::PrepareTitleResult;

	if (launchTitle.IsValid())
	{
		// the title might not be in the TitleList, so we add it as a temporary entry
		CafeTitleList::AddTitleFromPath(launchPath);
		// title is valid, launch from TitleId
		TitleId baseTitleId;
		if (!CafeTitleList::FindBaseTitleId(launchTitle.GetAppTitleId(), baseTitleId))
		{
			return ERROR_GAME_BASE_FILES_NOT_FOUND;
		}
		CafeSystem::PREPARE_STATUS_CODE r = CafeSystem::PrepareForegroundTitle(baseTitleId);
		if (r != CafeSystem::PREPARE_STATUS_CODE::SUCCESS)
		{
			return ERROR_UNKNOWN;
		}
	}
	else // if (launchTitle.GetFormat() == TitleInfo::TitleDataFormat::INVALID_STRUCTURE )
	{
		// title is invalid, if it's an RPX/ELF we can launch it directly
		// otherwise it's an error
		CafeTitleFileType fileType = DetermineCafeSystemFileType(launchPath);
		if (fileType == CafeTitleFileType::RPX || fileType == CafeTitleFileType::ELF)
		{
			CafeSystem::PREPARE_STATUS_CODE r = CafeSystem::PrepareForegroundTitleFromStandaloneRPX(launchPath);
			if (r != CafeSystem::PREPARE_STATUS_CODE::SUCCESS)
			{
				return ERROR_UNKNOWN;
			}
		}
		else if (launchTitle.GetInvalidReason() == TitleInfo::InvalidReason::NO_DISC_KEY)
		{
			return ERROR_NO_DISC_KEY;
		}
		else if (launchTitle.GetInvalidReason() == TitleInfo::InvalidReason::NO_TITLE_TIK)
		{
			return ERROR_NO_TITLE_TIK;
		}
		else
		{
			return ERROR_UNKNOWN;
		}
	}

	return SUCCESSFUL;
}

extern "C" [[maybe_unused]] JNIEXPORT void JNICALL
Java_info_cemu_cemu_nativeinterface_NativeEmulation_launchTitle([[maybe_unused]] JNIEnv* env, [[maybe_unused]] jclass clazz)
{
	CafeSystem::LaunchForegroundTitle();
}

extern "C" [[maybe_unused]] JNIEXPORT void JNICALL
Java_info_cemu_cemu_nativeinterface_NativeEmulation_pauseTitle([[maybe_unused]] JNIEnv* env, [[maybe_unused]] jclass clazz)
{
	CafeSystem::PauseTitle();
}

extern "C" [[maybe_unused]] JNIEXPORT void JNICALL
Java_info_cemu_cemu_nativeinterface_NativeEmulation_resumeTitle([[maybe_unused]] JNIEnv* env, [[maybe_unused]] jclass clazz)
{
	CafeSystem::ResumeTitle();
}

extern "C" [[maybe_unused]] JNIEXPORT jboolean JNICALL
Java_info_cemu_cemu_nativeinterface_NativeEmulation_isTitlePaused([[maybe_unused]] JNIEnv* env, [[maybe_unused]] jclass clazz)
{
	return CafeSystem::IsTitlePaused();
}

extern "C" [[maybe_unused]] JNIEXPORT void JNICALL
Java_info_cemu_cemu_nativeinterface_NativeEmulation_shutdownTitle([[maybe_unused]] JNIEnv* env, [[maybe_unused]] jclass clazz)
{
	CafeSystem::ShutdownTitle();
}

// ★ NesStation 前端全局 FPS 显示：返回核心渲染线程最近统计的真实呈现帧率
//   （每秒刷新；未出帧时为 0，前端据此回退心跳计数）。
extern "C" [[maybe_unused]] JNIEXPORT jdouble JNICALL
Java_info_cemu_cemu_nativeinterface_NativeEmulation_getRendererFps([[maybe_unused]] JNIEnv* env, [[maybe_unused]] jclass clazz)
{
	return (jdouble)LattePerformanceMonitor_GetRendererFps();
}
