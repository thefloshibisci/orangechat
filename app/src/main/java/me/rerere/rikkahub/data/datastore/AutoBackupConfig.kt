package me.rerere.rikkahub.data.datastore

import kotlinx.serialization.Serializable

@Serializable
enum class AutoBackupDestination { LOCAL, WEBDAV, S3 }

@Serializable
data class AutoBackupConfig(
    val enabled: Boolean = false,
    val destination: AutoBackupDestination = AutoBackupDestination.LOCAL,
    val directoryUri: String = "",
    val intervalHours: Long = 24,
    val backupHour: Int = 3,
    val backupMinute: Int = 0,
    val wifiOnly: Boolean = true,
    val lastSuccessTime: Long = 0,
    val lastError: String = "",
) {
    fun scheduleConfig() = copy(lastSuccessTime = 0, lastError = "")

    /** Restoring an archive never grants folder access or opts this device into scheduled writes. */
    fun forRestore() = copy(enabled = false, directoryUri = "", lastSuccessTime = 0, lastError = "")
}
