/* Internal interface of the Wayland front-end: server.c (thread, socket,
 * output, frame clock), surface.c (surfaces, xdg-shell, presentation) and
 * seat.c (pointer, keyboard). Everything runs on the compositor thread. */
#ifndef DROIDNUX_WL_PRIVATE_H
#define DROIDNUX_WL_PRIVATE_H

#include <stdbool.h>
#include <stdint.h>
#include <wayland-server-core.h>

#include "presenter.h"

#define DNX_WL_MAX_DAMAGE 16

/* Damaged rectangles in buffer pixels; past DNX_WL_MAX_DAMAGE, their bounding box. */
struct dnx_wl_damage {
    dnx_box rects[DNX_WL_MAX_DAMAGE];
    int count;
};

enum dnx_wl_role { DNX_WL_ROLE_NONE, DNX_WL_ROLE_CURSOR, DNX_WL_ROLE_XDG };

struct dnx_wl_surface {
    struct wl_resource* resource;
    enum dnx_wl_role role;
    struct dnx_wl_xdg_surface* xdg; /* with DNX_WL_ROLE_XDG */

    /* Double-buffered state, applied by wl_surface.commit. */
    struct wl_resource* pending_buffer;
    struct wl_listener pending_buffer_destroy;
    bool pending_attach;
    struct dnx_wl_damage pending_damage;
    struct wl_list pending_frames; /* wl_callback resources */

    /* The committed buffer, held until the next one replaces it. */
    struct wl_resource* buffer;
    struct wl_listener buffer_destroy;
    int32_t width, height;
    /* What the presenter has not shown yet (shown surface only). */
    struct dnx_wl_damage damage;

    /* Cursor role: hotspot set by wl_pointer.set_cursor. */
    int32_t hotspot_x, hotspot_y;
};

/* -- server.c -- */
extern struct wl_display* dnx_wl_display;
extern int dnx_wl_width, dnx_wl_height;
/* Asks for a vsync: frame callbacks and presenter retries run on it. */
void dnx_wl_request_frame(void);
uint32_t dnx_wl_now_ms(void);

/* -- surface.c -- */
void dnx_wl_surface_init(struct wl_display* display);
/* The surface shown as the desktop, or NULL. */
struct dnx_wl_surface* dnx_wl_shown_surface(void);
/* Vsync: frame callbacks done, damage the presenter could not take retried. */
void dnx_wl_surface_on_frame(void);
/* Full repaint of the shown surface. */
void dnx_wl_surface_redraw(void);
/* The desktop size changed: reconfigure toplevels. */
void dnx_wl_surface_resized(void);
void dnx_wl_damage_add(struct dnx_wl_damage* damage, int32_t x, int32_t y, int32_t w, int32_t h);

/* -- seat.c -- */
void dnx_wl_seat_init(struct wl_display* display, const char* keymap_path);
/* The shown surface changed (NULL: none): pointer and keyboard focus follow it. */
void dnx_wl_seat_set_focus(struct dnx_wl_surface* surface);
/* A cursor surface committed a new image. */
void dnx_wl_seat_cursor_committed(struct dnx_wl_surface* surface);
void dnx_wl_seat_surface_destroyed(struct dnx_wl_surface* surface);
void dnx_wl_seat_pointer(float x, float y, bool relative);
void dnx_wl_seat_button(int button, bool down);
void dnx_wl_seat_scroll(float dx, float dy);
void dnx_wl_seat_key(int evdev_keycode, bool down);

#endif
