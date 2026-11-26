#!/bin/sh
# FrameGuard v1.0.7 Live Device Verification & Leak Audit Script
# Standalone POSIX shell script runnable:
#   1) From host PC via ADB (auto-detects adb binary & connected device)
#   2) Directly on-device inside Termux or `adb shell`
#
# Non-destructive by default (read-only probes).
# Pass --stress for opt-in round-trip mutation + forced cleanup verification
# protected by an EXIT/INT/TERM restore trap.

set -u

MODE="readonly"
TARGET_PKG=""
SERIAL=""
ADB_BIN="${ADB_BIN:-}"
THERMAL_SAMPLES=2
THERMAL_INTERVAL=10

PASS_COUNT=0
WARN_COUNT=0
FAIL_COUNT=0

# Baseline state for --stress restore trap
STRESS_ARMED=0
ORIG_PEAK_HZ=""
ORIG_MIN_HZ=""
ORIG_ROTATE=""
ORIG_WM_SIZE_LINE=""
ORIG_WM_DENSITY_LINE=""
ORIG_GAME_MODE=""
ORIG_GAME_FPS=""

usage() {
    cat <<'EOF'
Usage: audit_verify.sh [OPTIONS]

Options:
  -s, --serial <id>       ADB device serial (host mode only; auto-detects single device)
  -p, --pkg <package>     Target game package to inspect/test (e.g. com.digitalextremes.warframemobile)
      --stress            Opt-in active round-trip test (wm/cmd game/thermal sampling) with auto-restore trap
      --samples <n>       Thermal samples in --stress mode (default: 2)
      --interval <sec>    Seconds between thermal samples in --stress mode (default: 10)
  -h, --help              Show this help
EOF
}

while [ $# -gt 0 ]; do
    case "$1" in
        -s|--serial)
            [ $# -ge 2 ] || { echo "Missing value for $1" >&2; exit 2; }
            SERIAL="$2"
            shift 2
            ;;
        -p|--pkg)
            [ $# -ge 2 ] || { echo "Missing value for $1" >&2; exit 2; }
            TARGET_PKG="$2"
            shift 2
            ;;
        --stress)
            MODE="stress"
            shift
            ;;
        --samples)
            [ $# -ge 2 ] || { echo "Missing value for $1" >&2; exit 2; }
            THERMAL_SAMPLES="$2"
            shift 2
            ;;
        --interval)
            [ $# -ge 2 ] || { echo "Missing value for $1" >&2; exit 2; }
            THERMAL_INTERVAL="$2"
            shift 2
            ;;
        -h|--help)
            usage
            exit 0
            ;;
        *)
            echo "Unknown option: $1" >&2
            usage >&2
            exit 2
            ;;
    esac
done

# Validate target package name safely (no shell metacharacters)
if [ -n "$TARGET_PKG" ]; then
    case "$TARGET_PKG" in
        *[!a-zA-Z0-9._]*)
            echo "[ERROR] Invalid package name: $TARGET_PKG" >&2
            exit 2
            ;;
    esac
fi

# Detect execution context: local Android shell (Termux / adb shell) vs Host via ADB
EXEC_MODE="host"
if [ -d /system/bin ] && command -v dumpsys >/dev/null 2>&1; then
    EXEC_MODE="local"
fi

find_adb() {
    if [ -n "$ADB_BIN" ] && [ -x "$ADB_BIN" ]; then
        return 0
    fi
    if command -v adb >/dev/null 2>&1; then
        ADB_BIN="adb"
        return 0
    fi
    if command -v adb.exe >/dev/null 2>&1; then
        ADB_BIN="adb.exe"
        return 0
    fi
    # Common Windows / Linux / macOS Android SDK locations
    for candidate in \
        "${LOCALAPPDATA:-}/Android/Sdk/platform-tools/adb.exe" \
        "/c/Users/${USERNAME:-${USER:-}}/AppData/Local/Android/Sdk/platform-tools/adb.exe" \
        "/mnt/c/Users/${USERNAME:-${USER:-}}/AppData/Local/Android/Sdk/platform-tools/adb.exe" \
        "${ANDROID_HOME:-}/platform-tools/adb" \
        "${ANDROID_SDK_ROOT:-}/platform-tools/adb" \
        "$HOME/Android/Sdk/platform-tools/adb" \
        "$HOME/Library/Android/sdk/platform-tools/adb"
    do
        if [ -n "$candidate" ] && [ -f "$candidate" ]; then
            ADB_BIN="$candidate"
            return 0
        fi
    done
    return 1
}

