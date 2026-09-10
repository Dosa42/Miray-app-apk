package com.example.db

import androidx.room.withTransaction
import com.example.model.AgentMemory
import java.security.MessageDigest

data class MemoryUpsertResult(
    val operation: String,
    val memory: AgentMemory?
)

class MemoryRepository(private val database: AppDatabase) {
    private val dao = database.agentMemoryDao()

    suspend fun insertManual(memory: AgentMemory): AgentMemory = database.withTransaction {
        val stableKey = memory.stableKey.ifBlank { stableKey(memory.category, memory.title) }
        check(dao.getByStableKey(stableKey) == null) { "Duplicate memory '$stableKey' was not inserted." }
        val prepared = memory.copy(stableKey = stableKey, source = "manual")
        val id = dao.insertMemory(prepared)
        dao.getById(id) ?: error("Manual memory commit verification failed for id $id")
    }

    suspend fun insertAgentTool(memory: AgentMemory): AgentMemory = database.withTransaction {
        require(memory.category.isNotBlank() && memory.title.isNotBlank() && memory.content.isNotBlank()) {
            "Agent tool memory fields cannot be blank."
        }
        require(memory.importance in 1..5) { "Agent tool memory importance must be 1 through 5." }
        val stableKey = memory.stableKey.ifBlank { stableKey(memory.category, memory.title) }
        check(dao.getByStableKey(stableKey) == null) { "Duplicate memory '$stableKey' was not inserted." }
        val prepared = memory.copy(stableKey = stableKey, source = "agent_tool", isPinned = false)
        val id = dao.insertMemory(prepared)
        dao.getById(id) ?: error("Agent tool memory commit verification failed for id $id")
    }

    suspend fun upsertLearned(
        operation: String,
        title: String,
        content: String,
        category: String,
        importance: Int
    ): MemoryUpsertResult = database.withTransaction {
        require(operation == "CREATE" || operation == "UPDATE") { "Unsupported memory operation '$operation'." }
        require(title.isNotBlank() && content.isNotBlank() && category.isNotBlank()) { "Learned memory fields cannot be blank." }
        require(importance in 1..5) { "Memory importance must be 1 through 5." }
        val key = stableKey(category, title)
        val existing = dao.getByStableKey(key)

        if (existing != null && (existing.isPinned || existing.source == "manual")) {
            return@withTransaction MemoryUpsertResult("IGNORE_PROTECTED", existing)
        }
        if (existing != null && operation == "CREATE" &&
            existing.content == content && existing.category == category && existing.importance == importance
        ) {
            return@withTransaction MemoryUpsertResult("IGNORE_DUPLICATE", existing)
        }

        val committed = if (existing == null) {
            check(operation == "CREATE") { "UPDATE requested for missing memory '$key'." }
            val id = dao.insertMemory(
                AgentMemory(
                    category = category,
                    title = title,
                    content = content,
                    importance = importance,
                    stableKey = key,
                    isPinned = false,
                    source = "reflection"
                )
            )
            dao.getById(id) ?: error("Learned memory CREATE commit verification failed for id $id")
        } else {
            check(operation == "UPDATE") { "CREATE would duplicate existing memory '$key'." }
            val updated = existing.copy(
                category = category,
                title = title,
                content = content,
                importance = importance,
                updatedAt = System.currentTimeMillis()
            )
            dao.updateMemory(updated)
            dao.getById(existing.id)?.also { verified ->
                check(verified.content == content && verified.category == category && verified.importance == importance) {
                    "Learned memory UPDATE verification failed for id ${existing.id}"
                }
            } ?: error("Learned memory UPDATE disappeared for id ${existing.id}")
        }
        MemoryUpsertResult(operation, committed)
    }

    companion object {
        fun stableKey(category: String, title: String): String {
            val normalized = "${category.trim().lowercase()}|${title.trim().lowercase()}"
            return MessageDigest.getInstance("SHA-256")
                .digest(normalized.toByteArray())
                .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
        }
    }
}
