/*
 * PipeWire module for the container: an Audio/Sink that plays through the
 * app's AAudio output and an Audio/Source that records the device's
 * microphone (display/src/main/cpp/core/audio_output.cpp), protocol in
 * audio_protocol.h. Each node has its own connection and ring.
 *
 * A node drives the guest's graph with a timer, as a sound card does with its
 * interrupt. The AAudio callback drains (fills) the shared ring at the
 * device's clock, and a DLL on the ring's fill corrects the timer, so the
 * graph follows the device's clock with no message per period. The sink is
 * the preferred driver; the source drives only graphs that record without
 * playing. The sink's volume is Android's media volume, as a sound card's
 * hardware volume: PipeWire scales the samples by it as for any node, and the
 * sink scales them back before the ring, so that only Android applies it.
 *
 * Built with the NDK (display/src/main/cpp/CMakeLists.txt) as
 * libpipewire-module-droidnux.so, but loaded by the guest's PipeWire under
 * glibc: it links to nothing, and its undefined symbols (libpipewire's, and
 * libc functions whose ABI matches bionic's) are resolved at load time. Not
 * there: bionic's __errno (see below), fortify, the stack protector and libm
 * (the DLL avoids exp()). For quick tests it also builds in the container:
 *   gcc -O2 -shared -fPIC -I<dir of audio_protocol.h> -I/usr/include/pipewire-0.3 \
 *       -I/usr/include/spa-0.2 module-droidnux.c -o libpipewire-module-droidnux.so
 *
 * Loaded by the pipewire.conf.d file that DesktopEnvironment writes.
 */

#include <errno.h>
#include <math.h>
#include <stdlib.h>
#include <string.h>
#include <sys/mman.h>
#include <sys/socket.h>
#include <sys/syscall.h>
#include <sys/un.h>
#include <time.h>
#include <unistd.h>

#include <spa/param/audio/format-utils.h>
#include <spa/param/latency-utils.h>
#include <spa/param/props.h>
#include <spa/pod/iter.h>
#include <spa/utils/result.h>

#include <pipewire/impl.h>

#include "audio_protocol.h"

#ifdef __BIONIC__
/* bionic's errno is *__errno(); glibc only has __errno_location(). */
extern int* __errno_location(void);
__attribute__((visibility("hidden"))) int* __errno(void) {
    return __errno_location();
}
#endif

#define NAME "droidnux"

PW_LOG_TOPIC_STATIC(mod_topic, "mod." NAME);
#define PW_LOG_TOPIC_DEFAULT mod_topic

#define RATE 48000u
#define RING_FRAMES 8192u /* 170 ms, above PipeWire's largest default quantum */
/* Fill kept in the ring above one device burst at every period: the timer's
 * jitter under proot. */
#define MARGIN_FRAMES (RATE / 200u) /* 5 ms */
/* After START, the first period waits for the device to run (no sound is lost
 * while AAudio starts), but not forever: a device that fails to start must not
 * stop the graph. */
#define START_POLL_NS (5 * SPA_NSEC_PER_MSEC)
#define START_TIMEOUT_NS (500 * SPA_NSEC_PER_MSEC)
/* Largest fill error the DLL sees, in frames, and its bandwidth (spa/utils/dll.h). */
#define MAX_ERROR 256.0
#define DLL_BW 0.016
/* How often the sink checks its latency while it plays, and the change that is
 * worth telling the graph (5 ms; AAudio's own swings by ~25 ms). */
#define LATENCY_CHECK_MS 500
#define LATENCY_STEP_FRAMES (RATE / 200u)

static const struct spa_dict_item module_props[] = {
    {PW_KEY_MODULE_AUTHOR, "DroidNux"},
    {PW_KEY_MODULE_DESCRIPTION, "Audio output and microphone through the DroidNux app (AAudio)"},
    {PW_KEY_MODULE_VERSION, "1"},
};

/* Second-order DLL, as spa/utils/dll.h, which needs exp() from libm. */
struct dll {
    double z1, z2, z3;
    double w0, w1, w2;
    uint32_t period;
};

/* One node: the sink, or the source (capture). */
struct impl {
    bool capture;
    uint32_t channels;
    struct pw_context* context;
    struct pw_impl_module* module;
    struct spa_hook module_listener;
    struct pw_loop* data_loop;

    struct pw_core* core;
    struct spa_hook core_proxy_listener;
    bool do_disconnect;

