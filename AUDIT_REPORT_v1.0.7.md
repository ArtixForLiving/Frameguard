# FrameGuard v1.0.7 — Ruthless Technical & Empirical Audit

- **Target:** FrameGuard (`com.optimizer.game`) v1.0.7 (`versionCode = 107`, `minSdk = 31`, `targetSdk = 37`, `compileSdk = 36`)
- **Source Baseline:** Commit `b49e0bf` + working-tree unit-test extractions (`WatchdogCycleEvaluator` in `BoostSessionService.kt`; `parseAutoRotateState` / `buildRestoreAutoRotateCommand` in `GameOptimizerEngine.kt`)
- **Live Test Target:** Motorola Edge 60 Pro (`ZP2222G6Z4`, `motorola_edge_60_pro` / `cybert`, Android 16 / API 36, MediaTek MTKDEV `1220x2712` @ `450` dpi, supported modes `120.00001` / `90.0` / `60.0` Hz)
- **Scope:** Read-only code audit across Vectors 1–5 + live non-destructive ADB state verification.

---

## 1. Executive Verdict

FrameGuard v1.0.7 behaves like a **fire-and-forget shell wrapper**, not a closed-loop OS optimizer.

1. **Global Exit-Code Blindness:** `PrivilegeBackend.exec` (`privilege/PrivilegeBackend.kt:19`) returns a raw `String`. Every backend (`ShellUserService.runCommand`, `DaemonServer.executeShellCommand`, `AdbTlsClient.executeCommand`) discards the OS exit code and collapses empty output, crashes, and transport failures into `"OK"`, `""`, or `"ERROR: ..."`. `GameOptimizerEngine.applyProfile` (`L433-434`) logs success and returns `true` unconditionally.
2. **Broken Android 14+ FPS Intervention Pipeline:** `applyProfile` configures `--mode 2` (`performance`) and `--mode 3` (`battery`), then activates `cmd game mode custom` (mode 4), which has no FPS intervention bound. Meanwhile `restoreDefaults` calls `cmd game mode 1` without `cmd game reset`, leaving stale Mode 2 overrides persisted across sessions and reboots.
3. **Dead Native ADB Watchdog:** `DaemonServer.executeShellCommand` (`adb/DaemonServer.kt:80`) converts empty stdout/stderr from `pidof <pkg>` (exit code `1` after game exit) into `"OK"`. `WatchdogCycleEvaluator.evaluate("OK")` treats any non-empty string without `"ERROR"` as `ALIVE`—rendering exit detection **100% dead** under Native ADB.
4. **Destructive Recovery & State Races:** Failed `restoreDefaults()` calls unconditionally delete the persisted `wm size`/`wm density` snapshot; the notification `"30 FPS"` quick-drop kills the active game mid-match and double-scales `wm` geometry; unsynchronized `serviceScope.launch` handlers race `applyProfile`, `restoreDefaults`, and `accelerometer_rotation`.
5. **Local Privilege Escalation & Key Exposure:** `DaemonServer` binds `127.0.0.1:25890`, receives its static UUID token on CLI (`/proc/<pid>/cmdline`), and accepts plaintext token auth from any local app before peer verification; `allowBackup="true"` with empty backup rules exposes RSA ADB private keys and the daemon token.

---

## 2. Empirical ADB Evidence (`ZP2222G6Z4` Live Probe)

Captured via ADB on `ZP2222G6Z4` while `com.optimizer.game` was idle (`dumpsys activity services com.optimizer.game` returned `(nothing)`):

| Subsystem | Observed Live State on `ZP2222G6Z4` | Audit Proof |
| :--- | :--- | :--- |
| **`dumpsys game`** | Orphaned Mode 2 interventions (`Game Mode:2, Scaling:-1.0, Use Angle:false, Fps:60`) persisted on:<br>- `com.roblox.client`<br>- `com.tencent.igfit`<br>- `com.digitalextremes.warframemobile`<br>- `com.miHoYo.GenshinImpact` | Proves `restoreDefaults()` (`GameOptimizerEngine.kt:488`) calling `cmd game mode 1 <pkg>` without `cmd game reset <pkg>` permanently leaks `GameManagerService` interventions across sessions/reboots. |
| **`settings get system`** | `peak_refresh_rate=60.0`<br>`min_refresh_rate=24.0`<br>`accelerometer_rotation=0` | `GameOptimizerEngine.kt:140, 367` defaults `cachedMinRefreshRate` to `"60.0"` whenever `settings get` returns `"null"` or fails—clobbering the device's `24.0` Hz LTPO/VRR floor on restore. |
| **`dumpsys display` & `SurfaceFlinger`** | Panel `1220x2712` @ `450` dpi; active `modeId 3` (`renderFrameRate 60.0`, `vsyncRate=60.00 Hz`); `supportedRefreshRates [120.00001, 90.0, 60.0]`; `primaryRanges={physical=[24.00 Hz, 60.00 Hz], render=[24.00 Hz, 60.00 Hz]}` | Real OS display policy and active vsync rate are directly queryable via `dumpsys display` and `SurfaceFlinger`, yet FrameGuard performs zero post-apply verification. |
| **`dumpsys thermalservice`** | `ThermalHAL AIDL 3 connected: yes`<br>`Thermal Status: 1`<br>Cached: `SKIN=44.939C (mStatus=1)`<br>Live HAL: `SKIN=31.361C (mStatus=0)` (follow-up probe: `31.839C`, `SOC=36.058C`, `BATTERY=29.9C`)<br>`SKIN` thresholds: `[NaN, 43.0, 45.0, 47.0, 55.0, 67.0, 68.0]`<br>Headroom thresholds: `[NaN, 0.8666667, 0.93333334, 1.0, 1.2666667, 1.6666666, 1.7]` | Cached thermal state (`44.939C`, `Status: 1`) lags far behind live HAL (`31.361C–31.839C`). Worse, FrameGuard's `HEADROOM_ENGAGE_THRESHOLD = 0.90f` triggers **below** the device's actual `THERMAL_STATUS_MODERATE` headroom threshold (`0.9333`), throttling normal gameplay prematurely. |
| **`wm size` / `wm density` / `deviceidle`** | `Physical size: 1220x2712`, `Physical density: 450` (no active override); `device_config list game_overlay` empty; `dumpsys deviceidle get deep` = `ACTIVE` | Geometry and Doze were clean during idle probe, confirming `dumpsys game` leaks independently even when `wm` and `deviceidle` were cleaned up. |

