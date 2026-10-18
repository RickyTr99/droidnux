# Builds proot (termux fork) and its loader for the container runtime.
#
# Both are executables, but they are named lib*.so so that Android installs
# them in the app's native library directory: since targetSdk 29 that is the
# only place an app is allowed to execute files from.

cmake_minimum_required(VERSION 3.22)
project(droidnux_container C ASM)

if(NOT ANDROID_ABI STREQUAL "arm64-v8a")
    message(FATAL_ERROR "proot is only built for arm64-v8a (got ${ANDROID_ABI})")
endif()

set(THIRD_PARTY ${CMAKE_CURRENT_LIST_DIR}/../../../../third_party)
set(PROOT_SRC ${THIRD_PARTY}/proot/src)
set(TALLOC_SRC ${THIRD_PARTY}/talloc)
set(PROOT_VERSION "5.1.107.97")

if(NOT EXISTS ${PROOT_SRC}/cli/proot.c)
    message(FATAL_ERROR "third_party/proot is empty: run 'git submodule update --init'")
endif()

# Third-party code we do not debug: always optimized, so that debug builds
# measure the same container performance as release builds. Its warnings are
# upstream issues and would only bury ours.
add_compile_options(-O2 -w)

# ---------------------------------------------------------------------------
# talloc, linked statically into proot

add_library(talloc STATIC ${TALLOC_SRC}/talloc.c)
target_include_directories(talloc
    PUBLIC ${TALLOC_SRC}
    PRIVATE ${CMAKE_CURRENT_LIST_DIR}/talloc-shim)

# ---------------------------------------------------------------------------
# Loader: freestanding static binary that proot maps into every tracee at a
# fixed address (LOADER_ADDRESS in proot's arch.h for ARM64).

set(PROOT_LOADER_ADDRESS 0x5400000000)

add_executable(proot-loader
    ${PROOT_SRC}/loader/loader.c
    ${PROOT_SRC}/loader/assembly.S)
set_target_properties(proot-loader PROPERTIES OUTPUT_NAME "libproot-loader.so")
target_include_directories(proot-loader PRIVATE ${PROOT_SRC})
target_compile_definitions(proot-loader PRIVATE _GNU_SOURCE)
target_compile_options(proot-loader PRIVATE
    -fPIC -ffreestanding -fno-stack-protector -U_FORTIFY_SOURCE
    -fno-function-sections -fno-data-sections
    -Wno-inline-asm)
target_link_options(proot-loader PRIVATE
    -static -nostdlib -no-pie
    # pokedata_workaround is never referenced inside the loader itself.
    -Wl,--no-gc-sections
    -Wl,--build-id=none
    -Wl,-Ttext=${PROOT_LOADER_ADDRESS}
    -Wl,--rosegment
    -Wl,-z,noexecstack)

# proot needs the offset of the loader's pokedata_workaround symbol.
set(LOADER_INFO_C ${CMAKE_CURRENT_BINARY_DIR}/loader-info.c)
if(CMAKE_READELF)
    set(DROIDNUX_READELF ${CMAKE_READELF})
else()
    set(DROIDNUX_READELF ${ANDROID_TOOLCHAIN_ROOT}/bin/llvm-readelf${ANDROID_TOOLCHAIN_SUFFIX})
endif()
add_custom_command(
    OUTPUT ${LOADER_INFO_C}
    COMMAND ${CMAKE_COMMAND}
        -DREADELF=${DROIDNUX_READELF}
        -DLOADER=$<TARGET_FILE:proot-loader>
        -DOUTPUT=${LOADER_INFO_C}
        -P ${CMAKE_CURRENT_LIST_DIR}/cmake/loader-info.cmake
    DEPENDS proot-loader ${CMAKE_CURRENT_LIST_DIR}/cmake/loader-info.cmake
    VERBATIM)

# ---------------------------------------------------------------------------
# proot

