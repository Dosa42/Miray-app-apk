package com.example.model

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "memory_learning_queue",
    indices = [Index(value = ["turnKey"], unique = true)]
)
data class MemoryLearningQueueEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val turnKey: String,
    val evidenceJson: String,
    val status: String = STATUS_PENDING,
    val attemptCount: Int = 0,
    val lastError: String = "",
    val decision: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
) {
    companion object {
        const val STATUS_PENDING = "PENDING"
        const val STATUS_COMPLETED = "COMPLETED"
        const val STATUS_HARD_FAILURE = "HARD_FAILURE"
    }
}
