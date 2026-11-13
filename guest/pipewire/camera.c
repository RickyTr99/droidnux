/*
 * The device's cameras as PipeWire nodes, part of module-droidnux (see there
 * for how it is built and loaded): a Video/Source with media.role Camera for
 * each camera of the app's camera service (display/src/main/cpp/core/
 * camera_input.cpp), protocol in camera_protocol.h. xdg-desktop-portal's
 * Camera portal offers them to browsers; OBS and GStreamer's pipewiresrc take
 * them directly.
 *
 * A node offers the camera's sizes, in NV12 or I420. While a client streams
 * from it, the app keeps the camera open and writes each frame into a shared
 * ring; the node drives its graph by the frames, one cycle per frame, as a
 * webcam's driver does.
 */

#include <errno.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/mman.h>
#include <sys/socket.h>
#include <sys/syscall.h>
#include <sys/un.h>
#include <unistd.h>

#include <spa/buffer/meta.h>
#include <spa/param/buffers.h>
#include <spa/param/video/format-utils.h>
#include <spa/pod/builder.h>
#include <spa/utils/result.h>

#include <pipewire/impl.h>

#include "camera_protocol.h"

PW_LOG_TOPIC_STATIC(camera_topic, "mod.droidnux.camera");
#define PW_LOG_TOPIC_DEFAULT camera_topic

struct camera {
    struct pw_impl_module* module;
    struct pw_context* context;
    struct pw_core* core;
    bool do_disconnect;
    struct spa_hook module_listener;
    struct pw_stream* stream;
    struct spa_hook stream_listener;
    struct spa_source* sock_source;
    int sock;
    uint32_t index;
    dnx_camera_info info;

    /* The negotiated format. */
    uint32_t width, height, fps, format;
    /* While streaming: the ring of the app's frames. */
    dnx_camera_ring* ring;
    size_t ring_size;
    uint32_t sequence; /* the last frame given to the graph */
};

static uint32_t frame_size(const struct camera* camera) {
    return (uint32_t) DNX_CAMERA_FRAME_SIZE(camera->width, camera->height);
}

static void send_stop(struct camera* camera) {
    dnx_camera_header header = {DNX_CAMERA_STOP, 0};
    send(camera->sock, &header, sizeof(header), MSG_NOSIGNAL);
}

static void unmap_ring(struct camera* camera) {
    if (camera->ring) munmap(camera->ring, camera->ring_size);
    camera->ring = NULL;
}

/* A new ring for the negotiated format, handed to the app with START. */
static int start_camera(struct camera* camera) {
    int memfd, res = 0;
    unmap_ring(camera);
    camera->ring_size = DNX_CAMERA_RING_SIZE(camera->width, camera->height);
    /* No memfd_create() in bionic's headers below API 30. */
    memfd = (int) syscall(SYS_memfd_create, "droidnux-camera", 1u /* MFD_CLOEXEC */);
    if (memfd < 0 || ftruncate(memfd, (off_t) camera->ring_size) < 0) {
        res = -errno;
        goto out;
    }
    camera->ring = mmap(NULL, camera->ring_size, PROT_READ | PROT_WRITE, MAP_SHARED, memfd, 0);
    if (camera->ring == MAP_FAILED) {
        camera->ring = NULL;
        res = -errno;
        goto out;
    }
    camera->ring->magic = DNX_CAMERA_MAGIC;
    camera->ring->version = DNX_CAMERA_VERSION;
    camera->ring->width = camera->width;
    camera->ring->height = camera->height;
    camera->ring->format = camera->format;
    camera->ring->slots = DNX_CAMERA_SLOTS;
    camera->ring->slot_size = frame_size(camera);
    camera->sequence = 0;

    struct {
        dnx_camera_header header;
        dnx_camera_start start;
    } message = {{DNX_CAMERA_START, sizeof(dnx_camera_start)},
                 {camera->width, camera->height, camera->fps, camera->format}};
    union {
        struct cmsghdr align;
        char buf[CMSG_SPACE(sizeof(int))];
    } control;
    struct iovec iov = {&message, sizeof(message)};
    struct msghdr msg = {.msg_iov = &iov, .msg_iovlen = 1, .msg_control = control.buf,
                         .msg_controllen = sizeof(control.buf)};
    memset(&control, 0, sizeof(control));
    struct cmsghdr* cmsg = CMSG_FIRSTHDR(&msg);
    cmsg->cmsg_level = SOL_SOCKET;
    cmsg->cmsg_type = SCM_RIGHTS;
    cmsg->cmsg_len = CMSG_LEN(sizeof(int));
    memcpy(CMSG_DATA(cmsg), &memfd, sizeof(int));
    if (sendmsg(camera->sock, &msg, MSG_NOSIGNAL) != (ssize_t) sizeof(message)) res = -errno;
out:
    if (memfd >= 0) close(memfd);
    if (res < 0) {
        pw_log_error("camera %u: cannot start: %s", camera->index, spa_strerror(res));
        unmap_ring(camera);
    } else {
        pw_log_info("camera %u: %ux%u at %u fps", camera->index, camera->width, camera->height, camera->fps);
    }
    return res;
}

