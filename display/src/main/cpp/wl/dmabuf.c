/*
 * linux-dmabuf (version 3) for the DroidNux Wayland front-end: single-plane
 * linear BGRA buffers, with version 4 feedback naming the GPU. Those the app allocated (Mesa patch 0003, found with
 * dnx_allocator_find) go to Android's compositor as they are; other ones are
 * copied from a CPU mapping, as the X11 front-end does for DRI3 pixmaps.
 */
#include <android/log.h>
#include <errno.h>
#include <fcntl.h>
#include <linux/dma-buf.h>
#include <linux/memfd.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <sys/syscall.h>
#include <unistd.h>
#include <wayland-server-protocol.h>

#include "allocator.h"
#include "linux-dmabuf-v1-protocol.h"
#include "private.h"

#define log(prio, ...) __android_log_print(ANDROID_LOG_##prio, "DroidNux.WL", __VA_ARGS__)

#define DRM_FORMAT_ARGB8888 0x34325241 /* fourcc AR24 */
#define DRM_FORMAT_XRGB8888 0x34325258 /* fourcc XR24 */
#define DRM_FORMAT_MOD_LINEAR 0ull

struct dnx_wl_dmabuf {
    struct wl_resource* resource; /* NULL once the client destroyed it */
    int fd;
    uint32_t offset, stride, format;
    int32_t width, height;
    AHardwareBuffer* hardware_buffer; /* when the app allocated it */
    void* map;                        /* CPU mapping for copies, lazily */
    size_t map_size;
    int uses;  /* surfaces holding it as their current buffer */
    int holds; /* presenter references (until DNX_BUFFER_RELEASED) */
};

static void dmabuf_free_if_unused(struct dnx_wl_dmabuf* b) {
    if (b->resource || b->holds > 0) return;
    if (b->map) munmap(b->map, b->map_size);
    if (b->hardware_buffer) AHardwareBuffer_release(b->hardware_buffer);
    close(b->fd);
    free(b);
}

/* The client may reuse it once no surface shows it and the presenter let go. */
static void dmabuf_maybe_release(struct dnx_wl_dmabuf* b) {
    if (b->resource && b->uses == 0 && b->holds == 0) wl_buffer_send_release(b->resource);
}

/* -- wl_buffer ----------------------------------------------------------- */

static void buffer_destroy(struct wl_client* client, struct wl_resource* resource) {
    (void) client;
    wl_resource_destroy(resource);
}

static const struct wl_buffer_interface buffer_impl = {.destroy = buffer_destroy};

static void buffer_destroyed(struct wl_resource* resource) {
    struct dnx_wl_dmabuf* b = wl_resource_get_user_data(resource);
    b->resource = NULL;
    dmabuf_free_if_unused(b);
}

struct dnx_wl_dmabuf* dnx_wl_dmabuf_get(struct wl_resource* buffer) {
    if (!buffer || !wl_resource_instance_of(buffer, &wl_buffer_interface, &buffer_impl)) return NULL;
    return wl_resource_get_user_data(buffer);
}

void dnx_wl_dmabuf_size(struct dnx_wl_dmabuf* b, int32_t* width, int32_t* height) {
    *width = b->width;
    *height = b->height;
}

void dnx_wl_dmabuf_use(struct dnx_wl_dmabuf* b) {
    b->uses++;
}

void dnx_wl_dmabuf_unuse(struct dnx_wl_dmabuf* b) {
    b->uses--;
    dmabuf_maybe_release(b);
}

/* -- Presentation --------------------------------------------------------- */

/* Binder thread: hand the event to the compositor thread. */
static void on_presenter_event(dnx_buffer_event event, void* data) {
    dnx_wl_post_buffer_event(data, event == DNX_BUFFER_RELEASED);
}

void dnx_wl_dmabuf_on_presenter_event(struct dnx_wl_dmabuf* b, bool released) {
    if (!released) return;
    b->holds--;
    dmabuf_maybe_release(b);
    dmabuf_free_if_unused(b);
}

const uint8_t* dnx_wl_dmabuf_begin_read(struct dnx_wl_dmabuf* b, uint32_t* format, uint32_t* stride) {
    if (!b->map) {
        size_t size = (size_t) b->offset + (size_t) b->stride * (size_t) b->height;
        void* map = mmap(NULL, size, PROT_READ, MAP_SHARED, b->fd, 0);
        if (map == MAP_FAILED) {
            log(ERROR, "dma-buf mmap: %s", strerror(errno));
            return NULL;
        }
        b->map = map;
        b->map_size = size;
    }
    struct dma_buf_sync sync = {.flags = DMA_BUF_SYNC_START | DMA_BUF_SYNC_READ};
    ioctl(b->fd, DMA_BUF_IOCTL_SYNC, &sync);
    *format = b->format;
    *stride = b->stride;
    return (const uint8_t*) b->map + b->offset;
}

