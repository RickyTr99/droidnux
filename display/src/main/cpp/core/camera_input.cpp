#include "camera_input.h"

#include <android/log.h>
#include <camera/NdkCameraDevice.h>
#include <camera/NdkCameraManager.h>
#include <camera/NdkCameraMetadata.h>
#include <media/NdkImageReader.h>
#include <poll.h>
#include <pthread.h>
#include <sys/eventfd.h>
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
#include <string>
#include <thread>
#include <vector>

#include "camera_protocol.h"

#define LOG_TAG "DroidNux.Camera"
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

// Webcam sizes: larger ones cost a copy per frame that no video call uses.
constexpr int32_t kMaxWidth = 1920;
constexpr int32_t kMaxHeight = 1080;
constexpr uint32_t kMaxFps = 30;

struct Camera {
    std::string id;
    uint32_t facing = DNX_CAMERA_EXTERNAL;
    int orientation = 0;  // clockwise turn of the sensor's image to the screen's natural orientation
    std::vector<dnx_camera_size> sizes;
    std::vector<std::pair<int32_t, int32_t>> fpsRanges;
};

std::atomic<int> gRotation{0};
std::atomic<bool> gPaused{false};
std::atomic<dnx_camera_permission_callback> gPermissionCallback{nullptr};
// Each answer of the user: a camera that waits for one opens (or gives up) once it changes.
std::atomic<uint32_t> gPermissionAnswers{0};
std::atomic<bool> gPermissionGranted{false};

class Session;
std::mutex gSessionsMutex;
std::vector<Session*> gSessions;

int64_t nowNs() {
    timespec ts{};
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return ts.tv_sec * 1'000'000'000LL + ts.tv_nsec;
}

