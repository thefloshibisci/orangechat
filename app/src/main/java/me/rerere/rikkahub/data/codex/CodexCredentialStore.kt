package me.rerere.rikkahub.data.codex

import android.content.Context
import android.util.AtomicFile
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import me.rerere.rikkahub.data.security.SecretCrypto
import java.io.File

/** Keystore-backed, excluded from Android backup and every Settings export. */
internal interface CodexAccountStore {
    fun read(): CodexAccountState
    fun write(state: CodexAccountState)
}

internal class CodexCredentialStore(context: Context, private val json: Json) : CodexAccountStore {
    private val file = AtomicFile(File(context.noBackupFilesDir, "codex_accounts.enc"))

    override fun read(): CodexAccountState {
        if (!file.baseFile.exists() && !File(file.baseFile.path + ".bak").exists()) return CodexAccountState()
        return try {
            val encrypted = file.openRead().use { it.readBytes().decodeToString() }
            check(SecretCrypto.isEncrypted(encrypted))
            json.decodeFromString<CodexAccountState>(checkNotNull(SecretCrypto.decrypt(encrypted, AAD)))
        } catch (_: Exception) {
            // A temporarily unavailable Keystore must not look like an empty account list,
            // which could then overwrite the still-recoverable encrypted credentials.
            error("Codex credentials are temporarily unavailable")
        } finally {
            SecretCrypto.forget(AAD)
        }
    }

    override fun write(state: CodexAccountState) {
        try {
            if (state.accounts.isEmpty()) {
                file.delete()
                check(!file.baseFile.exists() && !File(file.baseFile.path + ".bak").exists()) {
                    "Unable to clear Codex credentials"
                }
                return
            }
            val encrypted = checkNotNull(SecretCrypto.encrypt(json.encodeToString(state), AAD))
            val output = file.startWrite()
            try {
                output.write(encrypted.encodeToByteArray())
                file.finishWrite(output)
            } catch (_: Exception) {
                file.failWrite(output)
                error("Unable to save Codex credentials securely")
            }
        } finally {
            SecretCrypto.forget(AAD)
        }
    }

    private companion object { const val AAD = "codex.accounts.v1" }
}

@Serializable
internal data class CodexAccountState(
    val accounts: List<CodexAccount> = emptyList(),
    val nextAccountIndex: Int = 0,
) {
    override fun toString(): String = "CodexAccountState(credentials=REDACTED)"
}
