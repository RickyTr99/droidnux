/*
 * Allocator of AHardwareBuffers for GPU clients in the container.
 *
 * Mesa's Vulkan WSI (DroidNux patch 0003) asks it for its swapchain images,
 * so the buffers clients render into are AHardwareBuffers that Android's
 * compositor can show as they are, and the display server recognizes them
 * when they come back as dma-bufs (DRI3).
 *
 * Protocol, on a Unix stream socket: one connection per buffer. The client
 * sends a dnx_allocator_request, the allocator answers a dnx_allocator_reply
 * and, on success, the buffer's dma-buf in SCM_RIGHTS. The buffer lives as
 * long as the connection (and any reference taken with dnx_allocator_find).
 */
#ifndef DROIDNUX_ALLOCATOR_H
#define DROIDNUX_ALLOCATOR_H

#include <android/hardware_buffer.h>
#include <stdbool.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

#define DNX_ALLOCATOR_MAGIC 0x41584e44u /* "DNXA" */
#define DNX_ALLOCATOR_VERSION 1u

typedef struct dnx_allocator_request {
    uint32_t magic, version, width, height;
    uint32_t drm_format; /* DRM_FORMAT_ARGB8888 only: B, G, R, A in memory */
} dnx_allocator_request;

typedef struct dnx_allocator_reply {
    int32_t status;  /* 0, or a negative errno */
    uint32_t stride; /* bytes per row */
    uint64_t size;   /* bytes in the dma-buf */
} dnx_allocator_reply;

/* Listens on `socket_path` (replacing a stale socket) on a thread of its own. */
bool dnx_allocator_start(const char* socket_path);

/* The live buffer whose dma-buf is `dmabuf_fd` (same file, any descriptor),
 * acquired for the caller, or NULL. Any thread. */
AHardwareBuffer* dnx_allocator_find(int dmabuf_fd);

#ifdef __cplusplus
}
#endif

#endif /* DROIDNUX_ALLOCATOR_H */
