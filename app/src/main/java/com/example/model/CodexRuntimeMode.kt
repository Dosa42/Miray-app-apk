package com.example.model

/** User-visible Codex execution mode. No value is allowed to imply a fallback. */
enum class CodexRuntimeMode(val storedValue: String, val directReasoningEffort: String?) {
    HIGH("high", "high"),
    XHIGH("xhigh", "xhigh"),
    MAX("max", "max"),
    ULTRA("ultra", null);

    companion object {
        fun fromStored(value: String?): CodexRuntimeMode =
            entries.firstOrNull { it.storedValue == value } ?: HIGH
    }
}
