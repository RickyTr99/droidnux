package app.droidnux.container

import app.droidnux.container.Archive.deleteRecursivelyForce
import app.droidnux.container.Archive.readTextOrNull
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * A piece of the guest that DroidNux builds and ships separately from the APK
 * (built in CI from guest/<id>, published as a GitHub release), unpacked
 * into its own directory of the rootfs.
 *
 * Hardcoded for now; it will move to the :components manifest.
 */
data class Component(
    val id: String,
    val version: String,
    val url: String,
    val sha256: String,
    val sizeBytes: Long,
    /** Absolute guest path the archive's contents go to. */
    val guestPath: String,
) {
    companion object {
        private const val RELEASES = "https://github.com/RickyTr99/droidnux/releases/download"

        /** Turnip (Vulkan on Adreno through KGSL), Zink (OpenGL on Vulkan), GBM (KWin) and the VA-API driver: guest/mesa, guest/va. */
        val MESA = Component(
            id = "mesa",
            version = "26.2.5-dn6",
            url = "$RELEASES/mesa-26.2.5-dn6/droidnux-mesa-26.2.5-dn6-arm64.tar.xz",
            sha256 = "be525fbaffee235acb0f5777fff4fb4b8a0ebd9c806beb9f1353cf4c63b447bd",
            sizeBytes = 5_239_920,
            guestPath = DesktopEnvironment.MESA_PREFIX,
        )

        /** KSvg and KIconThemes with fewer file lookups, and system monitor plugins for Android: guest/plasma. */
        val PLASMA = Component(
            id = "plasma",
            version = "6.13.0-dn4",
            url = "$RELEASES/plasma-6.13.0-dn4/droidnux-plasma-6.13.0-dn4-arm64.tar.xz",
            sha256 = "4290bce12d8c861fded03076668d2ad7b3606b98faa37dca91c4614cf77c5b6a",
            sizeBytes = 203_544,
            guestPath = DesktopEnvironment.PLASMA_PREFIX,
        )

        val ALL = listOf(MESA, PLASMA)
    }
}

/** Installs [Component]s into a rootfs, replacing older versions atomically. */
class ComponentInstaller(private val downloadDir: File) {

    fun installedVersion(rootfs: File, component: Component): String? =
        File(dir(rootfs, component), MARKER).readTextOrNull()?.lineSequence()?.firstOrNull()

    fun isInstalled(rootfs: File, component: Component): Boolean =
        File(dir(rootfs, component), MARKER).readTextOrNull()?.lines()?.getOrNull(1) == component.sha256

    suspend fun install(rootfs: File, component: Component, onProgress: (InstallProgress) -> Unit) =
        withContext(Dispatchers.IO) {
            val archive = File(downloadDir, "${component.sha256}.tar.xz")
            Archive.download(component.url, component.sha256, component.sizeBytes, archive, onProgress)
            val target = dir(rootfs, component)
            val staging = File(target.parentFile, ".${target.name}.partial")
            staging.deleteRecursivelyForce()
            staging.mkdirs()
            Archive.extract(archive, Archive.Compression.XZ, staging, onProgress)
            File(staging, MARKER).writeText("${component.version}\n${component.sha256}\n")

            // Running guest processes keep the files they have open or mapped.
            target.deleteRecursivelyForce()
            if (!staging.renameTo(target)) throw InstallException("Cannot move ${component.id} into $target")
            archive.delete()
        }

    suspend fun uninstall(rootfs: File, component: Component) = withContext(Dispatchers.IO) {
        dir(rootfs, component).deleteRecursivelyForce()
    }

    /** The component's directory, refusing guest symlinks on the way (they point at host paths). */
    private fun dir(rootfs: File, component: Component): File {
        val dir = File(rootfs, component.guestPath.removePrefix("/"))
        var parent = dir.parentFile
        while (parent != null && parent != rootfs) {
            if (java.nio.file.Files.isSymbolicLink(parent.toPath())) {
                throw InstallException("${component.guestPath} goes through a symlink in the rootfs")
            }
            parent = parent.parentFile
        }
        return dir
    }

    private companion object {
        const val MARKER = ".droidnux-component"
    }
}
