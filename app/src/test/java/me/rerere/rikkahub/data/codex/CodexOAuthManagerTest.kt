package me.rerere.rikkahub.data.codex

import android.app.Application
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import me.rerere.ai.provider.providers.openai.CodexResponseAPI
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.net.SocketTimeoutException
import java.util.Base64
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28], manifest = Config.NONE)
class CodexOAuthManagerTest {
    private val json = Json { ignoreUnknownKeys = true }

    private class MemoryStore(var state: CodexAccountState = CodexAccountState()) : CodexAccountStore {
        override fun read(): CodexAccountState = state
        override fun write(state: CodexAccountState) { this.state = state }
    }

    private fun fakeIdToken(): String {
        val payload = """
            {
              "sub": "user-1",
              "email": "user@example.com",
              "name": "Test User",
              "https://api.openai.com/auth": {
                "chatgpt_account_id": "account-1",
                "chatgpt_user_id": "user-1"
              }
            }
        """.trimIndent()
        return listOf("{}", payload, "sig")
            .joinToString(".") { Base64.getUrlEncoder().withoutPadding().encodeToString(it.encodeToByteArray()) }
    }

    private suspend fun awaitTerminalStatus(oauth: CodexOAuthManager): CodexOAuthStatus =
        withTimeout(15_000L) {
            oauth.status.first { it is CodexOAuthStatus.Success || it is CodexOAuthStatus.Error }
        }

    @Test
    fun `deviceLogin sends official headers clean json content-type and handles 404 and 429`() = runBlocking {
        val pollAttempt = AtomicInteger(0)
        val seenPaths = mutableListOf<String>()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val req = chain.request()
            seenPaths += req.url.encodedPath
            assertEquals(CodexResponseAPI.USER_AGENT, req.header("User-Agent"))
            assertEquals("application/json", req.header("Accept"))
            assertEquals("codex_cli_rs", req.header("originator"))
            when (req.url.encodedPath) {
                "/api/accounts/deviceauth/usercode" -> {
                    val ct = req.body?.contentType()?.toString()
                    assertEquals("application/json", ct)
                    Response.Builder().request(req).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                        .body("""{"device_auth_id":"da_1","user_code":"CODE-123","interval":"1"}""".toResponseBody("application/json".toMediaType()))
                        .build()
                }
                "/api/accounts/deviceauth/token" -> {
                    val ct = req.body?.contentType()?.toString()
                    assertEquals("application/json", ct)
                    when (pollAttempt.incrementAndGet()) {
                        1 -> Response.Builder().request(req).protocol(Protocol.HTTP_1_1).code(404).message("Not Found")
                            .body("""{"error":{"message":"pending"}}""".toResponseBody("application/json".toMediaType()))
                            .build()
                        2 -> Response.Builder().request(req).protocol(Protocol.HTTP_1_1).code(429).message("Too Many Requests")
                            .body("""{"error":{"message":"slow down"}}""".toResponseBody("application/json".toMediaType()))
                            .build()
                        else -> Response.Builder().request(req).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                            .body("""{"authorization_code":"auth_code_1","code_verifier":"verifier_1"}""".toResponseBody("application/json".toMediaType()))
                            .build()
                    }
                }
                "/oauth/token" -> {
                    val tokenBody = """{"id_token":"${fakeIdToken()}","access_token":"acc_1","refresh_token":"rt_1","expires_in":3600}"""
                    Response.Builder().request(req).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                        .body(tokenBody.toResponseBody("application/json".toMediaType()))
                        .build()
                }
                else -> error("Unexpected path: ${req.url.encodedPath}")
            }
        }.build()

