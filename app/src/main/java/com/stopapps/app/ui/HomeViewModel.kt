package com.stopapps.app.ui

import android.app.Application
import android.content.Context
import android.os.PowerManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.stopapps.app.accessibility.ForceStopEngineHolder
import com.stopapps.app.accessibility.StopAccessService
import com.stopapps.app.data.AppEntry
import com.stopapps.app.data.AppRepository
import com.stopapps.app.data.FileLogger
import com.stopapps.app.data.PrefsStore
import com.stopapps.app.data.RamInfo
import com.stopapps.app.service.RunLog
import com.stopapps.app.service.StopRunnerService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class HomeViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = AppRepository(app.applicationContext)
    private val prefs = PrefsStore(app.applicationContext)

    private val _apps = MutableStateFlow<List<AppEntry>>(emptyList())
    val apps: StateFlow<List<AppEntry>> = _apps.asStateFlow()

    private val _ram = MutableStateFlow<RamInfo?>(null)
    val ram: StateFlow<RamInfo?> = _ram.asStateFlow()

    private val _loading = MutableStateFlow(true)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    private val _selection = MutableStateFlow<Set<String>>(emptySet())
    val selection: StateFlow<Set<String>> = _selection.asStateFlow()

    private val _whitelist = MutableStateFlow<Set<String>>(emptySet())
    val whitelist: StateFlow<Set<String>> = _whitelist.asStateFlow()

    private val _turbo = MutableStateFlow(false)
    val turbo: StateFlow<Boolean> = _turbo.asStateFlow()

    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running.asStateFlow()

    private val _logVersion = MutableStateFlow(0L)
    val logVersion: StateFlow<Long> = _logVersion.asStateFlow()

    private val _hasUsageAccess = MutableStateFlow(false)
    val hasUsageAccess: StateFlow<Boolean> = _hasUsageAccess.asStateFlow()

    private val _a11yEnabled = MutableStateFlow(false)
    val a11yEnabled: StateFlow<Boolean> = _a11yEnabled.asStateFlow()

    /**
     * True when the app is exempt from battery optimizations. Defaults to
     * true so the setup card does not flash before the first check
     * completes.
     */
    private val _batteryUnrestricted = MutableStateFlow(true)
    val batteryUnrestricted: StateFlow<Boolean> = _batteryUnrestricted.asStateFlow()

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    /** Last finished-run summary, shown as the success card. */
    private val _lastSummary = MutableStateFlow<RunSummary?>(null)
    val lastSummary: StateFlow<RunSummary?> = _lastSummary.asStateFlow()

    /** One-shot toast message for log export results. */
    private val _toastMsg = MutableStateFlow<String?>(null)
    val toastMsg: StateFlow<String?> = _toastMsg.asStateFlow()

    /** Number of lines currently in the persistent log.json. */
    private val _logLines = MutableStateFlow(0L)
    val logLines: StateFlow<Long> = _logLines.asStateFlow()

    private var monitorJob: Job? = null

    init {
        viewModelScope.launch {
            prefs.whitelist.collect { _whitelist.value = it }
        }
        viewModelScope.launch {
            prefs.turbo.collect { _turbo.value = it }
        }
        refreshAll()
        startMonitors()
    }

    fun refreshAll() {
        viewModelScope.launch { refreshAccessStates() }
        viewModelScope.launch { loadApps() }
        viewModelScope.launch { refreshRam() }
    }

    private suspend fun refreshAccessStates() {
        _hasUsageAccess.value = withContext(Dispatchers.IO) { repo.hasUsageAccess() }
        _a11yEnabled.value = withContext(Dispatchers.IO) {
            StopAccessService.isEnabled(getApplication())
        }
        _batteryUnrestricted.value = withContext(Dispatchers.IO) { isBatteryUnrestricted() }
    }

    /**
     * True when the app is exempt from battery optimizations. Vivo battery
     * management kills battery-optimized background apps, which tears down
     * the accessibility service mid-run — so the setup card nags when this
     * is false. Returns true when the state cannot be determined, so we do
     * not nag on devices where the query is unavailable.
     */
    private fun isBatteryUnrestricted(): Boolean {
        return try {
            val app = getApplication<Application>()
            val pm = app.getSystemService(Context.POWER_SERVICE) as PowerManager
            pm.isIgnoringBatteryOptimizations(app.packageName)
        } catch (_: Exception) {
            true
        }
    }

    private suspend fun loadApps() {
        _loading.value = true
        try {
            val wl = prefs.whitelist.first()
            // Running status mirrors the reference (AppSleep 2.4, v2.c.c):
            // enabled installed apps with FLAG_STOPPED clear and a launch
            // intent — no usage-recency window.
            val list = withContext(Dispatchers.IO) { repo.loadApps(wl) }
            _apps.value = list
            // Drop selection entries that no longer exist.
            val pkgs = list.map { it.packageName }.toSet()
            _selection.value = _selection.value.intersect(pkgs)
        } finally {
            _loading.value = false
        }
    }

    private suspend fun refreshRam() {
        _ram.value = withContext(Dispatchers.IO) { repo.ramInfo() }
    }

    private fun startMonitors() {
        monitorJob?.cancel()
        monitorJob = viewModelScope.launch {
            while (isActive) {
                _running.value = ForceStopEngineHolder.engine?.running == true
                val v = RunLog.version
                if (v != _logVersion.value) {
                    _logVersion.value = v
                    // Pick up the finished-run marker as a success summary.
                    val summary = RunLog.snapshot().lastOrNull { it.startsWith("[dbg] run finished:") }
                        ?.removePrefix("[dbg] ")
                        ?.let { parseRunSummary(it) }
                    if (summary != null && summary != _lastSummary.value) {
                        _lastSummary.value = summary
                        // A run just finished: reload the app list so the
                        // running counts and rows refresh without a manual
                        // pull. Separate launch so the monitor keeps ticking.
                        viewModelScope.launch { loadApps() }
                    }
                }
                _logLines.value = withContext(Dispatchers.IO) { FileLogger.lineCount() }
                // Refresh access states while visible. isEnabled() and
                // isIgnoringBatteryOptimizations() are Settings.Secure /
                // PowerManager binder IPC to system_server: they must NOT
                // run on the main thread.
                _a11yEnabled.value = withContext(Dispatchers.IO) {
                    StopAccessService.isEnabled(getApplication())
                }
                _batteryUnrestricted.value = withContext(Dispatchers.IO) { isBatteryUnrestricted() }
                delay(1500)
            }
        }
    }

    // ---------- selection ----------

    fun setQuery(q: String) {
        _query.value = q
    }

    fun toggleSelect(pkg: String) {
        _selection.value = _selection.value.toMutableSet().also { s ->
            if (!s.add(pkg)) s.remove(pkg)
        }
    }

    fun selectAllRunning(visible: List<AppEntry>) {
        _selection.value = _selection.value + visible.filter { it.isRunning }.map { it.packageName }
    }

    fun selectAllVisible(visible: List<AppEntry>) {
        _selection.value = _selection.value + visible.map { it.packageName }
    }

    fun clearSelection() {
        _selection.value = emptySet()
    }

    // ---------- whitelist / turbo ----------

    fun toggleWhitelist(pkg: String) {
        viewModelScope.launch {
            if (prefs.isWhitelisted(pkg)) prefs.removeFromWhitelist(pkg)
            else prefs.addToWhitelist(pkg)
            loadApps()
        }
    }

    fun unwhitelist(pkg: String) {
        viewModelScope.launch {
            prefs.removeFromWhitelist(pkg)
            loadApps()
        }
    }

    fun setTurbo(enabled: Boolean) {
        viewModelScope.launch { prefs.setTurbo(enabled) }
    }

    // ---------- run ----------

    fun stopSelected() {
        val pkgs = _selection.value.toList()
        if (pkgs.isEmpty()) return
        RunLog.clear()
        StopRunnerService.start(getApplication(), pkgs, _turbo.value)
    }

    fun cancelRun() {
        StopRunnerService.cancel(getApplication())
        ForceStopEngineHolder.engine?.cancel()
    }

    fun openUsageSettings() = repo.openUsageAccessSettings()

    fun logLines(): List<String> = RunLog.snapshot()

    fun clearSummary() {
        _lastSummary.value = null
    }

    fun consumeToast() {
        _toastMsg.value = null
    }

    /** Copies the persistent log.json into the public Downloads folder. */
    fun downloadLog() {
        viewModelScope.launch(Dispatchers.IO) {
            val name = FileLogger.copyToDownloads(getApplication())
            _toastMsg.value = if (name != null)
                "Log saved to Downloads/$name"
            else
                "Could not save the log — nothing recorded yet?"
        }
    }

    /** Returns a share intent for log.json (FileProvider), or null. */
    fun shareLogIntent(): android.content.Intent? {
        return FileLogger.shareIntent(getApplication())
    }

    override fun onCleared() {
        monitorJob?.cancel()
        super.onCleared()
    }
}
