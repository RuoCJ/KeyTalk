package com.keytalk.app.ui.conversation

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.keytalk.app.config.AppConfig
import com.keytalk.app.domain.model.Conversation
import com.keytalk.app.domain.repository.ConfigurationRepository
import com.keytalk.app.domain.repository.ConversationRepository
import com.keytalk.app.image.ImageCacheMaintenanceService
import com.keytalk.app.ui.toUserFacingMessage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

data class ConversationListUiState(
    val conversations: List<Conversation> = emptyList(),
    val feedback: String? = null,
)

private enum class ConversationDateFilter {
    RECENT,
    ALL,
    DATE,
}

class ConversationListViewModel(
    private val conversationRepository: ConversationRepository,
    private val configurationRepository: ConfigurationRepository,
    private val imageCacheMaintenance: ImageCacheMaintenanceService? = null,
) : ViewModel() {
    private val feedback = MutableStateFlow<String?>(null)

    val uiState = combine(
        conversationRepository.observeActiveConversations(),
        feedback,
    ) { conversations, currentFeedback ->
        ConversationListUiState(conversations = conversations, feedback = currentFeedback)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(AppConfig.Settings.uiStateStopTimeoutMillis),
        initialValue = ConversationListUiState(),
    )

    fun createConversation(onCreated: (String) -> Unit) {
        viewModelScope.launch {
            runCatching {
                val model = configurationRepository.getDefaultModel()
                    ?: error("请先在设置页新增连接和模型。")
                val conversation = conversationRepository.createConversation(
                    title = "新会话",
                    modelProfileId = model.id,
                    now = Instant.now(),
                )
                onCreated(conversation.id)
            }.onFailure { throwable ->
                feedback.value = throwable.toUserFacingMessage("创建会话失败")
            }
        }
    }

    fun deleteConversation(conversationId: String, immediateHardDelete: Boolean) {
        viewModelScope.launch {
            try {
                if (immediateHardDelete) {
                    conversationRepository.hardDelete(conversationId)
                    feedback.value = "会话已彻底删除，无法恢复。"
                    try {
                        imageCacheMaintenance?.cleanupOrphanedImages()
                    } catch (_: Throwable) {
                    }
                } else {
                    conversationRepository.moveToTrash(conversationId, Instant.now())
                    feedback.value = "会话已移入回收站，${AppConfig.Conversation.trashRetentionDays} 天内可恢复。"
                }
            } catch (throwable: Throwable) {
                feedback.value = throwable.toUserFacingMessage("删除失败")
            }
        }
    }

    fun renameConversation(conversationId: String, title: String) {
        viewModelScope.launch {
            runCatching {
                val normalized = title.trim()
                if (normalized.isBlank()) error("会话名称不能为空")
                conversationRepository.renameConversation(
                    conversationId = conversationId,
                    title = normalized,
                    now = Instant.now(),
                )
                feedback.value = "会话名称已更新。"
            }.onFailure { throwable ->
                feedback.value = throwable.toUserFacingMessage("重命名失败")
            }
        }
    }
}

