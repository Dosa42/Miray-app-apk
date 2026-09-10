package com.example.model

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "chat_message_entities")
data class ChatMessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val role: String, // "USER", "MODEL", "SYSTEM", "TOOL"
    val text: String,
    val actionsJson: String = "[]",
    val turnId: String = "",
    val isComplete: Boolean = true,
    val rawResponse: String = "",
    val isError: Boolean = false,
    val timestamp: Long = System.currentTimeMillis()
)