if [ "$EXEC_MODE" = "host" ]; then
    if ! find_adb; then
        echo "[ERROR] 'adb' binary not found. Set ADB_BIN=/path/to/adb or add platform-tools to PATH." >&2
        exit 1
    fi
    if [ -z "$SERIAL" ]; then
        SERIAL="${ANDROID_SERIAL:-}"
    fi
    if [ -z "$SERIAL" ]; then
        DEV_LIST=$("$ADB_BIN" devices 2>/dev/null | awk 'NR>1 && $2=="device" {print $1}')
        DEV_COUNT=$(printf '%s\n' "$DEV_LIST" | awk 'NF{c++} END{print c+0}')
        if [ "$DEV_COUNT" -eq 0 ]; then
            echo "[ERROR] No authorized ADB device connected." >&2
            exit 1
        elif [ "$DEV_COUNT" -gt 1 ]; then
            echo "[ERROR] Multiple ADB devices connected. Specify -s <serial>:" >&2
            printf '%s\n' "$DEV_LIST" >&2
            exit 1
        fi
        SERIAL=$(printf '%s\n' "$DEV_LIST" | awk 'NF{print; exit}')
    fi
fi

run_target() {
    if [ "$EXEC_MODE" = "local" ]; then
        sh -c "$1"
    else
        "$ADB_BIN" -s "$SERIAL" shell "$1"
    fi
}

log_pass() {
    PASS_COUNT=$((PASS_COUNT + 1))
    printf '[PASS] %s\n' "$1"
}

log_warn() {
    WARN_COUNT=$((WARN_COUNT + 1))
    printf '[WARN] %s\n' "$1"
}

log_fail() {
    FAIL_COUNT=$((FAIL_COUNT + 1))
    printf '[FAIL] %s\n' "$1"
}

log_info() {
    printf '[INFO] %s\n' "$1"
}

restore_stress_state() {
    if [ "$STRESS_ARMED" -ne 1 ]; then
        return 0
    fi
    STRESS_ARMED=0
    printf '\n[TRAP] Restoring pre-stress device state...\n'

    # Restore wm size
    case "$ORIG_WM_SIZE_LINE" in
        *Override\ size:*)
            orig_sz=$(printf '%s' "$ORIG_WM_SIZE_LINE" | sed -n 's/.*Override size:[[:space:]]*\([0-9]*x[0-9]*\).*/\1/p')
            [ -n "$orig_sz" ] && run_target "wm size $orig_sz" >/dev/null 2>&1 || run_target "wm size reset" >/dev/null 2>&1
            ;;
        *)
            run_target "wm size reset" >/dev/null 2>&1
            ;;
    esac

    # Restore wm density
    case "$ORIG_WM_DENSITY_LINE" in
        *Override\ density:*)
            orig_den=$(printf '%s' "$ORIG_WM_DENSITY_LINE" | sed -n 's/.*Override density:[[:space:]]*\([0-9]*\).*/\1/p')
            [ -n "$orig_den" ] && run_target "wm density $orig_den" >/dev/null 2>&1 || run_target "wm density reset" >/dev/null 2>&1
            ;;
        *)
            run_target "wm density reset" >/dev/null 2>&1
            ;;
    esac

    # Restore refresh rates
    if [ -n "$ORIG_PEAK_HZ" ] && [ "$ORIG_PEAK_HZ" != "null" ]; then
        run_target "settings put system peak_refresh_rate $ORIG_PEAK_HZ" >/dev/null 2>&1
    else
        run_target "settings delete system peak_refresh_rate" >/dev/null 2>&1
    fi
    if [ -n "$ORIG_MIN_HZ" ] && [ "$ORIG_MIN_HZ" != "null" ]; then
        run_target "settings put system min_refresh_rate $ORIG_MIN_HZ" >/dev/null 2>&1
    else
        run_target "settings delete system min_refresh_rate" >/dev/null 2>&1
    fi

    # Restore accelerometer_rotation
    if [ "$ORIG_ROTATE" = "0" ] || [ "$ORIG_ROTATE" = "1" ]; then
        run_target "settings put system accelerometer_rotation $ORIG_ROTATE" >/dev/null 2>&1
    fi

    # Restore target package game mode if touched
    if [ -n "$TARGET_PKG" ]; then
        run_target "cmd game reset $TARGET_PKG" >/dev/null 2>&1
        if [ -n "$ORIG_GAME_MODE" ] && [ -n "$ORIG_GAME_FPS" ]; then
            run_target "cmd game set --mode $ORIG_GAME_MODE --fps $ORIG_GAME_FPS $TARGET_PKG" >/dev/null 2>&1
            run_target "cmd game mode $ORIG_GAME_MODE $TARGET_PKG" >/dev/null 2>&1
        fi
    fi
    printf '[TRAP] Restoration complete.\n'
}

