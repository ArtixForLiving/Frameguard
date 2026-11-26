# PRODUCT REQUIREMENTS DOCUMENT · REKAYASA PERANGKAT LUNAK

# FrameGuard v1.0.8 (Build 108 — Revisi Resmi)

**Arsitektur Closed-Loop Verification, De-Gimmicking, Penggantian Fitur Nyata & Hardening Keamanan IPC**

| Parameter Proyek | Spesifikasi | Parameter Rilis | Spesifikasi |
| :--- | :--- | :--- | :--- |
| **Produk / Package** | FrameGuard (`com.optimizer.game`) | **Versi Target** | `v1.0.8` (`versionCode = 108`) |
| **Baseline Kode** | `v1.0.7` (Build 107, commit `b49e0bf`) | **Target Platform** | Android 12–17 (`minSdk 31`, `targetSdk 37`) |
| **Perangkat Uji Utama** | Motorola Edge 60 Pro (`ZP2222G6Z4`, Android 16) | **Status Dokumen** | **Revisi Resmi (Siap Eksekusi Fase 1–4)** |

> [!IMPORTANT]
> **Ringkasan Amandemen Teknis (Revisi v1.0.8):**
> Dokumen ini menggantikan draf awal PRD v1.0.8 dengan 5 penyesuaian arsitektural yang telah disepakati sebelum implementasi:
> 1. **Transport `DaemonServer`:** Menggunakan `127.0.0.1:0` (ephemeral TCP) + token via `stdin` + `READY:<port>` handshake + *mutual challenge-response* sebagai jalur utama (menghindari blokir SELinux `untrusted_app -> shell` pada abstract Unix socket).
> 2. **Kontrak `ShellResult` Lintas Backend:** Memperbarui `IShellService.aidl` untuk mengembalikan `@Parcelable` `ShellResult`; menambahkan sentinel *exit-code* berbasis *nonce* pada `AdbTlsClient` serta metode terpisah `execDetached()` untuk bootstrap `DaemonServer`.
> 3. **Audit LTPO Anti False-Positive:** Menggunakan `primaryRanges.render.max` / `physical.max` (`SurfaceFlinger`) + verifikasi Mode 2 `dumpsys game` untuk deteksi `OEM BYPASS` (bukan `vsyncRate` sesaat pada panel LTPO 24–120 Hz).
> 4. **Konsistensi `cmd game set --mode 2`:** Selalu mengirim `--fps` dan `--downscale` secara bersamaan, menggunakan `--downscale disable` (fallback `1.0`) saat reset, dan mengunci kontrol Downscale dengan label `APPLIES ON LAUNCH` selama sesi boost aktif.
> 5. **Penyelarasan UI & Probe DND:** Menambahkan badge `CLAMPED 30` saat clamp 30 FPS aktif, menonaktifkan tombol `[ RE-VERIFY ]` saat `isBoostActive == true`, dan menguji `cmd notification set_dnd` pada tahap *Live Capability Probe* sebelum menampilkan modul Auto Gaming DND.

---

## 1. Latar Belakang & Tujuan Strategis Rilis

