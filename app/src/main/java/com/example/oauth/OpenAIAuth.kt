package com.example.oauth

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

class SharedPrefsOAuthSessionStore(context: Context) : OAuthSessionStore {
    private val prefs: SharedPreferences = context.getSharedPreferences("oauth_prefs", Context.MODE_PRIVATE)
    private val KEY_SESSION = "oauth_session"

    override suspend fun load(): OAuthSession? = withContext(Dispatchers.IO) {
        val jsonString = prefs.getString(KEY_SESSION, null) ?: return@withContext null
        try {
            val json = JSONObject(jsonString)
            OAuthSession(
                accessToken = json.getString("access_token"),
                refreshToken = json.optString("refresh_token", null),
                idToken = json.optString("id_token", null),
                expiresAtEpochSeconds = json.getLong("expires_at")
            )
        } catch (e: Exception) {
            null
        }
    }

    override suspend fun save(session: OAuthSession) = withContext(Dispatchers.IO) {
        val json = JSONObject().apply {
            put("access_token", session.accessToken)
            session.refreshToken?.let { put("refresh_token", it) }
            session.idToken?.let { put("id_token", it) }
            put("expires_at", session.expiresAtEpochSeconds)
        }
        prefs.edit().putString(KEY_SESSION, json.toString()).apply()
    }

    override suspend fun clear() = withContext(Dispatchers.IO) {
        prefs.edit().remove(KEY_SESSION).apply()
    }
}

object OpenAIOAuthConfig {
    val config = OAuthProviderConfig(
        clientId = "app_EMoamEEZ73f0CkXaXp7hrann",
        authorizationEndpoint = "https://auth.openai.com/oauth/authorize",
        tokenEndpoint = "https://auth.openai.com/oauth/token",
        scopes = listOf("openid", "profile", "email", "offline_access", "api.connectors.read", "api.connectors.invoke"),
        callbackHost = "127.0.0.1",
        callbackPort = 1455,
        callbackPath = "/auth/callback",
        additionalAuthorizationParameters = mapOf(
            "id_token_add_organizations" to "true",
            "codex_cli_simplified_flow" to "true",
            "originator" to "codex_cli_rs"
        )
    )
}
