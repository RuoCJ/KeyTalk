package com.keytalk.app.domain.repository

import com.keytalk.app.domain.model.Conversation
import kotlinx.coroutines.flow.Flow
import java.time.Instant

interface ConversationRepository {
    fun observeConversation(conversationId: String): Flow<Conversation?>
    fun observeActiveConversations(): Flow<List<Conversation>>
    fun observeTrashConversations(): Flow<List<Conversation>>
    suspend fun listConversations(includeTrash: Boolean): List<Conversation>
    suspend fun getConversation(conversationId: String): Conversation?
    suspend fun saveConversation(conversation: Conversation)
    suspend fun createConversation(title: String, modelProfileId: String, now: Instant): Conversation
    suspend fun renameConversation(conversationId: String, title: String, now: Instant)
    suspend fun switchConversationModel(conversationId: String, modelProfileId: String, now: Instant)
    suspend fun updatePreview(conversationId: String, preview: String, now: Instant)
    suspend fun moveToTrash(conversationId: String, now: Instant)
    suspend fun restoreFromTrash(conversationId: String)
    suspend fun hardDelete(conversationId: String)
    suspend fun purgeExpiredTrash(now: Instant): Int
    suspend fun clearTrash(): Int
}
