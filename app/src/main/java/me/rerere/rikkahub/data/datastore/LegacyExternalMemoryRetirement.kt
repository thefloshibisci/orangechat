package me.rerere.rikkahub.data.datastore

import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

/** Retire the built-in Supabase connection only. Never contacts or deletes cloud data. */
internal fun Settings.withoutLegacyExternalMemories(): Settings {
    if (externalMemories.isEmpty() && assistants.all { it.externalMemoryIds.isEmpty() }) return this
    return copy(
    externalMemories = emptyList(),
    assistants = assistants.map { assistant ->
        if (assistant.externalMemoryIds.isEmpty()) assistant
        else assistant.copy(externalMemoryIds = emptySet())
    },
    )
}

/** One exact-value cache per store. Restored or edited ciphertext must be inspected again. */
internal class LegacyExternalMemoryRetirement {
    private var verifiedAssistants: String? = null

    fun retire(
        preferences: MutablePreferences,
        decrypt: (String, String) -> String?,
        encrypt: (String, String) -> String?,
    ) {
        preferences.remove(stringPreferencesKey("external_memories"))
        val stored = preferences[stringPreferencesKey("assistants")]
        if (stored == null) {
            verifiedAssistants = null
            return
        }
        if (stored == verifiedAssistants) return
        verifiedAssistants = retireLegacyExternalMemoryPreferences(preferences, decrypt, encrypt)
    }
}

/** Also applied at startup and every write so restoring an old backup cannot revive credentials. */
internal fun retireLegacyExternalMemoryPreferences(
    preferences: MutablePreferences,
    decrypt: (String, String) -> String?,
    encrypt: (String, String) -> String?,
): String? {
    preferences.remove(stringPreferencesKey("external_memories"))
    val key = stringPreferencesKey("assistants")
    val stored = preferences[key] ?: return null
    // Preserve the complete protected assistant record if it cannot be read or re-encrypted.
    // The settings read path still strips retired references before exposing it to callers.
    return runCatching {
        val plain = decrypt(stored, key.name) ?: return null
        val array = Json.parseToJsonElement(plain) as? JsonArray ?: return null
        // The ordinary serializer writes defaults, including empty bindings. They are already
        // retired; rewriting them would encrypt the whole assistant list twice on every edit.
        if (array.none { it is JsonObject && it["externalMemoryIds"]?.let { ids ->
                ids !is JsonArray || ids.isNotEmpty()
            } == true }) return stored
        val cleaned = JsonArray(array.map { entry ->
            if (entry is JsonObject) JsonObject(entry.filterKeys { it != "externalMemoryIds" }) else entry
        })
        encrypt(cleaned.toString(), key.name)?.takeIf { it.isNotEmpty() }?.also { preferences[key] = it }
    }.getOrNull()
}
