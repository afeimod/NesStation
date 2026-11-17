/*
    melonDS libretro wrapper - migrated to melonDS 1.1 API
*/

#include <ctime>
#include <string>
#include <vector>
#include <memory>

#include <libretro.h>
#include <libretro_core_options.h>
#include <streams/file_stream.h>
#include <file/file_path.h>
#include <compat/strl.h>

#include "Config.h"
#include "Platform.h"
#include "NDS.h"
#include "Args.h"
#include "NDSCart.h"
#include "GBACart.h"
#include "GPU.h"
#include "SPU.h"
#include "version.h"
#include "frontend/FrontendUtil.h"
#include "frontend/mic_blow.h"

#include "input.h"
#include "opengl.h"
#include "screenlayout.h"
#include "utils.h"
#include "GPU3D_Soft.h"

// JIT code-memory probe implemented in ARMJIT_Global.cpp (vendored core).
// Returns true when the device/SELinux policy allows mapping executable
// pages for the JIT recompiler.
namespace melonDS { namespace ARMJIT_Global { bool ProbeCodeMemory(); } }

using namespace melonDS;

char retro_base_directory[4096];
static char retro_saves_directory[4096];

struct retro_log_callback logging;

retro_audio_sample_batch_t audio_cb;
retro_environment_t environ_cb;
retro_input_poll_t input_poll_cb;
retro_input_state_t input_state_cb;
retro_log_printf_t log_cb;
retro_video_refresh_t video_cb;

std::string retro_save_path;
std::string retro_gba_save_path;

// Optional override for the .sav basename. Set by the loader (e.g. when
// the user has chosen "NesStation" save mode) when the ROM path is a
// shared temp file (e.g. content:// URI copied to cacheDir/temp_rom.<ext>)
// — without this override, every game would write to "temp_rom.sav" and
// clobber each other. Empty string = use the ROM's basename (default,
// matches upstream melonDS).
std::string melonds_save_basename_override;
std::string melonds_gba_save_basename_override;

// Called by the loader (nds_loader.cpp) to set the override BEFORE
// retro_load_game() is invoked. Pass an empty string to clear.
// __attribute__((visibility("default"))) ensures the symbol stays visible
// when melonds-core (static) is linked into ndscore.so (which uses
// -fvisibility=hidden), so nds_loader.cpp can resolve it at link time.
extern "C" __attribute__((visibility("default")))
void melonds_set_save_basename_override(const char* name) {
    melonds_save_basename_override = (name ? std::string(name) : std::string());
}
extern "C" __attribute__((visibility("default")))
void melonds_set_gba_save_basename_override(const char* name) {
    melonds_gba_save_basename_override = (name ? std::string(name) : std::string());
}

retro_game_info* cached_info;

VideoSettings video_settings;

bool libretro_supports_bitmasks = false;
bool enable_opengl = false;
bool using_opengl = false;
bool opengl_linear_filtering = false;
bool refresh_opengl = true;
bool swapped_screens = false;
bool toggle_swap_screen = false;
bool swap_screen_toggled = false;

const int SLOT_1_2_BOOT = 1;

static bool libretro_supports_option_categories = false;
#ifdef HAVE_OPENGL
static bool opengl_options = true;
#endif
static bool hybrid_options = true;
#ifdef JIT_ENABLED
static bool jit_options = true;
#endif

// Mic state (shared with platform.cpp)
int mic_noise_type = 0;
bool mic_noise_held = false;

// NDS instance — non-static so opengl.cpp can access it for
// GLRenderer initialization via GPU::SetRenderer3D().
melonDS::NDS* nds = nullptr;

// Game name (without extension) — stored for retro_reset() which needs
// to pass it to SetupDirectBoot(const std::string&).
static std::string s_retro_game_name;

// ---------------------------------------------------------------------------
// Soft-renderer threading — mirrors the reference melonDS Android frontend.
//
// The melonds_threaded_renderer option was consumed into
// video_settings.Soft_Threaded but never APPLIED: nothing called
// SoftRenderer::SetThreaded(), so CPU scanline rendering always ran
// single-threaded even when the option said "enabled". The standalone
// melonDS APK defaults this on, which is part of why it feels much faster
// on mid-range devices running the same core.
//
// Threading applies to the CPU renderer only. With the OpenGL hardware 3D
// renderer active the flag is irrelevant (polygons go to the GPU); when GL
// fails and we fall back to software we explicitly apply the option again
// so the fallback path does not ALSO lose multithreading.
// ---------------------------------------------------------------------------
static void apply_soft_threading(void)
{
   if (!nds || using_opengl)
      return;

   // NOTE: compiled with -fno-rtti, so no dynamic_cast here. The wrapper's
   // renderer lifecycle guarantees GetCurrentRenderer() is either a
   // SoftRenderer (software mode / GL fallback) or a GLRenderer installed
   // by opengl.cpp while using_opengl==true — and this function early-outs
   // whenever using_opengl is set. Therefore reaching this line means the
   // current renderer IS the CPU SoftRenderer.
   static_cast<melonDS::SoftRenderer&>(nds->GPU.GPU3D.GetCurrentRenderer())
      .SetThreaded(video_settings.Soft_Threaded, nds->GPU);
}

enum CurrentRenderer
{
   None,
   Software,
   OpenGLRenderer,
};

static CurrentRenderer current_renderer = CurrentRenderer::None;

static void fallback_log(enum retro_log_level level, const char *fmt, ...)
{
   (void)level;
   va_list va;
   va_start(va, fmt);
   vfprintf(stderr, fmt, va);
   va_end(va);
}

void retro_init(void)
{
   const char *dir = NULL;

   srand(time(NULL));
   if (environ_cb(RETRO_ENVIRONMENT_GET_SYSTEM_DIRECTORY, &dir) && dir)
      sprintf(retro_base_directory, "%s", dir);

   if (environ_cb(RETRO_ENVIRONMENT_GET_SAVE_DIRECTORY, &dir) && dir)
      sprintf(retro_saves_directory, "%s", dir);

   initialize_screnlayout_data(&screen_layout_data);
}

void retro_deinit(void)
{
   libretro_supports_bitmasks = false;
   libretro_supports_option_categories = false;
}

unsigned retro_api_version(void)
{
   return RETRO_API_VERSION;
}

void retro_get_system_info(struct retro_system_info *info)
{
   memset(info, 0, sizeof(*info));
   info->library_name     = "melonDS";
#ifndef MELONDS_VERSION
#define MELONDS_VERSION "1.1"
#endif
#ifndef GIT_VERSION
#define GIT_VERSION ""
#endif
   info->library_version  = MELONDS_VERSION GIT_VERSION;
   info->need_fullpath    = false;
   info->valid_extensions = "nds|ids|dsi";
}

