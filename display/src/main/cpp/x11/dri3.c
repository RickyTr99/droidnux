/*
 * DRI3 for the DroidNux X11 front-end: GPU clients in the container (Mesa
 * Turnip on KGSL, Zink on top of it) hand their images to the server as
 * dma-bufs.
 *
 * This is the generic path: any linear dma-buf becomes an fb pixmap backed by
 * a CPU mapping of the buffer, and Present copies it like any other pixmap.
 * DRI3Open gives the KGSL device; the container shows it to clients as the
 * render node /dev/dri/renderD128 (with a matching sysfs), so Mesa's loader
 * and EGL see a GPU device instead of falling back to software.
 *
 * Derived from Termux:X11 (lorie/InitOutput.c, GPL-3.0).
 */
#pragma clang diagnostic ignored "-Wstrict-prototypes"

#ifdef HAVE_DIX_CONFIG_H
#include <dix-config.h>
#endif

#include <android/log.h>
#include <errno.h>
#include <fcntl.h>
#include <linux/dma-buf.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/mman.h>
#include <unistd.h>

#include <X11/X.h>
#include <X11/Xmd.h>

#include "fb.h"
#include "privates.h"
#include "scrnintstr.h"
#include "dri3.h"
#include "drm_fourcc.h"

#include "allocator.h"
#include "gpu_copy.h"
#include "private.h"

#define log(prio, ...) __android_log_print(ANDROID_LOG_##prio, "DroidNux.X11", __VA_ARGS__)

/* A pixmap's view of an imported dma-buf, or of a buffer the server allocated
 * for itself (fd -1, mapped through the AHardwareBuffer lock). */
struct dmabuf {
    int fd;
    void* map;
    size_t size;
    AHardwareBuffer* ahb; /* the app allocated it (core/allocator.h): Android can show it as is */
    bool locked;          /* ahb stays locked for CPU access while the pixmap lives */
    void* gpuImage;       /* ahb as the GPU sees it (gpu_copy.h), made at the first GPU copy */
};

static DevPrivateKeyRec dmabufKeyRec;
#define dmabufKey (&dmabufKeyRec)

static DestroyPixmapProcPtr wrappedDestroyPixmap;

static struct dmabuf* pixmapDmabuf(PixmapPtr pixmap) {
    return dixLookupPrivate(&pixmap->devPrivates, dmabufKey);
}

static void dmabufFree(struct dmabuf* buffer) {
    dnx_gpu_image_free(buffer->gpuImage);
    if (buffer->map != MAP_FAILED) munmap(buffer->map, buffer->size);
    if (buffer->fd >= 0) close(buffer->fd);
    if (buffer->locked) AHardwareBuffer_unlock(buffer->ahb, NULL);
    if (buffer->ahb) AHardwareBuffer_release(buffer->ahb);
    free(buffer);
}

static Bool destroyPixmap(PixmapPtr pixmap) {
    ScreenPtr pScreen = pixmap->drawable.pScreen;
    struct dmabuf* buffer = pixmap->refcnt == 1 ? pixmapDmabuf(pixmap) : NULL;

    pScreen->DestroyPixmap = wrappedDestroyPixmap;
    Bool result = pScreen->DestroyPixmap(pixmap);
    wrappedDestroyPixmap = pScreen->DestroyPixmap;
    pScreen->DestroyPixmap = destroyPixmap;

    if (buffer) dmabufFree(buffer);
    return result;
}

/* The drm_fourcc.h generated for the server has formats but no modifiers
 * (its DRM_FORMAT_MOD_INVALID, -1, is what the DRI3 code passes for buffers
 * sent without one). */
#define MODIFIER_LINEAR 0ULL
#define MODIFIER_INVALID 0x00ffffffffffffffULL

static Bool isLinear(CARD64 modifier) {
    return modifier == MODIFIER_LINEAR || modifier == MODIFIER_INVALID || modifier == (CARD64) DRM_FORMAT_MOD_INVALID;
}

