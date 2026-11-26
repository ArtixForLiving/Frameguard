package com.optimizer.game.optimizer

import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.os.Build
import android.view.Display
import com.optimizer.game.privilege.PrivilegeManager
import com.optimizer.game.privilege.ShellResult
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Utilitarian OS-level game optimizer executing ADB shell directives via PrivilegeManager (UID 2000).
 * Strictly operates at system settings/binder level without modifying game files or injecting code.
 */
object GameOptimizerEngine {

    const val DEFAULT_PACKAGE = "com.digitalextremes.warframemobile"
    val VALID_DOWNSCALE_RATIOS = setOf("1.0", "0.85", "0.75", "0.6")

    private const val PREFS_GEOMETRY = "frameguard_geometry_snapshot"
    private const val KEY_HAS_SNAPSHOT = "has_snapshot"
    private const val KEY_PHYSICAL_WIDTH = "physical_width"
    private const val KEY_PHYSICAL_HEIGHT = "physical_height"
    private const val KEY_PHYSICAL_DPI = "physical_dpi"
    private const val KEY_OVERRIDE_WIDTH = "override_width"
    private const val KEY_OVERRIDE_HEIGHT = "override_height"
    private const val KEY_OVERRIDE_DPI = "override_dpi"
    private const val KEY_ACTIVE_PACKAGE = "active_package"

    private val PACKAGE_NAME_REGEX = Regex("""\A[a-zA-Z][a-zA-Z0-9_]*(\.[a-zA-Z0-9_]+)+\z""")
    private val COMPONENT_NAME_REGEX = Regex("""\A[a-zA-Z][a-zA-Z0-9_]*(\.[a-zA-Z0-9_]+)+/\.?[a-zA-Z0-9_$]+(\.[a-zA-Z0-9_$]+)*\z""")
    private val PHYSICAL_SIZE_REGEX = Regex("""Physical size:\s*(\d+)x(\d+)""", RegexOption.IGNORE_CASE)
    private val OVERRIDE_SIZE_REGEX = Regex("""Override size:\s*(\d+)x(\d+)""", RegexOption.IGNORE_CASE)
    private val PHYSICAL_DENSITY_REGEX = Regex("""Physical density:\s*(\d+)""", RegexOption.IGNORE_CASE)
    private val OVERRIDE_DENSITY_REGEX = Regex("""Override density:\s*(\d+)""", RegexOption.IGNORE_CASE)

    internal var commandExecutor: suspend (List<String>) -> ShellResult = { args ->
        PrivilegeManager.execArgs(args)
    }

    var activeSnapshot: DisplayGeometrySnapshot? = null
        private set

    data class DisplayGeometrySnapshot(
        val physicalWidth: Int,
        val physicalHeight: Int,
        val physicalDpi: Int,
        val overrideWidth: Int? = null,
        val overrideHeight: Int? = null,
        val overrideDpi: Int? = null
    )

    internal fun isValidPackageName(pkg: String): Boolean {
        if (pkg.isEmpty() || pkg.contains('\n') || pkg.contains('\r')) return false
        return PACKAGE_NAME_REGEX.matches(pkg)
    }

    internal fun parseSanitizedComponent(rawOutput: String): String? {
        return rawOutput.lineSequence()
            .map { it.trim() }
            .firstOrNull { line ->
                line.isNotEmpty() && COMPONENT_NAME_REGEX.matches(line)
            }
    }

    fun parseWmGeometry(sizeOutput: String, densityOutput: String): DisplayGeometrySnapshot? {
        val physicalSizeMatch = PHYSICAL_SIZE_REGEX.find(sizeOutput) ?: return null
        val physicalDensityMatch = PHYSICAL_DENSITY_REGEX.find(densityOutput) ?: return null

        val pWidth = physicalSizeMatch.groupValues[1].toIntOrNull() ?: return null
        val pHeight = physicalSizeMatch.groupValues[2].toIntOrNull() ?: return null
        val pDpi = physicalDensityMatch.groupValues[1].toIntOrNull() ?: return null
        if (pWidth <= 0 || pHeight <= 0 || pDpi <= 0) return null

        val overrideSizeMatch = OVERRIDE_SIZE_REGEX.find(sizeOutput)
        val overrideDensityMatch = OVERRIDE_DENSITY_REGEX.find(densityOutput)

        val oWidth = overrideSizeMatch?.groupValues?.get(1)?.toIntOrNull()
        val oHeight = overrideSizeMatch?.groupValues?.get(2)?.toIntOrNull()
        val oDpi = overrideDensityMatch?.groupValues?.get(1)?.toIntOrNull()

        return DisplayGeometrySnapshot(
            physicalWidth = pWidth,
            physicalHeight = pHeight,
            physicalDpi = pDpi,
            overrideWidth = oWidth,
            overrideHeight = oHeight,
            overrideDpi = oDpi
        )
    }

    internal fun buildRestoreCommandArgs(snapshot: DisplayGeometrySnapshot?): List<List<String>> {
        if (snapshot == null) return emptyList()

        val commands = mutableListOf<List<String>>()
        if (snapshot.overrideWidth != null && snapshot.overrideHeight != null) {
            commands.add(listOf("wm", "size", "${snapshot.overrideWidth}x${snapshot.overrideHeight}"))
        } else {
            commands.add(listOf("wm", "size", "reset"))
        }

        if (snapshot.overrideDpi != null) {
            commands.add(listOf("wm", "density", snapshot.overrideDpi.toString()))
        } else {
            commands.add(listOf("wm", "density", "reset"))
        }

        return commands
    }