void retro_get_system_av_info(struct retro_system_av_info *info)
{
   info->timing.fps            = 32.0f * 1024.0f * 1024.0f / 560190.0f;
   info->timing.sample_rate    = 32.0f * 1024.0f;
   info->geometry.base_width   = screen_layout_data.buffer_width;
   info->geometry.base_height  = screen_layout_data.buffer_height;
   info->geometry.max_width    = screen_layout_data.buffer_width;
   info->geometry.max_height   = screen_layout_data.buffer_height;
   info->geometry.aspect_ratio = (float)screen_layout_data.buffer_width / (float)screen_layout_data.buffer_height;
}

static bool update_option_visibility(void)
{
   struct retro_core_option_display option_display;
   struct retro_variable var;
   bool updated = false;

#ifdef HAVE_OPENGL
   bool opengl_options_prev = opengl_options;
   opengl_options = true;
   var.key = "melonds_opengl_renderer";
   if (environ_cb(RETRO_ENVIRONMENT_GET_VARIABLE, &var) && var.value && !strcmp(var.value, "disabled"))
      opengl_options = false;
   if (opengl_options != opengl_options_prev)
   {
      option_display.visible = opengl_options;
      option_display.key = "melonds_opengl_resolution";
      environ_cb(RETRO_ENVIRONMENT_SET_CORE_OPTIONS_DISPLAY, &option_display);
      option_display.key = "melonds_opengl_better_polygons";
      environ_cb(RETRO_ENVIRONMENT_SET_CORE_OPTIONS_DISPLAY, &option_display);
      option_display.key = "melonds_opengl_filtering";
      environ_cb(RETRO_ENVIRONMENT_SET_CORE_OPTIONS_DISPLAY, &option_display);
      updated = true;
   }
#endif

   bool hybrid_options_prev = hybrid_options;
   hybrid_options = true;
   var.key = "melonds_screen_layout";
   if (environ_cb(RETRO_ENVIRONMENT_GET_VARIABLE, &var) && var.value && (strcmp(var.value, "Hybrid Top") && strcmp(var.value, "Hybrid Bottom")))
      hybrid_options = false;
   if (hybrid_options != hybrid_options_prev)
   {
      option_display.visible = hybrid_options;
      option_display.key = "melonds_hybrid_small_screen";
      environ_cb(RETRO_ENVIRONMENT_SET_CORE_OPTIONS_DISPLAY, &option_display);
#ifdef HAVE_OPENGL
      option_display.key = "melonds_hybrid_ratio";
      environ_cb(RETRO_ENVIRONMENT_SET_CORE_OPTIONS_DISPLAY, &option_display);
#endif
      updated = true;
   }

#ifdef JIT_ENABLED
   bool jit_options_prev = jit_options;
   jit_options = true;
   var.key = "melonds_jit_enable";
   if (environ_cb(RETRO_ENVIRONMENT_GET_VARIABLE, &var) && var.value && !strcmp(var.value, "disabled"))
      jit_options = false;
   if (jit_options != jit_options_prev)
   {
      option_display.visible = jit_options;
      option_display.key = "melonds_jit_block_size";
      environ_cb(RETRO_ENVIRONMENT_SET_CORE_OPTIONS_DISPLAY, &option_display);
      option_display.key = "melonds_jit_branch_optimisations";
      environ_cb(RETRO_ENVIRONMENT_SET_CORE_OPTIONS_DISPLAY, &option_display);
      option_display.key = "melonds_jit_literal_optimisations";
      environ_cb(RETRO_ENVIRONMENT_SET_CORE_OPTIONS_DISPLAY, &option_display);
      option_display.key = "melonds_jit_fast_memory";
      environ_cb(RETRO_ENVIRONMENT_SET_CORE_OPTIONS_DISPLAY, &option_display);
      updated = true;
   }
#endif

   return updated;
}

void retro_set_environment(retro_environment_t cb)
{
   struct retro_vfs_interface_info vfs_iface_info;
   environ_cb = cb;

   libretro_set_core_options(environ_cb, &libretro_supports_option_categories);

   struct retro_core_options_update_display_callback update_display_cb;
   update_display_cb.callback = update_option_visibility;
   environ_cb(RETRO_ENVIRONMENT_SET_CORE_OPTIONS_UPDATE_DISPLAY_CALLBACK, &update_display_cb);

   static const struct retro_system_content_info_override content_overrides[] = {
      { "nds|dsi|gba", false, true },
      { NULL, false, false }
   };
   environ_cb(RETRO_ENVIRONMENT_SET_CONTENT_INFO_OVERRIDE, (void*)content_overrides);

   if (cb(RETRO_ENVIRONMENT_GET_LOG_INTERFACE, &logging))
      log_cb = logging.log;
   else
      log_cb = fallback_log;

   static const struct retro_controller_description controllers[] = {
      { "Nintendo DS", RETRO_DEVICE_JOYPAD },
      { NULL, 0 },
   };
   static const struct retro_controller_info ports[] = {
      { controllers, 1 },
      { NULL, 0 },
   };
   cb(RETRO_ENVIRONMENT_SET_CONTROLLER_INFO, (void*)ports);

   if (environ_cb(RETRO_ENVIRONMENT_GET_INPUT_BITMASKS, NULL))
      libretro_supports_bitmasks = true;

   static const struct retro_subsystem_memory_info gba_memory[] = {
      { "srm", 0x101 },
   };
   static const struct retro_subsystem_memory_info nds_memory[] = {
      { "sav", 0x102 },
   };
   static const struct retro_subsystem_rom_info slot_1_2_roms[] = {
      { "NDS Rom (Slot 1)", "nds", false, false, true, nds_memory, 0 },
      { "GBA Rom (Slot 2)", "gba", false, false, true, gba_memory, 1 },
      {}
   };
   static const struct retro_subsystem_info subsystems[] = {
      { "Slot 1/2 Boot", "gba", slot_1_2_roms, 2, SLOT_1_2_BOOT },
      {}
   };
   cb(RETRO_ENVIRONMENT_SET_SUBSYSTEM_INFO, (void*)subsystems);

   vfs_iface_info.required_interface_version = FILESTREAM_REQUIRED_VFS_VERSION;
   vfs_iface_info.iface = NULL;
   if (environ_cb(RETRO_ENVIRONMENT_GET_VFS_INTERFACE, &vfs_iface_info))
      filestream_vfs_init(&vfs_iface_info);
}

void retro_set_audio_sample(retro_audio_sample_t cb) { (void)cb; }

void retro_set_audio_sample_batch(retro_audio_sample_batch_t cb)
{
   audio_cb = cb;
}

void retro_set_input_poll(retro_input_poll_t cb)
{
   input_poll_cb = cb;
}

void retro_set_input_state(retro_input_state_t cb)
{
   input_state_cb = cb;
}

void retro_set_video_refresh(retro_video_refresh_t cb)
{
   video_cb = cb;
}

void retro_set_controller_port_device(unsigned port, unsigned device)
{
   log_cb(RETRO_LOG_INFO, "Plugging device %u into port %u.\n", device, port);
}


