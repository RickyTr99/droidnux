#include "allocator.h"

#include <android/log.h>
#include <dlfcn.h>
#include <poll.h>
#include <pthread.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <sys/un.h>
#include <unistd.h>

#include <cerrno>
#include <cstring>
#include <mutex>
#include <string>
#include <thread>
#include <vector>

#define LOG_TAG "DroidNux.Allocator"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

constexpr uint32_t kDrmFormatArgb8888 = 0x34325241;  // 'AR24'
constexpr uint32_t kFormatBgra8888 = 5;              // AHARDWAREBUFFER_FORMAT_B8G8R8A8_UNORM, not in the NDK headers
constexpr uint32_t kMaxSize = 8192;
constexpr size_t kMaxBuffers = 1024;  // every GL or Vulkan window has a swapchain of a few images

// The display composes these buffers directly (overlay or GPU), the client
// renders into them, and the X server reads them with the CPU when a window
// is not flipped.
constexpr uint64_t kUsage = AHARDWAREBUFFER_USAGE_GPU_COLOR_OUTPUT | AHARDWAREBUFFER_USAGE_GPU_SAMPLED_IMAGE |
                            AHARDWAREBUFFER_USAGE_COMPOSER_OVERLAY | AHARDWAREBUFFER_USAGE_CPU_READ_RARELY;

// Layout of native_handle_t (system/core/libcutils), stable since Android 1.
struct NativeHandle {
    int version;
    int numFds;
    int numInts;
    int data[0];
};

using GetNativeHandleFn = const NativeHandle* (*)(const AHardwareBuffer*);

GetNativeHandleFn getNativeHandle() {
    // Not in the public NDK headers, but exported by libnativewindow.
    static auto fn = reinterpret_cast<GetNativeHandleFn>(
        dlsym(dlopen("libnativewindow.so", RTLD_NOW), "AHardwareBuffer_getNativeHandle"));
    return fn;
}

struct Buffer {
    int connection = -1;
    AHardwareBuffer* buffer = nullptr;  // null until the request is answered
    dev_t device = 0;
    ino_t inode = 0;
};

class Allocator {
public:
    bool start(const char* path) {
        std::lock_guard lock(mutex_);
        if (listener_ >= 0) return true;
        if (getNativeHandle() == nullptr) {
            LOGE("AHardwareBuffer_getNativeHandle not available");
            return false;
        }
        sockaddr_un address{};
        address.sun_family = AF_UNIX;
        if (std::strlen(path) >= sizeof(address.sun_path)) {
            LOGE("socket path too long: %s", path);
            return false;
        }
        std::strcpy(address.sun_path, path);
        int fd = socket(AF_UNIX, SOCK_STREAM | SOCK_CLOEXEC | SOCK_NONBLOCK, 0);
        unlink(path);
        if (fd < 0 || bind(fd, reinterpret_cast<sockaddr*>(&address), sizeof(address)) != 0 || listen(fd, 16) != 0) {
            LOGE("cannot listen on %s: %s", path, std::strerror(errno));
            if (fd >= 0) close(fd);
            return false;
        }
        listener_ = fd;
        std::thread([this] { run(); }).detach();
        LOGI("listening on %s", path);
        return true;
    }

    AHardwareBuffer* find(int fd) {
        struct stat st {};
        if (fstat(fd, &st) != 0) return nullptr;
        std::lock_guard lock(mutex_);
        for (const Buffer& b : buffers_) {
            if (b.buffer != nullptr && b.device == st.st_dev && b.inode == st.st_ino) {
                AHardwareBuffer_acquire(b.buffer);
                return b.buffer;
            }
        }
        return nullptr;
    }

private:
    void run() {
        pthread_setname_np(pthread_self(), "dnx-allocator");
        std::vector<pollfd> fds;
        for (;;) {
            fds.clear();
            fds.push_back({listener_, POLLIN, 0});
            {
                std::lock_guard lock(mutex_);
                for (const Buffer& b : buffers_) fds.push_back({b.connection, POLLIN, 0});
            }
            if (poll(fds.data(), fds.size(), -1) < 0) {
                if (errno == EINTR) continue;
                LOGE("poll: %s", std::strerror(errno));
                return;
            }
            for (size_t i = 1; i < fds.size(); i++) {
                if (fds[i].revents != 0) serve(fds[i].fd);
            }
            if (fds[0].revents & POLLIN) accept();
        }
    }

    void accept() {
        int fd = accept4(listener_, nullptr, nullptr, SOCK_CLOEXEC);
        if (fd < 0) return;
        std::lock_guard lock(mutex_);
        if (buffers_.size() >= kMaxBuffers) {
            LOGE("too many buffers");
            close(fd);
            return;
        }
        buffers_.push_back(Buffer{fd});
    }