    fun buildRestoreCommands(snapshot: DisplayGeometrySnapshot?): List<String> =
        buildRestoreCommandArgs(snapshot).map { it.joinToString(" ") }

    internal fun verifyWmRestored(
        baseline: DisplayGeometrySnapshot,
        sizeOutput: String,
        densityOutput: String
    ): Boolean {
        val current = parseWmGeometry(sizeOutput, densityOutput) ?: return false
        val sizeMatches = if (baseline.overrideWidth != null && baseline.overrideHeight != null) {
            current.overrideWidth == baseline.overrideWidth && current.overrideHeight == baseline.overrideHeight
        } else {
            current.overrideWidth == null && current.overrideHeight == null
        }
        val densityMatches = if (baseline.overrideDpi != null) {
            current.overrideDpi == baseline.overrideDpi
        } else {
            current.overrideDpi == null
        }
        return sizeMatches && densityMatches
    }

    fun saveGeometrySnapshot(context: Context, snapshot: DisplayGeometrySnapshot, activePackage: String) {
        val prefs = context.getSharedPreferences(PREFS_GEOMETRY, Context.MODE_PRIVATE)
        prefs.edit()
            .putBoolean(KEY_HAS_SNAPSHOT, true)
            .putInt(KEY_PHYSICAL_WIDTH, snapshot.physicalWidth)
            .putInt(KEY_PHYSICAL_HEIGHT, snapshot.physicalHeight)
            .putInt(KEY_PHYSICAL_DPI, snapshot.physicalDpi)
            .apply {
                if (snapshot.overrideWidth != null) putInt(KEY_OVERRIDE_WIDTH, snapshot.overrideWidth) else remove(KEY_OVERRIDE_WIDTH)
                if (snapshot.overrideHeight != null) putInt(KEY_OVERRIDE_HEIGHT, snapshot.overrideHeight) else remove(KEY_OVERRIDE_HEIGHT)
                if (snapshot.overrideDpi != null) putInt(KEY_OVERRIDE_DPI, snapshot.overrideDpi) else remove(KEY_OVERRIDE_DPI)
            }
            .putString(KEY_ACTIVE_PACKAGE, activePackage)
            .commit()
    }

    fun loadGeometrySnapshot(context: Context): DisplayGeometrySnapshot? {
        val prefs = context.getSharedPreferences(PREFS_GEOMETRY, Context.MODE_PRIVATE)
        if (!prefs.getBoolean(KEY_HAS_SNAPSHOT, false)) {
            return null
        }
        val pw = prefs.getInt(KEY_PHYSICAL_WIDTH, -1)
        val ph = prefs.getInt(KEY_PHYSICAL_HEIGHT, -1)
        val pd = prefs.getInt(KEY_PHYSICAL_DPI, -1)
        if (pw <= 0 || ph <= 0 || pd <= 0) {
            return null
        }
        val ow = if (prefs.contains(KEY_OVERRIDE_WIDTH)) prefs.getInt(KEY_OVERRIDE_WIDTH, 0) else null
        val oh = if (prefs.contains(KEY_OVERRIDE_HEIGHT)) prefs.getInt(KEY_OVERRIDE_HEIGHT, 0) else null
        val od = if (prefs.contains(KEY_OVERRIDE_DPI)) prefs.getInt(KEY_OVERRIDE_DPI, 0) else null
        return DisplayGeometrySnapshot(
            physicalWidth = pw,
            physicalHeight = ph,
            physicalDpi = pd,
            overrideWidth = ow,
            overrideHeight = oh,
            overrideDpi = od
        )
    }

    fun clearGeometrySnapshot(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_GEOMETRY, Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
    }

    fun getActiveSessionPackage(context: Context): String? {
        val prefs = context.getSharedPreferences(PREFS_GEOMETRY, Context.MODE_PRIVATE)
        return prefs.getString(KEY_ACTIVE_PACKAGE, null)
    }

    // Cached display refresh rates before applying profile (null means unset / "null")
    private var cachedPeakRefreshRate: String? = null
    private var cachedMinRefreshRate: String? = null
    private var hasCachedRates = false
    private var cachedCurrentPeakHz: Float? = null
    private var didEnableDnd = false

    var dndCapabilityAllowed: Boolean = true

    var currentPackage: String = DEFAULT_PACKAGE
        private set

    var currentFps: Int = 45
        private set

    var currentDoze: Boolean = true
        private set

    var currentRenderScale: Int = 100
        private set

    var currentDownscaleRatio: String = "1.0"
        private set

    var currentRamTrim: Boolean = true
        private set

    var currentDnd: Boolean = true
        private set

    private val _pairedRefreshRate = MutableStateFlow("120Hz")
    val pairedRefreshRate: StateFlow<String> = _pairedRefreshRate.asStateFlow()

    // Shared stream for live shell execution logs displayed in the terminal UI
    private val _shellLogs = MutableSharedFlow<ShellLogEntry>(extraBufferCapacity = 64)
    val shellLogs: SharedFlow<ShellLogEntry> = _shellLogs.asSharedFlow()

    data class ShellLogEntry(
        val message: String,
        val type: LogType,
        val timestamp: Long = System.currentTimeMillis()
    )

