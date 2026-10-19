/*
 * DDX output of the DroidNux X11 front-end.
 *
 * Derived from Termux:X11 (lorie/InitOutput.c, GPL-3.0). The screen lives in
 * a plain CPU framebuffer (fb); instead of handing a root buffer to a separate
 * renderer, damaged areas go to the presentation core (core/presenter.h),
 * which copies them into AHardwareBuffers shown with ASurfaceControl. Frames
 * are produced only on vsyncs that were requested because something changed.
 */
#pragma clang diagnostic ignored "-Wstrict-prototypes"
#pragma clang diagnostic ignored "-Wmissing-prototypes"

#ifdef HAVE_DIX_CONFIG_H
#include <dix-config.h>
#endif

#include <android/log.h>
#include <libxcvt/libxcvt.h>
#include <pthread.h>
#include <stdatomic.h>
#include <stdint.h>
#include <stdlib.h>
#include <sys/eventfd.h>
#include <time.h>
#include <X11/X.h>
#include <X11/Xmd.h>

#include "fb.h"
#include "glxserver.h"
#include "glxutil.h"
#include "inputstr.h"
#include "micmap.h"
#include "miline.h"
#include "mipointer.h"
#include "misyncshm.h"
#include "opaque.h"
#include "present.h"
#include "present_priv.h"
#include "compint.h"
#include "randrstr.h"
#include "shmint.h"
#include "fbconfigs.h"

#include "gpu_copy.h"
#include "presenter.h"
#include "private.h"

#define log(prio, ...) __android_log_print(ANDROID_LOG_##prio, "DroidNux.X11", __VA_ARGS__)

extern DeviceIntPtr lorieMouse;

ScreenPtr pScreenPtr;

struct vblank {
    struct xorg_list link;
    uint64_t id, msc;
};

struct buffer_event {
    struct buffer_event* next;
    dnx_buffer_event event;
    uint64_t flip;               /* a flip's buffer... */
    struct layer_buffer* layer;  /* ...or a window layer's */
};

static struct {
    DamagePtr damage;
    CloseScreenProcPtr CloseScreen;
    int vsyncFd;               /* written by the vsync thread, read on the server thread */
    atomic_bool vsyncRequested; /* a vsync is pending; avoids one request per damage event */
    bool retryPresent;          /* last frame could not be shown, try again next vsync */
    int framerate;
    /* Media stream counter: one per display vsync, also while the clock
     * sleeps, so that Present clients see where the display really is and a
     * flip whose target has passed is shown at once. */
    uint64_t msc;                     /* never decreases */
    uint64_t vsyncMsc;                /* msc of the last vsync seen... */
    int64_t vsyncTimeNs;              /* ...and its time, CLOCK_MONOTONIC */
    _Atomic int64_t lastVsyncNs;      /* written by the vsync thread */
    struct xorg_list vblanks;
    /* Present flips: a full-screen client buffer is shown instead of the
     * screen pixmap, skipping the copy into it. */
    PixmapPtr flipPixmap;       /* shown while flipped; Present holds a reference */
    uint64_t flipEvent;         /* flip to report once flipPixmap has been shown */
    uint64_t unflipEvent;       /* unflip to report at the next frame */
    bool flipDirty;             /* flipPixmap has content not shown yet */
    /* Flips of app-allocated buffers go to the screen as they are; the
     * presenter's events (dnx_buffer_event) say when, through bufferFd. */
    uint64_t shownWaiting;      /* flip handed to the presenter, reported once SHOWN */
    uint64_t lastExternal;      /* flip whose buffer the display may still read, until RELEASED */
    int bufferFd;
    pthread_mutex_t bufferLock;
    struct buffer_event* bufferEvents; /* pushed by binder threads, under bufferLock */
    /* Window layer (dnx_present_copy). */
    struct layer_buffer* layerLatest; /* newest buffer on the layer; NULL: no window on it */
    XID layerWindow;
    BoxRec layerBox; /* where the layer shows it, screen coordinates */
} screen = {
    .vsyncFd = -1,
    .bufferFd = -1,
    .bufferLock = PTHREAD_MUTEX_INITIALIZER,
    .framerate = 60,
    .vblanks = {&screen.vblanks, &screen.vblanks},
};

static Bool TrueNoop() { return TRUE; }
static Bool FalseNoop() { return FALSE; }
static void VoidNoop() {}

/* -- Present copies --------------------------------------------------------
 * Present copies of app-allocated client buffers into the screen pixmap run
 * on the GPU (core/gpu_copy.h), synchronously; anything else takes the CPU
 * path. Termux:X11's xserver still calls these for its GPU copy polling,
 * which our present_execute.c never starts. */

bool lorieConnectionAlive(void) { return true; }
bool lorieRendererAvailable(void) { return true; }
Bool lorieGpuCopyIsDone(uint64_t serial) { return TRUE; }
void lorieGpuCopyAck(PixmapPtr pixmap, void* dst_buffer) {}

/* The region present_copy_region() would draw, in screen coordinates: the
 * update region of the pixmap at the offset, clipped to the window. Only for
 * windows drawn straight into the screen pixmap, whose coordinates are the
 * screen's. */
static void presentRegion(present_vblank_ptr vblank, RegionPtr region) {
    WindowPtr window = vblank->window;
    PixmapPtr pixmap = vblank->pixmap;
    BoxRec box = {0, 0, pixmap->drawable.width, pixmap->drawable.height};
    RegionInit(region, &box, 1);
    if (vblank->update) RegionIntersect(region, region, vblank->update);
    RegionTranslate(region, window->drawable.x + vblank->x_off, window->drawable.y + vblank->y_off);
    RegionIntersect(region, region, &window->clipList);
}

