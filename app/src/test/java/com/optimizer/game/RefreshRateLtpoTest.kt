package com.optimizer.game

import com.optimizer.game.optimizer.GameOptimizerEngine
import com.optimizer.game.privilege.ShellResult
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class RefreshRateLtpoTest {

    @Before
    fun setUp() {
        GameOptimizerEngine.resetStateForTesting()
    }

    @After
    fun tearDown() {
        GameOptimizerEngine.resetStateForTesting()
    }

    @Test
    fun testBuildRestoreRefreshRateArgsHandlesNullWithDelete() {
        assertEquals(
            listOf("settings", "delete", "system", "min_refresh_rate"),
            GameOptimizerEngine.buildRestoreRefreshRateArgs("min_refresh_rate", "null")
        )
        assertEquals(
            listOf("settings", "delete", "system", "peak_refresh_rate"),
            GameOptimizerEngine.buildRestoreRefreshRateArgs("peak_refresh_rate", "  ")
        )
        assertEquals(
            listOf("settings", "put", "system", "min_refresh_rate", "24.0"),
            GameOptimizerEngine.buildRestoreRefreshRateArgs("min_refresh_rate", "24.0")
        )
    }

    @Test
    fun testBuildRefreshRateLockSequenceOrdersMinAndPeakSafely() {
        // Stepping down (120 -> 60): min must be written BEFORE peak
        assertEquals(
            listOf(
                listOf("settings", "put", "system", "min_refresh_rate", "60.0"),
                listOf("settings", "put", "system", "peak_refresh_rate", "60.0")
            ),
            GameOptimizerEngine.buildRefreshRateLockSequence(targetHz = 60f, currentMinHz = 120f)
        )

        // Stepping up (24 -> 90 or 60 -> 120): peak must be written BEFORE min
        assertEquals(
            listOf(
                listOf("settings", "put", "system", "peak_refresh_rate", "90.0"),
                listOf("settings", "put", "system", "min_refresh_rate", "90.0")
            ),
            GameOptimizerEngine.buildRefreshRateLockSequence(targetHz = 90f, currentMinHz = 24f)
        )
        assertEquals(
            listOf(
                listOf("settings", "put", "system", "peak_refresh_rate", "120.0"),
                listOf("settings", "put", "system", "min_refresh_rate", "120.0")
            ),
            GameOptimizerEngine.buildRefreshRateLockSequence(targetHz = 120f, currentMinHz = 60f)
        )
    }

    @Test
    fun testResolveOptimalRefreshRateAgainstSupportedModes() {
        val fullLtpoPanel = listOf(60.0f, 90.0f, 120.00001f)
        assertEquals(60f, GameOptimizerEngine.resolveOptimalRefreshRate(30, fullLtpoPanel), 0.01f)
        assertEquals(120f, GameOptimizerEngine.resolveOptimalRefreshRate(40, fullLtpoPanel), 0.01f)
        assertEquals(90f, GameOptimizerEngine.resolveOptimalRefreshRate(45, fullLtpoPanel), 0.01f)
        assertEquals(60f, GameOptimizerEngine.resolveOptimalRefreshRate(60, fullLtpoPanel), 0.01f)
        assertEquals(90f, GameOptimizerEngine.resolveOptimalRefreshRate(90, fullLtpoPanel), 0.01f)
        assertEquals(120f, GameOptimizerEngine.resolveOptimalRefreshRate(120, fullLtpoPanel), 0.01f)

        val panelWithout90Hz = listOf(60.0f, 120.0f)
        assertEquals(120f, GameOptimizerEngine.resolveOptimalRefreshRate(45, panelWithout90Hz), 0.01f)
        assertEquals(60f, GameOptimizerEngine.resolveOptimalRefreshRate(90, panelWithout90Hz), 0.01f)
        assertEquals(120f, GameOptimizerEngine.resolveOptimalRefreshRate(40, panelWithout90Hz), 0.01f)

        val panel60HzOnly = listOf(60.0f)
        for (fps in listOf(30, 40, 45, 60, 90, 120)) {
            assertEquals(60f, GameOptimizerEngine.resolveOptimalRefreshRate(fps, panel60HzOnly), 0.01f)
        }
    }

    @Test
    fun testNullRefreshRateBaselineRestoredViaSettingsDelete() = runBlocking {
        val executed = mutableListOf<List<String>>()
        var gameReset = false
        GameOptimizerEngine.commandExecutor = { args ->
            executed.add(args)
            when (args) {
                listOf("settings", "get", "system", "peak_refresh_rate"),
                listOf("settings", "get", "system", "min_refresh_rate") ->
                    ShellResult(exitCode = 0, stdout = "null\n", stderr = "")
                listOf("dumpsys", "display") ->
                    ShellResult(exitCode = 0, stdout = "60.0 fps 90.0 fps 120.0 fps", stderr = "")
                listOf("cmd", "game", "reset", "com.example.game") -> {
                    gameReset = true
                    ShellResult(exitCode = 0, stdout = "", stderr = "")
                }
                listOf("dumpsys", "game") ->
                    ShellResult(
                        exitCode = 0,
                        stdout = if (gameReset) {
                            "No intervention found for package com.example.game"
                        } else {
                            "[Name:com.example.game Modes:{2=[Game Mode:2, Scaling:-1.0, Use Angle:false, Fps:60]}]"
                        },
                        stderr = ""
                    )
                else -> ShellResult(exitCode = 0, stdout = "", stderr = "")
            }
        }

        GameOptimizerEngine.applyProfile(
            packageName = "com.example.game",
            targetFps = 60,
            renderScale = 100,
            ramTrim = false,
            dnd = false
        )
        executed.clear()

        assertTrue(GameOptimizerEngine.restoreDefaults("com.example.game"))
        assertTrue(executed.contains(listOf("settings", "delete", "system", "peak_refresh_rate")))
        assertTrue(executed.contains(listOf("settings", "delete", "system", "min_refresh_rate")))
    }
}
