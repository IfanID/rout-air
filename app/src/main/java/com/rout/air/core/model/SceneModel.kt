package com.rout.air.core.model

import java.util.UUID

/**
 * Model adegan (scene) yang meniru `obs_scene_item`.
 *
 * Koordinat transform memakai piksel kanvas dengan titik asal kiri-atas,
 * mengikuti kebiasaan OBS dan lebih mudah dipahami di UI. Konversi ke
 * koordinat OpenGL (kiri-bawah) dilakukan saat menggambar.
 */
enum class SourceType {
    Screen,
    Image,
    Text,
    Color,

    /** Sumber microphone/Audio Input Capture seperti OBS. */
    AudioInput,

    /** Sumber audio output/desktop seperti OBS. */
    AudioOutput,

    /** Kamera perangkat; hanya tampil saat sesi tangkap aktif. */
    Camera,

    /** Video lokal yang diputar berulang sebagai sumber. */
    Media,

    /** Halaman web yang diambil sebagai gambar berkala. */
    Browser,

    /** Grup: adegan lain yang digambar sebagai satu lapisan. */
    Group,

    /** Beberapa gambar bergantian sesuai interval. */
    SlideShow,
}

/**
 * Arah tampil sumber Layar.
 *
 * `Portrait`/`Landscape` memaksa konten tampil tegak/mendatar: bila orientasi
 * tangkapan berbeda, gambar diputar 90 derajat lalu dimuat penuh tanpa
 * terpotong. `Auto` mengikuti orientasi tangkapan apa adanya.
 */
enum class ScreenDisplay {
    Auto,
    Portrait,
    Landscape,
}

/** True bila konten perlu diputar 90 derajat agar sesuai pilihan tampilan. */
fun screenNeedsRotation(
    display: ScreenDisplay?,
    sourceWidth: Int,
    sourceHeight: Int,
): Boolean {
    if (display == null || display == ScreenDisplay.Auto) return false
    if (sourceWidth <= 0 || sourceHeight <= 0) return false
    return when (display) {
        ScreenDisplay.Portrait -> sourceWidth > sourceHeight
        ScreenDisplay.Landscape -> sourceHeight > sourceWidth
        ScreenDisplay.Auto -> false
    }
}

/** Titik jangkar item terhadap posisi `Transform`. */
enum class Align {
    TopLeft,
    Top,
    TopRight,
    Left,
    Center,
    Right,
    BottomLeft,
    Bottom,
    BottomRight,
}

/**
 * Mode memuat sumber ke dalam kotak bounds, mengikuti `obs_bounds_type` di OBS.
 *
 * `NONE`       — ukuran asli, tanpa memaksa pas ke bounds.
 * `STRETCH`    — meregangkan ke bounds (rasio tidak dipertahankan).
 * `SCALE_INNER`— memasukkan seluruh sumber di bounds, tepi kosong di sisi yang lebih lepas.
 * `SCALE_OUTER`— menutup seluruh bounds, tepi sumber terpotong jika rasio berbeda.
 * `SCALE_TO_WIDTH` — lebar pas bounds, tinggi disesuaikan proporsional.
 * `SCALE_TO_HEIGHT`— tinggi pas bounds, lebar disesuaikan proporsional.
 */
enum class BoundsType {
    None,
    Stretch,
    ScaleInner,
    ScaleOuter,
    ScaleToWidth,
    ScaleToHeight,
}

/**
 * Mode pencampuran layer, mengikuti pilihan Blend Mode di OBS.
 *
 * `Add` dan `Screen` mencerahkan, `Multiply` menggelapkan. Nilai `name`
 * dipakai apa adanya di JSON, jadi jangan diubah tanpa migrasi.
 */
enum class BlendMode {
    Normal,
    Add,
    Screen,
    Multiply,
}

/**
 * Transformasi satu item. `x`/`y` adalah posisi titik jangkar di kanvas,
 * `scale` relatif terhadap ukuran asli sumber, `rotation` dalam derajat
 * searah jarum jam.
 */
