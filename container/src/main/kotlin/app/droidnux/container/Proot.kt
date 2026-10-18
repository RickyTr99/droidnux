package app.droidnux.container

import android.content.Context
import java.io.File

/**
 * Builds proot command lines.
 *
 * proot and its loader ship as lib*.so in the APK and are extracted by the
 * installer into the native library directory, the only place an app with
 * targetSdk >= 29 may execute files from.
 */
class Proot(context: Context) {

    private val nativeDir = File(context.applicationInfo.nativeLibraryDir)
    private val tmpDir = File(context.cacheDir, "proot-tmp")

    val binary = File(nativeDir, "libproot.so")
    val loader = File(nativeDir, "libproot-loader.so")

    /** Environment proot itself needs (not the guest's). */
    fun hostEnvironment(): Map<String, String> {
        tmpDir.mkdirs()
        return mapOf(
            "PROOT_LOADER" to loader.absolutePath,
            "PROOT_TMP_DIR" to tmpDir.absolutePath,
        )
    }

    /** `proot --version`, useful to check that the binary runs at all. */
    fun versionCommand(): List<String> = listOf(binary.absolutePath, "--version")

    /**
     * Runs [command] inside [rootfs] as fake root, with a clean guest
     * environment.
     */
    fun guestCommand(rootfs: File, command: List<String>, workDir: String = "/root"): List<String> =
        buildList {
            add(binary.absolutePath)
            add("--kill-on-exit")
            add("--link2symlink")
            add("--sysvipc")
            add("-L")
            add("-0")
            add("-r"); add(rootfs.absolutePath)
            for (bind in DEFAULT_BINDS) {
                add("-b"); add(bind)
            }
            add("-w"); add(workDir)
            add("/usr/bin/env"); add("-i")
            addAll(GUEST_ENVIRONMENT.map { (k, v) -> "$k=$v" })
            addAll(command)
        }

    private companion object {
        val DEFAULT_BINDS = listOf("/dev", "/proc", "/sys")

        val GUEST_ENVIRONMENT = linkedMapOf(
            "HOME" to "/root",
            "USER" to "root",
            "LANG" to "C.UTF-8",
            "TERM" to "xterm-256color",
            "PATH" to "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
        )
    }
}
