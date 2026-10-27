package app.droidnux.container

import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import java.io.File
import java.io.IOException

/**
 * The desktop that runs in the container, Plasma with its KWin nested on the
 * app's Wayland compositor: what to install, and the files that adapt it to
 * DroidNux (session script, defaults, libraries).
 */
object DesktopEnvironment {

    private const val SESSION_SCRIPT = "/usr/local/bin/droidnux-session"
    private const val APPS_SCRIPT = "/usr/local/bin/droidnux-apps"

    /** PNG icons of the desktop's apps, kept by [APPS_SCRIPT] for Android. */
    private const val ICON_CACHE = "/var/cache/droidnux/icons"

    /** Who Plasma runs as (USER_SESSION). */
    private const val DESKTOP_USER = "droidnux"

    /** FIFO the session reads desktop ids from, to start those apps inside it. */
    private const val LAUNCH_FIFO = "/tmp/.droidnux-launch"
    const val DISPLAY = 0

    /** The shadow hash the app's password got last (USER_SESSION), root's only. */
    private const val PASSWORD_STAMP = "/var/lib/droidnux/password-hash"

    /** KWin's render time prediction (guest/kwin-pacing), preloaded into Plasma. */
    private const val KWIN_LIBRARY = "libdroidnux-guest-kwin.so"
    private const val GUEST_LIB_DIR = "/usr/local/lib/droidnux"

    /** Sound: DroidNux's PipeWire sink (guest/pipewire), in PipeWire's module directory. */
    private const val PIPEWIRE_MODULE = "libpipewire-module-droidnux.so"
    private const val PIPEWIRE_MODULE_DIR = "/usr/lib/aarch64-linux-gnu/pipewire-0.3"
    private const val PIPEWIRE_CONF = "/etc/pipewire/pipewire.conf.d/50-droidnux.conf"
    private const val PIPEWIRE_CLIENT_CONF = "/etc/pipewire/client.conf.d/50-droidnux.conf"
    private const val WIREPLUMBER_CONF = "/etc/wireplumber/wireplumber.conf.d/50-droidnux.conf"

    /** Where the Mesa component (guest/mesa) is unpacked. */
    const val MESA_PREFIX = "/opt/droidnux/mesa"

    /** Where the Plasma component (guest/plasma: patched KSvg and KIconThemes) is unpacked. */
    const val PLASMA_PREFIX = "/opt/droidnux/plasma"

    /**
     * DroidNux's defaults for Plasma, ahead of the distro's in XDG_CONFIG_DIRS
     * (as Kubuntu does): what users change still goes to ~/.config. kdeglobals
     * picks [PLASMA_LOOK_AND_FEEL], applied by Plasma on first login.
     */
    private const val PLASMA_DEFAULTS = "/etc/xdg/droidnux-plasma"
    private const val PLASMA_LOOK_AND_FEEL = "/usr/share/plasma/look-and-feel/org.droidnux.desktop"

    /**
     * The input method KWin starts (guest/kwin-im): it hands KWin's input method
     * connection to the app's compositor, which types Android's keyboard into text fields.
     */
    private const val IM_LIBRARY = "libdroidnux-guest-im.so"
    private const val IM_PROGRAM = "$GUEST_LIB_DIR/droidnux-im"
    private const val IM_DESKTOP_FILE = "$PLASMA_DEFAULTS/droidnux-im.desktop"

    /** Opens KDE's wallet with the password before and moves it to the new one (WALLET). */
    private const val WALLET_SCRIPT = "$GUEST_LIB_DIR/droidnux-wallet"

    /**
     * Android's battery for the desktop: the app writes it to BATTERY_FILE,
     * UPOWER_SCRIPT serves it as UPower on a system bus of our own (SYSTEM_BUS).
     */
    private const val BATTERY_FILE = "/run/droidnux-battery/state"
    private const val UPOWER_SCRIPT = "$GUEST_LIB_DIR/droidnux-upower"
    private const val SYSTEM_BUS_CONF = "$GUEST_LIB_DIR/system-bus.conf"
    private const val SYSTEM_BUS_SOCKET = "/run/dbus/system_bus_socket"

    /** Plasma on the app's Wayland compositor, with KDE's everyday apps. */
    private val PLASMA_PACKAGES = listOf(
        "plasma-workspace", "plasma-desktop", "kwin-wayland", "breeze", "xkb-data",
        // The session bus without dbus-user-session, which pulls in systemd.
        "dbus-x11",
        // xkbcomp builds the keymap the compositor sends (SESSION).
        "x11-xkb-utils",
        "systemsettings", "kinfocenter", "plasma-systemmonitor", "plasma-pa", "xdg-desktop-portal-kde",
        // The battery applet (Plasma 6.3 moved it here), fed by UPOWER.
        "powerdevil",
        // Display settings: the scale, which the app's compositor follows.
        "kscreen",
        // The desktop's user administers with sudo (USER_SESSION).
        "sudo",
        // KDE's wallet moved to a new password over D-Bus (WALLET).
        "python3-gi",
        // Digest::SHA, for the wallet's key (USER_SESSION); perl-base lacks it.
        "perl",
        "konsole", "dolphin", "kwrite", "ark", "gwenview", "okular", "kde-spectacle",
        // GTK apps (Firefox) in Breeze, set from System Settings.
        "breeze-gtk-theme", "kde-config-gtk-style",
    )

    /** What the desktop needs besides Plasma. */
    private val COMMON_PACKAGES = listOf(
        // The browser pinned in the panel (PLASMA_LAYOUT).
        "firefox-esr",
        "fonts-dejavu-core", "tzdata",
        // Chinese, Japanese, Korean and emoji: without them web pages show empty boxes.
        "fonts-noto-cjk", "fonts-noto-color-emoji",
        // Android's language in the desktop's apps (SESSION generates the locale).
        "locales",
        // The app's list of Linux apps: gio starts them, rsvg-convert draws their icons (APPS_SCRIPT).
        "libglib2.0-bin", "librsvg2-bin",
        // SVG loader for gdk-pixbuf: GTK apps' icons are often SVG.
        "librsvg2-common",
        // H.264 and AAC for Firefox: its bundled decoders cover only VP8/VP9/AV1/Opus/Vorbis.
        "libavcodec61",
        // Sound, played by the app (PIPEWIRE_CONF); PulseAudio and ALSA clients too.
        "pipewire", "pipewire-pulse", "pipewire-alsa", "wireplumber",
    )

    private val PACKAGES = PLASMA_PACKAGES + COMMON_PACKAGES

    /** The packages of the desktop itself: the store never removes them. */
    val desktopPackages: List<String> get() = PACKAGES

    /** Written by [installScript]: the package list of the last successful install. */
    private const val PACKAGES_MARKER = "var/lib/droidnux/desktop-packages"

    /** Guest shell script installing or updating the desktop (needs network). */
    fun installScript(): String {
        val packages = PACKAGES.joinToString(" ")
        val language = java.util.Locale.getDefault().language.takeIf { Regex("[a-z]{2,3}").matches(it) } ?: "en"
        return """
        set -e
        export DEBIAN_FRONTEND=noninteractive
        apt-get update
        # The slim image keeps no man pages, nor their directories: some packages'
        # scripts copy theirs there (Gaia Sky's .deb into man6) and fail without them.
        for n in 1 2 3 4 5 6 7 8 9; do mkdir -p /usr/share/man/man${'$'}n; done
        # The slim Debian image drops every translation (/usr/share/locale/*):
        # let Android's language back in. Packages installed before this was
        # here are reinstalled once to get theirs.
        TRANSLATIONS=/etc/dpkg/dpkg.cfg.d/droidnux-translations
        if ! grep -qs "/locale/$language/" ${'$'}TRANSLATIONS; then
            printf 'path-include /usr/share/locale/%s/*\npath-include /usr/share/locale/%s_*/*\n' $language $language > ${'$'}TRANSLATIONS
            installed=${'$'}(dpkg-query -W -f '${'$'}{db:Status-Abbrev} ${'$'}{Package}\n' | awk '${'$'}1 == "ii" { print ${'$'}2 }' |
                grep -E '^(libgtk-3|libglib2|libqt6|libkf6|plasma-|kwin-|konsole|dolphin|systemsettings)' || true)
            [ -z "${'$'}installed" ] || apt-get install -y --reinstall --no-install-recommends ${'$'}installed
        fi
        # Firefox's interface in that language, when Debian has it, in the same
        # apt run: the app's progress follows one run.
        l10n=
        if apt-cache show firefox-esr-l10n-$language > /dev/null 2>&1; then l10n=firefox-esr-l10n-$language; fi
        apt-get install -y --no-install-recommends $packages ${'$'}l10n
        apt-get clean
        mkdir -p /${PACKAGES_MARKER.substringBeforeLast('/')}
        echo '$packages' > /$PACKAGES_MARKER
        echo DROIDNUX_INSTALL_OK
        """.trimIndent()
    }

