package com.optimizer.game

import com.optimizer.game.privilege.ShellResult
import com.optimizer.game.service.WatchdogCycleEvaluator
import com.optimizer.game.service.WatchdogDecision
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class WatchdogUnitTest {

    private lateinit var evaluator: WatchdogCycleEvaluator

    @Before
    fun setUp() {
        evaluator = WatchdogCycleEvaluator()
    }

    @Test
    fun testTwoConsecutiveEmptyChecksConfirmExit() {
        assertEquals(WatchdogDecision.MISSING_PENDING, evaluator.evaluate(""))
        assertEquals(1, evaluator.consecutiveEmptyCount)

        assertEquals(WatchdogDecision.EXIT_CONFIRMED, evaluator.evaluate("   "))
        assertEquals(2, evaluator.consecutiveEmptyCount)
    }

    @Test
    fun testSingleEmptyFollowedByAlivePidResetsCounter() {
        assertEquals(WatchdogDecision.MISSING_PENDING, evaluator.evaluate(""))
        assertEquals(1, evaluator.consecutiveEmptyCount)

        assertEquals(WatchdogDecision.ALIVE, evaluator.evaluate("12345"))
        assertEquals(0, evaluator.consecutiveEmptyCount)

        // Next empty check must restart at 1/2 instead of confirming immediately
        assertEquals(WatchdogDecision.MISSING_PENDING, evaluator.evaluate(""))
        assertEquals(1, evaluator.consecutiveEmptyCount)
    }

    @Test
    fun testErrorOutputTreatedAsGlitchWithoutIncrementingMissCount() {
        assertEquals(WatchdogDecision.MISSING_PENDING, evaluator.evaluate(""))
        assertEquals(1, evaluator.consecutiveEmptyCount)

        assertEquals(WatchdogDecision.GLITCH, evaluator.evaluate("ERROR: binder failed"))
        assertEquals(1, evaluator.consecutiveEmptyCount)
    }

    @Test
    fun testShellResultExitOneEmptyStdoutCountsTowardExitWhileTransportErrorIsGlitch() {
        assertEquals(
            WatchdogDecision.MISSING_PENDING,
            evaluator.evaluate(ShellResult(exitCode = 1, stdout = "", stderr = ""))
        )
        assertEquals(1, evaluator.consecutiveEmptyCount)

        assertEquals(
            WatchdogDecision.GLITCH,
            evaluator.evaluate(ShellResult(exitCode = -1, stdout = "", stderr = "", transportError = "daemon died"))
        )
        assertEquals(1, evaluator.consecutiveEmptyCount)

        assertEquals(
            WatchdogDecision.EXIT_CONFIRMED,
            evaluator.evaluate(ShellResult(exitCode = 1, stdout = "", stderr = ""))
        )
    }
}
