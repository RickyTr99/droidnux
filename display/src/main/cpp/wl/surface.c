/*
 * Surfaces of the DroidNux Wayland front-end: wl_compositor, wl_surface and
 * xdg-shell. The most recently mapped toplevel is the desktop: it is
 * configured full screen at the desktop size (in surface coordinates, see
 * dnx_wl_scale) and shown through the presenter. Popups are dismissed at once
 * (the nested compositor has none).
 */
#include <android/log.h>
#include <math.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>
#include <wayland-server-protocol.h>

#include "private.h"
#include "presentation-time-protocol.h"
#include "xdg-shell-protocol.h"

#define log(prio, ...) __android_log_print(ANDROID_LOG_##prio, "DroidNux.WL", __VA_ARGS__)

struct dnx_wl_xdg_surface {
    struct wl_resource* resource;
    struct dnx_wl_surface* surface; /* NULL once the wl_surface is gone */
    struct wl_resource* toplevel;
    bool configured; /* a configure was sent */
    bool acked;      /* and acknowledged */
    bool mapped;
    struct wl_list link; /* in `toplevels` while mapped, most recent first */
};

static struct wl_list toplevels;       /* mapped dnx_wl_xdg_surface */
static struct wl_list frame_callbacks; /* committed wl_callback resources */
static struct wl_list feedbacks;       /* committed wp_presentation_feedback, user data: their surface */
static struct wl_list held_feedbacks;  /* those of a frame waiting for the next vsync */
static bool frame_sent;                /* a frame went to the presenter since the last vsync */
static struct dnx_wl_surface* shown;

struct dnx_wl_surface* dnx_wl_shown_surface(void) {
    return shown;
}

/* -- Damage --------------------------------------------------------------- */

static int32_t clamp_coord(int64_t v) {
    return v < 0 ? 0 : v > INT32_MAX / 2 ? INT32_MAX / 2 : (int32_t) v;
}

void dnx_wl_damage_add(struct dnx_wl_damage* damage, int32_t x, int32_t y, int32_t w, int32_t h) {
    if (w <= 0 || h <= 0) return;
    /* Clients often damage (0, 0, INT32_MAX, INT32_MAX). */
    dnx_box b = {clamp_coord(x), clamp_coord(y), clamp_coord((int64_t) x + w), clamp_coord((int64_t) y + h)};
    if (b.x1 >= b.x2 || b.y1 >= b.y2) return;
    if (damage->count < DNX_WL_MAX_DAMAGE) {
        damage->rects[damage->count++] = b;
        return;
    }
    dnx_box* u = &damage->rects[0];
    for (int i = 1; i < damage->count; i++) {
        dnx_box* r = &damage->rects[i];
        if (r->x1 < u->x1) u->x1 = r->x1;
        if (r->y1 < u->y1) u->y1 = r->y1;
        if (r->x2 > u->x2) u->x2 = r->x2;
        if (r->y2 > u->y2) u->y2 = r->y2;
    }
    if (b.x1 < u->x1) u->x1 = b.x1;
    if (b.y1 < u->y1) u->y1 = b.y1;
    if (b.x2 > u->x2) u->x2 = b.x2;
    if (b.y2 > u->y2) u->y2 = b.y2;
    damage->count = 1;
}

static void damage_all(struct dnx_wl_surface* s) {
    s->damage.count = 0;
    dnx_wl_damage_add(&s->damage, 0, 0, INT32_MAX, INT32_MAX);
}

/* -- Presentation --------------------------------------------------------- */

/* Shows the damage of the shown surface, at most one frame per vsync: a
 * second one before the vsync would replace the first before Android latches
 * it. What waits, or what the presenter cannot take yet, goes on the next
 * vsync. True if a frame went to the presenter. */
static void flush_held_feedbacks(bool presented);

