#!/bin/sh
# Runs Burrow's test suite (container/src/main/cpp/burrow/tests) in the debug
# app's rootfs over adb, through tools/dn-guest.sh:
#
#   tools/burrow-test.sh            # all tests
#   tools/burrow-test.sh sysv.pl    # some
#
# The suite is copied to /tmp/burrow-tests in the rootfs. Like dn-guest.sh it
# runs without Android's app seccomp filter.
set -e
PKG=${DN_PKG:-app.droidnux.debug}
ADB=${ADB:-adb}
HERE=$(cd "$(dirname "$0")" && pwd)
export MSYS_NO_PATHCONV=1
tar c -C "$HERE/../container/src/main/cpp/burrow/tests" . | "$ADB" exec-in "run-as $PKG sh -c \
'cd files/rootfs/debian-trixie/tmp && rm -rf burrow-tests && mkdir burrow-tests && tar x -C burrow-tests'"
"$HERE/dn-guest.sh" "sh /tmp/burrow-tests/run.sh $*"