static PixmapPtr pixmapFromFds(ScreenPtr pScreen, CARD8 num_fds, const int* fds, CARD16 width, CARD16 height,
                               const CARD32* strides, const CARD32* offsets, CARD8 depth, CARD8 bpp,
                               CARD64 modifier) {
    if (num_fds != 1 || !isLinear(modifier) || bpp != 32 || (depth != 24 && depth != 32) ||
        strides[0] < width * 4u) {
        log(ERROR, "DRI3: unsupported buffer (%u planes, modifier 0x%llx, depth %u, bpp %u, stride %u)", num_fds,
            (unsigned long long) modifier, depth, bpp, strides[0]);
        return NULL;
    }

    /* The buffer may be larger than the image (alignment); map all of it. */
    off_t end = lseek(fds[0], 0, SEEK_END);
    size_t needed = (size_t) offsets[0] + (size_t) strides[0] * height;
    if (end < 0 || (size_t) end < needed) {
        log(ERROR, "DRI3: buffer of %lld bytes too small for %ux%u stride %u", (long long) end, width, height,
            strides[0]);
        return NULL;
    }

    struct dmabuf* buffer = calloc(1, sizeof(*buffer));
    if (!buffer) return NULL;
    buffer->size = (size_t) end;
    buffer->map = mmap(NULL, buffer->size, PROT_READ | PROT_WRITE, MAP_SHARED, fds[0], 0);
    buffer->fd = dup(fds[0]);
    buffer->ahb = dnx_allocator_find(fds[0]);
    if (buffer->map == MAP_FAILED || buffer->fd < 0) {
        log(ERROR, "DRI3: cannot map buffer: %s", strerror(errno));
        dmabufFree(buffer);
        return NULL;
    }

    PixmapPtr pixmap = pScreen->CreatePixmap(pScreen, 0, 0, depth, 0);
    if (!pixmap) {
        dmabufFree(buffer);
        return NULL;
    }
    if (!pScreen->ModifyPixmapHeader(pixmap, width, height, depth, bpp, strides[0],
                                     (uint8_t*) buffer->map + offsets[0])) {
        pScreen->DestroyPixmap(pixmap);
        dmabufFree(buffer);
        return NULL;
    }
    dixSetPrivate(&pixmap->devPrivates, dmabufKey, buffer);
    return pixmap;
}

static int openDevice(ClientPtr client, ScreenPtr pScreen, RRProviderPtr provider, int* fd) {
    *fd = open("/dev/kgsl-3d0", O_RDWR | O_CLOEXEC);
    if (*fd < 0) {
        log(ERROR, "DRI3: cannot open /dev/kgsl-3d0: %s", strerror(errno));
        return BadAlloc;
    }
    return Success;
}

static int getFormats(ScreenPtr pScreen, CARD32* num_formats, CARD32** formats) {
    static CARD32 supported[] = {DRM_FORMAT_XRGB8888, DRM_FORMAT_ARGB8888};
    *num_formats = ARRAY_SIZE(supported);
    *formats = supported;
    return TRUE;
}

static int getModifiers(ScreenPtr pScreen, uint32_t format, uint32_t* num_modifiers, uint64_t** modifiers) {
    static uint64_t linear = MODIFIER_LINEAR;
    if (format != DRM_FORMAT_XRGB8888 && format != DRM_FORMAT_ARGB8888) {
        *num_modifiers = 0;
        *modifiers = NULL;
        return TRUE;
    }
    *num_modifiers = 1;
    *modifiers = &linear;
    return TRUE;
}

static int getDrawableModifiers(DrawablePtr draw, uint32_t format, uint32_t* num_modifiers, uint64_t** modifiers) {
    *num_modifiers = 0;
    *modifiers = NULL;
    return TRUE;
}

static int fdsFromPixmap(ScreenPtr pScreen, PixmapPtr pixmap, int* fds, uint32_t* strides, uint32_t* offsets,
                         uint64_t* modifier) {
    return 0; /* no exportable pixmaps yet */
}

static const dri3_screen_info_rec dri3Info = {
    .version = 2,
    .open_client = openDevice,
    .pixmap_from_fds = pixmapFromFds,
    .fds_from_pixmap = fdsFromPixmap,
    .get_formats = getFormats,
    .get_modifiers = getModifiers,
    .get_drawable_modifiers = getDrawableModifiers,
};

Bool dnx_dri3_init(ScreenPtr pScreen) {
    if (!dixRegisterPrivateKey(&dmabufKeyRec, PRIVATE_PIXMAP, 0)) return FALSE;
    wrappedDestroyPixmap = pScreen->DestroyPixmap;
    pScreen->DestroyPixmap = destroyPixmap;
    return dri3_screen_init(pScreen, &dri3Info);
}

