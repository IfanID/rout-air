package com.rout.air.live

import android.net.Uri
import com.rout.air.BuildConfig
import net.openid.appauth.AuthorizationRequest
import net.openid.appauth.AuthorizationServiceConfiguration
import net.openid.appauth.ResponseTypeValues
import net.openid.appauth.TokenResponse
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** Token OAuth yang disimpan agar pengguna hanya login sekali. */
data class GoogleTokens(
    val accessToken: String,
    val refreshToken: String,
    val expiresAtMs: Long,
) {
    /** Token dianggap perlu diperbarui 1 menit sebelum kedaluwarsa. */
    fun fresh(nowMs: Long = System.currentTimeMillis()): Boolean =
        accessToken.isNotBlank() && nowMs < expiresAtMs - 60_000L
}

/**
 * Login Google memakai OAuth 2.0 untuk aplikasi Android (Authorization Code
 * + PKCE) melalui Custom Tabs.
 *
 * Tipe client Android tidak punya client secret; keamanan dijaga oleh
 * package name, SHA-1 sertifikat, dan PKCE. Redirect URI memakai skema
 * terbalik dari client ID, sama seperti AppAuth.
 */
object GoogleAuth {

    /** Scope "youtube" cukup untuk membuat siaran, mengikat stream, dan chat. */
    const val SCOPE = "https://www.googleapis.com/auth/youtube openid email"

    private const val AUTH_URL = "https://accounts.google.com/o/oauth2/v2/auth"
    private const val TOKEN_URL = "https://oauth2.googleapis.com/token"

    /** Client ID Android sesuai build type; kosong bila build tanpa kredensial. */
    val clientId: String get() = BuildConfig.GOOGLE_ANDROID_CLIENT_ID

    val configured: Boolean get() = clientId.isNotBlank()

    /** Skema redirect: client ID dibalik tanpa akhiran .apps.googleusercontent.com. */
    fun redirectScheme(clientId: String = this.clientId): String =
        if (clientId.isBlank()) {
            "com.rout.air"
        } else {
            "com.googleusercontent.apps." + clientId.removeSuffix(".apps.googleusercontent.com")
        }

    fun redirectUri(): Uri = Uri.parse("${redirectScheme()}:/oauth2redirect")

    fun authorizationRequest(): AuthorizationRequest =
        AuthorizationRequest.Builder(
            AuthorizationServiceConfiguration(
                Uri.parse(AUTH_URL),
                Uri.parse(TOKEN_URL),
                null,
            ),
            clientId,
            ResponseTypeValues.CODE,
            redirectUri(),
        )
            .setScope(SCOPE)
            // prompt didukung langsung oleh builder; access_type tetap
            // lewat parameter tambahan karena tidak ada method khusus.
            .setPrompt("consent")
            .setAdditionalParameters(mapOf("access_type" to "offline"))
            .build()

    /** Mengubah respons token AppAuth menjadi model penyimpanan aplikasi. */
    fun toTokens(response: TokenResponse): GoogleTokens {
        val now = System.currentTimeMillis()
        val expiration = response.accessTokenExpirationTime ?: 0L
        return GoogleTokens(
            accessToken = response.accessToken.orEmpty(),
            refreshToken = response.refreshToken.orEmpty(),
            // AppAuth memberi waktu kedaluwarsa absolut; samakan satuannya.
            expiresAtMs = if (expiration > now) expiration else now + 3_600_000L,
        )
    }

    /** Menukar refresh token menjadi access token baru (tanpa secret). */
    fun refresh(clientId: String, refreshToken: String): GoogleTokens? {
        if (clientId.isBlank() || refreshToken.isBlank()) return null
        val result = postFormOrError(
            TOKEN_URL,
            mapOf(
                "client_id" to clientId,
                "refresh_token" to refreshToken,
                "grant_type" to "refresh_token",
            ),
        )
        val json = result.body ?: return null
        return GoogleTokens(
            accessToken = json.optString("access_token"),
            refreshToken = json.optString("refresh_token", refreshToken),
            expiresAtMs = System.currentTimeMillis() +
                json.optInt("expires_in", 3600) * 1000L,
        )
    }

    /** Email akun yang login, untuk ditampilkan di Setelan. */
    fun accountEmail(accessToken: String): String {
        val connection = URL("https://www.googleapis.com/oauth2/v3/userinfo")
            .openConnection() as HttpURLConnection
        return try {
            connection.connectTimeout = 15_000
            connection.readTimeout = 15_000
            connection.setRequestProperty("Authorization", "Bearer $accessToken")
            val text = connection.inputStream.bufferedReader().use { it.readText() }
            runCatching { JSONObject(text).optString("email") }.getOrDefault("")
        } catch (_: Exception) {
            ""
        } finally {
            connection.disconnect()
        }
    }

