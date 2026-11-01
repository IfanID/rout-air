package com.rout.air.live

import android.content.Context
import com.rout.air.data.DiagLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** Satu pesan chat yang sudah siap ditampilkan. */
data class ChatMessage(val author: String, val text: String)

/** Data chat + penonton yang dipakai overlay Live. */
data class ChatState(
    val status: String = "",
    val live: Boolean = false,
    /** -1 berarti belum diketahui. */
    val viewers: Int = -1,
    val messages: List<ChatMessage> = emptyList(),
)

/** Siaran aktif milik akun yang login. */
data class BroadcastInfo(val videoId: String, val chatId: String)

/** Halaman pesan chat dari YouTube. */
data class ChatPage(
    val messages: List<ChatMessage>,
    val nextPageToken: String?,
    val intervalMs: Long,
)

/** Server dan kunci stream hasil "Hubungkan Akun", seperti OBS. */
data class StreamTarget(
    val streamId: String,
    val server: String,
    val key: String,
    val name: String,
)

/** Membaca `liveStreams.list` untuk mengambil server + stream key. */
internal fun parseStreamTarget(json: JSONObject): StreamTarget? {
    val item = json.optJSONArray("items")?.optJSONObject(0) ?: return null
    val info = item.optJSONObject("cdn")?.optJSONObject("ingestionInfo") ?: return null
    val server = info.optString("ingestionAddress")
    val key = info.optString("streamName")
    if (server.isBlank() || key.isBlank()) return null
    return StreamTarget(
        streamId = item.optString("id"),
        server = server,
        key = key,
        name = item.optJSONObject("snippet")?.optString("title").orEmpty(),
    )
}

/** Membaca nama channel dari `channels.list`. */
internal fun parseChannelTitle(json: JSONObject): String =
    json.optJSONArray("items")?.optJSONObject(0)
        ?.optJSONObject("snippet")
        ?.optString("title")
        .orEmpty()

/**
 * Membaca respons `liveBroadcasts.list` milik sendiri.
 * Dipisah dari jaringan agar bisa diuji tanpa koneksi.
 */
internal fun parseActiveBroadcast(json: JSONObject): BroadcastInfo? {
    val items = json.optJSONArray("items") ?: return null
    // liveBroadcasts.list tidak boleh memakai "mine" bersama "broadcastStatus",
    // jadi status disaring di sini.
    var fallback: BroadcastInfo? = null
    for (i in 0 until items.length()) {
        val item = items.optJSONObject(i) ?: continue
        val chatId = item.optJSONObject("snippet")?.optString("liveChatId").orEmpty()
        val videoId = item.optString("id")
        if (chatId.isBlank() || videoId.isBlank()) continue
        val info = BroadcastInfo(videoId, chatId)
        val cycle = item.optJSONObject("status")?.optString("lifeCycleStatus").orEmpty()
        if (cycle == "live") return info
        if (fallback == null) fallback = info
    }
    return fallback
}

/** Membaca respons `liveChatMessages.list`. */
internal fun parseChatPage(json: JSONObject): ChatPage {
    val messages = mutableListOf<ChatMessage>()
    val items = json.optJSONArray("items")
    for (i in 0 until (items?.length() ?: 0)) {
        val item = items?.optJSONObject(i) ?: continue
        val text = item.optJSONObject("snippet")?.optString("displayMessage").orEmpty()
        if (text.isBlank()) continue
        val author = item.optJSONObject("authorDetails")
            ?.optString("displayName")
            ?.takeIf { it.isNotBlank() }
            ?: "Penonton"
        messages += ChatMessage(author, text)
    }
    return ChatPage(
        messages = messages,
        nextPageToken = json.optString("nextPageToken").takeIf { it.isNotBlank() },
        intervalMs = json.optLong("pollingIntervalMillis", 5_000L),
    )
}

/** Klien REST minimal untuk YouTube Data API v3. */
class YouTubeApi(private val accessToken: String) {

    /** Mencari siaran yang sedang aktif di channel sendiri. */
    fun activeBroadcast(): BroadcastInfo? {
        val json = get(
            "https://www.googleapis.com/youtube/v3/liveBroadcasts",
            mapOf(
                "part" to "snippet,status",
                "mine" to "true",
                "maxResults" to "5",
            ),
        ) ?: return null
        return parseActiveBroadcast(json)
    }

