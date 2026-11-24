package app.droidnux.container

import android.content.Context
import app.droidnux.container.Archive.deleteIfLink
import app.droidnux.container.Archive.deleteRecursivelyForce
import app.droidnux.container.Archive.readTextOrNull
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption

/**
 * Downloads, verifies and unpacks a [Distro] into the app's private storage.
 *
 * The rootfs is unpacked into a staging directory and renamed into place only
 * when complete, so an interrupted install never looks installed.
 */
class RootfsInstaller(context: Context) {

    private val rootfsBase = File(context.filesDir, "rootfs")
    private val downloadDir = File(context.cacheDir, "downloads")

    fun rootfsDir(distro: Distro) = File(rootfsBase, distro.id)

    fun isInstalled(distro: Distro): Boolean =
        File(rootfsDir(distro), MARKER).readTextOrNull()?.trim() == distro.sha256

    /** [keepHome]: the users' files (/home) of the rootfs being replaced go to the new one. */
    suspend fun install(distro: Distro, keepHome: Boolean = false, onProgress: (InstallProgress) -> Unit): File =
        withContext(Dispatchers.IO) {
            val archive = File(downloadDir, "${distro.sha256}.tar.gz")
            val staging = File(rootfsBase, ".${distro.id}.partial")
            // Complete already when an install died while putting it in place: only that is left to do.
            if (File(staging, MARKER).readTextOrNull()?.trim() != distro.sha256) {
                Archive.download(distro.url, distro.sha256, distro.sizeBytes, archive, onProgress)
                staging.deleteRecursivelyForce()
                staging.mkdirs()
                Archive.extract(archive, Archive.Compression.GZIP, staging, onProgress)
                configure(staging)
                File(staging, MARKER).writeText(distro.sha256)
            }
            val target = rootfsDir(distro)
            replace(target, staging, File(rootfsBase, ".${distro.id}.old"), keepHome)
            archive.delete()
            target
        }

    suspend fun uninstall(distro: Distro) = withContext(Dispatchers.IO) {
        rootfsDir(distro).deleteRecursivelyForce()
    }

    /** Files that container images expect the runtime to provide. */
    private fun configure(root: File) {
        val etc = File(root, "etc").apply { mkdirs() }
        File(etc, "resolv.conf").apply {
            deleteIfLink()
            writeText("nameserver 1.1.1.1\nnameserver 8.8.8.8\n")
        }
        File(etc, "hosts").apply {
            if (!exists()) writeText("127.0.0.1 localhost\n::1 localhost ip6-localhost ip6-loopback\n")
        }
        File(root, "tmp").mkdirs()
    }

    internal companion object {
        private const val MARKER = ".droidnux-rootfs"

        /**
         * Puts [staging] in [target]'s place, the rootfs that was there moved to [old] until it is done;
         * with [keepHome] its /home goes to the new one. Only renames until then: an install that dies
         * here loses nothing, the next one finishes it.
         */
        internal fun replace(target: File, staging: File, old: File, keepHome: Boolean) {
            val replaced = target.exists()
            if (replaced) {
                old.deleteRecursivelyForce() // what an earlier install had not finished deleting
                if (!target.renameTo(old)) throw InstallException("Cannot move $target away")
            }
            // No target but an old one: an install died after moving it away. Its home is kept, whatever was asked then.
            if (keepHome || !replaced) moveHome(old, staging)
            if (!staging.renameTo(target)) throw InstallException("Cannot move rootfs into $target")
            old.deleteRecursivelyForce()
        }

        private fun moveHome(old: File, new: File) {
            val home = File(old, "home")
            // A real directory only: a guest link would point at the host.
            if (!Files.isDirectory(home.toPath(), LinkOption.NOFOLLOW_LINKS)) return
            val target = File(new, "home")
            target.deleteRecursivelyForce() // the image's empty one
            if (!home.renameTo(target)) throw InstallException("Cannot keep $home")
        }
    }
}
