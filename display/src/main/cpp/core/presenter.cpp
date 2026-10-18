#include "presenter.h"

#include <android/choreographer.h>
#include <android/hardware_buffer.h>
#include <android/log.h>
#include <android/looper.h>
#include <android/surface_control.h>
#include <poll.h>
#include <sys/eventfd.h>
#include <unistd.h>

#include <algorithm>
#include <atomic>
#include <condition_variable>
#include <cstring>
#include <mutex>
#include <thread>
#include <vector>

#define LOG_TAG "DroidNux.Presenter"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

constexpr int kBufferCount = 3;
constexpr size_t kMaxMissedRects = 48;  // beyond this, missed damage collapses to its bounding box
constexpr uint32_t kCursorSize = 256;   // largest cursor image kept (X cursors are almost always <= 64)
constexpr uint32_t kFormatBgra8888 = 5; // AHARDWAREBUFFER_FORMAT_B8G8R8A8_UNORM, not in the NDK headers

constexpr uint64_t kFrameUsage = AHARDWAREBUFFER_USAGE_CPU_WRITE_OFTEN | AHARDWAREBUFFER_USAGE_GPU_SAMPLED_IMAGE |
                                 AHARDWAREBUFFER_USAGE_COMPOSER_OVERLAY;

// ---------------------------------------------------------------------------
// Vsync clock: AChoreographer on a dedicated looper thread, ticking only when
// someone asked for a frame.

class VsyncClock {
public:
    VsyncClock() {
        eventFd_ = eventfd(0, EFD_CLOEXEC | EFD_NONBLOCK);
        thread_ = std::thread([this] { run(); });
        std::unique_lock lock(mutex_);
        ready_.wait(lock, [this] { return started_; });
    }

    void setCallback(dnx_vsync_callback callback, void* data) {
        std::lock_guard lock(mutex_);
        callback_ = callback;
        data_ = data;
    }

    void request() { eventfd_write(eventFd_, 1); }

    int refreshRate() const { return refreshRate_.load(); }

private:
    void run() {
        ALooper* looper = ALooper_prepare(0);
        choreographer_ = AChoreographer_getInstance();
        ALooper_addFd(looper, eventFd_, ALOOPER_POLL_CALLBACK, ALOOPER_EVENT_INPUT, onRequest, this);
        if (__builtin_available(android 30, *)) {
            AChoreographer_registerRefreshRateCallback(choreographer_, onRefreshRate, this);
        }
        {
            std::lock_guard lock(mutex_);
            started_ = true;
        }
        ready_.notify_all();
        for (;;) ALooper_pollOnce(-1, nullptr, nullptr, nullptr);
    }

    static int onRequest(int fd, int, void* data) {
        auto* self = static_cast<VsyncClock*>(data);
        eventfd_t value;
        eventfd_read(fd, &value);
        if (!self->posted_) {
            self->posted_ = true;
            AChoreographer_postFrameCallback64(self->choreographer_, onFrame, self);
        }
        return 1;  // keep the fd registered
    }

    static void onFrame(int64_t frameTimeNanos, void* data) {
        auto* self = static_cast<VsyncClock*>(data);
        self->posted_ = false;
        dnx_vsync_callback callback;
        void* callbackData;
        {
            std::lock_guard lock(self->mutex_);
            callback = self->callback_;
            callbackData = self->data_;
        }
        if (callback != nullptr) callback(frameTimeNanos, callbackData);
    }

