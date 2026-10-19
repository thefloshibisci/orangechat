package me.rerere.rikkahub.data.files

import java.io.File
import java.io.InputStream

/** Publish only a complete nonempty copy. A failed read never leaves a visible 0B attachment. */
internal fun copyAttachmentAtomically(target: File, openSource: () -> InputStream?) {
    val temporary = File.createTempFile(".import-", ".part", target.parentFile)
    try {
        val input = requireNotNull(openSource()) { "无法读取附件" }
        input.use { source -> temporary.outputStream().use { source.copyTo(it) } }
        require(temporary.length() > 0) { "附件为空" }
        check(temporary.renameTo(target)) { "无法保存附件" }
    } finally {
        temporary.delete()
    }
}
