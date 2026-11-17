// SPDX-License-Identifier: MIT
// OpenGL renderer initialization + CPU readback presentation
// for the melonDS libretro wrapper.
//
// Strategy:
//   1. Send SET_HW_RENDER → frontend (nds_loader.cpp) creates EGL/GLES 3.2
//      context and initializes glad (via createEglContext).
//   2. GLRenderer::New() + SetRenderer3D for hardware-accelerated 3D.
//   3. Each frame, after nds->RunFrame(), the GL compositor
//      (GLCompositor::RenderFrame) blends 2D + 3D into an offscreen FBO
//      at the user-selected resolution scale.
//   4. render_opengl_frame() glReadPixels that FBO back to a CPU buffer
//      and calls video_cb() for software presentation.
//
// The GL compositor always produces a fixed Top/Bottom stacked output:
//   top screen: rows 0 … 192*scale-1
//   gap:        2*scale pixels
//   bottom screen: rows 193*scale … (384+2)*scale-1
//
// Only Top/Bottom and Bottom/Top layouts are supported with the GL renderer.
// Other layouts (Left/Right, single-screen, hybrid) force software fallback.

#include "opengl.h"
#include "screenlayout.h"
#include "input.h"
#include "utils.h"

#include <libretro.h>

#include "NDS.h"
#include "GPU.h"
#include "GPU3D_OpenGL.h"
#include "PlatformOGL.h"

#include <android/log.h>
#include <cstring>
#include <memory>
#include <vector>
#include <cstdlib>

#define TAG "melonds-gl"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

// One-shot dump of the live GL context identity, used for performance
// triage: if this never appears in logcat, the hardware 3D renderer was
// never initialized and games are running entirely on the CPU.
static void log_gl_context_info()
{
    const char* ver   = (const char*)glGetString(GL_VERSION);
    const char* rend  = (const char*)glGetString(GL_RENDERER);
    LOGI("GL context: version=%s renderer=%s",
         ver ? ver : "(null)",
         rend ? rend : "(null)");
}

// ---------------------------------------------------------------------------
// Externs from libretro.cpp
// ---------------------------------------------------------------------------
extern melonDS::NDS* nds;
extern retro_video_refresh_t video_cb;
extern retro_environment_t environ_cb;
extern retro_log_printf_t log_cb;
extern InputState input_state;
extern ScreenLayout current_screen_layout;
extern ScreenLayoutData screen_layout_data;

// ---------------------------------------------------------------------------
// Local state
// ---------------------------------------------------------------------------
static melonDS::GLRenderer* s_glRenderer = nullptr;
static bool s_hw_render_sent = false;

// CPU readback buffer for GL → video_cb presentation
static std::vector<uint32_t> s_readback;
static int s_glW = 256;   // 256 × scale
static int s_glH = 386;   // (384+2) × scale

// ---------------------------------------------------------------------------
// HW render callbacks – called by the frontend (nds_loader.cpp) after
// EGL context creation / before destruction.
// ---------------------------------------------------------------------------
static void context_reset(void)
{
    LOGI("GL context reset (GLRenderer created in initialize_opengl)");
}

static void context_destroy(void)
{
    LOGI("GL context destroy");
    // GLRenderer resources are freed when deinitialize_opengl_renderer()
    // replaces the renderer with a SoftRenderer (called from unload/retro_run).
}

extern bool swapped_screens; // from libretro.cpp

// ---------------------------------------------------------------------------
// Public API
// ---------------------------------------------------------------------------
void update_gl_screenlayout(bool swap)
{
    if (!s_glRenderer) return;
    int scale = s_glRenderer->GetScaleFactor();
    if (scale < 1) scale = 1;

    screen_layout_data.screen_width     = 256 * scale;
    screen_layout_data.screen_height    = 192 * scale;
    screen_layout_data.screen_gap       = 2 * scale;
    screen_layout_data.buffer_width     = 256 * scale;
    screen_layout_data.buffer_height    = 386 * scale;
    screen_layout_data.buffer_stride    = 256 * scale * 4;
    screen_layout_data.pixel_size       = 4;
    screen_layout_data.enable_top_screen    = true;
    screen_layout_data.enable_bottom_screen = true;
    screen_layout_data.direct_copy      = false;
    screen_layout_data.hybrid           = false;
    screen_layout_data.top_screen_offset    = 0;
    screen_layout_data.bottom_screen_offset = 0;

    if (swap)
    {
        // Bottom/Top: bottom screen rendered at the top of the image.
        screen_layout_data.displayed_layout = ScreenLayout::BottomTop;
        screen_layout_data.touch_offset_x   = 0;
        screen_layout_data.touch_offset_y   = 0; // bottom screen is at y=0
    }
    else
    {
        // Top/Bottom: default.
        screen_layout_data.displayed_layout = ScreenLayout::TopBottom;
        screen_layout_data.touch_offset_x   = 0;
        screen_layout_data.touch_offset_y   = (192 * scale) + (2 * scale); // bottom screen starts here
    }
}

