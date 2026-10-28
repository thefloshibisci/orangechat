/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.ai.util

import android.media.ExifInterface
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.util.Base64
import android.util.Base64OutputStream
import androidx.core.net.toUri
import me.rerere.ai.ui.UIMessagePart
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.OutputStream
import java.net.URI

private val supportedTypes = setOf(
    "image/jpeg",
    "image/png",
    "image/gif",
    "image/webp",
)

data class EncodedImage(
    val base64: String,
    val mimeType: String
)

internal enum class ExifTransformType {
    NONE,
    FLIP_HORIZONTAL,
    ROTATE_180,
    FLIP_VERTICAL,
    TRANSPOSE,
    ROTATE_90,
    TRANSVERSE,
    ROTATE_270,
}

internal fun mapExifOrientationToTransform(orientation: Int): ExifTransformType = when (orientation) {
    ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> ExifTransformType.FLIP_HORIZONTAL
    ExifInterface.ORIENTATION_ROTATE_180 -> ExifTransformType.ROTATE_180
    ExifInterface.ORIENTATION_FLIP_VERTICAL -> ExifTransformType.FLIP_VERTICAL
    ExifInterface.ORIENTATION_TRANSPOSE -> ExifTransformType.TRANSPOSE
    ExifInterface.ORIENTATION_ROTATE_90 -> ExifTransformType.ROTATE_90
    ExifInterface.ORIENTATION_TRANSVERSE -> ExifTransformType.TRANSVERSE
    ExifInterface.ORIENTATION_ROTATE_270 -> ExifTransformType.ROTATE_270
    ExifInterface.ORIENTATION_NORMAL,
    ExifInterface.ORIENTATION_UNDEFINED
    -> ExifTransformType.NONE

    else -> ExifTransformType.NONE
}

fun UIMessagePart.Image.encodeBase64(withPrefix: Boolean = true): Result<EncodedImage> = runCatching {
    when {
        this.url.startsWith("file:") -> {
            val file = File(URI(url))
            if (!file.exists()) {
                throw IllegalArgumentException("File does not exist: ${this.url}")
            }
            val mimeType = file.guessMimeType().getOrThrow()
            file.compressAndEncode(mimeType, withPrefix)
        }

        this.url.startsWith("data:") -> {
            val separator = url.indexOf(',')
            require(separator > 0) { "Invalid image data URI" }
            val header = url.substring(5, separator)
            val mimeType = header.substringBefore(';')
            require(mimeType in supportedTypes && header == "$mimeType;base64") {
                "Unsupported image data URI encoding"
            }
            val data = url.substring(separator + 1)
            require(data.isNotBlank()) { "Empty image data URI" }
            EncodedImage(base64 = if (withPrefix) url else data, mimeType = mimeType)
        }
        this.url.startsWith("http") -> {
            // HTTP URL 无法确定 mime type，默认使用 image/png
            EncodedImage(base64 = url, mimeType = "image/png")
        }
        else -> throw IllegalArgumentException("Unsupported URL format: $url")
    }
}

fun UIMessagePart.Video.encodeBase64(withPrefix: Boolean = true): Result<String> = runCatching {
    when {
        this.url.startsWith("file://") -> {
            val filePath =
                this.url.toUri().path ?: throw IllegalArgumentException("Invalid file URI: ${this.url}")
            val file = File(filePath)
            if (!file.exists()) {
                throw IllegalArgumentException("File does not exist: ${this.url}")
            }
            val encoded = file.encodeToBase64Streaming()
            if (withPrefix) "data:video/mp4;base64,$encoded" else encoded
        }

        else -> throw IllegalArgumentException("Unsupported URL format: $url")
    }
}

fun UIMessagePart.Audio.encodeBase64(withPrefix: Boolean = true): Result<String> = runCatching {
    when {
        this.url.startsWith("file://") -> {
            val filePath =
                this.url.toUri().path ?: throw IllegalArgumentException("Invalid file URI: ${this.url}")
            val file = File(filePath)
            if (!file.isFile || file.length() == 0L) {
                throw IllegalArgumentException("File does not exist: ${this.url}")
            }
            val encoded = file.encodeToBase64Streaming()
            if (withPrefix) "data:${audioMimeType()};base64,$encoded" else encoded
        }

        else -> throw IllegalArgumentException("Unsupported URL format: $url")
    }
}

