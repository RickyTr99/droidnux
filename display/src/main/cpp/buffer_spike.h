#pragma once

#include <android/native_window.h>
#include <android/surface_control.h>

#include <atomic>
#include <mutex>
#include <string>
#include <thread>
#include <vector>

struct AHardwareBuffer;

namespace droidnux::display {

// Buffer-sharing spike, app side: allocates AHardwareBuffers, hands their
// dma-bufs to the guest client over a Unix socket and presents the frames it
// renders with ASurfaceControl, recycling each buffer once SurfaceFlinger
// releases it. Throwaway code; what it proves feeds the presentation core.
class BufferSpike {
public:
    BufferSpike(ANativeWindow* window, uint32_t width, uint32_t height, uint32_t bufferCount);
    ~BufferSpike();

    BufferSpike(const BufferSpike&) = delete;
    BufferSpike& operator=(const BufferSpike&) = delete;

    // False if setup failed; error() says why.
    bool ok() const { return error_.empty(); }
    const std::string& error() const { return error_; }

    // One-line status: connection, presented and completed frames per second.
    std::string stats();

private:
    struct Slot {
        AHardwareBuffer* buffer = nullptr;
        int fd = -1;  // dma-buf, owned by the AHardwareBuffer
        uint32_t stride = 0;
        uint64_t size = 0;
    };

    struct Completion {
        BufferSpike* spike;
        int index;
        int previous;
    };

    bool allocate(uint32_t width, uint32_t height, uint32_t count);
    void serve();
    void handleClient(int client);
    void present(int index);
    void sendRelease(int index);
    static void onComplete(void* context, ASurfaceTransactionStats* stats);

    std::string error_;
    ANativeWindow* window_ = nullptr;
    ASurfaceControl* surface_ = nullptr;
    std::vector<Slot> slots_;
    uint32_t width_ = 0;
    uint32_t height_ = 0;

    int listenFd_ = -1;
    std::atomic<int> clientFd_{-1};
    std::mutex sendMutex_;
    std::atomic<bool> stop_{false};
    std::thread thread_;

    int lastPresented_ = -1;
    bool shown_ = false;

    std::atomic<bool> connected_{false};
    std::atomic<uint64_t> presented_{0};
    std::atomic<uint64_t> completed_{0};
    uint64_t lastPresented_count_ = 0;
    uint64_t lastCompleted_count_ = 0;
    int64_t lastStatsNs_ = 0;
    std::string lastStats_;
};

}  // namespace droidnux::display