static bool present_shown(void) {
    struct dnx_wl_surface* s = shown;
    if (!s || !s->buffer || s->damage.count == 0) return false;
    if (frame_sent) {
        dnx_wl_request_frame();
        return false;
    }
    struct wl_shm_buffer* shm = wl_shm_buffer_get(s->buffer);
    struct dnx_wl_dmabuf* dmabuf = shm ? NULL : dnx_wl_dmabuf_get(s->buffer);
    if (shm) {
        uint32_t format = wl_shm_buffer_get_format(shm);
        if (format != WL_SHM_FORMAT_XRGB8888 && format != WL_SHM_FORMAT_ARGB8888) shm = NULL;
    }
    if (!shm && !dmabuf) {
        s->damage.count = 0;
        return false;
    }

    dnx_box rects[DNX_WL_MAX_DAMAGE];
    int count = 0;
    for (int i = 0; i < s->damage.count; i++) {
        dnx_box r = s->damage.rects[i];
        if (r.x2 > s->width) r.x2 = s->width;
        if (r.y2 > s->height) r.y2 = s->height;
        if (r.x1 < r.x2 && r.y1 < r.y2) rects[count++] = r;
    }
    if (count == 0) {
        s->damage.count = 0;
        return false;
    }

    bool ok;
    if (dmabuf) {
        ok = dnx_wl_dmabuf_present(dmabuf, rects, count);
    } else {
        wl_shm_buffer_begin_access(shm);
        ok = dnx_presenter_present(wl_shm_buffer_get_data(shm), (uint32_t) s->width, (uint32_t) s->height,
                                   (uint32_t) wl_shm_buffer_get_stride(shm), DNX_PIXEL_BGRX8888, NULL, NULL, rects,
                                   count);
        wl_shm_buffer_end_access(shm);
    }
    if (ok) {
        s->damage.count = 0;
        frame_sent = true;
        /* Also after a redraw: a frame held while the app was away waits for this. */
        flush_held_feedbacks(true);
    }
    dnx_wl_request_frame(); /* to retry, or to let the next frame through */
    return ok;
}

static void set_shown(struct dnx_wl_surface* s) {
    if (s == shown) return;
    shown = s;
    if (s) {
        damage_all(s);
        present_shown();
    }
    dnx_wl_seat_set_focus(s);
}

static void update_shown(void) {
    struct dnx_wl_xdg_surface* top = NULL;
    if (!wl_list_empty(&toplevels)) top = wl_container_of(toplevels.next, top, link);
    set_shown(top ? top->surface : NULL);
}

/* Feedback for a frame the presenter took: presented at the next vsync (the
 * one Android latches it on). Sent at once, not at that vsync, so a nested
 * compositor starts its next frame a vsync ahead (KWin's frame takes most of
 * a 120 Hz vsync); present_shown() holds that frame for its own vsync. */
static int64_t predict_presentation(void) {
    static int64_t last;
    int64_t vsync = dnx_wl_vsync_ns(), interval = 1000000000 / dnx_presenter_refresh_rate();
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    int64_t now = (int64_t) ts.tv_sec * 1000000000 + ts.tv_nsec;
    int64_t t = vsync > 0 && vsync <= now ? vsync + ((now - vsync) / interval + 1) * interval : now;
    if (t < last + interval && last - now < 2 * interval) t = last + interval;
    last = t;
    return t;
}

static void send_presented(struct wl_resource* feedback, int64_t when) {
    uint64_t seconds = (uint64_t) (when / 1000000000);
    uint32_t refresh = (uint32_t) (1000000000 / dnx_presenter_refresh_rate());
    wp_presentation_feedback_send_presented(feedback, (uint32_t) (seconds >> 32), (uint32_t) seconds,
                                            (uint32_t) (when % 1000000000), refresh, 0, 0,
                                            WP_PRESENTATION_FEEDBACK_KIND_VSYNC);
}

/* A commit that showed nothing new counts as presented at the next vsync. */
static void send_feedback(struct wl_resource* feedback, bool presented) {
    int64_t vsync = dnx_wl_vsync_ns();
    if (presented && vsync > 0)
        send_presented(feedback, vsync);
    else
        wp_presentation_feedback_send_discarded(feedback);
}

/* The held frame went to the presenter (or never will, for another surface). */
static void flush_held_feedbacks(bool presented) {
    if (wl_list_empty(&held_feedbacks)) return; /* predict_presentation() counts its calls */
    /* Nothing of the shown surface left to show: its held frame is on screen
     * (a nested compositor waits for this feedback before its next frame). */
    if (!presented && shown && shown->damage.count == 0) presented = true;
    int64_t when = presented ? predict_presentation() : 0;
    struct wl_resource *feedback, *next;
    wl_resource_for_each_safe(feedback, next, &held_feedbacks) {
        if (presented && wl_resource_get_user_data(feedback) == shown)
            send_presented(feedback, when);
        else if (!presented && wl_resource_get_user_data(feedback) == shown)
            continue;
        else
            wp_presentation_feedback_send_discarded(feedback);
        wl_resource_destroy(feedback);
    }
}

