/*
 * GPU copies between AHardwareBuffers, shared by the presenter and the
 * protocol front-ends: one surfaceless GLES context, made current on the
 * first call's thread for the life of the process. Call only from that
 * thread (the display server's).
 */
#ifndef DROIDNUX_GPU_COPY_H
#define DROIDNUX_GPU_COPY_H

#include <android/hardware_buffer.h>
#include <stdbool.h>

#include "presenter.h"

#ifdef __cplusplus
extern "C" {
#endif

/*
 * Copies boxes of dst (x1, y1, x2, y2) from the same boxes of src moved by
 * (-dx, -dy), and waits until the GPU is done. The GPU objects for each
 * buffer are made at the first copy and kept in *srcImage and *dstImage;
 * their owner frees them with dnx_gpu_image_free(). false if the GPU cannot
 * do it (the caller copies on the CPU).
 */
bool dnx_gpu_copy(AHardwareBuffer* src, void** srcImage, AHardwareBuffer* dst, void** dstImage, const dnx_box* boxes,
                  int count, int dx, int dy);
void dnx_gpu_image_free(void* image);

#ifdef __cplusplus
}
#endif

#endif /* DROIDNUX_GPU_COPY_H */