static bool gpuCopy(AHardwareBuffer* src, void** srcImage, PixmapPtr dst, RegionPtr region, int dx, int dy) {
    int count = RegionNumRects(region);
    BoxPtr rects = RegionRects(region);
    dnx_box stackBoxes[64];
    dnx_box* boxes = count <= 64 ? stackBoxes : malloc(sizeof(dnx_box) * count);
    if (!boxes) return false;
    for (int i = 0; i < count; i++) boxes[i] = (dnx_box) {rects[i].x1, rects[i].y1, rects[i].x2, rects[i].y2};
    bool done = dnx_gpu_copy(src, srcImage, dnx_dri3_hardware_buffer(dst), dnx_dri3_gpu_image(dst), boxes, count, dx, dy);
    if (boxes != stackBoxes) free(boxes);
    return done;
}

/* -- Window layer ----------------------------------------------------------
 * A window that shows all of an app-allocated buffer, unobscured, goes on its
 * own layer above the desktop instead of being copied into the screen pixmap
 * (dnx_presenter_show_window). Present hears that each buffer is idle only
 * once the display has let go of it. The screen pixmap keeps what was under
 * the layer until the window leaves it: then its newest buffer is copied in. */

struct layer_buffer {
    PixmapPtr pixmap; /* a reference, until idle */
    XID window;       /* for the idle notification, if the window still exists */
    CARD32 serial;
    struct present_fence* idleFence;
    bool released; /* the display no longer reads it */
};

static WindowPtr lookupWindow(XID id) {
    void* window = NULL;
    if (!id || dixLookupResourceByType(&window, id, RT_WINDOW, NullClient, DixGetAttrAccess) != Success) return NULL;
    return window;
}

static BoxRec windowBox(WindowPtr window) {
    return (BoxRec) {window->drawable.x, window->drawable.y, window->drawable.x + window->drawable.width,
                     window->drawable.y + window->drawable.height};
}

/* What of the window shows on screen, in screen coordinates (to uninit);
 * false if X does not put it there by itself. A window in a subtree that X
 * redirected on its own (an ARGB toplevel in a 24-bit parent: Firefox,
 * Chromium) draws into the subtree's pixmap, which Composite copies into the
 * screen as it is (PictOpSrc, alpha dropped): it shows like any other window.
 * A compositing manager's redirection is not ours to skip. */
static bool shownRegion(WindowPtr window, RegionPtr region) {
    PixmapPtr screenPixmap = pScreenPtr->GetScreenPixmap(pScreenPtr);
    PixmapPtr pixmap = pScreenPtr->GetWindowPixmap(window);
    RegionNull(region);
    if (!window->viewable) return false;
    RegionCopy(region, &window->clipList);
    if (pixmap == screenPixmap) return true;
    WindowPtr top = window;
    while (top->parent && pScreenPtr->GetWindowPixmap(top->parent) == pixmap) top = top->parent;
    CompWindowPtr cw = NULL;
    if (top->parent && pScreenPtr->GetWindowPixmap(top->parent) == screenPixmap) cw = GetCompWindow(top);
    if (!cw || cw->update != CompositeRedirectAutomatic) {
        RegionEmpty(region);
        return false;
    }
    RegionIntersect(region, region, &cw->borderClip);
    return true;
}

/* The window shows all of the pixmap on screen, and nothing covers it. */
static bool layerFits(WindowPtr window, PixmapPtr pixmap) {
    if (screen.flipPixmap || !dnx_dri3_hardware_buffer(pScreenPtr->GetScreenPixmap(pScreenPtr)) ||
        !dnx_dri3_hardware_buffer(pixmap) || pixmap->drawable.bitsPerPixel != 32 ||
        pixmap->drawable.width != window->drawable.width || pixmap->drawable.height != window->drawable.height)
        return false;
    BoxRec box = windowBox(window);
    RegionRec shown;
    bool fits = shownRegion(window, &shown) && RegionNumRects(&shown) == 1;
    BoxPtr clip = RegionExtents(&shown);
    fits = fits && clip->x1 == box.x1 && clip->y1 == box.y1 && clip->x2 == box.x2 && clip->y2 == box.y2;
    RegionUninit(&shown);
    return fits;
}

/* Idle once the display let go of it and it is no longer the window's
 * newest buffer, which leaveLayer() may still need. */
static void layerBufferIdle(struct layer_buffer* buffer) {
    if (!buffer->released || buffer == screen.layerLatest) return;
    present_pixmap_idle(buffer->pixmap, lookupWindow(buffer->window), buffer->serial, buffer->idleFence);
    present_fence_destroy(buffer->idleFence);
    buffer->pixmap->drawable.pScreen->DestroyPixmap(buffer->pixmap);
    free(buffer);
}

/* Takes the window off the layer, with the next desktop frame. */
static void leaveLayer(void) {
    struct layer_buffer* buffer = screen.layerLatest;
    if (!buffer) return;
    screen.layerLatest = NULL;
    PixmapPtr screenPixmap = pScreenPtr->GetScreenPixmap(pScreenPtr);
    WindowPtr window = lookupWindow(screen.layerWindow);
    RegionRec region;
    RegionInit(&region, &screen.layerBox, 1);
    RegionRec shown;
    RegionNull(&shown); /* the window may be gone */
    if (window && shownRegion(window, &shown)) {
        BoxRec box = {window->drawable.x, window->drawable.y, window->drawable.x + buffer->pixmap->drawable.width,
                      window->drawable.y + buffer->pixmap->drawable.height};
        RegionRec frame;
        RegionInit(&frame, &box, 1);
        RegionIntersect(&shown, &shown, &frame);
        RegionUninit(&frame);
        if (gpuCopy(dnx_dri3_hardware_buffer(buffer->pixmap), dnx_dri3_gpu_image(buffer->pixmap), screenPixmap,
                    &shown, window->drawable.x, window->drawable.y)) {
            RegionUnion(&region, &region, &shown);
        }
        /* A redirected window's own pixmap too: Composite copies it into
         * the screen again whenever the window is damaged. */
        if (pScreenPtr->GetWindowPixmap(window) != screenPixmap) {
            dnx_dri3_begin_cpu_read(buffer->pixmap);
            present_copy_region(&window->drawable, buffer->pixmap, NULL, 0, 0);
        }
    }
    RegionUninit(&shown);
    /* The frame that hides the layer shows what was under it. */
    dnx_presenter_hide_window();
    DamageDamageRegion(&screenPixmap->drawable, &region);
    RegionUninit(&region);
    screen.layerWindow = 0;
    layerBufferIdle(buffer);
}

