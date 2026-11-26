package com.optimizer.game.adb

import android.content.Context
import com.optimizer.game.optimizer.GameOptimizerEngine
import com.optimizer.game.privilege.ShellResult
import io.github.muntashirakon.adb.AdbConnection
import io.github.muntashirakon.adb.AdbStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit

object AdbTlsClient {

    const val DEFAULT_TIMEOUT_MS = 15_000L
    const val LONG_TIMEOUT_MS = 120_000L
    private const val CONNECT_VERIFY_TIMEOUT_MS = 1_500L

    /**
     * Verifies that 127.0.0.1:<port> accepts an authenticated ADB TLS connection.
     */
    suspend fun canConnect(
        context: Context,
        port: Int,
        timeoutMs: Long = CONNECT_VERIFY_TIMEOUT_MS
    ): Boolean = withContext(Dispatchers.IO) {
        var connection: AdbConnection? = null
        try {
            val keyPair = AdbKeyManager.getKeyPair(context)
            val cert = AdbKeyManager.getCertificate(context)
            connection = AdbConnection.create("127.0.0.1", port, keyPair.private, cert)
            connection.connect(timeoutMs, TimeUnit.MILLISECONDS, true)
        } catch (_: Exception) {
            false
        } finally {
            try { connection?.close() } catch (_: Exception) {}
        }
    }

    /**
     * Connects to Wireless ADB daemon on 127.0.0.1:<port> using authenticated TLS/TCP
     * via libadb-android and executes a shell command with a per-call nonce exit sentinel.
     */
    suspend fun executeCommand(
        context: Context,
        port: Int,
        command: String,
        timeoutMs: Long = if (command.contains("package compile")) LONG_TIMEOUT_MS else DEFAULT_TIMEOUT_MS
    ): ShellResult = withContext(Dispatchers.IO) {
        val trimmed = command.trim()
        if (trimmed.isEmpty()) {
            return@withContext ShellResult(exitCode = 2, stdout = "", stderr = "Empty command")
        }

        val nonce = UUID.randomUUID().toString().replace("-", "")
        val wrappedCommand = "$trimmed; printf '\\n__FG_EXIT_${nonce}__:%d\\n' \$?"

        var connection: AdbConnection? = null
        var stream: AdbStream? = null
        try {
            val keyPair = AdbKeyManager.getKeyPair(context)
            val cert = AdbKeyManager.getCertificate(context)

            GameOptimizerEngine.log("Connecting ADB to 127.0.0.1:$port...", GameOptimizerEngine.LogType.COMMAND)
            connection = AdbConnection.create("127.0.0.1", port, keyPair.private, cert)
            if (!connection.connect(5_000L, TimeUnit.MILLISECONDS, true)) {
                return@withContext ShellResult(
                    exitCode = -1,
                    stdout = "",
                    stderr = "",
                    transportError = "ADB connection not established"
                )
            }
            AdbPortStore.savePort(context, port)

            stream = connection.open("shell:$wrappedCommand")
            val rawBuilder = StringBuilder()

            val completed = withTimeoutOrNull(timeoutMs) {
                runInterruptible(Dispatchers.IO) {
                    val buffer = ByteArray(4096)
                    while (true) {
                        val bytesRead = try {
                            stream.read(buffer, 0, buffer.size)
                        } catch (e: IOException) {
                            if (stream.isClosed || e.cause is InterruptedException) {
                                -1
                            } else {
                                throw e
                            }
                        }
                        if (bytesRead < 0) break
                        if (bytesRead > 0) {
                            rawBuilder.append(String(buffer, 0, bytesRead, Charsets.UTF_8))
                        }
                    }
                }
                true
            }

            if (completed == null) {
                try { stream.close() } catch (_: Exception) {}
                return@withContext ShellResult(
                    exitCode = -1,
                    stdout = rawBuilder.toString().trimEnd(),
                    stderr = "",
                    timedOut = true
                )
            }

            parseNonceSentinelResult(rawBuilder.toString(), nonce)
        } catch (e: Exception) {
            GameOptimizerEngine.log(
                "[ERR] AdbConnection executeCommand failed: ${e.message}",
                GameOptimizerEngine.LogType.ERROR
            )
            ShellResult(
                exitCode = -1,
                stdout = "",
                stderr = "",
                transportError = e.message ?: "ADB execution failed"
            )
        } finally {
            try { stream?.close() } catch (_: Exception) {}
            try { connection?.close() } catch (_: Exception) {}
        }
    }

