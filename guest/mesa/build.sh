#!/bin/sh
# Builds the DroidNux Mesa component: Turnip (Vulkan on Adreno through KGSL,
# the GPU interface Android exposes), Zink (OpenGL on top of Vulkan) and the
# VA-API driver (guest/va, video decoding by the app), for the Debian release
# the app installs. Runs on an arm64 Debian of that release:
# in CI (.github/workflows/mesa.yml) or inside the container itself.
#
#   guest/mesa/build.sh <work dir> <output dir> [amd64]
#
# With amd64 it cross-builds the same drivers for x86-64 programs, from Debian's
# amd64 multiarch packages, into the FEX component (guest/fex/build.sh puts
# them in its archive): EGL and GBM have no FEX thunk, so x86 programs need
# their own; Zink in them still reaches Turnip through FEX's Vulkan thunk.
#
# Output: droidnux-mesa-<version>-<arch>.tar.xz, the contents of $PREFIX, and its .sha256
# (XZ because the app unpacks it in plain Java).
# The tree goes to /opt/droidnux/mesa, so it never replaces the distro's Mesa
# (llvmpipe stays as the fallback). The GL vendor libraries are named
# "droidnux" (libGLX_droidnux, libEGL_droidnux) for the same reason; the app's
# session selects them.
#
# Under flatpak-builder (FLATPAK_DEST set, guest/mesa/flatpak.sh) it builds the
# Flatpak GL extension org.freedesktop.Platform.GL.droidnux instead: installed
# straight into FLATPAK_DEST with the layout of the runtime's GL extensions, no
# VA driver (its socket is in the session's /tmp, which Flatpak's sandbox does
# not share) and no archive. Names stay "droidnux", apart from the runtime's
# GL.default, which stays enabled as the software fallback.
set -eu

HERE=$(cd "$(dirname "$0")" && pwd)
. "$HERE/version.sh"
WORK=$(mkdir -p "$1" && cd "$1" && pwd)
OUT=$(mkdir -p "$2" && cd "$2" && pwd)
ARCH=${3:-arm64}
if [ "$ARCH" = amd64 ]; then
    # No Turnip: their Zink reaches the arm64 one through FEX's Vulkan thunk.
    MULTIARCH=x86_64-linux-gnu CC=x86_64-linux-gnu-gcc MESA_PREFIX=/opt/droidnux/fex VULKAN_DRIVERS=
else
    MULTIARCH=aarch64-linux-gnu CC=gcc MESA_PREFIX=/opt/droidnux/mesa VULKAN_DRIVERS=freedreno
fi
if [ -n "${FLATPAK_DEST:-}" ]; then
    PREFIX=$FLATPAK_DEST LIBDIR=lib
else
    PREFIX=$MESA_PREFIX LIBDIR=lib/$MULTIARCH
fi
SRC=$WORK/mesa-$MESA_VERSION
TARBALL=$WORK/mesa-$MESA_VERSION.tar.xz

if [ "${DROIDNUX_MESA_DEPS:-}" = install ]; then
    export DEBIAN_FRONTEND=noninteractive
    apt-get update -q
    apt-get install -y -q --no-install-recommends \
        ca-certificates curl xz-utils patch \
        gcc g++ meson ninja-build pkg-config bison flex glslang-tools \
        python3-mako python3-yaml python3-packaging \
        libdrm-dev libexpat1-dev zlib1g-dev libzstd-dev libelf-dev libglvnd-dev \
        libx11-dev libx11-xcb-dev libxext-dev libxfixes-dev libxrandr-dev libxxf86vm-dev libxshmfence-dev \
        libxcb1-dev libxcb-dri3-dev libxcb-present-dev libxcb-randr0-dev libxcb-shm0-dev libxcb-sync-dev \
        libxcb-xfixes0-dev libxcb-glx0-dev libwayland-dev libwayland-egl-backend-dev wayland-protocols \
        libva-dev
    if [ "$ARCH" = amd64 ]; then
        dpkg --add-architecture amd64
        apt-get update -q
        apt-get install -y -q --no-install-recommends gcc-x86-64-linux-gnu g++-x86-64-linux-gnu \
            libdrm-dev:amd64 libexpat1-dev:amd64 zlib1g-dev:amd64 libzstd-dev:amd64 libelf-dev:amd64 \
            libglvnd-dev:amd64 libx11-dev:amd64 libx11-xcb-dev:amd64 libxext-dev:amd64 libxfixes-dev:amd64 \
            libxrandr-dev:amd64 libxxf86vm-dev:amd64 libxshmfence-dev:amd64 libxcb1-dev:amd64 \
            libxcb-dri3-dev:amd64 libxcb-present-dev:amd64 libxcb-randr0-dev:amd64 libxcb-shm0-dev:amd64 \
            libxcb-sync-dev:amd64 libxcb-xfixes0-dev:amd64 libxcb-glx0-dev:amd64 libwayland-dev:amd64 \
            libwayland-egl-backend-dev:amd64 libva-dev:amd64
    fi
fi

