package com.keytalk.app.domain.repository

import com.keytalk.app.domain.model.ConversationSummary
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

interface ConversationSummaryRepository {
    fun observeSummaries(conversationId: String): Flow<List<ConversationSummary>>
    suspend fun listSummaries(conversationId: String): List<ConversationSummary>
    suspend fun listAllSummaries(): List<ConversationSummary>
    suspend fun findCoveringRange(
        conversationId: String,
        coveredMessageStartId: String?,
        coveredMessageEndId: String?,
    ): ConversationSummary?
    suspend fun saveSummary(summary: ConversationSummary)
}

object NoopConversationSummaryRepository : ConversationSummaryRepository {
    override fun observeSummaries(conversationId: String): Flow<List<ConversationSummary>> = flowOf(emptyList())
    override suspend fun listSummaries(conversationId: String): List<ConversationSummary> = emptyList()
    override suspend fun listAllSummaries(): List<ConversationSummary> = emptyList()
    override suspend fun findCoveringRange(
        conversationId: String,
        coveredMessageStartId: String?,
        coveredMessageEndId: String?,
    ): ConversationSummary? = null
    override suspend fun saveSummary(summary: ConversationSummary) = Unit
}
