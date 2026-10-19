// SPDX-FileCopyrightText: Copyright 2026 Eden Emulator Project
// SPDX-License-Identifier: GPL-3.0-or-later

#include <jni.h>

#include <string>

#include "applets/software_keyboard.h"
#include "applets/web_browser.h"
#include "common/android/id_cache.h"
#include "common/assert.h"
#include "common/fs/fs_android.h"
#include "video_core/rasterizer_interface.h"
#include "common/android/multiplayer/multiplayer.h"
#include <network/network.h>


static JavaVM *s_java_vm;
static jclass s_native_library_class;
static jclass s_disk_cache_progress_class;
static jclass s_load_callback_stage_class;
static jclass s_game_dir_class;
static jmethodID s_game_dir_constructor;
static jmethodID s_exit_emulation_activity;
static jmethodID s_disk_cache_load_progress;
static jmethodID s_on_emulation_started;
static jmethodID s_on_emulation_stopped;
static jmethodID s_on_program_changed;
static jmethodID s_copy_to_storage;
static jmethodID s_file_exists;
static jmethodID s_file_extension;

static jclass s_game_class;
static jmethodID s_game_constructor;
static jfieldID s_game_title_field;
static jfieldID s_game_path_field;
static jfieldID s_game_program_id_field;
static jfieldID s_game_developer_field;
static jfieldID s_game_version_field;
static jfieldID s_game_is_homebrew_field;

static jclass s_string_class;
static jclass s_pair_class;
static jmethodID s_pair_constructor;
static jfieldID s_pair_first_field;
static jfieldID s_pair_second_field;

static jclass s_overlay_control_data_class;
static jmethodID s_overlay_control_data_constructor;
static jfieldID s_overlay_control_data_id_field;
static jfieldID s_overlay_control_data_enabled_field;
static jfieldID s_overlay_control_data_individual_scale_field;
static jfieldID s_overlay_control_data_landscape_position_field;
static jfieldID s_overlay_control_data_portrait_position_field;
static jfieldID s_overlay_control_data_foldable_position_field;

static jclass s_patch_class;
static jmethodID s_patch_constructor;
static jfieldID s_patch_enabled_field;
static jfieldID s_patch_name_field;
static jfieldID s_patch_version_field;
static jfieldID s_patch_type_field;
static jfieldID s_patch_program_id_field;
static jfieldID s_patch_title_id_field;

static jclass s_double_class;
static jmethodID s_double_constructor;
static jmethodID s_double_value_method;

static jclass s_integer_class;
static jmethodID s_integer_constructor;
static jmethodID s_integer_value_method;

static jclass s_boolean_class;
static jmethodID s_boolean_constructor;
static jmethodID s_boolean_value_method;

static jclass s_player_input_class;
static jmethodID s_player_input_constructor;
static jfieldID s_player_input_connected_field;
static jfieldID s_player_input_buttons_field;
static jfieldID s_player_input_analogs_field;
static jfieldID s_player_input_motions_field;
static jfieldID s_player_input_vibration_enabled_field;
static jfieldID s_player_input_vibration_strength_field;
static jfieldID s_player_input_body_color_left_field;
static jfieldID s_player_input_body_color_right_field;
static jfieldID s_player_input_button_color_left_field;
static jfieldID s_player_input_button_color_right_field;
static jfieldID s_player_input_profile_name_field;
static jfieldID s_player_input_use_system_vibrator_field;

static jclass s_yuzu_input_device_interface;
static jmethodID s_yuzu_input_device_get_name;
static jmethodID s_yuzu_input_device_get_guid;
static jmethodID s_yuzu_input_device_get_port;
static jmethodID s_yuzu_input_device_get_supports_vibration;
static jmethodID s_yuzu_input_device_vibrate;
static jmethodID s_yuzu_input_device_get_axes;
static jmethodID s_yuzu_input_device_has_keys;

static jmethodID s_add_netplay_message;
static jmethodID s_clear_chat;

static constexpr jint JNI_VERSION = JNI_VERSION_1_6;