    /** Jumlah penonton bersamaan; hanya tersedia untuk pemilik siaran. */
    fun viewers(videoId: String): Int? {
        val json = get(
            "https://www.googleapis.com/youtube/v3/videos",
            mapOf("part" to "liveStreamingDetails", "id" to videoId),
        ) ?: return null
        val details = json.optJSONArray("items")
            ?.optJSONObject(0)
            ?.optJSONObject("liveStreamingDetails")
            ?: return null
        return details.optString("concurrentViewers").toIntOrNull()
    }

    /** Server + kunci stream milik akun, dipakai tombol "Hubungkan Akun". */
    fun streamTarget(): StreamTarget? {
        val json = get(
            "https://www.googleapis.com/youtube/v3/liveStreams",
            mapOf("part" to "cdn,snippet", "mine" to "true"),
        ) ?: return null
        return parseStreamTarget(json)
    }

    /**
     * Membuat siaran baru dengan judul dan deskripsi, seperti tombol
     * "Start Streaming" OBS yang membuat broadcast lebih dulu.
     */
    fun createBroadcast(title: String, description: String, privacy: String): String? {
        val body = JSONObject().apply {
            put("snippet", JSONObject().apply {
                put("title", title)
                put("description", description)
                put("scheduledStartTime", java.time.Instant.now().toString())
            })
            put("status", JSONObject().apply {
                put("privacyStatus", privacy)
                put("selfDeclaredMadeForKids", false)
            })
            put("contentDetails", JSONObject().apply {
                // YouTube otomatis memulai siaran saat data RTMP masuk.
                put("enableAutoStart", true)
                put("enableAutoStop", true)
                put("enableDvr", true)
                put("monitorStream", JSONObject().put("enableMonitorStream", false))
            })
        }
        val json = post(
            "https://www.googleapis.com/youtube/v3/liveBroadcasts?part=snippet,status,contentDetails",
            body,
        ) ?: return null
        val id = json.optString("id")
        DiagLog.line("youtube: broadcast dibuat id=$id")
        return id.takeIf { it.isNotBlank() }
    }

    /** Menyambungkan broadcast ke stream milik akun. */
    fun bindBroadcast(broadcastId: String, streamId: String): Boolean {
        val url = "https://www.googleapis.com/youtube/v3/liveBroadcasts/bind" +
            "?id=$broadcastId&streamId=$streamId&part=id"
        val json = post(url, JSONObject()) ?: run {
            DiagLog.line("youtube: bind gagal broadcast=$broadcastId stream=$streamId")
            return false
        }
        val ok = json.optString("id").isNotBlank()
        DiagLog.line("youtube: bind ok=$ok broadcast=$broadcastId stream=$streamId")
        return ok
    }

    /** Nama channel untuk ditampilkan setelah terhubung. */
    fun channelTitle(): String {
        val json = get(
            "https://www.googleapis.com/youtube/v3/channels",
            mapOf("part" to "snippet", "mine" to "true"),
        ) ?: return ""
        return parseChannelTitle(json)
    }

    /** Mengambil pesan chat berikutnya; token null berarti mulai dari awal. */
    fun chat(chatId: String, pageToken: String?): ChatPage? {
        val params = linkedMapOf(
            "liveChatId" to chatId,
            "part" to "snippet,authorDetails",
            "maxResults" to "50",
        )
        if (!pageToken.isNullOrBlank()) params["pageToken"] = pageToken
        val json = get("https://www.googleapis.com/youtube/v3/liveChat/messages", params)
            ?: return null
        return parseChatPage(json)
    }

