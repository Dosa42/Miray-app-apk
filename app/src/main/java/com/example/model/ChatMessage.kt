package com.example.model

import java.util.UUID

enum class MessageRole {
    USER,
    MODEL,
    SYSTEM,
    TOOL
}

enum class ActionType {
    RUN_TERMUX_COMMAND,
    RUN_ROOT_COMMAND,
    SPAWN_TERMUX_AGENT,
    RUN_SANDBOX_COMMAND,
    READ_FILE,
    WRITE_FILE,
    LIST_DIRECTORY,
    DELETE_FILE,
    SAVE_MEMORY,
    CREATE_SCRIPT,
    SYSTEM_DIAGNOSTICS
}

enum class ActionStatus {
    PENDING,
    RUNNING,
    COMPLETED,
    FAILED
}

data class AgentAction(
    val id: String = UUID.randomUUID().toString(),
    val type: ActionType,
    val commandOrPath: String,
    val payload: String = "",
    val description: String = "",
    val codexCallId: String = "",
    val codexArgumentsJson: String = "",
    var status: ActionStatus = ActionStatus.PENDING,
    var output: String = "",
    var exitCode: Int? = null
)

data class ChatMessage(
    val id: Long = 0,
    val role: MessageRole,
    val text: String,
    val actions: List<AgentAction> = emptyList(),
    val codexResponseId: String = "",
    val timestamp: Long = System.currentTimeMillis(),
    val isError: Boolean = false,
    val isThinking: Boolean = false
)
