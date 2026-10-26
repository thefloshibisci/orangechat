package me.rerere.workspace.ssh

import com.jcraft.jsch.HostKeyRepository
import com.jcraft.jsch.HostKey
import me.rerere.workspace.WorkspaceLayout
import me.rerere.workspace.WorkspaceStorageArea
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.util.Base64

class SshWorkspaceContractTest {
    @Test fun unknownHostKeyIsRejectedBeforeAnyTrustCanBeAdded() {
        val repository = TrustedHostKeyRepository(null)
        val blob = hostKeyBlob(1)
        assertEquals(HostKeyRepository.NOT_INCLUDED, repository.check("example.test", blob))
        val rejection = repository.rejection
        assertTrue(rejection is SshUnknownHostKeyException)
        assertEquals(key(blob), (rejection as SshUnknownHostKeyException).hostKey)
        repository.add(HostKey("example.test", blob), null)
        assertEquals(HostKeyRepository.NOT_INCLUDED, repository.check("example.test", blob))
    }

    @Test fun onlyPinnedKeyAndItsAlgorithmAreAccepted() {
        val trusted = key(hostKeyBlob(1))
        val repository = TrustedHostKeyRepository(trusted)
        assertEquals(HostKeyRepository.OK, repository.check("example.test", hostKeyBlob(1)))
        assertNull(repository.rejection)
        assertEquals(1, repository.getHostKey("example.test", "ssh-ed25519").size)
        assertTrue(repository.getHostKey("example.test", "ssh-rsa").isEmpty())
        val inconsistent = TrustedHostKeyRepository(trusted.copy(type = "ssh-rsa"))
        assertEquals(HostKeyRepository.CHANGED, inconsistent.check("example.test", hostKeyBlob(1)))
    }

    @Test fun changedHostKeyExposesBothFingerprintsAndDoesNotReplaceThePin() {
        val trusted = key(hostKeyBlob(1))
        val repository = TrustedHostKeyRepository(trusted)
        assertEquals(HostKeyRepository.CHANGED, repository.check("example.test", hostKeyBlob(2)))
        val error = repository.rejection as SshHostKeyChangedException
        assertEquals(trusted, error.expected)
        assertEquals(key(hostKeyBlob(2)), error.actual)
        assertTrue(error.message!!.contains(trusted.fingerprint))
        assertEquals(HostKeyRepository.OK, repository.check("example.test", hostKeyBlob(1)))
    }

    @Test fun fingerprintMatchesOpenSshSha256Format() {
        val key = SshHostKey("fixture", "YWJj")
        assertEquals("SHA256:ungWv48Bz+pBQUDeXa4iI7ADYaOWF3qctBD/YfIAFa0", key.fingerprint)
        assertFalse(key.fingerprint.endsWith('='))
    }

    @Test fun malformedHostKeysAndInvalidEndpointsFailBeforeConnecting() {
        for (invalid in listOf("", "not base64!", "a".repeat(25 * 1024))) {
            assertTrue(runCatching { SshHostKey("ssh-ed25519", invalid) }.exceptionOrNull() is IllegalArgumentException)
        }
        for (port in listOf(-1, 0, 65536)) {
            assertTrue(runCatching { config().copy(port = port) }.exceptionOrNull() is IllegalArgumentException)
        }
        assertTrue(runCatching { config().copy(host = "host\ninjected") }.isFailure)
        assertTrue(runCatching { config().copy(username = "") }.isFailure)
        assertTrue(runCatching { config().copy(remoteDir = "relative") }.isFailure)
    }

    @Test fun credentialTextNeverAppearsInConfigurationDescription() {
        val value = config().copy(password = "PASSWORD-SENTINEL", privateKey = "PRIVATE-KEY-SENTINEL",
            passphrase = "PASSPHRASE-SENTINEL", hostKey = key(hostKeyBlob(1)))
        val description = value.toString()
        for (secret in listOf(value.password, value.privateKey, value.passphrase)) assertFalse(description.contains(secret!!))
        assertTrue(description.contains("user@example.test:22"))
        assertTrue(description.contains(value.hostKey!!.fingerprint))
    }

    @Test fun normalizationPreservesLiteralPosixFileNames() {
        assertEquals("/work/ note ", normalizeAbsolute("/work/ note "))
        assertEquals("/work/a\\b?*.txt", resolveRemotePath("/work", "a\\b?*.txt"))
        assertEquals("/work/x", resolveRemotePath("/work/.", "folder/../x"))
        assertEquals("/x", normalizeAbsolute("/../../x"))
        assertTrue(runCatching { normalizeAbsolute("relative") }.isFailure)
        assertTrue(runCatching { normalizeAbsolute("/bad\u0000name") }.isFailure)
    }

    @Test fun fileAreaCannotEscapeToAParentOrSiblingDirectory() {
        for (path in listOf("../secret", "nested/../../secret", "../workspace-other/file")) {
            assertTrue(path, runCatching { resolveRemotePath("/workspace", path) }.exceptionOrNull() is IllegalArgumentException)
        }
        assertEquals("/etc/config", resolveRemotePath("/", "etc/config"))
        assertEquals("/workspace/etc/config", resolveRemotePath("/workspace", "/etc/config"))
    }

    @Test fun uploadNameCannotChangeItsChosenDestinationDirectory() {
        for (name in listOf("", ".", "..", "../x", "nested/file", "bad\u0000name")) {
            assertTrue(name, runCatching { validateRemoteFileName(name) }.isFailure)
        }
        listOf("note.md", " spaced file ", "a\\b?*.txt").forEach(::validateRemoteFileName)
    }

    @Test fun workspaceLayoutChecksCompletePathSegmentsAndNormalizedTraversal() {
        val layout = WorkspaceLayout("/work", listOf("/work", "/tmp"))
        assertEquals("/workshop/file", layout.relativeToWorkspace("/workshop/file"))
        assertEquals("file", layout.relativeToWorkspace("/work/file"))
        assertEquals("", layout.relativeToWorkspace("/work"))
        assertFalse(layout.isWritable("/workshop/file"))
        assertFalse(layout.isWritable("/work/../etc/config"))
        assertFalse(layout.isWritable("relative"))
        assertTrue(layout.isWritable("/work/a/../b"))
        assertEquals("/work/file", layout.absolutePath(WorkspaceStorageArea.FILES, "file"))
        val root = SshWorkspaceBackend.layoutOf("/")
        assertEquals("/file", root.absolutePath(WorkspaceStorageArea.FILES, "file"))
        assertTrue(root.isWritable("/etc/config"))
    }

    @Test fun shellAndSftpQuotingKeepUserFileNamesLiteral() {
        assertEquals("'a'\"'\"'b'", "a'b".shellQuote())
        assertEquals("a\\\\b\\?\\*.txt", "a\\b?*.txt".sftpQuote())
        assertEquals("'line one\nline two'", "line one\nline two".shellQuote())
    }

    private fun config() = SshConnectionConfig(
        host = "example.test", username = "user", authType = SshAuthType.PASSWORD, password = "password", remoteDir = "/work",
    )

    private fun key(blob: ByteArray) = SshHostKey("ssh-ed25519", Base64.getEncoder().encodeToString(blob))

    private fun hostKeyBlob(seed: Int) = ByteArrayOutputStream().also { bytes ->
        DataOutputStream(bytes).use { out ->
            val algorithm = "ssh-ed25519".toByteArray()
            out.writeInt(algorithm.size); out.write(algorithm)
            out.writeInt(32); out.write(ByteArray(32) { seed.toByte() })
        }
    }.toByteArray()
}
