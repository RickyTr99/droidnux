#!/bin/sh
# Builds the DroidNux theme component: the Papirus and Papirus-Dark icon
# themes with their icon caches already generated (under proot,
# gtk-update-icon-cache over Papirus' ~47000 files takes about half an hour),
# and Arc-Dark window borders for xfwm4 scaled to 1.5x and 2x (Arc ships
# them at 1x only). Runs on a Debian of the release the app installs:
# in CI (.github/workflows/theme.yml) or inside the container itself.
#
#   guest/theme/build.sh <work dir> <output dir>
#
# Output: droidnux-theme-<version>.tar.xz, the contents of $PREFIX, and its .sha256.
# The tree goes to /opt/droidnux/theme; the session adds $PREFIX/share to
# XDG_DATA_DIRS. The GTK theme itself (arc-theme) comes from the distro.
set -eu

HERE=$(cd "$(dirname "$0")" && pwd)
. "$HERE/version.sh"
WORK=$(mkdir -p "$1" && cd "$1" && pwd)
OUT=$(mkdir -p "$2" && cd "$2" && pwd)
PREFIX=/opt/droidnux/theme
VERSION=${PAPIRUS_DEB%-*}-dn$DROIDNUX_REVISION

if [ "${DROIDNUX_THEME_DEPS:-}" = install ]; then
    export DEBIAN_FRONTEND=noninteractive
    apt-get update -q
    apt-get install -y -q --no-install-recommends xz-utils gtk-update-icon-cache python3-pil
fi

cd "$WORK"
rm -rf debs root stage
mkdir debs root
(cd debs && apt-get download "papirus-icon-theme=$PAPIRUS_DEB" "arc-theme=$ARC_DEB")
for d in debs/*.deb; do dpkg-deb -x "$d" root; done

STAGE=$WORK/stage$PREFIX
mkdir -p "$STAGE/share/icons" "$STAGE/share/themes"
# Papirus-Dark links most of its icons into ../Papirus.
cp -a root/usr/share/icons/Papirus root/usr/share/icons/Papirus-Dark "$STAGE/share/icons/"
python3 "$HERE/scale_xfwm4.py" root/usr/share/themes/Arc-Dark/xfwm4 "$STAGE/share/themes/Arc-Dark-hdpi/xfwm4" 1.5
python3 "$HERE/scale_xfwm4.py" root/usr/share/themes/Arc-Dark/xfwm4 "$STAGE/share/themes/Arc-Dark-xhdpi/xfwm4" 2
echo "$VERSION" > "$STAGE/droidnux-theme.version"

for t in Papirus Papirus-Dark; do gtk-update-icon-cache -f -q "$STAGE/share/icons/$t"; done

# GTK ignores a cache older than its theme directory, and the app's unpacker
# does not restore modification times: the caches go last, so they are
# written after everything else in their directories.
NAME=droidnux-theme-$VERSION.tar.xz
TAR=$WORK/theme.tar
tar -C "$STAGE" --owner=0 --group=0 --numeric-owner --sort=name --exclude=icon-theme.cache -cf "$TAR" .
tar -C "$STAGE" --owner=0 --group=0 --numeric-owner -rf "$TAR" ./share/icons/Papirus/icon-theme.cache ./share/icons/Papirus-Dark/icon-theme.cache
xz -9e -T0 -c "$TAR" > "$OUT/$NAME"
rm "$TAR"
(cd "$OUT" && sha256sum "$NAME" > "$NAME.sha256")
echo "built $OUT/$NAME ($(du -h "$OUT/$NAME" | cut -f1))"