/* Binder thread: a layer buffer is on screen or released. */
static void onLayerBufferEvent(dnx_buffer_event event, void* data) {
    struct buffer_event* e = malloc(sizeof(*e));
    if (!e) return;
    *e = (struct buffer_event) {.event = event, .layer = data};
    pthread_mutex_lock(&screen.bufferLock);
    e->next = screen.bufferEvents;
    screen.bufferEvents = e;
    pthread_mutex_unlock(&screen.bufferLock);
    eventfd_write(screen.bufferFd, 1);
}

static bool showOnLayer(present_vblank_ptr vblank) {
    WindowPtr window = vblank->window;
    PixmapPtr pixmap = vblank->pixmap;
    /* The update region does not matter: app-allocated buffers only come from
     * our Mesa's swapchains (patch 0003), whose images are always whole
     * frames; the region (incremental present, WebRender) only says what
     * changed. */
    if (vblank->x_off || vblank->y_off || !layerFits(window, pixmap)) return false;
    struct layer_buffer* buffer = calloc(1, sizeof(*buffer));
    if (!buffer) return false;
    if (screen.layerLatest && screen.layerWindow != window->drawable.id) leaveLayer();

    BoxRec box = windowBox(window);
    dnx_box where = {box.x1, box.y1, box.x2, box.y2};
    if (!dnx_presenter_show_window(dnx_dri3_hardware_buffer(pixmap), pixmap->drawable.width,
                                   pixmap->drawable.height, where, onLayerBufferEvent, buffer)) {
        free(buffer);
        return false;
    }
    *buffer = (struct layer_buffer) {
        .pixmap = pixmap, .window = window->drawable.id, .serial = vblank->serial, .idleFence = vblank->idle_fence};
    pixmap->refcnt++;
    vblank->idle_fence = NULL;
    if (vblank->update) {
        RegionDestroy(vblank->update);
        vblank->update = NULL;
    }
    struct layer_buffer* previous = screen.layerLatest;
    screen.layerLatest = buffer;
    screen.layerWindow = window->drawable.id;
    screen.layerBox = box;
    if (previous) layerBufferIdle(previous);
    return true;
}

/* Before a desktop frame: the window still fits the layer, where it is. */
static void checkLayer(void) {
    if (!screen.layerLatest) return;
    WindowPtr window = lookupWindow(screen.layerWindow);
    BoxRec box = window ? windowBox(window) : (BoxRec) {0};
    if (window && layerFits(window, screen.layerLatest->pixmap) && box.x1 == screen.layerBox.x1 &&
        box.y1 == screen.layerBox.y1 && box.x2 == screen.layerBox.x2 && box.y2 == screen.layerBox.y2)
        return;
    leaveLayer();
}

enum dnx_present_copy dnx_present_copy(present_vblank_ptr vblank) {
    WindowPtr window = vblank->window;
    PixmapPtr pixmap = vblank->pixmap;
    PixmapPtr screenPixmap = pScreenPtr->GetScreenPixmap(pScreenPtr);
    AHardwareBuffer* src = dnx_dri3_hardware_buffer(pixmap);
    if (showOnLayer(vblank)) return DNX_PRESENT_COPY_LAYER;
    /* Copied from now on: under the layer, the copy would not show. */
    if (screen.layerLatest && screen.layerWindow == window->drawable.id) leaveLayer();
    if (!src || pixmap->drawable.bitsPerPixel != 32 || pScreenPtr->GetWindowPixmap(window) != screenPixmap ||
        !dnx_dri3_hardware_buffer(screenPixmap)) {
        dnx_dri3_begin_cpu_read(pixmap);
        return DNX_PRESENT_COPY_CPU;
    }

    RegionRec region;
    presentRegion(vblank, &region);
    int dx = window->drawable.x + vblank->x_off, dy = window->drawable.y + vblank->y_off;
    if (!gpuCopy(src, dnx_dri3_gpu_image(pixmap), screenPixmap, &region, dx, dy)) {
        RegionUninit(&region);
        dnx_dri3_begin_cpu_read(pixmap);
        return DNX_PRESENT_COPY_CPU;
    }
    /* What present_copy_region() would have damaged, through the window so
     * that Composite and DAMAGE clients see it too. */
    DamageDamageRegion(&window->drawable, &region);
    RegionUninit(&region);
    if (vblank->update) {
        RegionDestroy(vblank->update);
        vblank->update = NULL;
    }
    return DNX_PRESENT_COPY_GPU;
}

/* Keyboard focus moved between windows (patched enterleave.c). Will drive the
 * soft keyboard; nothing to do yet. */
void DDXNotifyFocusChanged(void) {}

/* -- DDX entry points ----------------------------------------------------- */

void OsVendorInit(void) {}

void OsVendorFatalError(const char* f, va_list args) {
    __android_log_vprint(ANDROID_LOG_ERROR, "DroidNux.X11", f, args);
}

void ddxGiveUp(enum ExitCode error) {
    log(ERROR, "X server stopped (%d)", error);
}

void ddxReady(void) {
    CursorVisible = TRUE;
    pScreenPtr->DisplayCursor(lorieMouse, pScreenPtr, rootCursor);
    dnx_server_ready();
}

#if defined(DDXBEFORERESET)
void ddxBeforeReset(void) {}
#endif

#if INPUTTHREAD
void ddxInputThreadInit(void) {}
#endif

void ddxUseMsg(void) {}

int ddxProcessArgument(int argc, char* argv[], int i) {
    return 0;
}

/* -- Frame clock ---------------------------------------------------------- */

