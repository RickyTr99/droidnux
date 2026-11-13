package app.droidnux.container

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.File
import java.nio.file.Files

class ComponentReplaceTest {

    private val base = Files.createTempDirectory("droidnux-component").toFile()
    private val target = File(base, "plasma")
    private val installer = ComponentInstaller(File(base, "downloads"))

    private fun staging(version: String) = File(base, ".plasma.partial").apply {
        mkdirs()
        File(this, "version").writeText(version)
    }

    private fun version() = File(target, "version").readText()

    @Test
    fun failedSetupKeepsTheOldVersion() {
        runBlocking { installer.replace(target, staging("1")) {} }
        assertThrows(InstallException::class.java) {
            runBlocking {
                installer.replace(target, staging("2")) {
                    assertEquals("2", version()) // the setup sees the new one
                    throw InstallException("setup")
                }
            }
        }
        assertEquals("1", version())
        assertFalse(File(base, ".plasma.old").exists())
    }

    @Test
    fun failedFirstInstallLeavesNothing() {
        assertThrows(InstallException::class.java) {
            runBlocking { installer.replace(target, staging("1")) { throw InstallException("setup") } }
        }
        assertFalse(target.exists())
    }

    @Test
    fun oldVersionLeftAsideComesBack() {
        File(base, ".plasma.old").apply { mkdirs(); File(this, "version").writeText("1") }
        assertThrows(InstallException::class.java) {
            runBlocking { installer.replace(target, staging("2")) { throw InstallException("setup") } }
        }
        assertEquals("1", version())
    }
}
