package app.droidnux.container

import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import java.io.File
import java.io.IOException

/**
 * The desktop that runs in the container (XFCE for now): what to install, and
 * the files that adapt it to DroidNux (session script, defaults, libraries).
 */
object DesktopEnvironment {

    const val SESSION_SCRIPT = "/usr/local/bin/droidnux-session"
    /** Plasma on the app's Wayland compositor (the Wayland build, Fase 4b). */
    private const val PLASMA_SESSION_SCRIPT = "/usr/local/bin/droidnux-plasma-session"
    private const val UI_SCALE_SCRIPT = "/usr/local/bin/droidnux-ui-scale"
    private const val APPS_SCRIPT = "/usr/local/bin/droidnux-apps"

    /** PNG icons of the desktop's apps, kept by [APPS_SCRIPT] for Android. */
    private const val ICON_CACHE = "/var/cache/droidnux/icons"

    /** Who Plasma runs as under Burrow (USER_SESSION). */
    private const val DESKTOP_USER = "droidnux"

    /** FIFO the session reads desktop ids from, to start those apps inside it. */
    private const val LAUNCH_FIFO = "/tmp/.droidnux-launch"
    const val DISPLAY = 0

    /** MIT-SHM segments the app's X server can attach (display/src/main/cpp/shm). */
    private const val SHM_LIBRARY = "libdroidnux-guest-shm.so"

    /** Path syscalls without a proot stop (guest/paths), preloaded into Plasma. */
    private const val PATHS_LIBRARY = "libdroidnux-guest-paths.so"
    private const val GUEST_LIB_DIR = "/usr/local/lib/droidnux"

    /** Sound: DroidNux's PipeWire sink (guest/pipewire), in PipeWire's module directory. */
    private const val PIPEWIRE_MODULE = "libpipewire-module-droidnux.so"
    private const val PIPEWIRE_MODULE_DIR = "/usr/lib/aarch64-linux-gnu/pipewire-0.3"
    private const val PIPEWIRE_CONF = "/etc/pipewire/pipewire.conf.d/50-droidnux.conf"
    private const val WIREPLUMBER_CONF = "/etc/wireplumber/wireplumber.conf.d/50-droidnux.conf"

    /** Where the Mesa component (guest/mesa) is unpacked. */
    const val MESA_PREFIX = "/opt/droidnux/mesa"

    /** Where the theme component (guest/theme: Papirus icons, scaled window borders) is unpacked. */
    const val THEME_PREFIX = "/opt/droidnux/theme"

    /** Where the Plasma component (guest/plasma: patched KSvg and KIconThemes) is unpacked. */
    const val PLASMA_PREFIX = "/opt/droidnux/plasma"

    /**
     * DroidNux's defaults for XFCE (theme, panel), ahead of the distro's in
     * XDG_CONFIG_DIRS (as Xubuntu does): what users change still goes to ~/.config.
     */
    private const val XDG_DEFAULTS = "/etc/xdg/droidnux"
    private const val XFCONF_DEFAULTS = "$XDG_DEFAULTS/xfce4/xfconf/xfce-perchannel-xml"

    /** The same for Plasma: kdeglobals picks [PLASMA_LOOK_AND_FEEL], applied by Plasma on first login. */
    private const val PLASMA_DEFAULTS = "/etc/xdg/droidnux-plasma"
    private const val PLASMA_LOOK_AND_FEEL = "/usr/share/plasma/look-and-feel/org.droidnux.desktop"

    /**
     * The input method KWin starts (guest/kwin-im): it hands KWin's input method
     * connection to the app's compositor, which types Android's keyboard into text fields.
     */
    private const val IM_LIBRARY = "libdroidnux-guest-im.so"
    private const val IM_PROGRAM = "$GUEST_LIB_DIR/droidnux-im"
    private const val IM_DESKTOP_FILE = "$PLASMA_DEFAULTS/droidnux-im.desktop"

    /** XFCE on the app's X server. */
    private val XFCE_PACKAGES = listOf(
        "xfce4", "xfce4-terminal", "dbus-x11", "x11-xserver-utils", "xkb-data",
        "adwaita-icon-theme",
        // DroidNux's look (XSETTINGS_DEFAULTS, PANEL_DEFAULTS); Papirus comes in the theme component.
        "arc-theme", "fonts-inter", "xfce4-whiskermenu-plugin", "xfce4-docklike-plugin", "xfce4-pulseaudio-plugin",
    )

