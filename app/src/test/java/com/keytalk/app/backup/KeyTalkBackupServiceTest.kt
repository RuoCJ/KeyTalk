package com.keytalk.app.backup

import com.keytalk.app.domain.model.Attachment
import com.keytalk.app.domain.model.AttachmentType
import com.keytalk.app.domain.model.ConnectionProfile
import com.keytalk.app.domain.model.Conversation
import com.keytalk.app.domain.model.DeleteState
import com.keytalk.app.domain.model.Message
import com.keytalk.app.domain.model.MessageRole
import com.keytalk.app.domain.model.MessageStatus
import com.keytalk.app.domain.model.ModelProfile
import com.keytalk.app.domain.model.ProtocolAdapter
import com.keytalk.app.domain.repository.ConfigurationRepository
import com.keytalk.app.domain.repository.ConversationRepository
import com.keytalk.app.domain.repository.MessageRepository
import com.keytalk.app.security.CredentialStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class KeyTalkBackupServiceTest {
    private val now = Instant.parse("2026-06-28T00:00:00Z")

    @Test
    fun exportsEncryptedBackupWithoutPlaintextAndImportsWithCredentialRemap() = runTest {
        val source = fixture()
        source.credentials.saveApiKey("cred-1", "sk-super-secret")
        val conversation = source.conversations.saveSeedConversation(includeTrash = true)
        val message = Message(
            id = "msg-1",
            conversationId = conversation.id,
            role = MessageRole.USER,
            content = "private message",
            status = MessageStatus.COMPLETED,
            createdAt = now,
            updatedAt = now,
        )
        source.messages.saveMessage(message)
        source.messages.saveAttachment(
            Attachment(
                id = "att-1",
                messageId = message.id,
                type = AttachmentType.IMAGE,
                localEncryptedUri = "local://att-1",
                mimeType = "image/jpeg",
                width = 10,
                height = 10,
                sizeBytes = 5,
                sha256 = "sha",
                createdAt = now,
            ),
        )
        source.attachments.bytes["local://att-1"] = "image".toByteArray()

        val backupJson = source.service.exportEncryptedBackup(
            password = "passphrase".toCharArray(),
            includeApiKeys = true,
            includeTrash = true,
        )

        assertTrue(backupJson.contains(""""appVersion":"0.2.0-mvp-b""""))
        assertFalse(backupJson.contains("sk-super-secret"))
        assertFalse(backupJson.contains("private message"))

        val target = fixture(empty = true)
        val result = target.service.importEncryptedBackup(backupJson, "passphrase".toCharArray())

        assertEquals(1, result.importedConnections)
        assertEquals(1, result.importedModels)
        assertEquals(1, result.importedConversations)
        assertEquals(1, result.importedMessages)
        assertEquals(1, result.importedAttachments)
        val importedConnection = target.configuration.listConnections().single()
        assertNotEquals("cred-1", importedConnection.credentialId)
        assertEquals("sk-super-secret", target.credentials.readApiKey(importedConnection.credentialId))
        assertEquals("private message", target.messages.listMessages("conv-1").single().content)
        assertTrue(target.attachments.bytes.values.any { it.decodeToString() == "image" })
    }

    @Test
    fun excludesTrashAndApiKeysByDefault() = runTest {
        val source = fixture()
        source.credentials.saveApiKey("cred-1", "sk-secret")
        source.conversations.saveSeedConversation(includeTrash = false)
        source.conversations.saveTrashConversation()

        val backupJson = source.service.exportEncryptedBackup(
            password = "passphrase".toCharArray(),
            includeApiKeys = false,
            includeTrash = false,
        )
        val preview = source.service.previewEncryptedBackup(backupJson, "passphrase".toCharArray())

        assertEquals(false, preview.includeApiKeys)
        assertEquals(false, preview.includeTrash)
        assertEquals(1, preview.conversations)
        assertFalse(backupJson.contains("sk-secret"))

        val target = fixture(empty = true)
        val result = target.service.importEncryptedBackup(backupJson, "passphrase".toCharArray())

        assertEquals(0, result.remappedCredentials)
        assertEquals("cred-1", target.configuration.listConnections().single().credentialId)
        assertEquals(null, target.credentials.readApiKey("cred-1"))
    }

    @Test
    fun overwriteImportClearsExistingLocalDataBeforeImport() = runTest {
        val source = fixture()
        source.conversations.saveSeedConversation(includeTrash = false)
        val backupJson = source.service.exportEncryptedBackup(
            password = "passphrase".toCharArray(),
            includeApiKeys = false,
            includeTrash = false,
        )
        val target = fixture(empty = true)
        target.configuration.connections.value = listOf(
            ConnectionProfile(
                id = "old-conn",
                name = "Old",
                protocolAdapter = ProtocolAdapter.OPENAI_COMPATIBLE,
                baseUrl = "https://old.example",
                credentialId = "old-cred",
                createdAt = now,
                updatedAt = now,
            ),
        )
        target.configuration.models.value = listOf(
            ModelProfile(
                id = "old-model",
                connectionId = "old-conn",
                displayName = "Old",
                model = "old",
                createdAt = now,
                updatedAt = now,
            ),
        )
        target.credentials.saveApiKey("old-cred", "sk-old")
        target.conversations.saveTrashConversation()

        target.service.importEncryptedBackup(backupJson, "passphrase".toCharArray(), BackupImportMode.OVERWRITE)

        assertEquals(listOf("conn-1"), target.configuration.listConnections().map { it.id })
        assertEquals(listOf("model-1"), target.configuration.listModels().map { it.id })
        assertEquals(null, target.credentials.readApiKey("old-cred"))
        assertEquals(listOf("conv-1"), target.conversations.listConversations(includeTrash = true).map { it.id })
    }

    @Test
    fun overwriteImportUsesInjectedTransactionRunnerForLocalCleanup() = runTest {
        val source = fixture()
        source.conversations.saveSeedConversation(includeTrash = false)
        val backupJson = source.service.exportEncryptedBackup(
            password = "passphrase".toCharArray(),
            includeApiKeys = false,
            includeTrash = false,
        )
        val transactionRunner = RecordingBackupTransactionRunner()
        val target = fixture(empty = true, transactionRunner = transactionRunner)
        target.configuration.connections.value = listOf(
            ConnectionProfile(
                id = "old-conn",
                name = "Old",
                protocolAdapter = ProtocolAdapter.OPENAI_COMPATIBLE,
                baseUrl = "https://old.example",
                credentialId = "old-cred",
                createdAt = now,
                updatedAt = now,
            ),
        )

        target.service.importEncryptedBackup(backupJson, "passphrase".toCharArray(), BackupImportMode.OVERWRITE)

        assertEquals(1, transactionRunner.runCount)
    }

    @Test
    fun importSkipsExpiredTrashConversations() = runTest {
        val source = fixture()
        source.conversations.saveSeedConversation(includeTrash = false)
        source.conversations.saveExpiredTrashConversation()
        val backupJson = source.service.exportEncryptedBackup(
            password = "passphrase".toCharArray(),
            includeApiKeys = false,
            includeTrash = true,
        )
        val target = fixture(empty = true)

        val result = target.service.importEncryptedBackup(backupJson, "passphrase".toCharArray())

        assertEquals(1, result.importedConversations)
        assertEquals(1, result.skippedExpiredTrash)
        assertEquals(listOf("conv-1"), target.conversations.listConversations(includeTrash = true).map { it.id })
    }

    @Test
    fun configOnlyImportImportsConfigurationAndApiKeysWithoutConversations() = runTest {
        val source = fixture()
        source.credentials.saveApiKey("cred-1", "sk-config-only")
        source.saveSeedConversationMessageAndAttachment()
        val backupJson = source.service.exportEncryptedBackup(
            password = "passphrase".toCharArray(),
            includeApiKeys = true,
            includeTrash = false,
        )
        val target = fixture(empty = true)

        val result = target.service.importEncryptedBackup(
            backupJson,
            "passphrase".toCharArray(),
            BackupImportMode.CONFIG_ONLY,
        )

        assertEquals(1, result.importedConnections)
        assertEquals(1, result.importedModels)
        assertEquals(0, result.importedConversations)
        assertEquals(0, result.importedMessages)
        assertEquals(0, result.importedAttachments)
        val importedConnection = target.configuration.listConnections().single()
        assertNotEquals("cred-1", importedConnection.credentialId)
        assertEquals("sk-config-only", target.credentials.readApiKey(importedConnection.credentialId))
        assertEquals(listOf("model-1"), target.configuration.listModels().map { it.id })
        assertEquals(emptyList<Conversation>(), target.conversations.listConversations(includeTrash = true))
        assertEquals(emptyList<Message>(), target.messages.listMessages("conv-1"))
        assertTrue(target.attachments.bytes.isEmpty())
    }

    @Test
    fun configOnlyImportDoesNotReportExpiredTrashSkipForUnimportedConversations() = runTest {
        val source = fixture()
        source.credentials.saveApiKey("cred-1", "sk-config-only")
        source.conversations.saveExpiredTrashConversation()
        val backupJson = source.service.exportEncryptedBackup(
            password = "passphrase".toCharArray(),
            includeApiKeys = true,
            includeTrash = true,
        )
        val target = fixture(empty = true)

        val result = target.service.importEncryptedBackup(
            backupJson,
            "passphrase".toCharArray(),
            BackupImportMode.CONFIG_ONLY,
        )

        assertEquals(1, result.importedConnections)
        assertEquals(1, result.importedModels)
        assertEquals(0, result.importedConversations)
        assertEquals(0, result.importedMessages)
        assertEquals(0, result.importedAttachments)
        assertEquals(1, result.remappedCredentials)
        assertEquals(0, result.skippedExpiredTrash)
        assertEquals(emptyList<Conversation>(), target.conversations.listConversations(includeTrash = true))
    }

    @Test
    fun conversationsOnlyImportImportsMessagesAndAttachmentsWithoutChangingConfiguration() = runTest {
        val source = fixture()
        source.saveSeedConversationMessageAndAttachment()
        val backupJson = source.service.exportEncryptedBackup(
            password = "passphrase".toCharArray(),
            includeApiKeys = false,
            includeTrash = false,
        )
        val target = fixture(empty = true)
        target.configuration.connections.value = listOf(
            ConnectionProfile(
                id = "existing-conn",
                name = "Existing",
                protocolAdapter = ProtocolAdapter.OPENAI_COMPATIBLE,
                baseUrl = "https://existing.example",
                credentialId = "existing-cred",
                createdAt = now,
                updatedAt = now,
            ),
        )
        target.configuration.models.value = listOf(
            ModelProfile(
                id = "existing-model",
                connectionId = "existing-conn",
                displayName = "Existing",
                model = "existing",
                createdAt = now,
                updatedAt = now,
            ),
        )
        target.credentials.saveApiKey("existing-cred", "sk-existing")

        val result = target.service.importEncryptedBackup(
            backupJson,
            "passphrase".toCharArray(),
            BackupImportMode.CONVERSATIONS_ONLY,
        )

        assertEquals(0, result.importedConnections)
        assertEquals(0, result.importedModels)
        assertEquals(1, result.importedConversations)
        assertEquals(1, result.importedMessages)
        assertEquals(1, result.importedAttachments)
        assertEquals(listOf("existing-conn"), target.configuration.listConnections().map { it.id })
        assertEquals(listOf("existing-model"), target.configuration.listModels().map { it.id })
        assertEquals("sk-existing", target.credentials.readApiKey("existing-cred"))
        assertEquals(listOf("conv-1"), target.conversations.listConversations(includeTrash = true).map { it.id })
        assertEquals("private message", target.messages.listMessages("conv-1").single().content)
        assertTrue(target.attachments.bytes.values.any { it.decodeToString() == "image" })
    }

    @Test
    fun importReportsFriendlyErrorForInvalidAttachmentBase64() = runTest {
        val backupJson = BackupCrypto().encryptPayload(
            payload = BackupPayload(
                schemaVersion = 1,
                exportOptions = BackupExportOptions(includeApiKeys = false, includeTrash = false),
                connections = emptyList(),
                models = emptyList(),
                conversations = listOf(
                    BackupConversation(
                        id = "conv-1",
                        title = "Chat",
                        modelProfileId = "model-1",
                        lastMessagePreview = "image",
                        deleteState = DeleteState.ACTIVE.name,
                        deletedAt = null,
                        purgeAfter = null,
                        createdAt = now.toString(),
                        updatedAt = now.toString(),
                    ),
                ),
                messages = listOf(
                    BackupMessage(
                        id = "msg-1",
                        conversationId = "conv-1",
                        role = MessageRole.USER.name,
                        content = "image",
                        status = MessageStatus.COMPLETED.name,
                        tokenEstimate = 1,
                        providerInputTokens = null,
                        providerOutputTokens = null,
                        providerTotalTokens = null,
                        createdAt = now.toString(),
                        updatedAt = now.toString(),
                    ),
                ),
                attachments = listOf(
                    BackupAttachment(
                        id = "att-1",
                        messageId = "msg-1",
                        type = AttachmentType.IMAGE.name,
                        localEncryptedUri = "local://att-1",
                        mimeType = "image/jpeg",
                        width = 10,
                        height = 10,
                        sizeBytes = 5,
                        sha256 = "sha",
                        encryptedBlob = "not-valid-base64!",
                        createdAt = now.toString(),
                    ),
                ),
            ),
            password = "passphrase".toCharArray(),
            appVersion = "test",
        )
        val target = fixture(empty = true)

        val failure = runCatching {
            target.service.importEncryptedBackup(backupJson, "passphrase".toCharArray())
        }.exceptionOrNull()

        assertTrue(failure is IllegalArgumentException)
        assertEquals("备份附件格式无效。", failure?.message)
    }

    private fun fixture(
        empty: Boolean = false,
        transactionRunner: BackupTransactionRunner = NoopBackupTransactionRunner,
    ): BackupFixture {
        val configuration = FakeConfigurationRepository()
        val conversations = FakeConversationRepository()
        val messages = FakeMessageRepository()
        val credentials = FakeCredentialStore()
        val attachments = FakeAttachmentStore()
        if (!empty) {
            configuration.connections.value = listOf(
                ConnectionProfile(
                    id = "conn-1",
                    name = "Relay",
                    protocolAdapter = ProtocolAdapter.OPENAI_COMPATIBLE,
                    baseUrl = "https://relay.example",
                    credentialId = "cred-1",
                    createdAt = now,
                    updatedAt = now,
                ),
            )
            configuration.models.value = listOf(
                ModelProfile(
                    id = "model-1",
                    connectionId = "conn-1",
                    displayName = "Demo",
                    model = "demo",
                    createdAt = now,
                    updatedAt = now,
                ),
            )
        }
        return BackupFixture(
            configuration,
            conversations,
            messages,
            credentials,
            attachments,
            KeyTalkBackupService(
                configurationRepository = configuration,
                conversationRepository = conversations,
                messageRepository = messages,
                credentialStore = credentials,
                transactionRunner = transactionRunner,
                attachmentStore = attachments,
            ),
        )
    }

    private suspend fun BackupFixture.saveSeedConversationMessageAndAttachment() {
        val conversation = conversations.saveSeedConversation(includeTrash = false)
        val message = Message(
            id = "msg-1",
            conversationId = conversation.id,
            role = MessageRole.USER,
            content = "private message",
            status = MessageStatus.COMPLETED,
            createdAt = now,
            updatedAt = now,
        )
        messages.saveMessage(message)
        messages.saveAttachment(
            Attachment(
                id = "att-1",
                messageId = message.id,
                type = AttachmentType.IMAGE,
                localEncryptedUri = "local://att-1",
                mimeType = "image/jpeg",
                width = 10,
                height = 10,
                sizeBytes = 5,
                sha256 = "sha",
                createdAt = now,
            ),
        )
        attachments.bytes["local://att-1"] = "image".toByteArray()
    }

    private fun FakeConversationRepository.saveSeedConversation(includeTrash: Boolean): Conversation {
        val conversation = Conversation(
            id = "conv-1",
            title = "Chat",
            modelProfileId = "model-1",
            createdAt = now,
            updatedAt = now,
            lastMessagePreview = "private message",
            deleteState = if (includeTrash) DeleteState.TRASH else DeleteState.ACTIVE,
            deletedAt = if (includeTrash) now else null,
            purgeAfter = if (includeTrash) now.plusSeconds(30 * 86400) else null,
        )
        conversations.value = conversations.value + conversation
        return conversation
    }

    private fun FakeConversationRepository.saveTrashConversation() {
        conversations.value = conversations.value + Conversation(
            id = "trash-1",
            title = "Trash",
            modelProfileId = "model-1",
            createdAt = now,
            updatedAt = now,
            deleteState = DeleteState.TRASH,
            deletedAt = now,
            purgeAfter = now.plusSeconds(30 * 86400),
        )
    }

    private fun FakeConversationRepository.saveExpiredTrashConversation() {
        conversations.value = conversations.value + Conversation(
            id = "expired-trash-1",
            title = "Expired Trash",
            modelProfileId = "model-1",
            createdAt = now,
            updatedAt = now,
            deleteState = DeleteState.TRASH,
            deletedAt = now.minusSeconds(172800),
            purgeAfter = now.minusSeconds(1),
        )
    }
}

private data class BackupFixture(
    val configuration: FakeConfigurationRepository,
    val conversations: FakeConversationRepository,
    val messages: FakeMessageRepository,
    val credentials: FakeCredentialStore,
    val attachments: FakeAttachmentStore,
    val service: KeyTalkBackupService,
)

private class RecordingBackupTransactionRunner : BackupTransactionRunner {
    var runCount: Int = 0
        private set

    override suspend fun <T> runInTransaction(block: suspend () -> T): T {
        runCount += 1
        return block()
    }
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

private class FakeConversationRepository : ConversationRepository {
    val conversations = MutableStateFlow<List<Conversation>>(emptyList())
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
        saveConversation(conversation)
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

    override suspend fun updatePreview(conversationId: String, preview: String, now: Instant) = Unit
    override suspend fun moveToTrash(conversationId: String, now: Instant) = Unit
    override suspend fun restoreFromTrash(conversationId: String) = Unit
    override suspend fun hardDelete(conversationId: String) {
        conversations.value = conversations.value.filterNot { it.id == conversationId }
    }
    override suspend fun purgeExpiredTrash(now: Instant): Int = 0
    override suspend fun clearTrash(): Int = 0
}

private class FakeMessageRepository : MessageRepository {
    private val messages = MutableStateFlow<List<Message>>(emptyList())
    override fun observeMessages(conversationId: String): Flow<List<Message>> =
        messages.map { values -> values.filter { it.conversationId == conversationId } }
    override suspend fun listMessages(conversationId: String): List<Message> =
        messages.value.filter { it.conversationId == conversationId }
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
    override suspend fun updateMessage(message: Message) = saveMessage(message)
    override suspend fun appendAssistantDelta(messageId: String, delta: String) = Unit
}

private class FakeCredentialStore : CredentialStore {
    private val values = mutableMapOf<String, String>()
    override suspend fun saveApiKey(credentialId: String, apiKey: String) {
        values[credentialId] = apiKey
    }
    override suspend fun readApiKey(credentialId: String): String? = values[credentialId]
    override suspend fun deleteApiKey(credentialId: String) {
        values.remove(credentialId)
    }
}

private class FakeAttachmentStore : BackupAttachmentStore {
    val bytes = linkedMapOf<String, ByteArray>()
    override suspend fun readPlainBytes(localEncryptedUri: String): ByteArray? = bytes[localEncryptedUri]
    override suspend fun writePlainBytes(bytes: ByteArray, mimeType: String, width: Int, height: Int): String {
        val uri = "imported://${this.bytes.size + 1}"
        this.bytes[uri] = bytes
        return uri
    }
}