void dnx_wl_dmabuf_end_read(struct dnx_wl_dmabuf* b) {
    struct dma_buf_sync sync = {.flags = DMA_BUF_SYNC_END | DMA_BUF_SYNC_READ};
    ioctl(b->fd, DMA_BUF_IOCTL_SYNC, &sync);
}

static bool present_copy(struct dnx_wl_dmabuf* b, const dnx_box* rects, int count) {
    uint32_t format, stride;
    const uint8_t* data = dnx_wl_dmabuf_begin_read(b, &format, &stride);
    if (!data) return true; /* nothing to retry */
    bool ok = dnx_presenter_present(data, (uint32_t) b->width, (uint32_t) b->height, stride, DNX_PIXEL_BGRX8888,
                                    NULL, NULL, rects, count);
    dnx_wl_dmabuf_end_read(b);
    return ok;
}

bool dnx_wl_dmabuf_present(struct dnx_wl_dmabuf* b, const dnx_box* rects, int count) {
    if (!b->hardware_buffer) return present_copy(b, rects, count);
    b->holds++;
    if (dnx_presenter_present_buffer(b->hardware_buffer, (uint32_t) b->width, (uint32_t) b->height,
                                     on_presenter_event, b))
        return true;
    b->holds--;
    return false;
}

/* -- zwp_linux_buffer_params_v1 ------------------------------------------- */

struct params {
    int fd;
    uint32_t offset, stride;
    bool used;
};

static void params_destroy(struct wl_client* client, struct wl_resource* resource) {
    (void) client;
    wl_resource_destroy(resource);
}

static void params_destroyed(struct wl_resource* resource) {
    struct params* p = wl_resource_get_user_data(resource);
    if (p->fd >= 0) close(p->fd);
    free(p);
}

static void params_add(struct wl_client* client, struct wl_resource* resource, int32_t fd, uint32_t plane_idx,
                       uint32_t offset, uint32_t stride, uint32_t modifier_hi, uint32_t modifier_lo) {
    (void) client;
    struct params* p = wl_resource_get_user_data(resource);
    uint64_t modifier = ((uint64_t) modifier_hi << 32) | modifier_lo;
    if (p->used) {
        wl_resource_post_error(resource, ZWP_LINUX_BUFFER_PARAMS_V1_ERROR_ALREADY_USED, "params already used");
    } else if (plane_idx != 0) {
        wl_resource_post_error(resource, ZWP_LINUX_BUFFER_PARAMS_V1_ERROR_PLANE_IDX, "only one plane");
    } else if (p->fd >= 0) {
        wl_resource_post_error(resource, ZWP_LINUX_BUFFER_PARAMS_V1_ERROR_PLANE_SET, "plane already set");
    } else if (modifier != DRM_FORMAT_MOD_LINEAR) {
        wl_resource_post_error(resource, ZWP_LINUX_BUFFER_PARAMS_V1_ERROR_INVALID_FORMAT, "only linear buffers");
    } else {
        p->fd = fd;
        p->offset = offset;
        p->stride = stride;
        return;
    }
    close(fd);
}

/* The wl_buffer for `p`, or NULL with `reason` set. */
static struct wl_resource* create_buffer(struct wl_client* client, struct params* p, uint32_t id, int32_t width,
                                         int32_t height, uint32_t format, const char** reason) {
    *reason = NULL;
    if (p->fd < 0)
        *reason = "no plane";
    else if (format != DRM_FORMAT_ARGB8888 && format != DRM_FORMAT_XRGB8888)
        *reason = "unsupported format";
    else if (width <= 0 || height <= 0 || width > 16384 || height > 16384 || p->stride < (uint32_t) width * 4)
        *reason = "invalid size";
    else {
        off_t size = lseek(p->fd, 0, SEEK_END);
        if (size >= 0 && (uint64_t) p->offset + (uint64_t) p->stride * (uint64_t) height > (uint64_t) size)
            *reason = "buffer too small";
    }
    if (*reason) return NULL;

    struct dnx_wl_dmabuf* b = calloc(1, sizeof(*b));
    if (!b) {
        *reason = "out of memory";
        return NULL;
    }
    b->resource = wl_resource_create(client, &wl_buffer_interface, 1, id);
    if (!b->resource) {
        free(b);
        *reason = "out of memory";
        return NULL;
    }
    b->fd = p->fd;
    p->fd = -1;
    b->offset = p->offset;
    b->stride = p->stride;
    b->format = format;
    b->width = width;
    b->height = height;
    /* The layer shows the buffer from its first row: an AHB only at offset 0. */
    if (b->offset == 0) b->hardware_buffer = dnx_allocator_find(b->fd);
    log(INFO, "dma-buf %dx%d format %.4s stride %u offset %u: %s", width, height, (const char*) &format, b->stride,
        b->offset, b->hardware_buffer ? "app buffer, no copies" : "copied by the CPU");
    wl_resource_set_implementation(b->resource, &buffer_impl, b, buffer_destroyed);
    return b->resource;
}