---

## 3. Feature Efficacy Verdict Table

| Feature / Command | Source Location | Classification | Technical Justification |
| :--- | :--- | :--- | :--- |
| **ART Shader Pre-Compile** (`cmd package compile -m speed -f <pkg>`) | `GameOptimizerEngine.kt:240-273`, `MainViewModel.kt:649-667`, `MainScreen.kt:703-736` | **PLACEBO / GIMMICK** | Every game in `SUPPORTED_CATALOG` (`MainViewModel.kt:65-144`: Warframe, Genshin Impact/IL2CPP, Wuthering Waves/UE, Roblox, PUBG/UE, Free Fire/IL2CPP, MLBB/Unity) runs rendering/game logic in native ARM64 `.so` libraries and compiles Vulkan/GLES shaders at runtime. `dex2oat` only AOT-compiles DEX bytecode (`classes*.dex` -> `.odex/.vdex`). Moreover, `compileShaders()` prints fabricated `dex2oat` progress logs (including a non-existent `/data/dalvik-cache` step) and returns `true` (`"Completed (fallback)"`) even on failure. |
| **Force Deep Doze** (`dumpsys deviceidle force-idle` / `unforce`) | `GameOptimizerEngine.kt:400-403, 471-474`, `MainViewModel.kt:633-635` | **PLACEBO / GIMMICK** | Foreground `TOP` apps and wake-locked processes are already Doze-exempt, and Android 14–16 natively throttles background apps via the `cgroup` freezer. Forcing deep Doze with screen-on either drops out on motion/display events or disrupts background VoIP/notifications/ADB/Shizuku keepalives. If `restoreDefaults()` fails, the device remains stuck in forced-idle until reboot. |
| **`device_config` `game_overlay` & `set_sync_disabled_for_tests until_reboot`** | `GameOptimizerEngine.kt:333-336, 356-359, 477-485`, `BoostSessionService.kt:247, 343, 351` | **OEM-DEPENDENT / FRAGILE** | On Android 14–17 (`targetSdk = 37`), UID 2000 `device_config` mutations can throw `SecurityException` or be silently ignored by OEM game daemons (HyperOS Joyose, Samsung GOS, ColorOS OplusGames, Vivo GameCube). Locking `until_reboot` globally disables system/Play Phenotype sync across all namespaces if cleanup fails. |
| **GameManager FPS Lock** (`cmd game set --mode 2 --fps`, `mode custom`, `mode battery\|1`, `reset`) | `GameOptimizerEngine.kt:380-388, 488-491`, `MainViewModel.kt:607-613`, `BoostSessionService.kt:246, 340-341, 350` | **OEM-DEPENDENT / FRAGILE** | Proven effective on AOSP/Pixel Android 14+ *when configured and activated on the same mode ID*, but broken in v1.0.7 by `--mode 2` vs `mode custom` (mode 4) mismatch, missing API `<34` guards in `applyProfile()` (`minSdk = 31`), and missing `cmd game reset` in `restoreDefaults()`. |
| **Display Refresh Rate Lock** (`settings put system peak_refresh_rate` / `min_refresh_rate`) | `GameOptimizerEngine.kt:363-376, 391-396, 494-503`, `MainViewModel.kt:616-619` | **OEM-DEPENDENT / FRAGILE** | Works on AOSP/Motorola (`ZP2222G6Z4` honors `60.0`), but returns exit `0` while silently ignored on HyperOS/MIUI (`user_refresh_rate`), One UI, and ColorOS. Sequential `peak`/`min` ordering causes transient invalid bounds, and `"null"` fallback `"120.0"`/`"60.0"` destroys LTPO VRR (`24.0` Hz). |
| **WindowManager Render Scaling** (`wm size` / `wm density` / `reset`) | `GameOptimizerEngine.kt:306-328, 447-467` | **PROVEN EFFECTIVE** (WM geometry) / **OEM-DEPENDENT** (GPU load) | Actually alters `WindowManagerService` display bounds and density. However, games using `Display.Mode.getPhysicalWidth()` or fixed internal render targets gain zero GPU savings while SystemUI/keyboard/notifications shrink globally, and `display_settings.xml` persists corruption across reboots. |
| **App Lifecycle Launch** (`am force-stop`, `getLaunchIntentForPackage`, `am start`) | `GameOptimizerEngine.kt:299-302, 407-431` | **PROVEN EFFECTIVE** | `am force-stop` and `startActivity` / `am start` work reliably, but `startActivity` lacks BAL exception handling and `resolve-activity` stdout parsing lacks component sanitization. |
| **Adaptive Thermal Guard** (`ThermalHysteresisEvaluator` + `PowerManager`) | `BoostSessionService.kt:36-92, 313-361` | **OEM-DEPENDENT / FRAGILE** | Uses genuine Android `PowerManager` thermal status & headroom APIs, but polls `getThermalHeadroom(10)` every 3s (violating 10s rate limit), triggers prematurely at `MODERATE` / `0.90f`, deadlocks on `MODERATE` plateaus, erases user FPS caps on release, and only caps `cmd game` without adjusting Hz or restarting. |
| **Process Exit Watchdog** (`WatchdogCycleEvaluator` + `pidof`) | `BoostSessionService.kt:94-125, 264-311` | **OEM-DEPENDENT / FRAGILE** | Broken (`ALIVE` forever) on `DaemonServer` due to `"OK"` masking; 2-cycle (6s) `pidof` polling misfires on splash/hot-patch/subprocess games and ignores foreground/background state. |
| **Telemetry Queries** (`dumpsys display`, `dumpsys thermalservice`, `dumpsys battery`) | `GameOptimizerEngine.kt:230-233, 531-535`, `MainViewModel.kt:408-431, 449` | **OEM-DEPENDENT / FRAGILE** | `dumpsys battery` accurately reports battery temp (~29.9°C on `ZP2222G6Z4`)—not SoC temp (~36.1°C). `MainViewModel`'s `SOC|CPU|BATTERY` regex misses OEM thermal sensor names; `dumpsys display` Hz matching is string-brittle. |

