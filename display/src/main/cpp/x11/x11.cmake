# X11 front-end: the X server libraries from Termux:X11 plus our own DDX
# (x11/*.c), linked into droidnux-display.
#
# Server libraries come from one of:
#   - Linux host (CI): built from source with Termux:X11's CMake recipes.
#   - Windows host with display/prebuilt/x11/<abi>, fetched with
#     tools/fetch-x11-prebuilt.sh: static libraries and generated headers
#     built by CI, so only our code compiles locally.
#   - Windows host without them: the X11 front-end is left out.

set(TX11_DIR ${CMAKE_CURRENT_LIST_DIR}/../../../../../third_party/termux-x11/lorie/src/main/cpp)
get_filename_component(TX11_DIR ${TX11_DIR} ABSOLUTE)
get_filename_component(X11_PREBUILT_DIR ${CMAKE_CURRENT_LIST_DIR}/../../../../prebuilt/x11/${ANDROID_ABI} ABSOLUTE)

# Linked as a group: the DDX and the server libraries reference each other in
# both directions, and only the objects actually needed are pulled in (some
# archives carry client-side libX11 code a server never uses).
set(X11_SERVER_LIBS
    tirpc Xdmcp Xau pixman Xfont2 fontenc xshmfence xkbcomp
    xserver_glx xserver_glxvnd xserver_fb xserver_mi xserver_dix xserver_composite
    xserver_damageext xserver_dbe xserver_randr xserver_miext_damage xserver_render
    xserver_present xserver_xext xserver_dri3 xserver_miext_sync xserver_xfixes xserver_xi
    xserver_xkb xserver_record xserver_xi_stubs xserver_xkb_stubs xserver_os xserver_exa)
# Pulled in by the libraries above.
set(X11_DEPENDENCY_LIBS md xshmfence_pthread xshmfence_futex)

if(NOT CMAKE_HOST_WIN32)
    add_subdirectory(${TX11_DIR} ${CMAKE_CURRENT_BINARY_DIR}/termux-x11)
    set(X11_GENERATED_DIR ${CMAKE_CURRENT_BINARY_DIR}/termux-x11)
    set(X11_MODE "source")
elseif(EXISTS ${X11_PREBUILT_DIR}/lib/libxserver_dix.a)
    # Same source patches the recipes apply at configure time, so headers
    # match the prebuilt libraries.
    foreach(pair IN ITEMS "xserver;xserver" "libepoxy;libepoxy" "pixman;pixman" "xkbcomp;xkbcomp"
                         "libxkbfile;xkbfile" "libx11;x11" "libxtrans;Xtrans")
        list(GET pair 0 dir)
        list(GET pair 1 patch)
        execute_process(COMMAND bash -c
            "patch -p1 -d '${TX11_DIR}/${dir}' -i '${TX11_DIR}/patches/${patch}.patch' -R --dry-run > /dev/null 2>&1 || patch -p1 -N -d '${TX11_DIR}/${dir}' -i '${TX11_DIR}/patches/${patch}.patch' -r -")
    endforeach()
    foreach(lib IN LISTS X11_SERVER_LIBS X11_DEPENDENCY_LIBS)
        add_library(${lib} STATIC IMPORTED)
        set_target_properties(${lib} PROPERTIES IMPORTED_LOCATION ${X11_PREBUILT_DIR}/lib/lib${lib}.a)
    endforeach()
    set(X11_GENERATED_DIR ${X11_PREBUILT_DIR}/include)
    set(X11_MODE "prebuilt")
else()
    message(WARNING "X11 front-end left out: building it on Windows needs display/prebuilt/x11 "
                    "(run tools/fetch-x11-prebuilt.sh), or build on Linux")
    return()
endif()
message(STATUS "X11 front-end: server libraries from ${X11_MODE}")

