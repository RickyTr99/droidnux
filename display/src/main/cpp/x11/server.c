/*
 * X server thread and input for the DroidNux X11 front-end.
 *
 * Derived from Termux:X11 (lorie/cmdentrypoint.cpp, GPL-3.0): server startup
 * and the translation of input events into X events. The activity-to-server
 * socket is replaced by a pipe inside the app process.
 */
#ifdef HAVE_DIX_CONFIG_H
#include <dix-config.h>
#endif

#include <android/log.h>
#include <errno.h>
#include <fcntl.h>
#include <math.h>
#include <pthread.h>
#include <stdatomic.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <unistd.h>

#include "allocator.h"
#include "audio_output.h"
#include "dix.h"
#include "globals.h"
#include "input.h"
#include "inpututils.h"
#include "mi.h"
#include "opaque.h"
#include "os.h"
#include "scrnintstr.h"
#include "video_decoder.h"
#include "xkbsrv.h"

#include "dnx_x11.h"
#include "private.h"

#define log(prio, ...) __android_log_print(ANDROID_LOG_##prio, "DroidNux.X11", __VA_ARGS__)

extern DeviceIntPtr lorieMouse, lorieKeyboard;
extern ScreenPtr pScreenPtr;
extern int ucs2keysym(long ucs);
extern void lorieKeysymKeyboardEvent(KeySym keysym, int down);

/* Socket location, read by the patched Xtrans. */
char* xtrans_unix_path_x11 = NULL;
char* xtrans_unix_dir_x11 = NULL;

int dnx_initial_width = 1280, dnx_initial_height = 800;

enum event_type {
    EVENT_KEY = 1,
    EVENT_TEXT,
    EVENT_POINTER,
    EVENT_POINTER_RELATIVE,
    EVENT_BUTTON,
    EVENT_SCROLL,
    EVENT_RESIZE,
    EVENT_REDRAW,
};

struct event {
    uint32_t type;
    int32_t a, b;   /* keycode/down, codepoint, button/down, width/height */
    float x, y;     /* pointer position or delta, scroll amount */
};

static int event_pipe[2] = {-1, -1};
static atomic_bool running = false;
static pthread_t server_thread;
static char** server_argv;
static int server_argc;

/* -- Startup -------------------------------------------------------------- */

static void* server_main(void* unused) {
    (void) unused;
    pthread_setname_np(pthread_self(), "dnx-xserver");
    int status = dix_main(server_argc, server_argv, (char*[]) {NULL});
    log(ERROR, "X server exited with %d", status);
    running = false;
    return NULL;
}

bool dnx_x11_start(const dnx_x11_config* config) {
    static char display[16], dpi[16];
    static char* argv[24];
    int argc = 0;

    if (atomic_exchange(&running, true)) return false;

    if (pipe2(event_pipe, O_CLOEXEC | O_NONBLOCK) != 0) {
        log(ERROR, "pipe: %s", strerror(errno));
        running = false;
        return false;
    }

    /* The X server finds its socket, keymaps and fonts in the guest's rootfs. */
    setenv("TMPDIR", config->tmp_dir, 1);
    free(xtrans_unix_dir_x11);
    free(xtrans_unix_path_x11);
    asprintf(&xtrans_unix_dir_x11, "%s/.X11-unix/", config->tmp_dir);
    asprintf(&xtrans_unix_path_x11, "%s/.X11-unix/X", config->tmp_dir);
    mkdir(xtrans_unix_dir_x11, 01777);
    chmod(xtrans_unix_dir_x11, 01777);
    chdir(config->tmp_dir);

    /* GPU clients' swapchain images (Mesa patch 0003), as /tmp/.droidnux-ahb. */
    char* allocator_path = NULL;
    asprintf(&allocator_path, "%s/.droidnux-ahb", config->tmp_dir);
    dnx_allocator_start(allocator_path);
    free(allocator_path);
    /* Hardware video decoding for DroidNux's VA-API driver (guest/va). */
    char* video_path = NULL;
    asprintf(&video_path, "%s/.droidnux-video", config->tmp_dir);
    dnx_video_decoder_start(video_path);
    free(video_path);
    /* Sound from the container's PipeWire (guest/pipewire), played with AAudio. */
    char* audio_path = NULL;
    asprintf(&audio_path, "%s/.droidnux-audio", config->tmp_dir);
    dnx_audio_output_start(audio_path);
    free(audio_path);

    XkbBaseDirectory = strdup(config->xkb_dir);
    if (config->font_dir && access(config->font_dir, F_OK) == 0) {
        char* path = NULL;
        asprintf(&path, "%1$s/misc,%1$s/TTF,%1$s/OTF,%1$s/Type1,%1$s/100dpi,%1$s/75dpi,built-ins", config->font_dir);
        defaultFontPath = path;
    } else {
        defaultFontPath = "built-ins";
    }

    dnx_initial_width = config->width;
    dnx_initial_height = config->height;

    snprintf(display, sizeof(display), ":%d", config->display);
    snprintf(dpi, sizeof(dpi), "%d", config->dpi > 0 ? config->dpi : 96);
    argv[argc++] = "Xdroidnux";
    argv[argc++] = display;
    /* Only the filesystem socket: TCP is useless here and the abstract
     * ("local") socket would be reachable by other apps. */
    argv[argc++] = "-nolisten";
    argv[argc++] = "tcp";
    argv[argc++] = "-nolisten";
    argv[argc++] = "local";
    argv[argc++] = "-listen";
    argv[argc++] = "unix";
    /* Keep running when the last client disconnects (e.g. between sessions). */
    argv[argc++] = "-noreset";
    /* The socket lives in the app's private storage: only the app and its
     * container can reach it, so host-based access control adds nothing. */
    argv[argc++] = "-ac";
    argv[argc++] = "-dpi";
    argv[argc++] = dpi;
    argv[argc] = NULL;
    server_argc = argc;
    server_argv = argv;

    log(INFO, "starting X server on %s, %dx%d, socket dir %s", display, config->width, config->height,
        xtrans_unix_dir_x11);
    if (pthread_create(&server_thread, NULL, server_main, NULL) != 0) {
        running = false;
        return false;
    }
    return true;
}

