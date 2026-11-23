package com.optimizer.game.adb

import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket

/**
 * Privileged daemon executed under Android UID 2000 via app_process.
 * Operates over a secure local TCP loopback socket (127.0.0.1:25890) with UUID token authentication
 * to prevent SELinux unix_stream_socket denials on modern Android versions.
 */
object DaemonServer {

    const val DAEMON_PORT = 25890
    const val EOF_MARKER = "---FRAMEGUARD_EOF---"

    @JvmStatic
    fun main(args: Array<String>) {
        val expectedToken = args.firstOrNull() ?: return
        try {
            val server = ServerSocket(DAEMON_PORT, 5, InetAddress.getByName("127.0.0.1"))
            while (true) {
                try {
                    val client = server.accept()
                    handleClient(client, expectedToken)
                } catch (_: Exception) {}
            }
        } catch (_: Exception) {}
    }

    private fun handleClient(socket: Socket, expectedToken: String) {
        Thread {
            try {
                socket.soTimeout = 15000
                val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
                val writer = PrintWriter(socket.getOutputStream(), true)

                // 1. Authenticate with token handshake
                val clientToken = reader.readLine()
                if (clientToken != expectedToken) {
                    writer.println("ERR: UNAUTHORIZED")
                    socket.close()
                    return@Thread
                }
                writer.println("OK: AUTHENTICATED")

                // 2. Command execution loop
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    val cmd = line ?: break
                    if (cmd == "PING") {
                        writer.println("PONG")
                        continue
                    }
                    if (cmd == "EXIT") {
                        break
                    }

                    val output = executeShellCommand(cmd)
                    writer.println(output)
                    writer.println(EOF_MARKER)
                }
            } catch (_: Exception) {
            } finally {
                try { socket.close() } catch (_: Exception) {}
            }
        }.start()
    }

    private fun executeShellCommand(command: String): String {
        return try {
            val process = Runtime.getRuntime().exec(arrayOf("sh", "-c", command))
            val out = process.inputStream.bufferedReader().readText()
            val err = process.errorStream.bufferedReader().readText()
            process.waitFor()
            val combined = (out + err).trimEnd()
            if (combined.isEmpty()) "OK" else combined
        } catch (e: Exception) {
            "ERROR: ${e.message}"
        }
    }
}

