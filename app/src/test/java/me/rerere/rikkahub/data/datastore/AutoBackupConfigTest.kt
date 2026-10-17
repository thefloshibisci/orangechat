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
    }

    @Test fun `status updates do not reset the periodic schedule`() {
        val config = AutoBackupConfig(enabled = true, directoryUri = "content://backups")
        assertEquals(config.scheduleConfig(), config.copy(lastSuccessTime = 123, lastError = "error").scheduleConfig())
        assertNotEquals(config.scheduleConfig(), config.copy(intervalHours = 6).scheduleConfig())
        assertNotEquals(config.scheduleConfig(), config.copy(enabled = false).scheduleConfig())
    }
}
