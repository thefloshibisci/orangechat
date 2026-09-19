package me.rerere.rikkahub.data.service

internal fun mergeRecentNotifications(
    previous: List<NotificationData>,
    incoming: List<NotificationData>,
    now: Long,
): List<NotificationData> = (incoming + previous)
    .filter { it.timestamp in (now - 86_400_000L)..now }
    .distinctBy { it.key }
    .sortedByDescending { it.timestamp }
    .take(500)
