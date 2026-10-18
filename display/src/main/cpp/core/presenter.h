/*
 * Presentation core: shows a CPU framebuffer on an Android surface with
 * ASurfaceControl, copying only damaged areas into a small pool of
 * AHardwareBuffers, with the cursor on its own hardware layer and a vsync
 * clock that only ticks on request.
 *
 * C API so the X server (C) can call it. Threading: present/cursor/vsync
 * requests come from the display server's thread; attach/detach/resize from
 * the Android main thread. All entry points are safe to call concurrently.
 */
#ifndef DROIDNUX_PRESENTER_H
#define DROIDNUX_PRESENTER_H

#include <android/native_window.h>
#include <stdbool.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

typedef struct dnx_box {
    int32_t x1, y1, x2, y2;
} dnx_box;

/* Pixel layout of the framebuffer handed to dnx_presenter_present(). */
typedef enum dnx_pixel_format {
    DNX_PIXEL_BGRX8888 = 0, /* uint32 0x00RRGGBB, little endian: B, G, R, X in memory (X11 depth 24) */
} dnx_pixel_format;

/* Called on the vsync thread for each requested frame. */
typedef void (*dnx_vsync_callback)(int64_t frame_time_ns, void* data);

/* Surface lifecycle (Android main thread). The window is acquired. */
void dnx_presenter_attach(ANativeWindow* window);
void dnx_presenter_detach(void);
bool dnx_presenter_attached(void);

/*
 * Copies the union of `rects` (and whatever the target buffer missed in
 * earlier frames) from `pixels` into a free buffer and presents it, scaled to
 * the whole surface. Returns false if nothing could be presented (no surface
 * or no free buffer yet); the caller should keep its damage and retry on the
 * next vsync. A size change implies full damage.
 */
bool dnx_presenter_present(const void* pixels, uint32_t width, uint32_t height, uint32_t stride_bytes,
                           dnx_pixel_format format, const dnx_box* rects, int rect_count);

/* Cursor image in RGBA8888 (straight alpha), NULL or 0x0 hides it. Coordinates are framebuffer pixels. */
void dnx_presenter_set_cursor(const uint32_t* rgba, uint32_t width, uint32_t height, int32_t xhot, int32_t yhot);
void dnx_presenter_move_cursor(int32_t x, int32_t y);
void dnx_presenter_set_cursor_visible(bool visible);

/* Vsync clock: the callback runs once per dnx_presenter_request_vsync(). */
void dnx_presenter_set_vsync_callback(dnx_vsync_callback callback, void* data);
void dnx_presenter_request_vsync(void);

/* Refresh rate of the display the surface is on, in Hz (60 until known). */
int dnx_presenter_refresh_rate(void);

/* Frames presented since start, for the debug overlay. */
uint64_t dnx_presenter_frame_count(void);

#ifdef __cplusplus
}
#endif

#endif /* DROIDNUX_PRESENTER_H */