trap restore_stress_state EXIT INT TERM

# ==============================================================================
# Header / Device Identity
# ==============================================================================
DEV_MODEL=$(run_target "getprop ro.product.model" | tr -d '\r')
DEV_REL=$(run_target "getprop ro.build.version.release" | tr -d '\r')
DEV_SDK=$(run_target "getprop ro.build.version.sdk" | tr -d '\r')
FG_SERVICE_OUT=$(run_target "dumpsys activity services com.optimizer.game" | tr -d '\r')
FG_ACTIVE=0
if printf '%s' "$FG_SERVICE_OUT" | grep -q "BoostSessionService"; then
    FG_ACTIVE=1
fi

printf '================================================================\n'
printf 'FrameGuard Audit Verification (%s mode)\n' "$MODE"
printf 'Target: %s | Android %s (API %s) | Exec: %s\n' "$DEV_MODEL" "$DEV_REL" "$DEV_SDK" "$EXEC_MODE"
if [ "$EXEC_MODE" = "host" ]; then
    printf 'Serial: %s (%s)\n' "$SERIAL" "$ADB_BIN"
fi
printf 'FrameGuard BoostSessionService active: %s\n' "$([ "$FG_ACTIVE" -eq 1 ] && echo YES || echo NO)"
printf '================================================================\n\n'

# ==============================================================================
# (A) FPS Lock, dumpsys game, dumpsys display & SurfaceFlinger Persistence
# ==============================================================================
printf '%s\n' '--- [A] FPS Lock, Display Mode & SurfaceFlinger Consistency ---'

PEAK_HZ=$(run_target "settings get system peak_refresh_rate" | tr -d '\r\n ')
MIN_HZ=$(run_target "settings get system min_refresh_rate" | tr -d '\r\n ')
log_info "Settings refresh rates: peak_refresh_rate=${PEAK_HZ:-unset}, min_refresh_rate=${MIN_HZ:-unset}"

DISP_SUMMARY=$(run_target "dumpsys display" | tr -d '\r' | awk '
    /mOverrideDisplayInfo=DisplayInfo/ {
        if (match($0, /mode [0-9]+/)) mode=substr($0, RSTART, RLENGTH);
        if (match($0, /renderFrameRate [0-9.]+/)) rfr=substr($0, RSTART, RLENGTH);
        if (match($0, /supportedRefreshRates \[[^]]+\]/)) sup=substr($0, RSTART, RLENGTH);
        print mode " | " rfr " | " sup;
        exit;
    }'
)
if [ -n "$DISP_SUMMARY" ]; then
    log_info "dumpsys display: $DISP_SUMMARY"
else
    log_warn "Could not parse mOverrideDisplayInfo from dumpsys display"
fi

SF_LINES=$(run_target "dumpsys SurfaceFlinger" 2>/dev/null | tr -d '\r' | grep -E 'activeMode=|displayManagerPolicy=' || true)
SF_ACTIVE=$(printf '%s\n' "$SF_LINES" | grep -m1 "activeMode=" | sed 's/^[[:space:]]*//')
SF_POLICY=$(printf '%s\n' "$SF_LINES" | grep -m1 "displayManagerPolicy=" | sed 's/^[[:space:]]*//')
SF_VSYNC=$(printf '%s\n' "$SF_ACTIVE" | sed -n 's/.*vsyncRate=\([0-9.]*\).*/\1/p')