    enum class LogType {
        COMMAND, // $ cmd
        STDOUT,  // > stdout
        ERROR,   // ! stderr
        INFO     // # info
    }

    data class ScaledDisplayMetrics(
        val width: Int,
        val height: Int,
        val dpi: Int,
        val pixelLoadPercent: Int
    )

    fun computeScaledMetrics(
        scalePercent: Int,
        baseWidth: Int,
        baseHeight: Int,
        baseDpi: Int
    ): ScaledDisplayMetrics {
        if (scalePercent >= 100) {
            return ScaledDisplayMetrics(baseWidth, baseHeight, baseDpi, 100)
        }
        val factor = scalePercent / 100f
        val scaledWidth = ((baseWidth * factor).toInt() / 2) * 2
        val scaledHeight = ((baseHeight * factor).toInt() / 2) * 2
        val scaledDpi = Math.round(baseDpi * factor).coerceIn(160, 640)
        val pixelLoad = Math.round(factor * factor * 100f)
        return ScaledDisplayMetrics(scaledWidth, scaledHeight, scaledDpi, pixelLoad)
    }

    suspend fun log(message: String, type: LogType) {
        _shellLogs.emit(ShellLogEntry(message, type))
    }

    fun setTargetPackage(packageName: String) {
        if (isValidPackageName(packageName)) {
            currentPackage = packageName
        }
    }

    fun setTargetFps(fps: Int) {
        currentFps = fps
    }

    fun setDozeEnabled(enabled: Boolean) {
        currentDoze = enabled
    }

    fun setDownscaleRatio(ratio: String) {
        currentDownscaleRatio = normalizeDownscaleRatio(ratio)
    }

    fun setRamTrimEnabled(enabled: Boolean) {
        currentRamTrim = enabled
    }

    fun setDndEnabled(enabled: Boolean) {
        currentDnd = enabled
    }

    fun setPairedHz(hz: String) {
        _pairedRefreshRate.value = hz
    }

    internal fun normalizeDownscaleRatio(downscaleRatio: String): String =
        if (downscaleRatio in VALID_DOWNSCALE_RATIOS) downscaleRatio else "1.0"

    internal fun buildGameModeSetArgs(
        packageName: String,
        targetFps: Int,
        downscaleRatio: String,
        useDisableForNative: Boolean = true
    ): List<String> {
        val normalized = normalizeDownscaleRatio(downscaleRatio)
        val downscaleArg = if (normalized == "1.0" && useDisableForNative) "disable" else normalized
        return listOf(
            "cmd", "game", "set",
            "--mode", "2",
            "--fps", targetFps.toString(),
            "--downscale", downscaleArg,
            packageName
        )
    }

    private fun extractGamePackageBlock(dumpsysOutput: String, packageName: String): String? {
        val prefix = "[Name:$packageName"
        var searchFrom = 0
        while (true) {
            val start = dumpsysOutput.indexOf(prefix, startIndex = searchFrom)
            if (start < 0) return null
            val afterIdx = start + prefix.length
            val boundaryOk = afterIdx >= dumpsysOutput.length ||
                dumpsysOutput[afterIdx].isWhitespace() ||
                dumpsysOutput[afterIdx] == ',' ||
                dumpsysOutput[afterIdx] == ']'
            if (!boundaryOk) {
                searchFrom = afterIdx
                continue
            }
            var depth = 0
            for (i in start until dumpsysOutput.length) {
                when (dumpsysOutput[i]) {
                    '[' -> depth++
                    ']' -> {
                        depth--
                        if (depth == 0) {
                            return dumpsysOutput.substring(start, i + 1)
                        }
                    }
                }
            }
            return dumpsysOutput.substring(start)
        }
    }

    internal fun verifyGameManagerMode2(
        dumpsysOutput: String,
        packageName: String,
        expectedFps: Int,
        expectedDownscale: String?
    ): Boolean {
        val block = extractGamePackageBlock(dumpsysOutput, packageName) ?: return false
        val mode2Match = Regex("""\b2\s*=\s*\[([^\]]*)\]""").find(block)
        val mode2Body = mode2Match?.groupValues?.get(1)
            ?: if (Regex("""Game Mode\s*:\s*2\b""", RegexOption.IGNORE_CASE).containsMatchIn(block)) block else return false

        if (!Regex("""Game Mode\s*:\s*2\b""", RegexOption.IGNORE_CASE).containsMatchIn(mode2Body)) {
            return false
        }

        val fpsMatch = Regex("""Fps\s*:\s*(\d+)""", RegexOption.IGNORE_CASE).find(mode2Body) ?: return false
        if (fpsMatch.groupValues[1].toIntOrNull() != expectedFps) {
            return false
        }

        if (expectedDownscale == null) {
            return true
        }

        val scalingMatch = Regex("""Scaling\s*:\s*([0-9.-]+)""", RegexOption.IGNORE_CASE).find(mode2Body)
        val actualScaling = scalingMatch?.groupValues?.get(1)?.toFloatOrNull()
        return if (expectedDownscale == "1.0" || expectedDownscale.equals("disable", ignoreCase = true)) {
            actualScaling == null || actualScaling <= 0f || abs(actualScaling - 1.0f) < 0.01f
        } else {
            val expectedFloat = expectedDownscale.toFloatOrNull() ?: return false
            actualScaling != null && abs(actualScaling - expectedFloat) < 0.01f
        }
    }

