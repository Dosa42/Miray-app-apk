package com.example.codex

import com.example.model.AIProviderConfig
import com.example.model.AIProviderType
import com.example.model.AgentMemory
import com.example.model.ChatMessage
import com.example.model.HealthTestResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Compatibility facade retained for the existing UI/database; V5.2 has exactly one AI provider. */
object MultiProviderAIService {
    const val DEFAULT_OPENAI_URL = CodexAgentService.DEFAULT_BASE_URL
    const val DEFAULT_OPENAI_MODEL = CodexAgentService.DEFAULT_MODEL
    val PRESET_OPENAI_MODELS = listOf(DEFAULT_OPENAI_MODEL)

    fun getDefaultConfigs(): List<AIProviderConfig> = listOf(
        AIProviderConfig(
            providerId = AIProviderType.OPENAI.id,
            baseUrl = DEFAULT_OPENAI_URL,
            apiKey = "",
            model = DEFAULT_OPENAI_MODEL
        )
    )

    suspend fun testProviderHealth(config: AIProviderConfig): HealthTestResult = withContext(Dispatchers.IO) {
        val startTime = System.currentTimeMillis()
        if (AIProviderType.fromId(config.providerId) != AIProviderType.OPENAI) {
            return@withContext HealthTestResult(
                success = false,
                statusCode = 400,
                latencyMs = 0,
                message = "Unsupported provider '${config.providerId}'. V5.2 permits only $DEFAULT_OPENAI_MODEL."
            )
        }
        if (config.model != DEFAULT_OPENAI_MODEL) {
            return@withContext HealthTestResult(
                success = false,
                statusCode = 400,
                latencyMs = 0,
                message = "Unsupported model '${config.model}'. No model fallback was applied."
            )
        }
        CodexAgentService.testHealth(
            config.copy(baseUrl = DEFAULT_OPENAI_URL, model = DEFAULT_OPENAI_MODEL),
            startTime
        )
    }

    internal fun buildSystemInstruction(
        memories: List<AgentMemory>,
        termuxInstalled: Boolean,
        retrievalTrace: String = "All supplied memory layers included."
    ): String {
        val memoryBlock = if (memories.isEmpty()) {
            "No persisted memory matched this task."
        } else {
            memories.joinToString("\n") { memory ->
                "- [${memory.category.uppercase()}] ${memory.title}: ${memory.content} " +
                    "(Importance: ${memory.importance}/5, pinned: ${memory.isPinned}, source: ${memory.source})"
            }
        }

        return """
You are Termux Agentic AI, the owner's private system-engineering and coding agent powered only by ${CodexAgentService.DEFAULT_MODEL}.
Use the provided real tools whenever the task requires an operation. Never claim that an operation, command, file change, memory write, health check, or permission change succeeded unless its authoritative tool result proves success.

OWNER AUTHORITY AND CONFIGURATION PRESERVATION — PRIORITY 0:
- The owner intentionally uses a rooted Android test device with Termux and has requested unattended system/tool access.
- Never revoke, reduce, disable, reset, overwrite, or revert the owner's Android, root, Termux, microphone, storage, network, OAuth, Groq, exec-server, or app settings.
- Never replace owner settings with a safer default and never silently change permissions, model, reasoning effort, prompt, memory context, or tool parameters.
- If Android, Termux, root, a managed policy, or the exec-server denies an operation, preserve and report the exact real error. Do not invent a workaround result.
- Bugs, errors, mocks, silent fallbacks, partial responses, and fake success are critical hard failures. Latency is normal.

Environment:
- Termux installed: $termuxInstalled
- Direct execution targets: the official Termux RUN_COMMAND service, Android root shell through su, and the Android app sandbox.
- The real tool output is authoritative. Empty output, unknown exit status, malformed tool JSON, interrupted streams, and incomplete responses are failures.

Persistent memory retrieval trace:
$retrievalTrace

Persistent memory:
$memoryBlock

Working rules:
- Inspect relevant state before making dependent changes.
- Use Termux tools for Termux paths and packages; use root only when the requested system operation needs it.
- Produce complete, functional commands and scripts.
- Explain actual results concisely in the user's language.
- Use save_memory only for a durable owner directive, preference, configuration, project fact, or reusable workflow supported by the current conversation/tool evidence.
""".trimIndent()
    }

    suspend fun sendAgentChat(
        providerConfig: AIProviderConfig,
        conversationHistory: List<ChatMessage>,
        userMessage: String,
        memories: List<AgentMemory>,
        termuxInstalled: Boolean,
        reasoningEffort: String,
        retrievalTrace: String = "All supplied memory layers included.",
        onTextDelta: (String) -> Unit = {}
    ): Result<AgentResponse> = withContext(Dispatchers.IO) {
        if (AIProviderType.fromId(providerConfig.providerId) != AIProviderType.OPENAI) {
            return@withContext Result.failure(
                IllegalArgumentException("Unsupported provider '${providerConfig.providerId}'. No provider fallback was applied.")
            )
        }
        if (providerConfig.model != DEFAULT_OPENAI_MODEL) {
            return@withContext Result.failure(
                IllegalArgumentException("Unsupported model '${providerConfig.model}'. No model fallback was applied.")
            )
        }
        CodexAgentService.sendAgentChat(
            providerConfig.copy(baseUrl = DEFAULT_OPENAI_URL, model = DEFAULT_OPENAI_MODEL),
            conversationHistory,
            userMessage,
            buildSystemInstruction(memories, termuxInstalled, retrievalTrace),
            reasoningEffort,
            onTextDelta
        )
    }

    suspend fun continueCodexAgent(
        providerConfig: AIProviderConfig,
        conversationHistory: List<ChatMessage>,
        memories: List<AgentMemory>,
        termuxInstalled: Boolean,
        reasoningEffort: String,
        retrievalTrace: String = "All supplied memory layers included.",
        onTextDelta: (String) -> Unit = {}
    ): Result<AgentResponse> = withContext(Dispatchers.IO) {
        if (providerConfig.model != DEFAULT_OPENAI_MODEL) {
            return@withContext Result.failure(
                IllegalArgumentException("Unsupported model '${providerConfig.model}'. No model fallback was applied.")
            )
        }
        CodexAgentService.continueAgentChat(
            providerConfig.copy(baseUrl = DEFAULT_OPENAI_URL, model = DEFAULT_OPENAI_MODEL),
            conversationHistory,
            buildSystemInstruction(memories, termuxInstalled, retrievalTrace),
            reasoningEffort,
            onTextDelta
        )
    }
}