---

## 4. Critical, High & Medium-Severity Loopholes (Vectors 1–5)

### Critical Severity

#### C-1. Watchdog 100% Dead Under Native ADB (`DaemonServer` `"OK"` Masking)
- **Locations:** `adb/DaemonServer.kt:78-80`, `adb/AdbTlsClient.kt:37-49`, `service/BoostSessionService.kt:105-120, 285-298`
- **Root Cause:** When a game exits, `pidof $packageName` returns exit code `1` with empty stdout/stderr. `DaemonServer.executeShellCommand` (`adb/DaemonServer.kt:80`) discards the exit code and replaces empty `(out + err)` with `"OK"`. `WatchdogCycleEvaluator.evaluate("OK")` (`BoostSessionService.kt:105-120`) sees a non-empty string without `"ERROR"` and returns `WatchdogDecision.ALIVE` forever. Conversely, `AdbTlsClient.executeCommand` returns `""` on its 5s read timeout (`AdbTlsClient.kt:37-49`), causing false `EXIT_CONFIRMED` if fallback TLS runs.
- **Reproduction:** Start any boost session using Native ADB (`DaemonServer`), play for >10s, then force-close or exit the game. `BoostSessionService` stays active indefinitely with locked display scaling/Hz/Doze.

#### C-2. `cmd game` Mode Mismatch (`--mode 2` vs `custom`) & Orphaned Intervention Leak
- **Locations:** `optimizer/GameOptimizerEngine.kt:356, 380, 385, 488`, `viewmodel/MainViewModel.kt:607-613`, `service/BoostSessionService.kt:246`
- **Root Cause:** `applyProfile` and `onSelectFps` write FPS interventions to Mode `2` (`performance`) and Mode `3` (`battery`) via `device_config put game_overlay ... mode=2,fps=$effectiveFps:mode=3,fps=$effectiveFps` and `cmd game set --mode 2 --fps $effectiveFps`, then activate `cmd game mode custom $packageName` (Mode `4`). Mode `4` has no FPS cap attached, bypassing the cap on Android 14+. Worse, `restoreDefaults` (`GameOptimizerEngine.kt:488`) executes `cmd game mode 1 $packageName` without `cmd game reset $packageName`, leaving Mode `2` persisted in `GameManagerService`.
- **Reproduction:** Verified empirically on `ZP2222G6Z4` (`dumpsys game` showed persisted `{2=[Game Mode:2, ..., Fps:60]}` across 4 packages while FrameGuard was idle).

#### C-3. Unconditional Geometry Snapshot Wipe & Dirty Device Stranding on Failed Restore
- **Locations:** `optimizer/GameOptimizerEngine.kt:445-467, 503-510`, `service/BoostSessionService.kt:240-257`, `MainActivity.kt:63-82`
- **Root Cause:** `restoreDefaults()` calls `clearGeometrySnapshot(context)` (`L454`), clears `activeSnapshot = null` (`L456`), resets `hasCachedRates = false` (`L503`), and clears `cachedAutoRotate = null` (`L510`) unconditionally—even if `PrivilegeManager.exec(cmd)` returned `"ERROR: ..."`. `ACTION_STOP` then invokes `stopSelf()` (`BoostSessionService.kt:255`). Similarly, `MainActivity.checkGeometryRecovery()` (`MainActivity.kt:78`) cancels `recoveryJob` after one attempt regardless of whether `exec` succeeded.
- **Reproduction:** Start a scaled session (`75%`), revoke Shizuku permission or kill Shizuku/DaemonServer, then tap `STOP` in the notification. The phone remains stuck at scaled `wm size`/`wm density` and Doze while its recovery snapshot is permanently erased.

