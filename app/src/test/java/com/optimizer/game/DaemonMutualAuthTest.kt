package com.optimizer.game

import com.optimizer.game.adb.DaemonProtocol
import com.optimizer.game.adb.DaemonServer
import com.optimizer.game.adb.NativeDaemonManager
import com.optimizer.game.privilege.ShellResult
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class DaemonMutualAuthTest {

    @Test
    fun testMutualChallengeResponseProofVerification() {
        val token = "secret-daemon-token-123"
        val clientNonce = "aabbccddeeff00112233445566778899"
        val serverNonce = "99887766554433221100ffeeddccbbaa"

        val serverProof = DaemonProtocol.computeProof(token, "SERVER:$clientNonce:$serverNonce")
        val clientProof = DaemonProtocol.computeProof(token, "CLIENT:$clientNonce:$serverNonce")

        assertTrue(
            DaemonProtocol.verifyProof(
                DaemonProtocol.computeProof(token, "SERVER:$clientNonce:$serverNonce"),
                serverProof
            )
        )
        assertTrue(
            DaemonProtocol.verifyProof(
                DaemonProtocol.computeProof(token, "CLIENT:$clientNonce:$serverNonce"),
                clientProof
            )
        )

        // Wrong token must fail
        val wrongTokenProof = DaemonProtocol.computeProof("wrong-token", "SERVER:$clientNonce:$serverNonce")
        assertFalse(DaemonProtocol.verifyProof(serverProof, wrongTokenProof))

        // Tampered nonce must fail
        val tamperedNonceProof = DaemonProtocol.computeProof(token, "SERVER:${clientNonce}x:$serverNonce")
        assertFalse(DaemonProtocol.verifyProof(serverProof, tamperedNonceProof))
    }

    @Test
    fun testLengthPrefixedBinaryFramingRoundTrip() {
        val largeStdout = "A".repeat(70_000) + "\nline2"
        val samples = listOf(
            ShellResult(exitCode = 1, stdout = "", stderr = "permission denied"),
            ShellResult(exitCode = 0, stdout = "line1\nline2\nline3", stderr = ""),
            ShellResult(exitCode = 0, stdout = largeStdout, stderr = "warn")
        )

        val baos = ByteArrayOutputStream()
        val out = DataOutputStream(baos)
        for (sample in samples) {
            DaemonProtocol.writeResultFrame(out, sample)
        }

        val input = DataInputStream(ByteArrayInputStream(baos.toByteArray()))
        for (expected in samples) {
            val actual = DaemonProtocol.readResultFrame(input)
            assertEquals(expected.exitCode, actual.exitCode)
            assertEquals(expected.stdout, actual.stdout)
            assertEquals(expected.stderr, actual.stderr)
        }
    }

    @Test
    fun testDaemonServerEphemeralPortMutualAuthAndEmptyExitOne() {
        val token = "unit-test-token-999"
        val tempPortFile = File.createTempFile("fg_daemon_port_", ".tmp")
        tempPortFile.deleteOnExit()

        val readyLatch = CountDownLatch(1)
        val boundPort = AtomicInteger(-1)
        val serverThread = Thread {
            DaemonServer.serve(
                expectedToken = token,
                portFilePath = tempPortFile.absolutePath,
                bindPort = 0,
                onReady = { port ->
                    boundPort.set(port)
                    readyLatch.countDown()
                }
            )
        }.apply {
            isDaemon = true
            start()
        }

        try {
            assertTrue("DaemonServer did not become ready in time", readyLatch.await(5, TimeUnit.SECONDS))
            val port = boundPort.get()
            assertTrue("Expected ephemeral port > 0, got $port", port > 0)
            assertEquals(port.toString(), tempPortFile.readText().trim())

            // 1. Wrong-token peer must fail mutual auth BEFORE sending commands
            Socket().use { badSocket ->
                badSocket.connect(InetSocketAddress("127.0.0.1", port), 2000)
                badSocket.soTimeout = 2000
                val badIn = DataInputStream(BufferedInputStream(badSocket.getInputStream()))
                val badOut = DataOutputStream(BufferedOutputStream(badSocket.getOutputStream()))
                assertFalse(DaemonProtocol.authenticateClient(badIn, badOut, "invalid-token"))
            }

            // 2. Valid-token peer succeeds PING, non-zero empty EXEC, EXEC_ARGS, and SHUTDOWN
            Socket().use { socket ->
                socket.connect(InetSocketAddress("127.0.0.1", port), 2000)
                socket.soTimeout = 5000
                val input = DataInputStream(BufferedInputStream(socket.getInputStream()))
                val out = DataOutputStream(BufferedOutputStream(socket.getOutputStream()))

                assertTrue(DaemonProtocol.authenticateClient(input, out, token))

                // PING
                out.writeByte(DaemonProtocol.FRAME_PING.toInt())
                out.flush()
                val pingRes = DaemonProtocol.readResultFrame(input)
                assertTrue(pingRes.isOk)
                assertEquals("PONG", pingRes.stdout)

                val isWindows = System.getProperty("os.name").orEmpty().lowercase().contains("win")

                // Empty-output exit code 1 must NOT be masked as "OK"
                val failCommand = if (isWindows) "cmd /c exit 1" else "false"
                out.writeByte(DaemonProtocol.FRAME_EXEC.toInt())
                DaemonProtocol.writeStringFrame(out, failCommand)
                out.flush()
                val failRes = DaemonProtocol.readResultFrame(input)
                assertFalse(failRes.isOk)
                assertEquals(1, failRes.exitCode)
                assertEquals("", failRes.stdout)
                assertFalse(failRes.stdout == "OK")

                // EXEC_ARGS
                val args = if (isWindows) {
                    listOf("cmd", "/c", "echo", "hello_args")
                } else {
                    listOf("echo", "hello_args")
                }
                out.writeByte(DaemonProtocol.FRAME_EXEC_ARGS.toInt())
                out.writeInt(args.size)
                for (arg in args) {
                    DaemonProtocol.writeStringFrame(out, arg)
                }
                out.flush()
                val argsRes = DaemonProtocol.readResultFrame(input)
                assertTrue(argsRes.isOk)
                assertEquals("hello_args", argsRes.stdout)

                // SHUTDOWN
                out.writeByte(DaemonProtocol.FRAME_SHUTDOWN.toInt())
                out.flush()
                val shutdownRes = DaemonProtocol.readResultFrame(input)
                assertTrue(shutdownRes.isOk)
                assertEquals("SHUTDOWN", shutdownRes.stdout)
            }

            serverThread.join(3000)
            assertFalse(serverThread.isAlive)
        } finally {
            tempPortFile.delete()
        }
    }

    @Test
    fun testConcurrentEnsureDaemonStartedLaunchesOnlyOnce() = runBlocking {
        val previousPort = NativeDaemonManager.currentPort
        NativeDaemonManager.currentPort = null
        try {
            val launchCount = AtomicInteger(0)
            val daemonRunning = AtomicBoolean(false)
            val publishedPort = AtomicReference<Int?>(null)

            val results = (1..5).map {
                async {
                    NativeDaemonManager.ensureDaemonStartedInternal(
                        checkAlive = { daemonRunning.get() && NativeDaemonManager.currentPort == 41234 },
                        readPort = { publishedPort.get() },
                        launchDaemon = {
                            launchCount.incrementAndGet()
                            delay(50)
                            publishedPort.set(41234)
                            daemonRunning.set(true)
                            ShellResult(exitCode = 0, stdout = "", stderr = "")
                        },
                        pollDelayMs = 10L
                    )
                }
            }.awaitAll()

            assertTrue(results.all { it })
            assertEquals(1, launchCount.get())
            assertEquals(41234, NativeDaemonManager.currentPort)
        } finally {
            NativeDaemonManager.currentPort = previousPort
        }
    }
}
