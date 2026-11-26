package com.optimizer.game.privilege

import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Build
import android.view.Display
import com.optimizer.game.optimizer.GameOptimizerEngine
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.roundToInt

enum class ProbeStatus {
    PENDING,
    PROBING,
    PASS,
    WARN,
    SKIP
}

data class HiddenModule(
    val name: String,
    val reason: String
)

data class ProbeStepResult(
    val name: String,
    val detail: String,
    val badge: String,
    val status: ProbeStatus,
    val command: String,
    val output: String
)

data class DeviceCapabilityProfile(
    val fingerprint: String,
    val apiLevel: Int,
    val maxRefreshRateHz: Int,
    val supportedRefreshRates: List<Int>,
    val supportsWmWrite: Boolean,
    val supportsSettingsWrite: Boolean,
    val supportsGameManager: Boolean,
    val supportsThermalHal: Boolean,
    val supportsDnd: Boolean,
    val detectedOemDaemons: List<String>,
    val hiddenModules: List<HiddenModule>
)

/**
 * Probes actual UID 2000 shell capabilities in parallel after privilege link handover,
 * builds a DeviceCapabilityProfile, and caches it per Build.FINGERPRINT.
 */
object CapabilityProbe {

    private const val PREFS_NAME = "frameguard_capability_cache"
    private const val KEY_FINGERPRINT = "cached_fingerprint"
    private const val KEY_PROFILE_JSON = "cached_profile_json"

    val KNOWN_OEM_DAEMONS = listOf(
        "com.xiaomi.joyose",
        "com.samsung.android.game.gos",
        "com.oplus.games",
        "com.vivo.gamecube"
    )

    internal var commandExecutor: suspend (List<String>) -> ShellResult = { args ->
        PrivilegeManager.execArgs(args)
    }

    fun initialPendingSteps(): List<ProbeStepResult> = listOf(
        ProbeStepResult(
            name = "TRANSPORT VERIFICATION",
            detail = "Confirming UID 2000 shell context",
            badge = "PENDING",
            status = ProbeStatus.PENDING,
            command = "id -u",
            output = ""
        ),
        ProbeStepResult(
            name = "SYSTEM WRITE ACCESS",
            detail = "Dry-run wm & system settings write check",
            badge = "PENDING",
            status = ProbeStatus.PENDING,
            command = "wm size && settings get system min_refresh_rate",
            output = ""
        ),
        ProbeStepResult(
            name = "GAMEMANAGER API",
            detail = "Checking Android 13+ cmd game support",
            badge = "PENDING",
            status = ProbeStatus.PENDING,
            command = "dumpsys game",
            output = ""
        ),
        ProbeStepResult(
            name = "DISPLAY & THERMALHAL",
            detail = "Inspecting Display.supportedModes & HAL sensors",
            badge = "PENDING",
            status = ProbeStatus.PENDING,
            command = "dumpsys thermalservice",
            output = ""
        ),
        ProbeStepResult(
            name = "DND & OEM DAEMON SCAN",
            detail = "Checking set_dnd access & OEM game packages",
            badge = "PENDING",
            status = ProbeStatus.PENDING,
            command = "cmd notification set_dnd & pm list packages",
            output = ""
        )
    )

    fun isCacheValidForFingerprint(cachedFingerprint: String?, currentFingerprint: String): Boolean {
        return !cachedFingerprint.isNullOrBlank() && cachedFingerprint == currentFingerprint
    }

    fun filterSupportedFpsOptions(maxHz: Int): List<Int> {
        return when {
            maxHz <= 60 -> listOf(30, 45, 60)
            maxHz <= 90 -> listOf(60, 90)
            else -> listOf(60, 90, 120)
        }
    }

