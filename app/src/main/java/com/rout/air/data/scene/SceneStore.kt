package com.rout.air.data.scene

import android.content.Context
import android.net.Uri
import com.rout.air.core.model.SceneCollection
import com.rout.air.core.model.SourceType
import java.io.File
import java.util.UUID

/**
 * Menyimpan scene collection sebagai JSON berversi di penyimpanan internal.
 *
 * Memakai `org.json` bawaan Android supaya tidak menambah dependensi baru;
 * formatnya tetap mudah dibaca dan bisa diimpor dari OBS nanti.
 */
class SceneStore(
    context: Context,
    val collectionId: String = DEFAULT_ID,
) {
    private val appContext = context.applicationContext
    private val file = dataFile(appContext, collectionId)
    private val backup = backupFile(appContext, collectionId)
    private val assets = File(appContext.filesDir, ASSET_DIR).apply { mkdirs() }

    fun load(): SceneCollection? {
        read(file)?.let { return migrate(it) }
        // Berkas utama rusak atau hilang: pakai salinan terakhir yang masih
        // valid, lalu pulihkan supaya penyimpanan berikutnya normal.
        val restored = read(backup) ?: return null
        runCatching { backup.copyTo(file, overwrite = true) }
        return migrate(restored)
    }

    private fun read(source: File): SceneCollection? {
        if (!source.exists()) return null
        return runCatching { SceneJson.decode(source.readText()) }.getOrNull()
    }

    /**
     * Migrasi versi lama. Versi 4 mengunci item Layar supaya komposisi di
     * pratinjau tidak bergeser tanpa sengaja. Pengguna tetap bisa membuka
     * kunci lewat ikon gembok; migrasi hanya berjalan sekali per berkas.
     */
    private fun migrate(collection: SceneCollection): SceneCollection {
        if (collection.version >= 4) return collection
        val screenIds = collection.sources
            .filter { it.type == SourceType.Screen }
            .map { it.id }
            .toSet()
        return collection.copy(
            version = 4,
            scenes = collection.scenes.map { scene ->
                scene.copy(
                    items = scene.items.map { item ->
                        if (item.sourceId in screenIds) item.copy(locked = true) else item
                    },
                )
            },
        )
    }

    fun save(collection: SceneCollection) {
        val text = SceneJson.encode(collection)
        runCatching {
            // Tulis ke berkas sementara lalu ganti nama: crash di tengah
            // penulisan tidak meninggalkan JSON setengah jadi.
            val temp = File(file.parentFile, "${file.name}.tmp")
            temp.writeText(text)
            if (file.exists()) runCatching { file.copyTo(backup, overwrite = true) }
            if (!temp.renameTo(file)) {
                file.writeText(text)
                temp.delete()
            }
        }
    }

    /**
     * Menyalin gambar pilihan pengguna ke penyimpanan aplikasi. URI dari
     * Photo Picker tidak selalu bisa dipakai setelah proses mati, sedangkan
     * path lokal selalu valid dan ikut aman saat scene diimpor.
     */
    fun importImage(uri: Uri): String? {
        val name = "img_${UUID.randomUUID().toString().take(8)}"
        val target = File(assets, name)
        return runCatching {
            val input = appContext.contentResolver.openInputStream(uri)
                ?: return null
            input.use { source ->
                target.outputStream().use { sink -> source.copyTo(sink) }
            }
            target.absolutePath
        }.getOrNull()
    }

    /** Menyalin video pilihan ke penyimpanan aplikasi agar path tetap valid. */
    fun importMedia(uri: Uri): String? {
        val name = "media_${UUID.randomUUID().toString().take(8)}.mp4"
        val target = File(assets, name)
        return runCatching {
            val input = appContext.contentResolver.openInputStream(uri) ?: return null
            input.use { source ->
                target.outputStream().use { sink -> source.copyTo(sink) }
            }
            target.absolutePath
        }.getOrNull()
    }

    /** Menghapus berkas koleksi ini beserta cadangannya. */
    fun deleteFiles() {
        runCatching { file.delete() }
        runCatching { backup.delete() }
        runCatching { File(file.parentFile, "${file.name}.tmp").delete() }
    }

    /** Ekspor koleksi aktif ke URI pilihan pengguna (SAF). */
    fun export(uri: Uri, collection: SceneCollection): Boolean = runCatching {
        val output = appContext.contentResolver.openOutputStream(uri) ?: return false
        output.use { it.write(SceneJson.encode(collection).toByteArray()) }
        true
    }.getOrDefault(false)

    /** Membaca koleksi dari URI hasil impor. */
    fun readFrom(uri: Uri): SceneCollection? = runCatching {
        val text = appContext.contentResolver.openInputStream(uri)?.use {
            it.readBytes().toString(Charsets.UTF_8)
        } ?: return null
        SceneJson.decode(text)
    }.getOrNull()

    companion object {
        const val DEFAULT_ID = "default"
        private const val FILE_NAME = "scene_collection.json"
        private const val BACKUP_NAME = "scene_collection.bak.json"
        private const val ASSET_DIR = "scene_assets"
        private const val PREFIX = "collection_"

        fun dataFile(context: Context, id: String): File =
            if (id == DEFAULT_ID) {
                File(context.filesDir, FILE_NAME)
            } else {
                File(context.filesDir, "$PREFIX$id.json")
            }

        fun backupFile(context: Context, id: String): File =
            if (id == DEFAULT_ID) {
                File(context.filesDir, BACKUP_NAME)
            } else {
                File(context.filesDir, "$PREFIX$id.bak.json")
            }

        /** Id koleksi yang tersimpan; koleksi default selalu di urutan pertama. */
        fun ids(context: Context): List<String> = buildList {
            if (dataFile(context, DEFAULT_ID).exists()) add(DEFAULT_ID)
            context.filesDir.listFiles { file ->
                file.isFile &&
                    file.name.startsWith(PREFIX) &&
                    file.name.endsWith(".json") &&
                    !file.name.endsWith(".bak.json")
            }?.mapTo(this) { it.name.removePrefix(PREFIX).removeSuffix(".json") }
        }.distinct()
    }
}
