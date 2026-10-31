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

### Setelan
- **Video**: resolusi kanvas, orientasi (Auto/Vertikal/Horizontal), fps,
  bitrate, mode isi (Pas/Penuh/Peregangan).
- **Output**: bitrate, format, lokasi penyimpanan.
- **Audio**: sample rate, jumlah kanal, monitoring device, peak meter,
  push-to-talk/push-to-mute.
- **Hotkeys** dan **Advanced** (informasi teknis/diagnostik).

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
