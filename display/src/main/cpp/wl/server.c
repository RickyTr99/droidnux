/*
 * Compositor thread, socket, wl_output and frame clock of the DroidNux
 * Wayland front-end, and input from Android through a pipe (as in
 * x11/server.c).
 */
#define _GNU_SOURCE /* pipe2, asprintf */
#include <android/log.h>
#include <errno.h>
#include <fcntl.h>
#include <pthread.h>
#include <stdatomic.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/eventfd.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <sys/un.h>
#include <time.h>
#include <unistd.h>
#include <wayland-server-protocol.h>

#include "allocator.h"
#include "audio_output.h"
#include "dnx_wl.h"
#include "private.h"
#include "video_decoder.h"

#define log(prio, ...) __android_log_print(ANDROID_LOG_##prio, "DroidNux.WL", __VA_ARGS__)

struct wl_display* dnx_wl_display;
int dnx_wl_width = 1280, dnx_wl_height = 800;

enum event_type {
    EVENT_KEY = 1,
    EVENT_TEXT,
    EVENT_POINTER,
    EVENT_POINTER_RELATIVE,
    EVENT_BUTTON,
    EVENT_SCROLL,
    EVENT_RESIZE,
    EVENT_REDRAW,
    EVENT_BUFFER,
};

struct event {
    uint32_t type;
    int32_t a, b; /* keycode/down, codepoint, button/down, width/height, released */
    float x, y;   /* pointer position or delta, scroll amount */
    void* data;   /* the buffer of EVENT_BUFFER */
};

static int event_pipe[2] = {-1, -1};
static int vsync_fd = -1;
static atomic_bool vsync_requested;
static atomic_bool running;
static pthread_t server_thread;

uint32_t dnx_wl_now_ms(void) {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (uint32_t) (ts.tv_sec * 1000 + ts.tv_nsec / 1000000);
}

/* -- Frame clock ---------------------------------------------------------- */

/* Vsync thread: wake the compositor thread. */
static void on_vsync(int64_t frame_time_ns, void* data) {
    (void) frame_time_ns;
    (void) data;
    eventfd_write(vsync_fd, 1);
}

void dnx_wl_request_frame(void) {
    if (!atomic_exchange(&vsync_requested, true)) dnx_presenter_request_vsync();
}

static int on_vsync_fd(int fd, uint32_t mask, void* data) {
    (void) mask;
    (void) data;
    eventfd_t count;
    eventfd_read(fd, &count);
    atomic_store(&vsync_requested, false);
    dnx_wl_surface_on_frame();
    return 0;
}

/* -- wl_output ------------------------------------------------------------ */

static void output_release(struct wl_client* client, struct wl_resource* resource) {
    (void) client;
    wl_resource_destroy(resource);
}

static const struct wl_output_interface output_impl = {.release = output_release};

static void send_output_state(struct wl_resource* output) {
    wl_output_send_geometry(output, 0, 0, 0, 0, WL_OUTPUT_SUBPIXEL_UNKNOWN, "DroidNux", "Android",
                            WL_OUTPUT_TRANSFORM_NORMAL);
    wl_output_send_mode(output, WL_OUTPUT_MODE_CURRENT | WL_OUTPUT_MODE_PREFERRED, dnx_wl_width, dnx_wl_height,
                        dnx_presenter_refresh_rate() * 1000);
    int version = wl_resource_get_version(output);
    if (version >= WL_OUTPUT_SCALE_SINCE_VERSION) wl_output_send_scale(output, 1);
    if (version >= WL_OUTPUT_NAME_SINCE_VERSION) wl_output_send_name(output, "DROIDNUX-1");
    if (version >= WL_OUTPUT_DESCRIPTION_SINCE_VERSION) wl_output_send_description(output, "DroidNux desktop");
    if (version >= WL_OUTPUT_DONE_SINCE_VERSION) wl_output_send_done(output);
}

static struct wl_list outputs; /* bound wl_output resources */

static void output_unbind(struct wl_resource* resource) {
    wl_list_remove(wl_resource_get_link(resource));
}

