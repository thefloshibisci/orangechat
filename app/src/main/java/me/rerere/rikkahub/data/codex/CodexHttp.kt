package me.rerere.rikkahub.data.codex

import kotlinx.coroutines.CancellationException
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import java.io.IOException
import java.util.concurrent.TimeUnit

internal fun isCodexOfficialTarget(url: HttpUrl): Boolean =
    url.scheme == "https" && url.port == 443 && url.username.isEmpty() && url.password.isEmpty() &&
        when (url.host) {
            "auth.openai.com" -> url.encodedPath in setOf(
                "/oauth/token", "/api/accounts/deviceauth/usercode", "/api/accounts/deviceauth/token",
            )
            "chatgpt.com" -> url.encodedPath in setOf(
                "/backend-api/codex/models", "/backend-api/codex/responses", "/backend-api/wham/usage",
            )
            else -> false
        }

/** Deliberately constructed from scratch: no shared loggers, interceptors or custom endpoints. */
internal fun codexHttpClient(): OkHttpClient = OkHttpClient.Builder()
    .followRedirects(false)
    .followSslRedirects(false)
    .retryOnConnectionFailure(false)
    .connectTimeout(30, TimeUnit.SECONDS)
    .readTimeout(10, TimeUnit.MINUTES)
    .writeTimeout(120, TimeUnit.SECONDS)
    .addInterceptor { chain ->
        if (!isCodexOfficialTarget(chain.request().url)) throw IOException("Blocked non-official Codex target")
        chain.proceed(chain.request())
    }
    .build()

internal suspend fun <T> codexSafe(block: suspend () -> T): T = try {
    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { block() }
} catch (error: CancellationException) {
    throw error
} catch (_: Exception) {
    // No raw HTTP body, JSON decoder input, token, URL query or nested cause escapes.
    throw IllegalStateException("Codex 请求失败；请检查网络、登录状态及订阅额度")
}
