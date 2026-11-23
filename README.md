# FrameGuard

> **Utilitarian OS-Level Game Optimizer via Standalone Wireless ADB & Rootless Directives**

FrameGuard is an Android performance optimization utility engineered for consistent frame pacing, thermal headroom management, and stutter elimination. Operating under privileged shell level (**UID 2000**), FrameGuard applies system directives directly through its **built-in on-device Wireless ADB engine** or an optional **Shizuku bridge**—without modifying game APKs, injecting code, or requiring root access.

---

## ⚡ What's New in v1.0.5

- **Zero-PC Built-in Wireless ADB Engine**  
  Pair and authorize directly on-device using Android 11+ TLS 1.3 + SPAKE2 cryptography. Once paired, FrameGuard bootstraps an authenticated persistent daemon (`UID 2000`) over local loopback (`127.0.0.1:25890`), maintaining privileges throughout your session even after leaving Wi-Fi or turning Wireless Debugging off.
- **Dual-Backend Privilege Architecture (`PrivilegeBackend`)**  
  Seamlessly switches between Native Wireless ADB (`UID 2000 · ADB`) and external Shizuku (`UID 2000 · SHIZUKU`). If one backend disconnects, FrameGuard falls back gracefully.
- **Android 16 & API 36 Ready**  
  Built with `compileSdk = 36` and `targetSdk = 36`. Enforces test-sync lock (`device_config set_sync_disabled_for_tests until_reboot`) during active sessions so OS background sync cannot revert frame rate caps mid-gameplay.
- **Utilitarian Monospace Session Notification**  
  Custom obsidian (`#111111`) HUD notification featuring real-time battery/SoC temperature telemetry, active FPS/Hz badge, quick-action `30 FPS` thermal drop, and instant `STOP & RESTORE`.

---

## 🎯 Core Capabilities

- **Display Refresh Rate & Target FPS Pairing**  
  Synchronizes target game frame rates (45, 60, 90, 120 FPS) with physical display panel modes (60Hz, 90Hz, 120Hz) to enforce predictable VSYNC intervals and eliminate frame pacing jitter.

- **ART Dex2oat Ahead-Of-Time Compilation**  
  Triggers Android Runtime (ART) speed profile compilation (`cmd package compile -m speed -f <package>`) directly from the device to minimize runtime JIT overhead and micro-stutters during heavy gameplay.

- **Real-Time Telemetry & Thermal Monitoring**  
  Monitors hardware thermal sensors and throttling thresholds dynamically to provide transparent device status during sessions.

- **Aggressive Doze & Background Standby**  
  Applies system-level device idle directives to suppress non-essential background processes and reclaim CPU time during active game sessions.

- **Integrated Live Mechanical Terminal**  
  Displays transparent real-time logs of all ADB shell commands and system responses executed during profile application.

---

## 📱 OEM & Brand Compatibility Guide

FrameGuard v1.0.5 utilizes standard AOSP Android 11+ (API 30+) protocols. However, certain manufacturer OEM ROMs implement custom security rules or aggressive battery management:

### 1. Google Pixel, Motorola, Sony, Asus ROG, Nothing OS (Near-AOSP)
* **Status:** **100% Plug & Play.**
* **Details:** Uses clean, upstream Android implementation. Enable Wireless Debugging in Developer Options, input the 6-digit pairing code via the FrameGuard notification, and the daemon starts immediately without extra configuration.

### 2. Xiaomi, Redmi, POCO (MIUI / HyperOS)
* **Status:** Compatible (Requires 1 additional toggle).
* **Details:**
  * Open **Developer Options**.
  * Enable **"USB debugging (Security settings)"** (requires signing in with a Mi Account). This permission is mandatory for shell directives to adjust display refresh rates (`settings put system`).
  * If HyperOS automatically turns off Wireless Debugging after several minutes, FrameGuard is unaffected because its background `DaemonServer` runs independently in system RAM under UID 2000.

### 3. Samsung Galaxy (One UI)
* **Status:** **100% Compatible.**
* **Details:**
  * Samsung Knox does not restrict local TCP loopback (`127.0.0.1`) communication between application sandboxes and UID 2000.
  * If Samsung Game Optimizing Service (GOS) is active, FrameGuard still overrides display refresh rate bounds and enforces game frame pacing via `device_config`.

### 4. OPPO, Realme, OnePlus (ColorOS / OxygenOS / Realme UI)
* **Status:** Compatible (Requires 1 additional toggle).
* **Details:**
  * Open **Developer Options**.
  * Enable **"Disable permission monitoring"**. This prevents ColorOS from interrupting background shell command execution with security confirmation prompts.

### 5. Vivo & iQOO (Funtouch OS / OriginOS)
* **Status:** Compatible.
* **Details:**
  * Funtouch OS employs aggressive memory cleanup. It is recommended to **Lock** FrameGuard in the **Recent Apps** overview so the UI is not terminated during heavy gaming sessions. The privileged UID 2000 daemon continues executing in the background regardless.

### 6. Transsion (Infinix, Tecno, itel - XOS / HiOS)
* **Status:** Compatible.
* **Details:**
  * Ensure FrameGuard is excluded from the system "Freeze" application or automatic background task killers.

---

## 🏗️ Architecture & Technology Stack

- **UI Layer**: 100% Jetpack Compose with custom cybernetic utilitarian obsidian design system (`#080808`, `#111111`, `#242424`, `#7C3AED`, `#DC2626`).
- **State Management**: Kotlin Coroutines, `StateFlow`, and `SharedFlow` with reactive MVVM architecture.
- **Privilege Engine**: 
  - Standalone Android 11+ Wireless ADB Client (SPAKE2 + TLS 1.3 via `libadb-android` & Conscrypt).
  - Loopback IPC Server (`DaemonServer.kt`) running under `UID 2000` (`app_process`) with UUID token handshake.
  - Fallback Shizuku AIDL Binder Service (`IShellService.aidl`).
- **Target Platform**: Android 12 to Android 16 (API Level 31 to 36).

---

## 🚀 Getting Started

### Method 1: Built-in Wireless ADB (Recommended - Zero PC)
1. Connect your device to a **Wi-Fi** network (or enable a local Wi-Fi hotspot).
2. Open **Developer Options** in Android Settings and toggle on **Wireless debugging**.
3. Open FrameGuard and tap **`[ PAIR WIRELESS ADB ]`**.
4. In the Wireless Debugging screen, tap **Pair device with pairing code**.
5. Pull down the Android notification shade and enter the 6-digit code into FrameGuard's **ENTER CODE** notification action.
6. The header updates immediately to **`[ ● UID 2000 • ADB ]`**, and the setup card auto-collapses.

### Method 2: Shizuku (Alternative)
If you already have [Shizuku](https://shizuku.rikka.app/) running on your device, launch FrameGuard and tap **`[ CONNECT SHIZUKU ]`**.

---

## 📜 License

This project is licensed under the **FrameGuard Non-Commercial License**.  
Personal, educational, and research use is permitted. **Commercial use, distribution, or monetization is strictly prohibited.** See the [LICENSE](LICENSE) file for full terms.