    /** Plasma on the app's Wayland compositor, with KDE's everyday apps. */
    private val PLASMA_PACKAGES = listOf(
        "plasma-workspace", "plasma-desktop", "kwin-wayland", "breeze", "xkb-data",
        // The session bus without dbus-user-session, which pulls in systemd.
        "dbus-x11",
        // xkbcomp builds the keymap the compositor sends (PLASMA_SESSION).
        "x11-xkb-utils",
        "systemsettings", "kinfocenter", "plasma-systemmonitor", "plasma-pa", "xdg-desktop-portal-kde",
        // Display settings: the scale, which the app's compositor follows.
        "kscreen",
        // The desktop's user administers with sudo (USER_SESSION).
        "sudo",
        "konsole", "dolphin", "kwrite", "ark", "gwenview", "okular", "kde-spectacle",
        // GTK apps (Firefox) in Breeze, set from System Settings.
        "breeze-gtk-theme", "kde-config-gtk-style",
    )

    /** What both desktops need. */
    private val COMMON_PACKAGES = listOf(
        // The browser pinned in the dock (PANEL_DEFAULTS).
        "firefox-esr",
        "fonts-dejavu-core", "tzdata",
        // Chinese, Japanese, Korean and emoji: without them web pages show empty boxes.
        "fonts-noto-cjk", "fonts-noto-color-emoji",
        // Android's language in the desktop's apps (SESSION generates the locale).
        "locales",
        // The app's list of Linux apps: gio starts them, rsvg-convert draws their icons (APPS_SCRIPT).
        "libglib2.0-bin", "librsvg2-bin",
        // SVG loader for gdk-pixbuf: XFCE's default wallpaper and many icons are SVG.
        "librsvg2-common",
        // H.264 and AAC for Firefox: its bundled decoders cover only VP8/VP9/AV1/Opus/Vorbis.
        "libavcodec61",
        // Sound, played by the app (PIPEWIRE_CONF); PulseAudio and ALSA clients too.
        "pipewire", "pipewire-pulse", "pipewire-alsa", "wireplumber",
    )

    private fun packages(wayland: Boolean) = (if (wayland) PLASMA_PACKAGES else XFCE_PACKAGES) + COMMON_PACKAGES

    /** Written by [installScript]: the package list of the last successful install. */
    private const val PACKAGES_MARKER = "var/lib/droidnux/desktop-packages"

    /** Guest shell script installing or updating the desktop, Plasma when [wayland] (needs network). */
    fun installScript(wayland: Boolean): String {
        val packages = packages(wayland).joinToString(" ")
        val language = java.util.Locale.getDefault().language.takeIf { Regex("[a-z]{2,3}").matches(it) } ?: "en"
        return """
        set -e
        export DEBIAN_FRONTEND=noninteractive
        apt-get update
        # The slim Debian image drops every translation (/usr/share/locale/*):
        # let Android's language back in. Packages installed before this was
        # here are reinstalled once to get theirs.
        TRANSLATIONS=/etc/dpkg/dpkg.cfg.d/droidnux-translations
        if ! grep -qs "/locale/$language/" ${'$'}TRANSLATIONS; then
            printf 'path-include /usr/share/locale/%s/*\npath-include /usr/share/locale/%s_*/*\n' $language $language > ${'$'}TRANSLATIONS
            installed=${'$'}(dpkg-query -W -f '${'$'}{db:Status-Abbrev} ${'$'}{Package}\n' | awk '${'$'}1 == "ii" { print ${'$'}2 }' |
                grep -E '^(xfce4|xfwm4|xfdesktop4|thunar|libxfce4|libxfconf|xfconf|exo-utils|libexo|libgarcon|garcon|tumbler|libgtk-3|libglib2|mousepad|ristretto|xfce4-.*-plugin)' || true)
            [ -z "${'$'}installed" ] || apt-get install -y --reinstall --no-install-recommends ${'$'}installed
        fi
        apt-get install -y --no-install-recommends $packages
        # Firefox's interface in that language, when Debian has it.
        if apt-cache show firefox-esr-l10n-$language > /dev/null 2>&1; then
            apt-get install -y --no-install-recommends firefox-esr-l10n-$language
        fi
        apt-get clean
        mkdir -p /${PACKAGES_MARKER.substringBeforeLast('/')}
        echo '$packages' > /$PACKAGES_MARKER
        echo DROIDNUX_INSTALL_OK
        """.trimIndent()
    }