    /** Pesan kesalahan OAuth yang ramah, mis. akun belum jadi test user. */
    fun errorMessage(code: String?): String = when (code) {
        "access_denied" ->
            "Akses ditolak. Tambahkan email Anda di Google Cloud Console > " +
                "Audience > Test users, lalu coba lagi."

        "invalid_client" ->
            "Client ID tidak cocok dengan sertifikat APK ini. Periksa SHA-1 di Google Cloud."

        "invalid_grant" -> "Sesi login kedaluwarsa. Coba hubungkan ulang."
        null, "" -> "Login dibatalkan"
        else -> "Login gagal: $code"
    }

    private class FormResult(val body: JSONObject?, val error: String?)

    private fun postFormOrError(url: String, form: Map<String, String>): FormResult {
        val payload = form.entries.joinToString("&") { (key, value) ->
            "${URLEncoder.encode(key, "UTF-8")}=${URLEncoder.encode(value, "UTF-8")}"
        }
        val connection = URL(url).openConnection() as HttpURLConnection
        return try {
            connection.requestMethod = "POST"
            connection.connectTimeout = 15_000
            connection.readTimeout = 20_000
            connection.doOutput = true
            connection.setRequestProperty(
                "Content-Type",
                "application/x-www-form-urlencoded",
            )
            connection.outputStream.use { it.write(payload.toByteArray()) }
            val code = connection.responseCode
            val text = (if (code in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader()
                ?.use { it.readText() }
                .orEmpty()
            val json = runCatching { JSONObject(text) }.getOrNull()
            if (code in 200..299) {
                FormResult(json, null)
            } else {
                FormResult(null, json?.optString("error")?.ifBlank { null } ?: "HTTP $code")
            }
        } finally {
            connection.disconnect()
        }
    }
}

/**
 * Menyimpan token hasil login. Client ID tidak lagi disimpan di sini karena
 * nilainya sudah ditanam per build type lewat BuildConfig.
 */
class GoogleAuthStore(context: android.content.Context) {
    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)

    var accountEmail: String
        get() = prefs.getString(KEY_EMAIL, "").orEmpty()
        set(value) = prefs.edit().putString(KEY_EMAIL, value).apply()

    fun loadTokens(): GoogleTokens? {
        // Token lama dengan scope berbeda harus login ulang.
        if (prefs.getInt(KEY_SCOPE_VERSION, 0) < SCOPE_VERSION) return null
        val access = prefs.getString(KEY_ACCESS, "").orEmpty()
        if (access.isBlank()) return null
        return GoogleTokens(
            accessToken = access,
            refreshToken = prefs.getString(KEY_REFRESH, "").orEmpty(),
            expiresAtMs = prefs.getLong(KEY_EXPIRES, 0L),
        )
    }

    fun saveTokens(tokens: GoogleTokens) {
        // Respons token tidak selalu berisi refresh token baru; pertahankan
        // yang lama supaya sesi tidak terputus.
        val refresh = tokens.refreshToken.ifBlank {
            prefs.getString(KEY_REFRESH, "").orEmpty()
        }
        prefs.edit()
            .putString(KEY_ACCESS, tokens.accessToken)
            .putString(KEY_REFRESH, refresh)
            .putLong(KEY_EXPIRES, tokens.expiresAtMs)
            .putInt(KEY_SCOPE_VERSION, SCOPE_VERSION)
            .apply()
    }

    fun clear() {
        prefs.edit()
            .remove(KEY_ACCESS)
            .remove(KEY_REFRESH)
            .remove(KEY_EXPIRES)
            .remove(KEY_EMAIL)
            .apply()
    }

    val loggedIn: Boolean
        get() = loadTokens() != null

    /** Access token yang sudah diperbarui bila hampir kedaluwarsa. */
    fun freshAccessToken(): String? {
        val tokens = loadTokens() ?: return null
        if (tokens.fresh()) return tokens.accessToken
        val renewed = GoogleAuth.refresh(GoogleAuth.clientId, tokens.refreshToken)
            ?: return tokens.accessToken.takeIf { it.isNotBlank() }
        saveTokens(renewed)
        return renewed.accessToken
    }

    private companion object {
        const val PREFS = "routair_google"
        const val KEY_ACCESS = "access_token"
        const val KEY_REFRESH = "refresh_token"
        const val KEY_EXPIRES = "expires_at"
        const val KEY_EMAIL = "account_email"
        const val KEY_SCOPE_VERSION = "scope_version"

        /** Naikkan bila [GoogleAuth.SCOPE] berubah agar pengguna login ulang. */
        const val SCOPE_VERSION = 4
    }
}
