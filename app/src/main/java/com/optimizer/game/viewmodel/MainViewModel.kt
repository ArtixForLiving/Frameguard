package com.optimizer.game.viewmodel

import android.app.ActivityManager
import android.app.Application
import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Build
import android.view.Display
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.optimizer.game.adb.AdbMdnsDiscovery
import com.optimizer.game.adb.AdbPairingClient
import com.optimizer.game.adb.AdbPortScanner
import com.optimizer.game.adb.NativeDaemonManager
import com.optimizer.game.optimizer.GameOptimizerEngine
import com.optimizer.game.privilege.CapabilityProbe
import com.optimizer.game.privilege.DeviceCapabilityProfile
import com.optimizer.game.privilege.PrivilegeManager
import com.optimizer.game.privilege.PrivilegeSource
import com.optimizer.game.privilege.PrivilegeState
import com.optimizer.game.privilege.ProbeStatus
import com.optimizer.game.privilege.ProbeStepResult
import com.optimizer.game.service.BoostSessionService
import com.optimizer.game.service.ClosedLoopStatus
import com.optimizer.game.shizuku.ShizukuManager
import com.optimizer.game.ui.components.GameAppItem
import com.optimizer.game.ui.components.triggerWirelessAdbPairing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import rikka.shizuku.Shizuku
import java.util.Locale

enum class UiStage {
    CONNECTION_GATE,
    CAPABILITY_PROBE,
    MAIN_CONSOLE
}

class MainViewModel(application: Application) : AndroidViewModel(application) {

    val privilegeState: StateFlow<PrivilegeState> = PrivilegeManager.state
    val isBoostActive: StateFlow<Boolean> = BoostSessionService.isBoostActive
    val closedLoopStatus: StateFlow<ClosedLoopStatus> = BoostSessionService.closedLoopStatus
    val isClamped30Fps: StateFlow<Boolean> = BoostSessionService.isClamped30Fps
    val effectiveRefreshRateHz: StateFlow<Float?> = BoostSessionService.effectiveRefreshRateHz
    val activePackage: StateFlow<String?> = BoostSessionService.activePackage
    val currentTemperatureC: StateFlow<Float?> = BoostSessionService.currentTemperatureC
    val pairedRefreshRate: StateFlow<String> = GameOptimizerEngine.pairedRefreshRate

    private val _currentDisplayHz = MutableStateFlow("60Hz")
    val currentDisplayHz: StateFlow<String> = _currentDisplayHz.asStateFlow()

    private val _uiStage = MutableStateFlow(UiStage.CONNECTION_GATE)
    val uiStage: StateFlow<UiStage> = _uiStage.asStateFlow()

    private val _daemonStoppedReason = MutableStateFlow(false)
    val daemonStoppedReason: StateFlow<Boolean> = _daemonStoppedReason.asStateFlow()

    private val _hasPendingWmSnapshot = MutableStateFlow(
        GameOptimizerEngine.loadGeometrySnapshot(application) != null
    )
    val hasPendingWmSnapshot: StateFlow<Boolean> = _hasPendingWmSnapshot.asStateFlow()

    private val _deviceProfile = MutableStateFlow(
        CapabilityProbe.defaultFallbackProfile(application)
    )
    val deviceProfile: StateFlow<DeviceCapabilityProfile> = _deviceProfile.asStateFlow()

    private val _probeSteps = MutableStateFlow(CapabilityProbe.initialPendingSteps())
    val probeSteps: StateFlow<List<ProbeStepResult>> = _probeSteps.asStateFlow()

    private val _probeLogs = MutableStateFlow<List<GameOptimizerEngine.ShellLogEntry>>(emptyList())
    val probeLogs: StateFlow<List<GameOptimizerEngine.ShellLogEntry>> = _probeLogs.asStateFlow()

    private val _probeElapsedMs = MutableStateFlow(0L)
    val probeElapsedMs: StateFlow<Long> = _probeElapsedMs.asStateFlow()

    private val _isProbing = MutableStateFlow(false)
    val isProbing: StateFlow<Boolean> = _isProbing.asStateFlow()

    private val _probeRequiresDecision = MutableStateFlow(false)
    val probeRequiresDecision: StateFlow<Boolean> = _probeRequiresDecision.asStateFlow()

