package com.optimizer.game.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.DeadObjectException
import android.os.IBinder
import android.os.PowerManager
import android.widget.RemoteViews
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.optimizer.game.MainActivity
import com.optimizer.game.R
import com.optimizer.game.optimizer.GameOptimizerEngine
import com.optimizer.game.privilege.PrivilegeManager
import com.optimizer.game.privilege.ShellResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.math.abs

enum class ClosedLoopStatus {
    IDLE,
    SYNC_LOCKED,
    OEM_BYPASS,
    THERMAL_30,
    DEGRADED
}

enum class ThermalLimiterState {
    UNCAPPED,
    CAPPED_30FPS
}

data class ProcessAndForegroundState(
    val isProcessAlive: Boolean,
    val isForeground: Boolean
)

internal data class SurfaceFlingerRanges(
    val physicalMaxHz: Float?,
    val renderMaxHz: Float?
)

internal fun parseThermalHeadroomSevereThreshold(dumpsysOutput: String): Float? {
    val match = Regex("""Temperature headroom thresholds:\s*\[([^\]]+)\]""").find(dumpsysOutput)
        ?: return null
    val values = match.groupValues[1].split(",").map { it.trim() }
    val severe = values.getOrNull(PowerManager.THERMAL_STATUS_SEVERE)?.toFloatOrNull()
        ?: return null
    return if (severe.isNaN() || severe <= 0f) null else severe
}

internal fun parseProcessAndForegroundState(
    psOutput: String,
    activitiesOutput: String,
    packageName: String
): ProcessAndForegroundState {
    val isProcessAlive = psOutput.lineSequence().any { line ->
        val parts = line.trim().split(Regex("""\s+"""))
        if (parts.size < 2 || parts[0].toIntOrNull() == null) return@any false
        val procName = parts[1]
        procName == packageName || procName.startsWith("$packageName:")
    }

    val pkgComponentRegex = Regex("""(?:^|\s)${Regex.escape(packageName)}/""")
    val topLines = activitiesOutput.lineSequence()
        .map { it.trim() }
        .filter { it.contains("topResumedActivity") }
        .toList()
    val nonNullTopLines = topLines.filterNot {
        it.endsWith("=null") || it.endsWith(": null") || it.endsWith(" null")
    }

    val isForeground = if (nonNullTopLines.isNotEmpty()) {
        nonNullTopLines.any { pkgComponentRegex.containsMatchIn(it) }
    } else if (topLines.isNotEmpty()) {
        false
    } else {
        activitiesOutput.lineSequence()
            .map { it.trim() }
            .filter { it.contains("mResumedActivity") }
            .filterNot { it.endsWith("=null") || it.endsWith(": null") || it.endsWith(" null") }
            .any { pkgComponentRegex.containsMatchIn(it) }
    }

    return ProcessAndForegroundState(
        isProcessAlive = isProcessAlive,
        isForeground = isForeground
    )
}

internal fun parseSurfaceFlingerPrimaryRanges(sfOutput: String): SurfaceFlingerRanges? {
    val afterPrimary = sfOutput.substringAfter("primaryRanges={", "")
    if (afterPrimary.isEmpty()) return null
    val closing = afterPrimary.indexOf('}')
    val rangesSection = if (closing >= 0) afterPrimary.substring(0, closing) else afterPrimary.take(200)

    val physicalMatch = Regex(
        """physical=\[\s*[0-9.]+(?:\s*(?:Hz|fps))?\s*,\s*([0-9.]+)(?:\s*(?:Hz|fps))?\s*\]""",
        RegexOption.IGNORE_CASE
    ).find(rangesSection)
    val renderMatch = Regex(
        """render=\[\s*[0-9.]+(?:\s*(?:Hz|fps))?\s*,\s*([0-9.]+)(?:\s*(?:Hz|fps))?\s*\]""",
        RegexOption.IGNORE_CASE
    ).find(rangesSection)

    val physicalMax = physicalMatch?.groupValues?.get(1)?.toFloatOrNull()
    val renderMax = renderMatch?.groupValues?.get(1)?.toFloatOrNull()
    if (physicalMax == null && renderMax == null) return null
    return SurfaceFlingerRanges(physicalMaxHz = physicalMax, renderMaxHz = renderMax)
}

internal fun evaluateSurfaceFlingerAudit(
    sfOutput: String,
    gameOutput: String,
    packageName: String,
    expectedFps: Int,
    expectedMaxHz: Float,
    expectedDownscale: String
): Boolean {
    if (!GameOptimizerEngine.verifyGameManagerMode2(gameOutput, packageName, expectedFps, expectedDownscale)) {
        return false
    }
    val ranges = parseSurfaceFlingerPrimaryRanges(sfOutput) ?: return false
    val renderOk = ranges.renderMaxHz == null || ranges.renderMaxHz <= expectedMaxHz + 0.5f
    val physicalOk = ranges.physicalMaxHz == null || ranges.physicalMaxHz <= expectedMaxHz + 0.5f
    return renderOk && physicalOk
}

