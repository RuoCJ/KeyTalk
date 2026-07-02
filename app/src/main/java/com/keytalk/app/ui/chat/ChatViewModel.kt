package com.keytalk.app.ui.chat

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.keytalk.app.config.AppConfig
import com.keytalk.app.domain.model.ConnectionProfile
import com.keytalk.app.domain.model.Conversation
import com.keytalk.app.domain.model.PreparedImageAttachment
import com.keytalk.app.domain.repository.ConfigurationRepository
import com.keytalk.app.domain.repository.ConversationRepository
import com.keytalk.app.domain.model.Message
import com.keytalk.app.domain.model.MessageRole
import com.keytalk.app.domain.model.MessageStatus
import com.keytalk.app.domain.model.ModelProfile
import com.keytalk.app.domain.repository.MessageRepository
import com.keytalk.app.domain.service.ChatService
import com.keytalk.app.domain.service.ContextPressure
import com.keytalk.app.domain.service.ContextUsage
import com.keytalk.app.domain.service.ContextWindowManager
import com.keytalk.app.image.AndroidImageInputProcessor
import com.keytalk.app.ui.toUserFacingMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Instant

data class ChatUiState(
    val messages: List<Message> = emptyList(),
    val isSending: Boolean = false,
    val pendingImages: List<PreparedImageAttachment> = emptyList(),
    val supportsVision: Boolean = false,
    val contextUsage: ContextUsage = ContextUsage(0, AppConfig.Context.defaultContextWindow, 0.0, ContextPressure.NORMAL),
    val currentConversation: Conversation? = null,
    val currentModel: ModelProfile? = null,
    val currentConnection: ConnectionProfile? = null,
    val availableModels: List<ModelProfile> = emptyList(),
    val connections: List<ConnectionProfile> = emptyList(),
    val feedback: String? = null,
)

private data class ChatModelState(
    val conversation: Conversation?,
    val currentModel: ModelProfile?,
    val currentConnection: ConnectionProfile?,
    val availableModels: List<ModelProfile>,
    val connections: List<ConnectionProfile>,
)