/**
 * 每次请求都会把历史图片重新解码，位图按 4 字节/像素占内存。
 * 1600 万像素时单张位图约 64MB，图片多的会话容易 OOM；压到约 600 万像素（单张约 24MB），
 * 仍能完整保留 1440x3200 这类手机截图，1200 万像素照片会缩到 300 万像素。
 */
internal const val IMAGE_MAX_DIMENSION = 10_000
internal const val IMAGE_MAX_PIXELS = 6_000_000L

private fun File.compressAndEncode(mimeType: String, withPrefix: Boolean): EncodedImage =
    RequestImageEncoding.withCache { cache ->
        val outputType = if (mimeType == "image/gif") "image/png" else "image/jpeg"
        val prefix = if (withPrefix) "data:$outputType;base64," else ""
        val policy = "image-v1:$mimeType:$IMAGE_MAX_DIMENSION:$IMAGE_MAX_PIXELS:jpeg85:exif:gif-sheet-v1"
        if (cache != null) {
            cache.useEncoded(this, policy, { compressForRequest(mimeType, it) }) {
                EncodedImage(it.encodeToBase64Streaming(prefix), outputType)
            }
        } else {
            // Library callers without an Application still get bounded decoding. Release the
            // bitmap before allocating the Base64 buffers, even on this in-memory fallback.
            val compressed = ByteArrayOutputStream()
            compressForRequest(mimeType, compressed)
            val encoded = ByteArrayOutputStream()
            encoded.write(prefix.toByteArray(Charsets.US_ASCII))
            Base64OutputStream(encoded, Base64.NO_WRAP).use { compressed.writeTo(it) }
            EncodedImage(encoded.toString(Charsets.ISO_8859_1.name()), outputType)
        }
    }

private fun File.compressForRequest(mimeType: String, output: OutputStream) {
    if (mimeType == "image/gif") {
        require(length() in 1..GifContactSheet.MAX_INPUT_BYTES.toLong()) { "GIF is too large" }
        output.write(GifContactSheet.render(readBytes()).png)
        return
    }
    val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(absolutePath, options)
    options.inSampleSize = calculateImageInSampleSize(
        options.outWidth, options.outHeight, IMAGE_MAX_DIMENSION, IMAGE_MAX_PIXELS,
    )
    options.inJustDecodeBounds = false
    val bitmap = BitmapFactory.decodeFile(absolutePath, options)
        ?: throw IllegalArgumentException("Failed to decode image: $absolutePath")
    var normalized = bitmap
    try {
        normalized = normalizeByExif(bitmap)
        // Rotation/mirroring can allocate another bitmap; do not keep both during compression.
        if (normalized !== bitmap) bitmap.recycle()
        check(normalized.compress(Bitmap.CompressFormat.JPEG, 85, output)) { "Failed to encode image" }
    } finally {
        if (normalized !== bitmap) normalized.recycle()
        if (!bitmap.isRecycled) bitmap.recycle()
    }
}

private fun File.normalizeByExif(bitmap: Bitmap): Bitmap {
    val orientation = runCatching {
        ExifInterface(absolutePath).getAttributeInt(
            ExifInterface.TAG_ORIENTATION,
            ExifInterface.ORIENTATION_NORMAL
        )
    }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
    val transform = mapExifOrientationToTransform(orientation)
    return applyExifTransform(bitmap, transform)
}

