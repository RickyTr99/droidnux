/*
 * Wayland front-end of the presentation core: a compositor (libwayland-server)
 * on its own thread inside the app process. It shows one window at a time,
 * the most recently mapped toplevel, as the whole desktop: the guest's own
 * compositor (KWin, nested) does the window management. See PLAN.md, Fase 4b.
 *
 * Same entry points as the X11 front-end (dnx_x11.h). Input functions may be
 * called from any thread: events go through a pipe to the compositor thread.
 */
#ifndef DROIDNUX_WL_H
#define DROIDNUX_WL_H

#include <stdbool.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

typedef struct dnx_wl_config {
    const char* tmp_dir; /* host path of the guest's /tmp (its XDG_RUNTIME_DIR): the socket is wayland-<display> */
    int display;         /* socket number, usually 0 */
    int width, height;   /* initial desktop size in pixels */
} dnx_wl_config;

/* Starts the compositor thread. Returns false if it is already running or setup failed. */
bool dnx_wl_start(const dnx_wl_config* config);
bool dnx_wl_running(void);

/* Changes the desktop size: the shown window is configured to it. */
void dnx_wl_resize(int width, int height);

/* Repaints everything at the next vsync, e.g. after the surface was recreated. */
void dnx_wl_redraw(void);

/* Keyboard: evdev keycode (Android scan code). */
void dnx_wl_key(int evdev_keycode, bool down);
/* Soft keyboard text for the focused text field of the nested KWin (its input
 * method, see wl/nested.c): committed, or still being composed (preedit, "" to
 * clear it). UTF-8, copied. */
void dnx_wl_commit_text(const char* utf8);
void dnx_wl_preedit_text(const char* utf8);

/* Called on the compositor thread when a text field gets (true) or loses the
 * focus: the soft keyboard may show. Set before dnx_wl_start. */
void dnx_wl_set_text_input_callback(void (*callback)(bool active));

/* The interface scale the nested KWin should use (1.0 = 100%). */
void dnx_wl_set_scale(double scale);

/* Pointer, in desktop pixels. */
void dnx_wl_pointer_move(float x, float y);
void dnx_wl_pointer_move_relative(float dx, float dy);
/* X button numbers, as in dnx_x11.h: 1 left, 2 middle, 3 right, 8/9 back/forward. */
void dnx_wl_button(int button, bool down);
/* Scroll, in notches (1.0 = one wheel click); fractional values for touchpads. */
void dnx_wl_scroll(float dx, float dy);

/* Touchscreen, in desktop pixels; a frame ends each group of changes. */
void dnx_wl_touch_down(int id, float x, float y);
void dnx_wl_touch_motion(int id, float x, float y);
void dnx_wl_touch_up(int id);
void dnx_wl_touch_frame(void);
void dnx_wl_touch_cancel(void);

#ifdef __cplusplus
}
#endif

#endif /* DROIDNUX_WL_H */
