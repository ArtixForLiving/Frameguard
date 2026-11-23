package com.optimizer.game.adb

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.RemoteInput
import com.optimizer.game.R
import com.optimizer.game.optimizer.GameOptimizerEngine
import com.optimizer.game.privilege.PrivilegeManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class AdbPairingReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_PAIR_CODE = "com.optimizer.game.action.PAIR_CODE"
        const val KEY_PAIRING_CODE = "key_pairing_code"
        const val CHANNEL_ID = "frameguard_pairing_channel"
        const val NOTIFICATION_ID = 2002

        fun showPairingNotification(context: Context, pairingPort: Int?) {
            val manager = context.getSystemService(NotificationManager::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    "FrameGuard Pairing Service",
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = "Interactive Wireless ADB Pairing Notification"
                }
                manager?.createNotificationChannel(channel)
            }

            val remoteInput = RemoteInput.Builder(KEY_PAIRING_CODE)
                .setLabel("Enter 6-digit pairing code")
                .build()

            val replyIntent = Intent(context, AdbPairingReceiver::class.java).apply {
                action = ACTION_PAIR_CODE
                if (pairingPort != null) putExtra("pairing_port", pairingPort)
            }

            // CRITICAL: FLAG_MUTABLE is mandatory for RemoteInput on Android 12-16
            val replyPendingIntent = PendingIntent.getBroadcast(
                context,
                0,
                replyIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
            )

            val action = NotificationCompat.Action.Builder(
                R.drawable.ic_notification,
                "ENTER CODE",
                replyPendingIntent
            ).addRemoteInput(remoteInput).build()

            val subText = if (pairingPort != null) "PORT: $pairingPort" else "DISCOVERING PORT..."

            val notification = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle("Wireless Debugging Pairing")
                .setContentText("Enter the 6-digit code shown in Developer Options")
                .setSubText(subText)
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .addAction(action)
                .build()

            manager?.notify(NOTIFICATION_ID, notification)
        }
    }

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != ACTION_PAIR_CODE) return

        val bundle = RemoteInput.getResultsFromIntent(intent)
        val code = bundle?.getCharSequence(KEY_PAIRING_CODE)?.toString()?.trim()
        val port = intent.getIntExtra("pairing_port", -1)

        val manager = context.getSystemService(NotificationManager::class.java)

        if (code.isNullOrEmpty()) return

        CoroutineScope(Dispatchers.IO).launch {
            // Support both plain "678036" and explicit "<port> <code>" (e.g. "45065 678036")
            var explicitPort: Int? = null
            var cleanCode = code
            val parts = code.split(Regex("[\\s:]+"))
            if (parts.size >= 2) {
                val parsedPort = parts[0].toIntOrNull()
                if (parsedPort != null && parsedPort in 1024..65535) {
                    explicitPort = parsedPort
                    cleanCode = parts[1]
                }
            }

            GameOptimizerEngine.log("Initiating SPAKE2 TLS handshake with code: $cleanCode", GameOptimizerEngine.LogType.COMMAND)

            val targetPort = explicitPort ?: if (port > 0) port else {
                val discovery = AdbMdnsDiscovery(context)
                discovery.startDiscovery()
                var resolved: Int? = null
                for (i in 0 until 15) {
                    resolved = discovery.pairingPort.value
                    if (resolved != null) break
                    kotlinx.coroutines.delay(200)
                }
                discovery.stopDiscovery()
                resolved ?: -1
            }

            if (targetPort <= 0) {
                GameOptimizerEngine.log("[ERR] Pairing port not discovered. Ensure Wireless Debugging is active on Wi-Fi.", GameOptimizerEngine.LogType.ERROR)
                val failNotif = NotificationCompat.Builder(context, CHANNEL_ID)
                    .setSmallIcon(R.drawable.ic_notification)
                    .setContentTitle("Pairing Failed")
                    .setContentText("Pairing port not discovered. Input '<port> <code>' to specify manually.")
                    .setAutoCancel(true)
                    .build()
                manager?.notify(NOTIFICATION_ID, failNotif)
                return@launch
            }

            val success = AdbPairingClient.pair(context, targetPort, cleanCode)
            if (success) {
                GameOptimizerEngine.log("Wireless ADB paired successfully (RSA public key accepted)", GameOptimizerEngine.LogType.STDOUT)
                val successNotif = NotificationCompat.Builder(context, CHANNEL_ID)
                    .setSmallIcon(R.drawable.ic_notification)
                    .setContentTitle("Pairing Successful")
                    .setContentText("FrameGuard authorized under UID 2000")
                    .setAutoCancel(true)
                    .build()
                manager?.notify(NOTIFICATION_ID, successNotif)

                // Bootstrap daemon right after pairing
                val discovery = AdbMdnsDiscovery(context)
                discovery.startDiscovery()
                var connectPort: Int? = null
                for (i in 0 until 15) {
                    connectPort = discovery.connectPort.value
                    if (connectPort != null) break
                    kotlinx.coroutines.delay(200)
                }
                discovery.stopDiscovery()

                if (connectPort == null) {
                    connectPort = AdbPortScanner.findOpenPort(context)
                }

                if (connectPort != null) {
                    GameOptimizerEngine.log("Starting UID 2000 daemon via port $connectPort...", GameOptimizerEngine.LogType.COMMAND)
                    NativeDaemonManager.ensureDaemonStarted(context, connectPort)
                }

                PrivilegeManager.init(context)
                PrivilegeManager.refreshState()
            } else {
                GameOptimizerEngine.log("[ERR] SPAKE2 handshake failed: expired code or TLS rejection.", GameOptimizerEngine.LogType.ERROR)
                val failNotif = NotificationCompat.Builder(context, CHANNEL_ID)
                    .setSmallIcon(R.drawable.ic_notification)
                    .setContentTitle("Pairing Handshake Failed")
                    .setContentText("Incorrect code or expired pairing dialog")
                    .setAutoCancel(true)
                    .build()
                manager?.notify(NOTIFICATION_ID, failNotif)
            }
        }
    }
}
