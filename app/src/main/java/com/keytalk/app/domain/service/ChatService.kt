package com.keytalk.app.domain.service

import com.keytalk.app.domain.model.ConnectionProfile
import com.keytalk.app.domain.model.Message
import com.keytalk.app.domain.model.MessageRole
import com.keytalk.app.domain.model.MessageStatus
import com.keytalk.app.domain.model.ModelProfile
import com.keytalk.app.domain.model.PreparedImageAttachment
import com.keytalk.app.domain.model.ConversationSummary
import com.keytalk.app.domain.model.estimateTokens
import com.keytalk.app.domain.repository.ConfigurationRepository
import com.keytalk.app.domain.repository.ConversationRepository
import com.keytalk.app.domain.repository.ConversationSummaryRepository
import com.keytalk.app.domain.repository.FailoverPolicyRepository
import com.keytalk.app.domain.repository.MessageRepository
import com.keytalk.app.domain.repository.NoopConversationSummaryRepository
import com.keytalk.app.network.ChatNetworkClient
import com.keytalk.app.provider.ChatRequest
import com.keytalk.app.provider.ProviderException
import com.keytalk.app.provider.StreamEvent
import com.keytalk.app.security.CredentialStore
import kotlinx.coroutines.flow.collect
import java.time.Clock
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

