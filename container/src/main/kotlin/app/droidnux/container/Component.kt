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
            version = "26.2.5-dn8",
            url = "$RELEASES/mesa-26.2.5-dn8/droidnux-mesa-26.2.5-dn8-arm64.tar.xz",
            sha256 = "c4f0818f251c18383b28779c6ba0055e3a2465fb7af5b2ae1c81bcfbe18aaa4e",
            sizeBytes = 5_223_644,
            guestPath = DesktopEnvironment.MESA_PREFIX,
        )

        /** KSvg and KIconThemes with fewer file lookups, KWin passing on the scroll source and taking the backlight, and system monitor plugins for Android: guest/plasma. */
        val PLASMA = Component(
            id = "plasma",
            version = "6.13.0-dn7",
            url = "$RELEASES/plasma-6.13.0-dn7/droidnux-plasma-6.13.0-dn7-arm64.tar.xz",
            sha256 = "eaaa434fca4482d64c4c8593b1869e56882039560e65588a7dbed33199bab263",
            sizeBytes = 2_130_836,
            guestPath = DesktopEnvironment.PLASMA_PREFIX,
        )

        val ALL = listOf(MESA, PLASMA)

        /** [MESA] as Flatpak's GL extension, one per Freedesktop runtime branch (guest/mesa/flatpak.sh): the session installs it. */
        val FLATPAK_GL = listOf(
            FlatpakExtension(
                "25.08", "$RELEASES/mesa-26.2.5-dn8/droidnux-mesa-26.2.5-dn8-flatpak-25.08-arm64.flatpak",
                "245b6217173633aaecddedc777a94110514722a76657ce7a899d61cd3b6a96f4",
            ),
            FlatpakExtension(
                "26.08", "$RELEASES/mesa-26.2.5-dn8/droidnux-mesa-26.2.5-dn8-flatpak-26.08-arm64.flatpak",
                "70879d160a919212b9bb2d78826970398ca900302c0e2efd27f5158dd3ca825e",
            ),
        )
    }
}

/** A Flatpak bundle of the extension for runtime [branch], at [url]. */
data class FlatpakExtension(val branch: String, val url: String, val sha256: String)

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
