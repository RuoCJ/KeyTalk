package com.keytalk.app.provider

import okhttp3.Request

interface ProviderAdapter {
    fun buildRequest(request: ChatRequest): Request
    fun parseNonStreamingResponse(requestId: String, body: String): ChatResponse
    fun parseStreamingLine(requestId: String, line: String): StreamEvent? {
        val trimmed = line.trim()
        if (trimmed.isBlank() || trimmed.startsWith(":")) {
            return StreamEvent.Heartbeat(requestId)
        }
        if (!trimmed.startsWith("data:")) return null

        return parseStreamingEvent(
            requestId = requestId,
            event = SseEvent(
                event = null,
                data = trimmed.removePrefix("data:").trim(),
                id = null,
                retryMillis = null,
            ),
        )
    }

    fun parseStreamingEvent(requestId: String, event: SseEvent): StreamEvent? = null
    fun mapHttpError(statusCode: Int, body: String?): ChatError
}
