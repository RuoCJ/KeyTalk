package com.keytalk.app.ui.trash

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
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.keytalk.app.domain.model.Conversation

@Composable
fun TrashScreen(
    viewModel: TrashViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsState()
    var confirmClearTrash by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onBack) { Text("返回") }
            Button(onClick = { viewModel.purgeExpiredTrash() }) { Text("清理过期") }
            Button(
                onClick = { confirmClearTrash = true },
                enabled = state.conversations.isNotEmpty(),
            ) {
                Text("清空回收站")
            }
        }
        state.feedback?.let { Text(it) }

        if (state.conversations.isEmpty()) {
            Text("回收站为空。")
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(state.conversations, key = { it.id }) { conversation ->
                    TrashConversationRow(
                        conversation = conversation,
                        onRestore = { viewModel.restore(conversation.id) },
                        onHardDelete = { viewModel.hardDelete(conversation.id) },
                    )
                }
            }
        }
    }

    if (confirmClearTrash) {
        AlertDialog(
            onDismissRequest = { confirmClearTrash = false },
            title = { Text("清空回收站？") },
            text = { Text("彻底删除后无法恢复。是否继续？") },
            confirmButton = {
                Button(onClick = {
                    viewModel.clearTrash()
                    confirmClearTrash = false
                }) { Text("彻底删除") }
            },
            dismissButton = {
                TextButton(onClick = { confirmClearTrash = false }) { Text("取消") }
            },
        )
    }
}

@Composable
private fun TrashConversationRow(
    conversation: Conversation,
    onRestore: () -> Unit,
    onHardDelete: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(conversation.title)
            Text("将于 ${conversation.purgeAfter ?: "未知时间"} 后自动彻底删除")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onRestore) { Text("恢复") }
                Button(onClick = onHardDelete) { Text("彻底删除") }
            }
        }
    }
}