void retro_reset(void)
{
   if (!nds) return;

   nds->Reset();

   // NOTE: re-parsing the ROM from cached_info is unsafe here:
   // cached_info is the retro_game_info from loadFromFile() which lives
   // on the loader thread's stack and is freed once it returns (the ROM
   // buffer itself is a local vector). NDS::Reset() keeps the inserted
   // cart, so we only need to re-run the DirectBoot setup.
   //
   // CRITICAL: Call SetupDirectBoot(const std::string&) (the overload with
   // a rom name). The parameterless SetupDirectBoot() loads the game code
   // into RAM and configures the MMU, but it does NOT set the ARM9/ARM7
   // entry-point registers and does NOT call JumpTo(). Without JumpTo(),
   // the ARM9 stays in the FreeBIOS boot_handler which is a dead loop
   // (b 0b), causing a permanent white screen. The overload with a rom
   // name additionally sets R[12]/R[13]/R[14]/R_IRQ/R_SVC and calls
   // ARM9.JumpTo(entry) / ARM7.JumpTo(entry) to redirect execution to
   // the game's entry point.
   nds->SetupDirectBoot(s_retro_game_name);
}

static void check_variables(bool init)
{
   struct retro_variable var = {0};

#ifdef HAVE_OPENGL
   bool gl_update = false;
#endif

   var.key = "melonds_console_mode";
   if (environ_cb(RETRO_ENVIRONMENT_GET_VARIABLE, &var) && var.value)
   {
      if (!strcmp(var.value, "DSi"))
         Config::ConsoleType = 1;
      else
         Config::ConsoleType = 0;
   }

   var.key = "melonds_boot_directly";
   if (environ_cb(RETRO_ENVIRONMENT_GET_VARIABLE, &var) && var.value)
   {
      if (!strcmp(var.value, "disabled"))
         Config::DirectBoot = 0;
      else
         Config::DirectBoot = 1;
   }

   ScreenLayout layout = ScreenLayout::TopBottom;
   var.key = "melonds_screen_layout";
   if (environ_cb(RETRO_ENVIRONMENT_GET_VARIABLE, &var) && var.value)
   {
      if (!strcmp(var.value, "Top/Bottom"))
         layout = ScreenLayout::TopBottom;
      else if (!strcmp(var.value, "Bottom/Top"))
         layout = ScreenLayout::BottomTop;
      else if (!strcmp(var.value, "Left/Right"))
         layout = ScreenLayout::LeftRight;
      else if (!strcmp(var.value, "Right/Left"))
         layout = ScreenLayout::RightLeft;
      else if (!strcmp(var.value, "Top Only"))
         layout = ScreenLayout::TopOnly;
      else if (!strcmp(var.value, "Bottom Only"))
         layout = ScreenLayout::BottomOnly;
      else if (!strcmp(var.value, "Hybrid Top"))
         layout = ScreenLayout::HybridTop;
      else if (!strcmp(var.value, "Hybrid Bottom"))
         layout = ScreenLayout::HybridBottom;
   }

   var.key = "melonds_screen_gap";
   if (environ_cb(RETRO_ENVIRONMENT_GET_VARIABLE, &var) && var.value)
   {
        screen_layout_data.screen_gap_unscaled = std::stoi(var.value);
   }

#ifdef HAVE_OPENGL
   var.key = "melonds_hybrid_ratio";
   if (environ_cb(RETRO_ENVIRONMENT_GET_VARIABLE, &var) && var.value != NULL)
   {
      screen_layout_data.hybrid_ratio = std::stoi(var.value);
   }
#else
   screen_layout_data.hybrid_ratio = 2;
#endif

   var.key = "melonds_hybrid_small_screen";
   if (environ_cb(RETRO_ENVIRONMENT_GET_VARIABLE, &var) && var.value != NULL)
   {
      SmallScreenLayout old_hybrid_screen_value = screen_layout_data.hybrid_small_screen;
      if (!strcmp(var.value, "Top"))
         screen_layout_data.hybrid_small_screen  = SmallScreenLayout::SmallScreenTop;
      else if (!strcmp(var.value, "Bottom"))
         screen_layout_data.hybrid_small_screen  = SmallScreenLayout::SmallScreenBottom;
      else
         screen_layout_data.hybrid_small_screen  = SmallScreenLayout::SmallScreenDuplicate;

   #ifdef HAVE_OPENGL
      if(old_hybrid_screen_value != screen_layout_data.hybrid_small_screen) {
         gl_update = true;
      }
   #endif
   }

   var.key = "melonds_swapscreen_mode";
   if (environ_cb(RETRO_ENVIRONMENT_GET_VARIABLE, &var) && var.value != NULL)
   {
      toggle_swap_screen = !strcmp(var.value, "Toggle");
   }

   var.key = "melonds_randomize_mac_address";
   if (environ_cb(RETRO_ENVIRONMENT_GET_VARIABLE, &var) && var.value)
   {
      if (!strcmp(var.value, "enabled"))
         Config::RandomizeMAC = 1;
      else
         Config::RandomizeMAC = 0;
   }

#ifdef HAVE_THREADS
   var.key = "melonds_threaded_renderer";
   if (environ_cb(RETRO_ENVIRONMENT_GET_VARIABLE, &var) && var.value)
   {
      if (!strcmp(var.value, "enabled"))
         video_settings.Soft_Threaded = true;
      else
         video_settings.Soft_Threaded = false;
   }
#endif

   TouchMode new_touch_mode = TouchMode::Disabled;
   var.key = "melonds_touch_mode";
   if (environ_cb(RETRO_ENVIRONMENT_GET_VARIABLE, &var) && var.value)
   {
      if (!strcmp(var.value, "Mouse"))
         new_touch_mode = TouchMode::Mouse;
      else if (!strcmp(var.value, "Touch"))
         new_touch_mode = TouchMode::Touch;
      else if (!strcmp(var.value, "Joystick"))
         new_touch_mode = TouchMode::Joystick;
   }

#ifdef HAVE_OPENGL
   if(input_state.current_touch_mode != new_touch_mode)
      gl_update = true;

   var.key = "melonds_opengl_renderer";
   if (environ_cb(RETRO_ENVIRONMENT_GET_VARIABLE, &var) && var.value)
   {
      bool use_opengl = !strcmp(var.value, "enabled");
      if (use_opengl != enable_opengl)
      {
         // OpenGL enabled state changed - reset renderer state
         // so render_frame() will re-initialize the GL renderer
         // (or fall back to software) on the next frame.
         if (using_opengl && !use_opengl)
            deinitialize_opengl_renderer();
         current_renderer = CurrentRenderer::None;
         gl_update = true;
      }
      enable_opengl = use_opengl;
   }

      // NOTE (perf): this wrapper previously forced
      //     video_settings.Soft_Threaded = false;
      // here whenever the OpenGL OPTION was enabled, even though the
      // software renderer would still be used after GL init failure or on
      // non-stacked layouts — silently leaving the fallback single-threaded
      // AND dead-locking the flag away from later checks. Threading is now
      // honored via apply_soft_threading(): while actually running the GL
      // renderer it has no effect anyway.

   var.key = "melonds_opengl_resolution";
   if (environ_cb(RETRO_ENVIRONMENT_GET_VARIABLE, &var) && var.value)
   {
      int first_char_val = (int)var.value[0];
      int scaleing = Clamp(first_char_val - 48, 0, 8);
      if(video_settings.GL_ScaleFactor != scaleing)
         gl_update = true;
      video_settings.GL_ScaleFactor = scaleing;
   }
   else
   {
      video_settings.GL_ScaleFactor = 1;
   }

   var.key = "melonds_opengl_better_polygons";
   if (environ_cb(RETRO_ENVIRONMENT_GET_VARIABLE, &var) && var.value)
   {
      bool enabled = !strcmp(var.value, "enabled");
      gl_update |= enabled != video_settings.GL_BetterPolygons;
      if (enabled)
         video_settings.GL_BetterPolygons = true;
      else
         video_settings.GL_BetterPolygons = false;
   }

   var.key = "melonds_opengl_filtering";
   if (environ_cb(RETRO_ENVIRONMENT_GET_VARIABLE, &var) && var.value)
   {
      opengl_linear_filtering  = !strcmp(var.value, "linear");
   }

   if((using_opengl && gl_update) || layout != current_screen_layout)
      refresh_opengl = true;
#endif

#ifdef JIT_ENABLED
   var.key = "melonds_jit_enable";
   if (environ_cb(RETRO_ENVIRONMENT_GET_VARIABLE, &var) && var.value)
   {
      if (!strcmp(var.value, "enabled"))
         Config::JIT_Enable = true;
      else
         Config::JIT_Enable = false;
   }

   var.key = "melonds_jit_block_size";
   if (environ_cb(RETRO_ENVIRONMENT_GET_VARIABLE, &var) && var.value)
   {
      Config::JIT_MaxBlockSize = std::stoi(var.value);
   }

   var.key = "melonds_jit_branch_optimisations";
   if (environ_cb(RETRO_ENVIRONMENT_GET_VARIABLE, &var) && var.value)
   {
      if (!strcmp(var.value, "enabled"))
         Config::JIT_BranchOptimisations = true;
      else
         Config::JIT_BranchOptimisations = false;
   }

   var.key = "melonds_jit_literal_optimisations";
   if (environ_cb(RETRO_ENVIRONMENT_GET_VARIABLE, &var) && var.value)
   {
      if (!strcmp(var.value, "enabled"))
         Config::JIT_LiteralOptimisations = true;
      else
         Config::JIT_LiteralOptimisations = false;
   }

   var.key = "melonds_jit_fast_memory";
   if (environ_cb(RETRO_ENVIRONMENT_GET_VARIABLE, &var) && var.value)
   {
      if (!strcmp(var.value, "enabled"))
         Config::JIT_FastMemory = true;
      else
         Config::JIT_FastMemory = false;
   }
#endif

   var.key = "melonds_dsi_sdcard";
   if (environ_cb(RETRO_ENVIRONMENT_GET_VARIABLE, &var) && var.value)
   {
      if (!strcmp(var.value, "enabled"))
         Config::DSiSDEnable = 1;
      else
         Config::DSiSDEnable = 0;
   }

   var.key = "melonds_mic_input";
   if (environ_cb(RETRO_ENVIRONMENT_GET_VARIABLE, &var) && var.value)
   {
      if (!strcmp(var.value, "Blow Noise"))
         mic_noise_type = 1;
      else
         mic_noise_type = 0;
   }

   var.key = "melonds_audio_bitrate";
   if (environ_cb(RETRO_ENVIRONMENT_GET_VARIABLE, &var) && var.value)
   {
      if (!strcmp(var.value, "10-bit"))
         Config::AudioBitrate = 1;
      else if (!strcmp(var.value, "16-bit"))
         Config::AudioBitrate = 2;
      else
         Config::AudioBitrate = 0;
   }

   var.key = "melonds_audio_interpolation";
   if (environ_cb(RETRO_ENVIRONMENT_GET_VARIABLE, &var) && var.value)
   {
      if (!strcmp(var.value, "Cubic"))
         Config::AudioInterp = 3;
      else if (!strcmp(var.value, "Cosine"))
         Config::AudioInterp = 2;
      else if (!strcmp(var.value, "Linear"))
         Config::AudioInterp = 1;
      else
         Config::AudioInterp = 0;
   }

   var.key = "melonds_use_fw_settings";
   if (environ_cb(RETRO_ENVIRONMENT_GET_VARIABLE, &var) && var.value)
   {
      if (!strcmp(var.value, "disabled"))
         Config::FirmwareOverrideSettings = true;
      else
         Config::FirmwareOverrideSettings = false;
   }

   var.key = "melonds_language";
   if (environ_cb(RETRO_ENVIRONMENT_GET_VARIABLE, &var) && var.value)
   {
      if (!strcmp(var.value, "Japanese"))
         Config::FirmwareLanguage = 0;
      else if (!strcmp(var.value, "English"))
         Config::FirmwareLanguage = 1;
      else if (!strcmp(var.value, "French"))
         Config::FirmwareLanguage = 2;
      else if (!strcmp(var.value, "German"))
         Config::FirmwareLanguage = 3;
      else if (!strcmp(var.value, "Italian"))
         Config::FirmwareLanguage = 4;
      else if (!strcmp(var.value, "Spanish"))
         Config::FirmwareLanguage = 5;
   }

   input_state.current_touch_mode = new_touch_mode;

#ifdef HAVE_OPENGL
   // When the GL renderer is active, the screen layout is managed by
   // update_gl_screenlayout() in opengl.cpp.  Don't call update_screenlayout
   // here — it would overwrite the GL-specific layout dimensions.
   if (!using_opengl)
      update_screenlayout(layout, &screen_layout_data, false, swapped_screens);
#else
   update_screenlayout(layout, &screen_layout_data, false, swapped_screens);
#endif

   // Apply soft-renderer threading whenever options changed while running
   // the software renderer (e.g. user toggles threaded_renderer / screen
   // layout flips from stacked to horizontal forcing software mode).
   apply_soft_threading();

   update_option_visibility();
}