class ChatService(
    private val configurationRepository: ConfigurationRepository,
    private val conversationRepository: ConversationRepository,
    private val messageRepository: MessageRepository,
    private val credentialStore: CredentialStore,
    private val networkClient: ChatNetworkClient,
    private val summaryRepository: ConversationSummaryRepository = NoopConversationSummaryRepository,
    private val failoverPolicyRepository: FailoverPolicyRepository? = null,
    private val clock: Clock = Clock.systemDefaultZone(),
    private val contextWindowManager: ContextWindowManager = ContextWindowManager(),
) {
    // 每个会话只记录最新一次网络请求标识。收尾时按键值同时匹配后再删除。
    // 避免较早请求结束时误删同一会话后来请求的取消句柄。
    private val activeRequestIdsByConversation = ConcurrentHashMap<String, String>()

    suspend fun sendMessage(
        conversationId: String,
        content: String,
        images: List<PreparedImageAttachment> = emptyList(),
    ): Message {
        val text = content.trim()
        require(text.isNotBlank() || images.isNotEmpty()) { "消息内容和图片不能同时为空。" }

        val conversation = conversationRepository.getConversation(conversationId)
            ?: throw IllegalArgumentException("会话不存在：$conversationId")
        val model = configurationRepository.getModel(conversation.modelProfileId)
            ?: throw IllegalStateException("模型配置不存在。")
        val connection = configurationRepository.getConnection(model.connectionId)
            ?: throw IllegalStateException("连接配置不存在。")
        val apiKey = credentialStore.readApiKey(connection.credentialId)
            ?: throw IllegalStateException("当前连接缺少 API Key。")
        if (images.isNotEmpty() && !model.supportsVision) {
            throw ProviderException(
                com.keytalk.app.provider.ChatError(
                    type = com.keytalk.app.provider.ChatErrorType.UNSUPPORTED_VISION,
                    message = "当前模型未开启视觉能力，不能发送图片。请在模型配置中启用视觉模型。",
                    retryable = false,
                ),
            )
        }

        val now = clock.instant()
        val userMessage = Message(
            conversationId = conversationId,
            role = MessageRole.USER,
            content = text,
            status = MessageStatus.COMPLETED,
            createdAt = now,
            updatedAt = now,
        )
        messageRepository.saveMessage(userMessage)
        images.forEach { image ->
            messageRepository.saveAttachment(image.toAttachment(userMessage.id))
        }
        val preview = text.ifBlank { "[图片]" }
        conversationRepository.updatePreview(conversationId, preview, now)

        val assistantMessage = Message(
            conversationId = conversationId,
            role = MessageRole.ASSISTANT,
            content = "",
            status = MessageStatus.SENDING,
            createdAt = now,
            updatedAt = now,
        )
        messageRepository.saveMessage(assistantMessage)

        val history = messageRepository.listMessages(conversationId)
            .filter { it.id != assistantMessage.id && it.status != MessageStatus.FAILED }

        return try {
            val result = sendWithModel(
                conversationId = conversationId,
                history = history,
                userMessageId = userMessage.id,
                images = images,
                model = model,
                connection = connection,
                apiKey = apiKey,
                assistantMessage = assistantMessage,
            )
            if (result.status == MessageStatus.FAILED) {
                tryFailover(
                    conversationId = conversationId,
                    history = history,
                    userMessageId = userMessage.id,
                    images = images,
                    primaryModel = model,
                    assistantMessage = assistantMessage,
                )?.let { return it }
            }
            result
        } catch (e: ProviderException) {
            tryFailover(
                conversationId = conversationId,
                history = history,
                userMessageId = userMessage.id,
                images = images,
                primaryModel = model,
                assistantMessage = assistantMessage,
            )?.let { return it }
            val failed = assistantMessage.copy(
                content = e.error.message,
                status = MessageStatus.FAILED,
                updatedAt = clock.instant(),
            )
            messageRepository.updateMessage(failed)
            throw e
        } catch (e: Exception) {
            tryFailover(
                conversationId = conversationId,
                history = history,
                userMessageId = userMessage.id,
                images = images,
                primaryModel = model,
                assistantMessage = assistantMessage,
            )?.let { return it }
            val failed = assistantMessage.copy(
                content = e.message ?: "发送失败，请稍后重试。",
                status = MessageStatus.FAILED,
                updatedAt = clock.instant(),
            )
            messageRepository.updateMessage(failed)
            throw e
        }
    }

    private suspend fun sendWithModel(
        conversationId: String,
        history: List<Message>,
        userMessageId: String,
        images: List<PreparedImageAttachment>,
        model: ModelProfile,
        connection: ConnectionProfile,
        apiKey: String,
        assistantMessage: Message,
        forceNonStreaming: Boolean = false,
    ): Message {
        val contextBuild = contextWindowManager.buildRequestContext(
            history = history,
            model = model,
        ) { message ->
            if (message.id == userMessageId) images.map { image -> image.toChatImage() } else emptyList()
        }
        persistGeneratedSummary(conversationId, contextBuild)

        val stream = model.supportsStreaming && !forceNonStreaming
        val request = ChatRequest(
            protocolAdapter = connection.protocolAdapter,
            baseUrl = connection.baseUrl,
            apiKey = apiKey,
            model = model.model,
            messages = contextBuild.messages,
            stream = stream,
            temperature = model.temperature,
            maxTokens = model.maxTokens,
            contextWindow = contextWindowManager.limitTokens(model),
            enable1mContext = model.enable1mContext,
            reasoningEffort = model.reasoningEffort,
        )

        activeRequestIdsByConversation[conversationId] = request.requestId
        return try {
            if (stream) {
                sendStreaming(request, assistantMessage)
            } else {
                sendNonStreaming(request, assistantMessage)
            }
        } finally {
            activeRequestIdsByConversation.remove(conversationId, request.requestId)
        }
    }

    private suspend fun tryFailover(
        conversationId: String,
        history: List<Message>,
        userMessageId: String,
        images: List<PreparedImageAttachment>,
        primaryModel: ModelProfile,
        assistantMessage: Message,
    ): Message? {
        val enabledConnectionIds = failoverPolicyRepository?.getEnabledConnectionIds().orEmpty()
        if (enabledConnectionIds.isEmpty()) return null

        val primaryModelKey = primaryModel.model.normalizedModelKey()
        val primaryDisplayKey = primaryModel.displayName.normalizedModelKey()
        val allModels = configurationRepository.listModels()
        val allConnections = configurationRepository.listConnections().associateBy { it.id }
        val orderedCandidates = enabledConnectionIds.flatMap { connectionId ->
            val connection = allConnections[connectionId] ?: return@flatMap emptyList()
            val connectionModels = allModels
                .filter { candidate ->
                    candidate.connectionId == connectionId &&
                        candidate.id != primaryModel.id &&
                        (images.isEmpty() || candidate.supportsVision)
                }
                .sortedWith(
                    compareByDescending<ModelProfile> { it.isDefault }
                        .thenByDescending { it.updatedAt },
                )
            if (connectionModels.isEmpty()) return@flatMap emptyList()

            val sameModelOrAlias = connectionModels.firstOrNull { candidate ->
                val candidateModelKey = candidate.model.normalizedModelKey()
                val candidateDisplayKey = candidate.displayName.normalizedModelKey()
                candidateModelKey == primaryModelKey ||
                    candidateModelKey == primaryDisplayKey ||
                    candidateDisplayKey == primaryModelKey ||
                    candidateDisplayKey == primaryDisplayKey
            }
            val fallback = connectionModels.firstOrNull()
            listOfNotNull(sameModelOrAlias, fallback)
                .distinctBy { candidate -> candidate.id }
                .map { candidate -> candidate to connection }
        }
        if (orderedCandidates.isEmpty()) return null

        for ((candidateModel, candidateConnection) in orderedCandidates) {
            val candidateApiKey = credentialStore.readApiKey(candidateConnection.credentialId) ?: continue
            val result = runCatching {
                sendWithModel(
                    conversationId = conversationId,
                    history = history,
                    userMessageId = userMessageId,
                    images = images,
                    model = candidateModel,
                    connection = candidateConnection,
                    apiKey = candidateApiKey,
                    assistantMessage = assistantMessage,
                    forceNonStreaming = true,
                )
            }.getOrNull()
            if (result?.status == MessageStatus.COMPLETED) {
                conversationRepository.switchConversationModel(conversationId, candidateModel.id, clock.instant())
                return result.copy(
                    content = result.content.ifBlank { "已通过备用连接 ${candidateConnection.name} 完成响应。" },
                )
            }
        }
        return null
    }

    private fun String.normalizedModelKey(): String = trim().lowercase()

    private suspend fun persistGeneratedSummary(
        conversationId: String,
        contextBuild: ContextBuildResult,
    ) {
        val summary = contextBuild.generatedSummary ?: return
        val startId = contextBuild.coveredMessageStartId ?: return
        val endId = contextBuild.coveredMessageEndId ?: return
        val existing = summaryRepository.findCoveringRange(conversationId, startId, endId)
        if (existing != null) return
        val now = clock.instant()
        summaryRepository.saveSummary(
            ConversationSummary(
                conversationId = conversationId,
                summaryContent = summary,
                coveredMessageStartId = startId,
                coveredMessageEndId = endId,
                tokenEstimate = estimateTokens(summary),
                createdAt = now,
                updatedAt = now,
            ),
        )
    }

    fun cancel(requestId: String) {
        networkClient.cancel(requestId)
    }

    fun cancelConversation(conversationId: String) {
        activeRequestIdsByConversation[conversationId]?.let(networkClient::cancel)
    }

    private suspend fun sendNonStreaming(
        request: ChatRequest,
        assistantMessage: Message,
    ): Message {
        val response = networkClient.send(request.copy(stream = false))
        val now = clock.instant()
        val completed = assistantMessage.copy(
            content = response.messageText,
            status = MessageStatus.COMPLETED,
            tokenEstimate = estimateTokens(response.messageText),
            providerInputTokens = response.usage?.inputTokens,
            providerOutputTokens = response.usage?.outputTokens,
            providerTotalTokens = response.usage?.totalTokens,
            updatedAt = now,
        )
        messageRepository.updateMessage(completed)
        conversationRepository.updatePreview(completed.conversationId, response.messageText, now)
        return completed
    }

    private suspend fun sendStreaming(
        request: ChatRequest,
        assistantMessage: Message,
    ): Message {
        var latest = assistantMessage.copy(status = MessageStatus.STREAMING, updatedAt = clock.instant())
        messageRepository.updateMessage(latest)

        val text = StringBuilder()
        var usageInput: Int? = null
        var usageOutput: Int? = null
        var usageTotal: Int? = null

        networkClient.stream(request.copy(stream = true)).collect { event ->
            when (event) {
                is StreamEvent.DeltaText -> {
                    text.append(event.textDelta)
                    latest = latest.copy(
                        content = text.toString(),
                        status = MessageStatus.STREAMING,
                        tokenEstimate = estimateTokens(text.toString()),
                        updatedAt = clock.instant(),
                    )
                    messageRepository.updateMessage(latest)
                }

                is StreamEvent.UsageReported -> {
                    usageInput = event.usage.inputTokens
                    usageOutput = event.usage.outputTokens
                    usageTotal = event.usage.totalTokens
                }

                is StreamEvent.MessageCompleted -> {
                    latest = latest.copy(
                        content = text.toString(),
                        status = MessageStatus.COMPLETED,
                        tokenEstimate = estimateTokens(text.toString()),
                        providerInputTokens = event.usage?.inputTokens ?: usageInput,
                        providerOutputTokens = event.usage?.outputTokens ?: usageOutput,
                        providerTotalTokens = event.usage?.totalTokens ?: usageTotal,
                        updatedAt = clock.instant(),
                    )
                    messageRepository.updateMessage(latest)
                }

                is StreamEvent.Error -> {
                    latest = latest.copy(
                        content = if (text.isNotEmpty()) text.toString() else event.error.message,
                        status = if (text.isNotEmpty()) MessageStatus.INTERRUPTED else MessageStatus.FAILED,
                        tokenEstimate = estimateTokens(text.toString()),
                        updatedAt = clock.instant(),
                    )
                    messageRepository.updateMessage(latest)
                }

                is StreamEvent.Heartbeat -> Unit
            }
        }

        if (latest.status == MessageStatus.STREAMING) {
            latest = latest.copy(
                status = MessageStatus.COMPLETED,
                tokenEstimate = estimateTokens(text.toString()),
                updatedAt = clock.instant(),
            )
            messageRepository.updateMessage(latest)
        }
        if (latest.content.isNotBlank()) {
            conversationRepository.updatePreview(latest.conversationId, latest.content, Instant.now(clock))
        }
        return latest
    }
}
