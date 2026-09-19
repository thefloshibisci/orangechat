package me.rerere.rikkahub.data.service

import me.rerere.rikkahub.data.datastore.ProactiveMessageSetting
import java.time.Instant
import java.time.ZoneId

internal fun isProactiveActiveTime(setting: ProactiveMessageSetting, millis: Long, zone: ZoneId): Boolean {
    if (!setting.activeHoursEnabled) return true
    val hour = Instant.ofEpochMilli(millis).atZone(zone).hour
    val start = setting.activeStartHour.coerceIn(0, 23)
    val end = setting.activeEndHour.coerceIn(0, 23)
    return when {
        start == end -> true
        start < end -> hour in start until end
        else -> hour >= start || hour < end
    }
}

internal fun nextProactiveActiveTime(setting: ProactiveMessageSetting, millis: Long, zone: ZoneId): Long {
    if (isProactiveActiveTime(setting, millis, zone)) return millis
    val local = Instant.ofEpochMilli(millis).atZone(zone)
    var next = local.toLocalDate().atTime(setting.activeStartHour.coerceIn(0, 23), 0).atZone(zone)
    if (next.toInstant().toEpochMilli() <= millis) next = next.plusDays(1)
    return next.toInstant().toEpochMilli()
}

internal fun useFullProactiveTools(setting: ProactiveMessageSetting, roll: Int): Boolean =
    roll.coerceIn(0, 99) < setting.fullToolChancePercent.coerceIn(0, 100)

internal fun canExecuteProactiveTool(needsApproval: Boolean, forceConfirm: Boolean, autoApprove: Boolean): Boolean =
    autoApprove || (!needsApproval && !forceConfirm)
