package com.keytalk.app.backup

import com.keytalk.app.image.AndroidEncryptedImageCache

interface BackupAttachmentStore {
    suspend fun readPlainBytes(localEncryptedUri: String): ByteArray?
    suspend fun writePlainBytes(bytes: ByteArray, mimeType: String, width: Int, height: Int): String
}

class AndroidBackupAttachmentStore(
    private val imageCache: AndroidEncryptedImageCache,
) : BackupAttachmentStore {
    override suspend fun readPlainBytes(localEncryptedUri: String): ByteArray? =
        imageCache.readDecrypted(localEncryptedUri)

    override suspend fun writePlainBytes(bytes: ByteArray, mimeType: String, width: Int, height: Int): String =
        imageCache.save(bytes, mimeType, width, height).localEncryptedUri
}

object NoopBackupAttachmentStore : BackupAttachmentStore {
    override suspend fun readPlainBytes(localEncryptedUri: String): ByteArray? = null
    override suspend fun writePlainBytes(bytes: ByteArray, mimeType: String, width: Int, height: Int): String =
        "imported://attachment/${bytes.size}"
}
