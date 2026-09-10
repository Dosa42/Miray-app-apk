package com.example.codex

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.TimeUnit

data class CodexOAuthSession(
    val accessToken: String,
    val refreshToken: String,
    val idToken: String,
    val accountId: String,
    val expiresAtEpochSeconds: Long
) {
    fun encodeForStorage(): String = JSONObject().apply {
        put("type", "codex_oauth")
        put("access_token", accessToken)
        put("refresh_token", refreshToken)
        put("id_token", idToken)
        put("account_id", accountId)
        put("expires_at", expiresAtEpochSeconds)
    }.toString()
}

/** ChatGPT OAuth + PKCE session manager for the Codex backend. No API-key fallback. */
object OpenAIOAuthManager {
    const val CLIENT_ID = "app_EMoamEEZ73f0CkXaXp7hrann"
    const val AUTH_ENDPOINT = "https://auth.openai.com/oauth/authorize"
    const val TOKEN_ENDPOINT = "https://auth.openai.com/oauth/token"
    const val REDIRECT_URI = "http://localhost:1455/auth/callback"

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private fun generateCodeVerifier(): String {
        val bytes = ByteArray(32)
        SecureRandom().nextBytes(bytes)
        return Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
    }

    private fun generateCodeChallenge(verifier: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII))
        return Base64.encodeToString(digest, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
    }

    suspend fun authenticate(context: Context): Result<CodexOAuthSession> = withContext(Dispatchers.IO) {
        var serverSocket: ServerSocket? = null
        try {
            val codeVerifier = generateCodeVerifier()
            val state = UUID.randomUUID().toString()
            val authUrl = Uri.parse(AUTH_ENDPOINT).buildUpon()
                .appendQueryParameter("response_type", "code")
                .appendQueryParameter("client_id", CLIENT_ID)
                .appendQueryParameter("redirect_uri", REDIRECT_URI)
                .appendQueryParameter(
                    "scope",
                    "openid profile email offline_access api.connectors.read api.connectors.invoke"
                )
                .appendQueryParameter("code_challenge", generateCodeChallenge(codeVerifier))
                .appendQueryParameter("code_challenge_method", "S256")
                .appendQueryParameter("id_token_add_organizations", "true")
                .appendQueryParameter("codex_cli_simplified_flow", "true")
                .appendQueryParameter("originator", "codex_cli_rs")
                .appendQueryParameter("state", state)
                .build()

            serverSocket = ServerSocket().apply {
                reuseAddress = true
                bind(InetSocketAddress("127.0.0.1", 1455))
                soTimeout = 180_000
            }

            context.startActivity(
                Intent(Intent.ACTION_VIEW, authUrl).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )

            val socket = serverSocket.accept()
            val reader = BufferedReader(InputStreamReader(socket.inputStream))
            val requestLine = reader.readLine().orEmpty()
            while (!reader.readLine().isNullOrEmpty()) Unit

            val requestTarget = requestLine.split(' ').getOrNull(1).orEmpty()
            val callbackUri = Uri.parse("http://localhost$requestTarget")
            val returnedState = callbackUri.getQueryParameter("state")
            val code = callbackUri.getQueryParameter("code")
            val oauthError = callbackUri.getQueryParameter("error_description")
                ?: callbackUri.getQueryParameter("error")

            val validState = returnedState == state
            val success = validState && !code.isNullOrBlank() && oauthError.isNullOrBlank()
            val responseHtml = if (success) {
                """<html><body style="font-family:sans-serif;background:#101827;color:#20e58b;text-align:center;padding-top:64px"><h2>Codex login gelukt</h2><p>Je kunt dit tabblad sluiten en teruggaan naar Termux Agent AI.</p></body></html>"""
            } else {
                """<html><body style="font-family:sans-serif;background:#101827;color:#ff6577;text-align:center;padding-top:64px"><h2>Codex login mislukt</h2><p>Ga terug naar de app en probeer opnieuw.</p></body></html>"""
            }
            val httpResponse = "HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\nConnection: close\r\nContent-Length: ${responseHtml.toByteArray().size}\r\n\r\n$responseHtml"
            socket.getOutputStream().use { output ->
                output.write(httpResponse.toByteArray())
                output.flush()
            }
            socket.close()
            serverSocket.close()

            when {
                !validState -> Result.failure(Exception("OAuth state mismatch; login was rejected for safety."))
                !oauthError.isNullOrBlank() -> Result.failure(Exception("Codex login rejected: $oauthError"))
                code.isNullOrBlank() -> Result.failure(Exception("No authorization code received."))
                else -> exchangeCodeForTokens(code, codeVerifier)
            }
        } catch (e: Exception) {
            try { serverSocket?.close() } catch (_: Exception) {}
            Result.failure(e)
        }
    }

    fun decodeStoredSession(stored: String): CodexOAuthSession? {
        return try {
            val obj = JSONObject(stored)
            if (obj.optString("type") != "codex_oauth") return null
            val accessToken = obj.optString("access_token")
            val idToken = obj.optString("id_token")
            val accountId = obj.optString("account_id").ifBlank {
                extractAccountId(idToken).orEmpty()
            }
            if (accessToken.isBlank() || accountId.isBlank()) return null
            CodexOAuthSession(
                accessToken = accessToken,
                refreshToken = obj.optString("refresh_token"),
                idToken = idToken,
                accountId = accountId,
                expiresAtEpochSeconds = obj.optLong("expires_at", jwtExpiry(accessToken) ?: 0L)
            )
        } catch (_: Exception) {
            null
        }
    }

    fun isLoggedIn(stored: String): Boolean = decodeStoredSession(stored) != null

    fun refreshIfNeeded(stored: String): Result<CodexOAuthSession> {
        val session = decodeStoredSession(stored)
            ?: return Result.failure(Exception("Log eerst in met ChatGPT OAuth voor Codex."))
        val now = System.currentTimeMillis() / 1000L
        if (session.expiresAtEpochSeconds == 0L || session.expiresAtEpochSeconds > now + 300L) {
            return Result.success(session)
        }
        if (session.refreshToken.isBlank()) {
            return Result.failure(Exception("De Codex-sessie is verlopen. Log opnieuw in."))
        }

        val body = FormBody.Builder()
            .add("grant_type", "refresh_token")
            .add("client_id", CLIENT_ID)
            .add("refresh_token", session.refreshToken)
            .build()
        return exchangeTokenRequest(body, previous = session)
    }

    private fun exchangeCodeForTokens(code: String, verifier: String): Result<CodexOAuthSession> {
        val body = FormBody.Builder()
            .add("grant_type", "authorization_code")
            .add("client_id", CLIENT_ID)
            .add("code", code)
            .add("redirect_uri", REDIRECT_URI)
            .add("code_verifier", verifier)
            .build()
        return exchangeTokenRequest(body, previous = null)
    }

    private fun exchangeTokenRequest(
        body: FormBody,
        previous: CodexOAuthSession?
    ): Result<CodexOAuthSession> {
        val request = Request.Builder().url(TOKEN_ENDPOINT).post(body).build()
        httpClient.newCall(request).execute().use { response ->
            val rawBody = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                val detail = try {
                    val json = JSONObject(rawBody)
                    json.optString("error_description").ifBlank { json.optString("error") }
                } catch (_: Exception) { "" }
                return Result.failure(
                    Exception("OAuth token request failed (HTTP ${response.code})${if (detail.isNotBlank()) ": $detail" else ""}")
                )
            }

            return try {
                val obj = JSONObject(rawBody)
                val accessToken = obj.optString("access_token")
                val refreshToken = obj.optString("refresh_token").ifBlank { previous?.refreshToken.orEmpty() }
                val idToken = obj.optString("id_token").ifBlank { previous?.idToken.orEmpty() }
                val accountId = extractAccountId(idToken)
                    ?: extractAccountId(accessToken)
                    ?: previous?.accountId
                    ?: ""
                val expiresAt = jwtExpiry(accessToken)
                    ?: ((System.currentTimeMillis() / 1000L) + obj.optLong("expires_in", 3600L))

                if (accessToken.isBlank() || accountId.isBlank()) {
                    Result.failure(Exception("OAuth response mist access_token of ChatGPT account-id."))
                } else {
                    Result.success(CodexOAuthSession(accessToken, refreshToken, idToken, accountId, expiresAt))
                }
            } catch (e: Exception) {
                Result.failure(Exception("OAuth token response could not be parsed: ${e.message}"))
            }
        }
    }

    internal fun extractAccountId(jwt: String): String? {
        return jwtPayload(jwt)?.optJSONObject("https://api.openai.com/auth")
            ?.optString("chatgpt_account_id")
            ?.takeIf { it.isNotBlank() }
    }

    private fun jwtExpiry(jwt: String): Long? = jwtPayload(jwt)?.optLong("exp")?.takeIf { it > 0L }

    private fun jwtPayload(jwt: String): JSONObject? {
        return try {
            val payload = jwt.split('.').getOrNull(1) ?: return null
            val decoded = Base64.decode(payload, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
            JSONObject(String(decoded, Charsets.UTF_8))
        } catch (_: Exception) {
            null
        }
    }
}
