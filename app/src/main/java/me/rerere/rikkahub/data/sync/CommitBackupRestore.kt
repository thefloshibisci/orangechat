package me.rerere.rikkahub.data.sync

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Both DataStore write locks must remain held through preparation, commit and any rollback. */
internal suspend fun <S, P> commitBackupRestore(
    transaction: BackupRestoreTransaction,
    files: List<String>,
    databases: List<String>,
    settingsSession: RestorePreferencesSession<S>,
    settings: S?,
    pluginSession: RestorePreferencesSession<P>?,
    plugins: P?,
) {
    currentCoroutineContext().ensureActive()
    // Cancellation may stop extraction, but must not interrupt commit or recovery halfway through.
    withContext(NonCancellable) {
        try {
            val preferences = buildList {
                if (settings != null) add(transaction.relativePath(settingsSession.file))
                if (plugins != null) add(transaction.relativePath(requireNotNull(pluginSession).file))
            }
            transaction.prepare(files, preferences, databases)
            transaction.installFiles()
            if (settings != null) settingsSession.apply(settings)
            if (plugins != null) requireNotNull(pluginSession).apply(plugins)
            transaction.finish(databases.isNotEmpty())
        } catch (error: Throwable) {
            if (transaction.isPrepared) {
                transaction.beginRollback()
                var recoveryFailed = false
                suspend fun recover(block: suspend () -> Unit) {
                    try { block() } catch (recovery: Throwable) {
                        error.addSuppressed(recovery)
                        recoveryFailed = true
                    }
                }
                if (plugins != null) recover { requireNotNull(pluginSession).rollback() }
                if (settings != null) recover { settingsSession.rollback() }
                recover { transaction.rollbackFiles() }
                // Keep all snapshots for startup recovery if even one rollback step failed.
                if (!recoveryFailed) transaction.discard()
            } else {
                transaction.discardUnprepared()
            }
            throw error
        }
    }
}
