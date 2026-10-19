#include "video_decoder.h"

#include <android/hardware_buffer.h>
#include <android/log.h>
#include <dlfcn.h>
#include <media/NdkImageReader.h>
#include <media/NdkMediaCodec.h>
#include <poll.h>
#include <pthread.h>
#include <sys/socket.h>
#include <sys/un.h>
#include <unistd.h>

#include <cerrno>
#include <chrono>
#include <condition_variable>
#include <cstring>
#include <deque>
#include <mutex>
#include <string>
#include <thread>
#include <unordered_map>

#include "video_protocol.h"

#define LOG_TAG "DroidNux.Video"
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

constexpr uint32_t kDrmFormatNv12 = 0x3231564e;  // 'NV12'
constexpr uint32_t kMaxSize = 8192;
constexpr int32_t kMinImages = 4;
constexpr int32_t kMaxImages = 48;
// Frames are sampled by the GPU in the container; the CPU maps each buffer once,
// to learn where its chroma plane is.
constexpr uint64_t kUsage = AHARDWAREBUFFER_USAGE_GPU_SAMPLED_IMAGE | AHARDWAREBUFFER_USAGE_CPU_READ_RARELY;
// Presentation timestamps are the decode sequence, 1 ms apart.
constexpr int64_t kPtsStep = 1000;
constexpr int32_t kColorFormatYuv420Flexible = 0x7f420888;  // MediaCodecInfo.CodecCapabilities
constexpr uint32_t kFormatNv12Venus = 0x7fa30c04;           // HAL_PIXEL_FORMAT_NV12_VENUS (Qualcomm)

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

bool recvAll(int fd, void* data, size_t size) {
    auto* p = static_cast<uint8_t*>(data);
    while (size > 0) {
        ssize_t n = recv(fd, p, size, MSG_WAITALL);
        if (n < 0 && errno == EINTR) continue;
        if (n <= 0) return false;
        p += n;
        size -= static_cast<size_t>(n);
    }
    return true;
}

bool skip(int fd, size_t size) {
    uint8_t scratch[4096];
    while (size > 0) {
        size_t n = size < sizeof(scratch) ? size : sizeof(scratch);
        if (!recvAll(fd, scratch, n)) return false;
        size -= n;
    }
    return true;
}

const char* mimeType(uint32_t codec) {
    switch (codec) {
        case DNX_VIDEO_VP9: return "video/x-vnd.on2.vp9";
        case DNX_VIDEO_H264: return "video/avc";
        default: return nullptr;
    }
}

// Where the planes of a decoder's NV12 buffer are, learned once per buffer.
struct Layout {
    AHardwareBuffer_Desc desc{};
    uint32_t offsets[2]{}, pitches[2]{};
    uint64_t size = 0;
    int fd = -1;  // the buffer's dma-buf, owned by the buffer
};

class Session {
public:
    explicit Session(int fd) : fd_(fd) {}

    // The connection's thread: serves its requests until it closes.
    void run() {
        pthread_setname_np(pthread_self(), "dnx-video");
        dnx_video_header header{};
        while (recvAll(fd_, &header, sizeof(header))) {
            bool ok = false;
            switch (header.type) {
                case DNX_VIDEO_OPEN: ok = open(header.size); break;
                case DNX_VIDEO_DECODE: ok = decode(header.size); break;
                case DNX_VIDEO_RELEASE: ok = release(header.size); break;
                default: LOGE("unknown request %u", header.type); break;
            }
            if (!ok) break;
        }
        close();
    }

private:
    struct Pending {
        int64_t pts;
        uint32_t tag;
    };

    bool open(uint32_t size) {
        dnx_video_open request{};
        if (codec_ != nullptr || size != sizeof(request) || !recvAll(fd_, &request, sizeof(request))) return false;
        dnx_video_opened reply{create(request)};
        std::lock_guard lock(mutex_);
        return send(DNX_VIDEO_OPENED, &reply, sizeof(reply), -1) && reply.status == 0;
    }

