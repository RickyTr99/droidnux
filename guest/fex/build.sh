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
    # The thunks' guest halves are x86 libraries, built against Debian's amd64 and i386 multiarch packages.
    dpkg --add-architecture amd64
    dpkg --add-architecture i386
    apt-get update -q
    apt-get install -y -q --no-install-recommends \
        ca-certificates git xz-utils patch clang lld llvm cmake ninja-build python3 pkg-config file \
        libclang-dev llvm-dev libssl-dev \
        libegl-dev libgl-dev libvulkan-dev libdrm-dev libwayland-dev libasound2-dev libx11-dev libxcb1-dev \
        libc6-dev:amd64 libstdc++-14-dev:amd64 libgcc-14-dev:amd64 \
        libc6-dev:i386 libstdc++-14-dev:i386 libgcc-14-dev:i386
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
    -DBUILD_TESTING=OFF -DBUILD_FEXCONFIG=OFF -DENABLE_ASSERTIONS=OFF -DBUILD_THUNKS=ON -DX86_DEV_ROOTFS=/ \
    -DENABLE_CCACHE=OFF -DENABLE_OFFLINE_TELEMETRY=OFF
ninja -C "$SRC/build"
rm -rf "$WORK/stage"
DESTDIR="$WORK/stage" ninja -C "$SRC/build" install

# The session's preload (guest/kwin-pacing, Qt's animation timer) for x86 programs: their ld.so
# looks for it in its own $LIB, where the app links lib/<multiarch> of this component.
for ARCH in x86_64 i386; do
    mkdir -p "$WORK/stage$PREFIX/lib/$ARCH-linux-gnu"
    clang --target=$ARCH-linux-gnu -O2 -fPIC -shared -nostdlib -fuse-ld=lld \
        -Wall -Wextra -Werror -ffreestanding -fno-builtin -fno-stack-protector -U_FORTIFY_SOURCE \
        -fno-asynchronous-unwind-tables -fvisibility=hidden -Wl,-z,undefs -Wl,--hash-style=both \
        -o "$WORK/stage$PREFIX/lib/$ARCH-linux-gnu/libdroidnux-guest-kwin.so" "$HERE/../kwin-pacing/droidnux-qt-animations.c"
done

# FEX's global configuration. RootFS "/": the x86 libraries are the rootfs's own multiarch ones, and
# with no RootFS FEX looks for /usr/lib/x86_64-linux-gnu from the working directory, missing the
# thunks' overlays. The thunks hand GLX and Vulkan to the arm64 Mesa (Zink and Turnip). Not yet
# WaylandClient (its guest library lacks wl_list_*: libdecor and GTK fail) nor EGL (a stub upstream).
cat > "$WORK/stage$PREFIX/share/fex-emu/Config.json" << 'EOF'
{
  "Config": {"RootFS": "/"},
  "ThunksDB": {"GL": 1, "Vulkan": 1}
}
EOF

NAME=droidnux-fex-${FEX_VERSION#FEX-}-dn$DROIDNUX_REVISION-arm64
tar -C "$WORK/stage$PREFIX" -cJf "$OUT/$NAME.tar.xz" .
(cd "$OUT" && sha256sum "$NAME.tar.xz" > "$NAME.tar.xz.sha256")
echo "$OUT/$NAME.tar.xz"
