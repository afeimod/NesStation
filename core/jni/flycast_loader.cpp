// SPDX-License-Identifier: MIT
// libretro frontend that drives the prebuilt Flycast (Dreamcast / Naomi /
// Atomiswave) core — see flycast_loader.h for the architecture overview.
//
// This loader follows the same dlopen() pattern as psx_loader.cpp; the only
// structural difference is the hardware-rendering path (EGL + GLES3 FBO
// compositor) required by flycast's GPU renderer.

#include "flycast_loader.h"
#include "shared/core_shared.h"

#include <libretro.h>
#include <android/log.h>
#include <android/native_window.h>
#include <EGL/egl.h>
#include <EGL/eglext.h>
#include <GLES3/gl3.h>

#include <dlfcn.h>
#include <atomic>
#include <algorithm>
#include <cctype>
#include <cmath>
#include <cstdarg>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <map>
#include <mutex>
#include <string>
#include <vector>

// SIGSEGV armor: flycast installs its own SIGSEGV handler (fault_handler)
// during retro_init() to service writes into write-protected RAM/VRAM pages
// (dynarec code protection). If anything in this process overwrites the
// process-wide SIGSEGV handler afterwards (GL driver init, another libretro
// core, crash SDK), protected-page writes die instead of being rewritten —
// exactly the single-frame addrspace::write32 + [anon:.bss] tombstone we
// were chasing. We re-assert the core's handler around game load and wrap it
// with a probe that logs fault addr + registers for diagnosis.
#include <csignal>
#include <cerrno>
#include <ucontext.h>

#define TAG "flycastcore-rom"
#undef LOGI
#undef LOGW
#undef LOGE
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN,  TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