if [ ! -f "$TARBALL" ]; then
    curl -fL --retry 3 -o "$TARBALL.part" "https://archive.mesa3d.org/mesa-$MESA_VERSION.tar.xz"
    mv "$TARBALL.part" "$TARBALL"
fi
echo "$MESA_SHA256  $TARBALL" | sha256sum -c -

rm -rf "$SRC"
tar -xJf "$TARBALL" -C "$WORK"
for p in "$HERE"/patches/*.patch; do
    [ -e "$p" ] || continue
    echo "applying $(basename "$p")"
    patch -d "$SRC" -p1 --no-backup-if-mismatch < "$p"
done

# Turnip also gets the msm (DRM) backend: a KGSL-only build drops libdrm, and
# with it the WSI's DRM image path that X11 presentation through DRI3 needs
# (Turnip still picks KGSL at run time, as there is no /dev/dri).
# Desktop GL through Zink only: no LLVM (llvmpipe comes from the distro), no
# video, and only system libraries (no wrap
# fallbacks: freedreno would pull libarchive and libxml2 for its debug tools).
# GBM (and EGL on it) for nested compositors such as KWin on the Wayland front-end.
CROSS=
if [ "$ARCH" = amd64 ]; then
    CROSS=$WORK/cross-amd64.ini
    cat > "$CROSS" <<EOF
[binaries]
c = 'x86_64-linux-gnu-gcc'
cpp = 'x86_64-linux-gnu-g++'
strip = 'x86_64-linux-gnu-strip'
pkg-config = 'pkg-config'
[properties]
pkg_config_libdir = ['/usr/lib/x86_64-linux-gnu/pkgconfig', '/usr/share/pkgconfig']
[host_machine]
system = 'linux'
cpu_family = 'x86_64'
cpu = 'x86_64'
endian = 'little'
EOF
fi
meson setup "$SRC/build-$ARCH" "$SRC" ${CROSS:+--cross-file "$CROSS"} \
    --prefix="$PREFIX" --libdir="$LIBDIR" \
    --buildtype=release -Db_ndebug=true -Dstrip=true --wrap-mode=nofallback \
    -Dplatforms=x11,wayland \
    -Dvulkan-drivers=$VULKAN_DRIVERS -Dfreedreno-kmds=msm,kgsl \
    -Dgallium-drivers=zink \
    -Dopengl=true -Dglx=dri -Degl=enabled -Dgles1=disabled -Dgles2=enabled \
    -Dglvnd=enabled -Dglvnd-vendor-name=droidnux \
    -Dgbm=enabled -Dllvm=disabled -Dvalgrind=disabled -Dlibunwind=disabled \
    -Dvideo-codecs= -Dtools= -Dvulkan-layers= -Dbuild-tests=false
ninja -C "$SRC/build-$ARCH"

if [ -n "${FLATPAK_DEST:-}" ]; then
    meson install -C "$SRC/build-$ARCH" --no-rebuild --quiet
    # Where the runtime's merge-dirs look (as org.freedesktop.Platform.GL.default has them).
    mkdir -p "$PREFIX/vulkan"
    ln -s ../share/vulkan/icd.d "$PREFIX/vulkan/icd.d"
    # GL.default has a freedreno_icd too (Turnip without KGSL): merged, one would hide the other.
    mv "$PREFIX/share/vulkan/icd.d/freedreno_icd.aarch64.json" "$PREFIX/share/vulkan/icd.d/droidnux_icd.aarch64.json"
    ln -s share/glvnd "$PREFIX/glvnd"
    rm -rf "$PREFIX/include" "$PREFIX/lib/pkgconfig"
    echo "$MESA_VERSION-dn$DROIDNUX_REVISION" > "$PREFIX/droidnux-mesa.version"
    exit 0
fi

STAGE=$WORK/stage
rm -rf "$STAGE"
DESTDIR=$STAGE meson install -C "$SRC/build-$ARCH" --no-rebuild --quiet
# The session points LIBVA_DRIVERS_PATH here.
mkdir -p "$STAGE$PREFIX/$LIBDIR/dri"
$CC -O2 -fPIC -shared -s -Wall -Wextra -Werror -fvisibility=hidden \
    -I "$HERE/../../display/src/main/cpp/core" \
    -o"$STAGE$PREFIX/$LIBDIR/dri/droidnux_drv_video.so" "$HERE/../va/droidnux_drv_video.c" -lpthread
# Headers and pkg-config files are for building against Mesa, not running it.
rm -rf "$STAGE$PREFIX/include" "$STAGE$PREFIX/$LIBDIR/pkgconfig"
cat > "$STAGE$PREFIX/droidnux-mesa.version" <<EOF
$MESA_VERSION-dn$DROIDNUX_REVISION
EOF

NAME=droidnux-mesa-$MESA_VERSION-dn$DROIDNUX_REVISION-$ARCH.tar.xz
tar -C "$STAGE$PREFIX" --owner=0 --group=0 --numeric-owner -cf - . | xz -9e -T0 > "$OUT/$NAME"
(cd "$OUT" && sha256sum "$NAME" > "$NAME.sha256")
echo "built $OUT/$NAME ($(du -h "$OUT/$NAME" | cut -f1))"
