#include "audio_output.h"

#include <aaudio/AAudio.h>
#include <android/log.h>
#include <poll.h>
#include <pthread.h>
#include <sys/mman.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <sys/un.h>
#include <time.h>
#include <unistd.h>

#include <algorithm>
#include <atomic>
#include <cerrno>
#include <cstring>
#include <mutex>
#include <thread>

#include "audio_protocol.h"

#define LOG_TAG "DroidNux.Audio"
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

constexpr uint32_t kMaxFrames = 1u << 16;
constexpr uint32_t kMaxChannels = 8;
// While playing, how often the session checks for a lost device and updates
// the latency estimate.
constexpr int kPollMs = 200;
constexpr int64_t kStopTimeoutNs = 200'000'000;

std::atomic<int> gPlaying{0};
std::atomic<dnx_audio_playing_callback> gPlayingCallback{nullptr};
std::atomic<bool> gMuted{false};

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

int64_t nowNs() {
    timespec ts{};
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return ts.tv_sec * 1'000'000'000LL + ts.tv_nsec;
}

class Session {
public:
    explicit Session(int fd) : fd_(fd) {}

    ~Session() {
        setPlaying(false);
        closeStream();
        if (ring_ != nullptr) munmap(ring_, size_);
        ::close(fd_);
    }

    // The connection's thread: plays the ring until the sink goes away.
    void run() {
        pthread_setname_np(pthread_self(), "dnx-audio");
        if (!hello()) return;
        LOGI("sink connected: %u Hz, %u channels, ring of %u frames", rate_, channels_, frames_);
        for (;;) {
            pollfd p{fd_, POLLIN, 0};
            int r = poll(&p, 1, playing_ ? kPollMs : -1);
            if (r < 0 && errno != EINTR) break;
            if (r > 0) {
                dnx_audio_header header{};
                if (!recvAll(fd_, &header, sizeof(header)) || header.size != 0) break;
                if (header.type == DNX_AUDIO_START) {
                    start();
                } else if (header.type == DNX_AUDIO_STOP) {
                    stop();
                } else {
                    LOGE("unknown message %u", header.type);
                    break;
                }
            }
            if (!playing_) continue;
            if (lost_.exchange(false)) {
                // Headphones, Bluetooth...: the stream is dead, open one on the new device.
                LOGI("output device changed");
                closeStream();
                start();
            } else {
                updateLatency();
            }
        }
        LOGI("sink disconnected");
    }

private:
    bool hello() {
        struct {
            dnx_audio_header header;
            dnx_audio_hello hello;
        } message{};
        union {
            cmsghdr align;
            char buf[CMSG_SPACE(sizeof(int))];
        } control{};
        iovec iov{&message, sizeof(message)};
        msghdr msg{};
        msg.msg_iov = &iov;
        msg.msg_iovlen = 1;
        msg.msg_control = control.buf;
        msg.msg_controllen = sizeof(control.buf);
        ssize_t n;
        do {
            n = recvmsg(fd_, &msg, MSG_CMSG_CLOEXEC | MSG_WAITALL);
        } while (n < 0 && errno == EINTR);
        int memfd = -1;
        for (cmsghdr* c = CMSG_FIRSTHDR(&msg); c != nullptr; c = CMSG_NXTHDR(&msg, c)) {
            if (c->cmsg_level == SOL_SOCKET && c->cmsg_type == SCM_RIGHTS && c->cmsg_len >= CMSG_LEN(sizeof(int))) {
                std::memcpy(&memfd, CMSG_DATA(c), sizeof(int));
            }
        }
        bool ok = n == static_cast<ssize_t>(sizeof(message)) && memfd >= 0 &&
                  message.header.type == DNX_AUDIO_HELLO && message.header.size == sizeof(dnx_audio_hello) &&
                  message.hello.magic == DNX_AUDIO_MAGIC && message.hello.version == DNX_AUDIO_VERSION;
        struct stat st{};
        if (ok && (fstat(memfd, &st) != 0 || st.st_size < static_cast<off_t>(sizeof(dnx_audio_ring)) ||
                   st.st_size > static_cast<off_t>(DNX_AUDIO_RING_SIZE(kMaxFrames, kMaxChannels)))) {
            ok = false;
        }
        void* map = ok ? mmap(nullptr, st.st_size, PROT_READ | PROT_WRITE, MAP_SHARED, memfd, 0) : MAP_FAILED;
        if (memfd >= 0) ::close(memfd);
        if (map == MAP_FAILED) {
            LOGE("bad HELLO from the sink");
            return false;
        }
        ring_ = static_cast<dnx_audio_ring*>(map);
        size_ = static_cast<size_t>(st.st_size);
        // The client can rewrite the header at any time: read its constants once.
        rate_ = ring_->rate;
        channels_ = ring_->channels;
        frames_ = ring_->frames;
        if (ring_->magic != DNX_AUDIO_MAGIC || ring_->version != DNX_AUDIO_VERSION || rate_ < 8000 ||
            rate_ > 192000 || channels_ == 0 || channels_ > kMaxChannels || frames_ == 0 || frames_ > kMaxFrames ||
            (frames_ & (frames_ - 1)) != 0 || DNX_AUDIO_RING_SIZE(frames_, channels_) > size_) {
            LOGE("bad ring from the sink");
            return false;
        }
        samples_ = reinterpret_cast<float*>(ring_ + 1);
        return true;
    }