#### C-4. Notification `"30 FPS"` Button Kills Active Game Mid-Match & Double-Scales Display
- **Locations:** `service/BoostSessionService.kt:231-238, 412-422`, `optimizer/GameOptimizerEngine.kt:299-330`
- **Root Cause:** Tapping `"30 FPS"` on the foreground notification dispatches `ACTION_SWITCH_FPS`, which calls full `applyProfile()` (`BoostSessionService.kt:235`). `applyProfile()` unconditionally executes `am force-stop $packageName` (`GameOptimizerEngine.kt:299`)—killing the running match—then runs `wm size`/`wm density` queries (`L305-312`) on the *already-scaled* display, overwriting `activeSnapshot` and `SharedPreferences` with scaled dimensions and applying a second scale pass (e.g., `0.75 * 0.75 = 56.25%`).
- **Reproduction:** Launch a game at `75%` scale and `60 FPS`, pull down the notification shade, and tap `30 FPS`. The game terminates, relaunches at `~56%` scale, and `restoreDefaults()` later restores to the corrupted `75%` snapshot instead of native resolution.

#### C-5. Binder Guard Checks Wrong Binder & Swallows `DeadObjectException`
- **Locations:** `service/BoostSessionService.kt:222-257, 272-308, 313-361`, `shizuku/ShizukuManager.kt:151-161`, `adb/NativeDaemonManager.kt:118-120`, `adb/AdbTlsClient.kt:50-53`
- **Root Cause:** `startProcessWatchdog` only calls `Shizuku.pingBinder()` (`BoostSessionService.kt:275`), checking the Shizuku server process rather than `ShizukuManager.shellService` (`IShellService` UserService). If `ShellUserService` dies while Shizuku server lives, `pingBinder()` stays `true` while `ShizukuManager.exec()` returns `"ERROR: Shizuku ShellUserService not connected"`. Additionally, `catch (_: DeadObjectException)` / `e.cause is DeadObjectException` (`BoostSessionService.kt:300-307`) is unreachable dead code because all three backends catch `Exception` and return `"ERROR: ..."` strings without clearing `shellService` or updating `_state`. Outside the watchdog (`ACTION_START`, `ACTION_SWITCH_FPS`, `ACTION_STOP`, `startTelemetryPoller`), there is no binder guard at all.
- **Reproduction:** Force-stop the Shizuku UserService process during a boost session; watchdog logs `"Watchdog: Binder/execution glitch"` forever without rebinding or restoring state.

