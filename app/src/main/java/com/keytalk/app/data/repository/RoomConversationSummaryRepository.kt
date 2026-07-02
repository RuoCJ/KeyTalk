package com.keytalk.app.data.repository

import com.keytalk.app.data.db.dao.ConversationSummaryDao
import com.keytalk.app.data.db.entity.toDomain
import com.keytalk.app.data.db.entity.toEntity
import com.keytalk.app.domain.model.ConversationSummary
import com.keytalk.app.domain.repository.ConversationSummaryRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class RoomConversationSummaryRepository(
    private val summaryDao: ConversationSummaryDao,
) : ConversationSummaryRepository {
    override fun observeSummaries(conversationId: String): Flow<List<ConversationSummary>> =
        summaryDao.observeByConversation(conversationId).map { values -> values.map { it.toDomain() } }

    override suspend fun listSummaries(conversationId: String): List<ConversationSummary> =
        summaryDao.listByConversation(conversationId).map { it.toDomain() }

    override suspend fun listAllSummaries(): List<ConversationSummary> =
        summaryDao.listAll().map { it.toDomain() }

    override suspend fun findCoveringRange(
        conversationId: String,
        coveredMessageStartId: String?,
        coveredMessageEndId: String?,
    ): ConversationSummary? =
        summaryDao.findCoveringRange(conversationId, coveredMessageStartId, coveredMessageEndId)?.toDomain()

    override suspend fun saveSummary(summary: ConversationSummary) {
        summaryDao.upsert(summary.toEntity())
    }
}
