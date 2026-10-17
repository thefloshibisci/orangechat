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
    val wifiOnly: Boolean = true,
    val lastSuccessTime: Long = 0,
    val lastError: String = "",
) {
    fun scheduleConfig() = copy(lastSuccessTime = 0, lastError = "")
}