    struct pw_stream* stream;
    struct spa_hook stream_listener;
    bool running; /* main loop: START sent */
    /* Main loop, sink only: the latency the stream's port reports (frames). */
    struct spa_source* latency_timer;
    uint32_t latency;
    /* Main loop, sink only: messages from the app; the node, bound through a
     * connection of its own to set its volume as the desktop does (neither
     * pw_stream_set_param nor the stream's own connection reach the node's
     * converter); the volume last shown and the one PipeWire applies, which the
     * data loop reads (atomically) to undo it; whether the app's volume still
     * has to be set on the node. */
    struct spa_source* sock_source;
    struct pw_core* volume_core;
    struct pw_registry* registry;
    struct pw_node* node;
    float volume, node_volume;
    bool mute;
    bool volume_pending;

    int sock;
    dnx_audio_ring* ring;
    float* samples;
    size_t ring_size;

    /* Data loop only. */
    struct spa_source* timer;
    struct spa_io_position* position;
    bool active;  /* the timer runs */
    bool started; /* periods tick */
    uint64_t start_time, next_time;
    struct dll dll;
    double corr;
};

static uint64_t now_ns(void) {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return SPA_TIMESPEC_TO_NSEC(&ts);
}

static void dll_init(struct dll* dll) {
    dll->z1 = dll->z2 = dll->z3 = 0.0;
    dll->period = 0;
}

static void dll_set_bw(struct dll* dll, double bw, uint32_t period, uint32_t rate) {
    double w = 2 * M_PI * bw * period / rate;
    /* 1 - exp(-x): x is below 0.35 up to PipeWire's quantum limit (8192),
     * where this series is off by less than 3e-6. */
    double x = 20.0 * w;
    dll->w0 = x * (1 - x / 2 * (1 - x / 3 * (1 - x / 4 * (1 - x / 5))));
    dll->w1 = w * 1.5 / period;
    dll->w2 = w / 1.5;
    dll->period = period;
}

static double dll_update(struct dll* dll, double err) {
    dll->z1 += dll->w0 * (dll->w1 * err - dll->z1);
    dll->z2 += dll->w0 * (dll->z1 - dll->z2);
    dll->z3 += dll->w2 * dll->z2;
    return 1.0 - (dll->z2 + dll->z3);
}

static void send_message(struct impl* impl, uint32_t type) {
    dnx_audio_header header = {type, 0};
    if (impl->sock >= 0 && send(impl->sock, &header, sizeof(header), MSG_NOSIGNAL) != sizeof(header))
        pw_log_warn("cannot reach the app: %m");
}

static void set_timeout(struct impl* impl, uint64_t time) {
    struct timespec value = {(time_t) (time / SPA_NSEC_PER_SEC), (long) (time % SPA_NSEC_PER_SEC)};
    struct timespec interval = {0, 0};
    pw_loop_update_timer(impl->data_loop, impl->timer, &value, &interval, true);
}

static uint32_t frame_size(const struct impl* impl) {
    return impl->channels * (uint32_t) sizeof(float);
}

static uint32_t target_fill(const struct impl* impl) {
    return __atomic_load_n(&impl->ring->burst, __ATOMIC_RELAXED) + MARGIN_FRAMES;
}

/* Data loop: the device runs (or never will). A sink's first period starts
 * with the target fill of silence, a source's with at most that much of the
 * microphone. */
static void begin_periods(struct impl* impl, uint64_t now) {
    uint32_t fill = SPA_MIN(target_fill(impl), RING_FRAMES / 2);
    if (impl->capture) {
        uint32_t write = __atomic_load_n(&impl->ring->write_index, __ATOMIC_ACQUIRE);
        uint32_t read = __atomic_load_n(&impl->ring->read_index, __ATOMIC_RELAXED);
        if (write - read > fill) __atomic_store_n(&impl->ring->read_index, write - fill, __ATOMIC_RELEASE);
    } else {
        uint32_t read = __atomic_load_n(&impl->ring->read_index, __ATOMIC_ACQUIRE);
        for (uint32_t i = 0; i < fill * impl->channels; i++) {
            impl->samples[((read * impl->channels) + i) & (RING_FRAMES * impl->channels - 1)] = 0.0f;
        }
        __atomic_store_n(&impl->ring->write_index, read + fill, __ATOMIC_RELEASE);
    }
    dll_init(&impl->dll);
    impl->corr = 1.0;
    impl->next_time = now;
    impl->started = true;
}

