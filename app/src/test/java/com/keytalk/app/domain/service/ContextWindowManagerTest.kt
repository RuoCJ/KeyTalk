package com.keytalk.app.domain.service

import com.keytalk.app.domain.model.Message
import com.keytalk.app.domain.model.MessageRole
import com.keytalk.app.domain.model.MessageStatus
import com.keytalk.app.domain.model.ModelProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class ContextWindowManagerTest {
    private val manager = ContextWindowManager()
    private val now = Instant.parse("2026-06-28T00:00:00Z")

    @Test
    fun reportsThresholdPressureAt70_85_95Percent() {
        val model = model(defaultWindow = 100)

        assertEquals(ContextPressure.NORMAL, manager.usage(listOf(message(tokens = 69)), model).pressure)
        assertEquals(ContextPressure.WARNING_70, manager.usage(listOf(message(tokens = 70)), model).pressure)
        assertEquals(ContextPressure.WARNING_85, manager.usage(listOf(message(tokens = 85)), model).pressure)
        assertEquals(ContextPressure.CRITICAL_95, manager.usage(listOf(message(tokens = 95)), model).pressure)
    }

    @Test
    fun usesOneMillionLimitWhenModelEnables1mContext() {
        val model = model(defaultWindow = 128_000, supports1m = true, enable1m = true)

        assertEquals(1_000_000, manager.limitTokens(model))
    }

    @Test
    fun trimsOlderMessagesIntoRollingSummaryAndKeepsRecentMessages() {
        val model = model(defaultWindow = 100)
        val history = listOf(
            message(content = "old-1", tokens = 50),
            message(content = "old-2", tokens = 40),
            message(content = "recent", tokens = 30),
        )

        val requestMessages = manager.buildRequestMessages(history, model)

        assertTrue(requestMessages.first().content.contains("滚动摘要"))
        assertTrue(requestMessages.first().content.contains("old-1"))
        assertEquals("recent", requestMessages.last().content)
    }

    private fun model(
        defaultWindow: Int,
        supports1m: Boolean = false,
        enable1m: Boolean = false,
    ): ModelProfile =
        ModelProfile(
            connectionId = "conn-1",
            displayName = "Demo",
            model = "demo",
            defaultContextWindow = defaultWindow,
            supports1mContext = supports1m,
            enable1mContext = enable1m,
            createdAt = now,
            updatedAt = now,
        )

    private fun message(
        content: String = "x",
        tokens: Int,
    ): Message =
        Message(
            conversationId = "conv-1",
            role = MessageRole.USER,
            content = content,
            status = MessageStatus.COMPLETED,
            tokenEstimate = tokens,
            createdAt = now,
            updatedAt = now,
        )
}
