package com.example.data

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "messages")
data class Message(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val senderId: String, // "abi" or "mirai"
    val text: String,
    val timestamp: Long = System.currentTimeMillis()
)

@Entity(tableName = "homework_sessions")
data class HomeworkSession(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val imageUri: String,
    val timestamp: Long = System.currentTimeMillis(),
    val isResolved: Boolean = false
)

@Entity(tableName = "homework_messages")
data class HomeworkMessage(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val sessionId: Int,
    val sender: String, // "mirai" or "ai"
    val text: String,
    val timestamp: Long = System.currentTimeMillis()
)

@Dao
interface MiraiDao {
    @Query("SELECT * FROM messages ORDER BY timestamp ASC")
    fun getAllMessages(): Flow<List<Message>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMessage(message: Message)

    @Query("SELECT * FROM homework_sessions ORDER BY timestamp DESC")
    fun getAllHomeworkSessions(): Flow<List<HomeworkSession>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertHomeworkSession(session: HomeworkSession): Long

    @Query("SELECT * FROM homework_sessions WHERE id = :id LIMIT 1")
    suspend fun getSessionById(id: Int): HomeworkSession?

    @Query("SELECT * FROM homework_messages WHERE sessionId = :sessionId ORDER BY timestamp ASC")
    fun getMessagesForSession(sessionId: Int): Flow<List<HomeworkMessage>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertHomeworkMessage(message: HomeworkMessage)
}

@Database(entities = [Message::class, HomeworkSession::class, HomeworkMessage::class], version = 1, exportSchema = false)
abstract class MiraiDatabase : RoomDatabase() {
    abstract fun miraiDao(): MiraiDao
}