static void requestFrame(void) {
    if (!atomic_exchange(&screen.vsyncRequested, true)) dnx_presenter_request_vsync();
}

/* Vsync thread: wake the server thread. */
static void onVsync(int64_t frameTimeNanos, void* data) {
    atomic_store(&screen.lastVsyncNs, frameTimeNanos);
    eventfd_write(screen.vsyncFd, 1);
}

static int64_t vsyncPeriodNs(void) {
    return 1000000000LL / (screen.framerate > 0 ? screen.framerate : 60);
}

/* Vsyncs between the last one seen and `timeNs`, rounded. */
static int64_t vsyncsSince(int64_t timeNs) {
    int64_t period = vsyncPeriodNs();
    if (screen.vsyncTimeNs == 0 || timeNs <= screen.vsyncTimeNs) return 0;
    return (timeNs - screen.vsyncTimeNs + period / 2) / period;
}

/* A vsync the clock reported, counting those it slept through. */
static void onVsyncTime(int64_t vsyncNs) {
    int64_t elapsed = vsyncsSince(vsyncNs);
    screen.vsyncMsc += elapsed > 0 ? elapsed : 1;
    screen.vsyncTimeNs = vsyncNs;
    if (screen.vsyncMsc > screen.msc) screen.msc = screen.vsyncMsc;
}

/* Between vsyncs: where the display is now, extrapolated from the last one. */
static void updateMsc(int64_t nowNs) {
    int64_t period = vsyncPeriodNs();
    if (screen.vsyncTimeNs == 0 || nowNs <= screen.vsyncTimeNs) return;
    uint64_t now = screen.vsyncMsc + (uint64_t) ((nowNs - screen.vsyncTimeNs) / period);
    if (now > screen.msc) screen.msc = now;
}

static uint64_t mscTimeNs(void) {
    return (uint64_t) (screen.vsyncTimeNs + (int64_t) (screen.msc - screen.vsyncMsc) * vsyncPeriodNs());
}

static uint64_t nowNs(void) {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (uint64_t) ts.tv_sec * 1000000000ULL + ts.tv_nsec;
}

static void performVblanks(void) {
    struct vblank *vblank, *tmp;
    xorg_list_for_each_entry_safe(vblank, tmp, &screen.vblanks, link) {
        if (vblank->msc <= screen.msc) {
            present_event_notify(vblank->id, GetTimeInMicros(), screen.msc);
            xorg_list_del(&vblank->link);
            free(vblank);
        }
    }
}

/* Binder thread: a buffer given to dnx_presenter_present_buffer() is on
 * screen or released. */
static void onBufferEvent(dnx_buffer_event event, void* data) {
    struct buffer_event* e = malloc(sizeof(*e));
    if (!e) return;
    *e = (struct buffer_event) {.event = event, .flip = (uint64_t) (uintptr_t) data};
    pthread_mutex_lock(&screen.bufferLock);
    e->next = screen.bufferEvents;
    screen.bufferEvents = e;
    pthread_mutex_unlock(&screen.bufferLock);
    eventfd_write(screen.bufferFd, 1);
}

static void reportUnflip(void) {
    if (!screen.unflipEvent || screen.lastExternal) return;
    uint64_t event = screen.unflipEvent;
    screen.unflipEvent = 0;
    present_event_notify(event, GetTimeInMicros(), screen.msc);
}

/* Server thread: the presenter's events, oldest first. */
static void onBufferEvents(int fd, int ready, void* data) {
    eventfd_t value;
    eventfd_read(fd, &value);
    pthread_mutex_lock(&screen.bufferLock);
    struct buffer_event* list = screen.bufferEvents;
    screen.bufferEvents = NULL;
    pthread_mutex_unlock(&screen.bufferLock);

    struct buffer_event* ordered = NULL;
    while (list) {
        struct buffer_event* next = list->next;
        list->next = ordered;
        ordered = list;
        list = next;
    }
    while (ordered) {
        struct buffer_event* e = ordered;
        ordered = e->next;
        if (e->layer) {
            if (e->event == DNX_BUFFER_RELEASED) {
                e->layer->released = true;
                layerBufferIdle(e->layer);
            }
        } else if (e->event == DNX_BUFFER_SHOWN && e->flip == screen.shownWaiting) {
            screen.shownWaiting = 0;
            present_event_notify(e->flip, GetTimeInMicros(), screen.msc);
        } else if (e->event == DNX_BUFFER_RELEASED && e->flip == screen.lastExternal) {
            screen.lastExternal = 0;
            reportUnflip();
        }
        free(e);
    }
}

/* Shows the flipped client buffer, whole, then reports the flip. */
static void presentFlip(void) {
    PixmapPtr pixmap = screen.flipPixmap;
    AHardwareBuffer* hardwareBuffer = dnx_dri3_hardware_buffer(pixmap);
    if (screen.flipDirty && hardwareBuffer && screen.flipEvent) {
        /* No copy: the buffer itself goes to the display, and Present
         * hears of the flip once it is on screen (onBufferEvents). */
        uint64_t event = screen.flipEvent;
        if (dnx_presenter_present_buffer(hardwareBuffer, pixmap->drawable.width, pixmap->drawable.height, onBufferEvent,
                                         (void*) (uintptr_t) event)) {
            screen.flipDirty = false;
            screen.flipEvent = 0;
            screen.shownWaiting = event;
            screen.lastExternal = event;
            return;
        }
    }
    if (screen.flipDirty) {
        dnx_box all = {0, 0, pixmap->drawable.width, pixmap->drawable.height};
        dnx_dri3_begin_cpu_read(pixmap);
        if (dnx_presenter_present(pixmap->devPrivate.ptr, pixmap->drawable.width, pixmap->drawable.height,
                                  pixmap->devKind, DNX_PIXEL_BGRX8888, hardwareBuffer, dnx_dri3_gpu_image(pixmap),
                                  &all, 1)) {
            screen.flipDirty = false;
        } else if (!dnx_presenter_attached()) {
            /* Nothing to show it on: the frame is lost, but the client
             * must not wait for it (dnx_x11_redraw shows it again). */
            screen.flipDirty = false;
        }
    }
    if (!screen.flipDirty && screen.flipEvent) {
        uint64_t event = screen.flipEvent;
        screen.flipEvent = 0;
        present_event_notify(event, GetTimeInMicros(), screen.msc);
    }
}

