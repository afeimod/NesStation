/* Minimal android/log.h stub for host-side syntax checking. */
#pragma once
#include <cstdarg>
#define ANDROID_LOG_DEBUG 3
#define ANDROID_LOG_INFO 4
#define ANDROID_LOG_WARN 5
#define ANDROID_LOG_ERROR 6
extern "C" int __android_log_print(int prio, const char* tag, const char* fmt, ...);
extern "C" int __android_log_vprint(int prio, const char* tag, const char* fmt, va_list ap);
