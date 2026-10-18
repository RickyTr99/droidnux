#include "buffer_spike.h"

#include <android/hardware_buffer.h>
#include <android/log.h>
#include <dlfcn.h>
#include <poll.h>
#include <sys/socket.h>
#include <sys/un.h>
#include <time.h>
#include <unistd.h>

#include <cstddef>
#include <cstdio>
#include <cstring>

#include "protocol.h"

#define LOG_TAG "DroidNux.Spike"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace droidnux::display {

namespace {

constexpr uint32_t kDrmFormatAbgr8888 = 0x34324241;  // 'AB24': RGBA8 bytes in memory

struct NativeHandle {
    int version;
    int numFds;
    int numInts;
    int data[0];
};

const NativeHandle* nativeHandle(const AHardwareBuffer* buffer) {
    using Fn = const NativeHandle* (*)(const AHardwareBuffer*);
    static auto fn = reinterpret_cast<Fn>(
        dlsym(dlopen("libnativewindow.so", RTLD_NOW), "AHardwareBuffer_getNativeHandle"));
    return fn ? fn(buffer) : nullptr;
}

int64_t nowNs() {
    timespec ts{};
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return ts.tv_sec * 1000000000LL + ts.tv_nsec;
}

bool sendWithFd(int socket, const void* data, size_t size, int fd) {
    iovec iov{const_cast<void*>(data), size};
    msghdr msg{};
    msg.msg_iov = &iov;
    msg.msg_iovlen = 1;
    char control[CMSG_SPACE(sizeof(int))] = {};
    if (fd >= 0) {
        msg.msg_control = control;
        msg.msg_controllen = sizeof(control);
        cmsghdr* c = CMSG_FIRSTHDR(&msg);
        c->cmsg_level = SOL_SOCKET;
        c->cmsg_type = SCM_RIGHTS;
        c->cmsg_len = CMSG_LEN(sizeof(int));
        std::memcpy(CMSG_DATA(c), &fd, sizeof(int));
    }
    return sendmsg(socket, &msg, MSG_NOSIGNAL) == static_cast<ssize_t>(size);
}

}  // namespace

BufferSpike::BufferSpike(ANativeWindow* window, uint32_t width, uint32_t height, uint32_t bufferCount)
    : window_(window), width_(width), height_(height) {
    ANativeWindow_acquire(window_);
    surface_ = ASurfaceControl_createFromWindow(window_, "droidnux-buffer-spike");
    if (surface_ == nullptr) {
        error_ = "ASurfaceControl_createFromWindow failed";
        return;
    }
    if (!allocate(width, height, bufferCount)) return;

    listenFd_ = socket(AF_UNIX, SOCK_SEQPACKET | SOCK_CLOEXEC, 0);
    sockaddr_un addr{};
    addr.sun_family = AF_UNIX;
    std::memcpy(addr.sun_path + 1, DN_SOCKET_NAME, std::strlen(DN_SOCKET_NAME));
    auto length = static_cast<socklen_t>(offsetof(sockaddr_un, sun_path) + 1 + std::strlen(DN_SOCKET_NAME));
    if (listenFd_ < 0 || bind(listenFd_, reinterpret_cast<sockaddr*>(&addr), length) != 0 ||
        listen(listenFd_, 1) != 0) {
        error_ = std::string("socket: ") + std::strerror(errno);
        return;
    }
    lastStatsNs_ = nowNs();
    thread_ = std::thread([this] { serve(); });
    LOGI("listening, %ux%u, %u buffers", width, height, bufferCount);
}

BufferSpike::~BufferSpike() {
    stop_ = true;
    if (int client = clientFd_.exchange(-1); client >= 0) {
        shutdown(client, SHUT_RDWR);
        close(client);
    }
    if (listenFd_ >= 0) shutdown(listenFd_, SHUT_RDWR);
    if (thread_.joinable()) thread_.join();
    if (listenFd_ >= 0) close(listenFd_);

    if (surface_ != nullptr) {
        // Detach the buffer from the screen before freeing it.
        ASurfaceTransaction* t = ASurfaceTransaction_create();
        ASurfaceTransaction_reparent(t, surface_, nullptr);
        ASurfaceTransaction_apply(t);
        ASurfaceTransaction_delete(t);
        ASurfaceControl_release(surface_);
    }
    for (Slot& slot : slots_) {
        if (slot.buffer != nullptr) AHardwareBuffer_release(slot.buffer);
    }
    ANativeWindow_release(window_);
}

bool BufferSpike::allocate(uint32_t width, uint32_t height, uint32_t count) {
    AHardwareBuffer_Desc desc{};
    desc.width = width;
    desc.height = height;
    desc.layers = 1;
    desc.format = AHARDWAREBUFFER_FORMAT_R8G8B8A8_UNORM;
    desc.usage = AHARDWAREBUFFER_USAGE_GPU_SAMPLED_IMAGE | AHARDWAREBUFFER_USAGE_GPU_COLOR_OUTPUT |
                 AHARDWAREBUFFER_USAGE_COMPOSER_OVERLAY;
    for (uint32_t i = 0; i < count; i++) {
        Slot slot;
        if (AHardwareBuffer_allocate(&desc, &slot.buffer) != 0) {
            error_ = "AHardwareBuffer_allocate failed";
            return false;
        }
        AHardwareBuffer_Desc actual{};
        AHardwareBuffer_describe(slot.buffer, &actual);
        const NativeHandle* handle = nativeHandle(slot.buffer);
        if (handle == nullptr || handle->numFds < 1) {
            error_ = "no dma-buf in the AHardwareBuffer native handle";
            AHardwareBuffer_release(slot.buffer);
            return false;
        }
        slot.fd = handle->data[0];
        slot.stride = actual.stride * 4;
        off_t size = lseek(slot.fd, 0, SEEK_END);
        lseek(slot.fd, 0, SEEK_SET);
        slot.size = static_cast<uint64_t>(size);
        slots_.push_back(slot);
        LOGI("buffer %u: stride %u bytes, %llu bytes", i, slot.stride, static_cast<unsigned long long>(slot.size));
    }
    return true;
}