void dnx_wl_surface_on_frame(void) {
    frame_sent = false;
    uint32_t now = dnx_wl_now_ms();
    struct wl_resource *callback, *next;
    wl_resource_for_each_safe(callback, next, &frame_callbacks) {
        wl_callback_send_done(callback, now);
        wl_resource_destroy(callback);
    }
    wl_resource_for_each_safe(callback, next, &feedbacks) {
        send_feedback(callback, wl_resource_get_user_data(callback) == shown);
        wl_resource_destroy(callback);
    }
    if (!present_shown()) flush_held_feedbacks(false);
}

void dnx_wl_surface_redraw(void) {
    if (!shown) return;
    damage_all(shown);
    present_shown();
}

/* -- xdg-shell: configure, map, unmap ------------------------------------- */

/* The desktop in surface coordinates: KWin renders its output at this size times its scale. */
static int32_t logical_size(int pixels) {
    return (int32_t) lround(pixels / dnx_wl_scale);
}

static void configure_toplevel(struct dnx_wl_xdg_surface* x) {
    if (!x->toplevel) return;
    int32_t width = logical_size(dnx_wl_width), height = logical_size(dnx_wl_height);
    int version = wl_resource_get_version(x->toplevel);
    if (version >= XDG_TOPLEVEL_CONFIGURE_BOUNDS_SINCE_VERSION)
        xdg_toplevel_send_configure_bounds(x->toplevel, width, height);
    if (version >= XDG_TOPLEVEL_WM_CAPABILITIES_SINCE_VERSION) {
        struct wl_array capabilities; /* none: no menus, no minimizing */
        wl_array_init(&capabilities);
        xdg_toplevel_send_wm_capabilities(x->toplevel, &capabilities);
        wl_array_release(&capabilities);
    }
    struct wl_array states;
    wl_array_init(&states);
    uint32_t* state = wl_array_add(&states, 2 * sizeof(uint32_t));
    if (state) {
        state[0] = XDG_TOPLEVEL_STATE_ACTIVATED;
        state[1] = XDG_TOPLEVEL_STATE_FULLSCREEN;
    }
    xdg_toplevel_send_configure(x->toplevel, width, height, &states);
    wl_array_release(&states);
    xdg_surface_send_configure(x->resource, wl_display_next_serial(dnx_wl_display));
    x->configured = true;
}

static void unmap(struct dnx_wl_xdg_surface* x) {
    if (!x->mapped) return;
    x->mapped = false;
    wl_list_remove(&x->link);
    update_shown();
}

void dnx_wl_surface_resized(void) {
    struct dnx_wl_xdg_surface* x;
    wl_list_for_each(x, &toplevels, link) configure_toplevel(x);
}

/* A commit of a surface with the xdg_surface role. */
static void xdg_commit(struct dnx_wl_xdg_surface* x) {
    struct dnx_wl_surface* s = x->surface;
    if (!x->toplevel) return;
    if (!x->configured) {
        /* The initial commit, without a buffer: answer with the first configure. */
        configure_toplevel(x);
        return;
    }
    if (!s->buffer) {
        unmap(x);
        return;
    }
    if (!x->mapped && x->acked) {
        x->mapped = true;
        wl_list_insert(&toplevels, &x->link);
        update_shown();
    }
}

/* -- xdg_positioner (popups are dismissed, so nothing is stored) ----------- */

static void destroy_resource(struct wl_client* client, struct wl_resource* resource) {
    (void) client;
    wl_resource_destroy(resource);
}

static void positioner_set_size(struct wl_client* c, struct wl_resource* r, int32_t w, int32_t h) {}
static void positioner_set_anchor_rect(struct wl_client* c, struct wl_resource* r, int32_t x, int32_t y, int32_t w,
                                       int32_t h) {}
static void positioner_set_enum(struct wl_client* c, struct wl_resource* r, uint32_t v) {}
static void positioner_set_offset(struct wl_client* c, struct wl_resource* r, int32_t x, int32_t y) {}
static void positioner_set_reactive(struct wl_client* c, struct wl_resource* r) {}
static void positioner_set_parent_configure(struct wl_client* c, struct wl_resource* r, uint32_t serial) {}

