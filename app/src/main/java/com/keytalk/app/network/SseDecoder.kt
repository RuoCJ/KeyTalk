package com.keytalk.app.network

import com.keytalk.app.provider.SseEvent

class SseDecoder {
    private var event: String? = null
    private var id: String? = null
    private var retryMillis: Long? = null
    private val data = StringBuilder()

    fun accept(line: String): SseEvent? {
        if (line.isEmpty()) {
            return dispatch()
        }

        if (line.startsWith(":")) {
            return null
        }

        val colonIndex = line.indexOf(':')
        val field: String
        val value: String
        if (colonIndex >= 0) {
            field = line.substring(0, colonIndex)
            value = line.substring(colonIndex + 1).removePrefix(" ")
        } else {
            field = line
            value = ""
        }

        when (field) {
            "event" -> event = value
            "data" -> {
                data.append(value)
                data.append('\n')
            }
            "id" -> id = value
            "retry" -> retryMillis = value.toLongOrNull()
        }

        return null
    }

    fun finish(): SseEvent? = dispatch()

    private fun dispatch(): SseEvent? {
        if (event == null && data.isEmpty() && id == null && retryMillis == null) return null

        val result = SseEvent(
            event = event,
            data = data.toString().removeSuffix("\n"),
            id = id,
            retryMillis = retryMillis,
        )

        event = null
        id = null
        retryMillis = null
        data.clear()

        return result
    }
}