void BufferSpike::serve() {
    while (!stop_) {
        int client = accept4(listenFd_, nullptr, nullptr, SOCK_CLOEXEC);
        if (client < 0) {
            if (!stop_) LOGE("accept: %s", std::strerror(errno));
            return;
        }
        LOGI("guest connected");
        clientFd_ = client;
        connected_ = true;
        handleClient(client);
        connected_ = false;
        if (int fd = clientFd_.exchange(-1); fd >= 0) close(fd);
        LOGI("guest disconnected");
    }
}

void BufferSpike::handleClient(int client) {
    dn_hello hello{DN_MAGIC, DN_VERSION, static_cast<uint32_t>(slots_.size()), 0};
    if (!sendWithFd(client, &hello, sizeof(hello), -1)) return;
    for (size_t i = 0; i < slots_.size(); i++) {
        dn_buffer info{};
        info.index = static_cast<uint32_t>(i);
        info.width = width_;
        info.height = height_;
        info.stride = slots_[i].stride;
        info.drm_format = kDrmFormatAbgr8888;
        info.offset = 0;
        info.size = slots_[i].size;
        if (!sendWithFd(client, &info, sizeof(info), slots_[i].fd)) return;
    }

    dn_message message{};
    while (!stop_) {
        ssize_t n = recv(client, &message, sizeof(message), 0);
        if (n != sizeof(message)) return;
        if (message.type == DN_MSG_PRESENT && message.index < slots_.size()) {
            present(static_cast<int>(message.index));
        }
    }
}

void BufferSpike::present(int index) {
    ASurfaceTransaction* t = ASurfaceTransaction_create();
    ASurfaceTransaction_setBuffer(t, surface_, slots_[index].buffer, -1);
    if (!shown_) {
        ARect source{0, 0, static_cast<int32_t>(width_), static_cast<int32_t>(height_)};
        ARect destination{0, 0, ANativeWindow_getWidth(window_), ANativeWindow_getHeight(window_)};
        ASurfaceTransaction_setGeometry(t, surface_, source, destination, ANATIVEWINDOW_TRANSFORM_IDENTITY);
        ASurfaceTransaction_setBufferTransparency(t, surface_, ASURFACE_TRANSACTION_TRANSPARENCY_OPAQUE);
        ASurfaceTransaction_setVisibility(t, surface_, ASURFACE_TRANSACTION_VISIBILITY_SHOW);
        shown_ = true;
    }
    auto* completion = new Completion{this, index, lastPresented_};
    ASurfaceTransaction_setOnComplete(t, completion, onComplete);
    ASurfaceTransaction_apply(t);
    ASurfaceTransaction_delete(t);
    lastPresented_ = index;
    presented_++;
}

void BufferSpike::onComplete(void* context, ASurfaceTransactionStats* stats) {
    auto* completion = static_cast<Completion*>(context);
    BufferSpike* spike = completion->spike;
    spike->completed_++;
    // The buffer this transaction replaced is free once its release fence
    // signals.
    if (completion->previous >= 0) {
        int fence = ASurfaceTransactionStats_getPreviousReleaseFenceFd(stats, spike->surface_);
        if (fence >= 0) {
            pollfd pfd{fence, POLLIN, 0};
            poll(&pfd, 1, 1000);
            close(fence);
        }
        spike->sendRelease(completion->previous);
    }
    delete completion;
}

void BufferSpike::sendRelease(int index) {
    std::lock_guard lock(sendMutex_);
    int client = clientFd_.load();
    if (client < 0) return;
    dn_message message{DN_MSG_RELEASE, static_cast<uint32_t>(index), 0};
    send(client, &message, sizeof(message), MSG_NOSIGNAL);
}

std::string BufferSpike::stats() {
    int64_t now = nowNs();
    double seconds = (now - lastStatsNs_) / 1e9;
    if (seconds >= 1.0 || lastStats_.empty()) {
        uint64_t presented = presented_.load();
        uint64_t completed = completed_.load();
        char line[160];
        std::snprintf(line, sizeof(line), "%s · %ux%u · presented %.1f fps · completed %.1f fps",
                      connected_ ? "guest connected" : "waiting for guest", width_, height_,
                      (presented - lastPresented_count_) / seconds, (completed - lastCompleted_count_) / seconds);
        lastStats_ = line;
        lastPresented_count_ = presented;
        lastCompleted_count_ = completed;
        lastStatsNs_ = now;
    }
    return lastStats_;
}

}  // namespace droidnux::display
