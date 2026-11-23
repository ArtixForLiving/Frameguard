package com.optimizer.game

import com.optimizer.game.optimizer.GameOptimizerEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RenderScaleUnitTest {

    @Test
    fun testComputeScaledMetrics100Percent() {
        val metrics = GameOptimizerEngine.computeScaledMetrics(
            scalePercent = 100,
            baseWidth = 1080,
            baseHeight = 2400,
            baseDpi = 420
        )
        assertEquals(1080, metrics.width)
        assertEquals(2400, metrics.height)
        assertEquals(420, metrics.dpi)
        assertEquals(100, metrics.pixelLoadPercent)
    }

    @Test
    fun testComputeScaledMetrics75PercentHwcEven() {
        val metrics = GameOptimizerEngine.computeScaledMetrics(
            scalePercent = 75,
            baseWidth = 1220,
            baseHeight = 2712,
            baseDpi = 450
        )
        // 1220 * 0.75 = 915 -> even integer is 914
        assertEquals(914, metrics.width)
        assertEquals(2034, metrics.height)
        assertEquals(338, metrics.dpi)
        assertEquals(56, metrics.pixelLoadPercent)
        assertTrue("Width must be even", metrics.width % 2 == 0)
        assertTrue("Height must be even", metrics.height % 2 == 0)
    }

    @Test
    fun testComputeScaledMetrics50PercentHwcEven() {
        val metrics = GameOptimizerEngine.computeScaledMetrics(
            scalePercent = 50,
            baseWidth = 1440,
            baseHeight = 3200,
            baseDpi = 560
        )
        assertEquals(720, metrics.width)
        assertEquals(1600, metrics.height)
        assertEquals(280, metrics.dpi)
        assertEquals(25, metrics.pixelLoadPercent)
        assertTrue("Width must be even", metrics.width % 2 == 0)
        assertTrue("Height must be even", metrics.height % 2 == 0)
    }

    @Test
    fun testComputeScaledMetricsDpiClamp() {
        val metrics = GameOptimizerEngine.computeScaledMetrics(
            scalePercent = 50,
            baseWidth = 720,
            baseHeight = 1600,
            baseDpi = 240
        )
        // 240 * 0.5 = 120 -> clamped to 160
        assertEquals(160, metrics.dpi)
    }

    @Test
    fun testSmallestWidthRemainsConstant() {
        val nativeMetrics = GameOptimizerEngine.computeScaledMetrics(100, 1080, 2400, 420)
        val scaledMetrics = GameOptimizerEngine.computeScaledMetrics(75, 1080, 2400, 420)

        val nativeSw = nativeMetrics.width / (nativeMetrics.dpi / 160f)
        val scaledSw = scaledMetrics.width / (scaledMetrics.dpi / 160f)

        // Difference must be negligible (< 2dp) and remain phone layout (< 600dp)
        assertTrue("Smallest width must stay below 600dp", scaledSw < 600f)
        assertTrue("Smallest width difference must be minimal", Math.abs(nativeSw - scaledSw) < 2f)
    }
}
