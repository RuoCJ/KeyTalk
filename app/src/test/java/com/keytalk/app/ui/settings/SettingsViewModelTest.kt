package com.keytalk.app.ui.settings

import com.keytalk.app.backup.BackupImportResult
import com.keytalk.app.backup.KeyTalkBackupService
import com.keytalk.app.backup.NoopBackupTransactionRunner
import com.keytalk.app.domain.model.Attachment
import com.keytalk.app.domain.model.ConnectionProfile
import com.keytalk.app.domain.model.Conversation
import com.keytalk.app.domain.model.Message
import com.keytalk.app.domain.model.ModelProfile
import com.keytalk.app.domain.model.ProtocolAdapter
import com.keytalk.app.domain.repository.ConfigurationRepository
import com.keytalk.app.domain.repository.ConversationRepository
import com.keytalk.app.domain.repository.FailoverPolicyRepository
import com.keytalk.app.domain.repository.MessageRepository
import com.keytalk.app.network.ChatNetworkClient
import com.keytalk.app.provider.ChatRequest
import com.keytalk.app.provider.ChatResponse
import com.keytalk.app.provider.StreamEvent
import com.keytalk.app.security.CredentialStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun savingNewConnectionDoesNotReplaceExistingConnection() = runTest {
        val configuration = FakeConfigurationRepository()
        val credentials = FakeCredentialStore()
        val viewModel = createViewModel(configuration, credentials)

        viewModel.saveConnection(
            connectionId = null,
            name = "连接 A",
            protocolAdapter = ProtocolAdapter.OPENAI_COMPATIBLE,
            baseUrl = "https://a.example/v1",
            apiKey = "sk-a",
        )
        advanceUntilIdle()

        viewModel.saveConnection(
            connectionId = null,
            name = "连接 B",
            protocolAdapter = ProtocolAdapter.OPENAI_COMPATIBLE,
            baseUrl = "https://b.example/v1",
            apiKey = "sk-b",
        )
        advanceUntilIdle()

        assertEquals(2, configuration.connections.value.size)
        assertEquals(setOf("连接 A", "连接 B"), configuration.connections.value.map { it.name }.toSet())
        assertEquals(2, configuration.connections.value.map { it.id }.toSet().size)
        assertEquals(2, configuration.connections.value.map { it.credentialId }.toSet().size)
        assertEquals(2, credentials.apiKeys.size)
    }

    @Test
    fun editingConnectionUpdatesOnlyTargetConnection() = runTest {
        val configuration = FakeConfigurationRepository()
        val credentials = FakeCredentialStore()
        val viewModel = createViewModel(configuration, credentials)

        viewModel.saveConnection(
            connectionId = null,
            name = "连接 A",
            protocolAdapter = ProtocolAdapter.OPENAI_COMPATIBLE,
            baseUrl = "https://a.example/v1",
            apiKey = "sk-a",
        )
        advanceUntilIdle()
        val original = configuration.connections.value.single()

        viewModel.saveConnection(
            connectionId = original.id,
            name = "连接 A 改名",
            protocolAdapter = ProtocolAdapter.CLAUDE_NATIVE,
            baseUrl = "https://claude.example",
            apiKey = "",
        )
        advanceUntilIdle()

        val updated = configuration.connections.value.single()
        assertEquals(original.id, updated.id)
        assertEquals(original.credentialId, updated.credentialId)
        assertEquals("连接 A 改名", updated.name)
        assertEquals(ProtocolAdapter.CLAUDE_NATIVE, updated.protocolAdapter)
        assertEquals("https://claude.example", updated.baseUrl)
        assertEquals("sk-a", credentials.apiKeys[original.credentialId])
    }

    @Test
    fun savingSameModelAgainUpdatesExistingModelInsteadOfDuplicating() = runTest {
        val configuration = FakeConfigurationRepository()
        val credentials = FakeCredentialStore()
        val viewModel = createViewModel(configuration, credentials)
        backgroundScope.launch { viewModel.uiState.collect { } }

        viewModel.saveConnection(
            connectionId = null,
            name = "连接 A",
            protocolAdapter = ProtocolAdapter.OPENAI_COMPATIBLE,
            baseUrl = "https://a.example/v1",
            apiKey = "sk-a",
        )
        advanceUntilIdle()

        viewModel.saveModel(
            displayName = "Demo",
            modelName = "demo-model",
            streamEnabled = true,
            visionEnabled = false,
            defaultContextWindow = 128_000,
            supports1mContext = false,
            enable1mContext = false,
        )
        advanceUntilIdle()
        val original = configuration.models.value.single()

        viewModel.saveModel(
            displayName = "Demo Updated",
            modelName = "demo-model",
            streamEnabled = false,
            visionEnabled = true,
            defaultContextWindow = 200_000,
            supports1mContext = false,
            enable1mContext = false,
        )
        advanceUntilIdle()

        val updated = configuration.models.value.single()
        assertEquals(original.id, updated.id)
        assertEquals("Demo Updated", updated.displayName)
        assertEquals(false, updated.supportsStreaming)
        assertEquals(true, updated.supportsVision)
        assertEquals(200_000, updated.defaultContextWindow)
    }

    @Test
    fun uiStateDeduplicatesModelsWithSameConnectionAndModelName() = runTest {
        val configuration = FakeConfigurationRepository()
        val credentials = FakeCredentialStore()
        val viewModel = createViewModel(configuration, credentials)
        configuration.connections.value = listOf(
            ConnectionProfile(
                id = "conn-1",
                name = "君中转",
                protocolAdapter = ProtocolAdapter.OPENAI_COMPATIBLE,
                baseUrl = "https://relay.example/v1",
                credentialId = "cred-1",
                createdAt = Instant.EPOCH,
                updatedAt = Instant.EPOCH,
            ),
        )
        configuration.models.value = listOf(
            ModelProfile(
                id = "model-new",
                connectionId = "conn-1",
                displayName = "gpt5.4",
                model = "gpt5.4",
                createdAt = Instant.EPOCH,
                updatedAt = Instant.EPOCH.plusSeconds(2),
            ),
            ModelProfile(
                id = "model-old",
                connectionId = "conn-1",
                displayName = "GPT5.4 duplicate",
                model = " GPT5.4 ",
                createdAt = Instant.EPOCH,
                updatedAt = Instant.EPOCH.plusSeconds(1),
            ),
        )
        backgroundScope.launch { viewModel.uiState.collect { } }
        advanceUntilIdle()

        assertEquals(2, viewModel.uiState.value.models.size)
        assertEquals(1, viewModel.uiState.value.modelsForSelectedConnection.size)
        assertEquals("gpt5.4", viewModel.uiState.value.modelsForSelectedConnection.single().model)
    }

    @Test
    fun savingModelCleansExistingDuplicatesForSameConnection() = runTest {
        val configuration = FakeConfigurationRepository()
        val credentials = FakeCredentialStore()
        val viewModel = createViewModel(configuration, credentials)
        configuration.connections.value = listOf(
            ConnectionProfile(
                id = "conn-1",
                name = "君中转",
                protocolAdapter = ProtocolAdapter.OPENAI_COMPATIBLE,
                baseUrl = "https://relay.example/v1",
                credentialId = "cred-1",
                createdAt = Instant.EPOCH,
                updatedAt = Instant.EPOCH,
            ),
        )
        configuration.models.value = listOf(
            ModelProfile(
                id = "model-keep",
                connectionId = "conn-1",
                displayName = "gpt5.4",
                model = "gpt5.4",
                createdAt = Instant.EPOCH,
                updatedAt = Instant.EPOCH.plusSeconds(2),
            ),
            ModelProfile(
                id = "model-delete",
                connectionId = "conn-1",
                displayName = "GPT5.4 duplicate",
                model = " GPT5.4 ",
                createdAt = Instant.EPOCH,
                updatedAt = Instant.EPOCH.plusSeconds(1),
            ),
        )
        backgroundScope.launch { viewModel.uiState.collect { } }
        advanceUntilIdle()

        viewModel.saveModel(
            displayName = "GPT 5.4",
            modelName = "gpt5.4",
            streamEnabled = true,
            visionEnabled = true,
            defaultContextWindow = 1_000_000,
            supports1mContext = true,
            enable1mContext = false,
        )
        advanceUntilIdle()

        assertEquals(1, configuration.models.value.size)
        assertEquals("model-keep", configuration.models.value.single().id)
        assertEquals("GPT 5.4", configuration.models.value.single().displayName)
    }

    @Test
    fun backupImportFeedbackReportsAttachmentCredentialAndExpiredTrashDetails() {
        val result = BackupImportResult(
            importedConnections = 1,
            importedModels = 2,
            importedConversations = 3,
            importedMessages = 4,
            importedAttachments = 5,
            remappedCredentials = 6,
            skippedExpiredTrash = 7,
        )

        assertEquals(
            "导入完成：连接 1，模型 2，会话 3，消息 4。 附件 5 个已迁移。 API Key 已重新写入本机安全存储 6 个。 已跳过过期回收站 7 个。",
            result.toSettingsFeedbackMessage(),
        )
    }

    @Test
    fun backupImportFeedbackOmitsZeroOptionalDetails() {
        val result = BackupImportResult(
            importedConnections = 1,
            importedModels = 1,
            importedConversations = 0,
            importedMessages = 0,
            importedAttachments = 0,
            remappedCredentials = 0,
            skippedExpiredTrash = 0,
        )

        assertEquals(
            "导入完成：连接 1，模型 1，会话 0，消息 0。",
            result.toSettingsFeedbackMessage(),
        )
    }

    @Test
    fun defaultBackupFileNameIncludesLocalTimestampAndMvpMarker() {
        val fileName = defaultBackupFileName(
            now = Instant.parse("2026-06-28T11:15:59Z"),
            zoneId = ZoneId.of("Asia/Shanghai"),
        )

        assertEquals("keytalk-backup-20260628-191559-mvp-b.json", fileName)
    }

    @Test
    fun defaultBackupFileNameAvoidsGenericOrPathUnsafeName() {
        val fileName = defaultBackupFileName(
            now = Instant.parse("2026-06-28T11:15:59Z"),
            zoneId = ZoneId.of("UTC"),
        )

        assertFalse(fileName == "keytalk-backup.json")
        assertTrue(fileName.startsWith("keytalk-backup-20260628-111559-"))
        assertTrue(fileName.endsWith(".json"))
        assertFalse(fileName.any { it in charArrayOf('/', '\\', ':', '*', '?', '"', '<', '>', '|') })
    }

    private fun createViewModel(
        configurationRepository: FakeConfigurationRepository,
        credentialStore: FakeCredentialStore,
    ): SettingsViewModel =
        SettingsViewModel(
            configurationRepository = configurationRepository,
            credentialStore = credentialStore,
            networkClient = FakeChatNetworkClient,
            backupService = KeyTalkBackupService(
                configurationRepository = configurationRepository,
                conversationRepository = FakeConversationRepository,
                messageRepository = FakeMessageRepository,
                credentialStore = credentialStore,
                transactionRunner = NoopBackupTransactionRunner,
            ),
            failoverPolicyRepository = FakeFailoverPolicyRepository(),
        )
}

