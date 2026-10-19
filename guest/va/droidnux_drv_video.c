/*
 * DroidNux VA-API driver: hardware video decoding for Linux apps in the
 * container, done by the device's MediaCodec decoders in the DroidNux app
 * (display/src/main/cpp/core/video_decoder.cpp, protocol in video_protocol.h).
 *
 * VA-API is stateless (the app parses the bitstream and hands over each
 * picture with its parameters) while MediaCodec is stateful (whole frames in,
 * pictures out), so the driver forwards each picture's bitstream, which VP9
 * carries whole. A surface shows the buffer of the last frame decoded into
 * it; exporting a surface gives that buffer's dma-buf, so the frames reach the
 * app's GPU without a copy.
 *
 * Selected with LIBVA_DRIVER_NAME=droidnux (the GPU device answers no DRM
 * ioctl, so libva cannot name a driver by itself).
 */
#define _GNU_SOURCE
#include <errno.h>
#include <fcntl.h>
#include <poll.h>
#include <pthread.h>
#include <stdarg.h>
#include <stdbool.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/mman.h>
#include <sys/socket.h>
#include <sys/un.h>
#include <unistd.h>

#include <va/va_backend.h>
#include <va/va_dec_vp9.h>
#include <va/va_drmcommon.h>

#include "video_protocol.h"

#define DNX_FOURCC(a, b, c, d) ((uint32_t)(a) | ((uint32_t)(b) << 8) | ((uint32_t)(c) << 16) | ((uint32_t)(d) << 24))
#define DRM_FORMAT_R8 DNX_FOURCC('R', '8', ' ', ' ')
#define DRM_FORMAT_GR88 DNX_FOURCC('G', 'R', '8', '8')
#define DRM_FORMAT_NV12 DNX_FOURCC('N', 'V', '1', '2')
#define DRM_FORMAT_MOD_LINEAR 0

/* linux/dma-buf.h */
struct dma_buf_sync {
    uint64_t flags;
};
#define DMA_BUF_SYNC_READ (1 << 0)
#define DMA_BUF_SYNC_START (0 << 2)
#define DMA_BUF_SYNC_END (1 << 2)
#define DMA_BUF_IOCTL_SYNC _IOW('b', 0, struct dma_buf_sync)

#define MAX_SIZE 8192
#define SYNC_TIMEOUT_MS 3000

static bool debug_enabled(void) {
    static int enabled = -1;
    if (enabled < 0) enabled = getenv("DROIDNUX_VA_DEBUG") != NULL;
    return enabled;
}

#define DEBUG(...)                             do {                                           if (debug_enabled()) dnx_log(__VA_ARGS__);     } while (0)

static void dnx_log(const char* format, ...) {
    va_list args;
    va_start(args, format);
    fputs("droidnux_drv_video: ", stderr);
    vfprintf(stderr, format, args);
    fputc('\n', stderr);
    va_end(args);
}

/* --- Objects ------------------------------------------------------------- */

enum object_type { OBJECT_CONFIG = 1, OBJECT_CONTEXT, OBJECT_SURFACE, OBJECT_BUFFER, OBJECT_IMAGE };

struct config {
    VAProfile profile;
    VAEntrypoint entrypoint;
};

struct context;

struct surface {
    unsigned width, height;
    struct context* context; /* of the last decode into it */
    uint32_t tag;            /* of that decode */
    bool pending;            /* its frame has not arrived yet */
    bool failed;             /* it never will */
    int fd;                  /* the current frame's dma-buf, or -1 */
    dnx_video_frame frame;
};

struct context {
    int socket;
    pthread_mutex_t receiving; /* one reader of the socket at a time */
    uint32_t codec;
    VASurfaceID target;
    uint32_t next_tag;
    VADecPictureParameterBufferVP9 vp9;
    bool have_picture;
    VASliceParameterBufferVP9 slice;
    uint8_t* data;
    size_t data_size, data_capacity;
};

struct buffer {
    VABufferType type;
    unsigned size, count;
    void* data;
    VAImageID derived; /* for an image's buffer: the image, mapped from its surface */
};

struct image {
    VAImage image;
    VASurfaceID derived_from; /* VA_INVALID_ID, or the surface whose frame it maps */
    void* mapping;
    size_t mapping_size;
};

struct object {
    int type;
    void* data;
};

struct driver {
    pthread_mutex_t mutex; /* the objects and the sockets' writes */
    struct object* objects;
    unsigned count, capacity;
};

#define DRIVER(ctx) ((struct driver*)(ctx)->pDriverData)

static unsigned add_object(struct driver* driver, int type, void* data) {
    for (unsigned i = 0; i < driver->count; i++) {
        if (driver->objects[i].type == 0) {
            driver->objects[i] = (struct object){type, data};
            return i + 1;
        }
    }
    if (driver->count == driver->capacity) {
        unsigned capacity = driver->capacity ? driver->capacity * 2 : 64;
        struct object* objects = realloc(driver->objects, capacity * sizeof(*objects));
        if (objects == NULL) return VA_INVALID_ID;
        driver->objects = objects;
        driver->capacity = capacity;
    }
    driver->objects[driver->count] = (struct object){type, data};
    return ++driver->count;
}

static void* find_object(struct driver* driver, unsigned id, int type) {
    if (id == 0 || id > driver->count || driver->objects[id - 1].type != type) return NULL;
    return driver->objects[id - 1].data;
}

static void remove_object(struct driver* driver, unsigned id) {
    driver->objects[id - 1] = (struct object){0, NULL};
}

/* --- Connection to the app ------------------------------------------------- */

static bool send_all(int fd, const void* data, size_t size) {
    const uint8_t* p = data;
    while (size > 0) {
        ssize_t n = send(fd, p, size, MSG_NOSIGNAL);
        if (n < 0 && errno == EINTR) continue;
        if (n <= 0) return false;
        p += n;
        size -= (size_t)n;
    }
    return true;
}

static bool send_message(int fd, uint32_t type, const void* body, uint32_t size, const void* data, uint32_t data_size) {
    dnx_video_header header = {type, size + data_size};
    return send_all(fd, &header, sizeof(header)) && send_all(fd, body, size) &&
           (data_size == 0 || send_all(fd, data, data_size));
}

static bool recv_all(int fd, void* data, size_t size) {
    uint8_t* p = data;
    while (size > 0) {
        ssize_t n = recv(fd, p, size, MSG_WAITALL);
        if (n < 0 && errno == EINTR) continue;
        if (n <= 0) return false;
        p += n;
        size -= (size_t)n;
    }
    return true;
}

/* A message, with the dma-buf that may come with it (or -1). */
static bool recv_message(int fd, dnx_video_header* header, void* body, uint32_t size, int* passed_fd) {
    char control[CMSG_SPACE(sizeof(int))];
    struct iovec iov = {header, sizeof(*header)};
    struct msghdr message = {.msg_iov = &iov, .msg_iovlen = 1, .msg_control = control, .msg_controllen = sizeof(control)};
    *passed_fd = -1;
    ssize_t n;
    do {
        n = recvmsg(fd, &message, MSG_WAITALL | MSG_CMSG_CLOEXEC);
    } while (n < 0 && errno == EINTR);
    if (n != sizeof(*header)) return false;
    for (struct cmsghdr* cmsg = CMSG_FIRSTHDR(&message); cmsg != NULL; cmsg = CMSG_NXTHDR(&message, cmsg)) {
        if (cmsg->cmsg_level == SOL_SOCKET && cmsg->cmsg_type == SCM_RIGHTS) memcpy(passed_fd, CMSG_DATA(cmsg), sizeof(int));
    }
    if (header->size != size || !recv_all(fd, body, size)) {
        if (*passed_fd >= 0) close(*passed_fd);
        return false;
    }
    return true;
}

