package me.rerere.rikkahub.data.datastore

import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class AutoBackupConfigTest {
    @Test fun `old configurations default to disabled and require a destination`() {
        val config = Json.decodeFromString<AutoBackupConfig>("{}")
        assertFalse(config.enabled)
        assertEquals("", config.directoryUri)
        assertEquals(24L, config.intervalHours)
        assertEquals(3, config.backupHour)
        assertEquals(0, config.backupMinute)
    }

    @Test fun `status updates do not reset the periodic schedule`() {
        val config = AutoBackupConfig(enabled = true, directoryUri = "content://backups")
        assertEquals(config.scheduleConfig(), config.copy(lastSuccessTime = 123, lastError = "error").scheduleConfig())
        assertNotEquals(config.scheduleConfig(), config.copy(intervalHours = 6).scheduleConfig())
        assertNotEquals(config.scheduleConfig(), config.copy(enabled = false).scheduleConfig())
        assertNotEquals(config.scheduleConfig(), config.copy(backupHour = 4).scheduleConfig())
        assertNotEquals(config.scheduleConfig(), config.copy(backupMinute = 30).scheduleConfig())
    }

    @Test fun `restoring never enables a schedule or reuses a folder grant from another device`() {
        val original = AutoBackupConfig(
            enabled = true,
            destination = AutoBackupDestination.LOCAL,
            directoryUri = "content://old-device/backups",
            intervalHours = 72,
            backupHour = 22,
            backupMinute = 15,
            wifiOnly = false,
            lastSuccessTime = 123,
            lastError = "old failure",
        )
        val restored = Json.decodeFromString<AutoBackupConfig>(Json.encodeToString(original)).forRestore()

        assertFalse(restored.enabled)
        assertEquals("", restored.directoryUri)
        assertEquals(0L, restored.lastSuccessTime)
        assertEquals("", restored.lastError)
        assertEquals(original.destination, restored.destination)
        assertEquals(original.intervalHours, restored.intervalHours)
        assertEquals(original.wifiOnly, restored.wifiOnly)
        assertEquals(original.backupHour, restored.backupHour)
        assertEquals(original.backupMinute, restored.backupMinute)
        assertTrue(original.enabled)
    }

    @Test fun `backup status edits preserve simultaneous UI and scheduling changes`() {
        val previous = Settings(autoBackupConfig = AutoBackupConfig(enabled = true, directoryUri = "content://backups"))
        val edited = previous.copy(autoBackupConfig = previous.autoBackupConfig.copy(intervalHours = 6))
        val current = previous.copy(
            displaySetting = previous.displaySetting.copy(fontSizeRatio = 1.2f),
            autoBackupConfig = previous.autoBackupConfig.copy(lastSuccessTime = 123, lastError = ""),
        )

        val merged = current.withSettingsEdit(previous, edited)

        assertEquals(6L, merged.autoBackupConfig.intervalHours)
        assertEquals(123L, merged.autoBackupConfig.lastSuccessTime)
        assertEquals(current.displaySetting, merged.displaySetting)
    }
}