    internal fun verifyGameManagerClean(dumpsysOutput: String, packageName: String): Boolean {
        val block = extractGamePackageBlock(dumpsysOutput, packageName) ?: return true
        val hasMode2 = Regex("""\b2\s*=\s*\[[^\]]*\]""").containsMatchIn(block)
        val hasMode3 = Regex("""\b3\s*=\s*\[[^\]]*\]""").containsMatchIn(block)
        if (!hasMode2 && !hasMode3) return true

        val activeFps = Regex("""Fps\s*:\s*(\d+)""", RegexOption.IGNORE_CASE)
            .findAll(block)
            .any { (it.groupValues[1].toIntOrNull() ?: 0) > 0 }
        val activeScaling = Regex("""Scaling\s*:\s*([0-9.-]+)""", RegexOption.IGNORE_CASE)
            .findAll(block)
            .any {
                val scaling = it.groupValues[1].toFloatOrNull() ?: -1f
                scaling > 0f && abs(scaling - 1.0f) > 0.01f
            }
        return !activeFps && !activeScaling
    }

    internal fun parseRawRefreshRateSetting(rawOutput: String): String {
        val trimmed = rawOutput.trim()
        return if (trimmed.isEmpty() || trimmed.equals("null", ignoreCase = true)) "null" else trimmed
    }

    internal fun buildRestoreRefreshRateArgs(key: String, cachedValue: String): List<String> {
        val trimmed = cachedValue.trim()
        return if (trimmed.isEmpty() || trimmed.equals("null", ignoreCase = true)) {
            listOf("settings", "delete", "system", key)
        } else {
            listOf("settings", "put", "system", key, trimmed)
        }
    }

    internal fun resolveOptimalRefreshRate(targetFps: Int, supportedHz: List<Float>): Float {
        if (supportedHz.isEmpty()) {
            return when (targetFps) {
                45, 90 -> 90f
                40, 120 -> 120f
                else -> 60f
            }
        }
        val has60 = supportedHz.any { abs(it - 60f) < 1.5f }
        val has90 = supportedHz.any { abs(it - 90f) < 1.5f }
        val has120 = supportedHz.any { abs(it - 120f) < 1.5f }

        return when (targetFps) {
            45 -> when {
                has90 -> 90f
                has120 -> 120f
                else -> 60f
            }
            40, 120 -> if (has120) 120f else 60f
            90 -> if (has90) 90f else 60f
            30, 60 -> if (has60) 60f else (supportedHz.minOrNull() ?: 60f)
            else -> if (has90) 90f else 60f
        }
    }

    internal fun buildRefreshRateLockSequence(
        targetHz: Float,
        currentMinHz: Float?,
        targetFps: Int = targetHz.roundToInt()
    ): List<List<String>> {
        val hzValue = String.format(Locale.US, "%.1f", targetHz)
        val putPeak = listOf("settings", "put", "system", "peak_refresh_rate", hzValue)

        // When targetFps < targetHz (e.g., 30 FPS on 60Hz, 45 FPS on 90Hz, 40 FPS on 120Hz),
        // locking min_refresh_rate to targetHz raises PRIORITY_USER_SETTING_MIN_RENDER_FRAME_RATE
        // in DisplayModeDirector and blocks SurfaceFlinger's GameFrameRateOverride.
        if (targetFps < targetHz.roundToInt()) {
            val deleteMin = listOf("settings", "delete", "system", "min_refresh_rate")
            return listOf(deleteMin, putPeak)
        }

        val putMin = listOf("settings", "put", "system", "min_refresh_rate", hzValue)
        val steppingDown = if (currentMinHz != null) {
            targetHz < currentMinHz
        } else {
            targetHz <= 60f
        }
        return if (steppingDown) {
            listOf(putMin, putPeak)
        } else {
            listOf(putPeak, putMin)
        }
    }

    private fun buildOrderedRefreshRateRestore(
        cachedPeak: String?,
        cachedMin: String?
    ): List<List<String>> {
        val peakArgs = cachedPeak?.let { buildRestoreRefreshRateArgs("peak_refresh_rate", it) }
        val minArgs = cachedMin?.let { buildRestoreRefreshRateArgs("min_refresh_rate", it) }

        val deleteFirst = mutableListOf<List<String>>()
        val putAfter = mutableListOf<List<String>>()

        val minIsDelete = minArgs?.getOrNull(1) == "delete"
        val peakIsDelete = peakArgs?.getOrNull(1) == "delete"

        if (minIsDelete) deleteFirst.add(minArgs)
        if (peakIsDelete) deleteFirst.add(peakArgs)

        if (!minIsDelete && !peakIsDelete && minArgs != null && peakArgs != null) {
            val peakNum = cachedPeak.toFloatOrNull()
            val lockHz = cachedCurrentPeakHz
            if (peakNum != null && lockHz != null && peakNum >= lockHz) {
                putAfter.add(peakArgs)
                putAfter.add(minArgs)
            } else {
                putAfter.add(minArgs)
                putAfter.add(peakArgs)
            }
        } else {
            if (!peakIsDelete && peakArgs != null) putAfter.add(peakArgs)
            if (!minIsDelete && minArgs != null) putAfter.add(minArgs)
        }

        return deleteFirst + putAfter
    }

    private fun formatHzLabel(hz: Float): String = "${hz.roundToInt()}Hz"

