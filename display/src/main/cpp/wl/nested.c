/*
 * The compositor as a client of the nested KWin, on the connection KWin gives
 * its input method: guest/kwin-im passes it here through the socket
 * /tmp/.droidnux-im. On it:
 * - zwp_input_method_v1: KWin activates it when a text field gets the focus
 *   (text-input v1-v3 of its clients); Android's keyboard is shown and its
 *   text committed or composed (preedit) in that field. KWin's nested backend
 *   has no host text-input, so this is the only way in.
 * - kde_output_device_v2 and kde_output_management_v2: KWin's output scale,
 *   which sets the size of its output in surface coordinates (the desktop
 *   size divided by it), and the scale the app asks for.
 */
#define _GNU_SOURCE /* accept4 */
#include <android/log.h>
#include <errno.h>
#include <fcntl.h>
#include <math.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <sys/un.h>
#include <unistd.h>
#include <wayland-client-core.h>

#include "input-method-unstable-v1-client-protocol.h"
#include "kde-output-device-v2-client-protocol.h"
#include "kde-output-management-v2-client-protocol.h"
#include "private.h"
#include "wayland-client-protocol.h"

#define log(prio, ...) __android_log_print(ANDROID_LOG_##prio, "DroidNux.WL", __VA_ARGS__)

double dnx_wl_scale = 1.0;

static struct wl_display* nested;
static struct wl_event_source* nested_source;
static struct zwp_input_method_v1* input_method;
static struct zwp_input_method_context_v1* context;
static uint32_t context_serial;
static struct kde_output_device_v2* device;
static struct kde_output_management_v2* management;
static double device_scale, pending_device_scale;
static double requested_scale; /* by the app, 0 for none */
static void (*text_input_callback)(bool active);

/* -- Output scale ------------------------------------------------------------ */

static void config_applied(void* data, struct kde_output_configuration_v2* config) {
    kde_output_configuration_v2_destroy(config);
}

static void config_failed(void* data, struct kde_output_configuration_v2* config) {
    log(WARN, "KWin refused the output scale");
    kde_output_configuration_v2_destroy(config);
}

static void config_failure_reason(void* data, struct kde_output_configuration_v2* config, const char* reason) {
    log(WARN, "output configuration: %s", reason);
}

static const struct kde_output_configuration_v2_listener config_listener = {
    .applied = config_applied,
    .failed = config_failed,
    .failure_reason = config_failure_reason,
};

/* Asks KWin for the requested scale, once it told its current one. */
static void apply_requested_scale(void) {
    if (!management || !device || device_scale <= 0 || requested_scale <= 0) return;
    if (fabs(requested_scale - device_scale) < 0.004) return;
    struct kde_output_configuration_v2* config = kde_output_management_v2_create_configuration(management);
    kde_output_configuration_v2_add_listener(config, &config_listener, NULL);
    kde_output_configuration_v2_scale(config, device, wl_fixed_from_double(requested_scale));
    kde_output_configuration_v2_apply(config);
    log(INFO, "output scale %.2f requested", requested_scale);
}

static void device_geometry(void* data, struct kde_output_device_v2* d, int32_t x, int32_t y, int32_t pw,
                            int32_t ph, int32_t subpixel, const char* make, const char* model, int32_t transform) {}
static void device_mode(void* data, struct kde_output_device_v2* d, struct kde_output_device_mode_v2* mode) {}
static void device_string(void* data, struct kde_output_device_v2* d, const char* s) {}
static void device_int(void* data, struct kde_output_device_v2* d, int32_t v) {}
static void device_uint(void* data, struct kde_output_device_v2* d, uint32_t v) {}

static void device_scale_event(void* data, struct kde_output_device_v2* d, wl_fixed_t factor) {
    /* KWin keeps scales in 120ths (fractional-scale-v1), sent here in 256ths. */
    pending_device_scale = round(wl_fixed_to_double(factor) * 120) / 120;
}

static void device_done(void* data, struct kde_output_device_v2* d) {
    if (pending_device_scale <= 0 || pending_device_scale == device_scale) return;
    bool first = device_scale <= 0;
    device_scale = pending_device_scale;
    log(INFO, "KWin output scale %.3f", device_scale);
    dnx_wl_scale = device_scale;
    dnx_wl_surface_resized(); /* the output size in surface coordinates changed */
    if (first) apply_requested_scale();
}

