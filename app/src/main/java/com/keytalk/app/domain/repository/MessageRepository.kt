package com.keytalk.app.domain.repository

import com.keytalk.app.domain.model.Attachment
import com.keytalk.app.domain.model.Message
import kotlinx.coroutines.flow.Flow

interface MessageRepository {
    fun observeMessages(conversationId: String): Flow<List<Message>>
    suspend fun listMessages(conversationId: String): List<Message>
    suspend fun getMessage(messageId: String): Message?
    suspend fun saveMessage(message: Message)
    suspend fun saveAttachment(attachment: Attachment)
    suspend fun updateMessage(message: Message)
    suspend fun appendAssistantDelta(messageId: String, delta: String)
}
