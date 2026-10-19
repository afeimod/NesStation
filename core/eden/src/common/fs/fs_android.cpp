// SPDX-FileCopyrightText: Copyright 2023 yuzu Emulator Project
// SPDX-License-Identifier: GPL-2.0-or-later

#include "common/android/android_common.h"
#include "common/android/id_cache.h"
#include "common/assert.h"
#include "common/fs/fs_android.h"
#include "common/string_util.h"

namespace Common::FS::Android {

void RegisterCallbacks(JNIEnv* env, jclass clazz) {
    env->GetJavaVM(&g_jvm);
    native_library = clazz;

    // ★ 契约加固（与 id_cache.cpp 同策略）：GetStaticMethodID 失败会挂起
    //   NoSuchMethodError（pending exception），若不清掉继续调任何做
    //   pending-check 的 JNI 函数（FindClass / NewGlobalRef…），ART 直接
    //   "No pending exception expected" SIGABRT。此处失败 → 清异常 →
    //   对应 MethodID 留空；所有调用点（OpenContentUri / GetSize /
    //   IsDirectory / Exists / GetParentDirectory / GetFilename）均已
    //   判空，缺哪个回调就降级为「不支持该操作」。
    s_get_parent_directory = env->GetStaticMethodID(native_library, "getParentDirectory",
                                                    "(Ljava/lang/String;)Ljava/lang/String;");
    s_get_filename = env->GetStaticMethodID(native_library, "getFilename",
                                            "(Ljava/lang/String;)Ljava/lang/String;");
    s_get_size = env->GetStaticMethodID(native_library, "getSize", "(Ljava/lang/String;)J");
    s_is_directory = env->GetStaticMethodID(native_library, "isDirectory", "(Ljava/lang/String;)Z");
    s_file_exists = env->GetStaticMethodID(native_library, "exists", "(Ljava/lang/String;)Z");
    s_open_content_uri = env->GetStaticMethodID(native_library, "openContentUri",
                                                "(Ljava/lang/String;Ljava/lang/String;)I");
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
    }
}

void UnRegisterCallbacks() {
    s_get_parent_directory = nullptr;
    s_get_filename = nullptr;

    s_get_size = nullptr;
    s_is_directory = nullptr;
    s_file_exists = nullptr;

    s_open_content_uri = nullptr;
}

bool IsContentUri(const std::string& path) {
    constexpr std::string_view prefix = "content://";
    if (path.size() < prefix.size()) [[unlikely]] {
        return false;
    }

    return path.find(prefix) == 0;
}

s32 OpenContentUri(const std::string& filepath, OpenMode openmode) {
    if (s_open_content_uri == nullptr)
        return -1;

    const char* mode = "";
    switch (openmode) {
    case OpenMode::Read:
        mode = "r";
        break;
    default:
        UNIMPLEMENTED();
        return -1;
    }
    auto env = Common::Android::GetEnvForThread();
    jstring j_filepath = Common::Android::ToJString(env, filepath);
    jstring j_mode = Common::Android::ToJString(env, mode);
    return env->CallStaticIntMethod(native_library, s_open_content_uri, j_filepath, j_mode);
}

u64 GetSize(const std::string& filepath) {
    if (s_get_size == nullptr) {
        return 0;
    }
    auto env = Common::Android::GetEnvForThread();
    return static_cast<u64>(env->CallStaticLongMethod(
        native_library, s_get_size,
        Common::Android::ToJString(Common::Android::GetEnvForThread(), filepath)));
}

bool IsDirectory(const std::string& filepath) {
    if (s_is_directory == nullptr) {
        return 0;
    }
    auto env = Common::Android::GetEnvForThread();
    return env->CallStaticBooleanMethod(
        native_library, s_is_directory,
        Common::Android::ToJString(Common::Android::GetEnvForThread(), filepath));
}

bool Exists(const std::string& filepath) {
    if (s_file_exists == nullptr) {
        return 0;
    }
    auto env = Common::Android::GetEnvForThread();
    return env->CallStaticBooleanMethod(
        native_library, s_file_exists,
        Common::Android::ToJString(Common::Android::GetEnvForThread(), filepath));
}

std::string GetParentDirectory(const std::string& filepath) {
    if (s_get_parent_directory == nullptr) {
        return 0;
    }
    auto env = Common::Android::GetEnvForThread();
    jstring j_return = static_cast<jstring>(env->CallStaticObjectMethod(
        native_library, s_get_parent_directory, Common::Android::ToJString(env, filepath)));
    if (!j_return) {
        return {};
    }
    return Common::Android::GetJString(env, j_return);
}

std::string GetFilename(const std::string& filepath) {
    if (s_get_filename == nullptr) {
        return 0;
    }
    auto env = Common::Android::GetEnvForThread();
    jstring j_return = static_cast<jstring>(env->CallStaticObjectMethod(
        native_library, s_get_filename, Common::Android::ToJString(env, filepath)));
    if (!j_return) {
        return {};
    }
    return Common::Android::GetJString(env, j_return);
}

} // namespace Common::FS::Android