static void output_bind(struct wl_client* client, void* data, uint32_t version, uint32_t id) {
    (void) data;
    struct wl_resource* resource = wl_resource_create(client, &wl_output_interface, (int) version, id);
    if (!resource) {
        wl_client_post_no_memory(client);
        return;
    }
    wl_resource_set_implementation(resource, &output_impl, NULL, output_unbind);
    wl_list_insert(&outputs, wl_resource_get_link(resource));
    send_output_state(resource);
}

/* -- Events from Android (any thread) --------------------------------------- */

static void send_event(struct event e) {
    if (event_pipe[1] < 0) return;
    /* Atomic small writes; dropping input beats blocking the UI thread. */
    if (write(event_pipe[1], &e, sizeof(e)) != sizeof(e)) log(WARN, "input event dropped");
}

void dnx_wl_key(int evdev_keycode, bool down) {
    send_event((struct event) {.type = EVENT_KEY, .a = evdev_keycode, .b = down});
}

void dnx_wl_text(uint32_t codepoint) {
    send_event((struct event) {.type = EVENT_TEXT, .a = (int32_t) codepoint});
}

void dnx_wl_pointer_move(float x, float y) {
    send_event((struct event) {.type = EVENT_POINTER, .x = x, .y = y});
}

void dnx_wl_pointer_move_relative(float dx, float dy) {
    send_event((struct event) {.type = EVENT_POINTER_RELATIVE, .x = dx, .y = dy});
}

void dnx_wl_button(int button, bool down) {
    send_event((struct event) {.type = EVENT_BUTTON, .a = button, .b = down});
}

void dnx_wl_scroll(float dx, float dy) {
    send_event((struct event) {.type = EVENT_SCROLL, .x = dx, .y = dy});
}

void dnx_wl_resize(int width, int height) {
    send_event((struct event) {.type = EVENT_RESIZE, .a = width, .b = height});
}

void dnx_wl_redraw(void) {
    send_event((struct event) {.type = EVENT_REDRAW});
}

void dnx_wl_post_buffer_event(struct dnx_wl_dmabuf* buffer, bool released) {
    /* Never dropped: the buffer is freed on its last RELEASED. */
    struct event e = {.type = EVENT_BUFFER, .a = released, .data = buffer};
    while (write(event_pipe[1], &e, sizeof(e)) != sizeof(e)) {
        if (errno != EAGAIN && errno != EINTR) {
            log(ERROR, "buffer event lost: %s", strerror(errno));
            return;
        }
        usleep(1000);
    }
}

/* -- Events on the compositor thread -------------------------------------- */

static void handle_event(const struct event* e) {
    switch (e->type) {
    case EVENT_KEY:
        dnx_wl_seat_key(e->a, e->b);
        break;
    case EVENT_TEXT:
        /* shortcut: no text-input-v3 yet, soft keyboard text is dropped; Fase 4b step 4. */
        break;
    case EVENT_POINTER:
        dnx_wl_seat_pointer(e->x, e->y, false);
        break;
    case EVENT_POINTER_RELATIVE:
        dnx_wl_seat_pointer(e->x, e->y, true);
        break;
    case EVENT_BUTTON:
        dnx_wl_seat_button(e->a, e->b);
        break;
    case EVENT_SCROLL:
        dnx_wl_seat_scroll(e->x, e->y);
        break;
    case EVENT_RESIZE: {
        if (e->a <= 0 || e->b <= 0 || (e->a == dnx_wl_width && e->b == dnx_wl_height)) break;
        dnx_wl_width = e->a;
        dnx_wl_height = e->b;
        struct wl_resource* output;
        wl_resource_for_each(output, &outputs) send_output_state(output);
        dnx_wl_surface_resized();
        break;
    }
    case EVENT_REDRAW:
        dnx_wl_surface_redraw();
        break;
    case EVENT_BUFFER:
        dnx_wl_dmabuf_on_presenter_event(e->data, e->a);
        break;
    }
}

static int on_events(int fd, uint32_t mask, void* data) {
    (void) mask;
    (void) data;
    struct event events[32];
    for (;;) {
        ssize_t n = read(fd, events, sizeof(events));
        if (n <= 0) break;
        for (ssize_t i = 0; i < n / (ssize_t) sizeof(struct event); i++) handle_event(&events[i]);
        if (n < (ssize_t) sizeof(events)) break;
    }
    return 0;
}

/* -- Startup -------------------------------------------------------------- */

