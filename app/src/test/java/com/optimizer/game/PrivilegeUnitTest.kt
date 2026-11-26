package com.optimizer.game

import com.optimizer.game.adb.AdbMdnsDiscovery
import com.optimizer.game.adb.DaemonProtocol
import com.optimizer.game.adb.DaemonServer
import com.optimizer.game.optimizer.GameOptimizerEngine
import com.optimizer.game.privilege.PrivilegeSource
import com.optimizer.game.privilege.PrivilegeState
import com.optimizer.game.viewmodel.MainViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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
        assertEquals("/data/local/tmp/.fg_daemon_port", DaemonServer.PORT_FILE_PATH)
        assertEquals(0x01.toByte(), DaemonProtocol.FRAME_PING)
        assertEquals(0x02.toByte(), DaemonProtocol.FRAME_EXEC)
        assertEquals(0x03.toByte(), DaemonProtocol.FRAME_EXEC_ARGS)
        assertEquals(0x04.toByte(), DaemonProtocol.FRAME_SHUTDOWN)
    }

    @Test
    fun testMdnsServiceTypes() {
        assertEquals("_adb-tls-pairing._tcp", AdbMdnsDiscovery.SERVICE_TYPE_PAIRING)
        assertEquals("_adb-tls-connect._tcp", AdbMdnsDiscovery.SERVICE_TYPE_CONNECT)
    }

    @Test
    fun testOptimizerDefaults() {
        assertEquals("com.digitalextremes.warframemobile", GameOptimizerEngine.DEFAULT_PACKAGE)
        GameOptimizerEngine.setTargetFps(45)
        assertEquals(45, GameOptimizerEngine.currentFps)
    }

    @Test
    fun testRobloxCatalogAndScaleGating() {
        val ids = MainViewModel.SUPPORTED_CATALOG.map { it.id }.toSet()
        assertEquals(setOf("warframe", "genshin", "minecraft", "roblox", "pubg", "pubgflash"), ids)

        for (game in MainViewModel.SUPPORTED_CATALOG) {
            assertTrue("${game.id} should support scale badge", game.supportsRenderScale)
            assertEquals(75, game.recommendedScale)
        }
    }

    @Test
    fun testBuildConfigVersionIs108() {
        assertEquals(108, com.optimizer.game.BuildConfig.VERSION_CODE)
        assertEquals("1.0.8", com.optimizer.game.BuildConfig.VERSION_NAME)
    }
}
