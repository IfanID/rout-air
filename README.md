# RoutAir

Aplikasi **perekam layar Android bergaya OBS Studio**: scene multi-sumber,
pratinjau ala Studio Mode, mixer audio, dan rekaman MP4.

- Package: `com.rout.air`
- Bahasa: Kotlin + Jetpack Compose + OpenGL ES
- `minSdk 31`, `compileSdk`/`targetSdk` 37
- Bahasa aplikasi: **Indonesia** (default, lewat `res/values/strings.xml`)

## Fitur

### Siaran (Home)
- Rekam layar ke **MP4 (H.264 + AAC)** memakai `MediaProjection`,
  `MediaCodec`, dan `MediaMuxer`.
- **Audio internal** lewat `AudioPlaybackCapture`, ditambah mikrofon dan
  kanal aplikasi lewat mixer.
- Sesi pratinjau tetap hidup saat aplikasi ke background; rekaman berjalan
  di foreground service dengan notifikasi.
- **Izin rekam layar**:
  - diminta otomatis saat aplikasi dibuka;
  - tombol ikon **izin rekam layar** (cadangan) muncul saat sesi belum aktif;
  - tombol **akhiri sesi** melepas izin `MediaProjection`
    (izin harus diminta ulang pada sesi berikutnya).
- **Tombol Rekam** berubah sesuai kondisi: ikon rekam (siap), spinner
  (menyiapkan), ikon stop merah berdenyut (merekam).
- **Tombol Live** untuk mulai/berhenti streaming RTMP(S); status tombol
  mengikuti koneksi (menyambung, live, sambung ulang, error).
- **Dua tombol overlay terpisah**: `Overlay Rekam` dan `Overlay Live`
  (hanya satu yang boleh aktif pada satu waktu).
- **Jeda/lanjutkan rekaman** tanpa memutus sesi atau membuat gap di timeline;
  bisa dari halaman Siaran/Studio maupun notifikasi.
- **Statistik langsung** saat merekam: ukuran berkas, bitrate, jumlah frame,
  dan frame yang gagal (`drop`).
- **Quick Settings tile** untuk mulai/stop rekaman tanpa membuka aplikasi.
- **Pemeriksaan ruang penyimpanan** sebelum merekam (minimal 200 MB) dan
  penghentian aman bila penulisan file gagal terus.
- **Pembersihan otomatis** file rekaman pending yang tertinggal saat proses
  aplikasi mati di tengah rekaman.

### Overlay
- **Overlay rekaman** terpisah dari overlay live: logo transparan yang bisa
  digeser, panel 9 slot berisi Rekam/Stop, Jeda/Lanjut, pratinjau, VU audio,
  statistik live, dan tombol buka Studio.
- **Overlay live** memakai service sendiri (`OverlayLiveService`) dengan panel
  Obrolan YouTube dan jumlah penonton asli setelah login Google.
- Panel bisa diperbesar/diperkecil, diingat posisinya, dan tombol tutup ada di
  notifikasi.

### Live Streaming
- **RTMP / RTMPS** langsung dari encoder yang sama dengan rekaman
  (`MediaCodec` H.264 + AAC).
- **Rekam + live bersamaan**: keduanya memakai satu encoder; menghentikan
  salah satu tidak menghentikan yang lain.
- **Sambung ulang otomatis** dengan backoff 1–10 detik saat koneksi terputus.
- **Statistik jaringan** di halaman Siaran: data terkirim, bitrate, tag yang
  dibuang, dan jumlah reconnect.
- Server dan kunci stream diatur di **Setelan → Live**; kunci disimpan lokal.

### Studio Mode
- Dua panel: **Pratinjau (mentah)** untuk menyunting dan **Program (siaran)**
  untuk keluaran yang direkam.
- Tombol **Transisi** memindahkan hasil edit ke Program; rekaman selalu
  memakai Program.
- **Pratinjau penuh**: tombol ikon di header menyembunyikan panel Program
  sehingga pratinjau jadi luas; header, tombol Transisi/Rekam, dan navbar
  tetap tampil. Status full screen diingat saat berpindah tab.
- Gestur di pratinjau: geser, cubit (skala), putar, dan jangkar.

### Adegan
- Scene berlapis dengan sumber **Layar, Gambar, Teks, dan Warna**.
- Transform ala OBS: posisi, skala (seragam/X/Y), rotasi, crop, flip, jangkar.
- Opacity dan blend mode (Normal, Add, Screen, Multiply).
- Urutkan lapisan (drag handle), kunci/buka kunci, sembunyikan, duplikat,
  hapus.
- **Satu Layar per scene**; sumber Layar dipakai ulang di scene lain tanpa
  menduplikasi tangkapan.
- Menu transform (tekan lama item): flip, putar, fit/stretch/center,
  **Reset posisi**, **Reset scale**, **Reset rotasi**, **Reset transformasi**.
- Scene collection disimpan sebagai JSON dengan autosave.

### Audio
- **Dock Audio Mixer ala OBS** dengan dukungan layout baris dan kolom.
- **Dua sumber global**: *Audio internal* (Desktop Audio) dan *Mikrofon*
  (Mic/Aux), plus **kanal aplikasi** dan kanal dari sumber audio legacy.
