package com.keytalk.app.image

import android.content.Context
import com.keytalk.app.config.AppConfig
import com.keytalk.app.data.db.dao.AttachmentDao
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class ImageCacheMaintenanceService(
    private val attachmentDao: AttachmentDao,
    private val imageCache: ImageCacheCleanup,
    context: Context,
) {
    private val appContext = context.applicationContext
    private val cameraCacheRoot = File(appContext.cacheDir, AppConfig.Image.cameraCacheDirName).also { it.mkdirs() }.canonicalFile

    suspend fun cleanupOrphanedImages(): Int = withContext(Dispatchers.IO) {
        imageCache.cleanupUnreferenced(attachmentDao.listAllLocalEncryptedUris().toSet())
    }

    suspend fun cleanupStaleCameraCaptures(olderThanMillis: Long): Int = withContext(Dispatchers.IO) {
        cameraCacheRoot.listFiles()
            .orEmpty()
            .asSequence()
            .filter { it.isFile }
            .filter { file ->
                val canonicalPath = runCatching { file.canonicalPath }.getOrNull()
                canonicalPath != null && canonicalPath.startsWith(cameraCacheRoot.path + File.separator)
            }
            .filter { it.lastModified() <= olderThanMillis }
            .count { it.delete() }
    }
}