/* Every event up to version 2, the one bound. */
static const struct kde_output_device_v2_listener device_listener = {
    .geometry = device_geometry,
    .current_mode = device_mode,
    .mode = device_mode,
    .done = device_done,
    .scale = device_scale_event,
    .edid = device_string,
    .enabled = device_int,
    .uuid = device_string,
    .serial_number = device_string,
    .eisa_id = device_string,
    .capabilities = device_uint,
    .overscan = device_uint,
    .vrr_policy = device_uint,
    .rgb_range = device_uint,
    .name = device_string,
};

void dnx_wl_nested_set_scale(double scale) {
    requested_scale = scale;
    apply_requested_scale();
    if (nested) wl_display_flush(nested);
}

/* -- Input method -------------------------------------------------------------- */

static void context_surrounding_text(void* data, struct zwp_input_method_context_v1* c, const char* text,
                                     uint32_t cursor, uint32_t anchor) {}
static void context_reset(void* data, struct zwp_input_method_context_v1* c) {}
static void context_content_type(void* data, struct zwp_input_method_context_v1* c, uint32_t hint,
                                 uint32_t purpose) {}
static void context_invoke_action(void* data, struct zwp_input_method_context_v1* c, uint32_t button,
                                  uint32_t index) {}
static void context_preferred_language(void* data, struct zwp_input_method_context_v1* c, const char* language) {}

static void context_commit_state(void* data, struct zwp_input_method_context_v1* c, uint32_t serial) {
    context_serial = serial;
}

static const struct zwp_input_method_context_v1_listener context_listener = {
    .surrounding_text = context_surrounding_text,
    .reset = context_reset,
    .content_type = context_content_type,
    .invoke_action = context_invoke_action,
    .commit_state = context_commit_state,
    .preferred_language = context_preferred_language,
};

static void set_context(struct zwp_input_method_context_v1* c) {
    bool was_active = context != NULL;
    if (context) zwp_input_method_context_v1_destroy(context);
    context = c;
    context_serial = 0;
    if (c) zwp_input_method_context_v1_add_listener(c, &context_listener, NULL);
    if (was_active != (c != NULL) && text_input_callback) text_input_callback(c != NULL);
}

static void im_activate(void* data, struct zwp_input_method_v1* im, struct zwp_input_method_context_v1* c) {
    set_context(c);
}

static void im_deactivate(void* data, struct zwp_input_method_v1* im, struct zwp_input_method_context_v1* c) {
    if (c == context) set_context(NULL);
    else zwp_input_method_context_v1_destroy(c);
}

static const struct zwp_input_method_v1_listener im_listener = {
    .activate = im_activate,
    .deactivate = im_deactivate,
};

void dnx_wl_nested_set_text_input_callback(void (*callback)(bool active)) {
    text_input_callback = callback;
}

void dnx_wl_nested_commit(const char* utf8) {
    if (!context) return;
    zwp_input_method_context_v1_commit_string(context, context_serial, utf8);
    wl_display_flush(nested);
}

void dnx_wl_nested_preedit(const char* utf8) {
    if (!context) return;
    zwp_input_method_context_v1_preedit_cursor(context, (int32_t) strlen(utf8));
    zwp_input_method_context_v1_preedit_string(context, context_serial, utf8, utf8);
    wl_display_flush(nested);
}

/* -- Connection ------------------------------------------------------------- */

static void registry_global(void* data, struct wl_registry* registry, uint32_t name, const char* interface,
                            uint32_t version) {
    if (strcmp(interface, zwp_input_method_v1_interface.name) == 0 && !input_method) {
        input_method = wl_registry_bind(registry, name, &zwp_input_method_v1_interface, 1);
        zwp_input_method_v1_add_listener(input_method, &im_listener, NULL);
    } else if (strcmp(interface, kde_output_device_v2_interface.name) == 0 && !device) {
        /* KWin's only output: the window on this compositor. */
        device = wl_registry_bind(registry, name, &kde_output_device_v2_interface, version < 2 ? version : 2);
        kde_output_device_v2_add_listener(device, &device_listener, NULL);
    } else if (strcmp(interface, kde_output_management_v2_interface.name) == 0 && !management) {
        management = wl_registry_bind(registry, name, &kde_output_management_v2_interface, 1);
    }
}

