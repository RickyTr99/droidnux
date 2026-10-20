package app.droidnux.container

import android.system.Os
import java.io.File
import java.io.IOException

/**
 * The desktop that runs in the container (XFCE for now): what to install, and
 * the files that adapt it to DroidNux (session script, defaults, libraries).
 */
object DesktopEnvironment {

    const val SESSION_SCRIPT = "/usr/local/bin/droidnux-session"
    private const val UI_SCALE_SCRIPT = "/usr/local/bin/droidnux-ui-scale"
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

    private val PACKAGES = listOf(
        "xfce4", "xfce4-terminal", "dbus-x11", "x11-xserver-utils", "xkb-data",
        "adwaita-icon-theme", "fonts-dejavu-core",
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
    fun installScript(): String = """
        set -e
        export DEBIAN_FRONTEND=noninteractive
        apt-get update
        apt-get install -y --no-install-recommends ${PACKAGES.joinToString(" ")}
        apt-get clean
        mkdir -p /${PACKAGES_MARKER.substringBeforeLast('/')}
        echo '${PACKAGES.joinToString(" ")}' > /$PACKAGES_MARKER
        echo DROIDNUX_INSTALL_OK
    """.trimIndent()

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
        writeFile(
            rootfs, "$GUEST_LIB_DIR/$SHM_LIBRARY", File(nativeLibraryDir, SHM_LIBRARY).readBytes(),
            mode = 0x1A4,
        )
        writeFile(rootfs, "$PIPEWIRE_MODULE_DIR/$PIPEWIRE_MODULE", File(nativeLibraryDir, PIPEWIRE_MODULE).readBytes())
        writeFile(rootfs, PIPEWIRE_CONF, PIPEWIRE_DEFAULTS.toByteArray())
        writeFile(rootfs, WIREPLUMBER_CONF, WIREPLUMBER_DEFAULTS.toByteArray())
        // Android composes the screen: a compositing window manager would only
        // add a second, slower composition pass.
        writeFile(
            rootfs, "/root/.config/xfce4/xfconf/xfce-perchannel-xml/xfwm4.xml", XFWM4_DEFAULTS.toByteArray(),
            overwrite = false,
        )
        writeFile(rootfs, FIREFOX_PREFS_PATH, FIREFOX_PREFS.toByteArray())
        writeFile(rootfs, FIREFOX_AUTOCONFIG_PATH, FIREFOX_AUTOCONFIG.toByteArray())
        writeFile(rootfs, FIREFOX_CFG_PATH, FIREFOX_CFG.toByteArray())
    }

    /** Printed by the session script when the desktop logs out. */
    const val SESSION_END_MARKER = "DROIDNUX_SESSION_END"

    /** Command starting the session, with the X keyboard [layout] (e.g. "it"). */
    fun sessionCommand(layout: String, dpi: Int): String =
        "DROIDNUX_XKB_LAYOUT=$layout DROIDNUX_DPI=$dpi exec $SESSION_SCRIPT"

    /** Command applying the interface size ([dpi], 96 = 1x) to the running session. */
    fun uiScaleCommand(dpi: Int): String = "$UI_SCALE_SCRIPT $dpi"

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
            # MIT-SHM segments the X server can attach.
            export LD_PRELOAD=$GUEST_LIB_DIR/$SHM_LIBRARY${'$'}{LD_PRELOAD:+:${'$'}LD_PRELOAD}
            # Firefox's media decoder processes fail in their seccomp sandbox
            # under proot (video: "Failed to copy image data", audio: "no
            # decoder found"), so no video plays. Same as the content sandbox
            # in droidnux.js; Firefox has no prefs for these two.
            export MOZ_DISABLE_RDD_SANDBOX=1
            export MOZ_DISABLE_UTILITY_SANDBOX=1
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
        startxfce4
        echo $SESSION_END_MARKER
    """.trimIndent() + "\n"

    /** Default prefs of Debian's Firefox ESR (browser/defaults/preferences, next to Debian's own). */
    private const val FIREFOX_PREFS_PATH = "/usr/lib/firefox-esr/browser/defaults/preferences/droidnux.js"

    // Under proot, Firefox's content processes crash in their seccomp sandbox
    // (SIGSEGV at startup, pages never load). VA-API decoding through the app
    // costs Firefox more CPU than software (it reimports every frame), so it
    // stays off there (PLAN.md, phase 2).
    private val FIREFOX_PREFS = """
        // Written by DroidNux.
        pref("security.sandbox.content.level", 0);
        pref("media.hardware-video-decoding.enabled", false);
    """.trimIndent() + "\n"

    /** Autoconfig prefs: they must sit in the app's defaults/pref to load [FIREFOX_CFG]. */
    private const val FIREFOX_AUTOCONFIG_PATH = "/usr/lib/firefox-esr/defaults/pref/droidnux-autoconfig.js"
    private const val FIREFOX_CFG_PATH = "/usr/lib/firefox-esr/droidnux.cfg"

    private val FIREFOX_AUTOCONFIG = """
        // Written by DroidNux.
        pref("general.config.filename", "droidnux.cfg");
        pref("general.config.obscure_value", 0);
        pref("general.config.sandbox_enabled", false);
    """.trimIndent() + "\n"

    // With the content sandbox off (FIREFOX_PREFS) Firefox shows a warning bar
    // in every window and no pref hides it, so this script turns it off at
    // startup. Firefox skips the first line of an autoconfig file.
    private val FIREFOX_CFG = """
        // Written by DroidNux.
        try {
          ChromeUtils.importESModule("resource://gre/modules/SandboxUtils.sys.mjs")
            .SandboxUtils.maybeWarnAboutDisabledContentSandbox = () => {};
        } catch (e) {
          Cu.reportError(e);
        }
    """.trimIndent() + "\n"

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

    private val XFWM4_DEFAULTS = """
        <?xml version="1.0" encoding="UTF-8"?>
        <channel name="xfwm4" version="1.0">
          <property name="general" type="empty">
            <property name="use_compositing" type="bool" value="false"/>
          </property>
        </channel>
    """.trimIndent() + "\n"

    /**
     * Writes [content] at the guest [path] inside [rootfs], refusing to follow
     * symlinks out of it (guest links are absolute and would point at the host).
     * The file is replaced by a rename, so processes that have it open or
     * mapped keep the old copy.
     */
    private fun writeFile(rootfs: File, path: String, content: ByteArray, mode: Int = 0x1A4, overwrite: Boolean = true) {
        val file = File(rootfs, path.removePrefix("/"))
        val root = rootfs.canonicalPath
        val parent = file.parentFile!!
        parent.mkdirs()
        if (!parent.canonicalPath.let { it == root || it.startsWith("$root/") }) {
            throw IOException("$path resolves outside the rootfs")
        }
        if (java.nio.file.Files.isSymbolicLink(file.toPath())) file.delete()
        if (file.exists() && (!overwrite || file.length() == content.size.toLong() && file.readBytes().contentEquals(content))) {
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
}