static int connect_app(void) {
    const char* path = getenv("DROIDNUX_VIDEO_SOCKET");
    if (path == NULL) path = DNX_VIDEO_SOCKET;
    struct sockaddr_un address = {.sun_family = AF_UNIX};
    if (strlen(path) >= sizeof(address.sun_path)) return -1;
    strcpy(address.sun_path, path);
    int fd = socket(AF_UNIX, SOCK_STREAM | SOCK_CLOEXEC, 0);
    if (fd >= 0 && connect(fd, (struct sockaddr*)&address, sizeof(address)) != 0) {
        close(fd);
        fd = -1;
    }
    return fd;
}

/* The frame the app sent for a decode: into its surface, unless the surface
 * has been decoded into again since. Called with the driver's mutex. */
static void apply_frame(struct driver* driver, struct context* context, const dnx_video_frame* frame, int fd) {
    struct surface* target = NULL;
    for (unsigned i = 0; i < driver->count; i++) {
        struct surface* s = driver->objects[i].type == OBJECT_SURFACE ? driver->objects[i].data : NULL;
        if (s != NULL && s->context == context && s->pending && s->tag == frame->tag) target = s;
    }
    DEBUG("frame for decode %u: status %d, buffer %u, %s", frame->tag, frame->status, frame->buffer,
          target != NULL ? "applied" : "stale");
    if (target == NULL || frame->status != 0) {
        if (frame->status == 0) {
            dnx_video_release release = {frame->buffer};
            send_message(context->socket, DNX_VIDEO_RELEASE, &release, sizeof(release), NULL, 0);
        }
        if (fd >= 0) close(fd);
        if (target != NULL) {
            /* The codec dropped it (-ENODATA): the surface keeps its last frame. */
            if (frame->status != -ENODATA) dnx_log("decode failed: %s", strerror(-frame->status));
            target->pending = false;
            target->failed = frame->status != -ENODATA;
        }
        return;
    }
    if (target->fd >= 0) {
        dnx_video_release release = {target->frame.buffer};
        send_message(context->socket, DNX_VIDEO_RELEASE, &release, sizeof(release), NULL, 0);
        close(target->fd);
    }
    target->frame = *frame;
    target->fd = fd;
    target->pending = false;
    target->failed = false;
}

/* Waits for the surface's frame. Called without the driver's mutex. */
static VAStatus sync_surface(VADriverContextP ctx, VASurfaceID id, int timeout_ms) {
    struct driver* driver = DRIVER(ctx);
    pthread_mutex_lock(&driver->mutex);
    struct surface* surface = find_object(driver, id, OBJECT_SURFACE);
    if (surface == NULL) {
        pthread_mutex_unlock(&driver->mutex);
        return VA_STATUS_ERROR_INVALID_SURFACE;
    }
    struct context* context = surface->context;
    bool pending = surface->pending;
    pthread_mutex_unlock(&driver->mutex);
    if (!pending) return surface->failed ? VA_STATUS_ERROR_DECODING_ERROR : VA_STATUS_SUCCESS;

    VAStatus status = VA_STATUS_SUCCESS;
    pthread_mutex_lock(&context->receiving);
    for (;;) {
        pthread_mutex_lock(&driver->mutex);
        pending = surface->pending;
        pthread_mutex_unlock(&driver->mutex);
        if (!pending) break;

        struct pollfd pfd = {context->socket, POLLIN, 0};
        int ready = poll(&pfd, 1, timeout_ms);
        if (ready == 0) {
            status = VA_STATUS_ERROR_TIMEDOUT;
            break;
        }
        dnx_video_header header;
        dnx_video_frame frame;
        int fd = -1;
        if (ready < 0 || !recv_message(context->socket, &header, &frame, sizeof(frame), &fd) ||
            header.type != DNX_VIDEO_FRAME) {
            dnx_log("lost the decoder");
            if (fd >= 0) close(fd);
            pthread_mutex_lock(&driver->mutex);
            surface->pending = false;
            surface->failed = true;
            pthread_mutex_unlock(&driver->mutex);
            break;
        }
        pthread_mutex_lock(&driver->mutex);
        apply_frame(driver, context, &frame, fd);
        pthread_mutex_unlock(&driver->mutex);
    }
    pthread_mutex_unlock(&context->receiving);
    if (status == VA_STATUS_SUCCESS && surface->failed) status = VA_STATUS_ERROR_DECODING_ERROR;
    return status;
}

/* --- VP9 ------------------------------------------------------------------- */

struct bits {
    const uint8_t* data;
    size_t size, position;
};

static unsigned read_bits(struct bits* b, unsigned n) {
    unsigned value = 0;
    while (n-- > 0) {
        size_t byte = b->position / 8;
        unsigned bit = byte < b->size ? (b->data[byte] >> (7 - b->position % 8)) & 1 : 0;
        value = (value << 1) | bit;
        b->position++;
    }
    return value;
}

/* The reference slots a frame refreshes, from its uncompressed header. */
static unsigned vp9_refresh_frame_flags(const uint8_t* data, size_t size) {
    struct bits b = {data, size, 0};
    if (read_bits(&b, 2) != 2) return 0; /* frame_marker */
    unsigned profile = read_bits(&b, 1);
    profile |= read_bits(&b, 1) << 1;
    if (profile == 3) read_bits(&b, 1);
    if (read_bits(&b, 1)) return 0; /* show_existing_frame */
    unsigned frame_type = read_bits(&b, 1);
    unsigned show_frame = read_bits(&b, 1);
    unsigned error_resilient_mode = read_bits(&b, 1);
    if (frame_type == 0) return 0xff; /* key frame */
    unsigned intra_only = show_frame ? 0 : read_bits(&b, 1);
    if (!error_resilient_mode) read_bits(&b, 2); /* reset_frame_context */
    if (intra_only) {
        read_bits(&b, 24); /* sync code */
        if (profile > 0) {
            if (profile >= 2) read_bits(&b, 1); /* ten_or_twelve_bit */
            unsigned color_space = read_bits(&b, 3);
            if (color_space != 7) { /* not CS_RGB */
                read_bits(&b, 1);   /* color_range */
                if (profile == 1 || profile == 3) read_bits(&b, 3);
            } else if (profile == 1 || profile == 3) {
                read_bits(&b, 1);
            }
        }
    }
    return read_bits(&b, 8);
}

static void release_frame(struct surface* surface);

static VAStatus vp9_end_picture(struct driver* driver, struct context* context, struct surface* surface) {
    if (!context->have_picture || context->data_size == 0) return VA_STATUS_ERROR_INVALID_PARAMETER;
    /* The decode replaces the surface's frame: the app gets its buffer back
     * now, so the client holds at most one buffer per surface (a decoder
     * that runs ahead would otherwise use up the app's buffers). */
    release_frame(surface);
    uint32_t tag = ++context->next_tag;
    bool shown = context->vp9.pic_fields.bits.show_frame;
    DEBUG("decode %u into surface %u: %zu bytes, %s", tag, context->target, context->data_size,
          shown ? "shown" : "hidden");
    dnx_video_decode decode = {tag, shown ? 0 : DNX_VIDEO_NO_OUTPUT};
    if (!send_message(context->socket, DNX_VIDEO_DECODE, &decode, sizeof(decode), context->data,
                      (uint32_t)context->data_size)) {
        return VA_STATUS_ERROR_OPERATION_FAILED;
    }
    if (!shown) {
        /* MediaCodec outputs nothing for a hidden frame, while the app may show
         * it later (show_existing_frame, which never reaches the driver): have
         * it shown now from a slot it refreshed, as its surface's frame. */
        unsigned refresh = vp9_refresh_frame_flags(context->data, context->data_size);
        DEBUG("hidden frame refreshes slots %#x", refresh);
        if (refresh == 0) return VA_STATUS_SUCCESS; /* nothing to show it from */
        unsigned profile = context->vp9.profile;
        uint8_t header = 0x80 | (profile & 1) << 5 | (profile >> 1) << 4 | 0x08 | (unsigned)__builtin_ctz(refresh);
        if (profile == 3) return VA_STATUS_SUCCESS; /* one more bit: not supported anyway */
        decode.flags = 0;
        if (!send_message(context->socket, DNX_VIDEO_DECODE, &decode, sizeof(decode), &header, 1)) {
            return VA_STATUS_ERROR_OPERATION_FAILED;
        }
    }
    (void)driver;
    surface->context = context;
    surface->tag = tag;
    surface->pending = true;
    surface->failed = false;
    return VA_STATUS_SUCCESS;
}