    /** True when the desktop packages are present in [rootfs]. */
    fun isInstalled(rootfs: File): Boolean = File(rootfs, "usr/bin/startplasma-wayland").exists()

    /** False when this version of the app needs packages the last install did not have. */
    fun isUpToDate(rootfs: File): Boolean =
        runCatching { File(rootfs, PACKAGES_MARKER).readText().trim().split(" ").toSet() }.getOrNull() ==
            PACKAGES.toSet()

    /**
     * Writes the session script, DroidNux defaults and guest libraries into
     * [rootfs]; [nativeLibraryDir] is the app's, where the guest libraries
     * ship. Safe to call before every session; user settings are never
     * overwritten.
     */
    fun prepare(rootfs: File, nativeLibraryDir: File) {
        writeFile(rootfs, SESSION_SCRIPT, SESSION.toByteArray(), mode = 0x1ED /* 0755 */)
        writeFile(rootfs, APPS_SCRIPT, APPS.toByteArray(), mode = 0x1ED)
        writeFile(
            rootfs, "$GUEST_LIB_DIR/$KWIN_LIBRARY", File(nativeLibraryDir, KWIN_LIBRARY).readBytes(),
            mode = 0x1A4,
        )
        writeFile(rootfs, "$PIPEWIRE_MODULE_DIR/$PIPEWIRE_MODULE", File(nativeLibraryDir, PIPEWIRE_MODULE).readBytes())
        writeFile(rootfs, PIPEWIRE_CONF, PIPEWIRE_DEFAULTS.toByteArray())
        writeFile(rootfs, PIPEWIRE_CLIENT_CONF, PIPEWIRE_CLIENT_DEFAULTS.toByteArray())
        writeFile(rootfs, WIREPLUMBER_CONF, WIREPLUMBER_DEFAULTS.toByteArray())
        writeFile(rootfs, "$PLASMA_DEFAULTS/kdeglobals", "[KDE]\nLookAndFeelPackage=org.droidnux.desktop\n".toByteArray())
        writeFile(rootfs, "$PLASMA_DEFAULTS/kwinrc", PLASMA_KWINRC.toByteArray())
        writeFile(rootfs, "$PLASMA_DEFAULTS/kscreenlockerrc", PLASMA_SCREENLOCKER.toByteArray())
        writeFile(rootfs, "$PLASMA_DEFAULTS/kwalletrc", PLASMA_KWALLETRC.toByteArray())
        writeFile(rootfs, WALLET_SCRIPT, WALLET.toByteArray(), mode = 0x1ED)
        writeFile(rootfs, UPOWER_SCRIPT, UPOWER.toByteArray(), mode = 0x1ED)
        writeFile(rootfs, SYSTEM_BUS_CONF, SYSTEM_BUS.toByteArray())
        writeFile(rootfs, "$PLASMA_DEFAULTS/powerdevilrc", PLASMA_POWERDEVILRC.toByteArray())
        writeFile(rootfs, IM_DESKTOP_FILE, IM_DESKTOP.toByteArray())
        writeFile(rootfs, IM_PROGRAM, File(nativeLibraryDir, IM_LIBRARY).readBytes(), mode = 0x1ED)
        writeFile(rootfs, "$PLASMA_LOOK_AND_FEEL/metadata.json", PLASMA_LNF_METADATA.toByteArray())
        writeFile(rootfs, "$PLASMA_LOOK_AND_FEEL/contents/defaults", PLASMA_LNF_DEFAULTS.toByteArray())
        writeFile(rootfs, "$PLASMA_LOOK_AND_FEEL/contents/layouts/org.kde.plasma.desktop-layout.js", PLASMA_LAYOUT.toByteArray())
        writeFile(rootfs, PLASMA_BATTERY_UPDATE, PLASMA_BATTERY.toByteArray())
        writeFile(rootfs, FIREFOX_PREFS_PATH, FIREFOX_PREFS.toByteArray())
        OBSOLETE_FILES.forEach { removeFile(rootfs, it) }
    }

    /** Printed by the session script when the desktop logs out. */
    const val SESSION_END_MARKER = "DROIDNUX_SESSION_END"

    /** Printed by Plasma's session once KDE's wallet opens with the new password (see [sessionCommand]). */
    const val WALLET_MOVED_MARKER = "DROIDNUX_WALLET_MOVED"

    /**
     * Command starting the session, with the X keyboard [layout] (e.g. "it"),
     * as [user] (a valid Linux user name; empty: the default one). The
     * session reads the user's password from its input, one line, then the
     * password before it (empty line if it did not change): KDE's wallet
     * moves to the new one, and [WALLET_MOVED_MARKER] says so.
     */
    fun sessionCommand(layout: String, user: String = ""): String =
        "DROIDNUX_XKB_LAYOUT=$layout DROIDNUX_LOCALE=${glibcLocale()} " +
            (if (user.isNotEmpty()) "DROIDNUX_USER=$user " else "") +
            "exec $SESSION_SCRIPT"

    /** Android's language as a glibc locale name ("it_IT"); the session checks Debian has it. */
    private fun glibcLocale(): String {
        val locale = java.util.Locale.getDefault()
        val name = "${locale.language}_${locale.country.ifEmpty { locale.language.uppercase() }}"
        return name.takeIf { Regex("[a-z]{2,3}_[A-Z]{2}").matches(it) } ?: "C"
    }

    /** A launchable app of the desktop, from its .desktop file. */
    data class LinuxApp(val id: String, val name: String, val categories: List<String>)

    /** Command listing the desktop's apps (see [parseApps]), named in [language] when they can be. */
    fun appsCommand(language: String): String =
        "$APPS_SCRIPT ${language.filter { it.isLetter() }.ifEmpty { "en" }}"

    fun parseApps(output: String): List<LinuxApp> = output.lineSequence().mapNotNull { line ->
        val fields = line.split('\t')
        if (fields.size < 3 || !isDesktopId(fields[0])) return@mapNotNull null
        LinuxApp(fields[0], fields[1], fields[2].split(';').filter { it.isNotEmpty() })
    }.toList()

    /** The icon [APPS_SCRIPT] keeps for an app, if any. */
    fun iconFile(rootfs: File, id: String): File? {
        if (!isDesktopId(id)) return null
        val file = File(rootfs.canonicalFile, "${ICON_CACHE.removePrefix("/")}/$id.png")
        // Read only real files inside the rootfs, never through a guest link.
        return file.takeIf { it.isFile && it.canonicalPath == it.path }
    }

    /**
     * Asks the running session to start the app [id]. False when no session
     * listens yet (none running, or still starting).
     */
    fun requestLaunch(rootfs: File, id: String): Boolean {
        if (!isDesktopId(id)) return false
        val fifo = File(rootfs, LAUNCH_FIFO.removePrefix("/"))
        if (java.nio.file.Files.isSymbolicLink(fifo.toPath())) return false
        return try {
            // Non-blocking: with no reader the open fails (ENXIO) instead of waiting.
            val fd = Os.open(fifo.path, OsConstants.O_WRONLY or OsConstants.O_NONBLOCK, 0)
            try {
                val line = "$id\n".toByteArray()
                Os.write(fd, line, 0, line.size) == line.size
            } finally {
                Os.close(fd)
            }
        } catch (_: ErrnoException) {
            false
        }
    }

