package com.example.oauth

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

private const val CHATGPT_ACCOUNT_ID_METADATA_KEY = "chatgpt_account_id"

sealed interface OpenAIConnectionState {
    data object LoggedOut : OpenAIConnectionState
    data object Authorizing : OpenAIConnectionState
    data class Connected(val accountId: String? = null) : OpenAIConnectionState
    data object Refreshing : OpenAIConnectionState
    data class Error(val message: String) : OpenAIConnectionState
}

object OpenAIProviderDefaults {
    const val DEFAULT_MODEL = "gpt-5.6-sol"
    const val BACKEND_BASE_URL = "https://chatgpt.com/backend-api/codex"

    val MODELS: List<String> = listOf(
        "gpt-5.6-sol",
        "gpt-5.6-terra",
        "gpt-5.6-luna"
    )
}

data class OpenAIProviderSettings(
    val model: String = OpenAIProviderDefaults.DEFAULT_MODEL,
    val backendBaseUrl: String = OpenAIProviderDefaults.BACKEND_BASE_URL
)

val OAuthSession.chatGptAccountId: String?
    get() = providerMetadata[CHATGPT_ACCOUNT_ID_METADATA_KEY]
        ?.takeIf { it.isNotBlank() }

class SharedPrefsOpenAIProviderStore(context: Context) : OAuthSessionStore {
    private val prefs: SharedPreferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE
    )

    override suspend fun load(): OAuthSession? = withContext(Dispatchers.IO) {
        try {
            val stored = prefs.getString(KEY_SESSION, null) ?: return@withContext null
            val json = JSONObject(stored)
            val accessToken = json.optString(KEY_ACCESS_TOKEN)
            if (accessToken.isBlank()) {
                return@withContext null
            }

            val refreshToken = json.optionalString(KEY_REFRESH_TOKEN)
            val idToken = json.optionalString(KEY_ID_TOKEN)
            val metadata = json.optJSONObject(KEY_PROVIDER_METADATA).toStringMap()
            val accountId = json.optionalString(KEY_ACCOUNT_ID)
                ?: metadata[CHATGPT_ACCOUNT_ID_METADATA_KEY]
                ?: OpenAIJwtClaims.accountId(idToken)
                ?: OpenAIJwtClaims.accountId(accessToken)

            accountId?.let { metadata[CHATGPT_ACCOUNT_ID_METADATA_KEY] = it }
            OAuthSession(
                accessToken = accessToken,
                refreshToken = refreshToken,
                idToken = idToken,
                expiresAtEpochSeconds = json.optLong(
                    KEY_EXPIRES_AT,
                    OpenAIJwtClaims.expiry(accessToken) ?: 0L
                ),
                providerMetadata = metadata
            )
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            null
        }
    }

    override suspend fun save(session: OAuthSession) = withContext(Dispatchers.IO) {
        val metadata = session.providerMetadata.toMutableMap()
        val accountId = session.chatGptAccountId
            ?: OpenAIJwtClaims.accountId(session.idToken)
            ?: OpenAIJwtClaims.accountId(session.accessToken)
        accountId?.takeIf { it.isNotBlank() }
            ?.let { metadata[CHATGPT_ACCOUNT_ID_METADATA_KEY] = it }
        val metadataJson = JSONObject().apply {
            metadata.forEach { (key, value) -> put(key, value) }
        }

        val json = JSONObject().apply {
            put(KEY_TYPE, SESSION_TYPE)
            put(KEY_ACCESS_TOKEN, session.accessToken)
            session.refreshToken?.let { put(KEY_REFRESH_TOKEN, it) }
            session.idToken?.let { put(KEY_ID_TOKEN, it) }
            accountId?.takeIf { it.isNotBlank() }?.let { put(KEY_ACCOUNT_ID, it) }
            put(KEY_EXPIRES_AT, session.expiresAtEpochSeconds)
            put(KEY_PROVIDER_METADATA, metadataJson)
        }
        editPreferencesBestEffort {
            putString(KEY_SESSION, json.toString())
        }
    }

    override suspend fun clear() = withContext(Dispatchers.IO) {
        editPreferencesBestEffort {
            remove(KEY_SESSION)
        }
    }

    suspend fun loadSettings(): OpenAIProviderSettings = withContext(Dispatchers.IO) {
        try {
            val stored = prefs.getString(KEY_SETTINGS, null)
                ?: return@withContext OpenAIProviderSettings()
            val json = JSONObject(stored)
            OpenAIProviderSettings(
                model = json.optString(KEY_MODEL)
                    .ifBlank { OpenAIProviderDefaults.DEFAULT_MODEL },
                backendBaseUrl = json.optString(KEY_BACKEND_BASE_URL)
                    .ifBlank { OpenAIProviderDefaults.BACKEND_BASE_URL }
            )
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            OpenAIProviderSettings()
        }
    }

    suspend fun saveSettings(settings: OpenAIProviderSettings) = withContext(Dispatchers.IO) {
        val json = JSONObject().apply {
            put(KEY_MODEL, settings.model)
            put(KEY_BACKEND_BASE_URL, settings.backendBaseUrl)
        }
        editPreferencesBestEffort {
            putString(KEY_SETTINGS, json.toString())
        }
    }

    private fun editPreferencesBestEffort(block: SharedPreferences.Editor.() -> Unit) {
        try {
            val editor = prefs.edit()
            editor.block()
            editor.apply()
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            // The in-memory provider/session state remains authoritative for this process.
        }
    }

    private companion object {
        const val PREFERENCES_NAME = "oauth_prefs"
        const val KEY_SESSION = "oauth_session"
        const val KEY_SETTINGS = "openai_provider_settings"
        const val KEY_TYPE = "type"
        const val SESSION_TYPE = "codex_oauth"
        const val KEY_ACCESS_TOKEN = "access_token"
        const val KEY_REFRESH_TOKEN = "refresh_token"
        const val KEY_ID_TOKEN = "id_token"
        const val KEY_ACCOUNT_ID = "account_id"
        const val KEY_EXPIRES_AT = "expires_at"
        const val KEY_PROVIDER_METADATA = "provider_metadata"
        const val KEY_MODEL = "model"
        const val KEY_BACKEND_BASE_URL = "backend_base_url"
    }
}

