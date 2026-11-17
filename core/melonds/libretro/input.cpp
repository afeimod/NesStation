#include "input.h"
#include "libretro_state.h"
#include "utils.h"

#include <atomic>

#include "NDS.h"

InputState input_state;
u32 input_mask = 0xFFF;
static bool has_touched = false;

// ---------------------------------------------------------------------------
// Direct-pixel touchscreen state (official melonDS Android architecture).
// ---------------------------------------------------------------------------
// The frontend maps a touch on the bottom-screen rectangle DIRECTLY to DS
// touchscreen pixel coordinates (0..255, 0..191) and stores them here —
// exactly like the standalone melonDS Android app (MelonEmulator.onScreenTouch
// -> NDS::TouchScreen). This bypasses the composite-frame coordinate
// round-trip below, which is fragile: it silently breaks whenever the
// frontend's view geometry disagrees with the core's screen layout (custom
// free-form layout, GL gap rows, screen-gap option, hybrid layouts, ...).
//
// When touch_direct_mode is true, update_input() takes these values as raw
// bottom-screen pixels; the legacy RETRO_DEVICE_POINTER path (normalized
// over the whole composite framebuffer) is used otherwise (hybrid layouts).
// ---------------------------------------------------------------------------
std::atomic<bool>    touch_direct_mode{false};
std::atomic<int16_t> touch_direct_x{0};
std::atomic<int16_t> touch_direct_y{0};
std::atomic<bool>    touch_direct_pressed{false};

#define ADD_KEY_TO_MASK(key, i, bits) if (bits & (1 << key)) input_mask &= ~(1 << i); else input_mask |= (1 << i);

bool cursor_enabled(InputState *state)
{
   return state->current_touch_mode == TouchMode::Mouse || state->current_touch_mode == TouchMode::Joystick;
}