static void registry_global_remove(void* data, struct wl_registry* registry, uint32_t name) {}

static const struct wl_registry_listener registry_listener = {
    .global = registry_global,
    .global_remove = registry_global_remove,
};

static struct wl_registry* registry;

/* The scale learnt stays: the next KWin most likely has it too. */
static void disconnect(void) {
    if (!nested) return;
    set_context(NULL);
    wl_event_source_remove(nested_source);
    struct wl_proxy* proxies[] = {(struct wl_proxy*) input_method, (struct wl_proxy*) device,
                                  (struct wl_proxy*) management, (struct wl_proxy*) registry};
    for (size_t i = 0; i < sizeof(proxies) / sizeof(proxies[0]); i++) {
        if (proxies[i]) wl_proxy_destroy(proxies[i]);
    }
    wl_display_disconnect(nested);
    nested = NULL;
    input_method = NULL;
    device = NULL;
    management = NULL;
    registry = NULL;
    device_scale = pending_device_scale = 0;
}

static int on_nested(int fd, uint32_t mask, void* data) {
    if ((mask & (WL_EVENT_HANGUP | WL_EVENT_ERROR)) || wl_display_dispatch(nested) < 0) {
        log(INFO, "KWin's input method connection closed");
        disconnect();
        return 0;
    }
    wl_display_flush(nested);
    return 0;
}

/* A connection from guest/kwin-im: its one byte carries KWin's socket. */
static int on_im_socket(int listen_fd, uint32_t mask, void* data) {
    int conn = accept4(listen_fd, NULL, NULL, SOCK_CLOEXEC);
    if (conn < 0) return 0;
    char byte;
    struct iovec iov = {.iov_base = &byte, .iov_len = 1};
    union {
        char buf[CMSG_SPACE(sizeof(int))];
        struct cmsghdr align;
    } control;
    struct msghdr msg = {.msg_iov = &iov, .msg_iovlen = 1, .msg_control = control.buf,
                         .msg_controllen = sizeof(control.buf)};
    int fd = -1;
    if (recvmsg(conn, &msg, MSG_CMSG_CLOEXEC) == 1) {
        struct cmsghdr* cmsg = CMSG_FIRSTHDR(&msg);
        if (cmsg && cmsg->cmsg_level == SOL_SOCKET && cmsg->cmsg_type == SCM_RIGHTS) memcpy(&fd, CMSG_DATA(cmsg), sizeof(fd));
    }
    close(conn);
    if (fd < 0) return 0;

    disconnect(); /* KWin started a new input method */
    nested = wl_display_connect_to_fd(fd);
    if (!nested) {
        log(ERROR, "cannot use KWin's input method connection");
        return 0;
    }
    registry = wl_display_get_registry(nested);
    wl_registry_add_listener(registry, &registry_listener, NULL);
    wl_display_flush(nested);
    nested_source = wl_event_loop_add_fd(wl_display_get_event_loop(dnx_wl_display), wl_display_get_fd(nested),
                                         WL_EVENT_READABLE, on_nested, NULL);
    log(INFO, "connected to KWin as its input method");
    return 0;
}

void dnx_wl_nested_init(struct wl_display* display, const char* socket_path) {
    struct sockaddr_un addr = {.sun_family = AF_UNIX};
    if (strlen(socket_path) >= sizeof(addr.sun_path)) return;
    strcpy(addr.sun_path, socket_path);
    int fd = socket(AF_UNIX, SOCK_STREAM | SOCK_CLOEXEC | SOCK_NONBLOCK, 0);
    unlink(socket_path);
    if (fd < 0 || bind(fd, (struct sockaddr*) &addr, sizeof(addr)) != 0 || listen(fd, 4) != 0) {
        log(ERROR, "%s: %s", socket_path, strerror(errno));
        if (fd >= 0) close(fd);
        return;
    }
    wl_event_loop_add_fd(wl_display_get_event_loop(display), fd, WL_EVENT_READABLE, on_im_socket, NULL);
}
