# Panduan Upload ke GitHub dan Build APK Otomatis

Panduan ini untuk project **TIMER MAXDIS JAVA**. Hasil akhirnya: kode tersimpan di GitHub, dan APK
dibuat otomatis oleh GitHub Actions sehingga bisa diunduh dan dipasang di HP.

---

## 0. Aturan terpenting: isi repository harus berupa ISI folder project

File `settings.gradle` dan folder `.github` harus berada di **akar (root) repository**, bukan di dalam
subfolder. Jika Anda mengunggah folder induk, workflow tidak akan terbaca dan build tidak jalan.

Benar (root repository):

```
settings.gradle
build.gradle
gradle.properties
.gitignore
.github/workflows/android.yml
app/
gradle/
README.md
...
```

Salah: `TIMER_MAXDIS_JAVA/settings.gradle` (ada satu lapis folder di atasnya).

---

## 1. Persiapan (sekali saja)

1. Buat akun di <https://github.com> dan verifikasi email.
2. Pasang **Git**: <https://git-scm.com/downloads>.
   Di Windows, pilih opsi bawaan; Git for Windows sudah menyertakan *Git Credential Manager* yang
   membuka jendela login browser saat push pertama.
3. Atur identitas (ganti dengan data Anda):

```bash
git config --global user.name  "Nama Anda"
git config --global user.email "email-anda@contoh.com"
```

4. Ekstrak ZIP project. Pastikan Anda membuka folder yang **langsung berisi** `settings.gradle`.

---

## 2. Buat repository kosong di GitHub

1. Klik **+** (kanan atas) lalu **New repository**.
2. **Repository name**: misalnya `timer-maxdis-java`.
3. Pilih **Private** bila kode tidak untuk umum.
4. **Jangan** centang *Add a README*, *.gitignore*, atau *license* (project sudah punya). Repository
   harus benar-benar kosong agar push pertama tidak bentrok.
5. Klik **Create repository**. Salin URL HTTPS-nya, contoh `https://github.com/USERNAME/timer-maxdis-java.git`.

---

## 3. Metode A — Git lewat terminal (direkomendasikan)

Buka terminal (Windows: *Git Bash* atau PowerShell) **di dalam folder project**:

```bash
cd path/ke/TIMER_MAXDIS_JAVA

git init
git add .
git status                      # periksa: .github/workflows/android.yml harus ikut terdaftar
git commit -m "Initial commit: TIMER MAXDIS Java"
git branch -M main
git remote add origin https://github.com/USERNAME/timer-maxdis-java.git
git push -u origin main
```

Autentikasi saat `git push`:

- **Windows / macOS dengan Git Credential Manager**: jendela browser terbuka, login GitHub, selesai.
- **Meminta password di terminal**: GitHub *tidak menerima password akun* lagi. Pakai Personal Access Token:
  1. GitHub → foto profil → **Settings** → **Developer settings** → **Personal access tokens**.
  2. Buat token baru. Berikan akses baca-tulis **Contents** pada repository ini, dan
     **Workflows** (token klasik: centang scope `repo` dan `workflow`).
  3. Tempel token sebagai password saat diminta.