    private suspend fun getSupportedRefreshRates(context: Context?): List<Float> {
        if (context != null) {
            val dm = context.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager
            val display = dm?.getDisplay(Display.DEFAULT_DISPLAY)
            val modes = display?.supportedModes?.map { it.refreshRate }?.filter { it > 0f }
            if (!modes.isNullOrEmpty()) return modes
        }
        val res = commandExecutor(listOf("dumpsys", "display"))
        if (!res.isOk) return emptyList()
        val parsed = mutableSetOf<Float>()
        Regex("""(\d+(?:\.\d+)?)\s*fps""", RegexOption.IGNORE_CASE).findAll(res.stdout).forEach {
            it.groupValues[1].toFloatOrNull()?.let(parsed::add)
        }
        Regex("""fps=(\d+(?:\.\d+)?)""", RegexOption.IGNORE_CASE).findAll(res.stdout).forEach {
            it.groupValues[1].toFloatOrNull()?.let(parsed::add)
        }
        return parsed.toList()
    }

    /**
     * Checks if a specific display refresh rate (e.g., 90Hz) is supported by the panel hardware.
     */
    suspend fun isRefreshRateSupported(hz: Int, context: Context?): Boolean {
        val supported = getSupportedRefreshRates(context)
        return supported.any { abs(it - hz.toFloat()) < 1.5f }
    }

    private fun supportsGameManagerCli(): Boolean =
        Build.VERSION.SDK_INT == 0 || Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU

    private suspend fun applyGameModeAndVerify(
        packageName: String,
        targetFps: Int,
        downscaleRatio: String
    ): Boolean {
        if (!supportsGameManagerCli()) return false

        val normalized = normalizeDownscaleRatio(downscaleRatio)
        val primaryArgs = buildGameModeSetArgs(packageName, targetFps, normalized, useDisableForNative = true)
        log(primaryArgs.joinToString(" "), LogType.COMMAND)
        var setRes = commandExecutor(primaryArgs)
        if (setRes.stdout.isNotBlank()) log(setRes.stdout, LogType.STDOUT)

        if (normalized == "1.0" && !setRes.isOk) {
            val fallbackArgs = buildGameModeSetArgs(packageName, targetFps, normalized, useDisableForNative = false)
            log(fallbackArgs.joinToString(" "), LogType.COMMAND)
            setRes = commandExecutor(fallbackArgs)
            if (setRes.stdout.isNotBlank()) log(setRes.stdout, LogType.STDOUT)
        }
        if (!setRes.isOk && setRes.stderr.isNotBlank()) {
            log(setRes.stderr, LogType.ERROR)
        }

        val modeArgs = listOf("cmd", "game", "mode", "2", packageName)
        log(modeArgs.joinToString(" "), LogType.COMMAND)
        val modeRes = commandExecutor(modeArgs)
        if (modeRes.stdout.isNotBlank()) log(modeRes.stdout, LogType.STDOUT)
        if (!modeRes.isOk && modeRes.stderr.isNotBlank()) {
            log(modeRes.stderr, LogType.ERROR)
        }

        val dumpRes = commandExecutor(listOf("dumpsys", "game"))
        val verified = setRes.isOk &&
            modeRes.isOk &&
            dumpRes.isOk &&
            verifyGameManagerMode2(dumpRes.stdout, packageName, targetFps, normalized)
        if (!verified) {
            log("GameManager Mode 2 verification failed for $packageName", LogType.INFO)
        }
        return verified
    }

    private suspend fun applyRefreshRateLock(optimalHz: Float, targetFps: Int) {
        var currentMinHz: Float? = null
        if (!hasCachedRates) {
            val peakRes = commandExecutor(listOf("settings", "get", "system", "peak_refresh_rate"))
            val minRes = commandExecutor(listOf("settings", "get", "system", "min_refresh_rate"))
            if (peakRes.isOk && minRes.isOk) {
                cachedPeakRefreshRate = parseRawRefreshRateSetting(peakRes.stdout)
                cachedMinRefreshRate = parseRawRefreshRateSetting(minRes.stdout)
                hasCachedRates = true
                currentMinHz = cachedMinRefreshRate?.toFloatOrNull()
                log("Cached display refresh rates: peak=$cachedPeakRefreshRate min=$cachedMinRefreshRate", LogType.INFO)
            } else {
                log("Failed to read system refresh rate baseline; skip caching", LogType.ERROR)
            }
        } else {
            val minRes = commandExecutor(listOf("settings", "get", "system", "min_refresh_rate"))
            if (minRes.isOk) {
                currentMinHz = minRes.stdout.trim().toFloatOrNull()
            }
        }

        cachedCurrentPeakHz = optimalHz
        for (args in buildRefreshRateLockSequence(optimalHz, currentMinHz, targetFps)) {
            log(args.joinToString(" "), LogType.COMMAND)
            val hzRes = commandExecutor(args)
            if (!hzRes.isOk && hzRes.stderr.isNotBlank()) {
                log(hzRes.stderr, LogType.ERROR)
            }
        }
    }

