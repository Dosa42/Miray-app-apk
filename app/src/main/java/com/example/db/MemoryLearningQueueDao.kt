package com.example.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.model.MemoryLearningQueueEntity

@Dao
interface MemoryLearningQueueDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun enqueue(item: MemoryLearningQueueEntity): Long

    @Query("SELECT * FROM memory_learning_queue WHERE status = 'PENDING' ORDER BY createdAt ASC LIMIT 1")
    suspend fun nextPending(): MemoryLearningQueueEntity?

    @Query("SELECT * FROM memory_learning_queue WHERE turnKey = :turnKey LIMIT 1")
    suspend fun byTurnKey(turnKey: String): MemoryLearningQueueEntity?

    @Query("UPDATE memory_learning_queue SET status = :status, attemptCount = :attemptCount, lastError = :lastError, decision = :decision, updatedAt = :updatedAt WHERE id = :id")
    suspend fun updateOutcome(
        id: Long,
        status: String,
        attemptCount: Int,
        lastError: String,
        decision: String,
        updatedAt: Long = System.currentTimeMillis()
    ): Int

    @Query("SELECT COUNT(*) FROM memory_learning_queue WHERE status = 'PENDING'")
    suspend fun pendingCount(): Int
}
