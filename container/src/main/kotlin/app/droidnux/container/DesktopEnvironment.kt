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
    private const val UI_SCALE_SCRIPT = "/usr/local/bin/droidnux-ui-scale"
    private const val APPS_SCRIPT = "/usr/local/bin/droidnux-apps"

    /** PNG icons of the desktop's apps, kept by [APPS_SCRIPT] for Android. */
    private const val ICON_CACHE = "/var/cache/droidnux/icons"

    /** FIFO the session reads desktop ids from, to start those apps inside it. */
    private const val LAUNCH_FIFO = "/tmp/.droidnux-launch"
    const val DISPLAY = 0

    /** MIT-SHM segments the app's X server can attach (display/src/main/cpp/shm). */
    private const val SHM_LIBRARY = "libdroidnux-guest-shm.so"
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

    /**
     * DroidNux's defaults for XFCE (theme, panel), ahead of the distro's in
     * XDG_CONFIG_DIRS (as Xubuntu does): what users change still goes to ~/.config.
     */
    private const val XDG_DEFAULTS = "/etc/xdg/droidnux"
    private const val XFCONF_DEFAULTS = "$XDG_DEFAULTS/xfce4/xfconf/xfce-perchannel-xml"

    private val PACKAGES = listOf(
        "xfce4", "xfce4-terminal", "dbus-x11", "x11-xserver-utils", "xkb-data",
        // The browser pinned in the dock (PANEL_DEFAULTS).
        "firefox-esr",
        "adwaita-icon-theme", "fonts-dejavu-core", "tzdata",
        // Android's language in the desktop's apps (SESSION generates the locale).
        "locales",
        // DroidNux's look (XSETTINGS_DEFAULTS, PANEL_DEFAULTS); Papirus comes in the theme component.
        "arc-theme", "fonts-inter", "xfce4-whiskermenu-plugin", "xfce4-docklike-plugin", "xfce4-pulseaudio-plugin",
        // The app's list of Linux apps: gio starts them, rsvg-convert draws their icons (APPS_SCRIPT).
        "libglib2.0-bin", "librsvg2-bin",
        // SVG loader for gdk-pixbuf: XFCE's default wallpaper and many icons are SVG.
        "librsvg2-common",
        // H.264 and AAC for Firefox: its bundled decoders cover only VP8/VP9/AV1/Opus/Vorbis.
        "libavcodec61",
        // Sound, played by the app (PIPEWIRE_CONF); PulseAudio and ALSA clients too.
        "pipewire", "pipewire-pulse", "pipewire-alsa", "wireplumber",
    )

    /** Written by [installScript]: the package list of the last successful install. */
    private const val PACKAGES_MARKER = "var/lib/droidnux/desktop-packages"

    /** Guest shell script installing or updating the desktop (needs network). */
    fun installScript(): String {
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
        apt-get install -y --no-install-recommends ${PACKAGES.joinToString(" ")}
        # Firefox's interface in that language, when Debian has it.
        if apt-cache show firefox-esr-l10n-$language > /dev/null 2>&1; then
            apt-get install -y --no-install-recommends firefox-esr-l10n-$language
        fi
        apt-get clean
        mkdir -p /${PACKAGES_MARKER.substringBeforeLast('/')}
        echo '${PACKAGES.joinToString(" ")}' > /$PACKAGES_MARKER
        echo DROIDNUX_INSTALL_OK
        """.trimIndent()
    }

    /** True when the desktop packages are present in [rootfs]. */
    fun isInstalled(rootfs: File): Boolean =
        File(rootfs, "usr/bin/startxfce4").exists() && File(rootfs, "usr/bin/dbus-launch").exists()

    /** False when this version of the app needs packages the last install did not have. */
    fun isUpToDate(rootfs: File): Boolean =
        runCatching { File(rootfs, PACKAGES_MARKER).readText().trim() }.getOrNull() == PACKAGES.joinToString(" ")

    /**
     * Writes the session script, DroidNux defaults and guest libraries into
     * [rootfs]; [nativeLibraryDir] is the app's, where the guest libraries
     * ship. Safe to call before every session; user settings are never
     * overwritten.
     */
    fun prepare(rootfs: File, nativeLibraryDir: File) {
        writeFile(rootfs, SESSION_SCRIPT, SESSION.toByteArray(), mode = 0x1ED /* 0755 */)
        writeFile(rootfs, UI_SCALE_SCRIPT, UI_SCALE.toByteArray(), mode = 0x1ED)
        writeFile(rootfs, APPS_SCRIPT, APPS.toByteArray(), mode = 0x1ED)
        writeFile(
            rootfs, "$GUEST_LIB_DIR/$SHM_LIBRARY", File(nativeLibraryDir, SHM_LIBRARY).readBytes(),
            mode = 0x1A4,
        )
        writeFile(rootfs, "$PIPEWIRE_MODULE_DIR/$PIPEWIRE_MODULE", File(nativeLibraryDir, PIPEWIRE_MODULE).readBytes())
        writeFile(rootfs, PIPEWIRE_CONF, PIPEWIRE_DEFAULTS.toByteArray())
        writeFile(rootfs, WIREPLUMBER_CONF, WIREPLUMBER_DEFAULTS.toByteArray())
        writeFile(rootfs, "$XFCONF_DEFAULTS/xfwm4.xml", XFWM4_DEFAULTS.toByteArray())
        writeFile(rootfs, "$XFCONF_DEFAULTS/xsettings.xml", XSETTINGS_DEFAULTS.toByteArray())
        writeFile(rootfs, "$XFCONF_DEFAULTS/xfce4-panel.xml", PANEL_DEFAULTS.toByteArray())
        writeFile(rootfs, "$XDG_DEFAULTS/xfce4/panel/docklike.rc", DOCK_DEFAULTS.toByteArray())
        writeFile(rootfs, FIREFOX_PREFS_PATH, FIREFOX_PREFS.toByteArray())
        OBSOLETE_FILES.forEach { removeFile(rootfs, it) }
    }

    /** Printed by the session script when the desktop logs out. */
    const val SESSION_END_MARKER = "DROIDNUX_SESSION_END"

    /** Command starting the session, with the X keyboard [layout] (e.g. "it"). */
    fun sessionCommand(layout: String, dpi: Int): String =
        "DROIDNUX_XKB_LAYOUT=$layout DROIDNUX_DPI=$dpi DROIDNUX_LOCALE=${glibcLocale()} exec $SESSION_SCRIPT"

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
            mkdir -p "${'$'}XDG_RUNTIME_DIR" && chmod 700 "${'$'}XDG_RUNTIME_DIR"
            mkdir -p /tmp/.ICE-unix && chmod 1777 /tmp/.ICE-unix

            # The X server starts with the app; wait for its socket.
            i=0
            while [ ! -S /tmp/.X11-unix/X$DISPLAY ] && [ ${'$'}i -lt 100 ]; do sleep 0.1; i=${'$'}((i + 1)); done

            # Android's language, once its locale is generated (about a second, the first time).
            if [ -f /usr/share/i18n/locales/${'$'}DROIDNUX_LOCALE ]; then
                locale -a | grep -qx "${'$'}DROIDNUX_LOCALE.utf8" ||
                    localedef -i "${'$'}DROIDNUX_LOCALE" -c -f UTF-8 "${'$'}DROIDNUX_LOCALE.UTF-8"
                export LANG=${'$'}DROIDNUX_LOCALE.UTF-8
            fi

            if [ -n "${'$'}DROIDNUX_XKB_LAYOUT" ]; then
                setxkbmap "${'$'}DROIDNUX_XKB_LAYOUT" || echo "setxkbmap ${'$'}DROIDNUX_XKB_LAYOUT failed"
            fi

            # The device's storage in the home folder (when the app may access it).
            if [ -d $GUEST_SDCARD ] && [ ! -e /root/Android ] && [ ! -L /root/Android ]; then
                ln -s $GUEST_SDCARD /root/Android
            fi

            exec dbus-launch --exit-with-session "${'$'}0" --in-dbus
        fi

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
        # The interface size, once xfsettingsd and the panel are up.
        if [ -n "${'$'}DROIDNUX_DPI" ]; then (sleep 4; $UI_SCALE_SCRIPT "${'$'}DROIDNUX_DPI") & fi
        # Apps the app starts (its app list, Android shortcuts), by desktop id: they
        # must run in this proot, which a separate one would kill when it exits.
        rm -f $LAUNCH_FIFO && mkfifo -m 600 $LAUNCH_FIFO
        # Apps started before xfsettingsd shares the theme keep default icons.
        (until pgrep -x xfsettingsd > /dev/null; do sleep 0.2; done; sleep 1
        while :; do
            while read -r id; do
                case "${'$'}id" in ''|*[!A-Za-z0-9._-]*) continue ;; esac
                for d in /usr/local/share/applications /usr/share/applications; do
                    [ -f "${'$'}d/${'$'}id.desktop" ] && { gio launch "${'$'}d/${'$'}id.desktop" & break; }
                done
            done < $LAUNCH_FIFO
        done) &
        startxfce4
        echo $SESSION_END_MARKER
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