bool initialize_opengl()
{
    if (using_opengl)
        return true;

    if (!nds)
    {
        LOGE("initialize_opengl: nds is null");
        enable_opengl = false;
        return false;
    }

    // Screen layouts other than vertical stacking cannot be produced by the
    // GL compositor (which always stacks top-over-bottom).  Fall back to
    // software renderer for non-standard layouts.
    if (current_screen_layout != ScreenLayout::TopBottom &&
        current_screen_layout != ScreenLayout::BottomTop)
    {
        LOGI("initialize_opengl: non-stacked layout, software fallback");
        enable_opengl = false;
        return false;
    }

    // --- Send HW render request -------------------------------------------
    if (!s_hw_render_sent)
    {
        retro_hw_render_callback cb;
        std::memset(&cb, 0, sizeof(cb));
        cb.context_type       = RETRO_HW_CONTEXT_OPENGLES_VERSION;
        cb.version_major      = 3;
        cb.version_minor      = 2;
        cb.depth              = true;
        cb.stencil            = true;
        cb.bottom_left_origin = false;
        cb.context_reset      = context_reset;
        cb.context_destroy    = context_destroy;
        if (!environ_cb(RETRO_ENVIRONMENT_SET_HW_RENDER, &cb))
        {
            LOGE("SET_HW_RENDER rejected, forcing software");
            enable_opengl = false;
            return false;
        }
        s_hw_render_sent = true;
        LOGI("SET_HW_RENDER accepted (ES 3.2)");
    }

    // Verify glad is wired up. glEnable is a GL 1.0 function that is always
    // loaded on a valid ES 3.2 context.  If it's still null, the EGL context
    // creation must have failed (or glad was never called).
    if (glad_glEnable == nullptr)
    {
        LOGE("glad_glEnable is NULL – EGL context creation likely failed");
        enable_opengl = false;
        return false;
    }

    log_gl_context_info();

    // --- Create the OpenGL 3D renderer ------------------------------------
    LOGI("initialize_opengl: creating GLRenderer...");
    auto renderer = melonDS::GLRenderer::New();
    if (!renderer)
    {
        const char* ver = (const char*)glGetString(GL_VERSION);
        LOGE("GLRenderer::New() failed (shader compile / GL caps). "
             "context=%s — shader set requires OpenGL ES 3.2; falling back "
             "to the CPU renderer.",
             ver ? ver : "(unknown)");
        enable_opengl = false;
        return false;
    }

    int scale = video_settings.GL_ScaleFactor;
    if (scale < 1) scale = 1;
    renderer->SetScaleFactor(scale);
    LOGI("GLRenderer scale factor = %d", scale);

    s_glRenderer = renderer.get();
    nds->GPU.SetRenderer3D(std::move(renderer));

    // --- Set up presentation geometry ------------------------------------
    // Allocate readback buffer for GL → video_cb presentation.
    s_glW = 256 * scale;
    s_glH = 386 * scale;  // 384 + 2-screen gap
    s_readback.resize((size_t)s_glW * s_glH);

    // Sync screen layout geometry for touch mapping.
    update_gl_screenlayout(swapped_screens);

    // We do NOT allocate screen_layout_data.buffer_ptr here — the GL
    // readback path uses s_readback for the actual frame.  buffer_ptr
    // will be set to point at s_readback.data() in render_opengl_frame()
    // just before draw_cursor is called (if applicable).
    screen_layout_data.buffer_ptr = nullptr;

    using_opengl  = true;
    refresh_opengl = false;
    LOGI("GL renderer ready: %dx%d", s_glW, s_glH);
    return true;
}

void deinitialize_opengl_renderer(void)
{
    if (!using_opengl)
        return;

    LOGI("deinitialize_opengl_renderer: resetting to software renderer");
    if (nds)
        nds->GPU.SetRenderer3D(nullptr);

    s_glRenderer = nullptr;
    using_opengl = false;
    s_hw_render_sent = false;
}

