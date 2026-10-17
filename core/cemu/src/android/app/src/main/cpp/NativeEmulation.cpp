#include "AndroidFilesystemCallbacks.h"
#include "AndroidInputHelpers.h"
#include "Cafe/CafeSystem.h"
#include "Cafe/HW/Latte/Core/LatteOverlay.h"
#include "Cafe/HW/Latte/Core/LatteTexture.h"
#include "Cafe/HW/Latte/LegacyShaderDecompiler/LatteDecompiler.h"
#include "Cafe/nesstation/NesStationPerf.h"
#include "Cafe/HW/Latte/Core/LattePerformanceMonitor.h"
#include "Cafe/HW/Latte/Renderer/Vulkan/VulkanAPI.h"
#include "Cafe/HW/Latte/Renderer/Vulkan/VulkanRenderer.h"
#include "Cafe/HW/Latte/Renderer/Vulkan/VulkanFrameGen.h"
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

// ★ NesStation R10: 分辨率缩放 = 纯呈现侧缩放（恢复 R8 已验证正确的语义）。
// 内部渲染目标缩放（R3~R7 的 LatteTexture_SetInternalRenderScale）会破坏
// 游戏光照/阴影采样（非 1x 画面发黑），彻底停用 —— 内部恒 1.0，游戏内部
// 渲染与 1x 完全一致，用户选择的档位仅影响最终上屏分辨率。
// ★ R13: >1x（1.5x/2x）不再钳制 —— 走与 <1x 对称的呈现侧超采样
//   （最终 quad 画进更大离屏目标后缩小 blit 回全尺寸交换链）。
extern "C" JNIEXPORT void JNICALL
Java_info_cemu_cemu_nativeinterface_NativeEmulation_setOutputRenderScale([[maybe_unused]] JNIEnv* env, [[maybe_unused]] jclass clazz, jfloat scale)
{
        VulkanRenderer::SetOutputRenderScale(scale);
}

