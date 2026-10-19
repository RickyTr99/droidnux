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
    const val DISPLAY = 0

    /** MIT-SHM segments the app's X server can attach (display/src/main/cpp/shm). */
    private const val SHM_LIBRARY = "libdroidnux-guest-shm.so"
    private const val GUEST_LIB_DIR = "/usr/local/lib/droidnux"

    /** Where the Mesa component (guest/mesa) is unpacked. */
    const val MESA_PREFIX = "/opt/droidnux/mesa"

    private val PACKAGES = listOf(
        "xfce4", "xfce4-terminal", "dbus-x11", "x11-xserver-utils", "xkb-data",
        "adwaita-icon-theme", "fonts-dejavu-core",
        // SVG loader for gdk-pixbuf: XFCE's default wallpaper and many icons are SVG.
        "librsvg2-common",
        // H.264 and AAC for Firefox: its bundled decoders cover only VP8/VP9/AV1/Opus/Vorbis.
        "libavcodec61",
        // No PipeWire yet: under proot its daemon stops answering clients after
        // the first reply and wireplumber spins at 100% CPU (PLAN.md, phase 3).
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
        writeFile(
            rootfs, "$GUEST_LIB_DIR/$SHM_LIBRARY", File(nativeLibraryDir, SHM_LIBRARY).readBytes(),
            mode = 0x1A4,
        )
        // Android composes the screen: a compositing window manager would only
        // add a second, slower composition pass.
        writeFile(
            rootfs, "/root/.config/xfce4/xfconf/xfce-perchannel-xml/xfwm4.xml", XFWM4_DEFAULTS.toByteArray(),
            overwrite = false,
        )
        writeFile(rootfs, FIREFOX_PREFS_PATH, FIREFOX_PREFS.toByteArray())
    }

    /** Command starting the session, with the X keyboard [layout] (e.g. "it"). */
    fun sessionCommand(layout: String): String = "DROIDNUX_XKB_LAYOUT=$layout exec $SESSION_SCRIPT"

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

            exec dbus-launch --exit-with-session "${'$'}0" --in-dbus
        fi

        # Session services without systemd start here, on the session bus.
        exec startxfce4
    """.trimIndent() + "\n"

    /** Default prefs of Debian's Firefox ESR (browser/defaults/preferences, next to Debian's own). */
    private const val FIREFOX_PREFS_PATH = "/usr/lib/firefox-esr/browser/defaults/preferences/droidnux.js"

    // Under proot, Firefox's content processes crash in their seccomp sandbox
    // (SIGSEGV at startup, pages never load).
    private val FIREFOX_PREFS = """
        // Written by DroidNux.
        pref("security.sandbox.content.level", 0);
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