    /** True when the desktop packages (Plasma when [wayland]) are present in [rootfs]. */
    fun isInstalled(rootfs: File, wayland: Boolean): Boolean =
        if (wayland) File(rootfs, "usr/bin/startplasma-wayland").exists()
        else File(rootfs, "usr/bin/startxfce4").exists() && File(rootfs, "usr/bin/dbus-launch").exists()

    /** False when this version of the app needs packages the last install did not have. */
    fun isUpToDate(rootfs: File, wayland: Boolean): Boolean =
        runCatching { File(rootfs, PACKAGES_MARKER).readText().trim().split(" ").toSet() }.getOrNull() ==
            packages(wayland).toSet()

    /**
     * Writes the session script, DroidNux defaults and guest libraries into
     * [rootfs]; [nativeLibraryDir] is the app's, where the guest libraries
     * ship. Safe to call before every session; user settings are never
     * overwritten.
     */
    fun prepare(rootfs: File, nativeLibraryDir: File) {
        writeFile(rootfs, SESSION_SCRIPT, SESSION.toByteArray(), mode = 0x1ED /* 0755 */)
        writeFile(rootfs, PLASMA_SESSION_SCRIPT, PLASMA_SESSION.toByteArray(), mode = 0x1ED)
        writeFile(rootfs, UI_SCALE_SCRIPT, UI_SCALE.toByteArray(), mode = 0x1ED)
        writeFile(rootfs, APPS_SCRIPT, APPS.toByteArray(), mode = 0x1ED)
        writeFile(
            rootfs, "$GUEST_LIB_DIR/$SHM_LIBRARY", File(nativeLibraryDir, SHM_LIBRARY).readBytes(),
            mode = 0x1A4,
        )
        writeFile(
            rootfs, "$GUEST_LIB_DIR/$PATHS_LIBRARY", File(nativeLibraryDir, PATHS_LIBRARY).readBytes(),
            mode = 0x1A4,
        )
        writeFile(rootfs, "$PIPEWIRE_MODULE_DIR/$PIPEWIRE_MODULE", File(nativeLibraryDir, PIPEWIRE_MODULE).readBytes())
        writeFile(rootfs, PIPEWIRE_CONF, PIPEWIRE_DEFAULTS.toByteArray())
        writeFile(rootfs, WIREPLUMBER_CONF, WIREPLUMBER_DEFAULTS.toByteArray())
        writeFile(rootfs, "$XFCONF_DEFAULTS/xfwm4.xml", XFWM4_DEFAULTS.toByteArray())
        writeFile(rootfs, "$XFCONF_DEFAULTS/xsettings.xml", XSETTINGS_DEFAULTS.toByteArray())
        writeFile(rootfs, "$XFCONF_DEFAULTS/xfce4-panel.xml", PANEL_DEFAULTS.toByteArray())
        writeFile(rootfs, "$XDG_DEFAULTS/xfce4/panel/docklike.rc", DOCK_DEFAULTS.toByteArray())
        writeFile(rootfs, "$PLASMA_DEFAULTS/kdeglobals", "[KDE]\nLookAndFeelPackage=org.droidnux.desktop\n".toByteArray())
        writeFile(rootfs, "$PLASMA_DEFAULTS/kwinrc", PLASMA_KWINRC.toByteArray())
        writeFile(rootfs, "$PLASMA_DEFAULTS/kscreenlockerrc", PLASMA_SCREENLOCKER.toByteArray())
        writeFile(rootfs, "$PLASMA_DEFAULTS/kwalletrc", PLASMA_KWALLETRC.toByteArray())
        writeFile(rootfs, IM_DESKTOP_FILE, IM_DESKTOP.toByteArray())
        writeFile(rootfs, IM_PROGRAM, File(nativeLibraryDir, IM_LIBRARY).readBytes(), mode = 0x1ED)
        writeFile(rootfs, "$PLASMA_LOOK_AND_FEEL/metadata.json", PLASMA_LNF_METADATA.toByteArray())
        writeFile(rootfs, "$PLASMA_LOOK_AND_FEEL/contents/defaults", PLASMA_LNF_DEFAULTS.toByteArray())
        writeFile(rootfs, "$PLASMA_LOOK_AND_FEEL/contents/layouts/org.kde.plasma.desktop-layout.js", PLASMA_LAYOUT.toByteArray())
        writeFile(rootfs, FIREFOX_PREFS_PATH, FIREFOX_PREFS.toByteArray())
        OBSOLETE_FILES.forEach { removeFile(rootfs, it) }
    }

