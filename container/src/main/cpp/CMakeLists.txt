# Builds Burrow, the container runtime (see burrow/burrow.h).
#
# It is an executable, but named lib*.so so that Android installs it in the
# app's native library directory: since targetSdk 29 that is the only place an
# app is allowed to execute files from.

cmake_minimum_required(VERSION 3.22)
project(droidnux_container C ASM)

if(NOT ANDROID_ABI STREQUAL "arm64-v8a")
    message(FATAL_ERROR "Burrow is only built for arm64-v8a (got ${ANDROID_ABI})")
endif()

# A freestanding static binary linked at a fixed address, since its SIGSYS
# handler and syscall gadget stay mapped in every guest process.
add_executable(burrow
    burrow/src/start.S
    burrow/src/config.c
    burrow/src/elf.c
    burrow/src/l2s.c
    burrow/src/main.c
    burrow/src/path.c
    burrow/src/procfs.c
    burrow/src/sysv.c
    burrow/src/trap.c
    burrow/src/util.c)
set_target_properties(burrow PROPERTIES OUTPUT_NAME "libburrow.so")
target_compile_options(burrow PRIVATE
    -O2 -Wall -Wextra
    -ffreestanding -fno-stack-protector -U_FORTIFY_SOURCE
    -fno-pic -fno-pie -fno-asynchronous-unwind-tables -mno-outline-atomics
    # Go keeps its g in x28 and its signal handlers trust it: a signal that
    # comes while the SIGSYS handler runs must find the guest's.
    -ffixed-x28)
target_link_options(burrow PRIVATE
    -static -nostdlib -no-pie
    -Wl,--image-base=0x5600000000
    -Wl,--build-id=none
    -Wl,-z,noexecstack)