bool dnx_x11_running(void) {
    return running;
}

/* -- Events from Android (any thread) --------------------------------------- */

static void send_event(struct event e) {
    if (event_pipe[1] < 0) return;
    /* Small fixed-size writes to a pipe are atomic. If the server is so far
     * behind that the pipe is full, dropping input is better than blocking
     * the UI thread. */
    if (write(event_pipe[1], &e, sizeof(e)) != sizeof(e)) log(WARN, "input event dropped");
}

void dnx_x11_key(int evdev_keycode, bool down) {
    send_event((struct event) {.type = EVENT_KEY, .a = evdev_keycode, .b = down});
}

void dnx_x11_text(uint32_t codepoint) {
    send_event((struct event) {.type = EVENT_TEXT, .a = (int32_t) codepoint});
}

void dnx_x11_pointer_move(float x, float y) {
    send_event((struct event) {.type = EVENT_POINTER, .x = x, .y = y});
}

void dnx_x11_pointer_move_relative(float dx, float dy) {
    send_event((struct event) {.type = EVENT_POINTER_RELATIVE, .x = dx, .y = dy});
}

void dnx_x11_button(int button, bool down) {
    send_event((struct event) {.type = EVENT_BUTTON, .a = button, .b = down});
}

void dnx_x11_scroll(float dx, float dy) {
    send_event((struct event) {.type = EVENT_SCROLL, .x = dx, .y = dy});
}

void dnx_x11_resize(int width, int height) {
    send_event((struct event) {.type = EVENT_RESIZE, .a = width, .b = height});
}

void dnx_x11_redraw(void) {
    send_event((struct event) {.type = EVENT_REDRAW});
}

/* -- Events on the server thread ------------------------------------------ */

static void handle_event(const struct event* e) {
    ValuatorMask mask;
    valuator_mask_zero(&mask);

    switch (e->type) {
    case EVENT_KEY:
        /* X keycodes are evdev keycodes + 8. */
        QueueKeyboardEvents(lorieKeyboard, e->b ? KeyPress : KeyRelease, e->a + 8);
        break;
    case EVENT_TEXT: {
        KeySym keysym = ucs2keysym(e->a);
        if (keysym != NoSymbol) {
            lorieKeysymKeyboardEvent(keysym, TRUE);
            lorieKeysymKeyboardEvent(keysym, FALSE);
        }
        break;
    }
    case EVENT_POINTER:
        if (!pScreenPtr) break;
        valuator_mask_set_double(&mask, 0, fmaxf(0, fminf(e->x, pScreenPtr->width - 1)));
        valuator_mask_set_double(&mask, 1, fmaxf(0, fminf(e->y, pScreenPtr->height - 1)));
        QueuePointerEvents(lorieMouse, MotionNotify, 0, POINTER_ABSOLUTE | POINTER_SCREEN | POINTER_NORAW, &mask);
        break;
    case EVENT_POINTER_RELATIVE:
        valuator_mask_set_double(&mask, 0, e->x);
        valuator_mask_set_double(&mask, 1, e->y);
        QueuePointerEvents(lorieMouse, MotionNotify, 0, POINTER_RELATIVE | POINTER_ACCELERATE, &mask);
        break;
    case EVENT_BUTTON:
        QueuePointerEvents(lorieMouse, e->b ? ButtonPress : ButtonRelease, e->a, POINTER_RELATIVE, NULL);
        break;
    case EVENT_SCROLL:
        /* Valuators 2 and 3 are the smooth-scroll axes set up in InitInput.c. */
        if (e->x != 0) valuator_mask_set_double(&mask, 2, e->x);
        if (e->y != 0) valuator_mask_set_double(&mask, 3, e->y);
        if (valuator_mask_num_valuators(&mask))
            QueuePointerEvents(lorieMouse, MotionNotify, 0, POINTER_RELATIVE, &mask);
        break;
    case EVENT_RESIZE:
        dnx_output_resize(e->a, e->b);
        break;
    case EVENT_REDRAW:
        dnx_output_force_redraw();
        break;
    }
}

static void on_events(int fd, int ready, void* data) {
    struct event events[32];
    (void) ready;
    (void) data;
    for (;;) {
        ssize_t n = read(fd, events, sizeof(events));
        if (n <= 0) break;
        input_lock();
        for (ssize_t i = 0; i < n / (ssize_t) sizeof(struct event); i++) handle_event(&events[i]);
        input_unlock();
        if (n < (ssize_t) sizeof(events)) break;
    }
}

void dnx_server_ready(void) {
    static bool registered = false;
    if (registered) return;
    registered = true;
    /* Handled on the server's main thread, which owns XKB state and RandR. */
    SetNotifyFd(event_pipe[0], on_events, X_NOTIFY_READ, NULL);
    log(INFO, "X server ready");
}
