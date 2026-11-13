package app.droidnux.container

import android.content.Context
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

    /** Who Plasma runs as (droidnux-session). */
    private const val DESKTOP_USER = "droidnux"

    /** FIFO the session reads desktop ids from, to start those apps inside it. */
    private const val LAUNCH_FIFO = "/tmp/.droidnux-launch"
    const val DISPLAY = 0

    /** The shadow hash the app's password got last (droidnux-session), root's only. */
    private const val PASSWORD_STAMP = "/var/lib/droidnux/password-hash"

    /** KWin's render time prediction and Qt's animation timer (guest/kwin-pacing), preloaded into Plasma. */
    private const val KWIN_LIBRARY = "libdroidnux-guest-kwin.so"

    private const val KWIN_LIBRARY_PATH = "/usr/local/lib/aarch64-linux-gnu/droidnux/$KWIN_LIBRARY"

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

    /** Adds the XDG folders to Dolphin's places, once (droidnux-places). */
    private const val PLACES_SCRIPT = "$GUEST_LIB_DIR/droidnux-places"

    /** Opens KDE's wallet with the password before and moves it to the new one (droidnux-wallet). */
    private const val WALLET_SCRIPT = "$GUEST_LIB_DIR/droidnux-wallet"

    /**
     * Android's battery for the desktop: the app writes it to BATTERY_FILE,
     * UPOWER_SCRIPT serves it as UPower on a system bus of our own (system-bus.conf).
     */
    private const val BATTERY_FILE = "/run/droidnux-battery/state"
    private const val UPOWER_SCRIPT = "$GUEST_LIB_DIR/droidnux-upower"
    private const val SYSTEM_BUS_CONF = "$GUEST_LIB_DIR/system-bus.conf"
    /** The system bus's services started on demand: powerdevil's backlight helper only (backlighthelper.service). */
    private const val SYSTEM_SERVICES_DIR = "$GUEST_LIB_DIR/system-services"
    private const val BACKLIGHT_HELPER = "org.kde.powerdevil.backlighthelper"
    /** polkit for that helper (droidnux-polkit). */
    private const val POLKIT_SCRIPT = "$GUEST_LIB_DIR/droidnux-polkit"
    /** Android's screen as a backlight (/sys/class/backlight/droidnux), in the rootfs: [backlightDevice]. */
    private const val BACKLIGHT_DIR = "run/droidnux-backlight"
    const val BACKLIGHT_MAX = 1000

    /** Installs DroidNux's Mesa as Flatpak's GL extension (droidnux-flatpak-gl). */
    const val FLATPAK_GL_SCRIPT = "$GUEST_LIB_DIR/droidnux-flatpak-gl"

    /** Copies Plasma's wallpaper for the app's home screen (droidnux-wallpaper). */
    private const val WALLPAPER_SCRIPT = "$GUEST_LIB_DIR/droidnux-wallpaper"
    private const val WALLPAPER_FILE = "/tmp/.droidnux-wallpaper"

    /** The mouse's settings the app applies, written by KWin (guest/plasma, KWin patch 0003). */
    private const val POINTER_FILE = "/tmp/.droidnux-pointer"

    /** The orientation chosen in Plasma's display settings, written by KWin (guest/plasma, KWin patch 0004). */
    private const val ORIENTATION_FILE = "/tmp/.droidnux-orientation"

    /** logind, timedated and hostnamed for the session (droidnux-logind), which writes AWAKE_FILE. */
    private const val LOGIND_SCRIPT = "$GUEST_LIB_DIR/droidnux-logind"
    private const val AWAKE_FILE = "/tmp/.droidnux-awake"

    /** bubblewrap for Flatpak, on what Burrow emulates (droidnux-bwrap). */
    private const val BWRAP_SCRIPT = "$GUEST_LIB_DIR/droidnux-bwrap"

    /** systemctl without systemd (docker-systemctl-replacement), in place of the distro's. */
    private const val SYSTEMCTL_SCRIPT = "$GUEST_LIB_DIR/droidnux-systemctl"
    private const val SYSTEMCTL_PY = "$GUEST_LIB_DIR/systemctl3.py"

    /** journalctl -u from the services' logs of systemctl.py, in place of the distro's. */
    private const val JOURNALCTL_SCRIPT = "$GUEST_LIB_DIR/droidnux-journalctl"

    /**
     * The document portal without FUSE (droidnux-documents), started on demand by the session bus: its
     * directory comes before /usr/share's, so it takes the place of xdg-document-portal.
     */
    private const val DOCUMENTS_SCRIPT = "$GUEST_LIB_DIR/droidnux-documents"
    private const val DOCUMENTS_SERVICE = "/usr/local/share/dbus-1/services/org.freedesktop.portal.Documents.service"

    /** Groups the desktop's processes by app for System Monitor (droidnux-app-groups). */
    private const val APP_GROUPS_SCRIPT = "$GUEST_LIB_DIR/droidnux-app-groups"

    /**
     * Android's active network for ksystemstats (guest/plasma, patch 0002): the
     * app writes NETWORK_STATE, and its byte counters again whenever the plugin
     * writes NETWORK_REQUEST.
     */
    private const val NETWORK_DIR = "/run/droidnux-network"
    private const val NETWORK_STATE = "$NETWORK_DIR/state"
    const val NETWORK_REQUEST = "wanted"
    private const val SYSTEM_BUS_SOCKET = "/run/dbus/system_bus_socket"

    /**
     * For guest jobs outside the session (apt): the session's system bus is
     * stopped with it while the desktop is paused, and a package script that
     * asks it something (flatpak's remote-list) would wait until it resumes.
     */
    const val NO_SYSTEM_BUS = "export DBUS_SYSTEM_BUS_ADDRESS=unix:path=/run/droidnux-no-bus"

    /** Plasma on the app's Wayland compositor, with KDE's everyday apps. */
    private val PLASMA_PACKAGES = listOf(
        "plasma-workspace", "plasma-desktop", "kwin-wayland", "breeze", "xkb-data",
        // The session bus without dbus-user-session, which pulls in systemd.
        "dbus-x11",
        // xkbcomp builds the keymap the compositor sends (droidnux-session).
        "x11-xkb-utils",
        "systemsettings", "kinfocenter", "plasma-systemmonitor", "plasma-pa", "xdg-desktop-portal-kde",
        // The battery applet (Plasma 6.3 moved it here), fed by UPOWER.
        "powerdevil",
        // Display settings: the scale, which the app's compositor follows.
        "kscreen",
        // The desktop's user administers with sudo (droidnux-session).
        "sudo",
        // Documents, Pictures, Music... in the user's language (droidnux-session).
        "xdg-user-dirs",
        // KDE's wallet moved to a new password over D-Bus (droidnux-wallet).
        "python3-gi",
        // Digest::SHA, for the wallet's key (droidnux-session); perl-base lacks it.
        "perl",
        "konsole", "dolphin", "kwrite", "ark", "gwenview", "okular", "kde-spectacle",
        // Dolphin's image and video previews: only recommended, so --no-install-recommends drops them.
        "kio-extras", "ffmpegthumbs",
        // GTK apps (Firefox) in Breeze, set from System Settings.
        "breeze-gtk-theme", "kde-config-gtk-style",
    )

    /** What the desktop needs besides Plasma. */
    private val COMMON_PACKAGES = listOf(
        // The browser pinned in the panel (lnf-layout.js).
        "firefox-esr",
        "fonts-dejavu-core", "tzdata",
        // HTTPS for the desktop's tools (Flatpak's remotes, curl, git): the slim image has no CA certificates.
        "ca-certificates",
        // ssh for git and editors' remote hosts (VS Code's Remote - SSH): the slim image has none.
        "openssh-client",
        // ip and ss, which every Linux has (Burrow answers their netlink requests): the slim image has neither.
        "iproute2",
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
        $NO_SYSTEM_BUS
        # An install cut short (the app closed halfway) leaves packages unpacked but not set up.
        dpkg --configure -a
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
     * Writes the session script, DroidNux defaults ([FILES]) and guest libraries
     * (in the app's native library directory) into [rootfs]. Safe to call before
     * every session; user settings are never overwritten.
     */
    fun prepare(context: Context, rootfs: File) {
        for ((asset, path) in FILES) {
            val text = render(context.assets.open("desktop/$asset").use { it.readBytes().decodeToString() })
            writeFile(rootfs, path, text.toByteArray(), mode = if (text.startsWith("#!")) 0x1ED /* 0755 */ else 0x1A4)
        }
        val nativeLibraryDir = File(context.applicationInfo.nativeLibraryDir)
        writeFile(rootfs, KWIN_LIBRARY_PATH, File(nativeLibraryDir, KWIN_LIBRARY).readBytes())
        removeFile(rootfs, "$GUEST_LIB_DIR/$KWIN_LIBRARY") // its place before
        writeFile(rootfs, "$PIPEWIRE_MODULE_DIR/$PIPEWIRE_MODULE", File(nativeLibraryDir, PIPEWIRE_MODULE).readBytes())
        writeFile(rootfs, IM_PROGRAM, File(nativeLibraryDir, IM_LIBRARY).readBytes(), mode = 0x1ED)
        // KIO counts Android's storage (FUSE) as remote, where previews are off by default: allow files up to 50 MiB.
        writeFile(
            rootfs, "$PLASMA_DEFAULTS/kdeglobals",
            "[KDE]\nLookAndFeelPackage=org.droidnux.desktop\n\n[PreviewSettings]\nMaximumRemoteSize=52428800\n".toByteArray(),
        )
        writeFile(
            rootfs, DOCUMENTS_SERVICE,
            "[D-BUS Service]\nName=org.freedesktop.portal.Documents\nExec=$DOCUMENTS_SCRIPT\n".toByteArray(),
        )
        hidePowerProfiles(rootfs)
        touchTables(rootfs)
        OBSOLETE_FILES.forEach { removeFile(rootfs, it) }
        // systemd's ssh proxy (Host unix/* vsock/* .host) needs systemd's sockets, which never exist here; editors
        // list ".host" as the only remote host (VS Code's Remote - SSH) and fail. systemd's upgrades bring it back.
        removeFile(rootfs, "/etc/ssh/ssh_config.d/20-systemd-ssh-proxy.conf")
    }

    /** Printed by the session script when the desktop logs out, and by LOGIND_SCRIPT when it shuts down. */
    const val SESSION_END_MARKER = "DROIDNUX_SESSION_END"

    /** Printed by LOGIND_SCRIPT when the desktop restarts: the app starts the session again. */
    const val SESSION_RESTART_MARKER = "DROIDNUX_SESSION_RESTART"

    /** Printed by Plasma's session once KDE's wallet opens with the new password (see [sessionCommand]). */
    const val WALLET_MOVED_MARKER = "DROIDNUX_WALLET_MOVED"

    /**
     * Command starting the session, with the X keyboard [layout] (e.g. "it"),
     * as [user] (a valid Linux user name; empty: the default one). The
     * session reads the user's password from its input, one line, then the
     * password before it (empty line if it did not change): KDE's wallet
     * moves to the new one, and [WALLET_MOVED_MARKER] says so.
     */
    /** The places of [user]'s Dolphin in [rootfs], when they are a real file there (never through a guest link). */
    fun userPlaces(rootfs: File, user: String): File? =
        File(rootfs.canonicalFile, "home/${user.ifEmpty { DESKTOP_USER }}/.local/share/user-places.xbel")
            .takeIf { it.isFile && it.canonicalPath == it.path }

    /** [brightness]: Android's, 0 to 1 as the backlight has it, for KWin to start with. */
    fun sessionCommand(layout: String, user: String = "", verbose: Boolean = false, brightness: Float? = null): String =
        "DROIDNUX_XKB_LAYOUT=$layout DROIDNUX_LOCALE=${glibcLocale()} " +
            (if (user.isNotEmpty()) "DROIDNUX_USER=$user " else "") +
            (if (brightness != null) "DROIDNUX_BRIGHTNESS=${"%.3f".format(java.util.Locale.ROOT, brightness)} " else "") +
            (if (verbose) "DROIDNUX_VERBOSE=1 " else "") +
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

    /**
     * Android's battery for the desktop's UPower (droidnux-upower): charge in percent,
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

    /** The directory where the network plugin writes [NETWORK_REQUEST], made if missing. */
    fun networkDir(rootfs: File): File = File(rootfs, NETWORK_DIR.removePrefix("/")).apply { mkdirs() }

    /**
     * Android's default network for the network plugin and the tray's applets: [fields] as
     * interface, name, type (wifi, cellular, ethernet, vpn), signal (percent), ipv4/ipv6
     * (address/prefix), ipv4gateway, ipv6gateway, ipv4dns, ipv6dns, received, sent (bytes),
     * time (ms of the counters) and bluetooth (1 when on); only bluetooth when offline.
     */
    fun writeNetwork(rootfs: File, fields: Map<String, Any>) {
        writeFile(rootfs, NETWORK_STATE, fields.entries.joinToString("") { "${it.key}=${it.value}\n" }.toByteArray())
    }

    /** The guest's /sys/class/backlight (Burrow binds it there), holding [backlightDevice]. */
    fun backlightClass(rootfs: File): File = File(rootfs, BACKLIGHT_DIR)

    /**
     * Android's screen as the backlight powerdevil's helper finds in /sys/class/backlight:
     * brightness from 0 to [BACKLIGHT_MAX], which the desktop's process writes and watches.
     * Laid out as sysfs has it, a class entry linking to the device (powerdevil takes the
     * link's target), with the uevent and subsystem udev asks for. Made if missing.
     */
    fun backlightDevice(rootfs: File): File {
        val device = File(backlightClass(rootfs), ".device/droidnux").apply { mkdirs() }
        for ((name, value) in listOf("max_brightness" to "$BACKLIGHT_MAX\n", "type" to "raw\n", "uevent" to "")) {
            val file = File(device, name)
            if (!file.isFile) file.writeText(value)
        }
        link(File(device, "subsystem"), "../../../../class/backlight")
        link(File(backlightClass(rootfs), "droidnux"), ".device/droidnux")
        return device
    }

    private fun link(file: File, target: String) {
        if (runCatching { Os.readlink(file.path) }.getOrNull() == target) return
        file.delete()
        Os.symlink(target, file.path)
    }

    /** Plasma's wallpaper as the last session copied it, for the home screen; it may be missing. */
    fun wallpaperFile(rootfs: File): File = File(rootfs, WALLPAPER_FILE.removePrefix("/"))

    /**
     * The mouse's settings from Plasma that the app applies: "leftHanded" (true or false) and
     * "pointerAcceleration" (-1 to 1, libinput's speed), one "name=value" a line. KWin renames it
     * into place at its start and at every change; missing before that and without the Plasma component.
     */
    fun pointerSettingsFile(rootfs: File): File = File(rootfs, POINTER_FILE.removePrefix("/"))

    /**
     * The orientation chosen in Plasma's display settings, as KWin's output transform: 0 (normal), 1, 2 or 3
     * (turned by 90, 180 or 270 degrees). KWin renames it into place at every change; missing until the
     * first one in a session.
     */
    fun orientationFile(rootfs: File): File = File(rootfs, ORIENTATION_FILE.removePrefix("/"))

    /**
     * "1" while an app inhibits idling (a video, a presentation) and the screen should stay on, else "0".
     * The session renames it into place at its start and at every change.
     */
    fun awakeFile(rootfs: File): File = File(rootfs, AWAKE_FILE.removePrefix("/"))

    /**
     * No power profiles in the battery applet: Android manages the CPU and an app
     * cannot change it, so the applet would only say they are not available. The
     * applet has no setting for it: its QML is changed in place, again after
     * every update of powerdevil.
     */
    private fun hidePowerProfiles(rootfs: File) {
        val dialog = File(rootfs, "usr/share/plasma/plasmoids/org.kde.plasma.battery/contents/ui/PopupDialog.qml")
        if (java.nio.file.Files.isSymbolicLink(dialog.toPath()) || !dialog.isFile) return
        val qml = dialog.readText()
        if (HIDDEN_PROFILES in qml) return
        val hidden = qml.replace(Regex("""(?m)^(\s*)PowerProfileItem \{$""")) { "${it.value}\n${it.groupValues[1]}    $HIDDEN_PROFILES" }
        if (hidden != qml) writeFile(rootfs, "/${dialog.relativeTo(rootfs).path}", hidden.toByteArray())
    }

    private const val HIDDEN_PROFILES = "visible: false // DroidNux: Android manages power"

    /**
     * System Monitor's tables (processes, apps) with a finger: upstream they
     * never flick (a drag selects rows) and a tap is also a right click (a
     * TapHandler's acceptedButtons does not filter touch), which opens the
     * column menu instead of sorting. The QML is changed in place, again after
     * every update of plasma-systemmonitor; Qt reads it from the disk only
     * without the qmldir's "prefer" of the compiled copy.
     */
    private fun touchTables(rootfs: File) {
        val dir = "usr/lib/aarch64-linux-gnu/qt6/qml/org/kde/ksysguard/table"
        val rightTap = Regex("""(?m)^(\s*)acceptedButtons: Qt\.RightButton$""")
        val mouseOnly = { m: MatchResult ->
            "${m.value}\n${m.groupValues[1]}acceptedDevices: PointerDevice.Mouse | PointerDevice.TouchPad | PointerDevice.Stylus $TOUCH_TABLES"
        }
        val table = patchFile(rootfs, "$dir/BaseTableView.qml", TOUCH_TABLES) { qml ->
            qml.replace("interactive: false\n", "interactive: Kirigami.Settings.hasTransientTouchInput $TOUCH_TABLES\n")
                .replace(
                    "selectionMode: SelectionRectangle.Drag\n",
                    "selectionMode: Kirigami.Settings.hasTransientTouchInput ? SelectionRectangle.PressAndHold : SelectionRectangle.Drag\n",
                )
                .replace(rightTap, mouseOnly)
        }
        val header = patchFile(rootfs, "$dir/TableViewHeader.qml", TOUCH_TABLES) { it.replace(rightTap, mouseOnly) }
        if (table && header) patchFile(rootfs, "$dir/qmldir", null) { it.replace(Regex("""(?m)^prefer .*\n"""), "") }
    }

    private const val TOUCH_TABLES = "// DroidNux: a finger flicks"

    /**
     * Changes a file of the rootfs in place, unless it is a link or already has
     * [marker]; [change] returns it as it is when it does not apply. True when
     * the file has [marker] (any file, for none).
     */
    private fun patchFile(rootfs: File, path: String, marker: String?, change: (String) -> String): Boolean {
        val file = File(rootfs, path)
        if (java.nio.file.Files.isSymbolicLink(file.toPath()) || !file.isFile) return false
        val text = file.readText()
        if (marker != null && marker in text) return true
        val changed = change(text)
        if (changed != text) writeFile(rootfs, "/$path", changed.toByteArray())
        return marker == null || marker in changed
    }

    /** Run once by plasmashell, on new and existing desktops alike. */
    private const val PLASMOIDS = "/usr/share/plasma/plasmoids"
    private const val PLASMA_BATTERY_UPDATE = "/usr/share/plasma/shells/org.kde.plasma.desktop/contents/updates/droidnux-battery.js"

    /** Read by Debian's chromium launcher. */
    private const val CHROMIUM_FLAGS_PATH = "/etc/chromium.d/droidnux"

    /** Default prefs of Debian's Firefox ESR (browser/defaults/preferences, next to Debian's own). */
    private const val FIREFOX_PREFS_PATH = "/usr/lib/firefox-esr/browser/defaults/preferences/droidnux.js"

    /**
     * The files of assets/desktop and where [prepare] writes them, with [render]'s
     * placeholders replaced; executable when they start with "#!".
     */
    internal val FILES = listOf(
        "droidnux-session" to SESSION_SCRIPT,
        "droidnux-apps" to APPS_SCRIPT,
        "pipewire.conf" to PIPEWIRE_CONF,
        "pipewire-client.conf" to PIPEWIRE_CLIENT_CONF,
        "wireplumber.conf" to WIREPLUMBER_CONF,
        // KWin: Android's keyboard as the input method (droidnux-im.desktop).
        "kwinrc" to "$PLASMA_DEFAULTS/kwinrc",
        // No lock screen: Android locks the device.
        "kscreenlockerrc" to "$PLASMA_DEFAULTS/kscreenlockerrc",
        // One wallet, opened at the session's start, that apps use without asking.
        "kwalletrc" to "$PLASMA_DEFAULTS/kwalletrc",
        "droidnux-wallet" to WALLET_SCRIPT,
        "droidnux-upower" to UPOWER_SCRIPT,
        "droidnux-logind" to LOGIND_SCRIPT,
        "droidnux-places" to PLACES_SCRIPT,
        "droidnux-app-groups" to APP_GROUPS_SCRIPT,
        "droidnux-bwrap" to BWRAP_SCRIPT,
        "droidnux-systemctl" to SYSTEMCTL_SCRIPT,
        "systemctl3.py" to SYSTEMCTL_PY,
        "droidnux-journalctl" to JOURNALCTL_SCRIPT,
        "droidnux-documents" to DOCUMENTS_SCRIPT,
        "droidnux-flatpak-gl" to FLATPAK_GL_SCRIPT,
        "droidnux-wallpaper" to WALLPAPER_SCRIPT,
        "system-bus.conf" to SYSTEM_BUS_CONF,
        // Plasma's brightness slider: powerdevil's helper writes [backlightDevice].
        "backlighthelper.service" to "$SYSTEM_SERVICES_DIR/$BACKLIGHT_HELPER.service",
        "droidnux-polkit" to POLKIT_SCRIPT,
        // Android dims, locks and sleeps the device: powerdevil does none of it.
        "powerdevilrc" to "$PLASMA_DEFAULTS/powerdevilrc",
        "droidnux-im.desktop" to IM_DESKTOP_FILE,
        // DroidNux's look-and-feel: Breeze Dark's defaults (org.kde.breezedark.desktop), the default
        // panel without Discover (not installed, an empty icon) in the task manager's launchers.
        "lnf-metadata.json" to "$PLASMA_LOOK_AND_FEEL/metadata.json",
        "lnf-defaults" to "$PLASMA_LOOK_AND_FEEL/contents/defaults",
        "lnf-layout.js" to "$PLASMA_LOOK_AND_FEEL/contents/layouts/org.kde.plasma.desktop-layout.js",
        // Plasma hides the battery while it is full and plugged in: always in the
        // panel instead, with its percentage, as on Android's status bar.
        "battery-update.js" to PLASMA_BATTERY_UPDATE,
        // VA-API decoding through the app costs Firefox more CPU than software (it
        // reimports every frame), so it stays off there (PLAN.md, phase 2).
        "firefox-prefs.js" to FIREFOX_PREFS_PATH,
        // Chromium's sandbox needs user namespaces or its set-user-ID helper, neither of which
        // Android allows (Electron apps: ELECTRON_DISABLE_SANDBOX in the session).
        "chromium-flags" to CHROMIUM_FLAGS_PATH,
        // gdb under Burrow: its traps pass, run starts the program without a shell.
        "gdbinit" to "/etc/gdb/gdbinit.d/droidnux.gdb",
        // Android's network and Bluetooth in the system tray, as status only: Android does not let apps switch them.
        "network-metadata.json" to "$PLASMOIDS/org.droidnux.network/metadata.json",
        "network-main.qml" to "$PLASMOIDS/org.droidnux.network/contents/ui/main.qml",
        "bluetooth-metadata.json" to "$PLASMOIDS/org.droidnux.bluetooth/metadata.json",
        "bluetooth-main.qml" to "$PLASMOIDS/org.droidnux.bluetooth/contents/ui/main.qml",
    )

    /** What @NAME@ stands for in the files of assets/desktop. */
    private val PLACEHOLDERS = mapOf(
        "APP_GROUPS_SCRIPT" to APP_GROUPS_SCRIPT,
        "AWAKE_FILE" to AWAKE_FILE,
        "BACKLIGHT_HELPER" to BACKLIGHT_HELPER,
        "BATTERY_FILE" to BATTERY_FILE,
        "BWRAP_SCRIPT" to BWRAP_SCRIPT,
        "SYSTEMCTL_PY" to SYSTEMCTL_PY,
        "SYSTEMCTL_SCRIPT" to SYSTEMCTL_SCRIPT,
        "JOURNALCTL_SCRIPT" to JOURNALCTL_SCRIPT,
        "DESKTOP_USER" to DESKTOP_USER,
        "DISPLAY" to "$DISPLAY",
        // The extension's builds, one "<branch> <url> <sha256>" line each.
        "FLATPAK_GL_EXTENSIONS" to Component.FLATPAK_GL.joinToString("\n") { "${it.branch} ${it.url} ${it.sha256}" },
        "FLATPAK_GL_SCRIPT" to FLATPAK_GL_SCRIPT,
        "GUEST_LIB_DIR" to GUEST_LIB_DIR,
        "GUEST_SDCARD" to GUEST_SDCARD,
        "ICON_CACHE" to ICON_CACHE,
        "IM_DESKTOP_FILE" to IM_DESKTOP_FILE,
        "IM_PROGRAM" to IM_PROGRAM,
        "KWIN_LIBRARY" to KWIN_LIBRARY,
        "LAUNCH_FIFO" to LAUNCH_FIFO,
        "LOGIND_SCRIPT" to LOGIND_SCRIPT,
        "MESA_PREFIX" to MESA_PREFIX,
        "NETWORK_STATE" to NETWORK_STATE,
        "PASSWORD_STAMP" to PASSWORD_STAMP,
        "PLACES_SCRIPT" to PLACES_SCRIPT,
        "PLASMA_DEFAULTS" to PLASMA_DEFAULTS,
        "PLASMA_PREFIX" to PLASMA_PREFIX,
        "POLKIT_SCRIPT" to POLKIT_SCRIPT,
        "ORIENTATION_FILE" to ORIENTATION_FILE,
        "POINTER_FILE" to POINTER_FILE,
        "SESSION_END_MARKER" to SESSION_END_MARKER,
        "SESSION_RESTART_MARKER" to SESSION_RESTART_MARKER,
        "SYSTEM_BUS_CONF" to SYSTEM_BUS_CONF,
        "SYSTEM_BUS_SOCKET" to SYSTEM_BUS_SOCKET,
        "SYSTEM_SERVICES_DIR" to SYSTEM_SERVICES_DIR,
        "UPOWER_SCRIPT" to UPOWER_SCRIPT,
        "WALLET_MOVED_MARKER" to WALLET_MOVED_MARKER,
        "WALLET_SCRIPT" to WALLET_SCRIPT,
        "WALLPAPER_FILE" to WALLPAPER_FILE,
        "WALLPAPER_SCRIPT" to WALLPAPER_SCRIPT,
    )

    /** [text] with each @NAME@ replaced by [PLACEHOLDERS]' value; an unknown name is a bug. */
    internal fun render(text: String): String = Regex("@([A-Z_]+)@").replace(text) {
        PLACEHOLDERS[it.groupValues[1]] ?: throw IllegalArgumentException("unknown placeholder ${it.value}")
    }

    // Written by earlier versions: an autoconfig script hiding Firefox's
    // "sandbox disabled" bar, and what only XFCE and proot used.
    private val OBSOLETE_FILES = listOf(
        "/usr/lib/firefox-esr/defaults/pref/droidnux-autoconfig.js",
        "/usr/lib/firefox-esr/droidnux.cfg",
        "/usr/local/bin/droidnux-plasma-session",
        "/usr/local/bin/droidnux-ui-scale",
        "$GUEST_LIB_DIR/libdroidnux-guest-shm.so",
        "$GUEST_LIB_DIR/libdroidnux-guest-paths.so",
        "$PLASMA_DEFAULTS/klipperrc",
    )

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