    /**
     * Launches a detached background command over a raw non-PTY ADB exec pipe,
     * feeds stdinPayload directly without exposing secrets in cmdline, and reads
     * any immediate stdout (e.g. discovered ephemeral port) up to readTimeoutMs.
     */
    suspend fun execDetached(
        context: Context,
        port: Int,
        command: String,
        stdinPayload: String? = null,
        readTimeoutMs: Long = 2_800L
    ): ShellResult = withContext(Dispatchers.IO) {
        var connection: AdbConnection? = null
        var stream: AdbStream? = null
        try {
            val keyPair = AdbKeyManager.getKeyPair(context)
            val cert = AdbKeyManager.getCertificate(context)

            connection = AdbConnection.create("127.0.0.1", port, keyPair.private, cert)
            if (!connection.connect(5_000L, TimeUnit.MILLISECONDS, true)) {
                return@withContext ShellResult(
                    exitCode = -1,
                    stdout = "",
                    stderr = "",
                    transportError = "ADB connection not established"
                )
            }
            AdbPortStore.savePort(context, port)

            stream = connection.open("exec:$command")
            if (stdinPayload != null) {
                val bytes = stdinPayload.toByteArray(Charsets.UTF_8)
                stream.write(bytes, 0, bytes.size)
                stream.flush()
            }

            val rawBuilder = StringBuilder()
            withTimeoutOrNull(readTimeoutMs) {
                runInterruptible(Dispatchers.IO) {
                    val buffer = ByteArray(1024)
                    while (true) {
                        val bytesRead = try {
                            stream.read(buffer, 0, buffer.size)
                        } catch (e: IOException) {
                            if (stream.isClosed || e.cause is InterruptedException) -1 else throw e
                        }
                        if (bytesRead < 0) break
                        if (bytesRead > 0) {
                            rawBuilder.append(String(buffer, 0, bytesRead, Charsets.UTF_8))
                        }
                    }
                }
            }
            ShellResult(exitCode = 0, stdout = rawBuilder.toString().trim(), stderr = "")
        } catch (e: Exception) {
            GameOptimizerEngine.log(
                "[ERR] AdbConnection execDetached failed: ${e.message}",
                GameOptimizerEngine.LogType.ERROR
            )
            ShellResult(
                exitCode = -1,
                stdout = "",
                stderr = "",
                transportError = e.message ?: "ADB detached execution failed"
            )
        } finally {
            try { stream?.close() } catch (_: Exception) {}
            try { connection?.close() } catch (_: Exception) {}
        }
    }

    internal fun parseNonceSentinelResult(rawOutput: String, nonce: String): ShellResult {
        val sentinelRegex = Regex("""(?:\r?\n)?__FG_EXIT_${Regex.escape(nonce)}__:(-?\d+)\s*$""")
        val match = sentinelRegex.find(rawOutput)
            ?: return ShellResult(
                exitCode = -1,
                stdout = rawOutput.trimEnd(),
                stderr = "",
                transportError = "Missing exit sentinel"
            )

        val exitCode = match.groupValues[1].toIntOrNull() ?: -1
        val cleanStdout = rawOutput.substring(0, match.range.first).trimEnd()
        return ShellResult(
            exitCode = exitCode,
            stdout = cleanStdout,
            stderr = ""
        )
    }
}