static void params_create(struct wl_client* client, struct wl_resource* resource, int32_t width, int32_t height,
                          uint32_t format, uint32_t flags) {
    (void) flags;
    struct params* p = wl_resource_get_user_data(resource);
    if (p->used) {
        wl_resource_post_error(resource, ZWP_LINUX_BUFFER_PARAMS_V1_ERROR_ALREADY_USED, "params already used");
        return;
    }
    p->used = true;
    const char* reason;
    struct wl_resource* buffer = create_buffer(client, p, 0, width, height, format, &reason);
    if (buffer)
        zwp_linux_buffer_params_v1_send_created(resource, buffer);
    else {
        log(WARN, "dma-buf rejected: %s", reason);
        zwp_linux_buffer_params_v1_send_failed(resource);
    }
}

static void params_create_immed(struct wl_client* client, struct wl_resource* resource, uint32_t id, int32_t width,
                                int32_t height, uint32_t format, uint32_t flags) {
    (void) flags;
    struct params* p = wl_resource_get_user_data(resource);
    if (p->used) {
        wl_resource_post_error(resource, ZWP_LINUX_BUFFER_PARAMS_V1_ERROR_ALREADY_USED, "params already used");
        return;
    }
    p->used = true;
    const char* reason;
    if (!create_buffer(client, p, id, width, height, format, &reason))
        wl_resource_post_error(resource, ZWP_LINUX_BUFFER_PARAMS_V1_ERROR_INVALID_WL_BUFFER, "%s", reason);
}

static const struct zwp_linux_buffer_params_v1_interface params_impl = {
    .destroy = params_destroy,
    .add = params_add,
    .create = params_create,
    .create_immed = params_create_immed,
};

/* -- zwp_linux_dmabuf_v1 -------------------------------------------------- */

static void dmabuf_destroy(struct wl_client* client, struct wl_resource* resource) {
    (void) client;
    wl_resource_destroy(resource);
}

static void dmabuf_create_params(struct wl_client* client, struct wl_resource* resource, uint32_t id) {
    struct params* p = calloc(1, sizeof(*p));
    struct wl_resource* params =
        p ? wl_resource_create(client, &zwp_linux_buffer_params_v1_interface, wl_resource_get_version(resource), id)
          : NULL;
    if (!params) {
        free(p);
        wl_client_post_no_memory(client);
        return;
    }
    p->fd = -1;
    wl_resource_set_implementation(params, &params_impl, p, params_destroyed);
}

/* -- Feedback (version 4) ---------------------------------------------------- */

static const uint32_t formats[] = {DRM_FORMAT_ARGB8888, DRM_FORMAT_XRGB8888};

/* The GPU as the guest sees it: /dev/dri/renderD128 is a bind of this node.
 * Mesa's EGL opens the render node named here, else it falls back to software. */
static dev_t main_device;

static void feedback_destroy(struct wl_client* client, struct wl_resource* resource) {
    (void) client;
    wl_resource_destroy(resource);
}

static const struct zwp_linux_dmabuf_feedback_v1_interface feedback_impl = {.destroy = feedback_destroy};

/* The format table, a sealed memfd shared by every feedback (clients map it MAP_PRIVATE). */
static int format_table_fd = -1;