namespace flycastcore::rom {

// ---------------------------------------------------------------------------
// FBO size caps. The core renders at reicast_internal_resolution; the FBO is
// grown on demand up to 4x native (2560x1920) which is the maximum the UI
// exposes. CPU readback (screenshots / no-surface preview) uses the same cap
// so captures are never cropped.
// ---------------------------------------------------------------------------
static constexpr int kMaxFboW = 2560;
static constexpr int kMaxFboH = 1920;

static constexpr int TARGET_SAMPLE_RATE = coreshared::TARGET_SAMPLE_RATE;

// ---------------------------------------------------------------------------
// libretro function pointer types
// ---------------------------------------------------------------------------
typedef void   (*retro_init_t)(void);
typedef void   (*retro_deinit_t)(void);
typedef unsigned (*retro_api_version_t)(void);
typedef void   (*retro_get_system_info_t)(struct retro_system_info* info);
typedef void   (*retro_get_system_av_info_t)(struct retro_system_av_info* info);
typedef void   (*retro_set_controller_port_device_t)(unsigned port, unsigned device);
typedef void   (*retro_reset_t)(void);
typedef void   (*retro_run_t)(void);
typedef size_t (*retro_serialize_size_t)(void);
typedef bool   (*retro_serialize_t)(void* data, size_t size);
typedef bool   (*retro_unserialize_t)(const void* data, size_t size);
typedef void*  (*retro_get_memory_data_t)(unsigned id);
typedef size_t (*retro_get_memory_size_t)(unsigned id);
typedef bool   (*retro_load_game_t)(const struct retro_game_info* game);
typedef void   (*retro_unload_game_t)(void);
typedef void   (*retro_set_environment_t)(retro_environment_t);
typedef void   (*retro_set_video_refresh_t)(retro_video_refresh_t);
typedef void   (*retro_set_audio_sample_t)(retro_audio_sample_t);
typedef void   (*retro_set_audio_sample_batch_t)(retro_audio_sample_batch_t);
typedef void   (*retro_set_input_poll_t)(retro_input_poll_t);
typedef void   (*retro_set_input_state_t)(retro_input_state_t);

// ---------------------------------------------------------------------------
// State — dlopen handle and resolved symbols
// ---------------------------------------------------------------------------
static void* s_coreLib = nullptr;

static retro_init_t                      s_retro_init = nullptr;
static retro_deinit_t                    s_retro_deinit = nullptr;
static retro_api_version_t               s_retro_api_version = nullptr;
static retro_get_system_info_t           s_retro_get_system_info = nullptr;
static retro_get_system_av_info_t        s_retro_get_system_av_info = nullptr;
static retro_set_controller_port_device_t s_retro_set_controller_port_device = nullptr;
static retro_reset_t                     s_retro_reset = nullptr;
static retro_run_t                       s_retro_run = nullptr;
static retro_serialize_size_t            s_retro_serialize_size = nullptr;
static retro_serialize_t                 s_retro_serialize = nullptr;
static retro_unserialize_t               s_retro_unserialize = nullptr;
static retro_load_game_t                 s_retro_load_game = nullptr;
static retro_unload_game_t               s_retro_unload_game = nullptr;
static retro_set_environment_t           s_retro_set_environment = nullptr;
static retro_set_video_refresh_t         s_retro_set_video_refresh = nullptr;
static retro_set_audio_sample_t          s_retro_set_audio_sample = nullptr;
static retro_set_audio_sample_batch_t    s_retro_set_audio_sample_batch = nullptr;
static retro_set_input_poll_t            s_retro_set_input_poll = nullptr;
static retro_set_input_state_t           s_retro_set_input_state = nullptr;

static bool s_loaded = false;
static bool s_gameLoaded = false;
static int  s_sampleRate = 0;
static double s_refreshRate = 60.0;
static int  s_region = 0;
static std::string s_systemDir;
static std::string s_saveDir;
static std::string s_saveName;
static std::string s_lastRomPath;
static std::string s_coreMessage;
static std::string s_coreError;
static std::string s_coreLibPath;

// Persistent copy of the currently-loaded ROM path — flycast keeps
// game_info.path (opens the disc image by name, resolves .cue siblings,
// MAME romsets etc.), so the pointer must outlive the JNI string release.
static std::string s_romPath;

// Fallback frame buffer (ARGB 0xAARRGGBB) — only written on CPU readback
// (no-surface mode / captureFrame).
static std::mutex s_frameMtx;
static std::vector<uint32_t> s_frame;
static unsigned s_frameW = 0;
static unsigned s_frameH = 0;
static std::atomic<bool> s_newFrame{false};

static unsigned s_videoW = 640;
static unsigned s_videoH = 480;

// FPS HUD: count frames the core actually rendered (video_cb with
// RETRO_HW_FRAME_BUFFER_VALID).
static std::atomic<int> s_presentedFrames{0};

// Gamepad bits (project UI layout, port 0..3) — converted to libretro
// layout by dcProjectToLibretro() when the core queries input.
static std::atomic<uint16_t> s_pad1{0};
static std::atomic<uint16_t> s_pad2{0};
static std::atomic<uint16_t> s_pad3{0};
static std::atomic<uint16_t> s_pad4{0};

// Analog stick axes (libretro convention: right/down positive), port 0..3,
// axis 0..3 = LX/LY/RX/RY. Written from the UI thread via JNI, read from
// the input callback on the emu thread — plain atomics, no lock needed.
static std::atomic<int16_t> s_analog[4][4] = {};

static std::atomic<bool> s_fastForward{false};
static std::atomic<int>  s_ffFrameSkip{0};
static std::atomic<int>  s_ffMaxSkip{6};

static coreshared::AudioRingBuffer s_audio;
static coreshared::AudioResampler s_resampler;

static std::mutex s_optMtx;
static std::map<std::string, std::string> s_options;
static std::atomic<bool> s_optionsChanged{false};

// Controller-port device switching queued from the UI thread — applied at
// the top of the next stepFrame() (same thread that calls retro_run()).
static std::atomic<uint32_t> s_pendingPortDevices{0xFFu << 24};

// ---------------------------------------------------------------------------
// EGL / GL state — created and used EXCLUSIVELY on the emulation thread.
// ---------------------------------------------------------------------------
static ANativeWindow* s_window = nullptr;      // may be null (pbuffer mode)
static bool s_windowChanged = false;           // surface swapped since last ensureEgl

static EGLDisplay s_eglDisplay = EGL_NO_DISPLAY;
static EGLContext s_eglContext = EGL_NO_CONTEXT;
static EGLSurface s_eglSurface = EGL_NO_SURFACE;   // window or pbuffer
static bool s_eglSurfaceIsWindow = false;
static int  s_eglClientVersion = 3;

// Hardware render callback registered by the core via SET_HW_RENDER.
static struct retro_hw_render_callback s_hwRender{};
static std::atomic<bool> s_hwRenderAccepted{false};
static bool s_ctxResetCalled = false;

// ---------------------------------------------------------------------------
// SIGSEGV handler armor v2.
//
// Why this exists: flycast's fault_handler (installed by os_InstallFaultHandler
// during retro_init) repairs SIGSEGVs caused by writes into write-protected
// RAM / VRAM pages and on-demand FPCB pages (bm_RamWriteAccess /
// VramLockedWrite / bm_lockedWrite). Those faults are PART OF THE DESIGN —
// every DC game hits them constantly. If anything in the process replaces the
// process-wide SIGSEGV sigaction after retro_init (EGL/GL driver init, SDL,
// another libretro core, an OS/game-mode injected lib, ...), the very next
// protected-page write kills the process at the faulting store: the
// `addrspace::write32 ... / [anon:.bss]` two-frame tombstone.
//
// We can't edit the prebuilt core, but it exports os_InstallFaultHandler
// (dynsym `_Z22os_InstallFaultHandlerv`) and fault_handler
// (dynsym `_Z13fault_handleriP7siginfoPv`) — so we:
//   1. Verify the SIGSEGV chain at every checkpoint (after retro_init, after
//      load_game, after EGL context creation, before EVERY retro_run):
//      top of chain must be our probe and directly under it the core's
//      fault_handler. Anything else → identify the clobberer in logcat
//      (dladdr: library + offset) and rebuild the chain.
//   2. The probe itself is ASYNC-SIGNAL-SAFE: it only stores {fault addr, PC}
//      into a preallocated lock-free ring (NO logging / allocation / locks
//      inside the signal handler — the previous version called
//      __android_log_print in signal context, which is unsafe) and forwards
//      the signal unchanged to the core's fault_handler.
//   3. stepFrame() drains the ring OUTSIDE the signal context and logs the
//      repair counters, so logcat shows that repairs happen — and when they
//      stop, that is the moment something clobbered the chain.
// ---------------------------------------------------------------------------
typedef void (*os_install_fault_handler_t)(void);
static os_install_fault_handler_t s_osInstallFaultHandler = nullptr;

// Core's fault_handler address (dynsym `_Z13fault_handleriP7siginfoPv`) —
// used to detect whether the process-wide SIGSEGV disposition is still the
// core's handler, so we can re-wrap it with the probe WITHOUT re-running
// os_InstallFaultHandler (which would chain fault_handler onto itself and
// recurse forever when a fault is genuinely unhandled).
static void (*s_faultHandlerFn)(int, siginfo_t*, void*) = nullptr;

// Previous SIGSEGV disposition saved when we installed the probe (normally
// the core's fault_handler; restored when the probe is unwrapped before core
// teardown).
static struct sigaction s_prevSegvAction;
static std::atomic<bool> s_segvProbeInstalled{false};

// Lock-free fault-record ring written by the signal handler (async-signal-
// safe: preallocated, atomics only, 8 slots, newest wins on overflow).
struct SegvFaultRecord {
    std::atomic<uintptr_t> addr;
    std::atomic<uintptr_t> pc;
};
static SegvFaultRecord s_segvRing[8];
static std::atomic<unsigned> s_segvRingIdx{0};
static std::atomic<int> s_segvPending{0};     // records not yet reported
static std::atomic<int> s_segvTotal{0};       // repairs since process start
static std::atomic<int> s_segvLoggedTotal{0}; // last total reported to logcat

// Forwarding probe (SA_SIGINFO). STRICTLY async-signal-safe: no logging, no
// allocation, no locks, no errno-visible calls on the normal path. Passes the
// exact siginfo/ucontext straight through to the core's fault_handler.
static void segvProbeHandler(int sig, siginfo_t* si, void* ctx) {
    const unsigned idx = s_segvRingIdx.fetch_add(1, std::memory_order_relaxed) & 7u;
    s_segvRing[idx].addr.store(si ? (uintptr_t)si->si_addr : 0,
                               std::memory_order_relaxed);
    uintptr_t pc = 0;
#if defined(__aarch64__)
    if (ctx) pc = (uintptr_t)((ucontext_t*)ctx)->uc_mcontext.pc;
#elif defined(__arm__)
    if (ctx) pc = (uintptr_t)((ucontext_t*)ctx)->uc_mcontext.arm_pc;
#elif defined(__x86_64__)
    if (ctx) pc = (uintptr_t)((ucontext_t*)ctx)->uc_mcontext.gregs[REG_RIP];
#endif
    s_segvRing[idx].pc.store(pc, std::memory_order_relaxed);
    s_segvTotal.fetch_add(1, std::memory_order_relaxed);
    s_segvPending.fetch_add(1, std::memory_order_relaxed);

    // Hand off to the saved disposition — normally flycast's fault_handler,
    // which performs the bm_RamWriteAccess / VramLockedWrite / rewrite fixups.
    if (s_prevSegvAction.sa_sigaction != nullptr &&
        (s_prevSegvAction.sa_flags & SA_SIGINFO)) {
        s_prevSegvAction.sa_sigaction(sig, si, ctx);
        return;
    }
    // Underlying disposition was a plain handler (no SA_SIGINFO).
    if (s_prevSegvAction.sa_handler != nullptr &&
        s_prevSegvAction.sa_handler != SIG_DFL &&
        s_prevSegvAction.sa_handler != SIG_IGN) {
        s_prevSegvAction.sa_handler(sig);
        return;
    }
    // Nothing under us: restore default so the crash stays observable.
    signal(sig, SIG_DFL);
    raise(sig);
}

// Identify a handler pointer (library + offset) in logcat. NOT signal-safe —
// only call from regular (non-signal) context.
static void logSigactionOwner(const char* what, void* fn) {
    if (!fn) {
        LOGW("SIGSEGV %s: <null/SIG_DFL>", what);
        return;
    }
    Dl_info info{};
    if (dladdr(fn, &info) && info.dli_fname) {
        LOGW("SIGSEGV %s: %p (%s + %#lx)", what, fn, info.dli_fname,
             (unsigned long)((uintptr_t)fn - (uintptr_t)info.dli_fbase));
    } else {
        LOGW("SIGSEGV %s: %p (unknown library)", what, fn);
    }
}

// Re-assert the core's fault_handler and (re)wrap it with the probe.
//
// Careful with ordering: os_InstallFaultHandler() saves the *current* SIGSEGV
// disposition as its next_segv_handler and installs fault_handler. So it must
// only be called while the current handler is NOT fault_handler — otherwise
// the core would chain fault_handler onto itself and recurse forever once a
// fault cannot be repaired. Our probe always sits on top:
//   SIGSEGV -> probe (records, forwards) -> fault_handler (repairs) ->
//   core's next (whatever was installed when the core last re-asserted).
//
// `force` re-installs even when the probe looks present (used after EGL/GL
// driver init, which is the most likely window for a sigaction clobber).
static void armSegvHandler(bool force) {
    if (!s_osInstallFaultHandler || !s_faultHandlerFn)
        return;

    // Current disposition.
    struct sigaction cur{};
    if (sigaction(SIGSEGV, nullptr, &cur) != 0)
        return;

    // Fully armed (probe on top) AND the chain below the probe intact and no
    // force -> nothing to do. Checking the chain (not just the top) matters:
    // a clobber-and-restore sequence can leave our saved prev pointing at a
    // stale handler, which would swallow faults on the very next frame.
    if (!force && cur.sa_sigaction == segvProbeHandler && s_segvProbeInstalled.load() &&
        s_prevSegvAction.sa_sigaction == s_faultHandlerFn &&
        (s_prevSegvAction.sa_flags & SA_SIGINFO))
        return;

    // If the current handler is one of ours (the probe), unwrap it so the
    // following logic starts from the real underlying disposition.
    if (cur.sa_sigaction == segvProbeHandler) {
        if (s_prevSegvAction.sa_sigaction != s_faultHandlerFn)
            logSigactionOwner("chain: handler under our probe was",
                              (void*)s_prevSegvAction.sa_sigaction);
        sigaction(SIGSEGV, &s_prevSegvAction, nullptr);
        s_segvProbeInstalled.store(false);
        if (sigaction(SIGSEGV, nullptr, &cur) != 0)
            return;
    }

    // If the process-wide handler is not the core's fault_handler anymore,
    // somebody clobbered it — identify the clobberer in logcat, then
    // (re)assert it via the exported installer. That records whatever is
    // currently installed as the core's next handler — chain preserved.
    if (cur.sa_sigaction != s_faultHandlerFn) {
        logSigactionOwner("disposition was replaced by", (void*)cur.sa_sigaction);
        LOGW("SIGSEGV armor: re-asserting core os_InstallFaultHandler() "
             "(expected fault_handler %p)", (void*)s_faultHandlerFn);
        s_osInstallFaultHandler();
    }

    // Wrap the (now core) handler with our signal-safe probe.
    struct sigaction act{};
    act.sa_sigaction = segvProbeHandler;
    sigemptyset(&act.sa_mask);
    act.sa_flags = SA_SIGINFO;
    if (sigaction(SIGSEGV, &act, &s_prevSegvAction) != 0) {
        LOGE("sigaction(SIGSEGV) probe install failed: %s", strerror(errno));
        return;
    }
    s_segvProbeInstalled.store(true);
    LOGI("SIGSEGV armor armed: probe -> core fault_handler %p",
         (void*)s_faultHandlerFn);
}

// Called from stepFrame AFTER retro_run — OUTSIDE any signal context — to
// report how many protected-page faults the core handler repaired since the
// previous frame. When these lines STOP appearing while the game still runs,
// something clobbered the SIGSEGV chain and the next protected-page write
// would have crashed (armor re-arms it before the next frame).
static void drainSegvReports() {
    int pending = s_segvPending.exchange(0, std::memory_order_acq_rel);
    if (pending <= 0)
        return;
    const int total = s_segvTotal.load(std::memory_order_relaxed);
    const int logged = s_segvLoggedTotal.load(std::memory_order_relaxed);
    if (total < 8 || total - logged >= 128) {
        s_segvLoggedTotal.store(total, std::memory_order_relaxed);
        const unsigned last = (s_segvRingIdx.load(std::memory_order_relaxed) - 1) & 7u;
        LOGI("SIGSEGV protected-page faults repaired: +%d (total %d), "
             "last addr=%#lx pc=%#lx",
             pending, total,
             (unsigned long)s_segvRing[last].addr.load(std::memory_order_relaxed),
             (unsigned long)s_segvRing[last].pc.load(std::memory_order_relaxed));
    }
}

// Core render target (our FBO) + compositor program.
static GLuint s_fbo = 0;
static GLuint s_fboTexture = 0;
static GLuint s_fboDepthStencil = 0;
static int    s_fboW = 0;
static int    s_fboH = 0;
static GLuint s_program = 0;
static GLint  s_aPosLoc = -1;
static GLint  s_uTexLoc = -1;
static GLint  s_uFlipLoc = -1;
static bool   s_glObjectsValid = false;

// Last frame the core presented (video_cb VALID) — drives FBO sizing and
// the compositor viewport.
static unsigned s_lastFrameW = 0;
static unsigned s_lastFrameH = 0;
static bool     s_frameReady = false;   // new frame in the FBO after retro_run

// CPU readback request (captureFrame) — consumed by the next stepFrame().
static std::atomic<bool> s_readbackRequest{false};

// ---------------------------------------------------------------------------
// Initialize Flycast core options with upstream defaults.
// Keys MUST match flycast's shell/libretro/libretro_core_options.h exactly
// (CORE_OPTION_NAME = "reicast", verified against the buildbot core).
// UI-set options (applyCoreOptions -> setCoreOption runs BEFORE loadRom on
// first load) are never overwritten here — setIfMissing keeps user choices.
// ---------------------------------------------------------------------------
static void initDefaultOptions() {
    auto setIfMissing = [](const std::string& key, const std::string& val) {
        if (s_options.find(key) == s_options.end()) {
            s_options[key] = val;
        }
    };

    // --- System / BIOS ---
    setIfMissing("reicast_region",            "USA");       // Japan | USA | Europe | Default
    setIfMissing("reicast_language",          "English");   // Japanese..Italian | Default
    setIfMissing("reicast_hle_bios",          "disabled");  // forced HLE BIOS (restart required)
    setIfMissing("reicast_enable_dsp",        "enabled");   // AICA DSP (more accurate audio)
    setIfMissing("reicast_broadcast",         "NTSC");      // NTSC | PAL | PAL_N | PAL_M | Default
    setIfMissing("reicast_cable_type",        "TV (Composite)"); // VGA | TV (RGB) | TV (Composite)
    setIfMissing("reicast_dc_32mb_mod",       "disabled");  // 32MB RAM mod (homebrew/dev)
    setIfMissing("reicast_force_wince",       "disabled");  // Windows CE mode hack

    // --- CPU / SH4 ---
    setIfMissing("reicast_sh4clock",          "200");       // SH4 clock MHz (100..330)

    // --- Video / GPU (PVR2) ---
    setIfMissing("reicast_internal_resolution", "640x480"); // 320x240 .. 10240x7680
    setIfMissing("reicast_alpha_sorting",     "per-triangle (normal)");
    setIfMissing("reicast_anisotropic_filtering", "4");     // off | 2 | 4 | 8 | 16
    setIfMissing("reicast_texture_filtering", "0");         // 0 default | 1 nearest | 2 linear
    setIfMissing("reicast_mipmapping",        "enabled");
    setIfMissing("reicast_fog",               "enabled");
    setIfMissing("reicast_volume_modifier_enable", "enabled");
    setIfMissing("reicast_widescreen_hack",   "disabled");
    setIfMissing("reicast_widescreen_cheats", "disabled");
    setIfMissing("reicast_pvr2_filtering",    "disabled");
    setIfMissing("reicast_emulate_framebuffer", "disabled"); // full VRAM fb emulation (slow)
    setIfMissing("reicast_enable_rttb",       "disabled");
    setIfMissing("reicast_native_depth_interpolation", "disabled");
    setIfMissing("reicast_fix_upscale_bleeding_edge", "disabled");
    setIfMissing("reicast_texupscale",        "1");         // xBRZ texture upscale
    setIfMissing("reicast_texupscale_max_filtered_texture_size", "256");
    setIfMissing("reicast_screen_rotation",   "horizontal");
    setIfMissing("reicast_delay_frame_swapping", "disabled");
    setIfMissing("reicast_detect_vsync_swap_interval", "disabled");

    // --- Performance / threading ---
    setIfMissing("reicast_threaded_rendering", "enabled");  // GPU thread (big win)
    setIfMissing("reicast_auto_skip_frame",   "disabled");  // disabled | some | more
    setIfMissing("reicast_frame_skipping",    "disabled");  // disabled | 1..6
    setIfMissing("reicast_gdrom_fast_loading", "enabled");  // GD-ROM speed hack

    // --- Audio ---
    setIfMissing("reicast_vmu_sound",         "disabled");  // beep from VMU minigames

    // --- Input ---
    setIfMissing("reicast_analog_stick_deadzone", "15%");
    setIfMissing("reicast_trigger_deadzone",  "0%");
    setIfMissing("reicast_digital_triggers",  "disabled");

    // --- Controller expansion slots (port 1 default VMU + Purupuru rumble) ---
    setIfMissing("reicast_device_port1_slot1", "VMU");
    setIfMissing("reicast_device_port1_slot2", "Purupuru");
    setIfMissing("reicast_device_port2_slot1", "VMU");
    setIfMissing("reicast_device_port2_slot2", "None");
    setIfMissing("reicast_device_port3_slot1", "VMU");
    setIfMissing("reicast_device_port3_slot2", "None");
    setIfMissing("reicast_device_port4_slot1", "VMU");
    setIfMissing("reicast_device_port4_slot2", "None");
    setIfMissing("reicast_per_content_vmus",  "disabled");  // disabled | VMU A1 | All VMUs
    setIfMissing("reicast_linked_vmu_storage", "disabled");

    // --- Arcade (Naomi / Atomiswave) ---
    setIfMissing("reicast_allow_service_buttons", "disabled");
    setIfMissing("reicast_force_freeplay",    "disabled");
    setIfMissing("reicast_coin_limit",        "0");

    // --- Network (off by default — no netplay UI yet) ---
    setIfMissing("reicast_dcnet",             "disabled");
    setIfMissing("reicast_emulate_bba",       "disabled");
    setIfMissing("reicast_network_output",    "disabled");
    setIfMissing("reicast_upnp",              "disabled");

    // --- Texture replacement (off) ---
    setIfMissing("reicast_custom_textures",   "disabled");
    setIfMissing("reicast_preload_custom_textures", "disabled");
    setIfMissing("reicast_dump_textures",     "disabled");
    setIfMissing("reicast_dump_replaced_textures", "disabled");
}

// ---------------------------------------------------------------------------
// dlopen the core .so and resolve all retro_* symbols.
// ---------------------------------------------------------------------------
static bool loadCoreLib() {
    if (s_coreLib) return true;

    std::vector<std::string> candidates;
    if (!s_coreLibPath.empty()) candidates.push_back(s_coreLibPath);
    candidates.push_back("libflycast_libretro_android.so");

    // --- vmem (fastmem) rescue -------------------------------------------------
    // The core imports ASharedMemory_create as a WEAK symbol but does NOT link
    // libandroid.so (DT_NEEDED is only libm/libdl/libc), and its fallback
    // open("/dev/ashmem") is denied by SELinux for apps targeting API 30+.
    // With neither available, virtmem::init() fails and the core falls back to
    // malloc'ed buffers: every SH4 load/store then goes through the slow
    // addrspace::read32/write32 helpers (the frame seen in the DC crash
    // tombstone). bionic resolves symbols against the RTLD_GLOBAL group of the
    // namespace (linker.cpp: SymbolLookupList(global_group, local_group)), so
    // preloading the REAL libandroid.so into the global group before dlopen()
    // lets the core's weak import bind and vmem/fastmem come back online.
    // Harmless if it doesn't bind (core stays vmem-disabled, as before) — the
    // core's own "nvmem is enabled/disabled" log line (tag flycastcore-rom)
    // tells us which mode was actually chosen.
    if (!dlopen("libandroid.so", RTLD_NOW | RTLD_GLOBAL))
        LOGW("libandroid.so preload failed: %s (core vmem stays disabled)",
             dlerror());
    else
        LOGI("libandroid.so preloaded RTLD_GLOBAL for core weak "
             "ASharedMemory_create");

    const char* lastDlError = nullptr;
    for (const auto& name : candidates) {
        s_coreLib = dlopen(name.c_str(), RTLD_NOW);
        if (s_coreLib) {
            LOGI("dlopen(%s) OK", name.c_str());
            break;
        } else {
            lastDlError = dlerror();
            LOGW("dlopen(%s) failed: %s", name.c_str(),
                 lastDlError ? lastDlError : "(unknown)");
        }
    }

    if (!s_coreLib) {
        s_coreError = "dlopen(libflycast_libretro_android.so) failed: ";
        s_coreError += (lastDlError ? lastDlError : "(unknown)");
        LOGE("%s", s_coreError.c_str());
        return false;
    }

    #define RESOLVE(name) \
        s_##name = reinterpret_cast<name##_t>(dlsym(s_coreLib, #name)); \
        if (!s_##name) { \
            const char* _dlerr = dlerror(); \
            s_coreError = "dlsym(" #name ") failed: "; \
            s_coreError += (_dlerr ? _dlerr : "(unknown)"); \
            LOGE("%s", s_coreError.c_str()); \
            dlclose(s_coreLib); s_coreLib = nullptr; \
            return false; \
        }

    RESOLVE(retro_init);
    RESOLVE(retro_deinit);
    RESOLVE(retro_api_version);
    RESOLVE(retro_get_system_info);
    RESOLVE(retro_get_system_av_info);
    RESOLVE(retro_set_controller_port_device);
    RESOLVE(retro_reset);
    RESOLVE(retro_run);
    RESOLVE(retro_serialize_size);
    RESOLVE(retro_serialize);
    RESOLVE(retro_unserialize);
    RESOLVE(retro_load_game);
    RESOLVE(retro_unload_game);
    RESOLVE(retro_set_environment);
    RESOLVE(retro_set_video_refresh);
    RESOLVE(retro_set_audio_sample);
    RESOLVE(retro_set_audio_sample_batch);
    RESOLVE(retro_set_input_poll);
    RESOLVE(retro_set_input_state);

    #undef RESOLVE

    // Optional: the core's SIGSEGV-handler installer (dynsym, C++-mangled).
    // Used to re-assert fault_handler if something clobbers the process-wide
    // SIGSEGV sigaction after retro_init (see header comment above).
    s_osInstallFaultHandler = reinterpret_cast<os_install_fault_handler_t>(
        dlsym(s_coreLib, "_Z22os_InstallFaultHandlerv"));
    if (s_osInstallFaultHandler) {
        LOGI("dlsym os_InstallFaultHandler OK");
    } else {
        LOGW("dlsym os_InstallFaultHandler failed: %s", dlerror());
    }

    // The core's fault_handler body (dynsym) — lets us detect whether the
    // process-wide SIGSEGV disposition still points at it (vs. any other
    // library that clobbered the sigaction) without re-running the installer
    // (re-running it while fault_handler is current would chain it onto
    // itself and recurse forever on an unhandled fault).
    s_faultHandlerFn = reinterpret_cast<void (*)(int, siginfo_t*, void*)>(
        dlsym(s_coreLib, "_Z13fault_handleriP7siginfoPv"));
    if (s_faultHandlerFn) {
        LOGI("dlsym fault_handler OK (%p)", (void*)s_faultHandlerFn);
    } else {
        LOGW("dlsym fault_handler failed: %s", dlerror());
    }

    LOGI("All retro_* symbols resolved");
    return true;
}

// ---------------------------------------------------------------------------
// EGL helpers — emulation thread only
// ---------------------------------------------------------------------------

// Destroy the GL objects that belong to the current context (FBO, texture,
// compositor program). Must be called with the context current.
static void destroyGlObjectsLocked() {
    if (!s_glObjectsValid) return;
    if (s_fbo)          glDeleteFramebuffers(1, &s_fbo);
    if (s_fboTexture)   glDeleteTextures(1, &s_fboTexture);
    if (s_fboDepthStencil) glDeleteRenderbuffers(1, &s_fboDepthStencil);
    if (s_program)      glDeleteProgram(s_program);
    s_fbo = 0; s_fboTexture = 0; s_fboDepthStencil = 0; s_program = 0;
    s_fboW = s_fboH = 0;
    s_glObjectsValid = false;
}

// (Re)create the compositor program. GLSL ES 100 — valid on GLES2 and 3.
static bool createCompositorProgram() {
    const char* vs =
        "attribute vec2 aPos;\n"
        "uniform float uFlip;\n"
        "varying vec2 vUV;\n"
        "void main() {\n"
        "  gl_Position = vec4(aPos, 0.0, 1.0);\n"
        "  vUV = vec2(aPos.x * 0.5 + 0.5, mix(aPos.y * -0.5 + 0.5, aPos.y * 0.5 + 0.5, uFlip));\n"
        "}\n";
    const char* fs =
        "precision mediump float;\n"
        "uniform sampler2D uTex;\n"
        "varying vec2 vUV;\n"
        "void main() {\n"
        "  gl_FragColor = texture2D(uTex, vUV);\n"
        "}\n";

    GLuint vs_ = glCreateShader(GL_VERTEX_SHADER);
    glShaderSource(vs_, 1, &vs, nullptr);
    glCompileShader(vs_);
    GLint ok = GL_FALSE;
    glGetShaderiv(vs_, GL_COMPILE_STATUS, &ok);
    if (!ok) { LOGE("vs compile failed"); glDeleteShader(vs_); return false; }

    GLuint fs_ = glCreateShader(GL_FRAGMENT_SHADER);
    glShaderSource(fs_, 1, &fs, nullptr);
    glCompileShader(fs_);
    glGetShaderiv(fs_, GL_COMPILE_STATUS, &ok);
    if (!ok) { LOGE("fs compile failed"); glDeleteShader(vs_); glDeleteShader(fs_); return false; }

    s_program = glCreateProgram();
    glAttachShader(s_program, vs_);
    glAttachShader(s_program, fs_);
    glLinkProgram(s_program);
    glGetProgramiv(s_program, GL_LINK_STATUS, &ok);
    glDeleteShader(vs_);
    glDeleteShader(fs_);
    if (!ok) { LOGE("program link failed"); glDeleteProgram(s_program); s_program = 0; return false; }

    s_aPosLoc = glGetAttribLocation(s_program, "aPos");
    s_uTexLoc = glGetUniformLocation(s_program, "uTex");
    s_uFlipLoc = glGetUniformLocation(s_program, "uFlip");
    return true;
}

// Create the RGBA8 + D24S8 FBO the core renders into.
static bool createRenderFbo(int w, int h) {
    if (w <= 0 || h <= 0) return false;
    w = std::min(w, kMaxFboW);
    h = std::min(h, kMaxFboH);

    destroyGlObjectsLocked();

    glGenFramebuffers(1, &s_fbo);
    glGenTextures(1, &s_fboTexture);
    glGenRenderbuffers(1, &s_fboDepthStencil);

    glBindTexture(GL_TEXTURE_2D, s_fboTexture);
    glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, w, h, 0, GL_RGBA, GL_UNSIGNED_BYTE, nullptr);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);

    glBindRenderbuffer(GL_RENDERBUFFER, s_fboDepthStencil);
    glRenderbufferStorage(GL_RENDERBUFFER, GL_DEPTH24_STENCIL8, w, h);

    glBindFramebuffer(GL_FRAMEBUFFER, s_fbo);
    glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, s_fboTexture, 0);
    glFramebufferRenderbuffer(GL_FRAMEBUFFER, GL_DEPTH_STENCIL_ATTACHMENT,
                              GL_RENDERBUFFER, s_fboDepthStencil);
    GLenum status = glCheckFramebufferStatus(GL_FRAMEBUFFER);
    glBindFramebuffer(GL_FRAMEBUFFER, 0);
    if (status != GL_FRAMEBUFFER_COMPLETE) {
        LOGE("FBO incomplete: 0x%x (%dx%d)", status, w, h);
        destroyGlObjectsLocked();
        return false;
    }

    if (!s_program && !createCompositorProgram()) {
        destroyGlObjectsLocked();
        return false;
    }

    s_fboW = w;
    s_fboH = h;
    s_glObjectsValid = true;
    LOGI("Render FBO created: %dx%d", w, h);
    return true;
}

