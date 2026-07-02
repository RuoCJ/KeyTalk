package com.keytalk.app.data.db.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.keytalk.app.data.db.entity.AttachmentEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface AttachmentDao {
    @Query(
        """
        SELECT attachments.*
        FROM attachments
        INNER JOIN messages ON messages.id = attachments.messageId
        WHERE messages.conversationId = :conversationId
        ORDER BY attachments.createdAtMillis ASC
        """,
    )
    fun observeByConversation(conversationId: String): Flow<List<AttachmentEntity>>

    @Query("SELECT * FROM attachments WHERE messageId = :messageId ORDER BY createdAtMillis ASC")
    suspend fun listByMessage(messageId: String): List<AttachmentEntity>

    @Query("SELECT * FROM attachments WHERE messageId IN (:messageIds) ORDER BY createdAtMillis ASC")
    suspend fun listByMessages(messageIds: List<String>): List<AttachmentEntity>

    @Query("SELECT localEncryptedUri FROM attachments")
    suspend fun listAllLocalEncryptedUris(): List<String>

    @Upsert
    suspend fun upsert(attachment: AttachmentEntity)
}