data class Transform(
    val x: Float = 0f,
    val y: Float = 0f,
    val scaleX: Float = 1f,
    val scaleY: Float = 1f,
    val rotation: Float = 0f,
    val align: Align = Align.TopLeft,
    /** Cermin horizontal/vertikal, seperti Flip di OBS. */
    val flipH: Boolean = false,
    val flipV: Boolean = false,
)

/** Crop dalam fraksi 0..0.49 dari sisi sumber. */
data class Crop(
    val left: Float = 0f,
    val top: Float = 0f,
    val right: Float = 0f,
    val bottom: Float = 0f,
) {
    val isIdentity: Boolean
        get() = left == 0f && top == 0f && right == 0f && bottom == 0f
}

/** Satu item sumber di dalam adegan. */
data class SceneItem(
    val id: String = newId("item"),
    val sourceId: String,
    val z: Int = 0,
    val visible: Boolean = true,
    val locked: Boolean = false,
    val transform: Transform = Transform(),
    val crop: Crop = Crop(),
    val blend: BlendMode = BlendMode.Normal,
    /** Opasitas 0..1 seperti slider Opacity di OBS. */
    val opacity: Float = 1f,
)

/**
 * Setelan khusus per tipe sumber.
 *
 * `width`/`height` adalah ukuran asli (intrinsic) sumber di kanvas.
 * Untuk sumber layar nilainya 0 karena ukurannya mengikuti kanvas.
 */
data class SourceSettings(
    val imagePath: String? = null,
    val text: String = "Teks baru",
    val textSize: Float = 48f,
    val textColor: Long = 0xFFFFFFFF,
    /** Indeks huruf yang diberi warna lain; kosong berarti tanpa sorotan. */
    val textHighlightIndices: List<Int> = emptyList(),
    val textHighlightColor: Long = 0xFF00E5FF,
    /** Teks rata tengah di dalam kotaknya (dipakai layar jeda). */
    val textCenter: Boolean = false,
    /** Huruf dikecilkan otomatis agar muat satu baris. */
    val textAutoFit: Boolean = false,
    val color: Long = 0xFF00E5FF,
    val width: Int = 0,
    val height: Int = 0,
    /** Mode memuat sumber ke kotak bounds (mirip OBS bounds type). */
    val boundsType: BoundsType = BoundsType.None,
    /** Lebar kotak bounds dalam piksel kanvas; 0 = ukuran sumber asli. */
    val boundsWidth: Int = 0,
    /** Tinggi kotak bounds dalam piksel kanvas; 0 = ukuran sumber asli. */
    val boundsHeight: Int = 0,
    /** Perataan sumber di dalam kotak bounds. */
    val boundsAlign: Align = Align.Center,
    /** Potong tepi sumber yang keluar bounds (hanya untuk scale outer/fit). */
    val cropToBounds: Boolean = false,
    /** Id perangkat input untuk AudioInput; 0 berarti perangkat default. */
    val audioDeviceId: Int = 0,
    val audioDeviceName: String? = null,
    val audioDeviceType: Int = 0,
    val audioScoEnabled: Boolean = false,
    /** Arah tampil sumber Layar; null/`Auto` mengikuti tangkapan. */
    val screenDisplay: ScreenDisplay? = null,
    /**
     * Alamat stream kamera H.264 dari perangkat lain (Wi-Fi lokal), mis.
     * `tcp://192.168.43.57:3333` (Annex B).
     */
    val cameraUrl: String? = null,
    /** Dinaikkan tombol Hubungkan untuk memaksa koneksi ulang. */
    val cameraConnectToken: Int = 0,
    /** Video Media dijeda tanpa melepas sumber. */
    val mediaPaused: Boolean = false,
    /** Kecepatan putar Media 0,5–2×. */
    val mediaSpeed: Float = 1f,
    /** Putar ulang otomatis saat habis. */
    val mediaLoop: Boolean = true,
    /** Dinaikkan untuk memutar video dari awal lagi. */
    val mediaRestartToken: Int = 0,
    /** Posisi tujuan 0–100% dan token pemicu seek. */
    val mediaSeekPercent: Float = 0f,
    val mediaSeekToken: Int = 0,
    /** Detik antar tangkapan Browser. */
    val browserRefreshSec: Float = 1f,
    /** Perbesaran halaman Browser 0,5–2×. */
    val browserZoom: Float = 1f,
    /** Pakai user agent desktop. */
    val browserDesktop: Boolean = false,
    /** Dinaikkan untuk memuat ulang halaman. */
    val browserReloadToken: Int = 0,
    /** Urutan gambar slide show diacak tetapi tetap sama tiap putaran. */
    val slideShuffle: Boolean = false,
    /** Fade singkat saat gambar slide show berganti. */
    val slideFade: Boolean = false,
    /** Path video lokal untuk sumber [SourceType.Media]. */
    val mediaPath: String? = null,
    /** URL halaman untuk sumber [SourceType.Browser]. */
    val browserUrl: String? = null,
    /** Scene yang dipakai sumber [SourceType.Group]. */
    val groupSceneId: String? = null,
    /** Daftar gambar untuk [SourceType.SlideShow]. */
    val imagePaths: List<String> = emptyList(),
    /** Detik per gambar pada slide show. */
    val slideIntervalSec: Int = 5,
)