// Frontend-provided retro_hw_get_current_framebuffer_t — glsm binds this FBO
// for every core render pass. Created lazily (the first call happens during
// context_reset, with the context current).
static uintptr_t hwGetCurrentFramebuffer() {
    if (!s_glObjectsValid) {
        if (!createRenderFbo(640, 480)) return 0;
    }
    return (uintptr_t)s_fbo;
}

// Frontend-provided retro_hw_get_proc_address_t.
static retro_proc_address_t hwGetProcAddress(const char* sym) {
    return reinterpret_cast<retro_proc_address_t>(eglGetProcAddress(sym));
}

// Create the EGL display/config (idempotent).
static bool ensureEglDisplay() {
    if (s_eglDisplay != EGL_NO_DISPLAY) return true;
    s_eglDisplay = eglGetDisplay(EGL_DEFAULT_DISPLAY);
    if (s_eglDisplay == EGL_NO_DISPLAY) {
        s_coreError = "eglGetDisplay failed";
        return false;
    }
    if (!eglInitialize(s_eglDisplay, nullptr, nullptr)) {
        s_coreError = "eglInitialize failed";
        s_eglDisplay = EGL_NO_DISPLAY;
        return false;
    }
    return true;
}

// Pick a config with depth 24 + stencil 8 (flycast requests depth AND
// stencil through retro_hw_render_callback).
static EGLConfig chooseEglConfig(bool wantWindow) {
    const EGLint attribs[] = {
        EGL_SURFACE_TYPE, EGLint(wantWindow ? (EGL_WINDOW_BIT | EGL_PBUFFER_BIT) : EGL_PBUFFER_BIT),
        EGL_RENDERABLE_TYPE, EGL_OPENGL_ES3_BIT_KHR,
        EGL_RED_SIZE, 8, EGL_GREEN_SIZE, 8, EGL_BLUE_SIZE, 8, EGL_ALPHA_SIZE, 8,
        EGL_DEPTH_SIZE, 24, EGL_STENCIL_SIZE, 8,
        EGL_NONE
    };
    EGLConfig cfg = nullptr;
    EGLint num = 0;
    if (eglChooseConfig(s_eglDisplay, attribs, &cfg, 1, &num) && num > 0) return cfg;

    // Fallback 1: relax alpha.
    const EGLint attribs2[] = {
        EGL_SURFACE_TYPE, EGLint(wantWindow ? (EGL_WINDOW_BIT | EGL_PBUFFER_BIT) : EGL_PBUFFER_BIT),
        EGL_RENDERABLE_TYPE, EGL_OPENGL_ES3_BIT_KHR,
        EGL_RED_SIZE, 8, EGL_GREEN_SIZE, 8, EGL_BLUE_SIZE, 8,
        EGL_DEPTH_SIZE, 24, EGL_STENCIL_SIZE, 8,
        EGL_NONE
    };
    if (eglChooseConfig(s_eglDisplay, attribs2, &cfg, 1, &num) && num > 0) return cfg;

    // Fallback 2: GLES2 renderable (context still created as ES3 if the
    // driver allows; many Android drivers report limited renderable bits).
    const EGLint attribs3[] = {
        EGL_SURFACE_TYPE, EGLint(wantWindow ? (EGL_WINDOW_BIT | EGL_PBUFFER_BIT) : EGL_PBUFFER_BIT),
        EGL_RENDERABLE_TYPE, (EGL_OPENGL_ES2_BIT | EGL_OPENGL_ES3_BIT_KHR),
        EGL_RED_SIZE, 8, EGL_GREEN_SIZE, 8, EGL_BLUE_SIZE, 8,
        EGL_DEPTH_SIZE, 24, EGL_STENCIL_SIZE, 8,
        EGL_NONE
    };
    if (eglChooseConfig(s_eglDisplay, attribs3, &cfg, 1, &num) && num > 0) return cfg;

    // Fallback 3: no stencil (last resort — flycast prefers stencil but
    // renders on many devices without it).
    const EGLint attribs4[] = {
        EGL_SURFACE_TYPE, EGLint(wantWindow ? (EGL_WINDOW_BIT | EGL_PBUFFER_BIT) : EGL_PBUFFER_BIT),
        EGL_RENDERABLE_TYPE, EGL_OPENGL_ES3_BIT_KHR,
        EGL_RED_SIZE, 8, EGL_GREEN_SIZE, 8, EGL_BLUE_SIZE, 8,
        EGL_DEPTH_SIZE, 24,
        EGL_NONE
    };
    if (eglChooseConfig(s_eglDisplay, attribs4, &cfg, 1, &num) && num > 0) return cfg;

    return nullptr;
}

