/*
 * Seat of the DroidNux Wayland front-end: one pointer, one keyboard and a
 * touchscreen, focused on the shown surface. The pointer image comes from the client
 * (wl_pointer.set_cursor) and goes to the presenter's cursor layer.
 */
#include <android/log.h>
#include <errno.h>
#include <fcntl.h>
#include <math.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <linux/memfd.h>
#include <sys/mman.h>
#include <sys/syscall.h>
#include <unistd.h>
#include <wayland-server-protocol.h>

#include "private.h"

#define log(prio, ...) __android_log_print(ANDROID_LOG_##prio, "DroidNux.WL", __VA_ARGS__)

/* Linux input event codes (linux/input-event-codes.h). */
#define BTN_LEFT 0x110
#define BTN_RIGHT 0x111
#define BTN_MIDDLE 0x112
#define BTN_SIDE 0x113
#define BTN_EXTRA 0x114

static struct wl_list pointers;  /* wl_pointer resources */
static struct wl_list keyboards; /* wl_keyboard resources */
static struct wl_list touches;   /* wl_touch resources */
static struct dnx_wl_surface* focus;
static bool pointer_entered, keyboard_entered;
/* A touch sequence stays with the surface it started on. */
static struct dnx_wl_surface* touch_focus;
static int touch_points; /* down on touch_focus */
static float pointer_x, pointer_y; /* desktop pixels */
static char* keymap_path;
static char* keymap_text; /* xkb keymap of the guest's layout, or NULL */
static bool ralt_is_level3;

/* -- Keymap and modifiers --------------------------------------------------- */

/*
 * The keymap is compiled in the guest (xkbcomp, from the session's layout)
 * and read from its /tmp. shortcut: modifiers are tracked from fixed evdev
 * keys with the real-modifier order of every xkbcomp keymap (Shift, Lock,
 * Control, Mod1..Mod5); layouts that move modifiers (caps:ctrl...) need
 * libxkbcommon here. Nested compositors keep their own xkb state anyway.
 */
enum { MOD_SHIFT = 1 << 0, MOD_LOCK = 1 << 1, MOD_CONTROL = 1 << 2, MOD_1 = 1 << 3, MOD_2 = 1 << 4,
       MOD_4 = 1 << 6, MOD_5 = 1 << 7 };

static uint32_t mods_depressed, mods_locked;
static bool keys_down[256];

static void load_keymap(void) {
    FILE* f = fopen(keymap_path, "rb");
    if (!f) return;
    char* text = NULL;
    size_t size = 0;
    if (fseek(f, 0, SEEK_END) == 0) {
        long length = ftell(f);
        if (length > 0 && length < (16 << 20) && fseek(f, 0, SEEK_SET) == 0 && (text = malloc((size_t) length + 1))) {
            size = fread(text, 1, (size_t) length, f);
            text[size] = '\0';
        }
    }
    fclose(f);
    if (!text || size == 0) {
        free(text);
        return;
    }
    free(keymap_text);
    keymap_text = text;
    /* "key <RALT> { [ ISO_Level3_Shift ] };" in layouts with AltGr. */
    const char* ralt = strstr(text, "key <RALT>");
    const char* end = ralt ? strchr(ralt, ';') : NULL;
    ralt_is_level3 = false;
    if (end) {
        const char* level3 = strstr(ralt, "ISO_Level3_Shift");
        ralt_is_level3 = level3 && level3 < end;
    }
}

static uint32_t modifier_of(int key) {
    switch (key) {
    case 42: case 54: return MOD_SHIFT;   /* LEFTSHIFT, RIGHTSHIFT */
    case 29: case 97: return MOD_CONTROL; /* LEFTCTRL, RIGHTCTRL */
    case 56: return MOD_1;                /* LEFTALT */
    case 100: return ralt_is_level3 ? MOD_5 : MOD_1; /* RIGHTALT */
    case 125: case 126: return MOD_4;     /* LEFTMETA, RIGHTMETA */
    default: return 0;
    }
}

static void send_modifiers(struct wl_resource* keyboard, uint32_t serial) {
    wl_keyboard_send_modifiers(keyboard, serial, mods_depressed, 0, mods_locked, 0);
}

