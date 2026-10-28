#!/bin/sh
# Builds the DroidNux Plasma component: Debian's KSvg and KIconThemes with
# DroidNux's patches (guest/plasma/patches/<library>), which save the file
# lookups of Plasma's interface and of KDE's icons, slow in the container, and
# ksystemstats' GPU, disk and network plugins, which find Android's Adreno,
# storage and network.
# Runs on a Debian of the release the app installs: in CI
# (.github/workflows/plasma.yml) or inside the container itself.
#
#   guest/plasma/build.sh <work dir> <output dir>
#
# Output: droidnux-plasma-<version>-arm64.tar.xz, the contents of $PREFIX,
# and its .sha256. The tree goes to /opt/droidnux/plasma; the Plasma session
# puts its lib directory first in LD_LIBRARY_PATH (and its Qt plugins first
# in QT_PLUGIN_PATH) while the distro's
# libraries are the versions they were built from (droidnux-plasma.version:
# the component's version, then a "<package> <version>" line for each).
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
        libkf6colorscheme-dev libkf6coreaddons-dev libkf6guiaddons-dev libkirigami-dev \
        qt6-base-private-dev qt6-declarative-private-dev qt6-tools-dev libkf6breezeicons-dev \
        libkf6configwidgets-dev libkf6i18n-dev libkf6widgetsaddons-dev libxkbcommon-dev
    apt-get build-dep -y -q ksystemstats
fi

cd "$WORK"
rm -rf src build stage
mkdir src
STAGE=$WORK/stage$PREFIX
mkdir -p "$STAGE/$LIBDIR"
printf '%s\n' "$VERSION" > "$STAGE/droidnux-plasma.version"

# build <source package> <version> <directory> <CMake target> <library> <binary package>
# Only the library: QML modules and the rest stay Debian's.
build() {
    fetch "$@"
    cmake --build "build/$3" --target "$4" -j"$(nproc)"
    cp -L "build/$3/bin/$5" "$STAGE/$LIBDIR/$5"
    strip --strip-unneeded "$STAGE/$LIBDIR/$5"
    printf '%s %s\n' "$6" "$2" >> "$STAGE/droidnux-plasma.version"
}

# fetch <source package> <version> <directory>: sources, patches and CMake.
fetch() {
    (cd src && apt-get source -q --download-only "$1=$2" && dpkg-source -x "$1"_*.dsc "$3")
    for p in "$HERE/patches/$3"/*.patch; do patch -d "src/$3" -p1 < "$p"; done
    cmake -S "src/$3" -B "build/$3" -DCMAKE_BUILD_TYPE=RelWithDebInfo -DCMAKE_INSTALL_PREFIX=/usr \
        -DBUILD_TESTING=OFF -DBUILD_QCH=OFF
}
build kf6-ksvg "$KSVG_DEB" ksvg KF6Svg libKF6Svg.so.6 libkf6svg6
build kf6-kiconthemes "$KICONTHEMES_DEB" kiconthemes KF6IconThemes libKF6IconThemes.so.6 libkf6iconthemes6

# ksystemstats' plugins, under the names of the distro's they replace (found first).
fetch ksystemstats "$KSYSTEMSTATS_DEB" ksystemstats
PLUGINS=$STAGE/$LIBDIR/qt6/plugins/ksystemstats
mkdir -p "$PLUGINS"
for plugin in ksystemstats_plugin_gpu ksystemstats_plugin_disk ksystemstats_plugin_network; do
    cmake --build build/ksystemstats --target "$plugin" -j"$(nproc)"
    cp -L "build/ksystemstats/bin/$plugin.so" "$PLUGINS/$plugin.so"
    strip --strip-unneeded "$PLUGINS/$plugin.so"
done
printf '%s %s\n' ksystemstats "$KSYSTEMSTATS_DEB" >> "$STAGE/droidnux-plasma.version"

NAME=droidnux-plasma-$VERSION-arm64.tar.xz
tar -C "$STAGE" --owner=0 --group=0 --numeric-owner --sort=name -cJf "$OUT/$NAME" .
(cd "$OUT" && sha256sum "$NAME" > "$NAME.sha256")
echo "built $OUT/$NAME ($(du -h "$OUT/$NAME" | cut -f1))"
