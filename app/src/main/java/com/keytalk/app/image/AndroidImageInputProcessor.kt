package com.keytalk.app.image

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Base64
import androidx.core.content.FileProvider
import com.keytalk.app.config.AppConfig
import com.keytalk.app.domain.model.Attachment
import com.keytalk.app.domain.model.PreparedImageAttachment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.util.UUID
import kotlin.math.ceil
import kotlin.math.max

class AndroidImageInputProcessor(
    context: Context,
    private val encryptedImageCache: AndroidEncryptedImageCache,
) {
    private val appContext = context.applicationContext

    fun createCameraOutputUri(): Uri {
        val dir = File(appContext.cacheDir, AppConfig.Image.cameraCacheDirName).also { it.mkdirs() }
        val file = File(dir, "${AppConfig.Image.cameraFilePrefix}${UUID.randomUUID()}.jpg")
        return FileProvider.getUriForFile(appContext, "${appContext.packageName}.fileprovider", file)
    }

    suspend fun process(uri: Uri): PreparedImageAttachment = withContext(Dispatchers.IO) {
        querySourceSizeBytes(uri)?.let(ImageInputLimits::ensureSourceFileSizeAllowed)
        val sourceBytes = appContext.contentResolver.openInputStream(uri)?.use { input ->
            input.readBytesWithLimit()
        } ?: error("无法读取图片。")
        require(sourceBytes.isNotEmpty()) { "图片文件为空。" }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(sourceBytes, 0, sourceBytes.size, bounds)
        require(bounds.outWidth > 0 && bounds.outHeight > 0) { "图片格式不受支持或文件已损坏。" }

        val sampleSize = calculateSampleSize(bounds.outWidth, bounds.outHeight)
        val bitmap = BitmapFactory.decodeByteArray(
            sourceBytes,
            0,
            sourceBytes.size,
            BitmapFactory.Options().apply { inSampleSize = sampleSize },
        ) ?: error("图片解码失败。")

        val scaled = bitmap.scaleToMaxSide(AppConfig.Image.maxLongSide)
        if (scaled !== bitmap) bitmap.recycle()

        try {
            val compressed = scaled.compressForProvider()
            val width = scaled.width
            val height = scaled.height
            encryptedImageCache.save(
                bytes = compressed.bytes,
                mimeType = compressed.mimeType,
                width = width,
                height = height,
            )
        } finally {
            scaled.recycle()
        }
    }

    suspend fun prepareExistingAttachment(attachment: Attachment): PreparedImageAttachment = withContext(Dispatchers.IO) {
        val bytes = encryptedImageCache.readDecrypted(attachment.localEncryptedUri)
            ?: error("无法读取原图片缓存，请重新选择图片后再发送。")
        PreparedImageAttachment(
            localEncryptedUri = attachment.localEncryptedUri,
            mimeType = attachment.mimeType,
            width = attachment.width,
            height = attachment.height,
            sizeBytes = attachment.sizeBytes,
            sha256 = attachment.sha256,
            base64Data = Base64.encodeToString(bytes, Base64.NO_WRAP),
        )
    }

    private fun calculateSampleSize(width: Int, height: Int): Int {
        val maxSide = max(width, height)
        if (maxSide <= AppConfig.Image.maxLongSide * 2) return 1
        return ceil(maxSide.toDouble() / (AppConfig.Image.maxLongSide * 2).toDouble()).toInt().coerceAtLeast(1)
    }

    private fun querySourceSizeBytes(uri: Uri): Long? {
        runCatching {
            appContext.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
                val index = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (index >= 0 && cursor.moveToFirst() && !cursor.isNull(index)) {
                    return cursor.getLong(index).takeIf { it >= 0L }
                }
            }
        }
        return runCatching {
            appContext.contentResolver.openAssetFileDescriptor(uri, "r")?.use { descriptor ->
                descriptor.length.takeIf { it >= 0L }
            }
        }.getOrNull()
    }

    private fun InputStream.readBytesWithLimit(): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(ImageReadBufferBytes)
        var totalBytes = 0
        while (true) {
            val read = read(buffer)
            if (read == -1) break
            totalBytes += read
            ImageInputLimits.ensureSourceFileSizeAllowed(totalBytes.toLong())
            output.write(buffer, 0, read)
        }
        return output.toByteArray()
    }

    private fun Bitmap.scaleToMaxSide(maxSide: Int): Bitmap {
        val currentMaxSide = max(width, height)
        if (currentMaxSide <= maxSide) return this
        val scale = maxSide.toFloat() / currentMaxSide.toFloat()
        return Bitmap.createScaledBitmap(this, (width * scale).toInt(), (height * scale).toInt(), true)
    }

    private fun Bitmap.compressForProvider(): CompressedImage {
        var quality = 88
        var bytes: ByteArray
        do {
            val output = ByteArrayOutputStream()
            compress(Bitmap.CompressFormat.JPEG, quality, output)
            bytes = output.toByteArray()
            quality -= 8
        } while (bytes.size > AppConfig.Image.maxCompressedBytes && quality >= AppConfig.Image.minJpegQuality)
        ImageInputLimits.ensureProviderPayloadSizeAllowed(bytes.size)
        return CompressedImage(mimeType = "image/jpeg", bytes = bytes)
    }

    private data class CompressedImage(
        val mimeType: String,
        val bytes: ByteArray,
    )
}

private const val ImageReadBufferBytes = 8 * 1024
