package com.optimizer.game.adb

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class AdbMdnsDiscovery(private val context: Context) {

    private val nsdManager = context.getSystemService(Context.NSD_SERVICE) as? NsdManager

    private val _pairingPort = MutableStateFlow<Int?>(null)
    val pairingPort: StateFlow<Int?> = _pairingPort.asStateFlow()

    private val _connectPort = MutableStateFlow<Int?>(null)
    val connectPort: StateFlow<Int?> = _connectPort.asStateFlow()

    private var pairingDiscoveryListener: NsdManager.DiscoveryListener? = null
    private var connectDiscoveryListener: NsdManager.DiscoveryListener? = null

    companion object {
        const val SERVICE_TYPE_PAIRING = "_adb-tls-pairing._tcp"
        const val SERVICE_TYPE_CONNECT = "_adb-tls-connect._tcp"
    }

    fun startDiscovery() {
        val manager = nsdManager ?: return
        stopDiscovery()

        // Discover pairing service
        pairingDiscoveryListener = createDiscoveryListener(SERVICE_TYPE_PAIRING) { info ->
            _pairingPort.value = info.port
        }
        try {
            manager.discoverServices(
                SERVICE_TYPE_PAIRING,
                NsdManager.PROTOCOL_DNS_SD,
                pairingDiscoveryListener
            )
        } catch (_: Exception) {}

        // Discover connect service
        connectDiscoveryListener = createDiscoveryListener(SERVICE_TYPE_CONNECT) { info ->
            _connectPort.value = info.port
        }
        try {
            manager.discoverServices(
                SERVICE_TYPE_CONNECT,
                NsdManager.PROTOCOL_DNS_SD,
                connectDiscoveryListener
            )
        } catch (_: Exception) {}
    }

    private fun createDiscoveryListener(
        serviceType: String,
        onResolved: (NsdServiceInfo) -> Unit
    ): NsdManager.DiscoveryListener {
        return object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(regType: String) {}

            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                resolveServiceSafely(serviceInfo, onResolved)
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                if (serviceType == SERVICE_TYPE_PAIRING) {
                    _pairingPort.value = null
                } else if (serviceType == SERVICE_TYPE_CONNECT) {
                    _connectPort.value = null
                }
            }

            override fun onDiscoveryStopped(serviceType: String) {}

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                try { nsdManager?.stopServiceDiscovery(this) } catch (_: Exception) {}
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                try { nsdManager?.stopServiceDiscovery(this) } catch (_: Exception) {}
            }
        }
    }

    private fun resolveServiceSafely(
        serviceInfo: NsdServiceInfo,
        onResolved: (NsdServiceInfo) -> Unit
    ) {
        val manager = nsdManager ?: return
        try {
            @Suppress("DEPRECATION")
            manager.resolveService(
                serviceInfo,
                object : NsdManager.ResolveListener {
                    override fun onResolveFailed(serviceInfo: NsdServiceInfo?, errorCode: Int) {
                        android.util.Log.w("AdbMdns", "mDNS resolve failed: ${serviceInfo?.serviceName} (code: $errorCode)")
                    }

                    override fun onServiceResolved(resolvedInfo: NsdServiceInfo?) {
                        if (resolvedInfo != null && resolvedInfo.port > 0) {
                            android.util.Log.i("AdbMdns", "mDNS resolved: ${resolvedInfo.serviceName} on port ${resolvedInfo.port}")
                            onResolved(resolvedInfo)
                        }
                    }
                }
            )
        } catch (_: Exception) {}
    }

    fun stopDiscovery() {
        val manager = nsdManager ?: return
        pairingDiscoveryListener?.let {
            try { manager.stopServiceDiscovery(it) } catch (_: Exception) {}
            pairingDiscoveryListener = null
        }
        connectDiscoveryListener?.let {
            try { manager.stopServiceDiscovery(it) } catch (_: Exception) {}
            connectDiscoveryListener = null
        }
    }
}
