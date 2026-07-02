package com.keytalk.app.security

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.keytalk.app.config.AppConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class AndroidCredentialStore(context: Context) : CredentialStore {
    private val appContext = context.applicationContext

    private val preferences by lazy {
        val masterKey = MasterKey.Builder(appContext)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()

        EncryptedSharedPreferences.create(
            appContext,
            AppConfig.Security.credentialPrefsName,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    override suspend fun saveApiKey(credentialId: String, apiKey: String) {
        require(credentialId.isNotBlank()) { "凭据标识不能为空。" }
        require(apiKey.isNotBlank()) { "API Key 不能为空。" }
        withContext(Dispatchers.IO) {
            preferences.edit().putString(storageKey(credentialId), apiKey).apply()
        }
    }

    override suspend fun readApiKey(credentialId: String): String? {
        require(credentialId.isNotBlank()) { "凭据标识不能为空。" }
        return withContext(Dispatchers.IO) {
            preferences.getString(storageKey(credentialId), null)
        }
    }

    override suspend fun deleteApiKey(credentialId: String) {
        require(credentialId.isNotBlank()) { "凭据标识不能为空。" }
        withContext(Dispatchers.IO) {
            preferences.edit().remove(storageKey(credentialId)).apply()
        }
    }

    private fun storageKey(credentialId: String): String = "${AppConfig.Security.apiKeyStoragePrefix}$credentialId"
}