static void on_timeout(void* data, uint64_t expirations) {
    struct impl* impl = data;
    struct spa_io_position* pos = impl->position;
    uint64_t now = now_ns(), duration, current;
    uint32_t rate, burst, fill;
    (void) expirations;

    if (!impl->active) return;
    burst = __atomic_load_n(&impl->ring->burst, __ATOMIC_RELAXED);
    if (!impl->started) {
        if (burst == 0 && now - impl->start_time < START_TIMEOUT_NS) {
            set_timeout(impl, now + START_POLL_NS);
            return;
        }
        if (burst == 0) pw_log_warn("the app's audio output did not start");
        begin_periods(impl, now);
    }

    if (SPA_LIKELY(pos != NULL)) {
        duration = pos->clock.target_duration;
        rate = pos->clock.target_rate.denom;
    } else {
        duration = 1024;
        rate = RATE;
    }
    if (duration == 0 || rate == 0) {
        duration = 1024;
        rate = RATE;
    }

    fill = __atomic_load_n(&impl->ring->write_index, __ATOMIC_RELAXED) -
           __atomic_load_n(&impl->ring->read_index, __ATOMIC_ACQUIRE);
    if (fill > RING_FRAMES) fill = RING_FRAMES;
    /* A node that follows another driver only keeps its timer for when it drives again. */
    if (!pw_stream_is_driving(impl->stream)) {
        impl->next_time = now + duration * SPA_NSEC_PER_SEC / rate;
        set_timeout(impl, impl->next_time);
        return;
    }
    /* Only while the device runs: a stopped one would wind the DLL up. A sink
     * slows down when its ring fills, a source speeds up. */
    if (burst != 0) {
        if (impl->dll.period != duration) dll_set_bw(&impl->dll, DLL_BW, (uint32_t) duration, rate);
        double error = (double) fill - (double) target_fill(impl);
        if (impl->capture) error = -error;
        impl->corr = SPA_CLAMPD(dll_update(&impl->dll, SPA_CLAMPD(error, -MAX_ERROR, MAX_ERROR)), 0.95, 1.05);
    }

    current = impl->next_time;
    impl->next_time += (uint64_t) (duration / impl->corr * 1e9 / rate);
    /* Late by more than a period (the process was stalled): catch up at once
     * rather than with a burst of periods. */
    if (impl->next_time + duration * SPA_NSEC_PER_SEC / rate < now) {
        current = now;
        impl->next_time = now + duration * SPA_NSEC_PER_SEC / rate;
    }

    if (SPA_LIKELY(pos != NULL)) {
        pos->clock.nsec = current;
        pos->clock.rate = pos->clock.target_rate;
        pos->clock.position += pos->clock.duration;
        pos->clock.duration = pos->clock.target_duration;
        pos->clock.delay = impl->capture ? 0 : fill + __atomic_load_n(&impl->ring->latency, __ATOMIC_RELAXED);
        pos->clock.rate_diff = impl->corr;
        pos->clock.next_nsec = impl->next_time;
    }
    set_timeout(impl, impl->next_time);
    pw_stream_trigger_process(impl->stream);
}

/* Data loop: one period of the microphone from the ring, silence for what it
 * does not have. */
static void capture_process(struct impl* impl, struct pw_buffer* buffer) {
    struct spa_data* d = &buffer->buffer->datas[0];
    const uint32_t size = frame_size(impl);
    if (d->data == NULL || d->chunk == NULL) return;
    uint32_t frames = d->maxsize / size;
    if (impl->position != NULL && impl->position->clock.duration > 0)
        frames = SPA_MIN(frames, (uint32_t) impl->position->clock.duration);
    float* dst = d->data;
    uint32_t got = 0;
    if (impl->started) {
        uint32_t read = __atomic_load_n(&impl->ring->read_index, __ATOMIC_RELAXED);
        uint32_t available = SPA_MIN(__atomic_load_n(&impl->ring->write_index, __ATOMIC_ACQUIRE) - read, RING_FRAMES);
        got = SPA_MIN(available, frames);
        for (uint32_t done = 0; done < got;) {
            uint32_t at = (read + done) & (RING_FRAMES - 1);
            uint32_t n = SPA_MIN(got - done, RING_FRAMES - at);
            memcpy(dst + done * impl->channels, impl->samples + at * impl->channels, n * size);
            done += n;
        }
        __atomic_store_n(&impl->ring->read_index, read + got, __ATOMIC_RELEASE);
    }
    memset(dst + got * impl->channels, 0, (frames - got) * size);
    d->chunk->offset = 0;
    d->chunk->size = frames * size;
    d->chunk->stride = (int32_t) size;
}

