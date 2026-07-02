package com.keytalk.app.domain.repository

interface FailoverPolicyRepository {
    fun getEnabledConnectionIds(): Set<String>
    fun saveEnabledConnectionIds(connectionIds: Set<String>)
}
