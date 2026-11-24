package com.optimizer.game

import android.os.PowerManager
import com.optimizer.game.service.ThermalHysteresisEvaluator
import com.optimizer.game.service.ThermalLimiterState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class ThermalLimiterUnitTest {

    private lateinit var evaluator: ThermalHysteresisEvaluator

    @Before
    fun setUp() {
        evaluator = ThermalHysteresisEvaluator()
    }

    @Test
    fun testTwoConsecutiveModerateCyclesTriggersCapped() {
        // Cycle 1: Moderate thermal status, state is UNCAPPED -> null (waiting for 2nd cycle)
        val result1 = evaluator.evaluate(
            currentStatus = PowerManager.THERMAL_STATUS_MODERATE,
            headroom = Float.NaN,
            currentState = ThermalLimiterState.UNCAPPED
        )
        assertNull(result1)

        // Cycle 2: Moderate thermal status again -> transitions to CAPPED_30FPS
        val result2 = evaluator.evaluate(
            currentStatus = PowerManager.THERMAL_STATUS_MODERATE,
            headroom = Float.NaN,
            currentState = ThermalLimiterState.UNCAPPED
        )
        assertEquals(ThermalLimiterState.CAPPED_30FPS, result2)
    }

    @Test
    fun testSingleModerateFollowedByLightResetsEngageCounter() {
        // Cycle 1: Moderate thermal status -> count = 1
        val result1 = evaluator.evaluate(
            currentStatus = PowerManager.THERMAL_STATUS_MODERATE,
            headroom = Float.NaN,
            currentState = ThermalLimiterState.UNCAPPED
        )
        assertNull(result1)

        // Cycle 2: Drops back to LIGHT -> counter reset, remains UNCAPPED
        val result2 = evaluator.evaluate(
            currentStatus = PowerManager.THERMAL_STATUS_LIGHT,
            headroom = Float.NaN,
            currentState = ThermalLimiterState.UNCAPPED
        )
        assertNull(result2)

        // Cycle 3: Moderate thermal status again -> should be count = 1, NOT 2
        val result3 = evaluator.evaluate(
            currentStatus = PowerManager.THERMAL_STATUS_MODERATE,
            headroom = Float.NaN,
            currentState = ThermalLimiterState.UNCAPPED
        )
        assertNull(result3)
    }

    @Test
    fun testTwoConsecutiveLightCyclesTriggersUncapped() {
        // In CAPPED_30FPS state
        // Cycle 1: Light thermal status -> null (waiting for 2nd cycle)
        val result1 = evaluator.evaluate(
            currentStatus = PowerManager.THERMAL_STATUS_LIGHT,
            headroom = 0.50f,
            currentState = ThermalLimiterState.CAPPED_30FPS
        )
        assertNull(result1)

        // Cycle 2: Light thermal status again -> transitions to UNCAPPED
        val result2 = evaluator.evaluate(
            currentStatus = PowerManager.THERMAL_STATUS_LIGHT,
            headroom = 0.50f,
            currentState = ThermalLimiterState.CAPPED_30FPS
        )
        assertEquals(ThermalLimiterState.UNCAPPED, result2)
    }

    @Test
    fun testSingleLightFollowedByModerateResetsReleaseCounter() {
        // In CAPPED_30FPS state
        // Cycle 1: Light -> count = 1
        val result1 = evaluator.evaluate(
            currentStatus = PowerManager.THERMAL_STATUS_LIGHT,
            headroom = 0.50f,
            currentState = ThermalLimiterState.CAPPED_30FPS
        )
        assertNull(result1)

        // Cycle 2: Heats back to MODERATE -> counter reset, remains CAPPED_30FPS
        val result2 = evaluator.evaluate(
            currentStatus = PowerManager.THERMAL_STATUS_MODERATE,
            headroom = 0.85f,
            currentState = ThermalLimiterState.CAPPED_30FPS
        )
        assertNull(result2)

        // Cycle 3: Light again -> count = 1, NOT 2
        val result3 = evaluator.evaluate(
            currentStatus = PowerManager.THERMAL_STATUS_LIGHT,
            headroom = 0.50f,
            currentState = ThermalLimiterState.CAPPED_30FPS
        )
        assertNull(result3)
    }

    @Test
    fun testHeadroomThresholdTriggersEngage() {
        // Even if status is NONE (0), headroom >= 0.90f for 2 cycles should trigger CAPPED_30FPS
        val result1 = evaluator.evaluate(
            currentStatus = PowerManager.THERMAL_STATUS_NONE,
            headroom = 0.92f,
            currentState = ThermalLimiterState.UNCAPPED
        )
        assertNull(result1)

        val result2 = evaluator.evaluate(
            currentStatus = PowerManager.THERMAL_STATUS_NONE,
            headroom = 0.95f,
            currentState = ThermalLimiterState.UNCAPPED
        )
        assertEquals(ThermalLimiterState.CAPPED_30FPS, result2)
    }

    @Test
    fun testHeadroomNaNHandledSafely() {
        // Headroom NaN with status NONE should NOT trigger engage
        val result1 = evaluator.evaluate(
            currentStatus = PowerManager.THERMAL_STATUS_NONE,
            headroom = Float.NaN,
            currentState = ThermalLimiterState.UNCAPPED
        )
        assertNull(result1)

        val result2 = evaluator.evaluate(
            currentStatus = PowerManager.THERMAL_STATUS_NONE,
            headroom = Float.NaN,
            currentState = ThermalLimiterState.UNCAPPED
        )
        assertNull(result2)

        // Headroom NaN with status LIGHT in CAPPED_30FPS state SHOULD allow release
        val result3 = evaluator.evaluate(
            currentStatus = PowerManager.THERMAL_STATUS_LIGHT,
            headroom = Float.NaN,
            currentState = ThermalLimiterState.CAPPED_30FPS
        )
        assertNull(result3)

        val result4 = evaluator.evaluate(
            currentStatus = PowerManager.THERMAL_STATUS_LIGHT,
            headroom = Float.NaN,
            currentState = ThermalLimiterState.CAPPED_30FPS
        )
        assertEquals(ThermalLimiterState.UNCAPPED, result4)
    }
}
