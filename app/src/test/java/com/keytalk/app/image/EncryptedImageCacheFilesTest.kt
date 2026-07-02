package com.keytalk.app.image

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class EncryptedImageCacheFilesTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun cleanupUnreferencedDeletesOnlyOrphansInCacheRoot() {
        val root = tempFolder.newFolder("encrypted_image_cache")
        val cacheFiles = EncryptedImageCacheFiles(root)
        val referenced = File(root, "ref.jpg.enc").apply { writeText("ref") }
        val orphan = File(root, "orphan.jpg.enc").apply { writeText("orphan") }
        val outside = tempFolder.newFile("outside.jpg.enc").apply { writeText("outside") }

        val deleted = cacheFiles.cleanupUnreferenced(setOf(referenced.toURI().toString(), outside.toURI().toString()))

        assertEquals(1, deleted)
        assertTrue(referenced.exists())
        assertFalse(orphan.exists())
        assertTrue(outside.exists())
    }

    @Test
    fun resolveCacheFileRejectsOutsideOrNonEncryptedFiles() {
        val root = tempFolder.newFolder("encrypted_image_cache")
        val cacheFiles = EncryptedImageCacheFiles(root)
        val insideEnc = File(root, "inside.jpg.enc").apply { writeText("ok") }
        val insideText = File(root, "inside.txt").apply { writeText("no") }
        val outsideEnc = tempFolder.newFile("outside.jpg.enc").apply { writeText("out") }

        assertNotNull(cacheFiles.resolveCacheFile(insideEnc.toURI().toString()))
        assertNull(cacheFiles.resolveCacheFile(insideText.toURI().toString()))
        assertNull(cacheFiles.resolveCacheFile(outsideEnc.toURI().toString()))
    }
}