    private fun isDesktopId(id: String) = id.isNotEmpty() && id.all { it.isLetterOrDigit() || it in "._-" }

    private val APPS = """
        #!/bin/sh
        # Written by DroidNux: lists the desktop's applications for the app, one per
        # line (desktop id, name in the language ${'$'}1, categories; tab-separated), and
        # keeps a PNG of each one's icon in $ICON_CACHE/<id>.png for Android.
        CACHE=$ICON_CACHE
        mkdir -p "${'$'}CACHE"
        lang=${'$'}{1:-en}
        tab=${'$'}(printf '\t')

        find_icon() {
            case "${'$'}1" in
            /*) [ -f "${'$'}1" ] && echo "${'$'}1"; return ;;
            '') return ;;
            esac
            for dir in /usr/share/icons/breeze-dark/apps/48 /usr/share/icons/breeze/apps/48 \
                /usr/share/icons/hicolor/scalable/apps /usr/share/icons/hicolor/256x256/apps \
                /usr/share/icons/hicolor/128x128/apps /usr/share/icons/hicolor/64x64/apps \
                /usr/share/icons/hicolor/48x48/apps /usr/share/pixmaps; do
                for ext in svg png; do
                    [ -f "${'$'}dir/${'$'}1.${'$'}ext" ] && { echo "${'$'}dir/${'$'}1.${'$'}ext"; return; }
                done
            done
        }

        render() {
            src=${'$'}(find_icon "${'$'}1")
            case "${'$'}src" in
            *.svg) rsvg-convert -w 96 -h 96 -o "${'$'}2.tmp" "${'$'}src" 2> /dev/null && mv "${'$'}2.tmp" "${'$'}2" ;;
            *.png) cp "${'$'}src" "${'$'}2.tmp" && mv "${'$'}2.tmp" "${'$'}2" ;;
            *) rm -f "${'$'}2" ;;
            esac
        }

        for f in /usr/local/share/applications/*.desktop /usr/share/applications/*.desktop; do
            [ -f "${'$'}f" ] || continue
            id=${'$'}(basename "${'$'}f" .desktop)
            awk -F= -v lang="${'$'}lang" '
                /^\[/ { section = ${'$'}0; next }
                section != "[Desktop Entry]" { next }
                ${'$'}1 == "Type" { type = ${'$'}2 }
                ${'$'}1 == "Name" { name = substr(${'$'}0, 6) }
                ${'$'}1 == "Name[" lang "]" { lname = substr(${'$'}0, length(lang) + 8) }
                ${'$'}1 == "Icon" { icon = substr(${'$'}0, 6) }
                (${'$'}1 == "NoDisplay" || ${'$'}1 == "Hidden") && ${'$'}2 == "true" { hidden = 1 }
                ${'$'}1 == "OnlyShowIn" && ${'$'}2 !~ /(^|;)KDE(;|${'$'})/ { hidden = 1 }
                ${'$'}1 == "NotShowIn" && ${'$'}2 ~ /(^|;)KDE(;|${'$'})/ { hidden = 1 }
                ${'$'}1 == "Categories" { cats = ${'$'}2 }
                END { if (type == "Application" && !hidden && name != "") print (lname != "" ? lname : name) "\t" icon "\t" cats }
            ' "${'$'}f" | while IFS="${'$'}tab" read -r name icon cats; do
                png=${'$'}CACHE/${'$'}id.png
                if [ ! -s "${'$'}png" ] || [ "${'$'}f" -nt "${'$'}png" ]; then render "${'$'}icon" "${'$'}png"; fi
                printf '%s\t%s\t%s\n' "${'$'}id" "${'$'}name" "${'$'}cats"
            done
        done
    """.trimIndent() + "\n"

    /** Session script part: DroidNux's Mesa for the session's apps. */
    private val GPU_ENV = """
        # GPU: DroidNux's Mesa (Turnip, and Zink for OpenGL) when installed,
        # otherwise the distro's software rendering.
        MESA=$MESA_PREFIX
        if [ -f "${'$'}MESA/droidnux-mesa.version" ]; then
            export LD_LIBRARY_PATH=${'$'}MESA/lib/aarch64-linux-gnu${'$'}{LD_LIBRARY_PATH:+:${'$'}LD_LIBRARY_PATH}
            export VK_DRIVER_FILES=${'$'}MESA/share/vulkan/icd.d/freedreno_icd.aarch64.json
            export __GLX_VENDOR_LIBRARY_NAME=droidnux
            export __EGL_VENDOR_LIBRARY_FILENAMES=${'$'}MESA/share/glvnd/egl_vendor.d/50_droidnux.json
            export MESA_LOADER_DRIVER_OVERRIDE=zink
            export GALLIUM_DRIVER=zink
            # Samsung's KGSL (One UI) needs Turnip's UBWC flag hint: without
            # it, whole frames flash while Firefox scrolls (WebRender on Zink).
            export FD_DEV_FEATURES=enable_tp_ubwc_flag_hint=1
            # Swapchain images in AHardwareBuffers from the app, which shows
            # them without copying (Mesa patch 0003).
            export DROIDNUX_AHB_SOCKET=/tmp/.droidnux-ahb
            # Video decoding by the app's MediaCodec (guest/va), for players
            # like mpv and ffmpeg; Firefox keeps software decoding (droidnux.js).
            if [ -f "${'$'}MESA/lib/aarch64-linux-gnu/dri/droidnux_drv_video.so" ]; then
                export LIBVA_DRIVERS_PATH=${'$'}MESA/lib/aarch64-linux-gnu/dri
                export LIBVA_DRIVER_NAME=droidnux
            fi
        else
            # With DRI3 but no device, Debian's GLX tries Zink; with no
            # Vulkan driver Zink fails and Mesa 25.0 deadlocks on its own
            # instance_lock in the error path (zink_destroy_screen): X11 apps
            # on Xwayland would hang. Plain software GLX instead.
            export LIBGL_DRI3_DISABLE=1
        fi
    """.trimIndent()

    /** Session script part: Android's language. */
    private val LOCALE_ENV = """
        # Android's language, once its locale is generated (about a second, the first time).
        if [ -f /usr/share/i18n/locales/${'$'}DROIDNUX_LOCALE ]; then
            locale -a | grep -qx "${'$'}DROIDNUX_LOCALE.utf8" ||
                localedef -i "${'$'}DROIDNUX_LOCALE" -c -f UTF-8 "${'$'}DROIDNUX_LOCALE.UTF-8"
            export LANG=${'$'}DROIDNUX_LOCALE.UTF-8
        fi
    """.trimIndent()

    /** Session script part: the device's storage in the home folder. */
    private val SDCARD_LINK = """
        # The device's storage in the home folder (when the app may access it).
        if [ -d $GUEST_SDCARD ] && [ ! -e "${'$'}HOME/Android" ] && [ ! -L "${'$'}HOME/Android" ]; then
            ln -s $GUEST_SDCARD "${'$'}HOME/Android"
        fi
    """.trimIndent()

    /** Session script part, inside D-Bus: sound. */
    private val SESSION_SERVICES = """
        # Session services without systemd start here, on the session bus.
        if command -v pipewire > /dev/null; then
            # Its clients give up if its socket is not there yet: wait for it,
            # not for the one the last session left behind.
            rm -f "${'$'}XDG_RUNTIME_DIR/pipewire-0" "${'$'}XDG_RUNTIME_DIR/pipewire-0-manager"
            pipewire &
            i=0
            while [ ! -S "${'$'}XDG_RUNTIME_DIR/pipewire-0" ] && [ ${'$'}i -lt 50 ]; do sleep 0.1; i=${'$'}((i + 1)); done
            wireplumber &
            pipewire-pulse &
        fi
    """.trimIndent()

