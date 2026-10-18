package app.droidnux.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.droidnux.container.CommandResult
import app.droidnux.container.Component
import app.droidnux.container.Container
import app.droidnux.container.DesktopEnvironment
import app.droidnux.container.Distro
import app.droidnux.container.InstallProgress
import app.droidnux.display.DisplayCore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface InstallState {
    data object Checking : InstallState
    data object NotInstalled : InstallState
    data class Installing(val progress: InstallProgress?) : InstallState
    data object Installed : InstallState
    data class Failed(val message: String) : InstallState
}

data class DiagnosticsState(
    val distro: Distro = Distro.DEBIAN_TRIXIE,
    val proot: CommandResult? = null,
    val rootfs: InstallState = InstallState.Checking,
    val running: Boolean = false,
    val guest: CommandResult? = null,
    val display: String? = null,
    val desktopInstalled: Boolean = false,
    /** False when this app version needs packages the last desktop install did not have. */
    val desktopUpToDate: Boolean = true,
    val desktopInstalling: Boolean = false,
    /** Last lines of the desktop install log. */
    val desktopLog: String = "",
    val mesa: InstallState = InstallState.Checking,
    /** Version of the installed Mesa component, if any (may be older than [Component.MESA]). */
    val mesaVersion: String? = null,
)

/** Phase 0 checks: proot runs, the rootfs installs, a command runs inside it. */
class DiagnosticsViewModel(application: Application) : AndroidViewModel(application) {

    private val container = Container(application)
    private val _state = MutableStateFlow(DiagnosticsState())
    val state: StateFlow<DiagnosticsState> = _state.asStateFlow()

    private var installJob: Job? = null
    private var mesaJob: Job? = null

    init {
        viewModelScope.launch {
            val proot = runCatching { container.prootVersion() }
                .getOrElse { CommandResult(-1, it.message ?: it.toString()) }
            val installed = withContext(Dispatchers.IO) { container.installer.isInstalled(_state.value.distro) }
            val display = withContext(Dispatchers.Default) {
                runCatching { DisplayCore.probe() }.getOrElse { it.toString() }
            }
            val rootfs = container.installer.rootfsDir(_state.value.distro)
            val (desktop, upToDate) = withContext(Dispatchers.IO) {
                DesktopEnvironment.isInstalled(rootfs) to DesktopEnvironment.isUpToDate(rootfs)
            }
            refreshMesa()
            _state.update {
                it.copy(
                    proot = proot,
                    rootfs = if (installed) InstallState.Installed else InstallState.NotInstalled,
                    display = display,
                    desktopInstalled = desktop,
                    desktopUpToDate = upToDate,
                )
            }
        }
    }

    /** Installs XFCE in the container with apt, streaming the log. */
    fun installDesktop() {
        if (_state.value.desktopInstalling) return
        val distro = _state.value.distro
        viewModelScope.launch {
            _state.update { it.copy(desktopInstalling = true, desktopLog = "") }
            val ok = withContext(Dispatchers.IO) {
                val lines = ArrayDeque<String>()
                var success = false
                runCatching {
                    val process = container.spawnShell(distro, DesktopEnvironment.installScript())
                    process.inputStream.bufferedReader().forEachLine { line ->
                        if (line == "DROIDNUX_INSTALL_OK") success = true
                        lines.addLast(line)
                        if (lines.size > LOG_LINES) lines.removeFirst()
                        _state.update { it.copy(desktopLog = lines.joinToString("\n")) }
                    }
                    process.waitFor()
                }.onFailure { e -> _state.update { it.copy(desktopLog = e.toString()) } }
                val rootfs = container.installer.rootfsDir(distro)
                (success && DesktopEnvironment.isInstalled(rootfs)) to DesktopEnvironment.isUpToDate(rootfs)
            }
            val (installed, upToDate) = ok
            _state.update {
                it.copy(desktopInstalling = false, desktopInstalled = installed, desktopUpToDate = upToDate)
            }
        }
    }

    fun install() {
        if (installJob?.isActive == true) return
        val distro = _state.value.distro
        installJob = viewModelScope.launch {
            _state.update { it.copy(rootfs = InstallState.Installing(null), guest = null) }
            try {
                container.installer.install(distro) { progress ->
                    _state.update { it.copy(rootfs = InstallState.Installing(progress)) }
                }
                _state.update { it.copy(rootfs = InstallState.Installed) }
                refreshMesa() // a fresh rootfs has no components
            } catch (e: CancellationException) {
                _state.update { it.copy(rootfs = InstallState.NotInstalled) }
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(rootfs = InstallState.Failed(e.message ?: e.toString())) }
            }
        }
    }

    fun cancelInstall() {
        installJob?.cancel()
    }

    private suspend fun refreshMesa() {
        val rootfs = container.installer.rootfsDir(_state.value.distro)
        val (installed, version) = withContext(Dispatchers.IO) {
            container.components.isInstalled(rootfs, Component.MESA) to
                container.components.installedVersion(rootfs, Component.MESA)
        }
        _state.update {
            it.copy(mesa = if (installed) InstallState.Installed else InstallState.NotInstalled, mesaVersion = version)
        }
    }

    /** Downloads and unpacks the Mesa component (Turnip + Zink) into the rootfs. */
    fun installMesa() {
        if (mesaJob?.isActive == true) return
        val rootfs = container.installer.rootfsDir(_state.value.distro)
        mesaJob = viewModelScope.launch {
            _state.update { it.copy(mesa = InstallState.Installing(null)) }
            try {
                container.components.install(rootfs, Component.MESA) { progress ->
                    _state.update { it.copy(mesa = InstallState.Installing(progress)) }
                }
                refreshMesa()
            } catch (e: CancellationException) {
                refreshMesa()
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(mesa = InstallState.Failed(e.message ?: e.toString())) }
            }
        }
    }

    fun cancelMesa() {
        mesaJob?.cancel()
    }

    fun runUname() {
        if (_state.value.running) return
        viewModelScope.launch {
            _state.update { it.copy(running = true) }
            val result = runCatching {
                container.shell(_state.value.distro, "uname -a; echo; head -n 2 /etc/os-release; id")
            }.getOrElse { CommandResult(-1, it.message ?: it.toString()) }
            _state.update { it.copy(running = false, guest = result) }
        }
    }
}

private const val LOG_LINES = 8