    private fun post(url: String, body: JSONObject): JSONObject? {
        val connection = URL(url).openConnection() as HttpURLConnection
        return try {
            connection.requestMethod = "POST"
            connection.connectTimeout = 20_000
            connection.readTimeout = 30_000
            connection.doOutput = true
            connection.setRequestProperty("Authorization", "Bearer $accessToken")
            connection.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
            connection.outputStream.use { it.write(body.toString().toByteArray()) }
            val code = connection.responseCode
            val text = (if (code in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader()
                ?.use { it.readText() }
                .orEmpty()
            if (code in 200..299) {
                runCatching { JSONObject(text) }.getOrNull()
            } else {
                DiagLog.line("youtube: POST HTTP $code ${text.take(200)}")
                null
            }
        } catch (error: Exception) {
            DiagLog.line("youtube: POST ${error.message}")
            null
        } finally {
            connection.disconnect()
        }
    }

    private fun get(url: String, params: Map<String, String>): JSONObject? {
        val query = params.entries.joinToString("&") { (key, value) ->
            "${URLEncoder.encode(key, "UTF-8")}=${URLEncoder.encode(value, "UTF-8")}"
        }
        val connection = URL("$url?$query").openConnection() as HttpURLConnection
        return try {
            connection.requestMethod = "GET"
            connection.connectTimeout = 15_000
            connection.readTimeout = 20_000
            connection.setRequestProperty("Authorization", "Bearer $accessToken")
            val code = connection.responseCode
            val text = (if (code in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader()
                ?.use { it.readText() }
                .orEmpty()
            if (code in 200..299) {
                runCatching { JSONObject(text) }.getOrNull()
            } else {
                DiagLog.line("youtube: HTTP $code ${text.take(160)}")
                null
            }
        } catch (error: Exception) {
            DiagLog.line("youtube: ${error.message}")
            null
        } finally {
            connection.disconnect()
        }
    }
}

/**
 * Polling chat + penonton selama overlay Live atau pratinjau live aktif.
 *
 * Hanya berjalan bila pengguna sudah login, supaya tidak memakai kuota
 * dan baterai saat fitur tidak dipakai.
 */
object ChatMonitor {
    val state = MutableStateFlow(ChatState())

    private var scope: CoroutineScope? = null
    private var job: Job? = null

    fun start(context: Context) {
        if (job != null) return
        val app = context.applicationContext
        val freshScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope = freshScope
        job = freshScope.launch { loop(app) }
    }

    fun stop() {
        job?.cancel()
        job = null
        scope?.cancel()
        scope = null
    }

    private suspend fun loop(context: Context) {
        val store = GoogleAuthStore(context)
        var info: BroadcastInfo? = null
        var pageToken: String? = null
        var nextBroadcastAt = 0L
        var nextViewerAt = 0L
        var failures = 0
        while (currentCoroutineContext().isActive) {
            if (store.clientId.isBlank()) {
                publish(ChatState(status = "Client ID Google belum diisi"))
                delay(30_000)
                continue
            }
            val token = store.freshAccessToken()
            if (token.isNullOrBlank()) {
                publish(ChatState(status = "Belum login Google"))
                delay(30_000)
                continue
            }
            val api = YouTubeApi(token)
            val now = System.currentTimeMillis()
            if (info == null || now >= nextBroadcastAt) {
                info = api.activeBroadcast()
                nextBroadcastAt = System.currentTimeMillis() + 60_000
                pageToken = null
            }
            val current = info
            if (current == null) {
                state.update { it.copy(status = "Menunggu siaran aktif", live = false, viewers = -1) }
                delay(15_000)
                continue
            }
            if (now >= nextViewerAt) {
                nextViewerAt = System.currentTimeMillis() + 30_000
                api.viewers(current.videoId)?.let { count ->
                    state.update { it.copy(viewers = count) }
                }
            }
            val page = api.chat(current.chatId, pageToken)
            if (page == null) {
                failures++
                if (failures >= 3) {
                    // Siaran mungkin sudah berakhir; cari ulang nanti.
                    info = null
                    failures = 0
                }
                state.update { it.copy(status = "Gagal memuat chat") }
                delay(10_000)
                continue
            }
            failures = 0
            pageToken = page.nextPageToken
            state.update {
                it.copy(
                    status = "Terhubung",
                    live = true,
                    messages = (it.messages + page.messages).takeLast(MAX_MESSAGES),
                )
            }
            delay(page.intervalMs.coerceIn(2_000L, 10_000L))
        }
    }

    private fun publish(value: ChatState) {
        state.update { it.copy(status = value.status) }
    }

    private const val MAX_MESSAGES = 60
}