    /** Session script part, inside D-Bus: apps the app starts, once the process LAUNCH_AFTER runs. */
    private val LAUNCH_LOOP = """
        # Apps the app starts (its app list, Android shortcuts), by desktop id: they
        # must run in this session, which a separate runtime would leave when it exits.
        rm -f $LAUNCH_FIFO && mkfifo -m 600 $LAUNCH_FIFO
        # Apps started before the desktop shares its theme keep default icons.
        # They get LAUNCH_AFTER's environment, as the desktop's launchers do: under
        # Plasma, ours names the app's compositor instead of KWin.
        (until pgrep -x ${'$'}LAUNCH_AFTER > /dev/null; do sleep 0.2; done; sleep 1
        while :; do
            while read -r id; do
                case "${'$'}id" in ''|*[!A-Za-z0-9._-]*) continue ;; esac
                p=${'$'}(pgrep -x ${'$'}LAUNCH_AFTER | head -1)
                for d in /usr/local/share/applications /usr/share/applications; do
                    [ -f "${'$'}d/${'$'}id.desktop" ] || continue
                    # In a subshell that exits at once: the app is orphaned and reaped
                    # by the runtime, not left a zombie of this loop.
                    if [ -r "/proc/${'$'}p/environ" ]; then
                        (xargs -0 -a "/proc/${'$'}p/environ" sh -c 'exec env -i "${'$'}@" gio launch "${'$'}0"' "${'$'}d/${'$'}id.desktop" &)
                    else
                        (gio launch "${'$'}d/${'$'}id.desktop" &)
                    fi
                    break
                done
            done < $LAUNCH_FIFO
        done) &
    """.trimIndent()

    /**
     * Plasma session part: plasmashell back when it exits during the session. KWin
     * drops a client on a protocol error and Qt then exits instead of crashing, so
     * KCrash does not restart it and the desktop stays black. Waits with tail --pid
     * (one syscall a second) rather than polling pgrep.
     */
    private val PLASMASHELL_WATCH = """
        (until p=${'$'}(pgrep -x plasmashell | head -1) && [ -n "${'$'}p" ]; do sleep 1; done
        sleep 2
        env=${'$'}XDG_RUNTIME_DIR/.droidnux-plasmashell-environ
        # The copy keeps environ's mode (0400): the last session's cannot be overwritten.
        rm -f "${'$'}env" && cp /proc/${'$'}p/environ "${'$'}env" || exit
        n=0
        while [ ${'$'}n -lt 20 ]; do
            tail --pid="${'$'}p" -f /dev/null
            # At logout ksmserver goes too: leave plasmashell gone.
            sleep 2
            pgrep -x ksmserver > /dev/null || exit
            p=${'$'}(pgrep -x plasmashell | head -1)
            if [ -z "${'$'}p" ]; then
                echo "plasmashell exited, restarting it"
                xargs -0 -a "${'$'}env" sh -c 'exec env -i "${'$'}@" plasmashell' sh > /dev/null 2>&1 &
                p=${'$'}!
                n=${'$'}((n + 1))
            fi
        done) &
    """.trimIndent()

    /**
     * Plasma session part, as root: the user the desktop runs as
     * (some apps refuse root, VLC among them), DROIDNUX_USER, made on first use
     * with the settings root had (or renamed from the account made before);
     * the password the app sends on the input line becomes its password and
     * sudo's (when new to the account: one changed in Linux stays), and the key of KDE's wallet, opened in --in-dbus as pam_kwallet
     * would at login (DROIDNUX_WALLET; DROIDNUX_WALLET_BEFORE when the app
     * also sends the password before, on a second line). Then the script again with the user's
     * ids (perl: setpriv needs capabilities Android refuses).
     */
    private val USER_SESSION = """
        if [ "${'$'}(id -u)" = 0 ] && [ "${'$'}1" != --in-dbus ]; then
            user=${'$'}{DROIDNUX_USER:-$DESKTOP_USER}
            pass= before=
            IFS= read -r pass || true
            IFS= read -r before || true
            # Qt temporary files and directories (name.XXXXXX) of the last session, emptied
            # but never removed: the session is killed, and /tmp is not cleared at boot.
            find /tmp -mindepth 1 -maxdepth 1 -empty -name '*.??????' -delete 2> /dev/null
            # The account made before this one, by DroidNux (its comment).
            old=${'$'}(getent passwd | awk -F: '${'$'}5 == "DroidNux" { print ${'$'}1; exit }')
            if ! id -u "${'$'}user" > /dev/null 2>&1 && [ -n "${'$'}old" ]; then
                oldhome=${'$'}(getent passwd "${'$'}old" | cut -d: -f6)
                # Lines with \x escapes are binary values (Qt streams with length
                # prefixes, Dolphin's session among them): rewriting breaks them,
                # the link keeps their old paths working.
                usermod -l "${'$'}user" -d "/home/${'$'}user" -m "${'$'}old" && groupmod -n "${'$'}user" "${'$'}old" &&
                    ln -s "/home/${'$'}user" "${'$'}oldhome" &&
                    grep -rlI --exclude-dir=mozilla -e "${'$'}oldhome/" "/home/${'$'}user/.config" 2> /dev/null |
                        xargs -r sed -i "/[\\]x/!s|${'$'}oldhome/|/home/${'$'}user/|g"
            fi
            if ! id -u "${'$'}user" > /dev/null 2>&1; then
                useradd -m -s /bin/bash -c DroidNux "${'$'}user"
                for g in sudo audio video render input; do
                    getent group ${'$'}g > /dev/null && usermod -a -G ${'$'}g "${'$'}user"
                done
                home=${'$'}(getent passwd "${'$'}user" | cut -d: -f6)
                # The settings of the desktop that ran as root, once.
                for f in .config .local .mozilla .thunderbird .ssh .gtkrc-2.0 .vscode; do
                    [ -e "/root/${'$'}f" ] && cp -a "/root/${'$'}f" "${'$'}home/"
                done
                grep -rlI --exclude-dir=mozilla -e /root/ -e file:///root "${'$'}home/.config" "${'$'}home/.local/share/user-places.xbel" 2> /dev/null |
                    xargs -r sed -i "s|file:///root\b|file://${'$'}home|g; s|=/root/|=${'$'}home/|g; s|\"/root/|\"${'$'}home/|g"
            fi
            home=${'$'}(getent passwd "${'$'}user" | cut -d: -f6)
            unset DROIDNUX_WALLET DROIDNUX_WALLET_BEFORE
            if [ -n "${'$'}pass" ]; then
                # The app's password becomes the account's when it is new to it (first start, changed in the
                # app): one changed in Linux (passwd, Plasma's settings) stays. The stamp is the hash the
                # app's password got last; the password goes on perl's input, not its command line.
                stamp=$PASSWORD_STAMP
                last=${'$'}(cat "${'$'}stamp" 2> /dev/null) || last=${'$'}(getent shadow "${'$'}user" | cut -d: -f2)
                if ! printf '%s\n%s' "${'$'}last" "${'$'}pass" | perl -e 'chomp(my ${'$'}h = <STDIN>); local ${'$'}/; my ${'$'}p = <STDIN>;
                        exit(${'$'}h ne "" && crypt(${'$'}p, ${'$'}h) eq ${'$'}h ? 0 : 1)'; then
                    printf '%s:%s\n' "${'$'}user" "${'$'}pass" | chpasswd || echo "chpasswd failed"
                    last=${'$'}(getent shadow "${'$'}user" | cut -d: -f2)
                fi
                mkdir -p "${'$'}{stamp%/*}" && (umask 077 && printf '%s\n' "${'$'}last" > "${'$'}stamp")
                sudo="ALL=(ALL:ALL) ALL"
                # pam_kwallet's key: PBKDF2-HMAC-SHA512 of the password, 50000 rounds, the wallet's salt.
                wallets=${'$'}home/.local/share/kwalletd
                mkdir -p "${'$'}wallets"
                [ -s "${'$'}wallets/kdewallet.salt" ] || head -c 56 /dev/urandom > "${'$'}wallets/kdewallet.salt"
                chown -R "${'$'}user:" "${'$'}home/.local"
                walletkey() {
                    printf %s "${'$'}1" | perl -MDigest::SHA=hmac_sha512 -e '
                        local ${'$'}/; my ${'$'}key = <STDIN>; open my ${'$'}f, "<", ${'$'}ARGV[0] or die "salt: ${'$'}!\n"; binmode ${'$'}f;
                        my ${'$'}salt = <${'$'}f>; my ${'$'}u = hmac_sha512(${'$'}salt . pack("N", 1), ${'$'}key); my ${'$'}t = ${'$'}u;
                        for (2 .. 50000) { ${'$'}u = hmac_sha512(${'$'}u, ${'$'}key); ${'$'}t ^= ${'$'}u }
                        print "[", join(", ", unpack("C56", ${'$'}t)), "]";' "${'$'}wallets/kdewallet.salt"
                }
                DROIDNUX_WALLET=${'$'}(walletkey "${'$'}pass") && export DROIDNUX_WALLET
                # The password changed in the app: the wallet still opens with the one before.
                if [ -n "${'$'}before" ] && [ "${'$'}before" != "${'$'}pass" ]; then
                    DROIDNUX_WALLET_BEFORE=${'$'}(walletkey "${'$'}before") && export DROIDNUX_WALLET_BEFORE
                fi
            else
                sudo="ALL=(ALL:ALL) NOPASSWD: ALL"
            fi
            unset pass before
            if [ -d /etc/sudoers.d ]; then
                echo "${'$'}user ${'$'}sudo" > /etc/sudoers.d/droidnux && chmod 440 /etc/sudoers.d/droidnux
            fi
            # A system bus with UPower on it, as root: Plasma shows Android's battery.
            mkdir -p ${SYSTEM_BUS_SOCKET.substringBeforeLast('/')} && rm -f $SYSTEM_BUS_SOCKET
            if dbus-daemon --config-file=$SYSTEM_BUS_CONF --fork --nopidfile; then
                export DBUS_SYSTEM_BUS_ADDRESS=unix:path=$SYSTEM_BUS_SOCKET
                $UPOWER_SCRIPT &
            fi
            # Burrow starts in /root: apps launched from Plasma (Konsole) inherit the directory.
            cd "${'$'}home" || true
            exec perl -e '${'$'}u = shift; @g = split / /, shift; ${'$'}) = "${'$'}g[0] @g"; ${'$'}( = ${'$'}g[0]; ${'$'}< = ${'$'}> = ${'$'}u;
                die "cannot become ${'$'}u\n" if ${'$'}> != ${'$'}u; exec @ARGV or die "exec: ${'$'}!\n"' \
                "${'$'}(id -u "${'$'}user")" "${'$'}(id -G "${'$'}user")" \
                env HOME="${'$'}home" USER="${'$'}user" LOGNAME="${'$'}user" "${'$'}0" "${'$'}@"
        fi
    """.trimIndent()