static const struct xdg_positioner_interface positioner_impl = {
    .destroy = destroy_resource,
    .set_size = positioner_set_size,
    .set_anchor_rect = positioner_set_anchor_rect,
    .set_anchor = positioner_set_enum,
    .set_gravity = positioner_set_enum,
    .set_constraint_adjustment = positioner_set_enum,
    .set_offset = positioner_set_offset,
    .set_reactive = positioner_set_reactive,
    .set_parent_size = positioner_set_offset,
    .set_parent_configure = positioner_set_parent_configure,
};

/* -- xdg_toplevel ------------------------------------------------------------ */

static void toplevel_set_parent(struct wl_client* c, struct wl_resource* r, struct wl_resource* parent) {}
static void toplevel_set_string(struct wl_client* c, struct wl_resource* r, const char* s) {}
static void toplevel_show_window_menu(struct wl_client* c, struct wl_resource* r, struct wl_resource* seat,
                                      uint32_t serial, int32_t x, int32_t y) {}
static void toplevel_move(struct wl_client* c, struct wl_resource* r, struct wl_resource* seat, uint32_t serial) {}
static void toplevel_resize(struct wl_client* c, struct wl_resource* r, struct wl_resource* seat, uint32_t serial,
                            uint32_t edges) {}
static void toplevel_set_size(struct wl_client* c, struct wl_resource* r, int32_t w, int32_t h) {}

/* Every state request gets the same answer: full screen at the desktop size. */
static void toplevel_reconfigure(struct wl_client* c, struct wl_resource* r) {
    (void) c;
    struct dnx_wl_xdg_surface* x = wl_resource_get_user_data(r);
    if (x && x->configured) configure_toplevel(x);
}
static void toplevel_set_fullscreen(struct wl_client* c, struct wl_resource* r, struct wl_resource* output) {
    toplevel_reconfigure(c, r);
}
static void toplevel_set_minimized(struct wl_client* c, struct wl_resource* r) {}

static const struct xdg_toplevel_interface toplevel_impl = {
    .destroy = destroy_resource,
    .set_parent = toplevel_set_parent,
    .set_title = toplevel_set_string,
    .set_app_id = toplevel_set_string,
    .show_window_menu = toplevel_show_window_menu,
    .move = toplevel_move,
    .resize = toplevel_resize,
    .set_max_size = toplevel_set_size,
    .set_min_size = toplevel_set_size,
    .set_maximized = toplevel_reconfigure,
    .unset_maximized = toplevel_reconfigure,
    .set_fullscreen = toplevel_set_fullscreen,
    .unset_fullscreen = toplevel_reconfigure,
    .set_minimized = toplevel_set_minimized,
};

static void toplevel_destroyed(struct wl_resource* resource) {
    struct dnx_wl_xdg_surface* x = wl_resource_get_user_data(resource);
    if (!x) return;
    unmap(x);
    x->toplevel = NULL;
    x->configured = x->acked = false;
}

/* -- xdg_popup ------------------------------------------------------------- */

static void popup_grab(struct wl_client* c, struct wl_resource* r, struct wl_resource* seat, uint32_t serial) {}
static void popup_reposition(struct wl_client* c, struct wl_resource* r, struct wl_resource* positioner,
                             uint32_t token) {}

static const struct xdg_popup_interface popup_impl = {
    .destroy = destroy_resource,
    .grab = popup_grab,
    .reposition = popup_reposition,
};

/* -- xdg_surface ----------------------------------------------------------- */

static void xdg_surface_get_toplevel(struct wl_client* client, struct wl_resource* resource, uint32_t id) {
    struct dnx_wl_xdg_surface* x = wl_resource_get_user_data(resource);
    if (x->toplevel) {
        wl_resource_post_error(resource, XDG_SURFACE_ERROR_ALREADY_CONSTRUCTED, "already a toplevel");
        return;
    }
    x->toplevel = wl_resource_create(client, &xdg_toplevel_interface, wl_resource_get_version(resource), id);
    if (!x->toplevel) {
        wl_client_post_no_memory(client);
        return;
    }
    wl_resource_set_implementation(x->toplevel, &toplevel_impl, x, toplevel_destroyed);
}

