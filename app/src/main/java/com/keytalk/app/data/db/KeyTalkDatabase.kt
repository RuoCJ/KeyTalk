package com.keytalk.app.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.keytalk.app.data.db.dao.AttachmentDao
import com.keytalk.app.data.db.dao.ConnectionDao
import com.keytalk.app.data.db.dao.ConversationDao
import com.keytalk.app.data.db.dao.ConversationSummaryDao
import com.keytalk.app.data.db.dao.MessageDao
import com.keytalk.app.data.db.dao.ModelDao
import com.keytalk.app.config.AppConfig
import com.keytalk.app.data.db.entity.AttachmentEntity
import com.keytalk.app.data.db.entity.ConnectionProfileEntity
import com.keytalk.app.data.db.entity.ConversationEntity
import com.keytalk.app.data.db.entity.ConversationSummaryEntity
import com.keytalk.app.data.db.entity.MessageEntity
import com.keytalk.app.data.db.entity.ModelProfileEntity
import com.keytalk.app.security.AndroidDatabasePassphraseProvider
import net.sqlcipher.database.SQLiteDatabase
import net.sqlcipher.database.SupportFactory

@Database(
    entities = [
        ConnectionProfileEntity::class,
        ModelProfileEntity::class,
        ConversationEntity::class,
        MessageEntity::class,
        AttachmentEntity::class,
        ConversationSummaryEntity::class,
    ],
    version = 4,
    exportSchema = false,
)
abstract class KeyTalkDatabase : RoomDatabase() {
    abstract fun connectionDao(): ConnectionDao
    abstract fun modelDao(): ModelDao
    abstract fun conversationDao(): ConversationDao
    abstract fun messageDao(): MessageDao
    abstract fun attachmentDao(): AttachmentDao
    abstract fun conversationSummaryDao(): ConversationSummaryDao

    companion object {
        fun create(
            context: Context,
            passphraseProvider: AndroidDatabasePassphraseProvider,
        ): KeyTalkDatabase {
            SQLiteDatabase.loadLibs(context.applicationContext)
            val factory = SupportFactory(passphraseProvider.getOrCreatePassphrase())

            return Room.databaseBuilder(
                context.applicationContext,
                KeyTalkDatabase::class.java,
                AppConfig.Security.databaseFileName,
            )
                .openHelperFactory(factory)
                .addMigrations(migration1To2(), migration2To3(), migration3To4())
                .fallbackToDestructiveMigrationOnDowngrade()
                .build()
        }

        private fun migration1To2(): Migration = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `attachments` (
                        `id` TEXT NOT NULL,
                        `messageId` TEXT NOT NULL,
                        `type` TEXT NOT NULL,
                        `localEncryptedUri` TEXT NOT NULL,
                        `mimeType` TEXT NOT NULL,
                        `width` INTEGER NOT NULL,
                        `height` INTEGER NOT NULL,
                        `sizeBytes` INTEGER NOT NULL,
                        `sha256` TEXT NOT NULL,
                        `createdAtMillis` INTEGER NOT NULL,
                        PRIMARY KEY(`id`),
                        FOREIGN KEY(`messageId`) REFERENCES `messages`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent(),
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_attachments_messageId` ON `attachments` (`messageId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_attachments_sha256` ON `attachments` (`sha256`)")
            }
        }

        private fun migration2To3(): Migration = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `model_profiles` ADD COLUMN `supports1mContext` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `model_profiles` ADD COLUMN `enable1mContext` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `messages` ADD COLUMN `providerInputTokens` INTEGER")
                db.execSQL("ALTER TABLE `messages` ADD COLUMN `providerOutputTokens` INTEGER")
                db.execSQL("ALTER TABLE `messages` ADD COLUMN `providerTotalTokens` INTEGER")
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `conversation_summaries` (
                        `id` TEXT NOT NULL,
                        `conversationId` TEXT NOT NULL,
                        `summaryContent` TEXT NOT NULL,
                        `coveredMessageStartId` TEXT,
                        `coveredMessageEndId` TEXT,
                        `tokenEstimate` INTEGER NOT NULL,
                        `createdAtMillis` INTEGER NOT NULL,
                        `updatedAtMillis` INTEGER NOT NULL,
                        PRIMARY KEY(`id`),
                        FOREIGN KEY(`conversationId`) REFERENCES `conversations`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent(),
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_conversation_summaries_conversationId` ON `conversation_summaries` (`conversationId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_conversation_summaries_coveredMessageStartId` ON `conversation_summaries` (`coveredMessageStartId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_conversation_summaries_coveredMessageEndId` ON `conversation_summaries` (`coveredMessageEndId`)")
            }
        }

        private fun migration3To4(): Migration = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `model_profiles` ADD COLUMN `reasoningEffort` TEXT")
            }
        }
    }
}
