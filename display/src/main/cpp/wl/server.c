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
#include <semaphore.h>
#include <stdatomic.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/eventfd.h>
#include <sys/file.h>
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
    EVENT_COMMIT_TEXT,
    EVENT_PREEDIT_TEXT,
    EVENT_POINTER,
    EVENT_POINTER_RELATIVE,
    EVENT_BUTTON,
    EVENT_SCROLL,
    EVENT_RESIZE,
    EVENT_REDRAW,
    EVENT_BUFFER,
    EVENT_TOUCH_DOWN,
    EVENT_TOUCH_MOTION,
    EVENT_TOUCH_UP,
    EVENT_TOUCH_FRAME,
    EVENT_TOUCH_CANCEL,
    EVENT_SCALE,
    EVENT_SNAPSHOT,
};

struct event {
    uint32_t type;
    int32_t a, b; /* keycode/down, button/down, width/height, released, touch id */
    float x, y;   /* pointer or touch position, delta, scroll amount, scale */
    void* data;   /* the buffer of EVENT_BUFFER, the text (malloc) of EVENT_*_TEXT, a snapshot */
};

/* Shared by the caller and the compositor thread; the last one to let go frees it. */
struct snapshot {
    uint32_t* pixels;
    int width, height;
    bool ok;
    sem_t done;
    atomic_int refs;
};

static void snapshot_unref(struct snapshot* s) {
    if (atomic_fetch_sub(&s->refs, 1) == 1) {
        sem_destroy(&s->done);
        free(s->pixels);
        free(s);
    }
}

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
static atomic_llong vsync_ns;

static void on_vsync(int64_t frame_time_ns, void* data) {
    (void) data;
    atomic_store(&vsync_ns, frame_time_ns);
    eventfd_write(vsync_fd, 1);
}

/* Binder thread: Android latched our last frame. */
static int latch_fd = -1;

static void on_latch(void* data) {
    (void) data;
    eventfd_write(latch_fd, 1);
}

static int on_latch_fd(int fd, uint32_t mask, void* data) {
    (void) mask;
    (void) data;
    eventfd_t count;
    eventfd_read(fd, &count);
    dnx_wl_surface_on_latch();
    return 0;
}

int64_t dnx_wl_vsync_ns(void) {
    return atomic_load(&vsync_ns);
}

void dnx_wl_request_frame(void) {
    /* Without a window (the app in the background) no frame reaches the screen:
     * no vsync, so KWin and its clients wait instead of drawing for nothing.
     * The redraw when the window is back starts the frames again. */
    if (!dnx_presenter_attached()) return;
    if (!atomic_exchange(&vsync_requested, true)) dnx_presenter_request_vsync();
}

static void send_output_state(struct wl_resource* output);
static struct wl_list outputs; /* bound wl_output resources */
static int announced_refresh_rate;

static int on_vsync_fd(int fd, uint32_t mask, void* data) {
    (void) mask;
    (void) data;
    eventfd_t count;
    eventfd_read(fd, &count);
    atomic_store(&vsync_requested, false);
    /* The presenter learns the display's rate after the first frames:
     * nested compositors pace themselves by the announced mode. */
    if (dnx_presenter_refresh_rate() != announced_refresh_rate) {
        struct wl_resource* output;
        wl_resource_for_each(output, &outputs) send_output_state(output);
    }
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
    announced_refresh_rate = dnx_presenter_refresh_rate();
    wl_output_send_mode(output, WL_OUTPUT_MODE_CURRENT | WL_OUTPUT_MODE_PREFERRED, dnx_wl_width, dnx_wl_height,
                        announced_refresh_rate * 1000);
    int version = wl_resource_get_version(output);
    if (version >= WL_OUTPUT_SCALE_SINCE_VERSION) wl_output_send_scale(output, 1);
    if (version >= WL_OUTPUT_NAME_SINCE_VERSION) wl_output_send_name(output, "DROIDNUX-1");
    if (version >= WL_OUTPUT_DESCRIPTION_SINCE_VERSION) wl_output_send_description(output, "DroidNux desktop");
    if (version >= WL_OUTPUT_DONE_SINCE_VERSION) wl_output_send_done(output);
}

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

static void send_text(uint32_t type, const char* utf8) {
    struct event e = {.type = type, .data = strdup(utf8)};
    if (event_pipe[1] < 0 || !e.data) return;
    if (write(event_pipe[1], &e, sizeof(e)) != sizeof(e)) {
        log(WARN, "text dropped");
        free(e.data);
    }
}

void dnx_wl_commit_text(const char* utf8) {
    send_text(EVENT_COMMIT_TEXT, utf8);
}

void dnx_wl_preedit_text(const char* utf8) {
    send_text(EVENT_PREEDIT_TEXT, utf8);
}

void dnx_wl_set_text_input_callback(void (*callback)(bool active)) {
    dnx_wl_nested_set_text_input_callback(callback);
}

void dnx_wl_set_scale(double scale) {
    send_event((struct event) {.type = EVENT_SCALE, .x = (float) scale});
}

