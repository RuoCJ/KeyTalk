package com.keytalk.app.security

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AndroidCredentialStoreTest {
    @Test
    fun savesReadsAndDeletesApiKeyWithEncryptedSharedPreferences() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val store = AndroidCredentialStore(context)
        val credentialId = "android-test-credential"

        store.deleteApiKey(credentialId)
        store.saveApiKey(credentialId, "sk-android-test-secret")

        assertEquals("sk-android-test-secret", store.readApiKey(credentialId))

        store.deleteApiKey(credentialId)
        assertNull(store.readApiKey(credentialId))
    }
}
