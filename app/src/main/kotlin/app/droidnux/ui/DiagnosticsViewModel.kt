package app.droidnux.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.droidnux.container.CommandResult
import app.droidnux.container.Container
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

sealed interface RootfsState {
    data object Checking : RootfsState
    data object NotInstalled : RootfsState
    data class Installing(val progress: InstallProgress?) : RootfsState
    data object Installed : RootfsState
    data class Failed(val message: String) : RootfsState
}

data class DiagnosticsState(
    val distro: Distro = Distro.DEBIAN_TRIXIE,
    val proot: CommandResult? = null,
    val rootfs: RootfsState = RootfsState.Checking,
    val running: Boolean = false,
    val guest: CommandResult? = null,
    val display: String? = null,
)

/** Phase 0 checks: proot runs, the rootfs installs, a command runs inside it. */
class DiagnosticsViewModel(application: Application) : AndroidViewModel(application) {

    private val container = Container(application)
    private val _state = MutableStateFlow(DiagnosticsState())
    val state: StateFlow<DiagnosticsState> = _state.asStateFlow()

    private var installJob: Job? = null

    init {
        viewModelScope.launch {
            val proot = runCatching { container.prootVersion() }
                .getOrElse { CommandResult(-1, it.message ?: it.toString()) }
            val installed = withContext(Dispatchers.IO) { container.installer.isInstalled(_state.value.distro) }
            val display = withContext(Dispatchers.Default) {
                runCatching { DisplayCore.probe() }.getOrElse { it.toString() }
            }
            _state.update {
                it.copy(
                    proot = proot,
                    rootfs = if (installed) RootfsState.Installed else RootfsState.NotInstalled,
                    display = display,
                )
            }
        }
    }

    fun install() {
        if (installJob?.isActive == true) return
        val distro = _state.value.distro
        installJob = viewModelScope.launch {
            _state.update { it.copy(rootfs = RootfsState.Installing(null), guest = null) }
            try {
                container.installer.install(distro) { progress ->
                    _state.update { it.copy(rootfs = RootfsState.Installing(progress)) }
                }
                _state.update { it.copy(rootfs = RootfsState.Installed) }
            } catch (e: CancellationException) {
                _state.update { it.copy(rootfs = RootfsState.NotInstalled) }
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(rootfs = RootfsState.Failed(e.message ?: e.toString())) }
            }
        }
    }

    fun cancelInstall() {
        installJob?.cancel()
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
