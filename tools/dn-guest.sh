#!/bin/sh
# Runs a command inside the debug app's Debian rootfs over adb, with the same
# proot options as the app (app.droidnux.container.Proot).
#
#   tools/dn-guest.sh 'uname -a'
#   DN_PROOT_ARGS='-b files/x:/x' tools/dn-guest.sh 'ls /x'   # extra proot options
#
# Needs a debuggable build installed and the rootfs installed from the app.
set -e
PKG=app.droidnux.debug
ADB=${ADB:-adb}
export MSYS_NO_PATHCONV=1
LIB=$("$ADB" shell dumpsys package $PKG | sed -n 's/^ *legacyNativeLibraryDir=//p' | tr -d '\r')/arm64
SCRIPT=$(printf '%s' "$*" | sed "s/'/'\\''/g")
"$ADB" shell "run-as $PKG sh -c 'cd /data/data/$PKG && mkdir -p cache/proot-tmp files/rootfs/debian-trixie/tmp/.droidnux-shm && \
GPU= && if [ -d files/gpu-node/dri ]; then GPU=\"-b \$PWD/files/gpu-node/sys:/sys/dev/char -b \$PWD/files/gpu-node/dri:/dev/dri -b /dev/kgsl-3d0:/dev/dri/renderD128\"; fi && \
PROOT_LOADER=$LIB/libproot-loader.so PROOT_TMP_DIR=\$PWD/cache/proot-tmp \
$LIB/libproot.so --kill-on-exit --link2symlink --sysvipc -L -0 -r files/rootfs/debian-trixie \
-b /dev -b /proc -b /sys -b \$PWD/files/rootfs/debian-trixie/tmp/.droidnux-shm:/dev/shm \$GPU ${DN_PROOT_ARGS:-} -w /root /usr/bin/env -i HOME=/root USER=root LANG=C.UTF-8 TERM=xterm-256color \
PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin /bin/sh -c \"\$0\"' '$SCRIPT'"