private class FakeConfigurationRepository : ConfigurationRepository {
    val connections = MutableStateFlow<List<ConnectionProfile>>(emptyList())
    val models = MutableStateFlow<List<ModelProfile>>(emptyList())

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

    override suspend fun getDefaultModel(): ModelProfile? = models.value.firstOrNull { it.isDefault }
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

private class FakeCredentialStore : CredentialStore {
    val apiKeys = mutableMapOf<String, String>()

    override suspend fun saveApiKey(credentialId: String, apiKey: String) {
        apiKeys[credentialId] = apiKey
    }

    override suspend fun readApiKey(credentialId: String): String? = apiKeys[credentialId]

    override suspend fun deleteApiKey(credentialId: String) {
        apiKeys.remove(credentialId)
    }
}

private object FakeChatNetworkClient : ChatNetworkClient {
    override suspend fun send(request: ChatRequest): ChatResponse =
        error("这些测试不会使用网络。")

    override fun stream(request: ChatRequest): Flow<StreamEvent> = emptyFlow()
    override fun cancel(requestId: String) = Unit
}

private class FakeFailoverPolicyRepository : FailoverPolicyRepository {
    private var enabledConnectionIds = emptySet<String>()
    override fun getEnabledConnectionIds(): Set<String> = enabledConnectionIds
    override fun saveEnabledConnectionIds(connectionIds: Set<String>) {
        enabledConnectionIds = connectionIds
    }
}

private object FakeConversationRepository : ConversationRepository {
    override fun observeConversation(conversationId: String): Flow<Conversation?> = MutableStateFlow(null)
    override fun observeActiveConversations(): Flow<List<Conversation>> = MutableStateFlow(emptyList())
    override fun observeTrashConversations(): Flow<List<Conversation>> = MutableStateFlow(emptyList())
    override suspend fun listConversations(includeTrash: Boolean): List<Conversation> = emptyList()
    override suspend fun getConversation(conversationId: String): Conversation? = null
    override suspend fun saveConversation(conversation: Conversation) = Unit
    override suspend fun createConversation(title: String, modelProfileId: String, now: Instant): Conversation =
        error("Conversation is not used by these tests")

    override suspend fun renameConversation(conversationId: String, title: String, now: Instant) = Unit
    override suspend fun switchConversationModel(conversationId: String, modelProfileId: String, now: Instant) = Unit
    override suspend fun updatePreview(conversationId: String, preview: String, now: Instant) = Unit
    override suspend fun moveToTrash(conversationId: String, now: Instant) = Unit
    override suspend fun restoreFromTrash(conversationId: String) = Unit
    override suspend fun hardDelete(conversationId: String) = Unit
    override suspend fun purgeExpiredTrash(now: Instant): Int = 0
    override suspend fun clearTrash(): Int = 0
}

private object FakeMessageRepository : MessageRepository {
    override fun observeMessages(conversationId: String): Flow<List<Message>> = MutableStateFlow(emptyList())
    override suspend fun listMessages(conversationId: String): List<Message> = emptyList()
    override suspend fun getMessage(messageId: String): Message? = null
    override suspend fun saveMessage(message: Message) = Unit
    override suspend fun saveAttachment(attachment: Attachment) = Unit
    override suspend fun updateMessage(message: Message) = Unit
    override suspend fun appendAssistantDelta(messageId: String, delta: String) = Unit
}