namespace Common::Android {
    JNIEnv *GetEnvForThread() {
        thread_local static struct OwnedEnv {
            OwnedEnv() {
                status = s_java_vm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6);
                if (status == JNI_EDETACHED)
                    s_java_vm->AttachCurrentThread(&env, nullptr);
            }

            ~OwnedEnv() {
                if (status == JNI_EDETACHED)
                    s_java_vm->DetachCurrentThread();
            }

            int status;
            JNIEnv *env = nullptr;
        } owned;
        return owned.env;
    }

    jclass GetNativeLibraryClass() {
        return s_native_library_class;
    }

    jclass GetDiskCacheProgressClass() {
        return s_disk_cache_progress_class;
    }

    jclass GetDiskCacheLoadCallbackStageClass() {
        return s_load_callback_stage_class;
    }

    jclass GetGameDirClass() {
        return s_game_dir_class;
    }

    jmethodID GetGameDirConstructor() {
        return s_game_dir_constructor;
    }

    jmethodID GetExitEmulationActivity() {
        return s_exit_emulation_activity;
    }

    jmethodID GetDiskCacheLoadProgress() {
        return s_disk_cache_load_progress;
    }

    jmethodID GetCopyToStorage() {
        return s_copy_to_storage;
    }

    jmethodID GetFileExists() {
        return s_file_exists;
    }

    jmethodID GetFileExtension() {
        return s_file_extension;
    }

    jmethodID GetOnEmulationStarted() {
        return s_on_emulation_started;
    }

    jmethodID GetOnEmulationStopped() {
        return s_on_emulation_stopped;
    }

    jmethodID GetOnProgramChanged() {
        return s_on_program_changed;
    }

    jclass GetGameClass() {
        return s_game_class;
    }

    jmethodID GetGameConstructor() {
        return s_game_constructor;
    }

    jfieldID GetGameTitleField() {
        return s_game_title_field;
    }

    jfieldID GetGamePathField() {
        return s_game_path_field;
    }

    jfieldID GetGameProgramIdField() {
        return s_game_program_id_field;
    }

    jfieldID GetGameDeveloperField() {
        return s_game_developer_field;
    }

    jfieldID GetGameVersionField() {
        return s_game_version_field;
    }

    jfieldID GetGameIsHomebrewField() {
        return s_game_is_homebrew_field;
    }

    jclass GetStringClass() {
        return s_string_class;
    }

    jclass GetPairClass() {
        return s_pair_class;
    }

    jmethodID GetPairConstructor() {
        return s_pair_constructor;
    }

    jfieldID GetPairFirstField() {
        return s_pair_first_field;
    }

    jfieldID GetPairSecondField() {
        return s_pair_second_field;
    }

    jclass GetOverlayControlDataClass() {
        return s_overlay_control_data_class;
    }

    jmethodID GetOverlayControlDataConstructor() {
        return s_overlay_control_data_constructor;
    }

    jfieldID GetOverlayControlDataIdField() {
        return s_overlay_control_data_id_field;
    }

    jfieldID GetOverlayControlDataEnabledField() {
        return s_overlay_control_data_enabled_field;
    }

    jfieldID GetOverlayControlDataIndividualScaleField() {
        return s_overlay_control_data_individual_scale_field;
    }

    jfieldID GetOverlayControlDataLandscapePositionField() {
        return s_overlay_control_data_landscape_position_field;
    }

    jfieldID GetOverlayControlDataPortraitPositionField() {
        return s_overlay_control_data_portrait_position_field;
    }

    jfieldID GetOverlayControlDataFoldablePositionField() {
        return s_overlay_control_data_foldable_position_field;
    }

    jclass GetPatchClass() {
        return s_patch_class;
    }

    jmethodID GetPatchConstructor() {
        return s_patch_constructor;
    }

    jfieldID GetPatchEnabledField() {
        return s_patch_enabled_field;
    }

    jfieldID GetPatchNameField() {
        return s_patch_name_field;
    }

    jfieldID GetPatchVersionField() {
        return s_patch_version_field;
    }

    jfieldID GetPatchTypeField() {
        return s_patch_type_field;
    }

    jfieldID GetPatchProgramIdField() {
        return s_patch_program_id_field;
    }

    jfieldID GetPatchTitleIdField() {
        return s_patch_title_id_field;
    }

    jclass GetDoubleClass() {
        return s_double_class;
    }

    jmethodID GetDoubleConstructor() {
        return s_double_constructor;
    }

    jmethodID GetDoubleValueMethod() {
        return s_double_value_method;
    }

    jclass GetIntegerClass() {
        return s_integer_class;
    }

    jmethodID GetIntegerConstructor() {
        return s_integer_constructor;
    }

    jmethodID GetIntegerValueMethod() {
        return s_integer_value_method;
    }

    jclass GetBooleanClass() {
        return s_boolean_class;
    }

    jmethodID GetBooleanConstructor() {
        return s_boolean_constructor;
    }

    jmethodID GetBooleanValueMethod() {
        return s_boolean_value_method;
    }

    jclass GetPlayerInputClass() {
        return s_player_input_class;
    }

    jmethodID GetPlayerInputConstructor() {
        return s_player_input_constructor;
    }

    jfieldID GetPlayerInputConnectedField() {
        return s_player_input_connected_field;
    }

    jfieldID GetPlayerInputButtonsField() {
        return s_player_input_buttons_field;
    }

    jfieldID GetPlayerInputAnalogsField() {
        return s_player_input_analogs_field;
    }

    jfieldID GetPlayerInputMotionsField() {
        return s_player_input_motions_field;
    }

    jfieldID GetPlayerInputVibrationEnabledField() {
        return s_player_input_vibration_enabled_field;
    }

    jfieldID GetPlayerInputVibrationStrengthField() {
        return s_player_input_vibration_strength_field;
    }

    jfieldID GetPlayerInputBodyColorLeftField() {
        return s_player_input_body_color_left_field;
    }

    jfieldID GetPlayerInputBodyColorRightField() {
        return s_player_input_body_color_right_field;
    }

    jfieldID GetPlayerInputButtonColorLeftField() {
        return s_player_input_button_color_left_field;
    }

    jfieldID GetPlayerInputButtonColorRightField() {
        return s_player_input_button_color_right_field;
    }

    jfieldID GetPlayerInputProfileNameField() {
        return s_player_input_profile_name_field;
    }

    jfieldID GetPlayerInputUseSystemVibratorField() {
        return s_player_input_use_system_vibrator_field;
    }

    jclass GetYuzuInputDeviceInterface() {
        return s_yuzu_input_device_interface;
    }

    jmethodID GetYuzuDeviceGetName() {
        return s_yuzu_input_device_get_name;
    }

    jmethodID GetYuzuDeviceGetGUID() {
        return s_yuzu_input_device_get_guid;
    }

    jmethodID GetYuzuDeviceGetPort() {
        return s_yuzu_input_device_get_port;
    }

    jmethodID GetYuzuDeviceGetSupportsVibration() {
        return s_yuzu_input_device_get_supports_vibration;
    }

    jmethodID GetYuzuDeviceVibrate() {
        return s_yuzu_input_device_vibrate;
    }

    jmethodID GetYuzuDeviceGetAxes() {
        return s_yuzu_input_device_get_axes;
    }

    jmethodID GetYuzuDeviceHasKeys() {
        return s_yuzu_input_device_has_keys;
    }

    jmethodID GetAddNetPlayMessage() {
        return s_add_netplay_message;
    }

    jmethodID ClearChat() {
        return s_clear_chat;
    }

