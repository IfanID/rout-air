package com.rout.air.live

import android.content.Context
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CredentialManager
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.common.api.Scope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import kotlin.coroutines.resume

/** Token OAuth yang disimpan agar pengguna tidak perlu login ulang. */
data class GoogleTokens(
    val accessToken: String,
    val expiresAtMs: Long,
) {
    /** Token dianggap perlu diperbarui 1 menit sebelum kedaluwarsa. */
    fun fresh(nowMs: Long = System.currentTimeMillis()): Boolean =
        accessToken.isNotBlank() && nowMs < expiresAtMs - 60_000L
}

/**
 * Login Google memakai Google Identity Services `AuthorizationClient`.
 *
 * Tidak ada redirect URI, custom scheme, maupun client secret. Google Play
 * Services menyimpan persetujuan pengguna, lalu memberi access token baru
 * tanpa UI selama izin belum dicabut.
 */
object GoogleAuth {

    /** Scope "youtube" cukup untuk membuat siaran, mengikat stream, dan chat. */
    const val YOUTUBE_SCOPE = "https://www.googleapis.com/auth/youtube"

    private const val USERINFO_URL = "https://www.googleapis.com/oauth2/v3/userinfo"

    /** Access token Google berlaku sekitar satu jam; simpan dengan margin. */
    private const val TOKEN_LIFETIME_MS = 55 * 60 * 1000L

    fun scopes(): List<Scope> = listOf(
        Scope(YOUTUBE_SCOPE),
        Scope("openid"),
        Scope("email"),
    )

    fun authorizationRequest(): AuthorizationRequest =
        AuthorizationRequest.builder()
            .setRequestedScopes(scopes())
            .build()

    /** False bila perangkat tidak punya Google Play Services. */
    fun available(context: Context): Boolean =
        GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(context) ==
            ConnectionResult.SUCCESS

    fun expiryFrom(nowMs: Long = System.currentTimeMillis()): Long = nowMs + TOKEN_LIFETIME_MS

    /**
     * Meminta token tanpa UI. Mengembalikan null bila Play Services masih
     * memerlukan persetujuan pengguna.
     */
    suspend fun silentToken(context: Context): String? = withContext(Dispatchers.Main) {
        suspendCancellableCoroutine { continuation ->
            Identity.getAuthorizationClient(context)
                .authorize(authorizationRequest())
                .addOnSuccessListener { result ->
                    val token = if (result.hasResolution()) null else result.accessToken
                    if (continuation.isActive) continuation.resume(token)
                }
                .addOnFailureListener {
                    if (continuation.isActive) continuation.resume(null)
                }
        }
    }

    /**
     * Melepas akun default aplikasi agar pemilih akun muncul lagi.
     * Izin yang sudah pernah diberikan TIDAK dicabut, jadi memilih akun
     * lama tidak perlu menyetujui ulang.
     */
    suspend fun switchAccount(context: Context): Boolean = withContext(Dispatchers.Main) {
        runCatching {
            CredentialManager.create(context)
                .clearCredentialState(ClearCredentialStateRequest())
        }.isSuccess
    }

    /** Email akun yang login, untuk ditampilkan di Setelan. */
    fun accountEmail(accessToken: String): String {
        val connection = URL(USERINFO_URL).openConnection() as HttpURLConnection
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
}

/**
 * Menyimpan access token hasil login. Tidak ada refresh token karena
 * token diperbarui lewat Google Play Services.
 */
class GoogleAuthStore(context: Context) {
    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    var accountEmail: String
        get() = prefs.getString(KEY_EMAIL, "").orEmpty()
        set(value) = prefs.edit().putString(KEY_EMAIL, value).apply()

    fun loadTokens(): GoogleTokens? {
        val access = prefs.getString(KEY_ACCESS, "").orEmpty()
        if (access.isBlank()) return null
        return GoogleTokens(
            accessToken = access,
            expiresAtMs = prefs.getLong(KEY_EXPIRES, 0L),
        )
    }

    fun saveTokens(tokens: GoogleTokens) {
        prefs.edit()
            .putString(KEY_ACCESS, tokens.accessToken)
            .putLong(KEY_EXPIRES, tokens.expiresAtMs)
            .apply()
    }

    fun clear() {
        prefs.edit()
            .remove(KEY_ACCESS)
            .remove(KEY_EXPIRES)
            .remove(KEY_EMAIL)
            .apply()
    }

    val loggedIn: Boolean
        get() = loadTokens() != null

    /** Token yang masih berlaku, atau null bila perlu minta ulang. */
    fun freshAccessToken(): String? {
        val tokens = loadTokens() ?: return null
        return tokens.accessToken.takeIf { tokens.fresh() }
    }

    private companion object {
        const val PREFS = "routair_google"
        const val KEY_ACCESS = "access_token"
        const val KEY_EXPIRES = "expires_at"
        const val KEY_EMAIL = "account_email"
    }
}
