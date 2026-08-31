package com.example.oauth

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Base64
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.TimeUnit

data class OAuthProviderConfig(
    val clientId: String,
    val authorizationEndpoint: String,
    val tokenEndpoint: String,
    val scopes: List<String>,
    val callbackHost: String = "127.0.0.1",
    val callbackPort: Int,
    val callbackPath: String = "/oauth/callback",
    val additionalAuthorizationParameters: Map<String, String> = emptyMap()
) {
    val redirectUri: String
        get() = "http://$callbackHost:$callbackPort$callbackPath"
}

data class OAuthSession(
    val accessToken: String,
    val refreshToken: String?,
    val idToken: String?,
    val expiresAtEpochSeconds: Long,
    val providerMetadata: Map<String, String> = emptyMap()
)

interface OAuthSessionStore {
    suspend fun load(): OAuthSession?
    suspend fun save(session: OAuthSession)
    suspend fun clear()
}

fun interface OAuthTokenDecoder {
    fun decode(rawJson: String, previous: OAuthSession?): OAuthSession
}

class StandardOAuthTokenDecoder : OAuthTokenDecoder {
    override fun decode(rawJson: String, previous: OAuthSession?): OAuthSession {
        val json = JSONObject(rawJson)
        val accessToken = json.optString("access_token")
        require(accessToken.isNotBlank()) { "Token response did not contain access_token." }

        val refreshToken = json.optString("refresh_token")
            .takeIf { it.isNotBlank() }
            ?: previous?.refreshToken
        val idToken = json.optString("id_token")
            .takeIf { it.isNotBlank() }
            ?: previous?.idToken
        val expiresIn = json.optLong("expires_in", 3600L)

        return OAuthSession(
            accessToken = accessToken,
            refreshToken = refreshToken,
            idToken = idToken,
            expiresAtEpochSeconds = (System.currentTimeMillis() / 1000L) + expiresIn
        )
    }
}