    int32_t create(const dnx_video_open& request) {
        if (request.magic != DNX_VIDEO_MAGIC || request.version != DNX_VIDEO_VERSION) return -EPROTO;
        const char* mime = mimeType(request.codec);
        if (mime == nullptr) return -ENOTSUP;
        if (request.width == 0 || request.height == 0 || request.width > kMaxSize || request.height > kMaxSize) {
            return -EINVAL;
        }
        if (getNativeHandle() == nullptr) return -ENOSYS;

        auto images = static_cast<int32_t>(request.buffers) + 2;  // and one being handed over
        images = images < kMinImages ? kMinImages : images > kMaxImages ? kMaxImages : images;
        if (AImageReader_newWithUsage(static_cast<int32_t>(request.width), static_cast<int32_t>(request.height),
                                      AIMAGE_FORMAT_YUV_420_888, kUsage, images, &reader_) != AMEDIA_OK) {
            LOGE("cannot create an image reader for %ux%u", request.width, request.height);
            return -ENOMEM;
        }
        AImageReader_ImageListener listener{this, onImage};
        AImageReader_setImageListener(reader_, &listener);
        ANativeWindow* window = nullptr;
        AImageReader_getWindow(reader_, &window);  // owned by the reader

        codec_ = createDecoder(mime);
        if (codec_ == nullptr) {
            LOGE("no decoder for %s", mime);
            return -ENOTSUP;
        }
        AMediaFormat* format = AMediaFormat_new();
        AMediaFormat_setString(format, AMEDIAFORMAT_KEY_MIME, mime);
        AMediaFormat_setInt32(format, AMEDIAFORMAT_KEY_WIDTH, static_cast<int32_t>(request.width));
        AMediaFormat_setInt32(format, AMEDIAFORMAT_KEY_HEIGHT, static_cast<int32_t>(request.height));
        // Out as soon as decoded: the client waits for each frame it submits.
        AMediaFormat_setInt32(format, "low-latency", 1);
        // Every frame reaches the client: by default a frame the image reader
        // has not taken yet is replaced by the next one.
        AMediaFormat_setInt32(format, "allow-frame-drop", 0);
        AMediaFormat_setInt32(format, AMEDIAFORMAT_KEY_PRIORITY, 0);  // real time
        // Flexible YUV rather than the decoder's own choice for a surface
        // (Qualcomm's: compressed UBWC, which only its own blocks read).
        AMediaFormat_setInt32(format, AMEDIAFORMAT_KEY_COLOR_FORMAT, kColorFormatYuv420Flexible);
        renderer_ = std::thread([this] { render(); });
        AMediaCodecOnAsyncNotifyCallback callbacks{onInput, onOutput, onFormat, onError};
        media_status_t status = AMediaCodec_setAsyncNotifyCallback(codec_, callbacks, this);
        if (status == AMEDIA_OK) status = AMediaCodec_configure(codec_, format, window, nullptr, 0);
        if (status == AMEDIA_OK) status = AMediaCodec_start(codec_);
        AMediaFormat_delete(format);
        char* name = nullptr;
        if (AMediaCodec_getName(codec_, &name) == AMEDIA_OK) {
            LOGI("%s %ux%u, %d buffers: %s", name, request.width, request.height, images,
                 status == AMEDIA_OK ? "started" : "failed");
            AMediaCodec_releaseName(codec_, name);
        }
        return status == AMEDIA_OK ? 0 : -EIO;
    }

    // The default decoder for the type, or its low-latency variant: devices
    // name those "<decoder>.low_latency" (Qualcomm), and only they honor the
    // low-latency key, without which a decoder holds frames back until it
    // gets more input.
    static AMediaCodec* createDecoder(const char* mime) {
        AMediaCodec* codec = AMediaCodec_createDecoderByType(mime);
        char* name = nullptr;
        if (codec == nullptr || AMediaCodec_getName(codec, &name) != AMEDIA_OK) return codec;
        std::string lowLatency = std::string(name) + ".low_latency";
        AMediaCodec_releaseName(codec, name);
        AMediaCodec* variant = AMediaCodec_createCodecByName(lowLatency.c_str());
        if (variant == nullptr) return codec;
        AMediaCodec_delete(codec);
        return variant;
    }