void dnx_wl_touch_down(int id, float x, float y) {
    send_event((struct event) {.type = EVENT_TOUCH_DOWN, .a = id, .x = x, .y = y});
}

void dnx_wl_touch_motion(int id, float x, float y) {
    send_event((struct event) {.type = EVENT_TOUCH_MOTION, .a = id, .x = x, .y = y});
}

void dnx_wl_touch_up(int id) {
    send_event((struct event) {.type = EVENT_TOUCH_UP, .a = id});
}

void dnx_wl_touch_frame(void) {
    send_event((struct event) {.type = EVENT_TOUCH_FRAME});
}

void dnx_wl_touch_cancel(void) {
    send_event((struct event) {.type = EVENT_TOUCH_CANCEL});
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

bool dnx_wl_snapshot(uint32_t* pixels, int width, int height) {
    if (!running || width <= 0 || height <= 0) return false;
    struct snapshot* s = calloc(1, sizeof(*s));
    if (!s) return false;
    s->pixels = calloc((size_t) width * height, sizeof(uint32_t));
    s->width = width;
    s->height = height;
    sem_init(&s->done, 0, 0);
    atomic_init(&s->refs, 2);
    if (!s->pixels || event_pipe[1] < 0 ||
        write(event_pipe[1], &(struct event) {.type = EVENT_SNAPSHOT, .data = s}, sizeof(struct event)) !=
            sizeof(struct event)) {
        atomic_store(&s->refs, 1);
        snapshot_unref(s);
        return false;
    }
    struct timespec deadline;
    clock_gettime(CLOCK_REALTIME, &deadline);
    deadline.tv_nsec += 500000000L;
    if (deadline.tv_nsec >= 1000000000L) {
        deadline.tv_sec++;
        deadline.tv_nsec -= 1000000000L;
    }
    bool ok = sem_timedwait(&s->done, &deadline) == 0 && s->ok;
    if (ok) memcpy(pixels, s->pixels, (size_t) width * height * sizeof(uint32_t));
    snapshot_unref(s);
    return ok;
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
    case EVENT_COMMIT_TEXT:
        dnx_wl_nested_commit(e->data);
        free(e->data);
        break;
    case EVENT_PREEDIT_TEXT:
        dnx_wl_nested_preedit(e->data);
        free(e->data);
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
    case EVENT_TOUCH_DOWN:
        dnx_wl_seat_touch_down(e->a, e->x, e->y);
        break;
    case EVENT_TOUCH_MOTION:
        dnx_wl_seat_touch_motion(e->a, e->x, e->y);
        break;
    case EVENT_TOUCH_UP:
        dnx_wl_seat_touch_up(e->a);
        break;
    case EVENT_TOUCH_FRAME:
        dnx_wl_seat_touch_frame();
        break;
    case EVENT_TOUCH_CANCEL:
        dnx_wl_seat_touch_cancel();
        break;
    case EVENT_SCALE:
        dnx_wl_nested_set_scale(e->x);
        break;
    case EVENT_SNAPSHOT: {
        struct snapshot* s = e->data;
        s->ok = dnx_wl_surface_snapshot(s->pixels, s->width, s->height);
        sem_post(&s->done);
        snapshot_unref(s);
        break;
    }
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
    /* libwayland's lock next to the socket: compositors in the guest
     * (kwin_wayland_wrapper) that find it free take the name and replace the
     * socket. Held until the process exits. */
    char lock_path[sizeof(addr.sun_path) + 5];
    snprintf(lock_path, sizeof(lock_path), "%s.lock", path);
    int lock_fd = open(lock_path, O_RDWR | O_CREAT | O_CLOEXEC, 0660);
    if (lock_fd < 0 || flock(lock_fd, LOCK_EX | LOCK_NB) != 0) {
        log(ERROR, "%s: %s", lock_path, strerror(errno));
        if (lock_fd >= 0) close(lock_fd);
        return -1;
    }
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
        (vsync_fd = eventfd(0, EFD_CLOEXEC | EFD_NONBLOCK)) < 0 ||
        (latch_fd = eventfd(0, EFD_CLOEXEC | EFD_NONBLOCK)) < 0 || !(dnx_wl_display = wl_display_create())) {
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
    wl_event_loop_add_fd(loop, latch_fd, WL_EVENT_READABLE, on_latch_fd, NULL);
    dnx_presenter_set_latch_callback(on_latch, NULL);

    wl_display_init_shm(dnx_wl_display); /* ARGB8888 and XRGB8888: the presenter's BGRX */
    wl_list_init(&outputs);
    wl_global_create(dnx_wl_display, &wl_output_interface, 4, NULL, output_bind);
    dnx_wl_surface_init(dnx_wl_display);
    dnx_wl_dmabuf_init(dnx_wl_display);
    char* keymap = NULL;
    asprintf(&keymap, "%s/.droidnux-keymap.xkb", config->tmp_dir);
    dnx_wl_seat_init(dnx_wl_display, keymap);
    free(keymap);
    char* im_socket = NULL;
    asprintf(&im_socket, "%s/.droidnux-im", config->tmp_dir);
    dnx_wl_nested_init(dnx_wl_display, im_socket);
    free(im_socket);

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
