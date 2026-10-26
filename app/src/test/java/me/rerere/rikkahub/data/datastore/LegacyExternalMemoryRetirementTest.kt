package me.rerere.rikkahub.data.datastore

import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.serialization.json.Json
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.ExternalMemory
import me.rerere.rikkahub.data.serein.SereinHookConfig
import org.junit.Assert.*
import org.junit.Test

class LegacyExternalMemoryRetirementTest {
    private val oldKey = stringPreferencesKey("external_memories")
    private val assistantsKey = stringPreferencesKey("assistants")

    @Test fun `old backup loses only built in Supabase credentials and assistant bindings`() {
        val old = ExternalMemory(supabaseUrl = "https://synthetic.invalid", supabaseKey = "synthetic")
        val assistant = Assistant(name = "kept", externalMemoryIds = setOf(old.id), saveLocalRawMessages = true)
        val hook = SereinHookConfig(baseUrl = "https://memory.example", gatewayKey = "synthetic-hook")
        val before = Settings(assistants = listOf(assistant), externalMemories = listOf(old), sereinHook = hook,
            compressPrompt = "custom retained", enableWebSearch = true)
        val expected = before.copy(externalMemories = emptyList(), assistants = listOf(assistant.copy(externalMemoryIds = emptySet())))
        assertEquals(expected, before.withoutLegacyExternalMemories())
        assertEquals(expected, expected.withoutLegacyExternalMemories())
        assertEquals(listOf(old), before.externalMemories)
    }

    @Test fun `preference cleanup removes plaintext key and preserves unknown assistant fields`() {
        val unrelated = stringPreferencesKey("serein_hook")
        val preferences = mutablePreferencesOf(
            oldKey to "synthetic-url-and-key",
            assistantsKey to """[{"name":"kept","externalMemoryIds":["old"],"futureField":{"x":1}}]""",
            unrelated to "untouched-hook",
        )
        var encryptionCalls = 0
        val encrypt: (String, String) -> String? = { plain, field ->
            assertEquals("assistants", field); encryptionCalls++; "protected:$plain"
        }
        val decrypt: (String, String) -> String? = { raw, _ -> raw.removePrefix("protected:") }
        retireLegacyExternalMemoryPreferences(preferences, decrypt, encrypt)
        assertFalse(preferences.contains(oldKey))
        assertEquals("untouched-hook", preferences[unrelated])
        assertEquals(Json.parseToJsonElement("""[{"name":"kept","futureField":{"x":1}}]"""),
            Json.parseToJsonElement(preferences[assistantsKey]!!.removePrefix("protected:")))
        retireLegacyExternalMemoryPreferences(preferences, decrypt, encrypt)
        assertEquals(1, encryptionCalls)
        // An old restored preference is removed again, not protected by a one-time marker.
        preferences[oldKey] = "restored-old-key"
        retireLegacyExternalMemoryPreferences(preferences, decrypt, encrypt)
        assertFalse(preferences.contains(oldKey))
    }

    @Test fun `unreadable assistant ciphertext is preserved while retired credentials are erased`() {
        val preferences = mutablePreferencesOf(oldKey to "encrypted-legacy", assistantsKey to "unreadable-assistant")
        retireLegacyExternalMemoryPreferences(preferences, { _, _ -> null }, { _, _ -> error("must not encrypt") })
        assertFalse(preferences.contains(oldKey))
        assertEquals("unreadable-assistant", preferences[assistantsKey])
    }

    @Test fun `encryption failure never destroys other assistant settings`() {
        val original = """[{"name":"kept","externalMemoryIds":["old"]}]"""
        val preferences = mutablePreferencesOf(oldKey to "legacy-key", assistantsKey to original)
        retireLegacyExternalMemoryPreferences(preferences, { raw, _ -> raw }, { _, _ -> throw IllegalStateException("keystore unavailable") })
        assertFalse(preferences.contains(oldKey))
        assertEquals(original, preferences[assistantsKey])
    }
}