static void xdg_surface_get_popup(struct wl_client* client, struct wl_resource* resource, uint32_t id,
                                  struct wl_resource* parent, struct wl_resource* positioner) {
    struct wl_resource* popup = wl_resource_create(client, &xdg_popup_interface, wl_resource_get_version(resource), id);
    if (!popup) {
        wl_client_post_no_memory(client);
        return;
    }
    wl_resource_set_implementation(popup, &popup_impl, NULL, NULL);
    /* shortcut: popups are dismissed at once; host-side windows need them (rootless mode). */
    xdg_popup_send_popup_done(popup);
}

static void xdg_surface_set_window_geometry(struct wl_client* c, struct wl_resource* r, int32_t x, int32_t y,
                                            int32_t w, int32_t h) {}

static void xdg_surface_ack_configure(struct wl_client* client, struct wl_resource* resource, uint32_t serial) {
    (void) client;
    (void) serial;
    struct dnx_wl_xdg_surface* x = wl_resource_get_user_data(resource);
    if (x->configured) x->acked = true;
}

static const struct xdg_surface_interface xdg_surface_impl = {
    .destroy = destroy_resource,
    .get_toplevel = xdg_surface_get_toplevel,
    .get_popup = xdg_surface_get_popup,
    .set_window_geometry = xdg_surface_set_window_geometry,
    .ack_configure = xdg_surface_ack_configure,
};

static void xdg_surface_destroyed(struct wl_resource* resource) {
    struct dnx_wl_xdg_surface* x = wl_resource_get_user_data(resource);
    unmap(x);
    if (x->toplevel) wl_resource_set_user_data(x->toplevel, NULL);
    if (x->surface) x->surface->xdg = NULL;
    free(x);
}

/* -- xdg_wm_base ----------------------------------------------------------- */

static void wm_base_create_positioner(struct wl_client* client, struct wl_resource* resource, uint32_t id) {
    struct wl_resource* positioner =
        wl_resource_create(client, &xdg_positioner_interface, wl_resource_get_version(resource), id);
    if (!positioner) {
        wl_client_post_no_memory(client);
        return;
    }
    wl_resource_set_implementation(positioner, &positioner_impl, NULL, NULL);
}

static void wm_base_get_xdg_surface(struct wl_client* client, struct wl_resource* resource, uint32_t id,
                                    struct wl_resource* surface_resource) {
    struct dnx_wl_surface* s = wl_resource_get_user_data(surface_resource);
    if (s->role != DNX_WL_ROLE_NONE && s->role != DNX_WL_ROLE_XDG) {
        wl_resource_post_error(resource, XDG_WM_BASE_ERROR_ROLE, "surface has another role");
        return;
    }
    if (s->xdg || s->buffer) {
        wl_resource_post_error(resource, XDG_SURFACE_ERROR_UNCONFIGURED_BUFFER, "surface already has a buffer");
        return;
    }
    struct dnx_wl_xdg_surface* x = calloc(1, sizeof(*x));
    if (x) x->resource = wl_resource_create(client, &xdg_surface_interface, wl_resource_get_version(resource), id);
    if (!x || !x->resource) {
        free(x);
        wl_client_post_no_memory(client);
        return;
    }
    x->surface = s;
    wl_list_init(&x->link);
    wl_resource_set_implementation(x->resource, &xdg_surface_impl, x, xdg_surface_destroyed);
    s->role = DNX_WL_ROLE_XDG;
    s->xdg = x;
}

static void wm_base_pong(struct wl_client* c, struct wl_resource* r, uint32_t serial) {}

static const struct xdg_wm_base_interface wm_base_impl = {
    .destroy = destroy_resource,
    .create_positioner = wm_base_create_positioner,
    .get_xdg_surface = wm_base_get_xdg_surface,
    .pong = wm_base_pong,
};

static void wm_base_bind(struct wl_client* client, void* data, uint32_t version, uint32_t id) {
    (void) data;
    struct wl_resource* resource = wl_resource_create(client, &xdg_wm_base_interface, (int) version, id);
    if (!resource) {
        wl_client_post_no_memory(client);
        return;
    }
    wl_resource_set_implementation(resource, &wm_base_impl, NULL, NULL);
}

/* -- wl_surface ------------------------------------------------------------ */

/* The surface no longer shows `buffer`: the client may reuse it (dma-bufs
 * once the presenter let go too). */