/* Data loop: one period of the graph, into (or out of) the ring. */
static void stream_process(void* data) {
    struct impl* impl = data;
    struct pw_buffer* buffer = pw_stream_dequeue_buffer(impl->stream);
    if (buffer == NULL) return;
    if (impl->capture) {
        capture_process(impl, buffer);
        pw_stream_queue_buffer(impl->stream, buffer);
        return;
    }

    struct spa_data* d = &buffer->buffer->datas[0];
    const float* src = NULL;
    uint32_t frames = 0;
    if (d->data != NULL && d->chunk != NULL) {
        uint32_t offset = SPA_MIN(d->chunk->offset, d->maxsize);
        frames = SPA_MIN(d->chunk->size, d->maxsize - offset) / frame_size(impl);
        src = SPA_PTROFF(d->data, offset, const float);
    }
    if (impl->started) {
        uint32_t write = __atomic_load_n(&impl->ring->write_index, __ATOMIC_RELAXED);
        uint32_t read = __atomic_load_n(&impl->ring->read_index, __ATOMIC_ACQUIRE);
        uint32_t space = RING_FRAMES - SPA_MIN(write - read, RING_FRAMES);
        if (frames > space) {
            pw_log_debug("ring full, %u frames dropped", frames - space);
            frames = space;
        }
        /* PipeWire scaled the period by the node's volume, which Android applies. */
        float volume;
        __atomic_load(&impl->node_volume, &volume, __ATOMIC_RELAXED);
        float gain = volume > 0.0f && volume < 1.0f ? 1.0f / volume : 1.0f;
        for (uint32_t done = 0; done < frames;) {
            uint32_t at = (write + done) & (RING_FRAMES - 1);
            uint32_t n = SPA_MIN(frames - done, RING_FRAMES - at);
            float* dst = impl->samples + at * impl->channels;
            const float* from = src + done * impl->channels;
            if (gain == 1.0f) {
                memcpy(dst, from, n * frame_size(impl));
            } else {
                for (uint32_t i = 0; i < n * impl->channels; i++) dst[i] = from[i] * gain;
            }
            done += n;
        }
        __atomic_store_n(&impl->ring->write_index, write + frames, __ATOMIC_RELEASE);
    }
    pw_stream_queue_buffer(impl->stream, buffer);
}

static int do_start(struct spa_loop* loop, bool async, uint32_t seq, const void* data, size_t size,
                    void* user_data) {
    struct impl* impl = user_data;
    (void) loop, (void) async, (void) seq, (void) data, (void) size;
    /* The device is stopped: drop what it did not play (record) last time. */
    if (impl->capture) {
        __atomic_store_n(&impl->ring->read_index, __atomic_load_n(&impl->ring->write_index, __ATOMIC_ACQUIRE),
                         __ATOMIC_RELEASE);
    } else {
        __atomic_store_n(&impl->ring->write_index, __atomic_load_n(&impl->ring->read_index, __ATOMIC_ACQUIRE),
                         __ATOMIC_RELEASE);
    }
    impl->active = true;
    impl->started = false;
    impl->start_time = now_ns();
    set_timeout(impl, impl->start_time + START_POLL_NS);
    return 0;
}

static int do_stop(struct spa_loop* loop, bool async, uint32_t seq, const void* data, size_t size,
                   void* user_data) {
    struct impl* impl = user_data;
    (void) loop, (void) async, (void) seq, (void) data, (void) size;
    impl->active = false;
    impl->started = false;
    set_timeout(impl, 0);
    return 0;
}

/* Main loop: frames from the graph to the speaker, the ring's fill plus the
 * device's own latency, as the sink's port latency. Players (pipewire-pulse
 * clients: Firefox, mpv) only see a port's latency, not the driver's
 * clock.delay: without it the picture ran ~90 ms ahead of the sound. */
static void publish_latency(struct impl* impl, uint32_t frames) {
    uint8_t buffer[256];
    struct spa_pod_builder b = SPA_POD_BUILDER_INIT(buffer, sizeof(buffer));
    struct spa_latency_info latency = SPA_LATENCY_INFO(SPA_DIRECTION_INPUT, .min_rate = frames, .max_rate = frames);
    const struct spa_pod* params[1] = {spa_latency_build(&b, SPA_PARAM_Latency, &latency)};
    impl->latency = frames;
    pw_stream_update_params(impl->stream, params, 1);
}