static void audio_callback(void)
{
   if (!nds) return;

   static int16_t buffer[0x1000];
   u32 size = nds->SPU.GetOutputSize();
   if(size > sizeof(buffer) / (2 * sizeof(int16_t)))
      size = sizeof(buffer) / (2 * sizeof(int16_t));

   nds->SPU.ReadOutput(buffer, size);
   audio_cb(buffer, size);
}

static void render_frame(void)
{
   if (!nds) return;

   if (current_renderer == CurrentRenderer::None)
   {
 #ifdef HAVE_OPENGL
         if (enable_opengl)
         {
            if (initialize_opengl()) current_renderer = CurrentRenderer::OpenGLRenderer;
            else
            {
               using_opengl = false;
               current_renderer = CurrentRenderer::Software;
               // GL init failed — keep the software fallback threaded and
               // leave a LOUD trace so the perf regression isn't silent.
               log_cb(RETRO_LOG_WARN,
                  "[melonds-perf] OpenGL renderer unavailable — running CPU "
                  "renderer (threaded=%d). Expect lower performance; check "
                  "earlier melonds-gl logs for the cause.\n",
                  video_settings.Soft_Threaded ? 1 : 0);
               apply_soft_threading();
            }
         }
         else
         {
            if(using_opengl) deinitialize_opengl_renderer();
#endif
            current_renderer = CurrentRenderer::Software;
            apply_soft_threading();
#ifdef HAVE_OPENGL
         }
#endif
   }
#ifdef HAVE_OPENGL
   if(using_opengl)
   {
      if (current_renderer == CurrentRenderer::Software) render_opengl_frame(true);
      else render_opengl_frame(false);
   }
   else
   {
   #endif
      int frontbuf = nds->GPU.FrontBuffer;

      if(screen_layout_data.hybrid)
      {
         unsigned primary = screen_layout_data.displayed_layout == ScreenLayout::HybridTop ? 0 : 1;

         copy_hybrid_screen(&screen_layout_data, nds->GPU.Framebuffer[frontbuf][primary].get(), ScreenId::Primary);

         switch(screen_layout_data.hybrid_small_screen) {
            case SmallScreenLayout::SmallScreenTop:
               copy_hybrid_screen(&screen_layout_data, nds->GPU.Framebuffer[frontbuf][0].get(), ScreenId::Bottom);
               break;
            case SmallScreenLayout::SmallScreenBottom:
               copy_hybrid_screen(&screen_layout_data, nds->GPU.Framebuffer[frontbuf][1].get(), ScreenId::Bottom);
               break;
            case SmallScreenLayout::SmallScreenDuplicate:
               copy_hybrid_screen(&screen_layout_data, nds->GPU.Framebuffer[frontbuf][0].get(), ScreenId::Top);
               copy_hybrid_screen(&screen_layout_data, nds->GPU.Framebuffer[frontbuf][1].get(), ScreenId::Bottom);
               break;
         }

         if(cursor_enabled(&input_state))
            draw_cursor(&screen_layout_data, input_state.touch_x, input_state.touch_y);

         video_cb((uint8_t*)screen_layout_data.buffer_ptr, screen_layout_data.buffer_width, screen_layout_data.buffer_height, screen_layout_data.buffer_width * sizeof(uint32_t));
      }
      else
      {
         if(screen_layout_data.enable_top_screen)
            copy_screen(&screen_layout_data, nds->GPU.Framebuffer[frontbuf][0].get(), screen_layout_data.top_screen_offset);
         if(screen_layout_data.enable_bottom_screen)
            copy_screen(&screen_layout_data, nds->GPU.Framebuffer[frontbuf][1].get(), screen_layout_data.bottom_screen_offset);

         if(cursor_enabled(&input_state) && current_screen_layout != ScreenLayout::TopOnly)
            draw_cursor(&screen_layout_data, input_state.touch_x, input_state.touch_y);

         video_cb((uint8_t*)screen_layout_data.buffer_ptr, screen_layout_data.buffer_width, screen_layout_data.buffer_height, screen_layout_data.buffer_width * sizeof(uint32_t));
      }
#ifdef HAVE_OPENGL
   }
#endif
}

