package com.optimizer.game

import com.optimizer.game.optimizer.GameOptimizerEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AutoRotateUnitTest {

    @Test
    fun testParseAutoRotateStateAcceptsZeroAndOneOnly() {
        assertEquals("0", GameOptimizerEngine.parseAutoRotateState("0\n"))
        assertEquals("1", GameOptimizerEngine.parseAutoRotateState(" 1 "))
        assertNull(GameOptimizerEngine.parseAutoRotateState("null"))
        assertNull(GameOptimizerEngine.parseAutoRotateState("ERROR: permission denied"))
        assertNull(GameOptimizerEngine.parseAutoRotateState(""))
    }

    @Test
    fun testBuildRestoreAutoRotateCommandGeneratesExactSettingsPut() {
        assertEquals(
            "settings put system accelerometer_rotation 0",
            GameOptimizerEngine.buildRestoreAutoRotateCommand("0")
        )
        assertEquals(
            "settings put system accelerometer_rotation 1",
            GameOptimizerEngine.buildRestoreAutoRotateCommand("1")
        )
        assertNull(GameOptimizerEngine.buildRestoreAutoRotateCommand(null))
        assertNull(GameOptimizerEngine.buildRestoreAutoRotateCommand("invalid"))
    }
}
