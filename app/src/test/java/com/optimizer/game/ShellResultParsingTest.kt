package com.optimizer.game

import com.optimizer.game.adb.AdbMdnsDiscovery
import com.optimizer.game.adb.AdbTlsClient
import com.optimizer.game.privilege.ShellCommandRunner
import com.optimizer.game.privilege.ShellResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.net.InetAddress

class ShellResultParsingTest {

    @Test
    fun testShellResultIsOk() {
        assertTrue(ShellResult(exitCode = 0, stdout = "ok", stderr = "").isOk)
        assertFalse(ShellResult(exitCode = 1, stdout = "", stderr = "fail").isOk)
        assertFalse(ShellResult(exitCode = 1, stdout = "", stderr = "").isOk)
        assertFalse(ShellResult(exitCode = 0, stdout = "ok", stderr = "", timedOut = true).isOk)
        assertFalse(ShellResult(exitCode = 0, stdout = "ok", stderr = "", transportError = "dead").isOk)
    }

    @Test
    fun testParseNonceSentinelValidExitZero() {
        val nonce = "0123456789abcdef0123456789abcdef"
        val raw = "Physical size: 1080x2400\nOverride size: 810x1800\n\n__FG_EXIT_${nonce}__:0\n"
        val result = AdbTlsClient.parseNonceSentinelResult(raw, nonce)

        assertTrue(result.isOk)
        assertEquals(0, result.exitCode)
        assertEquals("Physical size: 1080x2400\nOverride size: 810x1800", result.stdout)
        assertEquals("", result.stderr)
        assertNull(result.transportError)
    }

    @Test
    fun testParseNonceSentinelNonZeroExitWithEmptyStdout() {
        val nonce = "abcdef0123456789abcdef0123456789"
        val raw = "\n__FG_EXIT_${nonce}__:1\n"
        val result = AdbTlsClient.parseNonceSentinelResult(raw, nonce)

        assertFalse(result.isOk)
        assertEquals(1, result.exitCode)
        assertEquals("", result.stdout)
        assertFalse(result.stdout == "OK")
        assertNull(result.transportError)
    }

    @Test
    fun testParseNonceSentinelWithUnrelatedLinesAndCrlf() {
        val nonce = "deadbeefdeadbeefdeadbeefdeadbeef"
        val raw = "line one\r\n__FG_EXIT_other__:0\r\nvalue=42\r\n\r\n__FG_EXIT_${nonce}__:2\r\n"
        val result = AdbTlsClient.parseNonceSentinelResult(raw, nonce)

        assertFalse(result.isOk)
        assertEquals(2, result.exitCode)
        assertEquals("line one\r\n__FG_EXIT_other__:0\r\nvalue=42", result.stdout)
    }

    @Test
    fun testParseNonceSentinelMissingMarkerSetsTransportError() {
        val nonce = "11112222333344445555666677778888"
        val result = AdbTlsClient.parseNonceSentinelResult("partial output", nonce)

        assertFalse(result.isOk)
        assertEquals(-1, result.exitCode)
        assertEquals("partial output", result.stdout)
        assertNotNull(result.transportError)
    }

    @Test
    fun testTokenizeValidCommands() {
        assertEquals(
            listOf("cmd", "game", "set", "--mode", "2", "--fps", "60", "com.example.game"),
            ShellCommandRunner.tokenizeCommand("cmd game set --mode 2 --fps 60 com.example.game")
        )
        assertEquals(
            listOf("settings", "put", "system", "peak_refresh_rate", "120.0"),
            ShellCommandRunner.tokenizeCommand("  settings   put\tsystem \"peak_refresh_rate\" '120.0' ")
        )
        assertEquals(
            listOf("echo", "hello world"),
            ShellCommandRunner.tokenizeCommand("echo hello\\ world")
        )
    }

    @Test
    fun testTokenizeRejectsShellMetacharacters() {
        val maliciousInputs = listOf(
            "id; rm -rf /",
            "dumpsys | grep fps",
            "sleep 1 & id",
            "echo `id`",
            "echo \$HOME",
            "echo x > /tmp/out",
            "cat < /etc/hosts",
            "id\nwhoami",
            "id\rwhoami"
        )
        for (input in maliciousInputs) {
            try {
                ShellCommandRunner.tokenizeCommand(input)
                fail("Expected IllegalArgumentException for input: $input")
            } catch (expected: IllegalArgumentException) {
                assertTrue(expected.message?.contains("Rejected shell metacharacter") == true)
            }
        }
    }

    @Test
    fun testTokenizeRejectsInvalidSyntax() {
        for (invalid in listOf("", "   ", "echo \"unclosed", "echo 'unclosed", "echo trailing\\")) {
            try {
                ShellCommandRunner.tokenizeCommand(invalid)
                fail("Expected IllegalArgumentException for input: $invalid")
            } catch (_: IllegalArgumentException) {
                // Expected
            }
        }
    }

    @Test
    fun testLoopbackValidation() {
        assertTrue(AdbMdnsDiscovery.isLoopbackHost(listOf(InetAddress.getByName("127.0.0.1"))))
        assertTrue(AdbMdnsDiscovery.isLoopbackHost(listOf(InetAddress.getByName("::1"))))
        assertFalse(AdbMdnsDiscovery.isLoopbackHost(listOf(InetAddress.getByName("192.168.1.10"))))
        assertFalse(AdbMdnsDiscovery.isLoopbackHost(emptyList()))
    }

    @Test
    fun `isLocalDeviceHost accepts device local IP and rejects external LAN IP`() {
        val localIp = InetAddress.getByName("192.168.1.42")
        val externalIp = InetAddress.getByName("192.168.1.99")
        val localSet = setOf(localIp)

        assertTrue(AdbMdnsDiscovery.isLocalDeviceHost(listOf(localIp), localSet))
        assertTrue(AdbMdnsDiscovery.isLocalDeviceHost(listOf(InetAddress.getByName("127.0.0.1")), emptySet()))
        assertFalse(AdbMdnsDiscovery.isLocalDeviceHost(listOf(externalIp), localSet))
        assertFalse(AdbMdnsDiscovery.isLocalDeviceHost(emptyList(), localSet))
    }
}
