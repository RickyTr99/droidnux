# Native presentation core (buffers, frame pacing, final composition), the
# Wayland front-end on top of it, and the libraries DroidNux puts in the guest.

cmake_minimum_required(VERSION 3.22)
project(droidnux_display C CXX)

set(GUEST_DIR ${CMAKE_CURRENT_LIST_DIR}/../../../../guest)

add_library(droidnux-display SHARED
    core/allocator.cpp
    core/audio_output.cpp
    core/presenter.cpp
    core/video_decoder.cpp
    buffer_spike.cpp
    jni.cpp
    probe.cpp)
target_include_directories(droidnux-display PRIVATE ${GUEST_DIR}/buffer-spike core)
target_compile_options(droidnux-display PRIVATE
    "$<$<COMPILE_LANGUAGE:CXX>:-Wall;-Wextra;-Werror>" -fvisibility=hidden)
# Newer-than-minSdk APIs are guarded with __builtin_available.
target_compile_definitions(droidnux-display PRIVATE __ANDROID_UNAVAILABLE_SYMBOLS_ARE_WEAK__)
target_link_libraries(droidnux-display PRIVATE aaudio android log mediandk nativewindow EGL GLESv3 dl)

# Preload library for KWin (guest/kwin-pacing): its render time prediction.
# It runs under glibc, so it links to nothing from the NDK: its few libc
# symbols are resolved by glibc at load time.
add_library(droidnux-guest-kwin SHARED ${GUEST_DIR}/kwin-pacing/droidnux-kwin-pacing.c)
target_compile_options(droidnux-guest-kwin PRIVATE
    -Wall -Wextra -Werror -ffreestanding -fno-builtin -fno-stack-protector -mno-outline-atomics
    -U_FORTIFY_SOURCE -fno-asynchronous-unwind-tables -fvisibility=hidden)
target_link_options(droidnux-guest-kwin PRIVATE
    -nostdlib -Wl,--as-needed -Wl,-z,undefs -Wl,--pack-dyn-relocs=none -Wl,--hash-style=both)

# PipeWire module for the container's audio (guest/pipewire): an Audio/Sink
# playing through core/audio_output.cpp. Like the library above it is loaded by
# glibc, so it links to nothing; the PipeWire headers are only for its types and
# inline functions, its pw_* calls resolve to the guest's libpipewire.
add_library(droidnux-guest-pipewire SHARED ${GUEST_DIR}/pipewire/module-droidnux.c)
set_target_properties(droidnux-guest-pipewire PROPERTIES OUTPUT_NAME pipewire-module-droidnux)
target_include_directories(droidnux-guest-pipewire PRIVATE core
    ${CMAKE_CURRENT_LIST_DIR}/../../../../third_party/pipewire/include/pipewire-0.3
    ${CMAKE_CURRENT_LIST_DIR}/../../../../third_party/pipewire/include/spa-0.2)
target_compile_options(droidnux-guest-pipewire PRIVATE
    -Wall -Wextra -Werror -Wno-unused-parameter -Wno-missing-field-initializers
    -U_FORTIFY_SOURCE -D_FORTIFY_SOURCE=0 -fno-stack-protector -mno-outline-atomics
    -fno-asynchronous-unwind-tables -fvisibility=hidden)
target_link_options(droidnux-guest-pipewire PRIVATE
    -nostdlib -Wl,--as-needed -Wl,-z,undefs -Wl,--pack-dyn-relocs=none -Wl,--hash-style=both)

# Input method KWin starts (guest/kwin-im): hands its connection to the Wayland
# front-end. A freestanding static executable, named .so so that it ships with
# the native libraries.
add_executable(droidnux-guest-im ${GUEST_DIR}/kwin-im/droidnux-im.c)
set_target_properties(droidnux-guest-im PROPERTIES OUTPUT_NAME "libdroidnux-guest-im.so")
target_compile_options(droidnux-guest-im PRIVATE
    -Wall -Wextra -Werror -ffreestanding -fno-builtin -fno-stack-protector -U_FORTIFY_SOURCE
    -fno-asynchronous-unwind-tables)
target_link_options(droidnux-guest-im PRIVATE -static -nostdlib -no-pie -Wl,--build-id=none -Wl,-z,noexecstack)

# Wayland front-end, see wl/wl.cmake.
include(wl/wl.cmake)
