/*
 * Wire protocol of the buffer-sharing spike. Shared verbatim by the guest
 * client and the app (display/src/main/cpp/spike). SOCK_SEQPACKET, one
 * struct per message, native byte order (both ends run on the same CPU).
 */
#ifndef DROIDNUX_SPIKE_PROTOCOL_H
#define DROIDNUX_SPIKE_PROTOCOL_H

#include <stdint.h>

#define DN_SOCKET_NAME "droidnux-buffer-spike"
#define DN_MAGIC 0x444e5853u /* "DNXS" */
#define DN_VERSION 1u

/* app -> guest, first message. */
struct dn_hello {
    uint32_t magic;
    uint32_t version;
    uint32_t count; /* number of dn_buffer messages that follow */
    uint32_t reserved;
};

/* app -> guest, one per buffer, with the dma-buf fd attached (SCM_RIGHTS). */
struct dn_buffer {
    uint32_t index;
    uint32_t width;
    uint32_t height;
    uint32_t stride; /* bytes per row */
    uint32_t drm_format;
    uint32_t reserved;
    uint64_t offset;
    uint64_t size;
};

enum {
    DN_MSG_PRESENT = 1, /* guest -> app: buffer `index` holds a finished frame */
    DN_MSG_RELEASE = 2, /* app -> guest: buffer `index` may be rendered into again */
};

struct dn_message {
    uint32_t type;
    uint32_t index;
    uint64_t frame;
};

#endif
