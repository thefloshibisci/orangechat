package me.rerere.rikkahub.data.sync

import java.io.File

/** Valid only while the owning DataStore's write lock is held. */
internal class RestorePreferencesSession<T>(
    val file: File,
    val apply: suspend (T) -> Unit,
    val rollback: suspend () -> Unit,
)