class ChatViewModel(
    private val conversationId: String,
    private val messageRepository: MessageRepository,
    private val conversationRepository: ConversationRepository,
    private val configurationRepository: ConfigurationRepository,
    private val chatService: ChatService,
    private val imageInputProcessor: AndroidImageInputProcessor,
    private val appScope: CoroutineScope,
) : ViewModel() {
    private val contextWindowManager = ContextWindowManager()
    private val pendingImages = MutableStateFlow<List<PreparedImageAttachment>>(emptyList())
    private val feedback = MutableStateFlow<String?>(null)

    private val modelState = combine(
        conversationRepository.observeConversation(conversationId),
        configurationRepository.observeModels(),
        configurationRepository.observeConnections(),
    ) { conversation, models, connections ->
        val model = conversation?.let { current ->
            models.firstOrNull { it.id == current.modelProfileId }
        }
        ChatModelState(
            conversation = conversation,
            currentModel = model,
            currentConnection = model?.let { currentModel ->
                connections.firstOrNull { it.id == currentModel.connectionId }
            },
            availableModels = models,
            connections = connections,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(AppConfig.Settings.uiStateStopTimeoutMillis),
        initialValue = ChatModelState(null, null, null, emptyList(), emptyList()),
    )

    private val messagesWithContextUsage = combine(
        messageRepository.observeMessages(conversationId),
        modelState,
    ) { messages, currentModelState ->
        val limit = currentModelState.currentModel?.let(contextWindowManager::limitTokens) ?: AppConfig.Context.defaultContextWindow
        val used = messages.sumOf { it.providerTotalTokens ?: it.tokenEstimate }
        val ratio = used.toDouble() / limit.toDouble()
        val usage = ContextUsage(
            usedTokens = used,
            limitTokens = limit,
            ratio = ratio,
            pressure = when {
                ratio >= AppConfig.Context.criticalRatio95 -> ContextPressure.CRITICAL_95
                ratio >= AppConfig.Context.warningRatio85 -> ContextPressure.WARNING_85
                ratio >= AppConfig.Context.warningRatio70 -> ContextPressure.WARNING_70
                else -> ContextPressure.NORMAL
            },
        )
        messages to usage
    }

    private val isSending = messageRepository.observeMessages(conversationId)
        .map { messages ->
            messages.any { message ->
                message.role == MessageRole.ASSISTANT &&
                    (message.status == MessageStatus.SENDING || message.status == MessageStatus.STREAMING)
            }
        }

    val uiState = combine(
        messagesWithContextUsage,
        isSending,
        pendingImages,
        feedback,
        modelState,
    ) { messagesAndUsage, sending, images, currentFeedback, currentModelState ->
        ChatUiState(
            messages = messagesAndUsage.first,
            isSending = sending,
            pendingImages = images,
            supportsVision = currentModelState.currentModel?.supportsVision ?: false,
            contextUsage = messagesAndUsage.second,
            currentConversation = currentModelState.conversation,
            currentModel = currentModelState.currentModel,
            currentConnection = currentModelState.currentConnection,
            availableModels = currentModelState.availableModels,
            connections = currentModelState.connections,
            feedback = currentFeedback,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(AppConfig.Settings.uiStateStopTimeoutMillis),
        initialValue = ChatUiState(),
    )

    fun sendMessage(content: String) {
        val images = pendingImages.value
        if ((content.isBlank() && images.isEmpty()) || uiState.value.isSending) return
        pendingImages.value = emptyList()
        feedback.value = null
        appScope.launch {
            runCatching {
                chatService.sendMessage(conversationId = conversationId, content = content, images = images)
            }.onFailure { throwable ->
                feedback.value = throwable.toUserFacingMessage("发送失败，请检查网络或配置。")
            }
        }
    }

    fun retryMessage(messageId: String) {
        if (uiState.value.isSending) return
        appScope.launch {
            val message = messageRepository.getMessage(messageId)
            if (message == null) {
                feedback.value = "原问题不存在，无法重新询问。"
                return@launch
            }
            feedback.value = null
            val images = runCatching {
                message.attachments.map { attachment ->
                    imageInputProcessor.prepareExistingAttachment(attachment)
                }
            }.onFailure { throwable ->
                feedback.value = throwable.toUserFacingMessage("读取原图片失败，请重新选择图片后再发送。")
            }.getOrNull() ?: emptyList()
            if (feedback.value != null) {
                return@launch
            }
            runCatching {
                chatService.sendMessage(
                    conversationId = conversationId,
                    content = message.content,
                    images = images,
                )
            }.onFailure { throwable ->
                feedback.value = throwable.toUserFacingMessage("重新询问失败，请检查网络或配置。")
            }
        }
    }

    fun switchModel(modelProfileId: String) {
        if (uiState.value.isSending) {
            feedback.value = "正在生成时不能切换模型，请先停止或等待完成。"
            return
        }
        viewModelScope.launch {
            runCatching {
                val model = configurationRepository.getModel(modelProfileId)
                    ?: error("模型不存在，请先在设置页保存模型。")
                conversationRepository.switchConversationModel(
                    conversationId = conversationId,
                    modelProfileId = model.id,
                    now = Instant.now(),
                )
                pendingImages.value = if (model.supportsVision) pendingImages.value else emptyList()
                feedback.value = "已切换到模型：${model.displayName}。下一次发送会使用新模型。"
            }.onFailure { throwable ->
                feedback.value = throwable.toUserFacingMessage("切换模型失败")
            }
        }
    }

    fun createCameraOutputUri(): Uri = imageInputProcessor.createCameraOutputUri()

    fun addImage(uri: Uri) {
        if (pendingImages.value.size >= AppConfig.Image.maxPendingImages) {
            feedback.value = "一次最多添加 ${AppConfig.Image.maxPendingImages} 张图片。"
            return
        }
        viewModelScope.launch {
            feedback.value = "正在处理图片..."
            runCatching {
                imageInputProcessor.process(uri)
            }.onSuccess { image ->
                pendingImages.value = pendingImages.value + image
                feedback.value = "图片已压缩并写入加密缓存。"
            }.onFailure { throwable ->
                feedback.value = throwable.toUserFacingMessage("图片处理失败")
            }
        }
    }

    fun removePendingImage(imageId: String) {
        pendingImages.value = pendingImages.value.filterNot { it.id == imageId }
    }

    fun showFeedback(message: String) {
        feedback.value = message
    }

    fun stopGeneration() {
        chatService.cancelConversation(conversationId)
        feedback.value = "已请求停止生成，已生成内容会保留。"
    }
}
