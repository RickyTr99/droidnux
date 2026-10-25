package app.droidnux.ui

import android.app.ActivityManager
import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.droidnux.R
import app.droidnux.container.AppStore
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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

/** The first-run installation: rootfs, desktop packages, the components, then the chosen [apps] (store ids). */
data class SetupState(
    val step: SetupStep = SetupStep.ROOTFS,
    val done: Boolean = false,
    val error: String? = null,
    val apps: List<String> = emptyList(),
)

enum class SetupStep { ROOTFS, DESKTOP, COMPONENTS, APPS }

/** An apt job of the store, waiting for the one before it, running, or failed. */
sealed interface StoreJob {
    data object Queued : StoreJob
    /** [done] of [total] steps (downloads and setups) once apt knows them, 0 of 0 before. */
    data class Running(val removing: Boolean, val done: Int = 0, val total: Int = 0) : StoreJob
    data class Failed(val message: String) : StoreJob
}

/** Packages found by the store's search, for [query]; null results while searching. */
data class StoreSearch(val query: String, val results: List<AppStore.Found>? = null)

data class AppState(
    val distro: Distro = Distro.DEBIAN_TRIXIE,
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
    /** True while the desktop process (display server and session) is alive. */
    val sessionRunning: Boolean = false,
    /** The Home's quick launch: desktop ids, in the user's order. */
    val homeApps: List<String> = emptyList(),
    /** Packages installed in the rootfs, as dpkg says. */
    val packages: Set<String> = emptySet(),
    /** Packages installed from the store's search, listed with the installed apps. */
    val storePackages: List<String> = emptyList(),
    /** The store's apt jobs, by store id (or package name, for search results). */
    val storeJobs: Map<String, StoreJob> = emptyMap(),
    val storeSearch: StoreSearch? = null,
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

    /** The last list of apps, shown at once on the next start while the container lists them again. */
    private val appsCache = File(application.filesDir, "apps.tsv")
    private val icons = java.util.concurrent.ConcurrentHashMap<String, Bitmap>()

    /** The Home's quick launch and the store's search installs: one name per line. */
    private val homeFile = File(application.filesDir, "home-apps.txt")
    private val storeFile = File(application.filesDir, "store-packages.txt")

    /** One apt job at a time, in the order they came (Mutex is fair). */
    private val apt = Mutex()
    private val storeJobs = mutableMapOf<String, Job>()
    private val storeProcesses = java.util.concurrent.ConcurrentHashMap<String, Process>()
    private var searchJob: Job? = null

    private val rootfs get() = container.installer.rootfsDir(_state.value.distro)

    init {
        viewModelScope.launch {
            val cached = withContext(Dispatchers.IO) {
                runCatching { DesktopEnvironment.parseApps(appsCache.readText()) }.getOrNull()
            }
            if (!cached.isNullOrEmpty()) _state.update { it.copy(apps = it.apps ?: cached.sortedBy { app -> app.name.lowercase() }) }
        }
        viewModelScope.launch {
            val (home, store) = withContext(Dispatchers.IO) {
                (if (homeFile.isFile) readLines(homeFile) else HOME_DEFAULT) to readLines(storeFile)
            }
            _state.update { it.copy(homeApps = home, storePackages = store) }
            refreshPackages()
        }
        viewModelScope.launch {
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
        if (_state.value.ready && SystemClock.elapsedRealtime() - appsLoadedAt > APPS_REFRESH_MS) {
            loadApps()
            viewModelScope.launch { refreshPackages() } // apt in the desktop's terminal
        }
    }

    /** Sets the Home's quick launch (desktop ids, in order). */
    fun setHomeApps(ids: List<String>) {
        val list = ids.distinct()
        _state.update { it.copy(homeApps = list) }
        viewModelScope.launch(Dispatchers.IO) { writeLines(homeFile, list) }
    }

    private suspend fun refreshPackages() {
        val packages = withContext(Dispatchers.IO) { runCatching { AppStore.installedPackages(rootfs) }.getOrDefault(emptySet()) }
        _state.update { it.copy(packages = packages) }
    }

    /** The launcher that opens a store app's [packages] once installed ([launcher] if it names one). */
    fun storeLauncher(packages: List<String>, launcher: String? = null): String? =
        launcher ?: packages.firstNotNullOfOrNull { AppStore.desktopId(rootfs, it) }

    /** Installs [packages] for the store entry [key], after the apt jobs before it. */
    fun storeInstall(key: String, packages: List<String>) = startStoreJob(key, packages, remove = false)

    /** Removes [packages] (and what only they needed) for the store entry [key]. */
    fun storeRemove(key: String, packages: List<String>) = startStoreJob(key, packages, remove = true)

    /** Stops a waiting or running job; a stopped apt is finished by the next job's dpkg --configure. */
    fun storeCancel(key: String) {
        storeJobs[key]?.cancel()
        storeProcesses[key]?.destroy()
    }

    private fun startStoreJob(key: String, packages: List<String>, remove: Boolean) {
        if (storeJobs[key]?.isActive == true) return
        storeJobs[key] = viewModelScope.launch { runStoreJob(key, packages, remove) }
    }

    /** Runs one apt job; true when apt succeeded (a failure stays in the job's state). */
    private suspend fun runStoreJob(key: String, packages: List<String>, remove: Boolean): Boolean {
        fun set(job: StoreJob?) = _state.update { if (job == null) it.copy(storeJobs = it.storeJobs - key) else it.copy(storeJobs = it.storeJobs + (key to job)) }
        set(StoreJob.Queued)
        try {
            val error = apt.withLock {
                set(StoreJob.Running(remove))
                withContext(Dispatchers.IO) { runApt(key, packages, remove) { done, total -> set(StoreJob.Running(remove, done, total)) } }
            }
            refreshPackages()
            if (error != null) {
                set(StoreJob.Failed(error))
                return false
            }
            set(null)
            // Search installs are listed with the installed apps; catalog ones are known already.
            if (StoreCatalog.get(key) == null) {
                val list = if (remove) _state.value.storePackages - key else (_state.value.storePackages + key).distinct()
                _state.update { it.copy(storePackages = list) }
                withContext(Dispatchers.IO) { writeLines(storeFile, list) }
            }
            appsJob?.join()
            loadApps()
            return true
        } catch (e: CancellationException) {
            set(null)
            withContext(kotlinx.coroutines.NonCancellable) { refreshPackages() }
            throw e
        }
    }

    /** Runs apt in the container; null when it succeeded, else the reason. */
    private fun runApt(key: String, packages: List<String>, remove: Boolean, progress: (Int, Int) -> Unit): String? {
        val script = if (remove) {
            AppStore.removeScript(packages, DesktopEnvironment.desktopPackages)
        } else {
            AppStore.installScript(packages, Locale.getDefault().language)
        }
        val process = container.spawnShell(_state.value.distro, script)
        storeProcesses[key] = process
        var ok = false
        var kept: String? = null
        var last = ""
        var total = 0
        var done = 0
        try {
            process.inputStream.bufferedReader().forEachLine { line ->
                when {
                    line == AppStore.DONE_MARKER -> ok = true
                    line.startsWith(AppStore.KEEP_MARKER) -> kept = line.substringAfterLast(' ')
                    // Each new package is downloaded, then set up: two steps.
                    line.startsWith("Get:") || line.startsWith("Setting up ") || line.startsWith("Removing ") -> done++
                }
                APT_SUMMARY.find(line)?.let { total += 2 * (it.groupValues[1].toInt() + it.groupValues[2].toInt()) }
                APT_REMOVE.find(line)?.let { if (remove) total = it.groupValues[1].toInt() }
                if (line.isNotBlank()) last = line
                progress(done.coerceAtMost(total), total)
            }
            process.waitFor()
        } catch (e: java.io.IOException) {
            last = e.toString() // destroyed by storeCancel
        } finally {
            storeProcesses.remove(key)
            process.destroy()
        }
        return when {
            ok -> null
            kept != null -> getApplication<Application>().getString(R.string.store_keep, kept)
            else -> last
        }
    }

    /** Searches Debian for [query] (after a short pause, so typing does not start a search per key). */
    fun storeSearch(query: String) {
        searchJob?.cancel()
        val command = AppStore.searchCommand(query)
        if (command == null) {
            _state.update { it.copy(storeSearch = if (query.isBlank()) null else StoreSearch(query, emptyList())) }
            return
        }
        _state.update { it.copy(storeSearch = StoreSearch(query, it.storeSearch?.results)) }
        searchJob = viewModelScope.launch {
            delay(SEARCH_DELAY_MS)
            val results = runCatching { AppStore.parseSearch(container.shell(_state.value.distro, command).output) }.getOrDefault(emptyList())
            _state.update { it.copy(storeSearch = StoreSearch(query, results)) }
        }
    }

    private fun readLines(file: File): List<String> =
        runCatching { file.readLines().map { it.trim() }.filter { it.isNotEmpty() } }.getOrDefault(emptyList())

    private fun writeLines(file: File, lines: List<String>) {
        val temp = File(file.parentFile, "${file.name}.new")
        temp.writeText(lines.joinToString("\n", postfix = "\n"))
        temp.renameTo(file)
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
                DesktopEnvironment.parseApps(result.output).also { apps ->
                    if (apps.isNotEmpty()) withContext(Dispatchers.IO) { appsCache.writeText(result.output) }
                }
            }.getOrNull() ?: return@launch
            appsLoadedAt = SystemClock.elapsedRealtime()
            icons.clear() // the script redraws the icons of updated apps
            _state.update { it.copy(apps = apps.sortedBy { app -> app.name.lowercase() }) }
        }
    }

    /** An app's icon if already decoded, for the first frame of its tile. */
    fun cachedIcon(id: String): Bitmap? = icons[id]

    /** Decodes an app's icon (off the main thread) and keeps it for the next time. */
    fun icon(id: String): Bitmap? = icons[id] ?: DesktopEnvironment.iconFile(rootfs, id)
        ?.let { BitmapFactory.decodeFile(it.path) }?.also { icons[id] = it }

    /** First run: installs whatever is missing, in order, and stops at the first failure. */
    fun runSetup(apps: List<String> = _state.value.setup?.apps.orEmpty()) {
        if (setupJob?.isActive == true) return
        setupJob = viewModelScope.launch {
            _state.update { it.copy(setup = SetupState(apps = apps)) }
            try {
                if (!withContext(Dispatchers.IO) { container.installer.isInstalled(_state.value.distro) }) {
                    installRootfs()
                }
                _state.update { it.copy(setup = SetupState(SetupStep.DESKTOP, apps = apps)) }
                if (!_state.value.desktopInstalled || !_state.value.desktopUpToDate) {
                    if (!installDesktopPackages()) throw IllegalStateException(_state.value.desktopLog.lines().lastOrNull().orEmpty())
                }
                _state.update { it.copy(setup = SetupState(SetupStep.COMPONENTS, apps = apps)) }
                for (component in Component.ALL) {
                    if (component.sha256.isEmpty() || _state.value.components[component.id] == InstallState.Installed) continue
                    installComponentNow(component)
                }
                _state.update { it.copy(setup = SetupState(SetupStep.APPS, apps = apps)) }
                refreshPackages()
                // The desktop works without them: a failed app stays failed in the store, the setup goes on.
                for (app in apps.mapNotNull(StoreCatalog::get)) {
                    if (app.packages.first() in _state.value.packages) continue
                    if (runStoreJob(app.id, app.packages, remove = false)) {
                        withContext(Dispatchers.IO) { storeLauncher(app.packages, app.launcher) }
                            ?.let { setHomeApps(_state.value.homeApps + it) }
                    }
                }
                _state.update { it.copy(setup = SetupState(SetupStep.APPS, done = true, apps = apps)) }
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

    /** Installs the desktop in the container with apt, streaming the log. */
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
        val APT_REMOVE = Regex("""(\d+) to remove""")
        const val SEARCH_DELAY_MS = 350L

        /** The quick launch until the user changes it (apps that are not installed are not shown). */
        val HOME_DEFAULT = listOf("firefox-esr", "org.kde.dolphin", "org.kde.konsole", "systemsettings")
    }
}
