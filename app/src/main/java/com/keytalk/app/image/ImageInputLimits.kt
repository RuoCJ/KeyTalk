package com.keytalk.app.image

import com.keytalk.app.config.AppConfig

object ImageInputLimits {
    fun ensureSourceFileSizeAllowed(sizeBytes: Long) {
        if (sizeBytes < 0) return
        require(sizeBytes <= AppConfig.Image.maxSourceBytes) {
            "图片文件不能超过 ${formatBytes(AppConfig.Image.maxSourceBytes.toLong())}，请先压缩或选择更小的图片。"
        }
    }

    fun ensureProviderPayloadSizeAllowed(sizeBytes: Int) {
        require(sizeBytes > 0) { "图片数据不能为空。" }
        require(sizeBytes <= AppConfig.Image.maxCompressedBytes) {
            "图片压缩后仍超过 ${formatBytes(AppConfig.Image.maxCompressedBytes.toLong())}，请选择更小的图片。"
        }
    }

    fun formatBytes(bytes: Long): String =
        if (bytes >= 1024L * 1024L) {
            "${bytes / (1024L * 1024L)}MB"
        } else {
            "${bytes / 1024L}KB"
        }
}
