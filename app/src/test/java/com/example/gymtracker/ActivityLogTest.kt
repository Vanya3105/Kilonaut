package com.example.gymtracker

import org.junit.Assert.assertEquals
import org.junit.Test

/** The activity kept on the phone: newest first, capped, and read back as written. */
class ActivityLogTest {
    private fun item(at: Long, type: String = "like") = ActivityLog.Item(type, "u$at", "hi", "owner", "p$at", at)

    @Test
    fun newestFirstAndCapped() {
        val old = (1L..79L).map { item(it) }.sortedByDescending { it.at }
        val merged = ActivityLog.merged(old, listOf(item(100), item(90)))
        assertEquals(80, merged.size)
        assertEquals(listOf(100L, 90L, 79L), merged.take(3).map { it.at })
    }

    @Test
    fun likesOnTheSamePostBecomeOneRow() {
        val like = { from: String, post: String, at: Long -> ActivityLog.Item("like", from, "", "me", post, at) }
        val items = listOf(
            like("maya", "p1", 9), ActivityLog.Item("comment", "dan", "nice", "me", "p1", 8),
            like("dan", "p1", 7), like("noa", "p2", 6), like("noa", "p1", 5), like("maya", "p1", 4)
        )
        val groups = ActivityLog.grouped(items)
        assertEquals(listOf("like", "comment", "like"), groups.map { it.item.type })
        // The newest like leads; each other person once.
        assertEquals("maya", groups[0].item.from)
        assertEquals(listOf("dan", "noa"), groups[0].others)
        assertEquals(emptyList<String>(), groups[2].others)
    }

    @Test
    fun headings() {
        val now = java.util.Calendar.getInstance().apply { set(2026, 10, 10, 15, 0) }.timeInMillis
        val hour = 3_600_000L
        assertEquals(0, ActivityLog.bucketOf(now - 2 * hour, now))
        assertEquals(1, ActivityLog.bucketOf(now - 20 * hour, now))
        assertEquals(2, ActivityLog.bucketOf(now - 9 * 24 * hour, now))
    }

    @Test
    fun roundTrips() {
        val items = listOf(item(5, "follow").copy(accepted = true), item(3, "reply"))
        assertEquals(items, ActivityLog.parse(ActivityLog.encode(items)))
        assertEquals(emptyList<ActivityLog.Item>(), ActivityLog.parse("not json"))
    }
}