void retro_run(void)
{
   if (!nds) return;

   update_input(&input_state);

   if (input_state.swap_screens_btn != swapped_screens)
   {
      if (toggle_swap_screen)
      {
         if (swapped_screens == false)
         {
            swap_screen_toggled = !swap_screen_toggled;
#ifdef HAVE_OPENGL
            if (using_opengl)
               update_gl_screenlayout(swap_screen_toggled);
            else
#endif
               update_screenlayout(current_screen_layout, &screen_layout_data, false, swap_screen_toggled);
            refresh_opengl = true;
         }
         swapped_screens = input_state.swap_screens_btn;
      }
      else
      {
         swapped_screens = input_state.swap_screens_btn;
#ifdef HAVE_OPENGL
         if (using_opengl)
            update_gl_screenlayout(swapped_screens);
         else
#endif
            update_screenlayout(current_screen_layout, &screen_layout_data, false, swapped_screens);
         refresh_opengl = true;
      }
   }

   // Mic state is now handled by Platform::Mic_ReadInput callback
   mic_noise_held = input_state.holding_noise_btn;

   // Always run the emulation frame regardless of renderer state.
   // The old check (current_renderer != CurrentRenderer::None) skipped
   // RunFrame() on the very first frame, which meant render_frame() read
   // an uninitialized (blank/white) framebuffer and the screen never
   // updated to show the actual game content.
   nds->RunFrame();

   render_frame();

   audio_callback();

   bool updated = false;
   if (environ_cb(RETRO_ENVIRONMENT_GET_VARIABLE_UPDATE, &updated) && updated)
   {
      check_variables(false);

      struct retro_system_av_info updated_av_info;
      retro_get_system_av_info(&updated_av_info);
      environ_cb(RETRO_ENVIRONMENT_SET_SYSTEM_AV_INFO, &updated_av_info);
      clean_screenlayout_buffer(&screen_layout_data);
   }

   // NDSCart_SRAMManager::Flush() removed - handled by Platform::WriteNDSSave callback
}


static bool load_bios_files()
{
   // Try to load BIOS files from the system directory.
   // If they don't exist, we use FreeBIOS (default in NDSArgs).
   if (!nds) return false;

   FILE* f = nullptr;
   const size_t ARM9BIOS_SIZE = 0x1000;
   const size_t ARM7BIOS_SIZE = 0x4000;

   // Load ARM9 BIOS
   std::string bios9_path = std::string(retro_base_directory) + "/bios9.bin";
   f = fopen(bios9_path.c_str(), "rb");
   if (f)
   {
      melonDS::ARM9BIOSImage bios9;
      size_t read = fread(bios9.data(), 1, ARM9BIOS_SIZE, f);
      fclose(f);
      if (read == ARM9BIOS_SIZE)
         nds->SetARM9BIOS(bios9);
      else
         log_cb(RETRO_LOG_WARN, "BIOS9 file size mismatch (expected 0x1000), using FreeBIOS.\n");
   }
   else
      log_cb(RETRO_LOG_INFO, "BIOS9 not found, using FreeBIOS.\n");

   // Load ARM7 BIOS
   std::string bios7_path = std::string(retro_base_directory) + "/bios7.bin";
   f = fopen(bios7_path.c_str(), "rb");
   if (f)
   {
      melonDS::ARM7BIOSImage bios7;
      size_t read = fread(bios7.data(), 1, ARM7BIOS_SIZE, f);
      fclose(f);
      if (read == ARM7BIOS_SIZE)
         nds->SetARM7BIOS(bios7);
      else
         log_cb(RETRO_LOG_WARN, "BIOS7 file size mismatch (expected 0x4000), using FreeBIOS.\n");
   }
   else
      log_cb(RETRO_LOG_INFO, "BIOS7 not found, using FreeBIOS.\n");

   nds->LoadBIOS();

   return true;
}

