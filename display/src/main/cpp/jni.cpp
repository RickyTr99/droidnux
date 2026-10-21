#ifndef DROIDNUX_HAVE_X11
#define DROIDNUX_HAVE_X11 0
#endif

#include <android/native_window_jni.h>
#include <jni.h>

#include <cstring>
#include <string>
#include <vector>

#include "buffer_spike.h"
#include "probe.h"

using droidnux::display::BufferSpike;

extern "C" JNIEXPORT jstring JNICALL
Java_app_droidnux_display_DisplayCore_nativeProbe(JNIEnv* env, jobject /* this */) {
    return env->NewStringUTF(droidnux::display::probe().c_str());
}

extern "C" JNIEXPORT jlong JNICALL
Java_app_droidnux_display_BufferSpike_nativeStart(JNIEnv* env, jclass, jobject surface, jint width, jint height,
                                                 jint buffers) {
    ANativeWindow* window = ANativeWindow_fromSurface(env, surface);
    if (window == nullptr) return 0;
    auto* spike = new BufferSpike(window, width, height, buffers);
    ANativeWindow_release(window);  // BufferSpike holds its own reference
    if (!spike->ok()) {
        env->ThrowNew(env->FindClass("java/lang/IllegalStateException"), spike->error().c_str());
        delete spike;
        return 0;
    }
    return reinterpret_cast<jlong>(spike);
}

extern "C" JNIEXPORT jstring JNICALL
Java_app_droidnux_display_BufferSpike_nativeStats(JNIEnv* env, jclass, jlong handle) {
    return env->NewStringUTF(reinterpret_cast<BufferSpike*>(handle)->stats().c_str());
}

extern "C" JNIEXPORT void JNICALL
Java_app_droidnux_display_BufferSpike_nativeStop(JNIEnv*, jclass, jlong handle) {
    delete reinterpret_cast<BufferSpike*>(handle);
}

// -- Audio focus -------------------------------------------------------------

#include "audio_output.h"

namespace {
JavaVM* audioVm;
jclass audioFocusClass;
jmethodID audioPlayingMethod;

// On a sink's thread, which ends with its connection: attach only for the call.
void onAudioPlaying(bool playing) {
    JNIEnv* env = nullptr;
    bool attached = false;
    if (audioVm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK) {
        if (audioVm->AttachCurrentThread(&env, nullptr) != JNI_OK) return;
        attached = true;
    }
    env->CallStaticVoidMethod(audioFocusClass, audioPlayingMethod, playing ? JNI_TRUE : JNI_FALSE);
    if (env->ExceptionCheck()) env->ExceptionClear();
    if (attached) audioVm->DetachCurrentThread();
}
}  // namespace

extern "C" JNIEXPORT void JNICALL
Java_app_droidnux_display_AudioFocus_nativeInit(JNIEnv* env, jclass clazz) {
    if (audioVm != nullptr) return;
    env->GetJavaVM(&audioVm);
    audioFocusClass = static_cast<jclass>(env->NewGlobalRef(clazz));
    audioPlayingMethod = env->GetStaticMethodID(clazz, "onPlaying", "(Z)V");
    dnx_audio_output_set_playing_callback(onAudioPlaying);
}

extern "C" JNIEXPORT void JNICALL
Java_app_droidnux_display_AudioFocus_nativeSetMuted(JNIEnv*, jclass, jboolean muted) {
    dnx_audio_output_set_muted(muted);
}

// -- Presenter ---------------------------------------------------------------

#include "presenter.h"

extern "C" JNIEXPORT void JNICALL
Java_app_droidnux_display_Presenter_nativeAttach(JNIEnv* env, jclass, jobject surface, jfloat maxRefreshRate) {
    ANativeWindow* window = ANativeWindow_fromSurface(env, surface);
    if (window == nullptr) return;
    dnx_presenter_attach(window, maxRefreshRate);
    ANativeWindow_release(window);  // the presenter holds its own reference
}

extern "C" JNIEXPORT void JNICALL
Java_app_droidnux_display_Presenter_nativeDetach(JNIEnv*, jclass) {
    dnx_presenter_detach();
}