static void on_latency_timer(void* data, uint64_t expirations) {
    struct impl* impl = data;
    (void) expirations;
    if (!impl->running || impl->stream == NULL || __atomic_load_n(&impl->ring->burst, __ATOMIC_RELAXED) == 0) return;
    uint32_t frames = target_fill(impl) + __atomic_load_n(&impl->ring->latency, __ATOMIC_RELAXED);
    uint32_t change = frames > impl->latency ? frames - impl->latency : impl->latency - frames;
    if (change >= LATENCY_STEP_FRAMES) publish_latency(impl, frames);
}

/* Main loop: shows Android's volume and mute as the node's, through the
 * server, once the node is there. */
static void apply_app_volume(struct impl* impl) {
    uint32_t id = impl->stream ? pw_stream_get_node_id(impl->stream) : SPA_ID_INVALID;
    if (!impl->volume_pending || id == SPA_ID_INVALID) return;
    if (impl->node == NULL) {
        if (impl->volume_core == NULL) impl->volume_core = pw_context_connect(impl->context, NULL, 0);
        if (impl->volume_core != NULL && impl->registry == NULL)
            impl->registry = pw_core_get_registry(impl->volume_core, PW_VERSION_REGISTRY, 0);
        if (impl->registry != NULL)
            impl->node = pw_registry_bind(impl->registry, id, PW_TYPE_INTERFACE_Node, PW_VERSION_NODE, 0);
        if (impl->node == NULL) {
            pw_log_warn("cannot bind the sink's node: %m");
            return;
        }
    }
    uint8_t buffer[512];
    struct spa_pod_builder b = SPA_POD_BUILDER_INIT(buffer, sizeof(buffer));
    struct spa_pod_frame f;
    float volumes[SPA_AUDIO_MAX_CHANNELS];
    for (uint32_t i = 0; i < impl->channels; i++) volumes[i] = impl->volume;
    spa_pod_builder_push_object(&b, &f, SPA_TYPE_OBJECT_Props, SPA_PARAM_Props);
    spa_pod_builder_prop(&b, SPA_PROP_mute, 0);
    spa_pod_builder_bool(&b, impl->mute);
    spa_pod_builder_prop(&b, SPA_PROP_channelVolumes, 0);
    spa_pod_builder_array(&b, sizeof(float), SPA_TYPE_Float, impl->channels, volumes);
    pw_node_set_param(impl->node, SPA_PARAM_Props, 0, spa_pod_builder_pop(&b, &f));
    impl->volume_pending = false;
}

/* Main loop: the node's volume or mute changed (Plasma's slider, wpctl, or
 * apply_app_volume): Android's media volume follows. */
static void stream_param_changed(void* data, uint32_t id, const struct spa_pod* param) {
    struct impl* impl = data;
    if (impl->capture || id != SPA_PARAM_Props || param == NULL || !spa_pod_is_object(param)) return;
    float volume = impl->volume;
    bool mute = impl->mute;
    const struct spa_pod_object* object = (const struct spa_pod_object*) param;
    const struct spa_pod_prop* prop;
    SPA_POD_OBJECT_FOREACH(object, prop) {
        if (prop->key == SPA_PROP_mute) {
            bool value;
            if (spa_pod_get_bool(&prop->value, &value) == 0) mute = value;
        } else if (prop->key == SPA_PROP_channelVolumes) {
            float volumes[SPA_AUDIO_MAX_CHANNELS];
            uint32_t n = spa_pod_copy_array(&prop->value, SPA_TYPE_Float, volumes, SPA_AUDIO_MAX_CHANNELS);
            if (n > 0) {
                volume = 0.0f;
                for (uint32_t i = 0; i < n; i++) volume = SPA_MAX(volume, volumes[i]);
            }
        }
    }
    __atomic_store(&impl->node_volume, &volume, __ATOMIC_RELAXED);
    if (volume == impl->volume && mute == impl->mute) return;
    impl->volume = volume;
    impl->mute = mute;
    struct {
        dnx_audio_header header;
        dnx_audio_volume volume;
    } message = {{DNX_AUDIO_VOLUME, sizeof(dnx_audio_volume)}, {SPA_CLAMPF(volume, 0.0f, 1.0f), mute}};
    if (impl->sock >= 0 && send(impl->sock, &message, sizeof(message), MSG_NOSIGNAL) != sizeof(message))
        pw_log_warn("cannot reach the app: %m");
}

