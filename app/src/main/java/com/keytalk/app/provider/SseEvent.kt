package com.keytalk.app.provider

data class SseEvent(
    val event: String?,
    val data: String,
    val id: String?,
    val retryMillis: Long?,
)