    private val SESSION = """
        #!/bin/sh
        # Written by DroidNux: starts Plasma, its KWin nested on the app's
        # Wayland compositor. Runs twice: first to set up and start D-Bus, then
        # inside it (--in-dbus).
${USER_SESSION.prependIndent("        ")}
        if [ "${'$'}1" != --in-dbus ]; then
            export XDG_RUNTIME_DIR=/tmp/runtime-${'$'}(id -u)
            # The app's compositor; KWin puts its own socket in XDG_RUNTIME_DIR.
            export WAYLAND_DISPLAY=/tmp/wayland-$DISPLAY
            export XDG_SESSION_TYPE=wayland
            export SHELL=/bin/bash
            export XDG_CONFIG_DIRS=$PLASMA_DEFAULTS:/etc/xdg
            # Android's SELinux denies reopening a memfd through /proc/self/fd,
            # so Firefox's parent process falls back to unsealed shm_open while
            # its children still demand F_SEAL_SHRINK: every tab crashes with
            # "Shared memory PlatformHandle is not safe to map" (Firefox 154+).
            export MOZ_SHM_NO_SEALS=1
            # Electron apps (VS Code, Discord, Obsidian): Chromium's sandbox needs user
            # namespaces or its set-user-ID helper, neither of which Android allows.
            export ELECTRON_DISABLE_SANDBOX=1
${GPU_ENV.prependIndent("            ")}
            if [ -f "${'$'}MESA/droidnux-mesa.version" ]; then
                # KWin renders into GBM buffers from the app (Mesa patch 0004).
                export GBM_BACKENDS_PATH=${'$'}MESA/lib/aarch64-linux-gnu/gbm
            fi
            # KSvg and KIconThemes with DroidNux's patches (Plasma component),
            # while the distro's are the versions they were built from (the
            # "<package> <version>" lines after the component's version).
            PLASMA=$PLASMA_PREFIX
            if [ -f "${'$'}PLASMA/droidnux-plasma.version" ] && [ "${'$'}(sed 1d "${'$'}PLASMA/droidnux-plasma.version")" = \
                "${'$'}(sed 1d "${'$'}PLASMA/droidnux-plasma.version" | while read -r package version; do
                    echo "${'$'}package ${'$'}(dpkg-query -W -f '${'$'}{Version}' "${'$'}package" 2> /dev/null)"; done)" ]; then
                export LD_LIBRARY_PATH=${'$'}PLASMA/lib/aarch64-linux-gnu${'$'}{LD_LIBRARY_PATH:+:${'$'}LD_LIBRARY_PATH}
                # ksystemstats' GPU and disk plugins for Android, found before the distro's of the same name.
                export QT_PLUGIN_PATH=${'$'}PLASMA/lib/aarch64-linux-gnu/qt6/plugins${'$'}{QT_PLUGIN_PATH:+:${'$'}QT_PLUGIN_PATH}
            fi
            # Turnip on KGSL has no calibrated timestamps: Zink answers
            # GL_TIMESTAMP with a GPU round trip, which KWin asks every frame.
            export KWIN_NO_TIMER_QUERY=1
            # KWin's render time prediction (guest/kwin-pacing): clients at the full refresh rate.
            export LD_PRELOAD=$GUEST_LIB_DIR/$KWIN_LIBRARY${'$'}{LD_PRELOAD:+:${'$'}LD_PRELOAD}
            mkdir -p "${'$'}XDG_RUNTIME_DIR" && chmod 700 "${'$'}XDG_RUNTIME_DIR"
            # The wallet's key, from the environment (D-Bus would pass it to every service) to a private file.
            if [ -n "${'$'}DROIDNUX_WALLET" ]; then
                (umask 077 && printf '%s\n%s\n' "${'$'}DROIDNUX_WALLET" "${'$'}DROIDNUX_WALLET_BEFORE" > "${'$'}XDG_RUNTIME_DIR/.droidnux-wallet")
                unset DROIDNUX_WALLET DROIDNUX_WALLET_BEFORE
            fi
            # Xwayland's sockets, made by KWin.
            mkdir -p /tmp/.X11-unix && chmod 1777 /tmp/.X11-unix

            # The compositor starts with the app; wait for its socket.
            i=0
            while [ ! -S "${'$'}WAYLAND_DISPLAY" ] && [ ${'$'}i -lt 100 ]; do sleep 0.1; i=${'$'}((i + 1)); done

${LOCALE_ENV.prependIndent("            ")}

            # The keyboard layout: KWin's own (kxkbrc, unless the user set one), and
            # the keymap the app's compositor sends, which it reads on first use.
            config=${'$'}{XDG_CONFIG_HOME:-${'$'}HOME/.config}
            mkdir -p "${'$'}config"
            layout=${'$'}{DROIDNUX_XKB_LAYOUT:-us}
            [ -f "${'$'}config/kxkbrc" ] || printf '[Layout]\nLayoutList=%s\nUse=true\n' "${'$'}layout" > "${'$'}config/kxkbrc"
            printf 'xkb_keymap { xkb_keycodes { include "evdev+aliases(qwerty)" }; xkb_types { include "complete" };
                xkb_compat { include "complete" }; xkb_symbols { include "pc+%s+inet(evdev)" }; };\n' "${'$'}layout" |
                xkbcomp -w 0 -xkb - /tmp/.droidnux-keymap.xkb || echo "keymap for ${'$'}layout failed"
            # Without systemd: Plasma starts its services itself.
            [ -f "${'$'}config/startkderc" ] || printf '[General]\nsystemdBoot=false\n' > "${'$'}config/startkderc"

${SDCARD_LINK.prependIndent("            ")}

            exec dbus-run-session "${'$'}0" --in-dbus
        fi

        # KDE's wallet, opened with the account's password (USER_SESSION) as pam_kwallet does at login.
        if [ -f "${'$'}XDG_RUNTIME_DIR/.droidnux-wallet" ]; then
            key=${'$'}(sed -n 1p "${'$'}XDG_RUNTIME_DIR/.droidnux-wallet")
            before=${'$'}(sed -n 2p "${'$'}XDG_RUNTIME_DIR/.droidnux-wallet")
            rm -f "${'$'}XDG_RUNTIME_DIR/.droidnux-wallet"
            if [ -n "${'$'}before" ]; then
                # The keys on its input: the command line is visible to every process.
                (printf '%s\n%s\n' "${'$'}key" "${'$'}before" | $WALLET_SCRIPT "${'$'}HOME/.local/share/kwalletd" &&
                    echo $WALLET_MOVED_MARKER) &
            else
                gdbus call --session --dest org.kde.kwalletd6 --object-path /modules/kwalletd6 \
                    --method org.kde.KWallet.pamOpen kdewallet "${'$'}key" 0 > /dev/null 2>&1 &
            fi
            unset key before
        fi
${SESSION_SERVICES.prependIndent("        ")}
        LAUNCH_AFTER=plasmashell
${LAUNCH_LOOP.prependIndent("        ")}
${PLASMASHELL_WATCH.prependIndent("        ")}
        startplasma-wayland
        echo $SESSION_END_MARKER
    """.trimIndent() + "\n"

