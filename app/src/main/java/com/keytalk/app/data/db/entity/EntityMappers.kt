package com.keytalk.app.data.db.entity

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
import java.time.Instant

fun ConnectionProfileEntity.toDomain(): ConnectionProfile =
    ConnectionProfile(
        id = id,
        name = name,
        protocolAdapter = ProtocolAdapter.fromStorage(protocolAdapter),
        baseUrl = baseUrl,
        credentialId = credentialId,
        createdAt = Instant.ofEpochMilli(createdAtMillis),
        updatedAt = Instant.ofEpochMilli(updatedAtMillis),
    )

fun ConnectionProfile.toEntity(): ConnectionProfileEntity =
    ConnectionProfileEntity(
        id = id,
        name = name,
        protocolAdapter = protocolAdapter.name,
        baseUrl = baseUrl,
        credentialId = credentialId,
        createdAtMillis = createdAt.toEpochMilli(),
        updatedAtMillis = updatedAt.toEpochMilli(),
    )

fun ModelProfileEntity.toDomain(): ModelProfile =
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
        createdAt = Instant.ofEpochMilli(createdAtMillis),
        updatedAt = Instant.ofEpochMilli(updatedAtMillis),
    )

fun ModelProfile.toEntity(): ModelProfileEntity =
    ModelProfileEntity(
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
        createdAtMillis = createdAt.toEpochMilli(),
        updatedAtMillis = updatedAt.toEpochMilli(),
    )

fun ConversationEntity.toDomain(): Conversation =
    Conversation(
        id = id,
        title = title,
        modelProfileId = modelProfileId,
        createdAt = Instant.ofEpochMilli(createdAtMillis),
        updatedAt = Instant.ofEpochMilli(updatedAtMillis),
        lastMessagePreview = lastMessagePreview,
        deleteState = DeleteState.valueOf(deleteState),
        deletedAt = deletedAtMillis?.let(Instant::ofEpochMilli),
        purgeAfter = purgeAfterMillis?.let(Instant::ofEpochMilli),
    )

fun Conversation.toEntity(): ConversationEntity =
    ConversationEntity(
        id = id,
        title = title,
        modelProfileId = modelProfileId,
        createdAtMillis = createdAt.toEpochMilli(),
        updatedAtMillis = updatedAt.toEpochMilli(),
        lastMessagePreview = lastMessagePreview,
        deleteState = deleteState.name,
        deletedAtMillis = deletedAt?.toEpochMilli(),
        purgeAfterMillis = purgeAfter?.toEpochMilli(),
    )

fun ConversationSummaryEntity.toDomain(): ConversationSummary =
    ConversationSummary(
        id = id,
        conversationId = conversationId,
        summaryContent = summaryContent,
        coveredMessageStartId = coveredMessageStartId,
        coveredMessageEndId = coveredMessageEndId,
        tokenEstimate = tokenEstimate,
        createdAt = Instant.ofEpochMilli(createdAtMillis),
        updatedAt = Instant.ofEpochMilli(updatedAtMillis),
    )

fun ConversationSummary.toEntity(): ConversationSummaryEntity =
    ConversationSummaryEntity(
        id = id,
        conversationId = conversationId,
        summaryContent = summaryContent,
        coveredMessageStartId = coveredMessageStartId,
        coveredMessageEndId = coveredMessageEndId,
        tokenEstimate = tokenEstimate,
        createdAtMillis = createdAt.toEpochMilli(),
        updatedAtMillis = updatedAt.toEpochMilli(),
    )

fun MessageEntity.toDomain(): Message =
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
        attachments = emptyList(),
        createdAt = Instant.ofEpochMilli(createdAtMillis),
        updatedAt = Instant.ofEpochMilli(updatedAtMillis),
    )

fun Message.toEntity(): MessageEntity =
    MessageEntity(
        id = id,
        conversationId = conversationId,
        role = role.name,
        content = content,
        status = status.name,
        tokenEstimate = tokenEstimate,
        providerInputTokens = providerInputTokens,
        providerOutputTokens = providerOutputTokens,
        providerTotalTokens = providerTotalTokens,
        createdAtMillis = createdAt.toEpochMilli(),
        updatedAtMillis = updatedAt.toEpochMilli(),
    )

fun AttachmentEntity.toDomain(): Attachment =
    Attachment(
        id = id,
        messageId = messageId,
        type = AttachmentType.valueOf(type),
        localEncryptedUri = localEncryptedUri,
        mimeType = mimeType,
        width = width,
        height = height,
        sizeBytes = sizeBytes,
        sha256 = sha256,
        createdAt = Instant.ofEpochMilli(createdAtMillis),
    )

fun Attachment.toEntity(): AttachmentEntity =
    AttachmentEntity(
        id = id,
        messageId = messageId,
        type = type.name,
        localEncryptedUri = localEncryptedUri,
        mimeType = mimeType,
        width = width,
        height = height,
        sizeBytes = sizeBytes,
        sha256 = sha256,
        createdAtMillis = createdAt.toEpochMilli(),
    )