set(X11_INCLUDE_DIRS
    ${X11_GENERATED_DIR}
    ${X11_GENERATED_DIR}/xserver
    ${TX11_DIR}/lorie
    ${TX11_DIR}/lorie/shm
    ${TX11_DIR}/libxcvt/include
    ${TX11_DIR}/libxfont/include
    ${TX11_DIR}/pixman/pixman
    ${TX11_DIR}/xorgproto/include
    ${TX11_DIR}/libxkbfile/include
    ${TX11_DIR}/xserver/Xext
    ${TX11_DIR}/xserver/Xi
    ${TX11_DIR}/xserver/composite
    ${TX11_DIR}/xserver/damageext
    ${TX11_DIR}/xserver/fb
    ${TX11_DIR}/xserver/mi
    ${TX11_DIR}/xserver/miext/damage
    ${TX11_DIR}/xserver/miext/shadow
    ${TX11_DIR}/xserver/miext/sync
    ${TX11_DIR}/xserver/dbe
    ${TX11_DIR}/xserver/dri3
    ${TX11_DIR}/xserver/include
    ${TX11_DIR}/xserver/present
    ${TX11_DIR}/xserver/randr
    ${TX11_DIR}/xserver/render
    ${TX11_DIR}/xserver/xfixes
    ${TX11_DIR}/xserver/glx
    ${TX11_DIR}/xserver/exa)

# Upstream sources used as they are, plus our DDX.
set(X11_SOURCES
    ${TX11_DIR}/xserver/mi/miinitext.c
    ${TX11_DIR}/xserver/hw/xquartz/keysym2ucs.c
    ${TX11_DIR}/libxcvt/lib/libxcvt.c
    ${TX11_DIR}/lorie/shm/shmem.c
    ${TX11_DIR}/lorie/InitInput.c
    ${TX11_DIR}/lorie/InputXKB.c
    ${TX11_DIR}/lorie/workqueue.cpp
    ${CMAKE_CURRENT_LIST_DIR}/output.c
    ${CMAKE_CURRENT_LIST_DIR}/server.c)

set(X11_C_OPTIONS
    -std=gnu99 -DHAVE_DIX_CONFIG_H -fno-strict-aliasing -fvisibility=hidden -fPIC
    -D_DEFAULT_SOURCE -D_BSD_SOURCE -DHAS_FCHOWN -DHAS_STICKY_DIR_BIT -D_XSERVER64=1
    "-D_PATH_TMP=getenv(\"TMPDIR\")?:\"/tmp\""
    -Wall -Wno-unused-parameter -Wno-sign-compare -Wno-missing-field-initializers
    -Wno-macro-redefined -Wno-unused-function -Wno-unused-variable)

target_sources(droidnux-display PRIVATE ${X11_SOURCES})
set(X11_C_SOURCES ${X11_SOURCES})
list(FILTER X11_C_SOURCES INCLUDE REGEX "[.]c$")
set_source_files_properties(${X11_SOURCES} PROPERTIES INCLUDE_DIRECTORIES "${X11_INCLUDE_DIRS}")
set_source_files_properties(${X11_C_SOURCES} PROPERTIES COMPILE_OPTIONS "${X11_C_OPTIONS}")
# workqueue.cpp (Termux:X11's thread-safe work queue): same defines, C++.
set_source_files_properties(${TX11_DIR}/lorie/workqueue.cpp PROPERTIES
    COMPILE_OPTIONS "-DHAVE_DIX_CONFIG_H;-D_XSERVER64=1;-fno-exceptions;-fno-rtti;-Wno-error")
target_compile_definitions(droidnux-display PRIVATE DROIDNUX_HAVE_X11=1)
target_include_directories(droidnux-display PRIVATE ${CMAKE_CURRENT_LIST_DIR})
target_link_options(droidnux-display PRIVATE -Wl,--no-undefined)
target_link_libraries(droidnux-display PRIVATE
    -Wl,--start-group ${X11_SERVER_LIBS} ${X11_DEPENDENCY_LIBS} -Wl,--end-group
    mediandk m z GLESv2)
