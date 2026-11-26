package com.optimizer.game.adb

import android.content.Context
import com.optimizer.game.privilege.ShellResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.UUID

object NativeDaemonManager {

    private const val TOKEN_FILE = "daemon_auth_token"
    private var cachedToken: String? = null
    private val bootstrapMutex = Mutex()

    @Volatile
    var currentPort: Int? = null

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
     * Checks if the daemon process is running on currentPort and passes mutual auth + PING.
     */
    suspend fun isDaemonAlive(context: Context): Boolean = withContext(Dispatchers.IO) {
        val port = currentPort ?: return@withContext false
        val token = getOrCreateAuthToken(context)
        val result = sendFrameToPort(port, token, timeoutMs = 2_000) { out ->
            out.writeByte(DaemonProtocol.FRAME_PING.toInt())
            out.flush()
        }
        if (result.isOk && result.stdout == "PONG") {
            true
        } else {
            currentPort = null
            false
        }
    }

    /**
     * Bootstraps DaemonServer via ADB TLS shell if it is not already running.
     * Serialized via bootstrapMutex so concurrent callers never spawn duplicate daemons.
     */
    suspend fun ensureDaemonStarted(context: Context, connectPort: Int): Boolean =
        ensureDaemonStartedInternal(
            checkAlive = { isDaemonAlive(context) },
            readPort = { readDaemonPortOverAdb(context, connectPort) },
            launchDaemon = {
                val token = getOrCreateAuthToken(context)
                val apkPath = context.packageCodePath
                // Feed token via stdin so /proc/<pid>/cmdline never exposes it; setsid detaches from adbd PTY.
                val bootstrapCmd = "rm -f ${DaemonServer.PORT_FILE_PATH}; " +
                    "IFS= read -r FG_TOK; " +
                    "(printf '%s\\n' \"\$FG_TOK\" | setsid app_process -Djava.class.path='$apkPath' " +
                    "/system/bin com.optimizer.game.adb.DaemonServer >/dev/null 2>&1 &)"

                AdbTlsClient.execDetached(
                    context = context,
                    port = connectPort,
                    command = bootstrapCmd,
                    stdinPayload = "$token\n"
                )
            }
        )

    internal suspend fun ensureDaemonStartedInternal(
        checkAlive: suspend () -> Boolean,
        readPort: suspend () -> Int?,
        launchDaemon: suspend () -> ShellResult,
        pollDelayMs: Long = 600L
    ): Boolean = withContext(Dispatchers.IO) {
        if (checkAlive()) return@withContext true

        bootstrapMutex.withLock {
            if (checkAlive()) return@withContext true

            // Reuse an already-running daemon if its ephemeral port file is valid and passes mutual auth.
            val existingPort = readPort()
            if (existingPort != null) {
                currentPort = existingPort
                if (checkAlive()) return@withContext true
            }

            val launchResult = launchDaemon()
            if (launchResult.transportError != null) {
                return@withContext false
            }

            for (i in 0 until 4) {
                delay(pollDelayMs)
                val discoveredPort = readPort()
                if (discoveredPort != null) {
                    currentPort = discoveredPort
                    if (checkAlive()) {
                        return@withContext true
                    }
                }
            }
            false
        }
    }

    private suspend fun readDaemonPortOverAdb(context: Context, connectPort: Int): Int? {
        val res = AdbTlsClient.executeCommand(context, connectPort, "cat ${DaemonServer.PORT_FILE_PATH}")
        if (!res.isOk) return null
        val parsed = res.stdout.trim().toIntOrNull() ?: return null
        return if (parsed in 1024..65535) parsed else null
    }

    /**
     * Executes a shell command via the authenticated ephemeral TCP loopback socket.
     */
    suspend fun exec(context: Context, command: String): ShellResult = withContext(Dispatchers.IO) {
        val port = currentPort ?: return@withContext ShellResult(
            exitCode = -1,
            stdout = "",
            stderr = "",
            transportError = "Local daemon port not available"
        )
        val token = getOrCreateAuthToken(context)
        val timeoutMs = if (command.contains("package compile")) 120_000 else 15_000
        val result = sendFrameToPort(port, token, timeoutMs) { out ->
            out.writeByte(DaemonProtocol.FRAME_EXEC.toInt())
            DaemonProtocol.writeStringFrame(out, command)
            out.flush()
        }
        if (result.transportError != null) {
            currentPort = null
        }
        result
    }

    /**
     * Executes raw argument vector via the authenticated ephemeral TCP loopback socket.
     */
    suspend fun execArgs(context: Context, args: List<String>): ShellResult = withContext(Dispatchers.IO) {
        if (args.isEmpty()) {
            return@withContext ShellResult(exitCode = 2, stdout = "", stderr = "Empty argument list")
        }
        val port = currentPort ?: return@withContext ShellResult(
            exitCode = -1,
            stdout = "",
            stderr = "",
            transportError = "Local daemon port not available"
        )
        val token = getOrCreateAuthToken(context)
        val timeoutMs = if (args.any { it.contains("compile") }) 120_000 else 15_000
        val result = sendFrameToPort(port, token, timeoutMs) { out ->
            out.writeByte(DaemonProtocol.FRAME_EXEC_ARGS.toInt())
            out.writeInt(args.size)
            for (arg in args) {
                DaemonProtocol.writeStringFrame(out, arg)
            }
            out.flush()
        }
        if (result.transportError != null) {
            currentPort = null
        }
        result
    }

    private fun sendFrameToPort(
        port: Int,
        token: String,
        timeoutMs: Int,
        writeRequest: (DataOutputStream) -> Unit
    ): ShellResult {
        var socket: Socket? = null
        return try {
            socket = Socket()
            socket.connect(InetSocketAddress("127.0.0.1", port), 2_000)
            socket.soTimeout = timeoutMs

            val input = DataInputStream(BufferedInputStream(socket.getInputStream()))
            val out = DataOutputStream(BufferedOutputStream(socket.getOutputStream()))

            if (!DaemonProtocol.authenticateClient(input, out, token)) {
                return ShellResult(
                    exitCode = -1,
                    stdout = "",
                    stderr = "",
                    transportError = "Local daemon mutual authentication failed"
                )
            }

            writeRequest(out)
            DaemonProtocol.readResultFrame(input)
        } catch (_: SocketTimeoutException) {
            ShellResult(exitCode = -1, stdout = "", stderr = "", timedOut = true)
        } catch (e: Exception) {
            ShellResult(
                exitCode = -1,
                stdout = "",
                stderr = "",
                transportError = e.message ?: "Local daemon transport error"
            )
        } finally {
            try { socket?.close() } catch (_: Exception) {}
        }
    }
}