    /** Printed by the session script when the desktop logs out. */
    const val SESSION_END_MARKER = "DROIDNUX_SESSION_END"

    /**
     * Command starting the session, with the X keyboard [layout] (e.g. "it"):
     * XFCE on the X server, or Plasma on the Wayland compositor when [wayland],
     * as [user] (a valid Linux user name; empty: the default one). Plasma's
     * session reads the user's password from its input, one line.
     */
    fun sessionCommand(layout: String, dpi: Int, wayland: Boolean = false, user: String = ""): String =
        "DROIDNUX_XKB_LAYOUT=$layout DROIDNUX_DPI=$dpi DROIDNUX_LOCALE=${glibcLocale()} " +
            (if (user.isNotEmpty()) "DROIDNUX_USER=$user " else "") +
            "exec ${if (wayland) PLASMA_SESSION_SCRIPT else SESSION_SCRIPT}"

    /** Android's language as a glibc locale name ("it_IT"); the session checks Debian has it. */
    private fun glibcLocale(): String {
        val locale = java.util.Locale.getDefault()
        val name = "${locale.language}_${locale.country.ifEmpty { locale.language.uppercase() }}"
        return name.takeIf { Regex("[a-z]{2,3}_[A-Z]{2}").matches(it) } ?: "C"
    }

