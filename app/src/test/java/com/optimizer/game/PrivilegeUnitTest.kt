package com.optimizer.game

import com.optimizer.game.adb.AdbMdnsDiscovery
import com.optimizer.game.adb.DaemonServer
import com.optimizer.game.optimizer.GameOptimizerEngine
import com.optimizer.game.privilege.PrivilegeSource
import com.optimizer.game.privilege.PrivilegeState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PrivilegeUnitTest {

    @Test
    fun testPrivilegeStateDefaults() {
        val defaultState = PrivilegeState()
        assertFalse(defaultState.isConnected)
        assertEquals(PrivilegeSource.NONE, defaultState.source)
        assertEquals("UNBOUND", defaultState.statusMessage)
    }

    @Test
    fun testPrivilegeStateNativeAdb() {
        val state = PrivilegeState(
            isConnected = true,
            source = PrivilegeSource.NATIVE_ADB,
            statusMessage = "UID 2000 · ADB"
        )
        assertTrue(state.isConnected)
        assertEquals(PrivilegeSource.NATIVE_ADB, state.source)
        assertEquals("UID 2000 · ADB", state.statusMessage)
    }

    @Test
    fun testPrivilegeStateShizuku() {
        val state = PrivilegeState(
            isConnected = true,
            source = PrivilegeSource.SHIZUKU,
            statusMessage = "UID 2000 · SHIZUKU"
        )
        assertTrue(state.isConnected)
        assertEquals(PrivilegeSource.SHIZUKU, state.source)
        assertEquals("UID 2000 · SHIZUKU", state.statusMessage)
    }

    @Test
    fun testDaemonConstants() {
        assertEquals("frameguard_shell_daemon", DaemonServer.SOCKET_NAME)
        assertEquals("---FRAMEGUARD_EOF---", DaemonServer.EOF_MARKER)
    }

    @Test
    fun testMdnsServiceTypes() {
        assertEquals("_adb-tls-pairing._tcp.", AdbMdnsDiscovery.SERVICE_TYPE_PAIRING)
        assertEquals("_adb-tls-connect._tcp.", AdbMdnsDiscovery.SERVICE_TYPE_CONNECT)
    }

    @Test
    fun testOptimizerDefaults() {
        assertEquals("com.digitalextremes.warframemobile", GameOptimizerEngine.DEFAULT_PACKAGE)
        GameOptimizerEngine.setTargetFps(45)
        assertEquals(45, GameOptimizerEngine.currentFps)
    }
}
