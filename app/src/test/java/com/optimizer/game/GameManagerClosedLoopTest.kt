package com.optimizer.game

import com.optimizer.game.optimizer.GameOptimizerEngine
import com.optimizer.game.privilege.ShellResult
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class GameManagerClosedLoopTest {

    @Before
    fun setUp() {
        GameOptimizerEngine.resetStateForTesting()
    }

    @After
    fun tearDown() {
        GameOptimizerEngine.resetStateForTesting()
    }

    @Test
    fun testBuildGameModeSetArgsAlwaysSendsFpsAndDownscaleTogether() {
        assertEquals(
            listOf("cmd", "game", "set", "--mode", "2", "--fps", "60", "--downscale", "0.75", "com.example.game"),
            GameOptimizerEngine.buildGameModeSetArgs("com.example.game", 60, "0.75")
        )
        assertEquals(
            listOf("cmd", "game", "set", "--mode", "2", "--fps", "90", "--downscale", "disable", "com.example.game"),
            GameOptimizerEngine.buildGameModeSetArgs("com.example.game", 90, "1.0", useDisableForNative = true)
        )
        assertEquals(
            listOf("cmd", "game", "set", "--mode", "2", "--fps", "90", "--downscale", "1.0", "com.example.game"),
            GameOptimizerEngine.buildGameModeSetArgs("com.example.game", 90, "1.0", useDisableForNative = false)
        )
    }

    @Test
    fun testVerifyGameManagerMode2ActiveAndCleanParsing() {
        val activeDump = """
            [Name:com.example.game.other Modes:{2=[Game Mode:2, Scaling:0.6, Use Angle:false, Fps:30]}]
            [Name:com.example.game Modes:{1=[Game Mode:1, Scaling:-1.0, Use Angle:false, Fps:0], 2=[Game Mode:2, Scaling:0.75, Use Angle:false, Fps:60, Loading Boost Duration:0]}]
        """.trimIndent()

        assertTrue(GameOptimizerEngine.verifyGameManagerMode2(activeDump, "com.example.game", 60, "0.75"))
        assertFalse(GameOptimizerEngine.verifyGameManagerMode2(activeDump, "com.example.game", 90, "0.75"))
        assertFalse(GameOptimizerEngine.verifyGameManagerMode2(activeDump, "com.example.game", 60, "0.85"))
        assertFalse(GameOptimizerEngine.verifyGameManagerClean(activeDump, "com.example.game"))

        val cleanDump = """
            No intervention found for package com.example.game
            [Name:com.example.game.other Modes:{2=[Game Mode:2, Scaling:0.6, Use Angle:false, Fps:30]}]
        """.trimIndent()
        assertTrue(GameOptimizerEngine.verifyGameManagerClean(cleanDump, "com.example.game"))
        assertFalse(GameOptimizerEngine.verifyGameManagerMode2(cleanDump, "com.example.game", 60, null))

        // Manifest-declared Mode 2 entry with zero FPS/scaling intervention after cmd game reset
        val manifestMode2CleanDump = """
            [Name:com.example.game Modes:{1=[Game Mode:1, Scaling:-1.0, Use Angle:false, Fps:0], 2=[Game Mode:2, Scaling:-1.0, Use Angle:false, Fps:0]}]
        """.trimIndent()
        assertTrue(GameOptimizerEngine.verifyGameManagerClean(manifestMode2CleanDump, "com.example.game"))
    }

    @Test
    fun testDownscaleDisableFallsBackToOnePointZeroAndMode2NeverUsesCustom() = runBlocking {
        val executed = mutableListOf<List<String>>()
        var gameReset = false
        GameOptimizerEngine.commandExecutor = { args ->
            executed.add(args)
            when {
                args == listOf("cmd", "game", "set", "--mode", "2", "--fps", "60", "--downscale", "disable", "com.example.game") ->
                    ShellResult(exitCode = 1, stdout = "", stderr = "Invalid downscale value: disable")
                args == listOf("cmd", "game", "reset", "com.example.game") -> {
                    gameReset = true
                    ShellResult(exitCode = 0, stdout = "", stderr = "")
                }
                args == listOf("dumpsys", "game") ->
                    ShellResult(
                        exitCode = 0,
                        stdout = if (gameReset) {
                            "No intervention found for package com.example.game"
                        } else {
                            "[Name:com.example.game Modes:{2=[Game Mode:2, Scaling:1.0, Use Angle:false, Fps:60]}]"
                        },
                        stderr = ""
                    )
                args == listOf("settings", "get", "system", "peak_refresh_rate") ||
                    args == listOf("settings", "get", "system", "min_refresh_rate") ->
                    ShellResult(exitCode = 0, stdout = "null", stderr = "")
                args == listOf("settings", "get", "global", "zen_mode") ->
                    ShellResult(exitCode = 0, stdout = "0", stderr = "")
                else -> ShellResult(exitCode = 0, stdout = "", stderr = "")
            }
        }

        val ok = GameOptimizerEngine.applyProfile(
            packageName = "com.example.game",
            targetFps = 60,
            renderScale = 100,
            downscaleRatio = "1.0",
            ramTrim = true,
            dnd = true
        )
        assertTrue(ok)

        assertTrue(executed.contains(listOf("cmd", "game", "set", "--mode", "2", "--fps", "60", "--downscale", "disable", "com.example.game")))
        assertTrue(executed.contains(listOf("cmd", "game", "set", "--mode", "2", "--fps", "60", "--downscale", "1.0", "com.example.game")))
        assertTrue(executed.contains(listOf("cmd", "game", "mode", "2", "com.example.game")))
        assertFalse(executed.any { it.contains("custom") })
        assertFalse(executed.any { it.contains("device_config") })
        assertFalse(executed.any { it.contains("deviceidle") })
        assertTrue(executed.contains(listOf("am", "kill-all")))
        assertTrue(executed.contains(listOf("cmd", "notification", "set_dnd", "priority")))

        executed.clear()
        val restored = GameOptimizerEngine.restoreDefaults("com.example.game")
        assertTrue(restored)
        assertTrue(executed.contains(listOf("cmd", "game", "reset", "com.example.game")))
        assertTrue(executed.contains(listOf("dumpsys", "game")))
        assertTrue(executed.contains(listOf("cmd", "notification", "set_dnd", "off")))
    }

    @Test
    fun testUpdateLiveGameConfigDoesNotForceStopOrTouchWm() = runBlocking {
        val executed = mutableListOf<List<String>>()
        GameOptimizerEngine.commandExecutor = { args ->
            executed.add(args)
            when {
                args == listOf("dumpsys", "game") ->
                    ShellResult(
                        exitCode = 0,
                        stdout = "[Name:com.example.game Modes:{2=[Game Mode:2, Scaling:0.75, Use Angle:false, Fps:30]}]",
                        stderr = ""
                    )
                args == listOf("dumpsys", "display") ->
                    ShellResult(exitCode = 0, stdout = "60.0 fps 90.0 fps 120.0 fps", stderr = "")
                else -> ShellResult(exitCode = 0, stdout = "60.0", stderr = "")
            }
        }

        val verified = GameOptimizerEngine.updateLiveGameConfig(
            packageName = "com.example.game",
            targetFps = 30,
            downscaleRatio = "0.75"
        )
        assertTrue(verified)
        assertTrue(executed.contains(listOf("cmd", "game", "set", "--mode", "2", "--fps", "30", "--downscale", "0.75", "com.example.game")))
        assertFalse(executed.any { it.contains("force-stop") })
        assertFalse(executed.any { it.firstOrNull() == "wm" })
    }

    @Test
    fun testThermalHalPrioritizesSkinThenSocAndIgnoresCached() = runBlocking {
        val dumpWithSkin = """
            Cached temperatures:
                Temperature{mValue=44.9, mType=3, mName=SKIN, mStatus=1}
            Current temperatures from HAL:
                Temperature{mValue=31.4, mType=3, mName=SKIN, mStatus=0}
                Temperature{mValue=36.1, mType=0, mName=SOC, mStatus=0}
                Temperature{mValue=29.9, mType=2, mName=BATTERY, mStatus=0}
            Current cooling devices from HAL:
        """.trimIndent()
        assertEquals(31.4f, GameOptimizerEngine.parseThermalHalTemperature(dumpWithSkin)!!, 0.01f)

        val dumpWithSocOnly = """
            Cached temperatures:
                Temperature{mValue=44.9, mType=3, mName=SKIN, mStatus=1}
            Current temperatures from HAL:
                Temperature{mValue=36.1, mType=0, mName=SOC, mStatus=0}
                Temperature{mValue=29.9, mType=2, mName=BATTERY, mStatus=0}
        """.trimIndent()
        assertEquals(36.1f, GameOptimizerEngine.parseThermalHalTemperature(dumpWithSocOnly)!!, 0.01f)

        val cachedOnlyDump = """
            Cached temperatures:
                Temperature{mValue=44.9, mType=3, mName=SKIN, mStatus=1}
        """.trimIndent()
        assertNull(GameOptimizerEngine.parseThermalHalTemperature(cachedOnlyDump))

        GameOptimizerEngine.commandExecutor = {
            ShellResult(exitCode = -1, stdout = "", stderr = "", transportError = "disconnected")
        }
        assertNull(GameOptimizerEngine.queryThermalReading())
    }
}