Audit kode sumber dan pengujian ADB langsung pada perangkat fisik v1.0.7 ([AUDIT_REPORT_v1.0.7.md](file:///d:/Frameguard/AUDIT_REPORT_v1.0.7.md)) membuktikan bahwa aplikasi masih beroperasi sebagai *open-loop shell wrapper* (*fire-and-forget*). Seluruh backend eksekusi membuang OS *exit code*, beberapa modul bersifat plasebo, pipeline `cmd game` gagal menerapkan batas FPS karena ketidakcocokan mode, watchdog pada Native ADB mati total, dan terdapat celah eskalasi hak akses lokal pada `DaemonServer`.

- **Menghilangkan Efek Plasebo (*De-Gimmicking*):** Memangkas mekanisme yang tidak memengaruhi *frame pacing* maupun suhu pada game modern berbasis mesin C++, lalu menggantinya dengan perintah shell UID 2000 yang memiliki dampak terukur.
- **Menerapkan Verifikasi *Closed-Loop*:** Mewajibkan setiap mutasi sistem diperiksa ulang melalui `dumpsys` dan struktur `ShellResult` agar aplikasi melapor secara jujur ketika pengaturan FPS ditimpa oleh *daemon* bawaan OEM.
- **Menutup Seluruh Celah Kritis & Tinggi (C-1 s.d. C-6, H-1 s.d. H-8):** Memperbaiki kebocoran status `GameManager`, perlindungan layar LTPO `24.0 Hz`, sinkronisasi sesi berbasis `Mutex`, watchdog *foreground-aware*, kalibrasi `ThermalHAL`, serta keamanan IPC dan kunci ADB.

> **Batasan Eksplisit (Non-Goals):** FrameGuard beroperasi pada hak akses non-root (`UID 2000` melalui Shizuku atau Wireless ADB). Aplikasi tidak melakukan *overclock* GPU, tidak memodifikasi *governor* kernel, dan tidak memaksa kepatuhan mutlak pada *daemon* sistem berhak akses lebih tinggi seperti HyperOS Joyose, Samsung GOS, ColorOS OplusGames, atau Vivo GameCube.

---

## 2. Matriks Evolusi Fitur (Pemangkasan Plasebo & Pengganti Nyata)

Perombakan v1.0.8 mempertahankan empat pilar utama FrameGuard (pembatasan FPS tingkat OS, penyelaras *refresh rate* layar, pemantauan termal adaptif, dan dual-backend Shizuku/ADB) sekaligus mengganti modul plasebo dengan fitur sistem yang benar-benar efektif.

| Komponen / Fitur | Keputusan v1.0.8 | Justifikasi Teknis & Pengganti Nyata |
| :--- | :--- | :--- |
| **ART Shader Pre-Compile**<br>(`cmd package compile`) | **HAPUS** → Live Telemetry | Seluruh game katalog (Warframe, Genshin Impact, Wuthering Waves, Roblox, PUBG, Free Fire, MLBB) berjalan di *native library* C++ (`.so`) dan shader Vulkan/GLES runtime. Diganti pembacaan langsung `primaryRanges` / `vsyncRate` dari `SurfaceFlinger` dan suhu `SKIN` / `SOC` dari `ThermalHAL`. |
| **Force Deep Doze**<br>(`dumpsys deviceidle`) | **HAPUS** → `am kill-all` | Aplikasi foreground sudah kebal Doze dan Android 14–16 membekukan proses latar belakang via *cgroup freezer*. Diganti pembersihan proses *cached* yang aman via `am kill-all` sebelum game dimulai tanpa mengganggu VoIP/notifikasi. |
| **Global Sync Lock**<br>(`device_config`) | **HAPUS** → Auto Gaming DND | Menghapus penguncian Phenotype `until_reboot` yang rentan tertinggal permanen. Menambahkan aktivasi otomatis mode Jangan Ganggu (`cmd notification set_dnd`) selama sesi game berlangsung beserta pemulihan status aslinya (lolos uji *probe*). |
| **Per-App Game Downscale**<br>(`cmd game set --downscale`) | **BARU (Utama)** | Memanfaatkan fitur *render downscaling* bawaan `GameManagerService` (Android 13/14+) per paket game (misal `0.85`, `0.75`, `0.6`) untuk meringankan beban GPU tanpa mengecilkan UI sistem, keyboard, maupun panel notifikasi. |
| **WindowManager Scale**<br>(`wm size` / `wm density`) | **EXPERIMENTAL** | Tetap tersedia sebagai opsi sekunder bagi perangkat/game yang tidak merespons `--downscale`, dipindahkan ke bagian eksperimental dengan peringatan penyusutan UI global dan perlindungan verifikasi pemulihan snapshot. |
| **OS FPS Lock & Hz Pairing**<br>(`cmd game` + `settings`) | **PEROMBAKAN** | Memperbaiki ketidakcocokan `--mode 2` vs `custom`, mengirim `--fps` + `--downscale` bersamaan, wajib menjalankan `cmd game reset` saat selesai, serta melindungi nilai `"null"` pada `min_refresh_rate` agar batas bawah LTPO `24.0 Hz` tetap utuh. |

---

## 3. Spesifikasi Teknis & Arsitektur Sistem

### 3.1 Kontrak Eksekusi Terstruktur (`ShellResult`), AIDL & `AdbTlsClient`

Seluruh backend eksekusi (`ShellUserService`, `DaemonServer`, dan `AdbTlsClient`) wajib mengembalikan objek `ShellResult` terstruktur alih-alih string mentah yang menyembunyikan kegagalan.

```kotlin
@Parcelize
data class ShellResult(
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
    val timedOut: Boolean = false,
    val transportError: String? = null
) : Parcelable {
    val isOk: Boolean get() = transportError == null && !timedOut && exitCode == 0
}
```

- **Kontrak `IShellService.aidl` (Amandemen #2):**
  - Tambahkan `ShellResult.aidl` (`parcelable ShellResult;`) dan ubah `IShellService.aidl` dari `String runCommand(String command)` menjadi `ShellResult runCommand(String command);`.
  - `ShellUserService` mengisi `exitCode`, `stdout`, dan `stderr` secara eksplisit serta membaca `stdout` dan `stderr` secara konkuren untuk mencegah *pipe buffer deadlock* 64KB.
- **Sentinel Exit-Code Berbasis Nonce & `execDetached()` pada `AdbTlsClient` (Amandemen #2):**
  - Menghentikan pemutusan pembacaan dini ketika `stream.available() == 0` agar output multi-paket seperti `dumpsys` tidak terpotong, serta menghapus pemutusan `400ms` pada perintah yang mengandung `&` atau `&&`.
  - Karena protokol klasik `shell:` ADB (`libadb-android`) tidak mengirim *exit code* terpisah secara native, `AdbTlsClient.executeCommand` membungkus perintah dengan sentinel berbasis *nonce* acak per-eksekusi (misal mengakhiri perintah dengan `; printf '\n__FG_EXIT_%s__:%d\n' "<nonce>" $?`) untuk mengekstrak `exitCode` secara akurat tanpa bertabrakan dengan stdout normal.
  - Sediakan metode terpisah `AdbTlsClient.execDetached(context, port, command)` khusus untuk bootstrap `DaemonServer` (`nohup ... </dev/null >/dev/null 2>&1 &`) agar peluncuran proses latar belakang tidak memblokir pembacaan EOF hingga timeout.
- **Eliminasi Masking `"OK"`:** `DaemonServer.executeShellCommand` dilarang mengubah output kosong (seperti `pidof` dengan exit code `1`) menjadi `"OK"`. Pembacaan `stdout` dan `stderr` pada `DaemonServer` wajib dilakukan secara konkuren.
- **Binder Guard Presisi:** Pemantauan koneksi diarahkan langsung pada `ShizukuManager.shellService` (`IShellService`) dan kesehatan `NativeAdbBackend`. Penangkapan `DeadObjectException` wajib mengisi `transportError`, mereset `shellService = null` serta `isBound = false`, memperbarui status koneksi, dan memicu *rebind*.

---

### 3.2 Closed-Loop GameManager (`--mode 2` & `--downscale`) serta Perlindungan LTPO

- **Pipeline `cmd game` Terpadu (API 33+ — Amandemen #4):**
  - Saat menerapkan profil atau memperbarui konfigurasi Mode 2, **selalu** kirim `--fps` dan `--downscale` secara bersamaan dalam satu pemanggilan `cmd game set --mode 2 --fps <target> --downscale <ratio|disable> <pkg>`, lalu aktifkan `cmd game mode 2 <pkg>`. Jangan mengaktifkan `cmd game mode custom` (Mode 4) yang tidak terikat konfigurasi Mode 2.
  - Untuk reset downscale ke native (`1.0x`), gunakan `--downscale disable` terlebih dahulu; jika ditolak parser `GameManagerService` pada build Android tertentu (`!result.isOk`), lakukan fallback otomatis ke `--downscale 1.0`.
- **Verifikasi & Pembersihan Wajib:**
  - Setelah penerapan, baca `dumpsys game` untuk memastikan blok `[Name:<pkg> ...]` menampilkan Mode `2` aktif beserta nilai `Fps` / `Scaling` yang sesuai.
  - Saat sesi berakhir, wajib jalankan `cmd game reset <pkg>` lalu verifikasi ulang bahwa intervensi pada paket tersebut telah bersih.
- **Perlindungan LTPO / VRR & Validasi Panel:**
  - Simpan nilai mentah `settings get system peak_refresh_rate` dan `min_refresh_rate` termasuk literal `"null"` tanpa menggantinya dengan angka default `"120.0"` atau `"60.0"`. Saat memulihkan nilai `"null"`, jalankan `settings delete system <key>`.
  - Validasi seluruh target Hz (`45 -> 90Hz` fallback `40@120Hz` atau `60Hz`, `40/120 -> 120Hz`) terhadap `Display.supportedModes`, dan tulis `min_refresh_rate` sebelum `peak_refresh_rate` saat menurunkan Hz agar tidak membentuk batas terbalik.
- **Audit Loop `SurfaceFlinger` & `dumpsys game` Anti False-Positive LTPO (Amandemen #3):**
  - Setelah game menjadi `topResumedActivity` dan secara berkala setiap 10 detik:
    1. Parse `primaryRanges={physical=[min, max], render=[min, max]}` dari `dumpsys SurfaceFlinger` (`displayManagerPolicy`) serta `dumpsys game` untuk paket aktif.
    2. **Jangan** memakai `activeMode.vsyncRate` atau `renderFrameRate` sesaat sebagai penentu `OEM BYPASS`, karena panel LTPO (`24–120 Hz`) wajar menurunkan `vsyncRate` ke `24 Hz` saat frame statis atau melonjak sesaat ke `120 Hz` saat UI sistem muncul.
    3. Status `OEM BYPASS` hanya dilaporkan jika `primaryRanges.render.max` / `physical.max` tetap melampaui target Hz (atau Mode `2` di `dumpsys game` tertimpa/hilang) setelah 1 kali *re-assert*.

---

### 3.3 Pemangkasan RAM (`am kill-all`), Auto Gaming DND & Pembersihan Bloat

- **Safe Background RAM Trimming:** Jalankan `am kill-all` tepat sebelum peluncuran game untuk membebaskan memori dari proses *cached* yang aman dimatikan sistem.
- **Auto Gaming DND (Terverifikasi Probe — Amandemen #5):**
  - Modul Auto Gaming DND hanya diaktifkan di Main UI jika `cmd notification set_dnd` lolos uji izin UID 2000 pada tahap *Live Capability Probe*.
  - Simpan nilai awal `settings get global zen_mode` sebelum sesi dimulai; jika bernilai `0` (nonaktif) dan opsi DND diaktifkan pengguna, jalankan `cmd notification set_dnd priority` lalu kembalikan ke mode awal (`cmd notification set_dnd off`) saat sesi berakhir.
- **Pembersihan Dependensi & Kode Usang:** Hapus dependensi `dev.mobile:dadb:1.2.7` dari `app/build.gradle.kts` serta aturan ProGuard terkait, hapus logika cache/restore `accelerometer_rotation`, dan pastikan `PrivilegeManager.init()` hanya diinisialisasi satu kali sebagai singleton.

---

### 3.4 Sinkronisasi Sesi (`Mutex`), Live FPS Switch & Proteksi Geometri

- **Serialisasi dengan `Mutex`:** Seluruh penanganan `ACTION_START`, `ACTION_SWITCH_FPS`, dan `ACTION_STOP` pada `BoostSessionService` wajib dikunci menggunakan satu `Mutex`. Kunci identitas `activePackage` selama sesi berlangsung agar pemilihan game lain di UI tidak menulis intervensi `cmd game` ke paket yang salah.
- **Live FPS Switch (`ACTION_SWITCH_FPS`):** Pisahkan tombol cepat notifikasi `"30 FPS"` dari siklus penuh `applyProfile()`. Perubahan FPS langsung hanya memperbarui konfigurasi `cmd game set --mode 2 --fps 30 --downscale <currentDownscale> <pkg>` dan batas Hz layar tanpa memanggil `am force-stop` dan tanpa menyentuh `wm size` / `wm density`.
- **Proteksi Snapshot Geometri & Peluncuran Game:** Ambil snapshot `wm` hanya ketika belum ada snapshot aktif, hapus total nilai fallback palsu `1080x2400 @ 420dpi`, dan hapus snapshot tersimpan hanya setelah pembacaan ulang `wm size` dan `wm density` membuktikan layar telah kembali ke resolusi awal. Bungkus `context.startActivity(launchIntent)` dengan `try/catch` agar pembatasan *Background Activity Launch* (BAL) otomatis beralih ke fallback `am start -n`.

---

### 3.5 Watchdog Berbasis Fokus & Subproses

- **Urutan Grace Timer:** Mulai `startProcessWatchdog()` dengan masa tenggang minimal `15_000ms` hanya setelah rangkaian `applyProfile()` selesai dijalankan.
- **Deteksi Proses & Fokus Layar Depan:** Ganti kueri `pidof` dengan `ps -A -o PID,NAME` untuk mendeteksi proses utama maupun subproses (`<pkg>:*` seperti `:unity`, `:UE4`, `:game`), digabungkan dengan pemeriksaan `topResumedActivity` / `mResumedActivity` dari `dumpsys activity activities`.
- **Pemulihan Otomatis saat Keluar / Latar Belakang:** Picu `restoreDefaults()` secara otomatis apabila proses terkonfirmasi mati selama 2 siklus berturut-turut atau game ditinggalkan di latar belakang lebih dari 20 detik, serta tambahkan upaya pembersihan terukur pada `onTaskRemoved()` dan `onDestroy()`.

---

### 3.6 Kalibrasi Ulang Adaptive Thermal Guard

- **Kepatuhan Rate-Limit API:** Naikkan interval pemanggilan `PowerManager.getThermalHeadroom(10)` dari `3000ms` menjadi minimal `10_000ms`.
- **Ambang Dinamis & Anti-Deadlock:** Hapus konstanta prematur `HEADROOM_ENGAGE_THRESHOLD = 0.90f`. Baca tabel `Temperature headroom thresholds` perangkat saat inisialisasi atau gunakan pemicu `>= THERMAL_STATUS_SEVERE` / `headroom >= 1.0f`, serta sediakan jalur pelepasan berbasis penurunan *headroom* agar pembatasan tidak terkunci permanen pada status `MODERATE`.
- **Pemulihan Target FPS & Sensor HAL:** Saat suhu kembali aman, terapkan ulang `currentTargetFps` pilihan pengguna bersama `--downscale` aktif (jangan memanggil `cmd game reset` yang melepas game ke 120 Hz). Prioritaskan pembacaan sensor aktif `Current temperatures from HAL:` (`SKIN` lalu `SOC`) daripada suhu baterai, dan sinkronkan perubahan toggle termal dari UI ke sesi yang sedang berjalan.

---

### 3.7 Security Hardening (`DaemonServer`, IPC & Privasi Kunci)

- **Eliminasi Injeksi Shell:** Hapus seluruh eksekusi `sh -c` berbasis interpolasi string. Gunakan array argumen `ProcessBuilder`, validasi ketat pola `packageName` (`^[a-zA-Z][a-zA-Z0-9_]*(\.[a-zA-Z0-9_]+)+$`), dan sanitasi format komponen `pkg/activity` hasil `resolve-activity` sebelum masuk ke `am start -n`.
- **Perombakan Arsitektur `DaemonServer` (Amandemen #1):**
  - Pindahkan komunikasi dari port statis `127.0.0.1:25890` ke **ephemeral TCP port (`127.0.0.1:0`)** sebagai jalur utama agar kompatibel dengan kebijakan SELinux `untrusted_app -> shell` di Android 11–17.
  - `DaemonServer` membaca token autentikasi dari **`stdin`** (bukan argumen CLI yang terlihat di `/proc/<pid>/cmdline`), melakukan bind `ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))`, lalu mencetak `READY:<port>` agar `NativeDaemonManager` mengetahui port aktif sesi tersebut.
  - Terapkan **mutual challenge-response** berbasis *nonce* + perbandingan *constant-time* (`MessageDigest.isEqual`) atas token bersama, sehingga listener ilegal di port lokal tidak bisa mengekstrak token dari klien sebelum membuktikan kepemilikan token.
  - Batasi ukuran *thread pool* server (`Executors.newFixedThreadPool`) dan ganti pemisah `---FRAMEGUARD_EOF---` dengan *length-prefixed framing* (`DataOutputStream` / `DataInputStream`).
- **Perlindungan Kunci ADB & Pairing:** Setel `android:allowBackup="false"` pada `AndroidManifest.xml` (serta kecualikan direktori `adb_keys` dan `daemon_auth_token` pada `backup_rules.xml` dan `data_extraction_rules.xml`). Gunakan `goAsync()` pada `AdbPairingReceiver` tanpa mencatat kode pairing ke logcat, tutup `FileInputStream` di `AdbKeyManager` dengan `.use {}`, gunakan `SecureRandom` untuk nomor seri sertifikat X.509, dan validasi alamat loopback pada `AdbMdnsDiscovery`.

---

### 3.8 Alur Koneksi, Loopback Handover & Live Capability Probe

Aplikasi mengadopsi *state machine* empat tahap (**Connection Ping → Connection Gate → Live Capability Probe → Filtered Main UI**) agar pengguna hanya berinteraksi dengan fitur yang terverifikasi berjalan di perangkatnya:

- **Loopback Daemon Handover (Kebal Putus Wi-Fi):** Begitu Wireless ADB tersambung satu kali melalui jaringan Wi-Fi, FrameGuard segera menanam proses `DaemonServer` (`UID 2000`) pada `127.0.0.1:<ephemeral_port>` via `AdbTlsClient.execDetached()`. Karena jalur komunikasi berpindah ke loopback memori internal perangkat, mematikan Wi-Fi atau berpindah jaringan tidak akan memutus akses FrameGuard hingga perangkat di-reboot atau daemon dihentikan oleh LMK.
- **Fast Connection Ping (`< 50ms`):** Setiap kali aplikasi dibuka atau kembali ke foreground, sistem memeriksa ping koneksi aktif terlebih dahulu:
  - *Tersambung + Cache Valid:* Langsung masuk ke **Filtered Main UI** tanpa layar pemindaian ulang.
  - *Tersambung + Pertama Kali / Update OS:* Menjalankan **Live Capability Probe** lalu menyimpan hasilnya berdasarkan kunci `Build.FINGERPRINT`.
  - *Terputus (Pasca-Reboot / LMK):* Mengarahkan pengguna ke **Connection Gate** disertai indikator penyebab terputus (misal `DAEMON STOPPED · RECONNECT ADB`), tanpa menghapus snapshot geometri yang masih aktif.
- **Live Capability Probe (Uji Tembak Paralel 300–500ms):** Setelah hak akses `UID 2000` terhubung, layar verifikasi menjalankan pengecekan paralel:
  1. Uji izin tulis `settings` dan `wm` untuk mendeteksi pemblokiran keamanan vendor (seperti *USB Debugging (Security Settings)* / *Disable permission monitoring* pada HyperOS/ColorOS).
  2. Dukungan `cmd game` (`--fps` & `--downscale`) pada API 33+.
  3. Pembacaan `Display.supportedModes` serta ketersediaan sensor `ThermalHAL`.
  4. Uji izin `cmd notification set_dnd` pada `UID 2000` (Amandemen #5) untuk menentukan visibilitas modul Auto Gaming DND.
  5. Pemindaian paket *game daemon* OEM (`Joyose`, `GOS`, `OplusGames`, `GameCube`).

---

## 4. Spesifikasi Antarmuka & Telemetri Jujur (UI/UX)

Desain antarmuka tetap mempertahankan tata letak Swiss-grid utilitarian (palet Obsidian `#080808` / `#111111`, aksen ungu `#7C3AED`, tipografi teknis berbahasa Inggris yang ringkas) dengan penyesuaian perilaku hasil amandemen:

| Komponen / Layar UI | Spesifikasi Perilaku v1.0.8 (Revisi) |
| :--- | :--- |
| **1. Connection Gate Screen** | Layar gerbang awal saat Shizuku/Daemon belum terhubung. Menampilkan tombol koneksi cepat Shizuku, alur pairing Wireless ADB, panduan singkat pengaturan spesifik per merek HP, lencana alasan putus koneksi, serta lencana peringatan jika snapshot `wm` masih tersimpan. Footer keamanan mencerminkan mekanisme aktual (`127.0.0.1 EPHEMERAL · MUTUAL AUTH · ALLOWBACKUP=FALSE`). |
| **2. Live Capability Probe** | Layar diagnostik singkat yang tampil saat koneksi pertama kali berhasil, setelah pembaruan OS (`Build.FINGERPRINT`), atau saat tombol `RE-VERIFY` ditekan. Menampilkan daftar periksa progres pengujian sistem (termasuk uji `set_dnd`) dan peringatan satu baris apabila izin mutasi `wm` / `settings` diblokir OEM. |
| **3. Filtered Main UI & Device Profile Bar** | Modul yang tidak lolos uji kemampuan (misal `PER-APP DOWNSCALE` pada Android 12, opsi `90/120 FPS` pada layar 60 Hz, atau `AUTO GAMING DND` jika `set_dnd` ditolak) disembunyikan dari layar utama. Bagian bawah menampilkan baris ringkas (`N MODULES HIDDEN · DEVICE PROFILE` + `[ VIEW ]` + `[ RE-VERIFY ]`). Tombol **`[ RE-VERIFY ]` dinonaktifkan (`disabled`) saat `isBoostActive == true`** (Amandemen #5). |
| **4. Frame Pacing & Status Closed-Loop** | Menampilkan salah satu dari empat status terverifikasi OS: `SYNC LOCKED` (cocok di `dumpsys game` & `primaryRanges` `SurfaceFlinger`), `OEM BYPASS` (ditimpa daemon vendor), `THERMAL 30` (pembatasan suhu aktif), atau `DEGRADED` (koneksi terputus). Saat 30 FPS clamp aktif (`THERMAL 30` atau notifikasi `LIVE 30 FPS`) pada panel 120Hz, header Frame Pacing menampilkan badge **`CLAMPED 30`** (Amandemen #5). |
| **5. Kontrol Downscale & Skala** | Menempatkan `PER-APP DOWNSCALE` (`cmd game`) pada kontrol utama (API 33+) dengan penguncian kontrol dan label **`APPLIES ON LAUNCH`** selama sesi boost aktif (Amandemen #4), serta memindahkan `WM DISPLAY SCALE` ke panel `EXPERIMENTAL` dengan catatan `Global UI scale · Persists across reboot if killed`. |
| **6. Telemetri Suhu & Hz** | Menampilkan pembacaan nyata `SKIN` / `SOC` HAL serta `primaryRanges` / `vsyncRate` aktif. Jika sensor gagal dibaca, tampilkan `--.-°C` (menghapus nilai statis palsu `36.2°C`). |

---

## 5. Rencana Pengujian & Kriteria Penerimaan

### 5.1 Automated Unit Tests (Target: 100% Lulus)

1. `ShellResultParsingTest`: Memastikan output kosong dengan exit code `1` menghasilkan `isOk == false` dan tidak dikonversi menjadi `"OK"`, serta parsing sentinel *nonce* `AdbTlsClient` mengekstrak `exitCode` secara akurat.
2. `DaemonMutualAuthTest`: Memastikan token dikirim via `stdin` (bukan CLI args), port ephemeral `READY:<port>` terbaca, dan *mutual challenge-response* menolak peer tanpa token valid.
3. `GameManagerClosedLoopTest`: Memverifikasi pengiriman `--fps` dan `--downscale` bersamaan pada `--mode 2`, `--downscale disable` (fallback `1.0`) saat reset rasio, `cmd game reset` saat berhenti, serta parsing `dumpsys game`.
4. `RefreshRateLtpoTest`: Memverifikasi nilai awal `"null"` dipulihkan menggunakan `settings delete`, urutan penulisan `min` / `peak`, pengecekan `Display.supportedModes`, dan evaluasi `primaryRanges.max` agar fluktuasi LTPO `24–120 Hz` tidak memicu false-positive `OEM BYPASS`.
5. `WatchdogForegroundTest`: Memverifikasi pencocokan subproses `<pkg>:*`, masa tenggang 15 detik pasca-peluncuran, dan pemulihan otomatis saat aplikasi berada di latar belakang.
6. `ThermalHeadroomCalibrationTest`: Memverifikasi interval pemantauan `>= 10s`, ambang dinamis HAL, dan pemulihan `currentTargetFps` (+ downscale aktif) saat suhu turun.
7. `GeometryRecoveryGuardTest`: Memverifikasi snapshot `wm` tidak terhapus ketika pemulihan gagal dan tidak tertimpa saat `ACTION_SWITCH_FPS` dipanggil.
8. `PackageSanitizerTest`: Memastikan penolakan karakter injeksi shell (`;`, `&&`, baris baru, spasi) pada `packageName` serta komponen `resolve-activity`.
9. `CapabilityProbeFilterTest`: Memverifikasi penyembunyian otomatis modul yang tidak didukung (API `< 33`, panel 60 Hz, blokir izin OEM, atau `set_dnd` ditolak) serta validasi cache berdasarkan `Build.FINGERPRINT`.

### 5.2 Verifikasi Empiris ADB pada Perangkat Fisik (`ZP2222G6Z4`)

| ID Uji | Skenario Verifikasi | Kriteria Lulus (Pass Criteria) |
| :--- | :--- | :--- |
| **V-1** | Siklus `GameManager` & Downscale | Saat game berjalan, `dumpsys game` menampilkan `Game Mode:2` dengan `Fps` dan `Scaling` sesuai target; setelah berhenti, `cmd game reset` membersihkan seluruh intervensi. |
| **V-2** | Perlindungan LTPO & Audit `primaryRanges` | Sebelum dan sesudah sesi pada `ZP2222G6Z4`, `settings get system min_refresh_rate` tetap `24.0` (atau `"null"`), dan diamnya frame di 24 Hz selama sesi tidak memicu false-positive `OEM BYPASS`. |
| **V-3** | Tombol Notifikasi `30 FPS` & Badge UI | Menekan `30 FPS` di tengah sesi tidak membunuh PID game, tidak menggandakan faktor skala layar, mempertahankan downscale aktif, dan memunculkan badge `CLAMPED 30`. |
| **V-4** | Watchdog Native ADB & DND | Menutup game saat memakai Native ADB memicu pemulihan seluruh pengaturan dan `zen_mode` dalam waktu `<= 15` detik. |
| **V-5** | Ketahanan Putus Hak Akses | Mencabut izin Shizuku / mematikan daemon di tengah sesi memicu status `DEGRADED` dan mempertahankan snapshot geometri untuk pemulihan berikutnya. |
| **V-6** | Audit Proses, Socket & Backup Kunci | Output `ps -ef` / `/proc/<pid>/cmdline` tidak memuat token `DaemonServer`, koneksi port ephemeral terautentikasi mutual, dan ekstraksi backup ADB tidak menyertakan berkas `adb_keys`. |
| **V-7** | Putus Wi-Fi & Cache Probe | Mematikan Wi-Fi setelah terhubung tidak memutus sesi berkat loopback `DaemonServer` (`execDetached()`), dan pembukaan ulang aplikasi melewati layar loading selama `Build.FINGERPRINT` tetap sama. |

---

## 6. Urutan Eksekusi Implementasi (Roadmap)

- **Fase 1 — Core Transport & Security Hardening:**
  Migrasi kontrak `ShellResult` (termasuk `ShellResult.aidl` + `IShellService.aidl`), perombakan `DaemonServer` (`127.0.0.1:0` ephemeral port, token via `stdin`, `READY:<port>`, *mutual challenge-response*, *length-prefixed framing*, bounded thread pool), `AdbTlsClient` (sentinel exit-code berbasis *nonce* + `execDetached()`, hapus pemutusan `&`/`&&`), sanitasi `ProcessBuilder`, singleton `PrivilegeManager`, serta pembatasan backup kunci ADB.
- **Fase 2 — Engine Overhaul, De-Gimmicking & Penggantian Fitur:**
  Menghapus `compileShaders()`, Deep Doze, `device_config` lock, `dadb`, dan `accelerometer_rotation`; mengimplementasikan `cmd game set --mode 2 --fps ... --downscale ...` + `--downscale disable`, `am kill-all`, `cmd notification set_dnd`, perlindungan LTPO (`"null"` -> `settings delete`), serta penjagaan snapshot `wm`.
- **Fase 3 — Service Synchronization, Watchdog & Thermal Calibration:**
  Menerapkan `Mutex` pada `BoostSessionService`, memisahkan `ACTION_SWITCH_FPS`, mengaktifkan watchdog berbasis fokus + subproses, kalibrasi `ThermalHAL` 10 detik, serta loop audit `SurfaceFlinger` berbasis `primaryRanges.max`.
- **Fase 4 — Connection Flow, UI Alignment & Verifikasi Akhir:**
  Mengimplementasikan `Connection Gate`, `Live Capability Probe` (termasuk uji `set_dnd`), penyaringan modul dinamis beserta `Device Profile Bar`, penguncian kontrol Downscale (`APPLIES ON LAUNCH`) dan `[ RE-VERIFY ]` saat sesi aktif, badge `CLAMPED 30`, indikator status *closed-loop*, mengeksekusi seluruh *unit test*, serta memvalidasi `V-1` hingga `V-7` pada perangkat fisik.
