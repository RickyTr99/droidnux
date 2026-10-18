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
#include <stdatomic.h>
#include <sys/eventfd.h>
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
#include "randrstr.h"
#include "shmint.h"
#include "fbconfigs.h"

#include "presenter.h"
#include "private.h"

#define log(prio, ...) __android_log_print(ANDROID_LOG_##prio, "DroidNux.X11", __VA_ARGS__)

extern DeviceIntPtr lorieMouse;

ScreenPtr pScreenPtr;

struct vblank {
    struct xorg_list link;
    uint64_t id, msc;
};

static struct {
    DamagePtr damage;
    CloseScreenProcPtr CloseScreen;
    int vsyncFd;               /* written by the vsync thread, read on the server thread */
    atomic_bool vsyncRequested; /* a vsync is pending; avoids one request per damage event */
    bool retryPresent;          /* last frame could not be shown, try again next vsync */
    int framerate;
    uint64_t msc;
    struct xorg_list vblanks;
} screen = {
    .vsyncFd = -1,
    .framerate = 60,
    .vblanks = {&screen.vblanks, &screen.vblanks},
};

static Bool TrueNoop() { return TRUE; }
static Bool FalseNoop() { return FALSE; }
static void VoidNoop() {}

/* -- Hooks the Termux:X11 xserver patch expects ----------------------------
 * Present copies always take the CPU path here (no separate renderer). */

bool lorieConnectionAlive(void) { return true; }
bool lorieRendererAvailable(void) { return true; }
Bool lorieGpuCopyIsDone(uint64_t serial) { return TRUE; }
void lorieGpuCopyAck(PixmapPtr pixmap, void* dst_buffer) {}
Bool lorieTryScheduleGpuCopy(PixmapPtr pixmap, PixmapPtr dst, RegionPtr update, int16_t x_off, int16_t y_off,
                             uint64_t* out_serial, void** out_dst_buffer) {
    return FALSE;
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
    eventfd_write(screen.vsyncFd, 1);
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

/* Server thread, once per requested vsync. */
static void onFrame(int fd, int ready, void* data) {
    eventfd_t value;
    eventfd_read(fd, &value);
    screen.vsyncRequested = false;
    screen.msc++;
    performVblanks();

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
                                           pixmap->devKind, DNX_PIXEL_BGRX8888, boxes, count);
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

static Bool createScreenResources(ScreenPtr pScreen) {
    PixmapPtr pixmap = pScreen->CreatePixmap(pScreen, pScreen->width, pScreen->height, pScreen->rootDepth, 0);
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
    present_restore_screen_pixmap(pScreen);
    SetRootClip(pScreen, ROOT_CLIP_NONE);

    pScreen->root->drawable.width = pScreen->width = width;
    pScreen->root->drawable.height = pScreen->height = height;
    pScreen->mmWidth = width * 25.4 / monitorResolution;
    pScreen->mmHeight = height * 25.4 / monitorResolution;

    PixmapPtr oldPixmap = pScreen->GetScreenPixmap(pScreen);
    PixmapPtr newPixmap = pScreen->CreatePixmap(pScreen, width, height, pScreen->rootDepth, 0);
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
    *ust = GetTimeInMicros();
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

static struct present_screen_info presentInfo = {
    .get_crtc = presentGetCrtc,
    .get_ust_msc = presentGetUstMsc,
    .queue_vblank = presentQueueVblank,
    .abort_vblank = presentAbortVblank,
    .check_flip = FalseNoop, /* Phase 1: every Present is a copy */
};

/* -- Screen --------------------------------------------------------------- */

static Bool screenInit(ScreenPtr pScreen, int argc, char** argv) {
    pScreenPtr = pScreen;

    if (screen.vsyncFd < 0) {
        screen.vsyncFd = eventfd(0, EFD_CLOEXEC | EFD_NONBLOCK);
        dnx_presenter_set_vsync_callback(onVsync, NULL);
    }
    SetNotifyFd(screen.vsyncFd, onFrame, X_NOTIFY_READ, NULL);
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
        !present_screen_init(pScreen, &presentInfo))
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
