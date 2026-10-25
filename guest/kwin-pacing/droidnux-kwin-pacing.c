/*
 * KWin's render time prediction, preloaded into the Plasma session.
 *
 * KWin starts compositing a frame its predicted render time (plus 1 ms)
 * before the vblank it targets. The prediction (RenderJournal) is a running
 * mean plus a "variance" that jumps to any spike and decays over ~6 s: one
 * slow frame (a new window's first texture, a shader compiled) keeps it at
 * 9-13 ms for seconds, while frames take 2-3 ms here. Above one vblank KWin
 * then works two frames ahead (triple buffering), and a client that draws
 * after its frame callback (Firefox, any Vulkan or EGL app) misses the next
 * frame: clients get half the refresh rate, 60 fps of 120.
 *
 * This library replaces RenderJournal::add() and result(): the prediction is
 * the 90th percentile of the last 64 render times (half a second at 120 Hz),
 * so a few spikes cost a frame each instead of halving the rate for seconds.
 * KWin calls both through the PLT (libkwin is not linked -Bsymbolic), so the
 * preloaded definitions win; other processes never call them.
 *
 * Compiled with the NDK but loaded by glibc, like guest/paths: it calls only
 * glibc's dlsym.
 */

#include <stddef.h>
#include <stdint.h>

#define API __attribute__((visibility("default")))
#define RTLD_NEXT ((void*) -1l)
#define SAMPLES 64
#define JOURNALS 8

void* dlsym(void* handle, const char* name);

/* One per RenderLoop (one per output): keyed by the RenderJournal's address. */
static struct journal {
    const void* self;
    int64_t times[SAMPLES];
    int count, next;
} journals[JOURNALS];

static struct journal* find(const void* self, int create) {
    for (int i = 0; i < JOURNALS; i++) {
        if (journals[i].self == self) return &journals[i];
    }
    if (!create) return NULL;
    for (int i = 0; i < JOURNALS; i++) {
        if (!journals[i].self) {
            journals[i].self = self;
            return &journals[i];
        }
    }
    /* More outputs than slots: reuse the first (only a worse prediction). */
    journals[0] = (struct journal){.self = self};
    return &journals[0];
}

/* KWin::RenderJournal::add(std::chrono::nanoseconds renderTime, std::chrono::nanoseconds presentationTimestamp) */
API void _ZN4KWin13RenderJournal3addENSt6chrono8durationIlSt5ratioILl1ELl1000000000EEEES5_(void* self, int64_t time,
                                                                                            int64_t when) {
    static void (*original)(void*, int64_t, int64_t);
    if (!original)
        original = (void (*)(void*, int64_t, int64_t)) dlsym(
            RTLD_NEXT, "_ZN4KWin13RenderJournal3addENSt6chrono8durationIlSt5ratioILl1ELl1000000000EEEES5_");
    if (original) original(self, time, when);
    struct journal* j = find(self, 1);
    j->times[j->next] = time > 0 ? time : 0;
    j->next = (j->next + 1) % SAMPLES;
    if (j->count < SAMPLES) j->count++;
}

/* KWin::RenderJournal::result() const */
API int64_t _ZNK4KWin13RenderJournal6resultEv(const void* self) {
    struct journal* j = find(self, 0);
    if (!j || j->count < 8) {
        static int64_t (*original)(const void*);
        if (!original) original = (int64_t (*)(const void*)) dlsym(RTLD_NEXT, "_ZNK4KWin13RenderJournal6resultEv");
        return original ? original(self) : 0;
    }
    /* Insertion sort of a copy: 64 values, once per frame. */
    int64_t sorted[SAMPLES];
    for (int i = 0; i < j->count; i++) {
        int64_t v = j->times[i];
        int k = i;
        for (; k > 0 && sorted[k - 1] > v; k--) sorted[k] = sorted[k - 1];
        sorted[k] = v;
    }
    return sorted[(j->count * 9) / 10];
}
