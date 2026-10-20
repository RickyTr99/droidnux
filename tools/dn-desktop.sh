#!/bin/sh
# Opens the desktop of the debug app over adb, without touching the screen
# (the debug manifest exports DesktopActivity).
#
#   tools/dn-desktop.sh [scale]   # scale: fraction of the screen, e.g. 0.7
set -e
ADB=${ADB:-adb}
EXTRA=
[ -n "$1" ] && EXTRA="--ef app.droidnux.desktop.SCALE $1"
"$ADB" shell am start -W -n ${DN_PKG:-app.droidnux.debug}/app.droidnux.desktop.DesktopActivity $EXTRA
