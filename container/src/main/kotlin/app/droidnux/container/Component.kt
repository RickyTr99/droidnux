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
    /** Installed only when the user asks for it, not by the first run. */
    val optional: Boolean = false,
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

        /**
         * FEX, which runs x86 and x86-64 programs (Burrow hands them to it), with GL and Vulkan thunks to
         * [MESA]: guest/fex. Its setup adds Debian's amd64 and i386 architectures and their base libraries.
         * droidnux-x86 stands in for their Mesa (some 200 MB each with LLVM), unused behind the thunks, and
         * for the arm64 tools x86 packages depend on that Debian doesn't mark Multi-Arch: foreign (Valve's
         * steam_latest.deb wants apt, python3-apt, lsof and a terminal), so they install as on a PC; with
         * these missing, apt would take the x86 python3-apt in place of the arm64 one. FEX's ldd lists x86
         * programs' libraries too.
         */
        val FEX = Component(
            id = "fex",
            version = "2611-dn4",
            url = "$RELEASES/fex-2611-dn4/droidnux-fex-2611-dn4-arm64.tar.xz",
            sha256 = "b355617c354c5968dfc0c5592782c49a042011c040a71ddfbffaefbf8a86b5ff",
            sizeBytes = 3_424_988,
            guestPath = DesktopEnvironment.FEX_PREFIX,
            optional = true,
            setup = """
                set -e
                export DEBIAN_FRONTEND=noninteractive
                dpkg --add-architecture amd64
                dpkg --add-architecture i386
                apt-get update -q
                apt-get install -y -q --no-install-recommends python3-apt lsof
                dir=$(mktemp -d)
                provides='libglx-mesa0, libegl-mesa0, libgl1-mesa-dri, mesa-vulkan-drivers, mesa-va-drivers, mesa-vdpau-drivers'
                for p in apt python3 python3-apt lsof konsole; do
                    v=$(dpkg-query -W -f '${'$'}{db:Status-Abbrev}${'$'}{Version}' ${'$'}p 2>/dev/null) || continue
                    case ${'$'}v in ii*) provides="${'$'}provides, ${'$'}p (= ${'$'}{v#ii })" ;; esac
                done
                for arch in amd64 i386; do
                    mkdir -p ${'$'}dir/${'$'}arch/DEBIAN
                    printf 'Package: droidnux-x86\nVersion: 1\nArchitecture: %s\nMulti-Arch: same\nProvides: %s\nMaintainer: DroidNux\nDescription: arm64 GL and tools for x86 programs (FEX)\n' ${'$'}arch "${'$'}provides" > ${'$'}dir/${'$'}arch/DEBIAN/control
                    dpkg-deb -b ${'$'}dir/${'$'}arch ${'$'}dir/droidnux-x86_${'$'}arch.deb > /dev/null
                done
                # dpkg, not apt: it replaces an installed droidnux-x86 of the same or a newer version, whose
                # Provides may name versions of the tools that are gone.
                dpkg -i ${'$'}dir/droidnux-x86_*.deb
                apt-get install -y -q --no-install-recommends \
                    libc6:amd64 libgl1:amd64 libvulkan1:amd64 libc6:i386 libgl1:i386 libvulkan1:i386
                rm -rf ${'$'}dir
                ln -sf ${DesktopEnvironment.FEX_PREFIX}/bin/ldd /usr/local/bin/ldd
            """.trimIndent(),
        )

        val ALL = listOf(MESA, PLASMA, FEX)

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
