package me.rerere.rikkahub.data.sync

import me.rerere.rikkahub.data.datastore.AutoBackupConfig
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId

/** Calendar days preserve the chosen local time across DST and delayed background execution. */
internal fun nextAutoBackupTime(
    config: AutoBackupConfig,
    nowMillis: Long = System.currentTimeMillis(),
    zone: ZoneId = ZoneId.systemDefault(),
): Long {
    val now = Instant.ofEpochMilli(nowMillis).atZone(zone)
    val time = LocalTime.of(config.backupHour.coerceIn(0, 23), config.backupMinute.coerceIn(0, 59))
    val hours = config.intervalHours.coerceIn(1, 8760)
    if (hours % 24L == 0L) {
        val last = config.lastSuccessTime.takeIf { it in 1..nowMillis }
        var date = if (last != null) Instant.ofEpochMilli(last).atZone(zone).toLocalDate().plusDays(hours / 24)
            else now.toLocalDate()
        while (!date.atTime(time).atZone(zone).isAfter(now)) {
            date = date.plusDays(if (last == null) 1 else hours / 24)
        }
        return date.atTime(time).atZone(zone).toInstant().toEpochMilli()
    }
    var candidate = now.toLocalDate().minusDays(1).atTime(time).atZone(zone)
    while (!candidate.isAfter(now)) candidate = candidate.plusHours(hours)
    return candidate.toInstant().toEpochMilli()
}

/** Stable across process restarts; status-only edits must not postpone queued or overdue work. */
internal fun AutoBackupConfig.scheduleTag(zone: ZoneId = ZoneId.systemDefault()): String {
    val key = listOf(destination.name, directoryUri, intervalHours.toString(), backupHour.toString(),
        backupMinute.toString(), wifiOnly.toString(), zone.id).joinToString("\u0000")
    val digest = java.security.MessageDigest.getInstance("SHA-256").digest(key.toByteArray(Charsets.UTF_8))
    return "auto-backup-clock:" + digest.joinToString("") { "%02x".format(it) }
}