static void send_keymap(struct wl_resource* keyboard) {
    if (!keymap_text) load_keymap();
    if (!keymap_text) {
        log(WARN, "no keymap at %s: keyboard without keymap", keymap_path);
        int fd = open("/dev/null", O_RDONLY | O_CLOEXEC);
        if (fd >= 0) {
            wl_keyboard_send_keymap(keyboard, WL_KEYBOARD_KEYMAP_FORMAT_NO_KEYMAP, fd, 0);
            close(fd);
        }
        return;
    }
    /* A sealed memfd: clients map it MAP_PRIVATE (version 7+), which reads
     * zeros from ashmem. memfd_create has no bionic wrapper before API 30. */
    size_t size = strlen(keymap_text) + 1;
    int fd = (int) syscall(__NR_memfd_create, "droidnux-keymap", MFD_CLOEXEC | MFD_ALLOW_SEALING);
    if (fd < 0) {
        log(ERROR, "memfd_create: %s", strerror(errno));
        return;
    }
    if (write(fd, keymap_text, size) == (ssize_t) size &&
        fcntl(fd, F_ADD_SEALS, F_SEAL_SHRINK | F_SEAL_GROW | F_SEAL_WRITE | F_SEAL_SEAL) == 0)
        wl_keyboard_send_keymap(keyboard, WL_KEYBOARD_KEYMAP_FORMAT_XKB_V1, fd, (uint32_t) size);
    close(fd);
}

/* -- Focus ------------------------------------------------------------------ */

static bool same_client(struct wl_resource* resource, struct dnx_wl_surface* surface) {
    return surface && wl_resource_get_client(resource) == wl_resource_get_client(surface->resource);
}

/* Desktop pixels to surface coordinates: the shown surface fills the desktop. */
static wl_fixed_t to_surface(float pixels) {
    return wl_fixed_from_double(pixels / dnx_wl_scale);
}

static wl_fixed_t surface_x(void) {
    return to_surface(pointer_x);
}

static wl_fixed_t surface_y(void) {
    return to_surface(pointer_y);
}

static void pointer_enter(void) {
    if (pointer_entered || !focus) return;
    pointer_entered = true;
    uint32_t serial = wl_display_next_serial(dnx_wl_display);
    struct wl_resource* pointer;
    wl_resource_for_each(pointer, &pointers) {
        if (!same_client(pointer, focus)) continue;
        wl_pointer_send_enter(pointer, serial, focus->resource, surface_x(), surface_y());
        if (wl_resource_get_version(pointer) >= WL_POINTER_FRAME_SINCE_VERSION) wl_pointer_send_frame(pointer);
    }
}

static void keyboard_enter(void) {
    if (keyboard_entered || !focus) return;
    keyboard_entered = true;
    uint32_t serial = wl_display_next_serial(dnx_wl_display);
    struct wl_array keys;
    wl_array_init(&keys);
    for (int k = 0; k < 256; k++) {
        uint32_t* key = keys_down[k] ? wl_array_add(&keys, sizeof(uint32_t)) : NULL;
        if (key) *key = (uint32_t) k;
    }
    struct wl_resource* keyboard;
    wl_resource_for_each(keyboard, &keyboards) {
        if (!same_client(keyboard, focus)) continue;
        wl_keyboard_send_enter(keyboard, serial, focus->resource, &keys);
        send_modifiers(keyboard, serial);
    }
    wl_array_release(&keys);
}

void dnx_wl_seat_set_focus(struct dnx_wl_surface* surface) {
    if (surface == focus) return;
    uint32_t serial = wl_display_next_serial(dnx_wl_display);
    struct wl_resource* resource;
    if (focus && pointer_entered) {
        wl_resource_for_each(resource, &pointers) {
            if (!same_client(resource, focus)) continue;
            wl_pointer_send_leave(resource, serial, focus->resource);
            if (wl_resource_get_version(resource) >= WL_POINTER_FRAME_SINCE_VERSION) wl_pointer_send_frame(resource);
        }
    }
    if (focus && keyboard_entered) {
        wl_resource_for_each(resource, &keyboards) {
            if (same_client(resource, focus)) wl_keyboard_send_leave(resource, serial, focus->resource);
        }
    }
    focus = surface;
    pointer_entered = keyboard_entered = false;
    pointer_enter();
    keyboard_enter();
}

