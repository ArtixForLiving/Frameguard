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

class BoostSessionService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var telemetryJob: Job? = null

    private var currentPkg: String = GameOptimizerEngine.DEFAULT_PACKAGE
    private var currentTargetFps: Int = 60
    private var currentDoze: Boolean = true
    private var cachedTemp: Float? = null

    companion object {
        const val ACTION_START = "com.optimizer.game.action.START_BOOST"
        const val ACTION_STOP = "com.optimizer.game.action.STOP_BOOST"
        const val ACTION_SWITCH_FPS = "com.optimizer.game.action.SWITCH_FPS"

        const val EXTRA_PACKAGE_NAME = "extra_package_name"
        const val EXTRA_TARGET_FPS = "extra_target_fps"
        const val EXTRA_DOZE_ENABLED = "extra_doze_enabled"

        private const val NOTIFICATION_CHANNEL_ID = "frameguard_boost_channel"
        private const val NOTIFICATION_ID = 1001

        private val _isBoostActive = MutableStateFlow(false)
        val isBoostActive: StateFlow<Boolean> = _isBoostActive.asStateFlow()

        fun start(context: Context, packageName: String, targetFps: Int, dozeEnabled: Boolean) {
            val intent = Intent(context, BoostSessionService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_PACKAGE_NAME, packageName)
                putExtra(EXTRA_TARGET_FPS, targetFps)
                putExtra(EXTRA_DOZE_ENABLED, dozeEnabled)
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
                    GameOptimizerEngine.applyProfile(currentPkg, currentTargetFps, currentDoze, applicationContext)
                    updateNotification()
                }

                startTelemetryPoller()
            }

            ACTION_SWITCH_FPS -> {
                val newFps = intent.getIntExtra(EXTRA_TARGET_FPS, 30)
                currentTargetFps = newFps
                serviceScope.launch {
                    GameOptimizerEngine.applyProfile(currentPkg, currentTargetFps, currentDoze, applicationContext)
                    updateNotification()
                }
            }

            ACTION_STOP -> {
                telemetryJob?.cancel()
                serviceScope.launch {
                    try {
                        GameOptimizerEngine.restoreDefaults(currentPkg)
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

    private fun startTelemetryPoller() {
        telemetryJob?.cancel()
        telemetryJob = serviceScope.launch {
            while (isActive && _isBoostActive.value) {
                delay(3500)
                try {
                    val temp = GameOptimizerEngine.queryThermalReading()
                    if (temp != null) {
                        cachedTemp = temp
                        updateNotification()
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
        remoteViews.setTextViewText(R.id.notification_cap_badge, "CAP $currentTargetFps • $pairedHz")

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
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    override fun onDestroy() {
        _isBoostActive.value = false
        telemetryJob?.cancel()
        serviceScope.cancel()
        super.onDestroy()
    }
}