    /** KWin: Android's keyboard as the input method (IM_DESKTOP). */
    private val PLASMA_KWINRC = """
        [Wayland]
        InputMethod=$IM_DESKTOP_FILE
        VirtualKeyboardEnabled=true
    """.trimIndent() + "\n"

    /** One wallet, opened at the session's start, that apps use without asking. */
    private val PLASMA_KWALLETRC = """
        [Wallet]
        First Use=false
        Enabled=true
        Use One Wallet=true
        Default Wallet=kdewallet
        Prompt on Open=false
        Close When Idle=false
        Leave Open=true
    """.trimIndent() + "\n"

    /**
     * Opens KDE's wallet with the account's key when the password changed in
     * the app: kwalletd only changes a password through its own dialog, so
     * the wallet opens with the key of the password before, and its entries
     * move to a wallet made with the new key (same salt), which takes its place.
     */
    private val WALLET = """
        #!/usr/bin/python3
        # Written by DroidNux: moves KDE's wallet to the account's new password.
        #   droidnux-wallet <wallet dir>, with the new key and the key before on its
        #   input, one line each: "[b0, b1, ...]", pam_kwallet's PBKDF2 of the password.
        # Exits 0 once the wallet is open with the new key.
        import os
        import shutil
        import sys
        import time

        from gi.repository import Gio, GLib

        WALLET = "kdewallet"
        TEMP = "droidnux-moved"
        APP = "DroidNux"

        bus = Gio.bus_get_sync(Gio.BusType.SESSION)


        def call(method, signature, args, reply):
            result = bus.call_sync("org.kde.kwalletd6", "/modules/kwalletd6", "org.kde.KWallet", method,
                                   GLib.Variant(signature, args), GLib.VariantType(reply), 0, 30000, None)
            return result.unpack()[0] if reply != "()" else None


        def key(line):
            return bytes(int(b) for b in line.strip().strip("[]").split(","))


        def open_with(wallet, k):
            # pamOpen does not say whether it opened the wallet, and kwalletd
            # ignores it while it handles another request: try for a while.
            for _ in range(15):
                call("pamOpen", "(sayi)", (wallet, k, 0), "()")
                if call("isOpen", "(s)", (wallet,), "(b)"):
                    return True
                time.sleep(0.2)
            return False


        def fail(message):
            print(f"droidnux-wallet: {message}", file=sys.stderr)
            sys.exit(1)


        directory = sys.argv[1]
        new, before = key(sys.stdin.readline()), key(sys.stdin.readline())
        # kwalletd starts on the first call.
        for _ in range(50):
            try:
                call("isOpen", "(s)", (WALLET,), "(b)")
                break
            except GLib.Error:
                time.sleep(0.2)

        if open_with(WALLET, new):
            sys.exit(0)
        if not open_with(WALLET, before):
            fail("the wallet opens with neither password")
        for suffix in (".kwl", ".salt"):
            if os.path.exists(f"{directory}/{TEMP}{suffix}"):
                os.remove(f"{directory}/{TEMP}{suffix}")
        shutil.copyfile(f"{directory}/{WALLET}.salt", f"{directory}/{TEMP}.salt")
        if not open_with(TEMP, new):
            fail("cannot make the new wallet")
        source = call("open", "(sxs)", (WALLET, 0, APP), "(i)")
        target = call("open", "(sxs)", (TEMP, 0, APP), "(i)")
        count = 0
        for folder in call("folderList", "(is)", (source, APP), "(as)"):
            call("createFolder", "(iss)", (target, folder, APP), "(b)")
            for name, value in call("entriesList", "(iss)", (source, folder, APP), "(a{sv})").items():
                kind = call("entryType", "(isss)", (source, folder, name, APP), "(i)")
                if call("writeEntry", "(issayis)", (target, folder, name, bytes(value), kind, APP), "(i)") != 0:
                    fail(f"cannot copy {folder}/{name}")
                count += 1
        call("sync", "(is)", (target, APP), "()")
        call("close", "(sb)", (TEMP, True), "(i)")
        call("close", "(sb)", (WALLET, True), "(i)")
        os.replace(f"{directory}/{TEMP}.kwl", f"{directory}/{WALLET}.kwl")
        os.remove(f"{directory}/{TEMP}.salt")
        if not open_with(WALLET, new):
            fail("the moved wallet does not open")
        print(f"droidnux-wallet: {count} entries moved to the new password")
    """.trimIndent() + "\n"

    /**
     * Android's battery for the desktop's UPower ([UPOWER]): charge in percent,
     * [charging], [plugged] in, seconds to full (0 = unknown), °C and volts.
     * Written only when it changes; safe to call often.
     */
    fun writeBattery(rootfs: File, percent: Int, charging: Boolean, full: Boolean, plugged: Boolean, toFull: Long, celsius: Float, volts: Float) {
        val state = when {
            full -> "full"
            charging -> "charging"
            plugged -> "pending"
            else -> "discharging"
        }
        writeFile(
            rootfs, BATTERY_FILE,
            "percent=$percent\nstate=$state\nplugged=${if (plugged) 1 else 0}\nto_full=$toFull\ncelsius=$celsius\nvolts=$volts\n".toByteArray(),
        )
    }

    /** The system bus: no services started on demand, and everyone may talk to everyone (one user, in a container). */
    private val SYSTEM_BUS = """
        <!DOCTYPE busconfig PUBLIC "-//freedesktop//DTD D-Bus Bus Configuration 1.0//EN"
         "http://www.freedesktop.org/standards/dbus/1.0/busconfig.dtd">
        <!-- Written by DroidNux. -->
        <busconfig>
          <type>system</type>
          <listen>unix:path=$SYSTEM_BUS_SOCKET</listen>
          <auth>EXTERNAL</auth>
          <auth>ANONYMOUS</auth>
          <allow_anonymous/>
          <policy context="default">
            <allow user="*"/>
            <allow own="*"/>
            <allow send_destination="*" eavesdrop="true"/>
            <allow receive_sender="*"/>
          </policy>
        </busconfig>
    """.trimIndent() + "\n"

