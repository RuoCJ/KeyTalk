package com.keytalk.app.domain.model

import com.keytalk.app.config.AppConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BuiltInModelCatalogTest {
    @Test
    fun infersGpt5FamilyAsOneMillionContext() {
        val capability = BuiltInModelCatalog.capabilityFor("gpt-5.4")

        assertEquals(AppConfig.Context.oneMillionWindow, capability?.defaultContextWindow)
        assertTrue(capability?.supportsVision == true)
        assertTrue(capability?.supports1mContext == true)
    }

    @Test
    fun treatsKnownLargeContextModelsAsOneMillionCapable() {
        val qwenTurbo = BuiltInModelCatalog.capabilityFor("qwen-turbo")
        val gemini25Pro = BuiltInModelCatalog.capabilityFor("gemini-2.5-pro")

        assertTrue(qwenTurbo?.supports1mContext == true)
        assertTrue(gemini25Pro?.supports1mContext == true)
    }

    @Test
    fun leavesUnknownModelUnclassified() {
        assertNull(BuiltInModelCatalog.capabilityFor("vendor-private-model"))
    }
}
