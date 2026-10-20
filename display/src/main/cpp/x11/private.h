/* Internal interface between server.c (thread, input) and output.c (DDX). */
#ifndef DROIDNUX_X11_PRIVATE_H
#define DROIDNUX_X11_PRIVATE_H

#include <stdbool.h>

#include "miscstruct.h"
#include "pixmap.h"
#include "screenint.h"

/* Initial screen geometry, set by server.c before the server starts. */
extern int dnx_initial_width, dnx_initial_height;

/* output.c, server thread only. */
void dnx_output_resize(int width, int height);
void dnx_output_force_redraw(void);
/* Server thread: the screen scaled to width x height, 0xAARRGGBB. */
void dnx_output_snapshot(uint32_t* pixels, int width, int height);

/* dri3.c, server thread. */
Bool dnx_dri3_init(ScreenPtr pScreen);
/* Makes GPU writes to a DRI3 pixmap visible to the CPU; no-op for other pixmaps. */
void dnx_dri3_begin_cpu_read(PixmapPtr pixmap);
/* True for pixmaps imported from a client's dma-buf. */
Bool dnx_dri3_is_dmabuf(PixmapPtr pixmap);
/* The AHardwareBuffer behind a DRI3 pixmap, when the app allocated it, or
 * behind a pixmap from dnx_dri3_create_hardware_pixmap(). */
struct AHardwareBuffer* dnx_dri3_hardware_buffer(PixmapPtr pixmap);
/* A depth 24 pixmap in an AHardwareBuffer the CPU and the GPU both use (the
 * screen pixmap); NULL if it cannot be allocated. */
PixmapPtr dnx_dri3_create_hardware_pixmap(ScreenPtr pScreen, int width, int height, int depth);

/* Where the GPU objects for a pixmap's AHardwareBuffer are kept (see
 * core/gpu_copy.h), freed with the pixmap; NULL for pixmaps without one. */
void** dnx_dri3_gpu_image(PixmapPtr pixmap);

/* output.c: how present_execute_copy() (present_execute.c) shows a Present
 * request. The DDX did all but the flush and the idle notification, unless
 * it says CPU: then Present copies on the CPU as usual. LAYER: the buffer is
 * on screen as it is, and the DDX sends its idle notification later. */
enum dnx_present_copy { DNX_PRESENT_COPY_CPU, DNX_PRESENT_COPY_GPU, DNX_PRESENT_COPY_LAYER };
struct present_vblank;
enum dnx_present_copy dnx_present_copy(struct present_vblank* vblank);

/* server.c: called from ddxReady() once the server can take input. */
void dnx_server_ready(void);

#endif
