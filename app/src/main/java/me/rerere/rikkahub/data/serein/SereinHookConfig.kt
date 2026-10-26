package me.rerere.rikkahub.data.serein

import kotlinx.serialization.Serializable
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.security.MessageDigest

@Serializable
data class SereinHookConfig(
    val enabled: Boolean = false,
    val assistantId: String = "",
    val baseUrl: String = "",
    val gatewayKey: String = "",
    val timeoutMs: Long = 3000,
    val generation: String = "",
    val uploadEnabled: Boolean = false,
    val uploadStartedAt: String = "",
) {
    val waitMs: Long get() = timeoutMs.coerceIn(2000, 5000)
    fun rootUrl(): String? = baseUrl.trim().toHttpUrlOrNull()?.takeIf {
        it.isHttps && it.username.isEmpty() && it.password.isEmpty() && it.query == null && it.fragment == null &&
            it.encodedPath == "/"
    }?.toString()?.trimEnd('/')
    fun usableBy(owner: String) = enabled && assistantId == owner && rootUrl() != null && gatewayKey.isNotBlank()
    fun uploadsFor(owner: String) = uploadEnabled && owner.isNotBlank() && assistantId == owner && rootUrl() != null &&
        gatewayKey.isNotBlank() && uploadStartedAt.isNotBlank()
    // Key rotation and recall timeout changes must not reroute or duplicate originals.
    fun uploadDestination() = hookHash("$assistantId\u0000${rootUrl()}")
    fun fingerprint(): String = hookHash("$assistantId\u0000${rootUrl()}\u0000$gatewayKey\u0000$generation\u0000$waitMs")
    override fun toString() = "SereinHookConfig(enabled=$enabled, timeoutMs=$waitMs, credentials=redacted)"
}

internal fun hookHash(text: String): String = MessageDigest.getInstance("SHA-256")
    .digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it.toInt() and 255) }
