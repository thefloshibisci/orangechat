package me.rerere.workspace.ssh

import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.io.InterruptedIOException

class SshAtomicWriteTest {
    @Test fun incompleteUploadNeverTruncatesOriginalAndOnlyItsOwnTempIsRemoved() {
        val files = linkedMapOf("/work/file" to "original", "/work/other" to "unrelated")
        var temporary: String? = null
        val error = runCatching {
            publishRemoteTextAtomically("/work/file", "replacement".toByteArray(),
                upload = { path, _ -> temporary = path; files[path] = "partial"; throw IOException("upload failed") },
                publish = { _, _ -> error("Incomplete content must not be published") },
                removeTemporary = { files.remove(it) },
            )
        }.exceptionOrNull()
        assertEquals("upload failed", error?.message)
        assertEquals(mapOf("/work/file" to "original", "/work/other" to "unrelated"), files)
        assertTrue(temporary!!.startsWith("/work/.orangechat-write-"))
    }

    @Test fun refusedRenameDoesNotDeleteOrOverwriteOriginal() {
        val files = linkedMapOf("/work/file" to "original")
        val error = runCatching {
            publishRemoteTextAtomically("/work/file", "new".toByteArray(),
                upload = { path, bytes -> files[path] = bytes.toString(Charsets.UTF_8) },
                publish = { _, _ -> throw IOException("Server does not support replacing an existing file") },
                removeTemporary = { files.remove(it) },
            )
        }.exceptionOrNull()
        assertNotNull(error)
        assertEquals(mapOf("/work/file" to "original"), files)
    }

    @Test fun successfulPublishUsesCompleteSiblingContentAndLeavesNoTemp() {
        val files = linkedMapOf("/work/file" to "old", "/work/other" to "keep")
        publishRemoteTextAtomically("/work/file", "中文 new content".toByteArray(),
            upload = { path, bytes -> files[path] = bytes.toString(Charsets.UTF_8) },
            publish = { temporary, target -> files[target] = files.remove(temporary)!! },
            removeTemporary = { error("Published temp has already been renamed") },
        )
        assertEquals(mapOf("/work/file" to "中文 new content", "/work/other" to "keep"), files)
    }

    @Test fun interruptionPreservesOriginalAndAvoidsMoreRequestsOnAbandonedChannel() {
        val files = linkedMapOf("/work/file" to "old")
        var cleanupRequests = 0
        val failure = runCatching {
            publishRemoteTextAtomically("/work/file", "new".toByteArray(),
                upload = { path, _ -> files[path] = "partial"; throw InterruptedIOException("cancelled") },
                publish = { _, _ -> error("Cancelled content must not publish") },
                removeTemporary = { cleanupRequests++; files.remove(it) },
            )
        }.exceptionOrNull()
        assertTrue(failure is InterruptedIOException)
        assertEquals("old", files["/work/file"])
        assertEquals(0, cleanupRequests)
        assertEquals(2, files.size)
    }
}