#ifdef __cplusplus
    extern "C" {
#endif

    jint InitFFmpegOnLoad(JavaVM *vm);

    void JNI_OnUnload(JavaVM *vm, void *reserved) {
        JNIEnv *env;
        if (vm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION) != JNI_OK) {
            return;
        }

        // UnInitialize Android Storage
        Common::FS::Android::UnRegisterCallbacks();
        env->DeleteGlobalRef(s_native_library_class);
        env->DeleteGlobalRef(s_disk_cache_progress_class);
        env->DeleteGlobalRef(s_load_callback_stage_class);
        env->DeleteGlobalRef(s_game_dir_class);
        env->DeleteGlobalRef(s_game_class);
        env->DeleteGlobalRef(s_string_class);
        env->DeleteGlobalRef(s_pair_class);
        env->DeleteGlobalRef(s_overlay_control_data_class);
        env->DeleteGlobalRef(s_patch_class);
        env->DeleteGlobalRef(s_double_class);
        env->DeleteGlobalRef(s_integer_class);
        env->DeleteGlobalRef(s_boolean_class);
        env->DeleteGlobalRef(s_player_input_class);
        env->DeleteGlobalRef(s_yuzu_input_device_interface);

        // UnInitialize applets
        SoftwareKeyboard::CleanupJNI(env);
        WebBrowser::CleanupJNI(env);

        AndroidMultiplayer::NetworkShutdown();
    }

