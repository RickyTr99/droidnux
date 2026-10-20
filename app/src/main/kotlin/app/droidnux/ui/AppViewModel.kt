package app.droidnux.ui

import android.app.ActivityManager
import android.app.Application
import android.os.SystemClock
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
import java.io.File
import java.util.Locale

sealed interface InstallState {
    data object Checking : InstallState
    data object NotInstalled : InstallState
    data class Installing(val progress: InstallProgress?) : InstallState
    data object Installed : InstallState
    data class Failed(val message: String) : InstallState
}

/** The first-run installation: rootfs, desktop packages, then the components. */
data class SetupState(
    val step: SetupStep = SetupStep.ROOTFS,
    val done: Boolean = false,
    val error: String? = null,
)

enum class SetupStep { ROOTFS, DESKTOP, COMPONENTS }

data class AppState(
    val distro: Distro = Distro.DEBIAN_TRIXIE,
    val proot: CommandResult? = null,
    val rootfs: InstallState = InstallState.Checking,
    val running: Boolean = false,
    val guest: CommandResult? = null,
    val display: String? = null,
    /** GPU name for the user ("Adreno 740"), when the display core found one. */
    val gpu: String? = null,
    val desktopInstalled: Boolean = false,
    /** False when this app version needs packages the last desktop install did not have. */
    val desktopUpToDate: Boolean = true,
    val desktopInstalling: Boolean = false,
    /** Packages set up so far and in all, while apt installs the desktop (0 of 0 until apt knows). */
    val desktopPackages: Pair<Int, Int> = 0 to 0,
    /** Last lines of the desktop install log. */
    val desktopLog: String = "",
    /** Install state of each [Component], by id (missing = still checking). */
    val components: Map<String, InstallState> = emptyMap(),
    /** Version of each installed component, by id (may be older than the app's). */
    val componentVersions: Map<String, String> = emptyMap(),
    /** The first-run installation, while it runs or failed. */
    val setup: SetupState? = null,
    /** The desktop's apps, once listed. */
    val apps: List<DesktopEnvironment.LinuxApp>? = null,
    /** True while the desktop process (X server and session) is alive. */
    val sessionRunning: Boolean = false,
) {
    /** Everything the desktop needs is there: the home screen instead of the first-run wizard. */
    val ready: Boolean get() = rootfs == InstallState.Installed && desktopInstalled && setup == null
}

/** State and actions of the app's screens: installation, components, the desktop's apps. */
class AppViewModel(application: Application) : AndroidViewModel(application) {

    private val container = Container(application)
    private val _state = MutableStateFlow(AppState())
    val state: StateFlow<AppState> = _state.asStateFlow()

    private var installJob: Job? = null
    private var setupJob: Job? = null
    private val componentJobs = mutableMapOf<String, Job>()
    private var appsJob: Job? = null
    private var appsLoadedAt = 0L

    private val rootfs get() = container.installer.rootfsDir(_state.value.distro)

    init {
        viewModelScope.launch {
            val proot = runCatching { container.prootVersion() }
                .getOrElse { CommandResult(-1, it.message ?: it.toString()) }
            val installed = withContext(Dispatchers.IO) { container.installer.isInstalled(_state.value.distro) }
            val display = withContext(Dispatchers.Default) {
                runCatching { DisplayCore.probe() }.getOrElse { it.toString() }
            }
            val (desktop, upToDate) = withContext(Dispatchers.IO) {
                DesktopEnvironment.isInstalled(rootfs) to DesktopEnvironment.isUpToDate(rootfs)
            }
            Component.ALL.forEach { refreshComponent(it) }
            _state.update {
                it.copy(
                    proot = proot,
                    rootfs = if (installed) InstallState.Installed else InstallState.NotInstalled,
                    display = display,
                    gpu = GPU_NAME.find(display)?.let { match -> "Adreno ${match.groupValues[1]}" },
                    desktopInstalled = desktop,
                    desktopUpToDate = upToDate,
                )
            }
        }
    }

    /** Called when a screen comes back: the session may have started or ended, apps may have changed. */
    fun refresh() {
        val processes = getApplication<Application>().getSystemService(ActivityManager::class.java).runningAppProcesses
        _state.update { state -> state.copy(sessionRunning = processes.orEmpty().any { it.processName.endsWith(":desktop") }) }
        if (_state.value.ready && SystemClock.elapsedRealtime() - appsLoadedAt > APPS_REFRESH_MS) loadApps()
    }

    /** Lists the desktop's apps (and renders their icons) in the container. */
    fun loadApps() {
        if (appsJob?.isActive == true) return
        appsJob = viewModelScope.launch {
            val apps = runCatching {
                withContext(Dispatchers.IO) {
                    DesktopEnvironment.prepare(rootfs, File(getApplication<Application>().applicationInfo.nativeLibraryDir))
                }
                val result = container.shell(_state.value.distro, DesktopEnvironment.appsCommand(Locale.getDefault().language))
                DesktopEnvironment.parseApps(result.output)
            }.getOrNull() ?: return@launch
            appsLoadedAt = SystemClock.elapsedRealtime()
            _state.update { it.copy(apps = apps.sortedBy { app -> app.name.lowercase() }) }
        }
    }

    fun iconFile(id: String): File? = DesktopEnvironment.iconFile(rootfs, id)

