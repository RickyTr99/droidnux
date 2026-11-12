package app.droidnux.container

import android.content.Context
import android.os.Build
import android.provider.Settings
import android.system.Os
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.util.TimeZone

/**
 * The system settings a Linux install sets up and Android knows: the host name (Burrow gives the guest
 * /etc/hostname's as uname()'s, Android's is "localhost") and the time zone. Written before each guest
 * command, never through a symlink planted in the rootfs.
 */
object GuestSystem {

    /** The names the rootfs image comes with: replaced; one the user chose stays. */
    private val IMAGE_HOSTNAMES = setOf("", "debuerreotype", "localhost")

    fun write(context: Context, rootfs: File) {
        val etc = File(rootfs, "etc").apply { mkdirs() }
        writeHostname(etc, hostnameOf(context))
        writeTimeZone(rootfs, etc)
    }

    /** Android's device name ("Tab S9+ di Riccardo" → tab-s9-di-riccardo), else the model. */
    internal fun hostnameOf(context: Context): String {
        val name = Settings.Global.getString(context.contentResolver, Settings.Global.DEVICE_NAME)
        return sanitize(name.orEmpty()).ifEmpty { sanitize(Build.MODEL) }.ifEmpty { "droidnux" }
    }

    /** RFC 1123: letters, digits and inner hyphens, at most 63. */
    internal fun sanitize(name: String): String =
        name.lowercase().replace(Regex("[^a-z0-9]+"), "-").take(63).trim('-')

    private fun writeHostname(etc: File, name: String) {
        val hostname = File(etc, "hostname")
        val current = if (isPlainFile(hostname)) hostname.readText().trim() else ""
        if (current !in IMAGE_HOSTNAMES) return
        replace(hostname, "$name\n")
        // Debian's installer line, so that the name resolves (sudo warns and waits otherwise).
        val hosts = File(etc, "hosts")
        val lines = if (isPlainFile(hosts)) hosts.readLines().filterNot { it.startsWith("127.0.1.1") }
        else listOf("127.0.0.1 localhost", "::1 localhost ip6-localhost ip6-loopback")
        replace(hosts, (lines.take(1) + "127.0.1.1 $name" + lines.drop(1)).joinToString("\n", postfix = "\n"))
    }

    /** /etc/localtime to Android's zone, when the guest has its data (tzdata). */
    private fun writeTimeZone(rootfs: File, etc: File) {
        val zone = TimeZone.getDefault().id
        if (!hasZone(rootfs, zone)) return
        val target = "/usr/share/zoneinfo/$zone"
        val localtime = File(etc, "localtime")
        if (runCatching { Os.readlink(localtime.path) }.getOrNull() != target) {
            localtime.delete()
            Os.symlink(target, localtime.path)
        }
        val timezone = File(etc, "timezone")
        if (!isPlainFile(timezone) || timezone.readText() != "$zone\n") replace(timezone, "$zone\n")
    }

    /** Whether the guest's tzdata has [zone] (a file, or one of its links). */
    fun hasZone(rootfs: File, zone: String): Boolean =
        !zone.contains("..") && Files.exists(File(rootfs, "usr/share/zoneinfo/$zone").toPath(), LinkOption.NOFOLLOW_LINKS)

    private fun isPlainFile(file: File) = Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS)

    private fun replace(file: File, content: String) {
        if (Files.isSymbolicLink(file.toPath())) file.delete()
        file.writeText(content)
    }
}