    fun computeHiddenModules(
        apiLevel: Int,
        maxHz: Int,
        supportsWmWrite: Boolean,
        supportsDnd: Boolean
    ): List<HiddenModule> {
        val hidden = mutableListOf<HiddenModule>()
        if (apiLevel < Build.VERSION_CODES.TIRAMISU) {
            hidden.add(
                HiddenModule(
                    name = "PER-APP GAME DOWNSCALE",
                    reason = "Requires Android 13+ (API 33) GameManagerService"
                )
            )
        }
        if (maxHz <= 60) {
            hidden.add(
                HiddenModule(
                    name = "90 / 120 FPS CAP OPTIONS",
                    reason = "Panel max refresh rate is ${maxHz}Hz (Display.supportedModes)"
                )
            )
        }
        if (!supportsWmWrite) {
            hidden.add(
                HiddenModule(
                    name = "WM RENDER SCALE (EXPERIMENTAL)",
                    reason = "Blocked by OEM security setting — Enable \"USB Debugging (Security Settings)\""
                )
            )
        }
        if (!supportsDnd) {
            hidden.add(
                HiddenModule(
                    name = "AUTO GAMING DND",
                    reason = "Blocked or unsupported by notification policy (cmd notification set_dnd)"
                )
            )
        }
        return hidden
    }

    fun defaultFallbackProfile(
        context: Context? = null,
        fingerprint: String = Build.FINGERPRINT,
        apiLevel: Int = Build.VERSION.SDK_INT
    ): DeviceCapabilityProfile {
        val rates = readDisplayRefreshRates(context)
        val maxHz = rates.maxOrNull() ?: 60
        val supportsGame = apiLevel >= Build.VERSION_CODES.TIRAMISU
        return DeviceCapabilityProfile(
            fingerprint = fingerprint,
            apiLevel = apiLevel,
            maxRefreshRateHz = maxHz,
            supportedRefreshRates = rates,
            supportsWmWrite = true,
            supportsSettingsWrite = true,
            supportsGameManager = supportsGame,
            supportsThermalHal = false,
            supportsDnd = true,
            detectedOemDaemons = emptyList(),
            hiddenModules = computeHiddenModules(
                apiLevel = apiLevel,
                maxHz = maxHz,
                supportsWmWrite = true,
                supportsDnd = true
            )
        )
    }

    suspend fun runProbe(
        context: Context?,
        targetPackage: String = GameOptimizerEngine.DEFAULT_PACKAGE,
        fingerprint: String = Build.FINGERPRINT,
        apiLevel: Int = Build.VERSION.SDK_INT,
        onStepUpdate: (index: Int, step: ProbeStepResult) -> Unit = { _, _ -> }
    ): Pair<DeviceCapabilityProfile, List<ProbeStepResult>> = coroutineScope {
        val initial = initialPendingSteps()
        initial.forEachIndexed { idx, step ->
            onStepUpdate(idx, step.copy(status = ProbeStatus.PROBING, badge = "PROBING"))
        }

        val transportDeferred = async {
            val step = probeTransport()
            onStepUpdate(0, step.first)
            step
        }
        val writeDeferred = async {
            val step = probeSystemWriteAccess()
            onStepUpdate(1, step.first)
            step
        }
        val gameDeferred = async {
            val step = probeGameManager(apiLevel, targetPackage)
            onStepUpdate(2, step.first)
            step
        }
        val displayThermalDeferred = async {
            val step = probeDisplayAndThermal(context)
            onStepUpdate(3, step.a)
            step
        }
        val dndOemDeferred = async {
            val step = probeDndAndOemDaemons()
            onStepUpdate(4, step.first)
            step
        }

        val transportRes = transportDeferred.await().first
        val (writeStep, supportsWmWrite, supportsSettingsWrite) = writeDeferred.await()
        val (gameStep, supportsGameManager) = gameDeferred.await()
        val (displayStep, supportedRates, maxHz, supportsThermalHal) = displayThermalDeferred.await()
        val (dndOemStep, supportsDnd, oemDaemons) = dndOemDeferred.await()

        GameOptimizerEngine.dndCapabilityAllowed = supportsDnd

        val profile = DeviceCapabilityProfile(
            fingerprint = fingerprint,
            apiLevel = apiLevel,
            maxRefreshRateHz = maxHz,
            supportedRefreshRates = supportedRates,
            supportsWmWrite = supportsWmWrite,
            supportsSettingsWrite = supportsSettingsWrite,
            supportsGameManager = supportsGameManager,
            supportsThermalHal = supportsThermalHal,
            supportsDnd = supportsDnd,
            detectedOemDaemons = oemDaemons,
            hiddenModules = computeHiddenModules(
                apiLevel = apiLevel,
                maxHz = maxHz,
                supportsWmWrite = supportsWmWrite,
                supportsDnd = supportsDnd
            )
        )

        val steps = listOf(transportRes, writeStep, gameStep, displayStep, dndOemStep)
        if (context != null) {
            saveCachedProfile(context, profile)
        }
        profile to steps
    }

