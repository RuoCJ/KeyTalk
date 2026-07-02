package com.keytalk.app.provider

import java.io.IOException

enum class ChatErrorType {
    INVALID_API_KEY,
    PERMISSION_DENIED,
    MODEL_NOT_FOUND,
    INVALID_BASE_URL,
    RATE_LIMITED,
    QUOTA_EXCEEDED,
    CONTEXT_EXCEEDED,
    UNSUPPORTED_VISION,
    UNSUPPORTED_STREAMING,
    NETWORK_TIMEOUT,
    TLS_ERROR,
    PROVIDER_UNAVAILABLE,
    INVALID_RESPONSE,
    STREAM_INTERRUPTED,
    STORAGE_ERROR,
    UNKNOWN,
}

data class ChatError(
    val type: ChatErrorType,
    val message: String,
    val retryable: Boolean = false,
    val httpStatusCode: Int? = null,
    val sanitizedRawResponse: String? = null,
)

class ProviderException(val error: ChatError) : IOException(error.message)
