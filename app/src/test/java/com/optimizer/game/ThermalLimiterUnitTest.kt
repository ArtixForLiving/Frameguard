package com.optimizer.game

import android.os.PowerManager
import com.optimizer.game.service.ThermalHysteresisEvaluator
import com.optimizer.game.service.ThermalLimiterState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ThermalLimiterUnitTest {

    private lateinit var evaluator: ThermalHysteresisEvaluator

    @Before
    fun setUp() {
        evaluator = ThermalHysteresisEvaluator()
    }

    @Test
    fun testDefaultPollIntervalRespectsHeadroomApiRateLimit() {
        assertTrue(evaluator.pollIntervalMs >= 10_000L)
        assertEquals(1.0f, evaluator.engageHeadroomThreshold, 0.001f)
    }

    @Test
    fun testTwoConsecutiveSevereCyclesTriggersCapped() {
        val result1 = evaluator.evaluate(
            currentStatus = PowerManager.THERMAL_STATUS_SEVERE,
            headroom = Float.NaN,
            currentState = ThermalLimiterState.UNCAPPED
        )
        assertNull(result1)

        val result2 = evaluator.evaluate(
            currentStatus = PowerManager.THERMAL_STATUS_SEVERE,
            headroom = Float.NaN,
            currentState = ThermalLimiterState.UNCAPPED
        )
        assertEquals(ThermalLimiterState.CAPPED_30FPS, result2)
    }

    @Test
    fun testModerateStatusAndSubUnityHeadroomDoNotTriggerPrematurely() {
        repeat(4) {
            assertNull(
                evaluator.evaluate(
                    currentStatus = PowerManager.THERMAL_STATUS_MODERATE,
                    headroom = 0.92f,
                    currentState = ThermalLimiterState.UNCAPPED
                )
            )
        }
    }

    @Test
    fun testSingleSevereFollowedByModerateResetsEngageCounter() {
        val result1 = evaluator.evaluate(
            currentStatus = PowerManager.THERMAL_STATUS_SEVERE,
            headroom = Float.NaN,
            currentState = ThermalLimiterState.UNCAPPED
        )
        assertNull(result1)

        val result2 = evaluator.evaluate(
            currentStatus = PowerManager.THERMAL_STATUS_MODERATE,
            headroom = 0.90f,
            currentState = ThermalLimiterState.UNCAPPED
        )
        assertNull(result2)

        val result3 = evaluator.evaluate(
            currentStatus = PowerManager.THERMAL_STATUS_SEVERE,
            headroom = Float.NaN,
            currentState = ThermalLimiterState.UNCAPPED
        )
        assertNull(result3)
    }

    @Test
    fun testAntiDeadlockReleasesOnModeratePlateauWithCoolHeadroom() {
        val result1 = evaluator.evaluate(
            currentStatus = PowerManager.THERMAL_STATUS_MODERATE,
            headroom = 0.70f,
            currentState = ThermalLimiterState.CAPPED_30FPS
        )
        assertNull(result1)

        val result2 = evaluator.evaluate(
            currentStatus = PowerManager.THERMAL_STATUS_MODERATE,
            headroom = 0.75f,
            currentState = ThermalLimiterState.CAPPED_30FPS
        )
        assertEquals(ThermalLimiterState.UNCAPPED, result2)
    }

    @Test
    fun testHotHeadroomAtModerateResetsReleaseCounter() {
        assertNull(
            evaluator.evaluate(
                currentStatus = PowerManager.THERMAL_STATUS_LIGHT,
                headroom = 0.50f,
                currentState = ThermalLimiterState.CAPPED_30FPS
            )
        )

        assertNull(
            evaluator.evaluate(
                currentStatus = PowerManager.THERMAL_STATUS_MODERATE,
                headroom = 0.95f,
                currentState = ThermalLimiterState.CAPPED_30FPS
            )
        )

        assertNull(
            evaluator.evaluate(
                currentStatus = PowerManager.THERMAL_STATUS_LIGHT,
                headroom = 0.50f,
                currentState = ThermalLimiterState.CAPPED_30FPS
            )
        )
    }

    @Test
    fun testHeadroomAtOrAboveSevereThresholdTriggersEngage() {
        val result1 = evaluator.evaluate(
            currentStatus = PowerManager.THERMAL_STATUS_NONE,
            headroom = 1.00f,
            currentState = ThermalLimiterState.UNCAPPED
        )
        assertNull(result1)

        val result2 = evaluator.evaluate(
            currentStatus = PowerManager.THERMAL_STATUS_NONE,
            headroom = 1.05f,
            currentState = ThermalLimiterState.UNCAPPED
        )
        assertEquals(ThermalLimiterState.CAPPED_30FPS, result2)
    }

    @Test
    fun testHeadroomNaNHandledSafely() {
        assertNull(
            evaluator.evaluate(
                currentStatus = PowerManager.THERMAL_STATUS_MODERATE,
                headroom = Float.NaN,
                currentState = ThermalLimiterState.UNCAPPED
            )
        )
        assertNull(
            evaluator.evaluate(
                currentStatus = PowerManager.THERMAL_STATUS_MODERATE,
                headroom = Float.NaN,
                currentState = ThermalLimiterState.UNCAPPED
            )
        )

        // Headroom NaN with status < SEVERE in CAPPED_30FPS state allows release after 2 cycles
        assertNull(
            evaluator.evaluate(
                currentStatus = PowerManager.THERMAL_STATUS_MODERATE,
                headroom = Float.NaN,
                currentState = ThermalLimiterState.CAPPED_30FPS
            )
        )
        assertEquals(
            ThermalLimiterState.UNCAPPED,
            evaluator.evaluate(
                currentStatus = PowerManager.THERMAL_STATUS_MODERATE,
                headroom = Float.NaN,
                currentState = ThermalLimiterState.CAPPED_30FPS
            )
        )
    }
}
