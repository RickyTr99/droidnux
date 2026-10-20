#!/bin/sh
# Downloads the X server libraries built by CI into display/prebuilt/x11, so
# the X11 front-end builds on Windows (where Termux:X11's recipes can't run).
# Only our DDX code is then compiled locally. Needs the gh CLI.
#
#   tools/fetch-x11-prebuilt.sh [branch]
set -e
cd "$(dirname "$0")/.."
REPO=RickyTr99/droidnux
BRANCH=${1:-$(git branch --show-current)}
WANT=$(git -C third_party/termux-x11 rev-parse HEAD)

RUN=$(gh run list --repo "$REPO" --branch "$BRANCH" --workflow Build --status success --limit 1 \
    --json databaseId --jq '.[0].databaseId')
[ -n "$RUN" ] || { echo "No successful CI run on $BRANCH" >&2; exit 1; }

TMP=display/prebuilt/x11.tmp
rm -rf "$TMP" && mkdir -p "$TMP"
gh run download "$RUN" --repo "$REPO" --name x11-prebuilt-arm64-v8a --dir "$TMP"
GOT=$(cat "$TMP/arm64-v8a/termux-x11.commit")
[ "$GOT" = "$WANT" ] || echo "Warning: prebuilt is for termux-x11 $GOT, the submodule is at $WANT" >&2
rm -rf display/prebuilt/x11 && mv "$TMP" display/prebuilt/x11
echo "X server prebuilt from run $RUN ($(ls display/prebuilt/x11/arm64-v8a/lib | wc -l) libraries)"
