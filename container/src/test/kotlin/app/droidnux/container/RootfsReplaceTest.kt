package app.droidnux.container

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.io.File
import java.nio.file.Files

class RootfsReplaceTest {

    private val base = Files.createTempDirectory("droidnux-rootfs").toFile()
    private val target = File(base, "debian")
    private val staging = File(base, ".debian.partial")
    private val old = File(base, ".debian.old")

    private fun rootfs(dir: File, version: String, document: String? = null) {
        File(dir, "home").mkdirs()
        File(dir, "version").writeText(version)
        if (document != null) File(dir, "home/user").apply { mkdirs() }.resolve("document").writeText(document)
    }

    private fun document() = File(target, "home/user/document")

    @Test
    fun keepsTheHome() {
        rootfs(target, "1", "mine")
        rootfs(staging, "2")
        RootfsInstaller.replace(target, staging, old, keepHome = true)
        assertEquals("2", File(target, "version").readText())
        assertEquals("mine", document().readText())
        assertFalse(old.exists())
        assertFalse(staging.exists())
    }

    @Test
    fun dropsTheHomeWhenNotAsked() {
        rootfs(target, "1", "mine")
        rootfs(staging, "2")
        RootfsInstaller.replace(target, staging, old, keepHome = false)
        assertFalse(document().exists())
    }

    @Test
    fun finishesAnInstallThatDiedAfterMovingTheOldOneAway() {
        rootfs(old, "1", "mine")
        rootfs(staging, "2")
        RootfsInstaller.replace(target, staging, old, keepHome = false)
        assertEquals("mine", document().readText())
    }

    @Test
    fun aLeftoverOldOneIsNotTakenForTheHome() {
        rootfs(old, "0", "stale")
        rootfs(target, "1", "mine")
        rootfs(staging, "2")
        RootfsInstaller.replace(target, staging, old, keepHome = true)
        assertEquals("mine", document().readText())
    }

    @Test
    fun firstInstall() {
        rootfs(staging, "1")
        RootfsInstaller.replace(target, staging, old, keepHome = true)
        assertEquals("1", File(target, "version").readText())
    }
}
