package com.optimizer.game

import com.optimizer.game.optimizer.GameOptimizerEngine
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class PackageSanitizerTest {

    @Before
    fun setUp() {
        GameOptimizerEngine.resetStateForTesting()
    }

    @After
    fun tearDown() {
        GameOptimizerEngine.resetStateForTesting()
    }

    @Test
    fun testValidPackageNamesAccepted() {
        assertTrue(GameOptimizerEngine.isValidPackageName("com.example.game"))
        assertTrue(GameOptimizerEngine.isValidPackageName("com.miHoYo.GenshinImpact"))
        assertTrue(GameOptimizerEngine.isValidPackageName("com.kurogame.wutheringwaves.global"))
        assertTrue(GameOptimizerEngine.isValidPackageName("a.b_1"))
    }

    @Test
    fun testInjectionPackageNamesRejected() {
        val malicious = listOf(
            "",
            "singleword",
            "1com.example.game",
            "com.example.game; id",
            "com.example.game && whoami",
            "com.example.game | cat",
            "com.example.game `id`",
            "com.example.game\nwhoami",
            "com.example.game\r",
            "com.example.game extra",
            "com.example..game"
        )
        for (pkg in malicious) {
            assertFalse("Expected rejection for '$pkg'", GameOptimizerEngine.isValidPackageName(pkg))
        }
    }

    @Test
    fun testParseSanitizedComponentAcceptsCleanAndRejectsMalicious() {
        val cleanResolve = """
            priority=0 preferredOrder=0 match=0x108000 specificIndex=-1 isDefault=true
            com.example.game/.MainActivity
        """.trimIndent()
        assertEquals("com.example.game/.MainActivity", GameOptimizerEngine.parseSanitizedComponent(cleanResolve))

        assertEquals(
            "com.example.game/com.example.game.Activity\$Inner",
            GameOptimizerEngine.parseSanitizedComponent("com.example.game/com.example.game.Activity\$Inner")
        )

        val maliciousOutputs = listOf(
            "",
            "No activity found",
            "com.example.game/.MainActivity; rm -rf /",
            "com.example.game/.MainActivity && id",
            "com.example.game/.MainActivity `id`",
            "com.example.game/.Main Activity",
            "badpkg/.MainActivity",
            "com.example.game/",
            "com.example.game/."
        )
        for (raw in maliciousOutputs) {
            assertNull("Expected null for '$raw'", GameOptimizerEngine.parseSanitizedComponent(raw))
        }
    }

    @Test
    fun testInvalidPackageAbortsExecutionWithoutShellCalls() = runBlocking {
        var calls = 0
        GameOptimizerEngine.commandExecutor = {
            calls++
            error("Command executor must not be called for invalid package")
        }

        assertFalse(GameOptimizerEngine.applyProfile(packageName = "com.bad;id"))
        assertFalse(GameOptimizerEngine.updateLiveGameConfig(packageName = "com.bad && id", targetFps = 60))
        assertFalse(GameOptimizerEngine.restoreDefaults(packageName = "com.bad\nwhoami"))
        assertEquals(0, calls)
    }
}
