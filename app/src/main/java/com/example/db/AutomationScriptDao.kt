package com.example.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.example.model.AutomationScript
import kotlinx.coroutines.flow.Flow

@Dao
interface AutomationScriptDao {
    @Query("SELECT * FROM automation_scripts ORDER BY createdAt DESC")
    fun getAllScripts(): Flow<List<AutomationScript>>

    @Query("SELECT * FROM automation_scripts WHERE id = :id LIMIT 1")
    suspend fun getScriptById(id: Long): AutomationScript?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertScript(script: AutomationScript): Long

    @Update
    suspend fun updateScript(script: AutomationScript)

    @Query("UPDATE automation_scripts SET executionCount = executionCount + 1, lastExecuted = :timestamp WHERE id = :id")
    suspend fun recordExecution(id: Long, timestamp: Long = System.currentTimeMillis())

    @Query("DELETE FROM automation_scripts WHERE id = :id")
    suspend fun deleteScriptById(id: Long)
}
