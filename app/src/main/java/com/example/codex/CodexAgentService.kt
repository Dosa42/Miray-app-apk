package com.example.codex

import com.example.model.AIProviderConfig
import com.example.model.ActionType
import com.example.model.AgentAction
import com.example.model.ChatMessage
import com.example.model.MessageRole
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.TimeUnit

/** Direct client for the ChatGPT Codex Responses backend using OAuth credentials only. */
object CodexAgentService {
    const val DEFAULT_BASE_URL = "https://chatgpt.com/backend-api/codex"
    const val DEFAULT_MODEL = "gpt-5.6-sol"
    const val DICTATION_REASONING_EFFORT = "max"

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        // Deliberately no response read deadline: long reasoning is normal operation.
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true) // OkHttp retries the identical immutable request only.
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    internal fun responsesEndpoint(baseUrl: String): String {
        val normalized = baseUrl.trim().removeSuffix("/")
        return if (normalized.endsWith("/responses")) normalized else "$normalized/responses"
    }

    fun testHealth(config: AIProviderConfig, startTime: Long): com.example.model.HealthTestResult {
        val session = OpenAIOAuthManager.decodeStoredSession(config.apiKey)
            ?: return com.example.model.HealthTestResult(
                success = false,
                statusCode = 401,
                latencyMs = 0,
                message = "Codex OAuth login required. Tap 'Login with ChatGPT OAuth'."
            )

        val body = buildRequestBody(
            model = DEFAULT_MODEL,
            instructions = "Reply with exactly OK.",
            history = emptyList(),
            userMessage = "Health check",
            includeTools = false,
            reasoningEffort = "high"
        )
        return executeRequest(config, session, body).fold(
            onSuccess = { agentResponse ->
                val latency = System.currentTimeMillis() - startTime
                val returnedOk = agentResponse.replyText.trim().trimEnd('.').equals("OK", ignoreCase = true)
                if (returnedOk) {
                    com.example.model.HealthTestResult(
                        success = true,
                        statusCode = 200,
                        latencyMs = latency,
                        message = "Codex OAuth connected",
                        details = "${DEFAULT_MODEL} returned verified model text in ${latency}ms"
                    )
                } else {
                    com.example.model.HealthTestResult(
                        success = false,
                        statusCode = 502,
                        latencyMs = latency,
                        message = "Codex connected, but the model-text verification failed."
                    )
                }
            },
            onFailure = { error ->
                com.example.model.HealthTestResult(
                    success = false,
                    statusCode = extractHttpCode(error.message),
                    latencyMs = System.currentTimeMillis() - startTime,
                    message = error.message ?: "Codex health test failed"
                )
            }
        )
    }

    fun sendAgentChat(
        config: AIProviderConfig,
        conversationHistory: List<ChatMessage>,
        userMessage: String,
        systemPrompt: String,
        reasoningEffort: String,
        onTextDelta: (String) -> Unit = {}
    ): Result<AgentResponse> {
        val session = OpenAIOAuthManager.decodeStoredSession(config.apiKey)
            ?: return Result.failure(
                Exception("Codex OAuth login ontbreekt of is ongeldig. Log opnieuw in via Settings.")
            )
        val body = buildRequestBody(
            model = DEFAULT_MODEL,
            instructions = systemPrompt + "\n\nUse the supplied native function tools whenever an operation is required. Do not print pseudo action tags. You may call multiple independent tools in parallel. Tool calls execute directly and their outputs return to you for continued reasoning.",
            history = conversationHistory,
            userMessage = userMessage,
            includeTools = true,
            reasoningEffort = validatedDirectEffort(reasoningEffort)
        )
        return executeRequest(config, session, body, onTextDelta)
    }

    fun cleanupDictation(
        config: AIProviderConfig,
        rawTranscript: String,
        cleanupPrompt: String
    ): Result<String> {
        if (rawTranscript.isBlank()) return Result.failure(Exception("Raw transcript is empty."))
        val session = OpenAIOAuthManager.decodeStoredSession(config.apiKey)
            ?: return Result.failure(
                Exception("Codex OAuth login ontbreekt of is ongeldig. Log opnieuw in via Settings.")
            )
        val body = buildRequestBody(
            model = DEFAULT_MODEL,
            instructions = cleanupPrompt,
            history = emptyList(),
            userMessage = rawTranscript,
            includeTools = false,
            reasoningEffort = DICTATION_REASONING_EFFORT
        )
        return executeRequest(config, session, body).map { response ->
            response.replyText.trim().also { cleaned ->
                require(cleaned.isNotBlank()) { "Codex returned an empty cleaned transcript." }
            }
        }
    }

    fun analyzeMemoryTurn(
        config: AIProviderConfig,
        evidenceJson: String,
        existingMemories: List<com.example.model.AgentMemory>
    ): Result<MemoryDecision> {
        if (evidenceJson.isBlank()) return Result.failure(Exception("Memory evidence is empty."))
        val session = OpenAIOAuthManager.decodeStoredSession(config.apiKey)
            ?: return Result.failure(Exception("Codex OAuth login is required for durable learning."))
        val existing = existingMemories.joinToString("\n") { memory ->
            "${memory.stableKey}|${memory.source}|pinned=${memory.isPinned}|${memory.category}|${memory.title}|${memory.content}|${memory.importance}"
        }
        val body = buildRequestBody(
            model = DEFAULT_MODEL,
            instructions = """
You are the durable-memory analyzer. Use only explicit evidence in the supplied turn JSON.
Return exactly one JSON object and nothing else.
Allowed decisions:
- {"action":"IGNORE","reason":"..."}
- {"action":"CREATE","title":"...","content":"...","category":"...","importance":1,"evidence_quote":"exact substring from evidence"}
- {"action":"UPDATE","title":"...","content":"...","category":"...","importance":1,"evidence_quote":"exact substring from evidence"}
Never invent a generic preference, workflow, configuration, or fact. Transient chat is IGNORE.
CREATE is only for a new durable owner directive, preference, configuration, project fact, or reusable workflow.
UPDATE is only for an existing reflection-sourced, unpinned memory with the same category and title.
Manual or pinned memories are immutable and must produce IGNORE.
Preserve the category and importance you actually infer; importance must be 1 through 5.

EXISTING MEMORIES:
$existing
""".trimIndent(),
            history = emptyList(),
            userMessage = evidenceJson,
            includeTools = false,
            reasoningEffort = "max"
        )
        return executeRequest(config, session, body).mapCatching { response ->
            parseMemoryDecision(response.replyText, evidenceJson)
        }
    }

    /** Continues the stateless Codex tool loop after native tools have completed. */
    fun continueAgentChat(
        config: AIProviderConfig,
        conversationHistory: List<ChatMessage>,
        systemPrompt: String,
        reasoningEffort: String,
        onTextDelta: (String) -> Unit = {}
    ): Result<AgentResponse> {
        val session = OpenAIOAuthManager.decodeStoredSession(config.apiKey)
            ?: return Result.failure(Exception("Codex OAuth login ontbreekt of is ongeldig. Log opnieuw in via Settings."))
        val body = buildRequestBody(
            model = DEFAULT_MODEL,
            instructions = systemPrompt + "\n\nContinue from the completed function-call outputs. Use another native tool when needed; otherwise give the final answer.",
            history = conversationHistory,
            userMessage = "",
            includeTools = true,
            reasoningEffort = validatedDirectEffort(reasoningEffort)
        )
        return executeRequest(config, session, body, onTextDelta)
    }

    internal fun buildRequestBody(
        model: String,
        instructions: String,
        history: List<ChatMessage>,
        userMessage: String,
        includeTools: Boolean,
        reasoningEffort: String
    ): JSONObject = JSONObject().apply {
        put("model", model)
        put("instructions", instructions)
        put("store", false)
        put("stream", true)
        put("tool_choice", "auto")
        put("parallel_tool_calls", true)
        put("include", JSONArray().put("reasoning.encrypted_content"))
        put("reasoning", JSONObject().apply {
            put("effort", reasoningEffort)
            put("summary", "auto")
        })
        val input = JSONArray()
        history.forEach { message ->
            when (message.role) {
                MessageRole.USER -> input.put(messageItem("user", "input_text", message.text))
                MessageRole.MODEL -> {
                    val isSyntheticToolNotice = message.actions.isNotEmpty() &&
                        message.text.startsWith("Codex heeft ")
                    if (message.text.isNotBlank() && !isSyntheticToolNotice) {
                        input.put(messageItem("assistant", "output_text", message.text))
                    }
                    message.actions.forEach { action ->
                        if (action.codexCallId.isBlank()) return@forEach
                        input.put(JSONObject().apply {
                            put("type", "function_call")
                            put("call_id", action.codexCallId)
                            put("name", actionFunctionName(action.type))
                            put("arguments", action.codexArgumentsJson.ifBlank { actionArguments(action).toString() })
                        })
                        if (action.status == com.example.model.ActionStatus.COMPLETED ||
                            action.status == com.example.model.ActionStatus.FAILED
                        ) {
                            if (action.status == com.example.model.ActionStatus.COMPLETED) {
                                requireNotNull(action.exitCode) {
                                    "Completed tool ${action.codexCallId} has no authoritative exitCode."
                                }
                            }
                            input.put(JSONObject().apply {
                                put("type", "function_call_output")
                                put("call_id", action.codexCallId)
                                put("output", JSONObject().apply {
                                    put("success", action.status == com.example.model.ActionStatus.COMPLETED)
                                    put("exit_code", action.exitCode ?: JSONObject.NULL)
                                    put("output", action.output)
                                }.toString())
                            })
                        }
                    }
                }
                else -> Unit
            }
        }
        if (userMessage.isNotBlank()) input.put(messageItem("user", "input_text", userMessage))
        put("input", input)
        if (includeTools) put("tools", nativeTools())
    }

    private fun messageItem(role: String, contentType: String, text: String) = JSONObject().apply {
        put("type", "message")
        put("role", role)
        put("content", JSONArray().put(JSONObject().put("type", contentType).put("text", text)))
    }

    private fun executeRequest(
        config: AIProviderConfig,
        session: CodexOAuthSession,
        body: JSONObject,
        onTextDelta: (String) -> Unit = {}
    ): Result<AgentResponse> {
        val request = Request.Builder()
            .url(responsesEndpoint(config.baseUrl.ifBlank { DEFAULT_BASE_URL }))
            .addHeader("Authorization", "Bearer ${session.accessToken}")
            .addHeader("ChatGPT-Account-ID", session.accountId)
            .addHeader("originator", "codex_cli_rs")
            .addHeader("OpenAI-Beta", "responses=experimental")
            .addHeader("Accept", "text/event-stream")
            .addHeader("Content-Type", "application/json")
            .addHeader("User-Agent", "codex_cli_rs/0.142.5 (Android)")
            .addHeader("session_id", UUID.randomUUID().toString())
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()

        return try {
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    val rawBody = response.body?.string().orEmpty()
                    return Result.failure(
                        Exception("Codex HTTP ${response.code}: ${extractError(rawBody)}")
                    )
                }
                val responseBody = response.body
                    ?: return Result.failure(Exception("Codex returned an empty HTTP body."))
                parseStreamingResponse(responseBody.source(), onTextDelta)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun parseStreamingResponse(
        source: okio.BufferedSource,
        onTextDelta: (String) -> Unit
    ): Result<AgentResponse> {
        val rawBody = StringBuilder()
        val streamedText = StringBuilder()
        val completedItems = linkedMapOf<String, JSONObject>()
        var finalResponse: JSONObject? = null
        var streamError: String? = null

        return try {
            while (!source.exhausted()) {
                val line = source.readUtf8Line() ?: break
                rawBody.append(line).append('\n')
                if (!line.startsWith("data:")) continue
                val data = line.removePrefix("data:").trim()
                if (data.isBlank() || data == "[DONE]") continue
                val event = try {
                    JSONObject(data)
                } catch (error: Exception) {
                    return Result.failure(Exception("Codex stream contained invalid JSON: ${error.message}"))
                }
                when (event.optString("type")) {
                    "response.output_text.delta" -> {
                        val delta = event.optString("delta")
                        streamedText.append(delta)
                        if (delta.isNotEmpty()) onTextDelta(delta)
                    }
                    "response.output_text.done" -> if (streamedText.isEmpty()) {
                        val text = event.optString("text")
                        streamedText.append(text)
                        if (text.isNotEmpty()) onTextDelta(text)
                    }
                    "response.output_item.done" -> {
                        val item = event.optJSONObject("item")
                            ?: return Result.failure(Exception("Codex output-item event omitted the item."))
                        completedItems[item.optString("id", UUID.randomUUID().toString())] = item
                    }
                    "response.completed" -> finalResponse = event.optJSONObject("response")
                        ?: return Result.failure(Exception("Codex completed event omitted the response."))
                    "response.failed" -> streamError = event.optJSONObject("response")
                        ?.optJSONObject("error")?.optString("message").orEmpty().ifBlank { data }
                    "error" -> streamError = event.optString("message").ifBlank {
                        event.optJSONObject("error")?.optString("message").orEmpty()
                    }
                }
            }

            if (!streamError.isNullOrBlank()) {
                return Result.failure(Exception("Codex stream failed: $streamError"))
            }
            val completed = finalResponse
                ?: return Result.failure(Exception("Codex stream disconnected before response.completed."))
            val status = completed.optString("status")
            if (status.isNotBlank() && status != "completed") {
                return Result.failure(Exception("Codex response status was '$status'."))
            }
            completed.optJSONArray("output")?.let { finalOutput ->
                for (index in 0 until finalOutput.length()) {
                    val item = finalOutput.optJSONObject(index) ?: continue
                    completedItems[item.optString("id", "final_$index")] = item
                }
            }
            validatedResponse(
                parseOutputItems(
                    items = completedItems.values,
                    rawBody = rawBody.toString(),
                    responseId = completed.optString("id"),
                    streamedText = streamedText.toString()
                )
            )
        } catch (error: Exception) {
            Result.failure(Exception("Codex stream hard failure: ${error.localizedMessage ?: error.message}", error))
        }
    }

    internal fun parseResponse(rawBody: String): Result<AgentResponse> {
        return try {
            val trimmed = rawBody.trim()
            if (trimmed.startsWith("{")) {
                val response = JSONObject(trimmed)
                val status = response.optString("status")
                if (status.isNotBlank() && status != "completed") {
                    return Result.failure(Exception("Codex response status was '$status'."))
                }
                return validatedResponse(parseCompletedResponse(response, rawBody))
            }

            val text = StringBuilder()
            val completedItems = linkedMapOf<String, JSONObject>()
            var finalResponse: JSONObject? = null
            var streamError: String? = null

            rawBody.lineSequence().forEach { line ->
                if (!line.startsWith("data:")) return@forEach
                val data = line.removePrefix("data:").trim()
                if (data.isBlank() || data == "[DONE]") return@forEach
                val event = try { JSONObject(data) } catch (_: Exception) { return@forEach }
                when (event.optString("type")) {
                    "response.output_text.delta" -> text.append(event.optString("delta"))
                    "response.output_text.done" -> if (text.isEmpty()) text.append(event.optString("text"))
                    "response.output_item.done" -> {
                        val item = event.optJSONObject("item")
                        if (item != null) completedItems[item.optString("id", UUID.randomUUID().toString())] = item
                    }
                    "response.completed" -> finalResponse = event.optJSONObject("response")
                    "response.failed" -> streamError = event.optJSONObject("response")
                        ?.optJSONObject("error")?.optString("message")
                    "error" -> streamError = event.optString("message").ifBlank {
                        event.optJSONObject("error")?.optString("message").orEmpty()
                    }
                }
            }

            if (!streamError.isNullOrBlank()) {
                Result.failure(Exception("Codex stream failed: $streamError"))
            } else if (finalResponse == null) {
                Result.failure(Exception("Codex stream disconnected before response.completed."))
            } else {
                val finalOutput = finalResponse?.optJSONArray("output")
                if (finalOutput != null) {
                    for (i in 0 until finalOutput.length()) {
                        val item = finalOutput.optJSONObject(i) ?: continue
                        completedItems[item.optString("id", "final_$i")] = item
                    }
                }
                validatedResponse(
                    parseOutputItems(
                        items = completedItems.values,
                        rawBody = rawBody,
                        responseId = finalResponse?.optString("id").orEmpty(),
                        streamedText = text.toString()
                    )
                )
            }
        } catch (e: Exception) {
            Result.failure(Exception("Codex response kon niet worden verwerkt: ${e.message}"))
        }
    }

    private fun parseCompletedResponse(response: JSONObject, rawBody: String): AgentResponse {
        val output = response.optJSONArray("output") ?: JSONArray()
        val items = buildList {
            for (i in 0 until output.length()) output.optJSONObject(i)?.let(::add)
        }
        return parseOutputItems(items, rawBody, response.optString("id"), "")
    }

    private fun parseOutputItems(
        items: Collection<JSONObject>,
        rawBody: String,
        responseId: String,
        streamedText: String
    ): AgentResponse {
        val finalText = StringBuilder()
        val commentaryText = StringBuilder()
        val actions = mutableListOf<AgentAction>()
        items.forEach { item ->
            when (item.optString("type")) {
                "message" -> {
                    val target = if (item.optString("phase") == "commentary") commentaryText else finalText
                    val content = item.optJSONArray("content") ?: JSONArray()
                    for (j in 0 until content.length()) {
                        val part = content.optJSONObject(j) ?: continue
                        if (part.optString("type") == "output_text") target.append(part.optString("text"))
                    }
                }
                "function_call" -> toolItemToAction(item)?.let(actions::add)
            }
        }
        val reply = finalText.toString().ifBlank { streamedText }.ifBlank { commentaryText.toString() }.ifBlank {
            if (actions.isNotEmpty()) "Running ${actions.size} verified native tool call(s)."
            else ""
        }
        return AgentResponse(reply, actions, rawBody, responseId)
    }

    private fun validatedResponse(response: AgentResponse): Result<AgentResponse> {
        return if (response.replyText.isBlank() && response.actions.isEmpty()) {
            Result.failure(Exception("Codex stream completed without assistant text or tool calls."))
        } else {
            Result.success(response)
        }
    }

    private fun toolItemToAction(item: JSONObject): AgentAction? {
        if (item.optString("type") != "function_call") return null
        val name = item.optString("name")
        val rawArguments = item.optString("arguments", "{}")
        val args = try {
            JSONObject(rawArguments)
        } catch (error: Exception) {
            throw IllegalArgumentException("Incomplete tool JSON for '$name': ${error.message}")
        }
        val callId = item.optString("call_id").ifBlank { item.optString("id") }
        require(callId.isNotBlank()) { "Tool '$name' omitted call_id." }
        return when (name) {
            "run_termux_command" -> AgentAction(
                type = ActionType.RUN_TERMUX_COMMAND,
                commandOrPath = args.optString("command"),
                description = args.optString("description", "Run command in Termux"),
                codexCallId = callId,
                codexArgumentsJson = rawArguments
            )
            "run_root_command" -> AgentAction(
                type = ActionType.RUN_ROOT_COMMAND,
                commandOrPath = args.optString("command"),
                description = args.optString("description", "Run command as root"),
                codexCallId = callId,
                codexArgumentsJson = rawArguments
            )
            "spawn_termux_agent" -> AgentAction(
                type = ActionType.SPAWN_TERMUX_AGENT,
                commandOrPath = args.optString("command"),
                payload = args.optString("log_path"),
                description = args.optString("name", "Termux agent"),
                codexCallId = callId,
                codexArgumentsJson = rawArguments
            )
            "run_sandbox_command" -> AgentAction(
                type = ActionType.RUN_SANDBOX_COMMAND,
                commandOrPath = args.optString("command"),
                description = args.optString("description", "Run command in app sandbox"),
                codexCallId = callId,
                codexArgumentsJson = rawArguments
            )
            "read_file" -> AgentAction(type = ActionType.READ_FILE, commandOrPath = args.optString("path"), description = "Read file", codexCallId = callId, codexArgumentsJson = rawArguments)
            "write_file" -> AgentAction(
                type = ActionType.WRITE_FILE,
                commandOrPath = args.optString("path"),
                payload = args.optString("content"),
                description = "Write file",
                codexCallId = callId,
                codexArgumentsJson = rawArguments
            )
            "list_directory" -> AgentAction(type = ActionType.LIST_DIRECTORY, commandOrPath = args.optString("path"), description = "List directory", codexCallId = callId, codexArgumentsJson = rawArguments)
            "delete_file" -> AgentAction(type = ActionType.DELETE_FILE, commandOrPath = args.optString("path"), description = "Delete file", codexCallId = callId, codexArgumentsJson = rawArguments)
            "save_memory" -> AgentAction(
                type = ActionType.SAVE_MEMORY,
                commandOrPath = args.optString("title"),
                payload = args.optString("content"),
                description = "[${args.optString("category", "Learned Commands")}] (Importance: ${args.optInt("importance", 3)})",
                codexCallId = callId,
                codexArgumentsJson = rawArguments
            )
            "create_script" -> AgentAction(
                type = ActionType.CREATE_SCRIPT,
                commandOrPath = args.optString("name"),
                payload = args.optString("code"),
                description = "${args.optString("name")} (${args.optString("language", "bash")}) - ${args.optString("description", "Codex-generated script")}",
                codexCallId = callId,
                codexArgumentsJson = rawArguments
            )
            "system_diagnostics" -> AgentAction(type = ActionType.SYSTEM_DIAGNOSTICS, commandOrPath = "system", description = "Inspect device diagnostics", codexCallId = callId, codexArgumentsJson = rawArguments)
            else -> throw IllegalArgumentException("Unsupported Codex tool '$name'.")
        }.also { action ->
            require(action.commandOrPath.isNotBlank()) { "Tool '$name' omitted its required target or command." }
        }
    }

    private fun actionFunctionName(type: ActionType): String = when (type) {
        ActionType.RUN_TERMUX_COMMAND -> "run_termux_command"
        ActionType.RUN_ROOT_COMMAND -> "run_root_command"
        ActionType.SPAWN_TERMUX_AGENT -> "spawn_termux_agent"
        ActionType.RUN_SANDBOX_COMMAND -> "run_sandbox_command"
        ActionType.READ_FILE -> "read_file"
        ActionType.WRITE_FILE -> "write_file"
        ActionType.LIST_DIRECTORY -> "list_directory"
        ActionType.DELETE_FILE -> "delete_file"
        ActionType.SAVE_MEMORY -> "save_memory"
        ActionType.CREATE_SCRIPT -> "create_script"
        ActionType.SYSTEM_DIAGNOSTICS -> "system_diagnostics"
    }

    private fun actionArguments(action: AgentAction): JSONObject = when (action.type) {
        ActionType.RUN_TERMUX_COMMAND, ActionType.RUN_ROOT_COMMAND, ActionType.RUN_SANDBOX_COMMAND -> JSONObject()
            .put("command", action.commandOrPath).put("description", action.description)
        ActionType.SPAWN_TERMUX_AGENT -> JSONObject()
            .put("name", action.description).put("command", action.commandOrPath).put("log_path", action.payload)
        ActionType.READ_FILE, ActionType.LIST_DIRECTORY, ActionType.DELETE_FILE -> JSONObject()
            .put("path", action.commandOrPath)
        ActionType.WRITE_FILE -> JSONObject().put("path", action.commandOrPath).put("content", action.payload)
        ActionType.SAVE_MEMORY -> JSONObject().put("title", action.commandOrPath).put("content", action.payload)
            .put("category", "Learned Commands").put("importance", 3)
        ActionType.CREATE_SCRIPT -> JSONObject().put("name", action.commandOrPath).put("language", "bash")
            .put("description", action.description).put("code", action.payload)
        ActionType.SYSTEM_DIAGNOSTICS -> JSONObject()
    }

    private fun nativeTools(): JSONArray = JSONArray().apply {
        put(tool("run_termux_command", "Run a shell command in the installed Termux environment.", objSchema(
            "command" to stringProp("Complete bash command"),
            "description" to stringProp("Short user-facing purpose")
        ), listOf("command", "description")))
        put(tool("run_root_command", "Run a shell command as Android root through su on this rooted test device.", objSchema(
            "command" to stringProp("Complete root shell command"),
            "description" to stringProp("Short purpose of the root operation")
        ), listOf("command", "description")))
        put(tool("spawn_termux_agent", "Start a detached long-running AI agent or worker inside Termux and return its PID and log path.", objSchema(
            "name" to stringProp("Short stable agent name"),
            "command" to stringProp("Complete Termux command that starts the agent or worker"),
            "log_path" to stringProp("Absolute Termux path for stdout and stderr")
        ), listOf("name", "command", "log_path")))
        put(tool("run_sandbox_command", "Run a shell command in the Android app sandbox.", objSchema(
            "command" to stringProp("Complete shell command"),
            "description" to stringProp("Short user-facing purpose")
        ), listOf("command", "description")))
        put(tool("read_file", "Read a text file.", objSchema("path" to stringProp("Absolute file path")), listOf("path")))
        put(tool("write_file", "Write complete text content to a file.", objSchema(
            "path" to stringProp("Absolute file path"), "content" to stringProp("Complete file content")
        ), listOf("path", "content")))
        put(tool("list_directory", "List a directory.", objSchema("path" to stringProp("Absolute directory path")), listOf("path")))
        put(tool("delete_file", "Delete one file.", objSchema("path" to stringProp("Absolute file path")), listOf("path")))
        put(tool("save_memory", "Save a durable workflow fact or user preference.", objSchema(
            "title" to stringProp("Short title"),
            "content" to stringProp("Durable fact or instruction"),
            "category" to stringProp("Memory category"),
            "importance" to JSONObject().put("type", "integer").put("minimum", 1).put("maximum", 5)
        ), listOf("title", "content", "category", "importance")))
        put(tool("create_script", "Save a reusable automation script.", objSchema(
            "name" to stringProp("Filename"),
            "language" to stringProp("Script language"),
            "description" to stringProp("What the script does"),
            "code" to stringProp("Complete script source")
        ), listOf("name", "language", "description", "code")))
        put(tool("system_diagnostics", "Inspect Android, storage, memory and Termux availability.", objSchema(), emptyList()))
    }

    private fun tool(name: String, description: String, schema: JSONObject, required: List<String>) = JSONObject().apply {
        put("type", "function")
        put("name", name)
        put("description", description)
        schema.put("required", JSONArray(required))
        put("parameters", schema)
        put("strict", true)
    }

    private fun objSchema(vararg properties: Pair<String, JSONObject>) = JSONObject().apply {
        put("type", "object")
        put("properties", JSONObject().apply { properties.forEach { (name, schema) -> put(name, schema) } })
        put("additionalProperties", false)
    }

    private fun stringProp(description: String) = JSONObject().put("type", "string").put("description", description)

    private fun extractError(rawBody: String): String {
        return try {
            val obj = JSONObject(rawBody)
            obj.optJSONObject("error")?.optString("message")
                ?.takeIf { it.isNotBlank() }
                ?: obj.optString("detail").ifBlank { rawBody.take(300) }
        } catch (_: Exception) {
            rawBody.take(300).ifBlank { "Empty response" }
        }
    }

    private fun extractHttpCode(message: String?): Int = Regex("HTTP (\\d{3})")
        .find(message.orEmpty())?.groupValues?.getOrNull(1)?.toIntOrNull() ?: -1

    internal fun validatedDirectEffort(value: String): String {
        require(value in setOf("high", "xhigh", "max")) {
            "Unsupported direct reasoning effort '$value'. No fallback was applied."
        }
        return value
    }

    internal fun parseMemoryDecision(raw: String, evidenceJson: String): MemoryDecision {
        val trimmed = raw.trim()
        require(trimmed.startsWith("{") && trimmed.endsWith("}")) {
            "Memory analyzer returned non-JSON output."
        }
        val json = JSONObject(trimmed)
        val action = json.optString("action")
        require(action in setOf("CREATE", "UPDATE", "IGNORE")) {
            "Memory analyzer returned unsupported action '$action'."
        }
        if (action == "IGNORE") {
            return MemoryDecision(action = action, reason = json.optString("reason"))
        }
        val title = json.optString("title")
        val content = json.optString("content")
        val category = json.optString("category")
        val importance = json.optInt("importance", 0)
        val evidenceQuote = json.optString("evidence_quote")
        require(title.isNotBlank() && content.isNotBlank() && category.isNotBlank()) {
            "Memory analyzer returned blank required fields."
        }
        require(importance in 1..5) { "Memory analyzer importance is outside 1..5." }
        require(evidenceQuote.isNotBlank() && evidenceJson.contains(evidenceQuote)) {
            "MEMORY_HALLUCINATION: evidence_quote is not an exact substring of the persisted turn."
        }
        val contentTokens = groundingTokens(content)
        val quoteTokens = groundingTokens(evidenceQuote)
        val requiredOverlap = minOf(3, contentTokens.size).coerceAtLeast(1)
        require(contentTokens.intersect(quoteTokens).size >= requiredOverlap) {
            "MEMORY_HALLUCINATION: learned content is not lexically grounded in its evidence quote."
        }
        return MemoryDecision(action, title, content, category, importance, evidenceQuote)
    }

    private fun groundingTokens(value: String): Set<String> = value.lowercase()
        .split(Regex("[^\\p{L}\\p{N}_./-]+"))
        .filter { it.length >= 3 }
        .toSet()
}

data class MemoryDecision(
    val action: String,
    val title: String = "",
    val content: String = "",
    val category: String = "",
    val importance: Int = 0,
    val evidenceQuote: String = "",
    val reason: String = ""
)