class OAuthPkceManager(
    private val config: OAuthProviderConfig,
    private val sessionStore: OAuthSessionStore,
    private val tokenDecoder: OAuthTokenDecoder = StandardOAuthTokenDecoder(),
    private val httpClient: OkHttpClient = defaultOAuthHttpClient(),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) {
    @Volatile
    private var activeSession: OAuthSession? = null

    suspend fun login(context: Context): Result<OAuthSession> = withContext(ioDispatcher) {
        try {
            val verifier = createCodeVerifier()
            val state = UUID.randomUUID().toString()
            val authorizationUri = buildAuthorizationUri(verifier, state)

            val authorizationCode = ServerSocket().use { server ->
                server.reuseAddress = true
                server.bind(InetSocketAddress(config.callbackHost, config.callbackPort))
                server.soTimeout = 180_000

                context.startActivity(
                    Intent(Intent.ACTION_VIEW, authorizationUri)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )

                receiveAuthorizationCode(server, state)
            }

            val session = exchangeAuthorizationCode(authorizationCode, verifier)
            activeSession = session
            persistSessionBestEffort(session)
            Result.success(session)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            Result.failure(error)
        }
    }

    suspend fun validSession(refreshWindowSeconds: Long = 0L): Result<OAuthSession> =
        withContext(ioDispatcher) {
            try {
                val session = requireNotNull(
                    activeSession ?: sessionStore.load()?.also { activeSession = it }
                ) {
                    "No OAuth session is stored."
                }
                val now = System.currentTimeMillis() / 1000L
                val validSession = if (
                    session.expiresAtEpochSeconds == 0L ||
                    session.expiresAtEpochSeconds > now + refreshWindowSeconds
                ) {
                    session
                } else {
                    try {
                        val refreshedSession = refresh(session)
                        activeSession = refreshedSession
                        persistSessionBestEffort(refreshedSession)
                        refreshedSession
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Throwable) {
                        if (session.accessToken.isNotBlank()) session else throw error
                    }
                }
                activeSession = validSession
                Result.success(validSession)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                Result.failure(error)
            }
        }

    suspend fun logout() {
        activeSession = null
        withContext(ioDispatcher) {
            clearSessionBestEffort()
        }
    }

    private fun buildAuthorizationUri(verifier: String, state: String): Uri {
        val builder = Uri.parse(config.authorizationEndpoint).buildUpon()
            .appendQueryParameter("response_type", "code")
            .appendQueryParameter("client_id", config.clientId)
            .appendQueryParameter("redirect_uri", config.redirectUri)
            .appendQueryParameter("scope", config.scopes.joinToString(" "))
            .appendQueryParameter("code_challenge", createCodeChallenge(verifier))
            .appendQueryParameter("code_challenge_method", "S256")
            .appendQueryParameter("state", state)

        config.additionalAuthorizationParameters.forEach { (name, value) ->
            builder.appendQueryParameter(name, value)
        }
        return builder.build()
    }

    private fun receiveAuthorizationCode(server: ServerSocket, expectedState: String): String {
        return server.accept().use { socket ->
            val reader = BufferedReader(InputStreamReader(socket.inputStream))
            val requestLine = reader.readLine().orEmpty()
            while (!reader.readLine().isNullOrEmpty()) Unit

            val requestTarget = requestLine.split(' ').getOrNull(1).orEmpty()
            val callback = Uri.parse(
                "http://${config.callbackHost}:${config.callbackPort}$requestTarget"
            )
            val returnedState = callback.getQueryParameter("state")
            val code = callback.getQueryParameter("code")
            val error = callback.getQueryParameter("error_description")
                ?: callback.getQueryParameter("error")

            val validPath = callback.path == config.callbackPath
            val validState = returnedState == expectedState
            val success = !code.isNullOrBlank() && error.isNullOrBlank()
            sendBrowserResponse(socket, success)

            require(error.isNullOrBlank()) { "OAuth provider returned: $error" }
            require(!code.isNullOrBlank()) {
                buildString {
                    append("OAuth callback contained no authorization code.")
                    if (!validPath) append(" Callback path did not match the configured path.")
                    if (!validState) append(" OAuth state was absent or did not match.")
                }
            }
            code
        }
    }

    private suspend fun persistSessionBestEffort(session: OAuthSession) {
        try {
            sessionStore.save(session)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Throwable) {
            // The active in-memory session remains usable when durable persistence is unavailable.
        }
    }

    private suspend fun clearSessionBestEffort() {
        try {
            sessionStore.clear()
        } catch (error: CancellationException) {
            throw error
        } catch (_: Throwable) {
            // Logout has already cleared the active in-memory session.
        }
    }

    private fun sendBrowserResponse(socket: java.net.Socket, success: Boolean) {
        val message = if (success) {
            "Login completed. You may return to the app."
        } else {
            "Login failed. Return to the app for details."
        }
        val body = "<html><body><h2>$message</h2></body></html>"
        val response = buildString {
            append("HTTP/1.1 200 OK\r\n")
            append("Content-Type: text/html; charset=utf-8\r\n")
            append("Connection: close\r\n")
            append("Content-Length: ${body.toByteArray().size}\r\n\r\n")
            append(body)
        }
        socket.getOutputStream().use { output ->
            output.write(response.toByteArray())
            output.flush()
        }
    }

    private fun exchangeAuthorizationCode(code: String, verifier: String): OAuthSession {
        val body = FormBody.Builder()
            .add("grant_type", "authorization_code")
            .add("client_id", config.clientId)
            .add("code", code)
            .add("redirect_uri", config.redirectUri)
            .add("code_verifier", verifier)
            .build()
        return executeTokenRequest(body, previous = null)
    }

    private fun refresh(previous: OAuthSession): OAuthSession {
        val refreshToken = requireNotNull(previous.refreshToken) {
            "The OAuth session expired and has no refresh token."
        }
        val body = FormBody.Builder()
            .add("grant_type", "refresh_token")
            .add("client_id", config.clientId)
            .add("refresh_token", refreshToken)
            .build()
        return executeTokenRequest(body, previous)
    }

    private fun executeTokenRequest(body: FormBody, previous: OAuthSession?): OAuthSession {
        val request = Request.Builder()
            .url(config.tokenEndpoint)
            .post(body)
            .build()

        httpClient.newCall(request).execute().use { response ->
            val rawBody = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                val detail = tokenErrorDetail(rawBody)
                throw IOException(
                    buildString {
                        append("OAuth token request failed with HTTP ${response.code}")
                        if (detail.isNotBlank()) append(": $detail")
                        append('.')
                    }
                )
            }
            return tokenDecoder.decode(rawBody, previous)
        }
    }

    private fun tokenErrorDetail(rawBody: String): String {
        if (rawBody.isBlank()) return ""

        return runCatching {
            val json = JSONObject(rawBody)
            json.optString("error_description").takeIf { it.isNotBlank() }
                ?: when (val error = json.opt("error")) {
                    is JSONObject -> error.optString("message").takeIf { it.isNotBlank() }
                        ?: error.optString("code").takeIf { it.isNotBlank() }
                        ?: error.toString()
                    is String -> error.takeIf { it.isNotBlank() }
                    else -> null
                }
                ?: json.optString("message").takeIf { it.isNotBlank() }
        }.getOrNull().orEmpty().ifBlank { rawBody.trim() }
    }

    private fun createCodeVerifier(): String {
        val bytes = ByteArray(32).also { SecureRandom().nextBytes(it) }
        return Base64.encodeToString(
            bytes,
            Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP
        )
    }

    private fun createCodeChallenge(verifier: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(verifier.toByteArray(Charsets.US_ASCII))
        return Base64.encodeToString(
            digest,
            Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP
        )
    }

    companion object {
        private fun defaultOAuthHttpClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }
}
