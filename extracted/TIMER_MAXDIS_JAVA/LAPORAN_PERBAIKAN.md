# Laporan Perbaikan — TIMER MAXDIS JAVA

Tanggal: 18 November 2026. Semua temuan di bawah berasal dari pembacaan kode baris demi baris dan, untuk
logika murni, dari eksekusi nyata (lihat `HASIL_PENGUJIAN.txt`).

## Bug yang terbukti dan sudah diperbaiki

| # | Tingkat | Bug | Dampak | Perbaikan |
|---|---------|-----|--------|-----------|
| 1 | Tinggi | Rumus durasi memakai floating point: `ceil((qty/12.0)*60.0)` | Salah **+1 detik** pada 2.750 dari 100.000 nilai Qty (2,75%): Qty 25 menjadi 126 dtk, Qty 100 menjadi 501 dtk. Test lama lolos hanya karena memakai Qty 1/12/13/60. | `qty * 5` dengan bilangan bulat (secara matematis identik dengan rumus asli). |
| 2 | Tinggi | `onStartCommand` keluar sebelum memanggil `startForeground()` bila tidak ada timer aktif | Aplikasi **crash** (`ForegroundServiceDidNotStartInTimeException`) pada alarm basi, tombol LANJUT notifikasi ditekan dua kali setelah lorong terakhir, dsb. | `startForeground()` selalu dipanggil lebih dulu; `AlarmReceiver` mengabaikan alarm bila tidak ada timer. |
| 3 | Tinggi | Qty tanpa batas atas | Qty sangat besar membuat `qty*5*1000` overflow menjadi deadline negatif (timer langsung "selesai"); angka >19 digit ditolak diam-diam. | `MAX_QTY = 100.000`, `TimerLogic.parseQty()` dengan pesan galat jelas. |
| 4 | Sedang | Android 12+ dapat menolak `startForegroundService` dari latar belakang | Crash saat alarm/boot. | `try/catch` plus jalur cadangan `recoverWithoutService()` (timer ditandai selesai dan notifikasi berbunyi lewat sistem). |
| 5 | Sedang | Waktu selesai dicatat saat kode kebetulan berjalan, bukan batas timer | Alarm terlambat (Doze) atau pemulihan setelah reboot menggelembungkan "Waktu Kerja" dan merusak deviasi. | Waktu selesai = `min(sekarang, endEpoch)`. |
| 6 | Sedang | ENTER memicu dua jalur (aksi IME dan key-up) | Dialog izin alarm muncul ganda / start ganda pada beberapa keyboard. | Penjaga pemicu ganda + `TimerState.start()` menolak bila sudah berjalan. |
| 7 | Sedang | Tombol LANJUT diproses asinkron oleh Service | Status belum berubah saat UI menyegarkan (tiap 400 ms), sehingga dialog sempat muncul kembali (kedip). | Status dimajukan sinkron lewat `TimerService.advanceAndStop()`; tombol notifikasi memakai jalur yang sama tanpa menyalakan Service baru. |
| 8 | Sedang | Getar berada di blok yang sama dengan MP3 | Bila MP3 gagal dimuat, tidak ada bunyi **dan** tidak ada getar. | Getar independen; pemutar mencoba pulih sendiri (maks. 3 kali). |
| 9 | Sedang | Pemutar alarm tanpa wake lock | CPU dapat tidur saat layar mati sehingga bunyi terputus. | `setWakeMode(PARTIAL_WAKE_LOCK)` (izin WAKE_LOCK sudah ada). |
| 10 | Sedang | Alarm basi dapat menyelesaikan timer lebih awal | Timer lorong baru bisa selesai prematur. | Alarm yang datang >1,5 detik sebelum waktunya diabaikan dan status disinkronkan ulang. |
| 11 | Sedang | Channel alarm membunyikan nada bawaan sekaligus MP3 | Dua suara bertumpuk saat selesai. | Channel layanan dibuat senyap; channel cadangan (suara sistem) hanya dipakai pada jalur cadangan. |
| 12 | Sedang | Alarm sistem hilang setelah aplikasi diperbarui | Timer yang sedang berjalan tidak berbunyi. | `BootReceiver` juga menangani `MY_PACKAGE_REPLACED`. |
| 13 | Sedang | Android 15 (targetSdk 35) edge-to-edge: inset keyboard tidak diperhitungkan | Keyboard dapat menutupi kolom Qty. | Inset `ime()` ditambahkan ke padding. |
| 14 | Rendah | `VideoView` menampilkan dialog bawaan "Can't play this video" bila video gagal | Dialog galat mengganggu operator. | `OnErrorListener` menelan galat. |
| 15 | Rendah | Dialog tidak ditutup saat Activity dihancurkan | `WindowLeaked`; dialog rekap bisa ganda. | Semua dialog ditutup di `onDestroy` + penjaga `isFinishing()`. |
| 16 | Rendah | Header kolom memakai spasi manual | Kolom tidak sejajar di layar sempit / font berbeda. | Header dibuat dengan layout 3 kolom yang sejajar dengan baris. |
| 17 | Rendah | Ikon besar notifikasi didekode ulang setiap detik | Alokasi memori sia-sia. | Di-cache. |
| 18 | Rendah | Transisi status memakai `apply()` | Bila proses dimatikan seketika, perubahan bisa hilang. | `commit()` untuk start / selesai / lanjut. |
| 19 | Rendah | Tidak ada pemulihan bila Service mati saat aplikasi dibuka | Timer tampak jalan di layar tetapi tanpa alarm. | `onResume` menyinkronkan ulang Service dan alarm. |