void update_input(InputState *state)
{
   u32 joypad_bits;
   int i;

   input_poll_cb();

   if (libretro_supports_bitmasks)
   {
      joypad_bits = input_state_cb(0, RETRO_DEVICE_JOYPAD, 0, RETRO_DEVICE_ID_JOYPAD_MASK);
   }
   else
   {
      joypad_bits = 0;
      for (i = 0; i < (RETRO_DEVICE_ID_JOYPAD_R3 + 1); i++)
         joypad_bits |= input_state_cb(0, RETRO_DEVICE_JOYPAD, 0, i) ? (1 << i) : 0;
   }
   
   ADD_KEY_TO_MASK(RETRO_DEVICE_ID_JOYPAD_A,      0,  joypad_bits);
   ADD_KEY_TO_MASK(RETRO_DEVICE_ID_JOYPAD_B,      1,  joypad_bits);
   ADD_KEY_TO_MASK(RETRO_DEVICE_ID_JOYPAD_SELECT, 2,  joypad_bits);
   ADD_KEY_TO_MASK(RETRO_DEVICE_ID_JOYPAD_START,  3,  joypad_bits);
   ADD_KEY_TO_MASK(RETRO_DEVICE_ID_JOYPAD_RIGHT,  4,  joypad_bits);
   ADD_KEY_TO_MASK(RETRO_DEVICE_ID_JOYPAD_LEFT,   5,  joypad_bits);
   ADD_KEY_TO_MASK(RETRO_DEVICE_ID_JOYPAD_UP,     6,  joypad_bits);
   ADD_KEY_TO_MASK(RETRO_DEVICE_ID_JOYPAD_DOWN,   7,  joypad_bits);
   ADD_KEY_TO_MASK(RETRO_DEVICE_ID_JOYPAD_R,      8,  joypad_bits);
   ADD_KEY_TO_MASK(RETRO_DEVICE_ID_JOYPAD_L,      9,  joypad_bits);
   ADD_KEY_TO_MASK(RETRO_DEVICE_ID_JOYPAD_X,      10, joypad_bits);
   ADD_KEY_TO_MASK(RETRO_DEVICE_ID_JOYPAD_Y,      11, joypad_bits);

   melonDS::NDS* nds = melonDS::NDS::Current;
   if (nds)
      nds->SetKeyMask(input_mask);

   bool lid_closed_btn = !!input_state_cb(0, RETRO_DEVICE_JOYPAD, 0, RETRO_DEVICE_ID_JOYPAD_L3);
   if(lid_closed_btn != state->lid_closed)
   {
      if (nds) nds->SetLidClosed(lid_closed_btn);
      state->lid_closed = lid_closed_btn;
   }

   state->holding_noise_btn = !!input_state_cb(0, RETRO_DEVICE_JOYPAD, 0, RETRO_DEVICE_ID_JOYPAD_L2);
   state->swap_screens_btn = !!input_state_cb(0, RETRO_DEVICE_JOYPAD, 0, RETRO_DEVICE_ID_JOYPAD_R2);

   if(current_screen_layout != ScreenLayout::TopOnly)
   {
      // ---------------------------------------------------------------------
      // Direct-pixel touchscreen path — checked BEFORE the touch-mode switch
      // so it works regardless of the melonds_touch_mode core option value.
      // The core option DEFAULTS to "Mouse" (libretro_core_options.h); if the
      // frontend ever fails to override it to "Touch", the old code silently
      // disabled the touchscreen entirely (TouchMode::Mouse reads
      // RETRO_DEVICE_MOUSE, which this frontend never provides). The frontend
      // always injects bottom-screen pixels via setTouchInputDirect(), so this
      // path takes priority whenever direct mode is armed. The legacy POINTER
      // path (used by Hybrid layouts via setTouchInput) still works below
      // whenever direct mode is disarmed (setTouchInput clears it).
      // ---------------------------------------------------------------------
      bool handled = false;
      if (touch_direct_mode.load(std::memory_order_relaxed))
      {
         handled = true;
         if (touch_direct_pressed.load(std::memory_order_relaxed))
         {
            state->touching = true;
            state->touch_x = Clamp(touch_direct_x.load(std::memory_order_relaxed), 0, VIDEO_WIDTH - 1);
            state->touch_y = Clamp(touch_direct_y.load(std::memory_order_relaxed), 0, VIDEO_HEIGHT - 1);
         }
         else if (state->touching)
         {
            state->touching = false;
         }
      }

      if (!handled)
      switch(state->current_touch_mode)
      {
         case TouchMode::Disabled:
            state->touching = false;
            break;
         case TouchMode::Mouse:
            {
               int16_t mouse_x = input_state_cb(0, RETRO_DEVICE_MOUSE, 0, RETRO_DEVICE_ID_MOUSE_X);
               int16_t mouse_y = input_state_cb(0, RETRO_DEVICE_MOUSE, 0, RETRO_DEVICE_ID_MOUSE_Y);

               state->touching = input_state_cb(0, RETRO_DEVICE_MOUSE, 0, RETRO_DEVICE_ID_MOUSE_LEFT);

               state->touch_x = Clamp(state->touch_x + mouse_x, 0, VIDEO_WIDTH - 1);
               state->touch_y = Clamp(state->touch_y + mouse_y, 0, VIDEO_HEIGHT - 1);
            }

            break;
         case TouchMode::Touch:
            // Legacy POINTER path — the frontend maps the touch to normalized
            // coordinates over the whole composite framebuffer (used by the
            // Hybrid layouts, whose small-screen geometry only the core knows).
            if(input_state_cb(0, RETRO_DEVICE_POINTER, 0, RETRO_DEVICE_ID_POINTER_PRESSED))
            {
               int16_t pointer_x = input_state_cb(0, RETRO_DEVICE_POINTER, 0, RETRO_DEVICE_ID_POINTER_X);
               int16_t pointer_y = input_state_cb(0, RETRO_DEVICE_POINTER, 0, RETRO_DEVICE_ID_POINTER_Y);

               unsigned int touch_scale = screen_layout_data.displayed_layout == ScreenLayout::HybridBottom ? screen_layout_data.hybrid_ratio : 1;

               unsigned int x = ((int)pointer_x + 0x8000) * screen_layout_data.buffer_width / 0x10000 / touch_scale;
               unsigned int y = ((int)pointer_y + 0x8000) * screen_layout_data.buffer_height / 0x10000 / touch_scale;

               if ((x >= screen_layout_data.touch_offset_x) && (x < screen_layout_data.touch_offset_x + screen_layout_data.screen_width) &&
                     (y >= screen_layout_data.touch_offset_y) && (y < screen_layout_data.touch_offset_y + screen_layout_data.screen_height))
               {
                  state->touching = true;

                  state->touch_x = Clamp((x - screen_layout_data.touch_offset_x) * VIDEO_WIDTH / screen_layout_data.screen_width, 0, VIDEO_WIDTH - 1);
                  state->touch_y = Clamp((y - screen_layout_data.touch_offset_y) * VIDEO_HEIGHT / screen_layout_data.screen_height, 0, VIDEO_HEIGHT - 1);
               }
            }
            else if(state->touching)
            {
               state->touching = false;
            }

            break;
         case TouchMode::Joystick:
            int16_t joystick_x = input_state_cb(0, RETRO_DEVICE_ANALOG, RETRO_DEVICE_INDEX_ANALOG_RIGHT, RETRO_DEVICE_ID_ANALOG_X) / 2048;
            int16_t joystick_y = input_state_cb(0, RETRO_DEVICE_ANALOG, RETRO_DEVICE_INDEX_ANALOG_RIGHT, RETRO_DEVICE_ID_ANALOG_Y) / 2048;

            state->touch_x = Clamp(state->touch_x + joystick_x, 0, VIDEO_WIDTH - 1);
            state->touch_y = Clamp(state->touch_y + joystick_y, 0, VIDEO_HEIGHT - 1);

            state->touching = !!input_state_cb(0, RETRO_DEVICE_JOYPAD, 0, RETRO_DEVICE_ID_JOYPAD_R3);

            break;
      }
   }
   else
   {
      state->touching = false;
   }

   if(state->touching)
   {
      if (melonDS::NDS::Current) melonDS::NDS::Current->TouchScreen(state->touch_x, state->touch_y);
      has_touched = true;
   }
   else if(has_touched)
   {
      if (melonDS::NDS::Current) melonDS::NDS::Current->ReleaseScreen();
      has_touched = false;
   }
}
