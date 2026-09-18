// Adapted from ExTV/rikkahub-agent 51cb7e1 (AGPL-3.0).
package me.rerere.rikkahub.data.codex

import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import me.rerere.ai.provider.ImageGenerationParams
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.Provider
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.ui.MessageChunk
import me.rerere.ai.ui.ImageGenerationResult
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.CancellationException
import me.rerere.ai.provider.providers.openai.CodexResponseAPI
import me.rerere.ai.ui.UIMessage
import me.rerere.common.http.await
import me.rerere.rikkahub.AppScope
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.ResponseBody.Companion.asResponseBody

class CodexProvider(
    private val client: OkHttpClient,
    private val repository: CodexAccountRepository,
    private val json: Json,
    private val scope: AppScope,
) : Provider<ProviderSetting.Codex> {

    override suspend fun listModels(providerSetting: ProviderSetting.Codex): List<Model> =
        codexSafe { withContext(Dispatchers.IO) {
            val account = repository.acquireAccount()
            val request = Request.Builder()
                .url("$CODEX_API_BASE/models?client_version=$CLIENT_VERSION")
                .codexHeaders(account)
                .get()
                .build()
            val response = client.newBuilder().authenticator(CodexAuthenticator(repository, account.id))
                .build().newCall(request).await()
            if (!response.isSuccessful) {
                response.close()
                error("Failed to get Codex models: ${response.code}")
            }
            parseCodexModels(json.parseToJsonElement(response.body.string()))
        }}

    override suspend fun generateText(providerSetting: ProviderSetting.Codex, messages: List<UIMessage>, params: TextGenerationParams): MessageChunk = codexSafe {
        val account = repository.acquireAccount(params.conversationId)
        responseApiFor(account).generateText(account.accessToken, account.chatgptAccountId, messages, params)
    }

    override suspend fun streamText(providerSetting: ProviderSetting.Codex, messages: List<UIMessage>, params: TextGenerationParams): Flow<MessageChunk> = codexSafe {
        val account = repository.acquireAccount(params.conversationId)
        responseApiFor(account).streamText(account.accessToken, account.chatgptAccountId, messages, params).catch { error ->
            if (error is CancellationException) throw error
            throw IllegalStateException("Codex 回复中断；请检查网络、登录状态和订阅额度")
        }
    }

    override suspend fun generateImage(
        providerSetting: ProviderSetting,
        params: ImageGenerationParams,
    ): ImageGenerationResult {
        error("Image generation is not supported by the Codex provider")
    }

    private fun Request.Builder.codexHeaders(account: CodexAccount): Request.Builder {
        return header("Authorization", "Bearer ${account.accessToken}")
            .header("ChatGPT-Account-Id", account.chatgptAccountId)
            .header("OpenAI-Beta", "responses=experimental")
            .header("originator", "codex_cli_rs")
            .header("User-Agent", CODEX_USER_AGENT)
    }

    /**
     * Wraps [client] with an account-scoped interceptor so the same response that carries the
     * model reply also carries the account's rate-limit headers (quota tracking).
     * Authentication recovery is handled before the SSE handshake. Also patches a missing
     * Content-Type so OkHttp's SSE factory recognizes the stream - some Codex backend responses
     * omit it.
     */
    private fun responseApiFor(account: CodexAccount): CodexResponseAPI {
        val accountAwareClient = client.newBuilder()
            .authenticator(CodexAuthenticator(repository, account.id))
            .addNetworkInterceptor { chain ->
                if (!repository.isSignedIn(account.id)) throw java.io.IOException("Codex account signed out")
                val response = chain.proceed(chain.request())
                parseCodexUsage(response.headers)?.let { usage ->
                    scope.launch { codexBackground { repository.updateUsage(account.id, usage) } }
                }
                if (response.isSuccessful && response.header("Content-Type") == null) {
                    val body = response.body
                    response.newBuilder()
                        .header("Content-Type", "text/event-stream")
                        .body(
                            body.source().asResponseBody(
                                contentType = "text/event-stream".toMediaType(),
                                contentLength = body.contentLength(),
                            )
                        )
                        .build()
                } else {
                    response
                }
            }
            .build()
        return CodexResponseAPI(accountAwareClient)
    }

    private companion object {
        const val CODEX_API_BASE = "${CodexAccountRepository.CODEX_BASE_URL}/codex"
        const val CLIENT_VERSION = CodexResponseAPI.CLIENT_VERSION

        // The Codex backend routes newer models (e.g. gpt-5.6-luna, which is gated on
        // minimal_client_version 0.144.0) by the codex version advertised in the User-Agent, not
        // just the `client_version` query param on /models. Without a codex-shaped UA the backend
        // resolves the public slug to an unavailable internal engine and returns 404 "Model not
        // found". Mirror the codex CLI's UA format: "<originator>/<version> (<os>; <arch>)".
        val CODEX_USER_AGENT =
            "codex_cli_rs/$CLIENT_VERSION (Android ${Build.VERSION.RELEASE}; " +
                "${Build.SUPPORTED_ABIS.firstOrNull() ?: "arm64"})"
        const val DEFAULT_INSTRUCTIONS = "You are a helpful assistant."
    }
}

private suspend fun codexBackground(block: suspend () -> Unit) {
    try { block() } catch (e: CancellationException) { throw e } catch (_: Exception) { }
}