    /** First run: installs whatever is missing, in order, and stops at the first failure. */
    fun runSetup() {
        if (setupJob?.isActive == true) return
        setupJob = viewModelScope.launch {
            _state.update { it.copy(setup = SetupState()) }
            try {
                if (!withContext(Dispatchers.IO) { container.installer.isInstalled(_state.value.distro) }) {
                    installRootfs()
                }
                _state.update { it.copy(setup = SetupState(SetupStep.DESKTOP)) }
                if (!_state.value.desktopInstalled || !_state.value.desktopUpToDate) {
                    if (!installDesktopPackages()) throw IllegalStateException(_state.value.desktopLog.lines().lastOrNull().orEmpty())
                }
                _state.update { it.copy(setup = SetupState(SetupStep.COMPONENTS)) }
                for (component in Component.ALL) {
                    if (component.sha256.isEmpty() || _state.value.components[component.id] == InstallState.Installed) continue
                    installComponentNow(component)
                }
                _state.update { it.copy(setup = SetupState(SetupStep.COMPONENTS, done = true)) }
            } catch (e: CancellationException) {
                _state.update { it.copy(setup = null) }
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(setup = it.setup?.copy(error = e.message ?: e.toString())) }
            }
        }
    }

    fun cancelSetup() {
        setupJob?.cancel()
    }

    /** Leaves the finished first run for the home screen. */
    fun finishSetup() {
        _state.update { it.copy(setup = null) }
        loadApps()
    }

    /** Installs XFCE in the container with apt, streaming the log. */
    fun installDesktop() {
        if (_state.value.desktopInstalling) return
        viewModelScope.launch { installDesktopPackages() }
    }

    private suspend fun installDesktopPackages(): Boolean {
        val distro = _state.value.distro
        _state.update { it.copy(desktopInstalling = true, desktopLog = "", desktopPackages = 0 to 0) }
        val ok = withContext(Dispatchers.IO) {
            val lines = ArrayDeque<String>()
            var success = false
            var total = 0
            var done = 0
            runCatching {
                val process = container.spawnShell(distro, DesktopEnvironment.installScript())
                process.inputStream.bufferedReader().forEachLine { line ->
                    if (line == "DROIDNUX_INSTALL_OK") success = true
                    APT_SUMMARY.find(line)?.let { total = it.groupValues[1].toInt() + it.groupValues[2].toInt() }
                    if (line.startsWith("Setting up ")) done++
                    lines.addLast(line)
                    if (lines.size > LOG_LINES) lines.removeFirst()
                    _state.update { it.copy(desktopLog = lines.joinToString("\n"), desktopPackages = done to total) }
                }
                process.waitFor()
            }.onFailure { e -> _state.update { it.copy(desktopLog = e.toString()) } }
            (success && DesktopEnvironment.isInstalled(rootfs)) to DesktopEnvironment.isUpToDate(rootfs)
        }
        val (installed, upToDate) = ok
        _state.update { it.copy(desktopInstalling = false, desktopInstalled = installed, desktopUpToDate = upToDate) }
        return installed && upToDate
    }

    fun install() {
        if (installJob?.isActive == true) return
        installJob = viewModelScope.launch {
            try {
                installRootfs()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // The state shows it.
            }
        }
    }

    private suspend fun installRootfs() {
        val distro = _state.value.distro
        _state.update { it.copy(rootfs = InstallState.Installing(null), guest = null) }
        try {
            container.installer.install(distro) { progress ->
                _state.update { it.copy(rootfs = InstallState.Installing(progress)) }
            }
            _state.update { it.copy(rootfs = InstallState.Installed) }
            val (desktop, upToDate) = withContext(Dispatchers.IO) {
                DesktopEnvironment.isInstalled(rootfs) to DesktopEnvironment.isUpToDate(rootfs)
            }
            _state.update { it.copy(desktopInstalled = desktop, desktopUpToDate = upToDate) }
            Component.ALL.forEach { refreshComponent(it) } // a fresh rootfs has no components
        } catch (e: CancellationException) {
            _state.update { it.copy(rootfs = InstallState.NotInstalled) }
            throw e
        } catch (e: Exception) {
            _state.update { it.copy(rootfs = InstallState.Failed(e.message ?: e.toString())) }
            throw e
        }
    }

    fun cancelInstall() {
        installJob?.cancel()
    }

    private suspend fun refreshComponent(component: Component) {
        val (installed, version) = withContext(Dispatchers.IO) {
            container.components.isInstalled(rootfs, component) to
                container.components.installedVersion(rootfs, component)
        }
        _state.update {
            it.copy(
                components = it.components + (component.id to if (installed) InstallState.Installed else InstallState.NotInstalled),
                componentVersions = if (version != null) it.componentVersions + (component.id to version) else it.componentVersions - component.id,
            )
        }
    }

    private fun setComponent(component: Component, state: InstallState) =
        _state.update { it.copy(components = it.components + (component.id to state)) }

    /** Downloads and unpacks a [Component] into the rootfs. */
    fun installComponent(component: Component) {
        if (componentJobs[component.id]?.isActive == true) return
        componentJobs[component.id] = viewModelScope.launch {
            try {
                installComponentNow(component)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // The state shows it.
            }
        }
    }

    private suspend fun installComponentNow(component: Component) {
        setComponent(component, InstallState.Installing(null))
        try {
            container.components.install(rootfs, component) { progress ->
                setComponent(component, InstallState.Installing(progress))
            }
            refreshComponent(component)
        } catch (e: CancellationException) {
            refreshComponent(component)
            throw e
        } catch (e: Exception) {
            setComponent(component, InstallState.Failed(e.message ?: e.toString()))
            throw e
        }
    }

    fun cancelComponent(component: Component) {
        componentJobs[component.id]?.cancel()
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

    private companion object {
        const val LOG_LINES = 8
        const val APPS_REFRESH_MS = 30_000L
        val GPU_NAME = Regex("""Adreno \(TM\) (\d+)""")
        val APT_SUMMARY = Regex("""(\d+) upgraded, (\d+) newly installed""")
    }
}