// ★ NesStation R5: GamePad 悬浮画面更新频率（省电）—— 纯呈现侧节流
// （R3 时间片机制参数化），不触碰游戏渲染管线。0=每帧(最流畅)，
// 33≈30fps（省电），50=20fps（强省电）。被跳过的帧由合成器保持上一帧。
extern "C" JNIEXPORT void JNICALL
Java_info_cemu_cemu_nativeinterface_NativeEmulation_setPadPresentInterval([[maybe_unused]] JNIEnv* env, [[maybe_unused]] jclass clazz, jint intervalMs)
{
        VulkanRenderer::SetPadPresentIntervalMs(intervalMs < 0 ? 0 : (int)intervalMs);
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

// ★★★ NesStation R7: 「取消阴影渲染」—— 着色器反编译层把全部
// 阴影比较采样（SAMPLE_C 系列）替换为常数 1.0（完全受光），
// 阴影消失但画面亮度正常。状态进入像素着色器 auxHash，
// 运行中切换自动重编受影响着色器（短暂卡顿属正常）。
// ★★★ NesStation R11 前置声明（定义在各自实现文件）
namespace GX2
{
        void GX2SetDrawDoneSkipWait(bool skip);
}

// ★★★ NesStation R11: CPU 核心调度（默认0/大核优先1/小核优先2）。
// 对全部已注册模拟线程（3 个调度主机线程 + GPU 渲染线程）立即重绑，
// 运行中热生效；策略 0 = 解除绑定恢复全核。
extern "C" JNIEXPORT void JNICALL
Java_info_cemu_cemu_nativeinterface_NativeEmulation_setCoreSchedulingPolicy([[maybe_unused]] JNIEnv* env, [[maybe_unused]] jclass clazz, jint policy)
{
        int p = policy;
        if (p < 0 || p > 2)
                p = 0;
        NesStationPerf::s_coreSchedPolicy.store(p, std::memory_order_relaxed);
        NesStationPerf::ApplyPolicyToAll(p);
}

// ★★★ NesStation R11: CPUMode 运行时覆盖（-1=默认Auto推断 / 0=单核解释器 /
// 1=单核重编译 / 3=多核重编译）。启动期读取，切换后重启游戏完全生效。
extern "C" JNIEXPORT void JNICALL
Java_info_cemu_cemu_nativeinterface_NativeEmulation_setCpuModeOverride([[maybe_unused]] JNIEnv* env, [[maybe_unused]] jclass clazz, jint mode)
{
        ActiveSettings::SetCPUModeOverride(mode < -1 ? -1 : (int)mode);
}

// ★★★ NesStation R11: GX2DrawDone 快速模式（默认关 = 严格等待上游语义）
extern "C" JNIEXPORT void JNICALL
Java_info_cemu_cemu_nativeinterface_NativeEmulation_setDrawDoneSkipWait([[maybe_unused]] JNIEnv* env, [[maybe_unused]] jclass clazz, jboolean skip)
{
        GX2::GX2SetDrawDoneSkipWait(skip == JNI_TRUE);
}

// ★★★ NesStation R12: ADPF 动态性能提示开关（默认开；API 31+ 生效）
extern "C" JNIEXPORT void JNICALL
Java_info_cemu_cemu_nativeinterface_NativeEmulation_setAdpfEnabled([[maybe_unused]] JNIEnv* env, [[maybe_unused]] jclass clazz, jboolean enable)
{
        NesStationPerf::SetAdpfEnabled(enable == JNI_TRUE);
}

// ★★★ NesStation R12: 帧生成开关（实验性，默认关；呈现侧光流插帧）
extern "C" JNIEXPORT void JNICALL
Java_info_cemu_cemu_nativeinterface_NativeEmulation_setFrameGenEnabled([[maybe_unused]] JNIEnv* env, [[maybe_unused]] jclass clazz, jboolean enable)
{
        VulkanFrameGen::SetEnabled(enable == JNI_TRUE);
}

// ★★★ NesStation R13: Lossless.dll 加载（PE 数据容器解析 + 资源校验）。
// 返回 0 = 成功；2/3 = 解析/校验失败（GetFrameGenDllError 取原因）。
extern "C" JNIEXPORT jint JNICALL
Java_info_cemu_cemu_nativeinterface_NativeEmulation_setFrameGenDll(JNIEnv* env, [[maybe_unused]] jclass clazz, jstring pathJava)
{
        const char* path = env->GetStringUTFChars(pathJava, nullptr);
        std::string pathStr = path ? path : "";
        if (path)
                env->ReleaseStringUTFChars(pathJava, path);
        return (jint)VulkanFrameGen::SetDllPath(pathStr);
}

// ★★★ NesStation R13: 最近一次 DLL 加载错误（空串 = 就绪）
extern "C" JNIEXPORT jstring JNICALL
Java_info_cemu_cemu_nativeinterface_NativeEmulation_getFrameGenDllError(JNIEnv* env, [[maybe_unused]] jclass clazz)
{
        std::string err = VulkanFrameGen::GetDllError();
        return env->NewStringUTF(err.c_str());
}

// ★★★ NesStation R13: DLL 是否已就绪（UI 状态显示）
extern "C" JNIEXPORT jboolean JNICALL
Java_info_cemu_cemu_nativeinterface_NativeEmulation_isFrameGenDllReady([[maybe_unused]] JNIEnv* env, [[maybe_unused]] jclass clazz)
{
        return VulkanFrameGen::IsDllReady() ? JNI_TRUE : JNI_FALSE;
}

// ★★★ NesStation R13: 帧生成运行配置（倍率 2/3/4、光流比例 0.25..1.0、性能模式）
extern "C" JNIEXPORT void JNICALL
Java_info_cemu_cemu_nativeinterface_NativeEmulation_setFrameGenConfig([[maybe_unused]] JNIEnv* env, [[maybe_unused]] jclass clazz, jint multiplier, jfloat flowScale, jboolean performanceMode)
{
        VulkanFrameGen::SetConfig((int)multiplier, (float)flowScale, performanceMode == JNI_TRUE);
}

extern "C" JNIEXPORT void JNICALL
Java_info_cemu_cemu_nativeinterface_NativeEmulation_setShadowRenderingDisabled([[maybe_unused]] JNIEnv* env, [[maybe_unused]] jclass clazz, jboolean disabled)
{
        LatteDecompiler_SetShadowRenderingDisabled(disabled == JNI_TRUE);
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