static int create_format_table(void) {
    struct {
        uint32_t format, padding;
        uint64_t modifier;
    } table[sizeof(formats) / sizeof(formats[0])];
    for (size_t i = 0; i < sizeof(formats) / sizeof(formats[0]); i++) {
        table[i].format = formats[i];
        table[i].padding = 0;
        table[i].modifier = DRM_FORMAT_MOD_LINEAR;
    }
    int fd = (int) syscall(__NR_memfd_create, "droidnux-dmabuf-formats", MFD_CLOEXEC | MFD_ALLOW_SEALING);
    if (fd < 0) return -1;
    if (write(fd, table, sizeof(table)) != (ssize_t) sizeof(table) ||
        fcntl(fd, F_ADD_SEALS, F_SEAL_SHRINK | F_SEAL_GROW | F_SEAL_WRITE | F_SEAL_SEAL) != 0) {
        close(fd);
        return -1;
    }
    return fd;
}

static void send_feedback(struct wl_resource* feedback) {
    zwp_linux_dmabuf_feedback_v1_send_format_table(feedback, format_table_fd,
                                                   (uint32_t) (sizeof(formats) / sizeof(formats[0]) * 16));
    struct wl_array device;
    wl_array_init(&device);
    dev_t* d = wl_array_add(&device, sizeof(dev_t));
    if (d) *d = main_device;
    zwp_linux_dmabuf_feedback_v1_send_main_device(feedback, &device);
    zwp_linux_dmabuf_feedback_v1_send_tranche_target_device(feedback, &device);
    wl_array_release(&device);
    struct wl_array indices;
    wl_array_init(&indices);
    for (uint16_t i = 0; i < sizeof(formats) / sizeof(formats[0]); i++) {
        uint16_t* index = wl_array_add(&indices, sizeof(uint16_t));
        if (index) *index = i;
    }
    zwp_linux_dmabuf_feedback_v1_send_tranche_formats(feedback, &indices);
    wl_array_release(&indices);
    zwp_linux_dmabuf_feedback_v1_send_tranche_flags(feedback, 0);
    zwp_linux_dmabuf_feedback_v1_send_tranche_done(feedback);
    zwp_linux_dmabuf_feedback_v1_send_done(feedback);
}

/* Default and per-surface feedback are the same: one GPU, one layout. */
static void dmabuf_get_feedback(struct wl_client* client, struct wl_resource* resource, uint32_t id) {
    struct wl_resource* feedback =
        wl_resource_create(client, &zwp_linux_dmabuf_feedback_v1_interface, wl_resource_get_version(resource), id);
    if (!feedback) {
        wl_client_post_no_memory(client);
        return;
    }
    wl_resource_set_implementation(feedback, &feedback_impl, NULL, NULL);
    send_feedback(feedback);
}

static void dmabuf_get_surface_feedback(struct wl_client* client, struct wl_resource* resource, uint32_t id,
                                        struct wl_resource* surface) {
    dmabuf_get_feedback(client, resource, id);
}

/* -- zwp_linux_dmabuf_v1 -------------------------------------------------- */

static const struct zwp_linux_dmabuf_v1_interface dmabuf_impl = {
    .destroy = dmabuf_destroy,
    .create_params = dmabuf_create_params,
    .get_default_feedback = dmabuf_get_feedback,
    .get_surface_feedback = dmabuf_get_surface_feedback,
};

static void dmabuf_bind(struct wl_client* client, void* data, uint32_t version, uint32_t id) {
    (void) data;
    struct wl_resource* resource = wl_resource_create(client, &zwp_linux_dmabuf_v1_interface, (int) version, id);
    if (!resource) {
        wl_client_post_no_memory(client);
        return;
    }
    wl_resource_set_implementation(resource, &dmabuf_impl, NULL, NULL);
    if (version >= ZWP_LINUX_DMABUF_V1_GET_DEFAULT_FEEDBACK_SINCE_VERSION) return; /* formats come with feedback */
    for (size_t i = 0; i < sizeof(formats) / sizeof(formats[0]); i++) {
        if (version >= ZWP_LINUX_DMABUF_V1_MODIFIER_SINCE_VERSION)
            zwp_linux_dmabuf_v1_send_modifier(resource, formats[i], 0, 0); /* DRM_FORMAT_MOD_LINEAR */
        else
            zwp_linux_dmabuf_v1_send_format(resource, formats[i]);
    }
}

void dnx_wl_dmabuf_init(struct wl_display* display) {
    struct stat st;
    if (stat("/dev/kgsl-3d0", &st) == 0) main_device = st.st_rdev;
    format_table_fd = create_format_table();
    /* Without the table or the device, version 3: formats as events, software EGL. */
    int version = format_table_fd >= 0 && main_device ? 4 : 3;
    if (version < 4) log(WARN, "linux-dmabuf version 3 only: no format table or GPU node");
    wl_global_create(display, &zwp_linux_dmabuf_v1_interface, version, NULL, dmabuf_bind);
}