    private val _isPairingInProgress = MutableStateFlow(false)
    val isPairingInProgress: StateFlow<Boolean> = _isPairingInProgress.asStateFlow()

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) {}
        override fun onDisplayRemoved(displayId: Int) {}
        override fun onDisplayChanged(displayId: Int) {
            if (displayId == Display.DEFAULT_DISPLAY) {
                queryCurrentRefreshRate()
            }
        }
    }

    data class SupportedGameDef(
        val id: String,
        val defaultName: String,
        val candidatePackages: List<String>,
        val recommendedFps: Int,
        val maxSupportedFps: Int,
        val recommendationReason: String,
        val supportsRenderScale: Boolean = false,
        val recommendedScale: Int = 100,
        val scaleReason: String = ""
    )

    companion object {
        val SUPPORTED_CATALOG = listOf(
            SupportedGameDef(
                id = "warframe",
                defaultName = "Warframe Mobile",
                candidatePackages = listOf("com.digitalextremes.warframemobile"),
                recommendedFps = 60,
                maxSupportedFps = 60,
                recommendationReason = "Engine native cap 60 FPS · 60Hz lock & 30 FPS clamp verified",
                supportsRenderScale = true,
                recommendedScale = 75,
                scaleReason = "75% cuts GPU load while keeping text legible."
            ),
            SupportedGameDef(
                id = "genshin",
                defaultName = "Genshin Impact",
                candidatePackages = listOf("com.miHoYo.GenshinImpact", "com.HoYoverse.GenshinImpact", "com.miHoYo.Yuanshen"),
                recommendedFps = 60,
                maxSupportedFps = 60,
                recommendationReason = "60Hz display lock active · In-game FPS setting takes priority over 30 FPS clamp",
                supportsRenderScale = true,
                recommendedScale = 75,
                scaleReason = "75% cuts GPU load by 44% for steadier 60 FPS."
            ),
            SupportedGameDef(
                id = "minecraft",
                defaultName = "Minecraft",
                candidatePackages = listOf("com.mojang.minecraftpe"),
                recommendedFps = 60,
                maxSupportedFps = 120,
                recommendationReason = "Supports GameManager 30 FPS clamp & SurfaceFlinger downscale",
                supportsRenderScale = true,
                recommendedScale = 75,
                scaleReason = "75% lowers GPU heat with crisp UI."
            ),
            SupportedGameDef(
                id = "roblox",
                defaultName = "Roblox",
                candidatePackages = listOf("com.roblox.client"),
                recommendedFps = 60,
                maxSupportedFps = 60,
                recommendationReason = "Engine requests 120Hz via setFrameRate · System FPS/Hz lock may be overridden",
                supportsRenderScale = true,
                recommendedScale = 75,
                scaleReason = "SurfaceFlinger buffer scale & WM scale supported."
            ),
            SupportedGameDef(
                id = "pubg",
                defaultName = "PUBG Mobile",
                candidatePackages = listOf("com.tencent.ig", "com.pubg.krmobile", "com.pubg.imobile", "com.vng.pubgmobile", "com.tencent.tmgp.pubgmhd"),
                recommendedFps = 90,
                maxSupportedFps = 120,
                recommendationReason = "Supports up to 120 FPS · 90Hz lock recommended for competitive latency & thermals",
                supportsRenderScale = true,
                recommendedScale = 75,
                scaleReason = "75% lowers GPU heat in sustained matches."
            ),
            SupportedGameDef(
                id = "pubgflash",
                defaultName = "PUBG Mobile Flash",
                candidatePackages = listOf("com.tencent.igfit"),
                recommendedFps = 60,
                maxSupportedFps = 60,
                recommendationReason = "Lightweight client capped at 60 FPS · 60Hz lock provides flat 16.6ms frametime",
                supportsRenderScale = true,
                recommendedScale = 75,
                scaleReason = "SurfaceFlinger buffer scale supported."
            )
        )
    }

    private val defaultPresets = SUPPORTED_CATALOG.map { def ->
        GameAppItem(
            id = def.id,
            name = def.defaultName,
            packageName = def.candidatePackages.first(),
            recommendedFps = def.recommendedFps,
            maxSupportedFps = def.maxSupportedFps,
            recommendationReason = def.recommendationReason,
            isInstalled = false,
            supportsRenderScale = def.supportsRenderScale,
            recommendedScale = def.recommendedScale,
            scaleReason = def.scaleReason
        )
    }

    private val _selectedApp = MutableStateFlow(defaultPresets[0])
    val selectedApp: StateFlow<GameAppItem> = _selectedApp.asStateFlow()

    private val _availableApps = MutableStateFlow(defaultPresets)
    val availableApps: StateFlow<List<GameAppItem>> = _availableApps.asStateFlow()

    private val _isAppDrawerOpen = MutableStateFlow(false)
    val isAppDrawerOpen: StateFlow<Boolean> = _isAppDrawerOpen.asStateFlow()

    private val _targetFps = MutableStateFlow(defaultPresets[0].recommendedFps)
    val targetFps: StateFlow<Int> = _targetFps.asStateFlow()

    private val _downscaleRatio = MutableStateFlow("1.0")
    val downscaleRatio: StateFlow<String> = _downscaleRatio.asStateFlow()

    private val _ramTrimEnabled = MutableStateFlow(true)
    val ramTrimEnabled: StateFlow<Boolean> = _ramTrimEnabled.asStateFlow()

    private val _dndEnabled = MutableStateFlow(true)
    val dndEnabled: StateFlow<Boolean> = _dndEnabled.asStateFlow()

    private val _isAdaptiveThermalEnabled = MutableStateFlow(false)
    val isAdaptiveThermalEnabled: StateFlow<Boolean> = _isAdaptiveThermalEnabled.asStateFlow()

    private val prefs = application.getSharedPreferences("frameguard_game_profiles", Context.MODE_PRIVATE)

    private val _renderScale = MutableStateFlow(100)
    val renderScale: StateFlow<Int> = _renderScale.asStateFlow()

    private val _scaledDisplayMetrics = MutableStateFlow(
        GameOptimizerEngine.computeScaledMetrics(100, 1080, 2400, 420)
    )
    val scaledDisplayMetrics: StateFlow<GameOptimizerEngine.ScaledDisplayMetrics> = _scaledDisplayMetrics.asStateFlow()

    private val _thermalValue = MutableStateFlow("--.-°C")
    val thermalValue: StateFlow<String> = _thermalValue.asStateFlow()

    private val _thermalSubText = MutableStateFlow("NO SENSOR")
    val thermalSubText: StateFlow<String> = _thermalSubText.asStateFlow()

    private val _logs = MutableStateFlow<List<GameOptimizerEngine.ShellLogEntry>>(emptyList())
    val logs: StateFlow<List<GameOptimizerEngine.ShellLogEntry>> = _logs.asStateFlow()

    private var probeJob: Job? = null
    private var hasEverConnected = false

    init {
        viewModelScope.launch {
            GameOptimizerEngine.shellLogs.collect { entry ->
                _logs.value = (_logs.value + entry).takeLast(100)
            }
        }

        logDeviceHardwareInfo()

        PrivilegeManager.init(application)

        // Observe live privilege state transitions and route between the 3 UI stages.
        viewModelScope.launch {
            var lastReportedSource: PrivilegeSource? = null
            privilegeState.collect { state ->
                _hasPendingWmSnapshot.value = GameOptimizerEngine.loadGeometrySnapshot(application) != null

                if (state.isConnected) {
                    val sourceChanged = state.source != lastReportedSource
                    if (sourceChanged) {
                        lastReportedSource = state.source
                        when (state.source) {
                            PrivilegeSource.SHIZUKU -> {
                                val version = try {
                                    Shizuku.getVersion()
                                } catch (_: Exception) {
                                    13
                                }
                                GameOptimizerEngine.log("Shizuku API v$version bound (uid=2000)", GameOptimizerEngine.LogType.STDOUT)
                            }
                            PrivilegeSource.NATIVE_ADB -> {
                                GameOptimizerEngine.log("Native ADB daemon ready (uid=2000)", GameOptimizerEngine.LogType.STDOUT)
                            }
                            else -> Unit
                        }
                    }

                    hasEverConnected = true
                    _daemonStoppedReason.value = false

                    if (sourceChanged || _uiStage.value == UiStage.CONNECTION_GATE) {
                        val cached = CapabilityProbe.loadCachedProfile(application, Build.FINGERPRINT)
                        if (cached != null) {
                            applyDeviceProfile(cached)
                            _uiStage.value = UiStage.MAIN_CONSOLE
                            GameOptimizerEngine.log(
                                "Capability cache hit (${Build.MODEL} · ${cached.maxRefreshRateHz}Hz · ${cached.hiddenModules.size} hidden)",
                                GameOptimizerEngine.LogType.INFO
                            )
                        } else if (!_isProbing.value) {
                            startCapabilityProbe()
                        }
                    }
                } else {
                    lastReportedSource = null
                    if (hasEverConnected) {
                        _daemonStoppedReason.value = true
                    }
                    if (!isBoostActive.value && closedLoopStatus.value != ClosedLoopStatus.DEGRADED) {
                        probeJob?.cancel()
                        _isProbing.value = false
                        _probeRequiresDecision.value = false
                        _uiStage.value = UiStage.CONNECTION_GATE
                    }
                    updateHardwareThermal()
                }
            }
        }

        // Poll ThermalHAL periodically when idle; while boost is active, prefer BoostSessionService.
        viewModelScope.launch(Dispatchers.IO) {
            while (isActive) {
                updateHardwareThermal()
                delay(2500)
            }
        }

        viewModelScope.launch {
            isBoostActive.collect {
                updateHardwareThermal()
            }
        }

        viewModelScope.launch {
            currentTemperatureC.collect {
                updateHardwareThermal()
            }
        }

        viewModelScope.launch {
            closedLoopStatus.collect { status ->
                updateHardwareThermal()
                if (status != ClosedLoopStatus.DEGRADED && !isBoostActive.value && !privilegeState.value.isConnected) {
                    _uiStage.value = UiStage.CONNECTION_GATE
                }
            }
        }

        GameOptimizerEngine.setTargetPackage(defaultPresets[0].packageName)
        loadGameProfile(defaultPresets[0])
        loadInstalledGames()

        val dm = application.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager
        dm?.registerDisplayListener(displayListener, null)
        queryCurrentRefreshRate()
        updateDisplayMetrics()
    }

    private fun startCapabilityProbe() {
        val app = getApplication<Application>()
        probeJob?.cancel()
        _uiStage.value = UiStage.CAPABILITY_PROBE
        _isProbing.value = true
        _probeRequiresDecision.value = false
        _probeElapsedMs.value = 0L
        _probeSteps.value = CapabilityProbe.initialPendingSteps()
        _probeLogs.value = buildList {
            if (_hasPendingWmSnapshot.value) {
                add(
                    GameOptimizerEngine.ShellLogEntry(
                        "Pending WM snapshot detected; restoring baseline after probe",
                        GameOptimizerEngine.LogType.INFO
                    )
                )
            }
        }

        probeJob = viewModelScope.launch(Dispatchers.IO) {
            val startMs = System.currentTimeMillis()
            val timerJob = launch {
                while (isActive) {
                    _probeElapsedMs.value = System.currentTimeMillis() - startMs
                    delay(50)
                }
            }

            try {
                val (profile, finalSteps) = CapabilityProbe.runProbe(
                    context = app,
                    targetPackage = _selectedApp.value.packageName,
                    onStepUpdate = { index, step ->
                        _probeSteps.update { list ->
                            if (index in list.indices) {
                                list.toMutableList().apply { set(index, step) }
                            } else {
                                list
                            }
                        }
                        if (step.status == ProbeStatus.PROBING) {
                            appendProbeLog(step.command, GameOptimizerEngine.LogType.COMMAND)
                        } else if (step.output.isNotBlank()) {
                            val type = if (step.status == ProbeStatus.WARN) {
                                GameOptimizerEngine.LogType.ERROR
                            } else {
                                GameOptimizerEngine.LogType.STDOUT
                            }
                            appendProbeLog(step.output, type)
                        }
                    }
                )

                _probeSteps.value = finalSteps
                applyDeviceProfile(profile)
                _hasPendingWmSnapshot.value = GameOptimizerEngine.loadGeometrySnapshot(app) != null

                if (!profile.supportsWmWrite) {
                    _probeRequiresDecision.value = true
                    appendProbeLog(
                        "OEM security block detected on WM write; awaiting user choice",
                        GameOptimizerEngine.LogType.ERROR
                    )
                } else {
                    _probeRequiresDecision.value = false
                    appendProbeLog(
                        "Probe complete (${profile.hiddenModules.size} modules filtered); entering console",
                        GameOptimizerEngine.LogType.STDOUT
                    )
                    delay(400)
                    if (_uiStage.value == UiStage.CAPABILITY_PROBE && privilegeState.value.isConnected) {
                        _uiStage.value = UiStage.MAIN_CONSOLE
                    }
                }
            } catch (e: Exception) {
                appendProbeLog("Probe error: ${e.message ?: "unknown"}", GameOptimizerEngine.LogType.ERROR)
                _probeRequiresDecision.value = true
            } finally {
                timerJob.cancel()
                _probeElapsedMs.value = (System.currentTimeMillis() - startMs).coerceAtLeast(1L)
                _isProbing.value = false
            }
        }
    }

    private fun appendProbeLog(message: String, type: GameOptimizerEngine.LogType) {
        _probeLogs.update { current ->
            (current + GameOptimizerEngine.ShellLogEntry(message, type)).takeLast(40)
        }
    }

    private fun applyDeviceProfile(profile: DeviceCapabilityProfile) {
        _deviceProfile.value = profile
        GameOptimizerEngine.dndCapabilityAllowed = profile.supportsDnd

        val allowedFps = CapabilityProbe.filterSupportedFpsOptions(profile.maxRefreshRateHz)
        if (_targetFps.value !in allowedFps) {
            val clamped = clampFpsToOptions(_targetFps.value, allowedFps)
            _targetFps.value = clamped
            GameOptimizerEngine.setTargetFps(clamped)
        }
        if (!profile.supportsGameManager && _downscaleRatio.value != "1.0") {
            _downscaleRatio.value = "1.0"
            GameOptimizerEngine.setDownscaleRatio("1.0")
        }
        if (!profile.supportsWmWrite && _renderScale.value != 100) {
            _renderScale.value = 100
            updateDisplayMetrics()
        }
    }

    private fun clampFpsToOptions(desiredFps: Int, options: List<Int>): Int {
        if (desiredFps in options) return desiredFps
        return options.lastOrNull { it <= desiredFps } ?: options.firstOrNull() ?: 60
    }

    fun onReProbe() {
        if (isBoostActive.value || !privilegeState.value.isConnected) return
        CapabilityProbe.clearCache(getApplication())
        startCapabilityProbe()
    }

    fun onContinueFiltered() {
        _probeRequiresDecision.value = false
        _uiStage.value = UiStage.MAIN_CONSOLE
    }

    fun onReVerifyCapabilities() {
        if (isBoostActive.value) {
            viewModelScope.launch {
                GameOptimizerEngine.log(
                    "Disengage active boost session before re-verifying capabilities",
                    GameOptimizerEngine.LogType.INFO
                )
            }
            return
        }
        if (!privilegeState.value.isConnected) {
            _uiStage.value = UiStage.CONNECTION_GATE
            return
        }
        CapabilityProbe.clearCache(getApplication())
        startCapabilityProbe()
    }

    private fun queryCurrentRefreshRate() {
        val dm = getApplication<Application>().getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager
        val display = dm?.getDisplay(Display.DEFAULT_DISPLAY)
        val rate = display?.mode?.refreshRate?.let { Math.round(it) } ?: 60
        _currentDisplayHz.value = "${rate}Hz"
    }

    private fun logDeviceHardwareInfo() {
        viewModelScope.launch(Dispatchers.IO) {
            val context = getApplication<Application>()

            val actManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            val memInfo = ActivityManager.MemoryInfo()
            actManager?.getMemoryInfo(memInfo)
            val ramGb = if (memInfo.totalMem > 0L) {
                Math.round(memInfo.totalMem.toDouble() / (1024.0 * 1024.0 * 1024.0)).toInt()
            } else 8

            val socModel = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                Build.SOC_MODEL.takeIf { it.isNotBlank() && !it.equals("unknown", ignoreCase = true) }
            } else null

            val hardware = Build.HARDWARE.lowercase(Locale.ROOT)
            val board = Build.BOARD.lowercase(Locale.ROOT)

            val socDesc = buildString {
                if (!socModel.isNullOrBlank()) {
                    append(socModel.lowercase(Locale.ROOT))
                    if (!hardware.contains(socModel.lowercase(Locale.ROOT)) && hardware != "unknown") {
                        append(" / $hardware")
                    }
                } else if (hardware.isNotBlank() && hardware != "unknown") {
                    append(hardware)
                    if (board.isNotBlank() && board != hardware && board != "unknown") {
                        append(" / $board")
                    }
                } else {
                    append(board.ifBlank { "universal" })
                }
            }

            val dm = context.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager
            val display = dm?.getDisplay(Display.DEFAULT_DISPLAY)
            val mode = display?.mode
            val density = context.resources.displayMetrics.densityDpi
            val screenMetrics = if (mode != null) {
                " • ${mode.physicalWidth}x${mode.physicalHeight} @ ${density}dpi"
            } else ""

            GameOptimizerEngine.log("soc: $socDesc · ${ramGb}gb ram$screenMetrics", GameOptimizerEngine.LogType.INFO)
        }
    }

    override fun onCleared() {
        super.onCleared()
        val dm = getApplication<Application>().getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager
        dm?.unregisterDisplayListener(displayListener)
    }

    private fun loadInstalledGames() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val context = getApplication<Application>()
                val pm = context.packageManager

                val installedPackages = try {
                    pm.getInstalledPackages(0).associateBy { it.packageName }
                } catch (_: Exception) {
                    emptyMap()
                }

                val resolvedApps = SUPPORTED_CATALOG.map { gameDef ->
                    val installedPkg = gameDef.candidatePackages.firstOrNull { installedPackages.containsKey(it) }
                    if (installedPkg != null) {
                        val appInfo = installedPackages[installedPkg]?.applicationInfo
                        val appLabel = appInfo?.let { pm.getApplicationLabel(it).toString() } ?: gameDef.defaultName
                        GameAppItem(
                            id = gameDef.id,
                            name = appLabel,
                            packageName = installedPkg,
                            recommendedFps = gameDef.recommendedFps,
                            maxSupportedFps = gameDef.maxSupportedFps,
                            recommendationReason = gameDef.recommendationReason,
                            isInstalled = true,
                            supportsRenderScale = gameDef.supportsRenderScale,
                            recommendedScale = gameDef.recommendedScale,
                            scaleReason = gameDef.scaleReason
                        )
                    } else {
                        GameAppItem(
                            id = gameDef.id,
                            name = gameDef.defaultName,
                            packageName = gameDef.candidatePackages.first(),
                            recommendedFps = gameDef.recommendedFps,
                            maxSupportedFps = gameDef.maxSupportedFps,
                            recommendationReason = gameDef.recommendationReason,
                            isInstalled = false,
                            supportsRenderScale = gameDef.supportsRenderScale,
                            recommendedScale = gameDef.recommendedScale,
                            scaleReason = gameDef.scaleReason
                        )
                    }
                }

                val sortedApps = resolvedApps.sortedByDescending { it.isInstalled }
                _availableApps.value = sortedApps

                val currentPkg = _selectedApp.value.packageName
                val matched = sortedApps.find { it.packageName == currentPkg || it.id == _selectedApp.value.id }
                if (matched != null) {
                    _selectedApp.value = matched
                } else if (sortedApps.isNotEmpty()) {
                    _selectedApp.value = sortedApps.first()
                }
            } catch (_: Exception) {}
        }
    }

    private suspend fun updateHardwareThermal() {
        val boostActive = isBoostActive.value
        val serviceTemp = if (boostActive) currentTemperatureC.value else null

        val temp: Float? = when {
            serviceTemp != null -> serviceTemp
            privilegeState.value.isConnected -> try {
                GameOptimizerEngine.queryThermalReading()
            } catch (_: Exception) {
                null
            }
            else -> null
        }

        if (temp != null && temp > 0f) {
            _thermalValue.value = String.format(Locale.US, "%.1f°C", temp)
            val isSevere = closedLoopStatus.value == ClosedLoopStatus.THERMAL_30 || temp >= 45f
            _thermalSubText.value = if (isSevere) "HAL SEVERE" else "HAL SKIN"
        } else {
            _thermalValue.value = "--.-°C"
            _thermalSubText.value = "NO SENSOR"
        }
    }

    fun onPillClicked() {
        if (closedLoopStatus.value == ClosedLoopStatus.DEGRADED) {
            _uiStage.value = UiStage.CONNECTION_GATE
            return
        }
        val state = privilegeState.value
        if (!state.isConnected) {
            PrivilegeManager.refreshState()
            if (!privilegeState.value.isConnected) {
                ShizukuManager.requestPermission()
            }
        } else {
            viewModelScope.launch {
                GameOptimizerEngine.log(
                    "Privilege backend active: ${state.source} (${state.statusMessage})",
                    GameOptimizerEngine.LogType.STDOUT
                )
            }
        }
    }

    fun onPairAdb(context: Context) {
        triggerWirelessAdbPairing(context)
    }

    fun onConnectWirelessAdb(context: Context) {
        viewModelScope.launch(Dispatchers.IO) {
            PrivilegeManager.refreshState()
            delay(350)
            if (!privilegeState.value.isConnected) {
                val discovery = AdbMdnsDiscovery(context)
                discovery.startDiscovery()
                var port: Int? = null
                repeat(8) {
                    port = discovery.connectPort.value
                    if (port != null) return@repeat
                    delay(150)
                }
                discovery.stopDiscovery()

                if (port == null) {
                    val cached = com.optimizer.game.adb.AdbPortStore.getPort(context)
                    if (cached != null && AdbPortScanner.isPortListening(cached, 50)) {
                        port = cached
                    }
                }
                if (port != null) {
                    NativeDaemonManager.ensureDaemonStarted(context, port)
                    PrivilegeManager.refreshState()
                    delay(250)
                }
            }
            if (!privilegeState.value.isConnected) {
                launch(Dispatchers.Main) {
                    triggerWirelessAdbPairing(context)
                }
            }
        }
    }

    fun onPairAdbWithCode(context: Context, portText: String, codeText: String) {
        val explicitPort = portText.trim().takeIf { it.isNotEmpty() }?.toIntOrNull()
        val code = codeText.trim()
        if ((portText.isNotBlank() && (explicitPort == null || explicitPort !in 1024..65535)) || code.length != 6) {
            viewModelScope.launch {
                GameOptimizerEngine.log(
                    "Invalid pairing input: enter a 6-digit code (port optional if mDNS active)",
                    GameOptimizerEngine.LogType.ERROR
                )
            }
            return
        }
        if (_isPairingInProgress.value) return
        _isPairingInProgress.value = true

        viewModelScope.launch(Dispatchers.IO) {
            try {
                val port = explicitPort ?: run {
                    val pairingDiscovery = AdbMdnsDiscovery(context)
                    pairingDiscovery.startDiscovery()
                    var resolved: Int? = null
                    repeat(15) {
                        resolved = pairingDiscovery.pairingPort.value
                        if (resolved != null) return@repeat
                        delay(200)
                    }
                    pairingDiscovery.stopDiscovery()
                    resolved
                }
                if (port == null) {
                    GameOptimizerEngine.log(
                        "Pairing port not discovered via mDNS. Enter the pairing port manually or use notification pairing.",
                        GameOptimizerEngine.LogType.ERROR
                    )
                    return@launch
                }
                val paired = AdbPairingClient.pair(context, port, code)
                if (paired) {
                    val discovery = AdbMdnsDiscovery(context)
                    discovery.startDiscovery()
                    var connectPort: Int? = null
                    repeat(15) {
                        connectPort = discovery.connectPort.value
                        if (connectPort != null) return@repeat
                        delay(200)
                    }
                    discovery.stopDiscovery()

                    if (connectPort == null) {
                        connectPort = AdbPortScanner.findOpenPort(context)
                    }
                    if (connectPort != null) {
                        NativeDaemonManager.ensureDaemonStarted(context, connectPort)
                    }
                    PrivilegeManager.refreshState()
                }
            } finally {
                _isPairingInProgress.value = false
            }
        }
    }

    fun onConnectShizuku() {
        PrivilegeManager.refreshState()
        ShizukuManager.requestPermission()
    }

    fun onSelectApp(app: GameAppItem) {
        if (isBoostActive.value) {
            _isAppDrawerOpen.value = false
            viewModelScope.launch {
                GameOptimizerEngine.log(
                    "Session locked to ${_selectedApp.value.packageName}; disengage first to switch target",
                    GameOptimizerEngine.LogType.INFO
                )
            }
            return
        }

        _selectedApp.value = app
        _isAppDrawerOpen.value = false
        GameOptimizerEngine.setTargetPackage(app.packageName)
        loadGameProfile(app)

        viewModelScope.launch {
            GameOptimizerEngine.log("target set -> ${app.packageName}", GameOptimizerEngine.LogType.STDOUT)
            GameOptimizerEngine.log(
                "smart preset applied: ${_targetFps.value} FPS (${app.recommendationReason})",
                GameOptimizerEngine.LogType.INFO
            )
        }
    }

    fun onSelectDownscale(ratio: String) {
        if (isBoostActive.value) {
            viewModelScope.launch {
                GameOptimizerEngine.log(
                    "Per-App Game Downscale applies on launch; disengage active session to change",
                    GameOptimizerEngine.LogType.INFO
                )
            }
            return
        }
        val normalized = GameOptimizerEngine.normalizeDownscaleRatio(ratio)
        _downscaleRatio.value = normalized
        GameOptimizerEngine.setDownscaleRatio(normalized)
        saveCurrentGameProfile()
        viewModelScope.launch {
            GameOptimizerEngine.log(
                "per-app game downscale set to ${normalized}x for ${_selectedApp.value.name}",
                GameOptimizerEngine.LogType.INFO
            )
        }
    }

    fun onToggleRamTrim(enabled: Boolean) {
        _ramTrimEnabled.value = enabled
        GameOptimizerEngine.setRamTrimEnabled(enabled)
        saveCurrentGameProfile()
        viewModelScope.launch {
            GameOptimizerEngine.log(
                "safe RAM trim (am kill-all) ${if (enabled) "ENABLED" else "DISABLED"}",
                GameOptimizerEngine.LogType.INFO
            )
        }
    }

    fun onToggleDnd(enabled: Boolean) {
        _dndEnabled.value = enabled
        GameOptimizerEngine.setDndEnabled(enabled)
        saveCurrentGameProfile()
        viewModelScope.launch {
            GameOptimizerEngine.log(
                "auto gaming DND ${if (enabled) "ENABLED" else "DISABLED"}",
                GameOptimizerEngine.LogType.INFO
            )
        }
    }

    fun onSelectRenderScale(scale: Int) {
        if (isBoostActive.value) {
            viewModelScope.launch {
                GameOptimizerEngine.log(
                    "WindowManager Render Scale applies on launch; disengage active session to change",
                    GameOptimizerEngine.LogType.INFO
                )
            }
            return
        }
        if (!_deviceProfile.value.supportsWmWrite) return
        _renderScale.value = scale
        updateDisplayMetrics()
        saveCurrentGameProfile()
        viewModelScope.launch {
            if (scale < 100) {
                GameOptimizerEngine.log("render scale set to $scale% for ${_selectedApp.value.name}", GameOptimizerEngine.LogType.INFO)
            } else {
                GameOptimizerEngine.log("render scale set to 100% (native)", GameOptimizerEngine.LogType.INFO)
            }
        }
    }

    private fun updateDisplayMetrics() {
        val dm = getApplication<Application>().getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager
        val display = dm?.getDisplay(Display.DEFAULT_DISPLAY)
        val baseWidth = display?.mode?.physicalWidth ?: 1080
        val baseHeight = display?.mode?.physicalHeight ?: 2400
        val baseDpi = getApplication<Application>().resources.displayMetrics.densityDpi
        val scale = if (_deviceProfile.value.supportsWmWrite) _renderScale.value else 100
        _scaledDisplayMetrics.value = GameOptimizerEngine.computeScaledMetrics(scale, baseWidth, baseHeight, baseDpi)
    }

    private fun saveCurrentGameProfile() {
        val packageName = _selectedApp.value.packageName
        prefs.edit()
            .putInt("${packageName}_fps", _targetFps.value)
            .putInt("${packageName}_scale", _renderScale.value)
            .putString("${packageName}_downscale", _downscaleRatio.value)
            .putBoolean("${packageName}_ram_trim", _ramTrimEnabled.value)
            .putBoolean("${packageName}_dnd", _dndEnabled.value)
            .putBoolean("${packageName}_adaptive_thermal", _isAdaptiveThermalEnabled.value)
            .putBoolean("adaptive_thermal_${packageName}", _isAdaptiveThermalEnabled.value)
            .apply()
    }

    private fun loadGameProfile(app: GameAppItem) {
        val allowedFps = CapabilityProbe.filterSupportedFpsOptions(_deviceProfile.value.maxRefreshRateHz)
        val rawFps = prefs.getInt("${app.packageName}_fps", app.recommendedFps)
        val savedFps = clampFpsToOptions(rawFps, allowedFps)
        val savedScale = if (_deviceProfile.value.supportsWmWrite) {
            prefs.getInt("${app.packageName}_scale", 100)
        } else 100
        val savedDownscale = if (_deviceProfile.value.supportsGameManager) {
            GameOptimizerEngine.normalizeDownscaleRatio(
                prefs.getString("${app.packageName}_downscale", "1.0") ?: "1.0"
            )
        } else "1.0"
        val savedRamTrim = prefs.getBoolean("${app.packageName}_ram_trim", true)
        val savedDnd = prefs.getBoolean("${app.packageName}_dnd", true)
        val savedAdaptiveThermal = prefs.getBoolean(
            "${app.packageName}_adaptive_thermal",
            prefs.getBoolean("adaptive_thermal_${app.packageName}", false)
        )

        _targetFps.value = savedFps
        _renderScale.value = savedScale
        _downscaleRatio.value = savedDownscale
        _ramTrimEnabled.value = savedRamTrim
        _dndEnabled.value = savedDnd
        _isAdaptiveThermalEnabled.value = savedAdaptiveThermal

        GameOptimizerEngine.setTargetFps(savedFps)
        GameOptimizerEngine.setDownscaleRatio(savedDownscale)
        GameOptimizerEngine.setRamTrimEnabled(savedRamTrim)
        GameOptimizerEngine.setDndEnabled(savedDnd)
        updateDisplayMetrics()
    }

    fun onToggleAppDrawer() {
        if (isBoostActive.value) {
            viewModelScope.launch {
                GameOptimizerEngine.log(
                    "Session locked to ${_selectedApp.value.packageName}; disengage first to switch target",
                    GameOptimizerEngine.LogType.INFO
                )
            }
            return
        }
        _isAppDrawerOpen.value = !_isAppDrawerOpen.value
    }

    fun onSelectFps(fps: Int) {
        val allowed = CapabilityProbe.filterSupportedFpsOptions(_deviceProfile.value.maxRefreshRateHz)
        val clamped = clampFpsToOptions(fps, allowed)
        _targetFps.value = clamped
        saveCurrentGameProfile()
        GameOptimizerEngine.setTargetFps(clamped)
        if (isBoostActive.value && privilegeState.value.isConnected) {
            BoostSessionService.switchFps(getApplication(), clamped)
        }
    }

    fun onToggleAdaptiveThermal(enabled: Boolean) {
        _isAdaptiveThermalEnabled.value = enabled
        saveCurrentGameProfile()
        if (isBoostActive.value) {
            BoostSessionService.updateAdaptiveThermal(getApplication(), enabled)
        }
        viewModelScope.launch {
            val stateDesc = if (enabled) "ENABLED" else "DISABLED"
            GameOptimizerEngine.log("adaptive thermal limiter $stateDesc", GameOptimizerEngine.LogType.INFO)
        }
    }

    fun onToggleBoost(context: Context) {
        if (closedLoopStatus.value == ClosedLoopStatus.DEGRADED) {
            _uiStage.value = UiStage.CONNECTION_GATE
            return
        }
        if (!privilegeState.value.isConnected) return

        if (!isBoostActive.value) {
            val profile = _deviceProfile.value
            val scale = if (profile.supportsWmWrite) _renderScale.value else 100
            val downscale = if (profile.supportsGameManager) _downscaleRatio.value else "1.0"
            val dnd = if (profile.supportsDnd) _dndEnabled.value else false
            BoostSessionService.start(
                context = context,
                packageName = _selectedApp.value.packageName,
                targetFps = _targetFps.value,
                dozeEnabled = false,
                renderScale = scale,
                adaptiveThermalEnabled = _isAdaptiveThermalEnabled.value,
                downscaleRatio = downscale,
                ramTrimEnabled = _ramTrimEnabled.value,
                dndEnabled = dnd
            )
        } else {
            BoostSessionService.stop(context)
        }
    }

    fun onClearLogs() {
        _logs.value = emptyList()
        viewModelScope.launch {
            GameOptimizerEngine.log("stdout cleared", GameOptimizerEngine.LogType.INFO)
        }
    }
}
