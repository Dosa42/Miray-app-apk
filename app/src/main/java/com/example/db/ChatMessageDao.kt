package com.example.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.model.ChatMessageEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ChatMessageDao {
    @Query("SELECT * FROM chat_message_entities ORDER BY timestamp ASC")
    fun getAllMessages(): Flow<List<ChatMessageEntity>>

    @Query("SELECT * FROM chat_message_entities ORDER BY timestamp ASC, id ASC")
    suspend fun getAllMessagesList(): List<ChatMessageEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMessage(message: ChatMessageEntity): Long

    @Query("SELECT * FROM chat_message_entities WHERE turnId = :turnId ORDER BY timestamp ASC, id ASC")
    suspend fun getMessagesForTurn(turnId: String): List<ChatMessageEntity>

    @Query("DELETE FROM chat_message_entities")
    suspend fun clearAllMessages()

    @Query("DELETE FROM chat_message_entities WHERE id = :id")
    suspend fun deleteMessageById(id: Long)
}