    bool openStream() {
        AAudioStreamBuilder* builder = nullptr;
        if (AAudio_createStreamBuilder(&builder) != AAUDIO_OK) return false;
        AAudioStreamBuilder_setDirection(builder, AAUDIO_DIRECTION_OUTPUT);
        AAudioStreamBuilder_setSharingMode(builder, AAUDIO_SHARING_MODE_SHARED);
        AAudioStreamBuilder_setFormat(builder, AAUDIO_FORMAT_PCM_FLOAT);
        AAudioStreamBuilder_setChannelCount(builder, static_cast<int32_t>(channels_));
        AAudioStreamBuilder_setSampleRate(builder, static_cast<int32_t>(rate_));
        // Measured on the Tab S9+ playing a tone (whole chain, % of one core): none
        // 14% with ~155 ms of output latency, low latency 23% (audioserver +9) with
        // ~54 ms, power saving the same as none. The sink reports the latency to
        // PipeWire, so players keep audio and video in sync either way.
        AAudioStreamBuilder_setPerformanceMode(builder, AAUDIO_PERFORMANCE_MODE_NONE);
        AAudioStreamBuilder_setUsage(builder, AAUDIO_USAGE_MEDIA);
        AAudioStreamBuilder_setContentType(builder, AAUDIO_CONTENT_TYPE_MUSIC);
        AAudioStreamBuilder_setDataCallback(builder, &Session::onData, this);
        AAudioStreamBuilder_setErrorCallback(builder, &Session::onError, this);
        aaudio_result_t result = AAudioStreamBuilder_openStream(builder, &stream_);
        AAudioStreamBuilder_delete(builder);
        if (result != AAUDIO_OK) {
            LOGE("cannot open the output: %s", AAudio_convertResultToText(result));
            stream_ = nullptr;
            return false;
        }
        LOGI("output open: %d Hz, burst %d, buffer %d of %d frames, performance mode %d",
             AAudioStream_getSampleRate(stream_), AAudioStream_getFramesPerBurst(stream_),
             AAudioStream_getBufferSizeInFrames(stream_), AAudioStream_getBufferCapacityInFrames(stream_),
             AAudioStream_getPerformanceMode(stream_));
        return true;
    }

    void closeStream() {
        playing_ = false;
        if (stream_ != nullptr) {
            AAudioStream_close(stream_);  // waits for the callback
            stream_ = nullptr;
        }
        if (ring_ != nullptr) __atomic_store_n(&ring_->burst, 0u, __ATOMIC_RELAXED);
    }

    void start() {
        playing_ = true;
        if (stream_ == nullptr && !openStream()) {
            playing_ = false;
            return;
        }
        underruns_ = __atomic_load_n(&ring_->underruns, __ATOMIC_RELAXED);
        aaudio_result_t result = AAudioStream_requestStart(stream_);
        if (result != AAUDIO_OK) {
            LOGE("cannot start the output: %s", AAudio_convertResultToText(result));
            closeStream();
        }
        setPlaying(stream_ != nullptr);
    }

    // Counts the sessions playing; the app hears about the first and the last.
    void setPlaying(bool playing) {
        if (playing == counted_) return;
        counted_ = playing;
        int now = playing ? gPlaying.fetch_add(1) + 1 : gPlaying.fetch_sub(1) - 1;
        dnx_audio_playing_callback callback = gPlayingCallback.load();
        if (callback != nullptr && now == (playing ? 1 : 0)) callback(playing);
    }

