package com.keytalk.app.security

import com.keytalk.app.domain.model.ConnectionProfile
import com.keytalk.app.domain.model.ProtocolAdapter
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant

class CredentialStoreTest {
    @Test
    fun fakeCredentialStoreSavesReadsAndDeletesApiKey() = runTest {
        val store = InMemoryCredentialStore()

        store.saveApiKey("cred-1", "sk-test-secret")
        assertEquals("sk-test-secret", store.readApiKey("cred-1"))

        store.deleteApiKey("cred-1")
        assertNull(store.readApiKey("cred-1"))
    }

    @Test
    fun connectionProfileStoresCredentialIdOnly() {
        val now = Instant.parse("2026-06-27T00:00:00Z")
        val connection = ConnectionProfile(
            id = "conn-1",
            name = "Relay",
            protocolAdapter = ProtocolAdapter.OPENAI_COMPATIBLE,
            baseUrl = "https://relay.example",
            credentialId = "cred-1",
            createdAt = now,
            updatedAt = now,
        )

        assertEquals("cred-1", connection.credentialId)
        assertFalse(connection.toString().contains("sk-test-secret"))
    }
}

private class InMemoryCredentialStore : CredentialStore {
    private val values = mutableMapOf<String, String>()

    override suspend fun saveApiKey(credentialId: String, apiKey: String) {
        values[credentialId] = apiKey
    }

    override suspend fun readApiKey(credentialId: String): String? = values[credentialId]

    override suspend fun deleteApiKey(credentialId: String) {
        values.remove(credentialId)
    }
}