/* --- Configs ----------------------------------------------------------------- */

static const VAProfile profiles[] = {VAProfileVP9Profile0};

static bool supported(VAProfile profile) {
    for (unsigned i = 0; i < sizeof(profiles) / sizeof(profiles[0]); i++) {
        if (profiles[i] == profile) return true;
    }
    return false;
}

static VAStatus dnx_QueryConfigProfiles(VADriverContextP ctx, VAProfile* list, int* count) {
    (void)ctx;
    *count = sizeof(profiles) / sizeof(profiles[0]);
    memcpy(list, profiles, sizeof(profiles));
    return VA_STATUS_SUCCESS;
}

static VAStatus dnx_QueryConfigEntrypoints(VADriverContextP ctx, VAProfile profile, VAEntrypoint* list, int* count) {
    (void)ctx;
    if (!supported(profile)) return VA_STATUS_ERROR_UNSUPPORTED_PROFILE;
    list[0] = VAEntrypointVLD;
    *count = 1;
    return VA_STATUS_SUCCESS;
}

static void fill_attributes(VAConfigAttrib* list, int count) {
    for (int i = 0; i < count; i++) {
        switch (list[i].type) {
            case VAConfigAttribRTFormat: list[i].value = VA_RT_FORMAT_YUV420; break;
            case VAConfigAttribMaxPictureWidth:
            case VAConfigAttribMaxPictureHeight: list[i].value = MAX_SIZE; break;
            default: list[i].value = VA_ATTRIB_NOT_SUPPORTED; break;
        }
    }
}

static VAStatus dnx_GetConfigAttributes(VADriverContextP ctx, VAProfile profile, VAEntrypoint entrypoint,
                                        VAConfigAttrib* list, int count) {
    (void)ctx;
    if (!supported(profile)) return VA_STATUS_ERROR_UNSUPPORTED_PROFILE;
    if (entrypoint != VAEntrypointVLD) return VA_STATUS_ERROR_UNSUPPORTED_ENTRYPOINT;
    fill_attributes(list, count);
    return VA_STATUS_SUCCESS;
}

static VAStatus dnx_CreateConfig(VADriverContextP ctx, VAProfile profile, VAEntrypoint entrypoint,
                                 VAConfigAttrib* attributes, int count, VAConfigID* id) {
    if (!supported(profile)) return VA_STATUS_ERROR_UNSUPPORTED_PROFILE;
    if (entrypoint != VAEntrypointVLD) return VA_STATUS_ERROR_UNSUPPORTED_ENTRYPOINT;
    for (int i = 0; i < count; i++) {
        if (attributes[i].type == VAConfigAttribRTFormat && !(attributes[i].value & VA_RT_FORMAT_YUV420)) {
            return VA_STATUS_ERROR_UNSUPPORTED_RT_FORMAT;
        }
    }
    struct config* config = calloc(1, sizeof(*config));
    if (config == NULL) return VA_STATUS_ERROR_ALLOCATION_FAILED;
    config->profile = profile;
    config->entrypoint = entrypoint;
    struct driver* driver = DRIVER(ctx);
    pthread_mutex_lock(&driver->mutex);
    *id = add_object(driver, OBJECT_CONFIG, config);
    pthread_mutex_unlock(&driver->mutex);
    if (*id == VA_INVALID_ID) {
        free(config);
        return VA_STATUS_ERROR_ALLOCATION_FAILED;
    }
    return VA_STATUS_SUCCESS;
}

static VAStatus dnx_DestroyConfig(VADriverContextP ctx, VAConfigID id) {
    struct driver* driver = DRIVER(ctx);
    pthread_mutex_lock(&driver->mutex);
    struct config* config = find_object(driver, id, OBJECT_CONFIG);
    if (config != NULL) remove_object(driver, id);
    pthread_mutex_unlock(&driver->mutex);
    free(config);
    return config != NULL ? VA_STATUS_SUCCESS : VA_STATUS_ERROR_INVALID_CONFIG;
}

static VAStatus dnx_QueryConfigAttributes(VADriverContextP ctx, VAConfigID id, VAProfile* profile,
                                          VAEntrypoint* entrypoint, VAConfigAttrib* list, int* count) {
    struct driver* driver = DRIVER(ctx);
    pthread_mutex_lock(&driver->mutex);
    struct config* config = find_object(driver, id, OBJECT_CONFIG);
    if (config != NULL) {
        *profile = config->profile;
        *entrypoint = config->entrypoint;
        list[0].type = VAConfigAttribRTFormat;
        list[0].value = VA_RT_FORMAT_YUV420;
        *count = 1;
    }
    pthread_mutex_unlock(&driver->mutex);
    return config != NULL ? VA_STATUS_SUCCESS : VA_STATUS_ERROR_INVALID_CONFIG;
}

/* --- Surfaces ---------------------------------------------------------------- */

static VAStatus dnx_CreateSurfaces2(VADriverContextP ctx, unsigned format, unsigned width, unsigned height,
                                    VASurfaceID* surfaces, unsigned count, VASurfaceAttrib* attributes,
                                    unsigned attribute_count) {
    if (format != VA_RT_FORMAT_YUV420) return VA_STATUS_ERROR_UNSUPPORTED_RT_FORMAT;
    if (width == 0 || height == 0 || width > MAX_SIZE || height > MAX_SIZE) return VA_STATUS_ERROR_RESOLUTION_NOT_SUPPORTED;
    for (unsigned i = 0; i < attribute_count; i++) {
        const VASurfaceAttrib* a = &attributes[i];
        if (!(a->flags & VA_SURFACE_ATTRIB_SETTABLE)) continue;
        if (a->type == VASurfaceAttribPixelFormat && a->value.value.i != (int)VA_FOURCC_NV12) {
            return VA_STATUS_ERROR_INVALID_IMAGE_FORMAT;
        }
        /* Frames live in the app's buffers: no imported memory. */
        if (a->type == VASurfaceAttribMemoryType && a->value.value.i != VA_SURFACE_ATTRIB_MEM_TYPE_VA) {
            return VA_STATUS_ERROR_UNSUPPORTED_MEMORY_TYPE;
        }
    }
    struct driver* driver = DRIVER(ctx);
    VAStatus status = VA_STATUS_SUCCESS;
    pthread_mutex_lock(&driver->mutex);
    unsigned created = 0;
    for (; created < count; created++) {
        struct surface* surface = calloc(1, sizeof(*surface));
        if (surface == NULL) break;
        surface->width = width;
        surface->height = height;
        surface->fd = -1;
        surfaces[created] = add_object(driver, OBJECT_SURFACE, surface);
        if (surfaces[created] == VA_INVALID_ID) {
            free(surface);
            break;
        }
    }
    if (created < count) {
        for (unsigned i = 0; i < created; i++) {
            free(find_object(driver, surfaces[i], OBJECT_SURFACE));
            remove_object(driver, surfaces[i]);
        }
        status = VA_STATUS_ERROR_ALLOCATION_FAILED;
    }
    pthread_mutex_unlock(&driver->mutex);
    return status;
}

