package me.rerere.ai.util

import java.io.OutputStream
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.encodeToStream
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody
import okio.BufferedSink

/**
 * Keep the JSON tree, but never allocate a full serialized String and UTF-8 byte array.
 * Count bytes without retaining them so gateways still receive a Content-Length, and cache
 * that count because OkHttp and logging interceptors may ask for it more than once.
 * The tree must not be mutated after constructing the body. Writes are replayable for retries.
 */
@OptIn(ExperimentalSerializationApi::class)
internal fun JsonElement.toJsonRequestBody(format: Json = json): RequestBody = object : RequestBody() {
    private val length: Long by lazy {
        val counter = CountingOutputStream()
        format.encodeToStream(JsonElement.serializer(), this@toJsonRequestBody, counter)
        counter.count
    }

    override fun contentType() = JSON_MEDIA_TYPE

    override fun contentLength(): Long = length

    override fun writeTo(sink: BufferedSink) {
        // OkHttp owns the sink; do not close it (including on an encoding/write failure).
        format.encodeToStream(JsonElement.serializer(), this@toJsonRequestBody, sink.outputStream())
    }
}

private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

private class CountingOutputStream : OutputStream() {
    var count = 0L
        private set

    override fun write(value: Int) { count++ }

    override fun write(bytes: ByteArray, offset: Int, length: Int) { count += length }
}