- **Application Audio Capture**: tambah kanal per aplikasi (UID) dari tab
  Audio; aplikasi yang melarang capture akan senyap.
- **Tombol on/off kanal** langsung di samping label **Aktif/Nonaktif**
  (tidak perlu buka Properti).
- **Context menu** ala OBS: Unhide All, Pin/Unpin, Hide/Unhide, Lock Volume,
  Copy/Paste Filters, Rename, Filters, Properties.
- **Fader logaritmik** `OBS_FADER_LOG` (-96…0 dB, adv +26 dB).
- **Meter L/R**: hijau -60…-20, kuning -20…-9, merah -9…0, peak hold,
  clip, decay rate, True Peak.
- **Mute**, **monitoring on/off**, **balance**, **force mono**,
  **sync offset**, dan **tracks 1–6** per kanal.
- **Advanced Audio Properties** dalam bentuk tabel.
- **Filter per kanal**: Gain, Noise Gate, Compressor, Limiter, Invert
  Polarity, Noise Suppression, Equalizer 3-band; bisa diurutkan,
  di-enable/disable, disalin, dan ditempel.
- **Monitoring device** dipilih di Setelan → Audio (daftar sudah
  di-deduplikasi).

### Rekaman
- Daftar file rekaman, buka, bagikan, dan hapus.
- Lokasi simpan bisa dipilih: `Movies/RoutAir` (default), `Movies`,
  `DCIM/RoutAir`, atau `DCIM/Camera`.
- File ditulis lewat `MediaStore` (langsung muncul di galeri, tanpa izin
  storage).

### Setelan
- **Video**: resolusi kanvas, orientasi (Auto/Vertikal/Horizontal), output
  resolution (rescale 75/50/25%), fps, color space/range, bitrate, mode isi
  (Pas/Penuh/Peregangan).
- **Output**: tab **Streaming**, **Recording**, dan **Audio** (mode Advanced
  saja, mengikuti OBS). Streaming: encoder, bitrate video/audio, rate control
  (VBR/CBR), keyframe, profile, rescale, audio track 1–6. Recording: bitrate,
  kualitas, format MP4, nama berkas (`{date}`, `{time}`, `{datetime}`), tanpa
  spasi, track 1–6, lokasi penyimpanan.
- **Stream**: Service (Custom/YouTube/Twitch/Facebook/Kick), Server, Stream Key
  (tampil/sembunyikan), autentikasi username/password untuk Custom, Audio
  Track 1–6, tes koneksi, tombol dashboard, sambung ulang otomatis, dan
  **login Google** untuk chat + jumlah penonton YouTube.
- **Audio**: sample rate, jumlah kanal, monitoring device, peak meter,
  push-to-talk/push-to-mute.
- **Advanced**: informasi teknis/diagnostik dan opsi performa
  (**Hemat CPU saat layar diam** dan **Thermal throttle**, keduanya default mati
  agar encoder selalu 60 fps seperti OBS).

## Struktur project

```
app/src/main/java/com/rout/air/
├── core/      model scene, konfigurasi, model audio
├── data/      penyimpanan scene/setelan, mixer, SceneManager
├── capture/   pembungkus MediaProjection / VirtualDisplay
├── encode/    encoder audio, filter, sync
├── output/    pipeline rekaman (GL, encoder, muxer, engine)
├── service/   foreground service rekaman
└── ui/        Compose: Siaran, Studio, Adegan, Audio, Rekaman, Setelan
```

## Internasionalisasi (i18n)

Semua teks UI berada di satu file dengan pengelompokan per halaman:

```
app/src/main/res/values/strings.xml
```

- Default: **Bahasa Indonesia**.
- Dikelompokkan dengan komentar `<!-- ===== Halaman ... ===== -->`
  dan awalan nama: `common_`, `home_`, `studio_`, `scenes_`, `audio_`,
  `recordings_`, `settings_`.
- Komponen Compose memakai `stringResource(R.string.xxx)`; teks dinamis
  memakai placeholder (`%1$s`, `%1$d`).
- Untuk menambah bahasa lain, buat `values-<kode>/strings.xml`
  dengan nama resource yang sama.

## Build

Prasyarat: JDK 21/25, Android SDK (platform 36), Gradle wrapper.

```bash
./gradlew :app:compileDebugKotlin     # kompilasi
./gradlew :app:testDebugUnitTest      # unit test
android-build debug                   # build APK debug
android-build release                 # build APK release (ditandatangani)
```

Hasil build debug dipindah ke:

```
/storage/emulated/0/Documents/app/debug/RoutAir-debug.apk
```

## Catatan

- Tangkapan per aplikasi memakai `AudioPlaybackCapture`; aplikasi dengan
  `allowAudioPlaybackCapture=false` tidak akan menghasilkan suara.
- Monitoring lewat Bluetooth (TWS) memakai profil SCO/HFP; perangkat harus
  mendukung jalur mic/headset.
- Rekaman memakai satu track video/audio utama; multitrack (1–6) disimpan
  sebagai bitmask pada kanal.