/* Main loop: Android's media volume, from the app. */
static void on_app_message(void* data, int fd, uint32_t mask) {
    struct impl* impl = data;
    struct {
        dnx_audio_header header;
        dnx_audio_volume volume;
    } message;
    if (mask & (SPA_IO_ERR | SPA_IO_HUP)) {
        pw_log_warn("the app closed the connection");
        pw_loop_destroy_source(pw_context_get_main_loop(impl->context), impl->sock_source);
        impl->sock_source = NULL;
        return;
    }
    ssize_t n = recv(fd, &message, sizeof(message), MSG_DONTWAIT);
    if (n == (ssize_t) sizeof(message) && message.header.type == DNX_AUDIO_VOLUME &&
        message.header.size == sizeof(dnx_audio_volume)) {
        impl->volume = SPA_CLAMPF(message.volume.volume, 0.0f, 1.0f);
        impl->mute = message.volume.mute != 0;
        impl->volume_pending = true;
        apply_app_volume(impl);
    } else if (n > 0) {
        pw_log_warn("unexpected message from the app (%zd bytes)", n);
    }
}

static void stream_state_changed(void* data, enum pw_stream_state old, enum pw_stream_state state,
                                 const char* error) {
    struct impl* impl = data;
    (void) old;
    if (state == PW_STREAM_STATE_PAUSED || state == PW_STREAM_STATE_STREAMING) apply_app_volume(impl);
    if (state == PW_STREAM_STATE_STREAMING) {
        if (!impl->running) {
            impl->running = true;
            pw_loop_invoke(impl->data_loop, do_start, 0, NULL, 0, true, impl);
            send_message(impl, DNX_AUDIO_START);
            if (impl->latency_timer) {
                struct timespec interval = {0, LATENCY_CHECK_MS * SPA_NSEC_PER_MSEC};
                pw_loop_update_timer(pw_context_get_main_loop(impl->context), impl->latency_timer, &interval,
                                     &interval, false);
            }
        }
        return;
    }
    if (impl->running) {
        impl->running = false;
        if (impl->latency_timer) {
            pw_loop_update_timer(pw_context_get_main_loop(impl->context), impl->latency_timer, NULL, NULL, false);
        }
        pw_loop_invoke(impl->data_loop, do_stop, 0, NULL, 0, true, impl);
        send_message(impl, DNX_AUDIO_STOP);
    }
    if (state == PW_STREAM_STATE_ERROR || state == PW_STREAM_STATE_UNCONNECTED) {
        pw_log_error("%s stream %s: %s", impl->capture ? "source" : "sink", pw_stream_state_as_string(state),
                     error ? error : "");
        pw_impl_module_schedule_destroy(impl->module);
    }
}

static void stream_io_changed(void* data, uint32_t id, void* area, uint32_t size) {
    struct impl* impl = data;
    (void) size;
    if (id == SPA_IO_Position) impl->position = area;
}

static void stream_destroy(void* data) {
    struct impl* impl = data;
    spa_hook_remove(&impl->stream_listener);
    impl->stream = NULL;
}

static const struct pw_stream_events stream_events = {
    PW_VERSION_STREAM_EVENTS,
    .destroy = stream_destroy,
    .state_changed = stream_state_changed,
    .io_changed = stream_io_changed,
    .param_changed = stream_param_changed,
    .process = stream_process,
};

