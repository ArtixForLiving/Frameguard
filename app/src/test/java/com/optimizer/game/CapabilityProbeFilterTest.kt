package com.optimizer.game

import com.optimizer.game.privilege.CapabilityProbe
import com.optimizer.game.privilege.ProbeStatus
import com.optimizer.game.privilege.ShellResult
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CapabilityProbeFilterTest {

    @After
    fun tearDown() {
        CapabilityProbe.resetForTesting()
    }

    @Test
    fun `filterSupportedFpsOptions maps 60Hz, 90Hz, and 120Hz+ panels accurately`() {
        assertEquals(listOf(30, 45, 60), CapabilityProbe.filterSupportedFpsOptions(60))
        assertEquals(listOf(30, 45, 60), CapabilityProbe.filterSupportedFpsOptions(48))
        assertEquals(listOf(60, 90), CapabilityProbe.filterSupportedFpsOptions(90))
        assertEquals(listOf(60, 90), CapabilityProbe.filterSupportedFpsOptions(75))
        assertEquals(listOf(60, 90, 120), CapabilityProbe.filterSupportedFpsOptions(120))
        assertEquals(listOf(60, 90, 120), CapabilityProbe.filterSupportedFpsOptions(144))
    }

    @Test
    fun `computeHiddenModules hides downscale on API under 33 and 90-120 on 60Hz`() {
        val hidden = CapabilityProbe.computeHiddenModules(
            apiLevel = 31,
            maxHz = 60,
            supportsWmWrite = true,
            supportsDnd = true
        )
        assertEquals(2, hidden.size)
        assertTrue(hidden.any { it.name == "PER-APP GAME DOWNSCALE" })
        assertTrue(hidden.any { it.name == "90 / 120 FPS CAP OPTIONS" })
    }

    @Test
    fun `computeHiddenModules hides experimental WM scale and DND when blocked`() {
        val hidden = CapabilityProbe.computeHiddenModules(
            apiLevel = 35,
            maxHz = 120,
            supportsWmWrite = false,
            supportsDnd = false
        )
        assertEquals(2, hidden.size)
        assertTrue(hidden.any { it.name == "WM RENDER SCALE (EXPERIMENTAL)" })
        assertTrue(hidden.any { it.name == "AUTO GAMING DND" })
    }

    @Test
    fun `computeHiddenModules returns empty list when all capabilities are supported`() {
        val hidden = CapabilityProbe.computeHiddenModules(
            apiLevel = 36,
            maxHz = 120,
            supportsWmWrite = true,
            supportsDnd = true
        )
        assertTrue(hidden.isEmpty())
    }

    @Test
    fun `isCacheValidForFingerprint validates exact non-blank fingerprint match`() {
        val fp = "motorola/moto/36:16/BP2A.250605/123:user/release-keys"
        assertTrue(CapabilityProbe.isCacheValidForFingerprint(fp, fp))
        assertFalse(CapabilityProbe.isCacheValidForFingerprint(null, fp))
        assertFalse(CapabilityProbe.isCacheValidForFingerprint("", fp))
        assertFalse(CapabilityProbe.isCacheValidForFingerprint("   ", fp))
        assertFalse(CapabilityProbe.isCacheValidForFingerprint("other/fingerprint", fp))
    }

    @Test
    fun `runProbe detects HyperOS SecurityException on wm write and OEM daemon`() = runBlocking {
        CapabilityProbe.commandExecutor = { args ->
            when {
                args == listOf("id", "-u") -> ShellResult(0, "2000", "")
                args == listOf("wm", "size") -> ShellResult(0, "Physical size: 1200x2670", "")
                args == listOf("wm", "size", "reset") ->
                    ShellResult(255, "", "java.lang.SecurityException: Permission denial")
                args.take(3) == listOf("settings", "get", "system") -> ShellResult(0, "0", "")
                args.take(3) == listOf("settings", "put", "system") ->
                    ShellResult(255, "", "java.lang.SecurityException: WRITE_SETTINGS denied")
                args.take(3) == listOf("cmd", "game", "list-modes") -> ShellResult(0, "Mode 1", "")
                args == listOf("dumpsys", "thermalservice") -> ShellResult(
                    0,
                    "Current temperatures from HAL:\nTemperature{mValue=36.4, mType=3, mName=SKIN, mStatus=0}",
                    ""
                )
                args == listOf("dumpsys", "display") -> ShellResult(0, "Supported modes: 120.0 fps", "")
                args == listOf("settings", "get", "global", "zen_mode") -> ShellResult(0, "0", "")
                args.take(3) == listOf("cmd", "notification", "set_dnd") -> ShellResult(0, "", "")
                args == listOf("pm", "list", "packages") -> ShellResult(
                    0,
                    "package:com.android.settings\npackage:com.xiaomi.joyose\n",
                    ""
                )
                else -> ShellResult(0, "", "")
            }
        }

        val (profile, steps) = CapabilityProbe.runProbe(
            context = null,
            targetPackage = "com.digitalextremes.warframemobile",
            fingerprint = "xiaomi/hyperos/test",
            apiLevel = 35
        )

        assertFalse(profile.supportsWmWrite)
        assertFalse(profile.supportsSettingsWrite)
        assertTrue(profile.supportsGameManager)
        assertTrue(profile.supportsThermalHal)
        assertTrue(profile.supportsDnd)
        assertEquals(listOf("com.xiaomi.joyose"), profile.detectedOemDaemons)
        assertTrue(profile.hiddenModules.any { it.name == "WM RENDER SCALE (EXPERIMENTAL)" })
        assertEquals(5, steps.size)
        assertEquals(ProbeStatus.PASS, steps[0].status)
        assertEquals(ProbeStatus.WARN, steps[1].status)
        assertEquals(ProbeStatus.PASS, steps[2].status)
        assertEquals(ProbeStatus.PASS, steps[3].status)
        assertEquals(ProbeStatus.WARN, steps[4].status)
    }
}
