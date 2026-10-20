#!/bin/sh
# Regenerates the Wayland protocol code in display/src/main/cpp/wl/protocols
# with the wayland-scanner of the debug app's rootfs (Windows has none). The
# XML goes through /sdcard, so the debug app needs "all files access".
#
#   tools/gen-wayland-protocols.sh
set -e
cd "$(dirname "$0")/.."
ADB=${ADB:-adb}
export MSYS_NO_PATHCONV=1
OUT=display/src/main/cpp/wl/protocols
XFER=/sdcard/Download/dnx-wl
WP=third_party/wayland-protocols

# Core protocol: the header and code libwayland-server itself is built with.
CORE=third_party/wayland/protocol/wayland.xml
# Extension protocols the compositor implements.
EXT="$WP/stable/xdg-shell/xdg-shell.xml
$WP/stable/linux-dmabuf/linux-dmabuf-v1.xml
$WP/stable/presentation-time/presentation-time.xml
$WP/stable/viewporter/viewporter.xml"

"$ADB" shell "rm -rf $XFER && mkdir -p $XFER"
for x in $EXT; do "$ADB" push -q "$x" "$XFER/"; done
# Debian's scanner (1.23) rejects the newer frozen="true" attribute, which only
# drives validation: strip it.
mkdir -p display/build && TMP=display/build/wayland.xml && sed 's/ frozen="true"//' "$CORE" > "$TMP"
"$ADB" push -q "$TMP" "$XFER/wayland.xml" && rm -f "$TMP"

SCRIPT="cd $XFER && wayland-scanner -s server-header wayland.xml wayland-server-protocol.h && \
wayland-scanner -s server-header -c wayland.xml wayland-server-protocol-core.h && \
wayland-scanner -s public-code wayland.xml wayland-protocol.c"
for x in $EXT; do
    n=$(basename "$x" .xml)
    SCRIPT="$SCRIPT && wayland-scanner -s server-header $n.xml $n-protocol.h && \
wayland-scanner -s private-code $n.xml $n-protocol.c"
done
tools/dn-guest.sh "$SCRIPT && rm *.xml"

rm -rf "$OUT" && mkdir -p "$OUT"
"$ADB" pull -q "$XFER/." "$OUT"
"$ADB" shell "rm -rf $XFER"
echo "$(ls "$OUT" | wc -l) files in $OUT ($(tools/dn-guest.sh 'wayland-scanner --version' 2>&1))"
