/*
 * Protocol of the video decoder service (video_decoder.h), shared with its
 * client in the container: DroidNux's VA-API driver (guest/va). Plain C with
 * fixed-size fields only, as the client is built against glibc.
 *
 * On a Unix stream socket, one connection per decoding session. Every message
 * starts with a dnx_video_header whose `size` counts the bytes that follow it.
 *
 * Client:  OPEN, then DECODE and RELEASE in any number.
 * Server:  OPENED once, then one FRAME for each DECODE without
 *          DNX_VIDEO_NO_OUTPUT, in decode order (the codecs are run so that they
 *          output every frame as soon as it is decoded). A FRAME with status 0
 *          carries the frame's dma-buf in SCM_RIGHTS; the buffer is the
 *          client's until it sends RELEASE for it (or closes the connection).
 */
#ifndef DROIDNUX_VIDEO_PROTOCOL_H
#define DROIDNUX_VIDEO_PROTOCOL_H

#include <stdint.h>

#define DNX_VIDEO_MAGIC 0x56584e44u /* "DNXV" */
#define DNX_VIDEO_VERSION 1u
#define DNX_VIDEO_SOCKET "/tmp/.droidnux-video"

/* Largest compressed frame a DECODE may carry. */
#define DNX_VIDEO_MAX_FRAME (16u << 20)

enum dnx_video_codec {
    DNX_VIDEO_VP9 = 1,
    DNX_VIDEO_H264 = 2,
};

enum dnx_video_type {
    DNX_VIDEO_OPEN = 1,    /* client: dnx_video_open */
    DNX_VIDEO_OPENED = 2,  /* server: dnx_video_opened */
    DNX_VIDEO_DECODE = 3,  /* client: dnx_video_decode, then the bitstream */
    DNX_VIDEO_FRAME = 4,   /* server: dnx_video_frame */
    DNX_VIDEO_RELEASE = 5, /* client: dnx_video_release */
};

typedef struct dnx_video_header {
    uint32_t type, size;
} dnx_video_header;

typedef struct dnx_video_open {
    uint32_t magic, version;
    uint32_t codec;         /* enum dnx_video_codec */
    uint32_t width, height; /* coded size */
    uint32_t buffers;       /* most frames the client holds at once */
} dnx_video_open;

typedef struct dnx_video_opened {
    int32_t status; /* 0, or a negative errno */
} dnx_video_opened;

/* The bitstream of one frame (or a VP9 show_existing_frame header) follows. */
#define DNX_VIDEO_NO_OUTPUT 1u /* the codec shows nothing for it: no FRAME */

typedef struct dnx_video_decode {
    uint32_t tag; /* returned in the FRAME */
    uint32_t flags;
} dnx_video_decode;

typedef struct dnx_video_frame {
    uint32_t tag;
    int32_t status;  /* 0, or a negative errno: no frame for this DECODE */
    uint32_t buffer; /* id for RELEASE */
    uint32_t width, height;    /* visible size */
    uint32_t fourcc;           /* DRM_FORMAT_NV12 */
    uint32_t offsets[2], pitches[2];
    uint32_t reserved;
    uint64_t size;  /* bytes in the dma-buf */
} dnx_video_frame;

typedef struct dnx_video_release {
    uint32_t buffer;
} dnx_video_release;

#endif /* DROIDNUX_VIDEO_PROTOCOL_H */
