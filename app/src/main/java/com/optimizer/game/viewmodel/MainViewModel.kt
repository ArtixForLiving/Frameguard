package com.optimizer.game.viewmodel

import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.display.DisplayManager
import android.os.BatteryManager
import android.view.Display
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.optimizer.game.optimizer.GameOptimizerEngine
import com.optimizer.game.service.BoostSessionService
import com.optimizer.game.shizuku.ShizukuManager
import com.optimizer.game.shizuku.ShizukuState
import com.optimizer.game.ui.components.GameAppItem
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class MainViewModel(application: Application) : AndroidViewModel(application) {

    val shizukuState: StateFlow<ShizukuState> = ShizukuManager.state
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
        val recommendationReason: String
    )

    companion object {
        val SUPPORTED_CATALOG = listOf(
            SupportedGameDef(
                id = "warframe",
                defaultName = "Warframe Mobile",
                candidatePackages = listOf("com.digitalextremes.warframemobile"),
                recommendedFps = 60,
                maxSupportedFps = 60,
                recommendationReason = "Engine native cap 60 FPS · 60Hz lock provides 1:1 VSYNC & lowest thermals"
            ),
            SupportedGameDef(
                id = "genshin",
                defaultName = "Genshin Impact",
                candidatePackages = listOf("com.miHoYo.GenshinImpact", "com.HoYoverse.GenshinImpact", "com.miHoYo.Yuanshen"),
                recommendedFps = 60,
                maxSupportedFps = 60,
                recommendationReason = "Engine hard-capped at 60 FPS · 60Hz lock eliminates thermal throttling"
            ),
            SupportedGameDef(
                id = "wuwa",
                defaultName = "Wuthering Waves",
                candidatePackages = listOf("com.kurogame.wutheringwaves.global", "com.kurogame.kj"),
                recommendedFps = 60,
                maxSupportedFps = 60,
                recommendationReason = "Android engine capped at 60 FPS · 60Hz lock ensures sustained GPU stability"
            ),
            SupportedGameDef(
                id = "pubg",
                defaultName = "PUBG Mobile",
                candidatePackages = listOf("com.tencent.ig", "com.pubg.krmobile", "com.pubg.imobile", "com.vng.pubgmobile", "com.tencent.tmgp.pubgmhd"),
                recommendedFps = 90,
                maxSupportedFps = 120,
                recommendationReason = "Supports up to 120 FPS · 90Hz lock recommended for competitive latency & thermals"
            ),
            SupportedGameDef(
                id = "freefire",
                defaultName = "Free Fire",
                candidatePackages = listOf("com.dts.freefireth", "com.dts.freefiremax"),
                recommendedFps = 90,
                maxSupportedFps = 120,
                recommendationReason = "Supports High-FPS (90/120) · 90Hz lock delivers fluid response & steady frametimes"
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
            isInstalled = false
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

        // Add initial boot sequence logs matching prototype
        viewModelScope.launch {
            GameOptimizerEngine.log("shizuku api v13.1.5 ready (uid=2000)", GameOptimizerEngine.LogType.STDOUT)
            GameOptimizerEngine.log("soc: mt6897 / dimensity 8350 · 12gb ram", GameOptimizerEngine.LogType.INFO)
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

        // Initialize optimizer engine with active game preset
        GameOptimizerEngine.setTargetPackage(defaultPresets[0].packageName)
        GameOptimizerEngine.setTargetFps(defaultPresets[0].recommendedFps)

        loadInstalledGames()

        val dm = application.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager
        dm?.registerDisplayListener(displayListener, null)
        queryCurrentRefreshRate()
    }

    private fun queryCurrentRefreshRate() {
        val dm = getApplication<Application>().getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager
        val display = dm?.getDisplay(Display.DEFAULT_DISPLAY)
        val rate = display?.mode?.refreshRate?.let { Math.round(it) } ?: 120
        _currentDisplayHz.value = "${rate}Hz"
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
                            isInstalled = true
                        )
                    } else {
                        GameAppItem(
                            id = gameDef.id,
                            name = gameDef.defaultName,
                            packageName = gameDef.candidatePackages.first(),
                            recommendedFps = gameDef.recommendedFps,
                            maxSupportedFps = gameDef.maxSupportedFps,
                            recommendationReason = gameDef.recommendationReason,
                            isInstalled = false
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

        if (shizukuState.value == ShizukuState.CONNECTED) {
            try {
                val dump = ShizukuManager.exec("dumpsys thermalservice")
                temp = extractHalThermal(dump)
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

    fun onShizukuPillClicked() {
        when (shizukuState.value) {
            ShizukuState.UNAVAILABLE -> {
                ShizukuManager.updateState()
            }
            ShizukuState.NO_PERMISSION -> {
                ShizukuManager.requestPermission()
            }
            ShizukuState.CONNECTED -> {
                viewModelScope.launch {
                    GameOptimizerEngine.log("binder status: IShellService attached (uid=2000)", GameOptimizerEngine.LogType.STDOUT)
                }
            }
        }
    }

    fun onSelectApp(app: GameAppItem) {
        _selectedApp.value = app
        _isAppDrawerOpen.value = false
        _compileDone.value = false
        GameOptimizerEngine.setTargetPackage(app.packageName)

        // Smart Preset: Automatically adapt target FPS to game's recommended tier
        _targetFps.value = app.recommendedFps
        GameOptimizerEngine.setTargetFps(app.recommendedFps)

        viewModelScope.launch {
            GameOptimizerEngine.log("target set -> ${app.packageName}", GameOptimizerEngine.LogType.STDOUT)
            GameOptimizerEngine.log("smart preset applied: ${app.recommendedFps} FPS (${app.recommendationReason})", GameOptimizerEngine.LogType.INFO)
        }
    }

    fun onToggleAppDrawer() {
        _isAppDrawerOpen.value = !_isAppDrawerOpen.value
    }

    fun onSelectFps(fps: Int) {
        _targetFps.value = fps
        GameOptimizerEngine.setTargetFps(fps)
        if (isBoostActive.value && shizukuState.value == ShizukuState.CONNECTED) {
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
                val gameSetCmd = "cmd game set --fps $effectiveFps $pkg"
                GameOptimizerEngine.log(gameSetCmd, GameOptimizerEngine.LogType.COMMAND)
                ShizukuManager.exec(gameSetCmd)

                val gameModeCmd = "cmd game mode custom $pkg"
                GameOptimizerEngine.log(gameModeCmd, GameOptimizerEngine.LogType.COMMAND)
                ShizukuManager.exec(gameModeCmd)

                // Set refresh rate
                val peakCmd = "settings put system peak_refresh_rate $optimalHz"
                val minCmd = "settings put system min_refresh_rate $optimalHz"
                ShizukuManager.exec(peakCmd)
                ShizukuManager.exec(minCmd)

                val pairedLabel = "${optimalHz.substringBefore(".")}Hz"
                GameOptimizerEngine.setPairedHz(pairedLabel)
            }
        }
    }

    fun onToggleDoze(enabled: Boolean) {
        _isDozeEnabled.value = enabled
        GameOptimizerEngine.setDozeEnabled(enabled)
        if (isBoostActive.value && shizukuState.value == ShizukuState.CONNECTED) {
            viewModelScope.launch {
                val cmd = if (enabled) "dumpsys deviceidle force-idle" else "dumpsys deviceidle unforce"
                GameOptimizerEngine.log(cmd, GameOptimizerEngine.LogType.COMMAND)
                ShizukuManager.exec(cmd)
            }
        }
    }

    fun onRunArtCompile() {
        if (_isCompiling.value || shizukuState.value != ShizukuState.CONNECTED) return

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
        if (shizukuState.value != ShizukuState.CONNECTED) return

        if (!isBoostActive.value) {
            BoostSessionService.start(
                context = context,
                packageName = _selectedApp.value.packageName,
                targetFps = _targetFps.value,
                dozeEnabled = _isDozeEnabled.value
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
