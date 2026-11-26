# FrameGuard

> **Utilitarian OS-Level Game Optimizer via Standalone Wireless ADB & Rootless Directives**  
> **Official Repository:** [https://github.com/ArtixForLiving/FrameGuard](https://github.com/ArtixForLiving/FrameGuard)

FrameGuard is an open-source Android performance optimization tool engineered to deliver consistent frame pacing, lower operating temperatures, and stutter-free gaming. By utilizing privileged shell access (**UID 2000**)—either through its built-in on-device Wireless ADB engine or Shizuku—FrameGuard applies native Android system directives directly. It requires no root access, modifies zero game files, and injects no code into game processes.

---

## 💡 Philosophy

Modern mobile gaming often suffers from erratic frame pacing, thermal throttling, and aggressive battery drain. FrameGuard is built around four core principles:

1. **Deterministic Stability Over Unconstrained FPS**  
   A stable, rock-solid 60 FPS with consistent 16.6ms frame times always feels smoother than a game that spikes to 90 FPS and immediately drops to 40 FPS due to thermal saturation. FrameGuard prioritizes consistent frame pacing over theoretical peak numbers.

2. **Thermal & Battery Preservation**  
   Instead of waiting for the hardware to overheat and trigger abrupt thermal throttling by the OS kernel, FrameGuard proactively manages render workloads and thermal thresholds, maintaining a sustainable device temperature for long gaming sessions.

3. **Zero Root & Zero Game Modification**  
   FrameGuard operates strictly within official Android framework APIs and system shell boundaries (`UID 2000`). It does not modify APKs, patch memory, or hook game engines. Your games remain 100% untouched, ensuring complete safety with anti-cheat systems and Google Play Integrity.

4. **Complete Transparency (No Snake Oil)**  
   FrameGuard has no hidden tricks or placebo toggles. Every optimization maps directly to documented Android system commands (`cmd game`, `settings`, `wm`, `am kill-all`, `cmd notification set_dnd`). An integrated live terminal lets you see exactly what commands run on your device.

---

## ⚡ What FrameGuard Does (Core Capabilities)

- **Target FPS & Refresh Rate Synchronization**  
  Synchronizes chosen target frame rates (30, 45, 60, 90, 120 FPS) with physical display panel modes (60Hz, 90Hz, 120Hz). This locks the hardware VSYNC interval, eliminating micro-stutters and frame pacing jitter.

- **Per-App Game Downscale & Experimental WM Render Scale**  
  Applies per-app surface buffer scaling (`cmd game set --downscale`: `1.0x`, `0.85x`, `0.75x`, `0.6x`) without altering System UI, plus an optional **Experimental WM Render Scale** (`wm size` / `wm density`) with verified snapshot recovery.

- **Adaptive Thermal Limiter**  
  Polls Android `ThermalHAL` (`PowerManager.getThermalHeadroom`) every 10s. If the device reaches `SEVERE` thermal headroom, FrameGuard clamps supported games to 30 FPS and releases back to the target FPS when cool.

- **Safe RAM Trim (`am kill-all`)**  
  Clears cached background processes before launching your game to free memory bandwidth without killing active foreground services.

- **Auto Gaming DND (`cmd notification set_dnd`)**  
  Automatically switches Do Not Disturb to `priority` during boost sessions (when previously off) and restores your original `zen_mode` on exit.

- **Live Capability Probe & 3-Stage Filtered UI**  
  Probes device support (`cmd game`, `wm` write access, `set_dnd`, panel refresh rates, and OEM game daemons) on first connect and hides unsupported controls.

- **Transparent Live Shell Terminal**  
  Displays a real-time console log in the UI showing every shell command executed and the exact response from Android's subsystem.

---

## 📖 How to Use FrameGuard

### 1. Grant Shell Permissions (One-Time Setup)

Choose either of the two rootless options:

* **Option A: Built-in Wireless ADB (Zero PC Required)**
  1. Connect to Wi-Fi (or turn on a personal Wi-Fi Hotspot).
  2. Open Android **Settings** > **Developer Options** and enable **Wireless debugging**.
  3. Open FrameGuard, tap **`[ PAIR WIRELESS ADB ]`**, then tap **Pair device with pairing code** in Android settings.
  4. Pull down the notification shade and type the 6-digit pairing code into FrameGuard's prompt.
  5. The status indicator will turn green: **`[ ● UID 2000 • ADB ]`**.

* **Option B: Shizuku Bridge**
  1. If you already have [Shizuku](https://shizuku.rikka.app/) installed and running, simply open FrameGuard and tap **`[ CONNECT SHIZUKU ]`**.
  2. Grant permission when prompted.

### 2. Configure Your Game Profile

1. **Select Target App:** Tap **CHANGE** in Target Application and choose your game from the list.
2. **Set Frame Pacing & Display Mode:** Choose your target frame rate (e.g., 60 FPS, 90 FPS, 120 FPS). FrameGuard automatically pairs the display refresh rate.
3. **Choose Per-App Downscale:** Select `1.0x` (Native), `0.85x`, `0.75x`, or `0.6x` (applies on launch; Experimental **WM Render Scale** is also available if supported).
4. **Adaptive Thermal Limiter:** Set to **OFF** for standard sessions, or **ADAPTIVE** for long gaming sessions where you want automatic 30 FPS thermal protection.
5. **System Directives:** Toggle **Safe RAM Trim** (`am kill-all`) and **Auto Gaming DND** (`set_dnd`) as desired.

### 3. Launch & Play

- Tap **`ENGAGE PROFILE & LAUNCH`**. FrameGuard applies all directives, verifies `GameManager` Mode 2, displays the output in the terminal, and launches your game.
- A persistent notification lets you monitor status (`SYNC LOCKED`, `THERMAL 30`, `RATE OVERRIDDEN`) or live-switch to **30 FPS** without restarting the game.

### 4. Restore When Finished

- When you finish playing (or exit the game), tap **`DISENGAGE & RESTORE DEFAULTS`** inside FrameGuard or **`STOP & RESTORE`** from the notification. The foreground watchdog also restores `cmd game`, refresh rates, `zen_mode`, and `wm` geometry automatically.

---

## 📱 Brand-Specific Setup Guide

FrameGuard uses standard Android 11+ AOSP APIs. However, because different phone manufacturers customize Android, here is what you need to know for your specific brand:

### Google Pixel, Motorola, Sony, Nothing OS, Asus ROG
* **Compatibility:** 100% Plug & Play.
* **Guide:** Clean, near-stock Android. No special configurations required. Pair via Wireless ADB or Shizuku, and it works immediately.

### Xiaomi, Redmi, POCO (MIUI / HyperOS)
* **Compatibility:** Supported (requires 1 extra setting).
* **Guide:**
  1. Go to **Settings** > **Developer Options**.
  2. Turn on **"USB debugging (Security settings)"** (requires Mi Account login). This is required so the system allows refresh rate and system setting changes.
  3. *Note:* If HyperOS disables Wireless Debugging after a few minutes of inactivity, FrameGuard's background daemon will still keep running normally in RAM.

### Samsung Galaxy (One UI)
* **Compatibility:** 100% Supported.
* **Guide:**
  - Works out of the box with Wireless ADB or Shizuku.
  - Samsung Knox does not block local loopback (`127.0.0.1`) communication.
  - FrameGuard's system directives coordinate with Samsung Game Optimizing Service (GOS) via `cmd game` and system refresh rate directives.

### OPPO, OnePlus, Realme (ColorOS / OxygenOS / Realme UI)
* **Compatibility:** Supported (requires 1 extra setting).
* **Guide:**
  1. Go to **Settings** > **Developer Options**.
  2. Turn on **"Disable permission monitoring"**. This prevents the system from showing security prompt dialogs whenever shell commands are executed.

### Vivo & iQOO (Funtouch OS / OriginOS)
* **Compatibility:** Supported.
* **Guide:**
  - Vivo's memory management is very aggressive.
  - Open the **Recent Apps** screen and **Lock** FrameGuard so the system does not kill the app while your game is running. (The background shell daemon will continue running regardless).

### Transsion Devices: Infinix, Tecno, itel (XOS / HiOS)
* **Compatibility:** Supported.
* **Guide:**
  - Exclude FrameGuard from the built-in "Freeze" app or aggressive battery cleaner to ensure background telemetry continues uninterrupted.

---

## 🏗️ Architecture & Technology Stack

FrameGuard is engineered following modern Android architecture standards:

- **UI & Presentation:** 100% [Jetpack Compose](https://developer.android.com/jetpack/compose) with a utilitarian dark cybernetic theme (`#080808`, `#111111`, `#242424`, `#F2F2EE`).
- **Reactive State:** Kotlin Coroutines, `StateFlow`, and `SharedFlow` with an unidirectional data flow (MVI / MVVM).
- **Privilege Backends (`PrivilegeBackend`):**
  - **Embedded Wireless ADB Engine:** Pure on-device implementation utilizing SPAKE2 key exchange and TLS 1.3 over loopback (`127.0.0.1`), spawning an authenticated standalone `app_process` daemon running under `UID 2000`.
  - **Shizuku IPC Bridge:** Fallback AIDL binder interface (`IShellService.aidl`) connecting to the system Shizuku service.
- **System Directives Engine:** Interacts with low-level Android subsystems:
  - `cmd game` (Android 13+) for Game Mode 2 FPS limits and per-app buffer downscaling.
  - `settings put/delete system` for LTPO-safe hardware refresh rate bounds.
  - `am kill-all` for safe pre-launch background RAM trimming.
  - `cmd notification set_dnd` for Auto Gaming DND (`zen_mode`).
  - `wm size` / `wm density` for optional experimental display scaling with snapshot recovery.
  - `dumpsys thermalservice` & Android `PowerManager` ThermalHAL for real-time thermal telemetry.
- **Platform Compatibility:** Targets Android 12 through Android 17 (API Level 31 to 37).

---

## 📜 License

This project is licensed under the **GNU General Public License v3.0 (GPL-3.0)**.

You are free to use, modify, and distribute this software under the terms of the GPL-3.0 license. See the [LICENSE](LICENSE) file for full details.

