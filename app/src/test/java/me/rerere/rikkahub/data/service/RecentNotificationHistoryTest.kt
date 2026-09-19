package me.rerere.rikkahub.data.service

import org.junit.Assert.*
import org.junit.Test

class RecentNotificationHistoryTest {
    private val now = 100_000_000L
    private fun notification(key: String, time: Long = now, title: String = "message") =
        NotificationData(key, 1, "example.app", "App", title, "body", time)

    @Test fun `dismissed notification remains in recent history`() {
        val observed = notification("one")
        assertEquals(listOf(observed), mergeRecentNotifications(listOf(observed), emptyList(), now))
    }

    @Test fun `refresh replaces same key without duplicating and respects tags`() {
        val first = notification("tag-one")
        val second = notification("tag-two")
        val updated = first.copy(title = "updated")
        val result = mergeRecentNotifications(listOf(first, second), listOf(updated), now)
        assertEquals(2, result.size)
        assertEquals("updated", result.first { it.key == "tag-one" }.title)
    }

    @Test fun `history is bounded and excludes expired and future entries`() {
        val incoming = (1..600).map { notification("$it", now - it) } +
            notification("old", now - 86_400_001L) + notification("future", now + 1)
        val result = mergeRecentNotifications(emptyList(), incoming, now)
        assertEquals(500, result.size)
        assertFalse(result.any { it.key == "old" || it.key == "future" })
    }
}