static void wayland_log(const char* format, va_list args) {
    __android_log_vprint(ANDROID_LOG_WARN, "DroidNux.WL", format, args);
}

/* The listening socket at `path`, replacing a stale one; -1 on errors. */
static int listen_socket(const char* path) {
    struct sockaddr_un addr = {.sun_family = AF_UNIX};
    if (strlen(path) >= sizeof(addr.sun_path)) {
        log(ERROR, "socket path too long: %s", path);
        return -1;
    }
    strcpy(addr.sun_path, path);
    int fd = socket(AF_UNIX, SOCK_STREAM | SOCK_CLOEXEC, 0);
    if (fd < 0) return -1;
    unlink(path);
    if (bind(fd, (struct sockaddr*) &addr, sizeof(addr)) != 0 || listen(fd, 128) != 0) {
        log(ERROR, "%s: %s", path, strerror(errno));
        close(fd);
        return -1;
    }
    return fd;
}

static void* server_main(void* unused) {
    (void) unused;
    pthread_setname_np(pthread_self(), "dnx-wayland");
    struct wl_event_loop* loop = wl_display_get_event_loop(dnx_wl_display);
    while (running) {
        wl_display_flush_clients(dnx_wl_display);
        if (wl_event_loop_dispatch(loop, -1) < 0 && errno != EINTR) {
            log(ERROR, "event loop: %s", strerror(errno));
            break;
        }
    }
    running = false;
    return NULL;
}

bool dnx_wl_start(const dnx_wl_config* config) {
    if (atomic_exchange(&running, true)) return false;

    wl_log_set_handler_server(wayland_log);
    dnx_wl_width = config->width;
    dnx_wl_height = config->height;

    /* GPU clients' swapchain images (Mesa patch 0003), video decoding and
     * sound, as for the X11 front-end. */
    char* path = NULL;
    asprintf(&path, "%s/.droidnux-ahb", config->tmp_dir);
    dnx_allocator_start(path);
    free(path);
    asprintf(&path, "%s/.droidnux-video", config->tmp_dir);
    dnx_video_decoder_start(path);
    free(path);
    asprintf(&path, "%s/.droidnux-audio", config->tmp_dir);
    dnx_audio_output_start(path);
    free(path);

    if (pipe2(event_pipe, O_CLOEXEC | O_NONBLOCK) != 0 ||
        (vsync_fd = eventfd(0, EFD_CLOEXEC | EFD_NONBLOCK)) < 0 || !(dnx_wl_display = wl_display_create())) {
        log(ERROR, "setup: %s", strerror(errno));
        running = false;
        return false;
    }

    asprintf(&path, "%s/wayland-%d", config->tmp_dir, config->display);
    int socket_fd = listen_socket(path);
    if (socket_fd < 0 || wl_display_add_socket_fd(dnx_wl_display, socket_fd) != 0) {
        if (socket_fd >= 0) close(socket_fd);
        free(path);
        wl_display_destroy(dnx_wl_display);
        dnx_wl_display = NULL;
        running = false;
        return false;
    }

    struct wl_event_loop* loop = wl_display_get_event_loop(dnx_wl_display);
    wl_event_loop_add_fd(loop, event_pipe[0], WL_EVENT_READABLE, on_events, NULL);
    wl_event_loop_add_fd(loop, vsync_fd, WL_EVENT_READABLE, on_vsync_fd, NULL);
    dnx_presenter_set_vsync_callback(on_vsync, NULL);

    wl_display_init_shm(dnx_wl_display); /* ARGB8888 and XRGB8888: the presenter's BGRX */
    wl_list_init(&outputs);
    wl_global_create(dnx_wl_display, &wl_output_interface, 4, NULL, output_bind);
    dnx_wl_surface_init(dnx_wl_display);
    dnx_wl_dmabuf_init(dnx_wl_display);
    char* keymap = NULL;
    asprintf(&keymap, "%s/.droidnux-keymap.xkb", config->tmp_dir);
    dnx_wl_seat_init(dnx_wl_display, keymap);
    free(keymap);

    log(INFO, "starting compositor on %s, %dx%d", path, config->width, config->height);
    free(path);
    if (pthread_create(&server_thread, NULL, server_main, NULL) != 0) {
        running = false;
        return false;
    }
    return true;
}

bool dnx_wl_running(void) {
    return running;
}
