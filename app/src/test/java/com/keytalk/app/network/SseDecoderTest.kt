package com.keytalk.app.network

import com.keytalk.app.provider.SseEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SseDecoderTest {
    @Test
    fun decodesMultiLineEventDataAndMetadata() {
        val decoder = SseDecoder()

        assertNull(decoder.accept("event: content_block_delta"))
        assertNull(decoder.accept("id: 123"))
        assertNull(decoder.accept("retry: 5000"))
        assertNull(decoder.accept("data: hello"))
        val event = decoder.accept("data: world")
        assertNull(event)

        val flushed = decoder.finish()

        assertEquals(
            SseEvent(
                event = "content_block_delta",
                data = "hello\nworld",
                id = "123",
                retryMillis = 5000,
            ),
            flushed,
        )
    }

    @Test
    fun ignoresCommentAndFlushesOnEof() {
        val decoder = SseDecoder()

        assertNull(decoder.accept(": ping"))
        assertNull(decoder.accept("data: hi"))

        assertEquals(
            SseEvent(
                event = null,
                data = "hi",
                id = null,
                retryMillis = null,
            ),
            decoder.finish(),
        )
    }
}
