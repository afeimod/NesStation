#pragma once
#include "native_window.h"
extern "C" {
ANativeWindow* ANativeWindow_fromSurface(void* env, void* surface);
}
