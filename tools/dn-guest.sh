#!/bin/sh
# Runs a command inside the debug app's Debian rootfs over adb, under Burrow
# with the same options as the app (app.droidnux.container.Burrow).
#
#   tools/dn-guest.sh 'uname -a'
#   DN_BURROW_ARGS='-b files/x:/x' tools/dn-guest.sh 'ls /x'   # extra Burrow options
#
# Needs a debuggable build installed and the rootfs installed from the app.
# run-as is not started by zygote: the command runs without Android's app
# seccomp filter, so what depends on it must be tried inside the session.
# DN_PKG selects another package.
set -e
PKG=${DN_PKG:-app.droidnux.debug}
ADB=${ADB:-adb}
export MSYS_NO_PATHCONV=1
# The running session's DNS proxy, which the rootfs's resolv.conf points to.
DNS=$("$ADB" shell "ps -A -o ARGS" | grep -m1 -o "libburrow.so -r [^ ]* -d [0-9]*" | awk '{print "-d " $5}' | tr -d '\r')
LIB=$("$ADB" shell dumpsys package $PKG | sed -n 's/^ *legacyNativeLibraryDir=//p' | tr -d '\r')/arm64
SCRIPT=$(printf '%s' "$*" | sed "s/'/'\\''/g")
"$ADB" shell "run-as $PKG sh -c 'cd /data/data/$PKG && R=\$PWD/files/rootfs/debian-trixie && mkdir -p \$R/tmp/.droidnux-shm && \
GPU= && if [ -d files/gpu-node/dri ]; then GPU=\"-b \$PWD/files/gpu-node/sys:/sys/dev/char -b \$PWD/files/gpu-node/dri:/dev/dri -b /dev/kgsl-3d0:/dev/dri/renderD128 -b /dev/kgsl-3d0:/dev/dri/renderD0\"; fi && \
SD= && if [ -r /storage/emulated/0 ]; then SD=\"-b /storage/emulated/0:/sdcard\"; fi && \
$LIB/libburrow.so -r \$R $DNS -b /dev -b /proc -b /sys -b \$R/tmp/.droidnux-shm:/dev/shm \$GPU \$SD ${DN_BURROW_ARGS:-} -w /root -- \
/usr/bin/env -i HOME=/root USER=root LANG=C.UTF-8 TERM=xterm-256color \
PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin /bin/sh -c \"\$0\"' '$SCRIPT'"
