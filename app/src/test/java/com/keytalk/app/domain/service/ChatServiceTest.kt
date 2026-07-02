package com.keytalk.app.domain.service

import com.keytalk.app.domain.model.ConnectionProfile
import com.keytalk.app.domain.model.ConversationSummary
import com.keytalk.app.domain.model.Conversation
import com.keytalk.app.domain.model.DeleteState
import com.keytalk.app.domain.model.Attachment
import com.keytalk.app.domain.model.Message
import com.keytalk.app.domain.model.MessageRole
import com.keytalk.app.domain.model.MessageStatus
import com.keytalk.app.domain.model.ModelProfile
import com.keytalk.app.domain.model.PreparedImageAttachment
import com.keytalk.app.domain.model.ProtocolAdapter
import com.keytalk.app.domain.repository.ConfigurationRepository
import com.keytalk.app.domain.repository.ConversationRepository
import com.keytalk.app.domain.repository.ConversationSummaryRepository
import com.keytalk.app.domain.repository.FailoverPolicyRepository
import com.keytalk.app.domain.repository.MessageRepository
import com.keytalk.app.network.ChatNetworkClient
import com.keytalk.app.provider.ChatError
import com.keytalk.app.provider.ChatErrorType
import com.keytalk.app.provider.ChatRequest
import com.keytalk.app.provider.ChatResponse
import com.keytalk.app.provider.ProviderException
import com.keytalk.app.provider.StreamEvent
import com.keytalk.app.provider.Usage
import com.keytalk.app.security.CredentialStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit

class ChatServiceTest {
    private val now: Instant = Instant.parse("2026-06-27T00:00:00Z")
    private val clock: Clock = Clock.fixed(now, ZoneOffset.UTC)

    @Test
    fun nonStreamingChatSavesUserAndAssistantMessages() = runTest {
        val fixture = chatFixture(modelSupportsStreaming = false)
        fixture.network.nextResponse = ChatResponse(
            requestId = "ignored",
            messageText = "pong",
            finishReason = "stop",
            usage = Usage(inputTokens = 1, outputTokens = 2, totalTokens = 3, providerReported = true),
        )

        val assistant = fixture.service.sendMessage(fixture.conversation.id, "ping")

        val messages = fixture.messages.listMessages(fixture.conversation.id)
        assertEquals(MessageStatus.COMPLETED, assistant.status)
        assertEquals(listOf(MessageRole.USER, MessageRole.ASSISTANT), messages.map { it.role })
        assertEquals("ping", messages[0].content)
        assertEquals("pong", messages[1].content)
        assertEquals("pong", fixture.conversations.getConversation(fixture.conversation.id)!!.lastMessagePreview)
        assertEquals(false, fixture.network.lastRequest!!.stream)
        assertEquals("sk-test-secret", fixture.network.lastRequest!!.apiKey)
    }

    @Test
    fun streamingChatAppendsDeltasAndCompletesAssistantMessage() = runTest {
        val fixture = chatFixture(modelSupportsStreaming = true)
        fixture.network.streamEvents = listOf(
            StreamEvent.DeltaText("req", "hel"),
            StreamEvent.DeltaText("req", "lo"),
            StreamEvent.MessageCompleted(
                requestId = "req",
                finishReason = "stop",
                usage = Usage(inputTokens = 2, outputTokens = 3, totalTokens = 5, providerReported = true),
            ),
        )

        val assistant = fixture.service.sendMessage(fixture.conversation.id, "ping")

        assertEquals(MessageStatus.COMPLETED, assistant.status)
        assertEquals("hello", assistant.content)
        assertEquals(5, assistant.providerTotalTokens)
        assertEquals("hello", fixture.messages.listMessages(fixture.conversation.id).last().content)
        assertEquals(true, fixture.network.lastRequest!!.stream)
    }

    @Test
    fun streamingErrorAfterDeltaKeepsPartialContentAsInterrupted() = runTest {
        val fixture = chatFixture(modelSupportsStreaming = true)
        fixture.network.streamEvents = listOf(
            StreamEvent.DeltaText("req", "partial"),
            StreamEvent.Error("req", ChatError(ChatErrorType.STREAM_INTERRUPTED, "流式连接已中断。", retryable = true)),
        )

        val assistant = fixture.service.sendMessage(fixture.conversation.id, "ping")

        assertEquals(MessageStatus.INTERRUPTED, assistant.status)
        assertEquals("partial", assistant.content)
        assertEquals("partial", fixture.messages.listMessages(fixture.conversation.id).last().content)
    }