/* Server thread, once per requested vsync. */
static void onFrame(int fd, int ready, void* data) {
    eventfd_t value;
    eventfd_read(fd, &value);
    screen.vsyncRequested = false;
    /* Resynchronized on the real vsync, so extrapolation never drifts. */
    onVsyncTime(atomic_load(&screen.lastVsyncNs));
    performVblanks();

    /* After an app-allocated buffer, only once the display let go of it. */
    reportUnflip();
    checkLayer();

    if (screen.flipPixmap) {
        presentFlip();
        if (screen.flipDirty || screen.flipEvent) requestFrame();
        return;
    }

    PixmapPtr pixmap = pScreenPtr ? pScreenPtr->GetScreenPixmap(pScreenPtr) : NULL;
    RegionPtr region = screen.damage ? DamageRegion(screen.damage) : NULL;
    bool damaged = region && RegionNotEmpty(region);

    if (pixmap && (damaged || screen.retryPresent)) {
        dnx_box stackBoxes[64];
        int count = damaged ? RegionNumRects(region) : 0;
        dnx_box* boxes = count <= 64 ? stackBoxes : malloc(sizeof(dnx_box) * count);
        BoxPtr rects = damaged ? RegionRects(region) : NULL;
        for (int i = 0; i < count; i++) boxes[i] = (dnx_box) {rects[i].x1, rects[i].y1, rects[i].x2, rects[i].y2};

        /* The presenter records the damage whether or not it can show it now. */
        bool shown = dnx_presenter_present(pixmap->devPrivate.ptr, pixmap->drawable.width, pixmap->drawable.height,
                                           pixmap->devKind, DNX_PIXEL_BGRX8888, dnx_dri3_hardware_buffer(pixmap),
                                           dnx_dri3_gpu_image(pixmap), boxes, count);
        if (boxes != stackBoxes) free(boxes);
        if (damaged) DamageEmpty(screen.damage);
        /* Without a surface there is nothing to retry until one is attached
         * (dnx_x11_redraw), so do not keep the clock ticking. */
        screen.retryPresent = !shown && dnx_presenter_attached();
    }

    if (screen.retryPresent || !xorg_list_is_empty(&screen.vblanks)) requestFrame();
}

/* Damage went from empty to non-empty. */
static void onDamage(DamagePtr damage, RegionPtr region, void* closure) {
    requestFrame();
}

void dnx_output_force_redraw(void) {
    if (!pScreenPtr) return;
    leaveLayer();
    screen.flipDirty = screen.flipPixmap != NULL;
    /* A flip in flight when the surface went away may never be reported
     * by the presenter; its client must not wait for it forever. */
    if (screen.shownWaiting) {
        uint64_t event = screen.shownWaiting;
        screen.shownWaiting = 0;
        present_event_notify(event, GetTimeInMicros(), screen.msc);
    }
    BoxRec box = {0, 0, pScreenPtr->width, pScreenPtr->height};
    RegionRec all;
    RegionInit(&all, &box, 1);
    DamageDamageRegion(&pScreenPtr->GetScreenPixmap(pScreenPtr)->drawable, &all);
    RegionUninit(&all);
    requestFrame();
}

/* -- Cursor --------------------------------------------------------------- */

static Bool cursorFromMouse(DeviceIntPtr pDev) {
    return !pDev || GetMaster(pDev, MASTER_POINTER) == inputInfo.pointer;
}

static void moveCursor(DeviceIntPtr pDev, ScreenPtr pScr, int x, int y) {
    if (cursorFromMouse(pDev)) dnx_presenter_move_cursor(x, y);
}

static void setCursor(DeviceIntPtr pDev, ScreenPtr pScr, CursorPtr pCurs, int x0, int y0) {
    if (!cursorFromMouse(pDev)) return;
    if (!pCurs || !pCurs->bits) {
        dnx_presenter_set_cursor(NULL, 0, 0, 0, 0);
        return;
    }
    CursorBitsPtr bits = pCurs->bits;
    uint32_t* rgba = malloc(sizeof(uint32_t) * bits->width * bits->height);
    if (!rgba) return;
    if (bits->argb) {
        /* X ARGB cursors are premultiplied 0xAARRGGBB; the layer wants
         * premultiplied RGBA bytes. */
        for (int i = 0; i < bits->width * bits->height; i++) {
            CARD32 p = bits->argb[i];
            rgba[i] = (p & 0xFF00FF00) | ((p & 0x00FF0000) >> 16) | ((p & 0x000000FF) << 16);
        }
    } else {
        uint32_t fg = ((pCurs->foreBlue & 0xff00) << 8) | (pCurs->foreGreen & 0xff00) | (pCurs->foreRed >> 8);
        uint32_t bg = ((pCurs->backBlue & 0xff00) << 8) | (pCurs->backGreen & 0xff00) | (pCurs->backRed >> 8);
        int stride = BitmapBytePad(bits->width);
        uint32_t* p = rgba;
        for (int y = 0; y < bits->height; y++) {
            for (int x = 0; x < bits->width; x++) {
                int i = y * stride + x / 8, bit = 1 << (x & 7);
                uint32_t d = (bits->source[i] & bit) ? fg : bg;
                *p++ = (bits->mask[i] & bit) ? d | 0xff000000 : 0;
            }
        }
    }
    dnx_presenter_set_cursor(rgba, bits->width, bits->height, bits->xhot, bits->yhot);
    free(rgba);
    dnx_presenter_move_cursor(x0, y0);
}