static VAStatus dnx_CreateSurfaces(VADriverContextP ctx, int width, int height, int format, int count,
                                   VASurfaceID* surfaces) {
    return dnx_CreateSurfaces2(ctx, (unsigned)format, (unsigned)width, (unsigned)height, surfaces, (unsigned)count,
                               NULL, 0);
}

static void release_frame(struct surface* surface) {
    if (surface->fd < 0) return;
    if (surface->context != NULL) {
        dnx_video_release release = {surface->frame.buffer};
        send_message(surface->context->socket, DNX_VIDEO_RELEASE, &release, sizeof(release), NULL, 0);
    }
    close(surface->fd);
    surface->fd = -1;
}

static VAStatus dnx_DestroySurfaces(VADriverContextP ctx, VASurfaceID* surfaces, int count) {
    struct driver* driver = DRIVER(ctx);
    pthread_mutex_lock(&driver->mutex);
    for (int i = 0; i < count; i++) {
        struct surface* surface = find_object(driver, surfaces[i], OBJECT_SURFACE);
        if (surface == NULL) continue;
        release_frame(surface);
        remove_object(driver, surfaces[i]);
        free(surface);
    }
    pthread_mutex_unlock(&driver->mutex);
    return VA_STATUS_SUCCESS;
}

static VAStatus dnx_SyncSurface(VADriverContextP ctx, VASurfaceID id) {
    return sync_surface(ctx, id, SYNC_TIMEOUT_MS);
}

static VAStatus dnx_SyncSurface2(VADriverContextP ctx, VASurfaceID id, uint64_t timeout_ns) {
    int timeout_ms = timeout_ns == VA_TIMEOUT_INFINITE ? SYNC_TIMEOUT_MS
                     : timeout_ns / 1000000 > SYNC_TIMEOUT_MS ? SYNC_TIMEOUT_MS
                                                              : (int)(timeout_ns / 1000000);
    return sync_surface(ctx, id, timeout_ms);
}

static VAStatus dnx_QuerySurfaceStatus(VADriverContextP ctx, VASurfaceID id, VASurfaceStatus* status) {
    struct driver* driver = DRIVER(ctx);
    pthread_mutex_lock(&driver->mutex);
    struct surface* surface = find_object(driver, id, OBJECT_SURFACE);
    if (surface != NULL) *status = surface->pending ? VASurfaceRendering : VASurfaceReady;
    pthread_mutex_unlock(&driver->mutex);
    return surface != NULL ? VA_STATUS_SUCCESS : VA_STATUS_ERROR_INVALID_SURFACE;
}

static VAStatus dnx_QuerySurfaceAttributes(VADriverContextP ctx, VAConfigID config, VASurfaceAttrib* list,
                                           unsigned* count) {
    struct driver* driver = DRIVER(ctx);
    pthread_mutex_lock(&driver->mutex);
    bool valid = find_object(driver, config, OBJECT_CONFIG) != NULL;
    pthread_mutex_unlock(&driver->mutex);
    if (!valid) return VA_STATUS_ERROR_INVALID_CONFIG;

    const VASurfaceAttrib attributes[] = {
        {VASurfaceAttribPixelFormat, VA_SURFACE_ATTRIB_GETTABLE | VA_SURFACE_ATTRIB_SETTABLE,
         {VAGenericValueTypeInteger, {.i = VA_FOURCC_NV12}}},
        {VASurfaceAttribMinWidth, VA_SURFACE_ATTRIB_GETTABLE, {VAGenericValueTypeInteger, {.i = 16}}},
        {VASurfaceAttribMinHeight, VA_SURFACE_ATTRIB_GETTABLE, {VAGenericValueTypeInteger, {.i = 16}}},
        {VASurfaceAttribMaxWidth, VA_SURFACE_ATTRIB_GETTABLE, {VAGenericValueTypeInteger, {.i = MAX_SIZE}}},
        {VASurfaceAttribMaxHeight, VA_SURFACE_ATTRIB_GETTABLE, {VAGenericValueTypeInteger, {.i = MAX_SIZE}}},
        {VASurfaceAttribMemoryType, VA_SURFACE_ATTRIB_GETTABLE | VA_SURFACE_ATTRIB_SETTABLE,
         {VAGenericValueTypeInteger, {.i = VA_SURFACE_ATTRIB_MEM_TYPE_VA | VA_SURFACE_ATTRIB_MEM_TYPE_DRM_PRIME_2}}},
    };
    unsigned n = sizeof(attributes) / sizeof(attributes[0]);
    if (list == NULL) {
        *count = n;
        return VA_STATUS_SUCCESS;
    }
    if (*count < n) {
        *count = n;
        return VA_STATUS_ERROR_MAX_NUM_EXCEEDED;
    }
    memcpy(list, attributes, sizeof(attributes));
    *count = n;
    return VA_STATUS_SUCCESS;
}

static VAStatus dnx_ExportSurfaceHandle(VADriverContextP ctx, VASurfaceID id, uint32_t mem_type, uint32_t flags,
                                        void* out) {
    if (mem_type != VA_SURFACE_ATTRIB_MEM_TYPE_DRM_PRIME_2) return VA_STATUS_ERROR_UNSUPPORTED_MEMORY_TYPE;
    VAStatus status = sync_surface(ctx, id, SYNC_TIMEOUT_MS);
    if (status != VA_STATUS_SUCCESS && status != VA_STATUS_ERROR_DECODING_ERROR) return status;

    struct driver* driver = DRIVER(ctx);
    pthread_mutex_lock(&driver->mutex);
    struct surface* surface = find_object(driver, id, OBJECT_SURFACE);
    int fd = surface != NULL && surface->fd >= 0 ? fcntl(surface->fd, F_DUPFD_CLOEXEC, 0) : -1;
    if (fd < 0) {
        pthread_mutex_unlock(&driver->mutex);
        return VA_STATUS_ERROR_INVALID_SURFACE; /* nothing decoded into it */
    }
    const dnx_video_frame* frame = &surface->frame;
    VADRMPRIMESurfaceDescriptor* d = out;
    memset(d, 0, sizeof(*d));
    d->fourcc = VA_FOURCC_NV12;
    d->width = surface->width;
    d->height = surface->height;
    d->num_objects = 1;
    d->objects[0].fd = fd;
    d->objects[0].size = (uint32_t)frame->size;
    d->objects[0].drm_format_modifier = DRM_FORMAT_MOD_LINEAR;
    if (flags & VA_EXPORT_SURFACE_SEPARATE_LAYERS) {
        d->num_layers = 2;
        d->layers[0] = (typeof(d->layers[0])){DRM_FORMAT_R8, 1, {0}, {frame->offsets[0]}, {frame->pitches[0]}};
        d->layers[1] = (typeof(d->layers[1])){DRM_FORMAT_GR88, 1, {0}, {frame->offsets[1]}, {frame->pitches[1]}};
    } else {
        d->num_layers = 1;
        d->layers[0] = (typeof(d->layers[0])){DRM_FORMAT_NV12, 2, {0, 0},
                                              {frame->offsets[0], frame->offsets[1]},
                                              {frame->pitches[0], frame->pitches[1]}};
    }
    pthread_mutex_unlock(&driver->mutex);
    return VA_STATUS_SUCCESS;
}

/* --- Contexts ---------------------------------------------------------------- */

