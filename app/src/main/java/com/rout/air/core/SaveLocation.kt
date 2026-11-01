package com.rout.air.core

/**
 * Lokasi penyimpanan rekaman. Semua path berada di MediaStore sehingga file
 * langsung terlihat di galeri/aplikasi file tanpa izin storage.
 */
enum class SaveLocation(val relativePath: String) {
    MoviesRoutAir("Movies/RoutAir"),
    Movies("Movies"),
    DcimRoutAir("DCIM/RoutAir"),
    DcimCamera("DCIM/Camera");

    companion object {
        fun fromName(name: String?): SaveLocation =
            entries.firstOrNull { it.name == name } ?: MoviesRoutAir
    }
}
