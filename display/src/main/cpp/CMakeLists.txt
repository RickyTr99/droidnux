# Native presentation core: buffers, frame pacing and final composition shared
# by every protocol front-end (X11 first, Wayland later). See PLAN.md.

cmake_minimum_required(VERSION 3.22)
project(droidnux_display CXX)

set(GUEST_DIR ${CMAKE_CURRENT_LIST_DIR}/../../../../guest)

add_library(droidnux-display SHARED
    buffer_spike.cpp
    jni.cpp
    probe.cpp)
target_include_directories(droidnux-display PRIVATE ${GUEST_DIR}/buffer-spike)
target_compile_options(droidnux-display PRIVATE -Wall -Wextra -Werror -fvisibility=hidden)
target_link_libraries(droidnux-display PRIVATE android log nativewindow EGL dl)
