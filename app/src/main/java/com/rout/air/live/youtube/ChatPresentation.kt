package com.rout.air.live.youtube

/** Peran penonton pada chat YouTube. */
enum class ChatRole { Owner, Moderator, Member, Viewer }

/** Satu pesan chat yang sudah siap ditampilkan. */
data class ChatMessage(
    /** Id pesan dari YouTube; dipakai agar bubble tidak tampil dua kali. */
    val id: String,
    val author: String,
    val text: String,
    val role: ChatRole = ChatRole.Viewer,
    val verified: Boolean = false,
)

/** Satu bubble chat yang sedang tampil di overlay Live. */
data class ChatBubble(
    val id: String,
    val author: String,
    val text: String,
    val role: ChatRole,
    val verified: Boolean,
    /** true saat bubble sedang dianimasikan keluar. */
    val leaving: Boolean = false,
)

/** Lencana emoji peran; penonton biasa tidak memakai lencana. */
fun ChatRole.emoji(): String = when (this) {
    ChatRole.Owner -> "👑"
    ChatRole.Moderator -> "🛡"
    ChatRole.Member -> "⭐"
    ChatRole.Viewer -> ""
}

/** Warna nama mengikuti tema RoutAir (ARGB). */
fun ChatRole.nameColor(): Int = when (this) {
    ChatRole.Owner -> 0xFF00E5FF.toInt()
    ChatRole.Moderator -> 0xFF4CD964.toInt()
    ChatRole.Member -> 0xFF4A9EFF.toInt()
    ChatRole.Viewer -> 0xFFFFFFFF.toInt()
}

/** Warna lencana centang untuk akun terverifikasi. */
const val VERIFIED_COLOR: Int = 0xFF00B8D4.toInt()

/**
 * Lama bubble tampil: 3 detik untuk pesan pendek, bertambah 25 ms per
 * karakter sampai 6 detik agar pesan panjang masih sempat dibaca.
 */
fun bubbleDurationMs(text: String): Long = 3_000L + text.length.coerceAtMost(120) * 25L
