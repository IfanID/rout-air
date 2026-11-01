package com.rout.air.live

import android.content.Context
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

/** Kode device flow yang ditampilkan ke pengguna. */
data class DeviceLogin(
    val deviceCode: String,
    val userCode: String,
    val verificationUrl: String,
    val intervalSec: Int,
    val expiresAtMs: Long,
)

/** Hasil satu kali polling token. */
sealed interface TokenPoll {
    /** Pengguna belum menyetujui di browser. */
    data object Pending : TokenPoll

    /** Google minta jeda polling diperpanjang. */
    data object SlowDown : TokenPoll

    data class Success(val tokens: GoogleTokens) : TokenPoll

    data class Failure(val message: String) : TokenPoll
}

/**
 * Login Google memakai OAuth 2.0 Device Flow.
 *
 * Dipilih karena tidak butuh redirect URI, tidak butuh SHA-1, dan tidak
 * terikat Play Services: kode ditampilkan di layar, pengguna mengetiknya
 * di google.com/device.
 */
object GoogleAuth {
    /**
     * Scope "youtube" (bukan force-ssl) karena device flow menolak
     * force-ssl dengan error invalid_scope. Scope ini sudah cukup untuk
     * membuat siaran, mengikat stream, dan membaca chat.
     */
    const val SCOPE = "https://www.googleapis.com/auth/youtube openid email"

    private const val DEVICE_CODE_URL = "https://oauth2.googleapis.com/device/code"
    private const val TOKEN_URL = "https://oauth2.googleapis.com/token"

    fun requestDeviceCode(clientId: String, clientSecret: String): DeviceLogin {
        val body = mutableMapOf(
            "client_id" to clientId,
            "scope" to SCOPE,
        )
        if (clientSecret.isNotBlank()) body["client_secret"] = clientSecret
        val response = postForm(DEVICE_CODE_URL, body)
        return DeviceLogin(
            deviceCode = response.getString("device_code"),
            userCode = response.getString("user_code"),
            verificationUrl = response.optString(
                "verification_url",
                "https://www.google.com/device",
            ),
            intervalSec = response.optInt("interval", 5).coerceAtLeast(5),
            expiresAtMs = System.currentTimeMillis() +
                response.optInt("expires_in", 1800) * 1000L,
        )
    }

    fun pollToken(clientId: String, clientSecret: String, deviceCode: String): TokenPoll {
        val body = mutableMapOf(
            "client_id" to clientId,
            "device_code" to deviceCode,
            "grant_type" to "urn:ietf:params:oauth:grant-type:device_code",
        )
        if (clientSecret.isNotBlank()) body["client_secret"] = clientSecret
        val result = postFormOrError(TOKEN_URL, body)
        result.error?.let { error ->
            return when (error) {
                "authorization_pending" -> TokenPoll.Pending
                "slow_down" -> TokenPoll.SlowDown
                "access_denied" -> TokenPoll.Failure(
                    "Akun ini belum terdaftar sebagai penguji. Tambahkan email Anda di " +
                        "Google Cloud Console > Audience > Test users, lalu coba lagi.",
                )
                "expired_token" -> TokenPoll.Failure("Kode kedaluwarsa, ulangi login")
                else -> TokenPoll.Failure(error)
            }
        }
        return TokenPoll.Success(parseTokens(result.body ?: JSONObject()))
    }

    /** Menukar refresh token menjadi access token baru. */
    fun refresh(clientId: String, clientSecret: String, refreshToken: String): GoogleTokens? {
        val body = mutableMapOf(
            "client_id" to clientId,
            "refresh_token" to refreshToken,
            "grant_type" to "refresh_token",
        )
        if (clientSecret.isNotBlank()) body["client_secret"] = clientSecret
        val result = postFormOrError(TOKEN_URL, body)
        if (result.error != null || result.body == null) return null
        val json = result.body
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

    private fun parseTokens(json: JSONObject): GoogleTokens = GoogleTokens(
        accessToken = json.optString("access_token"),
        refreshToken = json.optString("refresh_token"),
        expiresAtMs = System.currentTimeMillis() + json.optInt("expires_in", 3600) * 1000L,
    )

    private class FormResult(val body: JSONObject?, val error: String?)

    private fun postForm(url: String, form: Map<String, String>): JSONObject {
        val result = postFormOrError(url, form)
        return result.body
            ?: throw IllegalStateException(result.error ?: "Respons kosong dari Google")
    }

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

/** Menyimpan Client ID, Client Secret, dan token hasil login. */
class GoogleAuthStore(context: Context) {
    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    var clientId: String
        get() = prefs.getString(KEY_CLIENT_ID, "").orEmpty().ifBlank { DEFAULT_CLIENT_ID }
        set(value) = prefs.edit().putString(KEY_CLIENT_ID, value.trim()).apply()

    var clientSecret: String
        get() = prefs.getString(KEY_CLIENT_SECRET, "").orEmpty().ifBlank { DEFAULT_CLIENT_SECRET }
        set(value) = prefs.edit().putString(KEY_CLIENT_SECRET, value.trim()).apply()

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
        prefs.edit()
            .putString(KEY_ACCESS, tokens.accessToken)
            .putString(KEY_REFRESH, tokens.refreshToken)
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
        val id = clientId
        if (id.isBlank()) return null
        val renewed = GoogleAuth.refresh(id, clientSecret, tokens.refreshToken)
            ?: return tokens.accessToken.takeIf { it.isNotBlank() }
        saveTokens(renewed)
        return renewed.accessToken
    }

    private companion object {
        const val PREFS = "routair_google"

        /**
         * Client OAuth bawaan aplikasi. Nilainya diisi lewat local.properties
         * (routair.googleClientId / googleClientSecret) supaya tidak ikut
         * tersimpan di repositori.
         */
        val DEFAULT_CLIENT_ID: String = com.rout.air.BuildConfig.GOOGLE_CLIENT_ID
        val DEFAULT_CLIENT_SECRET: String = com.rout.air.BuildConfig.GOOGLE_CLIENT_SECRET

        const val KEY_CLIENT_ID = "client_id"
        const val KEY_CLIENT_SECRET = "client_secret"
        const val KEY_ACCESS = "access_token"
        const val KEY_REFRESH = "refresh_token"
        const val KEY_EXPIRES = "expires_at"
        const val KEY_EMAIL = "account_email"
        const val KEY_SCOPE_VERSION = "scope_version"

        /** Naikkan bila [GoogleAuth.SCOPE] berubah agar pengguna login ulang. */
        const val SCOPE_VERSION = 3
    }
}
