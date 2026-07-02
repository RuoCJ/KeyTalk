package com.keytalk.app.domain

import com.keytalk.app.domain.model.Conversation
import com.keytalk.app.domain.model.DeleteState
import com.keytalk.app.domain.repository.ConversationRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant
import java.time.temporal.ChronoUnit

class ConversationDeletionTest {
    @Test
    fun defaultDeleteMovesToTrashAndRestoreClearsFields() = runTest {
        val repository = InMemoryConversationRepository()
        val now = Instant.parse("2026-06-27T00:00:00Z")
        val conversation = repository.createConversation("chat", "model-1", now)

        repository.moveToTrash(conversation.id, now)
        val trashed = repository.getConversation(conversation.id)!!

        assertEquals(DeleteState.TRASH, trashed.deleteState)
        assertEquals(now, trashed.deletedAt)
        assertEquals(now.plus(30, ChronoUnit.DAYS), trashed.purgeAfter)

        repository.restoreFromTrash(conversation.id)
        val restored = repository.getConversation(conversation.id)!!
        assertEquals(DeleteState.ACTIVE, restored.deleteState)
        assertNull(restored.deletedAt)
        assertNull(restored.purgeAfter)
    }

    @Test
    fun hardDeleteBypassesTrash() = runTest {
        val repository = InMemoryConversationRepository()
        val now = Instant.parse("2026-06-27T00:00:00Z")
        val conversation = repository.createConversation("chat", "model-1", now)

        repository.hardDelete(conversation.id)

        assertNull(repository.getConversation(conversation.id))
        assertEquals(emptyList<Conversation>(), repository.observeTrashConversations().first())
    }

    @Test
    fun purgeExpiredTrashDeletesOnlyExpiredConversations() = runTest {
        val repository = InMemoryConversationRepository()
        val now = Instant.parse("2026-06-27T00:00:00Z")
        val expired = repository.createConversation("expired", "model-1", now)
        val fresh = repository.createConversation("fresh", "model-1", now)
        repository.moveToTrash(expired.id, now.minus(31, ChronoUnit.DAYS))
        repository.moveToTrash(fresh.id, now)

        val purged = repository.purgeExpiredTrash(now)

        assertEquals(1, purged)
        assertNull(repository.getConversation(expired.id))
        assertEquals(DeleteState.TRASH, repository.getConversation(fresh.id)!!.deleteState)
    }
}

private class InMemoryConversationRepository : ConversationRepository {
    private val conversations = MutableStateFlow<List<Conversation>>(emptyList())

    override fun observeConversation(conversationId: String): Flow<Conversation?> =
        conversations.map { values -> values.firstOrNull { it.id == conversationId } }

    override fun observeActiveConversations(): Flow<List<Conversation>> =
        conversations.map { values -> values.filter { it.deleteState == DeleteState.ACTIVE } }

    override fun observeTrashConversations(): Flow<List<Conversation>> =
        conversations.map { values -> values.filter { it.deleteState == DeleteState.TRASH } }

    override suspend fun listConversations(includeTrash: Boolean): List<Conversation> =
        conversations.value.filter { includeTrash || it.deleteState == DeleteState.ACTIVE }

    override suspend fun getConversation(conversationId: String): Conversation? =
        conversations.value.firstOrNull { it.id == conversationId }

    override suspend fun saveConversation(conversation: Conversation) {
        conversations.value = conversations.value.filterNot { it.id == conversation.id } + conversation
    }

    override suspend fun createConversation(title: String, modelProfileId: String, now: Instant): Conversation {
        val conversation = Conversation(
            title = title,
            modelProfileId = modelProfileId,
            createdAt = now,
            updatedAt = now,
        )
        conversations.value = conversations.value + conversation
        return conversation
    }

    override suspend fun renameConversation(conversationId: String, title: String, now: Instant) {
        conversations.value = conversations.value.map {
            if (it.id == conversationId) it.copy(title = title.trim(), updatedAt = now) else it
        }
    }

    override suspend fun switchConversationModel(conversationId: String, modelProfileId: String, now: Instant) {
        conversations.value = conversations.value.map {
            if (it.id == conversationId) it.copy(modelProfileId = modelProfileId, updatedAt = now) else it
        }
    }

    override suspend fun updatePreview(conversationId: String, preview: String, now: Instant) {
        conversations.value = conversations.value.map {
            if (it.id == conversationId) it.updatePreview(preview, now) else it
        }
    }

    override suspend fun moveToTrash(conversationId: String, now: Instant) {
        conversations.value = conversations.value.map {
            if (it.id == conversationId) it.moveToTrash(now) else it
        }
    }

    override suspend fun restoreFromTrash(conversationId: String) {
        val now = Instant.parse("2026-06-27T00:00:00Z")
        conversations.value = conversations.value.map {
            if (it.id == conversationId) it.restoreFromTrash(now) else it
        }
    }

    override suspend fun hardDelete(conversationId: String) {
        conversations.value = conversations.value.filterNot { it.id == conversationId }
    }

    override suspend fun purgeExpiredTrash(now: Instant): Int {
        val (expired, kept) = conversations.value.partition { it.isTrashExpired(now) }
        conversations.value = kept
        return expired.size
    }

    override suspend fun clearTrash(): Int {
        val trash = conversations.value.count { it.deleteState == DeleteState.TRASH }
        conversations.value = conversations.value.filterNot { it.deleteState == DeleteState.TRASH }
        return trash
    }
}
