/*
 * Wayland front-end of the presentation core: a compositor (libwayland-server)
 * on its own thread inside the app process. It shows one window at a time,
 * the most recently mapped toplevel, as the whole desktop: the guest's own
 * compositor (KWin, nested) does the window management. See PLAN.md, Fase 4b.
 *
 * Input functions may be called from any thread: events go through a pipe to
 * the compositor thread.
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

/* The desktop scaled to width x height (ARGB), for the home screen's thumbnail.
 * Waits up to half a second for the compositor thread; false without a window. */
bool dnx_wl_snapshot(uint32_t* pixels, int width, int height);

/* Keyboard: evdev keycode (Android scan code). */
void dnx_wl_key(int evdev_keycode, bool down);
/* Soft keyboard text for the focused text field of the nested KWin (its input
 * method, see wl/nested.c): committed, or still being composed (preedit, "" to
 * clear it). UTF-8, copied. */
void dnx_wl_commit_text(const char* utf8);
void dnx_wl_preedit_text(const char* utf8);

/* Called on the compositor thread when a text field gets (true) or loses the
 * focus: the soft keyboard may show. True again when the focused field changes
 * (a tap on it does). Set before dnx_wl_start. */
void dnx_wl_set_text_input_callback(void (*callback)(bool active));

/* Android's clipboard text (UTF-8, copied): the nested desktop pastes it. */
void dnx_wl_set_clipboard(const char* utf8);
/* Called on the compositor thread with the text copied in the nested desktop.
 * Set before dnx_wl_start. */
void dnx_wl_set_clipboard_callback(void (*callback)(const char* utf8));

/* The interface scale the nested KWin should use (1.0 = 100%). */
void dnx_wl_set_scale(double scale);

/* Called on the compositor thread when KWin's scale changes by other means
 * (Plasma's display settings). Set before dnx_wl_start. */
void dnx_wl_set_scale_callback(void (*callback)(double scale));

/* Pointer, in desktop pixels. */
void dnx_wl_pointer_move(float x, float y);
void dnx_wl_pointer_move_relative(float dx, float dy);
/* X button numbers: 1 left, 2 middle, 3 right, 8/9 back/forward. */
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