static VAStatus dnx_CreateContext(VADriverContextP ctx, VAConfigID config_id, int width, int height, int flags,
                                  VASurfaceID* targets, int target_count, VAContextID* id) {
    (void)flags;
    (void)targets;
    struct driver* driver = DRIVER(ctx);
    pthread_mutex_lock(&driver->mutex);
    struct config* config = find_object(driver, config_id, OBJECT_CONFIG);
    VAProfile profile = config != NULL ? config->profile : VAProfileNone;
    pthread_mutex_unlock(&driver->mutex);
    if (config == NULL) return VA_STATUS_ERROR_INVALID_CONFIG;
    if (width <= 0 || height <= 0 || width > MAX_SIZE || height > MAX_SIZE) return VA_STATUS_ERROR_RESOLUTION_NOT_SUPPORTED;

    struct context* context = calloc(1, sizeof(*context));
    if (context == NULL) return VA_STATUS_ERROR_ALLOCATION_FAILED;
    pthread_mutex_init(&context->receiving, NULL);
    context->codec = profile == VAProfileVP9Profile0 ? DNX_VIDEO_VP9 : 0;
    context->socket = connect_app();
    if (context->socket < 0) {
        dnx_log("cannot reach the DroidNux app: %s", strerror(errno));
        free(context);
        return VA_STATUS_ERROR_HW_BUSY;
    }
    dnx_video_open open = {DNX_VIDEO_MAGIC, DNX_VIDEO_VERSION, context->codec, (uint32_t)width, (uint32_t)height,
                           target_count > 0 ? (uint32_t)target_count : 16};
    dnx_video_header header;
    dnx_video_opened opened = {-EPROTO};
    int fd = -1;
    if (!send_message(context->socket, DNX_VIDEO_OPEN, &open, sizeof(open), NULL, 0) ||
        !recv_message(context->socket, &header, &opened, sizeof(opened), &fd) || header.type != DNX_VIDEO_OPENED ||
        opened.status != 0) {
        dnx_log("the app cannot decode %dx%d: %s", width, height, strerror(-opened.status));
        if (fd >= 0) close(fd);
        close(context->socket);
        free(context);
        return VA_STATUS_ERROR_HW_BUSY;
    }
    pthread_mutex_lock(&driver->mutex);
    *id = add_object(driver, OBJECT_CONTEXT, context);
    pthread_mutex_unlock(&driver->mutex);
    if (*id == VA_INVALID_ID) {
        close(context->socket);
        free(context);
        return VA_STATUS_ERROR_ALLOCATION_FAILED;
    }
    return VA_STATUS_SUCCESS;
}

static VAStatus dnx_DestroyContext(VADriverContextP ctx, VAContextID id) {
    struct driver* driver = DRIVER(ctx);
    pthread_mutex_lock(&driver->mutex);
    struct context* context = find_object(driver, id, OBJECT_CONTEXT);
    if (context != NULL) {
        remove_object(driver, id);
        /* Frames stay in their surfaces (their dma-bufs keep the memory),
         * but are no longer the app's to release: the connection closes. */
        for (unsigned i = 0; i < driver->count; i++) {
            struct surface* s = driver->objects[i].type == OBJECT_SURFACE ? driver->objects[i].data : NULL;
            if (s != NULL && s->context == context) {
                s->context = NULL;
                if (s->pending) {
                    s->pending = false;
                    s->failed = true;
                }
            }
        }
    }
    pthread_mutex_unlock(&driver->mutex);
    if (context == NULL) return VA_STATUS_ERROR_INVALID_CONTEXT;
    close(context->socket);
    pthread_mutex_destroy(&context->receiving);
    free(context->data);
    free(context);
    return VA_STATUS_SUCCESS;
}

/* --- Buffers ----------------------------------------------------------------- */

static VAStatus dnx_CreateBuffer(VADriverContextP ctx, VAContextID context, VABufferType type, unsigned size,
                                 unsigned count, void* data, VABufferID* id) {
    (void)context;
    if (size == 0 || count == 0 || (uint64_t)size * count > DNX_VIDEO_MAX_FRAME) return VA_STATUS_ERROR_INVALID_PARAMETER;
    struct buffer* buffer = calloc(1, sizeof(*buffer));
    if (buffer == NULL || (buffer->data = malloc((size_t)size * count)) == NULL) {
        free(buffer);
        return VA_STATUS_ERROR_ALLOCATION_FAILED;
    }
    buffer->type = type;
    buffer->size = size;
    buffer->count = count;
    buffer->derived = VA_INVALID_ID;
    if (data != NULL) memcpy(buffer->data, data, (size_t)size * count);
    struct driver* driver = DRIVER(ctx);
    pthread_mutex_lock(&driver->mutex);
    *id = add_object(driver, OBJECT_BUFFER, buffer);
    pthread_mutex_unlock(&driver->mutex);
    if (*id == VA_INVALID_ID) {
        free(buffer->data);
        free(buffer);
        return VA_STATUS_ERROR_ALLOCATION_FAILED;
    }
    return VA_STATUS_SUCCESS;
}

static VAStatus dnx_BufferSetNumElements(VADriverContextP ctx, VABufferID id, unsigned count) {
    struct driver* driver = DRIVER(ctx);
    pthread_mutex_lock(&driver->mutex);
    struct buffer* buffer = find_object(driver, id, OBJECT_BUFFER);
    VAStatus status = buffer == NULL ? VA_STATUS_ERROR_INVALID_BUFFER
                      : count > buffer->count ? VA_STATUS_ERROR_INVALID_PARAMETER
                                              : VA_STATUS_SUCCESS;
    if (status == VA_STATUS_SUCCESS) buffer->count = count;
    pthread_mutex_unlock(&driver->mutex);
    return status;
}

static VAStatus map_derived(struct driver* driver, struct image* image);

static VAStatus dnx_MapBuffer(VADriverContextP ctx, VABufferID id, void** data) {
    struct driver* driver = DRIVER(ctx);
    pthread_mutex_lock(&driver->mutex);
    struct buffer* buffer = find_object(driver, id, OBJECT_BUFFER);
    VAStatus status = buffer != NULL ? VA_STATUS_SUCCESS : VA_STATUS_ERROR_INVALID_BUFFER;
    if (buffer != NULL && buffer->derived != VA_INVALID_ID) {
        struct image* image = find_object(driver, buffer->derived, OBJECT_IMAGE);
        status = image != NULL ? map_derived(driver, image) : VA_STATUS_ERROR_INVALID_IMAGE;
        if (status == VA_STATUS_SUCCESS) *data = image->mapping;
    } else if (buffer != NULL) {
        *data = buffer->data;
    }
    pthread_mutex_unlock(&driver->mutex);
    return status;
}

static void unmap_derived(struct driver* driver, struct image* image);

static VAStatus dnx_UnmapBuffer(VADriverContextP ctx, VABufferID id) {
    struct driver* driver = DRIVER(ctx);
    pthread_mutex_lock(&driver->mutex);
    struct buffer* buffer = find_object(driver, id, OBJECT_BUFFER);
    if (buffer != NULL && buffer->derived != VA_INVALID_ID) {
        struct image* image = find_object(driver, buffer->derived, OBJECT_IMAGE);
        if (image != NULL) unmap_derived(driver, image);
    }
    pthread_mutex_unlock(&driver->mutex);
    return buffer != NULL ? VA_STATUS_SUCCESS : VA_STATUS_ERROR_INVALID_BUFFER;
}

static void destroy_buffer(struct driver* driver, VABufferID id) {
    struct buffer* buffer = find_object(driver, id, OBJECT_BUFFER);
    if (buffer == NULL) return;
    remove_object(driver, id);
    free(buffer->data);
    free(buffer);
}

