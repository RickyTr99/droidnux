#ifndef DROIDNUX_HAVE_X11
#define DROIDNUX_HAVE_X11 0
#endif

#include <android/native_window_jni.h>
#include <jni.h>

#include <cstring>

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

// -- X11 front-end -----------------------------------------------------------

#if DROIDNUX_HAVE_X11
#include "dnx_x11.h"
#endif

extern "C" JNIEXPORT jboolean JNICALL
Java_app_droidnux_display_X11Server_nativeAvailable(JNIEnv*, jclass) {
    return DROIDNUX_HAVE_X11 ? JNI_TRUE : JNI_FALSE;
}

#if DROIDNUX_HAVE_X11

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

// X clients' copies go to X11Server.clipboardFromX, on the X server thread.
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
Java_app_droidnux_display_X11Server_nativeStart(JNIEnv* env, jclass clazz, jstring tmpDir, jstring xkbDir, jstring fontDir,
                                                jint display, jint width, jint height, jint dpi) {
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

extern "C" JNIEXPORT jboolean JNICALL
Java_app_droidnux_display_X11Server_nativeRunning(JNIEnv*, jclass) {
    return dnx_x11_running() ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT void JNICALL
Java_app_droidnux_display_X11Server_nativeResize(JNIEnv*, jclass, jint width, jint height) {
    dnx_x11_resize(width, height);
}

extern "C" JNIEXPORT void JNICALL
Java_app_droidnux_display_X11Server_nativeRedraw(JNIEnv*, jclass) {
    dnx_x11_redraw();
}

extern "C" JNIEXPORT void JNICALL
Java_app_droidnux_display_X11Server_nativeKey(JNIEnv*, jclass, jint keycode, jboolean down) {
    dnx_x11_key(keycode, down);
}

extern "C" JNIEXPORT void JNICALL
Java_app_droidnux_display_X11Server_nativeText(JNIEnv*, jclass, jint codepoint) {
    dnx_x11_text(static_cast<uint32_t>(codepoint));
}

extern "C" JNIEXPORT void JNICALL
Java_app_droidnux_display_X11Server_nativePointer(JNIEnv*, jclass, jfloat x, jfloat y) {
    dnx_x11_pointer_move(x, y);
}

extern "C" JNIEXPORT void JNICALL
Java_app_droidnux_display_X11Server_nativePointerRelative(JNIEnv*, jclass, jfloat dx, jfloat dy) {
    dnx_x11_pointer_move_relative(dx, dy);
}

extern "C" JNIEXPORT void JNICALL
Java_app_droidnux_display_X11Server_nativeButton(JNIEnv*, jclass, jint button, jboolean down) {
    dnx_x11_button(button, down);
}

extern "C" JNIEXPORT void JNICALL
Java_app_droidnux_display_X11Server_nativeScroll(JNIEnv*, jclass, jfloat dx, jfloat dy) {
    dnx_x11_scroll(dx, dy);
}

extern "C" JNIEXPORT void JNICALL
Java_app_droidnux_display_X11Server_nativeSetClipboard(JNIEnv* env, jclass, jbyteArray utf8) {
    jsize length = env->GetArrayLength(utf8);
    jbyte* bytes = env->GetByteArrayElements(utf8, nullptr);
    if (bytes == nullptr) return;
    dnx_x11_set_clipboard(reinterpret_cast<const char*>(bytes), static_cast<size_t>(length));
    env->ReleaseByteArrayElements(utf8, bytes, JNI_ABORT);
}

#endif  // DROIDNUX_HAVE_X11
