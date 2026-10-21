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
    /** Only for the Plasma (Wayland) desktop. */
    val waylandOnly: Boolean = false,
) {
    companion object {
        private const val RELEASES = "https://github.com/RickyTr99/droidnux/releases/download"

        /** Turnip (Vulkan on Adreno through KGSL), Zink (OpenGL on Vulkan), GBM (KWin) and the VA-API driver: guest/mesa, guest/va. */
        val MESA = Component(
            id = "mesa",
            version = "26.2.5-dn4",
            url = "$RELEASES/mesa-26.2.5-dn4/droidnux-mesa-26.2.5-dn4-arm64.tar.xz",
            sha256 = "2a2e97cb162feeab9c465a37b2feb0ecec267659b848eadf01cf410954dd523c",
            sizeBytes = 5_231_000,
            guestPath = DesktopEnvironment.MESA_PREFIX,
        )

        /** Papirus icons with their caches, and Arc-Dark window borders at 1.5x and 2x: guest/theme. */
        val THEME = Component(
            id = "theme",
            version = "20250501-dn1",
            url = "$RELEASES/theme-20250501-dn1/droidnux-theme-20250501-dn1.tar.xz",
            sha256 = "e247749f3fba45cecd559f7caa53672b26b675660ee18f662eae05723a32f6ba",
            sizeBytes = 17_731_972,
            guestPath = DesktopEnvironment.THEME_PREFIX,
        )

        /** KSvg with fewer file lookups, which proot makes slow: guest/plasma. */
        val PLASMA = Component(
            id = "plasma",
            version = "6.13.0-dn1",
            url = "$RELEASES/plasma-6.13.0-dn1/droidnux-plasma-6.13.0-dn1-arm64.tar.xz",
            sha256 = "",
            sizeBytes = 0,
            guestPath = DesktopEnvironment.PLASMA_PREFIX,
            waylandOnly = true,
        )

        val ALL = listOf(MESA, THEME, PLASMA)

        /** The components a desktop uses. */
        fun all(wayland: Boolean) = ALL.filter { wayland || !it.waylandOnly }
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
