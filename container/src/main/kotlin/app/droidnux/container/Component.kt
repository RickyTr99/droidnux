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
    /** Guest shell script run as root after unpacking; the install fails if it does. */
    val setup: String? = null,
) {
    companion object {
        private const val RELEASES = "https://github.com/RickyTr99/droidnux/releases/download"

        /** Turnip (Vulkan on Adreno through KGSL), Zink (OpenGL on Vulkan), GBM (KWin) and the VA-API driver: guest/mesa, guest/va. */
        val MESA = Component(
            id = "mesa",
            version = "26.2.5-dn9",
            url = "$RELEASES/mesa-26.2.5-dn9/droidnux-mesa-26.2.5-dn9-arm64.tar.xz",
            sha256 = "c955ca0cfb072d3f583eec24f64805956bc578719bed9f52849ccae8287cee07",
            sizeBytes = 5_228_484,
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
                "25.08", "$RELEASES/mesa-26.2.5-dn9/droidnux-mesa-26.2.5-dn9-flatpak-25.08-arm64.flatpak",
                "4253bea5e137bbfd5469cb53bd472bb0d5a5ada6e352a17af19147e043f3ff1d",
            ),
            FlatpakExtension(
                "26.08", "$RELEASES/mesa-26.2.5-dn9/droidnux-mesa-26.2.5-dn9-flatpak-26.08-arm64.flatpak",
                "8d589b8713d19bb3dc99d12ee9dbd4a1213b57802a7eafe0eb1d7c045a1a7c7a",
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

    /** [setup] runs with the new version in place; if it throws, the version that was there comes back. */
    suspend fun install(rootfs: File, component: Component, onProgress: (InstallProgress) -> Unit, setup: suspend () -> Unit = {}) =
        withContext(Dispatchers.IO) {
            val archive = File(downloadDir, "${component.sha256}.tar.xz")
            Archive.download(component.url, component.sha256, component.sizeBytes, archive, onProgress)
            val target = dir(rootfs, component)
            val staging = File(target.parentFile, ".${target.name}.partial")
            staging.deleteRecursivelyForce()
            staging.mkdirs()
            Archive.extract(archive, Archive.Compression.XZ, staging, onProgress)
            File(staging, MARKER).writeText("${component.version}\n${component.sha256}\n")
            replace(target, staging, setup)
            archive.delete()
        }

    /** Puts [staging] in [target]'s place, keeping the old [target] aside until [setup] is done. */
    internal suspend fun replace(target: File, staging: File, setup: suspend () -> Unit) {
        val old = File(target.parentFile, ".${target.name}.old")
        // Left by an install the app died in: the old version if its move back never happened.
        if (old.exists() && !target.exists()) old.renameTo(target)
        old.deleteRecursivelyForce()
        // Running guest processes keep the files they have open or mapped.
        if (target.exists() && !target.renameTo(old)) throw InstallException("Cannot move $target away")
        try {
            if (!staging.renameTo(target)) throw InstallException("Cannot move $staging into $target")
            setup()
        } catch (e: Throwable) {
            target.deleteRecursivelyForce()
            old.renameTo(target)
            throw e
        }
        old.deleteRecursivelyForce()
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