    bool decode(uint32_t size) {
        dnx_video_decode request{};
        if (codec_ == nullptr || size < sizeof(request) || size - sizeof(request) > DNX_VIDEO_MAX_FRAME ||
            !recvAll(fd_, &request, sizeof(request))) {
            return false;
        }
        size_t bytes = size - sizeof(request);
        bool output = (request.flags & DNX_VIDEO_NO_OUTPUT) == 0;

        int32_t index = -1;
        int64_t pts = 0;
        {
            std::unique_lock lock(mutex_);
            // Inputs free up as the codec decodes; a client that went away
            // in the meantime must not leave the codec allocated.
            while (inputs_.empty() && !failed_) {
                if (inputReady_.wait_for(lock, std::chrono::milliseconds(200)) == std::cv_status::timeout &&
                    hungUp()) {
                    return false;
                }
            }
            if (!failed_) {
                index = inputs_.front();
                inputs_.pop_front();
                pts = nextPts_ += kPtsStep;
            }
        }
        size_t capacity = 0;
        uint8_t* buffer = index >= 0 ? AMediaCodec_getInputBuffer(codec_, static_cast<size_t>(index), &capacity) : nullptr;
        if (buffer == nullptr || bytes > capacity) {
            if (index >= 0) {
                LOGE("frame of %zu bytes, input buffer of %zu", bytes, capacity);
                AMediaCodec_queueInputBuffer(codec_, static_cast<size_t>(index), 0, 0, pts, 0);
            }
            if (!skip(fd_, bytes)) return false;
            std::lock_guard lock(mutex_);
            return !output || sendError(request.tag, failed_ ? -EIO : -E2BIG);
        }
        if (!recvAll(fd_, buffer, bytes)) return false;
        if (output) {
            std::lock_guard lock(mutex_);
            pending_.push_back({pts, request.tag});
        }
        if (AMediaCodec_queueInputBuffer(codec_, static_cast<size_t>(index), 0, bytes, pts, 0) != AMEDIA_OK) {
            std::lock_guard lock(mutex_);
            fail("queueInputBuffer");
        }
        return true;
    }

    bool hungUp() const {
        pollfd pfd{fd_, POLLRDHUP, 0};
        return poll(&pfd, 1, 0) > 0 && (pfd.revents & (POLLRDHUP | POLLHUP | POLLERR)) != 0;
    }

    bool release(uint32_t size) {
        dnx_video_release request{};
        if (size != sizeof(request) || !recvAll(fd_, &request, sizeof(request))) return false;
        {
            std::lock_guard lock(mutex_);
            auto it = images_.find(request.buffer);
            if (it == images_.end()) return true;  // released by a failure
            AImage_delete(it->second);
            images_.erase(it);
        }
        drain();  // a frame may be waiting for a free image
        return true;
    }

    void close() {
        if (renderer_.joinable()) {
            {
                std::lock_guard lock(mutex_);
                stopping_ = true;
                outputReady_.notify_one();
            }
            renderer_.join();
        }
        if (codec_ != nullptr) {
            AMediaCodec_stop(codec_);
            AMediaCodec_delete(codec_);
        }
        std::lock_guard readerLock(readerMutex_);
        std::lock_guard lock(mutex_);
        closed_ = true;
        for (auto& [id, image] : images_) AImage_delete(image);
        images_.clear();
        if (reader_ != nullptr) AImageReader_delete(reader_);
        reader_ = nullptr;
        ::close(fd_);
    }

    static void onInput(AMediaCodec*, void* self, int32_t index) {
        auto* session = static_cast<Session*>(self);
        std::lock_guard lock(session->mutex_);
        session->inputs_.push_back(index);
        session->inputReady_.notify_one();
    }

