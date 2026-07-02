package com.keytalk.app.image

import java.io.File
import java.net.URI

class EncryptedImageCacheFiles(rootDir: File) {
    private val cacheRoot = runCatching { rootDir.canonicalFile }.getOrElse { rootDir.absoluteFile }

    fun resolveCacheFile(localEncryptedUri: String): File? {
        val file = runCatching { File(URI(localEncryptedUri)).canonicalFile }.getOrNull() ?: return null
        val isInsideCache = file.path.startsWith(cacheRoot.path + File.separator)
        val isEncryptedCache = file.name.endsWith(".enc")
        return if (isInsideCache && isEncryptedCache) file else null
    }

    fun delete(localEncryptedUri: String): Boolean {
        val file = resolveCacheFile(localEncryptedUri) ?: return false
        return file.exists() && file.delete()
    }

    fun cleanupUnreferenced(referencedUris: Set<String>): Int {
        val referencedFiles = referencedUris
            .mapNotNull(::resolveCacheFile)
            .map { it.canonicalPath }
            .toSet()

        return cacheRoot.listFiles()
            .orEmpty()
            .asSequence()
            .filter { it.isFile && it.name.endsWith(".enc") }
            .count { file ->
                val canonicalPath = runCatching { file.canonicalPath }.getOrNull() ?: return@count false
                canonicalPath !in referencedFiles && file.delete()
            }
    }
}
