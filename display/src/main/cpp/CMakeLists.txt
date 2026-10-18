# Native presentation core: buffers, frame pacing and final composition shared
# by every protocol front-end (X11 first, Wayland later). See PLAN.md.

cmake_minimum_required(VERSION 3.22)
project(droidnux_display C CXX)

set(GUEST_DIR ${CMAKE_CURRENT_LIST_DIR}/../../../../guest)

add_library(droidnux-display SHARED
    buffer_spike.cpp
    jni.cpp
    probe.cpp)
target_include_directories(droidnux-display PRIVATE ${GUEST_DIR}/buffer-spike)
target_compile_options(droidnux-display PRIVATE -Wall -Wextra -Werror -fvisibility=hidden)
target_link_libraries(droidnux-display PRIVATE android log nativewindow EGL dl)

# --- X11 front-end
# The Termux:X11 build needs a Linux host (symlinks, host tools built and run
# during the build). On Windows the library is skipped; CI builds it.
if(CMAKE_HOST_WIN32)
    message(WARNING "Skipping the X11 front-end: it needs a Linux build host (CI builds it)")
else()
    # Temporary: upstream Termux:X11 build, to validate the toolchain in CI.
    add_subdirectory(${CMAKE_CURRENT_LIST_DIR}/../../../../third_party/termux-x11/lorie/src/main/cpp
                     ${CMAKE_CURRENT_BINARY_DIR}/termux-x11)
endif()
