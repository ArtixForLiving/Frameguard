package com.optimizer.game

import com.optimizer.game.privilege.ShellResult
import com.optimizer.game.service.BoostSessionService
import com.optimizer.game.service.ClosedLoopStatus
import com.optimizer.game.service.WatchdogCycleEvaluator
import com.optimizer.game.service.WatchdogDecision
import com.optimizer.game.service.evaluateSurfaceFlingerAudit
import com.optimizer.game.service.parseProcessAndForegroundState
import com.optimizer.game.service.resolveTerminalClosedLoopStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WatchdogForegroundTest {

    @Test
    fun testWatchdogGracePeriodIsAtLeastFifteenSeconds() {
        assertTrue(BoostSessionService.WATCHDOG_GRACE_MS >= 15_000L)
    }

    @Test
    fun testMatchesSubprocessesAndRejectsPrefixCollisions() {
        val psOutput = """
              PID NAME
             1042 com.android.systemui
             4510 com.example.game:unity
             4511 com.example.game:UE4
             7890 com.example.game.helper
        """.trimIndent()
        val activitiesOutput = """
            Display #0 (activities from top to bottom):
              topResumedActivity=ActivityRecord{11ab u0 com.example.game/com.unity3d.player.UnityPlayerActivity t42}
        """.trimIndent()

        val state = parseProcessAndForegroundState(psOutput, activitiesOutput, "com.example.game")
        assertTrue(state.isProcessAlive)
        assertTrue(state.isForeground)

        val collisionOnlyPs = """
              PID NAME
             7890 com.example.game.helper
        """.trimIndent()
        val collisionState = parseProcessAndForegroundState(collisionOnlyPs, activitiesOutput, "com.example.game")
        assertFalse(collisionState.isProcessAlive)
    }

    @Test
    fun testForegroundDetectionWithTopResumedAndMResumedFallback() {
        val psAlive = "  PID NAME\n 4510 com.example.game\n"

        val launcherTop = """
            topResumedActivity=ActivityRecord{22cd u0 com.motorola.launcher3/.Launcher t1}
            mResumedActivity: ActivityRecord{11ab u0 com.example.game/.MainActivity t42}
        """.trimIndent()
        assertFalse(parseProcessAndForegroundState(psAlive, launcherTop, "com.example.game").isForeground)

        val mResumedOnly = "  mResumedActivity: ActivityRecord{11ab u0 com.example.game/.MainActivity t42}"
        assertTrue(parseProcessAndForegroundState(psAlive, mResumedOnly, "com.example.game").isForeground)

        val cloneTop = "topResumedActivity=ActivityRecord{33ef u0 com.example.game.clone/.MainActivity t99}"
        assertFalse(parseProcessAndForegroundState(psAlive, cloneTop, "com.example.game").isForeground)
    }

    @Test
    fun testBackgroundTimeoutTriggersAtTwentySecondsAndResetsOnForeground() {
        val evaluator = WatchdogCycleEvaluator()
        val psAlive = ShellResult(exitCode = 0, stdout = "  PID NAME\n 4510 com.example.game\n", stderr = "")
        val fgDump = ShellResult(
            exitCode = 0,
            stdout = "topResumedActivity=ActivityRecord{11ab u0 com.example.game/.MainActivity t42}",
            stderr = ""
        )
        val bgDump = ShellResult(
            exitCode = 0,
            stdout = "topResumedActivity=ActivityRecord{22cd u0 com.motorola.launcher3/.Launcher t1}",
            stderr = ""
        )

        assertEquals(WatchdogDecision.ALIVE, evaluator.evaluateState(psAlive, fgDump, "com.example.game", nowMs = 1_000L))
        assertEquals(WatchdogDecision.ALIVE, evaluator.evaluateState(psAlive, bgDump, "com.example.game", nowMs = 10_000L))
        assertEquals(WatchdogDecision.ALIVE, evaluator.evaluateState(psAlive, bgDump, "com.example.game", nowMs = 25_000L))

        // Return to foreground before 20s clears background timer
        assertEquals(WatchdogDecision.ALIVE, evaluator.evaluateState(psAlive, fgDump, "com.example.game", nowMs = 28_000L))
        assertEquals(WatchdogDecision.ALIVE, evaluator.evaluateState(psAlive, bgDump, "com.example.game", nowMs = 30_000L))
        assertEquals(WatchdogDecision.ALIVE, evaluator.evaluateState(psAlive, bgDump, "com.example.game", nowMs = 49_999L))

        // Transport glitch must not trigger timeout
        val glitchRes = ShellResult(exitCode = -1, stdout = "", stderr = "", transportError = "binder died")
        assertEquals(WatchdogDecision.GLITCH, evaluator.evaluateState(glitchRes, bgDump, "com.example.game", nowMs = 55_000L))

        // Continuous background >= 20_000ms confirms BACKGROUND_TIMEOUT
        assertEquals(
            WatchdogDecision.BACKGROUND_TIMEOUT,
            evaluator.evaluateState(psAlive, bgDump, "com.example.game", nowMs = 50_000L)
        )
    }

    @Test
    fun testEvaluateSurfaceFlingerAuditIgnoresInstantaneousLtpoVsyncRate() {
        val compliantGameDump = "[Name:com.example.game Modes:{2=[Game Mode:2, Scaling:0.75, Use Angle:false, Fps:60]}]"

        // Instantaneous vsyncRate dropped to 24 Hz (static LTPO frame) or spiked to 120 Hz, while primaryRanges max is 60 Hz
        val ltpoStatic24Sf = """
            activeMode={id=3, vsyncRate=24.00 Hz}
            displayManagerPolicy={defaultMode=3, primaryRanges={physical=[24.00 Hz, 60.00 Hz], render=[24.00 Hz, 60.00 Hz]}}
        """.trimIndent()
        assertTrue(
            evaluateSurfaceFlingerAudit(
                sfOutput = ltpoStatic24Sf,
                gameOutput = compliantGameDump,
                packageName = "com.example.game",
                expectedFps = 60,
                expectedMaxHz = 60f,
                expectedDownscale = "0.75"
            )
        )

        val ltpoSpike120VsyncSf = """
            activeMode={id=3, vsyncRate=120.00 Hz}
            renderFrameRate 120.0
            displayManagerPolicy={defaultMode=3, primaryRanges={physical=[24.00 Hz, 60.00 Hz], render=[24.00 Hz, 60.00 Hz]}}
        """.trimIndent()
        assertTrue(
            evaluateSurfaceFlingerAudit(
                sfOutput = ltpoSpike120VsyncSf,
                gameOutput = compliantGameDump,
                packageName = "com.example.game",
                expectedFps = 60,
                expectedMaxHz = 60f,
                expectedDownscale = "0.75"
            )
        )

        // True OEM bypass: primaryRanges max unlocked to 120 Hz
        val oemBypassSf = """
            activeMode={id=1, vsyncRate=60.00 Hz}
            displayManagerPolicy={defaultMode=1, primaryRanges={physical=[24.00 Hz, 120.00 Hz], render=[24.00 Hz, 120.00 Hz]}}
        """.trimIndent()
        assertFalse(
            evaluateSurfaceFlingerAudit(
                sfOutput = oemBypassSf,
                gameOutput = compliantGameDump,
                packageName = "com.example.game",
                expectedFps = 60,
                expectedMaxHz = 60f,
                expectedDownscale = "0.75"
            )
        )

        // True OEM bypass: GameManager Mode 2 FPS overwritten to 120
        val bypassedGameDump = "[Name:com.example.game Modes:{2=[Game Mode:2, Scaling:0.75, Use Angle:false, Fps:120]}]"
        assertFalse(
            evaluateSurfaceFlingerAudit(
                sfOutput = ltpoStatic24Sf,
                gameOutput = bypassedGameDump,
                packageName = "com.example.game",
                expectedFps = 60,
                expectedMaxHz = 60f,
                expectedDownscale = "0.75"
            )
        )
    }

    @Test
    fun testResolveTerminalClosedLoopStatusPreservesDegradedAcrossStopAndDestroy() {
        assertEquals(ClosedLoopStatus.DEGRADED, resolveTerminalClosedLoopStatus(ClosedLoopStatus.DEGRADED))
        assertEquals(ClosedLoopStatus.IDLE, resolveTerminalClosedLoopStatus(ClosedLoopStatus.SYNC_LOCKED))
        assertEquals(ClosedLoopStatus.IDLE, resolveTerminalClosedLoopStatus(ClosedLoopStatus.OEM_BYPASS))
        assertEquals(ClosedLoopStatus.IDLE, resolveTerminalClosedLoopStatus(ClosedLoopStatus.THERMAL_30))
        assertEquals(ClosedLoopStatus.IDLE, resolveTerminalClosedLoopStatus(ClosedLoopStatus.IDLE))
    }
}
