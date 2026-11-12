package com.cloudplay.bridge.core

import android.content.Context
import android.net.Uri
import android.util.Base64
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.security.MessageDigest
import java.security.SecureRandom

class NotSignedInException : IOException("Not signed in to OneDrive")

class AuthManager(context: Context, private val settings: Settings) {
    private val store = SecureStore(context)
    private val http = OkHttpClient()
    private val random = SecureRandom()

    private val _signedIn = MutableStateFlow(store.get(KEY_REFRESH) != null)
    val signedIn: StateFlow<Boolean> = _signedIn

    fun buildSignInUri(): Uri {
        val verifier = randomString(64)
        val state = randomString(24)
        store.put(KEY_VERIFIER, verifier)
        store.put(KEY_STATE, state)
        val challenge = base64Url(
            MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII))
        )
        return Uri.parse("$AUTHORITY/authorize").buildUpon()
            .appendQueryParameter("client_id", settings.clientId)
            .appendQueryParameter("response_type", "code")
            .appendQueryParameter("redirect_uri", REDIRECT_URI)
            .appendQueryParameter("response_mode", "query")
            .appendQueryParameter("scope", SCOPES)
            .appendQueryParameter("code_challenge", challenge)
            .appendQueryParameter("code_challenge_method", "S256")
            .appendQueryParameter("state", state)
            .appendQueryParameter("prompt", "select_account")
            .build()
    }

    fun handleRedirect(uri: Uri) {
        uri.getQueryParameter("error")?.let { error ->
            throw IOException(uri.getQueryParameter("error_description") ?: error)
        }
        val expectedState = store.get(KEY_STATE)
        if (expectedState == null || uri.getQueryParameter("state") != expectedState) {
            throw IOException("Sign-in state mismatch. Try again.")
        }
        val code = uri.getQueryParameter("code") ?: throw IOException("No sign-in code returned.")
        val verifier = store.get(KEY_VERIFIER) ?: throw IOException("Sign-in expired. Try again.")
        store.put(KEY_STATE, null)
        store.put(KEY_VERIFIER, null)
        tokenRequest(
            FormBody.Builder()
                .add("client_id", settings.clientId)
                .add("grant_type", "authorization_code")
                .add("code", code)
                .add("redirect_uri", REDIRECT_URI)
                .add("code_verifier", verifier)
                .add("scope", SCOPES)
                .build()
        )
    }

    @Synchronized
    fun accessToken(): String {
        val token = store.get(KEY_ACCESS)
        val expiresAt = store.get(KEY_EXPIRES)?.toLongOrNull() ?: 0L
        if (token != null && System.currentTimeMillis() < expiresAt - 60_000) return token
        val refresh = store.get(KEY_REFRESH) ?: throw NotSignedInException()
        tokenRequest(
            FormBody.Builder()
                .add("client_id", settings.clientId)
                .add("grant_type", "refresh_token")
                .add("refresh_token", refresh)
                .add("scope", SCOPES)
                .build()
        )
        return store.get(KEY_ACCESS) ?: throw NotSignedInException()
    }

    @Synchronized
    fun invalidateAccessToken() = store.put(KEY_EXPIRES, "0")

    @Synchronized
    fun signOut() {
        store.clear()
        _signedIn.value = false
    }

    private fun tokenRequest(form: FormBody) {
        val request = Request.Builder().url("$AUTHORITY/token").post(form).build()
        http.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            val json = runCatching { JSONObject(body) }.getOrNull()
            if (!response.isSuccessful || json == null || !json.has("access_token")) {
                val error = json?.optString("error").orEmpty()
                if (error == "invalid_grant") signOut()
                throw IOException(
                    json?.optString("error_description")?.takeIf { it.isNotBlank() }
                        ?: "Sign-in failed (HTTP ${response.code})"
                )
            }
            store.put(KEY_ACCESS, json.getString("access_token"))
            json.optString("refresh_token").takeIf { it.isNotBlank() }?.let { store.put(KEY_REFRESH, it) }
            val expiresIn = json.optLong("expires_in", 3600L)
            store.put(KEY_EXPIRES, (System.currentTimeMillis() + expiresIn * 1000).toString())
            _signedIn.value = store.get(KEY_REFRESH) != null
        }
    }

    private fun randomString(bytes: Int): String =
        base64Url(ByteArray(bytes).also { random.nextBytes(it) })

    private fun base64Url(data: ByteArray): String =
        Base64.encodeToString(data, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)

    companion object {
        const val AUTHORITY = "https://login.microsoftonline.com/consumers/oauth2/v2.0"
        const val REDIRECT_URI = "cloudplay://auth"
        const val SCOPES = "Files.Read offline_access"

        private const val KEY_ACCESS = "access_token"
        private const val KEY_REFRESH = "refresh_token"
        private const val KEY_EXPIRES = "expires_at"
        private const val KEY_VERIFIER = "pkce_verifier"
        private const val KEY_STATE = "pkce_state"
    }
}