    @Test
    fun streamingErrorBeforeDeltaMarksAssistantMessageFailedWithoutDroppingUserMessage() = runTest {
        val fixture = chatFixture(modelSupportsStreaming = true)
        fixture.network.streamEvents = listOf(
            StreamEvent.Error("req", ChatError(ChatErrorType.INVALID_API_KEY, "API Key 无效或未授权。")),
        )

        val assistant = fixture.service.sendMessage(fixture.conversation.id, "ping")
        val messages = fixture.messages.listMessages(fixture.conversation.id)

        assertEquals(MessageStatus.FAILED, assistant.status)
        assertEquals("API Key 无效或未授权。", assistant.content)
        assertEquals(listOf(MessageRole.USER, MessageRole.ASSISTANT), messages.map { it.role })
        assertEquals("ping", messages.first().content)
    }

    @Test
    fun nonStreamingProviderErrorFailsOverToCandidateConnectionDefaultModel() = runTest {
        val primaryConnection = testConnection("conn-primary", "oumi", "cred-primary")
        val fallbackConnection = testConnection("conn-fallback", "jun", "cred-fallback")
        val primaryModel = testModel(
            id = "model-primary",
            connectionId = primaryConnection.id,
            displayName = "gpt-5.5",
            model = "gpt-5.5",
            supportsStreaming = false,
        )
        val fallbackModel = testModel(
            id = "model-fallback",
            connectionId = fallbackConnection.id,
            displayName = "gpt-5.4",
            model = "gpt-5.4",
            supportsStreaming = true,
            isDefault = true,
            updatedAt = now.plus(1, ChronoUnit.SECONDS),
        )
        val conversations = FakeConversationRepository()
        val conversation = conversations.createConversation("Chat", primaryModel.id, now)
        val network = FakeChatNetworkClient()
        network.sendHandler = { request ->
            if (request.model == primaryModel.model) {
                throw ProviderException(
                    ChatError(ChatErrorType.PERMISSION_DENIED, "当前账号或 API Key 无权访问该模型。"),
                )
            }
            ChatResponse(request.requestId, "fallback pong", "stop")
        }
        val service = ChatService(
            configurationRepository = FakeConfigurationRepository(
                listOf(primaryConnection, fallbackConnection),
                listOf(primaryModel, fallbackModel),
            ),
            conversationRepository = conversations,
            messageRepository = FakeMessageRepository(),
            credentialStore = FakeCredentialStore(
                mapOf(
                    primaryConnection.credentialId to "sk-primary",
                    fallbackConnection.credentialId to "sk-fallback",
                ),
            ),
            networkClient = network,
            failoverPolicyRepository = FakeFailoverPolicyRepository(setOf(fallbackConnection.id)),
            clock = clock,
        )

        val assistant = service.sendMessage(conversation.id, "ping")

        assertEquals(MessageStatus.COMPLETED, assistant.status)
        assertEquals("fallback pong", assistant.content)
        assertEquals(fallbackModel.id, conversations.getConversation(conversation.id)!!.modelProfileId)
        assertEquals(listOf("gpt-5.5", "gpt-5.4"), network.requests.map { it.model })
        assertEquals(listOf(false, false), network.requests.map { it.stream })
    }

    @Test
    fun streamingErrorBeforeDeltaFailsOverToCandidateConnection() = runTest {
        val primaryConnection = testConnection("conn-primary", "oumi", "cred-primary")
        val fallbackConnection = testConnection("conn-fallback", "jun", "cred-fallback")
        val primaryModel = testModel(
            id = "model-primary",
            connectionId = primaryConnection.id,
            displayName = "gpt-5.5",
            model = "gpt-5.5",
            supportsStreaming = true,
        )
        val fallbackModel = testModel(
            id = "model-fallback",
            connectionId = fallbackConnection.id,
            displayName = "gpt-5.4",
            model = "gpt-5.4",
            supportsStreaming = true,
            isDefault = true,
            updatedAt = now.plus(1, ChronoUnit.SECONDS),
        )
        val conversations = FakeConversationRepository()
        val messages = FakeMessageRepository()
        val conversation = conversations.createConversation("Chat", primaryModel.id, now)
        val network = FakeChatNetworkClient()
        network.streamEvents = listOf(
            StreamEvent.Error("req", ChatError(ChatErrorType.PERMISSION_DENIED, "当前账号或 API Key 无权访问该模型。")),
        )
        network.sendHandler = { request ->
            ChatResponse(request.requestId, "stream recovered by fallback", "stop")
        }
        val service = ChatService(
            configurationRepository = FakeConfigurationRepository(
                listOf(primaryConnection, fallbackConnection),
                listOf(primaryModel, fallbackModel),
            ),
            conversationRepository = conversations,
            messageRepository = messages,
            credentialStore = FakeCredentialStore(
                mapOf(
                    primaryConnection.credentialId to "sk-primary",
                    fallbackConnection.credentialId to "sk-fallback",
                ),
            ),
            networkClient = network,
            failoverPolicyRepository = FakeFailoverPolicyRepository(setOf(fallbackConnection.id)),
            clock = clock,
        )

        val assistant = service.sendMessage(conversation.id, "ping")

        assertEquals(MessageStatus.COMPLETED, assistant.status)
        assertEquals("stream recovered by fallback", assistant.content)
        assertEquals("stream recovered by fallback", messages.listMessages(conversation.id).last().content)
        assertEquals(fallbackModel.id, conversations.getConversation(conversation.id)!!.modelProfileId)
        assertEquals(listOf("gpt-5.5", "gpt-5.4"), network.requests.map { it.model })
        assertEquals(listOf(true, false), network.requests.map { it.stream })
    }