@Composable
fun ConversationListScreen(
    viewModel: ConversationListViewModel,
    onOpenConversation: (String) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenTrash: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsState()
    var pendingDelete by remember { mutableStateOf<Conversation?>(null) }
    var pendingRename by remember { mutableStateOf<Conversation?>(null) }
    var dateFilter by rememberSaveable { mutableStateOf(ConversationDateFilter.RECENT) }
    var selectedDateText by rememberSaveable { mutableStateOf("") }
    var showDatePicker by rememberSaveable { mutableStateOf(false) }
    val zoneId = remember { ZoneId.systemDefault() }
    val sortedConversations = remember(state.conversations) {
        state.conversations.sortedByDescending { it.updatedAt }
    }
    val availableConversationDates = remember(sortedConversations, zoneId) {
        sortedConversations.map { it.updatedAt.atZone(zoneId).toLocalDate() }.toSet()
    }
    val parsedSelectedDate = remember(selectedDateText) {
        selectedDateText.trim().takeIf { it.isNotBlank() }?.let { text ->
            runCatching { LocalDate.parse(text) }.getOrNull()
        }
    }
    val dateFilteredConversations = remember(sortedConversations, dateFilter, parsedSelectedDate, zoneId) {
        when (dateFilter) {
            ConversationDateFilter.RECENT -> sortedConversations.take(AppConfig.Conversation.recentConversationDisplayLimit)
            ConversationDateFilter.ALL -> sortedConversations
            ConversationDateFilter.DATE -> parsedSelectedDate?.let { selectedDate ->
                sortedConversations.filter { conversation ->
                    conversation.updatedAt.atZone(zoneId).toLocalDate() == selectedDate
                }
            }.orEmpty()
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        HomeHeader()

        Button(
            onClick = { viewModel.createConversation(onOpenConversation) },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("新建会话")
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedButton(
                onClick = onOpenSettings,
                modifier = Modifier.weight(1f),
            ) {
                Text("设置")
            }
            OutlinedButton(
                onClick = onOpenTrash,
                modifier = Modifier.weight(1f),
            ) {
                Text("回收站")
            }
        }

        state.feedback?.let { feedback ->
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                ),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = feedback,
                    modifier = Modifier.padding(14.dp),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }

        if (state.conversations.isEmpty()) {
            EmptyConversationState(onOpenSettings = onOpenSettings)
        } else {
            ConversationListFilters(
                totalCount = sortedConversations.size,
                shownCount = dateFilteredConversations.size,
                dateFilter = dateFilter,
                selectedDateText = selectedDateText,
                selectedDateValid = selectedDateText.isBlank() || parsedSelectedDate != null,
                availableDates = availableConversationDates,
                showDatePicker = showDatePicker,
                onFilterChange = { dateFilter = it },
                onRequestDatePicker = {
                    dateFilter = ConversationDateFilter.DATE
                    showDatePicker = true
                },
                onDateSelected = {
                    selectedDateText = it
                    showDatePicker = false
                },
                onDatePickerDismiss = { showDatePicker = false },
            )
            if (dateFilteredConversations.isEmpty()) {
                Text(
                    text = if (dateFilter == ConversationDateFilter.DATE) {
                        "这一天没有会话。请输入 yyyy-MM-dd，例如 ${LocalDate.now().format(DateInputFormatter)}。"
                    } else {
                        "没有符合条件的会话。"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(dateFilteredConversations, key = { it.id }) { conversation ->
                        ConversationRow(
                            conversation = conversation,
                            onOpen = { onOpenConversation(conversation.id) },
                            onRename = { pendingRename = conversation },
                            onDelete = { pendingDelete = conversation },
                        )
                    }
                }
            }
        }
    }

    val conversationToDelete = pendingDelete
    if (conversationToDelete != null) {
        DeleteConversationDialog(
            conversationTitle = conversationToDelete.title,
            onDismiss = { pendingDelete = null },
            onConfirm = { hardDelete ->
                viewModel.deleteConversation(conversationToDelete.id, hardDelete)
                pendingDelete = null
            },
        )
    }

    val conversationToRename = pendingRename
    if (conversationToRename != null) {
        RenameConversationDialog(
            currentTitle = conversationToRename.title,
            onDismiss = { pendingRename = null },
            onConfirm = { newTitle ->
                viewModel.renameConversation(conversationToRename.id, newTitle)
                pendingRename = null
            },
        )
    }
}

@Composable
private fun HomeHeader() {
    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        ),
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = "KeyTalk",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = "本机直连你自己的 AI 服务，聊天记录与密钥保存在本机。",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

@Composable
private fun EmptyConversationState(onOpenSettings: () -> Unit) {
    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        ),
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = "还没有会话",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = "先添加一个连接和模型，然后就可以开始聊天。支持 OpenAI-Compatible、Claude、Gemini 和 Grok。",
                style = MaterialTheme.typography.bodyMedium,
            )
            OutlinedButton(onClick = onOpenSettings) {
                Text("去设置连接")
            }
        }
    }
}

