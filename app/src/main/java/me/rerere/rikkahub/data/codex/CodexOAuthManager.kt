// PKCE parameters from ExTV/rikkahub-agent; device flow from openai/codex login/src/device_code_auth.rs.
package me.rerere.rikkahub.data.codex

import android.content.Context
import android.content.Intent
import android.net.Uri
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.*
import me.rerere.common.http.await
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

class CodexOAuthManager(
    private val context: Context,
    private val scope: CoroutineScope,
    private val client: OkHttpClient,
    private val repository: CodexAccountRepository,
    private val json: Json,
) {
    private val mutableStatus = MutableStateFlow<CodexOAuthStatus>(CodexOAuthStatus.Idle)
    val status = mutableStatus.asStateFlow()
    private var job: Job? = null

    fun startLogin(device: Boolean = true) {
        if (job?.isActive == true) return
        mutableStatus.value = CodexOAuthStatus.Waiting()
        job = scope.launch(Dispatchers.IO) {
            try {
                withTimeout(15 * 60_000L) {
                    val account = if (device) deviceLogin() else browserLogin()
                    mutableStatus.value = CodexOAuthStatus.Success(account.id)
                }
            } catch (_: TimeoutCancellationException) {
                mutableStatus.value = CodexOAuthStatus.Error("登录已超时，请重试")
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                mutableStatus.value = CodexOAuthStatus.Error("登录未完成；请检查网络，或开启 ChatGPT 安全设置中的设备码登录后重试")
            }
        }
    }

    fun cancel() {
        job?.cancel()
        mutableStatus.value = CodexOAuthStatus.Idle
    }

    fun consumeResult() { mutableStatus.value = CodexOAuthStatus.Idle }

    fun openDevicePage() = openBrowser(DEVICE_PAGE)

    private fun openBrowser(url: String) {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private suspend fun postJson(url: String, body: JsonObject): Pair<Int, String> = client.newCall(
        Request.Builder().url(url).post(body.toString().toRequestBody("application/json".toMediaType())).build()
    ).await().use { it.code to it.body.string() }

    private suspend fun deviceLogin(): CodexAccount {
        val (statusCode, body) = postJson("$DEVICE_API/usercode", buildJsonObject { put("client_id", CLIENT_ID) })
        check(statusCode in 200..299)
        val device = json.parseToJsonElement(body).jsonObject
        val authId = device.getValue("device_auth_id").jsonPrimitive.content
        val userCode = (device["user_code"] ?: device.getValue("usercode")).jsonPrimitive.content
        val interval = (device["interval"]?.jsonPrimitive?.content?.toLongOrNull() ?: 5L).coerceIn(1, 60)
        mutableStatus.value = CodexOAuthStatus.Waiting(userCode)
        // Show the code before opening the browser; the user explicitly opens it from the settings card.
        while (currentCoroutineContext().isActive) {
            delay(interval * 1000)
            val (code, result) = postJson("$DEVICE_API/token", buildJsonObject {
                put("device_auth_id", authId)
                put("user_code", userCode)
            })
            if (code == 403 || code == 404) continue
            check(code in 200..299)
            val token = json.parseToJsonElement(result).jsonObject
            return exchangeCode(token.getValue("authorization_code").jsonPrimitive.content,
                token.getValue("code_verifier").jsonPrimitive.content, "https://auth.openai.com/deviceauth/callback")
        }
        throw CancellationException()
    }

    private suspend fun browserLogin(): CodexAccount {
        val state = randomUrlSafe(32)
        val verifier = randomUrlSafe(64)
        val challenge = Base64.getUrlEncoder().withoutPadding().encodeToString(
            MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray()))
        val callback = CompletableDeferred<String>()
        val redirect = "http://localhost:1455/auth/callback"
        val server = embeddedServer(CIO, host = "127.0.0.1", port = 1455) {
            routing {
                get("/auth/callback") {
                    val query = call.request.queryParameters
                    if (query["state"] != state) {
                        call.respondText("Invalid OAuth state", status = HttpStatusCode.BadRequest)
                    } else if (query["error"] != null || query["code"].isNullOrBlank()) {
                        call.respondText("Sign-in failed. Return to OrangeChat.")
                        callback.completeExceptionally(IllegalStateException("OAuth authorization declined"))
                    } else {
                        call.respondText("<html><meta name=viewport content='width=device-width'><p>授权已收到，请返回橘瓣查看登录结果。</p></html>", ContentType.Text.Html)
                        callback.complete(query["code"]!!)
                    }
                }
            }
        }
        try {
            server.start(wait = false)
            val uri = Uri.parse(AUTHORIZE_URL).buildUpon()
                .appendQueryParameter("response_type", "code")
                .appendQueryParameter("client_id", CLIENT_ID)
                .appendQueryParameter("redirect_uri", redirect)
                .appendQueryParameter("scope", "openid profile email offline_access")
                .appendQueryParameter("state", state)
                .appendQueryParameter("code_challenge", challenge)
                .appendQueryParameter("code_challenge_method", "S256")
                .appendQueryParameter("id_token_add_organizations", "true")
                .appendQueryParameter("codex_cli_simplified_flow", "true")
                .appendQueryParameter("originator", "codex_cli_rs").build()
            openBrowser(uri.toString())
            return exchangeCode(callback.await(), verifier, redirect)
        } finally {
            server.stop(0, 500)
        }
    }

    private suspend fun exchangeCode(code: String, verifier: String, redirect: String): CodexAccount {
        val body = client.newCall(Request.Builder().url(TOKEN_URL).post(FormBody.Builder()
            .add("grant_type", "authorization_code").add("client_id", CLIENT_ID)
            .add("code", code).add("code_verifier", verifier).add("redirect_uri", redirect).build()).build())
            .await().use { response ->
                check(response.isSuccessful) { "Token exchange failed" }
                response.body.string()
            }
        currentCoroutineContext().ensureActive()
        return repository.saveLogin(body)
    }

    private fun randomUrlSafe(size: Int): String = Base64.getUrlEncoder().withoutPadding()
        .encodeToString(ByteArray(size).also { SecureRandom().nextBytes(it) })

    companion object {
        const val CLIENT_ID = "app_EMoamEEZ73f0CkXaXp7hrann"
        const val TOKEN_URL = "https://auth.openai.com/oauth/token"
        const val AUTHORIZE_URL = "https://auth.openai.com/oauth/authorize"
        const val REFRESH_SCOPES = "openid profile email"
        private const val DEVICE_API = "https://auth.openai.com/api/accounts/deviceauth"
        private const val DEVICE_PAGE = "https://auth.openai.com/codex/device"
    }
}

sealed interface CodexOAuthStatus {
    data object Idle : CodexOAuthStatus
    data class Waiting(val userCode: String? = null) : CodexOAuthStatus
    data class Success(val accountId: String) : CodexOAuthStatus
    data class Error(val message: String) : CodexOAuthStatus
}
