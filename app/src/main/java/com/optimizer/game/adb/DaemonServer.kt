package com.optimizer.game.adb

import com.optimizer.game.privilege.ShellCommandRunner
import com.optimizer.game.privilege.ShellResult
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.BufferedReader
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.File
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Privileged daemon executed under Android UID 2000 via app_process.
 * Binds an ephemeral local TCP loopback socket (127.0.0.1:0) with mutual challenge-response
 * authentication and length-prefixed binary framing.
 */
object DaemonServer {

    const val PORT_FILE_PATH = "/data/local/tmp/.fg_daemon_port"
    private const val CLIENT_POOL_SIZE = 4
    private const val MAX_ARGS_COUNT = 4096

    @JvmStatic
    fun main(args: Array<String>) {
        // Read shared auth token strictly from stdin so /proc/<pid>/cmdline never exposes it.
        val expectedToken = try {
            BufferedReader(InputStreamReader(System.`in`, Charsets.UTF_8)).readLine()?.trim()
        } catch (_: Exception) {
            null
        }
        if (expectedToken.isNullOrEmpty()) return

        serve(expectedToken = expectedToken, portFilePath = PORT_FILE_PATH)
    }

    internal fun serve(
        expectedToken: String,
        portFilePath: String? = PORT_FILE_PATH,
        bindPort: Int = 0,
        onReady: ((Int) -> Unit)? = null
    ) {
        val pool = Executors.newFixedThreadPool(CLIENT_POOL_SIZE)
        val running = AtomicBoolean(true)
        var serverSocket: ServerSocket? = null
        try {
            val server = ServerSocket(bindPort, 8, InetAddress.getByName("127.0.0.1"))
            serverSocket = server
            val boundPort = server.localPort

            if (!portFilePath.isNullOrEmpty()) {
                writePortFile(boundPort, portFilePath)
            }

            println("READY:$boundPort")
            System.out.flush()
            onReady?.invoke(boundPort)

            while (running.get()) {
                try {
                    val client = server.accept()
                    pool.execute {
                        handleClient(client, expectedToken) {
                            running.set(false)
                            try {
                                server.close()
                            } catch (_: Exception) {}
                        }
                    }
                } catch (_: SocketException) {
                    if (!running.get()) break
                } catch (_: Exception) {
                    if (!running.get()) break
                }
            }
        } catch (_: Exception) {
        } finally {
            running.set(false)
            try { serverSocket?.close() } catch (_: Exception) {}
            pool.shutdownNow()
        }
    }

    private fun writePortFile(port: Int, path: String) {
        try {
            val file = File(path)
            file.writeText("$port\n", Charsets.UTF_8)
            file.setReadable(true, false)
        } catch (_: Exception) {}
    }

    private fun handleClient(
        socket: Socket,
        expectedToken: String,
        onShutdown: () -> Unit
    ) {
        try {
            socket.soTimeout = 120_000
            val input = DataInputStream(BufferedInputStream(socket.getInputStream()))
            val out = DataOutputStream(BufferedOutputStream(socket.getOutputStream()))

            if (!DaemonProtocol.authenticateServer(input, out, expectedToken)) {
                return
            }

            while (true) {
                val frameType = try {
                    input.readByte()
                } catch (_: EOFException) {
                    break
                }

                when (frameType) {
                    DaemonProtocol.FRAME_PING -> {
                        DaemonProtocol.writeResultFrame(
                            out,
                            ShellResult(exitCode = 0, stdout = "PONG", stderr = "")
                        )
                    }
                    DaemonProtocol.FRAME_EXEC -> {
                        val cmd = DaemonProtocol.readStringFrame(input)
                        val result = ShellCommandRunner.runCommand(cmd)
                        DaemonProtocol.writeResultFrame(out, result)
                    }
                    DaemonProtocol.FRAME_EXEC_ARGS -> {
                        val count = input.readInt()
                        if (count !in 1..MAX_ARGS_COUNT) {
                            DaemonProtocol.writeResultFrame(
                                out,
                                ShellResult(exitCode = 2, stdout = "", stderr = "Invalid argument count: $count")
                            )
                            break
                        }
                        val args = ArrayList<String>(count)
                        for (i in 0 until count) {
                            args.add(DaemonProtocol.readStringFrame(input))
                        }
                        val result = ShellCommandRunner.runArgs(args)
                        DaemonProtocol.writeResultFrame(out, result)
                    }
                    DaemonProtocol.FRAME_SHUTDOWN -> {
                        DaemonProtocol.writeResultFrame(
                            out,
                            ShellResult(exitCode = 0, stdout = "SHUTDOWN", stderr = "")
                        )
                        onShutdown()
                        break
                    }
                    else -> {
                        DaemonProtocol.writeResultFrame(
                            out,
                            ShellResult(exitCode = 2, stdout = "", stderr = "Unknown frame type: $frameType")
                        )
                        break
                    }
                }
            }
        } catch (_: Exception) {
        } finally {
            try { socket.close() } catch (_: Exception) {}
        }
    }
}
