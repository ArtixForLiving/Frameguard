package com.optimizer.game.viewmodel

import android.app.ActivityManager
import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.display.DisplayManager
import android.os.BatteryManager
import android.os.Build
import android.view.Display
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.optimizer.game.adb.NativeAdbBackend
import com.optimizer.game.optimizer.GameOptimizerEngine
import com.optimizer.game.privilege.PrivilegeManager
import com.optimizer.game.privilege.PrivilegeSource
import com.optimizer.game.privilege.PrivilegeState
import com.optimizer.game.service.BoostSessionService
import com.optimizer.game.shizuku.ShizukuManager
import com.optimizer.game.ui.components.GameAppItem
import com.optimizer.game.ui.components.triggerWirelessAdbPairing
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import rikka.shizuku.Shizuku

class MainViewModel(application: Application) : AndroidViewModel(application) {

    val privilegeState: StateFlow<PrivilegeState> = PrivilegeManager.state
    val isBoostActive: StateFlow<Boolean> = BoostSessionService.isBoostActive
    val pairedRefreshRate: StateFlow<String> = GameOptimizerEngine.pairedRefreshRate

    private val _currentDisplayHz = MutableStateFlow("120Hz")
    val currentDisplayHz: StateFlow<String> = _currentDisplayHz.asStateFlow()

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
                recommendationReason = "Engine native cap 60 FPS · 60Hz lock provides 1:1 VSYNC & lowest thermals",
                supportsRenderScale = false
            ),
            SupportedGameDef(
                id = "genshin",
                defaultName = "Genshin Impact",
                candidatePackages = listOf("com.miHoYo.GenshinImpact", "com.HoYoverse.GenshinImpact", "com.miHoYo.Yuanshen"),
                recommendedFps = 60,
                maxSupportedFps = 60,
                recommendationReason = "Engine hard-capped at 60 FPS · 60Hz lock eliminates thermal throttling",
                supportsRenderScale = true,
                recommendedScale = 75,
                scaleReason = "75% cuts GPU load by 44% for steadier 60 FPS."
            ),
            SupportedGameDef(
                id = "wuwa",
                defaultName = "Wuthering Waves",
                candidatePackages = listOf("com.kurogame.wutheringwaves.global", "com.kurogame.kj"),
                recommendedFps = 60,
                maxSupportedFps = 60,
                recommendationReason = "Android engine capped at 60 FPS · 60Hz lock ensures sustained GPU stability",
                supportsRenderScale = true,
                recommendedScale = 75,
                scaleReason = "75% lowers GPU heat while keeping visuals sharp."
            ),
            SupportedGameDef(
                id = "roblox",
                defaultName = "Roblox",
                candidatePackages = listOf("com.roblox.client"),
                recommendedFps = 60,
                maxSupportedFps = 60,
                recommendationReason = "Official mobile client capped at 60 FPS · 60Hz lock eliminates frame judder & saves battery",
                supportsRenderScale = true,
                recommendedScale = 75,
                scaleReason = "Cuts GPU load while keeping draw distance high."
            ),
            SupportedGameDef(
                id = "pubg",
                defaultName = "PUBG Mobile",
                candidatePackages = listOf("com.tencent.ig", "com.pubg.krmobile", "com.pubg.imobile", "com.vng.pubgmobile", "com.tencent.tmgp.pubgmhd"),
                recommendedFps = 90,
                maxSupportedFps = 120,
                recommendationReason = "Supports up to 120 FPS · 90Hz lock recommended for competitive latency & thermals",
                supportsRenderScale = false
            ),
            SupportedGameDef(
                id = "freefire",
                defaultName = "Free Fire",
                candidatePackages = listOf("com.dts.freefireth", "com.dts.freefiremax"),
                recommendedFps = 90,
                maxSupportedFps = 120,
                recommendationReason = "Supports High-FPS (90/120) · 90Hz lock delivers fluid response & steady frametimes",
                supportsRenderScale = false
            ),
            SupportedGameDef(
                id = "mlbb",
                defaultName = "Mobile Legends: Bang Bang",
                candidatePackages = listOf("com.mobile.legends", "com.vng.mlbbvn", "com.mobilelegends.hwag", "com.mobilelegends.mi"),
                recommendedFps = 90,
                maxSupportedFps = 120,
                recommendationReason = "Supports up to 120 FPS · 90Hz lock recommended for low touch latency & thermals",
                supportsRenderScale = false
            ),
            SupportedGameDef(
                id = "pubgflash",
                defaultName = "PUBG Mobile Flash",
                candidatePackages = listOf("com.tencent.igfit"),
                recommendedFps = 60,
                maxSupportedFps = 60,
                recommendationReason = "Lightweight client capped at 60 FPS · 60Hz lock provides flat 16.6ms frametime",
                supportsRenderScale = false
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

    private val _isDozeEnabled = MutableStateFlow(true)
    val isDozeEnabled: StateFlow<Boolean> = _isDozeEnabled.asStateFlow()

    private val _isAdaptiveThermalEnabled = MutableStateFlow(false)
    val isAdaptiveThermalEnabled: StateFlow<Boolean> = _isAdaptiveThermalEnabled.asStateFlow()

    private val prefs = application.getSharedPreferences("frameguard_game_profiles", Context.MODE_PRIVATE)

    private val _renderScale = MutableStateFlow(100)
    val renderScale: StateFlow<Int> = _renderScale.asStateFlow()

    private val _scaledDisplayMetrics = MutableStateFlow(
        GameOptimizerEngine.computeScaledMetrics(100, 1080, 2400, 420)
    )
    val scaledDisplayMetrics: StateFlow<GameOptimizerEngine.ScaledDisplayMetrics> = _scaledDisplayMetrics.asStateFlow()

    private val _isCompiling = MutableStateFlow(false)
    val isCompiling: StateFlow<Boolean> = _isCompiling.asStateFlow()

    private val _compileProgress = MutableStateFlow(0f)
    val compileProgress: StateFlow<Float> = _compileProgress.asStateFlow()

    private val _compileDone = MutableStateFlow(false)
    val compileDone: StateFlow<Boolean> = _compileDone.asStateFlow()

    private val _thermalValue = MutableStateFlow(getInitialBatteryThermal())
    val thermalValue: StateFlow<String> = _thermalValue.asStateFlow()

    private val _thermalSubText = MutableStateFlow("SOC NOMINAL")
    val thermalSubText: StateFlow<String> = _thermalSubText.asStateFlow()

    private val _logs = MutableStateFlow<List<GameOptimizerEngine.ShellLogEntry>>(emptyList())
    val logs: StateFlow<List<GameOptimizerEngine.ShellLogEntry>> = _logs.asStateFlow()

    init {
        // Collect live engine logs
        viewModelScope.launch {
            GameOptimizerEngine.shellLogs.collect { entry ->
                _logs.value = (_logs.value + entry).takeLast(100)
            }
        }

        // Log real device hardware configuration dynamically
        logDeviceHardwareInfo()

        // Observe live privilege state transitions and log accurately
        viewModelScope.launch {
            var lastReportedSource: PrivilegeSource? = null
            privilegeState.collect { state ->
                if (state.isConnected && state.source != lastReportedSource) {
                    lastReportedSource = state.source
                    when (state.source) {
                        PrivilegeSource.SHIZUKU -> {
                            val version = try {
                                Shizuku.getVersion()
                            } catch (_: Exception) {
                                13
                            }
                            GameOptimizerEngine.log("shizuku api v$version ready (uid=2000)", GameOptimizerEngine.LogType.STDOUT)
                        }
                        PrivilegeSource.NATIVE_ADB -> {
                            GameOptimizerEngine.log("native adb daemon ready (uid=2000)", GameOptimizerEngine.LogType.STDOUT)
                        }
                        else -> {}
                    }
                } else if (!state.isConnected) {
                    lastReportedSource = null
                }
            }
        }

        // Live hardware thermal polling loop
        viewModelScope.launch(Dispatchers.IO) {
            while (isActive) {
                updateHardwareThermal()
                delay(2500)
            }
        }

        // Observe boost state changes to refresh telemetry
        viewModelScope.launch {
            isBoostActive.collect { _ ->
                updateHardwareThermal()
            }
        }

        val nativeBackend = NativeAdbBackend(application)
        PrivilegeManager.registerNativeBackend(nativeBackend)
        PrivilegeManager.init(application)

        // Initialize optimizer engine with active game preset and persisted profile
        GameOptimizerEngine.setTargetPackage(defaultPresets[0].packageName)
        loadGameProfile(defaultPresets[0])

        loadInstalledGames()

        val dm = application.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager
        dm?.registerDisplayListener(displayListener, null)
        queryCurrentRefreshRate()
        updateDisplayMetrics()
    }

    private fun queryCurrentRefreshRate() {
        val dm = getApplication<Application>().getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager
        val display = dm?.getDisplay(Display.DEFAULT_DISPLAY)
        val rate = display?.mode?.refreshRate?.let { Math.round(it) } ?: 120
        _currentDisplayHz.value = "${rate}Hz"
    }

    private fun logDeviceHardwareInfo() {
        viewModelScope.launch(Dispatchers.IO) {
            val context = getApplication<Application>()

            // 1. Dynamic Total RAM
            val actManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            val memInfo = ActivityManager.MemoryInfo()
            actManager?.getMemoryInfo(memInfo)
            val ramGb = if (memInfo.totalMem > 0L) {
                Math.round(memInfo.totalMem.toDouble() / (1024.0 * 1024.0 * 1024.0)).toInt()
            } else 8

            // 2. Dynamic SoC / Hardware Model
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

            // 3. Dynamic Physical Display Metrics
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

                // If currently selected app matches one of the resolved apps, update it
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

    private fun getInitialBatteryThermal(): String {
        return try {
            val intent = getApplication<Application>().registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val raw = intent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1) ?: -1
            if (raw > 0) String.format(Locale.US, "%.1f°C", raw / 10.0f) else "38.0°C"
        } catch (_: Exception) {
            "38.0°C"
        }
    }

    private fun extractHalThermal(dump: String): Float? {
        val halSection = dump.substringAfter("Current temperatures from HAL:", "")
        if (halSection.isEmpty()) return null

        val socRegex = Regex("""Temperature\{mValue=([0-9.]+)[^}]*mName=SOC""")
        val socMatch = socRegex.find(halSection)
        if (socMatch != null) {
            return socMatch.groupValues[1].toFloatOrNull()
        }

        val cpuRegex = Regex("""Temperature\{mValue=([0-9.]+)[^}]*mName=CPU""")
        val cpuMatch = cpuRegex.find(halSection)
        if (cpuMatch != null) {
            return cpuMatch.groupValues[1].toFloatOrNull()
        }

        val batRegex = Regex("""Temperature\{mValue=([0-9.]+)[^}]*mName=BATTERY""")
        val batMatch = batRegex.find(halSection)
        if (batMatch != null) {
            return batMatch.groupValues[1].toFloatOrNull()
        }

        return null
    }

    private fun readBatteryThermal(): Float? {
        return try {
            val intent = getApplication<Application>().registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val raw = intent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1) ?: -1
            if (raw > 0) raw / 10.0f else null
        } catch (_: Exception) {
            null
        }
    }

    private suspend fun updateHardwareThermal() {
        var temp: Float? = null
        var sensorSource = "SOC"

        if (privilegeState.value.isConnected) {
            try {
                val dumpRes = PrivilegeManager.exec("dumpsys thermalservice")
                if (dumpRes.isOk) {
                    temp = extractHalThermal(dumpRes.stdout)
                }
            } catch (_: Exception) {}
        }

        if (temp == null) {
            temp = readBatteryThermal()
            sensorSource = "BATT"
        }

        if (temp != null && temp > 0f) {
            _thermalValue.value = String.format(Locale.US, "%.1f°C", temp)

            val isBoost = isBoostActive.value
            _thermalSubText.value = when {
                isBoost -> when {
                    temp < 40f -> "STABILIZED"
                    temp < 45f -> "ACTIVE · BOOST"
                    else -> "WARM · BOOST"
                }
                sensorSource == "SOC" -> when {
                    temp < 38f -> "SOC COOL"
                    temp < 42f -> "SOC NOMINAL"
                    temp < 46f -> "SOC WARM"
                    else -> "SOC ELEVATED"
                }
                else -> "BATT SENSOR"
            }
        }
    }

    fun onPillClicked() {
        val state = privilegeState.value
        if (!state.isConnected) {
            PrivilegeManager.refreshState()
            if (!privilegeState.value.isConnected) {
                ShizukuManager.requestPermission()
            }
        } else {
            viewModelScope.launch {
                GameOptimizerEngine.log("Privilege backend active: ${state.source} (${state.statusMessage})", GameOptimizerEngine.LogType.STDOUT)
            }
        }
    }

    fun onPairAdb(context: Context) {
        triggerWirelessAdbPairing(context)
    }

    fun onConnectShizuku() {
        ShizukuManager.requestPermission()
    }

    fun onSelectApp(app: GameAppItem) {
        _selectedApp.value = app
        _isAppDrawerOpen.value = false
        _compileDone.value = false
        GameOptimizerEngine.setTargetPackage(app.packageName)

        // Load saved profile or apply defaults with persistence
        loadGameProfile(app)

        viewModelScope.launch {
            GameOptimizerEngine.log("target set -> ${app.packageName}", GameOptimizerEngine.LogType.STDOUT)
            GameOptimizerEngine.log("smart preset applied: ${_targetFps.value} FPS (${app.recommendationReason})", GameOptimizerEngine.LogType.INFO)
            if (app.supportsRenderScale) {
                GameOptimizerEngine.log("render scale directive available (${_renderScale.value}%)", GameOptimizerEngine.LogType.INFO)
            }
        }
    }

    fun onSelectRenderScale(scale: Int) {
        if (!_selectedApp.value.supportsRenderScale) return
        _renderScale.value = scale
        updateDisplayMetrics()
        saveGameProfile(_selectedApp.value.packageName, _targetFps.value, scale, _isDozeEnabled.value)
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
        val scale = if (_selectedApp.value.supportsRenderScale) _renderScale.value else 100
        _scaledDisplayMetrics.value = GameOptimizerEngine.computeScaledMetrics(scale, baseWidth, baseHeight, baseDpi)
    }

    private fun saveGameProfile(
        packageName: String,
        fps: Int,
        scale: Int,
        doze: Boolean,
        adaptiveThermal: Boolean = _isAdaptiveThermalEnabled.value
    ) {
        prefs.edit()
            .putInt("${packageName}_fps", fps)
            .putInt("${packageName}_scale", scale)
            .putBoolean("${packageName}_doze", doze)
            .putBoolean("${packageName}_adaptive_thermal", adaptiveThermal)
            .putBoolean("adaptive_thermal_${packageName}", adaptiveThermal)
            .apply()
    }

    private fun loadGameProfile(app: GameAppItem) {
        val savedFps = prefs.getInt("${app.packageName}_fps", app.recommendedFps)
        val savedScale = if (app.supportsRenderScale) {
            prefs.getInt("${app.packageName}_scale", 100)
        } else 100
        val savedDoze = prefs.getBoolean("${app.packageName}_doze", true)
        val savedAdaptiveThermal = prefs.getBoolean(
            "${app.packageName}_adaptive_thermal",
            prefs.getBoolean("adaptive_thermal_${app.packageName}", false)
        )

        _targetFps.value = savedFps
        _renderScale.value = savedScale
        _isDozeEnabled.value = savedDoze
        _isAdaptiveThermalEnabled.value = savedAdaptiveThermal
        GameOptimizerEngine.setTargetFps(savedFps)
        updateDisplayMetrics()
    }

    fun onToggleAppDrawer() {
        _isAppDrawerOpen.value = !_isAppDrawerOpen.value
    }

    fun onSelectFps(fps: Int) {
        _targetFps.value = fps
        saveGameProfile(_selectedApp.value.packageName, fps, _renderScale.value, _isDozeEnabled.value)
        GameOptimizerEngine.setTargetFps(fps)
        if (isBoostActive.value && privilegeState.value.isConnected) {
            viewModelScope.launch {
                val pkg = _selectedApp.value.packageName
                var effectiveFps = fps
                val optimalHz = when (fps) {
                    60 -> "60.0"
                    90 -> {
                        val supports90 = GameOptimizerEngine.isRefreshRateSupported(90, getApplication())
                        if (supports90) "90.0" else "60.0"
                    }
                    120 -> "120.0"
                    30 -> "60.0"
                    40 -> "120.0"
                    45 -> "90.0"
                    else -> "90.0"
                }

                _targetFps.value = effectiveFps

                // Set custom mode intervention config and activate Custom Game Mode
                val gameSetCmd = "cmd game set --mode 2 --fps $effectiveFps $pkg"
                GameOptimizerEngine.log(gameSetCmd, GameOptimizerEngine.LogType.COMMAND)
                PrivilegeManager.exec(gameSetCmd)

                val gameModeCmd = "cmd game mode custom $pkg"
                GameOptimizerEngine.log(gameModeCmd, GameOptimizerEngine.LogType.COMMAND)
                PrivilegeManager.exec(gameModeCmd)

                // Set refresh rate
                val peakCmd = "settings put system peak_refresh_rate $optimalHz"
                val minCmd = "settings put system min_refresh_rate $optimalHz"
                PrivilegeManager.exec(peakCmd)
                PrivilegeManager.exec(minCmd)

                val pairedLabel = "${optimalHz.substringBefore(".")}Hz"
                GameOptimizerEngine.setPairedHz(pairedLabel)
            }
        }
    }

    fun onToggleDoze(enabled: Boolean) {
        _isDozeEnabled.value = enabled
        saveGameProfile(_selectedApp.value.packageName, _targetFps.value, _renderScale.value, enabled)
        GameOptimizerEngine.setDozeEnabled(enabled)
        if (isBoostActive.value && privilegeState.value.isConnected) {
            viewModelScope.launch {
                val cmd = if (enabled) "dumpsys deviceidle force-idle" else "dumpsys deviceidle unforce"
                GameOptimizerEngine.log(cmd, GameOptimizerEngine.LogType.COMMAND)
                PrivilegeManager.exec(cmd)
            }
        }
    }

    fun onToggleAdaptiveThermal(enabled: Boolean) {
        _isAdaptiveThermalEnabled.value = enabled
        saveGameProfile(_selectedApp.value.packageName, _targetFps.value, _renderScale.value, _isDozeEnabled.value, enabled)
        viewModelScope.launch {
            val stateDesc = if (enabled) "ENABLED" else "DISABLED"
            GameOptimizerEngine.log("adaptive thermal limiter $stateDesc", GameOptimizerEngine.LogType.INFO)
        }
    }

    fun onRunArtCompile() {
        if (_isCompiling.value || !privilegeState.value.isConnected) return

        _isCompiling.value = true
        _compileDone.value = false
        _compileProgress.value = 0f

        viewModelScope.launch {
            val pkg = _selectedApp.value.packageName
            val success = GameOptimizerEngine.compileShaders(pkg) { step, total, _ ->
                _compileProgress.value = step.toFloat() / total.toFloat()
                delay(350)
            }
            _compileProgress.value = 1.0f
            delay(250)
            _isCompiling.value = false
            _compileDone.value = success
        }
    }

    fun onToggleBoost(context: Context) {
        if (!privilegeState.value.isConnected) return

        if (!isBoostActive.value) {
            val scale = if (_selectedApp.value.supportsRenderScale) _renderScale.value else 100
            BoostSessionService.start(
                context = context,
                packageName = _selectedApp.value.packageName,
                targetFps = _targetFps.value,
                dozeEnabled = _isDozeEnabled.value,
                renderScale = scale,
                adaptiveThermalEnabled = _isAdaptiveThermalEnabled.value
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
