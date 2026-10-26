package me.rerere.rikkahub.data.datastore

/** One current decoded value per known protected preference; never caches a failed read. */
internal class ProtectedSettingsDecodeCache {
    private class Entry(val raw: String, val value: Any?)
    private val entries = mutableMapOf<String, Entry>()

    @Synchronized
    fun <T> read(name: String, raw: String, decode: () -> T): T {
        val entry = entries[name]
        if (entry != null && entry.raw == raw) {
            @Suppress("UNCHECKED_CAST")
            return entry.value as T
        }
        // Invalidate before decoding so a failed/key-locked read cannot return stale settings.
        entries.remove(name)
        return decode().also { entries[name] = Entry(raw, it) }
    }

    @Synchronized
    fun forget(name: String) { entries.remove(name) }
}