static bool _handle_load_game(unsigned type, const struct retro_game_info *info)
{
   cached_info = const_cast<retro_game_info*>(info);

   std::vector <std::string> required_roms = {"bios7.bin", "bios9.bin", "firmware.bin"};
   std::vector <std::string> missing_roms;

   for(std::string& rom : required_roms)
   {
      std::string fullpath = std::string(retro_base_directory) + "/" + rom;
      FILE* f = fopen(fullpath.c_str(), "rb");
      if (!f)
         missing_roms.push_back(rom);
      else
         fclose(f);
   }

   if(!missing_roms.empty())
   {
      std::string msg = "Missing bios/firmware in system directory. Using FreeBIOS.\n";
      log_cb(RETRO_LOG_ERROR, msg.c_str());
   }

   strcpy(Config::BIOS7Path, "bios7.bin");
   strcpy(Config::BIOS9Path, "bios9.bin");
   strcpy(Config::FirmwarePath, "firmware.bin");
   strcpy(Config::DSiBIOS7Path, "dsi_bios7.bin");
   strcpy(Config::DSiBIOS9Path, "dsi_bios9.bin");
   strcpy(Config::DSiFirmwarePath, "dsi_firmware.bin");
   strcpy(Config::DSiNANDPath, "dsi_nand.bin");
   strcpy(Config::DSiSDPath, "dsi_sd_card.bin");

   const char *retro_username;
   if (environ_cb(RETRO_ENVIRONMENT_GET_USERNAME, &retro_username) && retro_username)
      strcpy(Config::FirmwareUsername, retro_username);
   else
      strcpy(Config::FirmwareUsername, "melonDS");

   struct retro_input_descriptor desc[] = {
      { 0, RETRO_DEVICE_JOYPAD, 0, RETRO_DEVICE_ID_JOYPAD_LEFT,  "Left" },
      { 0, RETRO_DEVICE_JOYPAD, 0, RETRO_DEVICE_ID_JOYPAD_UP,    "Up" },
      { 0, RETRO_DEVICE_JOYPAD, 0, RETRO_DEVICE_ID_JOYPAD_DOWN,  "Down" },
      { 0, RETRO_DEVICE_JOYPAD, 0, RETRO_DEVICE_ID_JOYPAD_RIGHT, "Right" },
      { 0, RETRO_DEVICE_JOYPAD, 0, RETRO_DEVICE_ID_JOYPAD_A, "A" },
      { 0, RETRO_DEVICE_JOYPAD, 0, RETRO_DEVICE_ID_JOYPAD_B, "B" },
      { 0, RETRO_DEVICE_JOYPAD, 0, RETRO_DEVICE_ID_JOYPAD_SELECT, "Select" },
      { 0, RETRO_DEVICE_JOYPAD, 0, RETRO_DEVICE_ID_JOYPAD_START, "Start" },
      { 0, RETRO_DEVICE_JOYPAD, 0, RETRO_DEVICE_ID_JOYPAD_R, "R" },
      { 0, RETRO_DEVICE_JOYPAD, 0, RETRO_DEVICE_ID_JOYPAD_L, "L" },
      { 0, RETRO_DEVICE_JOYPAD, 0, RETRO_DEVICE_ID_JOYPAD_X, "X" },
      { 0, RETRO_DEVICE_JOYPAD, 0, RETRO_DEVICE_ID_JOYPAD_Y, "Y" },
      { 0, RETRO_DEVICE_JOYPAD, 0, RETRO_DEVICE_ID_JOYPAD_L2, "Make microphone noise" },
      { 0, RETRO_DEVICE_JOYPAD, 0, RETRO_DEVICE_ID_JOYPAD_R2, "Swap screens" },
      { 0, RETRO_DEVICE_JOYPAD, 0, RETRO_DEVICE_ID_JOYPAD_L3, "Close lid" },
      { 0, RETRO_DEVICE_JOYPAD, 0, RETRO_DEVICE_ID_JOYPAD_R3, "Touch joystick" },
      { 0, RETRO_DEVICE_ANALOG, RETRO_DEVICE_INDEX_ANALOG_RIGHT, RETRO_DEVICE_ID_ANALOG_X, "Touch joystick X" },
      { 0, RETRO_DEVICE_ANALOG, RETRO_DEVICE_INDEX_ANALOG_RIGHT, RETRO_DEVICE_ID_ANALOG_Y, "Touch joystick Y" },
      { 0 },
   };
   environ_cb(RETRO_ENVIRONMENT_SET_INPUT_DESCRIPTORS, desc);

   enum retro_pixel_format fmt = RETRO_PIXEL_FORMAT_XRGB8888;
   if (!environ_cb(RETRO_ENVIRONMENT_SET_PIXEL_FORMAT, &fmt))
   {
      log_cb(RETRO_LOG_INFO, "XRGB8888 is not supported.\n");
      return false;
   }

   check_variables(true);

   // Build NDSArgs
   melonDS::NDSArgs args;

   // Audio settings
   switch (Config::AudioBitrate)
   {
      case 1: args.BitDepth = melonDS::AudioBitDepth::_10Bit; break;
      case 2: args.BitDepth = melonDS::AudioBitDepth::_16Bit; break;
      default: args.BitDepth = melonDS::AudioBitDepth::Auto; break;
   }
   switch (Config::AudioInterp)
   {
      case 1: args.Interpolation = melonDS::AudioInterpolation::Linear; break;
      case 2: args.Interpolation = melonDS::AudioInterpolation::Cosine; break;
      case 3: args.Interpolation = melonDS::AudioInterpolation::Cubic; break;
      default: args.Interpolation = melonDS::AudioInterpolation::None; break;
   }
   args.OutputSampleRate = 32000.0;

#ifdef JIT_ENABLED
   // Gate the JIT on actual capability: some OEM ROMs/SELinux policies deny
   // PROT_EXEC on anonymous memory — recompiled blocks would then either
   // crash or never execute, which looks exactly like "JIT didn't help".
   // Probe once per load and fall back to the interpreter with a LOUD log.
   if (Config::JIT_Enable && !melonDS::ARMJIT_Global::ProbeCodeMemory())
   {
      log_cb(RETRO_LOG_ERROR,
         "[melonds-perf] JIT unusable on this device (executable code memory "
         "denied by W^X policy) — falling back to interpreter.\n");
      Config::JIT_Enable = false;
   }
   if (Config::JIT_Enable)
   {
      melonDS::JITArgs jit_args;
      jit_args.MaxBlockSize = Config::JIT_MaxBlockSize;
      jit_args.BranchOptimizations = Config::JIT_BranchOptimisations;
      jit_args.LiteralOptimizations = Config::JIT_LiteralOptimisations;
      jit_args.FastMemory = Config::JIT_FastMemory;
      args.JIT = jit_args;
   }
   else
      args.JIT = std::nullopt;
#endif

   // Create the NDS instance
   nds = new melonDS::NDS(std::move(args), nullptr);
   melonDS::NDS::Current = nds;

   // Initialize renderer
   // The default SoftRenderer is already set by NDSArgs

   // Initialize the opengl state if needed
#ifdef HAVE_OPENGL
   if (enable_opengl)
      initialize_opengl();
#endif

   // If the GL renderer was successfully initialized, the screen layout
   // geometry has already been set up inside initialize_opengl() to match
   // the GL compositor output (256*scale × 386*scale, top/bottom stack).
   // Skip the update_screenlayout call below to avoid overwriting it.
   //
   // If the GL renderer failed to initialize (or was disabled), we must
   // recalculate the screen layout now for the software renderer.
   if (!using_opengl)
      update_screenlayout(current_screen_layout, &screen_layout_data, false, swapped_screens);

   // Load BIOS files
   load_bios_files();

   // Set console type
   nds->ConsoleType = Config::ConsoleType;

   // Set up audio interpolation
   switch (Config::AudioInterp)
   {
      case 1: nds->SPU.SetInterpolation(melonDS::AudioInterpolation::Linear); break;
      case 2: nds->SPU.SetInterpolation(melonDS::AudioInterpolation::Cosine); break;
      case 3: nds->SPU.SetInterpolation(melonDS::AudioInterpolation::Cubic); break;
      default: nds->SPU.SetInterpolation(melonDS::AudioInterpolation::None); break;
   }

   // Set up save path
   char game_name[256];
   const char *ptr = path_basename(info->path);
   if (ptr)
      strlcpy(game_name, ptr, sizeof(game_name));
   else
      strlcpy(game_name, info->path, sizeof(game_name));
   path_remove_extension(game_name);
   s_retro_game_name = game_name;

   // If the loader set a save-basename override (e.g. NesStation's stable
   // game.id instead of the cacheDir/temp_rom.<ext> path), use it. This
   // avoids the bug where every game loaded from a content:// URI would
   // write to the same "temp_rom.sav" file and clobber each other.
   if (!melonds_save_basename_override.empty())
   {
      strlcpy(game_name, melonds_save_basename_override.c_str(), sizeof(game_name));
      log_cb(RETRO_LOG_INFO, "[melonds] save basename override: %s\n", game_name);
   }

   retro_save_path = std::string(retro_saves_directory) + "/" + std::string(game_name) + ".sav";

   // Load the ROM
   auto cart = melonDS::NDSCart::ParseROM(
      (u8*)info->data, info->size);
   if (!cart)
   {
      log_cb(RETRO_LOG_ERROR, "Failed to parse ROM.\n");
      delete nds;
      nds = nullptr;
      return false;
   }

   nds->SetNDSCart(std::move(cart));

   // Load existing save data if present
   FILE* save_fp = fopen(retro_save_path.c_str(), "rb");
   if (save_fp)
   {
      fseek(save_fp, 0, SEEK_END);
      long save_len = ftell(save_fp);
      fseek(save_fp, 0, SEEK_SET);

      if (save_len > 0)
      {
         u8* save_data = new u8[save_len];
         fread(save_data, 1, save_len, save_fp);
         nds->SetNDSSave(save_data, (u32)save_len);
         delete[] save_data;
      }
      fclose(save_fp);
   }

   // Setup direct boot and start emulation.
   // CRITICAL: Call SetupDirectBoot(const std::string&) (the overload with
   // a rom name). The parameterless SetupDirectBoot() loads the game code
   // into RAM and configures the MMU, but it does NOT set the ARM9/ARM7
   // entry-point registers and does NOT call JumpTo(). Without JumpTo(),
   // the ARM9 stays in the FreeBIOS boot_handler which is a dead loop
   // (b 0b), causing a permanent white screen. The overload with a rom
   // name additionally sets R[12]/R[13]/R[14]/R_IRQ/R_SVC and calls
   // ARM9.JumpTo(entry) / ARM7.JumpTo(entry) to redirect execution to
   // the game's entry point.
   if (Config::DirectBoot)
      nds->SetupDirectBoot(s_retro_game_name);

   nds->Start();

   // Apply soft-renderer threading right after the NDS instance is live
   // (check_variables already parsed the option; note the GL renderer may
   // have been initialized earlier inside this function).
   apply_soft_threading();

#ifdef JIT_ENABLED
   log_cb(RETRO_LOG_INFO,
      "[melonds-perf] JIT: %s block=%d branch=%d literal=%s fastmem=%s\n",
      Config::JIT_Enable ? "ON" : "OFF",
      Config::JIT_MaxBlockSize,
      Config::JIT_BranchOptimisations ? 1 : 0,
      Config::JIT_LiteralOptimisations ? "on" : "off",
      Config::JIT_FastMemory ? "on" : "off");
#else
   log_cb(RETRO_LOG_INFO,
      "[melonds-perf] JIT: unavailable in this build (no aarch64 backend).\n");
#endif

   // --- NDS diagnostics: one-time boot configuration summary ---
   // Helps diagnose white-screen issues: tells us whether we're on FreeBIOS or
   // real BIOS, whether JIT is active, and the composited framebuffer geometry.
   {
      int jit = -1;
#ifdef JIT_ENABLED
      jit = Config::JIT_Enable ? 1 : 0;
#endif
      log_cb(RETRO_LOG_INFO,
         "[melonds-diag] boot: console=%s directboot=%d jit=%d bios9=%d bios7=%d layout=%ux%u",
         Config::ConsoleType ? "DSi" : "DS",
         Config::DirectBoot,
         jit,
         (int)nds->IsLoadedARM9BIOSKnownNative(),
         (int)nds->IsLoadedARM7BIOSKnownNative(),
         screen_layout_data.buffer_width,
         screen_layout_data.buffer_height);
   }

   if (type == SLOT_1_2_BOOT)
   {
      char gba_game_name[256];
      std::string gba_save_path;
      const char *ptr = path_basename(info[1].path);
      if (ptr)
         strlcpy(gba_game_name, ptr, sizeof(gba_game_name));
      else
         strlcpy(gba_game_name, info[1].path, sizeof(gba_game_name));
      path_remove_extension(gba_game_name);

      retro_gba_save_path = std::string(retro_saves_directory) + "/" + std::string(gba_game_name) + ".srm";

      auto gbacart = melonDS::GBACart::ParseROM(
         (u8*)info[1].data, info[1].size);
      if (gbacart)
         nds->SetGBACart(std::move(gbacart));

      // Load existing GBA save data
      save_fp = fopen(retro_gba_save_path.c_str(), "rb");
      if (save_fp)
      {
         fseek(save_fp, 0, SEEK_END);
         long save_len = ftell(save_fp);
         fseek(save_fp, 0, SEEK_SET);
         if (save_len > 0)
         {
            u8* save_data = new u8[save_len];
            fread(save_data, 1, save_len, save_fp);
            nds->SetGBASave(save_data, (u32)save_len);
            delete[] save_data;
         }
         fclose(save_fp);
      }
   }

   return true;
}

