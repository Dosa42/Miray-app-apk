package com.example.db

import com.example.model.AgentMemory

data class MemoryContext(
    val memories: List<AgentMemory>,
    val trace: String,
    val characterCount: Int
)

object MemoryContextBuilder {
    // Conservative preflight below the model's nominal context. Nothing is truncated to meet it.
    internal const val MAX_MEMORY_CHARACTERS = 2_000_000

    fun build(all: List<AgentMemory>, query: String): MemoryContext {
        val queryTokens = tokens(query)
        val used = linkedSetOf<Long>()
        val layers = mutableListOf<Pair<String, List<AgentMemory>>>()

        fun addLayer(name: String, candidates: List<AgentMemory>) {
            val unique = candidates.filter { used.add(it.id) }
            layers += name to unique
        }

        addLayer(
            "owner_directives_and_pinned",
            all.filter { it.isPinned || it.category.equals("Owner Directives", ignoreCase = true) }
        )

        val isSystemTask = queryTokens.any { it in SYSTEM_TERMS }
        addLayer(
            "system_termux_root_permission",
            if (isSystemTask) all.filter { memory ->
                val text = "${memory.category} ${memory.title} ${memory.content}".lowercase()
                SYSTEM_TERMS.any(text::contains)
            } else emptyList()
        )

        addLayer(
            "project_and_workflow",
            all.filter { memory ->
                (memory.category.contains("project", true) || memory.category.contains("workflow", true)) &&
                    relevance(memory, queryTokens) > 0
            }.sortedWith(compareByDescending<AgentMemory> { relevance(it, queryTokens) }.thenByDescending { it.importance })
        )

        addLayer(
            "traceable_long_history",
            all.filter { relevance(it, queryTokens) > 0 }
                .sortedWith(compareByDescending<AgentMemory> { relevance(it, queryTokens) }.thenByDescending { it.updatedAt })
        )

        val selected = mutableListOf<AgentMemory>()
        var total = 0
        val traceLines = mutableListOf<String>()
        layers.forEach { (name, memories) ->
            val sectionSize = memories.sumOf { memorySize(it) }
            if (total + sectionSize > MAX_MEMORY_CHARACTERS) {
                throw IllegalStateException(
                    "MEMORY_CONTEXT_OVERFLOW\nSection not fit: $name\n" +
                        "Current characters: $total\nSection characters: $sectionSize\nLimit: $MAX_MEMORY_CHARACTERS"
                )
            }
            selected += memories
            total += sectionSize
            traceLines += "$name: ${memories.size} item(s), $sectionSize characters, ids=${memories.joinToString(",") { it.id.toString() }}"
        }

        return MemoryContext(selected, traceLines.joinToString("\n"), total)
    }

    private fun relevance(memory: AgentMemory, queryTokens: Set<String>): Int {
        if (queryTokens.isEmpty()) return 0
        val memoryTokens = tokens("${memory.category} ${memory.title} ${memory.content}")
        return queryTokens.count(memoryTokens::contains)
    }

    private fun tokens(value: String): Set<String> = value.lowercase()
        .split(Regex("[^\\p{L}\\p{N}_./-]+"))
        .filter { it.length >= 3 }
        .toSet()

    private fun memorySize(memory: AgentMemory): Int =
        memory.category.length + memory.title.length + memory.content.length + 96

    private val SYSTEM_TERMS = setOf(
        "android", "system", "termux", "root", "permission", "permissions", "izin", "yetki",
        "storage", "microphone", "network", "oauth", "groq", "exec-server", "websocket", "kali", "nethunter"
    )
}
