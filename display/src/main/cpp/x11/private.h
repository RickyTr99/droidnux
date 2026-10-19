/* Internal interface between server.c (thread, input) and output.c (DDX). */
#ifndef DROIDNUX_X11_PRIVATE_H
#define DROIDNUX_X11_PRIVATE_H

#include <stdbool.h>

#include "pixmap.h"
#include "screenint.h"

/* Initial screen geometry, set by server.c before the server starts. */
extern int dnx_initial_width, dnx_initial_height;

/* output.c, server thread only. */
void dnx_output_resize(int width, int height);
void dnx_output_force_redraw(void);

/* dri3.c, server thread. */
Bool dnx_dri3_init(ScreenPtr pScreen);
/* Makes GPU writes to a DRI3 pixmap visible to the CPU; no-op for other pixmaps. */
void dnx_dri3_begin_cpu_read(PixmapPtr pixmap);
/* True for pixmaps imported from a client's dma-buf. */
Bool dnx_dri3_is_dmabuf(PixmapPtr pixmap);
/* The AHardwareBuffer behind a DRI3 pixmap, when the app allocated it. */
struct AHardwareBuffer* dnx_dri3_hardware_buffer(PixmapPtr pixmap);

/* server.c: called from ddxReady() once the server can take input. */
void dnx_server_ready(void);

#endif
