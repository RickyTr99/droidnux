package app.droidnux.container

import android.content.Context
import android.os.Environment
import android.system.ErrnoException
import android.system.Os
import java.io.File

/** Where the device's shared storage appears in the guest. */
internal const val GUEST_SDCARD = "/sdcard"

/**
 * Builds the command lines of Burrow, the guest runtime (see burrow/burrow.h).
 *
 * It ships as lib*.so in the APK and is extracted by the installer into the
 * native library directory, the only place an app with targetSdk >= 29 may
 * execute files from.
 */
class Burrow(context: Context) {

    private val binary = File(context.applicationInfo.nativeLibraryDir, "libburrow.so")
    private val gpuNodeDir = File(context.filesDir, "gpu-node")
    private val statsFile = File(context.filesDir, "burrow-stats")

    /** Where the guest's DNS queries go (the app's proxy); 0 while it is not running. */
    val dnsPort: Int get() = DnsProxy.port

    /**
     * Runs [command] inside [rootfs] as fake root, with a clean guest
     * environment.
     */
    fun guestCommand(rootfs: File, command: List<String>, workDir: String = "/root"): List<String> =
        buildList {
            add(binary.absolutePath)
            add("-r"); add(rootfs.absolutePath)
            if (dnsPort > 0) { add("-d"); add(dnsPort.toString()) }
            // Per-syscall counts of Burrow's handler while the file exists (tools/burrow-stats.py).
            if (statsFile.exists()) { add("-s"); add(statsFile.absolutePath) }
            for (bind in DEFAULT_BINDS) {
                add("-b"); add(bind)
            }
            // Android has no /dev/shm; glibc shm_open() needs one.
            add("-b"); add("${File(rootfs, SHM_DIR).apply { mkdirs() }.absolutePath}:/dev/shm")
            // The device's shared storage, once the user allows access to all files.
            if (Environment.isExternalStorageManager()) {
                add("-b"); add("${Environment.getExternalStorageDirectory().absolutePath}:$GUEST_SDCARD")
            }
            addAll(gpuNodeBinds())
            add("-w"); add(workDir)
            add("--")
            add("/usr/bin/env"); add("-i")
            addAll(GUEST_ENVIRONMENT.map { (k, v) -> "$k=$v" })
            // Android's time zone (the guest's /etc/localtime says UTC).
            add("TZ=${java.util.TimeZone.getDefault().id}")
            addAll(command)
        }

    /**
     * Shows the KGSL device to the guest as the DRM render node
     * /dev/dri/renderD128, with the sysfs entries libdrm reads, so Mesa's
     * loader and EGL see a GPU device. Without one, EGL falls back to
     * software and Firefox blocklists WebRender. KGSL answers no DRM ioctl:
     * Turnip's msm probe skips the node and keeps using KGSL.
     *
     * libdrm also finds a node's type from the device number alone
     * (drmGetNodeTypeFromFd, which libva's vaGetDisplayDRM needs): it looks
     * for /dev/dri/card<minor> or /dev/dri/renderD<minor>, so the device is
     * also shown under its real minor.
     */
    private fun gpuNodeBinds(): List<String> {
        val rdev = try {
            Os.stat(KGSL_DEVICE).st_rdev
        } catch (_: ErrnoException) {
            return emptyList()
        }
        val major = ((rdev shr 8) and 0xfff).toInt()
        val minor = ((rdev and 0xff) or ((rdev shr 12) and 0xfff00)).toInt()
        val sys = File(gpuNodeDir, "sys")
        val device = File(sys, "$major:$minor/device")
        val dri = File(gpuNodeDir, "dri")
        File(device, "drm/$RENDER_NODE").mkdirs()
        dri.mkdirs()
        File(device, "drm/$RENDER_NODE/dev").writeText("$major:$minor\n")
        File(device, "uevent").writeText(
            "DRIVER=kgsl-3d0\nMODALIAS=platform:kgsl-3d0\nOF_FULLNAME=/soc/qcom,kgsl-3d0\n" +
                "OF_COMPATIBLE_0=qcom,kgsl-3d0\nOF_COMPATIBLE_N=1\n",
        )
        for ((name, target) in listOf("subsystem" to "/sys/bus/platform", "driver" to "/sys/bus/platform/drivers/msm_drm")) {
            val link = File(device, name)
            if (runCatching { Os.readlink(link.path) }.getOrNull() != target) {
                link.delete()
                Os.symlink(target, link.path)
            }
        }
        val minorNode = "renderD$minor"
        File(dri, RENDER_NODE).createNewFile() // mount points for the device
        File(dri, minorNode).createNewFile()
        return listOf(
            "-b", "${sys.absolutePath}:/sys/dev/char",
            "-b", "${dri.absolutePath}:/dev/dri",
            "-b", "$KGSL_DEVICE:/dev/dri/$RENDER_NODE",
            "-b", "$KGSL_DEVICE:/dev/dri/$minorNode",
        )
    }

    private companion object {
        const val KGSL_DEVICE = "/dev/kgsl-3d0"
        const val RENDER_NODE = "renderD128"

        val DEFAULT_BINDS = listOf("/dev", "/proc", "/sys")
        const val SHM_DIR = "tmp/.droidnux-shm"

        val GUEST_ENVIRONMENT = linkedMapOf(
            "HOME" to "/root",
            "USER" to "root",
            "LANG" to "C.UTF-8",
            "TERM" to "xterm-256color",
            "PATH" to "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
        )
    }
}
