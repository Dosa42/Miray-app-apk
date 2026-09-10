package com.example.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.model.ExecutionLog
import kotlinx.coroutines.flow.Flow

@Dao
interface ExecutionLogDao {
    @Query("SELECT * FROM execution_logs ORDER BY timestamp DESC LIMIT 100")
    fun getRecentLogs(): Flow<List<ExecutionLog>>

    @Query("SELECT * FROM execution_logs ORDER BY timestamp DESC LIMIT 20")
    suspend fun getRecentLogsList(): List<ExecutionLog>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertLog(log: ExecutionLog): Long

    @Query("DELETE FROM execution_logs")
    suspend fun clearLogs()
}
