package com.keytalk.app.data.repository

import com.keytalk.app.config.AppConfig
import com.keytalk.app.data.db.dao.ConversationDao
import com.keytalk.app.data.db.entity.toDomain
import com.keytalk.app.data.db.entity.toEntity
import com.keytalk.app.domain.model.Conversation
import com.keytalk.app.domain.repository.ConversationRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.Instant

class RoomConversationRepository(
    private val conversationDao: ConversationDao,
) : ConversationRepository {
    override fun observeConversation(conversationId: String): Flow<Conversation?> =
        conversationDao.observeById(conversationId).map { it?.toDomain() }

    override fun observeActiveConversations(): Flow<List<Conversation>> =
        conversationDao.observeActive().map { entities -> entities.map { it.toDomain() } }

    override fun observeTrashConversations(): Flow<List<Conversation>> =
        conversationDao.observeTrash().map { entities -> entities.map { it.toDomain() } }

    override suspend fun listConversations(includeTrash: Boolean): List<Conversation> =
        if (includeTrash) {
            conversationDao.listAll().map { it.toDomain() }
        } else {
            conversationDao.listActive().map { it.toDomain() }
        }

    override suspend fun getConversation(conversationId: String): Conversation? =
        conversationDao.getById(conversationId)?.toDomain()

    override suspend fun saveConversation(conversation: Conversation) {
        conversationDao.upsert(conversation.toEntity())
    }

    override suspend fun createConversation(
        title: String,
        modelProfileId: String,
        now: Instant,
    ): Conversation {
        val conversation = Conversation(
            title = title,
            modelProfileId = modelProfileId,
            createdAt = now,
            updatedAt = now,
        )
        conversationDao.upsert(conversation.toEntity())
        return conversation
    }

    override suspend fun renameConversation(conversationId: String, title: String, now: Instant) {
        val normalized = title.trim().ifBlank { throw IllegalArgumentException("会话名称不能为空") }
        conversationDao.renameConversation(
            conversationId = conversationId,
            title = normalized.take(AppConfig.Conversation.titleMaxChars),
            updatedAtMillis = now.toEpochMilli(),
        )
    }

    override suspend fun switchConversationModel(conversationId: String, modelProfileId: String, now: Instant) {
        conversationDao.switchModel(
            conversationId = conversationId,
            modelProfileId = modelProfileId,
            updatedAtMillis = now.toEpochMilli(),
        )
    }

    override suspend fun updatePreview(conversationId: String, preview: String, now: Instant) {
        conversationDao.updatePreview(
            conversationId = conversationId,
            preview = preview.take(AppConfig.Conversation.previewMaxChars),
            updatedAtMillis = now.toEpochMilli(),
        )
    }

    override suspend fun moveToTrash(conversationId: String, now: Instant) {
        val trash = getConversation(conversationId)?.moveToTrash(now)
            ?: throw IllegalArgumentException("会话不存在：$conversationId")
        conversationDao.moveToTrash(
            conversationId = conversationId,
            deletedAtMillis = trash.deletedAt!!.toEpochMilli(),
            purgeAfterMillis = trash.purgeAfter!!.toEpochMilli(),
        )
    }

    override suspend fun restoreFromTrash(conversationId: String) {
        conversationDao.restoreFromTrash(conversationId, Instant.now().toEpochMilli())
    }

    override suspend fun hardDelete(conversationId: String) {
        conversationDao.deleteById(conversationId)
    }

    override suspend fun purgeExpiredTrash(now: Instant): Int {
        val expiredIds = conversationDao.findExpiredTrashIds(now.toEpochMilli())
        expiredIds.forEach { id -> hardDelete(id) }
        return expiredIds.size
    }

    override suspend fun clearTrash(): Int {
        val ids = conversationDao.listTrashIds()
        ids.forEach { id -> hardDelete(id) }
        return ids.size
    }
}
