package me.rerere.rikkahub.ui.components.richtext

/** Memory-only LRU: scrolling a completed message back into view does not parse its text again. */
internal class MarkdownParseCache<T : Any>(
    private val maxEntries: Int = 128,
    private val maxSourceChars: Int = 512 * 1024,
) {
    private val lock = Any()
    private val entries = LinkedHashMap<String, T>(16, 0.75f, true)
    private var sourceChars = 0

    init { require(maxEntries > 0 && maxSourceChars > 0) }

    fun getOrParse(source: String, parse: (String) -> T): T {
        synchronized(lock) { entries[source]?.let { return it } }
        // Parsing must not hold the shared lock: a large background parse must not block cached UI reads.
        val parsed = parse(source)
        if (source.length > maxSourceChars) return parsed
        return synchronized(lock) {
            entries[source]?.let { return@synchronized it }
            entries[source] = parsed
            sourceChars += source.length
            val oldest = entries.entries.iterator()
            while (entries.size > maxEntries || sourceChars > maxSourceChars) {
                sourceChars -= oldest.next().key.length
                oldest.remove()
            }
            parsed
        }
    }
}
