#ifndef _OPENGL_H
#define _OPENGL_H

extern bool enable_opengl;
extern bool using_opengl;
extern bool refresh_opengl;

bool initialize_opengl();
void deinitialize_opengl_renderer();
void render_opengl_frame(bool sw);

// Updates screen_layout_data geometry to match the GL compositor output
// (256*scale × 386*scale, top-over-bottom stack).  Called from retro_run()
// when the user swaps screens or the layout changes at runtime.
// swap = true  → bottom screen rendered at top (for touch mapping)
// swap = false → default (bottom screen at bottom)
void update_gl_screenlayout(bool swap);

#endif