    /**
     * Updates GameManager Mode 2 (--fps + --downscale) and display Hz limits without force-stopping
     * the running game or re-scaling WindowManager geometry.
     */
    suspend fun updateLiveGameConfig(
        packageName: String = currentPackage,
        targetFps: Int,
        downscaleRatio: String = currentDownscaleRatio,
        context: Context? = null
    ): Boolean {
        if (!isValidPackageName(packageName)) {
            log("Rejected invalid package name: $packageName", LogType.ERROR)
            return false
        }
        val normalizedDownscale = normalizeDownscaleRatio(downscaleRatio)
        currentPackage = packageName
        currentFps = targetFps
        currentDownscaleRatio = normalizedDownscale

        val supportedHz = getSupportedRefreshRates(context)
        val optimalHz = resolveOptimalRefreshRate(targetFps, supportedHz)
        val pairedLabel = formatHzLabel(optimalHz)
        _pairedRefreshRate.value = pairedLabel

        val gameVerified = applyGameModeAndVerify(packageName, targetFps, normalizedDownscale)
        applyRefreshRateLock(optimalHz, targetFps)
        log("Live config updated: $targetFps FPS / downscale=$normalizedDownscale / $pairedLabel", LogType.STDOUT)
        return gameVerified
    }

    /**
     * Applies the Cool & Stable thermal/pacing profile:
     * 1. Force-stops target package so GameManager applies rules cleanly on cold launch.
     * 2. Optionally configures experimental WM display scaling (only when no active snapshot exists).
     * 3. Validates target Hz against Display.supportedModes and caches raw system refresh rates.
     * 4. Sets GameManager Mode 2 with --fps and --downscale simultaneously and verifies via dumpsys game.
     * 5. Locks display refresh rate in min/peak order safe for LTPO panels.
     * 6. Runs safe RAM trim (am kill-all) if enabled.
     * 7. Enables Auto Gaming DND if enabled and zen_mode == 0.
     * 8. Launches target game via framework Intent (with BAL try/catch) or sanitized am start fallback.
     */
    suspend fun applyProfile(
        packageName: String = currentPackage,
        targetFps: Int = currentFps,
        @Suppress("UNUSED_PARAMETER") doze: Boolean = currentDoze,
        renderScale: Int = currentRenderScale,
        context: Context? = null,
        downscaleRatio: String = currentDownscaleRatio,
        ramTrim: Boolean = currentRamTrim,
        dnd: Boolean = currentDnd
    ): Boolean {
        if (!isValidPackageName(packageName)) {
            log("Rejected invalid package name: $packageName", LogType.ERROR)
            return false
        }

        val normalizedDownscale = normalizeDownscaleRatio(downscaleRatio)
        currentPackage = packageName
        currentFps = targetFps
        currentDownscaleRatio = normalizedDownscale
        currentRamTrim = ramTrim
        currentDnd = dnd

        // 1. Force-stop target package first so GameManager applies rule cleanly on cold start
        val stopArgs = listOf("am", "force-stop", packageName)
        log(stopArgs.joinToString(" "), LogType.COMMAND)
        val stopRes = commandExecutor(stopArgs)
        if (stopRes.stdout.isNotBlank()) log(stopRes.stdout, LogType.STDOUT)

        // 2. Experimental pre-launch WM display scaling if renderScale < 100
        if (renderScale < 100) {
            val existingSnapshot = context?.let { loadGeometrySnapshot(it) } ?: activeSnapshot
            val snapshot = if (existingSnapshot != null) {
                activeSnapshot = existingSnapshot
                existingSnapshot
            } else {
                val sizeRes = commandExecutor(listOf("wm", "size"))
                val densityRes = commandExecutor(listOf("wm", "density"))
                val parsed = if (sizeRes.isOk && densityRes.isOk) {
                    parseWmGeometry(sizeRes.stdout, densityRes.stdout)
                } else {
                    null
                }
                if (parsed == null) {
                    log("WM snapshot capture failed; aborting experimental display scaling", LogType.ERROR)
                    currentRenderScale = 100
                    null
                } else {
                    activeSnapshot = parsed
                    if (context != null) {
                        saveGeometrySnapshot(context, parsed, packageName)
                    }
                    parsed
                }
            }

            if (snapshot != null) {
                val baseWidth = snapshot.overrideWidth ?: snapshot.physicalWidth
                val baseHeight = snapshot.overrideHeight ?: snapshot.physicalHeight
                val baseDpi = snapshot.overrideDpi ?: snapshot.physicalDpi
                val metrics = computeScaledMetrics(renderScale, baseWidth, baseHeight, baseDpi)

                val sizeArgs = listOf("wm", "size", "${metrics.width}x${metrics.height}")
                log(sizeArgs.joinToString(" "), LogType.COMMAND)
                val setSizeRes = commandExecutor(sizeArgs)
                if (setSizeRes.stdout.isNotBlank()) log(setSizeRes.stdout, LogType.STDOUT)

                val densityArgs = listOf("wm", "density", metrics.dpi.toString())
                log(densityArgs.joinToString(" "), LogType.COMMAND)
                val setDensityRes = commandExecutor(densityArgs)
                if (setDensityRes.stdout.isNotBlank()) log(setDensityRes.stdout, LogType.STDOUT)

                if (setSizeRes.isOk && setDensityRes.isOk) {
                    currentRenderScale = renderScale
                    log(
                        "Render scale pre-launch configured: ${renderScale}% (${metrics.width}x${metrics.height} @ ${metrics.dpi}dpi)",
                        LogType.INFO
                    )
                } else {
                    log("WM scaling command failed; aborting display scaling", LogType.ERROR)
                    currentRenderScale = 100
                }
            }
        } else {
            currentRenderScale = 100
        }

        // 3. Validate target Hz against panel supportedModes
        val supportedHz = getSupportedRefreshRates(context)
        val optimalHz = resolveOptimalRefreshRate(targetFps, supportedHz)
        val pairedLabel = formatHzLabel(optimalHz)
        _pairedRefreshRate.value = pairedLabel

        // 4. Configure GameManager Mode 2 (--fps + --downscale) & verify via dumpsys game
        val gameVerified = applyGameModeAndVerify(packageName, targetFps, normalizedDownscale)

        // 5. Cache raw refresh rates and apply LTPO-safe refresh rate lock
        applyRefreshRateLock(optimalHz, targetFps)

        // 6. Safe background RAM trim before launch
        if (ramTrim) {
            val killArgs = listOf("am", "kill-all")
            log(killArgs.joinToString(" "), LogType.COMMAND)
            val killRes = commandExecutor(killArgs)
            if (killRes.stdout.isNotBlank()) log(killRes.stdout, LogType.STDOUT)
        }

        // 7. Auto Gaming DND (only if zen_mode is currently 0)
        if (dnd && dndCapabilityAllowed && !didEnableDnd) {
            val zenRes = commandExecutor(listOf("settings", "get", "global", "zen_mode"))
            if (zenRes.isOk && zenRes.stdout.trim() == "0") {
                val dndArgs = listOf("cmd", "notification", "set_dnd", "priority")
                log(dndArgs.joinToString(" "), LogType.COMMAND)
                val dndRes = commandExecutor(dndArgs)
                if (dndRes.isOk) {
                    didEnableDnd = true
                } else if (dndRes.stderr.isNotBlank()) {
                    log(dndRes.stderr, LogType.ERROR)
                }
            }
        }

        // 8. Launch target game via framework Intent (with BAL try/catch) or sanitized am start
        var launched = false
        if (context != null) {
            try {
                val launchIntent = context.packageManager.getLaunchIntentForPackage(packageName)
                if (launchIntent != null) {
                    launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(launchIntent)
                    log("Launched $packageName via framework Launch Intent", LogType.INFO)
                    launched = true
                }
            } catch (e: Exception) {
                log("Framework launch blocked (${e.javaClass.simpleName}); falling back to am start", LogType.INFO)
                launched = false
            }
        }
        if (!launched) {
            val resolveArgs = listOf("cmd", "package", "resolve-activity", "--brief", packageName)
            val resolveRes = commandExecutor(resolveArgs)
            val component = if (resolveRes.isOk) parseSanitizedComponent(resolveRes.stdout) else null
            val startArgs = if (component != null) {
                listOf("am", "start", "-n", component)
            } else {
                listOf(
                    "am", "start",
                    "-a", "android.intent.action.MAIN",
                    "-c", "android.intent.category.LAUNCHER",
                    "-p", packageName
                )
            }
            log(startArgs.joinToString(" "), LogType.COMMAND)
            val startRes = commandExecutor(startArgs)
            if (startRes.stdout.isNotBlank()) log(startRes.stdout, LogType.STDOUT)
        }

        log(
            "Profile engaged: Cap $targetFps FPS / $pairedLabel (Mode2Verified=$gameVerified)",
            LogType.STDOUT
        )
        return true
    }

