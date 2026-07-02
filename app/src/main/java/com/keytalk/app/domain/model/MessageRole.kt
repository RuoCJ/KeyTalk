package com.keytalk.app.domain.model

enum class MessageRole {
    SYSTEM,
    USER,
    ASSISTANT;

    fun wireName(): String = name.lowercase()

    companion object {
        fun fromStorage(value: String): MessageRole =
            entries.firstOrNull { it.name == value } ?: USER
    }
}

enum class MessageStatus {
    SENDING,
    STREAMING,
    COMPLETED,
    FAILED,
    INTERRUPTED;

    companion object {
        fun fromStorage(value: String): MessageStatus =
            entries.firstOrNull { it.name == value } ?: COMPLETED
    }
}