static miPointerSpriteFuncRec spriteFuncs = {
    .RealizeCursor = TrueNoop,
    .UnrealizeCursor = TrueNoop,
    .SetCursor = setCursor,
    .MoveCursor = moveCursor,
    .DeviceCursorInitialize = TrueNoop,
    .DeviceCursorCleanup = VoidNoop,
};

static miPointerScreenFuncRec pointerScreenFuncs = {
    .CursorOffScreen = FalseNoop,
    .CrossScreen = VoidNoop,
    .WarpCursor = miPointerWarpCursor,
};

/* -- Screen pixmap and RandR ---------------------------------------------- */

static void trackScreenPixmap(PixmapPtr pixmap) {
    if (screen.damage) {
        DamageUnregister(screen.damage);
        DamageDestroy(screen.damage);
    }
    screen.damage = DamageCreate(onDamage, NULL, DamageReportNonEmpty, TRUE, pScreenPtr, NULL);
    if (!screen.damage) FatalError("Couldn't set up damage\n");
    DamageRegister(&pixmap->drawable, screen.damage);
}

/* In an AHardwareBuffer, so the GPU can copy into and out of the screen;
 * plain memory if that fails. */
static PixmapPtr createScreenPixmap(ScreenPtr pScreen, int width, int height) {
    PixmapPtr pixmap = dnx_dri3_create_hardware_pixmap(pScreen, width, height, pScreen->rootDepth);
    return pixmap ? pixmap : pScreen->CreatePixmap(pScreen, width, height, pScreen->rootDepth, 0);
}

static Bool createScreenResources(ScreenPtr pScreen) {
    PixmapPtr pixmap = createScreenPixmap(pScreen, pScreen->width, pScreen->height);
    if (!pixmap) return FALSE;
    pScreen->devPrivate = pixmap;
    trackScreenPixmap(pixmap);
    return TRUE;
}

static Bool closeScreen(ScreenPtr pScreen) {
    if (screen.damage) {
        DamageUnregister(screen.damage);
        DamageDestroy(screen.damage);
        screen.damage = NULL;
    }
    pScreen->DestroyPixmap(pScreen->devPrivate);
    pScreen->devPrivate = NULL;
    pScreenPtr = NULL;
    pScreen->CloseScreen = screen.CloseScreen;
    return pScreen->CloseScreen(pScreen);
}

static RRModePtr cvtMode(int width, int height, int framerate) {
    struct libxcvt_mode_info* info = libxcvt_gen_mode_info(width, height, framerate, 0, 0);
    char name[128];
    xRRModeInfo modeinfo = {0};

    snprintf(name, sizeof name, "%dx%d", info->hdisplay, info->vdisplay);
    modeinfo.nameLength = strlen(name);
    modeinfo.width = info->hdisplay;
    modeinfo.height = info->vdisplay;
    modeinfo.dotClock = info->dot_clock * 1000.0;
    modeinfo.hSyncStart = info->hsync_start;
    modeinfo.hSyncEnd = info->hsync_end;
    modeinfo.hTotal = info->htotal;
    modeinfo.vSyncStart = info->vsync_start;
    modeinfo.vSyncEnd = info->vsync_end;
    modeinfo.vTotal = info->vtotal;
    modeinfo.modeFlags = info->mode_flags;
    free(info);
    return RRModeGet(&modeinfo, name);
}

static int setPixmapVisitWindow(WindowPtr window, void* data) {
    ScreenPtr pScreen = window->drawable.pScreen;
    if (pScreen->GetWindowPixmap(window) == data) {
        pScreen->SetWindowPixmap(window, pScreen->GetScreenPixmap(pScreen));
        return WT_WALKCHILDREN;
    }
    return WT_DONTWALKCHILDREN;
}

static Bool rrScreenSetSize(ScreenPtr pScreen, CARD16 width, CARD16 height, CARD32 mmWidth, CARD32 mmHeight) {
    BoxRec box = {0, 0, width, height};

    performVblanks();
    leaveLayer();
    present_restore_screen_pixmap(pScreen);
    /* The screen pixmap has the flip's content again; Present unflips at
     * its next frame, as the window no longer covers the screen. */
    screen.flipPixmap = NULL;
    SetRootClip(pScreen, ROOT_CLIP_NONE);

    pScreen->root->drawable.width = pScreen->width = width;
    pScreen->root->drawable.height = pScreen->height = height;
    pScreen->mmWidth = width * 25.4 / monitorResolution;
    pScreen->mmHeight = height * 25.4 / monitorResolution;

    PixmapPtr oldPixmap = pScreen->GetScreenPixmap(pScreen);
    PixmapPtr newPixmap = createScreenPixmap(pScreen, width, height);
    pScreen->SetScreenPixmap(newPixmap);
    trackScreenPixmap(newPixmap);

    if (oldPixmap) {
        GCPtr gc = GetScratchGC(newPixmap->drawable.depth, pScreen);
        if (gc) {
            ValidateGC(&newPixmap->drawable, gc);
            gc->ops->CopyArea(&oldPixmap->drawable, &newPixmap->drawable, gc, 0, 0,
                              min(oldPixmap->drawable.width, newPixmap->drawable.width),
                              min(oldPixmap->drawable.height, newPixmap->drawable.height), 0, 0);
            FreeScratchGC(gc);
        }
        TraverseTree(pScreen->root, setPixmapVisitWindow, oldPixmap);
        pScreen->DestroyPixmap(oldPixmap);
    }

    pScreen->ResizeWindow(pScreen->root, 0, 0, width, height, NULL);
    RegionReset(&pScreen->root->winSize, &box);
    SetRootClip(pScreen, ROOT_CLIP_FULL);

    RRScreenSizeNotify(pScreen);
    update_desktop_dimensions();
    dnx_output_force_redraw();
    return TRUE;
}

static Bool rrCrtcSet(ScreenPtr pScreen, RRCrtcPtr crtc, RRModePtr mode, int x, int y, Rotation rotation,
                      int numOutput, RROutputPtr* outputs) {
    return (crtc && mode) ? RRCrtcNotify(crtc, mode, x, y, rotation, NULL, numOutput, outputs) : FALSE;
}