    @Test
    fun missingCredentialDoesNotCreateNetworkRequest() = runTest {
        val fixture = chatFixture(modelSupportsStreaming = false, apiKey = null)

        val failure = runCatching {
            fixture.service.sendMessage(fixture.conversation.id, "ping")
        }.exceptionOrNull()

        assertTrue(failure is IllegalStateException)
        assertEquals(null, fixture.network.lastRequest)
    }

    @Test
    fun imageMessageRequiresVisionModel() = runTest {
        val fixture = chatFixture(modelSupportsStreaming = false, modelSupportsVision = false)

        val failure = runCatching {
            fixture.service.sendMessage(fixture.conversation.id, "describe", images = listOf(testImage()))
        }.exceptionOrNull()

        assertTrue(failure is com.keytalk.app.provider.ProviderException)
        assertEquals(ChatErrorType.UNSUPPORTED_VISION, (failure as com.keytalk.app.provider.ProviderException).error.type)
        assertEquals(null, fixture.network.lastRequest)
    }

    @Test
    fun imageMessageStoresAttachmentAndSendsChatImage() = runTest {
        val fixture = chatFixture(modelSupportsStreaming = false, modelSupportsVision = true)

        fixture.service.sendMessage(fixture.conversation.id, "describe", images = listOf(testImage()))

        val userMessage = fixture.messages.listMessages(fixture.conversation.id).first()
        assertEquals(1, userMessage.attachments.size)
        assertEquals("image/jpeg", userMessage.attachments.single().mimeType)
        assertEquals(1, fixture.network.lastRequest!!.messages.first().images.size)
    }

    @Test
    fun overBudgetHistoryPersistsRollingSummary() = runTest {
        val fixture = chatFixture(
            modelSupportsStreaming = false,
            modelSupportsVision = false,
            defaultContextWindow = 12,
        )
        fixture.messages.saveMessage(
            Message(
                conversationId = fixture.conversation.id,
                role = MessageRole.USER,
                content = "older message that should be summarized",
                tokenEstimate = 20,
                createdAt = now.minusSeconds(10),
                updatedAt = now.minusSeconds(10),
            ),
        )

        fixture.service.sendMessage(fixture.conversation.id, "new")

        val summary = fixture.summaries.listSummaries(fixture.conversation.id).single()
        assertTrue(summary.summaryContent.contains("滚动摘要"))
        assertTrue(fixture.network.lastRequest!!.messages.first().content.contains("滚动摘要"))
    }

    private suspend fun chatFixture(
        modelSupportsStreaming: Boolean,
        modelSupportsVision: Boolean = false,
        defaultContextWindow: Int = 128_000,
        apiKey: String? = "sk-test-secret",
    ): ChatFixture {
        val connection = ConnectionProfile(
            id = "conn-1",
            name = "Relay",
            protocolAdapter = ProtocolAdapter.OPENAI_COMPATIBLE,
            baseUrl = "https://relay.example",
            credentialId = "cred-1",
            createdAt = now,
            updatedAt = now,
        )
        val model = ModelProfile(
            id = "model-1",
            connectionId = connection.id,
            displayName = "Demo",
            model = "demo-model",
            supportsStreaming = modelSupportsStreaming,
            supportsVision = modelSupportsVision,
            defaultContextWindow = defaultContextWindow,
            createdAt = now,
            updatedAt = now,
        )
        val configuration = FakeConfigurationRepository(connection, model)
        val conversations = FakeConversationRepository()
        val messages = FakeMessageRepository()
        val summaries = FakeSummaryRepository()
        val credentials = FakeCredentialStore(apiKey?.let { mapOf("cred-1" to it) }.orEmpty())
        val network = FakeChatNetworkClient()
        val conversation = conversations.createConversation("Chat", model.id, now)
        val service = ChatService(
            configurationRepository = configuration,
            conversationRepository = conversations,
            messageRepository = messages,
            credentialStore = credentials,
            networkClient = network,
            summaryRepository = summaries,
            clock = clock,
        )
        return ChatFixture(service, conversations, messages, summaries, network, conversation)
    }