    /**
     * UPower with one battery and one charger, from [BATTERY_FILE]: what Plasma's
     * battery applet and powerdevil read (Solid's UPower backend).
     */
    private val UPOWER = """
        #!/usr/bin/python3
        # Written by DroidNux: Android's battery as UPower (org.freedesktop.UPower)
        # on the system bus, from the state the app writes to $BATTERY_FILE.
        import time

        from gi.repository import Gio, GLib

        STATE = "$BATTERY_FILE"
        BASE = "/org/freedesktop/UPower"
        BATTERY = BASE + "/devices/battery_android"
        CHARGER = BASE + "/devices/line_power_android"
        DISPLAY = BASE + "/devices/DisplayDevice"
        DEVICE_PROPS = {
            "NativePath": "s", "Vendor": "s", "Model": "s", "Serial": "s", "UpdateTime": "t", "Type": "u",
            "PowerSupply": "b", "HasHistory": "b", "HasStatistics": "b", "Online": "b", "Energy": "d",
            "EnergyEmpty": "d", "EnergyFull": "d", "EnergyFullDesign": "d", "EnergyRate": "d", "Voltage": "d",
            "ChargeCycles": "i", "Luminosity": "d", "TimeToEmpty": "x", "TimeToFull": "x", "Percentage": "d",
            "Temperature": "d", "IsPresent": "b", "State": "u", "IsRechargeable": "b", "Capacity": "d",
            "Technology": "u", "WarningLevel": "u", "BatteryLevel": "u", "IconName": "s",
        }
        DAEMON_PROPS = {"DaemonVersion": "s", "OnBattery": "b", "LidIsClosed": "b", "LidIsPresent": "b"}
        # UPower's State values.
        STATES = {"charging": 1, "discharging": 2, "full": 4, "pending": 5}

        NODE = Gio.DBusNodeInfo.new_for_xml(
            '<node><interface name="org.freedesktop.UPower">'
            '<method name="EnumerateDevices"><arg type="ao" direction="out"/></method>'
            '<method name="GetDisplayDevice"><arg type="o" direction="out"/></method>'
            '<method name="GetCriticalAction"><arg type="s" direction="out"/></method>'
            '<signal name="DeviceAdded"><arg type="o"/></signal><signal name="DeviceRemoved"><arg type="o"/></signal>'
            + "".join(f'<property name="{n}" type="{t}" access="read"/>' for n, t in DAEMON_PROPS.items())
            + '</interface><interface name="org.freedesktop.UPower.Device"><method name="Refresh"/>'
            '<method name="GetHistory"><arg type="s" direction="in"/><arg type="u" direction="in"/>'
            '<arg type="u" direction="in"/><arg type="a(udu)" direction="out"/></method>'
            '<method name="GetStatistics"><arg type="s" direction="in"/><arg type="a(dd)" direction="out"/></method>'
            + "".join(f'<property name="{n}" type="{t}" access="read"/>' for n, t in DEVICE_PROPS.items())
            + "</interface></node>")
        DAEMON, DEVICE = NODE.interfaces


        def read_state():
            state = {}
            try:
                with open(STATE) as f:
                    for line in f:
                        key, _, value = line.strip().partition("=")
                        state[key] = value
            except OSError:
                pass
            return state


        def props(path, state):
            percent = float(state.get("percent", 0))
            plugged = state.get("plugged") == "1"
            if path == DAEMON_PATH:
                return {"DaemonVersion": "1.90.9", "OnBattery": not plugged, "LidIsClosed": False, "LidIsPresent": False}
            p = {n: {"s": "", "b": False, "d": 0.0}.get(t, 0) for n, t in DEVICE_PROPS.items()}
            p["UpdateTime"] = int(time.time())
            p["PowerSupply"] = True
            p["IsPresent"] = bool(state)
            p["WarningLevel"] = 1
            p["BatteryLevel"] = 1
            if path == CHARGER:
                p.update(NativePath="android-charger", Type=1, Online=plugged)
                return p
            charging = state.get("state") == "charging"
            discharging = state.get("state") == "discharging"
            p.update(NativePath="android-battery" if path == BATTERY else "", Type=2, Percentage=percent,
                     State=STATES.get(state.get("state"), 0), IsRechargeable=True, Technology=1, Capacity=100.0,
                     TimeToFull=int(state.get("to_full", 0)) if charging else 0,
                     Temperature=float(state.get("celsius", 0)), Voltage=float(state.get("volts", 0)),
                     WarningLevel=4 if discharging and percent <= 5 else 3 if discharging and percent <= 10 else 1,
                     IconName="battery-level-%d%s-symbolic" % (int(percent) // 10 * 10, "-charging" if charging else ""))
            return p


        DAEMON_PATH = BASE
        PATHS = [DAEMON_PATH, BATTERY, CHARGER, DISPLAY]
        current = read_state()
        bus = Gio.bus_get_sync(Gio.BusType.SYSTEM)


        def get_property(connection, sender, path, interface, name):
            props_of = DAEMON_PROPS if path == DAEMON_PATH else DEVICE_PROPS
            return GLib.Variant(props_of[name], props(path, current)[name])


        def method_call(connection, sender, path, interface, method, parameters, invocation):
            replies = {
                "EnumerateDevices": GLib.Variant("(ao)", ([BATTERY, CHARGER],)),
                "GetDisplayDevice": GLib.Variant("(o)", (DISPLAY,)),
                "GetCriticalAction": GLib.Variant("(s)", ("PowerOff",)),
                "GetHistory": GLib.Variant("(a(udu))", ([],)),
                "GetStatistics": GLib.Variant("(a(dd))", ([],)),
            }
            invocation.return_value(replies.get(method))


        def reload(*_):
            global current
            state = read_state()
            if state == current:
                return True
            before, current = current, state
            for path in PATHS:
                old, new = props(path, before), props(path, current)
                changed = {n: v for n, v in new.items() if n != "UpdateTime" and old[n] != v}
                if not changed:
                    continue
                types = DAEMON_PROPS if path == DAEMON_PATH else DEVICE_PROPS
                changed["UpdateTime"] = new.get("UpdateTime", 0)
                bus.emit_signal(None, path, "org.freedesktop.DBus.Properties", "PropertiesChanged", GLib.Variant(
                    "(sa{sv}as)", (DAEMON.name if path == DAEMON_PATH else DEVICE.name,
                                   {n: GLib.Variant(types[n], v) for n, v in changed.items() if n in types}, [])))
            return True


        for path in PATHS:
            bus.register_object(path, DAEMON if path == DAEMON_PATH else DEVICE, method_call, get_property, None)
        Gio.bus_own_name_on_connection(bus, "org.freedesktop.UPower", Gio.BusNameOwnerFlags.NONE, None, None)
        monitor = Gio.File.new_for_path(STATE).monitor_file(Gio.FileMonitorFlags.WATCH_MOVES, None)
        monitor.connect("changed", reload)
        # In case a change was missed.
        GLib.timeout_add_seconds(60, reload)
        GLib.MainLoop().run()
    """.trimIndent() + "\n"

    /** Android dims, locks and sleeps the device: powerdevil does none of it. */
    private val PLASMA_POWERDEVILRC = """
        [General]
        BatteryCriticalAction=0

        [AC][Display]
        DimDisplayWhenIdle=false
        TurnOffDisplayWhenIdle=false

        [AC][SuspendAndShutdown]
        AutoSuspendAction=0

        [Battery][Display]
        DimDisplayWhenIdle=false
        TurnOffDisplayWhenIdle=false

        [Battery][SuspendAndShutdown]
        AutoSuspendAction=0

        [LowBattery][Display]
        DimDisplayWhenIdle=false
        TurnOffDisplayWhenIdle=false

        [LowBattery][SuspendAndShutdown]
        AutoSuspendAction=0
    """.trimIndent() + "\n"

