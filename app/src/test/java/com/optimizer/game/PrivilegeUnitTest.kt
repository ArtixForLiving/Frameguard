package com.optimizer.game

import com.optimizer.game.adb.AdbMdnsDiscovery
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
        assertEquals(25890, DaemonServer.DAEMON_PORT)
        assertEquals("---FRAMEGUARD_EOF---", DaemonServer.EOF_MARKER)
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
        val roblox = MainViewModel.SUPPORTED_CATALOG.find { it.id == "roblox" }
        assertNotNull(roblox)
        assertEquals(60, roblox?.recommendedFps)
        assertEquals(60, roblox?.maxSupportedFps)
        assertTrue(roblox?.supportsRenderScale == true)
        assertEquals(75, roblox?.recommendedScale)

        val warframe = MainViewModel.SUPPORTED_CATALOG.find { it.id == "warframe" }
        assertTrue(warframe?.supportsRenderScale == false)

        val genshin = MainViewModel.SUPPORTED_CATALOG.find { it.id == "genshin" }
        assertTrue(genshin?.supportsRenderScale == true)
        assertEquals(75, genshin?.recommendedScale)
    }

    @Test
    fun testBuildConfigVersionIs107() {
        assertEquals(107, com.optimizer.game.BuildConfig.VERSION_CODE)
        assertEquals("1.0.7", com.optimizer.game.BuildConfig.VERSION_NAME)
    }

    @Test
    fun testCompileShadersGracefulFallback() = kotlinx.coroutines.runBlocking {
        // In unbound unit test environment, PrivilegeManager.exec returns ERROR
        // With HARD-03 non-blocking fallback, compileShaders returns true
        val result = GameOptimizerEngine.compileShaders("com.example.game")
        assertTrue(result)
    }
}
