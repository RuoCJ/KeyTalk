package com.keytalk.app.backup

import com.keytalk.app.config.AppConfig
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Clock
import java.time.Instant
import java.util.Arrays
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

class BackupCrypto(
    private val json: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    },
    private val clock: Clock = Clock.systemUTC(),
    private val secureRandom: SecureRandom = SecureRandom(),
) {
    fun encryptPayload(
        payload: BackupPayload,
        password: CharArray,
        appVersion: String,
        sourcePlatform: String = AppConfig.Backup.defaultSourcePlatform,
    ): String {
        validateBackupPassword(password)?.let { throw IllegalArgumentException(it) }
        val salt = secureRandom.randomBytes(AppConfig.Backup.saltBytes)
        val nonce = secureRandom.randomBytes(AppConfig.Backup.nonceBytes)
        val key = deriveKey(password, salt, AppConfig.Backup.defaultIterations)
        val plaintext = json.encodeToString(payload).toByteArray(Charsets.UTF_8)
        val ciphertext = aesGcm(Cipher.ENCRYPT_MODE, key, nonce).doFinal(plaintext)
        val envelope = BackupEnvelope(
            backupVersion = AppConfig.Backup.backupVersion,
            createdAt = Instant.now(clock).toString(),
            sourcePlatform = sourcePlatform,
            appVersion = appVersion,
            kdf = BackupKdf(
                name = AppConfig.Backup.kdfName,
                iterations = AppConfig.Backup.defaultIterations,
                salt = salt.base64(),
                keyLengthBits = AppConfig.Backup.keyBits,
            ),
            cipher = BackupCipher(
                name = AppConfig.Backup.cipherName,
                nonce = nonce.base64(),
            ),
            checksum = sha256(ciphertext).base64(),
            encryptedPayload = ciphertext.base64(),
        )
        return json.encodeToString(envelope)
    }

    fun decryptPayload(backupJson: String, password: CharArray): BackupPayload {
        validateBackupPassword(password)?.let { throw IllegalArgumentException(it) }
        require(backupJson.length <= AppConfig.Backup.maxBackupJsonChars) { "备份文件过大。" }
        val envelope = runCatching { json.decodeFromString<BackupEnvelope>(backupJson) }
            .getOrElse { throw IllegalArgumentException("备份文件格式无效。") }
        require(envelope.backupVersion <= AppConfig.Backup.backupVersion) { "备份版本过高，当前版本不支持导入。" }
        require(envelope.kdf.name == AppConfig.Backup.kdfName) { "不支持的备份 KDF：${envelope.kdf.name}" }
        require(envelope.kdf.iterations in AppConfig.Backup.defaultIterations..AppConfig.Backup.maxKdfIterations) { "备份 KDF 迭代次数不受支持。" }
        require(envelope.kdf.keyLengthBits == AppConfig.Backup.keyBits) { "备份 KDF 密钥长度不受支持。" }
        require(envelope.cipher.name == AppConfig.Backup.cipherName) { "不支持的备份加密算法：${envelope.cipher.name}" }
        require(envelope.encryptedPayload.length <= AppConfig.Backup.maxEncryptedPayloadBase64Chars) { "备份文件过大。" }

        val ciphertext = envelope.encryptedPayload.fromBase64("备份密文")
        require(ciphertext.size <= AppConfig.Backup.maxEncryptedPayloadBytes) { "备份文件过大。" }
        val expectedChecksum = envelope.checksum.fromBase64("备份 checksum")
        val salt = envelope.kdf.salt.fromBase64("备份 KDF salt")
        val nonce = envelope.cipher.nonce.fromBase64("备份加密 nonce")
        require(salt.size == AppConfig.Backup.saltBytes) { "备份 KDF salt 长度无效。" }
        require(nonce.size == AppConfig.Backup.nonceBytes) { "备份加密 nonce 长度无效。" }
        require(expectedChecksum.size == AppConfig.Backup.checksumBytes) { "备份 checksum 长度无效。" }
        require(sha256(ciphertext).contentEquals(expectedChecksum)) { "备份文件损坏或校验失败。" }

        val key = deriveKey(password, salt, envelope.kdf.iterations)
        val plaintext = runCatching {
            aesGcm(Cipher.DECRYPT_MODE, key, nonce).doFinal(ciphertext)
        }.getOrElse {
            throw IllegalArgumentException("备份密码错误或文件已损坏。")
        }
        return runCatching { json.decodeFromString<BackupPayload>(plaintext.toString(Charsets.UTF_8)) }
            .getOrElse { throw IllegalArgumentException("备份 payload 格式无效。") }
    }

    private fun deriveKey(password: CharArray, salt: ByteArray, iterations: Int): SecretKeySpec {
        require(iterations in AppConfig.Backup.defaultIterations..AppConfig.Backup.maxKdfIterations) { "备份 KDF 迭代次数不受支持。" }
        require(salt.size == AppConfig.Backup.saltBytes) { "备份 KDF salt 长度无效。" }
        val spec = PBEKeySpec(password, salt, iterations, AppConfig.Backup.keyBits)
        val bytes = try {
            SecretKeyFactory.getInstance(AppConfig.Backup.kdfJvmName).generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
        return try {
            SecretKeySpec(bytes, "AES")
        } finally {
            Arrays.fill(bytes, 0)
        }
    }

    private fun aesGcm(mode: Int, key: SecretKeySpec, nonce: ByteArray): Cipher =
        Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(mode, key, GCMParameterSpec(128, nonce))
        }

    private fun SecureRandom.randomBytes(size: Int): ByteArray =
        ByteArray(size).also { nextBytes(it) }

    private fun ByteArray.base64(): String = Base64.getEncoder().encodeToString(this)
    private fun String.fromBase64(fieldName: String): ByteArray =
        runCatching { Base64.getDecoder().decode(this) }
            .getOrElse { throw IllegalArgumentException("$fieldName 格式无效。") }

    private fun sha256(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)

    companion object {
        fun validateBackupPassword(password: CharArray): String? = when {
            password.isEmpty() -> "备份密码不能为空。"
            password.size < AppConfig.Backup.minPasswordLength -> "备份密码至少需要 ${AppConfig.Backup.minPasswordLength} 个字符。"
            else -> null
        }
    }
}
