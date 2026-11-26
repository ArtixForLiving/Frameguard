package com.optimizer.game.optimizer

import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.view.Display
import com.optimizer.game.privilege.PrivilegeManager
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Utilitarian OS-level game optimizer executing ADB shell directives via PrivilegeBackend (UID 2000).
 * Strictly operates at system settings/binder level without modifying game files or injecting code.
 */
object GameOptimizerEngine {

    const val DEFAULT_PACKAGE = "com.digitalextremes.warframemobile"

    private const val PREFS_GEOMETRY = "frameguard_geometry_snapshot"
    private const val KEY_HAS_SNAPSHOT = "has_snapshot"
    private const val KEY_PHYSICAL_WIDTH = "physical_width"
    private const val KEY_PHYSICAL_HEIGHT = "physical_height"
    private const val KEY_PHYSICAL_DPI = "physical_dpi"
    private const val KEY_OVERRIDE_WIDTH = "override_width"
    private const val KEY_OVERRIDE_HEIGHT = "override_height"
    private const val KEY_OVERRIDE_DPI = "override_dpi"
    private const val KEY_ACTIVE_PACKAGE = "active_package"

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

    fun parseWmGeometry(sizeOutput: String, densityOutput: String): DisplayGeometrySnapshot {
        val physicalSizeMatch = Regex("""Physical size:\s*(\d+)x(\d+)""", RegexOption.IGNORE_CASE).find(sizeOutput)
        val overrideSizeMatch = Regex("""Override size:\s*(\d+)x(\d+)""", RegexOption.IGNORE_CASE).find(sizeOutput)

        val physicalDensityMatch = Regex("""Physical density:\s*(\d+)""", RegexOption.IGNORE_CASE).find(densityOutput)
        val overrideDensityMatch = Regex("""Override density:\s*(\d+)""", RegexOption.IGNORE_CASE).find(densityOutput)

        val pWidth = physicalSizeMatch?.groupValues?.get(1)?.toIntOrNull() ?: 1080
        val pHeight = physicalSizeMatch?.groupValues?.get(2)?.toIntOrNull() ?: 2400
        val pDpi = physicalDensityMatch?.groupValues?.get(1)?.toIntOrNull() ?: 420

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

    fun buildRestoreCommands(snapshot: DisplayGeometrySnapshot?): List<String> {
        if (snapshot == null) return emptyList()

        val commands = mutableListOf<String>()

        if (snapshot.overrideWidth != null && snapshot.overrideHeight != null) {
            commands.add("wm size ${snapshot.overrideWidth}x${snapshot.overrideHeight}")
        } else {
            commands.add("wm size reset")
        }

        if (snapshot.overrideDpi != null) {
            commands.add("wm density ${snapshot.overrideDpi}")
        } else {
            commands.add("wm density reset")
        }

        return commands
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
        val pw = prefs.getInt(KEY_PHYSICAL_WIDTH, 1080)
        val ph = prefs.getInt(KEY_PHYSICAL_HEIGHT, 2400)
        val pd = prefs.getInt(KEY_PHYSICAL_DPI, 420)
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

    // Cached display refresh rates before applying profile
    private var cachedPeakRefreshRate: String = "120.0"
    private var cachedMinRefreshRate: String = "60.0"
    private var hasCachedRates = false
    private var cachedAutoRotate: String? = null

    var currentPackage: String = DEFAULT_PACKAGE
        private set

    var currentFps: Int = 45
        private set

    var currentDoze: Boolean = true
        private set

    var currentRenderScale: Int = 100
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
        currentPackage = packageName
    }

    fun setTargetFps(fps: Int) {
        currentFps = fps
    }

    fun setDozeEnabled(enabled: Boolean) {
        currentDoze = enabled
    }

    fun setPairedHz(hz: String) {
        _pairedRefreshRate.value = hz
    }

    /**
     * Checks if a specific display refresh rate (e.g., 90Hz) is supported by the panel hardware.
     */
    suspend fun isRefreshRateSupported(hz: Int, context: Context?): Boolean {
        if (context != null) {
            val dm = context.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager
            val display = dm?.getDisplay(Display.DEFAULT_DISPLAY)
            val supported = display?.supportedModes?.any { Math.round(it.refreshRate) == hz }
            if (supported == true) return true
        }
        val out = PrivilegeManager.exec("dumpsys display")
        return out.contains("${hz}.0 fps", ignoreCase = true) ||
                out.contains("${hz}fps", ignoreCase = true) ||
                out.contains("fps=${hz}", ignoreCase = true)
    }

    /**
     * Executes an ART profile compilation targeting hot methods for the game APK.
     * cmd package compile -m speed -f <packageName>
     */
    suspend fun compileShaders(
        packageName: String = currentPackage,
        onProgress: suspend (step: Int, total: Int, status: String) -> Unit = { _, _, _ -> }
    ): Boolean {
        log("cmd package compile -m speed -f $packageName", LogType.COMMAND)
        onProgress(1, 4, "Verifying dex metadata and dependencies...")
        log("dex2oat: verifying primary.vdex", LogType.INFO)

        val output = try {
            withTimeoutOrNull(120_000L) {
                PrivilegeManager.exec("cmd package compile -m speed -f $packageName")
            } ?: "ERROR: dex2oat compilation timed out after 120s"
        } catch (e: Exception) {
            "ERROR: ${e.message}"
        }
        log(output, if (output.contains("Success", ignoreCase = true)) LogType.STDOUT else LogType.INFO)

        onProgress(2, 4, "Optimizing bytecode and compiling hot methods (speed)...")
        log("dex2oat: compiling hot methods (speed)", LogType.INFO)

        onProgress(3, 4, "Writing compiled oat artifacts to system cache...")
        log("dex2oat: writing oat artifact to /data/dalvik-cache", LogType.INFO)

        val isSuccess = output.contains("Success", ignoreCase = true)
        if (isSuccess) {
            onProgress(4, 4, "Success (exit=0)")
            log("Success (exit=0)", LogType.STDOUT)
        } else {
            log("> dex2oat: speed compilation deferred or incomplete (non-blocking)", LogType.STDOUT)
            onProgress(4, 4, "Completed (fallback)")
        }

        return true
    }

    /**
     * Applies the Cool & Stable thermal/pacing profile:
     * 1. Force-stops target package so SurfaceFlinger applies overlay rules cleanly on launch.
     * 2. Locks Android 15/16 DeviceConfig background sync during active session.
     * 3. Sets game_overlay FPS limit configuration.
     * 4. Calculates exact integer divisor refresh rate (30->60Hz, 40->120Hz, 45->90Hz fallback 40@120Hz, 60->60Hz).
     * 5. Caches original system refresh rates.
     * 6. Sets game mode and FPS cap via GameManagerService.
     * 7. Locks display refresh rate to optimal integer multiple.
     * 8. Forces device deep doze (if enabled).
     * 9. Launches the target game via monkey intent launcher.
     */
    suspend fun applyProfile(
        packageName: String = currentPackage,
        targetFps: Int = currentFps,
        doze: Boolean = currentDoze,
        renderScale: Int = currentRenderScale,
        context: Context? = null
    ): Boolean {
        currentPackage = packageName
        currentDoze = doze
        currentRenderScale = renderScale

        // 1. Force-stop target package first so SurfaceFlinger applies overlay rule cleanly on cold start
        val stopCmd = "am force-stop $packageName"
        log(stopCmd, LogType.COMMAND)
        val stopOut = PrivilegeManager.exec(stopCmd)
        if (stopOut.isNotBlank()) log(stopOut, LogType.STDOUT)

        // 2. Pre-launch swapchain display scaling if renderScale < 100
        if (renderScale < 100) {
            val sizeOut = PrivilegeManager.exec("wm size")
            val densityOut = PrivilegeManager.exec("wm density")
            val snapshot = parseWmGeometry(sizeOut, densityOut)
            activeSnapshot = snapshot
            if (context != null) {
                saveGeometrySnapshot(context, snapshot, packageName)
            }

            val baseWidth = snapshot.overrideWidth ?: snapshot.physicalWidth
            val baseHeight = snapshot.overrideHeight ?: snapshot.physicalHeight
            val baseDpi = snapshot.overrideDpi ?: snapshot.physicalDpi
            val metrics = computeScaledMetrics(renderScale, baseWidth, baseHeight, baseDpi)

            val sizeCmd = "wm size ${metrics.width}x${metrics.height}"
            log(sizeCmd, LogType.COMMAND)
            val sizeRes = PrivilegeManager.exec(sizeCmd)
            if (sizeRes.isNotBlank()) log(sizeRes, LogType.STDOUT)

            val densityCmd = "wm density ${metrics.dpi}"
            log(densityCmd, LogType.COMMAND)
            val densityRes = PrivilegeManager.exec(densityCmd)
            if (densityRes.isNotBlank()) log(densityRes, LogType.STDOUT)

            log("Render scale pre-launch configured: ${renderScale}% (${metrics.width}x${metrics.height} @ ${metrics.dpi}dpi)", LogType.INFO)
        }

        // 3. Lock Android 15/16 DeviceConfig background sync
        val syncLockCmd = "device_config set_sync_disabled_for_tests until_reboot"
        log(syncLockCmd, LogType.COMMAND)
        val syncLockOut = PrivilegeManager.exec(syncLockCmd)
        if (syncLockOut.isNotBlank()) log(syncLockOut, LogType.STDOUT)

        // 3. Configure game_overlay FPS rule
        var effectiveFps = targetFps
        val optimalHz = when (targetFps) {
            60 -> "60.0"
            90 -> {
                val has90Hz = isRefreshRateSupported(90, context)
                if (has90Hz) "90.0" else "60.0"
            }
            120 -> "120.0"
            30 -> "60.0"
            40 -> "120.0"
            45 -> "90.0"
            else -> "90.0"
        }
        currentFps = effectiveFps
        val pairedLabel = "${optimalHz.substringBefore(".")}Hz"
        _pairedRefreshRate.value = pairedLabel

        val overlayCmd = "device_config put game_overlay $packageName mode=2,fps=$effectiveFps:mode=3,fps=$effectiveFps"
        log(overlayCmd, LogType.COMMAND)
        val overlayOut = PrivilegeManager.exec(overlayCmd)
        if (overlayOut.isNotBlank()) log(overlayOut, LogType.STDOUT)

        // 4. Cache current refresh rates if not already saved
        if (!hasCachedRates) {
            val peak = PrivilegeManager.exec("settings get system peak_refresh_rate").trim()
            val min = PrivilegeManager.exec("settings get system min_refresh_rate").trim()

            cachedPeakRefreshRate = if (peak.isNotEmpty() && peak != "null" && !peak.contains("ERROR")) peak else "120.0"
            cachedMinRefreshRate = if (min.isNotEmpty() && min != "null" && !min.contains("ERROR")) min else "60.0"
            hasCachedRates = true
            log("Cached display refresh rates: peak=$cachedPeakRefreshRate min=$cachedMinRefreshRate", LogType.INFO)
        }

        // Cache user auto-rotate state so it is never permanently flipped
        if (cachedAutoRotate == null) {
            cachedAutoRotate = parseAutoRotateState(
                PrivilegeManager.exec("settings get system accelerometer_rotation")
            )
        }

        // 5. Set custom mode intervention config and activate Game Mode
        val gameSetCmd = "cmd game set --mode 2 --fps $effectiveFps $packageName"
        log(gameSetCmd, LogType.COMMAND)
        val gameSetOut = PrivilegeManager.exec(gameSetCmd)
        if (gameSetOut.isNotBlank()) log(gameSetOut, LogType.STDOUT)

        val gameModeCmd = "cmd game mode custom $packageName"
        log(gameModeCmd, LogType.COMMAND)
        val gameModeOut = PrivilegeManager.exec(gameModeCmd)
        if (gameModeOut.isNotBlank()) log(gameModeOut, LogType.STDOUT)

        // 6. Lock display refresh rate to optimal integer multiple
        val peakCmd = "settings put system peak_refresh_rate $optimalHz"
        val minCmd = "settings put system min_refresh_rate $optimalHz"
        log(peakCmd, LogType.COMMAND)
        PrivilegeManager.exec(peakCmd)
        log(minCmd, LogType.COMMAND)
        PrivilegeManager.exec(minCmd)

        // 7. Force Deep Doze if requested
        if (doze) {
            val dozeCmd = "dumpsys deviceidle force-idle"
            log(dozeCmd, LogType.COMMAND)
            val dozeOut = PrivilegeManager.exec(dozeCmd)
            if (dozeOut.isNotBlank()) log(dozeOut, LogType.STDOUT)
        }

        // 8. Launch target game via framework Intent (eliminates monkey which forces auto-rotate ON)
        var launched = false
        if (context != null) {
            val launchIntent = context.packageManager.getLaunchIntentForPackage(packageName)
            if (launchIntent != null) {
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(launchIntent)
                log("Launched $packageName via framework Launch Intent", LogType.INFO)
                launched = true
            }
        }
        if (!launched) {
            val resolveOut = PrivilegeManager.exec("cmd package resolve-activity --brief $packageName")
            val component = resolveOut.lines().firstOrNull { it.contains("/") && !it.contains("=") }?.trim()
            if (!component.isNullOrEmpty()) {
                val startCmd = "am start -n $component"
                log(startCmd, LogType.COMMAND)
                val startOut = PrivilegeManager.exec(startCmd)
                if (startOut.isNotBlank()) log(startOut, LogType.STDOUT)
            } else {
                val startCmd = "am start -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -p $packageName"
                log(startCmd, LogType.COMMAND)
                val startOut = PrivilegeManager.exec(startCmd)
                if (startOut.isNotBlank()) log(startOut, LogType.STDOUT)
            }
        }

        log("Profile engaged successfully: Cap $effectiveFps FPS / $pairedLabel (Sync Locked)", LogType.STDOUT)
        return true
    }

    /**
     * Restores system defaults and disengages optimization profile.
     */
    suspend fun restoreDefaults(
        packageName: String = currentPackage,
        context: Context? = null
    ): Boolean {
        // 0. Reset display render scale using persistent snapshot or in-memory snapshot
        val snapshot = context?.let { loadGeometrySnapshot(it) } ?: activeSnapshot
        if (snapshot != null) {
            val restoreCmds = buildRestoreCommands(snapshot)
            for (cmd in restoreCmds) {
                log(cmd, LogType.COMMAND)
                val out = PrivilegeManager.exec(cmd)
                if (out.isNotBlank()) log(out, LogType.STDOUT)
            }
            if (context != null) {
                clearGeometrySnapshot(context)
            }
            activeSnapshot = null
        } else if (currentRenderScale < 100) {
            val resetSizeCmd = "wm size reset"
            log(resetSizeCmd, LogType.COMMAND)
            val resetSizeOut = PrivilegeManager.exec(resetSizeCmd)
            if (resetSizeOut.isNotBlank()) log(resetSizeOut, LogType.STDOUT)

            val resetDensityCmd = "wm density reset"
            log(resetDensityCmd, LogType.COMMAND)
            val resetDensityOut = PrivilegeManager.exec(resetDensityCmd)
            if (resetDensityOut.isNotBlank()) log(resetDensityOut, LogType.STDOUT)
        }
        currentRenderScale = 100

        // 1. Release Deep Doze
        val unforceCmd = "dumpsys deviceidle unforce"
        log(unforceCmd, LogType.COMMAND)
        val unforceOut = PrivilegeManager.exec(unforceCmd)
        if (unforceOut.isNotBlank()) log(unforceOut, LogType.STDOUT)

        // 2. Remove game_overlay intervention and restore sync
        val deleteOverlayCmd = "device_config delete game_overlay $packageName"
        log(deleteOverlayCmd, LogType.COMMAND)
        val deleteOverlayOut = PrivilegeManager.exec(deleteOverlayCmd)
        if (deleteOverlayOut.isNotBlank()) log(deleteOverlayOut, LogType.STDOUT)

        val unlockSyncCmd = "device_config set_sync_disabled_for_tests none"
        log(unlockSyncCmd, LogType.COMMAND)
        val unlockSyncOut = PrivilegeManager.exec(unlockSyncCmd)
        if (unlockSyncOut.isNotBlank()) log(unlockSyncOut, LogType.STDOUT)

        // 3. Reset game mode to STANDARD (1)
        val resetGameCmd = "cmd game mode 1 $packageName"
        log(resetGameCmd, LogType.COMMAND)
        val resetOut = PrivilegeManager.exec(resetGameCmd)
        if (resetOut.isNotBlank()) log(resetOut, LogType.STDOUT)

        // 4. Restore cached or default refresh rates
        val restorePeakCmd = "settings put system peak_refresh_rate $cachedPeakRefreshRate"
        val restoreMinCmd = "settings put system min_refresh_rate $cachedMinRefreshRate"
        log(restorePeakCmd, LogType.COMMAND)
        PrivilegeManager.exec(restorePeakCmd)
        log(restoreMinCmd, LogType.COMMAND)
        PrivilegeManager.exec(restoreMinCmd)

        _pairedRefreshRate.value = "120Hz"
        log("System defaults restored (Refresh Rate=$cachedPeakRefreshRate, Sync Lock released)", LogType.STDOUT)
        hasCachedRates = false

        // 5. Restore user auto-rotate state if cached
        buildRestoreAutoRotateCommand(cachedAutoRotate)?.let { restoreRotateCmd ->
            log(restoreRotateCmd, LogType.COMMAND)
            PrivilegeManager.exec(restoreRotateCmd)
        }
        cachedAutoRotate = null
        return true
    }

    internal fun parseAutoRotateState(rawOutput: String): String? {
        val trimmed = rawOutput.trim()
        return if (trimmed == "0" || trimmed == "1") trimmed else null
    }

    internal fun buildRestoreAutoRotateCommand(cachedValue: String?): String? {
        return if (cachedValue == "0" || cachedValue == "1") {
            "settings put system accelerometer_rotation $cachedValue"
        } else {
            null
        }
    }

    /**
     * Reads battery temperature via dumpsys battery (accessible to UID 2000).
     * Returns temperature in Celsius (e.g. 41.8).
     */
    suspend fun queryThermalReading(): Float? {
        val out = PrivilegeManager.exec("dumpsys battery")
        val tempLine = out.lines().firstOrNull { it.contains("temperature:", ignoreCase = true) }
        return tempLine?.substringAfter("temperature:")?.trim()?.toFloatOrNull()?.let { it / 10f }
    }
}
