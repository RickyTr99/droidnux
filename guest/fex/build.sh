#!/bin/sh
# Builds the DroidNux FEX component: FEX-Emu, which runs x86 and x86-64 Linux
# programs on arm64, for the Debian release the app installs. Runs on an arm64
# Debian of that release: in CI or inside the container itself.
#
#   guest/fex/build.sh <work dir> <output dir>
#
# Output: droidnux-fex-<version>-arm64.tar.xz, the contents of $PREFIX, and its .sha256.
# The x86 libraries come from Debian's own amd64/i386 multiarch packages in the
# same rootfs, so FEX runs without a separate x86 RootFS.
# Patches 0001, 0003 and 0004 come from DroidDeck (github.com/Droid-Deck/DroidDeck, tools/fex/patches).
set -eu

HERE=$(cd "$(dirname "$0")" && pwd)
. "$HERE/version.sh"
WORK=$(mkdir -p "$1" && cd "$1" && pwd)
OUT=$(mkdir -p "$2" && cd "$2" && pwd)
PREFIX=/opt/droidnux/fex
SRC=$WORK/fex-$FEX_VERSION

if [ "${DROIDNUX_FEX_DEPS:-}" = install ]; then
    export DEBIAN_FRONTEND=noninteractive
    apt-get update -q
    apt-get install -y -q --no-install-recommends \
        ca-certificates git xz-utils patch clang lld llvm cmake ninja-build python3 pkg-config file
fi

if [ ! -d "$SRC" ]; then
    git clone -q --depth 1 --branch "$FEX_VERSION" --recurse-submodules --shallow-submodules \
        https://github.com/FEX-Emu/FEX.git "$SRC.part"
    for p in "$HERE"/patches/*.patch; do
        echo "applying $(basename "$p")"
        patch -d "$SRC.part" -p1 --no-backup-if-mismatch < "$p"
    done
    mv "$SRC.part" "$SRC"
fi

# Generic armv8-a tuning: the same build runs on every Snapdragon the app supports.
cmake -S "$SRC" -B "$SRC/build" -G Ninja \
    -DCMAKE_BUILD_TYPE=Release -DCMAKE_INSTALL_PREFIX="$PREFIX" \
    -DCMAKE_C_COMPILER=clang -DCMAKE_CXX_COMPILER=clang++ -DUSE_LINKER=lld -DCMAKE_CXX_SCAN_FOR_MODULES=OFF \
    -DTUNE_CPU=none -DTUNE_ARCH=armv8-a \
    -DBUILD_TESTING=OFF -DBUILD_FEXCONFIG=OFF -DENABLE_ASSERTIONS=OFF -DBUILD_THUNKS=OFF \
    -DENABLE_CCACHE=OFF -DENABLE_OFFLINE_TELEMETRY=OFF
ninja -C "$SRC/build"
rm -rf "$WORK/stage"
DESTDIR="$WORK/stage" ninja -C "$SRC/build" install

NAME=droidnux-fex-${FEX_VERSION#FEX-}-dn$DROIDNUX_REVISION-arm64
tar -C "$WORK/stage$PREFIX" -cJf "$OUT/$NAME.tar.xz" .
(cd "$OUT" && sha256sum "$NAME.tar.xz" > "$NAME.tar.xz.sha256")
echo "$OUT/$NAME.tar.xz"
