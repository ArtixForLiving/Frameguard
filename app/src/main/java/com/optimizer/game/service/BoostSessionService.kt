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
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.optimizer.game.MainActivity
import com.optimizer.game.R
import com.optimizer.game.optimizer.GameOptimizerEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class BoostSessionService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    companion object {
        const val ACTION_START = "com.optimizer.game.action.START_BOOST"
        const val ACTION_STOP = "com.optimizer.game.action.STOP_BOOST"

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
                val pkg = intent.getStringExtra(EXTRA_PACKAGE_NAME) ?: GameOptimizerEngine.DEFAULT_PACKAGE
                val fps = intent.getIntExtra(EXTRA_TARGET_FPS, 90)
                val doze = intent.getBooleanExtra(EXTRA_DOZE_ENABLED, true)

                val notification = buildNotification(pkg, fps, doze)
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
                    GameOptimizerEngine.applyProfile(pkg, fps, doze, applicationContext)
                }
            }

            ACTION_STOP -> {
                serviceScope.launch {
                    try {
                        GameOptimizerEngine.restoreDefaults()
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

    private fun buildNotification(packageName: String, fps: Int, doze: Boolean): Notification {
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, BoostSessionService::class.java).apply {
                action = ACTION_STOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val dozeStatus = if (doze) "Doze active" else "Doze off"

        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Profile Engaged — Cap $fps FPS / 60Hz")
            .setContentText("$packageName · $dozeStatus")
            .setSubText("FRAMEGUARD")
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(
                0,
                "STOP & RESTORE",
                stopIntent
            )
            .build()
    }

    override fun onDestroy() {
        _isBoostActive.value = false
        serviceScope.cancel()
        super.onDestroy()
    }
}
