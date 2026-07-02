package com.keytalk.app.data.repository

import android.content.Context
import com.keytalk.app.config.AppConfig
import com.keytalk.app.domain.repository.FailoverPolicyRepository

class SharedPreferencesFailoverPolicyRepository(context: Context) : FailoverPolicyRepository {
    private val preferences = context.applicationContext.getSharedPreferences(AppConfig.Failover.prefsName, Context.MODE_PRIVATE)

    override fun getEnabledConnectionIds(): Set<String> =
        preferences.getStringSet(AppConfig.Failover.enabledConnectionIdsKey, emptySet()).orEmpty()

    override fun saveEnabledConnectionIds(connectionIds: Set<String>) {
        preferences.edit()
            .putStringSet(AppConfig.Failover.enabledConnectionIdsKey, connectionIds)
            .apply()
    }
}
