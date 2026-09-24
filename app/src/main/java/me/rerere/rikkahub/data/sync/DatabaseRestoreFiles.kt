package me.rerere.rikkahub.data.sync

import java.io.File

internal object DatabaseRestoreFiles {
    fun replace(
        pendingDir: File,
        databaseDir: File,
        backupDir: File,
        names: List<String>,
        move: (File, File) -> Boolean = { from, to -> from.renameTo(to) },
    ) {
        check(!backupDir.exists() || backupDir.listFiles()?.isEmpty() == true) {
            "An earlier database restore backup still needs recovery"
        }
        check(backupDir.isDirectory || backupDir.mkdirs()) { "Unable to create database backup directory" }
        val backedUp = mutableListOf<String>()
        val installed = mutableListOf<String>()
        try {
            names.forEach { name ->
                val target = File(databaseDir, name)
                if (target.exists()) {
                    check(move(target, File(backupDir, name))) { "Unable to back up database file: $name" }
                    backedUp += name
                }
            }
            names.forEach { name ->
                val staged = File(pendingDir, name)
                if (staged.exists()) {
                    check(move(staged, File(databaseDir, name))) { "Unable to install database file: $name" }
                    installed += name
                }
            }
        } catch (error: Throwable) {
            // Only undo completed moves; an original file may still be outside the backup directory.
            installed.asReversed().forEach { name ->
                runCatching {
                    check(move(File(databaseDir, name), File(pendingDir, name))) {
                        "Unable to return staged database file: $name"
                    }
                }.onFailure(error::addSuppressed)
            }
            backedUp.asReversed().forEach { name ->
                runCatching {
                    check(move(File(backupDir, name), File(databaseDir, name))) {
                        "Unable to recover original database file: $name"
                    }
                }.onFailure(error::addSuppressed)
            }
            throw error
        }
    }
}