- **Alternatif paling mudah: GitHub CLI** (<https://cli.github.com>):

```bash
gh auth login
gh auth refresh -s workflow     # jika push ditolak karena file workflow
```

> Jika muncul pesan seperti *refusing to allow a token to create or update workflow ... without `workflow` scope*,
> berarti token Anda belum punya izin Workflows. Tambahkan izinnya (lihat di atas), lalu ulangi `git push`.

Selesai. Buka halaman repository dan pastikan folder `.github/workflows` dan `app` tampil.

---

## 4. Metode B — Upload lewat browser (tanpa Git)

Cocok bila tidak ingin memasang Git.

1. Buka repository yang baru dibuat → tautan **uploading an existing file**.
2. Buka folder project di File Explorer, tekan **Ctrl+A** (pilih semua isi), lalu **seret** ke halaman GitHub.
   Pilih *isi* folder, bukan folder induknya.
3. Tunggu semua file selesai diunggah (GitHub membatasi 100 file per unggahan; project ini kurang dari itu).
4. Isi *Commit message*, pilih **Commit directly to the main branch**, klik **Commit changes**.
5. **Periksa wajib**: pastikan `.github/workflows/android.yml` dan `.gitignore` ada di repository.
   - macOS: file berawalan titik tersembunyi di Finder; tekan **Cmd+Shift+.** agar terlihat sebelum menyeret.
   - Jika `.github` tidak ikut, klik **Add file → Create new file**, ketik nama
     `.github/workflows/android.yml` (garis miring otomatis membuat folder), lalu salin isi file tersebut.

---

## 5. Metode C — Dari Android Studio

1. Buka project → menu **Git → Create Git Repository…** (atau **VCS → Create Git Repository**), pilih folder root.
2. **Git → GitHub → Share Project on GitHub**, login, isi nama repository, klik **Share**.
3. Android Studio melakukan commit dan push pertama untuk Anda.

---

## 6. Menjalankan build dan mengunduh APK

1. Buka tab **Actions** di repository. Jika ada tombol *"I understand my workflows, go ahead and enable them"*, klik.
2. Setiap `push` ke `main` otomatis menjalankan workflow **Build TIMER MAXDIS APK**. Untuk menjalankan
   manual: pilih workflow itu → **Run workflow** → **Run workflow**.
3. Klik run yang berjalan. Langkahnya: unit test → build APK → unggah hasil. Tunggu sekitar 3–8 menit
   (lebih lama pada run pertama).
4. Bila hijau (✓), gulir ke bagian **Artifacts** di halaman run, unduh **TIMER-MAXDIS-debug-apk**
   (berupa ZIP), ekstrak, ambil `app-debug.apk`.
5. Bila merah (✗), klik langkah yang gagal untuk melihat pesan galat. Artifact **unit-test-report** berisi
   laporan test bila yang gagal adalah unit test.

---

## 7. Memasang APK di HP Samsung / Android

1. Kirim `app-debug.apk` ke HP (kabel, Google Drive, atau unduh langsung dari GitHub di HP).
2. Buka file tersebut. Android akan meminta izin **Pasang aplikasi tidak dikenal** untuk aplikasi yang
   Anda pakai membuka (Files / Chrome). Izinkan.
3. **Samsung One UI 6.1+**: bila instalasi diblokir, matikan sementara **Auto Blocker**
   (Setelan → Keamanan dan privasi → Auto Blocker), pasang aplikasi, lalu nyalakan lagi.
4. Jika Play Protect memperingatkan, pilih **Tetap instal**. Ini wajar untuk APK debug buatan sendiri.
5. Saat pertama dibuka: izinkan **Notifikasi**, dan izinkan **Alarms & reminders** saat diminta.
6. Samsung: Setelan → Aplikasi → TIMER MAXDIS → Baterai → **Tidak dibatasi**, agar alarm tidak ditunda.

---

## 8. Mengirim perubahan berikutnya

Setiap kali kode diubah:

```bash
git add .
git commit -m "Jelaskan perubahan singkat"
git push
```

Build otomatis berjalan lagi dan menghasilkan APK baru di tab Actions.

---

## 9. (Opsional) Membuat Gradle Wrapper

Android Studio dan GitHub Actions tidak membutuhkannya. Jika ingin `./gradlew` tersedia untuk build lokal
via terminal, jalankan sekali (membutuhkan Gradle terpasang):

```bash
gradle wrapper --gradle-version 8.9
git add gradlew gradlew.bat gradle/wrapper
git commit -m "Tambah Gradle Wrapper"
git push
```

---

## 10. (Opsional) Rilis dan keamanan

- **Jangan pernah** meng-commit file `*.jks` / `*.keystore` atau kata sandinya. `.gitignore` sudah
  memblokirnya. Untuk build rilis bertanda tangan, simpan keystore sebagai **Secrets**
  (Settings → Secrets and variables → Actions).
- Untuk membagikan APK ke rekan: **Releases → Draft a new release**, buat tag (mis. `v1.0.0`), lampirkan
  `app-debug.apk`, lalu **Publish release**. Repository Private hanya dapat diakses orang yang Anda undang.
- Penerbitan ke Google Play membutuhkan build rilis bertanda tangan (AAB) dan deklarasi layanan
  *foreground* `specialUse` di Play Console.

---

## 11. Pemecahan masalah

| Gejala | Penyebab | Solusi |
|--------|----------|--------|
| Tab Actions kosong / workflow tidak muncul | `.github/workflows/android.yml` tidak ada di root | Periksa struktur di bagian 0; unggah ulang file tersebut. |
| `Support for password authentication was removed` | Memakai password akun | Pakai Personal Access Token atau `gh auth login` (bagian 3). |
| `refusing to allow ... workflow scope` | Token tanpa izin Workflows | Tambahkan izin Workflows / scope `workflow`. |
| `failed to push some refs` / `rejected` | Repository tidak kosong (ada README bawaan) | `git pull origin main --rebase`, lalu `git push`; atau buat repository baru yang benar-benar kosong. |
| `remote origin already exists` | Remote sudah ditambahkan | `git remote set-url origin URL_BARU`. |
| `src refspec main does not match any` | Belum ada commit | Jalankan `git add .` dan `git commit` terlebih dahulu. |
| Build gagal: `SDK location not found` (lokal) | `local.properties` belum ada | Buka lewat Android Studio (membuatnya otomatis) atau set `ANDROID_HOME`. |
| Build gagal: versi Java | JDK bukan 17 | Android Studio → Settings → Build Tools → Gradle → **Gradle JDK = 17**. |
| Build gagal: `Failed to find Build Tools / platform` | SDK 35 belum terpasang | Android Studio → SDK Manager → pasang **Android 15 (API 35)** dan Build-Tools 35. |
| APK tidak ada di Artifacts | Build/test gagal | Lihat langkah merah di log; cek artifact `unit-test-report`. |
| HP menolak memasang APK | Sumber tidak dikenal / Auto Blocker | Ikuti bagian 7. |