extern "C" JNIEXPORT void JNICALL
Java_app_droidnux_display_Presenter_nativeSetCursorVisible(JNIEnv*, jclass, jboolean visible) {
    dnx_presenter_set_cursor_visible(visible);
}

extern "C" JNIEXPORT jint JNICALL
Java_app_droidnux_display_Presenter_nativeCursorY(JNIEnv*, jclass) {
    return dnx_presenter_cursor_y();
}

extern "C" JNIEXPORT jlong JNICALL
Java_app_droidnux_display_Presenter_nativeFrameCount(JNIEnv*, jclass) {
    return static_cast<jlong>(dnx_presenter_frame_count());
}

extern "C" JNIEXPORT jlong JNICALL
Java_app_droidnux_display_Presenter_nativeFrameTimeTotal(JNIEnv*, jclass) {
    return static_cast<jlong>(dnx_presenter_frame_time_total_ns());
}

extern "C" JNIEXPORT jlong JNICALL
Java_app_droidnux_display_Presenter_nativeTakeFrameTimeMax(JNIEnv*, jclass) {
    return static_cast<jlong>(dnx_presenter_take_frame_time_max_ns());
}

// -- Display server: X11 or Wayland front-end ------------------------------

#include <atomic>

#include "dnx_wl.h"
#if DROIDNUX_HAVE_X11
#include "dnx_x11.h"
#define DNX_X11_CALL(fn, ...) dnx_x11_##fn(__VA_ARGS__)
#else
#define DNX_X11_CALL(fn, ...) ((void) 0)
#endif

namespace {
// Which front-end DisplayServer started; set once, before any input.
std::atomic<bool> wayland{false};
}  // namespace

// The same call to whichever front-end runs.
#define DNX_DISPLAY_CALL(fn, ...)                    \
    do {                                             \
        if (wayland) {                               \
            dnx_wl_##fn(__VA_ARGS__);                \
        } else {                                     \
            DNX_X11_CALL(fn, __VA_ARGS__);           \
        }                                            \
    } while (0)

extern "C" JNIEXPORT jboolean JNICALL
Java_app_droidnux_display_DisplayServer_nativeAvailable(JNIEnv*, jclass) {
    return DROIDNUX_HAVE_X11 ? JNI_TRUE : JNI_FALSE;
}

namespace {
struct JniString {
    JNIEnv* env;
    jstring string;
    const char* chars;
    JniString(JNIEnv* e, jstring s) : env(e), string(s), chars(s ? e->GetStringUTFChars(s, nullptr) : nullptr) {}
    ~JniString() {
        if (chars) env->ReleaseStringUTFChars(string, chars);
    }
};
}  // namespace

namespace {
// Text fields of the nested KWin gaining or losing the focus go to
// DisplayServer.textInputFromWayland, on the compositor thread.
JavaVM* textInputVm;
jclass textInputClass;
jmethodID textInputMethod;

void onTextInput(bool active) {
    JNIEnv* env = nullptr;
    // The compositor thread lives as long as the process: attach it once.
    if (textInputVm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK &&
        textInputVm->AttachCurrentThread(&env, nullptr) != JNI_OK) {
        return;
    }
    env->CallStaticVoidMethod(textInputClass, textInputMethod, active ? JNI_TRUE : JNI_FALSE);
    if (env->ExceptionCheck()) env->ExceptionClear();
}
}  // namespace