/** Sumber (source) yang bisa dipakai banyak item. */
data class SceneSource(
    val id: String = newId("src"),
    val type: SourceType,
    val name: String,
    val settings: SourceSettings = SourceSettings(),
    /** Filter GPU per sumber; berlaku di semua scene yang memakainya. */
    val filters: List<VideoFilter> = emptyList(),
)

/** Satu adegan berisi daftar item. */
data class Scene(
    val id: String = newId("scene"),
    val name: String,
    val items: List<SceneItem> = emptyList(),
)

/** Scene collection berversi, disimpan sebagai JSON. */
data class SceneCollection(
    val version: Int = 4,
    val activeSceneId: String,
    val scenes: List<Scene>,
    val sources: List<SceneSource>,
    /** Nama koleksi/proyek; kosong berarti memakai nama bawaan di UI. */
    val title: String = "",
) {
    fun activeScene(): Scene? =
        scenes.firstOrNull { it.id == activeSceneId } ?: scenes.firstOrNull()

    fun source(id: String): SceneSource? = sources.firstOrNull { it.id == id }

    /** Item berdasarkan id, dipakai UI untuk panel properti dan gestur. */
    fun item(id: String?): SceneItem? =
        id?.let { key -> scenes.firstNotNullOfOrNull { s -> s.items.firstOrNull { it.id == key } } }

    fun itemSource(item: SceneItem): SceneSource? = source(item.sourceId)

    companion object {
        /**
         * Adegan awal: satu sumber layar penuh. Ukuran asli sumber layar
         * mengikuti kanvas, jadi transform identitas sudah memenuhi bidikan.
         */
        fun default(
            canvasWidth: Int,
            canvasHeight: Int,
            screenName: String,
            sceneName: String,
        ): SceneCollection {
            val screen = SceneSource(type = SourceType.Screen, name = screenName)
            val scene = Scene(
                name = sceneName,
                // Layar dikunci default supaya komposisi tidak bergeser saat
                // mengatur item lain. Buka lewat ikon gembok bila perlu digeser.
                // Tidak dikunci: posisi layar perlu bisa diatur, mis. saat
                // kanvas horizontal menampilkan tangkapan portrait.
                items = listOf(SceneItem(sourceId = screen.id, z = 0, locked = false)),
            )
            return SceneCollection(
                activeSceneId = scene.id,
                scenes = listOf(scene),
                sources = listOf(screen),
            )
        }
    }
}

/** Id pendek yang cukup unik untuk scene/source/item. */
internal fun newId(prefix: String): String =
    "$prefix-${UUID.randomUUID().toString().take(8)}"
