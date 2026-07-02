package com.keytalk.app.config

object AppConfig {
    object Backup {
        val backupVersion = 1
        val schemaVersion = 1
        val kdfName = "pbkdf2-hmac-sha256"
        val kdfJvmName = "PBKDF2WithHmacSHA256"
        val cipherName = "aes-256-gcm"
        val keyBits = 256
        val defaultIterations = 120_000
        val maxKdfIterations = 1_000_000
        val saltBytes = 16
        val nonceBytes = 12
        val checksumBytes = 32
        val minPasswordLength = 8
        val maxBackupJsonChars = 128 * 1024 * 1024
        val maxEncryptedPayloadBytes = 96 * 1024 * 1024
        val maxEncryptedPayloadBase64Chars = ((maxEncryptedPayloadBytes + 2) / 3) * 4 + 4
        val defaultAppVersion = "0.2.0-mvp-b"
        val defaultSourcePlatform = "android"
    }

    object Image {
        val maxLongSide = 1_568
        val maxSourceBytes = 32 * 1024 * 1024
        val maxCompressedBytes = 3 * 1024 * 1024
        val minJpegQuality = 56
        val maxPendingImages = 4
        val nonceBytes = 12
        val cameraCacheDirName = "camera_capture"
        val cameraFilePrefix = "capture-"
        val encryptedCacheDirName = "encrypted_image_cache"
        val imageCacheKeyAlias = "keytalk_image_cache_aes_gcm"
        val aesGcmTransformation = "AES/GCM/NoPadding"
    }

    object Context {
        val oneMillionWindow = 1_000_000
        val defaultRecentTargetRatio = 0.70
        val warningRatio70 = 0.70
        val warningRatio85 = 0.85
        val criticalRatio95 = 0.95
        val summaryMessagePreviewChars = 160
        val defaultContextWindow = 128_000
        val tokenEstimateCharsPerToken = 4
    }

    object Conversation {
        val trashRetentionDays = 30L
        val titleMaxChars = 80
        val previewMaxChars = 160
        val recentConversationDisplayLimit = 20
    }

    object Network {
        val connectTimeoutSeconds = 30L
        val readTimeoutSeconds = 120L
        val callTimeoutSeconds = 180L
        val sanitizedResponseMaxChars = 500
        val providerRawResponseMaxChars = 2_000
        val modelTestPreviewChars = 40
        val jsonContentType = "application/json; charset=utf-8"
    }

    object Security {
        val databaseFileName = "keytalk.db"
        val credentialPrefsName = "keytalk_credentials"
        val apiKeyStoragePrefix = "api_key_"
        val databasePrefsName = "keytalk_database_keys"
        val databaseKeyName = "room_sqlcipher_passphrase"
    }

    object Failover {
        val prefsName = "keytalk_failover_policy"
        val enabledConnectionIdsKey = "enabled_connection_ids"
    }

    object Provider {
        val supportedImageMediaTypes = setOf("image/jpeg", "image/png", "image/webp")
        val anthropicVersion = "2023-06-01"
        val claudeDefaultMaxTokens = 1024
        val defaultModelSourceName = "其他"
    }

    object ModelCatalog {
        val context32k = 32_768
        val context64k = 64_000
        val context131k = 131_072
        val context200k = 200_000
        val openAiGpt41Context = 1_047_576
        val gemini15ProContext = 2_000_000
        val qwenLongContext = 10_000_000
    }

    object Settings {
        val maxAutoSaveDiscoveredModels = 100
        val suggestedModelPreviewCount = 20
        val modelLibraryPreviewCount = 6
        val uiStateStopTimeoutMillis = 5_000L
        val modelProbeTemperature = 0.2
        val modelProbeMaxTokens = 64
        val connectionTestMaxTokens = 16
    }

    object Test {
        val instrumentationDatabaseName = "keytalk-instrumentation-test.db"
    }
}
