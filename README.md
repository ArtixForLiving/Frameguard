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
   FrameGuard has no hidden tricks or placebo "RAM booster" buttons. Every optimization maps directly to documented Android system commands (`device_config`, `settings`, `cmd package compile`, `dumpsys deviceidle`). An integrated live terminal lets you see exactly what commands run on your device.

---

## ⚡ What FrameGuard Does (Core Capabilities)

- **Target FPS & Refresh Rate Synchronization**  
  Synchronizes chosen target frame rates (30, 45, 60, 90, 120 FPS) with physical display panel modes (60Hz, 90Hz, 120Hz). This locks the hardware VSYNC interval, eliminating micro-stutters and frame pacing jitter.

- **Pre-Launch Swapchain Render Scaling**  
  Adjusts the internal display window render scale (100% Native, 75% Balanced, 50% Ultra-Performance) before the game launches. This significantly reduces GPU fill-rate workload on demanding 3D games while keeping the display crisp.

- **Adaptive Thermal Limiter**  
  Monitors Android HAL thermal status in real-time. If the device reaches moderate thermal levels, FrameGuard automatically caps the frame rate to 30 FPS, letting the device cool down gracefully without abrupt kernel thermal throttling.

- **Ahead-Of-Time (AOT) Shader Compilation**  
  Triggers Android Runtime (ART) speed compilation (`cmd package compile -m speed -f`) for your game. This compiles game code into native machine code ahead of time, eliminating first-time shader stutters and JIT compiler spikes during intense gameplay.

- **Deep Doze & Background Standby**  
  Applies system-level device idle directives to suppress non-essential background tasks, freeing up CPU cores and memory bandwidth exclusively for your active game.

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

1. **Select Target App:** Tap **SELECT APPLICATION** and choose your game from the list.
2. **Set Frame Pacing & Display Mode:** Choose your target frame rate (e.g., 60 FPS, 90 FPS, 120 FPS). FrameGuard will automatically match the display refresh rate.
3. **Choose Render Scaling:** Select 100% (Native), 75% (Balanced), or 50% (Performance).
4. **Adaptive Thermal Limiter:** Set to **OFF** for standard sessions, or **ADAPTIVE** for long gaming sessions where you want automatic thermal protection.
5. **System Directives:** Toggle **Deep Doze** (suppress background apps) and **ART Compiler** (pre-compile shaders) as desired.

### 3. Launch & Play

- Tap **`[ ENGAGE OPTIMIZER ]`**. FrameGuard applies all directives, displays the output in the terminal, and launches your game.
- A persistent notification keeps you informed of your current FPS cap, SoC temperatures, and active optimizations.

### 4. Restore When Finished

- When you finish playing, tap **STOP & RESTORE** inside FrameGuard or from the notification. FrameGuard immediately restores all system settings (refresh rates, sync, doze) back to their original defaults.

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
  - FrameGuard's system directives override Samsung Game Optimizing Service (GOS) refresh rate locks cleanly via `device_config`.

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
  - `device_config` for frame rate limits and pacing enforcement.
  - `settings put system` for hardware refresh rate bounds.
  - `dumpsys deviceidle` for deep doze background state enforcement.
  - `cmd package compile -m speed -f` for ART dex2oat Ahead-Of-Time compilation.
  - Android HAL `PowerManager` for real-time hardware thermal throttling telemetry.
- **Platform Compatibility:** Targets Android 12 through Android 16 (API Level 31 to 36).

---

## 📜 License

This project is licensed under the **GNU General Public License v3.0 (GPL-3.0)**.

You are free to use, modify, and distribute this software under the terms of the GPL-3.0 license. See the [LICENSE](LICENSE) file for full details.