    static void onOutput(AMediaCodec*, void* self, int32_t index, AMediaCodecBufferInfo*) {
        // Callbacks run on the codec's own looper, which releasing a buffer
        // waits for: the renderer thread does it.
        auto* session = static_cast<Session*>(self);
        std::lock_guard lock(session->mutex_);
        session->outputs_.push_back(index);
        session->outputReady_.notify_one();
    }

    // Sends the codec's decoded buffers to the image reader, with their
    // inputs' timestamps.
    void render() {
        pthread_setname_np(pthread_self(), "dnx-video-out");
        for (;;) {
            int32_t index;
            {
                std::unique_lock lock(mutex_);
                outputReady_.wait(lock, [this] { return !outputs_.empty() || stopping_; });
                if (stopping_) return;
                index = outputs_.front();
                outputs_.pop_front();
            }
            AMediaCodec_releaseOutputBuffer(codec_, static_cast<size_t>(index), true);
        }
    }

    static void onFormat(AMediaCodec*, void*, AMediaFormat* format) {
        LOGI("output format %s", AMediaFormat_toString(format));
    }

    static void onError(AMediaCodec*, void* self, media_status_t error, int32_t, const char* detail) {
        auto* session = static_cast<Session*>(self);
        LOGE("codec error %d: %s", error, detail != nullptr ? detail : "");
        std::lock_guard lock(session->mutex_);
        session->fail("codec");
    }

    static void onImage(void* self, AImageReader*) {
        auto* session = static_cast<Session*>(self);
        session->drain();
    }

    // Hands every decoded image to the client, answering the decodes before it
    // that produced nothing (the codec dropped them).
    void drain() {
        std::lock_guard readerLock(readerMutex_);
        for (;;) {
            AImage* image = nullptr;
            // Waits for the image's fence, so the client gets finished frames.
            if (closed_ || AImageReader_acquireNextImage(reader_, &image) != AMEDIA_OK) return;  // none, or all held
            std::lock_guard lock(mutex_);
            int64_t timestamp = 0;
            AImage_getTimestamp(image, &timestamp);
            int64_t pts = timestamp / 1000;
            while (!pending_.empty() && pending_.front().pts < pts) {
                LOGW("no frame for decode %u (pts %lld, next frame %lld)", pending_.front().tag,
                     static_cast<long long>(pending_.front().pts), static_cast<long long>(pts));
                sendError(pending_.front().tag, -ENODATA);
                pending_.pop_front();
            }
            if (pending_.empty() || pending_.front().pts != pts) {
                LOGW("unexpected frame (pts %lld)", static_cast<long long>(pts));
                AImage_delete(image);
                continue;
            }
            uint32_t tag = pending_.front().tag;
            pending_.pop_front();
            sendImage(tag, image);
        }
    }

    void sendImage(uint32_t tag, AImage* image) {
        AHardwareBuffer* buffer = nullptr;
        AImageCropRect crop{};
        const Layout* layout = nullptr;
        if (AImage_getHardwareBuffer(image, &buffer) == AMEDIA_OK && AImage_getCropRect(image, &crop) == AMEDIA_OK) {
            layout = layoutOf(buffer);
        }
        if (layout == nullptr || crop.left != 0 || crop.top != 0) {
            AImage_delete(image);
            sendError(tag, -EINVAL);
            return;
        }
        dnx_video_frame frame{};
        frame.tag = tag;
        frame.buffer = nextBuffer_++;
        frame.width = static_cast<uint32_t>(crop.right);
        frame.height = static_cast<uint32_t>(crop.bottom);
        frame.fourcc = kDrmFormatNv12;
        std::memcpy(frame.offsets, layout->offsets, sizeof(frame.offsets));
        std::memcpy(frame.pitches, layout->pitches, sizeof(frame.pitches));
        frame.size = layout->size;
        images_[frame.buffer] = image;
        send(DNX_VIDEO_FRAME, &frame, sizeof(frame), layout->fd);
    }