    private suspend fun probeTransport(): Pair<ProbeStepResult, Boolean> {
        val cmd = listOf("id", "-u")
        val res = commandExecutor(cmd)
        val uid = res.stdout.trim()
        val ok = res.isOk && uid == "2000"
        val step = ProbeStepResult(
            name = "TRANSPORT VERIFICATION",
            detail = if (ok) "UID 2000 shell active" else "Shell UID check failed (${uid.ifBlank { "exit=${res.exitCode}" }})",
            badge = if (ok) "PASS" else "WARN",
            status = if (ok) ProbeStatus.PASS else ProbeStatus.WARN,
            command = "id -u",
            output = if (ok) "uid=2000 verified (exit=0)" else (res.stderr.ifBlank { res.transportError ?: "exit=${res.exitCode}" })
        )
        return step to ok
    }

    private suspend fun probeSystemWriteAccess(): Triple<ProbeStepResult, Boolean, Boolean> {
        val sizeRes = commandExecutor(listOf("wm", "size"))
        val minHzRes = commandExecutor(listOf("settings", "get", "system", "min_refresh_rate"))

        val parsedSize = GameOptimizerEngine.parseWmGeometry(sizeRes.stdout, "Physical density: 420")
        val wmReadSecurityError = hasSecurityException(sizeRes)
        val wmWriteCheckArgs = if (parsedSize?.overrideWidth != null && parsedSize.overrideHeight != null) {
            listOf("wm", "size", "${parsedSize.overrideWidth}x${parsedSize.overrideHeight}")
        } else {
            listOf("wm", "size", "reset")
        }
        val wmWriteRes = if (!wmReadSecurityError && sizeRes.isOk) {
            commandExecutor(wmWriteCheckArgs)
        } else {
            sizeRes
        }
        val supportsWmWrite = sizeRes.isOk && wmWriteRes.isOk && !hasSecurityException(wmWriteRes)

        val rotRes = commandExecutor(listOf("settings", "get", "system", "accelerometer_rotation"))
        val rotVal = rotRes.stdout.trim()
        val settingsWriteRes = if (rotRes.isOk && (rotVal == "0" || rotVal == "1")) {
            commandExecutor(listOf("settings", "put", "system", "accelerometer_rotation", rotVal))
        } else {
            minHzRes
        }
        val supportsSettingsWrite = minHzRes.isOk && settingsWriteRes.isOk && !hasSecurityException(settingsWriteRes)

        val bothOk = supportsWmWrite && supportsSettingsWrite
        val badge = if (bothOk) "PASS" else if (!supportsWmWrite) "BLOCKED" else "WARN"
        val detail = when {
            bothOk -> "wm & settings write dry-run OK"
            !supportsWmWrite -> "wm write blocked by SecurityException"
            else -> "settings write restricted by OEM policy"
        }
        val out = when {
            bothOk -> "wm & min_refresh_rate=${minHzRes.stdout.trim().ifBlank { "unset" }} writable (exit=0)"
            !supportsWmWrite -> wmWriteRes.stderr.ifBlank { sizeRes.stderr }.ifBlank { "SecurityException: WM write denied (exit=${wmWriteRes.exitCode})" }
            else -> settingsWriteRes.stderr.ifBlank { "SecurityException: settings write denied (exit=${settingsWriteRes.exitCode})" }
        }

        return Triple(
            ProbeStepResult(
                name = "SYSTEM WRITE ACCESS",
                detail = detail,
                badge = badge,
                status = if (bothOk) ProbeStatus.PASS else ProbeStatus.WARN,
                command = "wm size && settings get system min_refresh_rate",
                output = out
            ),
            supportsWmWrite,
            supportsSettingsWrite
        )
    }