    private fun testImage(): PreparedImageAttachment =
        PreparedImageAttachment(
            localEncryptedUri = "file:///encrypted/test.jpg.enc",
            mimeType = "image/jpeg",
            width = 64,
            height = 64,
            sizeBytes = 12,
            sha256 = "abc",
            base64Data = "aGVsbG8=",
            createdAt = now,
        )

    private fun testConnection(
        id: String,
        name: String,
        credentialId: String,
    ): ConnectionProfile =
        ConnectionProfile(
            id = id,
            name = name,
            protocolAdapter = ProtocolAdapter.OPENAI_COMPATIBLE,
            baseUrl = "https://$id.example",
            credentialId = credentialId,
            createdAt = now,
            updatedAt = now,
        )

    private fun testModel(
        id: String,
        connectionId: String,
        displayName: String,
        model: String,
        supportsStreaming: Boolean,
        supportsVision: Boolean = false,
        isDefault: Boolean = false,
        updatedAt: Instant = now,
    ): ModelProfile =
        ModelProfile(
            id = id,
            connectionId = connectionId,
            displayName = displayName,
            model = model,
            supportsStreaming = supportsStreaming,
            supportsVision = supportsVision,
            isDefault = isDefault,
            createdAt = now,
            updatedAt = updatedAt,
        )
}

private data class ChatFixture(
    val service: ChatService,
    val conversations: FakeConversationRepository,
    val messages: FakeMessageRepository,
    val summaries: FakeSummaryRepository,
    val network: FakeChatNetworkClient,
    val conversation: Conversation,
)

private class FakeConfigurationRepository(
    initialConnections: List<ConnectionProfile>,
    initialModels: List<ModelProfile>,
) : ConfigurationRepository {
    constructor(connection: ConnectionProfile, model: ModelProfile) : this(listOf(connection), listOf(model))

    private val connections = MutableStateFlow(initialConnections)
    private val models = MutableStateFlow(initialModels)

    override fun observeConnections(): Flow<List<ConnectionProfile>> = connections
    override fun observeModels(): Flow<List<ModelProfile>> = models
    override fun observeModels(connectionId: String): Flow<List<ModelProfile>> =
        models.map { values -> values.filter { it.connectionId == connectionId } }

    override suspend fun listConnections(): List<ConnectionProfile> = connections.value
    override suspend fun listModels(): List<ModelProfile> = models.value
    override suspend fun getConnection(connectionId: String): ConnectionProfile? =
        connections.value.firstOrNull { it.id == connectionId }

    override suspend fun getModel(modelProfileId: String): ModelProfile? =
        models.value.firstOrNull { it.id == modelProfileId }

    override suspend fun getDefaultModel(): ModelProfile? = models.value.firstOrNull()
    override suspend fun saveConnection(connectionProfile: ConnectionProfile) {
        connections.value = connections.value.filterNot { it.id == connectionProfile.id } + connectionProfile
    }

    override suspend fun saveModel(modelProfile: ModelProfile) {
        models.value = models.value.filterNot { it.id == modelProfile.id } + modelProfile
    }

    override suspend fun deleteConnection(connectionProfile: ConnectionProfile) {
        connections.value = connections.value.filterNot { it.id == connectionProfile.id }
    }

    override suspend fun deleteModel(modelProfile: ModelProfile) {
        models.value = models.value.filterNot { it.id == modelProfile.id }
    }
}

private class FakeFailoverPolicyRepository(initialConnectionIds: Set<String>) : FailoverPolicyRepository {
    private var connectionIds: Set<String> = initialConnectionIds

    override fun getEnabledConnectionIds(): Set<String> = connectionIds

    override fun saveEnabledConnectionIds(connectionIds: Set<String>) {
        this.connectionIds = connectionIds
    }
}

