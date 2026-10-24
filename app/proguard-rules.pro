# JNI entry points of the native modules.
-keepclasseswithmembernames class * {
    native <methods>;
}

# Called by name from native code.
-keepclassmembers class app.droidnux.display.AudioFocus { void onPlaying(boolean); }
-keepclassmembers class app.droidnux.display.AudioVolume { void onDesktopVolume(float, boolean); }
-keepclassmembers class app.droidnux.display.DisplayServer {
    void textInputFromWayland(boolean);
    void clipboardFromDesktop(byte[]);
}
