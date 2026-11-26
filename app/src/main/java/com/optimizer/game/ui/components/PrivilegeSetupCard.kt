package com.optimizer.game.ui.components

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.optimizer.game.adb.AdbMdnsDiscovery
import com.optimizer.game.adb.AdbPairingReceiver
import com.optimizer.game.optimizer.GameOptimizerEngine
import com.optimizer.game.shizuku.ShizukuManager
import com.optimizer.game.ui.theme.AccentPurple
import com.optimizer.game.ui.theme.BoneWhite
import com.optimizer.game.ui.theme.HairlineBorder
import com.optimizer.game.ui.theme.ObsidianSurface
import com.optimizer.game.ui.theme.SignalRed
import com.optimizer.game.ui.theme.ZincMuted
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

private var activePairingDiscoveryJob: Job? = null
private var activePairingDiscovery: AdbMdnsDiscovery? = null

fun stopActivePairingDiscovery() {
    activePairingDiscoveryJob?.cancel()
    activePairingDiscoveryJob = null
    activePairingDiscovery?.stopDiscovery()
    activePairingDiscovery = null
}

@Composable
fun PrivilegeSetupCard(
    modifier: Modifier = Modifier,
    onPairAdb: () -> Unit,
    onConnectShizuku: () -> Unit
) {
    val context = LocalContext.current

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(4.dp))
            .background(ObsidianSurface)
            .border(1.dp, HairlineBorder, RoundedCornerShape(4.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "PRIVILEGE SETUP REQUIRED",
                fontFamily = FontFamily.Monospace,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp,
                color = AccentPurple
            )
            Text(
                text = "UID 2000",
                fontFamily = FontFamily.Monospace,
                fontSize = 9.sp,
                fontWeight = FontWeight.SemiBold,
                color = ZincMuted
            )
        }

        Text(
            text = "FrameGuard requires system shell privileges to lock frame pacing and manage OS-level display sync. Pair via built-in Wireless ADB or connect to an active Shizuku service.",
            fontFamily = FontFamily.SansSerif,
            fontSize = 11.5.sp,
            color = ZincMuted,
            lineHeight = 16.sp
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Button 1: PAIR WIRELESS ADB
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(4.dp))
                    .background(ObsidianSurface)
                    .border(1.dp, AccentPurple, RoundedCornerShape(4.dp))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) {
                        onPairAdb()
                    }
                    .padding(vertical = 10.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "[ PAIR WIRELESS ADB ]",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.5.sp,
                    color = AccentPurple
                )
            }

            // Button 2: CONNECT SHIZUKU
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(4.dp))
                    .background(ObsidianSurface)
                    .border(1.dp, HairlineBorder, RoundedCornerShape(4.dp))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) {
                        onConnectShizuku()
                    }
                    .padding(vertical = 10.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "[ CONNECT SHIZUKU ]",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 0.5.sp,
                    color = BoneWhite
                )
            }
        }
    }
}

fun triggerWirelessAdbPairing(context: Context) {
    val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
    val network = cm?.activeNetwork
    val capabilities = cm?.getNetworkCapabilities(network)
    val isWifi = capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true

    if (!isWifi) {
        CoroutineScope(Dispatchers.IO).launch {
            GameOptimizerEngine.log("[ERR] Wi-Fi required for loopback Wireless ADB. Connect to Wi-Fi and enable Wireless Debugging.", GameOptimizerEngine.LogType.ERROR)
        }
    }

    // Launch live mDNS discovery and pairing notification
    stopActivePairingDiscovery()
    val appContext = context.applicationContext
    AdbPairingReceiver.showPairingNotification(appContext, null)

    val discovery = AdbMdnsDiscovery(appContext)
    activePairingDiscovery = discovery
    discovery.startDiscovery()
    activePairingDiscoveryJob = CoroutineScope(Dispatchers.IO).launch {
        try {
            withTimeoutOrNull(120_000L) {
                discovery.pairingPort.collectLatest { port ->
                    if (port != null && port > 0) {
                        AdbPairingReceiver.showPairingNotification(appContext, port)
                    }
                }
            }
        } finally {
            discovery.stopDiscovery()
            if (activePairingDiscovery === discovery) {
                activePairingDiscovery = null
            }
        }
    }

    // Open Developer Settings
    try {
        val intent = Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        context.startActivity(intent)
    } catch (_: Exception) {
        val fallbackIntent = Intent(Settings.ACTION_SETTINGS).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        context.startActivity(fallbackIntent)
    }
}