private fun applyExifTransform(bitmap: Bitmap, transform: ExifTransformType): Bitmap {
    if (transform == ExifTransformType.NONE) return bitmap

    val matrix = Matrix()
    when (transform) {
        ExifTransformType.NONE -> return bitmap
        ExifTransformType.FLIP_HORIZONTAL -> matrix.setScale(-1f, 1f)
        ExifTransformType.ROTATE_180 -> matrix.setRotate(180f)
        ExifTransformType.FLIP_VERTICAL -> matrix.setScale(1f, -1f)
        ExifTransformType.TRANSPOSE -> {
            matrix.setRotate(90f)
            matrix.postScale(-1f, 1f)
        }
        ExifTransformType.ROTATE_90 -> matrix.setRotate(90f)
        ExifTransformType.TRANSVERSE -> {
            matrix.setRotate(270f)
            matrix.postScale(-1f, 1f)
        }
        ExifTransformType.ROTATE_270 -> matrix.setRotate(270f)
    }

    return runCatching {
        Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }.getOrElse { bitmap }
}

private fun File.encodeToBase64Streaming(prefix: String = ""): String {
    val expectedSize = ((length() + 2) / 3 * 4 + prefix.length)
        .coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    val byteArrayOutputStream = ByteArrayOutputStream(expectedSize)
    byteArrayOutputStream.write(prefix.toByteArray(Charsets.US_ASCII))
    Base64OutputStream(byteArrayOutputStream, Base64.NO_WRAP).use { base64Stream ->
        inputStream().use { input ->
            input.copyTo(base64Stream, bufferSize = 8 * 1024)
        }
    }
    return byteArrayOutputStream.toString(Charsets.ISO_8859_1.name())
}

internal fun calculateImageInSampleSize(
    width: Int,
    height: Int,
    maxDimension: Int,
    maxPixels: Long
): Int {
    if (width <= 0 || height <= 0) return 1

    var inSampleSize = 1
    while (
        (height / inSampleSize) > maxDimension ||
        (width / inSampleSize) > maxDimension ||
        (width.toLong() / inSampleSize) * (height.toLong() / inSampleSize) > maxPixels
    ) {
        inSampleSize *= 2
    }
    return inSampleSize
}

private fun File.guessMimeType(): Result<String> = runCatching {
    inputStream().use { input ->
        val bytes = ByteArray(16)
        val read = input.read(bytes)
        if (read < 12) error("File too short to determine MIME type")

        // 判断 HEIC 格式：包含 "ftypheic"
        if (bytes.copyOfRange(4, 12).toString(Charsets.US_ASCII) == "ftypheic") {
            return@runCatching "image/heic"
        }

        // 判断 JPEG 格式：开头为 0xFF 0xD8
        if (bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte()) {
            return@runCatching "image/jpeg"
        }

        // 判断 PNG 格式：开头为 89 50 4E 47 0D 0A 1A 0A
        if (bytes.copyOfRange(0, 8).contentEquals(
                byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
            )
        ) {
            return@runCatching "image/png"
        }

        // 判断WebP格式：开头为 "RIFF" + 4字节长度 + "WEBP"
        if (bytes.copyOfRange(0, 4).toString(Charsets.US_ASCII) == "RIFF" && bytes.copyOfRange(8, 12)
                .toString(Charsets.US_ASCII) == "WEBP"
        ) {
            return@runCatching "image/webp"
        }

        // 判断 GIF 格式：开头为 "GIF89a" 或 "GIF87a"
        val header = bytes.copyOfRange(0, 6).toString(Charsets.US_ASCII)
        if (header == "GIF89a" || header == "GIF87a") {
            return@runCatching "image/gif"
        }

        error(
            "Failed to guess MIME type: $header, ${
                bytes.joinToString(",") {
                    it.toUByte().toString()
                }
            }"
        )
    }
}

fun UIMessagePart.Audio.audioMimeType(): String = when (url.substringBefore('?').substringAfterLast('.').lowercase()) {
    "wav" -> "audio/wav"
    "mp3" -> "audio/mpeg"
    "m4a", "mp4" -> "audio/mp4"
    "aac" -> "audio/aac"
    "ogg", "oga" -> "audio/ogg"
    "flac" -> "audio/flac"
    else -> error("无法识别音频格式，请使用 WAV 或 MP3 文件")
}

fun UIMessagePart.Audio.openAiAudioFormat(): String = when (audioMimeType()) {
    "audio/wav" -> "wav"
    "audio/mpeg" -> "mp3"
    else -> error("当前 OpenAI 兼容通道仅支持 WAV/MP3 音频，请启用语音转写")
}
