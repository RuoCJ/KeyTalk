package com.keytalk.app.provider

sealed interface StreamEvent {
    val requestId: String

    data class DeltaText(
        override val requestId: String,
        val textDelta: String,
    ) : StreamEvent

    data class MessageCompleted(
        override val requestId: String,
        val finishReason: String?,
        val usage: Usage? = null,
    ) : StreamEvent

    data class UsageReported(
        override val requestId: String,
        val usage: Usage,
    ) : StreamEvent

    data class Error(
        override val requestId: String,
        val error: ChatError,
    ) : StreamEvent

    data class Heartbeat(
        override val requestId: String,
    ) : StreamEvent
}
