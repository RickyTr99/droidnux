package app.droidnux.container

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.io.File

class DesktopFilesTest {

    private val dir = File("src/main/assets/desktop")

    @Test
    fun everyAssetIsWritten() {
        assertEquals(dir.list()!!.toSet(), DesktopEnvironment.FILES.map { it.first }.toSet())
    }

    @Test
    fun placeholdersAreKnown() {
        for ((asset, _) in DesktopEnvironment.FILES) {
            // render throws on an unknown name; a lowercase or misspelled one would stay.
            val text = DesktopEnvironment.render(File(dir, asset).readText())
            assertFalse(asset, Regex("@[A-Za-z_]+@").containsMatchIn(text))
        }
    }

    /** Syntax of the shell and Python scripts, where the host has sh and python3 (CI does). */
    @Test
    fun scriptsParse() {
        for ((asset, _) in DesktopEnvironment.FILES) {
            val text = DesktopEnvironment.render(File(dir, asset).readText())
            val check = when (text.lineSequence().first()) {
                "#!/bin/sh" -> listOf("sh", "-n")
                "#!/usr/bin/python3" -> listOf("python3", "-c", "import sys; compile(sys.stdin.read(), sys.argv[1], 'exec')", asset)
                else -> continue
            }
            if (run(listOf(check[0], "-c", ""), "").first != 0) continue
            val (status, output) = run(check, text)
            assertEquals("$asset: $output", 0, status)
        }
    }

    /** Exit status and output of [command] given [input]; -1 when it cannot start. */
    private fun run(command: List<String>, input: String): Pair<Int, String> {
        val process = runCatching { ProcessBuilder(command).redirectErrorStream(true).start() }.getOrNull()
            ?: return -1 to ""
        process.outputStream.use { it.write(input.toByteArray()) }
        val output = process.inputStream.bufferedReader().readText()
        return process.waitFor() to output
    }
}
