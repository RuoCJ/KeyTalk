package com.keytalk.app.provider

data class ChatResponse(
    val requestId: String,
    val messageText: String,
    val finishReason: String?,
    val usage: Usage? = null,
    val rawResponseRef: String? = null,
)

data class Usage(
    val inputTokens: Int?,
    val outputTokens: Int?,
    val totalTokens: Int?,
    val providerReported: Boolean,
)
