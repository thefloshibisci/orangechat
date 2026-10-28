/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.ai

import me.rerere.common.android.LogEntry
import me.rerere.common.android.Logging
import okhttp3.Interceptor
import okhttp3.Response
import okio.Buffer

class RequestLoggingInterceptor : Interceptor {
    companion object {
        private const val MAX_RESPONSE_BODY_LOG_BYTES = 64 * 1024L // 64KB
        private const val MAX_REQUEST_BODY_LOG_BYTES = 64 * 1024L // 64KB
        // Matches common API key patterns (Bearer tokens, sk- prefixed keys)
        private val API_KEY_PATTERN = Regex(
            "(sk-[A-Za-z0-9_\\-]+|Bearer\\s[A-Za-z0-9_\\-]+)",
            RegexOption.IGNORE_CASE
        )
    }

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val startTime = System.currentTimeMillis()

        val requestHeaders = request.headers.toMap()
        // 聊天请求体常有 1MB 以上（带图更大），日志里最多保留 100 条，全文常驻会吃掉几百 MB。
        // 大请求只记大小和开头一段；不读取全文，也就不会多序列化一遍。
        val requestBody = request.body?.let { body ->
            val length = body.contentLength()
            if (length > MAX_REQUEST_BODY_LOG_BYTES || length < 0) {
                "[request body ${if (length < 0) "unknown size" else "${length / 1024} KB"}, omitted]"
            } else {
                val buffer = Buffer()
                body.writeTo(buffer)
                buffer.readUtf8()
            }
        }

        val response: Response
        var error: String? = null

        try {
            response = chain.proceed(request)
        } catch (e: Exception) {
            error = e.message
            Logging.logRequest(
                LogEntry.RequestLog(
                    tag = "HTTP",
                    url = request.url.toString(),
                    method = request.method,
                    requestHeaders = requestHeaders,
                    requestBody = requestBody,
                    error = error
                )
            )
            throw e
        }

        val durationMs = System.currentTimeMillis() - startTime
        val responseHeaders = response.headers.toMap()

        // Only log response body for error responses (non-2xx)
        // Successful SSE streaming responses are not logged to avoid huge log entries
        var responseBody: String? = null
        if (response.code !in 200..299) {
            try {
                val peekedBody = response.peekBody(MAX_RESPONSE_BODY_LOG_BYTES)
                val bodyString = peekedBody.string()
                responseBody = sanitizeResponseBody(bodyString)
            } catch (e: Exception) {
                responseBody = "[Failed to read response body: ${e.message}]"
            }
        }

        Logging.logRequest(
            LogEntry.RequestLog(
                tag = "HTTP",
                url = request.url.toString(),
                method = request.method,
                requestHeaders = requestHeaders,
                requestBody = requestBody,
                responseCode = response.code,
                responseHeaders = responseHeaders,
                responseBody = responseBody,
                durationMs = durationMs,
                error = error
            )
        )

        return response
    }

    private fun okhttp3.Headers.toMap(): Map<String, String> {
        return names().associateWith { get(it) ?: "" }
    }

    /**
     * Sanitize response body by masking potential API keys/tokens
     */
    private fun sanitizeResponseBody(body: String): String {
        if (body.length > MAX_RESPONSE_BODY_LOG_BYTES.toInt()) {
            return body.take(MAX_RESPONSE_BODY_LOG_BYTES.toInt()) + "...[truncated]"
        }
        // Mask common API key patterns
        return API_KEY_PATTERN.replace(body) { matchResult ->
            matchResult.value.take(10) + "***REDACTED***"
        }
    }
}
