package com.optimizer.game.adb

import android.content.Context
import com.optimizer.game.optimizer.GameOptimizerEngine
import io.github.muntashirakon.adb.AdbConnection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object AdbTlsClient {

    /**
     * Connects to Wireless ADB daemon on 127.0.0.1:<port> using authenticated TLS/TCP
     * via libadb-android and executes a shell command.
     */
    suspend fun executeCommand(context: Context, port: Int, command: String): String = withContext(Dispatchers.IO) {
        var connection: AdbConnection? = null
        try {
            val keyPair = AdbKeyManager.getKeyPair(context)
            val cert = AdbKeyManager.getCertificate(context)

            GameOptimizerEngine.log("Connecting ADB to 127.0.0.1:$port...", GameOptimizerEngine.LogType.COMMAND)
            connection = AdbConnection.create("127.0.0.1", port, keyPair.private, cert)
            connection.connect()
            AdbPortStore.savePort(context, port)

            val stream = connection.open("shell:$command\n")
            if (command.contains("&") || command.startsWith("nohup")) {
                kotlinx.coroutines.delay(400)
                try { stream.close() } catch (_: Exception) {}
                return@withContext "OK"
            }

            val output = StringBuilder()
            val buffer = ByteArray(2048)
            var bytesRead: Int
            val start = System.currentTimeMillis()
            while (System.currentTimeMillis() - start < 5000) {
                if (stream.available() > 0) {
                    bytesRead = stream.read(buffer, 0, buffer.size)
                    if (bytesRead > 0) {
                        output.append(String(buffer, 0, bytesRead, Charsets.UTF_8))
                    }
                } else if (output.isNotEmpty()) {
                    break
                }
                kotlinx.coroutines.delay(100)
            }
            try { stream.close() } catch (_: Exception) {}
            output.toString().trimEnd()
        } catch (e: Exception) {
            GameOptimizerEngine.log("[ERR] AdbConnection executeCommand failed: ${e.message}", GameOptimizerEngine.LogType.ERROR)
            "ERROR: ${e.message}"
        } finally {
            try { connection?.close() } catch (_: Exception) {}
        }
    }
}
