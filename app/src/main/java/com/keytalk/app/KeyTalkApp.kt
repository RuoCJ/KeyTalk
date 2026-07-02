package com.keytalk.app

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.room.withTransaction
import com.keytalk.app.backup.BackupTransactionRunner
import com.keytalk.app.backup.AndroidBackupAttachmentStore
import com.keytalk.app.backup.KeyTalkBackupService
import com.keytalk.app.data.db.KeyTalkDatabase
import com.keytalk.app.data.repository.RoomConfigurationRepository
import com.keytalk.app.data.repository.RoomConversationRepository
import com.keytalk.app.data.repository.RoomConversationSummaryRepository
import com.keytalk.app.data.repository.RoomMessageRepository
import com.keytalk.app.data.repository.SharedPreferencesFailoverPolicyRepository
import com.keytalk.app.domain.service.ChatService
import com.keytalk.app.image.AndroidEncryptedImageCache
import com.keytalk.app.image.AndroidImageInputProcessor
import com.keytalk.app.image.ImageCacheMaintenanceService
import com.keytalk.app.network.OkHttpChatNetworkClient
import com.keytalk.app.security.AndroidCredentialStore
import com.keytalk.app.security.AndroidDatabasePassphraseProvider
import com.keytalk.app.ui.chat.ChatViewModel
import com.keytalk.app.ui.conversation.ConversationListViewModel
import com.keytalk.app.ui.settings.SettingsViewModel
import com.keytalk.app.ui.trash.TrashViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.Instant

class KeyTalkApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        container.purgeExpiredTrashAndCleanupCachesOnStartup()
    }
}

class AppContainer(application: Application) {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val appContext = application.applicationContext

    val credentialStore by lazy { AndroidCredentialStore(appContext) }
    private val databasePassphraseProvider by lazy { AndroidDatabasePassphraseProvider(appContext) }
    val database by lazy { KeyTalkDatabase.create(appContext, databasePassphraseProvider) }

    val configurationRepository by lazy {
        RoomConfigurationRepository(database.connectionDao(), database.modelDao())
    }
    val conversationRepository by lazy {
        RoomConversationRepository(database.conversationDao())
    }
    val summaryRepository by lazy {
        RoomConversationSummaryRepository(database.conversationSummaryDao())
    }
    val messageRepository by lazy {
        RoomMessageRepository(database.messageDao(), database.attachmentDao())
    }
    val failoverPolicyRepository by lazy { SharedPreferencesFailoverPolicyRepository(appContext) }
    private val encryptedImageCache by lazy { AndroidEncryptedImageCache(appContext) }
    private val imageCacheMaintenance by lazy {
        ImageCacheMaintenanceService(
            attachmentDao = database.attachmentDao(),
            imageCache = encryptedImageCache,
            context = appContext,
        )
    }
    val imageInputProcessor by lazy { AndroidImageInputProcessor(appContext, encryptedImageCache) }
    val backupService by lazy {
        KeyTalkBackupService(
            configurationRepository = configurationRepository,
            conversationRepository = conversationRepository,
            messageRepository = messageRepository,
            summaryRepository = summaryRepository,
            credentialStore = credentialStore,
            attachmentStore = AndroidBackupAttachmentStore(encryptedImageCache),
            transactionRunner = object : BackupTransactionRunner {
                override suspend fun <T> runInTransaction(block: suspend () -> T): T =
                    database.withTransaction { block() }
            },
        )
    }
    val networkClient by lazy { OkHttpChatNetworkClient() }
    val chatService by lazy {
        ChatService(
            configurationRepository = configurationRepository,
            conversationRepository = conversationRepository,
            messageRepository = messageRepository,
            credentialStore = credentialStore,
            networkClient = networkClient,
            summaryRepository = summaryRepository,
            failoverPolicyRepository = failoverPolicyRepository,
        )
    }

    fun purgeExpiredTrashOnStartup() {
        appScope.launch {
            conversationRepository.purgeExpiredTrash(Instant.now())
        }
    }

    fun purgeExpiredTrashAndCleanupCachesOnStartup() {
        appScope.launch {
            try {
                conversationRepository.purgeExpiredTrash(Instant.now())
            } catch (_: Throwable) {
            }
            try {
                imageCacheMaintenance.cleanupOrphanedImages()
            } catch (_: Throwable) {
            }
            try {
                imageCacheMaintenance.cleanupStaleCameraCaptures(
                    olderThanMillis = Instant.now().minus(Duration.ofHours(24)).toEpochMilli(),
                )
            } catch (_: Throwable) {
            }
        }
    }

    fun settingsViewModelFactory(): ViewModelProvider.Factory = viewModelFactory {
        SettingsViewModel(
            configurationRepository,
            credentialStore,
            networkClient,
            backupService,
            failoverPolicyRepository,
            imageCacheMaintenance,
        )
    }

    fun conversationListViewModelFactory(): ViewModelProvider.Factory = viewModelFactory {
        ConversationListViewModel(conversationRepository, configurationRepository, imageCacheMaintenance)
    }

    fun chatViewModelFactory(conversationId: String): ViewModelProvider.Factory = viewModelFactory {
        ChatViewModel(
            conversationId = conversationId,
            messageRepository = messageRepository,
            conversationRepository = conversationRepository,
            configurationRepository = configurationRepository,
            chatService = chatService,
            imageInputProcessor = imageInputProcessor,
            appScope = appScope,
        )
    }

    fun trashViewModelFactory(): ViewModelProvider.Factory = viewModelFactory {
        TrashViewModel(conversationRepository, imageCacheMaintenance)
    }

    private fun <VM : ViewModel> viewModelFactory(create: () -> VM): ViewModelProvider.Factory =
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = create() as T
        }
}
