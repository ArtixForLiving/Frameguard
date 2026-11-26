package com.optimizer.game.adb

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Socket

object AdbPortScanner {

    fun isPortListening(port: Int, timeoutMs: Int = 25): Boolean {
        return try {
            Socket().use { s ->
                s.connect(InetSocketAddress("127.0.0.1", port), timeoutMs)
                true
            }
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Checks cached port or scans ephemeral ports (32768..55000) over 127.0.0.1,
     * verifying authenticated ADB TLS connectivity before persisting any port.
     */
    suspend fun findOpenPort(context: Context): Int? = withContext(Dispatchers.IO) {
        val daemonPort = NativeDaemonManager.currentPort

        // 1. Check cached port first, verifying ADB TLS handshake
        AdbPortStore.getPort(context)?.let { cached ->
            if (cached != daemonPort && isPortListening(cached, 50) && AdbTlsClient.canConnect(context, cached)) {
                return@withContext cached
            }
        }

        // 2. Parallel scan ephemeral range in chunks of 200, verifying ADB TLS before persisting
        val candidatePorts = (32768..55000).toList()
        for (chunk in candidatePorts.chunked(200)) {
            val deferreds = chunk.map { port ->
                async {
                    if (port != daemonPort && isPortListening(port, 25)) port else null
                }
            }
            val openPorts = deferreds.awaitAll().filterNotNull()
            for (candidate in openPorts) {
                if (AdbTlsClient.canConnect(context, candidate)) {
                    AdbPortStore.savePort(context, candidate)
                    return@withContext candidate
                }
            }
        }
        null
    }
}
