/* Minimal JNI stub for host-side syntax checking of nds_bridge.cpp / nds_loader.cpp.
 * C++ member-function form (matches real jni.h when compiled as C++).
 * Regenerate: see NDS_FILTER_PERF_FIX_20260918.md section 五. */
#pragma once
#include <cstdint>
#include <cstddef>
#include <cstdarg>

#define JNIEXPORT
#define JNICALL
typedef int32_t jint;
typedef int32_t jsize;
typedef uint8_t jboolean;
typedef int8_t jbyte;
typedef int16_t jshort;
typedef int64_t jlong;
typedef void* jobject;
typedef void* jclass;
typedef void* jstring;
typedef void* jbyteArray;
typedef void* jshortArray;
typedef void* jintArray;
typedef void* jlongArray;
typedef void* jfloatArray;
typedef void* jbooleanArray;
typedef void* jobjectArray;
typedef void* jthrowable;
typedef void* jfieldID;
typedef void* jmethodID;
#define JNI_TRUE 1
#define JNI_FALSE 0
#define JNI_ABORT 2
#define JNI_OK 0
#define JNI_EDETACHED (-1)
#define JNI_VERSION_1_6 0x00010006

struct JavaVMStub;
using JavaVM = JavaVMStub;
struct JavaVMStub {
    jint AttachCurrentThread(void**, void*) { return 0; }
    jint DetachCurrentThread() { return 0; }
    jint GetEnv(void**, jint) { return 0; }
};

struct JNIEnvStub;
using JNIEnv = JNIEnvStub;
struct JNIEnvStub {
    jint GetEnv(void**, jint) { return 0; }
    jint* GetIntArrayElements(jintArray, jboolean*) { return nullptr; }
    void ReleaseIntArrayElements(jintArray, jint*, jint) {}
    jshort* GetShortArrayElements(jshortArray, jboolean*) { return nullptr; }
    void ReleaseShortArrayElements(jshortArray, jshort*, jint) {}
    jstring NewStringUTF(const char*) { return nullptr; }
    void SetIntArrayRegion(jintArray, jsize, jsize, const jint*) {}
    void GetByteArrayRegion(jbyteArray, jsize, jsize, jbyte*) {}
    void SetByteArrayRegion(jbyteArray, jsize, jsize, const jbyte*) {}
    void* GetDirectBufferAddress(jobject) { return nullptr; }
    jlong GetDirectBufferCapacity(jobject) { return 0; }
    jbyteArray NewByteArray(jsize) { return nullptr; }
    jintArray NewIntArray(jsize) { return nullptr; }
    jsize GetArrayLength(jobjectArray) { return 0; }
    const char* GetStringUTFChars(jstring, jboolean*) { return nullptr; }
    void ReleaseStringUTFChars(jstring, const char*) {}
    jsize GetStringUTFLength(jstring) { return 0; }
    jobject NewDirectByteBuffer(void*, jlong) { return nullptr; }
    jclass FindClass(const char*) { return nullptr; }
    jobject NewObject(jclass, jmethodID, ...) { return nullptr; }
    jmethodID GetMethodID(jclass, const char*, const char*) { return nullptr; }
    jfieldID GetFieldID(jclass, const char*, const char*) { return nullptr; }
    jobject GetObjectField(jobject, jfieldID) { return nullptr; }
    jint GetIntField(jobject, jfieldID) { return 0; }
    void SetIntField(jobject, jfieldID, jint) {}
    jboolean GetBooleanField(jobject, jfieldID) { return 0; }
    jint RegisterNatives(jclass, const void*, jint) { return 0; }
    jint ThrowNew(jclass, const char*) { return 0; }
};
