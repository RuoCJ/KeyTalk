package com.keytalk.app.image

import com.keytalk.app.config.AppConfig
import org.junit.Assert.assertTrue
import org.junit.Test

class ImageInputLimitsTest {
    @Test
    fun acceptsSourceFileAtConfiguredLimit() {
        ImageInputLimits.ensureSourceFileSizeAllowed(AppConfig.Image.maxSourceBytes.toLong())
    }

    @Test
    fun rejectsSourceFileAboveConfiguredLimit() {
        val failure = runCatching {
            ImageInputLimits.ensureSourceFileSizeAllowed(AppConfig.Image.maxSourceBytes.toLong() + 1)
        }.exceptionOrNull()

        assertTrue(failure is IllegalArgumentException)
        assertTrue(failure?.message.orEmpty().contains("图片文件不能超过"))
    }

    @Test
    fun rejectsProviderPayloadAboveCompressedLimit() {
        val failure = runCatching {
            ImageInputLimits.ensureProviderPayloadSizeAllowed(AppConfig.Image.maxCompressedBytes + 1)
        }.exceptionOrNull()

        assertTrue(failure is IllegalArgumentException)
        assertTrue(failure?.message.orEmpty().contains("图片压缩后仍超过"))
    }
}
