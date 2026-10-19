# Native presentation core: buffers, frame pacing and final composition shared
# by every protocol front-end (X11 first, Wayland later). See PLAN.md.

cmake_minimum_required(VERSION 3.22)
project(droidnux_display C CXX)

set(GUEST_DIR ${CMAKE_CURRENT_LIST_DIR}/../../../../guest)

add_library(droidnux-display SHARED
    core/allocator.cpp
    core/gpu_copy.c
    core/presenter.cpp
    buffer_spike.cpp
    jni.cpp
    probe.cpp)
target_include_directories(droidnux-display PRIVATE ${GUEST_DIR}/buffer-spike core)
target_compile_options(droidnux-display PRIVATE
    "$<$<COMPILE_LANGUAGE:CXX>:-Wall;-Wextra;-Werror>" -fvisibility=hidden)
# Newer-than-minSdk APIs are guarded with __builtin_available.
target_compile_definitions(droidnux-display PRIVATE __ANDROID_UNAVAILABLE_SYMBOLS_ARE_WEAK__)
target_link_libraries(droidnux-display PRIVATE android log nativewindow EGL GLESv3 dl)

# Preload library for the desktop session in the container: MIT-SHM segments
# the X server can attach (shm/dnx_shm.c). It runs under glibc, so it links to
# nothing from the NDK: its few libc symbols are resolved by glibc at load time.
add_library(droidnux-guest-shm SHARED shm/dnx_shm.c)
target_compile_definitions(droidnux-guest-shm PRIVATE DNX_SHM_GUEST=1)
target_compile_options(droidnux-guest-shm PRIVATE
    -Wall -Wextra -Werror -ffreestanding -fno-builtin -fno-stack-protector -mno-outline-atomics
    -fno-asynchronous-unwind-tables -fvisibility=hidden)
target_link_options(droidnux-guest-shm PRIVATE
    -nostdlib -Wl,--as-needed -Wl,-z,undefs -Wl,--pack-dyn-relocs=none -Wl,--hash-style=both)

# X11 front-end (X server from Termux:X11 with our own DDX), see x11/x11.cmake.
include(x11/x11.cmake)
