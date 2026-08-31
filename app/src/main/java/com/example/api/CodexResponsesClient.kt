package com.example.api

import com.example.oauth.OAuthPkceManager
import com.example.oauth.SharedPrefsOpenAIProviderStore
import com.example.oauth.chatGptAccountId
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.BufferedSource

data class CodexResponseResult(
    val responseId: String?,
    val text: String
)

open class CodexResponsesException(
    message: String,
    cause: Throwable? = null
) : IOException(message, cause)

class CodexAuthenticationException(
    message: String,
    cause: Throwable? = null
) : CodexResponsesException(message, cause)

class CodexHttpException(
    val statusCode: Int,
    val responseBody: String,
    detail: String?
) : CodexResponsesException(
    buildString {
        append("Codex Responses request failed with HTTP $statusCode")
        if (!detail.isNullOrBlank()) append(": $detail")
        append('.')
    }
)

class CodexProviderException(
    val eventType: String,
    detail: String
) : CodexResponsesException("Codex provider event $eventType: $detail")

class CodexSseException(
    message: String,
    cause: Throwable? = null
) : CodexResponsesException(message, cause)

class CodexTransportException(
    message: String,
    cause: Throwable
) : CodexResponsesException(message, cause)

class CodexResponsesClient(
    private val oauthManager: OAuthPkceManager,
    private val providerStore: SharedPrefsOpenAIProviderStore
) {
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .readTimeout(300, TimeUnit.SECONDS)
        .build()

    suspend fun createResponse(
        prompt: String,
        model: String,
        onTextDelta: (String) -> Unit = {}
    ): Result<CodexResponseResult> {
        val session = oauthManager.validSession().getOrElse { error ->
            return Result.failure(
                CodexAuthenticationException(
                    buildString {
                        append("A valid ChatGPT OAuth session is required for Codex Responses.")
                        error.message?.trim()?.takeIf { it.isNotEmpty() }?.let { detail ->
                            append(" ")
                            append(detail)
                        }
                    },
                    error
                )
            )
        }
        val accessToken = session.accessToken.trim().takeIf { it.isNotEmpty() }
            ?: return Result.failure(
                CodexAuthenticationException(
                    "The ChatGPT OAuth session does not contain an access token."
                )
            )
        val accountId = session.chatGptAccountId?.trim()?.takeIf { it.isNotEmpty() }

        return try {
            val settings = providerStore.loadSettings()
            val endpoint = settings.backendBaseUrl.trimEnd('/')
                .takeIf { it.isNotBlank() }
                ?.let { "$it/responses" }
                ?: throw CodexResponsesException("The Codex backend base URL is empty.")
            val request = buildRequest(
                endpoint = endpoint,
                accessToken = accessToken,
                accountId = accountId,
                prompt = prompt,
                model = model
            )
            Result.success(executeStreaming(request, onTextDelta))
        } catch (error: CancellationException) {
            throw error
        } catch (error: CodexResponsesException) {
            Result.failure(error)
        } catch (error: Throwable) {
            Result.failure(
                CodexTransportException(
                    "Codex Responses request failed: " +
                        (error.message ?: error::class.java.simpleName),
                    error
                )
            )
        }
    }

    private fun buildRequest(
        endpoint: String,
        accessToken: String,
        accountId: String?,
        prompt: String,
        model: String
    ): Request {
        val requestJson = buildJsonObject {
            put("model", model)
            put("instructions", "")
            put(
                "input",
                buildJsonArray {
                    add(
                        buildJsonObject {
                            put("role", "user")
                            put(
                                "content",
                                buildJsonArray {
                                    add(
                                        buildJsonObject {
                                            put("type", "input_text")
                                            put("text", prompt)
                                        }
                                    )
                                }
                            )
                        }
                    )
                }
            )
            put("tools", buildJsonArray {})
            put("tool_choice", "auto")
            put("parallel_tool_calls", true)
            put("store", false)
            put("stream", true)
            put("include", buildJsonArray {})
        }

        val requestBuilder = Request.Builder()
            .url(endpoint)
            .post(requestJson.toString().toRequestBody(JSON_MEDIA_TYPE))
            .header("Authorization", "Bearer $accessToken")
            .header("originator", ORIGINATOR)
            .header("version", CLIENT_VERSION)
            .header("Accept", "text/event-stream")
            .header("Content-Type", "application/json")

        accountId?.let { requestBuilder.header("ChatGPT-Account-ID", it) }
        return requestBuilder.build()
    }

    private suspend fun executeStreaming(
        request: Request,
        onTextDelta: (String) -> Unit
    ): CodexResponseResult = suspendCancellableCoroutine { continuation ->
        val call = httpClient.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(
            object : Callback {
                override fun onFailure(call: Call, error: IOException) {
                    if (!continuation.isActive) return
                    continuation.resumeWith(
                        Result.failure(
                            CodexTransportException(
                                "Codex Responses transport failed: " +
                                    (error.message ?: "I/O error"),
                                error
                            )
                        )
                    )
                }

                override fun onResponse(call: Call, response: Response) {
                    try {
                        response.use {
                            if (!response.isSuccessful) {
                                val responseBody = response.body?.string().orEmpty()
                                throw CodexHttpException(
                                    statusCode = response.code,
                                    responseBody = responseBody,
                                    detail = extractErrorDetail(responseBody)
                                )
                            }
                            val body = response.body
                                ?: throw CodexSseException(
                                    "Codex Responses returned an empty SSE body."
                                )
                            val result = parseCodexResponsesSse(
                                source = body.source(),
                                isActive = { continuation.isActive },
                                onTextDelta = onTextDelta
                            )
                            if (continuation.isActive) {
                                continuation.resumeWith(Result.success(result))
                            }
                        }
                    } catch (error: Throwable) {
                        if (!continuation.isActive) return
                        val attributedError = when (error) {
                            is CodexResponsesException -> error
                            else -> CodexSseException(
                                "Codex Responses stream failed: " +
                                    (error.message ?: error::class.java.simpleName),
                                error
                            )
                        }
                        continuation.resumeWith(Result.failure(attributedError))
                    }
                }
            }
        )
    }

    private companion object {
        const val ORIGINATOR = "codex_cli_rs"
        const val CLIENT_VERSION = "Miray-Android/1.0"
        val JSON_MEDIA_TYPE = "application/json".toMediaType()
    }
}

