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

## 📱 Catatan Kompatibilitas Merek HP (OEM Guide)

FrameGuard v1.0.5 menggunakan protokol standar AOSP Android 11+ (API 30+). Namun, beberapa antarmuka pabrikan (OEM ROM) memiliki kebijakan keamanan atau penghemat baterai khusus:

### 1. Google Pixel, Motorola, Sony, Asus ROG, Nothing OS (Near-AOSP)
* **Status:** **100% Plug & Play.**
* **Catatan:** Menggunakan implementasi Android murni. Cukup aktifkan Wireless Debugging, masukkan 6 digit kode pada notifikasi FrameGuard, dan daemon langsung aktif.

### 2. Xiaomi, Redmi, POCO (MIUI / HyperOS)
* **Status:** Kompatibel (Butuh 1 opsi tambahan).
* **Catatan:**
  * Buka **Opsi Pengembang (Developer Options)**.
  * Aktifkan toggle **"USB debugging (Security settings)"** / *Setelan keamanan* (memerlukan akun Mi). Opsi ini diperlukan agar shell diizinkan mengubah refresh rate layar (`settings put system`).
  * Jika HyperOS otomatis mematikan Wireless Debugging setelah beberapa menit, hal ini tidak masalah karena `DaemonServer` FrameGuard sudah berjalan mandiri di memori RAM.

### 3. Samsung Galaxy (One UI)
* **Status:** **100% Kompatibel.**
* **Catatan:**
  * Samsung Knox tidak memblokir koneksi loopback TCP `127.0.0.1` antara aplikasi dan UID 2000.
  * Jika Samsung Game Optimizing Service (GOS) aktif, FrameGuard tetap dapat memaksakan limit refresh rate panel dan profile frame pacing via command `device_config`.

### 4. OPPO, Realme, OnePlus (ColorOS / OxygenOS / Realme UI)
* **Status:** Kompatibel (Butuh 1 opsi tambahan).
* **Catatan:**
  * Buka **Opsi Pengembang (Developer Options)**.
  * Aktifkan opsi **"Disable permission monitoring"** (*Nonaktifkan pemantauan izin*). Ini mencegah ColorOS menampilkan dialog konfirmasi keamanan saat FrameGuard mengeksekusi perintah shell di latar belakang.

### 5. Vivo & iQOO (Funtouch OS / OriginOS)
* **Status:** Kompatibel.
* **Catatan:**
  * Manajemen memori Funtouch OS cukup agresif. Disarankan untuk **Mengunci (Lock)** aplikasi FrameGuard di menu *Recent Apps* agar UI tidak ditutup paksa saat kamu bermain game berat. Daemon UID 2000 akan tetap berjalan di background sistem.

### 6. Transsion (Infinix, Tecno, itel - XOS / HiOS)
* **Status:** Kompatibel.
* **Catatan:**
  * Pastikan FrameGuard tidak dimasukkan ke dalam daftar *Freeze* atau pembersih RAM otomatis bawaan XOS/HiOS.

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

### Metode 1: Built-in Wireless ADB (Rekomendasi - Tanpa PC)
1. Sambungkan HP ke jaringan **Wi-Fi** (atau aktifkan Wi-Fi hotspot lokal).
2. Buka **Developer Options (Opsi Pengembang)** di Pengaturan HP, lalu aktifkan **Wireless debugging (Proses debug nirkabel)**.
3. Buka FrameGuard, ketuk **`[ PAIR WIRELESS ADB ]`**.
4. Ketuk *Pair device with pairing code*, lalu masukkan 6 digit angka yang muncul ke notifikasi FrameGuard.
5. Header akan langsung berubah menjadi **`[ ● UID 2000 • ADB ]`**.

### Metode 2: Shizuku (Alternatif)
Jika HP kamu sudah memiliki [Shizuku](https://shizuku.rikka.app/) yang aktif, cukup buka FrameGuard dan ketuk **`[ CONNECT SHIZUKU ]`**.

---

## 📜 License

This project is licensed under the **FrameGuard Non-Commercial License**.  
Personal, educational, and research use is permitted. **Commercial use, distribution, or monetization is strictly prohibited.** See the [LICENSE](LICENSE) file for full terms.
