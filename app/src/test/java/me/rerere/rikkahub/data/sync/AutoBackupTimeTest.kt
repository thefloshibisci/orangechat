package me.rerere.rikkahub.data.sync

import me.rerere.rikkahub.data.datastore.AutoBackupConfig
import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class AutoBackupTimeTest {
    private fun millis(value: String) = Instant.parse(value).toEpochMilli()
    private fun check(now: String, expected: String, config: AutoBackupConfig = AutoBackupConfig(),
                      zone: String = "Asia/Shanghai") {
        assertEquals(millis(expected), nextAutoBackupTime(config, millis(now), ZoneId.of(zone)))
    }

    @Test fun firstBackupUsesNextLocalThreeAm() {
        check("2026-10-20T17:00:00Z", "2026-10-20T19:00:00Z")
        check("2026-10-20T20:00:00Z", "2026-10-21T19:00:00Z")
    }
    @Test fun threeDayIntervalDoesNotDelayTheFirstBackupByThreeDays() {
        check("2026-10-20T20:00:00Z", "2026-10-21T19:00:00Z", AutoBackupConfig(intervalHours = 72))
    }
    @Test fun delayedExecutionDoesNotMoveTheDailyClock() {
        check("2026-10-20T22:00:00Z", "2026-10-21T19:00:00Z",
            AutoBackupConfig(lastSuccessTime = millis("2026-10-20T22:00:00Z")))
    }
    @Test fun threeDayIntervalSurvivesRestart() {
        check("2026-10-21T21:00:00Z", "2026-10-23T19:00:00Z",
            AutoBackupConfig(intervalHours = 72, lastSuccessTime = millis("2026-10-20T19:05:00Z")))
    }
    @Test fun sixHourIntervalUsesChosenClockAsAnchor() {
        check("2026-10-20T20:00:00Z", "2026-10-21T01:00:00Z", AutoBackupConfig(intervalHours = 6))
        check("2026-10-21T01:00:00Z", "2026-10-21T07:00:00Z", AutoBackupConfig(intervalHours = 6))
    }
    @Test fun changedClockIsUsed() {
        check("2026-10-20T17:00:00Z", "2026-10-20T20:30:00Z",
            AutoBackupConfig(backupHour = 4, backupMinute = 30))
    }
    @Test fun dailyBackupKeepsLocalTimeAcrossDaylightSaving() {
        check("2026-03-07T09:00:00Z", "2026-03-08T07:00:00Z",
            AutoBackupConfig(lastSuccessTime = millis("2026-03-07T08:00:00Z")), "America/New_York")
        check("2026-10-31T09:00:00Z", "2026-11-01T08:00:00Z",
            AutoBackupConfig(lastSuccessTime = millis("2026-10-31T07:00:00Z")), "America/New_York")
    }
    @Test fun futureSuccessTimestampDoesNotPostponeBackupIndefinitely() {
        check("2026-10-20T17:00:00Z", "2026-10-20T19:00:00Z",
            AutoBackupConfig(lastSuccessTime = millis("2099-01-01T00:00:00Z")))
    }
    @Test fun scheduleIdentitySurvivesRestartAndStatusChangesButTracksClockAndDestination() {
        val zone = ZoneId.of("Asia/Shanghai")
        val config = AutoBackupConfig(enabled = true, directoryUri = "content://folder")
        val restored = config.copy(lastSuccessTime = 123, lastError = "temporary failure")
        assertEquals(config.scheduleTag(zone), restored.scheduleTag(zone))
        assertNotEquals(config.scheduleTag(zone), config.copy(backupMinute = 30).scheduleTag(zone))
        assertNotEquals(config.scheduleTag(zone), config.copy(directoryUri = "content://other").scheduleTag(zone))
        assertNotEquals(config.scheduleTag(zone), config.scheduleTag(ZoneId.of("UTC")))
    }

}