static void release_buffer(struct wl_resource* buffer) {
    struct dnx_wl_dmabuf* dmabuf = dnx_wl_dmabuf_get(buffer);
    if (dmabuf)
        dnx_wl_dmabuf_unuse(dmabuf);
    else
        wl_buffer_send_release(buffer);
}

static void set_buffer(struct wl_resource** slot, struct wl_listener* listener, struct wl_resource* buffer) {
    if (*slot) wl_list_remove(&listener->link);
    *slot = buffer;
    if (buffer) wl_resource_add_destroy_listener(buffer, listener);
}

static void on_pending_buffer_destroy(struct wl_listener* listener, void* data) {
    (void) data;
    struct dnx_wl_surface* s = wl_container_of(listener, s, pending_buffer_destroy);
    wl_list_remove(&listener->link);
    s->pending_buffer = NULL;
}

static void on_buffer_destroy(struct wl_listener* listener, void* data) {
    (void) data;
    struct dnx_wl_surface* s = wl_container_of(listener, s, buffer_destroy);
    wl_list_remove(&listener->link);
    s->buffer = NULL; /* what the presenter copied stays on screen */
}

static void surface_attach(struct wl_client* client, struct wl_resource* resource, struct wl_resource* buffer,
                           int32_t x, int32_t y) {
    (void) client;
    struct dnx_wl_surface* s = wl_resource_get_user_data(resource);
    set_buffer(&s->pending_buffer, &s->pending_buffer_destroy, buffer);
    s->pending_attach = true;
}

/* No transform. KWin at a fractional scale sets the buffer scale rounded up: damage
 * scaled by it covers at least the real area. */
static void surface_damage(struct wl_client* client, struct wl_resource* resource, int32_t x, int32_t y, int32_t w,
                           int32_t h) {
    (void) client;
    struct dnx_wl_surface* s = wl_resource_get_user_data(resource);
    int64_t k = s->pending_scale;
    dnx_wl_damage_add(&s->pending_damage, clamp_coord(x * k), clamp_coord(y * k), clamp_coord(w * k),
                      clamp_coord(h * k));
}

static void surface_damage_buffer(struct wl_client* client, struct wl_resource* resource, int32_t x, int32_t y,
                                  int32_t w, int32_t h) {
    (void) client;
    struct dnx_wl_surface* s = wl_resource_get_user_data(resource);
    dnx_wl_damage_add(&s->pending_damage, x, y, w, h);
}

static void callback_destroyed(struct wl_resource* resource) {
    wl_list_remove(wl_resource_get_link(resource));
}

static void surface_frame(struct wl_client* client, struct wl_resource* resource, uint32_t id) {
    struct dnx_wl_surface* s = wl_resource_get_user_data(resource);
    struct wl_resource* callback = wl_resource_create(client, &wl_callback_interface, 1, id);
    if (!callback) {
        wl_client_post_no_memory(client);
        return;
    }
    wl_resource_set_implementation(callback, NULL, NULL, callback_destroyed);
    wl_list_insert(s->pending_frames.prev, wl_resource_get_link(callback));
}

static void surface_set_region(struct wl_client* c, struct wl_resource* r, struct wl_resource* region) {}
static void surface_set_int(struct wl_client* c, struct wl_resource* r, int32_t v) {}

static void surface_set_buffer_scale(struct wl_client* client, struct wl_resource* resource, int32_t scale) {
    (void) client;
    struct dnx_wl_surface* s = wl_resource_get_user_data(resource);
    if (scale < 1) {
        wl_resource_post_error(resource, WL_SURFACE_ERROR_INVALID_SCALE, "scale %d", scale);
        return;
    }
    s->pending_scale = scale;
}
static void surface_offset(struct wl_client* c, struct wl_resource* r, int32_t x, int32_t y) {}

