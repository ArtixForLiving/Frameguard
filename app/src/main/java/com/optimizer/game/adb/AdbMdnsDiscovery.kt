package com.optimizer.game.adb

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.net.InetAddress
import java.net.NetworkInterface

class AdbMdnsDiscovery(private val context: Context) {

    private val nsdManager = context.getSystemService(Context.NSD_SERVICE) as? NsdManager

    private val _pairingPort = MutableStateFlow<Int?>(null)
    val pairingPort: StateFlow<Int?> = _pairingPort.asStateFlow()

    private val _connectPort = MutableStateFlow<Int?>(null)
    val connectPort: StateFlow<Int?> = _connectPort.asStateFlow()

    private var pairingDiscoveryListener: NsdManager.DiscoveryListener? = null
    private var connectDiscoveryListener: NsdManager.DiscoveryListener? = null

    private val resolveLock = Any()
    private var isResolving = false
    private val pendingResolves = ArrayDeque<Pair<NsdServiceInfo, (NsdServiceInfo) -> Unit>>()

    companion object {
        const val SERVICE_TYPE_PAIRING = "_adb-tls-pairing._tcp"
        const val SERVICE_TYPE_CONNECT = "_adb-tls-connect._tcp"

        internal fun isLoopbackHost(addresses: List<InetAddress>): Boolean =
            addresses.any { it.isLoopbackAddress }

        internal fun isLocalDeviceHost(
            addresses: List<InetAddress>,
            localAddresses: Set<InetAddress> = collectLocalAddresses()
        ): Boolean {
            if (isLoopbackHost(addresses)) return true
            if (addresses.isEmpty() || localAddresses.isEmpty()) return false
            val localHostStrings = localAddresses.mapNotNull { it.hostAddress?.substringBefore('%') }.toSet()
            return addresses.any { addr ->
                addr in localAddresses ||
                    addr.hostAddress?.substringBefore('%') in localHostStrings
            }
        }

        internal fun collectLocalAddresses(): Set<InetAddress> {
            return try {
                val result = mutableSetOf<InetAddress>()
                val interfaces = NetworkInterface.getNetworkInterfaces() ?: return emptySet()
                while (interfaces.hasMoreElements()) {
                    val iface = interfaces.nextElement()
                    val addrs = iface.inetAddresses
                    while (addrs.hasMoreElements()) {
                        result.add(addrs.nextElement())
                    }
                }
                result
            } catch (_: Exception) {
                emptySet()
            }
        }
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
        synchronized(resolveLock) {
            if (isResolving) {
                pendingResolves.addLast(serviceInfo to onResolved)
                return
            }
            isResolving = true
        }
        executeResolve(manager, serviceInfo, onResolved)
    }

    private fun executeResolve(
        manager: NsdManager,
        serviceInfo: NsdServiceInfo,
        onResolved: (NsdServiceInfo) -> Unit
    ) {
        try {
            @Suppress("DEPRECATION")
            manager.resolveService(
                serviceInfo,
                object : NsdManager.ResolveListener {
                    override fun onResolveFailed(serviceInfo: NsdServiceInfo?, errorCode: Int) {
                        android.util.Log.w("AdbMdns", "mDNS resolve failed: ${serviceInfo?.serviceName} (code: $errorCode)")
                        drainNextResolve(manager)
                    }

                    override fun onServiceResolved(resolvedInfo: NsdServiceInfo?) {
                        try {
                            if (resolvedInfo != null && resolvedInfo.port > 0) {
                                @Suppress("DEPRECATION")
                                val addresses = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                    resolvedInfo.hostAddresses.ifEmpty { listOfNotNull(resolvedInfo.host) }
                                } else {
                                    listOfNotNull(resolvedInfo.host)
                                }
                                if (isLocalDeviceHost(addresses)) {
                                    android.util.Log.i("AdbMdns", "mDNS resolved local host: ${resolvedInfo.serviceName} on port ${resolvedInfo.port}")
                                    onResolved(resolvedInfo)
                                } else {
                                    android.util.Log.w("AdbMdns", "Rejected non-local mDNS service: ${resolvedInfo.serviceName}")
                                }
                            }
                        } finally {
                            drainNextResolve(manager)
                        }
                    }
                }
            )
        } catch (_: Exception) {
            drainNextResolve(manager)
        }
    }

    private fun drainNextResolve(manager: NsdManager) {
        val next = synchronized(resolveLock) {
            val item = pendingResolves.removeFirstOrNull()
            if (item == null) {
                isResolving = false
            }
            item
        }
        if (next != null) {
            executeResolve(manager, next.first, next.second)
        }
    }

    fun stopDiscovery() {
        synchronized(resolveLock) {
            pendingResolves.clear()
            isResolving = false
        }
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
