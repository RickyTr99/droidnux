/*
 * X11 front-end of the presentation core: an X server (from Termux:X11) that
 * runs on its own thread inside the app process, draws into a CPU framebuffer
 * and hands damaged areas to the presenter.
 *
 * Input functions may be called from any thread: events go through a pipe
 * and are applied on the server thread.
 */
#ifndef DROIDNUX_X11_H
#define DROIDNUX_X11_H

#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

typedef struct dnx_x11_config {
    const char* tmp_dir;  /* host path of the guest's /tmp: the socket goes in .X11-unix/X<display> */
    const char* xkb_dir;  /* host path of the guest's /usr/share/X11/xkb */
    const char* font_dir; /* host path of the guest's /usr/share/fonts/X11, or NULL */
    int display;          /* display number, usually 0 */
    int width, height;    /* initial desktop size in pixels */
    int dpi;
} dnx_x11_config;

/* Starts the server thread. Returns false if it is already running or setup failed. */
bool dnx_x11_start(const dnx_x11_config* config);
bool dnx_x11_running(void);

/* Changes the desktop size (RandR), e.g. when the window or the scale changes. */
void dnx_x11_resize(int width, int height);

/* Repaints everything at the next vsync, e.g. after the surface was recreated. */
void dnx_x11_redraw(void);

/* Keyboard: evdev keycode (Android scan code), as on a Linux PC. */
void dnx_x11_key(int evdev_keycode, bool down);
/* Text without a physical key (IME): a Unicode code point. */
void dnx_x11_text(uint32_t codepoint);

/* Pointer, in desktop pixels. */
void dnx_x11_pointer_move(float x, float y);
void dnx_x11_pointer_move_relative(float dx, float dy);
/* 1 = left, 2 = middle, 3 = right, 8/9 = back/forward. */
void dnx_x11_button(int button, bool down);
/* Scroll, in notches (1.0 = one wheel click); fractional values for touchpads. */
void dnx_x11_scroll(float dx, float dy);

/* A picture of the desktop scaled to width x height (0xAARRGGBB), taken on
 * the server thread: false if it did not answer within half a second.
 * Windows shown on their own layer (full-screen GPU apps) are not in it. */
bool dnx_x11_snapshot(uint32_t* pixels, int width, int height);

/* Android's clipboard text (UTF-8, copied): X clients paste it from CLIPBOARD. */
void dnx_x11_set_clipboard(const char* utf8, size_t length);
/* Called on the server thread with the text an X client copied (UTF-8, NUL-terminated). */
typedef void (*dnx_x11_clipboard_callback)(const char* utf8);
void dnx_x11_set_clipboard_callback(dnx_x11_clipboard_callback callback);

#ifdef __cplusplus
}
#endif

#endif /* DROIDNUX_X11_H */
