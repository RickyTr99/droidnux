package app.droidnux.container

import android.system.Os
import androidx.test.platform.app.InstrumentationRegistry
import app.droidnux.container.Archive.deleteRecursivelyForce
import kotlinx.coroutines.runBlocking
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.zip.GZIPOutputStream

/** Guest symlinks are absolute host paths: nothing on the Android side may follow them. */
class SymlinkSafetyTest {

    private lateinit var base: File
    private lateinit var root: File
    private lateinit var outside: File

    @Before
    fun setUp() {
        base = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "symlink-test")
        base.deleteRecursivelyForce()
        root = File(base, "root").apply { mkdirs() }
        outside = File(base, "outside").apply { mkdirs() }
        File(outside, "keep").writeText("keep")
    }

    @After
    fun tearDown() = base.deleteRecursivelyForce()

    @Test
    fun extractRefusesEntriesThroughPlantedSymlinks() {
        val archive = File(base, "evil.tar.gz")
        TarArchiveOutputStream(GZIPOutputStream(archive.outputStream())).use { tar ->
            tar.putArchiveEntry(TarArchiveEntry("link", TarArchiveEntry.LF_SYMLINK).apply { linkName = outside.path })
            tar.closeArchiveEntry()
            val data = "pwned".toByteArray()
            tar.putArchiveEntry(TarArchiveEntry("link/pwned").apply { size = data.size.toLong() })
            tar.write(data)
            tar.closeArchiveEntry()
        }
        assertThrows(InstallException::class.java) {
            runBlocking { Archive.extract(archive, Archive.Compression.GZIP, root) {} }
        }
        assertFalse(File(outside, "pwned").exists())
    }

    @Test
    fun deleteDoesNotFollowSymlinks() {
        Os.symlink(outside.path, File(root, "link").path)
        root.deleteRecursivelyForce()
        assertFalse(root.exists())
        assertTrue(File(outside, "keep").isFile)
    }

    @Test
    fun componentDirThroughSymlinkIsRefused() {
        // The rootfs's /opt points at a host directory.
        Os.symlink(outside.path, File(root, "opt").path)
        val installer = ComponentInstaller(File(base, "downloads"))
        assertThrows(InstallException::class.java) { installer.installedVersion(root, Component.MESA) }
        assertThrows(InstallException::class.java) { runBlocking { installer.uninstall(root, Component.MESA) } }
        assertTrue(File(outside, "keep").isFile)
    }
}
