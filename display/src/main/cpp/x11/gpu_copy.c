/*
 * GPU copies between AHardwareBuffers for the X server: Present copies a
 * client's buffer (allocated by the app, core/allocator.h) into the screen
 * pixmap with a GLES blit, so the CPU never reads the client's pixels.
 *
 * One surfaceless GLES context, current on the server thread for the life
 * of the process. Server thread only.
 */
#define EGL_EGLEXT_PROTOTYPES
#define GL_GLEXT_PROTOTYPES

#include <android/hardware_buffer.h>
#include <android/log.h>
#include <EGL/egl.h>
#include <EGL/eglext.h>
#include <GLES3/gl3.h>
#include <GLES2/gl2ext.h>
#include <stdbool.h>
#include <stdlib.h>

#include "private.h"

#define log(prio, ...) __android_log_print(ANDROID_LOG_##prio, "DroidNux.X11", __VA_ARGS__)

/* A buffer as the GPU sees it: a texture to read from, and a framebuffer to
 * read from or draw into. Lives as long as the pixmap. */
struct gpu_image {
    EGLImageKHR image;
    GLuint texture;
    GLuint fbo;
};

static struct {
    EGLDisplay display;
    EGLContext context;
    bool failed; /* no GPU copies for the life of the process */
} gpu = {EGL_NO_DISPLAY, EGL_NO_CONTEXT, false};

static bool gpuReady(void) {
    if (gpu.context != EGL_NO_CONTEXT) return true;
    if (gpu.failed) return false;
    gpu.failed = true;

    gpu.display = eglGetDisplay(EGL_DEFAULT_DISPLAY);
    if (gpu.display == EGL_NO_DISPLAY || !eglInitialize(gpu.display, NULL, NULL)) {
        log(ERROR, "GPU copy: cannot initialize EGL");
        return false;
    }
    const EGLint attributes[] = {EGL_CONTEXT_CLIENT_VERSION, 3, EGL_NONE};
    /* No config and no surface: the context only draws into AHB framebuffers. */
    gpu.context = eglCreateContext(gpu.display, EGL_NO_CONFIG_KHR, EGL_NO_CONTEXT, attributes);
    if (gpu.context == EGL_NO_CONTEXT ||
        !eglMakeCurrent(gpu.display, EGL_NO_SURFACE, EGL_NO_SURFACE, gpu.context)) {
        log(ERROR, "GPU copy: cannot create a GLES context (0x%x)", eglGetError());
        if (gpu.context != EGL_NO_CONTEXT) eglDestroyContext(gpu.display, gpu.context);
        gpu.context = EGL_NO_CONTEXT;
        return false;
    }
    gpu.failed = false;
    return true;
}

static struct gpu_image* gpuImage(AHardwareBuffer* buffer, void** slot) {
    if (*slot) return *slot;
    struct gpu_image* image = calloc(1, sizeof(*image));
    if (!image) return NULL;

    const EGLint attributes[] = {EGL_IMAGE_PRESERVED_KHR, EGL_TRUE, EGL_NONE};
    image->image = eglCreateImageKHR(gpu.display, EGL_NO_CONTEXT, EGL_NATIVE_BUFFER_ANDROID,
                                     eglGetNativeClientBufferANDROID(buffer), attributes);
    if (image->image == EGL_NO_IMAGE_KHR) {
        log(ERROR, "GPU copy: cannot import a buffer (0x%x)", eglGetError());
        free(image);
        return NULL;
    }
    glGenTextures(1, &image->texture);
    glBindTexture(GL_TEXTURE_2D, image->texture);
    glEGLImageTargetTexture2DOES(GL_TEXTURE_2D, image->image);
    glGenFramebuffers(1, &image->fbo);
    glBindFramebuffer(GL_FRAMEBUFFER, image->fbo);
    glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, image->texture, 0);
    GLenum status = glCheckFramebufferStatus(GL_FRAMEBUFFER);
    glBindFramebuffer(GL_FRAMEBUFFER, 0);
    if (status != GL_FRAMEBUFFER_COMPLETE) {
        log(ERROR, "GPU copy: buffer not usable as a framebuffer (0x%x)", status);
        dnx_gpu_image_free(image);
        return NULL;
    }
    *slot = image;
    return image;
}

void dnx_gpu_image_free(void* data) {
    struct gpu_image* image = data;
    if (!image) return;
    glDeleteFramebuffers(1, &image->fbo);
    glDeleteTextures(1, &image->texture);
    eglDestroyImageKHR(gpu.display, image->image);
    free(image);
}

bool dnx_gpu_copy(AHardwareBuffer* src, void** srcSlot, AHardwareBuffer* dst, void** dstSlot, const BoxRec* boxes,
                  int count, int dx, int dy) {
    if (!gpuReady()) return false;
    struct gpu_image* from = gpuImage(src, srcSlot);
    struct gpu_image* to = from ? gpuImage(dst, dstSlot) : NULL;
    if (!to) return false;

    glBindFramebuffer(GL_READ_FRAMEBUFFER, from->fbo);
    glBindFramebuffer(GL_DRAW_FRAMEBUFFER, to->fbo);
    /* Framebuffer rows are buffer rows in both: no flip. */
    for (int i = 0; i < count; i++) {
        const BoxRec* b = &boxes[i];
        glBlitFramebuffer(b->x1 - dx, b->y1 - dy, b->x2 - dx, b->y2 - dy, b->x1, b->y1, b->x2, b->y2,
                          GL_COLOR_BUFFER_BIT, GL_NEAREST);
    }
    glBindFramebuffer(GL_FRAMEBUFFER, 0);

    /* Wait here: fb may draw into the screen pixmap as soon as we return.
     * The wait sleeps on the kernel fence; the blit takes well under 1 ms. */
    EGLSyncKHR sync = eglCreateSyncKHR(gpu.display, EGL_SYNC_FENCE_KHR, NULL);
    if (sync == EGL_NO_SYNC_KHR) {
        glFinish();
        return true;
    }
    EGLint result = eglClientWaitSyncKHR(gpu.display, sync, EGL_SYNC_FLUSH_COMMANDS_BIT_KHR, 1000000000);
    eglDestroySyncKHR(gpu.display, sync);
    if (result != EGL_CONDITION_SATISFIED_KHR) log(ERROR, "GPU copy: fence wait failed (0x%x)", result);
    return true;
}
