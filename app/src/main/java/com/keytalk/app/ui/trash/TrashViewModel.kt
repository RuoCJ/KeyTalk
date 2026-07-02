package com.keytalk.app.ui.trash

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.keytalk.app.config.AppConfig
import com.keytalk.app.domain.model.Conversation
import com.keytalk.app.domain.repository.ConversationRepository
import com.keytalk.app.image.ImageCacheMaintenanceService
import com.keytalk.app.ui.toUserFacingMessage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Instant

data class TrashUiState(
    val conversations: List<Conversation> = emptyList(),
    val feedback: String? = null,
)

class TrashViewModel(
    private val conversationRepository: ConversationRepository,
    private val imageCacheMaintenance: ImageCacheMaintenanceService? = null,
) : ViewModel() {
    private val feedback = MutableStateFlow<String?>(null)

    val uiState = combine(
        conversationRepository.observeTrashConversations(),
        feedback,
    ) { conversations, currentFeedback ->
        TrashUiState(conversations = conversations, feedback = currentFeedback)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(AppConfig.Settings.uiStateStopTimeoutMillis),
        initialValue = TrashUiState(),
    )

    init {
        purgeExpiredTrash()
    }

    fun purgeExpiredTrash() {
        viewModelScope.launch {
            try {
                val purged = conversationRepository.purgeExpiredTrash(Instant.now())
                if (purged > 0) {
                    try {
                        imageCacheMaintenance?.cleanupOrphanedImages()
                    } catch (_: Throwable) {
                    }
                    feedback.value = "已自动清理 $purged 个过期会话。"
                }
            } catch (throwable: Throwable) {
                feedback.value = throwable.toUserFacingMessage("清理过期失败")
            }
        }
    }

    fun restore(conversationId: String) {
        viewModelScope.launch {
            runCatching {
                conversationRepository.restoreFromTrash(conversationId)
                feedback.value = "会话已恢复。"
            }.onFailure { throwable ->
                feedback.value = throwable.toUserFacingMessage("恢复失败")
            }
        }
    }

    fun hardDelete(conversationId: String) {
        viewModelScope.launch {
            try {
                conversationRepository.hardDelete(conversationId)
                feedback.value = "会话已彻底删除。"
                try {
                    imageCacheMaintenance?.cleanupOrphanedImages()
                } catch (_: Throwable) {
                }
            } catch (throwable: Throwable) {
                feedback.value = throwable.toUserFacingMessage("彻底删除失败")
            }
        }
    }

    fun clearTrash() {
        viewModelScope.launch {
            try {
                val count = conversationRepository.clearTrash()
                if (count > 0) {
                    try {
                        imageCacheMaintenance?.cleanupOrphanedImages()
                    } catch (_: Throwable) {
                    }
                }
                feedback.value = "已清空回收站，共删除 $count 个会话。"
            } catch (throwable: Throwable) {
                feedback.value = throwable.toUserFacingMessage("清空回收站失败")
            }
        }
    }
}
