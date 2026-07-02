package com.keytalk.app

import android.os.Bundle
import androidx.activity.compose.BackHandler
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import com.keytalk.app.ui.chat.ChatScreen
import com.keytalk.app.ui.conversation.ConversationListScreen
import com.keytalk.app.ui.settings.SettingsScreen
import com.keytalk.app.ui.trash.TrashScreen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val container = (application as KeyTalkApp).container
        setContent {
            MaterialTheme {
                Surface {
                    KeyTalkRoot(container = container)
                }
            }
        }
    }
}

private enum class AppScreen {
    CONVERSATIONS,
    CHAT,
    SETTINGS,
    TRASH,
    ;

    companion object {
        fun fromName(name: String): AppScreen =
            entries.firstOrNull { it.name == name } ?: CONVERSATIONS
    }
}

@Composable
private fun KeyTalkRoot(container: AppContainer) {
    var currentScreenName by rememberSaveable { mutableStateOf(AppScreen.CONVERSATIONS.name) }
    val currentScreen = AppScreen.fromName(currentScreenName)
    var currentConversationId by rememberSaveable { mutableStateOf<String?>(null) }

    fun navigateHome() {
        currentConversationId = null
        currentScreenName = AppScreen.CONVERSATIONS.name
    }

    fun navigateTo(screen: AppScreen) {
        currentScreenName = screen.name
    }

    BackHandler(enabled = currentScreen != AppScreen.CONVERSATIONS) {
        navigateHome()
    }

    when (currentScreen) {
        AppScreen.CONVERSATIONS -> ConversationListScreen(
            modifier = Modifier,
            viewModel = viewModel(factory = container.conversationListViewModelFactory()),
            onOpenConversation = { conversationId ->
                currentConversationId = conversationId
                navigateTo(AppScreen.CHAT)
            },
            onOpenSettings = { navigateTo(AppScreen.SETTINGS) },
            onOpenTrash = { navigateTo(AppScreen.TRASH) },
        )

        AppScreen.CHAT -> {
            val conversationId = currentConversationId
            if (conversationId == null) {
                navigateHome()
            } else {
                ChatScreen(
                    modifier = Modifier,
                    viewModel = viewModel(
                        key = conversationId,
                        factory = container.chatViewModelFactory(conversationId),
                    ),
                    onBack = { navigateHome() },
                )
            }
        }

        AppScreen.SETTINGS -> SettingsScreen(
            modifier = Modifier,
            viewModel = viewModel(factory = container.settingsViewModelFactory()),
            onBack = { navigateHome() },
        )

        AppScreen.TRASH -> TrashScreen(
            modifier = Modifier,
            viewModel = viewModel(factory = container.trashViewModelFactory()),
            onBack = { navigateHome() },
        )
    }
}
