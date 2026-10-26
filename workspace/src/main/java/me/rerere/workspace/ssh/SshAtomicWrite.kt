package me.rerere.workspace.ssh

import java.util.UUID

/** Never truncate the destination until the complete new content has reached a sibling file. */
internal fun publishRemoteTextAtomically(
    destination: String,
    bytes: ByteArray,
    upload: (temporary: String, content: ByteArray) -> Unit,
    publish: (temporary: String, destination: String) -> Unit,
    removeTemporary: (temporary: String) -> Unit,
) {
    val parent = destination.substringBeforeLast('/').ifEmpty { "/" }
    val temporary = parent.trimEnd('/') + "/.orangechat-write-${UUID.randomUUID()}.tmp"
    var published = false
    var interrupted = false
    try {
        upload(temporary, bytes)
        publish(temporary, destination)
        published = true
    } catch (error: Throwable) {
        interrupted = error.isInterruption() || Thread.currentThread().isInterrupted
        throw error
    } finally {
        // A cancelled SFTP request leaves unread replies. Its connection owner will discard the channel.
        // Keep a possible partial temp file rather than issuing another request on that damaged channel.
        if (!published && !interrupted) runCatching { removeTemporary(temporary) }
    }
}
