#include "probe.h"

#include <EGL/egl.h>
#include <android/hardware_buffer.h>
#include <dlfcn.h>
#include <unistd.h>
#include <vulkan/vulkan.h>

#include <cstdarg>
#include <cstdio>
#include <cstring>
#include <vector>

namespace droidnux::display {

namespace {

void appendf(std::string& out, const char* format, ...) {
    char line[512];
    va_list args;
    va_start(args, format);
    std::vsnprintf(line, sizeof(line), format, args);
    va_end(args);
    out += line;
}

// Layout of native_handle_t (system/core/libcutils), stable since Android 1.
struct NativeHandle {
    int version;
    int numFds;
    int numInts;
    int data[0];
};

using GetNativeHandleFn = const NativeHandle* (*)(const AHardwareBuffer*);

GetNativeHandleFn getNativeHandleFn() {
    // Not in the public NDK headers, but exported by libnativewindow.
    static auto fn = reinterpret_cast<GetNativeHandleFn>(
        dlsym(dlopen("libnativewindow.so", RTLD_NOW), "AHardwareBuffer_getNativeHandle"));
    return fn;
}

void describeHandle(std::string& out, const AHardwareBuffer* buffer) {
    auto getHandle = getNativeHandleFn();
    if (getHandle == nullptr) {
        out += "  native handle: AHardwareBuffer_getNativeHandle not available\n";
        return;
    }
    const NativeHandle* handle = getHandle(buffer);
    if (handle == nullptr) {
        out += "  native handle: null\n";
        return;
    }
    appendf(out, "  native handle: %d fds, %d ints\n", handle->numFds, handle->numInts);
    for (int i = 0; i < handle->numFds; i++) {
        int fd = handle->data[i];
        char link[64];
        char target[128] = {};
        std::snprintf(link, sizeof(link), "/proc/self/fd/%d", fd);
        readlink(link, target, sizeof(target) - 1);
        off_t size = lseek(fd, 0, SEEK_END);
        lseek(fd, 0, SEEK_SET);
        appendf(out, "  fd[%d] = %s, size %lld\n", i, target, static_cast<long long>(size));
    }
    out += "  ints:";
    for (int i = 0; i < handle->numInts; i++) {
        if (i % 8 == 0) out += "\n   ";
        appendf(out, " %08x", static_cast<unsigned>(handle->data[handle->numFds + i]));
    }
    out += "\n";
}

// Allocates a buffer the way the presentation core will: a desktop-sized
// RGBA surface the GPU can render into and Android can composite.
void probeHardwareBuffer(std::string& out, const char* label, uint32_t width, uint32_t height, uint64_t usage) {
    AHardwareBuffer_Desc desc{};
    desc.width = width;
    desc.height = height;
    desc.layers = 1;
    desc.format = AHARDWAREBUFFER_FORMAT_R8G8B8A8_UNORM;
    desc.usage = usage;

    appendf(out, "AHardwareBuffer %ux%u RGBA8, %s: ", width, height, label);
    if (AHardwareBuffer_isSupported(&desc) == 0) {
        out += "not supported\n";
        return;
    }
    AHardwareBuffer* buffer = nullptr;
    int result = AHardwareBuffer_allocate(&desc, &buffer);
    if (result != 0 || buffer == nullptr) {
        appendf(out, "allocation failed (%d)\n", result);
        return;
    }
    AHardwareBuffer_Desc actual{};
    AHardwareBuffer_describe(buffer, &actual);
    appendf(out, "ok, stride %u px\n", actual.stride);
    describeHandle(out, buffer);
    AHardwareBuffer_release(buffer);
}

bool hasToken(const char* list, const char* token) {
    size_t length = std::strlen(token);
    for (const char* p = list; (p = std::strstr(p, token)) != nullptr; p += length) {
        bool startOk = p == list || p[-1] == ' ';
        bool endOk = p[length] == ' ' || p[length] == '\0';
        if (startOk && endOk) return true;
    }
    return false;
}

void probeEgl(std::string& out) {
    EGLDisplay display = eglGetDisplay(EGL_DEFAULT_DISPLAY);
    if (display == EGL_NO_DISPLAY || !eglInitialize(display, nullptr, nullptr)) {
        out += "EGL: cannot initialize\n";
        return;
    }
    const char* vendor = eglQueryString(display, EGL_VENDOR);
    const char* extensions = eglQueryString(display, EGL_EXTENSIONS);
    appendf(out, "EGL %s (%s)\n", eglQueryString(display, EGL_VERSION), vendor ? vendor : "?");
    for (const char* ext : {"EGL_EXT_image_dma_buf_import", "EGL_EXT_image_dma_buf_import_modifiers",
                            "EGL_ANDROID_get_native_client_buffer", "EGL_ANDROID_native_fence_sync",
                            "EGL_KHR_wait_sync"}) {
        appendf(out, "  %s %s\n", hasToken(extensions, ext) ? "+" : "-", ext);
    }
    eglTerminate(display);
}

void probeVulkan(std::string& out) {
    void* lib = dlopen("libvulkan.so", RTLD_NOW);
    auto getProc = lib ? reinterpret_cast<PFN_vkGetInstanceProcAddr>(dlsym(lib, "vkGetInstanceProcAddr")) : nullptr;
    if (getProc == nullptr) {
        out += "Vulkan: not available\n";
        return;
    }
    auto createInstance = reinterpret_cast<PFN_vkCreateInstance>(getProc(nullptr, "vkCreateInstance"));
    VkApplicationInfo app{};
    app.sType = VK_STRUCTURE_TYPE_APPLICATION_INFO;
    app.apiVersion = VK_API_VERSION_1_3;
    VkInstanceCreateInfo info{};
    info.sType = VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO;
    info.pApplicationInfo = &app;
    VkInstance instance = VK_NULL_HANDLE;
    if (createInstance(&info, nullptr, &instance) != VK_SUCCESS) {
        out += "Vulkan: vkCreateInstance failed\n";
        return;
    }
#define LOAD(name) auto name = reinterpret_cast<PFN_##name>(getProc(instance, #name))
    LOAD(vkEnumeratePhysicalDevices);
    LOAD(vkGetPhysicalDeviceProperties);
    LOAD(vkEnumerateDeviceExtensionProperties);
    LOAD(vkDestroyInstance);
#undef LOAD
    uint32_t count = 1;
    VkPhysicalDevice device = VK_NULL_HANDLE;
    vkEnumeratePhysicalDevices(instance, &count, &device);
    if (count == 0 || device == VK_NULL_HANDLE) {
        out += "Vulkan: no device\n";
        vkDestroyInstance(instance, nullptr);
        return;
    }
    VkPhysicalDeviceProperties props{};
    vkGetPhysicalDeviceProperties(device, &props);
    appendf(out, "Vulkan %u.%u.%u, %s, driver %08x\n", VK_API_VERSION_MAJOR(props.apiVersion),
            VK_API_VERSION_MINOR(props.apiVersion), VK_API_VERSION_PATCH(props.apiVersion), props.deviceName,
            props.driverVersion);

    uint32_t extCount = 0;
    vkEnumerateDeviceExtensionProperties(device, nullptr, &extCount, nullptr);
    std::vector<VkExtensionProperties> exts(extCount);
    vkEnumerateDeviceExtensionProperties(device, nullptr, &extCount, exts.data());
    for (const char* wanted : {"VK_EXT_external_memory_dma_buf", "VK_KHR_external_memory_fd",
                               "VK_EXT_image_drm_format_modifier", "VK_ANDROID_external_memory_android_hardware_buffer",
                               "VK_KHR_external_semaphore_fd", "VK_EXT_queue_family_foreign"}) {
        bool found = false;
        for (const auto& ext : exts) found |= std::strcmp(ext.extensionName, wanted) == 0;
        appendf(out, "  %s %s\n", found ? "+" : "-", wanted);
    }
    vkDestroyInstance(instance, nullptr);
}

}  // namespace

std::string probe() {
    constexpr uint64_t gpu = AHARDWAREBUFFER_USAGE_GPU_SAMPLED_IMAGE | AHARDWAREBUFFER_USAGE_GPU_COLOR_OUTPUT;
    constexpr uint64_t composer = AHARDWAREBUFFER_USAGE_COMPOSER_OVERLAY;
    constexpr uint64_t cpu = AHARDWAREBUFFER_USAGE_CPU_READ_RARELY | AHARDWAREBUFFER_USAGE_CPU_WRITE_RARELY;

    std::string report;
    probeHardwareBuffer(report, "gpu+composer", 2800, 1752, gpu | composer);
    probeHardwareBuffer(report, "gpu+composer+cpu", 2800, 1752, gpu | composer | cpu);
    probeHardwareBuffer(report, "gpu+composer", 64, 64, gpu | composer);
    probeEgl(report);
    probeVulkan(report);
    return report;
}

}  // namespace droidnux::display