/* Connects to the app and hands it the ring. */
static int connect_app(struct impl* impl) {
    struct sockaddr_un address = {.sun_family = AF_UNIX};
    int memfd = -1, res;

    strcpy(address.sun_path, DNX_AUDIO_SOCKET);
    impl->sock = socket(AF_UNIX, SOCK_STREAM | SOCK_CLOEXEC, 0);
    if (impl->sock < 0 || connect(impl->sock, (struct sockaddr*) &address, sizeof(address)) < 0) {
        res = -errno;
        pw_log_error("cannot connect to %s: %m", DNX_AUDIO_SOCKET);
        return res;
    }

    impl->ring_size = DNX_AUDIO_RING_SIZE(RING_FRAMES, impl->channels);
    /* No memfd_create() in bionic's headers below API 30. */
    memfd = (int) syscall(SYS_memfd_create, "droidnux-audio", 1u /* MFD_CLOEXEC */);
    if (memfd < 0 || ftruncate(memfd, (off_t) impl->ring_size) < 0) {
        res = -errno;
        pw_log_error("cannot create the ring: %m");
        goto out;
    }
    impl->ring = mmap(NULL, impl->ring_size, PROT_READ | PROT_WRITE, MAP_SHARED, memfd, 0);
    if (impl->ring == MAP_FAILED) {
        impl->ring = NULL;
        res = -errno;
        pw_log_error("cannot map the ring: %m");
        goto out;
    }
    impl->samples = (float*) (impl->ring + 1);
    impl->ring->magic = DNX_AUDIO_MAGIC;
    impl->ring->version = DNX_AUDIO_VERSION;
    impl->ring->rate = RATE;
    impl->ring->channels = impl->channels;
    impl->ring->frames = RING_FRAMES;
    impl->ring->direction = impl->capture ? DNX_AUDIO_INPUT : DNX_AUDIO_OUTPUT;

    struct {
        dnx_audio_header header;
        dnx_audio_hello hello;
    } message = {{DNX_AUDIO_HELLO, sizeof(dnx_audio_hello)}, {DNX_AUDIO_MAGIC, DNX_AUDIO_VERSION}};
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
    if (sendmsg(impl->sock, &msg, MSG_NOSIGNAL) != (ssize_t) sizeof(message)) {
        res = -errno;
        pw_log_error("cannot send the ring: %m");
        goto out;
    }
    res = 0;
out:
    if (memfd >= 0) close(memfd);
    return res;
}

static int create_stream(struct impl* impl) {
    const char* name = impl->capture ? "droidnux-input" : "droidnux-output";
    struct pw_properties* props = pw_properties_new(
        PW_KEY_MEDIA_CLASS, impl->capture ? "Audio/Source" : "Audio/Sink",
        PW_KEY_NODE_NAME, name,
        PW_KEY_NODE_DESCRIPTION, "Android",
        PW_KEY_NODE_RATE, "1/48000",
        /* The adapter's DSP ports take their layout from these, not from EnumFormat. */
        PW_KEY_AUDIO_FORMAT, "F32LE",
        PW_KEY_AUDIO_RATE, "48000",
        PW_KEY_AUDIO_CHANNELS, impl->capture ? "1" : "2",
        SPA_KEY_AUDIO_POSITION, impl->capture ? "[ MONO ]" : "[ FL FR ]",
        /* Above the dummy driver and any other node: the default output and
         * input. The sink drives when both run. */
        "priority.driver", impl->capture ? "1500" : "2000",
        /* The source above the sink: WirePlumber also offers the sink's monitor as a source. */
        "priority.session", impl->capture ? "2100" : "2000",
        /* WirePlumber would restore its last volume onto the node: Android's is the volume. */
        "state.restore-props", "false",
        NULL);
    if (props == NULL) return -errno;
    pw_properties_set(props, PW_KEY_NODE_LOOP_NAME, impl->data_loop->name);

    impl->stream = pw_stream_new(impl->core, name, props);
    if (impl->stream == NULL) return -errno;
    pw_stream_add_listener(impl->stream, &impl->stream_listener, &stream_events, impl);

    uint8_t buffer[1024];
    struct spa_pod_builder b = SPA_POD_BUILDER_INIT(buffer, sizeof(buffer));
    struct spa_audio_info_raw info = {
        .format = SPA_AUDIO_FORMAT_F32,
        .rate = RATE,
        .channels = impl->channels,
        .position = {SPA_AUDIO_CHANNEL_FL, SPA_AUDIO_CHANNEL_FR},
    };
    if (impl->capture) info.position[0] = SPA_AUDIO_CHANNEL_MONO;
    const struct spa_pod* params[1] = {spa_format_audio_raw_build(&b, SPA_PARAM_EnumFormat, &info)};
    return pw_stream_connect(impl->stream, impl->capture ? PW_DIRECTION_OUTPUT : PW_DIRECTION_INPUT, PW_ID_ANY,
                             PW_STREAM_FLAG_DRIVER | PW_STREAM_FLAG_MAP_BUFFERS | PW_STREAM_FLAG_RT_PROCESS,
                             params, 1);
}