Bool dnx_dri3_is_dmabuf(PixmapPtr pixmap) {
    struct dmabuf* buffer = pixmap ? pixmapDmabuf(pixmap) : NULL;
    return buffer && buffer->fd >= 0;
}

#define AHB_FORMAT_B8G8R8A8_UNORM 5 /* not in the NDK headers */

PixmapPtr dnx_dri3_create_hardware_pixmap(ScreenPtr pScreen, int width, int height, int depth) {
    /* Cached on the CPU (fb draws into it at malloc speed on Adreno), and
     * readable and writable by the GPU, so the app can copy into and out of
     * it without the CPU. */
    AHardwareBuffer_Desc desc = {
        .width = width,
        .height = height,
        .layers = 1,
        .format = AHB_FORMAT_B8G8R8A8_UNORM,
        .usage = AHARDWAREBUFFER_USAGE_CPU_READ_OFTEN | AHARDWAREBUFFER_USAGE_CPU_WRITE_OFTEN |
                 AHARDWAREBUFFER_USAGE_GPU_SAMPLED_IMAGE | AHARDWAREBUFFER_USAGE_GPU_COLOR_OUTPUT,
    };
    struct dmabuf* buffer = calloc(1, sizeof(*buffer));
    if (!buffer) return NULL;
    buffer->fd = -1;
    buffer->map = MAP_FAILED;
    if (width <= 0 || height <= 0 || depth != 24 || AHardwareBuffer_allocate(&desc, &buffer->ahb) != 0) {
        log(ERROR, "cannot allocate a %dx%d screen buffer", width, height);
        dmabufFree(buffer);
        return NULL;
    }
    AHardwareBuffer_describe(buffer->ahb, &desc);
    /* Locked once for the pixmap's lifetime: the mapping is coherent with the
     * GPU here, and fb needs a fixed address. */
    void* pixels = NULL;
    if (AHardwareBuffer_lock(buffer->ahb, AHARDWAREBUFFER_USAGE_CPU_READ_OFTEN | AHARDWAREBUFFER_USAGE_CPU_WRITE_OFTEN,
                             -1, NULL, &pixels) != 0) {
        log(ERROR, "cannot lock the screen buffer");
        dmabufFree(buffer);
        return NULL;
    }
    buffer->locked = true;

    PixmapPtr pixmap = pScreen->CreatePixmap(pScreen, 0, 0, depth, 0);
    if (!pixmap) {
        dmabufFree(buffer);
        return NULL;
    }
    if (!pScreen->ModifyPixmapHeader(pixmap, width, height, depth, 32, desc.stride * 4, pixels)) {
        pScreen->DestroyPixmap(pixmap);
        dmabufFree(buffer);
        return NULL;
    }
    dixSetPrivate(&pixmap->devPrivates, dmabufKey, buffer);
    return pixmap;
}

AHardwareBuffer* dnx_dri3_hardware_buffer(PixmapPtr pixmap) {
    struct dmabuf* buffer = pixmap ? pixmapDmabuf(pixmap) : NULL;
    return buffer ? buffer->ahb : NULL;
}

void** dnx_dri3_gpu_image(PixmapPtr pixmap) {
    struct dmabuf* buffer = pixmap ? pixmapDmabuf(pixmap) : NULL;
    return buffer && buffer->ahb ? &buffer->gpuImage : NULL;
}

void dnx_dri3_begin_cpu_read(PixmapPtr pixmap) {
    struct dmabuf* buffer = pixmap ? pixmapDmabuf(pixmap) : NULL;
    if (!buffer) return;
    /* The GPU wrote the buffer behind the CPU caches. Start and end at once:
     * the start invalidates the caches, the end only cleans lines nobody
     * dirtied, and the copy that follows happens on the server thread
     * before the client can render into the buffer again. */
    struct dma_buf_sync sync = {.flags = DMA_BUF_SYNC_START | DMA_BUF_SYNC_READ};
    ioctl(buffer->fd, DMA_BUF_IOCTL_SYNC, &sync);
    sync.flags = DMA_BUF_SYNC_END | DMA_BUF_SYNC_READ;
    ioctl(buffer->fd, DMA_BUF_IOCTL_SYNC, &sync);
}