# Equivalent of the build.h that proot's GNUmakefile generates.
file(WRITE ${CMAKE_CURRENT_BINARY_DIR}/proot-gen/build.h
"#ifndef BUILD_H
#define BUILD_H
#undef VERSION
#define VERSION \"${PROOT_VERSION}\"
#define HAVE_PROCESS_VM
#define HAVE_SECCOMP_FILTER
#endif
")

# Same list as OBJECTS in third_party/proot/src/GNUmakefile.
add_executable(proot
    ${PROOT_SRC}/cli/cli.c
    ${PROOT_SRC}/cli/proot.c
    ${PROOT_SRC}/cli/note.c
    ${PROOT_SRC}/execve/enter.c
    ${PROOT_SRC}/execve/exit.c
    ${PROOT_SRC}/execve/shebang.c
    ${PROOT_SRC}/execve/elf.c
    ${PROOT_SRC}/execve/ldso.c
    ${PROOT_SRC}/execve/auxv.c
    ${PROOT_SRC}/execve/aoxp.c
    ${PROOT_SRC}/path/binding.c
    ${PROOT_SRC}/path/glue.c
    ${PROOT_SRC}/path/canon.c
    ${PROOT_SRC}/path/f2fs-bug.c
    ${PROOT_SRC}/path/path.c
    ${PROOT_SRC}/path/proc.c
    ${PROOT_SRC}/path/temp.c
    ${PROOT_SRC}/syscall/seccomp.c
    ${PROOT_SRC}/syscall/syscall.c
    ${PROOT_SRC}/syscall/chain.c
    ${PROOT_SRC}/syscall/enter.c
    ${PROOT_SRC}/syscall/exit.c
    ${PROOT_SRC}/syscall/sysnum.c
    ${PROOT_SRC}/syscall/socket.c
    ${PROOT_SRC}/syscall/heap.c
    ${PROOT_SRC}/syscall/rlimit.c
    ${PROOT_SRC}/syscall/pipe_shadow.c
    ${PROOT_SRC}/tracee/tracee.c
    ${PROOT_SRC}/tracee/mem.c
    ${PROOT_SRC}/tracee/reg.c
    ${PROOT_SRC}/tracee/event.c
    ${PROOT_SRC}/tracee/seccomp.c
    ${PROOT_SRC}/tracee/statx.c
    ${PROOT_SRC}/ptrace/ptrace.c
    ${PROOT_SRC}/ptrace/user.c
    ${PROOT_SRC}/ptrace/wait.c
    ${PROOT_SRC}/extension/extension.c
    ${PROOT_SRC}/extension/ashmem_memfd/ashmem_memfd.c
    ${PROOT_SRC}/extension/kompat/kompat.c
    ${PROOT_SRC}/extension/fake_id0/chown.c
    ${PROOT_SRC}/extension/fake_id0/chroot.c
    ${PROOT_SRC}/extension/fake_id0/getsockopt.c
    ${PROOT_SRC}/extension/fake_id0/sendmsg.c
    ${PROOT_SRC}/extension/fake_id0/socket.c
    ${PROOT_SRC}/extension/fake_id0/open.c
    ${PROOT_SRC}/extension/fake_id0/unlink.c
    ${PROOT_SRC}/extension/fake_id0/rename.c
    ${PROOT_SRC}/extension/fake_id0/chmod.c
    ${PROOT_SRC}/extension/fake_id0/utimensat.c
    ${PROOT_SRC}/extension/fake_id0/access.c
    ${PROOT_SRC}/extension/fake_id0/exec.c
    ${PROOT_SRC}/extension/fake_id0/link.c
    ${PROOT_SRC}/extension/fake_id0/symlink.c
    ${PROOT_SRC}/extension/fake_id0/mk.c
    ${PROOT_SRC}/extension/fake_id0/stat.c
    ${PROOT_SRC}/extension/fake_id0/helper_functions.c
    ${PROOT_SRC}/extension/fake_id0/fake_id0.c
    ${PROOT_SRC}/extension/hidden_files/hidden_files.c
    ${PROOT_SRC}/extension/mountinfo/mountinfo.c
    ${PROOT_SRC}/extension/port_switch/port_switch.c
    ${PROOT_SRC}/extension/sysvipc/sysvipc.c
    ${PROOT_SRC}/extension/sysvipc/sysvipc_msg.c
    ${PROOT_SRC}/extension/sysvipc/sysvipc_sem.c
    ${PROOT_SRC}/extension/sysvipc/sysvipc_shm.c
    ${PROOT_SRC}/extension/link2symlink/link2symlink.c
    ${PROOT_SRC}/extension/fix_symlink_size/fix_symlink_size.c
    ${LOADER_INFO_C})
set_target_properties(proot PROPERTIES OUTPUT_NAME "libproot.so")
target_include_directories(proot PRIVATE
    ${CMAKE_CURRENT_BINARY_DIR}/proot-gen
    ${PROOT_SRC})
target_compile_definitions(proot PRIVATE
    _FILE_OFFSET_BITS=64
    _GNU_SOURCE
    ARG_MAX=131072
    # Never used: the app always passes PROOT_LOADER. Defining it keeps proot
    # from embedding the loader and extracting it to a temporary file.
    PROOT_UNBUNDLE_LOADER="/droidnux-loader-unset")
# Some proot sources rely on <string.h> being pulled in indirectly.
target_compile_options(proot PRIVATE -include string.h)
target_link_libraries(proot PRIVATE talloc)
target_link_options(proot PRIVATE -Wl,-z,noexecstack)