/* The app's messages: a frame (a cycle of the graph) or why the camera would not open. */
static void on_app_message(void* data, int fd, uint32_t mask) {
    struct camera* camera = data;
    if (mask & (SPA_IO_ERR | SPA_IO_HUP)) {
        pw_log_warn("camera %u: the app closed the connection", camera->index);
        pw_loop_destroy_source(pw_context_get_main_loop(camera->context), camera->sock_source);
        camera->sock_source = NULL;
        return;
    }
    for (;;) {
        struct {
            dnx_camera_header header;
            dnx_camera_error error;
        } message;
        ssize_t n = recv(fd, &message.header, sizeof(message.header), MSG_DONTWAIT);
        if (n != (ssize_t) sizeof(message.header)) return;
        if (message.header.type == DNX_CAMERA_ERROR && message.header.size == sizeof(dnx_camera_error)) {
            if (recv(fd, &message.error, sizeof(message.error), MSG_WAITALL) != (ssize_t) sizeof(message.error)) return;
            pw_log_warn("camera %u: %s", camera->index,
                        message.error.code == DNX_CAMERA_DENIED ? "the user did not allow the camera"
                                                                 : "the camera is not available");
        } else if (message.header.type == DNX_CAMERA_FRAME && message.header.size == 0) {
            if (camera->ring && camera->stream) pw_stream_trigger_process(camera->stream);
        } else {
            pw_log_warn("camera %u: unexpected message %u", camera->index, message.header.type);
            return;
        }
    }
}

static void stream_process(void* data) {
    struct camera* camera = data;
    struct pw_buffer* buffer = pw_stream_dequeue_buffer(camera->stream);
    if (buffer == NULL) return;
    struct spa_buffer* buf = buffer->buffer;
    struct spa_data* d = &buf->datas[0];
    uint32_t size = frame_size(camera);
    d->chunk->offset = 0;
    d->chunk->size = 0;
    d->chunk->stride = (int32_t) camera->width;
    d->chunk->flags = SPA_CHUNK_FLAG_CORRUPTED;
    dnx_camera_ring* ring = camera->ring;
    uint32_t sequence = ring ? __atomic_load_n(&ring->sequence, __ATOMIC_ACQUIRE) : 0;
    if (ring && d->data && d->maxsize >= size && sequence != 0 && sequence != camera->sequence) {
        uint32_t slot = (sequence - 1) % DNX_CAMERA_SLOTS;
        memcpy(d->data, (uint8_t*) ring + DNX_CAMERA_RING_DATA + (uint64_t) slot * size, size);
        /* The app went round the ring meanwhile: the copy may be torn. */
        if (__atomic_load_n(&ring->sequence, __ATOMIC_ACQUIRE) - sequence < DNX_CAMERA_SLOTS - 1) {
            d->chunk->size = size;
            d->chunk->flags = 0;
        }
        struct spa_meta_header* header = spa_buffer_find_meta_data(buf, SPA_META_Header, sizeof(*header));
        if (header) {
            header->flags = 0;
            header->offset = 0;
            header->pts = (int64_t) __atomic_load_n(&ring->timestamp[slot], __ATOMIC_RELAXED);
            header->dts_offset = 0;
            header->seq = sequence - 1;
        }
        camera->sequence = sequence;
    }
    pw_stream_queue_buffer(camera->stream, buffer);
}

