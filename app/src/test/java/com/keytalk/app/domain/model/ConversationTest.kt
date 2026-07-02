package com.keytalk.app.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.temporal.ChronoUnit

class ConversationTest {
    @Test
    fun defaultConversationIsActive() {
        val now = Instant.parse("2026-06-27T00:00:00Z")
        val conversation = Conversation(
            title = "Test",
            modelProfileId = "model-1",
            createdAt = now,
            updatedAt = now,
        )

        assertEquals(DeleteState.ACTIVE, conversation.deleteState)
        assertNull(conversation.deletedAt)
        assertNull(conversation.purgeAfter)
    }

    @Test
    fun moveToTrashSetsDeletedAtAndPurgeAfter() {
        val created = Instant.parse("2026-06-27T00:00:00Z")
        val deleted = Instant.parse("2026-06-28T00:00:00Z")
        val conversation = Conversation(
            title = "Test",
            modelProfileId = "model-1",
            createdAt = created,
            updatedAt = created,
        ).moveToTrash(deleted)

        assertEquals(DeleteState.TRASH, conversation.deleteState)
        assertEquals(deleted, conversation.deletedAt)
        assertEquals(deleted.plus(30, ChronoUnit.DAYS), conversation.purgeAfter)
        assertTrue(conversation.isTrashExpired(deleted.plus(31, ChronoUnit.DAYS)))
    }

    @Test
    fun restoreClearsDeleteFields() {
        val now = Instant.parse("2026-06-27T00:00:00Z")
        val restored = Conversation(
            title = "Test",
            modelProfileId = "model-1",
            createdAt = now,
            updatedAt = now,
        )
            .moveToTrash(now)
            .restoreFromTrash(now.plusSeconds(10))

        assertEquals(DeleteState.ACTIVE, restored.deleteState)
        assertNull(restored.deletedAt)
        assertNull(restored.purgeAfter)
    }
}
