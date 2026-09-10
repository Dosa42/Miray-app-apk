package com.example.gemini

import com.example.model.AgentAction

data class AgentResponse(
    val replyText: String,
    val actions: List<AgentAction>,
    val rawResponse: String = "",
    val responseId: String = ""
)