    const Layout* layoutOf(AHardwareBuffer* buffer) {
        AHardwareBuffer_Desc desc{};
        AHardwareBuffer_describe(buffer, &desc);
        auto it = layouts_.find(buffer);
        if (it != layouts_.end() && std::memcmp(&it->second.desc, &desc, sizeof(desc)) == 0) return &it->second;

        Layout layout{desc};
        const NativeHandle* handle = getNativeHandle()(buffer);
        off_t size = handle != nullptr && handle->numFds > 0 ? lseek(handle->data[0], 0, SEEK_END) : -1;
        if (size <= 0) {
            LOGE("decoded frame without a dma-buf");
            return nullptr;
        }
        layout.size = static_cast<uint64_t>(size);
        layout.fd = handle->data[0];
        if (desc.format == kFormatNv12Venus) {
            // Qualcomm's video NV12 (msm_media_info.h): rows of desc.stride
            // bytes, luma rows aligned to 32, chroma rows to 16. CPU mappings
            // of it see a single plane.
            uint32_t rows = (desc.height + 31) & ~31u;
            uint32_t chromaRows = ((desc.height + 1) / 2 + 15) & ~15u;
            layout.pitches[0] = layout.pitches[1] = desc.stride;
            layout.offsets[1] = desc.stride * rows;
            if (layout.offsets[1] + static_cast<uint64_t>(desc.stride) * chromaRows > layout.size) {
                LOGE("NV12 frame %ux%u (stride %u) larger than its %llu bytes", desc.width, desc.height, desc.stride,
                     static_cast<unsigned long long>(layout.size));
                return nullptr;
            }
            return remember(buffer, layout);
        }
        AHardwareBuffer_Planes planes{};
        if (AHardwareBuffer_lockPlanes(buffer, AHARDWAREBUFFER_USAGE_CPU_READ_RARELY, -1, nullptr, &planes) != 0) {
            LOGE("cannot map a decoded frame");
            return nullptr;
        }
        auto* y = static_cast<uint8_t*>(planes.planes[0].data);
        auto* u = static_cast<uint8_t*>(planes.planes[1].data);
        auto* v = static_cast<uint8_t*>(planes.planes[2].data);
        // NV12: Y, then one plane of interleaved U and V. The mapping starts at
        // the buffer's first byte, as the Y plane does on these devices.
        bool nv12 = planes.planeCount == 3 && planes.planes[0].pixelStride == 1 && planes.planes[1].pixelStride == 2 &&
                    v == u + 1 && planes.planes[1].rowStride == planes.planes[2].rowStride && u > y;
        layout.offsets[1] = static_cast<uint32_t>(u - y);
        layout.pitches[0] = planes.planes[0].rowStride;
        layout.pitches[1] = planes.planes[1].rowStride;
        AHardwareBuffer_unlock(buffer, nullptr);
        if (!nv12) {
            LOGE("decoded frames are not NV12 (format %#x: %u planes, pixel strides %u %u %u, row strides %u %u %u, "
                 "offsets %td %td)",
                 desc.format, planes.planeCount, planes.planes[0].pixelStride, planes.planes[1].pixelStride,
                 planes.planes[2].pixelStride, planes.planes[0].rowStride, planes.planes[1].rowStride,
                 planes.planes[2].rowStride, u - y, v - y);
            return nullptr;
        }
        return remember(buffer, layout);
    }

    const Layout* remember(AHardwareBuffer* buffer, const Layout& layout) {
        LOGI("frame buffer %ux%u (format %#x): pitch %u, chroma at %u, %llu bytes", layout.desc.width,
             layout.desc.height, layout.desc.format, layout.pitches[0], layout.offsets[1],
             static_cast<unsigned long long>(layout.size));
        return &(layouts_[buffer] = layout);
    }

    void fail(const char* what) {
        if (!failed_) LOGE("decoding stopped: %s", what);
        failed_ = true;
        for (const Pending& p : pending_) sendError(p.tag, -EIO);
        pending_.clear();
        inputReady_.notify_all();
    }