static Bool rrGetInfo(ScreenPtr pScreen, Rotation* rotations) {
    *rotations = RR_Rotate_0;
    return TRUE;
}

static Bool randrInit(ScreenPtr pScreen) {
    static char outputName[] = "DroidNux";
    RROutputPtr output;
    RRCrtcPtr crtc;
    RRModePtr mode;

    if (!RRScreenInit(pScreen)) return FALSE;
    rrScrPrivPtr priv = rrGetScrPriv(pScreen);
    priv->rrGetInfo = rrGetInfo;
    priv->rrCrtcSet = rrCrtcSet;
    priv->rrScreenSetSize = rrScreenSetSize;
    RRScreenSetSizeRange(pScreen, 1, 1, 32767, 32767);

    return (mode = cvtMode(pScreen->width, pScreen->height, screen.framerate)) &&
           (crtc = RRCrtcCreate(pScreen, NULL)) && RRCrtcGammaSetSize(crtc, 256) &&
           (output = RROutputCreate(pScreen, outputName, strlen(outputName), NULL)) &&
           RROutputSetClones(output, NULL, 0) && RROutputSetModes(output, &mode, 1, 0) &&
           RROutputSetCrtcs(output, &crtc, 1) && RROutputSetConnection(output, RR_Connected) &&
           RRCrtcNotify(crtc, mode, 0, 0, RR_Rotate_0, NULL, 1, &output);
}

void dnx_output_resize(int width, int height) {
    ScreenPtr pScreen = pScreenPtr;
    RROutputPtr output = pScreen ? RRFirstOutput(pScreen) : NULL;
    int framerate = dnx_presenter_refresh_rate();
    if (!output || width <= 0 || height <= 0) return;
    if (pScreen->width == width && pScreen->height == height && screen.framerate == framerate) return;

    RRModePtr mode = cvtMode(width, height, framerate);
    RROutputSetModes(output, &mode, 1, 0);
    RRCrtcNotify(RRFirstEnabledCrtc(pScreen), mode, 0, 0, RR_Rotate_0, NULL, 1, &output);
    RRScreenSizeSet(pScreen, width, height, width * 25.4 / monitorResolution, height * 25.4 / monitorResolution);
    screen.framerate = framerate;
    log(INFO, "desktop resized to %dx%d@%d", width, height, framerate);
}

/* -- Present: vblanks follow the real display vsync ----------------------- */

static RRCrtcPtr presentGetCrtc(WindowPtr w) {
    return RRFirstEnabledCrtc(w->drawable.pScreen);
}

static int presentGetUstMsc(RRCrtcPtr crtc, uint64_t* ust, uint64_t* msc) {
    updateMsc(nowNs());
    *ust = mscTimeNs() / 1000;
    *msc = screen.msc;
    return Success;
}

static Bool presentQueueVblank(RRCrtcPtr crtc, uint64_t event_id, uint64_t msc) {
    struct vblank* vblank = calloc(1, sizeof(*vblank));
    if (!vblank) return BadAlloc;
    *vblank = (struct vblank) {.id = event_id, .msc = msc};
    xorg_list_add(&vblank->link, &screen.vblanks);
    requestFrame();
    return Success;
}

static void presentAbortVblank(RRCrtcPtr crtc, uint64_t id, uint64_t msc) {
    struct vblank *vblank, *tmp;
    xorg_list_for_each_entry_safe(vblank, tmp, &screen.vblanks, link) {
        if (vblank->id == id) {
            xorg_list_del(&vblank->link);
            free(vblank);
            break;
        }
    }
}

/* Present already checked that the window covers the whole screen,
 * unredirected and unobscured, with a pixmap of exactly its size. */
static Bool presentCheckFlip(RRCrtcPtr crtc, WindowPtr window, PixmapPtr pixmap, Bool syncFlip) {
    return dnx_dri3_is_dmabuf(pixmap) && pixmap->drawable.bitsPerPixel == 32;
}

static Bool presentFlipPixmap(RRCrtcPtr crtc, uint64_t eventId, uint64_t targetMsc, PixmapPtr pixmap, Bool syncFlip) {
    /* Present flips again only after the last flip event (flip_pending). */
    leaveLayer();
    screen.flipPixmap = pixmap;
    screen.flipEvent = eventId;
    screen.flipDirty = true;
    if (dnx_dri3_hardware_buffer(pixmap)) {
        presentFlip();
        if (!screen.flipDirty) return TRUE;
    }
    requestFrame();
    return TRUE;
}

static void presentAfterFlip(RRCrtcPtr crtc, uint64_t eventId, uint64_t ust, uint64_t targetMsc, PixmapPtr pixmap) {}

static void presentUnflip(ScreenPtr pScreen, uint64_t eventId) {
    /* present_unflip() has copied the last flip into the screen pixmap,
     * damaging it, so the next frame shows the screen pixmap again. */
    screen.flipPixmap = NULL;
    screen.unflipEvent = eventId;
    requestFrame();
}

static struct present_screen_info presentInfo = {
    .get_crtc = presentGetCrtc,
    .get_ust_msc = presentGetUstMsc,
    .queue_vblank = presentQueueVblank,
    .abort_vblank = presentAbortVblank,
    .check_flip = presentCheckFlip,
    .flip = presentFlipPixmap,
    .after_flip = presentAfterFlip,
    .unflip = presentUnflip,
};

/* -- Screen --------------------------------------------------------------- */