static VAStatus dnx_DestroyBuffer(VADriverContextP ctx, VABufferID id) {
    struct driver* driver = DRIVER(ctx);
    pthread_mutex_lock(&driver->mutex);
    bool found = find_object(driver, id, OBJECT_BUFFER) != NULL;
    destroy_buffer(driver, id);
    pthread_mutex_unlock(&driver->mutex);
    return found ? VA_STATUS_SUCCESS : VA_STATUS_ERROR_INVALID_BUFFER;
}

static VAStatus dnx_BufferInfo(VADriverContextP ctx, VABufferID id, VABufferType* type, unsigned* size,
                               unsigned* count) {
    struct driver* driver = DRIVER(ctx);
    pthread_mutex_lock(&driver->mutex);
    struct buffer* buffer = find_object(driver, id, OBJECT_BUFFER);
    if (buffer != NULL) {
        *type = buffer->type;
        *size = buffer->size;
        *count = buffer->count;
    }
    pthread_mutex_unlock(&driver->mutex);
    return buffer != NULL ? VA_STATUS_SUCCESS : VA_STATUS_ERROR_INVALID_BUFFER;
}

/* --- Decoding ---------------------------------------------------------------- */

static VAStatus dnx_BeginPicture(VADriverContextP ctx, VAContextID id, VASurfaceID target) {
    struct driver* driver = DRIVER(ctx);
    pthread_mutex_lock(&driver->mutex);
    struct context* context = find_object(driver, id, OBJECT_CONTEXT);
    bool valid = find_object(driver, target, OBJECT_SURFACE) != NULL;
    if (context != NULL && valid) {
        context->target = target;
        context->have_picture = false;
        context->data_size = 0;
    }
    pthread_mutex_unlock(&driver->mutex);
    return context == NULL ? VA_STATUS_ERROR_INVALID_CONTEXT : valid ? VA_STATUS_SUCCESS : VA_STATUS_ERROR_INVALID_SURFACE;
}

static VAStatus append_data(struct context* context, const uint8_t* data, size_t size) {
    if (context->data_size + size > DNX_VIDEO_MAX_FRAME) return VA_STATUS_ERROR_INVALID_PARAMETER;
    if (context->data_size + size > context->data_capacity) {
        size_t capacity = (context->data_size + size) * 2;
        uint8_t* grown = realloc(context->data, capacity);
        if (grown == NULL) return VA_STATUS_ERROR_ALLOCATION_FAILED;
        context->data = grown;
        context->data_capacity = capacity;
    }
    memcpy(context->data + context->data_size, data, size);
    context->data_size += size;
    return VA_STATUS_SUCCESS;
}

static VAStatus render_buffer(struct context* context, struct buffer* buffer) {
    switch (buffer->type) {
        case VAPictureParameterBufferType:
            if (buffer->size < sizeof(context->vp9)) return VA_STATUS_ERROR_INVALID_PARAMETER;
            memcpy(&context->vp9, buffer->data, sizeof(context->vp9));
            context->have_picture = true;
            return VA_STATUS_SUCCESS;
        case VASliceParameterBufferType:
            if (buffer->size < sizeof(context->slice)) return VA_STATUS_ERROR_INVALID_PARAMETER;
            memcpy(&context->slice, buffer->data, sizeof(context->slice));
            return VA_STATUS_SUCCESS;
        case VASliceDataBufferType: {
            size_t size = (size_t)buffer->size * buffer->count;
            size_t offset = context->slice.slice_data_offset;
            size_t length = context->slice.slice_data_size;
            if (offset > size || length > size - offset) return VA_STATUS_ERROR_INVALID_PARAMETER;
            return append_data(context, (const uint8_t*)buffer->data + offset, length);
        }
        default:
            return VA_STATUS_SUCCESS; /* nothing the codec needs (probability tables, ...) */
    }
}

static VAStatus dnx_RenderPicture(VADriverContextP ctx, VAContextID id, VABufferID* buffers, int count) {
    struct driver* driver = DRIVER(ctx);
    pthread_mutex_lock(&driver->mutex);
    struct context* context = find_object(driver, id, OBJECT_CONTEXT);
    VAStatus status = context != NULL ? VA_STATUS_SUCCESS : VA_STATUS_ERROR_INVALID_CONTEXT;
    for (int i = 0; i < count && status == VA_STATUS_SUCCESS; i++) {
        struct buffer* buffer = find_object(driver, buffers[i], OBJECT_BUFFER);
        status = buffer != NULL ? render_buffer(context, buffer) : VA_STATUS_ERROR_INVALID_BUFFER;
    }
    pthread_mutex_unlock(&driver->mutex);
    return status;
}

static VAStatus dnx_EndPicture(VADriverContextP ctx, VAContextID id) {
    struct driver* driver = DRIVER(ctx);
    pthread_mutex_lock(&driver->mutex);
    struct context* context = find_object(driver, id, OBJECT_CONTEXT);
    struct surface* surface = context != NULL ? find_object(driver, context->target, OBJECT_SURFACE) : NULL;
    VAStatus status = context == NULL   ? VA_STATUS_ERROR_INVALID_CONTEXT
                      : surface == NULL ? VA_STATUS_ERROR_INVALID_SURFACE
                                        : vp9_end_picture(driver, context, surface);
    pthread_mutex_unlock(&driver->mutex);
    return status;
}

/* --- Images (CPU access to frames, for apps that copy them) ------------------ */

static const VAImageFormat nv12_format = {VA_FOURCC_NV12, VA_LSB_FIRST, 12, 0, 0, 0, 0, 0, {0}};

static VAStatus dnx_QueryImageFormats(VADriverContextP ctx, VAImageFormat* list, int* count) {
    (void)ctx;
    list[0] = nv12_format;
    *count = 1;
    return VA_STATUS_SUCCESS;
}

static VAStatus create_image(struct driver* driver, unsigned width, unsigned height, const uint32_t pitches[2],
                             const uint32_t offsets[2], unsigned size, VASurfaceID derived_from, VAImage* out) {
    struct image* image = calloc(1, sizeof(*image));
    if (image == NULL) return VA_STATUS_ERROR_ALLOCATION_FAILED;
    image->derived_from = derived_from;
    VAImage* v = &image->image;
    v->format = nv12_format;
    v->width = (uint16_t)width;
    v->height = (uint16_t)height;
    v->data_size = size;
    v->num_planes = 2;
    v->pitches[0] = pitches[0];
    v->pitches[1] = pitches[1];
    v->offsets[0] = offsets[0];
    v->offsets[1] = offsets[1];

    struct buffer* buffer = calloc(1, sizeof(*buffer));
    if (buffer == NULL) {
        free(image);
        return VA_STATUS_ERROR_ALLOCATION_FAILED;
    }
    buffer->type = VAImageBufferType;
    buffer->size = size;
    buffer->count = 1;
    buffer->derived = VA_INVALID_ID;
    if (derived_from == VA_INVALID_ID && (buffer->data = malloc(size)) == NULL) {
        free(buffer);
        free(image);
        return VA_STATUS_ERROR_ALLOCATION_FAILED;
    }
    v->buf = add_object(driver, OBJECT_BUFFER, buffer);
    v->image_id = v->buf != VA_INVALID_ID ? add_object(driver, OBJECT_IMAGE, image) : VA_INVALID_ID;
    if (v->image_id == VA_INVALID_ID) {
        if (v->buf != VA_INVALID_ID) remove_object(driver, v->buf);
        free(buffer->data);
        free(buffer);
        free(image);
        return VA_STATUS_ERROR_ALLOCATION_FAILED;
    }
    if (derived_from != VA_INVALID_ID) buffer->derived = v->image_id;
    *out = *v;
    return VA_STATUS_SUCCESS;
}