typealias SharedPrefsOAuthSessionStore = SharedPrefsOpenAIProviderStore

object OpenAIOAuthConfig {
    val config = OAuthProviderConfig(
        clientId = "app_EMoamEEZ73f0CkXaXp7hrann",
        authorizationEndpoint = "https://auth.openai.com/oauth/authorize",
        tokenEndpoint = "https://auth.openai.com/oauth/token",
        scopes = listOf(
            "openid",
            "profile",
            "email",
            "offline_access",
            "api.connectors.read",
            "api.connectors.invoke"
        ),
        callbackHost = "localhost",
        callbackPort = 1455,
        callbackPath = "/auth/callback",
        additionalAuthorizationParameters = linkedMapOf(
            "id_token_add_organizations" to "true",
            "codex_cli_simplified_flow" to "true",
            "originator" to "codex_cli_rs"
        )
    )

    fun createManager(sessionStore: OAuthSessionStore): OAuthPkceManager = OAuthPkceManager(
        config = config,
        sessionStore = sessionStore,
        tokenDecoder = OpenAIOAuthTokenDecoder
    )
}

object OpenAIOAuthTokenDecoder : OAuthTokenDecoder {
    override fun decode(rawJson: String, previous: OAuthSession?): OAuthSession {
        val json = JSONObject(rawJson)
        val accessToken = json.optString("access_token")
        require(accessToken.isNotBlank()) {
            "OpenAI OAuth token response did not contain access_token."
        }

        val refreshToken = json.optionalString("refresh_token") ?: previous?.refreshToken
        val idToken = json.optionalString("id_token") ?: previous?.idToken
        val accountId = OpenAIJwtClaims.accountId(idToken)
            ?: OpenAIJwtClaims.accountId(accessToken)
            ?: previous?.chatGptAccountId

        val expiresAt = OpenAIJwtClaims.expiry(accessToken)
            ?: (System.currentTimeMillis() / 1000L) + json.optLong("expires_in", 3600L)
        val metadata = previous?.providerMetadata.orEmpty().toMutableMap().apply {
            accountId?.takeIf { it.isNotBlank() }
                ?.let { put(CHATGPT_ACCOUNT_ID_METADATA_KEY, it) }
        }

        return OAuthSession(
            accessToken = accessToken,
            refreshToken = refreshToken,
            idToken = idToken,
            expiresAtEpochSeconds = expiresAt,
            providerMetadata = metadata
        )
    }
}

private object OpenAIJwtClaims {
    private const val OPENAI_AUTH_CLAIM = "https://api.openai.com/auth"

    fun accountId(jwt: String?): String? = payload(jwt)
        ?.optJSONObject(OPENAI_AUTH_CLAIM)
        ?.optString(CHATGPT_ACCOUNT_ID_METADATA_KEY)
        ?.takeIf { it.isNotBlank() }

    fun expiry(jwt: String?): Long? = payload(jwt)
        ?.optLong("exp")
        ?.takeIf { it > 0L }

    private fun payload(jwt: String?): JSONObject? {
        if (jwt.isNullOrBlank()) return null
        return try {
            val encodedPayload = jwt.split('.').getOrNull(1) ?: return null
            val decoded = Base64.decode(
                encodedPayload,
                Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP
            )
            JSONObject(String(decoded, Charsets.UTF_8))
        } catch (_: Exception) {
            null
        }
    }
}

private fun JSONObject.optionalString(name: String): String? =
    optString(name).takeIf { it.isNotBlank() && it != "null" }

private fun JSONObject?.toStringMap(): MutableMap<String, String> {
    if (this == null) return mutableMapOf()
    val result = mutableMapOf<String, String>()
    val keys = keys()
    while (keys.hasNext()) {
        val key = keys.next()
        optionalString(key)?.let { result[key] = it }
    }
    return result
}