static void stream_param_changed(void* data, uint32_t id, const struct spa_pod* param) {
    struct camera* camera = data;
    struct spa_video_info_raw info;
    if (id != SPA_PARAM_Format || param == NULL) return;
    if (spa_format_video_raw_parse(param, &info) < 0) return;
    camera->width = info.size.width;
    camera->height = info.size.height;
    camera->format = info.format == SPA_VIDEO_FORMAT_I420 ? DNX_CAMERA_I420 : DNX_CAMERA_NV12;
    camera->fps = info.framerate.denom ? (info.framerate.num + info.framerate.denom - 1) / info.framerate.denom : 30;
    for (uint32_t i = 0; i < camera->info.sizes; i++) {
        const dnx_camera_size* s = &camera->info.size[i];
        if (s->width == camera->width && s->height == camera->height) camera->fps = SPA_CLAMP(camera->fps, 1u, s->fps);
    }

    uint8_t buffer[1024];
    struct spa_pod_builder b = SPA_POD_BUILDER_INIT(buffer, sizeof(buffer));
    const struct spa_pod* params[2];
    params[0] = spa_pod_builder_add_object(
        &b, SPA_TYPE_OBJECT_ParamBuffers, SPA_PARAM_Buffers,
        SPA_PARAM_BUFFERS_buffers, SPA_POD_CHOICE_RANGE_Int(4, 2, 8),
        SPA_PARAM_BUFFERS_blocks, SPA_POD_Int(1),
        SPA_PARAM_BUFFERS_size, SPA_POD_Int((int32_t) frame_size(camera)),
        SPA_PARAM_BUFFERS_stride, SPA_POD_Int((int32_t) camera->width),
        SPA_PARAM_BUFFERS_dataType, SPA_POD_CHOICE_FLAGS_Int((1 << SPA_DATA_MemFd) | (1 << SPA_DATA_MemPtr)));
    params[1] = spa_pod_builder_add_object(
        &b, SPA_TYPE_OBJECT_ParamMeta, SPA_PARAM_Meta,
        SPA_PARAM_META_type, SPA_POD_Id(SPA_META_Header),
        SPA_PARAM_META_size, SPA_POD_Int(sizeof(struct spa_meta_header)));
    pw_stream_update_params(camera->stream, params, 2);
}

static void stream_state_changed(void* data, enum pw_stream_state old, enum pw_stream_state state,
                                 const char* error) {
    struct camera* camera = data;
    (void) old;
    if (state == PW_STREAM_STATE_STREAMING) {
        if (!camera->ring) start_camera(camera);
        return;
    }
    if (camera->ring) {
        send_stop(camera);
        unmap_ring(camera);
        pw_log_info("camera %u: stopped", camera->index);
    }
    if (state == PW_STREAM_STATE_ERROR) pw_log_error("camera %u: %s", camera->index, error ? error : "");
}

static void stream_destroy(void* data) {
    struct camera* camera = data;
    spa_hook_remove(&camera->stream_listener);
    camera->stream = NULL;
}