    // The stream stays open: restarting it is faster than opening it again.
    void stop() {
        playing_ = false;
        setPlaying(false);
        if (stream_ == nullptr) return;
        AAudioStream_requestStop(stream_);
        aaudio_stream_state_t state = AAUDIO_STREAM_STATE_UNKNOWN;
        AAudioStream_waitForStateChange(stream_, AAUDIO_STREAM_STATE_STOPPING, &state, kStopTimeoutNs);
        __atomic_store_n(&ring_->burst, 0u, __ATOMIC_RELAXED);
        // Short ring callbacks include the first ones, before the sink's clock starts.
        LOGI("output stopped: %lld frames written since open, %u short callbacks, %d device underruns, latency %u frames",
             static_cast<long long>(AAudioStream_getFramesWritten(stream_)),
             __atomic_load_n(&ring_->underruns, __ATOMIC_RELAXED) - underruns_, AAudioStream_getXRunCount(stream_),
             __atomic_load_n(&ring_->latency, __ATOMIC_RELAXED));
    }

    // Frames between the ring and the speaker, for the sink's delay (A/V sync).
    void updateLatency() {
        int64_t position = 0, time = 0;
        if (stream_ == nullptr || AAudioStream_getTimestamp(stream_, CLOCK_MONOTONIC, &position, &time) != AAUDIO_OK) {
            return;
        }
        int64_t presented = position + (nowNs() - time) * rate_ / 1'000'000'000LL;
        int64_t latency = std::clamp<int64_t>(AAudioStream_getFramesWritten(stream_) - presented, 0, rate_);
        __atomic_store_n(&ring_->latency, static_cast<uint32_t>(latency), __ATOMIC_RELAXED);
    }

    static aaudio_data_callback_result_t onData(AAudioStream*, void* user, void* audio, int32_t count) {
        auto* self = static_cast<Session*>(user);
        auto* out = static_cast<float*>(audio);
        dnx_audio_ring* ring = self->ring_;
        const uint32_t frames = self->frames_, channels = self->channels_, wanted = static_cast<uint32_t>(count);
        uint32_t read = __atomic_load_n(&ring->read_index, __ATOMIC_RELAXED);
        uint32_t available = std::min(__atomic_load_n(&ring->write_index, __ATOMIC_ACQUIRE) - read, frames);
        uint32_t take = std::min(available, wanted);
        for (uint32_t done = 0; done < take;) {
            uint32_t at = (read + done) & (frames - 1);
            uint32_t n = std::min(take - done, frames - at);
            std::memcpy(out + done * channels, self->samples_ + at * channels, n * channels * sizeof(float));
            done += n;
        }
        if (take < wanted) {
            std::memset(out + take * channels, 0, (wanted - take) * channels * sizeof(float));
            __atomic_fetch_add(&ring->underruns, 1u, __ATOMIC_RELAXED);
        }
        __atomic_store_n(&ring->read_index, read + take, __ATOMIC_RELEASE);
        if (gMuted.load(std::memory_order_relaxed)) std::memset(out, 0, wanted * channels * sizeof(float));
        // The sink starts its clock once this is set, and keeps a burst in the ring.
        if (self->playing_ && wanted > __atomic_load_n(&ring->burst, __ATOMIC_RELAXED)) {
            __atomic_store_n(&ring->burst, wanted, __ATOMIC_RELAXED);
        }
        return AAUDIO_CALLBACK_RESULT_CONTINUE;
    }

    static void onError(AAudioStream*, void* user, aaudio_result_t error) {
        LOGW("output error: %s", AAudio_convertResultToText(error));
        static_cast<Session*>(user)->lost_ = true;
    }

    const int fd_;
    dnx_audio_ring* ring_ = nullptr;
    size_t size_ = 0;
    float* samples_ = nullptr;
    uint32_t rate_ = 0, channels_ = 0, frames_ = 0;
    uint32_t underruns_ = 0;  // ring underruns when the output last started
    AAudioStream* stream_ = nullptr;
    std::atomic<bool> playing_{false};
    std::atomic<bool> lost_{false};
    bool counted_ = false;  // in gPlaying (session thread only)
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
        if (fd < 0 || bind(fd, reinterpret_cast<sockaddr*>(&address), sizeof(address)) != 0 || listen(fd, 4) != 0) {
            LOGE("cannot listen on %s: %s", path, std::strerror(errno));
            if (fd >= 0) ::close(fd);
            return false;
        }
        listener_ = fd;
        std::thread([fd] {
            pthread_setname_np(pthread_self(), "dnx-audio-srv");
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

extern "C" void dnx_audio_output_set_playing_callback(dnx_audio_playing_callback callback) {
    gPlayingCallback.store(callback);
}

extern "C" void dnx_audio_output_set_muted(bool muted) { gMuted.store(muted); }

extern "C" bool dnx_audio_output_start(const char* socket_path) {
    static Server* server = new Server();  // lives as long as the process
    return server->start(socket_path);
}
