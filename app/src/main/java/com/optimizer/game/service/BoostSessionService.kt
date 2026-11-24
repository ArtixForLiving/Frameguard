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
import android.os.IBinder
import android.os.PowerManager
import android.widget.RemoteViews
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.optimizer.game.MainActivity
import com.optimizer.game.R
import com.optimizer.game.optimizer.GameOptimizerEngine
import com.optimizer.game.privilege.PrivilegeManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Locale

enum class ThermalLimiterState {
    UNCAPPED,
    CAPPED_30FPS
}

class ThermalHysteresisEvaluator(val pollIntervalMs: Long = 3000) {
    private var engageConsecutiveCount = 0
    private var releaseConsecutiveCount = 0

    fun evaluate(
        currentStatus: Int,
        headroom: Float,
        currentState: ThermalLimiterState
    ): ThermalLimiterState? {
        when (currentState) {
            ThermalLimiterState.UNCAPPED -> {
                releaseConsecutiveCount = 0
                val shouldEngage = currentStatus >= PowerManager.THERMAL_STATUS_MODERATE ||
                        (!headroom.isNaN() && headroom >= HEADROOM_ENGAGE_THRESHOLD)
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
                val shouldRelease = currentStatus <= PowerManager.THERMAL_STATUS_LIGHT &&
                        (headroom.isNaN() || headroom <= HEADROOM_RELEASE_THRESHOLD)
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
        const val HEADROOM_ENGAGE_THRESHOLD = 0.90f
        const val HEADROOM_RELEASE_THRESHOLD = 0.75f
    }
}

class BoostSessionService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var telemetryJob: Job? = null
    private var watchdogJob: Job? = null

    private var currentPkg: String = GameOptimizerEngine.DEFAULT_PACKAGE
    private var currentTargetFps: Int = 60
    private var currentDoze: Boolean = true
    private var currentRenderScale: Int = 100
    private var currentAdaptiveThermal: Boolean = false
    private var currentThermalState: ThermalLimiterState = ThermalLimiterState.UNCAPPED
    private val thermalEvaluator = ThermalHysteresisEvaluator()
    private var cachedTemp: Float? = null

    companion object {
        const val ACTION_START = "com.optimizer.game.action.START_BOOST"
        const val ACTION_STOP = "com.optimizer.game.action.STOP_BOOST"
        const val ACTION_SWITCH_FPS = "com.optimizer.game.action.SWITCH_FPS"

        const val EXTRA_PACKAGE_NAME = "extra_package_name"
        const val EXTRA_TARGET_FPS = "extra_target_fps"
        const val EXTRA_DOZE_ENABLED = "extra_doze_enabled"
        const val EXTRA_RENDER_SCALE = "extra_render_scale"
        const val EXTRA_ADAPTIVE_THERMAL_ENABLED = "extra_adaptive_thermal_enabled"

        private const val NOTIFICATION_CHANNEL_ID = "frameguard_boost_channel"
        private const val NOTIFICATION_ID = 1001

        private val _isBoostActive = MutableStateFlow(false)
        val isBoostActive: StateFlow<Boolean> = _isBoostActive.asStateFlow()

        fun start(
            context: Context,
            packageName: String,
            targetFps: Int,
            dozeEnabled: Boolean,
            renderScale: Int = 100,
            adaptiveThermalEnabled: Boolean = false
        ) {
            val intent = Intent(context, BoostSessionService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_PACKAGE_NAME, packageName)
                putExtra(EXTRA_TARGET_FPS, targetFps)
                putExtra(EXTRA_DOZE_ENABLED, dozeEnabled)
                putExtra(EXTRA_RENDER_SCALE, renderScale)
                putExtra(EXTRA_ADAPTIVE_THERMAL_ENABLED, adaptiveThermalEnabled)
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
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                currentPkg = intent.getStringExtra(EXTRA_PACKAGE_NAME) ?: GameOptimizerEngine.DEFAULT_PACKAGE
                currentTargetFps = intent.getIntExtra(EXTRA_TARGET_FPS, 60)
                currentDoze = intent.getBooleanExtra(EXTRA_DOZE_ENABLED, true)
                currentRenderScale = intent.getIntExtra(EXTRA_RENDER_SCALE, 100)
                currentAdaptiveThermal = intent.getBooleanExtra(EXTRA_ADAPTIVE_THERMAL_ENABLED, false)
                currentThermalState = ThermalLimiterState.UNCAPPED
                thermalEvaluator.reset()

                val notification = buildCustomNotification()
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    ServiceCompat.startForeground(
                        this,
                        NOTIFICATION_ID,
                        notification,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                    )
                } else {
                    startForeground(NOTIFICATION_ID, notification)
                }

                _isBoostActive.value = true

                serviceScope.launch {
                    GameOptimizerEngine.applyProfile(currentPkg, currentTargetFps, currentDoze, currentRenderScale, applicationContext)
                    updateNotification()
                }

                startTelemetryPoller()
                startProcessWatchdog(currentPkg)
            }

            ACTION_SWITCH_FPS -> {
                val newFps = intent.getIntExtra(EXTRA_TARGET_FPS, 30)
                currentTargetFps = newFps
                serviceScope.launch {
                    GameOptimizerEngine.applyProfile(currentPkg, currentTargetFps, currentDoze, currentRenderScale, applicationContext)
                    updateNotification()
                }
            }

            ACTION_STOP -> {
                telemetryJob?.cancel()
                watchdogJob?.cancel()
                serviceScope.launch {
                    try {
                        if (currentThermalState == ThermalLimiterState.CAPPED_30FPS) {
                            PrivilegeManager.exec("cmd game reset $currentPkg")
                            PrivilegeManager.exec("device_config delete game_overlay $currentPkg")
                            currentThermalState = ThermalLimiterState.UNCAPPED
                        }
                        thermalEvaluator.reset()
                        GameOptimizerEngine.restoreDefaults(currentPkg, applicationContext)
                    } finally {
                        _isBoostActive.value = false
                        ServiceCompat.stopForeground(this@BoostSessionService, ServiceCompat.STOP_FOREGROUND_REMOVE)
                        stopSelf()
                    }
                }
            }
        }

