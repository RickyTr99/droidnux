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

// -- Audio volume ------------------------------------------------------------

namespace {
jclass audioVolumeClass;
jmethodID audioVolumeMethod;

// On a sink's thread, as onAudioPlaying.
void onAudioVolume(float volume, bool mute) {
    JNIEnv* env = nullptr;
    bool attached = false;
    if (audioVm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK) {
        if (audioVm->AttachCurrentThread(&env, nullptr) != JNI_OK) return;
        attached = true;
    }
    env->CallStaticVoidMethod(audioVolumeClass, audioVolumeMethod, volume, mute ? JNI_TRUE : JNI_FALSE);
    if (env->ExceptionCheck()) env->ExceptionClear();
    if (attached) audioVm->DetachCurrentThread();
}
}  // namespace

extern "C" JNIEXPORT void JNICALL
Java_app_droidnux_display_AudioVolume_nativeInit(JNIEnv* env, jclass clazz) {
    if (audioVolumeClass != nullptr) return;
    if (audioVm == nullptr) env->GetJavaVM(&audioVm);
    audioVolumeClass = static_cast<jclass>(env->NewGlobalRef(clazz));
    audioVolumeMethod = env->GetStaticMethodID(clazz, "onDesktopVolume", "(FZ)V");
    dnx_audio_output_set_volume_callback(onAudioVolume);
}

extern "C" JNIEXPORT void JNICALL
Java_app_droidnux_display_AudioVolume_nativeSetVolume(JNIEnv*, jclass, jfloat volume, jboolean mute) {
    dnx_audio_output_set_volume(volume, mute);
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

// -- Display server: the Wayland front-end ------------------------------------

#include "dnx_wl.h"

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

namespace {
// Text copied in the desktop goes to DisplayServer.clipboardFromDesktop, on
// the compositor thread.
JavaVM* clipboardVm;
jclass clipboardClass;
jmethodID clipboardMethod;

void onClipboard(const char* utf8) {
    JNIEnv* env = nullptr;
    // The compositor thread lives as long as the process: attach it once.
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

void initClipboard(JNIEnv* env, jclass clazz) {
    if (clipboardVm != nullptr) return;
    env->GetJavaVM(&clipboardVm);
    clipboardClass = static_cast<jclass>(env->NewGlobalRef(clazz));
    clipboardMethod = env->GetStaticMethodID(clazz, "clipboardFromDesktop", "([B)V");
}
}  // namespace

extern "C" JNIEXPORT jboolean JNICALL
Java_app_droidnux_display_DisplayServer_nativeStart(JNIEnv* env, jclass clazz, jstring tmpDir, jint display,
                                                    jint width, jint height) {
    JniString tmp(env, tmpDir);
    if (textInputVm == nullptr) {
        env->GetJavaVM(&textInputVm);
        textInputClass = static_cast<jclass>(env->NewGlobalRef(clazz));
        textInputMethod = env->GetStaticMethodID(clazz, "textInputFromWayland", "(Z)V");
        dnx_wl_set_text_input_callback(onTextInput);
    }
    initClipboard(env, clazz);
    dnx_wl_set_clipboard_callback(onClipboard);
    dnx_wl_config config{tmp.chars, display, width, height};
    return dnx_wl_start(&config) ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_app_droidnux_display_DisplayServer_nativeRunning(JNIEnv*, jclass) {
    return dnx_wl_running() ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT void JNICALL
Java_app_droidnux_display_DisplayServer_nativeResize(JNIEnv*, jclass, jint width, jint height) {
    dnx_wl_resize(width, height);
}

extern "C" JNIEXPORT void JNICALL
Java_app_droidnux_display_DisplayServer_nativeRedraw(JNIEnv*, jclass) {
    dnx_wl_redraw();
}

extern "C" JNIEXPORT void JNICALL
Java_app_droidnux_display_DisplayServer_nativeKey(JNIEnv*, jclass, jint keycode, jboolean down) {
    dnx_wl_key(keycode, down);
}

extern "C" JNIEXPORT void JNICALL
Java_app_droidnux_display_DisplayServer_nativePointer(JNIEnv*, jclass, jfloat x, jfloat y) {
    dnx_wl_pointer_move(x, y);
}

extern "C" JNIEXPORT void JNICALL
Java_app_droidnux_display_DisplayServer_nativePointerRelative(JNIEnv*, jclass, jfloat dx, jfloat dy) {
    dnx_wl_pointer_move_relative(dx, dy);
}

extern "C" JNIEXPORT void JNICALL
Java_app_droidnux_display_DisplayServer_nativeButton(JNIEnv*, jclass, jint button, jboolean down) {
    dnx_wl_button(button, down);
}

extern "C" JNIEXPORT void JNICALL
Java_app_droidnux_display_DisplayServer_nativeScroll(JNIEnv*, jclass, jfloat dx, jfloat dy) {
    dnx_wl_scroll(dx, dy);
}

// Soft keyboard text through KWin's input method, touch, scale, clipboard.

namespace {
// The UTF-8 bytes (Java strings in JNI are modified UTF-8) as a C string.
std::string utf8String(JNIEnv* env, jbyteArray bytes) {
    std::string s(static_cast<size_t>(env->GetArrayLength(bytes)), '\0');
    env->GetByteArrayRegion(bytes, 0, static_cast<jsize>(s.size()), reinterpret_cast<jbyte*>(s.data()));
    return s;
}
}  // namespace

extern "C" JNIEXPORT void JNICALL
Java_app_droidnux_display_DisplayServer_nativeSetClipboard(JNIEnv* env, jclass, jbyteArray utf8) {
    dnx_wl_set_clipboard(utf8String(env, utf8).c_str());
}

extern "C" JNIEXPORT void JNICALL
Java_app_droidnux_display_DisplayServer_nativeCommitText(JNIEnv* env, jclass, jbyteArray utf8) {
    dnx_wl_commit_text(utf8String(env, utf8).c_str());
}

extern "C" JNIEXPORT void JNICALL
Java_app_droidnux_display_DisplayServer_nativePreeditText(JNIEnv* env, jclass, jbyteArray utf8) {
    dnx_wl_preedit_text(utf8String(env, utf8).c_str());
}

extern "C" JNIEXPORT void JNICALL
Java_app_droidnux_display_DisplayServer_nativeTouch(JNIEnv*, jclass, jint type, jint id, jfloat x, jfloat y) {
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
    dnx_wl_set_scale(scale);
}

extern "C" JNIEXPORT jintArray JNICALL
Java_app_droidnux_display_DisplayServer_nativeSnapshot(JNIEnv* env, jclass, jint width, jint height) {
    if (width <= 0 || height <= 0 || width > 4096 || height > 4096) return nullptr;
    std::vector<uint32_t> pixels(static_cast<size_t>(width) * height);
    if (!dnx_wl_snapshot(pixels.data(), width, height)) return nullptr;
    jintArray result = env->NewIntArray(width * height);
    if (result != nullptr) env->SetIntArrayRegion(result, 0, width * height, reinterpret_cast<const jint*>(pixels.data()));
    return result;
}
