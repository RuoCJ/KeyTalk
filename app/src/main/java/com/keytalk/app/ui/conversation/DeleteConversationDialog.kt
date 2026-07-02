package com.keytalk.app.ui.conversation

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.keytalk.app.config.AppConfig

@Composable
fun DeleteConversationDialog(
    conversationTitle: String,
    onDismiss: () -> Unit,
    onConfirm: (hardDelete: Boolean) -> Unit,
) {
    var hardDelete by rememberSaveable { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("删除会话？") },
        text = {
            Column {
                Text("“$conversationTitle” 默认会移入回收站，${AppConfig.Conversation.trashRetentionDays} 天内可以恢复。")
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(top = 12.dp),
                ) {
                    Checkbox(checked = hardDelete, onCheckedChange = { hardDelete = it })
                    Text("立即彻底删除，不进入回收站")
                }
                if (hardDelete) {
                    Text("勾选后将立即删除该会话及其消息，无法恢复。")
                }
            }
        },
        confirmButton = {
            Button(onClick = { onConfirm(hardDelete) }) {
                Text(if (hardDelete) "彻底删除" else "删除")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        },
    )
}