    // A request on a new connection, or the end of an old one.
    void serve(int fd) {
        Buffer* entry = nullptr;
        {
            std::lock_guard lock(mutex_);
            for (Buffer& b : buffers_) {
                if (b.connection == fd) entry = &b;
            }
        }
        if (entry == nullptr) return;

        if (entry->buffer != nullptr) {
            // The client sends nothing after its request: readable means closed.
            remove(fd);
            return;
        }
        dnx_allocator_request request{};
        if (recv(fd, &request, sizeof(request), MSG_DONTWAIT | MSG_WAITALL) != sizeof(request)) {
            remove(fd);
            return;
        }
        dnx_allocator_reply reply{};
        AHardwareBuffer* buffer = nullptr;
        int dmabuf = -1;
        reply.status = allocate(request, &buffer, &dmabuf, &reply);

        char control[CMSG_SPACE(sizeof(int))] = {};
        iovec iov{&reply, sizeof(reply)};
        msghdr message{};
        message.msg_iov = &iov;
        message.msg_iovlen = 1;
        if (reply.status == 0) {
            message.msg_control = control;
            message.msg_controllen = sizeof(control);
            cmsghdr* cmsg = CMSG_FIRSTHDR(&message);
            cmsg->cmsg_level = SOL_SOCKET;
            cmsg->cmsg_type = SCM_RIGHTS;
            cmsg->cmsg_len = CMSG_LEN(sizeof(int));
            std::memcpy(CMSG_DATA(cmsg), &dmabuf, sizeof(int));
        }
        if (sendmsg(fd, &message, MSG_NOSIGNAL) != sizeof(reply) || reply.status != 0) {
            if (buffer != nullptr) AHardwareBuffer_release(buffer);
            remove(fd);
            return;
        }
        struct stat st {};
        fstat(dmabuf, &st);
        std::lock_guard lock(mutex_);
        for (Buffer& b : buffers_) {
            if (b.connection == fd) {
                b.buffer = buffer;
                b.device = st.st_dev;
                b.inode = st.st_ino;
            }
        }
    }

    static int allocate(const dnx_allocator_request& request, AHardwareBuffer** out, int* dmabuf,
                        dnx_allocator_reply* reply) {
        if (request.magic != DNX_ALLOCATOR_MAGIC || request.version != DNX_ALLOCATOR_VERSION) return -EPROTO;
        if (request.drm_format != kDrmFormatArgb8888) return -EINVAL;
        if (request.width == 0 || request.height == 0 || request.width > kMaxSize || request.height > kMaxSize) {
            return -EINVAL;
        }
        AHardwareBuffer_Desc desc{};
        desc.width = request.width;
        desc.height = request.height;
        desc.layers = 1;
        desc.format = kFormatBgra8888;
        desc.usage = kUsage;
        AHardwareBuffer* buffer = nullptr;
        if (AHardwareBuffer_allocate(&desc, &buffer) != 0) {
            LOGE("cannot allocate %ux%u", request.width, request.height);
            return -ENOMEM;
        }
        const NativeHandle* handle = getNativeHandle()(buffer);
        off_t size = handle != nullptr && handle->numFds > 0 ? lseek(handle->data[0], 0, SEEK_END) : -1;
        if (size <= 0) {
            AHardwareBuffer_release(buffer);
            return -EIO;
        }
        AHardwareBuffer_Desc actual{};
        AHardwareBuffer_describe(buffer, &actual);
        *out = buffer;
        *dmabuf = handle->data[0];  // sendmsg duplicates it into the client
        reply->stride = actual.stride * 4;
        reply->size = static_cast<uint64_t>(size);
        return 0;
    }

    void remove(int fd) {
        std::lock_guard lock(mutex_);
        for (size_t i = 0; i < buffers_.size(); i++) {
            if (buffers_[i].connection != fd) continue;
            if (buffers_[i].buffer != nullptr) AHardwareBuffer_release(buffers_[i].buffer);
            close(fd);
            buffers_.erase(buffers_.begin() + static_cast<long>(i));
            return;
        }
    }

    std::mutex mutex_;
    int listener_ = -1;
    std::vector<Buffer> buffers_;
};

Allocator& allocator() {
    static Allocator* instance = new Allocator();  // lives as long as the process
    return *instance;
}

}  // namespace

extern "C" {

bool dnx_allocator_start(const char* socket_path) { return allocator().start(socket_path); }
AHardwareBuffer* dnx_allocator_find(int dmabuf_fd) { return allocator().find(dmabuf_fd); }

}  // extern "C"
