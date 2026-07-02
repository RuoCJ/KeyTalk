package com.keytalk.app.domain.model

enum class ProtocolAdapter {
    OPENAI_COMPATIBLE,
    CLAUDE_NATIVE,
    GEMINI_NATIVE,
    GROK_NATIVE,
    CUSTOM;

    fun displayName(): String = when (this) {
        OPENAI_COMPATIBLE -> "OpenAI-Compatible"
        CLAUDE_NATIVE -> "Claude Native"
        GEMINI_NATIVE -> "Gemini Native"
        GROK_NATIVE -> "Grok Native"
        CUSTOM -> "Custom"
    }

    companion object {
        fun fromStorage(value: String): ProtocolAdapter =
            entries.firstOrNull { it.name == value } ?: OPENAI_COMPATIBLE
    }
}