private class FakeConversationRepository : ConversationRepository {
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
        val conversation = Conversation(title = title, modelProfileId = modelProfileId, createdAt = now, updatedAt = now)
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
        conversations.value = conversations.value.map {
            if (it.id == conversationId) it.restoreFromTrash(Instant.now()) else it
        }
    }

    override suspend fun hardDelete(conversationId: String) {
        conversations.value = conversations.value.filterNot { it.id == conversationId }
    }

    override suspend fun purgeExpiredTrash(now: Instant): Int {
        val expired = conversations.value.filter { it.isTrashExpired(now) }
        conversations.value = conversations.value - expired.toSet()
        return expired.size
    }

    override suspend fun clearTrash(): Int {
        val trash = conversations.value.filter { it.deleteState == DeleteState.TRASH }
        conversations.value = conversations.value - trash.toSet()
        return trash.size
    }
}

private class FakeMessageRepository : MessageRepository {
    private val messages = MutableStateFlow<List<Message>>(emptyList())

    override fun observeMessages(conversationId: String): Flow<List<Message>> =
        messages.map { values -> values.filter { it.conversationId == conversationId } }

    override suspend fun listMessages(conversationId: String): List<Message> =
        observeMessages(conversationId).first()

    override suspend fun getMessage(messageId: String): Message? =
        messages.value.firstOrNull { it.id == messageId }

    override suspend fun saveMessage(message: Message) {
        messages.value = messages.value.filterNot { it.id == message.id } + message
    }

    override suspend fun saveAttachment(attachment: Attachment) {
        messages.value = messages.value.map {
            if (it.id == attachment.messageId) it.copy(attachments = it.attachments + attachment) else it
        }
    }

    override suspend fun updateMessage(message: Message) {
        saveMessage(message)
    }

    override suspend fun appendAssistantDelta(messageId: String, delta: String) {
        messages.value = messages.value.map {
            if (it.id == messageId) {
                it.copy(content = it.content + delta, status = MessageStatus.STREAMING)
            } else {
                it
            }
        }
    }
}

private class FakeSummaryRepository : ConversationSummaryRepository {
    private val summaries = MutableStateFlow<List<ConversationSummary>>(emptyList())

    override fun observeSummaries(conversationId: String): Flow<List<ConversationSummary>> =
        summaries.map { values -> values.filter { it.conversationId == conversationId } }

    override suspend fun listSummaries(conversationId: String): List<ConversationSummary> =
        summaries.value.filter { it.conversationId == conversationId }

    override suspend fun listAllSummaries(): List<ConversationSummary> = summaries.value

    override suspend fun findCoveringRange(
        conversationId: String,
        coveredMessageStartId: String?,
        coveredMessageEndId: String?,
    ): ConversationSummary? =
        summaries.value.firstOrNull {
            it.conversationId == conversationId &&
                it.coveredMessageStartId == coveredMessageStartId &&
                it.coveredMessageEndId == coveredMessageEndId
        }

    override suspend fun saveSummary(summary: ConversationSummary) {
        summaries.value = summaries.value.filterNot { it.id == summary.id } + summary
    }
}

private class FakeCredentialStore(initial: Map<String, String>) : CredentialStore {
    private val credentials = initial.toMutableMap()

    override suspend fun saveApiKey(credentialId: String, apiKey: String) {
        credentials[credentialId] = apiKey
    }

    override suspend fun readApiKey(credentialId: String): String? = credentials[credentialId]

    override suspend fun deleteApiKey(credentialId: String) {
        credentials.remove(credentialId)
    }
}

private class FakeChatNetworkClient : ChatNetworkClient {
    var lastRequest: ChatRequest? = null
    var nextResponse: ChatResponse = ChatResponse("req", "ok", "stop")
    var streamEvents: List<StreamEvent> = emptyList()
    var sendHandler: (suspend (ChatRequest) -> ChatResponse)? = null
    var streamHandler: ((ChatRequest) -> List<StreamEvent>)? = null
    val requests: MutableList<ChatRequest> = mutableListOf()

    override suspend fun send(request: ChatRequest): ChatResponse {
        lastRequest = request
        requests.add(request)
        return (sendHandler?.invoke(request) ?: nextResponse).copy(requestId = request.requestId)
    }

    override fun stream(request: ChatRequest): Flow<StreamEvent> {
        lastRequest = request
        requests.add(request)
        return (streamHandler?.invoke(request) ?: streamEvents).map { event ->
            when (event) {
                is StreamEvent.DeltaText -> event.copy(requestId = request.requestId)
                is StreamEvent.MessageCompleted -> event.copy(requestId = request.requestId)
                is StreamEvent.UsageReported -> event.copy(requestId = request.requestId)
                is StreamEvent.Error -> event.copy(requestId = request.requestId)
                is StreamEvent.Heartbeat -> event.copy(requestId = request.requestId)
            }
        }.asFlow()
    }

    override fun cancel(requestId: String) = Unit
}
