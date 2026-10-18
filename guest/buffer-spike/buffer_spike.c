/*
 * Buffer-sharing spike, guest side.
 *
 * Connects to the DroidNux app, receives AHardwareBuffers as dma-buf fds,
 * imports them into Vulkan (Turnip on KGSL) as linear images and renders an
 * animated test pattern into them, handing each finished frame back to the
 * app for presentation. No copies anywhere: the GPU writes straight into the
 * buffers SurfaceFlinger composites.
 *
 * Build inside the container:
 *   gcc -O2 -Wall -o buffer-spike buffer_spike.c -lvulkan
 */
#define _GNU_SOURCE
#include <errno.h>
#include <math.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <sys/un.h>
#include <time.h>
#include <unistd.h>
#include <vulkan/vulkan.h>

#include "protocol.h"

#define MAX_BUFFERS 4

#define CHECK(expr)                                                           \
    do {                                                                      \
        VkResult r_ = (expr);                                                 \
        if (r_ != VK_SUCCESS) {                                               \
            fprintf(stderr, "%s:%d: %s failed: %d\n", __FILE__, __LINE__, #expr, r_); \
            exit(1);                                                          \
        }                                                                     \
    } while (0)

struct slot {
    struct dn_buffer info;
    int fd;
    VkImage image;
    VkDeviceMemory memory;
    VkImageView view;
    VkFramebuffer framebuffer;
    int busy; /* owned by the app until released */
};

static struct slot slots[MAX_BUFFERS];
static uint32_t slot_count;

static double now_seconds(void) {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return ts.tv_sec + ts.tv_nsec / 1e9;
}

static int connect_app(void) {
    int fd = socket(AF_UNIX, SOCK_SEQPACKET | SOCK_CLOEXEC, 0);
    if (fd < 0) { perror("socket"); exit(1); }
    struct sockaddr_un addr = {.sun_family = AF_UNIX};
    /* Abstract socket: leading NUL, not translated by proot. */
    memcpy(addr.sun_path + 1, DN_SOCKET_NAME, strlen(DN_SOCKET_NAME));
    socklen_t len = offsetof(struct sockaddr_un, sun_path) + 1 + strlen(DN_SOCKET_NAME);
    if (connect(fd, (struct sockaddr*)&addr, len) < 0) { perror("connect"); exit(1); }
    return fd;
}

/* Receives one message, plus an fd if one is attached. */
static ssize_t recv_message(int sock, void* buf, size_t size, int* fd_out) {
    char control[CMSG_SPACE(sizeof(int))];
    struct iovec iov = {.iov_base = buf, .iov_len = size};
    struct msghdr msg = {.msg_iov = &iov, .msg_iovlen = 1, .msg_control = control, .msg_controllen = sizeof(control)};
    ssize_t n = recvmsg(sock, &msg, MSG_CMSG_CLOEXEC);
    if (fd_out) *fd_out = -1;
    for (struct cmsghdr* c = CMSG_FIRSTHDR(&msg); c; c = CMSG_NXTHDR(&msg, c)) {
        if (c->cmsg_level == SOL_SOCKET && c->cmsg_type == SCM_RIGHTS && fd_out)
            memcpy(fd_out, CMSG_DATA(c), sizeof(int));
    }
    return n;
}

static void send_message(int sock, const void* buf, size_t size) {
    if (send(sock, buf, size, MSG_NOSIGNAL) != (ssize_t)size) { perror("send"); exit(1); }
}

static void handle_release(const struct dn_message* m) {
    if (m->type == DN_MSG_RELEASE && m->index < slot_count) slots[m->index].busy = 0;
}

int main(void) {
    int sock = connect_app();

    struct dn_hello hello;
    if (recv_message(sock, &hello, sizeof(hello), NULL) != sizeof(hello) || hello.magic != DN_MAGIC ||
        hello.version != DN_VERSION || hello.count == 0 || hello.count > MAX_BUFFERS) {
        fprintf(stderr, "bad hello\n");
        return 1;
    }
    slot_count = hello.count;
    for (uint32_t i = 0; i < slot_count; i++) {
        if (recv_message(sock, &slots[i].info, sizeof(slots[i].info), &slots[i].fd) != sizeof(slots[i].info) ||
            slots[i].fd < 0) {
            fprintf(stderr, "bad buffer %u\n", i);
            return 1;
        }
        printf("buffer %u: %ux%u stride %u bytes, size %llu, fd %d\n", i, slots[i].info.width, slots[i].info.height,
               slots[i].info.stride, (unsigned long long)slots[i].info.size, slots[i].fd);
    }
    uint32_t width = slots[0].info.width, height = slots[0].info.height;

    /* -- Vulkan setup ----------------------------------------------------- */
    VkApplicationInfo app = {.sType = VK_STRUCTURE_TYPE_APPLICATION_INFO, .apiVersion = VK_API_VERSION_1_1,
                             .pApplicationName = "droidnux-buffer-spike"};
    VkInstanceCreateInfo ici = {.sType = VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO, .pApplicationInfo = &app};
    VkInstance instance;
    CHECK(vkCreateInstance(&ici, NULL, &instance));

    uint32_t count = 1;
    VkPhysicalDevice phys;
    vkEnumeratePhysicalDevices(instance, &count, &phys);
    if (count == 0) { fprintf(stderr, "no Vulkan device\n"); return 1; }
    VkPhysicalDeviceProperties props;
    vkGetPhysicalDeviceProperties(phys, &props);
    printf("device: %s, Vulkan %u.%u\n", props.deviceName, VK_API_VERSION_MAJOR(props.apiVersion),
           VK_API_VERSION_MINOR(props.apiVersion));

    const char* exts[] = {
        VK_KHR_EXTERNAL_MEMORY_FD_EXTENSION_NAME,
        VK_EXT_EXTERNAL_MEMORY_DMA_BUF_EXTENSION_NAME,
        VK_EXT_IMAGE_DRM_FORMAT_MODIFIER_EXTENSION_NAME,
        VK_EXT_QUEUE_FAMILY_FOREIGN_EXTENSION_NAME,
    };
    float priority = 1.0f;
    VkDeviceQueueCreateInfo qci = {.sType = VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO, .queueFamilyIndex = 0,
                                   .queueCount = 1, .pQueuePriorities = &priority};
    VkDeviceCreateInfo dci = {.sType = VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO, .queueCreateInfoCount = 1,
                              .pQueueCreateInfos = &qci, .enabledExtensionCount = 4, .ppEnabledExtensionNames = exts};
    VkDevice device;
    CHECK(vkCreateDevice(phys, &dci, NULL, &device));
    VkQueue queue;
    vkGetDeviceQueue(device, 0, 0, &queue);

    PFN_vkGetMemoryFdPropertiesKHR getMemoryFdProperties =
        (PFN_vkGetMemoryFdPropertiesKHR)vkGetDeviceProcAddr(device, "vkGetMemoryFdPropertiesKHR");

    VkAttachmentDescription attachment = {
        .format = VK_FORMAT_R8G8B8A8_UNORM, .samples = VK_SAMPLE_COUNT_1_BIT,
        .loadOp = VK_ATTACHMENT_LOAD_OP_CLEAR, .storeOp = VK_ATTACHMENT_STORE_OP_STORE,
        .stencilLoadOp = VK_ATTACHMENT_LOAD_OP_DONT_CARE, .stencilStoreOp = VK_ATTACHMENT_STORE_OP_DONT_CARE,
        .initialLayout = VK_IMAGE_LAYOUT_UNDEFINED, .finalLayout = VK_IMAGE_LAYOUT_GENERAL};
    VkAttachmentReference ref = {0, VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL};
    VkSubpassDescription subpass = {.pipelineBindPoint = VK_PIPELINE_BIND_POINT_GRAPHICS,
                                    .colorAttachmentCount = 1, .pColorAttachments = &ref};
    VkRenderPassCreateInfo rpci = {.sType = VK_STRUCTURE_TYPE_RENDER_PASS_CREATE_INFO, .attachmentCount = 1,
                                   .pAttachments = &attachment, .subpassCount = 1, .pSubpasses = &subpass};
    VkRenderPass render_pass;
    CHECK(vkCreateRenderPass(device, &rpci, NULL, &render_pass));

    /* -- Import the app's buffers ----------------------------------------- */
    for (uint32_t i = 0; i < slot_count; i++) {
        struct slot* s = &slots[i];
        VkSubresourceLayout plane = {.offset = s->info.offset, .rowPitch = s->info.stride};
        VkImageDrmFormatModifierExplicitCreateInfoEXT modifier = {
            .sType = VK_STRUCTURE_TYPE_IMAGE_DRM_FORMAT_MODIFIER_EXPLICIT_CREATE_INFO_EXT,
            .drmFormatModifier = 0 /* DRM_FORMAT_MOD_LINEAR */, .drmFormatModifierPlaneCount = 1,
            .pPlaneLayouts = &plane};
        VkExternalMemoryImageCreateInfo external = {.sType = VK_STRUCTURE_TYPE_EXTERNAL_MEMORY_IMAGE_CREATE_INFO,
                                                    .pNext = &modifier,
                                                    .handleTypes = VK_EXTERNAL_MEMORY_HANDLE_TYPE_DMA_BUF_BIT_EXT};
        VkImageCreateInfo imci = {.sType = VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO, .pNext = &external,
                                  .imageType = VK_IMAGE_TYPE_2D, .format = VK_FORMAT_R8G8B8A8_UNORM,
                                  .extent = {width, height, 1}, .mipLevels = 1, .arrayLayers = 1,
                                  .samples = VK_SAMPLE_COUNT_1_BIT, .tiling = VK_IMAGE_TILING_DRM_FORMAT_MODIFIER_EXT,
                                  .usage = VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT,
                                  .sharingMode = VK_SHARING_MODE_EXCLUSIVE,
                                  .initialLayout = VK_IMAGE_LAYOUT_UNDEFINED};
        CHECK(vkCreateImage(device, &imci, NULL, &s->image));

        VkMemoryRequirements req;
        vkGetImageMemoryRequirements(device, s->image, &req);
        VkMemoryFdPropertiesKHR fdProps = {.sType = VK_STRUCTURE_TYPE_MEMORY_FD_PROPERTIES_KHR};
        CHECK(getMemoryFdProperties(device, VK_EXTERNAL_MEMORY_HANDLE_TYPE_DMA_BUF_BIT_EXT, s->fd, &fdProps));
        uint32_t bits = req.memoryTypeBits & fdProps.memoryTypeBits;
        if (!bits) { fprintf(stderr, "no compatible memory type\n"); return 1; }
        uint32_t type = __builtin_ctz(bits);
        if (req.size > s->info.size) {
            fprintf(stderr, "image needs %llu bytes, buffer has %llu\n", (unsigned long long)req.size,
                    (unsigned long long)s->info.size);
            return 1;
        }

        VkMemoryDedicatedAllocateInfo dedicated = {.sType = VK_STRUCTURE_TYPE_MEMORY_DEDICATED_ALLOCATE_INFO,
                                                   .image = s->image};
        VkImportMemoryFdInfoKHR import = {.sType = VK_STRUCTURE_TYPE_IMPORT_MEMORY_FD_INFO_KHR, .pNext = &dedicated,
                                          .handleType = VK_EXTERNAL_MEMORY_HANDLE_TYPE_DMA_BUF_BIT_EXT,
                                          .fd = dup(s->fd) /* Vulkan takes ownership */};
        VkMemoryAllocateInfo mai = {.sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO, .pNext = &import,
                                    .allocationSize = req.size, .memoryTypeIndex = type};
        CHECK(vkAllocateMemory(device, &mai, NULL, &s->memory));
        CHECK(vkBindImageMemory(device, s->image, s->memory, 0));

        VkImageViewCreateInfo ivci = {.sType = VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO, .image = s->image,
                                      .viewType = VK_IMAGE_VIEW_TYPE_2D, .format = VK_FORMAT_R8G8B8A8_UNORM,
                                      .subresourceRange = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1}};
        CHECK(vkCreateImageView(device, &ivci, NULL, &s->view));
        VkFramebufferCreateInfo fbci = {.sType = VK_STRUCTURE_TYPE_FRAMEBUFFER_CREATE_INFO, .renderPass = render_pass,
                                        .attachmentCount = 1, .pAttachments = &s->view, .width = width,
                                        .height = height, .layers = 1};
        CHECK(vkCreateFramebuffer(device, &fbci, NULL, &s->framebuffer));
        printf("buffer %u imported: memory type %u, %llu bytes\n", i, type, (unsigned long long)req.size);
    }

    VkCommandPoolCreateInfo cpci = {.sType = VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO,
                                    .flags = VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT};
    VkCommandPool pool;
    CHECK(vkCreateCommandPool(device, &cpci, NULL, &pool));
    VkCommandBufferAllocateInfo cbai = {.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO, .commandPool = pool,
                                        .level = VK_COMMAND_BUFFER_LEVEL_PRIMARY, .commandBufferCount = 1};
    VkCommandBuffer cmd;
    CHECK(vkAllocateCommandBuffers(device, &cbai, &cmd));
    VkFenceCreateInfo fci = {.sType = VK_STRUCTURE_TYPE_FENCE_CREATE_INFO};
    VkFence fence;
    CHECK(vkCreateFence(device, &fci, NULL, &fence));

    /* -- Render loop ------------------------------------------------------ */
    printf("rendering\n");
    fflush(stdout);
    double start = now_seconds(), last_report = start;
    uint64_t frame = 0, frames_since_report = 0;
    double gpu_time = 0;

    for (;;) {
        /* Pick a free buffer, waiting for the app to release one. */
        int index = -1;
        while (index < 0) {
            for (uint32_t i = 0; i < slot_count; i++)
                if (!slots[i].busy) { index = (int)i; break; }
            if (index >= 0) break;
            struct dn_message m;
            ssize_t n = recv_message(sock, &m, sizeof(m), NULL);
            if (n <= 0) { printf("app closed the connection\n"); return 0; }
            handle_release(&m);
        }
        struct slot* s = &slots[index];

        double t = now_seconds() - start;
        float hue = (float)fmod(t * 0.2, 1.0) * 6.0f;
        float r = fabsf(hue - 3.0f) - 1.0f, g = 2.0f - fabsf(hue - 2.0f), b = 2.0f - fabsf(hue - 4.0f);
        VkClearValue clear = {.color = {{0.15f + 0.25f * fminf(fmaxf(r, 0), 1), 0.15f + 0.25f * fminf(fmaxf(g, 0), 1),
                                         0.15f + 0.25f * fminf(fmaxf(b, 0), 1), 1.0f}}};

        VkCommandBufferBeginInfo begin = {.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO,
                                          .flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT};
        CHECK(vkBeginCommandBuffer(cmd, &begin));

        /* Acquire from the app (foreign queue family). */
        VkImageMemoryBarrier acquire = {.sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER,
                                        .dstAccessMask = VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT,
                                        .oldLayout = VK_IMAGE_LAYOUT_UNDEFINED,
                                        .newLayout = VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL,
                                        .srcQueueFamilyIndex = VK_QUEUE_FAMILY_FOREIGN_EXT, .dstQueueFamilyIndex = 0,
                                        .image = s->image,
                                        .subresourceRange = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1}};
        vkCmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT, 0,
                             0, NULL, 0, NULL, 1, &acquire);

        VkRenderPassBeginInfo rpbi = {.sType = VK_STRUCTURE_TYPE_RENDER_PASS_BEGIN_INFO, .renderPass = render_pass,
                                      .framebuffer = s->framebuffer, .renderArea = {{0, 0}, {width, height}},
                                      .clearValueCount = 1, .pClearValues = &clear};
        vkCmdBeginRenderPass(cmd, &rpbi, VK_SUBPASS_CONTENTS_INLINE);

        /* A bar sweeping left to right: shows motion, judder and tearing. */
        uint32_t bar = width / 12;
        int32_t x = (int32_t)(fmod(t * 0.5, 1.0) * (width + bar)) - (int32_t)bar;
        VkClearAttachment white = {.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT, .colorAttachment = 0,
                                   .clearValue = {.color = {{0.95f, 0.95f, 0.97f, 1.0f}}}};
        int32_t x0 = x < 0 ? 0 : x;
        int32_t x1 = x + (int32_t)bar > (int32_t)width ? (int32_t)width : x + (int32_t)bar;
        if (x1 > x0) {
            VkClearRect rect = {.rect = {{x0, 0}, {(uint32_t)(x1 - x0), height}}, .layerCount = 1};
            vkCmdClearAttachments(cmd, 1, &white, 1, &rect);
        }
        /* Frame counter strip: one block per frame mod 60, to spot drops. */
        uint32_t block = width / 60;
        VkClearRect counter = {.rect = {{(int32_t)((frame % 60) * block), (int32_t)(height - height / 20)},
                                        {block, height / 20}},
                               .layerCount = 1};
        VkClearAttachment accent = {.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT, .colorAttachment = 0,
                                    .clearValue = {.color = {{0.51f, 0.65f, 0.91f, 1.0f}}}};
        vkCmdClearAttachments(cmd, 1, &accent, 1, &counter);
        vkCmdEndRenderPass(cmd);

        /* Release back to the app. */
        VkImageMemoryBarrier release = {.sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER,
                                        .srcAccessMask = VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT,
                                        .oldLayout = VK_IMAGE_LAYOUT_GENERAL, .newLayout = VK_IMAGE_LAYOUT_GENERAL,
                                        .srcQueueFamilyIndex = 0, .dstQueueFamilyIndex = VK_QUEUE_FAMILY_FOREIGN_EXT,
                                        .image = s->image,
                                        .subresourceRange = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1}};
        vkCmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT, VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT,
                             0, 0, NULL, 0, NULL, 1, &release);
        CHECK(vkEndCommandBuffer(cmd));

        double submit_start = now_seconds();
        VkSubmitInfo si = {.sType = VK_STRUCTURE_TYPE_SUBMIT_INFO, .commandBufferCount = 1, .pCommandBuffers = &cmd};
        CHECK(vkQueueSubmit(queue, 1, &si, fence));
        /* Spike: CPU wait. The real path will hand a sync_file to the app. */
        CHECK(vkWaitForFences(device, 1, &fence, VK_TRUE, UINT64_MAX));
        CHECK(vkResetFences(device, 1, &fence));
        gpu_time += now_seconds() - submit_start;

        s->busy = 1;
        struct dn_message present = {.type = DN_MSG_PRESENT, .index = (uint32_t)index, .frame = frame};
        send_message(sock, &present, sizeof(present));
        frame++;
        frames_since_report++;

        double now = now_seconds();
        if (now - last_report >= 1.0) {
            printf("fps %.1f, gpu+wait %.2f ms/frame\n", frames_since_report / (now - last_report),
                   gpu_time * 1000 / frames_since_report);
            fflush(stdout);
            last_report = now;
            frames_since_report = 0;
            gpu_time = 0;
        }
    }
}
