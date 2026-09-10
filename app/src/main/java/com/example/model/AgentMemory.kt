package com.example.model

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "agent_memories",
    indices = [Index(value = ["stableKey"], unique = true)]
)
data class AgentMemory(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val category: String, // "Workflows", "Termux Config", "Preferences", "Learned Commands", "Project Insight"
    val title: String,
    val content: String,
    val importance: Int = 1, // 1 to 5
    val stableKey: String = "",
    val isPinned: Boolean = false,
    val source: String = "manual",
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)