static VAStatus dnx_CreateImage(VADriverContextP ctx, VAImageFormat* format, int width, int height, VAImage* out) {
    if (format->fourcc != VA_FOURCC_NV12) return VA_STATUS_ERROR_INVALID_IMAGE_FORMAT;
    if (width <= 0 || height <= 0 || width > MAX_SIZE || height > MAX_SIZE) return VA_STATUS_ERROR_INVALID_PARAMETER;
    uint32_t pitch = ((uint32_t)width + 1) & ~1u;
    uint32_t pitches[2] = {pitch, pitch};
    uint32_t offsets[2] = {0, pitch * (uint32_t)height};
    unsigned size = offsets[1] + pitch * (((uint32_t)height + 1) / 2);
    struct driver* driver = DRIVER(ctx);
    pthread_mutex_lock(&driver->mutex);
    VAStatus status = create_image(driver, (unsigned)width, (unsigned)height, pitches, offsets, size, VA_INVALID_ID, out);
    pthread_mutex_unlock(&driver->mutex);
    return status;
}

static VAStatus dnx_DeriveImage(VADriverContextP ctx, VASurfaceID id, VAImage* out) {
    VAStatus status = sync_surface(ctx, id, SYNC_TIMEOUT_MS);
    if (status != VA_STATUS_SUCCESS && status != VA_STATUS_ERROR_DECODING_ERROR) return status;
    struct driver* driver = DRIVER(ctx);
    pthread_mutex_lock(&driver->mutex);
    struct surface* surface = find_object(driver, id, OBJECT_SURFACE);
    if (surface == NULL || surface->fd < 0) {
        DEBUG("derive image: surface %u has no frame", id);
        status = VA_STATUS_ERROR_INVALID_SURFACE;
    } else {
        const dnx_video_frame* f = &surface->frame;
        status = create_image(driver, surface->width, surface->height, f->pitches, f->offsets, (unsigned)f->size, id, out);
    }
    pthread_mutex_unlock(&driver->mutex);
    return status;
}

/* Maps the frame a derived image shows. Called with the driver's mutex. */
static VAStatus map_derived(struct driver* driver, struct image* image) {
    if (image->mapping != NULL) return VA_STATUS_SUCCESS;
    struct surface* surface = find_object(driver, image->derived_from, OBJECT_SURFACE);
    if (surface == NULL || surface->fd < 0) return VA_STATUS_ERROR_INVALID_SURFACE;
    void* mapping = mmap(NULL, (size_t)surface->frame.size, PROT_READ, MAP_SHARED, surface->fd, 0);
    if (mapping == MAP_FAILED) return VA_STATUS_ERROR_OPERATION_FAILED;
    struct dma_buf_sync sync = {DMA_BUF_SYNC_START | DMA_BUF_SYNC_READ};
    ioctl(surface->fd, DMA_BUF_IOCTL_SYNC, &sync);
    image->mapping = mapping;
    image->mapping_size = (size_t)surface->frame.size;
    return VA_STATUS_SUCCESS;
}

static void unmap_derived(struct driver* driver, struct image* image) {
    if (image->mapping == NULL) return;
    struct surface* surface = find_object(driver, image->derived_from, OBJECT_SURFACE);
    if (surface != NULL && surface->fd >= 0) {
        struct dma_buf_sync sync = {DMA_BUF_SYNC_END | DMA_BUF_SYNC_READ};
        ioctl(surface->fd, DMA_BUF_IOCTL_SYNC, &sync);
    }
    munmap(image->mapping, image->mapping_size);
    image->mapping = NULL;
}

static VAStatus dnx_DestroyImage(VADriverContextP ctx, VAImageID id) {
    struct driver* driver = DRIVER(ctx);
    pthread_mutex_lock(&driver->mutex);
    struct image* image = find_object(driver, id, OBJECT_IMAGE);
    if (image != NULL) {
        unmap_derived(driver, image);
        destroy_buffer(driver, image->image.buf);
        remove_object(driver, id);
        free(image);
    }
    pthread_mutex_unlock(&driver->mutex);
    return image != NULL ? VA_STATUS_SUCCESS : VA_STATUS_ERROR_INVALID_IMAGE;
}

static VAStatus dnx_GetImage(VADriverContextP ctx, VASurfaceID surface_id, int x, int y, unsigned width,
                             unsigned height, VAImageID image_id) {
    VAStatus status = sync_surface(ctx, surface_id, SYNC_TIMEOUT_MS);
    DEBUG("get image of surface %u: sync %d", surface_id, status);
    if (status != VA_STATUS_SUCCESS && status != VA_STATUS_ERROR_DECODING_ERROR) return status;
    struct driver* driver = DRIVER(ctx);
    pthread_mutex_lock(&driver->mutex);
    struct surface* surface = find_object(driver, surface_id, OBJECT_SURFACE);
    struct image* image = find_object(driver, image_id, OBJECT_IMAGE);
    struct buffer* buffer = image != NULL ? find_object(driver, image->image.buf, OBJECT_BUFFER) : NULL;
    if (surface == NULL || surface->fd < 0) {
        DEBUG("get image: surface %u has no frame", surface_id);
        status = VA_STATUS_ERROR_INVALID_SURFACE;
    } else if (buffer == NULL || buffer->data == NULL) {
        status = VA_STATUS_ERROR_INVALID_IMAGE;
    } else if (x < 0 || y < 0 || (x | y) & 1 || x + width > image->image.width || y + height > image->image.height ||
               x + width > surface->frame.width || y + height > surface->frame.height) {
        status = VA_STATUS_ERROR_INVALID_PARAMETER;
    } else {
        const dnx_video_frame* f = &surface->frame;
        uint8_t* src = mmap(NULL, (size_t)f->size, PROT_READ, MAP_SHARED, surface->fd, 0);
        if (src == MAP_FAILED) {
            status = VA_STATUS_ERROR_OPERATION_FAILED;
        } else {
            struct dma_buf_sync sync = {DMA_BUF_SYNC_START | DMA_BUF_SYNC_READ};
            ioctl(surface->fd, DMA_BUF_IOCTL_SYNC, &sync);
            const VAImage* v = &image->image;
            uint8_t* dst = buffer->data;
            for (unsigned row = 0; row < height; row++) {
                memcpy(dst + v->offsets[0] + (size_t)row * v->pitches[0],
                       src + f->offsets[0] + (size_t)(y + row) * f->pitches[0] + x, width);
            }
            for (unsigned row = 0; row < (height + 1) / 2; row++) {
                memcpy(dst + v->offsets[1] + (size_t)row * v->pitches[1],
                       src + f->offsets[1] + (size_t)(y / 2 + row) * f->pitches[1] + x, (width + 1) & ~1u);
            }
            sync.flags = DMA_BUF_SYNC_END | DMA_BUF_SYNC_READ;
            ioctl(surface->fd, DMA_BUF_IOCTL_SYNC, &sync);
            munmap(src, (size_t)f->size);
        }
    }
    pthread_mutex_unlock(&driver->mutex);
    return status;
}

/* --- Everything else: not supported ------------------------------------------ */

static VAStatus dnx_Terminate(VADriverContextP ctx) {
    struct driver* driver = DRIVER(ctx);
    for (unsigned i = 0; i < driver->count; i++) {
        struct object* o = &driver->objects[i];
        if (o->type == OBJECT_CONTEXT) dnx_DestroyContext(ctx, i + 1);
    }
    for (unsigned i = 0; i < driver->count; i++) {
        struct object* o = &driver->objects[i];
        if (o->type == OBJECT_IMAGE) {
            dnx_DestroyImage(ctx, i + 1);
        } else if (o->type == OBJECT_SURFACE) {
            VASurfaceID id = i + 1;
            dnx_DestroySurfaces(ctx, &id, 1);
        }
    }
    for (unsigned i = 0; i < driver->count; i++) {
        struct object* o = &driver->objects[i];
        if (o->type == OBJECT_BUFFER) {
            destroy_buffer(driver, i + 1);
        } else if (o->type == OBJECT_CONFIG) {
            free(o->data);
        }
    }
    free(driver->objects);
    pthread_mutex_destroy(&driver->mutex);
    free(driver);
    ctx->pDriverData = NULL;
    return VA_STATUS_SUCCESS;
}