static void surface_commit(struct wl_client* client, struct wl_resource* resource) {
    (void) client;
    struct dnx_wl_surface* s = wl_resource_get_user_data(resource);

    if (s->pending_attach) {
        struct wl_resource* buffer = s->pending_buffer;
        if (buffer != s->buffer) {
            struct dnx_wl_dmabuf* dmabuf = dnx_wl_dmabuf_get(buffer);
            if (dmabuf) dnx_wl_dmabuf_use(dmabuf);
            if (s->buffer) release_buffer(s->buffer);
        }
        set_buffer(&s->buffer, &s->buffer_destroy, buffer);
        set_buffer(&s->pending_buffer, &s->pending_buffer_destroy, NULL);
        s->pending_attach = false;
        int32_t width = 0, height = 0;
        struct wl_shm_buffer* shm = buffer ? wl_shm_buffer_get(buffer) : NULL;
        struct dnx_wl_dmabuf* dmabuf = shm ? NULL : dnx_wl_dmabuf_get(buffer);
        if (shm) {
            width = wl_shm_buffer_get_width(shm);
            height = wl_shm_buffer_get_height(shm);
        } else if (dmabuf) {
            dnx_wl_dmabuf_size(dmabuf, &width, &height);
        } else if (buffer) {
            log(WARN, "unknown buffer type");
        }
        if (width != s->width || height != s->height) damage_all(s);
        s->width = width;
        s->height = height;
    }
    for (int i = 0; i < s->pending_damage.count; i++) {
        dnx_box* r = &s->pending_damage.rects[i];
        dnx_wl_damage_add(&s->damage, r->x1, r->y1, r->x2 - r->x1, r->y2 - r->y1);
    }
    s->pending_damage.count = 0;
    s->scale = s->pending_scale;
    if (!wl_list_empty(&s->pending_frames)) {
        wl_list_insert_list(frame_callbacks.prev, &s->pending_frames);
        wl_list_init(&s->pending_frames);
        dnx_wl_request_frame();
    }

    if (s->role == DNX_WL_ROLE_XDG && s->xdg) xdg_commit(s->xdg);
    if (s->role == DNX_WL_ROLE_CURSOR) dnx_wl_seat_cursor_committed(s);
    bool presented = false, held = false;
    if (s == shown && s->damage.count > 0) {
        presented = present_shown();
        held = !presented && s->damage.count > 0;
    }
    if (s != shown) s->damage.count = 0; /* shown again only with full damage */

    if (!wl_list_empty(&s->pending_feedbacks)) {
        struct wl_resource *feedback, *next;
        if (presented) {
            int64_t when = predict_presentation();
            wl_resource_for_each_safe(feedback, next, &s->pending_feedbacks) {
                send_presented(feedback, when);
                wl_resource_destroy(feedback);
            }
        } else if (held) {
            /* This commit replaces the content held so far. */
            wl_resource_for_each_safe(feedback, next, &held_feedbacks) {
                wp_presentation_feedback_send_discarded(feedback);
                wl_resource_destroy(feedback);
            }
            wl_list_insert_list(held_feedbacks.prev, &s->pending_feedbacks);
            wl_list_init(&s->pending_feedbacks);
        } else {
            wl_list_insert_list(feedbacks.prev, &s->pending_feedbacks);
            wl_list_init(&s->pending_feedbacks);
            dnx_wl_request_frame();
        }
    }
    /* Held feedback is answered on a vsync, presented or not. */
    if (!wl_list_empty(&held_feedbacks)) dnx_wl_request_frame();
}

static const struct wl_surface_interface surface_impl = {
    .destroy = destroy_resource,
    .attach = surface_attach,
    .damage = surface_damage,
    .frame = surface_frame,
    .set_opaque_region = surface_set_region,
    .set_input_region = surface_set_region,
    .commit = surface_commit,
    .set_buffer_transform = surface_set_int,
    .set_buffer_scale = surface_set_buffer_scale,
    .damage_buffer = surface_damage_buffer,
    .offset = surface_offset,
};

static void surface_destroyed(struct wl_resource* resource) {
    struct dnx_wl_surface* s = wl_resource_get_user_data(resource);
    if (s->xdg) {
        unmap(s->xdg);
        s->xdg->surface = NULL;
    }
    if (shown == s) set_shown(NULL);
    dnx_wl_seat_surface_destroyed(s);
    if (s->buffer) release_buffer(s->buffer);
    set_buffer(&s->buffer, &s->buffer_destroy, NULL);
    set_buffer(&s->pending_buffer, &s->pending_buffer_destroy, NULL);
    struct wl_resource *callback, *next;
    wl_resource_for_each_safe(callback, next, &s->pending_frames) wl_resource_destroy(callback);
    wl_resource_for_each_safe(callback, next, &s->pending_feedbacks) wl_resource_destroy(callback);
    struct wl_list* lists[] = {&feedbacks, &held_feedbacks};
    for (size_t i = 0; i < sizeof(lists) / sizeof(lists[0]); i++) {
        wl_resource_for_each_safe(callback, next, lists[i]) {
            if (wl_resource_get_user_data(callback) != s) continue;
            wp_presentation_feedback_send_discarded(callback);
            wl_resource_destroy(callback);
        }
    }
    free(s);
}

