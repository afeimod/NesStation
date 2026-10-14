#include "JNIUtils.h"

#include "Cafe/CafeSystem.h"
#include "Cafe/Cheats/CheatManager.h"

#include <jni.h>

extern "C" {

// (Re)loads all cheat files matching the currently running title.
// Returns the number of parsed cheats.
JNIEXPORT jint JNICALL
Java_info_cemu_cemu_nativeinterface_NativeCheats_reloadCheats([[maybe_unused]] JNIEnv* env, [[maybe_unused]] jclass clazz)
{
	jint result = 0;
	JNIUtils::HandleNativeException(env, [&]() {
		const auto titleId = CafeSystem::GetForegroundTitleId();
		result = (jint)Cheats::CheatManager::GetInstance().LoadCheatsForTitle((uint64)titleId);
	});
	return result;
}

JNIEXPORT jint JNICALL
Java_info_cemu_cemu_nativeinterface_NativeCheats_getCheatCount([[maybe_unused]] JNIEnv* env, [[maybe_unused]] jclass clazz)
{
	jint result = 0;
	JNIUtils::HandleNativeException(env, [&]() {
		result = (jint)Cheats::CheatManager::GetInstance().GetCheatCount();
	});
	return result;
}

JNIEXPORT jstring JNICALL
Java_info_cemu_cemu_nativeinterface_NativeCheats_getCheatName(JNIEnv* env, [[maybe_unused]] jclass clazz, jint index)
{
	jstring result = nullptr;
	JNIUtils::HandleNativeException(env, [&]() {
		result = JNIUtils::ToJString(env, Cheats::CheatManager::GetInstance().GetCheatName((size_t)index));
	});
	return result;
}

JNIEXPORT jstring JNICALL
Java_info_cemu_cemu_nativeinterface_NativeCheats_getCheatComment(JNIEnv* env, [[maybe_unused]] jclass clazz, jint index)
{
	jstring result = nullptr;
	JNIUtils::HandleNativeException(env, [&]() {
		result = JNIUtils::ToJString(env, Cheats::CheatManager::GetInstance().GetCheatComment((size_t)index));
	});
	return result;
}

JNIEXPORT jboolean JNICALL
Java_info_cemu_cemu_nativeinterface_NativeCheats_isCheatEnabled([[maybe_unused]] JNIEnv* env, [[maybe_unused]] jclass clazz, jint index)
{
	jboolean result = false;
	JNIUtils::HandleNativeException(env, [&]() {
		result = (jboolean)Cheats::CheatManager::GetInstance().IsCheatEnabled((size_t)index);
	});
	return result;
}

JNIEXPORT void JNICALL
Java_info_cemu_cemu_nativeinterface_NativeCheats_setCheatEnabled([[maybe_unused]] JNIEnv* env, [[maybe_unused]] jclass clazz, jint index, jboolean enabled)
{
	JNIUtils::HandleNativeException(env, [&]() {
		Cheats::CheatManager::GetInstance().SetCheatEnabled((size_t)index, enabled != JNI_FALSE);
	});
}

JNIEXPORT jlong JNICALL
Java_info_cemu_cemu_nativeinterface_NativeCheats_getCurrentTitleId([[maybe_unused]] JNIEnv* env, [[maybe_unused]] jclass clazz)
{
	jlong result = 0;
	JNIUtils::HandleNativeException(env, [&]() {
		result = (jlong)CafeSystem::GetForegroundTitleId();
	});
	return result;
}

} // extern "C"
