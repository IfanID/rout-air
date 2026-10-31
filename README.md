# RoutAir

Aplikasi **perekam layar Android bergaya OBS Studio**: scene multi-sumber,
pratinjau ala Studio Mode, dan rekaman MP4 dengan mixer audio internal +
mikrofon.

- Package: `com.rout.air`
- Bahasa: Kotlin + Jetpack Compose + OpenGL ES
- `minSdk 31`, `compileSdk`/`targetSdk 37`

## Fitur

### Rekaman
- Rekam layar ke **MP4 (H.264)** memakai `MediaProjection`, `MediaCodec`,
  dan `MediaMuxer`.
- **Audio internal** lewat `AudioPlaybackCapture` (AAC 48 kHz).
- Sesi pratinjau tetap hidup saat aplikasi ke background; rekaman berjalan
  lewat foreground service.

### Scene
- Scene berlapis dengan sumber **Layar, Gambar, Teks, dan Warna**.
- Audio (mikrofon, audio internal, dan audio per aplikasi) dikelola di tab
  **Audio**, bukan sebagai sumber visual di Adegan. Sumber audio lama tetap
  didukung sebagai sumber legacy.
- Transform ala OBS: geser, cubit (skala), putar, crop, flip, jangkar.
- Opacity dan blend mode (Normal, Add, Screen, Multiply).
- Urutkan lapisan, kunci, sembunyikan, duplikat item.
- **Satu Layar per scene**; Layar bisa dipakai ulang di scene lain tanpa
  menduplikasi sumber tangkapan.
- Scene collection disimpan sebagai JSON dengan autosave 500 ms.

### Studio Mode
- **Pratinjau** untuk menyunting dan **Program** untuk keluaran yang direkam.
- Tombol **Transisi** memindahkan hasil edit ke Program; rekaman selalu
  memakai Program.
- Panel pratinjau bisa digeser, dicubit, diputar, dan crop langsung.

### Audio (M3)
- **Dock Audio Mixer ala OBS** dengan toolbar bawah: jumlah sumber tersembunyi,
  ikon tata letak tujuan, gear Advanced Audio, dan menu **Opsi**.
- **Application Audio Capture**: tambah kanal per aplikasi dari tab Audio
  (`＋ App`), dengan pencarian aplikasi, volume/mute/filter/monitor, dan
  pemilihan track. Kanal aplikasi memakai `AudioPlaybackCapture` dengan UID
  aplikasi; aplikasi yang melarang capture akan senyap.
- **Dua sumber global tetap**: *Audio internal* (Desktop Audio) dan *Mikrofon*
  (Mic/Aux), ditambah **kanal aplikasi** dan kanal dinamis dari sumber audio
  lama di panel Sumber.
- **Context menu** persis struktur OBS: Unhide All, Pin/Unpin, Hide/Unhide,
  Lock Volume, Copy/Paste Filters, Rename, Filters, Properties.
- Kategori chip: **Aktif, Nonaktif, Disematkan, Disembunyikan, Tanpa track**.
- **Fader logaritmik** `OBS_FADER_LOG` (-96…0 dB), tick tiap 10 dB.
- **Meter L/R** gaya OBS: hijau -60…-20, kuning -20…-9, merah -9…0,
  peak-hold, clip, decay rate, dan True Peak (perkiraan oversampling).
- **Mute**, **monitoring on/off**, **balance**, **force mono**,
  **sync offset** (-950…20000 ms), dan **tracks 1–6** per kanal.
- **Advanced Audio Properties** berupa tabel: sumber, status, volume (dB/%),
  mono, balance, sync offset, monitoring, tracks.
- **Filters per kanal**: Gain, Noise Gate, Compressor, Limiter, Invert
  Polarity, Noise Suppression, dan Equalizer 3-band; bisa diurutkan,
  di-enable/disable, disalin, dan ditempel.
- **Push-to-talk / Push-to-mute** berupa tombol tahan di halaman Audio.
- **Multitrack MP4**: track audio terpisah ditulis bila Track 2–6 dipilih di
  Setelan › Output.
- Sumber audio **hidup sejak sesi pratinjau**: meter dan monitoring bekerja
  walau tombol rekam belum ditekan, sama seperti OBS.
- Perubahan volume, mute, filter, balance, sync, dan monitoring **langsung
  berlaku saat merekam**; pengaturan mixer tersimpan otomatis.

### Antarmuka
- Enam tab: **Siaran, Studio, Adegan, Audio, Rekaman, Setelan**.
- Tema gelap dengan aksen cyan `#00E5FF`.

## Status Milestone

