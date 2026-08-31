package com.example.data

import kotlinx.coroutines.flow.Flow

class MiraiRepository(private val dao: MiraiDao) {
    val allMessages: Flow<List<Message>> = dao.getAllMessages()
    
    suspend fun insertMessage(message: Message) = dao.insertMessage(message)

    val allHomeworkSessions: Flow<List<HomeworkSession>> = dao.getAllHomeworkSessions()

    suspend fun insertHomeworkSession(session: HomeworkSession): Long = dao.insertHomeworkSession(session)

    suspend fun getSessionById(id: Int): HomeworkSession? = dao.getSessionById(id)

    fun getMessagesForSession(sessionId: Int): Flow<List<HomeworkMessage>> = dao.getMessagesForSession(sessionId)

    suspend fun insertHomeworkMessage(message: HomeworkMessage) = dao.insertHomeworkMessage(message)
}
