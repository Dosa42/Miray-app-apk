package com.example.model

import androidx.room.Entity
import androidx.room.PrimaryKey

enum class AIProviderType(val id: String, val displayName: String) {
    OPENAI("OPENAI", "Codex OAuth"),
    ANTHROPIC("ANTHROPIC", "Anthropic Claude");

    companion object {
        fun fromId(id: String): AIProviderType {
            return entries.find { it.id.equals(id, ignoreCase = true) }
                ?: throw IllegalArgumentException("Unsupported provider '$id'. No provider fallback was applied.")
        }
    }
}

@Entity(tableName = "provider_configs")
data class AIProviderConfig(
    @PrimaryKey val providerId: String, // "OPENAI", "ANTHROPIC"
    val baseUrl: String,
    val apiKey: String,
    val model: String,
    val lastTestedTimestamp: Long = 0L,
    val lastTestSuccess: Boolean? = null,
    val lastTestLatencyMs: Long = 0L,
    val lastTestMessage: String = ""
)

data class HealthTestResult(
    val success: Boolean,
    val statusCode: Int = 0,
    val latencyMs: Long = 0L,
    val message: String,
    val details: String = ""
)