    private suspend fun probeGameManager(
        apiLevel: Int,
        targetPackage: String
    ): Pair<ProbeStepResult, Boolean> {
        if (apiLevel < Build.VERSION_CODES.TIRAMISU) {
            return ProbeStepResult(
                name = "GAMEMANAGER API",
                detail = "cmd game --fps & --downscale unavailable",
                badge = "SKIP · API $apiLevel",
                status = ProbeStatus.SKIP,
                command = "getprop ro.build.version.sdk",
                output = "$apiLevel (<33) · skipping cmd game downscale (exit=0)"
            ) to false
        }

        val res = commandExecutor(listOf("cmd", "game", "list-modes", targetPackage))
        val missingService = res.stderr.contains("Can't find service", ignoreCase = true) ||
            res.stdout.contains("Can't find service", ignoreCase = true) ||
            hasSecurityException(res)
        val supported = res.isOk && !missingService
        val step = ProbeStepResult(
            name = "GAMEMANAGER API",
            detail = if (supported) "cmd game --fps & --downscale" else "GameManager CLI unavailable",
            badge = if (supported) "PASS" else "SKIP",
            status = if (supported) ProbeStatus.PASS else ProbeStatus.SKIP,
            command = "cmd game list-modes $targetPackage",
            output = if (supported) "API $apiLevel · mode 2 fps/downscale ready (exit=0)" else res.stderr.ifBlank { "exit=${res.exitCode}" }
        )
        return step to supported
    }

    private suspend fun probeDisplayAndThermal(
        context: Context?
    ): Quad<ProbeStepResult, List<Int>, Int, Boolean> {
        val panelRates = readDisplayRefreshRates(context)
        val thermalRes = commandExecutor(listOf("dumpsys", "thermalservice"))
        val hasHalSection = thermalRes.isOk && thermalRes.stdout.contains("Current temperatures from HAL:")
        val temp = if (thermalRes.isOk) GameOptimizerEngine.parseThermalHalTemperature(thermalRes.stdout) else null
        val supportsThermalHal = hasHalSection && temp != null

        val rates = if (panelRates.isNotEmpty()) {
            panelRates
        } else {
            val dispRes = commandExecutor(listOf("dumpsys", "display"))
            parseRefreshRatesFromDumpsys(dispRes.stdout).ifEmpty { listOf(60) }
        }
        val maxHz = rates.maxOrNull() ?: 60

        val status = if (supportsThermalHal) ProbeStatus.PASS else ProbeStatus.WARN
        val badge = if (supportsThermalHal) "PASS" else "NO HAL"
        val tempLabel = temp?.let { String.format(java.util.Locale.US, "%.1f°C", it) } ?: "N/A"
        val step = ProbeStepResult(
            name = "DISPLAY & THERMALHAL",
            detail = "${maxHz}Hz max · ${if (supportsThermalHal) "SKIN/SOC HAL OK" else "ThermalHAL unavailable"}",
            badge = badge,
            status = status,
            command = "dumpsys thermalservice",
            output = "modes=${rates.joinToString("/")}Hz · HAL=$tempLabel (exit=${thermalRes.exitCode})"
        )
        return Quad(step, rates, maxHz, supportsThermalHal)
    }

