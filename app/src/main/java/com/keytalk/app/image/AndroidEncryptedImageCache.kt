package com.keytalk.app.image

import android.content.Context
import android.net.Uri
import android.util.Base64
import com.keytalk.app.config.AppConfig
import com.keytalk.app.domain.model.PreparedImageAttachment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.security.KeyStore
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class AndroidEncryptedImageCache(context: Context) : ImageCacheCleanup {
    private val appContext = context.applicationContext
    private val cacheDir = File(appContext.filesDir, AppConfig.Image.encryptedCacheDirName).also { it.mkdirs() }
    private val cacheFiles = EncryptedImageCacheFiles(cacheDir)

    suspend fun save(
        bytes: ByteArray,
        mimeType: String,
        width: Int,
        height: Int,
    ): PreparedImageAttachment = withContext(Dispatchers.IO) {
        ImageInputLimits.ensureProviderPayloadSizeAllowed(bytes.size)
        val sha256 = bytes.sha256Hex()
        val encryptedBytes = encrypt(bytes)
        val extension = when (mimeType) {
            "image/png" -> "png"
            "image/webp" -> "webp"
            else -> "jpg"
        }
        val file = File(cacheDir, "$sha256.$extension.enc")
        file.writeBytes(encryptedBytes)
        PreparedImageAttachment(
            localEncryptedUri = Uri.fromFile(file).toString(),
            mimeType = mimeType,
            width = width,
            height = height,
            sizeBytes = bytes.size,
            sha256 = sha256,
            base64Data = Base64.encodeToString(bytes, Base64.NO_WRAP),
        )
    }

    suspend fun readDecrypted(localEncryptedUri: String): ByteArray? = withContext(Dispatchers.IO) {
        val file = cacheFiles.resolveCacheFile(localEncryptedUri) ?: return@withContext null
        if (!file.exists()) return@withContext null
        decrypt(file.readBytes())
    }

    suspend fun delete(localEncryptedUri: String): Boolean = withContext(Dispatchers.IO) {
        cacheFiles.delete(localEncryptedUri)
    }

    override suspend fun cleanupUnreferenced(referencedUris: Set<String>): Int = withContext(Dispatchers.IO) {
        cacheFiles.cleanupUnreferenced(referencedUris)
    }

    private fun encrypt(bytes: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(AppConfig.Image.aesGcmTransformation)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val iv = cipher.iv ?: error("图片加密初始化失败：未生成 IV")
        val ciphertext = cipher.doFinal(bytes)
        return iv + ciphertext
    }

    private fun decrypt(bytes: ByteArray): ByteArray {
        require(bytes.size > AppConfig.Image.nonceBytes) { "加密图片数据过小。" }
        val iv = bytes.copyOfRange(0, AppConfig.Image.nonceBytes)
        val ciphertext = bytes.copyOfRange(AppConfig.Image.nonceBytes, bytes.size)
        val cipher = Cipher.getInstance(AppConfig.Image.aesGcmTransformation)
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(128, iv))
        return cipher.doFinal(ciphertext)
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keyStore.getEntry(AppConfig.Image.imageCacheKeyAlias, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }

        val keyGenerator = KeyGenerator.getInstance("AES", "AndroidKeyStore")
        val spec = android.security.keystore.KeyGenParameterSpec.Builder(
            AppConfig.Image.imageCacheKeyAlias,
            android.security.keystore.KeyProperties.PURPOSE_ENCRYPT or
                android.security.keystore.KeyProperties.PURPOSE_DECRYPT,
        )
            .setBlockModes(android.security.keystore.KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(android.security.keystore.KeyProperties.ENCRYPTION_PADDING_NONE)
            .setRandomizedEncryptionRequired(true)
            .build()
        keyGenerator.init(spec)
        return keyGenerator.generateKey()
    }

    private fun ByteArray.sha256Hex(): String =
        MessageDigest.getInstance("SHA-256")
            .digest(this)
            .joinToString("") { "%02x".format(it) }
}