#### C-6. Local Privilege Escalation on `DaemonServer` (`127.0.0.1:25890`) & Backup Key Exposure
- **Locations:** `adb/NativeDaemonManager.kt:17, 31-34, 45-51, 75, 97-104`, `adb/DaemonServer.kt:17, 24, 31, 35-70`, `AndroidManifest.xml:15`, `res/xml/backup_rules.xml:1-3`, `res/xml/data_extraction_rules.xml:1-7`, `adb/AdbKeyManager.kt:24-27, 58, 89, 104-107`
- **Root Cause:**
  1. `NativeDaemonManager.ensureDaemonStarted` (`L75`) passes the static UUID `daemon_auth_token` on the `app_process` command line (`DaemonServer $token`), exposing it via `/proc/<pid>/cmdline` on devices without `hidepid`.
  2. `DaemonServer` hardcodes port `25890` (`DaemonServer.kt:17`) and swallows `BindException` (`L31`). Any local app with `INTERNET` permission can pre-bind `127.0.0.1:25890`; `NativeDaemonManager.isDaemonAlive` / `exec` transmits the plaintext token *before* verifying the peer (`NativeDaemonManager.kt:45-51, 97-104`), leaking UID 2000 shell access. Clearing FrameGuard app data rotates `daemon_auth_token` while the surviving UID 2000 `DaemonServer` still holds port `25890`, causing `BindException` and permanent auth failure until reboot.
  3. `DaemonServer` spawns an unbounded `Thread {}.start()` per connection (`L35-70`), compares tokens non-constant-time (`L43`), and `EXIT` only exits the client loop (`L58-60`), not the server.
  4. `AndroidManifest.xml:15` sets `android:allowBackup="true"` with empty `backup_rules.xml` / `data_extraction_rules.xml`, allowing cloud/ADB backup of `filesDir/adb_keys/{adbkey,adbkey.pub,adbkey.crt}` (plaintext PKCS#8 RSA key) and `filesDir/daemon_auth_token`.

---

### High Severity

#### H-1. Fabricated `"ART Shader Pre-Compile"` & Silent Failure UI Lies
- **Locations:** `privilege/PrivilegeBackend.kt:19`, `shizuku/ShellUserService.kt:28-29`, `adb/DaemonServer.kt:78-80`, `adb/AdbTlsClient.kt:26-49`, `optimizer/GameOptimizerEngine.kt:240-273, 433-434`, `viewmodel/MainViewModel.kt:649-667`, `ui/MainScreen.kt:703-736`
- **Root Cause:** `compileShaders()` (`GameOptimizerEngine.kt:240-273`) emits hardcoded `dex2oat` progress messages regardless of command execution, has zero effect on Vulkan/GLES shaders or native C++ `.so` engines, and falls through to `"Completed (fallback)"` returning `true` when `cmd package compile` fails or lacks `"Success"`. `MainViewModel.onRunArtCompile` (`L665`) sets `_compileDone = true`, displaying green `"OK"` in `MainScreen.kt:711-728`.

#### H-2. Unsynchronized Session/App-Switch Races & `accelerometer_rotation` Clobbering
- **Locations:** `service/BoostSessionService.kt:133, 222-257`, `optimizer/GameOptimizerEngine.kt:142, 372-377, 505-510`, `viewmodel/MainViewModel.kt:502-506, 583-625`
- **Root Cause:**
  1. `ACTION_START` and `ACTION_STOP` launch parallel coroutines on `serviceScope` without a `Mutex` or `Job.join()`. Rapid Start -> Stop -> Start lets an older `restoreDefaults` wipe `cachedAutoRotate` / `hasCachedRates` *after* the new `applyProfile` cached them, or lets an in-flight `applyProfile` re-apply Doze/Hz and launch the game after `stopSelf()`.
  2. `cachedAutoRotate` (`GameOptimizerEngine.kt:142`) is memory-only (unlike `DisplayGeometrySnapshot`), so LMK kill loses it; meanwhile `restoreDefaults` (`L506-509`) unconditionally restores `cachedAutoRotate` even though FrameGuard no longer uses `monkey`, silently reverting any deliberate auto-rotate change the user made via Quick Settings during a mid-game call.
  3. Switching selected games in the UI (`MainViewModel.kt:502-506`) mutates `GameOptimizerEngine.currentPackage` while `BoostSessionService.currentPkg` retains the old package; `onSelectFps` (`MainViewModel.kt:607-613`) then writes `cmd game` overrides to the *new* package that `ACTION_STOP` never cleans up.

#### H-3. Thermal Limiter Premature Throttling, Plateau Deadlock & Target-FPS Erasure
- **Locations:** `service/BoostSessionService.kt:41-92, 313-361`, `viewmodel/MainViewModel.kt:449, 640-647`, `optimizer/GameOptimizerEngine.kt:531-535`
- **Root Cause:**
  1. `ThermalHysteresisEvaluator` engages `CAPPED_30FPS` after 2 samples (6s) at `THERMAL_STATUS_MODERATE` (`2`) **or** `headroom >= 0.90f` (even at `THERMAL_STATUS_NONE`). On `ZP2222G6Z4`, `0.90f` triggers *before* `MODERATE` (`0.9333`).
  2. `powerManager.getThermalHeadroom(10)` (`L329`) is called every `3000ms` (`L318`), violating Android's ~10s rate-limit guidance and risking `NaN`/stale values.
  3. Release (`L67-68`) requires `currentStatus <= THERMAL_STATUS_LIGHT` (`1`), deadlocking at 30 FPS if the device stabilizes at `MODERATE` (`~40°C`).
  4. Release (`L348-354`) executes `cmd game reset` and `device_config delete game_overlay` **without re-applying `currentTargetFps`**, uncapping the game to 90/120 FPS and causing thermal flapping.
  5. `CAPPED_30FPS` (`L338-346`) does not lower `peak/min_refresh_rate`, omits `cmd game` below API 33, swallows all exceptions (`L358`), ignores mid-session `MainViewModel.onToggleAdaptiveThermal` toggles (`MainViewModel.kt:640-647`), and displays battery temp (`dumpsys battery`) + `"36.2°C"` fallback (`L403`) rather than SoC HAL temp.

#### H-4. Watchdog 2-Cycle (6s) False Positives, Launch Race & Background Blindness
- **Locations:** `service/BoostSessionService.kt:94-125, 231-238, 264-311`
- **Root Cause:**
  1. `pidof $packageName` matches exact `/proc/<pid>/cmdline == packageName`. Games whose splash process exits for >6s during self-restart, asset unpacking, or hot-patching, or whose main loop runs in `:game`/`:unity`/`:UE4` subprocesses, trigger false `EXIT_CONFIRMED` in 6s. Conversely, lingering anti-cheat/push services with the base package name keep returning a PID after the game UI closes.
  2. `startProcessWatchdog` starts its `8000ms` grace timer (`L268`) concurrently with `applyProfile()` (`L222-228`). Slow pre-launch commands eat the 8s budget before the game spawns.
  3. `ACTION_SWITCH_FPS` (`L231-238`) runs `am force-stop` via `applyProfile()` without pausing/resetting `watchdogJob`.
  4. `startProcessWatchdog` checks only `Shizuku.pingBinder()`, ignoring `NativeAdbBackend` health, and ignores foreground/background state (minimized games keep system-wide scaling/Hz/Doze locked until LMK kills the process).

#### H-5. Missing Boot Recovery, `START_NOT_STICKY` LMK Loss & Post-Reboot Privilege Gap
- **Locations:** `AndroidManifest.xml:1-63`, `MainActivity.kt:45, 57, 63-82`, `service/BoostSessionService.kt:261, 459-465`
- **Root Cause:** `BoostSessionService` returns `START_NOT_STICKY` (`L261`), implements no `onTaskRemoved()`, and performs no cleanup in `onDestroy()` (`L459-465`). `AndroidManifest.xml` defines neither `RECEIVE_BOOT_COMPLETED` nor a boot receiver; recovery only runs inside `MainActivity.checkGeometryRecovery()` on manual app launch. Even with a boot receiver, neither Shizuku nor Wireless ADB (`adb_wifi_enabled`) persists across reboot on non-rooted Android 11–17—leaving `display_settings.xml` (`wm size`/`wm density` overrides) stuck after an abrupt reboot until the user manually re-authorizes privileges and opens FrameGuard.

#### H-6. Raw `sh -c` Command Injection, Second-Order `resolve-activity` & Framing Injection
- **Locations:** `privilege/PrivilegeManager.kt:76-78`, `shizuku/ShellUserService.kt:15`, `adb/DaemonServer.kt:52, 75`, `adb/NativeDaemonManager.kt:75, 110`, `adb/AdbTlsClient.kt:26`, `optimizer/GameOptimizerEngine.kt:204-206, 418-424`
- **Root Cause:** Every backend executes raw strings via `sh -c` with zero argv isolation (`ProcessBuilder`) or `packageName` regex validation (`^[a-zA-Z][a-zA-Z0-9_]*(\.[a-zA-Z0-9_]+)+$`). Specifically:
  - `GameOptimizerEngine.kt:418-424` parses `component` from `cmd package resolve-activity --brief $packageName` using only `contains("/") && !contains("=")` and interpolates it raw into `am start -n $component`.
  - `NativeDaemonManager.kt:110` uses `writer.println(command)` over a line-delimited TCP socket (`DaemonServer.kt:52`), permitting `\n`/`\r` protocol injection and `---FRAMEGUARD_EOF---` output desync. `$apkPath` is also interpolated unquoted into `app_process -Djava.class.path=$apkPath` (`NativeDaemonManager.kt:75`).
  - *Mitigating boundary:* `BoostSessionService` and `AdbPairingReceiver` are `exported="false"` (`AndroidManifest.xml:34, 42`), and `SUPPORTED_CATALOG` is hardcoded, restricting external exploitability primarily to `DaemonServer` port `25890` abuse or crafted `resolve-activity` output.

#### H-7. Duplicate `PrivilegeManager.init` Collectors, Leaked Backend & Shizuku Deadlocks
- **Locations:** `MainActivity.kt:42`, `viewmodel/MainViewModel.kt:260-262`, `adb/AdbPairingReceiver.kt:161`, `privilege/PrivilegeManager.kt:22-61`, `shizuku/ShizukuManager.kt:38-43, 74-92, 118-135`, `shizuku/ShellUserService.kt:20-29`
- **Root Cause:** `PrivilegeManager.init()` is invoked 3 times (`MainActivity.onCreate:42`, `MainViewModel.init:262`, `AdbPairingReceiver.onReceive:161`). `MainActivity.onCreate` instantiates `NativeAdbBackend #1` (`PrivilegeManager.kt:40`) before lazy `viewModel` overwrites it with `NativeAdbBackend #2` (`MainViewModel.kt:260-261`), leaking Backend #1's `AdbMdnsDiscovery` + 1200ms polling loop + collector while stacking duplicate `ShizukuManager.state.collect` collectors and Shizuku listeners. In `ShizukuManager`, `onServiceConnected` (`L38-43`) never resets `isBound = false` on dead/null binder; `disconnect()` -> `cleanup()` (`L81-92`) permanently drops listeners; `requestPermission()` (`L118-126`) silently no-ops if `!pingBinder()` or pre-v11. In `ShellUserService.runCommand` (`L20-29`) and `DaemonServer` (`L76-77`), reading `stdOut` completely before `stdErr` deadlocks if a child process fills the 64KB stderr pipe.

#### H-8. `AdbTlsClient` Truncation/Hang, Port Scanner Hijack, mDNS Spoofing & Missing `goAsync()`
- **Locations:** `adb/AdbTlsClient.kt:22-49`, `adb/AdbPairingClient.kt:29`, `adb/AdbMdnsDiscovery.kt:65-75, 95-110`, `adb/AdbPortScanner.kt:28-52`, `adb/AdbPairingReceiver.kt:90-173`, `adb/NativeAdbBackend.kt:23-68, 89-107`
- **Root Cause:**
  - `AdbTlsClient.executeCommand` breaks as soon as `output.isNotEmpty() && stream.available() == 0` (`L37-47`), truncating multi-packet outputs (`dumpsys`); silent commands stall for `5000ms`; any command containing `&` (including `&&`) closes prematurely after `400ms` and returns `"OK"` (`L27-31`). Host is hardcoded to `"127.0.0.1"` (`AdbTlsClient.kt:22`, `AdbPairingClient.kt:29`).
  - `AdbPortScanner.findOpenPort` (`L28-52`) caches the *first* open TCP port in `32768..55000` (`!= 25890`), allowing any local listener/debugger to hijack `AdbPortStore`.
  - `AdbMdnsDiscovery` does not validate loopback origin, drops active resolves on concurrent calls (`FAILURE_ALREADY_ACTIVE`), and clears ports on any lost service.
  - `AdbPairingReceiver.onReceive` (`L90-173`) runs async SPAKE2 pairing without `goAsync()` and logs the plaintext pairing code (`L103`).
  - `NativeAdbBackend` races `checkConnection()`, mDNS, and port scanner to spawn `DaemonServer`, breaks its watcher loop permanently once connected (`L64`), and leaks `scope` + `DaemonServer` on `disconnect()` (`L104-107`).

---

### Medium Severity

#### M-1. Unchecked Panel Support for 45/40/120 Hz & Sequential `peak`/`min` Write Race
- **Locations:** `optimizer/GameOptimizerEngine.kt:223-234, 343-350, 363-367, 391-396, 494-499`, `viewmodel/MainViewModel.kt:594, 616-619`
- **Root Cause:** `isRefreshRateSupported(90, context)` is only checked for `targetFps == 90`. `45` unconditionally maps to `"90.0"` (contradicting `GameOptimizerEngine.kt:280`'s `"45->90Hz fallback 40@120Hz"` comment), and `40`/`120` unconditionally map to `"120.0"`. `peak_refresh_rate` is written before `min_refresh_rate` when stepping down (temporarily setting `peak < min`), and `settings get` returning `"null"` triggers hardcoded `"120.0"`/`"60.0"` restore values.

#### M-2. Silent `1080x2400 @ 420` Fallback & Foldable / Multi-Display Geometry Corruption
- **Locations:** `optimizer/GameOptimizerEngine.kt:46-69, 107-126, 193-195, 306-328`
- **Root Cause:** `parseWmGeometry` and `loadGeometrySnapshot` silently substitute `1080x2400 @ 420dpi` on parse/exec failure, and `applyProfile` applies scaled geometry without checking if `wm size`/`wm density` returned `"ERROR: ..."`. `wm` commands omit `-d <displayId>`, breaking on foldables when folding/unfolding mid-session swaps physical dimensions, while DPI clamping `[160, 640]` and integer truncation can shift `smallestWidthDp` breakpoints.

#### M-3. Background Activity Launch (BAL) Failure & Skipped `am start` Fallback
- **Locations:** `optimizer/GameOptimizerEngine.kt:407-431`
- **Root Cause:** Calling `context.startActivity(launchIntent)` from `BoostSessionService` (background context) can fail or throw under Android 14–16 BAL restrictions; `startActivity` has no `try/catch`, aborts `applyProfile` before `updateNotification()`, and sets `launched = true` before throwing/blocking, preventing the UID 2000 `am start` fallback (`L417-431`) from running.

#### M-4. Unused `dadb` Dependency, FD Leak & Weak Cert Serial
- **Locations:** `app/build.gradle.kts:84`, `app/proguard-rules.pro:17-20`, `adb/AdbKeyManager.kt:58, 89`
- **Root Cause:** `dev.mobile:dadb:1.2.7` is compiled and retained in ProGuard rules but completely unused (`libadb-android:3.1.1` is used). `AdbKeyManager.kt:58` leaks `FileInputStream(certFile)` (missing `.use {}`) and uses `System.currentTimeMillis()` (`L89`) for X.509 serials.

---

## 5. De-Gimmicking Recommendations for v1.0.8

| Action | Target Component | Concrete Plan for v1.0.8 |
| :--- | :--- | :--- |
| **CUT** | `compileShaders()` / `"ART Shader Pre-Compile"` (`GameOptimizerEngine.kt:240-273`, `MainScreen.kt:703-736`) | Remove the feature, fake `dex2oat` log lines, and `"OK"` fallback UI completely. It misleads users and does nothing for native C++/Vulkan/GLES games. |
| **CUT** | `dumpsys deviceidle force-idle` / `unforce` (`GameOptimizerEngine.kt:400-403, 471-474`) | Remove Doze forcing. Android's cached-app freezer handles background CPU/wakeup control safely without breaking foreground notifications/VoIP or stranding idle states. |
| **CUT** | Unused `dadb` + duplicate `PrivilegeManager.init` + `"36.2°C"` literal | Remove `libs.dadb` from `build.gradle.kts` & `proguard-rules.pro`; initialize `PrivilegeManager` once via Application/singleton; show `"--.-°C"` when thermal telemetry is unavailable. |
| **DOWNGRADE TO EXPERIMENTAL** | `wm size` / `wm density` render scaling | Gate behind an explicit "Experimental" warning detailing system-wide UI shrink, physical-surface game bypasses, and reboot persistence without root. Persist `cachedAutoRotate` (or stop touching `accelerometer_rotation` altogether since `monkey` was removed) and require verified clean `wm` reset before clearing snapshots. |
| **DOWNGRADE / TRIM** | `device_config set_sync_disabled_for_tests until_reboot` | Stop locking Phenotype sync globally `until_reboot`. Prefer `cmd game` on API 34+ and drop `device_config` mutations that silently fail or leave orphaned sync locks. |
| **RE-ARCHITECT** | `PrivilegeBackend` & `DaemonServer` | Replace `exec(): String` with `ShellResult(exitCode: Int, stdout: String, stderr: String, transportError: String?)`. Replace `sh -c` string interpolation with validated `ProcessBuilder` argv arrays. Move `DaemonServer` off hardcoded port `25890` (use ephemeral `0` + handshake or abstract Unix socket with `SO_PEERCRED` UID `== appUid` check), pass token via stdin/env instead of CLI args, add length-prefixed framing, drain stdout/stderr concurrently, and exclude `adb_keys` + `daemon_auth_token` in `backup_rules.xml` & `data_extraction_rules.xml`. |
| **RE-ARCHITECT** | `BoostSessionService` & `GameOptimizerEngine` | Serialize `ACTION_START`, `ACTION_SWITCH_FPS`, and `ACTION_STOP` with a single `Mutex`; fix `--mode 2` vs `custom` mismatch (`cmd game set --mode 2` + `cmd game mode 2`, or `custom` mode `4` consistently) and always execute `cmd game reset <pkg>` on stop; replace `ACTION_SWITCH_FPS` full restart with live FPS-only update (or explicit user confirmation); make watchdog foreground-aware (`dumpsys activity activities` / `topResumedActivity` + `ps -A -o PID,NAME` prefix match) with >=15s grace after `applyProfile()` finishes; tune `ThermalHysteresisEvaluator` to `>= THERMAL_STATUS_SEVERE` (or device-derived `headroom >= 1.0f`) polled at `>=10_000ms` and restore `currentTargetFps` on thermal release. |

---

## 6. Telemetry Feedback Loop Design (Closed-Loop Verification)

FrameGuard v1.0.8 must transition from **Open-Loop Execution** (`exec(cmd) -> assume success`) to a **Closed-Loop Verification Pipeline**:

```
[Snapshot Baseline] -> [Apply Intervention] -> [Verify Real OS State] -> [Re-assert / Downgrade / Rollback]
```

### 6.1 Structured Command Execution Contract
Every backend (`Shizuku`, `DaemonServer`, `AdbTlsClient`) returns:
```kotlin
data class ShellResult(
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
    val timedOut: Boolean = false,
    val transportError: String? = null
) {
    val isOk: Boolean get() = transportError == null && !timedOut && exitCode == 0
}
```
If `transportError != null` (e.g., `DeadObjectException`), `PrivilegeManager` immediately marks the backend disconnected, triggers rebind/reconnect, and **aborts snapshot deletion** during restore.

### 6.2 Per-Subsystem OS Verification Probes

1. **GameManager Intervention Verification (`dumpsys game`)**
   - **Apply (API 33+):** `cmd game set --mode 2 --fps <target> <pkg>` followed by `cmd game mode 2 <pkg>` (not `custom`!).
   - **Verify:** Run `dumpsys game` and parse the `[Name:<pkg> Modes: {...}]` stanza.
   - **Pass Criteria:** Active mode `2` contains `Fps:<target>`.
   - **Restore:** Run `cmd game reset <pkg>`, then re-query `dumpsys game` and assert `No intervention found for package <pkg>` (or absence of Mode 2/3 overrides).

2. **Display Refresh Rate & SurfaceFlinger Verification (`dumpsys display` + `dumpsys SurfaceFlinger` + `settings`)**
   - **Baseline Snapshot:** Read `settings get system peak_refresh_rate` and `min_refresh_rate` preserving literal `"null"` (unset) rather than replacing with `"120.0"`/`"60.0"`; on restore of `"null"`, issue `settings delete system peak_refresh_rate` / `min_refresh_rate`.
   - **Apply:** Query `Display.supportedModes` to confirm target Hz (`60`/`90`/`120`) exists (e.g., `45 FPS -> 90Hz` only if `90Hz` is in `supportedModes`, else `40 FPS -> 120Hz` or `60Hz`). Write `min` before `peak` when stepping down, `peak` before `min` when stepping up.
   - **Verify (Post-Launch + Periodic 10s Audit):**
     - `dumpsys display`: extract `mOverrideDisplayInfo` `mode <id>` and `renderFrameRate <hz>`.
     - `dumpsys SurfaceFlinger`: extract `activeMode={id=..., vsyncRate=<hz> Hz}` and `displayManagerPolicy={... primaryRanges={physical=[min, max], render=[min, max]}}`.
   - **Mismatch Handling:** If `vsyncRate` or `primaryRanges.max` diverges from target Hz after the game becomes `topResumedActivity`, detect OEM override (Joyose/GOS/OplusGames), log `OEM_OVERRIDE_DETECTED`, re-assert once, and surface accurate UI telemetry (`"Bypassed by OEM"` instead of fake `"Sync Locked"`).

3. **WindowManager Geometry Verification (`wm size` / `wm density`)**
   - **Pre-Apply Guard:** Snapshot only when `activeSnapshot == null` and persisted `has_snapshot == false` (never overwrite an existing baseline on `ACTION_SWITCH_FPS`). Fail fast if `wm size` / `wm density` `ShellResult` is not `isOk` or fails regex match (zero `1080x2400 @ 420` fallback).
   - **Verify Restore:** After running `buildRestoreCommands()`, query `wm size` and `wm density` again. **Only** call `clearGeometrySnapshot(context)` when `Override size` / `Override density` match the captured baseline (or are absent when baseline had no override).

4. **Thermal & Watchdog Verification (`dumpsys thermalservice` + `topResumedActivity`)**
   - **Thermal:** Poll `PowerManager.getThermalHeadroom(10)` at `>= 10_000ms` intervals. Parse `dumpsys thermalservice` `Current temperatures from HAL:` (ignoring stale `Cached temperatures:`) prioritizing `SKIN` and `SOC` over `BATTERY`. Read `Temperature headroom thresholds` at startup rather than hardcoding `0.90f`.
   - **Watchdog:** Confirm process health using both `ps -A -o PID,NAME` (matching `pkg` or `pkg:*` subprocesses) and `dumpsys activity activities` (`topResumedActivity` / `mResumedActivity`). Trigger restore immediately if the user leaves the game for >15–30s (or process exits), eliminating orphaned scaling/Hz locks when backgrounded.
