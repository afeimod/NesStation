#ifndef _INPUT_H
#define _INPUT_H

#include <atomic>
#include <cstdint>

#include "types.h"

using namespace melonDS;

enum TouchMode
{
   Disabled,
   Mouse,
   Touch,
   Joystick,
};

struct InputState
{
   bool touching;
   int touch_x, touch_y;
   TouchMode current_touch_mode;

   bool holding_noise_btn = false;
   bool swap_screens_btn = false;
   bool lid_closed = false;
};

extern InputState input_state;

bool cursor_enabled(InputState *state);

extern bool libretro_supports_bitmasks;

void update_input(InputState *state);

// ---------------------------------------------------------------------------
// Direct-pixel touchscreen state — written by the frontend loader
// (nds_loader.cpp setTouchInputDirect), read by update_input().
// x / y are DS bottom-screen PIXEL coordinates (0..255 / 0..191), matching
// the official melonDS Android frontend architecture. See input.cpp.
// ---------------------------------------------------------------------------
extern std::atomic<bool>    touch_direct_mode;
extern std::atomic<int16_t> touch_direct_x;
extern std::atomic<int16_t> touch_direct_y;
extern std::atomic<bool>    touch_direct_pressed;

#endif