- **M1** — pipeline rekaman layar MP4 + audio internal: selesai.
- **M2** — scene multi-sumber, transform, Studio Mode, blend, opacity: selesai.
- **M3 (audio lanjutan)** — dock mixer OBS, mikrofon, meter L/R, pin/hide/lock/
  rename, advanced properties, filter audio, sync offset, mono, monitoring,
  tracks/multitrack, dan setelan audio OBS: selesai.
- **M2.5** — kamera: belum dikerjakan.
- **M4–M8** — pause/preset, RTMP, VST/plugin, replay buffer, dll:
  belum dikerjakan.

## Struktur Proyek

```
app/src/main/java/com/rout/air/
├── core/       model scene, model mixer audio, konfigurasi rekaman, state
├── data/       penyimpanan scene/setelan/mixer, log diagnostik
├── encode/     encoder video/audio (mixer PCM), penulis MP4
├── engine/     EGL, kompositor OpenGL, tekstur sumber
├── output/     pipeline rekaman & jembatan engine
├── service/    foreground service tangkap layar
└── ui/         Compose: Beranda, Studio, Adegan, Audio, Rekaman, Setelan
```

## Build

Prasyarat:
- JDK 21 atau 25 (sesuai versi Gradle proyek)
- Android SDK Platform 36 + Build Tools 36
- Gradle wrapper sudah disertakan

```bash
./gradlew assembleDebug
# atau memakai skrip lingkungan pengembangan:
android-build debug /path/ke/rout-air
```

APK debug dihasilkan di `app/build/outputs/apk/debug/` (atau dipindahkan oleh
`android-build` ke folder `Documents/app/debug`).

## Catatan Audio

- Audio internal hanya menangkap aplikasi yang **mengizinkan
  AudioPlaybackCapture**. Aplikasi ber-DRM atau yang memblokir akan senyap.
- Mesin audio berjalan selama sesi tangkap layar (pratinjau), bukan hanya saat
  merekam. Encoder AAC baru dibuat ketika rekaman dimulai, jadi pratinjau
  hanya menangkap, mencampur, mengukur level, dan memonitor.
- Mikrofon memakai `AudioRecord` dengan sumber `UNPROCESSED` bila didukung,
  lalu jatuh ke `MIC`. Izin `RECORD_AUDIO` diminta saat sesi tangkap dibuka.
- Monitoring memutar campuran ke speaker/headset. Pakai headset agar tidak
  terjadi umpan balik (feedback); output monitor tidak ikut tertangkap ulang
  oleh AudioPlaybackCapture.
- Kanal yang diaktifkan saat rekaman sedang berjalan akan mulai ditangkap;
  minimal satu kanal harus aktif saat rekaman dimulai agar trek audio dibuat.
- Filter audio berjalan sebagai DSP di thread audio. VST plugin tidak
  didukung di Android; Noise Suppression memakai expander ringan, bukan
  algoritma spectral OBS.
- Multitrack MP4 menulis trek AAC terpisah untuk setiap track yang dipilih.
  Ini memakai CPU lebih tinggi dan tidak semua player memutar trek selain
  trek pertama.
- Beberapa sumber Audio Input bisa memakai perangkat berbeda, tetapi Android
  tidak selalu mengizinkan beberapa AudioRecord berjalan bersamaan; sumber
  yang gagal dibuka ditandai nonaktif di mixer.
- Daftar perangkat input dibersihkan: entri ganda untuk mikrofon yang sama
  digabung, device virtual seperti `remote-submix` disembunyikan, dan tipe
  perangkat ditampilkan (mikrofon bawaan, Bluetooth/TWS, headset kabel, USB).
- TWS Bluetooth memakai profil **SCO/HFP** untuk mikrofon. SCO diaktifkan
  otomatis saat kanal mikrofon Bluetooth dipakai dan dimatikan lagi saat
  tidak dipakai, dengan timeout + fallback ke mic default jika gagal. Selama
  SCO aktif, musik A2DP bisa berhenti atau turun kualitas karena TWS beralih
  ke mode headset; tidak semua perangkat mendukung mic TWS bersamaan dengan
  musik A2DP.
- Untuk merekam musik dari TWS/aplikasi musik, gunakan kanal **Audio internal
  (Desktop Audio)** atau **Application Audio Capture**, bukan mikrofon TWS.
- Monitoring memilih output Bluetooth/headset/USB yang terhubung secara
  otomatis. Jika SCO aktif (mic TWS), monitoring mengikuti perangkat
  komunikasi SCO; jika SCO mati, monitoring memakai jalur media A2DP.
  Pemilihan manual ada di **Setelan › Audio › Monitoring Device**.
- Filter audio (noise gate, compressor) dan sinkronisasi offset menyusul.

## Lisensi

Belum ditentukan.