#ifdef __cplusplus
    }
#endif

    // ★ NesStation 闪退加固（No pending exception expected SIGABRT）。
    //
    //   背景：FindClass 失败会在当前线程留下 pending 的
    //   ClassNotFoundException；若不清异常继续调用任何 JNI 函数
    //   （NewGlobalRef / 下一个 FindClass / GetMethodID ...），ART 将直接
    //   abort 进程（SIGABRT，Java 层无法捕获）。release 混淆构建下
    //   org.yuzu.yuzu_emu 契约包未被 keep 时（内部枚举
    //   DiskShaderCacheProgress$LoadCallbackStage 被改名），"进 Switch 核心
    //   设置"与"启动 Switch 游戏"两条路径（EdenEngine.coreInitOnce /
    //   loadRom → ensureInitialized → initJvm → Initialize）均在此 100%
    //   闪退。
    //
    //   处理：FindClass 失败时清掉挂起异常，改抛一个带缺失类名、可捕获的
    //   RuntimeException 并返回 nullptr；调用方（Initialize）立即返回。
    //   initJvm 把 pending 异常带回 Java 调用栈，EdenEngine.coreInitOnce 的
    //   catch (Throwable) 由此可以优雅降级并提示错误，而不是杀进程。
    static jclass FindClassOrThrow(JNIEnv* env, const char* name) {
        jclass local = env->FindClass(name);
        if (env->ExceptionCheck()) {
            env->ExceptionClear();
            const jclass runtime_exception = env->FindClass("java/lang/RuntimeException");
            if (runtime_exception != nullptr && !env->ExceptionCheck()) {
                env->ThrowNew(
                    runtime_exception,
                    (std::string("Eden JNI contract class missing: ") + name +
                     " (check -keep rules: org.yuzu.yuzu_emu.** / kotlin.Pair in "
                     "app/proguard-rules.pro)")
                        .c_str());
            }
            return nullptr;
        }
        return local;
    }

