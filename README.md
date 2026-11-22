# FrameGuard

> **Utilitarian OS-Level Game Optimizer via Rootless Shizuku Directives**

FrameGuard is an Android performance optimization utility designed for consistent frame pacing, thermal headroom management, and stutter elimination. Operating through Shizuku at privileged shell level (UID 2000), FrameGuard applies system directives directly without modifying game APKs, injecting code, or requiring root access.

---

## ⚡ Core Capabilities

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

## 🏗️ Architecture & Technology Stack

- **UI Layer**: 100% Jetpack Compose with custom cybernetic utilitarian obsidian design system.
- **State Management**: Kotlin Coroutines, `StateFlow`, and `SharedFlow` with reactive MVVM architecture.
- **Privilege Engine**: Shizuku API + AIDL Binder Service (`IShellService.aidl`) executing privileged directives as UID 2000.
- **Platform Compatibility**: Android 12+ (API Level 31 to 35).

---

## 🚀 Getting Started

### Prerequisites
1. Android 12 (API 31) or higher.
2. [Shizuku](https://shizuku.rikka.app/) installed and running on your device (via Wireless Debugging or computer ADB).

### Building from Source

Clone the repository and build using Gradle:

```bash
git clone https://github.com/ArtixForLiving/Frameguard.git
cd Frameguard

# On Linux / macOS:
./gradlew assembleDebug

# On Windows:
.\gradlew.bat assembleDebug
```

The compiled APK will be generated at:
`app/build/outputs/apk/debug/app-debug.apk`

---

## 📜 License

This project is licensed under the **FrameGuard Non-Commercial License**.  
Personal, educational, and research use is permitted. **Commercial use, distribution, or monetization is strictly prohibited.** See the [LICENSE](LICENSE) file for full terms.