    static void onRefreshRate(int64_t vsyncPeriodNanos, void* data) {
        if (vsyncPeriodNanos > 0) {
            static_cast<VsyncClock*>(data)->refreshRate_ =
                static_cast<int>((1'000'000'000LL + vsyncPeriodNanos / 2) / vsyncPeriodNanos);
        }
    }

    int eventFd_ = -1;
    std::thread thread_;
    AChoreographer* choreographer_ = nullptr;
    bool posted_ = false;  // looper thread only
    std::atomic<int> refreshRate_{60};

    std::mutex mutex_;
    std::condition_variable ready_;
    bool started_ = false;
    dnx_vsync_callback callback_ = nullptr;
    void* data_ = nullptr;
};

// ---------------------------------------------------------------------------

struct FrameBuffer {
    AHardwareBuffer* buffer = nullptr;
    uint32_t stride = 0;  // pixels
    bool busy = false;    // presented, release fence not signalled yet
    bool fullDamage = true;
    std::vector<dnx_box> missed;  // damage not copied into this buffer yet
};

struct CursorBuffer {
    AHardwareBuffer* buffer = nullptr;
    uint32_t stride = 0;
    bool busy = false;
};

class Presenter;

// One per transaction (a transaction has a single completion callback): which
// buffers it replaced on each layer, so they can be reused once released.
struct Completion {
    Presenter* presenter;
    uint64_t generation;
    bool frame = false;  // the transaction set a frame buffer
    int framePrevious = -1;
    bool cursor = false;  // the transaction set a cursor buffer
    int cursorPrevious = -1;
};

class Presenter {
public:
    void attach(ANativeWindow* window) {
        std::lock_guard lock(mutex_);
        detachLocked();
        ANativeWindow_acquire(window);
        window_ = window;
        surface_ = ASurfaceControl_createFromWindow(window, "droidnux-desktop");
        cursorSurface_ = surface_ ? ASurfaceControl_create(surface_, "droidnux-cursor") : nullptr;
        if (surface_ == nullptr || cursorSurface_ == nullptr) {
            LOGE("cannot create surface controls");
            detachLocked();
            return;
        }
        windowWidth_ = ANativeWindow_getWidth(window);
        windowHeight_ = ANativeWindow_getHeight(window);
        generation_++;
        for (FrameBuffer& b : frames_) {
            b.busy = false;
            b.fullDamage = true;
            b.missed.clear();
        }
        for (CursorBuffer& c : cursors_) c.busy = false;
        onScreen_ = -1;
        cursorOnScreen_ = -1;
        geometryDirty_ = true;
        cursorDirty_ = true;
        pending_ = true;  // repaint everything on the new surface
        LOGI("attached %dx%d", windowWidth_, windowHeight_);
    }

    void detach() {
        std::lock_guard lock(mutex_);
        detachLocked();
    }

    bool attached() {
        std::lock_guard lock(mutex_);
        return surface_ != nullptr;
    }

    bool present(const void* pixels, uint32_t width, uint32_t height, uint32_t stride, dnx_pixel_format,
                 const dnx_box* rects, int count) {
        std::lock_guard lock(mutex_);
        if (width != width_ || height != height_) {
            reallocateFramesLocked(width, height);
        }
        recordDamageLocked(rects, count);
        if (count > 0) pending_ = true;
        if (!pending_ || surface_ == nullptr) return false;

        int index = -1;
        for (int i = 0; i < kBufferCount; i++) {
            if (frames_[i].buffer != nullptr && !frames_[i].busy) {
                index = i;
                break;
            }
        }
        if (index < 0) return false;
        FrameBuffer& target = frames_[index];

        void* dst = nullptr;
        if (AHardwareBuffer_lock(target.buffer, AHARDWAREBUFFER_USAGE_CPU_WRITE_OFTEN, -1, nullptr, &dst) != 0) {
            LOGE("cannot lock frame buffer");
            return false;
        }
        if (target.fullDamage) {
            dnx_box all{0, 0, static_cast<int32_t>(width), static_cast<int32_t>(height)};
            copyRect(static_cast<const uint8_t*>(pixels), stride, static_cast<uint8_t*>(dst), target.stride * 4, all);
        } else {
            for (const dnx_box& box : target.missed) {
                copyRect(static_cast<const uint8_t*>(pixels), stride, static_cast<uint8_t*>(dst), target.stride * 4,
                         box);
            }
        }
        target.missed.clear();
        target.fullDamage = false;
        int acquireFence = -1;
        AHardwareBuffer_unlock(target.buffer, &acquireFence);

        ASurfaceTransaction* t = ASurfaceTransaction_create();
        ASurfaceTransaction_setBuffer(t, surface_, target.buffer, acquireFence);
        if (geometryDirty_) {
            ARect source{0, 0, static_cast<int32_t>(width), static_cast<int32_t>(height)};
            ARect destination{0, 0, windowWidth_, windowHeight_};
            ASurfaceTransaction_setGeometry(t, surface_, source, destination, ANATIVEWINDOW_TRANSFORM_IDENTITY);
            ASurfaceTransaction_setBufferTransparency(t, surface_, ASURFACE_TRANSACTION_TRANSPARENCY_OPAQUE);
            ASurfaceTransaction_setVisibility(t, surface_, ASURFACE_TRANSACTION_VISIBILITY_SHOW);
            ASurfaceTransaction_setZOrder(t, cursorSurface_, 1);
            geometryDirty_ = false;
            cursorDirty_ = true;  // cursor geometry depends on the scale
        }
        auto* completion = new Completion{this, generation_};
        completion->frame = true;
        completion->framePrevious = onScreen_;
        if (cursorDirty_) applyCursorLocked(t, completion);
        ASurfaceTransaction_setOnComplete(t, completion, onComplete);
        ASurfaceTransaction_apply(t);
        ASurfaceTransaction_delete(t);

        target.busy = true;
        onScreen_ = index;
        pending_ = false;
        frameCount_++;
        return true;
    }

    void setCursor(const uint32_t* rgba, uint32_t width, uint32_t height, int32_t xhot, int32_t yhot) {
        std::lock_guard lock(mutex_);
        cursorWidth_ = rgba ? std::min(width, kCursorSize) : 0;
        cursorHeight_ = rgba ? std::min(height, kCursorSize) : 0;
        cursorImage_.assign(rgba ? rgba : nullptr, rgba ? rgba + width * height : nullptr);
        cursorImageWidth_ = width;
        cursorHotX_ = xhot;
        cursorHotY_ = yhot;
        cursorImageDirty_ = true;
        cursorDirty_ = true;
        flushCursorLocked();
    }

    void moveCursor(int32_t x, int32_t y) {
        std::lock_guard lock(mutex_);
        if (x == cursorX_ && y == cursorY_) return;
        cursorX_ = x;
        cursorY_ = y;
        cursorDirty_ = true;
        flushCursorLocked();
    }

    void setCursorVisible(bool visible) {
        std::lock_guard lock(mutex_);
        if (visible == cursorVisible_) return;
        cursorVisible_ = visible;
        cursorDirty_ = true;
        flushCursorLocked();
    }

    uint64_t frameCount() const { return frameCount_.load(); }

    static void onComplete(void* context, ASurfaceTransactionStats* stats) {
        auto* completion = static_cast<Completion*>(context);
        completion->presenter->complete(*completion, stats);
        delete completion;
    }

private:
    void complete(const Completion& completion, ASurfaceTransactionStats* stats) {
        int frameFence = -1;
        int cursorFence = -1;
        bool releaseFrame = completion.frame && completion.framePrevious >= 0;
        bool releaseCursor = completion.cursor && completion.cursorPrevious >= 0;
        {
            std::lock_guard lock(mutex_);
            if (completion.generation != generation_ || (!releaseFrame && !releaseCursor)) return;
            if (releaseFrame) frameFence = ASurfaceTransactionStats_getPreviousReleaseFenceFd(stats, surface_);
            if (releaseCursor) cursorFence = ASurfaceTransactionStats_getPreviousReleaseFenceFd(stats, cursorSurface_);
        }
        // The replaced buffers may still be read by the display until their
        // release fences signal; wait outside the lock.
        for (int fence : {frameFence, cursorFence}) {
            if (fence < 0) continue;
            pollfd pfd{fence, POLLIN, 0};
            poll(&pfd, 1, 1000);
            close(fence);
        }
        std::lock_guard lock(mutex_);
        if (completion.generation != generation_) return;
        if (releaseFrame) frames_[completion.framePrevious].busy = false;
        if (releaseCursor) cursors_[completion.cursorPrevious].busy = false;
    }

    void detachLocked() {
        if (surface_ != nullptr) {
            ASurfaceTransaction* t = ASurfaceTransaction_create();
            if (cursorSurface_ != nullptr) ASurfaceTransaction_reparent(t, cursorSurface_, nullptr);
            ASurfaceTransaction_reparent(t, surface_, nullptr);
            ASurfaceTransaction_apply(t);
            ASurfaceTransaction_delete(t);
        }
        if (cursorSurface_ != nullptr) ASurfaceControl_release(cursorSurface_);
        if (surface_ != nullptr) ASurfaceControl_release(surface_);
        if (window_ != nullptr) ANativeWindow_release(window_);
        cursorSurface_ = nullptr;
        surface_ = nullptr;
        window_ = nullptr;
        generation_++;  // pending completions refer to the old surface
        for (FrameBuffer& b : frames_) b.busy = false;
        for (CursorBuffer& c : cursors_) c.busy = false;
    }

    void reallocateFramesLocked(uint32_t width, uint32_t height) {
        for (FrameBuffer& b : frames_) {
            if (b.buffer != nullptr) AHardwareBuffer_release(b.buffer);
            b = FrameBuffer{};
        }
        width_ = width;
        height_ = height;
        AHardwareBuffer_Desc desc{};
        desc.width = width;
        desc.height = height;
        desc.layers = 1;
        desc.usage = kFrameUsage;
        desc.format = kFormatBgra8888;
        swizzle_ = AHardwareBuffer_isSupported(&desc) == 0;
        if (swizzle_) desc.format = AHARDWAREBUFFER_FORMAT_R8G8B8X8_UNORM;
        for (FrameBuffer& b : frames_) {
            if (AHardwareBuffer_allocate(&desc, &b.buffer) != 0) {
                LOGE("cannot allocate %ux%u frame buffer", width, height);
                b.buffer = nullptr;
                continue;
            }
            AHardwareBuffer_Desc actual{};
            AHardwareBuffer_describe(b.buffer, &actual);
            b.stride = actual.stride;
        }
        onScreen_ = -1;
        geometryDirty_ = true;
        pending_ = true;
        generation_++;  // completions for the old buffers must not touch the new ones
        LOGI("frame buffers %ux%u, %s", width, height, swizzle_ ? "RGBX with swizzle" : "BGRA");
    }

    void recordDamageLocked(const dnx_box* rects, int count) {
        if (count <= 0) return;
        for (FrameBuffer& b : frames_) {
            if (b.fullDamage) continue;
            b.missed.insert(b.missed.end(), rects, rects + count);
            if (b.missed.size() > kMaxMissedRects) {
                dnx_box bounds = b.missed[0];
                for (const dnx_box& r : b.missed) {
                    bounds.x1 = std::min(bounds.x1, r.x1);
                    bounds.y1 = std::min(bounds.y1, r.y1);
                    bounds.x2 = std::max(bounds.x2, r.x2);
                    bounds.y2 = std::max(bounds.y2, r.y2);
                }
                b.missed.assign(1, bounds);
            }
        }
    }

    void copyRect(const uint8_t* src, uint32_t srcStride, uint8_t* dst, uint32_t dstStride, dnx_box box) const {
        box.x1 = std::clamp<int32_t>(box.x1, 0, static_cast<int32_t>(width_));
        box.x2 = std::clamp<int32_t>(box.x2, 0, static_cast<int32_t>(width_));
        box.y1 = std::clamp<int32_t>(box.y1, 0, static_cast<int32_t>(height_));
        box.y2 = std::clamp<int32_t>(box.y2, 0, static_cast<int32_t>(height_));
        if (box.x2 <= box.x1 || box.y2 <= box.y1) return;
        const size_t rowBytes = static_cast<size_t>(box.x2 - box.x1) * 4;
        for (int32_t y = box.y1; y < box.y2; y++) {
            const uint8_t* s = src + static_cast<size_t>(y) * srcStride + static_cast<size_t>(box.x1) * 4;
            uint8_t* d = dst + static_cast<size_t>(y) * dstStride + static_cast<size_t>(box.x1) * 4;
            if (!swizzle_) {
                std::memcpy(d, s, rowBytes);
            } else {
                auto* sp = reinterpret_cast<const uint32_t*>(s);
                auto* dp = reinterpret_cast<uint32_t*>(d);
                for (int32_t x = box.x1; x < box.x2; x++) {
                    uint32_t p = *sp++;  // 0x00RRGGBB -> bytes R, G, B, X
                    *dp++ = ((p >> 16) & 0xFF) | (p & 0xFF00) | ((p & 0xFF) << 16) | 0xFF000000u;
                }
            }
        }
    }

    // Cursor ---------------------------------------------------------------

    void flushCursorLocked() {
        if (surface_ == nullptr || !cursorDirty_) return;
        ASurfaceTransaction* t = ASurfaceTransaction_create();
        auto* completion = new Completion{this, generation_};
        applyCursorLocked(t, completion);
        ASurfaceTransaction_setOnComplete(t, completion, onComplete);
        ASurfaceTransaction_apply(t);
        ASurfaceTransaction_delete(t);
    }

    void applyCursorLocked(ASurfaceTransaction* t, Completion* completion) {
        cursorDirty_ = false;
        bool show = cursorVisible_ && cursorWidth_ > 0 && cursorHeight_ > 0 && width_ > 0;
        if (!show) {
            ASurfaceTransaction_setVisibility(t, cursorSurface_, ASURFACE_TRANSACTION_VISIBILITY_HIDE);
            return;
        }
        if (cursorImageDirty_ && !uploadCursorLocked(t, completion)) return;

        // Framebuffer to surface scale (the desktop may run below native resolution).
        float sx = static_cast<float>(windowWidth_) / static_cast<float>(width_);
        float sy = static_cast<float>(windowHeight_) / static_cast<float>(height_);
        ARect source{0, 0, static_cast<int32_t>(cursorWidth_), static_cast<int32_t>(cursorHeight_)};
        int32_t left = static_cast<int32_t>((cursorX_ - cursorHotX_) * sx);
        int32_t top = static_cast<int32_t>((cursorY_ - cursorHotY_) * sy);
        ARect destination{left, top, left + static_cast<int32_t>(cursorWidth_ * sx),
                          top + static_cast<int32_t>(cursorHeight_ * sy)};
        ASurfaceTransaction_setGeometry(t, cursorSurface_, source, destination, ANATIVEWINDOW_TRANSFORM_IDENTITY);
        ASurfaceTransaction_setVisibility(t, cursorSurface_, ASURFACE_TRANSACTION_VISIBILITY_SHOW);
    }

    bool uploadCursorLocked(ASurfaceTransaction* t, Completion* completion) {
        int index = -1;
        for (int i = 0; i < 2; i++) {
            if (!cursors_[i].busy && i != cursorOnScreen_) {
                index = i;
                break;
            }
        }
        if (index < 0) {
            cursorDirty_ = true;  // try again with the next frame or cursor event
            return false;
        }
        CursorBuffer& c = cursors_[index];
        if (c.buffer == nullptr) {
            AHardwareBuffer_Desc desc{};
            desc.width = kCursorSize;
            desc.height = kCursorSize;
            desc.layers = 1;
            desc.format = AHARDWAREBUFFER_FORMAT_R8G8B8A8_UNORM;
            desc.usage = kFrameUsage;
            if (AHardwareBuffer_allocate(&desc, &c.buffer) != 0) {
                LOGE("cannot allocate cursor buffer");
                c.buffer = nullptr;
                return false;
            }
            AHardwareBuffer_Desc actual{};
            AHardwareBuffer_describe(c.buffer, &actual);
            c.stride = actual.stride;
        }
        void* dst = nullptr;
        if (AHardwareBuffer_lock(c.buffer, AHARDWAREBUFFER_USAGE_CPU_WRITE_OFTEN, -1, nullptr, &dst) != 0) return false;
        auto* out = static_cast<uint32_t*>(dst);
        for (uint32_t y = 0; y < kCursorSize; y++) {
            for (uint32_t x = 0; x < kCursorSize; x++) {
                out[y * c.stride + x] =
                    (x < cursorWidth_ && y < cursorHeight_) ? cursorImage_[y * cursorImageWidth_ + x] : 0;
            }
        }
        int fence = -1;
        AHardwareBuffer_unlock(c.buffer, &fence);
        ASurfaceTransaction_setBuffer(t, cursorSurface_, c.buffer, fence);
        ASurfaceTransaction_setBufferTransparency(t, cursorSurface_, ASURFACE_TRANSACTION_TRANSPARENCY_TRANSLUCENT);
        completion->cursor = true;
        completion->cursorPrevious = cursorOnScreen_;
        c.busy = true;
        cursorOnScreen_ = index;
        cursorImageDirty_ = false;
        return true;
    }

    std::mutex mutex_;
    ANativeWindow* window_ = nullptr;
    ASurfaceControl* surface_ = nullptr;
    ASurfaceControl* cursorSurface_ = nullptr;
    int32_t windowWidth_ = 0;
    int32_t windowHeight_ = 0;
    uint64_t generation_ = 0;

    FrameBuffer frames_[kBufferCount];
    uint32_t width_ = 0;
    uint32_t height_ = 0;
    bool swizzle_ = false;
    int onScreen_ = -1;
    bool geometryDirty_ = true;
    bool pending_ = false;
    std::atomic<uint64_t> frameCount_{0};

    CursorBuffer cursors_[2];
    int cursorOnScreen_ = -1;
    std::vector<uint32_t> cursorImage_;
    uint32_t cursorImageWidth_ = 0;
    uint32_t cursorWidth_ = 0;
    uint32_t cursorHeight_ = 0;
    int32_t cursorHotX_ = 0;
    int32_t cursorHotY_ = 0;
    int32_t cursorX_ = 0;
    int32_t cursorY_ = 0;
    bool cursorVisible_ = true;
    bool cursorDirty_ = true;
    bool cursorImageDirty_ = false;
};

Presenter& presenter() {
    static Presenter* instance = new Presenter();  // lives as long as the process
    return *instance;
}

VsyncClock& vsyncClock() {
    static VsyncClock* instance = new VsyncClock();
    return *instance;
}

}  // namespace

extern "C" {

void dnx_presenter_attach(ANativeWindow* window) { presenter().attach(window); }
void dnx_presenter_detach(void) { presenter().detach(); }
bool dnx_presenter_attached(void) { return presenter().attached(); }

bool dnx_presenter_present(const void* pixels, uint32_t width, uint32_t height, uint32_t stride_bytes,
                           dnx_pixel_format format, const dnx_box* rects, int rect_count) {
    return presenter().present(pixels, width, height, stride_bytes, format, rects, rect_count);
}

void dnx_presenter_set_cursor(const uint32_t* rgba, uint32_t width, uint32_t height, int32_t xhot, int32_t yhot) {
    presenter().setCursor(rgba, width, height, xhot, yhot);
}
void dnx_presenter_move_cursor(int32_t x, int32_t y) { presenter().moveCursor(x, y); }
void dnx_presenter_set_cursor_visible(bool visible) { presenter().setCursorVisible(visible); }

void dnx_presenter_set_vsync_callback(dnx_vsync_callback callback, void* data) {
    vsyncClock().setCallback(callback, data);
}
void dnx_presenter_request_vsync(void) { vsyncClock().request(); }
int dnx_presenter_refresh_rate(void) { return vsyncClock().refreshRate(); }
uint64_t dnx_presenter_frame_count(void) { return presenter().frameCount(); }

}  // extern "C"