private fun parseCodexResponsesSse(
    source: BufferedSource,
    isActive: () -> Boolean,
    onTextDelta: (String) -> Unit
): CodexResponseResult {
    val state = CodexStreamState()
    var eventName: String? = null
    val dataLines = mutableListOf<String>()

    fun dispatchEvent() {
        if (dataLines.isEmpty()) {
            eventName = null
            return
        }
        if (!isActive()) throw CancellationException("Codex Responses call was cancelled.")
        state.accept(
            eventName = eventName,
            data = dataLines.joinToString("\n"),
            onTextDelta = onTextDelta
        )
        eventName = null
        dataLines.clear()
    }

    while (true) {
        if (!isActive()) throw CancellationException("Codex Responses call was cancelled.")
        val line = source.readUtf8Line() ?: break
        when {
            line.isEmpty() -> dispatchEvent()
            line.startsWith(":") -> Unit
            else -> {
                val separator = line.indexOf(':')
                val field = if (separator >= 0) line.substring(0, separator) else line
                val rawValue = if (separator >= 0) line.substring(separator + 1) else ""
                val value = rawValue.removePrefix(" ")
                when (field) {
                    "event" -> eventName = value
                    "data" -> dataLines += value
                }
            }
        }
    }
    dispatchEvent()

    return CodexResponseResult(
        responseId = state.responseId,
        text = state.text.toString()
    )
}

private class CodexStreamState {
    val text = StringBuilder()
    var responseId: String? = null

    fun accept(
        eventName: String?,
        data: String,
        onTextDelta: (String) -> Unit
    ) {
        if (data == "[DONE]") return

        val payload = try {
            Json.parseToJsonElement(data) as? JsonObject
                ?: throw CodexSseException(
                    "Codex SSE event did not contain a JSON object."
                )
        } catch (error: CodexResponsesException) {
            throw error
        } catch (error: Throwable) {
            if (eventName == "error") {
                throw CodexProviderException("error", data)
            }
            throw CodexSseException(
                "Could not parse Codex SSE event " + (eventName ?: "<unnamed>") + ".",
                error
            )
        }

        val eventType = if (eventName == "error") {
            "error"
        } else {
            payload.string("type") ?: eventName ?: "<unnamed>"
        }
        responseId = payload.string("response_id")
            ?: payload.objectValue("response")?.string("id")
            ?: responseId

        when (eventType) {
            "response.output_text.delta" -> {
                val delta = payload.string("delta")
                    ?: throw CodexSseException(
                        "Codex response.output_text.delta event contained no delta."
                    )
                text.append(delta)
                try {
                    onTextDelta(delta)
                } catch (error: Throwable) {
                    throw CodexSseException(
                        "The Codex text-delta consumer failed.",
                        error
                    )
                }
            }

            "error", "response.failed", "response.incomplete" -> {
                throw CodexProviderException(
                    eventType = eventType,
                    detail = payload.errorDetail()
                )
            }
        }
    }
}

private fun extractErrorDetail(rawBody: String): String? {
    if (rawBody.isBlank()) return null
    return try {
        (Json.parseToJsonElement(rawBody) as? JsonObject)?.errorDetail()
            ?.takeIf { it.isNotBlank() }
            ?: rawBody.trim()
    } catch (_: Throwable) {
        rawBody.trim()
    }
}

private fun JsonObject.errorDetail(): String {
    string("message")?.let { return it }
    val error = get("error")
    when (error) {
        is JsonPrimitive -> return error.content
        is JsonObject -> {
            error.string("message")?.let { return it }
            error.string("code")?.let { return it }
        }
        else -> Unit
    }
    objectValue("response")?.objectValue("error")?.let { responseError ->
        responseError.string("message")?.let { return it }
        responseError.string("code")?.let { return it }
    }
    return toString()
}

private fun JsonObject.string(name: String): String? =
    (get(name) as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() }

private fun JsonObject.objectValue(name: String): JsonObject? =
    get(name) as? JsonObject