[ -n "$SF_ACTIVE" ] && log_info "SurfaceFlinger $SF_ACTIVE" || log_warn "SurfaceFlinger activeMode not readable"
[ -n "$SF_POLICY" ] && log_info "SurfaceFlinger $SF_POLICY"

if [ -n "$PEAK_HZ" ] && [ "$PEAK_HZ" != "null" ] && [ -n "$SF_VSYNC" ]; then
    HZ_MATCH=$(awk -v a="$PEAK_HZ" -v b="$SF_VSYNC" 'BEGIN { d=a-b; if (d<0) d=-d; print (d<0.5) ? 1 : 0 }')
    if [ "$HZ_MATCH" -eq 1 ]; then
        log_pass "peak_refresh_rate ($PEAK_HZ) aligns with SurfaceFlinger active vsyncRate ($SF_VSYNC Hz)"
    else
        log_warn "Refresh mismatch: settings peak=$PEAK_HZ vs SurfaceFlinger vsyncRate=$SF_VSYNC Hz (adaptive/OEM override active)"
    fi
fi

INTERVENTIONS=$(run_target "dumpsys game" | tr -d '\r' | grep -E '\[Name:.*Modes:' || true)
INTERVENTION_COUNT=$(printf '%s\n' "$INTERVENTIONS" | awk 'NF{c++} END{print c+0}')

if [ "$INTERVENTION_COUNT" -gt 0 ]; then
    printf '%s\n' "$INTERVENTIONS" | while IFS= read -r line; do
        [ -n "$line" ] && log_info "dumpsys game: $line"
    done
    if [ "$FG_ACTIVE" -eq 0 ]; then
        log_fail "Found $INTERVENTION_COUNT persisted dumpsys game intervention(s) while BoostSessionService is IDLE"
    else
        log_pass "Active dumpsys game interventions ($INTERVENTION_COUNT) while BoostSessionService is running"
    fi
else
    log_pass "No orphaned interventions in dumpsys game"
fi

if [ -n "$TARGET_PKG" ]; then
    PKG_GAME_LINE=$(printf '%s\n' "$INTERVENTIONS" | grep -F "[Name:$TARGET_PKG " || true)
    if [ -n "$PKG_GAME_LINE" ]; then
        log_info "Target package intervention: $PKG_GAME_LINE"
    else
        log_info "Target package ($TARGET_PKG) has no active dumpsys game intervention"
    fi
fi

# ==============================================================================
# (B) GPU Load & WindowManager Size / Density Snapshot & Override Delta
# ==============================================================================
printf '\n--- [B] WindowManager Geometry & GPU Load Telemetry ---\n'

WM_SIZE=$(run_target "wm size" | tr -d '\r')
WM_DENSITY=$(run_target "wm density" | tr -d '\r')
PHYS_SIZE=$(printf '%s\n' "$WM_SIZE" | sed -n 's/.*Physical size:[[:space:]]*\([0-9]*x[0-9]*\).*/\1/p')
OVER_SIZE=$(printf '%s\n' "$WM_SIZE" | sed -n 's/.*Override size:[[:space:]]*\([0-9]*x[0-9]*\).*/\1/p')
PHYS_DEN=$(printf '%s\n' "$WM_DENSITY" | sed -n 's/.*Physical density:[[:space:]]*\([0-9]*\).*/\1/p')
OVER_DEN=$(printf '%s\n' "$WM_DENSITY" | sed -n 's/.*Override density:[[:space:]]*\([0-9]*\).*/\1/p')

log_info "wm size: Physical=${PHYS_SIZE:-unknown} Override=${OVER_SIZE:-none}"
log_info "wm density: Physical=${PHYS_DEN:-unknown} Override=${OVER_DEN:-none}"

if [ -n "$OVER_SIZE" ] || [ -n "$OVER_DEN" ]; then
    if [ -n "$PHYS_SIZE" ] && [ -n "$OVER_SIZE" ]; then
        PW=${PHYS_SIZE%x*}
        OW=${OVER_SIZE%x*}
        SCALE_PCT=$(awk -v o="$OW" -v p="$PW" 'BEGIN { if (p>0) printf "%.1f", (o*100.0)/p; else print "0" }')
        log_info "Active resolution scale delta: ${SCALE_PCT}% (${OVER_SIZE} vs ${PHYS_SIZE})"
    fi
    if [ "$FG_ACTIVE" -eq 0 ]; then
        log_fail "Display geometry override leaked while BoostSessionService is IDLE"
    else
        log_pass "Display geometry override active during BoostSessionService session"
    fi