void Initialize(JavaVM* vm, JNIEnv *env) {
    s_java_vm = vm;
    InitFFmpegOnLoad(vm);

    if (env->ExceptionCheck()) {
        env->ExceptionClear();
    }

    // Initialize Java classes
    // ★ 应用侧契约类的 FindClass 一律走 FindClassOrThrow：失败 → 可捕获
    //   RuntimeException（带缺失类名）并立即返回；java/lang/* 为 boot
    //   classpath 类（R8 不可改名/裁剪），保持原样即可。
    const jclass native_library_class =
        FindClassOrThrow(env, "org/yuzu/yuzu_emu/NativeLibrary");
    if (native_library_class == nullptr) {
        return;
    }
    s_native_library_class = reinterpret_cast<jclass>(env->NewGlobalRef(native_library_class));

    const jclass disk_cache_progress_class =
        FindClassOrThrow(env, "org/yuzu/yuzu_emu/disk_shader_cache/DiskShaderCacheProgress");
    if (disk_cache_progress_class == nullptr) {
        return;
    }
    s_disk_cache_progress_class =
        reinterpret_cast<jclass>(env->NewGlobalRef(disk_cache_progress_class));

    const jclass load_callback_stage_class = FindClassOrThrow(
        env, "org/yuzu/yuzu_emu/disk_shader_cache/DiskShaderCacheProgress$LoadCallbackStage");
    if (load_callback_stage_class == nullptr) {
        return;
    }
    s_load_callback_stage_class =
        reinterpret_cast<jclass>(env->NewGlobalRef(load_callback_stage_class));

    const jclass game_dir_class = FindClassOrThrow(env, "org/yuzu/yuzu_emu/model/GameDir");
    if (game_dir_class == nullptr) {
        return;
    }
    s_game_dir_class = reinterpret_cast<jclass>(env->NewGlobalRef(game_dir_class));
    s_game_dir_constructor = env->GetMethodID(game_dir_class, "<init>",
                                              "(Ljava/lang/String;Z)V");
    env->DeleteLocalRef(game_dir_class);

    // Initialize methods
    s_exit_emulation_activity =
        env->GetStaticMethodID(s_native_library_class, "exitEmulationActivity", "(I)V");
    s_disk_cache_load_progress =
        env->GetStaticMethodID(s_disk_cache_progress_class, "loadProgress", "(III)V");
    s_copy_to_storage = env->GetStaticMethodID(s_native_library_class, "copyFileToStorage",
                                               "(Ljava/lang/String;Ljava/lang/String;)Z");
    s_file_exists = env->GetStaticMethodID(s_native_library_class, "exists",
                                           "(Ljava/lang/String;)Z");
    s_file_extension = env->GetStaticMethodID(s_native_library_class, "getFileExtension",
                                              "(Ljava/lang/String;)Ljava/lang/String;");
    s_on_emulation_started =
        env->GetStaticMethodID(s_native_library_class, "onEmulationStarted", "()V");
    s_on_emulation_stopped =
        env->GetStaticMethodID(s_native_library_class, "onEmulationStopped", "(I)V");
    s_on_program_changed =
        env->GetStaticMethodID(s_native_library_class, "onProgramChanged", "(I)V");

    const jclass game_class = FindClassOrThrow(env, "org/yuzu/yuzu_emu/model/Game");
    if (game_class == nullptr) {
        return;
    }
    s_game_class = reinterpret_cast<jclass>(env->NewGlobalRef(game_class));
    s_game_constructor = env->GetMethodID(game_class, "<init>",
                                          "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/"
                                          "String;Ljava/lang/String;Ljava/lang/String;Z)V");
    s_game_title_field = env->GetFieldID(game_class, "title", "Ljava/lang/String;");
    s_game_path_field = env->GetFieldID(game_class, "path", "Ljava/lang/String;");
    s_game_program_id_field = env->GetFieldID(game_class, "programId", "Ljava/lang/String;");
    s_game_developer_field = env->GetFieldID(game_class, "developer", "Ljava/lang/String;");
    s_game_version_field = env->GetFieldID(game_class, "version", "Ljava/lang/String;");
    s_game_is_homebrew_field = env->GetFieldID(game_class, "isHomebrew", "Z");
    env->DeleteLocalRef(game_class);

    const jclass string_class = env->FindClass("java/lang/String");
    s_string_class = reinterpret_cast<jclass>(env->NewGlobalRef(string_class));
    env->DeleteLocalRef(string_class);

    // ★ kotlin/Pair：kotlin-stdlib 类，本工程 R8 会混淆改名（tombstone 中
    //   gc.a / zc.w0 等 kotlin 类均被改名），必须 keep + 这里同样检查。
    const jclass pair_class = FindClassOrThrow(env, "kotlin/Pair");
    if (pair_class == nullptr) {
        return;
    }
    s_pair_class = reinterpret_cast<jclass>(env->NewGlobalRef(pair_class));
    s_pair_constructor =
        env->GetMethodID(pair_class, "<init>", "(Ljava/lang/Object;Ljava/lang/Object;)V");
    s_pair_first_field = env->GetFieldID(pair_class, "first", "Ljava/lang/Object;");
    s_pair_second_field = env->GetFieldID(pair_class, "second", "Ljava/lang/Object;");
    env->DeleteLocalRef(pair_class);

    const jclass overlay_control_data_class =
        FindClassOrThrow(env, "org/yuzu/yuzu_emu/overlay/model/OverlayControlData");
    if (overlay_control_data_class == nullptr) {
        return;
    }
    s_overlay_control_data_class =
        reinterpret_cast<jclass>(env->NewGlobalRef(overlay_control_data_class));
    s_overlay_control_data_constructor =
        env->GetMethodID(overlay_control_data_class, "<init>",
                         "(Ljava/lang/String;ZLkotlin/Pair;Lkotlin/Pair;Lkotlin/Pair;F)V");
    s_overlay_control_data_id_field =
        env->GetFieldID(overlay_control_data_class, "id", "Ljava/lang/String;");
    s_overlay_control_data_enabled_field =
        env->GetFieldID(overlay_control_data_class, "enabled", "Z");
    s_overlay_control_data_landscape_position_field =
        env->GetFieldID(overlay_control_data_class, "landscapePosition", "Lkotlin/Pair;");
    s_overlay_control_data_portrait_position_field =
        env->GetFieldID(overlay_control_data_class, "portraitPosition", "Lkotlin/Pair;");
    s_overlay_control_data_foldable_position_field =
        env->GetFieldID(overlay_control_data_class, "foldablePosition", "Lkotlin/Pair;");
    s_overlay_control_data_individual_scale_field =
        env->GetFieldID(overlay_control_data_class, "individualScale", "F");
    env->DeleteLocalRef(overlay_control_data_class);

    const jclass patch_class = FindClassOrThrow(env, "org/yuzu/yuzu_emu/model/Patch");
    if (patch_class == nullptr) {
        return;
    }
    s_patch_class = reinterpret_cast<jclass>(env->NewGlobalRef(patch_class));
    s_patch_constructor = env->GetMethodID(
        patch_class, "<init>",
        "(ZLjava/lang/String;Ljava/lang/String;ILjava/lang/String;Ljava/lang/String;JI)V");
    s_patch_enabled_field = env->GetFieldID(patch_class, "enabled", "Z");
    s_patch_name_field = env->GetFieldID(patch_class, "name", "Ljava/lang/String;");
    s_patch_version_field = env->GetFieldID(patch_class, "version", "Ljava/lang/String;");
    s_patch_type_field = env->GetFieldID(patch_class, "type", "I");
    s_patch_program_id_field = env->GetFieldID(patch_class, "programId", "Ljava/lang/String;");
    s_patch_title_id_field = env->GetFieldID(patch_class, "titleId", "Ljava/lang/String;");
    env->DeleteLocalRef(patch_class);

    const jclass double_class = env->FindClass("java/lang/Double");
    s_double_class = reinterpret_cast<jclass>(env->NewGlobalRef(double_class));
    s_double_constructor = env->GetMethodID(double_class, "<init>", "(D)V");
    s_double_value_method = env->GetMethodID(double_class, "doubleValue", "()D");
    env->DeleteLocalRef(double_class);

    const jclass int_class = env->FindClass("java/lang/Integer");
    s_integer_class = reinterpret_cast<jclass>(env->NewGlobalRef(int_class));
    s_integer_constructor = env->GetMethodID(int_class, "<init>", "(I)V");
    s_integer_value_method = env->GetMethodID(int_class, "intValue", "()I");
    env->DeleteLocalRef(int_class);

    const jclass boolean_class = env->FindClass("java/lang/Boolean");
    s_boolean_class = reinterpret_cast<jclass>(env->NewGlobalRef(boolean_class));
    s_boolean_constructor = env->GetMethodID(boolean_class, "<init>", "(Z)V");
    s_boolean_value_method = env->GetMethodID(boolean_class, "booleanValue", "()Z");
    env->DeleteLocalRef(boolean_class);

    const jclass player_input_class =
        FindClassOrThrow(env, "org/yuzu/yuzu_emu/features/input/model/PlayerInput");
    if (player_input_class == nullptr) {
        return;
    }
    s_player_input_class = reinterpret_cast<jclass>(env->NewGlobalRef(player_input_class));
    s_player_input_constructor = env->GetMethodID(
        player_input_class, "<init>",
        "(Z[Ljava/lang/String;[Ljava/lang/String;[Ljava/lang/String;ZIJJJJLjava/lang/String;Z)V");
    s_player_input_connected_field = env->GetFieldID(player_input_class, "connected", "Z");
    s_player_input_buttons_field =
        env->GetFieldID(player_input_class, "buttons", "[Ljava/lang/String;");
    s_player_input_analogs_field =
        env->GetFieldID(player_input_class, "analogs", "[Ljava/lang/String;");
    s_player_input_motions_field =
        env->GetFieldID(player_input_class, "motions", "[Ljava/lang/String;");
    s_player_input_vibration_enabled_field =
        env->GetFieldID(player_input_class, "vibrationEnabled", "Z");
    s_player_input_vibration_strength_field =
        env->GetFieldID(player_input_class, "vibrationStrength", "I");
    s_player_input_body_color_left_field =
        env->GetFieldID(player_input_class, "bodyColorLeft", "J");
    s_player_input_body_color_right_field =
        env->GetFieldID(player_input_class, "bodyColorRight", "J");
    s_player_input_button_color_left_field =
        env->GetFieldID(player_input_class, "buttonColorLeft", "J");
    s_player_input_button_color_right_field =
        env->GetFieldID(player_input_class, "buttonColorRight", "J");
    s_player_input_profile_name_field =
        env->GetFieldID(player_input_class, "profileName", "Ljava/lang/String;");
    s_player_input_use_system_vibrator_field =
        env->GetFieldID(player_input_class, "useSystemVibrator", "Z");
    env->DeleteLocalRef(player_input_class);

    const jclass yuzu_input_device_interface =
        FindClassOrThrow(env, "org/yuzu/yuzu_emu/features/input/YuzuInputDevice");
    if (yuzu_input_device_interface == nullptr) {
        return;
    }
    s_yuzu_input_device_interface =
        reinterpret_cast<jclass>(env->NewGlobalRef(yuzu_input_device_interface));
    s_yuzu_input_device_get_name =
        env->GetMethodID(yuzu_input_device_interface, "getName", "()Ljava/lang/String;");
    s_yuzu_input_device_get_guid =
        env->GetMethodID(yuzu_input_device_interface, "getGUID", "()Ljava/lang/String;");
    s_yuzu_input_device_get_port = env->GetMethodID(yuzu_input_device_interface, "getPort",
                                                    "()I");
    s_yuzu_input_device_get_supports_vibration =
        env->GetMethodID(yuzu_input_device_interface, "getSupportsVibration", "()Z");
    s_yuzu_input_device_vibrate = env->GetMethodID(yuzu_input_device_interface, "vibrate",
                                                   "(F)V");
    s_yuzu_input_device_get_axes =
        env->GetMethodID(yuzu_input_device_interface, "getAxes", "()[Ljava/lang/Integer;");
    s_yuzu_input_device_has_keys =
        env->GetMethodID(yuzu_input_device_interface, "hasKeys", "([I)[Z");
    env->DeleteLocalRef(yuzu_input_device_interface);
    s_add_netplay_message = env->GetStaticMethodID(s_native_library_class, "addNetPlayMessage",
                                                   "(ILjava/lang/String;)V");
    s_clear_chat = env->GetStaticMethodID(s_native_library_class, "clearChat", "()V");

    // Initialize Android Storage
    Common::FS::Android::RegisterCallbacks(env, s_native_library_class);

    // Initialize applets
    Common::Android::SoftwareKeyboard::InitJNI(env);
    Common::Android::WebBrowser::InitJNI(env);
}

} // namespace Common::Android