    /** No lock screen: Android locks the device. */
    private val PLASMA_SCREENLOCKER = """
        [Daemon]
        Autolock=false
        LockOnResume=false
    """.trimIndent() + "\n"

    private val IM_DESKTOP = """
        [Desktop Entry]
        Type=Application
        Name=Android keyboard
        Name[it]=Tastiera di Android
        Exec=$IM_PROGRAM
        NoDisplay=true
        X-KDE-Wayland-VirtualKeyboard=true
    """.trimIndent() + "\n"

    /** DroidNux's look-and-feel for Plasma: Breeze Dark, the default panel with only installed apps pinned. */
    private val PLASMA_LNF_METADATA = """
        {
            "KPackageStructure": "Plasma/LookAndFeel",
            "KPlugin": {
                "Id": "org.droidnux.desktop",
                "Name": "DroidNux",
                "Description": "Breeze Dark, set up for DroidNux",
                "License": "GPLv3+"
            }
        }
    """.trimIndent() + "\n"

    /** Breeze Dark's defaults (org.kde.breezedark.desktop). */
    private val PLASMA_LNF_DEFAULTS = """
        [kdeglobals][KDE]
        widgetStyle=Breeze

        [kdeglobals][General]
        ColorScheme=BreezeDark

        [kdeglobals][Icons]
        Theme=breeze-dark

        [plasmarc][Theme]
        name=default

        [Wallpaper]
        Image=Next

        [kcminputrc][Mouse]
        cursorTheme=breeze_cursors

        [kwinrc][org.kde.kdecoration2]
        library=org.kde.breeze
        theme=Breeze

        [KSplash]
        Theme=org.kde.Breeze
    """.trimIndent() + "\n"

    // The task manager's default launchers include Discover, which is not
    // installed (an empty icon).
    private val PLASMA_LAYOUT = """
        loadTemplate("org.kde.plasma.desktop.defaultPanel")

        for (const desktop of desktopsForActivity(currentActivity())) {
            desktop.wallpaperPlugin = "org.kde.image"
        }
        for (const panel of panels()) {
            for (const tasks of panel.widgets("org.kde.plasma.icontasks")) {
                tasks.currentConfigGroup = ["General"]
                tasks.writeConfig("launchers", [
                    "applications:systemsettings.desktop",
                    "preferred://filemanager",
                    "preferred://browser",
                    "applications:org.kde.konsole.desktop",
                ])
            }
        }
    """.trimIndent() + "\n"

    /** Run once by plasmashell, on new and existing desktops alike. */
    private const val PLASMA_BATTERY_UPDATE = "/usr/share/plasma/shells/org.kde.plasma.desktop/contents/updates/droidnux-battery.js"

    // Plasma hides the battery while it is full and plugged in: always in the
    // panel instead, with its percentage, as on Android's status bar.
    private val PLASMA_BATTERY = """
        for (const panel of panels()) {
            for (const systray of panel.widgets("org.kde.plasma.systemtray")) {
                const tray = desktopById(systray.readConfig("SystrayContainmentId"))
                if (!tray) continue
                tray.currentConfigGroup = ["General"]
                const shown = tray.readConfig("shownItems", [])
                if (shown.indexOf("org.kde.plasma.battery") < 0) tray.writeConfig("shownItems", shown.concat(["org.kde.plasma.battery"]))
                const battery = tray.widgets("org.kde.plasma.battery")[0] || tray.addWidget("org.kde.plasma.battery")
                battery.currentConfigGroup = ["General"]
                battery.writeConfig("showPercentage", true)
            }
        }
    """.trimIndent() + "\n"

    /** Default prefs of Debian's Firefox ESR (browser/defaults/preferences, next to Debian's own). */
    private const val FIREFOX_PREFS_PATH = "/usr/lib/firefox-esr/browser/defaults/preferences/droidnux.js"

    // VA-API decoding through the app costs Firefox more CPU than software (it
    // reimports every frame), so it stays off there (PLAN.md, phase 2).
    private val FIREFOX_PREFS = """
        // Written by DroidNux.
        pref("media.hardware-video-decoding.enabled", false);
    """.trimIndent() + "\n"

    // Written by earlier versions: an autoconfig script hiding Firefox's
    // "sandbox disabled" bar, and what only XFCE and proot used.
    private val OBSOLETE_FILES = listOf(
        "/usr/lib/firefox-esr/defaults/pref/droidnux-autoconfig.js",
        "/usr/lib/firefox-esr/droidnux.cfg",
        "/usr/local/bin/droidnux-plasma-session",
        "/usr/local/bin/droidnux-ui-scale",
        "$GUEST_LIB_DIR/libdroidnux-guest-shm.so",
        "$GUEST_LIB_DIR/libdroidnux-guest-paths.so",
    )

    // There is no Flatpak in the container: access by socket, and full
    // permissions. PipeWire's Flatpak check opens /proc/<pid>/root, which a
    // runtime may not show as the guest's root (proot did not): every client
    // then looked sandboxed and waited for a portal that never answers.
    private val PIPEWIRE_DEFAULTS = """
        # Written by DroidNux.
        module.access.args = {
            access.socket = { pipewire-0 = "unrestricted", pipewire-0-manager = "unrestricted" }
        }
        # pipewire-pulse answers a client's timing query with the position of
        # the graph's last cycle as if it were now: Firefox's audio clock jumped
        # by up to a cycle (2048 frames, 43 ms, for its latency) and 60 fps video
        # lost frames. 512 frames (11 ms) leave the jumps below half a frame.
        context.properties = {
            default.clock.max-quantum = 512
        }
        # The sink that plays through the app (AAudio).
        context.modules = [
            { name = libpipewire-module-droidnux flags = [ nofail ] }
        ]
    """.trimIndent() + "\n"

    // No realtime for PipeWire clients (KWin, apps): apps get no RLIMIT_RTPRIO,
    // and the realtime portal answers RTTimeUSecMax 0, which module-rt turns
    // into RLIMIT_RTTIME 0. On the Galaxy S26 a thread then made realtime is
    // SIGKILLed at its first tick: KWin died seconds after start.
    private val PIPEWIRE_CLIENT_DEFAULTS = """
        # Written by DroidNux.
        context.properties = {
            module.rt = false
        }
    """.trimIndent() + "\n"

    private val WIREPLUMBER_DEFAULTS = """
        # Written by DroidNux (see PIPEWIRE_DEFAULTS).
        access.rules = [
          {
            matches = [ { pipewire.sec.flatpak = "true" } ]
            actions = { update-props = { access = "default", default_permissions = "all" } }
          }
        ]
    """.trimIndent() + "\n"

    /**
     * Writes [content] at the guest [path] inside [rootfs], refusing to follow
     * symlinks out of it (guest links are absolute and would point at the host).
     * The file is replaced by a rename, so processes that have it open or
     * mapped keep the old copy.
     */
    private fun writeFile(rootfs: File, path: String, content: ByteArray, mode: Int = 0x1A4) {
        val file = File(rootfs, path.removePrefix("/"))
        val root = rootfs.canonicalPath
        val parent = file.parentFile!!
        parent.mkdirs()
        if (!parent.canonicalPath.let { it == root || it.startsWith("$root/") }) {
            throw IOException("$path resolves outside the rootfs")
        }
        if (java.nio.file.Files.isSymbolicLink(file.toPath())) file.delete()
        if (file.exists() && file.length() == content.size.toLong() && file.readBytes().contentEquals(content)) {
            return
        }
        val temp = File(parent, ".${file.name}.droidnux-new")
        temp.writeBytes(content)
        Os.chmod(temp.path, mode)
        if (!temp.renameTo(file)) {
            temp.delete()
            throw IOException("cannot replace $path")
        }
    }

    /** Deletes [path] in the guest, never through a guest symlink to the host (File.delete unlinks a link). */
    private fun removeFile(rootfs: File, path: String) {
        val file = File(rootfs, path.removePrefix("/"))
        val root = rootfs.canonicalPath
        val parent = file.parentFile!!.canonicalPath
        if (parent == root || parent.startsWith("$root/")) file.delete()
    }
}