internal suspend fun applyThermalTransition(
    transition: ThermalLimiterState,
    packageName: String,
    userTargetFps: Int,
    downscaleRatio: String,
    context: Context?
): Boolean {
    val targetFps = when (transition) {
        ThermalLimiterState.CAPPED_30FPS -> 30
        ThermalLimiterState.UNCAPPED -> userTargetFps
    }
    return GameOptimizerEngine.updateLiveGameConfig(
        packageName = packageName,
        targetFps = targetFps,
        downscaleRatio = downscaleRatio,
        context = context
    )
}

class ThermalHysteresisEvaluator(
    val pollIntervalMs: Long = DEFAULT_POLL_INTERVAL_MS,
    engageHeadroomThreshold: Float = DEFAULT_ENGAGE_HEADROOM_THRESHOLD,
    val releaseHeadroomThreshold: Float = DEFAULT_RELEASE_HEADROOM_THRESHOLD
) {
    @Volatile
    var engageHeadroomThreshold: Float = engageHeadroomThreshold
        private set

    private var engageConsecutiveCount = 0
    private var releaseConsecutiveCount = 0

    fun updateEngageThreshold(threshold: Float) {
        if (!threshold.isNaN() && threshold > 0f) {
            engageHeadroomThreshold = threshold
        }
    }

    fun evaluate(
        currentStatus: Int,
        headroom: Float,
        currentState: ThermalLimiterState
    ): ThermalLimiterState? {
        when (currentState) {
            ThermalLimiterState.UNCAPPED -> {
                releaseConsecutiveCount = 0
                val shouldEngage = currentStatus >= PowerManager.THERMAL_STATUS_SEVERE ||
                    (!headroom.isNaN() && headroom >= engageHeadroomThreshold)
                if (shouldEngage) {
                    engageConsecutiveCount++
                    if (engageConsecutiveCount >= 2) {
                        engageConsecutiveCount = 0
                        return ThermalLimiterState.CAPPED_30FPS
                    }
                } else {
                    engageConsecutiveCount = 0
                }
            }
            ThermalLimiterState.CAPPED_30FPS -> {
                engageConsecutiveCount = 0
                val shouldRelease =
                    (currentStatus <= PowerManager.THERMAL_STATUS_MODERATE &&
                        !headroom.isNaN() &&
                        headroom <= releaseHeadroomThreshold) ||
                        (currentStatus < PowerManager.THERMAL_STATUS_SEVERE && headroom.isNaN())
                if (shouldRelease) {
                    releaseConsecutiveCount++
                    if (releaseConsecutiveCount >= 2) {
                        releaseConsecutiveCount = 0
                        return ThermalLimiterState.UNCAPPED
                    }
                } else {
                    releaseConsecutiveCount = 0
                }
            }
        }
        return null
    }

    fun reset() {
        engageConsecutiveCount = 0
        releaseConsecutiveCount = 0
    }

    companion object {
        const val DEFAULT_POLL_INTERVAL_MS = 10_000L
        const val DEFAULT_ENGAGE_HEADROOM_THRESHOLD = 1.0f
        const val DEFAULT_RELEASE_HEADROOM_THRESHOLD = 0.85f
    }
}

enum class WatchdogDecision {
    ALIVE,
    GLITCH,
    MISSING_PENDING,
    EXIT_CONFIRMED,
    BACKGROUND_TIMEOUT
}

class WatchdogCycleEvaluator(
    private val requiredConsecutiveMisses: Int = 2,
    private val backgroundTimeoutMs: Long = BACKGROUND_TIMEOUT_MS
) {
    var consecutiveEmptyCount: Int = 0
        private set

    var backgroundStartMs: Long? = null
        private set

    fun evaluate(result: ShellResult): WatchdogDecision {
        if (!result.isOk && result.transportError != null) {
            return WatchdogDecision.GLITCH
        }
        if (result.timedOut || (result.exitCode != 0 && result.exitCode != 1)) {
            return WatchdogDecision.GLITCH
        }
        return evaluate(result.stdout)
    }

    fun evaluate(pidOut: String): WatchdogDecision {
        val trimmed = pidOut.trim()
        if (trimmed.contains("ERROR")) {
            return WatchdogDecision.GLITCH
        }
        if (trimmed.isEmpty()) {
            consecutiveEmptyCount++
            return if (consecutiveEmptyCount >= requiredConsecutiveMisses) {
                WatchdogDecision.EXIT_CONFIRMED
            } else {
                WatchdogDecision.MISSING_PENDING
            }
        }
        consecutiveEmptyCount = 0
        return WatchdogDecision.ALIVE
    }

    fun evaluateState(
        psResult: ShellResult,
        activitiesResult: ShellResult,
        packageName: String,
        nowMs: Long
    ): WatchdogDecision {
        if (psResult.transportError != null || activitiesResult.transportError != null) {
            return WatchdogDecision.GLITCH
        }
        if (psResult.timedOut || (psResult.exitCode != 0 && psResult.exitCode != 1)) {
            return WatchdogDecision.GLITCH
        }
        if (activitiesResult.timedOut || activitiesResult.exitCode != 0) {
            return WatchdogDecision.GLITCH
        }
        val state = parseProcessAndForegroundState(psResult.stdout, activitiesResult.stdout, packageName)
        return evaluateParsedState(state, nowMs)
    }

    fun evaluateParsedState(
        state: ProcessAndForegroundState,
        nowMs: Long
    ): WatchdogDecision {
        if (!state.isProcessAlive) {
            consecutiveEmptyCount++
            return if (consecutiveEmptyCount >= requiredConsecutiveMisses) {
                WatchdogDecision.EXIT_CONFIRMED
            } else {
                WatchdogDecision.MISSING_PENDING
            }
        }
        consecutiveEmptyCount = 0

        if (state.isForeground) {
            backgroundStartMs = null
            return WatchdogDecision.ALIVE
        }

        val startedAt = backgroundStartMs
        if (startedAt == null) {
            backgroundStartMs = nowMs
            return WatchdogDecision.ALIVE
        }
        return if (nowMs - startedAt >= backgroundTimeoutMs) {
            WatchdogDecision.BACKGROUND_TIMEOUT
        } else {
            WatchdogDecision.ALIVE
        }
    }

    fun reset() {
        consecutiveEmptyCount = 0
        backgroundStartMs = null
    }

    companion object {
        const val BACKGROUND_TIMEOUT_MS = 20_000L
    }
}