static Bool screenInit(ScreenPtr pScreen, int argc, char** argv) {
    pScreenPtr = pScreen;

    if (screen.vsyncFd < 0) {
        screen.vsyncFd = eventfd(0, EFD_CLOEXEC | EFD_NONBLOCK);
        dnx_presenter_set_vsync_callback(onVsync, NULL);
    }
    if (screen.bufferFd < 0) screen.bufferFd = eventfd(0, EFD_CLOEXEC | EFD_NONBLOCK);
    SetNotifyFd(screen.vsyncFd, onFrame, X_NOTIFY_READ, NULL);
    SetNotifyFd(screen.bufferFd, onBufferEvents, X_NOTIFY_READ, NULL);
    screen.framerate = dnx_presenter_refresh_rate();

    miSetZeroLineBias(pScreen, 0);
    pScreen->blackPixel = 0;
    pScreen->whitePixel = 1;

    if (!miSetVisualTypesAndMasks(24, (1 << TrueColor) | (1 << DirectColor), 8, TrueColor, 0xFF0000, 0x00FF00,
                                  0x0000FF) ||
        !miSetPixmapDepths() ||
        !fbScreenInit(pScreen, NULL, dnx_initial_width, dnx_initial_height, monitorResolution, monitorResolution,
                      0, 32) ||
        !(pScreen->CreateScreenResources = createScreenResources) ||
        !fbPictureInit(pScreen, 0, 0) ||
        !randrInit(pScreen) ||
        !miPointerInitialize(pScreen, &spriteFuncs, &pointerScreenFuncs, TRUE) ||
        !fbCreateDefColormap(pScreen) ||
        !present_screen_init(pScreen, &presentInfo) ||
        !dnx_dri3_init(pScreen))
        return FALSE;

    screen.CloseScreen = pScreen->CloseScreen;
    pScreen->CloseScreen = closeScreen;

    ShmRegisterFbFuncs(pScreen);
    miSyncShmScreenInit(pScreen);
    return TRUE;
}

void InitOutput(ScreenInfo* screenInfo, int argc, char** argv) {
    int depths[] = {1, 4, 8, 15, 16, 24, 32};
    int bpp[] = {1, 8, 8, 16, 16, 32, 32};

    if (monitorResolution == 0) monitorResolution = 96;

    for (int i = 0; i < ARRAY_SIZE(depths); i++) {
        screenInfo->formats[i].depth = depths[i];
        screenInfo->formats[i].bitsPerPixel = bpp[i];
        screenInfo->formats[i].scanlinePad = BITMAP_SCANLINE_PAD;
    }
    screenInfo->imageByteOrder = IMAGE_BYTE_ORDER;
    screenInfo->bitmapScanlineUnit = BITMAP_SCANLINE_UNIT;
    screenInfo->bitmapScanlinePad = BITMAP_SCANLINE_PAD;
    screenInfo->bitmapBitOrder = BITMAP_BIT_ORDER;
    screenInfo->numPixmapFormats = ARRAY_SIZE(depths);

    xorgGlxCreateVendor();

    if (AddScreen(screenInit, argc, argv) == -1) FatalError("Couldn't add screen\n");
}

/* -- GLX: software rendering in the client (swrast), from Termux:X11 ------- */

static GLboolean drawableSwapBuffers(ClientPtr client, __GLXdrawable* drawable) { return TRUE; }
static void drawableCopySubBuffer(__GLXdrawable* basePrivate, int x, int y, int w, int h) {}

static __GLXdrawable* createDrawable(ClientPtr client, __GLXscreen* glxScreen, DrawablePtr pDraw, XID drawId,
                                     int type, XID glxDrawId, __GLXconfig* glxConfig) {
    __GLXdrawable* private = calloc(1, sizeof *private);
    if (private == NULL) return NULL;
    if (!__glXDrawableInit(private, glxScreen, pDraw, type, glxDrawId, glxConfig)) {
        free(private);
        return NULL;
    }
    private->destroy = (void (*)(__GLXdrawable*)) free;
    private->swapBuffers = drawableSwapBuffers;
    private->copySubBuffer = drawableCopySubBuffer;
    return private;
}

static void glxScreenDestroy(__GLXscreen* baseScreen) {
    free(baseScreen->GLXextensions);
    free(baseScreen->GLextensions);
    free(baseScreen->visuals);
    free(baseScreen);
}

static __GLXscreen* glxScreenProbe(ScreenPtr pScreen) {
    __GLXscreen* glxScreen = calloc(1, sizeof *glxScreen);
    if (glxScreen == NULL) return NULL;

    glxScreen->destroy = glxScreenDestroy;
    glxScreen->createDrawable = createDrawable;
    glxScreen->pScreen = pScreen;
    glxScreen->fbconfigs = configs;
    glxScreen->glvnd = "mesa";

    __glXInitExtensionEnableBits(glxScreen->glx_enable_bits);
    __glXEnableExtension(glxScreen->glx_enable_bits, "GLX_MESA_copy_sub_buffer");
    __glXEnableExtension(glxScreen->glx_enable_bits, "GLX_EXT_no_config_context");
    __glXEnableExtension(glxScreen->glx_enable_bits, "GLX_ARB_create_context");
    __glXEnableExtension(glxScreen->glx_enable_bits, "GLX_ARB_create_context_no_error");
    __glXEnableExtension(glxScreen->glx_enable_bits, "GLX_ARB_create_context_profile");
    __glXEnableExtension(glxScreen->glx_enable_bits, "GLX_EXT_create_context_es_profile");
    __glXEnableExtension(glxScreen->glx_enable_bits, "GLX_EXT_create_context_es2_profile");
    __glXEnableExtension(glxScreen->glx_enable_bits, "GLX_EXT_framebuffer_sRGB");
    __glXEnableExtension(glxScreen->glx_enable_bits, "GLX_ARB_fbconfig_float");
    __glXEnableExtension(glxScreen->glx_enable_bits, "GLX_EXT_fbconfig_packed_float");
    __glXEnableExtension(glxScreen->glx_enable_bits, "GLX_EXT_texture_from_pixmap");
    __glXScreenInit(glxScreen, pScreen);
    return glxScreen;
}

__GLXprovider __glXDRISWRastProvider = {
    glxScreenProbe,
    "DRISWRAST",
    NULL,
};
