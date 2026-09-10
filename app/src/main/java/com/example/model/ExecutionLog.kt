package com.example.model

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "execution_logs")
data class ExecutionLog(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val command: String,
    val target: String, // "TERMUX" or "SANDBOX" or "FILE_IO"
    val exitCode: Int,
    val output: String,
    val durationMs: Long,
    val success: Boolean,
    val timestamp: Long = System.currentTimeMillis()
)