        val store = MemoryStore()
        val repo = CodexAccountRepository(store, client, json)
        val scope = CoroutineScope(SupervisorJob())
        try {
            val oauth = CodexOAuthManager(RuntimeEnvironment.getApplication(), scope, client, repo, json)
            oauth.startLogin(device = true)
            val status = awaitTerminalStatus(oauth)
            assertTrue("Expected Success, got $status", status is CodexOAuthStatus.Success)
            assertEquals(1, repo.accounts.value.size)
            assertEquals("acc_1", repo.accounts.value.single().accessToken)
            assertEquals(
                listOf(
                    "/api/accounts/deviceauth/usercode",
                    "/api/accounts/deviceauth/token",
                    "/api/accounts/deviceauth/token",
                    "/api/accounts/deviceauth/token",
                    "/oauth/token",
                ),
                seenPaths,
            )
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `deviceLogin surfaces cloudflare 403 without leaking html or secrets`() = runBlocking {
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(403).message("Forbidden")
                .header("cf-mitigated", "challenge")
                .body("<!DOCTYPE html><html><title>Just a moment</title><body>Cloudflare Bearer secret-html</body></html>".toResponseBody("text/html".toMediaType()))
                .build()
        }.build()

        val repo = CodexAccountRepository(MemoryStore(), client, json)
        val scope = CoroutineScope(SupervisorJob())
        try {
            val oauth = CodexOAuthManager(RuntimeEnvironment.getApplication(), scope, client, repo, json)
            oauth.startLogin(device = true)
            val status = awaitTerminalStatus(oauth) as CodexOAuthStatus.Error
            assertTrue(status.message, status.message.contains("403") && status.message.contains("Cloudflare"))
            assertFalse(status.message, status.message.contains("secret-html") || status.message.contains("<!DOCTYPE"))
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `deviceLogin surfaces non-cloudflare 403 with device code guidance`() = runBlocking {
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(403).message("Forbidden")
                .body("""{"error":{"code":"device_auth_disabled","message":"Device code login is disabled"}}""".toResponseBody("application/json".toMediaType()))
                .build()
        }.build()

        val repo = CodexAccountRepository(MemoryStore(), client, json)
        val scope = CoroutineScope(SupervisorJob())
        try {
            val oauth = CodexOAuthManager(RuntimeEnvironment.getApplication(), scope, client, repo, json)
            oauth.startLogin(device = true)
            val status = awaitTerminalStatus(oauth) as CodexOAuthStatus.Error
            assertTrue(status.message, status.message.contains("403") && status.message.contains("设备码登录"))
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `exchangeCode surfaces oauth 400 error and redacts refresh token`() = runBlocking {
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val req = chain.request()
            when (req.url.encodedPath) {
                "/api/accounts/deviceauth/usercode" ->
                    Response.Builder().request(req).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                        .body("""{"device_auth_id":"da_1","user_code":"CODE-123","interval":"1"}""".toResponseBody("application/json".toMediaType()))
                        .build()
                "/api/accounts/deviceauth/token" ->
                    Response.Builder().request(req).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                        .body("""{"authorization_code":"auth_code_1","code_verifier":"verifier_1"}""".toResponseBody("application/json".toMediaType()))
                        .build()
                "/oauth/token" ->
                    Response.Builder().request(req).protocol(Protocol.HTTP_1_1).code(400).message("Bad Request")
                        .body("""{"error":"invalid_grant","error_description":"bad code rt_leak"}""".toResponseBody("application/json".toMediaType()))
                        .build()
                else -> error("Unexpected path: ${req.url.encodedPath}")
            }
        }.build()

        val repo = CodexAccountRepository(MemoryStore(), client, json)
        val scope = CoroutineScope(SupervisorJob())
        try {
            val oauth = CodexOAuthManager(RuntimeEnvironment.getApplication(), scope, client, repo, json)
            oauth.startLogin(device = true)
            val status = awaitTerminalStatus(oauth) as CodexOAuthStatus.Error
            assertTrue(status.message, status.message.contains("400") && status.message.contains("invalid_grant"))
            assertFalse(status.message, status.message.contains("rt_leak"))
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `deviceLogin surfaces sanitized network exception name without leaking host`() = runBlocking {
        val client = OkHttpClient.Builder().addInterceptor { _ ->
            throw SocketTimeoutException("failed to connect to auth.openai.com/198.51.100.3:443")
        }.build()

        val repo = CodexAccountRepository(MemoryStore(), client, json)
        val scope = CoroutineScope(SupervisorJob())
        try {
            val oauth = CodexOAuthManager(RuntimeEnvironment.getApplication(), scope, client, repo, json)
            oauth.startLogin(device = true)
            val status = awaitTerminalStatus(oauth) as CodexOAuthStatus.Error
            assertTrue(status.message, status.message.contains("SocketTimeoutException"))
            assertFalse(status.message, status.message.contains("auth.openai.com") || status.message.contains("198.51.100.3"))
        } finally {
            scope.cancel()
        }
    }
}