// Tear down the current EGL surface/context (emulation thread only).
static void destroyEglSurfaceAndContext() {
    // Notify the core before the context dies.
    if (s_hwRenderAccepted && s_hwRender.context_destroy && s_ctxResetCalled) {
        s_hwRender.context_destroy();
        s_ctxResetCalled = false;
    }
    destroyGlObjectsLocked();
    if (s_eglDisplay != EGL_NO_DISPLAY) {
        if (s_eglSurface != EGL_NO_SURFACE) {
            eglMakeCurrent(s_eglDisplay, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
            eglDestroySurface(s_eglDisplay, s_eglSurface);
            s_eglSurface = EGL_NO_SURFACE;
        }
        if (s_eglContext != EGL_NO_CONTEXT) {
            eglDestroyContext(s_eglDisplay, s_eglContext);
            s_eglContext = EGL_NO_CONTEXT;
        }
        eglTerminate(s_eglDisplay);
        s_eglDisplay = EGL_NO_DISPLAY;
    }
    s_eglSurfaceIsWindow = false;
    s_hwRenderAccepted.store(false);
}

// Create/refresh the EGL surface + context and run the core's context_reset.
// window: the ANativeWindow to bind, or null for a pbuffer context.
static bool ensureEglContext(ANativeWindow* window) {
    if (s_eglDisplay == EGL_NO_DISPLAY && !ensureEglDisplay()) return false;

    const bool wantWindow = (window != nullptr);
    // Same kind of surface and no swap since the last build → nothing to do.
    // (The ANativeWindow identity is tracked by the s_windowChanged flag
    // maintained in setSurface — surface recreation always flips it.)
    if (s_eglSurface != EGL_NO_SURFACE && s_eglSurfaceIsWindow == wantWindow &&
        !s_windowChanged) {
        return true;
    }

    // (Re)create everything.
    if (s_eglSurface != EGL_NO_SURFACE) {
        eglMakeCurrent(s_eglDisplay, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
        eglDestroySurface(s_eglDisplay, s_eglSurface);
        s_eglSurface = EGL_NO_SURFACE;
        s_eglSurfaceIsWindow = false;
    }
    // Context is recreated too: its drawables must all be recreated when the
    // window changes, and flycast prefers a full context_reset.
    if (s_eglContext != EGL_NO_CONTEXT) {
        if (s_hwRenderAccepted && s_hwRender.context_destroy && s_ctxResetCalled) {
            s_hwRender.context_destroy();
            s_ctxResetCalled = false;
        }
        destroyGlObjectsLocked();
        eglDestroyContext(s_eglDisplay, s_eglContext);
        s_eglContext = EGL_NO_CONTEXT;
    }

    EGLConfig cfg = chooseEglConfig(wantWindow);
    if (!cfg) {
        s_coreError = "eglChooseConfig failed (no GLES3 config with depth/stencil)";
        LOGE("%s", s_coreError.c_str());
        return false;
    }

    if (wantWindow) {
        EGLint format = 0;
        eglGetConfigAttrib(s_eglDisplay, cfg, EGL_NATIVE_VISUAL_ID, &format);
        ANativeWindow_setBuffersGeometry(window, 0, 0, format);
        s_eglSurface = eglCreateWindowSurface(s_eglDisplay, cfg, window, nullptr);
        s_eglSurfaceIsWindow = true;
    } else {
        const EGLint pbattribs[] = { EGL_WIDTH, 64, EGL_HEIGHT, 64, EGL_NONE };
        s_eglSurface = eglCreatePbufferSurface(s_eglDisplay, cfg, pbattribs);
        s_eglSurfaceIsWindow = false;
    }
    if (s_eglSurface == EGL_NO_SURFACE) {
        s_coreError = "eglCreateWindowSurface/Pbuffer failed";
        LOGE("%s (0x%x)", s_coreError.c_str(), eglGetError());
        return false;
    }

    for (int version = 3; version >= 2; --version) {
        const EGLint ctxAttribs[] = { EGL_CONTEXT_CLIENT_VERSION, version, EGL_NONE };
        s_eglContext = eglCreateContext(s_eglDisplay, cfg, EGL_NO_CONTEXT, ctxAttribs);
        if (s_eglContext != EGL_NO_CONTEXT) { s_eglClientVersion = version; break; }
    }
    if (s_eglContext == EGL_NO_CONTEXT) {
        s_coreError = "eglCreateContext failed (ES2/ES3)";
        LOGE("%s (0x%x)", s_coreError.c_str(), eglGetError());
        return false;
    }

    if (!eglMakeCurrent(s_eglDisplay, s_eglSurface, s_eglSurface, s_eglContext)) {
        s_coreError = "eglMakeCurrent failed";
        LOGE("%s (0x%x)", s_coreError.c_str(), eglGetError());
        return false;
    }

    LOGI("EGL context ready: ES%d %s", s_eglClientVersion,
         s_eglSurfaceIsWindow ? "window" : "pbuffer");

    // GL/EGL driver init is the most likely thing to clobber the
    // process-wide SIGSEGV sigaction after retro_init (the tombstone we
    // chased had only write32 + an anon JIT frame — no fault_handler at
    // all). Re-assert the core's handler right after the real context is up.
    armSegvHandler(true);

    // GL state flycast expects: standard libretro HW render setup.
    if (!s_glObjectsValid) {
        if (!createRenderFbo(640, 480)) {
            s_coreError = "Failed to create the core render FBO";
            return false;
        }
    }

    // Fire the core's context_reset exactly once per fresh context.
    if (s_hwRenderAccepted && !s_ctxResetCalled && s_hwRender.context_reset) {
        s_hwRender.context_reset();
        s_ctxResetCalled = true;
        LOGI("Core context_reset() dispatched");
    }

    s_windowChanged = false;
    return true;
}

// Composite the core's FBO texture into the window and swap.
static void compositeAndSwap() {
    if (s_eglSurface == EGL_NO_SURFACE || s_eglContext == EGL_NO_CONTEXT) return;
    if (!s_glObjectsValid || !s_program) return;

    const int winW = s_eglSurfaceIsWindow ? ANativeWindow_getWidth(s_window) : s_lastFrameW;
    const int winH = s_eglSurfaceIsWindow ? ANativeWindow_getHeight(s_window) : s_lastFrameH;
    if (winW <= 0 || winH <= 0) return;

    glBindFramebuffer(GL_FRAMEBUFFER, 0);
    glViewport(0, 0, winW, winH);
    glDisable(GL_DEPTH_TEST);
    glDisable(GL_STENCIL_TEST);
    glDisable(GL_BLEND);
    glDisable(GL_SCISSOR_TEST);
    glClearColor(0.f, 0.f, 0.f, 1.f);
    glClear(GL_COLOR_BUFFER_BIT);

    glUseProgram(s_program);
    glActiveTexture(GL_TEXTURE0);
    glBindTexture(GL_TEXTURE_2D, s_fboTexture);
    if (s_uTexLoc >= 0) glUniform1i(s_uTexLoc, 0);
    // bottom_left_origin=true (glsm) → the FBO texture is in GL orientation,
    // and the window render is GL too → no flip (uFlip=1 samples upright).
    if (s_uFlipLoc >= 0) glUniform1f(s_uFlipLoc, s_hwRender.bottom_left_origin ? 1.0f : 0.0f);

    static const GLfloat quad[] = {
        -1.f, -1.f,   1.f, -1.f,   -1.f, 1.f,
        -1.f, 1.f,    1.f, -1.f,    1.f, 1.f,
    };
    if (s_aPosLoc >= 0) {
        glEnableVertexAttribArray((GLuint)s_aPosLoc);
        glVertexAttribPointer((GLuint)s_aPosLoc, 2, GL_FLOAT, GL_FALSE, 0, quad);
        glDrawArrays(GL_TRIANGLES, 0, 6);
        glDisableVertexAttribArray((GLuint)s_aPosLoc);
    }

    eglSwapBuffers(s_eglDisplay, s_eglSurface);
}

// Read the core FBO back into the ARGB fallback frame buffer (used for
// captureFrame and for the rare no-surface preview path). Full frame —
// the FBO is always at least as large as the last presented frame.
static void readbackFrameToCpu() {
    if (!s_glObjectsValid || s_fboW <= 0 || s_fboH <= 0) return;

    const int w = s_lastFrameW > 0 ? (int)s_lastFrameW : s_fboW;
    const int h = s_lastFrameH > 0 ? (int)s_lastFrameH : s_fboH;
    if (w <= 0 || h <= 0 || w > kMaxFboW || h > kMaxFboH) return;
    std::vector<uint8_t> rgba((size_t)w * h * 4);
    uint8_t* pixels = rgba.data();
    glBindFramebuffer(GL_FRAMEBUFFER, s_fbo);
    glPixelStorei(GL_PACK_ALIGNMENT, 1);
    glReadPixels(0, 0, w, h, GL_RGBA, GL_UNSIGNED_BYTE, pixels);
    glBindFramebuffer(GL_FRAMEBUFFER, 0);

    // Convert RGBA -> 0xAARRGGBB. FBO origin is bottom-left
    // (bottom_left_origin=true); flip rows so the CPU buffer is top-left
    // like every other core's frame buffer.
    std::vector<uint32_t> argb((size_t)w * h);
    const bool flip = s_hwRender.bottom_left_origin;
    for (int y = 0; y < h; ++y) {
        const int srcY = flip ? (h - 1 - y) : y;
        const uint8_t* row = pixels + (size_t)srcY * w * 4;
        uint32_t* dst = argb.data() + (size_t)y * w;
        for (int x = 0; x < w; ++x) {
            dst[x] = 0xFF000000u |
                     ((uint32_t)row[x * 4 + 0] << 16) |
                     ((uint32_t)row[x * 4 + 1] << 8) |
                     (uint32_t)row[x * 4 + 2];
        }
    }

    {
        std::lock_guard<std::mutex> lk(s_frameMtx);
        if ((int)s_frame.size() < (size_t)w * h) s_frame.resize((size_t)w * h);
        std::memcpy(s_frame.data(), argb.data(), (size_t)w * h * sizeof(uint32_t));
        s_frameW = (unsigned)w;
        s_frameH = (unsigned)h;
    }
    s_newFrame.store(true, std::memory_order_release);
}

// ---------------------------------------------------------------------------
// libretro callbacks
// ---------------------------------------------------------------------------
static void libretroLog(retro_log_level level, const char* fmt, ...) {
    int prio = ANDROID_LOG_INFO;
    switch (level) {
        case RETRO_LOG_ERROR: prio = ANDROID_LOG_ERROR; break;
        case RETRO_LOG_WARN:  prio = ANDROID_LOG_WARN;  break;
        case RETRO_LOG_DEBUG: prio = ANDROID_LOG_DEBUG; break;
        default: break;
    }
    va_list ap;
    va_start(ap, fmt);
    __android_log_vprint(prio, "flycast", fmt, ap);
    va_end(ap);
}

static bool cb_environment(unsigned cmd, void* data) {
    switch (cmd) {
        case RETRO_ENVIRONMENT_GET_CAN_DUPE:
            if (data) *static_cast<bool*>(data) = true;
            return true;

        case RETRO_ENVIRONMENT_SET_PIXEL_FORMAT:
            // Software frames are not used on the HW path; accept whatever
            // the core negotiates so it never refuses to boot.
            return true;

        case RETRO_ENVIRONMENT_GET_LOG_INTERFACE:
            if (data) {
                auto* log = static_cast<retro_log_callback*>(data);
                log->log = libretroLog;
            }
            return true;

        case RETRO_ENVIRONMENT_GET_SYSTEM_DIRECTORY:
            if (data) *static_cast<const char**>(data) = s_systemDir.c_str();
            LOGI("GET_SYSTEM_DIRECTORY -> %s", s_systemDir.c_str());
            return !s_systemDir.empty();

        case RETRO_ENVIRONMENT_GET_SAVE_DIRECTORY:
            if (data) *static_cast<const char**>(data) = s_saveDir.c_str();
            return !s_saveDir.empty();

        case RETRO_ENVIRONMENT_GET_CONTENT_DIRECTORY:
            if (data) *static_cast<const char**>(data) = s_systemDir.c_str();
            return !s_systemDir.empty();

        case RETRO_ENVIRONMENT_SET_INPUT_DESCRIPTORS:
        case RETRO_ENVIRONMENT_SET_CONTROLLER_INFO:
        case RETRO_ENVIRONMENT_SET_VARIABLES:
        case RETRO_ENVIRONMENT_SET_CORE_OPTIONS:
        case RETRO_ENVIRONMENT_SET_CORE_OPTIONS_INTL:
#ifdef RETRO_ENVIRONMENT_SET_CORE_OPTIONS_V2
        case RETRO_ENVIRONMENT_SET_CORE_OPTIONS_V2:
        case RETRO_ENVIRONMENT_SET_CORE_OPTIONS_V2_INTL:
#endif
        case RETRO_ENVIRONMENT_SET_GEOMETRY:
        case RETRO_ENVIRONMENT_SET_CORE_OPTIONS_DISPLAY:
#ifdef RETRO_ENVIRONMENT_SET_MINIMUM_AUDIO_LATENCY
        case RETRO_ENVIRONMENT_SET_MINIMUM_AUDIO_LATENCY:
#endif
            return true;

        case RETRO_ENVIRONMENT_GET_CORE_OPTIONS_VERSION:
            if (data) *static_cast<unsigned*>(data) = 2;
            return true;

        case RETRO_ENVIRONMENT_SET_SYSTEM_AV_INFO:
            if (data) {
                auto* av2 = static_cast<retro_system_av_info*>(data);
                if (av2->timing.sample_rate > 8000) {
                    s_sampleRate = (int)av2->timing.sample_rate;
                    s_resampler.init(s_sampleRate, s_sampleRate);
                }
                if (av2->timing.fps > 10.0) {
                    s_refreshRate = av2->timing.fps;
                }
                LOGI("SET_SYSTEM_AV_INFO: %d Hz, %.4f fps, geom %ux%u",
                     s_sampleRate, s_refreshRate,
                     av2->geometry.base_width, av2->geometry.base_height);
            }
            return true;

        case RETRO_ENVIRONMENT_GET_AUDIO_VIDEO_ENABLE:
            if (data) *static_cast<int*>(data) = 3;
            return true;

        case RETRO_ENVIRONMENT_SET_MESSAGE: {
            if (data) {
                auto* msg = static_cast<const retro_message*>(data);
                if (msg && msg->msg) {
                    s_coreMessage = msg->msg;
                    LOGI("Core message: %s", msg->msg);
                }
            }
            return true;
        }

        case RETRO_ENVIRONMENT_GET_VARIABLE: {
            if (!data) return false;
            auto* var = static_cast<retro_variable*>(data);
            if (!var->key) return false;
            std::lock_guard<std::mutex> lk(s_optMtx);
            auto it = s_options.find(var->key);
            if (it != s_options.end()) {
                var->value = it->second.c_str();
                return true;
            }
            return false;
        }

        case RETRO_ENVIRONMENT_GET_VARIABLE_UPDATE:
            if (data) {
                *static_cast<bool*>(data) = s_optionsChanged.exchange(false,
                    std::memory_order_acq_rel);
            }
            return true;

        // --- Hardware rendering ---
        case RETRO_ENVIRONMENT_GET_PREFERRED_HW_RENDER:
            if (data) *static_cast<unsigned*>(data) = RETRO_HW_CONTEXT_OPENGLES3;
            LOGI("GET_PREFERRED_HW_RENDER -> OPENGLES3");
            return true;

        case RETRO_ENVIRONMENT_SET_HW_RENDER: {
            if (!data) return false;
            auto* hw = static_cast<retro_hw_render_callback*>(data);
            // Accept the GLES family only. glsm (HAVE_OPENGLES builds)
            // requests OPENGLES2 but resolves ES3 symbols through
            // get_proc_address — we create a real ES3 context.
            const unsigned t = hw->context_type;
            if (t != RETRO_HW_CONTEXT_OPENGLES2 &&
                t != RETRO_HW_CONTEXT_OPENGLES3 &&
                t != RETRO_HW_CONTEXT_OPENGLES_VERSION) {
                LOGW("Rejecting HW render type %u (only GLES accepted)", t);
                return false;
            }
            hw->get_current_framebuffer = hwGetCurrentFramebuffer;
            hw->get_proc_address = hwGetProcAddress;
            if (!hw->get_current_framebuffer || !hw->get_proc_address) return false;
            s_hwRender = *hw;
            s_hwRenderAccepted.store(true);
            LOGI("SET_HW_RENDER accepted: type=%u v%u.%u depth=%d stencil=%d blOrigin=%d",
                 hw->context_type, hw->version_major, hw->version_minor,
                 hw->depth ? 1 : 0, hw->stencil ? 1 : 0,
                 hw->bottom_left_origin ? 1 : 0);
            return true;
        }

#ifdef RETRO_ENVIRONMENT_GET_HW_RENDER_INTERFACE
        case RETRO_ENVIRONMENT_GET_HW_RENDER_INTERFACE:
            // No Vulkan / D3D interface — GL path only.
            return false;
#endif

#ifdef RETRO_ENVIRONMENT_SET_HW_RENDER_CONTEXT_NEGOTIATION_INTERFACE
        case RETRO_ENVIRONMENT_SET_HW_RENDER_CONTEXT_NEGOTIATION_INTERFACE:
            return false;
#endif

#ifdef RETRO_ENVIRONMENT_GET_CURRENT_SOFTWARE_FRAMEBUFFER
        case RETRO_ENVIRONMENT_GET_CURRENT_SOFTWARE_FRAMEBUFFER:
            return false;
#endif

        case RETRO_ENVIRONMENT_GET_VFS_INTERFACE:
            return false;

        case RETRO_ENVIRONMENT_GET_INPUT_BITMASKS:
            return false;

        case RETRO_ENVIRONMENT_GET_LANGUAGE:
            if (data) *static_cast<unsigned*>(data) = RETRO_LANGUAGE_ENGLISH;
            return true;

        case RETRO_ENVIRONMENT_GET_RUMBLE_INTERFACE:
            return false;

#ifdef RETRO_ENVIRONMENT_GET_TARGET_REFRESH_RATE
        case RETRO_ENVIRONMENT_GET_TARGET_REFRESH_RATE:
            return false;
#endif

        default:
            return false;
    }
}

static void cb_video(const void* data, unsigned width, unsigned height, size_t pitch) {
    // HW render path: the core signals the frame is in our FBO.
    if (data == RETRO_HW_FRAME_BUFFER_VALID) {
        if (width > 0 && height > 0) {
            s_lastFrameW = width;
            s_lastFrameH = height;
            s_frameReady = true;
            s_presentedFrames.fetch_add(1, std::memory_order_relaxed);
            s_videoW = width;
            s_videoH = height;
            // Grow the FBO if the core renders beyond its capacity.
            if ((int)width > s_fboW || (int)height > s_fboH) {
                const int nw = std::max((int)width, s_fboW);
                const int nh = std::max((int)height, s_fboH);
                LOGI("Core frame %ux%u exceeds FBO %dx%d — recreating",
                     width, height, s_fboW, s_fboH);
                createRenderFbo(nw, nh);
            }
        }
        return;
    }
    if (!data) return;   // duplicate frame — nothing to do on the FBO path
    // Software frames never arrive once SET_HW_RENDER was accepted; the
    // FBO/present path is the only output.
}

static void cb_audio_sample(int16_t left, int16_t right) {
    int16_t pair[2] = {left, right};
    s_audio.push(pair, 2);
}

static size_t cb_audio_batch(const int16_t* data, size_t frames) {
    s_audio.push(data, frames * 2);
    return frames;
}

static void cb_input_poll() { /* state is read on demand */ }

// Project UI bit layout -> libretro JOYPAD layout (see header comments).
// flycast dc_joymap: JOYPAD_B=DC_A, JOYPAD_A=DC_B, JOYPAD_Y=DC_X,
// JOYPAD_X=DC_Y, L2/R2=analog triggers, SELECT=DC_BTN_D, L/R=DC_BTN_C/Z.
static inline uint16_t dcProjectToLibretro(uint16_t bits) {
    uint16_t r = 0;
    if (bits & 0x0001) r |= (1 << 0);    // screen A -> JOYPAD_B (DC A)
    if (bits & 0x0002) r |= (1 << 8);    // screen B -> JOYPAD_A (DC B)
    if (bits & 0x0004) r |= (1 << 2);    // Select -> JOYPAD_SELECT (DC D)
    if (bits & 0x0008) r |= (1 << 3);    // Start -> JOYPAD_START
    if (bits & 0x0010) r |= (1 << 4);    // Up
    if (bits & 0x0020) r |= (1 << 5);    // Down
    if (bits & 0x0040) r |= (1 << 6);    // Left
    if (bits & 0x0080) r |= (1 << 7);    // Right
    if (bits & 0x0100) r |= (1 << 1);    // screen X -> JOYPAD_Y (DC X)
    if (bits & 0x0200) r |= (1 << 9);    // screen Y -> JOYPAD_X (DC Y)
    if (bits & 0x0400) r |= (1 << 10);   // L1 -> JOYPAD_L (DC C)
    if (bits & 0x0800) r |= (1 << 11);   // R1 -> JOYPAD_R (DC Z)
    if (bits & 0x1000) r |= (1 << 12);   // L2 -> JOYPAD_L2 (left trigger)
    if (bits & 0x2000) r |= (1 << 13);   // R2 -> JOYPAD_R2 (right trigger)
    if (bits & 0x4000) r |= (1 << 14);   // L3
    if (bits & 0x8000) r |= (1 << 15);   // R3
    return r;
}

static int16_t cb_input_state(unsigned port, unsigned device,
                              unsigned index, unsigned id) {
    const uint16_t projBits =
        (port == 0) ? s_pad1.load(std::memory_order_relaxed) :
        (port == 1) ? s_pad2.load(std::memory_order_relaxed) :
        (port == 2) ? s_pad3.load(std::memory_order_relaxed) :
        (port == 3) ? s_pad4.load(std::memory_order_relaxed) : 0;
    const uint16_t lrBits = dcProjectToLibretro(projBits);

    if (device == RETRO_DEVICE_ANALOG) {
        if (index == RETRO_DEVICE_INDEX_ANALOG_LEFT ||
            index == RETRO_DEVICE_INDEX_ANALOG_RIGHT) {
            const unsigned axis = (index == RETRO_DEVICE_INDEX_ANALOG_LEFT ? 0 : 2)
                                + (id == RETRO_DEVICE_ID_ANALOG_Y ? 1 : 0);
            if (port < 4) {
                return s_analog[port][axis].load(std::memory_order_relaxed);
            }
        }
        if (index == RETRO_DEVICE_INDEX_ANALOG_BUTTON) {
            // Analog trigger query (flycast get_analog_trigger): the on-screen
            // pad has digital triggers, so report full pull when held.
            if (id == RETRO_DEVICE_ID_JOYPAD_L2) return (lrBits & (1 << 12)) ? 0x7FFF : 0;
            if (id == RETRO_DEVICE_ID_JOYPAD_R2) return (lrBits & (1 << 13)) ? 0x7FFF : 0;
        }
        return 0;
    }
    if (device != RETRO_DEVICE_JOYPAD) return 0;
    if (id == RETRO_DEVICE_ID_JOYPAD_MASK) return lrBits;   // not used (bitmasks off)
    if (id >= 16) return 0;
    return (lrBits >> id) & 1;
}

// ---------------------------------------------------------------------------
// Apply any controller-port device switch queued by setPortDevice().
// ---------------------------------------------------------------------------
static void applyPendingPortDevicesLockedStep() {
    uint32_t pending = s_pendingPortDevices.load(std::memory_order_acq_rel);
    if ((pending >> 24) == 0xFF || !s_retro_set_controller_port_device) return;
    for (int port = 0; port < 4; ++port) {
        unsigned dev = (pending >> (port * 8)) & 0xFFu;
        if (dev == 0xFF) dev = RETRO_DEVICE_JOYPAD;
        s_retro_set_controller_port_device((unsigned)port, dev);
        LOGI("Controller port %d -> device %u", port, dev);
    }
    s_pendingPortDevices.store(0xFFu << 24, std::memory_order_release);
}

// ---------------------------------------------------------------------------
// File-extension helpers for loadFromFile.
// ---------------------------------------------------------------------------
static std::string getExtensionLower(const std::string& path) {
    size_t dot = path.find_last_of('.');
    if (dot == std::string::npos) return "";
    std::string ext = path.substr(dot + 1);
    std::transform(ext.begin(), ext.end(), ext.begin(),
                   [](unsigned char c){ return (char)std::tolower(c); });
    return ext;
}

// Extensions flycast opens by path: disc/GD-ROM images plus MAME-style
// archive romsets (multi-file, referenced by name inside the zip).
static bool isPathBasedContent(const std::string& ext) {
    return ext == "chd" || ext == "cdi" || ext == "gdi" || ext == "cue" ||
           ext == "iso" || ext == "m3u" || ext == "toc" || ext == "lst" ||
           ext == "bin" || ext == "dat" || ext == "zip" || ext == "7z";
}

// ---------------------------------------------------------------------------
// Public API
// ---------------------------------------------------------------------------
std::string loadFromFile(const std::string& path, int& regionOut) {
    regionOut = 0;

    if (!loadCoreLib()) {
        return s_coreError.empty()
            ? "Failed to load libflycast_libretro_android.so"
            : s_coreError;
    }

    if (!s_loaded) {
        initDefaultOptions();

        s_retro_set_environment(cb_environment);
        s_retro_set_video_refresh(cb_video);
        s_retro_set_audio_sample(cb_audio_sample);
        s_retro_set_audio_sample_batch(cb_audio_batch);
        s_retro_set_input_poll(cb_input_poll);
        s_retro_set_input_state(cb_input_state);

        s_retro_init();
        s_loaded = true;
        LOGI("Flycast core initialized (API version %u)", s_retro_api_version());

        // retro_init() installs the core's SIGSEGV handler (fault_handler,
        // via os_InstallFaultHandler). Wrap it with the logging probe and
        // confirm it's actually the process-wide disposition right now.
        armSegvHandler(false);
    }

    if (s_gameLoaded) {
        s_retro_unload_game();
        s_gameLoaded = false;
    }

    s_audio.reset();
    s_resampler.reset();

    // Pass the path by reference — flycast opens disc images / romsets
    // itself (parses TOC, resolves .cue/.gdi track files, reads zip members).
    // No in-memory preload for any supported format.
    s_romPath = path;

    retro_game_info gameInfo{};
    gameInfo.path = s_romPath.c_str();
    gameInfo.data = nullptr;
    gameInfo.size = 0;
    gameInfo.meta = nullptr;

    bool ok = s_retro_load_game(&gameInfo);
    if (!ok) {
        s_coreError = "retro_load_game() failed for: " + path;
        if (!s_coreMessage.empty()) {
            s_coreError += " (";
            s_coreError += s_coreMessage;
            s_coreError += ")";
            s_coreMessage.clear();
        }
        s_coreError += "\n\n常见原因:\n";
        s_coreError += "  1. BIOS 缺失: Dreamcast 需要 dc_boot.bin + dc_flash.bin, "
                        "放在系统目录的 dc/ 子目录下 (设置 → 系统 → BIOS 管理).\n";
        s_coreError += "  2. Naomi / Atomiswave 游戏: 需要 MAME BIOS romset "
                        "(naomi.zip / atomiswave.zip), 同样放在 dc/ 目录.\n";
        s_coreError += "  3. .cue 引用的轨道文件缺失或被改名 — 请保持镜像完整.\n";
        s_coreError += "  4. .chd/.gdi 压缩镜像损坏, 请重新转换.\n";
        LOGE("%s", s_coreError.c_str());
        return s_coreError;
    }

    s_gameLoaded = true;
    s_lastRomPath = path;

    // retro_load_game() runs the actual BIOS/disc boot path (and may pull in
    // code-loading / memory-setup that touches signal handling). Re-assert
    // the core's fault_handler in case anything during game load clobbered
    // the process-wide SIGSEGV sigaction.
    armSegvHandler(false);

    // 4 maple ports with the standard Dreamcast controller.
    if (s_retro_set_controller_port_device) {
        for (unsigned p = 0; p < 4; ++p)
            s_retro_set_controller_port_device(p, RETRO_DEVICE_JOYPAD);
    }

    retro_system_av_info av{};
    s_retro_get_system_av_info(&av);
    s_sampleRate = (int)av.timing.sample_rate;
    s_refreshRate = (av.timing.fps > 10.0) ? av.timing.fps : 60.0;
    s_region = (av.timing.fps < 55.0) ? 1 : 0;
    regionOut = s_region;
    s_videoW = av.geometry.base_width ? av.geometry.base_width : 640;
    s_videoH = av.geometry.base_height ? av.geometry.base_height : 480;

    {
        std::lock_guard<std::mutex> lk(s_frameMtx);
        s_frameW = s_videoW;
        s_frameH = s_videoH;
        s_frame.assign((size_t)s_frameW * s_frameH, 0xFF000000u);
    }

    s_audio.reset();
    s_newFrame.store(false);
    s_frameReady = false;

    if (s_sampleRate > 0) {
        s_resampler.init(s_sampleRate, s_sampleRate);
        LOGI("Audio passthrough: %d Hz", s_sampleRate);
    }

    if (!s_hwRenderAccepted.load()) {
        LOGW("Core did not register a HW render context — software output?!");
    }

    LOGI("DC/Naomi ROM loaded: %s  rate=%d  fps=%.2f  geom=%ux%u  max=%ux%u",
         path.c_str(), s_sampleRate, av.timing.fps,
         av.geometry.base_width, av.geometry.base_height,
         av.geometry.max_width, av.geometry.max_height);
    return "";
}

void unload() {
    if (s_loaded) {
        if (s_gameLoaded) {
            s_retro_unload_game();
            s_gameLoaded = false;
        }
        // Unwrap our SIGSEGV probe BEFORE the core tears down: retro_deinit
        // restores whatever was installed when retro_init ran, and our probe
        // chain must not survive the core (or another core/loader loading
        // later would hand a stale handler address to the kernel).
        if (s_segvProbeInstalled.load()) {
            sigaction(SIGSEGV, &s_prevSegvAction, nullptr);
            s_segvProbeInstalled.store(false);
        }
        s_retro_deinit();
        s_loaded = false;
    }
    s_sampleRate = 0;
    s_refreshRate = 60.0;
    s_region = 0;
    s_audio.reset();
    s_resampler.reset();
    s_newFrame.store(false);
    s_presentedFrames.store(0, std::memory_order_relaxed);
    {
        std::lock_guard<std::mutex> lk(s_frameMtx);
        s_frame.clear();
        s_frameW = 0;
        s_frameH = 0;
    }
    s_videoW = 640;
    s_videoH = 480;
    s_lastFrameW = 0;
    s_lastFrameH = 0;
    s_frameReady = false;
    s_lastRomPath.clear();
    s_saveName.clear();
    s_pad1.store(0, std::memory_order_relaxed);
    s_pad2.store(0, std::memory_order_relaxed);
    s_pad3.store(0, std::memory_order_relaxed);
    s_pad4.store(0, std::memory_order_relaxed);
    for (auto& portAxes : s_analog)
        for (auto& axis : portAxes)
            axis.store(0, std::memory_order_relaxed);
}

void resetEmulation(bool /*hard*/) {
    if (s_loaded && s_gameLoaded) s_retro_reset();
}

void stepFrame() {
    if (!s_loaded || !s_gameLoaded) return;
    applyPendingPortDevicesLockedStep();

    // Ensure an EGL context exists / matches the current surface. Without a
    // window we run against a pbuffer so rendering + capture still work.
    ANativeWindow* win = s_window;
    if (!ensureEglContext(win)) {
        LOGE("ensureEglContext failed: %s", s_coreError.c_str());
        return;
    }

    // Cheap guard: if anything replaced our SIGSEGV probe since the last
    // frame (other than armSegvHandler's own unwrap/rewrap), re-arm so the
    // core's fault_handler keeps repairing protected-page writes.
    armSegvHandler(false);

    s_retro_run();

    // Report protected-page faults the core handler repaired during this
    // frame (outside any signal context). See armor comment block above.
    drainSegvReports();

    // Present: composite the core's FBO into the window and swap.
    if (s_frameReady && s_eglSurfaceIsWindow) {
        compositeAndSwap();
    }
    s_frameReady = false;

    // CPU readback for captureFrame / no-surface preview.
    if (s_readbackRequest.exchange(false, std::memory_order_acq_rel) ||
        !s_eglSurfaceIsWindow) {
        readbackFrameToCpu();
    }
    // Note: fast-forward is implemented purely by the Kotlin pacing loop
    // (divide the frame budget by the multiplier). The GPU path has no
    // per-frame blit cost to skip, unlike the software cores.
}

// 读取并清零自上次轮询以来核心真实提交的帧数（FPS HUD 用）。
int pollPresentedFrames() {
    return s_presentedFrames.exchange(0, std::memory_order_acq_rel);
}

void setPortDevice(int port, int device) {
    if (port < 0 || port > 3) return;
    uint32_t next = s_pendingPortDevices.load(std::memory_order_relaxed);
    next = (next & ~(0xFFu << (port * 8))) |
           ((uint32_t)(device & 0xFF) << (port * 8));
    next &= 0x00FFFFFFu;                    // clear "no pending" flag byte
    s_pendingPortDevices.store(next, std::memory_order_acq_rel);
}

double videoRefreshRate() { return s_refreshRate; }

bool copyFramebufferARGB(uint32_t* out, int w, int h) {
    if (!out) return false;
    if (!s_loaded || !s_gameLoaded || s_frame.empty()) {
        std::memset(out, 0, (size_t)w * h * sizeof(uint32_t));
        return false;
    }
    std::lock_guard<std::mutex> lk(s_frameMtx);
    const int cw = (w < (int)s_frameW) ? w : (int)s_frameW;
    const int ch = (h < (int)s_frameH) ? h : (int)s_frameH;
    for (int y = 0; y < ch; ++y) {
        std::memcpy(out + (size_t)y * w,
                    s_frame.data() + (size_t)y * s_frameW,
                    (size_t)cw * sizeof(uint32_t));
    }
    return s_newFrame.exchange(false, std::memory_order_acq_rel);
}

int readAudio(int16_t* out, int maxFrames) {
    if (!s_loaded || !s_gameLoaded) return 0;
    return s_resampler.readResampled(s_audio, out, maxFrames);
}

int audioSampleRate() { return s_sampleRate; }
int audioTargetSampleRate() { return s_sampleRate; }  // default audio == core rate

void setControllerInput(int port, uint16_t bits) {
    if (port == 0)      s_pad1.store(bits, std::memory_order_relaxed);
    else if (port == 1) s_pad2.store(bits, std::memory_order_relaxed);
    else if (port == 2) s_pad3.store(bits, std::memory_order_relaxed);
    else if (port == 3) s_pad4.store(bits, std::memory_order_relaxed);
}

void setAnalogAxis(int port, int axis, int16_t value) {
    if (port < 0 || port > 3 || axis < 0 || axis > 3) return;
    s_analog[port][axis].store(value, std::memory_order_relaxed);
}

void setPaths(const std::string& systemDir, const std::string& saveDir) {
    s_systemDir = systemDir;
    s_saveDir = saveDir;
    // Flycast stores flash/VMU inside <system>/dc/... — make sure the dc/
    // data directory exists so first boot can write its internal NVMEM.
    if (!systemDir.empty()) {
        const std::string dcData = systemDir + "/dc/data";
        mkdir(dcData.c_str(), 0755);
    }
}

void setSaveName(const std::string& name) {
    s_saveName = name;
    LOGI("Save name set: '%s'", name.c_str());
}

void setCoreLibPath(const std::string& path) {
    s_coreLibPath = path;
    LOGI("Core lib path set: %s", s_coreLibPath.c_str());
}

void applyRegion(int /*region*/) { /* region is auto-detected at load */ }
void applySampleRate(int /*hz*/) { /* fixed by the core */ }
void applySpeed(float multiplier) {
    s_fastForward.store(multiplier > 1.0f, std::memory_order_relaxed);
    s_ffMaxSkip.store((int)multiplier, std::memory_order_relaxed);
    s_ffFrameSkip.store(0, std::memory_order_relaxed);
}

void saveStateToPath(int /*slot*/, const std::string& path) {
    if (!s_loaded || !s_gameLoaded || !s_retro_serialize) return;
    size_t sz = s_retro_serialize_size();
    if (sz == 0) return;
    std::vector<uint8_t> buf(sz);
    if (!s_retro_serialize(buf.data(), sz)) { LOGE("retro_serialize failed"); return; }
    FILE* f = std::fopen(path.c_str(), "wb");
    if (!f) { LOGE("Cannot open save state for write: %s", path.c_str()); return; }
    std::fwrite(buf.data(), 1, sz, f);
    std::fclose(f);
}

bool loadStateFromPath(int /*slot*/, const std::string& path) {
    if (!s_loaded || !s_gameLoaded || !s_retro_unserialize) return false;
    FILE* f = std::fopen(path.c_str(), "rb");
    if (!f) return false;
    std::fseek(f, 0, SEEK_END);
    long sz = std::ftell(f);
    std::fseek(f, 0, SEEK_SET);
    if (sz <= 0) { std::fclose(f); return false; }
    std::vector<uint8_t> buf((size_t)sz);
    size_t rd = std::fread(buf.data(), 1, (size_t)sz, f);
    std::fclose(f);
    if (rd != (size_t)sz) return false;
    if (!s_retro_unserialize(buf.data(), sz)) { LOGE("retro_unserialize failed"); return false; }
    return true;
}

void setSurface(void* nativeWindow) {
    ANativeWindow* win = static_cast<ANativeWindow*>(nativeWindow);
    if (win != s_window) {
        if (s_window) ANativeWindow_release(s_window);
        s_window = win;
        if (s_window) ANativeWindow_acquire(s_window);
        s_windowChanged = true;
    }
    // Surface changes must be applied on the emulation thread; the next
    // stepFrame() re-runs ensureEglContext() and (re)fires context_reset.
    if (s_window) {
        ANativeWindow_setBuffersGeometry(s_window, 0, 0, WINDOW_FORMAT_RGBA_8888);
    }
    LOGI("Surface %s (applied on next frame)",
         s_window ? "attached" : "detached");
}

void setCoreOption(const std::string& key, const std::string& value) {
    {
        std::lock_guard<std::mutex> lk(s_optMtx);
        s_options[key] = value;
    }
    s_optionsChanged.store(true, std::memory_order_release);
    LOGI("Core option set: %s = %s", key.c_str(), value.c_str());
}

int videoWidth()  { return (int)s_videoW; }
int videoHeight() { return (int)s_videoH; }

void videoAspectRatio(int& num, int& den) {
    // Dreamcast / Naomi: 4:3 (VGA monitor and NTSC TV). The widescreen hack
    // stretches to 16:9 but the frontend keeps 4:3 letterboxing semantics.
    num = 4;
    den = 3;
}

void setVideoFilter(int /*filter*/) {
    // No-op on the GPU path: flycast renders through the FBO and the
    // compositor samples with bilinear filtering. Post-processing filters
    // (scanline/CRT/HQx) apply to CPU blits only.
    LOGI("setVideoFilter ignored (GPU render path)");
}

void setHighQualityScaling(bool /*enabled*/) {
    // Buffer geometry is owned by the EGL window surface at native display
    // size; scaling happens on the GPU. Nothing to configure here.
}

bool isCoreLoaded() {
    return s_coreLib != nullptr;
}

bool isHwRenderAvailable() {
    return s_hwRenderAccepted.load();
}

void requestFrameReadback() {
    s_readbackRequest.store(true, std::memory_order_release);
}

} // namespace flycastcore::rom