        return START_NOT_STICKY
    }

    private fun startProcessWatchdog(packageName: String) {
        watchdogJob?.cancel()
        watchdogJob = serviceScope.launch {
            // Initial launch grace period (8000ms) for the game process to initialize
            delay(8000)
            while (isActive && _isBoostActive.value) {
                delay(3000)
                try {
                    val pidOut = PrivilegeManager.exec("pidof $packageName").trim()
                    if (pidOut.isEmpty() || pidOut.contains("ERROR")) {
                        // Game process terminated or swiped away from Recents
                        GameOptimizerEngine.log("Process exit detected for $packageName (PID terminated)", GameOptimizerEngine.LogType.INFO)
                        stop(this@BoostSessionService)
                        break
                    }
                } catch (_: Exception) {}
            }
        }
    }

    private fun startTelemetryPoller() {
        telemetryJob?.cancel()
        telemetryJob = serviceScope.launch {
            val powerManager = getSystemService(Context.POWER_SERVICE) as? PowerManager
            while (isActive && _isBoostActive.value) {
                delay(3000)
                try {
                    val temp = GameOptimizerEngine.queryThermalReading()
                    if (temp != null) {
                        cachedTemp = temp
                        updateNotification()
                    }

                    if (currentAdaptiveThermal) {
                        val status = powerManager?.currentThermalStatus ?: PowerManager.THERMAL_STATUS_NONE
                        val headroom = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && powerManager != null) {
                            powerManager.getThermalHeadroom(10)
                        } else {
                            Float.NaN
                        }

                        val transition = thermalEvaluator.evaluate(status, headroom, currentThermalState)
                        if (transition != null) {
                            when (transition) {
                                ThermalLimiterState.CAPPED_30FPS -> {
                                    currentThermalState = ThermalLimiterState.CAPPED_30FPS
                                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                        PrivilegeManager.exec("cmd game set --mode 3 --fps 30 $currentPkg")
                                        PrivilegeManager.exec("cmd game mode battery $currentPkg")
                                    } else {
                                        PrivilegeManager.exec("device_config put game_overlay $currentPkg mode=3,fps=30")
                                    }
                                    GameOptimizerEngine.log("Thermal limit engaged: 30 FPS locked (MODERATE / VSYNC skip)", GameOptimizerEngine.LogType.STDOUT)
                                    updateNotification()
                                }
                                ThermalLimiterState.UNCAPPED -> {
                                    currentThermalState = ThermalLimiterState.UNCAPPED
                                    PrivilegeManager.exec("cmd game reset $currentPkg")
                                    PrivilegeManager.exec("device_config delete game_overlay $currentPkg")
                                    GameOptimizerEngine.log("Thermal limit released: uncapped (LIGHT)", GameOptimizerEngine.LogType.STDOUT)
                                    updateNotification()
                                }
                            }
                        }
                    }
                } catch (_: Exception) {}
            }
        }
    }

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

    private fun buildCustomNotification(): Notification {
        val remoteViews = RemoteViews(packageName, R.layout.notification_frameguard)

        // Game title
        val displayTitle = currentPkg.substringAfterLast(".").uppercase()
        remoteViews.setTextViewText(R.id.notification_game_title, displayTitle)

        // Badge
        val pairedHz = GameOptimizerEngine.pairedRefreshRate.value
        val isThermalCapped = currentThermalState == ThermalLimiterState.CAPPED_30FPS
        val capText = if (isThermalCapped) {
            "Adaptive FPS Guard: 30 FPS (Thermal Saver)"
        } else if (currentRenderScale < 100) {
            "CAP $currentTargetFps • ${currentRenderScale}% • $pairedHz"
        } else {
            "CAP $currentTargetFps • $pairedHz"
        }
        remoteViews.setTextViewText(R.id.notification_cap_badge, capText)

        // Telemetry
        val tempText = cachedTemp?.let { String.format(Locale.US, "%.1f°C", it) } ?: "36.2°C"
        val dozeText = if (currentDoze) "ON" else "OFF"
        val privText = PrivilegeManager.state.value.statusMessage
        remoteViews.setTextViewText(
            R.id.notification_telemetry,
            "TEMP: $tempText | DOZE: $dozeText | $privText"
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
            .setContentText(if (isThermalCapped) "Adaptive FPS Guard: 30 FPS (Thermal Saver)" else capText)
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    override fun onDestroy() {
        _isBoostActive.value = false
        telemetryJob?.cancel()
        watchdogJob?.cancel()
        serviceScope.cancel()
        super.onDestroy()
    }
}
