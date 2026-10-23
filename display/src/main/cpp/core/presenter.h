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

#include <android/hardware_buffer.h>
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

/* Surface lifecycle (Android main thread). The window is acquired.
 * max_refresh_rate: the display's highest refresh rate, in Hz. */
void dnx_presenter_attach(ANativeWindow* window, float max_refresh_rate);
void dnx_presenter_detach(void);
bool dnx_presenter_attached(void);

/*
 * Copies the union of `rects` (and whatever the target buffer missed in
 * earlier frames) from `pixels` into a free buffer and presents it, scaled to
 * the whole surface. Returns false if nothing could be presented (no surface
 * or no free buffer yet); the caller should keep its damage and retry on the
 * next vsync. A size change implies full damage.
 *
 * If `pixels` live in `buffer` (BGRX, may be NULL), the GPU copies them
 * (gpu_copy.h, same thread rules), keeping its objects for `buffer` in
 * *gpu_image; the CPU copies from `pixels` if the GPU cannot.
 */
bool dnx_presenter_present(const void* pixels, uint32_t width, uint32_t height, uint32_t stride_bytes,
                           dnx_pixel_format format, AHardwareBuffer* buffer, void** gpu_image, const dnx_box* rects,
                           int rect_count);

/* What happened to a buffer given to dnx_presenter_present_buffer(). */
typedef enum dnx_buffer_event {
    DNX_BUFFER_SHOWN,    /* on screen, and the frame shown before it is no longer read */
    DNX_BUFFER_RELEASED, /* no longer read: the client may render into it again */
} dnx_buffer_event;

/* Runs on a binder thread, once per event and buffer. */
typedef void (*dnx_buffer_callback)(dnx_buffer_event event, void* data);

/*
 * Shows the top-left width x height of a client's buffer (BGRA; it may have
 * padding rows) as the whole frame, without copying it. The presenter holds a reference until RELEASED, and
 * reports both events for every buffer it accepted, also when the surface
 * goes away. Returns false, reporting nothing, without a surface.
 */
bool dnx_presenter_present_buffer(AHardwareBuffer* buffer, uint32_t width, uint32_t height, dnx_buffer_callback callback,
                                  void* data);

/*
 * Shows the top-left width x height of a client's buffer (BGRA) on a layer
 * above the desktop, at `where` (framebuffer pixels), without copying it:
 * a window the X server would otherwise copy into the screen. Events and
 * references as for dnx_presenter_present_buffer(). Returns false, reporting
 * nothing, without a surface or before the first frame.
 */
bool dnx_presenter_show_window(AHardwareBuffer* buffer, uint32_t width, uint32_t height, dnx_box where,
                               dnx_buffer_callback callback, void* data);

/* Hides that layer together with the next desktop frame, which should show
 * the window's content by then. Its buffer is RELEASED once that frame is on
 * screen. */
void dnx_presenter_hide_window(void);

/* Cursor image in RGBA8888 (straight alpha), NULL or 0x0 hides it. Coordinates are framebuffer pixels. */
void dnx_presenter_set_cursor(const uint32_t* rgba, uint32_t width, uint32_t height, int32_t xhot, int32_t yhot);
void dnx_presenter_move_cursor(int32_t x, int32_t y);
void dnx_presenter_set_cursor_visible(bool visible);
/* Where the cursor's hotspot is, in framebuffer pixels (the app keeps it above the soft keyboard). */
int32_t dnx_presenter_cursor_y(void);

/* Vsync clock: the callback runs once per dnx_presenter_request_vsync(). */
void dnx_presenter_set_vsync_callback(dnx_vsync_callback callback, void* data);
void dnx_presenter_request_vsync(void);

/* Refresh rate of the display the surface is on, in Hz (60 until known). */
int dnx_presenter_refresh_rate(void);

/* Frames presented since start, for the debug overlay. */
uint64_t dnx_presenter_frame_count(void);

/* True while the last frame (present or present_buffer) waits for
 * SurfaceFlinger to latch it: another one now would replace it on screen
 * (Android 12+; always false before). */
bool dnx_presenter_frame_pending(void);

/* Time spent preparing frames (copying damage, submitting), for the debug
 * overlay: the total since start, and the longest frame since the last call. */
uint64_t dnx_presenter_frame_time_total_ns(void);
uint64_t dnx_presenter_take_frame_time_max_ns(void);

#ifdef __cplusplus
}
#endif

#endif /* DROIDNUX_PRESENTER_H */
