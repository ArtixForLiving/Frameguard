package com.optimizer.game

import android.os.PowerManager
import com.optimizer.game.optimizer.GameOptimizerEngine
import com.optimizer.game.privilege.ShellResult
import com.optimizer.game.service.BoostSessionService
import com.optimizer.game.service.ThermalHysteresisEvaluator
import com.optimizer.game.service.ThermalLimiterState
import com.optimizer.game.service.applyThermalTransition
import com.optimizer.game.service.parseThermalHeadroomSevereThreshold
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ThermalHeadroomCalibrationTest {

    @Before
    fun setUp() {
        GameOptimizerEngine.resetStateForTesting()
    }

    @After
    fun tearDown() {
        GameOptimizerEngine.resetStateForTesting()
    }

    @Test
    fun testPollerIntervalIsAtLeastTenSeconds() {
        assertTrue(BoostSessionService.TELEMETRY_AUDIT_INTERVAL_MS >= 10_000L)
        assertTrue(ThermalHysteresisEvaluator().pollIntervalMs >= 10_000L)
    }

    @Test
    fun testParsesSevereThresholdFromDumpsysThermalservice() {
        val motoDump = """
            ThermalHAL AIDL 3 connected: yes
            Temperature headroom thresholds: [NaN, 0.8666667, 0.93333334, 1.0, 1.2666667, 1.6666666, 1.7]
            Current temperatures from HAL:
                Temperature{mValue=31.4, mType=3, mName=SKIN, mStatus=0}
        """.trimIndent()
        val parsedMoto = parseThermalHeadroomSevereThreshold(motoDump)
        assertNotNull(parsedMoto)
        assertEquals(1.0f, parsedMoto!!, 0.001f)

        val customDump = "Temperature headroom thresholds: [NaN, 0.80, 0.95, 1.15, 1.40, 1.60, 1.80]"
        val parsedCustom = parseThermalHeadroomSevereThreshold(customDump)
        assertNotNull(parsedCustom)
        assertEquals(1.15f, parsedCustom!!, 0.001f)

        val evaluator = ThermalHysteresisEvaluator()
        evaluator.updateEngageThreshold(parsedCustom)

        // 0.90f and 1.05f must NOT trigger when device SEVERE threshold is 1.15f
        assertNull(evaluator.evaluate(PowerManager.THERMAL_STATUS_MODERATE, 0.90f, ThermalLimiterState.UNCAPPED))
        assertNull(evaluator.evaluate(PowerManager.THERMAL_STATUS_MODERATE, 1.05f, ThermalLimiterState.UNCAPPED))

        // 1.16f for 2 cycles engages CAPPED_30FPS
        assertNull(evaluator.evaluate(PowerManager.THERMAL_STATUS_MODERATE, 1.16f, ThermalLimiterState.UNCAPPED))
        assertEquals(
            ThermalLimiterState.CAPPED_30FPS,
            evaluator.evaluate(PowerManager.THERMAL_STATUS_MODERATE, 1.18f, ThermalLimiterState.UNCAPPED)
        )
    }

    @Test
    fun testThermalEngageAndReleaseRestoresUserTargetFpsWithoutCmdGameReset() = runBlocking {
        val executed = mutableListOf<List<String>>()
        var activeFps = 90
        GameOptimizerEngine.commandExecutor = { args ->
            executed.add(args)
            when {
                args.size >= 8 && args[0] == "cmd" && args[1] == "game" && args[2] == "set" -> {
                    activeFps = args[6].toInt()
                    ShellResult(exitCode = 0, stdout = "", stderr = "")
                }
                args == listOf("dumpsys", "display") ->
                    ShellResult(exitCode = 0, stdout = "60.0 fps 90.0 fps 120.0 fps", stderr = "")
                args == listOf("dumpsys", "game") ->
                    ShellResult(
                        exitCode = 0,
                        stdout = "[Name:com.example.game Modes:{2=[Game Mode:2, Scaling:0.75, Use Angle:false, Fps:$activeFps]}]",
                        stderr = ""
                    )
                else -> ShellResult(exitCode = 0, stdout = "", stderr = "")
            }
        }

        val engaged = applyThermalTransition(
            transition = ThermalLimiterState.CAPPED_30FPS,
            packageName = "com.example.game",
            userTargetFps = 90,
            downscaleRatio = "0.75",
            context = null
        )
        assertTrue(engaged)
        assertTrue(
            executed.contains(
                listOf("cmd", "game", "set", "--mode", "2", "--fps", "30", "--downscale", "0.75", "com.example.game")
            )
        )
        assertFalse(executed.any { it.contains("device_config") })
        assertFalse(executed.any { it.contains("battery") })

        executed.clear()
        val released = applyThermalTransition(
            transition = ThermalLimiterState.UNCAPPED,
            packageName = "com.example.game",
            userTargetFps = 90,
            downscaleRatio = "0.75",
            context = null
        )
        assertTrue(released)
        assertTrue(
            executed.contains(
                listOf("cmd", "game", "set", "--mode", "2", "--fps", "90", "--downscale", "0.75", "com.example.game")
            )
        )
        assertFalse(executed.contains(listOf("cmd", "game", "reset", "com.example.game")))
        assertEquals("90Hz", GameOptimizerEngine.pairedRefreshRate.value)
    }
}