extern "C" JNIEXPORT jboolean JNICALL
Java_app_droidnux_display_DisplayServer_nativeStartWayland(JNIEnv* env, jclass clazz, jstring tmpDir, jint display,
                                                           jint width, jint height) {
    JniString tmp(env, tmpDir);
    if (textInputVm == nullptr) {
        env->GetJavaVM(&textInputVm);
        textInputClass = static_cast<jclass>(env->NewGlobalRef(clazz));
        textInputMethod = env->GetStaticMethodID(clazz, "textInputFromWayland", "(Z)V");
        dnx_wl_set_text_input_callback(onTextInput);
    }
    dnx_wl_config config{tmp.chars, display, width, height};
    if (!dnx_wl_start(&config)) return JNI_FALSE;
    wayland = true;
    return JNI_TRUE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_app_droidnux_display_DisplayServer_nativeRunning(JNIEnv*, jclass) {
    if (wayland) return dnx_wl_running() ? JNI_TRUE : JNI_FALSE;
#if DROIDNUX_HAVE_X11
    return dnx_x11_running() ? JNI_TRUE : JNI_FALSE;
#else
    return JNI_FALSE;
#endif
}

extern "C" JNIEXPORT void JNICALL
Java_app_droidnux_display_DisplayServer_nativeResize(JNIEnv*, jclass, jint width, jint height) {
    DNX_DISPLAY_CALL(resize, width, height);
}

extern "C" JNIEXPORT void JNICALL
Java_app_droidnux_display_DisplayServer_nativeRedraw(JNIEnv*, jclass) {
    DNX_DISPLAY_CALL(redraw);
}

extern "C" JNIEXPORT void JNICALL
Java_app_droidnux_display_DisplayServer_nativeKey(JNIEnv*, jclass, jint keycode, jboolean down) {
    DNX_DISPLAY_CALL(key, keycode, down);
}

extern "C" JNIEXPORT void JNICALL
Java_app_droidnux_display_DisplayServer_nativeText(JNIEnv*, jclass, jint codepoint) {
    if (!wayland) DNX_X11_CALL(text, static_cast<uint32_t>(codepoint));
}

extern "C" JNIEXPORT void JNICALL
Java_app_droidnux_display_DisplayServer_nativePointer(JNIEnv*, jclass, jfloat x, jfloat y) {
    DNX_DISPLAY_CALL(pointer_move, x, y);
}

extern "C" JNIEXPORT void JNICALL
Java_app_droidnux_display_DisplayServer_nativePointerRelative(JNIEnv*, jclass, jfloat dx, jfloat dy) {
    DNX_DISPLAY_CALL(pointer_move_relative, dx, dy);
}

extern "C" JNIEXPORT void JNICALL
Java_app_droidnux_display_DisplayServer_nativeButton(JNIEnv*, jclass, jint button, jboolean down) {
    DNX_DISPLAY_CALL(button, button, down);
}

extern "C" JNIEXPORT void JNICALL
Java_app_droidnux_display_DisplayServer_nativeScroll(JNIEnv*, jclass, jfloat dx, jfloat dy) {
    DNX_DISPLAY_CALL(scroll, dx, dy);
}

// Wayland only: soft keyboard text through KWin's input method, touch, scale.

namespace {
// The UTF-8 bytes (Java strings in JNI are modified UTF-8) as a C string.
std::string utf8String(JNIEnv* env, jbyteArray bytes) {
    std::string s(static_cast<size_t>(env->GetArrayLength(bytes)), '\0');
    env->GetByteArrayRegion(bytes, 0, static_cast<jsize>(s.size()), reinterpret_cast<jbyte*>(s.data()));
    return s;
}
}  // namespace

extern "C" JNIEXPORT void JNICALL
Java_app_droidnux_display_DisplayServer_nativeCommitText(JNIEnv* env, jclass, jbyteArray utf8) {
    if (wayland) dnx_wl_commit_text(utf8String(env, utf8).c_str());
}

extern "C" JNIEXPORT void JNICALL
Java_app_droidnux_display_DisplayServer_nativePreeditText(JNIEnv* env, jclass, jbyteArray utf8) {
    if (wayland) dnx_wl_preedit_text(utf8String(env, utf8).c_str());
}

extern "C" JNIEXPORT void JNICALL
Java_app_droidnux_display_DisplayServer_nativeTouch(JNIEnv*, jclass, jint type, jint id, jfloat x, jfloat y) {
    if (!wayland) return;
    // DisplayServer.TOUCH_*
    switch (type) {
    case 0: dnx_wl_touch_down(id, x, y); break;
    case 1: dnx_wl_touch_motion(id, x, y); break;
    case 2: dnx_wl_touch_up(id); break;
    case 3: dnx_wl_touch_frame(); break;
    case 4: dnx_wl_touch_cancel(); break;
    default: break;
    }
}

extern "C" JNIEXPORT void JNICALL
Java_app_droidnux_display_DisplayServer_nativeSetScale(JNIEnv*, jclass, jfloat scale) {
    if (wayland) dnx_wl_set_scale(scale);
}

// Snapshot and clipboard: X11 only so far.

#if DROIDNUX_HAVE_X11

namespace {
// X clients' copies go to DisplayServer.clipboardFromX, on the X server thread.
JavaVM* clipboardVm;
jclass clipboardClass;
jmethodID clipboardMethod;

void onClipboardFromX(const char* utf8) {
    JNIEnv* env = nullptr;
    // The X server thread lives as long as the process: attach it once.
    if (clipboardVm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK &&
        clipboardVm->AttachCurrentThread(&env, nullptr) != JNI_OK) {
        return;
    }
    auto length = static_cast<jsize>(strlen(utf8));
    jbyteArray bytes = env->NewByteArray(length);
    if (bytes == nullptr) return;
    env->SetByteArrayRegion(bytes, 0, length, reinterpret_cast<const jbyte*>(utf8));
    env->CallStaticVoidMethod(clipboardClass, clipboardMethod, bytes);
    if (env->ExceptionCheck()) env->ExceptionClear();
    env->DeleteLocalRef(bytes);
}
}  // namespace

extern "C" JNIEXPORT jboolean JNICALL
Java_app_droidnux_display_DisplayServer_nativeStart(JNIEnv* env, jclass clazz, jstring tmpDir, jstring xkbDir,
                                                    jstring fontDir, jint display, jint width, jint height, jint dpi) {
    JniString tmp(env, tmpDir), xkb(env, xkbDir), fonts(env, fontDir);
    if (clipboardVm == nullptr) {
        env->GetJavaVM(&clipboardVm);
        clipboardClass = static_cast<jclass>(env->NewGlobalRef(clazz));
        clipboardMethod = env->GetStaticMethodID(clazz, "clipboardFromX", "([B)V");
        dnx_x11_set_clipboard_callback(onClipboardFromX);
    }
    dnx_x11_config config{tmp.chars, xkb.chars, fonts.chars, display, width, height, dpi};
    return dnx_x11_start(&config) ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jintArray JNICALL
Java_app_droidnux_display_DisplayServer_nativeSnapshot(JNIEnv* env, jclass, jint width, jint height) {
    if (wayland || width <= 0 || height <= 0 || width > 4096 || height > 4096) return nullptr;
    std::vector<uint32_t> pixels(static_cast<size_t>(width) * height);
    if (!dnx_x11_snapshot(pixels.data(), width, height)) return nullptr;
    jintArray result = env->NewIntArray(width * height);
    if (result != nullptr) env->SetIntArrayRegion(result, 0, width * height, reinterpret_cast<const jint*>(pixels.data()));
    return result;
}

extern "C" JNIEXPORT void JNICALL
Java_app_droidnux_display_DisplayServer_nativeSetClipboard(JNIEnv* env, jclass, jbyteArray utf8) {
    if (wayland) return;
    jsize length = env->GetArrayLength(utf8);
    jbyte* bytes = env->GetByteArrayElements(utf8, nullptr);
    if (bytes == nullptr) return;
    dnx_x11_set_clipboard(reinterpret_cast<const char*>(bytes), static_cast<size_t>(length));
    env->ReleaseByteArrayElements(utf8, bytes, JNI_ABORT);
}

#else  // Wayland only: the X11 entry points answer "nothing".

extern "C" JNIEXPORT jboolean JNICALL
Java_app_droidnux_display_DisplayServer_nativeStart(JNIEnv*, jclass, jstring, jstring, jstring, jint, jint, jint, jint) {
    return JNI_FALSE;
}

extern "C" JNIEXPORT jintArray JNICALL
Java_app_droidnux_display_DisplayServer_nativeSnapshot(JNIEnv*, jclass, jint, jint) {
    return nullptr;
}

extern "C" JNIEXPORT void JNICALL
Java_app_droidnux_display_DisplayServer_nativeSetClipboard(JNIEnv*, jclass, jbyteArray) {}

#endif  // DROIDNUX_HAVE_X11
