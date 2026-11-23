package com.optimizer.game.adb

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.InetSocketAddress
import java.net.Socket
import java.util.UUID

object NativeDaemonManager {

    private const val TOKEN_FILE = "daemon_auth_token"
    private var cachedToken: String? = null

    @Synchronized
    fun getOrCreateAuthToken(context: Context): String {
        cachedToken?.let { return it }
        val file = File(context.filesDir, TOKEN_FILE)
        if (file.exists()) {
            val token = file.readText().trim()
            if (token.isNotEmpty()) {
                cachedToken = token
                return token
            }
        }
        val newToken = UUID.randomUUID().toString()
        file.writeText(newToken)
        cachedToken = newToken
        return newToken
    }

    /**
     * Checks if the daemon process is running and responding over TCP loopback.
     */
    suspend fun isDaemonAlive(context: Context): Boolean = withContext(Dispatchers.IO) {
        val token = getOrCreateAuthToken(context)
        var socket: Socket? = null
        try {
            socket = Socket()
            socket.connect(InetSocketAddress("127.0.0.1", DaemonServer.DAEMON_PORT), 1500)
            socket.soTimeout = 2000

            val writer = PrintWriter(socket.getOutputStream(), true)
            val reader = BufferedReader(InputStreamReader(socket.getInputStream()))

            writer.println(token)
            val authResp = reader.readLine()
            if (authResp != "OK: AUTHENTICATED") {
                return@withContext false
            }

            writer.println("PING")
            val pingResp = reader.readLine()
            pingResp == "PONG"
        } catch (_: Exception) {
            false
        } finally {
            try { socket?.close() } catch (_: Exception) {}
        }
    }

    /**
     * Bootstraps DaemonServer via ADB TLS shell if it is not already running.
     */
    suspend fun ensureDaemonStarted(context: Context, connectPort: Int): Boolean = withContext(Dispatchers.IO) {
        if (isDaemonAlive(context)) return@withContext true

        val token = getOrCreateAuthToken(context)
        val apkPath = context.packageCodePath
        val bootstrapCmd = "nohup app_process -Djava.class.path=$apkPath /system/bin com.optimizer.game.adb.DaemonServer $token > /dev/null 2>&1 &"

        AdbTlsClient.executeCommand(context, connectPort, bootstrapCmd)

        // Wait up to 3 seconds for ServerSocket to become ready
        for (i in 0 until 6) {
            delay(500)
            if (isDaemonAlive(context)) {
                return@withContext true
            }
        }
        false
    }

    /**
     * Executes a shell command via the authenticated TCP loopback socket connection.
     */
    suspend fun exec(context: Context, command: String): String = withContext(Dispatchers.IO) {
        val token = getOrCreateAuthToken(context)
        var socket: Socket? = null
        try {
            socket = Socket()
            socket.connect(InetSocketAddress("127.0.0.1", DaemonServer.DAEMON_PORT), 2000)
            socket.soTimeout = 15000

            val writer = PrintWriter(socket.getOutputStream(), true)
            val reader = BufferedReader(InputStreamReader(socket.getInputStream()))

            writer.println(token)
            val authResp = reader.readLine()
            if (authResp != "OK: AUTHENTICATED") {
                return@withContext "ERROR: Local daemon authentication failed: $authResp"
            }

            writer.println(command)
            val output = StringBuilder()
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                if (line == DaemonServer.EOF_MARKER) break
                output.append(line).append("\n")
            }
            output.toString().trimEnd()
        } catch (e: Exception) {
            "ERROR: ${e.message}"
        } finally {
            try { socket?.close() } catch (_: Exception) {}
        }
    }
}
