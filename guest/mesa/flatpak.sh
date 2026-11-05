#!/bin/sh
# Builds DroidNux's Mesa (guest/mesa/build.sh) as the Flatpak GL extension
# org.freedesktop.Platform.GL.droidnux for one branch of the Freedesktop runtime,
# against that runtime's SDK, so Flatpak apps get Turnip on KGSL and Zink. The
# session selects it with FLATPAK_GL_DRIVERS (DesktopEnvironment.kt).
#
#   guest/mesa/flatpak.sh <runtime branch> <output dir>     e.g. 26.08 out
#
# Needs flatpak and flatpak-builder, with Flathub as a user remote.
# Output: droidnux-mesa-<version>-flatpak-<branch>-arm64.flatpak, a bundle
# "flatpak install --bundle" takes, and its .sha256.
set -eu

HERE=$(cd "$(dirname "$0")" && pwd)
. "$HERE/version.sh"
BRANCH=$1
OUT=$(mkdir -p "$2" && cd "$2" && pwd)
WORK=$(mktemp -d)
ID=org.freedesktop.Platform.GL.droidnux

cat > "$WORK/$ID.yml" <<EOF
id: $ID
branch: '$BRANCH'
runtime: org.freedesktop.Platform
runtime-version: '$BRANCH'
sdk: org.freedesktop.Sdk
build-extension: true
separate-locales: false
appstream-compose: false
build-options:
  prefix: /usr/lib/aarch64-linux-gnu/GL/droidnux
  # build.sh downloads Mesa's release and checks its SHA-256.
  build-args: [--share=network]
# Python modules Mesa's build needs and the SDK lacks, for the build only.
cleanup: [/.build-python]
modules:
  - name: build-python
    buildsystem: simple
    build-commands:
      - pip3 install --no-cache-dir --target=\$FLATPAK_DEST/.build-python pyyaml packaging
  - name: mesa
    buildsystem: simple
    build-options:
      env:
        PYTHONPATH: /usr/lib/aarch64-linux-gnu/GL/droidnux/.build-python
    build-commands:
      - test "\$FLATPAK_DEST" = /usr/lib/aarch64-linux-gnu/GL/droidnux
      - sh build.sh "\$PWD/work" "\$PWD/out"
    sources:
      - type: dir
        path: $HERE
EOF

flatpak-builder --user --install-deps-from=flathub --force-clean --repo="$WORK/repo" \
    --state-dir="$WORK/state" "$WORK/build" "$WORK/$ID.yml"
NAME=droidnux-mesa-$MESA_VERSION-dn$DROIDNUX_REVISION-flatpak-$BRANCH-arm64.flatpak
flatpak build-bundle --runtime "$WORK/repo" "$OUT/$NAME" "$ID" "$BRANCH"
(cd "$OUT" && sha256sum "$NAME" > "$NAME.sha256")
echo "built $OUT/$NAME ($(du -h "$OUT/$NAME" | cut -f1))"
