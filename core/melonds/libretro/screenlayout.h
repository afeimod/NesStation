#ifndef _SCREENLAYOUT_H
#define _SCREENLAYOUT_H

#include <cstring>
#include <stdint.h>
#include <stdlib.h>

#define VIDEO_WIDTH 256
#define VIDEO_HEIGHT 192

// Engine's GPU::RenderSettings no longer exists in melonDS 2025.
// Keep the option state here so the libretro wrapper can compile/run
// without the OpenGL renderer (which is stubbed out).
struct VideoSettings
{
   int   GL_ScaleFactor    = 1;
   bool  GL_BetterPolygons = false;
   bool  Soft_Threaded     = false;
};

enum ScreenLayout
{
   TopBottom = 0,
   BottomTop = 1,
   LeftRight = 2,
   RightLeft = 3,
   TopOnly = 4,
   BottomOnly = 5,
   HybridTop = 6,
   HybridBottom = 7,
};

enum SmallScreenLayout
{
    SmallScreenTop = 0,
    SmallScreenBottom = 1,
    SmallScreenDuplicate = 2
};

enum ScreenId
{
    Primary = 0,
    Top = 1,
    Bottom = 2,
};

struct ScreenLayoutData
{
    bool enable_top_screen;
    bool enable_bottom_screen;
    bool direct_copy;

    unsigned pixel_size;
    unsigned scale;

    unsigned screen_width;
    unsigned screen_height;
    unsigned top_screen_offset;
    unsigned bottom_screen_offset;

    unsigned touch_offset_x;
    unsigned touch_offset_y;

    unsigned screen_gap_unscaled;
    unsigned screen_gap;

    bool hybrid;
    SmallScreenLayout hybrid_small_screen;
    unsigned hybrid_ratio;

    unsigned buffer_width;
    unsigned buffer_height;
    unsigned buffer_stride;
    size_t buffer_len;
    uint16_t* buffer_ptr;
    ScreenLayout displayed_layout;
};

extern ScreenLayout current_screen_layout;
extern ScreenLayoutData screen_layout_data;
extern VideoSettings video_settings;

void initialize_screnlayout_data(ScreenLayoutData *data);
void update_screenlayout(ScreenLayout layout, ScreenLayoutData *data, bool opengl, bool swap_screens);
void clean_screenlayout_buffer(ScreenLayoutData *data);
#endif