bool retro_load_game(const struct retro_game_info *info)
{
   return _handle_load_game(0, info);
}

void retro_unload_game(void)
{
#ifdef HAVE_OPENGL
   deinitialize_opengl_renderer();
#endif
   if (nds)
   {
      // Flush saves before destroying
      // NDS core calls Platform::WriteNDSSave automatically, but force one last write
      if (nds->GetNDSSave() && nds->GetNDSSaveLength() > 0)
      {
         melonDS::Platform::WriteNDSSave(
            nds->GetNDSSave(), nds->GetNDSSaveLength(), 0, 0, nullptr);
      }

      delete nds;
      nds = nullptr;
   }
   melonDS::NDS::Current = nullptr;
   retro_save_path.clear();
   retro_gba_save_path.clear();
}

unsigned retro_get_region(void)
{
   return RETRO_REGION_NTSC;
}

bool retro_load_game_special(unsigned type, const struct retro_game_info *info, size_t num)
{
   return _handle_load_game(type, info);
}


size_t retro_serialize_size(void)
{
   if (!nds) return 0;

   if (nds->ConsoleType == 0)
   {
      // melonDS >= 1.0 always saves the FULL 16MB MainRAM (MainRAMMaxSize,
      // shared between DS and DSi), so a savestate is ~17-18 MB in total —
      // a fixed 16 MB test buffer is TOO SMALL. Writing MainRAM into it
      // overflows at offset 0x14, Savestate::VarArray sets Error and skips
      // the block, and the returned "size" is a bogus ~2-3 MB value. The
      // subsequent retro_serialize(buf, bogus_size) then fails the same
      // overflow check and returns false — "core failed to generate
      // savestate", 100% reproducible.
      //
      // Fix: use the owned-buffer Savestate constructor (Savestate.h
      // DEFAULT_SIZE = 32 MB, auto-resizes if the state is even larger,
      // e.g. big cart SRAM). This mirrors what upstream melonDS itself
      // does when saving states.
      Savestate* savestate = new Savestate();
      nds->DoSavestate(savestate);
      savestate->Finish();
      size_t size = savestate->Error ? 0 : (size_t)savestate->Length();
      if (savestate->Error)
         log_cb(RETRO_LOG_ERROR, "retro_serialize_size: savestate generation failed\n");
      delete savestate; // owned buffer is freed by the destructor
      return size;
   }
   else
   {
      log_cb(RETRO_LOG_WARN, "Savestates unsupported in DSi mode.\n");
      return 0;
   }
}