void dnx_wl_seat_surface_destroyed(struct dnx_wl_surface* surface) {
    if (surface == focus) {
        focus = NULL; /* the client knows: no leave for a destroyed surface */
        pointer_entered = keyboard_entered = false;
    }
    if (surface == touch_focus) {
        touch_focus = NULL;
        touch_points = 0;
    }
}

/* -- Cursor ----------------------------------------------------------------- */

#define DRM_FORMAT_ARGB8888 0x34325241 /* fourcc AR24 */

/* Shm buffers from toolkits, dma-bufs from compositors such as KWin. */
void dnx_wl_seat_cursor_committed(struct dnx_wl_surface* s) {
    struct wl_shm_buffer* shm = s->buffer ? wl_shm_buffer_get(s->buffer) : NULL;
    struct dnx_wl_dmabuf* dmabuf = shm ? NULL : dnx_wl_dmabuf_get(s->buffer);
    const uint8_t* data = NULL;
    int32_t width = 0, height = 0;
    uint32_t stride = 0, format = 0;
    if (shm && wl_shm_buffer_get_format(shm) == WL_SHM_FORMAT_ARGB8888) {
        wl_shm_buffer_begin_access(shm);
        data = wl_shm_buffer_get_data(shm);
        width = wl_shm_buffer_get_width(shm);
        height = wl_shm_buffer_get_height(shm);
        stride = (uint32_t) wl_shm_buffer_get_stride(shm);
    } else if (dmabuf) {
        dnx_wl_dmabuf_size(dmabuf, &width, &height);
        data = dnx_wl_dmabuf_begin_read(dmabuf, &format, &stride);
        if (data && format != DRM_FORMAT_ARGB8888) {
            dnx_wl_dmabuf_end_read(dmabuf);
            data = NULL;
        }
    }
    if (!data) {
        dnx_presenter_set_cursor(NULL, 0, 0, 0, 0);
        return;
    }
    /* Desktop pixels: the image is width / buffer scale in surface coordinates. */
    double factor = dnx_wl_scale / s->scale;
    int32_t out_width = (int32_t) lround(width * factor), out_height = (int32_t) lround(height * factor);
    if (out_width < 1) out_width = 1;
    if (out_height < 1) out_height = 1;
    uint32_t* rgba = malloc(sizeof(uint32_t) * (size_t) out_width * (size_t) out_height);
    /* Premultiplied 0xAARRGGBB to the layer's premultiplied RGBA bytes, as in
     * x11/output.c, resampled to the nearest pixel. */
    for (int32_t y = 0; rgba && y < out_height; y++) {
        int32_t sy = (int32_t) (y * height / out_height);
        const uint32_t* row = (const uint32_t*) (data + (size_t) sy * stride);
        for (int32_t x = 0; x < out_width; x++) {
            uint32_t p = row[x * width / out_width];
            rgba[y * out_width + x] = (p & 0xFF00FF00) | ((p & 0x00FF0000) >> 16) | ((p & 0x000000FF) << 16);
        }
    }
    if (shm)
        wl_shm_buffer_end_access(shm);
    else
        dnx_wl_dmabuf_end_read(dmabuf);
    if (!rgba) return;
    dnx_presenter_set_cursor(rgba, (uint32_t) out_width, (uint32_t) out_height,
                             (int32_t) lround(s->hotspot_x * dnx_wl_scale), (int32_t) lround(s->hotspot_y * dnx_wl_scale));
    free(rgba);
}

/* -- wl_pointer ----------------------------------------------------------- */

