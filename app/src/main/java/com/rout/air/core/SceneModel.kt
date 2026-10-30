package com.rout.air.core

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
 * Mode pencampuran layer, mengikuti pilihan Blend Mode di OBS.
 *
 * `Add` dan `Screen` mencerahkan, `Multiply` menggelapkan. Nilai `name`
 * dipakai apa adanya di JSON, jadi jangan diubah tanpa migrasi.
 */
enum class BlendMode(val label: String) {
    Normal("Normal"),
    Add("Add (tambah)"),
    Screen("Screen"),
    Multiply("Multiply"),
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
    val color: Long = 0xFF00E5FF,
    val width: Int = 0,
    val height: Int = 0,
)

/** Sumber (source) yang bisa dipakai banyak item. */
data class SceneSource(
    val id: String = newId("src"),
    val type: SourceType,
    val name: String,
    val settings: SourceSettings = SourceSettings(),
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
        fun default(canvasWidth: Int, canvasHeight: Int): SceneCollection {
            val screen = SceneSource(type = SourceType.Screen, name = "Layar")
            val scene = Scene(
                name = "Adegan 1",
                // Layar dikunci default supaya komposisi tidak bergeser saat
                // mengatur item lain. Buka lewat ikon gembok bila perlu digeser.
                items = listOf(SceneItem(sourceId = screen.id, z = 0, locked = true)),
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
