/*
 * Protocol of the camera service (camera_input.h), shared with its client in
 * the container: DroidNux's PipeWire module (guest/pipewire/camera.c). Plain C
 * with fixed-size fields only, as the client is loaded by glibc.
 *
 * On a Unix stream socket, one connection per camera. Every message starts
 * with a dnx_camera_header whose `size` counts the bytes that follow it.
 *
 * Client:  HELLO once; then START, with a new ring's memfd in SCM_RIGHTS, and
 *          STOP in any number.
 * Server:  INFO, the answer to HELLO; FRAME for each frame put in the ring
 *          while started; ERROR when the camera cannot open (it stays closed
 *          until the next START).
 *
 * The ring is a dnx_camera_ring, then `slots` frames of `slot_size` bytes from
 * DNX_CAMERA_RING_DATA, each in the format START asked for, planes one after
 * the other with the width as stride. The server writes frame number
 * `sequence` into slot `sequence % slots`, then stores `sequence + 1`; the
 * client copies the slot of `sequence - 1` and, if `sequence` has moved on by
 * `slots - 1` meanwhile, drops the copy. Shared fields are accessed with
 * atomic loads and stores only (__atomic builtins on both sides).
 */
#ifndef DROIDNUX_CAMERA_PROTOCOL_H
#define DROIDNUX_CAMERA_PROTOCOL_H

#include <stdint.h>

#define DNX_CAMERA_MAGIC 0x43584e44u /* "DNXC" */
#define DNX_CAMERA_VERSION 1u
#define DNX_CAMERA_SOCKET "/tmp/.droidnux-camera"
#define DNX_CAMERA_MAX_SIZES 16u
#define DNX_CAMERA_SLOTS 3u

enum dnx_camera_type {
    DNX_CAMERA_HELLO = 1, /* client: dnx_camera_hello */
    DNX_CAMERA_INFO = 2,  /* server: dnx_camera_info */
    DNX_CAMERA_START = 3, /* client: dnx_camera_start, the ring's memfd in SCM_RIGHTS */
    DNX_CAMERA_STOP = 4,  /* client: nothing else */
    DNX_CAMERA_FRAME = 5, /* server: nothing else */
    DNX_CAMERA_ERROR = 6, /* server: dnx_camera_error */
};

enum dnx_camera_facing {
    DNX_CAMERA_FRONT = 0,
    DNX_CAMERA_BACK = 1,
    DNX_CAMERA_EXTERNAL = 2,
};

enum dnx_camera_format {
    DNX_CAMERA_NV12 = 1, /* Y, then interleaved U and V at half size */
    DNX_CAMERA_I420 = 2, /* Y, then U, then V at half size */
};

enum dnx_camera_error_code {
    DNX_CAMERA_DENIED = 1,      /* the user did not allow the camera */
    DNX_CAMERA_UNAVAILABLE = 2, /* another app has it, or it failed */
};

typedef struct dnx_camera_header {
    uint32_t type, size;
} dnx_camera_header;

typedef struct dnx_camera_hello {
    uint32_t magic, version;
    uint32_t index; /* which camera, from 0 */
} dnx_camera_hello;

typedef struct dnx_camera_size {
    uint32_t width, height, fps; /* landscape (width >= height), the highest frame rate */
} dnx_camera_size;

typedef struct dnx_camera_info {
    uint32_t count;  /* cameras the device has: the client connects once for each */
    uint32_t facing; /* dnx_camera_facing of this one */
    uint32_t sizes;  /* entries of `size`, largest first; 0 if `index` is not a camera */
    dnx_camera_size size[DNX_CAMERA_MAX_SIZES];
} dnx_camera_info;

typedef struct dnx_camera_start {
    uint32_t width, height, fps; /* one of INFO's sizes, and a rate up to its own */
    uint32_t format;             /* dnx_camera_format */
} dnx_camera_start;

typedef struct dnx_camera_error {
    uint32_t code; /* dnx_camera_error_code */
} dnx_camera_error;

typedef struct dnx_camera_ring {
    /* Set by the client before START, constant afterwards. */
    uint32_t magic, version;
    uint32_t width, height, format;
    uint32_t slots;     /* DNX_CAMERA_SLOTS */
    uint64_t slot_size; /* bytes of a frame: width * height * 3 / 2 */
    uint32_t reserved0[8];
    /* Server: frames written. */
    uint32_t sequence;
    uint32_t reserved1[15];
    /* Server: each slot's capture time, CLOCK_MONOTONIC nanoseconds. */
    uint64_t timestamp[DNX_CAMERA_SLOTS];
} dnx_camera_ring;

/* Where the frames start, page-aligned. */
#define DNX_CAMERA_RING_DATA 4096u
#define DNX_CAMERA_FRAME_SIZE(width, height) ((uint64_t) (width) * (height) * 3 / 2)
#define DNX_CAMERA_RING_SIZE(width, height) \
    (DNX_CAMERA_RING_DATA + (uint64_t) DNX_CAMERA_SLOTS * DNX_CAMERA_FRAME_SIZE(width, height))

#endif /* DROIDNUX_CAMERA_PROTOCOL_H */