## Perbaikan konfigurasi

- `windowLightNavigationBar` (API 27) dipindah ke `values-v27` karena `minSdk` = 23.
- `testInstrumentationRunner` dihapus (tidak ada dependensi `androidx.test`).
- `gradle/wrapper/gradle-wrapper.properties` ditambahkan (Gradle 8.9).
- `.gitignore` diperluas: `*.apk`, `*.aab`, `*.jks`, `*.keystore`, `.cxx`, dll.
- Workflow: `permissions` minimal, `timeout`, `concurrency`, trigger `pull_request`, laporan test diunggah.
- Cabang kode mati (API < 23) dihapus dari `scheduleCompletionAlarm`.

## Test baru

33 unit test (sebelumnya 4). Uji mutasi membuktikan bug #1, #5, dan #6 tertangkap bila dikembalikan.

## Batasan yang SENGAJA tidak diubah (perlu keputusan Anda)

1. **Timer berbasis jam dinding.** Bila jam / zona waktu HP diubah saat timer berjalan, sisa waktu bergeser.
   Solusi penuh memerlukan `elapsedRealtime` plus penanganan reboot; ini perubahan desain.
2. **Izin alarm presisi.** Pada Android 14+ pengguna harus mengizinkan *Alarms & reminders* secara manual.
   Alternatif `USE_EXACT_ALARM` dapat diberikan otomatis tetapi dibatasi kebijakan Google Play untuk
   aplikasi alarm/timer; tidak saya tambahkan agar tidak mengubah profil kebijakan aplikasi Anda.
3. **Celah shift.** `getShift` mengembalikan "DI LUAR SHIFT" untuk jam 00–02, 06–09, dan 16–21. Ini sesuai
   rumus asli sehingga tidak saya ubah; mohon pastikan memang disengaja.
4. **Gradle wrapper** (`gradlew` + `gradle-wrapper.jar`) tidak dapat dibuat tanpa jaringan. GitHub Actions
   dan Android Studio tidak membutuhkannya; lihat panduan untuk membuatnya (opsional).
5. **Force stop** oleh pengguna dan pembatasan baterai vendor tetap berada di luar kendali aplikasi.