static const struct pw_stream_events stream_events = {
    PW_VERSION_STREAM_EVENTS,
    .destroy = stream_destroy,
    .state_changed = stream_state_changed,
    .param_changed = stream_param_changed,
    .process = stream_process,
};

/* Connects to the app's camera service for camera `index` and reads its INFO. */
static int connect_app(struct camera* camera) {
    struct sockaddr_un address = {.sun_family = AF_UNIX};
    strcpy(address.sun_path, DNX_CAMERA_SOCKET);
    camera->sock = socket(AF_UNIX, SOCK_STREAM | SOCK_CLOEXEC, 0);
    if (camera->sock < 0 || connect(camera->sock, (struct sockaddr*) &address, sizeof(address)) < 0) {
        int res = -errno;
        pw_log_warn("cannot connect to %s: %s", DNX_CAMERA_SOCKET, spa_strerror(res));
        return res;
    }
    struct {
        dnx_camera_header header;
        dnx_camera_hello hello;
    } hello = {{DNX_CAMERA_HELLO, sizeof(dnx_camera_hello)}, {DNX_CAMERA_MAGIC, DNX_CAMERA_VERSION, camera->index}};
    struct {
        dnx_camera_header header;
        dnx_camera_info info;
    } answer;
    if (send(camera->sock, &hello, sizeof(hello), MSG_NOSIGNAL) != (ssize_t) sizeof(hello) ||
        recv(camera->sock, &answer, sizeof(answer), MSG_WAITALL) != (ssize_t) sizeof(answer) ||
        answer.header.type != DNX_CAMERA_INFO || answer.header.size != sizeof(dnx_camera_info) ||
        answer.info.sizes > DNX_CAMERA_MAX_SIZES) {
        pw_log_error("camera %u: no answer from the app", camera->index);
        return -EPROTO;
    }
    camera->info = answer.info;
    return 0;
}

static int create_stream(struct camera* camera, uint32_t nth) {
    char name[64], description[64];
    const char* facing = camera->info.facing == DNX_CAMERA_FRONT  ? "Front"
                         : camera->info.facing == DNX_CAMERA_BACK ? "Back"
                                                                  : "External";
    snprintf(name, sizeof(name), "droidnux-camera-%u", camera->index);
    if (nth > 1)
        snprintf(description, sizeof(description), "%s camera %u", facing, nth);
    else
        snprintf(description, sizeof(description), "%s camera", facing);
    struct pw_properties* props = pw_properties_new(
        PW_KEY_MEDIA_CLASS, "Video/Source",
        PW_KEY_MEDIA_TYPE, "Video",
        PW_KEY_MEDIA_CATEGORY, "Capture",
        PW_KEY_MEDIA_ROLE, "Camera",
        PW_KEY_NODE_NAME, name,
        PW_KEY_NODE_DESCRIPTION, description,
        /* The front camera first: the one video calls want. */
        "priority.session", camera->info.facing == DNX_CAMERA_FRONT ? "1000" : "900",
        NULL);
    if (props == NULL) return -errno;
    camera->stream = pw_stream_new(camera->core, name, props);
    if (camera->stream == NULL) return -errno;
    pw_stream_add_listener(camera->stream, &camera->stream_listener, &stream_events, camera);

    uint8_t buffer[8192];
    struct spa_pod_builder b = SPA_POD_BUILDER_INIT(buffer, sizeof(buffer));
    const struct spa_pod* params[DNX_CAMERA_MAX_SIZES];
    uint32_t count = 0;
    for (uint32_t i = 0; i < camera->info.sizes; i++) {
        const dnx_camera_size* s = &camera->info.size[i];
        params[count++] = spa_pod_builder_add_object(
            &b, SPA_TYPE_OBJECT_Format, SPA_PARAM_EnumFormat,
            SPA_FORMAT_mediaType, SPA_POD_Id(SPA_MEDIA_TYPE_video),
            SPA_FORMAT_mediaSubtype, SPA_POD_Id(SPA_MEDIA_SUBTYPE_raw),
            SPA_FORMAT_VIDEO_format, SPA_POD_CHOICE_ENUM_Id(3, SPA_VIDEO_FORMAT_NV12, SPA_VIDEO_FORMAT_NV12,
                                                            SPA_VIDEO_FORMAT_I420),
            SPA_FORMAT_VIDEO_size, SPA_POD_Rectangle(&SPA_RECTANGLE(s->width, s->height)),
            SPA_FORMAT_VIDEO_framerate, SPA_POD_CHOICE_RANGE_Fraction(&SPA_FRACTION(s->fps, 1), &SPA_FRACTION(1, 1),
                                                                      &SPA_FRACTION(s->fps, 1)));
    }
    return pw_stream_connect(camera->stream, PW_DIRECTION_OUTPUT, PW_ID_ANY,
                             PW_STREAM_FLAG_DRIVER | PW_STREAM_FLAG_MAP_BUFFERS, params, count);
}