    private suspend fun probeDndAndOemDaemons(): Triple<ProbeStepResult, Boolean, List<String>> {
        val zenRes = commandExecutor(listOf("settings", "get", "global", "zen_mode"))
        val zenMode = zenRes.stdout.trim()
        val dndRestoreMode = when (zenMode) {
            "0" -> "off"
            "1" -> "priority"
            "2" -> "none"
            "3" -> "alarms"
            else -> null
        }

        val supportsDnd = if (dndRestoreMode != null) {
            // Test with the current mode (or off when 0) so user DND state never changes.
            val checkRes = commandExecutor(listOf("cmd", "notification", "set_dnd", dndRestoreMode))
            checkRes.isOk && !hasSecurityException(checkRes)
        } else {
            val helpRes = commandExecutor(listOf("dumpsys", "notification", "--noredact"))
            helpRes.isOk && !hasSecurityException(helpRes)
        }

        val pmRes = commandExecutor(listOf("pm", "list", "packages"))
        val detectedDaemons = if (pmRes.isOk) {
            val installedLines = pmRes.stdout.lineSequence()
                .map { it.trim().removePrefix("package:") }
                .toSet()
            KNOWN_OEM_DAEMONS.filter { it in installedLines }
        } else {
            emptyList()
        }

        val hasOemRisk = detectedDaemons.isNotEmpty()
        val status = if (!supportsDnd || hasOemRisk) ProbeStatus.WARN else ProbeStatus.PASS
        val badge = when {
            hasOemRisk -> "WARN"
            !supportsDnd -> "NO DND"
            else -> "PASS"
        }
        val detail = when {
            hasOemRisk -> "Detected: ${detectedDaemons.joinToString(", ")}"
            !supportsDnd -> "DND policy blocked · clean OEM daemon scan"
            else -> "DND ready · no conflicting OEM daemon"
        }
        val out = buildString {
            append("set_dnd=")
            append(if (supportsDnd) "OK" else "BLOCKED")
            append(" · oem=")
            append(if (detectedDaemons.isEmpty()) "none" else detectedDaemons.joinToString(","))
        }

        return Triple(
            ProbeStepResult(
                name = "DND & OEM DAEMON SCAN",
                detail = detail,
                badge = badge,
                status = status,
                command = "cmd notification set_dnd & pm list packages",
                output = out
            ),
            supportsDnd,
            detectedDaemons
        )
    }

    private fun readDisplayRefreshRates(context: Context?): List<Int> {
        if (context == null) return emptyList()
        val dm = context.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager ?: return emptyList()
        val display = dm.getDisplay(Display.DEFAULT_DISPLAY) ?: return emptyList()
        return display.supportedModes
            .map { it.refreshRate.roundToInt() }
            .filter { it > 0 }
            .distinct()
            .sorted()
    }

    private fun parseRefreshRatesFromDumpsys(stdout: String): List<Int> {
        if (stdout.isBlank()) return emptyList()
        val rates = mutableSetOf<Int>()
        Regex("""(\d+(?:\.\d+)?)\s*fps""", RegexOption.IGNORE_CASE).findAll(stdout).forEach {
            it.groupValues[1].toFloatOrNull()?.roundToInt()?.takeIf { hz -> hz in 24..240 }?.let(rates::add)
        }
        Regex("""fps=(\d+(?:\.\d+)?)""", RegexOption.IGNORE_CASE).findAll(stdout).forEach {
            it.groupValues[1].toFloatOrNull()?.roundToInt()?.takeIf { hz -> hz in 24..240 }?.let(rates::add)
        }
        return rates.sorted()
    }