/* -- wp_presentation ------------------------------------------------------- */

static void presentation_feedback(struct wl_client* client, struct wl_resource* resource,
                                  struct wl_resource* surface_resource, uint32_t id) {
    struct dnx_wl_surface* s = wl_resource_get_user_data(surface_resource);
    struct wl_resource* feedback = wl_resource_create(client, &wp_presentation_feedback_interface, 1, id);
    if (!feedback) {
        wl_client_post_no_memory(client);
        return;
    }
    wl_resource_set_implementation(feedback, NULL, s, callback_destroyed);
    wl_list_insert(s->pending_feedbacks.prev, wl_resource_get_link(feedback));
}

static const struct wp_presentation_interface presentation_impl = {
    .destroy = destroy_resource,
    .feedback = presentation_feedback,
};

static void presentation_bind(struct wl_client* client, void* data, uint32_t version, uint32_t id) {
    (void) data;
    struct wl_resource* resource = wl_resource_create(client, &wp_presentation_interface, (int) version, id);
    if (!resource) {
        wl_client_post_no_memory(client);
        return;
    }
    wl_resource_set_implementation(resource, &presentation_impl, NULL, NULL);
    wp_presentation_send_clock_id(resource, CLOCK_MONOTONIC);
}

/* -- wl_region (input and opaque regions are not used) --------------------- */

static void region_box(struct wl_client* c, struct wl_resource* r, int32_t x, int32_t y, int32_t w, int32_t h) {}

static const struct wl_region_interface region_impl = {
    .destroy = destroy_resource,
    .add = region_box,
    .subtract = region_box,
};

/* -- wl_compositor --------------------------------------------------------- */

static void compositor_create_surface(struct wl_client* client, struct wl_resource* resource, uint32_t id) {
    struct dnx_wl_surface* s = calloc(1, sizeof(*s));
    if (s) s->resource = wl_resource_create(client, &wl_surface_interface, wl_resource_get_version(resource), id);
    if (!s || !s->resource) {
        free(s);
        wl_client_post_no_memory(client);
        return;
    }
    s->pending_buffer_destroy.notify = on_pending_buffer_destroy;
    s->buffer_destroy.notify = on_buffer_destroy;
    s->pending_scale = s->scale = 1;
    wl_list_init(&s->pending_frames);
    wl_list_init(&s->pending_feedbacks);
    wl_resource_set_implementation(s->resource, &surface_impl, s, surface_destroyed);
}

static void compositor_create_region(struct wl_client* client, struct wl_resource* resource, uint32_t id) {
    struct wl_resource* region = wl_resource_create(client, &wl_region_interface, 1, id);
    if (!region) {
        wl_client_post_no_memory(client);
        return;
    }
    wl_resource_set_implementation(region, &region_impl, NULL, NULL);
}

static const struct wl_compositor_interface compositor_impl = {
    .create_surface = compositor_create_surface,
    .create_region = compositor_create_region,
};

static void compositor_bind(struct wl_client* client, void* data, uint32_t version, uint32_t id) {
    (void) data;
    struct wl_resource* resource = wl_resource_create(client, &wl_compositor_interface, (int) version, id);
    if (!resource) {
        wl_client_post_no_memory(client);
        return;
    }
    wl_resource_set_implementation(resource, &compositor_impl, NULL, NULL);
}

void dnx_wl_surface_init(struct wl_display* display) {
    wl_list_init(&toplevels);
    wl_list_init(&frame_callbacks);
    wl_global_create(display, &wl_compositor_interface, 5, NULL, compositor_bind);
    wl_global_create(display, &xdg_wm_base_interface, 6, NULL, wm_base_bind);
    wl_list_init(&feedbacks);
    wl_list_init(&held_feedbacks);
    wl_global_create(display, &wp_presentation_interface, 1, NULL, presentation_bind);
}
