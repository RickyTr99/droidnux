package app.droidnux.container

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

data class CommandResult(val exitCode: Int, val output: String)

/** Entry point of the container layer for the rest of the app. */
class Container(context: Context) {

    private val context = context.applicationContext

    val proot = Proot(context)
    val installer = RootfsInstaller(context)

    /** Runs proot itself, without a rootfs. */
    suspend fun prootVersion(): CommandResult = run(proot.versionCommand())

    /** Runs [command] inside the installed [distro]. */
    suspend fun exec(distro: Distro, command: List<String>): CommandResult {
        check(installer.isInstalled(distro)) { "${distro.displayName} is not installed" }
        return run(guestCommand(distro, command))
    }

    suspend fun shell(distro: Distro, script: String): CommandResult =
        exec(distro, listOf("/bin/sh", "-c", script))

    /**
     * Starts a long-running shell [script] inside [distro] and returns at
     * once. Output (stdout and stderr merged) is read from the process; the
     * caller must destroy it when done.
     */
    fun spawnShell(distro: Distro, script: String): Process {
        check(installer.isInstalled(distro)) { "${distro.displayName} is not installed" }
        val command = guestCommand(distro, listOf("/bin/sh", "-c", script))
        return ProcessBuilder(command)
            .redirectErrorStream(true)
            .apply { environment().putAll(proot.hostEnvironment()) }
            .start()
    }

    /** proot command for [distro], with the guest network settings refreshed first. */
    private fun guestCommand(distro: Distro, command: List<String>): List<String> {
        val rootfs = installer.rootfsDir(distro)
        NetworkConfig.writeResolvConf(context, rootfs)
        return proot.guestCommand(rootfs, command)
    }

    private suspend fun run(command: List<String>, workDir: File? = null): CommandResult =
        withContext(Dispatchers.IO) {
            val process = ProcessBuilder(command)
                .redirectErrorStream(true)
                .apply {
                    environment().putAll(proot.hostEnvironment())
                    workDir?.let { directory(it) }
                }
                .start()
            val output = process.inputStream.bufferedReader().readText()
            CommandResult(process.waitFor(), output.trimEnd())
        }
}
