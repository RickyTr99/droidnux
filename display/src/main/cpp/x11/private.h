/* Internal interface between server.c (thread, input) and output.c (DDX). */
#ifndef DROIDNUX_X11_PRIVATE_H
#define DROIDNUX_X11_PRIVATE_H

#include <stdbool.h>

/* Initial screen geometry, set by server.c before the server starts. */
extern int dnx_initial_width, dnx_initial_height;

/* output.c, server thread only. */
void dnx_output_resize(int width, int height);
void dnx_output_force_redraw(void);

/* server.c: called from ddxReady() once the server can take input. */
void dnx_server_ready(void);

#endif
