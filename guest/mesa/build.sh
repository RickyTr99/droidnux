#!/bin/sh
# Builds the DroidNux Mesa component: Turnip (Vulkan on Adreno through KGSL,
# the GPU interface Android exposes), Zink (OpenGL on top of Vulkan) and the
# VA-API driver (guest/va, video decoding by the app), for the Debian release
# the app installs. Runs on an arm64 Debian of that release:
# in CI (.github/workflows/mesa.yml) or inside the container itself.
#
#   guest/mesa/build.sh <work dir> <output dir>
#
# Output: droidnux-mesa-<version>-arm64.tar.xz, the contents of $PREFIX, and its .sha256
# (XZ because the app unpacks it in plain Java).
# The tree goes to /opt/droidnux/mesa, so it never replaces the distro's Mesa
# (llvmpipe stays as the fallback). The GL vendor libraries are named
# "droidnux" (libGLX_droidnux, libEGL_droidnux) for the same reason; the app's
# session selects them.
set -eu

HERE=$(cd "$(dirname "$0")" && pwd)
. "$HERE/version.sh"
WORK=$(mkdir -p "$1" && cd "$1" && pwd)
OUT=$(mkdir -p "$2" && cd "$2" && pwd)
PREFIX=/opt/droidnux/mesa
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
# video, no DRM-only pieces (GBM, DRI2), and only system libraries (no wrap
# fallbacks: freedreno would pull libarchive and libxml2 for its debug tools).
meson setup "$SRC/build" "$SRC" \
    --prefix="$PREFIX" --libdir=lib/aarch64-linux-gnu \
    --buildtype=release -Db_ndebug=true -Dstrip=true --wrap-mode=nofallback \
    -Dplatforms=x11,wayland \
    -Dvulkan-drivers=freedreno -Dfreedreno-kmds=msm,kgsl \
    -Dgallium-drivers=zink \
    -Dopengl=true -Dglx=dri -Degl=enabled -Dgles1=disabled -Dgles2=enabled \
    -Dglvnd=enabled -Dglvnd-vendor-name=droidnux \
    -Dgbm=disabled -Dllvm=disabled -Dvalgrind=disabled -Dlibunwind=disabled \
    -Dvideo-codecs= -Dtools= -Dvulkan-layers= -Dbuild-tests=false
ninja -C "$SRC/build"

STAGE=$WORK/stage
rm -rf "$STAGE"
DESTDIR=$STAGE meson install -C "$SRC/build" --no-rebuild --quiet
# The session points LIBVA_DRIVERS_PATH here.
mkdir -p "$STAGE$PREFIX/lib/aarch64-linux-gnu/dri"
gcc -O2 -fPIC -shared -s -Wall -Wextra -Werror -fvisibility=hidden \
    -I "$HERE/../../display/src/main/cpp/core" \
    -o"$STAGE$PREFIX/lib/aarch64-linux-gnu/dri/droidnux_drv_video.so" "$HERE/../va/droidnux_drv_video.c" -lpthread
# Headers and pkg-config files are for building against Mesa, not running it.
rm -rf "$STAGE$PREFIX/include" "$STAGE$PREFIX/lib/aarch64-linux-gnu/pkgconfig"
cat > "$STAGE$PREFIX/droidnux-mesa.version" <<EOF
$MESA_VERSION-dn$DROIDNUX_REVISION
EOF

NAME=droidnux-mesa-$MESA_VERSION-dn$DROIDNUX_REVISION-arm64.tar.xz
tar -C "$STAGE$PREFIX" --owner=0 --group=0 --numeric-owner -cf - . | xz -9e -T0 > "$OUT/$NAME"
(cd "$OUT" && sha256sum "$NAME" > "$NAME.sha256")
echo "built $OUT/$NAME ($(du -h "$OUT/$NAME" | cut -f1))"
