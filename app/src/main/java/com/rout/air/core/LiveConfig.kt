package com.rout.air.core

/** Layanan live populer dengan server ingest default, seperti daftar OBS. */
enum class LiveService(
    val label: String,
    val defaultServer: String,
    val dashboardUrl: String = "",
    val custom: Boolean = false,
) {
    Custom("Custom", "", custom = true),
    YouTube(
        "YouTube - RTMP",
        "rtmp://a.rtmp.youtube.com/live2",
        "https://studio.youtube.com/",
    ),
    Twitch(
        "Twitch",
        "rtmp://live.twitch.tv/app",
        "https://dashboard.twitch.tv/settings/stream",
    ),
    Facebook(
        "Facebook Live",
        "rtmps://live-api-s.facebook.com:443/rtmp/",
        "https://www.facebook.com/live/producer",
    ),
    Kick(
        "Kick",
        "rtmps://fa723fc1b171.global-contribute.live-video.net:443/app/",
        "https://dashboard.kick.com/channel/stream",
    ),
    ;

    companion object {
        fun fromName(name: String?): LiveService =
            entries.firstOrNull { it.name == name } ?: Custom
    }
}

/** Konfigurasi tujuan live streaming RTMP, mengikuti halaman Stream OBS. */
data class LiveConfig(
    val service: LiveService = LiveService.Custom,
    val serverUrl: String = "",
    val streamKey: String = "",
    val useAuth: Boolean = false,
    val username: String = "",
    val password: String = "",
    val autoReconnect: Boolean = true,
    val audioTrack: Int = 1,
    /** True bila server dan kunci diisi lewat "Hubungkan Akun" seperti OBS. */
    val accountLinked: Boolean = false,
) {
    val isConfigured: Boolean
        get() = serverUrl.isNotBlank() && streamKey.isNotBlank()
}

enum class LiveStatus {
    Idle,
    Connecting,
    Live,
    Reconnecting,
    Error,
}

/** Statistik jaringan live yang ditampilkan di UI. */
data class LiveStats(
    val status: LiveStatus = LiveStatus.Idle,
    val bytesSent: Long = 0L,
    val tagsSent: Long = 0L,
    val droppedTags: Long = 0L,
    val bitrateKbps: Int = 0,
    val uptimeMs: Long = 0L,
    val reconnects: Int = 0,
    val lastError: String? = null,
) {
    val active: Boolean
        get() = status == LiveStatus.Live ||
            status == LiveStatus.Connecting ||
            status == LiveStatus.Reconnecting
}
