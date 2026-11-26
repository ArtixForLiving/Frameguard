package com.optimizer.game

import com.optimizer.game.adb.AdbMdnsDiscovery
import com.optimizer.game.privilege.ShellResult
import com.optimizer.game.shizuku.ShellUserService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.net.InetAddress

class ShellResultParsingTest {

    @Test
    fun testShellResultIsOk() {
        assertTrue(ShellResult(exitCode = 0, stdout = "ok", stderr = "").isOk)
        assertFalse(ShellResult(exitCode = 1, stdout = "", stderr = "fail").isOk)
        assertFalse(ShellResult(exitCode = 0, stdout = "ok", stderr = "", timedOut = true).isOk)
        assertFalse(ShellResult(exitCode = 0, stdout = "ok", stderr = "", transportError = "dead").isOk)
    }

    @Test
    fun testShellResultToLegacyString() {
        assertEquals("output", ShellResult(exitCode = 0, stdout = "output", stderr = "").toLegacyString())
        assertEquals("", ShellResult(exitCode = 1, stdout = "", stderr = "err").toLegacyString())
        assertEquals(
            "ERROR: Command timed out",
            ShellResult(exitCode = 0, stdout = "partial", stderr = "", timedOut = true).toLegacyString()
        )
        assertEquals(
            "ERROR: binder died",
            ShellResult(exitCode = -1, stdout = "", stderr = "", transportError = "binder died").toLegacyString()
        )
    }

    @Test
    fun testTokenizeValidCommands() {
        assertEquals(
            listOf("cmd", "game", "set", "--mode", "2", "--fps", "60", "com.example.game"),
            ShellUserService.tokenizeCommand("cmd game set --mode 2 --fps 60 com.example.game")
        )
        assertEquals(
            listOf("settings", "put", "system", "peak_refresh_rate", "120.0"),
            ShellUserService.tokenizeCommand("  settings   put\tsystem \"peak_refresh_rate\" '120.0' ")
        )
        assertEquals(
            listOf("echo", "hello world"),
            ShellUserService.tokenizeCommand("echo hello\\ world")
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
                ShellUserService.tokenizeCommand(input)
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
                ShellUserService.tokenizeCommand(invalid)
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
}