bool retro_serialize(void *data, size_t size)
{
   if (!nds) return false;

   if (nds->ConsoleType == 0)
   {
      Savestate* savestate = new Savestate(data, size, true);
      nds->DoSavestate(savestate);
      // CRITICAL: Finish() writes the savestate's actual length back into
      // the file header (offset 0x08) and writes each section's length
      // into its section header. Without Finish(), the header length
      // field stays 0 and retro_unserialize() rejects the file with
      // "savestate: expected a length of N, got 0". This was the bug
      // behind "NDS save state can't load" — files were written but
      // unreadable on the next launch.
      savestate->Finish();
      bool hadError = savestate->Error;
      delete savestate;
      return !hadError;
   }
   else
   {
      log_cb(RETRO_LOG_WARN, "Savestates unsupported in DSi mode.\n");
      return false;
   }
}

bool retro_unserialize(const void *data, size_t size)
{
   if (!nds) return false;

   if (nds->ConsoleType == 0)
   {
      Savestate* savestate = new Savestate((void*)data, size, false);
      nds->DoSavestate(savestate);
      // If the savestate header didn't match (wrong magic, wrong version,
      // length field was 0 from a non-Finish()'d save), Error will be
      // set and DoSavestate would have been a no-op. Report the failure
      // so the frontend can fall back to "no save" instead of silently
      // leaving the emulator in an inconsistent state.
      bool hadError = savestate->Error;
      delete savestate;
      return !hadError;
   }
   else
   {
      log_cb(RETRO_LOG_WARN, "Savestates unsupported in DSi mode.\n");
      return false;
   }
}

void *retro_get_memory_data(unsigned type)
{
   if (!nds) return NULL;

   if (type == RETRO_MEMORY_SYSTEM_RAM)
      return nds->MainRAM;
   else if (type == RETRO_MEMORY_SAVE_RAM)
   {
      // Expose the NDS cart's SRAM (in-game save) so the frontend can
      // load/save .sav files. Without this, retro_get_memory_data(SAVE_RAM)
      // returns NULL and nds_loader's loadSramFromDisk/saveSramToDisk no-op,
      // so .sav files are never read or written for NDS games.
      return nds->GetNDSSave();
   }
   else
      return NULL;
}

size_t retro_get_memory_size(unsigned type)
{
   if (!nds) return 0;

   if (type == RETRO_MEMORY_SYSTEM_RAM)
      return 0x400000;
   else if (type == RETRO_MEMORY_SAVE_RAM)
   {
      // Length of the cart's SRAM buffer. May be 0 if no cart is loaded
      // or the cart has no battery-backed save (e.g. some homebrew) — the
      // frontend treats 0 as "no SRAM to persist".
      return nds->GetNDSSaveLength();
   }
   else
      return 0;
}

void retro_cheat_reset(void)
{
   if (nds)
      nds->AREngine.Cheats.clear();
}

void retro_cheat_set(unsigned index, bool enabled, const char *code)
{
   if (!nds) return;

   ARCode curcode {};
   std::string str(code);
   char * pch =  &*str.begin();
   curcode.Name = std::string(code);
   curcode.Enabled = enabled;
   curcode.Parent = nullptr;
   pch = strtok(pch, " +");
   while (pch != NULL)
   {
    curcode.Code.push_back((u32)strtol(pch, NULL, 16));
    log_cb(RETRO_LOG_INFO, "Adding Code %s (%d) \n",pch, curcode.Code.back());
    pch = strtok(NULL, " +");
   }

   if (index < nds->AREngine.Cheats.size())
      nds->AREngine.Cheats[index] = curcode;
   else
      nds->AREngine.Cheats.push_back(curcode);
}
