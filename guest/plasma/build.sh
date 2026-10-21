#!/bin/sh
# Builds the DroidNux Plasma component: Debian's KSvg with DroidNux's patches
# (guest/plasma/patches), which save Plasma's interface file lookups that
# proot makes slow. Runs on a Debian of the release the app installs: in CI
# (.github/workflows/plasma.yml) or inside the container itself.
#
#   guest/plasma/build.sh <work dir> <output dir>
#
# Output: droidnux-plasma-<version>-arm64.tar.xz, the contents of $PREFIX,
# and its .sha256. The tree goes to /opt/droidnux/plasma; the Plasma session
# puts its lib directory first in LD_LIBRARY_PATH while the distro's
# libkf6svg6 is the version it was built from.
set -eu

HERE=$(cd "$(dirname "$0")" && pwd)
. "$HERE/version.sh"
WORK=$(mkdir -p "$1" && cd "$1" && pwd)
OUT=$(mkdir -p "$2" && cd "$2" && pwd)
PREFIX=/opt/droidnux/plasma
VERSION=${KSVG_DEB%-*}-dn$DROIDNUX_REVISION
LIBDIR=lib/aarch64-linux-gnu

if [ "${DROIDNUX_PLASMA_DEPS:-}" = install ]; then
    export DEBIAN_FRONTEND=noninteractive
    # Debian's source packages, for apt-get source.
    sed 's/^Types: deb$/Types: deb-src/' /etc/apt/sources.list.d/debian.sources > /etc/apt/sources.list.d/droidnux-src.sources
    apt-get update -q
    apt-get install -y -q --no-install-recommends dpkg-dev xz-utils cmake g++ extra-cmake-modules \
        qt6-base-dev qt6-declarative-dev qt6-svg-dev libkf6archive-dev libkf6config-dev \
        libkf6colorscheme-dev libkf6coreaddons-dev libkf6guiaddons-dev libkirigami-dev
fi

cd "$WORK"
rm -rf src build stage
mkdir src
(cd src && apt-get source -q --download-only "kf6-ksvg=$KSVG_DEB" && dpkg-source -x kf6-ksvg_*.dsc ksvg)
for p in "$HERE"/patches/*.patch; do patch -d src/ksvg -p1 < "$p"; done

# Only the library: the QML module stays Debian's.
cmake -S src/ksvg -B build -DCMAKE_BUILD_TYPE=RelWithDebInfo -DCMAKE_INSTALL_PREFIX=/usr \
    -DBUILD_TESTING=OFF -DBUILD_QCH=OFF
cmake --build build --target KF6Svg -j"$(nproc)"

STAGE=$WORK/stage$PREFIX
mkdir -p "$STAGE/$LIBDIR"
SONAME=libKF6Svg.so.6
cp -L "build/bin/$SONAME" "$STAGE/$LIBDIR/$SONAME"
strip --strip-unneeded "$STAGE/$LIBDIR/$SONAME"
printf '%s\nlibkf6svg6 %s\n' "$VERSION" "$KSVG_DEB" > "$STAGE/droidnux-plasma.version"

NAME=droidnux-plasma-$VERSION-arm64.tar.xz
tar -C "$STAGE" --owner=0 --group=0 --numeric-owner --sort=name -cJf "$OUT/$NAME" .
(cd "$OUT" && sha256sum "$NAME" > "$NAME.sha256")
echo "built $OUT/$NAME ($(du -h "$OUT/$NAME" | cut -f1))"
