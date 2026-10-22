package me.rerere.rikkahub.web

import android.app.Application
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.auth.authenticate
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicReference

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class WebAccessAuthTest {
    @Test fun `existing routes follow auth toggles password changes and missing password`() = runBlocking {
        val policy = AtomicReference(WebAccessPolicy(false, "first-password"))
        val server = embeddedServer(CIO, port = 0, host = "127.0.0.1") {
            install(ContentNegotiation) { json() }
            installWebAuthentication(policy::get)
            routing {
                authenticate("auth-jwt") { get("/protected") { call.respondText("private") } }
                get("/public") { call.respondText("public") }
            }
        }.start(wait = false)
        try {
            val port = server.engine.resolvedConnectors().first().port
            fun status(path: String = "/protected", token: String? = null): Int {
                val connection = URL("http://127.0.0.1:$port$path").openConnection() as HttpURLConnection
                connection.connectTimeout = 5000
                connection.readTimeout = 5000
                token?.let { connection.setRequestProperty("Authorization", "Bearer $it") }
                return try { connection.responseCode } finally { connection.disconnect() }
            }
            assertEquals(200, status())
            policy.set(WebAccessPolicy(true, "first-password"))
            assertEquals(401, status())
            assertEquals(401, status(token = "malformed"))
            val firstToken = createWebJwt("first-password").first
            assertEquals(200, status(token = firstToken))
            assertEquals(200, status("/protected?access_token=$firstToken"))
            assertEquals(200, status("/public"))
            policy.set(WebAccessPolicy(true, "second-password"))
            assertEquals(401, status(token = firstToken))
            assertEquals(200, status(token = createWebJwt("second-password").first))
            policy.set(WebAccessPolicy(true, ""))
            assertEquals(403, status())
            assertEquals(403, status(token = firstToken))
            policy.set(WebAccessPolicy(false, ""))
            assertEquals(200, status())
            assertEquals(200, status(token = "malformed"))
        } finally {
            server.stop(0, 1000)
        }
    }
}