static void camera_destroy(struct camera* camera) {
    if (camera->stream) pw_stream_destroy(camera->stream);
    if (camera->core && camera->do_disconnect) pw_core_disconnect(camera->core);
    if (camera->sock_source) pw_loop_destroy_source(pw_context_get_main_loop(camera->context), camera->sock_source);
    unmap_ring(camera);
    if (camera->sock >= 0) close(camera->sock);
    free(camera);
}

static void module_destroy(void* data) {
    struct camera* camera = data;
    spa_hook_remove(&camera->module_listener);
    camera_destroy(camera);
}

static const struct pw_impl_module_events module_events = {
    PW_VERSION_IMPL_MODULE_EVENTS,
    .destroy = module_destroy,
};

/* A node for camera `index`; how many cameras there are in `count`, and how many face each way in `seen`. */
static int create_camera(struct pw_impl_module* module, uint32_t index, uint32_t* count, uint32_t seen[3]) {
    struct camera* camera = calloc(1, sizeof(*camera));
    int res;
    if (camera == NULL) return -errno;
    camera->module = module;
    camera->context = pw_impl_module_get_context(module);
    camera->index = index;
    camera->sock = -1;
    if ((res = connect_app(camera)) < 0) goto error;
    *count = camera->info.count;
    if (camera->info.sizes == 0) {
        res = -ENODEV;
        goto error;
    }
    camera->core = pw_context_get_object(camera->context, PW_TYPE_INTERFACE_Core);
    if (camera->core == NULL) {
        camera->core = pw_context_connect(camera->context, NULL, 0);
        camera->do_disconnect = true;
    }
    if (camera->core == NULL) {
        res = -errno;
        goto error;
    }
    if ((res = create_stream(camera, ++seen[SPA_MIN(camera->info.facing, 2u)])) < 0) goto error;
    camera->sock_source = pw_loop_add_io(pw_context_get_main_loop(camera->context), camera->sock, SPA_IO_IN, false,
                                         on_app_message, camera);
    if (camera->sock_source == NULL) {
        res = -errno;
        goto error;
    }
    pw_impl_module_add_listener(module, &camera->module_listener, &module_events, camera);
    pw_log_info("camera %u: %s, %u sizes up to %ux%u", camera->index,
                camera->info.facing == DNX_CAMERA_FRONT ? "front" : "back", camera->info.sizes,
                camera->info.size[0].width, camera->info.size[0].height);
    return 0;
error:
    camera_destroy(camera);
    return res;
}

/* Called by module-droidnux's init: a node for each of the device's cameras. */
int dnx_camera_nodes(struct pw_impl_module* module) {
    uint32_t count = 0, seen[3] = {0, 0, 0};
    PW_LOG_TOPIC_INIT(camera_topic);
    int res = create_camera(module, 0, &count, seen);
    for (uint32_t i = 1; i < count; i++) create_camera(module, i, &count, seen);
    return count ? 0 : res;
}
