package com.keytalk.app.data.repository

import com.keytalk.app.data.db.dao.AttachmentDao
import com.keytalk.app.data.db.dao.MessageDao
import com.keytalk.app.data.db.entity.toDomain
import com.keytalk.app.data.db.entity.toEntity
import com.keytalk.app.domain.model.Attachment
import com.keytalk.app.domain.model.Message
import com.keytalk.app.domain.model.MessageStatus
import com.keytalk.app.domain.model.estimateTokens
import com.keytalk.app.domain.repository.MessageRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import java.time.Instant

class RoomMessageRepository(
    private val messageDao: MessageDao,
    private val attachmentDao: AttachmentDao,
) : MessageRepository {
    override fun observeMessages(conversationId: String): Flow<List<Message>> =
        combine(
            messageDao.observeByConversation(conversationId),
            attachmentDao.observeByConversation(conversationId),
        ) { messageEntities, attachmentEntities ->
            val attachmentsByMessage = attachmentEntities.map { it.toDomain() }.groupBy { it.messageId }
            messageEntities.map { entity ->
                entity.toDomain().copy(attachments = attachmentsByMessage[entity.id].orEmpty())
            }
        }

    override suspend fun listMessages(conversationId: String): List<Message> =
        messageDao.listByConversation(conversationId).let { messageEntities ->
            val attachmentsByMessage = if (messageEntities.isEmpty()) {
                emptyMap()
            } else {
                attachmentDao.listByMessages(messageEntities.map { it.id })
                    .map { it.toDomain() }
                    .groupBy { it.messageId }
            }
            messageEntities.map { entity ->
                entity.toDomain().copy(attachments = attachmentsByMessage[entity.id].orEmpty())
            }
        }

    override suspend fun getMessage(messageId: String): Message? =
        messageDao.getById(messageId)?.toDomain()?.let { message ->
            message.copy(attachments = attachmentDao.listByMessage(messageId).map { it.toDomain() })
        }

    override suspend fun saveMessage(message: Message) {
        messageDao.upsert(message.toEntity())
    }

    override suspend fun saveAttachment(attachment: Attachment) {
        attachmentDao.upsert(attachment.toEntity())
    }

    override suspend fun updateMessage(message: Message) {
        messageDao.updateMessage(
            messageId = message.id,
            content = message.content,
            status = message.status.name,
            tokenEstimate = message.tokenEstimate,
            providerInputTokens = message.providerInputTokens,
            providerOutputTokens = message.providerOutputTokens,
            providerTotalTokens = message.providerTotalTokens,
            updatedAtMillis = message.updatedAt.toEpochMilli(),
        )
    }

    override suspend fun appendAssistantDelta(messageId: String, delta: String) {
        messageDao.appendContent(
            messageId = messageId,
            delta = delta,
            status = MessageStatus.STREAMING.name,
            updatedAtMillis = Instant.now().toEpochMilli(),
        )
        val message = getMessage(messageId) ?: return
        messageDao.updateMessage(
            messageId = messageId,
            content = message.content,
            status = MessageStatus.STREAMING.name,
            tokenEstimate = estimateTokens(message.content),
            providerInputTokens = message.providerInputTokens,
            providerOutputTokens = message.providerOutputTokens,
            providerTotalTokens = message.providerTotalTokens,
            updatedAtMillis = Instant.now().toEpochMilli(),
        )
    }
}
