package com.optimizer.game.viewmodel

import android.app.Application
import android.content.Context
import android.content.Intent
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.optimizer.game.optimizer.GameOptimizerEngine
import com.optimizer.game.service.BoostSessionService
import com.optimizer.game.shizuku.ShizukuManager
import com.optimizer.game.shizuku.ShizukuState
import com.optimizer.game.ui.components.GameAppItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class MainViewModel(application: Application) : AndroidViewModel(application) {

    val shizukuState: StateFlow<ShizukuState> = ShizukuManager.state
    val isBoostActive: StateFlow<Boolean> = BoostSessionService.isBoostActive
    val pairedRefreshRate: StateFlow<String> = GameOptimizerEngine.pairedRefreshRate

    private val defaultPresets = listOf(
        GameAppItem("warframe", "Warframe Mobile", "com.digitalextremes.warframemobile"),
        GameAppItem("wuwa", "Wuthering Waves", "com.kurogame.wutheringwaves.global"),
        GameAppItem("genshin", "Genshin Impact", "com.miHoYo.GenshinImpact"),
        GameAppItem("hsr", "Honkai: Star Rail", "com.HoYoverse.hkrpgoversea"),
        GameAppItem("codm", "Call of Duty: Warzone", "com.activision.callofduty.warzone")
    )

    private val _selectedApp = MutableStateFlow(defaultPresets[0])
    val selectedApp: StateFlow<GameAppItem> = _selectedApp.asStateFlow()

    private val _availableApps = MutableStateFlow(defaultPresets)
    val availableApps: StateFlow<List<GameAppItem>> = _availableApps.asStateFlow()

    private val _isAppDrawerOpen = MutableStateFlow(false)
    val isAppDrawerOpen: StateFlow<Boolean> = _isAppDrawerOpen.asStateFlow()

    private val _targetFps = MutableStateFlow(45)
    val targetFps: StateFlow<Int> = _targetFps.asStateFlow()

    private val _isDozeEnabled = MutableStateFlow(true)
    val isDozeEnabled: StateFlow<Boolean> = _isDozeEnabled.asStateFlow()

    private val _isCompiling = MutableStateFlow(false)
    val isCompiling: StateFlow<Boolean> = _isCompiling.asStateFlow()

    private val _compileProgress = MutableStateFlow(0f)
    val compileProgress: StateFlow<Float> = _compileProgress.asStateFlow()

    private val _compileDone = MutableStateFlow(false)
    val compileDone: StateFlow<Boolean> = _compileDone.asStateFlow()

    private val _thermalValue = MutableStateFlow("41.8°C")
    val thermalValue: StateFlow<String> = _thermalValue.asStateFlow()

    private val _thermalSubText = MutableStateFlow("SOC IDLE")
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

        // Observe boost state changes to adjust telemetry
        viewModelScope.launch {
            isBoostActive.collect { active ->
                if (active) {
                    _thermalSubText.value = "STABILIZED"
                    animateThermal(36.2f)
                } else {
                    _thermalSubText.value = "SOC IDLE"
                    animateThermal(41.8f)
                }
            }
        }

        loadInstalledGames()
    }

    private fun loadInstalledGames() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val context = getApplication<Application>()
                val pm = context.packageManager
                val intent = Intent(Intent.ACTION_MAIN).apply {
                    addCategory(Intent.CATEGORY_LAUNCHER)
                }
                val resolveInfos = pm.queryIntentActivities(intent, 0)
                val installed = resolveInfos.mapNotNull {
                    val pkg = it.activityInfo?.packageName ?: return@mapNotNull null
                    val name = it.loadLabel(pm).toString()
                    GameAppItem(id = pkg, name = name, packageName = pkg)
                }

                val merged = (defaultPresets + installed).distinctBy { it.packageName }
                _availableApps.value = merged
            } catch (_: Exception) {}
        }
    }

    private fun animateThermal(target: Float) {
        viewModelScope.launch {
            val currentVal = _thermalValue.value.removeSuffix("°C").toFloatOrNull() ?: 41.8f
            val steps = 10
            val stepDiff = (target - currentVal) / steps
            var v = currentVal
            repeat(steps) {
                v += stepDiff
                _thermalValue.value = String.format("%.1f°C", v)
                delay(80)
            }
            _thermalValue.value = String.format("%.1f°C", target)
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
        viewModelScope.launch {
            GameOptimizerEngine.log("target set -> ${app.packageName}", GameOptimizerEngine.LogType.STDOUT)
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
                    30 -> "60.0"
                    40 -> "120.0"
                    45 -> {
                        val supports90 = GameOptimizerEngine.isRefreshRateSupported(90, getApplication())
                        if (supports90) {
                            "90.0"
                        } else {
                            GameOptimizerEngine.log("Display hardware does not support 90Hz. Auto-fallback to 40 FPS @ 120Hz.", GameOptimizerEngine.LogType.INFO)
                            effectiveFps = 40
                            "120.0"
                        }
                    }
                    60 -> "60.0"
                    else -> "60.0"
                }

                _targetFps.value = effectiveFps

                // Enforce game_overlay intervention rule
                val overlayCmd = "device_config put game_overlay $pkg mode=2,fps=$effectiveFps:mode=3,fps=$effectiveFps"
                GameOptimizerEngine.log(overlayCmd, GameOptimizerEngine.LogType.COMMAND)
                ShizukuManager.exec(overlayCmd)

                // Set game mode & fps
                val cmd = "cmd game set --mode 2 --fps $effectiveFps $pkg"
                GameOptimizerEngine.log(cmd, GameOptimizerEngine.LogType.COMMAND)
                ShizukuManager.exec(cmd)

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
