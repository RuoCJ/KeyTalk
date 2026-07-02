package com.keytalk.app.data.db

import android.content.Context
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.keytalk.app.config.AppConfig
import com.keytalk.app.data.db.entity.ConnectionProfileEntity
import com.keytalk.app.data.db.entity.ConversationEntity
import com.keytalk.app.data.db.entity.MessageEntity
import com.keytalk.app.data.db.entity.ModelProfileEntity
import kotlinx.coroutines.runBlocking
import net.sqlcipher.database.SQLiteDatabase
import net.sqlcipher.database.SupportFactory
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.charset.StandardCharsets

@RunWith(AndroidJUnit4::class)
class KeyTalkDatabaseTest {
    private lateinit var context: Context
    private lateinit var database: KeyTalkDatabase

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase(AppConfig.Test.instrumentationDatabaseName)
        SQLiteDatabase.loadLibs(context)
        database = Room.databaseBuilder(context, KeyTalkDatabase::class.java, AppConfig.Test.instrumentationDatabaseName)
            .openHelperFactory(SupportFactory(SQLiteDatabase.getBytes("test-passphrase".toCharArray())))
            .setJournalMode(RoomDatabase.JournalMode.TRUNCATE)
            .build()
    }

    @After
    fun tearDown() {
        database.close()
        context.deleteDatabase(AppConfig.Test.instrumentationDatabaseName)
    }

    @Test
    fun persistsConfigurationConversationAndMessageWithoutPlaintextSecrets() = runBlocking {
        val now = 1_772_000_000_000L
        database.connectionDao().upsert(
            ConnectionProfileEntity(
                id = "conn-1",
                name = "Test Relay",
                protocolAdapter = "OPENAI_COMPATIBLE",
                baseUrl = "https://relay.example",
                credentialId = "cred-1",
                createdAtMillis = now,
                updatedAtMillis = now,
            ),
        )
        database.modelDao().upsert(
            ModelProfileEntity(
                id = "model-1",
                connectionId = "conn-1",
                displayName = "Demo",
                model = "demo-model",
                modelSource = AppConfig.Provider.defaultModelSourceName,
                supportsStreaming = true,
                supportsVision = false,
                defaultContextWindow = AppConfig.Context.defaultContextWindow,
                supports1mContext = false,
                enable1mContext = false,
                temperature = null,
                maxTokens = null,
                isDefault = true,
                createdAtMillis = now,
                updatedAtMillis = now,
            ),
        )
        database.conversationDao().upsert(
            ConversationEntity(
                id = "conv-1",
                title = "Secret chat",
                modelProfileId = "model-1",
                createdAtMillis = now,
                updatedAtMillis = now,
                lastMessagePreview = "hello secret message",
                deleteState = "ACTIVE",
                deletedAtMillis = null,
                purgeAfterMillis = null,
            ),
        )
        database.messageDao().upsert(
            MessageEntity(
                id = "msg-1",
                conversationId = "conv-1",
                role = "USER",
                content = "hello secret message",
                status = "COMPLETED",
                tokenEstimate = 4,
                providerInputTokens = null,
                providerOutputTokens = null,
                providerTotalTokens = null,
                createdAtMillis = now,
                updatedAtMillis = now,
            ),
        )

        assertEquals("cred-1", database.connectionDao().getById("conn-1")!!.credentialId)
        assertEquals("hello secret message", database.messageDao().listByConversation("conv-1").single().content)

        database.close()
        val dbBytes = context.getDatabasePath(AppConfig.Test.instrumentationDatabaseName).readBytes()
        val dbText = dbBytes.toString(StandardCharsets.ISO_8859_1)
        assertFalse(dbText.contains("sk-test-secret"))
        assertFalse(dbText.contains("hello secret message"))
    }

    @Test
    fun hardDeleteConversationCascadesMessages() = runBlocking {
        val now = 1_772_000_000_000L
        database.connectionDao().upsert(
            ConnectionProfileEntity("conn-1", "Relay", "OPENAI_COMPATIBLE", "https://relay.example", "cred-1", now, now),
        )
        database.modelDao().upsert(
            ModelProfileEntity(
                "model-1",
                "conn-1",
                "Demo",
                "demo",
                AppConfig.Provider.defaultModelSourceName,
                true,
                false,
                AppConfig.Context.defaultContextWindow,
                false,
                false,
                null,
                null,
                true,
                now,
                now,
            ),
        )
        database.conversationDao().upsert(
            ConversationEntity("conv-1", "Chat", "model-1", now, now, "", "ACTIVE", null, null),
        )
        database.messageDao().upsert(
            MessageEntity("msg-1", "conv-1", "USER", "hello", "COMPLETED", 1, null, null, null, now, now),
        )

        database.conversationDao().deleteById("conv-1")

        assertEquals(emptyList<MessageEntity>(), database.messageDao().listByConversation("conv-1"))
    }
}
