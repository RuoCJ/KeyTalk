package com.keytalk.app.backup

import kotlinx.serialization.Serializable

@Serializable
data class BackupEnvelope(
    val backupVersion: Int,
    val createdAt: String,
    val sourcePlatform: String,
    val appVersion: String,
    val kdf: BackupKdf,
    val cipher: BackupCipher,
    val checksum: String,
    val encryptedPayload: String,
)

@Serializable
data class BackupKdf(
    val name: String,
    val iterations: Int,
    val salt: String,
    val keyLengthBits: Int,
)

@Serializable
data class BackupCipher(
    val name: String,
    val nonce: String,
)

@Serializable
data class BackupPayload(
    val schemaVersion: Int,
    val exportOptions: BackupExportOptions,
    val connections: List<BackupConnection>,
    val models: List<BackupModel>,
    val conversations: List<BackupConversation>,
    val messages: List<BackupMessage>,
    val attachments: List<BackupAttachment>,
    val summaries: List<BackupSummary> = emptyList(),
    val settings: Map<String, String> = emptyMap(),
)

@Serializable
data class BackupExportOptions(
    val includeApiKeys: Boolean,
    val includeTrash: Boolean,
)

@Serializable
data class BackupConnection(
    val id: String,
    val name: String,
    val protocolAdapter: String,
    val baseUrl: String,
    val credentialId: String,
    val apiKey: String? = null,
    val createdAt: String,
    val updatedAt: String,
)

@Serializable
data class BackupModel(
    val id: String,
    val connectionId: String,
    val displayName: String,
    val model: String,
    val modelSource: String,
    val supportsStreaming: Boolean,
    val supportsVision: Boolean,
    val defaultContextWindow: Int,
    val supports1mContext: Boolean,
    val enable1mContext: Boolean,
    val temperature: Double?,
    val maxTokens: Int?,
    val reasoningEffort: String? = null,
    val isDefault: Boolean,
    val createdAt: String,
    val updatedAt: String,
)

@Serializable
data class BackupConversation(
    val id: String,
    val title: String,
    val modelProfileId: String,
    val lastMessagePreview: String,
    val deleteState: String,
    val deletedAt: String?,
    val purgeAfter: String?,
    val createdAt: String,
    val updatedAt: String,
)

@Serializable
data class BackupMessage(
    val id: String,
    val conversationId: String,
    val role: String,
    val content: String,
    val status: String,
    val tokenEstimate: Int,
    val providerInputTokens: Int?,
    val providerOutputTokens: Int?,
    val providerTotalTokens: Int?,
    val createdAt: String,
    val updatedAt: String,
)

@Serializable
data class BackupAttachment(
    val id: String,
    val messageId: String,
    val type: String,
    val localEncryptedUri: String,
    val mimeType: String,
    val width: Int,
    val height: Int,
    val sizeBytes: Int,
    val sha256: String,
    val encryptedBlob: String? = null,
    val createdAt: String,
)

@Serializable
data class BackupSummary(
    val id: String,
    val conversationId: String,
    val summaryContent: String,
    val coveredMessageStartId: String? = null,
    val coveredMessageEndId: String? = null,
    val tokenEstimate: Int,
    val createdAt: String,
    val updatedAt: String,
)

enum class BackupImportMode {
    MERGE,
    OVERWRITE,
    CONFIG_ONLY,
    CONVERSATIONS_ONLY,
}
