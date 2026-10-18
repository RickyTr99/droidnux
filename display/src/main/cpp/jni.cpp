#include <android/native_window_jni.h>
#include <jni.h>

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
