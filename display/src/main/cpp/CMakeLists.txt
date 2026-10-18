# Native presentation core: buffers, frame pacing and final composition shared
# by every protocol front-end (X11 first, Wayland later). See PLAN.md.

cmake_minimum_required(VERSION 3.22)
project(droidnux_display C CXX)

set(GUEST_DIR ${CMAKE_CURRENT_LIST_DIR}/../../../../guest)

add_library(droidnux-display SHARED
    core/presenter.cpp
    buffer_spike.cpp
    jni.cpp
    probe.cpp)
target_include_directories(droidnux-display PRIVATE ${GUEST_DIR}/buffer-spike core)
target_compile_options(droidnux-display PRIVATE
    "$<$<COMPILE_LANGUAGE:CXX>:-Wall;-Wextra;-Werror>" -fvisibility=hidden)
# Newer-than-minSdk APIs are guarded with __builtin_available.
target_compile_definitions(droidnux-display PRIVATE __ANDROID_UNAVAILABLE_SYMBOLS_ARE_WEAK__)
target_link_libraries(droidnux-display PRIVATE android log nativewindow EGL dl)

# X11 front-end (X server from Termux:X11 with our own DDX), see x11/x11.cmake.
include(x11/x11.cmake)