class BoostSessionService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val cleanupScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val sessionMutex = Mutex()
    private var telemetryJob: Job? = null
    private var watchdogJob: Job? = null

    private var activePkg: String = GameOptimizerEngine.DEFAULT_PACKAGE
    private var currentTargetFps: Int = 60
    private var currentRenderScale: Int = 100
    private var currentDownscaleRatio: String = "1.0"
    private var currentRamTrim: Boolean = true
    private var currentDnd: Boolean = true
    private var currentAdaptiveThermal: Boolean = false
    private var currentThermalState: ThermalLimiterState = ThermalLimiterState.UNCAPPED
    private var thermalEvaluator = ThermalHysteresisEvaluator()
    private var cachedTemp: Float? = null

    @Volatile
    private var isGameInForeground: Boolean = false

    companion object {
        const val ACTION_START = "com.optimizer.game.action.START_BOOST"
        const val ACTION_STOP = "com.optimizer.game.action.STOP_BOOST"
        const val ACTION_SWITCH_FPS = "com.optimizer.game.action.SWITCH_FPS"
        const val ACTION_UPDATE_THERMAL_CONFIG = "com.optimizer.game.action.UPDATE_THERMAL_CONFIG"

        const val EXTRA_PACKAGE_NAME = "extra_package_name"
        const val EXTRA_TARGET_FPS = "extra_target_fps"
        const val EXTRA_DOZE_ENABLED = "extra_doze_enabled"
        const val EXTRA_RENDER_SCALE = "extra_render_scale"
        const val EXTRA_ADAPTIVE_THERMAL_ENABLED = "extra_adaptive_thermal_enabled"
        const val EXTRA_DOWNSCALE_RATIO = "extra_downscale_ratio"
        const val EXTRA_RAM_TRIM_ENABLED = "extra_ram_trim_enabled"
        const val EXTRA_DND_ENABLED = "extra_dnd_enabled"

        const val WATCHDOG_GRACE_MS = 15_000L
        const val WATCHDOG_POLL_INTERVAL_MS = 3_000L
        const val TELEMETRY_AUDIT_INTERVAL_MS = 10_000L

        private const val NOTIFICATION_CHANNEL_ID = "frameguard_boost_channel"
        private const val NOTIFICATION_ID = 1001

        private val _isBoostActive = MutableStateFlow(false)
        val isBoostActive: StateFlow<Boolean> = _isBoostActive.asStateFlow()

        private val _closedLoopStatus = MutableStateFlow(ClosedLoopStatus.IDLE)
        val closedLoopStatus: StateFlow<ClosedLoopStatus> = _closedLoopStatus.asStateFlow()

        private val _activePackage = MutableStateFlow<String?>(null)
        val activePackage: StateFlow<String?> = _activePackage.asStateFlow()

        private val _currentTemperatureC = MutableStateFlow<Float?>(null)
        val currentTemperatureC: StateFlow<Float?> = _currentTemperatureC.asStateFlow()

        private val _isClamped30Fps = MutableStateFlow(false)
        val isClamped30Fps: StateFlow<Boolean> = _isClamped30Fps.asStateFlow()

        private val _effectiveRefreshRateHz = MutableStateFlow<Float?>(null)
        val effectiveRefreshRateHz: StateFlow<Float?> = _effectiveRefreshRateHz.asStateFlow()

        fun start(
            context: Context,
            packageName: String,
            targetFps: Int,
            @Suppress("UNUSED_PARAMETER") dozeEnabled: Boolean = false,
            renderScale: Int = 100,
            adaptiveThermalEnabled: Boolean = false,
            downscaleRatio: String = GameOptimizerEngine.currentDownscaleRatio,
            ramTrimEnabled: Boolean = GameOptimizerEngine.currentRamTrim,
            dndEnabled: Boolean = GameOptimizerEngine.currentDnd
        ) {
            val intent = Intent(context, BoostSessionService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_PACKAGE_NAME, packageName)
                putExtra(EXTRA_TARGET_FPS, targetFps)
                putExtra(EXTRA_RENDER_SCALE, renderScale)
                putExtra(EXTRA_ADAPTIVE_THERMAL_ENABLED, adaptiveThermalEnabled)
                putExtra(EXTRA_DOWNSCALE_RATIO, downscaleRatio)
                putExtra(EXTRA_RAM_TRIM_ENABLED, ramTrimEnabled)
                putExtra(EXTRA_DND_ENABLED, dndEnabled)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, BoostSessionService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }

        fun updateAdaptiveThermal(context: Context, enabled: Boolean) {
            val intent = Intent(context, BoostSessionService::class.java).apply {
                action = ACTION_UPDATE_THERMAL_CONFIG
                putExtra(EXTRA_ADAPTIVE_THERMAL_ENABLED, enabled)
            }
            context.startService(intent)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val requestedPkg = intent.getStringExtra(EXTRA_PACKAGE_NAME) ?: GameOptimizerEngine.DEFAULT_PACKAGE
                if (!GameOptimizerEngine.isValidPackageName(requestedPkg)) {
                    startForegroundCompat(buildCustomNotification())
                    serviceScope.launch {
                        GameOptimizerEngine.log("Rejected invalid package name: $requestedPkg", GameOptimizerEngine.LogType.ERROR)
                    }
                    ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
                    stopSelf()
                    return START_NOT_STICKY
                }

                val requestedFps = intent.getIntExtra(EXTRA_TARGET_FPS, 60)
                val requestedRenderScale = intent.getIntExtra(EXTRA_RENDER_SCALE, 100)
                val requestedAdaptiveThermal = intent.getBooleanExtra(EXTRA_ADAPTIVE_THERMAL_ENABLED, false)
                val requestedDownscale = GameOptimizerEngine.normalizeDownscaleRatio(
                    intent.getStringExtra(EXTRA_DOWNSCALE_RATIO) ?: GameOptimizerEngine.currentDownscaleRatio
                )
                val requestedRamTrim = intent.getBooleanExtra(EXTRA_RAM_TRIM_ENABLED, GameOptimizerEngine.currentRamTrim)
                val requestedDnd = intent.getBooleanExtra(EXTRA_DND_ENABLED, GameOptimizerEngine.currentDnd)

                startForegroundCompat(buildCustomNotification(previewPkg = requestedPkg, previewFps = requestedFps))

                serviceScope.launch {
                    sessionMutex.withLock {
                        telemetryJob?.cancel()
                        watchdogJob?.cancel()
                        telemetryJob = null
                        watchdogJob = null

                        // Lock activePkg for the lifetime of this session so UI target changes cannot alter restore target.
                        activePkg = requestedPkg
                        currentTargetFps = requestedFps
                        currentRenderScale = requestedRenderScale
                        currentDownscaleRatio = requestedDownscale
                        currentRamTrim = requestedRamTrim
                        currentDnd = requestedDnd
                        currentAdaptiveThermal = requestedAdaptiveThermal
                        currentThermalState = ThermalLimiterState.UNCAPPED
                        thermalEvaluator = ThermalHysteresisEvaluator()
                        cachedTemp = null
                        isGameInForeground = false

                        _activePackage.value = activePkg
                        _currentTemperatureC.value = null
                        _isClamped30Fps.value = (requestedFps == 30)
                        _effectiveRefreshRateHz.value = null
                        _closedLoopStatus.value = ClosedLoopStatus.IDLE
                        _isBoostActive.value = true
                        updateNotification()

                        GameOptimizerEngine.applyProfile(
                            packageName = activePkg,
                            targetFps = currentTargetFps,
                            doze = false,
                            renderScale = currentRenderScale,
                            context = applicationContext,
                            downscaleRatio = currentDownscaleRatio,
                            ramTrim = currentRamTrim,
                            dnd = currentDnd
                        )

                        if (!_isBoostActive.value) return@withLock

                        _effectiveRefreshRateHz.value = parsePairedHzLabel(GameOptimizerEngine.pairedRefreshRate.value)
                        _closedLoopStatus.value = if (PrivilegeManager.state.value.isConnected) {
                            ClosedLoopStatus.SYNC_LOCKED
                        } else {
                            ClosedLoopStatus.DEGRADED
                        }
                        updateNotification()

                        // Start pollers strictly after applyProfile completes so the 15s grace timer begins post-launch.
                        startTelemetryAndAuditPoller()
                        startProcessWatchdog(activePkg)
                    }
                }
            }

            ACTION_SWITCH_FPS -> {
                val newFps = intent.getIntExtra(EXTRA_TARGET_FPS, 30)
                serviceScope.launch {
                    sessionMutex.withLock {
                        if (!_isBoostActive.value) {
                            stopSelf()
                            return@withLock
                        }
                        currentTargetFps = newFps
                        val verified = GameOptimizerEngine.updateLiveGameConfig(
                            packageName = activePkg,
                            targetFps = newFps,
                            downscaleRatio = currentDownscaleRatio,
                            context = applicationContext
                        )
                        _isClamped30Fps.value = (newFps == 30)
                        _effectiveRefreshRateHz.value = parsePairedHzLabel(GameOptimizerEngine.pairedRefreshRate.value)
                        if (!PrivilegeManager.state.value.isConnected) {
                            _closedLoopStatus.value = ClosedLoopStatus.DEGRADED
                        } else if (currentThermalState == ThermalLimiterState.CAPPED_30FPS && newFps == 30) {
                            _closedLoopStatus.value = ClosedLoopStatus.THERMAL_30
                        } else if (verified) {
                            _closedLoopStatus.value = ClosedLoopStatus.SYNC_LOCKED
                        }
                        updateNotification()
                    }
                }
            }

            ACTION_UPDATE_THERMAL_CONFIG -> {
                val enabled = intent.getBooleanExtra(EXTRA_ADAPTIVE_THERMAL_ENABLED, false)
                serviceScope.launch {
                    sessionMutex.withLock {
                        if (!_isBoostActive.value) {
                            stopSelf()
                            return@withLock
                        }
                        currentAdaptiveThermal = enabled
                        if (!enabled && currentThermalState == ThermalLimiterState.CAPPED_30FPS) {
                            currentThermalState = ThermalLimiterState.UNCAPPED
                            thermalEvaluator.reset()
                            val verified = applyThermalTransition(
                                transition = ThermalLimiterState.UNCAPPED,
                                packageName = activePkg,
                                userTargetFps = currentTargetFps,
                                downscaleRatio = currentDownscaleRatio,
                                context = applicationContext
                            )
                            _isClamped30Fps.value = (currentTargetFps == 30)
                            _effectiveRefreshRateHz.value = parsePairedHzLabel(GameOptimizerEngine.pairedRefreshRate.value)
                            _closedLoopStatus.value = when {
                                !PrivilegeManager.state.value.isConnected -> ClosedLoopStatus.DEGRADED
                                verified -> ClosedLoopStatus.SYNC_LOCKED
                                else -> _closedLoopStatus.value
                            }
                            GameOptimizerEngine.log(
                                "Adaptive thermal disabled mid-session; restored $currentTargetFps FPS",
                                GameOptimizerEngine.LogType.STDOUT
                            )
                            updateNotification()
                        } else if (!enabled) {
                            thermalEvaluator.reset()
                        }
                    }
                }
            }

            ACTION_STOP -> {
                cleanupScope.launch {
                    sessionMutex.withLock {
                        performStopLocked(stopService = true)
                    }
                }
            }
        }

        return START_NOT_STICKY
    }

    private fun startForegroundCompat(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private suspend fun performStopLocked(stopService: Boolean) {
        withContext(NonCancellable) {
            telemetryJob?.cancel()
            watchdogJob?.cancel()
            telemetryJob = null
            watchdogJob = null

            if (!_isBoostActive.value) {
                resetSessionStateFlows(ClosedLoopStatus.IDLE)
                if (stopService) {
                    ServiceCompat.stopForeground(this@BoostSessionService, ServiceCompat.STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
                return@withContext
            }

            var finalStatus = ClosedLoopStatus.IDLE
            try {
                currentThermalState = ThermalLimiterState.UNCAPPED
                thermalEvaluator.reset()
                if (PrivilegeManager.state.value.isConnected) {
                    val restored = GameOptimizerEngine.restoreDefaults(activePkg, applicationContext)
                    if (!restored && !PrivilegeManager.state.value.isConnected) {
                        finalStatus = ClosedLoopStatus.DEGRADED
                    }
                } else {
                    GameOptimizerEngine.log(
                        "Privilege link disconnected on stop; retaining recovery snapshot",
                        GameOptimizerEngine.LogType.INFO
                    )
                    finalStatus = ClosedLoopStatus.DEGRADED
                }
            } finally {
                resetSessionStateFlows(finalStatus)
                if (stopService) {
                    ServiceCompat.stopForeground(this@BoostSessionService, ServiceCompat.STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            }
        }
    }

    private fun resetSessionStateFlows(status: ClosedLoopStatus) {
        _isBoostActive.value = false
        _activePackage.value = null
        _isClamped30Fps.value = false
        _effectiveRefreshRateHz.value = null
        _currentTemperatureC.value = null
        _closedLoopStatus.value = status
        cachedTemp = null
        isGameInForeground = false
    }

    private fun startProcessWatchdog(packageName: String) {
        watchdogJob?.cancel()
        watchdogJob = serviceScope.launch {
            delay(WATCHDOG_GRACE_MS)
            val watchdogEvaluator = WatchdogCycleEvaluator()
            while (isActive && _isBoostActive.value) {
                delay(WATCHDOG_POLL_INTERVAL_MS)
                if (!isActive || !_isBoostActive.value) break
                try {
                    if (!PrivilegeManager.state.value.isConnected) {
                        markDegradedIfNeeded("Privilege link disconnected; session DEGRADED (retaining snapshot)")
                        continue
                    }

                    val psResult = PrivilegeManager.execArgs(listOf("ps", "-A", "-o", "PID,NAME"))
                    val activitiesResult = PrivilegeManager.execArgs(listOf("dumpsys", "activity", "activities"))

                    if (psResult.transportError != null || activitiesResult.transportError != null) {
                        val detail = psResult.transportError ?: activitiesResult.transportError ?: "transport error"
                        markDegradedIfNeeded("Watchdog transport error ($detail); session DEGRADED")
                        continue
                    }

                    val parsedState = if (psResult.isOk || psResult.exitCode == 1) {
                        if (activitiesResult.isOk) {
                            parseProcessAndForegroundState(psResult.stdout, activitiesResult.stdout, packageName)
                        } else null
                    } else null

                    if (parsedState != null) {
                        isGameInForeground = parsedState.isForeground
                    }

                    when (watchdogEvaluator.evaluateState(psResult, activitiesResult, packageName, System.currentTimeMillis())) {
                        WatchdogDecision.GLITCH -> {
                            val detail = psResult.stderr.ifBlank { activitiesResult.stderr }
                                .ifBlank { "ps=${psResult.exitCode}, am=${activitiesResult.exitCode}" }
                            GameOptimizerEngine.log("Watchdog: execution glitch ($detail). Retrying...", GameOptimizerEngine.LogType.INFO)
                        }
                        WatchdogDecision.MISSING_PENDING -> {
                            GameOptimizerEngine.log(
                                "Watchdog: process not detected (${watchdogEvaluator.consecutiveEmptyCount}/2 checks for $packageName)",
                                GameOptimizerEngine.LogType.INFO
                            )
                        }
                        WatchdogDecision.EXIT_CONFIRMED -> {
                            GameOptimizerEngine.log(
                                "Process exit confirmed (no main/subprocess after 2 cycles) for $packageName",
                                GameOptimizerEngine.LogType.INFO
                            )
                            stop(this@BoostSessionService)
                            break
                        }
                        WatchdogDecision.BACKGROUND_TIMEOUT -> {
                            GameOptimizerEngine.log(
                                "Background timeout (>= 20s out of foreground) for $packageName; restoring defaults",
                                GameOptimizerEngine.LogType.INFO
                            )
                            stop(this@BoostSessionService)
                            break
                        }
                        WatchdogDecision.ALIVE -> Unit
                    }
                } catch (_: DeadObjectException) {
                    markDegradedIfNeeded("UID 2000 binder dropped; session DEGRADED")
                } catch (e: Exception) {
                    if (e.cause is DeadObjectException) {
                        markDegradedIfNeeded("UID 2000 binder dropped; session DEGRADED")
                    } else {
                        GameOptimizerEngine.log("Watchdog glitch: ${e.message}. Retrying...", GameOptimizerEngine.LogType.INFO)
                    }
                }
            }
        }
    }

    private fun startTelemetryAndAuditPoller() {
        telemetryJob?.cancel()
        telemetryJob = serviceScope.launch {
            val powerManager = getSystemService(Context.POWER_SERVICE) as? PowerManager
            var calibratedHeadroomThreshold = false
            var auditReasserted = false

            while (isActive && _isBoostActive.value) {
                delay(TELEMETRY_AUDIT_INTERVAL_MS)
                if (!isActive || !_isBoostActive.value) break
                try {
                    if (!PrivilegeManager.state.value.isConnected) {
                        markDegradedIfNeeded("Privilege link disconnected; telemetry DEGRADED")
                        continue
                    }

                    // 1. Live ThermalHAL temperature + one-time SEVERE headroom threshold calibration
                    val temp: Float? = if (!calibratedHeadroomThreshold) {
                        val thermalDump = PrivilegeManager.execArgs(listOf("dumpsys", "thermalservice"))
                        if (thermalDump.transportError != null) {
                            markDegradedIfNeeded("Thermal query transport error; session DEGRADED")
                            continue
                        }
                        if (thermalDump.isOk) {
                            calibratedHeadroomThreshold = true
                            parseThermalHeadroomSevereThreshold(thermalDump.stdout)?.let { severeThreshold ->
                                thermalEvaluator.updateEngageThreshold(severeThreshold)
                            }
                            GameOptimizerEngine.parseThermalHalTemperature(thermalDump.stdout)
                        } else {
                            null
                        }
                    } else {
                        GameOptimizerEngine.queryThermalReading()
                    }

                    if (temp != null) {
                        cachedTemp = temp
                        _currentTemperatureC.value = temp
                        updateNotification()
                    }

                    // 2. Adaptive Thermal Guard (rate-limit compliant >= 10_000ms)
                    if (currentAdaptiveThermal) {
                        val status = powerManager?.currentThermalStatus ?: PowerManager.THERMAL_STATUS_NONE
                        val headroom = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && powerManager != null) {
                            powerManager.getThermalHeadroom(10)
                        } else {
                            Float.NaN
                        }

                        val transition = thermalEvaluator.evaluate(status, headroom, currentThermalState)
                        if (transition != null) {
                            sessionMutex.withLock {
                                if (!_isBoostActive.value || !currentAdaptiveThermal) return@withLock
                                currentThermalState = transition
                                val verified = applyThermalTransition(
                                    transition = transition,
                                    packageName = activePkg,
                                    userTargetFps = currentTargetFps,
                                    downscaleRatio = currentDownscaleRatio,
                                    context = applicationContext
                                )
                                _effectiveRefreshRateHz.value = parsePairedHzLabel(GameOptimizerEngine.pairedRefreshRate.value)
                                when (transition) {
                                    ThermalLimiterState.CAPPED_30FPS -> {
                                        _isClamped30Fps.value = true
                                        _closedLoopStatus.value = if (PrivilegeManager.state.value.isConnected) {
                                            ClosedLoopStatus.THERMAL_30
                                        } else {
                                            ClosedLoopStatus.DEGRADED
                                        }
                                        GameOptimizerEngine.log(
                                            "Thermal limit engaged: 30 FPS clamp (Mode2Verified=$verified)",
                                            GameOptimizerEngine.LogType.STDOUT
                                        )
                                    }
                                    ThermalLimiterState.UNCAPPED -> {
                                        _isClamped30Fps.value = (currentTargetFps == 30)
                                        _closedLoopStatus.value = if (PrivilegeManager.state.value.isConnected) {
                                            ClosedLoopStatus.SYNC_LOCKED
                                        } else {
                                            ClosedLoopStatus.DEGRADED
                                        }
                                        auditReasserted = false
                                        GameOptimizerEngine.log(
                                            "Thermal limit released: restored $currentTargetFps FPS (Mode2Verified=$verified)",
                                            GameOptimizerEngine.LogType.STDOUT
                                        )
                                    }
                                }
                                updateNotification()
                            }
                        }
                    }

                    // 3. LTPO-safe SurfaceFlinger & dumpsys game closed-loop audit (only after game is topResumedActivity)
                    val foregroundReady = if (isGameInForeground) {
                        true
                    } else {
                        val actRes = PrivilegeManager.execArgs(listOf("dumpsys", "activity", "activities"))
                        if (actRes.transportError != null) {
                            markDegradedIfNeeded("Activity query transport error; session DEGRADED")
                            continue
                        }
                        if (actRes.isOk) {
                            val fg = parseProcessAndForegroundState("", actRes.stdout, activePkg).isForeground
                            if (fg) isGameInForeground = true
                            fg
                        } else {
                            false
                        }
                    }

                    if (!foregroundReady) continue

                    val sfRes = PrivilegeManager.execArgs(listOf("dumpsys", "SurfaceFlinger"))
                    val gameRes = PrivilegeManager.execArgs(listOf("dumpsys", "game"))
                    if (sfRes.transportError != null || gameRes.transportError != null) {
                        markDegradedIfNeeded("Audit transport error; session DEGRADED")
                        continue
                    }
                    if (!sfRes.isOk || !gameRes.isOk) continue

                    sessionMutex.withLock {
                        if (!_isBoostActive.value) return@withLock
                        val expectedFps = if (currentThermalState == ThermalLimiterState.CAPPED_30FPS) 30 else currentTargetFps
                        val expectedMaxHz = parsePairedHzLabel(GameOptimizerEngine.pairedRefreshRate.value)
                            ?: GameOptimizerEngine.resolveOptimalRefreshRate(expectedFps, emptyList())
                        val ranges = parseSurfaceFlingerPrimaryRanges(sfRes.stdout)
                        _effectiveRefreshRateHz.value = ranges?.renderMaxHz ?: ranges?.physicalMaxHz ?: expectedMaxHz

                        val compliant = if (Build.VERSION.SDK_INT == 0 || Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            evaluateSurfaceFlingerAudit(
                                sfOutput = sfRes.stdout,
                                gameOutput = gameRes.stdout,
                                packageName = activePkg,
                                expectedFps = expectedFps,
                                expectedMaxHz = expectedMaxHz,
                                expectedDownscale = currentDownscaleRatio
                            )
                        } else {
                            ranges != null &&
                                (ranges.renderMaxHz == null || ranges.renderMaxHz <= expectedMaxHz + 0.5f) &&
                                (ranges.physicalMaxHz == null || ranges.physicalMaxHz <= expectedMaxHz + 0.5f)
                        }

                        if (compliant) {
                            auditReasserted = false
                            val desiredStatus = if (currentThermalState == ThermalLimiterState.CAPPED_30FPS) {
                                ClosedLoopStatus.THERMAL_30
                            } else {
                                ClosedLoopStatus.SYNC_LOCKED
                            }
                            if (_closedLoopStatus.value != desiredStatus) {
                                _closedLoopStatus.value = desiredStatus
                            }
                            updateNotification()
                        } else if (!auditReasserted) {
                            auditReasserted = true
                            GameOptimizerEngine.log(
                                "OEM_OVERRIDE_DETECTED for $activePkg; re-asserting Mode 2 and Hz lock",
                                GameOptimizerEngine.LogType.INFO
                            )
                            GameOptimizerEngine.updateLiveGameConfig(
                                packageName = activePkg,
                                targetFps = expectedFps,
                                downscaleRatio = currentDownscaleRatio,
                                context = applicationContext
                            )
                            _effectiveRefreshRateHz.value = parsePairedHzLabel(GameOptimizerEngine.pairedRefreshRate.value)
                            updateNotification()
                        } else {
                            if (_closedLoopStatus.value != ClosedLoopStatus.OEM_BYPASS) {
                                _closedLoopStatus.value = ClosedLoopStatus.OEM_BYPASS
                                GameOptimizerEngine.log(
                                    "OEM_BYPASS: system daemon overrode $activePkg after re-assert",
                                    GameOptimizerEngine.LogType.ERROR
                                )
                                updateNotification()
                            }
                        }
                    }
                } catch (_: DeadObjectException) {
                    markDegradedIfNeeded("UID 2000 binder dropped; session DEGRADED")
                } catch (e: Exception) {
                    if (e.cause is DeadObjectException) {
                        markDegradedIfNeeded("UID 2000 binder dropped; session DEGRADED")
                    } else {
                        GameOptimizerEngine.log("Telemetry/audit error: ${e.message}", GameOptimizerEngine.LogType.INFO)
                    }
                }
            }
        }
    }

    private suspend fun markDegradedIfNeeded(message: String) {
        if (_closedLoopStatus.value != ClosedLoopStatus.DEGRADED) {
            _closedLoopStatus.value = ClosedLoopStatus.DEGRADED
            GameOptimizerEngine.log(message, GameOptimizerEngine.LogType.ERROR)
            updateNotification()
        }
    }

    private fun parsePairedHzLabel(label: String): Float? =
        label.removeSuffix("Hz").removeSuffix("hz").trim().toFloatOrNull()

    private fun updateNotification() {
        val manager = getSystemService(NotificationManager::class.java)
        manager?.notify(NOTIFICATION_ID, buildCustomNotification())
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.notification_channel_description)
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    private fun buildCustomNotification(
        previewPkg: String = activePkg,
        previewFps: Int = currentTargetFps
    ): Notification {
        val remoteViews = RemoteViews(packageName, R.layout.notification_frameguard)

        // Game title
        val displayTitle = previewPkg.substringAfterLast(".").uppercase()
        remoteViews.setTextViewText(R.id.notification_game_title, displayTitle)

        // Closed-loop badge
        val pairedHz = GameOptimizerEngine.pairedRefreshRate.value
        val statusLabel = when (_closedLoopStatus.value) {
            ClosedLoopStatus.SYNC_LOCKED -> "SYNC LOCKED"
            ClosedLoopStatus.OEM_BYPASS -> "OEM BYPASS"
            ClosedLoopStatus.THERMAL_30 -> "THERMAL 30"
            ClosedLoopStatus.DEGRADED -> "DEGRADED"
            ClosedLoopStatus.IDLE -> "SYNC LOCKED"
        }
        val effectiveFps = if (currentThermalState == ThermalLimiterState.CAPPED_30FPS) 30 else previewFps
        val capText = buildString {
            append(statusLabel)
            append(" • ")
            append(effectiveFps)
            append(" FPS")
            if (currentDownscaleRatio != "1.0") {
                append(" • ")
                append(currentDownscaleRatio)
                append("x")
            } else if (currentRenderScale < 100) {
                append(" • ")
                append(currentRenderScale)
                append("%")
            }
            append(" • ")
            append(pairedHz)
        }
        remoteViews.setTextViewText(R.id.notification_cap_badge, capText)

        // Telemetry
        val tempText = cachedTemp?.let { String.format(Locale.US, "%.1f°C", it) } ?: "--.-°C"
        val dndText = if (currentDnd) "ON" else "OFF"
        val privText = PrivilegeManager.state.value.statusMessage
        remoteViews.setTextViewText(
            R.id.notification_telemetry,
            "TEMP: $tempText | DND: $dndText | $privText"
        )

        // Action: 30 FPS Quick Throttle Drop Button
        val fpsIntent = Intent(this, BoostSessionService::class.java).apply {
            action = ACTION_SWITCH_FPS
            putExtra(EXTRA_TARGET_FPS, 30)
        }
        val fpsPendingIntent = PendingIntent.getService(
            this,
            2,
            fpsIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        remoteViews.setOnClickPendingIntent(R.id.notification_action_fps, fpsPendingIntent)

        // Action: STOP & RESTORE Button
        val stopIntent = Intent(this, BoostSessionService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this,
            1,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        remoteViews.setOnClickPendingIntent(R.id.notification_action_stop, stopPendingIntent)

        // Content intent: open MainActivity
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setStyle(NotificationCompat.DecoratedCustomViewStyle())
            .setCustomContentView(remoteViews)
            .setContentTitle(displayTitle)
            .setContentText(capText)
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        if (_isBoostActive.value) {
            cleanupScope.launch {
                sessionMutex.withLock {
                    performStopLocked(stopService = true)
                }
            }
        }
    }

    override fun onDestroy() {
        telemetryJob?.cancel()
        watchdogJob?.cancel()
        if (_isBoostActive.value) {
            cleanupScope.launch {
                sessionMutex.withLock {
                    performStopLocked(stopService = false)
                }
            }
        } else {
            resetSessionStateFlows(ClosedLoopStatus.IDLE)
        }
        serviceScope.cancel()
        super.onDestroy()
    }
}