#pragma GCC diagnostic push
#pragma GCC diagnostic ignored "-Wunused-parameter"
#define UNSUPPORTED(name, ...)                         \
    static VAStatus dnx_##name(__VA_ARGS__) { return VA_STATUS_ERROR_UNIMPLEMENTED; }

UNSUPPORTED(PutSurface, VADriverContextP c, VASurfaceID s, void* d, short a, short b, unsigned short e,
            unsigned short f, short g, short h, unsigned short i, unsigned short j, VARectangle* k, unsigned l,
            unsigned m)
UNSUPPORTED(SetImagePalette, VADriverContextP c, VAImageID i, unsigned char* p)
UNSUPPORTED(PutImage, VADriverContextP c, VASurfaceID s, VAImageID i, int a, int b, unsigned d, unsigned e, int f,
            int g, unsigned h, unsigned j)
UNSUPPORTED(CreateSubpicture, VADriverContextP c, VAImageID i, VASubpictureID* s)
UNSUPPORTED(DestroySubpicture, VADriverContextP c, VASubpictureID s)
UNSUPPORTED(SetSubpictureImage, VADriverContextP c, VASubpictureID s, VAImageID i)
UNSUPPORTED(SetSubpictureChromakey, VADriverContextP c, VASubpictureID s, unsigned a, unsigned b, unsigned d)
UNSUPPORTED(SetSubpictureGlobalAlpha, VADriverContextP c, VASubpictureID s, float a)
UNSUPPORTED(AssociateSubpicture, VADriverContextP c, VASubpictureID s, VASurfaceID* t, int n, short a, short b,
            unsigned short d, unsigned short e, short f, short g, unsigned short h, unsigned short i, unsigned j)
UNSUPPORTED(DeassociateSubpicture, VADriverContextP c, VASubpictureID s, VASurfaceID* t, int n)
UNSUPPORTED(GetDisplayAttributes, VADriverContextP c, VADisplayAttribute* a, int n)
UNSUPPORTED(SetDisplayAttributes, VADriverContextP c, VADisplayAttribute* a, int n)
UNSUPPORTED(LockSurface, VADriverContextP c, VASurfaceID s, unsigned* a, unsigned* b, unsigned* d, unsigned* e,
            unsigned* f, unsigned* g, unsigned* h, unsigned* i, void** j)
UNSUPPORTED(UnlockSurface, VADriverContextP c, VASurfaceID s)
#pragma GCC diagnostic pop

static VAStatus dnx_QuerySubpictureFormats(VADriverContextP ctx, VAImageFormat* list, unsigned* flags, unsigned* count) {
    (void)ctx;
    (void)list;
    (void)flags;
    *count = 0;
    return VA_STATUS_SUCCESS;
}

static VAStatus dnx_QueryDisplayAttributes(VADriverContextP ctx, VADisplayAttribute* list, int* count) {
    (void)ctx;
    (void)list;
    *count = 0;
    return VA_STATUS_SUCCESS;
}

__attribute__((visibility("default"))) VAStatus __vaDriverInit_1_0(VADriverContextP ctx);

VAStatus __vaDriverInit_1_0(VADriverContextP ctx) {
    struct driver* driver = calloc(1, sizeof(*driver));
    if (driver == NULL) return VA_STATUS_ERROR_ALLOCATION_FAILED;
    pthread_mutex_init(&driver->mutex, NULL);
    ctx->pDriverData = driver;
    ctx->version_major = VA_MAJOR_VERSION;
    ctx->version_minor = VA_MINOR_VERSION;
    ctx->max_profiles = sizeof(profiles) / sizeof(profiles[0]);
    ctx->max_entrypoints = 1;
    ctx->max_attributes = 1;
    ctx->max_image_formats = 1;
    ctx->max_subpic_formats = 1;
    ctx->max_display_attributes = 1;
    ctx->str_vendor = "DroidNux VA-API driver over Android MediaCodec";

    struct VADriverVTable* v = ctx->vtable;
    v->vaTerminate = dnx_Terminate;
    v->vaQueryConfigProfiles = dnx_QueryConfigProfiles;
    v->vaQueryConfigEntrypoints = dnx_QueryConfigEntrypoints;
    v->vaGetConfigAttributes = dnx_GetConfigAttributes;
    v->vaCreateConfig = dnx_CreateConfig;
    v->vaDestroyConfig = dnx_DestroyConfig;
    v->vaQueryConfigAttributes = dnx_QueryConfigAttributes;
    v->vaCreateSurfaces = dnx_CreateSurfaces;
    v->vaCreateSurfaces2 = dnx_CreateSurfaces2;
    v->vaDestroySurfaces = dnx_DestroySurfaces;
    v->vaCreateContext = dnx_CreateContext;
    v->vaDestroyContext = dnx_DestroyContext;
    v->vaCreateBuffer = dnx_CreateBuffer;
    v->vaBufferSetNumElements = dnx_BufferSetNumElements;
    v->vaMapBuffer = dnx_MapBuffer;
    v->vaUnmapBuffer = dnx_UnmapBuffer;
    v->vaDestroyBuffer = dnx_DestroyBuffer;
    v->vaBufferInfo = dnx_BufferInfo;
    v->vaBeginPicture = dnx_BeginPicture;
    v->vaRenderPicture = dnx_RenderPicture;
    v->vaEndPicture = dnx_EndPicture;
    v->vaSyncSurface = dnx_SyncSurface;
    v->vaSyncSurface2 = dnx_SyncSurface2;
    v->vaQuerySurfaceStatus = dnx_QuerySurfaceStatus;
    v->vaQuerySurfaceAttributes = dnx_QuerySurfaceAttributes;
    v->vaExportSurfaceHandle = dnx_ExportSurfaceHandle;
    v->vaPutSurface = dnx_PutSurface;
    v->vaQueryImageFormats = dnx_QueryImageFormats;
    v->vaCreateImage = dnx_CreateImage;
    v->vaDeriveImage = dnx_DeriveImage;
    v->vaDestroyImage = dnx_DestroyImage;
    v->vaSetImagePalette = dnx_SetImagePalette;
    v->vaGetImage = dnx_GetImage;
    v->vaPutImage = dnx_PutImage;
    v->vaQuerySubpictureFormats = dnx_QuerySubpictureFormats;
    v->vaCreateSubpicture = dnx_CreateSubpicture;
    v->vaDestroySubpicture = dnx_DestroySubpicture;
    v->vaSetSubpictureImage = dnx_SetSubpictureImage;
    v->vaSetSubpictureChromakey = dnx_SetSubpictureChromakey;
    v->vaSetSubpictureGlobalAlpha = dnx_SetSubpictureGlobalAlpha;
    v->vaAssociateSubpicture = dnx_AssociateSubpicture;
    v->vaDeassociateSubpicture = dnx_DeassociateSubpicture;
    v->vaQueryDisplayAttributes = dnx_QueryDisplayAttributes;
    v->vaGetDisplayAttributes = dnx_GetDisplayAttributes;
    v->vaSetDisplayAttributes = dnx_SetDisplayAttributes;
    v->vaLockSurface = dnx_LockSurface;
    v->vaUnlockSurface = dnx_UnlockSurface;
    return VA_STATUS_SUCCESS;
}
