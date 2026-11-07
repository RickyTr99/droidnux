/*
 * Qt animations at the display's rate, preloaded into the Plasma session
 * with the KWin library (droidnux-kwin-pacing.c).
 *
 * Qt drives the animations of a thread (QPropertyAnimation, the smooth
 * scrolling of Dolphin and of every QtWidgets list, Qt Quick's basic render
 * loop) with a 16 ms timer: about 62 frames per second on a 120 Hz screen,
 * the jerky half rate users see in KDE apps with the wheel and the keyboard.
 * QUnifiedTimer::setTimingInterval() is the only knob and nothing sets it, so
 * this library sets 8 ms on the main thread when the application enters its
 * event loop. Apps call exec() through the PLT, so the preloaded definitions
 * win; they forward to Qt's. Qt still paints only on the compositor's frame
 * callbacks, so a 60 Hz screen costs only the extra animation steps.
 *
 * Compiled with the NDK but loaded by glibc, like the KWin library: it calls
 * only glibc's dlsym, and processes without Qt never call it.
 */

#define API __attribute__((visibility("default")))
#define RTLD_NEXT ((void*) -1l)
#define TIMING_INTERVAL_MS 8

void* dlsym(void* handle, const char* name);

static void set_timing_interval(void) {
    static void* (*instance)(void);
    static void (*set_interval)(void*, int);
    if (!instance) {
        instance = (void* (*)(void)) dlsym(RTLD_NEXT, "_ZN13QUnifiedTimer8instanceEv");
        set_interval = (void (*)(void*, int)) dlsym(RTLD_NEXT, "_ZN13QUnifiedTimer17setTimingIntervalEi");
    }
    if (!instance || !set_interval) return;
    void* timer = instance();
    if (timer) set_interval(timer, TIMING_INTERVAL_MS);
}

/* The three nest (QApplication's calls QGuiApplication's), so the interval is
 * set more than once: harmless, it is a plain assignment. */
#define EXEC(symbol)                                                       \
    API int symbol(void) {                                                 \
        static int (*original)(void);                                      \
        if (!original) original = (int (*)(void)) dlsym(RTLD_NEXT, #symbol); \
        set_timing_interval();                                             \
        return original ? original() : -1;                                 \
    }

EXEC(_ZN16QCoreApplication4execEv) /* QCoreApplication::exec() */
EXEC(_ZN15QGuiApplication4execEv)  /* QGuiApplication::exec() */
EXEC(_ZN12QApplication4execEv)     /* QApplication::exec() */