static void pointer_set_cursor(struct wl_client* client, struct wl_resource* resource, uint32_t serial,
                               struct wl_resource* surface_resource, int32_t hotspot_x, int32_t hotspot_y) {
    (void) serial;
    if (!focus || wl_resource_get_client(focus->resource) != client) return;
    if (!surface_resource) {
        dnx_presenter_set_cursor(NULL, 0, 0, 0, 0);
        return;
    }
    struct dnx_wl_surface* s = wl_resource_get_user_data(surface_resource);
    if (s->role != DNX_WL_ROLE_NONE && s->role != DNX_WL_ROLE_CURSOR) {
        wl_resource_post_error(resource, WL_POINTER_ERROR_ROLE, "surface has another role");
        return;
    }
    s->role = DNX_WL_ROLE_CURSOR;
    s->hotspot_x = hotspot_x;
    s->hotspot_y = hotspot_y;
    dnx_wl_seat_cursor_committed(s);
}

static void release_resource(struct wl_client* client, struct wl_resource* resource) {
    (void) client;
    wl_resource_destroy(resource);
}

static const struct wl_pointer_interface pointer_impl = {
    .set_cursor = pointer_set_cursor,
    .release = release_resource,
};

static const struct wl_keyboard_interface keyboard_impl = {
    .release = release_resource,
};

static void unlink_resource(struct wl_resource* resource) {
    wl_list_remove(wl_resource_get_link(resource));
}

/* -- Input from Android --------------------------------------------------- */

static void pointer_frame(struct wl_resource* pointer) {
    if (wl_resource_get_version(pointer) >= WL_POINTER_FRAME_SINCE_VERSION) wl_pointer_send_frame(pointer);
}

void dnx_wl_seat_pointer(float x, float y, bool relative) {
    if (relative) {
        x += pointer_x;
        y += pointer_y;
    }
    pointer_x = fmaxf(0, fminf(x, (float) dnx_wl_width - 1));
    pointer_y = fmaxf(0, fminf(y, (float) dnx_wl_height - 1));
    dnx_presenter_move_cursor((int32_t) pointer_x, (int32_t) pointer_y);
    if (!focus) return;
    if (!pointer_entered) {
        pointer_enter();
        return;
    }
    uint32_t time = dnx_wl_now_ms();
    struct wl_resource* pointer;
    wl_resource_for_each(pointer, &pointers) {
        if (!same_client(pointer, focus)) continue;
        wl_pointer_send_motion(pointer, time, surface_x(), surface_y());
        pointer_frame(pointer);
    }
}

void dnx_wl_seat_button(int button, bool down) {
    static const uint32_t codes[] = {[1] = BTN_LEFT, [2] = BTN_MIDDLE, [3] = BTN_RIGHT, [8] = BTN_SIDE,
                                     [9] = BTN_EXTRA};
    if (button < 0 || button >= (int) (sizeof(codes) / sizeof(codes[0])) || !codes[button] || !focus) return;
    pointer_enter();
    uint32_t serial = wl_display_next_serial(dnx_wl_display), time = dnx_wl_now_ms();
    struct wl_resource* pointer;
    wl_resource_for_each(pointer, &pointers) {
        if (!same_client(pointer, focus)) continue;
        wl_pointer_send_button(pointer, serial, time, codes[button],
                               down ? WL_POINTER_BUTTON_STATE_PRESSED : WL_POINTER_BUTTON_STATE_RELEASED);
        pointer_frame(pointer);
    }
}

/* One wheel notch is 15 units, as libinput reports wheels; whole notches are
 * a wheel, fractions a touchpad. */
static void send_axis(struct wl_resource* pointer, uint32_t time, uint32_t axis, float notches, bool wheel) {
    if (notches == 0) return;
    int version = wl_resource_get_version(pointer);
    if (wheel && version >= WL_POINTER_AXIS_VALUE120_SINCE_VERSION)
        wl_pointer_send_axis_value120(pointer, axis, (int32_t) lroundf(notches * 120));
    else if (wheel && version >= WL_POINTER_AXIS_DISCRETE_SINCE_VERSION)
        wl_pointer_send_axis_discrete(pointer, axis, (int32_t) lroundf(notches));
    wl_pointer_send_axis(pointer, time, axis, wl_fixed_from_double(notches * 15));
}

