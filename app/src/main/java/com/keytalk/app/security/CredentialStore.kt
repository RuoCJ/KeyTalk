package com.keytalk.app.security

interface CredentialStore {
    suspend fun saveApiKey(credentialId: String, apiKey: String)
    suspend fun readApiKey(credentialId: String): String?
    suspend fun deleteApiKey(credentialId: String)
}
