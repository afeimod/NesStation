/* Minimal ANativeWindow stub for host-side syntax checking. */
#pragma once
#include <cstdint>
struct ANativeWindow;
enum { WINDOW_FORMAT_RGBA_8888 = 1, WINDOW_FORMAT_RGBX_8888 = 2, WINDOW_FORMAT_RGB_565 = 4 };
struct ANativeWindow_Buffer { int32_t width; int32_t height; int32_t stride; int32_t format; void* bits; };
extern "C" {
int32_t ANativeWindow_getFormat(ANativeWindow*);
int32_t ANativeWindow_getWidth(ANativeWindow*);
int32_t ANativeWindow_getHeight(ANativeWindow*);
int32_t ANativeWindow_lock(ANativeWindow*, ANativeWindow_Buffer*, int32_t*);
int32_t ANativeWindow_unlockAndPost(ANativeWindow*);
int32_t ANativeWindow_setBuffersGeometry(ANativeWindow*, int32_t, int32_t, int32_t);
void ANativeWindow_release(ANativeWindow*);
ANativeWindow* ANativeWindow_acquire(ANativeWindow*);
}
