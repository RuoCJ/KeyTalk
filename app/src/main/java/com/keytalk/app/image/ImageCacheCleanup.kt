package com.keytalk.app.image

interface ImageCacheCleanup {
    suspend fun cleanupUnreferenced(referencedUris: Set<String>): Int
}
