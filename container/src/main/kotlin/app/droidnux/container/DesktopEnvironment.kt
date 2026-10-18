package app.droidnux.container

import android.system.Os
import java.io.File
import java.io.IOException

/**
 * The desktop that runs in the container (XFCE for now): what to install, and
 * the files that adapt it to DroidNux (session script, defaults).
 */
object DesktopEnvironment {

    const val SESSION_SCRIPT = "/usr/local/bin/droidnux-session"
    const val DISPLAY = 0

    private val PACKAGES = listOf(
        "xfce4", "xfce4-terminal", "dbus-x11", "x11-xserver-utils", "xkb-data",
        "adwaita-icon-theme", "fonts-dejavu-core",
    )

    /** Guest shell script installing the desktop (needs network). */
    fun installScript(): String = """
        set -e
        export DEBIAN_FRONTEND=noninteractive
        apt-get update
        apt-get install -y --no-install-recommends ${PACKAGES.joinToString(" ")}
        apt-get clean
        echo DROIDNUX_INSTALL_OK
    """.trimIndent()

    /** True when the desktop packages are present in [rootfs]. */
    fun isInstalled(rootfs: File): Boolean =
        File(rootfs, "usr/bin/startxfce4").exists() && File(rootfs, "usr/bin/dbus-launch").exists()

    /**
     * Writes the session script and DroidNux defaults into [rootfs]. Safe to
     * call before every session; user settings are never overwritten.
     */
    fun prepare(rootfs: File) {
        writeFile(rootfs, SESSION_SCRIPT, SESSION, mode = 0x1ED /* 0755 */)
        // Android composes the screen: a compositing window manager would only
        // add a second, slower composition pass.
        writeFile(
            rootfs, "/root/.config/xfce4/xfconf/xfce-perchannel-xml/xfwm4.xml", XFWM4_DEFAULTS,
            overwrite = false,
        )
    }

    /** Command starting the session, with the X keyboard [layout] (e.g. "it"). */
    fun sessionCommand(layout: String): String = "DROIDNUX_XKB_LAYOUT=$layout exec $SESSION_SCRIPT"

    private val SESSION = """
        #!/bin/sh
        # Written by DroidNux: starts the desktop on the app's X server.
        export DISPLAY=:$DISPLAY
        export XDG_RUNTIME_DIR=/tmp/runtime-${'$'}(id -u)
        export XDG_SESSION_TYPE=x11
        export XDG_CURRENT_DESKTOP=XFCE
        export SHELL=/bin/bash
        mkdir -p "${'$'}XDG_RUNTIME_DIR" && chmod 700 "${'$'}XDG_RUNTIME_DIR"
        mkdir -p /tmp/.ICE-unix && chmod 1777 /tmp/.ICE-unix

        # The X server starts with the app; wait for its socket.
        i=0
        while [ ! -S /tmp/.X11-unix/X$DISPLAY ] && [ ${'$'}i -lt 100 ]; do sleep 0.1; i=${'$'}((i + 1)); done

        if [ -n "${'$'}DROIDNUX_XKB_LAYOUT" ]; then
            setxkbmap "${'$'}DROIDNUX_XKB_LAYOUT" || echo "setxkbmap ${'$'}DROIDNUX_XKB_LAYOUT failed"
        fi

        exec dbus-launch --exit-with-session startxfce4
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
     */
    private fun writeFile(rootfs: File, path: String, content: String, mode: Int = 0x1A4, overwrite: Boolean = true) {
        val file = File(rootfs, path.removePrefix("/"))
        val root = rootfs.canonicalPath
        val parent = file.parentFile!!
        parent.mkdirs()
        if (!parent.canonicalPath.let { it == root || it.startsWith("$root/") }) {
            throw IOException("$path resolves outside the rootfs")
        }
        if (java.nio.file.Files.isSymbolicLink(file.toPath())) file.delete()
        if (file.exists() && !overwrite) return
        file.writeText(content)
        Os.chmod(file.path, mode)
    }
}