    /**
     * Restores system defaults and disengages optimization profile with closed-loop verification.
     */
    suspend fun restoreDefaults(
        packageName: String = currentPackage,
        context: Context? = null
    ): Boolean {
        if (!isValidPackageName(packageName)) {
            log("Rejected invalid package name on restore: $packageName", LogType.ERROR)
            return false
        }

        // 0. Reset display render scale and clear snapshot only after verified restored
        var geometryClean = true
        val snapshot = context?.let { loadGeometrySnapshot(it) } ?: activeSnapshot
        if (snapshot != null) {
            var commandsOk = true
            for (args in buildRestoreCommandArgs(snapshot)) {
                log(args.joinToString(" "), LogType.COMMAND)
                val res = commandExecutor(args)
                if (res.stdout.isNotBlank()) log(res.stdout, LogType.STDOUT)
                if (!res.isOk) commandsOk = false
            }
            val verifySizeRes = commandExecutor(listOf("wm", "size"))
            val verifyDensityRes = commandExecutor(listOf("wm", "density"))
            val verified = commandsOk &&
                verifySizeRes.isOk &&
                verifyDensityRes.isOk &&
                verifyWmRestored(snapshot, verifySizeRes.stdout, verifyDensityRes.stdout)
            if (verified) {
                if (context != null) {
                    clearGeometrySnapshot(context)
                }
                activeSnapshot = null
                currentRenderScale = 100
            } else {
                geometryClean = false
                log("WM geometry restore verification failed; retaining snapshot for recovery", LogType.ERROR)
            }
        } else if (currentRenderScale < 100) {
            val sizeRes = commandExecutor(listOf("wm", "size", "reset"))
            val densityRes = commandExecutor(listOf("wm", "density", "reset"))
            if (sizeRes.isOk && densityRes.isOk) {
                currentRenderScale = 100
            } else {
                geometryClean = false
            }
        } else {
            currentRenderScale = 100
        }

        // 1. Reset GameManager interventions and confirm clean via dumpsys game
        var gameClean = true
        if (supportsGameManagerCli()) {
            val resetArgs = listOf("cmd", "game", "reset", packageName)
            log(resetArgs.joinToString(" "), LogType.COMMAND)
            val resetRes = commandExecutor(resetArgs)
            if (resetRes.stdout.isNotBlank()) log(resetRes.stdout, LogType.STDOUT)
            val dumpRes = commandExecutor(listOf("dumpsys", "game"))
            gameClean = resetRes.isOk && dumpRes.isOk && verifyGameManagerClean(dumpRes.stdout, packageName)
            if (!gameClean) {
                log("GameManager reset verification failed for $packageName", LogType.ERROR)
            }
        }

        // 2. Restore cached refresh rates (preserving "null" via settings delete)
        var ratesClean = true
        if (hasCachedRates) {
            val restoreArgsList = buildOrderedRefreshRateRestore(cachedPeakRefreshRate, cachedMinRefreshRate)
            for (args in restoreArgsList) {
                log(args.joinToString(" "), LogType.COMMAND)
                val res = commandExecutor(args)
                if (!res.isOk) ratesClean = false
            }
            if (ratesClean) {
                cachedPeakRefreshRate = null
                cachedMinRefreshRate = null
                cachedCurrentPeakHz = null
                hasCachedRates = false
            }
        }
        _pairedRefreshRate.value = "120Hz"

        // 3. Restore Auto Gaming DND if enabled by FrameGuard
        var dndClean = true
        if (didEnableDnd) {
            val dndOffArgs = listOf("cmd", "notification", "set_dnd", "off")
            log(dndOffArgs.joinToString(" "), LogType.COMMAND)
            val dndRes = commandExecutor(dndOffArgs)
            if (dndRes.isOk) {
                didEnableDnd = false
            } else {
                dndClean = false
                log("Failed to disable Auto Gaming DND", LogType.ERROR)
            }
        }

        val allClean = geometryClean && gameClean && ratesClean && dndClean
        log(
            if (allClean) "System defaults restored cleanly" else "System defaults partially restored (verification pending)",
            if (allClean) LogType.STDOUT else LogType.ERROR
        )
        return allClean
    }