@Composable
private fun ConversationListFilters(
    totalCount: Int,
    shownCount: Int,
    dateFilter: ConversationDateFilter,
    selectedDateText: String,
    selectedDateValid: Boolean,
    availableDates: Set<LocalDate>,
    showDatePicker: Boolean,
    onFilterChange: (ConversationDateFilter) -> Unit,
    onRequestDatePicker: () -> Unit,
    onDateSelected: (String) -> Unit,
    onDatePickerDismiss: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = when (dateFilter) {
                ConversationDateFilter.RECENT ->
                    "最近会话 · 显示 $shownCount / $totalCount（按最后对话时间倒序）"
                ConversationDateFilter.ALL ->
                    "全部会话 · $shownCount 个（按最后对话时间倒序）"
                ConversationDateFilter.DATE ->
                    "按日期筛选 · $shownCount 个"
            },
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FilterChip(
                selected = dateFilter == ConversationDateFilter.RECENT,
                onClick = { onFilterChange(ConversationDateFilter.RECENT) },
                modifier = Modifier.weight(1f),
                label = { Text("最近") },
            )
            FilterChip(
                selected = dateFilter == ConversationDateFilter.ALL,
                onClick = { onFilterChange(ConversationDateFilter.ALL) },
                modifier = Modifier.weight(1f),
                label = { Text("全部") },
            )
            FilterChip(
                selected = dateFilter == ConversationDateFilter.DATE,
                onClick = onRequestDatePicker,
                modifier = Modifier.weight(1f),
                label = { Text("选择日期") },
            )
        }
        if (dateFilter == ConversationDateFilter.DATE && selectedDateText.isNotBlank()) {
            Text(
                text = "已选择：$selectedDateText",
                style = MaterialTheme.typography.bodyMedium,
                color = if (selectedDateValid) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
            )
        }
        if (showDatePicker) {
            ConversationDatePickerDialog(
                selectedDateText = selectedDateText,
                availableDates = availableDates,
                onDateSelected = onDateSelected,
                onDismiss = onDatePickerDismiss,
            )
        }
    }
}

@Composable
private fun ConversationRow(
    conversation: Conversation,
    onOpen: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    ElevatedCard(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = conversation.title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = conversation.lastMessagePreview.ifBlank { "暂无消息" },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = "最后对话：${conversation.updatedAt.atZone(ZoneId.systemDefault()).format(ConversationTimeFormatter)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onOpen) { Text("打开") }
                OutlinedButton(onClick = onRename) { Text("重命名") }
                OutlinedButton(onClick = onDelete) { Text("删除") }
            }
        }
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun ConversationDatePickerDialog(
    selectedDateText: String,
    availableDates: Set<LocalDate>,
    onDateSelected: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val zoneId = remember { ZoneId.systemDefault() }
    val selectedMillis = remember(selectedDateText, zoneId) {
        selectedDateText.trim().takeIf { it.isNotBlank() }?.let { text ->
            runCatching {
                LocalDate.parse(text).atStartOfDay(zoneId).toInstant().toEpochMilli()
            }.getOrNull()
        }
    }
    val defaultDisplayedMonthMillis = remember(availableDates, selectedMillis, zoneId) {
        when {
            selectedMillis != null -> selectedMillis
            availableDates.isNotEmpty() -> availableDates.maxOrNull()!!
                .atStartOfDay(zoneId).toInstant().toEpochMilli()
            else -> LocalDate.now(zoneId).atStartOfDay(zoneId).toInstant().toEpochMilli()
        }
    }
    val datePickerState = androidx.compose.material3.rememberDatePickerState(
        initialSelectedDateMillis = selectedMillis,
        initialDisplayedMonthMillis = defaultDisplayedMonthMillis,
        selectableDates = object : SelectableDates {
            override fun isSelectableDate(utcTimeMillis: Long): Boolean {
                val localDate = Instant.ofEpochMilli(utcTimeMillis).atZone(zoneId).toLocalDate()
                return localDate in availableDates
            }

            override fun isSelectableYear(year: Int): Boolean = true
        },
    )

    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                enabled = datePickerState.selectedDateMillis != null,
                onClick = {
                    val millis = datePickerState.selectedDateMillis ?: return@TextButton
                    val date = Instant.ofEpochMilli(millis).atZone(zoneId).toLocalDate()
                    onDateSelected(date.format(DateInputFormatter))
                },
            ) {
                Text("确定")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        DatePicker(
            state = datePickerState,
            showModeToggle = false,
        )
    }
}

@Composable
private fun RenameConversationDialog(
    currentTitle: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var title by rememberSaveable(currentTitle) { mutableStateOf(currentTitle) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("重命名会话") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("会话名称") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    text = "建议使用能区分主题的名称，例如“工作日报助手”。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = title.isNotBlank(),
                onClick = { onConfirm(title) },
            ) {
                Text("保存")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        },
    )
}

private val DateInputFormatter: DateTimeFormatter = DateTimeFormatter.ISO_LOCAL_DATE
private val ConversationTimeFormatter: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
