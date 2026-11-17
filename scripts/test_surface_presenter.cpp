// Regression: a blocked 50 Hz display must not block guest frame production.
#include "core_shared.h"
#include <cassert>
#include <chrono>
#include <condition_variable>

struct ANativeWindow { std::atomic<int> refs{1}; };
static std::mutex gate;
static std::condition_variable entered, releaseDisplay;
static bool blocked = false, released = false;
static std::vector<uint32_t> storage(4);
static std::vector<uint32_t> posted;
extern "C" {
int __android_log_print(int, const char*, const char*, ...) { return 0; }
int __android_log_vprint(int, const char*, const char*, va_list) { return 0; }
ANativeWindow* ANativeWindow_acquire(ANativeWindow* w) { ++w->refs; return w; }
void ANativeWindow_release(ANativeWindow* w) { --w->refs; }
int32_t ANativeWindow_setBuffersGeometry(ANativeWindow*, int32_t, int32_t, int32_t) { return 0; }
int32_t ANativeWindow_lock(ANativeWindow*, ANativeWindow_Buffer* b, int32_t*) {
    std::unique_lock<std::mutex> lock(gate);
    blocked = true;
    entered.notify_one();
    releaseDisplay.wait(lock, [] { return released; });
    *b = {2, 2, 2, WINDOW_FORMAT_RGBA_8888, storage.data()};
    return 0;
}
int32_t ANativeWindow_unlockAndPost(ANativeWindow*) {
    std::lock_guard<std::mutex> lock(gate);
    posted.push_back(storage.front());
    entered.notify_one();
    return 0;
}
}

int main() {
    using namespace std::chrono_literals;
    ANativeWindow target;
    ANativeWindow* surface = &target;
    std::mutex surfaceMutex;
    {
        coreshared::LatestFramePresenter presenter;
        uint32_t pixels[4] = {0xff112233, 0, 0, 0};
        presenter.submit(surface, surfaceMutex, pixels, 2, 2, 2, 0, false);
        {
            std::unique_lock<std::mutex> lock(gate);
            assert(entered.wait_for(lock, 2s, [] { return blocked; }));
        }
        // The renderer remains blocked through 120 guest submissions.
        for (unsigned i = 0; i < 120; ++i) {
            pixels[0] = 0xff000000 | i;
            presenter.submit(surface, surfaceMutex, pixels, 2, 2, 2, 0, false);
        }
        pixels[0] = 0; // queued frame must own its pixels
        {
            std::lock_guard<std::mutex> lock(surfaceMutex);
            surface = nullptr; // detach while presentation is blocked
        }
        {
            std::lock_guard<std::mutex> lock(gate);
            released = true;
        }
        releaseDisplay.notify_one();
        {
            std::unique_lock<std::mutex> lock(gate);
            assert(entered.wait_for(lock, 2s, [] { return posted.size() == 2; }));
            assert(posted[0] == 0xff332211); // original frame, channel swizzle
            assert(posted[1] == 0xff770000); // latest only, intermediate frames dropped
        }
    }
    assert(target.refs == 1);
    std::puts("PASS: blocked display, latest frame, pixel ownership, detach, window references");
}