else
    log_pass "Display geometry clean (no wm size/density override)"
fi

# Best-effort non-root GPU utilization probe (Qualcomm KGSL / MediaTek GED / Devfreq)
GPU_PROBE=$(run_target '
    for p in /sys/class/kgsl/kgsl-3d0/gpubusy /sys/kernel/ged/hal/gpu_utilization /sys/class/devfreq/13000000.mali/load; do
        if [ -r "$p" ]; then
            echo "$p=$(cat "$p" 2>/dev/null | tr "\n" " ")"
            exit 0
        fi
    done
    echo "UNAVAILABLE"
' | tr -d '\r')
if [ "$GPU_PROBE" = "UNAVAILABLE" ]; then
    log_info "Sysfs GPU load unreadable under UID $(run_target 'id -u' | tr -d '\r') SELinux policy (expected on production Android 14+)"
else
    log_info "GPU load node: $GPU_PROBE"
fi

# ==============================================================================
# (C) Thermal Status, Cached vs Live HAL & Headroom Thresholds
# ==============================================================================
printf '\n--- [C] Thermal HAL Status, Cached vs Live Delta & Headroom ---\n'

THERMAL_SUMMARY=$(run_target "dumpsys thermalservice" | tr -d '\r' | awk '
    /^Thermal Status:/ { print "STATUS=" $3; next }
    /ThermalHAL/ { gsub(/^[ \t]+/, ""); print "HAL=" $0; next }
    /Temperature headroom thresholds:/ { getline; print "THRESH=" $0; next }
    /Cached temperatures:/ { sec="cached"; next }
    /HAL Ready:/ { sec=""; next }
    /Current temperatures from HAL:/ { sec="live"; next }
    /Current cooling devices/ { sec=""; next }
    sec=="cached" && /mName=SKIN/ { gsub(/^[ \t]+/, ""); print "CSKIN=" $0; next }
    sec=="live" && /mName=SKIN/ { gsub(/^[ \t]+/, ""); print "LSKIN=" $0; next }
    sec=="live" && /mName=SOC/ { gsub(/^[ \t]+/, ""); print "LSOC=" $0; next }
')
THERMAL_STATUS=$(printf '%s\n' "$THERMAL_SUMMARY" | sed -n 's/^STATUS=//p' | head -n1)
HAL_CONN=$(printf '%s\n' "$THERMAL_SUMMARY" | sed -n 's/^HAL=//p' | head -n1)
HEADROOM_THRESH=$(printf '%s\n' "$THERMAL_SUMMARY" | sed -n 's/^THRESH=//p' | head -n1)
CACHED_SKIN=$(printf '%s\n' "$THERMAL_SUMMARY" | sed -n 's/^CSKIN=//p' | head -n1)
LIVE_SKIN=$(printf '%s\n' "$THERMAL_SUMMARY" | sed -n 's/^LSKIN=//p' | head -n1)
LIVE_SOC=$(printf '%s\n' "$THERMAL_SUMMARY" | sed -n 's/^LSOC=//p' | head -n1)
BAT_TEMP=$(run_target "dumpsys battery" | awk -F: '/temperature:/{gsub(/[[:space:]]/,"",$2); printf "%.1f", $2/10.0; exit}')

log_info "HAL: ${HAL_CONN:-unknown} | Thermal Status=${THERMAL_STATUS:-unknown} | Battery=${BAT_TEMP:-?}C"
[ -n "$CACHED_SKIN" ] && log_info "Cached SKIN: $CACHED_SKIN"
[ -n "$LIVE_SKIN" ] && log_info "Live HAL SKIN: $LIVE_SKIN"
[ -n "$LIVE_SOC" ] && log_info "Live HAL SOC: $LIVE_SOC"
[ -n "$HEADROOM_THRESH" ] && log_info "Headroom thresholds [NONE..SHUTDOWN]: $HEADROOM_THRESH"

if [ -n "$CACHED_SKIN" ] && [ -n "$LIVE_SKIN" ]; then
    CS_VAL=$(printf '%s' "$CACHED_SKIN" | sed -n 's/.*mValue=\([0-9.-]*\).*/\1/p')
    LS_VAL=$(printf '%s' "$LIVE_SKIN" | sed -n 's/.*mValue=\([0-9.-]*\).*/\1/p')
    if [ -n "$CS_VAL" ] && [ -n "$LS_VAL" ]; then
        DELTA=$(awk -v c="$CS_VAL" -v l="$LS_VAL" 'BEGIN { d=c-l; if (d<0) d=-d; printf "%.2f", d }')
        HIGH_DRIFT=$(awk -v d="$DELTA" 'BEGIN { print (d >= 5.0) ? 1 : 0 }')
        if [ "$HIGH_DRIFT" -eq 1 ]; then
            log_warn "Cached vs Live SKIN thermal drift is ${DELTA}C (cached=${CS_VAL}C, live=${LS_VAL}C) -- stale Thermal Status=$THERMAL_STATUS"
        else
            log_pass "Cached vs Live SKIN within ${DELTA}C"
        fi
    fi
fi

# ==============================================================================
# (D) Post-Kill / Idle State Restoration Leak Checks
# ==============================================================================
printf '\n--- [D] Post-Kill / Idle State Restoration Leak Audit ---\n'

ROTATE_VAL=$(run_target "settings get system accelerometer_rotation" | tr -d '\r\n ')
DEVICEIDLE_STATE=$(run_target "dumpsys deviceidle get deep" | tr -d '\r\n ')
SYNC_DISABLED=$(run_target "device_config get_sync_disabled_for_tests" 2>/dev/null | tr -d '\r\n ' || echo "unknown")
OVERLAY_LIST=$(run_target "device_config list game_overlay" 2>/dev/null | tr -d '\r' || true)

log_info "accelerometer_rotation=${ROTATE_VAL:-unset} | deviceidle_deep=${DEVICEIDLE_STATE:-unknown} | sync_disabled=${SYNC_DISABLED:-unknown}"

if [ "$FG_ACTIVE" -eq 0 ]; then
    if [ "$DEVICEIDLE_STATE" != "ACTIVE" ] && [ -n "$DEVICEIDLE_STATE" ]; then
        log_warn "deviceidle deep is '$DEVICEIDLE_STATE' while BoostSessionService is IDLE"
    else
        log_pass "deviceidle deep is ACTIVE (not stuck in force-idle)"
    fi

    if [ "$SYNC_DISABLED" != "none" ] && [ -n "$SYNC_DISABLED" ] && [ "$SYNC_DISABLED" != "null" ]; then
        log_fail "device_config sync lock leaked: $SYNC_DISABLED"
    else
        log_pass "device_config sync lock clean ($SYNC_DISABLED)"
    fi

    if [ -n "$OVERLAY_LIST" ]; then
        log_fail "Leftover device_config game_overlay entries found while IDLE:"
        printf '%s\n' "$OVERLAY_LIST"
    else
        log_pass "device_config game_overlay clean"
    fi
fi

# ==============================================================================
# Optional --stress Round-Trip Verification (Opt-In, Trap-Protected)
# ==============================================================================
if [ "$MODE" = "stress" ]; then
    printf '\n--- [STRESS] Opt-In Round-Trip & Restore Trap Verification ---\n'
    ORIG_PEAK_HZ="$PEAK_HZ"
    ORIG_MIN_HZ="$MIN_HZ"
    ORIG_ROTATE="$ROTATE_VAL"
    ORIG_WM_SIZE_LINE=$(printf '%s\n' "$WM_SIZE" | grep -m1 "Override size:" || true)
    ORIG_WM_DENSITY_LINE=$(printf '%s\n' "$WM_DENSITY" | grep -m1 "Override density:" || true)
    if [ -n "$TARGET_PKG" ]; then
        ORIG_GAME_MODE=$(printf '%s\n' "$INTERVENTIONS" | grep -F "[Name:$TARGET_PKG " | sed -n 's/.*Game Mode:\([0-9]*\).*/\1/p' | head -n1)
        ORIG_GAME_FPS=$(printf '%s\n' "$INTERVENTIONS" | grep -F "[Name:$TARGET_PKG " | sed -n 's/.*Fps:\([0-9]*\).*/\1/p' | head -n1)
    fi
    STRESS_ARMED=1

    # 1. Test non-destructive wm size/density override + restore round-trip
    if [ -n "$PHYS_SIZE" ] && [ -n "$PHYS_DEN" ]; then
        PW=${PHYS_SIZE%x*}
        PH=${PHYS_SIZE#*x}
        TEST_W=$(( (PW * 90 / 100) / 2 * 2 ))
        TEST_H=$(( (PH * 90 / 100) / 2 * 2 ))
        TEST_D=$(( PHYS_DEN * 90 / 100 ))
        log_info "Testing wm round-trip (${TEST_W}x${TEST_H} @ ${TEST_D}dpi)..."
        run_target "wm size ${TEST_W}x${TEST_H}; wm density ${TEST_D}" >/dev/null 2>&1
        MID_SIZE=$(run_target "wm size" | tr -d '\r')
        if printf '%s' "$MID_SIZE" | grep -q "Override size: ${TEST_W}x${TEST_H}"; then
            log_pass "wm size override verified active (${TEST_W}x${TEST_H})"
        else
            log_fail "wm size override did not take effect"
        fi
        run_target "wm size reset; wm density reset" >/dev/null 2>&1
        POST_SIZE=$(run_target "wm size" | tr -d '\r')
        if printf '%s' "$POST_SIZE" | grep -q "Override size:"; then
            log_fail "wm size reset failed to clear Override size"
        else
            log_pass "wm size reset verified clean"
        fi
    fi

    # 2. Test cmd game mode 2 + mode 1 leak vs cmd game reset (if --pkg provided)
    if [ -n "$TARGET_PKG" ]; then
        log_info "Testing cmd game mode 2 + reset round-trip for $TARGET_PKG..."
        run_target "cmd game set --mode 2 --fps 60 $TARGET_PKG && cmd game mode 2 $TARGET_PKG" >/dev/null 2>&1
        G_CHECK=$(run_target "dumpsys game" | tr -d '\r' | grep -F "[Name:$TARGET_PKG " || true)
        if printf '%s' "$G_CHECK" | grep -q "Fps:60"; then
            log_pass "cmd game set --mode 2 --fps 60 verified in dumpsys game"
        else
            log_warn "cmd game intervention not reflected in dumpsys game ($G_CHECK)"
        fi
        run_target "cmd game reset $TARGET_PKG" >/dev/null 2>&1
        G_POST=$(run_target "dumpsys game" | tr -d '\r' | grep -F "[Name:$TARGET_PKG " || true)
        if printf '%s' "$G_POST" | grep -q "Game Mode:2"; then
            log_fail "cmd game reset left Mode 2 behind for $TARGET_PKG"
        else
            log_pass "cmd game reset cleared intervention cleanly for $TARGET_PKG"
        fi
    else
        log_info "Skipping cmd game round-trip (pass -p <package> to enable)"
    fi

    # 3. Thermal transition sampling
    i=1
    while [ "$i" -le "$THERMAL_SAMPLES" ]; do
        S_PAIR=$(run_target "dumpsys thermalservice" | tr -d '\r' | awk '
            /^Thermal Status:/ { st=$3 }
            /Current temperatures from HAL:/ { sec="live"; next }
            /Current cooling devices/ { sec=""; next }
            sec=="live" && /mName=SKIN/ {
                if (match($0, /mValue=[0-9.-]+/)) sk=substr($0, RSTART+7, RLENGTH-7)
            }
            END { printf "%s|%s", st, sk }
        ')
        S_STAT=${S_PAIR%%|*}
        S_SKIN=${S_PAIR#*|}
        log_info "Thermal sample $i/$THERMAL_SAMPLES: status=${S_STAT:-?} live_skin=${S_SKIN:-?}C"
        if [ "$i" -lt "$THERMAL_SAMPLES" ]; then
            sleep "$THERMAL_INTERVAL"
        fi
        i=$((i + 1))
    done

    restore_stress_state
fi

printf '\n================================================================\n'
printf 'Summary: PASS=%d  WARN=%d  FAIL=%d\n' "$PASS_COUNT" "$WARN_COUNT" "$FAIL_COUNT"
printf '================================================================\n'

[ "$FAIL_COUNT" -eq 0 ]