    bool sendError(uint32_t tag, int32_t status) {
        dnx_video_frame frame{};
        frame.tag = tag;
        frame.status = status;
        return send(DNX_VIDEO_FRAME, &frame, sizeof(frame), -1);
    }

    bool send(uint32_t type, const void* body, uint32_t size, int fd) {
        dnx_video_header header{type, size};
        iovec iov[2] = {{&header, sizeof(header)}, {const_cast<void*>(body), size}};
        char control[CMSG_SPACE(sizeof(int))] = {};
        msghdr message{};
        message.msg_iov = iov;
        message.msg_iovlen = 2;
        if (fd >= 0) {
            message.msg_control = control;
            message.msg_controllen = sizeof(control);
            cmsghdr* cmsg = CMSG_FIRSTHDR(&message);
            cmsg->cmsg_level = SOL_SOCKET;
            cmsg->cmsg_type = SCM_RIGHTS;
            cmsg->cmsg_len = CMSG_LEN(sizeof(int));
            std::memcpy(CMSG_DATA(cmsg), &fd, sizeof(int));
        }
        ssize_t n;
        do {
            n = sendmsg(fd_, &message, MSG_NOSIGNAL);
        } while (n < 0 && errno == EINTR);
        return n == static_cast<ssize_t>(sizeof(header) + size);
    }

    const int fd_;
    AMediaCodec* codec_ = nullptr;
    AImageReader* reader_ = nullptr;

    std::thread renderer_;
    std::mutex readerMutex_;  // acquiring images, and deleting the reader (before mutex_)
    std::mutex mutex_;        // everything below, and writes to the socket
    std::condition_variable inputReady_;
    std::condition_variable outputReady_;
    std::deque<int32_t> inputs_;
    std::deque<int32_t> outputs_;  // decoded, for the renderer
    std::deque<Pending> pending_;
    std::unordered_map<uint32_t, AImage*> images_;  // held by the client
    std::unordered_map<AHardwareBuffer*, Layout> layouts_;
    int64_t nextPts_ = 0;
    uint32_t nextBuffer_ = 1;
    bool failed_ = false;
    bool stopping_ = false;  // the renderer
    bool closed_ = false;
};

class Server {
public:
    bool start(const char* path) {
        std::lock_guard lock(mutex_);
        if (listener_ >= 0) return true;
        sockaddr_un address{};
        address.sun_family = AF_UNIX;
        if (std::strlen(path) >= sizeof(address.sun_path)) {
            LOGE("socket path too long: %s", path);
            return false;
        }
        std::strcpy(address.sun_path, path);
        int fd = socket(AF_UNIX, SOCK_STREAM | SOCK_CLOEXEC, 0);
        unlink(path);
        if (fd < 0 || bind(fd, reinterpret_cast<sockaddr*>(&address), sizeof(address)) != 0 || listen(fd, 16) != 0) {
            LOGE("cannot listen on %s: %s", path, std::strerror(errno));
            if (fd >= 0) ::close(fd);
            return false;
        }
        listener_ = fd;
        std::thread([fd] {
            pthread_setname_np(pthread_self(), "dnx-video-srv");
            for (;;) {
                int connection = accept4(fd, nullptr, nullptr, SOCK_CLOEXEC);
                if (connection < 0) {
                    if (errno == EINTR || errno == ECONNABORTED) continue;
                    LOGE("accept: %s", std::strerror(errno));
                    return;
                }
                std::thread([connection] {
                    Session session(connection);
                    session.run();
                }).detach();
            }
        }).detach();
        LOGI("listening on %s", path);
        return true;
    }

private:
    std::mutex mutex_;
    int listener_ = -1;
};

}  // namespace

extern "C" bool dnx_video_decoder_start(const char* socket_path) {
    static Server* server = new Server();  // lives as long as the process
    return server->start(socket_path);
}
