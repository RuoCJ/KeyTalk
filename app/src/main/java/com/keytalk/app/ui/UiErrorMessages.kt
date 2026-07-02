package com.keytalk.app.ui

import com.keytalk.app.provider.ProviderException

internal fun Throwable.toUserFacingMessage(fallback: String): String {
    if (this is ProviderException) return error.message

    val candidate = message?.trim().orEmpty()
    return if (candidate.isNotBlank() && candidate.any { it in '\u4e00'..'\u9fff' }) {
        candidate
    } else {
        fallback
    }
}
