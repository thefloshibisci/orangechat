package me.rerere.rikkahub.data.datastore

import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import org.junit.Assert.*
import org.junit.Test

class SettingsHotPathTest {
    @Test fun `unchanged protected fields decode once across repeated settings reads`() {
        val cache = ProtectedSettingsDecodeCache()
        var decodes = 0
        val value = List(100) { "synthetic assistant $it" }
        repeat(100) {
            assertSame(value, cache.read("assistants", "same-ciphertext") { decodes++; value })
        }
        assertEquals(1, decodes)
        println("unchanged protected field: 100 reads, $decodes decode")
    }

    @Test fun `edited restored and deleted values invalidate only their own cache entry`() {
        val cache = ProtectedSettingsDecodeCache()
        var decodes = 0
        fun read(raw: String) = cache.read("assistants", raw) { decodes++; raw }
        assertEquals("old", read("old"))
        assertEquals("new", read("new"))
        assertEquals("old", read("old"))
        cache.read("serein_hook", "key") { "hook" }
        assertEquals("old", read("old"))
        cache.forget("assistants")
        assertEquals("old", read("old"))
        assertEquals(4, decodes)
        assertEquals("hook", cache.read<String>("serein_hook", "key") { error("unrelated field was invalidated") })
    }

    @Test fun `failed decode retries instead of caching defaults or returning stale settings`() {
        val cache = ProtectedSettingsDecodeCache()
        cache.read("assistants", "old") { "old value" }
        repeat(2) {
            assertTrue(runCatching {
                cache.read("assistants", "locked") { error("synthetic unavailable key") }
            }.isFailure)
        }
        assertEquals("recovered", cache.read("assistants", "locked") { "recovered" })
    }

    @Test fun `retirement skips unchanged blob but cleans a restored legacy connection`() {
        val key = stringPreferencesKey("assistants")
        val credentials = stringPreferencesKey("external_memories")
        val clean = """[{"name":"synthetic","externalMemoryIds":[]}]"""
        val prefs = mutablePreferencesOf(key to clean)
        val retirement = LegacyExternalMemoryRetirement()
        var decrypts = 0
        var encrypts = 0
        val decrypt: (String, String) -> String? = { raw, _ -> decrypts++; raw.removePrefix("enc:") }
        val encrypt: (String, String) -> String? = { raw, _ -> encrypts++; "enc:$raw" }
        repeat(100) { retirement.retire(prefs, decrypt, encrypt) }
        assertEquals(1, decrypts)
        assertEquals(0, encrypts)
        assertEquals(clean, prefs[key])
        prefs[credentials] = "restored synthetic key"
        retirement.retire(prefs, decrypt, encrypt)
        assertFalse(prefs.contains(credentials))
        assertEquals(1, decrypts)
        prefs[key] = """[{"name":"synthetic","externalMemoryIds":["legacy"]}]"""
        retirement.retire(prefs, decrypt, encrypt)
        assertFalse(prefs[key]!!.contains("legacy"))
        repeat(100) { retirement.retire(prefs, decrypt, encrypt) }
        assertEquals(2, decrypts)
        assertEquals(1, encrypts)
        println("unchanged retirement: 100 checks, 1 inspection, 0 encryption; restored legacy: cleaned once")
    }

    @Test fun `unreadable retirement retries after key recovery`() {
        val key = stringPreferencesKey("assistants")
        val original = """[{"externalMemoryIds":["legacy"]}]"""
        val prefs = mutablePreferencesOf(key to original)
        val retirement = LegacyExternalMemoryRetirement()
        retirement.retire(prefs, { _, _ -> null }, { _, _ -> error("must not encrypt") })
        assertEquals(original, prefs[key])
        retirement.retire(prefs, { raw, _ -> raw }, { raw, _ -> raw })
        assertEquals("[{}]", prefs[key])
    }
}
