package me.rerere.workspace

/** Tool paths are POSIX paths on the selected machine, never host/Windows paths. */
object WorkspacePathPolicy {
    fun normalize(path: String): String {
        require(path.startsWith('/') && '\u0000' !in path && '\\' !in path) { "Expected an absolute POSIX path" }
        val segments = ArrayDeque<String>()
        path.split('/').forEach {
            when (it) {
                "", "." -> Unit
                ".." -> { require(segments.isNotEmpty()) { "Path escapes root" }; segments.removeLast() }
                else -> segments.addLast(it)
            }
        }
        return "/" + segments.joinToString("/")
    }

    fun contains(root: String, path: String): Boolean {
        val r = normalize(root)
        val p = normalize(path)
        return r == "/" || p == r || p.startsWith("$r/")
    }

    fun requireInside(root: String, path: String): String = normalize(path).also {
        require(contains(root, it)) { "Path outside workspace root $root: $path" }
    }

    fun cwd(root: String, path: String): String {
        val absolute = requireInside(root, if (path.startsWith('/')) path else "${root.trimEnd('/')}/$path")
        return absolute.removePrefix(normalize(root)).trimStart('/')
    }
}
