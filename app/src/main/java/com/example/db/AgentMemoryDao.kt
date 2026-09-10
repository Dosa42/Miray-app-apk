package com.example.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.example.model.AgentMemory
import kotlinx.coroutines.flow.Flow

@Dao
interface AgentMemoryDao {
    @Query("SELECT * FROM agent_memories ORDER BY importance DESC, updatedAt DESC")
    fun getAllMemories(): Flow<List<AgentMemory>>

    @Query("SELECT * FROM agent_memories ORDER BY importance DESC, updatedAt DESC")
    suspend fun getAllMemoriesList(): List<AgentMemory>

    @Query("SELECT * FROM agent_memories WHERE stableKey = :stableKey LIMIT 1")
    suspend fun getByStableKey(stableKey: String): AgentMemory?

    @Query("SELECT * FROM agent_memories WHERE id = :id LIMIT 1")
    suspend fun getById(id: Long): AgentMemory?

    @Query("SELECT * FROM agent_memories WHERE category = :category ORDER BY updatedAt DESC")
    fun getMemoriesByCategory(category: String): Flow<List<AgentMemory>>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertMemory(memory: AgentMemory): Long

    @Update
    suspend fun updateMemory(memory: AgentMemory)

    @Query("DELETE FROM agent_memories WHERE id = :id")
    suspend fun deleteMemoryById(id: Long)

    @Query("DELETE FROM agent_memories")
    suspend fun clearAllMemories()
}
