package com.example.model

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "automation_scripts")
data class AutomationScript(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val language: String, // "bash", "sh", "python", "node", "lua", "shell"
    val code: String,
    val description: String = "",
    val tags: String = "",
    val executionCount: Int = 0,
    val lastExecuted: Long? = null,
    val createdAt: Long = System.currentTimeMillis()
)
