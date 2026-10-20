#!/bin/sh
# Runs Gradle with the host tools the native X11 build needs (Python 3, Bison,
# bash/patch). On Linux and in CI they come from the system; on Windows (Git
# Bash) this adds the winget install locations.
#
#   tools/gradle.sh assembleDebug
set -e
case "$(uname -s)" in
MINGW*|MSYS*)
    for dir in \
        "$LOCALAPPDATA/Programs/Python/Python313" \
        "$LOCALAPPDATA/Microsoft/WinGet/Packages/WinFlexBison.win_flex_bison_Microsoft.Winget.Source_8wekyb3d8bbwe"; do
        [ -d "$dir" ] && PATH="$(cygpath -u "$dir"):$PATH"
    done
    export PATH
    ;;
esac
exec "$(dirname "$0")/../gradlew" "$@"
