package com.keytalk.app.security

import android.content.Context
import android.util.Base64
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.keytalk.app.config.AppConfig
import java.security.SecureRandom

class AndroidDatabasePassphraseProvider(context: Context) {
    private val appContext = context.applicationContext

    private val preferences by lazy {
        val masterKey = MasterKey.Builder(appContext)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()

        EncryptedSharedPreferences.create(
            appContext,
            AppConfig.Security.databasePrefsName,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    fun getOrCreatePassphrase(): ByteArray {
        val existing = preferences.getString(AppConfig.Security.databaseKeyName, null)
        if (existing != null) {
            return Base64.decode(existing, Base64.NO_WRAP)
        }

        val generated = ByteArray(32)
        SecureRandom().nextBytes(generated)
        preferences.edit()
            .putString(AppConfig.Security.databaseKeyName, Base64.encodeToString(generated, Base64.NO_WRAP))
            .apply()
        return generated
    }
}