// The device's cameras that give pictures (not depth only), once per process.
const std::vector<Camera>& cameras() {
    static std::vector<Camera>* list = [] {
        auto* found = new std::vector<Camera>();
        ACameraManager* manager = ACameraManager_create();
        ACameraIdList* ids = nullptr;
        if (manager == nullptr || ACameraManager_getCameraIdList(manager, &ids) != ACAMERA_OK) {
            LOGE("cannot list the cameras");
            if (manager) ACameraManager_delete(manager);
            return found;
        }
        for (int i = 0; i < ids->numCameras; i++) {
            ACameraMetadata* meta = nullptr;
            if (ACameraManager_getCameraCharacteristics(manager, ids->cameraIds[i], &meta) != ACAMERA_OK) continue;
            Camera camera;
            camera.id = ids->cameraIds[i];
            ACameraMetadata_const_entry entry{};
            bool pictures = false;
            if (ACameraMetadata_getConstEntry(meta, ACAMERA_REQUEST_AVAILABLE_CAPABILITIES, &entry) == ACAMERA_OK) {
                for (uint32_t c = 0; c < entry.count; c++) {
                    pictures |= entry.data.u8[c] == ACAMERA_REQUEST_AVAILABLE_CAPABILITIES_BACKWARD_COMPATIBLE;
                }
            }
            if (ACameraMetadata_getConstEntry(meta, ACAMERA_LENS_FACING, &entry) == ACAMERA_OK && entry.count) {
                camera.facing = entry.data.u8[0] == ACAMERA_LENS_FACING_FRONT  ? DNX_CAMERA_FRONT
                                : entry.data.u8[0] == ACAMERA_LENS_FACING_BACK ? DNX_CAMERA_BACK
                                                                               : DNX_CAMERA_EXTERNAL;
            }
            if (ACameraMetadata_getConstEntry(meta, ACAMERA_SENSOR_ORIENTATION, &entry) == ACAMERA_OK && entry.count) {
                camera.orientation = entry.data.i32[0];
            }
            if (ACameraMetadata_getConstEntry(meta, ACAMERA_CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES, &entry) ==
                ACAMERA_OK) {
                for (uint32_t c = 0; c + 1 < entry.count; c += 2) {
                    camera.fpsRanges.emplace_back(entry.data.i32[c], entry.data.i32[c + 1]);
                }
            }
            ACameraMetadata_const_entry durations{};
            bool haveDurations = ACameraMetadata_getConstEntry(meta, ACAMERA_SCALER_AVAILABLE_MIN_FRAME_DURATIONS,
                                                               &durations) == ACAMERA_OK;
            if (ACameraMetadata_getConstEntry(meta, ACAMERA_SCALER_AVAILABLE_STREAM_CONFIGURATIONS, &entry) ==
                ACAMERA_OK) {
                for (uint32_t c = 0; c + 3 < entry.count; c += 4) {
                    int32_t format = entry.data.i32[c], width = entry.data.i32[c + 1], height = entry.data.i32[c + 2];
                    if (format != AIMAGE_FORMAT_YUV_420_888 || entry.data.i32[c + 3] != 0 || width < height ||
                        width > kMaxWidth || height > kMaxHeight || width % 2 || height % 2) {
                        continue;
                    }
                    uint32_t fps = kMaxFps;
                    for (uint32_t d = 0; haveDurations && d + 3 < durations.count; d += 4) {
                        if (durations.data.i64[d] == format && durations.data.i64[d + 1] == width &&
                            durations.data.i64[d + 2] == height && durations.data.i64[d + 3] > 0) {
                            fps = std::min<uint32_t>(kMaxFps, 1'000'000'000LL / durations.data.i64[d + 3]);
                        }
                    }
                    camera.sizes.push_back({static_cast<uint32_t>(width), static_cast<uint32_t>(height), fps});
                }
            }
            ACameraMetadata_free(meta);
            std::sort(camera.sizes.begin(), camera.sizes.end(), [](const auto& a, const auto& b) {
                return a.width * a.height > b.width * b.height;
            });
            if (camera.sizes.size() > DNX_CAMERA_MAX_SIZES) camera.sizes.resize(DNX_CAMERA_MAX_SIZES);
            if (!pictures || camera.sizes.empty()) continue;
            LOGI("camera %s: facing %u, sensor at %d degrees, %zu sizes up to %ux%u", camera.id.c_str(),
                 camera.facing, camera.orientation, camera.sizes.size(), camera.sizes[0].width,
                 camera.sizes[0].height);
            found->push_back(std::move(camera));
        }
        ACameraManager_deleteCameraIdList(ids);
        ACameraManager_delete(manager);
        return found;
    }();
    return *list;
}

// Turns the camera's YUV_420_888 image upright (a clockwise turn of `turn` degrees) into a `width` x
// `height` NV12 or I420 frame, cropping to its shape: a picture taken in portrait fills a landscape
// frame, as webcam drivers do. Nearest samples: the sizes match except when the screen is turned.
class Converter {
public:
    void convert(AImage* image, int turn, uint8_t* out, uint32_t width, uint32_t height, uint32_t format) {
        int32_t sw = 0, sh = 0;
        AImage_getWidth(image, &sw);
        AImage_getHeight(image, &sh);
        uint8_t *y = nullptr, *u = nullptr, *v = nullptr;
        int yLength = 0, uLength = 0, vLength = 0, yRow = 0, uRow = 0, vRow = 0, uStep = 1, vStep = 1;
        if (AImage_getPlaneData(image, 0, &y, &yLength) != AMEDIA_OK ||
            AImage_getPlaneData(image, 1, &u, &uLength) != AMEDIA_OK ||
            AImage_getPlaneData(image, 2, &v, &vLength) != AMEDIA_OK) {
            return;
        }
        AImage_getPlaneRowStride(image, 0, &yRow);
        AImage_getPlaneRowStride(image, 1, &uRow);
        AImage_getPlaneRowStride(image, 2, &vRow);
        AImage_getPlanePixelStride(image, 1, &uStep);
        AImage_getPlanePixelStride(image, 2, &vStep);

        uint8_t* outY = out;
        uint8_t* outU = out + width * height;
        uint32_t cw = width / 2, ch = height / 2;
        if (turn == 0 && static_cast<uint32_t>(sw) == width && static_cast<uint32_t>(sh) == height) {
            for (uint32_t row = 0; row < height; row++) std::memcpy(outY + row * width, y + row * yRow, width);
            for (uint32_t row = 0; row < ch; row++) {
                const uint8_t* su = u + row * uRow;
                const uint8_t* sv = v + row * vRow;
                if (format == DNX_CAMERA_NV12) {
                    uint8_t* d = outU + row * width;
                    if (uStep == 2 && vStep == 2 && sv == su + 1) {
                        std::memcpy(d, su, width);  // already interleaved: Android's usual NV21 is V first, NV12 is U first
                    } else {
                        for (uint32_t x = 0; x < cw; x++) {
                            d[2 * x] = su[x * uStep];
                            d[2 * x + 1] = sv[x * vStep];
                        }
                    }
                } else {
                    uint8_t* du = outU + row * cw;
                    uint8_t* dv = outU + cw * ch + row * cw;
                    for (uint32_t x = 0; x < cw; x++) {
                        du[x] = su[x * uStep];
                        dv[x] = sv[x * vStep];
                    }
                }
            }
            return;
        }
        plane(y, yRow, 1, nullptr, 0, 0, sw, sh, turn, outY, width, height, 1);
        if (format == DNX_CAMERA_NV12) {
            plane(u, uRow, uStep, v, vRow, vStep, sw / 2, sh / 2, turn, outU, cw, ch, 2);
        } else {
            plane(u, uRow, uStep, nullptr, 0, 0, sw / 2, sh / 2, turn, outU, cw, ch, 1);
            plane(v, vRow, vStep, nullptr, 0, 0, sw / 2, sh / 2, turn, outU + cw * ch, cw, ch, 1);
        }
    }

private:
    // One plane, or two interleaved into `out` (`step` 2: NV12's U and V).
    void plane(const uint8_t* a, int aRow, int aStep, const uint8_t* b, int bRow, int bStep, int sw, int sh, int turn,
               uint8_t* out, uint32_t width, uint32_t height, int step) {
        bool sideways = turn == 90 || turn == 270;
        int rw = sideways ? sh : sw, rh = sideways ? sw : sh;  // the upright picture
        float scale = std::max(static_cast<float>(width) / rw, static_cast<float>(height) / rh);
        float ox = (rw * scale - width) / 2, oy = (rh * scale - height) / 2;
        xs_.resize(width);
        ys_.resize(height);
        for (uint32_t x = 0; x < width; x++) xs_[x] = std::clamp(static_cast<int>((x + 0.5f + ox) / scale), 0, rw - 1);
        for (uint32_t y = 0; y < height; y++) ys_[y] = std::clamp(static_cast<int>((y + 0.5f + oy) / scale), 0, rh - 1);
        for (uint32_t y = 0; y < height; y++) {
            uint8_t* d = out + y * width * step;
            int ry = ys_[y];
            for (uint32_t x = 0; x < width; x++) {
                int rx = xs_[x], sx, sy;
                switch (turn) {
                    case 90: sx = ry; sy = sh - 1 - rx; break;
                    case 180: sx = sw - 1 - rx; sy = sh - 1 - ry; break;
                    case 270: sx = sw - 1 - ry; sy = rx; break;
                    default: sx = rx; sy = ry; break;
                }
                d[x * step] = a[sy * aRow + sx * aStep];
                if (b != nullptr) d[x * step + 1] = b[sy * bRow + sx * bStep];
            }
        }
    }

    std::vector<int> xs_, ys_;
};

class Session {
public:
    explicit Session(int fd) : fd_(fd), wake_(eventfd(0, EFD_CLOEXEC | EFD_NONBLOCK)) {}

    ~Session() {
        {
            std::lock_guard lock(gSessionsMutex);
            gSessions.erase(std::remove(gSessions.begin(), gSessions.end(), this), gSessions.end());
        }
        close();
        unmap();
        ::close(fd_);
        if (wake_ >= 0) ::close(wake_);
    }

    // The connection's thread: opens and closes the camera as the client and the app ask.
    void run() {
        pthread_setname_np(pthread_self(), "dnx-camera");
        if (!hello()) return;
        {
            std::lock_guard lock(gSessionsMutex);
            gSessions.push_back(this);
        }
        for (;;) {
            pollfd fds[2] = {{fd_, POLLIN, 0}, {wake_, POLLIN, 0}};
            if (poll(fds, 2, -1) < 0) {
                if (errno == EINTR) continue;
                return;
            }
            if (fds[1].revents & POLLIN) {
                uint64_t count;
                while (read(wake_, &count, sizeof(count)) > 0) {}
            }
            if ((fds[0].revents & (POLLIN | POLLHUP | POLLERR)) && !message()) return;
            update();
        }
    }

    void wake() {
        uint64_t one = 1;
        if (wake_ >= 0) write(wake_, &one, sizeof(one));
    }

private:
    bool hello() {
        struct {
            dnx_camera_header header;
            dnx_camera_hello hello;
        } message{};
        ssize_t n;
        do {
            n = recv(fd_, &message, sizeof(message), MSG_WAITALL);
        } while (n < 0 && errno == EINTR);
        if (n != static_cast<ssize_t>(sizeof(message)) || message.header.type != DNX_CAMERA_HELLO ||
            message.header.size != sizeof(dnx_camera_hello) || message.hello.magic != DNX_CAMERA_MAGIC ||
            message.hello.version != DNX_CAMERA_VERSION) {
            LOGE("bad HELLO");
            return false;
        }
        const auto& list = cameras();
        dnx_camera_info info{};
        info.count = static_cast<uint32_t>(list.size());
        if (message.hello.index < list.size()) {
            camera_ = &list[message.hello.index];
            info.facing = camera_->facing;
            info.sizes = static_cast<uint32_t>(camera_->sizes.size());
            std::copy(camera_->sizes.begin(), camera_->sizes.end(), info.size);
        }
        return send(DNX_CAMERA_INFO, &info, sizeof(info)) && camera_ != nullptr;
    }

    bool send(uint32_t type, const void* body, uint32_t size, int flags = 0) {
        std::lock_guard lock(sendMutex_);
        dnx_camera_header header{type, size};
        iovec iov[2] = {{&header, sizeof(header)}, {const_cast<void*>(body), size}};
        msghdr msg{};
        msg.msg_iov = iov;
        msg.msg_iovlen = size ? 2 : 1;
        return sendmsg(fd_, &msg, MSG_NOSIGNAL | flags) == static_cast<ssize_t>(sizeof(header) + size);
    }

    void sendError(uint32_t code) {
        dnx_camera_error error{code};
        send(DNX_CAMERA_ERROR, &error, sizeof(error));
    }

    // A message from the client; false once it is gone.
    bool message() {
        struct {
            dnx_camera_header header;
            dnx_camera_start start;
        } message{};
        union {
            cmsghdr align;
            char buf[CMSG_SPACE(sizeof(int))];
        } control{};
        iovec iov{&message, sizeof(message.header)};
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
        bool ok = n == static_cast<ssize_t>(sizeof(message.header));
        if (ok && message.header.type == DNX_CAMERA_START && message.header.size == sizeof(dnx_camera_start)) {
            ok = recv(fd_, &message.start, sizeof(message.start), MSG_WAITALL) ==
                 static_cast<ssize_t>(sizeof(message.start));
            if (ok) start(message.start, memfd);
            memfd = -1;
        } else if (ok && message.header.type == DNX_CAMERA_STOP && message.header.size == 0) {
            wanted_ = false;
        } else if (ok) {
            LOGW("unexpected message %u", message.header.type);
            ok = false;
        }
        if (memfd >= 0) ::close(memfd);
        return ok;
    }

    void start(const dnx_camera_start& start, int memfd) {
        bool known = std::any_of(camera_->sizes.begin(), camera_->sizes.end(), [&](const dnx_camera_size& s) {
            return s.width == start.width && s.height == start.height && start.fps >= 1 && start.fps <= s.fps;
        });
        uint64_t size = DNX_CAMERA_RING_SIZE(start.width, start.height);
        struct stat st{};
        if (!known || (start.format != DNX_CAMERA_NV12 && start.format != DNX_CAMERA_I420) || memfd < 0 ||
            fstat(memfd, &st) != 0 || static_cast<uint64_t>(st.st_size) < size) {
            LOGE("bad START: %ux%u at %u fps, format %u", start.width, start.height, start.fps, start.format);
            if (memfd >= 0) ::close(memfd);
            sendError(DNX_CAMERA_UNAVAILABLE);
            return;
        }
        void* map = mmap(nullptr, size, PROT_READ | PROT_WRITE, MAP_SHARED, memfd, 0);
        ::close(memfd);
        if (map == MAP_FAILED) {
            sendError(DNX_CAMERA_UNAVAILABLE);
            return;
        }
        close();  // a new format: the camera opens again for it
        unmap();
        std::lock_guard lock(frameMutex_);
        ring_ = static_cast<dnx_camera_ring*>(map);
        ringSize_ = size;
        width_ = start.width;
        height_ = start.height;
        fps_ = start.fps;
        format_ = start.format;
        wanted_ = true;  // a question to the user already asked still stands
    }

    // Opens or closes the camera for what the client wants, the session's pause and the user's answer.
    void update() {
        if (lost_.exchange(false)) {
            LOGW("camera %s lost", camera_->id.c_str());
            close();
            wanted_ = false;
            sendError(DNX_CAMERA_UNAVAILABLE);
        }
        if (askedAt_ != UINT32_MAX && askedAt_ != gPermissionAnswers.load()) {
            askedAt_ = UINT32_MAX;
            if (!gPermissionGranted.load()) {
                wanted_ = false;
                sendError(DNX_CAMERA_DENIED);
            }
        }
        bool run = wanted_ && !gPaused.load() && askedAt_ == UINT32_MAX;
        if (run && device_ == nullptr) {
            open();
        } else if (!run && device_ != nullptr) {
            close();
        }
    }

    void open() {
        ACameraManager* manager = ACameraManager_create();
        deviceCallbacks_ = {this, onDisconnected, onError};
        camera_status_t status = ACameraManager_openCamera(manager, camera_->id.c_str(), &deviceCallbacks_, &device_);
        ACameraManager_delete(manager);
        if (status == ACAMERA_ERROR_PERMISSION_DENIED) {
            device_ = nullptr;
            askedAt_ = gPermissionAnswers.load();
            if (auto callback = gPermissionCallback.load()) {
                LOGI("camera %s: asking for the permission", camera_->id.c_str());
                callback();
            } else {
                askedAt_ = UINT32_MAX;
                wanted_ = false;
                sendError(DNX_CAMERA_DENIED);
            }
            return;
        }
        if (status != ACAMERA_OK) {
            LOGE("cannot open camera %s: %d", camera_->id.c_str(), status);
            device_ = nullptr;
            wanted_ = false;
            sendError(DNX_CAMERA_UNAVAILABLE);
            return;
        }
        if (!capture()) {
            close();
            wanted_ = false;
            sendError(DNX_CAMERA_UNAVAILABLE);
            return;
        }
        LOGI("camera %s: %ux%u at %u fps", camera_->id.c_str(), width_, height_, fps_);
    }

    // The capture session: the reader's surface, repeated at the client's rate.
    bool capture() {
        uint32_t turn = turnFor(gRotation.load());
        bool sideways = turn == 90 || turn == 270;
        // The frame's own size; turned sideways, the smallest whose height covers the frame's width
        // (the largest if none does), as the picture is then cropped from its middle.
        int32_t width = static_cast<int32_t>(width_), height = static_cast<int32_t>(height_);
        if (sideways) {
            width = static_cast<int32_t>(camera_->sizes[0].width);
            height = static_cast<int32_t>(camera_->sizes[0].height);
            for (const auto& s : camera_->sizes) {
                if (s.height >= width_) {
                    width = static_cast<int32_t>(s.width);
                    height = static_cast<int32_t>(s.height);
                }
            }
        }
        if (AImageReader_new(width, height, AIMAGE_FORMAT_YUV_420_888, 4, &reader_) != AMEDIA_OK) return false;
        imageListener_ = {this, onImage};
        AImageReader_setImageListener(reader_, &imageListener_);
        ANativeWindow* window = nullptr;
        if (AImageReader_getWindow(reader_, &window) != AMEDIA_OK) return false;
        int32_t low = static_cast<int32_t>(fps_), high = static_cast<int32_t>(fps_);
        for (const auto& range : camera_->fpsRanges) {
            // The lowest floor at the client's rate: the exposure may lengthen in the dark, as a webcam's.
            if (range.second == high && range.first < low) low = range.first;
        }
        int32_t fps[2] = {low, high};
        sessionCallbacks_ = {this, onSessionState, onSessionState, onSessionState};
        return ACaptureSessionOutputContainer_create(&outputs_) == ACAMERA_OK &&
               ACaptureSessionOutput_create(window, &output_) == ACAMERA_OK &&
               ACaptureSessionOutputContainer_add(outputs_, output_) == ACAMERA_OK &&
               ACameraOutputTarget_create(window, &target_) == ACAMERA_OK &&
               ACameraDevice_createCaptureRequest(device_, TEMPLATE_RECORD, &request_) == ACAMERA_OK &&
               ACaptureRequest_addTarget(request_, target_) == ACAMERA_OK &&
               ACaptureRequest_setEntry_i32(request_, ACAMERA_CONTROL_AE_TARGET_FPS_RANGE, 2, fps) == ACAMERA_OK &&
               ACameraDevice_createCaptureSession(device_, outputs_, &sessionCallbacks_, &session_) == ACAMERA_OK &&
               ACameraCaptureSession_setRepeatingRequest(session_, nullptr, 1, &request_, nullptr) == ACAMERA_OK;
    }

    void close() {
        if (session_) {
            ACameraCaptureSession_stopRepeating(session_);
            ACameraCaptureSession_close(session_);
            session_ = nullptr;
        }
        if (device_) {
            ACameraDevice_close(device_);
            device_ = nullptr;
            LOGI("camera %s closed", camera_->id.c_str());
        }
        if (request_) ACaptureRequest_free(request_);
        if (target_) ACameraOutputTarget_free(target_);
        if (outputs_) ACaptureSessionOutputContainer_free(outputs_);
        if (output_) ACaptureSessionOutput_free(output_);
        request_ = nullptr;
        target_ = nullptr;
        outputs_ = nullptr;
        output_ = nullptr;
        if (reader_) {
            AImageReader_setImageListener(reader_, nullptr);
            AImageReader_delete(reader_);
            reader_ = nullptr;
        }
    }

    void unmap() {
        std::lock_guard lock(frameMutex_);
        if (ring_) munmap(ring_, ringSize_);
        ring_ = nullptr;
    }

    // Clockwise turn that makes this camera's picture upright on a screen turned `rotation` degrees.
    uint32_t turnFor(int rotation) const {
        int device = (360 - rotation) % 360;  // how far the device is turned clockwise
        int turn = camera_->facing == DNX_CAMERA_FRONT ? camera_->orientation + device : camera_->orientation - device;
        return static_cast<uint32_t>((turn % 360 + 360) % 360);
    }

    static void onImage(void* context, AImageReader* reader) {
        auto* self = static_cast<Session*>(context);
        AImage* image = nullptr;
        if (AImageReader_acquireLatestImage(reader, &image) != AMEDIA_OK || image == nullptr) return;
        {
            std::lock_guard lock(self->frameMutex_);
            dnx_camera_ring* ring = self->ring_;
            if (ring != nullptr) {
                uint32_t sequence = __atomic_load_n(&ring->sequence, __ATOMIC_RELAXED);
                uint32_t slot = sequence % DNX_CAMERA_SLOTS;
                auto* frame = reinterpret_cast<uint8_t*>(ring) + DNX_CAMERA_RING_DATA +
                              slot * DNX_CAMERA_FRAME_SIZE(self->width_, self->height_);
                self->converter_.convert(image, static_cast<int>(self->turnFor(gRotation.load())), frame,
                                         self->width_, self->height_, self->format_);
                __atomic_store_n(&ring->timestamp[slot], static_cast<uint64_t>(nowNs()), __ATOMIC_RELAXED);
                __atomic_store_n(&ring->sequence, sequence + 1, __ATOMIC_RELEASE);
            }
        }
        AImage_delete(image);
        self->send(DNX_CAMERA_FRAME, nullptr, 0, MSG_DONTWAIT);  // a client behind takes the latest frame anyway
    }

    static void onSessionState(void*, ACameraCaptureSession*) {}

    static void onDisconnected(void* context, ACameraDevice*) {
        auto* self = static_cast<Session*>(context);
        self->lost_ = true;
        self->wake();
    }

    static void onError(void* context, ACameraDevice*, int error) {
        LOGE("camera error %d", error);
        onDisconnected(context, nullptr);
    }

    int fd_;
    int wake_;
    const Camera* camera_ = nullptr;
    std::mutex sendMutex_;
    // The ring and the frame's format, for the reader's callbacks.
    std::mutex frameMutex_;
    dnx_camera_ring* ring_ = nullptr;
    uint64_t ringSize_ = 0;
    uint32_t width_ = 0, height_ = 0, fps_ = 0, format_ = 0;
    Converter converter_;
    // The session thread's state.
    bool wanted_ = false;
    uint32_t askedAt_ = UINT32_MAX;  // the answers counted when the permission was asked for
    std::atomic<bool> lost_{false};
    ACameraDevice_StateCallbacks deviceCallbacks_{};
    ACameraCaptureSession_stateCallbacks sessionCallbacks_{};
    AImageReader_ImageListener imageListener_{};
    ACameraDevice* device_ = nullptr;
    AImageReader* reader_ = nullptr;
    ACaptureSessionOutputContainer* outputs_ = nullptr;
    ACaptureSessionOutput* output_ = nullptr;
    ACameraOutputTarget* target_ = nullptr;
    ACaptureRequest* request_ = nullptr;
    ACameraCaptureSession* session_ = nullptr;
};

void wakeAll() {
    std::lock_guard lock(gSessionsMutex);
    for (Session* session : gSessions) session->wake();
}

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
            pthread_setname_np(pthread_self(), "dnx-camera-srv");
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

extern "C" void dnx_camera_input_set_rotation(int degrees) { gRotation.store(((degrees % 360) + 360) % 360); }

extern "C" void dnx_camera_input_set_permission_callback(dnx_camera_permission_callback callback) {
    gPermissionCallback.store(callback);
}

extern "C" void dnx_camera_input_set_permission(bool granted) {
    gPermissionGranted.store(granted);
    gPermissionAnswers.fetch_add(1);
    wakeAll();
}

extern "C" void dnx_camera_input_set_paused(bool paused) {
    gPaused.store(paused);
    wakeAll();
}

extern "C" bool dnx_camera_input_start(const char* socket_path) {
    static Server* server = new Server();  // lives as long as the process
    return server->start(socket_path);
}