void dnx_wl_seat_scroll(float dx, float dy) {
    if (!focus) return;
    pointer_enter();
    bool wheel = dx == roundf(dx) && dy == roundf(dy);
    uint32_t time = dnx_wl_now_ms();
    struct wl_resource* pointer;
    wl_resource_for_each(pointer, &pointers) {
        if (!same_client(pointer, focus)) continue;
        if (wl_resource_get_version(pointer) >= WL_POINTER_AXIS_SOURCE_SINCE_VERSION)
            wl_pointer_send_axis_source(pointer, wheel ? WL_POINTER_AXIS_SOURCE_WHEEL : WL_POINTER_AXIS_SOURCE_FINGER);
        send_axis(pointer, time, WL_POINTER_AXIS_VERTICAL_SCROLL, dy, wheel);
        send_axis(pointer, time, WL_POINTER_AXIS_HORIZONTAL_SCROLL, dx, wheel);
        pointer_frame(pointer);
    }
}

void dnx_wl_seat_key(int key, bool down) {
    if (key <= 0 || key >= 256 || keys_down[key] == down) return;
    keys_down[key] = down;
    uint32_t old_depressed = mods_depressed, old_locked = mods_locked;
    uint32_t mod = modifier_of(key);
    if (mod) {
        /* Released only when no other key of the same modifier is down. */
        mods_depressed &= ~mod;
        for (int k = 1; k < 256; k++) {
            if (keys_down[k] && modifier_of(k) == mod) mods_depressed |= mod;
        }
    }
    if (down && key == 58) mods_locked ^= MOD_LOCK; /* CAPSLOCK */
    if (down && key == 69) mods_locked ^= MOD_2;    /* NUMLOCK */
    if (!focus) return;
    keyboard_enter();
    uint32_t serial = wl_display_next_serial(dnx_wl_display), time = dnx_wl_now_ms();
    struct wl_resource* keyboard;
    wl_resource_for_each(keyboard, &keyboards) {
        if (!same_client(keyboard, focus)) continue;
        wl_keyboard_send_key(keyboard, serial, time, (uint32_t) key,
                             down ? WL_KEYBOARD_KEY_STATE_PRESSED : WL_KEYBOARD_KEY_STATE_RELEASED);
        if (mods_depressed != old_depressed || mods_locked != old_locked) send_modifiers(keyboard, serial);
    }
}

/* -- Touch ------------------------------------------------------------------- */

static const struct wl_touch_interface touch_impl = {
    .release = release_resource,
};

static void touch_cancel(void) {
    struct wl_resource* touch;
    if (touch_focus && touch_points > 0) {
        wl_resource_for_each(touch, &touches) {
            if (same_client(touch, touch_focus)) wl_touch_send_cancel(touch);
        }
    }
    touch_points = 0;
}

void dnx_wl_seat_touch_down(int32_t id, float x, float y) {
    if (!focus) return;
    if (touch_focus != focus) {
        touch_cancel();
        touch_focus = focus;
    }
    touch_points++;
    uint32_t serial = wl_display_next_serial(dnx_wl_display), time = dnx_wl_now_ms();
    struct wl_resource* touch;
    wl_resource_for_each(touch, &touches) {
        if (same_client(touch, focus))
            wl_touch_send_down(touch, serial, time, focus->resource, id, to_surface(x), to_surface(y));
    }
}

void dnx_wl_seat_touch_motion(int32_t id, float x, float y) {
    if (!touch_focus || touch_points == 0) return;
    uint32_t time = dnx_wl_now_ms();
    struct wl_resource* touch;
    wl_resource_for_each(touch, &touches) {
        if (same_client(touch, touch_focus)) wl_touch_send_motion(touch, time, id, to_surface(x), to_surface(y));
    }
}

void dnx_wl_seat_touch_up(int32_t id) {
    if (!touch_focus || touch_points == 0) return;
    touch_points--;
    uint32_t serial = wl_display_next_serial(dnx_wl_display), time = dnx_wl_now_ms();
    struct wl_resource* touch;
    wl_resource_for_each(touch, &touches) {
        if (same_client(touch, touch_focus)) wl_touch_send_up(touch, serial, time, id);
    }
}

