package com.example.gemini

import com.example.model.ChatMessage
import com.example.model.MessageRole
import kotlinx.coroutines.CompletableDeferred
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

data class UltraTurnResult(
    val text: String,
    val threadId: String,
    val turnId: String,
    val rawEvents: List<String>,
    val completedToolItems: List<String>
)

/**
 * Fail-closed WebSocket client for the Codex exec/app-server protocol.
 * Ultra is accepted only when the connected server advertises both the exact model and effort.
 */
class CodexExecServerClient(
    private val endpoint: String,
    private val workingDirectory: String,
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .followRedirects(false)
        .followSslRedirects(false)
        .build()
) {
    suspend fun runUltraTurn(
        systemPrompt: String,
        history: List<ChatMessage>,
        userMessage: String,
        onTextDelta: (String) -> Unit = {},
        onEvent: (String) -> Unit = {}
    ): Result<UltraTurnResult> {
        if (!endpoint.startsWith("ws://") && !endpoint.startsWith("wss://")) {
            return unavailable("Invalid WebSocket endpoint: $endpoint")
        }
        if (userMessage.isBlank()) return unavailable("User message is empty")

        val completion = CompletableDeferred<Result<UltraTurnResult>>()
        val finished = AtomicBoolean(false)
        val rawEvents = mutableListOf<String>()
        val finalText = StringBuilder()
        val toolItems = mutableListOf<String>()
        var webSocket: WebSocket? = null
        var threadId = ""
        var turnId = ""
        var receivedTurnEvent = false

        fun finish(result: Result<UltraTurnResult>) {
            if (finished.compareAndSet(false, true)) {
                completion.complete(result)
                webSocket?.close(1000, "turn finished")
            }
        }

        fun fail(cause: String) = finish(unavailable(cause))

        fun sendRequest(socket: WebSocket, id: Int, method: String, params: JSONObject) {
            val sent = socket.send(JSONObject().put("method", method).put("id", id).put("params", params).toString())
            if (!sent) fail("WebSocket rejected $method request")
        }

        val listener = object : WebSocketListener() {
            override fun onOpen(socket: WebSocket, response: Response) {
                webSocket = socket
                sendRequest(
                    socket,
                    1,
                    "initialize",
                    JSONObject().put(
                        "clientInfo",
                        JSONObject()
                            .put("name", "termux_agent_ai")
                            .put("title", "Termux Agent AI")
                            .put("version", "5.2")
                    ).put("capabilities", JSONObject().put("experimentalApi", true))
                )
            }

            override fun onMessage(socket: WebSocket, text: String) {
                if (finished.get()) return
                synchronized(rawEvents) { rawEvents += text }
                onEvent(text)
                val message = try {
                    JSONObject(text)
                } catch (error: Exception) {
                    fail("Unparseable exec-server event: ${error.message}")
                    return
                }

                if (message.has("error")) {
                    val rpcError = message.optJSONObject("error")
                    fail(rpcError?.optString("message").orEmpty().ifBlank { message.get("error").toString() })
                    return
                }

                when (message.optInt("id", -1)) {
                    1 -> {
                        socket.send(JSONObject().put("method", "initialized").put("params", JSONObject()).toString())
                        sendRequest(socket, 2, "model/list", JSONObject().put("limit", 200).put("includeHidden", true))
                        return
                    }
                    2 -> {
                        val models = message.optJSONObject("result")?.optJSONArray("data")
                        if (models == null) {
                            fail("model/list returned no data")
                            return
                        }
                        var exactModel: JSONObject? = null
                        for (index in 0 until models.length()) {
                            val candidate = models.optJSONObject(index) ?: continue
                            if (candidate.optString("model") == CodexAgentService.DEFAULT_MODEL ||
                                candidate.optString("id") == CodexAgentService.DEFAULT_MODEL
                            ) exactModel = candidate
                        }
                        if (exactModel == null) {
                            fail("exec-server does not advertise ${CodexAgentService.DEFAULT_MODEL}")
                            return
                        }
                        if (!serverSupportsUltra(models)) {
                            fail("${CodexAgentService.DEFAULT_MODEL} does not advertise real ultra effort")
                            return
                        }
                        sendRequest(
                            socket,
                            3,
                            "thread/start",
                            JSONObject()
                                .put("model", CodexAgentService.DEFAULT_MODEL)
                                .put("cwd", workingDirectory)
                                .put("approvalPolicy", "never")
                                .put("sandbox", "dangerFullAccess")
                                .put("serviceName", "termux_agent_ai_v52")
                        )
                        return
                    }
                    3 -> {
                        threadId = message.optJSONObject("result")
                            ?.optJSONObject("thread")?.optString("id").orEmpty()
                        if (threadId.isBlank()) {
                            fail("thread/start returned no thread id")
                            return
                        }
                        val inputText = buildUltraInput(systemPrompt, history, userMessage)
                        sendRequest(
                            socket,
                            4,
                            "turn/start",
                            JSONObject()
                                .put("threadId", threadId)
                                .put("input", JSONArray().put(JSONObject().put("type", "text").put("text", inputText)))
                                .put("cwd", workingDirectory)
                                .put("approvalPolicy", "never")
                                .put("sandboxPolicy", JSONObject().put("type", "dangerFullAccess"))
                                .put("model", CodexAgentService.DEFAULT_MODEL)
                                .put("effort", "ultra")
                        )
                        return
                    }
                    4 -> {
                        turnId = message.optJSONObject("result")
                            ?.optJSONObject("turn")?.optString("id").orEmpty()
                        if (turnId.isBlank()) fail("turn/start returned no turn id")
                        return
                    }
                }

                val method = message.optString("method")
                val params = message.optJSONObject("params") ?: JSONObject()

                // The owner selected unattended full access. Accept only explicit server approval requests.
                if (message.has("id") && method == "item/commandExecution/requestApproval") {
                    socket.send(JSONObject().put("id", message.get("id")).put("result", JSONObject().put("decision", "acceptForSession")).toString())
                    return
                }
                if (message.has("id") && method == "item/fileChange/requestApproval") {
                    socket.send(JSONObject().put("id", message.get("id")).put("result", JSONObject().put("decision", "acceptForSession")).toString())
                    return
                }
                if (message.has("id") && method == "item/permissions/requestApproval") {
                    val requested = params.optJSONArray("permissions")
                    if (requested == null) {
                        fail("Permission approval request omitted the requested permissions")
                    } else {
                        socket.send(JSONObject().put("id", message.get("id")).put("result", JSONObject().put("permissions", requested).put("scope", "session")).toString())
                    }
                    return
                }
                if (message.has("id") && method.isNotBlank()) {
                    fail("Unsupported server request: $method")
                    return
                }

                when (method) {
                    "model/rerouted" -> fail("Server attempted model reroute: ${params.toString()}")
                    "turn/started", "item/started", "item/completed",
                    "item/agentMessage/delta", "item/commandExecution/outputDelta",
                    "item/reasoning/summaryTextDelta", "item/reasoning/textDelta" -> receivedTurnEvent = true
                }

                when (method) {
                    "item/agentMessage/delta" -> {
                        val delta = params.optString("delta")
                        if (delta.isNotEmpty()) {
                            finalText.append(delta)
                            onTextDelta(delta)
                        }
                    }
                    "item/completed" -> {
                        val item = params.optJSONObject("item")
                        if (item == null) {
                            fail("item/completed omitted its authoritative item")
                            return
                        }
                        when (item.optString("type")) {
                            "agentMessage" -> {
                                val authoritative = item.optString("text")
                                if (authoritative.isNotBlank() && authoritative != finalText.toString()) {
                                    finalText.clear()
                                    finalText.append(authoritative)
                                }
                            }
                            "commandExecution" -> {
                                val status = item.optString("status")
                                if (status != "completed" || !item.has("exitCode")) {
                                    fail("Command tool did not complete with a known exitCode: ${item.toString()}")
                                    return
                                }
                                if (item.optInt("exitCode", -1) != 0) {
                                    fail("Command tool returned non-zero exitCode: ${item.toString()}")
                                    return
                                }
                                toolItems += item.toString()
                            }
                            "fileChange", "mcpToolCall", "dynamicToolCall" -> {
                                val status = item.optString("status")
                                if (status != "completed") {
                                    fail("Tool item did not complete: ${item.toString()}")
                                    return
                                }
                                if (item.has("success") && !item.optBoolean("success")) {
                                    fail("Tool item reported success=false: ${item.toString()}")
                                    return
                                }
                                toolItems += item.toString()
                            }
                        }
                    }
                    "error" -> fail(params.optJSONObject("error")?.optString("message").orEmpty().ifBlank { params.toString() })
                    "turn/completed" -> {
                        val turn = params.optJSONObject("turn")
                        val status = turn?.optString("status").orEmpty()
                        if (status != "completed") {
                            fail(turn?.optJSONObject("error")?.optString("message").orEmpty().ifBlank { "Turn status was $status" })
                            return
                        }
                        if (!receivedTurnEvent) {
                            fail("Turn completed without a real event stream")
                            return
                        }
                        if (finalText.isBlank() && toolItems.isEmpty()) {
                            fail("Turn completed without assistant text or tool output")
                            return
                        }
                        finish(Result.success(UltraTurnResult(finalText.toString(), threadId, turnId, rawEvents.toList(), toolItems.toList())))
                    }
                }
            }

            override fun onFailure(socket: WebSocket, error: Throwable, response: Response?) {
                fail("WebSocket failure${response?.code?.let { " HTTP $it" }.orEmpty()}: ${error.localizedMessage ?: error.message}")
            }

            override fun onClosed(socket: WebSocket, code: Int, reason: String) {
                if (!finished.get()) fail("WebSocket closed before turn completion ($code): $reason")
            }
        }

        webSocket = httpClient.newWebSocket(Request.Builder().url(endpoint).build(), listener)
        return completion.await()
    }

    internal fun buildUltraInput(
        systemPrompt: String,
        history: List<ChatMessage>,
        userMessage: String
    ): String = buildString {
        append("SYSTEM INSTRUCTIONS\n")
        append(systemPrompt)
        append("\n\nCONVERSATION HISTORY\n")
        history.forEach { message ->
            if (message.role == MessageRole.USER || message.role == MessageRole.MODEL) {
                append(if (message.role == MessageRole.USER) "USER: " else "ASSISTANT: ")
                append(message.text)
                append('\n')
            }
        }
        append("\nCURRENT USER REQUEST\n")
        append(userMessage)
    }

    private fun <T> unavailable(cause: String): Result<T> =
        Result.failure(IllegalStateException("ULTRA_UNAVAILABLE\nExact cause: $cause"))

    internal fun serverSupportsUltra(models: JSONArray): Boolean {
        for (index in 0 until models.length()) {
            val model = models.optJSONObject(index) ?: continue
            if (model.optString("model") != CodexAgentService.DEFAULT_MODEL &&
                model.optString("id") != CodexAgentService.DEFAULT_MODEL
            ) continue
            val efforts = model.optJSONArray("supportedReasoningEfforts") ?: return false
            return (0 until efforts.length()).any { effortIndex ->
                efforts.optJSONObject(effortIndex)?.optString("reasoningEffort") == "ultra"
            }
        }
        return false
    }
}
