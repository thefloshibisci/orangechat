package me.rerere.ai.provider.providers.google

// Bounded service diagnostics; never include the key or full echoed conversation.
internal fun redactInteractionError(value: String?, apiKey: String = "", maxLength: Int = 2_000): String {
        return value.orEmpty().let { if (apiKey.isBlank()) it else it.replace(apiKey, "<redacted>") }
            .replace(Regex("(?i)bearer\\s+[^\\s,}]+"), "Bearer <redacted>")
            .replace(
                Regex("(?i)(api[_-]?key|authorization|access[_-]?token)([\\\"']?\\s*[:=]\\s*[\\\"']?)[^\\s,}\\\"']+"),
                "\$1\$2<redacted>",
            )
            .replace(Regex("(?i)\\bsk-[a-z0-9_-]{8,}"), "sk-<redacted>")
            .replace(
                Regex(
                    "(?is)(\\\"(?:messages|prompt|input|content|reasoning_content)\\\"\\s*:\\s*)" +
                        "\\\"(?:\\\\.|[^\\\"])*\\\"",
                ),
                "\$1\"<redacted>\"",
            )
            .replace(
                Regex(
                    "(?is)(\\\"(?:messages|input)\\\"\\s*:\\s*)\\[[^]]*]",
                ),
                "\$1[<redacted>]",
            )
            .replace(Regex("AIza[0-9A-Za-z_-]{20,}"), "<redacted>")
            .take(maxLength)
    }