void dnx_wl_seat_touch_frame(void) {
    if (!touch_focus) return;
    struct wl_resource* touch;
    wl_resource_for_each(touch, &touches) {
        if (same_client(touch, touch_focus)) wl_touch_send_frame(touch);
    }
}

void dnx_wl_seat_touch_cancel(void) {
    touch_cancel();
}

/* -- wl_seat ----------------------------------------------------------------- */

static void seat_get_pointer(struct wl_client* client, struct wl_resource* resource, uint32_t id) {
    struct wl_resource* pointer = wl_resource_create(client, &wl_pointer_interface, wl_resource_get_version(resource), id);
    if (!pointer) {
        wl_client_post_no_memory(client);
        return;
    }
    wl_resource_set_implementation(pointer, &pointer_impl, NULL, unlink_resource);
    wl_list_insert(&pointers, wl_resource_get_link(pointer));
    if (focus && pointer_entered && same_client(pointer, focus)) {
        wl_pointer_send_enter(pointer, wl_display_next_serial(dnx_wl_display), focus->resource, surface_x(),
                              surface_y());
        pointer_frame(pointer);
    }
}

static void seat_get_keyboard(struct wl_client* client, struct wl_resource* resource, uint32_t id) {
    struct wl_resource* keyboard =
        wl_resource_create(client, &wl_keyboard_interface, wl_resource_get_version(resource), id);
    if (!keyboard) {
        wl_client_post_no_memory(client);
        return;
    }
    wl_resource_set_implementation(keyboard, &keyboard_impl, NULL, unlink_resource);
    wl_list_insert(&keyboards, wl_resource_get_link(keyboard));
    send_keymap(keyboard);
    /* Android repeats nothing (only the first key down is sent): clients repeat. */
    if (wl_resource_get_version(keyboard) >= WL_KEYBOARD_REPEAT_INFO_SINCE_VERSION)
        wl_keyboard_send_repeat_info(keyboard, 25, 600);
    if (focus && keyboard_entered && same_client(keyboard, focus)) {
        uint32_t serial = wl_display_next_serial(dnx_wl_display);
        struct wl_array keys;
        wl_array_init(&keys);
        wl_keyboard_send_enter(keyboard, serial, focus->resource, &keys);
        wl_array_release(&keys);
        send_modifiers(keyboard, serial);
    }
}

static void seat_get_touch(struct wl_client* client, struct wl_resource* resource, uint32_t id) {
    struct wl_resource* touch = wl_resource_create(client, &wl_touch_interface, wl_resource_get_version(resource), id);
    if (!touch) {
        wl_client_post_no_memory(client);
        return;
    }
    wl_resource_set_implementation(touch, &touch_impl, NULL, unlink_resource);
    wl_list_insert(&touches, wl_resource_get_link(touch));
}

static const struct wl_seat_interface seat_impl = {
    .get_pointer = seat_get_pointer,
    .get_keyboard = seat_get_keyboard,
    .get_touch = seat_get_touch,
    .release = release_resource,
};

static void seat_bind(struct wl_client* client, void* data, uint32_t version, uint32_t id) {
    (void) data;
    struct wl_resource* resource = wl_resource_create(client, &wl_seat_interface, (int) version, id);
    if (!resource) {
        wl_client_post_no_memory(client);
        return;
    }
    wl_resource_set_implementation(resource, &seat_impl, NULL, NULL);
    wl_seat_send_capabilities(resource,
                              WL_SEAT_CAPABILITY_POINTER | WL_SEAT_CAPABILITY_KEYBOARD | WL_SEAT_CAPABILITY_TOUCH);
    if (version >= WL_SEAT_NAME_SINCE_VERSION) wl_seat_send_name(resource, "seat0");
}

void dnx_wl_seat_init(struct wl_display* display, const char* path) {
    wl_list_init(&pointers);
    wl_list_init(&keyboards);
    wl_list_init(&touches);
    keymap_path = strdup(path);
    wl_global_create(display, &wl_seat_interface, 8, NULL, seat_bind);
}
