package com.keytalk.app.backup

import com.keytalk.app.config.AppConfig
import com.keytalk.app.domain.model.Attachment
import com.keytalk.app.domain.model.AttachmentType
import com.keytalk.app.domain.model.ConnectionProfile
import com.keytalk.app.domain.model.Conversation
import com.keytalk.app.domain.model.ConversationSummary
import com.keytalk.app.domain.model.DeleteState
import com.keytalk.app.domain.model.Message
import com.keytalk.app.domain.model.MessageRole
import com.keytalk.app.domain.model.MessageStatus
import com.keytalk.app.domain.model.ModelProfile
import com.keytalk.app.domain.model.ModelReasoningEffort
import com.keytalk.app.domain.model.ProtocolAdapter
import com.keytalk.app.domain.repository.ConfigurationRepository
import com.keytalk.app.domain.repository.ConversationRepository
import com.keytalk.app.domain.repository.ConversationSummaryRepository
import com.keytalk.app.domain.repository.MessageRepository
import com.keytalk.app.domain.repository.NoopConversationSummaryRepository
import com.keytalk.app.security.CredentialStore
import java.time.Clock
import java.time.Instant
import java.util.Base64
import java.util.UUID

class KeyTalkBackupService(
    private val configurationRepository: ConfigurationRepository,
    private val conversationRepository: ConversationRepository,
    private val messageRepository: MessageRepository,
    private val credentialStore: CredentialStore,
    private val transactionRunner: BackupTransactionRunner,
    private val attachmentStore: BackupAttachmentStore = NoopBackupAttachmentStore,
    private val summaryRepository: ConversationSummaryRepository = NoopConversationSummaryRepository,
    private val crypto: BackupCrypto = BackupCrypto(),
    private val clock: Clock = Clock.systemUTC(),
    private val appVersion: String = AppConfig.Backup.defaultAppVersion,
) {
    suspend fun exportEncryptedBackup(
        password: CharArray,
        includeApiKeys: Boolean,
        includeTrash: Boolean,
    ): String {
        val payload = buildPayload(includeApiKeys, includeTrash)
        return crypto.encryptPayload(payload, password, appVersion)
    }

    suspend fun importEncryptedBackup(
        backupJson: String,
        password: CharArray,
        mode: BackupImportMode = BackupImportMode.MERGE,
    ): BackupImportResult {
        val payload = crypto.decryptPayload(backupJson, password)
        require(payload.schemaVersion <= AppConfig.Backup.schemaVersion) { "备份 schema 版本过高，当前版本不支持导入。" }
        if (mode == BackupImportMode.OVERWRITE) {
            clearLocalDataForOverwrite()
        }

        val credentialMap = mutableMapOf<String, String>()
        val importableConversations = payload.conversations.filterNot { it.isExpiredTrash() }
        val skippedExpiredTrash =
            if (mode == BackupImportMode.CONFIG_ONLY) 0 else payload.conversations.size - importableConversations.size
        val importableConversationIds = importableConversations.map { it.id }.toSet()
        val importableMessages = payload.messages.filter { it.conversationId in importableConversationIds }
        val importableMessageIds = importableMessages.map { it.id }.toSet()
        val importableAttachments = payload.attachments.filter { it.messageId in importableMessageIds }
        val importableSummaries = payload.summaries.filter { it.conversationId in importableConversationIds }

        if (mode != BackupImportMode.CONVERSATIONS_ONLY) {
            payload.connections.forEach { connection ->
                val newCredentialId = if (connection.apiKey != null) {
                    "cred_import_${UUID.randomUUID()}"
                } else {
                    connection.credentialId
                }
                connection.apiKey?.let { credentialStore.saveApiKey(newCredentialId, it) }
                credentialMap[connection.credentialId] = newCredentialId
                configurationRepository.saveConnection(connection.toDomain(newCredentialId))
            }
            payload.models.forEach { model ->
                configurationRepository.saveModel(model.toDomain())
            }
        }

        if (mode != BackupImportMode.CONFIG_ONLY) {
            importableConversations.forEach { conversation ->
                conversationRepository.saveConversation(conversation.toDomain())
            }
            importableMessages.forEach { message ->
                messageRepository.saveMessage(message.toDomain())
            }
            importableAttachments.forEach { attachment ->
                val localUri = attachment.encryptedBlob?.let { blob ->
                    attachmentStore.writePlainBytes(
                        bytes = blob.fromBase64("备份附件"),
                        mimeType = attachment.mimeType,
                        width = attachment.width,
                        height = attachment.height,
                    )
                } ?: attachment.localEncryptedUri
                messageRepository.saveAttachment(attachment.toDomain(localUri))
            }
            importableSummaries.forEach { summary ->
                summaryRepository.saveSummary(summary.toDomain())
            }
        }

        return BackupImportResult(
            importedConnections = if (mode == BackupImportMode.CONVERSATIONS_ONLY) 0 else payload.connections.size,
            importedModels = if (mode == BackupImportMode.CONVERSATIONS_ONLY) 0 else payload.models.size,
            importedConversations = if (mode == BackupImportMode.CONFIG_ONLY) 0 else importableConversations.size,
            importedMessages = if (mode == BackupImportMode.CONFIG_ONLY) 0 else importableMessages.size,
            importedAttachments = if (mode == BackupImportMode.CONFIG_ONLY) 0 else importableAttachments.size,
            remappedCredentials = credentialMap.count { (originalCredentialId, importedCredentialId) ->
                originalCredentialId != importedCredentialId
            },
            skippedExpiredTrash = skippedExpiredTrash,
        )
    }

    private suspend fun clearLocalDataForOverwrite() {
        val credentialIds = runCatching {
            transactionRunner.runInTransaction {
                val connections = configurationRepository.listConnections()
                conversationRepository.listConversations(includeTrash = true)
                    .forEach { conversationRepository.hardDelete(it.id) }
                configurationRepository.listModels()
                    .forEach { configurationRepository.deleteModel(it) }
                connections.forEach { connection ->
                    configurationRepository.deleteConnection(connection)
                }
                connections.map { it.credentialId }
            }
        }.getOrElse { throwable ->
            throw IllegalStateException("清理本地数据失败，导入已中止。", throwable)
        }
        credentialIds.forEach { credentialId ->
            credentialStore.deleteApiKey(credentialId)
        }
    }

    suspend fun previewEncryptedBackup(backupJson: String, password: CharArray): BackupPreview {
        val payload = crypto.decryptPayload(backupJson, password)
        return BackupPreview(
            includeApiKeys = payload.exportOptions.includeApiKeys,
            includeTrash = payload.exportOptions.includeTrash,
            connections = payload.connections.size,
            models = payload.models.size,
            conversations = payload.conversations.size,
            messages = payload.messages.size,
            attachments = payload.attachments.size,
        )
    }

    private suspend fun buildPayload(includeApiKeys: Boolean, includeTrash: Boolean): BackupPayload {
        val conversations = conversationRepository.listConversations(includeTrash)
        val conversationIds = conversations.map { it.id }.toSet()
        val messages = conversations.flatMap { conversation ->
            messageRepository.listMessages(conversation.id)
        }
        return BackupPayload(
            schemaVersion = AppConfig.Backup.schemaVersion,
            exportOptions = BackupExportOptions(includeApiKeys, includeTrash),
            connections = configurationRepository.listConnections().map { connection ->
                connection.toBackup(
                    apiKey = if (includeApiKeys) credentialStore.readApiKey(connection.credentialId) else null,
                )
            },
            models = configurationRepository.listModels().map { it.toBackup() },
            conversations = conversations.map { it.toBackup() },
            messages = messages.map { it.toBackup() },
            attachments = messages
                .filter { it.conversationId in conversationIds }
                .flatMap { it.attachments }
                .map { it.toBackup() },
            summaries = summaryRepository.listAllSummaries()
                .filter { summary -> summary.conversationId in conversationIds }
                .map { it.toBackup() },
            settings = mapOf(
                "exported_at" to Instant.now(clock).toString(),
                "source" to "keytalk-android",
            ),
        )
    }

    private suspend fun Attachment.toBackup(): BackupAttachment =
        BackupAttachment(
            id = id,
            messageId = messageId,
            type = type.name,
            localEncryptedUri = localEncryptedUri,
            mimeType = mimeType,
            width = width,
            height = height,
            sizeBytes = sizeBytes,
            sha256 = sha256,
            encryptedBlob = attachmentStore.readPlainBytes(localEncryptedUri)?.base64(),
            createdAt = createdAt.toString(),
        )

    private fun ConnectionProfile.toBackup(apiKey: String?): BackupConnection =
        BackupConnection(
            id = id,
            name = name,
            protocolAdapter = protocolAdapter.name,
            baseUrl = baseUrl,
            credentialId = credentialId,
            apiKey = apiKey,
            createdAt = createdAt.toString(),
            updatedAt = updatedAt.toString(),
        )

    private fun ModelProfile.toBackup(): BackupModel =
        BackupModel(
            id = id,
            connectionId = connectionId,
            displayName = displayName,
            model = model,
            modelSource = modelSource,
            supportsStreaming = supportsStreaming,
            supportsVision = supportsVision,
            defaultContextWindow = defaultContextWindow,
            supports1mContext = supports1mContext,
            enable1mContext = enable1mContext,
            temperature = temperature,
            maxTokens = maxTokens,
            reasoningEffort = reasoningEffort?.name,
            isDefault = isDefault,
            createdAt = createdAt.toString(),
            updatedAt = updatedAt.toString(),
        )

    private fun Conversation.toBackup(): BackupConversation =
        BackupConversation(
            id = id,
            title = title,
            modelProfileId = modelProfileId,
            lastMessagePreview = lastMessagePreview,
            deleteState = deleteState.name,
            deletedAt = deletedAt?.toString(),
            purgeAfter = purgeAfter?.toString(),
            createdAt = createdAt.toString(),
            updatedAt = updatedAt.toString(),
        )

    private fun ConversationSummary.toBackup(): BackupSummary =
        BackupSummary(
            id = id,
            conversationId = conversationId,
            summaryContent = summaryContent,
            coveredMessageStartId = coveredMessageStartId,
            coveredMessageEndId = coveredMessageEndId,
            tokenEstimate = tokenEstimate,
            createdAt = createdAt.toString(),
            updatedAt = updatedAt.toString(),
        )

    private fun Message.toBackup(): BackupMessage =
        BackupMessage(
            id = id,
            conversationId = conversationId,
            role = role.name,
            content = content,
            status = status.name,
            tokenEstimate = tokenEstimate,
            providerInputTokens = providerInputTokens,
            providerOutputTokens = providerOutputTokens,
            providerTotalTokens = providerTotalTokens,
            createdAt = createdAt.toString(),
            updatedAt = updatedAt.toString(),
        )

    private fun BackupConnection.toDomain(credentialIdOverride: String): ConnectionProfile =
        ConnectionProfile(
            id = id,
            name = name,
            protocolAdapter = ProtocolAdapter.fromStorage(protocolAdapter),
            baseUrl = baseUrl,
            credentialId = credentialIdOverride,
            createdAt = Instant.parse(createdAt),
            updatedAt = Instant.parse(updatedAt),
        )

    private fun BackupModel.toDomain(): ModelProfile =
        ModelProfile(
            id = id,
            connectionId = connectionId,
            displayName = displayName,
            model = model,
            modelSource = modelSource,
            supportsStreaming = supportsStreaming,
            supportsVision = supportsVision,
            defaultContextWindow = defaultContextWindow,
            supports1mContext = supports1mContext,
            enable1mContext = enable1mContext,
            temperature = temperature,
            maxTokens = maxTokens,
            reasoningEffort = ModelReasoningEffort.fromStorage(reasoningEffort),
            isDefault = isDefault,
            createdAt = Instant.parse(createdAt),
            updatedAt = Instant.parse(updatedAt),
        )

    private fun BackupConversation.toDomain(): Conversation =
        Conversation(
            id = id,
            title = title,
            modelProfileId = modelProfileId,
            createdAt = Instant.parse(createdAt),
            updatedAt = Instant.parse(updatedAt),
            lastMessagePreview = lastMessagePreview,
            deleteState = DeleteState.valueOf(deleteState),
            deletedAt = deletedAt?.let(Instant::parse),
            purgeAfter = purgeAfter?.let(Instant::parse),
        )

    private fun BackupConversation.isExpiredTrash(): Boolean =
        deleteState == DeleteState.TRASH.name &&
            purgeAfter?.let { Instant.parse(it) <= Instant.now(clock) } == true

    private fun BackupSummary.toDomain(): ConversationSummary =
        ConversationSummary(
            id = id,
            conversationId = conversationId,
            summaryContent = summaryContent,
            coveredMessageStartId = coveredMessageStartId,
            coveredMessageEndId = coveredMessageEndId,
            tokenEstimate = tokenEstimate,
            createdAt = Instant.parse(createdAt),
            updatedAt = Instant.parse(updatedAt),
        )

    private fun BackupMessage.toDomain(): Message =
        Message(
            id = id,
            conversationId = conversationId,
            role = MessageRole.fromStorage(role),
            content = content,
            status = MessageStatus.fromStorage(status),
            tokenEstimate = tokenEstimate,
            providerInputTokens = providerInputTokens,
            providerOutputTokens = providerOutputTokens,
            providerTotalTokens = providerTotalTokens,
            createdAt = Instant.parse(createdAt),
            updatedAt = Instant.parse(updatedAt),
        )

    private fun BackupAttachment.toDomain(localUri: String): Attachment =
        Attachment(
            id = id,
            messageId = messageId,
            type = AttachmentType.valueOf(type),
            localEncryptedUri = localUri,
            mimeType = mimeType,
            width = width,
            height = height,
            sizeBytes = sizeBytes,
            sha256 = sha256,
            createdAt = Instant.parse(createdAt),
        )

    private fun ByteArray.base64(): String = Base64.getEncoder().encodeToString(this)
    private fun String.fromBase64(fieldName: String): ByteArray =
        runCatching { Base64.getDecoder().decode(this) }
            .getOrElse { throw IllegalArgumentException("${fieldName}格式无效。") }
}

data class BackupImportResult(
    val importedConnections: Int,
    val importedModels: Int,
    val importedConversations: Int,
    val importedMessages: Int,
    val importedAttachments: Int,
    val remappedCredentials: Int,
    val skippedExpiredTrash: Int = 0,
)

data class BackupPreview(
    val includeApiKeys: Boolean,
    val includeTrash: Boolean,
    val connections: Int,
    val models: Int,
    val conversations: Int,
    val messages: Int,
    val attachments: Int,
)

interface BackupTransactionRunner {
    suspend fun <T> runInTransaction(block: suspend () -> T): T
}
