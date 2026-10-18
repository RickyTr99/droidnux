package app.droidnux.container

import android.content.Context
import app.droidnux.container.Archive.deleteIfLink
import app.droidnux.container.Archive.deleteRecursivelyForce
import app.droidnux.container.Archive.readTextOrNull
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

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

    suspend fun install(distro: Distro, onProgress: (InstallProgress) -> Unit): File =
        withContext(Dispatchers.IO) {
            val archive = File(downloadDir, "${distro.sha256}.tar.gz")
            Archive.download(distro.url, distro.sha256, distro.sizeBytes, archive, onProgress)
            val staging = File(rootfsBase, ".${distro.id}.partial")
            staging.deleteRecursivelyForce()
            staging.mkdirs()
            Archive.extract(archive, Archive.Compression.GZIP, staging, onProgress)
            configure(staging)
            File(staging, MARKER).writeText(distro.sha256)

            val target = rootfsDir(distro)
            target.deleteRecursivelyForce()
            if (!staging.renameTo(target)) throw InstallException("Cannot move rootfs into $target")
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

    private companion object {
        const val MARKER = ".droidnux-rootfs"
    }
}
