package com.keytalk.app.backup

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import com.keytalk.app.config.AppConfig
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.Base64

class BackupCryptoTest {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }
    private val crypto = BackupCrypto(
        json = json,
        clock = Clock.fixed(Instant.parse("2026-06-28T00:00:00Z"), ZoneOffset.UTC),
    )

    @Test
    fun decryptRejectsTamperedCiphertextBeforeGcmDecrypt() {
        val backupJson = crypto.encryptPayload(minimalPayload(), "passphrase".toCharArray(), "0.2.0-mvp-b")
        val envelope = json.decodeFromString<BackupEnvelope>(backupJson)
        val tamperedCiphertext = Base64.getDecoder().decode(envelope.encryptedPayload).also { bytes ->
            bytes[0] = (bytes[0].toInt() xor 0x01).toByte()
        }
        val tamperedJson = json.encodeToString(
            envelope.copy(encryptedPayload = Base64.getEncoder().encodeToString(tamperedCiphertext)),
        )

        val error = assertThrows(IllegalArgumentException::class.java) {
            crypto.decryptPayload(tamperedJson, "passphrase".toCharArray())
        }

        assertTrue(error.message.orEmpty().contains("校验失败"))
    }

    @Test
    fun decryptRejectsUnsupportedKdfKeyLength() {
        val backupJson = crypto.encryptPayload(minimalPayload(), "passphrase".toCharArray(), "0.2.0-mvp-b")
        val envelope = json.decodeFromString<BackupEnvelope>(backupJson)
        val tamperedJson = json.encodeToString(
            envelope.copy(kdf = envelope.kdf.copy(keyLengthBits = 128)),
        )

        val error = assertThrows(IllegalArgumentException::class.java) {
            crypto.decryptPayload(tamperedJson, "passphrase".toCharArray())
        }

        assertTrue(error.message.orEmpty().contains("密钥长度"))
    }

    @Test
    fun decryptRejectsWeakKdfIterations() {
        val backupJson = crypto.encryptPayload(minimalPayload(), "passphrase".toCharArray(), "0.2.0-mvp-b")
        val envelope = json.decodeFromString<BackupEnvelope>(backupJson)
        val tamperedJson = json.encodeToString(
            envelope.copy(kdf = envelope.kdf.copy(iterations = 1)),
        )

        val error = assertThrows(IllegalArgumentException::class.java) {
            crypto.decryptPayload(tamperedJson, "passphrase".toCharArray())
        }

        assertTrue(error.message.orEmpty().contains("迭代次数"))
    }

    @Test
    fun encryptRejectsWeakBackupPassword() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            crypto.encryptPayload(minimalPayload(), "short".toCharArray(), "0.2.0-mvp-b")
        }

        assertTrue(error.message.orEmpty().contains("至少需要"))
    }

    private fun minimalPayload(): BackupPayload =
        BackupPayload(
            schemaVersion = AppConfig.Backup.schemaVersion,
            exportOptions = BackupExportOptions(includeApiKeys = false, includeTrash = false),
            connections = emptyList(),
            models = emptyList(),
            conversations = emptyList(),
            messages = emptyList(),
            attachments = emptyList(),
        )
}