    internal fun parseThermalHalTemperature(dumpsysOutput: String): Float? {
        val afterHeader = dumpsysOutput.substringAfter("Current temperatures from HAL:", "")
        if (afterHeader.isEmpty()) return null

        val halLines = afterHeader.lines()
            .drop(1)
            .takeWhile { line ->
                val trimmed = line.trim()
                trimmed.isEmpty() || trimmed.startsWith("Temperature{")
            }
            .joinToString("\n")
        if (halLines.isBlank()) return null

        val tempRegex = Regex("""Temperature\{mValue=([0-9.-]+),\s*mType=(-?\d+),\s*mName=([^,\s}]+)""")
        val readings = tempRegex.findAll(halLines).mapNotNull { match ->
            val value = match.groupValues[1].toFloatOrNull() ?: return@mapNotNull null
            val type = match.groupValues[2].toIntOrNull()
            val name = match.groupValues[3]
            if (value.isNaN() || value <= -50f || value >= 150f) return@mapNotNull null
            Triple(value, type, name)
        }.toList()

        if (readings.isEmpty()) {
            val skinMatch = Regex("""Temperature\{mValue=([0-9.]+)[^}]*mName=SKIN\b""", RegexOption.IGNORE_CASE).find(halLines)
            if (skinMatch != null) return skinMatch.groupValues[1].toFloatOrNull()
            val socMatch = Regex("""Temperature\{mValue=([0-9.]+)[^}]*mName=SOC\b""", RegexOption.IGNORE_CASE).find(halLines)
            if (socMatch != null) return socMatch.groupValues[1].toFloatOrNull()
            return null
        }

        // Priority 1: SKIN (mName=SKIN or TYPE_SKIN == 3)
        readings.firstOrNull { it.third.equals("SKIN", ignoreCase = true) }?.let { return it.first }
        readings.firstOrNull { it.second == 3 }?.let { return it.first }

        // Priority 2: SOC (mName=SOC, contains SOC, CPU, or TYPE_CPU == 0)
        readings.firstOrNull { it.third.equals("SOC", ignoreCase = true) }?.let { return it.first }
        readings.firstOrNull { it.third.contains("SOC", ignoreCase = true) }?.let { return it.first }
        readings.firstOrNull { it.third.equals("CPU", ignoreCase = true) || it.second == 0 }?.let { return it.first }

        return null
    }

    /**
     * Reads live SKIN/SOC temperature from ThermalHAL (`dumpsys thermalservice`) under
     * `Current temperatures from HAL:`, ignoring cached readings and returning null if unavailable.
     */
    suspend fun queryThermalReading(): Float? {
        val res = commandExecutor(listOf("dumpsys", "thermalservice"))
        if (!res.isOk) return null
        return parseThermalHalTemperature(res.stdout)
    }

    internal fun resetStateForTesting() {
        activeSnapshot = null
        cachedPeakRefreshRate = null
        cachedMinRefreshRate = null
        cachedCurrentPeakHz = null
        hasCachedRates = false
        didEnableDnd = false
        dndCapabilityAllowed = true
        currentPackage = DEFAULT_PACKAGE
        currentFps = 45
        currentDoze = true
        currentRenderScale = 100
        currentDownscaleRatio = "1.0"
        currentRamTrim = true
        currentDnd = true
        _pairedRefreshRate.value = "120Hz"
        commandExecutor = { args -> PrivilegeManager.execArgs(args) }
    }
}