    private fun hasSecurityException(result: ShellResult): Boolean {
        return result.stderr.contains("SecurityException", ignoreCase = true) ||
            result.stdout.contains("SecurityException", ignoreCase = true) ||
            result.stderr.contains("Permission Denial", ignoreCase = true)
    }

    fun loadCachedProfile(
        context: Context,
        currentFingerprint: String = Build.FINGERPRINT
    ): DeviceCapabilityProfile? {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val cachedFingerprint = prefs.getString(KEY_FINGERPRINT, null)
        if (!isCacheValidForFingerprint(cachedFingerprint, currentFingerprint)) {
            return null
        }
        val jsonStr = prefs.getString(KEY_PROFILE_JSON, null) ?: return null
        return decodeProfileJson(jsonStr)
    }

    fun saveCachedProfile(context: Context, profile: DeviceCapabilityProfile) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit()
            .putString(KEY_FINGERPRINT, profile.fingerprint)
            .putString(KEY_PROFILE_JSON, encodeProfileJson(profile))
            .apply()
    }

    fun clearCache(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().clear().apply()
    }

    internal fun encodeProfileJson(profile: DeviceCapabilityProfile): String {
        val root = JSONObject()
        root.put("fingerprint", profile.fingerprint)
        root.put("apiLevel", profile.apiLevel)
        root.put("maxRefreshRateHz", profile.maxRefreshRateHz)
        root.put("supportedRefreshRates", JSONArray(profile.supportedRefreshRates))
        root.put("supportsWmWrite", profile.supportsWmWrite)
        root.put("supportsSettingsWrite", profile.supportsSettingsWrite)
        root.put("supportsGameManager", profile.supportsGameManager)
        root.put("supportsThermalHal", profile.supportsThermalHal)
        root.put("supportsDnd", profile.supportsDnd)
        root.put("detectedOemDaemons", JSONArray(profile.detectedOemDaemons))

        val hiddenArr = JSONArray()
        for (mod in profile.hiddenModules) {
            hiddenArr.put(
                JSONObject()
                    .put("name", mod.name)
                    .put("reason", mod.reason)
            )
        }
        root.put("hiddenModules", hiddenArr)
        return root.toString()
    }

    internal fun decodeProfileJson(jsonStr: String): DeviceCapabilityProfile? {
        return try {
            val root = JSONObject(jsonStr)
            val ratesArr = root.optJSONArray("supportedRefreshRates") ?: JSONArray()
            val rates = List(ratesArr.length()) { i -> ratesArr.getInt(i) }

            val daemonsArr = root.optJSONArray("detectedOemDaemons") ?: JSONArray()
            val daemons = List(daemonsArr.length()) { i -> daemonsArr.getString(i) }

            val hiddenArr = root.optJSONArray("hiddenModules") ?: JSONArray()
            val hidden = List(hiddenArr.length()) { i ->
                val obj = hiddenArr.getJSONObject(i)
                HiddenModule(
                    name = obj.getString("name"),
                    reason = obj.getString("reason")
                )
            }

            DeviceCapabilityProfile(
                fingerprint = root.getString("fingerprint"),
                apiLevel = root.getInt("apiLevel"),
                maxRefreshRateHz = root.getInt("maxRefreshRateHz"),
                supportedRefreshRates = rates,
                supportsWmWrite = root.getBoolean("supportsWmWrite"),
                supportsSettingsWrite = root.getBoolean("supportsSettingsWrite"),
                supportsGameManager = root.getBoolean("supportsGameManager"),
                supportsThermalHal = root.getBoolean("supportsThermalHal"),
                supportsDnd = root.getBoolean("supportsDnd"),
                detectedOemDaemons = daemons,
                hiddenModules = hidden
            )
        } catch (_: Exception) {
            null
        }
    }

    internal fun resetForTesting() {
        commandExecutor = { args -> PrivilegeManager.execArgs(args) }
    }
}

private data class Quad<A, B, C, D>(val a: A, val b: B, val c: C, val d: D)
