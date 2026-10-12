// SPDX-License-Identifier: MIT
// JNI bridge for the PPSSPP (PSP) core — NesStation in-process integration.
// Mirrors PspNative.kt exactly. Thin adapter over psp_loader.h.
#pragma once

#include <jni.h>

namespace pspbridge {
void registerNatives(); // placeholder for symmetry with other bridges
} // namespace pspbridge
