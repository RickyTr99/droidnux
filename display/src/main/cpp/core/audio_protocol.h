/*
 * Protocol of the audio output service (audio_output.h), shared with its
 * client in the container: DroidNux's PipeWire module (guest/pipewire). Plain
 * C with fixed-size fields only, as the client is loaded by glibc.
 *
 * On a Unix stream socket, one connection per sink. Every message starts with
 * a dnx_audio_header whose `size` counts the bytes that follow it.
 *
 * Client:  HELLO once, with the ring's memfd in SCM_RIGHTS; then START and
 *          STOP in any number. The server sends nothing back: what the client
 *          needs from it is in the ring header.
 *
 * The ring is a dnx_audio_ring followed by `frames` interleaved F32 frames of
 * `channels` samples. The client (PipeWire's graph clock) writes, the server
 * (the AAudio callback) reads. Indices count frames and wrap at 2^32; `frames`
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
    DNX_AUDIO_START = 2, /* the sink is running: play the ring */
    DNX_AUDIO_STOP = 3,  /* the sink is idle: stop the device (battery) */
};

typedef struct dnx_audio_header {
    uint32_t type, size;
} dnx_audio_header;

typedef struct dnx_audio_hello {
    uint32_t magic, version;
} dnx_audio_hello;

typedef struct dnx_audio_ring {
    /* Set by the client before HELLO, constant afterwards. */
    uint32_t magic, version;
    uint32_t rate, channels; /* F32 samples */
    uint32_t frames;         /* capacity of the ring, a power of two */
    uint32_t reserved0[11];
    /* Client: frames written. */
    uint32_t write_index;
    uint32_t reserved1[15];
    /* Server: frames read; silence fills what the ring cannot. */
    uint32_t read_index;
    uint32_t underruns; /* callbacks that found too few frames */
    /* Server: frames per callback while the device plays, 0 while it does
     * not (set after START once the device runs, cleared on STOP). */
    uint32_t burst;
    /* Server: frames between the ring and the speaker, an estimate. */
    uint32_t latency;
    uint32_t reserved2[12];
} dnx_audio_ring;

/* Bytes of a ring for `frames` frames of `channels` F32 samples. */
#define DNX_AUDIO_RING_SIZE(frames, channels) \
    (sizeof(dnx_audio_ring) + (uint64_t) (frames) * (channels) * sizeof(float))

#endif /* DROIDNUX_AUDIO_PROTOCOL_H */
