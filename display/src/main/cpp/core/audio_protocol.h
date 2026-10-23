/*
 * Protocol of the audio output service (audio_output.h), shared with its
 * client in the container: DroidNux's PipeWire module (guest/pipewire). Plain
 * C with fixed-size fields only, as the client is loaded by glibc.
 *
 * On a Unix stream socket, one connection per sink or source. Every message starts with
 * a dnx_audio_header whose `size` counts the bytes that follow it.
 *
 * Client:  HELLO once, with the ring's memfd in SCM_RIGHTS; then START,
 *          STOP and (sink) VOLUME in any number.
 * Server:  (sink) VOLUME after HELLO and whenever Android's media volume
 *          changes. The rest the client needs is in the ring header.
 *
 * The ring is a dnx_audio_ring followed by `frames` interleaved F32 frames of
 * `channels` samples. For a sink the client (PipeWire's graph clock) writes
 * and the server (the AAudio callback) reads; for a source (the microphone,
 * `direction` DNX_AUDIO_INPUT) the server writes and the client reads. Indices count frames and wrap at 2^32; `frames`
 * is a power of two. Shared fields are accessed with atomic loads and stores
 * only (__atomic builtins on both sides).
 */
#ifndef DROIDNUX_AUDIO_PROTOCOL_H
#define DROIDNUX_AUDIO_PROTOCOL_H

#include <stdint.h>

#define DNX_AUDIO_MAGIC 0x41584e44u /* "DNXA" */
#define DNX_AUDIO_VERSION 1u
#define DNX_AUDIO_SOCKET "/tmp/.droidnux-audio"

enum dnx_audio_type {
    DNX_AUDIO_HELLO = 1, /* dnx_audio_hello, ring memfd in SCM_RIGHTS */
    DNX_AUDIO_START = 2, /* the node is running: play (record) the ring */
    DNX_AUDIO_STOP = 3,  /* the node is idle: stop the device (battery) */
    DNX_AUDIO_VOLUME = 4, /* dnx_audio_volume: the sink's volume is Android's media volume */
};

enum dnx_audio_direction {
    DNX_AUDIO_OUTPUT = 0,
    DNX_AUDIO_INPUT = 1,
};

typedef struct dnx_audio_header {
    uint32_t type, size;
} dnx_audio_header;

typedef struct dnx_audio_hello {
    uint32_t magic, version;
} dnx_audio_hello;

typedef struct dnx_audio_volume {
    float volume;  /* linear, 0 to 1, as PipeWire's channel volumes */
    uint32_t mute; /* 0 or 1 */
} dnx_audio_volume;

typedef struct dnx_audio_ring {
    /* Set by the client before HELLO, constant afterwards. */
    uint32_t magic, version;
    uint32_t rate, channels; /* F32 samples */
    uint32_t frames;         /* capacity of the ring, a power of two */
    uint32_t direction;      /* dnx_audio_direction */
    uint32_t reserved0[10];
    /* Writer (sink: client, source: server): frames written. */
    uint32_t write_index;
    uint32_t reserved1[15];
    /* Reader (sink: server, source: client): frames read; a sink's reader
     * fills with silence what the ring cannot. */
    uint32_t read_index;
    uint32_t underruns; /* server callbacks that found too few frames (sink) or no room (source) */
    /* Server: frames per callback while the device plays, 0 while it does
     * not (set after START once the device runs, cleared on STOP). */
    uint32_t burst;
    /* Server: frames between the ring and the speaker (sink), an estimate. */
    uint32_t latency;
    uint32_t reserved2[12];
} dnx_audio_ring;

/* Bytes of a ring for `frames` frames of `channels` F32 samples. */
#define DNX_AUDIO_RING_SIZE(frames, channels) \
    (sizeof(dnx_audio_ring) + (uint64_t) (frames) * (channels) * sizeof(float))

#endif /* DROIDNUX_AUDIO_PROTOCOL_H */