static void impl_destroy(struct impl* impl) {
    if (impl->stream) pw_stream_destroy(impl->stream);
    if (impl->core && impl->core_proxy_listener.link.next) spa_hook_remove(&impl->core_proxy_listener);
    if (impl->core && impl->do_disconnect) pw_core_disconnect(impl->core);
    if (impl->timer) pw_loop_destroy_source(impl->data_loop, impl->timer);
    if (impl->latency_timer) pw_loop_destroy_source(pw_context_get_main_loop(impl->context), impl->latency_timer);
    if (impl->sock_source) pw_loop_destroy_source(pw_context_get_main_loop(impl->context), impl->sock_source);
    if (impl->node) pw_proxy_destroy((struct pw_proxy*) impl->node);
    if (impl->registry) pw_proxy_destroy((struct pw_proxy*) impl->registry);
    if (impl->volume_core) pw_core_disconnect(impl->volume_core);
    if (impl->data_loop) pw_context_release_loop(impl->context, impl->data_loop);
    if (impl->ring) munmap(impl->ring, impl->ring_size);
    if (impl->sock >= 0) close(impl->sock);
    free(impl);
}

static void module_destroy(void* data) {
    struct impl* impl = data;
    spa_hook_remove(&impl->module_listener);
    impl_destroy(impl);
}

static const struct pw_impl_module_events module_events = {
    PW_VERSION_IMPL_MODULE_EVENTS,
    .destroy = module_destroy,
};

static void core_destroy(void* data) {
    struct impl* impl = data;
    spa_hook_remove(&impl->core_proxy_listener);
    impl->core = NULL;
    pw_impl_module_schedule_destroy(impl->module);
}

static const struct pw_proxy_events core_proxy_events = {
    PW_VERSION_PROXY_EVENTS,
    .destroy = core_destroy,
};

static int create_node(struct pw_impl_module* module, bool capture) {
    struct pw_context* context = pw_impl_module_get_context(module);
    struct impl* impl;
    int res;

    impl = calloc(1, sizeof(*impl));
    if (impl == NULL) return -errno;
    impl->capture = capture;
    impl->channels = capture ? 1u : 2u;
    impl->sock = -1;
    impl->module = module;
    impl->context = context;

    impl->data_loop = pw_context_acquire_loop(context, NULL);
    if (impl->data_loop == NULL) {
        res = -errno;
        goto error;
    }
    impl->timer = pw_loop_add_timer(impl->data_loop, on_timeout, impl);
    if (impl->timer == NULL) {
        res = -errno;
        goto error;
    }

    if (!capture) {
        impl->latency_timer = pw_loop_add_timer(pw_context_get_main_loop(context), on_latency_timer, impl);
        if (impl->latency_timer == NULL) {
            res = -errno;
            goto error;
        }
    }

    impl->core = pw_context_get_object(context, PW_TYPE_INTERFACE_Core);
    if (impl->core == NULL) {
        impl->core = pw_context_connect(context, NULL, 0);
        impl->do_disconnect = true;
    }
    if (impl->core == NULL) {
        res = -errno;
        pw_log_error("cannot connect: %m");
        goto error;
    }
    pw_proxy_add_listener((struct pw_proxy*) impl->core, &impl->core_proxy_listener, &core_proxy_events, impl);

    if ((res = connect_app(impl)) < 0 || (res = create_stream(impl)) < 0) goto error;
    if (!capture) {
        impl->volume = impl->node_volume = 1.0f;
        impl->sock_source = pw_loop_add_io(pw_context_get_main_loop(context), impl->sock, SPA_IO_IN, false,
                                           on_app_message, impl);
        if (impl->sock_source == NULL) {
            res = -errno;
            goto error;
        }
    }

    pw_impl_module_add_listener(module, &impl->module_listener, &module_events, impl);
    pw_log_info("%s through the app, %u Hz, %u channels", capture ? "recording" : "playing", RATE, impl->channels);
    return 0;

error:
    impl_destroy(impl);
    return res;
}

SPA_EXPORT int pipewire__module_init(struct pw_impl_module* module, const char* args) {
    (void) args;
    PW_LOG_TOPIC_INIT(mod_topic);
    int sink = create_node(module, false);
    /* Without the microphone the sink still plays, and the other way round. */
    int source = create_node(module, true);
    if (sink < 0 && source < 0) return sink;
    pw_impl_module_update_properties(module, &SPA_DICT_INIT_ARRAY(module_props));
    return 0;
}
