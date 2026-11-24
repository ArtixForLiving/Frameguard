package com.optimizer.game

import com.optimizer.game.optimizer.GameOptimizerEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DisplayGeometryUnitTest {

    @Test
    fun testParseStandardPhysicalGeometryWithoutOverrides() {
        val sizeOutput = "Physical size: 1080x2400"
        val densityOutput = "Physical density: 440"

        val snapshot = GameOptimizerEngine.parseWmGeometry(sizeOutput, densityOutput)

        assertEquals(1080, snapshot.physicalWidth)
        assertEquals(2400, snapshot.physicalHeight)
        assertEquals(440, snapshot.physicalDpi)
        assertNull(snapshot.overrideWidth)
        assertNull(snapshot.overrideHeight)
        assertNull(snapshot.overrideDpi)

        val restoreCommands = GameOptimizerEngine.buildRestoreCommands(snapshot)
        assertEquals(2, restoreCommands.size)
        assertTrue(restoreCommands.contains("wm size reset"))
        assertTrue(restoreCommands.contains("wm density reset"))
    }

    @Test
    fun testParseAndRestoreCustomOverrideDensity() {
        val sizeOutput = "Physical size: 1080x2400"
        val densityOutput = "Physical density: 440\nOverride density: 209"

        val snapshot = GameOptimizerEngine.parseWmGeometry(sizeOutput, densityOutput)

        assertEquals(1080, snapshot.physicalWidth)
        assertEquals(2400, snapshot.physicalHeight)
        assertEquals(440, snapshot.physicalDpi)
        assertNull(snapshot.overrideWidth)
        assertNull(snapshot.overrideHeight)
        assertEquals(209, snapshot.overrideDpi)

        val restoreCommands = GameOptimizerEngine.buildRestoreCommands(snapshot)
        assertEquals(2, restoreCommands.size)
        assertTrue(restoreCommands.contains("wm size reset"))
        assertTrue(restoreCommands.contains("wm density 209"))
        assertFalse(restoreCommands.contains("wm density reset"))
    }

    @Test
    fun testParseAndRestoreCustomOverrideSize() {
        val sizeOutput = "Physical size: 1080x2400\nOverride size: 720x1600"
        val densityOutput = "Physical density: 440"

        val snapshot = GameOptimizerEngine.parseWmGeometry(sizeOutput, densityOutput)

        assertEquals(1080, snapshot.physicalWidth)
        assertEquals(2400, snapshot.physicalHeight)
        assertEquals(440, snapshot.physicalDpi)
        assertEquals(720, snapshot.overrideWidth)
        assertEquals(1600, snapshot.overrideHeight)
        assertNull(snapshot.overrideDpi)

        val restoreCommands = GameOptimizerEngine.buildRestoreCommands(snapshot)
        assertEquals(2, restoreCommands.size)
        assertTrue(restoreCommands.contains("wm size 720x1600"))
        assertFalse(restoreCommands.contains("wm size reset"))
        assertTrue(restoreCommands.contains("wm density reset"))
    }

    @Test
    fun testParseAndRestoreBothCustomOverrides() {
        val sizeOutput = "Physical size: 1080x2400\nOverride size: 720x1600"
        val densityOutput = "Physical density: 440\nOverride density: 209"

        val snapshot = GameOptimizerEngine.parseWmGeometry(sizeOutput, densityOutput)

        assertEquals(720, snapshot.overrideWidth)
        assertEquals(1600, snapshot.overrideHeight)
        assertEquals(209, snapshot.overrideDpi)

        val restoreCommands = GameOptimizerEngine.buildRestoreCommands(snapshot)
        assertEquals(listOf("wm size 720x1600", "wm density 209"), restoreCommands)
    }

    @Test
    fun testBuildRestoreCommandsNullBypassesWmCommands() {
        val restoreCommands = GameOptimizerEngine.buildRestoreCommands(null)
        assertTrue("Null snapshot on 100% native scale must return empty command list", restoreCommands.isEmpty())
    }
}
