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
     * Rapidly checks cached port or scans standard ephemeral ports (32768..55000)
     * over 127.0.0.1 in parallel chunks to find the active ADB daemon port.
     */
    suspend fun findOpenPort(context: Context): Int? = withContext(Dispatchers.IO) {
        // 1. Check cached port first
        AdbPortStore.getPort(context)?.let { cached ->
            if (isPortListening(cached, 50)) {
                return@withContext cached
            }
        }

        // 2. Parallel scan ephemeral range in chunks of 200
        val candidatePorts = (32768..55000).toList()
        for (chunk in candidatePorts.chunked(200)) {
            val deferreds = chunk.map { port ->
                async {
                    if (isPortListening(port, 25)) port else null
                }
            }
            val openPorts = deferreds.awaitAll().filterNotNull()
            val valid = openPorts.firstOrNull { it != DaemonServer.DAEMON_PORT }
            if (valid != null) {
                AdbPortStore.savePort(context, valid)
                return@withContext valid
            }
        }
        null
    }
}