void render_opengl_frame(bool sw)
{
    (void)sw;

    if (!nds || !s_glRenderer)
        return;

    // --- Dynamic scale update ---------------------------------------------
    if (refresh_opengl)
    {
        refresh_opengl = false;
        int newScale = video_settings.GL_ScaleFactor;
        if (newScale < 1) newScale = 1;
        if (s_glRenderer->GetScaleFactor() != newScale)
        {
            s_glRenderer->SetScaleFactor(newScale);
            LOGI("scale factor updated to %d", newScale);
        }
    }

    // --- Read back the GL compositor output --------------------------------
    // After NDS::RunFrame → GPU VBlank → GPU3D.Blit() → GLCompositor::RenderFrame,
    // the composited frame is in CompScreenOutputFB[nds->GPU.FrontBuffer].
    // (GPU::FinishFrame flips FrontBuffer after the compositor renders.)
    int scale = s_glRenderer->GetScaleFactor();
    if (scale < 1) scale = 1;
    int w = 256 * scale;
    int h = 386 * scale;

    if (w != s_glW || h != s_glH)
    {
        s_glW = w;
        s_glH = h;
        s_readback.resize((size_t)w * h);
    }

    // Get the compositor's output FBO where the freshly-composited frame lives.
    GLuint fbo = s_glRenderer->GetCompositorOutputFBO(nds->GPU.FrontBuffer);
    if (fbo == 0)
        return;

    // Save current read framebuffer binding, then bind ours.
    GLint prevReadFB = 0;
    glGetIntegerv(GL_READ_FRAMEBUFFER_BINDING, &prevReadFB);

    // The melonDS GL renderer keeps a pixel-pack PBO bound at all times
    // (GLRenderer::PixelbufferID, sized for a 256x192 3D capture — see
    // GPU3D_OpenGL.cpp SetRenderSettings/PrepareCaptureFrame/GetLine; it
    // is never bound back to 0).  Our scaled readback (512x772 at 2x)
    // would overflow that small buffer, and glReadPixels would then fail
    // with GL_INVALID_OPERATION (0x502), leaving the presented frame
    // completely black.  Temporarily unbind the PBO so the pixels are
    // written straight into our CPU buffer, then restore the binding.
    GLint prevPackBuffer = 0;
    glGetIntegerv(GL_PIXEL_PACK_BUFFER_BINDING, &prevPackBuffer);
    if (prevPackBuffer != 0)
        glBindBuffer(GL_PIXEL_PACK_BUFFER, 0);

    glBindFramebuffer(GL_READ_FRAMEBUFFER, fbo);
    glReadBuffer(GL_COLOR_ATTACHMENT0);
    glPixelStorei(GL_PACK_ALIGNMENT, 4);
    glReadPixels(0, 0, w, h, GL_RGBA, GL_UNSIGNED_BYTE, s_readback.data());

    GLenum glerr = glGetError();
    if (glerr != GL_NO_ERROR)
        LOGE("glReadPixels failed: 0x%x (fbo=%u, %dx%d)", glerr, fbo, w, h);

    glBindFramebuffer(GL_READ_FRAMEBUFFER, (GLuint)prevReadFB);
    if (prevPackBuffer != 0)
        glBindBuffer(GL_PIXEL_PACK_BUFFER, (GLuint)prevPackBuffer);

    // Convert the compositor readback → ARGB (0xFFRRGGBB).
    //
    // The GL compositor shader (GPU_OpenGL_shaders.h kCompositorFS_Nearest)
    // writes `oColor = vec4(vec3(pixel.bgr), 1.0)` — the desktop frontend's
    // screen shader swaps .bgr back to rgb when displaying.  Our readback
    // path must do the same swap.  Consequently the FBO texture holds BGR
    // data and glReadPixels(GL_RGBA) returns bytes in [B][G][R][A] order,
    // which as a little-endian uint32_t is already 0xFFRRGGBB — no channel
    // shuffling is needed, only the alpha mask.
    // (The previous code assumed RGBA byte order and swapped R/B again,
    // producing blue↔red swapped colors.)
    uint32_t* out = s_readback.data();
    size_t npixels = (size_t)w * h;
    for (size_t i = npixels; i > 0; )
    {
        i--;
        out[i] = 0xFF000000u | (out[i] & 0x00FFFFFFu);
    }

    // Handle screen swap: if the screens are swapped, swap the two screen
    // halves in the CPU buffer so the presented order matches the user's
    // preference (the GL compositor always produces top-over-bottom).
    if (swapped_screens)
    {
        int screenPixels = 256 * 192 * scale * scale; // pixels per screen
        int gapPixels    = 2 * scale * w;              // one gap row's pixels
        const size_t offset = (size_t)screenPixels + gapPixels;
        for (int i = 0; i < screenPixels; i++)
        {
            uint32_t tmp = out[i];
            out[i] = out[offset + i];
            out[offset + i] = tmp;
        }
        // After swap, the bottom screen is rendered at the top of the
        // output buffer.  Update the touch offset to match.
        screen_layout_data.touch_offset_y = 0;
    }
    else
    {
        screen_layout_data.touch_offset_y = (192 * scale) + (2 * scale);
    }

    // Draw cursor overlay (if the stylus is active).
    // draw_cursor writes to screen_layout_data.buffer_ptr as uint32_t*,
    // so we temporarily point it at our readback buffer.
    if (cursor_enabled(&input_state))
    {
        uint16_t* saved_ptr = screen_layout_data.buffer_ptr;
        screen_layout_data.buffer_ptr = (uint16_t*)s_readback.data();
        draw_cursor(&screen_layout_data, input_state.touch_x, input_state.touch_y);
        screen_layout_data.buffer_ptr = saved_ptr;
    }

    // Present via the standard video_cb callback.
    video_cb((uint8_t*)s_readback.data(), (unsigned)w, (unsigned)h, (size_t)w * 4);
}