    /** Command applying the interface size ([dpi], 96 = 1x) to the running session. */
    fun uiScaleCommand(dpi: Int): String = "$UI_SCALE_SCRIPT $dpi"

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
            for dir in $THEME_PREFIX/share/icons/Papirus/64x64/apps /usr/share/icons/Papirus/64x64/apps \
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
                ${'$'}1 == "OnlyShowIn" && ${'$'}2 !~ /(^|;)XFCE(;|${'$'})/ { hidden = 1 }
                ${'$'}1 == "NotShowIn" && ${'$'}2 ~ /(^|;)XFCE(;|${'$'})/ { hidden = 1 }
                ${'$'}1 == "Categories" { cats = ${'$'}2 }
                END { if (type == "Application" && !hidden && name != "") print (lname != "" ? lname : name) "\t" icon "\t" cats }
            ' "${'$'}f" | while IFS="${'$'}tab" read -r name icon cats; do
                png=${'$'}CACHE/${'$'}id.png
                if [ ! -s "${'$'}png" ] || [ "${'$'}f" -nt "${'$'}png" ]; then render "${'$'}icon" "${'$'}png"; fi
                printf '%s\t%s\t%s\n' "${'$'}id" "${'$'}name" "${'$'}cats"
            done
        done
    """.trimIndent() + "\n"

    private val UI_SCALE = """
        #!/bin/sh
        # Written by DroidNux: sizes the desktop's interface for a DPI (96 = 1x):
        # text, cursor, panel and desktop icons. Run by the app, inside or outside the session.
        if [ -z "${'$'}DBUS_SESSION_BUS_ADDRESS" ]; then
            p=${'$'}(pgrep -x xfce4-session | head -1)
            [ -n "${'$'}p" ] || exit 0
            eval "${'$'}(tr '\0' '\n' < /proc/${'$'}p/environ | grep -E '^(DISPLAY|DBUS_SESSION_BUS_ADDRESS|XDG_RUNTIME_DIR)=' | sed 's/^\([^=]*\)=\(.*\)${'$'}/export \1="\2"/')"
        fi
        dpi=${'$'}1
        q() { xfconf-query "${'$'}@" 2> /dev/null; }
        q -c xsettings -p /Xft/DPI -n -t int -s "${'$'}dpi"
        q -c xsettings -p /Gtk/CursorThemeSize -n -t int -s ${'$'}((24 * dpi / 96))
        q -c xfce4-panel -p /panels/panel-1/size -n -t uint -s ${'$'}((40 * dpi / 96))
        q -c xfce4-desktop -p /desktop-icons/icon-size -n -t uint -s ${'$'}((48 * dpi / 96))
        # Window borders are pixel images: Arc-Dark at 1x, 1.5x or 2x (theme
        # component), unless the user picked another theme.
        case "${'$'}(q -c xfwm4 -p /general/theme)" in Arc-Dark|Arc-Dark-hdpi|Arc-Dark-xhdpi|Arc-Dark-DroidNux)
            borders=Arc-Dark
            [ ${'$'}dpi -ge 120 ] && borders=Arc-Dark-hdpi
            [ ${'$'}dpi -ge 168 ] && borders=Arc-Dark-xhdpi
            [ -d $THEME_PREFIX/share/themes/${'$'}borders ] || borders=Arc-Dark
            q -c xfwm4 -p /general/theme -n -t string -s ${'$'}borders
        esac
        # Whisker menu: its size is in pixels and its icons in steps (16, 24, 32, 38, 48, 64, 96).
        icon_step() {
            for s in 20:0 28:1 35:2 43:3 56:4 80:5; do [ "${'$'}1" -lt "${'$'}{s%:*}" ] && { echo "${'$'}{s#*:}"; return; }; done
            echo 6
        }
        for w in ${'$'}(q -c xfce4-panel -l | grep -E '^/plugins/plugin-[0-9]+${'$'}'); do
            [ "${'$'}(q -c xfce4-panel -p "${'$'}w")" = whiskermenu ] || continue
            q -c xfce4-panel -p "${'$'}w/menu-width" -n -t int -s ${'$'}((450 * dpi / 96))
            q -c xfce4-panel -p "${'$'}w/menu-height" -n -t int -s ${'$'}((500 * dpi / 96))
            q -c xfce4-panel -p "${'$'}w/launcher-icon-size" -n -t int -s "${'$'}(icon_step ${'$'}((24 * dpi / 96)))"
            q -c xfce4-panel -p "${'$'}w/category-icon-size" -n -t int -s "${'$'}(icon_step ${'$'}((24 * dpi / 96)))"
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
            # Swapchain images in AHardwareBuffers from the X server, which
            # shows full-screen ones without copying them (Mesa patch 0003).
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
            # instance_lock in the error path (zink_destroy_screen). GTK
            # opens GLX at startup, so the whole session would stop.
            # Plain software GLX (llvmpipe over MIT-SHM) instead.
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
        # must run in this proot, which a separate one would kill when it exits.
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

    private val SESSION = """
        #!/bin/sh
        # Written by DroidNux: starts the desktop on the app's X server.
        # Runs twice: first to set up and start D-Bus, then inside it (--in-dbus).
        if [ "${'$'}1" != --in-dbus ]; then
            export DISPLAY=:$DISPLAY
            export XDG_RUNTIME_DIR=/tmp/runtime-${'$'}(id -u)
            export XDG_SESSION_TYPE=x11
            export XDG_CURRENT_DESKTOP=XFCE
            export SHELL=/bin/bash
            export XDG_CONFIG_DIRS=$XDG_DEFAULTS:/etc/xdg
            # Icons and window borders from the theme component, when installed.
            if [ -f $THEME_PREFIX/droidnux-theme.version ]; then
                export XDG_DATA_DIRS=$THEME_PREFIX/share:/usr/local/share:/usr/share
            fi
            # MIT-SHM segments the X server can attach.
            export LD_PRELOAD=$GUEST_LIB_DIR/$SHM_LIBRARY${'$'}{LD_PRELOAD:+:${'$'}LD_PRELOAD}
            # Android's SELinux denies reopening a memfd through /proc/self/fd,
            # so Firefox's parent process falls back to unsealed shm_open while
            # its children still demand F_SEAL_SHRINK: every tab crashes with
            # "Shared memory PlatformHandle is not safe to map" (Firefox 154+).
            export MOZ_SHM_NO_SEALS=1
${GPU_ENV.prependIndent("            ")}
            mkdir -p "${'$'}XDG_RUNTIME_DIR" && chmod 700 "${'$'}XDG_RUNTIME_DIR"
            mkdir -p /tmp/.ICE-unix && chmod 1777 /tmp/.ICE-unix

            # The X server starts with the app; wait for its socket.
            i=0
            while [ ! -S /tmp/.X11-unix/X$DISPLAY ] && [ ${'$'}i -lt 100 ]; do sleep 0.1; i=${'$'}((i + 1)); done

${LOCALE_ENV.prependIndent("            ")}

            if [ -n "${'$'}DROIDNUX_XKB_LAYOUT" ]; then
                setxkbmap "${'$'}DROIDNUX_XKB_LAYOUT" || echo "setxkbmap ${'$'}DROIDNUX_XKB_LAYOUT failed"
            fi

${SDCARD_LINK.prependIndent("            ")}

            exec dbus-launch --exit-with-session "${'$'}0" --in-dbus
        fi

${SESSION_SERVICES.prependIndent("        ")}
        # The interface size, once xfsettingsd and the panel are up.
        if [ -n "${'$'}DROIDNUX_DPI" ]; then (sleep 4; $UI_SCALE_SCRIPT "${'$'}DROIDNUX_DPI") & fi
        LAUNCH_AFTER=xfsettingsd
${LAUNCH_LOOP.prependIndent("        ")}
        startxfce4
        echo $SESSION_END_MARKER
    """.trimIndent() + "\n"

    /**
     * Plasma session part: plasmashell back when it exits during the session. KWin
     * drops a client on a protocol error and Qt then exits instead of crashing, so
     * KCrash does not restart it and the desktop stays black. Waits with tail --pid
     * (one syscall a second) rather than polling pgrep, which costs proot hundreds.
     */
    private val PLASMASHELL_WATCH = """
        (until p=${'$'}(pgrep -x plasmashell | head -1) && [ -n "${'$'}p" ]; do sleep 1; done
        sleep 2
        env=${'$'}XDG_RUNTIME_DIR/.droidnux-plasmashell-environ
        cp /proc/${'$'}p/environ "${'$'}env" || exit
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
     * Plasma session part, as root under Burrow: the user the desktop runs as
     * (some apps refuse root, VLC among them), DROIDNUX_USER, made on first use
     * with the settings root had (or renamed from the account made before);
     * the password the app sends on the input line becomes its password and
     * sudo's, and the key of KDE's wallet, opened in --in-dbus as pam_kwallet
     * would at login (DROIDNUX_WALLET). Then the script again with the user's
     * ids (perl: setpriv needs capabilities Android refuses).
     */
    private val USER_SESSION = """
        if [ "${'$'}(id -u)" = 0 ] && [ "${'$'}DROIDNUX_RUNTIME" = burrow ] && [ "${'$'}1" != --in-dbus ]; then
            user=${'$'}{DROIDNUX_USER:-$DESKTOP_USER}
            pass=
            IFS= read -r pass || true
            # The account made before this one, by DroidNux (its comment).
            old=${'$'}(getent passwd | awk -F: '${'$'}5 == "DroidNux" { print ${'$'}1; exit }')
            if ! id -u "${'$'}user" > /dev/null 2>&1 && [ -n "${'$'}old" ]; then
                oldhome=${'$'}(getent passwd "${'$'}old" | cut -d: -f6)
                usermod -l "${'$'}user" -d "/home/${'$'}user" -m "${'$'}old" && groupmod -n "${'$'}user" "${'$'}old" &&
                    grep -rlI --exclude-dir=mozilla -e "${'$'}oldhome/" "/home/${'$'}user/.config" 2> /dev/null |
                        xargs -r sed -i "s|${'$'}oldhome/|/home/${'$'}user/|g"
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
            unset DROIDNUX_WALLET
            if [ -n "${'$'}pass" ]; then
                printf '%s:%s\n' "${'$'}user" "${'$'}pass" | chpasswd || echo "chpasswd failed"
                sudo="ALL=(ALL:ALL) ALL"
                # pam_kwallet's key: PBKDF2-HMAC-SHA512 of the password, 50000 rounds, the wallet's salt.
                wallets=${'$'}home/.local/share/kwalletd
                mkdir -p "${'$'}wallets"
                [ -s "${'$'}wallets/kdewallet.salt" ] || head -c 56 /dev/urandom > "${'$'}wallets/kdewallet.salt"
                chown -R "${'$'}user:" "${'$'}home/.local"
                DROIDNUX_WALLET=${'$'}(printf %s "${'$'}pass" | perl -MDigest::SHA=hmac_sha512 -e '
                    local ${'$'}/; my ${'$'}key = <STDIN>; open my ${'$'}f, "<", ${'$'}ARGV[0] or die "salt: ${'$'}!\n"; binmode ${'$'}f;
                    my ${'$'}salt = <${'$'}f>; my ${'$'}u = hmac_sha512(${'$'}salt . pack("N", 1), ${'$'}key); my ${'$'}t = ${'$'}u;
                    for (2 .. 50000) { ${'$'}u = hmac_sha512(${'$'}u, ${'$'}key); ${'$'}t ^= ${'$'}u }
                    print "[", join(", ", unpack("C56", ${'$'}t)), "]";' "${'$'}wallets/kdewallet.salt") && export DROIDNUX_WALLET
            else
                sudo="ALL=(ALL:ALL) NOPASSWD: ALL"
            fi
            unset pass
            if [ -d /etc/sudoers.d ]; then
                echo "${'$'}user ${'$'}sudo" > /etc/sudoers.d/droidnux && chmod 440 /etc/sudoers.d/droidnux
            fi
            exec perl -e '${'$'}u = shift; @g = split / /, shift; ${'$'}) = "${'$'}g[0] @g"; ${'$'}( = ${'$'}g[0]; ${'$'}< = ${'$'}> = ${'$'}u;
                die "cannot become ${'$'}u\n" if ${'$'}> != ${'$'}u; exec @ARGV or die "exec: ${'$'}!\n"' \
                "${'$'}(id -u "${'$'}user")" "${'$'}(id -G "${'$'}user")" \
                env HOME="${'$'}home" USER="${'$'}user" LOGNAME="${'$'}user" "${'$'}0" "${'$'}@"
        fi
    """.trimIndent()

    private val PLASMA_SESSION = """
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
            export MOZ_SHM_NO_SEALS=1 # see SESSION
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
            fi
            # Turnip on KGSL has no calibrated timestamps: Zink answers
            # GL_TIMESTAMP with a GPU round trip, which KWin asks every frame.
            export KWIN_NO_TIMER_QUERY=1
            # Opening apps: most of their path syscalls skip proot (proot patch 0009).
            # Burrow has no stops to skip.
            if [ "${'$'}DROIDNUX_RUNTIME" != burrow ]; then
                export LD_PRELOAD=$GUEST_LIB_DIR/$PATHS_LIBRARY${'$'}{LD_PRELOAD:+:${'$'}LD_PRELOAD}
            fi
            mkdir -p "${'$'}XDG_RUNTIME_DIR" && chmod 700 "${'$'}XDG_RUNTIME_DIR"
            # The wallet's key, from the environment (D-Bus would pass it to every service) to a private file.
            if [ -n "${'$'}DROIDNUX_WALLET" ]; then
                (umask 077 && printf %s "${'$'}DROIDNUX_WALLET" > "${'$'}XDG_RUNTIME_DIR/.droidnux-wallet")
                unset DROIDNUX_WALLET
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
            key=${'$'}(cat "${'$'}XDG_RUNTIME_DIR/.droidnux-wallet")
            rm -f "${'$'}XDG_RUNTIME_DIR/.droidnux-wallet"
            gdbus call --session --dest org.kde.kwalletd6 --object-path /modules/kwalletd6 \
                --method org.kde.KWallet.pamOpen kdewallet "${'$'}key" 0 > /dev/null 2>&1 &
            unset key
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

    /** Default prefs of Debian's Firefox ESR (browser/defaults/preferences, next to Debian's own). */
    private const val FIREFOX_PREFS_PATH = "/usr/lib/firefox-esr/browser/defaults/preferences/droidnux.js"

    // VA-API decoding through the app costs Firefox more CPU than software (it
    // reimports every frame), so it stays off there (PLAN.md, phase 2).
    private val FIREFOX_PREFS = """
        // Written by DroidNux.
        pref("media.hardware-video-decoding.enabled", false);
    """.trimIndent() + "\n"

    // Written by earlier versions, when Firefox's sandbox crashed under proot:
    // an autoconfig script hiding the "sandbox disabled" warning bar.
    private val OBSOLETE_FILES = listOf(
        "/usr/lib/firefox-esr/defaults/pref/droidnux-autoconfig.js",
        "/usr/lib/firefox-esr/droidnux.cfg",
    )

    // Under proot PipeWire's Flatpak check fails for every client: opening
    // /proc/<pid>/root gives ENOENT (proot resolves the link to the rootfs' host
    // path). PipeWire then takes each client for a sandboxed app and waits for a
    // portal that never answers (clients connect and get no reply), and
    // WirePlumber gives them read-only access (no streams). There is no Flatpak
    // in the container: access by socket, and full permissions.
    private val PIPEWIRE_DEFAULTS = """
        # Written by DroidNux.
        module.access.args = {
            access.socket = { pipewire-0 = "unrestricted", pipewire-0-manager = "unrestricted" }
        }
        # The sink that plays through the app (AAudio).
        context.modules = [
            { name = libpipewire-module-droidnux flags = [ nofail ] }
        ]
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

    // Android composes the screen: a compositing window manager would only add
    // a second, slower composition pass. The borders' size follows the
    // interface size (UI_SCALE).
    private val XFWM4_DEFAULTS = """
        <?xml version="1.0" encoding="UTF-8"?>
        <channel name="xfwm4" version="1.0">
          <property name="general" type="empty">
            <property name="use_compositing" type="bool" value="false"/>
            <property name="theme" type="string" value="Arc-Dark"/>
          </property>
        </channel>
    """.trimIndent() + "\n"

    private val XSETTINGS_DEFAULTS = """
        <?xml version="1.0" encoding="UTF-8"?>
        <channel name="xsettings" version="1.0">
          <property name="Net" type="empty">
            <property name="ThemeName" type="string" value="Arc-Dark"/>
            <property name="IconThemeName" type="string" value="Papirus-Dark"/>
          </property>
          <property name="Gtk" type="empty">
            <property name="FontName" type="string" value="Inter 10"/>
          </property>
        </channel>
    """.trimIndent() + "\n"

    // One full-width bottom panel: Whisker menu, Docklike, tray, volume, clock.
    // Panel and menu sizes follow the interface size (UI_SCALE).
    private val PANEL_DEFAULTS = """
        <?xml version="1.0" encoding="UTF-8"?>
        <channel name="xfce4-panel" version="1.0">
          <property name="configver" type="int" value="2"/>
          <property name="panels" type="array">
            <value type="int" value="1"/>
            <property name="dark-mode" type="bool" value="true"/>
            <property name="panel-1" type="empty">
              <property name="position" type="string" value="p=10;x=0;y=0"/>
              <property name="length" type="uint" value="100"/>
              <property name="size" type="uint" value="40"/>
              <property name="icon-size" type="uint" value="0"/>
              <property name="position-locked" type="bool" value="true"/>
              <property name="plugin-ids" type="array">
                <value type="int" value="1"/>
                <value type="int" value="2"/>
                <value type="int" value="3"/>
                <value type="int" value="4"/>
                <value type="int" value="5"/>
                <value type="int" value="6"/>
              </property>
            </property>
          </property>
          <property name="plugins" type="empty">
            <property name="plugin-1" type="string" value="whiskermenu">
              <property name="button-icon" type="string" value="distributor-logo-debian"/>
              <property name="show-button-title" type="bool" value="false"/>
            </property>
            <property name="plugin-2" type="string" value="docklike"/>
            <property name="plugin-3" type="string" value="separator">
              <property name="expand" type="bool" value="true"/>
              <property name="style" type="uint" value="0"/>
            </property>
            <property name="plugin-4" type="string" value="systray">
              <property name="square-icons" type="bool" value="true"/>
            </property>
            <property name="plugin-5" type="string" value="pulseaudio">
              <property name="enable-keyboard-shortcuts" type="bool" value="true"/>
            </property>
            <property name="plugin-6" type="string" value="clock"/>
          </property>
        </channel>
    """.trimIndent() + "\n"

    /** Apps pinned in the dock: Docklike reads xfce4/panel/docklike.rc from the config dirs until the user changes them. */
    private val DOCK_DEFAULTS = """
        [user]
        pinned=xfce4-terminal;thunar;firefox-esr;
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
