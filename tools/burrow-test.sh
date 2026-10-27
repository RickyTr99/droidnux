#!/bin/sh
# Runs Burrow's test suite (container/src/main/cpp/burrow/tests) in the debug
# app's rootfs over adb, through tools/dn-guest.sh:
#
#   tools/burrow-test.sh            # all tests
#   tools/burrow-test.sh sysv.pl    # some
#
# The suite is copied to /tmp/burrow-tests in the rootfs. With a Go toolchain
# ($GO, else go in PATH) the Go test is built and run too. Like dn-guest.sh it
# runs without Android's app seccomp filter.
set -e
PKG=${DN_PKG:-app.droidnux.debug}
ADB=${ADB:-adb}
GO=${GO:-go}
HERE=$(cd "$(dirname "$0")" && pwd)
TESTS=$HERE/../container/src/main/cpp/burrow/tests
export MSYS_NO_PATHCONV=1
if command -v "$GO" > /dev/null 2>&1; then
    (cd "$TESTS/go" && GOOS=linux GOARCH=arm64 CGO_ENABLED=0 GO111MODULE=off "$GO" build -o burrowtest main.go)
fi
tar c -C "$TESTS" . | "$ADB" exec-in "run-as $PKG sh -c \
'cd files/rootfs/debian-trixie/tmp && rm -rf burrow-tests && mkdir burrow-tests && tar x -C burrow-tests'"
"$HERE/dn-guest.sh" "sh /tmp/burrow-tests/run.sh $*"
