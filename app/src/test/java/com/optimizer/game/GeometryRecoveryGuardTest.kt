package com.optimizer.game

import com.optimizer.game.optimizer.GameOptimizerEngine
import com.optimizer.game.privilege.ShellResult
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class GeometryRecoveryGuardTest {

    @Before
    fun setUp() {
        GameOptimizerEngine.resetStateForTesting()
    }

    @After
    fun tearDown() {
        GameOptimizerEngine.resetStateForTesting()
    }

    @Test
    fun testApplyProfileAbortsScalingCleanlyWhenWmCaptureFails() = runBlocking {
        val executed = mutableListOf<List<String>>()
        GameOptimizerEngine.commandExecutor = { args ->
            executed.add(args)
            when (args) {
                listOf("wm", "size") -> ShellResult(exitCode = 1, stdout = "", stderr = "permission denied")
                listOf("wm", "density") -> ShellResult(exitCode = 0, stdout = "Physical density: 450", stderr = "")
                else -> ShellResult(exitCode = 0, stdout = "", stderr = "")
            }
        }

        GameOptimizerEngine.applyProfile(
            packageName = "com.example.game",
            targetFps = 60,
            renderScale = 75,
            ramTrim = false,
            dnd = false
        )

        assertNull(GameOptimizerEngine.activeSnapshot)
        assertEquals(100, GameOptimizerEngine.currentRenderScale)
        assertFalse(executed.any { it.size >= 3 && it[0] == "wm" && it[1] == "size" && it[2].contains("x") })
        assertFalse(executed.any { it.size >= 3 && it[0] == "wm" && it[1] == "density" })
    }

    @Test
    fun testExistingSnapshotIsNotOverwrittenOnReapply() = runBlocking {
        var currentSizeOut = "Physical size: 1220x2712"
        var currentDensityOut = "Physical density: 450"

        GameOptimizerEngine.commandExecutor = { args ->
            when {
                args == listOf("wm", "size") -> ShellResult(exitCode = 0, stdout = currentSizeOut, stderr = "")
                args == listOf("wm", "density") -> ShellResult(exitCode = 0, stdout = currentDensityOut, stderr = "")
                args.take(2) == listOf("wm", "size") -> {
                    currentSizeOut = "Physical size: 1220x2712\nOverride size: ${args[2]}"
                    ShellResult(exitCode = 0, stdout = "", stderr = "")
                }
                args.take(2) == listOf("wm", "density") -> {
                    currentDensityOut = "Physical density: 450\nOverride density: ${args[2]}"
                    ShellResult(exitCode = 0, stdout = "", stderr = "")
                }
                else -> ShellResult(exitCode = 0, stdout = "", stderr = "")
            }
        }

        GameOptimizerEngine.applyProfile(
            packageName = "com.example.game",
            targetFps = 60,
            renderScale = 75,
            ramTrim = false,
            dnd = false
        )
        val firstSnapshot = GameOptimizerEngine.activeSnapshot
        assertNotNull(firstSnapshot)
        assertNull(firstSnapshot!!.overrideWidth)

        // Second apply while already scaled must NOT overwrite baseline snapshot with Override size
        GameOptimizerEngine.applyProfile(
            packageName = "com.example.game",
            targetFps = 30,
            renderScale = 75,
            ramTrim = false,
            dnd = false
        )
        assertEquals(firstSnapshot, GameOptimizerEngine.activeSnapshot)
    }

    @Test
    fun testSnapshotRetainedWhenPostRestoreVerificationFailsAndClearedWhenVerified() = runBlocking {
        var overrideSizeLine = ""
        var overrideDensityLine = ""
        var allowWmReset = false

        GameOptimizerEngine.commandExecutor = { args ->
            when {
                args == listOf("wm", "size") -> ShellResult(
                    exitCode = 0,
                    stdout = "Physical size: 1220x2712$overrideSizeLine",
                    stderr = ""
                )
                args == listOf("wm", "density") -> ShellResult(
                    exitCode = 0,
                    stdout = "Physical density: 450$overrideDensityLine",
                    stderr = ""
                )
                args == listOf("wm", "size", "reset") -> {
                    if (allowWmReset) overrideSizeLine = ""
                    ShellResult(exitCode = 0, stdout = "", stderr = "")
                }
                args == listOf("wm", "density", "reset") -> {
                    if (allowWmReset) overrideDensityLine = ""
                    ShellResult(exitCode = 0, stdout = "", stderr = "")
                }
                args.size == 3 && args[0] == "wm" && args[1] == "size" -> {
                    overrideSizeLine = "\nOverride size: ${args[2]}"
                    ShellResult(exitCode = 0, stdout = "", stderr = "")
                }
                args.size == 3 && args[0] == "wm" && args[1] == "density" -> {
                    overrideDensityLine = "\nOverride density: ${args[2]}"
                    ShellResult(exitCode = 0, stdout = "", stderr = "")
                }
                else -> ShellResult(exitCode = 0, stdout = "", stderr = "")
            }
        }

        GameOptimizerEngine.applyProfile(
            packageName = "com.example.game",
            targetFps = 60,
            renderScale = 75,
            ramTrim = false,
            dnd = false
        )
        assertNotNull(GameOptimizerEngine.activeSnapshot)

        // Post-restore check still shows override -> snapshot must be retained
        assertFalse(GameOptimizerEngine.restoreDefaults("com.example.game"))
        assertNotNull(GameOptimizerEngine.activeSnapshot)

        // Now allow reset to actually clear overrides -> snapshot must be cleared
        allowWmReset = true
        assertTrue(GameOptimizerEngine.restoreDefaults("com.example.game"))
        assertNull(GameOptimizerEngine.activeSnapshot)
        assertEquals(100, GameOptimizerEngine.currentRenderScale)
    }
}
